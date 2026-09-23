---
type: plan
status: implementation specification; proof gates explicit
created: 2026-09-21
lane: D1
depends-on: [A1, A2, B1, B2, B3, B4]
tags: [agent-platform, isolation, merge, write-back, datahike-branch, sci-fork]
---

# Lane D1 — candidate isolation, explicit merge and write-back

Implementation ownership: net-definition comparison in `src/seon/program.cljc`,
merge/write-back composition in `src/seon/cluster/source.clj`, candidate request
facts in `resources/seon/schemas/seon.cluster.edn`, and regressions in
`test/seon/program_test.clj` / `test/seon/cluster/merge_test.clj`. B1 owns canonical
identity, source analysis/publication and MCP repairs; B2 owns acquisition,
arming/wake/turn lifecycle; B3 owns task writers and remaining previously unowned
code; B4 owns the one execution authority. Shared edits land as paired slices.
This is a specification with dated measurements; no fresh runtime proof is claimed.

## 0. For the owner: compose the isolation and writers we already have

Agents currently edit one shared program graph. Opening-time comparison tries to
prevent conflicting writes, and definition-time candidate evaluation runs tests
before an agent can keep an experimental definition (`turn.clj:1162-1180,
1301-1315,3253`; `sci/eval.clj:3170-3400`). That puts the acceptance gate inside
ordinary thinking. Accepted database definitions also lack a complete reverse
path to source files. Three separate changed-definition derivations repeat work.

Datahike branches share immutable indexes; SCI contexts fork with copy-on-write
Vars; the database writer already checks a supplied basis atomically; the program
owner already replaces declarations by identity; and the edit owner already
splices source under digest preconditions. Compose these mechanisms. Do not
confuse their individual guarantees: a cheap branch is not complete cluster
startup, a distinct SCI env is not isolation from shared JVM roots, and an
immutable test database does not prove which callable actually ran.

A candidate executes only its assigned task on a cluster forked from one immutable
shared commit. An explicit merge request freezes candidate, basis and shared
commits, derives net definition changes, and validates the proposed combined
program on a scratch branch. B4 executes every required test against that exact
program, preserving its evidence. The shared writer accepts only if the tested
shared head is still current. Write-back stages the complete accepted file delta
in an isolated checkout, verifies it with B1's file analyzer and callable proof,
then integrates and publishes through the existing source owner.

## 1. Goals, costs and evidence boundary

Historical 2026-09-21 evidence at HEAD `209a6652a` is retained in §4. It does not
measure the new candidate lifecycle. Full startup includes recovery decisions,
configuration, context construction and graph admission; measure all of them.

| Operation | Historical evidence | Required result |
|---|---|---|
| Materialize immutable commit | 1.857583 ms | Release every materialized value; record complete candidate start separately |
| SCI fork | 0.021875 ms; distinct env | Prove retained old/new callable and contract isolation, including indirect JVM calls |
| Speculative one-datom transaction | 0.339875 ms, two datoms | Actual scratch and final writer validation, not speculative commit identity |
| Source-attribute history sketch | 0 identities in 21.441292 / 20.675792 ms; 10,500 in 1506.152958 ms | Net definition digest projection; measure examined work with fixed delta and growing retained history |
| Static gate for `seon.id/id` | 1,838 of 2,157 tests in 130.576958 ms | Required static reaching tests union explicit task tests; no promised reduction to tens |
| Function span census | 4,600 of 4,600 | Does not establish file-ref completeness or provenance after an agent replacement |
| Write-back | Zero observed overrides | Complete file/source/digest/callable round trip; no implementation proof yet |

Measure start, projection, validation, selection, execution, acceptance and export
separately. Price tests by selected work and declaration bounds. A slow history
join may inspect retained history rather than only returned rows; no O(delta)
claim is accepted without an index/visited-work probe. Suspicious costs require
algorithmic explanation; the specification does not authorize an operation over
the owner's time bound by calling it a gate.

## 2. Data flow

### 2a. Candidate custody and lifecycle

| Data/event | Existing owner and invariant | Work |
|---|---|---|
| Trigger | B3 `trigger-call` records the task; it does not launch an agent | Task identity lookup and transaction |
| Assignment/start | B3 `start-call` owns assignment and first-turn admission; coordinate B2 arming so the same work does not execute on shared and candidate branches | Assigned task and agent, not every inherited agent |
| Fork basis | Registry records the exact immutable shared commit on the candidate start request/row; required for candidates, not invented on ordinary roots | Branch head/roster plus recorded basis |
| Candidate environment | **Ruled 2026-09-21 (README §7 "Candidate shape"): a candidate is a branch plus a HANDLE hosted by its cluster's JVM, not a cluster; a cluster stays the unit of a shared program and agent population, and candidates merge back to its branch.** `graph-definition` (`cluster/agent.clj:469`) is a pure function of `(agent-id, handle)`, and the handle already carries `:seon.db/connection`, `:seon.sci.eval/ctx`, `:seon.env/environment`, `:seon.flow/executor` and `:seon.agent/context-state` (the `default` handle's keys, review note §5.1). A candidate handle = `registry/branch!` off the immutable commit (0.34 s, landed) + `open-branch!` + `(sci/fork base-ctx)` (0.007 ms) + `arm!`; faults commit on the fault's own scoped connection (`env/carry` stamps the environment on every proc). Cluster start additionally stands store, source base, config, recovery, prepl, web, search, error fanout and graphs (`cluster.clj:3187-3276`): measured fork 9,924 ms + start 15,725 ms per candidate; the primitives measured live on 2026-09-21: branch 74.65 ms, open 25.62 ms, SCI fork 0.021 ms (Codex's REPL verification note) | A pointer, a fork, one `arm!`; the composed proof still owed: on a scratch cluster, branch, build the handle, arm one agent, evaluate one form with one effect and one fault; facts, effect rows and faults only on the candidate branch, the shared page unchanged, recovery after a stop |
| Work | Ordinary evaluations and B4 requests use the candidate's own connection/context | Actual evaluations and selected tests |
| Address | Existing cluster-qualified address and message owner, with the shared task identifying the candidate | Branch-local message/wake/reply facts |
| Refusal | Keep candidate and its evidence available for repair | No discard on red |
| Acceptance/discard | Stop and release via existing lifecycle owners; retire only after source, run and history retention is proven | No whole-store collection on every merge; maintenance owns GC |

