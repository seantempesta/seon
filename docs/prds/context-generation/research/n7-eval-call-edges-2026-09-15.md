---
type: research
status: active
tags: [research, database, test, architecture]
---

# N7 residual — ordinary evaluation call edges

## Outcome and boundary

Implementation commits: `f402c5d3d` (analysis) and `924fdbf3a` (persistence),
on `steward-platform`. Cold-worker follow-up: `171c0c193` verifies four
previously failing tests on fresh canonical bases: 18 assertions, zero failures
or errors. See the final section for the corrected attribution and boundaries.

**Guarantee:** every ordinary submitted evaluation uses the existing batch
analysis and commits its resolved call refs on its evaluation identity through
the same settlement writer that commits read evidence.

The turn integration is applied and verified in default's JVM. Both the direct
settlement API and ordinary turn fold persist `seon.db/q` and `my.turn/wait`
refs. Declaration edges remain on their program rows. No second analyzer,
edge family, callable identity, or namespace prefix rule was added.

The two canonical in-process regressions pass after development adoption:
**24 assertions, zero failures, zero errors**. No test JVM was launched in the
resumed assignment. The orchestrator's namespace/platform gates remain pending.
The issue is narrowed to its historical acceptance item: re-evaluating old
ablation conclusions; new evaluation persistence is fixed. The separate
retained-fixture lifecycle issue remains open; a fresh canonical base has the
correct contract and passes, so no fixture production change was needed.

## Grounding and dependency ledger

Read end to end: AGENTS.md; docs/seon/issues/README.md and its localized
AGENTS.md; the N7 class note; all nine member notes named in the N7 landing
(including the six now archived); the additional archived changed-test-selector
member; the N7 landing; the reaching-tests landing; and src/seon/fn.clj.
Read the class-mining N7 row and structural-kill column: **record the missing
fact, then query it; constructors accept no roster/prefix/count**.
Read the active roadmap entry and turn PRD §§13–15.

Applied data-oriented-clojure, repl, datahike, clojure-testing, and data-modeling
skills. This assignment owns one residual, not the other N7 owners.

- `reference-code/clj-kondo/analysis/README.md:120`: var usages carry resolved
  `:to`/`:name`; `:arity` distinguishes calls from ordinary Var references.
  `src/seon/fn/analyzer.clj:89` preserves these facts. Its stdin path disables
  cache writes; the change reuses that path.
- `src/seon/fn.clj:475`: resolvable runtime targets derive from existing
  program identities and declaration rows in the batch.
- `src/seon/fn.clj:539`: exact source spans assign each analyzer entry to its
  submitted form. The new projection uses that same already-local analysis.
- `reference-code/datahike/src/datahike/db/transaction.cljc:717`: multi-value
  refs and lookup-ref recognition; persisted declaration probes use the existing
  database transaction owner, not a separate graph store.
- `src/seon/turn.clj:1173`: relation assertions already emit call refs.
  `:1672` commits those alongside read evidence. `:158` already carries
  form facts through settlement.
- `src/seon/fn.clj:802`: the existing reach rules and gate-set query serve
  `tests-reaching`; `src/seon/test.clj:150` uses that authority for selection.
  Neither consumer needs a new reachability algorithm.

Archaeology: `1f3c099d2` introduced batched declaration analysis and the current
runtime target query. `5deb40e4e` fixed namespace relevance, not this residual.
Initial read/probe HEAD was `51e1998843d8063654f82e9aaf381674bdb9a4d4`;
the attempted gate snapshot used `d5787c4f8d4a6269e1e28952830d2406a44ec77d`.

## Initial analysis slice — before turn integration

Only [agent-form-calls-to-core-namespaces-are-not-indexed](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md)
is assigned here. At that checkpoint it was open for protected turn integration and its live
persistence proof; the resumed verdict is above.

1. `analyze-forms` admits absence of a declaration row. A supplied row still
   must satisfy the complete declaration contract; explicit nil is not admitted
   in the request map.
2. `analyze-form` translates its existing nullable positional argument into
   that absent key.
3. `analyzed-form` returns ordinary-form call refs in the existing first tuple
   member. Declaration call refs remain solely on their program row. It does
   not manufacture a callable identity for an ordinary evaluation.
