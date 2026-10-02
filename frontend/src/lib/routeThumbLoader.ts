import * as api from "@/api/endpoints";
import { routeShape, type RouteShape } from "@/lib/routeThumb";

/**
 * Session cache + concurrency cap for trip-row route thumbnails. A shape is
 * fetched once per trip id; at most MAX_INFLIGHT /trips/{id}/route requests
 * run at a time so a 50-row page doesn't fan out 50 requests.
 */
const cache = new Map<string, Promise<RouteShape | null>>();
const MAX_INFLIGHT = 4;
let inflight = 0;
const queue: Array<() => void> = [];

function runLimited<T>(fn: () => Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const go = () => {
      inflight++;
      fn()
        .then(resolve, reject)
        .finally(() => {
          inflight--;
          queue.shift()?.();
        });
    };
    if (inflight < MAX_INFLIGHT) go();
    else queue.push(go);
  });
}

export function loadRouteShape(tripId: string): Promise<RouteShape | null> {
  let p = cache.get(tripId);
  if (!p) {
    p = runLimited(() => api.getTripRoute(tripId))
      .then((r) => routeShape(r.points))
      .catch(() => {
        cache.delete(tripId); // let a later mount retry a transient failure
        return null;
      });
    cache.set(tripId, p);
  }
  return p;
}
