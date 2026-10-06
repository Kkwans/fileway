export {};

export interface IUser {
  id: number;
  username: string;
  password: string;
  scope: string;
  locale: string;
  perm: Permissions;
  commands: string[];
  rules: IRule[];
  lockPassword: boolean;
  hideDotfiles: boolean;
  singleClick: boolean;
  redirectAfterCopyMove: boolean;
  dateFormat: boolean;
  viewMode: ViewModeType;
  sorting?: Sorting;
  aceEditorTheme: string;
  sidebarPreferences?: string;
  listingPreferences?: ListingPreferences;
  playerPreferences?: PlayerPreferences;
}

export type ViewModeType =
  | "mosaic"
  | "compact-grid"
  | "windows-icons"
  | "details"
  | "compact-list";

export interface Permissions {
  admin: boolean;
  copy: boolean;
  create: boolean;
  delete: boolean;
  download: boolean;
  execute: boolean;
  modify: boolean;
  move: boolean;
  rename: boolean;
  share: boolean;
  shell: boolean;
  upload: boolean;
}

export interface Sorting {
  by: string;
  asc: boolean;
}

export interface PrefixRule {
  prefix: string;
  visible: boolean;
  expanded: boolean;
  order: number;
}

export interface ListingPreferences {
  version: number;
  prefixRules: PrefixRule[];
}

export interface PlayerPreferences {
  /** nil/undefined = default 4s; 0 = never hide; 1-20 = seconds */
  controlsTimeoutSec?: number | null;
  /** native | compat | ask */
  playbackMode?: string | null;
  /** 0.10–5.00 */
  playbackRate?: number | null;
  /** resume | from-start | ask — 进页续播策略 */
  resumeMode?: string | null;
  /** 续播提示/跳转阈值（秒）；默认 10；范围 5–600 */
  resumeMinSec?: number | null;
}

interface IRule {
  allow: boolean;
  path: string;
  regex: boolean;
  regexp: IRegexp;
}

interface IRegexp {
  raw: string;
}

export type UserTheme = "light" | "dark" | "";
