---
type: research
status: complete
tags: [database, datahike, performance, evidence]
---

# Per-cluster history creation and replay evidence

## Answer

The pinned Datahike fork stores `:keep-history?` in each branch record and its
writer consults that branch database value. The physical store can therefore
contain records with different settings. Its supported branch operations do
not, however, create that state: `branch!` copies the selected stored database
record and changes only `[:config :branch]`, while `fork-database` classifies
`:keep-history?` as a baked semantic key and refuses a mismatch. There is no
maintained history-on → history-off branch conversion.

The safe mechanism is consequently one representation per operator-root store:
ordinary/user roots remain history-on; isolated scratch/eval roots may be
created history-off. A mixed policy inside one existing store still requires a
Datahike conversion operation with rebuilt indices, commit ancestry, reopen,
branch, and garbage-collection proof. Seon must not manufacture such a record
by editing its config.

Commit `918d33623` lands the unprotected part of that mechanism:

- fresh stores accept `:seon.config.db/keep-history?` at `open-store!`;
- an omitted reopen derives the setting from the persisted `:db` branch record;
- an explicit conflicting reopen refuses `::keep-history-mismatch` before
  connecting;
- descendant branch connections use the root connection's representation; and
- non-temporal config reconciliation uses its one explicit adoption seam rather
  than requiring first-assertion history.

Commit `6ce45b4eb` separates compile from reconciliation through
`seon.config/apply-compiled!`, so the protected boot owner can compile the
manifest once before opening the creation-fixed store and apply the same value
after the branch connection exists.

The config-fact declaration and boot threading remain at the protected schema /
cluster boundary described below. The issue therefore remains open.

## Dependency ledger

The gitlink and checked-out Datahike source are both
`0e8601d7f2f68c01070e13a95483bc82be04cabc`.

- Database construction creates temporal indices only when `keep-history?` is
  true: `reference-code/datahike/src/datahike/db.cljc:907-957`.
- The writer persists temporal roots according to the branch database value:
  `reference-code/datahike/src/datahike/writing.cljc:477-552`.
- Supported branching copies the stored database and changes only its branch:
  `reference-code/datahike/src/datahike/versioning.cljc:237-289`.
- Cross-database fork validates `keep-history?` as a baked semantic setting:
  `reference-code/datahike/src/datahike/versioning.cljc:504-600`.
- Connection consistency does not auto-adopt `keep-history?` from the stored
  config: `reference-code/datahike/src/datahike/connector.cljc:140-235`.
- Datahike's flat migration establishes the supported datom replay shape:
  `reference-code/datahike/src/datahike/migrate.clj:8-41`.

The first-party owners are `src/seon/cluster/store.clj:132-397`,
`src/seon/reconcile.cljc:114-137`, and `src/seon/config.clj:239-270`.

## Temporal reader census and disposition

The production census contains three direct `d/history` readers:

1. `src/seon/reconcile.cljc:114-137` was boot-critical. It now asks its actual
   question: temporal databases derive the first assertion transaction;
   non-temporal databases have no such provenance and return an empty mapping.
   Config explicitly adopts only its own singleton identity, after which
   ordinary current-index convergence is idempotent. The non-temporal
   regression proves the fixture really refuses `d/history`, then proves one
   write and a zero-write reapply.
2. `src/seon/schema.clj:528-593` already catches absent history and fails closed
   for agent-authored provenance. It still invokes Datahike before catching,
   which made the isolated boot log the same Datahike refusal repeatedly. The
   protected database/schema owner should route this through the one temporal
   database-view owner described below so expected absence is an error value,
   not logged exceptions.
3. `src/seon/cluster/registry.clj:300-316` already catches absent history and
   marks current blob references only. That is the correct reachability answer
   when temporal datoms do not exist, but it should use the same temporal view
   owner to avoid exception logging.

