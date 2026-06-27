---
type: prd
status: draft
tags: [prd, schema, database, agent]
---

# `:kind`/`:type` removal — presence + malli as the kind model (audit + migration)

UX flagged that we still use `:kind`/`:type` *stored discriminator fields* in
places, instead of the datahike/Datomic-idiomatic model where an entity's
"kind" is determined by the PRESENCE/ABSENCE of its attributes and malli
VALIDATES/IDENTIFIES which kind a value is. This doc grounds that model in the
actual vendored library source, proves the replacement concepts in the live
REPL, audits every `:kind`/`:type` site (code + design docs), and lays out a
dependency-ordered migration.

**Headline finding.** The ACTIVE CLJS pod already has **zero stored
entity-kind discriminators** — it identifies kinds purely by attribute
presence (`{:seon.db/entity true}` → derived `:seon.entity/id-attr` →
`entity-primary-kind` required-attr subset test) and by malli validation.
There is exactly **ONE** genuine stored entity-kind discriminator in the whole
tree, and it is on the PAUSED JVM track: `seon.ai/::type`
(`:session`/`:message`/`:tool-call`). The two attrs the data-model doc flagged
(`:seon.error/kind`, `:seon.warn/kind`) are **value-level tags on non-entity
values**, not entity-kind discriminators — the idiomatic-presence rule does not
apply to them. So the fix is mostly: confirm/keep, fix one doc slip, optionally
generalize the existing mechanism into a reusable helper, and replace
`seon.ai/::type` when the JVM track resumes.

---

## 1. The grounded model (verified library facts, cited)

### 1.1 Datahike/Datomic: no entity type — an entity IS its attributes

Read `reference-code/datahike/src/datahike/`:

- **Schema is PER-ATTRIBUTE, not per-entity.** `schema.cljc:77-78`:
  `(s/def ::schema (s/keys :req [:db/ident :db/valueType :db/cardinality] :opt
  […]))` — a schema entry describes ONE attribute (its ident, value type,
  cardinality). `schema.cljc:84`: `(def required-keys #{:db/ident :db/valueType
  :db/cardinality})`. The legal keys of an attribute definition are enumerated
  at `schema.cljc:65` (`::schema-attribute`) — there is **no** entity-type /
  entity-class / table key anywhere in that set.
- **The DB record has no entity-type slot.** `db.cljc:302`:
  `(defrecord-updatable DB [schema eavt aevt avet … rschema …])`. The store is
  the four datom indexes plus `schema` (a map of attribute-ident →
  attribute-def) and `rschema` (reverse: property → attrs, `db.cljc:333`
  `-attrs-by`). `validate-schema` (`db.cljc:797-798`) iterates `[a-ident kv]`
  over that map — schema = attribute-ident → spec. Entities are enumerated by
  walking the **AEVT** index *for an attribute*; there is no "all entities of
  kind X" index because there is no kind.
- **Datahike's OWN notion of "entity shape" is already presence-based.**
  `schema.cljc:80`: `(s/def ::entity-spec (s/keys :opt [:db.entity/attrs
  :db.entity/preds]))` — Datomic's entity specs say "an entity tagged with this
  spec must carry these attrs and satisfy these preds." That is a **required-attr
  set + predicates** — a presence CONSTRAINT, not a discriminator field. Our
  `:seon.schema/required-attrs` is the same idea.

**Conclusion:** to ask "what kind is this entity?" in datahike you ask "which
identifying attribute(s) does it carry?" — never "what does its `:kind` field
say?", because there is no such field in the model.

### 1.2 Malli: identify a kind by structure (presence of required keys)

Read `reference-code/malli/src/malli/core.cljc`:

- **`:map` validator checks presence of required keys.**
  `core.cljc:1266-1287` (`-validator` for `-map-schema`): for each entry
  `[key {:keys [optional]} value]`, if the key is present validate its value;
  if ABSENT the entry is valid only when `optional` (`default (boolean
  optional)`, lines 1270-1280). So a **required key that is missing makes the
  map invalid** — exactly "kind = which required attrs are present." Maps are
  **open by default**: extra keys are rejected only under `:closed`
  (lines 1284-1285). Open-ness is why identification needs a **specificity**
  tie-break (below).
