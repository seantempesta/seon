---
type: research
status: active
tags: [research, agent, database]
---

# Structural-preview generic-default renderer

A story-telling abbreviated preview for the seon context system. When a
renderable kind has no explicit `:seon.render/ai` / `:seon.render/html`
metadata, the fallback renderer must let an agent infer a value's SHAPE
from the preview and write a TARGETED follow-up query — instead of getting
dumb first-N-chars of a `pprint`'d string.

## TL;DR — recommendation

- **No new dep.** Build the previewer from ClojureScript built-ins:
  `*print-level*` + `*print-length*` (confirmed CLJS-supported in both
  `cljs.core/pr-str` and `cljs.pprint`) plus a small custom
  `describe-shape` function. This is the right CLJS-viable answer; it is
  also coherent with the existing `clip-value` mechanism in `seon.eval`
  (structural-first, char-cap-as-backstop).
- **`fipp`** is CLJS-capable and honors `:print-length` / `:print-level`,
  but it is NOT already a dep and adds nothing over `cljs.pprint` for our
  need (we already truncate via the dynamic vars). Verdict: skip — adding
  a pretty-printer dep violates "prefer NO new dep" with no payoff.
- **`zprint`** — CLJS-capable but heavy (its own dep + config surface),
  same verdict: skip.
- **`clojure.datafy` / `nav`** — present in CLJS core (`clojure/datafy.cljs`),
  BUT `datafy` is identity for plain maps/vectors (no protocol impls for
  ordinary collections — only `js/Error`, `Var`, `Atom`, etc. are extended).
  It does NOT help the `:ai` text preview of plain query/pull data. It IS
  the right conceptual model for the `:html` lazy drill-in tree (portal's
  model), but we implement that drill-in directly against our hiccup live
  tile — we do not need the protocol as a dep.
- **`portal` (djblue)** — already in `deps.edn` (`djblue/portal 0.57.3`) but
  JVM-side only and not bundled into the CLJS pod. We borrow its *model*
  (datafy/nav + lazy expandable data-tree) for the `:html` view; we do not
  add it to the pod.
- **The shape-summarizer idea** (`{:type :vector :count 247 :elem-shape {…}
  :sample […]}`) is exactly right and is the core of the design — a small
  pure `describe-shape` fn, ~40 lines, no dep.

