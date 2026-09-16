---
type: research
status: active
date: 2026-09-16
tags: [research, steward, issue, detector, schema]
---

# Detectors and standards — exact contracts, identities and live counts

Assignment: [R4](../../../../tmp/orchestrator/wave3/research/R4-detectors-and-standards.md).
Read end to end before probing: [AGENTS.md](../../../../AGENTS.md);
[issue-family-spec](../plan/issue-family-spec-2026-09-16.md) §7–§8;
[namespace-data-model](../plan/namespace-data-model-2026-09-16.md) §1, §3, §8;
[data audit A](data-audit-a-2026-09-15.md) chains 1–7; `src/seon/issue.clj`;
`src/seon/fn.clj`; `src/seon/schema.clj`; `src/seon/test/accretion.clj`;
`resources/seon/schemas/seon.issue.edn`. Research only: no source edited, no
test JVM launched, every number below is a read-only MCP jvm-mode probe on
`default` with explicit custody
(`(seon.db/db (seon.operator/connection "default"))`), basis **536871978**.
The exact forms and their returned values are the committed companion
[probe file](detectors-and-standards-probe-2026-09-16.clj).

## 1. The checklist per entity kind, as detector contracts

Every detector is an ordinary function of ONE database value returning
subjects that each carry their identity attribute and value — never a bare
entity id, never a message string. The shared contract shape is

```clojure
[:=> [:cat :seon.db/database-value] [:or [:vector <subject-shape>] :seon.error/value]]
```

and the subject shapes are `[:map [:seon.schema/key :seon.schema/key]]`,
`[:map [:seon.fn/sym :seon.fn/sym]]`, `[:map [:seon.test/sym :seon.test/sym]]`,
`[:map [:seon.ns/name :seon.ns/name]]`, `[:map [:seon.error/signature :seon.error/signature]]`.
Identity is `(seon.id/id (into (sorted-map) {:seon.issue/detector '<fn> <identity-attr> <value>}))`
per [issue-family-spec §7](../plan/issue-family-spec-2026-09-16.md).

### Schema entities

| # | Checklist item | Detector (contract subject shape) | Identity parts | Required test template | Responsible namespace | Priority |
|---|---|---|---|---|---|---|
| D1 | An entity map declares an AI/HTML pair | `seon.issue.detect/entity-map-without-pair` → `[:vector [:map [:seon.schema/key :seon.schema/key]]]` | detector + `:seon.schema/key` | `(deftest ~(symbol (str (name k) "-renders-through-its-declared-pair")) (with-database [db] (let [unit {:seon.db/db db :seon.render/value <fixture entity>}] (is (string? (seon.render/ai-of ...))) (is (vector? (seon.render/html-of ...))))))` | keyword namespace of the key | 3 |
| D5 | A declared schema used by a public contract can generate values | `seon.issue.detect/contract-without-generator` → `[:vector [:map [:seon.fn/sym :seon.fn/sym] [:seon.schema/key :seon.schema/key]]]` (reuses `seon.test.accretion/generatable?`, stored as `:seon.schema/generatable?`) | detector + `:seon.fn/sym` | `(deftest …-auto-checks (is (true? (seon.test.accretion/generatable? <key>))))` plus the function's own auto-check | `:seon.fn/ns` of the function | 5 |

### Functions

| # | Checklist item | Detector | Identity parts | Required test template | Responsible namespace | Priority |
|---|---|---|---|---|---|---|
| D6 | An open error has a regression naming its signature | `seon.error/recurring-without-regression` → `[:vector [:map [:seon.error/signature :seon.error/signature]]]` | detector + `:seon.error/signature` | `(deftest …-does-not-recur (is (nil? (:seon.error/kind (<call that raised it>)))))` carrying `^{:seon.test/error-signatures [sig]}` | `:seon.fn/ns` of `:seon.error/fn` | **1** |
| D3 | A public function is reached by a test | `seon.fn/functions-without-tests` (EXISTS, `src/seon/fn.clj:939`) wrapped to return subject maps | detector + `:seon.fn/sym` | `(deftest …-behaves (is (= <expected> (<sym> <args>))))` with `^{:seon.test/subject '<sym>}` | `:seon.fn/ns` | **2** |
| D4 | A contract has no unjustified permissive position | `seon.fn/permissive-contracts`, reusing `seon.schema.internal/permissive-positions` (`src/seon/schema/internal.cljc:21`) over `:seon.fn/spec` | detector + `:seon.fn/sym` | `(deftest …-refuses-the-wrong-shape (is (= :seon.error/… (:seon.error/kind (<sym> <bad>)))))` | `:seon.fn/ns` | 4 |
| D2 | A public function has a docstring | `seon.fn/public-without-doc` | detector + `:seon.fn/sym` | `my.examples-test` extension: the docstring's example evaluates in the canonical agent context | `:seon.fn/ns` | 6 |

