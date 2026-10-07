# Compact, clear, fast Clojure: idioms mined from reference source, contrasted with Seon

Research note, 2026-10-07. I read the source, not the docs: `reference-code/{clojure, malli, sci, edamame,
clj-reload, fipp, puget, editscript, konserve, datahike, core.async, rewrite-clj, babashka-process, hyperlith}` and
compared it with `src/` at `a8e3bde5b`. Every count below is a grep over `src/**/*.clj*` (90,950 lines, 3,618
top-level `defn`/`defn-`). Counts are per line unless marked otherwise. `rg` resolves to `ugrep` on this machine, so
the multi-token patterns were run with `perl`. Patterns are given so anyone can rerun them.

## 0. The measured gap

| measure (whole `src`) | Seon | malli | sci | clojure.core+set+walk+string |
|---|---|---|---|---|
| lines / defn (mean, top-level forms) | 23.4 | 10.6 | 21.1 (an analyzer) | 13.0 |
| defns over 40 lines | 477 (35,422 lines, 39% of src) | 30 | 69 | 37 |
| defns over 80 lines | 126 | 9 | 21 | 5 |
| docstring lines inside defns | 11,933 (13%) | 228 (2.5%) | – | – |
| attr-map (`:malli/schema` etc.) lines inside defns | 9,792 (11%) | – | – | – |
| `;` comment lines | 4,873 (5.4%) | 500 | 851 | 1,009 |
| fully-qualified keywords per 1k lines | 329 | 32 | – | – |
| `(let [` per 1k lines | 37 | 55 | – | – |
| `(->`/`(->>` per 1k lines | 2.9 | 17 | – | – |
| `(fn [` + `#(` per 1k lines | 36 | 86 | – | – |
| `defmulti`/`defprotocol`/`reify`/`deftype` per 1k | 0.5 | 11.6 | 5.3 | 2.1 |
| mean defn name length (chars) | 14.7 | 12.0 | – | – |
| mean line length (chars) | 50.8 | 44.0 | – | – |

How the line types were measured: `/private/tmp/claude-501/parts.awk`, run over each top-level `defn`. It counts
docstring lines, then the lines before the arglist (the attr map), then the body. Function lengths come from `len.awk`,
which measures from one top-level `(` to the next. The method is approximate (reader conditionals inflate malli's
attr count), but the ranking is robust. **The body is only 65% of Seon's defn lines. Docstrings and contracts take
24%.** The bodies themselves are also about 2x longer than malli's for comparable work. Most of the cause is
plumbing: re-keying, error round-trips and defensive checks. Algorithmic content is not the cause.

Some rules below conflict with a current CLAUDE.md law. They are marked **Law tension**. The guide does not override the
law. It names the cost so the owner can rule on it.

---

## A. Function size and decomposition

### R1. Keep a function to one idea of about 5–15 lines, and name its parts as functions, not as `let` locals with comments.

Reference: rewrite-clj composes movement out of one-line functions.
`reference-code/rewrite-clj/src/rewrite_clj/zip/move.cljc:8-26`
```clojure
(defn right [zloc] (some-> zloc zraw/right ws/skip-whitespace))
(defn left  [zloc] (some-> zloc zraw/left ws/skip-whitespace-left))
(defn down  [zloc] (some-> zloc zraw/down ws/skip-whitespace))
(defn up    [zloc] (some-> zloc zraw/up ws/skip-whitespace-left))
```
`clojure/set.clj:95-104` is `index`: a whole relational operation in one `reduce`.

Seon counter-example: `src/seon/db.clj:4088` `write-owned-values-error` is **255 lines**. Inside one `let` it defines
`fail!`, `charge!` and `entity-map` (closures over a `volatile!`), a traversal memo, an owner-edge fold and
comment paragraphs. The other giants are `seon.test/select` (368 lines), `seon.test/run` (364),
`seon.sci.eval/evaluate` (360), `acquire-program!` (321), `turn/call-turn` (267) and `turn/step` (249).
Count: 477 defns are over 40 lines and hold 35,422 lines. 126 are over 80 lines (`len.awk`, threshold on top-level
form length).

Rewrite pattern. Each inner `fn` that closes over 1–2 locals becomes a top-level `defn-` taking those locals as
arguments. Each `;; paragraph` above a binding becomes that function's name:
```clojure
;; before: 255-line write-owned-values-error with fail!/charge!/entity-map closures
;; after
(defn- component-attrs [before after] (into #{} (keep (fn [[a p]] (when (:db/isComponent p) a)))
                                            (merge (dbi/-schema before) (dbi/-schema after))))
(defn- swept-cells [projection tx-data] ...)          ; 4 lines
(defn- new-owner-edges [max-eid components tx-data] ...) ; 6 lines
(defn- owned-refusal [cause data limit] (ex-info "Owned entity validation refused." ...)) ; 5 lines
(defn write-owned-values-error [projection report plans ids]
  (let [comps (component-attrs (:db-before report) (:db-after report)) ...] ...)) ; ~30 lines
```
Expected saving: 20–30% of a giant. The comments disappear into names, and the closure plumbing (`@traversal`,
`vswap!`) collapses once the traversal is a plain `reduce` over an explicit state map.
Speed: neutral. Top-level fns get direct linking, and closures allocated per call disappear.

### R2. Separate the pure core from the effectful shell: compute data in one function and perform it in another.

