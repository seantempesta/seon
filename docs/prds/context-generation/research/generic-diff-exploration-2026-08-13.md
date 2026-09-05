---
type: research
status: active
tags: [research, database, render, agent, context, wave/evolving-session-prd]
---

# Do we need our own diff? — `seon.db/diff` exploration, 2026-08-13

Empirical answer to the owner's question on
[ledger entry 20](../plan/design-ideas-ledger-2026-08-13.md): *do we need our
own diff at all, and if so can it be simpler?*

**One-sentence answer: we need no diff ALGORITHM — `clojure.data/diff` over an
identity-keyed map is already exactly the identity-aware diff we wanted — but
we do need a small helper, and its real content is the double execution plus
the identity re-keying, not the comparison.**

Every transcript below was executed. The probe scripts are committed beside
this note as `scripts/probe_core_diff_2026_08_13.clj`,
`scripts/probe_datahike_since_2026_08_13.clj`,
`scripts/probe_id_attr_2026_08_13.clj`,
`scripts/probe_candidates_2026_08_13.clj`, and
`scripts/probe_zero_helper_2026_08_13.clj`; run with
`clojure -M:dev -e '(load-file "<path>")'` from the repository root (Clojure
1.12.5).

## 1. `clojure.data/diff` on collections of identity-carrying rows

Inputs (`m1` changed, `m2` removed, `m3` unchanged but SHIFTED position, `m4`
added):

```clojure
(def before
  [{:seon.message/id "m1" :seon.message/text "hello"  :seon.message/read? false}
   {:seon.message/id "m2" :seon.message/text "second" :seon.message/read? false}
   {:seon.message/id "m3" :seon.message/text "third"  :seon.message/read? false}])

(def after
  [{:seon.message/id "m1" :seon.message/text "hello"  :seon.message/read? true}
   {:seon.message/id "m3" :seon.message/text "third"  :seon.message/read? false}
   {:seon.message/id "m4" :seon.message/text "fourth" :seon.message/read? false}])
```

### On the vectors as given — positional, and wrong

```clojure
(clojure.data/diff before after)
⟹ 
[[#:seon.message{:read? false}
  #:seon.message{:text "second", :id "m2"}
  #:seon.message{:text "third", :id "m3"}]
 [#:seon.message{:read? true}
  #:seon.message{:text "third", :id "m3"}
  #:seon.message{:text "fourth", :id "m4"}]
 [#:seon.message{:text "hello", :id "m1"}
  #:seon.message{:read? false}
  #:seon.message{:read? false}]]
```

`m3` did not change, yet it appears in BOTH only-before (index 1) and
only-after (index 1) because the removal of `m2` shifted it. `data/diff` pairs
vectors by INDEX. For a sorted read surface, one removal near the head
misreports every row after it. This is the failure the ruling anticipated.

### On the same rows keyed by identity — correct, and identity-aware

```clojure
(clojure.data/diff (update-vals (group-by :seon.message/id before) first)
                   (update-vals (group-by :seon.message/id after)  first))
⟹ 
({"m1" #:seon.message{:read? false},                            ; changed: only the delta
  "m2" #:seon.message{:id "m2", :text "second", :read? false}}  ; removed: whole row
 {"m4" #:seon.message{:id "m4", :text "fourth", :read? false}   ; added: whole row
  "m1" #:seon.message{:read? true}}
 {"m3" #:seon.message{:id "m3", :text "third", :read? false}    ; unchanged
  "m1" #:seon.message{:text "hello", :id "m1"}})                ; the common part
```

This is the central finding. **`clojure.data/diff` IS an identity-aware diff
the moment the collection is keyed by identity.** It even gives per-attribute
deltas for the changed row for free (`{:read? false}` → `{:read? true}`), which
a whole-row added/changed/removed map throws away.

`update-vals` + `group-by` are both `clojure.core` (1.11+); no `index-by` and
no library are needed. The classification the ruling wanted falls out of key-set
algebra on the triple:

```clojure
(let [[b a _] (data/diff (update-vals (group-by k before) first)
                         (update-vals (group-by k after)  first))]
  {:added   (set/difference (set (keys a)) (set (keys b)))
   :removed (set/difference (set (keys b)) (set (keys a)))
   :changed (set/intersection (set (keys a)) (set (keys b)))})
⟹ {:added #{"m4"}, :removed #{"m2"}, :changed #{"m1"}}
```

### `clojure.set` on raw sets — insufficient