### Tests

| # | Checklist item | Detector | Identity parts | Required test template | Responsible namespace | Priority |
|---|---|---|---|---|---|---|
| D7 | A test names the namespace it exercises | `seon.test/without-namespace-under-test` | detector + `:seon.test/sym` | the fix IS the fact: `^{:seon.test/subject '<fn>}` on the deftest, proven by `(is (seq (seon.fn/tests-reaching db "<fn>")))` | `:seon.test/ns` → `:seon.ns/name` | 4 |
| D9 | A test reaching an expensive fixture declares its observation | `seon.test/expensive-without-observation`, reusing the runner's own demand derivation (`src/seon/test/runner.clj:697`) | detector + `:seon.test/sym` | `(is (string? (:seon.test/fixture-observation (seon.db/pull db … [:seon.test/sym s]))))` | `:seon.test/ns` | 7 |

### Namespaces

| # | Checklist item | Detector | Identity parts | Required test template | Responsible namespace | Priority |
|---|---|---|---|---|---|---|
| D8 | A namespace has a steward | `seon.ns/without-steward` → `[:vector [:map [:seon.ns/name :seon.ns/name]]]` | detector + `:seon.ns/name` | `(is (some? (:seon.ns/steward (seon.db/pull db [:seon.ns/steward] [:seon.ns/name '<ns>]))))` | the namespace itself | 8 |

## 2. Live counts on default (basis 536871978)

| Detector | Live subjects | Population it is measured against | Probe |
|---|---|---|---|
| D1 entity map without a pair | **51** | 76 `:seon.db/attributes true` schema entities, 25 paired | probe 1 |
| D2 public function without a docstring | **39** | 1,137 public source-bearing functions | probe 2 |
| D3 public function without a reaching test | **303** | same 1,137; whole-graph derivation took **193 ms** | probe 5 |
| D4 contract with an unjustified permissive position | **0** | 1,066 public functions with a stored `:seon.fn/spec` | probes 3–4 |
| D5 contract blocked from auto-check by a non-generatable schema | **205** | 172 schema rows with `:seon.schema/generatable? false` | probe 6 |
| D6 recurring error without a regression | **3** (of 4 signatures; occurrence counts 2, 2, 2, 1) | the post-refork error population | probe 6 |
| D7 test with no first-party direct call | **135** | 1,779 tests | probe 6 |
| D8 namespace without a steward | **431** | 437 namespaces (6 stewards) | probe 2 |
| D9 expensive test without a fixture observation | **0** | 54 tests whose fixture demand is real; 55 observations declared | probe 8 |

Three probes disagreed with the assignment's working hypotheses, and each
disagreement is the finding:

- **D4 is empty, not large.** Audit A reported "205 unjustified positions on
  179 functions" with the explicit caveat that the helper was concurrently
  edited and refs were unexpanded. Running the schema owner's own
  `permissive-positions` over every stored public contract returns **zero**
  unjustified positions; the falsification probe proves the inspector still
  fires and marks `seon.schema/malli-form?`'s `:any` slot
  `:seon.schema/justified? true`. The standard already holds. Do not open 179
  issues from the stale number.
- **D9 is empty, not 782.** The naive database reach over the runner's three
  fixture owners yields 837 expensive tests and 782 without an observation.
  That is an artifact: `with-database` has an OPTIONAL fresh-store branch that
  the runner deliberately removes before computing demand
  (`src/seon/test/runner.clj:711-714`). Excluding it the way the runner does
  leaves 54 real subjects, and every one already declares its observation.
  A detector that copies the reach query instead of the runner's derivation
  would file 782 false issues on its first run.
- **`:seon.test/subject` has 1,779 non-holders — every single test.** So D3
  and D7 are, today, entirely reach-derived; the subject ref that
  [namespace-data-model §3.1](../plan/namespace-data-model-2026-09-16.md)
  declares is written nowhere.

Sanity reads taken alongside: 1,630 issue entities (261 notes under
`docs/seon/issues/`, 1,368 under `docs/seon/issues/archive/` — the indexer
covers the archive, which is correct but means a steward's "open issues"
view must filter by status, not by count); 1,066 of 1,137 public functions
carry a contract.

