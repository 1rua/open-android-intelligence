# open-android-intelligence Hermes Gateway

Native Python Gateway Protocol 2.1 plugin for Hermes.

## 运行要求

* Python 3.12 或更高版本
* Hermes Agent 宿主环境

## 一条命令安装

可以通过 pip 直接从源码或 Wheel 单文件安装：

```bash
# 从仓库源码安装
pip install -e integrations/hermes

# 或直接安装打包好的 Wheel 单文件
pip install dist/open_android_intelligence_hermes_gateway-2.1.0-py3-none-any.whl
```

## 向导式配置（推荐）

在 Hermes 宿主环境中运行原生配置向导：

```bash
hermes gateway setup
# 或 hermes setup gateway
```

在平台列表中勾选：
```text
[X] 📱 Open Android Intelligence (Gateway Protocol 2.1)
```

向导将自动引导完成：
1. 是否为手机创建连接账号；
2. 输入账号名称（例如 `phone1`）；
3. 密文输入并确认访问密码；
4. 本地确认并自动分配独立存储沙箱（包含 SQLite 数据库与加密凭据）。

无需执行任何外部 Python 脚本即可一步完成账号初始化。

### 主密钥（自动配置，无需手工步骤）

加载插件时会自动准备 Gateway 主密钥：宿主提供秘密存储时优先使用它，否则在
`~/.open-android-intelligence/gateway-master-key` 生成 32 字节随机密钥，权限固定
`0600`，位置在 Hermes 数据目录之外，不会随数据库、备份或诊断一起导出。每个账号
再用 HKDF-SHA256 从该文件派生独立密钥，账号之间不共享密钥材料。

因此完整的部署流程只有两步：**在 Agent 端安装插件**，然后运行
`hermes gateway setup` 创建账号密码；手机端填入网关地址、账号与密码即可配对使用。

部署者仍可覆盖或手工管理该来源：

```bash
# 指定其它受权限保护的路径（兼容别名 OPEN_ANDROID_GATEWAY_MASTER_KEY_FILE）
echo 'OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE=/secure/path/gateway-master-key' >> ~/.hermes/.env

# 或显式生成（绝不覆盖已有密钥）
./hermes-account.py init-key /secure/path/gateway-master-key
```

密钥文件缺失、是符号链接、不属于当前用户、组或其他用户可读、内容长度不合法，或
目录不可写导致无法生成时，网关**拒绝启动**并在日志中打印可操作原因（ADR 0023、
ADR 0048）。

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

## 修改监听端口与主机地址

网关默认监听在 `0.0.0.0:8045`。您可以通过以下两种方式修改监听端口：

### 方式 1：修改环境变量（推荐）

编辑 `~/.hermes/.env` 文件，添加或修改：

```bash
OPEN_ANDROID_GATEWAY_PORT=8085
# 可选：修改监听地址（默认 0.0.0.0）
OPEN_ANDROID_GATEWAY_HOST=0.0.0.0
```

修改后重启网关服务即可生效：
```bash
hermes gateway restart
```

### 方式 2：在 config.yaml 中配置

编辑 `~/.hermes/config.yaml`，在平台配置中添加：

```yaml
gateway:
  platforms:
    open_android:
      enabled: true
      extra:
        port: 8085
        host: "0.0.0.0"
```

## 自动化测试

使用 pytest 运行单元与集成测试：

```bash
pytest integrations/hermes/tests/
```