Reference: clj-reload's `unload` is a thin loop over `swap!` with pure `scan` and `keep/resolve-keeps`
(`reference-code/clj-reload/src/clj_reload/core.clj:304-332`). Datahike's API `transact!` only normalizes arguments
and delegates (`reference-code/datahike/src/datahike/api/impl.cljc:30-42`):
```clojure
(defn transact! [connection arg-map]
  (let [arg (cond (map? arg-map) (if (contains? arg-map :tx-data) arg-map (log/raise "..." {...}))
                  (or (vector? arg-map) (seq? arg-map)) {:tx-data arg-map}
                  :else (log/raise "..." {...}))]
    (dw/transact! connection arg)))
```
Seon counter-example: `src/seon/cluster.clj:1106-1140` `transact-initialization!` interleaves `db/db` reads,
refusal construction, readiness filtering and transacting in one `loop`. `src/seon/turn.clj:189`
`settlement-projection` stages a blob, an effect, inside a function named "projection". Effects mixed into derivations
are roughly what the 477 long functions have in common. There is no single grep for it.
Rewrite: `(ready-partition db pending) -> {:ready [...] :waiting [...]}` (pure, 5 lines) plus a 6-line loop that
transacts `:ready`.

---

## B. Data shapes and keys

### R3. Inside one namespace, use `::k` and `:alias/k`, and destructure with `:ns/keys`. Spell a full namespace only at the store boundary.

Reference: malli has 32 fully-qualified keywords per 1k lines. Internally it uses plain or `::` keys
(`reference-code/malli/src/malli/core.cljc:203`):
```clojure
(defn -exception [type data] (ex-info (str type) {:type type, :message type, :data data}))
```
clj-reload documents its whole state in plain keys once (`clj_reload/core.clj:28-41`: `:since :files :namespaces
:to-unload :to-load`).

Seon counter-example: there are 29,942 literal `:seon.x.y/z` keywords (329 per 1k lines) and 3,498 distinct ones.
The top ten are `:seon.agent/id` (794), `:seon.error/message` (694), `:seon.db/database-value` (679),
`:seon.schema/value` (505) and `:seon.error/at` (449). 4,625 `::` keywords are already in use, so the alias machinery is
there. Search: `grep -oE ':seon\.[a-z.\-]+/[a-z\-?!]+'`.
Rewrite example (`src/seon/render/web.clj:691-702`, with `:require [seon.db :as db] [seon.agent :as-alias agent]`):
```clojure
;; before (12 lines)
(merge render-context
       {:seon.db/db database
        :seon.cluster/name (or (:seon.cluster/name render-context)
                               (get-in render-context [:seon.turn.loop/cluster :seon.cluster/name]))
        :seon.db/connection connection :seon.agent/id agent-id
        :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms render-context) ...})
;; after (5 lines)
(merge ctx {::db/db database ::db/connection conn ::agent/id agent-id ::admit/caps caps
            ::cluster/name (or (::cluster/name ctx) (get-in ctx [::loop/cluster ::cluster/name]))
            ::eval/time-limit-ms (::eval-config/time-limit-ms ctx)
            ::value/root [::agent/id agent-id]})
```
**Law tension:** the "Use fully namespaced keys" law concerns *identity*. `::db/db` is the same keyword as
`:seon.db/db` after reading, so this rule keeps the law and changes only the spelling.
Saving: line width, not semantics. 381 lines are over 140 characters, and wrapping a long key or value pair accounts
for most 2-line map entries. Estimate: about 1,500 lines unwrapped.
Speed: identical, because the reader produces the same interned keyword.

### R4. Do not re-key a map into another namespace that holds the same facts. Pass the original value, or rename once with `set/rename-keys`.

Reference: `reference-code/clojure/src/clj/clojure/set.clj:78-87`
```clojure
(defn rename-keys [map kmap]
  (reduce (fn [m [old new]] (if (contains? map old) (assoc m new (get map old)) m))
          (apply dissoc map (keys kmap)) kmap))
```
`clojure.core/update-keys` (`core.clj:8152`) handles the systematic case.

Seon counter-example: `src/seon/schema.clj:4199-4215` `admit-declarations!` copies a projection into a new map. Each
`:seon.schema.projection/X` becomes `:seon.schema/X`: `schema-dependencies`, `schema-admissions`,
`function-admissions`, `pure-predicate-symbols` and so on, 11 lines. It does the same again at `schema.clj:4170-4183`.
`src/seon/fn.clj:3136-3146` copies `:seon.fn.file/{relative-path,digest,identities}` to `:seon.fn.change/*`.
Counts: 46 lines copy the same local name across two Seon namespaces (`perl: /:(seon..)\/(\w+) \(:(seon..)\/\2 /`
with `$1 ne $3`). 550 lines are literal `:k (:other-k m)` projections (`perl: /:(seon..)\/.. \(:(seon..)\/.. [a-z]/`).
Rewrite:
```clojure
;; before: 11 re-keyed lines, then (validate-declarations! {...})
;; after: validate-declarations! declares :seon.schema/projection as its input and reads the projection's own keys
(validate-declarations! projection (dependent-schema-keys projection keys) affected-fns)
```
The projection already is the value. The consumer should take it rather than a lookalike.
Saving: about 400 lines for the literal projections plus the schemas declaring the duplicate keys. Each mirrored key
pair also carries a registry entry in `resources/seon/schema*.edn`.
Speed: faster. No map is built, and no second key set is validated.

### R5. Copy optional keys with `select-keys` or `merge`, not with a `cond->` clause per key.

Reference: `clojure.core/select-keys` (`core.clj:1559`) already omits absent keys. malli builds properties with
`(merge ...)` everywhere.
Seon counter-example: `src/seon/fn.clj:785`, `src/seon/error.clj:2236`, `src/seon/fault.clj:437`.
```clojure
(cond-> row
  (:seon.fn/doc-order metadata) (assoc :seon.fn/doc-order (:seon.fn/doc-order metadata))
  (:seon.fn/spec definition)    (assoc :seon.fn/spec (:seon.fn/spec definition)))
```
Count: 21 one-line plus 58 two-line copy-if-present clauses (`perl: \((:k) x\)\s*\(assoc \1 \(\1 x\)\)`).
Rewrite: `(merge row (select-keys metadata [:seon.fn/doc-order :seon.fn/spec]))`, which is 1 line per group instead of
1–2 lines per key. One caveat: `select-keys` keeps present-but-`false` values, and `cond->` drops them. Seon stores no
nil and no false flags as absence, so the two agree there.
Speed: `select-keys` walks the requested keys once. `cond->` does the same lookups twice (test, then value).

