---
type: research
status: active
tags: [research]
---

# Test-Stability Audit + Safe-Fix Sweep (2026-06-10, overnight unit #19)

## TL;DR

- **CLJS lane is STABLE.** `bin/test-cljs` run 3x at c9f0ce1: 287/1071/0 exit-0
  every time, identical 32-ns test sets (diff-verified). The earlier
  284-vs-287 "nondeterminism" was a stale cross-commit measurement, not a
  flake. Post-fix run: 288/1073/0 exit-0, 0 compile warnings.
- **JVM lane (first full run in 24h+): 2544 pass / 1 fail / 0 errors** after
  this sweep. Started at 2543/2; the markdown failure was fixed (docs), and a
  NEW first-run-green/rerun-red failure in `seon.server.boot-test` was
  root-caused to a **registry-hook reload bug in production `.clj`** (the big
  finding below) — live JVM state repaired via REPL, test fixture hardened.
- The remaining 1 failure is the known `seon.flow.status/collect-flow-status`
  `:incomplete-spec` compliance violation (production `.clj` → report-only).
- Safe fixes shipped: markdown vault violations (2 docs), `(do *ns*)` parity
  docstring note, `listen!` `::keys` arglist display-expansion (+ pinned
  test), `fs/home-dir` `[:maybe :string]` → `:string`, boot-test fixture
  isolation.

## THE LEDGER

| # | Suite | Failure / item | Root-cause class | Action | Owner |
|---|-------|----------------|------------------|--------|-------|
| 1 | CLJS | 284-vs-287 test-count variance | stale measurement (cross-commit), NOT flake | closed — 3x identical at c9f0ce1; verifier's 284/1058 run predates the #20-lane test additions | — |
| 2 | CLJS | `no-stub-source-anywhere` intermittent | unreproduced flake (0/4 runs) | ledgered; plausible mechanism below | CLJS lane |
| 3 | JVM | `seon.dev.markdown-test/vault-zero-violations-test` | stale docs (2 files) | FIXED (docs edits), test green | done |
| 4 | JVM | `seon.dev.conventions-check-test/phase-3…` — `seon.flow.status/collect-flow-status` returns `[:maybe ::flow-status]` (status.clj:400) | real-bug (spec violates no-maybe rule) | REPORT-ONLY (production .clj). Recommended: return `::flow-status` and throw/envelope for unregistered flows | JVM track |
| 5 | JVM | `seon.server.boot-test/register-subscription…` 4 assertion fails, first-run-green/rerun-red | **real-bug: defonce-guard vs reloadable registry** (details below) | source = REPORT-ONLY; live JVM repaired via REPL; test fixture hardened (test-lane) | V2 server track |
| 6 | CLJS | `(do *ns*)` parity boundary | documented limitation | FIXED — docstring note in `seon.eval/parity-intercept` | done |
| 7 | CLJS | `listen!` `::keys` arglist render | real-bug (display: `::keys` mis-resolves outside owning ns) | FIXED — `expand-local-auto-kws` in client.cljs + pinned test | done |
| 8 | CLJS | `fs/home-dir` `[:maybe :string]` (fs.cljs:356) | convention violation (no-maybe) | FIXED — `:string` return; throws legibly on :wasi / missing HOME. Sole reference is the roster `#'`-literal (never called in src/test); pod is :node-only → no live caller behavior change | done |

