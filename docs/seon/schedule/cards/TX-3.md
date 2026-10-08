---
title: "Card TX-3: An error is recorded once per signature at the home; repeats only count"
type: reference
status: active
created: 2026-10-08
---

# TX-3 An error is recorded once per signature at the home; repeats only count

New row (group F). CLAUDE.md "Deliver every error to whoever can act on it": "the entrant records Seon's own errors
once per signature at the task home, with a count, a task and one notification; repeats only count." Census:
`seon.fault/record!` (`src/seon/fault.clj:138`) commits each core error on the agent's branch **and** on the cluster
home (Opus: 57 datoms each, then the eval record: 3 commits; Astra: origin + home 33 logical datoms each, 100.8 ms /
193,882 bytes), and a repeat from a later eval request writes both again (Opus 42 + 42; Astra 20 + 20, 51.4 ms /
118,344 bytes); the root notification then wakes root (4 more commits on HEAD). Lane: Sonnet. Cited at HEAD
`e7f120672`. Two slices.

## Why repeats are not folded today
`record!` folds a repeat only when `seon.error/repeat-of` (`src/seon/error.clj:1225-1249`) says the write would only
count: when the signature's **last capped slot** is already written. The slots are
`:seon.config.error/recurrence-limit` occurrences (`config/default.edn:257`: `3`), so the 2nd and 3rd occurrences each
write a full occurrence. The fold machinery itself (`fold!`, `folded-tx` riding the next commit on that branch,
`bump-call` `error.clj:1251`) exists and works once the slots are full.

## TX-3a: one occurrence per signature
- `config/default.edn:257`: `:seon.config.error/recurrence-limit 1`. The dial stays (owner: every limit is a dial);
  its default now matches the law. Nothing else changes: the second occurrence of an open signature folds into a count
  carried by the next commit on that branch.
- Proof: `seon.fault-test/a-repeated-error-from-a-later-eval-request-commits-nothing` (new; the census's name was
  "…on-the-home"): record the same core error (the census fixture: `seon.fault/fault!` with an `ex-info` and a fixed
  operation, `tx-census-astra.md` "Error recording" driver) twice through two eval requests of one agent; the second
  `fault!` answers `:seon.fault/folded` for every target, the home's basis-t did not move between the two calls'
  answers, and after one more ordinary commit on the home the occurrence's `:seon.error.occurrence/count` is 2.
- Reaching: `seon.fault-test`, `seon.error-test`, `seon.cluster.fault-storage-test`, `seon.cluster.fault-message-test`;
  each assertion that expects up to three occurrences of one signature is converted to one occurrence plus its count
  (classify: these tests assert the old rule).
- Commit: `bin/lane commit <lane> -m "TX-3a: one occurrence per open signature; repeats only count" --test seon.fault-test --test seon.error-test --test seon.cluster.fault-storage-test --test seon.cluster.fault-message-test config/default.edn <converted tests>`

## TX-3b: the home holds the occurrence; the agent's eval request holds its reference
- CHANGE `seon.fault/record!` (`:138`): commit on ONE target: the cluster home when this process runs it and the
  cluster places the agent there (today's `copy?` condition, `:268-270`), else the connection's own branch (fixtures,
  test members: today's "no copy" cases, unchanged). Delete the origin write for the copy case (the `mapv commit-on`
  over two targets, `:271`). The origin-digest read stays (the home copy already names the origin branch and digest,
  `:224-233`).
- The agent learns of the error from its own eval request: the reply's diagnostics (already) and the evaluation's
  `:seon.cluster.eval/faults` references (S5-05 writes the observed signatures). Readers that looked for occurrences
  on the agent's branch read the home instead: `rg -n 'seon.error.occurrence' src` lists them; `seon.problems`'
  `open-faults` called with an agent branch value reads `(seon.task/home-connection connection)`'s value
  (`src/seon/task.clj`, the home owner).
- Proof: `seon.fault-test/a-core-error-is-one-commit-at-the-home` (new): one core error in an agent's eval request ⇒
  the agent's branch holds no `:seon.error.occurrence/id` datom for it; the home holds one occurrence naming
  `:seon.error.occurrence/branch` = the agent's branch; the evaluation's `:seon.cluster.eval/faults` names its
  signature (after S5-05).
- Commit: `bin/lane commit <lane> -m "TX-3b: a core error is recorded once, at the home" --test seon.fault-test --test seon.problems-test --test seon.cluster.fault-storage-test src/seon/fault.clj src/seon/problems.clj test/seon/fault_test.clj`

## Cost
Before: 2 error commits (+ the eval record) per occurrence, 2 per repeat; after: 1 per new signature, 0 per repeat
(the count rides a commit that happens anyway). Each saved commit ~41 KB + 29 KB head on default.

## Dependencies
TX-3a: none. TX-3b: S5-05 (the evaluation's reference), TX-0. E-SDELETE later folds `seon.fault` into `seon.error`;
whichever lands second keeps this rule. Reset: none.
