---
type: research
status: resolved
date: 2026-09-15
tags: [operator, test, runtime]
---

# Hook publication and check feedback

Implementation commits: **`c0006a125`** and **`b8d2fb66b`** on `steward-platform`.

**Assigned class closed:** incremental publication uses the live advertisement
without a census prerequisite; automatic checks exclude declared observations
before preparing or running tests. Both members have live probes and canonical
regressions. The [declaration-loss member](../../../seon/issues/hook-checks-cannot-query-fixture-observation-declarations.md)
is resolved. Final paths-limited and platform gates remain the orchestrator's
proof obligation. The historical investigation and options below precede the
owner's approval; the final option 1 section records the completed work.

## Authorities and dependency ledger

Read AGENTS.md, docs/seon/issues/README.md, the localized issue instructions,
the quiet-window issue, the slow-surfaces/startup-and-hook-waste landing,
and the reaching-tests-tier landing end to end. Read the issue-class-mining
class rows and structural-kill column: P2 removes timing absence as a verdict;
N7 records the missing classification fact before querying it. This assignment
names two new operational defects, not a particular existing class-note
member list. The quiet-window note remains an existing documentation issue.
Read the active roadmap entry and working edge. Applied data-oriented-clojure,
repl, clojure-testing, seon-flow-architecture, datahike and data-modeling skills.

- `bin/seon-hook:1203` waits for the publication child and then the check
  child; `run-source-worker!` at line 1298 drains only after it returns.
  The process-overlap hypothesis was not established. The existing event
  probe from the startup lane remains the sole hook harness.
- `script/seon/fresh_operator.clj:968` catches census observation errors;
  `select-anchor` at line 1354 requires registrations and reachability.
  The former incremental `init!` used that pre-read to refuse publication.
- `resources/seon/operator/state.clj` already validates process identity
  against persisted advertisements. The existing `prepl-eval!` waits for
  the actual operation's terminal result under its declared bound.
- Babashka's process completion is already the hook's event; no timer,
  retry, scheduler, or second worker was introduced. Clojure's prepl
  implementation is `reference-code/clojure/src/clj/clojure/core/server.clj`.
- `src/seon/test.clj:195` owns check selection; `src/seon/fn.clj:348`
  constructs static test rows. The schema declares fixture observations
  at `resources/seon/schemas/seon.test.edn:6`, but row construction omits them.

## Member 1: false JVM refusal

Verdict: **resolved by `c0006a125`**, with the live operation and in-process
regression below. The orchestrator's final gate remains outstanding.

The log claim needed correction: batch `6fedfed5` contained **three** paths,
including `test/seon/mcp_test.clj`; successor `3d800021` contained two.
The preceding 23:34:23 record reports exit **124**, not the same retained
exit-1 refusal. The overwritten failure log cannot establish its old cause.
At 23:39:01.790509Z `6fedfed5` reported convergence; at
23:39:01.790960Z the successor was admitted, and at 23:39:12.994102Z it
reported exit 1. The precise exception behind that historical census failure
was not retained. A read-only census during this lane succeeded in 340 ms.

**Structural guarantee:** incremental publication selects the live process
advertisement and lets the publication operation report its own completion;
a separate registry census cannot veto it.

`init!` now obtains descriptor-only truth for incremental/development
publication and selects the requested cluster's live advertisement. Other
operator operations retain their existing census. No advertisement produces
`:seon.fresh-operator/live-advertisement-unavailable`, not a claim that no JVM
is running. The operation retains its existing prepl completion bound.

The extended `seon.dev.hook-test/idle-edit-starts-without-quiet-delay` uses
the real pending-file lock, enqueue, drain, process-identity census and
terminal writer. Its private Babashka process advertises its own actual
PID/start-instant. Only publication I/O, cache preparation and global claim
writes are substituted. Both batches must choose the exact advertisement;
removing it must produce the named missing-advertisement refusal. No new
harness or shared-JVM test mutation was introduced.

## Exact REPL and adoption evidence

Candidate `init!`, the probe value and covering deftest were evaluated through
MCP JVM mode before source edits. The existing `seon.test/run` recorded each
run in default, with armed contracts. The test launches Babashka, never a test
JVM. The recurring invocation is in the [probe script](hook-publication-race-probe-2026-09-15.clj).
Complete values are in [EDN evidence](hook-publication-race-evidence-2026-09-15.edn).