4. One canonical regression covers mixed declaration/test/ordinary batches,
   both namespace families, local shadowing, source-span separation, absence
   of an invented declaration, and persisted declaration reachability.

No schema resource change is needed: `:seon.fn/calls` already declares a set
of refs and the settlement writer already accepts the corresponding facts.
This slice does not add missing SCI binding rows or repair other N7 members.

## Exact live evidence

All MCP evaluations used JVM mode on default, PID 69622, root
`/Users/sean/src/seon`. No default lifecycle operation or provider call ran.
The [probe source](n7-eval-call-edges-probe-2026-09-15.clj) and
[complete saved results](n7-eval-call-edges-evidence-2026-09-15.edn) are retained.

Before editing production source, the exact ordinary `analyze-form` request
in the probe script refused in `analyze-forms` at
`[0 :seon.program/row]`: expected a map, got nil.

The three candidate definitions were evaluated from a project-local file via
MCP before editing src/seon/fn.clj. After arming, an immutable candidate
projection was made with the existing
`seon.schema/projection-with-function-contract` owner and the candidate's
authored metadata. This was necessary because the default database then held
the old contract. No default schema transaction or fixture-global mutation
was used. The representative call returned, in **4,824 ms**:

```clojure
[#:seon.fn{:calls #{[:seon.fn/sym "seon.db/q"]
                   [:seon.fn/sym "my.turn/wait"]
                   [:seon.fn/sym "clojure.core/do"]}}
 nil]
```

The single-form call exercises all three changed functions. The initial
projection probe mistakenly asked raw `@connection` for a carried projection;
that nil-projection refusal was corrected with `seon.db/db` and the explicit
projection owner. It is not attributed to the call-edge defect.

Exact regression commands:

```clojure
(seon.test/run
 #'seon.fn-test/ordinary-form-analysis-keeps-call-edges-without-a-declaration
 (seon.operator/connection "default"))

(seon.test/run
 #'seon.fn-test/defining-forms-share-one-form-local-kondo-batch
 (seon.operator/connection "default"))
```

| Run | Pass / fail / error | Recorded run / basis | Result |
|---|---|---|---|
| Candidate, before production edits, 23:22:40.093Z | 1 / 0 / 1 | 65002 / 536872137 | Canonical fixture's old required-row contract refuses request 2. |
| Immediate post-edit attempt, 23:23:23Z | 1 / 0 / 1 | 65005 / 536872139 | Same retained fixture contract; no adoption claim. |
| Existing declaration regression, 23:26:12Z | 5 / 0 / 0 | 65270 / 536872157 | Existing definition behavior preserved; MCP 1,856 ms. |
| Reloaded new regression after live contract observation, 23:27:07.367Z | 1 / 0 / 1 | 65281 / 536872171 | Same fixture boundary; MCP 1,911 ms. |

A later direct call with default's ordinary database value, **without** the
candidate projection, returned the same three edges in **18 ms**.
A four-form mixed batch returned in **50 ms**: core and my.* refs on the
declaration and ordinary form; the test calls the new batch declaration;
the shadowed local map adds no clojure.core/map edge. No declarations from
this analysis-only probe were installed in default.

Persisting the analyzed function and test rows on an isolated canonical
fixture succeeded at both transactions. `tests-reaching` returned exactly
`["seon.db/n7-observed-test"]` for the new function, and that test also
appeared in the transitive reach of `my.turn/wait`. **10,052 ms**, including
fixture acquisition and both queries. This proves declaration graph storage
and reachability; it does not stand in for the protected ordinary-turn proof.

A 6 ms independent query observed the widened spec on default and the old
required-row spec inside the canonical fixture. Filed
[canonical-fixture-retains-old-function-contracts-after-adoption](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md).
No fixture cache or another lane's session was operated.

At that observation, default's adoption record was
`6aa9d0b3-5a57-5862-af74-5f8717513752`, while current-src was
`6aa9d3b4-eabe-5a99-9d3b-8d859cb9b5dc`. Thus the changed contract and callable
were observed, but complete source convergence was not established. An earlier
hook publication refused because source changed during analysis. Hook feedback
contained shadowed-var warnings in fn.clj, no blocking fn.clj finding.

## Resumed integration — 2026-09-15

