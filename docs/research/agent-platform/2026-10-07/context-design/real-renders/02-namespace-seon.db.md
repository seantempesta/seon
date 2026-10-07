---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, render]
---

# Reference: the namespace `seon.db`

Kind: namespace (the task's own `:seon.task/ns`). Declared AI render: `:seon.ns/ns` → `seon.render.ns/render-ai`,
which lists public functions through `seon.sci.eval/directory-value` (what `dir` reads).

Call:

```clojure
(def ns-entity (seon.db/pull dbh [:seon.ns/name :seon.ns/doc :seon.ns/requires] [:seon.ns/name 'seon.db]))
(seon.render.ns/render-ai {:seon.render/value ns-entity :seon.db/db dbh :seon.render/distance 1})
```

Reply as the agent sees it (166 ms, 18,883 armed calls; 4,132 tokens whole, 896 shown):

```clojure
#:seon.repl{:value {:seon.ns/name seon.db,
 :seon.ns/doc
   "The one database namespace for all things Datahike. Reads and writes use",
 :seon.ns/requires
   [clojure.core.cache.wrapped clojure.set clojure.test.check.generators
    clojure.walk datahike.api datahike.connections datahike.connector
    datahike.constants datahike.datom datahike.db datahike.db.interface
    datahike.db.utils datahike.pull-api datahike.query datahike.schema
    …19 (drop 15 (get-in result/e06e71564dca1 [:seon.ns/requires]))],
 :seon.repl/columns [:sym :arglists :doc :in :out],
 :seon.repl/rows
   [[seon.db/*conn*
     ()
     "The current cluster's live branch connection, bound by its owning pass."
     #:seon.error{:at #inst "2026-10-07T05:51:03.566Z",
      :expected :seon.sci.eval/present-declaration,
      :layer :seon.sci.eval/program,
      …5 (get-in result/e06e71564dca1 [:seon.repl/rows 0 3])}
     #:seon.error{:at #inst "2026-10-07T05:51:03.566Z",
      :expected :seon.sci.eval/present-declaration,
      …6 (get-in result/e06e71564dca1 [:seon.repl/rows 0 4])}]
    [seon.db/*read-database*
     ()
     "An explicitly handed immutable read basis, scoped by an evaluation."
     …2 (drop 3 (get-in result/e06e71564dca1 [:seon.repl/rows 1]))]
    [seon.db/*read-evidence-sink*
     ()
     "An optional invocation-local atom collecting Datahike read evide"…+4 (subs (get-in result/e06e71564dca1 [:seon.repl/rows 2 2]) 64)
     …2 (drop 3 (get-in result/e06e71564dca1 [:seon.repl/rows 2]))]
    [seon.db/absent-call
     (…1 (get-in result/e06e71564dca1 [:seon.repl/rows 3 1]))
     "`tx-data` when `lookup-ref` name"…+40 (subs (get-in result/e06e71564dca1 [:seon.repl/rows 3 2]) 32)
     …2 (drop 3 (get-in result/e06e71564dca1 [:seon.repl/rows 3]))]
    [seon.db/apply-diff
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 4]))]
    [seon.db/arity-mismatches
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 5]))]
    [seon.db/as-of
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 6]))]
    [seon.db/attribute-installed?
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 7]))]
    [seon.db/basis-t
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 8]))]
    [seon.db/branch-connection
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 9]))]
    [seon.db/branch-database
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 10]))]
    [seon.db/call-reach
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 11]))]
    [seon.db/call-with-custody
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 12]))]
    [seon.db/call-with-root-bindings
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 13]))]
    [seon.db/call-without-custody
     …4 (drop 1 (get-in result/e06e71564dca1 [:seon.repl/rows 14]))]
    …50 (drop 15 (get-in result/e06e71564dca1 [:seon.repl/rows]))]}, :findings [{:seon.repl/finding :large-value, :seon.eval/value-tokens 4132, :seon.eval/shown-tokens 896, :seon.repl/fix "A value this big usually means a query or function returns more than it should: select what you need."}], :result result/e06e71564dca1, :ms 166, :calls 18883}
```

## What is wrong

- **Useless for this task.** The task touches `q` and three private helpers. The render lists 65 public names
  alphabetically, cut after 15 (`*conn*` … `call-without-custody`); `q` is never reached, and private functions
  never appear. A namespace render cannot serve as "the code to change".
- **The doc line is cut mid-sentence**: "Reads and writes use". `first-doc-line` takes the first physical line. Task
  `seon.render.ns/a-doc-line-is-the-first-physical-line-not-the-first-sentence`.
- **Each dynamic Var row carries two timestamped error maps** as `:in` and `:out`. Task
  `seon.sci.eval/dir-shows-each-uncontracted-def-as-two-timestamped-errors`.
- **Positional rows** (`[sym arglists doc in out]`) force the reader to count columns; after the cut a row is
  `[seon.db/as-of …4 …]`, a name and nothing else.
- **34 requires** are listed before the functions. They are not useful for this task.
- **Doc strings are cut** at 64 and 32 characters (value printer budget; lane source-display has this in flight).
- **Cost**: 166 ms and 18,883 armed calls for one namespace, which is too slow for a brief that renders several.

## What the render should do

- The namespace AI face should be short: the doc's first sentence, the function count, and the operation
  `(dir seon.db)`. The full directory belongs to `dir`, an explicit read.
- A task brief should render the *functions the task names*, not their namespace. Pull the namespace in only when the
  task names it alone (a `:namespace`-kind task), and then show its public names as one vector of symbols, not rows.
