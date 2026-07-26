---
type: prd
status: active
tags: [prd, agent, architecture, database]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Seon runtime — the plan

**You are in the right place. Read these four files, in this order, and nothing
else.**

| file | what it is | trust |
|---|---|---|
| **this file** | the ONE ordering: 7 base constructs, 8 capability-ordered steps, the landmines, the open owner rulings | current as of its last edit |
| [state.md](state.md) | what is TRUE right now, **generated** by `bin/plan-state` from the live tree, every row carrying the command that produced it | **highest — regenerate it and it cannot be stale** |
| [unsettled.md](unsettled.md) | what is UNDECIDED (needs a ruling), UNKNOWN (needs an experiment), UNBUILT — and where the primitives do not yet compose | current |
| [history.md](history.md) | what was tried before, with commit hashes, and the mistakes worth not repeating | permanent |

`reference/` holds the audits these steps were derived from, copied here so
everything is colocated. The originals stay in `../research/`; neither is
deleted. Cite them, do not re-derive them.

**If [state.md](state.md) disagrees with this file, state.md wins.** Run
`bin/plan-state` first — this session repeatedly found plan rows that a
one-day-old document had made wrong, and six of six assumptions falsified in a
single sitting. Verify against the tree, not against prose.

**A second ordered list anywhere in this chunk is a defect.** Seven once existed
across six files in five naming schemes, which is why "follow the plan" had no
referent. An eighth was created and collapsed on 2026-07-26. If you find
another, delete it and point here.

---

## Sci execution runtime roadmap

## The final system gate (owner, 2026-07-25 night) — READ THIS FIRST

This is what "done" means. Not a slogan: every line is falsifiable, and a
session that cannot point at one of these is not finished, however green its
suite is.

**Live agents, really running.** Not a fixture, not a drive script. Real agents
take real turns against a real model in the default cluster, author functions
into the one corpus, message each other, and are still running an hour later.
The proof is a transcript and committed datoms, not a passing test.

**Load-tested, by us, on purpose.** We drive it until something breaks, and we
know which thing broke and why. Not "it seems fine" — a number, a ceiling, and
the name of the resource that hit it. We already know the shape of the answer:
the commit path is one core, SCI's share is measured rather than assumed, and
the model dwarfs both. Find the real wall.

**Kick-ass fast, measured.** Boot in seconds, agent start in milliseconds, a
turn dominated by the model call and nothing else. Every performance claim
carries the conditions it was measured under — this program has already been
misled twice by a number without its context.

**Speed-clause status — SATISFIED 2026-07-26.** The invalid broken-turn
waterfall remains visible, and §18 supplies the corrected fresh-cluster
measurement, durable component reconciliation, conditions, exclusions, and
target-only reset proof.

**Every weird smell chased to its cause.** A coercion, an inconsistency, a
duplicate mechanism, a silently-wrong default: each one is a bug until proven
otherwise, and it gets an issue with evidence even when it is not fixed today.
Today alone produced the vector-order defect, `read-string` honouring
`*read-eval*`, and limits that did not bound — all found by pulling on
something that merely looked odd.

**Clojure already solved most of this — go read it.** Before inventing any
mechanism, find where Clojure, `core.async`, `core.async.flow`, SCI, or
Datahike already answers it, and take their answer *and their name for it*.
This program's best decisions were all of that shape: `:interrupt-fn` over an
invented door, `:io`/`:compute` over invented pool names, flow's `transform`
discipline, the admin surface we get for free by putting state in a database,
and `[:set X]` over a bridge rewrite. **The wheel is round. Every time we
reinvented it today, the evidence took it away from us.**

**And the standing test, from the owner:** *is this simpler than it was?* If it
is equally complex, the model was ported, not applied.

## THE PLAN (2026-07-26) — this section owns implementation order

**Read this and nothing else to decide what to do next.** Owner ruling O17: this
is the ONLY ordering in the chunk. Seven orderings once existed across six files
in five naming schemes, which is why "follow the plan" had no referent; they are
deleted (`24053c64e`) and git is the archive. An eighth was created and collapsed
here on the same day — if you find a second ordered list anywhere in this chunk,
it is a defect: delete it and point at this section.

