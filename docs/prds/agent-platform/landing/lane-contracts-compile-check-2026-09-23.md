---
type: landing
status: landed
created: 2026-09-23
tags: [agent-platform, contracts, from-zero, hook]
---

# Lane contracts-compile-check (fix schedule #0g), 2026-09-23

Class regression for "a `:malli/schema` a warm JVM accepts but a fresh store
refuses at its first publication". There were three occurrences in one day:
`[:fn #(instance? Throwable %)]` (dc89f1d8a), a bare `:vector` (74262d86e) and
`[:fn #(instance? ValueKey %)]` (fixed as `seon.db/projection-value-key?` in fa39b67b3).

## What landed

- `test/seon/contracts_compile_test.clj` (new). `check` reads every
  `:malli/schema` from source text using edamame. It reads the attr-map and
  the name metadata of the `def` family, and resolves `::alias/k` through the
  ns form. It does not load any namespace it reads. It turns each contract
  into the stored contract with the indexer's own steps:
  - `seon.fn/namespace-context`
  - `seon.fn/qualify-schema-symbols`
  - `schema/canonical-definition`
  - the `pr-str`/`edn/read-string` round trip that `add-contract-facts` reads.

  It then compiles the result the way `schema/projection-registry` compiles a
  function contract: `schema/compilable-form`, then `m/function-schema`
  against the registry of the projection built from the checkout's
  `resources/seon/schemas/*.edn` (composed by `schema.edn/derive-config-forms`).
  Each refusal names file:line:col, the definition, the authored form, the
  reason (Malli's subject data included) and the whole cause chain
  (`seon.error.refusal/chain`).

  Two deftests:
  - `every-declared-contract-compiles-against-the-packaged-projection` covers
    all of `src/` and `test/`.
  - `each-historical-from-zero-break-is-refused-by-name` covers the three
    reconstructed forms, held as source strings.
- `bin/seon-hook`. The check runs through the live root's prepl
  (`operator/live-root-value!`, the same seam schema admission uses) for each
  changed `.clj`/`.cljc` under `src/` or `test/`.
  - PreToolUse: prospective content is checked. An edit that declares a
    non-compiling contract is blocked (exit 2) before it lands.
  - PostToolUse: the landed file is checked. Errors are a refusal. If no live
    root answers, the result is an explicit `ADVISORY — contract compile check
    unavailable`, never a pass.
  - Config dial `[:contract-compile :enabled]` defaults to true. The hook
    config file is not edited.
  - The checker is `load-file`d from the checkout on each call, into its own
    namespace, never over default's Vars. That is because default runs a
    published snapshot (`user.dir` = `data/source/76b42f90f…`) whose classpath
    has no copy of it.
  - The live cluster's `seon.db/carried-projection` is handed in as
    `::candidate`. It is used only when its forms equal the checkout's
    packaged forms. This is not a new cache: it compares the value default
    already carries.

### Snapshot-JVM fidelity rule

Default runs an older snapshot, so it cannot resolve a predicate that exists
only in the checkout, such as `seon.profile/snapshot?` today. A fresh JVM's
`requiring-resolve` would bind that predicate. So the check binds it when the
checkout's source for that namespace defines the name (`checkout-defines?`).
Only compilation is asked, so nothing validates against the stand-in.

When neither the JVM nor the checkout defines a predicate, the check reports
that by name itself. It does not let `compilable-form`'s
`converged-predicate-var` `require :reload` one of default's namespaces.

In a JVM built from the checkout (a test run), every predicate resolves at
runtime and the fallback never runs.

## Evidence

The probe script is `tmp/contracts-compile-lane/probe.clj`. The historical
reconstruction is `tmp/contracts-compile-lane/historical.clj`; the forms are
not in `src`.

**HEAD a20b5c5ae**: git-archive snapshot `tmp/contracts-compile-lane/head-a20b5c5ae`,
with `reference-code` symlinked and the new test file copied in. Command:
`clojure -M:dev:test tmp/contracts-compile-lane/probe.clj`.
- 423 files, 1939 contracts, 0 read refusals, **0 findings**.
- The historical file gives exactly 3 findings:
  - `historical.clj:6:1 …/release-result … Predicate (fn* [%1] (clojure.core/instance? clojure.core/Throwable %1)) has no admitted callable in the corpus projection.`
  - `historical.clj:11:1 …/page-keys :malli/schema [:=> [:cat :map] :vector] … :malli.core/child-error {:type :vector, :properties nil, :children nil, :min 1, :max 1}`
  - `historical.clj:16:1 …/projection-value-key … Predicate (fn* [%1] (clojure.core/instance? ValueKey %1)) has no admitted callable in the corpus projection.`
- `(= (checkout-packaged-forms ".") (seon.schema.edn/packaged-forms))` → `true`.
  The disk read is the classpath population.

**Working tree** (with other lanes' uncommitted hunks): 418 files, 1927
contracts, 0 findings. The same 3 historical findings.

**Deftests** (working tree): `clojure -M:dev:test -e "(require 'clojure.test)
(require 'seon.contracts-compile-test) (clojure.test/run-tests …)"` twice in
one JVM. Both runs: `Ran 2 tests containing 6 assertions. 0 failures, 0 errors.`

**Hook** (synthetic events in `tmp/contracts-compile-lane/*.json`, live
default pid 9104):
- `pre-bad`: Write of the historical forms to
  `test/seon/contracts_compile_lane_probe_test.clj`. Exit 2, `BLOCKED: this
  edit declares contracts that do not compile …`, with the three findings
  above. The file never landed (`ls` says it does not exist).
- `pre-ok`: the same file with a declared schema, `[:vector :keyword]` and
  `clojure.core/ifn?`. Exit 0.
- `post-db`: PostToolUse on `src/seon/db.clj`. Contract errors = 0.
- Hook config with `:current-source :root` pointing at an empty dir:
  `ADVISORY — contract compile check unavailable: no live root JVM answers,
  so no contract was compiled`.
- Log lines: `CONTRACT_COMPILE | pre | … | available | errors=3 | ms=351` and
  `… errors=0 | ms=351`.

**Carried-projection reuse** (default JVM, check of `src/seon/db.clj`):
- candidate forms equal: 106 ms
- default's carried projection differs (the uncommitted `seon.profile/*`
  resources): 394 ms (rebuild)

## TIMINGS

| operation | wall ms | cache | justification (over 1 s) |
|---|---|---|---|
| hook Pre, clean edit, whole hook (parent HEAD hook) | 154–334 | — | — |
| hook Pre, clean edit, whole hook (this change) | 546–660 | projection rebuild (miss) | — |
| hook Pre contract step (load-file 37, resources 47, projection 243, check 50) | 351–388 | miss | — |
| hook contract step when default's carried projection matches the checkout | 106 | hit | — |
| hook Post on db.clj, whole hook | 1666 | miss | Dominated by the existing clj-kondo lint of a 4,000-line file; the contract step is 393 ms of it. |
| check one file (db.clj), projection in hand | 31–50 | — | — |
| static read of all 423 files (edamame) | 1110–1740 | — | Proportional to all source text. Only the all-files deftest pays it; the hook reads changed files only. |
| packaged projection, warm JVM | 243–280 | — | — |
| packaged projection, first time in a fresh JVM | 6044–6512 | cold | **Defect-class cost, not mine to fix here.** It is first-time `requiring-resolve` of the predicate owners (seon.db …) in a JVM with no dependency class cache. Paid once per JVM. |
| all 1939 contracts compiled, warm | ~30 (check-all 1138–1550 minus the 1110–1629 read) | — | — |
| deftests, warm JVM | 1899 | — | Proportional to the whole-program static read (above). |
| deftests, fresh JVM, first run | 8411 | cold | Includes the 6 s first-time predicate-namespace load above. |
| `require seon.contracts-compile-test` in a fresh JVM | 27980–31229 | **miss: `target/dev-dependency-classes` absent** | **Over 10 s: defect.** It compiles seon.fn's whole dependency graph from source. This is the existing issue `docs/seon/issues/source-load-is-118s-against-the-ten-second-law.md`. The orchestrator folds this row in; lanes do not append to shared issue notes. |
| fresh `clojure -M:dev:test` probe JVM, total | 41710–44560 | miss | Same as above. |

Writer-cost comparison (orchestrator note: 348 ms for 96 contracts). This
check compiles 1939 contracts in about 30 ms once the projection and read are
in hand. The per-hook cost is 351–394 ms including the projection rebuild, or
106 ms when default's carried projection is current.

## Verification limits

- The tests were not run through `bin/test`. Default runs snapshot 76b42f90f,
  whose classpath lacks this namespace (publication paused). `bin/test-fast`
  is deleted in the working tree by another lane. The evidence is plain
  `clojure -M:dev:test` JVMs from the working tree and from a HEAD archive.
- No scratch boot was run (per the rules). Fidelity to the from-zero path is
  argued from the code path: indexer qualification, canonicalization and the
  EDN round trip, then `compilable-form` and `m/function-schema` over the
  packaged registry. It is also shown by the three historical refusals
  reproducing the from-zero messages.
- Not covered:
  - `assert-complete-contract!` for agent-admitted contracts (core contracts
    skip it in `build-projection`).
  - `assert-render-contracts!` for render functions.
  - A schema-resource edit that breaks contracts in *other* files. The hook
    checks only the Clojure files an event changed; the deftest covers the
    whole tree.
- The hook `load-file`s the checker into default under its own namespace
  (never over default's Vars). The live check's `requiring-resolve` of a
  predicate can load one of default's snapshot namespaces, exactly as
  publication does.

RESET NEEDED: no.

## Commit

See the lane report for the commit id.
Paths: `bin/seon-hook`, `test/seon/contracts_compile_test.clj`, this note.