```clojure
(set/difference (set before) (set after))
⟹ #{{:id "m2" :text "second" :read? false}    ; genuinely removed
;;      {:id "m1" :text "hello"  :read? false}}   ; a CHANGE, reported as removal
(set/difference (set after) (set before))
⟹ #{{:id "m4" ...}                            ; genuinely added
;;      {:id "m1" :text "hello" :read? true}}     ; the same change, as an addition
```

Plain sets cannot separate *changed* from *added+removed* — set membership is
whole-value equality, and identity is not consulted. Every changed row is
double-counted. `clojure.set/index` is the relational indexing convention, but
it keys by a SUBMAP and values are SETS, which defeats `data/diff` entirely:

```clojure
(data/diff (set/index (set before) [:seon.message/id])
           (set/index (set after)  [:seon.message/id]))
⟹ (... ... nil)   ; third slot nil: no per-attribute delta survives
```

So: `update-vals`+`group-by`, not `clojure.set/index`.

## 2. Does Datahike already answer it? — no, and the reason matters

`reference-code/datahike/src/datahike/api/specification.cljc` enumerates every
operation. The complete temporal/history set is `history` (line 872), `since`
(886), `as-of` (902), `valid-at` (918), `valid-between` (933), `valid-during`
(949), `valid-all` (964). **There is no `diff`, no `tx-range`, and no
`since-datoms`.** Datahike's own answer to "what changed" is a filtered
DATABASE VALUE, not a delta.

`since` is implemented as `SinceDB`
(`reference-code/datahike/src/datahike/db.cljc:633`), whose search context is
`(context-with-temporal-timepred ... (as-of-pred time-point))` (db.cljc:652-654)
and whose datoms are post-processed by `assemble-datoms-xform`
(db.cljc:158-171, via `post-process-datoms` db.cljc:179-196) — the reducer that
collapses each `[e a]` to its current asserted value and DROPS retracted ones.

Live probe (in-memory Datahike, `:keep-history? true`; `m1` changed, `m4`
added, `m2` retracted after `basis-t`):

```clojure
(d/q '[:find ?id :where [?e :seon.message/id ?id]] (d/since db basis-t))
⟹ (["m4"])
```

**Only `m4`.** This is the decisive result. `since` is not a "what changed"
view; it is a view containing only the datoms asserted in the window. `m1`'s
`:read?` changed, but its `:seon.message/id` datom is old, so any query joining
through identity loses it. `m2`'s retraction is invisible entirely:

```clojure
(mapv (juxt :e :a :v :tx :added) (d/datoms (d/since db basis-t) :eavt))
⟹ [[536870917 :db/txInstant #inst"..." 536870917 true]
;;     [4 :seon.message/read? true  536870915 true]     ; m1's new value only
;;     [7 :seon.message/read? false 536870916 true]     ; m4
;;     [7 :seon.message/text "fourth" 536870916 true]
;;     [7 :seon.message/id   "m4"     536870916 true]
;;     ...txInstant datoms...]
;; no retraction datom for m2 anywhere
```

The datom level CAN be made to answer it, but only through full history:

```clojure
(->> (d/datoms (d/history db) :eavt) (filter #(> (:tx %) basis-t))
     (mapv (juxt :e :a :v :tx :added)))
⟹ [[4 :seon.message/read? false 536870915 false]   ; m1 retract old
;;     [4 :seon.message/read? true  536870915 true]    ; m1 assert new
;;     [5 :seon.message/id   "m2"   536870917 false]   ; m2 retracted
;;     [5 :seon.message/read? false 536870917 false]
;;     [5 :seon.message/text "second" 536870917 false]
;;     [7 ... m4 assertions ...]]
```

That is a full scan of the history index (there is no tx-ordered index to
slice; `tx` is the fourth component of `eavt`), and it costs the identity of
retracted entities separately — `m2`'s id survives only inside the retraction
datom itself.

**Verdict on dissolving the result-diffing half: no.** Even given a perfect
"which entity ids changed since t" answer, it is a strictly different question
from "what does this FORM return differently". A form filters, sorts,
aggregates, and projects: an entity can change without leaving the result set
(`:read?` flip), and an unrelated entity can change *membership* (a new
`:seon.cluster.message/to` ref) without the message entity changing. Only
`(count ...)`-style forms would be answerable, and not even those. The
datom-level view answers "which stored entities were touched"; the helper must
answer "what does this read return differently". They do not substitute.

Corollary worth recording: **the existing `:my.message/inbox-options`
`:seon.db/since` option is add-only.** `inbox*` (`src/my/message.clj:95-110`)
runs `[?message :seon.cluster.message/to ?recipient]` against the since-view;
the `to` datom is asserted at creation, so a message whose content changed
later, or which was retracted, is invisible. That is correct for an
append-only inbox and silently wrong as a general delta — which is precisely
why ruling 20 moved the mechanism out of the function.

