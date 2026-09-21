---
type: plan
status: integrated implementation plan; proof gates precede dependent cuts
created: 2026-09-21
tags: [plan, agent-platform, refactor, namespace-agents]
---

# Agent platform

Build the system that can improve its own program: query a real defect, give an
agent a bounded task on an isolated candidate, verify its change against the
combined program, accept it explicitly, and write the accepted definitions back.
The work removes duplicate machinery while building that complete loop.

This directory contains the integrated plan and implementation specifications.
Each spec states its design directly: the data flow, dependency source, ordered
changes, probes and acceptance conditions. Research and review history are outside
this directory. These are implementation instructions, not claims that the code
has already changed. The [proposed shared instructions](AGENTS-rewrite-2026-09-21.md)
remain a replacement proposal until the implementation transition.

## 1. What becomes simpler

The expensive pattern is repeated reconstruction. A dependency already maintains
an answer; Seon copies it, recomputes it, and adds mechanisms to keep the copy fresh.
Tests then protect those mechanisms rather than the behavior the user needs.

The change is to compute at the producing boundary and carry the answer into the
next operation. A database branch is a pointer, but creating an entire environment
is additional work. A compiled schema is reusable, but a changed schema must still
invalidate its dependents. Avoiding repeated work requires both facts to be explicit.

| Today’s unnecessary work | Integrated design | Guarantee retained |
|---|---|---|
| Reconstruct projections and preparation plans from program facts at acquisition or call time | Carry the admitted projection; update only changed declarations and their dependency closure | An older cluster continues to use its own contracts and definitions |
| Ask whether a read is current by rerunning it | Use Datahike dependency revisions; narrow wildcard producers where their semantics permit | A broad or unknown dependency never becomes falsely current |
| Multiple publication, manifest, operator and cache paths | Analyze changed inputs with clj-kondo, transact on an unpublished branch, use its report for caller analysis, then publish and adopt | A refused publication leaves the published head unchanged |
| Repeated test-host boot, full-program fixtures and duplicate result selection | One request uses an explicit execution program and canonical carried fixture; reuse each member’s valid recorded green | The actual candidate runs, unknown evidence executes or refuses, destructive work stays isolated |
| Per-turn program scans and independently assembled history views | Retain unchanged SCI contexts; apply a verified program transition only when needed; render stored evaluations through their pairs | Private objects survive turns and historical prompt bytes remain unchanged |
| Error stamps, copied unions and parallel task lifecycles | Declared error data and one task writer/settlement path | Missing completion evidence is visible; repeats do not create duplicate agents |
| Profiling and merge proposals that infer more than they observe | Inclusive observations by exact definition; test the proposed combined program before writer-side acceptance | No invented self time, stale-head merge, or untested file export |

[A1](lane-a1-projection-carried.md), [A2](lane-a2-datahike-one-answer.md),
[B1](lane-b1-one-publication-path.md), [B2](lane-b2-walk-flow-fork.md),
[B3](lane-b3-errors-tasks-dials.md), [B4](lane-b4-tests-in-process.md),
[C1](lane-c1-wrapper-profiling.md) and [D1](lane-d1-isolation-merge-writeback.md)
own the exact mechanisms and their source-backed proofs.

## 2. Acceptance: the complete loop

1. **Reliable development access.** The operator and MCP status/evaluation surfaces
   reach the selected root and cluster and report unavailable observations explicitly.
   The host REPL remains usable while implementation changes the system.
2. **One program authority.** File indexing and agent definitions use the same
   declaration analysis and transaction construction. Every declaration has an analysis
   digest; definition identity includes the resolver context that gives its source meaning.
3. **Cheap change.** Unchanged adoption compares commit ids. Explicit source requests
   inspect their admitted inputs. Schema, analysis, validation and arming work follows
   the changed declarations plus their required dependents, not an unrelated full scan.
4. **Independent execution.** An old ordinary cluster, a new development cluster and
   an existing candidate keep their own program and contracts across another cluster’s
   reload. Datahike branching alone does not prove JVM/SCI isolation.
