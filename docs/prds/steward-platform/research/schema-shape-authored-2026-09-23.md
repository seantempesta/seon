---
type: research
status: complete
created: 2026-09-23
tags: [schema-shape, class/stored-derived, wave/publication-velocity]
---

# Authored schema shapes — implementation and measurements

Read end to end: the owning issue
`docs/seon/issues/the-index-cache-retains-4gb-of-expanded-schema-shape-forms.md`,
`src/seon/fn/schema_shape.clj`, and
`resources/seon/schemas/seon.schema.shape.edn`; also read AGENTS.md §2.2
and “SECONDS, NOT MINUTES” and the requested reader ranges.

The owner accepted documentation commits `7b481dc72` and `a0437899f`,
released the clean instrumentation/function hunks, and chose named-reference
matching for supplied defaults. The original protected-hunk stop and three
semantic options are preserved in those commits; neither is a current blocker.

### Dependency ledger

- Malli `schema` creates a pointer for registry references, while `form`
  preserves its authored spelling:
  `reference-code/malli/src/malli/core.cljc:2550–2579`.
- Malli `deref-all` resolves top-level references; `deref-recursive`
  resolves schema pointers throughout the compiled schema but explicitly
  does not traverse `:ref` recursion:
  `reference-code/malli/src/malli/core.cljc:2818–2846`.
- Malli retains validators on the compiled schema itself:
  `reference-code/malli/src/malli/core.cljc:2625–2632`. An additional
  plain-validator cache would duplicate that mechanism.
- Seon's database carries its immutable projection in metadata:
  `src/seon/db.clj:249–269`; `carried-projection` reads it at `:1219`.
- Existing reference discovery uses Malli's walker and reference protocol,
  stopping at canonical references: `src/seon/program.cljc:552–570`.
- Datahike's node cache is count-bounded:
  `reference-code/datahike/src/datahike/index/persistent_set.cljc:463–466`.


### Measured baseline and live semantic probe

Fresh scratch publication: commit
`6ab08cc5-9928-5ed8-bae0-56b27f5582cd`, program digest
`3b0958695672ad886f1dd726b303125766d209e62bdd14ffc43abeb36179f571`.
The operator's complete initialization took 243,342 ms. The subsequent
`start schema-shape` completed; this proof exercised a **new fork**, not a
hot-reloaded Var or development adoption. The publication included the
working-tree test edits present during indexing; those foreign files were
not edited or committed by this assignment. The source is the canonical
published population, not a synthetic roster.

MCP JVM evaluation on root `/Users/sean/src/seon/tmp/schema-shape-root`,
cluster `schema-shape`, returned this complete numeric value in 454 ms:

```clojure
{:rows 4372
 :characters 72877056
 :utf8-bytes 72877170
 :maximum-characters 1563264}
```

Exact measurement form (also the repeatable after-measurement):

```clojure
(let [database (seon.db/db (seon.operator/connection "schema-shape"))]
  (reduce
   (fn [result datom]
     (let [value (:v datom)
           characters (count value)
           bytes (alength (.getBytes ^String value
                                    java.nio.charset.StandardCharsets/UTF_8))]
       (-> result
           (update :rows inc)
           (update :characters + characters)
           (update :utf8-bytes + bytes)
           (update :maximum-characters max characters))))
   {:rows 0 :characters 0 :utf8-bytes 0 :maximum-characters 0}
   (seon.db/datoms database :aevt :seon.schema.shape/form)))
```

## Accepted implementation (2026-09-23)

The owner accepted option 1: supplied defaults match the declared schema
NAME only. Inline structural copies and differently named aliases are not
requests for a default. The earlier documentation commits preserve the investigation and ruling.

The encoder now stores keyword references and vector heads. Ordered
entry/child rows store the operands, and properties have their existing
attribute. Local recursive registry forms remain one authored scope with no
redundant child rows. `expand-schema-form` and `expand-entry-form` are deleted.
Fingerprinting includes the authored definition and recursively derived
reference fingerprints; unrelated definitions do not move identity.

`prepare-forms` derives the reference fingerprints once for publication and
carries them in the immutable forms value. It walks Malli's retained compiled
references, without recompiling each referenced definition. There is no new
cache. Readers use the compiled registry carried by the database projection;
argument validators remain part of the existing call-preparation plan.
Named map entry facts derive into the existing preparation snapshot.
Instrumentation consumes the fingerprint returned by the owning normalizer.
The issue detector resolves named schemas through that same carried registry.

The first authored scratch publication exposed repeated definition traversal
in `reference-fingerprints` for each root. A thread sample of its one JVM
showed `schema/direct-references` beneath `shape-row` and `fn/add-contract-facts`.
The batch derivation and compiled-reference walk remove that repetition.
The canonical encoding regression fell from 6.286 seconds to 2.25 seconds;
the final test uses the ordinary five-second bound, with no extended allowance.

### Isolation and verification boundaries