### The first generator run

Running the five detectors that need no new fact (D1, D2, D3, D5, D7) would
create **733 issue entities** across **176 responsible namespaces**. Adding
D8 would add 431 more; D4, D6 and D9 add 0, 3 and 0.

| Namespace | issues | Namespace | issues |
|---|---|---|---|
| `seon.repl-parity-test` | 72 | `seon.flow` | 16 |
| `seon.schema` | 30 | `seon.env` | 16 |
| `my.plan` | 23 | `seon.maintenance.result` | 14 |
| `seon.turn` | 23 | `seon.render` | 12 |
| `seon.cluster` | 17 | `seon.ai` | 11 |
| `seon.render.web` | 17 | `seon.cluster.agent` | 11 |
| `seon.sci.eval` | 17 | `seon.print` | 11 |
| | | `seon.render.transcript` | 11 |

The head of that distribution is itself a finding: 72 of the 733 land on
`seon.repl-parity-test`, a test namespace whose helpers are public and
untested. Detector scope must be declared provenance (`:seon.schema.admission/source`,
`:seon.fn/ns` under `src/`), not a name pattern — the same rule audit A
applied to the 60 missing-contract sample.

## 3. Which detectors run today, and which need a fact that does not exist

| Detector | Runs today? | Missing fact | Writer that must emit it |
|---|---|---|---|
| D1, D2, D3, D5, D7, D8 | yes, as written above | — | — |
| D4 | yes; returns 0 | — | — |
| D6 | **no** | `:seon.test/error-signatures` (set, declared in [§3.2](../plan/namespace-data-model-2026-09-16.md)) and `:seon.error/fn` as a ref instead of the `:seon.instrument/fn` string | the analyzer's deftest metadata path (`src/seon/fn.clj:320` emits `:seon.test/subject` the same way), admitted through `seon.program`; the ref at the fault committer (`src/seon/error.clj:1095`) |
| D7 (strong form) | **no** — only the weak "no first-party direct call" proxy (135) | `:seon.test/namespace-under-test` and `:seon.test/subject` (0 holders of 1,779) | static indexing, `src/seon/fn.clj:320`, derived at index time from the test's direct calls |
| D9 | **no as a database query** — the honest number needs the runner's edge exclusion | either `:seon.test/expensive-fixture?` written where the runner already knows it, or `expensive-fixture-tests` (`src/seon/test/runner.clj:697`) lifted to a shared derivation over a database value | `seon.test.runner`, at selection |

D6's shape deserves the standing warning: with no `error-signatures` family,
"recurring error without a regression" is true of every signature by
construction — a check whose subject is absent reporting a finding for
everyone is the mirror image of the absence-as-health defect, and it is not
a detector until the fact exists.

## 4. `seon.issue/generate` — contract, idempotence, how it runs

```clojure
(defn generate
  "Derive issue facts for one detector's current subjects."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map [:seon.issue/detector :seon.fn/sym]
                             [:seon.issue/severity :seon.issue/severity]]]
                  :seon.db/tx-data]}
  [database request] …)
```

Called through the writer as `[[:db.fn/call #'generate request]]`, exactly
like `index-tx`, `start-tx` and `guard-call` already are
(`src/seon/issue.clj:106`, `:351`, `:462`). The detector is resolved from its
`:seon.fn/sym` program entity and stored as `:seon.issue/detector`, a ref —
one new optional key in `resources/seon/schemas/seon.issue.edn`, plus
`:seon.issue/schema` (ref to the `:seon.schema/key` entity) so a schema-keyed
finding can be queried like a function-keyed one.

Per run, per subject:

- `:seon.issue/id` = `(seon.id/id (into (sorted-map) {:seon.issue/detector '<fn> <identity-attr> <value>}))`. `:seon.issue/id` is a Datahike identity, so a second run upserts the same entity.
- Written every run: `:seon.issue/status`, `:seon.issue/severity`, `:seon.issue/detector`, the subject ref (`functions` / `tests` / `errors` / `schema`), the required test refs.
- Written only when absent: `:seon.issue/problem`, `:seon.issue/title` — a human's or a worker's edit survives regeneration.
- Subject gone from the detector's result: assert `:seon.issue/resolved-tx`. Subject reappears: retract it. History keeps both; no entity is ever deleted.

