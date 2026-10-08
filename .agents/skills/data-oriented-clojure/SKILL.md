---
name: data-oriented-clojure
description: "How to write Seon Clojure: small functions of data, one error map, one-line contracts, one pass, one owner of state, short docstrings. Read before every Seon Clojure change or review; the reference-code examples are the model to copy."
---

# How to write Seon Clojure

The laws are in [CLAUDE.md](../../../CLAUDE.md); this skill shows what code that obeys them looks like. Before
building, open the matching row of
[Read before you build](../../../docs/seon/architecture/reference-code.md#read-before-you-build) and read the library
code it names. Errors follow the reviewed [errors spec](../../../docs/seon/specs/errors.md); agent data follows the
[agent-loop spec](../../../docs/seon/specs/agent-loop.md). Where an owner ruling postdates a spec, the ruling wins: flag
the spec section to its owner. Tests follow the
[clojure-testing skill](../clojure-testing/SKILL.md).

**How to read a rule.** A **Law** restates a CLAUDE.md law or an owner ruling: break it only with the owner. A
**Review prompt** is a question to ask of your diff; the answer can be "keep it" when you can say why. Every rewrite
carries one label:

- **EQUIVALENT** — same result for every input the stated preconditions admit. Check the preconditions first.
- **BEHAVIOR CHANGE** — the result changes; land it with its contract change and its regression.
- **TARGET** — needs an installed change that has not landed (named); do not write it yet.

Examples cite `reference-code/` (paths below drop that prefix) at the revisions Seon's gitlinks record, which the
checkouts matched on 2026-10-07. Re-derive before relying on one: `git ls-tree HEAD reference-code/<lib>` and
`git -C reference-code/<lib> rev-parse --short=10 HEAD`. Pins: babashka-process `43bdd65e18`, clerk `4c25999462`,
clj-kondo `57252e0797`, clj-reload `61c6fa7785`, clojure `3bc2b3e91f`, core.async `dc35f3e0d7`, datahike
`d9121bbbad`, datastar-clojure `1cef624e9e`, edamame `196ea5e4ef`, editscript `b493ccf2e9`, fipp `255a19061f`,
http-kit `f56bbeaf97`, hyperlith `b08a8e8689`, kaocha `8846f91c9b`, konserve `8700ca7a95`, langchain4clj
`889f9e60e3`, malli `43c6dca8d2`, nrepl `f3a044153e`, rewrite-clj `60782e501a`, sci `ce0aa53b9b`. Seon examples are
named by function; find them with `rg` (line numbers drift). A source citation says what the code does, not that it
is loaded: verify installed behavior at the REPL.

## 0. Absent, nil, false and empty

Most unsafe "shortenings" confuse these four. Know which one your code means before rewriting it.

| Value | Means | Test it with |
|---|---|---|
| key absent | no fact (CLAUDE.md: absence is no key, never stored nil) | `(find m k)`, `(contains? m k)` on a map, `(get m k ::absent)` when `::absent` cannot be a legitimate value |
| `nil` | nothing; also what `(:k m)` returns for an absent key | `nil?`, `some?` |
| `false` | a fact whose value is false (`seon.fn/var-entity` stores `:seon.fn/inline? false` on purpose) | `false?`, `boolean?` |
| `[]`, `{}`, `""` | an empty collection or string; truthy | `empty?`; `seq` in condition position |

Rules that follow:

- `(:k m)` in a test position is a **truthiness** test: it treats absent, `nil` and `false` alike. `select-keys`,
  `find` and `contains?` are **presence** tests: they keep a present `nil` or `false`.
- `:or` in destructuring fills only an **absent** key, never a present `nil`.
- `some` returns the first **truthy** result of its function. It cannot return a matching `false` or `nil`.
- `seq` returns a seq or `nil`; `(not (empty? xs))` returns a boolean. They agree only in condition position.
- `:any` can admit nil, false and empty values. A union admits only its alternatives; an optional map entry permits
  absence but validates a present value against its declared schema; an open map permits additional keys. Remove a
  presence or variant guard only when that exact schema makes it redundant (§5).

## 1. Size and decomposition

**1.1 Review prompt: one responsibility per function.** 5-15 lines is a common size; past about 40 lines, ask which
roles are mixed (read, derive, validate, effect). Split by role and name each part. Keep a small helper or a local
closure when its name explains an opaque step or its lexical context is the simplest owner of a callback. Never
promise a line count before you have written the result.

GOOD: `rewrite-clj/src/rewrite_clj/zip/move.cljc:8-26`, each movement is one line composed from two smaller ones.

```clojure
(defn right [zloc] (some-> zloc zraw/right ws/skip-whitespace))
(defn up    [zloc] (some-> zloc zraw/up ws/skip-whitespace-left))
```

SEON: `seon.db/write-owned-values-error` is about 250 lines. Its `charge!` is a callback that `write-entity-value`
invokes, it reads through a traversal memo and it walks ancestors and a post-order worklist. Splitting it is real
design work: name the roles (bounded charging, cycle and owner checks, attempted-write capture) and keep each check.
`seon.test/select`, `seon.test/run` and `seon.sci.eval/evaluate` are each over 350 lines.

**Worked decomposition (EQUIVALENT in result, under the preconditions below).** `seon.cluster/transact-initialization!` mixes, inside one `loop`: reading the
database, refusing a failed readiness read, partitioning ready from waiting (calling `entity-ready?` twice per
entity), building the "missing" refusal, and transacting. Read `seon.cluster/transact-initialization!` for the original; the rewrite below preserves its writer protocol.

After: a readiness-read round, refusal construction and a short effectful shell. The round is not pure: it reads
through Seon's read owner (`entity-ready?` → `lookup-resolution` → `db/pull`), which captures read evidence. With
finite inputs and stable successful reads, the returned nil, transaction groups and order, metadata and refusal payloads
are preserved; readiness-predicate calls are halved. The round's shape is used twice: register it in the owner's EDN
when this lands (shown inline here).

```clojure
(defn- initialization-round
  "One round's verdict on `pending`: a refused readiness read, else the ready and the waiting entities."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :map]]
                  [:or [:map [::refused :map]] [:map [::ready [:vector :map]] [::waiting [:vector :map]]]]]}
  [database pending]
  (if-let [refused (some #(entity-lookup-refusal database %) pending)]
    {::refused refused}
    (let [{ready true waiting false} (group-by #(entity-ready? database %) pending)]
      {::ready (vec ready) ::waiting (vec waiting)})))

(defn- missing-lookups
  "The distinct lookup refs the waiting entities mention, as refusal data."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :map]] [:vector :map]]}
  [database waiting]
  (into [] (comp (mapcat #(lookup-refs-in database %)) (distinct)
                 (map (fn [[attribute value]] {:seon.activation/lookup-attribute attribute
                                               :seon.activation/lookup-value value})))
        waiting))

(defn- refuse-round!
  "Throw the boot refusal a round names; nil when the round can transact."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:or [:map [::refused :map]] [:map [::ready [:vector :map]] [::waiting [:vector :map]]]]] :nil]}
  [database {::keys [refused ready waiting]}]
  (cond
    refused (let [[attribute value] (::lookup refused)]
              (refused! (str "An initialization readiness read was refused: "
                             (:seon.error/message (::refusal refused)))
                        {:seon.activation/lookup-attribute attribute
                         :seon.activation/lookup-value value
                         :seon.boot/population :seon.config/initialization
                         :seon.boot/result (::refusal refused)}))
    (empty? ready) (refused! "Initialization lookup refs do not resolve."
                             {:seon.activation/missing (missing-lookups database waiting)})))

(defn- transact-initialization!
  {:malli/schema [:=> [:cat :seon.db/connection [:sequential :map]] :nil]}
  [connection entities]
  (config/require-functions! (db/db connection) entities)
  (loop [pending (vec entities)]
    (when (seq pending)
      (let [database (db/db connection)
            {::keys [ready waiting] :as round} (initialization-round database pending)]
        (refuse-round! database round)
        (require-committed!
         (db/transact! connection {:tx-data ready
                                   :tx-meta {:seon.db/process [:seon.db.process/id boot-process-identity]}})
         {:seon.boot/population :seon.config/initialization})
        (recur waiting)))))
```

Preconditions for EQUIVALENT, each checked in source: `refused!` always throws (`seon.cluster/refused!`);
`entity-ready?` returns exactly `true` or `false` (`every?`), so `group-by` buckets are the `true` and `false` keys and
keep input order; `entity-ready?` reads an immutable database value, so calling it once instead of twice changes no
result, only the number of readiness reads and their captured evidence per round (halved). The contracts are new and must be compiled; the
"`(distinct)` — the transducer" incident comment goes to Git (§9.2). What did not change: the readiness algorithm,
the refusal data, the transaction metadata and the custody of the write.

**1.2 Law: keep the pure core apart from the effectful shell.** One function computes data; another performs it.

GOOD (pure core): `clj-reload/src/clj_reload/parse.clj:163-175` (`topo-sort`, a worklist `loop` over a map) and
`clj-reload/src/clj_reload/core.clj:185-191` (`topo-sort-fn` closes over the sorted order and returns a filter).
GOOD (thin shell): `datahike/src/datahike/api/impl.cljc:30-42`, `transact!` normalizes its argument and delegates.

```clojure
(defn- topo-sort-fn [deps]
  (let [sorted (parse/topo-sort deps)]
    (fn [coll] (filter (set coll) sorted))))
```

Not an example of purity: clj-reload's `scan` (`core.clj:206-229`) reads the clock, `*config*`, `*loaded-libs*`
and the filesystem, and may throw. `unload` (`:304-332`) calls it inside `swap!` under its own lock. In Seon, read
the observations once, then pass them to a pure transformation. A function named for what it derives
(`…-projection`, `…-view`) performs no effect.

**1.3 Review prompt: inline a single-use helper when its name, docstring and contract say less than its body.**
Extract a function for reuse or for a concept the reader needs named. A one-line body is fine when the name carries
the concept (`move.cljc` above).

## 2. Data shapes and keys

**2.1 Spell keys with `::k` and `::alias/k`, using `:as-alias` for namespaces you do not load (EQUIVALENT: the reader
interns the same keyword, so the fully-namespaced law holds).**

GOOD: `malli/src/malli/error.cljc:44-62`: the message table is `::m/missing-key`, `::m/invalid-type`,
`::misspelled-key`.

SEON: `seon.render.web/debug-turn-request` spends 12 lines spelling seven keys in full. Rewrite (EQUIVALENT; the ns
already requires `seon.db :as db`, `seon.render.value :as value` and `seon.sci.admit :as admit`, and gains `:as-alias`
entries for `seon.cluster`, `seon.agent`, `seon.turn.loop`, `seon.sci.eval` and `seon.config.eval`; the body also renames
the arglist to `[db conn agent-id caps ctx]`, keeping the five-argument order):

```clojure
(merge ctx {::db/db db
            ::cluster/name (or (::cluster/name ctx) (get-in ctx [::loop/cluster ::cluster/name]))
            ::db/connection conn
            ::agent/id agent-id
            ::sci.eval/time-limit-ms (::config.eval/time-limit-ms ctx)
            ::value/root [::agent/id agent-id]
            ::admit/caps caps})
```

**2.2 Review prompt: destructure where the shape becomes known; prefer the arglist for unconditional input
fields.** Keep an explicit lookup when presence, computed keys or evaluation order matter, and name values so
competing roles stay distinct (`db-before`, `db-after`).

GOOD: `fipp/src/fipp/edn.cljc:9-10` and `malli/src/malli/core.cljc:717-718`.

```clojure
(defn pretty-coll [{:keys [print-level print-length] :as printer} open xs sep close f] ...)
(defn -min-max-pred [f] (fn [{:keys [min max]}] ...))
```

SEON: `seon.db/write-owned-values-error` opens with
`(let [before (:db-before report) after (:db-after report) limit (:seon.config.db/validation-node-limit projection)] ...)`.
EQUIVALENT rewrite (keys become arglist bindings): `[{:seon.config.db/keys [validation-node-limit] :as projection}
{:keys [db-before db-after]} …]`.

**2.3 Review prompt: within one domain, pass the map you were given.** Re-keying the same facts into another
namespace's keys, then validating the copy, is plumbing. At a real boundary (persisted, printed, or another domain's
interface) declare what is transformed and why; rename once with `set/rename-keys` (`clojure/src/clj/clojure/set.clj:78-87`).
Never carry live resources into a persisted or printed value.

