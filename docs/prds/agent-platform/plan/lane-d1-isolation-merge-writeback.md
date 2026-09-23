---
type: plan
status: implementation specification; proof gates explicit
created: 2026-09-21
lane: D1
depends-on: [A1, A2, B1, B2, B3, B4]
tags: [agent-platform, isolation, merge, write-back, datahike-branch, sci-fork]
---

# Lane D1 — branch, edit, test, review, merge and write back

## 0. Purpose

Seon agents change code on their own branch, and outside agents use the same door.
They receive immediate validation and affected-test feedback while their work stays
isolated from the cluster's shared program.
Root reviews the branch diff, then accepts through a mandatory gate or sends the
work back with a reason.
Accepted changes reach the cluster and source files only through a tested, reviewed
merge, verified file export and proven JVM convergence.
The system already has branches, custody, submission, selection and source writers;
compose those owners around immutable inputs instead of building parallel mechanisms.

This is a target specification, not a claim that the complete path is installed.
[Dated evidence and the superseded specification](../../../research/agent-platform/d1-evidence-2026-09-23.md)
retain all earlier measurements, exact probes, review history and design alternatives.
Owner rulings `a29faf5a6`, `38863a0c8`, `215c32d9a`, `5ec049cfd` and `eb709fcb8`,
and review fold `7523dd510`, are incorporated below.

D1 owns comparison in `src/seon/program.cljc`, merge/export composition in
`src/seon/cluster/source.clj`, candidate facts in `resources/seon/schemas/seon.cluster.edn`
and regressions in `test/seon/program_test.clj` and `test/seon/cluster/merge_test.clj`.
B1 owns canonical analysis, publication and the MCP bridge; B2 owns contexts,
installation and lifecycle; B3 owns tasks/configuration; B4 owns test execution and
evidence. Producer and consumer changes land together under whole-file ownership.

## 1. Requirements summary

The numbered requirements below are acceptance criteria. Each stage explains the
observable result, implementation seam, cost and recovery behavior.

| ID | Testable requirement | Stage |
|---|---|---|
| R1 | Create/list/unlink a named branch from an explicit immutable basis without starting a cluster or copying its program. | 1 |
| R2 | Only assigned work runs; candidate definitions, effects and faults stay under its custody; cleanup waits for exit. | 1 |
| R3 | Inside and outside source submissions use one entrance and persist the same branch program rows. | 2 |
| R4 | Four entry checks default to `:gate`; refusal precedes evaluation; `:warn` preserves callable experiments without stale contracts. | 2 |
| R5 | One admitted request has one run; retries reconcile it; successful replies prove exit, installation and feedback completion. | 2–3 |
| R6 | Every changed batch, including direct retractions, triggers exactly its affected-test request; reads and result writes do not. | 3 |
| R7 | Namespace context equals the ordinary agent context for identical inputs; deficiencies become routed task/issue data. | 3 |
| R8 | Merge enforces contract, coverage and test-first checks regardless of entry dials; unresolved scope remains explicit. | 4 |
| R9 | Acceptance covers every required reaching/task test with content-valid evidence for the exact combined program. | 4 |
| R10 | Net changes use canonical definition digests; cross-branch writes normalize identities, refs and components completely. | 5 |
| R11 | Root explicitly accepts or sends back; conflicting definitions create one structural root task per competing content. | 5 |
| R12 | The writer accepts only the tested head and proposal; moved heads require a new combined proof; evidence survives cleanup. | 5 |
| R13 | Load → arm → record excludes conflicting reloads and dependent execution; partial convergence never reports health. | 6 |
| R14 | Export stages captured bytes, proves canonical analysis and callable equivalence, then installs with digest fences. | 7 |
| R15 | Every partial install and restart reconciles the same accepted export before execution; commit is path-limited and never pushed. | 7 |
| R16 | File-only changes publish to the named branch and join the same gate, with required platform proof before integration. | 7 |
| R17 | Record each phase's time, work and memory; reuse valid inputs; leaf overhead targets <1 s without hiding test-body cost. | All |

Across all stages, a declared agent mistake returns a flat declared error. Core
failures retain the whole cause, commit an error fact and wake the responsible agent
(root by default). `:panic` stops the failing operation/graph loudly while the JVM
and REPL stay reachable; `:record` lets unaffected work continue after storage and
delivery. Failure to store the error panics in either mode. No catch may turn a
core failure into a warning, nil, a default result or a print-only diagnostic.

## 2. Stage 1 — Branch

**What the agent sees.** Create a named branch, work there, list it, then delete it
when finished. Delete means unlink; retention and GC decide when storage disappears.
Every MCP call names its branch, including status and host inspection. An internal
custody agent is not a second public identity the outside agent must manage.

**What must be true.**

1. **R1:** Create from a selected branch head or default's loaded program. Capture
   the immutable basis once. Loaded-code creation also supplies cluster/configuration
   facts: a bare publication commit is not sufficient test custody.
