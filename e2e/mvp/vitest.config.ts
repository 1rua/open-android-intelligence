import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    env: {
      TZ: "UTC"
    },
    exclude: [".worktrees/**", ".toolchains/**", "third_party/**", "**/node_modules/**", "tmp/**"],
    fileParallelism: false,
    maxWorkers: 1,
    minWorkers: 1,
    testTimeout: 10_000
  }
});

