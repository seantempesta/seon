---
type: research
status: draft
created: 2026-09-21
tags: [goals, rulings, vocabulary, archive-extract, agent-platform, namespace-agents, program-graph]
---

# Durable goals and rulings, extracted before the old plan directory is archived

Scope: `docs/prds/steward-platform/plan/` (27,848 lines, 31 files) plus the
context-generation turn-loop PRD and the two architecture pages. Everything
below is a GOAL, a FUNCTIONALITY TARGET, an OWNER RULING THAT DEFINES THE
PRODUCT, or a VOCABULARY decision. Schedules, lane plans, wave sequencing and
dated coordination are deliberately not carried forward; §7 says what that
costs. `docs/prds/steward-platform/research/` (449 files) is NOT part of the
archive — every `file:line` below still resolves.

Short paths used in sources: **README** = `steward-platform/plan/README.md`;
**PRD-PF** = `plan/program-facts-are-the-runtime-prd-2026-09-17.md`;
**NSA** = `plan/namespace-agents-plan-2026-09-19.md`;
**DEC** = `plan/owner-decisions-2026-09-17.md`;
**TEST** = `plan/test-system-is-the-database-prd-2026-09-17.md`;
**ERR** = `plan/error-conversion-prd-2026-09-20.md`;
**MALLI** = `plan/malli-native-bridge-prd-2026-09-20.md`;
**1JVM** = `plan/one-jvm-publication-redesign-2026-09-22.md`;
**NDM** = `plan/namespace-data-model-2026-09-16.md`;
**ISSUE-FAM** = `plan/issue-family-spec-2026-09-16.md`;
**TURN** = `docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`;
**ARCH** = `docs/seon/architecture/architecture.md`; **UI** = `docs/seon/architecture/ui.md`.

---

## 1. The mission, in the owner's words

> "The goal of Seon is to become the best software generation platform ever to
> exist and we are going to do it by designing a programming environment that
> is AI first, not human first. Fuck editing files and compiling code. The
> entire program graph is in the database and it's queryable and we know every
> function and call edge and what each input and output is and what tests
> exist and on and on. We need to leverage this to make it easy to refactor and
> impossible to cause certain software failures (like removing a function
> that's still in use, or allowing a function to be used if it's violating the
> schemas). Help me find all of these connections and make them unbreakable
> AND easy to teach agents how to refactor with the data."
> — owner, 2026-09-16 (PRD-PF:36-50)

> "Focus on writing good schemas with solid names for solid data that we need
> to store and link up correctly in our Datahike graph, in order to later
> define tasks on having this data around. … Issues rot because they are not
> linked, connected and assigned. The namespace-centric viewpoint makes this
> data discoverable, actionable, and later automatically triggered."
> — owner, 2026-09-16 01:20Z (NDM:10-16)

> "It was always supposed to be a single JVM and we pay the cost of startup
> once. We pay the cost of indexing once and then it's incremental. Stop
> fighting the tools they are already optimized." And: "start making tests
> fail if they exceed reasonable time limits. None of this shit should require
> minutes of computation." — owner, 2026-09-22 (1JVM:10-14)

> "Everything is data. We can't fuck this up." — owner, 2026-09-19 (NSA:21)

> "focus on fixing the highest value things first… we don't need 100% green and
> once the system is stable we can use the system itself (namespace agents) to
> fix the remaining issues" — owner, 2026-09-23 03:00 (unsettled.md:6855)

> "I want a faster schedule. I want to finish the problems and start building
> later today." — owner, 2026-09-23 02:20 (unsettled.md:6820)

> "no fake tasks … index all the real tasks we need to achieve this level of
> self building and repair." — owner, 2026-09-16 01:05Z (README:97-98)

**The target loop** (README:257-270, quoted near-verbatim, with the 2026-09-19
D1 renames applied): a task is an entity — instructions, namespace, subject, a
set of deftests that define done, a budget. Starting it creates an agent
entity with its first turn open, in the same transaction. The agent iterates
until every test in the set is verified on the current reach digest; it may add
tests, never remove them. Work runs on forked clusters, one branch per batch; a
finished batch merges its changed program entities into the shared branch, the
merge gate runs exactly the tests whose reach changed, and approved entities are
written back to their files by exact span. Signals derived from facts open the
tasks: red tests, untested and uncontracted functions, recurring faults, missing
render pairs, ugly output, lint findings, indexed issues, and a user's
unanswered messages. A namespace's agents are the engineers responsible for all
of it; triggers and scheduling plug in as callers of `start!` later.

---

## 2. Functionality targets

Acceptance conditions are as the archived docs state them. "Measured state"
only appears where a doc records a number.

### 2a. Program facts are the runtime

| Target | Measured state today | Acceptance condition | Source |
|---|---|---|---|
| The database holds everything needed to run agents; source files are one projection in (indexing) and one out (write-back) | — | One set of Malli schemas/attributes/refs describes code whichever seam produced it; the seam is a provenance attribute and nothing else | PRD-PF:9-19, R2 :66-68 |
| Indexing source and later updating that code in the database are the SAME operation on the same entity by the same identity | — | One analyzer, one row constructor, one writer per direction; no second analysis, no hand-built rows, no compatibility arity (I6) | PRD-PF:R3 :70-71, I6 :697-698 |
| An agent authors the shared cluster program with ordinary Clojure forms; every agent on that branch sees them | implemented for contracted `defn` (`sci/eval.clj:938`, `turn.clj:1775`) | A `defn` WITHOUT a `:malli/schema` contract is not installed and the agent is told so | PRD-PF:R1 :58-63, DEC:126-137; TURN:857-867 |
| Every function carries a complete Malli contract, private included; instrumentation arms every contracted function | 5,191 functions, 3,161 private, **16** private with a contract; 520 call a `seon.db` read directly, 352 of those private and uncontracted; 69 public contracts declare a bare `:map` output | The public-only rule is retired; arming covers private; an uncontracted eligible function is a POSITIVE finding fact at publication and newly asserted uncontracted identities are refused | PRD-PF:§1j :352-377; NSA wave 2a |
| Every function's output contract names the exact error schemas (or `:or` union) it can return | 937 src / 882 test / 51 bin+script references to the retired kind machinery at `bcb0ef256` | The armed wrapper refuses a returned error satisfying none of the declared schemas; a value satisfying a declared one AND others besides passes (maps are open); the declared union is checked against the body-derived set by the program graph | PRD-PF:§1q :505-517; ERR:95-116, :287-292 |
| Acquisition decides by digest equality, not authorship | — | A row references the loaded JVM Var when its analyzed-source digest equals the core row's; SCI interprets only genuine differences | PRD-PF:§1s :544-562 |
| Write-back: accepted definitions to disk by exact span, gated | not built | The ordinary indexer reproduces the merged entities BYTE FOR BYTE from the written files; targeted + platform + fresh-boot gates; path-limited commit | README F1-F3 :344-348; PRD-PF:S5; wave-5 spec:122, :232 |
| Every write validates all inputs, always | a partial update is refused for keys the entity already has; writers work around it with `[:db/add …]`, the less-validated grammar | Validate `(merge existing supplied)` inside a `:db.fn/call` so the serial writer makes the same decision it makes about upsert-versus-create; a failed validation ABORTS the whole transaction | DEC:437-489 (decision 6); PRD-PF:F2 :225-231 |
| A deletion that severs a known connection is refused, and the refusal is the refactoring data | — | The retraction and the repair land in ONE transaction verified on `:db-after`, or the deletion refuses and hands back the affected set | PRD-PF:§1g :300-309; mission :36-50 |

### 2b. Speed (the algorithmic targets)