The owner freed turn.clj after `72d7dc3a9`; `git status` confirmed it clean.
`analyze-settlement` now admits ordinary forms. `resume-turn` supplies every
submitted form to its existing batch and attaches ordinary call facts to the
existing evaluation identity. Both continue through `receipt-settle-call`'s
relation assertions beside read evidence. Accepted declaration rows remain in
the batch, supplying same-batch callable identities. The pre-install function
gate retains its earlier candidate analysis; no alternate analysis path was
introduced.

Both changed private function forms were read from a candidate file and
`eval`uated in `seon.turn` through MCP JVM mode before production edits.
The replacement persistence regression exercises each function with real
canonical data: direct `seon.sci.eval/evaluate` followed by settlement, then
`resume-turn` with the ordinary real SCI evaluation path. The old assertion
that ordinary evaluations have no call edges is deleted in the same commit.
The two turn examples use distinct agents, because the first direct-settlement
probe intentionally does not close its turn. `plan-tx` already creates the
evaluation identity, so the test does not call `receipt-start-tx` again.

Exact commands, each with `(seon.operator/connection "default")`:

```clojure
(seon.test/run
 #'seon.fn-test/settled-form-records-calls-across-every-program-namespace conn)
(seon.test/run
 #'seon.fn-test/ordinary-form-analysis-keeps-call-edges-without-a-declaration conn)
```

| Phase | Test | Pass / fail / error | Recorded run / basis | MCP time |
|---|---|---|---|---|
| Retained old fixture, 23:40:00Z | ordinary-form-analysis | 1 / 0 / 1 | 67058 / 536872325 | 2,143 ms |
| Candidate test setup, 23:42:33Z | settled-form | 7 / 6 / 0 | 67061 / 536872328 | 3,918 ms |
| Fresh base, before edits, 23:42:38Z | ordinary-form-analysis | 12 / 0 / 0 | 67062 / 536872329 | 11,115 ms |
| Corrected candidate, before edits, 23:43:03Z | settled-form | 12 / 0 / 0 | 67063 / 536872330 | 3,862 ms |
| After adoption, 23:46:09Z | settled-form | 12 / 0 / 0 | 67084 / 536872342 | 4,512 ms |
| After adoption, 23:46:14Z | ordinary-form-analysis | 12 / 0 / 0 | 67086 / 536872343 | 13,202 ms |

The fresh base was initialized by loading the canonical test-support namespace
in the existing JVM, then realizing its base with default's explicit carried
projection. A first preparation without that projection refused initial schema
population; the corrected preparation used `seon.schema/call-with-projection`,
as `seon.test/run` does. No schema roster or contract override was supplied.
This is explicit fresh fixture initialization, **not** proof that an already
retained fixture automatically follows adoption. That independent lifecycle
issue remains open. Default was never stopped, reforked, or restarted.

The complete live persistence result is retained in the evidence EDN and its
reproducible probe below. In **2,459 ms**, both direct and folded evaluation
identities had exactly these call targets:

```clojure
#{"clojure.core/do" "my.turn/wait" "seon.db/q"}
```

The real evaluation returned `{:my.turn/disposition :wait,
:my.turn/note "Waiting."}`, outcome `:ok`, duration **103 ms**, and the exact
shown text. A separate **11,708 ms** graph probe committed the analyzed
agent-authored function/test rows and returned
`["seon.db/n7-observed-test"]` from `tests-reaching` over
`"seon.db/n7-observed"`; transitive `my.turn/wait` reach also contained it.
This is installed program-graph reachability, not execution of that synthetic
test or a claim that the function passed the separate accretion gate.

`bin/seon init --dev default --changed src/seon/turn.clj --changed
test/seon/fn_test.clj` exited zero after loaded definitions, SCI acquisition,
and instrumentation. It reported development convergence at source commit
`6aa9d851-0cfa-50c8-8962-eaa48cc5df29`, digest
`39e2f56129402c42f32165b6015065a2b47fb0b589ad209afd7a05afccb452cd`.
The post-edit runs used that adopted definition. A subsequent observation
found current-src had advanced again to `6aa9d8bd-e156-5a4c-a877-bab45dc9db72`;
no claim of permanent equality across concurrent publication is made.

The persistence slice is **2 files, 80 insertions, 62 deletions**, **9,704 bytes**
of binary diff. `git diff --check` passed. The gate-request file now contains
only `seon.fn-test` and `seon.cluster.turn-test`, one namespace per line.
No test JVMs or provider calls ran. Historical evaluation rows and old ablation
scores are not backfilled; that exact acceptance residual stays in the issue.
Other N7 members and protected SCI/render owners are outside this slice.

