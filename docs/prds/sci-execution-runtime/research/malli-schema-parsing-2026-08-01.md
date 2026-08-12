---
type: research
status: complete
tags: [sci, malli, schema, program-graph, database]
---

# Malli schema parsing and queryable function contracts — 2026-08-01

## Verdict

Use Malli's compiled function schema as the only parser and derive two
complementary projections from that one object:

1. `m/-function-schema-arities` followed by `m/-function-info` is the
   authoritative function decomposition. Persist every Malli leaf it returns:
   `:input`, `:output`, `:arity`, `:min`, `:max`, and optional `:guard`, once per
   ordered arity. A top-level `:function` deliberately has no
   `-function-info`; its children are the arities
   (`reference-code/malli/src/malli/core.cljc:2233-2283`).
2. `m/ast` is the complete schema-as-data traversal vocabulary. Relationalize
   every AST key Malli emits—`:type`, `:properties`, `:children`, `:child`,
   `:input`, `:output`, `:guard`, `:keys`, `:registry`, `:order`, and `:value`—
   and add direct refs from encountered Malli `RefSchema`s to the existing
   `:seon.schema/key` entities. Preserve child order on component entry facts;
   never put ordered inputs in cardinality-many values.

The opaque `:seon.fn/spec` string remains the canonical contract form. The
relational facts are a same-transaction query index derived from it, not a new
authoring or reconstruction authority. This qualification matters because
Malli calls AST round-tripping lossless but also explicitly labels the AST
syntax internal and says not to use it as a database persistence model
(`reference-code/malli/README.md:2763-2806`). Keeping the stable form string as
authority lets an upgrade drop and regenerate the parsed index with the newly
pinned Malli rather than treating an old internal AST encoding as source.

The owner correction is incorporated: illustrative names such as
`:seon.fn/input-schema` and `:seon.fn/output-schema` do not dictate the model.
The splits and leaf names come from Malli. Registered-schema refs remain an
explicit query edge.

## Dependency ledger

| Dependency or owner | Revision / location | Contract established here |
|---|---|---|
| Malli | `80138076960e` (`0.20.1~10` checkout; application coordinate remains 0.20.0) | Schema construction, AST, function decomposition, refs, walk, registry |
| Malli core | `reference-code/malli/src/malli/core.cljc` | `Schema`, `AST`, `FunctionSchema`, `m/schema`, `m/ast`, `m/from-ast`, `m/form`, `m/children`, `m/walk`, `m/parse` |
| Malli util | `reference-code/malli/src/malli/util.cljc` | Traversal/transformation helpers only; no schema parser |
| Malli registry | `reference-code/malli/src/malli/registry.cljc` | Registry lookup and composition used while compiling refs |
| Program row owner | `src/seon/program.cljc:4-40,75-155` | One identity family, exact owned attrs, exact change detection |
| Static producer | `src/seon/fn.clj:229-263,637-683` | Analyzer metadata becomes canonical function rows and init transactions |
| Runtime producer | `src/seon/sci/reader.cljc:270-285`; `src/seon/sci/eval.clj:404-427`; `src/seon/cluster/run.cljc:680-748` | Agent contract extraction, admission, terminal same-transaction replacement |
| Schema projection | `src/seon/schema.cljc:1145-1310,1679-1895` | Complete active registry, compiled contracts, current string-to-projection rebuild |
| Schema EDN bridge | `resources/seon/schema/program.edn:1-25`; `src/seon/schema/datahike.cljc` | First-party declarations derive all Datahike facets |

The shortest falsifier was one active-registry raw JVM parse of three exact
database contract strings, including a direct registered output, a multi-arity
inline compound, and a function-level property registry. The reproducible
script is `tmp/malli-parse/probe.clj`.

## Candidate machinery, source, and verdict

### `m/schema` / `m/function-schema`

This is the actual schema-form parser. `m/schema` accepts schema instances,
constructors, vector syntax, or registry references and produces a `Schema`
object (`reference-code/malli/src/malli/core.cljc:2547-2573`).
`m/function-schema` then refuses anything that does not implement Malli's
function-schema protocol (`reference-code/malli/src/malli/core.cljc:3074-3078`).

Verdict: compile the canonical EDN form once with `m/function-schema` against
the exact active Seon projection registry. Every other extraction below reads
that compiled object. Do not parse keywords/vectors by hand.