There are no other production `d/as-of` or `d/since` callers. The temporal API
owner in `src/seon/db.clj:390-438` is protected by the write-custody lane and
already converts thrown failures into flat `:seon.error` values. Its
`database-view` should detect a non-temporal database before calling Datahike,
return the existing flat error shape with an explicit
`:seon.db/non-temporal-database` reason, and be the one place schema/registry
use. That exact change prevents both throws into boot and repeated Datahike
error logging. `reconcile.cljc` remains portable and owns the different,
domain-specific answer that absent first-assertion history means explicit
adoption is required.

## Physical replay measurement

The committed instrument is
`research/scripts/per-cluster-history-2026-08-02.clj`, SHA-256
`3ee724cb3e5dd52ee3a853b8a215eea79f3252b3a064b1ca63b4f39f772f8fc4`.
It opens only the prior census copy, selects the archived completed GPQA branch
`:inspect-grade-inspect-5c022394c4594117-fc8963cc`, groups its historical
datoms by their original transaction, and replays one source transaction per
target commit. Both cells use fused roots and diff buffer 256; only
`:keep-history?` differs. The script asserts the complete non-clock current
projection is identical before reporting bytes.

The retained evidence root is
`tmp/per-cluster-history-f70a950f-85b3-4af7-b856-216cf165441e`.

| Cell | Base B | Final B | 64-transaction episode growth |
|---|---:|---:|---:|
| History on | 20,324,614 | 30,838,480 | 10,513,866 B |
| History off | 11,852,969 | 18,386,657 | 6,533,688 B |

The measured episode saving is **3,980,178 B / 37.856%**. Both results contain
38,712 identical current domain datoms and end at the same maximum transaction.
The full reconstructed branch is 12,451,823 B / 40.378% smaller history-off.
This is a physical replay, not the earlier codec allocation or subtraction
projection. It is close to, but deliberately does not restate, the projected
4.061 MB per-sample reduction: the replay reconstructs one archived branch in
fresh stores and therefore has its own exact commit envelopes.

## Store and live evidence

Focused source-classpath verification after the implementation ran
`seon.cluster.store-test` plus `seon.reconcile-test`: **23 tests, 78 assertions,
0 failures, 0 errors**. It proves:

- the default history-on store answers `history`, `as-of`, and `since`;
- a history-off store creates and reopens without temporal indices;
- an explicit conflicting reopen refuses;
- a descendant history-off branch performs current reads and writes; and
- non-temporal reconciliation converges without a second transaction.

An isolated live root at `tmp/per-cluster-history-live-off-20260802` was created
with history off. `bin/seon --root ... init` successfully published
`:current-src` commit `6a6fe395-62a7-5ee3-95c4-de3c75b72024`, proving source
population and reopen over the non-temporal main branch. Cluster start then
opened `:cluster-eval-history-off` with `:keep-history? false` and advanced past
the repaired reconciliation reader.

READY / agent-turn / render proof is blocked by the concurrent protected schema
lane, not by this history mechanism: startup exits at
`seon.schema.internal/contract-error!` because `:seon.ai/usage` currently uses
forbidden `:any` in an agent-authored contract. The focused config gate also
sees that lane's incomplete addition of
`:seon.config.ai.backup/api-key-variable`: the schema registers it while
`config/default.edn` does not yet decide it. Per the task instruction, this lane
stopped the live gate and did not edit or resume the protected lane.

## Exact protected integration change

The protected owner should land these as one coherent change:

1. `resources/seon/schema.edn`: declare
   `:seon.config.db/keep-history?` as
   `[:boolean {:seon.config/dial true}]` in the config section.
2. `config/default.edn`: add
   `:seon.config.db/keep-history? true` with creation-fixed provenance. This file
   is owned here, but adding it before the protected schema declaration would
   make the shipped manifest invalid, so it was intentionally not landed alone.
3. `src/seon/cluster.clj`: in `start!`, compile the selected manifest once with
   the resolved cluster name before `stack-tower!`; pass the compiled value into
   the tower; pass its effective history boolean to `acquire-root-store!`; call
   `store/open-store!` with that key on first acquisition; and use the landed
   `config/apply-compiled!` after opening the cluster branch.