## 3. Vendored diff libraries — none, and none needed

`ls reference-code/` (128 entries) contains no `editscript`, no `differ`, no
`deep-diff`. `grep -nE 'editscript|differ|deep-diff' deps.edn` → no matches.
`clojure.data` is used nowhere in `src/` today. Section 1 shows core already
does the job on identity-keyed maps; adding a dependency here would buy edit
scripts for deeply nested trees, which is not the question a read-surface delta
asks.

## 4. What already exists in the tree (reuse, do not rebuild)

### 4a. BOTH halves of ruling 20 are one existing query — executed live

Ruling 20 postulates "a program-graph arity input-ref query, never a roster".
That attribute exists and is populated: `:seon.fn.arity/input-refs` /
`:seon.fn.arity/output-refs`, `[:set :seon.db/ref]` refs to canonical
`:seon.schema/key` rows (`resources/seon/schemas/seon.fn.arity.edn:5,15`;
written at `src/seon/program.cljc:516,554`; already consumed by the doc face at
`src/seon/sci/eval.clj:996,1067`). Run against the live `default` cluster
(`eval_clj`, SCI evaluation mode, read-only):

```clojure
{:input-refs  (seon.db/q '[:find ?k :where [?f :seon.fn/sym "my.message/inbox"]
                                           [?f :seon.fn/arities ?a]
                                           [?a :seon.fn.arity/input-refs ?r]
                                           [?r :seon.schema/key ?k]])
 :output-refs (seon.db/q '[:find ?k :where [?f :seon.fn/sym "my.message/inbox"]
                                           [?f :seon.fn/arities ?a]
                                           [?a :seon.fn.arity/output-refs ?r]
                                           [?r :seon.schema/key ?k]])}
⟹ 
{:input-refs  #{[:my.message/inbox-options] [:seon.db/database-value] [:seon.cluster.agent/id]},
 :output-refs #{[:my.message/inbox] [:seon.error/value]}}
```

Both questions the helper must ask are answered by that one query set:

- **Is this form diffable?** `:seon.db/database-value` ∈ `input-refs`. No
  roster, no name matching, and the refusal for a non-diffable function is the
  same query returning nothing.
- **What is the identity key of its rows?** `output-refs` names
  `:my.message/inbox`, which is `[:vector :my.message/inbox-entry]`, whose
  identity-bearing entry §4b derives. The helper never guesses from the VALUE;
  it reads the declared OUTPUT SCHEMA.

For the argument POSITION to substitute the as-of database into,
`seon.call-preparation/plan-for` (`src/seon/call_preparation.clj:652`) already
compiles it from the same facts, joining on declared key AND value shape; the
`:seon.db/db` row is declared at `config/default.edn:436-439` with supplier
`seon.db/supplied-database-value`, and the derived
`:seon.call-preparation/slot` carries `:seon.fn.argument/index`
(`resources/seon/schemas/seon.call-preparation.edn:93-101`). `seon.db/diff`
should reuse it rather than author a second argument-position query.

### 4b. The identity derivation ruling 20 names DOES NOT COVER THE EXAMPLE

`:seon.entity/id-attr` is attached only to schemas that DECLARE
`{:seon.db/entity true}` (`src/seon/schema/internal.cljc:173-185`). Measured
over the whole registry (all 22 `resources/seon/schemas/*.edn` merged, 2231
registered keys):

```clojure
{:entity-kinds 37, :total 2231, :fraction 0.0166}

(internal/derive-entity-id-attr schemas (:seon.cluster.message/message schemas))
⟹ :seon.cluster.message/id          ; the STORED entity: fine

(internal/derive-entity-id-attr schemas (:my.message/inbox-entry schemas))
⟹ nil                              ; the RENDERED ROW: no identity
```

**The canonical read surface named in the ruling has no derivable identity
today.** `:my.message/inbox-entry` is a projection row, not a declared entity
kind, so ruling 20's identity path falls straight through to the
`clojure.data/diff` fallback — which §1 proved is positional and wrong for
exactly this collection. Any implementation of ruling 20 that stops at
`:seon.entity/id-attr` ships broken on its own example.

The fix is small and stays inside facts-over-inference: starting from the
function's declared `:seon.fn.arity/output-refs` row schema (§4a), **resolve its
entries through the registry's keyword aliases and take the entry whose value
schema terminates at a declared entity id-attr.** `:my.message/id` is literally
declared as `:seon.cluster.message/id`:

```clojure
(take 3 (iterate #(get schemas % %) :my.message/id))
⟹ (:my.message/id
;;     :seon.cluster.message/id
;;     [:string {:min 1, :seon.db/identity true}])
```

Executed over the registry, this alias-chasing derivation resolves
`:my.message/inbox-entry` → `:my.message/id`, and gives a pairing key to 108
further `:map` schemas that have no `:seon.entity/id-attr` (sample:
`:my.background/receipt` → `:seon.effect/id`, `:my.plan/view` →
`:seon.cluster.agent/id`, `:seon.ai.model/observation-request` →
`:seon.ai.model/id`). It over-includes error and request envelopes
(`:my.note/not-found-error` → `:my.note/not-found`), which is harmless here:
we are choosing a PAIRING KEY for a collection, not stamping a catalogued
entity kind — the concern `derive-entity-id-attr`'s docstring guards against.

A near-identical derivation already exists for the renderer:
`seon.render/entity-lookup` (`src/seon/render.clj:500-512`) scans the
projection's shape rows for `:seon.entity/id-attr` values present in the value.
Whichever derivation lands should be the ONE owner both call.

## 5. Candidate designs, executed

All three ran against a live in-memory Datahike with a real read surface
(a function with a declared database-value first argument, like
`my.message/inbox`), `m1` edited, `m4` added, `m2` retracted after `basis-t`.

```clojure
(inbox (d/as-of db basis-t) "root")
⟹ [#:my.message{:id "m1", :preview "hello"}
;;     #:my.message{:id "m2", :preview "second"}]
(inbox db "root")
⟹ [#:my.message{:id "m1", :preview "hello, edited"}
;;     #:my.message{:id "m4", :preview "fourth"}]
```

### A — return `clojure.data/diff`'s triple over identity-keyed maps

```clojure
(seon.db/diff basis-t #'my.message/inbox "root")
⟹ ({"m1" #:my.message{:preview "hello"},
;;      "m2" #:my.message{:id "m2", :preview "second"}}
;;     {"m4" #:my.message{:id "m4", :preview "fourth"},
;;      "m1" #:my.message{:preview "hello, edited"}}
;;     {"m1" #:my.message{:id "m1"}})
```

**For:** the actual Clojure convention; zero invented keys; destructurable as
`[b a both]`; per-attribute deltas for changed rows come free; the added /
changed / removed classification is derivable by key-set algebra; it is the
same shape the identity-less fallback returns, so there is ONE return contract.

**Against, and this is decisive — the no-change case is the biggest output:**

```clojure
(seon.db/diff current-t #'my.message/inbox "root")   ; nothing changed
⟹ [nil nil {"m1" #:my.message{:id "m1", :preview "hello, edited"},
;;              "m4" #:my.message{:id "m4", :preview "fourth"}}]
```

The third slot is the ENTIRE unchanged collection. The whole point of the delta
is to avoid rendering that. Second: the changed row's delta
(`{"m1" {:preview "hello"}}`) has lost its own identity attribute — it lives in
slot three — so a changed row cannot be rendered standalone. Third: the agent
must perform set algebra on keys before it knows what happened, inside the
rendered context, every turn.

### B — classify into a namespaced map (ruling 20's shape) — RECOMMENDED

```clojure
(seon.db/diff basis-t #'my.message/inbox "root")
⟹ {:seon.db.diff/added   [#:my.message{:id "m4", :preview "fourth"}],
;;     :seon.db.diff/removed [#:my.message{:id "m2", :preview "second"}],
;;     :seon.db.diff/changed [#:seon.db.diff{:before #:my.message{:id "m1", :preview "hello"},
;;                                           :after  #:my.message{:id "m1", :preview "hello, edited"}}],
;;     :seon.db/basis-t 536870914,
;;     :seon.db/current-basis-t 536870917}

;; nothing changed:
⟹ #:seon.db.diff{:added [], :removed [], :changed []}   + the two basis keys
```

Signature (positional, matching `seon.db/as-of`'s own shape — see below):

```clojure
(seon.db/diff basis-t f & args)
;; basis-t : :seon.db/basis-t          (already registered, resources/seon/schemas/seon.db.edn:121)
;; f       : a var or fn whose contract declares a :seon.db/db input
;;           (the slot index from seon.call-preparation/plan-for)
;; args    : the caller's remaining arguments, applied to both executions
```

Built from: `clojure.core/apply`, `group-by`, `update-vals`, `clojure.set`
difference/intersection, `seon.db/as-of`, `seon.call-preparation/plan-for`, and
the row-identity derivation of §4b. No new comparison code, no dependency.

**For:** the empty result is empty (the delta stays cheap when nothing changed —
the case that dominates); every row carries its own identity, so a changed row
renders standalone; the agent destructures with ordinary `keys`/`select-keys`
and needs no set algebra; a fully namespaced map in/out is §3's stated
convention for API-like functions, so this is not invented machinery.

**Against, honestly:** it discards `data/diff`'s free per-attribute delta (we
return whole before/after rows); a caller who wants the attribute-level delta
must call `(clojure.data/diff before after)` on the pair themselves — which is
fine and is one visible core call.

