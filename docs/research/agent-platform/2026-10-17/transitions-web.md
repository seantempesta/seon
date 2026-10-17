---
title: "Transitions: web research on state machines, flow and reconcilers"
type: research
status: "web research by lane transitions-web (Sonnet), 2026-10-17, for task-model.md §5 (the gap table). No code was run on default."
---

# Transitions: what the wider landscape offers the reconciler (2026-10-17)

**Question.** The task model (`task-model.md` §1, §5) wants one pure function `(desired, observed) -> minimal actions`,
deterministic, idempotent and exhaustive over every state combination. The owner asked whether an FSM, a statechart or
a core.async flow is the best home for it. A separate Opus lane compares core.match, core.logic, statecharts and a plain
decision table with a REPL prototype; this note covers the wider landscape. Claims marked *(probe)* were run on a
throwaway JVM; the rest are from the cited pages. Search snippets that could not be confirmed on the primary page are
marked *(unconfirmed)*.

## 1. Summary

1. The task graph is already the "parallel states" of a statechart: many tasks, each a small lifecycle, advancing
   independently. A reconciler pass is a `map` over tasks, so the library never needs parallel or hierarchical states.
   What it needs is **one task's gap row**: a total function from a small tuple of enums to a set of actions.
2. Every Clojure FSM library is built to *run* a machine (hold a current state, receive events). The reconciler holds no
   current state and receives no events: it is level-triggered over facts that already live in Datahike. Using a library
   adds a second state holder beside the database, the thing the owner's rulings forbid.
