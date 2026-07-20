---
type: research
status: active
tags: [research, agent, architecture]
---

# B1 — sci engine behind the eval boundary: corpus + divergence (2026-07-20)

The sci engine placed behind the EXISTING `seon.eval` boundary contract
in the reproducible harness, with the eval/repl behavioral test corpus
driven through it. Production `seon.eval`/`seon.execution` are
untouched; the deliverable is this divergence list, not a cutover.

## Adapter (harness, `tmp/sci-probe/`)

- `src/probe/adapter.cljs` — the thinnest seam satisfying
  `seon.eval/eval`'s contract over a sci context:
  - `eval*` returns the production envelope
    (`{:seon.eval/ok? true :seon.eval/value v :seon.eval/ending-ns sym}`
    / `{:seon.eval/ok? false :seon/error {...} :seon.eval/ending-ns sym}`),
    never throws. `sci/eval-string+` with `:ns` supplies starting-ns in
    and ending-ns out natively (multi-form strings return the last
    value; `(ns …)` moves the fold — the `valid-ending-ns?` guard's job
    is done by sci itself).
  - `maybe-await-value`, `race-timeout`, `timed-out?`, `budget`,
    `defer`, `result-var-ref?`, `result-miss-message` are direct ports
    of the production owners — those functions are ENGINE-INDEPENDENT
    and a production cutover reuses the originals verbatim; the ports
    exist only so the harness has no production requires.
  - the admitted binding table is the ctx `:namespaces` map (compiled
    `seon.ai.tokens`/`seon.schema`, malli, db-shaped `^:async` host
    verbs returning Promise envelopes); authored `:seon.ns/source`
    loading flows through sci's `:load-fn` (the `guarded-load*` seam
    analog); a per-eval wall-clock deadline runs through
    `:interrupt-fn` + `sci.interrupt/interrupt!` +
    `interrupt/clojure-core`.
  - `bind-result-var!` interns a live value at `result/<id>` as one sci
    var (the sci analog of the analyzer-def + globalThis slot pair);
    the dead-reference graceful miss is re-created at the catch site.
- `src/probe/corpus.cljs` — the ported corpus: 33 deftests / 80
  assertions, each naming the production test it ports. Built by
  `build-corpus.sh` (same cljs.main `-t nodejs -O simple` shape as the
  probe), run under the vendored bun
  (`reference-code/bun/build/release/bun out/corpus.js`).

Result, 3 consecutive runs: **33 tests, 80 assertions, 0 failures,
0 errors**, corpus wall 579–589 ms.

## Coverage (honest inventory of the eval/repl behavioral corpus)

