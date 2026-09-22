---
type: implementation-evidence
date: 2026-09-22
status: two implementation slices; focused proof and landing record below
---

# Indexer partition and host-bound facts

## Partition slice

Entity schemas declare `:seon.program/partition`; the projection validator refuses
an identity-bearing stored entity schema without program/data ownership. Generated
configuration entity schemas declare data at their producer in `schema/edn.clj`.
`program-attributes` reads the compiled registry, includes component declarations
and projected schema metadata (including render pairs), and excludes entries owned
by another writer. The two literal identity/source enums are removed. Source-row
identities derive from the existing row-schema declarations; these retain the
source replacement contract, while `program-attributes` covers all analysis rows.
There is no production family or attribute roster.

The resource sweep classifies every identity-bearing entity schema, including the
48-outside-six population in the historical pack. Program means declarations and
analysis; runtime tasks, configuration, errors and outcomes are data. Schedule
tasks are runtime state; call-preparation rows declare contract suppliers and are
program. `seon.fn.output` and `seon.fn.manifest` describe return values, not stored
entity schemas. `seon.schema.map-entry` and `seon.fn.contract.finding` attributes
are covered by their containing program schemas, not a fabricated entity root.
The current producer treats `:seon.test/usage` as an authored declaration marker;
the pack's description of that attribute as a run outcome is stale.

| Entity schema | Partition |
| --- | --- |
| `:seon.issue.citation/citation` | `:seon.data` |
| `:seon.maintenance.receipt/receipt` | `:seon.data` |
| `:seon.fn.binding.child/row` | `:seon.program` |
| `:seon.maintenance.result/entity` | `:seon.data` |
| `:seon.issue/issue` | `:seon.data` |
| `:seon.ns.import/binding` | `:seon.program` |
| `:seon.turn/turn` | `:seon.data` |
| `:seon.error/error` | `:seon.data` |
| `:seon.maintenance.request/entity` | `:seon.data` |
| `:seon.cluster/cluster` | `:seon.data` |
| `:seon.fn.file/file` | `:seon.program` |
| `:seon.error.occurrence/blob` | `:seon.data` |
| `:seon.fn.argument/row` | `:seon.program` |
| `:seon.schedule.task/task` | `:seon.data` |
| `:seon.ns/ns` | `:seon.program` |
| `:seon.runtime/entity` | `:seon.data` |
| `:seon.dev.mcp.artifact/entity` | `:seon.data` |
| `:seon.error.occurrence/occurrence` | `:seon.data` |
| `:seon.fn.arity/row` | `:seon.program` |
| `:seon.ai.model/deepseek-window-entity` | `:seon.data` |
| `:seon.schema.shape.entry/row` | `:seon.program` |
| `:seon.message/message` | `:seon.data` |
| `:seon.ns.alias/binding` | `:seon.program` |
| `:seon.db.process/process` | `:seon.data` |
| `:seon.context.capture/capture` | `:seon.data` |
| `:my.plan/agent-state` | `:seon.data` |
| `:seon.schedule.fire/fire` | `:seon.data` |
| `:seon.schema.shape/row` | `:seon.program` |
| `:seon.fn.binding/row` | `:seon.program` |
| `:seon.test.report/report` | `:seon.data` |
| `:seon.test/test` | `:seon.program` |
| `:seon.test.failure/failure` | `:seon.data` |
| `:seon.agent/agent` | `:seon.data` |
| `:seon.ai.model/provider-entity` | `:seon.data` |
| `:seon.cluster.eval/receipt` | `:seon.data` |
| `:seon.schedule/schedule` | `:seon.data` |
| `:seon.ai/attempt` | `:seon.data` |
| `:seon.fn.contract/finding-row` | `:seon.program` |
| `:seon.context.contribution/contribution` | `:seon.data` |
| `:seon.effect/receipt` | `:seon.data` |
| `:my.plan/entity` | `:seon.data` |
| `:my.plan.item/item` | `:seon.data` |
| `:seon.fn/fn` | `:seon.program` |
| `:seon.lint/finding` | `:seon.program` |
| `:my.note/note` | `:seon.data` |
| `:seon.cluster.instruction/instruction` | `:seon.data` |
| `:seon.call-preparation/row` | `:seon.program` |
| `:seon.ai.model/entity` | `:seon.data` |
| `:seon.ns.refer/binding` | `:seon.program` |
| `:seon.schema.shape.child/row` | `:seon.program` |
| `:seon.test.run/run` | `:seon.data` |
| `:seon.fn.binding.entry/row` | `:seon.program` |
| `:seon.schema/schema` | `:seon.program` |
| `:seon.config/entity` (generated) | `:seon.data` |
| `:seon.config/agent-overlay` (generated) | `:seon.data` |