5. **Honest tests.** One `seon.test/run` request selects from the execution program,
   runs its definitions, records actual completion and exposes its recorded tally.
   An unchanged member reuses green only with matching per-member evidence.
6. **Real tasks.** Trigger identity resolves at the writer; starting establishes a
   valid done condition, agent and first turn. The agent sees tests/detector or reply
   obligations every turn. Budget exhaustion is recorded and resumable.
7. **Gated acceptance.** Explicit merge validates the combined program, requires the
   task’s tests and every test reaching changed functions, and refuses missing coverage
   by name. The writer admits only the tested shared head. Conflicts become root tasks.
8. **Verified export.** Stage changed files outside the live checkout, verify old
   file bases and exact spans, then reanalyze with the canonical resolver. Equivalent
   declaration facts, callable proof and accepted evidence precede installation.
9. **A live demonstration.** Two candidates perform bounded real work, an invalid
   candidate refuses, a same-identity conflict reaches root, and accepted work survives
   export/reindex. Only then start the namespace-agent contract campaign.

The five laws remain: values carry their world; facts over inference; bounded and
event-driven execution; total honest boundaries; one mechanism improved in place.
AI presentation elides only at the render functions/value renderer. HTML never clips.
Tests use real Datahike, real SCI and the contracts the cluster arms.

## 3. Shared contracts

These are the interfaces that let the specifications compose. Their owning spec
contains the exact data shape and producer/consumer changes; consumers do not invent
parallel facts or a second derivation.

| Contract | Producer → consumers | Required behavior |
|---|---|---|
| Carried projection | A1 construction with A2/B1 database acquisition → every read, validator, wrapper and fixture | Raw, materialized, temporal and forked values receive the correct immutable projection before reconstruction fallbacks disappear |
| `:seon.program/definition-digest` | B1 canonical declaration analysis → A1, B2, B4, C1, D1 | Source, qualified identity, complete normalized resolver context and effective semantic metadata define identity; position, branch identity and authorship do not; dependency/input evidence remains separate |
| Changed declaration report | B1 unpublished transaction → caller lint, A1 arming, A2 validation, B2 adoption | Report-based invalidation; schema references, aliases, macros and external inputs participate where they can change meaning |
| Validator facts | A1 render-property facts and B1 effective arity/default facts → A2 | Producers and every consumer land together; narrowing work preserves complete final-entity/component validation |
| Declared errors | B3 constructor/contracts → A1 wrappers, A2/B2 callers and composed renderer | Input failure prevents entry, output failure rejects the value; forwarding boundaries derive precise unions; no general error predicate |
| Execution request | B4 → B3 task tests and D1 merge gate | Immutable execution database/commit, matching projection/context and explicit durable recording custody; a candidate is never silently replaced with current-src |
| Observation | Existing A1 wrapper with C1/B4 execution input → profile and diagnostic reach | Capture installed definition identity; carry execution scope into owned child work; completion waits for that work; host/unattributed work is explicit |
| Task transitions | B3 trigger/start/settlement → B2 loop, C1 findings, D1 conflicts | Trigger and start remain distinct operations; repeated/new occurrences use the existing wake route; missing subject, test or detector cannot close work |
| Candidate acceptance | D1 using B1/B2/B3/B4 → shared branch and export | Actual combined-program execution, immutable evidence lineage, atomic tested-head admission, retained evidence after scratch cleanup |

**Selection is conservative.** Current call/reference dependencies and required test
obligations govern selection and destructive-host classification. Past observed reach
is diagnostic; absence on an earlier execution path cannot prove changed code irrelevant.
A missing/incomplete graph is unknown and must widen or refuse, never exclude silently.
A later unrelated green cannot cover an older member’s untested change.

**Isolation includes loaded behavior.** A wrapper closing over one cluster’s schema
cannot validate another cluster merely because both call the same JVM Var. Capturing
one JVM root also does not freeze its indirect calls. A1/B2 prove the full reachable
execution boundary before removing context-specific selection or candidate isolation.