**The previewer = `describe-shape` (the story header) + a depth/length-bounded
`pr-str` SAMPLE (the body) + a drill-in HINT (the id / a pull|query template),
with the char cap only as a final backstop.** It replaces the body of
`seon.render/generic-default-renderer` (`render.cljs:541-557`) and shares the
cap policy with `seon.eval/clip-value` (the task-#18 consolidation).

---

## 1. CLJS-compatible libraries / idioms

### 1a. `*print-level*` / `*print-length*` — RECOMMENDED baseline (built-in, no dep)

Confirmed in the vendored ClojureScript source:

- Both vars are defined in `cljs.core` (`reference-code/clojurescript/src/main/cljs/cljs/core.cljs:154-175`):

  > `*print-length* controls how many items of each collection the [printer prints] …`
  > `*print-level* controls how many levels deep the printer will [print]; … the printer prints '#' to represent it.`

- `pr`/`pr-str` honor them — the printer decrements `*print-level*` per level
  and emits `#` past the bound (`core.cljs:10381-10382`):

  ```clojure
  (binding [*print-level* (when-not (nil? *print-level*) (dec *print-level*))]
    (if (and (not (nil? *print-level*)) (neg? *print-level*)) "#" …))
  ```

- `cljs.pprint` also honors them (`pprint.cljs:615, 740-741, 782-783`):

  > `*print-length*, *print-level*, *print-namespace-maps* and *print-dup* are defined in cljs.core`
  > ```clojure
  > cljs.core/*print-length* (:length options cljs.core/*print-length*)
  > cljs.core/*print-level*  (:level  options cljs.core/*print-level*)
  > ```

**Verdict: USE.** This is the depth-N / first-M-with-`…` mechanism the
task calls the "depth-2 snapshot". `(binding [*print-level* 2 *print-length*
8] (pr-str v))` gives shape-to-depth-2, first-8-per-level, `#` for deeper
nodes, `...` for over-length collections — in pure CLJS, zero deps. This is
the SAMPLE body of the previewer.

Note: `pr-str` (compact) is preferred over `pprint` for the SAMPLE — it is
denser (fewer wasted newline tokens) and reads as eval'able Clojure. The
current generic default uses `pprint/pprint` (`render.cljs:553`); the
improved version uses bounded `pr-str` for the sample and reserves the
multi-line shape header for the story.

### 1b. `clojure.datafy` / `nav` — useful model, not useful dep here

Present in CLJS (`reference-code/clojurescript/src/main/cljs/clojure/datafy.cljs`).
`datafy` returns the value of `clojure.core.protocols/datafy`; the CLJS impls
extend only `js/Error`, `ExceptionInfo`, `Var`, `Reduced`, `Atom`, `Volatile`
— NOT plain maps/vectors (those datafy to themselves). So `datafy` adds
nothing to a preview of query/pull data, which is already plain data.

`nav` is the "drill-in" protocol — the right *idea* for the targeted next
query, but our drill-in is "pull the entity by its id" (a datalog query),
not a `nav` call. **Verdict: borrow the MODEL for the `:html` lazy tree,
do not require it for the `:ai` text.**

### 1c. `fipp` — capable, but no payoff (skip)

`fipp` supports CLJS "from build 3269 and up" and its `EdnPrinter` carries
`print-length` / `print-level` fields analogous to the core dynamic vars;
`:print-length` is implemented with a `take` transducer and appends `"..."`.
But it is not a current dep, and since we already get length/level
truncation from `cljs.core` + `cljs.pprint`, fipp buys us nothing.
**Verdict: skip (no new dep).**

### 1d. `zprint` — capable, heavy, skip

CLJS-capable, width-and-depth-limited pretty-print, but a large dep with its
own config surface. Same verdict as fipp: no payoff over the built-ins.
**Verdict: skip.**

### 1e. `portal` (djblue) — model only, already a JVM dep, not pod-bundled

`djblue/portal 0.57.3` is in `deps.edn:122` (JVM track). Its model — datafy/nav
+ lazy expandable data-tree, expand-on-click — is exactly what the `:html`
right-pane drill-in should feel like. We implement that with our own hiccup
+ the existing live-tile expand/collapse pattern; we do NOT bundle portal
into the CLJS pod. **Verdict: borrow model, no dep.**

### 1f. Dedicated shape-summarizer — BUILD IT (small custom fn)

The `{:type … :count … :elem-shape … :sample …}` summarizer is the heart of
the design and is ~40 lines of pure CLJS. No library does this the way we
want (anchored to our DB shapes + drill-in hints). Build `describe-shape`.

---

## 2. Data-shape catalog (STATIC — from query/pull/render code + schemas)

Headline: **the generic default sees five recurring families** — datalog
result sets, pull maps, datom triples, the store-inventory vector-of-rows,
and program-graph / domain entity maps. Sources below are all read-only
static reads of `src/`.

### 2a. `db/query` result — a set/vector of tuples (vectors)

`seon.db/query` (`db.cljs:465`) returns datalog `:find` results: a collection
of tuples. For `:find ?e ?a ?v` → `#{[12 :foo/bar "x"] …}`; for scalar
aggregates → a single 1-tuple. These overflow on broad queries (the
`result-row-cap 50` clip in `eval.cljs:2136` exists precisely for this).

GOOD preview: *"datalog result — 247 tuples of arity 3; sample [[12 :seon.fn/sym
"foo"] …(8)…]. Narrow with a tighter :where / a :find aggregate; the full
value is `result/<id>`."* Names the type, the row count, the tuple arity,
8 sample tuples, and the drill-in (narrow query OR `result/<id>` deref).

### 2b. `db/pull` / `pull-by-name` result — nested map with refs + components

`seon.db/pull` (`db.cljs:799`). A pull map nests component children (e.g.
`:seon.agent/sessions` → `[:vector {:seon.db/component true} :seon.db/ref]`,
`agent.cljs:45`; `:seon.agent/ctx`, `agent.cljs:51`) and ref attrs. A
`[*]` pull of an agent or turn is deeply nested (sessions → turns → evals).

GOOD preview: *"map, 9 keys: [:seon.agent/id :seon.agent/state
:seon.agent/sessions …]; :seon.agent/sessions → vector(3) of maps; depth-2
sample {…}. Drill: `(db/pull '[{:seon.agent/sessions [*]}] [:seon.agent/id
"…"])`."* Names map + key count, lists the TOP keys (the queryable surface),
flags which keys hold nested collections + their counts, then a depth-2
sample, then a refined pull template.

### 2c. Datoms — `[e a v t added]` (or our reader-safe `{:seon.eval/datom [e a v]}`)