2. **R2:** A candidate is a Datahike branch plus a retained handle in its cluster's
   JVM. It runs only the assigned task. Inherited agents, open turns and schedules
   neither recover nor run accidentally.
3. **R2:** The handle carries the connection, SCI context, environment, executor
   and context state. An agent's live/isolated mode determines its branch custody;
   it does not rebind connections independently. Another branch stays unchanged.
4. **R2:** Stop owned work and observe actual exit before releasing its handle.
   Archive any internal custody agent, then unlink through the registry. Preserve
   source, run and history evidence for their required retention lifetime.

**How it works.** Datahike `branch!` shares immutable indexes and updates a pointer
and roster (`reference-code/datahike/src/datahike/versioning.cljc:212`); it does not
construct a cluster. Compose `registry/branch!` (`src/seon/cluster/registry.clj:193`),
`store/open-branch!` (`src/seon/cluster/store.clj:539`), `sci.core/fork`
(`reference-code/sci/src/sci/core.cljc:345`) and B2 arming.
`graph-definition` (`src/seon/cluster/agent.clj:538`) consumes agent id and handle.
Extend the existing MCP branch operation (`script/seon/dev/mcp.clj:548`), retaining
custody in its context state. Retirement uses `registry/retire-branch!`
(`src/seon/cluster/registry.clj:330`), never a per-merge store sweep.

B3 trigger records a task; start owns assignment and first-turn admission. Agree
its transaction order with B2 arming so the same task cannot execute on both shared
and candidate branches. Use those owners, not a parallel registry or namespace roster.
SCI's copied context alone does not isolate calls through shared JVM roots. B2 must
interpret differing definitions and affected callers, including contract differences;
an uninterpretable affected caller refuses by name. Any first-party namespace may
be proposed when that closure is interpretable. No best-effort JVM fallback certifies it.

Messages use the existing cluster-qualified address and message owner with explicit
branch custody. Prove delivery after fork and during a turn, and history access after
retirement. Do not copy messages to manufacture forwarding. Conversation tasks keep
their ordinary reply done condition and may run shared; they do not require code tests.

**Cost and recovery.** Measure branch, open, context acquisition and arming separately,
then end to end. Work follows the pointer, context differences and one assigned agent,
not full cluster startup. Red work remains available for repair. A deadline does not
prove termination or authorize release. Missing addressing/custody proof blocks that
lifecycle slice; the B2/B3 interface choice is described in §10.

## 3. Stage 2 — Writing code at the REPL

**What the agent sees.** Submit source to a branch. A successful definition becomes
stored program data and is callable there when the reply completes. Undefining
retracts it, subject to surviving callers. The default requires schemas and tests
first; a branch may explicitly configure warnings while experimenting.

**What must be true.**

1. **R3:** Model replies and outside `submit` use the same admission, evaluation,
   settlement and installation path. MCP supplies branch, source and request identity.
2. **R4:** The following checks read effective configuration from the supplied branch
   database at each submission. All ship as `:gate`, each may be set to `:warn`.
3. **R4:** `:gate` refuses before evaluation and changes neither rows nor callable
   roots. `:warn` accepts authored data, reports the failure and never manufactures
   a contract or retains an invalidated old wrapper. Every valid contract stays armed.
4. **R5:** Bind request identity to branch incarnation and frozen source/namespace.
   Identical concurrent retries share a run; different inputs under the same identity
   refuse. A disconnect never silently evaluates the source again.

| Dial | Required entry evidence |
|---|---|
| `:seon.config.eval/schema-present` | Each proposed function has `:malli/schema`. |
| `:seon.config.eval/schema-valid` | The schema compiles and all domain references, attribute keys and predicate/function symbols are qualified. |
| `:seon.config.eval/test-present` | At least one current test reaches each proposed function. |
| `:seon.config.eval/test-first` | That reaching test exists in the captured branch basis before the function change. |

**How it works.** `seon.cluster.agent/submit-source!`
(`src/seon/cluster/agent.clj:607`) enters ordinary evaluation, `settle-batch!`
(`src/seon/turn.clj:3567`) and `install-evaluated-rows!`
(`src/seon/sci/eval.clj:1206`). `my.program/ns-unmap!` uses that custody's ordinary
writer (`src/my/program.clj:548`). Delete the non-settling SCI arm of `eval_clj`
(`script/seon/dev/mcp.clj:482`); B1's `host-eval` is JVM inspection only.
Host inspection does not expose branch-local compiled Vars.

Add the four leaves to `resources/seon/schemas/seon.config.eval.edn`, each
`[:enum {:seon.config/dial true} :warn :gate]`. Defaults belong to
`seon.config/default-decisions` (`src/seon/config.clj:416`); sparse overlays use
`apply!` and `effective` (`src/seon/config.clj:723,752`). `derive-config-forms`
(`src/seon/schema/edn.clj:62`) builds the composites. No MCP flag, agent override or
second settings store substitutes for the branch's effective value.

