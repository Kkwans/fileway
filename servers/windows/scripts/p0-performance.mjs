#!/usr/bin/env node

import { spawn } from "node:child_process";
import { copyFile, mkdir, rm, writeFile } from "node:fs/promises";
import { arch, platform, tmpdir } from "node:os";
import path from "node:path";
import process from "node:process";
import { performance } from "node:perf_hooks";

const DEFAULT_WORK_DIR = path.join(tmpdir(), "nas-file-browser-p0-benchmark");
const DEFAULT_RUNS = 5;
const DEFAULT_ORDINARY_FILES = 2000;
const DEFAULT_MEDIA_FILES = 200;

function parseArgs(argv) {
  const options = {
    runs: DEFAULT_RUNS,
    ordinaryFiles: DEFAULT_ORDINARY_FILES,
    mediaFiles: DEFAULT_MEDIA_FILES,
    workDir: DEFAULT_WORK_DIR,
    baselinePort: 18101,
    candidatePort: 18102,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const name = argv[index];
    const value = argv[index + 1];
    if (!name.startsWith("--") || value === undefined) {
      throw new Error(`Invalid argument: ${name}`);
    }
    index += 1;
    switch (name) {
      case "--baseline-binary":
        options.baselineBinary = value;
        break;
      case "--candidate-binary":
        options.candidateBinary = value;
        break;
      case "--image-fixture":
        options.imageFixture = value;
        break;
      case "--video-fixture":
        options.videoFixture = value;
        break;
      case "--work-dir":
        options.workDir = value;
        break;
      case "--runs":
        options.runs = Number(value);
        break;
      case "--ordinary-files":
        options.ordinaryFiles = Number(value);
        break;
      case "--media-files":
        options.mediaFiles = Number(value);
        break;
      case "--baseline-port":
        options.baselinePort = Number(value);
        break;
      case "--candidate-port":
        options.candidatePort = Number(value);
        break;
      default:
        throw new Error(`Unknown argument: ${name}`);
    }
  }

  for (const required of [
    "baselineBinary",
    "candidateBinary",
    "imageFixture",
  ]) {
    if (!options[required])
      throw new Error(
        `Missing --${required.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`,
      );
  }

  for (const [name, value] of [
    ["runs", options.runs],
    ["ordinary-files", options.ordinaryFiles],
    ["media-files", options.mediaFiles],
  ]) {
    if (!Number.isInteger(value) || value < 1) {
      throw new Error(`--${name} must be a positive integer`);
    }
  }

  for (const [name, value] of [
    ["baseline-port", options.baselinePort],
    ["candidate-port", options.candidatePort],
  ]) {
    if (!Number.isInteger(value) || value < 1024 || value > 65535) {
      throw new Error(`--${name} must be between 1024 and 65535`);
    }
  }

  const resolvedWorkDir = path.resolve(options.workDir);
  const resolvedTmpDir = `${path.resolve(tmpdir())}${path.sep}`;
  if (!resolvedWorkDir.startsWith(resolvedTmpDir)) {
    throw new Error(
      "--work-dir must be a child of the system temporary directory",
    );
  }
  options.workDir = resolvedWorkDir;
  return options;
}

async function inBatches(total, callback, batchSize = 100) {
  for (let start = 0; start < total; start += batchSize) {
    const end = Math.min(start + batchSize, total);
    await Promise.all(
      Array.from({ length: end - start }, (_, offset) =>
        callback(start + offset),
      ),
    );
  }
}

const numberedName = (prefix, index, extension) =>
  `${prefix}-${String(index).padStart(4, "0")}${extension}`;

