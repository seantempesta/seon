---
type: research
status: in progress
created: 2026-09-21
tags: [agent-platform, reset, platform, verification]
---

# Fresh implementation baseline

The owner authorized the database reset, complete fresh index, disposable tmp/build cleanup,
platform/dependency verification, and a new plan-named branch before implementation.
Host: macOS 27.0 build 26A428, OpenJDK 26.0.1, Babashka 1.12.212,
Clojure CLI 1.12.5.1654. Initial `bin/seon status` and MCP status reported the
old JVM absent and its advertisement stale; this is not an observed OS startup defect.
No JVM or test runner was live at the initial cleanup boundary.

The four inherited publication edits are preserved. They were inspected before reset;
they change report-based caller selection and namespace reload scope. Their initial bytes:

| Path | SHA-256 |
|---|---|
| `src/seon/cluster.clj` | `78d59d22768bfd338c1755d70738d61e2ba1c7fbc3fab9085b78dd3dd26a0fca` |
| `src/seon/fn.clj` | `8743b1aef49fb39a1d02453a5f7099d4be010129a0682da800269a96a91704ae` |
| `test/seon/cluster/publication_delta_test.clj` | `060f024d3a8282c931183cae73ae7823e85b51a4208254b407aa4a73d73525a6` |
| `test/seon/fn/publication_cache_test.clj` | `e7937d7edb28244b0ac44624422bddb7af11600e34bc12ae61d726fc8f454aa4` |

## Verified preparation

- Integrated plans and six architecture documents committed at `f6216bd26`.
  Local Markdown paths and whitespace checks passed; no audit appendices remain
  in `plan/`. The proposed AGENTS remains a proposal, not the live root instructions.
- Removed the previous untracked `target/` output and old tmp logs/lane scratch.
  Native recursive removal completed successfully. New output and test scratch
  belong to this verification and will be swept only after their processes exit.
- `bin/seon reset --force` completed successfully. It reclaimed 169,492,916 bytes
  from the old store, rebuilt the 373-namespace dependency cache, performed a
  complete index, started default and adopted the publication. See
  [reset output](fresh-start-reset.log). Reset lifecycle: 308,518 ms, plus
  26,241 ms preflight; this includes the explicitly authorized initial index.
- Default PID 12119 has no orphan Seon JVM. Its host JVM MCP probe returned in
  22 ms: carried projection present, 4,601 function rows and 2,153 test rows.
  All reported Flow procs replied. This proves boot/access, not complete health.
- Browser inspection of `/` found repeated render refusals from
  `seon.sci.eval/install-function-from-database!`. Boot also reproduced the
  existing reply-source map/vector contract fault. Both remain under repair;
  do not call this a stable baseline until live proof and the platform gate pass.

## Remaining preparation

Complete the conservative inherited-publication repair and the fresh boot/render
repairs; run canonical targeted and platform gates, verify fresh live behavior,
sweep holderless verification roots, record final footprint and create/switch to
`refactor/agent-platform`. No main refactor implementation is authorized by this
preparation checkpoint. Source changes here are bounded baseline repairs.


## Gate bootstrap boundary

The first HEAD-base preparation exposed the export-inventory defect, repaired at
`f2c7bf4bc` with 4 Babashka tests / 26 assertions and the real 702-input export
comparison. The next preparation refused because the pending reply schema differed
from HEAD's snapshot resources. The reply symbol/string storage change requires
a reset. All changed source owners passed a fresh JVM require before committing
the coherent baseline repairs; canonical gates follow the committed snapshot and
batched reset. These commits alone are not acceptance evidence.

The subsequent reset rejected the reply adapter's boolean-only no-forms error
declaration before publication. `0ed46d64c` adds required authored reply text;
the full `seon.schema/build-projection` over `packaged-forms` then validated
3,337 schema declarations (see `fresh-start-schema-validation.log`). Continuing
the operator's printed recovery sequence rebuilds current-src before refork/start.
The rejected reset stopped the previous default; its earlier PID and observations
above are historical evidence, not a claim that it remains running.

