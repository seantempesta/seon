---
type: prd
status: draft
tags: [prd, agent, cljs, pod, architecture]
---

# CLJS MVP Completion Plan — close all gaps, remove all legacy

**Date:** 2026-05-27
**Branch:** `feature/agent-runtime`
**Scope:** the CLJS pod under `src/seon/*.cljs` + `*.cljc`. The seon JVM
is a database/wire-server host only; full JVM integration is deferred.
**Authority:** locked principles 1–8 from the orchestrator brief.
**Predecessors:** `v0-to-v2-transition-plan-2026-05-26.md`,
`codebase-audit-and-cleanup-plan-2026-05-26.md`,
`repl-session-context-template-2026-05-26.md`,
`schemas-as-queryable-data-2026-05-26.md` (Item 4, in flight).

## TL;DR

The MVP loop is alive: parse → eval → tee `:seon.fn`/`:seon.schema`/
`:seon.ns` → render with subsumption → SSE-push to inspector. The
chronological view is correct and `retro_stamp` is gone (e39c955).
What still blocks "initial codebase and runtime evaled and persisted
are identical" is mostly **boot-time substrate seeding**: a fresh
agent's DB has zero `:seon.fn` / `:seon.schema` / `:seon.ns` rows for
substrate code, so replay-from-tx-0 reconstructs nothing. The other
seven enumerated gaps are real; one (Gap 7, ALS removal) is the
largest single chunk of legacy still in the tree. A subtle live bug
also surfaced: `replay-program-graph!` writes `:seon.log/*` datoms
that no longer exist as DB attrs (commit 2f3497f).

This plan: 6 sequenced phases, ~1100 LOC net (more deleted than
added), one new test surface. Implementation agents work each phase
sequentially because phase boundaries are also REPL verification
gates.

---

## §1 — Current state assessment (the eight gaps)

For each: PASS / PARTIAL / GAP with citations.

### Gap 1 — substrate boot transacts as `:seon.eval` / `:seon.fn` / `:seon.schema` / `:seon.ns`

**GAP.** `src/seon/client.cljs:535-630` (`start-agent!`) opens the
conn, installs the bootstrap-CLJS state, runs `replay-program-graph!`
(line 584) and then `setup-agent-ns!` (line 594). At no point are the
substrate's own functions / schemas / namespaces written as DB
entities. The agent's first turn sees its own `(defn …)` evals
land as `:seon.fn` rows via `detect-and-tee` (`src/seon/eval.cljs:648-698`),
but `seon.db/transact!`, `seon.agent/run-turn!`, `seon.handlers.eval/render-ai`,
etc. have no `:seon.fn` row. Replay from tx 0 has nothing to replay.

This is the most consequential gap. Sean's vision: "Initial codebase
and runtime evaled and persisted are identical." Substrate code must
be a `:seon.eval` entity with `:seon.eval/source` = its file text,
landing in tx-time order at the lowest tx-ids, before any agent
form runs.

### Gap 2 — auto-instrument on tee

**GAP.** `seon.instrument/install!`
(`src/seon/instrument.cljc:100-130`) is called once from `-main`
(`src/seon/client.cljs:684`) before agent boot. It walks the analyzer
state at THAT moment and registers schemas with
`malli.core/-function-schemas*`, then runs `mi/instrument!`. Any fn
the agent defines AFTER `-main` (via `eval-batch!` → `detect-and-tee`,
`src/seon/eval.cljs:648`) is **not** re-instrumented; `:seon.fn/specced? true`
shows up in the DB but the var is unwrapped at runtime. Bad inputs
silently pass.

### Gap 3 — `:malli/schema` value not validated before tee

**GAP.** `seon.analyzer-info/var-projection`
(`src/seon/analyzer_info.cljs:158-170`) sets `:specced? (some? (:malli/schema meta))`.
It does **not** check that the schema value is well-formed. A
`(defn foo {:malli/schema [GARBAGE]} [x] x)` form produces a
`:seon.fn` row with `:seon.fn/specced? true` and no `:seon.fn/schema-error`.

### Gap 4 — auto-test-run on fn or test change