### R6. Destructure in the arglist. Do not bind locals with `x (:k m)` in a `let`.

Reference: malli destructures nearly every options map in the parameter vector. Its `:keys [` density is
18.6 per 1k lines, against Seon's 2.1 (`malli/core.cljc:717`):
```clojure
(defn -min-max-pred [f]
  (fn [{:keys [min max]}] (cond (not (or min max)) nil ...)))
```
sci (`sci/impl/utils.cljc:65`) uses `(let [{:keys [:line :column :file]} (meta iobj)] ...)`.
Seon counter-example: there are 365 `let` lines of the exact form `name (:ns/k m)` and 34 of `name (get-in m [...])`
(perl over `src`). `src/seon/sci/eval.clj:1206` `install-row!` already uses a destructuring arglist: the style exists,
but it is not consistent.
```clojure
;; before (src/seon/db.clj:4100-4102)
(let [before (:db-before report) after (:db-after report)
      limit (:seon.config.db/validation-node-limit projection) ...
;; after
[{:seon.config.db/keys [validation-node-limit] :as projection} {:keys [db-before db-after tx-data]} plans ids]
```
Saving: about 1 line per 2 bindings. Roughly 200 lines, plus clearer signatures.
Speed: identical, because destructuring compiles to the same `get`.

### R7. Write a data shape once, as a named schema or a comment block. Do not restate it at every function.

Reference: clj-reload states its whole state model in 30 comment lines at the top
(`clj_reload/core.clj:10-52`). After that, function bodies never restate it. malli map entries can name a
registry key directly (*naked keys*, `malli/core.cljc:524-535`): `[:map ::id [::closed-tx {:optional true}]]`.
Seon counter-example: `src/seon/turn.clj:233` `interrupted-warning` has a 13-line schema for an 8-line body. It
spells `[:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]`, which is a key whose schema is itself.
332 such self-referential entries `[:k :k]` occur in `src` and 2,676 in `resources/**/*.edn`
(`perl: /\[(:ns\/k|::k)\s+(\{..\}\s+)?\1\]/`).
```clojure
;; before (13 lines)
{:malli/schema [:=> [:cat [:sequential [:map [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
                                             [:seon.cluster.eval/interrupted-at {:optional true}
                                              :seon.cluster.eval/interrupted-at]]]]
                    [:maybe [:map [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
                                  [::missing-results ::missing-results]]]]}
;; after (2 lines)
{:malli/schema [:=> [:cat [:sequential [:map :seon.cluster.eval/ordinal [:seon.cluster.eval/interrupted-at {:optional true}]]]]
                [:maybe ::interrupted-warning]]}
```
Saving: 332 entries in `src` at about 1 line each, plus the same in resources.
Speed: identical, because malli resolves the reference through the same registry.

---

## C. Errors

### R8. Throw `ex-info` with a data map where the failure is detected, and catch once at the boundary. Do not convert to a value and back at every layer.

Reference: malli has two error helpers and 11 `throw`s in 9k lines (`malli/core.cljc:203-207`):
```clojure
(defn -exception [type data] (ex-info (str type) {:type type, :message type, :data data}))
(defn -fail! ([type] (-fail! type nil)) ([type data] (throw (-exception type data))))
```
sci adds location once where it throws (`sci/impl/utils.cljc:62-70` `throw-error-with-location`). The REPL entrance
`eval-string*` (`sci/impl/interpreter.cljc:94-114`) has **no catch at all**, because the caller's boundary owns it.
babashka.process `check` (`babashka/process.cljc:95-114`) throws `(ex-info err (assoc proc :type ::error))`, so the
whole process map is the error data.

Seon counter-example: `db/q`, `db/pull` and `db/datoms` catch Datahike's `ex-info`. They convert it into an error
*value* (`src/seon/db.clj:225-250` `dependency-error`), and then the caller converts it straight back into an
exception. In `src/my/program.clj:106-155` `caller-data`, the following 4-line form appears **six times in one
function**:
```clojure
(let [result (db/pull database [:seon.fn.arity/min :seon.fn.arity/max] %)]
 (if (:seon.db/invalid-read result)
 (throw (ex-info (:seon.error/message result) result)) result))
```
Counts: `(throw (ex-info (:seon.error/message` appears 119 times (28 in `my/program.clj`, 13 in `fn.clj`, 12 in
`sci/eval.clj`). `if (:seon.db/invalid-read result)` appears 25 times. `:seon.db/invalid-read` has 272 references. Each
read site pays 3–4 lines to undo what `db/q` did.
Rewrite: the read owner throws its refusal (`(throw (ex-info msg refusal))`), and `my.program/read-result` keeps the
single catch it already has:
```clojure
;; before: 50-line caller-data with 6 unwrap blocks
;; after (~22 lines)
(let [arities (db/q database '[:find [?a ...] :in $ ?e :where [?e :seon.fn/arities ?a]] entity)
      declared (mapv #(dissoc (db/pull database [:seon.fn.arity/min :seon.fn.arity/max] %) :db/id) arities) ...]
```
**Law tension:** "Nothing crashes … RETURNS it as an error value". The law can hold at the *entrant boundary* (the
one-path fold, MCP and Flow procs already convert throws). It does not need to hold in every internal read. Values
inside and throws inside both satisfy "nothing crashes" at the edge. Only the round trip is waste.
Saving: about 119 × 3 = 360 lines for the rethrows, about 100 in `db.clj` refusal wrapping, and the
`read-refusal?`/`refused-read` helpers. The bigger effect is that every reader's output union shrinks from
`[:or value :seon.db/error-result]`, which removes schema lines too.
Speed: faster on the success path. There is no `(:seon.db/invalid-read result)` probe per read, and no `vary-meta`
per refusal. JVM exceptions cost only on failure.

