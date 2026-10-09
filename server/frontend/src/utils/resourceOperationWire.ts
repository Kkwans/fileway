import { favoriteWirePath } from "./favoritePersistence";
import { batchRenameWireKey } from "./batchRename";
import type { ListingResourceRef } from "./fileListing";
import { encodePath } from "./url";

export type OperationResource = ListingResourceRef | string;
export type OperationWireTarget = {
  wirePath: string;
  identity: string;
  plainPath?: string;
  legacyRoute: string;
};

/** Copy/move preserves the source's basename bytes, not its display spelling. */
export function operationDestinationRoute(
  destination: OperationResource,
  source: OperationResource
): string {
  const parent = operationWireTarget(destination, true).wirePath.replace(
    /\/+$/,
    ""
  );
  const basename = operationWireTarget(source, true).wirePath.split("/").at(-1);
  if (!basename) throw new Error("不能复制或移动文件系统根目录");
  return `/files${parent}/${basename}`;
}

/** Browser upload relative names are known Unicode input, encoded once. */
export function operationRelativeTarget(
  base: OperationResource,
  relative: string
): OperationWireTarget {
  const segments = relative.replace(/^\/+|\/+$/g, "").split("/");
  if (
    segments.some(
      (part) => !part || part === "." || part === ".." || part.includes("\0")
    )
  )
    throw new Error("上传相对路径无效");
  const parent = operationWireTarget(base, true).wirePath.replace(/\/+$/, "");
  const wirePath = `${parent}/${segments.map(encodeURIComponent).join("/")}`;
  let path = wirePath;
  try {
    path = wirePath.split("/").map(decodeURIComponent).join("/");
  } catch {
    /* opaque parent stays opaque */
  }
  return operationWireTarget({ path, wirePath });
}

/** Strings at old route-taking APIs are /files routes; ResourceRefs always
 * describe real filesystem paths, including a real directory named /files. */
export function operationWireTarget(
  value: OperationResource,
  routeString = false
): OperationWireTarget {
  let ref: ListingResourceRef;
  if (
    typeof value === "string" &&
    routeString &&
    (value === "/files" || value.startsWith("/files/"))
  ) {
    const wirePath = value.slice("/files".length) || "/";
    let path = wirePath;
    try {
      path = wirePath.split("/").map(decodeURIComponent).join("/");
    } catch {
      /* opaque bytes stay opaque */
    }
    ref = { path, wirePath };
  } else ref = typeof value === "string" ? { path: value } : value;
  const wirePath = favoriteWirePath(ref);
  if (!wirePath) throw new Error("原始路径无法确认，请重新选择文件");
  let plainPath: string | undefined;
  try {
    plainPath = wirePath.split("/").map(decodeURIComponent).join("/");
  } catch {
    /* never create a display fallback */
  }
  return {
    wirePath,
    identity: batchRenameWireKey(wirePath),
    plainPath,
    legacyRoute: `/files${wirePath}`,
  };
}

export function operationSource(readScope: () => string) {
  const scope = readScope();
  const current = () => readScope() === scope;
  return {
    scope,
    current,
    ensure() {
      if (!current()) throw new Error("文件来源已切换，请重新选择文件");
    },
  };
}

export function operationResourceSnapshot<T extends ListingResourceRef>(
  scope: string,
  rows: readonly T[]
) {
  return {
    scope,
    rows: Object.freeze(rows.map((row) => Object.freeze({ ...row }))),
  };
}

/** A queued task or missing wire ACK is not an actual destination. Never
 * select the requested collision target when keep-both chose another name. */
export function operationAcknowledgedTarget(
  response: Pick<Response, "status" | "headers"> | undefined,
  requested: readonly OperationResource[]
): ListingResourceRef | null {
  if (
    !response ||
    response.status < 200 ||
    response.status >= 300 ||
    response.status === 202
  )
    return null;
  try {
    const inputs = requested.map((value) => operationWireTarget(value, true));
    const wire = response.headers.get("X-Resource-Destination-WirePath");
    let target: OperationWireTarget;
    if (wire) {
      let path = wire;
      try {
        path = wire.split("/").map(decodeURIComponent).join("/");
      } catch {
        /* use confirmed bytes, not a display guess */
      }
      target = operationWireTarget({ path, wirePath: wire });
    } else {
      if (inputs.some((value) => value.plainPath === undefined)) return null;
      const legacy = response.headers.get("X-Resource-Destination");
      if (!legacy) return null;
      const path = decodeURIComponent(legacy);
      target = operationWireTarget({ path, wirePath: encodePath(path) });
    }
    const parent = (value: OperationWireTarget) =>
      value.identity.slice(0, value.identity.lastIndexOf("/")) || "/";
    if (inputs.length && parent(target) !== parent(inputs[inputs.length - 1]))
      return null;
    return {
      path: target.plainPath ?? target.wirePath,
      wirePath: target.wirePath,
      pathVerified: true,
    };
  } catch {
    return null;
  }
}
