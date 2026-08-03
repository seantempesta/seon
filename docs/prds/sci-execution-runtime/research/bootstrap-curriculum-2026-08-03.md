---
type: research
status: active
tags: [research, agent, bootstrap, context]
---

# Bootstrap curriculum: what the agent's opening forms should teach

Research for the redesign of `resources/seon/bootstrap.edn` — the ordered
form series every agent receives, re-evaluated every turn so its outputs are
always current.

## Method and honesty statement

Every number and every output in this document was produced by a live
`eval_clj` probe on 2026-08-03. The forms are recorded verbatim; outputs are
abridged only by eliding list tails, always marked `...`.

The shared `default` cluster is **degraded** and could not be used. Every
query against it returns a flat error value:

```clojure
(seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym _]])
;; => {:seon.error/kind :seon.db/invalid-read
;;     :seon.error/message "datahike/query$_resolve_clause$fn__49979"
;;     :seon.error/data {:seon.db/operation :seon.db/q
;;                       :seon.db/exception-class "java.lang.NoClassDefFoundError"}}
```

All measurements below therefore come from an **isolated operator root**
booted for this research and shut down afterwards:

```bash
mkdir -p tmp/bootstrap-research-root
bin/seon --root tmp/bootstrap-research-root init      # 50s, commit 6a70f3ed…
bin/seon --root tmp/bootstrap-research-root start research   # 6.5s
```

