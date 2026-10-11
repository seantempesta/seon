---
title: "Hybrid: what an agent's world holds and where its writes go"
type: research
status: hybrid of plan-agent-world-opus.md (610add01d) and plan-agent-world-sol.md (619800483) over dig-agent-world.md (f1b42b836), revised in place to the owner's direction and answers of 2026-10-11 (independent agents; one copy-back at a successful end; live messages between agents; branch kept or deleted by a dial; error ends copy nothing and notify root; declared conflict rules); phases filed as tasks; design only
basis: HEAD 610add01d read by symbol (`seon.cluster.message/send!`, `seon.task/main`, `seon.turn/end-task-call`, `seon.context/held-task`, `seon.cluster.wake/route!`, `seon.db/transact-call`, `seon.cluster.source/merge-base`, `seon.schema`'s partition check, `seon.fault/record!`, `seon.task/delete-branch!`, `seon.cluster.registry/collect!`, `retention-cutoff`, `seon.maintenance/collect!`); `reference-code/datahike` `gc.cljc`, `versioning.cljc`; Opus's default probes (disposable branch, deleted) reused as evidence; `runtime_status` on default 2026-10-11 (`:faulted`, pre-existing: 2 open faults, 2 failed tests, cache 495 of 512 MB)
---

# What an agent's world holds and where its writes go

## 1. The design in one paragraph

**An agent is independent: its world holds one connection, its own branch, forked from main's immutable value at
launch; everything it does (evaluations, task refinements, child tasks, test evidence, recorded errors, its end) is
data on that branch, written by its own forms through the same writer inside its own Flow; it never calls a platform
function that reaches main or another branch.** Root's world is the same value over main, the only difference. Nothing
moves while an agent runs. **At its end, one point, the system copies back exactly the declared facts**: entity
schemas declare a third partition value, **`:seon.cluster`**, and `db<->flow` (holding main and the roster, woken by
the end datom through the existing route) runs the pure **`seon.cluster.pick/cherry-pick`** over the branch since its
fork point and transacts the result on main as **one plain identity-matched transaction** with provenance in tx
metadata (branch, end commit) — matched by identity attribute, never entity id (Opus measured ids diverging after a
fork: 60192 vs 60204), never `merge-db!`/`:parents` (§2). Program changes reach main only through the merge request
root works. Then the agent's Flow is stopped and its branch deleted (`d/delete-branch!`), so `gc-storage!` sweeps its
unique commits, index nodes and blobs at the next collection: **the agent's whole run costs main one transaction and
leaves the store nothing** unless `:seon.config.agent/keep-ended-branches?` keeps it for audit. **Messages are the one
live fact** (owner): a message is written on the sender's branch and `db<->flow` carries it, identity-matched, to main and
then to the recipient's branch along the existing `:seon.message/to` listen edge; the recipient's own route wakes it.
**An error end copies nothing**: the branch is kept, root is notified by a problem task and merges by hand through the same
pure function; an explicit `my.agent/retire-branch!` destroys it. **Conflicts** resolve by a rule declared on the entity
schema where one is known; otherwise root decides through a conflict problem task.

## 2. Point by point: agree, differ, choice

| Point | Opus | Sol | Choice and reason |
|---|---|---|---|
| One custody per world, root = same world over main; no main member; `task/main`, `main-connection`, `send!`'s lookup, agent-side `branch-connection` deleted | yes | yes | **agreed**; verified `seon.batch/world-members` has no main member; `send!` queries `:seon.agent/branch` then `db/branch-connection` |
| **When data moves** | continuously: a pick proc wakes on every cluster datom, up then down | continuously, coalesced per commit | **declared per entity kind** (owner): `:seon.cluster/moves :at-end` (task refinements, children, ends, occurrences, test evidence: one copy at a successful end) or `:live` (messages only: carried as written). No separate pick proc and no watermark: `db<->flow` already holds main and the roster and is woken by the route; "due" is derived by identity (on the source, absent on the target), so a lost wake or a crash re-derives at the next prime. The pure function is Opus's |
| Rule on entity schemas | third partition value `:seon.cluster` | `:seon.data/movement`, `:seon.data/merge` properties | **Opus**: `:seon.program/partition` exists and `seon.schema`'s check already refuses an identity-bearing entity without `#{:seon.program :seon.data}`; one more value is accretion; with no down-pick no recipient edge is needed; merge semantics need no property (appended kinds have no base; keyed values three-way) |
| Transport primitive | plain `transact!` + tx-meta provenance; never `merge!` | `transact!` with `:parents` (Datahike merge commit) | **Opus**, two verified grounds: `datahike.gc/reachable-in-branch` follows `datahike/parents` of in-range commits, so a parented copy would pin the deleted branch's lineage to main and reclaim nothing (fatal to the owner's goal); `compare-branch`'s base is `branch-base-t` today and the attempt's fork point after H8, so lineage adds nothing the program merge uses |
| Fork point | the attempt's own main commit (unsure for successors) | historical fork + import of the live assignment | **main's head after the attempt write** (R1's `attempt-tx`, hybrid H7): the branch inherits its task, attempt and agent row and needs no import; a successor forks main too, since its predecessor's branch is deleted and its program reaches main only by root's merge. Sol's import is not needed |
| Conflicts | three-way per `[identity attribute]`, keep main, problem task by identity | same, plus three cautions | **both**; base = main's value at the fork point (`as-of` the fork commit on the branch's own lineage); Sol's cautions (basis advance ≠ acceptance; retractions participate; no negative-datom replay) are proof rows |
| Messages | up to main, down along `:seon.message/to` | same, wake after durable target write | **Opus's mechanism, Sol's two assertions** (owner: "messages should work between agents"): sender's branch → main → recipient's open attempt branch, each leg one identity-matched plain transaction; the recipient is woken only after its branch holds the message; root's are one leg each way (§4) |
| Agent row (Q3) | id, branch, attempt main's; runtime bookkeeping on the branch | roster authoritative on main | **agreed**: `:seon.data` on main, inherited at fork; `answered-t`, current namespace, disposition stay on the branch and die with it; status reads a running branch on purpose; `:seon.agent/admission-closed-tx` writes go |
| Recorded errors | `:seon.error.occurrence` is `:seon.cluster`; counts add | problem occurrence → main | **both**: `record!` writes once, on the world's connection (its "writes twice" edge case goes); the copy adds `count` by the declared rule `:seon.cluster/conflict :add` |
| Conflict policy | keep main, problem task | same | **owner Q4**: a rule declared on the entity schema, `:seon.cluster/conflict` ∈ `#{:root :theirs :ours :add}`, default `:root` (a problem task); `:add` on the occurrence count; `:theirs` is available for a kind whose branch is the authority. Appended kinds (messages, children, ends) have no base and never conflict |
| Branch lifetime | delete on close | delete after merge | **owner Q2/Q3/Q5**: a successful end copies back, then the branch is deleted when its merge request closes, or kept when `:seon.config.agent/keep-ended-branches?` is true (audit); an error end copies nothing, keeps the branch and writes a problem task (root merges by hand); `my.agent/retire-branch!` is the explicit destroy |
| Test members | unsure | `d/with` inside one run attempt; `settlement-connection`'s escape goes | **Sol** (round-1 Q1): members write nothing durable; the run's evidence is `:seon.program` on the run branch; the run's end is copied |
| Two-custody fallback | only if latency breaks a proof | contradicts the owner | **not built**; with no mid-run movement there is no latency to fall back from |

