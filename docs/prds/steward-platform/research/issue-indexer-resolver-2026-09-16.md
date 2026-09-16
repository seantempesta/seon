---
type: research
status: current
created: 2026-09-16
tags: [research, steward, issue, indexer, schema]
---

# Issue indexer: one derived citation resolver replaces five hand-rostered shapes

Implements option B of
[complex-issues-as-schema-spec](complex-issues-as-schema-spec-2026-09-16.md) §3c/§5,
with the ADDED rulings of 2026-09-16 12:05Z (unresolved tokens are evidence, not
refusals; archived notes never fail the check; counts per path; exit status for
malformed frontmatter and duplicate slugs only).

## 1. The kill

`src/seon/issue.clj` `index-tx` kept five citation shapes (qualified symbol →
`:seon.fn/sym`/`:seon.test/sym`, 64-hex → `:seon.error/signature`, 9-hex commit,
`class/x` tag) and dropped everything else on the floor. The sixth shape would
have been the sixth hand-rostered branch — the defect class `AGENTS.md` §2.2
names.

The landed shape is one resolver with no roster anywhere:

1. **The declaration is a fact.** Each issue attribute declares the installed
   identity attribute it collects, as a Malli property on its own schema:
   `:seon.issue/cites [:seon.fn.file/path]` on `:seon.issue/files`, and so on
   for `/functions`, `/tests`, `/errors` (signature AND error id), `/keys`,
   `/namespaces`, `/runs`, `/issues`.
2. **The resolver derives its whole target set** by reading those declarations
   back out of the database's own `:seon.schema/key` + `:seon.schema/form` rows
   and intersecting them with `seon.db/identity-attributes`
   (`src/seon/db.clj:943`). No declaration in a database is a loud refusal
   (`:seon.issue/citations-undeclared`), never a silent zero.
3. **Every identity value's citable spellings** are indexed once per
   `index-tx`: the value's own text, plus — for a stored ABSOLUTE path — each
   of its repository-relative tails, because that is how notes spell files.
   13,597 keys, built in 16 ms on `default`.
4. **A token resolves to exactly one [issue-attribute, entity] pair or it does
   not resolve.** Two distinct pairs is an ambiguity that is REPORTED and never
   guessed; the one 64-hex token that is both `:seon.error/id` and
   `:seon.error/signature` of the SAME entity is one pair, so it resolves.
5. **`path:line` and `path:line-line`** resolve the head as the file and become
   a `seon.issue.citation` component (`/file`, `/row`, `/end-row`) on a stable
   identity `(seon.id/id [issue-id path row end-row])`, so re-indexing reuses
   the entity and superseded citations are retracted by id.
6. **A token that resolves to nothing** and looks like a qualified symbol lands
   in `:seon.issue/unresolved` — a set of exact tokens on the issue, so "which
   notes name deleted symbols, Java methods or Maven coordinates" is a query.
   A Java class or Maven coordinate is decided by lookup, not by a naming rule.
7. **`:seon.issue/opened`** now comes from git when the frontmatter has no
   `created:`: one `git log --format=%x00%at --name-only -- docs/seon/issues`
   read in `notes` (outside the writer), keyed by note file name so an archived
   note keeps its original date. 439 ms, 1,648 dated names, declared bound 30 s.
8. **A new family gets linked with no indexer change**: declare
   `:seon.issue/cites` on one attribute.

Adoption follows the same derivation: `identity-row`'s pull pattern and
`adopt-tx`'s ref map are built from the declarations, and file citations adopt
by `:seon.issue.citation/id` with the file named by `[:seon.fn.file/path …]`.

## 2. Live measurement on `default` (never restarted, never reforked)

`(seon.issue/index! {… (seon.issue/notes ".")})`, 1,644 notes (269 open, 1,376
archived), 6.3 s including the git read. Datom counts before → after:

| attribute | before | after |
|---|---:|---:|
| `:seon.issue/functions` | 1,472 | 1,489 |
| `:seon.issue/tests` | 245 | 246 |
| `:seon.issue/errors` | 0 | 1 |
| `:seon.issue/keys` | 0 | **1,745** |
| `:seon.issue/namespaces` | 0 | **1,459** |
| `:seon.issue/files` | 0 | **1,836** |
| `:seon.issue.citation/id` | 0 | **1,836** |
| `:seon.issue/runs` | 0 | 1 |
| `:seon.issue/issues` | 0 | **150** |
| `:seon.issue/unresolved` | 0 | **1,531** |
| `:seon.issue/opened` | 47 | **1,644** |
| `:seon.issue/commits` | 1,509 | 1,516 |
| `:seon.issue/members` | 115 | 115 |

`bin/issues-index --check` refusals: **1,521 → 0**. The 1,531 unresolved tokens
across 736 note paths are now facts on the issues plus a per-path count in the
report; ambiguities are 2, both a symbol that is at once a `:seon.fn/sym` and a
`:seon.test/sym` row:

- `seon.cluster.mcp-test/jvm-exceptions-retain-the-root-location-and-flat-error`
- `seon.contracts-plan-test/run7-reader-refusal-uses-the-shared-grammar`

