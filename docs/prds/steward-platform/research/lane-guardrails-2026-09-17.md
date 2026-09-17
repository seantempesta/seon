---
type: research
status: active
tags: [research, test, operator, wave/dev-tooling-face-hygiene]
---

# Lane guardrails — 2026-09-17

First review checkpoint: item 1, declared Codex lane identity and cold-gate
refusal, plus the owner's follow-up on retained resume identity. Items 2–4
remain unimplemented at this checkpoint, per the
assignment's stop-after-first-coherent-item rule. No cold correctness gate
or platform proof is claimed.

## Accepted-item follow-up: pre-record lanes

The owner accepted `aba5d94a5`, then observed its resume refusal on this
lane, which had launched before retained records existed. The next review
checkpoint restores that one compatibility case: when the record is
absent and no explicit `LANE_SID` is supplied, resume reads the retained
stdout log's **first** valid CLI session header using `read_launch_session`.
After acquiring the lane and session claims, the existing atomic write
backfills `lanes/<name>/sid`. Subsequent resumes use that record. A present
but corrupt record still refuses; it never selects a later quoted header.
This supersedes the first checkpoint's missing-record refusal described
below. No other lane's actual session or record was read or modified.

The existing owning regression now covers ordinary run/resume, deleting
the record to simulate an older launch, successful backfill from a log
containing a later different quoted session, corrupt-record refusal despite
that log, and refusal when both record and log are absent. Its exact shell
script, extracted with the Clojure reader, returns **65** against the
accepted launcher (only **2** verified completions), and **0** against the
updated launcher (**3** verified completions, **2** expected refusals).
Only the paid Codex executable is replaced; both launchers are real. This
before/after shell proof does not claim an armed namespace tally.

`sh -n bin/codex-agent` and the owned-path whitespace check passed.
The command `bin/test-fast --paths bin/codex-agent
test/seon/test_runner_test.clj -- seon.test-runner-test` waited for a slot
on snapshot basis `79e0b0752a205bfd580c2fe22a1f33af247bf1bf`. At the
120-second report all three slots were still occupied. After the shell
proof completed, this slice's queued launcher PID 84818 was terminated and
awaited (**143**); no test JVM had launched and no armed tally is claimed.
No foreign holder was operated and no slot or silence bound was changed.
The owner reports default PID 33583's adoption refused
tree-wide at another lane's seam; verification uses fixtures, without
operating default. AGENTS.md's S3 diff and all foreign edits stay untouched.
This follow-up owns only `bin/codex-agent`,
`test/seon/test_runner_test.clj`, and this note. Items 2–4 remain next;
this compatibility correction is the next coherent review checkpoint.

## Grounding and dependency ledger

Read AGENTS.md §§0–7 and
[hook-passes-unlinted-paths-2026-09-17.md](hook-passes-unlinted-paths-2026-09-17.md)
in full, plus all of `bin/codex-agent`, `bin/test`, `bin/_test-slot`,
`bin/test-fast`, `src/seon/test/cache.clj`, and
`src/seon/test/selection.clj`. Read the runner's liveness watchdog,
declaration selection, worker exchange and coordinator entry seams,
including every `SEON_TEST_SILENCE_SECONDS` read in the runner and cache.
The working-edge entry headed **Owner ~03:50Z** is this assignment's
authority, not a new work queue.

After the orchestrator restarted this lane with hooks enabled, read both
[Codex CLI hooks](../../../seon/reference/codex-cli-hooks-2026-09-17.md) and
[Claude Code hooks](../../../seon/reference/claude-code-hooks-2026-09-17.md)
end to end. Commit `c41dd408b` enabled the vetted project hooks on run and
resume and also included the initial lane-name export and prompt change;
those bytes are retained. The launcher now announces that Codex snapshots
hook configuration at process start and that the orchestrator must stop
and resume running lanes after `.codex/hooks.json` changes.

The mechanism is shell environment inheritance at the existing process
launcher. `bin/codex-agent` already exports
`SEON_OPERATOR_EPHEMERAL_OWNER_PID` for resource custody and
`SEON_TEST_WORKERS` for pool sizing; neither names a lane. Its common
`run|resume` arm validates the name before launching the child through
`start_stream`. `bin/test` parses the selected mode before any snapshot,
slot acquisition or JVM launch. `bin/test-fast:14` executes that same
launcher with `--fast` for selected paths. No dependency library, second
launcher, process-name classifier or additional test harness is introduced.