## 3. Each fact kind: partition, home, writer, what the end copies

| Fact | Partition | Written on | By | At the end |
|---|---|---|---|---|
| agent row, attempt row (`:seon.task/assignment`, `/attempt-of`), task, intents, config, roster | `:seon.data` (attempt: `:seon.cluster`) | main, `db<->flow` in root's world (`attempt-tx`); inherited by the fork | root's world | the attempt's end (`:seon.task.attempt/end`, `/reason`, `/target`, `/commit`) copied |
| evaluations, calls, turns, answered `t`, current namespace, live-result handles | `:seon.data` | the branch | the recording transaction | deleted with the branch (history answers while the window lasts) |
| task refinement (intent, must-read, evidence, stage decision); child tasks, `needs` | `:seon.cluster` (`seon.task/task`) | the branch (`seon.task/written` on custody; `my.task/*`) | the agent | copied, three-way per attribute against the fork point; new children have no base |
| merge request (`seon.task/merge-request-row`) | `:seon.cluster` | main, by `db<->flow` on a copied `:done` end | root's world | is the copy's consequence |
| message | `:seon.cluster`, `:seon.cluster/moves :live` (`seon.message/message`) | the sender's branch (`send!` on custody) | the sender | carried live: → main → the recipient's branch; root's: one leg; absent from the end copy (already on main) |
| recorded error (`seon.error.occurrence`) | `:seon.cluster` | the branch of the world it happened in | the entrant boundary | copied; `count` adds |
| program (functions, tests, schemas, namespaces), test evidence keyed by digests | `:seon.program` | the branch | `defn`, `seon.test/run` | root's merge (`accept-merge!`) from the end commit, before the branch is deleted |
| copy provenance (`:seon.pick/source-branch`, `/source-commit`) | tx metadata (declared like `:seon.db/user`) | the target of each copy | the copy | is the record; a carried message's provenance excludes it from the up leg |

