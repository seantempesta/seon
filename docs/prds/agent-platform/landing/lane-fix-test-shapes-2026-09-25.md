---
type: landing
status: complete
lane: fix-test-shapes-opus
created: 2026-09-25
authority: docs/research/agent-platform/red-baseline-census-2026-09-25c.md (classes 2, 4, 6, 7)
---

# Lane fix-test-shapes: census c UPDATE-TEST classes 2, 4, 6 and 7

## Broken or not fixed first

- **Not fixed, needs a src change (the owner is wrong):**
  `seon.error-write-timing-test/retention-validates-expanded-writes-and-retained-target-identities`.
  The worker's refused test retraction must come back as a returned refusal. Instead it is thrown.
  - Evidence: recorded failure blob `1aa08310…`. The first worker write is refused by `seon.db/retention-check`
    (db.clj:4342) with an ex-info that carries `:seon.error/message` but no `:seon.error/at`.
  - Cause: `transact-call`'s catch (db.clj:4480–4509) returns only Seon refusals that carry `:seon.error/at`, so this one
    becomes `:seon.db/transaction-outcome-unknown`. The `:panic` policy then throws it.
  - Fix belongs in src/seon/db.clj: the retention refusal should be a `seon.error.refusal/diagnostic`.
  - Proposed issue: `docs/seon/issues/issue-test-retention-refusal-escapes-as-outcome-unknown.md`. It is not written:
    the path is outside this lane's ownership row.
- **Coverage gap, test deleted:** `seon.cluster.mcp-test/runtime-observation-shows-unavailable-problems-with-their-cause`.
  - Its only premise was `with-redefs boot/readiness` over a keyword connection, which is retired.
  - No fixture producer of a `seon.problems/problems` refusal exists without redefining readiness.
  - The `unavailable` branch of `mcp-runtime-observation` (cluster.clj:674–676) now has no regression. It needs a real
    refusal producer, for example a world whose problems read refuses.
- **Not verified by execution.** The LOAD RULE applies: no test requests ran. Every change was linted with clj-kondo
  (0 errors). Each changed shape was probed read-only at default's JVM REPL (MCP `eval_clj`, JVM mode, scratch namespace
  `fix-test-shapes-scratch`). Root runs are below.
- **RESET NEEDED:** none.

## Out-of-scope findings

1. **Dead code: the cleanup result projection has no producer.** `seon.maintenance/project-cluster-cleanup-result`
   (maintenance.clj:112) and the `seon.operator.cluster-cleanup` schemas have none: `seon.operator/cleanup-cluster!`
   is gone since 076827cc9. maintenance_test still tests that projection, in `cluster-cleanup-projection-…` and
   `a-successful-cleanup-persists-…`. It is a deletion candidate.
2. **A Var redefinition over a default Var.** `seon.problems-test/absent-facts-produce-no-entries` still runs
   `with-redefs` over `problems/html-report` and its own `with-db` to count calls. That violates the law. I left it alone
   because the class 6 fix did not need it.
3. **Ugly output: `declaration-row`'s refusal points at the wrong member.** When the output contract refuses (for
   example, a test row without `:seon.test/ns`), the message names `[:seon.ns/name]`, the last branch of the
   `:seon.program/declaration-row` `:or`. It does not name the member that is actually missing.

## Per-member table

