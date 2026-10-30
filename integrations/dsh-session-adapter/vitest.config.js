export default {
  cacheDir: "./integrations/dsh-session-adapter/.test-cache/vite",
  test: {
    include: ["integrations/dsh-session-adapter/*.test.js"],
    environment: "node",
    fileParallelism: false,
    maxWorkers: 1,
    testTimeout: 2000,
  },
};
