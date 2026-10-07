# Map: data, schemas, contracts, errors (Seon) versus the libraries it composes

Scope: `src/seon/{db,schema,fn,instrument,id,blob,cache,reconcile,config,error,fault,problems,program}`, `schema/*`, `fn/*`,
`error/refusal`, `cluster/{store,registry}`. Research only; nothing run; no JVM. Line counts are `wc -l` on the
checkout of 2026-10-07; "code lines" strip blanks, `;` comments and docstring lines (a small scan script, approximate).

## 0. Headline numbers

| Area (files) | Lines | Code lines | Closest library code | Library lines |
|---|---|---|---|---|
| Errors: `error.clj` 2837 + `error/refusal.clj` 830 + `fault.clj` 453 + `problems.clj` 658 | 4778 | ~3370 | `malli/error.cljc` 403 (+ `dev/pretty` 190, `dev/virhe` 205, `experimental/describe` 265) | 403 (1063 with printers) |
| Contracts at call time: `instrument.clj` | 1327 | 995 | `malli/instrument.clj` 162 + `core.cljc:2210-2245` `-instrument-f` + `core.cljc:3166-3185` `-instrument` | ~240 |
| Schemas: `schema.clj` 4226 + `schema/datahike` 1183 + `schema/edn` 694 + `schema/internal` 545 + `schema/admission` 510 | 7158 | ~5650 | `malli/registry.cljc` 105 + `malli/util.cljc` 404 + `core.cljc` registry paths | ~600 |
| Database layer: `db.clj` | 5971 | 4701 | `datahike/api.cljc` 89 + `api/impl.cljc` 395 + `writer.cljc` 494 (transact!) + `db/transaction.cljc` 1421 (validation) | ~2400 incl. the engine's own validation |
| Store / roster / GC: `cluster/store` 738 + `cluster/registry` 661 + `blob` 403 | 1802 | ~1200 | `datahike/connector.cljc` 561, `konserve/core.cljc` 799, `konserve/gc.cljc` 59 | |
| Program graph: `fn.clj` 4206 + `fn/analyzer` 835 + `fn/signature` 147 + `program.cljc` 1468 + `reconcile.cljc` 463 | 7119 | ~5800 | `clj-kondo/impl/analysis.clj` 233 (the analysis data itself comes from `clj-kondo.core/run!`), `editscript` 538 | ~770 |
| Cache, id, config | 307 + 147 + 971 = 1425 | ~1100 | Caffeine itself (`AsyncCache`), `clojure.core/hash` / MessageDigest | |

Whole area: 29,580 lines, about 23,000 code lines. Roughly 3,000 are docstrings, 1,470 `;` comments, 2,100 blanks.
So comments are not the main bulk; code is. About 1,500 lines of the eight biggest files are `:malli/schema` metadata
(db 444, schema 320, fn 263, error 218, instrument 142, config 53, problems 32, fault 16 lines, measured by a bracket
balance scan). Plus 15,493 lines of `resources/seon/schemas/*.edn` (203 files) that are outside this scope but are the
other half of the "contract" cost.

Malli whole `src` is 9,010 lines, of which `core.cljc` is 3,187 and carries every schema type, parser, explainer,
transformer hook, function schema and instrument hook. Seon's `error.clj` alone is 90% of malli's `core.cljc` size.

Per-file shape (largest forms; they show where code concentrates):

- `db.clj`: 277 top-level forms, 236 defn (179 private). Largest: `write-owned-values-error` 255 lines (:4088), `query-index-patterns` 120 (:679),
  `carried-projection` 114 (:1716), `transact-call` 75 (:5649), `index-evidence-current` 73 (:1221), `transact!` 70 (:5903).
- `schema.clj`: `projection-with-declarations` 234 (:2648), `projection-from-entities` 194 (:2965), `assert-complete-contract!` 182 (:1486),
  `projection-build` 173 (:2198), `canonical-definition` 123 (:649).
- `fn.clj`: `contract-findings` 151 (:2158), `var-entity` 146 (:692), `index!` 145 (:4063), `plan-file-change` 109 (:3039).
- `error.clj`: `prepare` 169 (:615), `commit-call` 165 (:1353), `recording` 115 (:1547), `reply-value` 112 (:2021).
- `instrument.clj`: `violation` 176 (:429), `compiled-wrapper` 114 (:824), `apply!` 106 (:1065).
- `fault.clj`: `record!` 178 (:138) in a 453-line file.

Malli's biggest idiom difference is visible immediately: its public surface is dozens of 3-to-12-line functions that
compose; Seon's has several 100 to 250-line functions that each own a whole concern.

---

## 1. Pairings and why the library is smaller

### 1.1 `seon.error` + `error/refusal` + `fault` + `problems`  <->  `malli.error` (+ `malli.dev.*`)

**What malli.error does in 403 lines.** One shape in, one shape out, and every step is a small function over data.

1. The source of truth is `m/explain` output, which is already the error value
   (`malli/core.cljc:2688-2703`):

   ```clojure
   (fn explainer
     ([value in acc]
      (when-let [errors (seq (explainer' value in acc))]
        {:schema schema' :value value :errors errors})))
   ```

   Valid is `nil`; invalid is `{:schema :value :errors [{:path :in :schema :value :type}]}`. No error class, no
   exception, no "classification", no stored discriminator. Each error entry already carries path (`:path` into the
   schema, `:in` into the value), the offending sub-schema, the offending value, and an optional `:type` keyword
   (`::m/missing-key`, `::m/extra-key`, `::m/invalid-type`, `::m/tuple-size` ...).
2. Messages are a lookup table, not code (`malli/error.cljc:44-62`):

   ```clojure
   (def default-errors
     {::unknown {:error/message {:en "unknown error"}}
      ::m/missing-key {:error/message {:en "missing required key"}}
      ::m/limits {:error/fn {:en (fn [{:keys [schema value]} _] ...)}}
      ::m/invalid-type {:error/message {:en "invalid type"}}
   ```

   The table is merged with the schema's own `:error/message` property and the registry's type-properties
   (`error.cljc:288-305`, `error-message` tries properties, type-properties, then the table, then `::unknown`). A
   schema author who wants a better sentence puts `{:error/message "..."}` on the schema; nothing else changes.