- **`m/parse` + `:orn` returns the matching kind as a tag.** A tagged-or
  `[:orn [:kind-a SchemaA] [:kind-b SchemaB]]` parsed with `m/parse` returns a
  `malli.core.Tag` whose `:key` is the branch that matched (or
  `:malli.core/invalid` if none). This is malli's native "fingerprint this
  value structurally and tell me what it is" — no dispatch field required.
- **`:multi` dispatches via an arbitrary fn, not necessarily a stored field.**
  `core.cljc:1861-1922`: `:multi` needs a `:dispatch` property (`core.cljc:1879`
  `dispatch (eval (:dispatch properties))`) applied to the VALUE
  (`core.cljc:1897-1898`: `(fn [x] (if-let [validator (find (dispatch x))]
  (validator x) false))`). The dispatch fn is *anything* — it can read a stored
  `:type` (the smell) OR be a structural predicate like
  `(fn [m] (cond (contains? m :a/id) :a …))`. So `:multi` does NOT mandate a
  discriminator; presence-based dispatch is a one-line dispatch fn.

**The model, stated once:**

> **An entity's kind is the set of attributes it carries — primarily its
> identity attr.** You IDENTIFY kind by (a) for stored entities: the
> required-attr subset test against registered entity schemas (most-specific
> wins); (b) for in-flight values: malli `m/parse` of an `:orn`/`:multi`
> fingerprint, or `m/validate` against the candidate schemas. You never store a
> `:kind`/`:type` field whose job is to select which schema a row obeys.

### 1.3 The crucial distinction the audit turns on

Not every keyword-valued field named `kind`/`type` is a violation. Two things
wear the same name:

- **Entity-kind discriminator (BANNED):** a STORED field whose VALUE selects
  *which schema / which attribute set* a row should obey — "is this row a
  session or a message or a tool-call?" This is what datahike has no concept of
  and what must be presence-based.
- **Attribute value enum (FINE, idiomatic):** a field that is a *flavor of an
  already-identified single kind* — a message's `:role "user"|"assistant"`, an
  error value's fault `:user-input`/`:core-bug`, a warning's source-check id.
  Enums are the correct tool here; these are not "kind" in the entity sense even
  when the keyword is literally named `kind`.

The audit question for each site is therefore: **does this field select the
entity's schema (BAD), or is it a value-flavor / derived label / library shape
(FINE)?**

---

## 2. Verified REPL proofs (live pod, session "default")

All run against the live CLJS pod (`db/*conn*` = the central wire-server
store). Probe rows were retracted afterward (verified `#{}` / `#{}`); a
`bin/seon cluster reset default` gives a fully pristine store.

### 2.1 Malli identifies kind purely by structure (no `:kind` field)

```clojure
(def schema-a [:map [:my.probe.a/id :string] [:my.probe.a/name :string]])
(def schema-b [:map [:my.probe.b/id :keyword] [:my.probe.b/score :int]])
(def ent-a {:my.probe.a/id "a-001" :my.probe.a/name "alpha"})
(def ent-b {:my.probe.b/id :b-001 :my.probe.b/score 42})
(defn identify-kind [candidates entity]
  (->> candidates (filter (fn [[_ s]] (m/validate s entity))) (map first)))
(identify-kind {:my.probe.a schema-a :my.probe.b schema-b} ent-a) ; => (:my.probe.a)
(identify-kind {:my.probe.a schema-a :my.probe.b schema-b} ent-b) ; => (:my.probe.b)
(m/validate schema-b ent-a) ; => false   (missing :my.probe.b/id → not that kind)
(m/validate schema-a ent-b) ; => false
```

Each entity validates against exactly its own schema; cross-validation is
false. No discriminator field.

### 2.2 The EXISTING pod mechanism keys on attribute presence, not a stored field

`renderable-kinds` (render.cljs) enumerates kinds by querying `:seon.schema`
rows that carry an `:seon.schema/id-attr` — the discriminators ARE the id-attrs;
there is no `:kind` column:

