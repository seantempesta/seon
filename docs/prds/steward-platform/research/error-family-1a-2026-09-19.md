---
type: research
status: open
created: 2026-09-19
tags: [error, schema, implementation, design-gate]
---

# Error family 1a — 2026-09-19

## Result and exact boundary

**C1’s claim that facets cannot ride the occurrence row is refuted.** The real
recorder stores a composed agent/turn observation and an actual armed arity
refusal, including its owned arity-bound component. A standalone identity-less
observation is correctly refused as `:seon.db/unowned-entity`. No observation
identity or per-facet root is needed to repair C1.

**Continuation under D12.** The accepted calibration is commit `431093b97`
(79 tests / 502 assertions / 0 failures / 0 errors). D12 in the namespace-agent
plan §8 supersedes the acquisition gate below: consumers branch on their
specific declared error members; output contracts validate completeness. No
new general predicate, global registry or per-call projection acquisition is
part of the continuation. The old three options are retained only as dated
evidence of the question the owner resolved.

## Authorities read

Read end to end, in the requested order: AGENTS §§2–3;
[the modeling guide](../../../seon/architecture/data-modeling-guide.md);
[the error PRD](../plan/error-entities-prd-2026-09-17.md);
[audit B](schema-audit-b-2026-09-19.md);
[the declaration manifest](error-declaration-manifest-2026-09-18.md);
[wrapper enforcement](error-wrapper-enforcement-2026-09-18.md);
[one predicate](one-error-predicate-2026-09-18.md);
[instrumentation accuracy](instrumentation-coverage-and-error-accuracy-2026-09-19.md);
and [the namespace-agent plan](../plan/namespace-agents-plan-2026-09-19.md),
including every assigned section and Turn 2/3 row. Then read the assigned error,
instrumentation and failure resources, both implementation files, both test
files, the database final-report validator and the specified schema regression.
The data-oriented-clojure, data-modeling, datahike, repl and clojure-testing
skills were applied. The active roadmap entry and latest working-edge sections
were also consulted.

## Dependency ledger and evidence

- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:1206`
  runs the final-report validator after the transaction and throws its actual
  refusal. Component retraction is at `:831`; no parallel writer is needed.
- Malli `reference-code/malli/src/malli/core.cljc:2203` validates inputs before
  calling the body, and outputs afterward; `:3119` owns instrumentation.
- `src/seon/db.clj:3506` expands actual ownership through EAVT/AVET and validates
  complete components; its unowned-root refusal is around `:3607`. Read only.
- `src/seon/error.clj:1520` derives the allowed diagnostic attributes from the
  supplied projection; `:1560` copies complete base/facet fields onto the same
  occurrence; `:1581` puts that row under the signature’s existing component
  relation. `:1617` prepares the real transaction.
- `resources/seon/schemas/seon.error.edn:61` declares
  `:seon.error/occurrences` as a component targeting
  `:seon.error.occurrence/occurrence`. The occurrence already has its real
  identity; `resources/seon/schemas/seon.error.occurrence.edn:2`.
- `src/seon/schema.clj:3038` derives a pull-result form for the actual selector.
  `:2967` derives its entries; cardinality-many pull values are vectors, not
  the stored `:set` declaration. This is why the final regression validates
  the read with the derived form, rather than widening a stored entity schema.
- Archaeology: `09869c8f1` already preserved refusal members on occurrences;
  `e42f494ab` added host enforcement; `f3ae2d055` added SCI enforcement;
  `5ad9ea70c` acquired the evidence policy once. The latter explains the
  missing-policy fixture repair below.

## Step 1 — canonical writer proof

The regression is
`test/seon/error_test.clj:error-facets-persist-through-the-real-occurrence-owner`.
It uses `with-database`, seeds the actual cluster, uses `transacted!` for every
write, invokes `seon.id/valid?` through its armed wrapper using `apply`, and
records both examples with `error/recording`. It proves the standalone refusal
leaves the basis unchanged, positive transaction reports contain datoms, each
occurrence belongs to the signature, the observed scalar fields survive, and
the actual arity-bound child equals the authored bound after removing pull’s
`:db/id`. It validates the read through `schema/pulled-form-in`, never through
a hand-written pulled schema.

The first attempt was stopped after static analysis correctly rejected a
literal wrong-arity call in the new test. It was changed to `apply`, matching
the existing arity regression; that attempt is not a fixture proof.

The first completed run also exposed an incorrect assertion in the new probe:
validating a pulled arity vector against the stored set grammar. This was a
probe mistake, not a failed write. The source facet validated; the transaction
landed; the full component was present. The final regression separates those
questions and prints both observations without asserting that the grammars
are interchangeable.

Exact emitted proof records from the corrected HEAD-plus-owned-paths run:

```clojure
#:seon.error-test{:unowned-refusal {:seon.db/invalid-write true, :seon.error/kind :seon.db/invalid-write, :seon.error/message "Complete component validation refused: unowned-entity; bound :seon.config.db/validation-node-limit=250000; #:seon.db{:entity 35771, :entity-value {:seon.agent/error-agent-id \"error-family-observed\", :seon.error/at #inst \"2026-09-19T00:00:00.000-00:00\", :seon.error/layer :seon.agent/lifecycle, :seon.error/operation seon.agent/by-id, :seon.turn/error-turn-id \"error-family-turn\"}}.", :seon.error/data #:seon.db{:diagnostic-cause :seon.db/unowned-entity, :validation-bound :seon.config.db/validation-node-limit, :validation-limit 250000, :entity 35771, :entity-value {:seon.agent/error-agent-id "error-family-observed", :seon.error/at #inst "2026-09-19T00:00:00.000-00:00", :seon.error/layer :seon.agent/lifecycle, :seon.error/operation seon.agent/by-id, :seon.turn/error-turn-id "error-family-turn"}}, :seon.db/transaction-refused true, :seon.db/tx-data [{:seon.error/at #inst "2026-09-19T00:00:00.000-00:00", :seon.error/layer :seon.agent/lifecycle, :seon.error/operation seon.agent/by-id, :seon.agent/error-agent-id "error-family-observed", :seon.turn/error-turn-id "error-family-turn"}]}}
#:seon.error-test{:stored-occurrence {:seon.error.occurrence/id "514569122bfe", :seon.error.occurrence/count 1, :seon.error.occurrence/message "An unclassified clojure.lang.PersistentArrayMap arrived where an error was expected.", :seon.error/operation seon.agent/by-id, :seon.error.occurrence/process #:db{:id 35772}, :seon.error/capped? false, :seon.error/data-edn "#:seon.print{:face :seon.print/map, :entries [[#:seon.print{:face :seon.print/keyword, :value :seon.error/at} #:seon.print{:face :seon.print/inst, :value #inst \"2026-09-19T00:00:00.000-00:00\"}] [#:seon.print{:face :seon.print/keyword, :value :seon.error/layer} #:seon.print{:face :seon.print/keyword, :value :seon.agent/lifecycle}] [#:seon.print{:face :seon.print/keyword, :value :seon.error/operation} #:seon.print{:face :seon.print/symbol, :value seon.agent/by-id}] [#:seon.print{:face :seon.print/keyword, :value :seon.agent/error-agent-id} #:seon.print{:face :seon.print/string, :value \"error-family-observed\"}] [#:seon.print{:face :seon.print/keyword, :value :seon.turn/error-turn-id} #:seon.print{:face :seon.print/string, :value \"error-family-turn\"}]]}", :seon.error.occurrence/last-at #inst "2026-09-19T00:00:00.000-00:00", :seon.error/layer :seon.agent/lifecycle, :seon.error.occurrence/first-at #inst "2026-09-19T00:00:00.000-00:00", :db/id 35773, :seon.turn/error-turn-id "error-family-turn", :seon.error/data-size 759, :seon.error/at #inst "2026-09-19T00:00:00.000-00:00", :seon.agent/error-agent-id "error-family-observed", :seon.error/process "test-cluster-4242-1753650000000"}, :required-facets #{:seon.agent/error :seon.turn/error}, :authored-facets #{:seon.agent/error :seon.turn/error}, :pulled-facets #{:seon.agent/error :seon.turn/error}}
#:seon.error-test{:stored-occurrence {:seon.error.occurrence/id "ff80bdbc4970", :seon.error.occurrence/count 1, :seon.error.occurrence/message "seon.id/valid? refused argument count at []: expected the declared arglists, got an argument count of 0. Fix: Call one of the declared arglists.", :seon.error/operation seon.id/valid?, :seon.error.occurrence/process #:db{:id 35772}, :seon.error/capped? true, :seon.instrument/actual-size 48, :seon.error/data-edn "#:seon.print{:face :seon.print/map, :entries [[#:seon.print{:face :seon.print/keyword, :value :seon.error/kind} #:seon.print{:face :seon.print/keyword, :value :seon.instrument/contract-violated}] [#:seon.print{:face :seon.print/keyword, :value :seon.error/at} #:seon.print{:face :seon.print/inst, :value #inst \"2026-09-19T17:54:09.703-00:00\"}] [#:seon.print{:face :seon.print/keyword, :value :seon.error/layer} #:seon.print{:face :seon.print/keyword, :value :seon.instrument/invocation}] [#:seon.print{:face :seon.print/keyword, :value :seon.error/operation} #:seon.print{:face :seon.print/symbol, :value seon.id/valid?}] [#:seon.print{:face :seon.print/keyword, :value :seon.error/message} #:seon.print{:face :seon.print/string, :value \"seon.id/valid? refused argument count at []: expected the declared arglists, got an argument count of 0. Fix: Call one of the declared arglists.\"}] [#:seon.print{:face :seon.print/keyword, :value :seon.sci.admit/remainder} #:seon.print{:face :seon.print/map, :entries [[#:seon.print{:face :seon.print/keyword, :value :seon.sci.admit/reason} #:seon.print{:face :seon.print/keyword, :value :over-bound}] [#:seon.print{:face :seon.print/keyword, :value :seon.sci.admit/bytes} #:seon.print{:face :seon.print/number, :value 3266}]]}]]}", :seon.instrument/fn seon.id/valid?, :seon.instrument/declared-arity-count 1, :seon.instrument/expected "([length id])", :seon.error/message "seon.id/valid? refused argument count at []: expected the declared arglists, got an argument count of 0. Fix: Call one of the declared arglists.", :seon.error.occurrence/last-at #inst "2026-09-19T17:54:09.703-00:00", :seon.error/layer :seon.instrument/invocation, :seon.instrument/declared-arities [{:db/id 35776, :seon.instrument.arity/max 2, :seon.instrument.arity/min 2, :seon.instrument.arity/ordinal 0}], :seon.instrument/args "0", :seon.error.occurrence/first-at #inst "2026-09-19T17:54:09.703-00:00", :db/id 35775, :seon.error/data-size 6155, :seon.error/at #inst "2026-09-19T17:54:09.703-00:00", :seon.instrument/arity 0, :seon.instrument/arm :input, :seon.instrument/actual "0", :seon.error/process "test-cluster-4242-1753650000000"}, :required-facets #{:seon.instrument/arity-error}, :authored-facets #{:seon.instrument/arity-error}, :pulled-facets #{}}
```

## Step 2 — persistence decision after falsifying C1

Use the existing signature → `:seon.error/occurrences` → occurrence relation.
The observation’s base and facets inhabit that occurrence’s row. Its component
attributes own their existing children. Do not add a nested error child,
`observation-id`, a kind, or identities on facets.

This follows guide §2.5: the error is an observed value whose actual persistence
owner is the occurrence; domain identity tokens remain observations and cannot
upsert the agent/turn/function being described. Guide §5’s component rules
apply to the actual children and final owning value. A domain facet need not
be the literal component target to be stored on that owner’s open row. The
recorder supplies the occurrence’s required persistence fields; C1 incorrectly
required the input facet itself to supply them.

The table is derived from the packaged base-extension declarations at this
checkpoint. It specifies the relation **when recorded**, not a claim that all
63 producers have been converted or that each was separately transacted.
Three facets were measured above; the common implementation derives all
facet attributes from the same projection. Transient evaluation results still
follow the existing shown-text policy until their owning seam records an
observation.

| Facet | Declaring resource | Persistence relation |
|---|---|---|
| `:my.background/error` | `resources/seon/schemas/my.background.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:my.edit/error` | `resources/seon/schemas/my.edit.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:my.fs/error` | `resources/seon/schemas/my.fs.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:my.message/error` | `resources/seon/schemas/my.message.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:my.plan/error` | `resources/seon/schemas/my.plan.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:my.shell/error` | `resources/seon/schemas/my.shell.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:my.turn/error` | `resources/seon/schemas/my.turn.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.agent.graph/error` | `resources/seon/schemas/seon.agent.graph.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.agent/error` | `resources/seon/schemas/seon.agent.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.ai/request-error` | `resources/seon/schemas/seon.ai.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.artifact/error` | `resources/seon/schemas/seon.artifact.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.boot/error` | `resources/seon/schemas/seon.boot.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.bootstrap/error` | `resources/seon/schemas/seon.bootstrap.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.cluster.prompt/error` | `resources/seon/schemas/seon.cluster.prompt.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.cluster.registry/error` | `resources/seon/schemas/seon.cluster.registry.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.cluster.reply/error` | `resources/seon/schemas/seon.cluster.reply.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.cluster.source/error` | `resources/seon/schemas/seon.cluster.source.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.cluster.store/error` | `resources/seon/schemas/seon.cluster.store.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.cluster.wake/error` | `resources/seon/schemas/seon.cluster.wake.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.cluster/error` | `resources/seon/schemas/seon.cluster.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.config/error` | `resources/seon/schemas/seon.config.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.config/rule-error` | `resources/seon/schemas/seon.config.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.db.availability/error` | `resources/seon/schemas/seon.db.availability.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.db.read/error` | `resources/seon/schemas/seon.db.read.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.db.write/error` | `resources/seon/schemas/seon.db.write.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.dev.mcp/error` | `resources/seon/schemas/seon.dev.mcp.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.effect/error` | `resources/seon/schemas/seon.effect.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.env/error` | `resources/seon/schemas/seon.env.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.eval.drive/error` | `resources/seon/schemas/seon.eval.drive.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.flow/error` | `resources/seon/schemas/seon.flow.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.fn.binding/error` | `resources/seon/schemas/seon.fn.binding.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.fn/error` | `resources/seon/schemas/seon.fn.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.instrument/arity-error` | `resources/seon/schemas/seon.instrument.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.instrument/contract-error` | `resources/seon/schemas/seon.instrument.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.instrument/registration-error` | `resources/seon/schemas/seon.instrument.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.instrument/undeclared-error` | `resources/seon/schemas/seon.instrument.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.message/error` | `resources/seon/schemas/seon.message.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.operator.collect/error` | `resources/seon/schemas/seon.operator.collect.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.operator/error` | `resources/seon/schemas/seon.operator.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.problems/error` | `resources/seon/schemas/seon.problems.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.program/error` | `resources/seon/schemas/seon.program.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.reconcile/error` | `resources/seon/schemas/seon.reconcile.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.render.data/error` | `resources/seon/schemas/seon.render.data.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.render.value/error` | `resources/seon/schemas/seon.render.value.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.render.walk/error` | `resources/seon/schemas/seon.render.walk.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.render.web/error` | `resources/seon/schemas/seon.render.web.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.render/error` | `resources/seon/schemas/seon.render.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.schedule/error` | `resources/seon/schemas/seon.schedule.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.schema.datahike/error` | `resources/seon/schemas/seon.schema.datahike.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.schema.shape/error` | `resources/seon/schemas/seon.schema.shape.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.schema/error` | `resources/seon/schemas/seon.schema.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.sci.admit/error` | `resources/seon/schemas/seon.sci.admit.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.sci.eval/acquisition-error` | `resources/seon/schemas/seon.sci.eval.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.sci.eval/evaluation-error` | `resources/seon/schemas/seon.sci.eval.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.sci.kernel/error` | `resources/seon/schemas/seon.sci.kernel.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.sci.reader/error` | `resources/seon/schemas/seon.sci.reader.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.search/error` | `resources/seon/schemas/seon.search.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.test.accretion/error` | `resources/seon/schemas/seon.test.accretion.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.test.run/error` | `resources/seon/schemas/seon.test.run.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.test.runner/error` | `resources/seon/schemas/seon.test.runner.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.test/error` | `resources/seon/schemas/seon.test.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.turn.loop/error` | `resources/seon/schemas/seon.turn.loop.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |
| `:seon.turn/error` | `resources/seon/schemas/seon.turn.edn` | Same occurrence row under `:seon.error/occurrences`; existing declared component attributes own children. |