3. `humanize` is one `reduce` over the errors (`error.cljc:374-390`):

   ```clojure
   (reduce (fn [acc error]
             (let [[path message] (resolve explanation error options)]
               (-push-in acc value path (wrap (assoc error :message message)))))
           nil errors)
   ```

   with `:wrap` and `:resolve` as the only two extension points. `with-error-messages` (`:332`) is the same
   thing flat, as `{:errors [... {:message}]}`. `error-value` (`:392`) gives "the part of the value that is wrong".
   `with-spell-checking` (`:339-372`) adds misspelled-key suggestions using a 30-line Levenshtein (`:245-280`) and the
   schema's declared keys, still as data (`:type ::misspelled-key`, `::likely-misspelling-of`).
4. Rendering is separate and optional. `malli/dev/pretty.cljc` (190) and `malli/dev/virhe.cljc` (205) take the thrown
   `ex-info` (whose `ex-data` is `{:type :malli.core/invalid-input :data {...}}`, built by
   `core.cljc:203-207`, `-exception`/`-fail!`) and format it. Dispatch is a multimethod on `:type`
   (`virhe.cljc:183`: `(defmulti -format (fn [e _ _] (-> e (ex-data) :type)) :default ::default)`).
5. Where malli throws, it throws exactly one thing at exactly one place, and only at the boundary:
   `(defn -fail! ([type data] (throw (-exception type data))))`. The instrumentation `:report` hook defaults to it, and
   callers who do not want a throw pass their own report fn (that is the seam Seon already uses).

**What Seon does instead, and why it is 4,778 lines.**

- A Seon error is a stored entity with ~41 `:seon.error/*` attributes (`resources/seon/schemas/seon.error.edn`, 456 lines;
  `grep -o ':seon.error/[a-z-]*'` counts 331 keys in `db.clj`, 610 in `error.clj`, 146 in `config.clj`, 116 in
  `instrument.clj`). The validation problem is not kept as malli data; it is re-described into a bespoke
  `:seon.error/problem-description` with `expected-description`, `actual-description`, `fix` strings.
- `explain-problem` (`error.clj:916-969`, 54 lines) and `schema-expectation` (`:865-896`, 32 lines) rebuild, by hand,
  the English that `malli.error/default-errors` plus `malli.experimental.describe/describe` already provide:

  ```clojure
  (case (m/type check)
    :vector (bounded "a vector" "count") :sequential (bounded "a sequence" "count")
    :map (bounded "a map" "count") :set (bounded "a set" "count")
    :string (bounded "a string" "length") :int (bounded "an integer" nil)
  ```

  (`error.clj:881-889`); compare `malli/experimental/describe.cljc` (265 lines, `describe` at `:255`, with
  `-min-max-suffix` / `-length-suffix` at `:22-36`). `edit-distance` (`error.clj:1949-1958`) is a hand-written
  Levenshtein beside malli's `-levenshtein` (`error.cljc:256-259`) that `with-spell-checking` already uses.
  `problem-parts` / `problem-sentence` (`:970-1009`) assemble "X refused Y at P: expected E, got A. Fix: F" from those strings.
- `refusal.clj` re-implements a stack-frame classifier (`:1-130`: `library-url?`, `classify-namespace`,
  `authored-namespace?`, `frame-site`, `prepared-call-prefix`) in order to decide which stack frame is first-party and so
  who the fault belongs to. `error.clj:132-151` has a second `frame-site` (a different function with the same name in
  another namespace) that strips compiler numbering for identity. Clojure's own `Throwable->map`
  (`reference-code/clojure/src/clj/clojure/core_print.clj:473-482`) already returns `:cause :via [{:type :message :data :at}] :trace`,
  exactly the "class, message, ex-data per link" the owner's law asks for.
- Errors have **five** constructors in my area alone: `refusal/error` (`refusal.clj:745`), `refusal/diagnostic`
  (`:676`), `refusal/producer` (`:595`), and in `db.clj` the private `error-value` (`:173`), `diagnostic` (`:184`),
  `schema-refusal` (`:217`) and `dependency-error` (`:231`); `config.clj` and `instrument.clj` each add more call
  shapes (`ex-info` counts: schema 39, fn 38, config 7, db 7, instrument 5, error 4, fault 3). The law says "there is one way
  to build, store and show an error"; the code has at least four.
- `error.clj:1086-1720` plus `fault.clj:138-315` is a durable "occurrence" system: signature identity (`:153-182`, a
  64-character id over layer, operation, schema, class, frame-site, path and producer), first-notification
  selection, escalation, steward lookup, bump counts, openings and obligations (`commit-call` 165 lines, `recording` 115,
  `record!` 178 with a two-write origin plus cluster copy). That is a bug-tracker, written as a Datahike transaction
  function. It is the most Seon-specific piece in the area and the only one that is not a reimplementation; it can
  still be an order of magnitude smaller (see reduction 1).

**How much of seon.error / refusal / fault could be malli explain data plus ex-info.**

My estimate, conservative: the validation-failure half (everything that formats, describes, humanizes, builds
sentences, or constructs problem descriptions; `error.clj:865-1085`, `:1867-2020`, `:2505-2606` and `refusal.clj`
`diagnostic`/`error`/`producer` construction, ~900 lines) collapses to: keep the `m/explain` map under one key, keep
`operation`/`at`/`fix`, and call `me/humanize`, `me/with-spell-checking` and `mx/describe` at render time (render is
already a separate declared face per CLAUDE.md). The stack/frame classification half (`refusal.clj:1-330`, `error.clj:132-182`)
collapses to `Throwable->map` plus one `ns->first-party?` predicate. The occurrence/recording half (`error.clj:1086-1720`,
`fault.clj`) is a genuine feature: a counted, signature-keyed fact. In Datahike that is one upsert on the signature
identity plus `:db/cas`/`:db.fn/call` increment; the 12 reads in `commit-call:1366-1388` that check "does this entity
exist" are there because the stored refs must resolve, which is a modeling cost (see reduction 1). Target: the four
files from 4,778 to roughly 1,300 lines.