| member (class) | change or delete | commit |
|---|---|---|
| context_blocks_fixture:25, 6 members (7) | order-row entity schema declares `:seon.program/partition :seon.data` | 1d8e5da9f |
| owner nit: `namespace-seed-call` (2) | refuses by name when `program/declaration-row` answers nil; +7 net src lines | 1d8e5da9f |
| maintenance-schema-test/receipt-request… (2) | agent id `maintenance-schema-test-root`, with no `/` | 1d8e5da9f |
| test-failure-facts ×16 (2) | rows come from `program/declaration-row`, `test-support/program-row` and `namespace-row`; analysis edits become `exact-replacement-tx` redeclarations; recorded-run-names gets its test row | 65893d82f |
| my.agent-test settings ×2 (4, 2) | drops `:my.agent/turns-left` (retired 0dca8534e); agent retraction deleted | 65893d82f |
| web-debug ×4 digest (2) | `declare-schemas!`: `projection-with-schema` plus `canonical-schema-rows`; declared-concerns gets its partition | 9a4120835 |
| web-debug the-outlines… (4) | `effective-ai-settings` 3-ary, via `request-cluster-name` | 9a4120835 |
| web-debug reverse-declarations… (4) | normalize gets its projection and `:seon.render/request-error` | 9a4120835 |
| schema-usage-guard ×7 (2) | `schema-row` goes through `declaration-row`; data hangs off one installed holder identity | 0f36ffa54 |
| fn-test incremental-tempid-rewrite (2) | raw Datahike attrs stay; schema rows are canonical | bf0a556c3 |
| fn-test analyzer-edges (4) | reads the flat `:seon.db/attribute` | bf0a556c3 |
| turn-test attempt-model-identity (4) | target and settings come from `provider-targets` | 324b0accb |
| turn-test a-terminal-refusal… (4) | refusal comes from `turn/refuse!` itself | 324b0accb |
| turn-test a-wake-meeting… (4) | work names `:seon.turn.work/situation :open` | 324b0accb |
| turn-test fault-unit-lists… (4) | declares `:seon.error/normalization-error` | 324b0accb |
| turn-test transitions-agree… (2) | agents via `test-support/agents-tx` | 324b0accb |
| turn-loop a-refused-phase-escalates… (4) | source comes from `seon.turn/phase` (phase-failed-error meta) | 66bb07059 |
| page-review turn-headers… (2) | required `:seon.turn/agent` retraction deleted | 6c06c2f18 |
| mcp runtime-observation-counts… (4) | **deleted**; its count assertions joined the fixture-backed bounds test | 27aac3899 |
| mcp runtime-observation-shows-unavailable… (4) | **deleted** (see Broken) | 27aac3899 |
| maintenance last-collection… (4) | cleanup-cluster! half deleted (deleted handler); root agent via `agent-tx`; unused `seed-report!` and `at-2` deleted | 849d6e224 |
| problems-test configured-models… (2) | agent-b via `agent-tx`; model overrides via `agent/settings!` | 25f1692ff |
| problems-test healthy, absent-facts, block (6) | `found` removes the member world's unstewarded namespaces; block compares the healthy render with its world's | 25f1692ff |
| agent-namespace source-bearing… (6) | judges only the fixture's `example.*` names | 35a9096c6 |
| agent-situation (6) | `seed-cluster!` "situation" with dial 2; expects 2 | 35a9096c6 |
| run6-stall (6, 4) | `seed-cluster!` "run6-stall"; normalize projection plus `:seon.ai/unparseable-body-error`; `retractAttribute` for completed-tx | 35a9096c6 |
| my.plan-test a-second-agent… (6) | `seed-cluster!` "plan-map-defaults" | 28ee0a865 |
| overlay-world (6, root ruling) | sentinel schema expectation retired; asserts that the branch's operational sentinel datum is absent | ca755bdad |
| error-write-timing retention (2) | **unchanged**, src finding | — |

## Evidence

- Recorded failures came from `(@#'seon.problems/failed-tests db)` on default. Blob bodies were read with
  `seon.blob/get` for analyzer-edges, retention and transitions.
- Probes, all read-only on default's JVM:
  - `declaration-row` of a qualified namespace name answers nil. An unqualified one derives a row.
  - `test-support/program-row` over a synthetic namespace source derives `:seon.fn/calls`, `:seon.fn/spec` and a digest
    in 40 ms.
  - `projection-with-schema` plus `canonical-schema-rows` for web-debug's forms takes 9 ms. The identity-bearing
    declared-concerns refuses without a partition.
  - `error/normalize` accepts the web-debug, run6 and fault-unit requests.
  - `seon.turn/phase` output validates `:seon.turn.loop/phase-failed-error`.
  - `provider-targets` answers the primary `deepseek-flash`.
- Lines in the owned paths: tests +243 / −268; src/seon/cluster/agent.clj +10 / −3. Net −18. Every commit adds
  ≤ 100 lines.
- Timings: none measured beyond the probes above. No test executed.

## Root runs (serially)

```
bin/test-check default --ns seon.test-failure-facts-test
bin/test-check default --ns seon.render.web-debug-test
bin/test-check default --ns seon.schema-usage-guard-test
bin/test-check default --test seon.fn-test/incremental-tempid-rewrite-follows-the-installed-attribute-type
bin/test-check default --test seon.fn-test/analyzer-edges-are-readable-symbols-or-no-edge
bin/test-check default --ns seon.turn-test
bin/test-check default --test seon.turn-loop-test/a-refused-phase-escalates-once-per-signature-and-never-to-itself
bin/test-check default --ns seon.render.page-review-test
bin/test-check default --test seon.cluster.mcp-test/runtime-observation-bounds-open-faults-and-counts-them-whole
bin/test-check default --ns seon.maintenance-test
bin/test-check default --ns seon.maintenance-schema-test
bin/test-check default --ns my.agent-test
bin/test-check default --ns seon.problems-test
bin/test-check default --ns seon.cluster.agent-namespace-test
bin/test-check default --ns seon.agent-situation-test
bin/test-check default --ns seon.run6-stall-test
bin/test-check default --test my.plan-test/a-second-agent-renders-through-the-same-defaults
bin/test-check default --ns seon.test.overlay-world-test
bin/test-check default --changed seon.cluster.agent/namespace-seed-call
```

The context_blocks fixture is proven through the web-debug `turn-details…` member and the other five Juniper members.
Released paths: every path in the fix-test-shapes-opus row.
