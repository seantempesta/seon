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