A branch inherits agents, open turns, schedules and profile facts. Ordinary boot
currently recovers turns and arms agents (`cluster.clj:2297-2341,3114-3119`;
`cluster/agent.clj:900-943`), so passing a different source commit alone is not
task-only isolation. Before the lifecycle slice lands, agree the B3 start/B2
arming transaction order and prove unrelated inherited work neither recovers nor
runs. Use current task facts at those owners; no parallel registry, namespace
roster or agent-level connection rebinding. Candidate first-turn admission is an
explicit request/lifecycle contract, not an accidental inherited open turn.

A candidate's environment retains its connection. Messages arriving after the
fork must reach that cluster's owner, and reply/history access must remain
explicit through retirement. Shared-only inbox forwarding would require a
separate cross-branch custody/read-evidence proof; copying messages is not the
design. Conversation tasks use the same task/turn owners with their reply done
condition and can run on shared without a candidate; do not require a detector
or test merely because implementation tasks have them.

### 2b. One net-definition projection

Inputs are an immutable basis commit B and a descendant database value C.
Verify ancestry; branch-local transaction numbers alone do not identify a common
basis. Bind B1's `:seon.program/definition-digest` attribute before the historical
identity join. Select assertions and retractions from `since(history)`, resolve
removed identities through history, deduplicate, and compare each identity's
basis/final digest before reading complete rows.

| Basis/final state | Result |
|---|---|
| Equal digest | Unchanged, including edit/revert or equal delete/recreate |
| Absent then present | Addition |
| Present then absent | Retraction |
| Different present digests | Replacement |
| Surviving row without required digest or unavailable basis | Named typed refusal |

B1 supplies the digest from exact source, qualified identity, normalized resolver
context and effective acquisition/test metadata. Resolver-only changes therefore
participate; namespace bindings and input obligations remain B1/B4 evidence.
No second source-only digest or literal list of source attributes appears here.

A declaration projection returns definitions, not test run/member facts. Preserve
B4 evidence by commit and run identity; do not replay recorder data as declaration
replacement. Reuse this projection for missed-publication or per-test-basis
selection only when their full namespace/file/input semantics match. Retain B1's
missed-publication fallback until that conversion is complete.

Normalize complete declaration values through the indexer's existing identity,
reference and component owner (`fn.clj:2992-3045`). `canonical-row` only selects
owned fields; it does not normalize numeric refs. No raw branch-local eid or
wildcard pull with a silent 1,000-member cut crosses branches. Use complete
declared selectors, lookup refs for existing targets, tempids for new identified
rows and owned nested components. Additions upsert, replacements use exact
replacement, deletions retract; final surviving callers still govern deletion.
Keep pure comparison in `program`; explicitly settle its database-read owner and
require graph before adding a `db` alias or copying pseudocode.

### 2c. Explicit merge through the tested head

Only explicit `my.task/merge!` requests acceptance. Green candidate settlement is
evidence, not shared acceptance or permission to auto-merge. A candidate-local
completion does not resolve the shared task; final shared acceptance does.

1. Capture fork basis **B**, candidate head **C**, and shared head **H** as
   immutable commits. Retain H's pre-application basis-t. Materialized values
   have bounded ownership and release on every exit.
2. Derive net candidate/shared changes relative to B. Conflict means both changed
   one identity away from B to different final digests, including deletion.
   Equal final content is not a conflict. Compose B3 trigger/start in one shared
   transaction to assign the existing root. Its structural conflict identity
   derives through `seon.id` from the subject, competing definitions and basis;
   store both source commits and a resolvable subject-local detector. The same
   conflict repeats idempotently; changed competing content is distinct work.
3. Fork scratch from immutable H. Apply the normalized proposal in one transaction
   through Seon's ordinary prepared write admission. Reuse schema compilation,
   materialization and final-report validation. Before tests, refuse malformed
   schemas, invalid final refs, unresolved surviving callers and incomplete merge
   contracts by identity. An untested working definition is allowed in the
   candidate; uncontracted is not synonymous with untested.
4. Derive obligations over the combined program and before/after dependencies:
   changed declarations, callers, references, namespace bindings and B1's schema
   contract closure. Keyword mentions are not that closure. Required tests are
   **current static reaching test identities union the task's explicit tests**.
   Each admitted function needs a reaching test; unknown dependency or missing
   coverage refuses by name. Observed reach remains diagnostic.
5. Call B4's one `seon.test/run` with named policy and those exact identities.
   Supply the immutable combined program/database/commit, matching projection
   and acquired context, and a separate explicit durable result connection.
   Fixtures and resolution must use the combined value, never implicitly
   `current-src`. Every required member must have positive executed/reused proof;
   excluded, unknown, missing, red or unfinished work refuses acceptance. Reuse
   retains the original tested basis and meets B4's complete evidence predicate.
6. Retain the exact tested scratch commit, run identity and recording authority.
   Acceptance uses the same prepared write admission as scratch, the immutable
   proposal and H's original basis-t. It settles the shared task only against
   accepted evidence. The Datahike merge request carries immutable parent commits,
   never mutable branch keywords.
7. If H moved, the writer refuses before mutation. Rebuild scratch from the new H,
   recompute conflicts/obligations and execute or reuse evidence valid for that
   new combined state within the existing request deadline. Never apply a smaller
   untested delta or retry forever.
8. Record the outcome for the candidate's next turn through the existing system
   evaluation/history owner. Release scratch connections and materialized values;
   retire storage only when accepted evidence remains queryable. Refusal preserves
   the working candidate. Root's conflict repair uses this same explicit gate.

