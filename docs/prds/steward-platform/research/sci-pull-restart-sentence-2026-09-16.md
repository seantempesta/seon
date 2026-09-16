---
date: 2026-09-16
lane: sci-pull
surface: render-selection
---

# Pulling a function row in SCI returned a stale-Var sentence

Issue:
[pulling-a-function-row-in-sci-returns-a-restart-the-jvm-sentence](../../../seon/issues/pulling-a-function-row-in-sci-returns-a-restart-the-jvm-sentence.md).
Filed by the issue-context trials lane
([evidence](issue-context-trials-2026-09-16.md)).

## Reproduced first

`default`, pid 45917, SCI evaluation mode, namespace `my.agents.root`,
read-only:

```clojure
(seon.db/pull (seon.db/db) '[:seon.fn/sym] [:seon.fn/sym "seon.turn/next-agent-work"])
;; shown text, verbatim, 137 ms:
;; Restart the JVM to remove stale loaded Var seon.turn/next-agent-work;
;; it is absent from the published program graph.
```

## Cause — one declaration, not a stale-Var check

There is no stale-Var check on the read path. The sentence is
`seon.problems/stale-var-ai` (`src/seon/problems.clj:438`), a problems-surface
row producer, SELECTED for the pulled row.

`:seon.problems/stale-var` was declared
`[:map #:seon.render{:ai … :html …} [:seon.fn/sym :seon.fn/sym]]`
(`resources/seon/schemas/seon.problems.edn:60`). Maps are OPEN (AGENTS.md
§2.5), so that shape is satisfied by EVERY `:seon.fn` row — `:seon.fn/sym` is
the family's `:db.unique/identity`. Measured on `default`'s live projection:

```clojure
(seon.schema/matching-shapes-in projection {:seon.fn/sym "seon.turn/next-agent-work"})
;; => one row: [:seon.problems/stale-var #{:seon.fn/sym} seon.problems/stale-var-ai]
```

A full `'[*]` pull (16 attributes) matches the same single ai-declaring shape,
so specificity never rescues it: `seon.render/schema-producers` filters to
shapes declaring the output BEFORE ranking specificity
(`src/seon/render.clj:306`), and no `:seon.fn` shape declares an AI pair.

