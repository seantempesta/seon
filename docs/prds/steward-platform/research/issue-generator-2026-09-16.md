---
type: research
status: current
created: 2026-09-16
tags: [research, steward, issue, detector, generator]
---

# `seon.issue/generate`: detector plus subject identity, measured on `default`

Lane `issue-generator`. Implements
[issue-family-spec §7–§8](../plan/issue-family-spec-2026-09-16.md) and
[detectors-and-standards §4](detectors-and-standards-2026-09-16.md) for the
orchestrator's option B (D1 render pair + D2 docstring only). Read end to end
before the work: `AGENTS.md`, the spec §7–§8, the detectors research, the
[indexer resolver](issue-indexer-resolver-2026-09-16.md) and
[publication cost](issue-index-publication-cost-2026-09-16.md) notes, and the
`data-oriented-clojure`, `datahike` and `data-modeling` skills. Commit
`88b04b970`.

## 1. What landed

`src/seon/issue/detect.clj` (new), `seon.issue/generate` + `generate!`,
`:seon.issue/detector` in `resources/seon/schemas/seon.issue.edn`,
`test/seon/issue_generate_test.clj` (3 regressions).

- **Identity** is `(seon.id/id (into (sorted-map) {:seon.issue/detector '<fn> <identity-attribute> <value>}))`,
  exactly §7. `:seon.issue/id` is a Datahike identity, so a second run upserts.
- **A subject carries exactly one INSTALLED identity attribute.** Zero or two
  is `:seon.issue/subject-without-identity`, which is how `{:db/id 12345}` is
  refused — `:db/id` is not an identity attribute, so the rule that refuses a
  bare entity id is the same rule that requires a stable one.
- **The subject's issue attribute is DERIVED**, not rostered: `generate` reuses
  `citation-attributes`, the resolver's map from an installed identity
  attribute to the issue attribute that declares `:seon.issue/cites` for it. A
  new subject family becomes linkable by declaring `:seon.issue/cites` on one
  attribute and nothing else changes.
- **`:seon.issue/schema` was NOT added.** The spec asked for it; since the spec
  was written, `:seon.issue/keys` landed on `resources/seon/schemas/seon.issue.edn`
  collecting `:seon.schema/key` refs. A second attribute for the same noun is
  the defect AGENTS.md §2.5 names, so a schema-keyed finding is stored in
  `:seon.issue/keys` and queried by `:seon.issue/_keys`, symmetrically with
  `:seon.issue/functions` / `:seon.issue/_functions`.
- **Write rules.** Every run decides `:seon.issue/status`, `/severity`,
  `/detector`, the subject ref and `/namespaces`. `:seon.issue/title` and
  `/problem` are written only when the issue holds none. `/opened` is written
  when the entity is minted. Nothing else is touched: `replacement-tx` gained a
  three-argument arity naming the attributes the caller OWNS, so a worker's
  `:seon.issue/tests`, `/agent`, `/budget` and edited prose survive a run. The
  two-argument arity (indexing a note, adopting one) is unchanged.
- **Resolution.** A subject the detector stops yielding gets `:seon.issue/status
  :resolved` and `:seon.issue/resolved-tx`; a subject that reappears has
  `resolved-tx` retracted and status reasserted. The entity and its identity
  survive both; nothing is deleted.
- **Delta only.** `generate!` derives the transaction first and writes nothing
  when it is empty, the discipline the publication-cost note landed for
  `index!`. The writer still re-derives what it commits through
  `[:db.fn/call #'generate request]`.

## 2. The two detectors, and what their exclusions are made of

| detector | raw read | excluded | subjects on `default` |
|---|---:|---:|---:|
| `entity-map-without-pair` | 77 `:seon.db/attributes true` maps, 45 paired | 16 witnessed components | **32** |
| `public-without-doc` | 37 public source-bearing functions with no `:seon.fn/doc` | 6 not owning their form | **31** |

