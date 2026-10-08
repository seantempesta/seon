---
title: "Card TX-7: Boot's source facts in one commit; a schedule firing is one entity"
type: reference
status: active
created: 2026-10-08
---

# TX-7 Boot's source facts in one commit; a schedule firing is one entity

New row (low frequency; group M/O and E). Lane: Sonnet. Cited at HEAD `e7f120672`. Two slices.

## TX-7a: boot's source facts
Census (Opus §2): default's history holds 26 + 26 + 24 single-purpose boot commits (`:seon.db.process/id`, source
digest/built-at, `:seon.source/commit-id`); an existing-store boot is 3 commits. Owners: `seon.cluster/ensure-cluster-entity!`'s
process row (TX-5 composes it), `seon.cluster/populate-source!` and the commit-id record (`rg -n 'defn populate-source!\|seon.source/commit-id' src/seon/cluster.clj src/seon/cluster/boot.clj`).
Change: boot composes the process row, the source digest/built-at and the commit id into the one seeding transaction
TX-5 builds (`extra-tx`), when they change. Proof: `seon.cluster.boot-test/a-boot-on-an-existing-store-writes-one-commit`
(new; on a throwaway root through `seon.operator/prepl-value!` as the census did): the cluster branch's basis advances
by one transaction across a restart with no program change. Commit:
`bin/lane commit <lane> -m "TX-7a: boot writes its source facts in one commit" --test seon.cluster.boot-test src/seon/cluster.clj src/seon/cluster/boot.clj test/seon/cluster/boot_test.clj`.
Depends on TX-5.

## TX-7b: a schedule firing is one entity
Census (Opus §2): a firing is `seon.schedule/fire-call` with 26 datoms across `:seon.maintenance.request/*`,
`:seon.maintenance.receipt/*` and `:seon.schedule.fire/*`, each naming the agent, task, handler and instants again,
then `settle-call` (8). Owner: `src/seon/schedule.clj` (`fire-call`, `:293` per the census; `rg -n 'defn fire-call\|defn settle-call' src/seon/schedule.clj`).
Change: the firing writes one `:seon.schedule.fire/*` entity holding the schedule ref, handler and its open state;
the request and receipt entities are deleted with their readers (`rg -ln 'seon.maintenance.request\|seon.maintenance.receipt' src`),
and agent/operation/instant come from the transaction (TX-0's stamp: the firing runs with the schedule's agent world, or
passes `:seon.db.transaction/agent` in its request map). The settle stays a second commit by design (the open fire is
what recovery reads, as an effect receipt). Before cutting, list each reader of the two retired families and convert
it in the same commit; if a reader needs a fact only the request/receipt holds, move that fact onto the fire entity.
Proof: `seon.schedule-test/a-firing-writes-one-entity` (new): after one firing, the transaction of `fire-call` holds
datoms of exactly one entity besides the transaction entity, and `settle-call` completes it. Commit:
`bin/lane commit <lane> -m "TX-7b: a schedule firing is one entity; who and when are the transaction's" --test seon.schedule-test --test seon.maintenance-test src/seon/schedule.clj <readers> resources/seon/schemas/seon.schedule.fire.edn resources/seon/schemas/seon.maintenance.request.edn resources/seon/schemas/seon.maintenance.receipt.edn test/seon/schedule_test.clj`.
Depends on TX-0.

## Cost
Boot: 3 → 1 commits per restart; firing: 26 → ~8 datoms.

## Reset needs
TX-7b retires two entity families: adopt the schema retirement on a branch of the live store (agent-loop.md §5); their
stored rows are discarded data (owner: DB data is disposable).