`:seon.error.disposition/observation` is not a base-extending facet and has no
production constructor in this source. Do not mint a root for an event that
nothing observes. Its eventual observing seam must supply the owning relation;
this checkpoint does not claim that orphan declaration is integrated.

## Superseded design gate — D12 removes the general predicate

The storage question is settled. The next change crosses a different boundary:
`error?` currently takes only a value, while the required authority is an
**acquired projection**. A read-only MCP probe in the owner’s JVM found no
handed projection. With the default connection explicitly supplied, the
installed base validator accepted the three base fields, while `error?`
returned false. There are still nine private predicate implementations in the
checked-out source; the earlier one-predicate landing’s “eight replaced” claim
is not current implementation evidence.

Measured JVM results (PID 41822, no mutation):

```clojure
{:projection? false :base-valid? nil :error? false :facet-count nil}
{:base-valid? true :error? false :facet-count 63}
```

In addition, the stored arity refusal’s `declared-arities` is a vector of
component maps after pull. `error/facets` validates complete authored values
and reports `#{}` for that pulled representation; the selector-derived pull
form validates it. A base with cardinality-many evidence has the same grammar
question. This is not a reason to change Datahike’s pull vocabulary or widen
individual stored attributes.

A drop-in rewrite cannot guarantee projection-correct recognition at every
pre-store and unscoped caller. Fetching a database or compiling a new packaged
population while handling each failure violates PRD §4’s acquisition rule;
a global schema-name cache violates its projection ownership; a message or
three-key fallback creates a second error taxonomy. The persistence proof does
not authorize any of those shortcuts.