## Evidence and foreign boundaries

Default was observed through `bin/seon status`, MCP `runtime_status` and read-only
MCP JVM evaluation with explicit root `/Users/sean/src/seon`, cluster `default`,
connection `(seon.cluster.boot/connection "default")`. PID 51528 answered every
readiness/proc ping; 14 errored receipts were already present. No default reset,
stop, source reload or SCI mutation was performed.

The shared source has simultaneous changes in program comparison, instrumentation,
cluster agent and hook tests. A HEAD snapshot at `295c03f6a` with only this lane's
partition patch and the existing reference-code links supplied isolated evidence.
The initial shared scratch boot refused the foreign `prepl-value!` arity in
`test/seon/dev/hook_test.clj:90`; an own missing `clojure.set` require was fixed.
The snapshot from-zero reset of `tmp/indexer-facts-root` succeeded: PID 78340,
start `2026-09-22T19:41:50.792Z`, ready in 168662 ms, published commit
`6ab2daad-fa1f-5541-bc1f-c2eecd5a0045`. This is cold boot evidence, not a claim
of seconds-level publication. Logs: `tmp/indexer-facts/snapshot-boot.log` and
`snapshot-boot-threads.txt`. The root was subsequently reopened, exported via
`seon.cluster/publication-base!`, and stopped through the operator before testing.

Single query:

```clojure
(seon.db/q '[:find [?e ...] :in $ [?a ...] :where [?e ?a]]
           database (seon.program/program-attributes projection))
```

The fresh publication measured 185 attributes and 36466 distinct rows. Census:
fn 4445; test 2073; schema 3291; namespace 410; file 702; lint 744;
fn arity 1942; fn argument 3397; schema shape 5390; shape child 3858;
shape entry 651. These are measured current counts, not the pack's historical
fixture counts. Raw result: `tmp/indexer-facts/partition-census.edn`.
The regression compares the single query's census to independently selected
entity-family counts on the canonical fixture, including components, and checks
program inclusion/data exclusion. The second regression proves missing partition
refusal and explicit program/data acceptance. Both passed with real contracts
armed (1697) and a canonical fixture exported from the scratch publication;
`tmp/indexer-facts/partition-focused.log`.

The requested `bin/test-fast --paths <owned paths> -- seon.fn-test
seon.program-test` ran (98 tests, 426 assertions, 11 failures, 54 errors;
`partition-tests.log`). This is NOT green. Three-question triage: no deleted
machinery is being retained; stale digest/renderer expectations were corrected;
the old published fixture lacks partition declarations and correctly refuses.
Surviving-seam failures include a declaration-world call count (2 vs 1) and a
prebuilt-manifest bound (13.5 s vs 5 s); these are not silently excused. Thread
capture of the long full-publication fixture shows existing ancestor ownership
validation in `seon.db/write-owned-values-error`. A direct runner attempt without
its git-sha/descriptor failed initialization, so it supplies no test evidence.
Platform/cold gates remain the orchestrator's responsibility.

## Host-reference producer

The owner ruled on 2026-09-22 that `src/seon/fn/analyzer.clj` belongs to this lane
for exporting clj-kondo's existing Java-reference facts. No second parse or
resolution pass is introduced. clj-kondo gitlink
`57252e07975710aa579b24f0d1b2b1e04195caa2`,
`reference-code/clj-kondo/src/clj_kondo/impl/analysis/java.clj:328–355`, records
resolved class/method/source coordinates; `core.clj:240–241` exports the collection.
The existing usage attribution joins those coordinates to declaration spans.
The indexer compares those resolved class names to SCI's admitted classes and
stores a boolean per function row before unresolved references are hoisted.
Recomputation follows the existing analysis event; added work follows its Java
reference population, not a walk of transitive callers.

SCI pin `fcbd8862800e638dc0f8f5521111f999279cbcd2`: `sci.impl.opts/default-classes`
is the dependency authority; `src/seon/sci/eval.clj` adds Throwable and Error.
Malli pin `606083c5c5b388e84d169c7080af33ed3ec242ae`: the compiled registry and
entity-entry traversal remain the schema authority. No alternate registry.

