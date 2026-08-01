---
type: research
status: active
tags: [research, agent, database, schema, render]
---

# Agent entity graph audit — what a recursive walk can actually reach

Audit date 2026-07-31. Read-only. Live evidence from the running `default`
cluster (pid 35516, basis `:max-tx` 536870924, 121 `:seon.ns` rows, 1367
`:seon.fn` rows, 622 `:seon.test` rows, 559 `:seon.schema` rows, 1 agent
`root`, 0 messages, 0 runs).

The owner's target — agent context derived by a recursive walk over the
agent's ENTITY — is **already implemented** as `seon.render.walk/neighborhood`
(`src/seon/render/walk.clj:328-423`), driven from
`seon.render.agent/namespace-ai` (`src/seon/render/agent.clj:362-407`) as the
`:namespace` block. This audit is therefore a reach audit of the existing
mechanism, not a design-from-zero.

## 1. Executive findings

1. **The walk starts at the AGENT entity, not the namespace**, despite the
   block being named `:namespace` and the ruling being phrased
   "render(its namespace, distance N)". Lookup is
   `[:seon.cluster.agent/id agent-id]` (`src/seon/render/agent.clj:399-400`).
   At distance 1 that reaches the namespace ROW and nothing inside it.
2. **Reverse traversal needs no attribute list.** `reverse-refs` runs one
   generic `[?source ?attribute ?target]` datalog clause
   (`src/seon/render/walk.clj:221-224`), so every reverse relation is found by
   computation, not enumeration. §4 lists them for the reader, not for the code.
3. **`:seon.ns/requires` is `[:set :symbol]`, NOT a ref**
   (`resources/seon/schema/program.edn:42`). The single most important edge in
   the owner's phrasing — "walk the agent's namespace then its requires" —
   **does not exist as a graph edge today**. Measured: 967 require symbols over
   121 namespaces (avg 7.99, max 28), of which only 493 (avg 4.07, max 19)
   name a namespace that has a `:seon.ns` row.
4. **The agent's own namespace row is a stub.** `creation-tx`
   (`src/seon/cluster/agent.clj:99-104`) writes only `:seon.ns/name`. Live
   check: 120 of 121 namespaces carry `:seon.ns/source`; the one that does not
   is `my.agents.root`. Because `:seon.ns/source` is REQUIRED in the
   `:seon.ns/ns` entity map (`resources/seon/schema/program.edn:49-57`), the
   agent's namespace matches no registered family, so `walk/projection` falls
   through to the floor and renders raw datoms. This is the exact node the
   design wants to be the centre of context.
5. **`:seon.schema` rows are graph-isolated.** No ref attribute anywhere points
   at a schema entity (live enumeration of `:db.type/ref` attributes, §3), and
   `:seon.fn/spec` is a `:string`. A walk can never reach a contract.
6. **Render caching must be cluster-global.** Measured redundancy WITHIN a
   single distance-3 walk from one namespace: 703 rendered nodes over 148
   distinct entities = **4.75×**. Across agents the overlap is larger still,
   because every agent's requires converge on the same corpus namespaces.
7. **Blocks and transaction entities are deliberately excluded** as
   "apparatus" (`src/seon/render/walk.clj:253-287`), by attribute presence
   (`:seon.render.block/name`, `:db/txInstant`), not by a list. Consequence:
   `:seon.db/trigger`, `:seon.db/user`, `:seon.db/process` provenance is
   unreachable by the walk (this is why `:trigger` survives as a hand-written
   block — `src/seon/render/agent.clj:41-50`, `src/seon/context.clj:212-245`).
8. **There is no agent memory family.** `rg memory resources/seon/schema/`
   returns only prose. Durable agent recall today is: messages, run/receipt
   rows, and the wait-note in a settled receipt's `result-edn`.

## 2. The entity graph (text diagram)

Legend: `-->` forward ref carried by the source; `<--` reverse ref (the walk
must traverse it backwards); `[C]` component; `{apparatus}` excluded by
`walk/apparatus?`; `(sym)` a symbol/string, NOT a ref edge.