**Colocated with this file:** [unsettled.md](unsettled.md) — what is UNDECIDED,
UNKNOWN and UNBUILT, including where the primitives do not yet compose and a
list of things believed true that were wrong within a day. **Read it before
designing anything.**

Four reference documents survive and none of them sequences anything:
`research/measurements-2026-07-25.md` (every number with its conditions — never
quote one without them), `conversion-wiki.md` (portable-core scars),
`research/capability-ledger-2026-07-26.md` + `pod-cut-verdict-2026-07-26.md` +
`jvm-render-design-2026-07-26.md` + `scheduling-design-2026-07-26.md` (the audits
that produced these steps; delete each when its step closes), and
`research/preprocessing-design-2026-07-23.md` (cited by the root vocabulary
table).

**A step whose evidence has not been re-grepped since the last cut is a
hypothesis, not work.** One cut discharged five rows on 2026-07-26, and a step
carried stale evidence within a day. Re-verify before starting.

### Discharged 2026-07-26

| owner | what | discharged by |
|---|---|---|
| `src/seon/host.clj` + all `src/seon/host/` | the old guarded door, 5,715 src + ~7,000 test lines. Took with it: per-agent ctx retention (R-8a leak *and* the rejected model), the fixed 10-thread pool, D9's walk-away cancel, `policy-either`-style resource-as-agent-fault mis-filing, the second IPC path, and D7's tools.reader `*read-eval*` path | `8dc8623ad`, seams filed `ef1f815a5` |
| `seon.error.frame` | ordering vocabulary reconciled to one spelling, `ordinal` | `ee000a4e7` |
| `seon.sci.ctx` / `seon.sci.eval` | D15 catch-class surface; the interrupt marker proven un-swallowable by `(catch Throwable …)` | `ce5e061f2` |
| `seon.agent.driver` | duplicate run admission; D5's residual wake loop; D2 lease readiness | `71f3cb0e0`, `1832764de`, `3946b7192` |
| `bin/codex-agent` | the sandbox dial, which made an audit's own output unrecordable | `42a9faf2e` |
| `reference-code/http-kit` | vendored as a submodule | `2953a3b2f` |

## 1. The base constructs

Everything the runtime is made of. Every step below is an application of
these and nothing else; a proposal that needs an eighth construct is wrong.

1. **The database value.** One immutable value at a named basis. Every read
   anywhere — eval, prompt, render, resume — is a pointer into one
   (`seon.db/db`).
2. **The transaction.** The only way anything changes. One writer per store;
   the report's `:db-after` is the next basis. All coordination is committed
   facts, so any process may die and a survivor resumes from them.
3. **The plan fold.** A reply freezes into one ordered form plan
   (`seon.agent.driver/plan-tx-data`, absent→digest CAS). Execution is a
   fold: running receipt → eval at the previous step's `:db-after` → terminal
   receipt (`execute-form!`). Custody is CAS + epoch + lease facts
   (`seon.agent.run.core/claim-plan`). Resume is a query
   (`seon.eval.receipt/next-ordinal`: first ordinal without a terminal
   receipt).
4. **The guarded eval.** One shared SCI base (`seon.sci.ctx/base`), one fork
   per evaluation, one `:interrupt-fn` with time as the only limit
   (`seon.sci.interrupt/start`), on a `:compute` platform thread behind a
   semaphore (`seon.sci.eval/evaluate`). Everything leaving is deeply
   realized and bounded at that one choke point; the heap's boundary is the
   process.
5. **The door.** The single dispatcher every genuine capability call enters —
   db, blob, fs, shell, web, messaging, LLM. Bindings computed from the
   corpus, never listed. Effects carry the one request identity (`seon.db`
   operation IDs); the honest ceiling is at-least-once.
6. **The corpus.** Code is facts: `:seon.fn`/`:seon.ns`/`:seon.schema`
   committed like any data, acquired at a basis into a fresh fork. One corpus
   answers "what exists?" and "load it." A compile-time JVM index is its
   first-party producer; agents are its runtime producer.
7. **The derived view.** Prompt, page, warning, context: pure functions of a
   database value on one reactive chain — interest → equality suppression →
   latest-wins (`seon.reactive`, `seon.db.host/listen!`). Nothing rendered is
   stored, with one owner-ruled exception pending (O14).