### `m/-function-schema-arities` and `m/-function-info`

`FunctionSchema` defines the two decomposition operations at
`reference-code/malli/src/malli/core.cljc:89-93`. A single `:=>` returns itself
as its only arity and returns:

```clojure
{:min min
 :arity (if (= min max) min :varargs)
 :input input-schema-object
 :output output-schema-object
 :max max                         ; present when bounded
 :guard guard-schema-object}      ; present when supplied
```

The implementation is
`reference-code/malli/src/malli/core.cljc:2140-2202`. The input must be
`:cat` or `:catn`; Malli calculates `:min`/`:max` through its regex-schema
machinery rather than asking Seon to infer arity. A top-level `:function`
returns its ordered child schemas from `-function-schema-arities` and nil from
`-function-info` (`reference-code/malli/src/malli/core.cljc:2233-2279`).

Verdict: this is the function-specific decomposition. It owns the fact split
and its names. Never infer arity from source arglists, count `:cat` children,
or special-case `:*`/`:?`/`:repeat` in Seon.

### `m/ast` / `m/from-ast`

The protocol is defined at `reference-code/malli/src/malli/core.cljc:45-47`.
Malli emits ordinary map data and reconstructs schemas through the registry at
`reference-code/malli/src/malli/core.cljc:2849-2876`. Specialized helpers use
the exact vocabulary proposed for facts:

- ordinary nodes: `:type`, optional `:properties`, `:children`
  (`reference-code/malli/src/malli/core.cljc:681-711`);
- map and named-regex entries: `:keys`, then per entry `:order`, `:value`, and
  optional `:properties` (`reference-code/malli/src/malli/core.cljc:667-693`);
- a `:=>`: `:input`, `:output`, optional `:guard`, optional `:properties`
  (`reference-code/malli/src/malli/core.cljc:2140-2161`);
- one-child shapes: `:child`; scalar shapes: `:value`; local property
  registries: `:registry` (`reference-code/malli/src/malli/core.cljc:681-711`).

Round-trip verdict: all three Seon contracts below satisfy exactly:

```clojure
(= (m/form compiled)
   (m/form (m/from-ast (m/ast compiled options) options)))
true
```

Malli's own test suite covers recursive `:ref`/`:schema` registries and exact
form reconstruction at `reference-code/malli/test/malli/core_test.cljc:2949-3009`.
The historical symbols/unqualified-keywords `from-ast` defect was fixed and
has a current regression at
`reference-code/malli/test/malli/core_test.cljc:3020-3043`.

Caveat: the AST is lossless for the pinned library and the tested contracts,
but it is explicitly internal. Therefore Seon may persist it only as a
regenerable query index paired with the canonical `:seon.fn/spec`; it must not
make `m/from-ast` the database recovery path.

### Registered-schema references

Malli does not inline an ordinary registered schema reference in the AST. A
compiled keyword reference is a pointer implementing `RefSchema`; its form
remains the keyword and `m/-ref` returns that keyword
(`reference-code/malli/src/malli/core.cljc:2551-2573`). Its AST is:

```clojure
{:type :malli.core/schema, :value :seon.render/unit}
```

The pointer AST behavior is implemented by the internal schema wrapper at
`reference-code/malli/src/malli/core.cljc:2050-2104`. Explicit `[:ref k]`
similarly retains `k` as `:value` and implements `RefSchema` without expanding
unless walk options request it
(`reference-code/malli/src/malli/core.cljc:1954-2031`).

Verdict: while relationalizing a node for which `m/-ref-schema?` is true,
persist both its AST `:value` and a direct Datahike ref to
`[:seon.schema/key (m/-ref node)]` when that key belongs to the canonical Seon
population. This is Malli's own reference determination, not a keyword scan.

### `m/form`, `m/children`, and `m/walk`

- `m/form` returns the original canonical form
  (`reference-code/malli/src/malli/core.cljc:2575-2580`). Keep its `pr-str` as
  `:seon.fn/spec`.
- `m/children` returns resolved child schema objects; map-entry schemas use
  `[key properties child]` triples
  (`reference-code/malli/src/malli/core.cljc:2596-2603`). It is useful for
  local inspection but is not enough because it does not name function roles
  and its output shape varies by schema family.
