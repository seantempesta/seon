---
type: landing
status: landed (b4c1ed00e, 8074989b9, 95b96f4aa, 26f1f40d6); root runs the tests
lane: fix-eval-schema-reds-opus
created: 2026-09-26
---

# Fix sci.eval-test and schema-test reds (2026-09-26)

Root's runs on default at f240fd666 (`tmp/orchestrator/verify-0720.out.d/004.txt`, `007.txt`)
showed 13 reds in `seon.sci.eval-test` and 12 failures in `seon.schema-test`. Each red is
classified below by the three questions, and each class is fixed once where it is produced.
Round 2 (root granted kernel.clj and schema/datahike.clj) fixed the two classes whose
producers are in those files. No red is left without a fix; root runs the tests.

## Round 2: the two owner fixes (95b96f4aa, 26f1f40d6)

- **`both-entrances-classify-one-failure-identically` (:2311), surviving-owner defect.** SCI's
  `interrupt!` tags its ex-data with an opaque marker object that `sci.impl.utils/interrupt-ex?`
  reads (reference-code/sci/src/sci/interrupt.cljc:42). The cause chain `diagnostic` builds
  (`seon.error.refusal/chain`, ed2e1a6b6) copies each link's ex-data whole. `failure-value`
  removed the marker from the top-level data only, so it survived at
  `[:seon.error/chain 1 :seon.error/data]`. The kernel owns the SCI knowledge, so it is fixed
  there, with one strip: `failure-value` reads the chain once, strips the marker from each link
  (`without-interrupt-marker`, contracted on `:seon.error/link`), takes the throwable's data from
  the outermost link and returns that chain. The chain `diagnostic` derives is replaced by the
  stripped one; `diagnostic` is unchanged. REPL (pid 7275), scratch copy compared with the
  installed function on a real SCI time-limit and a thrown ex-info: the marker is gone; the result
  validates as `:seon.sci.kernel/error` and against the output contract; every link validates;
  apart from the chain the value is equal; the ex-info case is identical. +19 −3.
- **`error-declared-schemas-and-their-owned-members-are-storable` (:1420 ×6), surviving-owner
  defect.** `compiled-storage` (schema/datahike.clj:93-100) merged a named ref's target
  properties, so an attribute that reuses an identity's value shape
  (`:seon.turn/compaction-agent-id :seon.agent/id`) installed as `:db.unique/identity`; an error
  carrying it would upsert onto another entity. `assert-storable-schema!` (:199-213) reads the
  member's own properties and already treats identity as the declaring attribute's. The ref now
  drops the target's `:seon.db/identity`/`:seon.db/unique`, and its own properties still apply.
  A scratch copy compared with the installed function over all 3,477 projection keys: no value-type
  or cardinality change; identity drops for 48 aliases, of which 6 are installed, exactly the
  test's six (`:seon.context/selection-agent-id`, `:seon.message/unknown-recipient`,
  `:seon.sci.eval/schema-refused`, `:seon.test.runner/default-cluster-refused`,
  `:seon.test.runner/long-test-ns-hook`, `:seon.turn/compaction-agent-id`).
  **Adoption on the live store**, an isolated branch of default (pid 7275), through the one schema-change
  mechanism `seon.cluster/attribute-change-tx` with the six declarations less `:db/unique`: each
  adopts `:replace` (`attribute-adoption`; dropping uniqueness is not an in-place Datahike update,
  `datahike/schema.cljc:270`), 12 operations, committed through `seon.db/transact!` in 220 ms with no
  refusal. Afterwards each is installed as the same value type and cardinality without
  `:db/unique`, and re-adopts `:converged`. The live store and the branch hold **0 datoms** of all six,
  so the replacement drops nothing. No accretion rename is needed. Introduced when refs began
  carrying target properties (22a1a0567); the test came with acee1e21f. +7 −1.

