import { existsSync, readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { fileURLToPath } from "node:url";
import {
  compileScript,
  compileTemplate,
  parse,
  registerTS,
} from "vue/compiler-sfc";
import ts from "typescript";
import * as vue from "vue";
import { createPinia, setActivePinia, storeToRefs } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Resource, ResourceItem } from "@/types/file";
import type { IUser } from "@/types/user";
import { useAuthStore } from "@/stores/auth";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import * as listing from "@/utils/fileListing";
import * as identity from "@/utils/favoritePersistence";
import * as operation from "@/utils/resourceOperationWire";
import * as urls from "@/utils/url";
import { batchRenameWireKey } from "@/utils/batchRename";
import { archiveWirePath } from "@/utils/archiveWire";

registerTS(() => ts);
const api = { fetch: vi.fn(), copy: vi.fn(), move: vi.fn() },
  preflight = vi.fn();
const toast = { $showError: vi.fn(), $showSuccess: vi.fn() };
const router = { push: vi.fn() },
  buttons = { loading: vi.fn(), done: vi.fn(), success: vi.fn() };
type AnyBindings = Record<string, unknown>;
function component(
  name: string,
  values: Record<string, unknown> = {},
  emit = vi.fn()
) {
  const filename = fileURLToPath(new URL(`../${name}.vue`, import.meta.url));
  const descriptor = parse(readFileSync(filename, "utf8"), {
    filename,
  }).descriptor;
  const script = compileScript(descriptor, {
    id: "owned-picker-test",
    fs: {
      fileExists: existsSync,
      readFile: (path) => readFileSync(path, "utf8"),
    },
  });
  const hooks: Array<() => void> = [],
    effect = vue.effectScope();
  const modules: Record<string, unknown> = {
    vue: {
      ...vue,
      inject: (key: keyof typeof toast) => toast[key],
      onMounted: () => {},
      onBeforeUnmount: (fn: () => void) => hooks.push(fn),
    },
    pinia: { storeToRefs },
    "vue-router": {
      useRouter: () => router,
      useRoute: () => ({ path: "/files/" }),
    },
    "@/api": { files: api },
    "@/stores/file": { useFileStore },
    "@/stores/auth": { useAuthStore },
    "@/stores/layout": { useLayoutStore },
    "@/utils/url": urls,
    "@/utils/archiveWire": { archiveWirePath },
    "@/utils/batchRename": { batchRenameWireKey },
    "@/utils/favoritePersistence": identity,
    "@/utils/fileListing": listing,
    "@/utils/resourceOperationWire": operation,
    "@/utils/upload": { checkConflict: preflight },
    "@/utils/buttons": { default: buttons },
    "@/utils/fileIcons": { getResourceIconName: () => "file" },
    "@/utils": { filesize: String },
    "@/utils/date": { default: (value: string) => ({ format: () => value }) },
  };
  function evaluate(code: string) {
    const module = { exports: {} as Record<string, unknown> };
    const output = ts.transpileModule(code, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText;
    runInNewContext(output, {
      module,
      exports: module.exports,
      require: (key: string) => {
        if (key in modules) return modules[key];
        if (key.endsWith(".vue")) return { default: { name: key } };
        throw new Error("Unexpected SFC dependency: " + key);
      },
    });
    return module.exports;
  }
  const compiled = evaluate(script.content).default as {
    props?: Record<string, { default?: unknown; type?: unknown }>;
    setup: (props: object, ctx: object) => AnyBindings;
  };
  const props: Record<string, unknown> = Object.fromEntries(
    Object.entries(values).map(([key, value]) => [
      key.replace(/-([a-z])/g, (_match, letter: string) =>
        letter.toUpperCase()
      ),
      value,
    ])
  );
  for (const [key, option] of Object.entries(compiled.props ?? {})) {
    if (!(key in props) && "default" in option)
      props[key] =
        typeof option.default === "function"
          ? (option.default as () => unknown)()
          : option.default;
    const types = Array.isArray(option.type) ? option.type : [option.type];
    if (
      types.some((type) => (type as { name?: string })?.name === "Boolean") &&
      props[key] === ""
    )
      props[key] = true;
  }
  const bindings = effect.run(() =>
    compiled.setup(vue.reactive(props), { expose: () => {}, emit })
  )!;
  const template = compileTemplate({
    source: descriptor.template!.content,
    filename,
    id: "owned-picker-test",
    compilerOptions: { bindingMetadata: script.bindings },
  });
  const render = evaluate(template.code).render as (
    ...args: unknown[]
  ) => vue.VNode;
  return {
    bindings,
    emit,
    render: () =>
      render(
        { ...props, ...vue.proxyRefs(bindings) },
        [],
        props,
        vue.proxyRefs(bindings),
        {},
        {}
      ),
    dispose: () => effect.stop(),
  };
}
type Picker = {
  load: (path: string, wire?: string) => Promise<void>;
  select: (path: string, wire?: string) => void;
  confirm: () => void;
  entries: vue.Ref<
    Array<{ path: string; wirePath: string; isParent?: boolean }>
  >;
};
const folder = (wirePath: string, path = "/中文"): ResourceItem => ({
  path,
  wirePath,
  name: path.split("/").at(-1)!,
  isDir: true,
  isSymlink: false,
  type: "dir",
  size: 0,
  modified: "",
  mode: 0,
  extension: "",
  url: `/files${wirePath}/`,
  index: 0,
  riskLevel: "low",
});
const directory = (
  wire: string,
  items: ResourceItem[] = [],
  path = "/"
): Resource => ({
  ...folder(wire, path),
  items,
  numDirs: items.length,
  numFiles: 0,
  sorting: { by: "name", asc: true },
});
function source() {
  const store = useFileStore();
  store.isFiles = true;
  const item = {
    ...folder("/%D6%D0%CE%C4.txt", "/中文.txt"),
    isDir: false,
    type: "text" as const,
    url: "/files/%D6%D0%CE%C4.txt",
  };
  store.updateRequest(directory("/", [item]));
  store.selectOnly(store.keyFor(item));
  return item;
}
describe("real SFC wire directory destinations (without DOM)", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    useAuthStore().setUser({ id: 1, scope: "/one" } as IUser);
    preflight.mockResolvedValue([]);
    api.copy.mockResolvedValue([new Response(null, { status: 202 })]);
    api.move.mockResolvedValue([new Response(null, { status: 202 })]);
  });
  it("wire exclude hides only the selected byte identity and preserves its same-display sibling", async () => {
    const opaque = folder("/%D6%D0"),
      unicode = folder("/%E4%B8%AD%E6%96%87");
    api.fetch.mockResolvedValue(directory("/", [opaque, unicode]));
    const view = component("PathPicker", {
        wirePaths: true,
        exclude: [opaque.wirePath],
      }),
      picker = view.bindings as unknown as Picker;
    await picker.load("/", "/");
    expect(picker.entries.value.map((item) => item.wirePath)).toEqual([
      unicode.wirePath,
    ]);
    view.dispose();
  });
  it("Copy listens only to resource emission and preserves both target parent and source basename bytes", async () => {
    source();
    const copy = component("Copy"),
      vnode = copy.render();
    expect(vnode.props?.onSelect).toBeUndefined();
    expect(vnode.props?.onSelectResource).toBeTypeOf("function");
    const emit = (event: string, value: unknown) => {
      if (event === "select-resource")
        return vnode.props!.onSelectResource(value);
    };
    const picker = component("PathPicker", vnode.props!, emit as never)
      .bindings as unknown as Picker;
    api.fetch.mockResolvedValue(directory("/%D6%D0", [], "/中文"));
    await picker.load("/中文", "/%D6%D0");
    picker.confirm();
    await vi.waitFor(() => expect(api.copy).toHaveBeenCalledTimes(1));
    expect(api.copy.mock.calls[0][0][0].to).toBe(
      "/files/%D6%D0/%D6%D0%CE%C4.txt"
    );
    expect(preflight.mock.calls[0][1]).toBe("/files/%D6%D0");
    expect(buttons.success).not.toHaveBeenCalled();
    expect(toast.$showSuccess.mock.calls[0][0]).toContain("提交");
    copy.dispose();
  });
  it("a real /files directory remains a native wire destination, not the UI prefix", async () => {
    source();
    const view = component("Copy"),
      vnode = view.render();
    await vnode.props!.onSelectResource({
      path: "/files",
      wirePath: "/files",
      isDir: true,
    });
    expect(api.copy.mock.calls[0][0][0].to).toBe(
      "/files/files/%D6%D0%CE%C4.txt"
    );
    view.dispose();
  });
  it("a late picker read after account/scope change cannot emit or replace the new source", async () => {
    let finish!: (value: Resource) => void;
    api.fetch.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      })
    );
    const view = component("PathPicker", { wirePaths: true }),
      picker = view.bindings as unknown as Picker;
    const pending = picker.load("/中文", "/%D6%D0");
    useAuthStore().setUser({ id: 1, scope: "/two" } as IUser);
    finish(directory("/%D6%D0", [], "/中文"));
    await pending;
    picker.confirm();
    expect(view.emit).not.toHaveBeenCalled();
    expect(picker.entries.value).toEqual([]);
    view.dispose();
  });
  it("late conflict checks never start Copy in another scope", async () => {
    source();
    let finish!: (value: unknown[]) => void;
    preflight.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      })
    );
    const view = component("Copy"),
      vnode = view.render();
    const pending = vnode.props!.onSelectResource({
      path: "/中文",
      wirePath: "/%D6%D0",
      isDir: true,
    });
    useAuthStore().setUser({ id: 1, scope: "/two" } as IUser);
    finish([]);
    await pending;
    expect(api.copy).not.toHaveBeenCalled();
    view.dispose();
  });
  it("Move excludes the original directory wire and transfers its basename without display encoding", async () => {
    const store = useFileStore();
    store.isFiles = true;
    const original = folder("/%D6%D0%CE%C4"),
      sibling = folder("/%E4%B8%AD%E6%96%87");
    store.updateRequest(directory("/", [original, sibling]));
    store.selectOnly(store.keyFor(original));
    const view = component("Move"),
      vnode = view.render();
    expect(vnode.props?.exclude).toEqual([original.wirePath]);
    expect(vnode.props?.onSelect).toBeUndefined();
    await vnode.props!.onSelectResource({
      path: "/out",
      wirePath: "/out",
      isDir: true,
    });
    expect(api.move.mock.calls[0][0][0].to).toBe("/files/out/%D6%D0%CE%C4");
    view.dispose();
  });
  it("ResultAction uses the selected resource and a 202 does not trigger a completed action", async () => {
    const original = source(),
      changed = vi.fn();
    useLayoutStore().showHover({ prompt: "result-action", action: changed });
    const view = component("ResultAction", {
        mode: "copy",
        result: { ...original, dir: false, pathVerified: true },
      }),
      vnode = view.render();
    expect(vnode.props?.onSelect).toBeUndefined();
    await vnode.props!.onSelectResource({
      path: "/中文",
      wirePath: "/%D6%D0%CE%C4",
      isDir: true,
    });
    expect(api.copy.mock.calls[0][0][0].to).toBe(
      "/files/%D6%D0%CE%C4/%D6%D0%CE%C4.txt"
    );
    expect(changed).not.toHaveBeenCalled();
    expect(toast.$showSuccess.mock.calls[0][0]).toContain("提交");
    view.dispose();
  });
});
