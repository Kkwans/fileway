import { partial } from "filesize";

/**
 * 使用 Windows 风格的 B/KB/MB/GB/TB 单位格式化大小。
 */
export const filesize = partial({ base: 2, standard: "jedec" });

export const vClickOutside = {
  created(el: HTMLElement, binding: { value: (event: Event) => void }) {
    el.clickOutsideEvent = (event: Event) => {
      const target = event.target;

      if (target instanceof Node) {
        if (!el.contains(target)) {
          binding.value(event);
        }
      }
    };

    document.addEventListener("click", el.clickOutsideEvent);
  },

  unmounted(el: HTMLElement) {
    if (el.clickOutsideEvent) {
      document.removeEventListener("click", el.clickOutsideEvent);
    }
  },
};