Exactly three concrete options, simplest first:

1. **Pass the projection explicitly (recommended).** Make the one predicate
   accept projection + complete observation value. Each caller receives the
   projection it already owns; pre-store callers receive the packaged
   projection from boot. The existing error read owner acquires complete
   observation values from declared components before facet recognition;
   generic pull remains selector-shaped. **Guarantee:** one schema authority,
   no fetch or global fallback during failure handling, and one complete-value
   grammar at error boundaries. **Cost:** estimated 2–4 hours across the nine
   predicate owners, boot/SCI call sites and error readers; coordinated source
   and regression changes, including currently held database paths. **Give up:**
   drop-in one-argument host calls and treating arbitrary partial pull maps as
   complete observations.
2. **Carry an acquired predicate in the environment.** Compile the base
   validator once with the projection and supply the resulting callable as
   ordinary environment/proc/wrapper input; lower owners call that value.
   Complete error acquisition remains at the error reader. **Guarantee:**
   one-argument validation against the supplying environment’s exact schema,
   including pre-store construction, without dynamic lookup. **Cost:** estimated
   4–6 hours of environment contracts, boot/graph acquisition and caller changes
   plus the reader work; the public helper still needs an explicit acquisition
   argument. **Give up:** a universally callable process-global predicate with
   only the error map as input.
