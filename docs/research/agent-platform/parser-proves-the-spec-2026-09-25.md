---
type: research
status: "exploratory proof lane prove-parser-spec-opus; REPL evidence, no production edits"
created: 2026-09-25
scope: can the one parser (clj-kondo fork + seon.fn row producers) produce every fact language-requirements asks of it
spec-pin: 2a1b5fbf8 (language-requirements-2026-09-24.md, derived-contracts-2026-09-25.md, function-face-and-canonical-authoring-2026-09-25.md)
kondo-pin: reference-code/clj-kondo 57252e07975710aa579b24f0d1b2b1e04195caa2
---

# The parser proves the spec (2026-09-25)

**Verdict.** Every link the spec asks for (task ids, must-read, see-also, proofs and seeds) is already present in the
analysis the indexer computes today. Today it is dropped between `seon.fn.analyzer/analyze` and `seon.fn/var-row`.
A prototype of about 40 lines turned that analysis into every requested edge and refusal in **2.1 ms**, and reconciled
it with edamame-read task EDN in **1.1 ms**. The reverse lookups ran on a `d/with` value in **0.2–1.0 ms** each.

Two parts of the spec do not hold as written:
- **Return keys from kondo's type inference (1.12) are a gap in both export and coverage.** The analysis exports no
  return types. The kondo cache holds a return type per arity, but only when the tail is a literal map: `let`, `if`,
  `when`, `assoc`, `merge`, `cond->`, `->`, `into` and plain calls all yield nothing.
- **Several encodings are silent or ambiguous.** `^:seon/tasks` stores `true`. The attribute-map style of `deftest`
  loses its proof edge. A bare symbol in `defn` metadata becomes a function object at runtime. `::alias/k` is
  invisible to grep. The Clojure reader turns an unrequired alias into a keyword that looks plausible.

Each has a small, loud fix, listed in §3.

For row 1.15, every stage has a named Malli schema: authored input, parser output and transformed rows (§2b).
The proposed schemas refuse each malformed sample with a message naming the member (T18–T20). The existing
`:seon.program/row` validates all 47 scratch rows in 0.32 ms. Two weaknesses remain: it is open, and as an `:or`
it explains every alternative when one row fails.

Method. Scratch sources were written under `tmp/prove-parser/` (deleted afterwards). All evaluation ran in the
`default` JVM (pid 35446) through MCP `eval_clj` in JVM mode, inside the throwaway namespace `pp.probe`, which was
removed afterwards. Nothing was written to default's database: database proofs used `datahike.api/with` on a held
value. The kondo cache was a private `tmp/prove-parser/cache`. After the run, the checkout cache
`.clj-kondo/.cache/v1/clj` contained no `pp.*` entry (checked by listing).

## 1. Requirement table

