# Android local toolchain and emulator testing

The Android toolchain is repository-local. SDK/JDK/Go downloads live under ignored
`.tools/`; AVDs, Gradle caches, emulator state, and logs live under ignored
`.work/android/`. The scripts do not edit shell profiles or system settings.

## Bootstrap

```bash
scripts/android/bootstrap.sh
source scripts/android/env.sh
```

The bootstrap reuses a system JDK only when it is Java 17; otherwise it downloads
a repository-local Temurin JDK 17. It always installs checksum-verified Go
1.26.6 at `.tools/go`, NDK 30.0.16138531, API 36, Build Tools 36.0.0,
platform-tools, and the emulator. A normal bootstrap also installs an x86_64
system image. It prefers
`system-images;android-36;google_apis_16k;x86_64`; when that exact official image
is absent from `sdkmanager --list`, it prints a warning and records the ordinary
`google_apis;x86_64` fallback. `--without-image` skips only the system image;
Go, the NDK, and all APK/AAR build dependencies are still installed.

Current Linux command-line tools are pinned with their published SHA-256. Override
`CMDLINE_TOOLS_REVISION` and `CMDLINE_TOOLS_SHA256` together when deliberately
updating them.

## Build and test

```bash
scripts/android/build.sh
scripts/android/test.sh
scripts/android/emulator-start.sh
scripts/android/test.sh --instrumented
scripts/android/install.sh
```

Extra Gradle arguments replace the default task, for example:

```bash
scripts/android/build.sh :app:assembleRelease
```

The AVD is a practical approximation of a vivo X200 Ultra: API 36, x86_64,
1440x3200 at 510 dpi, 4 cores, 4 GiB RAM, and a 12 GiB data partition. This is
not a vendor firmware/device-behavior replica. `emulator-start.sh` is headless by
default; pass `--window` for a GUI. It prints the guest page size after boot, so
16 KB mode is evidence-based rather than inferred from the package name. KVM is
used automatically by the Android emulator when `/dev/kvm` is accessible.

At the latest validation, the official API 36 x86_64 16 KB image was not
available and bootstrap selected `system-images;android-36;google_apis;x86_64`.
The running guest reported a 4096-byte page size. The packaged arm64
`libgojni.so` has `0x4000` alignment on every ELF `LOAD` segment and loaded
successfully on the tested physical device, but neither fact substitutes for a
test on a 16 KB guest.

The latest full API 36 connected suite passed 25/25. A physical device has also
been used against a real Tailnet and Host to verify connection, conversation
history, `StartTurn`, and `InterruptTurn`. Rich timeline rendering, workspace,
pending approval/user-input, and SAF upload/download were added later and have
only JVM/API 36 emulator evidence so far; they have not all been revalidated on
the physical device.

### Connected-test data warning

`scripts/android/test.sh --instrumented` runs Gradle's full
`connectedDebugAndroidTest`. The Android Gradle test flow uninstalls/reinstalls
the app and test packages, clearing this app's emulator data and embedded
Tailnet authorization. Do not use it as a prelude to a stateful real-Host smoke.
Run connected tests and real-Host smoke separately, and expect to authorize the
app again if connected tests were run on that same emulator.

To install an explicit APK or collect diagnostics:

```bash
scripts/android/install.sh path/to/app.apk
scripts/android/collect-logs.sh
ANDROID_COLLECT_BUGREPORT=1 scripts/android/collect-logs.sh
```

## Read-only UI smoke

With an authorized emulator/device, an already built debug APK, and a reachable
Host containing at least one Codex session:

```bash
ANDROID_SMOKE_HOST_ENDPOINT=ws://codex-remote-linux/connect \
  scripts/android/smoke.sh
```

The smoke uses only `adb`, `uiautomator dump`, and coordinates parsed from node
bounds. It installs with `adb install -r`, preserving app data and existing
Tailnet authorization, connects to the Host, refreshes the Codex list, opens one
session, waits for its history, and returns. It never taps the message input,
Send, or Stop controls and never sends `StartTurn`. Choose a session with
`ANDROID_SMOKE_CODEX_TITLE`; otherwise it opens the first listed session.

Keep this read-only smoke as the default real-Host check. Sending a real
`StartTurn` can invoke an external model and is intentionally outside both this
script and the default Android test workflow; run such a check only when it is
explicitly authorized.

No device, multiple devices without `ANDROID_SERIAL`, an unauthorized/offline
device, Tailnet login, connection failure, or an empty Host list produces an
explicit error. Once a device is selected, failures also capture diagnostic
artifacts under `.work/android/smoke/`. Only set
`ANDROID_SMOKE_CLEAR_DATA=1` when intentionally deleting this app's saved Host
and Tailnet state. Other useful overrides are `ANDROID_SMOKE_TIMEOUT` and
`ANDROID_SMOKE_ARTIFACTS`; transient Host connection failures are retried twice
by default, configurable with `ANDROID_SMOKE_CONNECT_ATTEMPTS`.

Logs remain under `.work/android/logs/`. Full bugreports are optional because
they are slow and large. Stop a running emulator with
`$ANDROID_SDK_ROOT/platform-tools/adb emu kill` after sourcing `env.sh`.

## Useful overrides

- `ANDROID_API`, `ANDROID_BUILD_TOOLS`, `ANDROID_AVD_NAME`
- `ANDROID_SDK_ROOT`, `ANDROID_WORK_DIR`, `GRADLE_USER_HOME`
- `ANDROID_EMULATOR_TIMEOUT` (seconds; default 240)

Background residency, lock-screen/freeze behavior, power-management handling,
and Clash VPN compatibility are explicitly frozen for the first version. The
scripts do not exercise or claim support for them.
