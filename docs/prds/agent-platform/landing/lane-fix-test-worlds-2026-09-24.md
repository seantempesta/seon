---
type: landing
status: partial
lane: fix-test-worlds
created: 2026-09-24
report: docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md
---

# Lane fix-test-worlds (2026-09-24)

`seon.test-support` already has the canonical writers: `namespace-row`
(through `program/declaration-row`), `agent-tx` (through
`agent/creation-tx`) and `program-fn-row`. The failures came from two places.
Call sites still hand-built namespace, schema and agent rows. And `agent-tx`
asked the database for its one cluster, although a fixture branch inherits the
cluster it was acquired from. This slice converts the call sites in the owned
files. It also makes the fixture name the cluster it seeded.

## Still broken (not in this slice's files)

- **`test/seon/fn_test.clj` still hand-builds namespace rows.** This file is held
  by another lane. 7 digest refusals remain in run 35cca646b1a6. The sites are
  lines 74, 930, 1196, 1257, 1311, 1407, 1478, 2206–2207, 2239, 2290, 2744 and 2750.
  - Each `{:seon.ns/name 'x ...}` that creates a namespace should be
    `(test-support/namespace-row database 'x)`.
  - `{:seon.ns/name 'seon.db}` at 2207 upserts an existing row and can stay.
  - The same class appears in about 45 other test files, from
    `grep -rn "{:seon.ns/name '" test/`. That wants one scripted sweep by root.
    Issue: `docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md`.
  - No production writer is involved. The turn-writer issue is already resolved,
    so nothing is owed in `src/seon/turn.clj`.
- **`test/seon/error_test.clj:93` hand-builds a namespace row.** The row is
  `my.error-graph` with a steward, so the `error-identity-and-occurrences-are-owned-by-the-writer`
  fixture refuses. This file belongs to another lane.
- **error-test counts include the live cluster's data.** Once the fixture
  cluster was fixed, the reds that remain in error-test count the live
  cluster's rows: default's error message and its "root" agent. Filed as
  `docs/seon/issues/test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md`.
- **Publication is refused.** At 17:07Z every save was refused with "branch
  :cluster-default-candidate still has a connection in this process". It
  cleared on its own a few minutes later. My last db_test edit then published,
  after commit 88e3bfb32. That edit is the
  `transaction-success-and-refusal-shapes-are-declared` fixture: a canonical
  `agent-tx` row with its id replaced by 42, instead of calling `agent-tx` with 42,
  which its contract refuses. Run 6014cbf51b17 gave 9 pass / 0 fail / 1 error, and
  the error is foreign live-cluster drift (`status-faulted-…` added by a concurrent
  test). Filed as
  `docs/seon/issues/publication-refuses-while-the-candidate-branch-holds-a-connection.md`.
- **`seon.shell.jvm-test` is not this class.** Its "Publish the canonical program
  into a physical store…" lines are declared-long exclusions of
  file-backed fixtures, and they are not failures. Its single red is
  `capture-task-completion-is-bounded-and-interrupts-only-that-task`
  (`jvm_test.clj:175`, `:seon.error/member` is nil). It is unrelated to fixtures
  and was not investigated.
- **Foreign reds seen during the runs:**
  - Worker-global drift, from concurrent publications of `seon.turn/preview-sources`,
    `seon.render/acquire-context!` and `seon.sci.kernel/invoke`.
  - `seon.test/green?` refusing a result that lacks `:seon.test/run-basis-t`
    (`src/seon/test.clj:1874`). Whole-namespace `seon.call-preparation-test` requests
    failed with "PREPL evaluation failed" because of this.
- **`bin/seon status` threw earlier today.** At 16:12Z, `seon.cluster.boot/readable-response`
  refused its own output because `:seon.error/at` was missing. This is foreign and was
  not investigated.

## Changes

- `seon.test-support/seeded-cluster-name` now takes the clusters in the fixture
  database, minus those in the database its execution handle acquired
  (`:seon.db/db`, `src/seon/cluster/agent.clj`, `acquire-context!`).
  - If that leaves exactly one cluster, the fixture seeded it and that is the answer.
  - If it leaves none, the answer is the handle's own `:seon.cluster/name`, when present.
  - `fork-cluster-ctx` and `agent-tx` both use it. Both are contracted, and
    `cluster-names` is split out.