3. **Carry projection provenance with each observation value.** Keep validators
   out of datoms; constructors and readers attach the acquired projection in
   process-local metadata, and the one predicate validates through it. Plain
   external maps require an explicit acquisition call before recognition.
   **Guarantee:** the value retains the world its observations came from; no
   database lookup or stored discriminator. **Cost:** estimated 6–10 hours across
   constructors, readers, copying/serialization boundaries and metadata-loss
   tests. **Give up:** unannotated-map interchangeability and simple reconstruction
   after metadata-stripping operations.

These are estimates for coordination, not measured implementation durations.
All retain the already-ruled same-row occurrence persistence. No option proposes
an identity per facet or an exclusive classification. The owner’s choice is
needed under the assignment’s design-stop rule before production changes.

## Accuracy and held-file follow-up

- B1 is positively reproduced, as above. The real recorder also supplies an
  “unclassified” message for a valid message-free base observation. That is
  legacy normalization behavior, not evidence that its stored facet is invalid.
  The nine private copies and
  cause-chain readers are listed in the retirement inventory.
- D1–D4 remain in the assigned implementation seam. No claim of repair:
  the caller frame is still only diagnostic data; reporter fallback still
  labels a reporter failure as a contract violation; graph arglists still
  precede loaded Var metadata; invalid `reject!` values still lack the shared
  recognizable failure contract. Kindless propagation also crosses
  `src/seon/error/refusal.clj` and `src/seon/sci/kernel.clj`, outside this scope.