**Timeout includes exit.** A Future timeout only reports a late result. B2/B4 retain
termination, cleanup and no-overlap guarantees; arbitrary host work does not acquire
SCI interruption merely by being called from SCI.

## 4. Ownership and order

| Spec | Owns | Main reduction or feature |
|---|---|---|
| [A1 — projections and contracts](lane-a1-projection-carried.md) | Schema/projection construction, Malli integration, instrumentation, supplied-default preparation, schema shape | Compiled values acquired once and retained correctly; name-based supplied defaults |
| [A2 — Datahike and storage](lane-a2-datahike-one-answer.md) | `seon.db`, bridge, store/registry, blobs, Datahike/konserve seams | One currency mechanism, report-scoped validation, complete pulls, native values only after comparator/history proof |
| [B1 — publication and operator](lane-b1-one-publication-path.md) | Analyzer/program facts, publication/adoption, operator/hook, identity; **MCP evaluation/status tool and server first** | One analysis/publication path, exact definition identity, reachable and honest development tools |
| [B2 — agent execution and rendering](lane-b2-walk-flow-fork.md) | SCI, turns, agent graphs, history, AI/HTML rendering and delivery | Stable candidate behavior, one turn path, one history and changed-view delivery |
| [B3 — errors, tasks and configuration](lane-b3-errors-tasks-dials.md) | Error/task writers, config/effect/environment, bootstrap; **surviving source remainder** | One task family, precise errors, event-based dials; no source remainder without an owner |
| [B4 — tests](lane-b4-tests-in-process.md) | Selection, execution, fixture, evidence/recording, thin launcher | One run authority, per-member reuse and real execution-program isolation |
| [C1 — profiling](lane-c1-wrapper-profiling.md) | Inclusive measurements and their query surface | Definition-scoped observations through the wrapper, no separate timer or database per call |
| [D1 — candidates and export](lane-d1-isolation-merge-writeback.md) | Candidate acquisition, combined-program validation, acceptance, conflicts, write-back | A real self-improvement path composed from the existing owners |

B3’s remainder includes filesystem/shell/web support, scheduling/maintenance,
reconciliation, editing/context/problem helpers, evaluation drivers, background work
and development-cache support unless an explicit owner above already holds the seam.
Ownership is responsibility to classify and preserve surviving behavior, not permission
to bulk-delete those files. B1 retains operator/bootstrap publication custody; B2
retains evaluation and rendering behavior. Convert overlapping callers in one slice.

Implementation is four substantial cuts. The specs define responsibilities inside
those cuts; they are not eight sequential projects or a queue of per-function fixes.
Preparation first proves dependency selection using the real analyzer and recorded
test authority: an ordinary leaf change selects its actual dependent tests, a shared
function change selects the wider justified set, and an unchanged green request
executes no tests. Explain selected paths and distinguish lexical calls, callable
dependencies and descriptive symbols before trusting a selective gate. Sample leaf,
intermediate and shared functions; broad shared-function samples do not establish an
average for ordinary edits.

| Cut | Coherent removal and replacement | Exit condition |
|---|---|---|
| 1 | Program facts, publication and contracts: A1/B1 with A2 validator inputs and B3 constructor contract; remove redundant construction/publication paths with all callers | Faithful dependencies and definition identity; carried compiled contracts; incremental work and refused publication preserve the published head |
| 2 | Database and execution: A2/B2 with A1 acquisition and B4 execution inputs; remove repeated database/context reconstruction and duplicate execution machinery | Complete validation; old/new/candidate definitions remain independent; private objects survive turns; named execution program actually runs |
| 3 | Tasks, rendering and tests: B2/B3/B4; remove parallel settlement, history and test-request paths with all callers | One task settlement, history renderer and test authority; per-member reuse, actual termination and observed browser behavior |
| 4 | Profiling, acceptance and export: C1/D1 with B4 evidence; complete the composed self-improvement path | Honest observations; invalid/stale/conflicting candidates refuse; a real accepted repair survives export and reindex |

