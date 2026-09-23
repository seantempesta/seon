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
has already changed. The [shared instructions](../../../../AGENTS.md) are active;
evergreen traps remain in the root instructions.

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

Concurrency is bounded by file ownership alone (ruled 2026-09-22, restated 2026-09-23: no lane count, no test-JVM slot, no prober cap): any number of assignments run at once while their file sets are disjoint; one file has one lane at a time — no regions (owner 2026-09-23: "do a better job orchestrating so lanes don't cross streams"); the orchestrator's ledger `tmp/orchestrator/file-ownership.md` assigns every path, and work on a held file goes to its holder or waits for its release. The orchestrator fills every ready file-disjoint step at every check-in; it never queues a step behind a lane it does not depend on.
No slice removes a safety mechanism before its replacement passes the
specific proof in its spec. A retirement and all callers are one commit. Remove the
superseded machinery in the same cut; do not keep dual implementations through an
extended migration. Small loadable commits may record progress inside a cut; they do
not each trigger a full integration cycle. This is a concentrated refactor, not a
multi-day sequence of small patches. If the evidence makes that scope infeasible,
bring the concrete scope/time tradeoff to the owner before extending it.

**Order inside the cuts.** The specs' stop rules describe producer→consumer seams;
this is the one order that satisfies all of them, so no lane waits on a lane that
waits on it. Each step is one loadable slice with every caller converted.

**Reprioritised 2026-09-22 (owner): the edit an agent just made is what it experiences, for
Seon agents and for filesystem agents alike, with the reaching tests as instant feedback.**
Two halves of one mechanism, file-disjoint, run in parallel from now:

