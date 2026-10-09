import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import type { Resource, ResourceItem } from "@/types/file";
import { useFileStore } from "@/stores/file";
import { operationAcknowledgedTarget } from "../resourceOperationWire";
const item = (path: string, wirePath: string, isDir = false): ResourceItem => ({
  path,
  wirePath,
  isDir,
  name: path.split("/").at(-1)!,
  url: `/files${wirePath}`,
  size: 1,
  modified: "2026-01-01T00:00:00Z",
  mode: 0,
  extension: ".txt",
  isSymlink: false,
  type: isDir ? "dir" : "text",
  index: 0,
  riskLevel: "low",
});
const listing = (
  path: string,
  wire: string,
  items: ResourceItem[]
): Resource => ({
  ...item(path, wire, true),
  items,
  numDirs: 0,
  numFiles: items.length,
  sorting: { by: "name", asc: true },
});
describe("acknowledged operation preselection", () => {
  beforeEach(() => setActivePinia(createPinia()));
  it("a suffix wire ACK selects the actual opaque destination, never the original collision", () => {
    const original = item("/中/new.txt", "/%D6%D0/new.txt"),
      actual = item("/中/new (1).txt", "/%D6%D0/new%20%281%29.txt");
    const store = useFileStore();
    store.updateRequest(listing("/中", "/%D6%D0", [original, actual]));
    const response = new Response(null, {
      status: 204,
      headers: { "X-Resource-Destination-WirePath": actual.wirePath! },
    });
    const target = operationAcknowledgedTarget(response, [
      "/files/%D6%D0/old.txt",
      "/files/%D6%D0/new.txt",
    ]);
    store.clearSelection();
    store.setPreselect(target);
    store.applyPreSelection();
    expect(store.selectedItems.map((row) => row.wirePath)).toEqual([
      actual.wirePath,
    ]);
  });
  it("202 with an advertised target still clears selection without implicit parent selection", () => {
    const child = item("/parent/child", "/parent/child", true),
      collision = item("/parent/existing.txt", "/parent/existing.txt");
    const store = useFileStore();
    store.updateRequest(listing(child.path, child.wirePath!, []));
    const response = new Response(null, {
      status: 202,
      headers: { "X-Resource-Destination-WirePath": collision.wirePath! },
    });
    const target = operationAcknowledgedTarget(response, [
      "/files/source.txt",
      "/files/parent/existing.txt",
    ]);
    expect(target).toBeNull();
    store.clearSelection();
    store.setPreselect(target);
    store.updateRequest(listing("/parent", "/parent", [child, collision]));
    store.applyPreSelection();
    expect(store.selectedCount).toBe(0);
  });
  it("normal return to a parent retains its child preselection when no explicit target was set", () => {
    const child = item("/parent/child", "/parent/child", true),
      store = useFileStore();
    store.updateRequest(listing(child.path, child.wirePath!, []));
    store.updateRequest(listing("/parent", "/parent", [child]));
    store.applyPreSelection();
    expect(store.selectedItems.map((row) => row.wirePath)).toEqual([
      child.wirePath,
    ]);
  });
  it("only strict UTF8 legacy ACKs decode once and opaque requests without wire ACK stay unselected", () => {
    const legacy = new Response(null, {
      status: 204,
      headers: { "X-Resource-Destination": "/literal%252F.txt" },
    });
    expect(
      operationAcknowledgedTarget(legacy, [
        "/files/old.txt",
        "/files/literal%252F.txt",
      ])?.wirePath
    ).toBe("/literal%252F.txt");
    expect(
      operationAcknowledgedTarget(legacy, [
        "/files/%D6%D0.txt",
        "/files/literal%252F.txt",
      ])
    ).toBeNull();
    const unrelated = new Response(null, {
      status: 204,
      headers: { "X-Resource-Destination-WirePath": "/other/file.txt" },
    });
    expect(
      operationAcknowledgedTarget(unrelated, [
        "/files/old.txt",
        "/files/new.txt",
      ])
    ).toBeNull();
  });
});