At most three editing assignments run concurrently; exact file ownership decides what
can overlap. No slice removes a safety mechanism before its replacement passes the
specific proof in its spec. A retirement and all callers are one commit. Remove the
superseded machinery in the same cut; do not keep dual implementations through an
extended migration. Small loadable commits may record progress inside a cut; they do
not each trigger a full integration cycle. This is a concentrated refactor, not a
multi-day sequence of small patches. If the evidence makes that scope infeasible,
bring the concrete scope/time tradeoff to the owner before extending it.

## 5. Measurements and size

The following are recorded baselines from the 2026-09-21 investigations, not a fresh
measurement of the stopped development system. Specs retain exact forms, source
revisions and measurement boundaries. Repeat the relevant case before implementation.

| Case | Recorded evidence | Interpretation |
|---|---|---|
| Source / tests / schemas | 90,162 / 98,985 / 14,256 lines in the writer’s baseline | Separate accounting scopes; no combined total disguises moved code |
| Projection acquisition | 3,996 ms inside a 4,648 ms fixture-base acquisition | The repeated reconstruction is the optimization target, not a larger timeout |
| Warm fixture branch | 37 ms p50 | Does not price full candidate environment/context construction |
| Explicit no-change publication / repeated docstring edit | 264 ms / 2,723 ms | Different from a pure unchanged-adoption commit-id comparison |
| Retained-read plans | 413 of 435 broad plans; 407 wildcard pulls | Producer shape limits the benefit of an attribute-revision comparator |
| Profiling microprobe | Approximately 40 ns timing-only increment; another attempted shape added 624 ns | Neither number proves final armed overhead, child-work attribution or concurrent recording |
| Text-matched long-test allowances | 68,105,085 ms across 128 declarations | A source inventory, not measured runtime; indexed declarations must drive conversion |

The ambition remains aggressive: **source ≤55,000 lines; tests ≤68,000 after the cut
and ≤45,000 after agents remove repeated setup; schemas ≤11,000; docs ≤35,000;
shared instructions ≤250 lines.** These are targets, not a proven sum of lane estimates.
Maintained correctness and a complete self-improvement loop decide acceptance.

Each spec owns its measured before/after scope and target. Before a cut, derive
mutually exclusive path/span sets: separate src, schemas, fixtures, tests and shell;
charge moved code once at its destination and include new profiling/merge code.
Do not sum overlapping `fn/*`, `my/*`, error conversions or transferred render code.
If an essential guarantee raises a target, state the mechanism and cost instead of
claiming the previous target was achieved. Compact lines are not a code reduction.

B1’s operation targets distinguish explicit no-change, non-core edit, core edit and
whole-program initialization. Measure visited inputs, compilations, transactions,
reloads and wrapper replacements as well as elapsed time. Boot/indexing above ten
seconds still needs owner authorization; a named slow path is not an explanation.

## 6. Implementation proof and recovery

Before a Clojure change, read the listed dependency source and probe the owning seam.
Use the canonical database population, explicit projection/environment, real SCI and
armed contracts. A publication regression uses a small complete fixture program.
During a cut, run focused regressions through the currently installed authority;
convert the authority and all callers before using the new API. The orchestrator
runs affected integration and platform checks at the completed cut. A full-suite
run belongs to final integration or a concrete cross-cut failure that warrants it,
never every edit, function conversion or small commit. Unknown selection evidence
must be repaired or explicitly widen that checkpoint, never silently skip coverage.
Retain platform/destructive isolation where its proof requires it.

Every commit leaves HEAD loadable and the host REPL reachable. Live verification
names hot reload, new fork or in-place adoption, and checks the actual program identity.
A browser-facing change includes observed paint. The orchestrator performs isolated
boot/platform proof for integration; lanes never reset `default`.

Record exact forms/results, timing/work counts, commit ids, touched paths, line deltas
and remaining proof limits in the owning landing note. A passing test is necessary
for its behavior, but does not prove speed, adoption, UI delivery or a deletion’s safety.

Before reset, preserve evidence and inputs needed for reconstruction. Database data is
disposable, but turns, tasks, results and private objects are lost unless reproduced.
After recovery, reconnect the tools and verify the fresh system; a reset does not
prove every intermediate commit was usable. Sweep only disposable roots without live
holders, never follow symlinks, and leave other assignments’ source edits intact.

