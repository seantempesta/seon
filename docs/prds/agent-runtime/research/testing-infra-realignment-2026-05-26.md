---
type: research
status: draft
tags: [research, agent, prd]
---

# Testing infra realignment — failure triage + direction critique (2026-05-26)

Audit of the 61 broken tests in `seon.db-test` surfaced by the Phase 1
runner ship (commit `88e7bcc`), re-evaluated against the platform's
actual direction: personal AI, multi-agent + shared substrate,
evolutionary parallel runs.

## §1 TL;DR

- **Headline triage:** 60 / 61 failures are a **single bug in the test
  runner**, not in the code under test. `run-vars` (in
  `src/seon/test/runner.cljs:313-363`) ignores `cljs.test/use-fixtures`
  entirely — it drives each var's `:test` body directly via
  `goog.getObjectByName`, never invoking the `:once` / `:each` fixture
  registries. `db_test`'s `:once :before` fixture
  (`test/seon/db_test.cljs:36-37`) registers `::name`, `::rank`,
  `::tags` Malli schemas; without it the db schema-gate
  (`src/seon/db.cljs:569-580 validate-attrs!`) correctly rejects them
  as unregistered. The bulk of the "61 failures" is one bug × N
  assertions.
- **Headline infra verdict:** The Phase 1-7 plan is roughly right for
  the single-agent unit-test path, but is **silent on the three
  load-bearing platform capabilities the user named** (substrate
  hardness against adversarial agent eval, multi-agent isolation +
  shared substrate, evolutionary parallel runs). Phase 8 is a stub.
  The plan needs a sibling track, not a reorder.
- **The fixture-skip bug also kills the substrate-hardness story.**
  If `use-fixtures` doesn't run, we can't write the "spawn an
  isolated agent ctx, fuzz `seon.eval` against it, assert no escape"
  tests that would prove adversarial-agent containment. Fix this
  first.
- **`db_test`'s `fresh-conn` model is a stronger pattern than the
  shipped runner.** It opens a fresh `:memory` Datahike per test, no
  globals. The Phase 4 `with-test-conn` macro should generalize
  this. Real per-agent isolation is just N of these in one process.
- **Top 3 actions:**
  1. Fix `run-vars` to honour `:once` + `:each` fixtures (90% of the
     "test bed" failures evaporate). ~30 LOC patch.
  2. Add a `seon.test.fixtures/with-isolated-agents` primitive
     (Phase 4 → bumped to Phase 2.5) — the multi-agent fixture is
     the same shape as Phase 4's per-test in-memory conn, just N of
     them. This is the cornerstone of every later test that exercises
     cross-agent isolation, substrate hardness, and evolutionary
     selection.
  3. Insert a "Phase 2.7 — substrate hardness suite" between the
     current Phase 2 (reactive spine) and Phase 3 (CLI). Property
     tests for `seon.fs` allowlist, `seon.eval` timeout, the
     instrumentation envelope, and "agent A's eval cannot mutate
     agent B's stash". The test infra exists to prove the platform
     is safe regardless of what an agent writes; today nothing
     enforces that, and the auto-run-on-redef story (Phase 2) is the
     ideal hook for these checks.

---

## §2 Per-failure triage table

61 events surface across 6 distinct test vars. Group rows; each row
collapses 1 .. N event records that share root cause.