Malli `schema` (`reference-code/malli/src/malli/core.cljc:2555`) compiles against
the branch registry and supplies compilation errors. Check qualification from
analysis facts, not a second parser. Grammar and built-ins such as `:=>`, `:cat`
and `:int` remain legal. Test-first reads the existing reach index in reverse through
`my.program/tests-reaching` (`src/my/program.clj:159`) and `seon.fn/gate-sets`
(`src/seon/fn.clj:1608`). First prove it records test call
edges to not-yet-defined symbols; missing producer evidence is a defect, not permission
to invent another detector.

Separate stored authored contract data from compilable input at the existing
declaration/projection/install owners. The same derivation must govern initial
installation, fresh acquisition and B4 forks. Prove valid → malformed under `:warn`
removes the old wrapper, remains callable after reacquisition, and repair restores
arming everywhere. Compilation errors in authored input are agent diagnostics;
failures inside valid core execution or recording follow the core fault policy.

**Cost and recovery.** Checks follow proposed forms, schema dependencies and reaching
lookups, never the whole program per submission. Listener installation precedes
`submit-source!`. At `system-run-call` (`src/seon/turn.clj:709`), extend admission if
request-to-run identity is missing; do not add an MCP dedup cache. Reconcile run id
with durable run/closed-turn facts. Closure proves settlement only. Release waits
for actual execution exit; reply success also requires installation and feedback
(Stage 3). A provider-free submission completes through the ordinary run lifecycle;
a continuation requiring a provider refuses by name. Private objects remain in
memory; durable definitions and exact shown text survive according to their owners.

## 4. Stage 3 — Feedback

**What the agent sees.** Each change returns shown text, definition warnings and the
results of tests affected by that change. Before working, the agent asks for the
namespace's ordinary agent context. Missing or misleading context can be reported as
structured feedback to its owner.

**What must be true.**

1. **R6:** Coalesce changed identities once from ordinary program writer reports,
   including direct retractions. Use before/after dependencies and changed tests.
   An installation vector alone is not the change inventory.
2. **R6:** Run the affected set through B4's one selector/runner. Read-only evaluations
   and test-result writes trigger no new request. Unknown selection is explicit;
   an empty set means missing-test warning, never green evidence.
3. **R5:** A successful reply joins actual execution exit, observed installation and
   the automatic test request's terminal evidence. Settled but unconfirmed installation
   or feedback is reported as such. A timeout/disconnect says outcome unknown and
   names the request/run; retry reconciles without replaying effects.
4. **R7:** `my.program/context` returns exactly the inside-agent context under identical
   namespace, immutable database, custody agent, selection inputs and render profile.
   The context read does not mutate assignment or configuration.

**How it works.** After settlement and installation, the agent path calls
`seon.test/run` with changed identities and the captured branch execution value,
as `my.test/check` composes it (`src/my/test.clj:5–26`). MCP only returns the result.
Each selected member runs on B4's isolated branch off that commit; results record
back to the submitting branch. Reuse requires positively valid member evidence.
A red result does not undo an accepted warned definition.

Use the following declared reply information; extend existing owners rather than
create a warning ledger or alternate result service.

| Reply information | Meaning |
|---|---|
| Request/run and branch basis | Identifies what was admitted and what retry must reconcile. |
| Evaluation shown text | The exact ordinary agent presentation, not a second rendering. |
| Definition diagnostics | Subject, check, mode, basis, branch, path when applicable, message and remedy. |
| Installation status | Observed success, failure or explicitly unconfirmed; durable closure alone is insufficient. |
| Test results | Required/selected identities and executed, reused, red, excluded, unfinished or unknown evidence with tested commits/digests. |
| Terminal status | Success only after the joined evidence above; no silence-as-success. |

Declare `:seon.program/definition-warning` and
`:seon.program/definition-refused-error` in the existing program schema family.
Diagnostic members are `:seon.program/subject`, `:seon.program/check`,
`:seon.program/check-mode`, `:seon.program/check-basis`, `:seon.store/branch`,
`:seon.program/check-path` and `:seon.program/check-message`. The refusal extends
`:seon.error/base` and also names operation, expected shape and offending value.
Warning text says “<function>: <check> failed at <path>; accepted on <branch>;
merge blocked until <remedy>.” Refusal says “definition refused”; no proposed row
installs. These are target declarations. File-based `seon.lint/finding`
(`resources/seon/schemas/seon.lint.edn:1–25`) is not this diagnostic.

`my.program/context` composes `seon.cluster.prompt/prompt`
(`src/seon/cluster/prompt.clj:418`) with the internal custody agent and ordinary
prompt request. Include namespace, budget/calibration, selection inputs and render
profile; return the rendered value and basis/contribution evidence. If assignment
must change, its ordinary writer runs before derivation. No handwritten outside
context or new context-version counter. Each outside lane starts here and files an
ordinary task/issue with namespace, basis/contribution inputs, deficiency and requested
context when it must look elsewhere. Route that feedback to the context owner.