```
                              AGENT  (:seon.cluster.agent/id, identity)
                                |
 d1  --> :seon.cluster.agent/namespace ------------> NS   (card-one, UNIQUE-VALUE)
 d1  --> :seon.cluster.agent/run ------------------> RUN  (card-one, optional = idle)
 d1  --> :seon.cluster.agent/blocks [C] ----------> {BLOCK} apparatus, NOT followed
 d1  <-- :seon.cluster.run/agent ------------------- RUN
 d1  <-- :seon.cluster.message/to ------------------ MESSAGE   (the wake attribute)
 d1  <-- :seon.cluster.message/from ---------------- MESSAGE
 d1  <-- :seon.error/agent ------------------------- ERROR FACT
     <-- (tx) :seon.db/user ----------------------- {TX}    apparatus

 NS  (:seon.ns/name, identity, symbol)
 d2  --> :seon.ns/aliases  [C] --------------------> NS.ALIAS  {local, target-ns}
 d2  --> :seon.ns/imports  [C] --------------------> NS.IMPORT {local, target-class}
 d2  --> :seon.ns/refers   [C] --------------------> NS.REFER  {local, target-ns, target-name}
 d2      :seon.ns/requires (SET OF SYMBOLS)  ......... NO EDGE — see §5
 d2  <-- :seon.fn/ns ------------------------------- FN     (fan-out: avg 12.3, max 104)
 d2  <-- :seon.test/ns ----------------------------- TEST
 d2  <-- :seon.cluster.eval/ns --------------------- RECEIPT (parse-time ns of an eval)
 d2  <-- :seon.cluster.run.form/ns ----------------- PLAN FORM
 d2  <-- :seon.cluster.agent/namespace ------------- AGENT (at most one — unique-value)

 RUN (:seon.cluster.run/id, identity)
 d2  --> :seon.cluster.run/agent ------------------> AGENT (cycle; dedupe-by target wins)
 d2  --> :seon.cluster.run/forms [C] --------------> PLAN FORM
 d2  <-- :seon.cluster.run.form/run ---------------- PLAN FORM (same set, other direction)
 d2  <-- :seon.cluster.eval/run -------------------- RECEIPT
 d2  <-- :seon.error/run --------------------------- ERROR FACT
 d2  <-- :seon.context.capture/run ----------------- CONTEXT CAPTURE
 d2  <-- :seon.ai.attempt/run ---------------------- PROVIDER ATTEMPT

 FN  (:seon.fn/sym, identity, string)
 d3  --> :seon.fn/ns ------------------------------> NS
 d3  --> :seon.fn/calls (SET OF REFS) -------------> FN (callees; 1742 edges / 715 fns)
 d3  <-- :seon.fn/calls ---------------------------- FN (callers)
         :seon.fn/spec (STRING) ...................... NO EDGE to :seon.schema

 MESSAGE (:seon.cluster.message/id, identity)
 d2  --> :seon.cluster.message/to ----------------> AGENT
 d2  --> :seon.cluster.message/from --------------> AGENT
 d2  --> :seon.cluster.message/about -------------> ERROR FACT | RECEIPT (problem id)

 CAPTURE (:seon.context.capture/id)
 d3  --> :seon.context.capture/contributions [C] --> CONTRIBUTION

 ATTEMPT (:seon.ai.attempt/id)
 d3  --> :seon.ai.attempt/error ------------------> ERROR FACT
 d3  --> :seon.ai.attempt/failover-from ----------> ATTEMPT

 SCHEMA (:seon.schema/key) — 559 rows, ZERO inbound or outbound refs. UNREACHABLE.
```

**Depths that matter, from the agent:**

| Depth | What is reached |
|---|---|
| 0 | the agent row: id, run-presence (idle vs running) |
| 1 | its namespace ROW (name only), its open run, every message to/from it, error facts attributed to it |
| 2 | namespace bindings (aliases/imports/refers), **its functions**, its tests, the run's plan forms, receipts, captures, attempts, error facts |
| 3 | **called/calling functions**, contribution rows, the run behind each receipt, peer agents through a shared message |
| 4+ | second-order call graph; the walk's per-path visited set is the only cycle guard |

## 3. Attribute inventory

Live enumeration of every installed `:db.type/ref` attribute on the `default`
cluster (`(->> (keys (:schema db)) (filter keyword?) (filter #(= :db.type/ref (:db/valueType ...))))`):