### R9. Construct an error with one short helper call carrying positional essentials plus a data map. Do not use an 8-key literal at every site.

Reference: sci, `(throw-error-with-location msg node {:extra 1})`. malli, `(-fail! ::invalid-entry {:entry e})`
(`malli/core.cljc:526`). The *type keyword* is the identity, and the data map carries only what is specific to the
failure.
Seon counter-example: `src/seon/render/web.clj:655-666` `debug-diagnostic` and `web.clj:681-689`.
```clojure
(seon.error.refusal/diagnostic (java.util.Date.) :seon.render.web/render 'seon.render.web/turn-function-result
  {:seon.error/message (str "Not yet available: " function)
   :seon.error/fix "Supply the expected member and repeat the requested operation."
   :seon.render.web/function-unavailable function
   :seon.error/member :seon.fn/sym
   :seon.error/expected "a resolvable turn function"
   :seon.error/data {:seon.render.web/arguments arguments}})
```
Counts: `refusal/diagnostic` has 225 call sites. There are 288 `(java.util.Date.)` (the timestamp is passed by hand),
302 `:seon.error/offending`, 326 `:seon.error/expected` and 684 `:seon.error/message`. 87 messages are built with `(str ...)`.
Rewrite: the helper supplies `at` (the clock) and `operation` (from `&form`, or from the caller's var via a one-line
macro, see R22). The fix text belongs to the error *schema*, not to each site:
```clojure
(err/refuse ::function-unavailable {:function function :arguments arguments})
```
Saving: 225 sites × about 5 lines ≈ 1,100 lines.
Speed: neutral to faster, because there is one fewer `Date` allocation per site when the recorder stamps time.

### R10. Walk a cause chain with sequence functions. Do not write a hand-made `loop`/`recur` at each site.

Reference: `clojure.stacktrace/root-cause` exists, and the idiom is
`(take-while some? (iterate ex-cause e))`. puget's dispatcher (`puget/dispatch.clj:14-31`) shows the same habit of
`some` over a sequence of candidates.
Seon counter-example: `src/seon/test/runner.clj:83-88`.
```clojure
(defn- throwable-signature [^Throwable failure]
  (loop [current failure]
    (when current (or (:seon.error/signature (ex-data current)) (recur (ex-cause current))))))
```
Count: 10 hand-written cause loops (`grep 'recur (ex-cause\|recur (.getCause'`): `cluster/agent.clj:509`,
`sci/kernel.clj:43,784`, `sci/eval.clj:3733`, `render/value.clj:355`, `print/value.clj:81`,
`schema/internal.cljc:447`, `error/refusal.clj:189,821`. `error.refusal/cause-links` already exists and is used 7
times.
Rewrite: `(some (comp :seon.error/signature ex-data) (error.refusal/cause-links failure))`. That is 1 line instead of
5, and the cycle guard lives in one place.

### R11. Catch only what you can handle, and catch it at the boundary. Never `catch Throwable` to keep going in the middle.

Reference: malli has 22 `catch` forms in 9k lines, almost all `-safe-pred`
(`malli/core.cljc:209`: `#(try (boolean (f %)) (catch Exception _ false))`). sci rethrows inside `try` and adds
location only at the top level (`sci/impl/utils.cljc:121-135`).
Seon: 157 `catch Throwable` and 36 `catch Exception`. Normalized, that is 2.1 catches per 1k lines against malli's 2.4,
so the *number* is fine. The cost is what follows each catch: a diagnostic literal (R9) and a value to unwrap (R8).
Rule: a catch body should be 1–3 lines that call one boundary function such as `fault/record!`.

---

## D. Validation and contracts

### R12. Validate at the boundary with a compiled validator. Inside a function, trust the shape.

Reference: malli itself says "Creates the `validator` for every call. When performance matters, (re-)use `validator`"
(`malli/core.cljc:2680-2686`). The fast path closes over pre-built per-key validators (`core.cljc:1266-1285`). This is a
vector of closures built once, with `.valAt` and a sentinel and no `find` per call.
Seon counter-example: `src/seon/turn.clj:336-353` `require-open-run` re-checks inside the writer whether a value is an
error by probing three keys:
```clojure
(cond (and (map? turn) (contains? turn :seon.error/at) (contains? turn :seon.error/layer)
           (contains? turn :seon.error/operation))
      (throw (ex-info (:seon.error/message turn) turn))
      (nil? turn) (refuse! operation ::no-such-run request) ...)
```
Counts per 1k lines (Seon / malli): `(contains? ` 6.0 / 2.6, `(or (:` 5.5 / 0.9, `(map? ` 4.6 / 3.3.
Seon also arms a contract on *every* function, private ones included (2,580 `:malli/schema`).
Rewrite: once `current-run` throws its read refusal (R8), the first clause disappears. The function becomes
`(cond (nil? turn) (refuse! ...) (not (open? turn)) (refuse! ...) :else turn)`, which is 4 lines instead of 10.
**Law tension:** "Contract every admitted function, private ones included." Malli's own practice is to put contracts
on the public surface (`m/=>` on API fns) and keep private helpers unchecked. Arming a private helper validates the same
value again at every hop.
Speed: each armed private call pays a validator call. The profile skill can measure this. Expect a significant share
of "armed" overhead on internal hops.

### R13. Write a contract on one line when it fits. Use registry names, never inline `[:map ...]` restatements.

