---
type: research
status: active
tags: [research, schema, agent]
---

# Instrumentation coverage audit — doctrine vs runtime (C44)

TL;DR — live census (default pod, pid 26102, boot 2026-07-05T16:58:56Z):
792 `:seon.fn` rows; **603 specced; 600 registered; 600 wrapped** (542
simple-path + 58 in-place); **3 never instrumented** — `seon.db/transact!`,
`seon.eval/eval`, `seon.client/mem-db` — via the STRUCTURAL async opt-out
(`async-unwrappable?`: `^:async` + non-simple-`:=>` shape ⇒ register nothing).
189 rows carry no spec at all (dominated by `*.internal` nses). The doctrine
"every `:malli/schema` fn is instrumented, no off mode" is false three ways:
the `SEON_INSTRUMENT` kill-switch IS an off mode; the async opt-out excludes
the persist boundary (`transact!`) and the eval conduit (`eval`) permanently
and deliberately; and coverage is a boot-time snapshot, not an invariant — I
observed a live window where 15 MORE core fns (`seon.client` +
`seon.agent.inspect`) were unwrapped despite a logged successful pass.
Recommendation: **(b) narrow the doctrine now** (fix CLAUDE.md), plus a
derived coverage-section follow-up; option (a) later via an observe-only
Promise-aware wrapper — never (c).

## 1. The distinct instrumentation paths (source map)

`src/seon/instrument.cljc` end-to-end gives exactly these ways a var becomes
wrapped:

1. **Boot: `instrument-from-db!`** (`instrument.cljc:509-607`) — the ONE
   boot path. Queries the program graph (`:seon.fn/sym` + `:seon.fn/spec`
   rows), resolves each live JS var, routes through `register-target!`, then
   ONE `mi/instrument! {:report ei/report-fn :skip-instrumented? true}`
   (`instrument.cljc:603`). Population: **compiled core fns** (seeded by
   `index-core!` — `client.cljs:1696`, roster = `public-fn-vars`, i.e. every
   PUBLIC first-party fn, `client.cljs:1019-1029`) **plus replayed agent
   fns** (their rows come from the eval-tee). Called at
   `client.cljs:2611` inside `start-agent!`, AFTER `boot-seed!`/replay.
   Private fns (e.g. `seon.eval/raw-eval`, `eval.cljs:1049`) are not in the
   roster and are never instrumented.
2. **Eval-tee: `instrument-tee-fns!`** (`eval.cljs:1776-1804`) — every
   newly agent-defined fn with a clean `:malli/schema` is registered via
   `seon.instrument/register-target!` and instrumented inline (filtered
   `mi/instrument!`). Population: **agent/program-graph fns defined between
   boots**.
