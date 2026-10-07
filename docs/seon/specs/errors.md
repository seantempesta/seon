---
type: spec
status: reviewed
created: 2026-10-07
tags: [spec, errors, malli, faults]
---
# Errors: one map, two carriers, one boundary (reviewed 2026-10-07)

What exists: five constructors, a metadata type stamp, value-to-exception round trips at every read, a bespoke
re-description of Malli reports and a 4,778-line error area (`error.clj` 2837, `error/refusal.clj` 830, `fault.clj` 453,
`problems.clj` 658). Data flow after this spec: a producer builds ONE error map with ONE constructor; it returns it when
answering is its job, else throws it as `ex-info` data; the entrant boundary takes it back once with `ex-data`, the
recorder upserts one fault per signature, and the reply carries the map as data. Principal approach: Malli's own shapes
(`:multi` on a type key, the instrument report, `m/explain`, `malli.error`) and Clojure's (`ex-info`, `Throwable->map`).

Owner rulings implemented (2026-10-07): one map with `:multi` variations on its type key, no metadata stamps; one
constructor; two carriers of the same map; error alternatives only in contracts of functions that return errors;
instrumentation everywhere, reporting from Malli's report; errors always reach the agent as data.

Verification legend: **[V]** read or probed today; **[U]** unverified, to be proven in the named slice.

## 1. The error map

```clojure
{:seon.error/type      :my.fs/not-found-error      ; required: the variant (= its schema key); the :multi dispatch
 :seon.error/operation 'my.fs/read                 ; required: who refused; producer default and signature member
 :seon.error/message   "No such file. Path: x.md"  ; required: ex-info needs it; the first thing the agent reads
 ;; optional base members
 :seon.error/fix       "Check the path with my.fs/glob."
 :seon.error/problems  [...]                       ; Malli explain errors (§5), only for validation failures
 :seon.error/chain     [...]                       ; Throwable->map :via, data referenced (boundary only)
 :seon.error/frame     [ns fn file line]           ; root cause's first first-party frame (boundary only)
 :seon.error/caller    'my.ns/f                    ; who made a refused call (instrumentation only)
 :seon.error/world     {:seon.agent/id .. :seon.turn/id ..}  ; references, attached by the boundary
 ;; variant members, fully namespaced
 :my.fs/path "x.md"}
```

Required keys and why each survives:

| Key | Why | Today |
|---|---|---|
| `:seon.error/type` | Selects the variant schema, the render pair and the signature. Replaces the `vary-meta :seon.error/declared-schema` stamp (99 lines in `src` mention it [V]), which `merge`/`into`/`assoc` across a boundary silently drop. | absent; `refusal.clj:787` stamps meta |
| `:seon.error/operation` | The refusing definition: the blame default (`refusal.clj:631`), the fault signature member. | required (`seon.error.edn` base) |
| `:seon.error/message` | `ex-info`'s message and the agent's first line; no carrier exists without one. | optional today |

Dropped from required: `:seon.error/at` (442 lines mention it [V]; the recorder stamps the occurrence time, a returned
error rides an evaluation that already has its time) and `:seon.error/layer` (273 lines [V]; the type's namespace says
it). See Q6.

**Variations.** Each variant is the base plus its own members, declared in its owner's schema EDN. Seon's registry has
no `:merge` (probe: `(mr/schema r :merge)` is nil, `:multi` present [V]), so a variant is an `:and`:

```clojure
:my.fs/not-found-error [:and :seon.error/base [:map [:my.fs/path :my.fs/path]]]

;; a function that RETURNS errors lists exactly its alternatives as one :multi
:my.fs/tool-error
[:multi {:dispatch :seon.error/type}
 [:my.fs/not-found-error :my.fs/not-found-error]
 [:my.fs/path-refused-error :my.fs/path-refused-error]
 ...]
my.fs/read  [:=> [:cat :my.fs/read-request] [:or :my.fs/read-result :my.fs/tool-error]]
```

Probed in a scratch JVM (malli at the pinned `reference-code/malli` 43c6dca8): the `:multi` validates a variant,
explains a missing variant member as `{:my.fs/path ["missing required key"]}`, and sits inside `[:or result multi]` [V].
An undeclared type is `::m/invalid-dispatch-value`, which `me/with-spell-checking` already turns into a misspelled-value
suggestion for keyword dispatch (`malli/error.cljc:343-349` [V]). 204 registry keys end in `-error` today [V]; each
gains the `:and :seon.error/base` wrapper by script. The generic `:seon.error/base` is the `::m/default` child where a
boundary must accept any error (Q3).