**GAP.** `src/seon/test/runner.cljs` provides `run-test-form!`,
`run-and-record!`, and `run-ns!`, but **no caller** wires them
into the eval pipeline. After `eval-batch!` transacts a `:seon.fn`,
nothing inspects `(:fn-refs <var-map>)` to find tests that reference
it; nothing inspects new `(deftest …)` forms and runs them. Status
attrs `:seon.test/last-passed-at` / `:seon.test/last-failed-at` are
registered (`src/seon/test/runner.cljs:119+`) but only set on
explicit `run-and-record!` invocations from the agent.

### Gap 5 — status indicators on `:seon.fn` cards

**PARTIAL.** `src/seon/handlers/fn.cljs:88-103` renders `specced` and
`private` pills. There is NO `tested?` / `test-passing?` pill. The
underlying data isn't even available on the entity: `:seon.fn` has
no `:seon.fn/tested?` or `:seon.fn/test-passing?` attr, and the
renderer doesn't join to `:seon.test` rows.

### Gap 6 — hiccup recursive validation

**PARTIAL.** A recursive schema IS registered as `:seon.render/hiccup`
(`src/seon/render.cljs:68-73`), but `:seon.render/html-response`
inlines `[:map [:seon.render/hiccup :any]]` (line 86) with a comment
acknowledging the
`:malli.core/potentially-recursive-seqex` instrument-time error. No
`[:fn valid-hiccup?]` predicate exists in the source tree
(grep: zero matches). Renderer outputs are effectively unvalidated.

### Gap 7 — AsyncLocalStorage still active

**GAP.** Two ALS instances live on:
- `src/seon/db.cljs:481-483` (`als-instance`, tx-context) +
  `src/seon/db.cljs:516-518` (`agent-id-als`).
- `src/seon/eval.cljs:205-207` (`warnings-als`).
The v0-to-v2 plan documents the single-atom replacement (per-eval
warning bucket on the agent map, tx-context threaded explicitly).
Not shipped on CLJS.

### Gap 8 — schemas-as-queryable-data (Item 4)

**IN FLIGHT** by another agent. Read-only confirmation:
`src/seon/schema.cljc:218-348` already derives
`:seon.entity/id-attr`, computes `map-required-attrs`, and transacts
`:seon.schema/required-attrs` rows. `src/seon/render.cljs:167-181`
(`renderable-kinds`) walks `m/properties` directly today — the
datalog query path is the in-flight work. This plan respects the
boundary and does NOT modify those files.

---

## §2 — Legacy path inventory

### LP-1. `:seon.log/*` DB writes in `replay-program-graph!` (LIVE BUG)

**Location:** `src/seon/client.cljs:451-462`
(`log-replay-failure!`).
**Why legacy:** commit 2f3497f deleted `:seon.log/*` from
`agent-bootstrap-attrs`. `seon.log` is a file-only API (NDJSON-EDN on
disk; see `src/seon/log.cljs:1-30`). `log-replay-failure!` still
calls `db/transact!` with `:seon.log/*` keys; the first replay
failure will throw `unregistered attribute`.
**Action:** REPLACE — call `log/warn!` (the file sink) instead.

### LP-2. ALS infrastructure

**Location:** `src/seon/db.cljs:468-574` (~110 LOC including
`with-tx-context`, `with-agent`, `current-tx-context`,
`current-agent-id`), `src/seon/eval.cljs:178-229` (`warnings-als`
plus `install-warning-dispatcher!`, ~50 LOC).
**Why legacy:** the v0-to-v2 plan replaces ALS with explicit
threading of `:seon.db/agent-id` and per-eval warning buckets stored
in the agent map; CLJS pod is single-process single-fiber for MVP
(no concurrent-await scenarios that need fiber-local). Removes a
`node:async_hooks` dep, makes write paths trivially traceable.
**Action:** REPLACE. ~160 LOC out, ~50 LOC in (explicit threading
helpers). Caller sites in `agent.cljs:441-445`, `agent.cljs:530-532`,
`agent.cljs:743-746` switch to passing `agent-id` as a normal arg.

### LP-3. `seon.web.broadcast` parallel SSE path

