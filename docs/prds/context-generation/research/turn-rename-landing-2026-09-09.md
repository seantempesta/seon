---
type: research
status: incomplete
date: 2026-09-09
tags: [research, runtime, sci]
---

# Turn rename: bounded continuation

## Recovery checkpoint, 2026-09-09 06:54 UTC

Entering HEAD: `66b296e67`. The tracked tree was clean; inherited untracked
`build/`, `workers/`, and `config/virtual-turns.edn` were preserved. Default
was alive at PID 87173 and MCP health answered. No other lane was operated.

Read AGENTS.md (including the embedded PRD §10 lane rules), the previous
`turn-cut-landing-2026-09-08.md` handoff end to end, and turn PRD §4/§4a,
§10, §12, §14–§16 end to end. Also read §0, §13, the plan README and
working edge. Applied the Clojure, testing, REPL, Datahike, data-modeling,
and flow skills. Inspected the stash's custody diff and the unmerged
candidate inventory; neither was applied wholesale.

This is a small first part of custody removal, not completion of slice 1.
Boot recovery now closes every open turn, including a saved holder named
in an incoming live-process set and system-generated source. It interrupts
unfinished evaluations/effects while preserving terminal evaluations.
The boot caller no longer supplies a process roster. Claim/release and the
legacy run interruption stamp still exist and are explicitly pending.

Dependency ledger: Datahike invokes `:db.fn/call` against its current
transaction database (`reference-code/datahike/src/datahike/db/transaction.cljc:1152`).
The existing `run/recover-call` and cluster boot recovery caller use that
mechanism. The immutable live probe uses the dependency's `with`, declared
referentially transparent in `reference-code/datahike/src/datahike/api/specification.cljc:466`.
The ordinary-proc regression uses the canonical `with-database` fixture,
real SCI, work launcher, agent graph, armed contracts and bounded events.

Verification:

- Entering-HEAD fast baseline: 21 tests / 154 assertions, zero failures/errors.
- Changed recovery fast test: 21 / 154; ordinary-proc fast test: 2 / 106,
  zero failures/errors. The proc test invokes the actual boot recovery
  function on an open turn with no evaluations, then successfully executes
  virtual turns for that agent. It does not substitute the evaluator.
- Final owned-path gate: **23 tests / 262 assertions, zero failures/errors**.
  Paths: `src/seon/cluster/run.clj`, `src/seon/cluster.clj`,
  `test/seon/cluster/run_test.clj`, `test/seon/turn_test.clj`.
  Namespaces: `seon.cluster.run-test seon.turn-test`.
  Log: `tmp/turn-rename-recovery-final-gate.log`; successful root removed
  by the runner. All runs set `SEON_TEST_WORKERS=3`.
- The fixed-seed recovery property varies generated/source state and
  saved holder, requires the expected evaluation count, preserves complete
  terminal entities, requires exact closing time, and verifies repeat
  recovery produces no operations. No literal-nil assertion was substituted.
- Live JVM probe: `turn_rename_recovery_probe_2026_09_09.clj` returned
  closed=true, interrupted=true, idempotent=true, default-unchanged=true,
  four operations. This exercises the changed live Var against an immutable
  copy of default's database, not a default restart or a scratch boot.
  At 06:54 UTC adoption was still converging: adopted
  `6aa1011d-e7c9-5989-8248-c076abcc4c60`, published
  `6aa10239-5ab7-5e05-b108-12ee4b30c9e7`. The loaded boot caller's arglist
  was `[connection]`. No completed-adoption or browser-paint claim yet.

No schema deletion in this checkpoint; no RESET NEEDED line applies yet.
No scratch cluster was seeded, no provider was invoked by this lane, and
default was never stopped, restarted, or reforked. The preliminary virtual
turn log counted four reports / 26 datoms, including a metadata-only report;
this is not the three-write proof and is not a deterministic count claim.

