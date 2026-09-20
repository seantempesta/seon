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
part of the continuation. The initial projection-acquisition gate is resolved; the current gate below
concerns recurrence identity after kind retirement.

**Latest continuation: partial, not integration-green.** Commits `06c4fe7fe`,
`340a878b2`, `6891f4f5d`, `a7f013251`, and `796a76314` land the reader,
instrument predicate removal, owned class stamps, accuracy fixes and wrapper
contracts. Final fast tally: **86 tests / 579 assertions / 1 failure / 1 error**.
Both remaining results identify read-only consumer boundaries, detailed below.
The recurrence-identity design gate below has exactly three options. Public predicate/kind retirement and the remaining error-owner
contracts are explicitly unfinished. The cold gate and wave reset are owed
to the orchestrator.

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

The initial predicate/projection gate in accepted commit `431093b97` is
resolved by D12. Its options are historical evidence in that commit, not
current alternatives. Consumers use their declared error members and the
wrapper validates complete declared output facets. The reader acquires
complete components before rendering. No general predicate is the target.

## Initial checkpoint — accuracy and held-file follow-up

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

## Initial checkpoint — inventory and verification

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
| 3–6, initial checkpoint | Initially stopped at the now-resolved acquisition gate; continuation evidence follows below. |

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

## Initial checkpoint — RESET NEEDED

**None from this checkpoint: exact changed-attribute list `[]`.** No schema
was edited. The planned kind/class deletion and future type changes remain
in the wave-1 reset batch; do not claim them landed from this note.

## Initial checkpoint — files touched and cleanup

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

## Step 4 continuation — owned class declarations

Removed the three owned legacy class schemas and four scalar stamps:
`:seon.error/unclassified-error`,
`:seon.instrument/contract-violated-error`,
`:seon.instrument/registration-failed-error`,
`:seon.error/unclassified`, `:seon.error/refusal`,
`:seon.instrument/contract-violated`, and
`:seon.instrument/registration-failed`. Instrumentation no longer emits its
two stamps. The old representative asserting its instrument class stamp was
removed; real armed output is covered by the facet regression.

The before-change marker regression records **9 failing assertions**, within
**41 / 292 / 15 / 0** above. Combined after-change verification is pending.
The shared `:seon.error/class` declaration, kind declaration and public
predicate await the external declaration/caller cut. This is not full kind
retirement. The inventory's D12 actions replace every old recommendation to
call the general predicate.

Touched here: `resources/seon/schemas/seon.error.edn`,
`resources/seon/schemas/seon.instrument.edn`, `src/seon/instrument.clj`,
`test/seon/error_test.clj`, `test/seon/instrument_test.clj`, the inventory,
and this note.

### RESET NEEDED

Retired installed attributes in this checkpoint:
`[:seon.error/unclassified :seon.error/refusal
  :seon.instrument/contract-violated :seon.instrument/registration-failed]`.
The three removed class schemas are declarations, not additional physical
attribute names. No reset, adoption, restart or refork was performed.

## Step 5 continuation — diagnostic accuracy

D1: the first caller outside instrumentation is part of both the flat refusal
sentence and its rendered sentence. Namespace exclusion is exact for this
owner, so `seon.instrument-test` is not excluded with it. The armed regression
checks the actual caller namespace and both rendered surfaces.

D2: deleted `minimal-violation` and the reporter's catch. A humanizer failure
now propagates its original Throwable and ex-data. It cannot acquire a
contract-violation label from a fallback. The regression uses the real compiled
wrapper and a declared failing humanizer; before the deletion, both original
message and original evidence assertions failed.

D3: loaded Var metadata supplies host arglists. Only an established JVM miss
consults the program graph for an interpreted function. The fixture transacts
an intentionally stale program declaration through `program-fn-row` and
`transacted!`; the armed call must report the loaded `[value]` binding, not
stored `[stale-name]`.

D4: the canonical-projection probe deliberately makes `refusal-result`
incompatible, then passes the actual thrown invalid-refusal to the real SCI
kernel boundary. **The current kind-bearing boundary retains the complete
contract-error and its check, expected-shape, explanations and function.**
The historical D4 loss is not reproduced here. No guessed repair is made.
Kindless propagation still requires the inventoried external
`seon.error.refusal` and `seon.sci.kernel` conversions.

D5 is the database-consumer output-contract gap, not the gate reporter. D6 is
the gate reporter's ex-data loss; `src/seon/test/runner.clj` remains outside
ownership. The SCI parity note's final addendum locates its remaining defect
in SCI function acquisition; changing reporter prose does not repair it.

Fast evidence: the initial step-5 assertions contributed **4 failures** to
**41 / 292 / 15 / 0**. The later combined run was
**86 / 582 / 3 / 1**: two D2 assertions, one external SCI stamp-consumer
assertion, and one bootstrap facet-discovery error. The final combined
rerun is in flight at this commit; no isolated step-5 green tally is claimed.

Touched: `src/seon/error.clj`, `src/seon/instrument.clj`,
`test/seon/instrument_test.clj`, this note. **RESET NEEDED attributes: `[]`**
for this step.

## Step 6 continuation — output guarantees at the wrapper

Every named function in `src/seon/instrument.clj` now declares a Malli
contract: source-form census **34 functions / 0 missing**. `apply!` returns
`applied` or the exact `registration-error` facet. Its refusal constructor and
`wrap-interpreted`'s acquisition failures supply observation time, responsible
layer, actual operation, observed function and owned registration evidence.
The former missing-recorder stamp is not restored.

A facet declaration no longer accidentally grants permission to return its
inherited bare base. The canonical armed regression proves that a complete
declared agent facet passes and an incomplete base is refused even alongside
a broad successful map arm. Existing host and SCI regressions prove that an
undeclared facet is refused through the wrapper. Facet validators derive once
from the supplied declarations, including boot projections that have no
program shape catalog. They remain projection-owned; no general predicate or
global/fetched registry was introduced.

Loaded-Var and callable contracts are named nonpersistent schema declarations.
Their canonical forms remain symbols; loaded metadata containing callable
objects uses the genuine polymorphic schema-value grammar rather than the
EDN-only definition grammar. This avoids turning compiler-internal values
into fabricated stored forms.

This is **not completion of 1q**. The source-form census of `error.clj` is
**102 functions / 46 missing contracts**. The legacy normalization/recording
cut remains, including generic outputs in `diagnostic` and `value`, and the
actual D5 consumers `agent-exists?`, `entity-exists?`, `steward`, `recurrence`,
`commit-call` and the fault readers. A `some?` around a refused database read
still cannot truthfully establish entity existence. Those sites must propagate
the precise declared read refusal, not guess success from its non-nil map.
The existing explicitly enumerated unions on truly polymorphic pass-through
helpers are not evidence that these producers have been converted.

Intermediate verification failures were resolved in the owned seam as follows:

- An arming attempt failed before tests because new internal contracts used
  the EDN definition grammar for loaded callable-bearing schema metadata.
  No test tally; changed those positions to `seon.schema/value`.
- An attempted inline quoted Var predicate failed source indexing. That run
  was terminated after repeated fixture-construction failures; no final tally.
  Named `loaded-var` and `callable` declarations preserve canonical source.
- **86 / 582 / 3 / 1** then exposed two D2 assertions, one external SCI
  marker consumer and bootstrap facet discovery's absent shape catalog.
  D2's catch and the shape-catalog dependency were removed.

The final combined fast run and its exact tally are recorded below. The two
measured external boundaries are listed in the inventory: SCI acquisition
still reads `registration-failed` at `src/seon/sci/eval.clj:882,1867`, and
`src/seon/schema.clj:1069` has no error union on its callback pass-through.
The latter produces the correct typed `undeclared-error` refusal rather than
silently accepting the newly complete registration error.

Touched in this checkpoint: `src/seon/error.clj`, `src/seon/instrument.clj`,
`resources/seon/schemas/seon.instrument.edn`, `test/seon/instrument_test.clj`,
the inventory and this note. **Additional RESET NEEDED attributes: `[]`**;
the new callable schemas declare values, not installed attributes.

## Owner design gate — kindless recurrence identity

D12 is accepted; this question does not reopen predicate recognition.
`signature` currently hashes kind, Throwable class, observed function and
frame (`src/seon/error.clj:313`). `prepare` obtains the function from legacy
diagnostic data or a stack, not the base's operation. The existing canonical
regressions verify that messages do not change this identity, different kinds
do, and repeated observations share the writer-owned count. `commit-call`
uses that count to suppress repeated notifications. Simply deleting kind
from the digest changes which failures share a root and which notifications
are suppressed. The target documents do not specify that replacement
coalescing guarantee.

Exactly three kindless choices, simplest first:

1. **Group by observation site (recommended):** hash base layer and operation,
   plus available Throwable class and frame, using the existing `seon.id/id`.
   **Guarantee:** repeated errors at the same observed site share a root;
   adding facets or changing message/data does not change recurrence identity.
   **Cost:** approximately 2–4 hours for the normalizer, root declarations,
   recurrence regressions and the already inventoried consumer publication.
   **Give up:** distinct error facets at one site can share the count and
   notification suppression. This is an explicit behavioral tradeoff.
2. **Group by site and satisfied facet set:** add the sorted declared facet
   keys derived from the supplied canonical projection to that digest input.
   **Guarantee:** different complete facet combinations at one site have
   separate recurrence counts; no kind or class stamp is stored.
   **Cost:** approximately 3–5 hours including complete-value acquisition
   and tests for composed/inherited facets across recorders/readers.
   **Give up:** recurrence identity is no longer stable when a new facet is
   added to otherwise identical evidence or its declaration changes.
3. **Give each observation a fresh root identity:** retain complete independent
   events, then derive recurrence and notification suppression from a declared
   query over their evidence.
   **Guarantee:** recording never combines different observations because a
   signature was too coarse.
   **Cost:** approximately 6–10 hours across recording, recurrence queries,
   `seon.problems`, cluster status, transcript and their tests.
   **Give up:** existing signature-root aggregation and constant-size repeat
   updates; the grouping query becomes an explicit separate policy decision.

Costs are coordination estimates, not measured runtimes. All three retain
structural base/facets and remove the general predicate/kind in the completed
cut. No production recurrence-identity change was made pending this ruling.
The public predicate's 75 external calls and six external required kind
members also require the orchestrator's already-planned mechanical follow-up
before a loadable deletion; their exact files and actions are inventoried.

## Final fast evidence, reset batch and handoff

The final HEAD-plus-owned-paths fast run completed on the isolated
`733d0f422` worktree at **2026-09-19T19:37:58Z**:

```text
Ran 86 tests containing 579 assertions.
1 failures, 1 errors.
```

This is the combined after-change tally for steps 3–6, not a separate green
claim for each intermediate commit. The step-2 reader-only run remains
**42 / 250 / 0 / 0**. The accepted step-1 tally remains **79 / 502 / 0 / 0**.
The final run passed D1, D2, D3, the current D4 evidence-retention probe,
complete declared facet acceptance, undeclared facet refusal, incomplete-base
refusal, complete 1,001-member observation reading, owned stamp removal and
all 34 instrument-function contract assertions.

The two remaining results, without changing their expectations:

```text
FAIL in (a-sovereign-sci-fork-acquires-its-own-recorder) (instrument_test.clj:449)
Construction cannot publish a context containing an unarmed definition.
expected: ((schema/projection-validator projection :seon.instrument/registration-error) missing-base)
actual: false

ERROR in (applying-without-a-handed-projection-refuses-before-collection) (instrument.clj:757)
actual: clojure.lang.ExceptionInfo: seon.schema/call-with-projection-state returned undeclared error facets #{:seon.instrument/registration-error}.
```

These are measured consequences of the owned changes meeting unconverted
consumers, not unrelated failures blamed on another lane. The exact repair
sites and required-member/union conversions are in the inventory. No wrapper
was disabled and no callback result was hidden to obtain a green tally.

Hot-wrapper measurements (three batches of 20,000 calls; microseconds per
call) are iteration measurements, not production latency guarantees:

| Surface/value | Existing validation | Validation with facet enforcement |
|---|---|---|
| Host scalar | 0.215–0.248 | 0.264–0.286 |
| Host ordinary map | 0.223–0.240 | 0.267–0.293 |
| Host declared error | 0.226–0.233 | 18.369–18.933 |
| SCI scalar | 0.306–0.445 | 0.342–0.403 |
| SCI ordinary map | 0.356–0.367 | 0.376–0.430 |
| SCI declared error | 0.325–0.355 | 16.028–18.193 |

All files touched across the accepted calibration and continuation:

- `resources/seon/schemas/seon.error.edn`
- `resources/seon/schemas/seon.instrument.edn`
- `src/seon/error.clj`
- `src/seon/instrument.clj`
- `test/seon/error_test.clj`
- `test/seon/instrument_test.clj`
- `docs/prds/steward-platform/research/error-kind-retirement-inventory-2026-09-19.md`
- `docs/prds/steward-platform/research/error-family-1a-2026-09-19.md`

**RESET NEEDED — exact retired installed attributes in the landed slice:**

```clojure
[:seon.error/unclassified
 :seon.error/refusal
 :seon.instrument/contract-violated
 :seon.instrument/registration-failed]
```

No attribute type was changed. Removing `:seon.error/kind` and the shared class
property remains pending, not falsely included in this landed list.
`seon.failure.edn` needed no change: it already describes the fault relation
and requested/stopped transaction observations, without a kind/class stamp.

[Inventory](error-kind-retirement-inventory-2026-09-19.md) retains all original
per-site actions and the current continuation counts/dependencies:
original **2,357 lines / 2,393 matches / 323 files**, current raw search
**2,348 / 2,384 / 322**. Outside source/resources were inspected, never edited.

Cold gate owed to the orchestrator after the listed consumer repairs and
completed retirement cut (the schema namespace is read-only for this lane):

