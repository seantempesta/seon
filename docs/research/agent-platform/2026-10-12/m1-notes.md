---
title: "M1 notes: where the code contradicts hybrid-done-and-merge M1"
type: research
status: lane m1, 2026-10-12. Contradiction 1 decided by the owner (below); M1 landed under that rule.
basis: code read by symbol in the working tree; one read-only probe on default (jvm mode as root, `seon.test/admit-run` called on main's value, nothing transacted).
---

# M1 notes

## Decision (owner, 2026-10-12, verbatim): "we don't need to run it again on main. that's an edge case. Keep things simple and performant."

None of options A, B or C: no `admit-run` change, no extra proof run, nothing stored. M1 judges the base only from
runs that already exist (a launched assignment's prove pass). A proof with no run at its base reach digest has an
unknown base result, which never counts as green and keeps today's fork point. `:satisfied` (no model turn) only when
every proof is green at the head and known green at the base; otherwise `:done` is today's rule. A hand-assigned task
with no base runs therefore behaves as today. Under this rule `the-outcome-recorded-at-the-end-equals-the-one-the-branch-shows`
stays green (its proof is unknown at the base); four `db_flow_test` tests went red, because a launched attempt's
green proof is now `:satisfied`: one became M1's no-turn proof, three start red and a provider reply redefines the
proof green (`fixed-chain-world`). Contradiction 2 is closed: lane cost-record2 released `seon.db`, so
`seon.db/commit-database` opens the base. Section 3's batch fix landed: one `result-at-reach` call per step for
all its proofs; the base read measured 3.9–5.4 ms warm for three real proofs (hybrid M1 row).

Left as findings: `seon.cluster.agent/basis-valid?` still calls `datahike.api/commit-as-db` itself and can call
`seon.db/commit-database`; `step-verdict` runs several times per pass (`task-finished?` in `proved-unanswered?`,
`next-agent-work`, the block), so the base read repeats each time.

The text below is the analysis that led to the decision.

## 1. A fork run cannot be recorded on the agent's branch (blocks proof 4, and through it proofs 2 and 3 for every agent that did not prove first)

**The hybrid says** (M1, §1 "Done, derived"): `seon.test/fork-point-red` "records its fork run on the agent's own branch
(`:seon.test.run/tested-branch` naming the fork), as the runner records a tested branch today".

**The code.** `seon.test/admit-run`, at the writer, refuses every run that is not a published snapshot when the run's
`:seon.test.run/branch` is not the destination's branch or when it carries `:seon.test.run/tested-branch` at all
(`:seon.test/cluster-mismatch`). The runner records `:seon.test.run/tested-branch` only for published-source
snapshots (`seon.test.runner/record-tx` and `prepare-failures!` on the snapshot path). Probe on default, main's
value, a one-member `:named` admission request built from `seon.test.runner/provenance`:

| provenance | `admit-run` |
|---|---|
| as captured | 1 datom group (admitted) |
| `assoc :seon.test.run/tested-branch :attempt-fork-x` | refused, `:seon.test/cluster-mismatch` |
| `assoc :seon.test.run/branch :attempt-fork-x` | refused, `:seon.test/cluster-mismatch` |

Admission also compares the destination's current program digest with the tested one (`seon.test.runner/program-digest`,
`program-written-since?`); a fork run tested the base program, which the agent's head no longer is.

**Second consequence, found while reading `seon.context/head-entries`.** If a base run were recorded on the agent's
branch after the head's run (the order `seon.turn/test-step` produces: proofs and reaching tests first, then
`record-broken!`), `seon.test.runner/latest-results`, which keeps the newest execution per test whatever its reach
digest, would answer the base run, and `proof-status` would show the head's proof `:pending` (its current digest is
not the base's). The head reader would have to read by the current reach digest (`result-at-reach` at the head's
digest) instead of the newest run.

**Why it matters for all of M1, not only proof 4.** Only an assignment started by `db<->flow` (`:seon.task/execution
:running`) gets the prove pass that runs its proofs at the base program before any form. A task assigned by hand
(`seon.task/assign!` to an outside or MCP agent, every lane today) never runs its proofs at the base, so with M1's rule
("at least one proof red at the merge base") it can never be `:done`: `fork-point-red` computes the answer on the
child and the child is released. Existing tests that go red under M1's rule for this reason or for M1's intended change:

- `seon.context-test/the-outcome-recorded-at-the-end-equals-the-one-the-branch-shows`: its proof is written on the
  agent's branch after the fork, so it is absent at the base under any option; it must be converted (declare the
  source before the fork, as `the-test-step-writes-nothing-on-main` does) and then needs option A or B below.
