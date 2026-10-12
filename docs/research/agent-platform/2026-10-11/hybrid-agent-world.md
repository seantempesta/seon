---
title: "Hybrid: what an agent holds and where its writes go"
type: research
status: hybrid of `plan-agent-world-opus.md` (610add01d) and `plan-agent-world-sol.md` (619800483) over `dig-agent-world.md` (f1b42b836), revised in place to the owner's direction and answers of 2026-10-11 (independent agents; the copy-back is a squash merge over store-wide unique entity ids (one in-process counter per store), identity matching only for natural-identity collisions; one copy-back at a successful end; messages as Flow channel deliveries written by the recipient; branch kept or deleted by a dial; error ends copy nothing and notify root; declared conflict rules); phases filed as tasks; design only
basis: HEAD 610add01d read by symbol (`seon.cluster.message/send!`, `seon.task/main`, `seon.turn/end-task-call`, `seon.context/held-task`, `seon.cluster.wake/route!`, `seon.db/transact-call`, `seon.cluster.source/merge-base`, `seon.schema`'s partition check, `seon.fault/record!`, `seon.task/delete-branch!`, `seon.cluster.registry/collect!`, `retention-cutoff`, `seon.maintenance/collect!`); `reference-code/datahike` `gc.cljc`, `versioning.cljc`; Opus's default probes (disposable branch, deleted) reused as evidence; `runtime_status` on default 2026-10-11 (`:faulted`, pre-existing: 2 open errors, 2 failed tests, cache 495 of 512 MB)
---

# What an agent holds and where its writes go

Phase numbering: this document's phases are cited **AW0–AW7** (renamed 2026-10-12; they were P0–P7 and collided with `hybrid-step-semantics.md`'s P1–P10).

## 1. The design in one paragraph