### C — return the pairing only, let the agent filter

```clojure
⟹ {"m1" [#:my.message{:id "m1" :preview "hello"} #:my.message{:id "m1" :preview "hello, edited"}]
;;     "m2" [#:my.message{:id "m2" :preview "second"} nil]
;;     "m4" [nil #:my.message{:id "m4" :preview "fourth"}]}
```

One `merge-with` produces it, and added/changed/removed are each a one-line
`filter`. **Against:** it pushes the classification into the rendered context
every single turn, and `nil`-in-a-tuple is exactly the stored-nil shape §3
avoids. Recorded as the minimal-mechanism option; not recommended.

### The zero-helper option — worth stating explicitly

Nothing above requires a `seon.db` function at all. The agent can already type:

```clojure
(clojure.data/diff
  (update-vals (group-by :my.message/id (my.message/inbox (seon.db/as-of 1200))) first)
  (update-vals (group-by :my.message/id (my.message/inbox)) first))
```

Every piece is core plus the existing `seon.db/as-of` and the ruled
declared-database-value argument. This runs verbatim today (ruling 19's test).
The helper's justification is therefore NOT the diff — it is that (a) the agent
must know and re-type the identity key on every read surface, which the
database already knows, and (b) the no-change case renders the whole
collection. B removes both. If the owner rejects B, the honest fallback is to
teach this two-line idiom rather than build A or C.

## 6. Recommendation

**B**, with these amendments to ruling 20:

1. **Drop the option map and the quoted form.** `(seon.db/diff basis-t
   #'my.message/inbox "root")` instead of
   `(seon.db/diff {:seon.db/form '(my.message/inbox) :seon.db/basis-t 1200})`.
   Taking a FUNCTION rather than a form means `seon.db` never needs an eval
   context or a SCI dependency, purity is checked on a var against
   `:seon.fn/external-sink` (an existing program-graph fact), and the call is
   ordinary higher-order Clojure. Two positional arguments plus varargs mirrors
   `seon.db/as-of`'s own `(as-of db time-point)` shape.
2. **Identity derivation reads the declared OUTPUT schema and chases registry
   aliases** (§4a-4b), not the value, and does not stop at
   `:seon.entity/id-attr` — otherwise the ruling's own example silently takes
   the positional fallback. One owner shared with `seon.render/entity-lookup`.
3. **Reuse the existing facts** — `:seon.fn.arity/input-refs` for diffability,
   `:seon.fn.arity/output-refs` for the row schema,
   `seon.call-preparation/plan-for` for the argument index (§4a). No new
   attribute, no new query mechanism.
4. **Keep `clojure.data/diff` as the identity-less fallback** — for a scalar or
   a single nested map it is exactly right
   (`(data/diff {:a {:b 1 :c 2}} {:a {:b 9 :c 2}})` →
   `({:a {:b 1}} {:a {:b 9}} {:a {:c 2}})`) — but refuse it loudly for a
   COLLECTION with no derivable identity, since §1 proved that case is
   positional and misreports. A typed refusal naming the collection's schema
   and the missing identity is the honest output; the silent positional diff is
   not.

## 7. What the helper cannot do, in any design

- **Ordering changes are invisible.** Keying by identity discards position, so
  a re-sorted result with identical membership diffs as empty. If order is
  agent-relevant, that is a separate declared fact, not a diff.
- **Non-collection results** get the `data/diff` triple, a different shape from
  B's map. Either the contract is `[:or ...]` (and the agent branches) or the
  scalar is wrapped. This is the one genuine contract wart in B.
- **Composed forms** (`(count (my.message/inbox))`) cannot be passed as a var;
  a lambda works but the graph can no longer verify sink-freedom, so purity
  becomes unchecked. Either refuse lambdas or accept unverified replay — an
  owner decision.
- **Cost is two full executions.** Ruling 20's v2 (diff against the receipt's
  stored prior result) sits behind the same contract and needs no design change.
- **Nothing here answers "did anything change" cheaply.** §2 showed the datom
  level cannot be made to answer it for an arbitrary form; the only cheap
  answer is the receipt-cached prior result of v2.