Corollary (owner ruling 2026-07-24): the agent-facing surface is three shapes
only — values the driver interprets, requests through the door, facts the
driver commits. Three processes carry all seven: cluster JVM, web-render JVM,
disposable leaves (`architecture.md:239-262`).

## 2. Where we are

The live path is four files, 1,181 lines measured today:
`seon.agent.driver` (888) → `seon.sci.eval` (146) → `seon.sci.ctx` (42) +
`seon.sci.interrupt` (105), with `seon.repl.parse` the pure parser.
Constructs 1–4 work and are proven: the fold survived six kill positions plus
a double kill, one re-execution per crash, zero torn transactions; receipt
identity is deterministic `(run, ordinal, epoch)`
(`seon.eval.receipt/receipt-id`); a rejected agent transaction becomes a
terminal error receipt, not a wedge (`execute-form!`); an authored infinite
loop dies at ~55 ms with the server healthy.

What an agent can DO is almost nothing. The callable surface is
`clojure.core`, `clojure.string`, and five `seon.agent.lifecycle` vars
(`seon.sci.ctx/base`) — no construct 5. Construct 6 is broken three ways: the
driver commits no corpus facts, boot installs none, and a `defn` in form 1 is
invisible to form 2 because `drive-sources!` never passes
`::sci.eval/base-ctx` or a basis. Construct 7 runs on the dying pod except
the JVM `/data` feed. Bounding is incomplete: `terminal-receipt-data`
`pr-str`s unbounded and drops `fn-entries`/`allocated-bytes`; prints are
lost; lazy values realize outside the armed boundary. `bin/test-writer`
discovers 0 tests. Five supervised processes; the target is three.

## 2b. The piece map — State A → State B

State B is small: the four-file live path, plus a door, an admission
operation, a corpus resolver, one scheduler, and a web-render tier — three
processes, seven constructs, an agent surface of three shapes. The distance
from here to there is not new machinery; it is pieces that already exist
wearing the wrong name or the wrong shape. This table names each one, says
which base construct (§1) it really is, and points at the step that
discharges it. **It sequences nothing — order lives only in §3.** Verify a
row against [state.md](state.md) before acting on it.

