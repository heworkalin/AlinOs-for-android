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
- **rootfs is reproducible**: `files.default.*.tar.gz.so` (4 ABIs) is compiled from source by
  `scripts/rootfs/`; see `scripts/rootfs/README.md`.

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

- **`files.default.*.tar.gz.so` (4 ABIs)** — packages the Termux `$PREFIX` (`files/default/`): a **Termux rootfs** containing `bash` / `openssh` / coreutils / `curl` / `tar` / `gzip` / `sed` / `grep` etc., all sourced from **[termux/termux-packages](https://github.com/termux/termux-packages)**. It is **self-compiled by this project** via `scripts/rootfs/` (only `bash` + `openssh` are built explicitly; the dependency graph pulls in `termux-tools` and its whole dependency set), then path-relocated (`com.termux`→`alin.android.alinos`, `files/usr`→`files/default`) and repacked with all hard links converted to symlinks. See `scripts/rootfs/README.md`.
- **`libproot.so` / `libproot-loader.so` (jniLibs, 4 ABIs)** — self-compiled static proot and loader, see “Prebuilt sources” below.
- **`libtar.so` (jniLibs, 4 ABIs)** — a **statically compiled GNU tar** (same upstream as the rootfs, but a
  **different build flow**: statically linked, zero-dependency): it exists simply as a zero-dependency extraction
  tool (the rootfs's `bin/tar` relies on the Termux dynamic-library environment) and is overlaid into `jniLibs`
  for the extraction flow to call directly. Unrelated to execution permissions.
  **TODO**: its static build flow is not yet codified (it required patching several package bodies); the
  existing artifact is used for now.
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

## Docs (`docs/`)

| File | Content |
|------|---------|
| `docs/MCP.md` | MCP positioning (server mode = developer debugging/dev, unrelated to the internal AI) and short-term plan |
| `docs/LOGGING.md` | Unified logging implementation record and short-term plan |
| `docs/PROOT_HARDLINK_FIX.md` | proot fake-hardlink ENOENT fix record |
| `docs/PROOT_LINK_SEMANTICS_REPORT.md` | proot link semantics fixes and residual limits |
| `docs/ANDROID_EXEC_RESTRICTION.md` | Android private-dir executable mechanism |

Convention: read this README first, then the active docs under `docs/`. Long historical reports were removed; see git log for history.

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

The four-ABI `files.default.*.tar.gz.so` (rootfs) originate from
**[termux/termux-packages](https://github.com/termux/termux-packages)**: this project **self-compiles** them
using the scripts under `scripts/rootfs/` (only `bash` + `openssh` are built explicitly; the dependency graph
pulls in `termux-tools` and its whole dependency set), then path-relocates them
(`com.termux`→`alin.android.alinos`, `files/usr`→`files/default`) and converts all hard links to symlinks
before packing.

### rootfs build (`scripts/rootfs/`)

| Script | Purpose |
|--------|---------|
| `build-rootfs.sh` | Orchestrator: patch properties.sh → Docker-build bash+openssh → pack → clean (4 ABIs) |
| `pack-rootfs.sh` | Extract deb → batch path replacement → hard-link→symlink → tar pack |
| `replace-paths.sh` | Batch replacement of hard-coded paths (standalone, supports `--dry-run`) |
| `README.md` | Environment requirements, step-by-step manual, self-check and troubleshooting |

Key points: upstream `build-package.sh` emits `.deb` by default; this project does **not** use its dpkg install
mechanics, instead extracting the deb contents onto the target path before packing. The archives contain
**only symlinks, no hard links**, and `tar` is run **without `-h`** (which would expand symlinks into real copies).

> Background: no `termux-packages`, no usable `bash` / `ssh` / `coreutils` / `proot` on Android.
> This project is merely a downstream consumer and packager.

**Inclusion principle**: whether an artifact is *downloaded* or *compiled from source*, if it ends up in the
repo/APK it counts as an included upstream resource — attribution and license obligations do not change with
the build method.

> TODO (not planned short-term): the static build flow of `libtar.so` (static GNU tar) is **not codified**.
> Same upstream as the rootfs, but it **cannot** be produced by `scripts/rootfs/` (that flow is dynamic).
> Static linking requires tar and its whole dependency chain (lzma / zstd / bzip2 / iconv, etc.) to be built
> statically, while those packages default to dynamic builds in termux-packages — several package bodies had to
> be patched at the time, so the change surface is large. The existing 4-ABI artifact (verified on device) is
> used for now.

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
  **The rootfs is self-compiled by this project** (`scripts/rootfs/`); attribution and license obligations do
  **not** change with the build method.
- **proot / proot-loader (self-compiled) — [termux/proot](https://github.com/termux/proot): GPLv2**; `loader`
  from the same source. `talloc`, statically linked in — LGPL-2.1+.
- `libtar.so` — GNU tar, GPLv3.
- Fake `/proc` data pack — [proot_proc](https://gitee.com/ak2/proot_proc), under that repository's terms.
- Model and pricing metadata (`assets/models.json`) — translated from [pi](https://pi.dev) provider data,
  under that upstream's terms.
- `com.termux.terminal` / `view` — [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator): Apache-2.0.
- Audio engines — [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (incl. [onnxruntime](https://github.com/microsoft/onnxruntime)),
  [alphacep/vosk-api](https://github.com/alphacep/vosk-api): Apache-2.0 / MIT (models per their own notices).
- Original `alin.android.alinos` code and Gradle deps (AndroidX / Material / okhttp / gson / media3 / lottie /
  markwon / JSch / Shizuku / HiddenApiBypass / commons-io etc.) — Apache-2.0 / BSD-style / MIT; details in
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
  (Note: `jna` and `prism4j` are declared but **currently commented out** — not active dependencies.)

Third-party components follow their original stated licenses.
