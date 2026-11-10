# SDK 官网下载与 SmartBox 接入

核对日期：2026-11-10。本指南提供无需取得原开发者私有压缩包的官方获取路径，面向 Windows＋Ubuntu 开发组合。只运行网页和模拟节点时不需要固件 SDK；需要修改或编译真实固件时再完成以下步骤。

## 1. 下载入口与要准备的内容

这里的 SDK 是 BearPi-HM Nano 配套的完整 OpenHarmony 工程，包括框架、Hi3861 平台代码、驱动和依赖，不是只有传感器例程。SmartBox 固件应用源码已在本仓库 firmware，不需要另找一套 POSE 工程。

| 内容 | 官方入口 | 操作 |
|---|---|---|
| 总入口与现成 Ubuntu 环境 | [BearPi 十分钟上手](https://bearpi.cn/dev_board/bearpi/hm/nano/software_OpenHarmony/BearPi-HM_Nano%E5%8D%81%E5%88%86%E9%92%9F%E4%B8%8A%E6%89%8B.html) | 第二节打开软件表，选择 Ubuntu 镜像；已有可用 Ubuntu 可跳过导入 |
| 完整 SDK 源码 | [官方 BearPi-HM_Nano 仓库](https://gitee.com/bearpi/bearpi-hm_nano) | 推荐在 Ubuntu 用 Git 获取，保留来源提交；也可选择 Clone/Download → Download ZIP |
| 编译工具与安装说明 | [官方环境搭建文档](https://gitee.com/bearpi/bearpi-hm_nano/blob/master/applications/BearPi/BearPi-HM_Nano/docs/quick-start/BearPi-HM_Nano%E5%BC%80%E5%8F%91%E6%90%AD%E5%BB%BA%E7%8E%AF%E5%A2%83.md) | 在安装编译工具章节找到 GN、Ninja、GCC 下载 |
| 工具下载分享页 | [官方环境文档所列网盘](https://pan.baidu.com/s/1vSpPWvIhL8wCVGnEOjXS7g?pwd=1234) | 页面公开提取码为 1234；按下表选择 Linux 归档，不将提取码当账号密码 |
| HiBurn 与串口驱动 | [官方入门软件表](https://bearpi.cn/dev_board/bearpi/hm/nano/software_OpenHarmony/BearPi-HM_Nano%E5%8D%81%E5%88%86%E9%92%9F%E4%B8%8A%E6%89%8B.html) | 仅烧录时在 Windows 准备；不是 Ubuntu 编译依赖 |

上述官网、官方仓库与文档页面本次可访问；Git 远端引用读取成功。**没有在本轮下载网盘二进制、导入虚拟机或编译官网下载的新源码**，不能将这些操作写成已通过。网盘可能需要浏览器、登录或客户端；页面入口可打开不代表下载权限及所有文件永久可用。若入口变化，回到官方文档查更新，不从未知镜像替换工具链。

SDK 和工具链来源、版本及本项目既有快照校验摘要已提交在[依赖来源记录](../../../deploy/sdk/sources.json)，无需依赖本机 artifacts 才能读到这些信息。

## 2. 两种版本的关系

- **官网下载源码**：公开获取渠道，有 Git 提交可追踪；新取得的版本需要做下文适配检查和实际编译。
- **项目已验证快照**：此前本地 SDK 清理后交付，已实际安装并完成 ENV/POSE 构建；缺少上游 Git 元数据，不能声称它等于官方某个提交。

本次直接读取官方 master 的结果为 `275854412f9d3f1b8fae62aeef25b81095b449ac`，这里只记录查询事实，**不是兼容版本推荐**。网页缓存展示的提交可能滞后，后续 master 也可能变化。不要将 bundle.json 的 1.0.0 标识或编译器版本相同当成全部源码相同。

如需原结果的完全一致输入，仍使用维护者提供的已验证清单包；如选官网下载路线，则取得、记录并验证自己的明确版本。仓库没有承诺未验证的上游提交可直接复现历史镜像。

## 3. Ubuntu 获取完整源码

以下在 Ubuntu 终端执行。先确认目标名称尚未使用，**不要覆盖已有学习 SDK**：

```bash
mkdir -p "$HOME/project"
cd "$HOME/project"
git clone --branch master --single-branch https://gitee.com/bearpi/bearpi-hm_nano.git bearpi-hm_nano-official
cd bearpi-hm_nano-official
git remote get-url origin
git rev-parse HEAD
git status --short
```

成功判据：origin 是上表官方地址、HEAD 返回完整提交号，工作区没有未解释的改动。记录当次 HEAD；开始适配后不要随意 git pull 改变输入。如需另一台电脑取得同一版本，在已克隆仓库中 checkout 记录的完整提交号并再次核对，不填写未经验证的“推荐 SHA”。

Git 下载失败时先检查 Ubuntu 网络和 Gitee 的访问提示，不把失败命令留下的半成品目录当完整 SDK。若出现认证要求，在自己的终端按平台提示处理，不把访问令牌写入仓库、URL或共享日志。

使用网页 ZIP 的替代步骤：打开官方仓库 → 选择所需分支/提交 → Clone/Download → Download ZIP → 传到 Ubuntu → 在新目录解压。解压后定位真正包含 build.py 的那一层，不将外层下载文件夹误填为 SDK。ZIP 通常不带 .git，需另外记录下载页地址、提交信息和压缩包 SHA256；无法确认提交时明确记为未知。

## 4. 编译工具放在哪里

在官方工具分享中核对以下文件名；这是旧 Hi3861 工程配套工具，不以发行版最新的 riscv64 编译器替代：

| 文件 | 目标目录 | 期望检查结果 |
|---|---|---|
| gn.1523.tar | ~/gn | gn --version 为 1523 |
| ninja.1.9.0.tar | ~/ninja | ninja --version 为 1.9.0 |
| gcc_riscv32-linux-7.3.0.tar.gz | ~/gcc_riscv32 | riscv32-unknown-elf-gcc 为 7.3.0 |

将下载归档放到 Ubuntu 自己的下载目录，例如 ~/smartbox-downloads。若经 Windows 上传，用 MobaXterm SFTP 或自己的传输工具即可，映射盘不是必需条件。

先查看归档内容并记录本地哈希：

```bash
cd "$HOME/smartbox-downloads"
sha256sum gn.1523.tar ninja.1.9.0.tar gcc_riscv32-linux-7.3.0.tar.gz
tar -tf gn.1523.tar
tar -tf ninja.1.9.0.tar
tar -tf gcc_riscv32-linux-7.3.0.tar.gz | head -n 20
```

确认来自官方入口、路径结构符合预期，且 ~/gn、~/ninja、~/gcc_riscv32 未存放需要保留的工具后再按官方归档布局解压：

```bash
tar -xf gn.1523.tar -C "$HOME"
tar -xf ninja.1.9.0.tar -C "$HOME"
tar -xf gcc_riscv32-linux-7.3.0.tar.gz -C "$HOME"
```

不要叠加覆盖已有工具。实际归档顶层若不同，先使用独立临时目录查看，再安排新工具目录；不要猜路径，也不要仅给不可执行或错误平台的文件添加权限来掩盖问题。

本地计算 SHA256 只能追踪之后是否变化，不能单独证明来源；本指南不提供未经实际下载核对的“官网文件哈希”。它也不能与项目重新打包的 toolchains.tar.gz 哈希直接比较：两者不是同一个压缩包。

## 5. 核对现有环境

已有相同 Ubuntu 时优先检查，不重复安装或升级：

```bash
export PATH="$HOME/gn:$HOME/ninja:$HOME/gcc_riscv32/bin:$PATH"
command -v python python3 scons gn ninja riscv32-unknown-elf-gcc
python --version
python3 --version
scons --version
gn --version
ninja --version
riscv32-unknown-elf-gcc --version
riscv32-unknown-elf-ld --version
```

项目历史实测为 Ubuntu18.04、Python3.8.0、SCons3.0.1，工具链如上，链接器2.31.1。官方环境文档的 SCons 建议版本与历史实测存在差异；这里保留两者来源，不自动把既有环境升级成“最新”。缺 Python/SCons/基础编译依赖时按官方环境文档准备并记录实际版本，新增组合须实际验证。

上面的 export 仅影响当前终端。Windows 发起的非交互 SSH 未必读取 .bashrc，因此 SmartBox 会从 workspace 的 tool_paths 为构建进程设置 PATH。只在交互终端看到版本并不能证明远程构建也能找到工具。

## 6. 核对源码结构并接入 SmartBox

先在 Ubuntu 的 SDK 根目录确认这些项目所需位置，不执行官方构建、不修改入口：

```bash
ls build.py bundle.json
ls applications/BearPi/BearPi-HM_Nano/sample/BUILD.gn
ls kernel/liteos_m/components/cmsis/2.0
ls third_party/paho_mqtt/MQTTClient-C/src third_party/cJSON
ls utils/native/lite/kv_store/src/BUILD.gn
ls vendor/hisi/hi3861/hi3861/include/hi_fs.h
```

这是依据本项目 GN/适配代码列出的前置检查，不是官方保证所有版本都具有相同布局。缺文件、GN目标不一致或CMSIS接口不同，意味着需要定位版本差异；不要直接覆盖原学习SDK或用临时空实现骗过链接。

Windows 完成[完整部署指南](../../delivery/deployment/开发者完整部署指南.md)的环境、私有配置和 SSH 准备，然后编辑忽略文件 secrets/workspace.local.json 中的 ubuntu 对象：

```json
{
  "host": "YOUR_UBUNTU_IP",
  "user": "YOUR_UBUNTU_USER",
  "port": 22,
  "key": "C:/Users/YOUR_USER/.ssh/id_ed25519_smartbox",
  "sdk": "/home/YOUR_UBUNTU_USER/project/bearpi-hm_nano-official",
  "tool_paths": [
    "/home/YOUR_UBUNTU_USER/gn",
    "/home/YOUR_UBUNTU_USER/ninja",
    "/home/YOUR_UBUNTU_USER/gcc_riscv32/bin"
  ]
}
```

这是 ubuntu 对象示例，外层仍保留 format:1 和 ubuntu 键；不能把它直接替代整个 workspace 文件。地址、账户和目录全部换成自己的值，不复制原作者的私钥或 IP。

```powershell
.venv/Scripts/python tools/project.py doctor
.venv/Scripts/python tools/sdk_bundle.py inspect
```

inspect 是只读盘点；doctor 不等于完整兼容认证。确认硬件本地配置、账号和启动区间准备后，按[编译烧录指南](编译烧录与联调流程.md)执行 firmware-build。不要在未备份时先手工运行会清理旧输出的官方构建脚本。

## 7. 什么时候可以登记为可用

官网下载路线必须逐级取得证据：

1. 下载来源、源码提交/压缩包哈希和实际工具版本明确。
2. 所需目录、头文件和 GN 目标存在，受控同步预览无冲突；入口选择满足工具要求。
3. ENV、POSE 分别实际编译和链接，应用符号和身份进入最终产物，各自日志及 SHA256 完整。
4. 构建前入口、旧输出及厂商状态恢复并核验。
5. 若要声称板端可用，再完成对应板件烧录、启动、采集、网络与实物动作验证。

只有前三四项通过才能称“此官网下载版本已在本项目完成构建适配”，不能直接沿用旧快照的构建或板端结论。失败时记录具体差异和已完成范围，不修改冻结契约、业务规则或旧迁移来适配 SDK。

如需将自行验证的 SDK 交接给他人，可使用 sdk_bundle.py export 生成带清单的独立交付包，步骤见完整部署指南。源码和下载说明随 Git 维护；本地已验证 SDK 包仍可作为另一条获取路径，不要求把完整 SDK 复制进业务仓库。