The invariant: a cold start pays JVM boot and one complete analysis; after
that an edit costs work proportional to the changed declarations and their
callers, and a request with nothing changed is a comparison of two commit ids
(1JVM:17-23). "Landed" = the measurement script row moves AND the platform
tier is green (1JVM:72).

| Case | Measured at `7924f4dae` | Target | Latest recorded | Source |
|---|---|---|---|---|
| From zero, complete publication | 175 s | ≤ 60 s | — | 1JVM:29-34 |
| Fork a cluster | 29 s (two JVM boots) | < 1 s (a Datahike branch) | **0.34 s, landed** | 1JVM:29-34, :60 |
| Boot to ready | 43 s | measured, paid once | 24.0 → 16.2 s | 1JVM:29-34, :67 |
| No change at all | 149 s | < 1 s (two commit ids compared) | **1.46 s, landed** | 1JVM:29-34, :62 |
| Docstring edit, non-core file | 483 s | ≤ 5 s | 12.7 → 7.14 → 5.42 s, **open** | 1JVM:29-34, :67 |
| Docstring edit, core file | 419 s | ≤ 5 s (no "toolchain" class) | open | 1JVM:29-34 |
| One complete clj-kondo analysis of 381 files | 10.2 s (the tool); our layer on top is the **277 s** | one changed file ≤ 2 s; clj-kondo's cache is the only analysis cache | — | 1JVM:42-46, :84-97 |
| Every test has a bound and FAILS when it exceeds it | — | A default per-test bound declared once in `seon.test.edn` (proposed 5 s), enforced by the runner as a failure with the measured time; `:seon.test/long-ms` the only way up and must carry its reason | landed | 1JVM:110-116, :68 |
| Schema shape rows store the authored form | expanded forms 65 MB, largest 1.4 MB, multiplied to **4 GB live** by Datahike's node cache | authored form with references as keywords | 72.9 MB → **103 KB**, largest form 61 chars | 1JVM:74 |
| One admitted generation of compiled Malli schemas carried by DB values and the environment | a 4-line Malli core scope repair took 100 candidate validations 644.447 → 22.735 ms and enumerations 2,000 → 0 | Repeated validation/explanation must not invoke the named-declaration compiler or allocate full registries — count provider calls and copied entries, not scratch timings | MALLI:17-19, :534-541 |
| A reset republishes in seconds, not minutes | reset 2026-09-23: 325 s total, republish 260 s, of which "program population compiled: 30320 entities" is **117 s** (~4 ms/entity, suspected O(n²)) | the algorithm's work, named — never "which path it took" | unsettled.md:7324, :7330 |
| No test exceeds its bound; a slow test is an algorithm finding | platform tier: 96 tests, median 1.1 s, the 23 over 5 s hold **94%** of 29.6 min; complete tier: 1,982 tests, median 3.4 s, 495 over 5 s holding 67% of 4.0 h, slowest exactly 270,007 ms (a bound firing) | A test exercising publication publishes a SMALL fixture program, never `src/`; a test never waits out a bound, it awaits the exact terminal event | unsettled.md:6907-6919 |
| A docstring edit is sub-second | 5.42 s (open) | Four algorithms, not a number: callers re-linted only when a contract digest changed; only the changed namespace reloaded (Vars are indirection); note indexing per changed path; the validator reads only touched rows | unsettled.md:7084-7086 |
| Derived state is computed once when the immutable database value is made and CARRIED on it | heap: Datahike's `CachedStorage` LRU, bounded by node COUNT (1000), held 852 K strings / 3.8 GB ≈ 47 copies of the database's 81 MB of string content | no second cache; the law answers the caching question | unsettled.md:7016, :7189 |
| Arming N functions performs ZERO named-declaration compilations | — | counted at real construction seams; an unchanged wrapper retains identity | bridge-step5 spec:10-18 |

### 2c. The test system is the database

| Target | Measured state today | Acceptance condition | Source |
|---|---|---|---|
| The database runner is the ONE gate; `bin/test` is only a launcher | `bin/test` is 807 lines and computes selection and tally in the shell | The launcher "contains no logic that is not 'start a JVM and hand it a request'"; the printed tally equals `(seon.test.runner/render-run db run-id)` byte for byte | TEST:12-18, :105-106, :295-309 |
| The cluster's own JVM is the primary in-process host; isolated workers are the exception (platform/destructive/boot, and gating a checkout snapshot) | worker pool already a dynamically claimed queue at 83–91% utilisation; test bodies are 27% of a cold gate, publication 42% | An agent writing a test in its REPL sees and runs it the same way the batch gate does — same function, same facts | TEST:43-59, :71-78 |
| An agent runs exactly the tests reaching its change, in-process | reach probe 39 s for all tests, median closure 668 functions; `:seon.test/reach` recorded for 281 of 1,829 | Selection derives from `:seon.fn/calls` edges, the reach digest and the last recorded run's basis `:t` — no mtimes, no file lists, no dirty flags; one selection function produces identical members and reasons in-process and in a worker | TEST:P3 :110-112, :212-227; NDM:51-53 |
| An unchanged, previously green request selects zero members and returns the recorded result | 4→0 zero-execution fast proof recorded | Reuse returns `:seon.test/unchanged true` with `:seon.test/recorded-basis-t` alongside the original tested basis; red or unknown evidence never qualifies | TEST:240-248; README state pointer :19 |
| Coverage is the stored call graph | — | "At least one test exercises this function" means a test reaches it transitively through stored clj-kondo edges. No metadata on tests naming what they test, no annotation of any kind | TEST:E2 :26-29 |
| A test loads by identity from facts, not a file path | worker resolves from files | An agent-authored deftest with NO file is selected, resolved, run by the worker path, and its result lands on that cluster | TEST:P4 :113-118, :258-274 |
| Which tests are destructive is a fact, not a hand list | — | The owner functions that destroy declare it once; destructiveness derives by reach and is indexed on the test so an agent can query where a test runs and why | PRD-PF:F1 :209-215 |
| `:seon.test/platform` is an indexed attribute; the bare namespace set derives from `:seon.ns/name` rows under the declared test root | **0 of 1,833** rows carry it (it is Var metadata today) | never a filename `find` | TEST:92-97 |
| Workers claim members from the run entity through one transaction function | coordinator statically partitions namespaces | A worker dying mid-claim leaves a member with a dead process record that the next claim reclaims | TEST:276-293 |
| Full runs are a last resort, even for root | — | The system records which functions changed — identity is the definition's CONTENT DIGEST, never a branch-local id, so it is consistent across exploratory SCI branches — and reruns only tests reaching them | NSA:D9 :391 |

### 2d. Tasks, namespace agents, routing