```
:seon.ai.attempt/error          :seon.ai.attempt/failover-from  :seon.ai.attempt/run
:seon.cluster.agent/blocks      :seon.cluster.agent/namespace   :seon.cluster.agent/run
:seon.cluster.eval/ns           :seon.cluster.eval/run
:seon.cluster.message/about     :seon.cluster.message/from      :seon.cluster.message/to
:seon.cluster.run/agent         :seon.cluster.run/forms
:seon.cluster.run.form/ns       :seon.cluster.run.form/run
:seon.context.capture/contributions  :seon.context.capture/run
:seon.db/process  :seon.db/trigger  :seon.db/user
:seon.error/agent  :seon.error/run
:seon.fn/calls  :seon.fn/ns
:seon.ns/aliases  :seon.ns/imports  :seon.ns/refers
:seon.test/ns  :seon.test.result/failure  :seon.test.result/run  :seon.test.result/test
```

### 3.1 Attributes on or pointing at the agent

| Attribute | Malli form | Card | Ref target | Direction | Declared | Written |
|---|---|---|---|---|---|---|
| `:seon.cluster.agent/id` | `[:string {:min 1 :seon.db/identity true}]` | one | — | on agent | `resources/seon/schema/run.edn:1` | `src/seon/cluster/agent.clj:101` |
| `:seon.cluster.agent/namespace` | `[:and {:seon.db/unique true} :seon.db/ref]` | one | `:seon.ns` | agent → ns | `resources/seon/schema/agent.edn:5-6` | `src/seon/cluster/agent.clj:99-102` |
| `:seon.cluster.agent/run` | `:seon.db/ref` | one | run | agent → run | `resources/seon/schema/run.edn:2` | assert `src/seon/cluster/run.cljc:262`; retract `:368`, `:841`, `:912` |
| `:seon.cluster.agent/blocks` | `[:set {:seon.db/component true} :seon.db/ref]` | many | block | agent → block | `resources/seon/schema/block.edn:86` | `src/seon/cluster/agent.clj:103-104`, `src/seon/render/block.clj` install-tx |
| `:seon.cluster.run/agent` | `:seon.db/ref` | one | agent | run → **agent** | `resources/seon/schema/run.edn:41` | `src/seon/cluster/run.cljc:258-261` |
| `:seon.cluster.message/to` | `:seon.db/ref` | one | agent | message → **agent** | `resources/seon/schema/message.edn:12` | `src/seon/cluster/message.cljc:304`, `src/seon/error.clj:694` |
| `:seon.cluster.message/from` | `[:and {:seon.db/index true} :seon.db/ref]` | one | agent | message → **agent** | `resources/seon/schema/message.edn:25` | `src/seon/cluster/message.cljc:413` region |
| `:seon.error/agent` | `[:and {:seon.db/index true} :seon.db/ref]` | one | agent | error → **agent** | `resources/seon/schema/error.edn:68` | `src/seon/error.clj:348` |

`:seon.cluster.agent/agent` is the entity map (`resources/seon/schema/run.edn:15-23`)
and carries the family lenses `:seon.render/ai seon.render.agent/agent-ai`,
`:seon.render/html seon.render.agent/agent-html`.

`:seon.cluster.agent/{routing,armed,eid,arm-request,disarm-request,blueprint-request,
creation-request,creation-tx,seed-blocks}` are **in-memory only** — see §6.

### 3.2 The namespace family (`:seon.ns`)

Declared `resources/seon/schema/program.edn:39-86`; written by
`src/seon/fn.clj:169-192` (`namespace-row`, build indexing) and
`src/seon/sci/reader.cljc:207-210,395-397` (runtime agent evals).