**Cause chain.** Only the boundary reads a Throwable: `(:via (Throwable->map t))`
(`reference-code/clojure/src/clj/clojure/core_print.clj:473-482`) gives `{:type :message :data :at}` per link, outermost
first [V, probed]. Each link's `:data` passes through `refusal/references`; `:trace` is filtered once with
`first-party-frame?` (`refusal.clj:173`) for `:seon.error/frame`. Suppressed throwables are not in `Throwable->map`;
today `walk-links` follows them (`refusal.clj:441-463`). Keep one line appending the root's `.getSuppressed` links only
if a test needs it [U].

**References replace copies.** The one piece with no library equivalent stays: `refusal/references` and its walker
(`refusal.clj:249-406`): a database is its commit id, a connection or SCI context its identity, a large value its
class and size. It runs once inside the constructor over the variant members (nothing else in the map can hold a world)
and once per chain link at the boundary. The whole referenced map larger than `:seon.config.error/max-evidence-bytes` is
stored once as a blob by the recorder (§6), never clipped here.

## 2. The constructor and the two carriers

`seon.error.refusal/error` is replaced in place (it already claims to be "THE one constructor", `refusal.clj:745`):

```clojure
(defn error
  "The one error map. `type` names its variant schema; `data` holds its members."
  {:malli/schema [:=> [:cat :qualified-keyword :qualified-symbol :string [:? :map]] :seon.error/base]}
  ([type operation message] (error type operation message nil))
  ([type operation message data]
   (assoc (references (operation-projection) data)
          :seon.error/type type :seon.error/operation operation :seon.error/message message)))

(defn fail!
  "Throw the error map as ex-info data: the carrier for functions whose job is not to answer."
  [type operation message & [data]]
  (throw (ex-info message (error type operation message data))))
```

No validation in the constructor: the producer's output contract (armed on every function) checks the variant on the
returning carrier, and the boundary's reply contract checks the thrown one. That deletes `construction-refusal`,
`checking-projection` and the explainer seam (`refusal.clj:215-248, 726-792`). `fail!` is the constructor plus `throw`,
as Malli pairs `-exception` with `-fail!` (`malli/core.cljc:203-207` [V]).

**Rule.** Return the map when answering is the function's job: agent tools (`my.*`), validators and checks whose
result IS the verdict (merge checks, gates, write-back digests). Throw it everywhere else. A returning tool whose
failure is detected deep inside catches its own throws once at its top with `(or (error-of e) (throw e))`, where
`error-of` is `(let [d (ex-data e)] (when (:seon.error/type d) d))`. No other layer catches to convert or rethrow.

| # | Before | After |
|---|---|---|
| 1 | `my/program.clj:111-142` `caller-data`: five `(let [r (db/pull ...)] (if (:seon.db/invalid-read r) (throw (ex-info (:seon.error/message r) r)) r))` blocks; 119 such rethrows in `src` (28 in `my/program.clj`) [V] | `(db/pull database selector e)`. `db/q` no longer catches Datahike's `ex-info` (`db.clj:2840-2844` → `dependency-error`); it propagates to the boundary with Datahike's own `:error` data in the chain. |
| 2 | `db.clj:2794` `(refused-read query-or-database 'seon.db/q)`: an error value accepted as a database input (`db.clj:2790` admits `:seon.error/value`) and passed on | Deleted: the armed input contract refuses a non-database at the call. `read-refusal?`, `refused-read`, `schema-refusal`, `dependency-error`, `error-value`, `diagnostic` (`db.clj:173-256`) go. |
| 3 | `fs/jvm.clj:55-59` `refuse!` throws `flat-error` (`:41-53`, which itself calls the validating constructor), then `fs/jvm.clj:501` catches every Throwable and rebuilds a value through `error-value` (`:61`) | `my.fs/read` answers: detection sites (`fs/jvm.clj:109,165,172,182`) return `(error :my.fs/path-refused-error 'my.fs/read "The path is outside every declared filesystem root." {:my.fs/path path})`; the catch at `:501` keeps only host IO exceptions. |
| 4 | `turn.clj:5107` `(fault/fault! env (ex-info (:seon.error/message unbound) unbound) {...})`: a value wrapped in an exception only to be recorded (also `turn.clj:5432, 5719, 5868`, `render/web.clj:2280`) | `(fault/record! env unbound)`: the recorder takes the map. |
| 5 | `instrument.clj:926-931` report: `(throw (ex-info (:seon.error/message value) (with-meta value {:seon.error/declared-schema declared-schema})))` after `boundary-refusal` + `refusal?` validator + `minimal` fallback (`:844-924`) | `:report (fn [kind data] (throw (ex-info msg (contract-error sym kind data))))` (§5). |
| 6 | `sci/kernel.clj:826-827` reads the deepest structural ex-data (`refusal/refusal`, `refusal.clj:804-830`) and tests three keys by hand, then `:828-883` re-attributes and re-chains it | `(error/caught t)` (§3); the SCI stacktrace member is added by the kernel's one call. |