```bash
bin/test --paths resources/seon/schemas/seon.error.edn resources/seon/schemas/seon.instrument.edn src/seon/error.clj src/seon/instrument.clj test/seon/error_test.clj test/seon/instrument_test.clj -- seon.error-test seon.instrument-test seon.schema-test
bin/test --platform
```

The lane ran neither command. No default reset, refork, restart, adoption or
lifecycle command was run. Runtime publication/reset proof remains owed.

Remaining undeclared named functions in `error.clj`, from parsed source forms
at `796a76314` (a dated completion checklist, not a runtime registry):

```clojure
[throwable kind root-cause message top-frame signature known-or-unknown
 meaningful-source utf8-size evidence-caps bounded-admission
 classifying-error-data bounded-error-admission bounded-text
 stack-failing-function contract-violation-data offending-entry admitted-size
 fit-fact-payload fact-source flat-data evidence-prose value-description
 schema-expectation collection-member-problem refusal-value-text
 reader-correction refusal-data refusal-text notice-ai-prose fact-tempid
 agent-exists? entity-exists? recurrence message-tx class-properties
 matched-error-classes error-marker error-evidence evidence-text default-ai-prose
 evidence-path fault-order run-identity fault-entities faults-input]
```

Cleanup: the final lane launcher and JVM (8622/8905) exited. All six overlaid
source/resource/test files were byte-identical to the committed main-tree
files before cleanup. The owned worktree was removed after unlinking its
reference-code and cache links; no symlink target was removed. Lane-only
scratch copies, logs and thread dumps were removed after recording the exact
results above. Foreign dirty files and all other worktrees were preserved.

## D13 continuation — recurrence identity (2026-09-19)

D13 in plan §8 supersedes the preceding recurrence design gate. Signature now
hashes the complete observation's layer, operation, sorted satisfied facet
keys, Throwable class/top frame, violated expected key/shape and location
path through `seon.id/id`. Message, offending bytes, time and process do not
enter it. Acquisition restores declared collection/ref representations before
facet validation; it keeps owned component evidence. Normalization now requires
its projection explicitly; recording acquires it from its supplied database.

The canonical armed before-change regression completed **87 tests / 586
assertions / 4 failures / 1 error**. Three failures reproduce D13 (facet,
expected-schema and path variants collapsed and changed the old occurrence).
The other failure/error are the already recorded SCI/schema boundaries. Main
`--paths` admission exited 64 before the JVM because dirty callers
`src/seon/test.clj` and `src/seon/test/runner.clj` were omitted. The prescribed
HEAD worktree `tmp/error-family-d13-wt` at `23f1f4975` excludes those edits;
its after-change run is in flight at this checkpoint.

Occurrences retain their existing process/turn custody; recurrence and the
first-notification decision query the complete root at the writer. There is
one notification identity per signature/recipient, with one chosen recipient
on the first occurrence. The future task writer consumes this same root; this
lane does not introduce a second task mechanism. The recurrence-limit input
is no longer required to record or deduplicate faults.

Files in this step: `src/seon/error.clj`,
`resources/seon/schemas/seon.error.edn`, `test/seon/error_test.clj`, this note.
No attribute type changes in this step. The external direct normalizer callers
must supply their already acquired projection (included in the follow-up
inventory). Cold and reset-boundary proof remain the orchestrator's.

## D12 continuation — owned retirement

Deleted the public `seon.error/error?`, class-discovery helpers, and all owned
kind writes/reads/declarations. Diagnostics now require and return the complete
base observation. Normalized facts and roots state layer and operation;
rendering reads operation and concrete diagnostic evidence. Instrumentation
constructs complete base/registration observations before composition.
No replacement general predicate or acquisition-at-predicate-time was added.
The supplied-entry reporter throws a complete registration observation when
its external owner returns an error without an exact declared error contract.

Tests assert the declared base, arity, contract or registration shapes instead
of stamps; obsolete class-recognition tests are removed. Static verification:
all four Clojure files parse; clj-kondo reports **0 errors**. The prior D13 run
was stopped after four identical fixture-construction errors (no final tally);
a diagnostic retry on the same canonical fixture is running to identify the
exact duplicate-program-identity refusal. This is not a green recurrence claim.

The inventory now lists **75 external public-predicate calls / 74 lines /
5 files**, all six external required kind members, and the external diagnostic
and normalization input changes. Current raw kind/class census:
**2,240 lines / 2,266 matches / 317 files**. No external file was edited.
Files in this step: both owned Clojure namespaces and test namespaces,
`resources/seon/schemas/seon.error.edn`, the inventory and this landing note.

**RESET NEEDED — additional retired installed attributes:**
`[:seon.error/kind :seon.error/class]`. The previous four retired attributes
remain in the wave-1 reset batch. No attribute type changes. Removal of the
external required members and external class properties belongs to the
orchestrator's mechanical sweep; this cut deliberately retains no shim.

## Exact error-owner outputs

The original 46 missing declarations are accounted for: eight old functions
(`kind`, `class-properties`, `matched-error-classes`, `error-marker`,
`error-evidence`, `evidence-text`, `default-ai-prose`, `run-identity`) were
deleted; `signature` acquired its contract in D13; the remaining **37** functions now declare
inputs and outputs. A new `evidence-text` formatter has its own complete
contract and performs no classification. Parsed source census: **95/95 error-owner functions and
34/34 instrumentation functions contracted**, zero missing declarations.
Polymorphic observation pass-throughs explicitly enumerate their permitted
base/facet union; database readers use the database owner's declared error
union. No owned function contract uses generic `:seon.error/value`.

Read failures are no longer truthy presence, a zero recurrence count, an absent
example, or successful recording: the corresponding exact output union
propagates them. A missing requested observation produces a complete base
refusal naming `seon.error/recording` and `:seon.error/error`. Canonical fixture
regressions cover that refusal and the named-function declaration census;
the previously landed armed host/SCI tests still cover complete declared
facets passing and undeclared facets being refused.

The complete owned cut's fast attempt exited **1 before namespace execution**:

```text
Syntax error compiling at (seon/fn.clj:1387:36).
No such var: error/error?
```

There is no test/assertion tally for this pre-load boundary. The caller is an
explicitly inventoried external sweep site; no shim or external edit was used.
All four owned Clojure files parse and clj-kondo reports **0 errors**.
The extra canonical refusal regression was added after that load refusal; it
is owed with the final integration gate, not claimed as executed.

The earlier fixture duplicate was local and is fixed: two redundant `declare`
findings at `src/seon/error.clj:1611:1` produced the identical lint identity
`29330d0581c3` (`seon.fn/lint-rows` hashes `[owner type row col]`). The exact
refusal carried `:seon.fn/identity [:seon.lint/id "29330d0581c3"]`. Removing the
redundant declaration let the corrected snapshot build the canonical fixture.
That run later exited **143**, without a final tally or cause attribution.
A fresh serial run of D13's runnable slice is pending below.

Files in this contract step: `src/seon/error.clj`, `test/seon/error_test.clj`,
this landing note. No additional RESET NEEDED attributes in this step.

Acquisition refinement: a root/value reference is acquired before recording;
a complete occurrence value (carrying its occurrence identity) is already the
observation and is not fetched over. The D13 regression also adds its new facet
to this acquired observation, so the old signature carried for navigation
cannot erase the added evidence. The writer contract now requires the fact and
occurrence identity its caller supplies. These final assertions remain owed
behind the external predicate-load boundary.

### RESET NEEDED — consolidated wave-1 batch

Retired installed attributes:

```clojure
[:seon.error/unclassified :seon.error/refusal
 :seon.instrument/contract-violated :seon.instrument/registration-failed
 :seon.error/kind :seon.error/class]
```

Existing root attributes now required by the error-root schema:
`[:seon.error/layer :seon.error/operation]`. No value-type changes were made.
The owner resets once after the external consumer/schema sweep; this lane
never resets, reforks, restarts or adopts default.

The same external compile dependency also prevents schema-hook admission:
[the recorded editing-surface symptom](../../../seon/issues/schema-edit-admission-cannot-load-after-error-predicate-retirement.md).
That issue is owned externally and was read, not edited. Its requested
verification belongs after the mechanical sweep, alongside the cold gate.

## D13 final verification and handoff

The serial armed run of the runnable D13 slice (`9c9b50e3c`, with the duplicate
declaration removed) completed at 20:39:31 UTC: **87 tests / 586 assertions /
17 failures / 1 error**, exit 1. The recurrence regression completed all eight
assertions without a failure. This run predates public-predicate retirement
and the final acquired-observation refinement; it is not a green claim for
the final cut.

The exact red distribution is:

- One owned assertion expected six transcript notifications; D13 produces one
  notification while retaining six occurrences. The expectation is corrected
  to one. The transcript reader counts delivered notifications at
  `src/seon/render/transcript.clj:2014`.
- Fifteen failures follow the refused `error/prepare` request at
  `src/seon/cluster.clj:3058`, which omits `:seon.schema/projection`: six in
  `sci-installed-contracts-enforce-facets-and-refusals-in-both-dials`, eight in
  `host-boundaries-enforce-per-arity-facets-in-both-modes`, and one in
  `a-sovereign-sci-fork-acquires-its-own-recorder`. The exact external repair is
  recorded in the inventory; the lane did not change that caller.
- One failure in the sovereign SCI test is the previously recorded unarmed
  fallback instead of a registration refusal.
- One error in `applying-without-a-handed-projection-refuses-before-collection`
  is the existing `seon.schema/call-with-projection-state` output contract:
  `returned undeclared error facets #{:seon.instrument/registration-error}`.

Final static verification: **95/95 error-owner and 34/34 instrument functions
declare contracts**, zero missing; clj-kondo on the four owned Clojure files
reports **0 errors / 81 warnings / 2 info**. `git diff --check` passes. Owned
kind/class/public-predicate sites are zero. The fresh raw inventory remains
**2,240 lines / 2,266 matches / 317 files**; the predicate sweep comprises
**75 production calls on 74 lines in five files**, plus **10 test calls in
four files**, with exact locations and replacement actions in the inventory.

The final refinements preserve concrete edit/MCP/index evidence in their
specialist renderers and update the fixture examples to their facet members.
The final acquired-observation and missing-observation assertions remain
subject to the complete cut's load boundary and the orchestrator's cold gate.
Files in this final slice: `src/seon/error.clj`, `test/seon/error_test.clj`,
this landing note, and the kind-retirement inventory. No additional reset
attributes; the consolidated list above is complete for this lane.

The final six-path snapshot was rerun after those refinements. It again exited
**1 before namespace execution**, with exactly
`Syntax error compiling at (seon/fn.clj:1387:36). No such var: error/error?`.
There is no test/assertion tally for this final cut. The public predicate is
retired without a shim as ruled; the inventoried external caller sweep must
land before these final regressions and the cold command above can execute.
No held file was changed.

The documentation edit hook reported stale dependency-gitlink citations in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`; that
external audit was not changed. This does not substitute for the successful
owned Clojure lint or the explicitly refused test load.

Cleanup verified zero Java processes using the owned worktree. Both final
launchers exited; all five reference/cache symlinks were unlinked before
removing `tmp/error-family-d13-wt`, preserving their targets. Lane-only scratch
files were removed after recording the results here. Foreign working edits
and other worktrees were preserved. No cold gate or default lifecycle command
was run. The orchestrator owes the external sweep, batched reset, named cold
gate and platform proof; this bounded lane stops here.

## Green-suite continuation — 2026-09-20

Read the complete kind-schema-references landing, including its final per-test
tally, and the Clojure/data modeling/testing/datahike/REPL skills. The inherited
dirty files were `bin/test`, `src/seon/test.clj`, and A1's landing note; later A1
changes were also preserved. The default advertisement and MCP health answered
at PID 41822. A read-only metadata probe found the live diagnostic still
declares the retired kind input, so that JVM is not evidence for this cut.
No reload, adoption, reset or lifecycle action was performed.

The fresh canonical armed baseline reproduced exactly **118 tests / 3,843
assertions / 46 failures / 23 errors**, exit 1, ending at
2026-09-19T21:38:30Z. It used HEAD `f71a00a9f` with the ten owned source/test/
resource paths selected. This independently verifies the prior report.

The first coherent repairs are:

- `1b732b28e`: instrument assertions pass their exact contract-error or
  arity-error registry key. `valid-candidate-value?` explicitly validates a
  candidate registry key; its API was not widened for a test's inline union.
- `77b06482b`: projection-state callback output explicitly admits the
  registration-error facet it passes through.
- `487d7e4eb`: unavailable cluster measurements are complete base observations.
  The existing measurement schema already admits that union; the producer's
  old kind-only map was wrong, not the integer arm. No store scan was added.
- `bcf240408`: the 13 SCI and eight host failures come from the fixture
  helper at `test/seon/test_support.clj:897`, which replaces marker-free
  returned errors with `:seon.test-support/committed`. Recording and the
  persistence assertions already succeed. Owned tests now inspect the direct
  record-mode return and retain thrown-evidence handling in panic mode. Every
  facet, arity, identity and persistence assertion is preserved.

The raw requested namespace-load command exited zero after each checkpoint.
The second combined fast iteration is pending; these are not green-suite
claims. The cold command remains the orchestrator's and will include every
path touched by this continuation plus `seon.db-test` and `seon.cluster-test`.

Scope clarification requested before external production edits: three schema
diagnostic producers (`render-contract-refusal!`, `refuse-projection-source`,
`pulled-selector-refusal`) omit the mandatory base inputs, but this assignment
permits only schema contract sites; two SCI acquisition catches still test
the retired registration-failed marker in `src/seon/sci/eval.clj`, outside the
owned paths. Neither producer-body set has been edited pending that answer.

The second iteration additionally verifies the writer callback boundary:
`call-with-projection` replaced the complete base diagnostic with
`:seon.instrument/undeclared-error` (declared facets `#{}`). `b9195cb97`
declares the returned base and registration observations explicitly; its
required namespace-load command exited zero and printed `:loads`.