## 4. The mechanism, live messages, cost and storage

**`seon.cluster.pick/cherry-pick`** `[source-db base-db target-db projection] → {:tx-data … :conflicts …}`, pure:
reads `seon.program/cluster-attributes` (sibling of `program-attributes`, same `projection-cache-value` pattern), the
source's datoms since the fork for those attributes (`d/since` on the branch; the fork point `t` is main's, repeated in
the branch's own lineage), groups by entity, translates to identities (`seon.db/identity-attributes`; components nested
with their owner; refs as lookup refs), classifies per `[identity attribute]` against base (branch `as-of` the fork) and
ours (main now): unchanged → nothing; only theirs → assert or retract; only ours → keep; both and different → the entity
schema's `:seon.cluster/conflict`: `:theirs` assert, `:ours` keep, `:add` a `[:db.fn/call …]` summing, `:root` keep ours
plus a conflict row `{entity attribute base ours theirs}`. A `selection` argument restricts it to given identities or to
`:seon.cluster/moves :live` kinds.
**The end.** `seon.turn/end-task-call` writes the end on the branch in the agent's last transaction. `:seon.task.attempt/end`
is a listened datom; the branch's route (every hosted connection is routed today) offers `db<->flow` a wake; its
pass finds attempts whose branch holds an end main lacks. **A successful end (`:done`)**: `cherry-pick` of the `:at-end`
kinds → one `seon.db/transact!` on main with `:tx-meta` provenance → one problem task per `:root` conflict by identity
`seon.id/id [:pick-conflict entity attribute]` (a repeat writes nothing) → the merge request (root works it on main; its
diff reads the end commit) → `flow/stop`, `join-graph!`, release the SCI context; when the merge request closes, `seon.task/delete-branch!`
(detached io work; it already takes the roster permit) unless `:seon.config.agent/keep-ended-branches?` (new dial, default
false) keeps it for audit. **An error end** (`:given-back`, `:capped`, `:stalled`, `:cancelled`): nothing is copied, the
graph stops, the branch stays, and runtime-follows-db §5's existing problem class (attempt ended given-back/capped/stalled)
is written on main by `db<->flow` carrying the end, reason and branch; root merges by hand at its REPL with the same
function (`(seon.cluster.pick/copy! branch selection)`, offered as an operation of the problem task) and destroys the
branch explicitly (`my.agent/retire-branch!`). Idempotent by identity: a crash after the copy re-runs an empty copy; one
before it re-derives from the branch. Root's end is the identity (its branch is main).

**Live messages, the smallest version.** `send!` writes the message on the sender's own connection (no recipient lookup).
`:seon.message/to` is already `:seon.wake/listen true`, so the sender's route offers `db<->flow` the same payload-free
wake. Its pass derives due messages by identity, no watermark: (up) on each routed branch, messages whose `:seon.message/id`
main lacks → `cherry-pick` restricted to them → one main transaction with provenance; (down) on main, messages whose `to`
names an agent with an open attempt on another branch and whose id that branch lacks → one branch transaction; the
recipient's existing route then wakes it, so it is woken only after its branch holds the message (Sol's assertion). Root
as sender writes main, so only the down leg runs; root as recipient only the up leg; an addressed agent with no open
attempt leaves the message on main for its next attempt's fork (never a silent loss; an unknown id is the existing refusal
on main). Down-carried messages carry provenance naming main, so the up leg excludes them (a returned message never
becomes a fresh one). A message is also a `:seon.cluster` fact, but already on main at the end: the end copy skips `:live`
kinds. Nothing else moves live: a running child sees a parent's refinement at its next attempt, by design.

