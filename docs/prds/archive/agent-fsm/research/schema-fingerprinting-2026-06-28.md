---
type: research
status: active
tags: [research, schema, database, agent]
---

# Schema-based fingerprinting — where data lives, by the kinds that ARE our schemas

Owner-directed research + design. Deliverable only — NO src commits (the
`store-inventory` / render / context lanes are being live-edited in parallel).
Every representation below was built and `println`'d against the LIVE pod
(7890, `:client` runtime) on 2026-06-28; outputs are pasted verbatim.

## TL;DR

- **The registered Malli schemas ARE the legitimate "kinds."** We do not need a
  `:kind` attr and we should stop calling `store-inventory`'s attr-namespace
  groups "kinds" as if they were primary — they are a PROXY. The primary kind is
  the registered entity-shape `:map` schema (the 16 `{:seon.db/entity true}`
  maps), and its `:seon.entity/id-attr` is exactly how you enumerate instances.
- **The schemas already live in the DB as data.** 544 `:seon.schema` rows
  (one per registered schema, ≈ the 554 in the in-memory registry) each carry a
  replayable `:seon.schema/source`, `:seon.schema/ns`, and — for entity maps —
  `:seon.schema/id-attr` + `:seon.schema/required-attrs`. **This durable corpus
  is currently UNUSED by agent context.** That is the headline gap.
- **A fingerprint is the join of (schema shape) × (live datom population).**
  For each kind: required (•) vs optional (○) attrs, ref attrs and their target
  kind (→), the entity count, and per-attr `[rows / entities]` population. This
  single join answers all three owner asks — WHERE data lives (kind→attrs),
  WHICH attrs are relevant (required + populated, refs), HOW MUCH (counts +
  density) — in one readout.
- **Recommended winner: Candidate A (the "fingerprint card") as the canonical
  data, with Candidate B (one-line scan) as the default rendered density and
  Candidate D (registered-vs-inferred drift) as a derived WARNING, not a
  standing block.** A new `schema-block` supersedes the "kinds" framing inside
  `inventory-block`.
- **Where do we explain schemas today? Effectively nowhere as a shape.** `my.*`
  source is rendered full (so its `register!` calls are visible as code), and
  `inventory-block` lists attr-namespaces + counts + sample values — but NO
  surface shows an agent the registered SHAPE (required/optional, refs between
  kinds, the entity grouping). The 544 durable `:seon.schema` rows go unread.

## Ground truth observed on the live store

```clojure
(let [all (schema/registered-schemas) ent (schema/entity-schema-keys)]
  {:total-registered (count all) :entity-schema-count (count ent)})
;=> {:total-registered 554, :entity-schema-count 16}

;; Every registered schema is ALSO a durable :seon.schema row:
;;   stored-count 544, registered-count 554, registered-not-stored 10
;; (the 10 un-teed are runtime-registered my.kb.* loose attrs)

;; A scalar schema row:
{:seon.schema/key :my.kb/question
 :seon.schema/source "(seon.schema/register! :my.kb/question :string)"
 :seon.schema/ns {:db/id 133}}

;; An entity-map schema row additionally carries id-attr + required-attrs:
{:seon.schema/key :seon.agent.todo/todo
 :seon.schema/id-attr :seon.agent.todo/id
 :seon.schema/required-attrs [:seon.agent.todo/created-at :seon.agent.todo/id
                              :seon.agent.todo/title]
 :seon.schema/source "[:map {:seon.db/entity true, :seon.entity/id-attr …} …]"}
```

The 16 entity kinds, by live volume:

```
:seon.fn 585  :seon.schema 544  :seon.test 216  :seon.ns 106  :seon.eval 13
:seon.route/route 6  :seon.agent.turn 4  :seon.agent.message 3  :seon.agent 2
:seon.agent.run 2  :seon.user 1  :my.kb.shared/shared 1  :seon.agent.todo/todo 1
:seon.agent.schedule 0  :seon.ai/config 0  :seon.web.brand/brand 0
```

### The fingerprint primitive