Raw datahike Datoms are opaque records; `seon.eval/opaque-summary`
(`eval.cljs:2000-2013`) already projects a Datom → `{:seon.eval/datom
[e a v]}`. A `d/datoms` / index scan returns a seq of these.

GOOD preview: *"datom seq — N entries [e a v t added]; sample [[12 :seon.fn/sym
"foo" 13 true] …]. Attrs seen: #{:seon.fn/sym :seon.fn/source}."* Names the
datom tuple positions and the distinct attrs in the slice (the most useful
orientation — "what attributes am I looking at").

### 2d. `store-inventory` — vector of `{:seon.db/kind … :seon.db/attrs {…}}` rows

`seon.db/store-inventory` (`db.cljs:1071`, schema `[:vector ::inventory-row]`,
`db.cljs:1138`). Already a digest, so it rarely hits the generic default
(it has its own section renderer, `inventory.cljs`). But if pulled raw:

GOOD preview: *"vector(14) of inventory rows; kinds: [:my.kb.codebase
:my.workout … :seon.agent]; sample {:seon.db/kind :my.kb.codebase
:seon.db/attrs {…2…}}."* Names it as the inventory shape and lists the kinds.

### 2e. Program-graph + domain entity maps (`:seon.eval`, `:seon.fn`, `:seon.ns`,
`:seon.schema`, `:seon.agent.message`, `:seon.agent.todo`, knowledge-base rows)

Entity `:map` schemas (`agent.cljs:126-180`+). These ARE renderables with
their own slots when rendered as a section, but a raw `db/pull` of one, or a
`:my.kb.codebase` row with no registered renderer, lands on the generic
default. Their strings (`:seon.fn/source`, `:seon.eval/result-edn`,
`:seon.eval/source`) can be large.

GOOD preview: *"map (:seon.fn): keys [:seon.fn/sym :seon.fn/ns :seon.fn/source
:seon.fn/doc …]; :seon.fn/source is a 1.4k-char string (clipped); :seon.fn/sym
= "seon.db/query". Drill: `(db/pull '[*] [:seon.fn/sym "seon.db/query"])`."*
KEY behavior: a big STRING value inside the map is summarized as "Nk-char
string" + its leading line, NOT dumped — that is the difference between
story-telling and first-N-chars. The identity attr (here `:seon.fn/sym`,
`agent.cljs:65`, `:seon.db/identity true`) is surfaced as the drill-in key.

### Catalog summary table

| Family | Shape | Story header names | Drill-in hint |
|--------|-------|--------------------|----------------|
| query result | coll of tuples | type, row count, tuple arity, N sample | narrow `:where` / `result/<id>` |
| pull map | nested map (refs/components) | key count, TOP keys, nested-coll counts | refined `db/pull` template |
| datoms | seq of `[e a v t added]` | count, `[e a v t added]` legend, distinct attrs | `result/<id>` |
| store-inventory | vector of kind rows | "inventory", kind list | `(db/store-inventory …)` |
| entity / kb map | map w/ identity + big strings | kind, top keys, big-string sizes, identity value | `db/pull` by identity attr |

---

## 3. The improved generic-default previewer (both views)

Replaces the body of `seon.render/generic-default-renderer`
(`render.cljs:541-557`). One shared `describe-shape` + `preview-sample`
backs both views; `:ai` emits text, `:html` emits a tree.

### `describe-shape` (pure, no dep) — the story header data

```clojure
(defn describe-shape
  "Pure structural summary of any value — the STORY. No dep.
   Returns a small map naming the type, dims, and surface."
  [v]
  (cond
    (map? v)        {:type :map  :count (count v)
                     :keys (vec (take 12 (keys v)))
                     ;; flag keys whose value is a sizeable collection/string
                     :nested (into {} (keep (fn [[k val]]
                                              (cond
                                                (and (coll? val) (counted? val))
                                                [k {:coll (count val)}]
                                                (and (string? val) (> (count val) 200))
                                                [k {:chars (count val)}]))
                                            v))}
    (and (coll? v) (counted? v) (every? vector? (take 5 v)))
    {:type :tuples :count (count v) :arity (count (first v))}
    (coll? v)       {:type (cond (vector? v) :vector (set? v) :set :else :seq)
                     :count (when (counted? v) (count v))}
    (string? v)     {:type :string :chars (count v)}
    :else           {:type (type->kw v)}))
```

### Cap policy — ONE parameterized policy, structural FIRST, char-cap backstop