Reference: malli's function schemas in its own tests and docs are one line:
`(m/=> plus [:=> [:cat :int :int] :int])`.
Seon: 9,792 attr-map lines across 3,618 defns, which is 2.7 lines of contract per function and about 0.17 per body
line. One reason is inline map schemas that restate registry keys (R7). Another is the repeated polymorphic exemption
literal, 152 sites. 45 of those carry the identical 160-character sentence "A total predicate accepts arbitrary
objects, including nil, …" (`src/seon/db.clj:72`, `src/seon/error.clj:53`).
Rewrite: register `:seon.schema/any-candidate` once with that property, then write
`{:malli/schema [:=> [:cat :seon.schema/any-candidate] :boolean]}`.
Saving: about 150 lines of exemption text, plus 1–3 lines for each inline map that becomes a named key. In total
roughly 2,500 of the 9,792.

### R14. Make the generator a property of the schema, not a top-level `def` beside it.

Reference: malli takes `:gen/elements`, `:gen/fmap` and `:gen/schema` as schema properties, so the generator travels
with the schema.
Seon: `src/seon/error.clj:43-49` defines `compiled-schema-generator` and `throwable-generator` as vars. There are 147
`:gen/elements` uses inline at function sites, mostly `[nil false 0 "" [] {}]` repeated with each exemption (R13).
Rewrite: put the generator on the one registered `:seon.schema/any-candidate`. That saves about 147 inline vectors.

---

## E. Iteration and transformation

### R15. Prefer `reduce`, `into` with a transducer, `some` or `keep` over `loop`/`recur`. Keep `loop` for real state machines and primitive index walks.

Reference: `group-by` (`clojure/core.clj:7298-7310`) and `frequencies` (`core.clj:7355-7363`) are one `reduce`
over a transient:
```clojure
(defn frequencies [coll]
  (persistent! (reduce (fn [counts x] (assoc! counts x (inc (get counts x 0)))) (transient {}) coll)))
```
sci's REPL loop (`sci/impl/interpreter.cljc:106-114`) is a `loop` because it is driven by a reader. That is the
legitimate kind.
Seon: 146 `(loop [` (1.6 per 1k, against malli 2.1), so the count is fine. Misuse is the problem: `test/runner.clj:85`
(R10) and `cluster.clj:894` `(loop [entity entity seen #{}]` are a `some` over an `iterate`. The legitimate ones are
`test/runner.clj:975` (a BitSet walk with transients) and `cluster.clj:1631` (a worklist BFS).
Rewrite example (BFS worklist, `cluster.clj:1631`): the `loop [pending reached]` is correct, so keep it.
`cluster.clj:1108` re-reads `db/db` and re-partitions each round. That can be `(let [{ready true waiting false}
(group-by #(entity-ready? db %) pending)] ...)`, which is one call instead of the `filter`/`remove` pair at
`cluster.clj:1124-1125`. Each entity is checked once instead of twice.
Speed: `group-by` halves `entity-ready?` calls, each of which is a lookup-ref resolution (a DB read).

### R16. Use one pass, not filter + remove, map + filter + sort over the same collection, or `count (filter ...)`.

Reference: `clojure.core/into` with an xform (`core.clj:7033-7051`) runs one transducing pass into a transient.
Seon: there are 15 `(count (filter`, 25 `(first (filter`, 47 `(set (map` and 35 `(into [] (map` (as opposed to `mapv`). `filter`+`remove`
pairs over the same input appear at `cluster.clj:1124-1125`. Seon's 83 `into ... (comp` transducer uses are good and
are the model.
Rewrite: `(first (filter p xs))` → `(some #(when (p %) %) xs)`, or a `find-first` (malli has
`malli.util/find-first`, `util.cljc:37`). `(set (map f xs))` → `(into #{} (map f) xs)`. These save no lines.
They save allocation: one fewer lazy seq realized per call.

### R17. Build big maps and vectors with transients (`persistent!`/`assoc!`), and walk maps with `reduce-kv`.

Reference: `update-vals` and `update-keys` (`clojure/core.clj:8136-8164`) use `reduce-kv` into a transient.
`map-invert` (`set.clj:106-112`) does the same. editscript's diff core uses `(transient {})` inside a `loop`
(`editscript/util/common.cljc:74`) and a `volatile!` counter (`editscript/edit.cljc:154-158`).
Seon: `reduce-kv` 44, `transient` 12, `update-vals` 23, `update-keys` 1. Meanwhile there are 297 `(into {} ` and many
`(into {} (map (fn [[k v]] [k (f v)])) m)`, which is `update-vals` written out by hand (for example
`src/seon/db.clj:4106-4110` builds `targets` with `(into {} (map (fn [a] [a ...])) components)`, which is
`(zipmap components (map f components))`).
Rewrite: `(update-vals m f)` or `(update-keys m f)` instead of `(into {} (map (fn [[k v]] [k (f v)])) m)`. That is 1 line,
no tuple allocated per entry, and the metadata is preserved.

### R18. Use the threading macros, and do not name an intermediate that is used once.

Reference: malli uses `(->` or `(->>` at 17 per 1k lines. `-comp` (`malli/core.cljc:247-260`) is literally
`(fn [x] (-> x f4 f3 f2 f1))`.
Seon: 2.9 per 1k (`(->` 161, `(->>` 104) against 3,385 `(let [` forms. `src/seon/turn.clj:195-206`
`settlement-projection` rebinds `receipt` twice, through `let receipt evaluation receipt (if-let ...)`:
```clojure
;; before (17 lines)
(let [receipt evaluation
      receipt (if-let [value-edn (:seon.eval/value-edn receipt)]
                (let [staged (blob/stage! (:seon.db/connection cluster) value-edn)]
                  (-> receipt (dissoc ...) (assoc ...) (update :seon.blob/staged-writes (fnil conj []) staged)))
                receipt)]
  [(dissoc receipt :seon.blob/staged-writes) evaluation (vec (:seon.blob/staged-writes receipt))])
;; after (6 lines)
(if-let [edn (:seon.eval/value-edn evaluation)]
  (let [staged (blob/stage! (::db/connection cluster) edn)]
    [(-> evaluation (dissoc :seon.eval/value-edn) (assoc :seon.eval/value-digest (:seon.blob/digest staged)))
     evaluation [staged]])
  [evaluation evaluation []])
```

