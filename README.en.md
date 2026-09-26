# AlinOs-for-Android

An Android client for cloud AI interfaces. Core architecture: **app shell ＋ a user-space Linux
execution layer on self-compiled *proot* (Ubuntu 24.04 rootfs, 4 ABIs)**.

> This environment is **not** claimed to be “lightweight / slim”. It runs on top of *proot* and is
> intentionally heavier; we picked proot to obtain a **near-complete, truly executable Linux user-space**
> (runnable by scripts / CLI / AI tools) without root, in third-party-app sandbox scope — not “small & cute”.

**中文（权威）**: [简体中文 README](README.md) — the Chinese version is authoritative.

---

## Nature & current status (please read first)

- **Personal evaluation / learning / testing**; code is largely **AI-assisted**, with the owner handling
  requirements, bug triage and on-device validation.
- **Not released: no compiled builds, no distribution, not published**; the repo only hosts source & evolution.
- Still **exploratory**: the execution layer has settled on the proot-container route (no more long-lived PTY
  sessions) and multi-protocol AI access is in place; audio / MCP shapes are not finalized.
- Interaction & architecture ideas **reference [pi.dev](https://pi.dev)-like philosophies** (no background
  long-running processes, observable one-shot commands, prompt/context compaction, multi-protocol provider
  abstraction…). If code is reused, its license applies — see LICENSE / THIRD_PARTY_NOTICES.
- If it ever moves toward a release, license compatibility would be reviewed and compliance handled first.

---

## Build requirements

