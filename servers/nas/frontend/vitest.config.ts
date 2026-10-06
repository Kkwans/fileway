import path from "node:path";
import { defineConfig, configDefaults } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: {
      "@/": `${path.resolve(__dirname, "src")}/`,
    },
  },
  test: {
    // Playwright owns browser specs under e2e/. Keep the unit/static suite
    // deterministic and let `pnpm run test:e2e` be the explicit browser gate.
    exclude: [...configDefaults.exclude, "e2e/**"],
  },
});