Live read-only probes (12 ms each) used source `(ns indexer-facts.probe)
(defn clock [] (System/currentTimeMillis)) (defn date [] (java.util.Date.))
(defn caller [] (clock))`. The old analyzer exported only var usages. Enabling
clj-kondo `:analysis {:java-class-usages true}` returned java.lang.System at
column 41 and java.util.Date at column 83, and no Java reference for caller.
The pack's per-row unresolved-ref premise was stale: current fn.clj attaches the
unresolved collection to namespace rows. Exporting the dependency facts repairs
that producer boundary.

Orchestrator conversion in the separately owned `src/seon/sci/eval.clj`:
replace the body of `host-bound-row?` with `(true? (:seon.fn/host-bound? row))`.
This lane does not edit that file. The current refused-head set excludes
`defprotocol`; protocols remain supported. The historical 21-head census includes
protocols and cannot be copied as an assertion of the current refusal rule.

## Landing record

The partition slice was first committed as `904a1b205` in the isolated snapshot.
Final shared-HEAD commit ids are recorded in `tmp/orchestrator/indexer-facts-summary.txt`.

### Partition load and publication clock

Required four-namespace load exited 0 (`partition-head-load.log`). The committed
measurement script completed using `tmp/indexer-facts-clock-root`: cold wall
137536 ms (boot ready 123256 ms), fork 454 ms, first adoption 342 ms, unchanged
347 ms, non-core edit 6018 ms, core edit 22489 ms. Heap observations: 977 MB after
cold, 3503 MB after core adoption; store 91 MB to 156 MB. `publication-clock.log`
and the clock root's `storage.edn` retain exact rows. Sweep refused missing
`:seon.config.db/snapshot-window-ms`; no retention pass claimed. Official down
completed and both measurement edits were reverted by the script in the snapshot.

The live identity census now has 55 attributes (`identity-census.edn`). Besides
the table's explicit entity identities, inherited alias identities
`:seon.context/selection-agent-id`, `:seon.message/unknown-recipient`,
`:seon.sci.eval/schema-refused`, `:seon.test.runner/default-cluster-refused`,
`:seon.test.runner/long-test-ns-hook`, `:seon.turn/compaction-agent-id` are error
values (data), not additional program families. `:seon.problems/id` identifies a
returned problem observation (data). `:db/ident` is database schema (program);
`:seon.source/digest` is the publication input observation (data), distinct from
stored declaration analysis digests. Their current declarations do not introduce
additional stored entity roots requiring an invented map schema.

### Host-bound slice and evidence

The analyzer retains Java class usages, applies its existing declaration-span
attribution, and exports resolved class names with locations and caller ownership.
`fn.clj` stores true/false on every analyzed function row, both file indexing and
runtime form analysis. The boolean is excluded from the authored definition digest.
The tiny fixture covers constructors, static calls, class literals, unresolved type
hints, admitted classes, imports, reify, generated type constructors, protocols,
quoted forms, and an ordinary caller. Quoted macro names are not calls. The pack §3 explicitly classifies import calls as host-bound operations. An
exact SCI probe importing `java.lang.String` succeeded (7 ms), but clj-kondo
exports no Java-class fact for either quoted import form (13 ms). The import
operation rule is retained, rather than adding another resolver. The same SCI
options refused a `java.io.File` type hint (11 ms).

Read-only MCP against the new scratch JVM exported one Java usage for
`(System/currentTimeMillis)`, with `:from indexer-facts.probe` and
`:from-var clock`, and no Java usage for its plain caller (15 ms). Another probe
showed clj-kondo records host type hints but not primitive hints or quoted class
symbols (105 ms). The old default JVM stayed at PID 51528 throughout these probes.

From-zero host-schema boot: PID 89286, start `2026-09-22T19:59:55.529Z`, ready
143854 ms, no missing readiness layers, published
`6ab2decc-ae86-5bac-aa76-da98e0adf061`. Its canonical publication census is
4447 functions: 854 host-bound, 3593 ordinary; host-bound heads are 613 defn-,
228 defn, 6 defrecord and 7 deftype. Every function has a boolean. The larger
body population than the pack's ~310 reflects resolved analyzer references,
including type hints, rather than source regex estimates. These measured counts
precede the final exclusion of quoted form names; they are
not asserted as final-source constants. The canonical regression asserts full
population coverage and independently checks the real `seon.id/sha-256` / `id`
host/caller pair. The initial test mistakenly expected `digest` to be host-bound;
inspection showed it only calls `id`, so that expectation was corrected.