```clojure
;; kinds in the live store, by id-attr — no :kind field anywhere:
(db/query {:seon.db/db db :seon.db/query
  '[:find ?key ?id-attr :where [?s :seon.schema/key ?key]
                                [?s :seon.schema/id-attr ?id-attr]]})
;; id-attrs: :seon.fn/sym :seon.agent/id :seon.schema/key :my.kb.shared/id
;;           :seon.eval/id … (15 kinds total)

;; entity-primary-kind = most-specific kind whose required-attrs ⊆ entity keys.
;; A real :seon.fn entity, pulled with NO :kind/:type field:
(def a-fn (db/pull db '[*] <a :seon.fn/sym eid>))
;; present attrs: (:seon.fn/arglists :seon.fn/created-at :seon.fn/doc
;;   :seon.fn/fn-var? :seon.fn/ns :seon.fn/private? :seon.fn/source
;;   :seon.fn/spec :seon.fn/sym …)
;; required-attrs of :seon.fn = #{:seon.fn/ns :seon.fn/source :seon.fn/sym}
;; → identified-as :seon.fn   (subset test; presence only)
```

Confirmed: `:a-fn-identified-as :seon.fn`, from `:req-of-that-kind
#{:seon.fn/ns :seon.fn/source :seon.fn/sym}` all present on the entity. No
stored discriminator is read. `client.cljs:1838` (`prune-core-ghosts!`) does
the same in datalog: `(ground :fn)`/`(ground :test)`/`(ground :schema)` bound by
WHICH identity attr is present.

### 2.3 Full declared-entity path: `{:seon.db/entity true}` → presence + malli

```clojure
(schema/register! :my.probe.a/id   [:string  {:seon.db/identity true}])
(schema/register! :my.probe.a/name :string)
(schema/register! :my.probe.a [:map {:seon.db/entity true}
                               [:my.probe.a/id :my.probe.a/id] [:my.probe.a/name :string]])
(schema/register! :my.probe.b/id    [:keyword {:seon.db/identity true}])
(schema/register! :my.probe.b/score :int)
(schema/register! :my.probe.b [:map {:seon.db/entity true}
                               [:my.probe.b/id :my.probe.b/id] [:my.probe.b/score :int]])
```

Derivation + decomposition (in-memory, from `{:seon.db/entity true}`):

```clojure
(:seon.entity/id-attr (si/schema-properties (schema/schema-definition :my.probe.a)))
;; => :my.probe.a/id                ; derived, never stored on rows
(schema/entity-schema-tx-data :my.probe.a)
;; => [[:db/add "schema-:my.probe.a" :seon.schema/key          :my.probe.a]
;;     [:db/add "schema-:my.probe.a" :seon.schema/id-attr      :my.probe.a/id]
;;     [:db/add "schema-:my.probe.a" :seon.schema/required-attrs :my.probe.a/id]
;;     [:db/add "schema-:my.probe.a" :seon.schema/required-attrs :my.probe.a/name]]
```

Transact one of each, then identify by presence + malli (NO `:kind` field on
the stored rows):

```clojure
(db/transact! db/*conn* [{:my.probe.a/id "pa-1" :my.probe.a/name "alpha"}])
(db/transact! db/*conn* [{:my.probe.b/id :pb-1 :my.probe.b/score 7}])
(def pa (db/pull db '[*] <eid by :my.probe.a/id presence>))
(keys pa)               ; => (:db/id :my.probe.a/id :my.probe.a/name)  — no :kind/:type
(or (:kind pa) (:type pa)) ; => nil

;; (A) :orn + m/parse — the fingerprint TAG's :key IS the kind:
(def fp [:orn [:my.probe.a :my.probe.a] [:my.probe.b :my.probe.b]])
(m/parse fp pa)  ; => #malli.core.Tag{:key :my.probe.a, :value {…}}
(m/parse fp pb)  ; => #malli.core.Tag{:key :my.probe.b, :value {…}}
(m/parse fp {:zz/q 1}) ; => :malli.core/invalid   (no kind matches)
;; NB: read the kind via (:key tag), NOT (first tag) — Tag is a record,
;; (first tag) yields the MapEntry [:key :my.probe.a].

;; (B) :multi with a STRUCTURAL dispatch fn (presence predicate), no field:
(def multi-fp [:multi {:dispatch (fn [m] (cond (contains? m :my.probe.a/id) :my.probe.a
                                               (contains? m :my.probe.b/id) :my.probe.b
                                               :else ::none))}
               [:my.probe.a :my.probe.a] [:my.probe.b :my.probe.b] [::none :any]])
(:key (m/parse multi-fp pa)) ; => :my.probe.a

;; (C) plain validate — entity matches ONLY its own kind:
(m/validate :my.probe.a pa) ; => true
(m/validate :my.probe.b pa) ; => false
```