**An agent is independent: it holds one connection, its own branch, forked from main's immutable value at
launch; everything it does (evaluations, task refinements, child tasks, test evidence, recorded errors, its end) is
data on that branch, written by its own forms through the same writer inside its own Flow; it never calls a platform
function that reaches main or another branch.** Root's is the same value over main, the only difference. Nothing
moves while an agent runs. **At its end, one point, the system copies back exactly the declared facts**: entity
schemas declare a third partition value, **`:seon.cluster`**, and `db<->flow` (holding main and the registry, woken by
the end datom through the existing route) runs the pure **`seon.cluster.squash/squash-merge`** over the branch since its
fork point and transacts the result on main as **one plain identity-matched transaction** with provenance in tx
metadata (branch, end commit): git's common case for a finished branch, a **squash merge** (a three-way diff from the
merge base, one commit on main, no parent link to the branch), filtered to the declared kinds — keyed by entity id, which **one store-wide counter** makes unique across
branches from birth (AW1a; today both lineages count up from the fork's `max-eid`, Opus measured 60192 vs 60204), so a
branch-born entity's datoms replay with its eid unchanged and identity matching remains only where two branches minted
the same `:db.unique/identity` value (§2); never `merge-db!`/`:parents` (§2). Program changes reach main only through the merge request
root works. Then the agent's Flow is stopped and its branch deleted (`d/delete-branch!`), so `gc-storage!` sweeps its
unique commits, index nodes and blobs at the next collection: **the agent's whole run costs main one transaction and
leaves the store nothing** unless `:seon.config.agent/keep-ended-branches?` keeps it for audit. **Messages are channels, not stored transport**
(owner): sending is a Flow `offer!` of the message value onto the recipient agent's inbox channel found in the registry;
nothing is written in transit and nothing relays through main; **the recipient's own Flow writes the message into its own
branch on result**, so its context renders it, and the sender keeps a copy only if it declares so. An undeliverable
message is answered to the sender as an error value; in-flight loss on a crash is accepted and resends are idempotent.
**An error end copies nothing**: the branch is kept, root is notified by a problem task and merges by hand through the same
pure function; an explicit `my.agent/retire-branch!` destroys it. **Conflicts** resolve by a rule declared on the entity
schema where one is known; otherwise root decides through a conflict problem task.

## 2. Point by point: agree, differ, choice

| Point | Opus | Sol | Choice and reason |
|---|---|---|---|
| One checked-out branch per agent, root = the same over main; no main member; `task/main`, `main-connection`, `send!`'s lookup, agent-side `branch-connection` deleted | yes | yes | **agreed**; verified `seon.batch/world-members` has no main member; `send!` queries `:seon.agent/branch` then `db/branch-connection` |
| **When data moves** | continuously: a pick proc wakes on every cluster datom, up then down | continuously, coalesced per commit | **once, at a successful end** (owner): task refinements, children, the end, occurrences. No pick proc, no watermark, nothing live through the database: `db<->flow` already holds main and the registry and is woken by the end datom through the route; "due" is derived by identity (an end on the branch that main lacks), so a lost wake or a crash re-derives at the next prime. The pure function is Opus's |
| Rule on entity schemas | third partition value `:seon.cluster` | `:seon.data/movement`, `:seon.data/merge` properties | **Opus**: `:seon.program/partition` exists and `seon.schema`'s check already refuses an identity-bearing entity without `#{:seon.program :seon.data}`; one more value is accretion; with no down-pick no recipient edge is needed; merge semantics need no property (appended kinds have no base; keyed values three-way) |
| Transport primitive | plain `transact!` + tx-meta provenance; never `merge!` | `transact!` with `:parents` (Datahike merge commit) | **Opus**, two verified grounds: `datahike.gc/reachable-in-branch` follows `datahike/parents` of in-range commits, so a parented copy would pin the deleted branch's lineage to main and reclaim nothing (fatal to the owner's goal); `compare-branch`'s base is `branch-base-t` today and the attempt's fork point after H8, so lineage adds nothing the program merge uses |
| **Entity ids across branches** (owner, 2026-10-11: why not one global counter, incremented as ids are handed out?) | partition bits per branch in the fork (`eid-ranges-opus.md`) | store-issued persisted blocks in the fork (`eid-ranges-sol.md`) | **The owner's counter (AW1a): one in-process `AtomicLong` per open store, no blocks, nothing persisted.** Valid because one process root holds the store's lifetime lock, so one process allocates. Seeded when the store is first connected in a process from 1 + the max of every registry branch head's `max-eid` (each committed db value already stores it; `:branches` lists the heads), so a restart needs no extra state and uncommitted allocations are harmless gaps. **Verified in the fork (`b2ffa4af`)**: every record Datahike accepts as a head carries `:max-eid` as a top-level field (`datahike.writing/stored-db?` requires it; `db->stored` writes it), and a head is read by `k/get` of the branch key then `datahike.writing/resolve-head` (a compact head names its commit record): the seed is O(branches) small record reads, no index or datom loaded, once per process. **The change, about 60 lines**: `datahike.db.transaction/next-eid` takes the report and allocates one past both `max-eid` and the report's `eid-floor` (the last id the allocator reserved when the writer began the transaction); its five call sites (`entity-map->op-vec` ×2, `transact-tx-data` ×2, `transact-entities-directly` ×1) pass the report they hold; `datahike.writing/with-store-eids` hands `datahike.core/with` the floor and then reserves the db-after's `max-eid` by `compareAndSet` from it, rerunning the transaction (a pure function of the writer's db) above the new floor when another branch reserved meanwhile, so a refused or retried transaction spends no ids (as committed: plain `incrementAndGet` burned ids on refused transactions and broke Datahike's exact-numbering tests); a per-store registry (one `AtomicLong` keyed by the store id `gc-guard` uses, created at first `connect`, removed by `delete-database`); an explicit eid below `tx0` above the counter advances it as `advance-max-eid` does (observed, never refused: Datahike's own tests transact small explicit eids on fresh dbs); an id above the current transaction (unissued, or above `emax`) refuses, while an issued transaction id stays accepted because it names that transaction's metadata (Datahike's valid-time and tx-meta writes annotate earlier transactions). For a single-branch store the counter equals that branch's `max-eid` at every step, so Datahike's tests see identical numbering; only branch tests can differ. **Prove**: `clojure -M:test -m kaocha.runner :clj-pss` in `reference-code/datahike` (952 tests at H2) green, then the fork bump as a platform slice with `--test seon.db-test`, `--test seon.cluster.registry-test`, `--test seon.cluster.source-test`. **Lurking problems checked**: an old record without `:max-eid` is not a head; a registry branch with a missing head raises `:head-commit-missing` (corrupt either way); history, as-of and since values delegate `-max-eid` to their origin and cannot be written; imports allocate through the same call site; `force-branch!` to an older commit lowers that branch's `max-eid` only, never the counter; the `tx0` ceiling is 2^29 ids for the store's life (default holds ~60k) with a loud refusal. Nothing found makes the counter wrong; if something did, the next simplest correct option is Sol's persisted blocks. Allocation ~10 ns, no I/O; `max-eid` keeps its meaning as the branch's observed high-water mark (`advance-max-eid`, `init-max-eid`, the planner's estimates, which now see a sparse id space: a measured follow-up, not a blocker) and no longer feeds allocation; speculative `datahike.core/with` and a db with no store keep the local `(inc max-eid)`, since a speculation never persists and must not burn the 2^29 ids; eids stay below `tx0`, so no scan bound, `emax`, temporal or Roaring change, and tx ids are untouched. **Reuse after restart**: an eid that lived only on a since-deleted branch (runtime facts, never squashed) can be reissued; within the retention window a by-commit read of the deleted lineage sees that number meaning something else there. It does not matter: an eid is meaningful only within one db value, nothing joins across lineages by eid, and the invariant the squash needs is exactly the one the seed gives (unique among every entity reachable from the registry's heads, which both the branch and main are at squash time); a store-wide highest-ever mark would need a persisted write and buys only cross-lineage comparability nobody uses. **The mutable cell**: the store's eid allocator, one counter per open store in the fork's per-store map keyed by store id (the registry for that scope), whose only operation is increment and whose value is rederived from committed heads on open: the same class as Datahike's own writer state and the profile counters; it goes in the ledger §3.19 list by that name. Blocks are declined: they persist a counter and a block per head for a guarantee the lock already gives; partition bits are declined: they widen `e` past 31 bits and touch every scan bound for the same gain. What AW1 loses: identity translation of every entity and ref (lookup refs, component nesting, tempids), the AW0 rule that every `:seon.cluster` entity carries an identity (an identity-less component copies by eid), risk 4. What stays: selection by cluster attribute since the merge base, three-way per `[e a]` (fork-inherited entities share eids already), the conflict rules, and a **collision remap** for a new entity whose `:db.unique/identity` value exists on main (Datahike refuses an explicit eid carrying another entity's unique value, `:transact/unique`, instead of upserting it: Sol's probe), so that eid and every ref to it become main's and the attribute resolves by rule (`:add` on the count). The 31-bit hidden-eid bug is guarded at the producer (`seon.db/an-explicit-eid-outside-the-allocators-range-is-refused`) |
| Fork point | the attempt's own main commit (unsure for successors) | historical fork + import of the live assignment | **main's head after the attempt write** (R1's `attempt-tx`, hybrid H7): the branch inherits its task, attempt and agent entity and needs no import; a successor forks main too, since its predecessor's branch is deleted and its program reaches main only by root's merge. Sol's import is not needed |
| Conflicts | three-way per `[identity attribute]`, keep main, problem task by identity | same, plus three cautions | **both**; base = main's value at the fork point (`as-of` the fork commit on the branch's own lineage); Sol's cautions (basis advance ≠ acceptance; retractions participate; no negative-datom replay) are proof entries |
| Messages | up to main, down along `:seon.message/to` | same, wake after durable target write | **neither** (owner: "messages are channels"): the message value travels on the recipient's inbox channel (Flow owns transport, channels are ephemeral) and is written once, by the recipient's own mailbox proc on its own branch; no database leg, no relay through main, no second writer on anyone's branch (§4) |
| Agent entity (Q3) | id, branch, attempt main's; runtime bookkeeping on the branch | registry authoritative on main | **agreed**: `:seon.data` on main, inherited at fork; `answered-t`, current namespace, disposition stay on the branch and die with it; status reads a running branch on purpose; `:seon.agent/admission-closed-tx` writes go |
| Recorded errors | `:seon.error.occurrence` is `:seon.cluster`; counts add | problem occurrence → main | **both**: `record!` writes once, on the agent's connection (its "writes twice" edge case goes); the copy adds `count` by the declared rule `:seon.cluster/conflict :add` |
| Conflict policy | keep main, problem task | same | **owner Q4**: a rule declared on the entity schema, `:seon.cluster/conflict` ∈ `#{:root :theirs :ours :add}`, default `:root` (a problem task); `:add` on the occurrence count; `:theirs` is available for a kind whose branch is the authority. Appended kinds (children, ends) have no base and never conflict |
| Branch lifetime | delete on close | delete after merge | **owner Q2/Q3/Q5**: a successful end copies back, then the branch is deleted when its merge request closes, or kept when `:seon.config.agent/keep-ended-branches?` is true (audit); an error end copies nothing, keeps the branch and writes a problem task (root merges by hand); `my.agent/retire-branch!` is the explicit destroy |
| Test vars | unsure | `d/with` inside one run attempt; `settlement-connection`'s escape goes | **Sol** (round-1 Q1): members write nothing durable; the run's evidence is `:seon.program` on the run branch; the run's end is copied |
| Two-checked-out branch fallback | only if latency breaks a proof | contradicts the owner | **not built**; with no mid-run movement there is no latency to fall back from |

