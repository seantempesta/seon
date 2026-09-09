---
type: research
status: complete
date: 2026-09-09
tags: [research, runtime, sci]
---

# Turn rename: bounded continuation

Current landing: slice 2 is complete in **`7296d173b`**. **RESET NEEDED: `7296d173b`.**
The two required gates are green; fresh-schema HTTP proof is recorded below.
Slices 3 and 4 remain unattempted. Earlier partial checkpoints are historical.

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
- Additional fast work and problems verification used the same canonical
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


## Completion evidence, 2026-09-09 07:36 UTC

Implementation landed as **`b4d665f8956edfd12fc14d5c67553f3f76d75b9a`**.
**RESET NEEDED: `b4d665f89`.** The default debug URL remains at the captured
service-input boundary documented above; no default lifecycle action was
performed. The remaining turn-row process identity found in the final fixture
review is removed in the evidence follow-up commit, not renamed to a second
identity. Its required gate and final platform result are appended below.

Fresh construction was verified in owned worktree/operator root
`tmp/custody-wt`, detached from entering HEAD with the exact implementation
diff overlaid and `reference-code` linked. Source digest matched the adopted
implementation: `05e6bfa7b2298523484168e627babb74c8de774224c564545afe6ad1faaaeb4e`.
The isolated cluster `custody` booted as PID 34133, PREPL 53560, HTTP 7872.
The Juniper config was supplied at boot with
`SEON_DESIGN_LAB_NO_CREDENTIAL` unset. The committed fresh probe creates
Juniper's settings component in the creation transaction, before installing
sample messages; that component explicitly selects the disabled credential
variable. The fixture now uses the current process provenance key and keeps
that credential setting when it adds the plan and messages.

Fresh live evidence:

- `turn_custody_fresh_probe_2026_09_09.clj` returned seeded=true,
  settings-variable=`SEON_DESIGN_LAB_NO_CREDENTIAL`, provider-usage-rows=0,
  plan-present=true.
- After serving the debug page, provider usage rows remained **0**. The
  query of installed Datahike identities for the two retired run attributes
  returned **[]**. This is a fresh-schema observation, not an old default
  database silently retaining the removed attributes.
- `http://127.0.0.1:7872/ns/my.agents.juniper/debug` returned **HTTP 200,
  55,189 bytes, 1.213877 s**. Its HTML contains `my.agents.juniper`,
  `Improve Juniper context inspection`, and the sample message beginning
  `Please make your current plan`. It does not contain `missing required key`.
  This verifies backend content; browser paint remains unavailable.
- `bin/seon --root tmp/custody-wt down` ended PID 34133 and reported the
  flock free. Status then reported zero clusters and no orphan JVMs. The
  worktree was removed. Default still reports PID **87173**, alive at 7994.

The scratch root was never an alternate development target and received no
paid submission. All five disposable edit scripts were deleted. The gate
runners removed successful roots themselves; no foreign root or worktree was
removed. Inherited untracked `build/`, `workers/`, and
`config/virtual-turns.edn` remain untouched. The namespace rename, write-count
collapse, and stable-id rollout were not attempted in this resume.


### Final gates and stop, 2026-09-09 07:42 UTC

**Slice 1 is complete.** The follow-up preserves the prompt fixture's
metadata-only transaction (it was not a custody operation) and removes the
remaining process identity from the direct agent-test turn row. Both changes
are path-gated together on HEAD plus only those two test files:

- `SEON_TEST_WORKERS=3 bin/test --paths test/seon/cluster/agent_test.clj
  test/seon/cluster/prompt_test.clj -- seon.cluster.run-test seon.turn-test
  seon.cluster.loop-test`: **48 tests / 356 assertions, 0 failures/errors**.
  Log `tmp/custody-corrected-gate.log`, root `run.yOdaXq` removed.
- `SEON_TEST_WORKERS=1 bin/test --platform --paths
  test/seon/cluster/agent_test.clj test/seon/cluster/prompt_test.clj`:
  **83 tests / 490 assertions, 0 failures/errors**.
  Log `tmp/custody-corrected-platform.log`, root `run.nZwj0H` removed.

The intervening three-worker platform run reproduced the existing
[cohost/sweep race](../../../seon/issues/cohost-start-races-the-reachability-sweep.md):
83 tests / 474 assertions, one error, followed by a passing isolated
confirmation. The final platform result above is green; no foreign edit was
attributed. Its failed root was removed after a process-table check found no
live holder.

Final adopted source equals published source:
`6aa10d39-844d-5bb6-aab2-23edcb76326e`. The main implementation's RESET NEEDED
boundary still applies: adoption does not reconstruct default's old HTTP
service input. Fresh construction returned HTTP 200 as recorded above.
Every owned shell has ended, and the isolated scratch root/worktree is gone.
The tracked tree is committed; only the inherited untracked paths remain.
The slice finished inside its 30-minute bound, without beginning slice 2.