| Target | Measured state today | Acceptance condition | Source |
|---|---|---|---|
| `seon.task` is ONE family: linked facts (subject refs, tests, errors, functions) plus an optional agent | `seon.issue` today; 240 markdown notes, 137 citing a `src/…clj` path, 113 a qualified symbol, 1 with a `namespace:` | A "template" is the render pair plus the units the task's data selects — never an entity or a registry; a detected defect is a task whose subject came from a detector | NSA:D1 :36-44, :107; ISSUE-FAM:1-56 |
| A trigger maps to a TASK, not to a namespace membership | — | One trigger yields one task and one agent; a repeat occurrence updates WITHOUT a new task, agent or notification; `start!` refuses while `:seon.task/agent` exists | NSA:D2 :108; wave-3a spec:45-60 |
| Done is a query, written by settlement only | — | An issue/task resolves when its cited tests verify on the current reach digest OR its detector stops naming the subject; `start!` admits either and refuses one with neither, inside its transaction function | PRD-PF:T1 :86-91; DEC:346-425 |
| The agent sees its tests and their results every turn | 7-opening trial: 3 of 7 sessions called a function that does not exist; 15 of 58 evaluation errors were unresolved symbols; only the 160-byte namespace-picture opening produced a correct fix | The opening names the exact tests or detector that will run; after each turn the system appends one concise evaluation with which passed, which failed with its shown text, and what is still open — an ordinary generated read re-evaluated by the system turn, not a new render path | PRD-PF:T3 :99-110; DEC:266-334 |
| Budget exhaustion is loud and resumable | — | A typed outcome is recorded on the task and root receives a message naming the task, turns spent and last status; root resumes by asserting a new budget and opening the next turn — "If it is more complicated than that, the abstraction is wrong and is fixed, not worked around" | PRD-PF:T4 :112-121, C4 :161-164 |
| Plural namespace responsibility: `:seon.ns/agents`, many-to-many | **2 of 411** namespaces have a steward, both agent namespaces | Two agents cover one namespace; work is not duplicated by routing; every agent state answered by ONE query | NSA:D1 :38, wave-1 1c; README:43; NDM:47-48, :165-170 |
| A conversation is DERIVED from messages, not an entity | — | Done = no outside wake newer than my reply; a reply task uses the same context mechanism as any other task; the conversation needs no fabricated defect | NSA:D1 :40; PRD-PF:F4 :233-241; README:43 |
| Untested public functions and uncontracted functions are the first agent task classes | **288** untested public functions; 3,145 private without contracts (detector count) | Detectors for both; the without-test detector depends on the call-graph fidelity fix so it does not lie | PRD-PF:F7 :247-250; README C2 :288; NDM:38 |
| Curated render pairs: every entity map and every function output has an AI and an HTML render | first generator run opened 32 entity-maps-without-pair and 31 public-without-docstring issues | The detector closes the task when the pair is declared on the schema; quality judged by reading the rendered output on the agent and namespace pages | DEC:834-869 |
| "Everything about namespace N" is ONE pull | pull returns name, functions, specs and nothing else | The §4 selector returns tests, faults, lint, issues, tasks and the responsible agents; the namespace picture pair renders it and an agent's opening IS this view | NDM:184-201, :366-369 |

### 2e. Errors as data

| Target | Measured state today | Acceptance condition | Source |
|---|---|---|---|
| An error IS a map: base (`:seon.error/at`, `/layer`, `/operation`, optional `/message`) plus a domain schema declared in the OWNING resource | landed (`431093b97`) | No kind stamp, no class marker; the base says when/where/who, the domain members say what | ERR:28-29, :43-45 |
| Recurrence identity is kindless and content-derived | — | `seon.id/id` of [layer, operation, the sorted set of schema keys the complete observation satisfies, throwable class + top frame when present, the violated expected key or shape when present, the location path] — NEVER the timestamp, basis, process, message text or offending bytes. Same hash = same bug: one root entity, occurrences as components, one repeat count, one task, one notification | NSA:D13 :393; ERR:32 |
| A consumer branches on a distinguishing REQUIRED member of the specific declared error schema | transitional base-three `contains?` check at 74 sites | The distinguishing member is required in that schema and shared by no sibling in the callee's union; each transitional site carries a `;; debt: <callee>` comment | ERR:120-146, :236 |
| Loud in development, recorded in production, one dial | `:seon.config/on-core-error` `:panic｜:record` exists | `:panic` throws at the seam with the flat error as ex-data, never caught and relabelled; `:record` writes a fault fact with provenance and continues. A critical fault under `:panic` stops only the affected agent/cluster graph and keeps the JVM, which requires a durable failed state and positive visibility at every status surface | PRD-PF:§1k :378-406, §1m :424-444 |
| A database failure is a system-down panic handled at the REPL | — | Owner: "if the db is down panic because the system is down, so shut down what we need to and the system REPL has to fix it. Otherwise errors are just data so we store them and connect them to best surface them." No durable side log with replay | PRD-PF:§1m :431-436 |
| Error messages tell the truth in one grammar | — | `<operation> refused <member> at [<path>]: expected <expected>, got <actual>. Fix: <fix>. Contract: <key>.` Tests assert the FACTS a message renders (member, path, expected, fix), never the exact string | ERR:148-168 |
| A dead agent turn proc is visible | the 2026-09-08 live trial: the agent's turn proc died silently on every wake | Agent procs appear in `runtime_status`; a proc death is a fault naming the agent; the mailbox→turn drop is counted; fault-evidence admission is partitioned so the classifying key survives the cap | README:206, :245 |

### 2f. Isolation, merge, and the human surface

| Target | Measured state today | Acceptance condition | Source |
|---|---|---|---|
| An isolated candidate per task: its own Datahike branch AND its own SCI context | not built; `bin/seon init NAME` already forks a published commit | Candidate writes leave main unchanged; concurrent conflicting changes refuse; the proposed combined program passes ARMED tests and schema validation before atomic acceptance | README:41; NSA wave 4 |
| Changed program entities since the fork basis as a PURE projection | not built | Edit three entities on a fork; the projection returns exactly those | README E2 :328 |
| Gated merge into the shared branch | not built | Exact replacement through `seon.program`; the merge gate is the existing test system running exactly the tests whose reach changed; a durable acceptance record carries base, proposed definitions, schema deps, selected tests, results and tested head | README E3-E4 :329-330; NSA wave 4b; wave-4 spec:115, :156, :199 |
| A same-identity conflict opens a CONFLICT TASK for root, carrying both sources and the basis | — | The conflict task has a fingerprint identity so only one instance arises; root resolves it; the tests written for that task must pass; then it merges. No automatic re-apply first | NSA:D6 :342, D8 :391 |
| The prompt is additive; bytes already sent never change | — | The prompt is the stored evaluations in order through `seon.repl/text`; only the tail is new; compaction is a wipe-and-regenerate with NO manual curation path | TURN:931-942, :951-953 |
| The since-diff covers every distinct read form | — | If read evidence changed since a read's latest evaluation `:t`, the system turn appends a fresh evaluation of that same form; writes and effects NEVER rerun; nothing changed ⇒ no system turn | TURN:948-950, :970-977 |
| Results are objects in memory, shown text on disk | — | `result/e<id>` binds the actual object with no serialization; the evaluation stores exactly what the agent saw; the prompt regenerates byte for byte after restart and the map then says the object is gone and the text remains | TURN:1005-1028 |
| One render pair per entity schema, one block per concern | — | Scalars render inside the entity's own block; components and declared derived queries own theirs; no render pair means the default attribute-map printer; a failed render produces a diagnostic | TURN:869-893; UI:17-30 |
| The debug page shows the algorithm, not a summary of it | — | State line; context now with a per-evaluation as-of check; the would-be system turn writing nothing; prompt bytes + digest; Run-system-turn / Virtual-turn / Compact controls | TURN:1081-1106 |
| Every proof for anything touching the running system is a LIVE observation, named as hot-reload, fork or in-place adoption | — | A change proven only by a passing test is not proven; browser paint requires its own observation | README:222-226; AGENTS.md §4 |

---

## 3. Owner rulings that define the product