Remaining: finish custody/provenance and its consumers; rename to seon.turn;
prove three writes per virtual turn; route stable identities through seon.id.
Platform gate and final shell cleanup remain pending at this checkpoint.

## Bounded partial: 2026-09-09 07:10 UTC

Recovery landed as **`34e47f595`**. Its explicit platform gate passed
**83 tests / 490 assertions, zero failures/errors**:
`SEON_TEST_WORKERS=3 bin/test --platform --paths
docs/prds/context-generation/research/turn-rename-landing-2026-09-09.md`.
That snapshot uses committed production source and excludes the rejected
draft below. Log: `tmp/turn-rename-recovery-platform.log`.

One independently green fixture repair changes only
`test/seon/cluster/work_test.clj`: two fixture writers and one terminal
query now use the installed shown-text attribute `:seon.eval/value`.
No test was deleted and no expected work state changed in this repair.
Its final path gate passed **14 tests / 91 assertions, zero failures/errors**:
`SEON_TEST_WORKERS=3 bin/test --paths test/seon/cluster/work_test.clj --
seon.cluster.work-test`. Log: `tmp/turn-rename-work-gate.log`.

**Slice 1 is incomplete.** Claims, releases, process custody, the legacy
turn interruption stamp, and their consumers have not been removed.
The rename, three-write grouping, and stable-id integration were not
started. No RESET NEEDED line applies to either landed change.

### Rejected draft and exact verification boundary

Stash **`a9cd1f5ad0abbe82b053a665a2475b6f7965eb70`**, named
`turn-rename-interruption-draft-2026-09-09`, preserves the eight-file
attempt: **56,384 patch bytes**, 128 insertions / 314 deletions.
Its base is `34e47f595`. It was removed from the working tree; use as
quarry only. The earlier owner's stash remains separately preserved as
`39d1bf18663b3c3fa46e8dde8baee5140a8140a6` (now the next stash entry).

Exact rejected paths:

```text
src/seon/cluster/agent.clj
src/seon/cluster/loop.clj
src/seon/cluster/work.clj
test/seon/cluster/agent_test.clj
test/seon/cluster/loop_test.clj
test/seon/cluster/turn_test.clj
test/seon/cluster/work_test.clj
test/seon/gen/loop_test.clj
```

The draft deletes `work/interruption` and loop-side
`settle-interruption!`, removes the corresponding per-pass cleanup and
three tests of that deleted mechanism, and changes the refusal-path test
to query actual open turns. Its later consumer edits are **not verified**.
In particular, do not treat arbitrary shown text as EDN: a shown Var is
not EDN, and the separate stored error-data codec is still needed.

The attempted gate used those eight paths and these complete namespaces:
`seon.cluster.loop-test seon.cluster.work-test seon.cluster.agent-test
seon.cluster.turn-test seon.gen.loop-test seon.turn-test`.
It was stopped after the unbounded routing fixture and worker-global
instrumentation drift were observed. The runner's reap backstop forced its
coordinator to exit; the wrapper exited **143**, with no complete tally.
Log: `tmp/turn-rename-interruption-gate.log`. No green result is claimed
for this draft, and no failure is attributed to another lane.

Observed boundaries, with the full class recorded in
[the consumer-fixture issue](../../../seon/issues/turn-consumer-fixtures-read-retired-result-storage.md):

- Work fixtures wrote/read the deleted result field: six assertion
  failures. The isolated fixture repair above resolves this member.
- HEAD-only `seon.cluster.agent-test` independently reproduced the
  prompt-refusal and parallel-turn failures and the install-gate
  observation failure. Its log is `tmp/turn-rename-agent-baseline.log`;
  it was stopped without a complete tally.
- `install-gate-failure-settles-commits-and-cancels-the-turn-backstop`
  read an uninstalled attribute and treated the returned error as a
  completion. A failed assertion printed a cyclic runtime value until
  `Required array length 2147483640 + 18 is too large`.