| Production namespace (tests) | Ran against sci | Inapplicable (engine-independent) | Unported |
|---|---|---|---|
| eval/result_var_test (5) | result-var-ref? matrix; ordinary form → 42; unknown `result/<id>` graceful miss; live `result/<id>` read | admission caps (`admit-result-value`) are host-side | eviction/prune cap + pending-settlement admission (host result-store code, reused as-is at cutover) |
| eval/promise_ergonomics_test (7) | defer wrapping; defer-beats-budget both orders; deadlines-belong-to-values; Promise form preserves ns | valid-ending-ns?/ns-declaration/bare-reentry/program-entry-skipped? (pure fold helpers) | — |
| eval/race_timeout_test (5) | all five contracts (timer-clear observed, sentinel identity, cancellation callback) against the port | (production fn itself is engine-independent) | — |
| eval/require_test (6) | require of admitted ns + alias call; bare require; absent ns errors naming it; transitive authored `:load-fn` load → 42; absent authored dep errors naming it | — | `ns-loaded?` (self-host guarded-load bundle-index mechanics; see divergence 3) |
| eval/auto_refer_test (7) | before: bare agent ns cannot resolve `db/query` (error names it); after: canonical-alias ns output resolves `db/query` live | augment-ns-source pure rewrite suite (4 tests — engine-independent string transform, reused verbatim) | fresh-bootstrap root :refer seeding (`init-bootstrap!`/`setup-agent-ns!` are self-host-specific; sci `:refer` works via ctx but the setup path needs its sci form at cutover) |
| eval/memory_safety_test (12) | — | all: cap-edn, admit-result-value, render-result-edn are host-side stores/renders reused unchanged | live-slot analyzer-def assertions (become sci-var assertions at cutover) |
| eval/print_capture_test (1) | sci-side seam: println inside evaluated code reaches the bound `sci/print-fn` | the ALS per-fiber dispatcher itself (host mechanism, engine-independent) | async-spanning capture through ALS with sci (B2 integration) |
| eval/prose_demote_test (1) | — | — | `prose-paren?` consults the cljs analyzer for resolving heads; needs a sci resolution query (divergence 4) |
| eval/repair_batch_test (2) | — | read-error-message (pure) | `preflight-eligible?` (compile-state macro detection → sci var-meta `:macro`; divergence 4) |
| eval/receipt_test (~8) | — | all: database-authority receipt tx shapes, no engine contact | — |
| repl_parity_test (5) | `in-ns` executes (ensure-ns! uses it live) | parity-intercept/repl-form-of/home-ns-alias-hint routing (pure) | alias/ns-unmap/ns-unalias execution not individually asserted (sci supports them natively; assert at B2) |
| handlers/test_test (1) | — | — | agent deftest-through-eval needs cljs.test in the ctx (divergence 8) |
| instrument_*_test (5 nses) | the eval-relevant seam: malli wrapper on the evaluated fn's var; errors-as-values envelope on bad input; wrapper survives (sci derefs vars per call, JIT var-epoch) | delta computation/reapply scheduling (host-side) | full delta/reapply flow over sci vars instead of globalThis slots (divergence 5) |

New engine assertions with no production counterpart: multi-form
last-value; value-def persistence; redefinition visibility; `^:async`
defn + `await` → native Promise → awaited to data; top-level `await`
rejection parity; Promise-rejection → error value; in-process tight-loop
cancellation (200 ms budget) + unswallowable interrupt; async-try
divergence probe; 200-form envelope burst timing.

## Divergence list

### Blockers

None found in the driven corpus.

### Adapter-work (fixable in the seam)

1. **Error message shape.** sci throws `Unable to resolve symbol: X` /
   `Could not find namespace: X.` (ex-data `:line`/`:column`); the
   production contract promotes analyzer warnings into a `:compile`
   envelope with the agent-facing "ran NOTHING" prose +
   `home-ns-alias-hint`. The symbol is recoverable from sci's message,
   so the seam synthesizes the same prose at the catch site. (measured)
2. **Detection mechanism inverts.** cljs.js emits `:undeclared-var`
   WARNINGS (per-fiber ALS bucket, `truly-undeclared?` promotion); sci
   throws at analysis. Observable timing is the same (a defn body
   referencing an unknown alias fails at defn-eval time — corpus test
   matches the production `before-bare-agent-ns…` expectation), but the
   warnings-ALS machinery is replaced by catch-site classification.
