export {};

import type { ViewModeType, Sorting, Permissions, UserTheme } from "./user";

export interface ISettings {
  signup: boolean;
  createUserDir: boolean;
  hideLoginButton: boolean;
  minimumPasswordLength: number;
  userHomeBasePath: string;
  defaults: SettingsDefaults;
  authMethod: string;
  rules: Rule[];
  branding: SettingsBranding;
  tus: SettingsTus;
  shell: string[];
  commands: SettingsCommand;
  tokenExpirationTime: string;
}

interface SettingsDefaults {
  scope: string;
  locale: string;
  viewMode: ViewModeType;
  singleClick: boolean;
  redirectAfterCopyMove: boolean;
  sorting: Sorting;
  perm: Permissions;
  commands: string[];
  hideDotfiles: boolean;
  dateFormat: boolean;
  aceEditorTheme: string;
}

interface Rule {
  allow: boolean;
  path: string;
  regex: boolean;
  regexp: { raw: string };
}

interface SettingsBranding {
  name: string;
  disableExternal: boolean;
  disableUsedPercentage: boolean;
  files: string;
  theme: UserTheme;
  color: string;
}

interface SettingsTus {
  chunkSize: number;
  retryCount: number;
}

export interface SettingsCommand {
  after_copy?: string[];
  after_delete?: string[];
  after_rename?: string[];
  after_save?: string[];
  after_upload?: string[];
  before_copy?: string[];
  before_delete?: string[];
  before_rename?: string[];
  before_save?: string[];
  before_upload?: string[];
}

export interface SettingsUnit {
  KB: number;
  MB: number;
  GB: number;
  TB: number;
}
