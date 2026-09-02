# AlinOs-for-Android

An Android client for cloud AI interfaces. Core architecture: **app shell ＋ an on-device Linux
user-space execution layer built on *proot* (`files.default` rootfs, 4 ABIs)**.

> This environment is **not** claimed to be “lightweight / slim”. It runs on top of *proot* and is
> intentionally heavier; we picked proot to obtain a **near-complete, truly executable Linux user-space**
> (runnable by scripts / CLI / AI tools) without root, in third-party-app sandbox scope — not “small & cute”.
> Execution used to center on a trimmed Termux helper layer; the emphasis has moved onto a proot root.

**中文（权威）**: [简体中文 README](README.md) — this Chinese version is authoritative.

---

## Nature & current status (please read first)

- **Personal evaluation / learning / testing**; code is largely **AI-assisted**, with the owner handling
  requirements, bug triage and on-device validation.
- **Not released: no compiled builds, no distribution, not published**; the repo only hosts source & evolution.
- Project is **still exploratory and may go dormant for a while**: execution layer / proot / audio / MCP /
  Agent shapes are not finalized. The owner is busy elsewhere short-term and will resume only after clearer
  direction.
- Interaction & architecture ideas **reference [pi.dev](https://pi.dev)-like philosophies** (no background
  long-running processes, observable one-shot commands, prompt/context-compaction ideas…). If code is reused,
  its license applies — see LICENSE / THIRD_PARTY_NOTICES.
- If it ever moves toward a release, license compatibility would be reviewed and compliance handled first.

---

## Build requirements

