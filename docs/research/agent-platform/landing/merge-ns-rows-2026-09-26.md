---
type: landing
lane: merge-ns-rows-opus
created: 2026-09-26
status: committed 97826db63, unadopted; RESET NEEDED (adoption); two root patches; one producer decision for root (three options below)
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
bin/test-check default --ns seon.cluster.merge-test
bin/test-check default --ns seon.cluster.write-back-test
bin/test-check default --ns seon.cluster.source-test
bin/test-check default --test seon.cluster.host-test/root-reads-a-branch-agents-proposal-and-only-roots-custody-accepts
```

Expected: `write-back-test/a-declaration-naming-an-unrequired-namespace-refuses-at-prepare-before-any-test-runs`
green now; `write-back-test/a-require-added-at-the-repl-merges-and-writes-back-its-ns-form` **red
until the producer decision lands** (its last assertion is the stale-digest class).

## Milestone finish (root, after the patches, option 1 and adoption; do not run before)

The branch agent adds the require by evaluating the whole `ns` form (issue 2). The merged test
(`my.plan-test/current-refuses-an-unknown-agent-by-name`) is already on the cluster since the
stranded merge; the write-back carries it with the namespace row from base
`6ab7ec38-bee7-5781-9bb7-c118831a2698` (test absent there; `plan_test.clj`'s recorded digest
`1eddf6b0…` must still equal the disk bytes).

1. MCP `eval_clj`, mode `sci`, no branch (root) — an issue on the cluster before the branch forks:

   ```clojure
   (def milestone (my.issue/add! {:seon.agent/id "root" :seon.issue/severity :cleanup
                                  :seon.issue/title "my.plan-test requires my.plan"
                                  :seon.issue/problem "The merged test calls my.plan/current; its namespace does not require my.plan."
                                  :seon.issue/tests #{[:seon.test/sym 'my.plan-test/current-refuses-an-unknown-agent-by-name]}}))
   (:seon.issue/id milestone)
   ```

2. MCP `branch` create `milestone-plan-ns`.
3. MCP `eval_clj`, mode `sci`, branch `milestone-plan-ns`:

   ```clojure
   (ns my.plan-test
     "The agent-owned plan component tree, its derivation, and its two projections."
     (:require [clojure.edn :as edn]
               [clojure.string :as str]
               [clojure.test :refer [deftest is testing]]
               [my.plan]
               [seon.plan :as plan]
               [seon.config :as config]
               [seon.db :as db]
               [seon.error :as error]
               [seon.env :as env]
               [seon.render :as render]
               [seon.schema]
               [seon.sci.eval :as sci.eval]
               [seon.test-support :as support]))
   ```

4. MCP `eval_clj`, mode `sci`, no branch (root), one run:

   ```clojure
   (def proposal (my.program/prepare-merge! {:seon.store/branch :milestone-plan-ns
                                             :seon.issue/id "<the id from step 1>"
                                             :seon.program/identities #{[:seon.ns/name 'my.plan-test]}
                                             :seon.fn/root "/Users/sean/src/seon"}))
   (def accepted (my.program/accept-merge! proposal))
   (my.program/write-back!
    {:seon.source/accepted (assoc accepted
                                  :seon.program/identities #{[:seon.ns/name 'my.plan-test]
                                                             [:seon.test/sym 'my.plan-test/current-refuses-an-unknown-agent-by-name]}
                                  :seon.source/base-commit-id #uuid "6ab7ec38-bee7-5781-9bb7-c118831a2698")
     :seon.fn/root "/Users/sean/src/seon"})
   ```

   Expected: the proposal passes (file proven before its tests), accept answers `#{[:seon.ns/name my.plan-test]}`,
   write-back answers `{:seon.source/changed-paths ["test/my/plan_test.clj"]
   … :replaced [[:seon.ns/name my.plan-test]] :added [[:seon.test/sym …current-refuses…]]}`.
5. Bytes: `git diff test/my/plan_test.clj` is exactly `+            [my.plan]` in the `ns` form and
   the appended test; then MCP `branch` retire `milestone-plan-ns`, commit that path, publish.

Released paths: src/seon/cluster/source.clj, src/my/program.clj, resources/seon/schemas/{my.program,seon.source}.edn,
test/seon/cluster/{write_back,merge,source}_test.clj, .agents/skills/repl/SKILL.md. Disposable: the JVM scratch
namespace `mnr.scratch` and `user/mnr-*` Vars in default (no default Var redefined); MCP branch retired.
