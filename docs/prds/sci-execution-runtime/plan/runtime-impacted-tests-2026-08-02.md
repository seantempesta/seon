---
type: prd
status: active
tags: [prd, testing, program-graph, sci, flow]
---

# Runtime impacted-test derivation — design (2026-08-02)

The owner's charter, verbatim intent: *"at runtime, when a function is
created or modified, calculate all the functions involved with that
function (we may need to improve our function parsing and db storage) so
we can query and run generative and unit tests whenever we change a
function, and limit the time to do so. Yes we will need test.check and
other testing in the main system. Ideally we don't need to run a separate
JVM to do testing."*

Four requirements fall out, and this document answers them in order: derive
the blast radius from facts; bind tests to the functions they exercise;
run in this JVM under a real time bound; generate properties from the
contracts we already persist.

## 0. Verdict up front

- **The impact derivation is cheap, selective, and already queryable — for
  BUILD-TIME rows.** 2,091 `:seon.fn/calls` edges over 1,605 functions;
  the full reverse-closure computation over every function is 31 ms, and
  the closure is *selective*: median 5, p90 42, max 249 (probe P3). A
  changed function's blast radius is a five-function set half the time.
  This is the strongest evidence in the document: impacted-set derivation
  is not a research problem here, it is a Datalog query we can afford on
  every install.
- **RUNTIME rows carry NO call edges at all. This is finding #1 and it is
  real.** Falsified directly, not inferred (probe P2).
- **Tests bind to nothing.** Zero `:seon.test` rows carry call edges, and
  the naming convention that would be the fallback covers only 49 of 82
  test namespaces (probe P4). There is no test-to-function traceability in
  the system today, by any mechanism.
- **`test.check` is not on the default classpath yet.** Ruling #36
  promoted it; the promotion has not landed (`deps.edn:15-50` default
  `:deps` has `metosin/malli` and no `org.clojure/test.check`; test.check
  appears only in `:test` extra-deps at `deps.edn:89` and `:writer-test`
  at `deps.edn:166`). Malli's generator namespace is inert without it.

## 1. Findings — grounded in source and live probes

### 1.1 Finding #1 — runtime installs cannot produce call edges

Two producers write `:seon.fn` rows, and only one of them attaches calls.

**Build time** (`src/seon/fn.clj`): `call-targets-by-caller`
(`fn.clj:210-226`) folds clj-kondo's `::analyzer/var-usages` into
`{caller #{target}}`, admitting an edge only when the usage carries an
`::analyzer/arity` and BOTH endpoints are first-party functions
(`first-party-function-symbols`, `fn.clj:198-207`). `var-row`
(`fn.clj:258-261`) attaches the sorted result as
`:seon.fn/calls` lookup refs.

**But `:seon.fn/calls` is not an owned attribute of the `:seon.fn/sym`
shape** (`src/seon/program.cljc:21-27`). `canonical-row`
(`program.cljc:335-357`) selects exactly `:seon.program/owned-attributes`,
so it *drops* calls. The build path survives only because `fn.clj/artifact`
re-attaches it outside the shape immediately after canonicalization:

```clojure
;; src/seon/fn.clj:297-302
(mapv (fn [row]
        (cond-> (program/canonical-row row)
          (seq (:seon.fn/calls row))
          (assoc :seon.fn/calls (:seon.fn/calls row))))
      rows)
```

That escape hatch is the whole reason the build graph has edges — and it
is exactly what the runtime producer lacks.

**Runtime** (`src/seon/cluster/run.cljc:643-733`, `row-tx`, and
`src/seon/sci/eval.clj:426-450`, `row`): both call
`(program/declaration-row row :contracted)`, which canonicalizes and
therefore strips calls. Probe P2 falsified this at the pure function —
`:seon.fn/calls` supplied explicitly, absent from the returned row:

```clojure
(seon.program/declaration-row
  {:seon.fn/sym "my.demo/f" ... :seon.fn/calls [[:seon.fn/sym "my.demo/g"]]}
  :contracted)
;=> #:seon.fn{:sym "my.demo/f", :ns [...], :source "(defn f [x] (g x))",
;             :arglists "([x])", :private? false, :spec "..."}
```