**Cost and recovery.** Work is changed closure plus selected bodies/recording and
selected context. Context derivation/render targets <1 s within the total leaf budget.
There is no system-wide test fallback. Preserve declared body bounds; explain broad
selection. Prove disconnect immediately after settlement, installation failure and
direct retraction. Post-settlement core failure follows the ordinary fault route.

## 5. Stage 4 — Gating

**What root sees.** A review can distinguish green, stale, missing and refused evidence.
Accept requires the complete obligation for the proposed combined program. A passing
subset is not a passing merge. Entry warnings cannot weaken this gate.

**What must be true.**

1. **R8:** Every function in the combined program has a well-formed, fully namespaced
   Malli schema and at least one reaching test under the current ruling. Changed
   functions also have test-first basis evidence. Changed-only scope is recommended
   but remains an owner decision (§10); strict entry did not settle it.
2. **R9:** Required tests are all current static reaching tests **union explicit task
   tests**. Include disconnected task tests and declared-long reaching members.
   Missing coverage or unknown dependencies refuse by name; observed reach is diagnostic.
3. **R9:** Every required member has positive executed/reused proof for immutable S,
   its matching execution program and proposal. Missing, red, unfinished, excluded or
   unknown members block acceptance. Refusal is not an empty successful selection.
4. **R9:** Selection with fully valid evidence executes and writes nothing. A changed
   seed alone does not invalidate proof for that exact content and its actual inputs.

**How it works.** O3a fixes `seon.test/select` at its existing B4 evidence owner
(`src/seon/test.clj:576`). Its named request carries merge delta, explicit task
identities, cluster custody and all required eligibility. The graph owner derives
obligations from before/after dependencies: declarations, callers, refs, namespace
bindings and B1's schema contract closure. Keyword mentions are not that closure.
No required member may disappear through eligibility filtering.

Reuse content/input proof, including `:seon.test.member/reach-digest`; delete the
unconditional reached-seed reuse veto and accept's hand-built `uncovered`/`gate-sets`
subset loop in one loadable slice. D1 does not recreate B4's dependency walk.

Run missing eligible work through one `seon.test/run`, with immutable combined
program/database/commit, matching projection/context and an explicit durable result
connection. Resolution and fixtures use S, never implicitly `current-src`. Evidence
may be stored at another authority, but its value must name tested S and the same
proposal; an evidence-bearing database is not silently substituted for S's execution
program. Preserve original tested bases when reusing members.

Validate malformed schemas, final refs, surviving callers and required contracts
through ordinary prepared write admission before tests. Contract/coverage proof can
be reused only by its actual inputs. Inherited missing proof blocks under the current
scope; it does not trigger a whole-suite fallback. Test-first means existence before
definition, not red-before-green history. Repair a warned violation by adding the test
and then resubmitting the function; history proves the basis, not a timestamp stamp.

**Cost and recovery.** Selection follows changed/reached work and member evidence.
Target non-body overhead <1 s; measure admission, selection, bodies, recording and
release separately. Test bodies keep their numeric bounds. Refusal leaves the branch
editable. First prove two reaching tests, one failing, plus a later passing one-test
run still refuses acceptance. Also prove missing task tests, stale evidence, moved H,
modified S, long members, absent evidence and a fully covered read-only success.

## 6. Stage 5 — Merging in the database

**What root sees.** A branch diff shows changed declarations and their source, conflicts,
schema/test warnings and latest test results, including stale or missing evidence.
Root accepts through the gate or sends back a reason. Green never auto-accepts.
Candidate completion alone never closes the shared task.

**What must be true.**

1. **R10:** Freeze basis **B**, candidate **C** and destination head **H**. Derive net
   definition changes by qualified identity and canonical digest, not raw datom replay.
2. **R11:** Root's explicit accept or `my.task/merge!` enters preparation. A conflict
   becomes a task for existing root; send-back leaves the author's branch editable.
3. **R12:** Scratch **S** starts from exactly captured H. Apply the proposal and test
   that combined program. Final acceptance **M** uses the same proposal and H's original
   writer precondition. No untested smaller delta is substituted.
4. **R12:** Retain immutable parent/test lineage and recording authority so run/member
   evidence remains queryable after scratch release and retirement.

**How the diff works.** Compose `my.program/diff` from `program/changed-identities`,
scoped `digest-map` and `three-way` (`src/seon/program.cljc:578,621`). Expose branch
`diff`; it reads, neither tests nor writes. Return absent source sides as well as
replacements. Send-back uses the existing task/message writer, not a review registry.
`prepare-merge!` and `accept-merge!` (`src/seon/cluster/source.clj:736,786`) own acceptance.

Verify ancestry before comparing B/C/H; branch-local transaction numbers do not prove
it. Bind `:seon.program/definition-digest` before the history identity join. Inspect
assertions and retractions in `since(history)`, recover removed identities from
history, deduplicate, then compare basis/final digests before expanding full rows.

| Basis → final | Net change |
|---|---|
| Equal digest | None, including edit/revert or equal delete/recreate. |
| Absent → present | Addition. |
| Present → absent | Retraction. |
| Different present digests | Replacement. |
| Missing required digest or unavailable basis | Typed refusal naming the subject. |

