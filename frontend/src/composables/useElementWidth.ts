import { onBeforeUnmount, onMounted, ref, type Ref } from "vue";

/** Live content-box width of an element (ResizeObserver). Lets an SVG chart
 *  draw in real pixels so its text never scales with the container. */
export function useElementWidth(el: Ref<HTMLElement | null>, fallback = 320): Ref<number> {
  const width = ref(fallback);
  let ro: ResizeObserver | null = null;
  onMounted(() => {
    if (!el.value) return;
    width.value = el.value.clientWidth || fallback;
    if (typeof ResizeObserver === "undefined") return;
    ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width;
      if (w && Math.abs(w - width.value) >= 1) width.value = Math.floor(w);
    });
    ro.observe(el.value);
  });
  onBeforeUnmount(() => ro?.disconnect());
  return width;
}
