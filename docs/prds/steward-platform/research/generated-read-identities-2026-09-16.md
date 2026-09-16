---
type: research
status: active
tags: [research, render, database, test]
---

# Generated reference identities — 2026-09-16

Implementation: `474234fb7` on `steward-platform`. Live Juniper installation
and prompt acquisition succeed with the REPL-loaded candidate. Canonical
regression execution and completed development adoption remain separate
verification boundaries below; no green gate is claimed.

## Scope and authorities

Read AGENTS.md, docs/seon/issues/README.md and the complete
[dated class mining report](../../sci-execution-runtime/research/issue-class-mining-2026-08-11.md).
The concrete generated-read-identities assignment names no class issue or
member roster from that August report. It supplies a September platform
incident and its structural repair directly; this is not closure of an
unassigned N1, N7 or N9 umbrella.

Read the adjacent
[bound-selector evidence record](../../../seon/issues/bound-pull-selector-evidence-retains-all-attributes.md),
the archived
[walk identity record](../../../seon/issues/archive/render-walk-spells-declared-identities-as-raw-eids.md),
and the
[failed fixture delay record](../../../seon/issues/in-process-runner-rethrows-failed-fixture-delay.md)
end to end. Read both programs' roadmap entry points and working edges.
Applied data-oriented-clojure, repl, datahike and clojure-testing skills.

## Construction and repair

A generic reference-identity selector included every installed identity,
including attributes declared context-inert. Rendering a read's result thereby
introduced dependencies on turn-taking that were absent from the read itself.

**Guarantee:** the generic identity selectors used by value rendering and walk
reference leaves exclude exactly the attributes derived by the generated-read
check's shared `seon.cluster.wake/inert-attributes` query.

The polymorphic plan subject has no single declared target family. This uses
the assignment's “any identity” case. Already supplied identity maps retain
their identity. A generic reference whose only identity is context-inert
falls back to its entity ID; explicit domain selectors remain the way to
request that identity. The walk's own root attributes remain ordinary explicit
reads; this repair does not claim that arbitrary whole-entity reads are
independent of turns.

The old check is intact apart from extracting its exact declaration query.
No suppression of captured read evidence, hand list of forbidden identities,
Datahike change, or weakening of generated-read-fault was needed.

## Reproduction and introducing commit

Default PID 7595; host JVM MCP mode with the running handle's projection state
and explicit `(seon.operator/connection "default")`.

[Reproduction form](generated-read-identities-2026-09-16-probe.clj).
The original complete preview was first saved to
`tmp/generated-read-identities-before.edn` (187,307 bytes).
[Every offending read request](generated-read-identities-2026-09-16-queries.edn)
preserves all 14 requests, in order, from
`tmp/generated-read-identities-queries.edn`. These are **pulls**, not
Datalog query strings: twelve pull entity 43294 (Juniper) and two pull 43523.
All use this exact selector:

```clojure
[:db/id :seon.error.occurrence/id :seon.schedule/id :my.note/id
 :seon.fn.file/path :seon.call-preparation/key :seon.problems/id
 :seon.schedule.fire/id :seon.config/cluster :seon.ai.model/provider-id
 :seon.effect/id :seon.cluster.eval/id :seon.test.run/id
 :seon.context.contribution/id :seon.error/id :seon.context.capture/id
 :seon.message/id :seon.runtime/agent :seon.schema/key :seon.cluster/name
 :seon.ai.model/deepseek-window-id :seon.test/reach-digest :seon.fn/sym
 :seon.agent/id :seon.schema.shape/fingerprint :seon.config/agent
 :seon.activation/source-digest :seon.error.occurrence/blob-digest
 :seon.ns/name :seon.ai.attempt/id :seon.maintenance.receipt/id :seon.lint/id
 :seon.ai.model/id :seon.schema.shape.child/id :seon.schedule.task/id
 :seon.source/digest :seon.cluster.instruction/id :db/ident
 :seon.dev.mcp.artifact/id :seon.issue/id :seon.db.process/id :example/order
 :my.plan/agent :seon.turn/id :seon.maintenance.request/id :my.plan.item/id
 :seon.test/sym :seon.maintenance.result/id :seon.schema.shape.entry/id
 :seon.activation.lookup/id :seon.error/signature]
```