The write observation records the immutable input database's store, branch,
commit and basis transaction, plus the supplied transaction's bounded
projection. This is the request's observed basis, not a claim about a writer
queue position. Datahike owns `store-identity` (`store.cljc:35`) and `commit-id`
(`api/impl.cljc:371`). Configuration follows the cluster's actual config ref,
or the sole config row before cluster construction; zero or ambiguous rows
retain the complete base refusal and explicitly report evidence unavailable.
The component regression now applies config through the canonical helper
before expecting a complete write facet. No default-cluster operation or
invented evidence bound is used.

`dc5dbbfb4` lands the database observation producer and its canonical fixture
regressions. The shared diagnostic constructor supplies `at` from the actual
observation, qualifies the supplied diagnostic layer under `seon.db`, and
preserves the supplied qualified operation. Its output is exactly
`:seon.error/base`; the transaction boundary composes the complete
`:seon.db.write/error` when bounded request evidence is available.
The 21 constructor call sites at this commit are:

| `src/seon/db.clj` producer | Line(s) | Layer / operation |
|---|---:|---|
| `replay-read` | 992 | database-read / `seon.db/replay-read` |
| `read-declarations` | 1201 | database-read / supplied operation |
| `unknown-attribute-error` | 1360 | database-read / supplied operation |
| `lookup-ref-error` | 1391, 1405 | database-read / supplied operation |
| `malformed-query-pattern-error` | 1470 | database-read / `seon.db/q` |
| `query-input-shape-error` | 1838 | database-read / `seon.db/q` |
| `missing-query-error` | 1894 | database-read / `seon.db/q` |
| `missing-pull-selector-error` | 1999 | database-read / supplied public operation |
| `pulled-entity-schema-key` | 2130 | database-read / `seon.db/pull` |
| `validate-pulled-value` | 2196 | database-read / supplied public operation |
| `pull-call` | 2283 | database-read / supplied public operation |
| `diff-refusal` | 2715 | agent-boundary / `seon.db/diff` |
| `invalid-write` | 3271 | database-write / `seon.db/transact!` |
| `write-owned-values-error` | 3543 | database-write / `seon.db/transact!` |
| `write-render-target-error` | 3774 | database-write / `seon.db/transact!` |
| `removed-definition-error` | 3827 | database-write / `seon.db/transact!` |
| `write-agent-retraction-error` | 3868 | database-write / `seon.db/transact!` |
| `write-report-error` | 3947 | database-write / `seon.db/transact!` |
| `transact-call` | 4215 | database-write / `seon.db/transact!` |
| `missing-transaction-data-error` | 4286 | database-write / `seon.db/transact!` |

`a931e68b8` updates schema probes to inspect required observation evidence and
hands the private compiled-wrapper probe real canonical config caps. It does
not widen a production input contract or relax a facet assertion.

The first repair iteration completed **118 tests / 3,853 assertions /
27 failures / 5 errors**, exit 1, at 2026-09-19T21:57:34Z. Its snapshot preceded
`b9195cb97`, the final database observation changes, and the caps fixture fix.
Both former multi-assertion failures are now zero: SCI 13 → 0, host 8 → 0.
The remaining kernel error positively identifies `failure-value` at
`src/seon/sci/kernel.clj:583`: its old kind check reconstructs a complete
instrumentation refusal, and its diagnostic request lacks the base members.
The deadline regression reaches that same producer. Scope for this function
was requested separately; it is outside the owned paths and remains unchanged.

The fourteen reference-grammar assertions now report their actual upstream
refusal: `seon.schema/pulled-selector-refusal` calls the diagnostic without
`at`, `layer`, or `operation`. The returned shapes were not changed by an error
schema resource edit in this continuation; the recursive component derivation
refusal is being obscured before the read can apply its existing fallback.

The load command passed after `dc5dbbfb4`. After `a931e68b8`, the shared tree
failed at `seon/render/walk.clj:766:21`, `No such namespace: error`; the
uncommitted render diff adds that reference without the alias. No render file
was edited. A detached `tmp/error-family-green-wt` at `a931e68b8`, with the
existing reference-code linked, passed the exact load command (exit 0,
`:loads`). The next serial fast run uses that HEAD worktree and the same ten
owned paths. No foreign edit or session is included or operated.

### Design gate discovered during final verification

Read the new error-conversion PRD end to end and the newly landed working-edge
ruling `6ebb5b5de` (the render gate). It says: "a producer carries the offending
value as data; bounding happens only at the recorder's admission". The existing
write facet requires `:seon.db.write.attempt/operations`, a component with
`:seon.error.projection/entity` grammar. Consequently the new helper in
`dc5dbbfb4` bounds the transaction before returning that facet, which conflicts
with this ruling. A green writer regression does not settle that model conflict.
No further production edit was made after identifying it. The helper is
committed and reviewable, but is not claimed ready for acceptance under the new
ruling. Exactly three options, simplest first:

1. **Return the complete base with raw transaction evidence for now.**
   Guarantee: no producer admission; required base and original writer cause
   remain complete, and the recorder receives the actual request. Cost: remove
   the new projection/config acquisition and explicitly revise the writer
   regression's promised output. Given up: a complete domain write facet at
   this boundary until its schema is resolved.
2. **Add a raw validation-refusal facet (recommended).** Guarantee: the returned
   error has required request identity and observed basis, with the actual
   transaction carried as data for recorder admission. Cost: extend ownership
   to the database write/attempt resources and database output union, declare
   the precise facet, and update the fixture assertion and consumers. Given up:
   the existing projection-required `:seon.db.write/error` as the transient
   validation-refusal shape; its stored declaration can remain unchanged.
3. **Separate transient and stored write-error schemas at the recorder.**
   Guarantee: producers retain raw requests and the recorder constructs the
   existing complete stored projection facet. Cost: coordinated recorder,
   reader, wrapper/output-contract and schema changes across owners, with
   additional persistence regressions. Given up: one unchanged error shape
   from the producer through storage; this is the larger cross-owner change.

The owner question names this conflict explicitly. It is separate from the
pending scope requests for schema producer bodies and SCI kernel/acquisition.

The final snapshot also includes the newer test-facet declarations. Its
`semantic-admission-explicitly-declares-every-error-facet` regression reports
three stale pass-through unions: `seon.sci.admit/semantic-value`,
`seon.error/refusal`, and `seon.error/latest-fact`. A separate read-only
declaration probe on the same HEAD, using `facet-keys` and `declared-result`,
exited zero and derived the same nine missing keys for each:

```clojure
(:seon.test/admission-error :seon.test/execution-error :seon.test/expired
 :seon.test/not-runnable-error :seon.test/resolution-error
 :seon.test/selection-error :seon.test/unknown-error
 :seon.test.run/immutable-error :seon.test.run/unavailable-error)
```

These unions remain an explicit continuation item after the design gate;
the assertion is retained. The first two error-owner functions are owned here;
`seon.sci.admit/semantic-value` is outside the assignment. New facets require
the actual pass-through output declarations to accrete them, not exclusion
from structural discovery or a relaxed regression.

### Final measured tally and handoff

The final serial fast run, on HEAD `a931e68b8` with the ten selected owned
paths, exited **1** at 2026-09-19T22:19:00Z:

```text
Ran 118 tests containing 4150 assertions.
27 failures, 4 errors.
```

| Namespace | Tests | Failures | Errors |
|---|---:|---:|---:|
| `seon.error-test` | 42 | 0 | 0 |
| `seon.instrument-test` | 44 | 3 | 3 |
| `seon.schema-test` | 32 | 24 | 1 |

The write facet, original unowned-entity cause, unchanged write basis,
cluster measurement, and both host/SCI multi-assertion regressions pass.
The caps fixture error also passes. The three-suite goal is **not green**.
Remaining work is explicit:

- `src/seon/schema.clj:1724,2598,2789`: the three diagnostic producer bodies
  need the base members; the last two output contracts still need exact
  declarations. They account for 24 failures and one error. Body edits are
  outside the assignment's "contract sites only" restriction.
- `src/seon/sci/kernel.clj:519`: preserve the existing declared refusal in
  `failure-value`, and construct a complete observation for a new guarded
  failure. This accounts for two errors; scope was requested.
- `src/seon/sci/eval.clj:882,1867`: recognize registration observations by
  their required facet members. The current fallback reaches the incomplete
  acquisition diagnostic at `:1580`; one error remains. Scope was requested.
- `src/seon/error.clj:71,1699` and `src/seon/sci/admit.clj:536`: accrete the
  nine measured facets into these pass-through output unions. Three
  assertions remain; the first two sites are owned, the third is not.
- Resolve the three-option database evidence gate above before accepting
  `dc5dbbfb4` as the final implementation.

Files touched by this continuation (seven): `src/seon/db.clj`,
`src/seon/schema.clj`, `src/seon/cluster/status.clj`,
`test/seon/error_test.clj`, `test/seon/instrument_test.clj`,
`test/seon/schema_test.clj`, and this landing note. Every production/test
change is in a path-limited commit. No error resource changed in this
continuation. Foreign A1 and render edits were preserved. The later A1
commit `04d044538` was not an input to the final fast snapshot.

Log measurements before removing lane-only scratch logs:

| Run | Bytes | SHA-256 |
|---|---:|---|
| Baseline | 20,128,500 | `711bb31f780462d430cdd349bcd5d51e1bbbdff5e817c3b310ed8b451d2e5fdc` |
| First repair iteration | 218,720 | `5eb894b7dcf67dbb598f9d3a8c59df813e2d46dc87ec417f923e43a8e047de2a` |
| Final iteration | 20,094,773 | `209546b3e52381e5d943ee247e63ffa412400f42f8bf51eef78a591ff380e3c8` |

Both fast launchers and the final JVM (PID 45128) exited. The five dependency/
cache symlinks were unlinked before removing `tmp/error-family-green-wt`;
their shared targets were preserved. The documentation hook still reports
the already recorded stale gitlink citations in the external AGENTS audit.

### RESET NEEDED

No additional attributes in this continuation. The earlier consolidated
attribute list remains the wave-1 reset obligation. No reset, refork,
restart, adoption, cold gate, or default lifecycle command was run.

After resolving the gate and remaining producers/unions, the orchestrator
owes this cold command and the platform/live proof:

```sh
bin/test --paths \
  src/seon/db.clj src/seon/schema.clj src/seon/cluster/status.clj \
  test/seon/error_test.clj test/seon/instrument_test.clj test/seon/schema_test.clj \
  docs/prds/steward-platform/research/error-family-1a-2026-09-19.md \
  -- seon.error-test seon.instrument-test seon.schema-test seon.db-test seon.cluster-test
```

Add any newly authorized producer/resource files to that path list when those
repairs land. This lane stops at the documented design gate, without claiming
that a passing error namespace proves the unresolved combined contract.

## Raw write-refusal continuation — 2026-09-19

The orchestrator accepted the previous landing through `0a5b7355b` and
ruled the producer/recorder split: the producer carries request identity,
the immutable observed basis entity, and actual transaction data; `prepare`
admits that data into the existing stored write attempt. The stored
`:seon.db.write/error` shape remains unchanged. This continuation resumes
that work; the preceding stop statement describes the previous turn.

The revised canonical writer/recorder regression was run before the
implementation with `bin/test-fast --paths test/seon/error_test.clj --
seon.error-test`. At 2026-09-19T22:41:23Z it measured **42 tests / 349
assertions / 4 failures / 1 error**. Every failure/error is in
`complete-error-children-validate-through-the-writer`: the raw facet is
not registered (`:malli.core/invalid-schema`), actual submitted transaction
data is absent, observed basis is absent both in the return and occurrence,
and the stored attempt's projection is absent. The fixture no longer
installs configuration solely for the producer. The recorder test decodes
the projection through `seon.sci.admit/semantic-value`, the codec's owner.

The first prospective schema edit was refused by the shared-tree admission
hook: `:seon.agent/context-state (unregistered-predicate)` naming
`seon.flow/atom-reference?`. A detached HEAD worktree at `16a869b2f`, linked
to the same dependency sources, ran the unchanged schema admission and
returned `()`, meaning zero error findings. Its required namespace load
printed `:loads`. No admission loading repair is justified by this probe.
The owner cluster remains PID 41822; read-only MCP health answered alive.
No lifecycle or adoption command was run.

The actual raw-facet admission then produced a different, reproducible
refusal in that worktree:

```text
:seon.schema.admission/declaration :seon.db.write/validation-refusal
:type :schema-malli-compilation
"Schema population did not compile through Malli: A stored error member must have a storable registered attribute."
```

`src/seon/schema/internal.cljc:161` applies the stored-member check to
every base extension. The bounded extension requested is to retain all
structural base/facet checks and apply storage checks to declarations
marked `:seon.db/attributes`, preserving the distinction already used by
the schema bridge. The raw facet explicitly asserts
`:seon.db/attributes false`; its transaction data never becomes datoms.
The canonical schema regression must continue refusing an unstoreable
member on a stored declaration while accepting a transient raw facet.

Additional scope requested, still pending at this checkpoint:

- `src/seon/schema/internal.cljc`, the storage-check condition above.
- `resources/seon/schemas/seon.schema.edn`, a raw schema-refusal declaration:
  the existing stored facet requires two projections, so producer-side
  bounding cannot honestly satisfy it.
- `src/seon/sci/kernel.clj`, only `failure-value`, for the two previously
  measured instrument-test errors.

The independent code-only slice accretes the nine measured test facets
into `semantic-value`, `refusal`, `latest-fact` and the observation grammar
restorer; the two authorized SCI acquisition catches now inspect the
required registration-observation member. Its serial three-suite run is
in progress. The raw-facet implementation is isolated in the lane worktree
until the admission boundary is resolved and verified.

### Unlanded raw-facet draft

The following patch is against the shared tree's code-only slice, not a
landed implementation. It deliberately does not edit the pending admission
owner. The writer regression follows in its own draft section below.
These bytes preserve
the reviewable draft while allowing disposable worktrees to be removed.

