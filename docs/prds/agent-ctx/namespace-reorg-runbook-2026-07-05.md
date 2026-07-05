---
type: reference
status: active
tags: [reference, agent]
---

# Namespace reorg — EXECUTION RUNBOOK (owner-approved scope)

**For the executing agent: this is a MECHANICAL pass. Every decision is
pre-made below. If reality contradicts a step (file missing, unexpected
consumer, red gate), STOP that section and report — do not improvise a
design decision.** Evidence basis:
[[research/namespace-reorg-survey-2026-07-05]] (the full inventory).

Goal: fewer top-level `seon.*` segments, duplicate mechanisms dead, stale
mass deleted — ACTIVE track only (pod `.cljs`, shared `.cljc`, live `.clj`
infra). The JVM-paused track is untouched (revisit at resume).

## Preconditions (verify ALL before the first edit)

1. **No other writers.** The AR-autofix unit (seon.repair/candidates,
   seon.eval pre-flight — session of 2026-07-05) has LANDED and is
   COMMITTED. `git status` shows no uncommitted `src/` edits you didn't
   make. The owner has quiesced the other lane (tooling / default pod).
2. `git log --oneline -3` noted in your report (rollback anchor).
3. `bin/test-cljs` baseline is green on HEAD (do not skip — you need a
   trusted before-state; ~300s).
4. Rollback rule: any gate red twice → `git checkout -- <section files>`,
   report, move to the next section only if independent.

## Mechanics of ONE ns rename (apply per row; the dev hook lints each edit)

a. `git mv` the file(s) to the new path (underscores in filenames).
b. Update the `(ns …)` form + its docstring self-references.
c. Update every requirer: `grep -rln '<old-ns>' src/ test/ src-diffusion/
   bin/ shadow-cljs.edn config/ docs/` — requires, aliases, FQ symbols,
   string refs. Keep existing ALIASES unchanged where they still read well
   (`[seon.eval.repair :as repair]` — churn-free call sites).
d. Keywords: `::` keys follow automatically. LITERAL `:seon.<old>/…`
   keywords in code/tests get renamed too (keyword-ns = code-ns rule) —
   ONLY for the nss marked "keys: envelope-only" below. If you find a
   literal keyword of a renamed ns being TRANSACTED (`db/transact!` /
   seeded rows) that this runbook didn't predict → STOP, report.
e. Test files move in the same step (`test/seon/...` mirrors `src/`).
f. Section gate: the moved nss compile — run the section's listed check.

## Section 1 — deletions (stale mass; verified zero live consumers)

| delete | notes |
|---|---|
| `src/seon/claude/exploration.clj` (+ dir if empty) | 0 deps; only refs are an old worktree + settings history |
| `src/seon/experimental/sci_exploration.clj*` (+ dir) | 0 deps |
| `src/seon/ai/agent/views.clj` | 16.2k tok, 0 deps |
| `src/seon/health/metrics.clj` + `test/**/health/metrics_test.clj` | BMI leftovers — violates the no-consumer-code rule |
| `src/seon/dev/repair.clj` | duplicate of `seon.repair` (.cljc); FIRST rewire its requirers (the dev hook — grep `seon.dev.repair` under `src/seon/dev/`) to require the cljc ns (post-§3 name: `seon.eval.repair`) and verify the hook still lints a scratch edit |

HELD (owner call, do NOT delete): `seon.ai.claude` (17k tok, 0 in-src deps
but possibly config-referenced).

Gate: `grep -rn "claude.exploration\|sci-exploration\|ai.agent.views\|health.metrics\|dev.repair" src/ config/ resources/ shadow-cljs.edn bin/` → only historical docs hits remain.

## Section 2 — merges

1. **`seon.result` → into `seon.items`** (result's sole consumer; 455 tok
   combined). Move the defs, update `seon.items`' docstring, delete
   `result.cljs`, rewire requirers. Literal `:seon.result/*` keywords (if
   any beyond `::`) follow to `:seon.items/*` — envelope-only.
2. **`seon.demo` → `seon.dev.demo`** + update the 3 shadow-cljs
   `:preloads` entries (lines ~69/103/125).

Gate: `clj -M:cljs compile worker-oracle-eval` (fast build touching the
shared graph) + `grep -rn "seon.result\|seon\.demo" src/ shadow-cljs.edn` clean.

## Section 3 — nestings (dependency order; each row = mechanics a-f)