**One writer guard and validator.** Current Datahike `merge!` does not expose an
expected-basis argument and its writer calls `core/with` directly
(`versioning.cljc:734-748`; `writing.cljc:860-889`). Extend the existing public
merge request and route it through the existing transaction operation before
adding parents. Carry Seon's prepared final-report validator too; direct
Datahike merge does not install it. Include public API, callback, stale-basis,
refusal and parent-lineage regressions in the maintained-fork slice. This is not
a copied four-line guard. Ordinary guarded `transact!` already suffices for atomic
content acceptance if the owner explicitly gives up multi-parent lineage.

**Evidence retention is a gate before cleanup.** Name the exact parent graph and
recording authority in the implementation contract: accepted shared M has prior
shared H plus immutable candidate C and tested scratch S as parents, or retains
S through B4's equivalent existing durable result lineage. If B4 records elsewhere,
retain its tested S reference and actual run/member authority explicitly. Query
run/member facts at that authority after scratch release/retirement; definition
replacement does not copy them. Datahike GC follows parents subject to its cutoff,
so ancestry is not eternal retention. Agree the existing retention lifetime and
verify it before automatic retirement; no new acceptance entity or result replay.

### 2d. Write-back of the accepted delta

Write-back consumes accepted definition changes at M: functions, tests, schemas,
new namespaces, additions and deletions. `overrides` is a useful function-scoped
post-publication check, not the export inventory (`program.cljc:43-77`).

1. Resolve original file/span provenance through existing declaration/history
   reads (`effect.clj:278-306`). An agent replacement may omit current file/span
   facts. A genuinely new declaration supplies destination and insertion position;
   ambiguous namespace-to-file mapping refuses with identity and candidate paths.
2. The source owner captures staging bytes outside live paths at the expected source commit.
   Read each touched file once; group edits against original bytes; apply existing
   lossless splices in descending half-open UTF-8 span order. Deletions remove old
   forms, additions have actual positions, and new-file absence is expressed
   under `:my.fs/precondition`. Stage the complete file set before analysis/loading.
   Keep whole-file holds through integration; use captured staging, never a git worktree.
3. Run B1's ordinary file publication analysis with captured staged bytes and its
   namespace/alias/refer/import/macro, reader, dependency and configuration context.
   Compare canonical definition digests for every accepted identity; verify intended
   absences, unchanged neighbors, valid callers and no unexpected declarations.
   Compare exact stored source bytes separately. `analyze-forms` with a synthetic
   prelude is not an equivalent file oracle.
4. Require complete merge evidence, staged round trip and proof the proposed
   callable loads and executes. A typed SCI-unloadable JVM fallback cannot certify
   the changed body. Use B4's existing isolated execution host for that proof,
   retaining its one admission/resolution/recording authority; no D1 runner.
5. Integrate the complete staged change through B1's source owner only against
   the expected checkout state; verify all installed bytes, then commit path-limited,
   naming task and M. Publish/adopt the complete set under the existing evaluation/adoption
   boundary. Close reload admission during installation; reconcile each installed file
   as specified in §2e before reopening it. A failed load leaves affected execution
   unavailable until load, arming and recorded convergence are proven.
6. Verify publication completion, actual loaded behavior and browser paint
   separately. No automatic push. Remove staging through its lifecycle owner only
   after retaining required evidence or a resumable commit.

Recovery derives desired staged bytes, current bytes, source publication and Git
commit state. Desired bytes already present require no write; unexpected bytes
refuse. A stale expected digest is not an unchanged result. Name the existing
commit awaiting integration/adoption and resume that operation without a second
completion registry. Refusal preserves accepted database definitions and produces
root's conflict task naming the affected file/identity. No `git reset` or restore
of unrelated edits.

### 2e. Outside agents take the same path

Owner ruling, 2026-09-23 (`a29faf5a6`, `38863a0c8`, `215c32d9a`, `5ec049cfd`): “stick with our git and datahike terms”; “make it so it's configurable”; “I want you to act as root checking the diffs”.

**Compose what exists.** A branch holds program rows; the ordinary agent path evaluates,
settles and installs them. Put MCP at that entrance and compose the existing diff,
test and merge owners. No MCP-only evaluator, persistence, loader or agent tool.
This is a target, not installed proof. The dated
[outside-agents evidence](../../../research/agent-platform/outside-agents-process-2026-09-23.md)
and [review, items 1–3 and 11](../../../research/agent-platform/astra-plan-review-2026-09-23.md)
supply the defects and measurements; the optional `agent-code-writing-api-2026-09-23.md`
is absent from this checkout. Current `script/seon/dev/mcp.clj:509-527` retains a
named branch's context, correcting the earlier per-call-release observation, but still
calls `evaluate` without settlement. Context retention is not program persistence.

**The interface is branches.** Every MCP tool call names its branch, including status
and JVM inspection (the latter observes the host, not branch-local compiled Vars).
Extend the existing `branch` tool (`script/seon/dev/mcp.clj:548-605,839`): create from
an explicitly selected branch head or default's loaded program, list branches, delete
(unlink). Capture the immutable base once. Creating from loaded code must also carry
the cluster's configuration/custody facts: a bare publication commit lacks the cluster
row and cannot run tests (dated evidence, break B5). No cluster start or program copy.
If custody requires an agent row, create it behind the branch name, retain its handle
in the existing context state, and archive it when the branch is unlinked. Release the
handle only after owned work terminates; unlink uses `registry/retire-branch!`, not GC.

The branch REPL (`submit`, branch + source + request identity) calls
`seon.cluster.agent/submit-source!` (`src/seon/cluster/agent.clj:606`), the same entrance
as a model reply: `sci.eval/evaluate` → `settle-batch!` → `install-evaluated-rows!`
(`src/seon/sci/eval.clj:3192,1218`; `src/seon/turn.clj:4970`). A definition becomes a
program row and is callable on that branch when the reply completes; another branch
is unchanged. `my.program/ns-unmap!` (`src/my/program.clj:555`) retracts through the
same custody and refuses surviving referrers. Delete the non-persisting SCI arm of
`eval_clj` (`script/seon/dev/mcp.clj:482-528`); B1's `host-eval` is JVM inspection only.

