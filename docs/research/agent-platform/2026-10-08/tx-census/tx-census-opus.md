---
type: research
status: measured
date: 2026-10-08
lane: tx-census-opus
---

# Transaction census: what each ordinary operation commits, and what the transaction entity could carry

Lane `tx-census-opus`, 2026-10-08. Research and measurement only; no source changed.

**What exists.** Every Seon write goes through `seon.db/transact!` → `transact-call` (`src/seon/db.clj:5650` at
HEAD), which calls `d/transact!` (or `d/merge-db!` when the request has `:parents`, `:5681`). `stamp-transaction`
(`src/seon/db.clj:3570`) already appends `{:db/id :db/current-tx …}` to every transaction:
`:seon.db.transaction/operation` (symbol, AVET-indexed), `:seon.db.transaction/agent` (string, AVET-indexed, when a
world names an agent), and above a slowness floor `:seon.db.transaction/ms`, `calls`, `hot`, `repeated`
(`seon.profile/transaction-observation`, `src/seon/profile.clj:445`). A few writers add their own tx attributes:
`:seon.db/process` (ref), `:seon.db/receipt`, `:seon.db/user`, `:seon.test/adoption-cluster`. So Seon already uses
the transaction entity for provenance; the census asks where it does not and should.

**Method.** A `d/listen` callback on every open Datahike connection (re-attached every 5 ms so new branches are
caught) records each tx-report: branch, tx id, `:seon.db.transaction/operation` and `agent`, datom count, retractions,
attribute set, and `[:tx-meta :db/commitId]`. A konserve write hook on the physical store records every key written;
node writes are grouped into the commit record that follows them, so each commit's retained bytes (new immutable
objects) and its branch-head overwrite are exact, joined to the listener row by commit id. Attempts are the growth of
`seon.db/transact!` calls in `seon.profile`. Scripts and raw results are in [`opus-evidence/`](opus-evidence/).

- **default**: MCP `eval_clj` as agent `tx-census` (SCI) and in JVM mode with the cluster connection; default ran
  `75aa6990b` until root restarted it at 06:34:30, then the new HEAD. Store: 577,800 current datoms per primary index,
  459,788 temporal; 1,218 transactions on `:cluster-default`; ~167 k store files.
- **throwaway root** `tmp/tx-census-opus/root` at `4707b9546` (fresh store, 538 k datoms), driven through
  `seon.operator/prepl-value!`, for the fixture-provider model pass, the task → test → merge request flow and error
  recording, where no other lane writes. `seon.pass-probe`'s helpers (`model-pass`, `mcp-pass`, `agent-world`)
  and `seon.test-support/with-provider` supplied the entrances.

## 0. Broken or surprising first

1. **A model eval request is 7 commits** (confirmed on HEAD): wake, system turn, open, context capture, attempt, plan,
   record. 470-555 KB retained plus 88-135 KB of branch-head rewrites per model eval request on a fresh store.
2. **A core error is recorded as two extra commits and repeats are not folded.** `seon.fault/record!`
   (`src/seon/fault.clj:138`, writes at `:267`) commits the error on the agent's branch *and* on the cluster home
   (57 datoms each), then the eval request commits its record: 3 commits where 1 would do. The second, identical
   occurrence from a later eval request wrote 42 datoms on each branch again; the "a repeat the writer would only count
   is folded, no commit" path did not fire. The notification then wakes root, whose system turn costs 4 more commits
   (251 + 9 + 6 + 5 datoms).
3. **Empty commits.** 56 of the 1,218 commits on default's `:cluster-default` carry no data datom at all: 39
   `seon.cluster.host/home-call` (re-hosting an agent that already exists: `home-call` answers `[]`, the transaction
   still commits, `src/seon/cluster/host.clj:44-71`, called twice by `start!` at `:73-90`) and 17 anonymous
   `seon.db/transact!`. Each costs a full commit (41 KB retained + 29 KB head on default).