**Location:** `src/seon/web/broadcast.cljs` (189 LOC) +
`seon.web.sse` (CLJS, 59 LOC, `src/seon/web/sse.cljs`).
**Why legacy:** `seon.web.inspector/install!`
(`src/seon/web/inspector.cljs`) is the inspector's own tx-listener
with its own SSE registry (`!sse-by-agent`, line 42). It pushes
DOM morphs directly; `seon.web.sse` is its inlined emitter (see
the docstring at `inspector.cljs:371`). `seon.web.broadcast` is
left over from a tile-grid UI that no consumer page loads —
`codebase-audit-and-cleanup-plan-2026-05-26.md` flags ~250 LOC
with zero current consumers. `client.cljs:622` still calls
`web.broadcast/install!`.
**Action:** DELETE `broadcast.cljs` (189 LOC), DELETE
`web/sse.cljs` (59 LOC). Inline its 3-line `emit-patch!` helper
into `web/inspector.cljs`. Remove the `:require` + boot call in
`client.cljs:67-68, 622`. Net: −245 LOC.

### LP-4. Quarantined disabled test files

**Location:** grep finds none ending `.disabled` in this repo
post-audit (the brief's `test/seon/_disabled/session_test.clj.disabled`
is not present at HEAD). No action.

### LP-5. `retro-stamp` references in docs

**Location:** mentioned in
`docs/prds/agent-runtime/codebase-audit-and-cleanup-plan-2026-05-26.md`,
`v0-to-v2-transition-plan-2026-05-26.md`,
`research/repl-session-context-template-2026-05-26.md`,
`architecture/ctx-render-strategies-prd.md`.
**Why legacy:** the source ns is gone (e39c955) but doc cross-refs
remain.
**Action:** DELETE the bullet-point mentions in each cited file
(grep + manual scan). Keep historical context in the audit doc by
appending "(removed 2026-05-26 in e39c955)" rather than rewriting.

### LP-6. `seon.handlers.*` per-kind organization

**Location:** `src/seon/handlers/{eval,fn,message,ns,schema,wake}.cljs`.
**Why this is NOT legacy yet:** the orchestrator brief defers
colocation. The handlers are stable, well-tested, and the entity-
kind schemas in `src/seon/agent.cljs:301-354` reference them by
fully-qualified symbol. Co-locating `render-ai` into `seon.eval`,
`seon.message`, etc. would touch every entity-kind registration
AND every handler. Defer.
**Action:** KEEP. Note: when the JVM merge eventually happens, this
is the natural moment to colocate.

### LP-7. CLJ-side sidecar-poc references

**Location:** `src/seon/server/{store,session,wire,broadcast}.clj`
carry doc-comments pointing at `pod-host/sidecar-poc/jvm-writer/`.
**Why this is NOT legacy for the CLJS MVP:** these are JVM files,
out of MVP scope per principle 1. The actual `pod-host/sidecar-poc/`
directory has zero callers from `src/seon/*.cljs`.
**Action:** KEEP — defer to a future JVM-merge cleanup.

### LP-8. Two HTTP servers (CLJS pod + JVM) — INTENTIONAL during MVP