## 3. Each fact kind: partition, home, writer, what the end copies

| Fact | Partition | Written on | By | At the end |
|---|---|---|---|---|
| agent entity, attempt entity (`:seon.task/assignment`, `/attempt-of`), task, intents, config, registry | `:seon.data` (attempt: `:seon.cluster`) | main, `db<->flow` in root's context (`attempt-tx`); inherited by the fork | root's bindings | the attempt's end (`:seon.task.attempt/end`, `/reason`, `/target`, `/commit`) copied |
| evaluations, calls, turns, answered `t`, current namespace, live-result handles | `:seon.data` | the branch | the recording transaction | deleted with the branch (history answers while the window lasts) |
| task refinement (intent, must-read, evidence, stage decision); child tasks, `needs` | `:seon.cluster` (`seon.task/task`) | the branch (`seon.task/written` on checked-out branch; `my.task/*`) | the agent | copied, three-way per attribute against the fork point; new children have no base |
| merge request (`seon.task/merge-request-row`) | `:seon.cluster` | main, by `db<->flow` on a copied `:done` end | root's context | is the copy's consequence |
| message | `:seon.data` (`seon.message/message`) | the recipient's branch, on result (`seon.cluster.message/send-call` in its mailbox proc); the sender's branch too only when the send declares `:my.message/keep? true` | the recipient (and the sender, if kept) | never; dies with the branch like any runtime fact |
| recorded error (`seon.error.occurrence`) | `:seon.cluster` | the branch of the agent it happened in | the entrant boundary | copied; `count` adds |
| program (functions, tests, schemas, namespaces), test evidence keyed by digests | `:seon.program` | the branch | `defn`, `seon.test/run` | root's merge (`accept-merge!`) from the end commit, before the branch is deleted |
| copy provenance (`:seon.squash/source-branch`, `/source-commit`) | tx metadata (declared like `:seon.db/user`) | main | the end copy | is the record |

