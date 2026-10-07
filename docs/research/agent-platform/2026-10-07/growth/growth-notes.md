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

Fix: `post-order` folds a collection met again by identity once, reusing its first result (an `IdentityHashMap`
local to the one walk). Work becomes proportional to the distinct data. EQUIVALENT in result: `open`/`step`/`close`
are pure functions of the node, so a reused result equals a recomputed one; repeated members now share one rebuilt
value. Regression: `seon.error.shared-data-test/a-value-repeated-under-every-problem-is-referenced-once` (300 problems
sharing a 10,000-deep value holding a place: every problem keeps the identical rebuilt value).

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

## 6. Patches for root (held files)

## 7. Design check

- The memo belongs in `post-order` (the one walk), not in callers: `chain`, `references` and the recorder's second
  `references` all go through it. It is a local accumulator of one fold, not an atom or a cache; nothing outlives
  the call.
- Awkward: the recorder still walks the error data more than once per fault (`chain` per link, then `references` on
  the refusal in `prepare`, then admission); each walk is now proportional to distinct data. Sharing one walk across
  them needs `seon.error` (held).
- Item 1's design held only as a diagnosis: the producer is Datahike's planner, so the fix is a dependency decision.

## Findings recorded

- The value printer shows a `ConcurrentHashMap` entry as `#object[...MapEntry]` and cuts 30-60 character strings
  inside a small vector (existing task `seon.render.value/a-short-identifier-string-is-cut-at-its-first-chunk`).
- `bin/seon check` boot: ready-ms 81,227, boot-ms 96,636 of a 122 s check.
- 1,000 SCI `ex-info` constructions cost 331 ms / 234 MB (agent's own work, ~234 KB per exception).
