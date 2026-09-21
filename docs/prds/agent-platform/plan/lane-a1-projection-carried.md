---
type: spec
status: first pass (Fable, 2026-09-21) for astra review; clean write follows
created: 2026-09-21
lane: A1
tags: [agent-platform, malli, projection, instrumentation, call-preparation, schema-shape, admission]
---

# Lane A1 — the projection is read, never rebuilt

Owned: `src/seon/schema.clj`, `src/seon/schema/{admission,edn}.clj`
(`schema/datahike.clj` is A2's), `src/seon/instrument.clj`,
`src/seon/call_preparation.clj`, `src/seon/test/arm.clj`,
`src/seon/fn/schema_shape.clj`, their tests, `reference-code/malli`.
Seam with A2: `seon.db/carried-projection` (`db.clj:1219-1225`, read, never
edited). Every `file:line` was opened this session at HEAD `215447c46`,
malli gitlink `606083c5`. Three read-only `eval_clj` probes were spent; their
forms and values are in §4.

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** Every cluster's database value already
carries its compiled Malli generation as Clojure metadata
(`db.clj:249-271`), and `seon.db/carried-projection` reads it in one map
lookup. Beside that authority we built nine ways to make the same value
again: `projection-from-database` re-queries 3,337 schema rows and 1,555
contract rows and recompiles them (589 ms on the warm `default` JVM,
3,996 ms in the cold test fixture — §1); `instrument/apply!` compiles a
SECOND whole population from the classpath as a "bootstrap" wrapper it
almost never calls (`instrument.clj:995-997`); every armed function
recompiles its own contract (`:747-754`); and on EVERY call of every one of
the 1,570 armed functions the wrapper scans each argument three ways
looking for a projection to validate against (`:604-619`, `:885`).
`call_preparation` rebuilds a function's argument structure with eight
Datalog queries over mirror rows (`:604-713`) although the compiled
contract in the registry already answers "what are the arities, slots,
keys and which are optional" by three Malli calls. A 36-line hand walker
canonicalises schema forms for fingerprints (`schema_shape.clj:21-56`) where
`m/ast` is the canonical form. A 24-line `case` table in `error.clj`
restates messages Malli's `default-errors` table mostly already has.

**Why that shape is wrong.** Each is a mirror kept beside the authority,
plus machinery to keep the mirror current, plus tests policing the
machinery's cost. The work is proportional to the PROGRAM (3,337 forms)
where the change is one declaration; and the wrapper guards its own cost on
every call because it does not trust the world it was armed in.

**The simpler way, as data flow.** One generation is compiled ONCE where the
program is published or a cluster boots (rows → forms → sealed
`mr/fast-registry`, `schema.clj:454-503`), carried on the database value
and the SCI ctx, and READ everywhere else: the seam returns the carried
value; arming reads `(mr/schema registry sym)` and closes over the compiled
contract; call preparation reads arities and entries off that same object;
the fingerprint hashes `m/ast` of it; error text comes from Malli's own
table with a small data overlay for our noun grammar. Recomputation has one
trigger — a declaration changed — and costs that declaration and its
reverse closure (`projection-with-schema`, `:2811`). Nothing else ever
compiles a named declaration again.

## 1. Goal and the numbers that prove it

| Number | Today (measured) | Target | How measured |
|---|---|---|---|
| Projection acquisition at the seam, warm JVM | **588.99 ms** rebuild; **77.63 ms** with the carried value handed as reusable | a metadata read (< 1 µs) | §4 form 1 |
| Same, cold fixture base | 3,995.96 ms of 4,647.8 ms (`test-system-fork-2026-09-23.md:103-110`, verified by data-pack-b4 §2) | ≈ 650 ms residual (clone + reidentify) | `test_support.clj:363` `create-base` clock |
| Carried value vs rebuilt | forms `=`, contracts `=`, fingerprint `=` (§4 form 1) | the premise holds; the rebuild is pure waste | — |
| Named-declaration compilations per arming pass over an unchanged generation | 1 whole population (`instrument.clj:995-997`) + 1 per pending Var (`:747-754`) | **0** (goals note `:131`) | §4 form 3 (count `m/schema`/`m/function-schema` provider calls) |
| Per-call work in the armed wrapper | 11 items (data-pack-c1 §1) | 5: rest-arg seq, `m/-instrument` input/output/guard, `try` frame | read of `arm-var!` |
| Entries in the second cache holder `:seon.schema.projection/compiled` | **5,589** on `default` (§4 form 2) | 0 — holder deleted (§6 C3) or ≤ 1,555 arity descriptors if C3 is rejected | `(count @(:seon.schema.projection/compiled p))` |
| Datalog queries per call-preparation plan | 8 + 3 prepared-symbol queries + 1 contract-t query | 0 (plans derive from the compiled contract at snapshot time) | read of `plan-for` |
| Armed Vars | 1,570; arity rows 1,684; shape rows 5,141 (§4 form 1) | unchanged; shape rows re-stamped at the RESET | — |

Anything above ~2 s after this lane is an algorithm finding: the only
O(program) steps that survive are the one boot/publication compile and the
worker's one packaged acquisition (`arm.clj:35-46`).

## 2. The data flow (astra reviews this first)

| Mechanism | Data | Computed when | Carried where | Recomputed on | Proportional to |
|---|---|---|---|---|---|
| Generation (forms, contracts, sealed registry, dependency graph) | `{schema-key form}`, `{sym contract}`, `mr/fast-registry` | once: publication (B1's `source/database` `source.clj:151-162`), cluster boot (`cluster.clj:1677`), worker start (`arm.clj:40`) | `(meta db)` `:seon.schema/projection` via `db/carry-projection-state`; SCI ctx `:seon.schema/projection`; env state | one declaration changes → `projection-with-schema` / `-function-contract` retains unaffected roots (`schema.clj:2811, 3231`) | the changed declaration + reverse closure |
| The seam `projection-from-database` | the carried value | never (a read) | — | — | O(1); a value without a carried projection is the BOOT case: it loads once (A1-2) and, after B1 names its loader, refuses (A1-2b) |
| Armed wrapper | compiled contract (`mr/schema registry sym`), `m/-function-info` per arity, `::declared` permissions, `base?`/`refusal?` validators, policy | at arm time, once per generation | closure on the Var root; metadata `:seon.instrument/{var,authored,contract,definitions}`, `::interpreted-original` (B2 reads it, `eval.clj:2115`) | `current-wrapper?` (`:844-858`) sees a changed contract or referenced definition | the changed contracts |
| SCI-side contract install | same wrapper via `wrap-interpreted` | `install-row!` (`eval.clj:938`, base ctx at acquisition; agent fork at candidate accept `turn.clj:3227`) | `sci/bind-root!` on the ctx's Var (`utils.cljc:362-379`) | a redefinition row | one Var |
| Call-preparation snapshot | supplied-default rows + **plans for every suppliable symbol** | on row change (`watch!` listener, `:563`) or a newer basis at the call (`current-snapshot` `:534`) | the ctx's `call-preparation/carrier` atom | rows or generation change | O(rows) + O(contracts × arities × slots) form comparisons ≈ 10 K, ms |
| Shape fingerprint | SHA-256 of normalised `m/ast` + dependency fingerprints | at publication for each changed declaration (`fn.clj:2620`, B1) | `:seon.schema.shape/fingerprint` rows | the declaration | one form's AST |
| Schema admission (hook) | findings over `{path source}` + the carried forms | on an edit | request → findings, no state | — | the edited file's declarations |
| Error text | `me/error-message` with `{:errors seon-nouns}` | at refusal render | the problem map | — | one problem |

Trust boundary: the wrapper trusts the generation it was armed in. A call
crossing clusters is validated by the CALLEE's generation, which is the
generation its Var was armed under — the per-call projection scan was
choosing among worlds at call time, exactly the fetch §2.1 forbids.

## 3. Reading list — open before editing

| Open | Guarantees |
|---|---|
| `reference-code/malli/src/malli/registry.cljc:17-22` `fast-registry` | `-schema` is `HashMap.get`; a sealed registry answers by lookup, compiles nothing |
| `registry.cljc:81-93` `lazy-registry`, `:97-102` `schema`/`schemas` | memoised per identity; `mr/schema` is the read |
| `core.cljc:2550-2573` `schema` | on a compiled Schema it is identity; `m/properties`/`m/children` are field reads |
| `core.cljc:2193-2206` `-function-info`, `:2264-2296` `-function-schema` `-instrument-f` | `:min :max :arity :input :output`; the `:function` schema dispatches by arity count for us, calling `report ::invalid-arity` itself |
| `core.cljc:2771-2798` `entries` | keys and `:optional` come off the compiled node as `MapEntry`s |
| `core.cljc:2848-2880` `from-ast`/`ast` | canonical normal form; **keeps** `:order`, `{:closed false}`, `{:optional false}`; references stay `{:type ::m/schema :value <key>}` (§4 form 3) |
| `core.cljc:3118-3139` `-instrument` | takes `:schema` compiled, `:scope`, `:report`; `(schema options)` on a Schema passes through |
| `core.cljc:268, 345-361` `-memoize`, `-create-cache`, `-cached` | every Schema caches its own validator/explainer; a second Seon validator cache is a duplicate |
| `error.cljc:44-172` `default-errors`, `:288-305` `error-message` | lookup ORDER: schema props → type props → `(errors type)` → `(errors (m/type schema))`; a collection problem carries `:type ::m/invalid-type`, so a `:vector` entry is reached only for a `:type`-less problem |
| `instrument.clj:18-43` `-strument!` | global `m/function-schemas`; why the Seon wrapper stays (goals note `:368`) |
| `sci/impl/utils.cljc:362-379` `bind-root!` | copies a Var whose `:sci/generation` differs from the ctx's, stamping the ctx's |
| `src/seon/schema.clj:454-503` `projection-registry` | the landed idiom: compile serially, realise every contract at `:501-502`, seal |
| `schema.clj:2811-2914` `projection-with-schema`, `:3231-3308` `projection-with-function-contract` | the incremental constructor: recompile the changed key and its reverse closure only |
| `schema.clj:2737-2770` `refuse-projection-source` | the refusal shape the seam's absent case takes |
| `db.clj:249-292` `carry-projection-state`, `carry-derived-projection`; `:1219-1225` `carried-projection` | how a value gets and answers its projection (A2 owns; A1 reads) |
| `sci/eval.clj:694-709` `install-function-contract!`, `:2100-2182` `base-bindings`/`regenerate-agent-context!` | B2's half of the arming interface |
| `call_preparation.clj:424-520` `snapshot`, `:534-561` `current-snapshot`, `:1099` `supply`, `:1277` `prepare`, `:1371` `hook` | the surviving seams; supplier rows are facts |

## 4. REPL protocol

Access: `.codex/config.toml` → `bin/mcp-server` → `seon.dev.mcp` `eval_clj`
(`mode "jvm"`, `read_only true`) against `default`; one evaluation in
flight; `mode "sci"` never for measurement. A mutating probe uses
`bin/seon --root tmp/a1-root start a1` (directory created first, downed and
deleted in the same turn).

**Form 1 — premise and cost** (evaluated 2026-09-21, 703 ms total):

```clojure
(let [db (seon.db/db (seon.operator/connection "default"))
      carried (seon.db/carried-projection db)
      t0 (System/nanoTime) p (seon.schema/projection-from-database db)
      ms (/ (- (System/nanoTime) t0) 1e6)
      t1 (System/nanoTime) p2 (seon.schema/projection-from-database db carried)
      ms2 (/ (- (System/nanoTime) t1) 1e6)]
  {:rebuild-ms ms :rebuild-with-reusable-ms ms2
   :forms (count (:seon.schema.projection/forms p))
   :contracts (count (:seon.schema.projection/function-contracts p))
   :carried? (some? carried)
   :same-forms? (= (:seon.schema.projection/forms carried) (:seon.schema.projection/forms p))
   :same-contracts? (= (:seon.schema.projection/function-contracts carried)
                       (:seon.schema.projection/function-contracts p))
   :fingerprint-equal? (= (:seon.schema.projection/fingerprint carried)
                          (:seon.schema.projection/fingerprint p))
   :arities-datoms (count (datahike.api/datoms db :aevt :seon.fn/arities))
   :shape-rows (count (datahike.api/datoms db :aevt :seon.schema.shape/fingerprint))
   :instrumented (count (seon.instrument/instrumented))})
;; => {:rebuild-ms 588.990958 :rebuild-with-reusable-ms 77.627833 :forms 3337
;;     :contracts 1555 :carried? true :same-forms? true :same-contracts? true
;;     :fingerprint-equal? true :arities-datoms 1684 :shape-rows 5141
;;     :instrumented 1570}
```

The carried value also holds 23 keys including a `:seon.schema.projection/compiled`
atom with **5,589** entries (form 2). `mr/schema` measured 2,412 ns per read
over 1,000 unwarmed iterations — re-measure at 10⁶ before reading anything
into it; a sealed `HashMap.get` should be tens of ns.

**Form 2 — the compiled contract answers argument structure** (3 ms):

```clojure
(let [p (seon.db/carried-projection (seon.db/db (seon.operator/connection "default")))
      reg (:seon.schema.projection/registry p)
      c (malli.registry/schema reg 'seon.call-preparation/supply)
      info (malli.core/-function-info (first (malli.core/-function-schema-arities c)))
      slots (malli.core/children (:input info))]
  {:compiled? (malli.core/schema? c)
   :identity-stable? (identical? c (malli.registry/schema reg 'seon.call-preparation/supply))
   :info (dissoc info :input :output) :input-type (malli.core/type (:input info))
   :slot-forms (mapv malli.core/form slots)
   :entries-of-first-map (some->> slots (some #(when (= :map (malli.core/type (malli.core/deref-all %))) (malli.core/deref-all %)))
                                  malli.core/entries (take 3)
                                  (mapv (fn [e] [(key e) (malli.core/properties (val e))])))})
;; => {:compiled? true :identity-stable? true :info {:arity 4 :max 4 :min 4}
;;     :input-type :cat
;;     :slot-forms [:seon.call-preparation/snapshot :seon.env/environment
;;                  :seon.call-preparation/slot :seon.fn/sym]
;;     :entries-of-first-map [[:seon.schema/projection {:optional true}]
;;                            [:seon.call-preparation/supplied-defaults nil]
;;                            [:seon.call-preparation/validators nil]]}
```

**Form 3 — `m/ast` and the error-message lookup order** (4 ms):

```clojure
(malli.core/ast (malli.core/schema [:map {:closed false} [:b {:optional false} :int] [:a :int]]))
;; => {:type :map :properties {:closed false}
;;     :keys {:a {:order 1 :value {:type :int}}
;;            :b {:order 0 :properties {:optional false} :value {:type :int}}}}
(malli.core/ast (malli.core/schema [:map [:x :seon.error/at]] {:registry reg}))
;; => {:type :map :keys {:x {:order 0 :value {:type :malli.core/schema :value :seon.error/at}}}}
(let [problem (first (:errors (malli.core/explain [:vector :int] 1)))]
  [(malli.error/error-message problem {:unknown false})
   (malli.error/error-message problem {:unknown false
                                       :errors (assoc malli.error/default-errors :vector {:error/message {:en "a vector"}})})])
;; => ["invalid type" "invalid type"]   ; the ::m/invalid-type entry wins at step 3
(malli.error/error-message (first (:errors (malli.core/explain :int "x"))) {:unknown false})
;; => "should be an integer"            ; simple schemas carry no :type, step 4 answers
```

**After each commit**, re-run form 1 and the counting form below; paste
both rows in the landing note.

```clojure
;; compiles during one arming pass over an unchanged generation — count, never time
(let [n (atom 0)]
  (with-redefs [malli.core/schema (let [f malli.core/schema] (fn [& a] (swap! n inc) (apply f a)))
                malli.core/function-schema (let [f malli.core/function-schema] (fn [& a] (swap! n inc) (apply f a)))]
    (seon.instrument/apply! {:seon.config/on-core-error :panic
                             :seon.sci.admit/caps (seon.config/result-caps seon.config/defaults)
                             :seon.schema/projection (seon.db/carried-projection (seon.db/db (seon.operator/connection "default")))}))
  @n)                                   ; acceptance: 0
```

## 5. The work, ordered as commits

Each commit is path-limited, net-negative or a named feature, and proven
loadable: `clojure -M -e "(require 'seon.schema 'seon.instrument 'seon.call-preparation 'seon.fn.schema-shape 'seon.schema.admission 'seon.test.arm)"`.
Fork commits are pushed before the deletion they enable.

| # | Commit | Delete | Build / convert | Spans |
|---|---|---|---|---|
| A1-1 | **The wrapper closes over its generation** | `supplied-projection` `:604-619`, `request-member` and its 70-line union `:572-603`, `boot-wrapper` delay `:874-877`, `bootstrap` `:995-997`, `contract-digest` `:871-873` + meta key `:896`, the per-wrapper recompile `:747-754` (`compilable-form` + `bind-contract-predicates` + `m/schema`), the arity scan `:778-786` | `compiled-wrapper` reads `(mr/schema (:seon.schema.projection/registry projection) sym)`; per-arity permission is computed once and handed to `m/-instrument` per arity (the `:function` schema's own dispatch, `core.cljc:2280-2294`, replaces our scan); `arm-var!` installs `(fn [& args] (apply wrapped args))` with the metadata `current-wrapper?` reads. `restore!`'s `declaration-projection` at `:1122` becomes a `:seon.schema/projection` member of the captured `state` — name it, no silent keep. Leave `arm-var!` clean for C1's sample | `instrument.clj:565-620, 734-819, 860-896, 927-1021, 1110-1135` |
| A1-1b | **`wrap-interpreted` reads the contract from the registry** | the `spec-edn` parameter (`:470-522`); the `edn/read-string spec-edn` per install | contract = `(mr/schema registry sym)` — the row's `:seon.fn/spec` is already in the projection `install-row!` advanced (`eval.clj:903`). The one caller is `sci/eval.clj:702`: convert it in this commit if `git status` shows the file free (it was free on 2026-09-21); else STOP and name it. **Contract with B2 (their half):** A1 promises the wrapper is a pure function of (compiled contract, original, policy), idempotent through `::interpreted-original` (`:413-418`), and never reads or writes `:sci/generation`; B2 decides where `bind-root!` runs (base vs fork, data-pack-b2 Q4) and deletes `same-program-root?` + `base-bindings` once program Vars are armed only at the base | `instrument.clj:470-522`, `sci/eval.clj:694-709` |
| A1-2 | **The seam reads the carried value** | `derive-projection-from-database` `:2771-2787`; `projection-from-rows` `:2546-2736` collapses to one ≈40-line row loader (rows → forms/contracts/admissions → `build-projection`); `projection-rows`/`-admissions` `:2514-2545` stay as that loader's two reads | `projection-from-database` = `(or (db/carried-projection db) (load-projection db))`; the reusable arity is deleted (a carried value is by definition the reusable one). All 43 callers read the carried value without being touched (§5b). `portable-string-hash` `:695-697` → `seon.id/id` truncation; bump `projection-fingerprint-version` `:806` (in-memory only, no reset) | `schema.clj:695-697, 806, 2514-2810` |
| A1-2b | **Absent is a refusal** (after B1 names its loader) | the load fallback inside the seam | `projection-from-database` returns `refuse-projection-source`-shaped `error.refusal/diagnostic` naming the caller; the 7 boot sites (§5b) call the loader by name. Lands only once `cluster.clj:1677,2093,3251,3269`, `cluster/source.clj:160,267`, `cluster/registry.clj:236` are converted by B1/A2 | `schema.clj` seam; foreign files listed |
| A1-3 | **The classpath fallback is a refusal** | `schema.clj:929-1085` (frame walking, `clojure.basis`, `decade?`, `!fallback-counts`, `warn-classpath-fallback!`); `candidate-forms`' last arm `:1099`; `schema/edn.clj:361-390` `packaged-population-cache` + `forget-packaged-population!` | `packaged-forms` keeps the stamped resource read (`edn.clj:330-360`); `declaration-population` refuses with `error.refusal/diagnostic`. Explicit `(declaration-projection (schema.edn/packaged-forms))` calls are constructor calls and stay; the zero-arity `declaration-projection` is deleted with its callers named for their owners: `schema/datahike.clj:509,555` (A2), `cluster/source.clj:408` (B1) | `schema.clj:929-1100, 1101-1125, 1227-1258`; `edn.clj:361-390` |
| A1-4 | **Config admission per declaration** | `assert-config-display!` `:1206-1225` and its call at `:1237` (a `structural-schema` compile of 3,337 forms per constructor call) | the check reads `(m/properties (mr/schema registry k))` for the ONE key inside `register!` (`:1545`) and inside `admission/admit`'s `compilation-finding` — O(edit). This is the measurable half of "config compiled 35 times" | `schema.clj:1206-1258, 1545-1611` |
| A1-5 | **Argument structure off the compiled contract; plans live in the snapshot** | `arity-query`, `positional-query`, `argument-shape-query`, `argument-validators`, the four map-entry queries `:604-713`; `prepared-positional/entry/named-entry-query` + `prepared-symbols` `:329-423`; `contract-transaction` `:591-602`; the `plan` cache `:1004-1054`; `by-fingerprint` `:322`; the four `snapshot`/`current-snapshot` inline guards (`:557, 583, 1400, 1404`) per D12 | `plan-for` takes `(mr/schema registry sym)`: arities = `m/-function-schema-arities` → `m/-function-info`; slots = `(m/children input)`, rest? = child type ∈ `#{:* :+ :? :repeat}`; entries = `(m/entries (m/deref-all slot))` with `(:optional (m/properties v))`. A slot is suppliable when `(= (m/form slot) schema-key)` or `(= (m/form (m/deref-all slot)) (m/form (mr/schema registry schema-key)))` — compiled-form equality replaces fingerprint joins. `snapshot` derives plans for every prepared symbol once; `hook` does one `get`. Supplier rows `:149-320`, `supply`, `prepare`, `hook` unchanged | `call_preparation.clj:322-423, 591-748, 837-1054, 1371-1417` |
| A1-6 | **Fingerprint off `m/ast`** — **RESET NEEDED** | `split-schema-form`, `canonical-map-entry`, `canonical-form` `:21-56`, `map-properties` `:14-17` | `authored-form` = `(normalize (m/ast compiled))` where `normalize` is one `clojure.walk/postwalk` dropping `:order`, `{:closed false}`, `{:optional false}` (form 3 proves all three survive `m/ast` and that references stay names, so `form-fingerprint`'s dependency composition `:100-113` is unchanged). Bump `normalization-revision` `:10-12`. Every `:seon.schema.shape/fingerprint` row changes: record the commit id as RESET NEEDED | `schema_shape.clj:10-56, 67-81, 100-113` |
| A1-7 | **Fork: the eight missing error types** (pushed first) | `error.clj:977-1000` `schema-expectation` (B3's file — delete only if `git status` shows it free; else list) | `default-errors` gains `:vector :sequential :map :set :tuple :and :or :fn` in Malli's sentence idiom. Seon's grammar (ERR:148-168, "expected a vector") is a 13-entry DATA overlay `seon.error/nouns` passed as `{:errors (merge me/default-errors nouns)}` from `explain-problem` (`:1020`), which already dissocs `:type` (`:983`, `:1046`) so step 4 of `error-message` answers. Decision: option (i) of the pack — fork in Malli's grammar, nouns as data in Seon, no `case`, no recursion. The `:and`/`:or` overlay entries compose children through `me/error-message` with the same options | `error.cljc:44-172`; `error.clj:977-1060` |
| A1-8 | **Admission is pure over the carried forms** | `schema-files`, `default-schema-directory`, `read-declarations`, `default-registry-excluding`, `canonical-path` `:71-158`; the second raw walker `form-properties`…`schema-nodes` `:159-183`; `-main` `:438-443` (the child-JVM path has no cluster and would need the classpath fallback A1-3 deletes) | `admit` request = `{::sources {path source} ::forms carried-forms}` → findings; house findings walk `(m/walk (structural-schema form) …)`; `compilation-finding` compiles only the candidate keys against `(mr/composite-registry candidate carried-registry)`. **B1 rewires `bin/seon-hook:498,503`** to send the carried forms of `default` and to report "admission unavailable" when no live process answers; A1 lands the seam and stops | `admission.clj:71-183, 375-443` |
| A1-9 | **Small deletions** | `byte-array?`/`sha-256` `schema.clj:761-772` (15 call sites → `clojure.core/bytes?`, `seon.id/sha-256`; `register-core-predicate!` re-points); `with-compiled-cache` `:357-385` and `projection-cache` if §6 C3 holds | — | `schema.clj:357-417, 761-772, 1153-1194` |

### 5b. The 43 `projection-from-database` callers, classified (verified by grep)

| Class | Sites | After A1-2 |
|---|---|---|
| The seam | `schema.clj` ×4 | rewritten |
| Boot / adoption (build once per cluster construction) | `cluster.clj:1677,2093,3251,3269`; `cluster/source.clj:160,267`; `cluster/registry.clj:236`; `db.clj:286` (`carry-derived-projection`, commit values) | read carried when present; the loader by name after A1-2b. Owners B1 / A2 |
| Running code, already carried-first | `fn.clj:2778`, `config.clj:797`, `error.clj:1161`, `fn.clj:831`, `turn.clj:1235`, `sci/eval.clj:824,2233,2326` | their `or` fallbacks become dead: 8 one-line deletions for the owners |
| Running code, rebuild per call | `error.clj:1241,1602,1697,1912,2150,2194`; `fn.clj:1662,2678,3055,3479`; `sci/eval.clj:853,903,944,988,2234,2371,2377`; `schedule.clj:613`; `cluster/wake.clj:415`; `reconcile.cljc:316`; `test.clj:1587`; `test/runner.clj:1506`; `turn.clj:1235` (reusable arity) | read the carried value without an edit; `runner.clj:1506` deliberately rebuilds to compare against an edited in-memory projection — it needs the loader by name |

If A1-2 makes any of them refuse, the refusal is the finding; no fallback.

### 5c. The eleven inline error guards in A1's files

All in `call_preparation.clj`: `:240, 313, 414, 421, 438, 557, 583, 725, 740, 1400, 1404`.
Four whose callee is `snapshot`/`current-snapshot` are converted in A1-5
(the callee's contract declares its union; the branch tests a required
member). Seven name `seon.db/q`, `db` or `history`: their unions live in
`db.clj` (A2) — list them in the landing note, do not add a predicate.

## 6. Better than the floor — probe first

| Candidate | Probe that decides | If it holds |
|---|---|---|
| **C1 Plans in the snapshot, no plan cache, no `contract-t`** | count suppliable symbols on `default`: `(count (:seon.call-preparation/prepared-symbols (call-preparation/snapshot db p)))`; time one plan derivation from the compiled contract | delete `plan` (`:1004-1054`), `contract-transaction`, the `[sym contract-t basis-t]` key; snapshot recomputes plans in ms on a row or generation change. Expected: tens of symbols |
| **C2 Supplier matching by compiled-form equality** | for every supplied-default row, `(m/form (m/deref-all slot))` vs `(m/form (mr/schema reg key))` across all contracts equals today's fingerprint join set | `call_preparation` reads no `:seon.schema.shape/*` row at all; fingerprints remain the shape rows' identity for `my.program`/`program.cljc` only |
| **C3 Delete `with-compiled-cache`** | after A1-1, `(count @(:seon.schema.projection/compiled p))` on a fresh generation and the eight `projection-cache-value` sites in `db.clj` (`:1319, 3355, 3376, 3569, 3628, 4060, 4126, 4140`, A2) — what do they store? If only validators: `m/validator` on the retained Schema is already cached (`core.cljc:345-361`) | holder gone; `projection-cache-value` becomes `(derive-fn)` until A2 converts its eight sites to `m/validator`. If the sites store selector-derived projections, keep the holder for that one key family and say so |
| **C4 `m/-instrument`'s arity dispatch hosts the facet check** | wrap each arity's `original` with its own `::declared` permission check and hand the `:function` schema to one `m/-instrument`; count wrappers and confirm `::invalid-arity` reports arrive through `:report` | our arity scan and `arities` vector disappear from the call path; one `-instrument` per Var |

A probe that shows a smaller cut than §5 wins; record the evidence and the
rejected candidates in the landing note.

## 7. Tests

The lane runs only the tests reaching its change, in process:
`seon.test/check` (`test.clj:1901`) or `seon.test/run-owned` (`:576`) with
`[{:seon.db/connection conn :seon.test/var #'the-test}]`; `bin/test-fast
--paths <owned> -- <ns>` is the fallback. Never a suite, never a cold gate.

| Group | Members | Disposition |
|---|---|---|
| Benchmarks as deftests | `schema_test.clj:41,305,1468`; `instrument_test.clj:1195,1223` | delete (~150 lines); measurement is §4's forms in the landing note |
| Pinned to deleted mechanisms | `instrument_test.clj:944-945,968-969,979` (contract-digest); `:960`; `:1091` | delete with the mechanism (~90) |
| Class B "a refusal names the offending argument" | `instrument_test.clj:280,612,644,655,687,721,770,1339,1357,1425`; `refusal_grammar_test.clj:13,29,44`; `schema_test.clj:806` | collapse 14 → 2 (host wrapper, SCI) |
| Class A "arming is idempotent" | `instrument_test.clj:471,866,878,913,960,1064,1117,1285`; `registry_isolation_test.clj:51` | collapse 9 → 3: idempotent; re-arm on contract change; re-arm on referenced-declaration change |
| Class C "the population resolves once" | `schema/edn_test.clj:77,107,122,185`; `schema/declaration_population_test.clj:63,82`; `schema/datahike_test.clj:441` | delete 5; unconstructable after A1-3 |
| Datalog-structure tests | `call_preparation_test.clj` (826 lines, 17 deftests) members asserting query rows or plan cache keys | delete; behaviour tests over `prepare`/`hook`/`supply` stay and gain one: "a plan derives from the compiled contract with no shape row present" |
| Fingerprint | `fn/schema_shape_test.clj` (191) | rewrite the canonical-form cases as `m/ast` normaliser cases: entry order, `{:closed false}`, `{:optional false}`, reference-as-name |
| Pinned to private helpers | 20 tests (`schema_test.clj:1265`; `schema/edn_test.clj` ×8 on `#'schema.edn/resource-population`) | rewrite against behaviour |
| **One class regression lands with the cut** | new, in `instrument_test.clj` | an arming pass over an unchanged generation performs ZERO named-declaration compilations, counted at `m/schema`/`m/function-schema` (§4 counting form) |

Time escapes in this area (all six verified):

| Declaration | Becomes |
|---|---|
| `schema/admission_test.clj:126-127` 300,000 ms | bound removed with A1-4/A1-8; publish a SMALL fixture population, never `src/` |
| `schema/datahike_test.clj:243-244` 25,000 ms | kept: 80 generative storage cases (A2's file; reason unchanged) |
| `schema/datahike_test.clj:360-361` 120,000 ms | bound removed: the cost was the seam rebuild |
| `schema/projection_acquisition_test.clj:37-38` 10,000 ms | deleted with `projection-from-rows` (A1-2) |
| `schema/projection_acquisition_test.clj:94-95` 10,000 ms | bound removed: one carried generation |
| `schema_redeclare_test.clj:72-73` 300,000 ms | kept: a real cold boot; tighten to the measured time |

Test corpus in A1's files: 7,233 lines → target **≈ 5,000**.

## 8. Size target, done, landing note, stop rules

| File | Before | Audit floor (area) | Target | Reasoning for the gap |
|---|---:|---|---:|---|
| `schema.clj` | 3,904 | 1,430–1,960 net across the Malli area | 3,150 | −210 fallback, −250 rows→seam, −60 holder, −32 config/sha; the registration-delta world (`:3358-3480`), pulled-form (`:2915-3190`) and identity-only/matching-shapes (`:3696-3886`) are other lanes' questions and stay |
| `schema/admission.clj` | 443 | | 250 | file walker, second form walker, `-main` |
| `schema/edn.clj` | 650 | | 590 | cache + forget; the stamped resource read stays |
| `instrument.clj` | 1,138 | | 750 | per-call scan, union, bootstrap, digest, recompile, arity scan |
| `call_preparation.clj` | 1,417 | | 700 | 12 queries, plan cache, prepared-symbol queries, guards |
| `fn/schema_shape.clj` | 468 | | 390 | walker → 8-line normaliser; row codec stays (B1 writes rows) |
| `test/arm.clj` | 254 | | 240 | unchanged except `arm-contracts!`'s handed projection |
| **A1 src total** | **8,274** | | **≈ 6,070 (−27 %)** | floor met and exceeded by C1–C3 |

**Done:** form 1 reports the seam as a read; the counting form reports 0;
`create-base` under 700 ms; HEAD loads after every commit; the reaching tests
green in process with the tally pasted; the cold proof named as owed to the
orchestrator. **Landing note:** `docs/prds/agent-platform/landing/lane-a1.md`
with the §4 forms and values before/after, `git diff --stat` per commit,
RESET NEEDED with the A1-6 commit id, the seven `db.clj` guard sites, the
33 now-redundant callers and 8 dead `or` arms by `file:line`, the §6 probe
results including rejected candidates, and any span whose lines moved.

**Stop rules.** (1) A held file: `git status --short` before every owned
edit; on 2026-09-21 `src/seon/cluster.clj`, `src/seon/fn.clj` and two of
their tests were dirty — never A1's to touch. (2) An unsettled design: three
options (guarantee, cost, what we give up) in the landing note, then stop.
(3) A public Var retirement with foreign callers: retirement and conversion
are one slice; `wrap-interpreted`'s arity change (A1-1b) and A1-2b are the two
that cross into `sci/eval.clj`, `cluster*.clj`, `db.clj`, `error.clj` — land
the one-line conversions only when the file is free, else list. (4) The
profiling sample is C1's: leave `arm-var!` as its hook point.