---

## F. Polymorphism and dispatch

### R19. Dispatch on data with a multimethod, a protocol or a map of keyword to fn. Do not grow `cond` ladders over key presence.

Reference: fipp's whole document model is a `defmulti` on `first`, with one 2–4 line method per node kind
(`fipp/engine.cljc:10-62`):
```clojure
(defmulti serialize-node first)
(defmethod serialize-node :text [[_ & text]] [{:op :text, :text (apply str text)}])
(defmethod serialize-node :break [& _] [{:op :break}])
(defmethod serialize-node :group [[_ & children]] (concat [{:op :begin}] (serialize children) [{:op :end}]))
```
fipp's printer is a protocol, `IVisitor` (`fipp/visit.cljc:8-30`), and the dispatch is one flat `cond` on type
(`visit*`). konserve's whole storage contract is three small protocols (`konserve/protocols.cljc:4-46`). malli's
registry is "a map is a registry" (`malli/registry.cljc:24-34`):
```clojure
(defn simple-registry [m] (reify Registry (-schema [_ type] (m type)) (-schemas [_] m)))
```
Seon: `defmulti` + `defprotocol` = 9 in total, which is 0.1 per 1k lines against malli's 3.6. There are 130 `case` and
601 `cond` forms. `src/seon/error.clj:96-123` `message` picks its message from which keys exist
(`(:seon.turn/rule source)`, `failure` and so on). Error *kinds* are told apart by key probing (R12), not by a dispatch
value.
Rewrite: give each error a `:seon.error/kind` (the declared schema name, already in metadata as
`:seon.error/declared-schema`, 65 `vary-meta` sites) and `(defmulti message :seon.error/kind)`. Each new kind is then one
method instead of a new `cond` branch with probes.
**Law tension:** "no … discriminator stamp". The law forbids a *stamp* (a copied tag). The declared schema the producer
already names *is* the identity, so dispatching on it is reading, not stamping.

### R20. Parameterize behaviour by passing functions. Do not use `requiring-resolve` to escape a namespace cycle.

Reference: puget and fipp take the dispatch or visitor as an argument (`puget/dispatch.clj:14`). babashka.process
takes `:program-resolver` and `:escape` as *option functions* (`babashka/process.cljc:215-220` `*defaults*`).
Seon counter-example: `src/seon/db.clj:49-68` has eight `defonce … (delay (requiring-resolve 'seon.error/…))` vars that
exist only because `seon.error` requires `seon.db`. Count: 124 `requiring-resolve` in total, plus 84 `declare`.
Rewrite: the caller passes `render-ai` and `render-html` in the options map it already passes to `db/transact!`, or the
two namespaces merge. Either way, 16 lines and one load-order puzzle go.
Speed: `requiring-resolve` takes a lock on first use, and `@delay` adds a volatile read on every call. Passing the fn
avoids both.

---

## G. Prose

### R21. Keep a docstring to 1–3 lines saying what the function returns. Put history, provenance, measurements and rationale in Git and specs.

