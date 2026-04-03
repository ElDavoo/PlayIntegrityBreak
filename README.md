<div align="center">
  <h2>Play Integrity Break</h2>

---

- **English**
- [中文（简体）](README_zh_CN.md)
- [Türkçe](README_tr.md)
- [日本語](README_ja.md)
- [Indonesia](README_id.md)

## About this module

This project is focused on Google Play API observability.

PIB runs as an LSPosed/Xposed module in the Play Store process and intercepts Integrity service request/response activity per target app. It is designed for debugging and telemetry workflows, and supports optional response rewriting for controlled testing scenarios.

## Build with Nix

PIB ships a repo-local Nix development shell for reproducible Android builds.

1. Enter the shell:

```bash
nix develop
```

2. Build all modules:

```bash
nix develop -c ./gradlew :common:assembleDebug :xposed:assembleDebug :app:assembleDebug --no-daemon
```

The shell uses Android SDK components from nixpkgs by default and only falls back to a host SDK when the required platform/build-tools are already present.