- `routing-conservation-waits-for-terminal-evidence` timed out while its
  future still owned temporary Var roots. The gate reported missing
  wrappers for `seon.ai/complete`, `seon.bootstrap/next-entry`, and
  `seon.sci.eval/evaluate`. The underlying arming channel wait was unbounded.
- The later turn-consumer fast attempt still failed stored-private-def
  queries, shown-Var EDN decoding, obsolete delivered-result expectations,
  prompt-render expectations, and reasoning-only failure cardinality.
  Log: `tmp/turn-rename-turn-consumers-fast.log`; no complete tally.

### Live state and cleanup

After removing the rejected draft, development adoption converged to
`6aa105fb-04b8-5401-8dab-67ebfee3a238`, source digest
`b60d6338b62e031be70015e3d5d816888ba90a9200b5e9491ba8b3e27fc6b5a2`.
The default debug route returned **HTTP 200, 49,718 bytes, 0.037061 s**.
CUA reported **No browser is available**; no browser-paint proof is claimed.
Default remained PID 87173 throughout and was never stopped or reforked.

Literal occurrences in landed src/resources remain:
`:seon.cluster.run/process` **93**, `claim-call` **6**, `release-call` **4**.
The eleven deletion-induced test references from the previous handoff
are not claimed resolved: their corresponding mechanisms remain in HEAD.
No three-write count is claimed and no stable-id rollout was made.

No scratch cluster was created or seeded. The execution checks used
virtual source submissions or the inherited provider-reply fixtures;
there was no deliberate live provider submission. Every owned test JVM
and superseded adoption command was ended. Successful gates removed
their own roots. `run.MGNliH` was removed only after process-table
inspection found no holder; `run.QPYZxQ` had already been removed by its
wrapper. No lane worktree was created. The one disposable edit script was
deleted; the rejected source remains in Git, not only in scratch files.


## Slice 1 resume, 2026-09-09 07:15–07:31 UTC

Entering HEAD `46e275d3b`; the two prior checkpoints were accepted. This
resume implements custody removal only. The 30-minute deadline is 07:45 UTC.
The named authorities were read end to end in the preceding checkpoint;
their requirements and the two stash reviews remain the grounding here.
The custody hunks of `39d1bf1` supplied the writer and request changes;
`a9cd1f5` supplied only reviewed deletions of loop interruption cleanup and
tests of that deleted mechanism. Neither stash was applied wholesale.

Claim/release, `held?`, process-holder queries, the live-process roster,
and the legacy run interruption stamp are removed. Close, plan and generated
append check existence and absence of `closed-at` inside the Datahike writer.
Boot closes all open turns and interrupts only unfinished evaluations and
effects. Execution handles and request contracts use `:seon.db.process/id`;
turn entities do not carry process identity. The problem renderer no longer
invents a dead-holder family. A reply's absence is queried from database
facts, because a render unit may omit the reply attribute.

The fixed-seed state model retains open/close/plan/start/settle/recover.
Removing custody made previously rare plan sequences reachable: the model
now accounts for the existing writer's append of plan evaluations after the
current ordinal count and its refusal of an occupied ordinal. Recovery tests
preserve complete terminal entities, expected evaluation counts, exact close
time, idempotence, late-settlement refusal, and zero invented evaluations.
The real ordinary-proc regression still proves an agent takes new virtual
turns after boot closes its old turn. No literal-nil assertion replaced a
removed mechanism.

Verification of the exact source/test paths below:

- Required gate: `SEON_TEST_WORKERS=3 bin/test --paths <listed paths> --
  seon.cluster.run-test seon.turn-test seon.cluster.loop-test` — **48 tests,
  356 assertions, zero failures/errors**. Log `tmp/custody-gate.log`;
  successful root `run.AYM1jE` removed by the runner.
- Explicit `bin/test --platform --paths <listed paths>` — **83 tests,
  490 assertions, zero failures/errors**. Log `tmp/custody-platform.log`;
  successful root `run.TcicHq` removed by the runner.