| piece today (State A) | what it really is | State B shape | discharged by |
|---|---|---|---|
| `seon.sci.eval`'s `Semaphore` | two mechanisms in one object — queueing and parallelism | bounded submission channel per workload class (backpressure) + bounded `:compute` executor (parallelism), both config facts; semaphore deleted (`3564882a3`) | scheduling design; wedge-N experiment in [unsettled.md](unsettled.md) §2 |
| the pod — 63 files, 30,962 lines | three unrelated jobs in one process: the pages producer (construct 6's first-party half), the render tier (construct 7), and a duplicate execution engine (construct 4, already owned by the JVM) | producer → JVM compile-time indexer; renderer → web-render over committed facts; engine → deleted, no replacement | steps 5–7 per `pod-cut-verdict-2026-07-26.md` |
| `seon.db.host/writer-session` UDS wire on the agent path | a process-boundary artifact mislabeled as database access | O1 co-location: a read is a pointer into a database value, a write is a function call in the cluster JVM | step 6 |
| `seon.sci.ctx/base`'s `:namespaces` literal | a hand list where a derived view belongs (L17) | computed binding table from corpus facts, every capability fn entering the one door | step 1 |
| code as source strings | the corpus with only its write half — facts committed, nothing resolves them | terminal tx commits `:seon.fn`/`:seon.ns`/`:seon.schema`; acquisition materializes a namespace from facts at a basis | steps 4–5 |
| `terminal-receipt-data`'s unbounded `pr-str`, dropped `fn-entries`/`allocated-bytes`, and the `persisted-value?` / wire-predicate split | value admission scattered across consumers instead of one choke point (L3) | one admission operation inside `evaluate` before disarm; one `ordinary-wire-value?` | step 3 |
| reply message with a freshly allocated id; wake filtered on `:origin :human` | allocated identity where derived identity belongs; a derivation with a hand filter | message identity = sending receipt `(run, ordinal, epoch)`; wake on the one inbound rule | step 2 + [[../../../seon/issues/agent-messages-never-wake-the-jvm-driver]] |
| run opened before its plan commits | custody split across two transactions that recovery reads as one | the pre-plan window recoverable (or run+plan one commit) | **no step owns it end to end** — [[../../../seon/issues/run-is-unrecoverable-before-its-plan-commits]] |
| `:seon.ai.attempt/*` 24 used / 0 registered; `:seon.agent.turn/*` 17 / 9; `:seon.agent/run` registered twice, once in a `.cljs` | facts written without their schema half; a registration on the deletion list | one surviving `.cljc` owner per attribute, moved before the pod cut | step 6 precondition (state.md §10–11) |
| hand-rolled `newCachedThreadPool` called `:compute` | borrowed vocabulary without the mechanism | core.async's own `executor-for :compute`, or the name goes | §5 flow ruling |
| the "or derive from raw initialization" branch | a second pages producer | deleted; missing pages fail loudly (O16) | step 5 |
| five supervised processes | writer+host are one construct split by history; the pod is the three jobs above | three: cluster JVM, web-render JVM, disposable leaves | steps 6, 8 |
| two writers on one store both winning the CAS | a configuration that must be impossible, currently merely documented (L6) | refuses to open, loudly | step 6, O2 |
| a stored render snapshot (O14) | not a violation — the one ruled exception to derive-don't-store, wearing guilt it hasn't earned | cardinality-one no-history fact, equality-suppressed, fenced by the ruling | step 7, O14 |

The pattern across every row is the one [unsettled.md](unsettled.md) §4
names: the write half of a primitive exists and the read half does not, or
one object is doing two constructs' jobs. Nothing here needs an eighth
construct.

## 3. The steps

Each: the capability, the change, landmines respected (§4), a falsifier.

### Step 1 — An agent can act

One live reply uses db, blob, messaging, fs, and web through one door.
**Change:** `seon.sci.ctx/base` gains one computed, schema'd binding table
whose capability functions all enter one guarded dispatcher; derive it from
the corpus facts today's initialization pages already commit, so no producer
change blocks it. Writes carry the existing operation-ID contract.
**Landmines:** L17 (computed, never the old two hand lists), L10, L1 (a
blocked host call keeps exactly its one permit — accepted residue until step
8 places foreign work), L20. **Falsifier:** the five-capability live reply
commits its receipts; `rg` finds no literal toolkit list and no per-family
session path; removing a capability fn from the corpus removes it from the
table with zero source edits. First: it blocks every demo, and steps 3–4 are
proven through it.

### Step 2 — The machine proves itself

Every claim above is claimed by a runner that runs. **Change:**
`bin/test-writer` restored behind one `bin/seon up`/`down` artifact freeze;
it claims the standing regression classes — resume-ordinal holes, duplicate
execution, poison-pill terminal receipts, plan-CAS splices, lazy admission,
door-is-computed — plus the open row-6 items: derive message identity from
`(run, ordinal, epoch)` (today `lifecycle-tx-data`'s reply message takes a
fresh allocated id, so re-execution can double-send) and bounded provider
attempt facts on the frozen request. Keep one regression that wake damping
holds: the terminal reply asserts wake attribute `:seon.agent.message/to`,
damped only by `pending-message-query`'s `:origin :human` filter (L8).
**Falsifier:** `bin/test-writer` count > 0 and red on any seeded regression.
A standing lane from the same day as step 1 (blocked runner = development
incident); second only because step 1 defines what it covers.

### Step 3 — What an agent returns is whole, bounded, and diagnosed

Any value an eval produces crosses out eager, capped, printable, explained.
**Change:** one admission operation inside `seon.sci.eval/evaluate` before
`::interrupt/stop!`: deep realization, depth/item/string caps, bounded print
capture; the terminal receipt keeps `:seon.eval/fn-entries` and
`:seon.eval/allocated-bytes` (today dropped); the time limit and caps become
config facts (`drive-sources!` hardcodes 60000). Same totality at the wire:
merge `persisted-value?` into one `ordinary-wire-value?` in
`seon.db.protocol`; delete the `pr-str` degradation path. **Landmines:** L3
(one choke point, never per consumer), L2, L16. **Falsifier:** a returned
lazy bomb runs zero callbacks after `evaluate` returns and comes back
`:time`; `(map inc [1 2 3])` crosses as `(2 3 4)`; a kill receipt carries the
spin-versus-blocked numbers. Before step 4: corpus values and renders both
cross this boundary.

### Step 4 — Code an agent writes today exists tomorrow

An agent defines a function in form 1, calls it in form 2, and another agent
calls it next turn after a restart. **Change:** (a) thread each report's
`:db-after` plus namespace identity into the next evaluate as the fold basis
— never a retained ctx; (b) the terminal transaction commits canonical
`:seon.fn`/`:seon.ns`/`:seon.schema`/require-edge facts with agent+process
provenance, requiring a complete `:malli/schema` for durable defns; (c)
acquisition materializes a namespace from corpus facts at a basis into the
fresh fork — `:namespaces` plus one `:load-fn`, and the fork materializes the
current namespace itself. **Landmines:** L5 (the fold basis is measured
FORCED), L14, L15, L13. **Falsifier:** the two-form reply returns the value,
not "Unable to resolve symbol"; kill the JVM; a second agent requires and
calls the function next turn — proof is committed datoms.

### Step 5 — The index has one producer

A fresh cluster starts with every first-party function, schema, and test
already facts, produced by one JVM build step — never at runtime. **Change:**
a JVM compile-time indexer in `script/seon/dev/` emits mandatory
initialization pages and becomes the one caller of
`seon.db.program/compile-tx-data` (today: only its own test); delete
`seon.db.protocol/initialization-pages`' "or derive from raw initialization"
branch — missing pages fail loudly; make `::calls` sound (three discard sites
in `seon.program.edge`, ledger row 10) so purity and placement stay computed.
**Landmines:** L17, L18. **Falsifier:** a throwaway cluster resets from pages
with shadow-cljs stopped; deleting the pages artifact fails boot loudly, not
slowly; a call graph reaching a capability edge is never classified pure.
Forced before step 6 finishes: the shadow hooks are today's only producer.

### Step 6 — One system, three processes

`bin/seon up` starts watcher, cluster JVM, web-render; kill any one and the
system recovers from facts. **Change:** the pod cut per
`research/pod-cut-verdict-2026-07-26.md` — groups 1–4 (15,231 lines) now,
they block nothing; group 5 (substrate, 8,806) after step 5 replaces the
pages producer; then `:seon.dev.process/pod` leaves
`script/seon/dev/process.clj`. Merge writer and host into the one cluster JVM
per store (O1 co-location; the measured shared-writer topology contradicts
O9), and make the two-writer configuration refuse to open once O2 amends
`architecture.md:242-246`. `bin/test-writer` claims the ground the 98 CLJS
test namespaces held — no fourth runner. **Landmines:** L6 (refusal, not
documentation), L7 (lease wake stays event-armed, `arm-lease-wake!`), L18.
**Falsifier:** the reset-boundary live proof on the default cluster with the
pod gone; two JVMs pointed at one store: the second refuses to open, loudly.

### Step 7 — A human watches without costing the agents anything

Any number of tabs sees every agent live; an authored infinite-loop canvas
costs one bounded evaluation and the agent learns why. **Change:** implement
`research/jvm-render-design-2026-07-26.md` on the ruled target
(`ui.md:23-27`) once O14 rules: one cluster-side registration per pinned
canvas, admission via step 3's boundary, equality-suppressed commit of the
complete snapshot as a cardinality-one no-history fact (never overwriting
`:seon.render.canvas/content`); web-render serves the whole UI from committed
facts and its replica; streamed reply partials ride the same construct — one
coalesced no-history fact; fence http-kit's unbounded socket queue (filed);
delete the 15 render-held files and `seon.reactive`'s CLJS async branches.
**Landmines:** L3 (no authored closure after disarm), L2, L7. **Falsifier:**
32 tabs → one authored evaluation; reconnect after zero consumers → zero; the
loop canvas → error morph to every consumer, healthy server, and a committed
`:agent` fault distinguishing spin from blocked.

### Step 8 — Packages return as disposable leaves (LAST, O10)

An agent calls a function from an installed JS package; the process serving
it can be killed freely. **Change:** choose and prove one package-native
compile/install-time surface enumeration (GAP 5 — nothing owns this after
step 5 deletes shadow indexing); runtime only reads its committed facts;
placement derives from the indexed call graph. **Landmines:** L1 (the leaf is
the cancellation boundary a blocked call finally gets), L10, L17.
**Falsifier:** kill the leaf mid-call: the agent receives a flat error naming
what may have happened; the surface facts exist with zero runtime
enumeration.

## 4. The landmines — standing constraints

Each measured or a dependency property; violating one is silently wrong.

- **L1** `Thread.stop` is removed (JDK 26); no suspend/resume of an SCI
  eval. Containment is one time-bounded `:interrupt-fn` plus process
  replacement — never a third mechanism.
- **L2** No in-process memory bound: a host-call allocation shows 0 fn
  entries; the allocation metric is cumulative, anti-correlated with
  footprint. Heap is the process boundary.
- **L3** Lazy values escape the armed boundary; deep-realize inside it, at
  one choke point, never a guard per consumer.
- **L5** Form granularity is forced (0 vs 9 at turn vs step basis); the turn
  is a fold of forms, the resume unit is the form, the basis is `:db-after`.
- **L6** One write connection per store: two writers silently destroyed
  40/40 commits. The unsafe configuration must refuse to open.
- **L7** `listen` fires on transact only; a stale lease is not a commit; a
  firing clock backstop is itself a bug report.
- **L8** No wake attribute the wake path's own work commits (7.0 → 124.8
  commits/run, then OOM).