| # | Var(s) | Surface | Root cause | Verdict | Notes |
|---|---|---|---|---|---|
| 1 | `prop-validate-attrs-accepts-registered+system-mix` (50 errors, all from one `dotimes 50` loop in `db_test.cljs:405-413`) | `rejected legitimate tx-data: [[:db/add 17 :seon.db-test/name "v"]]` → ex `:seon.db/unregistered-attrs`, `[:seon.db-test/name]` | Fixture `register-test-schemas!` (`db_test.cljs:31-34, 36-37`) registers `::name`/`::rank`/`::tags` in the `:once :before` slot. **The runner never invokes use-fixtures.** `run-vars` in `runner.cljs:313-363` calls `(:test (meta var))` directly via `goog.getObjectByName`, skipping `cljs.test/test-vars` and therefore both `cljs.test/each-fixtures` and `once-fixtures` wrappers. `db/validate-attrs!` (`src/seon/db.cljs:569-580`) is doing exactly the right thing — the test schemas were never registered. | **FIX-CODE (runner)** | This is **the** bug. Fixing it collapses rows 1, 2, 4 entirely and reduces 3, 5, 6. See §3 for the patch sketch. |
| 2 | `validate-attrs!-passes-when-all-registered` (1 error) | `Unregistered attributes in transaction: [:seon.db-test/name :seon.db-test/rank]` | Same root cause as row 1 — fixture not run. | **FIX-CODE (runner)** | Identical fix path. |
| 3 | `validate-values!-throws-on-bad-value` (4 fails) | `should throw`, `(= :seon.db/invalid-value …)`, `(= :seon.db-test/rank …)`, `(= "not-an-int" …)` | `validate-values!` (`src/seon/db.cljs:714+`) only validates attrs that pass `(schema/registered? attr)` (`src/seon/db.cljs:672`). With the fixture skipped, `::rank` is unregistered → validation silently no-ops → no throw → 4 assertions all fail. **The validate-values! behavior is correct** (don't validate values for attrs you have no schema for — they'll be caught by `validate-attrs!` first). | **FIX-CODE (runner)** | A real second-order check would also be nice: if `validate-attrs!` passes, `validate-values!` should be unreachable for unregistered attrs. That's already true by call order in `db.cljs:843-844`. |
| 4 | `validate-attrs!-throws-on-unregistered` (1 fail) | `(= [:seon.never-registered/whatever] (:seon.db/unregistered (ex-data ex)))` | Fixture skipped → `::name` ALSO unregistered → ex-data lists `[:seon.db-test/name :seon.never-registered/whatever]`, not the expected single-element vector. Subtle: this test PASSES the "did it throw" check but FAILS the "ex-data shape" check because of contamination from the missing fixture. | **FIX-CODE (runner)** | Once fixture runs, ex-data will be exactly `[:seon.never-registered/whatever]` as asserted. |
| 5 | `transact!-throws-synchronously-on-unregistered-attr` (2 fails) | `should throw before reaching datahike`, `(= :seon.db/unregistered-attrs …)` | Async test (`async done` wrapper). Test body: `(db/transact! {::db/tx-data [{:seon.nope/x 1}] ::db/conn conn})` should throw synchronously. The assertions are inside the `go` block AFTER the conn is opened. The fact that this REPORTS as a fail rather than an error suggests the throw happens but ex-data carries the bad payload — OR (more likely) the `db/transact!` returns the rejection inside a Promise instead of throwing. Need to read `transact!` to be sure. **Cannot confirm without a richer dump** — runner only captures `:expected`/`:actual` summaries. | **BLOCKED** | `@user` Probably one of: (a) `db/transact!` returns rather than throws because it's `^:async` (commit `ed72acb` made it so), so `(try … (catch :default e e))` catches nothing; the spec'd surface returns `{::db/ok? false ::db/error …}` instead of throwing. If true → **FIX-TEST** (assertions should expect the envelope shape, not a thrown ex-info). Test predates the async refactor. |
| 6 | `transact!-throws-synchronously-on-bad-value` (3 fails) | `string schema, int value — must throw`, `(= :seon.db/invalid-value …)`, `(= ::name …)` | Same pattern as row 5 + also depends on fixture (rests on `::name` being registered as `:string`). Two stacked root causes. | **BLOCKED + FIX-CODE (runner)** | `@user` Same async-vs-throw question as row 5. Even with the fixture fix, this remains failing if `transact!` doesn't throw synchronously anymore. |

Total: 1 runner bug (fixes 4 rows ≈ 56 events) + 2 rows blocked on
the async/throw semantics decision (≈ 5 events).

**Not a single failure indicates a real bug in `seon.db` validation
logic.** The schema-gate is doing its job correctly. The runner is
not.

---

## §3 Platform-direction lens — failures as a pattern