3. None of the Clojure options checks completeness for you. The techniques that do (DMN gap/overlap, Erlang's
   "every state x event pair" discipline, Kubernetes' level-triggering, XState path generation) are small, and all of
   them reduce to **enumerate the product of the axes and assert each cell has exactly one answer**.
4. `core.async.flow` should stay the runtime and the on/off switch (§8.1); the decision logic is a pure table that its `db<->flow` proc evaluates, not a flow-internal machine and not a second FSM runtime.

## 2. Comparison

| Option | Data-first? | Exhaustiveness | Parallel / hierarchy | Maturity | Fit for a pure idempotent reconciler over facts |
|---|---|---|---|---|---|
| [fulcrologic/statecharts](https://github.com/fulcrologic/statecharts/blob/main/README.adoc) | Yes: a chart is a nested map; executable content is data | None documented: the README mentions no validation, lint or coverage tooling | Full SCXML: compound, parallel, final, history | MIT; README says "Production quality and API stable"; the 1.0.0 [cljdoc page](https://cljdoc.org/d/com.fulcrologic/statecharts/1.0.0) still says alpha *(sources disagree)* | Poor. A session holds working memory and consumes events; the model is event-driven. Heavy for one decision per task |
| [clj-statecharts](https://github.com/lucywang000/clj-statecharts) | Yes (XState-style maps) | None documented | Hierarchical and parallel, guards, delayed transitions | EPL, copyright 2020-2021, 0.1.2 on [Clojars](https://clojars.org/clj-statecharts/versions/0.1.2); last activity not visible on the page. fulcrologic's README cites missing history support and the license as reasons not to use it | Poor, same reason |
| [metosin/tilakone](https://github.com/metosin/tilakone) | Yes: plain maps, no macros, serializable | Only "undeclared state transfers are errors"; no coverage check | None mentioned | **Deprecated** (`metosin-deprecated`), 0.0.4, 145 lines | Poor: deprecated; tiny, but still an event-fed machine |
| [reduce-fsm](https://github.com/cdorrat/reduce-fsm) | No: macros (`defsm`, `defsm-inc`, `defsm-seq`); matching through core.match | None documented; examples end in `_` catch-alls | Flat | 0.1.4, no GitHub releases, activity date not visible; Graphviz via `fsm/show-fsm` | Poor: macro-defined, accumulator-shaped |
| Malli-based state machine | No such library found (searches turned up none). Malli supplies the *axes*: `[:enum ...]` states, `m/children`, `malli.generator` | Only what we build | Whatever we build | Malli is already the contract owner | Best fit as the axes' schema, not as a machine |
| [telsos-lib `enum-case`](https://cljdoc.org/d/com.github.kongra/telsos-lib/0.1.561/api/telsos.lib.enums) (macro over a Malli `:enum`) | Yes: reads the registered `:enum` | **Macro-expansion-time**: every enum value must be handled; no default for `enum-case`; `check-exhaustiveness` returns `{:missing :extra}` | One axis only | Small personal library, `com.github.kongra` *(unconfirmed maintenance)* | Idea worth copying (a few lines over `m/children`), not worth depending on |
| `clojure.core.match` | Patterns are code | **None**: *(probe)* core.match 1.1.0 on Clojure 1.12.1 compiled a two-row `match` over `[state event]` with no warning; an uncovered pair threw `IllegalArgumentException: No matching clause: :a :z` at run time. `case` behaves the same | n/a | Contrib, stable | Loud only at run time; the gap is found by the call that hits it |
| Plain decision table (a map from enum tuple to actions) + product check | Yes: the table is EDN, readable by agents | By construction if the check enumerates the product of the axes | None needed: one row per task, a `map` over tasks | No dependency | Best fit; see §6 |
| `core.async.flow` | Graph is data (`create-flow`, `datafy`) | None for states | Parallel by construction (processes), no hierarchy | Contrib, alpha line `1.10.874-alpha3` pinned in `reference-code/core.async` | Wrong layer; see §3 |
| Erlang `gen_statem` | Callback modules | Convention only: "handle every combination of event and state", catch-alls for the rest | Flat state plus data | Decades in production | Idea source, not a dependency |
| XState + `@xstate/graph` | Machine config is JSON-like | Path generation gives *state* or *transition* coverage on request | Hierarchical and parallel | Very mature (JavaScript) | Idea source (§5) |
| TLA+ / Alloy | Specs, not data | Exhaustive within a model (TLC) or a scope (Alloy) | n/a | Mature | Idea source; heavy tooling for a table this small |

## 3. `core.async.flow` as the FSM

What the dependency says (read at the pinned `reference-code/core.async`, `doc/flow-guide.md`, and the
[published guide](https://clojure.github.io/core.async/flow-guide.html)):

- A step-fn has four arities: describe, `init`, `transition` and `transform`. The transition arity
  `(state transition) -> state'` is "called any time the flow or process undergoes a lifecycle transition
  (`::flow/start`, `::flow/stop`, `::flow/pause`, `::flow/resume`)". The doc says to use it "to coordinate the creation,
  pausing, and shutdown of external resources".
- The lifecycle exposed is process-level and fixed: `start`, `stop`, `pause`, `resume`, `pause-proc`, `resume-proc`,
  `ping`, `ping-proc`. A flow starts resumed. There are no user-declared states and no transition table; a
  proc's own states are whatever its state map holds.
- A [Clojurians thread](https://clojurians-log.clojureverse.org/core-async/2026-07-02) asks how to tell running from
  paused from stopped and how to see an in-progress transition. The visible answer pings and treats "flow not running"
  as stopped; there is no first-class status query *(thread read through a search snippet only)*.

Judgement:

- **Good for** what the reconciler's actions *do*: a graph per agent, the `transition` arity to tear down an agent's
  resources at `::flow/stop`, `ping` for the observed fact "this graph is running". That is exactly how
  `seon-flow-architecture` already uses it.
- **Bad for** holding the reconciler's *logic inside a proc's channel network* (calling a pure function from the `db<->flow` pass is what §8 recommends). A flow proc is an event consumer holding private state in a channel network. The
  reconciler must be a pure function over database values that can be called from a test with two EDN maps. Putting
  it in a proc makes it unreachable without a running graph, hides its inputs in channel history (the opposite of
  level-triggered), and adds a second place where "which tasks should run" lives. Flow's own lifecycle also has no
  vocabulary for "held", "ready", "blocked on a need", "merge conflict".
- A flow *can* be set up as an FSM (a proc whose state map holds the state and whose `transform` switches on it), but
  that is a hand-written event-driven machine with no more completeness help than `case`.

## 4. Reconciler and controller patterns elsewhere

| System | Model | How completeness / convergence is handled | What Seon should take |
|---|---|---|---|
| [Kubernetes controllers](https://kubernetes.io/docs/concepts/architecture/controller/) | Objects carry a `spec` (desired) and report current state; "each controller tries to move the current cluster state closer to the desired state"; the page also says it "doesn't matter if the overall state is stable or not" as long as controllers keep working | Level-triggered: the pass reads current state, not an event, so a missed event is repaired by the next pass. (The page itself does not use the words "level-triggered" or "idempotent"; those are the standard reading, not a quote.) | Seon's design already matches: facts on main are the level, a commit wakes the next pass. Keep the rule that the pass never reads an event queue |
| [Terraform plan/apply](https://developer.hashicorp.com/terraform/tutorials/1-0/refresh) | `plan` computes actions from configuration vs refreshed state and prints them; `apply` executes; an apply on a converged state reports no changes | The plan is *data you can look at before anything runs*; a second run on converged state is empty | Make `reconcile` return the plan as data (a vector of action maps) and let each action's actor execute it. "Run twice, answers nothing" becomes a one-line test |
| [Erlang `gen_statem`](https://www.erlang.org/doc/system/statem.html) | State plus data; two callback modes | The doc's aim is to "handle every combination of event and state", enforced only by convention and catch-alls. Its advice to keep the value that decides which events are handled in the *state* and not in *data*, or postponed events may never be retried | The classification step: whatever the table branches on must be in the key, not buried in a map value |
| [XState model-based testing](https://stately.ai/docs/xstate-graph) | `getShortestPaths` (Dijkstra) gives one path per reachable *state*; `getSimplePaths` (DFS, non-cyclic) enumerates all simple paths and covers every *transition*; infinite context needs `stopWhen`/`limit` | Coverage is generated from the machine, not hand-picked; state coverage alone misses transitions | When tasks gain a lifecycle across several passes, generate the observed-state sequences from the table and assert the plan at each step (§6 test 3) |
| DMN decision tables ([Calvanese et al., BPM 2016, arXiv 1603.07466](https://arxiv.org/pdf/1603.07466.pdf); [Sparx validation](https://sparxsystems.com/enterprise_architect_user_guide/16.0/dmn_decisiontable_validation.htm)) | Rules over input columns | Two checks: **gap** (an input matches no rule) and **overlap** (an input matches two rules under a unique hit policy). The paper treats rules as hyper-rectangles, so overlap is intersection and gaps are the universe minus the union. Enumerated input domains make the table checkable | The most directly transferable idea. With enum axes the check is simply the cross product |
| TLA+ (TLC), Alloy ([Jackson on the small scope hypothesis](https://people.csail.mit.edu/dnj/talks/zb2003/zb2003.noanim.pdf); [Alloy meets TLA+](https://arxiv.org/pdf/1603.03599)) | Specs checked by exhaustive search; TLC unbounded in depth, Alloy bounded by scope | "A high proportion of bugs can be found by testing a program for all test inputs within some small scope" | The reconciler's input space is small and finite, so *full* enumeration is possible, which is stronger than the small-scope bet. No tool needed |
| Clojure stateful property tests ([stateful-check](https://github.com/MichaelDrogalis/stateful-check), [check.statem](https://github.com/jeffh/check.statem)) | Generate command sequences against a model, run against the system | Finds sequence bugs; check.statem notes the difficulty of keeping generated commands valid | Optional later layer for multi-pass convergence (a plan applied then re-planned is empty); not needed for the table itself |

## 5. Exhaustiveness techniques usable from Clojure

1. **Enumerate the product of Malli enums.** An axis is a registered `[:enum ...]` schema; `malli.core/children` lists its
   values; `clojure.math.combinatorics/cartesian-product` (already in `~/.m2` as 0.2.0, not verified as a Seon
   dependency) or a `for` makes the full set of cells. Cost is the product of the axis sizes; at six axes of 3 to 5
   values this is a few thousand cells, evaluated in milliseconds. This is the DMN completeness check done literally.
2. **test.check / `malli.generator` over state schemas.** Useful when an axis is not an enum (a count, a time). For
   enum axes, generation is strictly weaker than enumeration (it samples what could be listed). Keep generators for the
   *action* side: `mg/generate` of a legal observed-state value, then assert the plan validates against the plan schema
   and the second pass is empty.
3. **core.match.** *(probe)* No exhaustiveness warning at compile time; a missing clause is a runtime
   `IllegalArgumentException`. It is loud, but only for the one input that reaches it. An `:else` branch silences even
   that, and under the "no guards or fences" law a default row is a fence.
4. **Compile-time enum macros.** telsos-lib's `enum-case` shows the shape: a `case`-like macro that reads the Malli
   `:enum` at expansion time and refuses a missing value. The same idea works over a table: a macro, or just a test,
   that compares the table's keys to the product of the axes and returns `{:missing ... :extra ...}`.
5. **Decision-table gap and overlap detection.** In a map keyed by the full tuple, overlap is impossible (keys are
   unique) and a gap is a missing key. Where a row should cover several cells (`:any` on one axis), expand it into cells
   first, then the check is: every cell has exactly one expanded row.

## 6. Ideas to adopt

1. **A decision table keyed by a classified tuple, with the product check as a deftest.** Source: DMN completeness
   ([Calvanese et al.](https://arxiv.org/pdf/1603.07466.pdf)) plus gen_statem's "state, not data" advice.
   - A pure `classify` maps one task's desired and observed facts to a tuple of enums, each registered with Malli
     (for example, needs, execution, environment, graph, attempt end, test result, merge/conflict).
   - The gap table is EDN: `{tuple -> [actions]}`, where an action is a map whose schema is registered, so it is
     checked where produced and where consumed.
   - The proof is one deftest over `(apply cartesian-product (map m/children axes))`: every cell has exactly one row,
     no extra key, every action validates. A new enum value makes that test red until the owner decides the row; there is
     no default.
2. **Plan as data, apply separately (Terraform).** `reconcile` returns a vector of action maps and nothing else.
   Properties: deterministic; `(reconcile s)` after applying its plan to `s` is empty; independent tasks produce
   independent actions, so parallelism is a `mapcat`. This is the idempotence test, and it needs no mocks.
3. **Generate the sequences, not just the cells (XState `getSimplePaths`).** The table covers one pass. For the
   lifecycle across passes, define the effect of each action on observed state (the writer's fact), walk the simple
   paths from "just written, held" to "done or bailed", and assert at each step that the plan is exactly the expected
   action and that no cell is unreachable-but-present (a dead row). This gives transition coverage and finds rows
   nobody can reach.

Not adopted: a statechart library (second state holder, event-driven), flow as the reconciler, core.match as the
decision structure (silent at compile time), TLA+/Alloy (the space is small enough to enumerate directly).

## 7. Recommendation

Judged against the owner's goals (states in data and validated by schemas; every permutation accounted for and loud
when one is not; as simple as possible):

**Keep core.async.flow as the runtime and `db<->flow` as the only evaluator; put the transitions in a plain decision
table over Malli-enum axes, with the cross-product completeness test; adopt no FSM or statechart library (at most
tilakone's data shape, §8).** The reconciler is a pure function that the `db<->flow` pass calls; flow's pause and
resume, with the proc's prime at `::flow/resume`, are the reactive on/off switch (§8.1). Flow runs the pass; it does
not hold the decision logic.

- *States in data, validated by schemas:* each axis is a registered `[:enum ...]`; the table is EDN; every action has a
  registered schema. Nothing is a macro or a closure.
- *Every permutation accounted for, loud when one is not:* the completeness deftest enumerates all cells and reports
  `{:missing :extra}`. At run time, a tuple with no row returns a typed error map (the errors law), never a default
  branch. Both sides fail on the first change that adds an enum value.
- *As simple as possible:* no dependency, no second state holder, one `map` over tasks gives the parallelism the
  statechart libraries sell, and the plan-as-data return makes idempotence a one-line property.
- *Flow is the runtime, not the logic:* the reconciler's actions start, stop and ping graphs; the `transition` arity cleans up the
  graph's resources.

Open point for the Opus lane to settle with its prototype: whether 5 to 7 axes, once classified, still give a table the
owner can read, or whether the axes must be grouped into two tables joined by one `classify` (for example, "is it
ready" and "what is its attempt doing"). That is the only place a hierarchy would reappear, and it is a nesting of
tables, not a statechart.

## 8. Owner follow-ups (2026-10-17): keep flow as the runtime

Two directions came in while this note was being written: reuse flow's lifecycle as the reactive on/off switch, and
consider a flow step-fn whose state map holds an FSM described by a library. Both change the recommendation's
framing, not its core: the **table stays data, the runtime stays flow**.

### 8.1 What flow's pause, resume and stop guarantee (read at `reference-code/core.async`, `flow/impl.clj`, `doc/flow-guide.md`)

- **Procs start paused.** The `start` docstring says "The processes start paused. Call `resume` or `resume-proc`".
  Each proc's loop begins at `status :paused` and, while paused, blocks on its control channel only
  (`(async/<!! control)`): it reads no input, so a paused proc consumes no wake and runs no pass.
- **Commands are broadcast, asynchronous and unacknowledged.** `pause`, `resume`, `pause-proc`, `resume-proc` and `stop`
  each `send-command` a map onto one control channel; a `mult` fans it to every proc's tap (buffer 10). The call returns
  without waiting for any proc. The way to observe the result is `ping` or `ping-proc`, which reply with
  `::flow/status` (`:paused`, `:running`) and `::flow/state` (from the proc's `:ping-map-fn`) for procs that answer
  within `timeout-ms` (default 1000).
- **Commands take effect between messages, never inside one.** A running proc reads `[control casts input...]` with
  `alts!! ... :priority true`, so a pending command wins the next read, but an in-progress `transform` runs to
  completion first; `send-outputs` also checks for commands between outputs. Pause is "after the current step", not
  an interrupt.
- **`transition` is called exactly when status changes.** `handle-transition` calls the step-fn's transition arity
  only if the old and new status differ, mapping `:exit`, `:running` and `:paused` to `::flow/stop`, `::flow/resume`
  and `::flow/pause`. A repeated `resume` on a running proc calls nothing. A proc that throws in `transition` has the
  exception put on `:error-chan` and keeps its previous status and state, so the command is lost with a report.
- **Stop ends procs and closes the report and error channels; the flow can be started again** (docstring).
- **What is not guaranteed:** no ordering between procs, no synchronous "all paused" barrier, no statuses beyond
  `:paused`, `:running` and `:exit`, and no first-class "pausing" in-flight status (the Clojurians question in §3).

Seon already uses this: `db<->flow` primes itself at `::flow/resume` (an `offer!` of `:seon.flow/plan-prime` into its own
sliding in-port), unregisters its listener at `::flow/stop`, and its pass derives from facts, so a pause/resume cycle
re-primes exactly as the source comment in `seon.cluster.agent` says. That is the owner's reactive switch with no new
mechanism: **pause the `db<->flow` proc and nothing reconciles; resume it and the prime makes the first pass read all
current facts.** It works only because the pass is level-triggered (§4); an event-sourced pass would lose whatever
arrived while paused. (Wakes during a pause coalesce in the sliding-1 in-port, which is safe for the same reason.)

### 8.2 Description without runtime: which libraries can supply only the table?

The test is whether the library lets us (a) write states, events and transitions as data, (b) validate or check
them, (c) evaluate one transition as a pure function of `(machine-state, event)` with no store, queue or session.

| Library | Data format worth adopting | Evaluation without its runtime | Validation / exhaustiveness | Verdict |
|---|---|---|---|---|
| tilakone | Yes, the cleanest: each state a map `{::tk/name ... ::tk/transitions [{::tk/on ... ::tk/to ... ::tk/actions [...]}]}`, actions as keywords, the interpreter function supplied separately | `tilakone.core/apply-signal` takes an FSM map and a signal and returns the FSM (pure; the FSM value is the proc state) | "Undeclared state transfers are errors"; no coverage check | Deprecated, 145 lines: adopt the *shape*, not the dependency. It is exactly the owner's picture |
| fulcrologic/statecharts | Chart as nested map (compound, parallel, history) | `start!` and `process-event!` return the next working memory (plain EDN, serializable), but take an `env` carrying data model, execution model, event queue and working-memory store; purity needs in-memory components, which the guide does not document as a supported pure mode | `configuration-problems` (checks an active configuration against the machine), used by `testing/goto-configuration!`; element constructors validate their parameters | Right only if one proc needs real hierarchy or parallel regions. `testing/new-testing-env`, `run-events!`, `in?` are a model for our own sequence test, not a coverage generator |
| clj-statecharts | Yes (XState-style maps) | Own service/scheduler runtime; whether it exposes a pure transition function was not confirmed | None documented | No gain over tilakone's shape; EPL, 2020-21 |
| reduce-fsm | No: macros | Machines are generated functions, not values | None | Not usable as a description |
| Malli | Yes: `[:enum ...]` axes and a schema for the table and for each action | Not an interpreter, by design | `m/validator`, `m/explain`, `m/children`, `malli.generator` | The validation layer for whichever shape is chosen |

None of them supplies the exhaustiveness check; every one leaves it to the user. The exhaustiveness value is
**ours to add in a deftest** (§5). The only transferable value from the libraries is the data shape.

### 8.3 Statecharts and FSMs inside core.async processes: prior art

- The classic pattern is a `go-loop` whose loop variable is the state, with `case` on it
  ([Zorched, "Using A Core.Async Routine As A State Machine"](https://www.zorched.net/2014/05/18/using-a-core-async-routine-as-a-state-machine/)).
  Flow generalizes it: the loop is flow's, the loop variable is the step-fn's state map, and `transition` replaces
  ad hoc control messages.
- fulcrologic/statecharts ships `event-queue.core-async-event-loop/run-event-loop!` (its guide): a core.async loop that
  *drives* a chart. That is the chart as runtime with core.async as its pump, the reverse of what we want.
- No published example of a statechart inside a `core.async.flow` step-fn turned up. The pairing below is therefore
  untried outside Seon and should be proved by the Opus lane's prototype before adoption.

### 8.4 Cleanest pairing

For a proc whose own state is an FSM (a graph's start/stop sequence, an agent's turn loop):

1. The **state map holds `{:fsm/state <enum>, ...data}`**; the machine is a separate constant (EDN in tilakone's shape,
   `{state {event {:to state :actions [...]}}}`) registered under a Malli schema over the enums.
2. `transform` does `(get-in machine [(:fsm/state s) event])`; a missing entry returns the typed error map, never a
   default. `transition` maps `::flow/pause`, `::flow/resume` and `::flow/stop` onto events of the same machine where the
   proc's own lifecycle matters.
3. `:ping-map-fn` returns `{:fsm/state ...}`, so the state is visible through `flow/ping`: that is how the reconciler's
   observed "graph running" is read.
4. The completeness deftest is the cross product of the machine's states and events (§5.1), plus path generation if
   lifecycle sequences matter (§6.3).

For the cluster reconciler (many tasks, one pass) there is **no per-task FSM state**: the table keys on the classified
tuple and the `db<->flow` pass is its only evaluator (§7). Both uses share the table shape and the test.

## 9. Sources

- fulcrologic/statecharts: https://github.com/fulcrologic/statecharts/blob/main/README.adoc , https://cljdoc.org/d/com.fulcrologic/statecharts/1.0.0
- clj-statecharts: https://github.com/lucywang000/clj-statecharts , https://clojars.org/clj-statecharts/versions/0.1.2
- tilakone: https://github.com/metosin/tilakone
- reduce-fsm: https://github.com/cdorrat/reduce-fsm
- telsos-lib enums: https://cljdoc.org/d/com.github.kongra/telsos-lib/0.1.561/api/telsos.lib.enums
- core.async.flow: `reference-code/core.async/doc/flow-guide.md`; https://clojure.github.io/core.async/flow-guide.html ; https://clojurians-log.clojureverse.org/core-async/2026-07-02
- Kubernetes controllers: https://kubernetes.io/docs/concepts/architecture/controller/
- Terraform refresh/plan: https://developer.hashicorp.com/terraform/tutorials/1-0/refresh
- Erlang gen_statem: https://www.erlang.org/doc/system/statem.html
- XState graph: https://stately.ai/docs/xstate-graph
- DMN: https://arxiv.org/pdf/1603.07466.pdf , https://sparxsystems.com/enterprise_architect_user_guide/16.0/dmn_decisiontable_validation.htm
- Alloy / TLA+: https://people.csail.mit.edu/dnj/talks/zb2003/zb2003.noanim.pdf , https://arxiv.org/pdf/1603.03599
- Stateful Clojure testing: https://github.com/MichaelDrogalis/stateful-check , https://github.com/jeffh/check.statem