O1 installs the listener **before** submission, reconciles the returned run id against
its durable run/closed-turn facts, and returns each evaluation's shown text, warnings
and test results. A disconnect or deadline without terminal evidence says **outcome
unknown**, names the request/run, and does not release execution custody. Retrying the
same admitted request identity resolves that run instead of evaluating source again;
land that admission at the existing run writer if absent, not in an MCP dedup cache.
Observe actual termination before release/unlink. A provider-free submission must
finish through ordinary run completion; a continuation that needs a provider refuses
by name. Prove these lifecycle semantics, including disconnect after admission, before
calling the door complete; a fifty-line estimate is not that proof.

**Start from the namespace context.** Add one REPL function `my.program/context` taking
the branch's execution value and namespace, returning exactly the context a Seon agent
working there receives. Compose `seon.context`, the existing prompt selection owner and
`seon.render.ns/render-ai` (`src/seon/context.clj:1-42`;
`src/seon/render/ns.clj:795`); no outside-agent context builder. Every outside lane starts
there and reports missing, unhelpful or wrong context when it must look elsewhere.
Declare that feedback on the ordinary task/issue value, naming the namespace, branch
commit and context version/selection inputs, with the observed deficiency and requested
context. Route it to the context owner through the existing task writer so tuning has
queryable evidence. O1d owns this B2 composition and B1 REPL exposure; its result is
rendered context, not a stored live evaluation result.

**Entry policy is data; merge policy is unconditional.** The config-schema search found
no matching check dial: `resources/seon/schemas/seon.config.eval.edn:1` declares the
deadline and `seon.config.test.edn:1` the auto-check count. O1c adds these four leaves to
`seon.config.eval.edn`, each `[:enum {:seon.config/dial true} :warn :gate]`, with an
explicit shipped default `:warn` through `seon.config/default-decisions`
(`src/seon/config.clj:378-434`):

| dial | check at the shared definition entrance |
|---|---|
| `:seon.config.eval/schema-present` | Every proposed function has `:malli/schema` |
| `:seon.config.eval/schema-valid` | Its schema is well formed and fully namespaced |
| `:seon.config.eval/test-present` | At least one current test reaches each proposed function |
| `:seon.config.eval/test-first` | That test exists in the captured branch basis before the function change (test-first/TDD) |

Read the effective cluster row from the branch's supplied database at each submission,
then carry that value through admission; model replies use the same check. A branch
applies its sparse overlay to its inherited config row through `seon.config/apply!`
and reads through `effective` (`src/seon/config.clj:725,754`); no second settings store,
agent override or MCP flag. `derive-config-forms` (`src/seon/schema/edn.clj:62`) builds
the composites. The acquisition point is this entrance, not graph arming.

Declare `:seon.program/definition-warning` and `:seon.program/definition-refused-error`
in the existing program schema family in O1c. Each flat value names
`:seon.program/subject` (qualified function symbol), `:seon.program/check` (one of the
four dial keys), `:seon.program/check-mode`, `:seon.program/check-basis` (commit id), `:seon.store/branch`,
`:seon.program/check-path` (schema path when applicable), and
`:seon.program/check-message`. Warning text: “<function>: <check> failed at <path>;
accepted on <branch>; merge blocked until <remedy>.” The refusal extends
`:seon.error/base`, uses the same diagnostic members, and names operation, expected
shape and offending value; text says “definition refused” and no proposed row installs.
These are new declared shapes, not claims of installed keys. Reuse the same diagnostics
for REPL, root's diff and merge. Existing `seon.lint/finding` requires a file and models
clj-kondo findings (`resources/seon/schemas/seon.lint.edn:1-25`), so it is not this value.

`:warn` accepts the definition, preserving even an invalid schema as source data;
it must not manufacture a valid contract or try to arm the malformed form. `:gate`
refuses that definition before its body/effects or program write; other definitions
remain available for repair. This needs the declaration producer and installer changed
together, not merely a caught compiler exception. Malli's `schema` compiles the supplied
form against the branch registry (`reference-code/malli/src/malli/core.cljc:2555`,
HEAD pin `8725a8cb`); reuse its errors. Fully namespaced means domain schema references,
map attribute keys and predicate/function symbols resolve to qualified identities;
Malli grammar and built-ins such as `:=>`, `:cat`, `:int` remain legal. Check declaration
facts at analysis, not with a second schema parser. Entry checking costs the proposed
forms, their schema dependencies and reaching-test lookup, never the whole program.

**Each change gets feedback.** After the ordinary agent path settles and installs a
changed batch, it calls B4's `seon.test/run` with that batch's changed identities and
captured branch execution value, as `my.test/check` already composes it
(`src/my/test.clj:5-26`; `src/my/program.clj:162`). This trigger lives in the agent path,
so inside and outside submissions both receive it; MCP only returns the answer.
Use before/after dependencies for deletions and changed edges, include changed tests,
and coalesce identities within the settled batch. Read-only evaluations and test-result
writes do not trigger it. Run the reaching set, never a system-wide fallback; unknown
selection is explicit. Each member executes on B4's isolated branch off that captured
commit; results are recorded back to the submitting branch. Red does not undo a warned
change. An empty set is a missing-test warning, not green evidence. Cost is changed
closure plus selected bodies/recording; reuse only positively valid member evidence.

**Root reviews, then accepts.** The orchestrator acts as root. Add one read composition,
`my.program/diff`, over `program/changed-identities` and `program/three-way`
(`src/seon/program.cljc:578,621`), exposed as branch `diff`; no review registry. It
compares base B, branch C and destination H and returns changed declaration identities,
per-declaration source diffs (including absent sides), conflicts, current schema/test
warnings and latest reaching-test results with their tested commits/digests. Stale or
missing results are labelled. Work follows changed identities and displayed sources;
it neither tests nor writes. Root's branch `accept` calls the existing
`prepare-merge!` / `accept-merge!` (`src/seon/cluster/source.clj:741,791`); `send-back`
uses the existing task/message writer to deliver a reason to the author and leaves
the branch editable. Write-back invokes §2d only for the accepted, fully tested delta.

