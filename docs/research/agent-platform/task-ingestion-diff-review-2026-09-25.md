---
type: review
status: revise
created: 2026-09-25
---

# Task ingestion diff review — 2026-09-25

**Verdict: revise.** No P0 found in this bounded, unwired bootstrap slice. P1 correctness and contract defects below prevent acceptance. The owner's acceptance of 156 production/schema lines stands; size is not a rejection reason.

Reviewed exactly `git diff 1de64b45b^ 55cebad48`, including all four commits, against [the landing](../../prds/agent-platform/landing/lane-impl-task-ingestion-2026-09-25.md) and [language requirements](language-requirements-2026-09-24.md), especially 1.5, 1.9, 1.15, 2.5, 3b.6 and 7.1–7.17. Source line references below refer to `55cebad48`. This is static review: no code edits, tests, JVM probes or `bin/seon` commands. The landing explicitly has no installed-change proof; its analyzer/Datalog experiments and clean lint do not establish integration, arming or performance.

The existing composition is appropriate: analyzer metadata → program value edges; edamame → task declarations → `seon.issue/replacement-tx` → database writer; Datalog derives neighbours. Keep those owners. Fix the admission boundaries rather than introducing another task registry, parser or scheduler.

## P1 findings and smallest fixes

### 1. The identity rule was replaced without authority

`resources/seon/schemas/seon.task.edn:2–4` and the seed at `resources/seon/tasks/seon.fn.edn:1` make an arbitrary component/name keyword the identity. The landing's identity paragraph explicitly excludes `seon.id` for authored tasks. Spec 3b.6 explicitly requires it for authored tasks too, over name plus component/subject kind; 2.5 excludes mutable content from identity. A literal token alone does not implement that rule.

**Fix:** use existing `seon.id/id` (`src/seon/id.clj:41`) on an ordered identifying aspect, retain the readable name/aspect as declared data, and write the resulting stable handle literally in EDN and annotations. Verify the supplied handle against that aspect at admission. Change the seed consistently. Do not hash intent/evidence or mint identities while re-ingesting. Prove same aspect plus changed intent retains identity. Any alternative needs an explicit amendment to 3b.6, not the landing's reinterpretation.

### 2. Transaction-time recomputation is not revision conflict detection

`src/seon/issue.clj:553–573` takes only desired file text. There is no caller basis or expected task revision. If a worker changes intent after the file author's read, `tasks-tx` sees the new value and replaces it with stale file content. `:db.fn/call` serializes that overwrite; it does not detect it. This violates 7.16 even before the runtime create API exists. The preliminary no-op check also does not establish a writer-validated basis.

**Fix:** pass the task declarations/revisions read with the proposal to this same writer; compare them with transaction-time current values and return a named conflict carrying base/current/proposed before producing changes. Use immutable declaration content or existing database history, not a connection-side version atom. Keep unchanged/no-write semantics, with a defined observation basis. Test two edits from the same basis: the second refuses and preserves the winner.

### 3. File admission silently ignores trailing input and loses offending values

`src/seon/issue.clj:543` calls `edamame/parse-string`, which reads one form. Thus `[] [#:seon.task{...}]` is accepted as an empty file: subsequent entries are ignored, potentially retracting existing tasks. Invalid trailing bytes are likewise unread. Reader failures escape without a task refusal. At `:547–548`, `me/humanize` replaces the invalid authored entry with explanatory strings in `:seon.error/offending`.

**Fix:** use edamame's existing all-forms reader and require exactly one vector, validating the original value with Malli. Convert declared reader failures into the named task diagnostic while retaining their cause and location; retain the authored offending value alongside the explanation. No regex/source parser. Test trailing forms, malformed syntax, `#=`, unknown members and non-vector roots; verify no mutation on each refusal.

### 4. The deftest placement check mistakes ordinary body data for metadata

`src/seon/fn.clj:707–708` refuses any test whose `used-keywords` contains `:seon/tasks`. For example, `(deftest t (is (= #{} (:seon/tasks {:seon/tasks #{}}))))` is legitimate code but triggers `:metadata-after-test-name`. Membership says nothing about where the keyword occurs. Conversely, the `::` check at `:623–627` searches the whole definition span: a literal annotated id and a matching auto-resolved keyword in its body can be misclassified as auto-resolved annotation metadata.

