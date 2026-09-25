---
type: landing
status: "stopped: ownership boundary"
created: 2026-09-25
---

# Lane impl-task-ingestion-opus: slice 1 (7.9) stopped at the ownership boundary (2026-09-25)

**Broken first: nothing landed.** Slice 1 cannot be written inside the owned paths. There are no production edits,
no tests, and nothing for root to run yet.

## Why (verified, not inferred)

1. **Schema placement refuses the spec's names in `seon.issue.edn`.** `seon.schema.edn/validate-resource-placement!`
   (`src/seon/schema/edn.clj:261-306`) throws `::misplaced-attribute` unless each key's namespace equals the file
   name. This rules out two things:
   - `:seon.task/id`, `:seon.task/intent`, `:seon.task/needs` and `:seon.task/must-read` (3b.6, 7.1) must live in
     `resources/seon/schemas/seon.task.edn`;
   - an authored-key row attribute `:seon/tasks` would need `seon.edn`.

   Neither file is owned. Declaring the attributes as `:seon.issue/*` in the owned file would contradict 3b.6, and
   every file, test and render would then have to be renamed.
2. **Row attributes exist only if the family's entity map declares them.** `seon.program/shapes-in`
   (`src/seon/program.cljc:256-272`) is "THE ONE ANSWER" for which attributes a program row owns, and
   `canonical-row` strips everything else. Probe in default's JVM, read-only:
   `(seon.program/canonical-row {:seon.fn/sym 'pp.x/f :seon.program/definition-digest "d" :seon.issue/title "t" :seon.fn/doc "x"})`
   kept `#{:seon.fn/sym :seon.program/definition-digest :seon.fn/doc}` in 1.24 ms. The undeclared key was dropped.

   So the edges that `var-row` and `namespace-row` would emit are neither written nor exactly replaced unless
   `:seon.fn/fn` (`seon.fn.edn:127`), `:seon.test/test` (`seon.test.edn:90`) and `:seon.ns/ns` (`seon.ns.edn:10`)
   list them. Without that, the "remove an annotation drops only that edge" regression cannot pass.

## Smallest cut needing ownership (root decides)

Grant these additional files, one line or member each, for about 12 schema lines:
- **New `resources/seon/schemas/seon.task.edn`:**
  - `:id`, a keyword identity whose `:seon.program/row-schema` is the issue entity: option (a) of one-repl §3.5,
    transitional second identity;
  - `:intent`, `:needs` and `:must-read`;
  - `:tasks`, `[:set :qualified-keyword]`, holding keyword values and never refs (R2);
  - `:must-read-targets` and `:see-also-targets`, typed edges.
- **The three row entity maps**, gaining `[:seon.task/tasks {:optional true}]` plus the two link members.

  On typed link values: an `:or` of symbol, keyword and string maps to an EDN-encoded string
  (`seon/schema/datahike.clj:115-122`). A native encoding needs one attribute per target kind (symbol, keyword,
  path). That is a design choice for the reviewer, not something this lane should settle.

The rest of the plan is unchanged and fits the owned files within the ceilings. Estimated additions:

| Part | Where | Lines |
|---|---|---|
| Edges and refusals, reusing `capability-symbol` and `seed-symbol` | `var-row` and `namespace-row` | about 30 |
| edamame reader, reconcile by `:seon.task/id`, and lookups | `seon.issue` | about 45 |
| Regressions | tests | about 55 |

## Evidence and limits

- Default runtime at start: pid 35446, `:faulted` with 21 open faults that predate this lane (`bin/seon status`).
- No branch, JVM or scratch root was created. There is nothing to clean up.
