---
type: landing
status: 4 commits (R0, N5, N6, root's private-definition ruling), code only under the load rule; N1, N3, N4, DRIFT and CFG stopped with evidence
lane: fix-harness-fallout-opus
created: 2026-09-25
authority: docs/research/agent-platform/red-baseline-census-2026-09-25d.md (R0, N1, N3, N4, N5, N6, DRIFT; root's CFG follow-up)
---

# Lane fix-harness-fallout (2026-09-25)

## Broken or unproven first

- **Nothing here has run** (load rule). clj-kondo reports 0 errors on the three touched files. Every probe was a
  read-only JVM form in the throwaway namespace `fix-harness-scratch` on default (pid 35446). Root's runs below are
  the first proof.
- **Neither class regression is committed** (R0, and the private-definition ruling). They belong in
  `test/seon/test/first_load_arming_test.clj` and `test/seon/sci/eval_test.clj`, which are not on my row; exact texts
  are below. The existing render tests `renderer-invocation-is-sci-only-and-live-var-backed` (B2) and the generic-renderer
  member (7919) exercise the ruling's class today.
- **The R0 class regression, specifically, is not committed.** It belongs in `test/seon/test/first_load_arming_test.clj`, which is
  not on my row. Its exact text is below, so root can grant the file or hand it to that file's owner.
- **N1: option B cannot work as written. I stopped there.** `with-database` has bound its own connection's custody
  since 678009fcd (09-22), at `run-database-body` (test_support.clj:602). That binding is what refuses these
  members. The three members whose first line is known all nest two `with-database` calls and write the OUTER
  connection inside the INNER body's custody: config-test `two-clusters…` (config_test.clj:671–684), and sci.eval-test
  `acquired-source-context-forks…` (:487–493) and `evaluation-custody…` (:1780–1786). `seon.db/foreign-connection-error`
  (db.clj:457) refuses them whether or not S4a exists.
  - **Evidence that S4a did not cause them:** run `000b6410d752` recorded the identical refusal for `two-clusters…`
    at 2026-09-24T20:28:31Z. db7f1c919 (S4a) was committed at 2026-09-25T02:15:43Z, six hours later.
  - `my.catalog/reads-canned-label`'s failure text lived only on the unlinked fixture branch (run `ac7b686cf2f7`).
    It is unattributed.
  - **Smaller alternative, for root to rule on:** the fixture writers `test-support/apply-config!`,
    `seed-cluster!` and `transacted!` name their connection explicitly. They could run under
    `(db/call-with-custody {:seon.db/connection connection} …)`, about 6 lines in test_support.clj. That fixes the
    two eval-test members, which use `seed-cluster!`. config-test calls `config/apply!` directly, so it needs its
    own one-line change to `test-support/apply-config!` in config_test.clj, a file I do not own.
- **N3: the world overlay never reads the checkout.** I verified this by reading. `program-overlay-tx`,
  `checkpoint-head`, `open-world!`, `prepare-checkpoint!` and `clean-request-handle` (test.clj:1544–1760) read only
  database values (`source/database`, `db/carried-projection`, `projection-from-database`, which is
  `db/carried-projection`, schema.clj:3286). test.clj contains no `packaged-forms` or `io/resource` call. The six reds
  have other readers:
  - **contracts-compile ×2:** the test's own subject. `packaged-projection` (contracts_compile_test.clj:244) compiles
    "the checkout's schema resources" by design. A parked uncommitted patch correctly fails it. The verdict is
    RERUN on a clean tree, with no fix.
  - **turn-test ×2 and edit-test ×2:** program-row paths that fall back to the authored resources on disk:
    - `seon.program/shapes` → `authored-shapes` (program.cljc:276–304);
    - `identity-attributes` (program.cljc:25);
    - `seon.fn/declaration-forms` (fn.clj:1490–1502).

    This is the open issue `docs/seon/issues/live-resources-outrun-the-loaded-program-identity-list.md`. None of
    these files is mine.
- **N4: the census's mechanism is refuted, and install-function-from-database! is unchanged.** The failing tests
  build their override as `(assoc (program-fn-row 'seon.id/valid?) :seon.fn/source "…")`. That row keeps the
  canonical row's stored `:seon.program/definition-digest`.
  - Probe: the admitted row's `row-definition-digest` is `c29f0e66…`, equal to the stored digest. Recomputed from
    the new source it is `302120263…`.
  - The writer admits the stale digest, so every digest comparison (acquisition's `function-digests`,
    `install-row!`'s `overridden-row?`, and the S4b lazy path) correctly reads "unchanged".
  - The test fix is `program-fn-row database sym source`, which analyzes the source and produces its digest. It
    goes in eval_test.clj, which fix-test-rows-d-opus owns.
  - The source defect is that the writer accepts a stored definition digest that disagrees with its row. That
    fails the law "validate final owning values".
    **Proposed issue** (not written, off my row): `docs/seon/issues/the-writer-admits-a-definition-digest-its-row-does-not-produce.md`.
  - `base-context-bindings…` predates S4b: run `c7279e24aaba` at 00:59Z was red with the same 2 failures, and S4b
    landed at 02:53Z. It is not N4.
- **DRIFT: not done; not small.** `ambient-snapshot` (runner.clj:604) is JVM-global. `seon.contracts-compile-test/check`
  appeared in `::snapshot-instrumented` during an error-test member, which means another thread loaded and armed a
  namespace. Excluding wrappers of namespaces absent at entry would also hide a member's own leak, so it needs a
  ruling on thread ownership of arming. I did not change it.
- **CFG (root's follow-up): stopped. It crosses five files I do not own.** `projection-state` (eval.clj:173–183)
  invents `"current-src"`. Its callers that know their cluster name are:
  - cluster/agent.clj:959 and :978 (the handle's `:seon.cluster/name`);
  - cluster/boot.clj:237 and :250 (`cluster-name`);
  - cluster.clj:1848 (the published source branch);
  - eval.clj:2525, :2936 and :2973;
  - test.clj:1662;
  - instrument_test.clj:419 and db_test.clj:380.

  Making the name a required argument at those call sites is about 15 lines, but only 4 of the sites are in my
  files. cluster-ctx's 1-arity and 2-arity (about 20 test callers) would take it from an optional
  `:seon.boot/cluster-name` in the 4-arity's arm-request, or from their caller. Deriving it from "the database's one
  cluster row" is not total: `evaluation-custody` holds two clusters. fix-test-rows-d already moved five CFG members
  onto `test-support/fork-cluster-ctx` (1e23bc1c6). Root rules which files to grant.
- RESET NEEDED: none.

## Changes

| commit | file | + / − | what |
|---|---|---:|---|
| 9ebdc9cd4 | `src/seon/test.clj` | +17 / −6 | R0: `arm-first-loads!` compares against, and arms with, the program `seon.sci.eval/loaded-identity` names now (`source/database` of that commit, from the ctx's store). Before, it used the ctx snapshot's `::loaded-database`, which is the acquisition's value and stays behind a later adoption. An identity naming no commit is the booted program the snapshot acquired. An unknown identity answers its `:seon.test/not-runnable-error`, which is added to the output contracts of `arm-first-loads!` and `run`. |
| 829d6909d | `test/seon/test/clean_world_test.clj` | +19 / −4 | N5: this member reaches `effect/request*` (see below), so by the owner ruling of 2026-09-25 its world holds exactly its attempt agent and turn. The test names them from `effect/*request-context*`, asserts they are the only agent and turn rows, and excludes their two transactions from the added-entities check. |
| 9f82663e1 | `src/seon/sci/eval.clj` | +7 / −2 | Root ruling: `install-function-from-database!` keeps a definition the context already made for the symbol. An unchanged row binds its compiled root only when the ctx's SCI namespace holds no entry for it. Probe: in default's ctx, 0 of 1,306 uninstalled program symbols have an SCI entry and 3,549 of 3,549 installed ones do, so a present entry is the ctx's own. Overridden or interpreted rows still interpret their source (the ruling names the compiled root only). |
| 50b0e82bc | `test/seon/test/seed_preparation_test.clj` | +7 / −6 | N6: seeds write `:seon.test/fixture-observation`, an installed string attribute with no identity. `:seon.test/skip-reason` is a map member, and it is installed nowhere, including default (probe: `(get (d/schema db) :seon.test/skip-reason)` returns nil). |

Net: +50 / −18 across four files.

## Evidence (read-only probes, default pid 35446)

- **R0 staleness, live now:**
  - The ctx snapshot's `::loaded-database` is commit `6ab5ec54`.
  - `seon.sci.eval/loaded-identity` is `6ab687a9`, and so are the adoption record and `loaded-program(loaded-source)`.
  - The helpers blocked at 03:xx no longer differ, because a restart and an adoption moved both commits. The class
    remains: any adoption after acquisition leaves the snapshot behind.
  - Cost of the new read: `source/database` plus `carried-projection` of the loaded commit took **44.7 ms**
    (2,092 contracts). It is paid only when a request loads a test namespace for the first time. Otherwise the
    function returns before any read, as before.
- **Why clean-world reaches effects:** among the test's calls, only `seon.cluster.boot/connection` is in
  `(seon.fn/reverse-closure {:seon.fn/seeds #{'seon.effect/request*}})`. The path runs through its `refuse!` error
  path. The gate set has 465 tests and took 54.6 ms.
  - The reach is static and ruled, so the world is right and the test's premise was stale. Whether reach should be
    that wide (every refusal path) is still root's ruling (census N5).
  - `my.catalog/a-hidden-call…` lives in effect_attempt_test.clj, which is not mine.

## Proposed R0 class regression (for `test/seon/test/first_load_arming_test.clj`)

```clojure
(deftest a-stale-acquisition-snapshot-does-not-decide-a-first-load
  ;; The comparison reads the program `seon.sci.eval/loaded-identity` names, never the ctx's snapshot.
  (test-support/with-database
   (fn [connection]
     (let [fixture 'seon.test.first-load-branch-fixture
           function 'seon.test.first-load-branch-fixture/identity-int
           ctx (:seon.sci.eval/ctx (test-support/execution-handle connection))
           _ (test-support/transacted!
              connection
              [(test-support/program-fn-row
                (db/db connection) function
                "(defn identity-int {:malli/schema [:=> [:cat :string] :string]} [value] value)")])
           changed (db/db connection)
           stale (assoc ctx :seon.sci.kernel/program-snapshot
                        (atom (assoc @(:seon.sci.kernel/program-snapshot ctx)
                                     :seon.sci.eval/loaded-database changed)))
           refusal (#'seon.test/arm-first-loads! changed (test-support/effective-config) stale #{fixture})]
       (is (= function (:seon.sci.eval/refused-function refusal)) (pr-str refusal))
       (is (nil? (find-ns fixture)) "a snapshot agreeing with the branch neither loads nor arms")))))
```

Without the fix, the snapshot equals the branch, so the fixture loads and arms and both assertions fail. With the
fix, the loaded commit keeps the `:int` contract and the request refuses by name.

## Proposed private-definition class regression (for `test/seon/sci/eval_test.clj`)

```clojure
(deftest a-context-definition-survives-its-symbols-first-install
  (test-support/with-database
   (fn [connection]
     (let [ctx (test-support/fork-cluster-ctx connection)
           function (first (remove @(::kernel/installed-functions ctx)
                                   (sort (keys (:functions @(::kernel/program-snapshot ctx))))))
           namespace-name (symbol (namespace function))]
       (is (some? function) "the subject: a program symbol this context has not installed")
       (sci/binding [sci/ns (sci/create-ns namespace-name)]
         (sci/eval-form ctx (list 'def (symbol (name function)) ::private-definition)))
       (kernel/ensure-function! ctx (db/db connection) function)
       (is (= ::private-definition @(sci/resolve ctx function))
           "the first install keeps the context's own definition")))))
```

## Root runs (serially, after adopting 9ebdc9cd4, 829d6909d, 50b0e82bc and 9f82663e1)

    bin/test-check default --ns seon.test.first-load-arming-test
    bin/test-check default --ns seon.problems-test
    bin/test-check default --ns seon.render.web-debug-test
    bin/test-check default --ns seon.schema-usage-guard-test
    bin/test-check default --ns seon.test-failure-facts-test
    bin/test-check default --ns seon.render-simplification-test
    bin/test-check default --changed seon.test/run
    bin/test-check default --ns seon.test.clean-world-test
    bin/test-check default --ns seon.test.seed-preparation-test
    bin/test-check default --ns seon.contracts-compile-test          ; N3 rerun on a clean tree
    bin/test-check default --ns seon.render-simplification-test      ; private-definition ruling
    bin/test-check default --ns seon.render.retained-test            ; private-definition ruling
    bin/test-check default --changed seon.sci.eval/install-function-from-database!

For R0, a regression from this lane names `arm-first-loads!`, `:seon.test/not-runnable` or `source/database`
("the adopted source commit is unavailable").

Released paths: `src/seon/test.clj`, `test/seon/test/clean_world_test.clj`, `test/seon/test/seed_preparation_test.clj`
`src/seon/sci/eval.clj` and this note. I did not edit `src/seon/test/runner.clj` or `test/seon/test_support.clj`;
they are released unchanged.
