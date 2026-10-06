import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it } from "vitest";

import { useLayoutStore } from "../layout";

describe("layout transient navigation", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("toggles the same transient panel instead of stacking duplicates", () => {
    const store = useLayoutStore();

    store.toggleTransient("more");
    expect(store.currentPromptName).toBe("more");

    store.toggleTransient("more");
    expect(store.currentPromptName).toBeNull();
    expect(store.prompts).toHaveLength(0);
  });

  it("switches between sidebar and more without leaving stale panels", () => {
    const store = useLayoutStore();

    store.toggleTransient("sidebar");
    store.toggleTransient("more");

    expect(store.currentPromptName).toBe("more");
    expect(store.prompts.map((prompt) => prompt.prompt)).toEqual(["more"]);
  });

  it("dismisses More without dismissing a dialog opened from it", () => {
    const store = useLayoutStore();
    store.toggleTransient("more");
    store.showHover("copy");
    store.closeTransient("more");
    expect(store.prompts.map((prompt) => prompt.prompt)).toEqual(["copy"]);
    store.closeTransient("more");
    expect(store.currentPromptName).toBe("copy");
  });
});
