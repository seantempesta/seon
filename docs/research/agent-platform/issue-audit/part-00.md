---
type: research
status: current
created: 2026-09-24
scope: issue audit part-00 — 109 open-folder issue notes (a-… through cohost-…) read as candidate tasks
---

# Issue audit, part 00: 109 notes as candidate tasks

Input: `tmp/orchestrator/issue-audit/part-00` (109 paths). Data: `part-00.edn` in this
directory, one map per note in input order. I read every note in full. For the relevance
probe, a script pulled each note's cited `seon.*`/`my.*` symbols and repo paths and checked
them with `rg`. For the judgement fields I opened the cited seam in `src/`, `test/` or
`resources/`. No JVM was used, so "live" means the cited code still has the described
shape. It does not mean the defect was reproduced.

## Counts

| field | values |
|---|---|
| frontmatter status | open 107, resolved 2 (both still in the open folder) |
| relevance | live 55, unknown 28, obsolete 26 |
| trigger | review-finding 30, test-failure 20, measurement-over-bound 19, error 17, query-finding 16, staleness 4, design-gap 2, other 1 |
| work | fix 37, test 18, investigate 11, design-decision 10, delete 9, measure 8, refactor 6, docs 6, tooling 2, upstream 2 |
| exit kind | example-test 37, empty-query 20, other 18, property 16, bound-test 9, root-decision 5, measurement 4 |
| size | small 65, medium 28, large 16 (every large has a split) |
| trigger query sketched | 64 of 109 (35 of the 55 live) |
| duplicate-of set | 6 |

Live notes only: exits are example-test 17, empty-query 13, property 11, bound-test 7,
root-decision 3, other 3, measurement 1. Sizes are small 21, medium 21, large 13.

### The 26 obsolete notes fall into two groups

- **Fixed in code, note never closed (14).** In each case the cited seam now carries the
  fix, and often a comment naming the defect: `a-data-only-commit…`
  (`acquired-database?`, 5f2aa93f4), `admit-run-derives…` (`src/seon/test.clj:1036`),
  `adoption-refuses-a-publication-that-changes-no-definition` (`arming-refused?`,
  `cluster.clj:2413`), `as-alias-recorded…` (`fn.clj:262`), `cluster-ctx-delegating…`,
  `an-archive-booted…` (`cluster.clj:2838`), `a-selected-prompt…` (moved to
  `seon.cluster.prompt`), `captured-prompt-history…`, `boot-initialization…search…`,
  `a-call-preparation-facet…` (member now optional), `cluster-named-store…`, the
  half-agent fixture, and two notes marked `status: resolved` that were never moved to
  `archive/` (`a-lookup-ref-pull…`, `analyzer-refuses…`).
- **Premise deleted (12).** The runner rewrite (8bc917872) deleted the launchers, pooled
  workers, published bases and retained canonical fixture (the two focused-JVM notes,
  nonexistent-namespace coordinator, worker contract stripping, cache-reuse, both
  canonical-fixture notes). Other notes lost their subject to a deletion: `seon.search`,
  the activation closure, `bootstrap_drive`, the source-change retry seams, and
  `about-identities`.

**About a quarter of this slice is closable now.** The indexer already reports unresolved
qualified symbols. Two detectors would find most of these without an audit:

- a query for notes whose every cited function or test identity is gone;
- a check for `status != open` under `docs/seon/issues/`.

## Recurring patterns

1. **The trigger is often the exit.** 20 notes end in an empty-query exit, and in most of
   them the query that would have raised the note is also the query that proves it done.
   Examples:
   - stored file rows whose path is missing;
   - required cardinality-many keys in stored schemas;
   - armable minus instrumented Vars;
   - config symbols with no program row;
   - functions reached only from tests;
   - readers of the legacy `:seon.test/pass-count`;
   - universal-only selection maps;
   - `seon.fn/output-path-report` bypasses.

   These are the cheapest tasks to generate: a detector returns a nonempty set, the task
   is "make it empty", and each member can become its own small task.
2. **Measurement notes are profile rows over a bound.** 19 notes carry a wall-clock or
   call-count table: 44 s reconciliation, 12 s one-file refresh, 57 s turn prelude,
   10–15 s admission, 0.55 s edge clause. The root cause repeats. Four notes point at
   `seon.db/carried-projection` or projection re-derivation per read, and several more
   point at whole-program refresh. Only two of these notes are tagged with the class they
   belong to (`class/n9`). A "calls per operation scale with program size" detector over
   the existing wrapper profile would raise all of them.
3. **Armed contract drifts from current contract (5 notes, one invariant).** These are:
   - the cluster arms ten fewer contracts than it declares;
   - adoption leaves namespaces unarmed;
   - adoption keeps the previous contract shape;
   - the cluster handle keeps an old projection;
   - the canonical fixture keeps old contracts.

   One invariant covers all five: loaded Var contract = published row contract = captured
   wrapper, for every armable Var, checked after boot and after every adoption. That is a
   single property-style detector, not five fixes.
4. **Unbounded offending value.** Four notes are one bug: the render refusal of 8.4 MB,
   the call-preparation dump of 79 MB, the resolve-test dump of 128 MB, and the
   coordinator's 19 MB manifest. `seon.instrument` embeds the whole argument.