One important design simplification the owner's own law implies and the code does not take: the law says "errors
hold references, never copies" and "an error is one flat map under a named extension of `:seon.error/base`". malli's
explain map is already flat data under one shape; the extension is the `:type` keyword. Seon pays for a second
parallel type system (`:seon.error/base` + `:declared-schema` metadata via `vary-meta`, `refusal.clj:745-790`) where
`:type` in the data would do.

### 1.2 `seon.instrument`  <->  `malli.instrument` + `core.cljc` `-instrument`

Malli's whole mechanism is two short functions. `-instrument-f` (`core.cljc:2210-2245`, 35 lines, quoted shape):

```clojure
(fn [& args]
  (let [args (or args []), arity (count args)]
    (when wrap-input
      (when-not (<= min arity (or max miu/+max-size+))
        (report ::invalid-arity {:arity arity ...}))
      (when-not (valid? :input validate-input args args nil)
        (report ::invalid-input {:input input, :args (vec args), :schema schema})))
    (let [value (apply f args)] ...
```

and `-strument!` in `malli/instrument.clj:21-47`, which loops `(m/function-schemas)`, finds each Var and
`alter-var-root`s with `(m/-instrument dgen f)`. Registration is a single `swap!` into one nested map
(`core.cljc:3138-3144`, `-register-function-schema!`: `(swap! -function-schemas* assoc-in [key ns name] ...)`).
`:malli/schema` is read straight from var metadata (`instrument.clj:44-48`, `-schema`).

Seon already uses `m/-instrument`'s report seam (`instrument.clj:824-938`, `compiled-wrapper`, comments cite
"malli core.cljc:2229-2240"). The excess is in what it does with the report: `violation` (`:429-605`, 176 lines)
recomputes `m/explain`, finds union alternatives (`mu/subschemas`), calls a `closest-alternatives` ranker, renders an
arglist from `diagnostic-arglists`, and fabricates its own `:seon.error/base`. `minimal` (`:844-880`) is a second fallback builder
for when the first fails. `boundary-refusal` (`:723-777`) is a third. All of that is "turn a malli report into a nicer
message", which `malli.dev.pretty` and `me/humanize` do from the same report in `pretty.cljc:20-24`:

```clojure
(defn -errors [explanation printer] ...)
(defn -explain [schema value printer] (-errors (m/explain schema value) printer))
```

Why malli is shorter: it reports `{:input :args :schema}` and stops; presentation is a separate multimethod.

### 1.3 `seon.schema` (+ `schema/*`)  <->  `malli.registry` + `malli.util` + `core` registry/schema construction

Malli's registry is a 105-line file with a protocol of two methods (`-schema`, `-schemas`) and five constructors
(`registry.cljc:19-80`): `simple-registry`, `fast-registry`, `composite-registry`, `mutable-registry`,
`lazy-registry` (`:73-96`). Seon uses three of them (`schema.clj:590-648`, `projection-registry`):

```clojure
(mr/lazy-registry
  (mr/composite-registry (m/default-schemas) retained)
  (fn [identity scope] (when-let [definition (get prepared identity)] ... (m/schema definition {:registry scope}))))
...
(mr/fast-registry (mr/schemas registry))
```

So the registry itself is malli; the remaining 4,000+ lines of `schema.clj` are what Seon built around it:

1. **Incremental projection**: `projection-with-declarations` (`:2648-2881`, 234 lines), `projection-from-entities`
   (`:2965-3158`, 194), `replace-reverse-dependencies`, `replace-shape-entities`, `function-dependents-of`,
   `shape-projections-with`, `declaration-changes`, `fingerprint-with`, `incremental-projection-keys` (~700 lines
   total, `:2420-2650`): recompile only changed schemas plus their reverse closure, "the result equals build-projection"
   (docstring `:2649-2669`). It is a hand-rolled incremental view maintenance system. Malli answers "what changed" with
   immutable schema objects (a schema is a value; `-cached` memoizes on the object itself, `core.cljc:353-361`), and a
   lazy registry that compiles on demand. A registry swap is one pointer.
2. **Fingerprints and canonical digests** (`:855-956`, `canonical-data-string`, `projection-fingerprint`, version constant
   `:955`): content addressing of schema forms. `pr-str` over a sorted structure, or `clojure.core/hash` plus `id.clj`
   `sha-256`, is the same function. 100 lines.
3. **Admission / completeness gating** (`assert-complete-contract!` `:1486-1667`, 182 lines; `validate-declarations!`
   `:2077-2179`; `schema/admission.clj` 510): predicate-purity proof, render-contract compatibility, reference acyclicity,
   "unresolved reference" refusals (`:485-589`). Malli's way is `m/schema` throwing `::m/invalid-schema` at compile
   plus `m/deref-all` for cycle-safe walks; policy rules (naming, collisions, near-duplicates, `admission.clj:236-420`) are
   a repo lint and live fine as a linter (kondo hook or a test), not in the runtime path.
4. **Datahike bridge** `schema/datahike.clj` (1183 lines): `malli->datahike-attr-in` (`:206`) is a type table
   (`:19-31`, a 13-entry map) and 60 lines of property reading. The remaining ~900 lines are the **vector cell** subsystem
   (`:419-1015`: `vector-attribute-in?`, `vector-pull`, `decode-vectors`, `lower-where`, `encode-transaction-data-in`,
   `physical-selector`) which stores ordered collections as separate cell entities and rewrites queries/pulls to match.
   Datahike has no ordered cardinality-many; this is Seon paying for that by adding a second storage model in the
   middle of the stack (see reduction 5).
5. **Projection state**: the projection is a map of ~30 `:seon.schema.projection/*` keys carried inside the database
   value (`db.clj:1716-1829`, `carried-projection`, 114 lines, plus `projection-cache-policy` `:1404`, `projection-cache-key`
   `:1423`, `declaration-content-key` `:1448`, `revision-agreement` `:1482`, `nearest-base` `:1498`, `speculative-projection`
   `:1543`, `construction-projection` `:1588`): ~480 lines in `db.clj` whose job is "find a registry for this database
   value without rebuilding". Malli never needs this because its registry is passed in (`{:registry r}` option) not
   discovered from a database.

