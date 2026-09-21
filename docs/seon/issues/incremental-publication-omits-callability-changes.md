---
type: issue
status: open
severity: blocker
created: 2026-09-21
tags: [issue, publication, program-graph]
---

# Incremental publication omits callability changes

The inherited, uncommitted publication slice selects caller lint only when a
function spec or schema form changes (`src/seon/fn.clj:2278`). An arity or privacy
change with unchanged/absent spec therefore skips surviving caller analysis.
This is a source-proven selection gap, not an observed cold-boot failure.

The same slice narrows namespace reload to macros/protocol methods
(`src/seon/cluster.clj:2017`). Clojure also embeds constants and inline expansions
(`reference-code/clojure/src/jvm/clojure/lang/Compiler.java:7914`, `:7507`).
No currently failing cross-namespace consumer has been established; that remains
an unverified incremental guarantee, independently of successful reset/indexing.

B1 owns complete dependency-sensitive publication in
[the integrated plan](../../prds/agent-platform/plan/lane-b1-one-publication-path.md).
Before treating the inherited optimization as safe, select every analyzer-visible
callability change and prove unchanged-spec arity/privacy refusals. Preserve the
existing compile-time dependency guarantees or prove their replacement, including
protocol methods, constants and inline expansion. Updated docstring/contract and
macro-selection tests alone do not cover those cases.

The conservative baseline repair now selects direct callers from every identity
in the declaration transaction report, preserving the report-based pipeline and
schema-consumer expansion. Development adoption again reloads every transitive
declared namespace dependent, retaining selection from both prior and current
database values. Narrower selection remains B1 work.

`seon.fn.publication-cache-test/lint-direct-callers-after-committed-declaration-changes`
now exercises real clj-kondo caller refusals after absent-spec arity and privacy
changes, with canonical database fixtures. The namespace regression
`seon.cluster.publication-delta-test/changed-declarations-reload-their-namespace-and-dependents`
requires transitive dependents for ordinary declarations as well as macros.

Verification is pending the orchestrator's tests and adoption. The pre-edit
read-only probe of live `default` (PID 12119, database basis 536870935) completed
in 5 ms and returned only `["seon.id"]` for an ordinary `seon.id/id` identity;
that establishes the inherited loaded behavior, not proof of this repair.
This issue stays open until the repaired path is verified.

The combined checkpoint `fresh-start-combined-gate-repaired.log` exposed two
fixture errors before the arity/privacy cases: the unchanged-manifest request
omitted its previous database (changing schema declaration-digest inputs), and
the changed contract used unsupported Malli `:number`. The fixture now carries
the previous database consistently and uses the supported `number?` predicate.
Assertions and bounds remain unchanged; armed verification is pending.