## Slice 2: turn namespace and component attempts, 2026-09-09

Started 07:43:50 UTC; the slice deadline is 08:13:50 UTC. Read the named
turn-cut handoff end to end, the turn PRD's requested sections (including
§16), and AGENTS' copied §10 lane instructions. Slice 1 remains accepted.
The original stash `39d1bf186` contains custody hunks, not the combined
rename. Candidate `78c8fc1d6` was inspected for mechanical naming and the
existing late-bound execution idiom; neither candidate was applied.

**RESET NEEDED — the commit containing this rename.** The source namespace,
writer API, schema resource, schema entity (`:seon.turn/turn`), and all
owned callers now use `seon.turn`. Existing debug controls are merged into
that owner. The four calls into loop/agent/SCI execution resolve at invocation
time to avoid their load-time cycle; they still receive all environment and
request inputs explicitly. No compatibility namespace remains. The writer
tests are merged into `seon.turn-test`, retaining the real recovery model.

The attempt relation moves from `:seon.ai.attempt/run` to the component
set `:seon.turn/attempts`. The AI owner retains every attempt fact; there
is one relation, not two mirrored edges. Writer maps use Datahike's reverse
ref syntax, queries follow the forward component edge, and the reasoning
pull accounts for a reverse pull's vector. Dependency ledger: Datahike
`db/transaction.cljc:728–770` owns reverse-map expansion;
`src/seon/cluster/loop.clj:972` writes it and
`src/seon/render/transcript.clj:626–650` reads it.

The writer still has existing non-custody fields needed by its current
callers: `starting-ns` and `plan-digest` in reply preparation/settlement,
`background-results` in `unanswered-background-results`, `supersedes` in
transcript selection, and error/undisposed facts in the work query. Their
removal is not silently claimed by a namespace rename. Request-map keys
remain in-memory contracts. Three-write collapse and stable-id rollout are
not attempted. The virtual-turn observation is now four transactions with
1/16/5/2 datoms (24 total), including the metadata-only first transaction.

### Exact scope and foreign boundary

The required gate runs in `tmp/turn-rename-wt`, detached from `a991283de`,
with HEAD plus only the paths below. `reference-code` is linked. The
concurrent `ordered-episode` edit in `src/seon/render/walk.clj`, its
`resources/seon/schemas/seon.repl.edn` change, and untracked
`test/seon/render/episode_test.clj` are excluded. Only namespace substitutions on four lines of the walk owner belong to this slice. The foreign
test still contains four old turn references and is not edited by this lane.
Inherited untracked `build/`, `workers/`, and `config/virtual-turns.edn` are
preserved. Documentation/probe additions accompany the implementation; the
code gate's exact snapshot digest is recorded below.