| Slice / invocation | Pass | Fail | Error | Run entity | Basis |
| --- | ---: | ---: | ---: | ---: | ---: |
| Candidate descriptor selection, before files | 10 | 0 | 0 | 67578 | 536872517 |
| Same probe with HEAD's old `init!` substituted | 4 | 6 | 0 | 67579 | 536872521 |
| Authored files, hot-loaded test | 10 | 0 | 0 | 67580 | 536872523 |
| After successful development adoption | 10 | 0 | 0 | 67582 | 536872529 |
| Candidate missing-advertisement diagnostic, before files | 11 | 0 | 0 | 67583 | 536872530 |
| Final authored files, hot-loaded test | 11 | 0 | 0 | 67585 | 536872533 |
| Final hook adoption, same in-process test | 11 | 0 | 0 | 67590 | 536872540 |

The enclosing MCP calls for the final two runs took **3558 ms** and
**3676 ms**, including test provenance and result recording. They are not
isolated assertion timings. The old implementation made one census request
and never admitted the first publication; the new implementation made exactly
two publication requests and drained all three successor edits.

The ordinary command
`bin/seon init --dev default --changed script/seon/fresh_operator.clj test/seon/dev/hook_test.clj`
converged at `6aa9dfe0-ab4c-58fa-892d-7bab168f2c65`, digest
`8358d3c80af6684929b7d1b58c35a66e4a093031ae4c580ecf94a902c770032b`.
A live comparison returned that same commit from default's source fact and
`seon.cluster.source/current`. Default stayed PID **69622**. The final
diagnostic edit's hook batch is `a92dbbd6-de9e-47f7-b699-ca36ff864f1f`;
its terminal evidence is recorded below when available.

## Member 2: expensive check feedback — protected prerequisite

Default basis **536872526** had **zero** fixture-observation test facts.
The examples test exists, directly calls SCI evaluation and has no
fixture-observation declaration. At basis **536872528**, the existing
reach rules return **19** cluster and **16** value-renderer functions for
it. `render-ai` itself is not among them. No incorrect edge was verified;
the claim that it reaches every render owner is too broad.

A check candidate partitioned selected tests by their stored observation
declaration, ran ordinary tests, and returned deferred symbols/reasons plus
an explicit-test command. Its canonical fixture regression passed **9**
assertions, run **67577**, basis **536872516**, because it explicitly inserted
the observation fact. An earlier candidate fixture transaction was refused
for missing admission-source metadata; that setup was corrected. The live
zero-fact probe then falsified the candidate's production premise.

The [candidate diff and regression](hook-publication-race-deferred-candidate-2026-09-15.patch)
are evidence only, not landed code. `seon.test` was restored from the current
authored source and re-armed. There is no claim that the candidate CLI exists:
`bin/test-check` still only accepts root/cluster selection. A full fix needs
the real declaration-to-row regression, an observation declaration for the
examples fixture, and explicit CLI test selection with a declared allowance.

The assignment explicitly protects `src/seon/fn.clj`; its `var-row` needs to
preserve the existing observation declaration. Runtime declaration parity
also belongs in `src/seon/sci/eval.clj:392`, which was concurrently edited
during this lane. Those owners and every listed protected file were untouched.

### Three priced options for the remaining cross-owner work

1. **Recommended: publish the existing observation facts, then defer them.**
   Approximately **0.5–1 engineer-day**, including static/runtime parity and
   the CLI. Guarantee: automatic checks never execute a declared observation;
   ordinary feedback and exact deferred commands remain available. Gives up
   automatic execution of those declared observations. Requires the protected
   row owner's coordinated change.
2. **Use the runner's existing Var-metadata declaration reader during check
   preparation.** Approximately **0.5 day**. Guarantee: declared observations
   are excluded from execution after their namespaces load. Gives up
   database-only selection and does not repair queryability of those facts.
   This is weaker than the repository's stated structural target.
3. **Defer the entire automatic check to the explicit namespace gate.**
   Approximately **0.25 day**. Guarantee: hook feedback cannot be consumed by
   test execution. Gives up the requested cheap reaching-test feedback; this
   is a scope change, not completion of the assigned behavior.

## Probe correction and boundaries

One direct invocation of the private operator `init!` inside default's JVM
was an incorrect live-probe surface: root claims identify the calling
process, so it temporarily made default's long-lived PID the root creator.
The next Babashka hook refused that creator. The probe future was cancelled;
under the existing control lock, a compare-checked restoration reinstated
the recorded prior creator **85589** and removed only the probe's
supersession entry. The normal operator command subsequently converged.
No process lifecycle, store, agent facts, or foreign session was changed.
Use the existing bounded Babashka probe for this CLI owner, even when its
new form is also evaluated through MCP as required.

