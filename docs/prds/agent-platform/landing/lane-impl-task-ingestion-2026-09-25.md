---
type: landing
status: "slice 1 (7.9) written and committed; unverified until root runs the commands below; over the line ceiling"
created: 2026-09-25
---

# Lane impl-task-ingestion-opus: slice 1 (7.9), ingestion plus reverse lookup (2026-09-25)

## Broken or unproven first

- **Over the ceiling.** Production code adds 129 lines: `seon.fn` +53/−8, including the 2 annotation lines, and
  `seon.issue` +76. Schema adds 27 lines. The total is **156 against the 112 allowed** (100 + 12). Regressions add 62
  lines against 60. Root rules on whether this growth stands. The smaller cut, if it doesn't, is listed at the end.
- **Nothing has run.** Under the LOAD RULE the lane loaded no changed namespace and ran no tests. Only these ran:
  - `clj-kondo`: 0 errors on `fn.clj`, `issue.clj` and the test;
  - read-only REPL probes of the assumptions (below).
- **The program gate does not refuse dangling ids yet.** `seon.issue/task-findings` returns the `:dangling` set as
  data. Wiring it into the merge or write-back gate needs the gate owner's file, so a dangling id is reported, not
  refused.
- **Publication does not ingest task files yet.** `index-tasks!` exists, but publication's `index-issues!` seam in
  `src/seon/cluster/source.clj:512-559` is not owned. Ingestion runs only when called.
- **Adopt schema and code together.** The seed annotations on `seon.fn/gate-set` and `seon.fn/tests-reaching`
  validate against `:seon.task/tasks`. If `fn.clj` is adopted before the four schema files, indexing refuses.
- **`:seon.task/needs` is omitted.** No current task uses it (grow by accretion).

## What landed (data flow)

**Annotations.** `var-row` and `namespace-row` now call `task-links` before the definition digest. REPL-authored
rows (`source-rows`) and file rows therefore carry the same facts and digests.
- It stores `:seon/tasks` as `:seon.task/tasks`, a set of keyword values that are never refs. The holder decides the
  role: a test row means proof, a function or namespace row means subject.
- It stores `:seon/must-read` and `:seon/see-also` as typed native symbol sets, `:seon.fn/must-read` and
  `:seon.fn/see-also`. It reuses `capability-symbol`, which unquotes, and `seed-symbol`, which resolves aliases.

Each malformed annotation refuses by name through `seon.fn/task-refused!`, whose error schema is
`:seon.task/refused-error`:

| Written | Refusal |
|---|---|
| `^:seon/tasks`, which stores `true`, or any non-set value | `:not-a-set-of-ids`, checked against the attribute's own schema |
| A `::alias` id, found from clj-kondo's `:auto-resolved` keyword entries inside the form | `:auto-resolved-id` |
| `(deftest name {:seon/tasks …} …)`, where the map is a body keyword with a `:from-var` | `:metadata-after-test-name` |
| A bare, unquoted link symbol | `:unquoted-link` |

**Task data** lives in `resources/seon/tasks/<owning-ns>.edn`.
- `seon.issue/task-entries` reads a file with edamame (nothing is evaluated; `#=` refuses) and validates it against
  `:seon.task/file`, a vector of closed `:seon.task/entry` maps. It refuses `:invalid-entry` (with the humanized
  member), `:misplaced-id` (an id outside `<owning-ns>.task`) and `:duplicate-id`.
- `tasks-tx` reconciles by `:seon.task/id` through the existing `replacement-tx`. Unchanged input gives an empty
  delta. An entry removed from its file retracts its task.
- `index-tasks!` writes nothing when the delta is empty. Otherwise it commits `[:db.fn/call #'tasks-tx files]`, so
  the writer re-decides the transaction.

**Lookups** are functions of a held database value:
- `task-edges` returns subjects, proofs, and the must-read referrers of the task's subjects;
- `open-tasks` answers function → open ids;
- `task-findings` returns `:dangling` and `:triage`.

**Identity (3b.6).** For an authored task the id keyword *is* the identifying aspect, component plus name, written
literally. No separate digest is minted. A generated task (producer plus subject) would need `seon.id`; that is not
in this slice.

**Seed.** The file is `resources/seon/tasks/seon.fn.edn`. It holds `:seon.fn.task/tests-reaching-saturates`, from
"Defects the runs exposed" (reach saturates: 1.9 s, 6.5 GB, 1,720 tests). Its subjects `seon.fn/gate-set` and
`seon.fn/tests-reaching` carry the id. It is raised, with no proof yet.

## Evidence (read-only probes in default's JVM, pid 35446)

1. `seon.program/canonical-row` drops an attribute the family map does not declare (1.24 ms). That is why the three
   entity maps gained member lines.
2. `seon.fn.analyzer/analyze` on a 4-line source took 8.6 ms and showed:
   - `^{:seon/tasks …}` on a deftest gives meta with no `:from-var` on the keyword;
   - the attribute-map-after-name form gives keyword entries with `:from-var bad` and no meta;
   - `::a/z` under `:as-alias` gives meta `#{:pq.a/z}` and a keyword entry `{:auto-resolved true}` with no
     `:from-var`;
   - var and ns entries carry `row`/`col`/`end-row`/`end-col`;
   - must-read `'pq.t/good` arrives as `(quote pq.t/good)`.
3. The three datalog shapes were run on literal datoms:
   - subject/proof with `ground` → `#{[a/f false] [a/t true]}`;
   - referrers with `or`/`and` → `[a/g :a.task/y]`;
   - dangling with `not` → `[:a.task/x]`.
4. edamame:
   - `#=` → "Read-eval not allowed";
   - `'a/f` → `(quote a/f)`, which `:seon.program/edge-symbol` refuses as `:invalid-entry`;
   - a bare `#{a/f}` reads as symbols.

## Root runs (serially, after adopting all committed paths together)

```
bin/test-check default --ns seon.issue.task-ingestion-test
bin/test-check default --ns seon.fn-test
bin/test-check default --ns seon.issue-test
```

The regressions assert the wanted behaviour:
- ingest → lookups both ways;
- index-tasks and reconcile both return zero writes on re-ingest;
- removing `g`'s annotation drops only that subject, while proofs and `f` stay;
- each annotation refusal by rule;
- duplicate, invalid and misplaced EDN;
- a dangling id appears in `:dangling`.

Timings are not measured. Record the three runs' wall time, and `seon.fn/rows` on the fixture root, parent against
this change.

## Smaller cut if root holds the ceiling (about 30 lines less)

Defer the typed link edges (`:seon.fn/must-read`, `:seon.fn/see-also`, `:seon.task/must-read`, the referrers
lookup and `:unquoted-link`) to slice 3. What remains is ingesting `:seon/tasks` plus task intent and evidence.

## Commits

See `git log --oneline -- resources/seon/schemas/seon.task.edn src/seon/fn.clj src/seon/issue.clj
test/seon/issue/task_ingestion_test.clj`. They are not pushed.

**Paths to release** once root accepts the slice:
- `resources/seon/schemas/seon.fn.edn`, `seon.test.edn` and `seon.ns.edn`;
- `resources/seon/schemas/seon.task.edn`;
- `resources/seon/tasks/`;
- `src/seon/fn.clj` and `src/seon/issue.clj`;
- the test.