| Attribute | Malli form | Notes |
|---|---|---|
| `:seon.ns/name` | `[:symbol {:seon.db/identity true}]` | the identity; `my.agents.<id>` for agents |
| `:seon.ns/source` | `[:string {:min 1}]` | REQUIRED in the entity map; absent on agent namespaces |
| `:seon.ns/doc` | `:string` | optional |
| `:seon.ns/requires` | `[:set :symbol]` | **not a ref** |
| `:seon.ns/aliases` | `[:set {:seon.db/component true} :seon.db/ref]` | → `:seon.ns.alias/binding` `{local, target-ns}` (both `:symbol`) |
| `:seon.ns/imports` | `[:set {:seon.db/component true} :seon.db/ref]` | → `:seon.ns.import/binding` `{local, target-class?}` |
| `:seon.ns/refers` | `[:set {:seon.db/component true} :seon.db/ref]` | → `:seon.ns.refer/binding` `{local, target-ns, target-name}` |

The bindings preserve SCI's resolver inputs, not the require operations
(`resources/seon/schema/program.edn:59-60`). `seon.fn/analyzer.clj:197-208`
reconstructs an `(ns … :require …)` prelude FROM those rows for lint, which is
the only place requires are re-materialised.

### 3.3 Run / plan-form / receipt

All `resources/seon/schema/run.edn`. Writers: `src/seon/cluster/run.cljc`
(`open-call:231-262`, `plan-call:395-446`, `receipt-start-call:502-512`,
`receipt-settle-tx:514+`), driven by `src/seon/cluster/loop.cljc:1011-1126`.

| Attribute | Form | Direction |
|---|---|---|
| `:seon.cluster.run/id` | identity string | on run |
| `:seon.cluster.run/agent` | ref | run → agent |
| `:seon.cluster.run/forms` | `[:set {:seon.db/component true} :seon.db/ref]` | run → form |
| `:seon.cluster.run/opened-at` / `closed-at` | `:inst` | presence = open/closed |
| `:seon.cluster.run/process` | string | presence = custody held |
| `:seon.cluster.run/plan-digest`, `/error`, `/missing-results` | scalars | |
| `:seon.cluster.run.form/{id,ordinal,source}` | scalars | on form |
| `:seon.cluster.run.form/run` | ref | form → run (mirrors `/forms`) |
| `:seon.cluster.run.form/ns` | ref | form → **ns** (parse-time namespace; routing owner) |
| `:seon.cluster.eval/{id,ordinal,at,result-edn,error,interrupted-at,output}` | scalars | receipt; presence IS state |
| `:seon.cluster.eval/run` | ref | receipt → run |
| `:seon.cluster.eval/ns` | ref | receipt → **ns** |
| `:seon.problems/id`, `:seon.error/kind` on the receipt | scalars | the routable problem identity |

### 3.4 Context capture, provider attempt, error fact

- `:seon.context.capture/{id,basis-t,prompt}` + `/run` ref + `/contributions`
  component set (`resources/seon/schema/context.edn:18-53`), written by
  `src/seon/context.clj:287-312`. Contribution rows carry name/hash/tokens/band/
  projection, no text (`src/seon/context.clj:264-285`).
- `:seon.ai.attempt/*` (`resources/seon/schema/ai.edn:117-143`): `/run` ref,
  `/error` ref to an error fact, `/failover-from` ref to a prior attempt.
- `:seon.error/fact` (`resources/seon/schema/error.edn:75-108`): `/agent` and
  `/run` refs, plus signature/kind/message/data-edn. `:seon.cluster.message/about`
  (`resources/seon/schema/error.edn:181`) points a message at the fact or receipt
  it concerns — this is the assignment edge `seon.context/assignment-ai` joins
  (`src/seon/context.clj:189-198`).

## 4. Reverse relations a walk must traverse backwards

The walk finds these by computation (`src/seon/render/walk.clj:221-235`), so
this list is documentation, not a hand list to maintain. Ordered by the entity
they point AT.

**At an AGENT:**
`:seon.cluster.run/agent`, `:seon.cluster.message/to`,
`:seon.cluster.message/from`, `:seon.error/agent`,
plus `:seon.db/user` on transaction entities (excluded as apparatus).

**At a NAMESPACE:**
`:seon.fn/ns`, `:seon.test/ns`, `:seon.cluster.eval/ns`,
`:seon.cluster.run.form/ns`, `:seon.cluster.agent/namespace`.

**At a RUN:**
`:seon.cluster.run.form/run`, `:seon.cluster.eval/run`, `:seon.error/run`,
`:seon.context.capture/run`, `:seon.ai.attempt/run`,
plus `:seon.cluster.agent/run` from the owning agent (a cycle).