- **L9** No retrofitted eval atomicity; mid-eval entity ids are wrong the
  moment anyone else commits.
- **L10** No exactly-once external effects; the ceiling is "may have
  happened, here is what it was."
- **L11** The writer is one serial loop (knee 65,536 callers / 4,336 tx/s,
  APFS metadata force); snapshot isolation, never serializability — full-head
  basis pinning degrades badly.
- **L13** Order is never a collection-type property: stored ordinal, tx id,
  or sort key. Cardinality-many is unordered; tuples cap at 8.
- **L14** `:load-fn` cannot resolve a bare same-namespace symbol, and
  `:namespaces` is consulted first.
- **L15** Compiling agent code to a native fn deletes the `:interrupt-fn`;
  availability is interning, nothing else.
- **L16** SCI is 0.15% of a measured turn; the provider is 78.5%
  (`research/measurements-2026-07-25.md` §18, conditions there). Interpreter
  speed justifies nothing.
- **L17** No second mechanism, no hand-maintained list, ever; every
  exception is a computed structural rule.
- **L18** Clusters always reset to current code and pages; no data migration.
- **L19** Never sandbox a lane; a read-only audit lost its own evidence.
- **L20** Agent code needs no reader conditionals; portability is derived.

## 5. Open owner decisions

- **O14 render materialization.** Blocks step 7. Recommend **commit** the
  snapshot as a cardinality-one no-history fact — measured 1 → 0 evaluations
  across a zero-consumer gap, survives restart — fenced as the one ruled
  exception to derive-don't-store.