One incident attribution was already corrected in
[test-fast-path-snapshots-are-misclassified-as-full-gates.md](../../../seon/issues/test-fast-path-snapshots-are-misclassified-as-full-gates.md):
the program-ops run killed as a full gate was a fast snapshot. Selected mode
must decide admission; the executable's basename cannot.

## Item 1

`bin/codex-agent:386` exports `SEON_CODEX_LANE=<validated-name>` for both
new and resumed lanes. Both prompts carry the same instruction to use
`bin/test-fast --paths` and leave cold gates to the orchestrator.

`bin/test:237` refuses a cold gate with nonempty `SEON_CODEX_LANE`, exit
64, before snapshot creation, slot acquisition or JVM launch. It names the
lane and the command to use. `SEON_TEST_ORCHESTRATOR=1` does not override
lane identity. `--fast` is still admitted and validated by the existing
parser. AGENTS.md's opening rule and §§5/7, the testing skill and the fast
launcher's comment now describe this division of work.

The initial regression's quoted session header exposed another real defect:
resume selected the last matching line anywhere in the lane's transcript.
Resume now reads `tmp/orchestrator/lanes/<name>/sid` exclusively (or an
explicit `LANE_SID`). The launcher's first CLI header establishes that
record once; later quoted output cannot overwrite it. The record survives
cleanup; the same lane directory's `active` child owns the atomic live
claim. Missing or invalid identity refuses with exit 65, without a
transcript fallback. Old launches that already deleted their record need
the orchestrator to supply their known `LANE_SID` once.

The regression
`seon.test-runner-test/codex-lanes-refuse-cold-gates-before-acquiring-resources`
runs the real launcher and real gate in a disposable checkout. The paid
Codex executable is a child fixture which asserts inherited identity and
then invokes the gate: bare, all, full, platform, selected paths,
`SEON_TEST_FULL=1`, and an orchestrator flag. It checks both run and resume,
the replacement instruction, and absence of run-root and slot side effects.
It also emits two different session headers, proves the first remains the
retained record, verifies resume's actual argv, and checks absent/corrupt
records refuse despite a transcript containing valid-looking identities.
It seeds a stale previous-launch log too; a new launch clears that log
before the session monitor starts, including while tee is opening its FIFO.
The fixture intentionally has no Java-home resolver: refusal must precede
even the launcher's Java-version probe.
The existing `fast-selected-paths-exclude-a-broken-foreign-file` regression
now runs the real `bin/test-fast --paths` with a lane identity and proves
the selected JVM still arms contracts and executes assertions.
Existing gate-lifecycle fixtures now explicitly supply the orchestrator
role to their isolated child ProcessBuilders; inheriting the testing
lane's identity would test admission instead of those fixtures' subjects.
The real lane-admission fixture retains inherited identity and the fast
snapshot regression explicitly supplies it.

## Evidence and verification boundary

At entry, `bin/seon status` and MCP runtime status both answered for
`default`, PID 33583. No stop, restart, refork or live definition change
was performed. Existing edits in the runner, its separate
`test/seon/test/runner_test.clj` namespace, program, schema, evaluation and
other protected files were preserved. The selected fast snapshot excludes
them. The shared AGENTS.md's unrelated acquisition edits are excluded from
this commit using a separate commit index and working-tree projection.

After the hook-enabled restart, the read-only source/adoption comparison
returned `:seon.config/missing-effective` for 68 required keys instead of
the value. Convergence is unverified, recorded in the existing
[partial-hot-reload issue](../../../seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md).
The hook also caught a foreign unmatched parenthesis in
`test/seon/render/web_debug_test.clj` immediately after an owned edit;
that file was left untouched and excluded from the test snapshot. Markdown
lint reported 31 historical citation findings beginning in
`agents-md-audit-2026-09-15.md`; no historical citations were changed here.

The one permitted live probe:

```sh
SEON_CODEX_LANE=lane-guardrails-probe bin/test --paths src/seon/schedule.clj -- seon.db-test
```

Exited **64** in the command tool's **0.005037 seconds**, printing exactly:

```text
bin/test: refusing gate from Codex lane lane-guardrails-probe; use bin/test-fast --paths <your files> -- <namespaces>; the orchestrator owns the cold gate.
```

