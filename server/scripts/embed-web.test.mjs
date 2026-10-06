import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
  existsSync,
  symlinkSync,
  rmSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import { embedWeb } from "./embed-web.mjs";

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), "fileway-embed-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const source = join(root, "web"),
    destination = join(root, "embedded");
  mkdirSync(join(source, "public"), { recursive: true });
  writeFileSync(join(source, "public/index.html"), "new-web");
  mkdirSync(destination);
  writeFileSync(join(destination, "README.txt"), "build-marker");
  writeFileSync(join(destination, "old.js"), "stale");
  return { root, source, destination };
}

test("replace stale assets and keep the tracked build marker", (t) => {
  const { source, destination } = fixture(t);
  embedWeb(source, destination);
  assert.equal(
    readFileSync(join(destination, "public/index.html"), "utf8"),
    "new-web",
  );
  assert.equal(
    readFileSync(join(destination, "README.txt"), "utf8"),
    "build-marker",
  );
  assert.equal(existsSync(join(destination, "old.js")), false);
});

test("reject incomplete builds without changing current assets", (t) => {
  const { source, destination } = fixture(t);
  rmSync(join(source, "public/index.html"));
  assert.throws(() => embedWeb(source, destination), /Build the Web first/);
  assert.equal(readFileSync(join(destination, "old.js"), "utf8"), "stale");
});

test("reject source and destination symlinks without deleting their targets", (t) => {
  const { root, source, destination } = fixture(t);
  symlinkSync(join(destination, "old.js"), join(source, "leak"));
  assert.throws(() => embedWeb(source, destination), /symlinks/);
  rmSync(join(source, "leak"));
  const link = join(root, "link");
  symlinkSync(destination, link);
  assert.throws(() => embedWeb(source, link), /physical asset directory/);
  assert.equal(readFileSync(join(destination, "old.js"), "utf8"), "stale");
});

test("reject overlapping trees", (t) => {
  const { source } = fixture(t);
  assert.throws(() => embedWeb(source, source), /overlap/);
  assert.throws(() => embedWeb(source, join(source, "output")), /overlap/);
});