B1's digest covers exact source, qualified identity, normalized resolver context and
effective acquisition/test metadata. Alias-only changes participate. Do not create a
source-only digest or hand list of source attributes. Keep pure comparison in `program`;
settle its database-read owner/require graph before adding a `db` dependency. Convert
other changed-definition derivations only when namespace/file/input semantics match;
retain B1's missed-publication fallback until that conversion is complete.

Normalize complete values with the indexer's identity/ref/component owner
(`src/seon/fn.clj:3289,3330`). `canonical-row` selects owned fields; it does not normalize
numeric refs. Use complete declared selectors, lookup refs, tempids for new identified
rows and nested owned components. No branch-local numeric eid or silently truncated
1,000-member pull crosses branches. Additions upsert, replacements replace exactly,
deletions retract with surviving-ref validation. Test colliding eids and >1,000 refs.
Declaration replacement excludes recorder rows: test runs/members remain program
evidence at B4's authority, not declarations to replay. Turns, messages, tasks, errors
and evaluation results are not merged as program changes.

**How acceptance works.**

1. Capture B/C/H and H's pre-application basis-t. Materialized values have bounded
   ownership and release on every exit.
2. Both branches changing one identity away from B to different final digests is a
   conflict, including deletion. Equal final content is not. Compose B3 trigger/start
   in one shared transaction for existing root. `seon.id` derives structural conflict
   identity from subject, competing content and basis. Retain both commits and a
   resolvable subject-local detector. Identical repeats are idempotent; changed content
   is distinct work. Root repair uses the same gate.
3. Fork S from captured H, not a reread mutable head. Apply normalized proposal in
   one ordinary prepared transaction, with compiled schemas and final-report validation.
   Stage 4 computes and proves the complete required set on S.
4. Accept the immutable proposal only with the tested H fence and bound H/C/S evidence.
   Final shared acceptance settles the shared task. Parent references are immutable
   commit ids, never mutable branch keywords.
5. If H moved, refuse before mutation. Rebuild S from the new H, recompute conflicts
   and obligations, then execute/reuse only evidence valid for the new combined state.
   Stay within the original request deadline; no endless retry or partial acceptance.
6. Record the outcome through the existing system evaluation/history owner for the
   candidate's next turn. Release scratch custody; retire only after retention proof.

The maintained Datahike fork's `merge!` arg-map accepts the expected basis
(`reference-code/datahike/src/datahike/versioning.cljc:738`). Its `merge-writer!`
delegates to `transact!` before adding parents (`reference-code/datahike/src/datahike/writing.cljc:864,883`).
Verify Seon's prepared final-report validator reaches that same operation; preserve
public API, callback, stale-basis, refusal and parent-lineage regressions. This is a
fork seam, not a claim about upstream. Do not rebuild its guard. Guarded transaction
alone is a smaller content-acceptance alternative only if the owner gives up required
multi-parent lineage.

M retains H plus immutable C and tested S as parents, or B4's equivalent durable
result lineage retains S. If results record elsewhere, retain the tested S reference
and actual run/member authority explicitly. Query that evidence after scratch
retirement. Datahike GC follows parents subject to its cutoff
(`reference-code/datahike/src/datahike/gc.cljc:22–81`); ancestry is not eternal retention.
Agree and prove the existing lifetime before automatic cleanup. No result replay or
new acceptance entity is required.

**Cost and recovery.** Diff work follows changed identities and displayed sources;
validation follows the proposal and its required closure. Measure visited history
work with fixed delta and growing history before claiming O(delta). No whole-store
collection per merge. Scratch preserves candidate repair state; rebasing the mutable
candidate would need an unnecessary pause protocol. Refusal retains the candidate,
accepted evidence and explicit reason. A moved head always requires fresh combined proof.

## 7. Stage 6 — Converging the running JVM

**What root sees.** Database acceptance, published files, loaded code, armed contracts
and browser paint are separate observations. A merge is not proof that the JVM runs it.
Convergence follows export when accepted rows must first reach files; this stage states
the boundary that Stage 7 must hold during integration.

**What must be true.**

1. **R13:** B1's evaluation/adoption boundary excludes conflicting reloads and dependent
   evaluations through **load → arm → record**. A database head guard cannot serialize
   Var mutations.
2. **R13:** A failed load or arm leaves affected execution unavailable until exact
   bytes, callable behavior and arming converge. An unchanged adoption record is not health.
3. **R13:** Prove interleaved adoptions, partial namespace reload failure, a replaced
   Var's wrapper and an old branch's indirect call before claiming convergence.

**How it works.** Compose B1's existing adoption owner in `src/seon/cluster.clj` and
`src/seon/cluster/source.clj` with B2's context installer
(`src/seon/sci/eval.clj:1206`). B1 §2a′ owns the exact boundary. Keep ordinary
`require :reload`; do not add a loader, token service or replay queue. Reload the
changed declarations' required namespace closure according to B1's declaration rules.
The Clojure `require` seam (`reference-code/clojure/src/clj/clojure/core.clj:6219–6226`)
does not itself make load/arm/record a transaction.