`bash -n bin/test bin/test-fast` and `sh -n bin/codex-agent` passed.
The selected fast namespace run armed **1,111** functions (**1,106**
program-armable). The new lane/refusal/resume regression completed in
**1.563 seconds**; the lane-identified fast snapshot regression completed
in **41.807 seconds**, both without failures. The final stale-log startup
refinement was made after that snapshot was created; the shell smoke
below reran its exact updated regression script against the final launcher.
The full namespace run exited **124**, with no final tally:

```sh
bin/test-fast --paths bin/codex-agent bin/test bin/test-fast test/seon/test_runner_test.clj -- seon.test-runner-test
```

Its snapshot basis was `d49447bac5edc1f0566182fdc2672d01ed072064`.
Two assertions failed in
`the-agent-fork-callable-returns-the-committed-projection` (lines 1045 and
1060, `(map? row)` received nil), matching the existing
[recorder/write-grammar issue](../../../seon/issues/the-recorder-creates-a-test-row-the-write-grammar-validator-refuses.md).
The last reporter event was **02:53:26.243287Z**, beginning
`concurrent-bin-test-invocations-both-reach-their-tallies`; the unchanged
watchdog reported **no reporter progress for 300 seconds**, dumped the
coordinator and descendants, and exited 124. This is the already-filed
[two-gates silence-bound issue](../../../seon/issues/a-test-that-drives-two-real-gates-reports-no-progress-to-the-silence-bound.md),
part of item 2's remaining work. No environment bound was raised. The
snapshot was removed after exit and the recorded runner/descendant PIDs
were absent. The namespace is not claimed green; the orchestrator still
owes the cold and platform proof after the remaining harness work.

The exact shell script authored by the new regression was also extracted
with the Clojure reader and run directly in a disposable checkout: exit
**0**, **2** verified run/resume completions and **2** missing/corrupt-record
refusals. This is a shell smoke check, not an armed test tally. The first
smoke command's reporting suffix used zsh's read-only `status` name and
failed after the script completed; the corrected reporting command reran
the script in a fresh checkout and returned the exit/counts above.

Touched paths: `bin/codex-agent`, `bin/test`, `bin/test-fast`,
`test/seon/test_runner_test.clj`, `AGENTS.md`,
`.agents/skills/clojure-testing/SKILL.md`, this note, and
`docs/seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md`.

## Limits and the remaining refusals/detector

Claude Agent subagents have hook-side identity: their parent's tool hooks
carry `agent_id` and `agent_type`. Those fields are not shell environment
variables, so this shell-only refusal does not enforce Claude admission.
The hook can identify those calls; hook-side admission is still owed.
Existing Codex processes
launched before this export also lack it. A shell can deliberately unset
an environment variable: this catches honest mistakes, not deliberate
removal of identity. Absence of lane identity is not positive evidence of
orchestrator identity.

The current runner reads `SEON_TEST_SILENCE_SECONDS` at
`src/seon/test/runner.clj:530`, default **300 s**; the ordinary exchange
bound is **270 s** at `:546`. Tasks already derive longer exchange bounds
from declared `:seon.test/long-ms` at `:3200`, and the suite horizon carries
the in-flight allowance at `:3297`. `src/seon/test/cache.clj:26` independently
reads the same environment variable with default **300 s**. Those knobs
remain unchanged in this first slice. So does `SEON_TEST_SLOTS` (current
shell default **3**, `bin/_test-slot:14`). Item 2 must close both readers
and the slot-count override, with positive orchestrator identity required
for any retained override.

Item 3 still owes graph-derived overlay completeness and finding-level
static-analysis diagnostics. Item 4 still owes the announcement
`orphaned gate pid N (parent dead) holds slot-K since <etime>` in slot waits
and the gate preamble, without killing the holder.

An agent ending its turn while a run remains in flight cannot be refused
by this launcher: a turn-end event is not an input it receives. The one
requested detector is the orphan announcement above. It detects the
parent-death case; it cannot detect a turn ending while the parent remains
alive, or prove whether a tally was read. No broader guarantee is claimed.

## Item 2 review: declared bounds and override admission

This item is implemented in a HEAD-based worktree at
`6bea78e2b78b7fd2ea433f825e19b2336f8fc48e`, because the shared runner still
contains another lane's uncommitted 153-line change and AGENTS.md carries
the S3 changes. Those shared files were not edited. The resulting isolated
commit needs orchestrator integration; its HEAD-based tests deliberately
exclude the foreign acquisition, schema and test-system work.