Call-site sweep: `refusal/diagnostic` has 225 call sites and builds `{:seon.error/at (Date.) :seon.error/layer ..}`
literals [V]; a script rewrites each to `(error type operation message data)` taking the type from the declared output
alternative (the 99 `declared-schema` stamps name it today).

## 3. The boundary

The one function, `seon.error.refusal/caught` (~20 lines), turns any Throwable into the error map:

```clojure
(defn caught [^Throwable t]
  (let [{:keys [via trace]} (Throwable->map t)
        own (some #(when (:seon.error/type (:data %)) (:data %)) (rseq via))  ; deepest declared error wins
        frame (first (filter first-party-frame? trace))]
    (cond-> (or own (error :seon.error/exception (or (some-> frame frame-function) 'seon.error/unknown)
                           (or (:message (peek via)) (.getName (class t)))))
      true  (assoc :seon.error/chain (mapv #(update % :data references*) via))
      frame (assoc :seon.error/frame frame)
      true  (merge-absent (producer own t) (world-reference (current-world))))))
```

`producer`, `world-reference` and `attributed` stay (`refusal.clj:595-675`) as the blame rule. Entrants that call it,
each in exactly one catch [V for location; the rewrite is per slice]:

| Entrant | Today |
|---|---|
| One REPL path, evaluate stage | `batch.clj:363` catch → `sci/kernel.clj:787-883` `failure-value`; stage guards `batch.clj:173, 623, 669, 689` |
| MCP | `mcp.clj:390` top catch, `fault-receipt` `:337-347` |
| Flow procs | `flow.clj:197-230` `proc-failed!`, `:845-858` `fault-handed-off!` |
| Boot / operator | `cluster/boot.clj:792-815` `request!`, `:70` boot recording |
| Web | `render/web.clj:2236-2250` `entrance-fault!`, `:2280` |
| Test runner | `test.clj:2346` `core-fault!` |
| Agent graphs | `cluster/agent.clj:1190, 1442, 1639` |

`kernel/failure-value` shrinks to `caught` plus the SCI location (`kernel.clj:763-786` stays). A fault already recorded
in this propagation keeps its receipt in the chain (`fault.clj:316-340`) and is not recounted.

## 4. Contracts

Only functions that return errors list error alternatives: the `my.*` tools, validators, checks and the boundary
functions themselves (`caught`, `record!`). Every reader's `[:or value :seon.db/error-result]` (151 references to
`:seon.db/error-result` in `src`+`resources` [V]) narrows to `value`. `error?` becomes `(contains? v :seon.error/type)`;
`let-ok` (`refusal.clj:793`, 2 uses [V]) goes with the round trips.

## 5. Instrumentation

Arming stays on every function (`instrument.clj` `apply!`, `arm-var!`, `wrap-interpreted`, call preparation). The
report becomes one function over Malli's report (`malli/core.cljc:2232-2244` [V]: `::invalid-input {:input :args
:schema}`, `::invalid-output {:output :value :args :schema}`, `::invalid-arity {:arity :arities :args}`,
`::invalid-guard`, the fork's `::invalid-schema-at-call {:arm :exception}`):

```clojure
(defn- contract-error [sym kind {:keys [input output args value] :as data}]
  (let [[schema checked] (if (= kind ::m/invalid-output) [output value] [input args])
        problems (some-> (explained schema checked) me/with-spell-checking me/with-error-messages :errors)]
    (error kind sym (str sym " " (name kind) ": " (pr-str (me/humanize {:value checked :errors problems})))
           (cond-> {:seon.error/problems (mapv #(-> (select-keys % [:in :value :type :message ::me/likely-misspelling-of])
                                                    (assoc :schema (m/form (:schema %)))) problems)}
             (= kind ::m/invalid-arity) (assoc :seon.error/arities (:arities data))
             (caller-frame) (assoc :seon.error/caller (caller-frame))))))