- **The component exclusion is a declaration plus a witness.** An entity map's
  declared keys come from its stored `:seon.schema/shape` entries; an entity is
  an instance when its whole attribute set lies inside those keys (an entity IS
  its attributes); the map is excluded when one instance is the value of an
  attribute the owner declared `:seon.db/component`. It excludes exactly the
  `*/row`, `seon.fn.ast/node`, `seon.error.occurrence/occurrence`,
  `seon.issue.citation/citation` and `seon.test.failure/failure` family the
  research named, in 9–12 ms, with no name pattern anywhere.
  - `every?`-instead-of-`some` was measured and rejected: 542 of 9,023
    `seon.fn.ast/node` entities are reachable from no component attribute at
    all, so requiring every instance would keep the whole family as subjects.
    That orphan population is filed as
    [orphan-ast-nodes-outlive-the-declaration-that-held-them](../../../seon/issues/orphan-ast-nodes-outlive-the-declaration-that-held-them.md).
  - `seon.error.occurrence/blob` stays a subject because its holder
    `:seon.error/data-blob` is a ref that is NOT declared `:seon.db/component`,
    although it behaves as one. That is a schema-declaration gap the detector
    correctly reports rather than papers over — declaring the component both
    fixes the retraction semantics and resolves the issue on the next run.
- **The form-ownership exclusion**: six functions share three
  `[:seon.fn/file :seon.fn/form-span]` pairs — the `defrecord` constructors
  `->Environment`/`map->Environment`, `->Raw`/`map->Raw`,
  `->CountedDroppingBuffer`… — and no docstring can be attached to a function
  that does not own its defining form. Six of these are the WHOLE set of shared
  spans in the graph, so the rule costs nothing else.
- **D2 is deliberately NOT scoped to production code**: 25 of its 31 subjects
  are public helpers in namespaces that declare deftests. No fact separates
  `src/` from `test/`, and inventing a name rule is a banned substitute, so the
  over-report is honest and the missing fact is filed as
  [the-program-graph-cannot-say-which-source-root-a-declaration-came-from](../../../seon/issues/the-program-graph-cannot-say-which-source-root-a-declaration-came-from.md).
  This is why the run is 63 issues rather than the ≈90 the assignment
  estimated: the research's 51 unpaired maps are 45-paired/32-subject today,
  and its 39 docstring-less functions are 37 with 6 unfixable.

## 3. Live proof on `default` (pid 45917, never stopped, reforked or restarted)

`jvm` mode, explicit custody `(seon.operator/connection "default")`.

| measurement | value |
|---|---|
| first run, `entity-map-without-pair`, severity `:friction` | 32 tx forms, 32 issues, 32 open, **1,941 ms** |
| first run, `public-without-doc`, severity `:cleanup` | 31 tx forms, 31 issues, 31 open, **409 ms** |
| second run of both, immediately after | **0 forms, 0 forms**; basis `:t` 536871343 → 536871343, i.e. no transaction at all |
| detector derivation alone | D1 ≈ 760 ms cold / 12 ms warm, D2 15 ms |

Resolution and re-open, proven on database VALUES with `datahike.api/with` so
nothing false was committed to `default`'s branch — the subject
`my.agents.root/largest`, issue entity **63261**:

```clojure
;; give the function a docstring -> the detector stops yielding it
(seon.issue/generate db1 request)
;; => 2 forms; entity 63261 is now {:seon.issue/status :resolved
;;                                  :seon.issue/resolved-tx {:db/id 536871345}}
;; retract the docstring -> the finding comes back
(seon.issue/generate db3 request)
;; => [[:db/retract 63261 :seon.issue/resolved-tx]
;;     [:db/retract 63261 :seon.issue/status]
;;     {:db/id 63261 :seon.issue/status :open}]
;; entity 63261 throughout: one identity, resolved and reopened, never deleted.
```

A hand-edited problem survives: writing `:seon.issue/problem "A human wrote
this."` onto entity 63261 and re-deriving returns **0 forms**, and the problem
reads back unchanged.

The steward's two pulls (real output, `default`, after the run):

```clojure
(seon.db/pull db '[:seon.fn/sym {:seon.issue/_functions [:seon.issue/id :seon.issue/status :seon.issue/title
                                                         {:seon.issue/detector [:seon.fn/sym]}]}]
              [:seon.fn/sym "my.agents.root/largest"])
;; => {:seon.fn/sym "my.agents.root/largest"
;;     :seon.issue/_functions [{:seon.issue/id "7cf1077d99bb"
;;                              :seon.issue/status :open
;;                              :seon.issue/title "Public function my.agents.root/largest carries no docstring"
;;                              :seon.issue/detector {:seon.fn/sym "seon.issue.detect/public-without-doc"}}]}

(seon.db/pull db '[:seon.schema/key {:seon.issue/_keys [:seon.issue/id :seon.issue/severity :seon.issue/title]}]
              [:seon.schema/key :seon.lint/finding])
;; => {:seon.schema/key :seon.lint/finding
;;     :seon.issue/_keys [{:seon.issue/id "7c87ed61550b" :seon.issue/severity :friction
;;                         :seon.issue/title "Entity map :seon.lint/finding declares no render pair"}]}
```

