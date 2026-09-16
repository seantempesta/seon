---
type: research
status: active
tags: [testing, destructive, program-facts, derive-or-die, render]
---

# Destructiveness is a declaration plus a derivation (2026-09-17)

Owner ruling F1
([program-facts-are-the-runtime PRD §1e](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)):
"we must know WHICH tests are destructive from facts, not by hand — the owner
functions that destroy declare it once, the test's destructiveness derives by
reach, and it is indexed on the test so an agent can query where a test runs
and why."

Read end to end before designing: the PRD's §1e and §4b, the
[test-system-is-the-database PRD §0b](../plan/test-system-is-the-database-prd-2026-09-17.md),
`tmp/orchestrator/wave2/repl-rule.txt`, AGENTS.md §0–§5 and §7, and both prior
landing notes
([in-process-run-refuses-destructive-tests](in-process-run-refuses-destructive-tests-2026-09-17.md),
[platform-tier-no-destructive-drill](platform-tier-no-destructive-drill-2026-09-17.md)).
Seams read end to end: `src/seon/test.clj` (the destructive block and `check`),
`src/seon/test/runner.clj` (tier selection and `record-tx`),
`src/seon/fn.clj`'s `var-row` (the metadata the indexer admits),
`src/seon/cluster/store.clj`'s `admit-destructive-path!`,
`src/seon/render/test.clj`.

## 1. What existed (2026-09-16/17, commits ccccea806 and its two landing notes)

The rule was correct and the SET was a hand list:

```clojure
(def destructive-owners
  #{"seon.test-support/populate-published-root!"
    "seon.test-support/populate-published-operator-root!"
    "seon.operator/cleanup-root-under-lock!"})
```

`seon.test.runner/destructive-owners` — three symbol STRINGS in code, read by
two consumers: the cold gate's platform-tier checker
(`verify-platform-tier-carries-no-destructive-drill!`) and the in-process
refusal (`seon.test/destructive-reach` → `seon.test/run`,
`seon.test/check`). It was guarded against drift (a symbol with no program row
threw / returned the typed unknown) but it was still the banned substitute: a
hand-maintained roster, remote from the functions it describes, invisible to
every agent and to every query. Nothing on a test entity said where that test
runs, and nothing in a test's render said it either.

## 2. What changed

**One declaration, at the definition.** `:seon.fn/destroys` — a nonblank
string saying what the function deletes that it did not create — carried in
the function's own metadata and admitted by the indexer into the function row
(`resources/seon/schemas/seon.fn.edn`, `src/seon/fn.clj` `var-row`). The three
owners declare it where they are defined: `seon.operator/cleanup-root-under-lock!`,
`seon.test-support/populate-published-root!`,
`seon.test-support/populate-published-operator-root!`.
`seon.cluster.store/create-store!` deliberately declares nothing: it deletes
only its own incomplete genesis and refuses a complete store, and declaring it
would empty the platform tier of every file-store fixture (42 tests reach it)
while naming nothing the incident is about.

