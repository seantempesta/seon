# Sol: push an agent’s tracked facts as they change

Design round 4, 2026-10-12. Independent plan; the other round-4 plan was not read. Authorities: AGENTS.md;
../2026-10-11/hybrid-agent-world.md; astra-overnight-review.md; verify-astra.md (246dd0bd9); landed dc5e1d348,
aaf1433eb, 9cc893681, e91100941, d281616fd and c1e0c97c9. The owner’s current decisions supersede end-only
movement, messages dying with the branch, and successors falling back to main.

## Design

An agent writes through its own branch connection. Root’s existing `seon.cluster.agent/db<->flow`, holding
main explicitly, pushes the schema-declared `:seon.cluster` facts after each recording transaction that
changed them, including recipient-persisted messages. The branch listener only wakes this proc; it never
writes main. Each push is one plain main transaction: its transaction function reads the last pushed SOURCE
commit from main, compares that immutable source base and the next source commit against the writer’s current
main, and returns facts, durable conflict evidence and the new source provenance together. This is a squash,
not a program merge or a parent link. The last source commit is a remote-tracking ref expressed in existing
provenance, independent of the program merge base. End, graph exit, merge decision and branch retirement are
separate facts from which the same plan derives its next actions. No copy-completed marker controls lifecycle.
Prefer this composition over giving every agent a main connection or adding a push proc, lock, expected-basis
refusal, retry, timer or transport database.

## Facts → when → how

| Facts | When | How / consumer |
|---|---|---|
| Task refinement, children, needs | First recording boundary after their durable branch change | Cluster projection; three-way per logical entity/attribute; main’s task planning and root context consume them. |
| Attempt end, reason, target, end commit | Its recording transaction | Same push; root derives stop, merge work, successor readiness and retirement independently. Error ends also push their already-written facts; no rollback of earlier pushes. |
| Error occurrences | First recording boundary after recording; terminal boundary if evaluation fails | Same identity, numeric source delta for `:add`; main’s error owner consumes them. |
| Delivered message and acknowledgement | Recipient mailbox transaction, then acknowledgement transaction | Message schema joins `:seon.cluster`; recipient remains its producer. Push is an archival fact, never a second inbox delivery. |
| Sender’s optional kept message | Its own recording boundary | Same message identity; equal facts converge. A kept offer is not evidence of delivery: recipient receipt must carry positive provenance. |
| Root’s own facts/messages | Its main transaction | Already on main; there is no self-push or second recording. |
| Program and test evidence; evaluations, calls, private state | Program: reviewed merge; runtime: never pushed | Existing partitions and root merge owner. No widening to a whole branch. |
| Push provenance and conflicts | Atomically with the main push | Existing source branch/commit metadata; typed conflict data on root’s decision task. These are main observations, not recursively pushed facts. |

## Base, identity and the writer

1. For this source branch, the newest main transaction carrying
   `:seon.squash/source-branch` and `:seon.squash/source-commit` defines the last
   pushed source commit. Order by main’s transaction order, never UUID or source t.
   Before the first push use the exact retained fork commit. For a successor this
   is its predecessor’s recorded end commit, not today’s main. Program merges keep
   their own `seon.cluster.source/merge-base`; pushing never calls
   `seon.cluster.registry/advance-base!` on that program base.
2. Resolve immutable source/base values before admission for availability, but
   select the effective base and classify target changes INSIDE `:db.fn/call`.
   The transaction function receives current main from Datahike. If another push
   advanced provenance meanwhile, derive from that newer source base. Preparing
   coordinates from source/base is permissible; preparing target replacements is not.
3. Reuse `seon.program/changed-identities`, `projected-values`, `three-way` and
   `seon.cluster.squash/squash-merge`. Keep the 9cc893681 changed-attribute algorithm.
   P1a eids replay unchanged, including identity-less owned components. Remap only
   genuine natural-identity collisions, including refs and BASE values. Remapping
   must still resolve an entity on later pushes: current `collision-remap` tests
   absence from base, which stops working once that entity enters the moving base.
4. `:add` means current-main + (source-now − source-at-last-push), with absence
   initially zero. Apply it to EVERY changed additive coordinate, not only a
   three-way conflict or newly born collision. Equal source/main totals do not
   prove the same increment: base 1, source 2, main 2 must yield 3. Noncolliding
   newly born count 1 yields 1; colliding new counts 1+1 yield 2; repetition yields
   2; subsequent source 2 yields 3. A repeat ordinary conflict count stays 6, not 8.