| Ruling | Date | Verbatim, or close paraphrase where the doc does not quote | Source |
|---|---|---|---|
| R1 agents author the shared program by evaluation | 2026-09-17 | Agents author the shared cluster environment with pure Clojure forms evaluated through our parser and eval system, written to the shared database; every agent on the branch gets them. Agents may also live in their own experimental SCI-context world, and ideally experimental database branches, thrown away or merged | PRD-PF:58-63 |
| R2 one shape, provenance is an attribute | 2026-09-17 | The same Malli schemas, attributes, values and refs describe code whether indexed from a file or added during a turn. Provenance is an additional attribute, never a different shape | PRD-PF:66-68 |
| R3 index and update are one operation | 2026-09-17 | Same operation, same entity, same identity | PRD-PF:70-71 |
| R4/1s loading decided per identity by digest | 2026-09-17 / 2026-09-18 | "It isn't really about 'agent authored'; it really is: is there a functional difference between symbols? … do what you think is correct and is simple and powerful." | PRD-PF:73-76, :544-562 |
| R5 write-back is gated | 2026-09-17 | Writing agent definitions back to `.clj` is the other direction of indexing; planned, gated on checks passing first | PRD-PF:78-83 |
| T1 done is a query | 2026-09-17 10:20Z | An issue's done condition is its cited tests verifying or its detector no longer naming the subject; `resolved-tx` is written by settlement only | PRD-PF:86-91 |
| T2 continuation derives from the task | 2026-09-17 10:20Z | An assigned agent takes another turn while its task is open and its budget remains; `my.turn/complete`/`wait` have no effect on an assigned agent and remain the disposition of a conversational agent | PRD-PF:93-97 |
| T5 waking is out of scope for now | 2026-09-17 | "I don't think we have a good system for waking right now, but we can work on that later" | PRD-PF:123-125 |
| C1 high admittance bar | 2026-09-17 11:00Z | Every function is fully specified (input and output contract) and has at least one test before admittance to a shared cluster. "The updating and testing system must be exactly right, because it is how we know an update broke nothing." | PRD-PF:131-137 |
| C2 the merge gate | 2026-09-17 11:00Z | The agent's task tests pass AND every test reaching any changed function passes; explicit, never automatic; its target is `default`, so files→facts (the hook) and facts→files (write-back) meet on one cluster | PRD-PF:139-147 |
| C3 override scope | 2026-09-17 11:00Z | All first-party namespaces, unless a definition cannot be evaluated in SCI — then that identity keeps the JVM definition and the fact records it as not loadable in SCI, a typed state | PRD-PF:149-152 |
| C5 no separate context system for tasks | 2026-09-17 11:00Z | The per-turn status is the agent's own data on the agent's debug page, with an AI/HTML pair; the AI render shows the forms to run alongside the reasoning so agents learn by seeing executions. "If the agent page cannot show it today, that is the work." | PRD-PF:166-172 |
| C6 no shell for self-modification | 2026-09-17 11:00Z | Agents never run `bin/seon` or any shell command to change their own system; the runtime exposes adoption, tests and collection as declared requests | PRD-PF:174-178 |
| D1 (11:20Z) the database runner is the one test gate | 2026-09-17 11:20Z | `bin/test` becomes a launcher of isolated worker JVMs running through the same runtime and SCI contexts and recording through the same functions | PRD-PF:181-188; TEST:12-18 |
| D2 (11:20Z) focus on the agent's runtime REPL | 2026-09-17 11:20Z | Agents update their runtime and pass the tests we give them; shell commands are not taught; a namespace's teaching is pulled in only when that namespace is required — "what a session requires is what teaches it" | PRD-PF:190-196 |
| D3 (11:20Z) the admittance bar is enforced at the merge, not at evaluation | 2026-09-17 11:20Z | An agent may define an untested function in its own session; the merge gate refuses an identity no test reaches, BY NAME; the per-turn status shows which definitions still lack a test, so the bar is visible while it works rather than a refusal mid-thought | PRD-PF:198-205 |
| E1 the cluster is explicit, no magic | 2026-09-17 | An agent's tests run on the cluster its custody names; a human names the cluster when launching; tests record to the cluster they ran on | TEST:22-26 |
| E2 coverage is the stored call graph | 2026-09-17 | "No metadata on tests naming what they test, no annotation of any kind." | TEST:26-29 |
| F1 destructiveness is a fact | 2026-09-17 18:00Z | Destructive tests run on an immutable snapshot of the named cluster in an isolated process, recording to the cluster; WHICH tests are destructive derives by reach from owner functions that declare it, never by hand | PRD-PF:209-215 |
| F2 write admission validates ALL inputs, always | 2026-09-17 18:00Z | "A grammar that is validated less … is a hack and is not an answer." A failed validation aborts the whole transaction | PRD-PF:225-231 |
| F3 the history is rendered per evaluation, composed | 2026-09-17 18:00Z | Never one giant string; the prompt is a composition of rendered units and the budget selects units. "If the way evaluations are stored on the entity gets in the way, change the storage." | PRD-PF:233-238 |
| F4 conversational agents get feedback every turn | 2026-09-17 18:00Z | A conversation's done condition is that a reply entity exists for the triggering message; the agent is told each turn whether it has replied and how. "No agent burns thirty turns with zero feedback because it never called the right function." | PRD-PF:240-247 |
| F5 everything that is a symbol is stored as a symbol | 2026-09-17 18:00Z | "Upgrade the schemas, delete the database and reset from scratch; no migration." | PRD-PF:249-251 |
| F6 root collects the store automatically | 2026-09-17 18:00Z | On the existing daily row at twice the last retained size, with the derived cutoff, no human step; the trigger multiple is two (C7) | PRD-PF:253-254, :159 |
| F7 first agent tasks are the simple ones | 2026-09-17 18:00Z | Functions without contracts or without a reaching test — NOT render pairs (rendering is complicated) | PRD-PF:247-250 |
| F8 the orchestrator resets `default` whenever needed | 2026-09-17 18:00Z | "and does not wait on the owner for important things" | PRD-PF:256 |
| G1 deletion is retraction | 2026-09-16 late | A deleted function, test, namespace, schema key or note is `[:db/retractEntity …]`. No entity is kept alive for another entity's refs. There is NO retirement attribute; the past is `history`/`as-of`/`since` | PRD-PF:264-271 |
| G2 observations are values, statements are refs | 2026-09-16 late | Call edges and test reach are `[:set :qualified-symbol]`; deleting a function touches only its own datoms and "A calls a name with no row" is one Datalog clause, reported positively, never prevented by the writer | PRD-PF:272-279 |
| G3 delete the tombstone machinery | 2026-09-16 late | A ref to nothing is refused at the writer, as Datahike already refuses a missing lookup ref | PRD-PF:280-284 |
| G4 looking is an event and the event is a datom | 2026-09-16 late | Every definition row carries the identity of what produced its facts, REQUIRED; `#{}` is never a sentinel; submission-time-only validation is a pre-read and is rejected | PRD-PF:285-291 |
| G5 a component is part of its parent's value | 2026-09-16 late | The whole-entity validator validates the parent pulled with components expanded as one value; no identities are invented for component rows | PRD-PF:292-295 |
| Deletion is strict with no escape | 2026-09-17 05:10Z | "we want either all the fixes in a single transaction (so we can verify with the db-after that everything is still correct) or we will farm out in a distributed way to agents to refactor and remove references … lets stick with being strict and figuring out how to make this work with agents." | PRD-PF:300-309 |
| Agents are never retracted | 2026-09-17 05:10Z | "Agents are always resumable but sure we can have an archived flag and if so we don't surface it in the UI" — `:seon.agent/archived-tx`, derived `archived?` | PRD-PF:310-313 |
| Don't be dogmatic; the dependency's own modeling overrides us | 2026-09-17 06:35Z | "learn from the datahike modeling and override our previous decisions on the schemas and refs vs components or whatever. don't be dogmatic." | PRD-PF:342-350 |
| §1j every function carries a contract, private included | 2026-09-17 16:30Z | The public-only rule is retired; instrumentation arms every contracted function; errors stay VALUES (no throwing) and a contracted consumer refuses one by shape | PRD-PF:352-377 |
| §1k loud in dev, collected in prod, one dial | 2026-09-17 17:45Z | "We want to fail loud in development and we need the instrumentation to help make that happen (so throw instead of catch in dev) and we need the production system to keep collecting the errors or to write them to the database and then we'll just triage them from there — that should work for all but database errors so careful there." | PRD-PF:378-406 |
| §1l the best schemas we have ever written | 2026-09-17 18:00Z | "I want the best schemas we've ever written to come out of this. We need a thoughtful analysis about the data model first, how we can improve it, and then we need to have the right checks in place and to return the best data for handling it in the future (system crash for critical so we immediately fix those)." Order: data model → checks → contract campaign | PRD-PF:408-422 |
| §1n boot carries no test namespaces | 2026-09-17 21:50Z | "Once the system is up and running most of the tests and software should be at runtime." The cluster boots from `src/` alone; tests are program facts resolved by identity after boot | PRD-PF:446-464 |
| §1o errors are entities; schemas compose; data first | 2026-09-17 22:35Z | A read failure inside a turn satisfies BOTH the database-read schema and the turn schema and renders the base block plus one block per satisfied schema — no most-specific-wins dispatch. The system consumes the DATA; humans get render pairs, including Malli's own `malli.error` helpers. "look into malli's human readable functions … maybe we should use our render functions too" | PRD-PF:466-494 |
| §1p render pairs stay schema properties | 2026-09-18 00:30Z | "okay fine. approved." Declared once in the resource, derived into the registry and the schema entity's property datoms; values carry data only | PRD-PF:495-503 |
| §1q every function lists the errors it can return | 2026-09-18 01:00Z | "We want to be clear that this function could return these errors." Explicit enumeration everywhere, generic helpers included; the declared union is checked against the body-derived set so it cannot drift silently | PRD-PF:505-517 |
| §1r two write bounds, derived from provenance | 2026-09-18 02:15–02:30Z | "root access (system) for no limits, and then agents; if they fail, root can re-run whatever transaction it is." And: "we don't have access control in the database explicitly but we have the user metadata so we can still write it in." A bounded-out agent write returns its refusal WITH the transaction data | PRD-PF:519-542 |
| D1 (09-19) names | 2026-09-19 | `:seon.ns/agents` + `seon.task` + derived conversation; "namespace agent" is the term | NSA:107 |
| D2 (09-19) routing | 2026-09-19 | "the trigger should map to TASK, so if an agent is already spun up for that specific task it gets an update and if not we spin up a new one. So there is a division of labor and focus." Namespace membership is responsibility and context, never the routing key; "wake all namespace agents" is withdrawn | NSA:108 |
| D3 (09-19) errors: data model first, kinds are always a problem | 2026-09-19 | "Getting these right is very important." `:seon.error/kind` and the 52 class markers are deleted in the same cut, not staged | NSA:109 |
| D4 finish what is sound, reset what is half-baked | 2026-09-19 17:30Z | "how good is the work? finish the ones that are sound and reset the half baked ideas." | NSA:340 |
| D5 the first live proof | 2026-09-19 17:30Z | "schema and missing tests" — a schema guarantee with its reproducing example and regression, plus missing test coverage for a function; two agents on separate candidate clusters, gated merge, a forced same-identity conflict and an invalid candidate | NSA:341 |
| D6 root resolves conflicts | 2026-09-19 17:30Z | "root is going to resolve conflicts." A divergence is not refuse-and-refork: the merge writer opens a conflict task for root carrying both sources and the basis; root's resolution goes through the same gate | NSA:342 |
| D9 full runs are a last resort | 2026-09-19 18:20Z | "Stop thinking about agents running bash commands and start thinking of the system recording data and doing more at runtime." Changed-function identity is the definition's CONTENT DIGEST, never a branch-local id | NSA:391 |
| D12 no general error predicate | 2026-09-19 20:40Z | "the point of this is to have more precise errors and schemas that name the specific errors or union of possible errors, so it's explicit that this function may return these errors. We don't want a general predicate. The goal is to improve the schemas and to know what we are calling and what can happen, and we are guaranteed those values will be present and valid with the instrumentation validating it." | NSA:392; ERR:10-15 |
| D13 kindless recurrence identity | 2026-09-20 00:05Z | "a hash of a unique aspect because we are storing these in the database and we need to detect duplicates so we don't overwhelm agents but also it needs to be a truly unique error" | NSA:393 |
| Stop fighting Malli | 2026-09-20 | "stop fighting malli and use its internals to make everything fast and simple. However you think we can improve our applications and definitions to the database schema sketch it out and run everything by astra with links to malli source so we stop reinventing the wheel." Earlier: "we should be able to specify a composite error without causing all these problems. The errors are just data right?" | MALLI:10-15 |
| Trust Datahike's native write guarantees; preserve only what it does not establish | 2026-09-20 10:15Z | Also: the population digest is stamped on the branch's existing source population row; missing or mismatched = typed refusal, never a fallback to files | MALLI:434-447 |
| One JVM, index once, then incremental | 2026-09-22 | "It was always supposed to be a single JVM and we pay the cost of startup once. We pay the cost of indexing once and then it's incremental. Stop fighting the tools they are already optimized." | 1JVM:10-12 |
| Tests fail over a bound | 2026-09-22 | "start making tests fail if they exceed reasonable time limits. None of this shit should require minutes of computation." | 1JVM:12-14 |
| No double caching | 2026-09-22 | "No double caching. Use all the existing tool caches." | 1JVM:84-86 |
| No new nouns; the error entity IS the deduplicated entity | 2026-09-16 02:30Z | "fault" and "class" are dropped; identity is `:seon.error/signature`; per-agent per-turn counts are `seon.error.occurrence` components. Vocabulary is Clojure's (`ex-info`, `ex-data`, Throwable class, stack frame) and Datahike's | NDM:461-468 |
| Refs point from the later, more specific fact to the more stable identity | 2026-09-16 | occurrence → error → function → namespace → agent, never the other way; counts, states and ownership are derived at read time | NDM:371-382 |
| One id derivation, never a new generator | 2026-09-16 02:30Z | `seon.id/id` over `(into (sorted-map) m)` — `pr-str` of a hash map has no guaranteed key order. "No new generator." | NDM:472-477 |
| Additive context | 2026-09-08 | "We shouldn't overwrite the previous context as that breaks caching … unless we wipe the data and do a compaction it is an additive process." | TURN:931-932 |
| Everything, not the inbox | 2026-09-08 | The since-diff covers every distinct read form, generated or agent-written; writes and effects never rerun | TURN:970-977 |
| Stop serializing private state | 2026-09-08 | "yes stop serializing. Store it in memory" — atoms keep identity across turns; `:seon.def` rows deleted with their serializer | TURN:833-837 |
| No functions without contracts | 2026-09-08 | "Don't allow functions without malli contracts." | TURN:859 |
| The render function is a function of the data | 2026-09-08 | "THE RENDER FUNCTION IS A FUNCTION OF THE DATA" (capitals in source); a block with nothing to say is absent | TURN:901-906 |
| No hand-made history | 2026-09-08 | "NO HAND MADE SHIT. Turtles all the way down." | TURN:1051 |
| Show it in practice | 2026-09-08 | "I want to see it in practice" — the debug page shows the algorithm | TURN:1081 |
| All turns shown | 2026-09-08 | no history clipping "until we get shit under control" | TURN:922-923 |
| Curated render pairs are a first agent task | 2026-09-17 10:00Z | "One of the tasks I want agents to do is for us to find all outputs that do not have render functions specified for both AI and HTML and to ensure that the data is only high quality and is curated. So we strip away all the garbage and synthesize a better clearer response. We do not rely on the value renderer for most things. We think about what data we have in the system and how to display it properly." | DEC:834-843 |
| Migrate forward; delete the old path | 2026-09-16 eve | "We are migrating to the new test code so fix everything in the new code and we can remove the old test code and migrate it to call the new code" | TEST:85-88 |
| The data comes first | 2026-09-16 01:20Z | "the DATA comes first. Read namespace-data-model before this index" | README:88-90 |
| Faults connected, one agent per class | 2026-09-16 01:45Z | "each class is a task, so the agent sees the faults rolling in and we do not spawn multiple agents on the same problem. … Never strings for things that should be symbols; refs to related entities; a real connected graph; record intelligently for future tasks." | NDM:225-231 |
| Database data is disposable | standing | Reset rather than migrate; every incompatible schema change waits for ONE refork | PRD-PF:G6 :296-297; NSA:73 |
| Resets are the recovery, not an event | 2026-09-17 | Eight resets in one day, each under two minutes, took the store 21 GB → 102 MB; none lost anything a reseed did not restore | AGENTS.md §7 (from the archived ledger) |

