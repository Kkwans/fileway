import { normalizeFileKey } from "./fileListing";

export const MAX_ANALYSIS_SCOPES = 32;

function isCoveredBy(path: string, root: string) {
  return root === "/" || path === root || path.startsWith(`${root}/`);
}

export function normalizeAnalysisScope(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return "";
  return normalizeFileKey(trimmed);
}

export function addAnalysisScope(scopes: string[], value: string) {
  const next = normalizeAnalysisScope(value);
  if (!next || scopes.some((scope) => isCoveredBy(next, scope))) {
    return [...scopes];
  }
  const withoutDescendants = scopes.filter(
    (scope) => !isCoveredBy(scope, next)
  );
  if (withoutDescendants.length >= MAX_ANALYSIS_SCOPES) {
    return [...scopes];
  }
  return [...withoutDescendants, next];
}

export function analysisScopesFromQuery(value: unknown) {
  const raw = Array.isArray(value)
    ? value
    : typeof value === "string"
      ? [value]
      : [];
  return raw.reduce<string[]>((scopes, entry) => {
    if (typeof entry !== "string") return scopes;
    return addAnalysisScope(scopes, entry);
  }, []);
}
