---
type: prd
status: active
tags: [prd, agent, architecture, database]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Sci execution runtime roadmap

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

## THE ONE ORDERED LEDGER (2026-07-26) — this section owns implementation order

**Read this and nothing else to decide what to do next.** Owner ruling O17,
2026-07-26, after asking why the previous plan was written and then not
followed. The answer was not indiscipline: **there were seven orderings in six
files with five unit-naming schemes**, and the file the repo's own rules make
authoritative — this one — held the oldest of them. An agent asking "what next"
got a different answer per file opened.

**From now: this section is the only ordering in the chunk, and every row is
self-sufficient** — what is broken, where, the closing change, and the
falsifier. You should never need to open another document to start work.

Four reference documents survive, and none of them sequences anything:

| document | what it is for |
|---|---|
| `research/measurements-2026-07-25.md` | every number with its conditions. **Never quote a number without them.** |
| `conversion-wiki.md` | portable-core scars. Read before `.cljc` work; append new ones. |
| `research/capability-ledger-2026-07-26.md` · `pod-cut-verdict-2026-07-26.md` · `jvm-render-design-2026-07-26.md` | the three audits that produced these rows. Delete each when its rows close. |
| `research/preprocessing-design-2026-07-23.md` | cited by the root `AGENTS.md` vocabulary table until code owners land. |

Everything else that once planned this work has been **deleted** — seven
orderings in six files, plus ~100 dated audits. Git is the archive. A document
that says "do not implement from this design" is not a design document; it is
deletable, and it was deleted.

**Row fields.** `owner` — the file or mechanism that must change, so a deletion
that discharges several rows shows it. `state` — `open`, `blocked on Ox`, or
`discharged by <sha>`. `verified` — the date the row's evidence was last
re-grepped; a row not verified since the last cut is a HYPOTHESIS, not work.
Anchors are symbols, not line numbers, wherever a symbol exists.

### Discharged 2026-07-26

| owner | what | discharged by |
|---|---|---|
| `src/seon/host.clj` + all `src/seon/host/` | the old guarded door, 5,715 src + ~7,000 test lines. Took with it: per-agent ctx retention (R-8a leak *and* the rejected model), the fixed 10-thread pool, D9's walk-away cancel, `policy-either`-style resource-as-agent-fault mis-filing, the second IPC path, and D7's tools.reader `*read-eval*` path | `8dc8623ad`, seams filed `ef1f815a5` |
| `seon.error.frame` | ordering vocabulary reconciled to one spelling, `ordinal` | `ee000a4e7` |
| `seon.sci.ctx` / `seon.sci.eval` | D15 catch-class surface; the interrupt marker proven un-swallowable by `(catch Throwable …)` | `ce5e061f2` |
| `seon.agent.driver` | duplicate run admission; D5's residual wake loop; D2 lease readiness | `71f3cb0e0`, `1832764de`, `3946b7192` |
| `bin/codex-agent` | the sandbox dial, which made an audit's own output unrecordable | `42a9faf2e` |
| `reference-code/http-kit` | vendored as a submodule | `2953a3b2f` |

### The ordered spine — one in-progress item at a time

**1. `seon.sci.ctx` — the agent capability door. `state: open`. `verified: 2026-07-26`.**
The earliest unsettled contract and it blocks every demo. **Verified: an agent's
entire callable surface today is `clojure.core`, `clojure.string`, and five
`seon.agent.lifecycle` vars.** No db, blob, fs, shell, web, messaging, or LLM —
the whole door died with `host/context.clj`. Agents can compute and close a
turn; they cannot act. Closing change: one **computed**, schema'd binding table
whose capability functions all enter one guarded dispatcher — never the two
hand-maintained lists the old `context.clj` used (R-14, and AGENTS.md bans hand
lists). Proof: one live reply uses db, blob, messaging, fs and web through one
door, with no registry or session path.

**2. `bin/test-writer` — restore the gate. `state: open`. `verified: 2026-07-26`.**
It discovers **0 tests**: it needs the compiled program artifact, so it needs a
`bin/seon up` / `bin/seon down` coordinated source freeze. Nothing currently
verifies the JVM at all, so every claim above rests on targeted evaluation
rather than a suite. A blocked test runner is named in AGENTS.md as an
attack-immediately item; it is second only because row 1 defines what the suite
must cover.