**One derivation, no stored mirror.** A test's destructiveness is NOT written
onto the test entity. It is derived, per question, from two facts that already
exist — `:seon.fn/destroys` and `:seon.fn/calls` — by
`seon.test/host`, and the answer moves the moment a declaration or a call edge
does. Storing it would be a materialised closure and a hand mirror by another
name (AGENTS.md §2.2, "derive or die"; the call-graph research's "no
materialised closure"). This is the one deliberate departure from the slice's
wording ("index it on the test entity"): the fact chain the owner asked for is
`:seon.fn/destroys` → `:seon.fn/calls` → `seon.test/host`, and it is queryable
by an agent in one call.

**Both consumers read that one derivation.**
`seon.test.runner/destructive-owner-rows` filters manifest rows by
`:seon.fn/destroys` (cold tier selection, where no database is held) and
`seon.test/destroyers` queries the same attribute on the cluster's database
(in-process). The hand set is deleted; no second list survives.

**Absence is never health.** A program in which nothing declares
`:seon.fn/destroys` is the typed unknown in process and a thrown refusal at
tier selection — not an empty owner set. A test with NO program row has no
known call graph: `host` answers the typed unknown and `seon.test/run` refuses
it on a development root instead of admitting it as in-process.

**It is readable.** `seon.test/host` returns `:seon.test/host-report`
(`:seon.test.host/in-process` or `:seon.test.host/isolated-snapshot` with the
owner, what it destroys, the call path and the cold command);
`seon.test/host-text` is the one line, and the test entity's AI and HTML render
pair both carry it, so an agent reading a test sees "runs: in the cluster
process" or "runs: isolated snapshot, under its own operator root (path: what
it destroys)". The AI render also offers `(seon.test/host (seon.db/db) "…")`
as a requery form.

## 3. The exact facts

| fact | where it lives | who writes it |
|---|---|---|
| `:seon.fn/destroys` | the function entity | indexing, from the function's own metadata |
| `:seon.fn/calls` | function and test entities | indexing (unchanged) |
| the test's host | nowhere — derived | `seon.test/host` per question |

## 4. Measured on `default` (pid 53320)

BEFORE (the hand set, 46 ms for the whole union):

| owner | tests reaching it |
|---|---|
| `seon.test-support/populate-published-root!` | 49 |
| `seon.operator/cleanup-root-under-lock!` | 16 |
| `seon.test-support/populate-published-operator-root!` | 3 |
| union | **124** of 1,836 test rows |

The analyzer reports the declaration at the definition (live, pid 53320):
`(seon.fn.analyzer/analyze {:seon.fn.analyzer/paths ["src/seon/operator.clj"]})`
returns `:seon.fn.analyzer/meta {:seon.fn/destroys "an operator root's whole
data/ directory …"}` for `cleanup-root-under-lock!`, which is what
`var-row` admits.

AFTER (derived from `:seon.fn/destroys`): NOT YET OBSERVED ON `default`.
Adoption could not be won: `bin/seon init --dev default --changed …` was
launched four times, waited 5–8 minutes each for the operator lifecycle lock
(17 concurrent `init --dev` publications from other lanes at 20:00Z) and was
refused twice with "Source changed while incremental publication was being
analyzed" — the open issue
[an-analysis-time-snapshot-change-never-reaches-the-one-publication-retry](../../../seon/issues/an-analysis-time-snapshot-change-never-reaches-the-one-publication-retry.md),
whose own declaration says the next read converges. Six attempts over ~2 hours all lost the same race;
the attribute itself IS installed on `default` (`:seon.fn/destroys`,
entity 80920), only the three owners' row values are missing, which needs
the analysis step to land. What WAS proven live on pid 53320: the analyzer reports the
declaration at the definition (above), and the before-numbers are the
derivation's expected answer — the owner set is unchanged by this slice, so
the derived union must remain 124 tests.

## 5. Boundary

* NO IN-PROCESS REGRESSION RUN HAPPENED, and the reason is infrastructure,
  not the slice: `seon.test/run` reaches its test namespace and the canonical
  fixture base through the PUBLISHED program, so the three regressions below
  cannot be exercised in process until adoption lands. They are written and
  committed; the cold gate is their proof of record:
  `seon.test-reaching-test/an-in-process-run-under-a-development-root-refuses-a-destructive-test`
  (extended: the refusal and `host` name the owner AND what it destroys),
  `…/a-program-declaring-no-destroyer-refuses-instead-of-admitting`,
  `…/a-test-with-no-program-row-is-unknown-and-is-never-run-in-process`,
  `…/a-tests-render-pair-shows-where-it-runs-and-why`, and
  `seon.test.runner-test/…destructive…` (the tier checker now derives its
  owners from the analyzed declarations of the real `src`/`test` files).
  The lane launched no test JVM.
* Publication contention is real and is the known open issue
  [an-analysis-time-snapshot-change-never-reaches-the-one-publication-retry](../../../seon/issues/an-analysis-time-snapshot-change-never-reaches-the-one-publication-retry.md):
  with a dozen lanes editing the shared checkout, `bin/seon init --dev default
  --changed …` waited minutes for the operator lifecycle lock and then refused
  twice with "Source changed while incremental publication was being analyzed",
  from a seam whose own declaration says the next read converges. A retry loop
  is the workaround this lane used; the issue remains the fix.
* PROTECTED-PATH EXCEPTION, declared: the slice cannot exist without two edits
  outside the lane's FREE list, because a declaration must live at the owner's
  definition and must be admitted by the indexer. They are additive and
  minimal: `src/seon/fn.clj` gains one `cond->` clause in `var-row` (plus the
  `destroys` binding), and `src/seon/operator.clj` gains one metadata key on
  `cleanup-root-under-lock!`. Both files were verified to hold no other lane's
  uncommitted edits at commit time. Flagged for the orchestrator's review.
* RESET NOT NEEDED: `:seon.fn/destroys` is a new optional key; no key changed
  meaning.