The evidence falsifies the initial attribution to the walk for these fourteen
entries: they come from `src/seon/render/value.clj:205`,
`reference-identity`, while structurally rendering `:my.plan.item/subject`.

The introducing behavioral change is **563034709**, 2026-09-15 17:55:32 -0600,
“Keep AI result maps structural and remove duplicate elision prose.”
It disables entity-pair selection for AI maps in `value-node*`, exposing the
generic reference path that existed since 2b93363495/51a98c973f.

A controlled live comparison installed only the parent commit's
`value-node*` form (read with `git show 563034709^:src/seon/render/value.clj`)
in its owning JVM namespace, ran the same preview, and restored the entering
Var root in `finally`. Same database, same schemas, same dependency checkout:

| Definition | Evidence entries | Offending entries | generated-read-fault |
|---|---:|---:|---|
| current structural AI value-node* | 29 | 14 | generated-read-depends-on-turns |
| parent value-node* | 15 | 0 | nil |
| current value-node* plus identity repair | 29 | 0 | nil |

This is a function-isolated introducing-change comparison, not a claim that a
whole older checkout was booted. The current function was restored immediately.

History checked:
- walk: `c98535249` introduced root-selector; today's `b80f78a7c` carries
  projection, and `0edd57230` changes bounds.
- check: `0dca8534e` introduced generated-read-fault; `0c70a1cb4` restricts
  agent reads admitted to generation.
- DB: `d5e5b870e`, `f68b79e01`, `b80f78a7c`, `28e955327` concern
  projection carriage. The identical-database comparison does not change them.
- Datahike gitlink: `db67d8ab1` (August 12); checkout `cdcb5792`;
  both repository submodule status and its internal working-tree status clean.
  No September introducing gitlink change.
- schema history: `6a491f0b3` issue entities, `45998fdbf` error identity,
  `3402913f3` file spans/lint, `2320dc1a9` occurrences and
  `2066b8c20` occurrence writer. None changes between the paired live probes.

## Dependency ledger

| Existing mechanism | Evidence read | First-party seam |
|---|---|---|
| Datahike explicit pull dependencies | reference-code/datahike/src/datahike/pull_api.cljc:107–160 | seon.db/pull-index-patterns at src/seon/db.clj:518 |
| Schema-declared context-inert attributes | src/seon/turn.clj:2019 original Datalog query | extracted next to wake-attributes in src/seon/cluster/wake.clj:121 |
| Root selector compilation and cache key | Datahike compile-pull-plan at pull_api.cljc:80; root-pull-plan selector cache key | src/seon/render/walk.clj:118, :344 |
| Canonical Juniper installer | test/seon/context_blocks_fixture.clj:259 | existing running-fixture-settles-its-seeded-wake in test/seon/loop_proof_test.clj:63 |

The selector itself participates in the walk pull-plan cache key, so changing
its leaf changes the acquired compiled plan. No second cache was added.

## REPL-first sequence and regression boundary

Before source edits, evaluated the complete candidate forms for:
`wake/inert-attributes`, `render.value/reference-identity`,
`walk/root-selector`, and `turn/generated-read-fault` in their JVM namespaces.
Called them against the running database: 68 inert attributes, Juniper resolves
to `[:seon.agent/id "juniper"]`, zero inert walk-leaf attributes, and the
29-entry plan preview has nil generated-read-fault.

Extended the existing canonical running-fixture regression: a nonempty stored
opening must include the plan read; every explicit dependency attribute set
must be disjoint from the declared inert attributes. No second fixture or
test JVM was introduced. Evaluated that candidate deftest in the live JVM
before editing its source.

Exact in-process command for every run:

```clojure
(seon.test/run
 #'seon.loop-proof-test/running-fixture-settles-its-seeded-wake
 (seon.operator/connection "default"))
```

| UTC | Phase | Recorded run entity | Pass/fail/error |
|---|---|---:|---|
| 01:51:38 | unmodified regression, candidate not installed | 45249 | 0/0/1 |
| 01:53:01 | production candidates installed; test edit had reader refusal | 45258 | 0/0/1 |
| 01:53:24 | production and regression candidate forms evaluated | 45265 | 0/0/1 |

