---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, agent-loop]
---

# Shape: hybrid (conversation, brief re-rendered at the end)

An append-only conversation (system, then assistant and REPL-reply turns) with the task brief re-rendered and placed
at the END of every prompt, after the newest REPL reply. The previous call's brief is not kept.

Sources: the system message is [system-message.md](system-message.md) verbatim. Every map in the brief is a real
render output from [real-renders/](real-renders/00-index.md), except lines marked `[glue]`, which are hand-drawn. Assistant
messages are hand-drawn (plausible, written for this mockup). REPL replies are **real**: the exact replies default gave
when those forms ran in agent `context-mockups`'s SCI context on 2026-10-07, including the wrong `:contract :missing`
in reply 2 (task `seon.sci.eval/the-defn-reply-prints-before-its-own-change-applies`). Sizes are estimated tokens
(characters / 4).

## The full prompt as sent on turn 3

**system** (990 tokens)

~~~~text
# You are a Seon agent at a Clojure REPL

You work on one task in your own branch of a live Clojure program. What you define stays on your branch until root
merges it. Nothing you do can break the cluster: the database is immutable and every change can be rolled back.

## How a reply works

- Write your reasoning as prose. Put code to run in fenced ```clojure blocks. **TARGET**: prose outside fences becomes
  comments; today every prose word is evaluated as a symbol.
- Every form runs in order, in your current namespace. You see the results in the next message, so do not act on a
  result in the same reply that asks for it.
- Each result is a map: `{:value … :result result/e1a2b3 :ms 3}`. On failure it is `{:error {…}}`, with the expected
  schema and the offending value as data.

  ```clojure
  (get-in result/e1a2b3 [:seon.task/proofs 0])   ; result/e… is the live value; pass it or dig into it
  ```

- A large value is shown cut. Each cut ends with the form that shows the rest; run that form.

## Look things up

- `(doc seon.db/q)` returns the summary, arglists and contract. `(dir my.task)` lists a namespace's public
  functions.
- `(my.program/source 'seon.db/query-patterns)` returns the source, private functions included.
  `(clojure.repl/source-fn 'f)` returns the source text. **TARGET**: plain `(source f)` and `(apropos "x")`.
- `(my.program/callers {:seon.program/subject 'seon.db/query-patterns})` returns callers and call sites.
- `(my.program/search "query-input-values")` searches program source.

## How to reference things

Write references fully qualified. The same text is a lookup you can run, and the system resolves it for your context.

| You mean | Write | Look it up with |
|---|---|---|
| a function, macro or def | `seon.db/q` | `(doc seon.db/q)`, `(my.program/source 'seon.db/q)` |
| a test | `seon.db-overhead-test/query-preparation-has-bounded-armed-work` | `(my.program/source 'seon.db-overhead-test/…)` |
| a namespace | `seon.db` | `(dir seon.db)` |
| a schema, attribute or error type | `:seon.db/query` | `(seon.db/pull (seon.db/db) '[*] [:seon.schema/key :seon.db/query])` |
| a task | `seon.db/the-query-wrapper-costs-36x-datahike` | **TARGET**: `(my.task/brief 'seon.db/the-query-wrapper-costs-36x-datahike)` |
| a live result | `result/e1a2b3` | evaluate it; it exists only in your context |
| a file | `docs/research/…/notes.md` | `(my.fs/read {:my.fs/path "…" :my.fs/max-bytes 4096})` |

- A test proves a task through its metadata:

  ```clojure
  (deftest ^{:seon/tasks #{'seon.db/the-query-wrapper-costs-36x-datahike}} a-query-derives-its-shape-once …)
  ```

- Never write `file:line`, commit ids or alias names like `d/q` in anything you leave behind; nothing resolves them.

## Change code

- Enter the namespace, then define as in Clojure:

  ```clojure
  (in-ns 'seon.db)
  (defn- query-patterns
    "The data patterns of a parsed query's where clauses."
    {:malli/schema [:=> [:cat :map] [:sequential :any]]}
    [parsed-query] …)
  ```

- Every function, private ones included, gets a one-line `:malli/schema` contract. Without one, the reply warns you
  and offers an example.
- `def` is a scratch value that is lost at restart. `defn` and `deftest` become program entities on your branch.
- To delete, use `(my.program/ns-unmap! 'seon.db/f)`.

## Prove it

- `(my.test/check {:seon.test/changed ['seon.db-overhead-test/a-query-derives-its-shape-once]})` runs the named tests,
  and the tests reaching named functions, on your branch. Read `:seon.test/passed?` and the failing assertions.
- Your task is done when its proof tests pass on your branch. **TARGET**: you are told, and the conversation ends.
  Today: `(my.program/propose-merge! {})` once the proofs pass.
- If the task cannot be done as written, say why and stop. **TARGET**: `(my.task/bail! "why")`.

## Ask

- `(my.message/send {:my.message/to "root" :my.message/content "…"})` sends a message. Sending does not end your reply.
~~~~

**assistant #1** (80 tokens) [hand-drawn]

~~~~text
The task says `seon.db/query-patterns` is walked by seven callers and `seon.db/query-input-values` runs twice. Before
changing anything I want the callers and the second input-values site.

```clojure
(my.program/callers {:seon.program/subject 'seon.db/query-patterns})
(my.program/source 'seon.db/may-read-vectors?)
```
~~~~

**user #2: REPL reply** (841 tokens) [real]

~~~~text
#:seon.repl{:value {:seon.program/subject seon.db/query-patterns,
 :seon.program/kind :seon.fn/sym,
 :seon.db/basis-t 536872799,
 :seon.program/unknown
   [#:seon.program{:gap :outside-graph,
     :message
       "This supplied database value answers; callers outside its program graph are unknown."}],
 :seon.program/callers
   #{seon.db/may-read-vectors? seon.db/encode-query-request
     seon.db/malformed-query-pattern-error seon.db/query-find-attributes
     seon.db/query-variable-attributes seon.db/native-query-result?
     seon.db/query-value-type-error},
 :seon.program/call-sites
   [#:seon.fn{:sym seon.db/encode-query-request,
     :form-span [118122 120192],
     :call-arity 1}
    #:seon.fn{:sym seon.db/malformed-query-pattern-error,
     :form-span [112015 112885],
     :call-arity 1}
    #:seon.fn{:sym seon.db/may-read-vectors?,
     :form-span [129957 131326],
     :call-arity 1}
    #:seon.fn{:sym seon.db/native-query-result?,
     :form-span [123829 124631],
     :call-arity 1}
    #:seon.fn{:sym seon.db/query-find-attributes,
     :form-span [116631 118120],
     :call-arity 1}
    #:seon.fn{:sym seon.db/query-value-type-error,
     :form-span [113828 115863],
     :call-arity 1}
    #:seon.fn{:sym seon.db/query-variable-attributes,
     :form-span [115865 116629],
     :call-arity 1}]}, :result result/eeb3a6946e270, :ms 18, :calls 1016}