| # | from | to | keys | external strings to update |
|---|---|---|---|---|
| 1 | `seon.repair` | `seon.eval.repair` | envelope-only (17 `:seon.repair/*` — never transacted; verify with `grep -rn ":seon.repair/" src/ \| grep -i transact` → empty) | none |
| 2 | `seon.repair.candidates` (post-autofix layout — VERIFY actual name first) | `seon.eval.repair.candidates` | check its keys the same way | `src/seon/worker_eval.cljs` requires it |
| 3 | `seon.analyzer-info` | `seon.eval.analyzer-info` | none registered | none |
| 4 | `seon.test.runner` | `seon.eval.test-runner` | check `:seon.test.runner/*` literals — envelope expected | `seon.dev.test-preload` docstring mention |
| 5 | `seon.indexing` (.clj macro ns) | `seon.dev.indexing` | none | none |
| 6 | `seon.worker-eval` | `seon.worker.eval` | n/a | shadow-cljs `:worker-oracle-eval` `:main`; `bin/oracle-server` comments; `src-diffusion/src/seon_diffusion/config.py` + `oracle.py` reference only the BUNDLE PATH `out/worker-oracle-eval/main.js` — keep the build id + output path UNCHANGED (zero Python churn) |
| 7 | `seon.worker-validator` | `seon.worker.validator` | n/a | shadow-cljs `:worker-validator` `:main`; same rule — build id/output unchanged |
| 8 | `seon.warn` | `seon.agent.warn` | 17 register! — verify none transacted (`grep -rn ":seon.warn/" src/ \| grep -i "transact\|seed"`); if ANY persisted → note "cluster reset required" in report and proceed | none |
| 9 | `seon.state` | `seon.db.state` | check same way | none |
| 10 | `seon.retry` | `seon.agent.retry` | envelope | none |
| 11 | `seon.route` | `seon.web.route` | **persisted** `:seon.route/*` attrs — keywords follow; cluster resets REQUIRED after (post-steps) | config manifests if they name route attrs (`grep -rn "seon.route" config/`) |
| 12 | `seon.store.wire` + `seon.store.internal.wire-node` | `seon.db.store.wire` + `seon.db.store.internal.wire-node` | check | shadow `:wire-node` `:main` if it points here |
| 13 | `seon.handlers.{eval,fn,message,ns,schema,test}` | `seon.render.entity.{…}` | none registered | none |

Gate after rows 1-5, again after 6-9, again after 10-13:
`clj -M:cljs compile worker-oracle-eval && bin/test-cljs` targeted? NO —
full suite only ONCE at the end; per-batch gate = the compile above plus
`grep -rn "<each old ns>" src/ test/ bin/ shadow-cljs.edn config/` → zero
non-doc hits.

## Section 4 — final verification (in order)

1. `grep -rn` sweep: EVERY old name from §1-3 → zero hits in src/ test/
   bin/ config/ shadow-cljs.edn src-diffusion/ (docs/ hits get updated in
   §5, historical research files stay as-is).
2. `clj -M:cljs compile worker-oracle-eval && clj -M:cljs compile bootstrap && bin/fix-bootstrap-macros`
   (bootstrap re-emits the analysis cache with new ns names).
3. `bin/test-cljs` — FULL suite once; report honest counts.
4. `cd src-diffusion && .venv/bin/pytest -q` (oracle/eval bundle contract).
5. `bin/acme build` (compiles the acme bundle against renamed nss).
6. Cluster resets (persisted attrs moved — `seon.route`, possibly warn):
   `bin/seon cluster reset default` ONLY if the owner has confirmed the
   tooling lane is done with it; `bin/acme` reset for acme (ours). Then
   `bin/seon start dg-worker` + one guided wire smoke
   (`curl POST /run mode:probe` → worker_sha present).
7. Live pod proof on acme: boot, roster minted, one agent eval round-trip
   (`:seon.fn` row lands — the tee path survived the renames).

## Section 5 — docs + commit

- Update non-historical doc references: `src/seon/CLAUDE.md` mechanism
  table, `docs/seon/architecture/*.md`, `docs/conventions.md`,
  `docs/prds/diffusion-dynamic-context/CLAUDE.md` — grep each old name;
  DATED research files are history, leave them.
- ONE atomic commit, explicit pathspecs (shared index — `git diff --stat
  --cached` review first; do not sweep other lanes' files). Message
  summarizes: segments 49→~31 active, duplicates killed, stale deleted,
  full-suite counts.
- Report: per-section outcomes, any STOP items, suite counts, which
  resets ran, rollback anchor sha.

## Pre-made decisions (do not reopen)

- `seon.instrument` / `seon.error` / `seon.error.instrument`: **no change**
  — coupled family but no duplicated logic (content-verified); forcing a
  merge is risk without payoff. `seon.dev.instrumentation` is JVM-paused.
- `seon.log` vs `seon.logging`: **no change now** — different mechanisms,
  logging is JVM-paused (renames at JVM resume).
- `seon.state`/`seon.runtime`/`seon.session`: NOT duplicates; only state
  (pod) moves.
- JVM-paused families (core/runner/system/ctx/ns.*/runtime/session/
  health/flow/web-clj/ai.claude…): untouched.
- `seon.derive`, `seon.log`, `seon.platform`, `seon.repl`, `seon.embed`,
  `seon.graph`, `seon.db`, `seon.schema`, `seon.eval`, `seon.agent`,
  `seon.ai`, `seon.config`, `seon.client`, `seon.render`, `seon.ui`,
  `seon.web`, `my.*`: stay.
