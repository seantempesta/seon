---
type: landing
status: landed (b4c1ed00e, 8074989b9); two reds need hunks in files this lane does not own; root runs the tests
lane: fix-eval-schema-reds-opus
created: 2026-09-26
---

# Fix sci.eval-test and schema-test reds (2026-09-26)

Root's runs on default at f240fd666 (`tmp/orchestrator/verify-0720.out.d/004.txt`, `007.txt`)
showed 13 reds in `seon.sci.eval-test` and 12 failures in `seon.schema-test`. Each red is
classified below by the three questions, and each class is fixed once where it is produced.
Two classes are produced in files this lane does not own. Their exact hunks and evidence are
in the first section.

## Still broken (hunks for their owners)

| Red | Class | Owner / hunk |
|---|---|---|
| `seon.sci.eval-test/both-entrances-classify-one-failure-identically` (:2311, 2 fails) | surviving-owner defect | `src/seon/sci/kernel.clj` (lost-error-seams lane). Hunk A below |
| `seon.schema-test/error-declared-schemas-and-their-owned-members-are-storable` (:1420, 6 fails) | surviving-owner defect | `src/seon/schema/datahike.clj` (unowned; the store adopts through `cluster.clj` `declaration-changes`). Hunk B below |

**Hunk A: SCI's interrupt marker reaches the cause chain.** `kernel/failure-value`
(src/seon/sci/kernel.clj:543) removes `:sci.impl/interrupt` from the top-level ex-data only.
`seon.error.refusal/diagnostic` then builds `:seon.error/chain` from the throwable
(refusal.clj:75-89, ed2e1a6b6, 2026-09-22). The second link's `:seon.error/data` keeps the
marker, an opaque `java.lang.Object`. REPL (pid 94554): the marker is at path
`[:seon.error/chain 1 :seon.error/data]`. The chain is the whole cause, so it stays; only the
kernel knows this key is SCI's private one. In `failure-value`, wrap the `diagnostic` result:

```clojure
(update result :seon.error/chain
        (partial mapv (fn [link]
                        (let [data (not-empty (dissoc (:seon.error/data link) :sci.impl/interrupt))]
                          (if data (assoc link :seon.error/data data) (dissoc link :seon.error/data))))))
```

Proof on the real value, with the scrub applied in a scratch form: `:seon.sci.kernel/error` still
validates, and no marker is left.

**Hunk B: an alias of an identity attribute inherits `:db/unique`.** `compiled-storage`
(src/seon/schema/datahike.clj:93-100) merges the target's properties through a named ref. An
attribute declared as `:seon.turn/compaction-agent-id :seon.agent/id` therefore installs as
`:db.unique/identity`, so an error carrying that value would upsert onto another error. The gate
meant to refuse this, `assert-storable-schema!` (:199-213), reads the member's own properties and
never dereferences, so it agrees with the test, not with the bridge. Identity is a property of
the attribute being declared, not of the value shape it reuses. The hunk at :100:

```clojure
(assoc target ::properties (merge (dissoc (::properties target) :seon.db/identity :seon.db/unique)
                                  properties))
```

REPL, a scratch copy of `compiled-storage` compared with the installed one over all 3,477
projection keys: no value-type or cardinality changes; identity drops for 48 aliases. Six of them
are installed with `:db/unique` today, exactly the test's six: `:seon.context/selection-agent-id`,
`:seon.message/unknown-recipient`, `:seon.sci.eval/schema-refused`,
`:seon.test.runner/default-cluster-refused`, `:seon.test.runner/long-test-ns-hook`,
`:seon.turn/compaction-agent-id`. Adoption on the live store replaces an attribute whose
uniqueness is dropped, together with its data (`cluster.clj:975-1005`,
`declaration-property-changes`). Prove that adoption on a branch of the live store before
landing. Introduced when refs began carrying target properties (22a1a0567); the test came with acee1e21f.

## Classes, dispositions, commits that introduced them

### schema.clj (b4c1ed00e)