The three changed Markdown documents pass their file validators. Repository
pin lint still reports unrelated audit citations; those files were preserved.
The publication shell exited, the completed preparation future was unbound,
and the lane's `tmp/n7-eval-call-edges` scratch directory was removed after
retaining the probe and complete results here. No owned background shell or
scratch cluster remains.

## Initial gate attempt and cleanup history

Attempted, once:

```text
bin/test --paths src/seon/fn.clj test/seon/fn_test.clj -- seon.fn-test
```

Exit **75**, before any test JVM or test assertions. Snapshot phase: **2 s**.
Exact refusal: `test runs are orchestrator-only right now (orchestrator batches
gates; set 2026-09-15 21:05Z)`. No platform invocation was attempted after this
explicit policy refusal. The affected namespace names are now queued in
`tmp/orchestrator/gate-requests/n7-eval-call-edges.txt`.
No gate success is claimed.

The code delta is **2 files, 77 insertions, 7 deletions**; the output of
`git show --format= --binary f402c5d3d` is **6,036 bytes**. No protected file
was edited. No scratch cluster or worktree was created; the refused gate's
shell exited. Probe scratch files are removed after retaining this evidence.
The refused gate left `tmp/test-runs/run.OtOT2Y`; its recorded launcher PID
68834 was absent, lane status and the JVM process table showed no holder,
and this lane removed only that snapshot without following symlinks.
The shared hook workers and another lane's files are not owned by this lane.
Markdown feedback reports existing gitlink citation errors in the separate
agents-md audit; those documents were not edited.

Final post-commit MCP observation: **56 ms**, the same three ordinary call
refs. Default's adopted source was `6aa9d3fc-9fe2-5348-91c0-53d22e0eaadb` and
current-src was `6aa9d576-f74f-59e8-8ba8-59cfe8540658`; complete convergence
was still not established. The shared development system remained alive.

## Batch 14 cold-worker follow-up — 2026-09-15 assignment

Batch 14 was red: 89 tests, 365 assertions, 59 failures, 44 errors. The earlier
24 passing assertions did not establish that the complete turn namespace was
green in a cold worker. This follow-up makes no such broader claim either.

Read the diffs of `72d7dc3a9` and `924fdbf3a`, the gate's failure records, the
four selected tests, their shared fixture, the evaluation output schema, and
the existing dynamic-call and retired-result consumer issues. The proposed
production return-shape attribution was **falsified** for the selected cases:

- `72d7dc3a9` changes evaluation database custody lookup, not the returned
  namespace/ending-namespace/print-options/record fields.
- `with-cluster` replaced the real evaluator with `fake-evaluate`, whose
  two-key result violates four required output fields. The complete direct
  refusal is retained; the failure happens before settlement call-edge analysis.
- `:seon.cluster.eval/result-edn` is uninstalled. The batched test passes the
  resulting `:seon.db/invalid-read` map to `clojure.edn/read-string`. No parsed
  map was stored in place of a string. The shared `semantic-result` also still
  serves error-data codec callers, so globally weakening or changing it would
  mix two different contracts.
- Dynamic `requiring-resolve` calls were invisible to static graph indexing.
  That is the independent fn census failure documented before this slice.

### Kill and exact boundaries

`171c0c193` fixes the existing fixture's evaluator seam: the real
SCI evaluator constructs the complete result envelope for the injected value,
then the fixture overlays its deliberately supplied evidence. No return contract
is widened and no schema declaration is added. The two selected observations
query saved `:seon.eval/shown`; the batched test asserts the exact shown `"43"`.
Other retired-field/print-node assertions remain the existing consumer issue.

The turn owner calls `evaluate-for-install`, `evaluate-sources`, and
`preview-sources` directly. Its forward definitions use ordinary `declare`.
Removing the unused static `seon.bootstrap` require from `seon.sci.eval`
breaks the namespace cycle; `build-base-ctx` already resolves the declared
injected symbols through `requiring-resolve` when a context is constructed
(`src/seon/sci/eval.clj:195`). No new loader or analysis mechanism is added.
The source dependency probe returned `:eval-to-turn nil` and
`:turn-to-eval [seon.turn seon.sci.eval]`. The graph regression additionally
asserts the real system-turn → evaluate-sources edge, which the old expected
set omitted because that call was also dynamic.

