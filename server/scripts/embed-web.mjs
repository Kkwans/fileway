import {
  cpSync,
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  renameSync,
  rmSync,
} from "node:fs";
import { dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

function requireTree(directory) {
  if (
    !lstatSync(directory).isDirectory() ||
    lstatSync(directory).isSymbolicLink()
  ) {
    throw new Error(`Not a physical asset directory: ${directory}`);
  }
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isSymbolicLink())
      throw new Error("Web assets must not contain symlinks");
    if (entry.isDirectory()) requireTree(resolve(directory, entry.name));
    else if (!entry.isFile())
      throw new Error("Web assets must be regular files");
  }
}

export function embedWeb(source, destination) {
  source = resolve(source);
  destination = resolve(destination);
  if (
    source === destination ||
    source.startsWith(destination + sep) ||
    destination.startsWith(source + sep)
  ) {
    throw new Error("Source and destination must not overlap");
  }
  requireTree(source);
  if (!existsSync(resolve(source, "public/index.html")))
    throw new Error("Build the Web first: public/index.html is missing");
  if (existsSync(destination)) requireTree(destination);
  mkdirSync(dirname(destination), { recursive: true });
  const staging = mkdtempSync(resolve(dirname(destination), ".embed-"));
  const previous = staging + "-previous";
  try {
    cpSync(source, staging, { recursive: true });
    const marker = resolve(destination, "README.txt");
    if (existsSync(marker)) cpSync(marker, resolve(staging, "README.txt"));
    if (existsSync(destination)) renameSync(destination, previous);
    try {
      renameSync(staging, destination);
    } catch (error) {
      if (existsSync(previous)) renameSync(previous, destination);
      throw error;
    }
    rmSync(previous, { recursive: true, force: true });
  } finally {
    rmSync(staging, { recursive: true, force: true });
  }
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const server = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  embedWeb(
    process.argv[2] || resolve(server, "frontend/dist"),
    resolve(server, "backend/frontend/dist"),
  );
  console.log("Fileway Web assets embedded; stale build files replaced");
}