For a kind we read its stored Malli form, split entries into required/optional,
detect `:seon.db/ref` attrs (and sample one ref to infer its target kind by the
target entity's id-attr namespace), then count `[rows / entities]` per attr:

```clojure
(entity-fingerprint :seon.agent.todo/todo)
;=> {:kind :seon.agent.todo/todo :id-attr :seon.agent.todo/id :count 1
;    :attrs [{:attr :…/id      :optional false :type [:and {:identity} :seon.db/id] :rows 1}
;            {:attr :…/title   :optional false :type [:string {:min 1}]            :rows 1}
;            {:attr :…/message :optional true  :type :seon.db/ref                  :rows 1}
;            {:attr :…/parent  :optional true  :type :seon.db/ref                  :rows 0}
;            …]}
```

## The candidate representations (verbatim live output)

### Candidate A — the "fingerprint card" (canonical, full detail)

`•` required, `○` optional, `→kind` = ref target, `[rows/entities]` = population.
~1029 tokens for the 13 kinds that hold data. Excerpt:

```
; :seon.eval  (13 entities, id=id)
;   • id [:and {:seon.db/identity true} :seon.db/id]  [13/13]
;   • source :string  [13/13]
;   • ok? :boolean  [13/13]
;   • at :inst  [13/13]
;   ○ agent :seon.db/ref  [11/13]
;   ○ duration-ms :int  [13/13]
;   ○ result-edn :string  [12/13]
;   ○ output :string  [0/13]
;   ○ error :string  [1/13]
;   ○ error-data :string  [0/13]

; :seon.agent.turn  (4 entities, id=id)
;   • id [:and {:seon.db/identity true} :seon.db/id]  [4/4]
;   • at :inst  [2/4]
;   • status [:enum :running :done :error]  [2/4]
;   ○ run :seon.db/ref  [2/4]
;   ○ evals [:vector {:seon.db/component true} :seon.db/ref]  [4/4]

; :seon.agent.message  (3 entities, id=id)
;   • from :seon.db/ref  [3/3]
;   • to [:vector :seon.db/ref]  [3/3]
;   • content :string  [3/3]
;   • origin [:enum :human :agent :core]  [3/3]

; :seon.agent.todo/todo  (1 entities, id=id)
;   • title [:string {:min 1}]  [1/1]
;   • created-at :inst  [1/1]
;   ○ message :seon.db/ref  [1/1]
;   ○ depends-on [:vector :seon.db/ref]  [0/1]
```

What it gets right: it is the COMPLETE answer in one place — the agent sees the
shape (so it knows what attrs exist before grepping), the required core (what it
MUST supply to add a row), the ref edges (how kinds link), and the live density
(`output [0/13]` is a dead column; `result-edn [12/13]` is near-universal). The
`[rows/entities]` column is the "which attributes are relevant / how much data"
axis the owner asked for, computed not asserted.

Cost: at full detail it grows with total attr count. For the current 13 kinds
that is fine (~1k tokens); a cluster with many populated kinds would want the
compact form (B) as the default and the card on demand per kind.

### Candidate B — one-line density scan (default rendered form)

~241 tokens for all 13 kinds. `req:` = required attrs, `+used:` = optional attrs
that actually carry data, `→` = ref targets, `·unused×N` = registered-but-empty
columns.

```
; :seon.fn ×585  req:sym,ns,source  +used:fn-var?,arglists,doc,private?,spec,created-at  ·unused×1
; :seon.schema ×544  req:key,source  +used:ns,created-at
; :seon.test ×216  req:sym  +used:ns,source,created-at  ·unused×4
; :seon.ns ×106  req:name,source
; :seon.eval ×13  req:id,source,ok?,at  +used:agent,duration-ms,narration,ns,result-edn,error  ·unused×2
; :seon.route/route ×6  req:pattern,method,name,handler  +used:middleware  ·unused×1
; :seon.agent.turn ×4  req:id,at,status  +used:run,prompt-chars,prompt-file,debug-dir,llm-usage,evals  ·unused×2
; :seon.agent.message ×3  req:id,from,to,content,at,hops,origin
; :seon.agent ×2  req:id  +used:purpose,parent,ai,html  ·unused×5
; :seon.agent.run ×2  req:id,agent,started-at,trigger,status,turn-limit,deadline  +used:cause,last-beat-at,closed-reason  ·unused×2
; :seon.user ×1  req:id
; :my.kb.shared/shared ×1  req:id  ·unused×1
; :seon.agent.todo/todo ×1  req:id,title,created-at  +used:message  ·unused×3
```

This is the right DEFAULT for the context: it is a 1-line-per-kind map an agent
(or human) scans to decide what to query, it carries the required attrs to write
a row, and `·unused×N` flags dead surface without dumping it. It loses the
per-attr counts and the exact ref targets (folded into a count/`→` summary) —
those live in Candidate A, fetched on demand per kind.

### Candidate D — registered-vs-inferred drift (derived WARNING, not a block)

`malli.provider/provide` infers a shape from sampled stored entities; diffing it
against the registered shape catches what pure counts cannot — optionality
mismatches in both directions:

```clojure
(drift :seon.agent.turn :seon.agent.turn/id)
;=> {:registered-never-seen        (:seon.agent.turn/llm-meta :seon.agent.turn/llm-retries)
;    :optional-but-always-present  (:seon.agent.turn/evals)
;    :required-but-sometimes-absent (:seon.agent.turn/at :seon.agent.turn/status)}

(drift :seon.eval :seon.eval/id)
;=> {:registered-never-seen        (:seon.eval/error-data :seon.eval/output)
;    :optional-but-always-present  (:seon.eval/duration-ms :seon.eval/narration :seon.eval/ns)}

(drift :seon.agent.run :seon.agent.run/id)
;=> {:registered-never-seen        (:seon.agent.run/paused-at :seon.agent.run/remaining-ms)
;    :optional-but-always-present  (:seon.agent.run/closed-reason)}
```

This surfaced a REAL, reproducible data-integrity bug, verified DB-only (robust
to the registry stomp described below):

```clojure
;; :seon.agent.turn/at and /status are registered REQUIRED (no {:optional true})
;; yet 4 of 6 live turns lack them:
{:turns 6 :have-at 2 :have-status 2}
```

So either the writer is leaving required attrs off, or the schema marks
`at`/`status` required when they are populated late. Either way it is a bug the
fingerprint flags for free. `optional-but-always-present` (e.g. `:seon.eval/ns`,
`duration-ms`) is the inverse nudge: the schema under-commits relative to
reality and could tighten.

This is the most expensive candidate (it pulls full sample entities and runs the
provider), so it should NOT be a standing context block — it is a derived
warning. It belongs in `seon.warn` (reactive-context: render only when a drift
exists; when the schema/data agree it returns "" and vanishes), exactly the
pattern the memory note "derive warnings, don't store them" prescribes.

## Two findings that shape the design

1. **Source the fingerprint from the DB rows, not the in-memory registry.**
   Mid-session the bootstrap `relink-registry!` stomp (documented in
   `seon.schema/relink-registry!`) deregistered leaf attrs — `:seon.agent/ai`
   went from registered to `registered? false` between two evals, while its
   `:seon.schema/source` row stayed intact in the DB. The durable
   `:seon.schema/*` rows are the trustworthy fingerprint source; the volatile
   `*schemas` atom is not. (The `required-attrs` are already decomposed into
   datoms; the full form is the `:seon.schema/source` string.)

2. **Entity-map kinds do not cover all data.** The live `my.kb.*` knowledge
   (`my.kb.source/*`, `my.kb.finding/*`, `my.kb.author/*`) is LOOSE scalar
   attrs with no entity-map schema, so a fingerprint keyed only on
   `entity-schema-keys` misses them. The fingerprint must be the UNION of
   (entity-map kinds, rendered with full shape) and (attr-namespaces that have
   data but no entity-map schema, rendered as `store-inventory` already does —
   a flat attr+count list). The entity-map case is the rich one; the loose case
   degrades gracefully to today's behavior.

## Where are we explaining schemas? (audit)

| Surface | What it shows about schemas | Gap |
| --- | --- | --- |
| `inventory-block` (`agent/ctx/inventory.cljs`) | attr-namespace ("kind") → `attr count «sample values»` | NO shape: no required/optional, no ref edges, no entity grouping; calls attr-ns "kinds" though the real kind is the `:map` schema |
| `namespaces-block` (full `my.*` / whitelist source) | the literal `register!` calls in rendered source | only for FULL-rendered nses; it's code, not a fingerprint; framework + non-full kinds invisible except via grep |
| DB `:seon.schema` rows (544, with source) | the COMPLETE durable shape corpus | **read by NOTHING in agent context** |
| `system-text` prose | teaches "query/grep, reuse shapes" | no concrete shape data |

Net: an agent is told to reuse shapes and query attrs, and can grep source, but
is never SHOWN the catalogue of kinds with their shapes and populations. The one
place built for it (the 544 durable rows) is dark.

## Recommendation

**1. Add a `schema-block` (Core lane, `seon.agent.ctx/`) that supersedes the
"kinds" framing.** It renders Candidate B (one-line density scan) by default,
sourced from the DB: the entity-map kinds from `:seon.schema/{key,id-attr,
required-attrs,ns}` rows joined to live per-attr datom counts, UNIONed with
loose attr-namespaces from `store-inventory`. Reactive: a kind line appears when
its first row lands and vanishes when emptied (pure fn of the db, stores
nothing). User-domain (`my.*` + third-party) kinds first, framework kinds after
— same ordering policy as `store-inventory`.

**2. Fold the existing `inventory-block` value-sampling into it (do NOT keep
two surfaces).** `inventory-block` today does the genuinely useful thing of
showing low-card lookup values (`«:open :closed»`) inline; that belongs on the
per-attr line of the fingerprint, not in a parallel section. This is a
"don't be a dumbass" merge — one block, not `inventory` + `schema`. The merged
block is the single "what does this store hold, by kind" surface.

**3. Candidate A (card) is the on-demand drill-down, not a standing block.**
Expose it as an agent-callable render fn (`schema-card <kind>` → the full
fingerprint card) and as the per-kind expansion in the inspector UI. The
context carries the compact scan; the agent pulls the card for a kind it's about
to write to.

**4. Candidate D (drift) → `seon.warn`, reactive.** A `check-schema-drift`
warning that renders the `required-but-sometimes-absent` /
`registered-never-seen` lines ONLY when a drift exists. Self-heals: fix the
writer or the schema and the warning vanishes.

### Impl plan + Core/UI lane split

This is cross-lane; flagging the split, not implementing.

- **Core / `seon.db` (or a new `seon.schema.fingerprint`):** the pure data
  primitive — `(schema-fingerprint db)` → `[{:kind :id-attr :count :attrs
  [{:attr :req :type :rows :ref-to}]}]`, sourced from `:seon.schema` rows +
  datom counts, UNIONed with loose `store-inventory` namespaces. This is the
  one computation both the context block and the UI consume. Reuse
  `bootstrap-row-ids` for the post-bootstrap scope already established in
  `store-inventory`. **Decision needed with the `store-inventory` owner:** does
  the fingerprint REPLACE `:seon.db/kinds` (re-shape its rows to carry the
  schema fields) or sit beside it? Recommend replace — `:seon.db/kinds` becomes
  the fingerprint rows, killing the "kinds=attr-namespace" proxy at the source.
- **Core / `seon.agent.ctx`:** the `schema-block` render fn (Candidate B text)
  wired into `default-seed-blocks`, REPLACING `inventory-block`, with the value
  sampling folded in. `seon.warn/check-schema-drift` (Candidate D).
- **UI / render arc (my lane):** the per-kind fingerprint card (Candidate A) as
  a collapsible tile in the `/data` browser (drill from the scan line to the
  card to the instances), and the drift warning styled in the warnings tile.
  The UI consumes the SAME `schema-fingerprint` primitive — no parallel query.

The token budget is small (B ≈ 240 tokens for the whole store; the card is
pulled on demand), so this rides near the prompt tail like the current
inventory, out of the cacheable prefix.

## Appendix — reproduce

All against the live `:client` runtime (pod 7890). Primitives:
`schema/entity-schema-keys`, `schema/schema-definition`, `m/form`,
`malli.provider/provide`, `db/query` for `[?e attr _]` counts, `:seon.schema/*`
rows for the durable shape. The card/line/drift renderers used above are ~15
lines each and are pasted in this session's transcript.
