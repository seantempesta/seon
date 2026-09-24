---
type: plan
status: revised 2026-09-24 for owner ruling option 3
created: 2026-09-21
lane: D1
depends-on: [A1, A2, B1, B2, B3, B4]
tags: [agent-platform, isolation, merge, write-back, datahike-branch]
---

# Lane D1 — branch, edit, test, review, merge and write back

## 0. Purpose

Seon already has branch, submission, test and source owners. Compose them around
immutable captured inputs; D1 supplies the reviewed merge and export, not another harness.

**Ruled model and interim (owner 2026-09-24).** [Publication option 3](../../../research/agent-platform/publication-under-concurrent-lanes-2026-09-24.md#exactly-three-priced-options)
(`b8fae5bf8`) is the target: Claude/Codex filesystem edits become program rows on their
named lane branch, combined candidates are tested on an intermediate branch, named root
acceptance is checked at the writer, and accepted bytes reach default through staged
write-back and fail-closed convergence. Option 1 is **in force now**: hook publication
is paused, lanes retain whole-file holds and commit their owned paths, and only root
integrates coherent checkpoints. This is an operational constraint, not proof that all
execution is automatically excluded. Default publication costs **0 ms per lane save**;
branch feedback and accepted integration have separate costs. Only root publishes the
accepted bytes into default; failed convergence leaves affected execution unavailable
until rows, loaded behavior, armed contracts and the record agree. JVM effects are not
rolled back. Option 2's content comparison is used at integration, not as a per-save
reconciler of everyone's unfinished checkout.

**The path.** Start from the namespace context and edit a named branch. Each submission
gets validation and test feedback. Root reviews its diff and requests acceptance or
sends it back. Preparation captures the original base **B**, candidate head **C** and
destination head **H**. It builds intermediate branch **S** from H plus the proposed
changes, keeping conflicts there for repair. The mandatory gate tests that combined
program. Named root/owner acceptance installs exactly the tested proposal if H is still
current, producing accepted commit **M**. The source owner stages and verifies M's file
changes, installs and commits the complete set, then finishes rows → load → arm → record
before affected execution resumes. Exclusion begins before accepted destination rows
change and spans export/convergence; M alone is not readiness. No second approval or push follows.

This is a target, not installed proof. The [implementation contracts and historical evidence](../../../research/agent-platform/d1-evidence-2026-09-23.md#implementation-contracts)
contain exact data shapes, source navigation and detailed proof records; the explicitly
normative contracts there are part of this plan except where this revision supersedes
their ordering, checkpoint lifecycle or slice accounting. Fenced historical archives are not.
Owner rulings `a29faf5a6`, `38863a0c8`, `215c32d9a`, `5ec049cfd`, `eb709fcb8` and review
folds `7523dd510`, `6f5ec198a` govern this account. D1 composes the work; B1 owns analysis,
publication and MCP, B2 execution/context, B3 tasks/configuration, B4 tests/evidence.

**Claude/Codex day to day — five lines (activate branch steps as §9 proves them).**

1. Take whole-file ownership and a named lane branch; use the shared REPL with explicit root/cluster/branch.
2. Submit captured filesystem edits to that branch; until available, commit held files for root's checkpoint.
3. Read branch-local diagnostics and request focused `seon.test/run` feedback when the owner load rule permits.
4. Send B/C/H, the diff and evidence to root; repair conflicts on S and request named acceptance, never self-adopt.
5. Let accepted staged write-back update default; await convergence, then release holds and retire only after exit.

## 1. Requirements summary

Each requirement is proved by its stage checklist, including the normative detail.

| ID | Required result | Stage |
|---|---|---|
| R1 | Named branch create/list/unlink retains its original immutable base. | 1 |
| R2 | Only assigned work runs; effects/faults stay on its branch; release waits for exit. | 1 |
| R3 | Inside and outside submissions persist through the same entrance. | 2 |
| R4 | Four entry checks default to `:gate`; `:warn` never leaves a stale contract wrapper. | 2 |
| R5 | One request has one run; success proves exit, installation and feedback completion. | 2–3 |
| R6 | Each changed batch, including direct retractions, gets its affected tests. | 3 |
| R7 | Namespace context matches inside-agent context; deficiencies reach its owner. | 1, 3 |
| R8 | Merge always checks contracts, coverage and test-first; scope remains explicit. | 5 |
| R9 | Every required reaching/task test has valid evidence for the exact combined program. | 5 |
| R10 | Compare canonical definition digests; transfer stable identities and complete refs. | 4 |
| R11 | Root explicitly accepts; conflicts retain disjoint work on S and create root tasks. | 4–5 |
| R12 | Acceptance checks tested H, proposal and lineage; evidence survives cleanup. | 5 |
| R13 | Rows → load → arm → record is exclusive; failed convergence leaves execution unavailable. | 7 |
| R14 | Export proves captured bytes, canonical analysis and actual callable behavior. | 6 |
| R15 | Partial installs/restarts resume the same export; commit only its paths, never push. | 6 |
| R16 | File-only changes join the same gate with their required platform proof. | 6 |
| R17 | Measure time, work and memory; reuse valid evidence; target <1 s ordinary overhead. | All |

“Custody” means the connection and context supplied to an execution. “Arming” means
installing contract checks. “Reaching tests” are tests connected to a function through
current call/reference dependencies. “Convergence” means loaded code and armed contracts
match accepted files. Ordinary overhead excludes selected test bodies, which retain
their declared bounds; separately fast phases do not excuse a slow total submission.
A definition digest identifies its source, resolved names and semantic metadata; equal
digests mean equal definitions for comparison, not automatically equal test evidence.

## 2. Stage 1 — Branch and namespace context

**What the agent sees.** Create a named branch from selected code, ask for the namespace
context, work there, list branches, then unlink when finished. A branch is a Datahike
pointer sharing immutable indexes, not a program copy or a new cluster to start.

**Requirements and proof — R1, R2, R7.**

1. Record the exact selected head or default loaded-program commit as the candidate's
   original base in its existing start/request facts. Retain it for diff, merge and
   recovery. After reopening its context it still names B even if the parent advanced. Ordinary
   root clusters do not acquire this candidate-only requirement.
2. Loaded-code creation also supplies cluster/configuration facts needed for tests.
   Retain one handle in the cluster's JVM; run only its assigned task, never inherited
   agents, open turns or schedules. Prove scoped effects/faults and an unchanged shared page.
3. Every MCP call names its branch, including status and host inspection. Any custody
   agent stays internal; host inspection does not claim branch-local compiled Vars.
   Release waits for actual exit; archive the internal agent, then unlink, retaining evidence.
4. Start with `my.program/context`: use the internal agent, namespace, immutable database,
   selection inputs (budget/calibration) and render profile. Prove equality with the
   inside-agent prompt for identical inputs. Assignment changes use their ordinary writer
   before this read; the read itself does not mutate anything.

**Mechanism.** Compose `registry/branch!` (`src/seon/cluster/registry.clj:193`),
`store/open-branch!` (`src/seon/cluster/store.clj:539`), SCI fork and B2 arming.
B3 start owns assignment/first-turn admission; agree its order with B2 so work cannot
run on both branches. Context delegates to `seon.cluster.prompt/prompt`
(`src/seon/cluster/prompt.clj:418`), returning rendered text and its basis/contributions.
No alternate evaluator, agent registry, outside-only context or version counter.

Checkpoint and attempt ownership comes from [one harness §§1–2, S2/S3/S4](../../../research/agent-platform/one-test-harness/design-2026-09-24.md#9c-ordered-cuts-gross-additions-and-proof-costs)
and [language §4b](../../../research/agent-platform/decoration-language-v0-2026-09-24.md#4b-execution-model-checkpoints-owner-2026-09-24-corrected-by-review-82d87392b).
S2 supplies a clean base plus branch differences, retractions and affected callers;
S4 owns attempt custody/effects/exit. D1 adds no checkpoint cache or attempt constructor.
A published database/SCI checkpoint is never evaluated into again; keep a branch head
for GC retention. Each attempt/proof forks it with fresh connections, recorders and
private mutable state. A successor checkpoint is a new fork; checkpoint changes discard
scratch, while durable program rows survive. Cache opening form plans, not evaluated
values; share validators by content and bind callables per execution. SCI `fork`
(`reference-code/sci/src/sci/core.cljc:345`, `fcbd88628`) alone freezes neither a
mutating parent nor an inherited atom. This supersedes D1's earlier context-update recipe.

**Cost and recovery.** Measure branch/open/acquisition/arming and their total; work
follows one assigned agent and changed definitions, not cluster startup. Context render
must fit within the <1 s overhead target. Red work remains editable. Timeout is not exit.
Messages use existing cluster-qualified addressing with explicit custody: prove delivery
after fork/during a turn and history after retirement. Do not copy messages. Conversation
tasks keep their reply condition and may run shared without code-test obligations.

## 3. Stage 2 — Writing code at the REPL

**What the agent sees.** Submit source to a branch. A successful definition persists
and is callable there when the reply completes. Undefining retracts it unless surviving
references prevent removal. The default requires a schema and a reaching test first;
an experimental branch can explicitly choose warnings.

**Requirements and proof — R3, R4, R5.**

1. Model replies and outside submissions share admission, evaluation, settlement and
   installation. Read effective branch configuration at each submission, not at arming.
2. All four dials ship as `:gate`, independently configurable to `:warn` through ordinary
   branch configuration. `:gate` refuses before evaluation, changing neither rows nor
   callable roots. `:warn` retains authored data and reports why merge is blocked.
3. Valid contracts remain armed. Warned missing/malformed contracts are not compiled or
   manufactured; replacing a valid contract removes its stale wrapper. Prove initial
   install, next call, fresh acquisition, test fork, repair and merge refusal together.
4. Bind request identity to branch incarnation and frozen source/namespace. Identical
   retries share one admitted run; different inputs under that identity refuse.

| Dial (`:seon.config.eval/…`) | Check |
|---|---|
| `schema-present` | Every proposed function has `:malli/schema`. |
| `schema-valid` | Schema is well formed; domain refs, map keys and predicate/function symbols are qualified. |
| `test-present` | At least one current test reaches each proposed function. |
| `test-first` | That test existed in the captured branch basis before the function change. |

**Mechanism.** `submit-source!` (`src/seon/cluster/agent.clj:607`) calls ordinary
evaluation → settlement → installation. MCP transports that request; delete its
non-settling SCI evaluator. `host-eval` remains JVM inspection only. Malli supplies
schema compilation/errors; built-ins such as `:=>`, `:cat`, `:int` remain legal.
Test-first uses the existing reach index in reverse. First prove it records test calls
to not-yet-defined symbols; do not invent another detector or schema parser.

Filesystem submissions capture owned paths and explicit deletions against the lane's
last admitted file bases, analyze those bytes with B1's resolver inputs, and write through
the same declaration admission to that **named branch**, never implicit `current-src` or
default. Dirty shared files are authoring inputs, not permission to load them. The target
keeps unaccepted bytes in source-owner staging outside live load paths (no worktree);
the interim instead holds dirty checkout files and defers conflicting root integrations.
Host-bound/file-only inputs remain staged and refused for ordinary SCI execution until
their required platform proof exists. Missing saves, first observation and deletions
are content differences; no session event acknowledges publication. MCP private `def`
state alone is neither durable submission nor the program tested by `seon.test/run`.

**Cost and recovery.** Checks follow proposed forms, schema dependencies and test lookups,
not the whole program. Install the listener before submission and reconcile durable
run/closed-turn facts. Closure proves settlement only; Stage 3 defines reply success.
Disconnect or deadline returns outcome unknown with request/run identity, never a source
replay or early release. Provider-free work completes through ordinary run completion;
a continuation needing a provider refuses by name. Durable rows/shown text persist;
private objects remain in memory. Preserve surviving candidate evaluation and the
writer's conflict-basis check when removing superseded definition-time gating.

## 4. Stage 3 — Feedback

**What the agent sees.** The reply shows evaluation text, definition diagnostics and
affected-test results. Context that is missing, wrong or unhelpful becomes ordinary
structured feedback to its owner whenever the outside agent must look elsewhere.

**Requirements and proof — R5, R6, R7.**

1. Coalesce changes once from the submission's program writer reports, including direct
   retractions and changed tests. Use dependencies before and after the change, not only
   the installation vector. Reads and test-result writes trigger no test request.
2. Run affected tests through B4's one selector/runner on the captured branch program;
   record back to the submitting branch. No system-wide fallback. Unknown selection is
   explicit; empty selection is a missing-test warning, not green. Red does not undo a
   warned definition. Reuse requires positive content/input evidence.
3. Reply success joins actual execution exit, observed installation and terminal test
   evidence. Prove disconnect just after settlement, installation failure and direct
   retraction. Settled-but-unconfirmed installation/feedback is not success.
4. Share one declared diagnostic contract across REPL, root diff and merge refusal:
   subject is a qualified function symbol, check is one of the four dial keys, basis is
   an immutable commit id. Include mode, branch, relevant path, message and remedy;
   refusal also names operation, expected shape and offending value.

| Reply part | What it tells the reader |
|---|---|
| Request/run, branch and basis | What happened and which run retry must reconcile. |
| Shown text and diagnostics | Exact ordinary presentation; which checks need repair. |
| Installation and terminal status | Proven, failed or explicitly unconfirmed; never inferred from closure. |
| Test identities/results | Executed, reused, red, excluded, unfinished or unknown, with tested commits/digests. |

Example: “`my.ns/f`: schema-valid failed at <path>; accepted on <branch>; merge blocked
until <remedy>.” A strict refusal says “definition refused” and installs no proposed row.
The exact declared keys live in the normative implementation contract, not a lint ledger.

**Mechanism, cost and recovery.** The ordinary agent path calls `seon.test/run` after
installation, as `my.test/check` composes it (`src/my/test.clj:5–26`). Work follows
changed dependencies and selected bodies/recording. Context feedback uses the existing
task/issue writer, naming namespace, basis/contribution inputs, deficiency and requested
context. Authored-input errors are declared diagnostics; failures inside valid core
execution or recording follow the full core error policy, never a broad warning catch.

## 5. Stage 4 — Root review and preparation

**What root sees.** The branch diff shows changed declarations, source differences
(including absent sides), conflicts, shared diagnostics and latest test evidence.
Stale/missing results are labelled. Root sends back a reason or requests preparation.
`my.task/merge!` requests preparation; shared acceptance still requires named root/owner
acceptance through the gate. Green candidate completion does not close the shared task.

**Requirements and proof — R10, R11.**

1. Recover original B; capture immutable C and H. Refuse unavailable ancestry or a missing
   required definition digest by name. Compare canonical digests before reading full rows.
2. Transfer declarations by stable identity with complete references/components. Never copy
   branch-local numeric IDs or silently truncate a pull. Prove colliding IDs, >1,000 refs,
   alias-only changes, edit/revert and equal delete/recreate. Validate surviving callers.
3. Create or retain intermediate S from captured H. Apply non-conflicting changes through
   ordinary prepared admission and retain each conflicting identity's B/C/H versions for
   repair. The shared head stays unchanged. S is the combined proposal, not a second workspace.
4. Create the structural conflict task for existing root. Let author/root repair that
   same intermediate proposal; once resolved, validate/test the complete combined program.
   Prove one conflicting plus one disjoint change: disjoint work survives repair and the
   final gate covers both. Refusal preserves editable work and evidence.

| Basis → final digest | Net change |
|---|---|
| Equal | None, including edit/revert or equal delete/recreate. |
| Absent → present | Addition. |
| Present → absent | Retraction. |
| Different present digests | Replacement. |

A conflict means both sides changed one identity away from B to different final content,
including deletion. Equal final content is not a conflict. Through B3's ordinary writer,
`seon.id` derives task identity from subject, competing definitions and basis. Retain both
source commits and a subject-local detector: identical repeats are idempotent; changed
content is distinct work. Root's repair uses this same gate.

**Mechanism.** `my.program/diff` composes `changed-identities`, scoped digests and
`three-way` (`src/seon/program.cljc:578,621`); it reads without tests or writes.
`prepare-merge!` (`src/seon/cluster/source.clj:736`) owns S. B1's digest includes source,
qualified identity, resolver context and semantic metadata. Normalize at the indexer's
existing owner. Test runs/members retain B4's evidence authority; they are not declaration
replacement input. Turns, messages, tasks, errors and evaluation results do not merge.

**Cost and recovery.** Review overhead targets <1 s, excluding test bodies; work follows
changed identities and displayed sources. Measure examined history with a fixed change
and growing history before claiming change-proportional work. Retain the working branch
rather than rebasing away its repair state. If H moves, Stage 5 rebuilds the combined
proposal and proof within the request deadline. No endless retry or partial acceptance.

## 6. Stage 5 — Gate and accept

**What root sees.** Acceptance requires the complete obligation for S, not a passing
subset. Entry warnings cannot weaken this gate. Acceptance produces M only when the
writer confirms that the tested destination H is still current.

**Requirements and proof — R8, R9, R12.**

1. Under the current rule, every function in the combined program has a well-formed,
   fully namespaced Malli schema and ≥1 reaching test. Changed functions also need
   test-first basis evidence. This revision retains that scope; option 3 does not narrow it.
2. Required tests are all current static reaching tests **union explicit task tests**,
   including disconnected task tests and declared-long members. Missing coverage/unknown
   dependencies refuse by name; observed past reach is diagnostic. Validate schemas, final
   refs, surviving callers and contracts before running tests.
3. Every required member has positive executed/reused evidence for immutable S and its
   proposal. Missing, red, unfinished, excluded or unknown blocks acceptance. No required
   member disappears through eligibility. Refusal is not empty successful selection.
4. The writer validates named root/owner acceptance of this exact S/proposal and its
   evidence, alongside H's original precondition; a branch name, green result or caller
   assertion is not acceptance. Close dependent execution before advancing the accepted
   destination and retain that unavailability through export/convergence. If H moved,
   refuse before mutation, rebuild S against new H, recompute conflicts/obligations and
   obtain valid combined proof. Never accept an untested subset. Only acceptance settles
   the shared task; report its outcome through the existing history owner.
5. Preserve merge ancestry (“parents”: the immutable commits from which M was formed)
   and tested-run evidence through cleanup. Prove retrieval by run identity after scratch
   release/retirement for the agreed retention lifetime; ancestry alone is not permanent storage.

**Mechanism.** O3a fixes `seon.test/select` (`src/seon/test.clj:576`) at B4's existing
owner. A named request includes changes, task tests, custody and required eligibility.
Reuse complete content/input evidence even for explicitly changed seeds. Delete the
reached-seed veto and accept's subset loop together; do not rebuild dependency walking.
Fully covered acceptance selection executes/writes nothing. Inherited missing proof
blocks under current scope; it does not justify running the whole suite.

`seon.test/run` executes S with its matching database/context and explicit result-recording
connection. Fixtures/resolution must not substitute `current-src` or the database holding
results for S. Reused members retain their actual tested basis. `accept-merge!`
(`src/seon/cluster/source.clj:797` at `82332488f`) uses the maintained Datahike guarded writer with Seon's
final-report validator (`reference-code/datahike/src/datahike/writing.cljc:880–904`,
`merge!` in `versioning.cljc:738`, gitlink `4157916b5`). The writer, not a pre-read,
checks the expected head; Datahike supplies lineage, not semantic conflict resolution.
M retains H/C/S ancestry or B4's equivalent durable S/result lineage;
retain an explicit reference when results live elsewhere. Use immutable commits, not branch names.

**Cost and recovery.** Measure selection, admission, bodies, recording and release separately;
non-body overhead targets <1 s, proportional to changed/reached work. Prove two reaching tests,
one failing, plus a later passing one-test run still refuses. Also prove missing task/long
members, stale evidence, modified S, moved H and fully covered read-only success. Repair
warned test-first violations by adding a test then resubmitting the function: history proves
existence-before-definition, not red-before-green or a new timestamp stamp. Release owned
values on every exit, retire only with retention proof, and never sweep the store per merge.

## 7. Stage 6 — File export and recovery

**What root sees.** After acceptance and proof, the source owner writes the accepted
files and commits only those paths, naming task and M. No second approval and no push.
First export existing interpreted function/test replacements with clear provenance;
full export later includes additions, deletions, schemas and new namespaces.

**Requirements and proof — R14, R15, R16.**

1. Export the complete accepted delta. Recover original file/span provenance through
   declaration/history reads; new declarations supply destination/position. Ambiguous
   mapping refuses with identity and candidate paths. `overrides` is a later check, not inventory.
2. Capture originals at the expected source base and stage the entire desired file set
   outside live paths under whole-file holds. Read each file once and splice exact spans.
   Preserve stale-source/symlink refusals; no worktree or per-definition live edit.
3. B1 analyzes the staged bytes in their real namespace/resolver/reader/dependency/config
   context. Compare canonical digests and exact source separately; verify deletions,
   neighbors, callers and no unexpected declarations. B4 must execute the proposed body;
   fallback to an old JVM body or synthetic file context is not proof.
4. Before the first live move, retain M, source base and all original/desired bytes through
   existing source/Git staging. Close reload admission across installation and Stage 7.
   Check each expected file digest before writing; verify the complete set before commit.
5. File-only host-bound declarations, schema resources, new namespaces without destination
   rules and non-Clojure inputs stage onto the named branch and join this same gate.
   Host-bound proof uses B4's platform host before integration or stays deferred. Non-Clojure
   inputs join declared test evidence. Schema adoption is incremental, with retirement
   refusal while writers/referrers survive; reset/from-zero boot is not schema proof.

| Actual state | Recovery |
|---|---|
| Desired bytes | Already installed; no repeat write. |
| Original bytes | Pending; install only against that digest. |
| Other bytes | Refuse for root repair; preserve unrelated edits. |
| Partial file set | Keep execution/reload admission closed; resume this accepted export. |
| Complete set, no commit | Verify all intended bytes, then commit only its paths. |
| Commit exists, convergence incomplete | Verify identity/bytes; resume publication/convergence without duplicate commit. |
| Restart with pending export | Reconcile before loading affected code; missing reconstruction evidence means unavailable. |

**Mechanism.** Existing edit/filesystem owners splice captured bytes and enforce
preconditions (`src/seon/edit.clj:229`; `src/seon/fs/jvm.clj:593–712`). B1's source
and boot owners reconcile pending export before restart admission. Never load mixed
files, replay definition effects, or add a D1 journal/completion registry. Refusal keeps
accepted database definitions and creates root's file/identity conflict task. Keep
staging until evidence or resumable commit is retained; never restore unrelated files.

**Cost and proof.** Work follows touched bytes/declarations. Analysis and total ordinary
export overhead target <1 s. Prove two splices in one file, multi-file resolver changes,
new/deleted declarations when enabled, caught I/O failure, interruption after the first
of two installs, and separately authorized new-process recovery. Ordinary export adds no
platform tier per change; host-bound work retains its required proof. File bytes,
publication, arming, actual behavior and browser paint each need their own observation.

## 8. Stage 7 — JVM convergence

**What root sees.** The accepted file set becomes usable only when loaded code and
contract checks match it. Database acceptance alone cannot make JVM mutations atomic.

**Requirements and proof — R13.**

1. B1 completes S3's existing evaluation/adoption boundary: mark unavailable before
   the first row/root mutation and exclude conflicting reloads and dependent evaluations
   through **rows → load → arm → record**, including projection readiness. This starts
   before acceptance changes the destination rows/head and spans export; failure leaves
   affected execution unavailable; an unchanged adoption record is not health. Keep the host REPL reachable.
2. Prove interleaved adoptions, partial namespace reload failure, new/replaced Var wrappers
   and an old branch's indirect call. Differing definitions/contracts and affected callers
   must stay isolated; an uninterpretable caller refuses by name, never silently runs host code.
3. Until exclusion is proven, the orchestrator alone integrates; lanes never self-adopt.
   A dirty required namespace/schema dependency defers only that integration. Whole-file
   holds, digest checks and lint-only hooks remain.

**Installed part and remaining proof.** Harness S3a `d77a9461a` marks the program unknown
before JVM changes and keeps it unknown after failure; S3b `389d2d265` resolves test
callables inside exclusion; S3c `548d7b6f4` adds run-level loaded provenance. These are
landed work, not D1 additions. [S3's landing](../landing/lane-harness-s3-program-validity-2026-09-24.md)
names remaining evaluation/render/seed exclusion, fault storage/delivery and per-member
provenance gaps. At revision `82332488f`, `cluster.clj:2730–2737` still writes rows before
exclusion; `test.clj:1052` records a run's basis before later members execute. Complete
those at S3's owners: validity and callable resolution must share exclusion through
actual exit, self-adoption defers, and each member proves the program it really ran.
An entry-only validity check cannot exclude an adoption that starts during execution.
Keep the existing conservative whole-program unknown scope until narrower exclusion is
proved. A lock release after failure never restores readiness; error storage/delivery
uses the ordinary error route, with the REPL still reachable.

Read-only revision evidence (MCP JVM, explicit root `/Users/sean/src/seon`, `default`,
2026-09-24 22:13:33Z): `stored-path-digests` equals `path-digests` for `cluster.clj`,
`test.clj`, `cluster/source.clj`, `seon.cluster.edn` and `seon.test.run.edn`;
`@seon.cluster/program-validity` returned nil, and both exclusion Vars resolved with
explicit lock/validity arguments. Form **21.075 ms**, PREPL **36 ms**, shared used heap
**1,593,275,440 bytes** (not allocation). Status: PID 39156, start 21:55:31Z, alive but
faulted, 16 open signatures; status observation max **2,077 ms** includes problem reads
(`failed-tests` max 1,851 ms), not publication cost. Row equality/Var presence is not
behavior or arming proof. No JVM was started; no test, branch mutation or reload ran for this revision.

**Mechanism, cost and recovery.** B1's adoption owner uses ordinary `require :reload`
and B2's installer (`src/seon/sci/eval.clj:1206`). Work follows changed declarations and
required dependents, including B1's macro/type recompilation rules. Measure load/arm/record
within total export overhead. No new loader, token service or replay queue. Prove bytes,
callable behavior and arming before reopening execution; `git revert` or an old record
cannot undo JVM effects. No lane reset/restart supplies this proof.

## 9. Build order

O codes are cuts at the existing owners, in the order below; they replace the former
O3a-first schedule. **S1/S2/S3/S4 are the harness's work**, not a second D1 implementation.
S3a–c have landed; adopt/prove their remaining guarantees before depending on them.
S2 clean checkpoints depend on S1/S3; S4 shares S1/S3 and precedes effectful attempts.
Whole-file holders integrate every producer/consumer change; D1 waits on or hands work
to that owner. No delegation or runtime work is authorized by this document revision.

Every named cut caps **all gross added source/schema/caller/regression lines at 100**,
not net growth. Figures below are budgets, not measured diffs; reuse existing regressions.
If the cut cannot fit, stop and reprice/split at a loadable seam before editing production.
All rows have **0 ms default publication/save**. Branch submission and ordinary accepted
merge/export/convergence each target **<1,000 ms** overhead, excluding measured selected
test bodies. Phase targets share that total, not separate
one-second allowances. Harness warm overhead targets ≤150 ms separately. Changed bytes,
definitions and required dependency closure determine work; unrelated saves do none.
First preparation and wide macro/type/platform work require separate clocks, allocation,
causing functions and justification; >10 s still needs owner authorization and an issue.

| Ordered cut / owner files (existing families include their callers/tests) | Dependency and smallest composition; retirement | Probe required before dependent cut | Gross + cap; cost target |
|---|---|---|---|
| O0 / B1: `bin/seon-hook`, `.claude/seon-hook.edn`, `test/seon/dev/hook_test.clj` | Keep interim paused; delete automatic publication entry/call sites. Keep named-path lint and root's explicit integration entrance. | Named/pathless saves, including schema and host-bound paths, never call publication or change default. | 30; save publication 0 ms, lint measured separately. |
| O1a / S2 owner: `src/seon/cluster/{agent,registry,boot}.clj`, `src/seon/sci/eval.clj`, agent/cluster schemas | After S1/S3, S2 clean/overlay/retention and installed alias proof; D1 consumes retained B and clean checkpoint handle. O2a's context work belongs to S2. | Reacquire after parent advance: B fixed, no parent sentinel, private atom or mutable parent leak; retain checkpoint through child acquisition. | 40 D1 adapter lines; submission acquisition ≤150 ms warm. |
| O1b / B1+B2: `script/seon/dev/mcp.clj`, `src/seon/cluster/agent.clj` | After O1a/S4; durable `submit-source!`, terminal reconciliation and ordinary attempt custody. Delete non-settling SCI submission arm; keep read-only JVM inspection. | Retry same request: one durable run; disconnect after settlement reconciles; body/effects stay on branch and release waits for exit. | 100; submission <1,000 ms, changed forms only. |
| O1f / B1: `src/seon/cluster{,/source}.clj`, `src/seon/fn.clj`, source schemas, MCP caller | After O1a; captured filesystem inputs plus expected lane head through existing analysis/admission. No implied shared publication/reload. | Two lanes change one callee differently; direct/indirect calls and contracts differ, default unchanged; stale lane head refuses. | 100; submission <1,000 ms, O(changed bytes + affected closure). |
| O1g / B1: same source/MCP owners and publication regression | After O1f; explicit additions/deletions and source-owner staging outside live paths; reconcile admitted content, not event timestamps. | Missed/refused save and deletion remain pending on resubmit; unrelated dirty/held dependency is named and deferred before load. | 80; submission <1,000 ms; unchanged admitted inputs skip analysis. |
| O1d / B2+B3: `src/my/program.clj`, `src/seon/cluster/prompt.clj`, `src/seon/issue.clj` | After O1a/S4; ordinary namespace/task context and owner feedback; delete outside-only context. | Equal inputs give inside/outside context equality; opening plan reused but changed data changes evaluated answers. | 70; submission context contribution <1,000 ms. |
| O2c / B2+B4: `src/seon/sci/eval.clj`, `src/my/test.clj`, `src/seon/test.clj` | After S2/S3/S4 and O1b/f; coalesce writer reports, call one `seon.test/run`; delete installation store scan. | Retraction and changed test select correct before/after dependencies; reads/result recording launch no test. | 80; submission install + selection/record <1,000 ms plus bodies. |
| O3a / B4: `src/seon/test.clj`, test request/run/member schemas and readers | After S3 actual-member provenance and O2c; complete obligation + content-valid reuse. Prepare replacement for reached-seed veto; retire with O3c. S3 owns validity/provenance. | Two reaching tests (one red), later one-test green still refuses; task/long/unknown member cannot disappear; full reuse writes/runs nothing. | 100; integration selection <1,000 ms; work follows obligations. |
| O3b / D1+B1: `src/seon/program.cljc`, `src/seon/cluster/source.clj`, `test/seon/cluster/merge_test.clj` | After O3a; B/C/H three-way additions/replacements/retractions and retained S; replace replacement-only refusal. | Conflict plus disjoint delta survives on S; repair tests the combined H + C delta; H/default unchanged. | 100; integration preparation <1,000 ms plus combined bodies. |
| O3c / D1+B3: `src/seon/cluster/source.clj`, `src/my/program.clj`, `src/seon/issue.clj`, issue schema (B3 owns task migration) | After O3b; named root decision bound to S/proposal/evidence at guarded writer; delete reached-seed veto and accept's subset loop together. | Missing/wrong acceptance, changed S or moved H refuses with no partial write; rebuild and retest; retained evidence retrievable after unlink. | 100; integration acceptance <1,000 ms, no repeated test execution. |
| O1c / B2+B3: `src/seon/{config,turn}.clj`, `src/seon/sci/eval.clj`, eval-config/program schemas | After O3c; four entry dials and shared diagnostics; convert fixed gate and all callers. | Strict refusal enters nothing; warned malformed contract removes stale wrapper; repair arms on fresh acquisition and test fork. | 100; submission <1,000 ms, proposed forms/schema reach. |
| O2b / B2: `src/seon/turn.clj`, `src/seon/sci/eval.clj`, submission regression | After O1c; retire only superseded pre-install gate; preserve candidate evaluation and writer conflict basis. | Successful reply joins actual exit, installed rows and terminal feedback; failed install cannot return success. | 60; submission <1,000 ms, one batch/report. |
| O4a / B1+D1: `src/seon/cluster/source.clj`, `src/seon/edit{,/jvm}.clj`, `src/seon/fs/jvm.clj` | After O3c; narrow replacement export from M under whole-file holds; reuse exact-span splice/precondition owners. | Two spans in one file plus second-file resolver change round-trip to identical declarations; stale base/symlink refuses, neighbors unchanged. | 100; integration export <1,000 ms, touched bytes only. |
| O4b / B1: `src/seon/cluster/{source,boot}.clj`, `src/seon/fs/jvm.clj`, `test/seon/cluster/export_test.clj` | After O4a; retain original/desired set and M before first move, resume same export/commit; no journal owner. | Interrupt after first file; mixed set stays unavailable; resume/commit only accepted paths; restart proof belongs to root's platform checkpoint. | 100; integration recovery <1,000 ms excluding authorized boot, touched set only. |
| O4c / S3+B1+B2: `src/seon/cluster.clj`, `src/seon/sci/eval.clj`, `src/seon/render/web.clj`, S3 regression | After O4b and S3's exclusion/delivery/provenance completion; include rows, readiness and record in existing boundary. No second validity atom/lock. | Partial reload/arm/record failure, interleaved adoption, render/eval/seed entry and self-adoption: never execute unknown, preserve full cause/delivery, REPL reachable. | 100 D1 integration lines; integration convergence <1,000 ms for ordinary closure. |
| O5a / B1+D1: `src/seon/cluster/source.clj`, `src/seon/{edit,fn}.clj`, declaration schemas | After O4c; expand export to additions/retractions/new namespaces with explicit destinations. | Add + delete round-trip; surviving reference refuses; retired symbol absent and branch callers run intended body. | 100; integration <1,000 ms, changed declarations/bytes. |
| O5b / B1+B4: `src/seon/cluster/source.clj`, `src/seon/schema/admission.clj`, `src/seon/test.clj` | After O5a; file-only/host-bound/resource inputs join same candidate gate and staged export. | Incremental schema+consumer adoption; host-bound proposal runs required platform proof or defers, never old-body SCI fallback. | 100; save 0 ms; ordinary integration <1,000 ms; platform body/boot separately authorized. |
| O6 / B1: `bin/seon-hook`, `.claude/seon-hook.edn`, `test/seon/dev/hook_test.clj`, lane guidance | After O1g/O2c feedback proof; remove session-digest acknowledgement and derived-write scan; retain named-path syntax/Markdown/docstring lint. | First event, missed/refused event, deletion and same-byte resubmit all compare to admitted branch content; no hook publication route remains. | 50; no per-save whole-tree digest walk; explicit submission <1,000 ms. |
| O7 / D1: `test/seon/cluster/{merge,export}_test.clj`, D1 guidance | After preceding cuts and shared harness proof; two-branch demonstration below, no new runtime owner. | End-to-end B/C/H/S/M → files → actual call; guidance names installed interfaces and exact limits. | 80; submission and integration <1,000 ms plus measured bodies; save publication 0 ms. |

**Counted retirement, checkout `82332488f` (physical lines, including internal blanks).**
O0 deletes `bin/seon-hook:1615–1705`: **91 lines**, the publication-only path predicates,
`publish-source-paths` and `current-source-feedback`, plus its two caller expressions
at 1960/2022. O6 deletes digest/scan helpers at 1727–1728 (**2**), 1730–1792 (**63**),
`record-digests!` at 1807–1814 (**8**) and `derived-write-feedback` at 1816–1866 (**51**):
**124 lines**, hence **215 counted hook body lines** total, before caller/config/comment
cleanup. Keep `event-agent` (1794–1805): the edit log still calls it. Config blocks
`:shell-writes` and publication-only `:current-source` leaves leave with their callers.
Preserve `:current-source` root/cluster lookup used by schema/contract lint (495, 594, 596)
until B1 converts those readers; do not delete their configuration accidentally. No
credit for deleting the shared root integration/analyzer. Named edit lint remains;
pathless writes receive authoritative lint at explicit branch submission. Root compares
accepted staged bytes against published/adopted content, including deletions and no-op
publication with unfinished convergence; never consume every lane's dirty files.

Harness work is charged only to S1/S2/S3/S4; the former D1 aggregate 355/40/40/300 is
superseded, not silently claimed met. Option 3's research estimate (250–450 production/
schema additions, 200–350 test additions, 40–72 h across owners) remains provisional:
per-cut caps above are ceilings, not their sum as an estimate. Before implementation,
root records disjoint actual budgets and any excess against that price. Net deletion
never excuses a >100-addition slice; no new machinery replaces the retired hook path.

**Demonstration.** Two real tasks on separate branches edit → get feedback → review →
repair conflict → gate → accept → export → converge → call. Include a schema guarantee,
a missing reaching test, direct/indirect candidate failure then repair, one disjoint plus
conflicting change retained on S, a moved H after green, retained test retrieval after
cleanup, and interrupted two-file export/restart recovery. Shared definitions stay unchanged
until named acceptance. Observe candidate addressing/history and browser paint separately.

Query inherited contract/coverage readiness first and assign gaps to existing namespace
agents. Prove needed test additions can be admitted and accepted before claiming TDD repair.
If narrow O4 cannot export them, use an already-covered replacement and name the unproven
full repair loop. O3a can prove its predicate while a real merge honestly refuses. No
hidden exemption or whole-suite fallback. Task renaming/nonessential profiling waits.

Record exact source/forms/results, task/branch/commit identities, B/C/H/S/M, required tests
and evidence, file digests/bytes, publication/adoption, timings/work/memory, line deltas,
cleanup and unknowns in `docs/prds/agent-platform/landing/lane-d1.md`; the normative appendix
specifies the full record. Follow [shared verification rules](../../../../AGENTS.md)
and [cut cadence](README.md#6-implementation-proof-and-recovery), including hot-path
parent/current probes and publication clock rows. Never treat unknown as passed or
another lane's breakage as authority to change its files/sessions.

## 10. Open decisions for the owner

No new policy decision is required to apply the publication ruling. Option 3, option 1's
interim, whole-program contract/coverage scope, combined reaching/task proof, named root
acceptance, whole-file holds and fail-closed convergence are settled. Do not reopen them
as alternatives. Use existing cluster-qualified addressing and prove scoped delivery;
copying messages or adding an outside-agent registry is not a candidate design.

S3's reported entry-check-versus-exclusion question is resolved by the existing law:
exclusion lasts through actual exit. Its incomplete per-member provenance is required
work under harness §5, not an optional relaxation. S2/S4 own checkpoint/attempt choices.
If an existing owner cannot supply a required guarantee within a cut's price, stop only
that dependent production cut and present **exactly three priced options**, simplest
viable constraint first, recommendation marked, each with guarantee, gross additions,
engineering hours, save/merge cost and sacrifice. Do not invent alternatives before
that concrete incompatibility exists. This revision authorizes no implementation or
runtime proof; root reviews the design before assigning production work.
