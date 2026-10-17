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
4. `core.async.flow` is the wrong place for the reconciler and the right place for what the reconciler *starts*.

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
- **Bad for** the reconciler itself. A flow proc is an event consumer holding private state in a channel network. The
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

**Use a plain decision table over Malli-enum axes, with the cross-product completeness test, and do not adopt an FSM or
statechart library or put the reconciler in a flow.**

- *States in data, validated by schemas:* each axis is a registered `[:enum ...]`; the table is EDN; every action has a
  registered schema. Nothing is a macro or a closure.
- *Every permutation accounted for, loud when one is not:* the completeness deftest enumerates all cells and reports
  `{:missing :extra}`. At run time, a tuple with no row returns a typed error map (the errors law), never a default
  branch. Both sides fail on the first change that adds an enum value.
- *As simple as possible:* no dependency, no second state holder, one `map` over tasks gives the parallelism the
  statechart libraries sell, and the plan-as-data return makes idempotence a one-line property.
- *Flow stays where it is:* the reconciler's actions start, stop and ping graphs; the `transition` arity cleans up the
  graph's resources.

Open point for the Opus lane to settle with its prototype: whether 5 to 7 axes, once classified, still give a table the
owner can read, or whether the axes must be grouped into two tables joined by one `classify` (for example, "is it
ready" and "what is its attempt doing"). That is the only place a hierarchy would reappear, and it is a nesting of
tables, not a statechart.

## 8. Sources

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
