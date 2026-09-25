---
type: landing
status: "slice 1 revised for review 03bf7d6cb and slice 1b; committed but unverified until root runs the commands below"
created: 2026-09-25
---

# Lane impl-task-ingestion-opus: task ingestion (7.9), review revision and slice 1b (2026-09-25)

## Broken, unproven or stopped first

- **The dangling gate on program merge is not wired: named and stopped.** The gate is
  `seon.cluster.source/accept-merge!` (`src/seon/cluster/source.clj`, the `accept-merge!` defn after
  `prepare-merge!`). My grant covers that file only for publication beside `index-issues!`, so I did not edit it.
  - To do: call `seon.issue/task-findings` on the tested program with the destination's tasks, and refuse
    `:dangling-id` before `db/transact!`.
  - Because of this, the review's case "program-gate failure leaving admitted task data" has no test.
  - Merge isolation is covered only by the regression that `seon.program/program-attributes` holds
    `:seon.task/tasks` and never `:seon.task/id` or `:seon.task/intent`.
- **Development and adoption clusters do not receive tasks.** Publication does not add task identities to
  `:seon.reconcile/adopt-identities`: `adopt-tx` and the cluster adoption path are another owner's. A cluster that
  adopts from current-src therefore gets the annotations without the task entities. Publication is the only place
  that refuses on dangling handles, so this reports rather than breaks.
- **Nothing has run.** Under the LOAD RULE I loaded no changed namespace and ran no tests. What ran:
  - clj-kondo: 0 errors on every touched file;
  - read-only REPL probes (below).
- **Publication tests call the private seam `#'seon.cluster.source/index-tasks!`, not `publish!`.** A full `publish!`
  run on a store is root's platform tier.
- **Still later work, as before:** schema-attribute annotations (7.1c), runtime task creation (7.8) and seed edges
  (7.9b) are neither changed nor proved.
- **Stale skill claim, relayed from the review and not fixed here.** The testing skill says `seon.test/run` does not
  arm, but `src/seon/test.clj:2060` calls `instrument/apply!`. The owner of `.agents/skills/clojure-testing` should
  fix it.

## Publication refusal after cdc4567e6, fixed

`bin/seon init --dev default` refused with "A stored error member must have a storable registered attribute."
- The cause was my required `[:seon.error/offending :seon.schema/value]` in `:seon.task/refused-error`, which
  `:seon.task/conflict-error` inherits. `:seon.error/offending` is not a registered attribute, and
  `seon.schema.internal/assert-error-declaration!` (`internal.cljc:274-281`) requires every required member to be
  storable.
- It is now optional, the way every other refusal carries it. The rest are unchanged: the required members are
  `:seon.task/refused` (enum) and `:seon.task/holder` (symbol), both storable, and base, current and proposed stay
  optional.
- Probe, read-only and pure: `seon.schema/build-projection` over default's forms plus `seon.task.edn` and the two
  `seon.fn` link attributes. The old form reproduced the refusal (identity `:seon.task/conflict-error`, member
  `:seon.error/offending`); the fix builds 3,434 forms in 138 ms. `storable-attribute-in?` is true for
  `:seon.task/id`, `/tasks`, `/refused`, `/holder` and `:seon.fn/must-read`.

## The review's findings, each fixed at its owner

1. **Identity (owner clarification 2026-09-25).** The handle is `seon.issue/task-handle`, which is `seon.id/id` over
   `[ns name kind]`: a 12-hex string such as `"300aca1ad57c"`.
   - It is written literally in task EDN and `:seon/tasks`.
   - `:seon.task/name` and `:seon.task/kind` are declared data; `:seon.task/ns` comes from the file name.
   - Admission computes the handle and refuses a supplied `:seon.task/id` that differs (`:handle-mismatch`, with
     `:seon.error/expected`).
   - Intent never enters the handle. A regression changes the intent and keeps the handle.
   - The seed was updated.
   - Because ids are now strings, a `::alias` id refuses through the annotation schema, and the keyword-position
     machinery was deleted.
2. **Revision conflict (7.16).** `index-tasks!` takes `:seon.task/basis` (the declarations the caller read) or reads
   it now through `task-basis`.
   - `tasks-tx` runs as `[:db.fn/call …]` in the writer. For each touched handle it compares the current declaration
     with the basis.
   - A mismatch refuses `:revision-conflict` with `:seon.task/base`, `/current` and `/proposed`. Equal proposals are
     no-ops.
   - A regression makes two edits from one basis: the second refuses, and the first value stays.
3. **Reading the file.** `edamame/parse-string-all` requires exactly one vector (`:not-one-vector`).
   - Reader failures refuse as `:unreadable-file`, with the edamame exception as cause. Its location sits in the
     `:seon.error/chain` data.
   - `:invalid-entry` keeps the authored value in `:seon.error/offending` and the humanized explanation in
     `:seon.task/explanation`.
   - Tested: trailing form, `[{`, `#=`, `{}`, unknown member, handle mismatch, duplicate. Each refusal is asserted to
     leave `basis-t` unchanged.