4. **Root's JVM-mode call records carry no agent.** 156 `seon.turn/record-evaluated-call` commits in 6 h on default
   have `:seon.db.transaction/operation` but no `:seon.db.transaction/agent`.
5. **One commit per retired agent.** 80 commits on `:cluster-default` carry exactly one datom,
   `:seon.agent/admission-closed-tx`.
6. A cold `my.test/check` of one test is 8 commits on three branches (EN-6 says "twice"); accept-merge is 5.

## 1. What one commit costs (Datahike, pinned `86c3301a`)

Each `d/transact!` that the caller awaits becomes its own commit. The writer batches only transactions already queued
together (`datahike/writer.cljc:229-295`; `:db/commitId` is stamped on every report of the batch at `:271`);
`commit-wait-time` defaults to 0 (`:84`). A commit (`datahike/writing.cljc:462` `commit!`):

- flushes the dirty nodes of the six indexes (EAVT, AEVT, AVET and the three temporal ones; `:99-110`): a leaf and its
  ancestors for every place the transaction's datoms land;
- inlines each index's **root node** into the commit record (`:fuse-index-roots? true` on default, `:176`); on default
  the six roots hold 81, 84, 31, 67, 63 and 15 children, so the record is **28.9 KB** (13.6 KB on a fresh store) and
  grows with the database;
- writes that record twice: the immutable commit under its commit id and the mutable **branch head** under the branch
  key (`:565-566`, `:586-596`). The head is an overwrite (write I/O, not growth).

Where datoms land decides the node count: new entities and every attribute's newest datoms go to index tails (EAVT
tail, the AEVT tail of each attribute), indexed attributes and refs go to AVET at their value's position
(`datahike/db/utils.cljc:308`: every `:db.type/ref` is AVET-indexed), so a random-hash unique id
(`:seon.cluster.eval/id`, `:seon.turn/id`) dirties a leaf in the middle of AVET. A value replacement also writes the
temporal indexes.

Measured on default with raw `d/transact!` on my disposable branch:

| transaction | objects | retained | head rewrite | wall |
|---|---|---|---|---|
| empty (`:db/txInstant` only) | 3 | 41.1 KB (28.9 KB of it the commit record) | 28.9 KB | 15 ms (first 41 ms) |
| + `:seon.db.transaction/operation` | 8 | 56.6 KB | 28.9 KB | — |
| + `operation` + `agent` | 7 | 57.1 KB | 28.9 KB | — |
| 12 datoms on 7 entities, **7 commits** | 78-83 | 606-629 KB | 202 KB | 106-112 ms |
| the same 12 datoms, **1 commit** | 19-22 | 206-241 KB | 29 KB | 20 ms |

So on default a commit costs ~41 KB retained + 29 KB rewritten before any data, and each *distinct attribute* it
touches costs ~1.5-2.5 node objects (4-10 KB each); further datoms of an attribute already touched are nearly free.
Folding 7 commits into 1 cut retained bytes 2.6-2.9×, written bytes 3.2× and time 5.5×. The owner's premise holds with
one correction: batching in data is free **per attribute already touched**; a new attribute costs a leaf per index it
lives in (an indexed or ref tx attribute ≈ +5 objects, +15 KB per commit on default).

This answers SR-GROW's "200 KB per commit": ordinary records touch 20-70 attributes (§2), so 250-620 KB on default.

## 2. Census

Durable = commits the listeners saw; attempts equalled durable in every window (a refused call to a retired agent
wrote nothing). "Bytes" = retained bytes, exact from the write hook; the branch-head rewrite is ~29 KB per commit on
default and 12-15 KB on the throwaway on top. Datom counts include the tx entity's own.

### On default (large store)

