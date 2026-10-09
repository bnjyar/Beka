# Beka

Android overlay personal assistant. Architecture and roadmap: https://claude.ai/code/artifact/f458dec0-4db5-4a99-b011-c35d0ee1e027

## Layout
- `core/llm` — pure Kotlin provider layer (OpenAI-compatible + Anthropic-compatible adapters, SSE). Unit-tested in CI.
- `core/memory` — pure Kotlin long-term memory: `MemoryItem`/`MemoryStore`, word-overlap ranking (no vectors until on-device embeddings in phase 5), profile + per-request context, remember/recall/forget tools, end-of-session `Extractor`. Unit-tested in CI.
- `core/rules` — pure Kotlin proactive rules: `Rule` JSON model (triggers app_opened/app_closed/notification/schedule, conditions, levels notify/suggest/confirm/auto), `RuleEngine` (per-app permission cap, cooldown, budget, `llm_check` last), `RuleDrafter` (text → rule), prompts. Unit-tested in CI.
- `core/agent` — pure Kotlin agent loop (tools, risk gate, blocklist) over a `Device` interface, optional `Memory`. Unit-tested in CI with fakes.
- `core/web` — pure Kotlin web access: `WebSearch` (TinyFish default, Brave, Tavily, DuckDuckGo fallback), `PageFetcher` (TinyFish/direct+Jsoup/r.jina.ai, private-address guard), `WebTools` = the `web_search`/`fetch_page` `ToolKit`. Unit-tested in CI with MockWebServer.
- `core/mcp` — pure Kotlin MCP client (Streamable HTTP, optional static header auth, no OAuth): `McpClient` (initialize/session header/SSE or JSON replies, legacy `initialize` era with fallback to the 2026-07-28 per-request-metadata era), `McpToolKit` = remote tools as `mcp_<server>__<tool>` `ToolKit` (readOnlyHint → SAFE, else confirm unless the server is set to auto-run). Unit-tested in CI with MockWebServer.
- `app` — Android app: accessibility service + `AgentDevice` action executor (`accessibility/`), overlay bubble + panel (`overlay/`), chat session + extraction trigger (`chat/`), SQLite memory store + Memory screen (`memory/`), proactive rules controller + rules.db + Rules screen + alarm/notification receivers (`rules/`, log tag `BekaRules`), provider settings with Keystore-encrypted keys (`settings/`), OpenRouter OAuth (`auth/`), debug bundles (`debug/`).

## Dev loop
There is no local Android toolchain: Claude Code runs in proot on the phone itself.
1. Push to `main` → GitHub Actions runs `:core:llm:test :core:memory:test :core:agent:test :core:rules:test :core:web:test :core:mcp:test :app:assembleDebug` and publishes `beka.apk` to the `nightly` release.
2. `scripts/dev.sh wait` then `scripts/dev.sh install` (installs via Shizuku `rish`).
3. `scripts/dev.sh a11y | log | crash | shot | ui | report` for debugging on device.

Signing uses a fixed keystore (repo secrets `BEKA_KEYSTORE_B64` / `BEKA_KEYSTORE_PASSWORD`, local copy in `~/.beka/`) so builds install over each other.

User-facing strings are Indonesian; prompts to models are English.
