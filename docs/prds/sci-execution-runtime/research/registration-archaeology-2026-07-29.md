---
type: research
status: active
tags: [research, program-graph, sci, schema, testing]
---

# Function, schema, and test registration archaeology

## Verdict

Seon has implemented program registration six times across five platform eras.
The recurring defect is not a missing declaration-specific branch. Build
indexing, runtime evaluation, and database replacement repeatedly acquired
separate interpretations of the same declaration. Each rewrite then lost one
already-paid-for invariant: tests vanished, schema source was confused with
its evaluated value, body-only redefinitions stayed stale, optional facts were
not retracted, or runtime state advanced before the database commit.

The surviving contract is one data transformation:

> One reader event plus one producer-admitted value becomes one canonical
> function, schema, or test row. One exact replacement transaction commits it.
> Runtime materialization consumes only that transaction report's `db-after`
> and remains scoped to its cluster.

Build and runtime retain one deliberate policy difference. Source indexing
records every `defn` and `defn-` because a complete call graph must include
private and uncontracted helpers. Agent runtime publication admits a durable
function only when its complete Malli contract is present and valid. That is
producer admission, not permission for two row formats.

## Dependency ledger

| dependency or mechanism | selected revision | maintained source | boundary established |
|---|---|---|---|
| SCI | submodule `8fac6e88f32d` | `reference-code/sci/src/sci/core.cljc`, `reference-code/sci/doc/interrupt.md` | one ctx/fork and post-commit interpreted installation |
| Datahike | submodule `19f5cdd950dc` | `reference-code/datahike/src/datahike/db/transaction.cljc`, `writing.cljc`, `writer.cljc` | identity upsert, exact retraction, one serial transaction, `db-after` |
| Malli | `0.20.0`, vendored revision `80138076960e` | `reference-code/malli/src/malli/registry.cljc`, `malli/core.cljc` | registries can be supplied explicitly; Malli's default registry is process-global |
| fresh reader | current tree | `src/seon/sci/reader.cljc` | literal form-head classification and exact source spans |
| pure schema projection | current tree | `src/seon/schema.cljc` | complete projection derivation from committed rows without database mutation |

## The six mechanisms

| generation | build/index mechanism | runtime mechanism | result |
|---|---|---|---|
| Original JVM graph, `b69a310de` through `d7cd70bdd` | clj-kondo functions/calls plus Edamame schema scanning | post-nREPL incremental clj-kondo only | runtime schemas and tests were absent; errors were swallowed; publication was non-atomic |
| Node/CLJS self-host pod, `f5d678c22` through `fa327214e` | Shadow compiler environment, loaded vars, schema registry, test preload | analyzer and registry snapshot/diff around eval | unified identities, but platform state became the oracle and silently omitted declarations |
| Database authority plus Bun children, `86db045d6` through `a1a419a77` | compiled rows and initialization pages | terminal publication at one database value; cold child acquisition at a basis | established basis pinning, current-state materialization, and restart without receipt replay |
| Transitional JVM SCI host, `b7808e357` through `a50845bd4` | committed corpus/base registry | another row builder from form, returned Var metadata, and registry delta | functions, namespaces, and schemas were copied; tests disappeared again |
| JVM initialization-page producer, `1867980cc` through `2ef6f0bbd` | Clojure reader exact source, loaded Vars, live schema registry, syntax tests | old evaluator remained | mixed source truth with loaded runtime state and retained separate build/runtime admission |
| Fresh JVM SCI, `90338c62a` through `7340e2635` | one reader feeding `seon.fn` | reader event admitted, committed with terminal receipt, installed from `db-after` | correct structural direction, but three owners restate row semantics; schemas are raw syntax and process-global; tests are inert |

The primary historical source anchors are
`f5d678c22:src/seon/graph/scanner.clj:103-368`,
`f5d678c22:src/seon/graph/extract.clj:52-575`,
`f5d678c22:src/seon/graph/ingest.clj:440-690`,
`333b21b574cc:src/seon/eval.cljs:1900-2602`,
`b7808e357:src/seon/host/record.clj:92-147,243-262,373-416`, and
`script/seon/dev/program_indexer.clj:49-241,331-390,464-576`.

## Already-paid-for failure classes

- `a53d2a691` fixed deftest detection after analyzer metadata placed `:test`
  somewhere the implementation did not inspect. Every test had silently
  become a function row. Later, ordinary `defn` metadata containing `:test`
  forced classification by the literal form head instead of analyzer flags.
- `f7464cce1` deleted boot-time test preload/indexing. A platform roster is not
  declaration discovery.
- `fa327214e` added a `schema/register!` self-tee because registrations outside
  the eval wrapper vanished. It fixed an omission by creating a second
  publication route.
- `fbde4283b` rolled back newly registered keys after failed eval;
  `57e85dbc7` introduced exact whole-registry snapshot restoration; and
  `c7c04247a` replaced that broad restoration with isolated per-eval deltas
  after concurrency made whole-process rollback unsafe.
- Analyzer digests omitted function bodies, so body-only function and test
  redefinitions stayed stale. Exact source is the durable input.
- Lookup refs to absent namespace rows aborted whole terminal transactions.
  Identity upserts and omission of absent optional namespace refs fixed that
  class.
- Redefinition had to retract omitted card-one facts and replace entire
  cardinality-many/component values. Omission is not retraction.
- Mutating a runtime before committing its row left runtime state ahead of
  database truth. The fresh commit-first order is retained.

## What the fresh implementation had to resolve

### Raw schema syntax was not the registered schema value