- `m/walk` is Malli's postwalk over schema objects
  (`reference-code/malli/src/malli/core.cljc:2612-2625`). It is the correct
  way to observe `RefSchema`s. Default walking preserves canonical refs rather
  than expanding them; Seon's existing `direct-references*` uses exactly that
  behavior (`src/seon/schema.cljc:32-50`).

Verdict: AST supplies the complete data shape; walk supplies the direct
registered-ref edge without reimplementing Malli reference recognition.
`children` and `form` are checks and consumer conveniences, not alternate
parsers.

### `m/parse` is not a schema parser

`m/parser` creates a function `value -> parsed-value | ::m/invalid`, and
`m/parse` invokes it on a value
(`reference-code/malli/src/malli/core.cljc:2668-2682`). It does not turn a
schema form into schema data. The live probe deliberately demonstrates the
distinction. A Clojure vector happens to satisfy a shallow function schema
because vectors are IFn, so parsing the schema-form vector as a value returns
that vector unchanged; parsing a string returns `:malli.core/invalid`. Neither
operation parses schema syntax.

Verdict: never call `m/parse` in the program-fact producer. The correct entry
is `m/function-schema`/`m/schema`.

### `malli.util` and `malli.registry`

`malli.util/find-first` and `malli.util/subschemas` are traversal helpers built
on Malli's walker (`reference-code/malli/src/malli/util.cljc:37-51,168-181`).
The latter follows schema refs by default, which is the opposite of the desired
direct-ref boundary. The remaining util helpers transform schemas; none parses
schema syntax into a more authoritative form.

`malli.registry` defines lookup, simple/fast registries, and composition
(`reference-code/malli/src/malli/registry.cljc:11-34,54-59`). It is required to
compile the contract against Seon's population but contributes no alternate
parser or decomposition.

## Live database and raw JVM probe

### Conditions

- Date: 2026-08-01.
- JVM: OpenJDK 26.0.1 on the repository `:dev` classpath.
- Malli revision: `80138076960e7820523b4cb932c5b5d1936d4e7f`.
- Database source: read-only query against live cluster `default`.
- Observed counts: `default` had 434 function rows carrying
  `:seon.fn/spec`; `current-src` had 437 contracted rows and 1,586 total
  function rows. The owner's approximately 950-row sizing target is therefore
  retained as the benchmark extrapolation, never encoded as a migration count.
- Registry: `schema.edn/packaged-forms` activated in the raw JVM; extraction
  used `:seon.schema.projection/compile-options` from that active projection.
- Timing: 1,000 warm-up calls, then 10,000 measured calls with
  `System/nanoTime`. These are local development measurements, not service
  guarantees.

The recorded structures and timing tables come from:

```bash
clojure -M:dev tmp/malli-parse/probe.clj
clojure -M:dev tmp/malli-parse/probe.clj benchmark
```

The exact database selection was:

```clojure
[:find ?sym ?spec
 :in $ [?sym ...]
 :where
 [?f :seon.fn/sym ?sym]
 [?f :seon.fn/spec ?spec]]
```

### Contract 1 — registered inputs and output

Database row:

```clojure
["seon.render.value/result-window-edn"
 "[:=> [:cat :seon.render/unit :seon.cluster.eval/result-edn] :seon.cluster.eval/result-edn]"]
```

Exact candidate outputs:

```clojure
{:form
 [:=>
  [:cat :seon.render/unit :seon.cluster.eval/result-edn]
  :seon.cluster.eval/result-edn]

 :children
 [[:cat :seon.render/unit :seon.cluster.eval/result-edn]
  :seon.cluster.eval/result-edn]

 :function-info
 {:min 2
  :arity 2
  :input [:cat :seon.render/unit :seon.cluster.eval/result-edn]
  :output :seon.cluster.eval/result-edn
  :max 2}

 :ast
 {:type :=>
  :input
  {:type :cat
   :children
   [{:type :malli.core/schema, :value :seon.render/unit}
    {:type :malli.core/schema,
     :value :seon.cluster.eval/result-edn}]}
  :output
  {:type :malli.core/schema,
   :value :seon.cluster.eval/result-edn}}

 :walk-refs
 [{:path [0 0], :ref :seon.render/unit}
  {:path [0 1], :ref :seon.cluster.eval/result-edn}
  {:path [1], :ref :seon.cluster.eval/result-edn}]

 :from-ast-form
 [:=>
  [:cat :seon.render/unit :seon.cluster.eval/result-edn]
  :seon.cluster.eval/result-edn]
 :round-trip? true

 :m-parse-form-as-value
 [:=>
  [:cat :seon.render/unit :seon.cluster.eval/result-edn]
  :seon.cluster.eval/result-edn]
 :m-parse-string-as-value :malli.core/invalid}
```

