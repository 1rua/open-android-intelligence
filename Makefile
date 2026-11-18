# Open Android Intelligence 工程统一命令入口
# 所有路径均相对于仓库根目录，禁止在此文件中写入任何绝对路径。

.PHONY: help bootstrap build-wasm typecheck lock-check test-fast test-pr test-full

help:
	@printf '可用目标说明:\n'
	@printf '  make bootstrap   安装根及各子包 Node 依赖，并配置 Git Hooks 路径 (.githooks)\n'
	@printf '  make build-wasm  使用 Rust 1.90.0 编译受保护插件 WASM 产物\n'
	@printf '  make typecheck   执行根目录与 plugin-tooling 的 TypeScript 类型检查\n'
	@printf '  make lock-check  校验远端受控来源与版本锁一致性\n'
	@printf '  make test-fast   执行日常快速增量验证 (Level 1)\n'
	@printf '  make test-pr     执行 PR 准入验证 (Level 2)\n'
	@printf '  make test-full   执行全量回归验证 (Level 3)\n'

bootstrap:
	npm ci
	npm ci --prefix plugin-tooling
	npm ci --prefix tools/remote-server
	npm ci --prefix tools/remote-interop
	npm ci --prefix integrations/oai-android-mcp
	@printf '正在配置本地 Git Hooks 目录为 .githooks ...\n'
	git config core.hooksPath .githooks
	@printf '完成！如需卸载自定义 Hooks，可执行: git config --unset core.hooksPath\n'

build-wasm:
	cargo +1.90.0 build --locked --target wasm32-unknown-unknown --release --manifest-path plugins/Cargo.toml --target-dir plugins/target

typecheck:
	npm run typecheck

lock-check:
	npm run lock:check

test-fast:
	npm run test:fast

test-pr:
	npm run test:pr

test-full:
	npm run test:full