`src/seon/sci/reader.cljc:274-285` stores the unevaluated third argument of
`seon.schema/register!`. The call `(register! ::compiled-validator 'fn?)`
therefore produces `(quote fn?)`, although `register!` receives `fn?`.
Computed schema expressions exposed the same defect. Commit `10dfe0ff4` makes
the intended distinction explicit in source: the reader supplies identity and
exact source, while evaluation stages `register!` in an isolated delta and
replaces the raw form with the evaluated canonical definition
(`src/seon/sci/eval.clj:539-575`;
`src/seon/schema.cljc:1861-1931`). Candidate projection validation happens
before the row can leave evaluation. The adversarial runtime gate below shows
that this source shape has not yet graduated.

### Schema projection was not cluster-sovereign

Before `10dfe0ff4`, `seon.sci.eval/activate-program-schemas!` read one database
and replaced process-global schema state. Malli's default registry is likewise
process-global
(`reference-code/malli/src/malli/registry.cljc:40-52`), while Malli compilation
accepts an explicit registry (`reference-code/malli/src/malli/core.cljc:309-333`).
The current runtime source derives an immutable projection from each supplied
database value and carries it in the SCI ctx (`src/seon/schema.cljc:1701-1765`;
`src/seon/sci/eval.clj:357-492`). The intended alternating-cluster proof lives
at `test/seon/cluster/turn_test.clj:1062-1129`, but currently errors while
compiling the cluster-specific schema; cluster sovereignty is not yet proven.

### Tests were rows without a runtime materialization contract

Before `10dfe0ff4`, test installation returned `true` without evaluating the
committed source, and acquisition queried only functions. Current source now
evaluates the exact test source pulled from `db-after`, and acquisition queries
agent-authored test rows alongside functions (`src/seon/sci/eval.clj:356-490`).
The intended restart proof executes the acquired test Var's `:test` function
(`test/seon/cluster/program_restart_test.clj:79-181`), but the current run times
out before the declaration fact commits. Registration and result recording
remain different mechanisms; test materialization is not yet proven.

Commit `87726eae2` closed the reader-identity defect: function, test, namespace,
and namespace-changing operations now use resolved operator identity
(`src/seon/sci/reader.cljc:200-285,287-362`). Qualified lookalikes and quoted
`in-ns` forms remain ordinary data, while real core and referred operations
retain their semantics (`test/seon/sci/reader_test.clj:405-431`).

### A runtime schema row did not make a usable database attribute

The old terminal transaction exact-upserted `:seon.schema/key` and
`:seon.schema/form` without deriving the corresponding Datahike declaration.
Commit `10dfe0ff4` rebuilds the candidate projection at the transaction's
database value and prepends the newly required Datahike schema transaction data
to the same receipt/program transaction (`src/seon/cluster/run.cljc:589-633`;
`src/seon/schema/datahike.cljc:213-274`). The recurring turn proof commits a computed
schema form, observes the row and receipt in one transaction, and immediately
transacts a fact using the installed attribute
(`test/seon/cluster/turn_test.clj:880-936`), but that proof currently fails.

### The first integrated runtime gate is red

After `10dfe0ff4`, `5a517dab7`, `2c2ea1b79`, and the global-schema cleanup were
present in the shared tree, the independent focused command
`bin/test seon.cluster.turn-test seon.cluster.program-restart-test` ran 33 tests
and 208 assertions with 5 failures and 2 errors. The failing classes were:

- evaluated runtime schema publication and immediate Datahike use;
- discard after a rejected terminal transaction, where candidate forms and the
  Datahike attribute remained changed;
- alternating incompatible cluster projections, which raised an invalid-schema
  error for the shared key; and
- restart acquisition, which timed out waiting for the declaration fact.

The commits establish the intended seams and recurring tests, not graduation.
These failures remain the working edge.

Commit `16afa2a10` removed the schema-family stale-removal exemption. The later
global-schema cleanup in `639497197` supersedes `eb4ac8167`'s temporary
namespace-stub repair:
the desired population now combines canonical resource rows with source-only
declarations under one global key identity, and schemas do not acquire
namespace ownership from keyword spelling. Provenance preserves
agent-authored rows while stale source-owned rows are removed
(`src/seon/fn.clj:266-318`). The focused recurring gate exercises redefinition,
stale removal, preservation, and a zero-operation second pass
(`test/seon/fn_test.clj:370-457`).

### The three duplicate owners were consolidated

Before `52423e362`, `seon.fn/durable-row`, `seon.sci.eval/program-row`, and
`seon.cluster.run/program-row-tx` separately defined identities, owned
attributes, deletion, and exact replacement. `seon.program` now owns those
shared facts and typed function/test deletion, and the transaction owner calls
that declaration admission before replacement (`src/seon/cluster/run.cljc:561-599`).
Producer-specific Malli admission remains outside it. The evaluated-schema,
cluster-projection, and test-materialization gaps remain open.

## Required recurring proof

One class-covering matrix must establish:

- canonical row parity for build and runtime over their shared admitted
  function, schema, and test domain;
- explicit build-all-functions/runtime-contracted-functions policy;
- evaluated schema form publication, including a computed form and refusal
  without candidate mutation, plus immediate database acceptance of a fact
  using the committed attribute;
- exact redefinition and typed deletion of functions and tests;
- resolved `clojure.test/deftest` identity, including refusal to misclassify a
  different `foo/deftest`;
- exact source-owned reconciliation for every declaration family, including
  stale removal and a converged zero-datom pass;
- installation only after successful terminal commit and only from that
  transaction report's `db-after`;
- current function, schema, and test materialization after cluster reopen,
  without receipt replay; and
- two incompatible cluster schema projections alternating in one JVM without
  bleed.

The surviving reopen harness was extended in place at
`test/seon/cluster/program_restart_test.clj:79-181`; its current timeout is part
of the red integrated gate above, not a reason to add a second mechanism.