The test-selection investigation established source-matched inferred call edges,
including `pull-many → transact!` through diagnostic operation symbols. The
graph-fidelity issue records the exact live queries. `7e852372c` makes separation
of real calls, callable dependencies and descriptive symbols a B1 prerequisite
for B4 precision claims. Neither the broad shared-function samples nor these
incorrect edges establish the average number of tests affected by an ordinary edit.

## Current live proof after repaired boot

Recovery completed: full publication 277,893 ms, default fork 9,924 ms, start
15,725 ms, then unchanged development adoption 2,179 ms. Logs are
`fresh-start-republish.log`, `fresh-start-refork.log`, `fresh-start-start.log`
and `fresh-start-adopt.log`. Default PID 16805 uses prepl 51325 and web 7994;
published/adopted commit is `6ab18937-ff35-559a-a665-022e153f3027`.
The browser was reloaded and observed rendering identity, settings, runtime,
fault and cluster blocks without the previous renderer refusals. Its cluster
block reports identical current/adopted commits. Armed live reply parsing
accepts vector source and returns complete errors for empty/prose/tag input.

This is still not complete health: actual stored evaluations of `(help)` and
`(seon.agent/settings)` report zero-argument refusals. These are under bounded
investigation; the startup page reports no routed core fault. Canonical gates
are still pending the coherent dependency-selection correction explicitly
approved by the owner. The owner selected declarations on actual dispatch
owners, not merely relabeling the overbroad inferred edges.

At this checkpoint `du -sh` reports store 151 MB, target 246 MB and temporary
verification output 320 MB; operator verbose status reports a broader root
footprint of 1.69 GiB and no orphan Seon JVMs. The database contains 2,156
test identities, including 110 platform declarations, measured with installed
`:seon.test/sym` and `:seon.test/platform` facts. Temporary verification roots
will be removed once no active work references them.

## Corrected dependency graph and startup

`33842ead4` contains the coordinated invocation declarations and twelve intended
database-default contracts. Fresh source/test namespace load and complete schema
validation passed (3,338 declarations). A batched reset completed in 366,653 ms
and adopted publication `6ab18d2a-54c1-5bdc-af7e-87cacae1cf5c`.

The committed [selection probe](selection-proof.clj), evaluated through MCP JVM
mode at basis 536870930, found both previously phantom call edges absent, both
source functions present, all six actual invokers/eight attribute declarations
present, and the genuine renderer target retained. Six gate sets together took
1,020.235 ms (whole MCP form 1,059 ms):

| Changed function | Selected tests |
|---|---:|
| `seon.cluster.reply/sources` | 259 |
| `seon.id/symbol-in` | 1,286 |
| `seon.id/valid?` | 18 |
| `seon.render.test/render-html` | 296 |
| `seon.db/pull-many` | 549 |
| `seon.db/transact!` | 1,398 |

These samples are not a production average or a claim that arbitrary dynamic
dispatch is complete. The probe was read-only. A subsequent 4 ms database query
found actual fresh evaluations of `(help)` (37549) and `(seon.agent/settings)`
(39309), with no stored evaluation errors. Browser reload observed rendered blocks,
an idle root, and zero open turns. The test preparation subsequently advanced
current-src; final development adoption after gate recording is still required.
These live checks do not replace the canonical gate.

## Canonical execution boundary

The combined gate on `33842ead4` recorded 162 errors before test bodies ran:
the worker constructed a lazy SCI context, then attempted resolution without
acquiring its execution program. See [gate evidence](fresh-start-combined-gate.log)
and [the existing issue](../../../seon/issues/task-execution-fixture-has-no-acquired-sci-program.md).
`98d1a8ede` acquires an unacquired context through the existing owner and loader,
while retaining refusal of an already acquired mismatched program. Changed
source and test namespaces loaded successfully in a fresh JVM.