5. Emit replacement/retraction datoms, conflict-task data, and provenance from
   one writer function. Replace `conflict-task!`’s `task/create!` effect with the
   task owner’s transaction-data producer. Final values use the existing writer
   validation and `malli.core/validator`; complete named request/result contracts
   belong at this producer. No task creation or target read beside the transaction.
6. A previously pushed source yields no fact change, conflict or new provenance.
   A new source with conflicts still advances the base: its rejected value is now
   an observed, unresolved decision, not fresh work on every pass. A new source
   whose net tracked change is empty may advance only provenance once; otherwise
   repeated scanning of a changed-then-restored interval would never finish.
   Native empty-transaction behavior must be measured; no claim of zero I/O merely
   because a transaction function returns an empty vector.

## Ordering, recovery and retained values

Listen before deriving. Extend `seon.cluster.wake/route!` to wake root’s plan on logical datoms selected by
the cluster projection, assertions AND retractions. Mailbox receipt is also a recording boundary; sender offer
success is not one. The wake carries branch identity or nothing; no tx-data replay is queued. Root derives
unpushed committed source states from branch lineage and its last pushed source commit and processes them in
source order before deriving task work. Several notifications for one native commit imply one push. A later
source state never lands before an earlier pending state of that branch. Different branches share only main’s
existing writer order. Source t is meaningful within its lineage.

Crash after branch recording but before push leaves a source commit not covered by main provenance; root’s
prime derives it. Crash after main commit but before wake/ack leaves facts AND provenance durable; the next
derivation finds no repeat delta. Missing notifications need no retry loop. Once an end reaches main, stop is
due while its graph exists; release is due after actual exit; deletion is due while its branch remains and no
program merge, successor fork or unresolved conflict requires it. A push marker cannot make any of those
disappear.

The moving base must survive no-history attributes and retention pruning. A UUID fact alone does not retain a
Datahike snapshot. Keep last-pushed snapshots and pending source commits as native retained commit roots,
independent of the program-base ref; derive those roots from main provenance and active attempts. Use
Datahike’s existing GC traversal, extending its root input where necessary, not a second node walker.
`datahike.gc/gc-storage!` currently walks roster heads; its reachable-extension accepts KEYS, not a public
snapshot-marking operation. That precise dependency gap must be closed in the fork before ranged GC proof.
Plain collection already retains live branch ancestry; it is insufficient proof for ranged collection. Release
push roots after draining and branch retirement. Successor end-commit tags have their own lifetime and survive
predecessor deletion.

Conflict identity includes source branch, source commit, entity and attribute, using `seon.id/id`; repeated
delivery finds the same decision. Store base/ours/theirs, absence, logical identity, rule, source/base commit
and main decision basis as DATA. Use existing inline/blob storage at its one threshold for large values; GC’s
existing referenced-blob extension retains that evidence. A changed later source gets another observation, not
an overwrite of the first unresolved decision. Root receives a runnable merge decision with a red proof over
its outcome, using the merge-request owner; do not manufacture a held task with prose to requery. Unresolved
decisions retain required source snapshots. Prove that evidence can also be read after branch deletion/GC, so
audit does not depend on a live branch.

## Cost and live evidence

Default PID 94708 runs 2319152dc, before P2/P3; health is faulted. Heap observation ~0.88 GB live/4 GB cap,
cache ~474.5/512 MB; neither is a peak push measurement. MCP JVM probes used root and explicit
`seon.cluster.boot/connection`; all values were speculative `seon.db/with` products. No source reload, durable
fixture, reset or provider run. First named-agent JVM request refused: installed JVM mode is root-only (182.8
ms, 1,223 calls); subsequent root probes succeeded.

Reproduction `result/e232354927139`: existing occurrence base 1, source 2, target
3 → first squash 4 → repeat 5; source-as-base → zero datoms (form 9 ms; request
127.2 ms, 8.7 MB work allocation, 4,143 calls). One intent coordinate, through
`seon.profile/measure`: unchanged target 0.47 ms/163,952 B/2 datoms; concurrent edit 0.43 ms/160,728 B/one
conflict; equal target 0.26 ms/156,480 B/zero datoms (`result/ee6e539cec50d`, request 136.3 ms/9.3 MB). Empty
pure squash samples
0.13/0.08/0.07 ms and 62,400/59,504/59,504 B (`result/e245a035df161`). Exact
forms: select an existing task or occurrence; `db/with` change the intent/count on base and target; measure
`squash-merge`; apply its tx-data with `db/with`; repeat once against original base, then source-as-base.
These prove value behavior, not installed pushing, collision replay, GC, transaction races or end lifecycle.

