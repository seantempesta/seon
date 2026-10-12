---
title: "Open faults on default, evening 2026-10-12"
type: research
status: complete (lane faults-evening, 2026-10-12); read-only, nothing resolved
created: 2026-10-12
---

# Open faults on default, evening 2026-10-12

Read-only snapshot of `(seon.problems/open-faults (seon.db/db (seon.cluster.boot/connection "default")))`
on the JVM running HEAD f7c730911 or later (JVM mode, root, no writes). Four open recorded errors.
Each `:seon.error/fact` was read in full; the rows below name the producer, the agent that caused the
occurrence, the lane it likely came from (agent ids and chain notes under this directory), the task that
already covers it, and the verdict.

## Table

| # | Signature (prefix) | Operation / producer | Message | Agent, branch, evaluation | Occ. | First / last (UTC) | Exception class, layer | Agent's mistake or Seon defect | Likely lane | Task filed |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `db142341dca2` | `seon.cluster.agent/fork-attempt!` (agent.clj, frame at line 1883) | "The attempt's predecessor branch is gone, so its fork point cannot be read." | `attempt/64abda4eb8aa`, `:attempt-64abda4eb8aa`, eval `967e3191fc0b` | 6 | 08:28:26.466Z / 08:28:26.466Z | `ExceptionInfo`, `:seon.error/normalization` | **Seon defect** | chain-3 (Implement #5 forked from Implement #4's end; `chain-experiment-3.md` lines 33, 78) | **No task names this message.** Partial cover: `seon.cluster.agent.edn` E6 (line 48) and M10 (line 70) |
| 2 | `759d40be224b` | `seon.cluster.registry/retire-branch!` (registry.clj, frame at line 503), reached from `seon.cluster.agent/release-context!` | "Connection has been released." | branch `:cluster-default`; process `81140-1791794380625` | 3 | 08:59:40.797Z / 08:59:40.797Z | `ExceptionInfo`, `:seon.error/normalization` | **Seon defect** | root or a probe that released a context twice (no agent id recorded on the fact) | Yes: `seon.cluster.registry.edn` `retiring-a-branch-records-connection-has-been-released` (line 20), `a-retire-branch-error-repeats-in-every-reply` (line 11); `seon.test.edn` line 96 (release order) |
| 3 | `117f391d785b` | `my.agent/retire-branch!` (contract refusal, `:seon.fn.arity/input`) | "my.agent/retire-branch! refused argument 0: expected :my.agent/branch-request (a map), got a keyword :agent-8f004cfa2806." | agent `chain-2-probe`, branch `:agent-8f004cfa2806`, eval `ab9379184d58` | 1 | 06:50:21.481Z / 06:50:21.481Z | `ExceptionInfo`, `:seon.error/layer :seon.instrument/invocation` | **Agent's own mistake** (a keyword passed where a map is declared; `branch-cleanup.md` line 34 records the same call) | chain-2-probe (leftover probe) | None found for the keyword mistake. The refusal is correct; see the recording note below |
| 4 | `bd510b996237` | `seon.sci.eval/install-row!` (eval.clj, frame at line 1553) | "Unable to resolve symbol: profile/measure" | agent `cost-acquire`, branch `:agent-95bbf985bb25` | 1 | 04:32:55.979Z / 04:32:55.979Z | `ExceptionInfo` (`:sci/error`, phase analysis), `:seon.error/normalization` | **Seon defect** (reach re-interpreted a caller in the wrong namespace alias environment) or the agent's source uses an alias its namespace never required; the task says establish which | cost-acquire (Phase H9/H10 cost work) | Yes: `seon.sci.eval.edn` task `reinterpreting-a-reached-caller-resolves-its-namespace-aliases` (line 410) |

Occurrence counts are the fact's `:seon.error/occurrence-count` at read time. Times are the fact's
`first-at` and `last-at` as stored.

## Evidence per row