**`seon.render.web` has no generated issue**, which the assignment asked for
explicitly: every public function in it carries a docstring and every entity map
in the `seon.render.web` keyword namespace is either paired or a component. The
requested pull returns `[]`, and that is the finding, not an error. The 63
issues' top responsible namespaces through `:seon.issue/functions → :seon.fn/ns`
are `seon.instrument-test` 8, `seon.cluster.source-test` 5,
`seon.contracts-fixture` 5, `seon.schedule-test` 3, `seon.flow` 2,
`seon.sci.admit-test` 2, `seon.effect-test` 2 — the test-helper skew §2 files.
Schema-keyed issues carry a `:seon.issue/namespaces` ref only for the 6 of 32
whose keyword namespace has a `:seon.ns/name` entity.

## 4. Verification boundary — read this before trusting the slice

- **The three regressions were NOT run.** `default`'s shared
  `seon.test-support/database-base` was realized before `:seon.issue/detector`
  was declared: the fixture branch does not install the attribute
  (`(contains? (:schema (schema-database base-db)) :seon.issue/detector)` is
  `false`), so every write in `test/seon/issue_generate_test.clj` refuses there.
  Per the standing rule the shared base was NOT rebuilt. A cold worker JVM
  installs it from resources. Gate request:
  `tmp/orchestrator/gate-requests/issue-generator.txt`.
  Everything the three regressions assert was instead proven live in §3, and
  the seed the tests use (`example.probe/alpha` without a docstring,
  `example.probe/beta` with one, plus the detector's own `:seon.fn/sym` row)
  was exercised against a real database value: the detector yields alpha, skips
  beta, and `generate` links the subject and its namespace as refs.
- **Adoption of `src/seon/issue.clj` did not converge**, four attempts over
  90 minutes. The first two were refused with "Source changed while … was being
  analyzed" (three lanes publishing at once); the last two were refused by a
  FOREIGN blocking analysis error,
  `test/seon/test_failure_facts_test.clj — seon.problems/problems is called
  with 1 arg but expects 2` (row 166, then row 170 as that lane kept editing),
  against a `seon.problems/problems` whose declared arglist is now `[db
  _request]`. Publication analyses the whole tree, so no lane can adopt
  while that arity error stands. The running JVM does hold this lane's
  definitions (loaded into the host) and the `:seon.issue/detector` attribute IS
  installed on `default` from the hook's earlier schema publication — that is
  why §3's measurements are real — but `default`'s `:seon.source/commit-id` does
  NOT yet name this commit. Re-run
  `bin/seon init --dev default --changed src/seon/issue.clj` once the foreign
  arity error is gone.
- Nothing else was touched: no test JVM was launched, `default` was never
  stopped, reforked or restarted, and the only writes to its branch are the 63
  issue entities of §3.

## 5. Left undone, and why

- **A generated issue carries no `:seon.issue/tests`**, so `seon.issue/start!`
  refuses it. Minting a `:seon.test` row for a deftest nobody has written would
  be a fabricated program fact on the identity the runner selects by, so the
  required test is stated in `:seon.issue/problem` as the acceptance instead,
  and a generated issue's completion is decided by its DETECTOR. Filed as
  [generated-issues-carry-no-tests-so-start-refuses-them](../../../seon/issues/generated-issues-carry-no-tests-so-start-refuses-them.md)
  for the issue-settlement lane, which owns `start!`.
- **Running the generators from the adoption path** (beside
  `seon.cluster.source/index-issues!`) is not wired: the counts should be
  watched by hand for a few days first, per the research's running order. A run
  is `(seon.issue/generate! {:seon.db/connection … :seon.issue/detector "…"
  :seon.issue/severity …})`.
- The remaining detectors (D3 reaching test, D5 generator, D7 test subject, D8
  steward) are untouched; D3 and D8 in particular would add 303 + 431 subjects
  and need the source-root fact above first.
