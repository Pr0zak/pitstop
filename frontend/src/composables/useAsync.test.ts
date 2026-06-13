import { describe, it, expect } from "vitest";
import { useAsync } from "./useAsync";

/** A deferred promise we can resolve/reject from the outside. */
function defer<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("useAsync", () => {
  it("populates data on a successful load", async () => {
    const { data, loading, error, reload } = useAsync(async () => 42, [], {
      immediate: false,
    });
    expect(data.value).toBeNull();
    const p = reload();
    expect(loading.value).toBe(true);
    await p;
    expect(loading.value).toBe(false);
    expect(error.value).toBeNull();
    expect(data.value).toBe(42);
  });

  it("captures the error message on failure", async () => {
    const { data, error, reload } = useAsync(
      async () => {
        throw new Error("boom");
      },
      [],
      { immediate: false },
    );
    await reload();
    expect(error.value).toBe("boom");
    expect(data.value).toBeNull();
  });

  it("ignores a stale (out-of-order) response — newer call wins", async () => {
    const first = defer<number>();
    const second = defer<number>();
    const queue = [first, second];
    let i = 0;
    const { data, reload } = useAsync(() => queue[i++].promise, [], {
      immediate: false,
    });

    // Fire two reloads; the second is the "newest" and must win even if
    // the first promise resolves afterwards.
    const p1 = reload();
    const p2 = reload();

    // Resolve the SECOND (newest) call first.
    second.resolve(2);
    await p2;
    expect(data.value).toBe(2);

    // Now resolve the FIRST (stale) call — it must be discarded.
    first.resolve(1);
    await p1;
    expect(data.value).toBe(2);
  });

  it("ignores a stale error after a newer success", async () => {
    const first = defer<number>();
    const second = defer<number>();
    const queue = [first, second];
    let i = 0;
    const { data, error, reload } = useAsync(() => queue[i++].promise, [], {
      immediate: false,
    });

    const p1 = reload();
    const p2 = reload();

    second.resolve(99);
    await p2;
    expect(data.value).toBe(99);

    // Stale call rejects late — must NOT overwrite error/data.
    first.reject(new Error("stale failure"));
    await p1.catch(() => {});
    expect(error.value).toBeNull();
    expect(data.value).toBe(99);
  });
});