Initial source residue in SCI evaluation, turn execution and their tests was
preserved. No `bin/test`, `bin/test-fast`, test JVM, scratch cluster or foreign
lane operation was launched. The sole lane operator shell completed; probe
resources are closed by the existing regression. Final cleanup is recorded
below. Markdown lint reports pre-existing dependency-citation errors in
`agents-md-audit-2026-09-15.md`; it is not evidence against these edits.

The gate request at `tmp/orchestrator/gate-requests/hook-publication-race.txt`
contains `seon.dev.hook-test` and `seon.dev.fresh-operator-test`. The
orchestrator must run their paths-limited gate, then the platform tier,
serially. Neither gate is claimed green here. The existing quiet-window
documentation issue remains open and unchanged.

## Final verification

Batch `a92dbbd6-de9e-47f7-b699-ca36ff864f1f` converged at
`6aa9e106-3112-5598-8157-9213553beb47`, digest
`60fea583bae72682afe5b70a029acd16bf48960fef88fa386a297d87de551847`.
Its automatic check reported widening for the script path, zero executed
tests and **5.18875 ms** elapsed, with exact paths-limited gate commands.
The subsequent in-process regression passed **11/11**, run **67590**,
at **00:24:42Z**, enclosing MCP call **2433 ms**. During its comparison
another publication advanced current-src to
`6aa9e17a-106e-5381-bbda-051f779c0d0c`; default still reported the lane's
converged `6aa9e106` commit. This is a recorded concurrent boundary,
not a claim that the two heads matched then.

Static lint of the operator, regression and durable probe: **0 errors,
54 warnings**, 89 ms. `git diff --check` is clean. The warning set and
foreign Markdown citation failures were not converted into a passing gate
claim. The residual issue index entry remains the orchestrator's ownership.

Cleanup complete: the probe future and candidate-only test Var were removed
from the development JVM; `tmp/hook-publication-race` was deleted after
retaining the evidence. The operator shell exited normally. No lane shell,
scratch root, or worktree remains. Final `bin/seon status` still reports
default PID **69622**, alive, with no orphan Seon JVMs. The gate-request file
and shared publication records remain for the orchestrator.

## Approved option 1 — landed

The owner released the static row owner and approved option 1 on September 15.
Commit **`b8d2fb66b`** completes that scope. Guarantee: **an authored fixture
observation survives both declaration paths into its canonical program row,
and automatic checks exclude every such selected row before fixture preparation
and execution, reporting its reason and exact explicit command.**

The existing mechanisms were extended in place:

- `src/seon/fn.clj:371` and `src/seon/sci/eval.clj:410` copy the existing
  observation metadata into the static and runtime test declarations.
- `src/seon/program.cljc:69` retains that attribute in the one owned test-row
  shape. The first live parity probe falsified the initial two-path-only
  candidate: canonical projection still stripped the supplied attribute.
  The regression also corrected its own attempt to transact the evaluation's
  transient `:seon.sci.eval/evaluated?` marker; the final probe transacts only
  the declaration row, as the installation owner does.
- `src/seon/test.clj:220` partitions selected rows before provenance, fixture
  preparation, namespace loading, and execution. Only runnable tests enter
  those operations. `feedback` prints every deferred row's declaration and
  command. `check`, `check-adoption`, the digest owner and their bounds were
  left intact. `src/seon/test.clj` and its schema had no foreign diff when
  edited; the reviewed commit contains only this lane's small additions.
- `resources/seon/schemas/seon.test.edn:121` declares the optional deferred
  result and command shapes. Existing keys retain their meanings.
- `bin/test-check` accepts `CLUSTER --test NS/TEST [--time-limit-ms N]` and
  uses the existing preparation, resolution and `seon.test/run` owners over
  the existing prepl transport. It starts no JVM. The configured check limit
  supplies the default explicit-run allowance; a caller can declare a larger
  positive allowance without changing cluster configuration. A failed explicit
  test exits nonzero. Ordinary adoption-check invocation is preserved.
- `test/my/examples_test.clj:41` now declares its actual HTTP, filesystem and
  background-effect observation. Its body is unchanged. No unjustified call
  edge was removed and no claim is made that it reaches every render function.

