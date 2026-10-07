---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, render]
---

# Reference: the task `seon.db/the-query-wrapper-costs-36x-datahike`

Kind: task (handle `d6e3b775f474`). Declared AI render: `:seon.task/task` → `seon.render.ns/task-ai`.

Call, in agent `context-mockups`'s SCI context on default (installed 42e569d9d), 2026-10-07:

```clojure
(def task-entity (seon.db/pull dbh '[*] [:seon.task/id "d6e3b775f474"]))
(seon.render.ns/task-ai {:seon.render/value task-entity :seon.db/db dbh :seon.render/distance 1})
```

`dbh` is `(seon.db/db (seon.cluster.boot/connection "default"))`, the cluster head.

Reply as the agent sees it (3 ms, 128 armed calls):

```clojure
#:seon.repl{:value {:seon.task/id "d6e3b775f474",
 :seon.task/kind :function,
 :seon.task/ns seon.db,
 :seon.task/must-read #{seon.db/q},
 :seon.task/name "the-query-wrapper-costs-36x-datahike",
 :seon.task/intent
   "seon.db/q costs 107 us and 472 KB for a two-clause query against d/q's 3 us and 6 KB (36x time, 76x allocation): query-patterns/parsed-nodes are tree-seq walked 5 times per call, query-input-values 4 times, and armed private checks add about 70 us (encode-query-request 18, decode-query-result 16, query-value-type-error 12, query-attribute-error 9 us). A trivial root run issues 62 q and 43 pull, 10-35 ms. Compute patterns and input values once per call; db? was fixed in the fork (cefd8a2bf).",
 :seon.related/data
   #:seon.task{:_tasks
      {:seon.related/items
         [{:seon.test/sym
             seon.db-overhead-test/query-preparation-has-bounded-armed-work,
           :seon.render.test/state "unrun or incomplete"}
          #:seon.fn{:sym seon.db/q,
           :doc
             "Run a Datalog query over explicit inputs or the current database value."}]},
    :must-read
      {:seon.related/items
         [#:seon.fn{:sym seon.db/q,
           :doc
             "Run a Datalog query over explicit inputs or the current database value."}]}}}, :result result/ed209c9c15280, :ms 3, :calls 128}
```

## What is wrong

- **The proof's state is wrong for the reader.** `:seon.render.test/state "unrun or incomplete"` comes from the
  related walk reading the *viewer's* branch, where `seon.test/recorded-result` finds nothing. The cluster head records
  the proof green (3 pass), and `my.test/check` on the agent's branch also passes (7.8 s). Task
  `seon.test/an-agent-branch-sees-none-of-its-clusters-test-evidence`.
- **The proof is not named as a proof.** It hides under `:seon.task/_tasks` (a reverse-attribute name), mixed with the
  subject function. Nothing says "done when these pass". Task `seon.task/open-tasks-with-all-proofs-green-are-not-flagged`
  (a green proof on an open task must be visible in the view).
- **Repeated.** `seon.db/q` and its doc line appear twice (as a `:seon/tasks` subject and as a must-read).
- **Not added by `task-ai`.** `:seon.related/data` is attached by the reply's own render selection, because the returned
  map still satisfies `:seon.task/task`. Calling the render explicitly does not give the bare render output.
- **The intent is one run-on string**, with history the agent cannot use ("db? was fixed in the fork (cefd8a2bf)") and
  numbers that are stale (measured today: 117 us and 189 KB against `datahike.api/q`'s 4 us and 5 KB).
- **Missing.** No `:seon.task/evidence` (dropped by `task-ai`), no `:seon.task/sym` (not installed on default), and no
  "how to prove" form such as `(my.test/check {:seon.test/changed ['…]})`.

## What the render should do

- Drop: `:seon.task/id` (the agent uses the symbol), `:seon.task/kind`, and the reverse-attribute key names.
- Add: `:seon.task/proofs` as a vector of `{test, state, failing assertion}` read at the task home or reused by reach
  digest; and an operation `(my.test/check {:seon.test/changed [proof …]})`.
- Show each reference once. The brief resolves the references; the task render names them.
