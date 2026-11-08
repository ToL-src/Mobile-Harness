# Changelog

All notable changes in this fork of [Mobile Harness](https://github.com/techjarves/Mobile-Harness) are listed here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows the upstream app (`versionName` / `versionCode`) plus fork notes under **Unreleased** until we tag.

Upstream in-app **What's New** (`app/src/main/res/values/strings_whats_new.xml`) is a separate user-facing dialog. Update it only when shipping an APK that should show the dialog; this file is the source of truth for the fork.

## [Unreleased]

### Fixed

- Agent sessions no longer abort on in-flight Claude Code `api_retry` / mid-stream auth noise; fatal auth and rate-limit reasons are classified only after final `result` or non-zero exit, and the CLI process is torn down on failure.
- DeepSeek Harness and Antigravity: rate-limit messages are distinct from rejected API key / out-of-credits (with localized Antigravity rate-limit text).

### Planned

- GitLab with a custom host when importing a project.
- Git changes tab: select files, commit message, commit and push.
- Branch switcher on the open project.

## [Upstream 1.0.10]

Baseline of this fork (`19ea1b5`).