Counts per class: 2 stale-test/docs (fixed), 2 real-bug in production source
(report-only: #4, #5-source), 2 convention/display bugs (fixed: #7, #8),
1 documented limitation (noted: #6), 1 unreproduced flake (#2), 1 stale
measurement closed (#1).

## BIG FINDING — registry-hook reload bug (JVM, production .clj, report-only)

`seon.server.registry/!on-ensure-db-hooks` is a plain `(defonce … (atom []))`
in a **reloadable** ns; `seon.server.boot/reactive-hook-installed?` and
`seon.server.wire/raw-broadcast-hook-installed?` are defonce guards whose
side-effect registers their hook ONCE per JVM. When clj-reload reloads
`seon.server.registry` (unload removes vars → fresh empty atom) without
reloading boot/wire, the hooks are silently LOST and the guards block
re-registration **until JVM restart**:

- Observed live: `!on-ensure-db-hooks` count = 0 while `reactive-hook-installed?`
  = true. Consequence: `register-subscription` ops fail with
  `Bad entity attribute :seon.subscription/id … not defined in current schema`
  (the hook that seeds the subscription schema + installs the `::reactive`
  listener never runs). All failures swallowed by `(catch Throwable _)` —
  fail-silent, exactly the pattern `feedback_surface_errors_loudly` bans.
- This made `seon.server.boot-test` green on the first post-restart run and
  red forever after (suite runs 2+3 today: 5 fails each).
- **Live repair applied (REPL, no source change):** `ns-unmap` both guards +
  `(require 'seon.server.wire :reload)` + `(require 'seon.server.boot :reload)`
  → hook-count 2 → boot-test green 3x, full suite 2544/1/0.
- **Recommended source fix (HOLD for the V2 server owner):** tag
  `!on-ensure-db-hooks` (and the registry atoms) `^:clj-reload/keep`, OR make
  hook registration key-based idempotent (`register-on-ensure-db-hook! ::reactive f`
  replacing by key) and drop the defonce guards. Also remove the silent
  `catch Throwable _` around the hook body.

### Test-lane hardening shipped alongside

`test/seon/server/boot_test.clj`: the fixture's ambient db-name is now unique
per invocation (`boot-test-ambient-<nanos>`). `seon.server.boot/!engines`
caches engine state by db-name for the JVM's lifetime; a fixed name handed
later runs a stale engine bound to a dead conn. Proof: boot-test 3x
`[0 17]` green, full suite green.

## Fixes applied (with proof)

1. **Markdown vault** — `research/e2e-demo-findings-2026-06-08.md` (two
   wrapped multi-line `###` headings joined), `RESUME-2026-06-08.md`
   (dangling wikilink → plain memory-note name; continuation line starting
   with `+ ` no longer parses as a plus-list). Proof: `seon.dev.markdown-test`
   61 assertions green.
2. **`seon.eval/parity-intercept` docstring** — names `(do *ns*)` wrapping as
   a known not-intercepted parity boundary (silently nils).
3. **`seon.client/expand-local-auto-kws`** (client.cljs) — stored
   `:seon.fn/arglists` now expand namespace-local `::kw` to the explicit
   `:owning.ns/kw` (alias-qualified `::a/x` untouched — verified no roster fn
   has alias-`::` in an ARG VECTOR; the `::db/keys` hits in db.cljs are inside
   docstrings, which the parser skips as strings). Pinned by new
   `arglists-expand-local-auto-kws` deftest. Proof: run 4 = 288/1073/0.
4. **`seon.fs/home-dir`** — `[:maybe :string]` → `:string`; throws
   `ex-info` on :wasi and on missing HOME/USERPROFILE (absent = error, never
   nil). No live caller behavior change (grep: sole reference is the
   `substrate-vars` roster literal).
5. **boot_test.clj fixture** — unique ambient name per test (above).
6. **Live JVM hook repair** — REPL-only, no source change (above).

Suites after all fixes: `bin/test-cljs` 288/1073/0 exit-0, 0 compile
warnings; JVM `(user/run-tests)` 2544 pass / 1 fail / 0 errors (the 1 =
ledger #4, report-only).

## Flake watch

- **`no-stub-source-anywhere`**: did NOT fail in 4 consecutive runs at
  c9f0ce1. Plausible mechanism if it recurs: `index-substrate!` reads source
  at var-meta `:file`/`:line`; under live-pod hot reload (or a shadow watch
  rebuild racing the test compile) line meta can drift from on-disk source →
  `extract-form-at-line` grabs a non-`(defn` form. Test-process runs are
  deterministic (fresh node, no hot reload), consistent with zero repro here.

## REPORT-ONLY items (morning read)

- **Stub self-wake loop dynamics (confirmed by reading, no redesign):**
  `seon.client/stub-llm` (client.cljs:1163) ALWAYS emits exactly two forms
  (`reply!` + state→:idle transact). `run-agentic-loop!` (agent.cljs:1037)
  stops on error / zero-forms / turns-cap — the zero-forms policy can NEVER
  fire for a stub agent, so a single wake burns turns to `turns-cap`. With
  two stub agents messaging each other, `reply!` ping-pongs hops until the
  hop-cap wake guard (documented incident at agent.cljs:676). Cheap option if
  wanted later: stub emits zero forms on its 2nd+ turn since inbound.
- **`data/seon-pod` disk growth:** 3.1 GB total, **2,295** run dirs; largest
  `2026-06-09T21-39-44-890Z` ≈ 782 MB; next: 130 MB, 99 MB, 78 MB, 63 MB.
  Nothing deleted. Policy options: (a) keep-last-N dirs (e.g. 20) pruned at
  pod boot; (b) age-based (>48h) prune; (c) `bin/seon` subcommand for manual
  prune; (d) cap per-run size at write time (the 782 MB outlier suggests one
  runaway log/blob — worth a look inside before choosing).
- **Broken `default` MCP cljs session — reproduced:** `(+ 1 1)` on
  session `default` (nrepl-sid 6ef305b5…) → NPE in
  `nrepl.middleware.interruptible-eval` (`Compiler.currentNS()` is null — the
  session's `*ns*` binding frame is corrupted/nil). Other sessions fine
  (verified `c630f8` → 2). Reset path: drop + recreate the singleton
  `default` nREPL session in the seon-cljs MCP server (or restart that MCP
  server); shadow itself is healthy. Also: **26 MCP cljs sessions**
  accumulated (ages up to 7h) — session GC worth considering.
- **Two parallel per-agent ALS instances (Phase-1 decision, held):**
  `seon.db/agent-id-als` (db.cljs:539) and `seon.agents/substrate-ctx-als`
  (agents.cljs:182) are never established together; plus stash-prefix drift
  vs eval.cljs and `::state` duplicating DB `:seon.agent/state` (c9f0ce1
  commit smells). Decide once in Phase-1 wiring: one ALS carrying a single
  per-agent context map.
- **`collect-flow-status`** (src/seon/flow/status.clj:393): return spec
  `[:maybe ::flow-status]` — recommended in-place bump to `::flow-status`
  with an explicit not-registered error (or response envelope), then the
  conventions test goes green. JVM/.clj lane owns it.
- **inspector-snapshot-404: NOT implemented** — the concurrent UI agent still
  owns `src/seon/web/inspector.cljs` + `src/seon/handlers/message.cljs`
  (uncommitted modifications live in the working tree throughout this unit;
  no report landed). Where the fix lives when freed: `seon.web.inspector`'s
  `route?`/`handle!` pair (serve.cljs:366 dispatches to it; anything it
  doesn't claim falls to the 404 at serve.cljs:367).