**Idempotence:** a second `index-tx` against the database the first one wrote
emits **0 retractions** and no new citation entities. Reaching that also fixed a
pre-existing churn: class `:seon.issue/members` were written as lookup refs,
which never compared equal to the stored entity, so 13 class notes
retracted-and-re-added their whole membership on every index.

The blocker the research page called invisible,
`development-adoption-can-mix-host-and-sci-generations` (0 program refs before):

```clojure
#:seon.issue{:id "development-adoption-can-mix-host-and-sci-generations"
             :status :open :severity :blocker
             :opened #inst "2026-09-08T21:33:54.000-00:00"
             :namespaces [seon.adoption-contract-freshness-test
                          seon.cluster.source-test seon.custody-stability-test]
             :files [{test/seon/adoption_contract_freshness_test.clj}
                     {src/seon/sci/eval.clj 1093-1160} {src/seon/cluster.clj 1868}
                     {src/seon/sci/eval.clj 957} {src/seon/cluster.clj 1403-1407}
                     {src/seon/cluster.clj 1771-1905} {src/seon/cluster/agent.clj 915-951}
                     {src/seon/sci/kernel.clj 108-115} {src/seon/sci/eval.clj 1558-1670}]}
```

Nine file citations with the exact spans §1.6 of the research page lists, three
namespaces, and a git-derived open date.

## 3. Report and check output shape

`seon.issue/report` (and `index!`) now return, besides `:seon.issue/count` and
`:seon.issue/entities`:

- `:seon.issue/refusals` — malformed frontmatter, duplicate slug, invalid
  `created:`, unknown class only. **This alone drives `bin/issues-index --check`'s
  exit status** (`script/seon/dev/issues.clj:20`, unchanged).
- `:seon.issue/ambiguous` — one diagnostic per ambiguous token, with its path.
- `:seon.issue/unresolved` — `{note-path count}`, a count per path, never a dump.
- `:seon.issue.unresolved/tokens`, `:seon.issue.unresolved/paths` — totals.
- `:seon.issue.parse/undated` — notes git could not date (an absence that is
  reported rather than read as health).

Measured after the change: `bin/issues-index --check` exits **0**, prints
**60,238 bytes** (was 242 KB and exit 1), and reports `:seon.issue/count 255`
open issues, `:seon.issue/refusals []`, two ambiguities, 1,531 unresolved
tokens over 736 paths, and `:seon.issue.parse/undated 0`.

## 4. Verification boundary

- Live, on `default`, `jvm` mode with explicit custody: the resolver, the git
  read, the full index, the before/after counts, the idempotent second pass and
  the pull above. `default` was never stopped, reforked or restarted; the
  schema and code landed by `bin/seon init --dev default --changed …`.
- The schema slice first refused publication because `:seon.issue/cites` was
  DECLARED as an attribute: the publication then writes the property as a datom
  and the cardinality-many write was refused. It is a Malli property only, like
  `:seon.render/units`' usage, and publication converged after removing the
  attribute declaration.
- `citation-attributes` reads the declarations from schema ROWS, not from the
  carried projection: at development program reconciliation the database carries
  no projection, and the projection-based version refused every adoption.
- In-process regressions could NOT be proven in `default`'s JVM: its shared
  `seon.test-support/database-base` was realized before this schema landed, so
  the fixture has neither the new attributes nor the `cites` declarations and
  `index!` refuses there with `:seon.issue/citations-undeclared`. Per the
  standing rule the shared base was not rebuilt. A fresh worker JVM populates
  the fixture from current resources; the four `seon.issue-test` regressions are
  for the orchestrator's batched gate.

## 5. Regressions added (`test/seon/issue_test.clj`)

- `cited-identities-resolve-through-one-derived-resolver` — the eight notes the
  research page converted by hand, each asserting the citation shapes §1 names,
  `opened` present on all eight, spans on the adoption note's `src/seon/cluster.clj`
  citations, and an idempotent re-index (same citation count, identical pull).
- `an-archived-note-reports-unresolved-tokens-without-a-refusal` — a note naming
  a deleted symbol, a Maven coordinate and a Java method indexes with zero
  refusals, stores those three exact tokens, still resolves `seon.db/pull`, and
  the report carries a count per path.
- `a-token-naming-two-identities-is-reported-never-guessed` — a symbol that is
  both a function and a test row is reported and linked to neither.
- `indexed-issues-replace-facts-and-retain-identities` — updated: unresolved
  symbols are no longer refusals but `:seon.issue/unresolved` members.

## 6. Files touched

`src/seon/issue.clj`, `resources/seon/schemas/seon.issue.edn`,
`resources/seon/schemas/seon.issue.citation.edn` (new),
`test/seon/issue_test.clj`, this note. No other lane's file was touched;
`script/seon/dev/issues.clj` needed no change because its exit status already
keys on refusals alone.

## 7. Left undone

- `:seon.issue/commits` stay 9-character strings (research §5.1 is an owner
  decision: a `seon.commit` family keyed by the full sha).
- `:seon.issue/measurements` and `:seon.issue/findings` (research §3b) are a
  separate slice, as recommended.
- `seon.fn.file` and `seon.test.run` still declare no render pair, so a cited
  file renders through the default attribute-map printer (research §4).