**Cost, against the plans.** End copy: one derivation (Opus measured 3.2 ms cold, `d/with` 2.3 ms) and one main
transaction (raw 13 ms, `seon.db/transact!` 15–25 ms) per attempt, proportional to its changed cluster datoms, plus
`delete-branch!`. A message agent → agent: two transactions (~40–60 ms end to end) against ~20 ms for today's direct write
on the recipient's branch; agent → root or root → agent: one (~20 ms); an eval request writing no message costs the
matcher lookup only (~0.2 ms per report). Opus's continuous pick paid the same per message but also per refinement, end
and error, plus a proc and a watermark; Sol's three commits per message plus a durable basis. This design moves live only
what must be live and pays the rest once per attempt, with no new proc, watermark or attribute: due is derived by identity.

**Storage reclaimed per agent, and how.** `d/delete-branch!` removes the branch from the store's known branches; the
next `seon.cluster.registry/collect!` (`gc-storage!`, mark from every roster branch head through commits newer than
`retention-cutoff` under `:seon.config.db/snapshot-window-ms`, sweep before the safe point) finds nothing reaching the
deleted branch's commits, so everything unique to it is swept: every commit record its transactions wrote, the index
root and path nodes those transactions created (shared nodes stay with main), its history indexes, and the konserve
blobs only its facts named (`seon.maintenance/collect!` verifies retained branches and blobs together). What survives is
exactly the one copied transaction on main and what root merged. The per-agent number is a red cost row (P2): the
store's key count and bytes after `collect!` with and without a deleted ten-evaluation attempt differ by the copied
transaction only. Today nothing is reclaimed while branches outlive their agents.

**Composition.** `start-flow!` (H7): `fork!` from the attempt's main commit, world, one SCI fork, graph; no import.
`db<->flow` (R1): the end copy, the message legs and the error problem task are three more actions of `plan`. Test
members: `d/with` on the run branch; `cherry-pick` is tested on values with `d/with` targets. Q3: the agent row is
main's; the branch inherits it and nothing writes it back.

## 5. Reused, changed, deleted (by symbol)

**Reused.** `seon.turn.world/world` (one connection); `seon.call-preparation/supply`, `supply-from-world`;
`seon.batch/world-members` (no new member); `seon.cluster.wake/route!`; `seon.program/program-attributes`'s pattern,
`three-way`'s classification; `seon.db/transact!` (`:tx-meta`), `identity-attributes`, `with`, `since`, `as-of`;
`seon.schema`'s partition check; `seon.id/id`; `seon.cluster.registry/fork!`, `collect!`; `seon.task/delete-branch!`;
`datahike.api/delete-branch!`, `gc-storage!`; `seon.flow/start-flow!` (H7); R1's `db<->flow` and `plan`.

**Changed.** Entity schemas `seon.message/message`, `seon.task/task`, `assignment-row`, `seon.error.occurrence/occurrence`:
`:seon.program/partition :seon.cluster`; the check accepts `:seon.cluster` and requires an identity attribute for it (a
declaration rule, not a runtime check). `seon.program/cluster-attributes` new. `send!`: its connection only. `seon.turn/end-task!`: `end-task-call` and `end-summary-tx` on custody and nothing else. `seon.task/written`,
`assign!`, `adopt-tasks-tx`: the given connection. `seon.context/held-task`, `ended`, `end-summary-tx`,
`current-state`: the world's database only. `seon.turn/admission-closed?`: the end on the attempt. `seon.fault/record!`:
one write. `seon.test/run`: members under `d/with`. `seon.flow/plan`: the end action (copy, merge request, stop, delete or keep), the message legs, the error problem task.
New schema properties `:seon.cluster/moves` and `:seon.cluster/conflict`; new dial `:seon.config.agent/keep-ended-branches?`.
Vocabulary rows first: **cherry-pick**, **`:seon.cluster`**, **end copy**, **live kind**.