Also verified: `(db/installed-schema db)` shows `:my.probe.a/id` bridged to
`{:db/unique :db.unique/identity :db/valueType :db.type/string …}` and
`:my.probe.b/id` to `:db.type/keyword` — i.e. the `[:string|:keyword
{:seon.db/identity true}]` shape correctly produces a unique identity attr,
which is what makes presence-by-id-attr enumeration work.

**Net:** both the malli structural path AND the existing pod
`:seon.schema`/`id-attr`/required-attrs path identify kind with zero stored
discriminator, proven on live data.

---

## 3. Audit — every `:kind`/`:type` site, classified

Legend — **REPLACE** = stored entity-kind discriminator, must become presence;
**KEEP** = acceptable (value enum / derived label / library shape);
**WATCH** = acceptable today but flagged for the JVM-track cleanup;
**DOC** = a design-doc change only.

| # | Site | What it is | Verdict |
|---|------|-----------|---------|
| 1 | `seon.ai/::type` `[:enum :session :message :tool-call]` — `ai.clj:39`, pinned in entity schemas `ai.clj:241` `[::type [:= :session]]`, `:263` `[:= :message]` | STORED field selecting which of 3 distinct entity kinds (session/message/tool-call) a row is | **REPLACE** (JVM track, paused) |
| 2 | `seon.repl/:form/type` `:keyword` — `repl.clj:61`, on the single `:form` entity | Value-flavor (defn/expr/require) of ONE "REPL form record" kind — NOT a table selector. Also single-segment ns `:form/*` (today's CLJS `register!` gate would reject it) | **WATCH** (JVM track) |
| 3 | `:seon.error/kind` `:keyword` — def `error/instrument.cljc:62`; values `:user-input`/`:core-bug`/`:compile`/`:read`/`:seon.eval/repl-parity` + 4 `:seon.error.kind/malli-instrument-*`; read at `db/internal.cljs:1092` (retag user-input vs core-bug), `eval.cljs:1972` | FAULT/provenance tag on an error VALUE in `{:ok false :error{…}}` envelopes (errors are values, not stored entities). The user-input↔core-bug axis is orthogonal to "which subsystem" and genuinely needs a value tag | **KEEP** (optionally rename, §6 / Q1) |
| 4 | `:seon.warn/kind` `:keyword` — `warn.cljs:50`, every check emits one; render clusters by it + picks a template (`warn.cljs:1042`) | Source-check identifier on a DERIVED (never-stored) warning map. Exactly malli's error-`:type` "registry key into messages" pattern | **KEEP** (optionally rename, §6 / Q1) |
| 5 | `seon.db/::kind` / `:seon.db/kind` — `db.cljs:1125,1294` `store-inventory` | DERIVED label: `(keyword (namespace a))` = the attr namespace of present datoms. THE idiomatic model in action | **KEEP** (exemplar) |
| 6 | `?kind` in `client.cljs:1838` `prune-core-ghosts!` | DERIVED in datalog via `(ground :fn/:test/:schema)` bound by which id-attr is present | **KEEP** (exemplar) |
| 7 | render.cljs `:kind` keys — `renderable-kinds` `:201`, `kind-tables` `:251`, `entity-primary-kind` `:274`, `entity-render-slot` `:299` | The IMPLEMENTATION of the presence model (local map keys naming the derived kind). Feeds U's web tiles (cross-lane, §5) | **KEEP** (the mechanism) |
| 8 | `:seon.code/kind [:enum :seon.fn :seon.schema :seon.test]` — **PROPOSED** `toolkit-catalog.md:500`, in `:seon.code/forget-response` | A RETURN-value label saying which presence-distinguished kind `forget!` removed. Enum values = the namespace keywords. Acceptable AS a derived response label, but the doc must say it is derived from which id-attr is present, never stored | **DOC** (clarify) |
| 9 | `seon.test.runner/:type` — `:pass`/`:fail`/`:begin-test-ns`/`:summary`, many sites | `cljs.test` reporting-protocol field (third-party shape) | **KEEP** |
| 10 | `seon.repair/:type` — `repair.cljc:67,98` `:delimiter` etc. | rewrite-clj parser node type (third-party shape) | **KEEP** |
| 11 | `seon.ai` content `{:type "text"}` — `ai.clj:480`, `ai.cljs:348` | Anthropic/Claude message content-block `type` (third-party API shape) | **KEEP** |
| 12 | ex-info `:type` — `db.clj:238` `:seon.db/unregistered-namespace`, `core.clj:79` `:port-conflict`, `health.clj:455` `:pool`, `ctx.clj`/`session.clj` `:malli.generator/no-generator` | Clojure's `ex-info`/`ex-data` `:type` dispatch idiom on transient error values, not entities (some bare keywords — minor JVM-track nit) | **KEEP** |
| 13 | HTML `:type` attrs — `routes.clj:259`, `web/html.clj:106`, `ui/html.cljc:56` `{:type "module"\|"submit"}` | HTML element attribute | **KEEP** |
| 14 | `seon.ui.markdown/:kind` — `:151-205` `:code-fence`/`:heading`/`:bullets` | Local markdown-parser block classification (transient parse state) | **KEEP** |
| 15 | `seon.ns.view/::view-type` + `render.clj/typed` — `view.clj:52,405`, `render.clj:476,493` | JVM-track EXPLICIT render dispatch by a passed type label — the OLD typed-render API the pod already replaced with presence-based `entity-primary-kind` | **WATCH** (JVM track) |
| 16 | `:db.secondary/type :proximum` — `embed.clj:299,314` | datahike's OWN secondary-index type attr (`::secondary-index-attribute`, datahike `schema.cljc:67`). embed.clj:51,458 already note "There is NO `:seon/kind` enum — the TRIGGER IS the attribute" | **KEEP** (library shape; already idiomatic) |

### 3.1 Design-doc consistency

The anti-`:kind` stance is **consistent** across the design corpus:
`architecture.md:94,205,233`, `context-render.md:18,413`,
`layout-context-unification-design-2026-06-27.md:55-56`,
`reconciliation-recommendations-2026-06-27.md:24,132,221`, and
`data-model-2026-06-27.md` (§3.9, principle #7/#8) all assert "no `:kind`/`:type`
discriminator — discriminate by the carrier attribute / key presence." The ONE
doc to touch is `toolkit-catalog.md:500` (#8 above) — clarify `:seon.code/kind`
is a derived response label, not a stored attr.

### 3.2 Counts

- **Stored entity-kind discriminators (truly BAD): 1** — `seon.ai/::type`
  (JVM track, paused).
- **WATCH (JVM-track value-flavor / legacy dispatch): 2** — `seon.repl/:form/type`,
  `render.clj/typed`+`view-type`.
- **KEEP — value tags on non-entity values: 2** — `:seon.error/kind`,
  `:seon.warn/kind` (the two the data-model doc flagged; reclassified here as
  value tags, not entity discriminators).
- **KEEP — derived labels / the mechanism itself / third-party shapes: the rest.**
- **DOC fix: 1** — `toolkit-catalog.md:500`.

The active CLJS pod has **0** stored entity-kind discriminators.

---

## 4. Replacement design — generalize the existing mechanism

The fix GENERALIZES what already exists (`{:seon.db/entity true}` →
`:seon.entity/id-attr` → `entity-primary-kind`), it does not invent anything.
Two reusable surfaces (both REPL-proven in §2):

### 4.1 Stored entities — `entity-kind` (promote the existing subset test)

`entity-primary-kind` (render.cljs:274) is private to render and does exactly
the right thing (most-specific kind whose `:seon.schema/required-attrs` ⊆ the
entity's keys). Promote the LOGIC to a public, reusable fn so non-render callers
(forget!, inventory, any "what is this row" need) share one implementation
rather than re-deriving it:

```clojure
;; in seon.db (it already owns :seon.schema reads + the kind cache lives in render)
(schema/register! :seon.entity/kind :keyword)   ; a DERIVED return label, never stored on rows
(defn entity-kind
  "The most-specific registered kind whose required-attrs are all present on
   `entity` (presence subset test against :seon.schema rows), or nil. Pure
   read; never reads a stored :kind field. Most-required-attrs wins;
   alphabetical tie-break."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db] [:seon.render/node :map]]
                  [:maybe :seon.entity/kind]]}
  [db entity] …)
```

Keep render's per-db `!kind-cache` as the perf path; `entity-kind` can reuse
`kind-tables`. (Do NOT change render's existing private fns' signatures — U's
web lane calls them; see §5. ADD the public fn alongside.)

### 4.2 In-flight values — `fingerprint` / `kind-of` (malli `:orn`)

For values NOT in the DB (wire envelopes, in-memory maps, anything you want to
classify without a db handle), build an `:orn` over the registered entity
schemas and `m/parse`:

```clojure
(defn fingerprint
  "An :orn schema over every registered {:seon.db/entity true} kind, branches
   ordered most-specific-first (descending required-attr count) so open-map
   overlap resolves to the most specific kind."
  {:malli/schema [:=> [:cat] :seon.schema/form]}
  []
  (into [:orn] (->> (schema/entity-schema-keys)
                    (sort-by #(- (or (schema/schema-required-count %) 0)))
                    (map (fn [k] [k k])))))

(defn kind-of
  "The registered entity kind `value` structurally IS, or nil. malli m/parse of
   (fingerprint); reads the matched Tag's :key (NOT first)."
  {:malli/schema [:=> [:catn [:seon.render/node :map]] [:maybe :keyword]]}
  [value]
  (let [r (m/parse (fingerprint) value)]
    (when-not (= :malli.core/invalid r) (:key r))))
```

**Specificity caveat (from §1.2 + §2):** malli `:map` is OPEN, so a value with
attrs of two kinds could match both. The DB path (`entity-kind`) already
handles this (most-required-attrs wins). For the `:orn` path, order branches by
descending required-attr count (above) so `m/parse` returns the most specific
match first. Standardize on "most-specific (most required attrs) wins,
alphabetical tie-break" everywhere (Q4).

### 4.3 What stays a value enum (NOT converted)

`:seon.error/kind`, `:seon.warn/kind`, `:role`, `:form/type`, the markdown
`:kind`, the cljs.test `:type` — these are flavors / library shapes / value
tags, NOT entity-kind selectors. They stay enums. The model in §1.3 is the rule:
convert only fields that SELECT a schema.

---

## 5. Migration path (dependency-ordered)

### Step 0 — lock the model (doc) — no code

Adopt §1.3's entity-kind-vs-value-enum distinction as the canonical rule in
`data-model-2026-06-27.md` (it currently says "no `:kind` anywhere," which
over-reaches and is why `:seon.error/kind`/`:seon.warn/kind` read as
violations). State: BANNED = stored field that selects a schema; FINE = value
enum / derived label / library shape.

### Step 1 — fix the one doc slip (DOC) — no code, no silent-failure risk

`toolkit-catalog.md:500`: annotate `:seon.code/kind` as a DERIVED response label
(from which id-attr the forgotten entity carries), the enum values being the
namespace keywords, never persisted. Optionally have `forget!`'s impl call
`db/entity-kind` (§4.1) to produce it.

### Step 2 — (optional) generalize the mechanism (CODE, active track)

Add `seon.db/entity-kind` (§4.1) and `seon.schema/fingerprint`+`kind-of`
(§4.2), both REPL-proven. Pure ADD — no existing call site changes. Lets
forget!, inventory, and future callers stop re-deriving. **Cross-lane note:** do
NOT alter the existing `render.cljs` private kind fns' signatures — U's
`web/**` lane (live_tile.cljs, value.cljs, sci.cljs, default.cljs, the
value-explorer) renders via `render-entity-html`/`entity-render-slot`, which sit
on `entity-primary-kind`. Add the public fn beside them; coordinate with U
before any render.cljs signature change.

### Step 3 — value-tag naming (CODE, OPTIONAL, owner decision — Q1)

If we want the word "kind" gone from value tags: rename `:seon.warn/kind` →
`:seon.warn/check` and `:seon.error/kind` → `:seon.error/fault`. **This is the
only step with silent-failure risk:** `:seon.error/kind` is read in
`db/internal.cljs:1092` (retag user-input vs core-bug → decides whether an
error surfaces as caller-fixable) and `eval.cljs:1972`; a MISSED read would
mis-surface errors with no exception. So if done, it must be ONE atomic patch
across: `error/instrument.cljc` (def + `kind-set` + `instrument-error?`),
`db/internal.cljs` (~15 produce sites + the 1092/1093/1164 reads), `eval.cljs`
(869, 1972-1973, 2371, 2783, 2813), `schema/internal.cljc` (142, 166),
`db.cljs` (775, 855), `web/reactive/transform.cljs:92`, `warn.cljs` (50, 83,
191, all check sites, render-warnings), plus tests + the design docs that name
them. **Recommendation: DEFER** — they are already correct value tags; the
churn/risk outweighs retiring a word. Decide via Q1.

### Step 4 — JVM-track replacements (CODE, when JVM resumes — paused)

- **`seon.ai/::type` (the one real BAD):** split into three declared
  `{:seon.db/entity true}` schemas with distinct identity attrs
  (`:seon.ai.session/id`, `:seon.ai.message/id`, `:seon.ai.tool-call/id`), drop
  `::type` and the `[::type [:= …]]` pins, identify by presence /
  `db/entity-kind`. Touches `ai.clj` (schemas + every read that branches on
  `::type`) + its tests. Atomic.
- **`seon.repl/:form/type`:** migrate ns to multi-segment (`:seon.repl.form/*`)
  to satisfy the `register!` gate; keep `type` as a value-flavor enum on the one
  form-record kind (NOT a table selector).
- **`render.clj/typed` + `view-type`:** when JVM render resumes, replace the
  explicit typed-dispatch with the presence-based `entity-kind` the pod already
  uses (converge the two render lanes).

Do Steps 4 only when the JVM track un-pauses; flag now so they're not lost.

---

## 6. Open questions for the owner

1. **Retire the word "kind" from the two value tags?** `:seon.error/kind` and
   `:seon.warn/kind` are value tags, NOT entity discriminators, so they do not
   violate the idiomatic-presence rule — but they keep the banned word. Rename
   to `:seon.error/fault` / `:seon.warn/check` (atomic, ~30 sites,
   silent-failure risk on the error retag), or KEEP + document the
   entity-vs-value distinction? (Recommendation: KEEP + document.)
2. **Promote the mechanism to public helpers** (`seon.db/entity-kind`,
   `seon.schema/fingerprint`/`kind-of`), or leave kind-identification
   render-private until a second caller actually needs it? (forget! is the
   pending second caller.)
3. **JVM-track `seon.ai/::type` + `seon.repl/:form/type`** — fix now as a small
   atomic patch even though the track is paused, or defer until JVM resumes?
4. **Standardize the specificity tie-break** as "most required attrs wins,
   alphabetical tie-break" for BOTH the DB path and the `:orn` path? (The DB
   path already does this; the `:orn` path needs branch ordering.) Confirm this
   is the canonical rule.