Measured candidate costs:

| Candidate | ns / contract | projected ms / 950 |
|---|---:|---:|
| `m/form` | 81.39 | 0.077 |
| `m/children` | 75.66 | 0.072 |
| `m/ast` | 441.38 | 0.419 |
| arities + `m/-function-info` | 591.07 | 0.562 |
| `m/walk` materialized observations | 979.47 | 0.930 |
| `m/from-ast` | 1,624.45 | 1.543 |
| full probe decomposition, including compile and round-trip checks | 6,106.87 | 5.802 |

### Contract 2 — multi-arity plus an inline compound

Database row:

```clojure
["seon.render.walk/prose"
 "[:function [:=> [:cat :seon.db/database-value :seon.render.walk/node] [:maybe :string]] [:=> [:cat :seon.db/database-value :seon.render.walk/node [:map {:closed true} [:seon.render.walk/branch {:optional true} [:vector [:or :keyword :int]]]]] [:maybe :string]]]"]
```

Exact function decomposition:

```clojure
{:function-info nil
 :arities
 [{:form
   [:=>
    [:cat :seon.db/database-value :seon.render.walk/node]
    [:maybe :string]]
   :function-info
   {:min 2
    :arity 2
    :input [:cat :seon.db/database-value :seon.render.walk/node]
    :output [:maybe :string]
    :max 2}}
  {:form
   [:=>
    [:cat
     :seon.db/database-value
     :seon.render.walk/node
     [:map
      {:closed true}
      [:seon.render.walk/branch
       {:optional true}
       [:vector [:or :keyword :int]]]]]
    [:maybe :string]]
   :function-info
   {:min 3
    :arity 3
    :input
    [:cat
     :seon.db/database-value
     :seon.render.walk/node
     [:map
      {:closed true}
      [:seon.render.walk/branch
       {:optional true}
       [:vector [:or :keyword :int]]]]]
    :output [:maybe :string]
    :max 3}}]}
```

Exact AST for the inline compound (the complete AST has these two ordered
children under `{:type :function, :children [...]}`):

```clojure
{:type :=>
 :input
 {:type :cat
  :children
  [{:type :malli.core/schema, :value :seon.db/database-value}
   {:type :malli.core/schema, :value :seon.render.walk/node}
   {:type :map
    :keys
    #:seon.render.walk
    {:branch
     {:order 0
      :value
      {:type :vector
       :child
       {:type :or
        :children [{:type :keyword} {:type :int}]}}
      :properties {:optional true}}}
    :properties {:closed true}}]}
 :output {:type :maybe, :child {:type :string}}}
```

The `:keys` map contains `:order`; no Datahike cardinality-many projection may
replace that order. `m/from-ast` returned the byte-equivalent printed form and
`:round-trip? true`. Registered refs stayed pointer nodes, while the inline map,
its property, vector, and `:or` remained separate AST data.

Measured candidate costs:

| Candidate | ns / contract | projected ms / 950 |
|---|---:|---:|
| `m/form` | 49.95 | 0.047 |
| `m/children` | 103.97 | 0.099 |
| arities + `m/-function-info` | 410.60 | 0.390 |
| `m/ast` | 1,182.98 | 1.124 |
| `m/walk` materialized observations | 2,142.87 | 2.036 |
| `m/from-ast` | 10,867.97 | 10.325 |
| full probe decomposition, including compile and round-trip checks | 38,014.33 | 36.114 |

### Contract 3 — function-level properties and local registry

Database row:

```clojure
["seon.schema.internal/assert-compilable-schema!"
 "[:function {:registry #:seon.schema.internal{:bound-definition [:or :nil :boolean [:fn clojure.core/number?] [:fn clojure.core/char?] :string :keyword :symbol :uuid [:fn clojure.core/inst?] [:fn clojure.core/map?] [:fn clojure.core/vector?] [:fn clojure.core/set?] [:fn clojure.core/sequential?]]}} [:=> [:cat :map :keyword :seon.schema.internal/bound-definition] :nil] [:=> [:cat :map :keyword :seon.schema.internal/bound-definition :map] :nil]]"]
```

