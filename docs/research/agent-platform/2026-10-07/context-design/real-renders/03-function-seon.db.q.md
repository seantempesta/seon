---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, render]
---

# Reference: the function `seon.db/q`

Kind: function (the task's `:seon.task/must-read`, and named in its intent). Declared AI render: `:seon.fn/fn` →
`seon.render.ns/function-ai`.

Call:

```clojure
(defn fn-unit [s] {:seon.render/value (seon.db/pull dbh [:seon.fn/sym :seon.fn/doc :seon.fn/source :seon.fn/spec] [:seon.fn/sym s])
                   :seon.db/db dbh :seon.render/distance 1})
(seon.render.ns/function-ai (fn-unit 'seon.db/q))
```

Reply as the agent sees it (0 ms, 102 armed calls; 1,517 tokens whole, 838 shown):

```clojure
#:seon.repl{:value {:seon.fn/sym seon.db/q,
 :seon.fn/doc
   "Run a Datalog query over explicit inputs or the current database value.",
 :seon.render.ns/source-lines
   ["(defn q"
    "  \"Run a Datalog query over explicit inputs or the current database value."
    "  Nil scalar inputs, including members of tuple and collection bindings,"
    "  return an invalid-read error before executing the query. False and empty"
    "  collections remain ordinary inputs.\""
    "  {:seon/tasks #{\"d6e3b775f474\"}" "   :malli/schema"
    "   [:=> [:catn [:seon.db/query-or-database [:or :seon.db/database-value :seon.error/value :seon.db/query :seon.db/query-args]] [:seon.db/arguments [:* {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason \"Datahike Datalog bindings carry arbitrary values. The function guard derives input count and database source positions from the parsed query.\", :gen/elements [[]]} :seon.schema/value]]] [:or :seon.schema/value :seon.db/invalid-read-error] [:fn #:error{:message \"The supplied arguments must match the query's :in (default [$]); every source input must be a database value. Use (seon.db/q query input ...) with $ elided, or (seon.db/q database query input ...) with the database first.\", :fn seon.db/query-guard-message} seon.db/query-call-valid?]]}"
    "  [query-or-database & arguments]"
    "  (if-not (or (db.utils/db? query-or-database) (vector? query-or-database) (string? query-or-database)"
    "                (:find query-or-database) (:query query-or-database))"
    "    (refused-read query-or-database 'seon.db/q)"
    "    (let [explicit-database? (db.utils/db? query-or-database)"
    "        query-input" "        (if explicit-database?"
    "          (first arguments)" "          query-or-database)"
    "        argument-inputs" "        (if explicit-database?"
    "          (rest arguments)" "          arguments)]" "    (try"
    "      ;; This disambiguates a Datalog map query from Datahike's argument map"
    "      ;; before Seon decides where the ambient database belongs."
    "      (let [normalized (query/normalize-q-input query-input argument-inputs)"
    "            aligned"
    "            (if (and (map? query-input) (contains? query-input :args) (seq argument-inputs))"
    "              (query-input-shape-error (:query normalized)"
    "                                       (into (vec (:args normalized)) argument-inputs))"
    "              (aligned-query-arguments"
    "               (when explicit-database? query-or-database)"
    "               (:query normalized)"
    …30 (drop 32 (get-in result/efcfc56139612 [:seon.render.ns/source-lines]))]}, :findings [{:seon.repl/finding :large-value, :seon.eval/value-tokens 1517, :seon.eval/shown-tokens 838, :seon.repl/fix "A value this big usually means a query or function returns more than it should: select what you need."}], :result result/efcfc56139612, :ms 0, :calls 102}
```

## What is wrong

- **Code is shown as quoted strings**, every `"` escaped. The printer packs several short lines onto one output line
  (`"        query-input" "        (if explicit-database?"`), which destroys the indentation that makes Clojure
  readable. Task `seon.render.ns/function-source-lines-print-several-per-line`; the printer half (multi-line strings)
  is lane source-display's.
- **The part the task is about is cut.** `query-input-values` is called on source line 38, but the printer stops at
  line 32 and leaves the rest behind a dig form. The agent must dig to find the line the task names.
- **Repeated.** The docstring is shown as `:seon.fn/doc` and again inside the source. The contract is one 700-character
  line, which the reader cannot scan.
- **The `:seon/tasks` tag holds a raw handle** `"d6e3b775f474"` (installed syntax). At HEAD it is the task symbol.
- **No callers or callees.** The docstring says they are "its related data", but they are not attached to an explicit
  call.

## What the render should do

- Show source as code: one string the printer shows as a multi-line code block, whole for a function the task names
  (a 62-line function is about 1,500 tokens, which is worth it when it is the subject).
- Drop `:seon.fn/doc` when the source is shown.
- Add `:callers` (symbols only) for private subjects. For this task that is the seven `query-patterns` sites.