async function createDataset(options) {
  const dataset = path.join(options.workDir, "dataset");
  await rm(options.workDir, { recursive: true, force: true });
  for (const name of ["ordinary", "images", "videos", "mixed"]) {
    await mkdir(path.join(dataset, name), { recursive: true });
  }

  const ordinaryPayload = Buffer.alloc(1024, "p0-benchmark\n");
  await inBatches(options.ordinaryFiles, async (index) => {
    await writeFile(
      path.join(dataset, "ordinary", numberedName("needle", index, ".txt")),
      ordinaryPayload,
    );
  });

  await inBatches(options.mediaFiles, async (index) => {
    await copyFile(
      options.imageFixture,
      path.join(dataset, "images", numberedName("needle-image", index, ".jpg")),
    );
    const videoTarget = path.join(
      dataset,
      "videos",
      numberedName("needle-video", index, ".mp4"),
    );
    if (options.videoFixture) {
      await copyFile(options.videoFixture, videoTarget);
    } else {
      await writeFile(videoTarget, "video fixture intentionally unavailable\n");
    }
  });

  const mixedCount = Math.max(1, Math.floor(options.mediaFiles / 2));
  await inBatches(mixedCount, async (index) => {
    await writeFile(
      path.join(dataset, "mixed", numberedName("needle-text", index, ".txt")),
      ordinaryPayload,
    );
    await copyFile(
      options.imageFixture,
      path.join(dataset, "mixed", numberedName("needle-image", index, ".jpg")),
    );
    const videoTarget = path.join(
      dataset,
      "mixed",
      numberedName("needle-video", index, ".mp4"),
    );
    if (options.videoFixture) {
      await copyFile(options.videoFixture, videoTarget);
    } else {
      await writeFile(videoTarget, "video fixture intentionally unavailable\n");
    }
  });

  return dataset;
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[middle - 1] + sorted[middle]) / 2
    : sorted[middle];
}

function summarize(samples) {
  return {
    median_ms: Number(median(samples).toFixed(3)),
    samples_ms: samples.map((value) => Number(value.toFixed(3))),
  };
}

async function waitForServer(child, label) {
  let output = "";
  const append = (chunk) => {
    output += chunk.toString();
  };
  child.stdout.on("data", append);
  child.stderr.on("data", append);

  const started = new Promise((resolve, reject) => {
    const inspect = (chunk) => {
      if (chunk.toString().includes("Listening on")) resolve();
    };
    child.stdout.on("data", inspect);
    child.stderr.on("data", inspect);
    child.once("exit", (code, signal) => {
      reject(
        new Error(
          `${label} exited before listening (code=${code}, signal=${signal})\n${output}`,
        ),
      );
    });
  });
  const timeout = new Promise((_, reject) => {
    setTimeout(
      () =>
        reject(
          new Error(`${label} did not listen within 20 seconds\n${output}`),
        ),
      20_000,
    );
  });
  await Promise.race([started, timeout]);
  return () => output;
}

async function stopServer(child) {
  if (child.exitCode !== null) return;
  child.kill("SIGINT");
  await Promise.race([
    new Promise((resolve) => child.once("exit", resolve)),
    new Promise((resolve) => setTimeout(resolve, 10_000)),
  ]);
  if (child.exitCode === null) child.kill("SIGTERM");
}

async function request(url, init, validate) {
  const startedAt = performance.now();
  const response = await fetch(url, init);
  const body = Buffer.from(await response.arrayBuffer());
  const elapsed = performance.now() - startedAt;
  if (!response.ok) {
    throw new Error(
      `${response.status} ${response.statusText}: ${body.toString("utf8")}`,
    );
  }
  validate?.(response, body);
  return elapsed;
}

async function sample(runs, callback) {
  const values = [];
  for (let index = 0; index < runs; index += 1) {
    values.push(await callback(index));
  }
  return summarize(values);
}

