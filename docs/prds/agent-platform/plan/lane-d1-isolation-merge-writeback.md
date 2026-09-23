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
They receive entry validation and affected-test feedback while their work stays isolated.
The orchestrator acts as root, reviewing changes and accepting them or sending them back.
Changes reach the shared program and files only through a tested, reviewed merge.
Compose the existing branch, submission, test and source owners around immutable inputs.

**The path.** Start from the namespace context and edit a named branch. Each submission
gets validation and test feedback. Root reviews its diff and requests acceptance or
sends it back. Preparation captures the original base **B**, candidate head **C** and
destination head **H**. It builds intermediate branch **S** from H plus the proposed
changes, keeping conflicts there for repair. The mandatory gate tests that combined
program. Named root/owner acceptance installs exactly the tested proposal if H is still
current, producing accepted commit **M**. The source owner stages and verifies M's file
changes, installs and commits the complete set, then loads code, installs contract
checks and records convergence before affected execution resumes. No second approval
or push follows.

This is a target, not installed proof. The [implementation contracts and historical evidence](../../../research/agent-platform/d1-evidence-2026-09-23.md#implementation-contracts)
contain exact data shapes, source navigation and detailed proof records; the explicitly
normative contracts there are part of this plan. Fenced historical archives are not.
Owner rulings `a29faf5a6`, `38863a0c8`, `215c32d9a`, `5ec049cfd`, `eb709fcb8` and review
folds `7523dd510`, `6f5ec198a` govern this account. D1 composes the work; B1 owns analysis,
publication and MCP, B2 execution/context, B3 tasks/configuration, B4 tests/evidence.

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
| R13 | Load → arm → record is exclusive; failed convergence leaves execution unavailable. | 7 |
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
   test-first basis evidence. Changed-only coverage is recommended but not yet ruled (§10).
2. Required tests are all current static reaching tests **union explicit task tests**,
   including disconnected task tests and declared-long members. Missing coverage/unknown
   dependencies refuse by name; observed past reach is diagnostic. Validate schemas, final
   refs, surviving callers and contracts before running tests.
3. Every required member has positive executed/reused evidence for immutable S and its
   proposal. Missing, red, unfinished, excluded or unknown blocks acceptance. No required
   member disappears through eligibility. Refusal is not empty successful selection.
4. Accept exactly the tested proposal with H's original writer precondition. If H moved,
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
(`src/seon/cluster/source.clj:786`) uses the maintained Datahike guarded writer with Seon's
final-report validator. M retains H/C/S ancestry or B4's equivalent durable S/result lineage;
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

1. B1's existing evaluation/adoption boundary excludes conflicting reloads and dependent
   evaluations through **load → arm → record**. Failed load/arm leaves affected execution
   unavailable; an unchanged adoption record is not health. Keep the host REPL reachable.
2. Prove interleaved adoptions, partial namespace reload failure, new/replaced Var wrappers
   and an old branch's indirect call. Differing definitions/contracts and affected callers
   must stay isolated; an uninterpretable caller refuses by name, never silently runs host code.
3. Until exclusion is proven, the orchestrator alone integrates; lanes never self-adopt.
   A dirty required namespace/schema dependency defers only that integration. Whole-file
   holds, digest checks and lint-only hooks remain.

**Mechanism, cost and recovery.** B1's adoption owner uses ordinary `require :reload`
and B2's installer (`src/seon/sci/eval.clj:1206`). Work follows changed declarations and
required dependents, including B1's macro/type recompilation rules. Measure load/arm/record
within total export overhead. No new loader, token service or replay queue. Prove bytes,
callable behavior and arming before reopening execution; `git revert` or an old record
cannot undo JVM effects. No lane reset/restart supplies this proof.

## 9. Build order

O codes identify implementation slices, not additional workflow stages. **O3a is the
first implementation**, after its file holder releases it. O1a/b preparation can proceed
on free files. Runtime proof follows health → O0 → O1a/b/d + O2a/c → proven O3 acceptance
→ O1c/O2b activation → narrow O4 → two-branch demonstration. Never activate warned
definitions or retire fixed gating before acceptance is proven.

| Slice / owner | Work and prerequisite | Stage acceptance checklist | Deletes; added-source cap |
|---|---|---|---|
| O3a / B4+D1, first | Complete content-valid selection and proposal binding. | [Gate/accept](#6-stage-5--gate-and-accept) | Reached-seed veto and subset loop; ≤100. |
| O0 / B1 holder | Verify/adopt its `:as-alias` correction; commit/acquisition evidence. | [Branch](#2-stage-1--branch-and-namespace-context) | False load edge; no new D1 code. |
| O1a / B2+B3+D1 | Branch lifecycle, retained base, task-only custody/addressing. | [Branch](#2-stage-1--branch-and-namespace-context) | Bare-branch plumbing; ≤80. |
| O1b / B1+B2 | Durable submission, retry and terminal reconciliation. | [REPL](#3-stage-2--writing-code-at-the-repl) | Non-settling SCI evaluator; ≤100. |
| O1d / B2+B1+B3 | Ordinary namespace context and quality feedback. | [Branch](#2-stage-1--branch-and-namespace-context), [feedback](#4-stage-3--feedback) | Outside-only context/instructions; ≤80. |
| O2a / B2 | Retain unchanged context; update changed definitions/dependents. | [Convergence](#8-stage-7--jvm-convergence) | Per-call full-program derivation; ≤60. |
| O2c / B2+B4 | Batch/report installation; install + check <1 s combined. | [Feedback](#4-stage-3--feedback) | Installation store scan; ≤60. |
| O3b / D1+B1 | Review, conflict repair, named accept/send-back; after O3a/execution proof. Review overhead <1 s excluding bodies. | [Preparation](#5-stage-4--root-review-and-preparation), [gate](#6-stage-5--gate-and-accept) | Raw JVM-only acceptance/duplicate checks; ≤80. |
| O1c / B2+B3+B4 | Dials/diagnostics/warn lifecycle and automatic feedback; after O3. | [REPL](#3-stage-2--writing-code-at-the-repl), [feedback](#4-stage-3--feedback) | Fixed definition gate and all callers; ≤100. |
| O2b / B2+D1 | Settle batch/report; after O3. Preserve surviving candidate evaluation and writer conflict-basis check. | [REPL](#3-stage-2--writing-code-at-the-repl) | Superseded pre-install gate work only; ≤60. |
| O4a / B1+D1 | Captured replacement staging/round trip. | [Export](#7-stage-6--file-export-and-recovery) | Function-only override inventory; ≤100. |
| O4b / B1+D1 | Recovery, commit and exclusive convergence, including restart admission. | [Export](#7-stage-6--file-export-and-recovery), [convergence](#8-stage-7--jvm-convergence) | Independent lane adoption; ≤100. |
| Demonstration / D1 | Two branches complete the path below. | All stages | No new mechanism. |
| O5 / B1+B4+D1 | Wider/file-origin export with destinations/platform proof. | [Export](#7-stage-6--file-export-and-recovery) | Shared-candidate save publication; split per owner, each ≤100. |
| O6/O7 / B1+D1 | Ship guidance with interfaces; branch diff replaces program-only ledger rows, file holds remain. | All linked interfaces | Obsolete instructions; docs only. |

Caps count additions, not net lines. If a slice exceeds ~100, split preparation from
its atomic producer/consumer conversion or price the smaller existing seam first.
Aggregate targets remain provisional pending a disjoint revised ledger: source 355,
schemas 40, maintained fork 40, test additions 300. Charge moved code once. Report the
responsible seam and cost if revised scope exceeds these; do not claim the old target met.
Reprice O4b before code if B1's existing bootstrap/convergence boundary cannot supply it.

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

**Merge contract/coverage scope is the open policy decision.** Costs are engineering
estimates. All choices still require every affected/task test and changed-function
test-first proof, irrespective of entry dials. Whole-program scope remains in force.

| Option, recommendation first | Guarantee | Cost and tradeoff |
|---|---|---|
| **Changed functions only — recommended** | Changed functions contracted and reached; untouched gaps visible to root. | About half a day in O3a; gives up inherited-population compliance. |
| **Whole combined program — current rule** | Every function meets contract/coverage policy. | Census plus multi-day namespace repair as needed; narrow merges wait on inherited gaps. |
| **Per-cluster scope dial** | Mandatory gate within declared scope. | About one day plus whole-program repairs where selected; adds policy and loses a uniform guarantee. Do not implement before ruling. |

B2/B3 must also settle the addressing interface before startup. Prefer existing
cluster-qualified addressing with scoped delivery/history proof. Shared-only addressing
costs additional cross-branch custody/read-evidence integration for a simpler sender
address. Copying messages is rejected because it creates another authority.

Everything else is settled: branches with internal custody, orchestrator as root,
named acceptance, gated write-back/commit without second approval, no push, host-bound
export deferred from the first loop. No merge-critical roster, approval counter, file
exception, automatic settlement merge or separate loader.