### Layer breakdown

- **CLJS bootstrap / load-order issues:** 0. Surprising, but real —
  the Phase 1 runner did succeed at finding + invoking every deftest
  var. The `vars-in-ns` runtime-meta walk
  (`runner.cljs:486-522`) works correctly for the ns under test.
- **Test driver / harness bugs:** 56 / 61 events (all rows except 5
  and 6). One bug — fixture-skip — accounts for the lion's share.
  This is the cljs.test surface area, not the platform-under-test.
- **Real logic bugs in `seon.db`:** 0.
- **Tests outdated by code drift (async semantics):** likely 5 / 61
  (rows 5 + 6, if the async-throw hypothesis holds). These are
  `FIX-TEST` not `FIX-CODE` — the platform shifted from
  throw-on-validation-fail to return-error-envelope at commit
  `ed72acb`; the test predates the shift.

### Single-conn assumptions

`db_test` is **already on the right side of multi-agent**: every
test calls `fresh-conn` (`db_test.cljs:57-68`) which opens a brand
new `:memory` datahike per test (`{:store {:backend :memory :id
(random-uuid)}}`). There is **no test in this file that relies on
`db/*conn*` being set globally** — every assertion routes through
the explicit `::db/conn` map key.

This is exactly the shape the Phase 4 `with-test-conn` macro is
supposed to enshrine. It's already happening informally; the macro
just sugars it. The pattern is also one step away from
`with-isolated-agents` — see §4.B.

### WIT-typed capability surface (post-WASM)

Zero tests in `seon.db-test` would need rewriting under WIT
capabilities. `db/transact!` / `db/query` are pure Clojure surfaces
that don't reach into the host. The audit's WASM-Tauri Phase 3 work
is orthogonal to this test bed. The places that WILL need rewriting
are tests of `seon.fs` and (eventually) `seon.ai.deepseek` — those
tests don't exist yet, which is itself a finding. See §4.A.

### Failures that would harden the substrate if fixed

The fixture-skip bug, once fixed, immediately enables:

1. The `seon.db` validation-gate property tests
   (`prop-validate-attrs-*`) actually exercise the gate. This is
   the only thing standing between agent-evaled tx-data and
   datahike's internals. Every regression here is a vector for an
   agent to corrupt the DB.
2. Test schemas registered via `:once` fixtures don't leak into
   other test runs — meaning we can write tests for
   `:seon.fn/test? true` discovery without polluting the agent's
   real `:seon.fn/*` registry.

Rows 5 and 6 (the throw-vs-envelope question) are the substrate
hardness story exposed: the platform changed its error contract,
and the test bed didn't notice. Phase 2's reactive spine should
have caught this; it didn't because (a) the runner was broken, and
(b) `:malli/schema` instrumentation isn't yet on agent-callable
fns (Phase 2 step 9 = unshipped).

---

## §4 Testing infra re-evaluation

Holding the §6 phase plan up against the three load-bearing
capabilities the user named.

### §4.A — Substrate hardness

**Current plan posture:** the word "hardness" appears zero times in
`cljs-testing-infrastructure-2026-05-25.md`. Phase 2.K mentions
malli-instrumenting agent-defined fns; that's necessary but not
sufficient. There is no plan for:

- Fuzz tests on `seon.eval` — given pathological CLJS forms (deeply
  nested, allocate-and-discard megabytes, throw inside `defmacro`
  expansion, redefine `cljs.core/+`), does the eval boundary
  contain the blast radius, surface the error envelope, and leave
  the pod in a usable state?
- Property tests on `seon.fs` allowlist — given a generator of
  paths including `..`, symlinks, absolute paths, paths with NUL
  bytes, paths through `~`, does the allowlist either accept (and
  the path is within the allowed root) or deny? The hardening
  shipped Phase 1 of `platform.md` and was reviewed by humans, but
  no machine check guards against regression.
