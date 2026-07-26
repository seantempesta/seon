---
type: prd
status: active
tags: [prd, agent, architecture]
---

# What is not settled

Owner instruction, 2026-07-26: *"I feel like we are close to representing
everything witht he same primitves and composing them together but we aren't
there yet and be honest about what isn't done."*

This file is that honesty. [README.md](README.md) says what to do; this says
what we do not yet know. Nothing here is a task list — a row graduates out of
this file either into a plan step or into a `docs/seon/issues/` note, and a row
that stays vague is a row nobody has thought about hard enough yet.

Three categories, and the distinction matters:

- **UNDECIDED** — the owner must rule; we can state the trade but not resolve it.
- **UNKNOWN** — nobody has the evidence yet; there is a named experiment.
- **UNBUILT** — decided and understood, simply not done. These live in
  [README.md](README.md)'s steps and appear here only where the gap is bigger
  than the step admits.

## 1. UNDECIDED — needs an owner ruling

| # | question | what turns on it | recommendation |
|---|---|---|---|
| O14 | Does a rendered snapshot get **committed** as a cardinality-one no-history fact? | It stores a derived value, against a standing rule. But it is the only way "render once, cached, still there for a second tab after everyone disconnects" holds; without it, zero-extra-evaluation is true only for *overlapping* consumers. | Commit it. The owner's own words describe materialization, and `:seon.db/no-history?` cardinality-one is already the ratified pattern for high-churn presentation state. Record it as a named exception to derive-don't-store rather than pretending it isn't one. |
| O4 | Is there an allocation **limit**, or only a diagnostic? | Today it is a diagnostic, so one agent can exhaust the shared heap of every agent in its cluster. | Keep it a diagnostic and accept that heap is the process boundary — no in-process metric can bound it (measured: 200 MB allocated in 1 ms with **0** fn entries, invisible to any cadence). Then the real answer is disposable per-agent processes, which is step 8 territory. Say so instead of implying the cap works. |
| O2 | May two cluster JVMs serve one store? | `architecture.md:242-246` promises interchangeable cluster JVMs. Measured: two live JVMs on one file store both won the same epoch CAS and **40 of 40** of the parent's returned commits vanished, zero errors, store pristine on reopen. | Amend the architecture and make the configuration **refuse to open**. Note this does *not* constrain N stores in one process — `create-writer :self` is per-connection. |
| — | Is `core.async.flow` adopted as a **library**, or only its vocabulary? | Flow's added value is a proc graph plus `ping`/`pause`/`resume`/`inject`, and that API exists *because* flow's state is hidden in memory. | Adopt `core.async`, not `core.async.flow`. **The flow-vs-Integrant overlap is resolved** (`bd8038419`): Integrant owns in-process boot ORDER, core.async owns SCHEDULING. Different jobs, no competition. Still wants an explicit owner ruling that non-adoption of the library is deliberate. |

**Resolved 2026-07-26, recorded so it is not reopened.** Integrant is adopted
**narrowly and conditionally** (`bd8038419`): only when writer, driver and
web-render merge into one JVM, and only if that merge deletes the ~360 lines of
standalone lifecycle scaffolding it identifies. The operator's OS-process graph
stays separate — an OS process cannot be an `init-key` value. Shape: one root
system containing one nested Integrant system per cluster, so a single-cluster
reset halts only its own nested system. Strongest borrow from the archive: the
single derived `ig/assert-key :seon/component` Malli-validation choke point.
`suspend-key!`/`resume-key` are **rejected** until measurement proves a specific
restart resource is too slow. Biggest risk, and an acceptance condition rather
than a preference: a flat `refset` edge would make one cluster's halt traverse
shared resources and take down every cluster.

## 2. UNKNOWN — needs evidence, with the experiment named

