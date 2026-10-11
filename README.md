# Beka

Beka is a personal assistant that lives in a floating bubble on your Android phone. It reads what is
on the screen, answers questions about it, and can act in your apps for you — with your approval.

> The app is in English and Indonesian: it follows the phone's language, or on Android 13+ Beka's own language setting.
> Beka itself answers in whatever language you write to it.

## What it does
- **Reads any app** through Android's accessibility service: ask about what is on screen without copying it.
- **Acts in apps**: open apps, tap, type, scroll, send a message, play music. Risky steps (sending, paying, deleting, calling) ask first; banking, wallet and password apps are never operated.
- **Works in the background** on a virtual display, even while the phone is locked (needs Wireless debugging or Shizuku once).
- **Remembers** preferences and facts you tell it, on the device; you can see and delete every item.
- **Proactive rules**: "when I open X, remind me Y", "every morning at 7…", "when a WhatsApp message from … arrives" — at the level you choose (note, suggestion, ask, or act).
- **Voice**: push-to-talk in the panel, replies read aloud, and it can be your phone's default assistant (long-press power).
- **Any model**: OpenAI-compatible or Anthropic-compatible APIs — OpenRouter (with login), OpenAI, Anthropic, or a local server. Keys stay encrypted on the phone.
- **Web search and MCP tools**: search the web and fetch pages; connect remote MCP servers.

## Install
A step-by-step guide for people new to Beka: [docs/INSTALL.md](docs/INSTALL.md) (in Indonesian: [docs/PASANG.md](docs/PASANG.md)).

1. Download `beka.apk` from [Releases](../../releases) — the latest release is stable, `nightly` is built from every change on `main`.
2. Open it and allow installing from your browser/file manager.
3. Open Beka and follow the setup: overlay permission, accessibility service, notifications, microphone (optional).
   On Android 13+ the accessibility switch may be greyed out for apps installed outside a store: App info → ⋮ → **Allow restricted settings**, then try again.
4. Add a model provider in Settings → AI model.

Later versions can be installed from inside the app (Settings → Updates).

Requires Android 11 (API 30) or newer.

## Privacy
Beka has no server. Screen contents, chats and memories go only to the model provider you configure,
and are stored only on your phone. Bug reports are saved locally; nothing is uploaded unless you attach
the file to an issue yourself.

## Reporting bugs
In the panel: ⋮ → **Report**. Describe what happened, choose what to include, and Beka saves a zip and
opens a prefilled GitHub issue. Check the zip before attaching it — issues are public.

## Contributing
Build instructions, project layout and conventions are in [AGENTS.md](AGENTS.md). Issues and pull requests are welcome.

## License
[GPL-3.0](LICENSE)
