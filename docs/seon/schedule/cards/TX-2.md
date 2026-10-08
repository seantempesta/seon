---
title: "Card TX-2: Retiring several agents closes their cluster rows in one transaction"
type: reference
status: active
created: 2026-10-08
---

# TX-2 Retiring several agents closes their cluster rows in one transaction

New row (group E/M). Census §0.5: 80 commits on default's `:cluster-default` carry exactly one datom,
`:seon.agent/admission-closed-tx`. Measured on default (2026-10-08, JVM read of every `admission-closed-tx` datom and
its transaction): all 80 are separate `seon.db/transact!` commits, stamped by the retiring agent: root 47,
landed-review-sol 11, redo-entrants-probe 5, redo-review-opus 4, … ; they come in bursts from one retirer within one
minute (10 at 12:43, 8 at 11:05, 8 at 12:47, 5 at 12:42, 5 at 12:27). Each is one call of `seon.cluster.host/retire!`
(`src/seon/cluster/host.clj:198-222`): one transaction on the agent's branch row, then one on its cluster row.
Lane: Sonnet. Cited at HEAD `e7f120672`.

## Change
- ADD `seon.cluster.host/retire-all!`
  `{:malli/schema [:=> [:cat [:vector ::host]] [:or :nil :seon.db/invalid-read-error :seon.db/transaction-refused-error :seon.await/timeout-error]]}`
  `[hosts]`: (1) each host's **branch** row closes in its own transaction (different branches: they cannot share one,
  and the branch row is the run writer's fence), (2) the **cluster** rows of all hosts that share one parent
  connection close in ONE transaction (`(mapv (fn [e] [:db/add e :seon.agent/admission-closed-tx "datomic.tx"]) eids)`),
  (3) `stop!` each. A refused read or write answers as `retire!` does today, and nothing further happens for the
  hosts after it. `retire!` becomes `(retire-all! [host])`.
- ADD `my.agent/retire-branches!`
  `{:malli/schema [:=> [:cat [:map [:seon.store/branches [:vector {:min 1} :seon.store/branch]]] :my.agent/branch-context] [:or [:vector :my.agent/retired-branch] :seon.error/value]]}`:
  `retire-branch!`'s checks per branch, then one `retire-all!` over the hosted ones, then each branch's release and
  roster removal as `retire-branch!` does (`src/my/agent.clj:186-208`). Docstring example
  `(my.agent/retire-branches! {:seon.store/branches [:lane-a :lane-b]})`; the help page that lists `retire-branch!`
  lists it too (`rg -n 'retire-branch!' src/seon/bootstrap.clj`).
- `seon.pass-probe` (`test/seon/pass_probe.clj`) and the census probes retire their agents with it at the end of a run
  instead of one by one (`run-mcp`, `run-model`, `run-check`'s `finally`), so the measuring tools stop producing the
  bursts.

## Proof
`seon.cluster.host-test/retiring-several-agents-closes-their-cluster-rows-in-one-transaction` (new): host three agents
on three branches; `(my.agent/retire-branches! {:seon.store/branches [b1 b2 b3]})` as root; the three cluster rows'
`:seon.agent/admission-closed-tx` values are one and the same transaction; each branch row has its own; a later
eval request for any of them is refused as retired (`seon.mcp/execution`'s closed-admission arm).

## Files / commit
`src/seon/cluster/host.clj`, `src/my/agent.clj`, `resources/seon/schemas/my.agent.edn`, `src/seon/bootstrap.clj` (help line),
`test/seon/pass_probe.clj`, `test/seon/cluster/host_test.clj`.
`bin/lane commit <lane> -m "TX-2: retiring several agents closes their cluster rows in one transaction" --test seon.cluster.host-test --test my.agent-test --test seon.pass-probe-test src/seon/cluster/host.clj src/my/agent.clj resources/seon/schemas/my.agent.edn src/seon/bootstrap.clj test/seon/pass_probe.clj test/seon/cluster/host_test.clj`

## Cost
A burst of N retirements: N + 1 commits instead of 2N (each ~41 KB + 29 KB head on default).

## Dependencies
TX-1a (`host.clj`). GV-2 renames `retire-branch!` → `delete-branch!`: whichever lands second names this
`delete-branches!`. Reset: none.