**3. `seon.agent.driver` terminal transaction + `seon.sci.ctx` — the corpus round trip. `state: open`. `verified: 2026-07-26`.**
Three defects, one mechanism. (a) Nothing writes `:seon.fn`/`:seon.ns`/`:seon.schema`
since the tee died — filed. (b) Boot installs no corpus — filed. (c) **A `defn` in
form 1 is invisible to form 2 of the same reply**, because the driver never passes
`::sci.eval/base-ctx` and every form forks the pristine base. The answer is NOT a
retained per-agent ctx (R-19a rejects it; R-8a is its leak): form 2's basis IS
form 1's transaction report `:db-after`, the read-your-own-writes property
measured FORCED (`measurements` §8.1, 0 vs 9). **Correction from the capability
ledger: `:load-fn` alone cannot resolve a bare same-namespace symbol**, so thread
`:db-after` and namespace identity, not a context. Canonical fn/ns/schema/test
facts plus agent/process provenance are required — not merely `:seon.fn/source`.

**4. `seon.sci.eval` — containment residue. `state: partly blocked on O4`. `verified: 2026-07-26`.**
(a) **Lazy values escape the armed boundary** — blocker, filed `40ea7e29c`:
`evaluate` returns SCI's raw value and cancels the timer before returning, so
realization happens outside with no `:interrupt-fn`. Fix at one choke point
inside the boundary, never a guard per realizer. (b) Prints are lost and terminal
`pr-str` is unbounded — one bounded writer plus a total bounded ordinary-value
projection, inside the armed evaluation. (c) A blocking host call permanently
retains a compute permit and there is no per-agent process to reap. (d) The
terminal receipt drops `fn-entries` and allocated bytes, so a kill cannot reach
the agent with the spin-versus-blocked diagnostic. (e) Allocation is diagnostic
only — **blocked on O4**.

**5. `script/seon/dev/` + `seon.db.protocol` — the index. `state: open`. `verified: 2026-07-26`. (O15, O16)**
Index at compile time from a **JVM build only**; never at runtime. The six
shadow-cljs hooks (`shadow-cljs.edn:63-80`), `src/seon/client/indexing.clj`, and
`script/seon/dev/program_inventory.clj` go with the pod. Delete
`initialization-pages`' "or derive from raw initialization" branch — missing
pages is a loud failure. Fresh cluster loads precomputed pages; resume reads
config for overrides only, then the database. `seon.db.program/compile-tx-data`
is orphaned (one caller, its own test) and gets a real producer or dies.

**6. `seon.eval.receipt` + `seon.agent.run.core` — durable-run correctness. `state: D1 blocked on O2, rest open`. `verified: 2026-07-25 — RE-GREP FIRST`.**
Six defects, each measured on the prototype; the HEAD symptom is stated where it
differs, because otherwise the fix aims at the wrong thing.

- **Two writers on one store destroy each other silently.** Two live JVMs on one
  file store both won the same epoch CAS and **40 of 40** of the parent's
  successfully-returned commits vanished, with zero transact errors and a store
  that looked pristine on reopen. Closing change: the unsafe configuration
  **refuses to open**. Documenting the constraint without enforcing it fails.
  **Blocked on O2** — `architecture.md:242-246` currently promises
  interchangeable cluster JVMs, which is unsafe as written.
- **A duplicate execution is undetectable.** `:seon.eval/id` is a generated
  compact value, so HEAD cannot destroy a receipt *and* cannot detect a
  duplicate either — no index at all. The terminal transition *is* CAS-fenced
  `:running → :running`, so double *recording* is prevented; double *execution*
  is not. Closing change: "form 3 of 7" answerable by query, and a step with a
  terminal receipt at its ordinal never re-executable. Keeping a generated eval
  id fails both halves.
- **Read-modify-write loses updates with no error.** 40 concurrent 1-step runs
  for one agent produced 40 `:ok` receipts and a counter of **1**. Closing
  change: **stop storing it** — the value is exactly `(count receipts)`, and the
  repo rule is derive projections rather than store them. Adding a CAS retry
  around a stored derived value fails the simplification test.
- **Agent-returned tx-data reaches the database unfiltered.** A poisoned fact
  detonated in the driver's own `d/transact` and the exception escaped: run left
  open, receipt stuck `:running`, **no fault recorded anywhere**. Hostile-but-valid
  facts wrote into another agent's counter. This violates "nothing throws into
  the agent loop." Closing change: commit the step transaction inside a try; on
  failure commit a **terminal** receipt carrying the fault, alone — which both
  records it and kills the poison pill, because resume advances past a terminal
  receipt.
- **Resume uses a count as a position.** With a hole in the receipt set it
  answered `{:total 7, :next-index 5}`, skipping 3 and 4 forever. Closing change:
  next ordinal is the first in `(range total)` with no terminal receipt. One
  line, and it deletes the in-flight special case.
- **Message identity upserted an earlier message** and killed a cycle after 3
  hops, silently. Closing change: derive message identity from the sending
  receipt `(run, ordinal, epoch)` — a deterministic id is exactly what keeps
  delivery idempotent under re-execution, so design this together with the
  duplicate-detection row above.