Why malli is shorter: schema objects are immutable values with their own memoization slot; registries compose with
`composite-registry`; policy rules are not in the compile path; there is no second representation (no stored-vs-authored,
no cells, no fingerprint).

### 1.4 `seon.db`  <->  `datahike.api` / `api.specification` / `api.impl` / `writer`

**How Datahike wraps its own API (89 + 1,282 lines).** `datahike/api.cljc` is one macro, `emit-api`
(`api.cljc:48-88`), that iterates a *data* map and emits a `def` per entry:

```clojure
`(def ~(with-meta n {:arglists `(malli-schema->argslist (quote ~args)) :doc doc})
   ~impl)
```

The map is `datahike.api.specification/api-specification` (1,282 lines, but 40 entries of
`{:args <malli :=> schema> :ret ... :doc ... :impl datahike.api.impl/transact}`; e.g. `specification.cljc:420-440`,
the `transact` entry). The same map generates the Java, Python, CLI, pod, HTTP, native and JS bindings
(`codegen/*`, which is why the data is verbose and the code is not). Each implementation is a thin delegate: 
`api/impl.cljc:30-42`, `transact!` normalizes `arg-map` into `{:tx-data ...}` (12 lines) and calls `dw/transact!`; the sync
variant is `@(transact! ...)`. Errors are `ex-info` through `log/raise` (`datahike/db/transaction.cljc:31,36,51,104,118,456,535,636`):
`(log/raise "Cannot add " datom " because of unique constraint: " found {:error :transact/unique ...})`. One carrier
(`ExceptionInfo`), one discriminator (`:error`), strings built where raised. Validation is part of the engine, done
once, on the datom, in `db/transaction.cljc` (1,421 lines for the entire transaction processor, including upsert,
tuples, composites and schema flexibility) and `schema.cljc` (311 lines of specs). The writer is `writer.cljc:427-458`,
`transact!`, 30 lines enqueuing to one thread plus `create-thread` (`:112-310`) that owns the loop.

**What Seon's 5,971-line `db.clj` is doing** (277 forms; I classified by region):

| Region | Lines | What it is |
|---|---|---|
| 1-550 | 550 | Custody/connection/database identity, dynamic vars (`*conn*`, `*read-database*`, `*receipt*`, `*open*`, `*read-evidence-sink*`), error constructors (:173-260), foreign-connection errors |
| 552-1350 | 800 | Read evidence: capture index patterns per query, stable result digest, dependency revision, replay, "current?" checks (`read-evidence` :1009, `index-evidence-current` :1221, `read-evidence-current?` :1294) |
| 1350-1830 | 480 | Projection discovery/caching on database values (above) |
| 1830-2580 | 750 | Declaration reading (`with-declarations`), value codecs, query encode/decode for EDN-encoded and vector-cell attributes (`encode-query-request` :2249 ... `decode-pull-result` :2568) |
| 2580-3460 | 880 | The public reads: `db`, `q`, `pull`, `pull-many`, `entity`, `datoms`, `index-page`, `history`, `as-of`, `since`, `diff`, with argument alignment, nil-input guards, relation-only handling, pull-budget checks |
| 3457-4090 | 630 | Pre-write validation: hand walk of tx-data against per-attribute malli schemas (`write-error` :3823, `write-map-error` :3793, `write-attribute-error` :3728, `write-key-candidates` :3777) |
| 4088-4430 | 340 | Post-write "owned values" validation (`write-owned-values-error` 255 lines) |
| 4431-4680 | 250 | Arity-mismatch report over stored call edges (also `fn.clj:2330-2337` re-exports it) |
| 4676-4925 | 250 | Render-pair and deletion guards (`write-render-target-error`, `removed-definition-error`, `deletion-error`) |
| 4928-5150 | 220 | Vector diff / migration tx |
| 5150-5410 | 260 | Digest verification of written declarations, report validation |
| 5411-5555 | 145 | Retention rules/checks |
| 5555-5971 | 415 | `prepared-write`, `transact-call`, `with`, `transact!`, transaction rendering |

Concretely, Datahike's `q` takes `(q query & inputs)`. Seon's `q` (`:2783-2845`, 63 lines, preceded by ~330 lines of
helper) re-implements argument alignment, nil rejection, relation-only detection, codec encode/decode and read-evidence
capture, wrapped in `try/catch ExceptionInfo -> dependency-error`. The wrapper is bigger than the thing it wraps by
an order of magnitude because it adds five concerns (supplied database, encoded storage, vector cells, read evidence, error
conversion) to one call. Each concern is legitimate on its own; none of them is expressed as data, so each lands in the
function body.

**Why Datahike is smaller here.** (a) the surface is *data* (`api-specification`), the code is one macro; (b) it does not
convert exceptions: errors are `ex-info` with `:error` key, raised where detected, caught at the boundary; (c) validation
is one pass in the engine, on the datom; (d) there is no second storage model; (e) it holds state in the connection
value + the writer thread, with no cross-cutting dynamic vars.

Important: Datahike already provides the hook that would carry most of Seon's post-write validation
(`specification.cljc:429`, the `transact` docstring): "Optional :tx-meta key :datahike/validate-report is a process-local
callback ... Return nil to accept or any value to reject the entire transaction with :transaction/validation-rejected
and :datahike/validation-refusal ... Runs once after expansion, before commit". `db.clj:5594-5625`
(`prepared-write`) appears to use it; the remaining hand-rolled pre-write walk (:3457-4090) is therefore a second,
duplicate validator running before the first.

### 1.5 `seon.fn`, `fn/analyzer`, `program`, `reconcile`  <->  clj-kondo analysis, editscript, Datahike rules

`clj-kondo` does the hard part. `seon.fn.analyzer` is already a thin adapter: `var-definition` (`analyzer.clj:153-163`),
`var-usage` (`:164-179`), `keyword-usage` (`:180-188`) are 10-line projections of kondo's analysis maps. The 835 lines are
dominated by dependency-cache management (`analyze-dependencies!` :360, `ensure-dependency-analysis!` :374, `forget-namespaces!`
:403, `write-mirror!` :433, cache-key derivation :336), which is Seon-specific because a running JVM's classpath is analysed
out-of-process. That is cost, not duplication.

`fn.clj` (4,206 lines, 167 forms) turns the analysis into entities and keeps them reconciled:

- 692-837 `var-entity` (146 lines) and 1097-1700 per-file entity builders; `program.cljc:700-1200` (~500 lines) stores each
  function contract's arglists as **entities**: `binding-entity`, `map-binding-entries`, `sequential-binding-children`,
  `typed-key-facts`, `argument-entity`, `arity-entity`, `contract-facts` (`program.cljc:1097`). That is a datafied
  destructuring parser (shorthand keys, `:keys`, `:as`, nested seqs) for data that malli already holds as
  `(m/-function-schema-arities compiled)` + `(m/-function-info arity)` (used at `program.cljc:1126`, one line before this
  parser starts) and that Clojure already holds as `:arglists` metadata. Every stored fact needs a consumer (CLAUDE.md "Data
  and contracts"); I found `contract-facts` consumed from `cluster/source.clj` and `my/program.clj` only, and the argument/binding entity
  family looks like a candidate for "derive on read".
- 1750-2160 reach rules, `gate-sets`, `reverse-closure`, `tests-reaching`, `functions-without-tests`: this is transitive
  closure over call edges, expressed partly as Datalog rules (good) and partly as imperative loops (`reverse-closure`
  :2007, `call-reach` :1973, `gate-set-in` :1896, `gate-sets-in` :1921).
- 3039-4206 (1,170 lines) is `plan-file-change`, `compile-index-transaction`, `reconcile-tx-in`, `published-index-entities`,
  `adoption-tx`, `index!`: converge stored rows to source.

**Four diff/convergence planners exist**: `seon.reconcile/plan` (config), `fn/reconcile-tx-in` (`fn.clj:3679`, program rows),
`program/exact-replacement-tx` (`program.cljc:1372-1425`, "changed-attributes", "replacement-tx"), and `db/vector-migration-tx` /
`vector-diff` (`db.clj:4999-5146`). All four are "given desired entity maps, current pulled entities, produce retract/assert
tx-data". `editscript` (538 lines, `edit.cljc:288`, `patch.cljc:107`) is the library for structural diff; `seon.reconcile`'s own
docstring (`reconcile.cljc:1-47`) already states the contract in prose ("changed attributes retracted and re-asserted,
absent-from-desired managed entities retracted entirely"). That statement is one function; having it four times is the
duplication CLAUDE.md "Collapse duplicates" is aimed at.

### 1.6 `cluster/store`, `cluster/registry`, `blob`, `cache`, `id`, `config`

These are the leanest parts and mostly correct delegations; savings here are small and mostly comment/ceremony.

- `cluster/store.clj:155-210` `datahike-configuration`: 55 lines of which ~35 are evidence comments about LZ4 and
  branching factors; the data itself is 15 lines. Good shape (config as one map). `open-store!` 101 lines
  (`:494-594`) owns flock + genesis + connect; `datahike.connector/connect` is 561 lines total and does not own a
  flock, so this is real.
- `cluster/registry.clj:561-660` `collect-and-inventory!` correctly delegates to `d/gc-storage` with a
  `:datahike.gc/reachable-extension` (`:569-576`): a model of "compose the dependency's seam". `retention-cutoff` (`:588-628`,
  41 lines) hand-builds a refusal with `ex-info` + prose; `referenced-blobs` (`:475-534`, 60 lines) is a hand-written scan of
  attribute holders (could be one Datalog query over attrs carrying the `:seon.blob/digest` property).
- `blob.clj` (403 lines, 4 docstring lines): `stage-binary!` (`:240-303`, 64 lines) is a manual read loop with SHA-256 digest,
  inline-prefix and spill-to-file. Konserve already stores binaries (`konserve.core/bassoc`, `konserve.core` 799 lines); the
  inline-prefix-then-spill policy is Seon's, but a `DigestInputStream` and `Files/copy` would replace the loop (~40 lines). The
  zero-read `ex-info` at `:271-278` builds an error via `refusal/diagnostic` and *throws* it: against the "return, don't
  throw" law and inside a function the contract says cannot fail.
- `cache.clj` (307 lines) wraps Caffeine's `AsyncCache` with per-kind `LongAdder` counters (`:40-52`, `counters`,
  `kind-counters`), claim/settle/join (`:108-160`) and weights. Caffeine itself provides `recordStats()`, `Weigher`,
  `AsyncCache.get(key, mapping)` (single-flight) and `Policy.Eviction`; per-kind stats are the only extra. 307 -> ~120 is
  plausible by using `Caffeine.recordStats` and keying kinds into the key. Low value but low risk.
- `id.clj` (147): `printed` (`:59-73`) canonicalizes by printing; `id` (`:95`) is sha-256 over it. Reasonable; this is the
  single file where a sorted-map `pr-str` is the whole idea.
- `config.clj` (971, 48 forms): `apply-compiled!` (:746, 73 lines), `admit-initialization-entities` (:268, 83), a
  re-implementation of `seon.reconcile` call (`reconcile-call` :682). Config as database facts is a law; the cost is that
  every dial passes through `schema-registered` + `admitted` + `reconcile` + `effective`. Candidate for 971 -> ~500 once
  reconcile is the single planner.

---

## 2. Idioms that explain the ratio (why the libraries stay small)

1. **Data-driven dispatch instead of control flow.** malli: `default-errors` map keyed by error type
   (`error.cljc:44`), type table `malli-type->datahike-type`-style tables; datahike: `api-specification` map drives macro,
   CLI, pod, Java, Python from one map. Seon: `case`/`cond` ladders (`error.clj:881-892` `schema-expectation`,
   `db.clj:3728-3775` `write-attribute-error`, `instrument.clj:844-870`).
2. **Small functions, one verb.** malli.error's largest function is `-resolve-root-error` (16 lines, `error.cljc:310-324`);
   core median is ~6 lines. Seon `db.clj` has 8 functions over 65 lines and `error.clj` 4 over 110, so each function holds
   state-in-locals that a data structure would otherwise carry.
3. **Errors: ex-info with one discriminator, built where raised; messages derived at the edge.** malli:
   `(ex-info (str type) {:type type :message type :data data})` (`core.cljc:203`). datahike: `log/raise` strings plus
   `{:error :transact/unique}`. Seon: ~41 stored attributes, five constructors, a declared output union per function,
   `vary-meta` to tag the producer's schema (`refusal.clj:790`, `db.clj:213,225`), and prose composed in the producer
   (`:seon.error/fix`, `expected-description`, `actual-description`) rather than at the render.
4. **Registry = a protocol with two methods and `composite-registry`.** Seon uses it correctly (`schema.clj:590`) then builds
   a second discovery system (`carried-projection`) around it.
5. **State held in the object that owns it.** malli: `Cached` slot on the schema (`core.cljc:353-361`, `-cached` does
   `(swap! c assoc k r)` on the schema's own atom); function schemas are one atom of a nested map (`core.cljc:3138`). Seon has
   the right instinct (state in the owner) but places derived state in a *separate* central Caffeine cache plus
   `projection-cache-value` plus `*projection-state*` dynamic vars (`schema.clj:1030-1034`) plus database-value metadata.
   That is four homes for one derived-state concept.
6. **Delegate instead of re-check.** datahike validates a datom once at the engine; Seon validates writes three times:
   hand walk (`db.clj:3457-4090`), owned-values (`:4088`), `validate-write-report` (`:5377`) plus datahike's own.
7. **Library features unused.** `me/humanize` (14 total `me/`+`mu/` references in `src`), `me/with-spell-checking` (0), `malli.experimental.describe` (0),
   `malli.dev` (0), `Throwable->map` (0), `editscript` (0 in this area), `:datahike/validate-report` (used, but beside a duplicate pre-walk).

---

## 3. Specific question: malli.error in one shape; how much of Seon's error stack could be explain data + ex-info

malli.error is a **projection of one value** (the explanation) into several views, all pure functions of
`{:schema :value :errors}`:

| View | Fn (`error.cljc`) | Lines |
|---|---|---|
| per-error message | `error-message` :288 | 18 |
| flat | `with-error-messages` :332 | 6 |
| nested like the value | `humanize` :374 | 17 |
| the offending value subtree | `error-value` :392 | 12 |
| suggestions | `with-spell-checking` :339 | 33 |
| path | `error-path` :282 | 6 |

It does not store, classify, count, route or recur anything. The error is created by the validator; its owner is whoever
called `m/explain`; and `ex-info` appears only at the instrumentation boundary.

Mapping to Seon's laws:

- "Errors are ordinary values of one shape": malli's explanation *is* that, with `{:type}` as discriminator. Replace
  `:seon.error/declared-schema` (metadata) and the five constructors with: a flat map
  `{:type :seon.error/... :seon.error/operation sym :seon.error/at inst :seon.error/explain <m/explain map> ...producer fields}`.
- "Whole cause chain": `Throwable->map` plus walking `ex-data` yields it; Seon already does the `ex-data` walk in
  `refusal/refusal` (`refusal.clj:800-830`, 30 lines, fine).
- "Errors hold references, never copies": `references` (`refusal.clj:419-470`, the `referenced` walker above) is the right
  idea and should stay; it is the one piece with no library equivalent and a real bug-class behind it (the 2 GB OOM comment).
- "Deliver to whoever can act": the occurrence store; keep, but model as `{:seon.error/signature :seon.error/count :seon.error/first-occurrences}`
  rather than 18 attributes with component occurrence slots and escalation/obligation/notified sets.
- "Render per consumer": `render-ai`/`render-html` over the explain map; messages from `default-errors` (merge Seon's
  `:error/message`s into the registry-level table via the `:errors` option, which malli.error supports: `error-message`
  looks at schema properties first).

Estimated size after: refusal construction 150, frame classification 120, humanize/describe glue 100, occurrence
recording 350, render 300, fault 150 = ~1,170 lines versus 4,778.

---

## 4. Top 10 reductions

Estimates are lines removed from this area net of the replacement (replacement lines are counted as added); "risk" is the
chance the swap changes agent-visible behavior or boot-critical paths. Ordered by (lines saved) x (confidence).

### R1. Error text and problem descriptions: use malli.error + describe instead of hand-built sentences
- **Seon code**: `error.clj:865-1010` (`schema-expectation`, `collection-member-problem`, `explain-problem`, `problem-parts`,
  `problem-sentence`, `scalar-text`), `:1867-2020` (`evidence-path`, `leaf-expected`, `expected-shown`, `example`, `problem-shown`,
  `missing-symbol`, `edit-distance`, `names-in`, `call-lines`), `:2505-2606` (prose helpers), plus `db.clj:3645-3690`
  (`write-problem`/`invalid-write`), `instrument.clj:429-605` (`violation`).
- **Replacement**: store the `m/explain` map (schema as `m/form`, value as a reference) under `:seon.error/explain`; at render time
  `(me/humanize explanation)`, `(me/with-spell-checking ...)` and `(malli.experimental.describe/describe schema)`; add Seon-specific
  messages as `:error/message` properties on the schemas that deserve them (the registry already has `:description` on 100s).
- **Lines removed**: ~900 (error.clj ~560, instrument.clj ~200, db.clj ~140).
- **Risk**: medium; agent-visible text changes (the existing sentences have been tuned over many sessions and are asserted by golden transcripts). Do on a branch, regenerate goldens, and compare token counts as the CLAUDE.md render law requires.

### R2. One error constructor
- **Seon code**: `db.clj:173-260` (`error-value`, `diagnostic`, `schema-refusal`, `dependency-error`, `refused-read`), similar locals in `config.clj`
  (9 `diagnostic` uses), `fault.clj:316`, `refusal.clj:595-745` (`producer`, `diagnostic`) merged with `error` (`:745`).
- **Replacement**: `refusal/error` as the only constructor taking `{:type kw ...}`; delete `vary-meta ... :seon.error/declared-schema`
  (a `:type` key in the data replaces the metadata, so errors survive `merge`/`into`, which metadata does not).
- **Lines removed**: ~350 (constructors ~150, call-site boilerplate ~200).
- **Risk**: low-medium. Contract union per function (`:seon.db/error-result`) keeps working if the error union is expressed with `:multi {:dispatch :type}`, which is malli's idiom (`malli.core` `:multi`).

### R3. Stack/frame classification via `Throwable->map`, one frame function
- **Seon code**: `refusal.clj:1-330` (classpath basis reading, `library-url?`, `classify-namespace`, `authored-namespace?`, `frame-site`, `authored-prefixes`, `first-party-in`, `prepared-call-prefix`) and `error.clj:132-182` (second `frame-site`, `signature`).
- **Replacement**: `Throwable->map` for chain/trace (`core_print.clj:473`); one predicate `first-party?` = "the class's resource comes from a
  file under a project root"; signature = `id/id [operation type root-frame-ns+fn path]`. Keep the 2-GB-OOM-driven `references` walker.
- **Lines removed**: ~350. **Risk**: medium (fault identity is derived from frames; changing it re-opens every existing fault once; fine because DB data is disposable per memory "DB DATA IS DISPOSABLE").

### R4. Occurrence/fault recording as one upsert
- **Seon code**: `error.clj:1086-1720` (`commit-call` 165, `recording` 115, `prepare` 169, `resolution`, `opening`, `notification-id`, `message-tx`, `producer-task`, `bump-call`, `repeat-of`, `occurrence-slot`), `fault.clj:138-453` (`record!` 178, `fault!` 96).
- **Replacement**: transact `{:seon.error/signature s :seon.error/last-at t :seon.error/count (inc)}` through one `:db.fn/call` that upserts by identity attribute (Datahike entity-map upsert + `:db/cas`); keep first-N occurrence bodies as blobs and the wake as a derived datom the existing listener already reads. Today each repeat consults 12 existence checks (`commit-call:1366-1388`).
- **Lines removed**: ~900. **Risk**: medium-high (deliver-to-agent and wake semantics live here; needs a regression per behavior class). Not before R1-R3 land: the shape of the error value must stabilize first.

### R5. Vector cells: choose a native ordered representation (design decision, not a mechanical swap)
- **Seon code**: `schema/datahike.clj:419-1015` (~600), `db.clj:2058-2580` (query/pull encode+decode, ~520), `db.clj:4928-5150` (vector diff, ~220), plus vector parts of `prepared-write` and `fn.clj` index builders (~100).
- **Replacement options (owner to price)**: (a) EDN-encode small ordered collections as one string value (the code already supports edn-encoded attributes: `schema/datahike.clj:396-418`, `db.clj:1908-1952`), (b) `:db.type/tuple` homogeneous tuples where arity is small, (c) component refs with an explicit `:order` attribute and sort at read (what most Datomic-style apps do), pulling with `:xform`. Choose per attribute in the schema property; the generic cell rewrite goes away.
- **Lines removed**: 800-1,300. **Risk**: high: storage format, query semantics, every vector-valued attribute in the 15k-line schema. It should be proven on a branch of the live store per the CLAUDE.md schema-change rule. Highest absolute win, so it needs an owner ruling.

### R6. Write validation: one pass, via the hook Datahike already offers
- **Seon code**: `db.clj:3457-4090` (pre-write walk: `write-error`, `write-map-error`, `write-attribute-error`, `write-ref-error`, `write-many-values`, `write-key-candidates`, `write-attribute-plan`, `cell-rows`, `cell-vectors`, `write-entity-value`, `write-error-identity`), and the redundant parts of `write-owned-values-error` (:4088-4343) and `validate-write-report` (:5377-5411).
- **Replacement**: validate the `:datahike/validate-report` argument (the final report with every entity the transaction touches, `specification.cljc:429`) once with `m/explainer` of the entity schemas (`:multi` dispatching on the identity attribute, which is how the program already names entity kinds), return `nil` or the explain map. Closest-match suggestions from `me/with-spell-checking` (replaces `write-key-candidates`, ~15 lines). This is "decide at the authority" in CLAUDE.md terms: the writer already serializes.
- **Lines removed**: ~700. **Risk**: medium-high: this is the boot-critical write gate. The existing 255-line owned-values function enforces component bounds (`validation-node-limit`) and ownership cycles; those rules must be preserved as small separate functions over the report, not dropped.

### R7. Incremental projection: measure the full rebuild first, then keep only what the measurement demands
- **Seon code**: `schema.clj:2420-2880` (`projection-with-declarations` 234, `replace-reverse-dependencies`, `replace-shape-entities`, `declaration-changes`, `fingerprint-with`, `replacement-base`, `incremental-projection-keys`) and `db.clj:1350-1830` (`projection-cache-*`, `nearest-base`, `speculative-projection`, `construction-projection`, `carried-projection` 114).
- **Replacement**: a registry is `(mr/lazy-registry defaults provider)` (`registry.cljc:73-96`); a changed declaration is a `(assoc forms k v)` and a new lazy registry; the lazy registry compiles on first use, so "recompile only what changed" is the default behavior, not a feature. Key the whole registry by `(hash forms)` in `seon.cache`. If the measured full rebuild is under the owner's sub-second target, delete the reverse-closure machinery.
- **Lines removed**: 500-700. **Risk**: medium; correctness is easy (the docstring at `schema.clj:2649-2669` already names the equivalence test `an-incremental-projection-equals-its-full-build`, which becomes the proof), but performance is the thing to measure. CLAUDE.md "simple and correct first, then optimize" favors trying it.

### R8. Read evidence delegated to Datahike's dependency plan
- **Seon code**: `db.clj:552-1350` (~800 lines: index pattern capture, `stable-value`, `read-result-digest`, `replay-read`, `index-evidence-current`, `read-evidence-current?`).
- **Replacement**: use only `:datahike.read/dependency-plan` + `:datahike.read/revision` (already stored, `db.clj:1019-1050`) and compare revisions (`read-evidence-current?:1311-1314` does that first: "Equal attribute revisions mean no commit since the read changed any attribute it depends on"). The history-scan and replay-and-compare branches (`:1316-1345`) exist for the "different revision, still equal result" case, a precision optimization (fewer re-renders). Per "optimize for the common path, not the rare failure": measure how often the revision differs but the result is equal; if rare, drop replay.
- **Lines removed**: ~400. **Risk**: high-medium (render refresh correctness; lose a cache hit not a result). Needs the miss-count measurement first.

### R9. One convergence planner; derive contract facts instead of storing binding entities
- **Seon code**: `program.cljc:700-1200` (binding/argument/arity entity family, ~500), the duplicate planners `fn.clj:3679-3775` (`reconcile-tx-in`, ~100), `program.cljc:1338-1425`, `config.clj:597-700`, `db.clj:4999-5146` (with R5).
- **Replacement**: `seon.reconcile/plan` is already the pure exact diff with provenance and no-op detection; give `fn`, `program` and `config` entity maps that satisfy its "exactly one identity attribute" contract and delete the others. For contract facts, derive arities and bindings from `(m/-function-schema-arities compiled)`/`:arglists` at read (the consumers are 2 files).
- **Lines removed**: ~700. **Risk**: medium; `fn.clj` reconcile has measured performance fixes (e.g. "ONE READ for every desired entity ... 0.42 ms a call, 6,106 calls" `fn.clj:3695-3700`) which `reconcile/plan` must preserve (it computes inside the writer via `reconcile-call`, so it already does).

### R10. Instrument: report-only wrapper and a small boundary
- **Seon code**: `instrument.clj:614-720` (`wrap-interpreted` 68), `723-777` (`boundary-refusal`), `824-938` (`compiled-wrapper`, includes `minimal` fallback builder `:844-880`), `1065-1170` (`apply!` 106), `1228-1287` (`restore!` 59).
- **Replacement**: `(m/-instrument {:schema s :report (fn [kind data] (error/return! kind data))} f)` with `kind`/`data` straight from malli, error built in R2's constructor; arm/restore by `alter-var-root` like `-strument!` (`malli/instrument.clj:21-47`); keep the caller-frame attribution (1 function).
- **Lines removed**: ~450. **Risk**: medium; the "supply defaults by schema name" arming (the `call-preparation` wrapper) sits in front and must stay a separate wrapper; keep a regression on supplied-world-member refusal.

Runner-ups (each 100-250 lines, low risk): `cache.clj` to Caffeine's own `recordStats` (-150); `blob.stage-binary!` to `DigestInputStream` + `Files/copy` (-60); `schema.clj:855-956` fingerprint/canonical printing to one `id/id` call (-80); `fn.clj` reach rules to Datalog rules only (-200); remove the `fn/arity-mismatches` pass-through (`fn.clj:2330-2337`, -8) and the `store.clj` generator re-exports (`:122-127`); move the 1,500 lines of `:malli/schema` metadata for private fns to resource edn only when it is shared (-0, noted not counted).

### Sum
R1 900 + R2 350 + R3 350 + R4 900 + R5 ~1000 + R6 700 + R7 600 + R8 400 + R9 700 + R10 450 = **~6,350 lines**, plus ~500 runner-ups: about 6,800 of the area's ~29,600 (23%). That moves the area to ~22,800 lines. That is nowhere near an 11%-of-today target (about 3,200 lines for this area at the 10k/91k ratio); closing that gap needs the deletions this report does *not* contain, and they are design questions:

1. The 15,493 lines of schema EDN are the other half of the contracts and are not counted; if every function and every private function must carry a complete schema (CLAUDE.md "Contract every admitted function, private ones included") the cost scales with function count, not with design quality. The cheapest cut is fewer functions (`db.clj` has 236 defn for what Datahike exposes as ~40 public calls).
2. "Read evidence", "vector cells", "incremental projection", "occurrence records" and "stored binding entities" are each a self-contained subsystem that a first-principles review could remove rather than shrink. Each is worth an owner decision with three priced options (CLAUDE.md "Merge/Decide" convention): delete, shrink to the library idiom above, keep.

---

## 5. Evidence log (files read, for verification)

Seon: `src/seon/error.clj` (1-200, 865-1010, 1353-1400, 1949-1966), `error/refusal.clj` (1-140, 335-420, 745-830), `fault.clj` (1-30, 138-200),
`instrument.clj` (429-470, 824-880), `db.clj` (173-260, 552-1074, 1294-1350, 2783-2846, 3362-3400, 3645-3835, 4088-4130, 4928-4960, 5649-5724),
`schema.clj` (590-648, 1486-1560, 2648-2700, 3772-3810), `schema/datahike.clj` (1-60, 419-470), `fn.clj` (1-40, 2330-2337, 3679-3775),
`fn/analyzer.clj` (1-30 + defn list), `program.cljc` (669-690, 1097-1130 + defn list), `reconcile.cljc` (1-48),
`cluster/store.clj` (155-215), `cluster/registry.clj` (561-661), `blob.clj` (240-303), `cache.clj` (1-60).

Library: `malli/src/malli/error.cljc` (44-62, 245-282, 288-403), `registry.cljc` (all 105), `instrument.clj` (all 162), `core.cljc`
(195-215, 347-361, 2205-2250, 2688-2711, 3109-3185), `experimental/describe.cljc` (1-40, 255-265), `dev/virhe.cljc` (1-40, 183), `dev/pretty.cljc`
(1-30);
`datahike/src/datahike/api.cljc` (all 89), `api/specification.cljc` (410-452), `api/impl.cljc` (30-56), `writer.cljc` (function index),
`db/transaction.cljc` (raise sites), `schema.cljc` (index); `konserve/src/konserve` (file sizes);
`clojure/src/clj/clojure/core_print.clj:473-482` (`Throwable->map`).

Not verified (flagged for the owner): whether `:seon.db.vector/cells` has a measured justification beyond ordering (search git history
for its introducing commit); consumers of the stored binding entities beyond `cluster/source.clj` and `my/program.clj` (I used a file-level grep); the
per-region line classification of `db.clj` is by reading function names, not by tracing call graphs; the "malli explain is enough for
agent-facing text" claim needs a golden-transcript comparison before any landing (R1 risk).