**Row 1 (fork-attempt!, Seon defect, no task).** The producer is `seon.cluster.agent/fork-attempt!`.
It reads the attempt's `:seon.task.attempt/fork` commit, finds the row whose `:seon.task.attempt/commit`
equals it, takes that row's `:seon.task/branch` as the parent, and throws when the parent is not in
`(registry/branches store)`. The fact's `data-edn` names the parent as `:attempt-02110a533e26` and the
fork commit as `6acc9606-ca27-5ea6-a9cc-981e1ca4d43d`. `chain-experiment-3.md` line 33 says Implement #5
(`:attempt-64abda4eb8aa`) was forked from Implement #4's end `6acc9a0a-...`, and line 78 places the start
at 08:28:26, the same second as the fault. Two candidate causes:

- (a) Ordering. The predecessor's branch was deleted (`seon.task/delete-branch!`) at its end before the
  successor forked, so the successor's fork read a branch name that no longer exists. E6 in
  `seon.cluster.agent.edn` line 48 names this ordering ("creates the successor's branch before ...
  removes the predecessor's"). Check: `delete-branch!` events for `:attempt-02110a533e26` before 08:28:26.
- (b) Lookup. The fork point is a commit in the store (retained by Datahike until collection), so the
  fork could read the commit directly and never need the parent branch name. The current code requires
  the branch to exist even though the commit is what it needs. Check: whether `db/branch-database` on a
  retired parent could read the commit, which Datahike's `as-of` path would allow.

Both causes lead to the same owner. Nothing falls back to main (the docstring says so), so the refusal is
the designed behaviour; what is missing is the task that names this message. Count note: the fact shows
occurrence-count 6 but `first-at` equals `last-at`. Either the repeats share one timestamp (a retry loop
within one request) or the count is not updated per repeat. Root should check which before filing.

**Row 2 (retire-branch! after release, Seon defect, covered).** The fact's chain runs
`release-context!` (agent.clj lines 977 to 996) into `registry/retire-branch!`. The frame at registry.clj
line 503 is inside the `d/delete-branch!` try, and the datahike message "Connection has been released." is
thrown on a connection that an earlier step had already released. Candidate causes:

- (a) Double release. A child execution owning a branch is released from the children loop and again from
  its own registry's release (the loop in `release-context!` lines 991-996 handles children through the same
  function). Check: whether the child handle appears in both paths for the process `81140-1791794380625`.
- (b) Release order. `store/release-branch!` runs before `registry/retire-branch!` on the same handle; if
  the connection was released by the first, the second cannot delete. The registry task
  `retiring-a-branch-records-connection-has-been-released` asks for exactly this to record nothing.

No agent id is on the fact, so the caller is `:cluster-default`, i.e. root or a process-level release. The
existing tasks already own the defect; this row is not new.

**Row 3 (contract refusal, agent's own mistake).** The keyword `:agent-8f004cfa2806` was passed to
`my.agent/retire-branch!`, which declares `:my.agent/branch-request` (a map). The refusal is correct and
matches the probe's own call in `branch-cleanup.md` line 34. Per the rule ("an agent's own mistake is only
delivered"), this should reach the agent's reply and not be recorded as an open Seon fault. Whether the
recording path records contract errors at main is a question for the owner of `seon.error` (no task found
for it; searched `resources/seon/tasks` for `branch-request`, `keyword :agent`). It is a recording question,
not a second defect in `retire-branch!`.

**Row 4 (install-row!, Seon defect with task).** The fault's `data-edn` shows `:sci.impl/symbol profile/measure`
in analysis phase during `seon.sci.eval/reinterpret-reach!` from `seon.turn/resume-turn`. The alias `profile`
belongs to the caller's own namespace. `seon.sci.eval.edn` task `reinterpreting-a-reached-caller-resolves-its-namespace-aliases` (line 410) already names the same message and
operation and asks for a regression. Nothing to add beyond the existing task.

## Summary

- Seon defects with a task: rows 2 and 4 (tasks above). Row 2 is still recorded, so its task is open.
- Seon defect with no task naming the message: row 1 (`fork-attempt!`, predecessor branch gone). E6 and M10
  are adjacent, not the same fix. A new task is needed on `seon.cluster.agent/fork-attempt!` naming its own deftest. Not filed here (read-only).
- Agent's own mistake: row 3 (`chain-2-probe`, keyword passed where a map is declared). Deliver only.
  The open question is why it stays in the open-fault list.
- Nothing resolved. No code, resource or test was changed.