Coherent with `context-render.md` "Clipping system (cross-cutting)" and the
task-#18 consolidation: `clip-value` already lives in `seon.eval` with
`result-row-cap 50` (`eval.cljs:1923`) and `result-body-render-cap 16384`
(`eval.cljs:1939`). The previewer reuses the SAME ladder:

1. **Structural first.** `describe-shape` (no truncation — it's a digest).
2. **Bounded sample.** `(binding [*print-level* 2 *print-length* 8]
   (pr-str (clip-value v)))` — depth-2, first-8-per-level, `#`/`...` markers.
   `clip-value` row-caps oversized inner collections before printing.
3. **Char-cap backstop.** Clip the final sample string to a small PREVIEW
   cap (proposed `preview-sample-cap`, ~600 chars — much tighter than the
   16384 result-body cap, because the SHAPE header carries the information;
   the sample only needs to be illustrative). Append the standard
   `;; … N chars clipped; full value at result/<id>` tail (`clip-result-body`,
   `eval.cljs:1948`).
4. **Drill-in hint** is always appended (never clipped away).

Parameters thread through one map (e.g. `{:seon.render/print-level 2
:seon.render/print-length 8 :seon.render/preview-cap 600}`), overridable per
renderable via the existing `:seon.render/clip` attr
(`context-render.md` registers it; `:none` opts out → full `pprint` like
today). Constants live beside the other caps in `eval.cljs` (the task-#18
single block), imported by `render.cljs` — no duplicate definitions.

### `:ai` view — eval'able-ish Clojure / clean comment

```clojure
;; <renderable-id>  —  map, 9 keys
;;   keys: [:seon.agent/id :seon.agent/state :seon.agent/sessions … +6]
;;   :seon.agent/sessions → vector(3)   :seon.agent/ctx → vector(12)
;;   sample (depth 2, first 8):
{:seon.agent/id "alpha", :seon.agent/state :running,
 :seon.agent/sessions [# # #], :seon.agent/ctx [# # # # # # # #], ...}
;;   drill: (seon.db/pull '[{:seon.agent/sessions [*]}] [:seon.agent/id "alpha"])
```

Properties: (1) names the type + dims on line 1; (2) lists the top keys /
the queryable surface; (3) flags nested collections + their counts so the
agent knows WHERE the bulk is; (4) a depth-bounded, length-bounded SAMPLE
that reads as Clojure (`#` = "pull deeper here"); (5) a concrete drill-in
template using the entity's identity attr. Every non-data line is a `;;`
comment so the whole block is eval-safe in the REPL-centric context.

### `:html` view — recursive, lazily-expandable data-tree

Borrows portal's model. The tree shows the `describe-shape` header as the
node label (`map · 9 keys`, `vector · 247`), with collapsed children that
**expand on click** (reuse the live-tile compact/expanded pattern,
`render/live_tile.cljs` + `valid-hiccup?` constraints from
`render/default.cljs:237-240` — string/int/nil/vector children only, build
with `into`, never bare lazy `(for …)`). Leaf scalars render inline; big
strings render a `Nk chars` chip that expands to the full string. The first
level (top keys / first-N rows) renders eagerly; deeper levels render the
shape header and expand on demand — so the human drills in exactly like
portal, without the dep.

### What changes in `render.cljs`

`generic-default-renderer` (`render.cljs:541-557`) stops doing
`(pprint/pprint (apply dissoc node render-control-attrs))` for both views.
Instead: strip control attrs, call `describe-shape` + bounded
`pr-str`/`clip-value`, assemble the story (ai) or the tree (html). The
control-attr strip and the id header stay. `resolve-slot` /`render`
(`render.cljs:571-631`) are unchanged — only the leaf renderer body changes.

---

## 4. Example-mined TEST SUITE plan

Goal: lock the previewer against REAL return shapes, mined from a live DB
AFTER the keystone lands (the implementation phase, not this research — this
pod is owned by another agent right now, so mining is deferred).

### Mining approach (run post-keystone, against the live store)

Sample one real value per catalog family and snapshot it to an EDN fixture
under `test/seon/render/fixtures/`:

1. **query result (small + large):** `(db/query {:seon.db/query '[:find ?e ?a ?v
   :where [?e ?a ?v]]})` (large, overflows row-cap) and a scalar aggregate
   `'[:find (count ?e) :where [?e :seon.fn/sym _]]` (1-tuple).
2. **pull map (shallow + deep):** `(db/pull '[*] [:seon.fn/sym "seon.db/query"])`
   and a deep `(db/pull '[{:seon.agent/sessions [{:seon.agent.session/turns
   [{:seon.agent.turn/evals [*]}]}]}] <agent>)`.
3. **datoms:** an index slice → seq of Datoms (projected via
   `project-agent-safe`).
4. **store-inventory:** `(db/store-inventory)` and `{:seon.db/system? true}`.
5. **entity / kb map with a big string:** a `:seon.fn` row whose
   `:seon.fn/source` is multi-kb; a `:seon.eval` row with a large
   `:seon.eval/result-edn`.
6. **scalar edge cases:** a 20k-char string; an opaque/record value
   (datahike DB) so we confirm graceful `opaque-summary` interplay.

Snapshot each value's `pr-str` to a fixture file (so the test does not
depend on the live pod), and record the provenance (the exact query) in a
comment beside each fixture.