**At a FUNCTION:** `:seon.fn/calls` (callers).
**At an ERROR FACT:** `:seon.cluster.message/about`, `:seon.ai.attempt/error`.
**At a BLOCK:** `:seon.cluster.agent/blocks` (blocks are apparatus; never entered).
**At a TEST:** `:seon.test.result/test`.
**At an ATTEMPT:** `:seon.ai.attempt/failover-from`.

Ordering and bound (`src/seon/render/walk.clj:203-235`): reverse neighbours are
grouped by attribute, sorted by attribute name, and within a group the NEWEST
`:seon.config.eval.result/max-collection` entity ids are kept then re-sorted
ascending. **This truncation is silent** — see §7 open question 5.

## 5. The namespace graph and the requires problem

### What exists

Requires are stored as a set of plain symbols
(`resources/seon/schema/program.edn:42`), produced by
`src/seon/fn.clj:171-175` from `namespace-context`. Aliases, imports and refers
are component entities and ARE ref edges. So a walk at the namespace node
today follows: 3 component binding families + reverse `:seon.fn/ns`,
`:seon.test/ns`, `:seon.cluster.eval/ns`, `:seon.cluster.run.form/ns`.

### Measured fan-out (live `default`)

| Measure | Value |
|---|---|
| `:seon.ns` rows | 121 |
| requires, total / avg / max | 967 / 7.99 / 28 |
| requires resolving to a `:seon.ns` row | 493 / 4.07 / 19 (**49% dangle**: `clojure.*`, `datahike.*`, java-side) |
| aliases / refers / imports, total | 866 / 195 / 115 |
| `:seon.fn` rows | 1367 |
| functions per namespace, avg / max | 12.3 / 104 |
| `:seon.fn/calls` edges | 1742 over 715 functions (avg 2.44 out-degree) |
| `:seon.test` rows | 622 |
| `:seon.schema` rows | 559 (unreachable) |

`seon.render.walk/refs` at the `seon.cluster.run` namespace node returns **39
connections**: `{:seon.fn/ns 32, :seon.ns/aliases 7}` — 32 is the
`max-collection` cap, not the real function count.

Full-walk cost from a namespace node (caps `max-collection 32`,
`max-nodes 100000`, kind `:seon.render/ai`):

| Distance | Rendered nodes | Distinct entities | ms | prose chars |
|---|---|---|---|---|
| 1 | 40 | — | 25 | 3 460 |
| 2 | 135 | — | 176 | 11 820 |
| 3 | 703 | 148 | 218 | 62 841 |

**4.75× render redundancy inside one walk** at distance 3. Add the agent hop
and an agent at distance N costs roughly a namespace at distance N−1.
62 KB of prose at distance 3 is far past a usable prompt — distance is the
only dial and it is coarse.

### What "walk the namespace then its requires" needs

Nothing in the current model gets there. Three shapes, cheapest first:

- **(a) derived edge in the walk.** Resolve `:seon.ns/requires` symbols
  through `:seon.ns/name` at traversal time and synthesise
  `{:seon.render.walk/attribute :seon.ns/requires, :target eid}` connections.
  No schema change, no dangling-row problem (unresolvable symbols simply
  produce no connection), and it is one function in the one traversal owner.
  Cost: the walk gains its first non-generic edge rule.
- **(b) make requires a ref set to `:seon.ns`.** Clean graph, but 474 of 967
  requires name namespaces with no row (`clojure.string`, `datahike.api`),
  so it forces either dangling upserts of empty `:seon.ns` rows or a lossy
  filter. Not recommended without a decision about external namespaces.
- **(c) a `:seon.ns.require/binding` component family** mirroring alias/import/
  refer, carrying `{target-ns :symbol, target :seon.db/ref (optional)}`. Most
  uniform with the existing three, most rows.

## 6. Agent data a walk CANNOT reach

### Not in the database at all (process-local)