- **`an-incremental-projection-equals-its-full-build` (:1595 ×4, :1671)**: surviving-owner defect,
  not 609a6365d. Compiling a declaration replaced its `:gen/gen` symbol with the Var's value
  (`requiring-resolve deref`, plus `::generator-symbol` metadata; 8704e5ed5). After the generator's
  namespace reloaded (`seon.sci.eval`), a derived projection kept the old object and a full build
  took the new one. REPL: the stored projection's `:seon.sci.eval/ctx`, `ctx-reference` and
  `sci-var` generators are not `=` to the current Vars; `projection-state`'s is. All four labels
  compare unequal raw and equal with generators read as their symbols. The Malli fork already
  resolves a qualified `:gen/gen` symbol to the loaded Var's current value when it generates
  (`reference-code/malli/src/malli/generator.cljc:466-482`, `core.cljc:2900-2906`, 25710a67).
  **Fix:** delete the compile-time resolution and its metadata canonicalization (−13 lines).
  REPL: all 66 declarations naming a generator (55 distinct symbols, all loaded) compile with the
  symbol and generate valid values (17 ms total). Regression:
  `a-compiled-generator-names-its-generator-by-symbol`, red on the unfixed JVM for the right
  reason (it finds a `Generator` object).
- **`a-refused-projection-source-never-yields-a-projection-with-no-forms` (:1453)**: surviving-owner
  defect since 9b8c5b405. `projection-from-database` declared its database as `:map`, so a refusal
  map passed and `seon.db/carried-projection` refused it one call deeper. **Fix:** the contract
  names `:seon.db/database-value`. REPL, the function schema's input: a db, an as-of view and a
  `d/with` value are valid; the poisoned map is not; the projection output is valid.

### sci/eval_test.clj (8074989b9)

