import type { ComponentCustomProperties } from "vue";
import { T } from "./utils/translations";

declare module "@vue/runtime-core" {
  interface ComponentCustomProperties {
    /**
     * 全局翻译函数，供所有 Vue 组件模板使用
     * 替代已移除的 vue-i18n
     * 注意：在 <script setup> 的 JS 代码中使用需通过模板或手动导入 T
     */
    t(key: string, opts?: Record<string, any>): string;
  }
}

// 全局 t 函数（供 <script setup> 中直接引用）
// 使用方式：const t = globalThis.__t;
(globalThis as any).__t = (key: string): string => {
  return (T as any)[key] ?? key;
};