Probes run in **`door` mode** (`seon.sci.eval/evaluate` against the cluster's
shared SCI ctx). This matters: door mode is the agent's real evaluation
environment, and it differs from `jvm` mode in ways the curriculum depends on
(see [Gap 1](#gap-1-jvm-mode-has-no-ambient-cluster-and-q-swallows-it)).

I read `resources/seon/bootstrap.edn`, `tmp/bootstrap-forms-lane-draft-2026-08-03.diff`,
and `src/seon/cluster/reply.clj` end to end.

## 1. The census

One form, door mode:

```clojure
{:fns          (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym _]])
 :public-fns   (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym _]
                            (not [?f :seon.fn/private? true])])
 :ns-distinct  (seon.db/q '[:find (count-distinct ?n) . :where [?f :seon.fn/ns ?n]])
 :ns-rows      (seon.db/q '[:find (count ?n) . :where [?n :seon.ns/name _]])
 :schemas      (seon.db/q '[:find (count ?s) . :where [?s :seon.schema/key _]])
 :tests        (seon.db/q '[:find (count ?t) . :where [?t :seon.test/sym _]])
 :caps         (seon.db/q '[:find (count ?f) . :where [?f :seon.effect/capability _]])
 :fns-with-keywords (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/keywords _]])
 :fns-with-calls    (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/calls _]])}
```

Actual output (53 ms):

```clojure
{:caps 10, :fns 2195, :fns-with-keywords 2251, :tests 906, :ns-distinct 167,
 :fns-with-calls 2230, :schemas 1454, :ns-rows 262, :public-fns 588}
```

| Fact | Count |
|---|---|
| `:seon.fn` rows | 2195 |
| — of which private | 1607 |
| — of which public | 588 |
| namespaces holding functions | 167 |
| `:seon.ns` rows | 262 |
| `:seon.schema` keys | 1454 |
| `:seon.test` rows | 906 |
| — carrying `:seon.fn/calls` edges | 856 |
| capability functions | **10** |

`:fns-with-keywords` (2251) and `:fns-with-calls` (2230) exceed the 2195
function rows because **test rows carry `:seon.fn/keywords` and
`:seon.fn/calls` too** — verified by pulling a `:seon.test` row, which has
both attributes alongside `:seon.test/sym`. That is what makes
tests-for-a-function answerable at all, and it means keyword search spans
tests and functions in one query.

### The capability surface, by name

```clojure
(sort-by first
  (seon.db/q '[:find ?sym ?cap ?doc
               :where [?f :seon.effect/capability ?cap]
                      [?f :seon.fn/sym ?sym]
                      [(get-else $ ?f :seon.fn/doc "(no docstring)") ?doc]]))
```

Complete, unabridged output:

| Function | Capability | Docstring |
|---|---|---|
| `my.edit/exact` | `seon.edit.jvm/edit` | Replace one exact string occurrence, or all occurrences when explicit. |
| `my.edit/form` | `seon.edit.jvm/edit` | Edit one unambiguous named top-level Clojure form. |
| `my.edit/lines` | `seon.edit.jvm/edit` | Replace an exact, digest-fenced one-based inclusive line window. |
| `my.fs/glob` | `seon.fs.jvm/glob` | Find a bounded set of paths without following symbolic links. |
| `my.fs/read` | `seon.fs.jvm/read` | Read one bounded byte window and digest through the filesystem owner. |
| `my.fs/stat` | `seon.fs.jvm/stat` | Read no-follow attributes for one filesystem path. |
| `my.fs/write` | `seon.fs.jvm/write` | Conditionally replace one file through the filesystem owner. |
| `my.shell/run` | `seon.shell.jvm/run` | Run one foreground argv vector and return complete process evidence. |
| `my.web/fetch` | `seon.web.jvm/fetch` | Fetch one bounded HTTP(S) URL through the protected web owner. |
| `my.web/search` | `seon.web.jvm/search` | Search the configured provider and return source rows plus raw evidence. |

**Answer to the owner's question: yes, decisively.** Ten rows, each one line,
each naming both the agent-facing function and the system owner behind the
door. The docstrings are genuinely good — every one states the bound
("one bounded byte window", "without following symbolic links", "one
foreground argv vector"). An agent reading this table learns the *shape* of
the effect model (every capability is bounded, singular, and owned) before it
learns any individual call. This one query replaces the rejected
`(dir my.fs)`/`(dir my.edit)`/`(dir my.web)` enumeration and teaches strictly
more.

### The whole agent-facing surface is 21 functions

```clojure
(sort (seon.db/q '[:find [?sym ...]
                   :where [?f :seon.fn/ns ?e] [?e :seon.ns/name ?n]
                          [(clojure.string/starts-with? ?n "my.")]
                          [?f :seon.fn/private? false] [?f :seon.fn/sym ?sym]]))
```

```clojure
;; 7 namespaces: my.background my.edit my.fs my.message my.run my.shell my.web
;; 23 function rows, 21 public:
("my.background/await" "my.background/poll" "my.edit/exact" "my.edit/form"
 "my.edit/lines" "my.edit/valid-form-operation?" "my.fs/content?" "my.fs/glob"
 "my.fs/read" "my.fs/stat" "my.fs/write" "my.fs/write-precondition?"
 "my.message/decline" "my.message/send" "my.run/complete" "my.run/wait"
 "my.shell/output?" "my.shell/run" "my.shell/stdin?" "my.web/fetch"
 "my.web/search")
```

This is the single most important census fact for the curriculum. The entire
`my.*` surface fits in one screen, and **one form finds all of it**. There is
no reason to enumerate namespaces in the bootstrap when a query that teaches
the ref-walk returns the same list and stays correct as the surface grows.

`seon.db`'s public surface is comparably small — 16 functions:

```clojure
("seon.db/as-of" "seon.db/commit-id" "seon.db/committed-value-identity"
 "seon.db/connection?" "seon.db/database-value?" "seon.db/datoms" "seon.db/db"
 "seon.db/entity" "seon.db/history" "seon.db/pull" "seon.db/pull-many"
 "seon.db/q" "seon.db/read-evidence" "seon.db/read-evidence-current?"
 "seon.db/since" "seon.db/transact!")
```

## 2. The query recipes

### The data model the recipes teach

Three facts about the shape, all discovered by probing and all of which an
agent must internalize:

1. **`:seon.fn/ns` is a ref**, not a string. `[?f :seon.fn/ns ?n]` binds an
   entity id; you must walk `[?e :seon.ns/name ?n]` to get the name. My first
   namespace query returned empty precisely because I assumed a string.
2. **`:seon.fn/sym` is a string; `:seon.ns/name` is a symbol.** Passing
   `"my.fs"` where a symbol is required throws a `ClassCastException` inside
   the query (returned as a flat error value). See [Gap 3](#gap-3-symns-inconsistency).
3. **Schema rows are flat and readable**: `:seon.schema/key` (keyword) plus
   `:seon.schema/form` (string).

```clojure
(seon.db/pull '[*] (first (seon.db/q '[:find [?s ...]
                                       :where [?s :seon.schema/key :my.fs/read-request]])))
;; => {:db/id 1140
;;     :seon.schema/key :my.fs/read-request
;;     :seon.schema/form "[:map [:my.fs/path :my.fs/path] [:my.fs/byte-offset {:optional true} …]]"
;;     :seon.schema.admission/source :core}
```

### Recipe grades

| Recipe | Latency | Teachability | Ergonomics raw |
|---|---|---|---|
| All capabilities | 3 ms | **Very high** — teaches the effect door exists and is small | Fine; 3-clause query |
| All `my.*` public functions | 10 ms | **Very high** — teaches the ns-is-a-ref walk | Fine |
| All schemas in a namespace | 6 ms | Medium — teaches `namespace` as a Datalog predicate | Fine |
| One function's full contract | 13 ms | High — teaches arity→refs→schema chain | **Poor** (see below) |
| Which functions accept/return a schema key | 11 ms | **Very high** — teaches the graph is bidirectional | Fine |
| Tests for one function | 12 ms | **Very high** — teaches tests are graph citizens | Fine |
| Tests for a namespace | ~14 ms | High | Fine |
| Keyword usage search | 5 ms | **Very high** — the find-me-code-touching-X query | Fine |
| Docstring substring search | 6 ms | High — teaches Datalog predicates over strings | Fine |

**Latency is a non-issue.** The most expensive recipe is 19 ms and the full
docstring scan across all 2195 rows is 6 ms. No helper can be justified on
performance grounds — only on repetition.

### Keyword search — the highest-value recipe

```clojure
(sort (seon.db/q '[:find [?sym ...] :in $ ?kw
                   :where [?f :seon.fn/keywords ?kw] [?f :seon.fn/sym ?sym]]
                 :seon.cluster.run/process))
```

5 ms, 30+ hits spanning implementation and tests:

```clojure
("seon.bootstrap/seed-tx" "seon.cluster.agent-test/handle" "seon.cluster.agent/held-run-id"
 "seon.cluster.agent/turn-step" "seon.cluster.loop/call-turn" "seon.cluster.loop/close-turn"
 "seon.cluster.run/claim-call" "seon.cluster.run/held-run" "seon.cluster.run/held?"
 "seon.cluster.run/recover-call" "seon.cluster.run/retract-custody" ...)
```

This is the query that answers *"what code touches attribute X?"* — the
question an agent asks constantly when modifying an unfamiliar subsystem, and
the one with no other honest answer. It deserves a worked example.

### Docstring / name substring search

Because `:seon.fn/sym` is a **string**, name search needs no coercion:

```clojure
(seon.db/q '[:find [?sym ...]
             :where [?f :seon.fn/doc ?d] [(clojure.string/includes? ?d "digest")]
                    [?f :seon.fn/sym ?sym]])
;; => 16 hits, 6 ms
```

### Function contract — the one recipe with bad ergonomics

```clojure
(seon.db/q '[:find ?sym ?spec ?dir ?key ?form
             :in $ ?sym
             :where
             [?f :seon.fn/sym ?sym]
             [?f :seon.fn/spec ?spec]
             [?f :seon.fn/arities ?a]
             (or-join [?a ?s ?dir]
               (and [?a :seon.fn.arity/input-refs ?s]  [(ground :in) ?dir])
               (and [?a :seon.fn.arity/output-refs ?s] [(ground :out) ?dir]))
             [?s :seon.schema/key ?key]
             [?s :seon.schema/form ?form]]
           "my.fs/read")
```

Output (13 ms), abridged:

```clojure
#{["my.fs/read" "[:=> [:cat :my.fs/read-request] [:or :my.fs/read-result :seon.error/value]]"
   :in  :my.fs/read-request "[:map [:my.fs/path :my.fs/path] …]"]
  ["my.fs/read" "[:=> …]" :out :my.fs/read-result "[:map [:my.fs/path …] [:my.fs/digest …] …]"]
  ["my.fs/read" "[:=> …]" :out :seon.error/value "[:map [:seon.error/kind …] …]"]}
```

The query is correct and the `or-join`/`ground` pairing genuinely teaches
something. But the flat relation **repeats `?spec` on every row**, and the
result is a set, so nothing groups. This is the one place where the raw form
is meaningfully worse than a shaped result — and the strongest argument for
the function-view helper.

### Errors are queryable and pervasive

```clojure
(count (seon.db/q '[:find [?sym ...] :in $ ?k
                    :where [?s :seon.schema/key ?k] [?a :seon.fn.arity/output-refs ?s]
                           [?f :seon.fn/arities ?a] [?f :seon.fn/sym ?sym]]
                  :seon.error/value))
;; => 56
```

**56 functions declare `:seon.error/value` as a return arm.** That single
number teaches errors-as-values better than any prose sentence could, and it
is a one-line form. Strong curriculum candidate.

## 3. Helper verdicts

The bar: *"anything we find ourselves doing twice is not teaching anything and
is likely common."* Every helper is one less fishing lesson, so I only count
compositions I actually reached for twice while doing this research.

### (a) Namespace overview — **EARNS A HELPER**

I ran this composition three times unprompted (`my.fs`, `my.run`, and again
for `seon.db`/`seon.schema` in modified form). Raw prototype:

```clojure
(let [n 'my.run]
  {:functions    (sort (seon.db/q '[:find [?sym ...] :in $ ?n
                                    :where [?e :seon.ns/name ?n] [?f :seon.fn/ns ?e]
                                           [?f :seon.fn/private? false] [?f :seon.fn/sym ?sym]] n))
   :schemas-used (sort (seon.db/q '[:find [?key ...] :in $ ?n
                                    :where [?e :seon.ns/name ?n] [?f :seon.fn/ns ?e]
                                           [?f :seon.fn/arities ?a]
                                           (or [?a :seon.fn.arity/input-refs ?s]
                                               [?a :seon.fn.arity/output-refs ?s])
                                           [?s :seon.schema/key ?key]] n))
   :tests        (sort (seon.db/q '[:find [?t ...] :in $ ?n
                                    :where [?e :seon.ns/name ?n] [?f :seon.fn/ns ?e]
                                           [?tt :seon.fn/calls ?f] [?tt :seon.test/sym ?t]] n))})
```

Actual output for `my.run` (14 ms) — this is the shape, unabridged:

```clojure
{:functions ("my.run/complete" "my.run/wait")
 :schemas-used (:my.run/completed :my.run/note :my.run/result :my.run/wait :seon.error/value)
 :tests ("my.run-test/a-blank-completion-is-an-error-value-not-a-throw"
         "my.run-test/a-disposition-is-an-ordinary-value"
         "my.run-test/a-wrong-type-is-the-same-error-value-never-a-throw"
         "seon.cluster.loop-test/a-disposition-is-read-only-when-it-really-is-one"
         "seon.cluster.turn-test/a-completing-disposition-closes-in-the-terminal-transaction"
         "seon.cluster.turn-test/a-waiting-disposition-frees-the-agent-and-keeps-its-note" …17 total)}
```

**Verdict: earns it.** Three separate queries with a shared `:in` binding,
run together every time, and the crucial property the owner asked for holds —
`:schemas-used` reaches schemas *wherever declared*, so `:seon.error/value`
(declared in `error.edn`, not `my.run`) appears. That is the data-flow
understanding a namespace-scoped schema query would miss. Note also what the
test list teaches for free: Seon test names are full sentences, so the
overview doubles as a behavioral specification.

Honest name and contract:

```clojure
(defn overview
  "Every public function in one namespace with the schemas its contracts
  reference and the tests that reach it."
  {:malli/schema [:=> [:cat [:map [:seon.ns/name :symbol]]]
                  [:map [:seon.fn/syms [:sequential :string]]
                        [:seon.schema/keys [:sequential :keyword]]
                        [:seon.test/syms [:sequential :string]]]]}
  [request] …)
```

One namespaced map in, one namespaced map out. The name `overview` is honest;
`describe`/`explore` would be vaguer nouns for the same thing.

### (b) Function view — **EARNS A HELPER, and fixes a real ergonomic defect**

I ran the contract query twice (`my.fs/read`, then the accepts/returns variant
for `:seon.error/value`), and the flat-relation problem above is genuine, not
cosmetic. A composed result would be:

```clojure
{:seon.fn/sym "my.fs/read"
 :seon.fn/spec "[:=> [:cat :my.fs/read-request] [:or :my.fs/read-result :seon.error/value]]"
 :seon.fn/doc "Read one bounded byte window and digest through the filesystem owner."
 :seon.schema/inputs  {:my.fs/read-request "[:map …]"}
 :seon.schema/outputs {:my.fs/read-result "[:map …]" :seon.error/value "[:map …]"}
 :seon.test/syms []}
```

**Verdict: earns it** — on ergonomics, not on repetition alone. The raw query
needs `or-join` + `ground`, which is the single most advanced Datalog
construct in the whole recipe set, and it still returns an ungrouped set with
a duplicated spec column. Honest name: `contract`, taking
`{:seon.fn/sym "…"}`.

Caveat worth recording: it surfaced that **capability functions have no tests
reaching them**:

```clojure
(seon.db/q '[:find [?t ...] :in $ ?sym
             :where [?f :seon.fn/sym ?sym] [?tt :seon.fn/calls ?f] [?tt :seon.test/sym ?t]]
           "my.fs/read")
;; => []
```

versus `"seon.db/q"` which returns 30+. A helper that renders an empty
`:seon.test/syms` prominently makes that gap visible to every agent that looks
at a capability, which is a feature.

### (c) Everything else — **NO HELPER**

Capability listing, `my.*` listing, keyword search, docstring search,
accepts/returns-a-schema: each is a single 2–4 clause query, each runs in
under 11 ms, each output is already readable, and each teaches a distinct
piece of the data model. Wrapping any of them would trade a fishing lesson for
nothing. **Two helpers total, both compositions of three-or-more queries.**

## 4. The curriculum

Priority key: **W** = worked example (a form with `;;` comments in the
bootstrap), **H** = one-form hint (present, uncommented), **D** = defer until
its mechanism lands.

Stability is for prefix-cache ordering (measured 67 % hit rate): **stable**
content must precede **volatile** content.

| # | Lesson | Why it matters | Candidate form | What its live output teaches | Stability | Pri |
|---|---|---|---|---|---|---|
| 1 | Session contract: forms, `;;` comments, batching | Everything else is written in this medium; round trips are the scarce resource | the shape of the bootstrap session itself + `(in-ns '…)` | that comments survive, that forms return values, that batching is normal | stable | **W** |
| 2 | Orientation / self | An agent must know which namespace it owns and that it is one of many | `(in-ns '{{seon.ns/name}})` | its own name, that namespaces are owned | stable | **W** |
| 3 | The graph is the map: census | Converts "what exists?" from prose into a query habit | the census form (§1) | 2195 fns / 1454 schemas / 906 tests — the system is big and countable | volatile | **W** |
| 4 | Finding capabilities | The effect door is the only way out; 10 rows is memorable | capability query (§1) | the complete door surface + that each capability is bounded and owned | stable | **W** |
| 5 | Finding the agent surface | Replaces enumerating `my.*` namespaces | `my.*` public query (§1) | 21 functions, and the ns-is-a-ref walk | stable | **W** |
| 6 | `doc` / `dir` from graph facts | The cheap drill-down after a query narrows the field | `(doc my.run/complete)` | docstrings are graph facts, rendered in the comment grammar | stable | **W** |
| 7 | Writing a durable defn with an honest open contract | The one persistence rule; currently taught **wrongly** | open-map `defn` + a call with an extra key | that declared keys validate and extra keys are ignored | stable | **W** |
| 8 | Contract violation is a value, not a crash | Agents must not fear experimenting | `(largest)` / `(largest [])` | a flat error value it can read and repair | stable | **W** |
| 9 | Errors-as-values are pervasive | Generalizes lesson 8 from anecdote to law | the 56-count form (§2) | 56 functions declare `:seon.error/value` | volatile | **W** |
| 10 | Keyword search | The "what touches attribute X?" question | keyword query (§2) | code and tests both indexed by the attributes they use | volatile | **H** |
| 11 | Tests are graph citizens | Enables test-first and behavioral reading | tests-for-a-function query | test names are sentences = a spec | volatile | **H** |
| 12 | Registry-first schema declaration | Prevents duplicate schema keys | `seon.schema/registered?` probe before `register!` | 1454 keys already exist; look first | stable | **H** |
| 13 | `seon.db` facts | Durable memory across runs | `seon.db/q` in the census already demonstrates reads | reads need no ceremony | stable | **H** |
| 14 | `my.message` / `my.run` lifecycle | How a run ends; how agents reach each other | `(dir my.message)` + `(doc my.run/complete)` | the two terminal dispositions | stable | **W** |
| 15 | Capabilities through the door | Actually using a capability once | one small `my.fs/read` | the request/result map shape and its digest | volatile | **H** |
| 16 | `my.background` await/wake | Long work without blocking a run | `my.background/await` in a hint | 3 functions: `await`, `poll` | stable | **D** |
| 17 | Self-improvement: editing one's own starting forms | The accretion endgame | prose-in-db hint only | — | stable | **D** |

Lessons 12, 13 and 17 are the ones I judge genuinely **undemonstrable as a
useful live form today**: a `register!` demo would mutate the shared cluster
ctx from the bootstrap, a `transact!` demo would write junk facts every turn,
and the self-improvement mechanism is still under design. These belong in the
database as instruction facts, not as forms.

### Cache ordering

Stable-first ordering falls out cleanly: lessons 1, 2, 4, 5, 6, 7, 8, 14 are
all stable (their outputs do not change between turns as the graph grows), and
lessons 3, 9, 10, 11, 15 are volatile (counts and hit lists move). The current
bootstrap already interleaves them; the draft diff correctly identified this
and introduced a `;; Volatile … stay after this boundary` marker. **Keep that
idea** — it is the one part of the draft I would carry forward unchanged.

### Top three lessons, drafted

These are written as an exemplary REPL session — the `;;` style is itself the
lesson, and the reply parser preserves it verbatim (verified below).

**Lesson 4 + 5 merged — finding what you can do:**

```clojure
;; Nothing here is a fixed toolkit. Every function this cluster knows is a
;; fact in one graph, so "what can I do?" is a query, not a list to memorize.
;; Capabilities are the functions that reach OUT of the process — filesystem,
;; shell, network. Each declares the system owner standing behind its door.
(seon.db/q '[:find ?sym ?doc
             :where [?f :seon.effect/capability _]
                    [?f :seon.fn/sym ?sym]
                    [?f :seon.fn/doc ?doc]])
```

```clojure
;; Everything else you call is an ordinary function. The `my.*` namespaces are
;; the ones written for you. Note `:seon.fn/ns` is a REF to a namespace
;; entity, so the query walks one hop to reach the name — that hop is how most
;; of this graph is shaped.
(sort (seon.db/q '[:find [?sym ...]
                   :where [?f :seon.fn/ns ?e]
                          [?e :seon.ns/name ?n]
                          [(clojure.string/starts-with? ?n "my.")]
                          [?f :seon.fn/private? false]
                          [?f :seon.fn/sym ?sym]]))
```

**Lesson 7 — writing something that lasts:**

```clojure
;; A defn with a complete :malli/schema becomes a durable fact: other agents
;; can find it by the same queries you just ran. Without a schema it lives
;; only in this session.
;;
;; Write the contract honestly. Declared keys are validated rigorously and a
;; missing required key fails — but maps stay OPEN, so a caller that supplies
;; extra keys is fine and its data is simply ignored. That is what lets a
;; contract grow without breaking the callers already written against it.
(defn largest
  "The row with the largest :amount."
  {:malli/schema [:=> [:cat [:sequential [:map [:label :string] [:amount :int]]]]
                  [:map [:label :string] [:amount :int]]]}
  [rows]
  (last (sort-by :amount rows)))
```

```clojure
;; The second row carries a key the contract never declared. It is ignored,
;; not refused — accretion in one line.
(largest [{:label "a" :amount 3}
          {:label "b" :amount 9 :note "extra data is fine"}])
```

```clojure
;; Break it on purpose. A contract violation comes back as a VALUE you can
;; read and repair; nothing throws into your run and nothing is wedged.
(largest)
```

## 5. The gaps

### Gap 1 — `jvm` mode has no ambient cluster, and `q` swallows it

In `jvm` mode `(seon.db/db)` returns a flat error value:

```clojure
{:seon.error/kind :seon.db/missing-connection-binding
 :seon.error/message "No current cluster connection is bound to seon.db/*conn*."}
```

That error value is then **accepted by `seon.db/q` as its `db` argument**, and
`q` returns `nil` instead of propagating it. My entire first census returned
`{:fns nil, :schemas nil, …}` with no indication of why. An error value
passed where a database value is required must return an error value, never
`nil`. **Missing behavior, not a missing fact** — and a silent one, which is
the worst kind.

### Gap 2 — the first contracted `defn` in a fresh cluster fails with a 274 KB internal error

This is the most serious finding in this document, because it lands exactly
where the bootstrap puts its most important lesson.

The first `defn` I evaluated in the freshly booted cluster:

```clojure
(defn largest-open
  "The row with the largest :amount; an explicit empty arm when there are none."
  {:malli/schema [:=> [:cat [:sequential [:map [:label :string] [:amount :int]]]]
                  [:or [:map [:label :string] [:amount :int]] [:map [:empty? [:= true]]]]]}
  [rows]
  (or (last (sort-by :amount rows)) {:empty? true}))
```

returned **276,363 characters** with `:seon.eval/allocated-bytes 264841944`
(264 MB) and:

```
seon.schema.internal/assert-non-nilable-value-schema! violated its contract
(invalid-input): [nil nil [{:value [:map {:seon.db/entity true, …
```

— followed by a dump of what appears to be the entire schema registry
(`:seon.ai.model/*` forms, print-face trees) as the error payload.

I isolated it. The shape is **not** the cause:

| Probe | Result |
|---|---|
| closed maps, plain map return (current bootstrap) | `:DEFINED-OK` |
| **open** maps, plain map return | `:OPEN-MAPS-OK` |
| closed maps, `[:or …]` return | `:CLOSED-OR-OK` |
| open maps, `[:or …]` return | `:OPEN-OR-OK` |
| **the identical failing form, re-run** | `:RERUN-OK` (1.1 MB, 1 ms) |

The same source that allocated 264 MB and failed now succeeds in 1 ms. So this
is a **first-contracted-`defn`-in-a-fresh-cluster-ctx** failure, not a schema-shape
failure. Root cause not isolated — the assertion lives at
`src/seon/schema/internal.cljc:313`, called from `src/seon/schema.clj:941` and
`:1092`.

Two defects, both needing issue notes:

1. the first contracted `defn` in a fresh cluster ctx fails; every fresh
   cluster's first agent hits this on the bootstrap's own lesson 7;
2. **an internal contract violation renders its entire candidate-forms
   registry into the error payload.** 274 KB is not a diagnostic, it is a
   denial of service against the agent's context window.

Related but distinct existing issue:
[contracted-defn-rebuilds-the-whole-schema-projection.md](docs/seon/issues/contracted-defn-rebuilds-the-whole-schema-projection.md)
records that every contracted `defn` costs 21–30 ms because
`projection-with-function-contract` is O(registry). Same hot spot, different
symptom — and the 264 MB allocation suggests the two share a root cause.

### Gap 3 — `:seon.fn/sym` is a string but `:seon.ns/name` is a symbol

```clojure
;; works
[?e :seon.ns/name ?n] [(clojure.string/starts-with? ?n "my.")]
;; ClassCastException: String cannot be cast to Symbol
(seon.db/q '[… [?e :seon.ns/name ?n] …] "my.fs")
```

Every other identity in the graph is a string (`:seon.fn/sym`,
`:seon.test/sym`), which makes name-substring search pleasantly uniform. The
namespace name being a symbol is the one exception, and it cost me a probe.
Either is defensible; the inconsistency is not.

### Gap 4 — quoting trap with no guard rail

Inside an already-quoted query, `'my.run` reads as `(quote my.run)` — a
`PersistentList` — and fails the same cast. The bare symbol works:

```clojure
[?e :seon.ns/name my.run]     ;; => works, 2 results
[?e :seon.ns/name 'my.run]    ;; => ClassCastException error value
```

This will bite every agent that writes queries. It is a curriculum item (pass
constants through `:in`), but it is also an argument for the error message
naming the offending clause rather than only the Java classes.

### Gap 5 — no test-for-capability coverage is visible as a fact

`my.fs/read` has zero tests reaching it via `:seon.fn/calls`, while `seon.db/q`
has 30+. Whether that means "untested" or "tested through a layer the call
edges do not cross" is **not answerable by query**. The missing fact is an
edge from a test to the capability it exercises through the door.

### Gap 6 — `System/nanoTime` is unavailable in door mode

```clojure
(System/nanoTime)
;; => :seon.sci.eval/evaluation-failed "Unable to resolve symbol: System/nanoTime"
```

Agents cannot time their own work with the obvious form. The
`:seon.sci.admit/record` returned with every evaluation *does* carry
`:seon.eval/duration-ms` and `:seon.eval/allocated-bytes`, which is the better
answer — but nothing tells the agent that, and the refusal does not point at
it. A one-line curriculum hint would close this.

## 6. Ugly output — tool and render feedback

Per the standing order, every probe output I found noisy, unreadable, or
misleading:

1. **The 274 KB `defn` error (Gap 2).** Worst offender by three orders of
   magnitude. An internal contract failure should render its own assertion and
   arguments, never the whole schema registry.

2. **`seon.db/pull '[*]` on a `:seon.fn` row is unreadable.** Refs come back
   unresolved (`:seon.fn/ns #:db{:id 1803}`), children elide to `[#]`, and —
   worst — a cardinality-many ref set renders as an inline **Markdown table**
   in the middle of an EDN map:

   ```
   :seon.fn.arity/output-refs
   | :db/id |
   |--------|
   |    587 |
   |    949 |
   , :seon.fn.arity/max 1, …
   ```

   A markdown table spliced inside a map literal is not valid EDN and not
   readable prose. This is the single ugliest render I met.

3. **Error values get shredded by ordinary collection functions.** When
   `seon.db/q` returned an error value and I passed it to `sort`, the map was
   destructured into key/value pairs and sorted:

   ```clojure
   ([:seon.error/data #:seon.db{…}] [:seon.error/kind :seon.db/invalid-read]
    [:seon.error/message "class java.lang.String cannot be cast to …"])
   ```

   The error survived but became much harder to recognize as an error. This is
   inherent to errors-as-values over maps, but it argues that
   `:seon.error/value` should carry a declared `:seon.render/ai` producer so it
   renders as an error even after a collection function has mangled it.

4. **The `ClassCastException` messages are pure JVM noise.** "class
   java.lang.String cannot be cast to class clojure.lang.Symbol
   (java.lang.String is in module java.base of loader 'bootstrap'; …)" — the
   module/loader clause is 60 % of the message and helps nobody. The useful
   information (which clause, which attribute, what was expected) is absent.

5. **`(doc …)` is good.** Recording the positive for calibration:

   ```
   -------------------------
   my.run/complete
   ([result])
   ; Finish this run with the reply the agent wants delivered.
   ;
   ;   Returns a completion value the run loop records with the final receipt.
   ;   Blank text returns a flat error value the agent can inspect and repair.
   ```

   Rendering the docstring in the agent's own comment grammar is a genuinely
   nice touch — the output is already valid to paste back into a reply.

## 7. Verified claims from the brief

- **The reply parser preserves leading comments verbatim.** Confirmed at
  `src/seon/cluster/reply.clj:77-84`: `prose-line` returns a line unchanged
  when it already starts with `;`, and prefixes `; ` otherwise. `plan-sources`
  (`:217-244`) then prepends the coalesced prose to the following form's
  source. So `;;` comments written by an agent survive exactly, and `;;;`
  would too.

- **The current bootstrap teaches `{:closed true}`, which is a defect against
  ruling #48.** Confirmed: open maps define successfully
  (`:OPEN-MAPS-OK` above). The prose at `resources/seon/bootstrap.edn:21-22`
  ("input maps must say `{:closed true}`") should be replaced, and the two
  `largest` worked-example forms with it. The draft diff's prose replacement
  is correct; its enumeration of `(dir my.fs)`/`(dir my.edit)`/`(dir my.web)`
  is not, and §1 above gives the query that replaces it.

## Cleanup

The isolated research root was shut down with
`bin/seon --root tmp/bootstrap-research-root down`. No shared cluster was
written to, and `resources/seon/bootstrap.edn` was not modified.
