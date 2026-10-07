---
type: research
status: reviewed-with-findings
created: 2026-10-07
---

# growth lane notes (2026-10-07)

Default pid 65136 on 42e569d9d, measured through `seon.mcp/call` from a JVM-mode probe namespace (`growth.probe`) for
warm agents `growth-probe` and `growth-deep`. Allocation is `getTotalThreadAllocatedBytes` (JVM-wide) unless marked
"thread" (`getThreadAllocatedBytes` of the calling thread). Raw JFR files and scripts are in the session scratchpad
(not committed); the numbers below are the evidence.

## 1. A trivial form allocates ~150 MB: what is it proportional to? (STOPPED: Datahike planner, options for root)

### Measured

| probe | ms | MB (JVM) | notes |
|---|---|---|---|
| `(+ 1 2)` warm, quiet | 117-130 | 144-162 | per-thread: ForkJoin carrier 39-70 MB, MCP connection thread 37 MB, flow async-mixed 32 MB |
| `(+ 1 2)` x10 under JFR | 150-223 | 215-295 | JFR inflates; used only for attribution |

Armed growth for one `(+ 1 2)`: `seon.db/q` x47 (71 ms inclusive), `seon.db/vector-query-call` x47 (63 ms),
`seon.db/with-declarations` x107.

JFR `ObjectAllocationSample` over ten `(+ 1 2)` (2,544 MB sampled): `seon.db/vector-query-call` holds 1,326 MB
inclusive (52%), of which:
- 757 MB in `datahike.index.persistent_set.CachedStorage.accessed` -> `clojure.core.cache` LRU `hit` ->
  `clojure.data.priority-map` assoc: the node cache's bookkeeping, one swap and one priority-map assoc per index node
  visited (~1.4 KB each);
- 131 MB `execute-fused-scan-rel` (attribute range scans).

Node visits counted exactly (a watch on the store's node-cache atom, removed after each probe): **54,000 node visits
per `(+ 1 2)`**, by first Seon caller:

| caller | visits |
|---|---|
| `seon.eval.drive/run-receipts` | 19,000 |
| `seon.fn/runtime-analysis-batch` | 11,340 |
| `seon.turn/next-ordinal` | 5,260 |
| `seon.turn/continuing-reply?` | 3,780 |
| `seon.turn/spent-runs` | 3,200 |
| `seon.render.transcript/branch-errors` | 2,820 |
| `seon.turn/evaluation-terminal-data` | 2,800 |
| `seon.repl/entity-emission` | 1,880 |
| `seon.problems/form-problem` | 1,860 |

### Cause (verified on the agent's branch database, 768,339 datoms, 1,353 evaluations, 898 runs)

Every one of these queries has the shape `[?x :<identity attr> ?input] [?y :<ref attr> ?x] [?y :a ?v] ...`. Datahike
(fork `d9121bbbad`) plans the `?y` patterns as an ENTITY-GROUP whose driving scan is the whole AEVT range of the ref
attribute, run BEFORE the unique AVET lookup that binds `?x` (`datahike.query/explain` output for `run-receipts`:
`ENTITY-GROUP on ?form ... scan: [?form :seon.cluster.eval/run ?run] [aevt]`, then `SCAN avet [?run :seon.turn/id
"..."]`). The work is proportional to **every evaluation/function ever written on the branch's history (store
size)**, not to the one run asked about.

`run-receipts` for one run, thread-allocated: 19,004 node visits, 14.7 MB, 10-18 ms. Bisected by clause: 2 clauses
59 visits; adding `[?form :source]` 618; `[?receipt :run ?run]` 5,677; each `get-else` +2,700.

Same query with the run passed as a lookup-ref input (`:in $ ?run-id ?run`, `[:seon.turn/id id]`): **22 visits,
0.9 MB, 1.8 ms** (planner substitutes the constant and probes AVET). A missing run answers `#{}` (no throw).
`runtime-analysis-batch`'s query (`[?n :seon.ns/name ?name] [?f :seon.fn/ns ?n] [?f :seon.fn/sym _]`): 11,355
visits, 10.8 MB per call, proportional to program size.