```

Spell checking needs a closed map (probe: on Seon's open `:my.fs/read-request` the misspelled key yields only
`missing required key` [V]). `explained` therefore explains the refusal path against `mu/closed-schema` and drops
`::m/extra-key` errors that carry no suggestion, so open maps stay open (Q4). Refusal path only; probe cost of
spell-check plus messages on the real schema: 207 µs per refusal, unarmed, default JVM 2026-10-07 [V].

The error an agent sees for `(my.fs/read {:my.fs/pth "AGENTS.md" :my.fs/max-bytes 0})`, assembled from the real
report and explain output probed on default today (`result/e8eb290e7fccc`):

```clojure
{:seon.error/type      :malli.core/invalid-input
 :seon.error/operation my.fs/read
 :seon.error/message   "my.fs/read invalid-input: [{:my.fs/max-bytes [\"should be at least 1\"], :my.fs/pth [\"should be spelled :my.fs/path\"]}]"
 :seon.error/caller    user/...
 :seon.error/problems
 [{:in [0 :my.fs/max-bytes] :value 0 :schema [:int {:min 1}] :message "should be at least 1"}
  {:in [0 :my.fs/pth] :value "AGENTS.md" :type :malli.error/misspelled-key
   :message "should be spelled :my.fs/path" :malli.error/likely-misspelling-of [[0 :my.fs/path]]}]}
```

Today the same call throws a 2,157-character map (about 540 estimated tokens) with 21 keys whose message names the
missing `:my.fs/path` but never the misspelling, and carries `:seon.error/declared-schema` as metadata [V, probed].

Deleted from `instrument.clj` (spans by top-level form [V]): `problem-path`, `failure-cause` `:172-197`; arglist
re-derivation `:198-273` (Malli reports `:arities`); `supplied-entry-problems` through `violation` `:274-600`;
`problem-location`, `boundary-refusal` `:701-777`; `minimal`, `refusal?`, `refusal` inside `compiled-wrapper`
`:844-924`. About 590 lines out, about 40 in. Kept: `caller-frame` `:130-171`, the arming and restore machinery.
From `error.clj`: `schema-expectation` through `scalar-text` `:865-1023`, `evidence-path` through `call-lines`
`:1867-2010` (including the hand Levenshtein `:1949-1961`; Malli's is `error.cljc:245-280`).

## 6. Fault recording and delivery

A fault is an error the boundary could not hand to an agent that caused it: anything thrown that is not a declared
agent mistake. Minimal model, one upsert per signature:

```clojure
{:seon.error/signature  (id/id [type operation producer (-> problems first :in) (frame-site frame)])
 :seon.error/type .. :seon.error/operation .. :seon.error/producer .. :seon.error/producer-digest ..
 :seon.error.occurrence/count n            ; noHistory, as today
 :seon.error.occurrence/last-at t
 :seon.error/data-blob digest              ; the first occurrence's whole referenced map, once by content
 :seon.error/agent [:seon.agent/id a]}     ; who it happened to; its reverse is the agent's fault list