| operation | commits | what each commit writes | bytes |
|---|---|---|---|
| MCP SCI eval request, no defs | 1 | agent branch: eval record (turn + evaluation), 27 datoms, 25 attributes, 38 objects | 253 KB |
| MCP JVM eval request (root) | 1 | `:cluster-default`: `record-evaluated-call`, 19 datoms (turn 7 attrs, evaluation 10), 29-30 objects | 288 KB |
| `defn` eval request | 1 | 144 datoms, 67 attributes, 156 objects (an earlier `defn`: 206 datoms, 103 of them `:seon.profile.measurement/*`) | 618 KB |
| task create + assign + revise (one eval request) | 3 + 1 | `adopt-tasks-tx` 10 datoms; assignment 8 datoms; eval record 361 datoms | ≈ 0.5 MB task, ~100 KB assignment (file mtimes, approximate) |
| branch create (`my.agent/create-branch!`) | 0 + 1 | `d/branch!` writes no transaction; the eval record 83 datoms | — |
| erroring eval request (core error) | 3 | `seon.error/commit-call` 57 datoms on the agent branch and again on the home branch; eval record 38 | ~176 + 162 KB (approximate) |
| boot, existing store, no program change | 3 | process row (`:seon.db/process` on the tx); tasks; `:seon.test/adoption-cluster` on the tx | — |
| boot after root's restart (06:34:30) | 3 + 2 | 4 / 590 (tasks) / 5 datoms; then `home-call` empty (2 tx datoms) and `home-call` 39 | — |
| background, 6 h on `:cluster-default` | 516 | `record-evaluated-call` 156, `home-call` 109, anonymous `transact!` 103, `absent-call` 76, `adopt-tasks-tx` 28, `open`/`plan`/`close-call` 8 each, `seon.error/commit-call` 7, `steps-call` 4, `send-call` 4, `seon.schedule/fire-call` 3 + `settle-call` 3, `record-tx` 1 | — |
| whole history of `:cluster-default` | 1,218 | 56 empty, 204 with 1-5 data datoms (80 `admission-closed-tx`, 26 `:seon.db.process/id`, 26 source digest/built-at, 24 `:seon.source/commit-id`), 617 with 6-20 (eval records), 183 with 21-100, 99 with 101-1,000, 59 larger | — |

### On the throwaway root (fresh store, quiet; wall ms of the MCP call)

| operation | commits | what each commit writes | bytes | ms |
|---|---|---|---|---|
| agent creation + first eval request | 3 (+1 branch head) | `home-call` on `:cluster-default` 39 datoms; `home-call` again on the new branch 40; eval record 27 | 63 + 63 + 71 KB | 254 |
| model eval request (fixture provider, 1 form) | **7** | wake `inbound-tx` 6 · system turn `transact!` 137-217 · `open-call` 9 · capture 50-86 · attempt 12 · `plan-call` 13 · record 14 (a `defn`: `program-facts-call` 363) | 470-555 KB + 88-135 KB heads | ~760 |
| `my.test/check` one test, cold | **8** on 3 branches | agent branch: admission 57, `record-tx` 59, eval record 757; checkpoint branch: `seon.config/reconcile-call` 120, then 5, 18, 4; member branch 140 (in-memory overlay, not on the store) | 545 KB | 1,592 |
| `my.test/check`, warm (green reused) | 1 | eval record 477 | 203 KB | 134 |
| task create | 2 | task 10 (home); eval record 619 | 23 + 142 KB | 138 |
| task assign (root) | 2 | assignment 23 (with `:seon.db/receipt`); root's eval record 514 | 81 + 176 KB | 163 |
| `deftest` naming the task | 1 | 46 | 95 KB | 166 |
| merge request (`propose-merge!`, proofs already green) | 3 + root's woken turn 3 (+1 later) | merge task 18; message to root 9; eval record 606; root: system turn 99, open 9, plan 6, close 5 | 627 KB | 276 |
| `prepare-merge!` | 4 + overlay | merge branch: program copy 89-90, test admission 55, `record-tx` 55; member overlay 140; root's eval record 438-1,070 | 550 KB | 1,824 (eval 927) |
| `accept-merge!` | 5 | `:cluster-default`: merged program 103 (with `seon.turn/*` and `seon.cluster.eval/*` rows), task outcome 5, `admission-closed-tx` 4; agent branch: `admission-closed-tx` 4; root's eval record 549 | 623 KB | eval 1,447 |
| core error, first occurrence | 3 | `seon.error/commit-call` 57 on the agent branch and 57 on the home; eval record 38 | 104 + 62 + 97 KB | — |
| the same error from a later eval request | 3 | 42 + 42 + 38 | 103 + 47 + 100 KB | — |
| agent's own error (`(/ 1 0)`) | 1 | eval record 24 (delivered, not recorded) | 161 KB | — |
| idle, 60 s | 0 | nothing | 0 | — |
| boot, fresh store (install) | 20 | 38 · 3,566 (schema) · 4 · 5 · 525,239 (program) · 35 · 63 · 4,804 (tasks) · 5 · 84 · 5 (`reconcile-call`) · 4 · 15 · 51 (`ensure-entity-call`) · 24 (`root-maintenance-seed-call`) · root: 1,029 (`append-generated-call`), 37, open 8, plan 6, close 5 | — | 71 s ready |