The value renderer then applies it. `seon.render.value/value-node*`
(`src/seon/render/value.clj:288`) consults `seon.render/project-node` for any
map when a ctx is present and `:seon.render.value/structural?` is not set.
That is DELIBERATE at HEAD: `563034709` had excluded AI output with
`(not ai?)` and `cecfaf428` ("Restore declared AI pairs and prove renderer
settlement through SCI") put it back, because attempt and evaluation entities
render through their declared pairs. So the renderer is not the defect — the
declaration is.

The pair was also DEAD as a selection target: `seon.problems/ai-prose`
(`src/seon/problems.clj:565`) and `html-report` (`src/seon/problems.clj:526`)
call `stale-var-ai`/`stale-var-html` DIRECTLY on the rows `stale-vars`
derived, which is the only place staleness is actually known. The sibling
finding rows `:seon.problems/failed-run` and `:seon.problems/unowned-namespace`
declare no pair. So the declaration bought nothing and shadowed 4,868 rows.

## Fix

Removed the `#:seon.render{…}` properties from `:seon.problems/stale-var`.
The shape, both producers and the problems surface are unchanged.

### Live proof — default pid 45917, jvm mode, "not adopted"

`seon.schema.edn/packaged-forms` reads the resource fresh, so the edited file
is readable in the running JVM; the cluster's PUBLISHED projection is not
(default adoption is refused by a foreign defect — test evidence referencing a
deleted declaration, recorder lane owns it). The proof is therefore at the
selection seam, handing `schema-producers` the projection with and without the
declaration:

```clojure
(#'seon.render/schema-producers projection request {:seon.fn/sym "seon.turn/next-agent-work"} :seon.render/ai)
;; live published projection      => ["seon.problems/stale-var-ai"]
;; same projection, pair removed  => []
(seon.schema.form/schema-properties (get (seon.schema.edn/packaged-forms) :seon.problems/stale-var))
;; => nil
```

This is a RESOURCE change, so nothing was hot-reloaded: no Var was redefined,
no fork was taken, and `default` was not adopted, restarted or reforked.

## Regressions

`test/seon/render/value_test.clj`
— `a-pulled-function-row-is-its-attributes-not-steering-prose`. The existing
twin `explicit-structural-results-retain-attributes-through-real-evaluation`
could not catch this: it passes
`:seon.render.value/options {:seon.render.value/structural? true}`, which is
exactly the option an agent's own evaluation never sets. The new test
evaluates the two-argument `seon.db/pull` (database elided, as an agent writes
it) through a real forked cluster SCI ctx, and asserts the shown text reads
back as the pulled map, contains no "Restart the JVM", and — the derivation
behind it — that no shape a bare `:seon.fn` row matches declares an AI pair.

`test/seon/problems_test.clj` — `stale-var-findings-declare-their-render-producers`
asserted the defective declaration. It is now
`stale-var-findings-declare-no-render-pair`, asserting both render properties
absent and the shape's entries unchanged.

### In-process runs, default pid 45917

| run | result |
|---|---|
| `seon.problems-test/stale-var-findings-declare-no-render-pair` | 3 pass, 0 fail, 0 error |
| `seon.render.value-test/a-pulled-function-row-is-its-attributes-not-steering-prose` | 3 pass, **3 fail**, 0 error |

The second is red ON PURPOSE and is the strongest evidence here. The JVM's
`seon.test-support/database-base` was realized before this edit, so the
fixture branch still carries the PRE-EDIT schema; the test reproduced the
filed sentence verbatim on the canonical harness:

```text
expected: (= raw (edn/read-string shown))
  actual: (not (= #:seon.fn{:private? false, :sym "seon.db/q"} Restart))
expected: (not (str/includes? shown "Restart the JVM"))
expected: (empty? (filter :seon.render/ai (schema/matching-shapes-in …)))
  actual: ({:seon.render/ai seon.problems/stale-var-ai, …
            :seon.schema/required-attrs #{:seon.fn/sym}})
```

The regression is therefore not a tautology: it fails on the old schema with
the exact reported bytes. Green needs a fixture base built from the edited
resource, which is the cold gate. Nothing here rebuilt the shared delay
(fixture-base poison rule).

## Verification boundary

- Reproduction, cause and the before/after selection measurement are live on
  `default` pid 45917, read-only.
- The schema edit is PROVEN AT THE SELECTION SEAM and by the freshly read
  resource, NOT by cluster adoption. Default was not adopted, restarted or
  reforked; no test JVM was launched.
- `a-pulled-function-row-is-its-attributes-not-steering-prose` has no in-process
  green. It needs `bin/test --paths … -- seon.render.value-test
  seon.problems-test` on a base built from HEAD plus these paths. Requested in
  `tmp/orchestrator/gate-requests/sci-pull.txt`.
- clj-kondo on the three changed files: 0 errors; the 3 warnings are
  pre-existing and untouched.

## Two findings alongside, both filed

1. **`dir` of a real namespace returns nothing at all.** `(dir seon.turn)` in
   SCI mode on `default` returns only an elision value —
   `{:seon.print/bound-by :seon.render.profile/token-budget,
   :seon.print/elision-unit :characters, :seon.print/omitted 41040,
   :seon.render.data/next-offset 0, :seon.render.data/path [],
   :seon.render.data/total 41040}` — 41,040 characters omitted, zero shown,
   and `:seon.render.data/path []` gives no narrower requery. This is the
   other half of "agents could not read source" in the issue-context trials.
   `(doc seon.turn/next-agent-work)` is fine and returns the complete
   documentation map. Filed as
   [dir-of-a-namespace-returns-an-elision-with-nothing-shown](../../../seon/issues/dir-of-a-namespace-returns-an-elision-with-nothing-shown.md).
2. **Render selection derives its projection from the SCI ctx, not from the
   database value it is handed.** `seon.render/project-node*` reads
   `(sci.kernel/context-projection ctx)` (`src/seon/render.clj:1060`) and
   ignores the request's `:seon.db/db` and its carried projection — the §2.1
   shape the rest of `seon.db` obeys (`src/seon/db.clj:950`). It did not cause
   this defect and changing it is a separate slice; it is why the A/B above
   had to be run at `schema-producers` rather than through the floor. Filed as
   [render-selection-reads-its-projection-from-the-ctx-not-the-handed-database](../../../seon/issues/render-selection-reads-its-projection-from-the-ctx-not-the-handed-database.md).

## Class note

The live projection carries 378 shapes declaring `:seon.render/ai`. Six have
required attributes that are all storable and all foreign to the shape key's
namespace: `my.plan/render-step`, `my.turn/namespace-unit`,
`seon.eval/entity`, `seon.problems/missing-model`, `seon.problems/stale-var`,
`seon.render/unknown`. The first three are the intended entity pairs. Only
`stale-var` was reachable as a one-attribute shadow of a whole entity family;
`missing-model` was probed live and does NOT hijack
(`(seon.db/pull (seon.db/db) '[:seon.config.ai/model] [:seon.config/cluster "default"])`
returns `#:seon.config.ai{:model "deepseek-flash"}`), and
`seon.render.value-test/settings-remain-data-when-block-and-problem-renderers-match`
already holds that line, so it was left alone rather than churned.