The combined rerun includes 186 members across thirteen namespaces, including
the runner's first-use and mismatched-program regressions. Its
[output](fresh-start-combined-gate-repaired.log) confirms body execution, but
worker readiness still preceded initial shared-context acquisition and the
first task reported that population as global-state drift. That initialization
boundary is under repair; neither gate is a passing baseline. The platform
checkpoint, final adoption, hook re-enable, holderless-root sweep and branch
switch remain pending. The main refactor has not started.

The repeated live health query reports all Flow procs answering, but three
durable error signatures appeared after the immediate boot proof:

- `1f3311909efaaf42cf900d6185f1652e95726b7a96b5016d5b64105d7d95dafd`:
  five refusals from `install-evaluated-rows!`, called by `seon.turn`, with
  a missing database argument.
- `399900708dc925ff405ca412d44b68d13884bbf3d5d4975a64dde090dd766063`:
  maintenance settlement refused a dead process identity requiring the
  observation-only `:seon.operator.process-census/alive?` field.
- `786ada69cf1ff11fd1b3f0ab71d5816962a77722da402e5b2af9af5b78b5ef1b`:
  a page-package await exceeded 30 seconds.

These invalidate a blanket stability claim. The compact query was
`seon.problems/problems` with the explicit default database and `{}` request,
projecting signature, operation, occurrence count and occurrence message.
The full rendered evidence remains blob
`a4d4f0c783f47af3421acd1360f2b76295ddb6da6e172bb207fe95cd80ceb2ee`.

The whole-program population regression completed in 216,062 ms against its
declared 180,000 ms. While the selection history regression ran, a virtual-thread
inclusive [worker sample](selection-worker-threads.json) observed its
`selection-facts` query inside Datahike result-cache weight calculation. One
sample does not establish the dominant cost or justify a dependency patch.

## Latest checkpoint and bounded follow-up

The combined checkpoint completed: **186 executed, 0 unchanged, 1,422 assertions,
51 failures and 13 errors**, coordinator/test phase 906 seconds. This is not a
green baseline. Seventeen reader assertions expect retired string identities;
eighteen failures are declared-duration overruns. Other cases include obsolete
query injection, raw fixture databases lacking carried projections, inconsistent
publication-fixture inputs, and unresolved production/fixture ownership questions.
Do not label every failure pre-existing without a baseline comparison.

Triage of the pending-selection assertion found that its helper opens a second
admission after the first already owns those members. The writer correctly
returns covered-by references, and the helper then completes no owned members.
The prerequisite repair must settle the original admission; production reservation
and reuse must remain intact. Named-selection and actual execution-reuse checks
reported duration failures only, not functional assertion mismatches.

Committed bounded repairs:

- `75e95678e`: acquire worker SCI before readiness; preserve drift detection.
- `47c6d1123`: nonindexed fixtures participate in external input invalidation;
  production Babashka owner check passed 5 tests / 47 assertions.
- `a64504302`: dead process census relation uses identity component schema.
- `087ee4d5f`: preserve kind-free writer refusals through existing turn settlement.

All changed source/test namespaces loaded in a fresh JVM. In-place development
adoption completed in 34,759 ms, installing current-src
`6ab193b5-508d-500e-a185-87fe4008c536` with program digest
`f7f68cb716fb12725cb5e75c4fc2e7bec045cefd5b25e56e32204aa256d709b3`.
This is adoption evidence, not proof of recovery of the previously open root turn.
An affected runtime checkpoint is running separately; targeted graph/publication
fixture repairs are in progress. Broad legacy-test repair is not a new workstream.

The owner requested another Fable perspective. The committed
[review brief](../research/fable-second-perspective-brief-2026-09-21.md) describes
the completed integrated Astra review, preparation evidence and remaining limits.
Fable owns documentation review only; Codex retains source/preparation custody.