| # | Requirement (spec row) | Verdict | Evidence (§2) | Smallest fix / owner, or counterexample | Stage validation (1.15): schema @ producer → refusal |
|---|---|---|---|---|---|
| 1a | `:seon/tasks #{…}` in `defn` → subject edge (7.2, 7.9) | **GAP** (in analysis, dropped by rows) | T1, T3, T5 | `var-row` (`src/seon/fn.clj:644`) and `namespace-row` (`:221`) read `(:seon/tasks metadata)` and emit a keyword-valued cardinality-many attribute; ~10 lines. Owner: ingestion slice 7.9 | S1 `:seon/tasks` `[:set {:min 1} :seon.task/id]` (proposed) inside `:seon.link/metadata`, at `var-row` → refusal names holder, key, value; S3 `:seon.program/tasks` on the row (T20) |
| 1b | `:seon/tasks` in `ns` attribute map → subject edge | **GAP**, same fix | T1 | the ns meta is present in `::analyzer/meta` of the namespace definition; `namespace-row` drops it | same S1/S3 schemas at `namespace-row` |
| 1c | `:seon/tasks` on a schema attribute (7.1) | **GAP, unproven here** | — | schemas are not analysed by kondo; the schema producer (`resources/seon/schemas/*.edn`, read by `seon.schema`) must emit the same attribute from property maps. Owner: schema registry. Not probed | same S1 schema at the schema-declaration reader (`seon.schema`); unprobed |
| 1d | `(def ^{:seon/tasks …} x 3)` | **PROVEN** in analysis | T5 (`a-constant`) | same fix as 1a | S1 at `var-row` (valid, T5) |
| 1e | `^:seon/tasks` shorthand | **FRAGILE, silent** | T1 (`caret-bare` → `#:seon{:tasks true}`) | the prototype refuses it `:tasks-not-a-set-of-qualified-keywords` (T5) | S1 refuses: `#:seon{:tasks ["invalid type"]}` (T18) |
| 1f | vector or single keyword instead of a set | **FRAGILE, silent today** | T5 (`alias-links`, `single-kw`) | same refusal | S1 refuses both (T18) |
| 1g | reader conditional `#?(:clj … :cljs …)` in `:seon/tasks` | **PROVEN for :clj; cljs branch silently absent** | T1 (`pp.beta/rc` → only `:pp.beta.task/clj-only`) | acceptable: the JVM index filters `:cljs` (`analyzer.clj` `jvm-entry?`). Rule: task ids never sit under a reader conditional; refuse in the canonical head | S1 cannot see the dropped branch; the canonical-head rule refuses a conditional |
| 1h | macro-generated `defn` carrying `:seon/tasks` in its template | **FRAGILE, loud but the edge is lost** | T1 (finding `Unresolved symbol: macro-made`), T8 | kondo sees no var. With `:lint-as` the var appears, but without the template's metadata, and call-site `^{:seon/tasks …}` survives (T8). Rule: tasks on generated vars are written at the call site. The template's task is triaged as orphaned (T6) | S2 `var-definition` validates what exists; a missing var is not a schema case (triage T6) |
| 2a | `(deftest ^{:seon/task :x.task/y} …)` → proof edge (7.3) | **GAP** (in analysis, dropped) | T1, T3 | `var-row` test branch, next to the existing `:seon.test/subject` (`fn.clj:458`, `:703`); ~4 lines | S1 `:seon.task/id` for `:seon/task` (or `:seon/tasks`, R1) at `var-row`; S3 `:seon.task/edge` (T20) |
| 2b | `(deftest name {:seon/task …} body)` attribute-map style | **FRAGILE, silent** | T1 (no meta), T4 (`:seon/task` appears only as a body keyword) | `deftest` has no attribute map; the map is a body expression. Refuse when a test's `:seon.fn/keywords` contains `:seon/task` or `:seon/tasks` (T4 proves it is detectable) | S2 keyword entries are valid; the refusal is a rule over `:seon.fn/keywords` at `var-row` |
| 2c | a deftest's id absent from task EDN | **PROVEN loud** (prototype) | T6 `:dangling` | 7.5 refusal at the gate (7.13) | S3 dangling is a relation, not a shape: a named check at the gate (7.13) |
| 3a | `:seon/must-read` / `:seon/see-also` symbols (5a.6, 7.9) | **PROVEN in analysis; untyped in rows** | T2, T5 | rows already carry them as `:seon.fn/references` (T3: `#{seon.db/pull seon.db/q seon.db/transact! …}`), but that set cannot say must-read from body reference. Emit typed edges using `capability-symbol` ∘ `seed-symbol` (`fn.clj:590`, `:480`), which the prototype reuses unchanged | S1 `:seon.link/code-target` (proposed) at `var-row`; S3 typed edge |
| 3b | alias inside a link (`db/q`, `` `db/pull ``) | **PROVEN with alias resolution** | T5 (`alias-links` → `seon.db/q`, `seon.db/pull`) | kondo's meta is raw sexpr (`db/q`, `(quote db/pull)`); `seed-symbol` resolves it through the namespace's aliases | S1 accepts `(quote db/pull)`, refuses bare `db/q` (T18) |
| 3c | bare symbol in `defn` metadata | **FRAGILE, runtime differs from index** | T9 | at load, Clojure evaluates var metadata: `#{clojure.core/inc}` becomes a function object `clojure.core$inc`; an unloaded namespace throws `no.such.ns`. kondo records the bare symbol. Refuse bare symbols in link metadata, as 4.3 does for proofs | S1 refuses the bare symbol: `#:seon{:see-also ["invalid type"]}` on `reads-db` (T18) |
| 3d | bare qualified symbol in the `ns` attribute map | **FRAGILE, loud and inconsistent** | T1 finding `4:21 Unresolved namespace seon.db` (blocking); T9b: no finding when the namespace is not required at all | quoting (`'seon.db/q` or `` `seon.db/q ``) is clean (T3, T9b). Ns-level link symbols have no holder, so today they land in the file row's `:seon.fn/unresolved-references #{seon.db/q}` (T4): misfiled | S1 at `namespace-row`, same schema |
| 3e | keyword and path targets (`:seon.db/db`, `"docs/…md"`) | **PROVEN** | T5 | typed as `:schema` / `:path` edges | S1 `:seon.link/code-target` branches |
| 4a | task EDN read without evaluation (1.6) | **PROVEN** | T7 | edamame reads `'pp.alpha/reads-db` as `(quote pp.alpha/reads-db)`, and one unwrap yields the symbol. `clojure.edn` returns ONE Symbol whose name is `'pp.alpha/reads-db` (silent misread, confirmed). `#=` refuses. `(System/exit 1)` is data | S1 `:seon.task/entry` closed map (proposed) at the task EDN reader: misspelled key → `:intnet ["disallowed key"]`, string id → `["should be a qualified keyword"]` (T18) |
| 4b | reconcile by id | **PROVEN** (prototype) | T6 | duplicate ids, dangling ids (misspelled `:pp.alpha.task/misspeled`, test-only `:typo-not-in-edn`, `pp.beta`'s id with no EDN), orphan triage, and raised/specified derived from edge presence | S1 per entry; duplicate ids need a whole-file check (Malli has no distinct-by), named `:duplicate-task-id` |
| 4c | misspelling that collides with another real id | **FRAGILE by nature** | — | undetectable by any parser; mitigated because ids read as problems (7.2) | none possible |
| 5a | `:keys [ns/k]`, `:ns/keys [k]`, `::alias/k` → input keys | **PROVEN** in kondo keyword analysis and Malli | T2 (`:keys-destructuring true`, `::db/connection` → `seon.db/connection` with `:alias db`), T10 | kondo gives the key set; `malli.destructure/parse` gives the schema (0.1–0.9 ms per arglist) | S2 `:seon.fn.analyzer/keyword` (proposed), at `analyze` (T19) |
| 5b | `:or` makes a key optional | **GAP (known)** | T10 (`:seon.agent/id` stays required) | derived-contracts §1 normalization 2 | S3 derived contract compiled against the projection registry (T20) |
| 5c | nested destructuring | **PROVEN as designed** | T2, T10 | outer `:seon.y/b` is a plain key (not `keys-destructuring`); inner `:seon.x/a` is marked. Malli drops inner and makes unqualified `:c` required `:any` (normalization 1) | S2 keyword; S3 contract compile |
| 5d | `::nope/k` with no require | **FRAGILE: kondo loud, reader silent** | T1 (blocking `Unresolved namespace nope`), T10 (`read-source` → `:nope/k`) | `signature/read-source`'s resolver returns the alias itself (`(get aliases sym sym)`, `src/seon/fn/signature.cljc:25`), so an unknown alias becomes a plausible keyword. Resolve to a refusal instead. Owner: `seon.fn.signature` | S2 refuses the kondo entry `:ns :clj-kondo/unknown-namespace` → `["should be a symbol"]` (T19) |
| 5e | unregistered key | **PROVEN loud** | T11 (`:malli.core/invalid-schema {:form :pp.nowhere/key}`) | — | S3 compile refuses `{:schema :pp.nowhere/key}` (T20) |
| 5f | `:ns/keys` pseudo key stored as a used keyword | **DEFECT (out of scope)** | T3 (`pp.alpha/reads-db` `:seon.fn/keywords` contains `:seon.agent/keys`) | `keywords-by-holder` should drop kondo keyword entries naming a destructuring directive (`keys`, `syms`, `strs` in a map-binding key position). Recorded in §4 | S3 would need a key-kind rule; shape alone passes |
| 5g | naked reference entries `[:map :seon.db/db]` storable | **GAP (derived-contracts S0), reconfirmed at HEAD** | T11b (`nth not supported on this type: Keyword`) | `schema_shape.clj:28-36` | S3 `:seon.fn/spec` store codec |
| 6a | return map keys "exported per arity" by kondo (1.12) | **GAP: not exported** | T12 | the fork computes `:arities {n {:ret …}}` (`analyzer.clj:898`, `:922`) and passes it to `analysis/reg-var!`, whose `select-some` (`analysis.clj:92`) drops it; it survives only in the cache transit. Fix: add `:arities` to that `select-some` behind an analysis option, and add `:arities` to Seon's `var-definition` `present-values` (`src/seon/fn/analyzer.clj`); ~3 lines. Owner: kondo fork + analyzer | S2 `:seon.fn.analyzer/arities` (proposed) at `analyze`, once exported (T19: six cache arities valid; malformed `:val` key refused) |
| 6b | coverage of kondo return typing | **GAP: literal-map tails only** | T12, T13 | typed: `{…}` literal (closed; `:open true` when a key is computed, `types.clj:372-380`). Untyped: `let`, `do`, `when`, `if`, `assoc`, `merge`, `cond->`, `->`, `into`, a call to a function returning a map (a deferred `{:call …}` tag that the cache does not keep), and the second arity of `multi`. derived-contracts P10 counts 28 literal tails among 196 map-input functions. §3 R4 chooses the owner | S2 same schema; coverage is not a shape |
| 7 | test seeds as edges (7.9b) | **PROVEN (installed)** | T3 | `:seon.test/seed [{:writes #{:seon.agent/id} :ordinal 0} {:function pp.alpha-test/seed-fn :ordinal 1} {:ordinal 2}]`; the literal value is validated against its schema; an uncontracted seed function refuses loudly (T3a) | S1 `:seon.test/authored-seed` (proposed; today hand-coded in `seed-occurrences`) at `var-row`; S3 existing `:seon.test.seed/occurrence` (T18) |
| 8a | task → subjects and proofs | **PROVEN** on `d/with` | T14 | value join on a keyword attribute | S3 `:seon.task/edge`; queries read validated rows |
| 8b | function → open tasks | **PROVEN** | T14 | "open" = the id is still listed in its source (7.6 settles by removing it), so the edge *is* openness; dangling is a `not` join | S3, same |
| 8c | schema → functions that read or return it | **PROVEN, split query 180 ms** | T15 | `my.program/reads-key` merges input/output/guard into one `:contract-refs` group; the split exists as data (`:seon.fn.arity/input-refs` vs `output-refs`). FRAGILE: a function whose contract names a wrapper map (`my.program/reads-key` → `:my.program/key-request`) lists no inner key; the inner keys are reachable through binding entries (T16, 6.6 ms) only for contracted functions | S3 existing `:seon.fn.arity/input-refs` / `output-refs` |
| 8d | schema ↔ render referrers | **PROVEN (private)** | T15 | `my.program/render-referrers` (`src/my/program.clj:228`, 17.7 ms): `seon.render.ns/function-ai` → `#{{:seon.schema/key :seon.fn/fn :seon.schema/property :seon.render/ai}}` | S3 existing schema properties |
| 9 | positional vs map contracts | **PROVEN** | T16b | positional `seon.fn.signature/declaration-metadata` → arity `"3"`, no refs (primitive inputs); map `my.program/reads-key` → arity `"1"`, input-refs `[:my.program/key-request]` | S3 existing `:seon.fn.arity` rows |
| 10a | one literal token in every place (1.9) | **PROVEN when written fully qualified** | T17 (`rg` finds EDN, `defn`, `deftest`) | — | S1 `:seon.task/id` |
| 10b | `::t/merge-adds-and-retracts` via `:as-alias` | **FRAGILE, silent to grep** | T17 (rg misses the line; the index resolves it: the fork commit 57252e07 resolves attribute-map keywords) | refuse `::` in link metadata; kondo marks it `:auto-resolved true :alias t` (T17b), so the refusal is one filter | S2 keyword `:auto-resolved true` → canonical-head refusal |
| 10c | rendered context | **PROVEN for ids; the key folds** | T17 | `(pr-str {:seon/tasks #{…}})` → `#:seon{:tasks #{:pp.alpha.task/…}}`: the id stays literal, but `rg :seon/tasks` over rendered text misses the key. Print link keys unfolded, or grep ids, never keys | render; not a validation stage |

## 2. REPL transcripts (exact forms abridged only where marked …; every output is verbatim)

Scratch sources (the `clean/` copy is the one that indexes; the first copy also held `(defn unaliased [{:keys [::nope/k]}] k)`,
`(defthing macro-made 1)` and a bare `seon.db/q` in the `ns` map):

```clojure
(ns pp.alpha "Scratch namespace for the parser proof."
  {:seon/tasks #{:pp.alpha.task/ns-level} :seon/must-read ['seon.db/q]}
  (:require [seon.db :as db] [clojure.string :as str]))
(defmacro defthing [nm & body] `(defn ~nm {:seon/tasks #{:pp.alpha.task/macro-made}} [] ~@body))
(defn reads-db "Destructures three spellings of qualified keys."
  {:seon/tasks #{:pp.alpha.task/merge-adds-and-retracts :pp.alpha.task/misspeled}
   :seon/must-read [`seon.db/q 'seon.db/pull]
   :seon/see-also #{seon.db/transact! :seon.db/db}}
  [{:keys [seon.db/db ::db/connection] :seon.agent/keys [id] :or {id nil} :as unit}]
  {:seon.render/value (str db connection unit) :seon.agent/id id})
(defn ^{:seon/tasks #{:pp.alpha.task/caret-meta}} caret [x] x)
(defn ^:seon/tasks caret-bare [x] x)
(defn nested [{{:keys [seon.x/a]} :seon.y/b c :c}] [a c])
(defn multi ([{:keys [seon.db/db]}] {:seon.agent/id db})
            ([{:keys [seon.db/db]} x] (into {} [[:seon.agent/id (str db x)]])))
(defn built-by-into [xs] (into {:seon.render/value 1} xs))
(defn returns-assoc [{:keys [seon.db/db] :as m}] (assoc m :seon.render/value db))
(defn branches [x] (if x {:seon.render/value 1} {:seon.render/html 2}))
(defn unregistered-out [] {:pp.nowhere/key 1})
(defn calls-other [] (reads-db {}))
(defn alias-links {:seon/must-read [db/q `db/pull :seon.db/db "docs/seon/architecture/architecture.md"]
                   :seon/tasks [:pp.alpha.task/vector-not-set]} [x] x)
(defn single-kw {:seon/tasks :pp.alpha.task/caret-meta} [x] x)
(def ^{:seon/tasks #{:pp.alpha.task/ns-level}} a-constant 3)

;; pp/beta.cljc
(ns pp.beta)
(defn rc {:seon/tasks #?(:clj #{:pp.beta.task/clj-only} :cljs #{:pp.beta.task/cljs-only})}
  [{:keys [#?(:clj seon.db/db :cljs seon.db/connection)]}]
  #?(:clj {:seon.render/value db} :cljs {:seon.render/html 2}))

;; pp/alpha_test.clj
(ns pp.alpha-test (:require [clojure.test :refer [deftest is]] [pp.alpha :as a]))
(defn seed-fn "Seed function." {:malli/schema [:=> [:cat :seon.test.seed/input] :seon.test.seed/value]}
  [_input] [{:seon.agent/id "seeded"}])
(deftest ^{:seon/task :pp.alpha.task/merge-adds-and-retracts
           :seon.test/seed [[{:seon.agent/id "x"}] pp.alpha-test/seed-fn (fn [_input] [])]}
  proves-merge (is (= 1 (a/caret 1))))
(deftest ^{:seon/task :pp.alpha.task/typo-not-in-edn} proves-typo (is true))
(deftest proves-attr-map-style {:seon/task :pp.alpha.task/caret-meta} (is true))
(deftest ^{:seon/tasks #{:pp.alpha.task/caret-meta :pp.alpha.task/ns-level}} proves-plural (is true))

;; resources/seon/tasks/pp.alpha.edn
[#:seon.task{:id :pp.alpha.task/merge-adds-and-retracts :intent "Merge adds and retracts program rows."
             :must-read ['pp.alpha/reads-db "docs/x.md"] :needs #{:pp.alpha.task/ns-level}}
 #:seon.task{:id :pp.alpha.task/ns-level :intent "Namespace-level task."}
 #:seon.task{:id :pp.alpha.task/caret-meta :intent "Caret metadata task."}
 #:seon.task{:id :pp.alpha.task/macro-made :intent "Macro task."}
 #:seon.task{:id :pp.alpha.task/ns-level :intent "Duplicate id."}]
```

**T1. Raw analysis, first copy** (200 ms cold for three files; 12 ms warm for one):

```clojure
(an/analyze {:seon.fn.analyzer/sources (into {} (map (juxt identity slurp)) pp-files)
             :seon.fn.analyzer/cache-root (str pp-root "/cache")})
;; findings
{:row 4, :col 21, :type :unresolved-namespace, :message "Unresolved namespace seon.db. Are you missing a require?", :level :error}
{:row 29, :col 26, :type :unresolved-namespace, :message "Unresolved namespace nope. Are you missing a require?", :level :error}
{:row 45, :col 11, :type :unresolved-symbol, :message "Unresolved symbol: macro-made", :level :error}
;; namespace meta
{:name pp.alpha, :meta #:seon{:must-read [seon.db/q], :tasks #{:pp.alpha.task/ns-level}}}
{:name pp.beta, :lang :clj}
;; var meta (selected)
reads-db   #:seon{:see-also #{:seon.db/db seon.db/transact!}, :must-read ['seon.db/q 'seon.db/pull],
                  :tasks #{:pp.alpha.task/merge-adds-and-retracts :pp.alpha.task/misspeled}}
caret      #:seon{:tasks #{:pp.alpha.task/caret-meta}}
caret-bare #:seon{:tasks true}
pp.beta/rc #:seon{:tasks #{:pp.beta.task/clj-only}}, :lang :clj
proves-merge {:seon/task :pp.alpha.task/merge-adds-and-retracts,
              :seon.test/seed [[#:seon.agent{:id "x"}] pp.alpha-test/seed-fn (fn [_input] [])]}, :test true
proves-typo  #:seon{:task :pp.alpha.task/typo-not-in-edn}
proves-attr-map-style  ;; no :meta at all
proves-plural #:seon{:tasks #{:pp.alpha.task/ns-level :pp.alpha.task/caret-meta}}
;; multi :arglist-strs ["[{:keys [seon.db/db]}]" "[{:keys [seon.db/db]} x]"], :fixed-arities [1 2]
;; no var for macro-made
```

Row production over the first copy refuses loudly (`seon.fn/analyze-rows`, 19.6 ms):
`"Static program analysis found blocking errors.\n…alpha.clj:4:21 :unresolved-namespace …\n…:29:26 … nope …\n…:45:11 :unresolved-symbol Unresolved symbol: macro-made"`, `:seon.fn/index-refused true`.

**T2. Keyword analysis** (selected; `:from-var` absent means the keyword sits in metadata):

```clojure
{:row 15, :col 18, :ns pp.alpha.task, :name "merge-adds-and-retracts"}          ; attr-map, no :from-var
{:row 18, :col 12, :ns seon.db, :name "db", :from-var reads-db, :keys-destructuring true}
{:row 18, :col 23, :ns seon.db, :name "connection", :from-var reads-db, :alias db, :auto-resolved true, :keys-destructuring true}
{:row 18, :col 40, :ns seon.agent, :name "keys", :from-var reads-db}             ; the :seon.agent/keys directive
{:row 18, :col 58, :ns seon.agent, :name "id", :from-var reads-db, :keys-destructuring true}
{:row 18, :col 62, :name "or", :from-var reads-db}
{:row 26, :col 13, :ns seon.x, :name "a", :from-var nested, :keys-destructuring true}
{:row 26, :col 24, :ns seon.y, :name "b", :from-var nested}                      ; outer key: not marked
{:row 29, :col 26, :ns :clj-kondo/unknown-namespace, :name "k", :from-var unaliased, :auto-resolved true, :keys-destructuring true}
{:row 11, :col 29, :ns pp.alpha.task, :name "macro-made", :from-var defthing}    ; attributed to the macro
```

**T3. Rows from the clean copy** (`seon.fn/analyze-rows` with `:seon.fn/root "tmp/prove-parser/clean"`,
`:seon.fn/roots ["src" "test"]`, default's carried projection and the scratch cache: **41 rows in 39.0 ms**).
`:any-task-key nil`: no row carries `:seon/tasks`, `:seon/task`, `:seon/must-read` or `:seon/see-also`.

```clojure
pp.alpha  {:seon.ns/name pp.alpha, :seon.ns/doc "…", :seon.ns/requires #{seon.db clojure.string},
           :seon.ns/aliases #{…}, …}                                   ; ns tasks and must-read dropped
pp.alpha/reads-db
 {:seon.fn/keywords #{:seon.agent/id :seon.agent/keys :seon.db/connection :seon.db/db :seon.render/value},
  :seon.fn/references #{seon.db/pull seon.db/q clojure.core/defn seon.db/transact!},
  :seon.fn/calls #{clojure.core/str}, :seon.fn/arglists "([{:keys [seon.db/db ::db/connection] …}])", …}
pp.beta/rc {:seon.fn/keywords #{:seon.db/db :seon.render/value}, …}
pp.alpha-test/proves-merge
 {:seon.fn/calls #{clojure.core/= pp.alpha/caret},
  :seon.fn/references #{pp.alpha-test/seed-fn clojure.core/fn clojure.test/is clojure.test/deftest},
  :seon.test/seed [#:seon.test.seed{:writes #{:seon.agent/id}, :ordinal 0}
                   #:seon.test.seed{:function pp.alpha-test/seed-fn, :ordinal 1}
                   #:seon.test.seed{:ordinal 2}], …}
```

T3a: before `seed-fn` had its contract, rows refused (73.5 ms):
`"Seed entry 1 of pp.alpha-test/proves-merge is refused: uncontracted-function."`.

**T4. The silent losses are detectable:**

```clojure
proves-attr-map-style #:seon.fn{:keywords #{:pp.alpha.task/caret-meta :seon/task}, :references #{clojure.test/is clojure.test/deftest}}
defthing              #:seon.fn{:keywords #{:pp.alpha.task/macro-made :seon/tasks}, …}
file row src/pp/alpha.clj  :seon.fn/unresolved-references #{seon.db/q}     ; the ns-level must-read, misfiled
```

**T5. Prototype edge producer** (a throwaway `pp.probe/pp-task-edges`, about 25 lines over `::analyzer/namespace-definitions`
and `::analyzer/var-definitions` meta. It reuses `seon.fn/capability-symbol` for unquoting and `seon.fn/seed-symbol`
for alias resolution, with aliases taken from `::analyzer/namespace-usages`). **2.11 ms**:

```clojure
{:holder pp.alpha, :relation :subject, :task :pp.alpha.task/ns-level}
{:holder pp.alpha, :relation :seon/must-read, :symbol seon.db/q}
{:holder pp.alpha/reads-db, :relation :subject, :task :pp.alpha.task/merge-adds-and-retracts}
{:holder pp.alpha/reads-db, :relation :subject, :task :pp.alpha.task/misspeled}
{:holder pp.alpha/reads-db, :relation :seon/must-read, :symbol seon.db/q}
{:holder pp.alpha/reads-db, :relation :seon/must-read, :symbol seon.db/pull}
{:holder pp.alpha/reads-db, :relation :seon/see-also, :schema :seon.db/db}
{:holder pp.alpha/reads-db, :relation :seon/see-also, :symbol seon.db/transact!}
{:holder pp.alpha/caret, :relation :subject, :task :pp.alpha.task/caret-meta}
{:holder pp.alpha/caret-bare, :refused :tasks-not-a-set-of-qualified-keywords, :key :seon/tasks, :value true}
{:holder pp.alpha/alias-links, :refused :tasks-not-a-set-of-qualified-keywords, :key :seon/tasks, :value [:pp.alpha.task/vector-not-set]}
{:holder pp.alpha/alias-links, :relation :seon/must-read, :symbol seon.db/q}        ; written db/q
{:holder pp.alpha/alias-links, :relation :seon/must-read, :symbol seon.db/pull}     ; written `db/pull
{:holder pp.alpha/alias-links, :relation :seon/must-read, :schema :seon.db/db}
{:holder pp.alpha/alias-links, :relation :seon/must-read, :path "docs/seon/architecture/architecture.md"}
{:holder pp.alpha/single-kw, :refused :tasks-not-a-set-of-qualified-keywords, :key :seon/tasks, :value :pp.alpha.task/caret-meta}
{:holder pp.alpha/a-constant, :relation :subject, :task :pp.alpha.task/ns-level}
{:holder pp.beta/rc, :relation :subject, :task :pp.beta.task/clj-only}
{:holder pp.alpha-test/proves-merge, :relation :proof, :task :pp.alpha.task/merge-adds-and-retracts}
{:holder pp.alpha-test/proves-typo, :relation :proof, :task :pp.alpha.task/typo-not-in-edn}
{:holder pp.alpha-test/proves-plural, :relation :proof, :task :pp.alpha.task/ns-level}
{:holder pp.alpha-test/proves-plural, :relation :proof, :task :pp.alpha.task/caret-meta}
```

Shape of kondo's raw meta values, showing why unquoting and alias resolution are both needed:
`alias-links :must-read [[db/q Symbol] (:list [quote Symbol] [db/pull Symbol]) [:seon.db/db Keyword] ["docs/…" String]]`.
kondo's var usages resolve the same symbols (`[[seon.db q db] [seon.db pull db]]`), which confirms what the alias
resolution computes.

**T6. Reconcile with task EDN** (`pp-reconcile`, 1.11 ms):

```clojure
{:duplicate-ids [{:refused :duplicate-task-id, :task :pp.alpha.task/ns-level, :count 2}],
 :dangling [{:refused :dangling-task-id, :task :pp.alpha.task/misspeled, :holders [pp.alpha/reads-db]}
            {:refused :dangling-task-id, :task :pp.beta.task/clj-only, :holders [pp.beta/rc]}
            {:refused :dangling-task-id, :task :pp.alpha.task/typo-not-in-edn, :holders [pp.alpha-test/proves-typo]}],
 :triage [{:triage :no-subject-no-proof, :task :pp.alpha.task/macro-made}],     ; the macro's template task, orphaned
 :raised [],
 :specified [:pp.alpha.task/caret-meta :pp.alpha.task/merge-adds-and-retracts :pp.alpha.task/ns-level],
 :task-must-read [[:pp.alpha.task/merge-adds-and-retracts [pp.alpha/reads-db "docs/x.md"]]]}
```

**T7. EDN readers:**

```clojure
(first (e/parse-string-all pp-edn-src {:quote true :auto-resolve {:current 'pp.alpha}}))
{:edamame-must-read ['pp.alpha/reads-db "docs/x.md"], :edamame-classes [clojure.lang.PersistentList java.lang.String],
 :clojure-edn [['pp.alpha/reads-db "docs/x.md"] [clojure.lang.Symbol java.lang.String]],   ; one symbol named 'pp.alpha/reads-db
 :eval? (System/exit 1), :read-eval "Read-eval not allowed. Use the `:read-eval` option"}
```

**T8. Macro-generated vars** (plain `clj-kondo.core/run!` on stdin):

```clojure
;; :lint-as {pp.eps/defthing clojure.core/def}
[[defthing nil clojure.core/defmacro] [macro-made nil pp.eps/defthing]
 [with-site-meta #:seon{:tasks #{:pp.eps.task/call-site}} pp.eps/defthing]]
;; :hooks {:macroexpand …} with the macro not on a hook path: no var, a WARNING on stderr, no finding
```

**T9. Runtime value of link metadata:**

```clojure
(defn pp-f {:seon/see-also #{clojure.core/inc} :seon/must-read [`inc 'clojure.core/dec] :seon/tasks #{:pp.alpha.task/x}} [x] x)
;; :must-read [clojure.core/inc Symbol] [clojure.core/dec Symbol]
;; :see-also  [#object clojure.core$inc]                     ; the bare symbol evaluated to the function
(eval '(defn pp-g {:seon/see-also #{no.such.ns/f}} [x] x))   ; throws "no.such.ns"
```

T9b. With plain kondo, `(ns pp.zeta {:seon/must-read [`seon.db/q]})` and `(defn f {:seon/see-also #{seon.render/value}} …)`
(seon.render not required) produce **no finding**. In T1, the same bare form in the `ns` map raised a blocking error
because `seon.db` was required further down. Whether kondo complains depends on context.

**T10. Destructuring through the existing reader and Malli** (`signature/read-source` with aliases `{db seon.db}`;
`md/parse` with `::md/inline-schemas false ::md/sequential-maps false ::md/required-keys true`):

```clojure
"reads-db"   [0.24 [:cat [:map :seon.db/db :seon.db/connection :seon.agent/id]]]   ; :or ignored → id required
"nested"     [0.10 [:cat [:map :seon.y/b [:c :any]]]]
"unaliased"  [0.31 [:cat [:map :nope/k]]]                                         ; silent: alias fell back to itself
"multi-1"    [0.32 [:cat [:map :seon.db/db]]]
"multi-2"    [0.15 [:cat [:map :seon.db/db] :any]]
"rc-cljc"    [0.69 [:cat [:map :seon.db/db]]]
"kw-in-keys" [0.90 [:cat [:map :seon.db/db]]]
"rename"     [0.37 [:cat [:map :seon.db/db]]]
```

**T11. Registry compile:** `[:map :seon.db/db :seon.db/connection :seon.agent/id]` → ok, 0.07 ms.
`[:map :nope/k]`, `[:map :seon.y/b]` and `[:map :pp.nowhere/key]` → `:malli.core/invalid-schema {:form <key>}`.
T11b: `(#'seon.fn.schema-shape/canonical-form [:map :seon.db/db])` → `"nth not supported on this type: Keyword"`,
while `[:map [:seon.db/db :seon.db/db]]` → itself.

**T12. Return types live only in the kondo cache** (read with transit from `tmp/prove-parser/cache/v1/clj/pp.alpha.transit.json`):

```clojure
pp.alpha/reads-db         {1 {:ret {:type :map, :val {:seon.render/value {}, :seon.agent/id {}}}, :arglist-str "…"}}
pp.alpha/multi            {1 {:ret {:type :map, :val #:seon.agent{:id {}}}, …}}      ; arity 2 (into) absent
pp.alpha/unregistered-out {0 {:ret {:type :map, :val #:pp.nowhere{:key {:tag :pos-int}}}, …}}
pp.alpha/nested           {1 {:ret :vector, …}}
pp.alpha/branches {}  pp.alpha/returns-assoc {}  pp.alpha/built-by-into {}  pp.alpha/calls-other {}
```

None of this appears in `::analyzer/var-definitions` (T1 keys).

**T13. Return typing coverage** (`pp/gamma.clj`, analysed in 12.0 ms):

```clojure
{lit          {0 {:type :map, :val #:seon.a{:x {:tag :pos-int}, :y {:tag :string}}}},
 via-computed {1 {:type :map, :val #:seon.a{:x {:tag :pos-int}}, :open true}},
 via-let {}, via-when {}, via-if {}, via-assoc-lit {}, via-assoc-arg {}, via-merge {}, via-cond-> {},
 via-call {}, via-into {}, via-thread {}, via-do {}, via-throw {}}
```

**T14. Reverse lookups on a held value** (`datahike.api/with` on default's db value: 5 attributes in 36.6 ms,
then 19 edge/task facts in 1.3 ms; nothing committed):

```clojure
:task->subjects     [1.05 [pp.alpha pp.alpha/a-constant]]                  ; :pp.alpha.task/ns-level
:task->proofs       [0.26 [pp.alpha-test/proves-merge]]
:fn->open-tasks     [0.57 [:pp.alpha.task/merge-adds-and-retracts]]      ; joined to an existing task entity
:fn->dangling-tasks [0.61 [:pp.alpha.task/misspeled]]                    ; (not [_ :pp.task/id ?t])
:must-read-referrers-of seon.db/q [0.23 [pp.alpha/reads-db pp.alpha/alias-links]]
```

**T15. Existing reverse lookups on default:**
`(my.program/reads-key {:seon.db/db pp-db :seon.schema/key :seon.fn/sym})` → 34 contract-refs, 3 writes-of, 258 mentions in 34.3 ms.
`(#'my.program/render-referrers pp-db 'seon.render.ns/function-ai)` → `#{#:seon.schema{:key :seon.fn/fn, :property :seon.render/ai}}` in 17.7 ms.
The input/output split query → `#:seon.fn.arity{:input-refs 18, :output-refs 18}` in 180.4 ms.

**T16. Destructured-by through binding entries** (6.6 ms):
`:seon.schema/key` → `my.program/reads-key`, `seon.schema/render-contract-refusal!`, `seon.schema/unresolved-reference-refusal`.
T16b: `seon.fn.signature/declaration-metadata` → `{:arity "3", :input-refs [], :output-refs []}`;
`my.program/reads-key` → `{:arity "1", :input-refs [:my.program/key-request], :output-refs [:seon.program/breakage :seon.program/not-found-error :seon.program/read-refused-error]}`.

**T17. The grep route:**

```text
$ rg -n ":pp.alpha.task/merge-adds-and-retracts" tmp/prove-parser
src/pp/alpha.clj:15   test/pp/alpha_test.clj:10   resources/seon/tasks/pp.alpha.edn:1
$ rg -n "merge-adds-and-retracts" tmp/prove-parser/clean
… also clean/src/pp/delta.clj:4:(defn aliased-task {:seon/tasks #{::t/merge-adds-and-retracts}} [x] x)
```

Analysing `delta.clj` (`(:require [pp.alpha.task :as-alias t])`) gives meta `#:seon{:tasks #{:pp.alpha.task/merge-adds-and-retracts}}`,
no finding, and the edge `{:holder pp.delta/aliased-task :relation :subject …}`. T17b: its keyword entry is
`{:alias t, :auto-resolved true, :ns pp.alpha.task, :row 4, :col 35}`.
Printing: `(pr-str {:seon/tasks #{…}})` → `"#:seon{:tasks #{:pp.alpha.task/merge-adds-and-retracts}}"`.

## 2b. Every stage validates against a named schema (row 1.15)

Existing schemas were found by `rg resources/seon/schemas` and by looking up keys in default's projection registry.
None of the proposed keys below exists yet (`:preexisting {}`). The existing stage schemas are:
- `:seon.test.seed/occurrence` and `:seon.test.seed/refused` (rows of seeds);
- `:seon.program/row` and `:seon.program/rows` (all program rows);
- the analyzer's own contract on `seon.fn.analyzer/analyze`, which types every entry vector as `[:vector :map]`, too
  weak to name a member.

The proposed schemas were evaluated in a composite registry (default's projection, then the proposals), never
registered:

```clojure
{:seon.task/id    [:qualified-keyword]
 :seon.task/entry [:map {:closed true} [:seon.task/id :seon.task/id] [:seon.task/intent [:string {:min 1}]]
                   [:seon.task/must-read {:optional true} [:vector :seon.link/authored-target]]
                   [:seon.task/needs {:optional true} [:set :seon.task/id]]]
 :seon.task/file  [:vector :seon.task/entry]
 :seon.link/authored-target [:or :qualified-symbol [:cat [:= 'quote] :symbol] :qualified-keyword [:string {:min 1}]]  ; EDN
 :seon.link/code-target     [:or [:cat [:= 'quote] :qualified-symbol] :qualified-keyword [:string {:min 1}]]           ; code: quoted only (T9)
 :seon/tasks      [:set {:min 1} :seon.task/id]
 :seon/must-read  [:vector :seon.link/code-target]
 :seon/see-also   [:or [:set :seon.link/code-target] [:vector :seon.link/code-target]]
 :seon.link/metadata [:map [:seon/tasks {:optional true} :seon/tasks]
                      [:seon/must-read {:optional true} :seon/must-read] [:seon/see-also {:optional true} :seon/see-also]]
 :seon.test/authored-seed [:vector [:or :qualified-symbol :map [:vector :map]
                                    [:cat [:enum 'fn 'clojure.core/fn] [:vector :any] [:* :any]]]]
 :seon.fn.analyzer/var-definition [:map [::filename :string] [::row pos-int?] [::col pos-int?] [::ns :symbol] [::name :symbol]
                                   [::defined-by {:optional true} :qualified-symbol] [::meta {:optional true} :map]
                                   [::arglist-strs {:optional true} [:vector :string]]
                                   [::fixed-arities {:optional true} [:vector nat-int?]] [::test {:optional true} :boolean]]
 :seon.fn.analyzer/keyword [:map [::filename :string] [::row pos-int?] [::col pos-int?] [::name :string]
                            [::ns {:optional true} :symbol] [::from-var {:optional true} :symbol] [::alias {:optional true} :symbol]
                            [::auto-resolved {:optional true} :boolean] [::keys-destructuring {:optional true} :boolean]]
 :seon.fn.analyzer/tag      [:or :keyword [:map [:type [:= :map]] [:val [:map-of :keyword [:map [:tag {:optional true} :any]]]]
                                              [:open {:optional true} :boolean]] [:map [:call :map]]]
 :seon.fn.analyzer/arities  [:map-of nat-int? [:map [:ret {:optional true} :seon.fn.analyzer/tag] [:arglist-str {:optional true} :string]]]
 :seon.program/tasks [:set :seon.task/id]
 :seon.task/edge [:or [:map {:closed true} [:holder :qualified-symbol] [:relation [:enum :subject :proof]] [:task :seon.task/id]]
                  [:map {:closed true} [:holder :symbol] [:relation [:= :subject]] [:task :seon.task/id]]]}
```

`pp-v` is `(fn [k x] (let [s (m/schema k {:registry pp-reg})] (if (m/validate s x) :valid (me/humanize (m/explain s x)))))`.

**T18. Stage 1, authored input** (5.7 ms for all ten checks):

```clojure
:task-file-good       :valid                                      ; the five entries of pp.alpha.edn
:task-misspelled-key  #:seon.task{:intent ["missing required key"], :intnet ["disallowed key"]}
:task-id-string       #:seon.task{:id ["should be a qualified keyword"]}
:task-id-unqualified  #:seon.task{:id ["should be a qualified keyword"]}
:caret-bare-meta      #:seon{:tasks ["invalid type"]}             ; ^:seon/tasks → true
:single-kw-meta       #:seon{:tasks ["invalid type"]}
:reads-db             #:seon{:see-also ["invalid type"]}          ; bare seon.db/transact! (T9 hazard) refused
:alias-links          #:seon{:must-read [["invalid type" "should be a qualified keyword" "should be a string"]],
                             :tasks ["invalid type"]}             ; bare db/q; vector of tasks
:seed-good            :valid                                      ; [[{:seon.agent/id "x"}] pp.alpha-test/seed-fn (fn [_input] [])]
:seed-bad             [["should be a qualified symbol" "invalid type" …] …]   ; [42 unqualified]
```

With the looser `:seon.link/authored-target` in code, `reads-db` validated even though a bare symbol evaluates to a
function at runtime. That is why code and EDN need two target schemas.

**T19. Stage 2, parser output** (0.60 ms for 21 var-definitions and 85 keyword entries):

```clojure
:var-defs-invalid []   :keywords-invalid []
:var-def-missing-name  #:seon.fn.analyzer{:name ["missing required key"]}
:stage2-unknown-namespace-keyword #:seon.fn.analyzer{:ns ["should be a symbol"]}   ; kondo's :clj-kondo/unknown-namespace sentinel
:arities-valid [[via-computed :valid] [lit :valid] [unregistered-out :valid] [nested :valid] [reads-db :valid] [multi :valid]]
:arities-malformed {1 {:ret ["should be a keyword"]}}                                ; a string key in :val
```

kondo writes the keyword sentinel `:clj-kondo/unknown-namespace` into a slot that otherwise holds a symbol. The schema
turns that silent mixed type into a named refusal at `analyze`, before `keywords-by-holder` stores a nonsense keyword.

**T20. Stage 3, transformed rows** (all 47 rows of the clean copy against the existing `:seon.program/row`: 0 invalid, 0.32 ms):

```clojure
:fn-row-sym-as-string  {:seon.fn/sym ["should be a qualified symbol"], :seon.test/source ["missing required key"],
                        :seon.lint/file ["missing required key"], … 18 members}      ; every :or alternative explained
:misspelled-attr-passes-row-schema true                                          ; the row schema is open
:tasks-attr-good :valid   :tasks-attr-bad #{["should be a qualified keyword"]}
:edge-good :valid         :edge-ns-proof-bad {:holder ["should be a qualified symbol"], :relation ["should be :subject"]}
:derived-contract :valid  ; [:=> [:cat [:map :seon.db/db :seon.db/connection [:seon.agent/id {:optional true}]]] [:map :seon.render/value :seon.agent/id]]
:derived-contract-unregistered [:malli.core/invalid-schema {:schema :pp.nowhere/key, :form :pp.nowhere/key}]
```

**Where each validation belongs** (at its producer; each refusal names stage, member and offending item):

| Stage | Producer (validates the value it builds) | Schema | Refusal names |
|---|---|---|---|
| 1 authored task EDN | task EDN reader (edamame, 7.9) | `:seon.task/entry`, whole-file duplicate check | file path, entry index, key (`:intnet`), offending value |
| 1 authored metadata | `var-row` / `namespace-row` (`src/seon/fn.clj:644`, `:221`), before any edge | `:seon.link/metadata`, `:seon.test/authored-seed` | holder symbol, metadata key, offending value (today seed refusals already do this, `fn.clj:466`) |
| 2 parser output | `seon.fn.analyzer/analyze`: replace `[:vector :map]` in its contract with the entry schemas | `:seon.fn.analyzer/var-definition`, `/keyword`, `/arities` | analysis key, filename, row/col, member |
| 3 rows | existing row validation, plus the new members | `:seon.program/row` (+ `:seon.program/tasks`), `:seon.task/edge`, derived contract compile | row identity, attribute, offending value |

Two stage-3 weaknesses the schemas exposed:
- `:seon.program/row` is an `:or` of two alternatives. A malformed function row explains every alternative, 18
  members for one bad `:seon.fn/sym`. A `:multi` dispatched on the identity attribute would name only the member that
  broke.
- The row schema is open, so a misspelled attribute (`:seon.program/taks`) passes it. Only the writer's final
  Datahike schema check catches it. 1.15 keeps that check ("the writer's final validation still applies"), but the
  transform-stage refusal should name the member first, for example with closed row alternatives.

## 3. Redesign suggestions (shaking the design)

**R1. One key, `:seon/tasks`, everywhere; the holder decides the role.** The spec uses `:seon/tasks #{…}` on code and
`:seon/task :x` on tests (7.2, 7.3). Two spellings of one relation invite `proves-plural` (T1), which is legal, and a
reader that has to accept both. A deftest is known to kondo (`:test true`), so `:seon/tasks` on a test *is* the
proof edge (T5 derives `:proof` from the holder with no extra key). The result:
- one refusal rule (a set of qualified keywords);
- one grep key;
- a test that proves two tasks needs no second form.

Cost: none. It deletes a spelling.

**R2. Task edges are keyword values, never refs.** T14 joins by value (`[?e :pp.program/tasks ?t] [?task :pp.task/id ?t]`).
- It keeps 7.13 exact: program rows carry ids, task entities live in data, program merge never needs a task entity
  to exist, and the destination gate finds a dangling id with one `not` join.
- A ref would couple program admission to task data, and a deleted task would sweep or refuse program rows.
- "Open" needs no stored state: the id's presence in source *is* openness, and 7.6 settles by deleting it.

**R3. Link symbols are always quoted in code, and resolved statically by the machinery that already exists.** T9 shows
that a bare symbol in `defn` metadata becomes a function object at runtime and throws for an unloaded namespace. T1
and T9b show kondo is inconsistent about the same form. With `` ` `` or `'`:
- runtime yields the symbol, with syntax quote resolving aliases;
- statically, `capability-symbol` (unquote) followed by `seed-symbol` (alias) yields the same symbol (T5);
- one value, both sides.

Refuse a bare symbol, `::` keywords (T17b) and reader conditionals (T1 `rc`) in link metadata. In EDN, write bare
fully qualified symbols (1.5): edamame and one unquote make `'x` harmless (T7), so the reader forgives a quote in data.

**R4. Pick one owner for return keys; do not claim kondo does it today.** Row 1.12 says outputs come from "the clj-kondo
fork's type inference, exported per arity". T12 and T13 show that export is missing (`analysis.clj:92` drops
`:arities`) and that coverage is literal map tails only. derived-contracts §2 designs a separate tail reader. Having
both would be two parsers. Three options:
1. *Simplest:* export `:arities` from the fork (~3 lines) and derive outputs only from literal-map tails; everything
   else writes `:malli/schema` or `:seon.fn/returns`. It covers the ~28/196 literal tails (derived-contracts P10).
2. **Recommended:** the same export, plus teach the fork's return tagging `let`/`do`/`when-let` last-expression,
   `if`/`when`/`cond`/`case` unions, and `assoc`/`merge`/`cond->` over a map tag, in `types.clj`/`analyzer.clj`
   where the body is already walked. That is a general kondo improvement, upstreamable, and keeps "one parser" true.
   Estimated size is unmeasured (it needs a fork probe).
3. Seon's own tail reader (derived-contracts §2). Most expressive, but a second parse of every body.

The spec row should be revised to name whichever is chosen.

**R5. Store typed link edges, not more `references`.** Today must-read and see-also targets already enter
`:seon.fn/references` (T3), mixed with body references. Emit `:seon.fn/must-read` and `:seon.fn/see-also`, each a
symbol, keyword or path. Namespace-level links belong on the namespace row, not in the file's
`:seon.fn/unresolved-references` (T4).

**R6. Refuse the silent encodings at the one producer.** Every refusal below is a filter over facts the analysis
already returns (T1–T5, T17b), so no new parse is needed:
- `^:seon/tasks` → `true`;
- a non-set value;
- a `deftest` whose body keywords include `:seon/task(s)`;
- a `::`-resolved id;
- an id under a reader conditional;
- a dangling or duplicate id at the gate.

The `read-source` alias fallback (`signature.cljc:25`) should refuse an unknown alias instead of minting `:nope/k` (T10).

**Estimate for the ingestion slice (7.9) on this evidence:** edges and refusals in `var-row`/`namespace-row` (~25 lines,
reusing `capability-symbol`/`seed-symbol`), the task EDN reader and reconcile (~25 lines), and schemas for the two
attributes. That is about 50 added lines plus schemas, measured at 2–3 ms for three files. It does not cover schema
attribute maps (1c), which need the schema producer and were not probed.

## 4. Out-of-scope findings (recorded here; no issue file written)

- `:seon.fn/keywords` includes the destructuring directive `:seon.agent/keys` as if it were an attribute mention
  (T3, `pp.alpha/reads-db`). Producer: `keywords-by-holder` in `src/seon/fn.clj`. `rg` over `docs/seon/issues`
  found no existing issue.
- `signature/read-source` resolves an unknown alias to itself (T10, `signature.cljc:25`), so `::nope/k` derives
  `:nope/k` silently in the SCI/reader path, while kondo refuses it (T1).
- `seon.fn.schema-shape/canonical-form` still crashes on naked map entries at HEAD (T11b; derived-contracts S0).
- `my.program/reads-key` merges input, output and guard refs into one group (T15). "Returns it" and "reads it" are
  distinct questions the data already answers. The split query took 180 ms because of a predicate after the join;
  binding the attribute as `:in` collection should be cheaper (unmeasured).
- The default cluster reported `:faulted`, with 21 open faults, before this lane started (`runtime_status`). None
  came from this lane.

Verification limits:
- schema attribute maps (1c) are not probed;
- `.cljc` task EDN auto-resolution is not probed;
- the fork's return-tag extension (R4 option 2) is unsized;
- reverse lookups ran on hand-shaped facts in `d/with`, not on rows produced by a changed `var-row`.