- **~~Does the submission-channel design replace the semaphore?~~ ANSWERED
  2026-07-26** (`3564882a3`): the semaphore is **deleted, not kept beside the
  channel** — `open!`/`available`/`permits` go; its queueing job becomes the
  channel's fixed buffer and its concurrency job becomes the launcher's slot
  count, both per-class config facts, with nothing outside the launcher able to
  acquire capacity. The launcher is one loop parked in `alts!!` over the three
  class channels. What remains UNKNOWN is only the measurement below. A
  bounded channel bounds the *queue* and parks puts (`async.clj:113-117`: "When
  full, puts will block/park"). It does **not** bound parallelism — the
  executor does. So `seon.sci.eval`'s semaphore is doing two jobs, and the
  replacement is (bounded channel = backpressure) + (bounded `:compute`
  executor = parallelism). Unverified: whether `:seon.eval/available`'s
  accounting survives that split, and what `newCachedThreadPool`'s removal does
  to the measured "a wedged eval degrades capacity by exactly one" property.
  **Experiment:** wedge N evals under the channel design and confirm capacity
  degrades by exactly N and a query still names the wedged step.
- **Agent messaging must be adapted, and the target shape is not settled.**
  Owner, 2026-07-26: *"we likely need to adapt agent messaing."* What is
  established: delivery is already pure derivation with no read/ack flag
  (`waking-inbound?`); the turn boundary is the take, so a message can never
  preempt a running eval; and the wake attribute must stay disjoint from
  attributes the wake path itself commits. What is **not** established: whether
  message identity derived from the sending receipt `(run, ordinal, epoch)` is
  sufficient to make delivery idempotent under re-execution, and what happens to
  a message whose sending form re-executes after a crash. Today's reply message
  takes a *freshly allocated* id, so re-execution can double-send. **Experiment:**
  kill a process after a send commits but before the run closes, and observe
  whether the recipient receives one message or two.
- **Whether the three turtles are genuinely one mechanism.** N cluster-writer
  flows, M agent drives, and every function call inside a turn are supposed to
  share one dispatch substrate. Partly verified: Datahike's writer is already a
  two-stage core.async pipeline, and on this JDK `go` expands to
  `(thread-call … :io)` (`async.clj:528-529`), so the database already rides
  `executor-for :io`. **Not** verified: that agent evals and function calls can
  join it without a second scheduler, and what the honest seam is. The
  scheduling design claims exactly one seam — agent interpreted code
  additionally carries `:interrupt-fn` + platform thread + permit, switched on by
  computed provenance. That claim has not been built or measured.
- **Whether workload can be derived soundly at all.** The derivation depends on
  `:seon.program.edge/calls`, which has three measured discard sites — a
  higher-order caller is a **silent false negative**, so `(map my-blocking-fn xs)`
  records only `clojure.core/map`. Until those close, any derived workload is
  wrong in the one direction that wedges a `:compute` thread. **Experiment:** fix
  the three sites, then assert that a call graph reaching a capability edge is
  never classified pure.
- **JVM boot after the door deletion.** The last pair is 10,293 → 3,886 ms
  (`-Xmx2g`, JDK 26.0.1, AOT 92.7% / AppCDS 7.3%). The residual was 63% three
  non-AOT namespaces including `seon.host.context` at ~900 ms — **which is now
  deleted**. Unmeasured. **Experiment:** re-run the boot breakdown at the same
  flags.
- **Whether the pod cut loses coverage we need.** It removes 98 CLJS test
  namespaces / 1,080 `deftest`s plus the CLJS branches of 24 `.cljc` namespaces.
  `bin/test-writer` must claim that ground, and nobody has enumerated which of
  those 1,080 assert a *surviving* mechanism versus a deleted one.

## 3. UNBUILT — understood, not done, and bigger than its step admits

- **An agent cannot act at all.** Its entire callable surface is
  `clojure.core`, `clojure.string`, and five `seon.agent.lifecycle` vars. No db,
  blob, fs, shell, web, messaging or LLM. Every demo, every load test and every
  proof of the design flows through the door that does not exist yet.
- **Nothing verifies the JVM.** `bin/test-writer` discovers **0 tests** — it
  needs the compiled artifact, so it needs a `bin/seon up`/`down` freeze. Every
  claim about today's tree, including in this file, rests on targeted evaluation
  rather than a suite.
- **The wire is still on the agent path.** `seon.db.host/writer-session` opens a
  UDS session to a separate `writer` process, so every agent read and write
  crosses a socket — measured at 6-7 writer round-trips for one form containing
  one write. O1's co-location is the target, not the state.
- **Two blockers filed today that no step yet owns end to end.** A run opened
  before its plan commits is unrecoverable by either recovery query, in the
  window holding 78.5% of a turn
  ([[../../../seon/issues/run-is-unrecoverable-before-its-plan-commits]]); and
  agent-to-agent messages never wake anyone because the wake query requires
  `:origin :human`
  ([[../../../seon/issues/agent-messages-never-wake-the-jvm-driver]]).
- **The corpus round trip is broken in three places at once**: nothing writes
  `:seon.fn`/`:seon.ns`/`:seon.schema`, boot installs no corpus, and a `defn` in
  form 1 is invisible to form 2. Note the correction: `:load-fn` alone cannot
  resolve a bare same-namespace symbol, so this is not "add a `:load-fn`".

## 4. Where the primitives do not yet compose — the honest core of this file

The owner's read is that we are close to one set of primitives that compose.
That is true in four places and not yet true in three, and the three are worth
more attention than the four:

**Composing already.** A read is a pointer into a database value at a basis. A
change is a transaction whose report gives the next basis. Custody is CAS +
epoch + lease facts. Delivery, wake and render all derive from facts through one
predicate each, with no stored flag.

**Not yet composing:**

1. **Scheduling is not one mechanism — it is four expressions of one idea.** The
   eval bound is a `Semaphore`; the transaction bound is a Datahike queue size
   we never set; run admission and capability calls have **no** bound at all.
   The design says one bounded submission channel per workload class. Until that
   lands, "backpressure" is a property of one path and an absence in two.
2. **The corpus is a fact store without a resolver.** Code is committed as facts
   and nothing loads it back. So "code is data" is currently half a primitive —
   the write half. Until acquisition materializes a namespace from facts at a
   basis, the corpus composes with nothing.
3. **Containment has one hole that is not a policy choice.** A lazy value leaves
   the armed boundary unrealized and is realized later with no `:interrupt-fn`.
   Until realization happens inside the boundary at one choke point, "everything
   leaving is bounded" is aspiration, not a primitive.

The pattern in all three: **the write side of a primitive exists and the read
side does not.** Facts are committed but not resolved; work is scheduled but not
bounded; values are produced but not admitted. That is a more useful way to hold
the remaining work than a step list, and it is why the plan's step 1, 3 and 4
are ordered the way they are.

## 5. Things believed true that were wrong within a day — read before trusting a row

Recorded because the failure mode is systematic, not incidental:

- Six assumptions were tested in the previous session and **six were wrong**.
- Four of six defects in one plan row were already fixed at HEAD; the row was
  written from a document one day old.
- The plan's own `file:line` anchors went stale in a day because the plan's own
  work moved them. Prefer symbols.
- A "~1,160 zero-caller lines" deletion claim was false for two of its three
  units.
- Multi-agent messaging was assumed working by the ledger, the capability index
  and the plan. It is not, and it is a one-line query filter.

**So: re-grep a row's evidence before acting on it.** Every claim in this file
was verified on 2026-07-26 and may already be stale.
