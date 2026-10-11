---
title: "Card TX-6: A merge commits once on the target; a merge request and its message are one home transaction"
type: reference
status: active
created: 2026-10-08
---

# TX-6 A merge commits once on the target; a merge request and its message are one home transaction

New row (group M; constrains MA-11). Census: `accept-merge!` is 5 commits (Opus §2: merged program 103 datoms with
copied `seon.turn/*` and `seon.cluster.eval/*` rows, task outcome 5, the cluster row's admission closure 4, the agent
branch's closure 4, root's eval record 549); `request-merge!` writes the merge request (18) and root's message (9) as
two home commits (Astra: "2 → 1 home commits; full installed proposal 5 → 4"). Lane: Sonnet. Cited at HEAD
`e7f120672`. Two slices.

## TX-6a: the merge transaction (folded into MA-11's `merge!`)
MA-11 replaces `accept-merge!` (`src/seon/cluster/source.clj:941`, `src/my/program.clj:547`) with one `merge!`. Its
merge transaction on the target branch carries, in the same `db/transact!` with `:parents`:
- the merged program's rows **without** `seon.turn/*`, `seon.cluster.eval/*` and `:seon.program/defined-in` (data
  partition: `replacement` in `source.clj` selects rows by `:seon.program/partition :seon.program`; check that the
  copied run rows come from a declaration's ref and drop the ref, which is NK-8a/MA-11's "pointer dropped at merge");
- the merge request's outcome (today `seon.task/decide!`'s data: use its tx builder, `rg -n 'defn decide' src/seon/task.clj`,
  inside the same transaction);
- the source agent's **cluster** row admission closure
  (`[:db/add [:seon.agent/id agent] :seon.agent/admission-closed-tx "datomic.tx"]`);
- transaction metadata: root's `:seon.db.transaction/agent` and `eval-request` (TX-0, from root's world) and the new
  `:seon.db.transaction/merge-request` (the merge request id; add it to `seon.db.transaction.edn` as
  `[:string {:min 1 :description "The merge request this merge commit carried out."}]`, unindexed, written only here).
No `:seon.merge/merged-by` attribute is added (the merge transaction's `agent` is it).
The agent branch's own closure stays one transaction on that branch (the branch is then deleted, owner storage ruling).
Proof (added to MA-11's proofs): `seon.merge-policy-test/a-merge-is-one-transaction-on-the-target` — after `merge!`
commits, the target's transactions after the merge base number 1 (plus root's eval record), and it carries
`:seon.db.transaction/merge-request`; MA-11's `…/a-merged-definition-carries-no-evaluation-pointer` covers the dropped rows.

## TX-6b: the merge request and its message
- CHANGE `seon.task/request-merge!` (`src/seon/task.clj:669-694`; GV-4 renames it `request-merge!`): optional last argument
  `message-candidate` (the value `seon.cluster.message/send` builds, `src/seon/cluster/message.clj:786`); when present
  its `[:db.fn/call #'seon.cluster.message/send-call candidate agent-id]` (`:803`) joins the `written` transaction.
- CHANGE `my.program/request-merge!` (`src/my/program.clj:416`): builds the candidate with `message/send` (same content
  as today's `send!` at `:464-467`) and passes it; delete the separate `send!`.
- Proof: `my.program-test/a-merge-request-and-its-message-are-one-transaction` (new): after `request-merge!`, the merge
  task row and root's message share one transaction on the home.
- Commit: `bin/lane commit <lane> -m "TX-6b: a merge request and root's message are one home transaction" --test my.program-test --test seon.task-ingestion-test src/seon/task.clj src/my/program.clj test/my/program_test.clj`

## Cost
Accept: 5 → 2 commits + root's record. Propose: 2 → 1 home commits.

## Dependencies
TX-6a: inside MA-11 (and TX-0 for the metadata). TX-6b: none; serialize with GV-4/GV-5 on `task.clj`/`program.clj`.
Reset: none.
