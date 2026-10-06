import path from "node:path";
import fs from "node:fs";
import { defineConfig, type Plugin } from "vite";
import vue from "@vitejs/plugin-vue";
import { compression } from "vite-plugin-compression2";

type AssetTree = {
  source: string;
  output: string;
  include?: (relativePath: string) => boolean;
};

const editorAssetTrees: AssetTree[] = [
  {
    source: path.resolve(__dirname, "node_modules/vditor/dist"),
    output: "vditor/dist",
    include: (relativePath) =>
      !relativePath.startsWith("ts/") &&
      !relativePath.startsWith("types/") &&
      !relativePath.endsWith(".d.ts"),
  },
  {
    source: path.resolve(
      __dirname,
      "node_modules/ace-builds/src-min-noconflict"
    ),
    output: "ace",
  },
];

const walkFiles = (root: string, current = root): string[] =>
  fs.readdirSync(current, { withFileTypes: true }).flatMap((entry) => {
    const absolute = path.join(current, entry.name);
    return entry.isDirectory() ? walkFiles(root, absolute) : [absolute];
  });

const contentType = (filename: string): string => {
  switch (path.extname(filename)) {
    case ".js":
      return "text/javascript; charset=utf-8";
    case ".css":
      return "text/css; charset=utf-8";
    case ".json":
      return "application/json; charset=utf-8";
    case ".svg":
      return "image/svg+xml";
    case ".png":
      return "image/png";
    case ".gif":
      return "image/gif";
    case ".woff":
      return "font/woff";
    case ".woff2":
      return "font/woff2";
    default:
      return "application/octet-stream";
  }
};

/** Keep Ace and Vditor runtime-loaded assets on the same origin. */
const localEditorAssets = (): Plugin => ({
  name: "local-editor-assets",
  configureServer(server) {
    server.middlewares.use((request, response, next) => {
      const pathname = decodeURIComponent(
        new URL(request.url ?? "/", "http://localhost").pathname
      );
      const tree = editorAssetTrees.find(
        ({ output }) =>
          pathname === `/${output}` || pathname.startsWith(`/${output}/`)
      );
      if (!tree) {
        next();
        return;
      }

      const relative = pathname.slice(tree.output.length + 2);
      const target = path.resolve(tree.source, relative);
      if (
        target !== tree.source &&
        !target.startsWith(`${tree.source}${path.sep}`)
      ) {
        response.statusCode = 403;
        response.end();
        return;
      }
      if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
        next();
        return;
      }
      response.setHeader("Content-Type", contentType(target));
      response.setHeader("Cache-Control", "no-cache");
      fs.createReadStream(target).pipe(response);
    });
  },
  generateBundle() {
    for (const tree of editorAssetTrees) {
      for (const filename of walkFiles(tree.source)) {
        const relative = path
          .relative(tree.source, filename)
          .split(path.sep)
          .join("/");
        if (tree.include && !tree.include(relative)) continue;
        this.emitFile({
          type: "asset",
          fileName: `${tree.output}/${relative}`,
          source: fs.readFileSync(filename),
        });
      }
    }
  },
});

const plugins = [
  vue(),
  localEditorAssets(),
  compression({
    include: /\.(js|mjs|css)$/,
    algorithms: ["gzip", "brotliCompress"],
    deleteOriginalAssets: false,
  }),
];

const resolve = {
  alias: {
    "@/": `${path.resolve(__dirname, "src")}/`,
  },
};

// https://vitejs.dev/config/
export default defineConfig(({ command }) => {
  if (command === "serve") {
    return {
      plugins,
      resolve,
      server: {
        proxy: {
          "/api/command": {
            target: "ws://127.0.0.1:8080",
            ws: true,
          },
          "/api": "http://127.0.0.1:8080",
        },
      },
    };
  } else {
    // command === 'build'
    return {
      plugins,
      resolve,
      base: "",
      build: {
        rollupOptions: {
          input: {
            index: path.resolve(__dirname, "./public/index.html"),
          },
          output: {
            manualChunks: (id) => {
              // bundle dayjs files in a single chunk
              if (id.includes("dayjs/")) {
                return "dayjs";
              }
            },
          },
        },
      },
      experimental: {
        renderBuiltUrl(filename, { hostType }) {
          if (hostType === "js") {
            return { runtime: `window.__prependStaticUrl("${filename}")` };
          } else if (hostType === "html") {
            return `[{[ .StaticURL ]}]/${filename}`;
          } else {
            return { relative: true };
          }
        },
      },
    };
  }
});
