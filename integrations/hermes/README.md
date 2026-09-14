# open-android-intelligence Hermes Gateway

Native Python Gateway Protocol v2 plugin for Hermes.

The plugin requires Python 3.12 or newer. Sensitive Gateway state is only
enabled when the Hermes secret store supplies an authenticated AEAD provider;
without one the plugin fails closed.
## 运行要求

* Python 3.12 或更高版本
* Hermes Agent 宿主环境

## 一条命令安装

可以通过 pip 直接从源码或 Wheel 单文件安装：

```bash
# 从仓库源码安装
pip install -e integrations/hermes

# 或直接安装打包好的 Wheel 单文件
pip install dist/open_android_intelligence_hermes_gateway-2.0.0-py3-none-any.whl
```

## 向导式配置（推荐）

在 Hermes 宿主环境中运行原生配置向导：

```bash
hermes gateway setup
# 或 hermes setup gateway
```

在平台列表中勾选：
```text
[X] 📱 Open Android Intelligence (Gateway v2)
```

向导将自动引导完成：
1. 是否为手机创建连接账号；
2. 输入账号名称（例如 `phone1`）；
3. 密文输入并确认访问密码；
4. 本地确认并自动分配独立存储沙箱（包含 SQLite 数据库与加密凭据）。

无需执行任何外部 Python 脚本即可一步完成账号初始化。

## 原生命令行管理

安装后，Hermes 自动挂载 `open-android-intelligence` 原生子命令：

```bash
# 查看所有已配对账号
hermes open-android-intelligence account list

# 命令行直接创建新账号
hermes open-android-intelligence account create -u <用户名> -p <密码> --confirm-local

# 查看网关运行状态
hermes open-android-intelligence status

# 删除账号及其沙箱数据
hermes open-android-intelligence account delete <用户名> --confirm-local
```

## 自动化测试

使用 pytest 运行单元与集成测试：

```bash
pytest integrations/hermes/tests/
```