Every run reports “Test execution failed: Program indexing transaction was
refused.” A direct canonical-fixture acquisition probe exposes
`:seon.fn/index-phase :seon.fn/population`,
`:seon.db/transaction-outcome-unknown true`, and
`java.lang.InterruptedException`. This matches the pre-existing failed-delay
issue above, whose file is another lane's untracked work and was preserved.
No regression assertion ran. The absence of assertion failures is not green.

Hook lint accepted the changes; it reported existing shadowed-var/unused-binding
and docstring warnings. `git diff --check` passed. No `bin/test`,
`bin/test-fast`, or separate Seon test JVM was launched.

## Live proof and exact bytes

```clojure
(load-file "docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj")
(juniper-fixture-2026-09-06/install! "default")
;; => {:seon.turn/id "aa071259cfd8"}
(juniper-fixture-2026-09-06/prompt "default")
```

Install completed in 10,102 ms. Prompt returned 9,778 characters; the successful
MCP return reports 253 ms execution (an earlier 20-second transport attempt
timed out). The first 40 lines, exactly 8,290 characters, are preserved in
[the opening artifact](generated-read-identities-2026-09-16-opening.txt).
Stored evaluation census: 10 evaluations, 32 read-evidence entries, zero
explicit dependency sets intersecting the inert declaration set.

The returned MCP envelope elided the whole opening string under its token
budget; the local artifact preserves the exact bytes. This is the existing
[whole-value elision issue](../../../seon/issues/a-value-larger-than-the-budget-is-elided-to-nothing.md),
not evidence that the prompt was empty.

## Per-member verdict and residuals

- Generated plan read refusal: fixed in 474234fb7; live install and opening
  prove the observed behavior with hot-reloaded Vars.
- Generic walk identity leaves: same schema-derived exclusion; direct live
  selector probe returns no inert identities.
- Coarse input-bound selector revisions: separate existing issue, unchanged.
  This repair does not pretend the `:all` revision cleanup is complete.
- Failed canonical fixture delay: remains with its owning lane; gate request
  names the existing regression and the exact blocker.
- MCP runtime_status: timed out while JVM evaluation worked. Existing
  runtime-status issue receives this dated observation without attributing the
  timeout to its earlier ClassCastException.
- No named August class/member roster was supplied for this concrete incident;
  no unrelated class note is closed.

## Publication and gate request

Hook request: `4319a29d-080d-497c-808a-10425a8f9741`.
At the live proof, the new function was not yet in the cluster's program facts
and `:seon.source/commit-id` was absent. This proves hot-reloaded Vars, not
completed development adoption. The explicit publication attempt refused
because source changed during analysis (digest
`ff4829e95a5e861eac7656a6548fe1d8f46f06960dda934b6b891d00652c34d2` →
`7ca347695a37f474aeeb72e4c29d125f3989f8b0560dcd66f503b49d00895f0a`).
A retry is recorded in the final addendum.

The orchestrator owns the serial isolated gate, followed by the platform tier:
`seon.loop-proof-test`, `seon.render.walk-test`,
`seon.render.value-test`, and `seon.cluster.wake-test`, path-limited to the
five implementation/test files. Gate request:
`tmp/orchestrator/gate-requests/generated-read-identities.txt`.

Only the generated-read-fault hunk changed in turn.clj; the diff was checked
before editing and again before the path-limited commit. Default was never
stopped, reforked or restarted. No other lane's session was operated.

## Post-edit verification addendum

At 02:01:26Z, loaded the checked-in regression source and reran the exact
two-argument `seon.test/run` above. Run entity 55134 recorded 0/0/1 with a
different boundary: the test did not finish within its declared 20,000 ms
allowance (MCP execution 21,553 ms). A Datahike log reported
`seon.turn/refused`, `agent-already-running`. This is not attributed to the
identity change and is not green; the prior failed-delay attribution applies
only to the three earlier runs.

The cluster subsequently contained the new `wake/inert-attributes` program
row, while its adopted source commit still read absent. Program-row presence
alone does not prove completed adoption. The explicit retry waited at least
244,233 ms for lifecycle owner PID 14204, then began complete publication
(1,948 source inputs). Final outcome follows below.

The incident is recorded as
[resolved with the live proof](../../../seon/issues/archive/generated-reference-identities-depend-on-turn-taking.md);
the canonical gate and adoption boundaries remain explicit here.