#:seon.repl{:value {:sym seon.db/may-read-vectors?,
 :kind :function,
 :line 2482,
 :file "src/seon/db.clj",
 :source
   "(defn- may-read-vectors?\n  \"Whether a query can read vector storage, judged from the installed schema\n   alone, so a query over cardinality-one attributes never derives the\n   declarations: cells are installed and the query has a pull, rules,\n   `missing?`/`get-else`/`get-some`, or a data pattern whose attribute is\n   unknown or installed cardinality-many (as every vector attribute is).\"\n  {:malli/schema [:=> [:cat :map :map :map] :boolean]}\n  [installed parsed-query encoded]\n  (boolean\n   (and (contains? installed :seon.db.vector/cells)\n        (let [bound (query-input-values parsed-query (:args encoded))]\n          (or (some #(instance? Pull %) (parser/find-elements (:qfind parsed-query)))\n              (some #(instance? RulesVar (:variable %)) (:qin parsed-query))\n              (some #{'missing? 'get-else 'get-some} (tree-seq coll? seq (:query encoded)))\n              (some (fn [pattern]\n                      (let [node (second (:pattern pattern))\n                            attributes (cond (instance? Constant node) [(:value node)]\n                                             (instance? Variable node) (get bound (:symbol node)))]\n                        (or (empty? attributes)\n                            (some #(= :db.cardinality/many (get-in installed [% :db/cardinality])) attributes))))\n                    (query-patterns parsed-query)))))))",
 :doc
   "Whether a query can read vector storage, judged from the installed schema\n   alone, so a query over cardinality-one attributes never derives the\n   declarations: cells are installed and the query has a pull, rules,\n   `missing?`/`get-else`/`get-some`, or a data pattern whose attribute is\n   unknown or installed cardinality-many (as every vector attribute is).",
 :contract [:=> [:cat :map :map :map] :boolean]}, :result result/e5d97e761a9e3, :ms 48, :calls 77}
~~~~

**assistant #2** (161 tokens) [hand-drawn]

