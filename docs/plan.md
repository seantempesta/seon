# Seon Rebuild Plan & Active Roadmap

This document tracks what is built, what is next, and every agreed architectural plan so nothing gets lost between sessions.

## Phase 1 — Lean Core, Health, `result/e<n>`, and 3-Level UI (Completed / Shipping on `jolt/rebuild`)

- [x] Static 8-proc Flow graph + 1-thread COW `ForkParent` (`GC ceiling = 5` / child `4`) + persistent multi-turn agent workers with LRU eviction.
- [x] 100% Malli-derived Datahike schemas (`:db.type/data`, `:db/contentId`, multi-segment naming, Malli write-seam rail, `seed = 42` generative checks).
- [x] Lossless `.clj` / `.md` indexing (`:seon.code.*`), namespace hierarchy refs, and mid-session `:main` -> warm branch code delta sync.
- [x] Pure derived Health engine (`:blocking-in-flow-proc`, `:state-outside-flow`, `:used-before-defined`, `:unschemed-attribute`, `:retyped-attribute`, `:schema-breaks-stored-data`, global `:failing-test`, `:proc-fault`) + fail-fast reach tests + anti-test-deletion gate.
- [x] Native MCP server inside `:web` (`POST /mcp` + zero-logic `bin/mcp`: `seon_repl`, `seon_health`, `seon_test`, `seon_merge`, `seon_status`).
- [x] Real runtime `result/e<n>` handles + `user/pin!` / `user/unpin!` + auto-discovered Level 2 eval tiles.
- [x] Real 3-level progressive-disclosure UI (Present-Moment `:canvas` for all agents, 2-panel `:workspace`, wire/ctx/side-by-side `:debug`), live in-place streaming with sticky auto-scroll, pulsing agent status placeholders, realtime branch-isolated custom `:seon.agent/render-fn` & `(user/present!)` stage visuals, colocated `{:mode :agent}` affordances on `@seon.ctx/ctx` & tile renderers, and live streaming DeepSeek multi-turn tool loops.
- [x] Rebuilt docs (`docs/README.md`, `architecture.md`, `agent.md`, `render.md`, `health.md`, `plan.md`) synchronized with the live codebase.

## Phase 2 — Multiplatform Embedded Browser + Standalone Desktop Window (Next)

**Goal:** Give users BOTH a standalone native Seon app window (macOS, Windows, Linux) AND remote browser access (`http://...`), while letting agents assist users across **any modern JS website** with real logins, persistent cookies/sessions, and low-latency native perception.

1. **Primary Choice — `tauri-apps/wry` (MIT/Apache-2.0 Rust `cdylib` -> thin `extern "C"` shim -> `jolt.ffi`, ~2–4 MB):**
   - **Native OS WebViews across all 3 platforms:** `WKWebView` (macOS), `WebView2` (Windows), `WebKitGTK` (Linux) — 100% real modern JS, WebGL, WebAuthn/Passkeys, and OAuth logins without shipping a 150 MB Chromium bundle.
   - **Real backend cookie & session persistence (key advantage over bare `webview/webview`):** WRY natively exposes `WKHTTPCookieStore` (macOS), `ICoreWebView2CookieManager` (Windows), and WebKitGTK's cookie manager + persistent profile data directories so users log in once and agents collaborate inside authenticated sites.
   - **Init-script injection & native IPC (`with_initialization_script` + `ipc_handler`):** Inject a compact Seon semantic-tree & action observer (`data-seon-id`, bounding rects, roles, states) on every page load and bridge events back to Clojure over native IPC (bypassing Linux WebKitGTK AT-SPI gaps cleanly).
   - **Native GPU snapshots (~15–30 ms, replacing headless Chrome subprocesses!):** Uses the raw platform handle exposed by WRY (`WKWebView.takeSnapshotWithConfiguration:` on macOS, `ICoreWebView2::CapturePreviewAsync` on Windows, snapshot surface on Linux) for ephemeral tail screenshots.
   - **Process isolation & hot-swap survival:** Run the thin native window shell on OS thread 0 talking to the standing Seon daemon over local socket/HTTP so Seon `--take-over` binary swaps never close the user's window or drop logged-in tabs. Focus on macOS (`WKWebView`) first while keeping the C ABI portable across Windows and Linux.
2. **Complementary Specialized Engines (Right Tool per Job):**
   - **Lightpanda (Zig + V8 headless, ~9x faster / ~16x leaner than Chrome):** For high-speed background/headless web research & multi-agent tasks over external JS sites where no visual window is needed (supports CDP + persistent cookies).
   - **Optional Headless `Blitz` (`DioxusLabs/blitz`, MIT/Apache-2.0):** Strictly for `< 5 ms` headless layout/overflow/collapse/overlap health audits (`node.final_layout()` + `AccessKit`) on Seon's *own* JS-free Hiccup UIs in CI/tests without a display server.
   - **Watchlist — `Servo` (`crates.io` 0.4–0.8, SpiderMonkey JS + emerging C ABI):** Worth revisiting once 1.0 web-compat + cookie/auth parity matures, as a single uniform cross-platform engine.

## Phase 3 — Distilled v1 Feature Ideas & Full Autonomy (No Old Implementation Baggage)

Good feature ideas preserved from `main`'s `docs/parity.md` & tasks (re-designed cleanly on the new core):

1. **Live Multi-Agent Rounds & Turns-to-Merged Metric:** Run real DeepSeek / vision model rounds (Root delegating to scoped child agents on parallel branches -> proving -> auto-merging -> tracking turns-to-merged-code & token/cache efficiency).
2. **Branch-Local Schema Tile Delegation:** Extend branch-isolated worker render evaluation (already live for `:seon.agent/render-fn` and edited `seon.render.ui/root-canvas`) to individual unmerged custom `:seon.render/hiccup` entity tile renderers too.
3. **Hybrid Semantic Vector + Call-Graph Search:** Wire `native/embed.c` (`libseon-embed.dylib`) into a dedicated post-`ForkParent` Flow worker for `(user/search ...)` & `(user/similar ...)`.
4. **Agent REPL Ergonomics & History:** Add `(user/history sym)` (reading Datahike's history index), `(user/callers sym)` / `(user/callees sym)` / `(user/source sym)`, `(user/diff)` / `(user/edit! ...)`, and safe `ns-unmap` referrer checks so all sessions operate 100% inside `seon_repl`.
5. **Merge & Coverage Quality Gates:** Add optional "changed function has reaching test" coverage warning, stale-doc citation check (`:stale-doc` when a doc cites a modified var), and Datahike store maintenance/GC + zstd out-of-line blobs for oversized values.
6. **External Effect Boundary & Security:** Same-origin check on `POST /call`, receipt-logged external effects (`my.fx/*`), and retiring temporary outside workarounds (`.claude/settings.json` hook and `bin/worktree`) once `jolt/rebuild` replaces `main`.