O3 begins with **acceptance correctness**, before additions/deletions. Capture immutable
H/C/B; prepare forks exactly H into S, applies the proposal and derives **every current
reaching test plus explicit task tests** for the combined program. B4's evidence owner
compares this complete required set to positive current member evidence bound to H/C/S
and the proposal. A caller-selected green subset is insufficient. The required set is found by the existing affected-tests selection, never new code (owner: "don't reinvent the wheel we already have efficient code for finding the test diffs … I mean the affected tests"): accept calls `seon.test/select` (`src/seon/test.clj:554`) read-only on the tested commit with the merge delta as `:seon.test/changed`, and requires nothing left to run and no exclusions; the hand-built `uncovered`/`gate-sets` loop in `accept-merge!` is deleted. Every function in the
combined program has a well-formed, fully namespaced Malli schema and at least one test;
all affected/task tests pass, and test-first obligations have basis evidence for the
changed functions, regardless of the entry dials. Reuse unchanged contract/coverage
proof by its actual inputs; missing inherited proof blocks merge, never becomes a
whole-suite run. TDD here proves test existence before the changed definition, not a
red-before-green history. A warned test-first violation is repaired by adding the test
then resubmitting the function; history supplies the basis, not a new timestamp stamp.
First regression: two required tests reach one replacement, one fails, a separate
one-test run passes → accept refuses. Also prove missing task tests, stale evidence,
moved H and modified S refuse. The writer still fences H as in §2c.

Branch acceptance is not **exclusive JVM convergence**. B1's existing evaluation/adoption
boundary excludes conflicting reloads and dependent evaluations through load → arm →
record. A database expected-head guard cannot serialize Var mutations. If that boundary
is unavailable, only the orchestrator integrates; affected execution remains unavailable
after a failed reload until exact bytes, callable behavior and arming converge. An old
record is not health. Prove interleaved adoptions, partial reload failure and an old
branch's indirect call (review item 2); keep ordinary `require :reload`, no new loader.

**Narrow write-back first.** O4 initially exports only interpreted function/test
replacements with unambiguous existing file/span provenance. §2d's wider additions,
deletions and declaration kinds follow after the two-branch loop. Hold each destination
file through integration. Capture and stage bytes outside live source paths (no git
worktree); run B1 analysis and B4 callable proof before any install. Close reload
admission while installing the complete set; digest-check every file. After each file,
recovery compares original, desired and actual bytes: desired means installed, original
means pending, anything else refuses for root repair. Partial installation keeps reload
closed; resume that same accepted delta, never reload the mixed set. Before a commit,
verify the entire intended set; after a commit, reconcile its identity and bytes and
resume publication/convergence without a duplicate commit. Commit path-limited, never
push. File bytes, publication, arming, callable behavior and browser paint are separate
observations. Green branch tests alone do not prove successful export.

Host-bound declarations, schema resources, new namespaces without destination rules,
and non-Clojure files stay on the file path with **whole-file holds**. Their captured
staging publishes to the named branch, not default; program changes join the same §2c
obligations, non-Clojure inputs join B4's declared input evidence. No early live-file
edit or independent lane adoption. Host-bound proof uses B4's existing platform host
before integration or is deferred; post-adoption tests and `git revert` cannot undo JVM
classes/effects. A dirty required namespace/schema dependency defers only that
integration. Keep digest verification and lint-only hooks during this transition.

**Costs belong to slices.** Historical timings below are not fresh measurements or
justifications for slowness. Measure end-to-end leaf submission/export, closure sizes,
allocations and retained memory; separately sub-second phases do not make their sum
sub-second. The leaf overhead target is <1 s; selected bodies retain their declared
bounds, so a broad reaching set has no unconditional sub-second promise.

| slice | measured defect → target work and time | existing owner; deletion / added-src budget |
|---|---|---|
| O0 | False `:as-alias` require edge refuses acquisition; fix producer, prove actual acquisition | B1 analysis (`src/seon/fn.clj:258`); delete false dependency, ≤10 |
| O1a | Branch create/list/unlink, retained custody and loaded-code base with cluster facts | D1 §2a / B2 acquisition; replace bare-branch custody plumbing, ≤80 |
| O1b | Branch-named submission with listener/run reconciliation, idempotent retry and terminal release | B1 §3b R3/R9 + B2 run writer; delete non-settling SCI evaluator, ≤100 |
| O1c | Four dials and declared diagnostics at the shared entrance; warn/install semantics and automatic changed-batch test reply | D1 slice 6 + B2 settlement + B4 run; replace fixed definition gate, ≤100; activates only after O3 |
| O1d | Namespace context and declared quality feedback, proportional to selected context | B2 context/render + B1 REPL + existing task writer; delete outside-only context assembly/instructions, ≤80 |
| O2a | Acquisition 1.3–1.7 s: two whole-program digest maps plus reverse closure → unchanged-head compare and changed closure, <1 s | B2 §2a retained acquisition; delete per-call full-program derivation, ≤60 |
| O2b | Settle 0.7–0.8 s → batch rows/report only, <1 s and reduce combined leaf latency | B2 settlement / D1 slice 6; delete pre-install candidate gate work after O3, ≤60 |
| O2c | Install 0.7 s → changed roots/affected callers; install-check 5.6 s over store → supplied batch identities/report, <1 s combined | B2 install / B4 execution custody; delete `installation-covers-program-change?` store scan (`src/seon/sci/eval.clj:1169`), ≤60 |
| O3a | Complete H/C/S required-set evidence; first failing-subset regression above | D1 slice 5 + B4 evidence owner; delete accept's green-subset predicate, ≤100 |
| O3b | Root diff, named accept/send-back; current whole-program first reach index (≈0.7 s) reused by input, changed-delta review <1 s excluding bodies | D1 slice 5 + B1 bridge; delete owner-only raw JVM acceptance and duplicate evidence checks, ≤80 |
| O4a | Captured replacement staging and canonical round trip, proportional to touched bytes/declarations | D1 slice 7 + B1 publication; replace function-only override export inventory, ≤100 |
| O4b | Interrupted file-set recovery, path-limited commit, exclusive load/arm/record; leaf export overhead <1 s target, measure each phase | D1 slice 7 + B1 §2a′; delete independent lane adoption, not digest checks or convergence exclusion, ≤100 |
| O5 | Later wider/file-origin branch publication with schema destinations and platform proof | D1 slices 5/7 + B1 §2a′ S6 + B4 platform; delete shared-candidate save publication for lanes; split by declaration owner, each ≤100 |
| O6/O7 | Branch diff replaces program-only ledger rows; file holds remain; tool/REPL guidance follows installed behavior | D1 coordination + B1 R8; delete obsolete agent-tool/non-settling instructions; docs only |

