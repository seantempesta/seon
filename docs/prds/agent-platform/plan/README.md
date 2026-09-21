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

**Isolation includes loaded behavior.** Reuse functions whose definitions and executable dependencies match the loaded runtime; interpret context-specific redefinitions and their affected callers in the cluster or agent's SCI context. B2 §2a owns this rule as five steps: `overridden` = rows whose stored source or namespace bindings differ from the JVM's loaded commit, plus a fork's private redefinitions; `affected` = their reverse closure over `:seon.fn/calls` and declared `:seon.fn/invokes`, computed at acquisition or change (313 ms for fifteen seeds over 34,363 edges); both are interpreted from stored source and armed per context; everything else binds the JVM copy. **Contract-only changes can reuse the compiled body, but not bypass the new contract.** A copied JVM caller still calls the host callee and its host contract. Treat context-specific contract differences as affected-call seeds unless the existing invocation seam demonstrably applies the context's contract to indirect calls. The focused SCI/Malli probe rejects a direct invalid argument, accepts it through the copied caller, and rejects it after only the caller is interpreted. Do not promise that contract work avoids caller closures before that guarantee is implemented. A copied JVM callable retains JVM call paths, so matching its own digest alone does not make an overridden callee safe (Codex's `copy-var*` probe, astra feedback note). Per-context wrappers preserve direct contract ownership; the JVM Var wrapper's profiling cell is the **host** cell, attributed to no cluster. An affected caller that cannot be interpreted is an owner decision (§7). Every override reports its interpreted-closure size; a core leaf's is a quarter to two fifths of the program (`seon.db/q` 1,116 of 4,603 functions). No per-call full-program scan is required. Evidence: [loaded reuse note](../../../research/agent-platform/loaded-reuse-bounded-completion-store-fixtures-2026-09-21.md).

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
Preparation is limited to the capabilities agents need to perform the refactor:
correct test execution and recorded reuse, usable JVM/SCI REPLs, real database
reads and writes with carried contracts, and core boot/runtime access. It does not
require making every legacy test or feature green before replacing its mechanism.
Record unrelated failures for their owning cut. An unavailable core capability or
unsound test verdict remains a blocker; an unrelated old expectation does not.
Preparation first proves dependency selection using the real analyzer and recorded
test authority: an ordinary leaf change selects its actual dependent tests, a shared
function change selects the wider justified set, and an unchanged green request
executes no tests. Explain selected paths and distinguish lexical calls, callable
dependencies and descriptive symbols before trusting a selective gate. Sample leaf,
intermediate and shared functions; broad shared-function samples do not establish an
average for ordinary edits.

**Ruled 2026-09-21: plumbing first, the turn and context last.** Every mechanism
whose deletion carries no agent semantics lands before anyone opens `turn.clj`; the
owner's experience is that every change to the turn or context code is a slog, so the
platform is made solid and small around it first, and the namespace agents start on
that platform. B2's context, turn, read-evidence and rendering work is the LAST cut.

| Cut | Coherent removal and replacement | Exit condition |
|---|---|---|
| 1 | **Plumbing, no agent semantics:** the operator and boot rewrite (B1 §2c), the lifecycle lock, the hook as one request, the carried projection and its ambient transport (A1-3/4/12), the schema-shape family (A1-13), `seon.search`, the error kind/class sites (B3 commit 4), the parser pre-checks (A2 c13), the B3 constructor contract (1.1) and the definition digest (1.2) | `default` restarts through the new operator; a docstring edit publishes through one request; the projection has one transport; reset batch 1 |
| 2 | **Tests, publication, database:** B4's machinery deletion and the one `seon.test/run`; B1's manifest/seal/snapshot dissolution and caller lint; A2's currency, codec, pull and validator narrowing | An unchanged green request executes nothing; a docstring edit adopts in ≤ 700 ms; complete validation proportional to the report |
| 3 | **Tasks, profiling, candidates on the existing turn loop:** B3's task family and settlement, C1 on the wrapper, D1's branch-plus-handle candidate, merge and export | Trigger→task→agent→merge→export demonstrated with the turn loop as it is; the first namespace agents start here on the 433 untested and 3,145 uncontracted functions |
| 4 | **Turn, context, rendering, last:** B2's acquire-once/fork-retained context, one turn function, bounded completion, the walk as history, whole-view delivery | Private objects survive turns; an ordinary write installs 0 rows; one history; two tabs converge |

At most three editing assignments run concurrently; exact file ownership decides what
can overlap. No slice removes a safety mechanism before its replacement passes the
specific proof in its spec. A retirement and all callers are one commit. Remove the
superseded machinery in the same cut; do not keep dual implementations through an
extended migration. Small loadable commits may record progress inside a cut; they do
not each trigger a full integration cycle. This is a concentrated refactor, not a
multi-day sequence of small patches. If the evidence makes that scope infeasible,
bring the concrete scope/time tradeoff to the owner before extending it.

**Order inside the cuts.** The specs' stop rules describe producer→consumer seams;
this is the one order that satisfies all of them, so no lane waits on a lane that
waits on it. Each step is one loadable slice with every caller converted.

| Step | Slice | Frees |
|---|---|---|
| 1.1 | B3's constructor and declared error contracts, additive (`seon.error.refusal/diagnostic` keeps its name; `at` supplied; no key retired yet) | A1's wrapper output check, A2's guard conversions, every later caller slice |
| 1.2 | B1 writes `:seon.program/definition-digest` on every declaration row, additive beside the old key (RESET batch 1 marker) | A1 arming identity, B2 acquisition, B4 selection, C1, D1 |
| 1.3 | A1-1/1b: wrappers read the retained contract; per-context installation of the original (§3 above); the per-call scan and classpath population go with their callers | B2's context work, C1's hook point |
| 1.3b | **B1b: the operator and boot rewrite** ([spec](lane-b1b-operator-and-boot-rewrite.md)): three new files, one-JVM reset under the store flock, lifecycle lock deleted, the old operator files and the boot span of `cluster.clj` deleted, eight drills; runs ALONE on `default` (breakage inside the slice ruled). Moved ahead of 1.4 on review (2026-09-21): A1's boot one-liners then edit `boot.clj` (≈ 420 lines) instead of `cluster.clj` (3,700); files disjoint from 1.1–1.3 | `default` on the new operator; every later boot-site edit lands in a small file |
| 1.4 | A1-3 with the boot-site one-liners (in `boot.clj`) in A2/B1/B2/B4: the projection is a read; `load-projection` for the cold constructors only; A1-12 deletes the ambient transport (`call-with-projection*`, `handed-projection`, the registration delta) once A2 c2 removes the in-writer decode that binds a projection state | every reconstruction fallback; the last dynamic-var authority |
| 1.4b | A1-13: the schema-shape family leaves with A1-1b and A1-6 (no surviving reader); A1-7's fingerprint normaliser is withdrawn; RESET batch 1 | ≈ 30 K datoms, one RESET item |
| 1.5 | B1 commit 12: the hook as one request over the new prepl client; B3 commit 14 (`seon.search`) and commit 4 (kind/class) | one-request publication; reset batch 1 |
| 2.x | B4 commits 1–3 (fixture on the open store, one `run`, launchers retired); B1 commits 2–10 (manifest, seal, snapshot, caller lint, reset cold path); A2 c1/c3–c6/c8/c13, then c2 after the `:db.type/any` proof | cut 3 callers |
| 3.x | B3 task family and settlement on the existing turn loop; C1 on the wrapper; D1 branch-plus-handle candidates, merge, export; the demonstration; reset batch 2 | the first namespace agents |
| 4.x | B2 commits 1–14: context, turn, bounded completion, history, delivery, namespace page, cycles | — |

B1b may be the first implementation assignment, alone on `default`. Its early boot
rewrite preserves calls to the currently installed projection, acquisition and search
owners until their listed replacement slices land; it does not require implementing
steps 1.1–1.3 or 1.4/1.5 inside B1b. Those steps retain their dependency order.

Assign models by the work: `gpt-5.6-sol` at low effort for fully specified
conversions, deletions and caller updates; `gpt-6-astra` at low effort for bounded
repairs that still require diagnosis; Astra at medium for architectural decisions
and cut review. New assignments set the model and effort explicitly. A clear spec
includes owned paths, the replacement seam, every caller to convert and concrete
acceptance checks; Sol should not have to invent a missing design. No high-effort
implementation lanes. The orchestrator owns integration and reviews each cut.

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

**What this plan does and does not achieve on size, stated plainly.** The sum of the
specs' own targets is ≈55,000 source lines: a 40 % cut, not the tenfold norm the owner
named. The [deep review](../../../research/agent-platform/deep-review-wins-2026-09-21.md)
added nine mechanism deletions (ambient projection transport, the schema-shape family,
the lifecycle lock, the hook as one request, call preparation, the provider error
classifier, the database owner's parser pre-checks, candidate-as-handle, a refusing
Malli `:report`) worth ≈ 5,000 further lines, so the honest sum is ≈ 50,000. The largest surviving files after the cut are still `turn.clj` (≈3,700),
`render/web.clj` (≈2,500), `sci/eval.clj` (≈2,500) and `fn.clj` (≈2,300). A
tenfold result (10,000–15,000 lines) is not reachable by deleting mirrors alone; it
needs a second dissolution pass over the surviving mechanisms — the turn loop, the
web page and the indexer as one function each — which this plan schedules as the
namespace agents' work after D1, on a codebase whose contracts, tests and graph make
that pass safe. Any claim of a tenfold reduction before that pass is false.

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

**Commits inside a cut are not test-gated** (owner, 2026-09-21: "this is not meant
to be a fix one thing, run the entire test suite and have it take forever. We are
breaking shit temporarily and ripping out a lot of bad code; if the plans are good we
repair it and write better tests to replace the garbage we were testing before").
Static reach is nearly saturated on the live graph (after the dispatch declarations
`seon.db/transact!` still selects 1,398 tests, `seon.id/symbol-in` 1,286;
`seon.id/valid?` 18), so "run the tests reaching my change" on `schema.clj` or
`db.clj` is the suite, at hours per commit. Therefore: a commit's gate is that HEAD
loads and the named REPL probe answers on `default`; a deleted mechanism's tests are
deleted in the same commit, never repaired first; the replacement's tests are written
at the END of the cut, one regression per behaviour class, on the canonical fixture
with real SCI and armed contracts; the orchestrator runs the platform tier once per
cut and the reaching selection once at the cut's end through the request that exists
by then. Breakage between steps of a cut is expected and named in the landing note;
the REPL stays up so it can be seen. No lane runs a suite, ever.

Except B1b's explicitly ruled temporary breakage (§7), every commit leaves HEAD
loadable and the host REPL reachable. Live verification
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

Four of them were re-examined with the owner on 2026-09-21 and stand, with numbers:

- **Every function contracted and armed, in every context** (§1j reaffirmed): the
  wrapper is optimised (A1 §1: 1,157 ns → ≤ 2× bare), never disarmed.
- **Additive context with one currency mechanism** (A2 c1, B2 commit 5): the prefix
  never changes because evaluations are immutable facts and a changed read is APPENDED,
  never rewritten; detecting the change is Datahike's own per-attribute revision
  comparison (0.7 ms for 435 reads); the other two arms and the replay leave. That is
  the whole mechanism; nothing simpler keeps provider cache hits.
- **The merge gate is the reaching set** (C2 reaffirmed), sized by the measured
  distribution on `default`: tests reaching a function are p50 **24**, p75 268, p90 598,
  p95 1,257, max 1,779; 2,256 of 4,603 functions are reached by fewer than 20 tests and
  337 by more than 1,000. The average merge runs tens of tests in process (37 ms fixture,
  5 s bound); a core change runs the suite, as the owner wants. **433 functions are
  reached by no test** — the first detector's population.
- **The operator and boot are rewritten, not cut** (B1 §2c): ≈ 9,000 lines of lifecycle
  plumbing replaced by ≈ 900 written from the data-flow tables, old files deleted in the
  same slice, revertable by `git revert`. Spec: [B1b](lane-b1b-operator-and-boot-rewrite.md).
  Ruled 2026-09-21 on review: (a) the slice MAY leave `default`'s MCP, REPL and boot
  unusable between its commits and may change the tools; everything is restored and
  drilled at the slice boundary, and no other lane uses `default` while the slice is
  open; (b) ≈ 900 is the measured target, not a ceiling — the landing note reports
  actual `wc -l` per new file and explains any overrun, and the right design wins
  over the count (owner: "I don't want the wrong design because it's 901 lines").

| Gate | Default path | What requires a decision or proof before changing it |
|---|---|---|
| Publication authority | Unpublished branch → transaction report → caller findings → guarded publication | A direct-to-current-src shortcut needs equivalent writer/concurrency proof and a changed owner ruling; otherwise do not implement it |
| Shared JVM behavior | Per-context wrapper installation over the original callable plus the five-step eligibility rule (§3); the JVM Var armed once as the host wrapper | The confirmation probe (two clusters, different contracts, direct/indirect/host calls, a changed callee under an unchanged caller) runs before the per-call scan is deleted; a failed probe keeps the scan and reports the case |
| An affected caller that SCI cannot interpret — **ruled 2026-09-21** | Refuse the override in that context, naming the host-bound caller; any first-party namespace may be overridden when its affected closure interprets cleanly; no namespace roster. Agents' new functions and schemas over database data have no compiled callers, so nothing is interpreted or refused for them; only 15 of 109 source namespaces carry host-defining forms, mostly platform plumbing | The typed `bypassing-callers` alternative and best-effort JVM fallback are rejected |
| Candidate shape — **ruled 2026-09-21** | A candidate is a Datahike branch plus a handle hosted by the cluster's JVM (`graph-definition` is a pure function of agent id and handle; measured branch 74.65 ms + open 25.62 ms + fork 0.021 ms versus cluster start ≈ 25 s). A CLUSTER remains the unit of a shared program and its agent population; several clusters of agents may run; a candidate explores cheaply and merges back to its cluster's branch (owner: "a cluster of shared agents with shared functions … an individual agent cheaply explores other ideas that we can merge back"). Supersedes the 2026-09-19 "separate candidate clusters" wording | The composed proof still owed: one armed candidate agent on a handle, scoped effects and faults on the candidate branch, recovery, and the shared page unchanged (D1 §2a) |
| Bounded completion | Two declared bounds (`time-limit`, then a bounded wait on the body's actual-exit signal after `cancel true`), then abandon-and-disarm with the live thread recorded (B2 §2b) | Keeping the 252-line observer is the fallback; an unbounded second wait is rejected |
| Reset shape — **ruled 2026-09-21** | Reset is ONE JVM: `bin/seon` terminates the old JVM by exact (pid, start-instant), then launches one JVM with a destroy flag; that JVM acquires the store flock as its first act (`store.clj` `acquire-flock!`), deletes the store beside the retained lock file BEFORE any connection opens, republishes, forks and boots without releasing, and remains `default`. No lock handoff, no lifecycle lock, no lock logic in Babashka. `open-store!` (`store.clj:421`) gains the one destructive option: acquire → optionally delete → create/open. Honest guarantee: competing starts are excluded throughout delete → republish → boot, NOT across the preceding JVM-replacement gap; a start that wins that gap makes reset refuse without deleting anything or killing the winner. The flock does not name its holder's pid: the refusal reports store and lock paths plus independently verified process identity when the advertisement supplies it, else "holder unknown" — no ownership machinery is rebuilt for the message | The decisive drill on a scratch root: two competing processes; the loser never reaches deletion, the winner keeps the same lock through boot, killing the winner releases it. The bb-held lock and the retained lifecycle lock are rejected unless that drill exposes a concrete problem |
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
These are planning classifications, **not 260 resolved defects**. Owner ruling
(2026-09-21): keep only the notes whose subject will still exist. Class A (165, the
subject itself is deleted by a spec) is deleted now — its one-line claim and location
survive in the audit table, which the lane spec's tests section inherits. Class B (95,
the defect is dissolved by a spec) stays until that spec lands, then closes with the
verified commit. Classes C, D and E stay; C is grouped by lane in the audit so the
implementing lane inherits each defect. B1 owns development-tool defects; B3 owns the
previously unassigned remainder. No bulk promotion of notes to tasks: a task is
created when an agent takes a note up, never for the directory.

Dependency removal is done: 89 unreferenced or history-only repositories were unvendored
on 2026-09-21 after the usage audit and a remote-preservation check (20 remain; see
`docs/seon/architecture/reference-code.md`). Recompute usage after the clean write and
remove any repository the surviving documents no longer cite. Keep the lane tooling
needed for implementation until a working replacement owns its job. The preparatory session also performs the owner-authorized fresh database index,
disposable tmp/build cleanup and platform verification, recorded outside this directory
in the fresh-start landing note. It then switches to `refactor/agent-platform`.
Source refactoring and task promotion begin in the subsequent implementation session.