Until that boundary is proven, the orchestrator alone integrates. Lanes never
self-adopt. A dirty required namespace/schema dependency delays only that integration;
hooks remain lint-only and whole-file holds remain. Shell edits do not publish.

**Cost and recovery.** Target work proportional to changed declarations and required
reload/arming closure. Measure load, arm and record separately within leaf export
latency. Recovery proves actual convergence before reopening execution; it cannot
restore health by keeping an old record or by `git revert` of JVM effects. Keep the
host REPL reachable for diagnosis. No lane resets or restarts default to prove this.

## 8. Stage 7 — Writing back to the files

**What root sees.** After explicit acceptance and complete proof, the source owner
writes the accepted bytes and commits them. There is no second approval step and no
push. First ship existing interpreted function/test replacements with unambiguous
file/span provenance; full export also covers additions, deletions, schemas and new
namespaces once their prerequisites pass.

**What must be true.**

1. **R14:** Export consumes the complete accepted definition delta at M. Function-only
   `overrides` is a post-publication check, not the export inventory.
2. **R14:** Capture all touched original bytes at the expected source base and stage
   the complete desired set outside live paths. Hold whole files through integration.
3. **R14:** B1 reanalysis proves accepted digests, exact source bytes, intended absences,
   unchanged neighbors, valid callers and no unexpected declarations before installation.
   B4 proves the proposed callable actually loads and runs.
4. **R15:** Digest-fence every install and retain recovery inputs before the first move.
   Partial installation and process restart keep affected execution unavailable.
5. **R16:** Host-bound declarations, schema resources, new namespaces without destination
   rules and non-Clojure files use captured file staging on the named branch and the
   same gate. They never edit live files early or bypass required platform proof.

**How staging works.** Resolve provenance through existing declaration/history reads
(`src/seon/effect.clj:278–306`); an agent replacement may omit current file/span facts.
New declarations supply destination and insertion position. Ambiguous namespace-to-file
mapping refuses with identity and candidate paths.

Read each touched file once. Group edits against original bytes, splice in descending
half-open UTF-8 span order, remove deleted forms and place additions at explicit
positions. New-file absence uses `:my.fs/precondition`. Reuse the edit and filesystem
owners (`src/seon/edit.clj:229,254–316`; `src/seon/edit/jvm.clj:95–122`;
`src/seon/fs/jvm.clj:593–712`; `src/my/fs.clj:57`). Preserve stale-source and symlink
refusals. No git worktree and no per-identity live checkout edits.

Analyze the captured staged file set through B1's ordinary file publication path,
including namespace aliases/refers/imports/macros, reader, dependency and configuration
inputs. Compare canonical definition digests and source bytes separately. A synthetic
prelude passed to `analyze-forms` is not an equivalent file oracle. Complete merge
evidence and staged round trip precede any install. B4's existing isolated host proves
callables that need it; typed SCI-unloadable fallback to old JVM code is not proof of
the proposal. There is no D1 test runner.

**How integration and recovery work.** Before moving any file, retain M, expected
source base and the complete original/desired set at the existing source/Git staging
authority. Close reload admission for the complete set, then install through B1's
source owner with digest checks. After each file, reconcile:

| Actual bytes or state | Required action |
|---|---|
| Desired bytes | Already installed; do not write again. |
| Original bytes | Pending; install only against that expected digest. |
| Any other bytes | Refuse for root repair; preserve unrelated edits. |
| Partial set installed | Keep reload closed and resume the same accepted export. |
| Full set installed, no commit | Verify the entire intended set, then commit only its paths, naming task and M. |
| Commit exists, convergence incomplete | Verify its identity/bytes and resume publication/adoption; no duplicate commit. |
| Restart with pending export | Reconcile before loading/admitting affected execution; missing reconstruction evidence means unavailable. |

`seon.cluster.boot` and `seon.cluster.source` own restart admission. Never boot from
mixed files or replay definition effects to reconstruct progress. Use existing staging,
Git and publication state, not a D1 journal or second completion registry. A stale
expected digest is a refusal, not “unchanged.” Preserve accepted database definitions
and produce root's conflict task naming the affected file/identity. Never reset or
restore unrelated work. Retain evidence or a resumable commit before cleaning staging.

Publish/adopt the complete set under Stage 6's boundary. Verify installed bytes,
publication, arming, callable behavior and browser paint separately. Branch green
alone does not prove export. Prove caught I/O failure in process, interruption after
the first of two installs, and restart reconciliation under separately authorized
platform proof. Reprice B1's seam before implementation if existing bootstrap and
convergence admission cannot meet O4b's cap.

