import { describe, expect, it } from "vitest";

import {
  clampTokenExpirationMinutes,
  durationToMinutes,
  minutesToDuration,
} from "../tokenExpiration";

describe("会话超时时间", () => {
  it("把后端 duration 转换为分钟", () => {
    expect(durationToMinutes("2h")).toBe(120);
    expect(durationToMinutes("30m")).toBe(30);
    expect(durationToMinutes("24h")).toBe(1440);
  });

  it("无法解析时回退为两小时", () => {
    expect(durationToMinutes("")).toBe(120);
    expect(durationToMinutes("invalid")).toBe(120);
  });

  it("强制限制在 10 分钟到 30 天", () => {
    expect(clampTokenExpirationMinutes(1)).toBe(10);
    expect(clampTokenExpirationMinutes(60)).toBe(60);
    expect(clampTokenExpirationMinutes(2000)).toBe(2000);
    expect(clampTokenExpirationMinutes(50000)).toBe(43200);
  });

  it("保存为 Go duration 字符串", () => {
    expect(minutesToDuration(10)).toBe("10m");
    expect(minutesToDuration(120)).toBe("2h");
    expect(minutesToDuration(1440)).toBe("24h");
    expect(minutesToDuration(43200)).toBe("720h");
  });
});