Exact arity split:

```clojure
[{:min 3
  :arity 3
  :input [:cat :map :keyword :seon.schema.internal/bound-definition]
  :output :nil
  :max 3}
 {:min 4
  :arity 4
  :input
  [:cat :map :keyword :seon.schema.internal/bound-definition :map]
  :output :nil
  :max 4}]
```

Exact top-level AST shape:

```clojure
{:type :function
 :children
 [{:type :=>
   :input
   {:type :cat
    :children
    [{:type :map, :keys {}}
     {:type :keyword}
     {:type :malli.core/schema,
      :value :seon.schema.internal/bound-definition}]}
   :output {:type :nil}}
  {:type :=>
   :input
   {:type :cat
    :children
    [{:type :map, :keys {}}
     {:type :keyword}
     {:type :malli.core/schema,
      :value :seon.schema.internal/bound-definition}
     {:type :map, :keys {}}]}
   :output {:type :nil}}]
 :registry
 #:seon.schema.internal
 {:bound-definition
  {:type :or
   :children
   [{:type :nil}
    {:type :boolean}
    {:type :fn, :value clojure.core/number?}
    {:type :fn, :value clojure.core/char?}
    {:type :string}
    {:type :keyword}
    {:type :symbol}
    {:type :uuid}
    {:type :fn, :value clojure.core/inst?}
    {:type :fn, :value clojure.core/map?}
    {:type :fn, :value clojure.core/vector?}
    {:type :fn, :value clojure.core/set?}
    {:type :fn, :value clojure.core/sequential?}]}}}
```

This proves that `m/ast` does not leave the local `:registry` as an opaque
property. Malli moves it to the top-level `:registry` AST key and recursively
AST-parses its schema values (`reference-code/malli/src/malli/core.cljc:681-685`).
`m/from-ast` reconstructed the exact registry-bearing form and
`:round-trip? true`.

Measured candidate costs:

| Candidate | ns / contract | projected ms / 950 |
|---|---:|---:|
| `m/children` | 7.91 | 0.008 |
| `m/form` | 7.96 | 0.008 |
| arities + `m/-function-info` | 586.15 | 0.557 |
| `m/ast` | 1,701.55 | 1.616 |
| `m/walk` materialized observations | 1,379.72 | 1.311 |
| `m/from-ast` | 276,455.46 | 262.633 |
| full probe decomposition, including compile and round-trip checks | 743,176.90 | 706.018 |

The local-registry row is a deliberately adversarial upper bound. Production
does not need `m/from-ast` or the full diagnostic walk in its write path. The
write path needs one already-required compile, `-function-schema-arities`,
`-function-info`, one `m/ast`, and a ref-observing walk. Even the local-registry
AST and function-info operations themselves extrapolate to less than 4 ms for
950 identical contracts on this machine; reconstruction is the expensive and
unnecessary candidate.

## Proposed fact model

### Design rules

1. `:seon.fn/spec` remains required canonical EDN for a durable function.
2. One function owns a collection of Malli arity facts and a component tree
   representing the complete Malli AST. The component attributes are
   cardinality-many sets only for ownership; semantic order is always the
   child's explicit Malli `:order` fact.
3. Each arity mirrors `-function-info` exactly: `input`, `output`, `guard`,
   `arity`, `min`, `max`. `max` and `guard` are absent when Malli omits them.
4. Each AST node mirrors Malli's keys. The AST `:type`, scalar `:value`, entry
   key, and arbitrary property value use canonical EDN strings because one
   Datahike attribute cannot honestly hold Malli's heterogeneous scalar union.
   This is a storage projection, not a parser.
5. Each AST `RefSchema` node also carries `:seon.fn.ast/ref` directly to the
   existing `:seon.schema` entity. Each arity carries unordered membership refs
   partitioned by Malli's `input`/`output`/`guard` roles. Membership is a set,
   so cardinality-many is honest there.
6. Inline compounds point at their AST root component. A registered schema
   points through its AST pointer node to the global `:seon.schema` entity;
   role membership refs make the common reverse lookup direct.
7. These facts are derived only by `seon.program` from the compiled canonical
   contract. Static indexing and runtime publication call the same pure row
   expansion before their existing transaction. No listener, backfill daemon,
   render-time parser, or second writer exists.