| wave | agent half (`sci/eval.clj`, the lifecycle, the runner) | file half (`cluster.clj`, `cluster/source.clj`, the hook) | both |
|---|---|---|---|
| A (now) | 1.3d commit 1: overridden rows and affected callers interpreted from the branch; one custody function | 1.2b: the publication envelope deleted (manifest, seal, snapshot, digest); reload = `require :reload` of changed namespaces + dependents from the stored graph; per-namespace compile cost measured. **Measured 2026-09-22: the `seon.id` closure (90 namespaces) compiles in 3,042 ms.** **Ruled 2026-09-23 (owner, on Clojure's own semantics):** the reload set is per DECLARATION, not per namespace — a dependent namespace needs recompiling only when the changed declaration is a macro, protocol, type/record, inline or `definline` (`:seon.fn/defined-by`, indexed today); a `defn` body change reloads its own namespace only, because Var indirection carries it. clj-reload and tools.namespace reload dependents blindly for lack of that fact; we have it. This is Var indirection as Clojure designed it — a caller compiled against a Var sees the new body without recompiling; only macros, protocols, types/records and inlined functions are compiled INTO their callers — so nothing is invented and no library is added (the [reload data pack](../../../research/agent-platform/reload-into-the-repl-data-pack-2026-09-22.md) found none that does this, because none has the per-declaration fact). Lands in 1.2b's remaining slice; hook publication re-enables on the orchestrator's judgement once the one-request round trip is measured and the platform tier is clean (owner 2026-09-23) | 1.3e schema-retirement refusal; the boot provenance repair |
| B | 1.3d commit 2: candidate lifecycle (`branch!` → `open-branch!` → `fork-cluster-ctx` → `retire-branch!`), the agent branch attribute, a branch member on the eval tool | 1.5: the hook as one prepl request; hook publication re-enabled once A2's adoption rows are measured cheap | the program partition and host-bound facts in the indexer |
| C | 1.3d commit 4: one test request on the lifecycle — reaching set, in process, on a branch, unlink; then commit 5 deletes B4's machinery | 1.3 wrappers narrowed to "arm only the changed identities"; 1.4 projection as a read (no longer blocked: projection by argument) | the save-time gate: index changed declarations into the candidate branch → reaching tests there → green advances default and reloads; red returns the failures |
| D | a minimal merge (three-way diff over digests, intermediate branch, the gate, a named accept) | 1.4b, RESET batch 2 | cut 1 ends: platform tier, bulk tier once |

| Step | Slice | Frees |
|---|---|---|
| 1.1 | B3's constructor and declared error contracts, additive (`seon.error.refusal/diagnostic` keeps its name; `at` supplied; no key retired yet) | A1's wrapper output check, A2's guard conversions, every later caller slice |
| 1.2 | B1 writes `:seon.program/definition-digest` on every declaration row, additive beside the old key (RESET batch 1 marker) | A1 arming identity, B2 acquisition, B4 selection, C1, D1 |
| 1.3 | A1-1/1b: wrappers read the retained contract; per-context installation of the original (§3 above); the per-call scan and classpath population go with their callers | B2's context work, C1's hook point |
| 1.3b | **B1b: the operator and boot rewrite** ([spec](lane-b1b-operator-and-boot-rewrite.md)): three new files, one-JVM destroy (now `nuke`) under the store flock, lifecycle lock deleted, the old operator files and the boot span of `cluster.clj` deleted, eight drills; runs ALONE on `default` (breakage inside the slice ruled). Moved ahead of 1.4 on review (2026-09-21): A1's boot one-liners then edit `boot.clj` (≈ 420 lines) instead of `cluster.clj` (3,700); files disjoint from 1.1–1.3 | `default` on the new operator; every later boot-site edit lands in a small file |
| 1.3c | **A2's storage retention pulled into cut 1 as a parallel track (ruled 2026-09-22, for parallelism):** the measured cost that keeps hook publication paused is ~150 MB of rewritten persistent-set index leaves retained per adoption (2026-09-22: 87 MB → 7.4 GB in 15 min; 245 GB of commit/index ancestry under the epoch GC cutoff the night before). The track lands A2 c10 (GC through Datahike's `:datahike.gc/sweep-opts` and konserve `sweep!`, dry run then sweep), c12 (`:keep-history?` as a store-fixed key), the epoch-cutoff retention decision for the development store, and `:db/noHistory` on churn attributes (fault occurrence counts and timestamps); target: an adoption grows the store by its datoms, not by rewritten ancestry, measured with the publication script. Then, in the same lane, the §6.2 comparator proof, f1 (`:db.type/any` in the fork) and c2 (the in-writer decode and codec), which 1.4/A1-12 requires; c1 (read-evidence currency) stays with cut 2 because it edits `turn.clj`. Files: `db.clj` (codec region), `cluster/store.clj`, `cluster/registry.clj`, `reference-code/datahike` (fork), the GC seam — disjoint from 1.2 and the error-schema lane. Hook publication is re-enabled for lanes once the adoption cost is measured cheap | live adoption during lanes; 1.4's prerequisite; the disk-growth class closed |
| 1.3d | **Tests run as agents run — the one-mechanism track** — design: [one lifecycle](lane-realities-one-lifecycle.md) (2026-09-22; composes the installed seams, names each refactor and the three new functions) (ruled 2026-09-22; design from the [data pack](../../../research/agent-platform/tests-as-agents-data-pack-2026-09-22.md) and the [astra review](../../../research/agent-platform/tests-as-agents-astra-review-2026-09-22.md)).** Every primitive is already an installed Seon function: custody as a value (`seon.db/call-with-custody`), the connection-repointed fork (`seon.sci.eval/fork-cluster-ctx`), `registry/branch!`, `store/open-branch!`, `registry/retire-branch!`, `sci.eval/run-test`, `seon.fn/index!` on any connection; `sci/fork` measured 0.00123 ms against a 141 s base publication + 73 s test JVM. Six commits, agent seam FIRST, test callers LAST: **(1) agent execution correctness** — B2 §2a steps 1–3 in `sci/eval.clj`: `install-row!` computes `overridden` from `:seon.program/definition-digest` against the context's loaded commit, `affected` from `seon.fn/gate-sets`, both interpreted through `install-function-from-database!`, the silent `:jvm-fallback` a named refusal; `evaluate` calls `call-with-custody` instead of its open-coded copy; **(2) candidate lifecycle** — D1 §2a minimal handle: one entrance composing `registry/branch!` → `store/open-branch!` → `fork-cluster-ctx`, exit observed, `retire-branch!` unlinks (data stays until the retention GC collects; never a per-fork sweep); **(3) destination publication** — `publish!` takes the target branch as a request member and never implies the process-wide `require :reload` at `cluster.clj:1984` (`refresh-source!` split; the reload decision below); **(4) one test request** — `seon.test/run` = acquired execution value + recording connection + selection + bound, using the candidate lifecycle; the fixture calls the same entrance (P3/M8) and the base copy, `Held`, prewarm and the `run`/`run-owned`/`check` ladder leave; **(5) machinery deletion** — workers, claims, staged results, slots, `bin/test-fast`, duplicate selection; the platform/destructive host (66 tests reach the two `:seon.fn/destroys` owners) keeps the same request; **(6) integration** — the orchestrator proves shared execution, candidate publication, retention and platform behaviour, then rewrites B4/B2/D1 to the installed seams. Deleted from the plans: B4's independent capture/acquire/context/thread lifecycle and worker ownership, B2's tolerated reload tear and JVM fallback, D1's candidate-as-cluster startup. Kept: selection, per-member evidence, fixture semantics, recorder, actual-exit. B4 c1 as running (branch off the captured commit, base copy deleted) lands now; c2 waits for commits 1–3 | lanes and agents test in process on their own branch through one mechanism; the two test-JVM slots and the stale-base class close |
| 1.3e | **Schema retirement refuses while writers survive (ruled 2026-09-22 after the from-zero boot refusal on `:seon.db/process`):** the schema declaration writer (A1's `seon.schema` admission / the resource transaction) refuses to retract an attribute declaration while any program row names it in `:seon.fn/writes` or `:seon.fn/references`, naming the writers — the same refusal a function deletion gets from its callers. One class regression: retract a declared attribute with one surviving writer → typed refusal naming it; convert the writer in the same transaction → accepted. **Superseded 2026-09-23 (owner: "a schema change should not require a from scratch boot. Period."):** a commit touching `resources/seon/schemas/` is proven incrementally — its declaration transaction on a branch of a live store, the retirement refusal above naming surviving writers; a change that cannot be adopted incrementally is a publication defect, never a reason to boot from zero. Files: `schema/admission.clj` or the resource writer, its test | the retire-without-converting class closed at the write |
| 1.3f | **Stamp and hand-cache deletions from the [dependency-already-does-it audit](../../../research/agent-platform/dependency-already-does-it-audit-2026-09-23.md) (owner 2026-09-23: "get rid of bullshit stamps; so much of what we are doing is already available in Datahike"):** 26 rows, 7 DELETE / 9 REPLACE / 10 KEEP, ≈366 lines plus the `:seon.operator.lock/*` schema family. Nine rows are already scheduled (A2 c1/c2/c9/c12, A1 wins 1/2, §6.3 KEEP); the eleven NEW rows land as bounded slices as their files free, each with the audit's probe executed as the proof: publication `ReentrantLock` → Datahike's expected-head guard (B1, −63 + schema); fabricated transaction report (B1, −12); oversight owning-instance search (B2, −24); temporal-view metadata re-merge and projection staleness by `basis-t` → commit identity (with the memoized-projection slice, A1); packaged-population cache → `core.cache.wrapped` and the environment declaration delay (A1, −66); render-walk entity cache (A2/B2, −30); `:seon.cluster.eval/read-basis-transaction` (B2 with A2 c1, RESET); SHA-256 of a gitlink and gate file-digest docstring (B4 with commit 4). The rule is now AGENTS.md "No stamps" | ≈366 lines; one shape less to rebuild |
| 1.4 | A1-3 with the boot-site one-liners (in `boot.clj`) in A2/B1/B2/B4: the projection is a read; `load-projection` for the cold constructors only; A1-12 deletes the ambient transport (`call-with-projection*`, `handed-projection`, the registration delta) once A2 c2 removes the in-writer decode that binds a projection state | every reconstruction fallback; the last dynamic-var authority |
| 1.2b | **B1 commits 2–10 pulled forward (ruled 2026-09-22, for the shared platform):** manifest, seal and snapshot dissolved; publication = capture → compare → classify → lint → rows on an unpublished branch; adoption proportional to the changed declarations and their dependents (a docstring edit adopts in ≤ 700 ms). Files: `cluster/source.clj`, the publication span of `cluster.clj`, `fn.clj`; after 1.2 (digest identity); disjoint from 1.3/1.3c | incremental adoption; with 1.3c, hook publication re-enabled for lanes |
| 1.4b | A1-13: the schema-shape family leaves with A1-1b and A1-6 (no surviving reader); A1-7's fingerprint normaliser is withdrawn; RESET batch 1 | ≈ 30 K datoms, one RESET item |
| 1.4c | **D1 candidate acquisition pulled forward (ruled 2026-09-22):** a candidate is a Datahike branch plus a handle on the cluster's JVM (§7); a lane's edits publish to ITS candidate, never to `default`'s branch; merge is orchestrator-manual (git) until the D1 merge gate lands with B4's run and B1's lint in cut 3. Files: `sci/eval.clj` acquisition seam, new candidate files; after 1.4 | every lane on a current shared platform with its own branch; the hook hazard (a half-landed slice adopted by `default`) closed |
| 1.6 | **Flow owns running machinery — MUST-NOW** ([PRD](lane-flow-owns-running-machinery.md), `5b137109a`, Astra-reviewed twice): N1 core.async fork failure hook at the three flow catches + channel `:xform` refused, unconditional terminal settlement; N2 one deadline over request/lock/stop/join; N3 Datahike fork listener failure callback, identity-based retirement, bounded recording; N4 one process uncaught handler attributing only from carried evidence. Lands as the M4 slot of the owner's must-fix order (README §7 "Priority to namespace agents"), together with the one error route ([final design](../../../research/agent-platform/error-route-final-design-2026-09-23.md)); the PRD's LATER items follow cut 3, cut 4 last | the one error route; faults never lost; bounded stop |
| 1.5 | B1 commit 12: the hook as one request over the new prepl client; B3 commit 14 (`seon.search`) and commit 4 (kind/class) | one-request publication; reset batch 1 |
| 2.x | B4 commits 1–3 (fixture on the open store, one `run`, launchers retired); B1 commits 2–10 (manifest, seal, snapshot, caller lint, reset cold path); A2 c1/c3–c6/c8/c13, then c2 after the `:db.type/any` proof | cut 3 callers |
| 3.x | B3 task family and settlement on the existing turn loop; C1 on the wrapper; D1 branch-plus-handle candidates, merge, export; the demonstration; reset batch 2 | the first namespace agents |
| 4.x | B2 commits 1–14: context, turn, bounded completion, history, delivery, namespace page, cycles | — |

B1b may be the first implementation assignment, alone on `default`. Its early boot
rewrite preserves calls to the currently installed projection, acquisition and search
owners until their listed replacement slices land; it does not require implementing
steps 1.1–1.3 or 1.4/1.5 inside B1b. Those steps retain their dependency order.

Assign models by the work (owner, 2026-09-23): Opus 5.5 implements and researches,
through the Agent tool with `model: opus`; `gpt-6-astra` at medium reviews diffs and
cuts and writes PRDs and designs; no Fable lanes. New assignments set the model and
effort explicitly. A clear spec
includes owned paths, the replacement seam, every caller to convert and concrete
acceptance checks; the implementer should not have to invent a missing design. No high-effort
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

**The method (owner, 2026-09-22): forest, not trees.** This plan was researched and
ruled; the steps in §4 carry their implementation references. Work is DEEP CUTS in the
ruled order: delete the mechanism the step names, write the replacement from the step's
data-flow table, confirm it at the REPL against the running system, land it as one
loadable slice with its callers, then ask of each red test whether it is still
relevant — a test of deleted machinery leaves with the machinery; a test asserting a
retired assumption (an error kind, a hand-rostered fixture, an old fallback) is fixed
at the expectation; only a test asserting wanted behavior of a surviving seam is a
defect to fix at the owner. The orchestrator never runs the bulk tier between steps,
never triages a full-suite red list into lanes, and never launches a lane to fix a
red in a mechanism a later step deletes. The platform tier runs at step landings; the
bulk tier once at the end of a cut, and its reds are read through the same three
questions. A step is done when its own proof in its lane spec holds, not when the
suite is green. When a landing exposes breakage (a parked proc, a filling store), the
orchestrator repairs it at the owner as its own bounded slice and returns to the
step; the 2026-09-21/22 night showed the cost of doing otherwise: five full gates,
514 reds, and three lanes on tests of code the next steps delete.


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
| Wake routing through Flow; launcher kept — **ruled 2026-09-23 (owner)** | "Flow processes for datahike listening is a great idea especially since agents are also flow processes … so we can wake them on their channels." Verified: each agent is a Flow graph whose `:seon.agent/mailbox` proc reads a `:seon.agent/wake` port. One offer-only, non-blocking Datahike listener per cluster puts a payload-free wake on a sliding-1 in-port of a **router proc**; the router reads its own connection for what changed and wakes each affected agent on its own mailbox wake port (Astra's option 1 in [the PRD review](../../../research/agent-platform/review-flow-prd-2026-09-23.md)). A failed hand-off throws into Datahike through the fork's listener failure callback (router ruling, same day). The bounded launcher stays the admission owner for long work; B2 commit 7's retirement stands only on proof that another owner provides bounded admission | Work on the writer path; a per-agent listener; retiring the launcher without an admission proof |
| Priority to namespace agents — **ruled 2026-09-23 (owner)** | Must-fix before cut 3, in order ([triage](../../../research/agent-platform/flow-fact-pack-and-triage-2026-09-23.md) §B4): the running must-fixes (SCI rebuild + receipts, writer cost + restart hang, stop hang, `runtime_status`, 1.3d commit 5, nuke/reset, leaf publication, issue guard) → M3 (fault-write cost) → M9 (adoption correctness) → M4 (minimal one error route) → cut 3. The flow restructure ([PRD](lane-flow-owns-running-machinery.md)) follows except its must-now pieces. **The 1.4 projection sweep moves AFTER cut 3's first namespace agents** (owner chose it over README's earlier order; the memo already makes reads correct). The packaged-population cache becomes `clojure.core.cache.wrapped` keyed by the packaged forms' digest, lock deleted (a pure derivation may compute twice under a race) | Starting cut 3 before the must-fixes; the sweep before cut 3 |
| Schema change and reset — **ruled 2026-09-23 (owner)** | "Make the option straightforward. If we don't give a shit about the data find the cheap way to drop the problematic data, update the schema and ideally reapply it or leave it up to the agent to reapply it. Full reindexing should be avoided at all costs unless things are truly fucked and then yes we want a nuclear option." A schema difference adopts IN PLACE on every branch as it opens (`accrete-schema-population!`): an accretive change is transacted; any other change drops that attribute's data with Datahike's own `:db.purge/attribute` (never the entity; per-datom `[:db/retract e a v]` was refuted on a history store — the next write of the new type throws ClassCastException in the history index, run `243e9c9f3aee`, `3f273fe7c`), retracts the attribute and installs the new declaration (Datahike allows this once no current datom remains: `db/transaction.cljc:137`; measured 24 ms in memory); a retired attribute is retracted the same way. Reapply: rows derived from files are re-derived only for the declarations that write the attribute; agent data is left for the agent to reapply. 1.3e still refuses while a program row writes or references the attribute. `bin/seon reset` = unlink the cluster branch and fork a fresh one from the program rows (measured 4.3 s vs ≈190 s; retention GC collects). The NUCLEAR option — delete the store and rebuild from the files — survives under an explicit name for a truly broken store only, never as a proof or routine step. Owner, same day: "If things are fucked. Then nuke it and rebuild and pay the cost now" and "nuke CANNOT refuse. Nuke drops everything and always returns a stable proper state" — it rebuilds from COMMITTED inputs (no in-flight hunk can break it), surfaces any failure with its full cause, and never leaves a JVM with a deleted store. **Nuke vs reset (owner, same day: "nuke nukes everything including all caches and reset everything. because it is doing this nothing can stop it. A reset now just does that fast fork and refresh … and leaves all caches"):** `nuke` deletes the store AND every derived cache (dependency classes, kondo analysis, per-file analysis, published test bases, source archives) and rebuilds from committed files, so no stale state can block it; if committed HEAD itself cannot boot it falls back to the newest recent commit that does, reporting HEAD's failure first. `reset` forks a fresh branch from the program rows and keeps every cache. **Resume is not a boot (owner, same day: "cold boot and index ... not acceptable for resume"):** `bin/seon start` on an existing store whose program equals the files transacts nothing, indexes nothing, analyzes nothing, loads dependencies from the class cache, arms only the running program's namespaces, reads the memoized projection and skips a converged schema diff by identity; target seconds wall, ~2–3 s ready (measured 2026-09-23: 28.7 s wall / 11.1 s ready); later 14.5 s wall / 5.2 s ready (resume landing), 12.4 s ready unchanged (C1 scratch); a `start --head` move 54–185 s (ad41853a0). A `RESET NEEDED` mark in a lane spec that predates this ruling reads as "adopts in place": the schema difference is adopted by `accrete-schema-population!` on every branch as it opens, never a reset (orchestrator ruling from the owner's, 2026-09-23) | Full reindexing or a from-zero boot as the answer to a schema change; a store deletion as reset |
| Reset shape — **ruled 2026-09-21** — since 2026-09-23 this is the shape of `nuke --force`; `reset --force` is the fresh branch (row "Schema change and reset") | Reset is ONE JVM: `bin/seon` terminates the old JVM by exact (pid, start-instant), then launches one JVM with a destroy flag; that JVM acquires the store flock as its first act (`store.clj` `acquire-flock!`), deletes the store beside the retained lock file BEFORE any connection opens, republishes, forks and boots without releasing, and remains `default`. No lock handoff, no lifecycle lock, no lock logic in Babashka. `open-store!` (`store.clj:421`) gains the one destructive option: acquire → optionally delete → create/open. Honest guarantee: competing starts are excluded throughout delete → republish → boot, NOT across the preceding JVM-replacement gap; a start that wins that gap makes reset refuse without deleting anything or killing the winner. The flock does not name its holder's pid: the refusal reports store and lock paths plus independently verified process identity when the advertisement supplies it, else "holder unknown" — no ownership machinery is rebuilt for the message | The decisive drill on a scratch root: two competing processes; the loser never reaches deletion, the winner keeps the same lock through boot, killing the winner releases it. The bb-held lock and the retained lifecycle lock are rejected unless that drill exposes a concrete problem |
| Errors are explicit named schemas — **ruled 2026-09-22** | Every error VALUE satisfies exactly one named Malli schema over `:seon.error/base` (its distinguishing members are its meaning; no composite set of several schemas a value satisfies at once, no registry-derived complete union, no shape-based "is this an error" check in the wrapper). A function's arity declares its explicit output union (`[:or <ok> <error-a> <error-b>]`); the armed wrapper validates against THAT union; a caller branches on the explicit union or a declared member however it models it. Only the error-handling owners that must accept any error (recorder, renderer, normaliser) are typed on the bare base (B3 commit 5). "Which functions can return error X, where does it propagate, who owns it" are Datalog queries over arity returns and call edges — the same way tests are | The composite-set vocabulary and the registry-wide union derivation at `error.clj:1921` (to be renamed) retire in favour of "declared error schema" and the arity's declared error union; a producer found accepting a subset of what it can return declares its union, never a longer list |
| Recorded error identity and retained observations — **applied 2026-09-22** (orchestrator, from the ruling above) | The D13 signature carries the ONE declared schema name the producer supplies through recording custody — `[layer operation declared-schema throwable-class frame expected-key/shape path]` — never a set, never a structural fallback; stored observations without it are not migrated (RESET). A retained observation that is not a duplicate of a sibling member (a disposition, a cause, the offending arguments) is a declared domain member of the OWNING named error schema (a bounded closed enum where it is a disposition); it is never nested inside `:seon.error/data` and never written to `:seon.error/source`, which stays the normaliser's polymorphic input. The 1.1 constructor slice reconstituted the retired shape under both spellings ([review](../../../research/agent-platform/constructor-slice-diff-review-2026-09-22.md) F3/F4); the error-schema lane lands F3–F5 inside its slice, a constructor-repair lane lands F1/F2/F6–F14 after it | A dedup semantics that merges distinct named errors at one site is rejected; a shared cause slot needs its own declared key with a non-`:any` form and an owner |
| Development store snapshot retention — **proposed 2026-09-22, lane a2-storage-retention** | Declare `:seon.config.db/snapshot-window-ms`; development overlay 0 ms, cutoff from captured head timestamps and the largest declared cluster window; all current heads survive; absent cluster policy refuses implicit GC | Orchestrator ratifies the window and adoption-time collection policy after scratch bytes/key/time proof; temporal-history retention remains separate |
| Tests run as agents run — **ruled 2026-09-22 (owner)** | A test executes on the SHARED JVM through the same mechanism an agent gets: a Datahike branch off the captured commit (the fixture is a branch, never a store copy), its own forked SCI context, and the same custody injection — when the test's arity declares the database or connection, the runner hands it the branch's view, exactly as agent evaluation elides db/conn. A lane's edits reach that branch through the index function (`seon.cluster/refresh-source!` / `seon.cluster.source/publish!`) on the branch, not through a JVM of its own. Ordinary edits run the reaching set through `seon.test/run`; the platform tier alone keeps a fresh JVM. B4 c1 (branch fixture) and c2 (the one request on the shared JVM) move into cut 1 as 1.3d; c3 (launchers, slot script, worker machinery deleted) follows when lanes are on candidates (1.4c). Owner: "this is cheap and we are further testing our system"; and: **this is not a parallel mechanism** — the runner calls the same functions agent evaluation calls for branch acquisition, context fork, custody injection and retirement; where the agent seam as it stands cannot serve a test, the AGENT seam is redesigned first and the test caller follows; a test-side copy of any part of that path is a defect | A second test host beside the shared JVM for ordinary edits is rejected; destructive/platform members keep isolation until B4 §2e proves confinement |
| One JVM, many realities — **ruled 2026-09-22 (owner)** | The model is [clusters, branches and contexts](../../../seon/architecture/clusters-branches-contexts.md): a cluster is a branch with agents; default's program is the files and runs compiled; every other reality interprets its differing rows (B2 §2a); host-bound rows are a computed per-declaration fact; an agent's mode is its branch attribute (live = the cluster's branch, isolated = a branch off its head; the task sets it, the agent may branch and request merge); stability comes from the branch, no reload under an evaluation, contexts reacquired at turn start by commit id; a test is a one-body isolated agent; merge is program rows only through the gate (green reaching tests + contracts) then a named accept by root or the owner; filesystem lanes index into one shared candidate branch of default; reload = `require :reload` of changed namespaces plus dependents, sub-second, the existing mechanism minus 1.2b's machinery; retirement = unlink then GC. Supersedes the 2026-09-22 "captures its database value at start" wording: custody stays the connection; only tests capture a commit | A test-side or lane-side copy of any of these functions; a namespace roster for host-bound; a per-branch cleanup; git worktrees |
| The projection is a memoized function of the value — **ruled 2026-09-23 (owner: "can't we just memoize the function? use Clojure concepts, not our own")** | After a write the projection is not stamped by the writer and not carried through the transaction: it is `projection-of(value)` memoized with `clojure.core.cache` (Datahike's own dependency and its schema cache's seam) keyed by the value's `:cache-context`, bounded LRU, bound declared as data; a miss derives from the value's declaration rows; `load-projection` is that derivation; `carried-projection` = look up or derive. Batching, late commits and concurrency are Datahike's business. Rules out the three scopes the 1.4 writer lane offered (single-flight writes, a fork change to the commit seam, deferral) | A second cache library, a metadata stamp on the writer path, a candidate projection threaded through `row-tx` |
| Native heterogeneous storage — **§6.2 ran 2026-09-22, codec retained** | The comparator proof failed (map replacement throws in `cmp-temporal-datoms-eavt-quick`; `cmp-nil` equates unequal maps; [evidence](../landing/lane-a2-storage-retention-2026-09-22.md)); option 1 ruled: the codec, in-writer decode and population plumbing stay. 1.4/A1-12 therefore removes the projection BINDING, not the codec: the decoder receives the projection as an argument from the writer that already holds it (values carry their world), and the dynamic-var transport still leaves. f1/c2 return only with a bounded, proven value language (option 2) as later work | A partial comparator patch (class-name, hash or printed order) is rejected |
| Storage retention — **ruled 2026-09-22** | A2 c10/c12 landed (`1cc00a4a5`, `289c9b587`, `907b231fe`, `9d4fa01a2`): GC through Datahike's sweep with a dry run; `:keep-history?` store-fixed and adopted by the library; retention = a required `:seon.config.db/snapshot-window-ms` on every captured head's configuration row, the largest window anchored to the newest head, development 0; implicit collection refuses a missing policy rather than retaining every ancestor; no cleanup job, no adoption hook. `:db/noHistory` on the fault-occurrence `count`/`last-at` attributes lands in the error-schema lane's slice (its resource), RESET batch 1 | An idle-time or size-based retention is rejected; hook publication stays paused until the measurement script's adoption rows run on a tree that loads |
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
directly to the specs; root `AGENTS.md` holds durable working laws.

**Shared instructions are activated independently of the remaining B1b integration
work.** The orchestrator owns root `AGENTS.md` and the rewritten testing skill;
installed commands are distinguished from future targets. `CLAUDE.md` remains its
symlink, not a second instruction authority. Testing and REPL guidance use §6's
cut-level verification policy; helper details live in the skill rather than being
duplicated in the root file. The dropped-rule review is complete; restored evergreen
rules remain in the root because agents may not load the specialized skills.
The development root now runs the new operator; the temporary breakage exception
has been removed from root `AGENTS.md`. Remaining platform-admission and app-connection
verification limits are recorded in the B1b integration landing note.
Each later cut updates the affected instructions with its implementation; B4's
runner instructions activate only when that runner and its callers actually land.
Running lanes receive the changed guidance explicitly; new lanes read it at launch.
The testing skill labels each rule as enforced by the installed fixture
or runner, partially enforced, or an author responsibility, citing the actual seam.
Explicitly cover duration failure, refused fixture writes through `transacted!`,
arming drift, and hand-written fixture maps. Do not claim that validating a map's
shape detects its hand-written origin or guarantees a faithful fixture.

The testing guidance must require positive setup evidence, a specific expected
refusal plus unchanged-state evidence, and assertions that fail when the subject is
absent. Concurrency tests exercise the contested identity and actual process/store
boundary; a generic nonzero exit or a valid local lock object alone is insufficient.
An execution exceeding its declared test bound fails. Derive each bound from the
measured operation and put its reason beside the declaration; distinguish cold and
warm work. Await named events and confirm actual termination before cleanup.
Keep one regression per behavior class;
ordinary fixtures do not re-index the program to test a small change.

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