<details>
<summary>Raw write producer, recorder, reader and schema regression draft</summary>

```diff
--- a/resources/seon/schemas/seon.db.edn
+++ b/resources/seon/schemas/seon.db.edn
@@ -14,6 +14,7 @@
            :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
            :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
            :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+           :seon.db.write/validation-refusal
            :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
            :seon.flow/error :seon.fn/error :seon.fn.binding/error
            :seon.instrument/arity-error :seon.instrument/contract-error
--- a/resources/seon/schemas/seon.db.write.edn
+++ b/resources/seon/schemas/seon.db.write.edn
@@ -1,5 +1,16 @@
 {
  ; Additive error declaration manifest, 2026-09-18.
+
+ :seon.db.write/validation-refusal
+ [:and
+  {:seon.db/attributes false
+   :description "Raw refused request and its observed basis. Recorder admission projects the transaction data into the stored write attempt; this transient value is not a stored entity."}
+  :seon.error/base
+  [:map
+   [:seon.db.write.attempt/request-id :seon.db.write.attempt/request-id]
+   [:seon.error/basis :seon.error/basis]
+   [:seon.error/data
+    [:map [:seon.db.write.attempt/transaction :seon.db.write.attempt/transaction]]]]]

  :seon.db.write/attempt
  [:and
--- a/resources/seon/schemas/seon.db.write.attempt.edn
+++ b/resources/seon/schemas/seon.db.write.attempt.edn
@@ -1,5 +1,9 @@
 {
  ; Additive error declaration manifest, 2026-09-18.
+
+ :seon.db.write.attempt/transaction
+ [:schema {:description "Actual submitted transaction, carried as data to recorder admission. Never a database attribute or an executable recorder request."}
+  :seon.store/transaction]

  :seon.db.write.attempt/bound-ms
  [:int {:min 1}]
--- a/resources/seon/schemas/seon.error.edn
+++ b/resources/seon/schemas/seon.error.edn
@@ -207,6 +207,7 @@
              :prepared
              [:map
               [:seon.error/fact :seon.error/fact]
+              [:seon.error/source :seon.error/source]
               [:seon.error/data-content :seon.error/data-content]],
              :steward :seon.db/ref,
              :of-steward
--- a/src/seon/db.clj
+++ b/src/seon/db.clj
@@ -50,12 +50,6 @@
 ;;; `requiring-resolve` on every call (AGENTS §2.1).
 (defonce ^:private error-diagnostic
   (delay (requiring-resolve 'seon.error/diagnostic)))
-(defonce ^:private error-project-observation
-  (delay (requiring-resolve 'seon.error/project-observation)))
-(defonce ^:private config-effective
-  (delay (requiring-resolve 'seon.config/effective)))
-(defonce ^:private config-result-caps
-  (delay (requiring-resolve 'seon.config/result-caps)))
 (defonce ^:private error-explain-problem
   (delay (requiring-resolve 'seon.error/explain-problem)))
 (defonce ^:private error-problem-sentence
@@ -4102,37 +4096,19 @@
            (seq (d/datoms database :eavt eid :seon.agent/id))))))))

 (defn- write-observation
-  "Complete a refused write with the request and its immutable pre-write basis.
-  Before a branch has one selected configuration, retain the base refusal and
-  state why its bounded request projection could not be acquired."
+  "Carry the actual refused request and immutable pre-write basis as data.
+  Recorder admission owns its bounded stored projection."
   {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/transaction :seon.error/base]
-                  :seon.db/error-result]}
+                  [:and :seon.db.write/validation-refusal :seon.db/error-result]]}
   [database transaction observation]
-  (let [cluster-names (d/q '[:find [?name ...]
-                            :where [?cluster :seon.cluster/config ?configuration]
-                                   [?configuration :seon.config/cluster ?name]] database)
-        config-names (if (seq cluster-names) cluster-names
-                        (d/q '[:find [?name ...] :where [_ :seon.config/cluster ?name]] database))]
-    (if-not (= 1 (count config-names))
-      (assoc-in observation [:seon.error/data ::observation-unavailable]
-                "The write basis does not select exactly one configuration for bounded evidence.")
-      (let [configuration (@config-effective database (first config-names))
-            caps (@config-result-caps configuration)
-            at (:seon.error/at observation)]
-        (if-not (and (pos-int? (:seon.config.eval.result/max-bytes caps))
-                     (pos-int? (:seon.config.eval.result/max-source caps)))
-          (assoc-in observation [:seon.error/data ::observation-unavailable] caps)
-          (assoc observation
-                 :seon.db.write/attempt
-                 {:seon.db.write.attempt/request-id (id/id)
-                  :seon.db.write.attempt/observed-at at
-                  :seon.db.write.attempt/operations
-                  (@error-project-observation caps transaction)}
-                 :seon.error/basis
-                 {:seon.error.basis/store (datahike.store/store-identity (:store (dbi/-config database)))
-                  :seon.error.basis/branch (:branch (dbi/-config database))
-                  :seon.error.basis/commit (d/commit-id database)
-                  :seon.error.basis/t (dbi/-max-tx database)}))))))
+  (-> observation
+      (assoc :seon.db.write.attempt/request-id (id/id)
+             :seon.error/basis
+             {:seon.error.basis/store (datahike.store/store-identity (:store (dbi/-config database)))
+              :seon.error.basis/branch (:branch (dbi/-config database))
+              :seon.error.basis/commit (d/commit-id database)
+              :seon.error.basis/t (dbi/-max-tx database)})
+      (assoc-in [:seon.error/data :seon.db.write.attempt/transaction] transaction)))

 (defn- transact-call
   {:malli/schema
--- a/src/seon/error.clj
+++ b/src/seon/error.clj
@@ -91,7 +91,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
@@ -226,7 +226,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
@@ -251,6 +251,7 @@
          projection ::observation-attributes
          (fn []
            (let [forms (:seon.schema.projection/forms projection)
+                 stored-attributes (set (schema.form/database-attributes forms))
                  attributes
                  (loop [pending (vec (conj (facet-keys projection) :seon.error/base))
                         seen #{} result #{}]
@@ -264,8 +265,9 @@
                                 (into result members))))
                      result))]
              (into {}
-                   (map (fn [attribute]
-                          [attribute (schema.datahike/malli->datahike-attr-in projection attribute)]))
+                   (comp (filter stored-attributes)
+                         (map (fn [attribute]
+                                [attribute (schema.datahike/malli->datahike-attr-in projection attribute)])))
                    attributes))))]
     (letfn [(restore [value]
               (if-not (map? value)
@@ -302,7 +304,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
@@ -369,7 +371,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
@@ -690,6 +692,17 @@
                             :seon.error/layer :seon.error/normalization
                             :seon.error/operation (or function 'seon.error/normalize)}
                            (when (map? error-value) error-value))
+        observation
+        (if ((schema/projection-validator projection :seon.db.write/validation-refusal) observation)
+          (-> observation
+              (assoc :seon.db.write/attempt
+                     {:seon.db.write.attempt/request-id (:seon.db.write.attempt/request-id observation)
+                      :seon.db.write.attempt/observed-at (:seon.error/at observation)
+                      :seon.db.write.attempt/operations
+                      (project-observation caps (get-in observation [:seon.error/data :seon.db.write.attempt/transaction]))})
+              (dissoc :seon.db.write.attempt/request-id)
+              (update :seon.error/data dissoc :seon.db.write.attempt/transaction))
+          observation)
         signature (signature projection observation
                              (or (some-> class-name symbol)
                                  (:seon.error/exception-class observation))
@@ -763,6 +776,7 @@
                                  (not= full-edn
                                        (:seon.error/data-edn fact)))))]
     {:seon.error/fact fact
+     :seon.error/source observation
      :seon.error/data-content full-edn}))

 (defn normalize
@@ -824,7 +838,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
@@ -860,7 +874,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
@@ -1636,7 +1650,9 @@
          request (cond-> request (map? source)
                    (assoc :seon.error/source
                           (stored-observation (:seon.schema/projection request) source)))
-         fact (or (:seon.error/fact request) (normalize request))
+         prepared (prepare request)
+         request (assoc request :seon.error/source (:seon.error/source prepared))
+         fact (or (:seon.error/fact request) (:seon.error/fact prepared))
          signature (:seon.error/signature fact)
          agent-id (second (:seon.error/agent fact))
          turn-id (second (:seon.error/run fact))
@@ -1686,10 +1702,12 @@
    projection ::observation-selector
    (fn []
      (let [forms (:seon.schema.projection/forms projection)
+           stored-attributes (set (schema.form/database-attributes forms))
            observation-keys (conj (facet-keys projection)
                                   :seon.error/base :seon.error.occurrence/occurrence)]
        (letfn [(members [schemas]
-                 (sort (into #{} (mapcat #(map first (schema.form/map-entries forms (get forms %)))) schemas)))
+                 (sort (into #{} (comp (mapcat #(map first (schema.form/map-entries forms (get forms %))))
+                                       (filter stored-attributes)) schemas)))
                (selector [schemas active]
                  (into [:db/id]
                        (map (fn [attribute]
@@ -1717,7 +1735,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
@@ -1772,7 +1790,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
@@ -1947,7 +1965,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
--- a/src/seon/instrument.clj
+++ b/src/seon/instrument.clj
@@ -542,7 +542,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
@@ -580,7 +580,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
--- a/src/seon/sci/admit.clj
+++ b/src/seon/sci/admit.clj
@@ -555,7 +555,7 @@
      :seon.cluster/error :seon.cluster.prompt/error :seon.cluster.registry/error
      :seon.cluster.reply/error :seon.cluster.source/error :seon.cluster.store/error
      :seon.cluster.wake/error :seon.config/error :seon.config/rule-error
-     :seon.db.availability/error :seon.db.read/error :seon.db.write/error
+     :seon.db.availability/error :seon.db.read/error :seon.db.write/error :seon.db.write/validation-refusal
      :seon.dev.mcp/error :seon.effect/error :seon.env/error :seon.eval.drive/error
      :seon.flow/error :seon.fn/error :seon.fn.binding/error
      :seon.instrument/arity-error :seon.instrument/contract-error
--- a/test/seon/schema_test.clj
+++ b/test/seon/schema_test.clj
@@ -1239,12 +1239,24 @@
                 [:and :seon.error/base [:map [:seon.error/at :string]]]
                 [:and :seon.error/base [:map]]
                 [:and :seon.error/base [:map [::domain-marker ::domain-marker]]]
-                [:and :seon.error/base [:map [::raw-payload ::raw-payload]]]]]
+                [:and {:seon.db/attributes true} :seon.error/base
+                 [:map {:seon.db/attributes true} [::raw-payload ::raw-payload]]]]]
          (let [outcome (try (schema/build-projection
                             (assoc forms ::domain-marker :boolean ::raw-payload :map
                                    ::invalid-facet definition))
                            nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
-           (is (map? outcome) (str "Declaration must refuse: " definition))))))))
+           (is (map? outcome) (str "Declaration must refuse: " definition))))
+       (let [raw (schema/build-projection
+                  (assoc forms ::raw-payload :map
+                         ::raw-facet [:and {:seon.db/attributes false} :seon.error/base
+                                      [:map [::raw-payload ::raw-payload]]]))
+             observation {:seon.error/at (java.util.Date.)
+                          :seon.error/layer ::admission
+                          :seon.error/operation 'seon.schema-test/error-declarations-expand-all-inherited-members
+                          ::raw-payload {::observed (Object.)}}]
+         (is ((schema/projection-validator raw ::raw-facet) observation))
+         (is (not (some #{::raw-payload}
+                        (schema.form/database-attributes (:seon.schema.projection/forms raw))))))))))

 (deftest error-facets-and-their-owned-members-are-storable
   (test-support/with-database
@@ -1254,6 +1266,7 @@
            facets (into #{:seon.error/base}
                         (keep (fn [[k definition]]
                                 (when (and (vector? definition)
+                                           (:seon.db/attributes (schema.form/schema-properties definition))
                                            (schema.form/extends-schema? forms definition :seon.error/base)) k)))
                         forms)
            declarations
```

</details>

The necessary admission change, pending scope, is:

```diff
--- a/src/seon/schema/internal.cljc
+++ b/src/seon/schema/internal.cljc
@@
-        (owned-storage! definition #{identity}))
+        (when (:seon.db/attributes (form/schema-properties forms definition))
+          (owned-storage! definition #{identity})))
```

This uses the schema bridge's same storage declaration and retains every
base-member, required-domain-member and boolean-marker check above it.

### Contract declaration checkpoint

The isolated run at `16a869b2f` reached
`semantic-admission-explicitly-declares-every-error-facet` at
2026-09-19T22:56:55Z: **1 test / 6 assertions / 0 failures / 0 errors**.
The nine-facet additions land in `src/seon/error.clj` and
`src/seon/sci/admit.clj`; the same nine are declared by the owned observation
grammar restorer, which also preserves a returned observation. The full
three-suite tally is still pending at this checkpoint.

At 2026-09-19T23:02:37Z the isolated three-suite run finished: **118 tests /
4,150 assertions / 24 failures / 4 errors**. Error tests remain **42 / 0F /
0E**; instrument tests are **44 / 0F / 3E**; schema tests remain **32 / 24F /
1E**. The three manifest assertions are now green. The sovereign-fork
acquisition still reached `seon.sci.eval/acquisition-refusal`; replacing
the marker on the outer `ex-data` alone did not establish the cause.

Commit `524aa07d6` passes the required HEAD namespace load in a fresh
worktree: exit 0 and `:loads`. Its loading worktree has been removed.
The independent SCI follow-up now reads the original refusal with the
existing pure cause-chain owner before testing its registration member;
SCI's wrapper stores the original exception as its cause
(`reference-code/sci/src/sci/impl/utils.cljc:180`). The owned sovereign-fork
regression observes record-mode values directly and retains panic-mode
ex-data capture. A serial instrument-only run is verifying these changes.