Malli's `:type` here is dependency data on an AST node, not a Seon entity-kind
stamp. An AST node is still found through its component connection from a
function and arity.

### Schema EDN block

The following block is ready for an implementation lane to place in
`resources/seon/schema/program.edn`, subject only to the owner's final naming
preference. Leaf names and splits mirror Malli; namespace qualification follows
Seon conventions.

```clojure
{:seon.fn/arities
 [:vector {:seon.db/component true} :seon.db/ref]
 :seon.fn/ast
 [:and {:seon.db/component true} :seon.db/ref]

 :seon.fn.arity/order :int
 ; Malli returns an integer or :varargs; canonical EDN keeps one Datahike type.
 :seon.fn.arity/arity :string
 :seon.fn.arity/min :int
 :seon.fn.arity/max :int
 :seon.fn.arity/input :seon.db/ref
 :seon.fn.arity/output :seon.db/ref
 :seon.fn.arity/guard :seon.db/ref
 ; Membership only; ordering is irrelevant and cardinality-many is honest.
 :seon.fn.arity/input-refs [:set :seon.db/ref]
 :seon.fn.arity/output-refs [:set :seon.db/ref]
 :seon.fn.arity/guard-refs [:set :seon.db/ref]
 :seon.fn.arity/row
 [:map
  [:seon.fn.arity/order :seon.fn.arity/order]
  [:seon.fn.arity/arity :seon.fn.arity/arity]
  [:seon.fn.arity/min :seon.fn.arity/min]
  [:seon.fn.arity/max {:optional true} :seon.fn.arity/max]
  [:seon.fn.arity/input :seon.fn.arity/input]
  [:seon.fn.arity/output :seon.fn.arity/output]
  [:seon.fn.arity/guard {:optional true} :seon.fn.arity/guard]
  [:seon.fn.arity/input-refs {:optional true} :seon.fn.arity/input-refs]
  [:seon.fn.arity/output-refs {:optional true} :seon.fn.arity/output-refs]
  [:seon.fn.arity/guard-refs {:optional true} :seon.fn.arity/guard-refs]]

 ; Malli AST scalar leaves are heterogeneous, so persist their canonical EDN.
 :seon.fn.ast/type :string
 :seon.fn.ast/value :string
 :seon.fn.ast/input [:and {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast/output [:and {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast/guard [:and {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast/child [:and {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast/ref :seon.db/ref
 :seon.fn.ast/properties
 [:vector {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast/children
 [:vector {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast/keys
 [:vector {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast/registry
 [:vector {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast/node
 [:map
  [:seon.fn.ast/type :seon.fn.ast/type]
  [:seon.fn.ast/value {:optional true} :seon.fn.ast/value]
  [:seon.fn.ast/input {:optional true} :seon.fn.ast/input]
  [:seon.fn.ast/output {:optional true} :seon.fn.ast/output]
  [:seon.fn.ast/guard {:optional true} :seon.fn.ast/guard]
  [:seon.fn.ast/child {:optional true} :seon.fn.ast/child]
  [:seon.fn.ast/ref {:optional true} :seon.fn.ast/ref]
  [:seon.fn.ast/properties {:optional true} :seon.fn.ast/properties]
  [:seon.fn.ast/children {:optional true} :seon.fn.ast/children]
  [:seon.fn.ast/keys {:optional true} :seon.fn.ast/keys]
  [:seon.fn.ast/registry {:optional true} :seon.fn.ast/registry]]

 ; One ordered/vector child or keyed map/registry entry. `value` points to an
 ; AST node; scalar map/property data uses canonical `key`/`value-edn` strings.
 :seon.fn.ast.entry/order :int
 :seon.fn.ast.entry/key :string
 :seon.fn.ast.entry/value [:and {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast.entry/value-edn :string
 :seon.fn.ast.entry/properties
 [:vector {:seon.db/component true} :seon.db/ref]
 :seon.fn.ast.entry/row
 [:map
  [:seon.fn.ast.entry/order {:optional true} :seon.fn.ast.entry/order]
  [:seon.fn.ast.entry/key {:optional true} :seon.fn.ast.entry/key]
  [:seon.fn.ast.entry/value {:optional true} :seon.fn.ast.entry/value]
  [:seon.fn.ast.entry/value-edn {:optional true}
   :seon.fn.ast.entry/value-edn]
  [:seon.fn.ast.entry/properties {:optional true}
   :seon.fn.ast.entry/properties]]}
```