~~~~text
All seven callers take `parsed-query` and call `query-patterns` once each. `may-read-vectors?` derives input values
from `(:args encoded)`, the encoded arguments, not the aligned ones `seon.db/q` already holds, so I must check that they
bind the same values before passing `input-values` through. First, `query-patterns` gets its contract so the proof can
count it.

```clojure
(in-ns 'seon.db)
(defn- query-patterns
  "The data patterns of a parsed query's where clauses, in order."
  {:malli/schema [:=> [:cat :map] [:sequential :seon.schema/value]]}
  [parsed-query]
  (filter #(instance? Pattern %) (parsed-nodes (:qwhere parsed-query))))
```
~~~~

**user #3: REPL reply, then the task brief re-rendered now** (1,894 tokens) [real reply; brief real re-render, glue marked]

~~~~text
#:seon.repl{:value #namespace[seon.db], :result result/e48f1b1090e6c, :ns seon.db, :ms 0}
#:seon.repl{:value {:seon.render.var/pending
   [seon.schema.vector-storage-test/datalog-over-a-vector-attribute-means-membership],
 :seon.render.var/contract :missing,
 :seon.render.var/symbol seon.db/query-patterns,
 :seon.render.var/example
   (defn query-patterns {:malli/schema [:=> [:cat :int] :int]} [x] x),
 :seon.render/operations
   [(my.test/check {:seon.test/changed [seon.db/query-patterns]})]}, :result result/ee3b7c281612b, :ms 4, :calls 226}

---

# Your task: seon.db/the-query-wrapper-costs-36x-datahike   [glue]

Each reference in the task is shown once, by its declared AI render.   [glue]

## Task   [glue: seon.render.ns/task-ai, intent = the proposed corrected text]

```clojure
{:seon.task/id "d6e3b775f474",
 :seon.task/kind :function,
 :seon.task/ns seon.db,
 :seon.task/must-read
   #{seon.db/q seon.db/query-input-values seon.db/query-patterns seon.db/may-read-vectors?},
 :seon.task/name "the-query-wrapper-costs-36x-datahike",
 :seon.task/intent
   "`seon.db/q` derives the same values from one parsed query several times per call. `seon.db/query-input-values` runs twice (in `seon.db/q` and again in `seon.db/may-read-vectors?`), and `seon.db/query-patterns` walks the `:qwhere` tree through `seon.db/parsed-nodes` in seven callers. A scalar query costs 117 us and 189 KB against `datahike.api/q`'s 4 us and 5 KB. Derive the patterns and the input values once in `seon.db/q` and pass them on; give `seon.db/query-patterns` a contract. The query stays a `:seon.db/query`, and a refusal stays a `:seon.db/invalid-read-error`."}
```

## Done when these pass on your branch   [glue]

```clojure
{:seon.test/sym seon.db-overhead-test/a-query-derives-its-shape-once,   ; [glue: proposed proof, not installed]
 :seon.render.test/state "unrun or incomplete"}
{:seon.test/sym
   seon.db-overhead-test/query-preparation-has-bounded-armed-work,
 :seon.render.test/state "pass",
 :seon.test/run-basis-t 536872188,
 :seon.render.test/host {:seon.test/host :seon.test.host/in-process}}
```

Run: `(my.test/check {:seon.test/changed ['seon.db-overhead-test/a-query-derives-its-shape-once]})`   [glue]

## Functions named   [glue: seon.render.ns/function-ai each]

```clojure
{:seon.fn/sym seon.db/q,
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
    …30 (drop 32 (get-in result/efcfc56139612 [:seon.render.ns/source-lines]))]}
{:seon.fn/sym seon.db/query-input-values,
 :seon.render.ns/source-lines
   ["(defn- query-input-values"
    "  {:malli/schema [:=> [:cat :seon.schema/value [:sequential :seon.schema/value]] :map]}"
    "  [parsed-query arguments]" "  (or (apply merge-with set/union"
    "             (map query-binding-values (:qin parsed-query) arguments)) {}))"]}
{:seon.fn/sym seon.db/query-patterns,
 :seon.fn/doc
   "The data patterns of a parsed query's where clauses, in order.",
 :seon.render.ns/source-lines
   ["(defn- query-patterns"
    "  \"The data patterns of a parsed query's where clauses, in order.\""
    "  {:malli/schema [:=> [:cat :map] [:sequential :seon.schema/value]]}"
    "  [parsed-query]"
    "  (filter #(instance? Pattern %) (parsed-nodes (:qwhere parsed-query))))"]}
{:seon.fn/sym seon.db/parsed-nodes,
 :seon.render.ns/source-lines
   ["(defn- parsed-nodes" "  [root]"
    "  (tree-seq #(or (map? %) (coll? %))" "            #(cond"
    "               (map? %) (vals %)" "               (coll? %) %"
    "               :else nil)" "            root))"]}
```