The shared-tree fast attempt preceding this isolated run exited **64**
before launching tests: overlay admission requested dirty callers in held
render files and `src/seon/cluster/source.clj`, plus the pending raw writer
regression. The isolated HEAD worktree excluded all those uncommitted
inputs. This is the exact foreign verification boundary, not a test red.

### Raw writer/recorder regression draft

This regression belongs with the unlanded raw model, and is preserved here
so the shared tree can retain the accepted stored-model tests while scope
is resolved. The preceding 42/349/4F/1E run measured its initial version;
the draft below also checks that recording never executes the refused
transaction and decodes the admitted print node through its owner.

```diff
diff --git a/test/seon/error_test.clj b/test/seon/error_test.clj
index b203384fc..b624b6316 100644
--- a/test/seon/error_test.clj
+++ b/test/seon/error_test.clj
@@ -1070,7 +1070,6 @@
      {::test-support/extra-schema
       (schema.datahike/malli->datahike-schema-in projection [::manifest-id ::manifest-location ::manifest-observation ::manifest-explanations])}
      (fn [connection]
-       (test-support/apply-config! connection "error-manifest" {})
        (db/carry-connection-projection-state!
         connection (sci.eval/projection-state @connection projection))
        (test-support/transacted! connection [{::manifest-id "root-path"
@@ -1135,10 +1134,29 @@
              segment (:v (first (db/datoms database :eavt location :seon.error.location/segments)))
              before (db/basis-t database)
              result (db/transact! connection [[:db/add segment :seon.error.location.segment/ordinal 3]])]
-         (is (schema/valid-candidate-value? :seon.db.write/error result) (pr-str result))
+         (is (schema/valid-candidate-value? :seon.db.write/validation-refusal result) (pr-str result))
+         (is (= [[:db/add segment :seon.error.location.segment/ordinal 3]]
+                (get-in result [:seon.error/data :seon.db.write.attempt/transaction])))
          (is (= before (get-in result [:seon.error/basis :seon.error.basis/t])))
          (is (= before (db/basis-t (db/db connection))))
-         (is (= 2 (count (db/datoms database :eavt location :seon.error.location/segments)))))))))
+         (is (= 2 (count (db/datoms database :eavt location :seon.error.location/segments))))
+         (let [recording (error/recording database (commit-request result {}))
+               report (test-support/transacted! connection (:seon.db/tx-data recording))
+               occurrence (first (:seon.error/occurrences
+                                  (db/pull (:db-after report) (error/observation-selector projection)
+                                           (:seon.error/ref recording))))
+               attempt (:seon.db.write/attempt occurrence)]
+           (is (= (:seon.db.write.attempt/request-id result)
+                  (:seon.db.write.attempt/request-id attempt)))
+           (is (= before (get-in occurrence [:seon.error/basis :seon.error.basis/t])))
+           (is (= (mapv :v (db/datoms database :eavt segment :seon.error.location.segment/ordinal))
+                  (mapv :v (db/datoms (:db-after report) :eavt segment :seon.error.location.segment/ordinal)))
+               "The recorder stores the submitted transaction as evidence; it never executes it.")
+           (is (= (get-in result [:seon.error/data :seon.db.write.attempt/transaction])
+                  (admit/semantic-value
+                   (edn/read-string (get-in attempt [:seon.db.write.attempt/operations :seon.instrument/actual])))))
+           (is (false? (get-in attempt [:seon.db.write.attempt/operations :seon.error/capped?])))
+           (is (not (contains? occurrence :seon.db.write.attempt/request-id)))))))))

 (deftest error-facets-persist-through-the-real-occurrence-owner
```

### Final scope boundary and measured SCI cause

The first focused SCI run finished at 2026-09-19T23:07:17Z:
**44 tests / 319 assertions / 0 failures / 3 errors**. Its compact acquisition
evidence identified `:seon.sci.eval/install-source-mismatch` for
`[:seon.fn/sym seon.id/valid?]`, with the message
`Committed declaration source does not match install request.`

The next canonical-fixture regression asserted the wildcard read's source
before acquisition. At 2026-09-19T23:15:52Z it measured **44 tests / 320
assertions / 1 failure / 3 errors**. The new assertion falsified the read:

```text
expected: (= "(defn valid? [length id] true)" (:seon.fn/source committed))
actual: (not (= "(defn valid? [length id] true)" nil))
seon.error/diagnostic refused argument 0 ... missing :seon.error/at.
Called from seon.schema (schema.clj:2796).
```

Thus the sovereign-fork source mismatch is downstream of the incomplete
`pulled-selector-refusal` producer, the same producer behind the recursive
pull-shape failures. It is not evidence that the actual committed source
changed. The two acquisition catches still require their retirement:
they now examine the registration facet's required member on the original
cause-chain refusal. The test observes record-mode return values directly,
retains panic-mode ex-data capture, and preserves the new upstream read
assertion. Temporary diagnostic printing was removed after recording the
evidence above; no assertion was removed or weakened.

The raw model ruling stands. Completion needs these three scope extensions,
requested during this turn and unanswered:

1. `src/seon/schema/internal.cljc`: apply its stored-member checks to the
   declarations marked for storage. The exact proposed condition and its
   canonical fixture regression are above.
2. `resources/seon/schemas/seon.schema.edn`: declare raw producer evidence
   for the three authorized schema diagnostic producers, then admit it in
   the recorder to the existing stored schema facet. Both existing stored
   members require projections; supplying raw values there would change
   their meaning, while producer-side projection would contradict the ruling.
3. `src/seon/sci/kernel.clj`: `failure-value` only, for the two measured
   missing-base errors in the kernel regressions.

The pending raw producer/recorder and its regression are preserved as
reviewable patches in this note. They are not installed in the shared tree:
the accepted error tests remain unchanged until the admission change can
land coherently. No schema resource or database producer change is claimed
as landed by this continuation. The completed production/test slice touches
`src/seon/error.clj`, `src/seon/sci/admit.clj`, `src/seon/sci/eval.clj`,
`test/seon/instrument_test.clj`, and this landing note.

A foreign uncommitted `base-extending-facet-compiles-without-enumerating-the-registry`
test appeared in `test/seon/schema_test.clj` during this run; it was preserved
and excluded from this lane's commits and snapshots. The load/tree-discipline
update in `9f9ff4fe3` was read when it appeared. The last focused run was the
lane's only active JVM, and no new worktree was created after that update.
The required shared-tree namespace load now exits 0 and prints `:loads`.

### RESET NEEDED and cold proof still owed

No additional reset attributes in the completed contract/catch slice.
The raw draft introduces `:seon.db.write.attempt/transaction` as a transient
schema key, not a stored attribute. It changes no existing stored type.
The earlier consolidated reset obligations remain owed to the orchestrator;
this lane ran no lifecycle, reset, refork, restart, adoption or cold gate.

After the pending scope lands, the orchestrator owes the cold command below
and platform/live proof. Include the pending resource/admission/kernel paths
when they land, and include the complete error-resource/reset batch from
the earlier landing sections.

```sh
bin/test --paths \
  src/seon/error.clj src/seon/instrument.clj src/seon/schema.clj \
  src/seon/db.clj src/seon/cluster/status.clj \
  src/seon/sci/admit.clj src/seon/sci/eval.clj \
  test/seon/error_test.clj test/seon/instrument_test.clj test/seon/schema_test.clj \
  docs/prds/steward-platform/research/error-family-1a-2026-09-19.md \
  -- seon.error-test seon.instrument-test seon.schema-test \
     seon.db-test seon.cluster-test seon.sci.eval-test
```

### Probe artifacts and cleanup

The lane's test and load JVMs exited. No Java process referenced the
remaining lane worktree before cleanup; its five shared-target symlinks
were unlinked first. All three lane worktrees have been removed, and no
foreign worktree, run root or session was changed. The declaration and
writer/recorder regression drafts remain in this note, rather than as
uncommitted failing schema/test changes in the shared tree.

| Iteration | Log bytes | SHA-256 |
|---|---:|---|
| Raw regression before implementation | 25,007 | `7775c4b65facabd26a88ad0f623181eb0580c96b1abbe25c5c592f277b19a477` |
| Shared overlay refusal | 914 | `2af4e78fce18bbcb0cd7f27c469f6334be109fc11fbbfd9052af2808b0716293` |
| Isolated three-suite run | 127,131 | `ef4f66a0b2e24c4798ca20c952960887004c0ff7f9bc7ce5de6eac6e54f320b1` |
| SCI cause-chain run | 22,501 | `3b6dff7dea5d9878c8cbb56357e3edebd1799c665873c804b75e832cc826782d` |
| SCI source-read probe | 23,014 | `1a6132691ff4ed67bdf65a9afe2ea1a503fefe40dda709a274c319961d8264c5` |
| Unchanged HEAD schema admission | 763 | `08f75f81a92826ba15d973a8650156cff155a608d0b173a4a86d1df44d4f6f64` |
| Raw-facet admission refusal | 1,129 | `90c1d70da21ab87960876526b35af27740b17f21310c59bd504e355a494d57ba` |

The completed slices introduce no kind or general error predicate. The
three-suite task remains **not green**, with the exact remaining producer,
admission and kernel boundaries above. Cold and live proof remain with the
orchestrator.

## Composition continuation — 2026-09-20, blocked at the foreign overlay boundary

Read the composition review Part 1 and Part 2 ranks 7–8, and the amended
error-conversion PRD sections 1.1–1.2 end to end. The newly granted raw
admission/schema/kernel scope is accepted; the earlier request for that
scope is settled. The raw producer/recorder drafts above remain pending.

### Complete open observations at the constructor and wrapper

Moved the sole pure constructor and its evidence helper to
`src/seon/error/refusal.clj`. `seon.error/diagnostic` keeps its public input
and output declaration and delegates directly to that leaf. No dynamic
resolution or second constructor was added. The leaf retains all supplied
domain members while moving its seven diagnostic request members into their
existing evidence map; unavailable evidence retains the same qualified
unknown values. The canonical fixture regression supplies an agent facet and
an additional domain map, then validates that facet and retained map.

The wrapper now refuses an error result when it satisfies no declared facet
(and the boundary does not explicitly declare the base). Additional satisfied
facets do not invalidate an otherwise complete declared facet. Malli still
validates the authored output, including conjunction constraints. The existing
broad-success-arm regression now also verifies that the composed value
satisfies exactly the agent and unknown-test facets and passes a boundary
which declares the agent facet. Existing undeclared-only and incomplete-error
regressions remain. These changes must land together: preserving facets at a
base-declared constructor would trip the previous all-facets wrapper rule.

### Exact verification

The HEAD-plus-tests baseline, HEAD `9ad085939834143948043dda2cc6917b4f7ebb85`,
finished at `2026-09-19T23:33:31Z`: **120 tests / 4,161 assertions /
27 failures / 4 errors**, exit 1. The new constructor regression produced
exactly two failures: the constructed value failed `:seon.agent/error`, and
`:seon.error-test/context` was nil rather than the supplied map. The existing
sovereign SCI source assertion accounts for one failure; the other 24 failures
and four errors reproduce the schema and kernel boundaries already recorded.
The initial extra-facet test input used the wrong member; after reading the
actual declaration it was corrected to `:seon.test/unknown` and an explicit
facet-set assertion was added. The baseline therefore proves the constructor
regression, not the corrected extra-facet regression.

The required five-namespace load exited 0 and printed `:loads` after the
constructor/wrapper edits. `git diff --check` passed. The post-change fast
snapshot at HEAD `4b3c4b5f39efa622fda67b1d0a9ad2b11d091905` refused before a
test JVM launched, exit **64**, with these exact bytes:

```text
bin/test: Incomplete --paths overlay; add changed caller files: src/seon/render.clj src/seon/render/transcript.clj src/seon/render/walk.clj src/seon/render/web.clj src/seon/sci/kernel.clj test/seon/render/faults_test.clj test/seon/render/history_test.clj test/seon/render/retained_test.clj test/seon/render/root_pull_test.clj test/seon/render/transcript_run_test.clj test/seon/render/transcript_test.clj test/seon/render/walk_test.clj test/seon/render/web_debug_test.clj test/seon/render/web_test.clj
```

The kernel path was this lane's own uncommitted draft. The other 13 paths
belong to the held render slice. They were neither edited nor included in the
snapshot. AGENTS.md lane rule 12 requires stopping at an overlay refusal naming
a foreign dirty caller; this assignment also explicitly forbids a worktree.
No workaround, foreign session operation, lifecycle action, or cold gate ran.
**The suites are not green and there is no post-change fast tally.**

The kernel draft below is preserved for continuation and removed from the
working source so the pending cause remains separate from this composition
slice. Its existing two failing regressions were re-observed in the baseline;
its implementation has not been verified under armed tests. Its deadline
regression must assert `:seon.sci.kernel/error` and `:time` at
`[:seon.error/data :seon.sci.admit/record :seon.eval/outcome]`, replacing the
retired `:seon.sci.eval/time-limit` assertion.

<details>
<summary>Pending authorized kernel draft</summary>