For the sci.eval owner (root routes it; not fixed here): incremental `install-row!` installs
the override's one row and does not interpret its affected callers, while regeneration
(`base-ctx` on the changed database) does (39a337013). Measured on a branch where
`seon.id/valid?` is overridden: the affected closure has 11 members, and regeneration binds
701 names that incremental installation does not. Compiled callers direct-link the JVM
definition, so after an incremental install they still call the JVM definition, not the
override. `database-provenance-…` now asserts the subset relation and compares roots outside the
affected closure.

## Round 5: call-preparation snapshot staleness, D7 / C10 (a44538acc)

- Reproduced on isolated branches of default. With `current-snapshot` on one call-state,
  reading `db1` then `db0` answered `db0` with `db1`'s snapshot: the added `probe.cp/f` was
  prepared, and checked-through 536874744 was above `db0`'s basis 536874742. A sibling branch
  without `f` also saw `f` prepared. `snapshot` answered both correctly.
- `adopt`, `current-snapshot` and the call-state's `:snapshot` member are deleted.
  `snapshot` looks up the completed snapshot by commit first (`[::snapshot commit-id]`), calls
  its callees unarmed on the hook path, and the hook calls it. The call-state holds plans only.
  `call_preparation.clj` +43 −90, test +42 −10.
- Hook cost on default (pid 33919, scratch copy against the installed hook, 20k calls each,
  callee outside `prepared-symbols`): 2.35-2.50 µs against 2.75-4.59 µs. Scratch runs of 11
  snapshot, plan and branch members are all green. Regression:
  `an-older-value-and-a-sibling-branch-read-their-own-snapshot`.
- No hunk is needed in `sci/eval.clj` or `sci/kernel.clj`: `install` and `hook`'s signature
  are unchanged.
- Not done: the hook still supplies against the connection's head, not the evaluation's
  `:seon.db/db`. Changing that decides read-your-writes inside one evaluation, which belongs to
  the repl-entry-renders design §2.3. It is recorded in the issue.
- Static checks: contracts-compile over `call_preparation.clj` 0 findings; resources-as-EDN
  211/0.
- Root runs: `bin/test-check default --ns seon.call-preparation-test`.

## Round 4: the seon.schema reloader and the one-argument program-fn-row (2a5375c3b, 324ae355a)

- **The reloader, proven by reproduction.** `seon.schema/converged-predicate-var` ran
  `(require ns :reload)` on any loaded namespace whose copy lacked a predicate a declaration
  named, and swallowed every error. On default (pid 25654) I loaded a scratch namespace from a
  scratch classloader root and replaced its Var root with a marker, as arming does. Compiling
  `[:fn fixevalreds.probe/absent?]` still refused ("no admitted callable"), and afterwards the
  Var held its source root, not the marker. So any declaration naming a missing
  `seon.schema/…` predicate strips all of that namespace's wrappers. Which caller did it in the
  052552039 pass is not pinned: no test names an absent `seon.schema` predicate literally.
  **Fix:** the function is deleted (−58 lines). Compilation refuses by name
  (`:seon.schema/predicate-namespace`), and adoption is the one place that reloads.
  **Consequence:** a commit that adds a predicate to an already-loaded namespace, together with
  a declaration naming it, is refused by in-place publication until that namespace loads from
  the new source (restart or adoption). Regression:
  `a-declaration-naming-a-predicate-its-loaded-owner-lacks-refuses-without-reloading`; its second
  case checks that `seon.schema/compilable-form`'s armed root is the same object after a refused
  compile. The reproduction is added to
  `docs/seon/issues/adopting-the-instrumentation-owner-leaves-unreloaded-namespaces-unarmed.md`.
  Static checks: contracts-compile over the edited schema.clj 0 findings; resources-as-EDN 211/0.
- **`program-fn-row` with one argument** now re-indexes the declaration's own file through
  publication's incremental path, against the executing member's world: the canonical file
  row, stored digest, 25-291 ms, instead of the whole-checkout manifest (12.8-15 s, refused on
  the dev roots). The four callers keep the one-argument form. Scratch runs:
  `overrides-follow-…` 10/0 in 1.0 s, `database-provenance-…` 26/0 in 3.6 s,
  `a-component-bearing-row-…` 274/0 in 1.4 s.

