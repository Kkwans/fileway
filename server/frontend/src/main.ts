import { disableExternal } from "@/utils/constants";
import { createApp } from "vue";
import VueNumberInput from "@chenfengyuan/vue-number-input";
import VueLazyload from "vue-lazyload";
import Toast, { POSITION, useToast } from "vue-toastification";
import type {
  ToastOptions,
  PluginOptions,
} from "vue-toastification/dist/types/types";
import createPinia from "@/stores";
import router from "@/router";
import App from "@/App.vue";
import CustomToast from "@/components/CustomToast.vue";
import { T } from "@/utils/translations";

import "@/utils/date";

import "./css/styles.css";

const pinia = createPinia(router);

const app = createApp(App);

app.component(VueNumberInput.name || "vue-number-input", VueNumberInput);
app.use(VueLazyload);
app.use(Toast, {
  transition: "Vue-Toastification__bounce",
  maxToasts: 10,
  newestOnTop: true,
} satisfies PluginOptions);

app.use(pinia);
app.use(router);

// 全局 t 函数，供所有 Vue 组件模板使用（替代 vue-i18n）
// 支持嵌套键：t('buttons.save') → "保存"
app.config.globalProperties.t = (
  key: string,
  opts?: Record<string, any>
): string => {
  const keys = key.split(".");
  let result: any = T;
  for (const k of keys) {
    result = result?.[k];
    if (result === undefined) break;
  }
  let text = typeof result === "string" ? result : key;
  if (opts) {
    for (const [k, v] of Object.entries(opts)) {
      text = text.replace(new RegExp(`\\{\\s*${k}\\s*\\}`, "g"), String(v));
    }
  }
  return text;
};

// provide v-focus for components
app.directive("focus", {
  mounted: async (el) => {
    // initiate focus for the element
    el.focus();
  },
});

const toastConfig = {
  position: POSITION.BOTTOM_CENTER,
  timeout: 2000,
  closeOnClick: true,
  pauseOnFocusLoss: true,
  pauseOnHover: true,
  draggable: true,
  draggablePercent: 0.6,
  showCloseButtonOnHover: false,
  hideProgressBar: false,
  closeButton: "button",
  icon: true,
} satisfies ToastOptions;

const timeoutFor = (
  options?: {
    importance?: ToastImportance;
    timeout?: number;
    persistent?: boolean;
  },
  fallback = 2000
) => {
  if (options?.persistent) return 0;
  if (typeof options?.timeout === "number") return options.timeout;
  if (options?.importance === "minor") return 1500;
  if (options?.importance === "important") return 5000;
  return fallback;
};

app.provide(
  "$showSuccess",
  (message: string, options?: ToastFeedbackOptions) => {
    const $toast = useToast();
    $toast.success(
      {
        component: CustomToast,
        props: {
          message: message,
        },
      },
      { ...toastConfig, timeout: timeoutFor(options), rtl: false }
    );
  }
);

app.provide(
  "$showAction",
  (
    message: string,
    actionLabel: string,
    action: () => void | Promise<void>,
    options?: ToastFeedbackOptions | number
  ) => {
    const $toast = useToast();
    const normalized =
      typeof options === "number" ? { timeout: options } : options;
    $toast.success(
      {
        component: CustomToast,
        props: { message, actionLabel, onAction: action },
      },
      {
        ...toastConfig,
        timeout: timeoutFor(normalized, 3000),
        closeOnClick: false,
        rtl: false,
      }
    );
  }
);

app.provide(
  "$showError",
  (
    error: Error | string,
    displayReport = true,
    options?: ToastFeedbackOptions
  ) => {
    const $toast = useToast();
    $toast.error(
      {
        component: CustomToast,
        props: {
          message: (error as Error).message || error,
          isReport: !disableExternal && displayReport,
          // TODO: could you add this to the component itself?
          reportText: "报告问题",
        },
      },
      {
        ...toastConfig,
        timeout: timeoutFor(options, 5000),
        rtl: false,
      }
    );
  }
);

router.isReady().then(() => app.mount("#app"));