`bin/_test-slot` admits `SEON_TEST_SLOTS` and
`SEON_TEST_SILENCE_SECONDS` only with `SEON_TEST_ORCHESTRATOR=1` and no
nonempty `SEON_CODEX_LANE`. Presence without authority refuses, including
an empty override; a lane plus an orchestrator flag still refuses. Both
launchers source this admission before Java resolution, slot acquisition,
or snapshot creation. Count and duration must be positive integers.

The JVM's one environment reader is `seon.test.bounds/silence-seconds`.
Both runner and cache use it; the cache validates before starting its
child. The ordinary body/exchange allowance remains **270 seconds**.
The dated maximum priming measurement **19,760 ms** comes from the three
worker readiness observations in
[test-preparation-costs-2026-09-16.md](test-preparation-costs-2026-09-16.md)
(19,760 / 19,549 / 19,686 ms). Whole-second bounds round upward: ordinary
exchange **290 s**, silence **320 s** including **30 s** reporter grace.
A numeric long declaration replaces the ordinary body allowance when
larger, then adds priming. A reason-only `:seon.test/long` does not invent
a duration; `:seon.test/long-ms` supplies it. An explicit orchestrator
override can widen, but cannot lower, the declared silence horizon.

Worker readiness already reports actual `fixture-preparation-ms`; the
worker now carries that measurement into its task exchange calculation.
The existing in-flight allowance carries the resulting horizon into the
watchdog. Fast `begin-test-var`/`end-test-var` reporter events now install
and remove the same declared long-body allowance, including declarations
in namespace metadata. Ordinary announcements preserve that allowance.
No polling mechanism, progress surrogate, or automatic retry was added.

Regression owners are `seon.test.bounds-test` (derivation and the real
shell admission across absent/lane/both/orchestrator identities) and the
existing `seon.test.runner-test` (real indexed declarations, reporter
events, and actual worker priming carried into the bound). The focused
command is:

```sh
bin/test-fast --paths bin/_test-slot bin/test bin/test-fast src/seon/test/bounds.clj src/seon/test/cache.clj src/seon/test/runner.clj test/seon/test/bounds_test.clj test/seon/test/runner_test.clj -- seon.test.bounds-test seon.test.runner-test
```

The first armed attempt refused this slice's inadmissible `:pos-int`
contract during initialization; corrected to an explicit integer schema
with minimum 1 before rerunning. The corrected run passed **21 tests / 171
assertions / 0 failures / 0 errors**, exit **0**. Review then fixed the END
event ordering: publish progress before removing its long allowance, so
the watchdog never sees the old timestamp under a shorter horizon. The
final rerun, including that transition regression, passed **21 tests / 172
assertions / 0 failures / 0 errors**, exit **0**, ending at
**2026-09-17T03:22:45.351365Z**. Both runs used the ordinary slot policy in
the isolated worktree and acquired a slot without waiting. Shell syntax
and owned-path whitespace checks also passed. No run remains in flight.
Two live fast-entry probes exited **64** before launching a JVM:
`SEON_CODEX_LANE=bounds-probe SEON_TEST_SILENCE_SECONDS=1500` on the
selected-path entry, and `SEON_CODEX_LANE=bounds-probe
SEON_TEST_ORCHESTRATOR=1 SEON_TEST_SLOTS=4` on the plain entry. The latter
printed `SEON_TEST_SLOTS refused: declared slot bound is 3`; the former
named `:seon.test/long`, `:seon.test/long-ms`, measured fixture priming and
the required orchestrator identity. No cold gate or default lifecycle
operation ran. The edit hook queued worktree paths through the main-root
publication queue; default adoption is not claimed. This is the existing
[worktree hook boundary](../../../seon/issues/worktree-edit-hook-publication-targets-main-root.md).

Owned paths: `bin/_test-slot`, `bin/test`, `bin/test-fast`,
`src/seon/test/bounds.clj`, `src/seon/test/cache.clj`,
`src/seon/test/runner.clj`, `test/seon/test/bounds_test.clj`,
`test/seon/test/runner_test.clj`, AGENTS.md's new bound sentences, and
this note. Items 3–4 remain pending at this review checkpoint.