So an agent authoring a function through the door produces a row that is
a *graph island*. Impact analysis over agent-authored code is impossible
today. **The owner explicitly authorizes the fix at the one producer
("we may need to improve our function parsing and db storage").**

### 1.2 The runtime deriver already exists, applied to the wrong rows

`src/seon/sci/eval.clj:539-559` already computes, per evaluated form,
`unproven-called-vars` — "Non-builtin Vars occurring in call position",
resolved through `sci/resolve` against the live ctx, plus
`resolved-form-vars` (`eval.clj:521-537`) for every mentioned Var. Both
are attached to `:seon.def` session rows
(`changed-session-defs`, `eval.clj:600-632`) — and the contracted function
is explicitly *removed* from that path (`eval.clj:608`), so the one row
that most needs edges is the one row that never gets them.

This is the good news: the runtime call-resolution mechanism is built,
read against SCI's own resolve, and already carries the correct fail-closed
posture in its docstring. The fix is to route its output onto the
`:seon.fn/sym` row, not to build a second analyzer.

### 1.3 Finding #2 — no test-to-function traceability

`:seon.test` rows own exactly `sym`, `ns`, `source`
(`program.cljc:33-37`; `resources/seon/schema/test.edn:3-10`). `var-row`
returns tests through a `cond` arm that attaches no calls
(`fn.clj:237-240`) — a test is never even a candidate for edges. Probe P4:
**0 of 689 tests carry calls**. The naming-convention fallback
(`seon.foo` ↔ `seon.foo-test`) pairs 49 of 82 test namespaces; 33 are
unpaired, including whole behavioral families (`seon.cluster.turn-test`,
`seon.blob-settlement-test`, `seon.schema.admission-test`). A convention
that misses 40% of the suite is not a selection mechanism.

### 1.4 What exists to steal, and what must not be reused

- **`script/seon/dev/changed_test.clj`** — the dev-side selector. Its
  selection is *file → clj-kondo namespace graph → one of two coarse
  boundaries → `bin/test` as a SEPARATE PROCESS* (subprocess launch,
  process-tree kill, log files: `changed_test.clj:290-400`). It also
  carries hand lists (`operator-path?`/`writer-path?`,
  `changed_test.clj:167-177`: literal `"src/seon/db/"`, `"src/seon/embed"`,
  `"bin/test"` prefixes) — a standing violation of the computed-rules law,
  filed below. **Nothing in it transfers to runtime**: wrong granularity
  (namespace, not function), wrong input (files, not facts), wrong
  execution model (a second JVM, which the owner forbids). Its one durable
  lesson is the widening/fallback discipline: when the analysis is
  uncertain, widen to the complete gate and say why.
- **`src/seon/test/runner.clj`** — the in-system runner. `run!`
  (`runner.clj:81-107`) binds `clojure.test/report`, runs
  `test/run-tests` over NAMESPACE SYMBOLS, and returns per-test values;
  `record-tx` (`runner.clj:123-174`) turns them into run/result/failure
  facts. This is the right sink and it is reusable as-is for the fact
  half. Two limits: it selects by namespace, not by test symbol, and it
  runs host `clojure.test` vars, not SCI-interpreted agent tests.
- **`fn.clj`'s incremental-change classifier** (`fn.clj:487-535`) already
  treats any cardinality-many/ref change — which includes
  `:seon.fn/calls` — as `:component-or-cardinality-many-change`, forcing a
  complete rebuild. Build-time edges are therefore never stale; the cost
  is that every call-graph change rebuilds from scratch. Runtime cannot
  use that hammer, which is why the shape fix below matters.

## 2. Web research — adopt/reject