- Gradle 8.14 · Gradle JDK 21
- [Android Studio](https://developer.android.google.cn/studio)
- proot / loader are **prebuilt artifacts committed to the repo**; see “Prebuilt sources” to rebuild.

## Links

- [GitHub](https://github.com/heworkalin/AlinOs-for-android) · [Gitee](https://gitee.com/hewrod/AlinOs-for-android)

## Current execution reality (honest)

- **Setup**: after cloning, run `bash scripts/fetch_deps.sh` to fetch the git-excluded engine
  (`sherpa-onnx-1.13.5.aar` → `app/libs/`).
- The **proot toolchain is now self-compiled and static** (4 ABIs); it no longer depends on the old proot
  inside `files.default.*` nor on `libtalloc.so.2`.

### Execution layer (current focus, working)

| Capability | Notes | Status |
|------------|-------|:------:|
| Container deploy page | 12 mirrors, resumable download, SHA256 pinning, fake-200 detection, extract | ✅ in use |
| Container extraction | `proot --link2symlink <libtar.so> -xJf`; hard links downgraded to symlinks automatically | ✅ verified |
| Container boot | Ubuntu 24.04.5 LTS / `uid=0(root)` / aarch64 via `--root-id` + `env -i` | ✅ verified |
| Fake `/proc` | bundled `proot_proc.tar.xz` + runtime host probing (enabled by default, LXC-friendly) | ✅ in use |
| AI tools | **bash / read / write / edit / ls / grep / find** (native names, no implementation leakage) | ✅ in use |
| Path sandbox | AI only sees container-internal paths; expand → symlink write-through → rootfs join; host paths unreachable | ✅ in use |
| Symlink semantics | reads/writes the **link target**, keeps the link intact, returns `link_warning` | ✅ in use |

### AI access (multi-protocol / multi-provider)

| Capability | Notes | Status |
|------------|-------|:------:|
| Protocol dialects | `openai-completions` / `openai-responses` / `anthropic-messages` (~82% of models) | ✅ in use |
| Model registry | **39 providers / 1312 models**, with pricing, context window, max output | ✅ in use |
| Dynamic model discovery | polls OpenAI-compatible / Anthropic / Google model endpoints, merged with the static baseline and persisted | ✅ in use |
| Cost calculation | same formula as pi: input/output/cache read-write + tiered pricing + Anthropic 1h cache ×2 | ✅ in use |
| Config editor | standalone Activity: basics + advanced (collapsed by default) + **dynamic custom params**, 128K context default | ✅ in use |
| Usage persistence | `usage_json` / `cost_total` / cache tokens stored in `chat_record`; token + cost shown under messages | ✅ in use |

### Other modules (legacy / paused)

| Module | Notes | Status |
|--------|-------|:------:|
| Streaming chat | SSE dialogue, multi-session + history | ✅ in use |
| Tool Calling | parse → run → feed back → recurse | ✅ in use |
| Think / logs | collapsible thought card / persisted call log | ✅ in use |
| Terminal / remote SSH | local shell (PTY) + JSch remote | 🟡 legacy (PTY tools no longer exposed to AI) |
| Audio (ASR/TTS/KWS/voiceprint) | sherpa-onnx / vosk offline engines | ⏸️ paused short-term |
| MCP server | HttpServer implementation | 🟡 may be dropped/kept internal, or replaced by an MCP client |

### Bundled environment

- **`files.default.*.tar.gz.so` (4 ABIs)** — packages the Termux `$PREFIX` (`files/default/`, i.e. Termux's `usr/`): a **Termux bootstrap rootfs** containing `bash` / coreutils / `curl` / `ssh` / `apt` / `dpkg` / `tar` / `proot` / `unzip`, sourced from the build artifacts and download sources of **[termux/termux-packages](https://github.com/termux/termux-packages)**; rebuild it by compiling the corresponding packages through that repo's (Docker) build flow and repacking.
- **`libproot.so` / `libproot-loader.so` (jniLibs, 4 ABIs)** — self-compiled static proot and loader, see “Prebuilt sources” below.
- **`libtar.so` (jniLibs, 4 ABIs)** — same origin as the rootfs (GNU tar from the Termux ecosystem), statically linked and overlaid into `jniLibs` so proot can `execve` it directly from `nativeLibraryDir` to extract containers (the `bin/tar` inside the rootfs is dynamically linked and lives in a private directory where Android refuses execution).
- `app/src/main/assets/proot_proc.tar.xz` — fake `/proc` data pack (from the `proot_proc` project).
- `app/src/main/assets/models.json` — model & pricing registry (translated from pi's provider data).

---

## Future direction (candidates — not a promise)

- **Execution layer wrap-up**: error recovery, timeout policy, output truncation, read-only mode.
- **AI access expansion**: remaining dialects (Google Generative AI / Vertex, Bedrock, Mistral) and providers.
- **Cost & stats**: per-session / global cumulative cost; show cost for historical messages.
- **Config**: model name search, provider endpoint templates, config import/export.
- **MCP**: prefer a **client** form, or just use `mcp-cli` to connect remote servers.
- **Audio (paused)**: no deep updates short-term; evaluate later if resumed.

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

---

## Which AI services are wired up now

The client bundles metadata and pricing for **39 providers / 1312 models**. Directly selectable mainstream
ones include: OpenAI, Anthropic, DeepSeek, Google Gemini, xAI Grok, Qwen, Moonshot Kimi, Z.ai GLM, MiniMax,
Mistral, Groq, Cerebras, Together, Fireworks, OpenRouter, Vercel AI Gateway, Cloudflare Workers AI,
Hugging Face, NVIDIA, Baseten, Amazon Bedrock, Azure OpenAI, GitHub Copilot, Cline/OpenCode, and more. It
also works with **any OpenAI-style self-hosted / local endpoint** (Ollama, llama.cpp, one-api / new-api
gateways, …). Three protocol dialects are implemented (Chat Completions, Responses, Anthropic Messages); the
data for the remaining dialects (Google / Bedrock / Mistral, …) is already in place and awaiting wiring.

---

## Credits

### Open-source projects

Development referenced / drew on / directly uses many open-source projects: **[Termux](https://github.com/termux/termux-app)**
(Android terminal emulation and `termux-shared` ideas), **[termux/termux-packages](https://github.com/termux/termux-packages)**
(upstream source of the Android user-space toolchain and patches), **[termux/proot](https://github.com/termux/proot)**
(proot and loader sources), **[Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)**
(terminal view/emulation), **[TMOE](https://github.com/2moe/tmoe)** (key reference for proot container
install/startup flows), **[Android-Proot-Builder](https://github.com/wuxianggujun/Android-Proot-Builder)**
(proot cross-compilation flow reference; this project extended it with 32-bit targets), **[proot_proc](https://gitee.com/ak2/proot_proc)**
(fake `/proc` data pack), **[talloc](https://talloc.samba.org/)** (proot's memory library, now statically
linked), **[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** and
**[onnxruntime](https://github.com/microsoft/onnxruntime)** (offline speech), **[alphacep/vosk-api](https://github.com/alphacep/vosk-api)**
(speech recognition), **[pi.dev](https://pi.dev)** (reference for agent tool design and multi-protocol provider
abstraction), plus Gradle dependencies such as AndroidX / OkHttp / Gson / JSch / Media3 / Lottie / Markwon.
**Licenses and copyright belong to their respective owners**; the upstream LICENSE of each governs.

### Prebuilt sources

The four-ABI `libproot.so` and `libproot-loader.so` under `app/src/main/jniLibs/` were cross-compiled with
**Android NDK r28c** (`aarch64-linux-android28` / `armv7a-linux-androideabi28` / `i686-linux-android28` /
`x86_64-linux-android28` toolchains) from the **[termux/proot](https://github.com/termux/proot)** sources, with
`talloc` **statically linked** so the result only needs `libc.so` and `libdl.so`. The build flow references
**[Android-Proot-Builder](https://github.com/wuxianggujun/Android-Proot-Builder)** and extends it with 32-bit
targets.

The four-ABI `libtar.so` and `files.default.*.tar.gz.so` (rootfs) share the
**[termux/termux-packages](https://github.com/termux/termux-packages)** ecosystem: the former is a static GNU tar
overlaid into `jniLibs` for proot to execute directly; the latter is the Termux bootstrap user-space, which can
be rebuilt through that repo's build flow and repacked.

### AI tools

Thanks to the **AI tools (mostly web) used** throughout development, debugging, research and code collaboration:
**pi** (https://pi.dev — recent direction, large-scale code collaboration and repo cleanup), **DeepSeek**
(https://chat.deepseek.com — early code-gen, API integration and cloud-API debugging), **Kimi**
(https://www.kimi.com — early code stitching and long-text reading), **Claude** (https://claude.ai — tricky-logic
debugging and code review), **Tongyi Qianwen** (https://www.tongyi.com — research and summaries), **Doubao**
(https://www.doubao.com — early code-gen and research), plus other models that helped with triage, translation
and documentation cleanup. Acknowledgement of the dev journey only — no endorsement implied.

> Honest record: this project spanned a long time with lots of exploration; early attempts kept chasing “slim”
> while often getting heavier. It is now documented truthfully above.

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
- **Local runtime toolchain (bash / ssh / coreutils, etc.) — [termux/termux-packages](https://github.com/termux/termux-packages)**:
  the upstream source of build scripts and Android porting patches; each package under its own declared license.
- **proot / proot-loader (self-compiled) — [termux/proot](https://github.com/termux/proot): GPLv2**; `loader`
  from the same source. `talloc`, statically linked in — LGPL-2.1+.
- `libtar.so` — GNU tar, GPLv3.
- Fake `/proc` data pack — [proot_proc](https://gitee.com/ak2/proot_proc), under that repository's terms.
- Model and pricing metadata (`assets/models.json`) — translated from [pi](https://pi.dev) provider data,
  under that upstream's terms.
- `com.termux.terminal` / `view` — [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator): Apache-2.0.
- Audio engines — [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (incl. [onnxruntime](https://github.com/microsoft/onnxruntime)),
  [alphacep/vosk-api](https://github.com/alphacep/vosk-api): Apache-2.0 / MIT (models per their own notices).
- Original `alin.android.alinos` code and Gradle deps (AndroidX / JNA / okhttp / gson / media3 / lottie /
  markwon-prism etc.) — Apache-2.0 / MIT / LGPL; details in NOTICE.

Third-party components follow their original stated licenses.