4. **Placement.** The analyzer's own kondo parse (`parsed-facts`) records lists whose first attribute map, after the
   name and an optional docstring, holds `:seon/tasks`. The var entry carries `:task-map-after-name?`, and the row
   producer refuses it only on a deftest. Body usage of `:seon/tasks` and `::x` stays legal; the positive and negative
   cases are paired.
5. **Empty sets.** Admission turns empty optional sets into absence, so an `:evidence #{}` re-ingest writes nothing.
   Clearing `:must-read` retracts it; both are tested.
6. **Declared errors.** `read-error` passes `db/q`, `db/pull` and `db/transact!` refusals through whole.
   - `open-tasks`, `task-edges`, `task-findings`, `task-basis` and `index-tasks!` name their alternatives in their
     result contracts.
   - A real `db/pull` refusal (an uninstalled lookup attribute) is tested. The probe returned
     `:seon.db/invalid-read true`.
7. **Stage and result schemas.**
   - Named: `:seon.task/annotation`, `/entry`, `/file`, `/files`, `/declaration`, `/basis`, `/links`, `/edges`,
     `/findings`, `/request` and `/report`.
   - `:seon.task/refused-error` is `[:and :seon.error/base …]`, and `:seon.task/conflict-error` extends it. They are
     built by `seon.fn/task-refused!` through `seon.error.refusal/diagnostic`, which keeps the value until render.
   - Contracts were added to `var-row`, `namespace-row`, every new helper and the test helpers.

P2 items:
- **Namespace referrers.** `task-edges` includes `:seon.ns/name` referrers. The test covers them, alias-resolved
  see-also (`` `s/join `` → `clojure.string/join`), and namespace subjects.
- **Linear comparison.** Stored rows are indexed once by handle. Owned attributes come from `:seon.task/declaration`,
  not a hand list, and each link is computed once.
- **Explicit file set.** A deleted file enters as `"[]"`, an empty declaration: `task-files` produces it for a changed
  path that is gone. Duplicates are checked across the whole input, and a path outside `resources/seon/tasks/`
  refuses (`:misplaced-file`).

## Slice 1b: publication

`src/seon/cluster/source.clj` gains `index-tasks!` beside `index-issues!`.
- `publish!` selects changed task paths with `seon.issue/task-path?`, or all task files on a full publication, then
  calls the task writer.
- It then refuses any dangling handle through `task-findings`, with every handle's holders in
  `:seon.task/dangling`, before the head advances.
- `:seon.task/changed?` joins the no-op decision, so a task-only change publishes.
- Schema installation already precedes this step (the scratch `missing` transaction), and `resources/` is a declared
  input root (`deps.edn :paths`).

Integration cases on the seam:
- coherent task and annotation admission;
- a no-op repeat;
- an annotation whose task is absent at the destination, which refuses naming only that handle;
- a removed task file, which retracts the task, leaving its annotations dangling so publication refuses.

## Probe evidence (read-only, default JVM pid 35446)

- **Parse shape.** In kondo's parse, `^{…}` metadata sits in the name node's `:meta` vector, not in `:children`. An
  attribute map after the name is `(nth children 2)`, or 3 after a string node with `:lines`. `::a/z` is a keyword
  node with `:namespaced? true`.
- **Stage schemas.** All new schemas compiled in a lazy registry over default's projection (0.59 ms for the samples).
  The one exception was `:seon.task/links`, which needs the `:seon.fn/must-read` declared in `seon.fn.edn`.
  Validation results:
  - `^:seon/tasks true` refuses as "invalid type";
  - a keyword id refuses as "should be a string";
  - `[:intnet]` refuses as "disallowed key".
- **Handles.** `seon.id/id` gives `"300aca1ad57c"` for `['seon.fn "tests-reaching-saturates" :function]` and
  `"043df611d179"` for the fixture.
- **Refused read.** `seon.db/pull` on an uninstalled lookup attribute returns `{:seon.db/invalid-read true …}`.

## Root runs (serially, after adopting all committed paths together; schemas first)

```
bin/test-check default --ns seon.issue.task-ingestion-test
bin/test-check default --ns seon.fn-test
bin/test-check default --ns seon.issue-test
bin/test-check default --ns seon.cluster.source-test
```

Then run one publication with a task-only edit and one with no change, and record the wall time for each run.
Timings are not measured. Record `seon.fn/rows` over `src` on the parent and on the change, because `task-links` now
validates every holder's annotation.

## Sizes and commits

For this lane's paths, from `1de64b45b^` to HEAD:

| Area | Lines |
|---|---|
| `seon.issue` | +166 |
| `seon.fn` | +47/−6 |
| analyzer | +19/−3 |
| `source.clj` | +24/−1 |
| schemas | +58 |
| seed | +6 |
| tests | +137 |

The owner accepted the size. Every commit is ≤100 added lines. Revision commits: d7139e8e5, 24cc5eebb, 1552a2d3a,
31cf5360f, ca5a385c8, 1405b7a13 and 438d402eb, plus this note. None is pushed.