3. **`register-target!` routing** (`instrument.cljc:452-491`) — shared by
   both paths. Three routes:
   - `async-unwrappable?` (`instrument.cljc:180-215`): `^:async` AND NOT
     (simple single-fixed-arity `:=>`) ⇒ **register NOTHING** — the
     structural opt-out. The fn's own body is the validation boundary.
   - simple fixed-arity `:=>` (sync or async) ⇒ `injecting-fschema`
     (`instrument.cljc:330-450`): injects declared-absent deps, validates
     input sync, validates output sync or on Promise resolution, records
     rejections as fault-tagged datoms.
   - sync variadic/multi-arity ⇒ raw schema form, malli's stock wrapper
     (wrap-in-place per arity slot — the census's `:wrapped-in-place`).
4. **The compile-time `collect!` macro** (`instrument.cljc:92-115`) still
   exists but has **NO call site in the pod boot path** (grep: only
   definition + docstrings; `client.cljs:2768-2773` explicitly says the
   program graph replaced it). It is dead weight on the active track.
5. **Kill-switch**: `enabled?` (`instrument.cljc:117-129`) —
   `SEON_INSTRUMENT=0/false/off/no` disables everything (boot AND tee). An
   "off mode" exists by design; the doctrine sentence "There is no off
   mode" is false as written.

Malli's wrap mechanics (`reference-code/malli/src/malli/instrument.cljs`):
`-strument!` (`:95-131`) iterates the registry, `-replace-fn` (`:77-93`)
replaces the ns-object property (simple path, stamps
`malli$instrument$original` on the wrapper) or wraps arity slots in place
(stamps `malli$instrument$instrumented?`). No filters, no silent skips —
if a var is registered and live, it gets wrapped in that pass.

## 2. Boot sequence

`client.cljs -main` → `datahike-smoke-test!` → `start-agent!`:
entity-schema tx → `seed-core!` → `index-core!` (every public fn becomes a
`:seon.fn` row; `:seon.fn/spec` present iff `:malli/schema` exists —
"honestly unspecced" otherwise) → replay (agent fns re-wrapped inline by the
tee) → `instrument-from-db!` (`client.cljs:2601-2613`) → per-agent init.
Boot log (this boot, `logs/pod.log:22`):

```
instrumentation: {:seon.instrument/registered 600, :seon.instrument/skipped 3,
 :seon.instrument/no-var 0, :seon.instrument/bad-spec 0,
 :seon.instrument/unresolvable-schema 0, :seon.instrument/enabled? true,
 :seon.instrument/n-instrumented 600}
```

Nothing re-runs `instrument-from-db!` except a later `start-agent!`
(POST /agents/new). A shadow hot-reload that re-evals a core ns replaces its
vars with FRESH (unwrapped) fns and nothing re-instruments them until the
next `start-agent!` — coverage is a snapshot, not an invariant (see §3
anomaly).

## 3. Live census (REPL, pod 7890, pid 26102)

Population = the same rows `instrument-from-db!` consumes. Census
expression (run via the shadow MCP eval, session `agent:default/root#812`;
`(.-pid js/process)` ⇒ 26102 confirmed the main pod):

```clojure
(let [db @seon.db/*conn*
      rows (seon.db/query '[:find ?sym ?spec
                            :where [?e :seon.fn/sym ?sym]
                                   [?e :seon.fn/spec ?spec]] db)
      classify
      (fn [[sym-str spec-str]]
        (let [slash  (clojure.string/index-of sym-str "/")
              ns-sym (symbol (subs sym-str 0 slash))
              fn-sym (symbol (subs sym-str (inc slash)))
              f      (seon.instrument/-find-js-var ns-sym fn-sym)
              orig   (when f (unchecked-get f "malli$instrument$original"))
              flag   (when f (unchecked-get f "malli$instrument$instrumented?"))
              real   (or orig f)
              async? (and (fn? real) (= "AsyncFunction" (.. real -constructor -name)))
              simple? (and (fn? real) (nil? (unchecked-get real "cljs$lang$maxFixedArity")))
              schema (try (cljs.reader/read-string spec-str) (catch :default _ ::bad))
              arrow? (try (= :=> (malli.core/type (malli.core/schema schema)))
                          (catch :default _ false))]
          {:sym sym-str
           :status (cond (nil? f) :no-var (some? orig) :wrapped-simple
                         flag :wrapped-in-place :else :unwrapped)
           :async? async? :simple? simple? :arrow? arrow?}))
      cs (mapv classify rows)]
  {:by-status (frequencies (map :status cs))
   :unwrapped (mapv (juxt :sym :async? :arrow? :simple?)
                    (sort-by :sym (filter #(= :unwrapped (:status %)) cs)))})
```

Output:

```clojure
{:pid 26102, :total-fn-rows 792, :specced 603, :registered 600,
 :by-status {:wrapped-simple 542, :wrapped-in-place 58, :unwrapped 3},
 :unwrapped [["seon.client/mem-db"  true false false]
             ["seon.db/transact!"   true false false]
             ["seon.eval/eval"      true false false]]}
```

Honest numbers: **603 schema'd fns, 600 wrapped, 3 unwrapped** — all three
`^:async` with `:function` (multi-arity) schemas ⇒ the `async-unwrappable?`
structural opt-out, exactly matching the boot stats `skipped 3` /
`registered 600`. Zero "registration path not run" cases in the current
main-pod state. Registry cross-check: `(malli.core/function-schemas :cljs)`
has no `seon.db transact!` / `seon.eval eval` entries (verified).

Unspecced population (189 of 792, grouped by ns — top rows): `seon.db.internal`
47, `my.plan.internal` 26, `seon.agent.fs.internal` 19,
`seon.agent.web.internal` 18, `seon.agent.search.internal` 17,
`seon.store.internal.wire-node` 13, `seon.schema.internal` 11,
`seon.agent.shell.internal` 8, `seon.web.debug` 8, `seon.web.datastar` 8.
Mostly deliberate `*.internal` surfaces; these are outside the doctrine's
"with `:malli/schema` metadata" wording but inside the "every public
function fully specs its arguments" claim.

### Anomaly: coverage is NOT monotonic (observed live)

An earlier probe sequence in this same session ran against shadow runtime
`#794` (also advertising `default/root`) and found **15 additional
unwrapped fns** — every registered entry of `seon.agent.inspect`
(`ctx-preview`, `error`, `errors`, `repro`, `turn`, `turn-diff`) and
`seon.client` (`seed-core!`, `open-agent-conn!`, `open-cluster-conn!`,
`after-reload`, `before-reload`, `datahike-smoke-test!`, `stop-heartbeat!`,
`replay-program-graph!`, `start-heartbeat!`) — all registered in malli's
registry, none wrapped, no `instrumented?` flag anywhere (so malli's
replace never ran OR the vars were re-defined after it ran). Minutes later,
on runtime `#812` (pid 26102, no second `instrumentation:` log line), the
same 15 were wrapped. A transient fork pod
(`pod-fork-default-536870986`, its own cluster + port 53104, booted
17:01:56Z, logged the same `registered 600 / skipped 3` pass, since exited)
was alive in the window; I could not conclusively pin `#794` to the main
pod vs the fork under read-only constraints.

Either way the observed fact stands: **a live runtime that had logged a
successful 600-registration pass exhibited 15 registered-but-unwrapped core
fns**. Candidate mechanisms (unresolved, worth a focused follow-up):

- a shadow-REPL/MCP session attach or hot-reload re-evaluating
  `seon.client` + `seon.agent.inspect` after the instrument pass (re-def
  replaces wrapped vars with fresh unwrapped fns; nothing re-instruments
  until the next `start-agent!`) — the observer-effect variant is that the
  eval-session plumbing itself touched exactly those nses;
- a wrap-ordering/timing effect in the fork's boot.

Nothing in the system would have noticed: there is no coverage invariant,
monitor, or derived warning.

## 4. The two C43-proven cases, explained

- **`seon.db/transact!` — permanent, structural, deliberate.**
  `db.cljs:473` — `^:async`, `:function` schema (map-in shape + two
  datahike-mimicking positional arities). `async-unwrappable?`
  (`instrument.cljc:180-215`) registers NOTHING for it; the opt-out is even
  documented at the def site (`db.cljs` comment above the schema: "Opted
  OUT of instrumentation — caught by the computed predicate"). Rationale in
  code: malli's sync wrapper would throw `:malli.core/invalid-output` on
  the returned Promise on EVERY call, and an input-only wrapper would THROW
  on a bad invocation shape — breaking the tested envelope contract
  (`db_test/transact!-returns-envelope-on-bad-invocation-shape`) and the
  never-throw-into-the-loop invariant. Validation is in the BODY
  (`assert-invocation-shape!`, attr registration + value validation before
  the tx reaches the writer) — enforcement exists, but not via
  instrumentation, and it returns an envelope rather than throwing.
- **`seon.error/parse-frames` — transient, NOT structural.**
  `error.cljs:222` — sync, simple fixed-arity `:=>` ⇒ always wrappable;
  nothing excludes it. Verified NOW: `malli$instrument$original` present
  (wrapped). C43's observation is not reproducible on the current boot.
  Most likely it was measured during a degraded window of the §3 kind (ns
  re-eval after the boot pass) or on a boot predating the
  `-original-fn`/idempotency fix (the retired once-per-process gate era).
  The two proven cases therefore have DIFFERENT causes; conflating them
  overstates the structural gap.

## 5. Consequences

- **The persist boundary is not malli-validated and never was.**
  CLAUDE.md "Database Access" — "`seon.db/transact!` enforces this at the
  boundary — unregistered or unspec'd attrs throw before the tx reaches the
  DB" — is wrong twice: enforcement is `transact!`'s own body (plus the
  wire-server), not instrumentation; and it does not throw, it returns
  `{::db/ok? false}`. The enforcement EXISTS, so no data-integrity hole —
  but any brief citing instrumentation as the mechanism is citing the wrong
  mechanism.
- **The eval conduit is not malli-validated.** `seon.eval/eval`
  (`eval.cljs:1158`, `:function` multi-arity async) is opted out;
  `seon.eval/raw-eval` is `^:private` and never in the roster at all. The
  `wrapper-fault` docstring (`instrument.cljc:283-286`) — "The instrumented
  `seon.eval` conduits (raw-eval, eval, …)" — overstates: the blame/strict-
  gate path actually rides the wrappers of INNER fns (`eval-batch!`,
  `record-eval!`, both verified wrapped), which still works, but the
  docstring should be corrected (docstrings render into agent context).
- **Non-monotonic coverage** (§3): any lane assuming "always validated"
  (error-recording laws, drive measurements reading zero malli violations
  as correctness) can silently measure an uninstrumented world after a ns
  re-eval. No invariant exists to surface it.
- `seon.client/mem-db` (scratch-world helper) shares the structural
  opt-out — low consequence.
- The JVM/wire-server `.clj` lane has its own separate machinery
  (`seon.dev.instrumentation` + `mi/collect!` in `seon.ai.gemini`); the
  pod doctrine sentence doesn't describe it either — out of scope here.

## 6. Options for the owner (not implemented)

- **(a) Make the doctrine true.** Build the Promise-aware
  variadic/multi-arity wrapper — `async-unwrappable?`'s own docstring says
  the rule "collapses to false" once it exists — closing all 3 opt-outs.
  The hard part is not the wrapper but the CONTRACT: `transact!`/`eval` pin
  never-throw envelope behavior, so their instrumentation must be
  observe-only (validate → `error/record!` datom → still return the
  envelope). That posture can stay STRUCTURAL ("async + `:function` schema
  ⇒ observe-only wrapper") — no hand list. Cost: wrapper engineering +
  per-call validation on the two hottest fns (measure; malli validators
  are cheap but `transact!` is on every write). Also requires fixing
  non-monotonicity (re-instrument after hot-reload, or accept the window).
- **(b) Narrow the doctrine to what is real** (recommended now). Rewrite
  CLAUDE.md "Function Instrumentation" + "Database Access": program-graph-
  driven at boot (`instrument-from-db!`) + inline tee; structural async
  opt-out (currently `transact!`, `eval`, `mem-db` — enforced by their own
  bodies, envelope-returning); `SEON_INSTRUMENT` kill-switch exists;
  `*.internal` fns are deliberately unspecced. Zero behavior change,
  stops docs lying to every agent that reads them.
- **(c) Hybrid allowlist** — a registered "instrument these" list is a
  hand-maintained name set, directly against the no-hand-lists rule.
  Reject.

**Recommendation:** (b) immediately, plus two queued follow-ups: (1) a
derived coverage section (reactive-context style — a section fn that walks
specced `:seon.fn` rows and surfaces any whose live var lacks a wrapper;
renders only when non-empty, self-healing, would have caught both the C43
parse-frames state and the §3 window); (2) the observe-only Promise-aware
wrapper as a later owner-gated unit, converting (b) back toward (a)
structurally. Fix the `wrapper-fault` docstring and delete the dead
`collect!` macro in the same doc-truth pass.
