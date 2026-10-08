# Beka

Android overlay personal assistant. Architecture and roadmap: https://claude.ai/code/artifact/f458dec0-4db5-4a99-b011-c35d0ee1e027

## Layout
- `core/llm` — pure Kotlin provider layer (OpenAI-compatible + Anthropic-compatible adapters, SSE). Unit-tested in CI.
- `core/agent` — pure Kotlin agent loop (tools, risk gate, blocklist) over a `Device` interface. Unit-tested in CI with fakes.
- `app` — Android app: accessibility service + `AgentDevice` action executor (`accessibility/`), overlay bubble + panel (`overlay/`), chat session (`chat/`), provider settings with Keystore-encrypted keys (`settings/`), OpenRouter OAuth (`auth/`), debug bundles (`debug/`).

## Dev loop
There is no local Android toolchain: Claude Code runs in proot on the phone itself.
1. Push to `main` → GitHub Actions runs `:core:llm:test :core:agent:test :app:assembleDebug` and publishes `beka.apk` to the `nightly` release.
2. `scripts/dev.sh wait` then `scripts/dev.sh install` (installs via Shizuku `rish`).
3. `scripts/dev.sh a11y | log | crash | shot | ui | report` for debugging on device.

Signing uses a fixed keystore (repo secrets `BEKA_KEYSTORE_B64` / `BEKA_KEYSTORE_PASSWORD`, local copy in `~/.beka/`) so builds install over each other.

User-facing strings are Indonesian; prompts to models are English.