```diff
diff --git a/src/seon/sci/kernel.clj b/src/seon/sci/kernel.clj
index 18bcd441f..0d6c11d13 100644
--- a/src/seon/sci/kernel.clj
+++ b/src/seon/sci/kernel.clj
@@ -517,28 +517,12 @@
             (.remove thread-arm)))))))

 (defn failure-value
-  "The ONE flat `:seon.error` value for a failure at the guarded boundary.
-
-  Both entrances classify here so they cannot drift apart. A throwable that
-  already carries a refusal — an instrument contract violation, a refused
-  schema declaration, an unresolved invocation — keeps its own
-  `:seon.error/kind` and gains this boundary's evidence; everything else
-  becomes `::time-limit-kind` when the diagnostic record's outcome is `:time`
-  and `::failure-kind` otherwise. `:seon.fn/sym` is the invoked function
-  symbol when one exists: it prefixes the message and rides in the data. A
-  form evaluation supplies no symbol, which is the ONLY difference between
-  the two entrances — the classification itself is identical.
-
-  THE OUTPUT UNION ENUMERATES EVERY ERROR FACET (program-facts PRD §1q).
-  This boundary classifies failures it did not raise, so the facet a value
-  carries is whatever the original refusal declared. A generic pass-through
-  lists the whole facet population rather than claiming a narrower one; the
-  armed wrapper derives its permissions from that union and refuses an
-  unlisted facet. The 63 members are `seon.error/facet-keys` over the
-  packaged declarations, sorted, on 2026-09-18."
+  "Preserve an existing structural refusal and accrete guard evidence.
+  Otherwise return the kernel facet with the observed evaluation duration;
+  the complete diagnostic record retains whether the deadline fired."
   {:malli/schema
    [:=> [:cat :seon.sci.kernel/failure-request [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A kernel failure can carry any thrown or returned value; normalization must preserve evidence of an unrecognized failure.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.sci.admit/record]
-    [:or :seon.error/value :seon.error/base
+    [:or :seon.error/base
          :my.background/error :my.edit/error :my.fs/error :my.message/error :my.plan/error
          :my.shell/error :my.turn/error :seon.agent/error :seon.agent.graph/error
          :seon.ai/request-error :seon.artifact/error :seon.boot/error :seon.bootstrap/error
@@ -558,9 +542,7 @@
          :seon.sci.eval/evaluation-error :seon.sci.kernel/error :seon.sci.reader/error
          :seon.search/error :seon.test/error :seon.test.accretion/error :seon.test.run/error
          :seon.test.runner/error :seon.turn/error :seon.turn.loop/error]]}
-  [{subject :seon.fn/sym
-    time-limit-kind ::time-limit-kind
-    failure-kind ::failure-kind}
+  [{subject :seon.fn/sym}
    throwable
    diagnostic-record]
   (let [timed-out? (= :time (:seon.eval/outcome diagnostic-record))
@@ -569,7 +551,9 @@
                           :seon.sci.admit/record diagnostic-record}
                    subject (assoc :seon.fn/sym subject))
         existing (error.refusal/refusal throwable)]
-    (if (:seon.error/kind existing)
+    (if (and (:seon.error/at existing)
+             (:seon.error/layer existing)
+             (:seon.error/operation existing))
       ;; The refusal is already the boundary value. Wrapping it copied its
       ;; data, its ex-data (the whole refusal), and its throw-site message into
       ;; a `:nested-refusal` envelope, so the terminal renderer fitted six
@@ -581,11 +565,12 @@
         (assoc :seon.error/message
                (or (ex-message throwable) "The operation was refused.")))
       (error/diagnostic
-       (let [kind (if timed-out? time-limit-kind failure-kind)]
-         {kind (or subject (if timed-out?
-                             (:seon.eval/fn-entries diagnostic-record)
-                             true))
-        :seon.error/kind kind
+       {:seon.error/at (java.util.Date.)
+        :seon.error/layer :seon.sci.kernel/evaluation
+        :seon.error/operation 'seon.sci.kernel/failure-value
+        :seon.sci.kernel/guard-observation
+        {:seon.error.evidence/attribute :seon.eval/duration-ms
+         :seon.error.evidence/value (:seon.eval/duration-ms diagnostic-record)}
       :seon.error/message
       (or (:seon.error/message existing)
           (cond->> (if timed-out?
@@ -595,7 +580,7 @@
                          (.getName (class throwable))))
             subject (str "Invocation of " subject " failed: ")))
       :seon.error/diagnostic-layer :sci
-      :seon.error/diagnostic-operation (or subject :evaluation)
+      :seon.error/diagnostic-operation 'seon.sci.kernel/failure-value
       :seon.error/diagnostic-member :throwable
       :seon.error/diagnostic-expected :successful-evaluation
       :seon.error/diagnostic-offending
@@ -612,7 +597,7 @@
         (assoc :seon.sci.eval/symbol (:sci.impl/symbol throwable-data))

         (ex-message throwable)
-        (assoc :seon.error/throw-site-message (ex-message throwable)))})))))
+        (assoc :seon.error/throw-site-message (ex-message throwable)))}))))

 (defn unarmed-record
   "The diagnostic record for a failure that never reached an arm."

```

</details>

### Touched files, reset, and proof owed

The composition slice touches `src/seon/error.clj`,
`src/seon/error/refusal.clj`, `src/seon/instrument.clj`,
`test/seon/error_test.clj`, `test/seon/instrument_test.clj`, and this note.
The temporary kernel implementation is preserved only as the draft above.
`test/seon/schema_test.clj` was clean at this continuation's first status;
its previously foreign regression was already landed and was not altered.
All unrelated uncommitted files were preserved.

**RESET NEEDED:** no additional attributes from this slice; the prior batch's
exact attribute obligations remain unchanged. No raw attribute or stored type
change lands in this continuation.

The next continuation still owes the authorized raw write/schema admission,
the three schema producers and their recorder conversion, and the kernel
repair, followed by the three-suite green fast tally. The orchestrator owes
this cold gate with the complete prior resource/reset batch appended:

```sh
bin/test --paths \
  src/seon/error.clj src/seon/error/refusal.clj src/seon/instrument.clj \
  src/seon/schema.clj src/seon/schema/internal.cljc src/seon/db.clj \
  src/seon/cluster/status.clj src/seon/sci/admit.clj src/seon/sci/eval.clj \
  src/seon/sci/kernel.clj resources/seon/schemas/seon.schema.edn \
  resources/seon/schemas/seon.db.edn resources/seon/schemas/seon.db.write.edn \
  resources/seon/schemas/seon.db.write.attempt.edn resources/seon/schemas/seon.error.edn \
  test/seon/error_test.clj test/seon/instrument_test.clj test/seon/schema_test.clj \
  -- seon.error-test seon.instrument-test seon.schema-test \
     seon.db-test seon.cluster-test seon.sci.eval-test
```

| Evidence | Bytes | SHA-256 |
|---|---:|---|
| Combined pre-change fast run | 128,632 | `9abc596e0a1a60ed32b94362469ae1788fa252e8082807c07bc5c4d73c6444ac` |
| Post-change overlay refusal | 975 | `17753b1f9c8b8e9a7ab207d65d310cc0a2e9482963b2888d18088ffa4b6004a8` |
| Required namespace load | 205 | `579c3de9fb226ffa47e63f1160596a1fe89089af30718e0a8855f4a369c28a18` |

## Raw producer and recorder continuation (2026-09-20)

The orchestrator explicitly authorized plain working-tree fast iteration after
the overlay refused the paused render lane's dirty callers. No worktree,
lifecycle operation, or cold gate was used. The first five-suite run completed
with **198 tests / 5,194 assertions / 32 failures / 29 errors**. The raw writer
regression and both kernel regressions passed; this is an intermediate tally.

Database validation refusals now carry request identity, the observed basis
entity, and the actual submitted transaction as transient data. The writer no
longer acquires rendering configuration or projects the transaction. `prepare`
projects that evidence into the unchanged stored write attempt before deriving
the signature; recording stores the prepared observation. The regression reads
the occurrence through the real owner, decodes the projected transaction, checks
the original basis/request identity, and verifies recording did not execute it.

Schema's three diagnostic producers now construct complete raw
`:seon.schema/validation-refusal` values through the leaf constructor. Recorder
admission projects the refused declaration and expected key into the existing
stored schema facet. Transient facets retain declaration validation but do not
promise Datahike attributes; storage readers select only declared datom
attributes. Pull-result and projection-cache outputs declare the raw refusal,
and the pull consumer branches on its required expected-value member.

The first run verified the remaining pull failures named the projection cache's
undeclared raw facet. The new schema-recorder fixture initially supplied a
keyword to a map-input producer, so it observed instrumentation instead of that
producer; the fixture now supplies a map. Neither contract was weakened.

The cause-chain reader preserves the deepest observation with all three base
members even when a deeper exception carries ordinary data. Its pass-through
contracts include the nine test facets. The kernel separately preserves that
value, or constructs its declared guard-observation facet with actual duration
and the complete diagnostic record. The deadline regression tests that facet
and the recorded `:time` outcome.

The working-tree facet manifest had 89 facets versus 74 declared at three
pass-through boundaries. Fifteen additional facets were in foreign render
drafts; this is a measured integration boundary, not permission to add references
to schemas absent from HEAD. Final verification below will name the remaining
tests and exact missing keys. The open-map wrapper regression now asserts that
both intended facets are present rather than asserting an exclusive taxonomy:
the same value also satisfies the newly landed `:seon.test/expired` shape.

### RESET NEEDED for this continuation

No stored attribute type changes. The new keys
`:seon.db.write.attempt/transaction`, `:seon.schema/refused-value`, and
`:seon.schema/expected-value` are transient. Existing stored write-attempt and
schema-error shapes are unchanged. Earlier reset-batch obligations in this note
remain owed.

## Verification boundary after ac3944048 and fc23a08b8 (2026-09-20)

Both path-limited commits passed the required five-namespace load before and
after landing. `ac3944048` owns raw producer/recorder conversion; `fc23a08b8`
owns the kernel repair and composition regression. The next plain working-tree
run completed **198 tests / 5,215 assertions / 17 failures / 24 errors**.

| Namespace | Tests | Failures | Errors |
|---|---:|---:|---:|
| `seon.error-test` | 45 | 3 | 1 |
| `seon.instrument-test` | 44 | 3 | 0 |
| `seon.schema-test` | 34 | 2 | 0 |
| `seon.db-test` | 63 | 4 | 23 |
| `seon.cluster-test` | 12 | 5 | 0 |

The only remaining error-suite test is
`complete-error-children-validate-through-the-writer`: its raw-facet, submitted
transaction, observed-basis and unchanged-database assertions pass, but the
subsequent root pull does not supply an occurrence/attempt (3 failures, 1 error).
The first run passed this test. The second followed the pull-cache contract
repair; this does **not** establish that storage lost the attempt. The added
assertion retains the complete root read result so the next run can distinguish
a read refusal from missing datoms. Its execution is blocked below; this item
remains open. `schema-refusals-are-admitted-at-the-recorder` and the structural
cause-chain regression pass.

Instrumentation's three failures are all
`semantic-admission-explicitly-declares-every-error-facet`, at
`seon.sci.admit/semantic-value`, `seon.error/refusal`, and
`seon.error/latest-fact`. The exact missing keys, all declared by the paused
render lane's uncommitted resources, are:

```clojure
#{:seon.render/request-error :seon.render.transcript/request-error
  :seon.render.walk/elided-error :seon.render/invalid-output-error
  :seon.render/ambiguous-error :seon.render.data/no-such-path-error
  :seon.render.web/value-unreadable-error :seon.render.web/missing-port-error
  :seon.render.walk/no-such-entity-error :seon.render/walk-failed-error
  :seon.render.web/value-not-found-error :seon.render/unknown
  :seon.render.web/function-unavailable-error
  :seon.render.data/observation-error :seon.render.web/request-error}
```

Their owning files are `resources/seon/schemas/seon.render.edn`,
`seon.render.transcript.edn`, `seon.render.walk.edn`, `seon.render.data.edn`, and
`seon.render.web.edn` in that directory. No references to unpublished foreign
facets were added to HEAD. Both kernel regressions, both host/SCI multi-assertion
contract tests, and `a-sovereign-sci-fork-acquires-its-own-recorder` pass.

Schema's remaining foreign boundary is
`declared-reference-maps-accept-the-pull-reference-grammar` at
`test/seon/schema_test.clj:279`: the generated pulled row for the draft
`:seon.render/ambiguous-error` fails at `:seon.render/candidates`, whose owner is
`resources/seon/schemas/seon.render.edn`. The second schema failure was the
`pulled-forms-derive-from-the-entity-schema-and-selector` assertion expecting
`seon.schema/pulled-form-in` instead of the actual observing producer
`seon.schema/pulled-selector-refusal`; corrected after the run. The canonical
reference grammar test (formerly 14 failures) and render-contract coherence
test pass. No render file was edited.

The database suite is outside the granted test-file scope. Its remaining tests
and event counts are recorded below; the normalized-form errors at
`src/seon/fn/schema_shape.clj:130` are the already open class in
[the projection/refusal issue](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md).
Several tests still construct or assert the retired kind-only shape; no kind
or general predicate was restored to satisfy them.

| Database test | Failures | Errors |
|---|---:|---:|
| `a-refused-declarations-read-refuses-decoding` | 1 | 0 |
| `a-write-naming-another-clusters-branch-is-refused-naming-both` | 0 | 1 |
| `every-database-value-reader-answers-for-all-four-view-shapes` | 0 | 1 |
| `every-public-read-preserves-an-upstream-database-error` | 0 | 15 |
| `malformed-public-database-requests-name-the-public-operation` | 2 | 0 |
| `malformed-reads-return-flat-errors` | 1 | 0 |
| `non-temporal-reads-return-one-flat-error-before-datahike` | 0 | 1 |
| `non-unique-writer-rejections-retain-their-datahike-data` | 0 | 1 |
| `transaction-wrappers-cannot-hide-a-classified-refusal` | 0 | 2 |
| `uncarried-read-reports-one-projection-fallback-per-call` | 0 | 1 |
| `unique-rejection-names-the-existing-owner-as-data` | 0 | 1 |

Cluster's `a-commit-tx-request-without-the-evidence-bound-names-the-key` still
asserts the retired kind. Four other failures are in
`a-dropped-storage-facet-refuses-reopening-the-branch-in-place`,
`schema-row-convergence-uses-the-stores-own-semantics`, and
`an-added-index-adopts-in-place-instead-of-forcing-a-refork`. The actual
convergence delta includes `:seon.config/display-divisor`, `/display-label`,
`/display-unit`, and `:seon.source/refused-test-run`, added by foreign edits
after fixture acquisition. The earlier run similarly named the then-new
`:seon.test.run/overlay-input-digest` and `/published-base-digest`. These are
working-tree/loaded-fixture boundaries, not an error-family storage migration.