## Round 3: reds at real HEAD 052552039 (verify-1510 018, 019)

The rows from verify-1210 came from a JVM still running the stale archive 65bf078ec
(pid 12358 and pid 14906). Their runs record loaded commit 6ab71b89…, `without-interrupt-marker`
did not resolve, and the old test names still did. They do not test these commits.

| Red | Class | Disposition |
|---|---|---|
| schema-test `a-component-bearing-row-validates-its-own-declared-shape` (error + 11.6 s) | slow fixture producer | The one-argument `test-support/program-fn-row` builds the whole-checkout manifest (`build-manifest` 12.8-15 s cold). At HEAD it also refuses on the dev roots (`dev-cache`, `seon.dev.*`, `seon.operator*`) that the dependency analysis excludes, because test files call them. The test now analyzes the world's stored source through the production path: 2.0 s, 275/0 in a scratch run (3c40f0c21) |
| schema-test `a-refused-projection-source-…` | environmental: `seon.schema` unarmed | In pid 19443, all 103 contracted `seon.schema` Vars had profile cells with calls (they were armed at boot), yet 0 carried a wrapper. Every other src namespace was fully armed. A reload of `seon.schema` during the pass removed them. The contract is correct: validated directly, the poisoned map is refused. Candidate reloaders: `seon.schema/converged-predicate-var` (schema.clj:246, `require … :reload` of any loaded namespace, errors swallowed) and development adoption (`cluster/load-development-definitions!`). Not pinned; for root |
| sci.eval-test `database-provenance-…` (16.4 s) | slow fixture producer | the same one-argument form ×3, replaced with the stored-source row (`:core` admission stated): 26/0 in 4.5 s |
| sci.eval-test `contracted-defn-renders-the-var-it-declared` (:833, :834) | floor lane | shown `#object[sci.lang.Var]` where `#'user/…` is expected: the floor printer's Var face |
| (destructive) `the-evaluator-remains-live-after-its-namespace-reloads` | host-bound test on the shared JVM | it `require :reload`s `seon.sci.eval` in default (the source of round 1's stale generators); it now declares `:seon.test/fixture-observation` and runs as a platform-host member |
| render.value-test "got nil nil" (verify-1210) | kernel owner defect since 9bf22ecd9 | `:seon.error/offending nil` stored when a failure names neither a symbol nor a subject; fixed in b28fcf408 (absence is no key) |
| web-debug-test `block-reference-headers-…` (:702) | retired assumption (26f1f40d6) | the fixture declares its identity attribute directly through `schema-rows` (f62e42661); 25/0. No other test declares an alias of an identity attribute |

For the owner of test_support.clj (the producer fix): the one-argument `program-fn-row`
should analyze the executing member's stored source (`(:seon.db/db (execution-handle …))`)
instead of building the checkout manifest. This removes about 15 s per publication and the
dev-root refusal for every caller: 4 one-argument call sites remain, among them eval_test's
`overrides-follow-…`.

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
| `both-entrances-…` | owner defect | fixed in kernel.clj, round 2 (95b96f4aa) | ed2e1a6b6 |

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
green (pass counts 6, 9, 3, 4, 26, 11, 4, 4); `both-entrances` is covered by the round-2 kernel proof. This is diagnostic only:
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

Adopt `src/seon/schema.clj`, `src/seon/schema/datahike.clj` (schema change: the six attributes
replace as above) and `src/seon/sci/kernel.clj` first.

## Sizes

- src: schema.clj +6 −15, kernel.clj +19 −3, schema/datahike.clj +7 −1 (net +13).
- tests: schema_test +15; eval_test +91 −54 (net +37, mostly the replacement test and comments).
- Net: +65, 0 new src machinery. Deleted: compile-time generator resolution (5 lines), generator
  canonicalization (7 lines), one with-redefs, one assertion of deleted machinery.
- Released: test/seon/sci/eval_test.clj, test/seon/schema_test.clj, src/seon/schema.clj, src/seon/sci/kernel.clj, src/seon/schema/datahike.clj.