`seon.db/may-read-vectors?` is in must-read but was not rendered for this mockup; see turn 1.   [glue]

## Schemas named   [glue: seon.render.schema/render-ai each]

```clojure
{:seon.schema/key :seon.db/query,
 :seon.schema.usage/referenced-by [:seon.db/query-args],
 :seon.schema.usage/referenced-by-count 1,
 :seon.schema.usage/contract-count 5,
 :seon.schema/definition [:or [:vector :seon.schema/value] :map :string],
 :seon.schema.usage/contracts
   [seon.db/arity-mismatches-with seon.db/call-edges
    seon.db/declared-arity-bounds seon.db/q seon.db/storage-q-in]}
{:seon.schema/key :seon.db/invalid-read-error,
 :seon.schema.usage/referenced-by-count 3,
 :seon.schema.usage/contract-count 76,
 :seon.schema/definition
   [:and
    :seon.error/base
    [:map
     {:error/message
        "must carry the :seon.db/invalid-read distinguishing member"}
     [:seon.db/invalid-read :seon.db/invalid-read]
     [:seon.db/refused-read-operation :seon.db/refused-read-operation]
     [:seon.error/message :seon.error/message]]],
 :seon.schema.usage/referenced-by
   [:seon.db.read/unreadable-declarations-error :seon.render.walk/cut
    :seon.test/host-error],
 :seon.schema.usage/contracts
   [my.task/held seon.ai/agent-overlay seon.ai/agent-setting-attributes
    seon.background/await seon.background/poll
    seon.cluster.host/agent-entity seon.cluster.host/retire!
    seon.cluster.prompt/calibration-for]}
```

## Named but not resolvable   [glue]

```clojure
[{:ref datahike.api/q :resolves-to :dependency-var}]   ; [glue]
```
~~~~

**Plausible turn-3 output** [hand-drawn]:

~~~~text
The reply says the contract is missing, but I declared one. Read the stored definition before assuming it was dropped,
and check the encoded-versus-aligned question on a real query.

```clojure
(select-keys (my.program/source 'seon.db/query-patterns) [:contract :doc])
(let [query '[:find ?key . :where [_ :seon.schema/key ?key] [(= ?key :seon.cluster/name)]]]
  (seon.db/q query (seon.db/db)))
```
~~~~

## Ten calls

The model behind the tables uses the measured sizes: system 995 tokens; brief 1,697 at start and 1,756 after the
`query-patterns` defn, plus about 40 per later changed function; one turn (assistant message plus REPL reply) 610 on
average, which is turns 1-2 of this mockup (live run 1 averaged about 900 per turn including its refresh units).
Assume code changes before calls 3, 5, 7 and 9, and proof runs before calls 6 and 9. "Cached prefix" is the longest
prefix identical to the previous call's prompt, which is what a provider prompt cache can hit.

| Call | Prompt | Cached prefix | Uncached | Cached share |
|---:|---:|---:|---:|---:|
| 1 | 2,692 | 0 | 2,692 | 0% |
| 2 | 3,302 | 995 | 2,307 | 30% |
| 3 | 3,971 | 1,605 | 2,366 | 40% |
| 4 | 4,581 | 2,215 | 2,366 | 48% |
| 5 | 5,231 | 2,825 | 2,406 | 54% |
| 6 | 5,841 | 3,435 | 2,406 | 59% |
| 7 | 6,491 | 4,045 | 2,446 | 62% |
| 8 | 7,101 | 4,655 | 2,446 | 66% |
| 9 | 7,711 | 5,265 | 2,446 | 68% |
| 10 | 8,321 | 5,875 | 2,446 | 71% |
| **10 calls** | **55,242** | **30,915** | **24,327** | **56%** |

## What is re-sent and what goes stale

- **Re-sent each call**: history (cached) plus the brief (never cached, because it moves to the end each time).
- **Stale**: nothing. The brief is always current and sits closest to the model's next token, where attention is
  strongest. History still shows superseded code in old replies, but the brief after it is newer.
- **Growth**: linear like the conversation, plus a fixed uncached ~1.8k brief every call: about 3x the conversation's
  uncached tokens.
- **Variant**: re-render only the parts that changed (proof state, redefined functions). That is the conversation
  shape with a richer footer.
