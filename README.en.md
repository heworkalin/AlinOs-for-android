# AlinOs-for-Android

> **EN overview.** The authoritative text is Chinese — see [`README.md`](README.md)（中文主档）.\
> This is an abbreviated English mirror and links back to it.

A personal, experimental Android "AI-shell": an **app shell + on-device Linux user-space via PROOT**.
A `files.default` rootfs (4 ABIs) ships **with `proot`** so scripts / CLIs run inside `proot` as a usable,
non-root Linux user-space.

> **Reality-check (not "slim")**: we do **not** claim it is small/lightweight. It started as a wish to
> simplify, but tooling kept piling up and we kept re-inventing wheels. This is an honest reassessment:
> execution now prefers **PROOT-backed, stateless single-shot CLI/bash**, not a trimmed toy or a complex
> PTY-session system.

- **Personal evaluation only** — largely AI-assisted, NOT released / distributed / published as an app.
- Direction still exploratory & on hold ("maybe later").

## Current direction (reassessed)
- Drop the hard-to-maintain PTY-session model → run **stateless single-shot CLI/bash inside proot**
  (read / write / execute primitives); trim heavy multi-layer tooling to what our AI/tools actually use.
- **Audio** (sherpa-onnx / vosk ASR-TTS-KWS-voiceprint) is **paused**, not a near-term roadmap item.
- MCP is likely to be consumed as a **client (e.g. via `mcp-cli`)**, not a self-hosted server.
- Design cues (prompt assembly, context compaction, observable one-shot commands) **reference
  pi.dev**-like philosophies — if code is reused, their licenses apply.

## Build requirements
- Android Studio (latest), Gradle JDK 21, Gradle 8.14

## Big dependencies — download once (`bash scripts/fetch_deps.sh`)
- `sherpa-onnx-1.13.5.aar` into `app/libs/` (git-ignored; fetched, not stored).
- The 4 ABI `files.default.*` env tarballs (with embedded proot tools) ship **in git** (no external
  rebuild source exists).

## Docs layout
- [`README.md`](README.md) = single active spec (中文主档).
- Older long reports archived (kept) under `docs/_archive_*`.

## License / credits
See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Termux-derived parts are
GPLv3; engine libs Apache-2.0/MIT; emulator Apache-2.0; proot GPLv2; details there.

> Also referenced / studied: Termux, Android-Terminal-Emulator, TMOE, k2-fsa/sherpa-onnx etc. If any code
> is reused, obey their own licenses.