4. In the existing-held-store arm of `acquire-root-store!`, compare the requested
   boolean with `get-in @main-connection [:config :keep-history?]` and refuse a
   mismatch. This states the real constraint: two clusters requesting different
   representations must use different operator roots until Datahike gains a
   supported branch conversion.
5. `src/seon/db.clj`: make the temporal database-view owner return its existing
   flat error value before calling Datahike on a non-temporal database; migrate
   `src/seon/schema.clj` and `src/seon/cluster/registry.clj` to that one owner.
6. Add the creation-fixed config application ledger row and paired isolated-root
   live proof. The history-off half must reach READY, run a real agent turn,
   render context, and return the flat temporal error; the history-on half must
   answer all three temporal views.

Until those protected edits and the blocked live gate land, the honest exposed
mechanism is history policy per isolated operator-root store, not mixed policy
among branches of one process-root store.

## History-unblock follow-up 2026-08-03

The protected source-row admission dependency is now removed. Program rows
record `:seon.schema.admission/source` at admission, and projection
classification reads that current fact rather than temporal history. The
history-off boot regression passes; the focused AI, admission, and program
gate passed 48 tests / 207 assertions, the runtime settlement gate passed 46
tests / 264 assertions, and the complete boot namespace passed 28 tests / 133
assertions. The final ten-namespace schema/config/database/AI/program/runtime
gate passed 148 tests / 732 assertions.

Paired isolated roots then published source digest
`1ee19366384d9b520ba3393be69256d1367f0f64ffd77c225913ccb6a10c1514`
and reached READY. The history-on root produced database values for `history`,
`as-of`, and `since`; the history-off root produced the flat
`:seon.db/non-temporal-database` value. Both were shut down cleanly.

The real-turn half falsified the preceding temporal reader census. That census
found direct `d/history` calls but missed Datalog joins against transaction
entities. A 204 message opened run
`3192cbaa-dbd4-45b0-9124-64b1f3b31bee` on the history-off root. Its identity
datom reports opening transaction `536870956`, but `(pull ?tx [*])` returned
nil; `seon.cluster.message/trigger` therefore returned nil. Prompt construction
committed repeated `:seon.cluster.prompt/no-trigger` errors before a context
capture or provider attempt could exist. The implicated current owners are
`src/seon/cluster/message.clj:71-99` and
`src/seon/cluster/work.clj:387-490`.

This is not repaired by trusting non-temporal rows or by weakening prompt
custody. The remaining design must record causal facts directly and migrate
every consumer together: run trigger, answeredness, conversation-chain depth,
and episode bounds. The exact live exit remains one history-off DeepSeek turn
with a committed context capture.

## Final graduation proof — 2026-08-03

The causal relationship is now ordinary data. Run opening records
`:seon.cluster.run/trigger`; delivery records
`:seon.cluster.message/caused-by`; all causal consumers query those refs rather
than transaction entities. The dedicated non-temporal regression proves the
opening transaction entity is absent while `seon.cluster.message/trigger`
still returns the triggering message. The complete focused checkpoint passed
213 tests / 1,069 assertions, and the final direct runtime checkpoint passed
84 tests / 464 assertions.

Fresh isolated history-off and history-on branches were reforked from the same
published source digest
`66c3301646b4c243cc598d4df1d0e1702cf2a29d3a3a25d115125bd6d563fcda`.
The history-off root returned the flat
`:seon.db/non-temporal-database` error from `history`, `as-of`, and `since`;
the history-on root returned three Datahike database values.

HTTP submission to the history-off root returned 204 and completed run
`62b82530-4b06-40b6-8598-110bcfd03b28`. Its recorded trigger was
`inbound-536870954-0`; the run closed with one terminal receipt, one committed
context capture, and one real `deepseek-v4-flash` attempt. The attempt recorded
6,898 prompt tokens, 28 completion tokens, and 6,926 total tokens. The captured
prompt was 21,313 characters, the derived context render was 9,035 characters,
and `/ns/my.agents.root/debug` returned HTTP 200 with 85,731 bytes. Both roots
were taken down through `bin/seon down`; each reported `0/0 clusters alive`, a
readable offline roster, and no orphan Seon JVMs.