A schedule firing (default history) is 2 commits: `fire-call` 26 datoms across three entities
(`:seon.maintenance.request/*`, `:seon.maintenance.receipt/*`, `:seon.schedule.fire/*`, each naming the agent, task,
handler and instants again) and `settle-call` (8, or 470 with a vector result). A declared effect is 2 by design
(`seon.effect/open-call` before the handler, `settle-call` after, `src/seon/effect.clj:227,280`): the open receipt is
what recovery reads, so it must be durable before the handler runs.

## 3. Waste

### Sequences that could be one transaction

| sequence | today | could be | why it may join |
|---|---|---|---|
| model eval request | 7 | 1 + the wake | open, capture, attempt, plan and record have no reader between them; the system turn and capture go with CN-21 |
| core error | 3 (+4 for root's woken turn) | 1 on first occurrence, 0 extra on repeat | the occurrence rides the eval request's own commit; the home copy only on a new signature |
| cold test check | 8 | 2 on the recording branch (admission kept) + 1 on the checkpoint branch | EN-6, and `prepare-checkpoint!`'s 4 commits fold |
| accept-merge | 4 + record | 1 on the cluster + 1 on the agent branch + record | merge, task outcome and the cluster's admission closure share the cluster branch |
| agent creation | 2 registrations | 1 | fork the agent's branch after the home registration, so the branch inherits it |
| agent retirement | 1 per agent | 1 per branch for all agents retiring | 80 single-datom commits on default |
| boot source facts | 3 small (process id; source digest/built-at; commit id) | 1 | 26 + 26 + 24 single-purpose commits in history |
| fresh-store boot | 20, ~10 tiny | ~6 | tiny commits between the large adoption commits |

Branches separate commits that cannot join: a Datahike transaction writes one branch, so a write to an agent branch and
the cluster home is always 2.

### Tiny or empty transactions

56 empty and 204 one-to-five-datom commits make 21% of default's history. At ~41 KB retained + 29 KB written each,
the 80 `admission-closed-tx` commits alone wrote ~5.6 MB. The empty `home-call` commits are pure loss.

### Entities and attributes that only record provenance

Each row below states who/when/which eval request/agent/task about a write that the transaction itself already
identifies, or that the transaction entity could carry once:

| attribute or entity | what it records | per commit today | as tx metadata |
|---|---|---|---|
| the turn entity: `:seon.turn/id`, `agent`, `opened-tx`, `closed-tx`, `starting-ns`, `reply-size`, `seon.turn.work/situation` | which eval request, which agent, which transactions opened and closed it | 7 attributes on every eval record (and its own random-id AVET leaf) | `:seon.db.transaction/eval-request` + the existing `agent`; `opened-tx`/`closed-tx` are the writing tx itself once the eval request is one commit |
| `:seon.cluster.eval/at`, `origin`, `run`, `read-basis-transaction` | when, which entrant, which turn, which basis — repeated per evaluation (3-4 per record) | 4 attributes × N evaluations | `:db/txInstant`, `operation`, `eval-request`, one `:seon.db.transaction/basis-t` |
| `:seon.ai.attempt/at`, `:seon.test.run/at`, `:seon.test.member/completed-tx`, `terminated-tx`, `:seon.test.run/selection-tx` | when / which transaction | 1 each | `:db/txInstant`, the writing tx |
| `:seon.error.occurrence/process`, `first-at`, `last-at`; `:seon.error/at` | which process, when | 4 of 42-57 datoms per occurrence, ×2 branches | `:seon.db/process`, `:db/txInstant`. `occurrence/agent` (ref) and `occurrence/evaluation` (indexed string) stay while delivery seeks by them (tx → entity is unindexed, §4); `occurrence/branch` stays (the home copy needs it) |
| `:seon.maintenance.request/*` + `:seon.maintenance.receipt/*` + `:seon.schedule.fire/*` | the same agent, task, handler and instants three times | 26 datoms | one fire entity; agent, task, operation on the tx |
| `:seon.profile.measurement/*` rows; `:seon.db.transaction/ms`, `calls`, `hot`, `repeated` | profile observations | 103 of 206 datoms in one `defn` record; `hot` 380 and `repeated` 372 datoms on 19 slow txs | delete (S5-02: health reads the live profile) |
| `:seon.db/receipt`, `*receipt*`, `*open*` | the pending eval request a write belongs to | 22 txs | `eval-request` on the tx (RP-14 deletes the mechanism) |

Already right and worth copying: `:seon.agent/admission-closed-tx` and `:seon.step/done-tx` are refs to the
transaction that made the transition (data guide §5: "a transition recorded by the database in its own transaction
uses a transaction ref"); `:seon.db/process` and `:seon.test/adoption-cluster` sit on the transaction entity.

## 4. Metadata design

### What every Seon transaction carries

Written once by `seon.db/stamp-transaction` (the one owner; it already installs missing tx attributes inline on an
old store, `src/seon/db.clj:3570-3605`), from the evaluation's own bindings, never passed by each writer:

| attribute | value | indexed | present when | consumer |
|---|---|---|---|---|
| `:db/txInstant` | Datahike's | no | always | every "when" above |
| `:seon.db.transaction/operation` | the writer symbol (exists) | AVET (exists) | always | census, health |
| `:seon.db.transaction/agent` | agent id **string** (exists; make it present on every agent-caused write, root's JVM records included) | AVET (exists) | an agent's world is bound | "who wrote this", merge review, error delivery |
| `:seon.db.transaction/eval-request` | the eval request id string (the id EN-1 calls the pass id) | no | inside an eval request | recovery by eval request (RP-15a), effects (EN-1), errors, turn/eval provenance |
| `:seon.db.transaction/basis-t` | the basis the eval request read and answered | no | inside an eval request | S6-05's answered basis; replaces per-evaluation `read-basis-transaction` |
| `:seon.db/process` | ref to the process row (exists, same branch) | AVET (ref) | system writers | boot, recovery |

Deliberately **values, not refs**, for agent, task and eval request: tx metadata is written on agent branches,
checkpoint and test-member worlds (which hold "none of its agents", `seon.test/run` docstring) and merge branches; a
ref to an agent or task row fails where the row is absent, eids allocated after a fork differ between branches, a
merge re-transacts and would need remapping, and `:db/retractEntity` of a referenced row retracts the refs from
transaction entities. This matches the existing decision in `stamp-transaction`'s docstring ("Agent names are
historical observations, not living entity refs") and CLAUDE.md ("an observed name is a value"). A task symbol on the
tx (`ns/task-name`) has no reader that needs it today; add it with its first consumer, not before.

A merge commit carries root's `agent`, its `eval-request`, and the merge request id (a value). The source commit is
already recorded by Datahike: `d/merge-db!` stores the parents in the commit record (`writing.cljc:928-937`), and
lineage reads it through `versioning.cljc`; no datom duplicates it.

### What it lets us delete

The turn entity (with CN-21/CN-19), `:seon.turn/opened-tx`/`closed-tx`, `:seon.cluster.eval/at`/`origin`/`run`/
`read-basis-transaction`, `:seon.ai.attempt/at`, `:seon.test.run/at` and the member `completed-tx`/`terminated-tx`
when the record is one transaction, the occurrence's process and instants, two of the three schedule entities,
`:seon.db/receipt` with `*receipt*`/`*open*`, the profile stamps and measurement rows, and MA-11's planned
`:seon.merge/merged-by` (never add it). On an ordinary eval record that is ~10 of 25 attributes: at ~1.5-2.5 objects
per attribute on default, roughly 60-100 KB less per eval request, against +1 unindexed attribute (+~2 objects) for
`eval-request`.

### How readers query it

Entity → transaction is cheap: the datom carries its tx (`[?e :seon.cluster.eval/id _ ?tx]
[?tx :seon.db.transaction/agent ?a] [?tx :db/txInstant ?at]` with `?e` bound walks EAVT twice).
Transaction → entities is **not indexed**: Datahike filters the tx position and never seeks by it
(`datahike/db/search.cljc:152`, `[_ a _ t *] (lookup-strategy :aevt _ 1 _ f)`), so "everything eval request X wrote"
scans the AEVT range of each attribute, O(that attribute's datoms), and Datahike has no transaction log to replace it.
Therefore the agent's hot read ("its evaluations in agent order") keeps one indexed agent-facing fact on the
evaluation (an agent ref plus ordinal, replacing `:seon.cluster.eval/run` → turn → agent); tx metadata answers who,
when and why for a fact already in hand, and cold history questions.

### Cost per transaction

Each tx attribute written on every commit dirties its AEVT tail leaf (+ AVET leaf if indexed or a ref) and their
parents: measured +5 objects / +15.5 KB for the indexed `operation` on default, the second attribute +0.5 KB more.
Unindexed `eval-request` and `basis-t` cost about half of an indexed one. The tx entity's EAVT leaf is already
dirtied by `:db/txInstant`.

### Risks

- **History.** Tx entity datoms are never retracted: every attribute adds one current datom per commit forever.
  At 1,218 commits that is trivial in count; the byte cost is the per-commit leaf above.
- **Merge.** `d/merge-db!` writes one new transaction on the target; source transaction entities are not copied.
  Source provenance stays reachable through the merge commit's parents only while collection keeps them:
  `datahike/gc.cljc:79` walks parents only `in-range?` of the retention window. After that the target answers "root,
  merge request M" and the merge request names the source agent and branch. This is the same loss MA-11/NK-8a already
  choose for definitions (the evaluation pointer drops at merge).
- **Schema adoption.** A tx attribute must exist before a transaction uses it: Datahike's `flush-tx-meta` raises "Bad
  transaction meta attribute" (`datahike/db/transaction.cljc:904-923`) and schema-on-write refuses it in tx-data.
  `stamp-transaction` already installs missing stamp declarations in the same transaction; new attributes join that
  list and are published and adopted with `seon.db.transaction.edn` on a branch of the live store.
- **Datahike's `:tx-meta` map vs tx-data.** Datahike applies `:tx-meta` only when `keep-history?` is on
  (`transaction.cljc:1270`); Seon's `{:db/id :db/current-tx …}` in tx-data works either way. Keep the tx-data form.
- **Group commit.** Datahike may batch concurrent transactions into one commit (`writer.cljc:229-295`); metadata is
  per transaction, so it stays correct when commits are shared.

## 5. Slices, ranked by bytes × frequency

Budgets are measured "before" and the expected "after"; each proves itself with the named test plus a census
window from [`opus-evidence/tx-census-probe.clj`](opus-evidence/tx-census-probe.clj).

1. **Model eval request: 7 commits → 1 (+ the wake).** RP-14 with EN-8b and CN-21. Files: `src/seon/turn.clj`
   (`open-turn` `:4353`, `record-attempt!` writing `:4271`, `system-turn` `:2240`, `plan-call` `:624`,
   `receipt-settle-batch-call` `:969`), `src/seon/db.clj` (`*receipt*`), `seon.db.transaction.edn`. Proof:
   `seon.cluster.turn-test/a-model-reply-pass-commits-its-evaluations-once` and C0's model row. Before: 7 commits,
   470-555 KB on a fresh store; controlled default experiment 606-629 KB + 202 KB heads, 106-112 ms for 7 commits.
   After: 1 commit, ≈ 206-241 KB + 29 KB, 20 ms. **Row change:** RP-14 says open and attempt facts "ride the one
   settlement"; they should not become entity facts at all. The turn's `opened-tx`/`closed-tx`/agent/id move to the
   transaction (`eval-request`, `agent`), and the system turn and context capture (CN-21) leave the commit rather
   than joining it.
2. **Eval record attributes: −10 of 25.** S5-02 (measurement rows and tx profile stamps: half the datoms of a `defn`
   record) plus the provenance moves of §4 (CN-21 for the turn; a new row for `:seon.cluster.eval/at`, `origin`,
   `run`, `read-basis-transaction`). Every MCP and model eval request pays this. Before: 253 KB (SCI), 288 KB (JVM),
   618 KB (`defn`) on default. After: ~150-190 KB ordinary, `defn` minus its measurement attributes. Proof: S5-02's
   `seon.health-storage-test/health-uses-live-profile-without-writing-measurements`, and a new
   `seon.db-test/an-eval-request-commit-names-its-eval-request-on-the-transaction`.
3. **Error recording: 3 commits → 1, repeats count only.** Errors cut (E-CUT rows); files `src/seon/fault.clj:138-267`,
   `src/seon/error.clj` `commit-call` (`:1330` at HEAD). The occurrence rides the eval request's own commit; the home
   copy only for a new signature; a repeat increments the count. Fix the unfolded repeat first (§0.2). Before: 3
   commits, ~260 KB (+ root's 4-commit woken turn); after: 1 commit, and no home write on repeat. Proof: a new
   `seon.fault-test/a-repeated-error-from-a-later-eval-request-commits-nothing-on-the-home`.
4. **Test request.** EN-6 as written (admission + one record on the recording branch), plus the checkpoint branch's
   4 commits folded into 1 (`prepare-checkpoint!`, `src/seon/test.clj:1790`) and the run's `at`/`completed-tx`/
   `terminated-tx` derived from the record transaction. Before: cold 8 commits, 545 KB, 1,592 ms; after: 3 commits.
   Proof: `seon.test-test/an-executed-proof-request-commits-twice`. **Row change:** EN-6 counts only the recording
   branch; name the checkpoint commits too.
5. **Merge accept: 5 → 2 + record.** MA-11: the merge transaction carries the task outcome and the cluster's
   admission closure; `:seon.merge/merged-by` is the merge transaction's `agent`, not a new attribute; the merge
   request id goes on the transaction. Also drop the copied `seon.turn/*`/`seon.cluster.eval/*` rows from the merged
   program (they are in today's 103-datom merge commit), as NK-8a/MA-11 already intend. Proof: MA-11's
   `…/a-merged-definition-carries-no-evaluation-pointer`.
6. **No empty commits; one registration per agent.** New row (no existing one). `src/seon/cluster/host.clj:73-90`:
   fork the agent's branch after the home registration so it inherits the row, and skip the transaction when the
   writer's pre-read already holds the matching row (the admission fence stays with the run writer). Alternative in
   our Datahike fork: a no-op transaction answers its report without committing. Before: 2 registration commits per
   new agent, 39 empty re-host commits in default's history; after: 1 and 0. Proof: a new
   `seon.cluster.host-test/an-existing-agent-rehosts-without-a-commit`.
7. **Retirement in one transaction per branch.** New row: the retiring callers close admission for every agent of a
   branch in one transaction (80 single-datom commits on default).
8. **Boot facts in one commit; schedule fire as one entity.** Low frequency: fold the process row, source digest and
   commit id into one boot commit (`src/seon/cluster.clj` `ensure-cluster-entity!`/`populate-source!`), and the
   fire's three entities into one with tx metadata (`src/seon/schedule.clj:293`).
9. **SR-GROW** is answered by §1: fixed 41 KB retained + 29 KB head per commit, growing with root fan-out; ~1.5-2.5
   objects per distinct attribute; random-id unique attributes dirty mid-AVET leaves. Its fix rows are 1-8 above.
   One Datahike-level option for the fork, write I/O only: a branch head that names the commit id instead of copying
   the 29 KB record.

S6-05 maps to the `basis-t` attribute: the answered basis of an eval request is the basis on its own commit.
EN-1 could drop `:seon.effect/pass` from the receipt: the receipt's open transaction carries `eval-request`, and
recovery reads few open receipts entity → tx. Keep the receipt entity itself; its open state is what recovery needs.

## 6. Health, limits and cleanup

**default's health as seen** (06:11 boot, before root's 06:34 restart): state `:faulted`; over-bound warnings 10-13
(`seon.batch/evaluate-batch` max 26,813 ms, `seon.batch/evaluate-step` 26,768 ms, `seon.cluster/adopt-entities!`
4,836 ms, `seon.fn.analyzer/analyze` 10,029 ms, `seon.fn/analyzed-files` 14,736 ms,
`seon.cluster.registry/referenced-blobs` 3,145 ms, `seon.problems/problems` 3,235 ms), errored receipts 53, open faults
6, unowned namespaces 26, regressions 12; cache 288-310 MB of 512 MB; heap used 2.4-4.4 GB of 11 GB; ready in
18,174 ms; store 166,693 files, last collection reclaimed 1.05 GB (1.35 GB → 302 MB). After the restart: state
`:faulted`, `seon.fn.analyzer/analyze` max 5,965 ms at the top.

**Limits.** Default's numbers for task, error and the first SCI/`defn` windows come from file modification times
before I had the write hook; they are marked approximate. Default is shared with other lanes; every listener row names
its branch, and rows that were other lanes' are excluded. The model pass, test, merge and error flows were measured on
the throwaway (HEAD `4707b9546`, fresh store) because a fixture-provider model pass needs `seon.test-support` and a
quiet store; their bytes are smaller than default's would be (commit record 13.6 KB vs 28.9 KB). Per-transaction
latency is measured only for raw commits (15-20 ms on default). My probe's `files-since` scan (5.3 s over 167 k files)
is the probe's cost, not Seon's.

**Cleanup.** Listeners, write hook and the probe namespace removed from default's JVM before the restart, and again
from the restarted JVM; the throwaway root stopped. Left on default: agent `tx-census` with branch
`:agent-b318b1e54d35` (retiring an agent's own branch from itself is refused, task 7116117e6aaf), task
`tx-census.scratch/census-probe` (`fa26326c3f00`) assigned to it, and its scratch definitions. `:tx-census-lane`
retired. `tmp/tx-census-opus/` holds the disposable drivers and the throwaway root.