**Fix:** use the analyzer's actual metadata/form positions to identify the forbidden post-name attribute map and auto-resolved tokens inside the annotation value. Keep body usage legal. If current analysis lacks the position, extend that producer's data rather than scanning text. Add paired positive/negative cases for both rules.

### 5. Empty optional sets break the promised no-op

The task entry accepts `:must-read #{}` and `:evidence #{}` (`seon.task.edn:5–6`). Cardinality-many storage has no datom for an empty set. On re-ingestion, `replacement-tx` compares absent `nil` with desired `#{}` (`issue.clj:307–318`) and emits an assertion again. `index-tasks!` consequently invokes the writer for unchanged valid input. The existing test covers only a nonempty must-read set and absent evidence.

**Fix:** canonicalize empty optional collections to absence once at task declaration admission, or explicitly require nonempty authored optional sets. Test empty collections, clearing a formerly nonempty collection, and a second ingestion with unchanged commit identity/no writer report.

### 6. Read/write refusals are converted into success-shaped results

`open-tasks` (`issue.clj:594`) selects `:seon.task/tasks` from a possible `db/pull` error and returns `#{}`. `db/pull` declares invalid-read and pull-budget errors (`db.clj:2588–2605`). The other lookups coerce `db/q` results without checking its declared error alternative (`db.clj:2365–2368`); `tasks-tx` also treats query output as rows. `index-tasks!` nests a refused `db/transact!` result under `:seon.db/transaction-report`; its broad `:map` alternative admits that wrapper.

**Fix:** propagate each owner's declared errors before selecting, iterating or wrapping values. Specify those alternatives in named result contracts. A successful report and a refused write must be distinguishable at the ordinary error boundary. Add failure-path coverage using real owner refusals, not Var redefinitions.

### 7. Named stage/error schemas and function contracts are incomplete

The closed authored `:seon.task/entry` is good. However, `task-links` accepts/returns generic maps; lookup outputs use undeclared unqualified keys `:subjects`, `:proofs`, `:must-read-referrers`, `:dangling`, `:triage` (`issue.clj:577–601`). These do not meet the named-stage requirement in 1.15 or fully namespaced-key law. `:seon.task/refused-error` (`seon.task.edn:20`) is an isolated map, not a composition over `:seon.error/base`; `task-refused!` constructs only exception data and eagerly prints the offending value into its message. The schema is not used to validate that boundary. Touched `namespace-row` and `var-row` still have no contracts; new test helpers `rows-of` and `refusal` have none either.

**Fix:** declare the few actual annotation/request/result shapes, use their named contracts and validate transformed values. Compose the task refusal with the existing error base and diagnostic constructor, preserving values until rendering. Contract the touched named functions and test helpers, then compile/arm through the packaged projection. Do not close extensible stored entity maps merely to compensate: the authored file entry is the boundary that must reject unknown keys. The stored task entity's open shape is consistent with accretion.

## P2 findings and smaller compositions

1. **Missing namespace reverse edge.** `task-edges` stores namespace must-read annotations but its referrer query (`issue.clj:587`) only admits function/test identities. Include `:seon.ns/name` in the same query. Prove namespace subjects and namespace referrers, alias resolution, and see-also preservation. No new lookup index is needed.
2. **Repeated scans and a second member list.** `tasks-tx` rebuilds the component set inside a lazy filter and repeatedly scans `stored` for each desired id (`:558–563`). Materialize components once and index stored rows once by id; desired/stored comparison becomes linear after acquisition rather than quadratic. Derive owned task attributes from the task declaration instead of the hand list `#{:seon.task/intent :seon.task/must-read :seon.task/evidence}`. `task-links` also computes each link twice; bind each once. Measure before adding any caching.
3. **File-set deletion/duplicates need an explicit boundary.** Omitting a removed file from `files` removes its component from consideration, leaving its task rows forever. Two paths with the same basename can provide duplicate ids; per-file duplicate detection misses them and `into {}` picks a winner. Keep an explicit changed-file input with deleted files represented as empty declarations, or reconcile an explicitly authoritative inventory; canonicalize/validate paths and reject duplicate ids across that input before building the map. Do not infer deletion of runtime-authored tasks from absence in a file listing.

## The two unwired pieces: smallest follow-up

These are acknowledged gaps, not hidden implementation claims. They must be completed before treating the feature as admitted task ingestion.