Push work is proportional to pending commits plus changed coordinates, their values/refs and actual conflicts,
not all entities or all historical attempts. Warm empty derivation needs zero durable writes. Each nonempty
push has a floor of one main durable transaction plus source/base acquisition and validation; that floor is
unmeasured here. Measure recording→main visibility and writer CPU, allocation, I/O and commit cost separately;
require the ordinary loop <1 s and record any >100 ms durable-write finding. Reuse content-valid projection
and unchanged branch observations. No policy number is introduced outside config.

## Deletions and PHASES

Delete `seon.cluster.agent/unlanded-ends`’ marker exclusion; rename the surviving root program-decision query
to `seon.cluster.agent/unmerged`. Replace `seon.cluster.agent/land-ends!` with the existing plan’s derived
lifecycle actions; delete `record-error-end!`’s special end copy and `detached!`’s landing path. Delete
`seon.cluster.squash/conflict-task!` and outside-writer decisions in `copy!`; manual `squash-merge!` enters
the same push owner. Remove `add-count`’s separate arithmetic decision once the writer function computes all
rules itself. Rename `seon.task/frontier` and `frontier-rules` to `seon.task/ready` and `ready-rules`,
converting every caller/test/schema reference. Remove identity-only partition admission. Fork/import deletions
remain the predecessor-world owner’s slice.

| Phase | Owned slice; RED proof before implementation |
|---|---|
| 1 | Squash + tests: extend `seon.cluster.squash-test/a-natural-identity-born-on-both-sides-maps-to-mains-eid` with second/third pushes and a later increment/ref. Add `a-repeated-copy-adds-nothing`, `a-copy-of-an-advanced-branch-adds-only-its-new-delta`; cover equal totals and noncollision conflict repeats. |
| 2 | Squash/task transaction producer + tests: `a-root-edit-after-capture-is-a-conflict-not-overwritten`; pause admission, root edits, resume push; inspect stored values and decision evidence. Same transaction holds evidence and provenance; current producer contracts compile. |
| 3 | Retention owner/fork + tests: retain last pushed no-history source and pending interval through ranged collection; prove independent program base, successor end tag, then release roots and collect runtime blobs. Extend registry GC proof with real large evaluation blobs. |
| 4 | Wake/db-flow + tests: two refinements and a child reach main while running; unrelated runtime recording writes no push; crash before/after push re-derives exactly once; actor has no main connection. Benchmark one/ten pending commits and one/100 changed coordinates. |
| 5 | Message schema/mailbox + tests: real graphs offer→recipient persistence→main archive→context; duplicate id gives one archive and one wake; sender keep does not fabricate receipt; root uses the same mailbox path. Land with P7’s actual consumer. |
| 6 | Plan lifecycle + tests: `seon.cluster.db-flow-test/an-interrupted-landing-finishes-on-the-next-pass`; interrupt each boundary after push, stop, exit, release, retirement. `a-conflicted-end-keeps-its-values-until-root-decides`; resolve, delete, collect, read saved evidence. Sweep ready/unmerged names and old end-only assertions in their owners. |

## Risks / unsure

Datahike batches transactions and replaces every report’s db-after with the batch commit. Do not promise an
exact per-transaction source snapshot from that report. Same-commit push is straightforward; preserving an
intermediate change inside a batch requires an exact recording-boundary value from the writer, or declared
one-recording-per-commit admission. Phase 4 must prove the chosen seam before calling every-recording
visibility complete; silently choosing only latest loses conflict observations. This is the principal
remaining design question.

Confirm how occurrence refs to nonpushed evaluations/turns become durable evidence without dead refs;
schema-owned observation values, not hand-stripped attributes. Confirm additive count retraction semantics
with its producer; zero is not an invented reset rule. Confirm root decision tasks’ existing proof entrance.
Dependency allocator changes are in flight; verify invocation-once before relying on transaction-function
cost. No edits to those owners are part of this plan.

Landing: documentation only; no program tests reach this file (`bin/lane`’s explicit docs-only rule). Full
src/script/test lint: 0 errors, 1,562 warnings,
6.774 s. Foreign boundaries: central driver/test edits, http-kit working state,
and later mailbox/allocator work; untouched. Installed P2/P3 and integrated push remain unverified. Only this
file is claimed and committed; source/test delta zero.