Refuted on the way: the store's index roots report no subtree counts (`has-subtree-counts?` false; root
`_subtreeCount` -1 although its children carry counts), so the planner uses its heuristic estimates (a unique lookup
estimated 768 rows). Healing the root count on a private `commit-as-db` copy made the estimates exact (1 / 1,353)
but the plan still ran the entity group first: 18,810 visits. The estimates are a second, smaller defect, not this
cause. Fresh stores (memory or file, Seon's index config, branch, reconnect) keep counts; how default's roots lost
theirs is not established.

Fixed per-query cost besides the scans: a one-clause `d/q` with a fresh input allocates ~310 KB (JFR: 41% in the
query-result cache's miss path `inheritable-entry`/`compatible-source-keys?`, which scans every cached bucket; 24%
plan creation, because the plan cache key embeds substituted constants); `seon.db/q` adds 200-330 KB over `d/q`.
47 queries x ~0.5 MB = ~25 MB of the trivial form.

### A one-line `defn`

Quiet reruns after the restart: `(defn plain-d [x] (inc x))` 232-264 ms, 335-397 MB JVM-wide (root's morning probe:
537 ms / 843 MB). The ~200 MB over `(+ 1 2)` is on the writer's flow thread (async-mixed, 165-168 MB): JFR over four
defns attributes 978 MB on async-mixed threads to `seon.db/speculative-projection` -> `seon.schema/load-projection`
-> `projection-from-entities` (417 MB inclusive) and `seon.schema/deleted-predicate-functions` (377 MB: a `tree-seq`
over every stored form and function contract per build). Every declaration commit rebuilds the whole projection:
proportional to the program, existing tasks `seon.schema/a-declaration-write-reloads-the-whole-projection` and
`seon.schema/a-function-contract-changes-the-schema-projection` (seon.schema held by task-tags; a design change).
The defn also adds node visits of the §1 class (`seon.sci.eval/installation-covers-program-change?` 7,960).

Konserve writes per `(+ 1 2)`: ~4 MB (LZ4 frame streams and byte buffers with no Seon frame in the JFR sample).

### Options for root (the fix is in the dependency's planner or across six owners)

1. **Datahike fork, planner (recommended).** When a group's join variable is bound by a unique-identity pattern with
   a constant value, resolve that pattern first (constant propagation like `substitute-consts-with-lookup-refs`
   already does for lookup-ref inputs), or cost an entity group by its scan's unconstrained cardinality so the
   AVET lookup orders first. One place; every Seon query of this shape becomes proportional to its answer. Cost:
   a Datahike change with its own tests, a pin bump, a restart; risk to all plans (mitigated: the fork's suite).
2. **Seon callers pass the identity as a lookup-ref input.** Measured 19,004 -> 22 visits for `run-receipts`.
   Nine queries in `seon.eval.drive`, `seon.fn`, `seon.turn` (4), `seon.render.transcript`, `seon.repl`,
   `seon.problems`; held files today. A workaround at callers (every new query can reintroduce the class), but no
   dependency change.
3. **Do nothing to queries; shrink the node-cache bookkeeping** (`clojure.core.cache` LRU priority map per visit).
   Halves allocation per visit, keeps the visits: treats the symptom.

## 2. Open `StackOverflowError` fault in `seon.error/normalize`

The three open faults (signatures `6886474456ca`, `438e41f190c2`, `632864f809b9`) are flow-exception recordings of a
StackOverflowError: the newest at 2026-10-07T02:08:06Z by agent `deep-value-probe` in the previous JVM (pid 30874),
two on 2026-09-28. `seon.error/normalize` is only the recorder's fallback operation name (`prepare` names
`'seon.error/normalize` when neither the failure, the instrument data nor the boundary names one), not where the
overflow happened. The producers were the recursive canonical printer and the recursive error-data walk, made
iterative at 11:21 and 12:10 +0900 (77c9f5459, 7a6878374), after the 11:08 +0900 occurrence.

Reproduction on current default (agent `growth-deep`): a 10,000-deep vector, map and list as values; `(throw (ex-info
"deep" {:v deep}))`; a 1,000-link chain: all return their value or error, no new fault. Regression exists:
`seon.sci.kernel-test/an-error-holding-a-ten-thousand-deep-value-keeps-its-error`.

Remaining defect (seon.error is held by task-tags): a fault with no attributable operation is recorded under the
recorder's own name. Patch for root below (§6).

## 3. Deep or repeated ex-data walked many times (FIXED in `seon.error.refusal/post-order`)

Found while reproducing §2: `(seon.db/q deepm)` with a 10,000-deep map ran over 32 s (MCP timeout) and drove
18,327,416 `seon.sci.admit/sci-context?` calls. Cause: `seon.db/q` passes a non-query map to `refused-read`, whose
contract refuses it; the instrument refusal names the offending argument under each of its **326 problems** (one per
union alternative's missing key). `refusal/referenced` then walked the same deep value 326 times, five armed world
predicates per node.

| depth of the argument | throw (instrument refusal) | `refusal/chain` |
|---|---|---|
| 50 | 67 ms, 133 MB (thread) | 39 ms, 45 MB |
| 100 | 79 ms, 158 MB | 67 ms, 80 MB |
| 200 | 108 ms, 207 MB | 121 ms, 149 MB |
| 400 | 162 ms, 305 MB | 234 ms, 287 MB |

At 10,000 deep the chain alone extrapolates to ~7 GB. The ex-data of the depth-50 case is 60,148 nodes for a
151-node argument (59,237 of them under `:seon.error/problems`).

A single deep value is linear (references on a nested map: 2.3 KB and ~3 us per node, 100 -> 800 deep), so the walk
is proportional to the data kept (the recorder stores the referenced data whole, errors spec); the waste was walking
one value once per mention.

Fix: `post-order` folds a completed composite met again by identity once, reusing its first result (an
`IdentityHashMap` local to the one walk). For stable, finite acyclic values and deterministic node callbacks, the
fold's visits follow distinct composite nodes plus their outgoing edges. Leaves are not memoized. Collection
updates, hashing and identity projection have their own costs; this is not a bound on all data admitted by `:any`.
EQUIVALENT in value equality under those preconditions; rebuilt repeated members now share identity. BEHAVIOR
CHANGE for the broader callable contract: a stateful projector under a repeated map produces `[{ :x 1} {:x 2}]`
on the parent and `[{ :x 1} {:x 1}]` on the change. The contract does not require purity. Root must settle that
semantic scope; see [the independent review](review-growth-codex-notes.md), including reproduction and options.
Regression: `seon.error.shared-data-test/a-value-repeated-under-every-problem-is-referenced-once` (300 problems
sharing a 10,000-deep value holding a place: every problem keeps the identical rebuilt value). The review measured
the same shared-depth input on SCI copies of parent/change: 11,336.9/93.8 ms and 7,249,546,240/27,523,840
thread-allocated bytes. These are private SCI probes, not proof of installation or a test run.

Not fixed here (held files, patches for root in §6): `seon.db/q` handing its raw argument to `refused-read`; the
instrument refusal building 326 problems that each carry the whole argument (133 MB for one refusal of a 50-deep
map).

## 4. Cause chain: 6 `throwable?` checks per link (REFUTED as growth)

`refusal/chain` on a 1,000-link ex-info chain, thread-allocated: 10 links 0.9 MB/0.9 ms, 100 links 1.8 MB/2.2 ms,
1,000 links 11.8 MB/15 ms: linear, and every link is kept. The per-link checks are the armed contracts of `link`,
`post-order`, `referenced`, `stack-frames`, `first-party-in` (one call each per link). Through the agent:
throwing the chain 352 ms / 268 MB; building the same chain without throwing 331 ms / 234 MB. The cost is the
agent's own 1,000 SCI `ex-info` constructions (~234 KB each), not the error path (+21 ms / +34 MB).

## 5. Profile reads (design; `seon.profile` held by task-tags)

`seon.profile/explain` over a window adds 6,864 `adder?`, 3,940 `histogram?` and 765 `entries` armed calls on 765
cells: every boundary a cell vector crosses validates each cell's `:seon.profile/cell` map, nine armed predicate
leaves per cell (`adder?` x3, `accumulator?`, `histogram?`, `throw-counts?`, `thread-local?`, `measured-mark?`), and
`cells`, `begin`'s and `explain`'s reads each pass the population. Proportional to the cell count per read, as
designed; the constant is the armed predicate leaves (the platform-wide question in
`seon.db/hot-total-predicates-run-hundreds-of-millions-of-times`).

Measured with 2,766 live cells (thread-allocated; the window's own `begin`/`explain` reads included):

| read | ms | MB | armed calls |
|---|---|---|---|
| `(seon.profile/cells)` unarmed original | 0.46 | 0.17 | 0 |
| `(seon.profile/cells)` armed | 11-18 | 3.6 | ~47k |
| `(seon.profile/explain mark 10)` | 98 | 7.8 | ~70k |
| `(seon.profile/summary (seon.profile/cells) 5)` | 187 | 31.8 | ~160k |

`summary` is what every `:slow` finding tells an agent to run. Options:
1. **Platform (recommended):** schema predicate leaves call the unarmed function (`seon.schema/runtime-predicate`,
   the existing task above). Every cell validation drops from ~9 armed calls to none, here and at every other
   boundary validating host-object maps.
2. **Profile-local:** `:seon.profile/cell` as one `[:fn seon.profile/cell?]` checking every member. One armed call
   per cell, but `cell?` restates the registered member schemas inline (data-oriented-clojure 4.1) and weakens the
   explained problems to one message.
3. **Fewer boundaries:** `top` filters cells by `entries` before `observation`; saves the per-cell observation chain
   for never-called cells only.

## 6. Patches for root (held files)

- `seon.db/q` (task `seon.db/q-hands-a-non-query-map-to-refused-read`): the first branch calls `refused-read` for any
  value that is not a database, query vector, string or query map; only an error value (`:seon.error/type` present)
  belongs there. A plain map should get q's own invalid-read refusal (the `diagnostic` shape `schema-refusal` builds,
  expected `:seon.db/query`, offending the map). Proof: `(seon.db/q {:a 1})` returns `:seon.db/invalid-read-error`
  under 1 MB (today 109 MB / 70 ms through a 326-problem instrument refusal).
- `seon.instrument` refusal construction: 326 problems for one argument against a union, each carrying the whole
  argument as `:seon.error/offending`, 109 MB per refusal independent of the argument's size. Existing task
  `seon.instrument/violation-construction-is-quadratic-in-explain-errors`.
- `seon.error/prepare` (task `seon.error/a-fault-without-an-operation-is-recorded-under-normalize`): the
  `(or function 'seon.error/normalize)` fallback names the recorder; use the boundary's layer operation or a typed
  unknown.

## 7. Design check

- The memo belongs in `post-order` (the one walk), not in callers: `chain`, `references` and the recorder's second
  `references` all go through it. It is a local accumulator of one fold, not an atom or a cache; nothing outlives
  the call.
- Awkward: the recorder still walks the error data more than once per fault (`chain` per link, then `references` on
  the refusal in `prepare`, then admission). The memo is per walk, not shared across those calls. Its fold visits
  follow distinct composites and edges under §3's preconditions; hashing an opaque leaf can still realize a lazy
  sequence or overflow on cyclic Java data. Sharing one walk across calls needs `seon.error` (held).
- Item 1's design held only as a diagnosis: the producer is Datahike's planner, so the fix is a dependency decision.

## Commits and tasks

- 3071fef19: `post-order` folds a repeated value once (src +10/-3; test +18; check 98 s, 21 tests / 133 assertions
  green: `seon.error.shared-data-test`, `seon.error.refusal-test`,
  `seon.sci.kernel-test/an-error-holding-a-ten-thousand-deep-value-keeps-its-error`). After-numbers on default need
  root's restart; re-run the depth table in §3 (`refusal/chain` on the refusal of `(seon.db/q <n-deep map>)`).
- Tasks created (database, agent growth): `seon.db/an-identity-join-scans-the-whole-ref-attribute` (a952d921f468),
  `seon.db/q-hands-a-non-query-map-to-refused-read` (05518143b09f),
  `seon.error/a-fault-without-an-operation-is-recorded-under-normalize` (ce40e5fac600),
  `seon.cluster.store/the-store-index-roots-carry-no-subtree-count` (aa983ae094b7).

## Findings recorded

- The value printer shows a `ConcurrentHashMap` entry as `#object[...MapEntry]` and cuts 30-60 character strings
  inside a small vector (existing task `seon.render.value/a-short-identifier-string-is-cut-at-its-first-chunk`).
- `bin/seon check` boot: ready-ms 81,227, boot-ms 96,636 of a 122 s check.
- 1,000 SCI `ex-info` constructions cost 331 ms / 234 MB (agent's own work, ~234 KB per exception).
- `(my.agent/retire-branch! {:seon.store/branch (:seon.agent/branch (my.agent/branch))})` run by the agent on its
  own branch answers only `{:seon.error/message "run transition refused: admission-closed"}`: the retirement closes
  admission before the run that asked for it settles, so the agent never sees the call's value (agents
  `growth-deep`, `growth-probe`).
- `(seon.db/q <10,000-deep map>)` ran past the 32 s MCP limit before 3071fef19 (18.3M `sci-context?` calls);
  the agent stayed responsive afterwards.
