import { getTheme } from "./theme";

export const getEditorTheme = async (themeName: string) => {
  const { themesByName } =
    await import("ace-builds/src-noconflict/ext-themelist");
  if (!themeName.startsWith("ace/theme/")) {
    themeName = `ace/theme/${themeName}`;
  }
  const themeKey = themeName.replace("ace/theme/", "");
  if (themesByName[themeKey] !== undefined) {
    return themeName;
  }
  if (getTheme() === "dark") {
    // monokai: 经典暗色主题，色彩丰富，适合代码阅读
    return "ace/theme/monokai";
  }
  // one_light: 现代亮色主题，对比度适中，不刺眼
  return "ace/theme/one_light";
};