- "Adversarial agent" suite — given an agent that calls
  `(js/require "node:fs")`, `(set! js/process.exit (fn []))`,
  `(reset! seon.db/!conn nil)`, what's the observable damage?
  CLAUDE.md is explicit that **the CLJS sandbox is not a security
  boundary today** but Phase 3 (WASM-Tauri) makes it one. We need
  tests that codify the current honest answer (yes, it can corrupt
  itself) AND tests that lock in the post-WASM answer (no, it can
  hit only the WIT surface).
- Instrumentation envelope regression — commit `528a539` added
  `:seon.eval/error-data` + structured renderer. There is no test
  that an arbitrary CLJS exception flowing through agent eval
  arrives as a structured `seon.error/->map` payload with the
  caused-by chain flattened (`f2bf527`-era work).

**Recommended slot:** a new **Phase 2.7 — substrate hardness
suite** sits between current Phase 2 (reactive spine) and Phase 3
(CLI). Concrete subset:

```clojure
;; test/seon/platform/eval_hardness_test.cljs
(defspec eval-boundary-contains-throws 50
  (prop/for-all [form (gen-pathological-form)]
    (let [r (await (eval/eval-batch! {:seon.agent/id :test
                                       :seon.eval/forms [form]}))]
      ;; never crashes the pod, always returns an envelope:
      (and (map? r)
           (contains? r :seon.eval/results)
           (every? #(or (:seon.eval/ok? %)
                        (some? (:seon.eval/error %))) (:seon.eval/results r))))))

;; test/seon/platform/fs_allowlist_test.cljs
(defspec fs-allowlist-rejects-traversal 100
  (prop/for-all [path (gen/one-of [(gen-traversal-path) (gen-good-path)])]
    (let [r (try (fs/read! {:seon.fs/path path}) (catch :default e e))]
      (cond
        (good? path) (string? r)
        :else        (and (instance? js/Error r)
                          (= :seon.fs/forbidden (-> r ex-data :seon.fs/error)))))))

```

Sizing: probably 1-2 days of test authoring + harness tweaks. Pays
for itself the first time a refactor accidentally regresses
`seon.fs/-normalize` or the eval timeout.

### §4.B — Multi-agent isolation + shared substrate