SEON: `seon.schema/admit-declarations!` copies each `:seon.schema.projection/X` to `:seon.schema/X`, but it also
computes defaults, compiled schemas, predicate functions and affected sets. Reuse the projection's keys where they
mean the same; keep those derivations. `seon.render.transcript/receipt-entry` is a normalization boundary (namespace,
time fallback, run relationships, a shared entry envelope), not a mirror; remove only proven duplicate facts.

**2.4 Copy present optional keys with `select-keys` and `merge`.** `select-keys` is a presence test
(`clojure/src/clj/clojure/core.clj:1559-1571` uses `find` and copies the returned entry); `(cond-> r (:k m) (assoc :k (:k m)))` is a
truthiness test. The rewrite `(merge r (select-keys m [:k …]))` is EQUIVALENT only when `r` is a map, no value of those keys is
`nil` or `false` and the copy may overwrite a key already in `r`; otherwise it is a BEHAVIOR CHANGE (with
`r = {:k :old}` and `m = {:k false}` the old code returns `{:k :old}`, the new `{:k false}`).

SEON: `seon.fn/var-entity` has clauses like
`(:seon.fn/doc-order metadata) (assoc :seon.fn/doc-order (:seon.fn/doc-order metadata))`. Its input is declared
`:map`, so prove the value is never `nil`/`false` (or tighten the contract) before grouping the keys.