- D5 / ruling 1q: exact output facets are still owed. Do not mistake the
  existing explicit generic-carrier enumeration for the missing producer
  contracts or published arity analysis facts.
- D6: `src/seon/test/runner.clj` is held; retaining gate ex-data is listed,
  not edited. The SCI parity issue remains an acceptance item, not claimed
  fixed by this storage probe.
- Audit C9 belongs to the program/arity declaration writer outside this
  ownership. C13’s listened-entity and context-contribution decisions also
  remain outside these files. They are not repaired by error identities.
- The historical audit’s “live writer fabricates 64 zeroes” points at an
  error **test generator**, not a production constructor. Do not turn that
  source reference into a production attribution.

The initial host-wrapper regression’s fourteen failures had this actual
first refusal: `seon.error/project-observation` received
`:seon.config.eval.result/max-bytes nil`. The private `arm-var!` call omitted
`:seon.config.error/max-evidence-bytes`, which the current implementation
expects in its acquired policy. The test now supplies that value from
`test-support/effective-config`, as its production acquisition does. No
production fallback was added. That fixture defect is independent of C1.

## Inventory and verification

[Kind retirement inventory](error-kind-retirement-inventory-2026-09-19.md):
2,357 matching lines, 2,393 literal/destructuring matches, 323 files across
source, tests, resources, scripts, launchers and config. The separate EDN
census finds 338 marked schema declarations in 65 resources; audit B’s slice
contains 52 marked schemas. Its 35 raw `[:= true]` hits include the inline
`seon.db.diff/removed?` constraint, which is not an error-class marker; there
are 34 literal true-valued top-level declarations. The file lists every matched line, its required
conversion, ownership, and each actual marker/payload declaration. It is a
point-in-time shared-tree census; no foreign listed file was edited. The
conversion itself has not been performed.

| Step / iteration | Exact result |
|---|---|
| 1, initial | Stopped after static analysis rejected the probe’s literal wrong-arity call; no complete tally. |
| 1, first completed | 79 tests, 491 assertions, 15 failures, 0 errors. One wrong-grammar probe assertion; fourteen missing acquired-policy fixture assertions. |
| 1, corrected | 79 tests, 502 assertions, 0 failures, 0 errors; exit 0. |
| 2 | Persistence relation established by step 1; no production schema mutation required to make those facets storable. Full base/occurrence contract replacement remains pending. |
| 3–6 | Not run or implemented; stopped at the acquisition design gate. |

Fast command for the final bytes:

```bash
bin/test-fast --paths test/seon/error_test.clj test/seon/instrument_test.clj -- seon.error-test seon.instrument-test
```

Cold proof owed to the orchestrator:

```bash
bin/test --paths test/seon/error_test.clj test/seon/instrument_test.clj -- seon.error-test seon.instrument-test
bin/test --platform
```

