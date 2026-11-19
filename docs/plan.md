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

## Phase 2 — Multiplatform Embedded Browser + Standalone Desktop Window (In Progress — macOS WKWebView Shipped!)

**Goal:** Give users BOTH a standalone native Seon app window (macOS, Windows, Linux) AND remote browser access (`http://...`), while letting agents see the real pixels of what the user sees on every turn (always replaced at the end of context, never stored in history) and assist across modern JS websites.

- [x] **macOS Native `WKWebView` Host (`native/browser/seon_browser.m` -> `target/seon-browser`, 78 KB) + `:browser` Flow Proc (`src/seon/browser.clj`):**
  - `--headless`: silent off-screen GPU-backed `WKWebView` (0 Dock icon, 0 focus steal, 0 Keychain popups) capturing real JPEG screenshots (~19–50 ms, ~40–70 KB) + live DOM layout telemetry (`overflowX`, `svgBoxes`, `theme`, `viewing`, `mode`).
  - `--window` (`./bin/seon browser`): standalone native macOS desktop window with persistent `WKWebsiteDataStore` cookies/logins that survives `--take-over` binary swaps cleanly.
  - Ephemeral end-of-context image replacement (`model/build-agent-wire-messages` -> multimodal `deepseek-flash` `["text", "image"]` + live `<img>` preview inside Debug View) so agents inspect their own UIs and fix clipping/contrast/artifacts in real time without bloating KV cache or history.
- [ ] **Cross-Platform Parity (Windows `WebView2` / Linux `WebKitGTK` via `tauri-apps/wry`):** Plug into the exact same newline-delimited JSON control protocol spoken by `:browser`.

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

## Phase 3 — Hierarchical Multi-Agent Context, Datahike History & Self-Refinement (Shipped on `jolt/rebuild`)

- [x] **Flow-Native Async & Awaitable Agent Calls (`:seon.agent.call/*`, `:seon.agent/awaiting`):** `user/ask!`, `user/await!`, `user/call!`, `user/answer!`, `user/fail!`, `user/cancel!`, `user/calls` with lazy `:ns/<ns>` specialist spawning, dispatch-time cycle rejection, Malli `:result-schema` checks, cascading `:parent-call` cancellation, and zero-timer auto-settlement (`:callee-paused`, `:callee-stalled`, `:schema-mismatch`, `:merged`).
- [x] **Level-Zoomed Namespace Context & Code ↔ Schema ↔ Doc ↔ Test Discovery:**
  - Indexed `:seon.code.var/keywords` + `(requiring-resolve 'ns/var)` reach tracking in `seon.code`.
  - Root `:coordinator` gets a subsystem routing table; specialists get Public API + inline `:malli/schema` + real covering tests (`code/reaching-tests`) + linked Markdown docs (`code/ns-linked-docs`) + Working Schemas (with `:seon.schema/doc`) + deterministic Context Hygiene metrics + ephemeral tail `<neighborhood-activity>` (keeping static `<walk-context>` KV-cache hit rates at 83–95%).
- [x] **True Datahike Retraction Compaction & Time-Travel (Zero Archive Watermarks):**
  - `(user/compact!)` actually retracts old `:seon.agent.eval/*` entities via `[:db/retractEntity eid]` while Datahike's native `d/history` retains every retracted eval, `:seen-messages` (preventing mail replay), monotonic turn/`result/e<n>` counters, Workspace `"Earlier history (d/history)"` fold, and Canvas `◀ / ▶` time-travel (`d/as-of`).
  - Agents time-travel & self-refine directly inside the REPL via `(user/past-evals)`, `(user/as-of tx)`, `(user/history sym)`, `(user/callers sym)`, `(user/callees sym)`, `(user/tests-for 'ns)`, `(user/doc! ...)`, `(user/register-schema! ...)`, `(user/remember!)`, and `(user/tune-context!)`.
- [x] **Autonomous End-to-End Branch Merge & Git Write-Back Proven Live:** Specialist agents edit code/tests/docs/schemas inside their branch REPL -> reach-test -> 3-way merge -> hot-reload -> lossless disk write-back & 2-parent Git commit (`seon.git/write-back!`).

## Phase 4 — Next High-Leverage & Nice-to-Have Roadmap

Prioritized by turns removed from "roughly specified task -> merged, verified code":

1. **B-Tree Namespace Splitting of Oversized Monoliths (`> 6k` tok target: `2k–6k` tok, branching factor `b = 4–8`):**
   - Split `seon.render.ui` (~27.5k tok) into `seon.render.ui.{theme,canvas,workspace,debug,tiles,shell}` (parent facade re-exporting public entrypoints).
   - Split `seon.code` (~12.4k tok) into `seon.code.{reader,graph,eval,persist}`, `seon.fork` (~11.4k tok) into `seon.fork.{parent,worker,repl}`, `seon.transactor` (~11.2k tok) into `seon.transactor.{calls,merge,io}`, and `seon.web` (~10.2k tok) into `seon.web.{routes,sse,mcp}`.
   - Group remaining flat `seon.*` namespaces into clean 2-level subsystem trees so `:agent/root` routes across ~6–8 coherent domain subtrees instead of 23 flat namespaces.
2. **Automated Context-Quality Health Advisories (`seon.health` warnings, never blocking):**
   - `:stale-doc` warning when a doc section mentions a var whose `:seon.code.form/hash` changed after the doc section's `tx` (surfaces directly in `ns-tile` / `seon_health` so agents keep docs truthful!).
   - `:uncovered-public-var` / `:undocumented-public-var` / `:oversized-namespace` soft advisories feeding autonomous "hygiene sweep" rounds where specialist agents add `:malli/schema` contracts, docstrings, doc updates, and reaching `deftest`s.
   - Measure **turns-to-merged** across randomized multi-agent rounds in Datahike (`:seon.eval/round`) so context improvements are evaluated strictly by turns removed.
3. **Structured Patch / Rename Ergonomics (`user/rename!`, `user/diff`):**
   - Branch-local `(user/diff)` showing exact changed forms/schemas/docs vs `:main` before calling `(user/ready!)`.
   - Safe var deletion / `ns-unmap` referrer check (refuse deleting a var that still has `:seon.code.var/refs` callers unless updated together).
4. **Nice-to-Have Platform & Scaling Items:**
   - **Branch-Local Schema Tile Delegation:** Extend branch-isolated worker render evaluation (already live for `:seon.agent/render-fn` and edited `root-canvas`) to individual unmerged custom `:seon.render/hiccup` entity tile renderers.
   - **Hybrid Semantic Vector + Call-Graph Search:** Wire `native/embed.c` (`libseon-embed.dylib`) into a dedicated post-`ForkParent` Flow worker for `(user/search ...)` & `(user/similar ...)` once the codebase grows beyond exact-match/call-graph reach.
   - **Cross-Platform Embedded Browser Parity:** Port macOS `WKWebView` control protocol to Windows (`WebView2`) / Linux (`WebKitGTK`) via `tauri-apps/wry` (plus optional Lightpanda headless browsing for external JS sites).
   - **Datahike Store Maintenance & Security Hardening:** Periodic LMDB compaction / zstd out-of-line blobs for oversized values, same-origin check on `POST /call`, receipt-logged external effects (`my.fx/*`), and retiring temporary outside workarounds once `jolt/rebuild` replaces `main`.