Budgets are added lines, not net estimates or permission to hide machinery in another
slice; recount deletions before implementation. **Critical path:** writer/REPL health →
O0 → O1a/b/d and O2 → O3 acceptance correctness → O1c activation → narrow O4 → two isolated
branches edit, automatically test, review, merge, export, reload and call their changes.
Do not retire the current definition gate before explicit acceptance is safe. O1c/O2b
preparation waits for the same-file owner; additions/deletions, task renaming and
nonessential profiling expansion wait for the demonstrated loop. O6/O7 ship with the
interfaces they describe; no claimed source or runtime change from this specification.

**Authority reconciliation and decisions.** `AGENTS.md:258-259` still says “Filesystem
lanes (Codex, Claude) index into one shared candidate branch of default and are live
agents there”. This contradicts the owner's per-branch ruling. The orchestrator replaces
those two lines exactly with: “gate. Filesystem lanes (Codex, Claude) work through the
shared agent REPL entrance on their own named branches; root reviews and explicitly
accepts through the merge gate before write-back to files. Seon agents doing every
update is the goal.” It also reconciles README §7's shared-candidate/guards-only wording
and B1 §3b's old SCI evaluator row; this lane does not edit those authorities beyond
the permitted cross-reference lines. The warn policy is an explicit branch-experiment
exception to the current every-function-armed wording, never permission to merge an
uncontracted function.

- **O-a ruled:** branch interface; any agent row is internal custody, created/retired
  with the branch. No outside-agent identity tool.
- **O-b ruled:** the orchestrator acts as root, reviews the diff and accepts or sends
  back a reason; green never auto-accepts.
- **O-c ruled for this path:** after explicit acceptance and all required proof, §2d
  writes the accepted bytes and commits path-limited. No second human approval step.
- **O-d wider host-bound export remains open:** (1, recommended) defer it beyond the
  interpreted replacement loop; smallest cost, gives up initial full-language editing;
  (2) integrate B4's existing isolated platform proof before export, broader guarantee
  at measured process cost; (3) keep host-bound work orchestrator-only under that proof,
  same guarantee but no autonomous host-bound editing. Adopt-then-test is not a safe
  rollback alternative. Process work over ten seconds still needs owner authorization.

## 3. Reading list

| Source | Guarantee and limitation |
|---|---|
| `reference-code/datahike/src/datahike/versioning.cljc:212-321,457-490` | Branch/roster, active-connection deletion refusal, immutable materialization and release |
| `reference-code/datahike/src/datahike/versioning.cljc:734-748`; `writer.cljc:421-437`; `writing.cljc:860-889` | Merge supplies lineage, caller supplies content; only transaction operation currently owns expected-basis guard |
| `reference-code/datahike/src/datahike/gc.cljc:22-81` | Parent traversal and cutoff; lineage alone is not permanent retention |
| `reference-code/datahike/src/datahike/db.cljc:149,180-185,678`; `pull_api.cljc:16,315,323` | Temporal filters and many-pull limit; returned rows do not establish work visited/completeness |
| `reference-code/sci/src/sci/core.cljc:260-276,345-350`; `impl/utils.cljc:362-379` | Fork env and copy-on-write inherited Vars; does not isolate shared JVM roots |
| `src/seon/program.cljc:43-77,134-177,211-273,848-886,973-1047`; `src/seon/fn.clj:2992-3045` | Ownership projection, replacement and separate reference normalization |
| `src/seon/db.clj:4050-4121,4290-4319`; `reference-code/datahike/src/datahike/db/transaction.cljc:1206-1226` | Prepared final-report validation and dependency callback |
| `src/seon/cluster/registry.clj:178-282,327-358`; `src/seon/cluster.clj:1660,3216,3286`; `src/seon/env.clj:330-370` | Exact fork/start input and scoped environment; startup must select intended work |
| `src/seon/turn.clj:1162-1180,1301-1315,3253-3330`; `src/seon/sci/eval.clj:3170-3400` | Existing definition-time gate; retain writer conflict checking through retirement |
| `src/seon/fn.clj:207,600-680,951-1014,1176-1213,1520,1779`; `src/seon/fn/analyzer.clj:657-698` | Spans, agent provenance differences, synthetic prelude and graph selection |
| `src/seon/edit.clj:242,254-316,344`; `src/seon/edit/jvm.clj:95-122`; `src/seon/fs/jvm.clj:593-712`; `src/my/fs.clj:57` | Lossless splice, stale-source refusal, nested preconditions and per-file atomic write |
| `src/seon/effect.clj:278-306`; `test/seon/test_support.clj:946-981`; `src/seon/test.clj:576-598` | Existing provenance lookup, bounded branch lifetime and execution seam B4 replaces in place |

## 4. Exact historical probe and implementation proof protocol

