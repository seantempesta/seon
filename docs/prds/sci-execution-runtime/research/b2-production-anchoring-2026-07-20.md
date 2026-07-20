---
type: research
status: active
tags: [research, agent, architecture, database]
---

# B2 — sci child at production anchoring: retention + turn latency (2026-07-20)

One real agent driven end-to-end through REAL turns on a sci-engined
execution child speaking the production child protocol, on an isolated
branch cluster, A/B against the normal self-host child on the SAME branch
database with the SAME scripted workload. This closes B1's caveat ("the
harness anchors less live state than a real child").

## Wiring (what was built, all B2-experimental)

- **deps.edn** (`:cljs` alias): sci enters as
  `org.babashka/sci {:local/root "reference-code/sci"}` — checkout HEAD
  `be4021d`, which contains the JIT commit `45bcf0f`. PACKAGING
  IMPLICATION (honest): `:local/root` is not a publishable coordinate; a
  production cutover needs a git dep on a pushed mirror. The harness
  source root `tmp/sci-probe/exec-src` is also on the alias; no
  production build requires anything from it.
- **shadow-cljs.edn**: two experimental builds, compiled one-off in a
  separate Shadow cache (`SHADOW_CLJS='{:cache-root ".shadow-cljs-b2"}'`,
  supported by the maintained shadow fork), never watched, never selected
  by any operator flavor:
  - `:execution-sci` → `out-b2/execution-sci/main.js`, main
    `seon.execution.sci-runtime/-main`;
  - `:b2-driver` → `out-b2/driver/main.js`, main
    `seon.execution.b2-driver/-main`.
- **`seon.execution.sci-runtime`** (tmp/sci-probe/exec-src): the
  PRODUCTION child composition with ONE substitution. It boots through
  the production `seon.execution/-main` (real Transit IPC, artifact
  digest self-verification, real `db/open-session!`, real
  `admission/prepare-committed!`/`admit-prepared!`), reuses the
  production `render-prompt!`/`render-agent-view!` entries verbatim from
  `seon.execution.runtime/compiled-functions`, and replaces only the
  `'seon.execution.runtime/eval-batch!` compiled entry with a sci-engined
  batch loop. The self-host branch point is exactly the artifact-level
  seam the runbook demanded: the compiled-function map — no production
  file changed, no runtime flag. The sci child's bundle still CARRIES
  cljs.js (it requires `seon.eval` for the reused engine-independent
  owners) but never initializes it; the measured deltas are engine
  state, not bundle-floor deltas (blocker 4 remains separate).
- **`seon.execution.b2-driver`**: mirrors the maintained
  `:execution-integration-client` pattern. Configures the production
  `seon.execution.host` at an explicit artifact (normal or sci) against
  the branch database, runs its own admission, mints a real agent
  (`seon.agent/mint!`), pins `:seon.config/repl-mode :batch` on the
  agent (the cluster config selects `:stream`, which evaluates only the
  first form per turn — discovered live), opens a real run
  (`seon.agent.run/open-run!`), and drives turns through the REAL
  `seon.agent.turn/run-turn!` with a scripted llm-fn returning
  `{:text … :seon.ai/adapter :stub}`. LLM CREDENTIALS WERE NOT USED —
  the turn/eval path is the measurement subject; everything else
  (prompt render in the child, blob capture, open/close-turn txs,
  eval-batch in the child, receipts, transcript growth) is production
  machinery. The driver samples `vmmap --summary` (child env carries
  `MIMALLOC_OS_TAG=240`) at named phases and closes the run at the end.
- Branch cluster: `bin/seon branch open b2` → database `default-b2`,
  connection-id `[54b5b7e7… :seon.branch/default-b2]`, driven through
  the LIVE default writer's UDS socket. Never the default database.
- Harness runner: `tmp/sci-probe/exec/run-b2.sh <label> <artifact>
  <build-id> [turns]`; raw vmmap + EDN results in
  `tmp/sci-probe/exec/out/`.

## Workload (identical for both drives)

21 real turns against one agent: defn + cross-turn reuse turns, db
round-trip turns (`db/query` scalar against the branch), a `my.plan`-era
turn-close path every turn (`publish-generated-program!` runs on every
run-attached close), turn 10 = a 202-form heavy burst (100 defns + 100
calls + `(def burst-data (vec (range 50000)))`), turn 11 = `(js/Bun.gc
true)` then a 60 s settle before the retention sample, final turn =
gc + settle. Both drives: **21/21 turns closed `:done`** with matching
per-turn eval counts (3/2/…/202). Semantic spot-proof on the sci side:
`(b2-double 21)` → 42, `(b2-step-3 3)` → 9 (cross-turn value-def reuse),
`(reduce + (map b2-double (range 1000)))` → 999000, db query scalar
returned, burst 202/202 ok, and the deliberately schema-invalid
`db/transact!` form returned the SAME error VALUE on both engines
(errors-as-values parity).

## Memory per phase (vmmap Physical footprint, one child pid per drive)

