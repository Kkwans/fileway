export function removeLastDir(url: string) {
  const arr = url.split("/");
  if (arr.pop() === "") {
    arr.pop();
  }

  return arr.join("/");
}

// this function is taken from mozilla
// https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/encodeURIComponent#Examples
export function encodeRFC5987ValueChars(str: string) {
  return (
    encodeURIComponent(str)
      // The following creates the sequences %27 %28 %29 %2A (Note that
      // the valid encoding of "*" is %2A, which necessitates calling
      // toUpperCase() to properly encode). Although RFC3986 reserves "!",
      // RFC5987 does not, so we do not need to escape it.
      .replace(
        /['()*]/g,
        (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`
      )
      // The following are not required for percent-encoding per RFC5987,
      // so we can allow for a little better readability over the wire: |`^
      .replace(/%(7C|60|5E)/g, (str, hex) =>
        String.fromCharCode(parseInt(hex, 16))
      )
  );
}

export function encodePath(str: string) {
  return str
    .split("/")
    .map((v) => encodeURIComponent(v))
    .join("/");
}

/** Decode each URL path segment once while preserving malformed legacy input. */
export function decodePath(str: string) {
  return str
    .split("/")
    .map((segment) => {
      try {
        return decodeURIComponent(segment);
      } catch {
        return segment;
      }
    })
    .join("/");
}

const FILES_ROUTE_PREFIX = "/files";

function isFilesRoute(value: string) {
  return (
    value === FILES_ROUTE_PREFIX || value.startsWith(`${FILES_ROUTE_PREFIX}/`)
  );
}

/**
 * Converts either a `/files/...` UI route or an already canonical NAS path
 * into the path used by resource APIs. UI routes are decoded one segment at a
 * time exactly once; canonical paths are intentionally left untouched so a
 * literal filename such as `%2Fname` remains a literal filename.
 */
export function canonicalResourcePath(value: string) {
  const trimmed = value.trim();
  if (isFilesRoute(trimmed)) {
    const resourcePath = trimmed.slice(FILES_ROUTE_PREFIX.length) || "/";
    return decodePath(resourcePath);
  }
  if (trimmed === "") return "/";
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

/** Builds a routable `/files/...` URL from a canonical NAS path. */
export function encodeResourceRoute(value: string) {
  const canonical = canonicalResourcePath(value);
  const trailingSlash = canonical.length > 1 && canonical.endsWith("/");
  const withoutTrailingSlash = trailingSlash
    ? canonical.replace(/\/+$/, "")
    : canonical;
  const route = `${FILES_ROUTE_PREFIX}${encodePath(withoutTrailingSlash || "/")}`;
  return trailingSlash && route !== `${FILES_ROUTE_PREFIX}/`
    ? `${route}/`
    : route;
}

/** Appends one literal filename segment while preserving URL encoding rules. */
export function appendResourceRouteSegment(parent: string, name: string) {
  // Keep an already encoded UI route opaque. decodeURIComponent cannot decode
  // legacy non-UTF-8 bytes such as `%D6%D0`, and re-encoding that placeholder
  // would turn it into `%25D6` and make rename/move requests target a
  // different file. New segments are still encoded exactly once.
  if (isFilesRoute(parent)) {
    const base = parent.replace(/\/+$/, "") || FILES_ROUTE_PREFIX;
    return `${base}/${encodeURIComponent(name)}`;
  }
  const base = canonicalResourcePath(parent).replace(/\/+$/, "");
  return encodeResourceRoute(`${base || "/"}/${name}`);
}

export default {
  encodeRFC5987ValueChars,
  removeLastDir,
  encodePath,
  decodePath,
  canonicalResourcePath,
  encodeResourceRoute,
  appendResourceRouteSegment,
};