| What | Where | Why it cannot be a fact |
|---|---|---|
| The routing entry: armed graphs, mailbox channels by entity id, fault channel | atom, `src/seon/cluster/agent.clj:276-284`; schema `resources/seon/schema/agent.edn:33-44` | names live channels; rebuilt by arming at boot |
| The agent's flow graph, mailbox channel, stop completion | `:seon.cluster.agent/armed`, `resources/seon/schema/agent.edn:46-48`, built `src/seon/cluster/agent.clj:382-391` | derived state, never stored (docstring `agent.clj:41-47`) |
| Proc pass/turn counters, mailbox deliveries, current run id in `::flow/state` | `src/seon/cluster/agent.clj:140,191-193,233-238` | flow ping state; observation only |
| Fault drop counter | `:seon.error/drops` atom, `resources/seon/schema/error.edn:158`; `src/seon/cluster.clj:984` | counts what never became a fact |
| The `:seon.oversight/*` fleet view (mailbox/turn buffer occupancy, passes) | `src/seon/oversight.clj:84-155` | derived from live flow pings; rendered into root's page as a block |
| Running instances registry | `src/seon/cluster.clj:186` | process artifact |
| SCI base ctx and per-run forks | `src/seon/sci/eval.clj:142,200-207` | compiler state |
| Web view registration / SSE streams | `src/seon/cluster.clj:974` | live connections |

### In the database but excluded by design

- **Blocks** — the agent's own view parts, `walk/apparatus?`
  (`src/seon/render/walk.clj:253-287`). Following them is a cycle in meaning:
  the block set is the INPUT to the render being derived.
- **Transaction entities** — and therefore `:seon.db/user`, `:seon.db/process`,
  and crucially `:seon.db/trigger` (`resources/seon/schema/provenance.edn:17-26`),
  the run's recorded cause. This is exactly why `:trigger` survives as a
  hand-written block (`src/seon/context.clj:212-245`) and why
  `src/seon/render/agent.clj:41-50` documents the overlap.

### In the database but unconnected

- **`:seon.schema` rows** (559). No ref attribute reaches them;
  `:seon.fn/spec` is a printed string (`resources/seon/schema/program.edn:10`).
  An agent cannot walk from a function to its contract.

### Does not exist

- **No memory family.** No `:seon.*memory*` attribute anywhere in
  `resources/seon/schema/`. Durable recall is messages + the wait-note carried
  in a settled receipt's `result-edn` (`src/seon/context.clj:247-258` teaches
  agents to use it that way).

## 7. Open modeling questions

1. **Should the walk start at the agent or the namespace?** The ruling says
   namespace; the code says agent (`src/seon/render/agent.clj:399-400`). Both
   reach the same nodes at N vs N+1, but the agent-first order puts messages
   and runs at distance 1 and functions at distance 2 — which is probably what
   is wanted and should be stated, or the block renamed.
2. **Should `agent → messages` be a real ref?** Recommendation: **no**. The
   reverse query is generic and needs no per-attribute knowledge; a forward
   `:seon.cluster.agent/messages` set would be stored-derived data that the
   loop would have to maintain on every delivery, and `:seon.cluster.message/to`
   is already the WAKE attribute (`src/seon/cluster/wake.cljc:93`) — a second
   representation would either double-wake or diverge. The same argument covers
   runs, errors, receipts, captures and attempts.
3. **`:seon.ns/requires` — derived edge, ref set, or component family?** §5.
   Recommendation (a), the derived edge, because it costs no schema change and
   handles external namespaces by construction.
4. **The agent's namespace stub.** Should `creation-tx` write a
   `:seon.ns/source` (an `(ns my.agents.<id>)` form) so the row matches the
   `:seon.ns/ns` family and gets a lens? Today the CENTRE of the agent's own
   context renders through the floor as raw datoms. This is a concrete defect,
   not a preference.
5. **Silent reverse truncation.** `reverse-refs` keeps the newest
   `max-collection` per attribute with no elision marker
   (`src/seon/render/walk.clj:216,232`). A 104-function namespace shows 32
   functions and says nothing about the other 72. The node budget elides
   loudly (`walk.clj:368-372`); the collection bound does not. Either emit an
   elision node per truncated group or state that reverse breadth is
   deliberately a sample.
6. **Distance is the only dial and it is coarse.** 3.5 KB → 11.8 KB → 62.8 KB
   across d1→d3 from one namespace. Per-relation budgets, or a band/priority on
   the connection rather than the block, may be needed before the walk can be
   the whole prompt.
