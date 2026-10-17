<p align="center">
  <img src=".github/icon.png" width="96" alt="DFRoot 图标" />
</p>

# DFRoot Shizuku

DFRoot Shizuku 是一个 Android 临时 Root 工具。它在受支持的设备上执行提权，并通过兼容 KernelSU 的管理器管理 Root 权限。Root 属于本次开机状态，设备完整重启后需要重新提权。

本项目基于 [diabl0w/DFRoot](https://github.com/diabl0w/DFRoot) 上游4.5版，增加 Shizuku 临时环境准备、应用列表授权处理、中文界面和全屏日志。**本版的手动提权需要先启动并授权 Shizuku。** 上游同步范围与详细改动见[修改说明](修改说明.md)。

[使用步骤](#使用步骤) · [常见问题](#常见问题) · [项目结构与开发规则](#项目结构与开发规则)

## 先弄清楚三个应用的作用

Root 是 Android 的系统管理员权限。下面三个应用各有职责，需要配合使用：

| 应用 | 作用 |
|---|---|
| DFRoot Shizuku | 执行提权、检查运行条件，并显示结果与日志。 |
| Shizuku | 让本应用在提权前有权限准备临时系统设置。它显示“已运行”不等于 DFRoot 已取得 Root。 |
| SU 管理器 | 提供 DFRoot 需要的管理器程序，并在提权成功后管理其他应用的 Root 授权。 |

部分设备的 init 日志设置会影响提权触发。本版通过 Shizuku 临时调整 `log.tag.init`，确认设置生效后再执行提权，结束后恢复原值。具体排查依据见修改说明中的荣耀 Magic7 Pro 案例。

## 使用前需要什么

| 项目 | 要求 |
|---|---|
| 本项目版本 | `5.1-cn`，应用包名为 `df.root.shizuku.cn`。 |
| 设备 | ARM64，Android 12L / API 32 或更高版本。 |
| 内核 | 能通过下方的 KMI 检查；固件和安全补丁也会影响实际结果。 |
| Shizuku | 13 或以上版本，服务已启动，并允许 DFRoot Shizuku 使用。 |
| SU 管理器 | 兼容 KernelSU，且能被本应用识别。其安装内容需要提供 `libksud.so`。 |

- 本项目安装包：查看 [Releases](https://github.com/kun520ovo/DFRoot/releases)。
- Shizuku：[官方下载](https://shizuku.rikka.app/download/)与[启动指南](https://shizuku.rikka.app/guide/setup/)。
- 管理器可从 [KernelSU](https://github.com/tiann/KernelSU/releases/latest)、[KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next/releases/latest) 等兼容项目选择；最终以本应用能否识别该管理器为准。

### 支持哪些内核

KMI 用来标识内核所属的 Android 分支与内核主次版本。当前内置以下 8 个条目：

| KMI | KMI |
|---|---|
| `android12-5.10` | `android13-5.10` |
| `android13-5.15` | `android14-5.15` |
| `android14-6.1` | `android15-6.6` |
| `android16-6.12` | `android17-6.18` |

判断时看应用中的“匹配 KMI”，不要只看手机设置里的 Android 版本。例如，Android 16 系统使用 `6.6.89-android15` 内核时，应匹配 `android15-6.6`。绿色 KMI 与列表中的 `✓` 表示通过了应用的内核检查，最终是否取得 Root 还要看执行结果。

## 使用步骤

1. **安装应用。** 安装本项目的已签名 APK、Shizuku 和一个兼容的 SU 管理器。当前测试按卸载旧版 DFRoot Shizuku 后重新安装处理；先完成旧版正在进行的提权或恢复任务。
2. **启动 Shizuku。** 打开 Shizuku，按其指南启动服务，确认它显示正在运行。
3. **允许读取应用列表。** 打开 DFRoot Shizuku，完成系统授权。主按钮显示“请先允许读取应用列表”时，点击它请求授权；如果变为“打开权限设置”，点击进入本应用的系统设置，允许读取应用列表后返回。
4. **选择 SU 管理器。** 只有一个可用管理器时会自动选中；有多个时选择你要使用的那个。如果未找到管理器，按下方常见问题排查，再点击“重新读取应用列表”。
5. **核对内核。** 确认“匹配 KMI”显示受支持的条目。显示“不支持”时不能启动提权。
6. **完成 Shizuku 授权。** 点击“检查 Shizuku”，按提示允许本应用使用 Shizuku，等待服务已运行、已授权且没有待恢复设置。
7. **启动并等待结果。** 点击“启动 Root”，等待提权和临时设置恢复结束。需要查看完整输出时，点击“运行日志”右侧的“全屏”，可长按选择并复制文本。
8. **到管理器确认。** 打开所选 SU 管理器，确认它已识别 Root，再按需给其他应用授权。

读取应用列表和使用 Shizuku 是两项不同的授权，需要分别完成。授权成功只刷新准备状态，不会自动执行提权。

结果需要分开看：**Root 是否成功、临时设置是否恢复、自动软重启是否已请求**。如果提示恢复未完成，重新启动或连接 Shizuku，再回到本应用检查；恢复未完成前不能再次手动提权。

设备完整重启后，如果通过无线调试启动 Shizuku，需要先重新启动该服务，再进行下一次手动提权。

## 设置选项

| 设置 | 默认状态 | 开启后的行为 |
|---|---|---|
| 开机自启 | 关闭 | 开机后使用保存的管理器配置尝试提权。这个入口独立运行，不依赖 Shizuku，也不执行前台的临时日志准备流程。 |
| 自动软重启 | 关闭 | 手动提权成功、临时设置恢复并清理恢复记录后，等待管理器初始化，再请求软重启。运行期间不可修改。 |
| 禁用所有模块 | 关闭 | 下次运行时先为已安装模块设置禁用标记，用于处理模块导致的启动异常。之后需要在管理器中按需重新启用模块。 |

开启自动软重启后仍可手动启动 Root。需要 Shizuku 临时日志准备的设备，应单独确认开机自启是否可用。

## 常见问题

| 问题 | 如何处理 |
|---|---|
| Shizuku 已运行，为什么仍提示未授权？ | Shizuku 服务状态与本应用的授权不同。回到本应用点击“检查 Shizuku”，完成授权。 |
| 应用列表权限被拒绝，系统不再弹窗 | 点击主按钮“打开权限设置”，允许读取应用列表后返回；不要在上方寻找另一个权限按钮。 |
| 管理器已经安装，但列表为空 | 先确认应用列表权限。该版本管理器还需要提供 `libksud.so`；缺少这个程序时无法被选作管理器。更换兼容版本后重新读取。 |
| 内核版本在列表里，为什么仍失败？ | KMI 匹配只说明有对应模块。厂商改动、安全补丁和原生执行结果仍会影响提权；查看完整日志定位失败阶段。 |
| 日志提示文件补丁失败或设备不受影响 | 上游说明列出的原因包括内核修复或厂商回补缓解措施。Shizuku 的临时准备不能代替这些内核条件。 |
| 提权后无法再次点击启动 | 本次开机检测到已有 Hook 时会跳过重复执行。先到管理器确认当前 Root 状态。 |
| Root 已成功，但提示临时设置未恢复 | 恢复 Shizuku 服务后再次检查，等待恢复完成。Root 成功与设置恢复是不同的结果。 |
| 开启了自动软重启，但没有看到重启 | 查看日志与管理器状态。“已请求”表示已发出命令；超时或未确认会单独提示，不代表已经完成重启。 |
| 安装模块后运行异常 | 在 DFRoot 设置中开启“禁用所有模块”，并在恢复后通过管理器检查有问题的模块。 |

日志只保留在当前会话中，不自动保存或上传。反馈问题时，可附设备型号、Android 版本、完整内核串、所选管理器版本和相关日志。

## 项目结构与开发规则

```text
仓库根目录/
├── README.md                  项目介绍与使用说明
├── AGENTS.md                  维护规则
├── 修改说明.md                主要改动与上游同步记录
├── app/
│   ├── build.gradle.kts        应用身份、SDK、依赖与签名配置
│   └── src/main/
│       ├── java/df/root/shizuku/cn/   Java 代码
│       ├── aidl/df/root/shizuku/cn/   Shizuku 服务接口
│       ├── res/               布局、文字与颜色资源
│       ├── assets/licenses/   依赖许可文本
│       └── jni/               原生代码、KO 与辅助程序
├── lkm/                       内核模块源码
├── gradle/                    Wrapper、JVM 与依赖版本配置
├── .github/workflows/         自动构建流程
├── Makefile                   内核与应用构建入口
├── gradlew                    Linux / macOS 的 Gradle Wrapper 启动脚本
└── gradlew.bat                Windows 的 Gradle Wrapper 启动脚本
```

修改代码前先读 [AGENTS.md](AGENTS.md) 和[修改说明](修改说明.md)，再查对应源码。

项目规则与构建入口都在仓库内，不依赖维护者电脑上的外层目录。提交或打包源码时，保留 KO、`splicehelper`、Wrapper 和已有许可文本，排除密钥、口令、`local.properties`、缓存、设备镜像与本机工具。普通 ZIP 压缩不会自动应用 `.gitignore`，需要检查压缩包实际内容。

## 来源与致谢

- 上游项目：[diabl0w/DFRoot](https://github.com/diabl0w/DFRoot)，作者 [@diabl0w](https://github.com/diabl0w)。
- 本项目：[kun520ovo/DFRoot](https://github.com/kun520ovo/DFRoot)。
- 原始 PoC 与相关代码：[LSPosed/LSPromise](https://github.com/LSPosed/LSPromise)。
- 内核模块与相关代码：[polygraphene/DFReroot](https://github.com/polygraphene/DFReroot)。
- 非特权 XFRM socket 方法：[combeng6th/DirtyInit](https://github.com/combeng6th/DirtyInit)。
- Shizuku 接入使用 API / Provider `13.1.5`，许可文本见 [shizuku-api.txt](app/src/main/assets/licenses/shizuku-api.txt)。

保留上游与依赖原有的作者署名和许可声明。
