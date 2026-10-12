---
title: "Plan (Opus): an agent's tracked facts are pushed to main as they change"
type: research
status: design round 4, Opus's independent plan; design only, nothing implemented
basis: HEAD 588d986c7 read by symbol (`seon.cluster.squash/squash-merge`, `copy!`, `add-count`, `collision-remap`;
  `seon.cluster.agent/unlanded-ends`, `land-ends!`, `record-error-end!`, `fork-attempt!`, `db<->flow`;
  `seon.cluster.source/merge-base`; `seon.cluster.registry/collect-and-inventory!`; `seon.program/changed-identities`,
  `three-way`; `seon.task/frontier`); `reference-code/datahike` `datahike.gc/reachable-in-branch`;
  [hybrid world](../2026-10-11/hybrid-agent-world.md), [Astra review](astra-overnight-review.md),
  [verify-astra](verify-astra.md) (findings #1–#4 confirmed, decision F); REPL probes on default 2026-10-12 (below)
---

# Pushing an agent's tracked facts to main

## 1. The design in one paragraph

**A push is the existing squash merge run inside main's writer, against the last commit main says it received.**
Each attempt row on main carries **`:seon.task.attempt/pushed`**, the branch commit id the last push took. That
is git's remote-tracking ref, and it is written in the same transaction as the facts it covers. One pusher exists:
**`db<->flow`**, root's proc, which already holds main and the roster. The branch's route wakes it on every
transaction that asserts a `:seon.cluster` attribute: every recording transaction that changed tracked facts, and
also `end-task!`, an occurrence recorded at the entrant boundary, and a mailbox's delivered message. The agent's
world never names main: it writes only its branch, and the listener and Flow carry the wake. `db<->flow`'s pass
derives **`seon.cluster.agent/unmerged`**: the started attempts whose branch head commit differs from `pushed`.
For each one, it transacts on main
`[[:db.fn/call seon.cluster.squash/push-tx head attempt projection]]`. The function receives the writer's own db
value, *ours*. It reads `pushed` from ours (else F's recorded fork commit), opens that commit as the **base**
(`d/commit-as-db`), runs `squash-merge` with base, theirs = the held immutable head, and ours, and returns one
transaction: the replayed facts, `[:db/add attempt :seon.task.attempt/pushed head-commit]`, and one conflict entity
per `:root` conflict.

This one move removes the four confirmed findings at their source:
- **#2 (double counting):** the base advances atomically with every push. An `:add` delta is theirs minus the last
  pushed value, so a repeat adds 0.
- **#3 (decision before the writer):** the three-way runs on the writer's db, so there is no read-then-write
  window, no lock and no retry.
- **#4 (lost conflict evidence):** conflicts are data with all three values and both commits, and they retain the
  branch until resolved.
- **#1 (the copy marker):** there is no marker. The end is a tracked fact and arrives by an ordinary push. Stopping
  and deleting are derived from facts on main and the roster.

A crash anywhere loses nothing: what is unpushed is derived (head ≠ `pushed`), and the pass primes at
`::flow/resume`.

## 2. Facts → when → how

| Fact (partition) | Written on | Reaches main | How |
|---|---|---|---|
| task refinement: intent, must-read, evidence, execution, stage, `needs` (`:seon.cluster`) | branch, the agent's recording tx | at the next `db<->flow` pass after that tx | `push-tx`, three-way per `[e a]` against `pushed` |
| child tasks, merge request row (`:seon.cluster`) | branch | same | born on the branch: replayed with eids unchanged (P1a); no base, never conflict |
| attempt end, reason, commit (`:seon.cluster`) | branch (`end-task!`) | same pass: the end is just a push | the lifecycle is derived from the end on main (§4) |
| error occurrence and its `count` (`:seon.cluster`, `count` is `:add`) | branch, entrant boundary | next pass | delta = theirs − base(`pushed`); a natural-identity collision is remapped to main's eid **whether or not base holds the entity** (today's `when-not base` condition double-remaps nothing but misses pushed entities) |
| delivered message (moves to `:seon.cluster`, appended, no rule needed) | recipient's branch, by its mailbox | next pass of the recipient's branch | a record only: identity `:seon.message/id`, so the sender's `keep?` copy collides onto the same entity; it wakes and starts nobody on main (P5 proof) |
| `:seon.task.attempt/pushed` (main only) | main | is the push | written inside `push-tx`; the branch never asserts it, so the three-way always keeps main's |
| conflict (`:seon.squash/conflict` entity, new) | main | is the push | entity, attribute, base/ours/theirs as the value printer's shown text plus native scalars where the attribute is inline-sized, `base-commit`, `source-commit`, branch; identity `seon.id/id [:push-conflict attempt entity attribute source-commit]` |
| evaluations, calls, turns, answered `t`, program | branch | never (program: root's merge request) | unchanged |

**Ordering.** One proc pushes, branch by branch, in sorted order per pass. Each branch's pushes follow its commit
order, because each one names its head and the next one's base is that head. Coalescing is squash semantics: N
branch commits between passes become one main transaction, and intermediate states are not replayed. Across
branches there is no order beyond the writer's serialization. The second push to touch an `[e a]` sees ours ≠ base
and applies the rule.

**Idempotence.** Inside the writer, `pushed = head` yields `[]`. The caller skips that case before transacting, so
no empty provenance transaction is written (verify-astra #2). Eids are never translated except by the collision
remap.

**Crash between the branch write and the push.** Nothing is recorded as pending: `unmerged` derives it. The prime
pass pushes once. A crash after the main transaction but before the lifecycle step re-derives that step (§4).

## 3. Cost (measured on default, JVM eval, values and one disposable branch `:probe/push-opus`, retired)

| Step | Measured | Proportional to |
|---|---|---|
| open base (`merge-base` → `commit-as-db`) | 3.4 ms | one commit read |
| `changed-identities` over the 64 tracked attributes | 0.8 ms warm, 3.3 cold | **each attribute's whole history**: 9,451 datoms today, not the change |
| `squash-merge` (one refined intent) | 0.15 ms warm, 0.4 cold; empty 0.06 ms | changed coordinates |
| the same squash as `[:db.fn/call …]` under `seon.db/with` on main | 1.4 ms | the same |
| one branch transaction (`seon.db/transact!`) | 19 ms | the writer floor |
| `force-branch!` of a ref (not used by this design) | 18 ms | — |

**A push costs about 5 ms inside the writer plus one main transaction (~19 ms). Its floor is one main
transaction per pass per branch with tracked changes.** The end copy cost one per attempt. Push costs one per
coalesced tracked change, and passes are woken only by tracked datoms, so evaluations alone never push. A branch
with only untracked commits since `pushed` costs the base read and `changed-identities` (≈4 ms) and writes nothing.
The `changed-identities` history scan is the one cost not proportional to the change. Recorded as risk 2.

## 4. The lifecycle, derived (replaces `land-ends!`'s marker)

`plan` gains two derived actions, read from main and the roster with no marker:
- **`:stop`** for a started graph whose attempt has an end on main.
- **`:delete-branch`** for a roster branch whose attempt has an end on main, where also:
  - `pushed` = head (nothing unmerged),
  - no merge request is open,
  - no unresolved `:seon.squash/conflict` names it,
  - `:seon.config.agent/keep-ended-branches?` is false.

A `:done` end opens the merge request (`land-ended!`), as today. An error end gets root's problem task by identity,
as today, but now with nothing to "copy by hand", because its tracked facts are already on main.

Each step is idempotent (stopping a stopped graph, deleting an absent branch), so an interruption after any
boundary finishes on the next pass. `land-ends!`'s per-end catch and its `detached!` inline fallback go: no guards,
no fences.

**Retention of the base.** `datahike.gc/reachable-in-branch` keeps an ancestor only while every newer commit on the
path is inside `:seon.config.db/snapshot-window-ms` (1 h). A `pushed` commit followed by an hour of untracked
evaluations would therefore be swept. `seon.cluster.registry/collect-and-inventory!` is the only collector (its
`:datahike.gc/reachable-extension`). Before marking, it derives the retained set from main's `pushed` values (and
F's fork commits) for roster branches, and keeps them through F's tag mechanism. Nothing is written per push.

## 5. Deleted, by symbol

- `seon.cluster.squash/copy!`, `conflict-task!`, `squash-merge!` and its `selection` argument with
  `selected-coordinates`: root pushes by calling the same `push!` at its REPL, and an error end has nothing left
  to copy.
- `add-count`: the sum is computed on ours inside `push-tx`.
- `collision-remap`'s `(when-not (seq (d/datoms base …)))` condition.
- `seon.cluster.agent/unlanded-ends`, `branch-end`, `record-error-end!`, `land-ends!`'s copy branch and catch.
- The `:seon.squash/attempt` tx-meta marker and its schema entry; `:seon.squash/datoms`, `/conflicts` (the answer
  map) become `push!`'s answer.
- `db<->flow`'s `unlanded-ends` try/catch.

**Renamed in the same commit:**
- `seon.task/frontier` → `seon.task/ready`
- `frontier-rules` → `ready-rules`
- `unlanded-ends` → `unmerged`, with its meaning changed to "head ≠ pushed"

## 6. Phases (about 15 min each, red first; P1 and P2 are an Opus lane, the rest Sonnet)

| # | Slice (owned files) | Red proof |
|---|---|---|
| U0 | Schema and words. `:seon.task.attempt/pushed` (commit id, on `assignment-row`); `:seon.squash/conflict` entity with identity; `seon.message/message` → `:seon.cluster`; delete `:seon.squash/attempt`. Vocabulary rows: **push**, **pushed commit**, **ready**, **unmerged**. Files: `resources/seon/schemas/{seon.task,seon.squash,seon.message}.edn`, `docs/seon/architecture/vocabulary.md`. Platform `--test` | `seon.program-test/cluster-attributes-are-the-declared-cluster-entities` gains rows: `:seon.message/id` is in the set; `:seon.task.attempt/pushed` is in it |
| U1 | `push-tx` pure in the writer (`src/seon/cluster/squash.clj`, `test/seon/cluster/squash_test.clj`) | `…/a-repeated-push-adds-nothing` (collision 1→1 after a second push; the conflict row 6 stays 6; tx-data `[]`) · `…/a-push-of-an-advanced-branch-adds-only-its-new-delta` (count 1 → branch 3 → main 3, not 4) · `…/a-root-edit-after-capture-is-a-conflict-not-overwritten` (`seon.db/with` on a main edited after the head was captured: root's value stays, one conflict entity) · `…/a-conflict-keeps-its-three-values-and-commits` · `…/a-pushed-collision-stays-remapped` (an occurrence pushed twice holds main's eid both times) · cost row via `seon.profile/measure`: ≤ 8 ms warm in the writer for a ten-evaluation branch |
| U2 | `db<->flow` pushes (`src/seon/cluster/agent.clj` `unmerged`, `push!`; route wake on `:seon.cluster` attributes derived in `seon.cluster.wake/starting-attributes`; `test/seon/cluster/db_flow_test.clj`) | `…/a-running-agents-refinement-reaches-main-at-the-next-pass` (main holds the intent while the graph still runs; `pushed` = head) · `…/an-evaluation-without-tracked-facts-writes-nothing-on-main` (main basis unchanged) · `…/an-unpushed-branch-after-a-crash-is-pushed-once-by-the-prime` (write on the branch with no pass, then resume: exactly one main transaction) |
| U3 | Derived lifecycle; delete the marker and catches (`src/seon/cluster/agent.clj` `plan`, `apply-plan!`) | verify-astra's `…/an-interrupted-landing-finishes-on-the-next-pass` (throw after the push, before stop: the next pass stops the graph and deletes the branch) · `…/a-conflicted-end-keeps-its-values-until-root-decides` (branch kept while the conflict is open, deleted after it resolves) |
| U4 | Base retention at collection (`src/seon/cluster/registry.clj`, `test/seon/cluster/registry_test.clj`) | `…/a-pushed-commit-older-than-the-window-survives-collection` (`collect!` with a 0 ms window after untracked commits: the next push's base opens) |
| U5 | Messages pushed as records (`src/seon/cluster/agent.clj` mailbox, `test/seon/cluster/message_test.clj`) | `…/a-delivered-message-is-pushed-to-main-and-wakes-no-one` (main holds it by id; no agent is started or woken on main; a `keep?` copy is one entity) |
| U6 | Mechanical rename `frontier` → `ready` (`src/seon/task.clj`, its callers, tests) | the R2 plan tests stay green; the symbol `seon.task/frontier` resolves nowhere |

**Order:** U0 → U1 → U2 → (U3, U4, U5 by file) → U6. U3 depends on F (lane `p4-branch-writes`) for the recorded
fork commit. Until F lands, the initial base is `seon.cluster.source/merge-base`.

## 7. Risks

1. **The writer holds ~5 ms of reads per push.** That is acceptable at today's rates. Measure p99 writer latency
   with 10 pushing agents (U2 cost row).
2. **`changed-identities` scans tracked-attribute history.** That is O(store history) per push, 9.5k datoms now.
   The follow-up reads Datahike's per-attribute revisions or the since index. File it when the U1 cost row
   approaches its bound.
3. **`:seon.error.occurrence/last-at` is noHistory and `:root`.** Both sides advance it, so every concurrent repeat
   is a conflict. It needs a `:max` rule (new rule value) or `:theirs`. Pick in U1's table.
4. **Pushed messages on main.** If anything derives work from `:seon.message/to` on main (`agents-to-start`, the
   route's to-ref belt), a record would start an agent. U5's proof is red until those read only the recipient's
   world, which ties this to verify-astra #8.
5. **Conflicts go to root continuously, not once at the end.** A busy agent refining a task root also edits makes
   one conflict per push and coordinate. Identity includes `source-commit`, so each push names its own.

## 8. Unsure

- **Conflict delivery:** whether a conflict should be root's merge-request-shaped decision (#4's "runnable") or a
  problem task carrying the conflict entity. Recommended: the conflict entity hangs off the open merge request
  when there is one, else off a `:running` root problem task whose proof reads that the conflict is resolved.
- **Retention:** whether F's tag mechanism or a `reachable-extension` returning the pushed commits' keys is the
  smaller retention seam. Both cost nothing per push.
- **A tracked datom with no `db<->flow` listening:** a nested execution's branch route must reach the root
  roster's `db<->flow` (the same route `end-task!`'s end uses today). Verify for children at depth > 1.