Implementation must also extend `:seon.fn/fn` with optional
`:seon.fn/arities` and `:seon.fn/ast`, and extend `seon.program`'s function
owned-attributes with those two attrs. `:seon.fn/ast` is the one component root;
its role/child/entry component edges own the rest of the AST tree. The
`input-refs`/`output-refs`/`guard-refs` names are not an invented schema parser:
they are role-partitioned materializations of Malli `RefSchema` observations.
If the owner prefers the full word `references`, change only that leaf spelling;
the split remains Malli's.

### How the common queries read

Functions whose input AST directly mentions a registered schema in any
position:

```clojure
[:find [?sym ...]
 :in $ ?schema-key
 :where
 [?schema :seon.schema/key ?schema-key]
 [?arity :seon.fn.arity/input-refs ?schema]
 [?function :seon.fn/arities ?arity]
 [?function :seon.fn/sym ?sym]]
```

Functions producing it:

```clojure
[:find [?sym ...]
 :in $ ?schema-key
 :where
 [?schema :seon.schema/key ?schema-key]
 [?arity :seon.fn.arity/output-refs ?schema]
 [?function :seon.fn/arities ?arity]
 [?function :seon.fn/sym ?sym]]
```

Exact arity metadata is one pull, ordered after read by Malli's persisted
`:order`:

```clojure
[:find [(pull ?arity
              [:seon.fn.arity/order
               :seon.fn.arity/arity
               :seon.fn.arity/min
               :seon.fn.arity/max]) ...]
 :in $ ?sym
 :where
 [?function :seon.fn/sym ?sym]
 [?function :seon.fn/arities ?arity]]
```

Sorting by `:seon.fn.arity/order` is required after the pull. The relationship
is a set in Datahike; no consumer may treat datom iteration as semantic order.

### Same-transaction derivation and replacement

The single pure operation belongs in `seon.program` conceptually as:

```clojure
(contract-facts {:seon.program/spec spec-string
                 :seon.program/compile-options compile-options})
{:seon.fn/arities [nested arity component maps ...]
 :seon.fn/ast nested-AST-root-component-map}
```

It must:

1. EDN-read the canonical `:seon.fn/spec`.
2. Call `m/function-schema` once with the exact candidate projection options.
3. Enumerate `m/-function-schema-arities` in vector order and call
   `m/-function-info` for each.
4. Call `m/ast` once.
5. Observe direct `RefSchema`s with Malli walk, partitioned beneath each
   function-info role.
6. Return nested transaction data using deterministic, invocation-local
   tempids. It performs no transaction itself.

Both current producers must call that same function before their existing
transaction:

- static rows: `src/seon/fn.clj:229-263`;
- runtime declaration rows: `src/seon/sci/eval.clj:404-427`, committed by
  `src/seon/cluster/run.cljc:680-748`.

The canonical spec and every derived component then commit or abort together.
There is no state in a listener and no later repair job.

Exact replacement needs one seam change. The current runtime replacement emits
plain `[:db/retract eid attr]` for changed attrs
(`src/seon/cluster/run.cljc:738-748`). For component attrs, plain retract would
sever the edge and leave children. Replacement must use
`:db.fn/retractAttribute` for `:seon.fn/arities` and `:seon.fn/ast`, then assert
the new graph in the same transaction. This follows the established component
lifecycle rather than adding cleanup machinery.

## Migration and initialization

At the first init containing these declarations:

1. Install the bridge-derived attributes.
2. Query every function row carrying `:seon.fn/spec`; do not use a hard-coded
   count. The owner sizes this at approximately 950; the observed branches in
   this probe had 434/437 contracted rows.
3. Build every parsed graph in memory against the one candidate projection.
4. Transact all backfilled function graphs in one transaction. Any malformed
   contract aborts the whole backfill; init must not publish a partially parsed
   program graph.
5. Publish the source branch only after that transaction succeeds.

New or changed rows never use a migration path: the ordinary `seon.program`
producer emits canonical and parsed facts together. Existing clusters remain
sovereign under the source-publication law; they acquire the new model only
when explicitly reforked.