7. **Should schemas join the graph?** A `:seon.fn/schemas` ref set (or making
   `:seon.fn/spec` reference `:seon.schema/key` rows) would let an agent walk
   from a function to its contract. Today it must read a printed string.
8. **Cache key.** Given the measured 4.75× intra-walk redundancy and
   cross-agent requires convergence, the cache must be keyed by
   `(entity-id, kind, distance, basis-t, projection-symbol)` and held
   **per cluster**, not per agent. Note the trap: a Datahike database value's
   equality compares the EAVT index, so the key must carry `:max-tx`
   (`:seon.context.capture/basis-t` already does exactly this,
   `src/seon/context.clj:303`), never the db value itself.
9. **Redirect/override plumbing exists but is unused.**
   `:seon.render/overrides` and `:seon.render/redirect`
   (`resources/seon/schema/walk.edn:39-59`) are threaded through
   `walk/projection` but every caller passes `{}` or omits them
   (`src/seon/render/agent.clj:177`, `:426`). If per-agent lenses are the plan,
   this is the seam; if not, it is dead weight.

## 8. Live-verification limits of this audit

The `default` cluster has **one** agent (`root`), zero messages and zero runs.
The agent-side fan-out numbers in §5 are therefore measured from a CORPUS
namespace (`seon.cluster.run`), and the message/run/receipt branches of the
graph are read from schema and writer source, not observed. Also:
`seon.cluster.agent/creation-tx` has exactly one caller
(`src/seon/cluster.clj:758`, root seeding) and there is no agent-creation route
in `src/seon/render/web.clj` — so multi-agent walk behaviour cannot be
exercised live in the fresh tree at all today.

## 9. Skill drift and adjacent findings

**Skills checked** (`data-modeling`, `datahike`, `data-oriented-clojure`).
Spot-checked file:line claims held: `test/seon/test_support.clj:151-183`
(`with-database`), `src/seon/schema.cljc:1149-1209` (entity-schema decomposition),
`src/seon/cluster/run.cljc:562-730` (schema-affected attributes),
`src/seon/instrument.clj:180-215` (`apply!`), `src/seon/schema/edn.clj:87-111`
(config derivation). No drift found in the three skills for this audit's
subject matter. Two notes:

- Neither `data-modeling` nor `datahike` mentions `seon.render.walk` or the
  family-lens idiom (`:seon.render/ai` / `:seon.render/html` as properties on a
  registered entity map). That idiom is now how a family gains a default
  renderer and it is discoverable only by reading `walk.clj:132-179`. Worth one
  paragraph in `data-modeling`.
- `data-modeling` says component refs cascade-delete; correct, and the agent's
  blocks rely on it (`resources/seon/schema/block.edn:85`).

**Source docstring drift (NOT a skill issue, but agent-facing):** 20+ source
comments and docstrings name schema files as `src/seon/schema/*.edn`; the files
live at `resources/seon/schema/*.edn`. Instances include
`src/seon/cluster/agent.clj:75`, `src/seon/render/walk.clj:78`,
`src/seon/context.clj:54`, `src/seon/render/agent.clj:66`,
`src/seon/cluster/run.cljc:919`, `src/seon/error.clj:153,840`,
`src/seon/cluster/message.cljc:62,431`, `src/seon/render/block.clj:65`,
`src/seon/problems.clj:64`, `src/seon/sci/eval.clj:115`,
`src/seon/render/web.clj:68`, `src/my/message.cljc:64`, `src/my/run.cljc:31`.
Docstrings render into agent context, so this is a lying pointer at scale. No
issue note was filed because this lane is report-only; it needs one.

## 10. Reproduction

Every number above came from `mcp__seon__eval_clj` against cluster `default`.
The database value is reached as:

```clojure
@(:seon.boot/cluster-connection
  (val (first @@#'seon.cluster/running-instances)))
```

Note `:seon.boot/cluster-connection`, not `:seon.store/branch-connection` —
the latter key is absent from the running-instance map. `seon.render.walk/refs`
and `/neighborhood` are instrumented, so a caps map must carry all four dials
(`max-collection`, `max-nodes`, `max-depth`, `max-string`) or the contract
refuses.
