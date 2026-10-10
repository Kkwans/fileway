import { afterEach, describe, expect, it, vi } from "vitest";
import {
  isAssetLoadError,
  reloadAfterConfirmation,
} from "../assetLoadRecovery";

afterEach(() => vi.unstubAllGlobals());

describe("lazy resource recovery", () => {
  it.each([
    "Failed to fetch dynamically imported module: /static/assets/SearchPage-old.js",
    "error loading dynamically imported module: /static/assets/Editor-old.js",
    "Importing a module script failed.",
    "Loading chunk 15 failed.",
    "Unable to preload CSS for /static/assets/Editor-old.css",
  ])("recognizes browser resource failures: %s", (message) => {
    expect(isAssetLoadError(new Error(message))).toBe(true);
    expect(isAssetLoadError({ message })).toBe(true);
    expect(isAssetLoadError(message)).toBe(true);
  });

  it.each([null, undefined, {}, new Error("Unauthorized"), "Failed to fetch"])(
    "does not mislabel auth/API errors: %s",
    (error) => expect(isAssetLoadError(error)).toBe(false)
  );

  it("never refreshes if the user declines, and only refreshes on confirmation", () => {
    const reload = vi.fn();
    const confirm = vi
      .fn()
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    vi.stubGlobal("window", { confirm, location: { reload } });
    reloadAfterConfirmation();
    expect(reload).not.toHaveBeenCalled();
    reloadAfterConfirmation();
    expect(reload).toHaveBeenCalledOnce();
  });
});