The admission regression uses canonical database fixtures, real static source
analysis, a real SCI context, and installed contracts. The check regression
extends the existing reaching-test fixture: an observation row comes from
real source indexing, the ordinary test passes and records its run, the
observation has no run, and an explicit call to the existing runner executes
it. This replaces the earlier rejected hand-inserted-metadata candidate as
the recurring proof. No second hook harness was introduced.

### Exact in-process runs

Each candidate function form was evaluated through MCP JVM mode before source
editing. The two canonical invocations, before and after adoption, were:

```clojure
(seon.test/run #'seon.fn-test/fixture-observations-survive-static-and-runtime-admission
               (seon.operator/connection "default"))
(seon.test/run #'seon.test-reaching-test/declared-observations-defer-before-cheap-reaching-tests
               (seon.operator/connection "default"))
```

| Probe | Recorded run | Pass / fail / error | Enclosing MCP duration |
|---|---:|---:|---:|
| Admission candidate before canonical-shape fix | 67598 | 6 / 5 / 0 | 6822 ms |
| Admission candidate, complete seam | 67760 | 11 / 0 / 0 | 7643 ms |
| Admission after adoption | 68065 | 11 / 0 / 0 | 6372 ms |
| Check candidate | 67764 | 9 / 0 / 0 | 17310 ms |
| Check after adoption | 68067 | 9 / 0 / 0 | 16216 ms |
| CLI execution form evaluated through MCP | 68063 | 8 / 0 / 0 | 15542 ms |
| Edited CLI over the same live JVM | 68064 | 8 / 0 / 0 | recorded at 00:39:09Z |

The CLI probe command was
`bin/test-check default --test seon.id-test/data-shape-and-explicit-length-determine-identity --time-limit-ms 10000`.
It exited **0**. Its generated form was evaluated through MCP before the file
edit; it uses `seon.test/run` with explicit connection, provenance and bound.
Complete returned data is retained in the
[option 1 evidence](hook-publication-race-option1-evidence-2026-09-15.edn).

### Live result, gates and boundaries

Default basis **536872577** contains **55** observation declarations, compared
with **0** before this change. Checking the examples test alone returned no
executed tests, no run provenance and one fully named deferred observation in
**3.492875 ms**. A mixed real check selected that test plus the identity test:
it ran only the identity test, recorded **8** passing assertions (run **68068**),
and reported the examples test's exact deferred command in **8619.659167 ms**.
The elapsed time includes the existing provenance/preparation work; that
performance remains the reach-digest lane's responsibility.

Hook batch `e1ec7b1e-0afc-4255-b37f-0719d79ad1ef` converged at
`6aa9e4fe-78cf-52e0-9370-0eb1dc46cc5b`. Its widened check returned in
**7.10225 ms**. A subsequent complete `bin/seon init --dev default` exited **0**;
at basis **536872584**, both default and current-src equal
**`6aa9e594-cf98-58c8-9ac4-fba18975c3d8`**, digest
`99a0c49bcc1c5e1069721aac8429c6c0cbaade4c4b11131129a23f70a4c78f5d`.
This is in-place development adoption, not a new fork.

During concurrent editing, earlier publication attempts reported source-digest
change and an `exact-source` index-bound exception during full analysis.
The latter's cause was not isolated; the existing
[source-offset issue](../../../seon/issues/source-analysis-can-slice-changing-files-with-stale-offsets.md)
owns that failure signature. Their successor and the complete publication converged;
no second retry mechanism was added. No foreign file was changed to obtain
this result. The foreign dirty operator, MCP, cluster and web-context test
paths were preserved. No default lifecycle operation was performed.

Static lint of the eight Clojure/script files: **0 errors, 62 warnings**, **407 ms**.
`git diff --check` passed. The Markdown hook still names the twelve pre-existing
dependency-citation failures in `agents-md-audit-2026-09-15.md`.
No test JVM, `bin/test`, or `bin/test-fast` was launched. The updated request at
`tmp/orchestrator/gate-requests/hook-publication-race.txt` names the affected
namespaces for the orchestrator's serial paths-limited and platform gates;
neither gate is claimed green here. The issue index remains its owner's file.

Option 1 cleanup: both lane-launched operator/CLI shells exited normally.
The temporary candidate forms, lint output and adoption log were removed
after retaining the complete probe values and measured results above.
No lane background shell, scratch cluster or worktree remains. The authored
regression Vars stay loaded normally. Default PID **69622** remains alive.