Reference: rewrite-clj's docstrings are one sentence (`zip/move.cljc:8-26`). clojure.core's `frequencies` has 2 lines.
malli spends 2.5% of its lines on docstrings, and its longest are on public API entry points such as `explain`.
Seon counter-example: `src/seon/error.clj:96-123` `message` has an 11-line docstring for a 15-line body. It
reports a measurement ("measured on the first real projection, a Datahike-wrapped transition refusal produced…"),
argues the design and repeats the rule. `src/seon/error.clj:61-71` `refusal` has a 9-line docstring on a 1-line body
that delegates. `turn.clj:233` `interrupted-warning` documents its return shape in prose *and* in a 13-line schema.
Counts: 11,933 docstring lines, 13% of `src`, against 2.5% in malli. Inside `src`: 260 dated lines
(`20xx-xx-xx`), 102 `§` references, 174 `file.clj:NNN` citations (these go stale on any edit above them), 118
"measured" and 42 "AGENTS". Rationale and history are the bulk of it.
Rewrite:
```clojure
(defn- message
  "The reader-facing message: the source's own, else the root cause's, else a description of what arrived."
  ...)
```
**Law tension:** "Docstrings are Markdown with fixed headings (Examples, Edge cases, Costs, …)". Keeping headings for
*public* `my.*` functions, where agents read them, and one line elsewhere satisfies the law where it pays.
Saving: if the docstring share fell to 4% (twice malli's), about 8,000 lines would go.

### R22. Comment *why* in one line, and only where the code cannot say it. Do not narrate.

Reference: hyperlith's `;; Make futures use virtual threads` (`hyperlith/core.clj:24`). clj-reload has a single
schema comment block. malli has 500 comment lines in 9k, mostly section separators.
Seon: 4,873 comment lines. `src/seon/db.clj:4120-4125` is a 6-line comment explaining Datahike's AVET guarantee with two
source citations, inside a binding. The 3-line `;;;` banner block (`;;; ----` / title / `;;; ----`) also
appears throughout. `src/seon/fn.clj:780-784` is a 5-line all-caps paragraph above one `cond->` clause.
Count: 337 `;;; ----` rule lines (`grep -cE '^;;; -{10}'`), which is about 170 banners at 3 lines each where 1 would
do (about 340 lines).
Saving: removing the provenance, measurement and citation comments and shortening banners to 1 line is about 1,500
lines.

---

## H. State, config and context

### R23. Keep state in one place: one atom holding a map, updated with pure functions through `swap!`.

Reference: clj-reload has exactly one `(def ^:private *state (atom {}))` (`clj_reload/core.clj:54`). Every change is
`(swap! *state f args)`, with `f` pure (`scan`, `update :namespaces ...`). The lock is separate and named.
Seon counter-example: `src/seon/flow.clj:669-671` creates three sibling atoms, `active-work`, `io-submissions` and
`accepting?`, plus a `promise`, in one `let`. `flow.clj:788-790` is three atoms per work item (`::status`, `::active?`,
`::task`). `src/seon/cluster.clj:3425-3430` is three more. Count: 67 `(atom `, 76 `defonce` and 22 `^:dynamic`
(malli has 24 atoms, mostly in registries).
Rewrite: `(atom {:active {} :submissions {} :accepting? true})` with `swap!` over pure `(admit state work)` and
`(retire state id)`. Coordinated updates become atomic, which three atoms cannot give: there is no window where
`accepting?` and `active-work` disagree. The CLAUDE.md "atoms are a smell" rule agrees that state belongs in its owner.

### R24. Use one options map with defaults merged once. Do not pass 6–8 positional arguments.

Reference: babashka.process `(def ^:private default-shell-opts {:in :inherit :out :inherit :err :inherit :shutdown
destroy-tree})` and `(merge default-shell-opts opts)` (`babashka/process.cljc:674-678`). edamame normalizes options once
(`edamame/impl/parser.cljc:935` `normalize-opts`) and then passes the normalized map down (`edamame/core.cljc:126`
`parse-next` "must be normalized with `normalize-opts` first").
Seon counter-example: `src/seon/render/web.clj:655` `debug-diagnostic` takes `[message operation member expected
offending cause evidence]`, 7 positionals with a 3-line `:cat` schema. `src/seon/db.clj:4098`
`write-owned-values-error` takes 4 positionals, of which `attribute-plans` and `identity-attrs` are derived from
`projection`.
Rewrite: `(debug-diagnostic {:keys [...]})`. The argument then *is* the error map, and the function shrinks to a
`merge`.

### R25. Use a dynamic var only for something genuinely ambient (out, ns, a log fn). Use `binding` at one place.

Reference: sci binds `current-ns`, data-readers and so on once in `eval-string*`
(`sci/impl/interpreter.cljc:99-103`). babashka.process exposes `*defaults*` "Use `alter-var-root` to change permanently
or `binding` to change temporarily" (`process.cljc:215`).
Seon: 22 `^:dynamic` and 58 `binding [`. `seon.batch/*world*` (`batch.clj:40`) and `seon.effect/*request-context*`
(`effect.clj:36`) carry the world, which CLAUDE.md says to pass explicitly. `seon.test` resolves
`seon.effect/*request-context*` through `requiring-resolve` (`test.clj:181`).
Rule: if a function reads a dynamic var to find its database, make the database an argument. That is R20 again.

---

## I. Naming

### R26. Name by role, and keep names short: the namespace already says the domain.

Reference: malli `validator`, `explain`, `-update`, `-comp`, `-vmap`. clj-reload `scan`, `unload`, `reload`. sci
`eval-form`. The mean defn name is 12.0 characters in malli and 14.7 in Seon. Locals are 7.3 against 8.0.
Seon examples: `seon.db/write-owned-values-error`, `seon.schema/projection-with-declarations`,
`seon.turn/interrupted-warning`, `seon.render.web/turn-function-result`, `seon.cluster/transact-initialization!`.
Within `seon.db`, "-values-error" and "owned" are context the caller has already read. The bigger cost is *key* names
(R3): `:seon.schema.admission/polymorphic-boundary` (169 uses) is 45 characters.
Rule: function names of 1–3 words, and locals of one word (`db`, `conn`, `tx`, `e`, `m`, `xs`). Seon itself uses
`database` 679 times as `:seon.db/database-value` and as a local in long form, while Datahike's own source uses `db`.

---

## J. Macros

### R27. Write a macro only to remove *repeated syntax* that a function cannot: binding forms, def-forms or body wrapping. Keep it under 15 lines.

Reference: core.async `defblockingop` (`core.async/src/main/clojure/clojure/core/async.clj:150-160`) generates
a checked and an unchecked `fn` from one body. clj-reload `with-lock` (`clj_reload/core.clj:60`). Seon itself has only
15 `defmacro`.
Candidates in Seon where a 5-line macro removes hundreds of lines:
1. The read-unwrap block in R8, if R8 is not adopted: `(read! (db/q ...))` as a *function* already works, so no macro is needed.
   The fact that it was inlined 22 times in `my/program.clj` is itself a smell. It looks like a mechanical sweep
   that inlined a helper.
2. `refuse` with the call site's operation symbol (R9): `(defmacro refuse [kind data] `(err/refuse* '~(symbol (str *ns*)
   ...) ~kind ~data))`. This removes the hand-typed `'seon.render.web/turn-function-result` operation that appears
   at 314 `:seon.error/operation` sites.
Safety: both expand to a single function call. Neither introduces bindings or evaluates arguments twice.

---

## K. Performance idioms

### R28. Build expensive things once, outside the per-call path: validators, regexes, registries, compiled queries.

Reference: malli's `-cached` validator (`malli/core.cljc:2678`), `-memoize` with an `AtomicReference`
(`core.cljc:268-270`) and `fast-registry`, a `HashMap` with load factor 0.25 (`registry.cljc:17-22`).
Seon: `src/seon/render/web.clj:674-679` `turn-function-result` rebuilds a resource path with two `str/replace` calls and
probes three `io/resource` calls *on every call* before `requiring-resolve`. `src/seon/db.clj:4114-4117` rebuilds
`swept` (`schema.datahike/vector-attributes-in projection`) on every write report. That one depends on the projection,
so it should live in the projection's cache (the `seon.cache` kind).
Rule: anything that is a function of a stable value (projection, schema, function symbol) goes into a cache keyed
by that value. That is already the "one central cache" law, applied at the call site.