**Deleted.** `seon.task/main`, `main-connection`, `written`'s indirection; `send!`'s recipient lookup and
`:seon.message/unknown-recipient` (the refusal moves to main's down leg); `seon.turn/closed-on?`'s
main half and every `(task/main database)` in `task-end`, `end-message`, `end-merge-request`, `next-agent-work`,
`task-proofs`; the four in `seon.context`; `:seon.agent/admission-closed-tx` writes; `record!`'s cluster copy;
`seon.turn.world/settlement-connection`'s requester escape; agent-side `seon.db/branch-connection`; the end's follow-ups
in the agent's own turn (`program-request-merge!`, `land-end!`, the agent calling `task/delete-branch!`). Not built:
Opus's separate `pick-step` and watermark; Sol's `move-data!`, `movement-tx`, `:seon.data/movement`, `:seon.data/merge`.

## 6. Phases (about fifteen minutes each, one owner, red first; filed as tasks)

| # | Slice (owned files) | Red proof and assertion |
|---|---|---|
| P0 | **the partition value and its two rules.** `:seon.cluster` on the four schemas with `:seon.cluster/moves` (`:live` on message) and `:seon.cluster/conflict` (`:add` on the occurrence count; default `:root`), `seon.program/cluster-attributes` (answering both properties per attribute), the check extended, vocabulary rows (`resources/seon/schemas/{seon.message,seon.task,seon.error.occurrence}.edn`, `src/seon/program.cljc`, `src/seon/schema.clj`, `docs/seon/architecture/vocabulary.md`, `test/seon/program_test.clj`); platform slice, `--test` | `seon.program-test/cluster-attributes-are-the-declared-cluster-entities`: on the packaged projection `cluster-attributes` ⊇ `#{:seon.message/to :seon.task.attempt/end :seon.task/intent :seon.error.occurrence/count}` and is disjoint from `program-attributes`; `(cluster-attributes projection :seon.cluster/moves :live)` is exactly the message's attributes; a fixture `:seon.cluster` schema with no identity refuses with `:seon.schema/member :seon.db/identity`; derivation under 5 ms warm |
| P1 | **the pure copy.** `seon.cluster.pick/cherry-pick` (`src/seon/cluster/pick.clj`, `test/seon/cluster/pick_test.clj`) | table on values, `d/with` targets: `seon.cluster.pick-test/an-attempts-cluster-facts-copy-onto-main-by-identity` (ids differ; main pulls the end, the refined intent and a child task by identity); `…/a-value-changed-on-both-sides-resolves-by-the-declared-rule` (table: `:root` keeps ours and yields one conflict row with three values while the entity's other attribute still copies; `:theirs` asserts; `:add` sums); `…/program-and-runtime-facts-never-copy` (a `defn`, an evaluation and a turn yield empty tx-data); `…/a-copy-of-an-unchanged-branch-is-empty`; `…/a-retraction-on-the-branch-retracts-on-an-unchanged-main-only`; cost row: a ten-evaluation attempt under 10 ms / 5 MB through `seon.profile/measure` |
| P2 | **the end copies; the branch dies or is kept; an error end notifies root.** `db<->flow`'s end action: copy of `:at-end` kinds, provenance, `:root` conflicts as problem tasks, merge request on `:done`, stop; delete when the merge request closes unless the new dial `:seon.config.agent/keep-ended-branches?` (default false; `resources/seon/schemas/seon.config.agent.edn`) keeps it; an error end copies nothing, keeps the branch and writes the §5 problem task offering `(seon.cluster.pick/copy! branch selection)`; `my.agent/retire-branch!` is the destroy (`src/seon/flow.clj`, `src/seon/turn.clj` `end-task!` trimmed, `test/seon/flow_test.clj`); Opus lane (one path) | `seon.flow-test/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` (before the pass main lacks the end; after it one main transaction carries end, refinements and provenance, no message; after the merge request closes the roster no longer lists the branch; with the dial true it does); `…/an-error-end-copies-nothing-and-writes-roots-problem-task` (main holds no refinement; the branch stays; one problem task by identity names end, reason and branch; root's by-hand `copy!` then equals the success path's copy); `…/a-crash-between-the-end-and-the-copy-copies-once`; `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` (store key count and bytes after `collect!` equal the baseline plus the copied transactions; cites `gc.cljc` reachable-in-branch) |
| P3 | **messages on custody.** `send!` writes its connection only; the lookup and `unknown-recipient` go (`src/seon/cluster/message.clj`, `test/seon/cluster/message_test.clj`) | `seon.cluster.message-test/send-writes-only-the-senders-branch` (the message is on the sender's branch, absent from main and the recipient's branch until `db<->flow`'s pass; `seon.profile/calls-of` of `seon.db/branch-connection` zero; `send!` under 25 ms) |
| P7 | **live messages between running agents.** `db<->flow`'s message legs: up by identity, down along `:seon.message/to` to the open attempt's branch, provenance, no watermark (`src/seon/flow.clj`, `src/seon/cluster/pick.clj` the `:live` selection, `test/seon/flow_test.clj`); Opus lane; after P1 and P3 | `seon.flow-test/a-message-between-running-agents-lands-on-the-recipients-branch-before-its-wake` (after one pass main holds it once, the recipient's branch holds it, the recipient's mailbox was offered only after that write; two transactions, under 60 ms warm); `…/a-message-to-root-and-from-root-each-cost-one-transaction`; `…/a-carried-message-is-never-carried-back` (a second pass transacts nothing on either head); `…/a-message-to-an-agent-with-no-open-attempt-waits-on-main` (its next attempt's fork holds it) |
| P4 | **tasks and context on custody.** Delete `seon.task/main`, `main-connection`; `written`, `assign!`, `adopt-tasks-tx`, the context readers use the world's connection (`src/seon/task.clj`, `src/seon/context.clj`, `src/seon/turn.clj` remaining reads, `test/seon/task_test.clj`) | `seon.task-test/a-refinement-on-a-branch-reaches-main-only-at-the-end` (main's intent unchanged while the attempt runs, equal after the end copy; a root edit of the same attribute meanwhile → main keeps root's, exactly one problem task); `seon.profile/calls-of` of `seon.task/main` zero (deleted) |
| P5 | **recorded errors once.** Occurrence `:seon.cluster`; `record!` one write; `count` adds (`src/seon/fault.clj`, `src/seon/cluster/pick.clj` the one `:db.fn/call`, `test/seon/fault_test.clj`) | `seon.fault-test/a-fault-on-a-branch-is-recorded-once-there-and-counted-on-main-at-the-end` (one transaction on the branch; after the copy main holds the signature with count 1; a second attempt's same signature → 2, never an overwrite) |
| P6 | **root differs only by its world; members `d/with`.** Delete `settlement-connection`'s escape; `seon.test/run` members under `d/with` (`src/seon/turn/world.clj`, `src/seon/test.clj`, `test/seon/test_test.clj`, `test/seon/cluster/pick_test.clj`) | `seon.cluster.pick-test/root-and-an-agent-differ-only-by-their-worlds-connection` (the same `my.task/refine!` form through the one path: over a fixture main it writes main; over a fixture branch it writes the branch and equals main's after the end copy); `seon.test-test/members-return-reports-without-writing-another-world` (fixture writes stay in each `d/with`; the run branch's and default's heads unchanged) |

**Order.** P0 → P1 → (P3, P4, P5 in parallel by file) → P2 and P7 (after R1's `db<->flow` and S3's end on the attempt;
until R1 lands, `end-task!`'s existing follow-up calls the copy once and the message legs run from `armer-step`'s pass) → P6.
Lanes: P0 platform (`--test`); P1, P3–P6 Sonnet on clear specs; P2 and P7 Opus (the one path). Measure the end copy and a
message round trip on default before P3 lands; main's writer at twenty agents messaging at once is the first measurement.

## 7. Risks

1. A branch deleted before root merged its program loses the program: deletion waits for the merge request to close;
   error ends keep their branch until root destroys it (retention window applies to roster branches only, which
   `retention-cutoff` already guarantees).
5. Main's writer carries one transaction per message leg; bursts coalesce per pass; measure at twenty agents.
2. A long attempt's conflict surface grows with its length (main moves meanwhile): three-way per attribute names it;
   the policy is Q4.
3. A running child sees a parent's refinement only at its next attempt (messages are the only live kind): by design.
4. A ref from a cluster entity to an identity-less, non-component entity cannot copy: refused at publication (P0).

## 8. Owner answers (2026-10-11) and what remains

Decided: **messages work between running agents** (the `:live` kind, §4); everything else is copied once at a
**successful end**, after which the branch is **deleted when its merge request closes** unless the dial
`:seon.config.agent/keep-ended-branches?` keeps it for audit; an **error end copies nothing**, keeps the branch and
notifies root by a problem task, who merges by hand and destroys explicitly; **conflicts** resolve by the entity schema's
`:seon.cluster/conflict` where a rule is known, else root decides. Agent runtime bookkeeping dies with the branch.

Remaining, small: (1) should any kind besides messages be `:live` (e.g. a task refinement so a running child sees its
parent's edit)? Recommended: no, until a chain run shows the need. (2) Which task fields, if any, take `:theirs` (the
holder's refinement wins over root's concurrent edit)? Recommended: none at first; every task conflict goes to root.