It must NOT: overwrite `problem` (that is the surviving prose), mint a random
id (`random-uuid` is only for a genuinely fresh event —
[AGENTS.md vocabulary table](../../../../AGENTS.md)), store a bare entity id
as a subject (reforks change them), or write `:seon.issue/agent` — assignment
is `start!`'s decision alone.

Regression, canonical harness, armed:

- Namespace `test/seon/issue_generate_test.clj`.
- `generate-is-idempotent-per-detector-and-subject`: with the canonical
  database fixture plus one `::test-support/extra-schema` entity map declared
  without a pair, run `generate` twice for `seon.issue.detect/entity-map-without-pair`;
  assert `(= 1 (count (seon.db/q '[:find [?e ...] :where [?e :seon.issue/detector ?d]] db)))`
  and that the two runs' `:seon.issue/id` values are equal, and that a
  `problem` edited between the runs is unchanged after the second.
- `generate-resolves-and-reopens-without-losing-identity`: declare the pair,
  re-run, assert `:seon.issue/resolved-tx` is present and the entity id is
  the same; remove the pair, re-run, assert `resolved-tx` is absent again and
  the id is still the same.
- `generate-refuses-a-bare-entity-id-subject`: a detector returning
  `[{:db/id 12345}]` yields a typed refusal naming the detector, and writes
  nothing.

Running order: by hand first (an operator call on `default`), then from the
adoption path beside `seon.issue/index!` once the counts are stable, then on
the maintenance schedule. Never inside a Datahike transaction function's
own body beyond the `:db.fn/call` the writer already runs, and never at
render time.

## 5. Three priced options for the first generator run on default

Simplest first. A decision is the owner's; the recommendation is option B.

| | Scope | Issue entities | Cost | Guarantee | What we give up |
|---|---|---|---|---|---|
| **A** | One namespace, all runnable detectors — `seon.render.web` (17 subjects) | ~17 | ~1 h: `generate` + one detector + one regression | The steward-triage loop is proven end to end on a namespace that already has a live steward candidate (P8) and a real page to observe | No measure of detector precision across the tree; the 733-subject shape stays unproven |
| **B — recommended** | Render pair + docstring only (D1 + D2) | **90** | ~2 h: two detectors, two regressions, one operator run | Both detectors are exact reads of stored facts (no reach, no derivation copy), so a false positive is a schema-declaration bug and nothing else; 90 fits one steward's triage | D3's 303 — the largest real backlog — waits a turn |
| **C** | Every detector that runs today (D1, D2, D3, D5, D7; D4 and D9 contribute 0) | **733** across 176 namespaces | ~4 h plus triage; D3 costs 193 ms per run, D5 parses 1,066 contracts | One complete picture of the standard in the database, queryable per namespace on day one | 72 of the issues land on one test namespace; without a declared-provenance scope the first run's largest cluster is noise, and 431 more from D8 would swamp the steward view |

Option B also buys the cheapest correction: D1's 51 includes component
entities (`seon.fn.arity/row`, `seon.fn.ast/node`, `seon.error.occurrence/blob`)
that render through their owner, so the pair standard should exclude schemas
reached only as a `:seon.db/component` set. That exclusion is one query, and
it is much cheaper to make before 51 issues exist than after.

## 6. Ownership and the working tree

Owned and added by this lane: this note and
[detectors-and-standards-probe-2026-09-16.clj](detectors-and-standards-probe-2026-09-16.clj).
Nothing else was written. `git status` at the start of the lane showed these
foreign uncommitted edits, all preserved and none read as authority:
`docs/seon/issues/attempt-recorder-returns-error-identity-without-occurrence-evidence.md`,
`docs/seon/issues/complete-publication-takes-seventy-seconds.md`,
`docs/seon/issues/evaluation-reader-refuses-pulled-renderer-ref.md`,
`resources/seon/schemas/seon.db.edn`, `resources/seon/schemas/seon.eval.edn`,
`resources/seon/schemas/seon.issue.edn`, `src/seon/db.clj`, `src/seon/issue.clj`,
`test/seon/issue_settlement_test.clj`, plus untracked `build/`, `workers/` and
`docs/seon/issues/in-process-test-timeout-precedes-fixture-release.md`.
`src/seon/issue.clj` and `resources/seon/schemas/seon.issue.edn` are the
issue-settlement lane's files and are protected: the `:seon.issue/detector`
and `:seon.issue/schema` keys above are a request to that owner, not an edit.
No test JVM was launched; `default` was neither stopped nor restarted.
