# Pinned Telegram X bootstrap

This branch starts from the clean main commit `9aea5c14351165d99b8ef901e946e8bab99c079c`. AutoPoll remains isolated on `feature/autopoll` and is not included here. No Agenda code is used.

## Toolchain and dependencies

- JDK 25, repository Gradle Wrapper 9.8.2.
- Android SDK packages `platforms;android-37.2` and `platforms;android-37.0`, build-tools 37.0.0, NDK 30.0.16248370, CMake 3.22.1.
- Native tools: make, C/C++ compiler, gperf, PHP CLI, nasm/yasm, autotools, pkg-config, Ninja, git-lfs.
- `tdlib` gitlink stays `b47cd822037a4c38916f0b78f57298f10a88821e`; its TDLib source stays `a94b83d67ab4c8397c6245e541b31a4131ea2f79`.

Run from the repository root:

```sh
scripts/bootstrap-submodules.sh
python3 scripts/bootstrap-tdlib-lfs.py
./gradlew :app:validateGitSetup
./gradlew :tdlib:tasks
./gradlew assembleLatestDebug --max-workers=4
```

For a first device-specific build, the documented `-Pabi=arm64` option reduces resource use without disabling components. The workflow builds and uploads one latest Debug APK for ARM64 (`arm64-v8a`).

The submodule helper explicitly uses `--checkout`, because some nested OpenSSL dependencies declare `update=rebase`. A plain recursive update previously produced rebase conflicts. It never tracks remote HEAD; on a shallow-history failure it fetches complete history before retrying pinned checkout. It refuses mismatched or missing gitlinks.

The LFS helper first uses normal upstream Git LFS. If GitHub's integration denies the LFS batch endpoint, it can restore public objects from `media.githubusercontent.com` at the exact pinned commit. Every object must match the original pointer size and SHA-256 before entering the local LFS cache; `git lfs fsck` runs before checkout. No TLS or checksum checks are disabled. No binary dependencies are committed. If the media hostname is blocked by the environment policy, enable that destination in environment settings; the script cannot grant access itself.

## CI

Workflow: `.github/workflows/build-apk.yml`, manual dispatch or push to `main` / `build/tdlib-bootstrap`. Configure repository Actions secrets `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` securely. Values are written only to ignored local.properties, never printed. The workflow retains APKs and safe diagnostic logs for 14 days. It initializes submodules manually and recursively with pinned checkout rather than letting checkout apply nested rebase policies.

This is a clean upstream-package Debug build (`org.thunderdog.challegram`), not the future separately installable AutoPoll app. A default debug signing key is for bootstrap testing; release signing and stable distribution are separate work.

## Current validation

- All 58 recursive submodules match their exact gitlinks; no mismatches or merge conflicts remain. A normal recursive update also completes after repair.
- All 66 TDLib/OpenSSL LFS paths are hydrated. Original size and SHA-256 are checked both in the LFS object cache and in each working-tree file; `git lfs fsck --objects` passes.
- Local LFS filters are initialized explicitly, without replacing repository hooks. Merely downloading objects is insufficient: Git LFS can return success from checkout without hydrating files when its filters are not installed. The helper verifies that postcondition and fails if any pointer remains.
- Pinned source checkout temporarily defers automatic LFS smudging so that the explicit verified hydration step can handle an integration's denied batch endpoint.
- `:app:validateGitSetup` and `:tdlib:tasks` pass on JDK 25 / Gradle 9.8.2.
- Actual `:app:validateGitSetup :tdlib:tasks assembleLatestDebug -Pabi=arm64` passed; a complete ARM64 latest Debug APK was produced. Local all-ABI builds have not been validated; CI is configured for them.
- `:app:testLatestDebugUnitTest -Pabi=arm64` passed: 1 test, no failures, errors or skipped tests.
- APK signature verification passes. All packaged native libraries are ELF binaries for arm64-v8a, including libtdjni.so, libtgcallsjni.so and libtgxjni.so. The APK is Telegram X 0.29.1.1820, package org.thunderdog.challegram, minimum Android 13 (API 33).
- The current cloud build uses upstream sample Telegram API configuration; repository Secrets owned by hren4073-cpu are consumed by Actions, not injected into this machine. No account login or Android device behavior has been verified.
- The public media endpoint now works and allowed exact upstream LFS restoration. The GitHub integration still reports pull=true and push=false for hren4073-cpu/Telegram-X. Branch publication and Actions execution remain blocked by write permissions; no Actions run or remote artifact is claimed.

In the current cloud snapshot, `source /workspace/setup/bootstrap-env.sh` activates retained tools and caches. The environment is already isolated; use this checkout and do not create a worktree unless requested. Installed libraries and tools are retained filesystem state; there is no server process to restart. Fresh-task restoration has not been independently validated.