async function benchmarkBinary(label, binary, port, dataset, options) {
  const runtime = path.join(options.workDir, label);
  const cache = path.join(runtime, "cache");
  await mkdir(cache, { recursive: true });
  const child = spawn(
    binary,
    [
      "--noauth",
      "--database",
      path.join(runtime, "filebrowser.db"),
      "--root",
      dataset,
      "--cacheDir",
      cache,
      "--address",
      "127.0.0.1",
      "--port",
      String(port),
    ],
    { stdio: ["ignore", "pipe", "pipe"] },
  );
  const getLogs = await waitForServer(child, label);
  const origin = `http://127.0.0.1:${port}`;

  try {
    const login = await fetch(`${origin}/api/login`, { method: "POST" });
    if (!login.ok) throw new Error(`${label} login failed: ${login.status}`);
    const token = await login.text();
    const headers = { "X-Auth": token };
    const metrics = {};

    for (const directory of ["ordinary", "images", "videos", "mixed"]) {
      metrics[`list_${directory}`] = await sample(options.runs, () =>
        request(
          `${origin}/api/resources/${directory}/`,
          { headers },
          (_, body) => {
            const listing = JSON.parse(body.toString("utf8"));
            if (!Array.isArray(listing.items) || listing.items.length === 0) {
              throw new Error(
                `${label} returned an empty ${directory} listing`,
              );
            }
          },
        ),
      );
    }

    metrics.image_preview_cold = await sample(options.runs, (index) =>
      request(
        `${origin}/api/preview/big/images/${numberedName("needle-image", index, ".jpg")}`,
        { headers },
        (response, body) => {
          if (
            !response.headers.get("content-type")?.startsWith("image/") ||
            body.length === 0
          ) {
            throw new Error(`${label} returned an invalid image preview`);
          }
        },
      ),
    );
    const hotImageURL = `${origin}/api/preview/big/images/${numberedName("needle-image", 0, ".jpg")}`;
    await request(hotImageURL, { headers });
    metrics.image_preview_hot = await sample(options.runs, () =>
      request(hotImageURL, { headers }),
    );

    metrics.search_recursive_cold = await sample(options.runs, (index) =>
      request(
        `${origin}/api/search/?query=${encodeURIComponent(numberedName("needle", index, ""))}&scope=recursive&limit=1000`,
        { headers },
        (_, body) => {
          const firstRecord = body
            .toString("utf8")
            .split("\n")
            .find((line) => line.trim() !== "");
          if (!firstRecord) {
            throw new Error(`${label} returned an invalid search stream`);
          }
          JSON.parse(firstRecord);
        },
      ),
    );
    const hotSearchURL = `${origin}/api/search/?query=needle&scope=recursive&limit=1000`;
    await request(hotSearchURL, { headers });
    metrics.search_recursive_hot = await sample(options.runs, () =>
      request(hotSearchURL, { headers }),
    );

    if (options.videoFixture) {
      metrics.video_preview_cold = await sample(options.runs, (index) =>
        request(
          `${origin}/api/preview/big/videos/${numberedName("needle-video", index, ".mp4")}`,
          { headers },
          (response, body) => {
            if (
              !response.headers.get("content-type")?.startsWith("image/") ||
              body.length === 0
            ) {
              throw new Error(`${label} returned an invalid video cover`);
            }
          },
        ),
      );
      const hotVideoURL = `${origin}/api/preview/big/videos/${numberedName("needle-video", 0, ".mp4")}`;
      await request(hotVideoURL, { headers });
      metrics.video_preview_hot = await sample(options.runs, () =>
        request(hotVideoURL, { headers }),
      );
    }

    return { metrics, logs: getLogs() };
  } finally {
    await stopServer(child);
  }
}

function compare(baseline, candidate) {
  const result = {};
  for (const [name, baselineMetric] of Object.entries(baseline)) {
    const candidateMetric = candidate[name];
    if (!candidateMetric) continue;
    const improvement =
      ((baselineMetric.median_ms - candidateMetric.median_ms) /
        baselineMetric.median_ms) *
      100;
    result[name] = {
      baseline_median_ms: baselineMetric.median_ms,
      candidate_median_ms: candidateMetric.median_ms,
      improvement_percent: Number(improvement.toFixed(2)),
    };
  }
  return result;
}

const options = parseArgs(process.argv.slice(2));
const dataset = await createDataset(options);
const baseline = await benchmarkBinary(
  "baseline",
  options.baselineBinary,
  options.baselinePort,
  dataset,
  options,
);
const candidate = await benchmarkBinary(
  "candidate",
  options.candidateBinary,
  options.candidatePort,
  dataset,
  options,
);
const report = {
  generated_at: new Date().toISOString(),
  environment: {
    platform: platform(),
    architecture: arch(),
    node: process.version,
  },
  parameters: {
    runs: options.runs,
    ordinary_files: options.ordinaryFiles,
    media_files_per_directory: options.mediaFiles,
    video_preview: options.videoFixture
      ? "measured"
      : "skipped-no-valid-fixture",
  },
  baseline: baseline.metrics,
  candidate: candidate.metrics,
  comparison: compare(baseline.metrics, candidate.metrics),
};

const reportPath = path.join(options.workDir, "report.json");
await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify({ reportPath, ...report }, null, 2));
