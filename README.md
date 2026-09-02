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

## 验证范围

真机已验证内嵌 Tailnet 连接真实 Host、加载会话历史、`StartTurn` 和
`InterruptTurn`。rich timeline、workspace、pending request 和 SAF 传输是后续增加的
功能，当前仅通过 JVM 单测和 API 36 模拟器验收，不表示它们已在真机上全部联调。

最近一次 API 36 x86_64 模拟器全量 connected suite 为 25/25 通过。当前官方
API 36 x86_64 16 KB system image 不可用，实际 guest page size 为 4096 bytes；
APK 中 arm64 native ELF 的 `LOAD` segment 对齐为 `0x4000`，且该 native 库已在真机成功加载。
这些证据不等同于 16 KB guest 模拟器验证。

后台长驻、锁屏/冻结、省电策略和 Clash VPN 兼容性明确冻结，不纳入第一版验收。

## 下载 APK

当前个人 Demo 可从 [GitHub Release v0.2.0](https://github.com/FireflyTang/codex-remote-android/releases/tag/v0.2.0)
下载 `codex-remote-android-v0.2.0-debug.apk`，或使用
[APK 直链](https://github.com/FireflyTang/codex-remote-android/releases/download/v0.2.0/codex-remote-android-v0.2.0-debug.apk)。
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

`:app:preBuild` 会通过 `mobilecore/build-aar.sh` 重新生成并校验约定路径
`mobilecore/build/mobilecore.aar`；该生成产物不会提交到 Git。

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。