Fast evidence is not isolated cold/platform evidence. No new fork,
hot-reloaded Var, development adoption or browser paint is claimed; no
production behavior changed. MCP runtime status and the two read-only JVM
forms answered. No default lifecycle or adoption command ran.

## RESET NEEDED

**None from this checkpoint: exact changed-attribute list `[]`.** No schema
was edited. The planned kind/class deletion and future type changes remain
in the wave-1 reset batch; do not claim them landed from this note.

## Files touched and cleanup

- `test/seon/error_test.clj`: canonical ownership/storage regression.
- `test/seon/instrument_test.clj`: hand the current acquired evidence policy
  to its real private arming call.
- `docs/prds/steward-platform/research/error-kind-retirement-inventory-2026-09-19.md`.
- `docs/prds/steward-platform/research/error-family-1a-2026-09-19.md`.

All lane-launched test processes exited. The fast launcher removed all three
owned snapshot roots; no scratch worktree or cluster was created. Exact proof
records and tallies are retained above; lane-only temporary logs and census
files were removed after recording the evidence. Unrelated working-tree edits
were preserved.


## Step 2 continuation — complete observation reader

The existing persistence relation is unchanged. `observation-selector` derives
explicit unlimited component selectors from canonical base/facet declarations;
peer refs remain refs. `latest-fact` retains all acquired occurrence members.
AI and HTML readers acquire this complete value before rendering and no longer
collapse its owned evidence back to entity IDs.

Canonical regression: the real recorder stores a composed agent/turn observation
with **1,001 location segments**, and an armed arity refusal with its owned
arity bounds. The derived pulled schema uses the actual selector; wildcard
pull's implicit 1,000-member grammar is not substituted for it.

Fast evidence in tests / assertions / failures / errors:

- Before reader change: **79 / 510 / 6 / 0** (both namespaces).
- First reader iteration: **79 / 525 / 2 / 0**; both failures were the new
  regression validating the unlimited read through a wildcard-derived form.
  Corrected the regression to derive from its actual selector.
- Interrupted combined continuation: **no final tally**; TERM terminated it.
- Corrected reader checkpoint: **42 / 250 / 0 / 0**, `seon.error-test`.

The main-tree overlay refused dirty caller paths `src/seon/test.clj`,
`src/seon/test/runner.clj`, and `test/seon/test/selection_test.clj`. Continued
from detached `733d0f422` in `tmp/error-family-1a-wt`, linked `reference-code`,
and copied only owned edits. One narrower reader invocation additionally
refused the dirty owned `test/seon/instrument_test.clj`; adding that overlay
path allowed the reader namespace to run. Neither pre-JVM refusal has a test
tally. No foreign file or session was changed.

Touched in this checkpoint: `src/seon/error.clj`, `test/seon/error_test.clj`,
and this landing note. **RESET NEEDED attributes: `[]`** for this checkpoint.
The owning relation and installed attribute types did not change.


## Step 3 continuation — instrument boundary

Deleted `flat-error-value?` and `buried-error`. Contract reports no longer
short-circuit on an error-shaped argument. The actual armed regression passes
an observation to an integer boundary and verifies its input refusal retains
the observation as offending evidence. Program-graph arglist lookup checks
its declared successful results (database value, string, or absent), and gives
an explicit failed lookup for any other result; no error predicate or
projection acquisition replaces the deleted predicate.

The public `seon.error/error?` and the other eight private copies still need
the external caller/contract cut described in the inventory. This checkpoint
is the owned instrumentation portion, not a claim that all nine are retired.

Before-change instrumentation run: **41 / 292 / 15 / 0**. The armed argument
regression passes; the 15 failures are the new step-4 marker (9), step-5 caller
and arglist (4), and step-6 registration/output completeness (2) assertions.
The combined after-change run is pending. Touched here:
`src/seon/instrument.clj`, `test/seon/instrument_test.clj`, this note.
**RESET NEEDED attributes: `[]`** for this checkpoint.
