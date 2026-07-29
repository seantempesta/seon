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
| SCI | submodule `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | `reference-code/sci/src/sci/core.cljc`, `reference-code/sci/doc/interrupt.md` | one ctx/fork and post-commit interpreted installation |
| Datahike | submodule `9a7a9ef10a954c32075e60d929f9101a9ac8abd9` | `reference-code/datahike/src/datahike/db/transaction.cljc`, `writing.cljc`, `writer.cljc` | identity upsert, exact retraction, one serial transaction, `db-after` |
| Malli | `0.20.0`, vendored revision `80138076960e7820523b4cb932c5b5d1936d4e7f` | `reference-code/malli/src/malli/registry.cljc`, `malli/core.cljc` | registries can be supplied explicitly; Malli's default registry is process-global |
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
- `fbde4283b` restored the schema registry after failed eval. `c7c04247a` then
  narrowed that repair because restoring a whole process snapshot corrupted
  concurrent registration. Isolation must be per evaluation.
- Analyzer digests omitted function bodies, so body-only function and test
  redefinitions stayed stale. Exact source is the durable input.
- Lookup refs to absent namespace rows aborted whole terminal transactions.
  Identity upserts and omission of absent optional namespace refs fixed that
  class.
- Redefinition had to retract omitted card-one facts and replace entire
  cardinality-many/component values. Omission is not retraction.
- Mutating a runtime before committing its row left runtime state ahead of
  database truth. The fresh commit-first order is retained.

## Current falsified assumptions

### Raw schema syntax is not the registered schema value

`src/seon/sci/reader.cljc:276-285` stores the unevaluated third argument of
`seon.schema/register!`. The call `(register! ::compiled-validator 'fn?)`
therefore produces `(quote fn?)`, although `register!` receives `fn?`.
Computed schema expressions have the same defect. Runtime currently skips the
declaration's evaluation entirely and commits raw syntax
(`src/seon/sci/eval.clj:321-347,522-533`). The existing isolated registration
delta at `src/seon/schema.cljc:1768-1819` is the maintained seam for obtaining
the evaluated value without publishing process state before commit.

### Schema projection is not cluster-sovereign

`seon.sci.eval/activate-program-schemas!` reads one database and calls the
process-global `seon.schema/activate-projection!`
(`src/seon/sci/eval.clj:411-439`; `src/seon/schema.cljc:325-332,1702-1716`).
Malli's default registry is likewise process-global
(`reference-code/malli/src/malli/registry.cljc:40-52`), while Malli compilation
accepts an explicit registry (`reference-code/malli/src/malli/core.cljc:309-333`).
A sparse fixture database has already replaced the JVM registry and broken
unrelated later tests. Two clusters with incompatible forms for the same key
cannot currently be sovereign.

### Tests are rows without a runtime materialization contract

`src/seon/sci/eval.clj:395-399` treats test installation as `true`, and
`acquire!` queries only functions. `src/seon/test/runner.clj:81-109,210-225`
runs explicitly loaded JVM namespaces and never discovers program-graph test
rows. Registration and result recording are different mechanisms; the latter
must not manufacture evidence that the former works.

### Three current owners can disagree

`seon.fn/durable-row`, `seon.sci.eval/program-row`, and
`seon.cluster.run/program-row-tx` separately define identities, required and
owned attributes, schema parsing, deletion, and exact replacement. Runtime
deletion names only `:seon.fn/delete`, although historical `ns-unmap`
retracted both function and test identities. This duplication is the repair's
first boundary.

## Required recurring proof

One class-covering matrix must establish:

- canonical row parity for build and runtime over their shared admitted
  function, schema, and test domain;
- explicit build-all-functions/runtime-contracted-functions policy;
- evaluated schema form publication, including a computed form and refusal
  without candidate mutation;
- exact redefinition and typed deletion of functions and tests;
- exact source-owned reconciliation for every declaration family, including
  stale removal and a converged zero-datom pass;
- installation only after successful terminal commit and only from that
  transaction report's `db-after`;
- current function, schema, and test materialization after cluster reopen,
  without receipt replay; and
- two incompatible cluster schema projections alternating in one JVM without
  bleed.

The existing function reopen test at
`test/seon/cluster/program_restart_test.clj:69-132` is the surviving harness.
It should be extended rather than shadowed by a second restart mechanism.