The following read-only JVM evaluation ran on `default`, root
`/Users/sean/src/seon`, with a 10,000 ms MCP timeout on 2026-09-21. It changes only
immutable speculative values and a fresh local SCI fork. An earlier six-position
history pattern failed with `Pattern mismatch` and supplied no measurements.
The corrected complete form and value follow; no fresh probe ran for this plan.

```clojure
(let [conn (seon.operator/connection "default") db (seon.db/db conn) t (seon.db/basis-t db) h (datahike.api/history db) timed (fn [f] (let [s (System/nanoTime) v (f)] [v (/ (- (System/nanoTime) s) 1e6)])) attrs [:seon.fn/source :seon.fn/spec :seon.fn/calls :seon.fn/references :seon.test/source :seon.ns/source :seon.schema/form] ids seon.program/identity-attributes instance (get @seon.operator.runtime/running-instances "default") ctx (get-in instance [:seon.turn.loop/cluster :seon.sci.eval/ctx]) cid (seon.cluster.registry/connection-branch-commit-id conn :current-src) material (timed #(datahike.api/commit-as-db conn cid)) src (first material)] (try {:head-t t :commit (datahike.api/commit-id db) :src-commit cid :src-t (:max-tx src) :commit-as-db-ms (second material) :fns (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym]] db) :spanned (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym] [?e :seon.fn/form-span]] db) :overrides (count (seon.program/overrides db)) :cluster-row (datahike.api/pull db [:seon.cluster/name :seon.source/commit-id] [:seon.cluster/name "default"]) :since (mapv (fn [n] [n (timed #(count (datahike.api/q '[:find ?ia ?iv :in $delta $history [?a ...] [?ia ...] :where [$delta ?e ?a _ _ _] [$history ?e ?ia ?iv]] (datahike.api/since h (- t n)) h attrs ids)))]) [1 10 100]) :with (timed #(let [r (datahike.api/with db [[:db/add [:seon.fn/sym 'seon.id/id] :seon.fn/doc "d1 read-only speculative probe"]])] {:datoms (count (:tx-data r)) :commit (datahike.api/commit-id (:db-after r))})) :fork (if ctx (timed #(let [f (sci.core/fork ctx)] {:different-env (not (identical? (:env f) (:env ctx)))})) :ctx-absent) :tests (datahike.api/q '[:find (count ?e) . :where [?e :seon.test/sym]] db) :reach (datahike.api/q '[:find (count ?e) . :where [?e :seon.test/reach]] db) :gate (timed #(count (seon.fn/gate-sets {:seon.db/db db :seon.fn/seeds #{'seon.id/id}})))} (finally (datahike.api/release-materialized-db src))))
```

Returned value (MCP JSON decoded to EDN; envelope `ms 1693`, `windowed? false`, cluster alive):

```clojure
{:fns 4600 :since [[1 [0 21.441292]] [10 [0 20.675792]] [100 [10500 1506.152958]]] :overrides 0 :src-commit "6ab0a852-4511-5f0e-8462-dcb611714979" :src-t 536870957 :commit-as-db-ms 1.857583 :tests 2157 :head-t 536870949 :reach nil :cluster-row {:seon.cluster/name "default" :seon.source/commit-id "6ab0a0b4-8927-5986-aedc-a3d20f36a036"} :with [{:commit "6ab15cfb-6177-50d4-9ccd-91ef300b2084" :datoms 2} 0.339875] :commit "6ab15cfb-6177-50d4-9ccd-91ef300b2084" :gate [1838 130.576958] :spanned 4600 :fork [{:different-env true} 0.021875]}
```

The speculative with result retains the parent's commit ID; it is not a committed candidate. The fork measurement proves a distinct env atom, not complete cluster isolation. Reach nil is absent recorded evidence, not a complete empty observation. Zero overrides supplies no write-back proof.

During implementation, record executable exact forms as each request contract
lands; do not paste future APIs with ellipses or unbound handles as proof. Use the
owner's fresh implementation baseline and canonical branch/context fixtures,
explicit MCP custody and one evaluation in flight. Mutating candidate scenarios
run on owned disposable resources, never by restarting `default`. The acceptance
scenarios in §8 are required outputs of those forms, not assertions that they
already pass. After each source slice, verify host `runtime_status`, the loaded
changed behavior and its adoption record; observe the candidate agent page.

## 5. Ordered implementation slices

| Slice | Change, prerequisites and proof |
|---|---|
| 1 | Agree B3 task/start/addressing, B2 task-only arming/recovery, B4 actual execution program/recording authority and evidence retention. Prove candidate-only failing body, indirect JVM call and unrelated inherited work before retiring any gate |
| 2 | Land B1 digest and complete-row normalization consumers, net projection and ancestry checks. Convert compatible publication/B4 derivations atomically; retain unmatched missed-publication semantics |
| 3 | Land candidate lifecycle and its required basis/request facts with all writers/readers. Mark incompatible persisted changes RESET NEEDED, batched with B1/B3/B4 by orchestrator |
| 4 | Maintained Datahike public merge request delegates guard/transaction work and retains validation callback; fork tests, push fork and bump gitlink before dependent Seon deletion |
| 5 | Compose explicit merge, full combined validation, B4 named obligations, immutable parent/evidence lineage, root conflicts and stale-head reconstruction. Prove red/unknown/unfinished never accepts |
| 6 | Retire definition-time gate and superseded helpers with every caller/schema/test in the same slice. Preserve B2's writer-owned conflict-basis check at exact replacement and any unsuperseded candidate evaluation |
| 7 | Land accepted-delta export, isolated complete-file analysis, callable proof, controlled integration and resumable adoption; round-trip regressions land together |
| 8 | Run §8 demonstration and record size/performance, REPL and browser evidence |