5. **Absence read as health.** This shape recurs across the slice (tags
   `class/absence-as-health`, `absence-is-not-health`):
   - a dead turn proc reads as idle;
   - a deleted file reads as unchanged;
   - an unfinished run hides every problem family;
   - a wake answered by a turn that died;
   - an absent blocked-plan subject is taken as fixed.

   The detectors are all "work pending or accepted, no terminal fact, no running holder".
6. **Shared-tree lane contention.** Some notes exist only because filesystem lanes shared
   one checkout: `a-db-adoption-refuses…`, the kondo cache lock, the classpath race,
   cluster boot instrumenting working-tree Vars, and the cohost sweep race. The dogfood
   model (agent branches, write-back gate) removes the premise. These should become one
   root decision, not fixes.
7. **Class notes are containers.** 13 class notes cite no code of their own; their only
   evidence is `bin/issues-index --class`. Some list members in folded hand tables, which
   is the hand-list shape the class notes themselves forbid. A class makes a good
   **exit template** (the n8 "9→10 plus permutation" property, the n2 "retain a failing
   counterexample" rule) and a good detector owner. It is not an assignable task.

## Most instructive notes for task design

- `a-deleted-source-file-is-not-a-publication-difference`: the trigger query, the exit
  query and the regression are one Datalog form. This is the target shape.
- `a-stored-entity-schema-requires-a-cardinality-many-key…`: the note derived its own
  25-key inventory mechanically. Arming that derivation as a checker generates the
  per-schema tasks.
- `a-lookup-ref-pull-sorts-the-whole-installed-schema…`: a model evidence chain. It runs
  profile → stack sample → cause in source → smallest fix at the owner (a dependency
  seam, `dbi/-schema`) → a before/after table. It also shows why a task must record the
  residual it did not attribute (55 µs per pull, 30× on `q`).
- `bound-transaction-input-selects-an-older-turn`: a complete repro form inside the note,
  and a dependency bug whose natural exit is a differential property (bound input ≡
  equality predicate).
- `an-agent-cannot-make-its-own-source-edit-live`: the blocking gap for the whole task
  model. An agent could write a correct fix, but no declared call can make that fix
  reach the test that decides done. `src/my/*` still has no adopt/evaluate-on-branch
  surface.
- `cluster-threads-outside-flow-have-unobserved-exits`: already split into four
  file-disjoint, one-regression tasks. This is the right granularity.
- `a-collection-member-refusal-does-not-name-the-members-index`: one function, one
  wanted value (`:seon.error/path [8]`), one regression. It is an ideal first task for a
  small-context agent.
- `class-proofs-pass-without-exercising-their-premise`: task exits are only as good as
  their tests. A `done-when` test with an empty subject or zero trials must refuse, or
  agents will take the shortest green path (toy run 3).
- `call-graph-fidelity-selection-awaits-adopted-proof`: context selection depends on it.
  If agent context is a query over `:seon.fn/calls`, phantom edges put unrelated code
  into every context.

## What does not fit the task model

- **Verify-only work.** Many open notes need no change, only evidence against current
  HEAD: the namespace-page curl, the reset-boundary proofs, "fixed, confirm the
  regression, archive". The model needs a `:verify` work kind and a closing move that
  moves the note, or these notes stay open forever.
- **Exits that are not tests.** Browser paint (`cluster-status-renders…`,
  `browser-ui-observation…`), root rulings (`admit-inst-overlap`, `capability-component…`,
  wake answeredness) and shell tooling (classpath lock) have no deftest or query exit.
  - Root rulings fit the draft's `seon.task/root-decision`.
  - Paint needs a declared observation evidence kind.
  - Shell tooling has no program-graph subject, so the indexer cannot link it.
- **Dependency-fork tasks.** Examples are the gc-guard permit leak and the Datahike
  tx-input binding. The exit test belongs in the fork's own suite, so the task needs the
  fork as its subject, and merging it is a separate pin bump.
- **Ordering between tasks.** Most `:task/needs` entries I filled in are inferred shared
  root causes, not true prerequisites. The model has no "same root cause, fix once" link
  distinct from "must happen first". `duplicate-of` is too strong for that relationship.
- **Detectors that would parse prose.** `class-documentation-restates-executable-contracts`
  wants stale docstrings found, but the design forbids parsing prose. Its exit has to be
  "help is rendered from contracts", not a docstring scan.
- **History-heavy notes.** Many notes are mostly a chain of dated lane sightings. A task
  agent needs the current claim, the seam and the exit. Once a task is generated, the note
  should shrink to that, with the history left in git.
- **Declared exemptions exist and could be reused.** The anonymous-contract census treats
  its "genuinely polymorphic" verdicts as prose, but
  `:seon.schema.admission/exemption` with a `:reason` is already declared on schemas
  (`test/seon/test_support.clj:655`). The census query should subtract those instead of
  a verdict table.

## Verification limits

This audit ran no JVM and no tests. "Obsolete" means the cited seam was changed or deleted
in source. It does not mean the regression was observed green. The 28 "unknown" notes need
a live probe, most often because the cited test or seam was renamed, or because the note
itself says it cannot be verified without the gate.