- `seon.cluster.db-flow-test/a-fresh-attempt-proves-first-and-green-proofs-end-it-with-no-model-call`: M1's intended
  change (green at the base is `:satisfied`, no `:done` end); converts into M1's no-turn proof (it already holds the
  only chain fixture, `chain-world`, which is private to that file).
- `an-ended-attempts-closed-merge-releases-its-connection-and-branch`, the A→B chain at `db_flow_test` 397 and the
  test at 634: they use "green proofs end it" as a fast route to a `:done` end; they convert to red proofs and
  `end-on-branch!` (the file's own helper), since their subject is the merge lifecycle, not the prove pass.
  `test/seon/cluster/db_flow_test.clj` is not in M1's owned files.

## 2. `seon.context` calling `datahike.api/commit-as-db`

AGENTS.md "Use the database owner": direct `datahike.api` calls belong in `seon.db`, store/registry and classified
branch-custody owners. The hybrid has `seon.context/step-observation` call `d/commit-as-db` directly and forbids a
new function. `seon.db` (where `branch-database` already does exactly this for a branch head) is held by lane
cost-record2. Smallest fix: `seon.db/commit-database [database commit-id]` beside `branch-database`, one line, after
cost-record2 releases `seon.db`; until then the call sits in `seon.context` as the hybrid says, beside
`seon.cluster.agent/basis-valid?`'s identical call.

## Options for contradiction 1 (root decides; the simplest viable first)

| | What | Guarantee | Cost | Sacrifice |
|---|---|---|---|---|
| **B** (simplest) | Every assignment proves first: `next-agent-work`'s `:prove` situation for a held task whatever its intent, and the MCP entrance's first eval request after an assignment runs the proofs before its forms (both on the agent's own branch, recorded there normally: tested branch = destination). `fork-point-red` stays as it is. | Every assignment whose branch head is its merge base at assignment has its base run. | `seon.turn` (`next-agent-work`, `prove-turn` for held tasks), `seon.mcp`/`seon.turn.batch` entrance; about one phase. | A branch that changed a proof's closure before its assignment (an agent that evaluated first, or a base moved by M11) still has no base result: `:working` until root re-forks or sends it back; edge 33/M11 must move the base only together with a rerun. |
| **A** (recommended) | The design as written: `seon.test/admit-run` admits a non-snapshot run with `:seon.test.run/tested-branch` (the fork child at the base commit) on the agent's destination, checking the tested program against the tested commit rather than the destination head; `seon.context/head-entries` reads each test by its current reach digest (`result-at-reach`) so a later base run never masks the head's; `latest-results`' other readers (`red-at-current-reach?`, `change-reds`, `seon.test` reuse) checked for the same masking. | Every (test, base digest) has a result after at most one fork run, whatever order the agent worked in. | The writer's admission path (Opus) plus the head reader; about two phases, with an admission proof and a masking proof. | A tested-branch that names a released child is provenance only; reuse must keep ignoring such runs as head evidence (it does today: `seon.test` filters on `tested-branch`). |
| **C** | Store the fork answer: `record-broken!` writes the proofs red at the fork beside `:seon.agent/broken`/`new-red`. | Simple; one transaction already written each step. | `seon.turn` only. | A stored judgment, which the hybrid rejected (third check A): stale when the base moves (M11) unless keyed by the base commit. |

Until root chooses, lane m1 lands only `seon.test.runner/result-at-reach` with its proof (needed by A, B and the
hybrid alike); the rule, `:satisfied` and the turn changes wait, because landing them alone leaves every hand-assigned
task unable to reach `:done`.

## 3. `result-at-reach` costs more than the hybrid's 0.5 ms per proof

Measured on default 2026-10-12 (jvm mode, read-only, `seon.profile/measure`, armed, seven calls each) on branch
`:agent-95bbf985bb25` for `seon.request-cost-test/a-request-counts-its-own-calls-reads-and-transactions` (5 executions):
the digest filter (one `:seon.test.member/symbol` AVET read and one `:seon.test.member/reach-digest` read per member)
0.09–0.16 ms; the newest-execution reads it shares with `latest-results` (two queries and `recorded-member-results`)
1.16–1.67 ms. So one `result-at-reach` is about 1.3 ms, and three proofs called one by one about 4 ms before the
commit value (0.72 ms) and the base reach digests (1.0–1.6 ms per test), above M1's 5 ms bound for three proofs. The
fix when M1 resumes: `step-observation` asks once for all its proofs (the private `seon.test.runner/newest-results`
already takes many tests' members in one pass; a batch arity over `{test reach-digest}` costs one ~1.3 ms read for
the set), and `reach-digests` is already one call for the set.
