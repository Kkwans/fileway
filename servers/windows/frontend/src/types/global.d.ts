export {};

declare global {
  interface Window {
    FileBrowser: {
      Name: string;
      DisableExternal: boolean;
      DisableUsedPercentage: boolean;
      BaseURL: string;
      StaticURL: string;
      ReCaptcha: string;
      ReCaptchaKey: string;
      Signup: boolean;
      Version: string;
      NoAuth: boolean;
      AuthMethod: string;
      LogoutPage: string;
      LoginPage: boolean;
      Theme: string;
      EnableThumbs: boolean;
      ResizePreview: boolean;
      EnableExec: boolean;
      TusSettings: object;
      HideLoginButton: boolean;
    };
    __prependStaticUrl: (url: string) => string;
    grecaptcha: {
      ready: (cb: () => void) => void;
      render: (id: string, options: object) => string;
      getResponse: (widgetId?: string) => string;
    };
  }

  interface HTMLElement {
    clickOutsideEvent?: (event: Event) => void;
  }

  // Vditor editor instance interface (methods may be optional for mock/preview mode)
  interface VditorInstance {
    getValue: () => string;
    setValue?: (value: string) => void;
    insertValue?: (value: string) => void;
    insertMD?: (value: string) => void;
    insertEmptyBlock?: (position: "beforebegin" | "afterend") => void;
    getCurrentMode?: () => string;
    focus?: () => void;
    blur?: () => void;
    destroy: () => void;
  }

  // Permissions API type补充（部分浏览器支持但TS lib未完全覆盖）
  interface Permissions {
    query(permissionDesc: { name: string }): Promise<PermissionStatus>;
  }
}