Fast iterations used HEAD plus the explicitly selected paths only. The
snapshot reported foreign dirty callers in `src/seon/cluster.clj`,
`src/seon/sci/eval.clj`, `src/seon/test/runner.clj`,
`test/seon/test/runner_test.clj`, and `test/seon/test/selection_test.clj`
at different iterations; their HEAD bytes were tested. None of their edits
was changed or included. `src/seon/fn.clj` became clean after the other lane
landed `aa61d3285`; an immediate status check preceded the single owned
batch-preparation line. Instrumentation has only the two authorized hunks.
No other lane's session was operated. A later recorder refusal required the
explicitly authorized worktree described below.

The fast fixture's published graph predates this representation change. The
size regression therefore encodes its complete real schema and function
population through the current writer. A separate regression writes and
reads actual new shape rows through the canonical fixture and armed contracts.
Live scratch datom measurements are the independent stored-size evidence.
Cold/platform gates remain the orchestrator's responsibility.

RESET NEEDED: existing expanded shape rows and fingerprints must be rebuilt.
No migration and no operation on `default` were performed.

### Canonical contract audit

`inline-default-contracts` traversed the canonical compiled function contracts,
including test declarations, against the actual supplied-default rows. It
found zero inline copies, but 26 formerly equivalent slots in 23 functions
using different names. The 21 database functions (23 slots) use `:seon.db/db`
instead of the supplied schema `:seon.db/database-value`. The two message
functions (three slots) use `:my.message/to` instead of `:seon.agent/id`;
excluding inferred self-recipients is intentional. This proves former
eligibility, not caller intent. None is owned by this assignment.

| Function | Declaration | Authored name |
|---|---|---|
| `seon.agent/archived?` | `src/seon/agent.clj:33` | `:seon.db/db` |
| `seon.agent/effective-settings` | `src/seon/agent.clj:117` | `:seon.db/db` |
| `seon.agent/identity` | `src/seon/agent.clj:11` | `:seon.db/db` |
| `seon.agent/open?` | `src/seon/agent.clj:47` | `:seon.db/db` |
| `seon.agent/settings` | `src/seon/agent.clj:75` | `:seon.db/db` |
| `seon.ai/agent-setting-attributes` | `src/seon/ai.clj:325` | `:seon.db/db` |
| `seon.bootstrap/beyond-closure-budget` | `src/seon/bootstrap.clj:379` | `:seon.db/db` |
| `seon.bootstrap/help-value` | `src/seon/bootstrap.clj:22` | `:seon.db/db` |
| `seon.bootstrap/situation` | `src/seon/bootstrap.clj:103` | `:seon.db/db` |
| `seon.cluster.message/decline` | `src/seon/cluster/message.clj:692` | `:my.message/to` |
| `seon.cluster.message/send` | `src/seon/cluster/message.clj:627` | `:my.message/to` |
| `seon.eval/of-agent` | `src/seon/eval.clj:9` | `:seon.db/db` |
| `seon.plan/blocked` | `src/seon/plan.clj:552` | `:seon.db/db` |
| `seon.plan/current` | `src/seon/plan.clj:536` | `:seon.db/db` |
| `seon.plan/ready` | `src/seon/plan.clj:574` | `:seon.db/db` |
| `seon.plan/ready-subjects` | `src/seon/plan.clj:586` | `:seon.db/db` |
| `seon.plan/steps` | `src/seon/plan.clj:563` | `:seon.db/db` |
| `seon.render.data/entity-observation` | `src/seon/render/data.clj:204` | `:seon.db/db` |
| `seon.render.value/transacted` | `src/seon/render/value.clj:30` | `:seon.db/db` |
| `seon.repl/frame` | `src/seon/repl.clj:50` | `:seon.db/db` |
| `seon.sci.eval/directory-value` | `src/seon/sci/eval.clj:1565` | `:seon.db/db` |
| `seon.sci.eval/documentation-value` | `src/seon/sci/eval.clj:1597` | `:seon.db/db` |
| `seon.turn/turns-left` | `src/seon/turn.clj:2742` | `:seon.db/db` |

Filed together in
[default alias eligibility](../../../seon/issues/default-eligibility-matched-differently-named-schema-aliases.md).
The platform tier was not run; its canonical contracted helpers were included
in this audit. Two separately identified program tests retain obsolete
expanded-string expectations:
[stale program regressions](../../../seon/issues/program-shape-regressions-still-expect-expanded-subtrees.md).

### Stored after measurement and live readers

Fresh authored publication: commit
`6ab09443-7b2d-55ed-91e7-39c3e61726d8`, digest
`866179978d163678cdb744efdcd46b72435ae25af66d87bdb8713d6ef0d4e503`.
A new `schema-shape` fork on the same owned scratch root returned the
following actual datom measurement in **121 ms**:

| Stored shape forms | Before | After |
|---|---:|---:|
| Rows | 4,372 | 5,139 |
| Characters | 72,877,056 | 103,406 |
| UTF-8 bytes | 72,877,170 | 103,406 |
| Largest form, characters | 1,563,264 | 61 |