3. **guarded-load's host-bundled fallback does not translate.** The
   self-host trick ("bundle index misses but ns already on globalThis →
   answer with empty `:js`") is meaningless to sci; host namespaces
   must be provisioned in the ctx binding table (which IS the admission
   mechanism), and authored sources flow through `:load-fn` (transitive
   load proven). `ns-loaded?`'s bundle-index semantics retire.
4. **Analyzer-resolution queries.** `prose-paren?` and
   preflight-repair eligibility read cljs analyzer state
   (`:cljs.analyzer/namespaces`, macro presence). sci equivalents exist
   (`sci/resolve`, `ns-publics`, var meta `:macro`) but are not built.
5. **Instrumentation reapply layer.** Wrappers move from globalThis
   slot replacement to `sci/alter-var-root` on sci vars (envelope
   proven; per-call deref proven). The delta/reapply scheduler needs
   the var-handle plumbing swapped.
6. **Print capture bridge.** sci routes evaluated printing through
   `sci/print-fn` (proven); the seam must point its root at the
   existing ALS bucket dispatcher so per-fiber isolation spans awaits
   (host mechanism unchanged; integration unproven until B2).
7. **Default/setup namespaces.** sci starts in `user`, not `cljs.user`;
   `setup-agent-ns!`/`init-bootstrap!` are self-host-specific and need
   their sci form (ctx creation + home-ns `:refer` seeding — `:refer`
   from ctx namespaces works, root-refer failure semantics unproven).
8. **Agent deftest-through-eval.** `handlers/test` runs agent-authored
   `deftest` via eval; the ctx needs a cljs.test surface (sci ships a
   `clojure.test` implementation + `:ns-aliases`). Unproven; assert in
   B2.
9. **Timeout semantics differ (favorably) for sync runaways.** The
   Promise path is byte-identical (`race-timeout` unchanged;
   `::pending-promise` carry proven). But production's "form keeps
   running in background, no preemption" caveat becomes optional: the
   deadline `:interrupt-fn` actually cancels sync loops. Agent-facing
   timeout prose must be re-aligned when this ships.

### Improvements (sci behaves better)

1. **Bare value defs persist across evals** — the documented self-host
   cross-`eval-str` gap (agents steered to atoms) does not exist;
   `(def counter-probe 41)` then `counter-probe` → 41. (measured)
2. **In-process cancellation of tight CPU loops**, and a sandboxed
   `try/catch` cannot swallow the interrupt — the self-host child
   documents the opposite. 200 ms budget returned an error envelope
   promptly. (measured)
3. **The async-try-expression quirk goes the OTHER way.** In compiled
   CLJS a `try` in expression position inside a `^:async` fn becomes an
   awaited IIFE and silently unwraps Promise values (the filed quirk);
   sci's async transform is await-driven, so an awaitless try keeps its
   Promise value (`(instance? js/Promise v)` → `true` inside the fn).
   (measured)
4. **User `defmacro` works directly** (no two-pass macro-ns dance) —
   feasibility probe, re-confirmed by the corpus building on ctx state.
5. **Defs are data**: `ns-publics` → sci vars with meta gives the
   program-graph tee a direct hook (no analyzer `:defs` digest walk).

### Cosmetic

1. Error wording/location format (`:line`/`:column` in ex-data, sci
   phase framing) differs from cljs.js analysis errors.
2. `user` vs `cljs.user` default-ns naming.
3. `def`/`defn` return-value printing shapes (sci var objects) differ
   from self-host emitted vars; the envelope carries them identically.

## Perf note

- 200-form defn burst (100 defns + 100 calls) through the FULL
  envelope path (`eval*` per form, ensure-ns + eval-string+ + error
  wrapping): **37–43 ms**, vs the live self-host child's measured
  143 ms for the same 200 forms (≈3.4–3.9× faster) and raw
  `sci/eval-string*` 8.8–13.6 ms (envelope overhead ≈3×, dominated by
  per-form ns resolution and envelope construction — headroom, not a
  regression).
- Whole corpus wall: 579–589 ms for 33 tests / 80 assertions, of which
  ≈400 ms is deliberate timer waiting (80 ms delays, 20 ms timers, two
  200 ms interrupt budgets). No corpus latency exceeded its self-host
  equivalent.

## Honest limits

- The corpus ran against the harness binding table (tokens, schema,
  malli, fake db verbs), not the full admitted toolkit; B2's
  production-anchored child is where the real table, ALS integration,
  receipts, tee, and `eval-batch!` composition get exercised.
- `augment-ns-source`, `record-eval!`, receipts, and the program-graph
  tee were exercised only at their engine-facing edges (their pure
  tests are engine-independent); their integration over sci vars is
  B2/C1 work.
- The ported helpers (race-timeout, maybe-await-value, …) were tested
  as ports; at cutover the production originals are reused and the
  production tests re-run unchanged.

## B1 gate verdict

**GREEN with divergences.** 0 blockers, 9 adapter-work items (each
with a named seam mechanism), 5 improvements, 3 cosmetic. The full
ported corpus is green against the sci engine in the harness;
production is untouched.