File-origin program changes publish from captured staging to the named branch, not
default, then enter Stages 4–5. Non-Clojure inputs participate in B4's declared input
evidence. Host-bound proof uses B4's existing platform host before integration or stays
deferred; post-adoption tests cannot undo classes or effects. Schema changes prove
incremental declaration adoption on a live branch, including refusal while retired
attributes still have writers/referrers. From-zero boot/reset is not their proof.

**Cost and recovery.** Work follows touched bytes/declarations and selected obligations.
Analysis targets <1 s within end-to-end leaf export overhead <1 s; phase targets do not
replace the total. Ordinary export does not add a platform tier per change. File-only
host-bound work retains its separately required platform proof and numeric bounds.

## 9. Build order

O3a is the first implementation slice once B4's file holder releases it. O0 is a
runtime verification dependency. O1a/O1b preparation can proceed on free files.
Runtime proof follows health → O0 → O1a/b/d and O2a/c → proven O3 acceptance →
O1c/O2b activation → narrow O4 → the two-branch demonstration. Never activate warned
definitions or retire fixed definition gating before acceptance is proven.

Each row is at most about 100 added source lines, not a net budget or permission to
hide machinery elsewhere. Split preparation from an atomic producer/consumer conversion
when needed; report the smaller dependency composition before exceeding the cap.

| Order / slice | Work and prerequisite | Required proof | Deletion; added-source cap |
|---|---|---|---|
| 1 — O3a | B4 complete content-valid acceptance selection, bound H/C/S; extend existing guard only if its proof fails. | Failing subset refuses; disconnected task/long members included; full valid proof selects with no execution/write; stale H/modified S refuse. Preserve fork API/callback/lineage classes. | Reached-seed reuse veto and accept subset loop; ≤100. |
| 2 — O0 | Verify/adopt B1 holder's `:as-alias` analyzer correction; no competing edit. | Commit and branch acquisition evidence; false load edge absent. | Holder removes false edge; no new D1 code. |
| 3 — O1a | Branch create/list/unlink, basis and retained custody with cluster facts; agree B3 start/B2 arming and addressing. | One armed task only; scoped effect/fault; shared page unchanged; message/history/recovery/exit proof. | Bare-branch custody plumbing; ≤80. |
| 4 — O1b | Branch submission and listener/run reconciliation at the existing admission writer. | Persistence, identical retry, mismatched-input refusal, disconnect, no-provider completion and release after exit. | Non-settling SCI evaluator; ≤100. |
| 5 — O1d | Namespace context composition and feedback through B2/B1/task owners. | Exact inside/outside equality; read does not mutate; deficiency reaches owner. | Outside-only context assembly/instructions; ≤80. |
| 6 — O2a | Retain acquisition by unchanged head and changed closure. | Old/new callable and contract isolation, including indirect JVM caller and reload elsewhere; no whole-program work per call. | Repeated full-program digest maps; ≤60. |
| 7 — O2c | Install changed roots/callers from supplied batch/report. | Actual changed body runs; direct retraction counted; installation checked without store scan. | `installation-covers-program-change?` scan; ≤60. |
| 8 — O3b | Root diff, accept/send-back, conflict task and retained evidence; depends on O3a and execution proof. | Conflict repeat idempotent; new content distinct; retained run query after scratch retirement; root repair uses same gate. | Raw JVM-only acceptance interface/duplicate checks; ≤80. |
| 9 — O1c | Four dials, declared diagnostics, warned contract derivation and automatic feedback; only after O3 acceptance proof. | Strict refusal unchanged; warn survives fresh acquisition/test fork; valid→invalid→repair wrappers; terminal feedback join; TDD undefined-symbol edge. | Fixed definition-time test gate with all callers/schemas; ≤100, split declaration preparation if needed. |
| 10 — O2b | Settlement from batch rows/report, after O3. | Unrelated state unchanged; writer conflict-basis check retained at exact replacement. | Superseded pre-install candidate gate work; ≤60. |
| 11 — O4a | Captured replacement staging and B1 canonical round trip. | Two splices, multi-file resolver/import/macro change, neighbors, exact bytes/digests and proposed callable. | Function-only override export inventory; ≤100. |
| 12 — O4b | File-set recovery, path-limited commit and exclusive convergence, including boot/source restart admission. | Each interrupted install/commit/adoption state; separately authorized new-process reconciliation; interleaved/partial reload refuses execution. | Independent lane adoption; retain digest checks/exclusion; ≤100. |
| 13 — Demonstration | Two isolated branches complete edit → feedback → review → merge → export → reload → call. | Completion record below; named gaps remain failed proof. | No new mechanism. |
| 14 — O5 | Wider export and file-origin branch publication, destination rules, schema and platform prerequisites. | Add/delete/schema/new-namespace round trip; incremental schema adoption; required platform proof. | Shared-candidate save publication for lanes; split per declaration owner, each ≤100. |
| With interfaces — O6/O7 | Branch diffs replace program-only ledger rows; tool/REPL guidance describes installed behavior. | Incoming guidance matches actual interfaces; whole-file holds remain. | Obsolete agent-tool/non-settling instructions; docs only. |