- Gradle 8.14 · Gradle JDK 21
- [Android Studio](https://developer.android.google.cn/studio)

## Links

- [GitHub](https://github.com/heworkalin/AlinOs-for-android) · [Gitee](https://gitee.com/hewrod/AlinOs-for-android)

## Current execution reality (honest)

- **Setup**: after cloning, run `bash scripts/fetch_deps.sh` to fetch the git-excluded engine
  (`sherpa-onnx-1.13.5.aar` → `app/libs/`). The 4-ABI `files.default.*` tarballs (with proot/unzip/
  libtalloc/libbz2 inside) ship **in the repo**.

### Implemented modules as they truly stand

| Module | Notes | Status |
|--------|-------|:------:|
| Streaming chat | SSE dialogue, multi-session + history | ✅ in use |
| Tool Calling | parse→run→feed-back→recurse | ✅ in use (tools to be trimmed with exec layer) |
| Agent tools | several registered incl. `search_tools` meta | ✅ in use; will be consolidated |
| Think / logs | collapsible thought card / persisted call log | ✅ in use |
| Config mgmt | multiple AI services (OpenAI / DeepSeek) | ✅ in use |
| Terminal / remote SSH | local shell (PTY) + JSch remote | 🟡 **legacy** — PTY sessions costly → moving to proot single-shot CLI |
| Audio (ASR/TTS/KWS/voiceprint) | sherpa-onnx / vosk offline engines | ⏸️ **paused short-term** |
| MCP server | earlier experimental HttpServer impl | 🟡 may be dropped/kept internal — prefer MCP **client** (mcp-cli) |

> Note: the table is the real “working/previously built” inventory. No new features short-term; if this is
> resumed, the focus is **trimming tools + execution layer down to proot single-shot CLI primitives**
> (read / write / execute).

### Bundled environment

`app/src/{arm,arm64,x86_64,i686}/assets/files.default.*.tar.gz.so` — four-ABI, non-official trimmed rootfs
(openssh/bash/coreutils…) with embedded proot/unzip among others, for containerized execution; no external
rebuild source ⇒ kept inside the repo.

---

## Future direction (candidate after consolidation — not a promise)

- **Execution**: PTY sessions → **stateless single-shot CLI/bash inside proot** (observable), tools reduced to
  read / write / execute primitives.
- **MCP**: prefer a **client** form, or just use `mcp-cli` to connect remote servers, rather than a self-maintained server.
- **Agent**: prompt assembly / relative paths / context compaction, referencing pi.dev ideas lightly; keep the
  “AI-facing” abilities separate from “tool-internal” ones.
- **Multi-API routing (optional)**: OpenAI Responses / Vertex / Anthropic etc., revisit as needed.
- **Audio (paused)**: no deep updates short-term; eval later if resumed (VAD/KWS floating listener, SDK/AAR…).

## Docs archive (`docs/`)

To avoid losing which doc to read, the long historical reports were moved (kept, index-only) under `docs/`:

| File | Content |
|------|---------|
| `docs/_archive_PROJECT_STATUS.md` | phase progress / status |
| `docs/_archive_AGENT_CAPABILITY_ROADMAP.md` | Agent capability planning |
| `docs/_archive_PROOT_REFACTOR_PLAN.md` | proot single-shot refactor (was planned) |
| `docs/_archive_TMOE_PROOT_ANALYSIS.md` | proot startup deep-dive |
| `docs/_archive_SYSTEM_REPORT.md` | early system report |
| `docs/_archive_mcp.md` | MCP spec excerpt |

Convention: read this README first; dig into `docs/_archive_*` only if needed.

## Credits

Development referenced / drew on [Termux](https://github.com/termux/termux-app),
[Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator),
[TMOE](https://github.com/2moe/tmoe) and k2-fsa/sherpa etc.; anything possibly incorporated is described here,
but **the upstream license of each governs** — verify each LICENSE before code reuse.

Also thanks to the **AI tools (mostly web) used** throughout development & debugging:

| Tool | Web site | Used for |
|---|---|---|
| **pi** | https://pi.dev | recent direction & code collaboration, large-file/repo cleanup |
| **DeepSeek** | https://chat.deepseek.com | early code-gen, API integration & cloud-API debugging |
| **Kimi** | https://www.kimi.com | early code stitching, long-text reading |
| **Claude** | https://claude.ai | debugging tricky logic, code review |
| **Tongyi Qianwen** | https://www.tongyi.com | documentation research / summaries |
| **Doubao** | https://www.doubao.com | early code-gen & research |

> Web entrypoints listed. Acknowledgement of the dev journey only — no endorsement implied.

> Honest record: this project spanned a long time with lots of exploration but kept re-inventing wheels while
> chasing “slim” — often getting heavier. It is now documented truthfully above; whether it continues is the
> owner’s call.

---

## Copyright

Copyright © 2026 heworkalin. All rights reserved.

Personal learning/testing (evaluation) use only — **no released builds, no distribution, not published**;
license compatibility would be reviewed before any release.

This project embeds/references third-party open-source components owned by their respective holders;
**using it implies accepting those upstream licenses**. Full list: [`LICENSE`](LICENSE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Summary:

- `com.termux.*` / `termux-shared` / native env — [Termux (termux-app)](https://github.com/termux/termux-app):
  mainly GPLv3, some MIT / Apache-2.0 / GPLv2+Classpath.
- `com.termux.terminal` / `view` — [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator): Apache-2.0.
- Audio engines — [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (incl. [onnxruntime](https://github.com/microsoft/onnxruntime)),
  [alphacep/vosk-api](https://github.com/alphacep/vosk-api): Apache-2.0 / MIT (models per their own notices).
- Compiled proot / unzip / libtalloc / libbz2 (inside `files.default.*`) — proot upstream GPLv2, unzip (Info-ZIP)
  BSD-style, libtalloc LGPL-2.1+, libbz2 BSD-like.
- Original `alin.android.alinos` code and Gradle deps (AndroidX / JNA / okhttp / gson / media3 / lottie /
  markwon-prism etc.) — Apache-2.0 / MIT / LGPL; details in NOTICE.

Third-party components follow their original stated licenses.
