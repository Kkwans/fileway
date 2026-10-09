import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { parse, compileScript } from "vue/compiler-sfc";
import ts from "typescript";
import * as vue from "vue";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Resource, ResourceItem } from "@/types/file";
import type { IUser } from "@/types/user";
import { useAuthStore } from "@/stores/auth";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { operationResourceSnapshot } from "@/utils/resourceOperationWire";
import { fileResourceIdentity } from "@/utils/fileListing";

// Execute the actual compiled script setup. This is a behavior contract for
// snapshot/callback handling, not a browser or rendered dialog acceptance.
const descriptor = parse(
  readFileSync(new URL("./Delete.vue", import.meta.url), "utf8")
).descriptor;
const script = compileScript(descriptor, { id: "owned-delete-test" }).content;
const code = ts.transpileModule(script, {
  compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText;
const api = { remove: vi.fn(), schedulePermanentDeletion: vi.fn() };
const tasks = { cancel: vi.fn() };
const toast = {
  $showError: vi.fn(),
  $showSuccess: vi.fn(),
  $showAction: vi.fn(),
};
type Bindings = {
  submit: () => Promise<void>;
  submitPermanent: () => Promise<void>;
};
function dialog(): Bindings {
  const module = {
    exports: {} as {
      default?: { setup: (props: object, context: object) => Bindings };
    },
  };
  const modules: Record<string, unknown> = {
    vue: { ...vue, inject: (key: keyof typeof toast) => toast[key] },
    "@/api": { files: api },
    "@/api/tasks": tasks,
    "@/stores/file": { useFileStore },
    "@/stores/layout": { useLayoutStore },
    "@/utils/resourceOperationWire": { operationResourceSnapshot },
    "@/utils/fileListing": { fileResourceIdentity },
    "@/components/ui/AppDialog.vue": { default: {} },
    "@/components/ui/AppIcon.vue": { default: {} },
  };
  runInNewContext(code, {
    module,
    exports: module.exports,
    require: (name: string) => {
      if (!(name in modules))
        throw new Error("Unexpected dialog dependency: " + name);
      return modules[name];
    },
  });
  return module.exports.default!.setup({}, { expose: () => {} });
}
function item(
  wirePath: string,
  index: number,
  riskLevel: "low" | "high" = "low"
): ResourceItem {
  return {
    path: "/中文.txt",
    wirePath,
    name: "中文.txt",
    url: `/files${wirePath}`,
    size: 1,
    modified: "2026-01-01T00:00:00Z",
    extension: ".txt",
    mode: 0,
    isDir: false,
    isSymlink: false,
    type: "text",
    index,
    riskLevel,
  };
}
function setup(risk: "low" | "high" = "low") {
  const a = item("/%D6%D0%CE%C4.txt", 0, risk),
    b = item("/%E4%B8%AD%E6%96%87.txt", 1);
  const store = useFileStore();
  store.isFiles = true;
  store.updateRequest({
    ...a,
    path: "/",
    wirePath: "/",
    url: "/files/",
    isDir: true,
    type: "dir",
    items: [a, b],
    numDirs: 0,
    numFiles: 2,
    sorting: { by: "name", asc: true },
  } as Resource);
  store.selectOnly(store.keyFor(a));
  useLayoutStore().showHover("delete");
  return { store, a, b };
}
const switchScope = () =>
  useAuthStore().setUser({ id: 1, scope: "/two" } as IUser);
describe("delete dialog immutable operation source", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    useAuthStore().setUser({ id: 1, scope: "/one" } as IUser);
    api.remove.mockResolvedValue({ id: "owned-trash" });
    api.schedulePermanentDeletion.mockResolvedValue({ id: "owned-task" });
    tasks.cancel.mockResolvedValue(undefined);
  });
  it("trash uses the original wire candidate even if selection changes after opening", async () => {
    const { store, a, b } = setup();
    const view = dialog();
    store.selectOnly(store.keyFor(b));
    await view.submit();
    expect(api.remove).toHaveBeenCalledTimes(1);
    expect(api.remove.mock.calls[0][0].wirePath).toBe(a.wirePath);
  });
  it("scope change after the first trash ACK prevents remaining writes and new-scope UI updates", async () => {
    const { store, a, b } = setup();
    store.setSelected([store.keyFor(a), store.keyFor(b)]);
    let finish!: (value: unknown) => void;
    api.remove.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      })
    );
    const pending = dialog().submit();
    switchScope();
    finish({ id: "owned-trash" });
    await pending;
    expect(api.remove).toHaveBeenCalledTimes(1);
    expect(store.preselect).toBeNull();
    expect(store.reload).toBe(false);
    expect(toast.$showSuccess).not.toHaveBeenCalled();
    expect(toast.$showError).not.toHaveBeenCalled();
  });
  it("risk confirmation retained from another scope cannot schedule deletion", async () => {
    setup("high");
    await dialog().submitPermanent();
    const confirm = useLayoutStore().currentPrompt!.props!.onconfirm;
    switchScope();
    confirm();
    expect(api.schedulePermanentDeletion).not.toHaveBeenCalled();
  });
  it("permanent deletion keeps the original wire snapshot and an old undo callback cannot cancel another scope", async () => {
    const { store, a, b } = setup();
    const view = dialog();
    store.selectOnly(store.keyFor(b));
    await view.submitPermanent();
    expect(
      api.schedulePermanentDeletion.mock.calls[0][0].map(
        (row: ResourceItem) => row.wirePath
      )
    ).toEqual([a.wirePath]);
    const undo = toast.$showAction.mock.calls[0][2];
    switchScope();
    await undo();
    expect(tasks.cancel).not.toHaveBeenCalled();
    expect(store.reload).toBe(false);
  });
  it("repeated submit while awaiting an ACK sends one request", async () => {
    setup();
    let finish!: (value: unknown) => void;
    api.remove.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      })
    );
    const view = dialog(),
      pending = view.submit();
    await view.submit();
    expect(api.remove).toHaveBeenCalledTimes(1);
    finish({ id: "owned-trash" });
    await pending;
  });
  it("an old undo failure after scope switch does not surface in the new workspace", async () => {
    setup();
    await dialog().submitPermanent();
    let fail!: (error: Error) => void;
    tasks.cancel.mockReturnValueOnce(
      new Promise((_resolve, reject) => {
        fail = reject;
      })
    );
    const pending = toast.$showAction.mock.calls[0][2]();
    switchScope();
    fail(new Error("owned-old-scope-failure"));
    await expect(pending).resolves.toBeUndefined();
    expect(toast.$showSuccess).not.toHaveBeenCalled();
  });
});