On a Malli revision change, init rebuilds all parsed components from
`:seon.fn/spec` in one transaction. This is the operational answer to Malli's
internal-AST warning.

## What this dissolves and what it does not

### Namespace context rendering

Today `seon.render.ns` reads every `:seon.fn/spec`, EDN-parses it, installs a
placeholder registry, walks it to discover references, then recursively pulls
schema rows (`src/seon/render/ns.clj:28-127,145-221`). This whole per-render
schema parser and placeholder registry disappears. The renderer seeds its
closure directly from the arities' input/output/guard ref facts, then follows
schema-to-schema refs. It may still read `:seon.fn/spec` to print the exact
function signature because the string remains presentation/source authority.

### Schema projection and removal guards

`projection-from-database` currently reads every spec string and recompiles the
complete contract population (`src/seon/schema.cljc:1679-1895`). Runtime
instrumentation still needs compiled Malli schemas, so parsed facts do not
replace canonical forms there. However, function dependency indexing and
schema-removal blocker discovery currently derive reference sets by walking
compiled contracts (`src/seon/schema.cljc:380-450,1199-1310`). At a database
boundary those dependency reads can become direct fact queries; the in-memory
candidate projection still derives from the candidate compiled objects before
the transaction. This keeps pre-commit validation honest and removes
post-commit rediscovery.

### Runtime lint namespace slice

The current `acquire!` namespace slice selects contracted functions by
`:seon.fn/spec` presence and source provenance, then installs their source
(`src/seon/sci/eval.clj:876-949`). Parsed contract facts do not replace
namespace/source selection, and they do not dissolve the call/source slice.
They remove any need for that slice or its consumers to reopen contract strings
when answering schema-relevance questions. Contract presence may remain the
admission predicate because the string is canonical.

### Workload and purity reachability

Workload/purity traversal remains reachability over `:seon.fn/calls` and leaf
facts; parsed schema facts do not replace the call graph. The current concrete
purity check reads `:seon.fn/workload` and `:seon.fn/calls`
(`src/seon/cluster/loop.cljc:296-322`). What changes is the seed and join:
"functions accepting/producing schema X, then which capability leaves do they
reach?" becomes an ordinary join from schema ref → arity → function → calls.
No consumer parses a Malli string to enter the reachability graph.

### Agent context and “what can I call with this data?”

The program graph finally supports the architecture claim directly. A value's
registered schema entity joins to matching input-ref arities, then to function
rows; outputs provide the forward dataflow join. Arity/min/max are already
facts for rendering an honest call shape. The agent context renderer can rank
or render those functions without a private parser, schema-name hand list, or
stored reverse back-pointer.

### Presence-only consumers

`seon.cluster.instruction/toolkit-namespaces` uses only `:seon.fn/spec`
presence as “contracted” (`src/seon/cluster/instruction.cljc:36-61`). It should
keep that canonical presence check. Runtime publication and receipt settlement
must also keep reading the canonical spec to compile and validate a candidate
before commit (`src/seon/sci/eval.clj:404-419`;
`src/seon/cluster/run.cljc:680-715`). Parsed facts are not a substitute for
Malli validation.

## Open questions for the owner

1. **Namespace spelling only:** should the derived AST facts live under
   `:seon.fn.ast/*` as proposed, or under `:malli.ast/*`? Recommendation:
   `:seon.fn.ast/*`; Malli supplies the leaf vocabulary but does not own Seon's
   durable database attributes, and the index is function-owned.
2. **Reference membership leaf spelling:** `input-refs`/`output-refs`/`guard-refs`
   or the longer `*-references`? Recommendation: `*-refs`, matching Malli's
   `RefSchema` protocol while keeping the `input`/`output`/`guard` split exact.
3. **AST retention depth:** retain all AST nodes as proposed, or only
   function-info plus direct refs and properties? Recommendation: retain all.
   The owner explicitly wants all useful parser data; measured `m/ast` cost is
   negligible, and the opaque string remains canonical if the internal shape
   changes.
4. **Backfill scope wording:** the owner estimate is approximately 950
   contracted functions, while the live `default`/`current-src` branches
   observed 434/437. Recommendation: query by `:seon.fn/spec` at init and keep
   every count out of code and schema.

No parser or schema semantic question remains open. These questions affect
attribute spelling and retained index breadth only.
