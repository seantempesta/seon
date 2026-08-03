---
type: research
status: active
tags: [research, agent, bootstrap, context]
---

# Bootstrap curriculum: what the agent's opening forms should teach

Research for the redesign of `resources/seon/bootstrap.edn` — the ordered
form series every agent receives, re-evaluated every turn so its outputs are
always current.

## Method

Every number, form, and output below was measured on **2026-08-03 against the
live `default` cluster**, reforked from today's published `current-src`
(commit `6a70fed1`, carrying the keyword edges, error-class schemas, and the
`my.*` tools). All probes ran in **`door` mode** — `seon.sci.eval/evaluate`
against the cluster's shared SCI ctx, which is the agent's real evaluation
environment and differs from `jvm` mode in ways the curriculum depends on
(see [Gap 1](#gap-1-jvm-mode-has-no-ambient-cluster-and-q-swallows-the-error)).
Every probe was read-only: `q`, `pull`, `datoms`, `doc`, `dir`. Nothing was
transacted and no cluster was reset.

Latencies are the `:seon.eval/duration-ms` the evaluation record returns with
every form, so they measure the work an agent would actually pay for.

Query-shape recommendations are grounded in the Datahike engine source under
`reference-code/datahike/`, cited by `file:line`, and every recommended shape
was then confirmed live.

Read end to end: `resources/seon/bootstrap.edn`,
`tmp/bootstrap-forms-lane-draft-2026-08-03.diff`, `src/seon/cluster/reply.clj`,
and the relevant spans of `reference-code/datahike/src/datahike/query.cljc`.

(History, one line: an earlier pass of this research ran against an isolated
operator root because the shared cluster's Datahike was temporarily broken.
That cluster has been reforked and every measurement here is fresh against it.)

## 0. The actionable-output filter

One test governs every recipe and every lesson in this document:

> **What would the agent DO differently, having seen this output?**

A form earns a place in the bootstrap only if its output changes the agent's
next action. Output that merely informs is cut, because the bootstrap is
re-evaluated every turn and every form spends context forever.

The filter's two poles, both measured:

- **PASSES — the capability query.** Ten rows, each a name plus a one-line
  contract. The agent's next action is immediate and specific: `(doc my.fs/read)`
  on the one that matches its task. The output *is* a menu.
- **FAILS — the census.** `{:fns 2238, :caps 10, :schemas 1475, :tests 915}`
  is a wall of counts. Knowing there are 2238 functions tells an agent nothing
  it can act on; it cannot call a number. **The census is cut from the
  bootstrap** and survives in this document only as background for the
  designer.

Everything below is graded on this filter, honestly, including recipes I like.

## 1. Census (background for the designer, NOT a bootstrap form)

```clojure
{:fns     (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym _]])
 :caps    (seon.db/q '[:find (count ?f) . :where [?f :seon.effect/capability _]])
 :schemas (seon.db/q '[:find (count ?s) . :where [?s :seon.schema/key _]])
 :tests   (seon.db/q '[:find (count ?t) . :where [?t :seon.test/sym _]])}
{:fns 2238, :caps 10, :schemas 1475, :tests 915}
```

The measured evaluation took 11 ms.

| Fact | Count |
|---|---|
| `:seon.fn` rows | 2238 |
| — public | 607 |
| namespaces holding functions | 170 |
| `:seon.schema` keys | 1475 |
| `:seon.test` rows | 915 |
| — carrying `:seon.fn/calls` edges | 865 |
| capability functions | **10** |
| public `my.*` functions | **22** |
| functions returning `:seon.error/value` | 56 |
| tests reaching `my.fs/read` | **0** |

Two of these numbers *are* actionable, and they are the ones that shape the
curriculum rather than appearing in it: the capability surface is **10**, and
the whole agent-facing `my.*` surface is **22**. Both fit on one screen, which
is why the bootstrap can teach *finding* instead of *enumerating*.

## 2. Query efficiency, grounded in the Datahike engine

I read the engine's post-processing pipeline to find out what work can be
pushed into the query rather than done in Clojure afterwards.

### The pipeline, and what it means

`post-process-result` (`reference-code/datahike/src/datahike/query.cljc:4185-4200`)
applies, in this fixed order:

```
:with truncation → aggregate → pull → -post-process → order-by (+ offset/limit)
→ return-maps → non-ordered offset/limit
```

Three consequences that decide every recipe below:

1. **`pull` inside `:find` runs after dedup** (`query.cljc:4194`, implementation
   at `:2398-2411`). The engine resolves each pull spec once
   (`dpp/parse-pull` at `:2402`) and applies it across the deduped result set.
   Pulling in-query therefore beats mapping `seon.db/pull` over query results:
   same attribute fetch, one parsed spec, no per-row call overhead, and it
   composes with the rest of the pipeline.
2. **`:keys` is a post-hoc `zipmap`** (`convert-to-return-maps`,
   `query.cljc:3121-3128`) — literally `(mapv #(zipmap mkeys %) resultset)`.
   It costs a map allocation per row and buys naming. Useful, but it is not an
   index optimization. Ordered queries sort and apply offset/limit to positional
   tuples before naming the retained rows.
3. **`:order-by`/`:limit`/`:offset` are engine-side** (accepted as query-map
   keys at `query.cljc:112`, applied at `:4197-4200`). When they work, they
   truncate before returning.

### Resolved fork defect: `:keys` and `:order-by` now compose

Upstream commit `ebbd623a` (PR #795) placed `convert-to-return-maps` before
`apply-order-by`, so ordering received **maps** and tried to index into them
positionally:

```clojure
(seon.db/q {:query '[:find ?sym ?doc
                     :keys seon.fn/sym seon.fn/doc
                     :where [?f :seon.effect/capability _]
                            [?f :seon.fn/sym ?sym] [?f :seon.fn/doc ?doc]]
            :args [] :order-by '?sym :limit 3})
#:seon.error{:kind :seon.db/invalid-read
             :message "nth not supported on this type: PersistentArrayMap"}
```

The maintained fork repairs the stage order in commit `574c5f0f`: ordering
resolves each find variable to its tuple position, sorts those tuples, applies
offset/limit, and only then applies the declared `:keys` names. The fork's
`datahike.test.query-test` regression also proves that the adjacent non-ordered
offset/limit branch still returns correctly named maps.

Naming the `:keys` alias instead fails earlier, with a genuinely good message:

Using `:order-by 'seon.fn/sym` instead produced the actual string value
`:order-by variable seon.fn/sym not found in :find [?sym ?doc]`.

The same query was re-run through the live `default` cluster's door after
hot-reloading the repaired Var and returned the first three capability rows as
an ordered table of `:seon.fn/sym` and `:seon.fn/doc` maps. The combination is
now safe to teach when engine-side ordering and truncation are the point;
pull-in-`:find` plus ordinary Clojure composition remains the preferred style
for nested naming and shaping.

### Index-direct access

`datoms`, `seek-datoms`, `rseek-datoms`, and `index-range` are first-class
(`reference-code/datahike/src/datahike/api/specification.cljc:712-772`;
`rseek-datoms` is documented at `:739` as "the primitive for windowed
backwards pagination"). `seon.db` exposes **`datoms` only**. Where a question
is "all values of one attribute", `datoms` on `:aevt` beats a query:

```clojure
(->> (seon.db/datoms {:index :aevt :components [:seon.effect/capability]})
     (map :v)
     frequencies)
{seon.edit.jvm/edit 3, seon.fs.jvm/read 1, seon.fs.jvm/write 1,
 seon.fs.jvm/glob 1, seon.fs.jvm/stat 1, seon.shell.jvm/run 1,
 seon.web.jvm/fetch 1, seon.web.jvm/search 1}
```

The measured evaluation took 2 ms.

Fastest probe in this document, and the output is *actionable*: it shows one
owner (`seon.edit.jvm/edit`) serving three agent-facing functions, which is
the door model in one line. `index-range`/`seek-datoms` are **not reachable
from `seon.db`** — recorded as [Gap 5](#gap-5-seondb-exposes-no-ranged-index-access).

### Recipe grades, all re-measured live

| Recipe | Best form | ms | Actionable? |
|---|---|---|---|
| All capabilities | pull-in-`:find` + `sort-by` | **5** | **Yes** — a menu you `(doc …)` into |
| Attribute value census | `datoms` `:aevt` + `frequencies` | **2** | **Yes** — reveals shared owners |
| All `my.*` public functions | pull-in-`:find` + `sort-by` | 7 | **Yes** — the callable surface |
| One function's contract | **nested pull** (see below) | **4** | **Yes** — tells you what to pass |
| Namespace overview | 3 queries, narrow pulls | 5–37 | **Yes** — orientation before editing |
| Docstring search | `q` + `map` first-line + `sort-by` | 9 | **Yes** — finds the function to call |
| Keyword usage search | pull + `group-by` | 18 | **Yes** — what to read before changing X |
| Tests for a function | `:seon.fn/calls` reverse walk | 12 | **Yes** — the spec to satisfy |
| Counting anything | `(count …)` aggregate | 11 | **No** — cut |

`count-distinct`, `count`, `min`, `max`, `sum`, `avg`, `median`, `stddev`,
`sample` are all built in (`query.cljc:632-668`) — worth knowing, but they
produce numbers, and numbers mostly fail the filter.

## 3. The recipes, in the composition style

The house style: **query for rows, then massage with ordinary Clojure**. The
engine finds; `->>`, `sort-by`, `keep`, `group-by`, and `frequencies` shape.

### Capabilities — the flagship

```clojure
(->> (seon.db/q '[:find [(pull ?f [:seon.fn/sym :seon.fn/doc]) ...]
                  :where [?f :seon.effect/capability _]])
     (sort-by :seon.fn/sym))
```

5 ms. The renderer turns a uniform sequence of maps into a clean table:

```
|    :seon.fn/sym |                                                               :seon.fn/doc |
|-----------------+----------------------------------------------------------------------------|
| "my.edit/exact" |   "Replace one exact string occurrence, or all occurrences when explicit." |
|  "my.edit/form" |                       "Edit one unambiguous named top-level Clojure form." |
| "my.edit/lines" |         "Replace an exact, digest-fenced one-based inclusive line window." |
|    "my.fs/glob" |            "Find a bounded set of paths without following symbolic links." |
|    "my.fs/read" |    "Read one bounded byte window and digest through the filesystem owner." |
|    "my.fs/stat" |                       "Read no-follow attributes for one filesystem path." |
|   "my.fs/write" |             "Conditionally replace one file through the filesystem owner." |
|  "my.shell/run" |     "Run one foreground argv vector and return complete process evidence." |
|  "my.web/fetch" |           "Fetch one bounded HTTP(S) URL through the protected web owner." |
| "my.web/search" | "Search the configured provider and return source rows plus raw evidence." |
```

This is the whole effect surface, sorted, in ten rows. Every docstring states
its bound ("one bounded byte window", "without following symbolic links"), so
the agent learns the *shape* of the effect model — bounded, singular, owned —
before any individual call. It passes the filter outright: the next action is
`(doc …)` on the matching row.

### One function's contract — nested pull replaces `or-join`

The obvious query needs `or-join` + `ground` to tag inputs against outputs, and
returns an ungrouped set with the spec repeated on every row. **A nested pull
over the arity's ref attributes does the whole job**, because pull descends
refs natively (`query.cljc:2398-2411` → `dpa/pull-spec`):

```clojure
(->> (seon.db/q '[:find [(pull ?a [{:seon.fn.arity/input-refs  [:seon.schema/key :seon.schema/form]}
                                   {:seon.fn.arity/output-refs [:seon.schema/key :seon.schema/form]}]) ...]
                  :in $ ?sym
                  :where [?f :seon.fn/sym ?sym] [?f :seon.fn/arities ?a]]
                "my.fs/read"))
```

4 ms, no `or-join`, and inputs/outputs arrive already grouped:

```clojure
[#:seon.fn.arity{:input-refs  [#:seon.schema{:key :my.fs/read-request
                                             :form "[:map [:my.fs/path :my.fs/path] …]"}]
                 :output-refs [#:seon.schema{:key :my.fs/read-result  :form "[:map …]"}
                               #:seon.schema{:key :seon.error/value   :form "[:map …]"}]}]
```

This is both the most efficient and the most readable form of the recipe, and
it is maximally actionable — it tells the agent exactly what map to construct
and exactly which two shapes can come back.

### Docstring search — massage the output

```clojure
(->> (seon.db/q '[:find ?sym ?doc
                  :where [?f :seon.fn/doc ?doc]
                         [(clojure.string/includes? ?doc "digest")]
                         [?f :seon.fn/sym ?sym]])
     (map (fn [[sym doc]] {:seon.fn/sym sym
                           :first-line (first (clojure.string/split-lines doc))}))
     (sort-by :seon.fn/sym)
     (take 6))
```

9 ms across all 2238 rows. Taking the first line is what makes it readable —
see [Ugly 2](#ugly-2-one-long-docstring-destroys-a-table):

```
|                 :seon.fn/sym |                                                             :first-line |
|------------------------------+-------------------------------------------------------------------------|
|              "my.edit/lines" |      "Replace an exact, digest-fenced one-based inclusive line window." |
|                 "my.fs/read" | "Read one bounded byte window and digest through the filesystem owner." |
|           "seon.blob/digest" |         "Return the content-addressed SHA-256 digest of UTF-8 content." |
|              "seon.blob/get" |                      "Read and verify UTF-8 content by SHA-256 digest." |
|             "seon.blob/put!" |               "Store UTF-8 content once and return its SHA-256 digest." |
| "seon.bootstrap/plan-digest" |        "The stable digest of a cluster's ordered bootstrap-plan facts." |
```

Note the predicate `[(clojure.string/includes? ?doc "digest")]` sits inside
the `:where` clause, so the engine filters during the scan rather than
materializing all 2238 docstrings into Clojure first.

### Keyword usage — separate the code from its tests

Test rows carry `:seon.fn/keywords` and `:seon.fn/calls` alongside
`:seon.test/sym`, so one query spans implementation and specification. Split
them with `group-by`:

```clojure
(->> (seon.db/q '[:find [(pull ?f [:seon.fn/sym :seon.test/sym]) ...] :in $ ?kw
                  :where [?f :seon.fn/keywords ?kw]] :seon.cluster.run/process)
     (group-by #(if (:seon.test/sym %) :tests :functions))
     (reduce-kv (fn [m k v]
                  (assoc m k (sort (keep (some-fn :seon.fn/sym :seon.test/sym) v))))
                {}))
```

18 ms. Returns `:functions` (`seon.cluster.run/claim-call`, `…/held?`,
`…/retract-custody`, `seon.cluster.loop/call-turn`, …) and `:tests`
(`…/a-non-holder-refuses-every-held-run-transition`,
`…/recovery-preserves-terminal-receipts-exactly`, …).

This is the highest-value *advanced* recipe: it answers "what must I read
before I change attribute X?", and the `:tests` half doubles as the behavioral
spec, because Seon test names are full sentences. `keep` + `some-fn` is
load-bearing — see [Ugly 3](#ugly-3-a-nil-from-a-heterogeneous-pull-npes-with-no-location).

### Namespace overview

Three narrow queries. Pull **only** `:seon.fn/sym` and `:seon.fn/arglists`
into the function table:

```clojure
(->> (seon.db/q '[:find [(pull ?f [:seon.fn/sym :seon.fn/arglists]) ...]
                  :in $ ?n
                  :where [?e :seon.ns/name ?n] [?f :seon.fn/ns ?e]
                         [?f :seon.fn/private? false]] 'my.fs)
     (sort-by :seon.fn/sym))
```

The measured evaluation took 5 ms and produced:

|                :seon.fn/sym | :seon.fn/arglists |
|-----------------------------|--------------------|
|            "my.fs/content?" |        "([value])" |
|                "my.fs/glob" |      "([request])" |
|                "my.fs/read" |      "([request])" |
|                "my.fs/stat" |      "([request])" |
|               "my.fs/write" |      "([request])" |
| "my.fs/write-precondition?" |        "([value])" |

The schema half is where this recipe earns its place — it resolves schemas
**wherever declared**, not just same-namespace ones:

```clojure
(->> (seon.db/q '[:find [(pull ?s [:seon.schema/key :seon.schema/form]) ...]
                  :in $ ?n
                  :where [?e :seon.ns/name ?n] [?f :seon.fn/ns ?e] [?f :seon.fn/arities ?a]
                         (or [?a :seon.fn.arity/input-refs ?s]
                             [?a :seon.fn.arity/output-refs ?s])] 'my.run)
     (sort-by :seon.schema/key))
```

```
|  :seon.schema/key |                                                             :seon.schema/form |
| :my.run/completed |    "[:map [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]" |
|      :my.run/note |                                                            "[:string {:min 1}]" |
|    :my.run/result |                                                            "[:string {:min 1}]" |
|      :my.run/wait | "[:map [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note] …]" |
| :seon.error/value | "[:map [:seon.error/kind …] [:seon.error/message …] [:seon.error/data …]]" |
```

`:seon.error/value` is declared in `error.edn`, not `my.run` — that is the
data-flow reach a namespace-scoped schema query would miss.

## 4. Helper verdicts

The bar: *"anything we find ourselves doing twice is not teaching anything and
is likely common."* I count only compositions I actually reached for twice.

### (a) Namespace overview — **EARNS A HELPER**

Ran it four times (`my.fs`, `my.run`, `seon.db`, `seon.schema`). Three queries
sharing one `:in` binding, always run together, and the schema half reaches
across namespaces. Honest contract:

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

It must **not** include full docstrings in its function table
([Ugly 2](#ugly-2-one-long-docstring-destroys-a-table)).

### (b) Function contract view — **VERDICT REVERSED: NO HELPER**

An earlier pass of this research recommended a helper here because the
`or-join` form was painful. **The nested pull dissolves that problem** — 4 ms,
one expression, naturally grouped, no advanced Datalog. Wrapping it would now
hide a genuinely elegant use of pull's ref-descent, which is exactly the
fishing lesson the curriculum wants to teach. The structure removed the need
for the helper; that is the better outcome.

### (c) Everything else — **NO HELPER**

Each remaining recipe is one query plus a short `->>` tail, each under 18 ms,
each output already readable. **One helper total.**

## 5. The curriculum

Priority: **W** = worked example (form followed by its actual value),
**H** = one-form hint, **D** = defer, **CUT** = fails the actionable filter.

Stability is for prefix-cache ordering (measured 67 % hit rate): stable
content precedes volatile.

| # | Lesson | What the agent DOES differently | Form | Stability | Pri |
|---|---|---|---|---|---|
| 1 | Session contract: forms, input-side comments, batching | Batches forms; may write comments that survive in its source | the session's own shape + `(in-ns '…)` | stable | **W** |
| 2 | Orientation / self | Knows which namespace it owns | `(in-ns '{{seon.ns/name}})` | stable | **W** |
| 3 | Finding capabilities | Picks the right door and `(doc …)`s it | capability recipe (§3) | stable | **W** |
| 4 | Finding the callable surface | Calls `my.*` functions instead of inventing them | `my.*` recipe | stable | **W** |
| 5 | Reading a contract before calling | Constructs the right request map first try | nested-pull contract recipe | stable | **W** |
| 6 | `doc` from graph facts | Drills into one row after a query narrows | `(doc my.run/complete)` | stable | **W** |
| 7 | Writing a durable defn with an open contract | Writes contracts that accrete instead of refusing | open-map `defn` + call with an extra key | stable | **W** |
| 8 | Contract violation is a value | Experiments freely; repairs instead of fearing | `(largest)` | stable | **W** |
| 9 | `my.message` / `my.run` lifecycle | Ends its run correctly; writes a usable note | `(doc my.run/wait)` | stable | **W** |
| 10 | Keyword usage search | Reads the right code before changing an attribute | keyword recipe (§3) | volatile | **H** |
| 11 | Tests as specification | Satisfies existing tests; writes test-first | tests-for-a-function | volatile | **H** |
| 12 | Using a capability once | Knows the request/result shape concretely | one small `my.fs/read` | volatile | **H** |
| 13 | Errors-as-values are pervasive | Stops writing try/catch | the 56-count form | volatile | **CUT→H** |
| 14 | Registry-first schema declaration | Reuses an existing key instead of duplicating | prose fact in db | stable | **H** |
| 15 | `seon.db` durable facts | Remembers across runs | prose fact in db | stable | **H** |
| 16 | `my.background` await/wake | Does long work without blocking | — | stable | **D** |
| 17 | Self-improvement: editing its own forms | — | — | stable | **D** |
| — | ~~Census~~ | ~~nothing~~ | ~~counts~~ | — | **CUT** |

Re-grades against the filter, stated honestly:

- **Census: CUT.** Counts change no action.
- **Lesson 13 demoted W→H.** "56 functions return `:seon.error/value`" is a
  striking fact, but it is still a number; lesson 8's *lived* error value
  teaches the same thing actionably. Keep it as an uncommented hint.
- **Lessons 14 and 15 demoted to prose facts.** A `register!` or `transact!`
  demo would mutate the shared cluster ctx or write junk facts every turn.
  Undemonstrable as a live form; they belong in the database as instruction
  facts.
- **Lesson 5 promoted to W.** Reading a contract before calling is the single
  highest-leverage habit: it converts a failed call into a correct first call.

### Cache ordering

Lessons 1–9 are stable (outputs do not move as the graph grows); 10–13 are
volatile. That boundary belongs in the bootstrap plan's data and ordering, not
as a displayed comment marker.

### Top three lessons, restyled for decision 11

Each code block below is only the submitted form; this design document does
not fabricate or annotate its future result. In the running bootstrap the form
is followed by its actual computed value. The teaching text moves into the
db-resident `(help)` prose. Recommended `(help)` additions:

- Every function is callable; capabilities are the functions that reach out of
  the process, and querying them gives the agent a menu to inspect with `doc`.
- Read a function's input and output schemas before calling it; nested pull
  follows the arity's schema refs directly.
- A `defn` with a complete Malli schema becomes durable. Required keys and
  declared values validate rigorously, while extra open-map data is ignored.
  Contract failures are ordinary values to inspect and repair.

**Lesson 3 — finding what you can do:**

```clojure
(->> (seon.db/q '[:find [(pull ?f [:seon.fn/sym :seon.fn/doc]) ...]
                  :where [?f :seon.effect/capability _]])
     (sort-by :seon.fn/sym))
```

**Lesson 5 — reading a contract before calling:**

```clojure
(->> (seon.db/q '[:find [(pull ?a [{:seon.fn.arity/input-refs  [:seon.schema/key :seon.schema/form]}
                                   {:seon.fn.arity/output-refs [:seon.schema/key :seon.schema/form]}]) ...]
                  :in $ ?sym
                  :where [?f :seon.fn/sym ?sym] [?f :seon.fn/arities ?a]]
                "my.fs/read"))
```

**Lesson 7 — writing something that lasts:**

```clojure
(defn largest
  "The row with the largest :amount."
  {:malli/schema [:=> [:cat [:sequential [:map [:label :string] [:amount :int]]]]
                  [:map [:label :string] [:amount :int]]]}
  [rows]
  (last (sort-by :amount rows)))
```

```clojure
(largest [{:label "a" :amount 3}
          {:label "b" :amount 9 :note "extra data is fine"}])
```

```clojure
(largest)
```

## 6. Verified claims

- **The reply parser preserves leading comments verbatim.**
  `src/seon/cluster/reply.clj:77-84`: `prose-line` returns a line unchanged
  when it already starts with `;`, prefixing `; ` otherwise. `plan-sources`
  (`:217-244`) prepends the coalesced prose to the following form's source. So
  `;;` comments survive exactly.

- **The current bootstrap's `{:closed true}` instruction is a defect against
  ruling #48.** Open maps define successfully. The prose at
  `resources/seon/bootstrap.edn:21-22` and both `largest` worked examples must
  change. The draft diff's prose replacement is correct; its
  `(dir my.fs)`/`(dir my.edit)`/`(dir my.web)` enumeration is not — §3's
  capability recipe replaces it and teaches more.

- **`:seon.fn/ns` is a ref; `:seon.ns/name` is a symbol; `:seon.fn/sym` is a
  string.** Walking `[?f :seon.fn/ns ?e] [?e :seon.ns/name ?n]` is required,
  and passing `"my.fs"` where a symbol belongs returns a cast error.

- **Inside an already-quoted query, `'my.run` reads as `(quote my.run)`** and
  fails the same cast; the bare symbol `my.run` works, as does passing it
  through `:in`. Teach `:in`.

## 7. Gaps — missing facts and behaviors

### Gap 1 — `jvm` mode has no ambient cluster, and `q` swallows the error

```clojure
(seon.db/db)
{:seon.error/kind :seon.db/missing-connection-binding
 :seon.error/message "No current cluster connection is bound to seon.db/*conn*."}
```

That error value is then **accepted by `seon.db/q` as its `db` argument**, and
`q` returns `nil` rather than propagating it. An error value passed where a
database value is required must return an error value, never `nil`. Silent,
and therefore the worst failure mode available.

### Gap 2 — resolved: `:keys` + `:order-by`

Root-caused above to pipeline order introduced by upstream `ebbd623a` and
repaired in the maintained fork at `574c5f0f`. The repair and its fork-native
regressions should be offered upstream.

### Gap 3 — no test-for-capability coverage is visible as a fact

`my.fs/read` has **0** tests reaching it via `:seon.fn/calls`; `seon.db/q` has
30+. Whether that means "untested" or "tested through a layer the call edges
do not cross" is **not answerable by query**. The missing fact is an edge from
a test to the capability it exercises through the door.

### Gap 4 — `System/nanoTime` is unavailable, and nothing points at the alternative

```clojure
(System/nanoTime)
{:seon.error/kind :seon.sci.eval/evaluation-failed
 :seon.error/message "Unable to resolve symbol: System/nanoTime"}
```

The `:seon.sci.admit/record` returned with every evaluation already carries
`:seon.eval/duration-ms` and `:seon.eval/allocated-bytes` — the better answer,
which nothing surfaces and the refusal does not mention.

### Gap 5 — `seon.db` exposes no ranged index access

Datahike ships `seek-datoms`, `rseek-datoms`, and `index-range`
(`api/specification.cljc:712-772`), with `rseek-datoms` documented as the
primitive for windowed backwards pagination. `seon.db` exposes `datoms` only,
so "the latest N messages" has no efficient form for an agent. Missing
surface, not a missing fact.

## 8. Ugly output — tool and render feedback

### Ugly 1 — nested pull results splice a markdown table inside a map

The renderer turns any uniform sequence of maps into a markdown table,
**including nested ones**, producing output that is neither valid EDN nor
readable prose:

```
[#:seon.fn.arity{:input-refs [#:seon.schema{:key :my.fs/read-request, :form "…"}],
    :output-refs
|   :seon.schema/key |   :seon.schema/form |
|--------------------+---------------------|
|  :seon.error/value |                 "…" |
}]
```

At top level the same mechanism is excellent. The fix is to fire only at top
level, or to render nested uniform sequences inline. Workaround an agent can
use today: flatten first, e.g. `(->> … first :seon.fn.arity/output-refs
(sort-by :seon.schema/key))`.

### Ugly 2 — one long docstring destroys a table

Pulling `:seon.fn/doc` for `my.run` produced rows padded to ~900 characters,
because the renderer pads every column to its widest cell and
`my.run/wait`'s docstring is a 20-line essay. One long value makes the entire
table unreadable. Either cap column width with an ellipsis or fall back to
non-table rendering past a threshold. This is why the namespace-overview
helper must pull `:seon.fn/arglists`, not `:seon.fn/doc`.

### Ugly 3 — a nil from a heterogeneous pull NPEs with no location

```clojure
(->> (seon.db/q '[:find [(pull ?f [:seon.fn/sym]) ...] :in $ ?kw
                  :where [?f :seon.fn/keywords ?kw]] :seon.cluster.run/process)
     (map :seon.fn/sym)
     (remove #(clojure.string/includes? % "-test/")))
"Cannot invoke \"Object.toString()\" because \"s\" is null"
```

Test rows match the keyword but have no `:seon.fn/sym`, so `map` yields nils.
The message names neither the value, the attribute, nor the form position.
`keep` + `some-fn` is the fix, but the error should have said so.

### Ugly 4 — `ClassCastException` messages are JVM noise

"class java.lang.String cannot be cast to class clojure.lang.Symbol
(java.lang.String is in module java.base of loader 'bootstrap'; …)" — the
module/loader clause is most of the message and helps nobody, while the useful
information (which clause, which attribute, what was expected) is absent.

### Positive calibration

- **`:order-by` refusals are excellent**: ":order-by variable seon.fn/sym not
  found in :find [?sym ?doc]" names the value, the expectation, and the
  context. This is the standard the others should meet. The former valid-query
  failure, "nth not supported on this type: PersistentArrayMap", did not meet
  that standard: it exposed an implementation type and named neither the
  option nor the incompatible stage. The fork repair eliminates that internal
  mismatch rather than adding a refusal for a valid query.
- **Target `doc` and `dir` results use strict REPL display.** The form is
  followed by an ordinary computed value, never docstrings prefixed as source
  comments. For example, the function facts may be returned as data:

  ```clojure
  (doc my.run/complete)
  {:seon.fn/sym "my.run/complete"
   :seon.fn/arglists "([result])"
   :seon.fn/doc "Finish this run with the reply the agent wants delivered.\n\nReturns a completion value the run loop records with the final receipt. Blank text returns a flat error value the agent can inspect and repair."}
  ```

  Current `program-doc-var` still prefixes docstring lines with `;`
  (`src/seon/sci/eval.clj:838-855`); that is a decision-11 implementation
  defect, not a display convention to teach.

- **Top-level map-sequence tables are genuinely good** and are the reason
  pull-in-`:find` is recommended throughout.