**2.5 Review prompt: an options map for numerous independent options; positionals for the essentials whose order
expresses the operation.** The errors constructor's target arities (errors P2) take type, operation, message and
data positionally on purpose; the installed one takes `[alternative members]`.

GOOD: `babashka-process/src/babashka/process.cljc:674-708`, `shell` normalizes its options and merges
`default-shell-opts` once.

SEON: `seon.render.web/debug-diagnostic` takes `[message operation member expected offending cause evidence]`. Those
are the members of an error map, so the function becomes one call to the error constructor (§3) once
`:seon.render.web/request-error` is a named variant carrying them (TARGET: its family's errors P3 slice).

## 3. Errors

The [errors spec](../../../docs/seon/specs/errors.md) and the owner rulings of 2026-10-07 are binding. One map:
`:seon.error/type` (its variant's schema key), `:seon.error/operation`, `:seon.error/message`, optional `fix` and Malli
`problems`, and the variant's own fully namespaced members. Two carriers of that map: returned, or thrown inside
`ex-info`. One boundary takes a thrown map back. One constructor builds it and validates the variant. There is no
separate fault concept: Seon's own failures are errors, recorded once per signature (count, task, one notification).

**Installed vs target** (verify at the REPL). Installed: S1, the type key replaces the metadata stamp; the constructor
`(seon.error.refusal/error alternative members)`, which validates, requires `:seon.error/operation` in `members` and
stamps `:seon.error/at` and `:seon.error/layer`; `seon.error.refusal/chain`, the cycle-safe cause and suppressed walk.
TARGET: errors P2 adds `(error type operation message data)` and `fail!` to the same defn; the errors **cut** brings
`caught` at every entrant, the report adapter over Malli's report and drops `at`/`layer`; after it, **S-reads** makes
reads throw one read family at a time and **S-renders** renders over the map. Re-read the spec's slice section before
relying on a slice name. Use what is installed; never write the patterns the spec deletes or a target not yet landed.

**3.1 Law: build every error with the one constructor where you detect the failure. Return it when answering is the
function's job (`my.*` tools, validators, merge checks, gates); otherwise throw it as `ex-info` data.**

GOOD: `malli/src/malli/core.cljc:203-207` pairs one constructor with one thrower.
`datahike/src/datahike/db/transaction.cljc:27-32` raises at the datom with a type key and the facts.

```clojure
(defn -exception [type data] (ex-info (str type) {:type type, :message type, :data data}))
(defn -fail! ([type] (-fail! type nil)) ([type data] (throw (-exception type data))))
```

Installed today (the my.fs variant's member is `:my.fs/not-found`, whose schema is `:my.fs/path`):

```clojure
(seon.error.refusal/error :my.fs/not-found-error
                          {:seon.error/operation 'my.fs/read :seon.error/message "No such file." :my.fs/not-found p})
```

SEON: `seon.render.web/turn-function-result` builds its refusal with `refusal/diagnostic`, a `(java.util.Date.)`, the
operation typed out and generic fix prose. There are about 225 such call sites; the errors slices rewrite them by
family.

**3.2 Preserve the installed read-refusal contract until S-reads.** Never catch only to wrap and rethrow.
Unwraps of returned read refusals are **load-bearing until errors S-reads**: reads still
return refusal values, so deleting an unwrap passes an error map downstream as data (live run 1: a refused `seon.db/q`
became map entries under `sort-by`). S-reads converts each read family's owner and every caller together.

SEON: `my.program/caller-data` unwraps five reads; `src` holds about 119 copies of:

```clojure
(let [result (db/pull database [:db/id :seon.fn/form-span] [:seon.fn/sym stored])]
  (if (:seon.db/invalid-read result) (throw (ex-info (:seon.error/message result) result)) result))
```

TARGET (errors S-reads): `(db/pull database [:db/id :seon.fn/form-span] [:seon.fn/sym stored])`. Until then keep the
unwrap. A new caller must also discriminate the installed union before consuming a read as data; reuse the
owner's handling rather than treating a target throwing read as installed. Convert that caller with its read family.

**3.3 Law: catch once, at the entrant boundary.** A returning tool whose failure is detected deep inside catches its
own declared variants once at its top; a deliberate translation names its own type and keeps the cause. Walk a cause
chain once, at the boundary that owns the error, with the one cycle-safe walk (`seon.error.refusal/chain`, or
`cause-links` for the primary path only), and pass the derived chain on. Never use `Throwable->map` as the traversal:
it follows `.getCause` without a seen-set, so a cause cycle never ends, and it drops suppressed throwables
(`clojure/src/clj/clojure/core_print.clj:484-501`). Its `:via` link shape is what each of Seon's links resembles.

GOOD: `clj-reload/src/clj_reload/core.clj:367-381`: `reload` throws `ex-info` with the outcome fields as data and the
cause attached, or returns those fields plus `:exception` when the caller asked for answers (`:throw false`).

SEON: `seon.test.runner/throwable-signature` walks `ex-cause` in its own `loop` and never ends on a cause cycle that
carries no signature. Rewrite (BEHAVIOR CHANGE: a cycle now terminates; the same result on every acyclic chain):
`(some (comp :seon.error/signature ex-data) (refusal/cause-links t))`.

**3.4 Law: only functions that return errors list error alternatives** (TARGET form: one `:multi` on
`:seon.error/type`). A function that throws declares only its success value. A returning entrant such as
`seon.test/run` keeps its exact returning variants; narrowing applies to readers (errors S-reads), not to entrants.

```clojure
:my.fs/tool-error [:multi {:dispatch :seon.error/type}
                   [:my.fs/not-found-error :my.fs/not-found-error] ...]
my.fs/read [:=> [:cat :my.fs/read-request] [:or :my.fs/read-result :my.fs/tool-error]]
```

Installed today `:my.fs/tool-error` is an `[:or …]` that also admits `:seon.effect/boundary-error`; the `:multi` lands
with that family's errors P3 slice. SEON: about 150 `[:or value :seon.db/error-result]` unions on readers. TARGET
(S-reads): they narrow to `value`.

**3.5 Producers write their domain message; validation prose comes from Malli.** The constructor takes the producer's
concise message ("No such file."). For schema violations use Malli: `m/explain`, `me/with-spell-checking`,
`me/with-error-messages`, `me/humanize` (`malli/src/malli/error.cljc:288-306,332-390`); a schema that deserves a better
sentence carries `:error/message`. A message says why ("should be at least 1 character"), never "expected a string,
got a string". Spelling suggestions need a closed schema: on the refusal path only, explain against `mu/closed-schema`
(`malli/src/malli/util.cljc:128`) and keep only extra-key errors that carry a suggestion, so valid calls on open maps
pay nothing.

SEON: `seon.error/schema-expectation` rebuilds "a vector … count" English with `case`; `seon.error/edit-distance`
re-implements Malli's private `-levenshtein` (`error.cljc:256-260`), whose public entry is `with-spell-checking`.

**Worked error flow (TARGET: errors P2 installs the arities and `fail!`, the cut installs `caught`).** Today's form of
the same flow is `seon.fs.jvm/refuse!` throwing `flat-error`, caught in `seon.fs.jvm/read-window`. This is a sketch of
the pieces, not one runnable block.

```clojure
;; 1. Variant schema, in the owner's resources/seon/schemas/my.fs.edn (an :and, since the registry has no :merge).
;;    BEHAVIOR CHANGE for every reader of today's member :my.fs/not-found, which becomes :my.fs/path.
:my.fs/not-found-error [:and :seon.error/base [:map :my.fs/path]]
:my.fs/tool-error [:multi {:dispatch :seon.error/type}
                   [:my.fs/not-found-error :my.fs/not-found-error]
                   [:my.fs/path-refused-error :my.fs/path-refused-error]]

;; 2. Producer, deep inside the read: its job is not to answer, so it throws the one map.
(when-not (Files/exists path no-follow)
  (refusal/fail! :my.fs/not-found-error 'my.fs/read "No such file." {:my.fs/path (str path)}))

;; 3. The returning tool's top answers only its own declared variants; everything else propagates.
;;    tool-error? is the cached validator of :my.fs/tool-error, compiled once from the owner's projection.
(catch clojure.lang.ExceptionInfo e
  (let [d (ex-data e)] (if (tool-error? d) d (throw e))))

;; 4. Boundary: an exception that is not a declared error reaches the entrant's one catch:
;;    (catch Throwable t (refusal/caught t)) -> {:seon.error/type :seon.error/exception … :seon.error/chain …},
;;    delivered in the agent's reply with a result handle and recorded once per signature.

;; 5. Assertion, through the agent's entrance (clojure-testing skill, example 3; the path must be inside the
;;    agent's filesystem policy root, as seon.fs.jvm-test arranges with a temporary root):
(is (= :my.fs/not-found-error (:seon.error/type (support/agent-value ctx "(my.fs/read {:my.fs/path \"absent.md\"})"))))
```

`fail!` validates through the constructor, so a producer that forgets `:my.fs/path` fails at the producer, not in the
agent's reply. The output contract of `my.fs/read` (`:my.fs/tool-error`) refuses an undeclared variant (errors spec T7).
A contract refusal or core error is not in the tool's union, so step 3 lets it propagate with its own blame.

## 4. Contracts

Every function you add or change is contracted, private ones included, and instrumentation arms all of them; existing
uncontracted functions are back-filled as generated tasks (`seon.contracts/task`, the
[contracts guide](../../../docs/seon/architecture/contracts-guide.md)). Keep each contract short, never weaker.

**4.1 Write the contract on one line when it fits. Use registry names and naked keys; never restate a registered
key's schema inline.** Keep optionality, bounds, relationships and exact returning variants.

GOOD: `malli/docs/function-schemas.md:526-530` and `malli/docs/reusable-schemas.md:116-120`.

```clojure
(defn minus {:malli/schema [:=> [:cat :int] small-int]} [x] (dec x))
(register! ::user [:map ::id ::name ::address])
```

SEON: `seon.turn/interrupted-warning` has a 13-line inline schema spelling
`[:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]`, a key whose schema is itself. EQUIVALENT rewrite (both
keys are registered, as the current contract's references show):

```clojure
{:malli/schema [:=> [:cat [:sequential [:map :seon.cluster.eval/ordinal [:seon.cluster.eval/interrupted-at {:optional true}]]]]
                [:maybe [:map :seon.cluster.eval/ordinal ::missing-results]]]}
```

**4.2 A repeated shape gets one name, registered in the owner's schema EDN with its generator and admission
explanation, then published and adopted on a branch.** Do not substitute `:map`, `:any` or `:int` for a declared
shape to fit one line; a plain `:map` proves none of its members. Small one-off structural contracts may stay inline.

SEON: the same polymorphic-exemption reason ("A total predicate accepts arbitrary objects, including nil, and returns
false when they do not satisfy its declared shape.") is pasted 45 times, with its `:gen/elements`, in contracts such as
`seon.id/writer?`:

```clojure
{:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary,
                                 :seon.schema.admission/reason "A total predicate accepts arbitrary objects, …",
                                 :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
```

Rewrite: search the registry for an existing name first. If none exists, register the input once, with its
exemption, reason and generator together, in the owning schema EDN (for example
`resources/seon/schemas/seon.schema.admission.edn`; the name below is a proposal), publish and adopt it on a branch,
then convert all 45 contracts by one script in the same commit:

```clojure
;; resources/seon/schemas/seon.schema.admission.edn
:seon.schema.admission/total-predicate-input
[:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
       :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape."
       :gen/elements [nil false 0 "" :k [] {}]}]

;; each predicate
{:malli/schema [:=> [:cat :seon.schema.admission/total-predicate-input] :boolean]}
```

EQUIVALENT once the name is adopted (same schema, same generator, same admission explanation); until then the
converted contracts do not compile. The one predicate whose reason differs ("…when they are not files") keeps its
own form.

## 5. Validation

**5.1 Validate at the boundary with the armed contract or a compiled validator. Remove an inner guard only when the
exact armed contract entails that exact fact.** Union discrimination, optional-key presence, domain predicates and
checks of newly read state remain meaningful.

GOOD: `malli/src/malli/core.cljc:2672-2686`. `validator` is cached on the schema object.

SEON: `my.program/caller-data` tests each read for `:seon.db/invalid-read` before using it. Those probes are the
read-refusal unwraps of §3.2: they stay until errors S-reads (TARGET) makes the reads throw; after that the remaining
checks are the domain ones (a caller with no stored form, a test instead of a function).

## 6. Iteration

**6.1 Review prompt: prefer `reduce`, `into` with a transducer, `some` and `keep`; use `loop` for a reader, a
worklist, an index or a state machine when `reduce` does not say it clearly.**

GOOD: `clojure/src/clj/clojure/core.clj:7298-7310` (`group-by`) and `:7355-7363` (`frequencies`), one `reduce` into
a transient. `sci/src/sci/impl/interpreter.cljc:94-114` (`eval-string*`) and `clj-reload/src/clj_reload/parse.clj:163-175`
(`topo-sort`) are legitimate `loop`s: a reader drives one, a shrinking worklist the other.

```clojure
(defn frequencies [coll]
  (persistent! (reduce (fn [counts x] (assoc! counts x (inc (get counts x 0)))) (transient {}) coll)))
```

**6.2 Make one pass when it preserves result, order, metadata and absence.** Each row's label holds only under its
precondition:

| Before | After | Label |
|---|---|---|
| `filter` then `remove` with one predicate | `(group-by p xs)` once | EQUIVALENT in result for finite inputs when `p` returns exactly `true`/`false` (destructure `{yes true no false}`), is free of effects, and the consumer needs both full buckets; realization becomes eager |
| `(first (filter p xs))` | `(some #(when (p %) %) xs)` | EQUIVALENT only when no matching element is `nil` or `false`; with `p = some?` and `[false :later]` the first returns `false`, the second `:later` |
| `(into {} (map (fn [[k v]] [k (f v)])) m)` | `(update-vals m f)` | BEHAVIOR CHANGE in metadata: `update-vals` keeps `m`'s metadata (`core.clj:8136-8150`), `into {}` drops it |
| `(set (map f xs))` | `(into #{} (map f) xs)` | EQUIVALENT |
| `(not (empty? xs))` returned as a value | `(boolean (seq xs))`; `(seq xs)` only in condition position | EQUIVALENT as written |

**6.3 Review prompt: thread when it shows the data flow.** A named intermediate is right when it separates roles
the reader must keep apart.

GOOD: `malli/src/malli/core.cljc:247-260`, where `-comp` is `(fn [x] (-> x f4 f3 f2 f1))`.

A chain of rebindings of one name (`(let [x (f a) x (g x)] …)`) is a thread in disguise (§14). Results are runtime
only: an evaluation's value is never staged or stored (`seon.turn/settlement-projection` keeps only shown text), so
no rewrite here may add a stored copy of a value.

## 7. Dispatch

**7.1 Review prompt: dispatch data variants on data, a multimethod on a key or a map from key to function, rather
than a growing `cond` that probes which keys are present.**

GOOD: `fipp/src/fipp/engine.cljc:10-46`, one small method per node kind; `langchain4clj/src/langchain4clj/core.clj:249-262`
dispatches providers on `:provider`; `malli/src/malli/error.cljc:44-62` keeps its messages in a table.

```clojure
(defmulti serialize-node first)
(defmethod serialize-node :text [[_ & text]] [{:op :text, :text (apply str text)}])
(defmethod serialize-node :break [& _] [{:op :break}])
```

Not every `cond` is a missing discriminator. `seon.error/message` is an ordered fallback (the value's own message,
then a transition's explanation, then the cause, then "unknown"); a multimethod on a type does not reproduce that
precedence. Retire its obsolete branches with errors S-renders.

**7.2 Review prompt: prefer maps of functions and multimethods; introduce a protocol only for a demonstrated
type-based polymorphic boundary.** Libraries do use protocols on their own types (Malli's `Registry`,
`malli/src/malli/registry.cljc:11-28`; Fipp's `IVisitor`, `fipp/src/fipp/visit.cljc:8-30`); CLAUDE.md forbids bespoke
protocols that hide information.

## 8. State

**8.1 Law: use the existing owner first (database facts, the Flow proc's state, the SCI context, `seon.cache`).
Mutable state is at most one validated state map per process and one per cluster: an atom of a fully specced map
whose `set-validator!` is a Malli validator, so every write is checked or refused, changed by pure functions. The
only other mutable cells are the per-function profile counters, the SCI interrupt flag, the running Thread and
clojure.test's report counter; cache counters are Caffeine's `recordStats`. Any other atom needs a reason the owner
accepts (owner 2026-10-08; ledger §3.19, AT-1..8).**

GOOD: `clj-reload/src/clj_reload/core.clj:54-55` keeps one private `*state` atom, changed by `swap!` (`:313`,
`:322-325`) under its own lock (`with-lock`, `:60-65`). Remember that `scan` is not pure (§1.2).

SEON: `seon.flow/start-work-launcher!` allocates `active-work`, `io-submissions` and `accepting?` as three atoms, and
`seon.flow/submit!` three more per item. Colocating them is a design sketch, not a rewrite: admission, draining,
callback ownership and external effects need a reviewed transition model, and a single `swap!` does not make
launch, cancel and retire effects atomic.

**8.2 Law: never escape a namespace cycle with `requiring-resolve` or a `defonce` delay.** Pass the function as an
argument, or move the code to the namespace that owns it.

GOOD: `babashka-process/src/babashka/process.cljc:250-263`: the process reads `:program-resolver` and `:escape`, both
functions, from its options map, so the caller passes behavior in and nothing is resolved late (`shell`, `:674-708`,
merges the options once).

SEON: the top of `seon.db` holds eight `(defonce ^:private … (delay (requiring-resolve …)))`: five resolve `seon.error`
functions and three `seon.call-preparation` functions, because both namespaces require `seon.db`.

**8.3 Law: a dynamic Var only for an ambient value bound once at the entrance** (`*out*`, the current namespace,
custody and the evaluation's world, which the declared supply boundary fills). Add no new one; an ordinary helper
takes its other inputs as arguments.

GOOD: `sci/src/sci/impl/interpreter.cljc:99-103`: `eval-string*` binds the namespace and data readers once.

SEON: `seon.effect/*request-context*` carries a request's identity, which `seon.test` reaches by `requiring-resolve`.

## 9. Docstrings and comments

**9.1 Law: a docstring's summary says in 1-3 lines what the function returns. Markdown headings (Examples, Edge
cases, Errors, Known issues) follow only on public `my.*` docstrings, which agents read.**

GOOD: `clojure/src/clj/clojure/core.clj:7356-7357`: "Returns a map from distinct items in coll to the number of times
they appear."

SEON: `seon.error/message` has an 11-line docstring over a 15-line body that reports a measurement and argues the
design. Rewrite: `"The reader-facing message: the source's own, else its transition's explanation, else the root
cause's, else what arrived."` (the four branches of §7.1, none dropped).

**9.2 Law: no dates, measurements, incident history or `file:line` in source.** Git, the owning spec and the task
hold them; costs live in profile evidence. Extended design discussion goes there too; keep a short invariant or
reason beside code when it prevents a wrong edit (§9.5).

**9.3 Law: a reference is a backticked span that reads as one fully qualified symbol or keyword**
(`seon.db/pull`, `:seon.error/type`). Tasks are referenced the same way, as `ns/task-name`; a task's identity derives
from its namespace and name, never a hand-typed id. This replaces `[[sym]]` links. TARGET: the index resolves each
reference into the subject's context and reports one that resolves to nothing as a problem on that var; until it
lands, a backticked span is prose that a reader can follow.

**9.4 Link a task with `:seon/tasks` metadata.** A definition or test names its tasks with `:seon/tasks` (on a deftest,
`^{:seon/tasks #{…}}` before the name; metadata after the name is refused). The index links it today
(`seon.fn/task-links`), and the deftests naming a task are its success tests (`seon.task/task-edges` `:proofs`). Today
the set holds task handles derived by `seon.task/task-handle`; TARGET (lane task-tags): it holds `ns/task-name`
symbols. A known issue under `Known issues` on a `my.*` docstring names the same task.

**9.5 Review prompt: comment why, in one line above the form, only where the code cannot say it.** No banner rules
(`;;; ----`), narration or restated docstrings; a `;;;` section title is fine.

GOOD: `hyperlith/src/hyperlith/core.clj:24`: `;; Make futures use virtual threads`.

## 10. Naming

**10.1 Review prompt: name by role, short and clear.** The namespace already names the domain. A pure function is
usually the noun of what it returns, an effect ends in `!`, a predicate in `?`. Short locals (`db`, `conn`, `tx`, `m`)
are good until two values of one kind meet: then name the roles (`db-before`, `db-after`). Long persistent keys
cost on every use: `:seon.schema.admission/polymorphic-boundary`.

## 11. Macros

**11.1 Review prompt: write a macro only for syntax a function cannot provide (binding forms, `def` forms, body
wrapping).** Evaluate each runtime expression exactly as often as the macro's documented semantics promise; binding
symbols and declarations are syntax, not expressions.

GOOD: `clj-reload/src/clj_reload/core.clj:60-65` (`with-lock`, a body wrap) and
`core.async/src/main/clojure/clojure/core/async.clj:150-160` (`defblockingop`).

SEON: `seon.error.refusal/let-ok` exists only to carry the round trips that errors S-reads deletes.

## 12. Performance

**12.1 Build once, call many.** Compile validators, explainers, regexes, registries, queries and resource paths
outside the per-call path, keyed by the value they derive from (a `seon.cache` kind when the key is content, and only
after a profile shows it pays; never `memoize` or an atom). Prefer the algorithm that does less work over any cache.
Malli's cache lives on the compiled schema object, so it saves work only when you reuse that object.

GOOD: `malli/src/malli/core.cljc:353-361` (`-cached`) and `:2672-2678` (`validator`).

GOOD (Seon): `seon.render/call-reach` keys its derivation by content, so unrelated writes keep the entry and nothing
is invalidated. `call-reach-key` is the important part: for a committed value it is the connection id, generation,
conservative revision and the two attributes' Datahike revisions (equal revisions from different connections or
generations prove nothing, `datahike/src/datahike/query.cljc:3010-3029`); for an as-of value over a committed origin
it is connection id, generation and time point. An uncachable database value (nil key) falls through to the read. (Its
docstring still carries a date and a measurement, against §9.2: the body is the example.)

```clojure
(if-let [content (call-reach-key database)]
  (seon.cache/lookup ::call-reach (into [selected] content)
                     #(* 80 (count %))
                     #(db/stored-call-reach database selected))
  (db/stored-call-reach database selected))
```

SEON: `seon.render.web/turn-function-result` rebuilds a resource path with two `str/replace` on every call; it probes
`io/resource` zero times when `find-ns` finds the namespace, else up to three. Profile before caching, and never cache
a classpath observation without its changing input.

**12.2 Read once and pass the value. When the consumer needs the complete set, N reads in a loop become one query or
one `pull-many`; no wrapper whose only job is mapping the single-item function.** A consumer that takes a prefix keeps
its lazy reads.

GOOD: `datahike/src/datahike/pull_api.cljc:541-555`: `pull-many` returns one input-aligned result per entity.

SEON: `my.program/caller-data` reads, per caller, a pull by `:seon.fn/sym`, a second pull by `:seon.test/sym` only when
the first misses, and a `q` for call arities. Acquisition sketch (not a complete rewrite: it must keep the sorted
callers, the subject filter on arities, the declared arities and the site maps):

```clojure
(db/pull-many db [:db/id :seon.fn/sym :seon.test/sym :seon.fn/form-span :seon.fn/call-arities] caller-eids)
```

**12.3 Be eager across effects, `binding`, `try` and resource scopes.** Return `mapv`, `into` or `run!` results.
Laziness or a reducible is right for a consumer that takes a prefix or streams the input within the owning scope.

GOOD: `clojure/src/clj/clojure/core.clj:7054-7065` (`mapv`).

**12.4 Use transients only in a builder the profile shows is hot** (`into`, `mapv`, `group-by` already use them).
Hint only what `*warn-on-reflection*` names.

**12.5 Choose the algorithm first: work proportional to what is shown, a lookup instead of a scan, what changed
instead of the whole program. Measure before and after; a guessed cost is a hypothesis.** Deterministic work counts
(cache misses, recomputations) can be specified behavior and tested; timings are profile evidence.

## 13. Fix what you touch

**13.1 Law: when the code you touch breaks a rule above, fix it if that takes about 15 minutes; otherwise create a
task and reference it (`ns/task-name`) in that function's docstring.** Stay inside file ownership: claim the file with
`bin/lane add`, or hand its owner a patch. A fix that changes behavior lands with its regression. A fix that changes a
schema resource is published and adopted on a branch. A rule whose rewrite is TARGET is not fixable yet: leave it.

## 14. Quick rewrites

Each row is safe only under its condition; follow the section for the full rule.

| You are about to write | Write instead | Label and condition |
|---|---|---|
| `(let [a (:x/a m) b (:x/b m)] …)` | `[{:x/keys [a b] :as m}]` in the arglist | EQUIVALENT (2.2) |
| `{:y/a (:x/a m) :y/b (:x/b m)}` in one domain | pass `m`; at a real boundary `(set/rename-keys (select-keys m ks) kmap)` | BEHAVIOR CHANGE for consumers of the copy (2.3) |
| `(cond-> r (:k m) (assoc :k (:k m)))` per key | `(merge r (select-keys m [:k …]))` | EQUIVALENT only if `r` is a map and no copied value is `nil`/`false` (2.4) |
| a read returning success or a refusal | discriminate its installed union before consuming it | TARGET errors S-reads permits the bare call only after that family throws (3.2) |
| `(catch Throwable t (error-value …))` mid-function | let it reach the boundary, or the returning tool's one top catch | BEHAVIOR CHANGE (3.3) |
| an `ex-cause` loop at a boundary | `(refusal/cause-links t)` once, then reuse it | BEHAVIOR CHANGE: terminates on a cause cycle (3.3) |
| `[:k :k]` entries in a contract | `[:map :k …]` naked entries, or a registry name | EQUIVALENT when `:k` is registered (4.1) |
| `(contains? x :k)` on an armed input | remove only if the exact contract requires `:k` | EQUIVALENT under that condition (5.1) |
| `filter` and `remove` with one predicate | `group-by` once | EQUIVALENT in result for finite inputs, a pure boolean predicate and a consumer needing both full buckets; eager (6.2) |
| `(first (filter p xs))` | `(some #(when (p %) %) xs)` | EQUIVALENT only if no match is `nil`/`false` (6.2) |
| `(into {} (map (fn [[k v]] [k (f v)])) m)` | `(update-vals m f)` | BEHAVIOR CHANGE: keeps metadata (6.2) |
| `(set (map f xs))` | `(into #{} (map f) xs)` | EQUIVALENT (6.2) |
| `(let [x (f a) x (g x) x (h x)] x)` | `(-> a f g h)` | EQUIVALENT (6.3) |
| a second `(atom …)` that must agree with the first | the existing owner, else one atom of a map | design change (8.1) |
| `(defonce f (delay (requiring-resolve 'other/f)))` | pass `f` in, or move the code | BEHAVIOR CHANGE in load order (8.2) |
| `(map #(db/pull db sel %) ids)` | `(db/pull-many db sel ids)` | BEHAVIOR CHANGE: eager, and one refusal for the whole batch; successful values stay input-aligned. Use when the consumer needs the complete batch (12.2) |
| `(if x y nil)` | `(when x y)` | EQUIVALENT |
| a lazy `map` crossing `binding`, `try` or `with-open` | `mapv`, `into` or `run!` | BEHAVIOR CHANGE (fixes a scope bug) (12.3) |

## 15. Review questions before you commit

- Which roles does each long function mix, and is each split named by its role? (1.1)
- Does any rewrite treat a truthiness test as a presence test, or drop a `nil`/`false`? (0, 2.4, 6.2)
- Is each error built by the one constructor at the detection site, carried by rule 3.1, and taken back only at a
  boundary? Did you keep the unwraps errors S-reads has not removed yet? (3)
- Is any contract restated inline, or weakened to fit one line? (4)
- Is any collection walked twice, or a database read in a loop? (6.2, 12.2)
- Does any value become text outside the value printer: `pr-str` or `str` of a value into a message, log, hash, sort
  or comparison, a character quota, a second clip? (CLAUDE.md "There is exactly ONE printer"; reference-code.md
  "Value printing")
- Is each docstring 1-3 lines, with no dates, measurements, history or `file:line`, and references as backticked
  qualified names? (9)
- Is every rewrite labeled EQUIVALENT (preconditions checked), BEHAVIOR CHANGE (with its regression) or left alone
  as TARGET?
- Do any problems remain on the code you touched that you neither fixed nor linked to a task? (13)

## 16. Working as a lane

Operational checks:

- Probe uncommitted code on your own agent's SCI branch (MCP `eval_clj` with your lane name), never by loading it into
  scratch namespaces in default's JVM.
- Scratch files go only under your session scratchpad's `<lane>/` directory; never share one across lanes or commits.
- Verify a tool's limit (a timeout, a cap, "cannot") at the REPL or in its source before you report it.
- When the design is wrong, stop and report it with priced options; never patch around it.
- Record every finding, "pre-existing" ones included, as a task (`seon.task/create!`) or name its existing task.
- Fix the existing printer, cache or runner; renaming a duplicate does not make it one owner.
