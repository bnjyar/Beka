# Beka — notes for contributors and coding agents

Beka is an Android overlay personal assistant: a floating bubble that reads the screen through an
accessibility service, chats with any LLM provider, acts in apps, remembers things about the user,
runs proactive per-app rules, and talks by voice. Native Kotlin + Jetpack Compose, minSdk 30,
targetSdk 36.

## Layout
- `core/llm` — provider layer (OpenAI-compatible + Anthropic-compatible adapters, SSE). No vendor SDKs: OkHttp + kotlinx.serialization.
- `core/memory` — long-term memory: `MemoryItem`/`MemoryStore`, word-overlap ranking, profile + per-request context, remember/recall/forget tools, end-of-session `Extractor`.
- `core/rules` — proactive rules: `Rule` JSON model (triggers app_opened/app_closed/notification/schedule, conditions, levels notify/suggest/confirm/auto), `RuleEngine` (per-app permission cap, cooldown, budget, `llm_check` last), `RuleDrafter` (text → rule), prompts.
- `core/agent` — agent loop (tools, risk gate, blocklist) over a `Device` interface, optional `Memory`.
- `core/web` — `WebSearch` (TinyFish, Brave, Tavily, DuckDuckGo fallback), `PageFetcher` (private-address guard), `WebTools`.
- `core/mcp` — MCP client (Streamable HTTP, static header auth): `McpClient`, `McpToolKit` (remote tools as `mcp_<server>__<tool>`).
- `app` — the Android app: accessibility service + `AgentDevice` (`accessibility/`), bubble + panel (`overlay/`), chat session (`chat/`), SQLite memory + Memory screen (`memory/`), rules controller + Rules screen (`rules/`), virtual display for background runs (`display/`), voice (`voice/`), settings with Keystore-encrypted keys (`settings/`), OpenRouter OAuth (`auth/`), debug bundles and the report form (`debug/`), updater (`update/`).

All `core/*` modules are pure Kotlin (JVM) and unit-tested; keep Android types out of them. The app
talks to them through small interfaces (`Device`, `MemoryStore`, …) so the logic stays testable
with fakes.

## Build and test
Requirements: JDK 17 and the Android SDK (compileSdk 36); the Gradle wrapper fetches Gradle itself.

```sh
./gradlew :core:llm:test :core:memory:test :core:agent:test :core:rules:test :core:web:test :core:mcp:test
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A local build is signed with your own debug key, so it cannot be installed over a release APK
(uninstall first). There are no instrumented or end-to-end tests; the behaviour on a real phone
is checked by hand before merging.

## CI and releases
`.github/workflows/build.yml` runs the unit tests and `:app:assembleDebug` on every push and pull request.
- Push to `main` → the `nightly` prerelease gets `beka.apk` + `beka.json`.
- Tag `v*` → a normal release (the in-app updater's stable channel).
- Pull requests are built with the default debug key and never published.

## Conventions
- User-facing strings are **Indonesian**; prompts to models and code/comments are **English**.
- Match the surrounding code: naming, comment density, Compose patterns. No new dependencies without a reason in the PR.
- Anything that acts on the user's phone goes through the agent's risk gate; don't add a path that bypasses confirmation, the lock-screen check or the app blocklist (banking, wallets, password managers).
- Log tags: `BekaOverlay`, `BekaChat`, `BekaRules`, `BekaVoice`, `BekaUpdate`, … Debug builds record Beka's whole log to files (`debug/AppLog`); the report form bundles it.
- Never commit API keys, keystores or personal data (screenshots, chat logs, report bundles).

## Pull requests
1. Open or pick an issue first for anything non-trivial.
2. Keep a PR to one change; add or update unit tests in `core/*` when you change logic there.
3. CI must be green. Describe what you tested on a device (or say you could not).
4. The maintainer tests on their phone before merging.