- `call_preparation_test`:
  - Five namespace rows now come from `test-support/namespace-row`.
  - The synthetic `:sample/marker` schema row now comes from `program/declaration-row`,
    so it carries its definition digest.
- `agent_namespace_test`:
  - Namespace rows come from `namespace-row`.
  - The hand-built agents "owner" and "worker" (no `:seon.agent/branch`) now come
    from `agents-tx`, then an ordinary reassignment. The assigned namespace stays
    unstewarded.
- `db_test`:
  - Namespace rows come from `namespace-row`, and shared-namespace agents come from `agent-tx`.
  - The renderer fixture declares a `:malli/schema` through `program-fn-row`. The
    hand-assoc'd source and arglists are dropped, so the renderer contract rule
    admits it.
  - The incomplete-create refusal now names `:seon.program/definition-digest`,
    the required key the schema reports first.
  - `db/transact!` one-arity under custody answers `:seon.db/tx`, and the test checks
    that instead of `:db-after`.

## Evidence

All runs used `bin/test-check default --ns NS`.

- Before: default ran HEAD, from 16:05Z.
- After: default pid 70372 ran the checkout, with my edits published and verified
  by `resolve 'seon.test-support/cluster-names` and the published test source.
  Test evidence had been wiped by root's nuke, so every member executed.

| namespace | before | after |
|---|---|---|
| seon.cluster.agent-namespace-test | c2f14f1251b4: 13 pass / 0 fail / 2 err (digest, agent/branch) | 17c4ef1247f5: 22 pass / 0 fail / 1 err (foreign drift `seon.turn/preview-sources`) |
| seon.call-preparation-test | ccea435d271e: 75 / 5 / 14 (7 digest) | whole-ns request crashed in `seon.test/green?` (foreign). The 7 class members run as `--test`, b776b2f8d8e1: 30 pass / 0 fail / 1 err (foreign drift `seon.render/acquire-context!`) |
| seon.db-test | 70633df53533: 453 / 34 / 9 (5 digest, 1 one-cluster, 1 agent-tx 42) | 7ca530fa0c17: 470 / 40 / 6; 0 digest, 0 cluster, 0 agent/branch (75 executed vs 16) |
| seon.error-test | a5145bc70222: 314 / 18 / 13 (9 one-cluster) | 3851b614fe6b: 258 / 22 / 3; 0 one-cluster, 0 digest; the remaining counts are the inherited-world issue |
| seon.fn-test | 634024c9cfb7: 337 / 8 / 15 (11 digest + 1 setup) | 35cca646b1a6: 161 / 7 / 8; 7 digest remain, all in fn_test.clj hand rows |
| seon.shell.jvm-test | 3042ed55be33: 3 / 1 / 0 | 154665e6af14: 3 / 1 / 0 (same unrelated red) |

- Of the 25 members that failed before in this lane's three classes:
  - 18 pass after.
  - 7 are error-test members that now reach their real assertions and fail on the
    inherited-world counts.
- An earlier snapshot run of the same 25 members agreed. It was run
  9dbd92cf30cd on a scratch root, before root's one-JVM ruling. That root has
  been stopped (pid 63373) and deleted.
- Cost: the added query is `cluster-names`, at 0.14 ms per call on default
  (100 calls, JVM mode). `agent-tx` and `fork-cluster-ctx` call it twice.

## Sizes

- Source: 0 lines.
- Tests: +59 / −58 (net +1).
  - `test_support.clj`: +23 / −11
  - `call_preparation_test.clj`: +9 / −9
  - `agent_namespace_test.clj`: +10 / −17
  - `db_test.clj`: +17 / −21

## Verification limits

- The db_test `42` fixture member passed its assertions, but it was not green: it carried foreign drift.
- The whole call-preparation namespace was not run green. Only its class members
  were run, because the request itself crashed (foreign).
- No class regression was added. The existing members are the regression: they
  refused at the fixture write before and pass now.