```text
.agents/skills/datahike/references/fork-maintenance.md
.agents/skills/seon-flow-architecture/references/decisions.md
AGENTS.md
bin/test
docs/prds/context-generation/research/turn-rename-landing-2026-09-09.md
docs/prds/context-generation/research/turn_cut_probe_2026_09_08.clj
docs/prds/context-generation/research/turn_namespace_probe_2026_09_09.clj
docs/prds/context-generation/research/turn_rename_recovery_probe_2026_09_09.clj
docs/seon/issues/dependency-resolution-can-race-maven-model-validation.md
docs/seon/issues/development-adoption-retains-old-web-service-inputs.md
docs/seon/issues/parallel-test-base-connect-can-lose-a-filestore-key.md
docs/seon/issues/turn-consumer-fixtures-read-retired-result-storage.md
resources/seon/schemas/seon.ai.attempt.edn
resources/seon/schemas/seon.ai.edn
resources/seon/schemas/seon.cluster.agent.edn
resources/seon/schemas/seon.cluster.eval.edn
resources/seon/schemas/seon.cluster.loop.edn
resources/seon/schemas/seon.cluster.message.edn
resources/seon/schemas/seon.cluster.prompt.edn
resources/seon/schemas/seon.cluster.run.edn
resources/seon/schemas/seon.cluster.work.edn
resources/seon/schemas/seon.context.edn
resources/seon/schemas/seon.effect.edn
resources/seon/schemas/seon.env.edn
resources/seon/schemas/seon.error.edn
resources/seon/schemas/seon.eval.drive.edn
resources/seon/schemas/seon.problems.edn
resources/seon/schemas/seon.render.edn
resources/seon/schemas/seon.render.transcript.edn
resources/seon/schemas/seon.sci.eval.edn
resources/seon/schemas/seon.turn.edn
src/seon/bootstrap.clj
src/seon/bootstrap_drive.clj
src/seon/cluster.clj
src/seon/cluster/agent.clj
src/seon/cluster/loop.clj
src/seon/cluster/message.clj
src/seon/cluster/prompt.clj
src/seon/cluster/reply.clj
src/seon/cluster/run.clj
src/seon/cluster/wake.clj
src/seon/cluster/work.clj
src/seon/context.clj
src/seon/effect.clj
src/seon/error.clj
src/seon/eval.clj
src/seon/eval/drive.clj
src/seon/oversight.clj
src/seon/problems.clj
src/seon/render.clj
src/seon/render/transcript.clj
src/seon/render/walk.clj
src/seon/render/web.clj
src/seon/sci/eval.clj
src/seon/turn.clj
test/my/background_test.clj
test/seon/agent_situation_test.clj
test/seon/ai_stream_fold_test.clj
test/seon/background_blob_test.clj
test/seon/background_test.clj
test/seon/blob_threshold_test.clj
test/seon/bootstrap_test.clj
test/seon/cluster/agent_test.clj
test/seon/cluster/armed_test.clj
test/seon/cluster/boot_test.clj
test/seon/cluster/evaluate_sources_test.clj
test/seon/cluster/loop_test.clj
test/seon/cluster/message_assignment_test.clj
test/seon/cluster/message_test.clj
test/seon/cluster/problem_routing_test.clj
test/seon/cluster/program_restart_test.clj
test/seon/cluster/prompt_test.clj
test/seon/cluster/reply_test.clj
test/seon/cluster/resume_artifact_routing_test.clj
test/seon/cluster/run_test.clj
test/seon/cluster/store_transact_test.clj
test/seon/cluster/turn_test.clj
test/seon/cluster/wake_test.clj
test/seon/cluster/work_test.clj
test/seon/concurrency_independence_test.clj
test/seon/concurrency_streams_test.clj
test/seon/concurrency_test.clj
test/seon/context_capture_test.clj
test/seon/context_selection_test.clj
test/seon/db/declaration_population_test.clj
test/seon/db_test.clj
test/seon/dev/changed_test_test.clj
test/seon/effect_test.clj
test/seon/error_test.clj
test/seon/eval/drive_test.clj
test/seon/eval_test.clj
test/seon/fn_test.clj
test/seon/gen/loop_test.clj
test/seon/oversight_test.clj
test/seon/problems_test.clj
test/seon/program_test.clj
test/seon/receipt_write_carrier_test.clj
test/seon/reconcile_test.clj
test/seon/render/root_pull_test.clj
test/seon/render/transcript_run_test.clj
test/seon/render/transcript_test.clj
test/seon/render/walk_test.clj
test/seon/render/web_context_test.clj
test/seon/render/web_test.clj
test/seon/render_coverage_test.clj
test/seon/render_simplification_test.clj
test/seon/render_source_test.clj
test/seon/schedule_test.clj
test/seon/schema/datahike_test.clj
test/seon/schema/program_test.clj
test/seon/schema_usage_guard_test.clj
test/seon/sci/eval_test.clj
test/seon/sci/reader_test.clj
test/seon/shell/jvm_test.clj
test/seon/turn_test.clj
test/seon/web/jvm_test.clj
```

### Verification and observed refusals

- Corrected turn-only fast run: 22 tests / 237 assertions, zero failures/errors.
- First three-namespace fast run loaded a mechanical test typo
  (`seon.cluster.turn/*`); it reported 105 tests / 700 assertions,
  26 failures / 7 errors. The typo is corrected, not accepted as evidence.
- First isolated gate: 105 tests / 701 assertions, three failures and one
  error. The error was the parallel fixture-base acquisition recorded in
  `parallel-test-base-connect-can-lose-a-filestore-key.md`. The web test
  counted initial paints absolutely; it now captures the positive initial
  count after registration closes and verifies zero additional closed-page
  derivations, then one per reopen. The property remains a real call-count
  observation, not a constant or a removed assertion.
- One gate attempt failed during Maven classpath acquisition; its exact
  exception is recorded in `dependency-resolution-can-race-maven-model-validation.md`.
- A subsequent static-analysis refusal identified an out-of-scope local
  binding introduced while adjusting that web test. The lexical scope was
  corrected; clj-kondo reports zero errors (11 existing warnings in that file).
- Additional turn/loop/transcript fast probe: 67 tests / 548 assertions,
  64 failures / 8 errors, all reported in the transcript namespace's old
  result/history fixtures. The updated existing consumer-fixture issue names
  this boundary; no claim of a green transcript suite is made. Its attempt
  reasoning case passes with the component relation.

### Fresh live proof