**Publication:** extend the existing `cluster/source.clj` publication orchestration beside `index-issues!` (`:513`, call at `:641`). Obtain task bytes and explicit deletions from the existing snapshot/change inventory, then call the corrected `seon.issue` writer. Include its report in the existing changed/no-op and readback decisions; a task-only change must not disappear through `unchanged?`. Verify task files participate in the installed input inventory; do not invent a second watcher or widen gates through documentation. Schema installation precedes task validation. On development/adoption destinations, reconcile data through this owner explicitly: do not add task rows to program merge.

**Dangling gate:** call the existing `task-findings` derivation on the candidate program with the destination's admitted task data; refuse each dangling handle with its holder and operation before destination program acceptance. Bind that decision to the tested destination/basis using the existing guarded acceptance path. A task admitted before a refused program gate remains valid/raised (7.13, 7.16). Triage is advisory. Validate at the destination, not merely on the source branch, and cover task removal with surviving annotations. Extend the existing gate; no second validator pipeline or dependency on task entities as refs.

Required focused integration cases: task-only publication and no-op repeat; removed task file; source annotation whose task is absent at destination; coherent task admission plus annotation; program-gate failure leaving admitted task data; and program merge carrying only keyword edges, never task rows. Schema annotations and runtime task creation remain explicit later work under 7.1/7.8, not capabilities this diff proves. Seed-edge support (7.9b) is neither changed nor independently proved here.

## What the diff gets right, and test quality

- Task identities are keyword **values**, not refs; links are native symbols. `:seon.task/task` declares `:seon.data`, while program rows carry only annotation edges. `seon.program/program-attributes` derives membership from schema partitions (`program.cljc:39–67`), so the design does not require a task-specific merge exclusion list.
- Reusing `replacement-tx` preserves unrelated task attributes for updates; analyzer rows incorporate edges before the definition digest. The annotation-removal test positively establishes two subjects and a proof before removing one edge. No task deletion is needed to settle that subject.
- Production parsing uses edamame and analyzer data, with no new regex. `task-component` uses a literal string replacement, not a regular expression. No explicit stored nil is introduced; optional links are omitted. Empty-set canonicalization still needs the fix above.
- The regressions use analyzed source, real writer calls and `with-database`, and clean temporary source files in `finally`. They do not fabricate production rows or redefine Vars. They are useful initial behavior tests.
- They do not prove installed/armed behavior, actual no-write commit identity, destination merge isolation, namespace links, empty optional collections, competing revisions, removal of task entries/files, full parse consumption or flat error schemas. Their final "dangling ids refuse" test only asserts a finding; rename it until a gate refusal is actually exercised. Fixture writes should use the canonical fail-fast transaction helper rather than an `is` that lets setup continue after refusal. Group cases by behavior rather than making one test cover every malformed input class.

## Dependency evidence, costs and verification limits

Dependency gitlinks at the reviewed commit:

- `reference-code/malli`: `befcd302a8e626a67d84b80aabee0cd1b38dadb6`.
- `reference-code/edamame`: `63373df6ab056d86d106a79caecc2ebfca18d357`.
- `reference-code/datahike`: `4157916b5a1b8d80d7f809c889bce1b098b1d75d`.

- Malli `m/explain` / `explainer`, `reference-code/malli/src/malli/core.cljc:2653–2676`, returns original value plus errors on failure and nil on success. Preserve that value; humanization is presentation, not the authored input.
- edamame `impl.parser/parse-string`, `:978–985`, calls `parse-next` once; `parse-string-all`, `:987–998`, consumes to EOF. This directly establishes finding 3 without execution.
- Datahike `db.transaction`'s `:db.fn/call`, `reference-code/datahike/src/datahike/db/transaction.cljc:1171–1172`, supplies transaction-time database state. This establishes writer recomputation, not authored revision checking.
- Added source is +129/−8 (net +121), schemas +27, task seed +4 and tests +62. The accepted 156 figure counts gross source plus schema additions. No latency or memory measurement was performed; none is claimed. The landing owes installed proof and parent/change indexing measurements; the repeated scans above are a source-derived complexity finding only.

Applied the data-oriented Clojure, data-modeling and clojure-testing skills for review. One relevant stale skill claim must be routed to its owner: the testing skill says `seon.test/run` is not an arming operation, but the current source's preparation invokes `instrument/apply!` (`src/seon/test.clj:2060`). That skill was not edited because this assignment authorizes exactly this review file. Neither that discrepancy nor another lane's state prevented this review.