### The next run admitted zero tests

After the second tally, the live-edited launcher printed
`bin/test-fast: line 45: syntax error near unexpected token then` (exit 2).
The subsequent launch parsed, loaded, and armed 1,391 contracts, then exited 1
before a test began:

```text
bin/test-fast: initialization or execution failed: Snapshot admission refused.
Per-agent dial :seon.config.ai.backup/api-key-variable must declare a nonempty :seon.config/display-label.
```

The returned observation names `seon.test.runner/record-snapshot!`, with causal
operation `seon.schema/assert-config-display!`, expected key
`:seon.config/display-label`, and the refused published definition
`[:string {:min 1, :seon.config/optional true, :seon.config/per-agent true,
:seon.config/dial true}]`. The new working-tree resource already carries the
label; the snapshot reader reports published base
`c7c66f815606ca3f11a53ab24f6067df22c9449f4e8eef83d6a3b20fcef150c3`,
97 commits behind HEAD. Run identity: `879e7da68973`.

Verified held files: `src/seon/test/fast.clj`, `bin/test-fast`, `bin/test`,
`src/seon/schema.clj` (foreign `assert-config-display!` plus candidate/build
regions), and `resources/seon/schemas/seon.config.ai.backup.edn`. No held bytes
were changed or committed by this lane. This admission dependency prevents the
writer read-back probe and the corrected schema assertion from executing. A
new publication is orchestrator-owned; no bypass, worktree, lifecycle operation,
second JVM, or cold gate was attempted. **The suites are not claimed green.**

### Files and proof owed

This continuation changed the landing note plus:

- `resources/seon/schemas/seon.db.edn`, `seon.db.write.attempt.edn`,
  `seon.db.write.edn`, `seon.error.edn`, `seon.schema.edn` in that directory;
- `src/seon/db.clj`, `src/seon/error.clj`, `src/seon/error/refusal.clj`,
  `src/seon/instrument.clj`, `src/seon/schema.clj`,
  `src/seon/schema/internal.cljc`, `src/seon/sci/admit.clj`,
  `src/seon/sci/kernel.clj`;
- `test/seon/error_test.clj`, `test/seon/instrument_test.clj`,
  `test/seon/schema_test.clj`.

The foreign agent-surface regression in schema-test landed separately before
this lane's path-limited commit; it was preserved. The final test-only slice
adds the explicit read-result assertion and corrects the diagnostic observer
name. The preceding RESET NEEDED statement applies without additions.

The orchestrator still owes the cold command already listed above, including
all these paths and the earlier `src/seon/cluster/status.clj` and
`src/seon/sci/eval.clj` changes, with namespaces `seon.error-test`,
`seon.instrument-test`, `seon.schema-test`, `seon.db-test`,
`seon.cluster-test`, and `seon.sci.eval-test`, then platform/reset-boundary proof.
First restore snapshot admission and run the writer read-back assertion; its
actual refusal or successful row is the next decision input.

| Evidence | Bytes | SHA-256 |
|---|---:|---|
| `raw-fast.log` | 180,239 | `4b9e45363a69868caab2f43e2e9e985fe02252865e611bfff634a2d0f1ae7160` |
| `raw-fast-2.log` | 142,045 | `368fe1e2e371ed258444f0981b79df3f3fe4d0900b906a705ccc3bc4bc0723ae` |
| `raw-fast-3.log` | 6,129 | `15540bf1cc009e4266f8d3e6a0d9cb2b22decaca9f60073584fe74d373950334` |
| `raw-load-2.log` | 205 | `579c3de9fb226ffa47e63f1160596a1fe89089af30718e0a8855f4a369c28a18` |
| `kernel-load.log` | 205 | `579c3de9fb226ffa47e63f1160596a1fe89089af30718e0a8855f4a369c28a18` |
| `kernel-post-load.log` | 205 | `579c3de9fb226ffa47e63f1160596a1fe89089af30718e0a8855f4a369c28a18` |

## Fresh-overlay continuation (2026-09-20)

Read the error-conversion PRD end to end again. The accepted fresh overlay
admits the six requested namespaces. The baseline snapshot at
`78a1cd47e9044be008555b70afbde59d95037568` completed **272 tests / 5,109
assertions / 34 failures / 31 errors** (`tmp/error-family-resume-fast-1.log`).

| Namespace | Tests | Failures | Errors |
|---|---:|---:|---:|
| `seon.error-test` | 45 | 0 | 0 |
| `seon.instrument-test` | 44 | 3 | 0 |
| `seon.schema-test` | 34 | 0 | 0 |
| `seon.db-test` | 63 | 4 | 23 |
| `seon.cluster-test` | 12 | 2 | 0 |
| `seon.sci.eval-test` | 74 | 25 | 8 |

The committed writer read-back probe passes: the occurrence contains the
projected attempt with its original request identity and observed basis;
decoding yields the actual submitted transaction, and recording leaves the
target datoms unchanged. The earlier missing-occurrence result is not
reproduced. The corrected `pulled-selector-refusal` observer assertion passes,
as do both canonical reference-grammar tests and render-contract coherence.

The three instrumentation failures name only the newly landed
`:seon.source/test-evidence-error`. Its declaration is in the snapshot's
`seon.source.edn`, not a foreign draft. The pass-through unions now include it
in `src/seon/error.clj`, `src/seon/error/refusal.clj`,
`src/seon/instrument.clj`, `src/seon/sci/admit.clj`,
`src/seon/sci/kernel.clj`, and `resources/seon/schemas/seon.db.edn`.
The existing manifest regression is the before-change proof; candidate fast
verification follows below. The required namespace load passed before this
slice. No stored attributes change: **RESET NEEDED: no additions** to the
earlier batch.

### Reporter and producer evidence in the fresh snapshot

The database suite reproduces the existing noncanonical-generator reporter
defect at `src/seon/fn/schema_shape.clj:130`. The compile/inverse owner is
`src/seon/schema.clj`: `compilable-form` resolves a named `:gen/gen` into an
opaque Generator, and `canonical-definition` previously tried to serialize
its implementation. Malli consumes the supplied object directly
(`reference-code/malli/src/malli/generator.cljc:473`); test.check declares it
as a record and treats it as opaque
(`reference-code/test.check/src/main/clojure/clojure/test/check/generators.cljc:28`).
The candidate carries the authored symbol in that object's metadata and reads
it back at the inverse boundary. It adds no registry or global lookup to the
reporter. The armed canonical-fixture regression checks both round-trip
definition equality and the original input contract refusal, including its
function, check, and explanations. This extends the existing open
[projection/refusal issue](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md).

`seon.db/projection-fallback` also returned a marker-only map despite its base
output contract. Its candidate now returns the already-declared transient
`:seon.schema/validation-refusal`, with the missing projection's key and the
requesting operation as submitted data. `dependency-error` recognizes that
specific facet member and preserves it. The unbound-read regression validates
through a validator captured before removing the supplied projection; its
assertion cannot accidentally acquire the missing world. SCI's corresponding
regression checks the same raw facet and exact requesting operation.

Stale contract-marker assertions in the database, cluster and SCI tests now
assert the declared contract/arity/kernel facet and retain their operation,
path, outcome, or unchanged-state evidence. The refusal-preservation fixture
constructs a complete declared agent facet. Two independently created
declarations-read observations are compared without their event timestamps;
the new timestamp itself must still be an instant. The cluster convergence
assertion now retains desired and actually read rows for the next probe.

The source-facet slice landed as `ace176c50`; the five-namespace load passed
before and after it. Reporter, projection-producer and assertion candidates
are separate pending slices at this point in the note. No additional stored
attributes or type changes are proposed by those candidates.

### Acquisition model decision — production edit held at the decision

The fresh canonical fixture verifies that
`one-unloadable-row-cannot-prevent-cold-acquisition` and
`non-evaluable-agent-source-reports-its-jvm-fallback` stop at
`seon.sci.eval/acquisition-refusal`: its diagnostic omits the required base
members. Adding those alone cannot fulfill the declared acquisition facet.
`resources/seon/schemas/seon.sci.eval.edn:285` requires
`:seon.sci.eval/requested-program` (64-character digest),
`:seon.sci.eval/acquisition-member`, and a complete owned observation.
The constructor at `src/seon/sci/eval.clj:1571` receives only `[row failure]`.
The three calls in `acquire-program!` carry no program digest. A row/source
hash would not identify the whole requested program.

There is already a whole-program digest owner:
`seon.test.runner/program-digest` derives it from a source seal plus changed
program facts. It can itself return a typed unavailable result; it is not
equivalent to hashing the failed row. The runner is held by the results-reuse
lane. Introducing a second digest algorithm or weakening the existing stored
facet would make a cross-owner decision here. This is the assignment's design
stop rule, not an approval requirement inferred from a skill.

Exactly three options, ordered by implementation cost (estimates):

1. **Make requested-program optional on the existing acquisition facet.**
   Guarantee: the failed member and complete observation are always present;
   a supplied program digest remains valid. Cost: roughly 1–2 hours for the
   breaking output-contract change, reader review and regression updates.
   Gives up: callers can no longer rely on a program identity in every
   acquisition error. This changes the existing schema's promise and needs an
   explicit ruling.
2. **Declare a separate row-acquisition facet — recommended.** Guarantee:
   row-level failures promise the actual observed member and failure evidence;
   the existing program-acquisition facet keeps all its required members.
   Occurrence ownership remains unchanged; no facet identity is minted.
   Cost: roughly 2–4 hours across the SCI declaration/producer, recorder
   admission, explicit pass-through contracts, and canonical regressions.
   Gives up: a row-only observation does not claim whole-program identity.
3. **Carry the authoritative program identity into acquisition.** Guarantee:
   every emitted acquisition facet satisfies the current required digest
   promise using the source-seal authority. Cost: roughly 4–8 hours to share
   that authority with SCI acquisition, carry its result through the three
   producer calls, and specify unsealed-program refusal; this crosses the
   held runner and publication owners. Gives up: acquisition cannot promise
   this facet until program identity acquisition succeeds.

No acquisition schema or producer change has been made for these options.
The independent reporter, projection and assertion fixes continue through
their pending fast verification before the lane stops.

### Fresh overlay verification after the reporter and producer repairs

The second six-suite run used HEAD `ace176c5088aeaeba785c4c20856f22e578aee7d`
plus the six selected dirty files named in its snapshot preamble. Exact fast
tally: **273 tests / 5,134 assertions / 10 failures / 30 errors**, exit 1.
Evidence: `tmp/error-family-resume-fast-2.log`. The first run was
272 / 5,109 / 34F / 31E. The new generator regression adds one test; the
changed assertions retain required evidence rather than accepting retired
markers.

| Namespace | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| seon.error-test | 45 | 0 | 0 |
| seon.instrument-test | 45 | 0 | 0 |
| seon.schema-test | 34 | 0 | 0 |
| seon.db-test | 63 | 0 | 22 |
| seon.cluster-test | 12 | 1 | 0 |
| seon.sci.eval-test | 74 | 9 | 8 |

The writer read-back and corrected schema assertion both pass again. The
generator round-trip/reporting regression and explicit facet manifest pass.
The missing-projection regressions pass at both the database and SCI
boundaries. The changed contract, arity, kernel, observation-timestamp and
complete-agent-fixture assertions all pass.

The cluster read-back probe falsifies a config metadata mismatch: **434 rows**
have different serialized `:seon.schema/form` bytes under the caller's
`*print-namespace-maps*` binding. One exact pair from the probe is:

```clojure
;; desired
"[:and #:seon.config{:display-label \"Jitter fraction\", :per-agent true, :dial true} :seon.ai.retry/jitter-fraction]"
;; stored
"[:and {:seon.config/display-label \"Jitter fraction\", :seon.config/per-agent true, :seon.config/dial true} :seon.ai.retry/jitter-fraction]"
```

The display attributes themselves match. `canonical-schema-rows` owns these
bytes and now binds namespace-map printing to false when serializing a form.
The existing canonical-fixture regression now requires no row changes under
both print settings. This is a serialization fix, not a new schema shape or a
relaxation of convergence. Its focused fast verification is recorded below.

Every remaining red in the second run is accounted for here. “Owned remaining”
means an unresolved cause, not permission to weaken the assertion. No broad
green claim follows from the three green namespaces.