| Idea (source) | What it is | Verdict for Seon |
|---|---|---|
| Reverse transitive closure over a static dependency graph (STARTS, [Legunsen et al.](https://www.cs.cornell.edu/~legunsen/pubs/LegunsenETAL17STARTS.pdf)) | Build a type/class dependency graph; impacted tests are those reaching a changed node | **ADOPT, at finer grain.** STARTS works at *class* level because Java gives it nothing finer. We have per-function `:seon.fn/calls` facts, so our closure is a function-level closure — strictly more precise, and measured at median 5 (P3). |
| Dynamic file-dependency collection (Ekstazi, [Gligoric et al.](https://users.ece.utexas.edu/~gligoric/papers/GligoricETAL15Ekstazi.pdf)) | Record at runtime which files each test actually touched; select on that | **REJECT as the primary mechanism, ADOPT as a later refinement.** Requires instrumenting a full run to bootstrap, and its state is stored-derived. Our equivalent — recording observed callees during a test run as facts — is a legitimate *second-generation* precision boost once the static closure is live, and it is the only thing that closes the dynamic-dispatch holes in §3.3. Queued, not slice 1. |
| Static RTS is *unsafe* under reflection ([Shi et al., OOPSLA 2019](https://lingming.cs.illinois.edu/publications/oopsla2019.pdf); reflection was the only observed cause of unsafety, and reflection-aware fixes cost up to 85.8% of retest-all) | Static edges miss reflective calls | **ADOPT THE LESSON, REJECT THE CURE.** Their fix costs nearly the whole suite. Ours is cheaper because our uncertainty is *nameable*: `resolve`, `var-get`, multimethod dispatch, and higher-order passing are all visible in the AST we already parse. §3.3 makes each one a declared widening reason rather than a silent miss. |
| Predictive (ML) test selection ([Facebook engineering, 2018](https://engineering.fb.com/2018/11/21/developer-tools/predictive-test-selection/): 99.9% of regressions caught while running a third of transitively-dependent tests) | Learn which tests are worth running from historical failures | **REJECT for now, note the precondition.** It needs a corpus of runs with outcomes. We are *building* that corpus (`:seon.test.result` rows already exist, `test.edn:33-47`). Revisit when the history is real; adopting a model before the data exists is cargo cult. |
| Batching beats selection on missed failures ([Cheng et al. / EMSE scale study](https://mcislab.github.io/publications/2025/emse_scale.pdf): selection cut feedback time up to 96% but missed up to 55% of failures; batching cut 99% and missed none) | Run everything, but batched | **REJECT as the model, ADOPT as the fence.** Their finding is a warning that selection alone is not a gate. Hence §5: the impacted run is *fast feedback for the agent*, and `bin/test` remains the gate. We never claim the impacted run proves the tree. |
| "Prioritize fast tests that recently failed" ([Cheng et al., 2024](https://mir.cs.illinois.edu/marinov/publications/ChengETAL24LongRTP.pdf)) | Simple heuristics beat sophisticated prioritization | **ADOPT as the ordering rule** when the budget cannot cover the impacted set: order by recorded duration ascending, failures first. Both facts come from `:seon.test.result` rows — derived, not stored heuristics. |
| Schema-derived generators (Jsongen; [PBT from business-rule models, SoSyM 2017](https://link.springer.com/article/10.1007/s10270-017-0647-0)) | Generate QuickCheck generators from a data schema | **ADOPT — already implemented upstream.** `malli.generator/function-checker` (`reference-code/malli/src/malli/generator.cljc:526-556`) takes a `:=>` schema, generates from `:input`, applies the fn, validates against `:output` and any `:guard`, and shrinks. That IS the free property. We write no generator code. |
| Type-targeted testing ([Seidel et al.](https://arxiv.org/pdf/1410.5370)) | Refinement types drive input generation to the interesting region | **ADOPT the framing** for §6's ranking: our named predicate schemas are refinements, and a function whose input schema is a narrow named predicate deserves more trials than one taking `:int`. Not slice 1. |
| Kaocha's load → **test-plan (a value)** → run split (`reference-code/kaocha/src/kaocha/testable.clj:74-228`) | The plan is data, inspectable before execution | **ADOPT the concept, REJECT the runner** (two-surfaces law). Our impacted set must be a *value* the agent and the debug page can see before anything runs. |
| clj-reload's `dependees` + `transitive-closure` (`reference-code/clj-reload/src/clj_reload/parse.clj:122-148`) | Invert the requires graph, expand from starts | **ADOPT the shape verbatim** — it is a nine-line inversion and a queue loop, and it is exactly what P3 measures. It is a *namespace*-level implementation of the same idea; ours is the function-level analogue over facts. No new dependency: we do not need clj-reload, we need its 20 lines of algorithm, which are the obvious ones. |

No library needs vendoring for this design. `test.check`, `malli`,
`clj-kondo`, and `kaocha`/`clj-reload` (concept-only) are all already in
`reference-code/`.

## 3. The impact derivation

### 3.1 The one query

Impacted functions of a changed set `S` = the reverse transitive closure
of `S` over `:seon.fn/calls`, inclusive of `S`. Because
`:seon.fn/calls` is a ref set, the inversion is a plain Datalog join —
no second index, no stored reverse edges (`derive, don't store`):

```clojure
[:find ?caller-sym
 :in $ ?target-sym
 :where [?target :seon.fn/sym ?target-sym]
        [?caller :seon.fn/calls ?target]
        [?caller :seon.fn/sym ?caller-sym]]
```

iterated to fixpoint by the queue loop clj-reload uses. Measured: the
*entire* graph's closures compute in 31 ms; one function's closure is
microseconds. **This is affordable on every install and needs no cache.**
If it ever stops being affordable, the answer is a per-turn memo of the
one `callers` map (an invocation-local value), never a stored reverse
index.

### 3.2 The fix at the one producer (finding #1)

Three changes, all at existing owners, no new namespace:

1. **`:seon.fn/calls` joins `:seon.program/owned-attributes` for the
   `:seon.fn/sym` shape** (`program.cljc:21-27`). This is the honest
   model — the attribute IS owned by the row — and it deletes the
   `fn.clj:297-302` escape hatch. Consequence checked against the
   dependency: `exact-replacement-tx` (`program.cljc:420-434`) emits
   `[:db/retract e a]` for non-component changed attributes, and Datahike
   treats a value-less retract as "retract every datom matching `[e a]`"
   (`reference-code/datahike/src/datahike/db/transaction.cljc:1059-1070`),
   so cardinality-many edges are correctly replaced, not accumulated. No
   bridge change is required.
2. **The runtime producer attaches edges.** `seon.sci.eval`'s
   `unproven-called-vars` (`eval.clj:539-559`) is already the correct
   resolver; route it onto the contracted-function row in `row`
   (`eval.clj:426-450`) instead of only onto `:seon.def` rows,
   filtered to symbols that have a `:seon.fn/sym` row (the same
   first-party admission the build path applies at `fn.clj:220-223`).
   Symbols with no row are NOT dropped silently — they are the
   `:unresolved-callee` widening reason in §3.3.
3. **Tests get edges too.** The `:seon.test/sym` shape gains
   `:seon.fn/calls`, and `var-row`'s test arm (`fn.clj:237-240`) attaches
   the same clj-kondo-derived set it already computes for functions. A
   test is a function that calls things; the call graph does not care that
   `deftest` expanded it. **This is the whole of test-to-function
   traceability — no hand mapping, no registry, no annotation.**

### 3.3 What static edges cannot see, and the posture for each

Stated honestly, because §2's OOPSLA result is that the unseen edge is
the entire failure mode of static selection.

| Construct | Why the edge is invisible | Posture |
|---|---|---|
| `(apply f args)`, `(map f xs)`, any Var passed as a value | The callee is a value, not a call-position symbol | **Conservative include.** `resolved-form-vars` (`eval.clj:521-537`) already over-approximates *every* mentioned Var; use it as the runtime edge source rather than call-position-only. The docstring's own reasoning applies: over-approximation can only widen. |
| `comp`/`partial`/threading into a Var | Same — mention without call position | Covered by the same over-approximation. |
| Multimethod dispatch | `defmethod` bodies are reached through `defmulti`, and clj-kondo records the `defmulti` usage, not the method | **Declared hole.** Treat a changed `defmethod` as changing its `defmulti`, and the `defmulti`'s closure as the impact. Requires the `defmulti`/`defmethod` relation as a fact; NOT slice 1 — filed. |
| Java interop / `requiring-resolve` / `resolve` | No first-party symbol exists to point at | **Widening reason.** A changed function whose AST contains `resolve`, `requiring-resolve`, `eval`, or `var-get` is marked `:unbounded-impact` and its run widens to the complete gate, with the reason named — the `changed_test.clj` widening discipline, kept. |
| Protocol/interface implementations | Extension is reached through the protocol Var | Same as multimethods; filed with them. |
| Data-driven dispatch (a symbol in a map, resolved later) | Genuinely undecidable statically | **Widening reason,** and the honest place for §2's dynamic-collection refinement later. |

The governing rule: **a hole is a named widening reason, never a silent
miss.** An impacted set is a value carrying both its members and its
`:seon.impact/widening` reasons; a set with reasons is not presented as
complete.

## 4. The run

### 4.1 In-process, and why that is now possible

The owner's requirement ("ideally we don't need to run a separate JVM")
is satisfiable because the eval already happens here: the agent's `defn`
runs in this JVM's SCI ctx, and `seon.test.runner/run!` is ordinary
`clojure.test` in this JVM. `changed_test.clj`'s subprocess model exists
for the *dev* loop, where the checkout's classpath may have changed; the
runtime loop has no such problem — the code under test is a fact and the
interpreter is already loaded.

### 4.2 A proc, never the turn

**Recommendation: a flow proc, not synchronous in the turn.** The
`seon-flow-architecture` answer is the existing shape — a per-cluster
plumbing graph alongside the render pipeline and fault committer:

- the install seam (the one that commits the `:seon.fn` row) emits the
  changed identity onto a **fixed** buffer (backpressure: we must not drop
  a change, and a burst of installs is exactly when we care);
- an **`:io`?** — no: **`:compute`** proc. It runs interpreted code and
  validators; it must never block. Capability-touching tests are the
  §5 hazard and are excluded there, not scheduled around;
- results are committed through `seon.test.runner/record-tx` — the
  existing sink, existing facts;
- the agent sees the outcome the way it sees everything else: as a
  rendered block derived from `:seon.test.result` rows on its next turn.
  Nothing is pushed at it, nothing blocks it.

A turn that blocks on a test suite is the "JavaScript event loop inside
Clojure" shape the architecture rejects, and it would make every agent
edit pay the worst-case closure's cost synchronously.

### 4.3 The time bound — derived, not a constant

`test.check`'s `quick-check` is a plain loop with **no deadline and no
abort hook** (`reference-code/test.check/src/main/clojure/clojure/test/check.cljc:199-231`);
`reporter-fn` is called after each trial but cannot stop the run. So
bounding is two mechanisms, both already ours:

1. **The budget is spent as trial counts, not wall clock.** One config
   fact — the impacted-run budget, in the same defaults-document family as
   every other dial — is divided across the impacted set: each function's
   `=>iterations` is the budget share scaled by the set's size, floored at
   a minimum that makes a run meaningful and capped at a knee measured the
   way ruling #26's caps were (measured, not guessed). A 5-function
   closure gets deep generative coverage; a 249-function closure gets
   shallow coverage of everything plus the §2 ordering rule (fast,
   recently-failed first) so the truncation is principled. `:max-size`
   scales the same way. **No bare timeout constant appears anywhere.**
2. **The backstop is the door's own `time-limit`.** Interpreted test and
   property bodies run under the ONE `:interrupt-fn`, which fires on every
   `fn` body entrance and stops an eval uncatchably
   (`reference-code/sci/doc/interrupt.md`). A property that spins is
   already stopped by the mechanism we have; the budget above is the
   *scheduling* dial, and the interrupt is the *loud last-resort* backstop
   whose firing is itself a bug report. Compiled host `clojure.test` vars
   have no such guard — a further reason the impacted runner's first
   citizens are interpreted agent tests, and the compiled gate stays
   `bin/test`.

Time is therefore bounded by construction (a finite trial count) and
guarded by an existing uncatchable stop, with no clock standing in for an
observable event.

## 5. Safety — what the tests run against

**A test must never run against the live cluster branch.** Tests transact;
the agent's own facts are in that branch; a property that shrinks over a
`db` argument would mutate the record it is grading.

**Recommendation: the grading-fork pattern, generalized** — already ruled
and partly built (`plan/grader-in-fact-space-2026-08-01.md`; branch fork
measured at ~17 ms; ruling #36 makes goal grading exactly this). The
impacted run:

1. forks the cluster's current commit into a scratch branch;
2. `acquire!`s the interpreted corpus from that fork's rows — so the
   function under test is the *just-installed* version, from facts, with
   no file and no reload;
3. runs the impacted tests and properties there;
4. commits only the `:seon.test.run` / `:seon.test.result` /
   `:seon.test.failure` rows back to the cluster branch, and discards the
   fork.

Fork lifecycle is the known open question (`grader-in-fact-space`, open
question 4: store growth under many forks). The impacted run makes it
*routine* rather than per-eval-run, so branch retirement must land with
this — a fork per install, never retired, is a store leak. Filed.

**Crash walk.** The JVM dies mid-impacted-run. Nothing re-executes
(architecture crash model): the scratch fork is unreferenced garbage that
branch retirement collects on the next start; no `:seon.test.run` row was
committed, so the impacted run simply did not happen; the function's
`:seon.fn` row is already durable because it committed at the install
seam, *before* the proc was fed. The correct recovery is "the agent's next
turn sees no result rows for that revision" — indistinguishable from "not
run yet", which is the truth. No dangling state, no repair path needed.

**Capability-touching tests are excluded from the automatic run.** A test
whose closure reaches a capability leaf (derivable today from
`:seon.fn/workload` `:io` metadata and the effect-door call sites) does
real fs/web/llm/db work; running it automatically on every install would
make an agent's edit spend money and touch the world. Such tests are
listed in the impacted set, marked, and NOT run — the agent or the gate
runs them deliberately. This exclusion is *derived from the call graph*,
not a hand list.

## 6. Generative properties from contracts

Ruling #33's parsed contract facts make this nearly free. For every
contracted function the row already carries `:seon.fn/spec` plus the
parsed `:seon.fn/arities` and `:seon.fn/ast` components with
`:seon.schema` refs (`program.cljc:310-332`, `with-contract-facts`).

**The free property, per contracted function:** generate from the input
schema, apply, validate the output. We do not implement it —
`malli.generator/function-checker` is exactly this, including `:guard`
support and shrinking (`reference-code/malli/src/malli/generator.cljc:526-551`),
with `::mg/=>iterations` as the trial dial the §4.3 budget drives.
`malli.generator/check` (`:558-562`) wraps it into an `explain` value —
errors as values, no exception at the boundary. This is why the
no-`:any` schema law pays off here: a contract with `:any` in it
generates garbage, so **the law that already forbids `:any` is what makes
this mechanism work at all.** That is worth saying out loud to agents.

What the free property does NOT prove, and where hand-written tests earn
their place:

- **relations between calls** — idempotency, round-trips, "transact then
  query returns it". A single-call output check cannot see these.
  Generative round-trips over schemas are the standing totality
  properties the testing law already names.
- **specific known-bad inputs** — the regression that pins a fixed defect
  class.
- **effects and facts** — that the right datom was committed. Output
  validation is blind to it.

So the run is: free contract properties for every impacted contracted
function, plus every impacted `:seon.test` row. Both feed the same runner
and the same result facts.

## 7. Slices

**Slice 1 (bounded, named): make the graph whole and the impacted set
queryable — no running.**

Owner: `seon.program` + `seon.sci.eval` + `seon.fn` (three existing
files, one mechanism).

1. `:seon.fn/calls` becomes an owned attribute of the `:seon.fn/sym`
   shape; delete the `fn.clj:297-302` escape hatch.
2. `:seon.fn/calls` is added to the `:seon.test/sym` shape and attached by
   `var-row`'s test arm, so build-time tests carry edges.
3. The runtime install seam attaches edges from `resolved-form-vars`,
   filtered to symbols owning a `:seon.fn/sym` row; unresolved callees
   become `:seon.impact/widening` reasons on the row's install receipt.
4. One pure `impacted-set` function over a database value: changed
   symbols → closure + reaching tests + widening reasons, returned as one
   namespaced value.

**Acceptance (falsifiable, live):** an agent installs `f` through the
door, then installs `g` calling `f`; querying `f`'s reverse closure
returns `g`. Today that query returns nothing — probe P2 is the
before-picture, and re-running it must show the edge. Plus: build-time
test rows carry edges, and `impacted-set` on a changed core function
returns a set whose size matches the P3 distribution.

Slice 1 deliberately runs no test. It is the smallest thing that makes
the owner's first clause ("calculate all the functions involved") true,
and every later slice is unimplementable without it.

**Slice 2** — the `:compute` proc, the derived budget, the grading fork,
results as facts, the capability exclusion.
**Slice 3** — free contract properties via `malli.generator/check`,
budget-scaled `=>iterations`, the ordering rule.
**Slice 4** — `defmulti`/`defmethod` and protocol relations as facts,
closing the §3.3 dispatch holes.

## 8. Open owner questions (recommendation first)

1. **Does the impacted run gate the agent, or only inform it?**
   *Recommendation: inform.* The agent sees results as rendered facts on
   its next turn and decides. Gating an agent's own edit on a test run
   makes the door synchronous and re-imports the blocking shape the
   architecture rejects; §2's batching study also warns that selection
   alone should never be treated as a gate. `bin/test` stays the gate.
2. **Does an over-approximated edge (a Var merely mentioned, never called)
   belong in `:seon.fn/calls`, or in a separate attribute?**
   *Recommendation: one attribute, over-approximated.* Two attributes
   means two graphs and a decision at every consumer. The precision cost
   is a slightly wider impacted set, which §0's distribution shows we can
   afford. If a consumer later genuinely needs "called for sure", that is
   the moment to split — not before.
3. **Fork per install, or one reused scratch branch per cluster?**
   *Recommendation: fork per install, with retirement landing in the same
   slice.* A reused branch accumulates test residue and makes runs
   order-dependent — the flakiness class §2's flaky-test literature
   describes. But this is a real store-growth commitment and it is
   the open question `grader-in-fact-space` already flagged; the owner
   should rule before slice 2 builds on it.
4. **Should `test.check` promotion to default deps land now?** *Yes* —
   ruling #36 already decided it and the promotion simply has not been
   made (§0). `malli.generator` is inert without it, so slice 3 is blocked
   on a one-line `deps.edn` edit.

## 9. Issues filed by this design

- **Runtime `:seon.fn` rows carry no call edges** (finding #1) — root
  cause `program.cljc:21-27` + `fn.clj:297-302`; fixed by slice 1.
- **No test-to-function traceability** (finding #2) — 0/689 tests with
  edges; convention covers 49/82 namespaces; fixed by slice 1.
- **Ruling #36's `test.check` promotion has not landed** — `deps.edn`
  default `:deps` lacks it.
- **`changed_test.clj` hand lists** (`changed_test.clj:167-177`) —
  literal path prefixes select the writer/operator boundary, violating
  the computed-rules law. Independent of this design; recorded here
  because the audit found it.
- **Fork retirement is unbuilt** and becomes load-bearing at slice 2.

## Probes

`tmp/impacted-tests/probes.clj` holds the four read-only forms behind
every number above (P1 census, P2 the finding-#1 falsifier, P3 the
closure distribution, P4 traceability), with their recorded results
against cluster `default` on 2026-08-02. No probe writes: no transact, no
install, no fork.

## Sources

- [STARTS: STAtic Regression Test Selection](https://www.cs.cornell.edu/~legunsen/pubs/LegunsenETAL17STARTS.pdf)
- [Practical Regression Test Selection with Dynamic File Dependencies (Ekstazi)](https://users.ece.utexas.edu/~gligoric/papers/GligoricETAL15Ekstazi.pdf)
- [Reflection-aware static regression test selection (OOPSLA 2019)](https://lingming.cs.illinois.edu/publications/oopsla2019.pdf)
- [Predictive test selection (Facebook Engineering, 2018)](https://engineering.fb.com/2018/11/21/developer-tools/predictive-test-selection/)
- [Contrasting Test Selection, Prioritization, and Batching](https://mcislab.github.io/publications/2025/emse_scale.pdf)
- [Revisiting Test-Case Prioritization on Long-Running Test Suites](https://mir.cs.illinois.edu/marinov/publications/ChengETAL24LongRTP.pdf)
- [Property-based testing of web services by deriving properties from business-rule models](https://link.springer.com/article/10.1007/s10270-017-0647-0)
- [Type Targeted Testing](https://arxiv.org/pdf/1410.5370)
- [Test flakiness' causes, detection, impact and responses: a multivocal review](https://www.sciencedirect.com/science/article/pii/S0164121223002327)