### Test structure — PROPERTY assertions on the preview, not exact bytes

For each fixture, the `:ai` preview is asserted on STRUCTURAL PROPERTIES
(robust to wording tweaks, in line with the "don't pin exact output mid-
iteration" preference):

- **names the type** — contains "map" / "vector" / "tuples" / "datom" etc.
- **names the size/dims** — contains the real count (key count, row count,
  tuple arity).
- **lists the top keys / attrs** — for maps, the first ≤12 keys appear; for
  datoms, the distinct attrs appear.
- **is bounded** — `≤ K` lines AND `≤ preview-sample-cap` chars + the
  structural sample shows `#` at depth > level and `...` past length.
- **includes a drill-in hint** — contains a `db/pull` / `db/query` template
  OR `result/<id>` for the family.
- **big strings are summarized, not dumped** — for the big-`:seon.fn/source`
  fixture, assert the preview contains the char-count chip and does NOT
  contain the full source body.
- **never throws** — opaque/record fixture degrades to a summary, no
  exception (mirrors `render`'s catch at `render.cljs:628`).

For `:html`, assert the hiccup passes `live-tile/valid-hiccup?`, the root
label carries the shape header text, and deep nodes are collapsed
(present-but-not-expanded) rather than fully materialized.

Place the suite in `test/seon/render/structural_preview_test.cljs`; run via
`bin/test-cljs` at the unit checkpoint (per the test-cadence rule).

---

## Source references

- `src/seon/render.cljs:541-557` — current `generic-default-renderer` (the
  `pprint`-the-whole-node fallback to replace).
- `src/seon/render.cljs:571-631` — `resolve-slot` / `render` (unchanged
  walker; generic default is step 5).
- `src/seon/render/default.cljs:34-47` — `pretty-ai` / `pretty-html` floors.
- `src/seon/eval.cljs:1923-1972` — `result-row-cap`, `result-body-render-cap`,
  `clip-result-body` (the cap ladder to reuse).
- `src/seon/eval.cljs:2038-2172` — `project-agent-safe`, `opaque-summary`,
  `render-result-edn` (the row-cap preview already done at write time).
- `src/seon/db.cljs:465` (`query`), `:799` (`pull`), `:1071-1145`
  (`store-inventory`).
- `src/seon/agent.cljs:126-180` — entity `:map` schemas (`:seon.eval`,
  `:seon.fn`, `:seon.schema`, `:seon.ns`).
- `docs/prds/agent-fsm/context-render.md:480-525` — "Clipping system
  (cross-cutting)" + `:seon.render/clip` attr.
- `reference-code/clojurescript/src/main/cljs/cljs/core.cljs:154-175,
  10381-10382` — `*print-level*` / `*print-length*` defs + printer honoring.
- `reference-code/clojurescript/src/main/cljs/cljs/pprint.cljs:615,
  740-741, 782-783` — pprint honors the dynamic vars.
- `reference-code/clojurescript/src/main/cljs/clojure/datafy.cljs` — CLJS
  datafy/nav (no plain-coll impls).
- `deps.edn:122` — `djblue/portal 0.57.3` (JVM-only; model borrowed).

## Web citations

- fipp CLJS support + `:print-length`/`:print-level` (take-transducer,
  `"..."` append): <https://github.com/brandonbloom/fipp>,
  <https://github.com/brandonbloom/fipp/pull/30/files>,
  <https://cljdoc.org/d/fipp/fipp/0.6.23/doc/readme>
- `cljs.pprint` API: <https://cljs.github.io/api/cljs.pprint/>