Before each commit, prove the touched require graph loads in the authorized
implementation harness; then use the running host's reload/adoption owner and
verify a debug read/ordinary turn. Graph topology changes use existing lifecycle
completion. No lane stops/resets/restarts `default`. Recovery reset is the
orchestrator's `bin/seon reset --force` (ruled 2026-09-23, README §7 "Schema change and reset": `reset --force` unlinks the cluster branch and forks a fresh one from the program rows, keeping every cache; `start --head` moves the JVM to committed HEAD keeping the store; `nuke --force` alone deletes the store, for a truly broken store), after preserving needed evidence: it
loses disposable database turns/results/tasks and private/result objects, rather
than “nothing durable.” Reset recovery is not proof of intermediate loadability.

## 6. Smaller mechanisms to probe first

| Candidate | Probe and decision |
|---|---|
| Guarded transaction vs multi-parent merge | Ordinary transaction proves atomic content acceptance; retain the fork API change only for required lineage, delegating its existing guard and validator |
| Net digest comparison before row expansion | Equal edit/revert, alias-only change and fixed delta under growing history; measure visited rows and complete normalization cost |
| One captured file set through analysis/loading | Multi-file refer/import/macro change, two splices in one file, unchanged neighbors and staged callable invocation; keep B1's analyzer rather than inventing a text-only oracle |
| Candidate rebasing instead of scratch | Would require pausing mutable candidate work and changes its repair state on refusal; use scratch unless existing lifecycle supplies the same isolation without a new pause protocol |

Numeric datom replay is not a simplification: two branches can allocate equal
numeric ids for different entities. Include that falsification in normalization
regressions; identity/ref/component conversion stays at its existing owner.

## 7. Tests and provisional size target

Preserve class coverage on canonical fixtures, real SCI and armed contracts:
net changes (alias-only, edit/revert, delete/recreate, >1,000 refs); task-only
startup; candidate callable isolation; combined schema/caller interactions;
missing/disconnected task tests; malformed/red/unfinished refusal; stale head;
conflict idempotence; retained run evidence; multi-file round trip and interrupted
export. Include symbolic ref normalization with colliding branch-local ids.

Delete tests only for retired definition-time gate behavior and superseded
changed-definition derivations; keep writer conflict, admission and candidate
isolation regressions at their surviving owners. B4's one `run` executes affected
members with default five-second bounds or a declared numeric allowance; no D1
runner or suite. The isolated hosting exception uses that same B4 authority.

Historical rough deletion inventory: approximately 300 source lines in the
turn/SCI definition-time gate, 30 in the source history arm, 25 in B4's duplicate
projection and 400 test lines. These spans overlap dependency cuts and must be
recounted after them; all retained behavior remains priced. Complete export,
lifecycle and evidence handling still need a disjoint addition ledger before
any net reduction is claimed.

Provisional target: replacement Seon source ≤355 lines, schema ≤40, maintained
fork API/guard composition ≤40, regression additions ≤300 test lines. These are
stretch caps, not measured floors or guarantees. Reconcile exact disjoint spans
with B1/B2/B4, charge moves once, and record `wc -l` plus per-commit additions and
deletions. If missing guarantees exceed the cap, report the existing owner that
can absorb work before adding machinery or claiming the target met.

## 8. Completion, decisions and stop rules

The landing note `docs/prds/agent-platform/landing/lane-d1.md` must retain:

1. Two actual task identities, one schema guarantee with reproducing example and
   regression, one function missing a reaching test; initial defect queries,
   evaluation source, agents, fork commits and carried projections.
2. Concurrent candidates with positive facts proving only assigned work ran,
   shared definitions unchanged before explicit acceptance and candidate calls
   observing candidate definitions. Include old/new contracts and reload elsewhere.
3. Exact required tests and executed/reused/excluded/unfinished verdicts, digests,
   tested commits and termination evidence. A candidate-only failing body must
   fail through both SCI and an indirect JVM caller before its repaired green.
   Include a shared schema/contract/caller change without same-identity conflict.
4. Disjoint explicit acceptances, then a shared-head move after green: typed
   stale-basis, zero partial acceptance and fresh combined proof before retry.
   Record actual parents and retrieve the run after scratch retirement.
5. Same-identity conflict routed once to existing root with both sources and B;
   identical repeat is idempotent, changed digest is not suppressed. Root's repair
   passes the conflict task's tests through the same explicit gate.
6. Malformed admission refuses before any gate execution; a separately untested
   candidate definition works locally but refuses by name at merge.
7. Both accepted deltas exported: original/staged file digests, exact source bytes,
   all affected identity digests, intended deletions, path-limited Git commit,
   publication/adoption commits, scoped `overrides` check and schema/test round trip.
   Include two edits in one file, new/deleted declaration, resolver change, stale
   source, symlink refusal and interrupted commit/integration/adoption recovery.
8. Trigger→task→agent→message→reply for conversation and candidate addressing,
   including a message after fork and during a turn and history after retirement.
   Observe page content; record startup/discard and each phase's cost, cleanup,
   host REPL reachability and remaining unknowns as failed proof obligations.

**Settled constraints:** explicit merge; all first-party namespaces may be
proposed; conservative static reach plus task tests; structural conflict task for
root; moved shared head requires a new combined proof. No merge-critical roster,
ten-merge approval counter, file exception or automatic settlement merge.

**Write-back policy remains an owner choice.** Recommend automatic path-limited
commit after explicit acceptance, complete required evidence, isolated file
analysis and callable proof (cost: changed files plus selected work; gives up
per-change human review). Alternatives are that same proof plus the platform
tier per export (more execution, broader boot proof) or plus human approval
(owner latency, human inspection). None substitutes old-root fallback green for
execution of the proposal.

**Addressability recommendation:** use the existing cluster-qualified candidate
address (branch-local custody, explicit cluster selection). Shared-only addressing
requires the existing message owner to prove cross-branch custody/read evidence
(additional integration work, simpler sender address); copying messages is rejected
because it gives up one authority. B2/B3 must settle that interface before startup.

Stop only the dependent slice at a held file, missing owner interface, unproven
candidate/combined-callable/retention guarantee, or genuine owner decision. Name
the exact boundary and three concrete options when needed. Preserve all unrelated
edits; do not operate another lane's session to pass a gate.