## 4. The mechanism, messages as channels, cost and storage

**`seon.cluster.squash/squash-merge`** `[source-db base-db target-db projection] → {:tx-data … :conflicts …}`, pure, git's
`merge --squash` over data (base = the merge base, theirs = the branch head, ours = main now; one commit on main, no
parent link, so the deleted branch stays collectable). It replays each branch-born entity's datoms with its eid unchanged (AW1a: one counter per store, so eids are unique from birth) and
remaps only a new entity whose `:db.unique/identity` value already exists on main (the eid and every ref to it become
main's). It reuses the merge-request machinery rather than growing a
second comparison: the merge base comes from `seon.cluster.source/merge-base` and `branch-base-t` (made public; the
attempt's fork, advanced per merge, read on the branch's own lineage because tx numbers collide across lineages), and
`seon.program/changed-identities` and `digest-map` are generalized over a **registry** argument (program attributes for
merge requests, `cluster-attributes` for the squash), so merge requests and the end copy are one three-way with two
registries. Every eid it transacts was issued by this store's allocator, never minted or copied from elsewhere. It
reads `seon.program/cluster-attributes` (sibling of `program-attributes`, same `projection-cache-value` pattern), the
source's datoms since the fork for those attributes (`d/since` on the branch; the fork point `t` is main's, repeated in
the branch's own lineage), groups by entity, applies the collision remap (`seon.db/identity-attributes` names the unique values to look up on
main's AVET), classifies per `[e a]` against base (branch `as-of` the fork) and
ours (main now): unchanged → nothing; only theirs → assert or retract; only ours → keep; both and different → the entity
schema's `:seon.cluster/conflict`: `:theirs` assert, `:ours` keep, `:add` a `[:db.fn/call …]` summing, `:root` keep ours
plus a conflict entity `{entity attribute base ours theirs}`. A `selection` argument restricts it to given identities (root's
by-hand copy).
**The end.** `seon.turn/end-task-call` writes the end on the branch in the agent's last transaction. `:seon.task.attempt/end`
is a listened datom; the branch's route (every hosted connection is routed today) offers `db<->flow` a wake; its
pass finds attempts whose branch holds an end main lacks. **A successful end (`:done`)**: `squash-merge` → one `seon.db/transact!` on main with `:tx-meta` provenance → one problem task per `:root` conflict by identity
`seon.id/id [:squash-conflict entity attribute]` (a repeat writes nothing) → the merge request (root works it on main; its
diff reads the end commit) → `flow/stop`, `join-graph!`, release the SCI context; when the merge request closes, `seon.task/delete-branch!`
(detached io work; it already takes the registry permit) unless `:seon.config.agent/keep-ended-branches?` (new dial, default
false) keeps it for audit. **An error end** (`:given-back`, `:capped`, `:stalled`, `:cancelled`): nothing is copied, the
graph stops, the branch stays, and runtime-follows-db §5's existing problem class (attempt ended given-back/capped/stalled)
is written on main by `db<->flow` carrying the end, reason and branch; root merges by hand at its REPL with the same
function (`(seon.cluster.squash/squash-merge! branch selection)`, offered as an operation of the problem task) and destroys the
branch explicitly (`my.agent/retire-branch!`). Idempotent by identity: a crash after the copy re-runs an empty copy; one
before it re-derives from the branch. Root's end is the identity (its branch is main).

**Messages as channels, the smallest version.** Each agent graph already has a mailbox proc whose in-port is its
payload-free wake channel (`seon.cluster.agent/mailbox-step`, `(sliding-buffer 1)`); the registry maps agents to those
channels (`seon.cluster.agent/channels`). It gains one more in-port, **`:seon.agent/inbox`**, a bounded buffer (dial
`:seon.config.agent/inbox-size`, default 64) that carries the message value itself. **Sending** (`my.message/send` →
`seon.cluster.message/send!`): validate the candidate against the sender's own database (its id minted there), look the
recipient's inbox channel up in the registry, `offer!` the value (never park), and answer `seon.cluster.wake/delivery`'s
classification: delivered → `{:seon.message/id … :seon.message/accepted true}`; no channel (not running), closed or
full → an error value to the sender, `:seon.message/undeliverable` naming the recipient, its intent and the cause. The
sender writes nothing unless the send declares `:my.message/keep? true`, in which case `send-call` also writes its own
copy on its own branch (the sender decides what it keeps). **Delivery**: the recipient's mailbox proc (`:io`) receives the
value, writes it on the recipient's own connection with `send-call` (identity `:seon.message/id`, so a repeat is a no-op),
then emits its episode signal; the recipient's next turn renders its inbox from its own branch, as today. Nothing is
written in transit; nothing relays through main; main never carries a message; the route's `:seon.wake/listen` on
`:seon.message/to` keeps waking the recipient from its own write. Root's graph has the same mailbox, so messages to and
from root take the same path. **A recipient that is not running**: the send is answered as the error above and nothing is
queued; if its intent is `:running` with an open attempt and no graph (between a restart and the prime) the reconciler
hosts it on its next pass regardless, and the sender resends then; for `:paused`, `:held` or `:stopped` the sender's
options are the error's data (wait, or `my.task/create!` a task for it). No problem task is written for a sender's
refused send (an agent's own outcome is only delivered); a refused offer to a **live** inbox (full) is runtime-follows-db
§5's existing `delivery failed` class. **In-flight loss** (a crash between the offer and the recipient's write) is accepted:
"accepted" means in the channel, not written; a sender that needs proof declares `keep?` and waits for a reply by its next
wake, and a resend carries the same `:seon.message/id`, which the recipient's identity write makes a no-op. Task refinement
stays at-end: a running child sees its parent's edit at its next attempt, by design.

**Cost, against the plans.** End copy: one derivation (Opus measured 3.2 ms cold, `d/with` 2.3 ms) and one main
transaction (raw 13 ms, `seon.db/transact!` 15–25 ms) per attempt, proportional to its changed cluster datoms, plus
`delete-branch!`. A message: one `offer!` (microseconds) and one transaction on the recipient's branch (15–25 ms), the
same as today's direct write but by the recipient's own writer; main carries nothing. Opus's continuous pick paid one or
two main transactions per message and per refinement, end and error, plus a proc and a watermark; Sol's three commits per
message plus a durable basis. This design has no transport state at all: Flow owns the channel, the recipient owns the
fact, main pays once per attempt.

**Storage reclaimed per agent, and how.** `d/delete-branch!` removes the branch from the store's known branches; the
next `seon.cluster.registry/collect!` (`gc-storage!`, mark from every registry branch head through commits newer than
`retention-cutoff` under `:seon.config.db/snapshot-window-ms`, sweep before the safe point) finds nothing reaching the
deleted branch's commits, so everything unique to it is swept: every commit record its transactions wrote, the index
root and path nodes those transactions created (shared nodes stay with main), its history indexes, and the konserve
blobs only its facts named (`seon.maintenance/collect!` verifies retained branches and blobs together). What survives is
exactly the one copied transaction on main and what root merged. The per-agent number is a red cost entry (AW2): the
store's key count and bytes after `collect!` with and without a deleted ten-evaluation attempt differ by the copied
transaction only. Today nothing is reclaimed while branches outlive their agents.

**Composition.** `start-flow!` (H7): `fork!` from the attempt's main commit, the agent's connection, one SCI fork, graph; no import.
`db<->flow` (R1): the end copy and the error problem task are two more actions of `plan`; the inbox in-port is one more entry of `seon.cluster.agent/graph-definition`. Test
members: `d/with` on the run branch; `squash-merge` is tested on values with `d/with` targets. Q3: the agent entity is
main's; the branch inherits it and nothing writes it back.

## 5. Reused, changed, deleted (by symbol)

**Reused.** `seon.turn.world/world` (one connection); `seon.call-preparation/supply`, `supply-from-world`;
`seon.batch/world-members` (no new member); `seon.cluster.wake/route!`; `seon.program/program-attributes`'s pattern,
`three-way`'s classification; `seon.db/transact!` (`:tx-meta`), `identity-attributes`, `with`, `since`, `as-of`;
`seon.schema`'s partition check; `seon.id/id`; `seon.cluster.registry/fork!`, `collect!`; `seon.task/delete-branch!`;
`datahike.api/delete-branch!`, `gc-storage!`; `seon.flow/start-flow!` (H7); R1's `db<->flow` and `plan`.

**Changed.** Entity schemas `seon.message/message`, `seon.task/task`, `assignment-row`, `seon.error.occurrence/occurrence`:
`:seon.program/partition :seon.cluster`; the check accepts `:seon.cluster` and requires an identity attribute for it (a
declaration rule, not a runtime check). `seon.program/cluster-attributes` new. `send!`: an `offer!` to the registry's inbox channel plus an optional own copy; `mailbox-step`: writes a received message with `send-call`; `graph-definition`: the `:seon.agent/inbox` in-port. `seon.turn/end-task!`: `end-task-call` and `end-summary-tx` on checked-out branch and nothing else. `seon.task/written`,
`assign!`, `adopt-tasks-tx`: the given connection. `seon.context/held-task`, `ended`, `end-summary-tx`,
`current-state`: the agent's database only. `seon.turn/admission-closed?`: the end on the attempt. `seon.fault/record!`:
one write. `seon.test/run`: members under `d/with`. `seon.flow/plan`: the end action (copy, merge request, stop, delete or keep) and the error problem task.
New schema property `:seon.cluster/conflict`; new dials `:seon.config.agent/keep-ended-branches?`, `:seon.config.agent/inbox-size`;
new optional send member `:my.message/keep?`. Vocabulary entries first: **squash merge** (replacing the cherry-pick entry; `docs/seon/architecture/vocabulary.md` is held by lane sol-p0-p1, so the entry goes to it as a patch: `| squash merge | git's \`merge --squash\` as the copy-back: the pure identity-matched three-way of declared cluster facts between the merge base, a source branch and main, landed as one plain transaction on main with no parent link to the source, so a deleted branch stays collectable; returns transaction data and conflicts (\`seon.cluster.squash/squash-merge\`) | cherry-pick, data relay |`), **`:seon.cluster`**, **end copy**, **inbox**.

**Deleted.** `seon.task/main`, `main-connection`, `written`'s indirection; `send!`'s recipient branch lookup,
its cross-branch `db/transact!` and `:seon.message/unknown-recipient` (replaced by `:seon.message/undeliverable`); `seon.turn/closed-on?`'s
main half and every `(task/main database)` in `task-end`, `end-message`, `end-merge-request`, `next-agent-work`,
`task-proofs`; the four in `seon.context`; `:seon.agent/admission-closed-tx` writes; `record!`'s cluster copy;
`seon.turn.world/settlement-connection`'s requester escape; agent-side `seon.db/branch-connection`; the end's follow-ups
in the agent's own turn (`program-request-merge!`, `land-end!`, the agent calling `task/delete-branch!`). Not built:
Opus's `pick-step`, watermark and down-pick; Sol's `move-data!`, `movement-tx`, `:seon.data/movement`, `:seon.data/merge`.

## 6. Phases (about fifteen minutes each, one owner, red first; filed as tasks)

| # | Slice (owned files) | Red proof and assertion |
|---|---|---|
| AW0 | **the partition value and its rule.** `:seon.cluster` on `seon.task/task`, `assignment-row` and `seon.error.occurrence/occurrence` with `:seon.cluster/conflict` (`:add` on the occurrence count; default `:root`), `seon.program/cluster-attributes` (answering the rule per attribute), the check extended, vocabulary entries (`resources/seon/schemas/{seon.task,seon.error.occurrence}.edn`, `src/seon/program.cljc`, `src/seon/schema.clj`, `docs/seon/architecture/vocabulary.md`, `test/seon/program_test.clj`); platform slice, `--test` | `seon.program-test/cluster-attributes-are-the-declared-cluster-entities`: on the packaged registry `cluster-attributes` ⊇ `#{:seon.task.attempt/end :seon.task/intent :seon.error.occurrence/count}`, excludes `:seon.message/to` and is disjoint from `program-attributes`; a fixture `:seon.cluster` schema with no identity refuses with `:seon.schema/member :seon.db/identity`; derivation under 5 ms warm |
| AW1a | **one eid counter per store** (the fork, `reference-code/datahike`; Opus, under a day, Astra review): a per-store `AtomicLong` seeded at first connect from 1 + the max `max-eid` over the registry's heads, handed to `transact-tx-data` by the writer and read by `next-eid`; `max-eid` observation only; `with` and store-less dbs allocate locally; the producer guard refusing an eid at or above `tx0` and advancing the counter past an explicit one below it; then the fork bump | `seon.db-test/entities-born-on-two-branches-of-one-commit-have-different-ids` (two forks of one commit each transact a new entity: the eids differ and are below `tx0`); `seon.db-test/an-explicit-eid-outside-the-allocators-range-is-refused` (table above); `…/a-reopened-store-allocates-above-every-heads-max-eid` (close and reopen the store: the next eid exceeds every head's `max-eid`); cost entry: one allocation under 1 µs warm and no store write, through `seon.profile/measure` |
| AW1 | **the squash merge** (after AW1a). `seon.cluster.squash/squash-merge` (`src/seon/cluster/squash.clj`, `test/seon/cluster/squash_test.clj`; `seon.cluster.source/merge-base` and `branch-base-t` public, `seon.program/changed-identities` and `digest-map` over a registry in `src/seon/cluster/source.clj`, `src/seon/program.cljc`) | table on values, `d/with` targets: `seon.cluster.squash-test/an-attempts-cluster-facts-copy-onto-main-by-identity` (main pulls the end, the refined intent and a child task by eid); `…/a-value-changed-on-both-sides-resolves-by-the-declared-rule` (table: `:root` keeps ours and yields one conflict entity with three values while the entity's other attribute still copies; `:theirs` asserts; `:add` sums); `…/program-and-runtime-facts-never-copy` (a `defn`, an evaluation and a turn yield empty tx-data); `…/a-copy-of-an-unchanged-branch-is-empty`; `…/a-retraction-on-the-branch-retracts-on-an-unchanged-main-only`; `…/a-branch-born-entity-keeps-its-eid-on-main` (the child task's eid on main equals its eid on the branch, refs included, and main's next allocation is unmoved); `…/a-natural-identity-born-on-both-sides-maps-to-mains-eid` (two branches each mint an occurrence with one signature: main holds one entity, its eid, count summed by `:add`, every ref remapped); `…/the-merge-base-is-the-merge-requests` (the squash's base commit equals `seon.cluster.source/merge-base` for the same branch); cost entry: a ten-evaluation attempt under 10 ms / 5 MB through `seon.profile/measure` |
| AW2 | **the end copies; the branch dies or is kept; an error end notifies root.** `db<->flow`'s end action: copy of `:at-end` kinds, provenance, `:root` conflicts as problem tasks, merge request on `:done`, stop; delete when the merge request closes unless the new dial `:seon.config.agent/keep-ended-branches?` (default false; `resources/seon/schemas/seon.config.agent.edn`) keeps it; an error end copies nothing, keeps the branch and writes the §5 problem task offering `(seon.cluster.squash/squash-merge! branch selection)`; `my.agent/retire-branch!` is the destroy (`src/seon/flow.clj`, `src/seon/turn.clj` `end-task!` trimmed, `test/seon/flow_test.clj`); Opus lane (one path) | `seon.flow-test/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` (before the pass main lacks the end; after it one main transaction carries end, refinements and provenance, no message; after the merge request closes the registry no longer lists the branch; with the dial true it does); `…/an-error-end-copies-nothing-and-writes-roots-problem-task` (main holds no refinement; the branch stays; one problem task by identity names end, reason and branch; root's by-hand `squash-merge!` then equals the success path's copy); `…/a-crash-between-the-end-and-the-copy-copies-once`; `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` (store key count and bytes after `collect!` equal the baseline plus the copied transactions; cites `gc.cljc` reachable-in-branch) |
| AW3 | **sending is an offer.** `send!` validates on the sender's database, offers the value to the registry's inbox channel, answers accepted or `:seon.message/undeliverable` (recipient, intent, cause), writes its own copy only under `:my.message/keep? true`; the branch lookup, cross-branch transact and `unknown-recipient` go (`src/seon/cluster/message.clj`, `src/my/message.clj`, `resources/seon/schemas/my.message.edn`, `test/seon/cluster/message_test.clj`); after AW7's in-port | `seon.cluster.message-test/a-send-writes-nothing-unless-it-keeps` (no transaction on any branch for a plain send; one on the sender's branch with `keep?`; `seon.profile/calls-of` of `seon.db/branch-connection` zero; `send!` under 2 ms, no writer wait); `…/a-send-to-an-agent-that-is-not-running-is-answered-as-undeliverable` (table: no graph, closed inbox, full inbox → the error names recipient, intent and cause; nothing queued) |
| AW7 | **delivery writes on the recipient's branch.** `:seon.agent/inbox` in-port (dial `:seon.config.agent/inbox-size`, default 64) on the mailbox proc; `mailbox-step` writes a received message with `send-call` on the agent's own connection, then signals its episode (`src/seon/cluster/agent.clj`, `resources/seon/schemas/seon.config.agent.edn`, `test/seon/cluster/agent_test.clj`); Opus lane | `seon.cluster.agent-test/a-received-message-is-written-on-the-recipients-branch-and-rendered-in-its-context` (after one offer the recipient's branch holds the message by identity, main and the sender's branch do not, its episode fired once and `current-state` shows it; offer → write under 30 ms warm); `…/a-resent-message-with-the-same-id-writes-nothing` (two offers, one datom set); `…/a-message-to-root-takes-the-same-path` (root's mailbox, written on main by root's own proc) |
| AW4 | **tasks and context on checked-out branch.** Delete `seon.task/main`, `main-connection`; `written`, `assign!`, `adopt-tasks-tx`, the context readers use the agent's connection (`src/seon/task.clj`, `src/seon/context.clj`, `src/seon/turn.clj` remaining reads, `test/seon/task_test.clj`) | `seon.task-test/a-refinement-on-a-branch-reaches-main-only-at-the-end` (main's intent unchanged while the attempt runs, equal after the end copy; a root edit of the same attribute meanwhile → main keeps root's, exactly one problem task); `seon.profile/calls-of` of `seon.task/main` zero (deleted) |
| AW5 | **recorded errors once.** Occurrence `:seon.cluster`; `record!` one write; `count` adds (`src/seon/fault.clj`, `src/seon/cluster/squash.clj` the one `:db.fn/call`, `test/seon/fault_test.clj`) | `seon.fault-test/a-fault-on-a-branch-is-recorded-once-there-and-counted-on-main-at-the-end` (one transaction on the branch; after the copy main holds the signature with count 1; a second attempt's same signature → 2, never an overwrite) |
| AW6 | **root differs only by its checked-out branch; members `d/with`.** Delete `settlement-connection`'s escape; `seon.test/run` members under `d/with` (`src/seon/turn/world.clj`, `src/seon/test.clj`, `test/seon/test_test.clj`, `test/seon/cluster/squash_test.clj`) | `seon.cluster.squash-test/root-and-an-agent-differ-only-by-their-worlds-connection` (the same `my.task/refine!` form through the one path: over a fixture main it writes main; over a fixture branch it writes the branch and equals main's after the end copy); `seon.test-test/members-return-reports-without-writing-another-world` (fixture writes stay in each `d/with`; the run branch's and default's heads unchanged) |