Final publication outcome: exit 1 after namespace reload, SCI acquisition,
and JVM instrumentation. Refusal was
`:seon.cluster/source-changed-during-adoption`, published commit
`6aa9f987-5457-5d2f-8e10-5fa66bbdcd3a`. Both explicit publication shells
exited; no lane-owned background process remains. This lane did not restart
or refork default to bypass concurrent source churn.

After that reload/instrumentation, reran the exact single-test command at
02:06:54Z. Run entity 55944 again recorded 0/0/1 at its 20,000 ms bound
(21,436 ms MCP execution), with the same agent-already-running log. The
[separate regression execution issue](../../../seon/issues/running-fixture-regression-exceeds-in-process-bound.md)
records this residual without assigning an unverified cause. The isolated
gate request must resolve it; no successful canonical regression is claimed.

Documentation hook validation also reported twelve pre-existing dependency-pin
citation findings in `docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.
The owned probe script's initial missing-require and namespace/file-name
findings were corrected. No production lint error remained in the owned slice.

## Batch 19 triage — 2026-09-16

Independent reds triage of the batch-19 named-namespace gate
(`tmp/orchestrator/gate-results/batch-19/generated-read-identities.md`, run at
02:32Z, gate base BEFORE `3f4f0cdf2`). Read that report, AGENTS.md, and the
[error-graph note](error-graph-2026-09-16.md) end to end. No test JVM was
launched: every verdict is `(seon.test/run #'<var> (seon.operator/connection
"default") {…})` in default's own JVM, one test at a time, the complete
returned value read, with the test namespace reloaded through `seon.test`'s own
loader first. Default was never stopped, restarted or reforked. Counts are
pass/fail/error assertions.

### Verdict table

| Namespace | Test | In-process at HEAD | Attribution | Fix |
|---|---|---|---|---|
| seon.loop-proof-test | virtual-loop-end-to-end | **229/0/0 green** | Not this lane. The two gate blocks both showed an elided `:seon.eval/shown` comparison; the gate base predates `3f4f0cdf2` (error-graph), whose reader conversions and steward derivation land the behaviour the assertions expect | none needed — green at HEAD |
| seon.cluster.wake-test | a-fault-wakes-the-steward-of-the-failing-functions-namespace | **6/0/0 green** (twice) | Pre-existing at the gate base; resolved by `3f4f0cdf2`, which replaced the stored-steward assertions with the derived `error/fn → fn/ns → ns/steward` reader | none needed — green at HEAD |
| seon.cluster.wake-test | a-fault-with-no-stewarded-function-asserts-no-steward | **does not exist at HEAD** | Deleted by `3f4f0cdf2` ("redundant stored-steward coverage was replaced by the real writer/wake test"); `git log -S` confirms the name exists only before that commit | none needed — the test is gone |

No red in this file is attributable to `474234fb7`, `28c2f7021` or
`bbf71fc2e`, and nothing in this lane's files was changed by the triage. The
whole batch-19 selection for this lane is green at HEAD.

### Verification hazard found and repaired

The first in-process attempt at `virtual-loop-end-to-end` exceeded
`mcp__seon__eval_clj`'s 30 s bound. The interrupt landed inside
`cluster/populate-source!` and was cached by `seon.test-support/database-base`,
a `delay` — so every subsequent in-process test in default's JVM, in every
lane, reported that one stale `InterruptedException` instead of its own
result. A second instance of the same cache appeared on the replacement JVM
because default's classpath carries no `:test` alias dependencies, so
`create-base`'s SCI acquisition cannot load `seon.dev.dependency-cache-test`
(`clojure.tools.build.api`) or `seon.flow-test`
(`clojure.core.async.flow-monitor` → `muuntaja.core`).

Recovery: preload the `:test` classpath (`clojure -A:test -Spath`) into the
loader, then rebuild the base delay inside the cluster's projection.
225 of 226 test namespaces load from default's own classpath. Every verdict
above was measured after that repair. Filed as
[in-process test runs poison the shared fixture base](../../../seon/issues/in-process-test-runs-poison-the-shared-fixture-base.md);
all test runs in this session were afterwards issued on their own daemon
thread so no MCP bound can interrupt a fixture again.

Separately, default pid 7595 died mid-session of a dev panic — "Agent \"root\"
with no observable open turn did not publish turn completion within 600000 ms"
— and was restarted by its owner as pid 53378. This lane neither stopped nor
started it.
