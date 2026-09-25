import { nextTick, onBeforeUnmount, watch, type Ref } from "vue";

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modal accessibility in one place: Esc closes, Tab/Shift-Tab cycle inside
 * the dialog, focus lands on `[autofocus]` (or the first focusable) when it
 * opens and goes back to whatever had it when it closes.
 *
 * `root` is the dialog panel element; `open` drives activation.
 */
export function useModalA11y(
  open: Ref<boolean> | (() => boolean),
  root: Ref<HTMLElement | null>,
  onClose: () => void,
) {
  let restoreTo: HTMLElement | null = null;
  const isOpen = () => (typeof open === "function" ? open() : open.value);

  function focusables(): HTMLElement[] {
    if (!root.value) return [];
    return Array.from(root.value.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      (el) => el.offsetParent !== null || el === document.activeElement,
    );
  }

  function onKey(e: KeyboardEvent) {
    if (!isOpen()) return;
    if (e.key === "Escape") {
      e.stopPropagation();
      e.preventDefault();
      onClose();
      return;
    }
    if (e.key !== "Tab") return;
    const els = focusables();
    if (els.length === 0) {
      e.preventDefault();
      return;
    }
    const first = els[0];
    const last = els[els.length - 1];
    const active = document.activeElement as HTMLElement | null;
    if (e.shiftKey && (active === first || !root.value?.contains(active))) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && (active === last || !root.value?.contains(active))) {
      e.preventDefault();
      first.focus();
    }
  }

  async function activate() {
    restoreTo = document.activeElement as HTMLElement | null;
    document.addEventListener("keydown", onKey, true);
    await nextTick();
    const target =
      root.value?.querySelector<HTMLElement>("[autofocus]") ?? focusables()[0] ?? root.value;
    target?.focus?.();
  }
  function deactivate() {
    document.removeEventListener("keydown", onKey, true);
    if (restoreTo && document.contains(restoreTo)) restoreTo.focus?.();
    restoreTo = null;
  }

  watch(
    () => isOpen(),
    (v) => (v ? void activate() : deactivate()),
    { immediate: true },
  );
  onBeforeUnmount(deactivate);
}