| Efficient updates; smart aggregation, never an entity per event | 2026-09-16 08:25Z | "Focus on improving code indexing, error and fault storage and linking, getting the robust test infrastructure set up so we are updating the test entities for each function when it passes and we always know the state of things. Efficient updates everywhere without creating a shitload of entities: smart aggregation based on identity and updating attributes." | unsettled.md:205, :920 |
| Record results, never rerun the same tests | 2026-09-20 06:40 | "keep coding but please don't just keep running the same tests over and over again and rebuilding everything every time. Prioritize the optimizations to speed up repetitive work." (2026-09-20 23:30) | unsettled.md:4441, :5061 |
| The offending value is a normal `result/e<id>`, reachable from the agent's REPL | 2026-09-22 10:30–10:40 | "Why can't we store the offending value as a blob and just store the reference? Sure you can also print it to a string with a cap (don't store too much string data in a database entry). Use the value printer for that so we only have one mechanism." / "it should be a normal result/<result-id> blob accessible from the agents repl" | unsettled.md:6046, :6068 |
| Strongly typed error entities; stop storing what the schema does not declare | 2026-09-22 12:50 | "Get rid of the error map edn copy. Serialize anything that doesn't fit the schema to a result/e<id> and store that reference. We should be using malli to generate a readable error message or to use their data format which we can clearly put into a schema and store. Everything that doesn't align with the schema should not be hacked into the database. Stop fighting the schemas. We don't need to store everything when shit goes sideways. If it happens during the runtime the agent can debug it from the results and otherwise it's historical data we are storing so we know wtf is going on. That's it. Yes. We want strongly typed data. Stop trying to store shit that isn't in the schema. Focus on clearing all the debt and finding all the remaining bottlenecks so we can work on the real system." | unsettled.md:6272 |
| Seconds, not minutes; our failures are algorithmic, not correctness | 2026-09-22 22:40 | "Nothing we are doing should take minutes so always consider anything larger than a couple of seconds with suspicion. Especially when we are using highly tuned libraries where their creators really nailed the implementations. Our fuckups have almost always been bad algorithmic choices not code correctness." Also: "Ideally we don't want any :any schemas but that isn't a hard rule. Not everything that will be processed by functions will be stored in the database." And: "Turn off the automatic push's until we fix the 'minutes for every edit' problem. I want you to pick up the cadence and we need to move past fire fighting to do the actual design." | unsettled.md:6662 |
| A fork is a branch pointer: milliseconds | 2026-09-22 23:30 | "those lower numbers are the correct ones… It's an immutable database and we are just creating a branch. No change 115s is just fucked." A fork never boots a JVM; `init NAME` records its commit so the first adoption is the no-op | unsettled.md:6703, :6718 |
| Fix the highest value first; 100% green is not required; the system fixes the rest | 2026-09-23 03:00 | "focus on fixing the highest value things first… we don't need 100% green and once the system is stable we can use the system itself (namespace agents) to fix the remaining issues" — the remaining red classes BECOME the namespace agents' first real work | unsettled.md:6855 |
| The durable form of any result is the value printer's rendering | 2026-09-23 05:50 | blob = complete, entity text = capped, live object only as `result/e<id>`; no faithful-EDN attempt, no encoder extension, no supported-type list | unsettled.md:6943 |
| Derive or die, applied to caching | 2026-09-23 08:20 | A database value is immutable, so everything derived from it is computed once when the value is made and CARRIED on it | unsettled.md:7016 |
| Every publication input is a file row carrying its digest | 2026-09-23 19:50 | A request hashes only its changed paths; analysis membership derives from the program graph's file references | unsettled.md:7173 |
| Config reconciliation transacts the DIFFERENCE | 2026-09-23 19:15 | No digest decides; an empty difference is no transaction; the stored applied-manifest digest is deleted | unsettled.md:7163 |
| Supplied defaults key on the declared schema NAME | 2026-09-23 20:50 | never on structural equivalence to a registry schema | unsettled.md:7254 |
| The transaction report is the seam for caller lint | 2026-09-23 22:05 | transact changed declarations on the unpublished branch → select callers from that report → transact findings → move the head | unsettled.md:7307 |
| A log line carries identities, never a serialized argument | 2026-09-23 21:45 | operation, exception and request identities only | unsettled.md:7293 |
| Recursive rendering of nested data is a design item | 2026-09-20 04:20 | "rendering happens at the edges but yeah I do want to look into a possible recursive rendering system if we have nested data." | unsettled.md:4337 |
| Are we creating our own problems? | 2026-09-20 09:40 | "we wrote the bridge between the schemas and the database. Can we apply the recent learnings better? What are our pain points and are we creating our own problems?" | unsettled.md:4569 |
| Resident memory is a smell, like time is | 2026-09-23 | standing rule in the final RESUME HERE block | unsettled.md:7250 |

### Process rulings — likely retired with the schedules

These governed HOW the work was farmed, not what the product is. Carried here
only so nothing is silently lost; the clean-slate plan should re-decide them
from scratch.

- At most three (earlier four) editing lanes beside `default`; file ownership is the scheduling constraint (README:117-121; NSA:50).
- Lanes use `bin/test-fast`, never cold `bin/test`; the orchestrator owns cold gates and `--platform` at wave boundaries (README:222-226; NSA:50).
- `bin/_test-slot` bounds a checkout to two concurrent test JVMs (README; AGENTS.md §5).
- Model/effort per lane: `gpt-6-astra` low for bounded slices, medium/high for design review; no Opus implementation lanes (README:157; NSA:D10 :392).
- D7 one orchestrator: "Whoever is doing orchestration needs to be running the tests. There will not be two orchestrators." (NSA:343) — a genuine product-adjacent rule about who gates, but expressed as session management.
- D11 parallelism is the orchestrator's call, game-time (NSA:392).
- Lanes never create worktrees; protected = concurrently edited only (README; AGENTS.md §7).
- The `orchestrator-only` test mode was DELETED (`fa971495f`) because it refused every lane invocation including `bin/test-fast`, so lanes committed untested (TEST:89-91).
- Wave/lane/bridge-step sequencing, the reset batch ordering, and per-lane file grants — all schedule, all dropped.

---

## 4. Vocabulary

### Settled (AGENTS.md §3 carries the full table; these are the terms the archived plan settled or changed)

| Term | One line | Source |
|---|---|---|
| namespace agent, `:seon.ns/agents` | Several agents per namespace; a cardinality-many ref set on the namespace; responsibility and context, never the routing key | NSA:D1 :107 |
| `seon.task` | ONE family for work an agent does: linked facts plus an optional agent; a "template" is the render pair and the units the task's data selects | NSA:D1 :38, :107 |
| conversation | DERIVED from `seon.message` facts; no entity | NSA:D1 :40 |
| task trigger | Resolves to a task identity at the writer (detector + subject value) | NSA:D2 :108 |
| error base / error schema / occurrence / signature | Base = at/layer/operation/message; a domain error schema lives in the owning resource; occurrences are components under the signature root | ERR:28-32 |
| distinguishing member | A required member of one error schema that no sibling in the callee's declared union shares | ERR:140-143 |
| generation (Malli) | One admitted immutable semantic generation of compiled schemas carried by DB values and the environment | MALLI:17-19 |
| stamp / population digest | A datom on the branch's existing source population row — never Clojure metadata on the branch name; distinct from the projection fingerprint and from `:seon.source/digest` | MALLI:261-279 |
| run request / selection reason | A run request names a cluster and a change basis; reasons are `:platform`, `:reaches-changed`, `:named`, `:first-run` | TEST:149-152, :231-234 |
| tally | A render of a query (`render-run`); nothing is counted in the shell | TEST:96-97, :159-161 |
| landed | The measurement script row moves AND the platform tier is green | 1JVM:72 |
| wake / answered / basis / turn / history / shown text / evaluation entity | As AGENTS.md §3 states them | TURN:102-121, :152-159, :1032-1034 |

### Ruled retired

| Retired | Use instead | Source |
|---|---|---|
| steward, `:seon.ns/steward`, steward-call | namespace agent, `:seon.ns/agents` | NSA:D1 :107 |
| `seon.issue` as the family name; `my.task` as a separate family | `seon.task` — one family | NSA:D1 :107; NDM:526-532 |
| kind, `:seon.error/kind`, `{:seon.error/class true}`, `[:= true]` class markers, `:type`/`:kind` discriminators | An entity IS its attributes; the error's SCHEMA is its meaning | NSA:D3 :109; ERR:36; ARCH:99; TURN:252 |
| facet ("never approved" — owner, 2026-09-22 09:40), family (2026-09-22 12:50) | "error map" / "error schema"; "name = the failure, not 'kind'"; explain in Clojure/Malli/Datahike/SCI/core.async terms only | unsettled.md:5996, :6306; wave-3a-launch:12; ERR:215 |
| the general predicate `seon.error/error?` and its nine private copies | The specific declared error schema's required members | NSA:D12 :392; ERR:36 |
| receipt | evaluation / result | TURN:159; DEC:895 |
| fault, class (as entity nouns) | the `seon.error` entity itself, keyed by signature | NDM:461-468 |
| transcript | the history (the walk over the agent's evaluations) | TURN:98-99, :1032-1034 |
| layer one / layer two | JVM-loaded first-party code / agent-authored definitions | PRD-PF:C8 :176-178 |
| claim, pending, inbox, mailbox, trigger, freeze, settle, episode, wake item, wake source, situation | "never written again" | TURN:108-110, :159 |
| "toolchain" class / `toolchain-digest` / `producer-paths` | deleted; there is no toolchain class | 1JVM:34, :94-96 |
| row (for a Datahike entity in prose or in an agent-visible refusal) | entity map / transaction data; "row" stays for program-graph and config declarations | DEC:896 |
| run (for a turn), worker (for the agent a task starts) | turn; agent | DEC:897-898 |
| prober, heartbeat, reclamation signal, churn, write storm, gate line, sweep (for a status check) | the grounded name in each case; note the observation that all three genuinely unmoored words were counts nobody measures — "A word standing in for a number that is never derived is the absence-as-health shape in prose" | DEC:899-911, :913-916 |
| `:seon.fn.file/root` | `:seon.fn.file/relative-root` | DEC:901 |
| `?prompt=true` query flag | context-now is always the primary debug view; provider prompt comparison always present, collapsed | TURN:1350-1352 |

---

## 5. Explicitly retired directions — do not resurrect

| Do not | Why | Source |
|---|---|---|
| Do not build `my.task`, a template entity, a template registry, a second scheduler, per-attribute pulled schemas, `:kind` stamps, or a hand contract campaign beyond the two proof waves | NSA's own "what this plan deliberately does not do" | NSA:111-113 |
| Do not teach agents to shell out (`bin/seon`, `my.shell`) to change their own system | The one measured attempt shelled out of the JVM to call back into it and died at the 30 s shell bound against a 221,788 ms adoption | PRD-PF:C6 :174-178; DEC:180-233 |
| Do not assign `src/` defects to agents before the gated candidate path exists | Decision 1, option 1: agent-authored definitions only until the gate is designed | DEC:236-262 |
| Do not use Flow as the execution model for the first-party suite | Measured premise correction: tests are not IO-bound; the pool is already a dynamically claimed queue at 83–91% | TEST:71-78 |
| Do not select tests by mtime, file lists or "dirty" flags; no recursive Datalog rule for reverse reach (6,753 ms vs 14.181 ms; `solve-rule` does not memoise) | Facts over inference; measured | TEST:110-112, :214-219 |
| Do not resolve tests by file path or add reload-by-namespace special cases | Those existed only because tests came from files | TEST:274-275 |
| Do not mint a placeholder test entity for a detector-decided task | "minting a `:seon.test` fact for a deftest nobody wrote would be a lie on the very identity the test runner selects by" | DEC:356-360 |
| Do not let `start!` admit a task with neither tests nor a detector | "an issue with no way to decide 'done' must stay refused, because admitting it is the absence-as-health class" | DEC:416-420 |
| Do not add a render fallback from an attribute-scoped request to the entity's form | "asking about an attribute never silently answers about the entity" | DEC:769-782 |
| Do not filter detectors by name (`-test`, `-fixture`) | A name rule is one of the three banned substitutes; scope by the declared `:seon.fn.file/relative-root` fact instead | DEC:339-345 |
| Do not raise the value budget to cut the history, and do not relax the tests that caught it | A tuned constant in place of an observable event; the cut carried an empty path and named no turn | DEC:568-586 |
| Do not restore the undisposed-turn notice; do not continue a paid session on a turn that settled no evaluation | The second is "the write-storm shape in miniature" | DEC:650-720 |
| Do not add a Seon-side retention dial | "refuse any future Seon-side retention dial on sight" | DEC:826-830 |
| Do not build a `seon.commit` entity for git shas | An entity holding `git show` output is a cache of git | DEC:816-821 |
| Do not intern (caller, callee, arity) as an entity family | Triples the datoms on exactly the axis store growth is fighting; keep the tuple | DEC:811-815 |
| Do not use `mr/mutable-registry` in running generations, `get registry k`, or Seon-owned duplicate validator/explainer caches | Malli's own vocabulary and internals win | MALLI:104, :130-144 |
| Do not use `malli.instrument` in place of the Seon wrapper, or per-cluster authority in global `m/function-schemas` | Global state cannot own independent cluster generations | MALLI:341-359 |
| Do not put compiled closures or cache atoms in Datahike's durable `:meta` field | That field is serialized for commit/version info | MALLI:243-248 |
| Do not reconstruct the projection from rows in ordinary running code | Live evidence: `bin/seon init` derived from OLD `current-src` rows and refused a stricter admission rule than the stored population satisfied | MALLI:232, :390 |
| Do not make a bulk owner scan the universal writer | Small and agent writes SLOWED under it | MALLI:392 |
| Do not add: a second cache, a second analysis path, a tuned timeout without a declared bound, a new noun | 1JVM's standing prohibition | 1JVM:122-123 |
| Do not re-render generated context at the top of the prompt; the old generated-opening machinery does not return | Additive context | TURN:935-947 |
| Do not add a manual curation path for an agent's context (add/remove/revision/proof/adoption) | Compaction is wipe-and-regenerate | TURN:951-953; UI:113-115 |
| Do not re-fork the SCI context from scratch per turn | Base changes are interned as a DIFF; handles accrete | TURN:954-960 |
| Do not store a stored print node, `result-edn`, result blobs, `restorable-node`, `semantic-value`, or `max-bytes` as a separate bound | All deleted | TURN:1019-1023 |
| Do not store the responsible agent on an error or a task | Derive it: `fn → ns → agents` | NDM:307, :144-146 |
| Do not include the process in an error's class identity | Every restart would mint new classes | NDM:303, :469-471 |
| Do not store a program identity as a string where a ref or a symbol is possible | "Never strings for things that should be symbols" | NDM:225-231, :293-297 |
| Do not store `:seon.error/data-edn`, `data-size`, `offending-projection`, a raw in-memory `:seon.error/offending`, or drop values writer-side | All retired; the offending value is a `result/e<id>` reference | unsettled.md:6055, :6296 |
| Do not store anything the schema does not declare | "Stop fighting the schemas… Stop trying to store shit that isn't in the schema." | unsettled.md:6300 |
| Do not leave automatic hook publication or automatic pushes on while a one-file edit is expensive | Owner turned both off until the edit is cheap | unsettled.md:6670, :6678 |
| Do not write a test that passes because a bound fired, and do not let a test publish all of `src/` | A bound firing is a bug report, never a pass | unsettled.md:6919 |
| Do not add a `writes` family, and do not put a wake attribute on the task | | unsettled.md:15, :27 |
| Do not create per-assertion pass entities, or any entity per event without its own identity | The no-unlimited-growth ruling; aggregate by identity and replace attributes | unsettled.md:920 |
| Do not launch further lanes at bulk-tier red classes | That work belongs to the namespace agents | unsettled.md:6857 |
| Do not keep a sealed activation-closure roster | It mirrors the program graph; "unreferenced" is one Datalog clause at the moment of asking | unsettled.md:6975-6988 |
| Do not use git worktrees for structural edits | Owner: "they cause more problems than they solve for structural edits" | ERR:281; AGENTS.md §7 |

---

## 6. Open decisions the archived docs still list for the owner

| # | Decision | Doc's recommendation | Source |
|---|---|---|---|
| 1 | The write-back gate: reaching tests green in a candidate context, plus the platform tier? Add the task's cited tests? A human approval before the commit? | as stated, ask | PRD-PF:1218-1222 |
| 2 | Which first-party namespaces agents may override at all | any, with interop failures as typed evaluation errors; an exclusion would be a schema property on the namespace, never a name list | PRD-PF:1223-1227 |
| 3 | Merge collision policy | refuse by name and require a rebase; never last-writer-wins. NOTE: partly superseded by D6 (conflict task for root) | PRD-PF:1228-1229; NSA:342 |
| 4 | Where an agent on an experimental branch is addressable | messages on main readable from the branch, unanswered until merge or discard | PRD-PF:1230-1233 |
| 5 | Worker claim granularity: per test, per namespace, or per file | three options with measured fixture-load costs | TEST:339-342 |
| 6 | Whether a run on a non-`default` cluster records a pointer on `:current-src` so "last green" is globally answerable | the cross-cluster query | TEST:343-345 |
| 7 | Whether the platform tier stays a declared `:seon.test/platform` set or derives by reach from boot/cluster owners | keep the declaration; it is a fact with a docstring | TEST:346-348 |
| 8 | The default per-test bound value | proposed 5 s, not ruled | 1JVM:110-113 |
| 9 | A general peer-existence rule for numeric refs, and inconsistent native component declarations | "a separate scope decision" | MALLI:392 |
| 10 | An error consumer needing to distinguish more than the schema's members express | stop and list; it is a schema decision, never improvised | ERR:265-269 |
| 11 | A producer whose failure has no natural owner resource | list with the candidate owner | ERR:265-266 |
| 12 | The prompt budget selecting whole evaluations, oldest dropped first (decision 7's option 1) | recommended; ruled in part 2026-09-17 09:45Z — the numbers moved to 15,000 per result and 1,000,000 per prompt; the selection question stays open | DEC:588-604 |
| 13 | `:seon.test/namespace-under-test` default when no subject metadata exists: the `-test` rule applied once at index time, or metadata only (leaving ~1,659 tests unlinked) | ask | NDM:219 |
| 14 | Which production namespaces get the first agents | candidates by evidence: `seon.render.web` (28 citations, 130 fns), `seon.cluster` (37), `seon.sci.eval` (32) | NDM:220, :208 |
| 15 | The first real subject for the first live slice, and the first messy namespace | owner picks; no fake tasks | README:360-364 |
| 16 | Compaction byte identity (§16a) | PROVISIONAL: exact bytes within one history generation; after compaction identical forms and shown values with fresh handles and timings | TURN:1112-1123 |
| 18 | Interned (callee, arity) identity family (~½ day, 4,892 entities, real refs) vs the `[string long]` tuple | tuple recommended (DEC:811) | unsettled.md:543 |
| 19 | Agent-facing adoption: does `my.edit` request in-process adoption, or does `my.test/check` adopt changed `src` namespaces? | F+B recommended | unsettled.md:696-727 |
| 20 | Write admission as a final reducer-report validation seam in our Datahike fork (a failure rejects the whole transaction; no transaction function re-executed) | orchestrator decided under F8; owner may veto; the fork commit joins the unpushed set | unsettled.md:942, :973 |
| 21 | The result mechanism placed at the recorder (`seon.error/prepare`) rather than the constructor | owner veto point | unsettled.md:6202 |
| 22 | Merging `steward-platform` into `main` | waits for the owner | unsettled.md:5207 |
| 23 | Is `seon.bootstrap`'s generated opening dead code or the target path? | ask | unsettled.md:828 |
| 17 | `:seon.fn/sym` to `:db.type/symbol` after the identity probe | approve after the probe — largely executed by the symbols-everywhere ruling (F5), confirm it is closed | DEC:803-810 |

---

## 7. What the archived schedules forecast — read this before deleting them

The plan directory's 13 wave and bridge-step specs price their own work in
lane-days: bridge step 2 3–5, step 3 5–7, step 4 2–3 (+0.5–1 optional), step 5
2–3; wave 1b 2–3, 1c 3–4, 1d 1–2, 1e 2–4; wave 2a 2–3, 2b ~1.5–2.25, 2c-turn
~1–1.75, 2c-transcript ~0.9–1.5, 2c-plan ~0.75–1.25; wave 3a 4–6, 3b 3–5, 3c
1.5–3; wave 4 6–10; wave 5 3.5–5.5 plus 1–2 for the demonstration; gate
restructure 1.5–2. The publication-dissolution spec is unpriced.
**Total: roughly 47–75 lane-days**, and every spec states that coordination,
reset batches, held-path release queues and the orchestrator's cold and
platform gates are ADDITIONAL. Several of those numbers are revisions UPWARD
from the parent PRDs' ranges after census work (steps 2, 3, 5, waves 4 and 5),
so the archived files hold the only record of why the cheaper estimates were
wrong. Two things are genuinely lost rather than merely forecast:
`wave-3a-launch-command-2026-09-23.md` is marked ready-to-launch, so archiving
drops a queued launch and not only a plan; and the per-spec acceptance matrices
(9 named proofs for bridge step 2, the 1e checker that must fail on an empty
catalog, wave 3c's one-turn judging bars for five recorded openings) are the
most concrete statements anywhere of what "done" looks like for each capability
— §2 above carries their substance, not their detail. Sources: the per-spec
rows are enumerated in this note's §2 acceptance column;
the estimates are at `bridge-step2:241`, `bridge-step3:181`, `bridge-step4:173`,
`bridge-step5:168`, `wave-1:233,372,482,583`, `wave-2:234,416,538,589,640`,
`wave-3a:184`, `wave-3bc:247,525`, `wave-4:832`, `wave-5:738-739`,
`gate-restructure:415`.