**Host preflight:** the owner upgraded to macOS 27 before this revision. The recorded
old JVM is absent and its advertisement stale; no startup attempt has established an
OS regression. B1 records the actual host/JDK/Babashka environment and startup result
before attributing any failure. Platform recovery is separate from this document revision.

## 7. Decisions and proof gates

The plan does not reopen settled guarantees: explicit merge; task tests plus current
reaching tests; name-based supplied defaults; no silent pull truncation; no migration;
private objects in memory; conservative selection; no shell-based product self-modification.
Engineering proof gates belong at the operation they block, not in a chronology.

| Gate | Default path | What requires a decision or proof before changing it |
|---|---|---|
| Publication authority | Unpublished branch → transaction report → caller findings → guarded publication | A direct-to-current-src shortcut needs equivalent writer/concurrency proof and a changed owner ruling; otherwise do not implement it |
| Shared JVM behavior | Preserve existing context-specific validation and isolation | A1/B2’s two-generation and indirect-call probes decide whether a cheaper binding is sound; a failed probe retains the existing guarantee |
| Native heterogeneous storage | Preserve the declared codec until the fork supports it | A2 proves ordering, equality, retraction, history, reconnect and shape restrictions before type reset |
| Destructive tests | Preserve isolated immutable-snapshot execution | Prefer one isolated host mechanism serving platform and destructive work; prove confinement before reducing isolation, or retain the existing host until then |
| Error payload durability | Keep declared data and distinguish shown text from live objects | B3 presents the conflicting complete-rendering-blob requirement explicitly; do not silently remove durable evidence or serialize arbitrary live results |
| Profiling-driven tasks | Expose inclusive measurements with honest concurrent semantics | Choose a detector only after measurement supports its meaning; no inferred self time or invented window threshold |
| Candidate base updates | Preserve independent program and private objects | B2 verifies replacement-fork versus in-place updates; unsupported JVM interop is a typed boundary, not a fallback to another cluster’s code |
| First real namespace | Query surviving contract gaps and coverage after the cuts | Owner selects a bounded subject from current evidence, not an old citation ranking |
| Shared Git branch | Keep current branch separation | Owner chooses the merge checkpoint; no automatic push or main merge follows from a document edit |

A failed proof stops only its dependent production cut. It does not justify abandoning
independent, already grounded work. Where a new design decision creates broad changes,
bring three concrete options with guarantees and costs before editing production.

## 8. Documentation and issue retirement

The implementation plans are the current design, not an audit log. Incorporate a
correction into its owning section; keep reproducible historical evidence in research
or landing material outside `plan/`. The writer brief is background research, not a
second set of implementation instructions. Architecture explains mechanisms and links
directly to the specs; the proposed instructions hold durable working laws.

Retire old authorities only after their still-binding rules, probe forms and acceptance
conditions have a surviving home. Preserve the publication measurement script before
removing its old directory. Update incoming links in the same slice. Do not delete a
research directory that still supplies the only evidence for a claim.

The issue audit classified 369 notes: 165 subjects proposed for deletion, 95 defects
proposed to dissolve, 70 surviving defects, 26 standing class notes and 13 undecided.
These are planning classifications, **not 260 resolved defects**. Recheck each subject
at the corresponding code landing; close with the verified commit, preserve surviving
evidence, and promote real tasks only after required resets. B1 owns development-tool
defects; B3 owns the previously unassigned remainder. Existing notes stay until that
work actually establishes their fate.

Dependency removal follows current usage, not the old count target. Recompute references
after document/code retirements, inspect local fork changes and verify their remote
preservation before unvendoring. Keep the lane tooling needed for implementation until
a working replacement owns its job. The preparatory session also performs the owner-authorized fresh database index,
disposable tmp/build cleanup and platform verification, recorded outside this directory
in the fresh-start landing note. It then switches to `refactor/agent-platform`.
Source refactoring and task promotion begin in the subsequent implementation session.