Owned `custody` boot in `tmp/turn-rename-wt`: HTTP 7872, PREPL 55069.
Published source `6aa1111c-7976-5c81-9aa0-c1da5e548f4c`, source digest
`8470e263d2199dfacc73ed23ccb3e7c7165acb045a4074f673c3bd110a4472f5`.
This is a fresh ordinary fork; it has no development-adoption commit stamp.
The existing committed settings-first probe creates Juniper's settings
component with `SEON_DESIGN_LAB_NO_CREDENTIAL` before seeding messages.
`turn_namespace_probe_2026_09_09.clj` then reports component ref/many=true,
zero retired turn attributes, retired attempt ref absent, a vector of one
evaluation from `seon.eval/of-agent`, and zero provider usage rows.

- `/ns/my.agents.juniper/debug`: HTTP 200, 52,752 bytes, 1.419858 seconds.
- `/ns/my.agents.juniper/debug?prompt=true`: HTTP 200, 62,181 bytes,
  1.902879 seconds; stored evaluation entries and all three controls are
  present. Plan and sample messages appear; neither page reports a missing
  required key. Provider usage is still zero after both requests.
- Browser paint is unavailable: CUA exposes no browsers and native Chrome
  returns `cgWindowNotFound` (-10005). These are HTTP/content observations.

Default's adoption refused at `seon.env/advance-projection!` after loaded
definitions. Its debug response is HTTP 500, 200 bytes, naming the old
`preview-sources` result missing renamed keys. A later comparison probe also
refused; convergence is not claimed. The existing development-adoption issue
records the boundary. No default stop/refork/restart was performed.

### Final gates, 2026-09-09 08:05 UTC

**Slice 2 is green.** Exact program/test snapshot:
`6d8d2580b161d95aa9cd8d63773ae68dc58d4180777d5115ffefd2155d351ae9`.

- `SEON_TEST_WORKERS=1 bin/test --paths <the owned paths above> --
  seon.turn-test seon.cluster.loop-test seon.render.web-test`: **105 tests /
  702 assertions, zero failures/errors**. Log `tmp/turn-rename-gate-final.log`;
  successful root `run.kE7f9u` removed by the runner.
- `SEON_TEST_WORKERS=1 bin/test --platform --paths <the owned paths above>`:
  **83 tests / 490 assertions, zero failures/errors**. Log
  `tmp/turn-rename-platform-final.log`; successful root `run.LQcV0I` removed.

The earlier transcript namespace failures remain explicitly outside these
green claims. The fresh page also reproduces the already-filed
`blocked-plan-values-refuse-pull-during-ai-projection.md` defect, which is
updated in this commit in addition to the path inventory above.

Owned scratch JVM PID 46431 was downed through `bin/seon --root
tmp/turn-rename-wt down`; the operator reports its flock free. The commit
reads the path-limited owned snapshot using Git's `--work-tree` option,
leaving the foreign working-tree episode hunks intact. No shared source
file is restored or replaced to construct that commit. Final commit ID,
default publication observation, and root/shell cleanup follow below.

### Commit and cleanup

Implementation **`7296d173b`**: 117 paths, 5,335 insertions / 4,974 deletions.
The commit's `src`, `test`, and `resources` have zero `seon.cluster.run/`
references. Its walk change is exactly four namespace-substitution lines;
none of the foreign episode schema, test, or behavior hunks entered it.

The final explicit default publication waited behind an existing operator
client publishing `src/seon/ai.clj`. A pre-cancellation ownership check found
that our client had acquired the lock, so it was not signalled; it was
allowed to complete. The preceding read-only source comparison returned
adopted `6aa10d39-844d-5bb6-aab2-23edcb76326e` and published
`6aa113d8-a8b1-58ad-a6e9-e2b6a9dee2aa` — not converged.

The owned scratch JVM is down. A process-table check found no java/bb/bash
holder of the owned worktree before removal; its reference-code symlink
was unlinked without traversing the target, then the worktree and all
its retained test roots were removed. The three disposable edit scripts
were deleted. No foreign worktree, session, or files were changed.
Main-tree residue is exactly the foreign `seon.repl.edn`/`render/walk.clj`
edits and episode test, plus the inherited untracked paths.

Final publication observation, 08:10 UTC: the owned client exited with the
same `seon.env/advance-projection!` invalid-input refusal after loaded
definitions. A successful read-only comparison still returns adopted
`6aa10d39-844d-5bb6-aab2-23edcb76326e` versus published
`6aa113d8-a8b1-58ad-a6e9-e2b6a9dee2aa`. The final default request returns
HTTP 500, 200 bytes, 0.025887 seconds, with the `preview-sources` missing-key
message recorded above. **RESET NEEDED: `7296d173b`.** The final publication
client and every other owned shell have ended; no attempted cancellation
sent a signal. Default remains the owner's running process.

The code gates and commit finished within the 30-minute slice; this final
note closes the evidence before 08:13:50 UTC. No slice 3 or 4 work began.