- Additional fast work/problematics verification used the same canonical
  fixture and armed contracts: `seon.cluster.work-test` and
  `seon.problems-test` passed; the whole five-namespace iteration was 75
  tests / 479 assertions with two subsequently corrected failures in the
  required namespaces. It is not represented as a separate green gate.
- Literal/aliased retired process attributes, run interruption attributes,
  and deleted API readers have **zero occurrences in src/test/resources**.
  Source/test/schema patch: **197,939 bytes**, 49 paths at this checkpoint.
- Virtual-turn observation: **4 transaction reports / 24 datoms**, distributed
  1, 16, 5, 2. The first is metadata-only. This remains evidence for the next
  slice, not a claim of three writes or work on that slice.

**RESET NEEDED with the custody removal implementation commit** (commit hash
recorded in the completion entry below). Both run schema attributes and
execution request shapes changed. Default was never stopped, restarted, or
reforked. Development adoption converged to
`6aa10a14-9ed8-5ea9-bfb3-376b62023e7f`, digest
`05e6bfa7b2298523484168e627babb74c8de774224c564545afe6ad1faaaeb4e`.
The updated committed immutable recovery probe returned closed, interrupted,
idempotent, default-unchanged and only-close-and-evaluation all true, with
**2 operations**. This exercised the adopted live Var, without writing to
or restarting default.

After convergence, the required default debug URL returned **HTTP 500,
110 bytes, 0.001136 s**. Its old captured service request lacks the new
process key. Exact reset boundary and browser-tool failures are recorded in
[the service-input issue](../../../seon/issues/development-adoption-retains-old-web-service-inputs.md).
No foreign lane or foreign edit is involved. Browser paint is unverified.

Fresh construction and cleanup are recorded in the completion entry below.
Slices 2–4 (rename, three writes, stable ids) are outside this resume.

Exact gated paths:

```text
resources/seon/schemas/seon.cluster.loop.edn
resources/seon/schemas/seon.cluster.prompt.edn
resources/seon/schemas/seon.cluster.run.edn
resources/seon/schemas/seon.cluster.work.edn
resources/seon/schemas/seon.context.capture.edn
resources/seon/schemas/seon.context.edn
resources/seon/schemas/seon.problems.edn
resources/seon/schemas/seon.render.web.edn
resources/seon/schemas/seon.schedule.edn
src/seon/bootstrap.clj
src/seon/cluster.clj
src/seon/cluster/agent.clj
src/seon/cluster/loop.clj
src/seon/cluster/run.clj
src/seon/cluster/work.clj
src/seon/context.clj
src/seon/eval/drive.clj
src/seon/problems.clj
src/seon/render.clj
src/seon/render/web.clj
src/seon/schedule.clj
test/seon/ai_stream_fold_test.clj
test/seon/background_test.clj
test/seon/bootstrap_test.clj
test/seon/cluster/agent_namespace_test.clj
test/seon/cluster/agent_test.clj
test/seon/cluster/armed_test.clj
test/seon/cluster/boot_test.clj
test/seon/cluster/evaluate_sources_test.clj
test/seon/cluster/loop_test.clj
test/seon/cluster/message_assignment_test.clj
test/seon/cluster/program_restart_test.clj
test/seon/cluster/prompt_test.clj
test/seon/cluster/run_test.clj
test/seon/cluster/turn_test.clj
test/seon/cluster/work_test.clj
test/seon/concurrency_independence_test.clj
test/seon/concurrency_test.clj
test/seon/effect_test.clj
test/seon/flow_configuration_test.clj
test/seon/fn_test.clj
test/seon/gen/loop_test.clj
test/seon/problems_test.clj
test/seon/render/transcript_test.clj
test/seon/render/web_performance_test.clj
test/seon/render/web_test.clj
test/seon/render_source_test.clj
test/seon/schedule_test.clj
test/seon/turn_test.clj
```