Plus the **committed ordered step plan**, the only *proven* resume mechanism: it
survived six kill positions plus a double kill (converged at epoch 3), one
re-execution per crash, and SIGKILL inside `d/transact` at 8 points over
200-datom transactions with **zero** torn transactions. Its mandatory companion:
`start-run!` is check-then-act and spliced two model replies into one 7-step plan
in **3 of 12 trials**, the window being the model call — gate the insert on a
`:db/cas` or write the plan as one cardinality-one value. **Reject** the
per-receipt remaining vector: no race, but it rewrites the tail every step.

**Correction that changes this row:** preflight repair must now finish *before*
plan commit, so the old six-emitted-to-seven-executed splicing proof no longer
applies and `:seon.eval/total`'s meaning must be restated as part of this work.

**7. `seon.db.protocol` — one wire predicate. `state: open`. `verified: 2026-07-26`.**
Merge `persisted-value?` into `ordinary-wire-value?`; delete the `pr-str`
degradation path and the unreachable encode `try/catch`. `(map inc [1 2 3])`
crosses as `(2 3 4)`. Interacts with row 4(b) — the same totality discipline at
the same kind of choke point. Free deletion riding here: `::mailbox-depth`.
**`seon.result/ok?` is mostly discharged by row 8** — 11+ of ~20 `ok?` variants
live in `.cljs` files on the deletion list, so do not refactor them first.

**8. the pod — 48 files, 24,037 lines. `state: open`. `verified: 2026-07-26`. (O13)**
DELETE-NOW in five commits per `research/pod-cut-verdict-2026-07-26.md`:
diffusion/typeahead 4,607 · JVM-owned capability leaves 5,113 ·
provider/generation/embedding 2,871 · pod toolkit/agent orchestration 2,640 ·
pod substrate and supervised entry 8,806. Then `:seon.dev.process/pod` leaves
the supervised set. **PORT-TO-CLJC is zero.** Known cost, to be accepted
explicitly rather than discovered: `bin/test-cljs` compiles Shadow's
`:node-test` build, so this loses 98 CLJS test namespaces / 1,080 `deftest`s
plus the CLJS branches of 24 `.cljc` namespaces / 191 more. `bin/test-writer`
must claim that ground — no fourth runner.

**9. render — 15 files, 6,920 lines. `state: blocked on O14 ruling`. `verified: 2026-07-26`.**
The investigation is done and its target is validated with one open decision:
the recommendation is to commit the complete render as a **cardinality-one
no-history fact** (the already-ratified pattern for high-churn presentation
state) — but that **stores a derived value**, against a standing rule. The owner
must rule that render-once across a zero-consumer gap beats derive-don't-store;
otherwise "zero additional evaluation" holds only for overlapping consumers.
Measured and settled: 32 consumers → **one** evaluation; an authored infinite
loop killed at **55 ms / 9,639,035 fn entries** with every consumer getting the
error morph and the server healthy. Also fix here: http-kit's socket queue is
unbounded (filed), and reconnect after zero consumers currently re-evaluates.

**10. Wave 5 capability + the accretion chain. `state: open`. `verified: 2026-07-25`.**
Namespace-addressed resident agents (O4 of the night batch), waiting = open +
unclaimed, `:seon.ns/owner` as a **new** attribute, and the five-link accretion
chain — sound `::calls` first (three discard sites in `seon.program.edge`), then
a non-constant effect rollup, output-map closedness, a JVM producer for test
evidence, and a function-granular test link.

**11. packages / bun as a leaf. `state: open`. `verified: 2026-07-26`. (O10 — LAST)**
Owner: *"This is complicated so do this last."* Carries GAP #5: after O15
deletes shadow indexing, **no selected mechanism enumerates a leaf package's
callable surface.** Choose and prove one package-native compile/install-time
source; runtime only reads its committed facts.

### The graduation gate

Unchanged from "The final system gate" above, plus: the **reset-boundary live
proof on the default cluster after O13 removes the pod**, and the acceptance
test's exit measure — **the `src/seon/` diff for the photos demo capability is
ZERO.** If it is not zero, that is where layers cost mechanism, and that is the
finding.

### Owed and unproven — do not let these become "done"

- the reset-boundary live proof for the door cut (`8dc8623ad`)
- JVM boot re-measurement now that `host.context`'s ~900 ms is deleted; the last
  pair is 10,293 → 3,886 ms at `-Xmx2g`, JDK 26.0.1, AOT 92.7% / AppCDS 7.3%
- 36 unregistered production attributes (24 attempt, 7 run, 5 turn) and 38
  unused require aliases — filed, riding rows 6 and 8
- O2, O4, O14 unruled; `core.async.flow`'s non-adoption unratified; and
  `seon.sci.eval` still hand-rolls `newCachedThreadPool` while calling itself
  `:compute` — route it through core.async's own dispatch

---