**Location:** `src/seon/web/serve.cljs` (424 LOC, CLJS pod's
loopback HTTP at port 7890) + `src/seon/web/server.clj` (JVM's
advanced web server with Caddy-fronted SSL termination at the
JVM's port).

**Why this is intentional:** Per the revised `v0-to-v2-transition-
plan-2026-05-26.md` §2.1-revised + §0b, MVP keeps PARALLEL
systems. The JVM has the production-grade web stack (Caddy proxy
for SSL, advanced Datastar SSE, full Datahike-backed UI, agent
JVM pool integration). The CLJS pod's `web/serve.cljs` is its own
loopback HTTP for the inspector pane. They coexist on different
ports during MVP. Multi-agent multiplexing across the JVM web
stack is its own work (deferred).

**Action:** KEEP BOTH. This plan does NOT propose merging the
HTTP layers or migrating the CLJS pod's web responsibilities into
the JVM. The full JVM-as-V2-server merge is Phase 8+ in the
v0-to-v2 plan, after the CLJS MVP is stable.

**For Platform reviewer:** the CLJS pod web stack stays AS-IS
through this MVP plan. Only the dead-weight parallel-SSE paths
(`web.broadcast` + `web.sse`, LP-3 above) get deleted. The main
`web.serve.cljs` + `web.inspector.cljs` stay because they're
load-bearing for the inspector pane and the chat POST endpoint
the agent UI uses. The JVM-side advanced web server is untouched
by this plan.

### LP-9. Stray `:any` violations

**PARTIAL.** Audit (`grep -n ":any\b" src/seon/*.cljs`):
- `seon.db.cljs`: ~12 `:any` uses for the wire-protocol payload (DB
  values, tx-data, query args). These are runtime-opaque handles;
  the registered shapes `:seon.db/db` / `:seon.db/conn` are `:any`
  on purpose (see `src/seon/render.cljs:48-49` rationale). KEEP.
- `seon.analyzer-info`: `::compile-state :any` and
  `[:map-of :any :any]` for var-map. Opaque analyzer state. KEEP.
- `seon.handlers.wake/wake-on-message`: `:seon.db/db :any` +
  `:seon.db/tx-report :any` + `:tx :any` (`handlers/wake.cljs:63-75`).
  Same opacity rationale. KEEP, but register them as
  `:seon.db/tx-report` once for reuse.
- `seon.handlers.schema/->shape` returns `:any` fall-through
  (`handlers/schema.cljs:38`). Replace with a registered
  `:seon.schema/shape-kind` enum. ACTION: minor; folded into Phase 5.

---

## §3 — The MVP target shape

After all phases land, the CLJS pod has this directory shape:

```
src/seon/
├── agent.cljs              ~1350 LOC  (no ALS scope wrapping)
├── agent_view.cljs           49
├── ai/deepseek.cljs         233
├── analyzer_info.cljs       170
├── client.cljs              ~700  (substrate-seed boot block added,
│                                   broadcast removed, ALS removed)
├── code.cljc                318
├── db.cljs                 ~1240  (ALS code removed, ~140 LOC out)
├── error.cljs                —
├── eval.cljs                ~960  (auto-instrument, schema-validate,
│                                   auto-test-run hooks; warnings-als
│                                   replaced with per-eval atom)
├── fs.cljs                  450
├── handler.cljs             255
├── handlers/
│   ├── eval.cljs            153
│   ├── fn.cljs              ~130  (+ tested?/test-passing? pills)
│   ├── message.cljs          64
│   ├── ns.cljs              133
│   ├── schema.cljs           72
│   └── wake.cljs            114
├── inspect.cljs              —
├── instrument.cljc           ~140  (+ instrument-one! helper)
├── log.cljs                 375
├── parse.cljc               228
├── platform.cljs             —
├── render.cljs              ~390  (+ valid-hiccup? predicate)
├── render/default.cljs      470
├── repl.cljs                 —
├── schema.cljc              437
├── test/runner.cljs        ~750  (+ run-tests-for-fn!)
├── ui/markdown.cljs         192
├── web/
│   ├── inspector.cljs      ~540  (inlined emit-patch! helper)
│   └── serve.cljs           424
└── (deleted)
    web/broadcast.cljs      −189
    web/sse.cljs             −59

```

Net: −1 file, −245 LOC web. Eval/db/instrument grow by ~250 LOC
combined. Net delta ≈ +5 LOC. Functionality grows substantially.

How an implementation agent verifies "are we there yet" — the
acceptance criteria in §4 are testable REPL expressions.

---

## §4 — Acceptance criteria

All testable in the REPL at the pod nREPL on `localhost:7889`. Each
should be `true` / non-empty / threw-as-expected.

1. **Substrate seed lands at lowest tx-ids.**
   `(let [c db/*conn*] (->> (d/datoms @c :aevt :seon.fn/sym) (take 5) (mapv :tx)))` — should be the
   FIRST tx-ids after the schema bootstrap, before any agent eval.

2. **Substrate `:seon.fn` count is ≥ 50** for a fresh boot:
   `(count (d/datoms @db/*conn* :aevt :seon.fn/sym))`.

3. **Substrate `:seon.eval` entities exist with `:seon.eval/source`** =
   substrate file text:
   `(d/q '[:find ?src :where [?e :seon.eval/source ?src] [?e :seon.eval/seed? true]] @db/*conn*)`
   returns N rows.

4. **Bad eval persists, doesn't reject silently.** After
   `(seon.eval/eval-batch! cs [{:kind :form :source "(/ 1 0)"}] 'cljs.user "a1" "t1")`:
   the resulting `:seon.eval` row has `:seon.eval/ok? false` AND
   `:seon.eval/error` populated.

5. **Auto-instrument wraps newly-tee'd fns.** After
   `(seon.eval/eval-batch! cs [{:kind :form :source "(defn foo {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"}] ...)`,
   `(foo "bad")` throws `:malli.core/invalid-input` (NOT NaN, NOT
   "Cannot call ... with bad args" — the envelope reaches the
   `:seon.eval/error-data` path).

6. **Malformed `:malli/schema` flagged, not silently accepted.** After
   `(seon.eval/eval-batch! cs [{:kind :form :source "(defn bad {:malli/schema [GARBAGE]} [x] x)"}] ...)`,
   the `:seon.fn` row has `:seon.fn/specced? false` AND
   `:seon.fn/schema-error` is a non-blank string explaining the
   failure.

7. **Auto-test-run on `deftest` form.** After
   `(eval-batch! cs [{:kind :form :source "(cljs.test/deftest sample (cljs.test/is (= 1 1)))"}] ...)`,
   the `:seon.test` row for `:seon.test/sym` matching `"sample"` has
   `:seon.test/last-passed-at` within 100 ms.

8. **Auto-test-run on fn change.** Given a test
   `(deftest foo-test (is (= 2 (foo 1))))` and then a fn change
   `(defn foo [x] (inc x))`, `:seon.test/last-passed-at` for
   `foo-test` advances. With `(defn foo [x] (dec x))`,
   `:seon.test/last-failed-at` advances and `:seon.test/last-failure-summary`
   is set.

9. **`:seon.fn` cards show four pills.** Open `/agent/<id>` and
   visually verify each `:seon.fn` card renders pills for
   `specced`, `private`, `tested`, `test-passing`.

10. **Hiccup validation rejects garbage.** Force a renderer to
    return `[123 "not a tag"]` — `seon.render/html-render` throws
    a clear `:invalid-hiccup` error rather than crashing the
    inspector with `Cannot read property of undefined`.

11. **No `node:async_hooks` import in db.cljs.**
    `grep -n "node:async_hooks" src/seon/db.cljs` returns nothing.

12. **No `node:async_hooks` import in eval.cljs.**
    `grep -n "node:async_hooks" src/seon/eval.cljs` returns nothing.

13. **No `web.broadcast` require anywhere.**
    `grep -rn "web.broadcast\|web/broadcast" src/seon/ src/seon/**/`
    returns nothing.

14. **Replay is faithful.** `(seon.client/replay-program-graph! …)`
    on a fresh empty conn, after substrate seed transacts:
    `(:seon.client/replay-n-ok r)` ≥ 50, `:replay-n-fail` = 0.

15. **Replay failure logs to file, not DB.** Inject a bad source
    into `:seon.fn/source`, re-run replay, check
    `logs/pod-events.log` for the warn line. No `db/transact!`
    call carrying `:seon.log/*` attrs anywhere in `client.cljs`.

---

## §5 — Phased plan

Six phases, sequenced because each gates the next.

### Phase 1 — fix the live bug

**Goal.** Stop `replay-program-graph!` from throwing on the first
replay failure.
**Scope.** `src/seon/client.cljs:451-462` only. Replace `db/transact!`
with `log/warn!`. ~15 LOC delta.
**Dependencies.** None.
**REPL gate.** Inject a bad source into a `:seon.fn` row, call
`replay-program-graph!`, verify it returns `{:replay-n-fail 1}` and
a `:warn` line lands in `logs/pod-events.log`. No exception.
**Estimate.** 30 minutes.

### Phase 2 — substrate boot seed (THE big one)

**Goal.** Make the substrate's own code present as `:seon.eval` +
`:seon.fn` + `:seon.schema` + `:seon.ns` entities at tx-time
ordering BEFORE the first agent turn. Replay-from-tx-0 then
reconstructs the system.
**Scope.**
- New ns `seon.substrate.seed` (~150 LOC). At pod boot, reads its
  own source files (via the existing `seon.fs` allowlist — add
  `src/seon/` as a substrate-read-only preopen) and emits, in
  dependency order:
  - `:seon.ns` entity per file, source = file text.
  - `:seon.eval` entity per top-level `(defn …)` /
    `(schema/register! …)` form, marked
    `:seon.eval/seed? true`, with `:seon.eval/source` = the form
    text, `:seon.eval/ok? true`, `:seon.eval/at` = the same
    `(js/Date.)` for the entire seed (one wall-clock instant; tx-id
    is the order).
  - `:seon.fn` / `:seon.schema` entities as if `detect-and-tee`
    had observed each form (the structural extractors in
    `seon.code` already parse defn/schema/ns forms).
- Wire into `start-agent!` BEFORE `replay-program-graph!`:
  - On first-ever boot of a conn (no rows): run seed.
  - On subsequent boots (rows present): skip seed; replay does
    the work.
- Add `:seon.eval/seed? :boolean` (registered schema), and entity-
  schema entry on `:seon.eval`.
- Subsumption rule already in `render.cljs:364` suppresses
  `:seon.fn`/`:seon.schema`/`:seon.ns` from the chronological
  window — substrate seeded entities are noise-free in the
  default view, surface only at "Front" or drill-in. No render
  changes needed.

**Ordering risk.** Must be deterministic. Strategy: enumerate files
in a fixed `seed-file-order` vector. Within a file, forms in
read order. Forward-ref-safe because all `(ns)` forms write first
(one `:seon.ns` per file) and `:seon.fn/ns` lookup-refs resolve
post-tx.
**Dependencies.** Phase 1.
**REPL gate.** Acceptance criteria 1, 2, 3, 14.
**Estimate.** 1 day. Most of it is the file-order list and verifying
no form parses to two different ns-context interpretations.
**Parallelism.** Can run in parallel with Phase 4 (different files,
no overlap).

### Phase 3 — schema validation + auto-instrument + schema-error

**Goal.** Close Gaps 2 + 3 together — they share the
`detect-and-tee` write site.
**Scope.**
- `src/seon/eval.cljs:648-698` (`build-tee-entities`): before
  stamping `:seon.fn/specced? true`, call a new
  `seon.instrument/validate-schema-meta!` that wraps Malli's
  `m/schema` in `try`. On success: tee `:specced? true`. On
  failure: tee `:specced? false` AND `:seon.fn/schema-error <reason>`.
- Register `:seon.fn/schema-error :string {:optional true}` in
  `agent.cljs`, add to entity-schema, add to
  `agent-bootstrap-attrs`.
- New helper `seon.instrument/instrument-one!` (~30 LOC): given an
  agent-defined fn symbol, registers its schema with
  `-function-schemas*` and re-wraps the var. Called by
  `build-tee-entities` for every `:specced? true` fn.
- Idempotency: re-defining a fn re-instruments — `mi/instrument!`
  reads the current schema-registry; safe.

**Race risk.** Auto-instrument runs INSIDE the same eval-batch! tx
context as the tee. The fn var was just defined by cljs.js (synchronous
during the eval); instrument-one! wraps the var; subsequent forms in
the same batch (or the agent's next turn) see the wrapped var. Tested:
no race because cljs.js's eval-str callback runs before
`build-tee-entities` enters.
**Dependencies.** Phase 1.
**REPL gate.** Acceptance criteria 5, 6.
**Estimate.** 0.5 day.

### Phase 4 — auto-test-run + status attrs + status pills

**Goal.** Close Gaps 4 + 5. After every `eval-batch!` that produces
either `:seon.fn` or `:seon.test` entities, find the affected tests
and run them.
**Scope.**
- New helper in `seon.test.runner`: `(tests-referring-to fn-sym)` —
  scans analyzer state via `analyzer-info` for `(deftest …)` forms
  whose body mentions `fn-sym`. Returns the set of test syms.
- After `build-tee-entities` + `record-eval!`, if any new `:seon.fn`
  was teed, call `run-and-record!` on `(tests-referring-to sym)`
  for each fn. If new `:seon.test` was teed (a `deftest` form),
  run it immediately. Fire-and-forget — uses a separate await chain
  inside `eval-batch!` so it doesn't block the turn return.
- Loop guard: tests must NOT trigger more tests. Pass an explicit
  `:seon.test/from-auto-run? true` flag through the tx-meta;
  `eval-batch!` skips auto-test-run for evals carrying that flag.
- Register new attrs on `:seon.fn`:
  - `:seon.fn/tested? :boolean` — derived true when there exists
    a `:seon.test` row whose test sym refers this fn. Computed on
    read (not stored) — the renderer joins.
  - For the simpler MVP: STORE `:seon.fn/tested? :boolean` and
    `:seon.fn/test-passing? :boolean` flags written at the moment
    auto-test-run finishes. Three-tier rule: these are derivable
    but we're storing pointers, not log content. Reasonable cache.
- `src/seon/handlers/fn.cljs:88-103`: add `tested` + `test-passing`
  pills.

**Loop risk.** Mitigated by `:from-auto-run?` short-circuit.
**Dependencies.** Phase 3 (instrumentation must be in place so test
runs see wrapped vars).
**REPL gate.** Acceptance criteria 7, 8, 9.
**Estimate.** 1 day.

### Phase 5 — hiccup validation + render polish

**Goal.** Close Gap 6 and resolve LP-9 schema/handler residue.
**Scope.**
- New predicate `seon.render/valid-hiccup?` (~20 LOC, recursive
  check: keyword head; optional map; children either nil, string,
  number, or another valid hiccup).
- Re-register `:seon.render/html-response` as
  `[:map [:seon.render/hiccup [:fn valid-hiccup?]]]`. The
  `:malli.core/potentially-recursive-seqex` error goes away —
  `[:fn pred]` is not a regex schema.
- `seon.handlers.schema/->shape` fall-through: register
  `:seon.schema/shape-kind [:enum :map :vector :enum :predicate :unknown]`
  and use it.

**Dependencies.** Phase 1.
**REPL gate.** Acceptance criterion 10.
**Estimate.** 0.5 day.
**Parallelism.** Can run in parallel with Phase 4 (no file overlap).

### Phase 6 — ALS removal + broadcast deletion

**Goal.** Close Gap 7 (LP-2), delete LP-3.
**Scope.**

ALS removal (`src/seon/db.cljs:468-574`, `src/seon/eval.cljs:178-229`):
- Delete `als-instance`, `agent-id-als`, `warnings-als`,
  `install-warning-dispatcher!`, the `.run` calls in `raw-eval` and
  `with-agent`.
- New: `(defonce ^:private !tx-context (atom {}))` — process-global,
  set/cleared explicitly. NOT fiber-local; the CLJS pod is single-
  threaded and the turn loop is serialized. Re-entrancy uses
  push/pop semantics: `with-tx-context` becomes
  `(let [old @!tx-context] (try (reset! !tx-context (merge old m)) (f) (finally (reset! !tx-context old))))`.
- Warning capture: pass an explicit `warnings` atom into `raw-eval`
  via a new arg (no fiber-local; the atom is per-eval and lives on
  the eval-batch! frame).
- `seon.agent.cljs:441-445, 530-532, 743-746`: pass `agent-id`
  explicitly to the kicked handler / async retransact / handler
  dispatch. No more "re-enter `with-agent`" comments.

Broadcast deletion:
- Delete `src/seon/web/broadcast.cljs` (189 LOC).
- Delete `src/seon/web/sse.cljs` (59 LOC) — its 3-line `emit-patch!`
  inlines into `web/inspector.cljs` (already partially inlined, see
  the comment at `inspector.cljs:371`).
- Remove `web.broadcast/install!` call and `:require` from
  `client.cljs:67-68, 622`.

**Dependencies.** Phase 4 (don't move stable infrastructure under
in-flight test code). Most risk-laden — touches 5 files.
**REPL gate.** Acceptance criteria 11, 12, 13. Existing per-agent
inspector tests must pass against the inlined SSE emitter.
**Estimate.** 1 day.

---

### Phase order recap

```
Phase 1 (bugfix)  →  Phase 2 (seed)        ─┐
                  →  Phase 3 (instrument)   │
                  →  Phase 4 (auto-test)    │── then Phase 6 (ALS+broadcast)
                  →  Phase 5 (hiccup)      ─┘

```

Phases 2, 3, 5 can each be a single agent. Phase 4 depends on Phase
3. Phase 6 is the riskiest single change and goes last so the prior
phases' verification is unaffected by the refactor.

---

## §6 — Risk register

### R1. Replay determinism collapses under bad seed order

Phase 2 emits substrate entities; if the file order or within-file
form order is wrong, replay will fail to find a fn before its
caller. Datahike + cljs.js are forgiving (forward refs at the JS
level resolve through globalThis munged paths), but `(ns)` forms
must precede defs in their ns. Mitigation: enumerate `seed-file-order`
literally; CI test that `replay-program-graph!` against a fresh
conn after seed-only returns `n-fail = 0`. Acceptance criterion 14.

### R2. Auto-test-run infinite loop

Phase 4 auto-runs tests when a fn changes; if a test redefines the
fn (e.g. via a `with-redefs` macro that expands to defn), the
re-tee triggers re-test. Mitigation: `:from-auto-run?` tx-meta flag
short-circuits inside `eval-batch!`. Belt-and-suspenders: hard cap
of 5 auto-runs per single user turn; the 6th drops a `log/warn!`
and stops.

### R3. Instrumentation race on re-def

Phase 3 instruments newly-tee'd fns. If the fn is referenced from
inside the same eval-batch! by a later form, the later form's
analyzer pass already saw the unwrapped var, but cljs.js's
`eval-str` resolves via globalThis at runtime — the WRAPPED var is
what the call hits. No race. Verified by mental model + the existing
boot-time path that already wraps every substrate fn before any
agent form runs.

### R4. ALS removal under concurrent-await

Phase 6 assumes CLJS pod is single-fiber. There IS a concurrent path:
SSE handlers run on Node's event loop independent of the turn loop,
and `seon.web.inspector` resolves agent-ids from tx-meta on every
tx-listener fire. Mitigation: tx-listener handlers do NOT need
`(current-agent-id)` — they read agent-id from the tx eid directly
(see `agent-view.cljs:21-29`). The only consumers of
`current-agent-id` are inside `with-agent` scopes (boot path, turn
path, handler dispatch). Audit: every `(db/current-agent-id)` call
site must be reachable only via a `with-agent` ancestor. Grep
confirms: `agent.cljs` 6 sites, all inside turn/run paths;
`eval.cljs` 1 site in `setup-agent-ns!` (boot path).

### R5. Schema-validation strictness rejects older data

Sean's "no migration" rule means existing rows are kept. If a row
written under an older schema is encountered, validation could
throw at the next read. Mitigation: schema validation runs on
WRITES (in `transact!`), not on reads. Existing pre-MVP rows pass
through unchanged. New schemas only constrain new writes. The
acceptance criteria don't depend on existing rows.

---

## §7 — Explicit non-goals

- **Full JVM merge.** The seon JVM stays a database/wire-server
  host; no CLJS code moves to JVM. Phase 6 deletions are CLJS-only.
- **WASM/Tauri containment.** Separate track. Nothing in this plan
  changes the wasm-rquickjs spike (`pod-host/wasm-tauri/`).
- **Multi-tenancy.** One JVM, many DBs; current shape is fine.
- **HTTP server merge between CLJS pod and JVM.** Two ports stay.
- **`seon.handlers.*` colocation into per-entity-kind namespaces.**
  Deferred — touches every entity-schema registration; revisit at
  JVM-merge time.
- **JVM-side cleanups beyond the live wire-server.** Not in scope.
- **Replacing `:any` for genuinely opaque runtime handles** (DB,
  conn, tx-report, var-map, compile-state). The current Malli idiom
  matches reality.

---

## §8 — Open questions for Sean

1. **Substrate seed: include `(ns)` forms as `:seon.eval` rows or
   only the inner defs?** Phase 2 currently plans one `:seon.eval`
   per defn / schema/register! / deftest, plus one `:seon.ns`
   marker per file. The `(ns)` form itself doesn't become a
   `:seon.eval`. Replay re-evals from `:seon.ns/source` for the file
   header and `:seon.eval/source` for each form. Alternative: one
   `:seon.eval` per top-level form including the `(ns)` form, so
   chronological replay is a flat sequence. The flat shape is
   simpler but loses the per-form rendering distinction.

2. **Auto-test-run caching: store `:seon.fn/tested?` and
   `:seon.fn/test-passing?` as derived-but-cached boolean datoms,
   or compute on read via a single datalog query?** Phase 4 plans
   to store. Three-tier rule says the cached value is fine
   (pointer, not log content); but stored flags can go stale if
   test-runner crashes mid-run. Alternative: compute on read with
   a Malli `:default/fn` on the renderer's input. Tradeoff:
   ~30µs/render vs. one-extra-tx-per-test-run.

3. **ALS removal scope: keep `with-tx-context` push/pop semantics,
   or thread the tx-context explicitly through every `transact!`
   call?** Explicit threading is the "right" form per the "code as
   data — runtime IS the database" principle (no implicit state),
   but mechanically painful: every transact! call site grows an
   arg. Push/pop is single-fiber-safe and one-line. The pod IS
   single-fiber. Recommendation: push/pop. Confirm.