| Phase | normal child | sci child | delta |
|---|---|---|---|
| P1 boot + session + admission + engine init | 465.2M | 298.0M | −167M |
| P2 first prompt render | 701.3M | 383.3M | −318M |
| P3 after first eval turn | 510.6M | 326.7M | −184M |
| P4 after 202-form burst + gc + 60 s (retention) | 445.5M | 230.8M | −215M |
| P5 after all 21 turns + gc | 441.9M | 231.3M | −211M |
| Peak | 701.3M | 419.4M | −282M |

Mimalloc tag-240 regions are small in both (dirty 15–23M) — the JSC/JS
heap dominates, so footprint is the honest comparator. At production
anchoring the sci child SETTLES ~211M below the normal child and holds
that settle through the burst; neither child shows burst-proportional
permanent residue at this burst size (both settle below their pre-burst
phase), so the retention win at anchoring manifests as the standing
~211M engine-state difference plus a 282M peak reduction.

## Turn latency (same branch, same workload, LLM excluded)

Non-burst turns (n=20 each):

| | mean | median | p95 | min | max |
|---|---|---|---|---|---|
| normal | 5272 ms | 4258 ms | 8914 ms | 2899 ms | 9125 ms |
| sci | 2774 ms | 2728 ms | 3281 ms | 2361 ms | 3590 ms |

202-form burst turn: **345.9 s (normal) vs 64.3 s (sci)** — 5.4×. The
normal child's defn turns (6–9 s) are dominated by self-host
compile + tee work; sci turns are uniform ~2.4–3.6 s, dominated by the
shared prompt-render/receipt path.

Honest caveat on iso-context: the final-turn assembled context was
33.2k tokens (sci) vs 47.1k (normal). Both transcripts grew per turn
and render the same eval receipts; the gap comes from reduced
program-graph/tee metadata on the sci side (minimal tee) plus the
normal engine's verbose opaque-value defn receipts (sci records the
readable `#'my.agent…/f` var; self-host records an
`:seon.eval/opaque-value` map). Some of the non-burst latency advantage
is therefore smaller-context rendering; the burst factor is
eval-dominated and stands on its own.

## B1 adapter items: implemented vs deferred (the B→decision punch list)

Implemented in `seon.execution.sci-runtime`:

1. error-prose synthesis (minimal: catch-site classification + the
   "ran NOTHING" compile prose; `home-ns-alias-hint` not wired);
2. warning→catch-site classification (same mechanism);
3. binding-table provisioning — COMPUTED from the admitted database
   program graph (`:seon.fn` rows) + `eval/lookup-value`, authored
   sources through sci `:load-fn`; never a hand list;
6. print capture through `sci/print-fn` (per-form buffer; NOT
   ALS-spanning across awaits);
7. `setup-agent-ns!` sci form (`home/home-requires-for` +
   `home-ns-form` evaluated in the ctx; `:refer` resolves);
9. timeout semantics (sync wall-clock deadline through `:interrupt-fn`;
   Promise path uses the production `race-timeout`/`budget`/`defer`
   verbatim).

Deferred (each now with production-anchored evidence of its cost):

4. sci resolution queries for prose-demote/preflight repair — the
   repair sub-loop and `prose-paren?` are absent from the sci batch;
5. instrumentation reapply over sci vars — evaluated fns are not
   malli-wrapped in the sci child;
8. cljs.test in ctx — agent deftest-through-eval unsupported;
   plus, surfaced by B2: the FULL program-graph tee (contract
   transitions, read-attrs, require-edge txs — the minimal defn/ns tee
   costs ~14k tokens of namespace/transcript context vs normal, the
   measured anchoring gap above); result-var admission caps/pruning
   (sci interns uncapped); failed-defs/namespace fencing and
   skipped-entries bookkeeping; ALS-spanning print capture.

## Other findings

- PRODUCTION BUG found and fixed in passing:
  `my.plan/generated-namespace-for-run-query` declared
  `:find [?a ?b ?c] .` (find-tuple + scalar dot — unparseable), erroring
  EVERY run-attached turn close on this branch. Fixed in
  `src/my/plan.cljs` (dot removed, `::db/max-results` 4); issue
  [[../../../seon/issues/generated-namespace-run-query-unparseable-find]]
  closed with live proof.
- Cluster config `repl-mode :stream` silently evaluates only the first
  form of a multi-form reply — expected streaming behavior, but it
  invalidated the first measurement pass; drives pin `:batch`.

## Gate verdict

**PASS.** At production anchoring the sci child holds a ~211M lower
settled footprint (231M vs 442M) with a 282M lower peak through a
202-form burst + 20 real turns, and there is NO eval-latency
regression — non-burst turns are ~1.9× faster (median) and the burst is
5.4× faster, with the iso-context caveat noted. The deferred items
above are the concrete B→decision punch list. Blocker 3 (retention
re-proof at production anchoring) closes; blocker 4 (bundle floor —
this artifact still ships cljs.js unused) remains open and is the next
B-side measurement.
