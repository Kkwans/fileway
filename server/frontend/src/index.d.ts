declare module "*.vue";
declare module "*.css";
declare module "*.css?inline" {
  const css: string;
  export default css;
}

// Global t function provided by main.ts (replaces vue-i18n)

// Make this file a module (required for `declare global`)
export {};
declare global {
  interface Window {
    /** Vditor instance loaded from the local application bundle. */
    Vditor?: typeof import("vditor").default;
    /** highlight.js instance loaded from the local application bundle. */
    hljs?: typeof import("highlight.js").default;
  }
}

// Extend video.js Player interface for plugins
declare module "video.js" {
  interface Player {
    /** Mobile UI plugin for video.js */
    mobileUi?: () => void;
  }
}
