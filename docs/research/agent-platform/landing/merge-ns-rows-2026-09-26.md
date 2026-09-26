---
type: landing
lane: merge-ns-rows-opus
created: 2026-09-26
status: committed 97826db63 + 6f5fe6abb, unadopted; scope patch applied (d111c309e); root patches digest-rederive + require-ns-source pending; RESET NEEDED; lane stopped
---
# merge-ns-rows: namespace rows merge and write back

What exists: `seon.cluster.source/prepare-merge!` → `accept-merge!` → `write-back!` (continuing
377b308c9). Namespaces are program rows (CLAUDE.md), and `digest-map`/`three-way` already classify
them (`:seon.ns/name` is a compared family; REPL-verified: my branch's `my.plan-test` row came out
`:changed-on-branch`). Only `seon.program/changed-identities` kept them out, by an explicit
"Only function and test declarations may change here" scope (`src/seon/program.cljc:614`).

## Broken or blocked first

1. **A namespace row change leaves every declaration of that namespace with a stale digest.**
   A function or test digest includes its namespace's resolver context
   (`seon.db/definition-digest-of`, `db.clj:4301`). Evaluating `my.plan-test`'s `ns` form plus
   `[my.plan]` on my branch changed the namespace digest (`52cc108f…` → `2a3dca57…`) and left all
   31 declarations (23 tests, 8 fns) with their old digests; the writer did not refuse, because
   `write-digest-error` (`db.clj:4317`) checks only touched fn/test rows. For all 31, the digest the
   writer's own reader derives under the new row (`declaration-value` + `definition-digest-of`)
   equals the analyzer's reindex of the spliced file (31/31), and none equals the stored one.
   Consequence here: write-back's equivalence proof refuses the namespace change `:not-equivalent`
   naming all 31 (32 with the stranded test), so **the milestone cannot finish until the producer
   is fixed**. Issue: `docs/seon/issues/a-namespace-row-change-leaves-its-declarations-digests-stale.md`.
   Decision for root: three options below.
2. **`(require '[x])` at the REPL records the `require` form as the namespace's source**
   (`:seon.ns/source "(require '[clojure.walk])"`; its requires are right). Written back, it would
   replace the `ns` form; the proof refuses it, loudly. Until fixed, agents add requires by
   evaluating the whole `ns` form (verified: the row then holds that form).
   Issue: `docs/seon/issues/a-require-at-the-repl-records-the-require-form-as-its-namespaces-source.md`.
3. **Tests redefine `seon.fn`'s analyzer JVM-wide.** While root ran `--ns seon.fn-test`, my
   read-only probe of `analyze-rows` threw "analysis must not run" (`test/seon/fn_test.clj:1067`,
   `with-redefs [analyzer/analyze …]`; also `publication_inputs_test.clj`). A concurrent write-back
   or publication in default fails the same way. Added as evidence to the existing class issue
   `eval-test-with-redefs-of-packaged-forms-breaks-concurrent-publication.md`.
4. **Nothing ran as tests** (default runs archive `2b4a836f…`; the commit is unadopted). The
   regressions are written, not run.
5. A REPL-evaluated `ns` form drops the row's `:seon.fn/file` (exact replacement of a file-owned
   attribute). Write-back reads the file from the before-state, so it works; recorded, not fixed.
6. The pre-commit contract check with relative `::paths` reads the JVM's cwd, which is the
   archive (`data/source/2b4a…`), not the checkout: pass absolute paths.
   (`fix-merge-basis`'s "0 findings" used relative paths, so it checked the archive's source.)

## What changed (97826db63, plus two patches applied from fix-merge-basis: ead8e2fa0, fbe18cd3f)

- **Merge (1).** With the scope patch, a changed namespace row is part of the three-way delta
  like any row (added / changed / conflict). Selection closure: a selected function or test pulls
  its namespace's row when the candidate changed it
  (`(into selection (filter whole) [[:seon.ns/name (namespace v)] …])`). `replacement` already
  transfers it (7 datoms for `my.plan-test`; `seon.db/with` accepted it, 4.6 ms).
- **Write-back (2).** Write-back's proof is split out as `proven-file` (before, after, identities,
  root → the proven candidate or the refusal). A namespace row's before-span is its recorded `ns`
  form's single exact occurrence in the file (the row records source, not a span); the edit
  splices the merged `ns` form there. Missing or ambiguous → `:span-mismatch`. The expected
  digests of the file are now the **after** commit's for every declaration the file holds
  (before-file identities ∪ written), so re-derived siblings compare exactly; the old
  merge-of-before-and-written map is gone.