This follow-up is **4 code/test files, 22 insertions, 11 deletions; 6,326 bytes**
of binary diff. `git diff --check` passed. The gate request was left byte-for-byte
unchanged: only `seon.fn-test` and `seon.cluster.turn-test`, one per line.

### Fresh canonical proof

All runs were in default PID 69622 through MCP JVM mode, with explicit
`(seon.operator/connection "default")`. No test JVM or provider was launched.
The baseline used `create-base` on a new private clone of batch 14's published
base `8d743392a4e4b9bbf989ee96357b2b55e21de58cb69efede49f1f23085834e24`.
Each test obtained a fresh canonical database branch; each turn fixture acquired
its fresh SCI context through `fork-cluster-ctx`.

Candidate and post-adoption runs built complete canonical source populations
from detached HEAD `779b1ec06` plus ONLY the four owned candidate paths under
`tmp/n7-cold-wt`, with reference-code linked. No schema roster or hand-written
call-edge rows were used. The fixture's existing `source-manifest` and
`database-base` Vars were scoped with `with-redefs-fn` only around construction
and the serial test calls, then restored. Every new base was closed in `finally`.
Protected test-support/test-runner/render files were neither edited nor reloaded.

The two changed turn function forms were evaluated from the candidate file;
`seon.instrument/apply!` re-armed **1,057** host contracts before the candidate
runs. The candidate `with-cluster` and changed deftests were also evaluated
before file edits. The real lifecycle and batched turn tests call the changed
functions with canonical data; the graph test reads a freshly built population.

| Test Var (namespace abbreviated below) | Baseline pass/fail/error (run) | Candidate pass/fail/error (run) | Adopted fresh pass/fail/error (run / basis) |
|---|---|---|---|
| `seon.cluster.turn-test/a-completing-disposition-closes-in-the-terminal-transaction` | 0/0/1 (67390) | 2/0/0 (67418) | 2/0/0 (67567 / 536872507) |
| `seon.cluster.turn-test/a-waiting-disposition-frees-the-agent-and-keeps-its-note` | 0/0/1 (67391) | 6/0/0 (67419) | 6/0/0 (67568 / 536872508) |
| `seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts` | 6/0/1 (67392) | 7/0/0 (67420) | 7/0/0 (67569 / 536872509) |
| `seon.fn-test/agent-source-reaches-the-evaluator-through-one-visible-path` | 1/2/0 (67393) | 3/0/0 (67421) | 3/0/0 (67570 / 536872510) |

Final total: **4 tests, 18 assertions, zero failures and zero errors**.

Every row above uses `seon.test/run` over the named Var and the explicit default
connection. Full returned results and the complete refused output/read values
are retained in [cold evidence](n7-eval-call-edges-cold-evidence-2026-09-15.edn).
The [probe](n7-eval-call-edges-cold-probe-2026-09-15.clj) records the exact fresh
base construction and serial test calls.

The first post-adoption attempt passed completion, waiting, and graph census;
the batched test hit the runner's **20,000 ms** event bound (run 67561).
Cancellation cleanup was still releasing a branch when base deletion ran.
The one owned store was subsequently observed to have no active connections,
then deleted. This failed attempt is retained, not counted green. The final
run uses the existing run-options arity and default's declared
`:seon.test/check-time-limit-ms` (**120,000 ms**) as its remaining bound, with
fresh provenance. No runner function, contract, or timeout configuration changed.

Development adoption exited zero, including loaded definitions, SCI acquisition,
and JVM instrumentation, at source commit
`6aa9dd6e-169a-5556-81fe-a35cc3bbe80a`, digest
`520f5de1a7a7789e8455eaf2c7d5c50ab1e98ac46287f57c2b43822d0ca1ef2a`.
The final four runs exercised those adopted definitions against a newly built
canonical base; their source bytes matched the isolated candidate snapshot.
The complete batch/platform gate remains the orchestrator's final proof.

The publication shell exited. The final scoped base closed successfully and
its fixture roots were restored. The completed probe future was unbound, and
the owned worktree and scratch directory were removed after retaining this
evidence. The batch-14 retained root and foreign files were preserved.
