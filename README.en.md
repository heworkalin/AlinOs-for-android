# AlinOs-for-Android

> **EN overview.** Primary docs are Chinese (`README.md`); this file is a brief English mirror.

A personal, experimental Android "AI-shell": an **app shell + lightweight on-device CLI layer**,
wrapping cloud Open APIs (OpenAI-compatible) and executing local commands/audio.

> **Personal evaluation only** — code is largely AI-assisted, NOT released / distributed / published
> as an app. The project direction is still being explored and periodically simplified.

## Current focus (lean)
- Keep it **small & reviewable**. We found complex long-lived PTY session execution too costly to
  maintain → moving execution to **stateless single-shot CLI/bash** (read/modify/execute primitives).
- **Audio** (sherpa-onnx / vosk ASR-TTS-KWS-voiceprint) is **paused**; not a near-term roadmap item.
- MCP is likely to be consumed as a **client (e.g. via `mcp-cli`)**, not a self-hosted server.
- Design cues (prompt assembly, context compaction, observable one-shot commands) **reference
  pi.dev**-like philosophies — if their code is reused, their licenses apply.

## Build requirements
- Android Studio (latest), Gradle JDK 21, Gradle 8.14

## Big dependencies — download once (`bash scripts/fetch_deps.sh`)
- `sherpa-onnx-1.13.5.aar` into `app/libs/` (engine libs are git-ignored; they're fetched, not stored).
- The 4 ABI prebuilt `files.default.*` env tarballs (with embedded proot tools) ship **in git** because
  there is no external rebuild source.

## Docs layout
- `README.md` = the single active spec.
- Older long reports are archived (kept) under `docs/_archive_*`.

## License / credits
See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Termux-derived parts are
GPLv3; engine libs Apache-2.0/MIT; emulator Apache-2.0; proot GPLv2; see details there.

> Also referenced / studied: Termux, Android-Terminal-Emulator, TMOE, k2-fsa/sherpa-onnx etc. If code is reused, obey their own licenses.