**Current plan posture:** §4.C describes `with-test-conn` as a
per-test single-conn fixture. The conn is shared by no one. The
multi-agent case isn't addressed; Phase 8 alludes to a `with-test-
pod` from `loop-testing-strategy.md` but no detail.

The platform direction (per `research/multi-runtime-architecture-2026-05-24.md`
§9) is **v1 = single wasm runtime, multi-agent via DB-partitioning**.
Each agent has a `:seon.agent/id`; the home-ns is the scoping
primitive. There is NO per-agent conn today — there's ONE conn and
agents are distinguished by id on every datom.

This shifts what "isolation" means for tests. There are two
meaningful flavours:

1. **Logical isolation** (v1, today): agent A and agent B share
   `db/*conn*` but every tx tags `:seon.agent/id`. A test for
   isolation asserts that an `entity-view` for agent A returns ONLY
   datoms tagged with A's id, and that an agent A eval cannot read
   agent B's home-ns stash.
2. **Physical isolation** (v2, post per-runtime): each agent has
   its own conn + its own QuickJS instance. A test asserts the
   wasmtime export boundary contains a crash.

For v1 (the right target for the test infra TODAY), we need a
fixture that creates N agent contexts in one process — not N conns.
Sketch:

```clojure
;; src/seon/test/fixtures.cljs (new — folds into Phase 4)

(defmacro with-isolated-agents
  "Bind `ids` to a vector of fresh agent ctxs in one shared conn.
   Each ctx has its own home-ns (mutated under cljs.user.test.<id>),
   its own ALS-scoped :seon.agent/id, and its own stash key prefix.
   The shared conn is :memory + has the agent-bootstrap schema."
  {:style/indent 1}
  [[ids n] & body]
  `(async done#
     (go
       (let [conn# (await (fresh-bootstrap-conn))
             ~ids (vec (for [i# (range ~n)]
                         (await (mk-agent-ctx! conn# (keyword "test" (str "a" i#))))))]
         (binding [db/*conn* conn#]
           (try ~@body
                (finally
                  (doseq [c# ~ids] (teardown-agent-ctx! c#))
                  (done#))))))))

;; usage
(deftest agent-stash-is-isolated
  (with-isolated-agents [[a b] 2]
    (eval/eval-batch! {:seon.agent/id (:seon.agent/id a)
                       :seon.eval/forms ["(def secret 42)"]})
    (let [b-eval (await (eval/eval-batch! {:seon.agent/id (:seon.agent/id b)
                                            :seon.eval/forms ["(try secret (catch :default _ :not-visible))]"}))]
      (is (= :not-visible (-> b-eval :seon.eval/results first :seon.eval/value))))))

```

The `with-shared-substrate` flavour the prompt asks about is the
same fixture, plus a second conn passed as `db/*substrate-conn*`
when the schema-runtime-unification work (`docs/prds/schema-runtime-unification`,
draft) lands. Today everything shares one conn; the substrate
split happens later. The fixture's API should be forward-compatible
— give it an `:isolated-conns?` option that's `false` today and
`true` post-split.

**Discovery routing:** add `:seon.test/isolation-mode` to the
`:malli/schema` slot on test fns, with enum `#{::single ::multi
::shared-substrate}`. The runner's `vars-in-ns` already walks meta;
adding a `(case isolation-mode …)` dispatch in `run-vars` is ~10
LOC. The default stays single. Tests that need N agents opt in.

**Why this matters NOW, not at Phase 8:** the sidecar PoC
(`pod-host/sidecar-poc/`) is GREEN with N=3 multi-agent stress over
300s, the migration plan in `RECOMMENDATION.md` is **5 days** to
swap V0 over. The moment V0 swaps, every existing test runs against
a writer-sidecar where conn semantics differ slightly (basis-t
keys, request-id dedup gap #2). If we don't have multi-agent
fixtures by then, we'll find out the hard way which tests
secretly relied on single-conn semantics.

### §4.C — Evolutionary parallel runs

The user's third capability. The prompt frames it as "N agents,
same task, score outcome." This is genuinely a different beast
from §4.A or §4.B:

- §4.A asks "is the substrate safe?" — boolean predicate per run.
- §4.B asks "do N agents not interfere?" — invariants over a
  multi-agent state.
- §4.C asks "given N agents tried, which one's strategy won?" —
  scoring function over per-agent outcomes.

The question is whether this is `run!` or a new entrypoint. My
take: **it's a new entrypoint** because the run-result shape is
different. A `::run-result` today is `{::events ::summary}`. A
genetic run is `{::variants {<agent-id> ::run-result} ::winner
<agent-id> ::score-fn-result <number>}`. Trying to overload `run!`
muddies the signal.

Sketch:

```clojure
(schema/register! ::variant-result
  [:map
   [:seon.agent/id :keyword]
   [::run-result   ::run-result]
   [::score        {:optional true} :double]])

(schema/register! ::experiment-request
  [:map
   [::task        :string]                 ; e.g. a problem statement
   [::n           [:int {:min 2 :max 32}]]
   [::score-fn    [:=> [:cat ::run-result] :double]]
   [::seed-policy [:enum ::shared ::per-variant]]])

(schema/register! ::experiment-result
  [:map
   [::variants [:vector ::variant-result]]
   [::winner   :keyword]
   [::ranking  [:vector :keyword]]])

(defn ^:async experiment!
  {:malli/schema [:=> [:cat ::experiment-request] ::experiment-result]}
  [{::keys [task n score-fn] :as req}]
  ;; spawn n agent ctxs via with-isolated-agents primitive;
  ;; each agent runs the task to completion in its own home-ns;
  ;; collect run-results; rank by score-fn; pin winner to DB
  ;; under :seon.experiment/* schema.
  ...)

```

Render: the warnings tile can show top-2 variants
(`:seon.experiment/last-winner` + `:seon.experiment/last-runner-up`)
the same way the tests tile shows last-failure. The renderer
infrastructure is already reactive.

**This is Phase 8+ in the current plan.** It should stay there,
BUT Phase 4 (`with-test-conn`) needs to be authored
`with-isolated-agents`-shaped so Phase 8 inherits the primitive
instead of reinventing it. **The §4.B fixture work directly
unblocks §4.C.** This is the strongest argument for bumping it
forward in priority.

### §4.D — What to defer or cut

Things in the current Phase 1-7 plan that look mismatched against
the realigned direction:

1. **Phase 6 (test.check integration) can be deferred behind §4.A.**
   The plan is `defspec`-on-`db_test`'s `prop-*` tests. The
   substrate-hardness suite (§4.A) is where `test.check` actually
   pays for itself — random-form fuzz tests, allowlist property
   tests. The `db_test` prop tests are 50-iteration `dotimes` and
   work fine without `defspec`. Move test.check to Phase 2.7 (the
   new hardness suite) where shrinking + reproducible seeds are
   load-bearing.
2. **Phase 7 (per-fn call graph) is gold-plating for now.** The
   Phase 2 ns-level affected-set already covers >90% of redef-runs.
   Per-fn granularity matters when the agent is iterating fast on
   one fn and doesn't want unrelated tests to re-run; but the
   actual cost of running an ns of tests is small (sub-second for
   most cases). Defer until measured pain. Reallocate the budget
   to §4.A and §4.B.
3. **Phase 4 step 22 (warnings tile reactive surface) is unchanged
   and load-bearing.** The hardness suite (§4.A) feeds it; the
   isolation tests (§4.B) feed it; the experiment results (§4.C)
   feed it. Keep this exactly as planned.
4. **Phase 5 (failed-only) — keep as-is.** Small ticket, big
   ergonomic win once tests are real.
5. **The auto-trigger handler's coupling to `:seon.fn/source`
   (Phase 2 step 11-12)** survives the sidecar split unchanged
   because the trigger fires on the LISTENER side of the writer
   sidecar, not on transact. Phase D of the PoC showed listeners
   work cleanly through the overlay. No-op.

---

## §5 Revised roadmap

New headline: **testing infra serves substrate hardness +
multi-agent isolation + evolutionary runs, in that priority order.**

Reordered + extended phases (Phase 1 is done, list the rest):

| # | Name | Why | Effort |
|---|---|---|---|
| **1.5** | **Fixture support in `run-vars`** | Without this, 60 / 61 current failures are uninformative AND every later phase that writes a test with setup is also broken. **Blocks everything.** | 2 hr |
| 2 | Reactive spine, end-to-end for ONE test | Unchanged from original Phase 2 (steps 7-13). The reactive surface is the headline. | 1 day |
| **2.5** | **Multi-agent fixture primitive** (`with-isolated-agents`) | Was Phase 4 / Phase 8 fragment. Bumped here because it's the load-bearing shape for §4.B AND §4.C, AND because the V0→sidecar migration could happen within the week (per `pod-host/sidecar-poc/RECOMMENDATION.md` day-5 plan). | 1 day |
| **2.7** | **Substrate hardness suite** | New. Property + fuzz tests on `seon.eval`, `seon.fs`, the instrumentation envelope. Pulls test.check forward from Phase 6 because shrinking is load-bearing here. Adversarial-agent suite documents the current honest sandbox boundary so the WASM-Tauri Phase 3 work has a concrete contract to satisfy. | 1-2 days |
| 3 | Suite + all-runner + CLI | Unchanged. The "before-victory" escape hatch. | ½ day |
| 4 | Fixtures + render | Trimmed: `with-test-conn` already covered by 2.5; this phase becomes purely the warnings tile / render work. | ½ day |
| 5 | Failed-only | Unchanged. | ½ day |
| 6 | test.check on `defspec` for `db_test` | Demoted (test.check primary use lives in 2.7 now). Optional — `db_test`'s `dotimes` works. | ½ day if wanted |
| 7 | Per-fn call graph | **Defer** until measured pain. Currently gold-plating. | 1-2 days, deferred |
| **8** | **Evolutionary `experiment!` entrypoint** | Was a fragment of Phase 8. Now its own headline phase. Inherits the 2.5 fixture primitive. Renderer tile + DB schema for variant scoring. | 2-3 days |

Total reordered budget for the high-priority path (1.5 → 2 → 2.5 →
2.7 → 3 → 4 → 5): ~5 days. Same total as the original Phase 2-5
budget, but covers all three platform capabilities.

---

## §6 Open questions

1. **`@user` — Does `db/transact!` throw synchronously on
   validation failure or return an envelope?** The two BLOCKED rows
   in §2 (rows 5, 6) turn on this. If it returns an envelope, those
   tests are outdated and should be rewritten to assert on the
   envelope shape; the test-vs-fn contract is currently incoherent.
   ~30 min check + 15 min test rewrite.
2. **`@user` — When does V0 swap over to the sidecar?**
   `RECOMMENDATION.md` says ~5 days. If it's "this week", Phase 2.5
   (multi-agent fixtures) becomes urgent. If it's "after the
   agent-runtime PRD lands", 2.5 can stay where it is.
3. **`@user` — Are agents adversarial or trusted?** §4.A's
   pathological-form fuzz suite is shaped differently for "the
   agent is the user's helper" (small fuzz to catch accidents) vs
   "the agent might be from an untrusted source someday" (full
   adversarial corpus). CLAUDE.md says the sandbox is currently
   NOT a security boundary, which suggests "trusted today, harden
   for tomorrow". Confirm before sizing.
4. **`@user` — Does `experiment!` belong in `seon.test.*` or its
   own ns?** Evolutionary runs aren't really testing — they're
   experimentation. A separate `seon.experiment` ns avoids overload
   on "test", but loses the shared fixture primitive convenience.
   My vote: same fixture primitive, separate ns. ~0 effort cost,
   conceptual clarity win.
5. **`@user` — The `:seon.test/isolation-mode` tag — is meta on a
   deftest the right place, or do we tag the ns (so a whole
   `multi_agent_test.cljs` file is `::multi` by default)?** Either
   works; ns-level is less ceremony for the common case but means
   you can't mix modes in one file. ~1 hr decision.
6. **`@user` — Should the substrate-hardness suite run on every
   eval (via the reactive spine) or only on `bin/seon test pod`?**
   Property tests with shrinking take seconds to minutes; the
   reactive cost would be unacceptable. Probably: tag hardness
   tests as `:seon.test/manual-only true`, exclude from the
   auto-trigger handler, run them on the CLI suite + CI only. ~10
   LOC tag check.

---

### Reference paths cited

- `src/seon/test/runner.cljs:230-256` — `resolve-test-fn` (bypasses fixtures)
- `src/seon/test/runner.cljs:313-363` — `run-vars` (the fixture-skip bug)
- `src/seon/test/runner.cljs:486-522` — `vars-in-ns`
- `test/seon/db_test.cljs:31-37` — fixture registration
- `test/seon/db_test.cljs:57-68` — `fresh-conn` (already-good per-test isolation)
- `test/seon/db_test.cljs:405-413` — the prop test that throws 50 errors
- `src/seon/db.cljs:544-580` — `system-attr?`, `validate-attrs!`
- `src/seon/db.cljs:671-673` — `validate-values!` registered-only guard
- `src/seon/db.cljs:840-844` — `transact!` validation gate call order
- `docs/prds/agent-runtime/research/cljs-testing-infrastructure-2026-05-25.md:1342-1490` — current Phase 1-8 plan
- `docs/prds/agent-runtime/research/multi-runtime-architecture-2026-05-24.md:9-40` — single-runtime + multi-agent direction
- `pod-host/sidecar-poc/RECOMMENDATION.md` — sidecar PoC GREEN, V0 migration plan
- `docs/prds/agent-runtime/platform.md:85-116` — shipped / known-gaps board
- `docs/prds/agent-runtime/phase-1-handoff-2026-05-25.md` — what just shipped + the honest "61 failures are pre-existing" note (which §2 of this doc proves is half-wrong: they ARE pre-existing, but they pre-exist because the runner can't run them)
