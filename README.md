# Codex Remote Android

面向个人 Demo 的 Android 原生客户端。第一版聚焦最短可用链路：在 Android 设备内运行 Tailnet，并连接 Codex Remote Host。

## 当前范围

- Android 16（API 36）
- `arm64-v8a` 与 `x86_64`
- Kotlin + Jetpack Compose
- 中文竖屏界面
- 默认 Host：`ws://codex-remote-linux/connect`

应用已接入 `MobileCore` AAR，在进程内启动 Tailnet，并通过 Codex Remote
协议 v1.1.2 连接 Host。首次使用需要登录 Tailscale 时，界面会显示登录入口；
连接成功后会执行 `GetHost` 和 `ListCodexes`，并在首页展示 Tailnet 状态、
可编辑且持久化的 Host 地址以及 Host 返回的 Codex 列表。

第一版已包含：

- 内嵌 Tailnet 登录、Host 连接和状态展示。
- 项目目录选择，以及 Codex/session 的创建、导入、打开、重命名、解除管理和忘记。
- 会话历史、Markdown 消息、reasoning、plan、command、diff 和失败状态组成的 rich timeline，支持发送和中断回合。
- approval 和 user-input 待处理请求的展示与回复。
- workspace 目录浏览、文本文件查看/编辑，以及通过 Android SAF 上传普通文件或 ZIP、下载文件或目录 ZIP。
- 当前会话通过 Host watch 事件实时更新；连接中断重连后会从已知位置继续，并在回合结束后校准历史。
- 按 Codex 分别保存未发送草稿和最后打开的会话，应用进程重建后可恢复到原会话。
- 对话和项目文件之外新增“任务上下文”页，集中展示 cwd、Codex/turn 状态、待处理请求、当前 turn 文件变化以及去重后的警告与标识。

## 验证范围

当前 v0.3.0 候选版本的 MobileCore Go 全量与 `-race` 测试均通过；普通
`go test -json -count=1 ./...` 运行共有 132 个通过的 Test/subtest 事件；JVM 129/129、
API 36 x86_64 低资源模拟器 connected suite 51/51。connected 稳定配置为 1536 MiB、
2 核、720x1600@320、SwiftShader GLES 并明确禁用 Vulkan；emulator 实际 guest
`MemTotal` 约 2532296 kB。
现有自动化覆盖连接与错误状态、项目目录和 session 可用性、会话管理、rich timeline、
pending request、workspace/SAF 边界、诊断导出、前台恢复、进程重建恢复及任务上下文页。

应用离开前台至少 10 秒后再次回到前台时，会先单次刷新；刷新失败时，最多执行一次标准
stop/config/start，并重新选择原来打开的 Codex。运行中的回合、待审批/用户输入和正在
执行的 UI 操作不会被打断。这里没有后台定时器、循环重连、前台服务、通知或 WakeLock，
也不代表应用能在后台长驻。

vivo X200 Ultra（OriginOS 6）真机已验证内嵌 Tailnet 连接真实 Host、后台 120 秒和
锁屏 120 秒后均单次恢复原会话、新消息在当前页自动出现、无 request-ID 冲突，以及
诊断导出。功耗边界检查未发现 service、前台服务、通知或 WakeLock。

当前官方 API 36 x86_64 16 KB system image 不可用，实际 guest page size 为 4096 bytes；
APK 中 arm64 native ELF 的 `LOAD` segment 对齐为 `0x4000`，且该 native 库已在真机成功加载。
这些证据不等同于 16 KB guest 模拟器验证。

尚未验证或宣称支持 OriginOS 长期锁屏、冻结、省电策略下的后台保活，也未完成 Clash
开启状态下的真机专项联调。

## 下载 APK

当前个人 Demo 可从 [GitHub Release v0.3.0](https://github.com/FireflyTang/codex-remote-android/releases/tag/v0.3.0)
下载 `codex-remote-android-v0.3.0-debug.apk`，或使用
[APK 直链](https://github.com/FireflyTang/codex-remote-android/releases/download/v0.3.0/codex-remote-android-v0.3.0-debug.apk)。
这是便于试用的 debug APK，并非商店签名的正式发行包。

## 构建

首次使用先准备仓库内的 JDK、Android SDK 与模拟器：

```bash
scripts/android/bootstrap.sh
```

之后构建、测试或安装到已启动的设备：

```bash
scripts/android/build.sh
scripts/android/test.sh
scripts/android/install.sh
```

`scripts/android/test.sh --instrumented` 会运行全量 connected suite，并卸载/重装 app 和
test package，从而清除模拟器中的 app data 与 Tailnet 授权。需要保留真实 Host
状态时，应将 connected test 与真实 Host smoke 分开执行。默认 smoke 只读，
不会发送真实 `StartTurn`；详见 [Android 测试文档](docs/android_testing.md)。

手工运行 `./gradlew` 前先在同一 shell 中执行 `source scripts/android/env.sh`，以复用仓库
约定的 Android user home 和 debug keystore，避免因使用不同 debug keystore 导致安装失败。

`:app:preBuild` 会通过 `mobilecore/build-aar.sh` 重新生成并校验约定路径
`mobilecore/build/mobilecore.aar`；该生成产物不会提交到 Git。

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。
