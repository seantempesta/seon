---
type: prd
status: active
tags: [prd, agent, architecture]
---

# The plan — from A to B on seven constructs

Steps are capabilities the system gains; defects are subordinate to the
capability that closes them. Disagreements with `roadmap.md`'s ledger are
stated inline.

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