`host-cold-boot.log`, `host-census.edn`, `host-fixture-export-2.log` and
`host-down.log` retain the boot, stored query, canonical export and confirmed exit.
The first export correctly refused to overwrite an existing fixture; a new
export path was used. That refusal is not a boot failure or a fixture pass.

Full fresh-fixture run through the armed production runner: 98 tests / 590
assertions / 8 failures / 27 errors (`fresh-regressions-3.log`,
`fresh-results.edn`). No full-suite green is claimed. Three-question triage:

- No obsolete mechanism is preserved to make a test pass.
- Retired expectations: reader rows now carry definition digests; observed writes
  include undeclared keywords; renderer admission needs the canonical projection's
  function contracts. Those in-scope tests were updated. The census expectation
  named the wrong host/caller and was corrected as described above.
- Surviving foreign seams: `test/seon/test_support.clj` and runtime namespace
  admission create namespace rows without the required definition digest;
  `canonical-schema-rows` is called with a projection missing its new key;
  prebuilt-manifest reconciliation removes a required issue-citation file; the
  manifest path resolves declarations twice. Protected owners were not edited.
  Four tests also exceeded their existing 5 s bounds (fixture observation,
  prebuilt manifest, populated-branch refusal, reference selection). Those are
  failures, not warming exemptions. Thread evidence is in
  `fresh-regression-threads.txt`; the runner exited normally, with no signal sent.

A resumed clock against the older head branch refused issue adoption with
`:malli.core/invalid-schema` (nil schema); its head also honestly refused a read
of the uninstalled host-bound attribute. The branch was not newly adopted merely
because its JVM loaded new source. A subsequent from-zero clock on
`tmp/indexer-facts-host-clock-root` completed: cold wall 164942 ms, fork 535 ms,
first adoption 562 ms, unchanged 410 ms, non-core edit 7025 ms, core edit 25131 ms.
Heap and storage rows remain with that clock's `storage.edn`; retention sweep
again refused the undeclared retention setting. The script confirmed process
94236 exited and restored its two measured docstring edits.

**Rollout boundary — RESET NEEDED under orchestrator custody:** new analyzer
facts require a complete publication on an older branch before the SCI consumer
switch. An export from the older clock root contained 4218 unchanged function
rows without the new fact, despite 229 re-analyzed rows having it. This is the
existing incremental manifest's retained-analysis boundary, outside the assigned
analyzer region. The fresh from-zero publication has all 4447 booleans. Do not
interpret absence in an old publication as an affirmative ordinary-row fact.
The separately owned consumer conversion is still one attribute read, after
that complete publication. Default was neither reset nor stopped by this lane.

### Final focused verification

The production runner with 1699 armed contracts and the fresh canonical fixture
ran seven selected regressions: both partition tests, both host-bound tests,
observed write retention, exact re-index replacement, and schema-property
projection using the real function contracts. **7 tests, 30 assertions, 0 failures,
0 errors**, including the runner's existing duration checks
(`focused-final-2.log`, `focused-final-results.edn`). The prior import experiment
failed one assertion; the explicit pack §3 operation rule above resolves it.
No production logic changed after this passing run; only explanatory prose.

The two slices are path-limited commits. No SCI consumer, reverse walk, manifest
implementation, agent owner, instrumentation, hook or protected fixture helper is
part of either commit. Verification ran on the HEAD snapshot plus owned changes;
foreign in-flight program-comparison hunks were excluded.

### Final integration boundary

The two isolated commits rebased cleanly onto shared HEAD `2920fa971` without
incorporating another lane's uncommitted hunks. The rebased partition HEAD passed
the exact required four-namespace load (`landed-commit-1-load.log`). The rebased
host HEAD uses the same command (`landed-commit-2-load.log`); its result and final
commit ids are in the summary. The first slice changes 55 files (379 additions,
78 deletions including this note); the second changes seven owned files (analyzer,
indexer analysis, fn schema, program digest exclusion, the two test namespaces,
and this note). Exact final sizes are recorded in the summary.

All owned runtime roots are disposable; clock/export logs and raw probes are
retained under `tmp/indexer-facts/`. Confirmed process identities, exit results,
root removal and final default observation are recorded in
`tmp/orchestrator/indexer-facts-summary.txt`. No shared shell, runtime, or foreign
session is stopped or resumed.