```

Delivery: when the upsert opens the signature (first time, or first since its resolution), the same transaction adds
one message to the agent of the failing world, else root, its content the value printer's text of the map (as
`message-tx`, `error.clj:1251-1267`, does now). The next context and the MCP reply both read that message and the
receipt. Storage failure keeps today's one rule: the owner's stop request runs and the fault prints through
`print-unrecorded!` (`fault.clj:341-358`). Increment is read-then-write in one `db.fn/call` at the writer [U: confirm
Datahike's `:db.fn/call` on the pinned fork in slice S6].

Goes: `prepare` and the bounded-admission stack `error.clj:183-805`, `commit-call`/`recording`/`occurred-since`
`:1086-1774` (12 existence reads per repeat, `:1366-1388` [V]), the `-valid?` predicates and generators `:2607-2837`,
`fault.clj` folding and cluster copy `:26-315` (Q5). `resolution` (`:1162-1220`) stays if Q5 keeps openings.

## 7. Deletion, conversion and slices

| File | Lines today [V] | After (estimate) |
|---|---|---|
| `src/seon/error.clj` | 2837 | ~450 (renders over `me/humanize`, finding, resolution) |
| `src/seon/error/refusal.clj` | 830 | ~300 (`references` walker, `producer`, `error`, `fail!`, `caught`) |
| `src/seon/fault.clj` | 453 | ~120 |
| `src/seon/instrument.clj` | 1327 | ~780 |
| `src/seon/db.clj` error helpers and read catches | ~150 | 0 |
| 119 rethrows, 111 `read-refusal?` lines, 225 `diagnostic` literals | ~1,100 | ~250 |
| `resources/seon/schemas/seon.error.edn` | 456 | ~120 |

About 5,000 lines out, about 1,900 in; `problems.clj` follows in a later spec. Estimates, not measurements.

Slices; each lands through `bin/lane commit --test`, HEAD boots after each, and each is proven by publishing and adopting
its schema change on a branch of the live store:

1. **S1 Type key.** Add optional `:seon.error/type` to the base; `refusal/error` stamps it from the alternative instead
   of metadata; `fault.clj:420-427` and render selection read the key. Delete every `vary-meta ... declared-schema`.
   Test: T1, T5.
2. **S2 Constructor sweep.** New `error`/`fail!` signature; script the 225 `diagnostic` and 14 `error` call sites; make
   type, operation, message required; drop `:at`/`:layer` from the base. Test: T1, T7.
3. **S3 Boundary.** `caught` with `Throwable->map`; convert the entrants in §3; delete `walk-links`, frame
   classification `refusal.clj:8-214` beyond `first-party-frame?`/`frame-function`, and `refusal/refusal`. Test: T2, T4.
4. **S4 Reads throw.** Delete `db.clj:173-256` and the read catches (`db.clj:320, 1163, 2640, 2844, 2998, 3223, 3280,
   3317, 3330`); script the 119 rethrows and 111 `read-refusal?` lines; narrow the 151 `:seon.db/error-result` unions.
   Test: T2 plus the reaching tests of `my.program`.
5. **S5 Instrumentation.** `contract-error` replaces §5's spans. Test: T3.
6. **S6 Faults.** One upsert per signature; delete §6's spans. Test: T5, T6.
7. **S7 Renders.** `render-ai`/`render-html` over the map and `me/humanize`; delete prose `error.clj:2505-2606`. Golden
   transcripts regenerated once and compared in estimated tokens.

## 8. Tests

One behavior per test, asserted on data:

- **T1 returned error**: `my.fs/read` of an absent path returns a map whose `:seon.error/type` is
  `:my.fs/not-found-error` and validates `:my.fs/tool-error`; nothing is thrown.
- **T2 thrown error reaches the reply**: an SCI evaluation calling a function that `fail!`s gives a reply error `=` to
  the thrown `ex-data` on type, operation and message, with a chain.
- **T3 instrumentation**: the §5 call gives `:malli.core/invalid-input`, operation `my.fs/read`, and a problem at
  `[0 :my.fs/pth]` whose `likely-misspelling-of` is `[[0 :my.fs/path]]`.
- **T4 references**: an error whose data holds a connection carries its identity, not the object.
- **T5 fault upsert**: the same failure twice gives one signature, count 2, one message to its agent.
- **T6 unrecordable fault**: a refused write runs the stop request and prints (keep `fault_test.clj`'s case).
- **T7 undeclared variant**: a returning function answering a type outside its `:multi` is an output refusal naming
  the type (`::m/invalid-dispatch-value`).

Deleted with the machinery they test (per-test triage in each slice): most of `test/seon/error_test.clj` (59
deftests, 1702 lines: normalization, capping, fitting, notice prose, projection keys), `test/seon/error/refusal_test.clj`
(17, frame classification and construction refusals), `test/seon/refusal_grammar_test.clj` (4), the violation-sentence
tests in `test/seon/instrument_test.clj` (57 deftests; arming tests stay), `test/seon/db_error_test.clj` (2) and
`test/seon/error_result_test.clj` (4). Kept and narrowed: `fault_test.clj`, `error/resolution_test.clj`,
`error/attribution_test.clj`.

## 9. Owner decisions (2026-10-07)

1. Type key: `:seon.error/type`.
2. Instrumentation types: Malli's own keywords (`:malli.core/invalid-input` and friends), registered as variants.
3. Variants: each returning function's `:multi` lists its own; boundaries accept `:seon.error/base`.
4. Spelling suggestions: on the refusal path only, explain against `mu/closed-schema` and keep extra-key errors that
   carry a suggestion; valid calls pay nothing.
5. Faults: one upsert per signature with count, first occurrence blob, one message per opening and resolution by
   digest plus a green run; steward, escalation, folding and the branch-to-cluster copy go.
6. No `:seon.error/at` on the map; the recorder stamps the occurrence, a returned error rides its evaluation's time.
7. The constructor lives in `seon.error.refusal` now and merges with `seon.error` after S6.