That is a 99.858% reduction in shape-form bytes. This measures stored strings,
not a post-reset heap claim. The after publication contains the pre-batch
encoder; the subsequent batch optimization preserves row identity/contents.
The final fast regression separately measures the complete current encoder:
6,610 unique shapes, 112,211 bytes, maximum 446 bytes (whole contract roots
included in that encoder check, unlike the publication's argument/return roots).

The MCP JVM reader proof reconstructed `:seon.db/connection` from its stored
row, resolved it through the carried registry and accepted the actual scratch
connection. Call preparation admitted five defaults with zero refusals,
derived 130 named map entries and 470 prepared symbols, and returned the
expected `[0 0 :seon.db/connection]` for the existing connection probe.
The named-map reader was hot-reloaded after correcting `m/entries` to
`m/children`; Malli documents the latter's triples at
`reference-code/malli/src/malli/core.cljc:2595`. The combined audit/snapshot
probe completed in 300 ms, and the stored-reader/plan probe in 167 ms.

### Live fixture boundary

Juniper installation was attempted through the existing live installer on
this scratch cluster only. It exceeded the 10-second MCP evaluation bound.
The agent row exists, but `:example/order` was not installed, so this is
**not a successful Juniper seed or platform proof**. The log reports the
already-filed `seon.cluster.reply/sources` map-versus-vector contract refusal
(signature `73e324b026fd14e51cc9adc1d1bc4a6115ad2356c549face29b3ddb24b068df0`),
then `run transition refused: agent-already-running`. Its writer log also
printed a very large complete projection. These foreign boundaries are
recorded in the existing reply-contract and raw-writer-log issues; no foreign
code was edited. Stored-size and direct reader measurements completed before
this attempted seed and do not depend on it.

The root was shut down with `bin/seon --root tmp/schema-shape-root down`;
the operator reported all recorded JVMs stopped and the store flock free.

### Final verification isolation

After the successful six-test run `ed4a3f4a8e56` (21 assertions), the added
named-map regression initially expected a connection default in the old
published fixture. That base refuses the connection supplier; the fresh
scratch publication admitted it. The regression now asserts the canonical
`[:my.agent/settings-request :seon.agent/id]` entry, which the fixture does
admit. This is distinct from the live connection proof above.

The next run `2aa8a7aee206` was refused before execution because the shared
recorder saw `:seon.config/compiled` referencing the concurrently removed
`:seon.config/applied-manifest-digest` resource declaration. The foreign dirty
files were `resources/seon/schemas/seon.config.edn`, `src/seon/config.clj`,
and `src/seon/schema/edn.clj`; none was edited by this lane.

Per the owner's explicit isolation instruction, created
`tmp/schema-shape-wt` at HEAD `2d6511b82`, linked `reference-code`, and applied
only this slice's source/resource/test changes. The first worktree attempt
correctly refused because its recording authority had no `current-src`.
Copied the existing immutable exported canonical store into the worktree's
own `data/store` using filesystem copy-on-write; the base publication itself
was not rebuilt or changed. Linked the existing published-base catalog for
selection. The resulting fast run records into this disposable isolated
store, not `default`. No cold gate or baseline preparation was run.

The copied store was reidentified through
`seon.cluster.export/reidentify!`, the same seam used by
`seon.test-support/create-base`; opening an un-reidentified copy correctly
refused with a store identity mismatch. This changed only the disposable copy.

Final fast run **`d01dd44188d4`**: **7 executed, 0 unchanged, 24 assertions,
0 failures, 0 errors**. Program digest
`85952d271e32c24912c56fb95d83f56bb6031994beac7ace01721e54bd6a9444`;
input digest `6618c7b3d3edc7e7e82a1f42abf844024b08ca4cc352fb86e372c9bf3c8e5ceb`.
All tests used the ordinary five-second bound; canonical encoding took
0.680 seconds and the database reconstruction regression 1.772 seconds.

Exact invocation (from the isolated worktree):

```sh
bin/test-fast --paths \
  src/seon/fn/schema_shape.clj src/seon/fn.clj \
  src/seon/call_preparation.clj src/seon/instrument.clj \
  src/seon/issue/detect.clj \
  resources/seon/schemas/seon.schema.shape.edn \
  resources/seon/schemas/seon.call-preparation.edn \
  test/seon/fn/schema_shape_test.clj -- seon.fn.schema-shape-test
```

The implementation slice touches those eight paths, this landing note, the
new alias-eligibility and stale-program-regression issues, and observation
updates to the existing reply-contract and raw-writer-log issues. The extra
production reader is `src/seon/issue/detect.clj`; without its registry lookup,
named schema roots would incorrectly appear to declare no attributes.

The required namespace-load command is the pre-commit and post-commit check:

```sh
clojure -M -e "(require 'seon.fn.schema-shape 'seon.call-preparation)"
```

The isolated worktree, its recording-store copy, and the scratch root are
removed after verification. The shared published base and foreign edits are
preserved. RESET NEEDED for the implementation commit containing this note; cold/platform
proof is owed by the orchestrator. The pre-commit namespace-load command exited zero.