- **Gate (3), answered: yes, and it now does.** `prepare-merge!` takes an optional checkout root
  (`my.program/prepare-merge!` `:seon.fn/root`) and runs the same `proven-file` over
  `(seon.db/with head {:tx-data tx})` before building S, so the refusal lands before any test
  runs and before accept. One implementation, two calls: write-back re-proves because the file
  may change between prepare and write-back; reusing the proof keyed by the candidate's digest
  is the follow-up that makes it one analysis (not built; it needs the digest carried through
  proposal → accepted).
- Schemas: `:seon.source/proposal` (replaces the proposal map spelled twice in prepare's contract),
  `:seon.source/proof`, `:seon.source/proof-rows`, `:seon.source/candidate-bytes`.

Size: src+schemas +128/−68 (source.clj +103/−61, most of it `write-back-commits` split into
`proven-file`; my/program.clj +12/−7; seon.source.edn +13). Tests +82 (write_back_test).
Deleted: the proposal map's duplicate spelling, write-back's expected-map merge, the `:one-file`
span clause (moved to `:span-mismatch`).

## REPL evidence (default pids 27723 then 36805, JVM read-only; scratch ns `mnr.scratch` = unarmed copies, `tmp/merge-ns-rows/scratch.clj`)

- Branch `merge-ns-rows-opus` (MCP, retired): the `ns` form with `[my.plan]` settles a namespace
  row, `:agent`, requires include `my.plan`, no `:seon.fn/file`.
- `changed-identities head branch` on HEAD code → `:seon.program/scope` refusal naming
  `[:seon.ns/name my.plan-test]` (the gap). `digest-map` + `three-way` over that identity →
  `:changed-on-branch` (108 ms).
- `proven-file head head #{a sibling test}` (no-op): 413 ms, candidate bytes identical to disk,
  `:seon.source/proof` valid; an absent identity → `:one-file`, a valid `:seon.source/write-back-error`.
- Stranded milestone, test only (before `6ab7ec38…`, after head): `:not-analyzable`
  "Unresolved namespace my.plan" at 611 — 529 ms. This is what prepare now refuses up front.
- Ns only (before head, after `with(head, ns replacement)`): analysis clean, `:not-equivalent` 31,
  the namespace row itself equal — 493 ms.
- Milestone (before `6ab7ec38…`, after the same, identities ns + test): clean, `:not-equivalent`
  32 (31 + the test), namespace row equal — 452 ms.
- Re-derivation check: 31/31 reindexed digests = `definition-digest-of` under the merged row.
- Contract check (absolute paths, checkout projection): 0 findings, ~550 ms; `seon.source.edn` reads.

Cost: one proof ≈ 450–530 ms, all in `seon.fn/analyze-rows` (`analysis-projection` rebuilds a
projection per call, ~350 ms). Prepare + write-back = two analyses ≈ 1 s. Owner of the cost:
`seon.fn/analysis-projection` (memoize on the projection value), not this lane's files.

## Decision: the stale-digest producer (three priced options, simplest first)

1. **(recommended) Re-derive at the writer.** `seon.db/namespace-digests-tx [database ns-name]`
   (a `:db.fn/call`, so it reads the in-transaction value): for each function/test of the
   namespace, `declaration-value` + `definition-digest-of` under the namespace row, `[:db/add e
   :seon.program/definition-digest d]` where it differs. Callers: SCI namespace admission
   (`sci/eval.clj`, one line) and merge `replacement` when the delta holds a namespace row
   (`source.clj`, one line). The writer's `write-digest-error` also checks the declarations of
   any touched namespace row (so the next producer cannot forget). ~25 lines in `db.clj`,
   1–2 hours. Guarantee: stored digests always equal the derivation. Sacrifice: a require change
   marks every declaration of the namespace changed, so the gate reruns every test reaching them
   (`my.plan-test`: its 23 tests).
2. **Narrow the resolver context** to the entries a declaration uses (aliases/refers it
   resolves, required namespaces it names). Still needs option 1's re-derivation, for fewer rows;
   every digest re-derives once (one full rerun). Adding `[my.plan]` then changes only the test
   that names `my.plan`. `program.cljc`, `fn.clj`, `db.clj`; half a day. Smallest gate per future
   change.
3. **Drop the resolver context from function/test digests**; the namespace row's digest carries
   it, and selection/reuse treat a namespace change as changing its declarations. Deletes the
   `stored-namespace-context` plumbing (~40 lines across db/fn/sci.eval) but moves the coupling
   into `seon.test` selection and reuse keys. A day, cross-owner.

## Root: patches, adoption, runs

Patches (`git apply -p0 --check` passes on HEAD):
- `tmp/merge-ns-rows/program-scope.patch` — `changed-identities` admits `:seon.ns/name`
  (`program.cljc`, 3 lines) and `program_test.clj`'s scope assertion becomes "a namespace row is a
  change like any declaration". Required for (1).
- `tmp/merge-ns-rows/ns-form-span.patch` — optional follow-up: file-indexed namespace rows record
  `:seon.fn/form-span` (`fn.clj` +1, `seon.ns.edn` +1; the span is excluded from the digest).
  Once every indexed namespace row carries it, `proven-file`'s byte-occurrence lookup (5 lines)
  is deleted. Note: `lint-rows` then attributes findings inside the `ns` form to the namespace row.

RESET NEEDED (adopt 97826db63 + patches). Then:

```
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
bin/test-check default --test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn
bin/test-check default --test seon.program-test/changed-identities-scope-the-digest-maps-to-one-change
bin/test-check default --test seon.db-test/a-namespace-row-change-re-derives-its-declarations-digests
bin/test-check default --test seon.db-test/one-owner-digests-a-definition-with-its-stored-namespace
bin/test-check default --test seon.sci.eval-test/require-context-rows-carry-namespace-symbols
bin/test-check default --ns seon.cluster.merge-test
bin/test-check default --ns seon.cluster.write-back-test
bin/test-check default --ns seon.cluster.source-test
bin/test-check default --test seon.cluster.host-test/root-reads-a-branch-agents-proposal-and-only-roots-custody-accepts
```

Expected: `write-back-test/a-declaration-naming-an-unrequired-namespace-refuses-at-prepare-before-any-test-runs`
green now; `write-back-test/a-require-added-at-the-repl-merges-and-writes-back-its-ns-form` green once digest-rederive.patch is applied.

## Round 2 (root ruling: option 1; `require` must update the namespace row)

Commits: 6f5fe6abb (my file). Patches for root (`git apply -p0 --check` passes on HEAD d111c309e+):

- **`tmp/merge-ns-rows/digest-rederive.patch`** — `src/seon/db.clj` +34/−5, `test/seon/db_test.clj` +24.
  `prepared-write` (the one writer request, so `transact!` and `seon.db/with` both) appends
  `[:db.fn/call namespace-digests-tx projection namespaces written]` when the transaction writes a
  namespace row (a top-level map with `:seon.ns/name` and its digest). Datahike runs it on the
  in-transaction value (`reference-code/datahike/src/datahike/db/transaction.cljc:1171`); it
  re-derives, with the writer's own reader (`declaration-value` + `definition-digest-of`), every
  function and test of that namespace whose stored digest differs, except declarations the same
  transaction writes (those keep the writer's refusal, so `one-owner-digests-a-definition-with-its-stored-namespace`
  still holds). Regression `a-namespace-row-change-re-derives-its-declarations-digests`.
  REPL (unarmed copy, `tmp/merge-ns-rows/scratch-rederive.clj`): `my.plan-test` + `[my.plan]` →
  the transaction re-derives 32 rows in 8.6 ms (whole `d/with`), and all 32 digests plus the
  namespace's equal the analyzer's reindex of the spliced file (32/32).
- **`tmp/merge-ns-rows/require-ns-source.patch`** — `src/seon/sci/eval.clj` +37/−5,
  `test/seon/sci/eval_test.clj` +4. The producer is `namespace-context-row` (`sci/eval.clj:1160`
  at HEAD), called from `unmap-row` for a form that changes SCI's namespace table without
  declaring (`require`, `alias`, `refer`, `import`); it stored the evaluated form as
  `:seon.ns/source`. New `namespace-source`: a `require`'s new libspecs are spliced into the
  stored `ns` form's `:require` clause, one per line, aligned with the clause (edamame's
  locations; every other byte stays exact); no stored `ns` form → `(ns name\n  (:require …))`;
  any other form keeps the stored `ns` form. `unmap-row` receives the current database instead
  of the source. REPL: `(require '[my.plan])` on `my.plan-test` gives exactly the stored form
  plus `\n            [my.plan]` before its closing parens; an already-required libspec returns
  the stored form unchanged; all outputs conform to `:clojure.core.specs.alpha/ns-form`.
  Limitation: `alias`/`refer`/`import` at the REPL change the facts but keep the stored `ns` text
  (write-back's proof then refuses `:not-equivalent`, loudly).
- **`merge` (6f5fe6abb)**: the gate also runs the tests of every declaration the writer
  re-derived on S (`seon.fn/report-identities` of the S transaction), per the ruling that tests
  reaching the namespace rerun.
- Contract check over source.clj plus both patched files (checkout projection, absolute paths):
  0 findings.

**Whole finish, simulated read-only on the live store** (the branch = head plus the agent's
namespace change with re-derivation; the scope patch live): `changed-identities` = 33 (namespace
+ 32 re-derived); `replacement` of the namespace onto head + re-derivation; prepare's
`proven-file` → proven, the namespace replaced (554 ms); write-back's `proven-file` from base
`6ab7ec38…` with the namespace and the stranded test → proven, 1 replaced + 1 added (479 ms). The
candidate differs from `test/my/plan_test.clj` by exactly `\n            [my.plan]` in the `ns`
form and the appended test (+364 characters).

## Milestone finish (root, after both patches, adoption and the owner's nuke; do not run before)

The nuke removes the stranded merge (the test is in no file), so this is the milestone whole, as an
agent: branch → `require` + `deftest` at the REPL → merge → write-back. All MCP.

1. `eval_clj`, mode `sci`, no branch (root) — the issue on the cluster, so the branch forks with it
   (an issue cannot yet cite a test only the branch holds; the gate runs the added test as a change):
   ```clojure
   (def milestone (my.issue/add! {:seon.agent/id "root" :seon.issue/severity :cleanup
                                  :seon.issue/title "my.plan refuses an unknown agent by name"
                                  :seon.issue/problem "my.plan/current names a missing agent in its refusal; prove it with a test."}))
   (:seon.issue/id milestone)
   ```
2. `branch` create `milestone-plan`.
3. `eval_clj`, mode `sci`, branch `milestone-plan`, namespace `my.plan-test`, two calls:
   ```clojure
   (require '[my.plan])
   ```
   ```clojure
   (clojure.test/deftest current-refuses-an-unknown-agent-by-name
     (let [refusal (my.plan/current {:seon.db/db (seon.db/db) :seon.agent/id "an-agent-that-does-not-exist"})]
       (clojure.test/is (= "an-agent-that-does-not-exist" (:my.plan/missing-agent-id refusal)))
       (clojure.test/is (= :seon.agent/id (:my.plan/refused-member refusal)))))
   ```
4. `eval_clj`, mode `sci`, no branch (root), one run:
   ```clojure
   (def proposal (my.program/prepare-merge! {:seon.store/branch :milestone-plan
                                             :seon.issue/id "<the id from step 1>"
                                             :seon.program/identities #{[:seon.test/sym 'my.plan-test/current-refuses-an-unknown-agent-by-name]}
                                             :seon.fn/root "/Users/sean/src/seon"}))
   (def accepted (my.program/accept-merge! proposal))
   (my.program/write-back! {:seon.source/accepted accepted :seon.fn/root "/Users/sean/src/seon"})
   ```
   Expected: the selected test carries its namespace row (selection closure); the file is proven
   before the gate; the gate runs the test and the tests of the 31 re-derived declarations;
   `accepted` names `#{[:seon.ns/name my.plan-test] [:seon.test/sym …current-refuses…]}`;
   write-back answers `{:seon.source/changed-paths ["test/my/plan_test.clj"] … :replaced
   [[:seon.ns/name my.plan-test]] :added [[:seon.test/sym …current-refuses…]]}`.
5. Bytes: `git diff test/my/plan_test.clj` is exactly the `[my.plan]` line in the `ns` form plus the
   appended test. Then `branch` retire `milestone-plan`, commit that path, publish.

Released paths: src/seon/cluster/source.clj, src/my/program.clj, resources/seon/schemas/{my.program,seon.source}.edn,
test/seon/cluster/{write_back,merge,source}_test.clj, .agents/skills/repl/SKILL.md. Disposable: the JVM scratch
namespace `mnr.scratch` and `user/mnr-*` Vars in default (no default Var redefined); MCP branch retired.