**Order.** AW0 → AW1a → AW1 → (AW4, AW5, AW7 in parallel by file) → AW3 (after AW7) → AW2 (after R1's `db<->flow` and S3's end on the
attempt; until R1 is committed, `end-task!`'s existing follow-up calls the copy once) → AW6. Lanes: AW0 platform (`--test`); AW1,
AW3–AW6 Sonnet on clear specs; AW2 and AW7 Opus (the one path). Measure the end copy and a message offer → write on default
before AW3 is committed.

## 7. Risks

1. A branch deleted before root merged its program loses the program: deletion waits for the merge request to close;
   error ends keep their branch until root destroys it (retention window applies to registry branches only, which
   `retention-cutoff` already guarantees).
5. A full inbox refuses the offer: the sender gets the error and §5's `delivery failed` task names the recipient; the
   dial is sized for a conversation, not a queue.
2. A long attempt's conflict surface grows with its length (main moves meanwhile): three-way per attribute names it;
   the policy is Q4.
3. A running child sees a parent's refinement only at its next attempt (a message is the live way to tell it): by design.
4. (Retired by AW1a: an identity-less entity copies by eid.)

## 8. Owner answers (2026-10-11) and what remains

Decided: **messages are channels**: a send is an `offer!` onto the recipient's inbox channel, the recipient's Flow writes
it on its own branch on result, the sender keeps a copy only by declaration, an undeliverable send is an error value to
the sender, in-flight loss on a crash is accepted and resends by the same id are no-ops. Everything else is copied once at
a **successful end**, after which the branch is **deleted when its merge request closes** unless the dial
`:seon.config.agent/keep-ended-branches?` keeps it for audit; an **error end copies nothing**, keeps the branch and
notifies root by a problem task, who merges by hand and destroys explicitly; **conflicts** resolve by the entity schema's
`:seon.cluster/conflict` where a rule is known, else root decides. Agent runtime bookkeeping dies with the branch.

Remaining, small: (1) `:seon.config.agent/inbox-size` default 64 (a conversation, not a queue)? (2) Which task fields,
if any, take `:theirs` (the holder's refinement wins over root's concurrent edit)? Recommended: none at first.