Before the demonstration, query current inherited contract/coverage readiness and assign
missing prerequisites to existing namespace-agent work. Required new tests are not
optional wider export: prove branch admission and acceptance before claiming TDD repair.
If narrow O4 cannot persist test additions, demonstrate an already-covered replacement
and explicitly state that the full TDD repair loop remains unproven. O3a may prove its
predicate on canonical fixtures while a real merge honestly refuses. No hidden
inherited exemption or whole-suite fallback. Task renaming and nonessential profiling
expansion wait until the narrow loop works.

**Proof and completion record.** Use canonical database fixtures, real SCI, armed valid
contracts and B4's one run authority. Default test bounds are five seconds; any longer
numeric declaration has measured work and a reason. Preserve tests for surviving writer
conflicts, admission and candidate isolation; delete only tests of retired mechanisms.
The landing note `docs/prds/agent-platform/landing/lane-d1.md` records:

1. Two real task identities, initial defect queries, one schema guarantee and regression,
   one missing reaching test, exact evaluation source, agents, fork commits and projections.
2. Concurrent task-only execution, candidate calls and old/new contracts, unchanged shared
   definitions before accept, a candidate-only failing body through SCI and an indirect
   JVM caller, then repaired green. Include shared schema/contract/caller interaction
   without same-identity conflict.
3. Exact required tests, digests, tested commits, executed/reused/excluded/unfinished
   verdicts and actual termination; malformed admission before tests; strict and warned
   entry/merge behavior; missing/disconnected task tests and unknown coverage.
4. Disjoint acceptances, moved H after green with zero partial acceptance, new combined
   proof, actual parent graph and retained run retrieval; structural conflict and root repair.
5. Both deltas' original/staged file digests, exact source and all identity digests,
   intended deletion/unchanged neighbors, Git commit, publication/adoption, scoped
   overrides check, schema/test round trip and interruption recovery. Scope-limited
   milestones label any deferred addition/deletion proof explicitly.
6. Trigger → task → agent → message → reply, after-fork/during-turn delivery, history
   after retirement, observed page, host REPL reachability, cleanup and remaining unknowns.

Record executable exact forms and complete results, not future API pseudocode.
Verify loaded behavior and adoption separately from browser paint. Implementation
probes use explicit custody and one evaluation in flight on owned branches; never
restart default. Focused requests stay within the cut, platform/affected integration
at its completion under the orchestrator. A schema gap calls for incremental adoption,
not a reset. Any separately necessary recovery preserves evidence first and is not
proof of intermediate loadability.

For R17, report start, projection, validation, selection, bodies, recording, acceptance,
export and cleanup independently and end to end, with allocations/retained memory and
visited-work counts. Every operation over one second names its cause, proportionality
and armed functions, with a directive to fix unjustified overhead. Over ten seconds
needs explicit authorization and an issue even when justified. Record net src/test
lines and disjoint additions/deletions; historical stretch caps are not measured proof.
Hot-path changes carry the same parent/current probe; regressions over 20% or 50 ms
are defects. Publication slices retain the committed measurement script's clock row.

Stop only the dependent slice for a held file, missing interface, unproven callable or
retention guarantee, or genuine owner decision. Name the boundary and concrete options.
Preserve unrelated files and sessions; do not operate another lane to pass a gate.

## 10. Open decisions for the owner

**Merge contract/coverage scope is the one open policy decision.** These are engineering
estimates, not runtime measurements. Every option still requires all affected/task tests
and test-first evidence for changed functions; no entry dial can bypass the merge gate.

| Option, recommendation first | Guarantee | Cost and what it gives up |
|---|---|---|
| **Changed functions only — recommended** | Changed functions have valid fully namespaced contracts and ≥1 reaching test; root sees untouched inherited gaps. | About half a day for scoped O3a predicate/regressions; gives up whole inherited-population compliance. |
| **Every function in the combined program — current rule** | The entire combined population meets contract/coverage policy. | Current census plus a multi-day namespace-agent repair campaign as gaps require; narrow merges wait for inherited gaps. |
| **Per-cluster scope dial** | Unconditional gate within a declared changed-only or whole-program scope. | About one day for configuration and both-policy regressions, plus repairs where whole-program applies; adds policy and gives up one uniform guarantee. Do not implement before ruling. |

**Addressing is a B2/B3 interface proof, not permission to copy messages.** Prefer
existing cluster-qualified candidate addressing with branch custody: it preserves one
message authority and requires scoped delivery/history proof. Shared-only addressing
needs additional cross-branch custody/read-evidence integration, buying a simpler
sender address at that cost. Message copying is rejected because it creates a second
authority. Settle the interface before task-only startup; escalate only a genuinely
new product tradeoff.

The other choices are settled: branch interface with internal custody, orchestrator
as root, explicit accept/send-back, write-back and path-limited commit after complete
proof without another approval, no push, and host-bound export deferred from the first
loop. No merge-critical roster, approval counter, file exception, automatic settlement
merge or separate loader is authorized.