| Test (namespace prefix omitted only within the named group) | F/E | Classification and observed boundary |
| --- | ---: | --- |
| db: a-write-naming-another-clusters-branch-is-refused-naming-both | 0/1 | Owned remaining: `src/seon/db.clj` foreign-connection producer returns incomplete error data; `transact!` output validation refuses it. |
| db: every-database-value-reader-answers-for-all-four-view-shapes | 0/1 | Owned remaining: `src/seon/db.clj` `database-value-identity` returns a marker-only refusal for an uncommitted view; output fits neither identity nor complete error. |
| db: unique-rejection-names-the-existing-owner-as-data | 0/1 | Owned remaining: `src/seon/db.clj` `rejected-value` still produces a marker-only native rejection; `transact-call` output refuses it. |
| db: every-public-read-preserves-an-upstream-database-error | 0/15 | Stale fixture plus remaining consumers: `test/seon/db_test.clj` constructs marker-only upstream data; `src/seon/db.clj` still uses its legacy private predicate. Converting only the assertion would lose the preservation guarantee. |
| db: non-temporal-reads-return-one-flat-error-before-datahike | 0/1 | Owned remaining: `src/seon/db.clj` `history` returns a marker-only non-temporal refusal rejected at output. |
| db: non-unique-writer-rejections-retain-their-datahike-data | 0/1 | Owned remaining: the same native `rejected-value` boundary as the uniqueness case. |
| db: transaction-wrappers-cannot-hide-a-classified-refusal | 0/2 | Stale fixture plus remaining producer: `test/seon/db_test.clj` supplies marker-only exception data; `src/seon/db.clj` `transact-call` cannot return it as a complete error. |
| cluster: schema-row-convergence-uses-the-stores-own-semantics | 1/0 | Owned serialization cause in `src/seon/schema.clj`, verified by desired/stored bytes above; fixed candidate in focused verification. |
| sci.eval: a-refusal-keeps-its-own-kind-at-both-entrances | 2/0 | Stale assertion plus owned producers: reader-count in `src/seon/sci/eval.clj:515` and missing-installer in `src/seon/sci/kernel.clj:174` are incomplete. Kernel correctly does not preserve an undeclared marker-only map as an error. |
| sci.eval: one-unloadable-row-cannot-prevent-cold-acquisition | 0/1 | Acquisition design decision above; `src/seon/sci/eval.clj` `acquisition-refusal` calls the diagnostic without base members and cannot supply the required whole-program digest from its arguments. |
| sci.eval: base-context-injections-have-program-rows | 1/0 | Program population boundary: `src/seon/fn.clj` `desired-rows` omits 39 public `clojure.test` names derived by `src/seon/program.cljc` from `seon.sci.binding.edn`. Set difference is verified; the indexing cause is not yet isolated. |
| sci.eval: unloadable-host-namespace-carries-the-cause-message-and-location | 4/2 | Owned producer: `src/seon/sci/eval.clj:1216` calls the diagnostic without base members. Its contract refusal replaces the loader evidence, then location assertions encounter nil. |
| sci.eval: a-foreign-armed-context-is-refused-as-a-value | 0/1 | Owned boundary: `src/seon/sci/kernel.clj:307` throws from arm acquisition before `src/seon/sci/eval.clj` enters the evaluation body's catch. |
| sci.eval: non-evaluable-agent-source-reports-its-jvm-fallback | 0/1 | Same acquisition design decision and producer as the unloadable-row test. |
| sci.eval: overrides-follow-current-admission-and-survive-lost-file-coordinates | 0/1 | Owned investigation remains: fixture transaction in `test/seon/sci/eval_test.clj` is refused by the final writer for `seon.id/digest`, path `[3251 :seon.schema.admission/source]`. Observed value is `:core`; the reason it fails the candidate shape is not yet isolated. No assertion changed. |
| sci.eval: a-selected-render-inherits-the-live-arm-or-owns-one-when-unarmed | 0/1 | Verified foreign held boundary: `src/seon/render.clj:916` calls the diagnostic without base members. The snapshot uses HEAD, not that lane's drafts. |
| sci.eval: public-walk-is-callable-through-an-agent-sci-eval | 2/0 | Foreign held render-plan boundary (`src/seon/render/walk.clj` and `src/seon/render.clj`): plan-reuse assertion is false and through-SCI allocation is 6,236,661,904 bytes against the existing 1 GiB bound. This run does not isolate whether the adjacent test's diagnostic defect causes the performance failure. |
| sci.eval: a-configless-database-refuses-contract-installation | 0/1 | Owned producer/consumer: `src/seon/sci/eval.clj` `database-effective-config` returns marker-only data and `instrumentation-config` passes it to `seon.config/result-caps`, whose effective-config input refuses it. |

The reporter repair changes the 22 database errors from a secondary
noncanonical-generator exception into the original contract refusal with
function, member, expected shape and caller. It does not itself repair the
incomplete database producers listed above.

**RESET NEEDED:** no additional stored attribute or type changes in this
resume. The earlier reset batch remains owed; the lane did not touch default.

The render lane subsequently landed `f5716e841` while the focused run was
already using its `0d5088438` snapshot. The foreign rows above describe the
measured older snapshot; they are not claims about that later render commit.

### Cold proof owed after the acquisition decision

No cold gate, platform gate, live adoption or lifecycle command was run by
this lane. The orchestrator owes the following accumulated path-limited cold
command, followed by its platform and reset-boundary live proof:

```sh
bin/test --paths \
  docs/prds/steward-platform/research/error-family-1a-2026-09-19.md \
  docs/prds/steward-platform/research/error-kind-retirement-inventory-2026-09-19.md \
  resources/seon/schemas/seon.db.edn \
  resources/seon/schemas/seon.db.write.edn \
  resources/seon/schemas/seon.db.write.attempt.edn \
  resources/seon/schemas/seon.error.edn \
  resources/seon/schemas/seon.instrument.edn \
  resources/seon/schemas/seon.schema.edn \
  src/seon/db.clj src/seon/error.clj src/seon/error/refusal.clj \
  src/seon/instrument.clj src/seon/schema.clj src/seon/schema/internal.cljc \
  src/seon/cluster/status.clj src/seon/sci/admit.clj src/seon/sci/eval.clj \
  src/seon/sci/kernel.clj \
  test/seon/error_test.clj test/seon/instrument_test.clj \
  test/seon/schema_test.clj test/seon/db_test.clj \
  test/seon/cluster_test.clj test/seon/sci/eval_test.clj \
  -- seon.error-test seon.instrument-test seon.schema-test \
  seon.db-test seon.cluster-test seon.sci.eval-test
```

Files changed in this resume: `src/seon/error.clj`,
`src/seon/error/refusal.clj`, `src/seon/instrument.clj`,
`src/seon/sci/admit.clj`, `src/seon/sci/kernel.clj`,
`resources/seon/schemas/seon.db.edn`, `src/seon/schema.clj`,
`src/seon/db.clj`, `test/seon/instrument_test.clj`,
`test/seon/db_test.clj`, `test/seon/cluster_test.clj`,
`test/seon/sci/eval_test.clj`, and this landing note. The inventory and other
paths in the cold command were changed in earlier accepted slices of this
lane. Foreign checkout edits were neither changed nor committed.

### Focused canonical serialization verification

The final focused `--paths` run selected `seon.schema-test seon.cluster-test`
on HEAD `0d50884382ce1fa87f0c05ba0ddbca14d5258369` plus this lane's selected
changes. Exact tally: **46 tests / 3,635 assertions / 0 failures / 0 errors**,
exit 0 (`tmp/error-family-resume-convergence-fast.log`). The convergence
regression passed with both `*print-namespace-maps*` settings. A thread sample
from its one JVM (`tmp/error-family-resume-convergence-threads.log`) confirmed
that the initial wait was canonical fixture construction, not a second JVM
or a blocked schema-test body.

This resolves the one cluster failure in the preceding six-suite snapshot.
There is no newer combined six-suite tally: 273 / 5,134 / 10F / 30E remains
the last complete combined measurement, with the focused 46 / 3,635 / 0 / 0
as subsequent evidence. Acquisition remains at the three-option decision
above. No pending producer is made green by accepting incomplete error data.

### Landing checkpoints for this resume

- `ace176c50`: explicit pass-through unions include the newly landed source
  test-evidence facet. The manifest is green in the second combined run.
- `8704e5ed5`: generator symbols survive compilation for canonical refusal
  evidence; stored schema form bytes are independent of namespace-map
  printing; canonical and armed regressions pass as measured above.
  Namespace loads before and after this commit both returned `:loads`
  (`tmp/error-family-resume-final-load-1.log` and `-2.log`).
- `494c40715`: the producer/assertion slice contains `src/seon/db.clj`,
  `test/seon/db_test.clj`, and `test/seon/sci/eval_test.clj`: complete raw
  missing-projection refusals, specific raw-facet propagation, and the
  successful declared-facet assertions measured by the second combined run.
  It adds no schema or stored attribute. The required namespace-load command
  returned `:loads` after this commit as well
  (`tmp/error-family-resume-final-load-3.log`).

The lane stops at the **acquisition model decision**, with the exact three
priced options above and recommendation 2. The required whole-program digest
cannot truthfully be fabricated from a failed row. Outstanding red tests and
the cold command remain explicit; this is not a six-suite green landing.
All fast and load JVMs were awaited serially. The final fast snapshot root
was removed by its launcher; no worktree was created. Only foreign edits
remain outside this final documentation update.

## Row acquisition ruling implemented, 2026-09-20

The owner selected option 2: a failed row observes its member and failure
evidence without claiming the complete program digest. The new
`:seon.sci.eval/row-acquisition-error` requires the base, `row-member`, and
the existing `acquisition-observation` evidence component. `row-member`
accepts namespace/function/test symbols and schema keywords. It is a new
attribute because the existing `acquisition-member` promises a qualified
symbol or keyword and cannot represent a namespace such as `seon.id`.
The existing program acquisition facet and its required 64-character digest
are unchanged. Occurrences own both facets; no identity was added.

The producer is `seon.sci.eval/acquisition-refusal`; the namespace loader
uses the same row facet for its observed namespace and preserves the actual
loader cause/location. Exact pass-through contracts include the new facet
in error/refusal/latest-fact, instrumentation, SCI admission/kernel, the
database result union, and `seon.render.value`'s existing pass-through
declarations. The latter file was clean and only its contracts changed.
The canonical fixture's `refusal-data` helper still recognized retired kind
maps; it now recognizes the base's three required members directly, so it
does not misreport a returned complete error as a committed transaction.

The new armed canonical regression reads both an actual function row and an
actual namespace row, calls the producer, then records through
`error/recording` and `transacted!` and reads the complete occurrence. It
verifies the row facet before/after storage and falsifies the whole-program
facet without fabricating a digest. The cold acquisition regression also
queries the actual occurrence's observed member, rather than a retired root
kind.

Before implementation, request `75e0bb5b240d` durably recorded **46 tests /
364 assertions / 2 failures / 1 error**, all in the new regression
(`tmp/error-family-row-before.log`). The first six-suite candidate executed
**276 tests / 6,030 assertions / 16 failures / 29 errors**
(`tmp/error-family-row-after.log`). That request did not record: the armed
`src/seon/blob.clj` `with-publication!` boundary declared zero facets while
returning `:seon.db.write/validation-refusal` plus
`:seon.test/execution-error`. Its nested evidence measured 16,407 against
16,384 bytes. This is the same publication/recorder boundary measured in
the bridge step-1 note; no cap was raised. It is an execution tally, never
a durable verdict.

### RESET NEEDED

No existing attribute type changes in this slice. The additive stored
attribute is exactly `:seon.sci.eval/row-member`; its evidence uses existing
`:seon.sci.eval/acquisition-observation`. No reset, lifecycle operation,
default adoption, or worktree was performed.

The corrected focused snapshot selected `seon.error-test seon.sci.eval-test`
with all row-facet, namespace-loader, and fixture-helper changes. Execution
completed **120 tests / 759 assertions / 6 failures / 4 errors**
(`tmp/error-family-row-corrected.log`). All 46 error tests passed, including
both stored row-member cases. SCI cold row acquisition, JVM fallback, and
namespace-loader evidence passed. Remaining events: configless installation
(1E), foreign armed context (1E), retired entrance-kind assertions (2F),
selected render (1E, `seon.render/present-output` receives a map where it
declares a string), storable declaration assertion (1F), program injection
rows (1F), override retraction (1E), and public walk allocation/plan reuse
(2F). These are the next classification inputs, not attributed causes merely
from the test names. The render diagnostic prints the supplied projection
in a nested exception; subsequent log inspection is bounded to event lines.

Request `dd9806c1ae02` finished with exit 1. Completion recording reached
the same `seon.blob/with-publication!` undeclared-facet refusal, so the focused
tally above is also execution evidence. Its snapshot tested foreign callers
at HEAD bytes, including `src/seon/cluster.clj`, `src/seon/schema/edn.clj`,
`src/seon/schema/internal.cljc`, `src/seon/fn.clj`, and the schema datahike/EDN
tests. Their checkout changes were preserved. The required namespace load
returned `:loads` before the row-facet commit
(`tmp/error-family-row-load-before.log`).

### Database propagation and SCI boundary follow-up

`2a59e5e11` landed the row facet; the post-commit load also returned `:loads`
(`tmp/error-family-row-load-after.log`). The next DB-only snapshot did not
execute tests: a test still dereferenced the retired private DB predicate.
The caller was converted in the same slice. Its positive observation is
the canonical provider's `:seon.ai.model/provider-entity` schema key, not
the old comment's claim that the provider has no entity schema.

The following six-suite snapshot executed **276 tests / 6,088 assertions /
9 failures / 10 errors** (`tmp/error-family-boundaries.log`). Error,
instrumentation, schema, and cluster namespaces had zero failure/error
events. All 18 upstream DB propagation assertions passed after dissolving
the private `db/error-value?` into the base's three required-member checks
at its 50 consumers. No lookup, projection acquisition, or replacement
general predicate was added. Remaining old DB producers are explicitly
incomplete; this slice does not make their marker-only values valid.

SCI's foreign-arm refusal, preserved reader/installer facets, and the
noncanonical declaration spelling now pass. The pre-arm refusal uses the
same failure handler as the armed body; an armed body's failure is still
admitted before disarm. The outer catch handles only failed acquisition,
not a second attempt to report a body failure. The existing reader count
schema now composes the base and its observed integer count, and all
pass-through unions name it explicitly. No source projection is fabricated
for a failure to obtain exactly one event.

The configless regression still had one stale assertion: registration
evidence names the absent recorder in its required
`:seon.instrument/registration-observation`, not an optional expected-key.
Its exact-member assertion was updated accordingly. Its config producer
now returns the existing complete config facet. The provider schema
assertion and this registration-member assertion were corrected from the
actual values measured in this run; their next execution is still owed.

`src/seon/render.clj` is clean/released under the latest scope. Read-only
inspection isolated the selected-render failure at its private
`present-output`: an identity function with an input that excluded the
typed unknown returned by `raw-output`. The redundant function and all
three local callers are removed together; the existing error output
contracts remain the authority. This correction and the typed-unknown
assertion await the next changed-input run. The render-owner skill and UI
architecture were read end to end before this edit.