### R29. Read a value once, and pass it. Do not re-read the database inside a loop over the same basis.

Reference: Datahike's `transact!` takes the connection and derives `db` once. malli explainers take `value in acc`
and accumulate with no re-lookup (`core.cljc:2688-2703`).
Seon: `src/seon/cluster.clj:1108-1112` calls `(db/db connection)` at each loop round. That is fine, because the loop
transacts. But `src/my/program.clj:122-138` issues *two* `db/pull` calls per caller (`:seon.fn/sym`, then `:seon.test/sym`)
plus one `db/q` for call arities. That is 3N reads for N callers, and one Datalog query joining
`[?caller :seon.fn/calls ?subject] [?caller :seon.fn/call-arities ?t] [?caller :seon.fn/form-span ?span]` answers it
in one pass. `turn.clj:578-590` `previous-ending-namespace` runs a `q` for the max eid and then a `pull` of one attribute,
which a single `q` with `(max ...)` over the pair would answer. Shape: N+1 query patterns. A rough grep:
`(map #(db/pull` / `(mapv #(db/pull`, 4 sites (`grep -cE '\(mapv? #\(db/pull'`), for example `turn.clj:366`
`(map #(db/pull db '[*] %))`, where `pull-many` exists (`db.clj:3057`).
Speed: N pulls become one query or one `pull-many`. This is the largest *speed* win in this list. It is linear in
callers rather than a constant factor.

### R30. Keep laziness out of effects and out of dynamic scope. Use `mapv`, `run!` and `into` when the result must be realized now.

Reference: `clojure.walk/walk` deliberately `doall`s seqs (`walk.clj:46`). sci's analyzer uses `mapv`
throughout.
Seon: 240 `(map (fn` and 1,377 `#(` lambdas, with `(map ...)` returned from fns that are later consumed inside
`binding` or after a `with-open`. Seon has only 3 `doall`s, so laziness is mostly avoided by using `mapv`/`into` (good).
Rule: return `mapv`/`into` from any function whose caller crosses a `binding`, a `try` or a resource scope. Laziness
pays only when the consumer takes a prefix.

---

## The ten rules that would cut the most from Seon

Method: for each rule, the measured occurrence count × the lines saved per occurrence, read off a rewritten sample.
These are estimates of *removable* lines and they overlap. For example, the docstrings of giant functions count in both
#1 and #4. The overlap-corrected total is about 20–24k lines (25%) of 91k. Ranking by estimated lines saved:

| # | rule | count basis | lines/site | est. lines saved |
|---|---|---|---|---|
| 1 | **R21** docstrings 1–3 lines; history and provenance to Git or specs | 11,933 docstring lines → 4% of src (2× malli's share) | – | ~8,000 |
| 2 | **R13 + R7** one-line contracts with registry names and naked keys; one registered exemption schema | 9,792 attr lines; 332 `[:k :k]` entries; 152 exemption literals | 1–3 | ~2,500 (plus ~2,700 in resources) |
| 3 | **R1** split the 126 functions over 80 lines; closures become `defn-`s; comment paragraphs become names | 35,422 lines in functions over 40 lines | 10–20% each | ~3,500 |
| 4 | **R22** remove narration, citations, dated measurements and 3-line banners | 4,873 comment lines; 260 dated; 174 `file:line` | – | ~1,500 |
| 5 | **R3** `::`/`:alias/` keys and `:ns/keys` destructuring (unwraps long lines) | 29,942 fq keywords; 381 lines over 140 characters; 2-line entries | ~0.05 per key | ~1,500 |
| 6 | **R9** one `refuse` helper; clock and operation supplied; fix text on the schema | 225 `diagnostic` + 128 `refuse!` + 31 `refused!` sites | ~4–5 | ~1,100 |
| 7 | **R8** throw read refusals; catch once; no value→exception round trip | 119 rethrows; 25 inlined unwraps; error-union schemas on every reader | 3–4 + schema | ~700 |
| 8 | **R4 + R5** stop re-keying; copy optional keys with `select-keys` | 550 `:k (:k2 m)` lines; 46 same-name cross-namespace; 79 copy-if-present | ~0.7 | ~450 |
| 9 | **R6** destructure in arglists | 365 `x (:k m)` + 34 `x (get-in ...)` let lines | ~0.5 | ~200 |
| 10 | **R12** trust the shape inside; drop re-validation and key probing | 549 `contains?`, 501 `(or (:`, 420 `map?` (at about 10% removable) | 1–2 | ~200 (more if private contracts are unarmed: Law tension) |

Runners-up: R20 (`requiring-resolve`/`defonce delay` cycle breakers, about 150), R10 (cause-chain loops, about 40),
R18 (threading instead of rebinding `let`s, hard to count, a few hundred), R23 (atom consolidation, small line effect
but a correctness gain).

Biggest speed wins, independent of lines: R29 (N+1 reads to one query or `pull-many`), R8 (no refusal probe per
read and no `vary-meta`), R12 (no armed validation on private hops), R28 (per-call `io/resource` and derivations that
depend on the projection), R15 (`group-by` instead of filter+remove over DB-reading predicates).

Where to start: rules 1, 2 and 4 are pure deletions of prose and metadata with no behaviour risk. They are about 12k lines and could be
scripted per namespace. Rules 6 and 7 need an owner ruling on the "error values everywhere" law (R8 Law tension).
Rule 3 is per-function refactoring that should ride along with whoever owns each giant.