- **O4 allocation.** Recommend **ratify diagnostic-only** (L2: the metric
  bounds the wrong quantity); heap protection is the process boundary plus
  loud OOM recording.
- **O2 horizontal cluster JVMs.** Blocks step 6's refusal gate. Recommend
  **amend `architecture.md:242-246`**: one cluster JVM per store (O1/O9),
  scale by adding clusters, interchangeable-JVM prose deleted.
- **`core.async.flow` non-adoption.** Recommend **ratify**: keep the
  transform vocabulary, skip the library; route `seon.sci.eval`'s hand-rolled
  `newCachedThreadPool` through core.async's own `:compute` dispatch or stop
  borrowing the word.

## 6. What done looks like

The owner-written final system gate (`roadmap.md`) — live agents really
running for an hour, load-tested to a named wall, fast with conditions, every
smell chased — plus the reset-boundary live proof after step 6 and the
acceptance exit: the `src/seon/` diff for the photos demo capability is ZERO.

The one measurement: re-run the §18 self-attributing turn on the
three-process system with a real plan — agent A authors a corpus function
through the door, agent B calls it next turn, a human watches the canvas —
every driver component attributed within the predeclared tolerance, provider
dominant, conditions stated, transcript and datoms retained. If that turn
cannot be measured and repeated after a cluster reset, the program is not
done, whatever the suites say.