| Red | Class | Disposition | Since |
|---|---|---|---|
| `a-turn-fork-registers-an-empty-assigned-agent-namespace` (:1404) | retired assumption | `creation-tx` seeds the namespace's `ns` row (`namespace-seed-call`), and a namespace row is program, so the acquired base holds it. The assertion now says so | 6cd8d86f5 |
| `one-unloadable-row-cannot-prevent-cold-acquisition` (:1556 nil eid) | retired assumption | a bad agent row is `::agent-mistakes` with `:seon.sci.eval/refused-function`; the query, count and schema (`:seon.sci.eval/interpretation-error`) follow. The stored fact is asserted present before it is pulled | 03bd7cfc9 |
| `acquisition-records-its-refusals-on-the-acquired-branch-under-foreign-custody` (:1619) | retired assumption | same query key; the claim (recorded on the acquired branch, never the caller's) holds | 03bd7cfc9 |
| `base-context-bindings-resolve-without-dependency-stubs` (error, 11.3 s) | slow test design plus a test bug | it re-analyzed `src`+`test` and refused on dev roots the dependency analysis excludes. It now reads the world's program rows. Its `(some-> % (comp m/properties …) :k)` always answered nil, so `clojure.test` counted as first-party; it now reads the property | test |
| `database-provenance-regenerates-the-base-and-reverts-for-retained-forks` (:193, :202, 16 s) | fixture producer, retired assumptions, with-redefs | `replacement-tx` (the hunk from fix-turn-test-reds §B); the private-sentinel assertion is removed (a private `def` is a declaration: default holds 680 `def` rows); `with-redefs` over `packaged-forms` is deleted; regeneration interprets affected callers, so incremental names are a subset and root identity is compared outside the affected closure. Hidden before by the :202 error | 1b3c0f1cb, 39a337013 |
| `evaluation-custody-is-derived-only-from-the-cluster-context` (:1839) | retired assumption | a write refusal is `:seon.db/transaction-refused` naming `:seon.db/attribute`, not an invalid read; the read-back pulls its own message | unknown |
| `non-evaluable-agent-source-reports-its-jvm-fallback` (error) | test of deleted machinery | `:jvm-fallback` has no src reference. Replaced by `a-non-evaluable-agent-override-is-its-agents-mistake-never-the-jvm-definition`: the mistake names `seon.id/valid?` and its cause, and the JVM definition does not resolve in its place (AGENTS: refuse unsupported overrides by name) | 03bd7cfc9 |
| `static-and-runtime-contracted-definitions-publish-identical-facts` (9.5 s) | slow test design | publication's incremental path (`:seon.fn/changed-paths` + `:seon.source/previous-database`) indexes the one file: 306 ms, a row equal to the full build's (9,633 ms) | test |
| `both-entrances-…` | owner defect | Hunk A | ed2e1a6b6 |

The `s3.missing` override now refuses at analysis ("no analysis of s3.missing … fill the cache").
That is a platform-fault diagnosis for the author's mistake, a call to a namespace that does
not exist. Filed as
[a-call-into-a-namespace-that-does-not-exist-refuses-as-missing-dependency-analysis](../../../seon/issues/a-call-into-a-namespace-that-does-not-exist-refuses-as-missing-dependency-analysis.md) (a9449b7c3).
The replacement test uses an unresolved local.

## Evidence and its limits

Every edited body was evaluated from the working file in a scratch namespace
(`tmp/fix-eval-schema-reds/runner.clj`: the file's requires and helpers, then selected
`deftest`s, run under a member handle acquired isolated off default's cluster and bound to
`seon.test/*member*`). Default pid 7275 (snapshot 65bf078ec): all eight edited eval members were
green (pass counts 6, 9, 3, 4, 26, 11, 4, 4), except `both-entrances`. This is diagnostic only:
the world is a branch of the live cluster, not the member request world, and the scratch copies
run unarmed. The schema.clj change is **not loaded** in default (hook publication off), so
schema-test's two fixes are proven on their parts, not by the tests. Default was down from 00:58
to 01:10Z (restarted by root, then boot stalled at the ctx layer with the MCP JVM eval answering
only the config error; see fix-boot-head). Nothing here stopped or reset it.

## Why sci.eval-test takes 94 s

Stored run 792ecc882546: 64 members, 80.9 s from the run's `:at` to the last completion, plus
admission and recording. By completion gaps: database-provenance 16.0 s, of which the one-argument
`program-fn-row` building the whole-checkout manifest is 15 s cold (`build-manifest` 14.9 s,
cached afterwards); base-context-bindings 11.3 s (whole re-analysis, now 0.44 s); then twelve
members at 1.6-3.8 s, and 46 under 1 s totalling 21.2 s. static-and-runtime was 9.5 s (now 0.8 s).
The per-operation costs on a member branch (pid 7275, root's run concurrent):

| op | ms |
|---|---|
| `with-database` (branch acquire + release) | 36 |
| `fork-cluster-ctx` / `build-base-ctx` | 3 / 2 |
| namespace-row transaction | 72 |
| `evaluate "(inc 1)"` | 158 |
| `eval/base-ctx` on a changed db | 301 |
| `acquire!` into a fresh ctx | 548 |
| `program-fn-row` + transaction | 555 |
| `seed-cluster!` | 611 |

Setup is cheap. The cost is in the canonical producers the bodies call: `seon.db/with-declarations`
(3,114 calls, 6.1 s inclusive in that probe) and `seon.db/datoms` (11 calls, 2.4 s) under
`seed-cluster!` and acquisition. Those are owners outside this lane (seon.db, test_support seeding,
sci.eval acquisition). The remaining whole-program cost in this file is the one-argument
`program-fn-row` manifest (15 s cold per publication); `database-provenance` and
`overrides-follow-…` use it.

## Root runs

```
bin/test-check default --ns seon.schema-test        # after adopting src/seon/schema.clj
bin/test-check default --ns seon.sci.eval-test
```

Adopt `src/seon/schema.clj` first. `seon.schema-test` stays red on
`error-declared-schemas-…` until Hunk B lands; `seon.sci.eval-test` stays red on `both-entrances-…`
until Hunk A lands.

## Sizes

- src: schema.clj +6 −15 (net −9).
- tests: schema_test +15; eval_test +91 −54 (net +37, mostly the replacement test and comments).
- Net: +43, 0 new src machinery. Deleted: compile-time generator resolution (5 lines), generator
  canonicalization (7 lines), one with-redefs, one assertion of deleted machinery.
- Released: test/seon/sci/eval_test.clj, test/seon/schema_test.clj, src/seon/schema.clj.
