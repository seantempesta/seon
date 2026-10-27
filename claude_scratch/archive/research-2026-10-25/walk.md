# The walk: what it is, whether it has merit (read-only findings, main @ 8517a97d6)

## 1. What it is today
`seon.render/walk` (src/seon/render.clj:281-319) + `merged` (:321-350) + `page` (:352-371) + `data` (:376-390) + `shown`/`rendered` (:393-414).
Algorithm, `walk db eid [seen]`:
- Pull `[*]` of the entity (:296). Entity schema = the Malli schema that names one of its identity attrs (`entity-schema` :208-214; map `entity-schemas` :201-203 derived from registry forms with property `:seon.db/attributes`).
- Neighbours = the schema property `:seon.render/units` (render.edn:37): a vector of pull attrs, leading `_` = reverse ref. Exactly ONE hop, no depth parameter. Ref-valued units are pulled `{a [*]}` (second pull :299); non-ref units become `:value` nodes.
- Each node is filtered by schema property `:seon.render/keys` (`relevant` :273-279): only declared attrs kept (the agent/run/root facts, facts.edn:137,246,318).
- Citation pass (`cite` :241-271): attrs whose schema has `:seon.render/prose true` (facts.edn:128,150,151,210,350,359,390) are parsed for var/section/file references (seon.ref/references, seon.docs/cited); each distinct ref pulled once, numbered, `cited-at` recorded.
- Output is DATA (not hiccup): `{:root node :nodes [node] :refs [ref]}`; node = `{:kind :value :schema :eid :from {:eid :attr} :cites :t}` (schema render.edn:29-87).
- `merged` (:321) walks main then the agent's branch db and unions nodes (main wins; orders by unit order then tx `t`). `page` (:352) additionally walks each `:seon.render/parts` unit (agent -> `:seon.run/_agent`) and joins its nodes.
- Rendering is separate: `shown` (:393) renders a node by its schema's declared render on both faces (:ai text, :html hiccup); `rendered` maps `shown` over a walk.
Callers (grep src/): only `render/page` <- render/proc.clj:237 (page-value) <- routes `/`, `/agent/{id}`, `/walk/{eid}` (web.clj:134-140) <- ui.clj `page` (:565). `render/data` and `render/rendered`: NO src caller on main (only render_test.clj:468,687). `shown` is used by ui.clj rows. The model's context on main does not use the walk at all (no `walk` in context.clj/agent.clj). Unmerged jolt/evaluations adds `(my.agent/walk)` as turn-0 in the agent's context via `seon.context/walk-value` (data-shaped, uses render/relevant); that is the "one walk, both media" claim, not yet true on main.

## 2. History / size
- render.clj log: walk born 2026-10-23 (aeb4b5dd1 "The walk", 1764fec47 one-map, 43ccbc3fa/538f4259a self-review fixes, 7283c63a0 "data is better than text", f18321f87..5666aebb3 page). All within ~1 day, 3 merges (jolt/agent-view, a-page-is-an-entity, page).
- Walk portion of render.clj is lines 199-414 = 216 lines (210 non-comment, docstring-heavy); render.clj total 414. Plus schema block render.edn:29-87 (~55 lines), proc.clj page-value, ui.clj page (~30 lines), tests render_test.clj 351-481 (~130 lines).
- Branches: jolt/evaluations (unmerged, 43 files) adds context/walk-value, framed turn 0; jolt/agent-view-prototype (unmerged) makes the agent page a debug view; neither touches walk() itself (prototype diff has 0 "walk" hits).

## 3. Merits and faults
Matches the owner's target:
- Schema-declared, data-driven: units/keys/parts/prose are four Malli properties on the entity schema (facts.edn:137,177,246,318); adding a view of a new entity = editing a schema, no code.
- Output is data (render.edn:87), with `:from` provenance; renderers (`shown`) are separate and schema-declared, so one walk can feed :ai and :html faces.
- Reverse refs and `relevant` filtering give the agent a small, pulled-not-printed view (DECISIONS: "context is data").
Overbuilt / wrong:
- The audit's "/walk duplicates /agent" is half right: it is NOT a second producer. `/walk/{eid}` (web.clj:140) is the same `page` function addressed by eid; it is needed for an entity with no live agent id (proc.clj:234 says earlier-boot agents are reachable only that way). The duplicate is one regex line, not a mechanism. Real fix: none needed, or fold `/agent/{id}` into "location -> eid".
- Citation machinery (`texts`, `address`, `cite`, `prose?` render.clj:216-271, ~55 lines, seon.ref + seon.docs/cited dependencies) is the biggest chunk and is not "walk the graph": it is a second kind of edge (text -> var/section). Valuable for the task plan page, but it is a separate feature coupled in.
- `merged` (30 lines) and `page`'s parts logic (20 lines): cross-db union and a second-level walk bolted on because main and branch hold different halves of an agent, and because an agent's run is a part. That is the real depth>1 need, expressed as a special property rather than depth.
- `data`, `rendered`: unused in src on main (render.clj:376, 405); `shown` records `ai-by/html-by/ai-text/truncated?` for every part though nothing reads them (audit item 1).
- Two pulls hand-rolled (`ref?` via d/schema :298) instead of one pull pattern built from the units.

## 4. Clojure words
- datafy/nav (clojure.datafy, REBL): datafy = turn a thing into data; nav = given coll, key, value return the thing the value refers to. `walk` is datafy(entity) with the neighbours pre-navigated one hop. Datahike's `d/entity` already is a lazy nav surface (attr access, `:_reverse`), and `d/pull` with `[{:a [*]} {:_b [*]}]` is exactly "walk one hop declared by a pattern". Our `:seon.render/units` IS a pull pattern stored as a schema property; `relevant` IS `select-keys` driven by `:seon.render/keys`; `:from` is nav's (coll k v) provenance. So yes: walk = pull pattern from schema + select-keys from schema + provenance tags. Malli schema properties are the right place (they are Malli's own extension point, m/properties). Not literally datafy/nav (we do not implement the protocols; Jolt lacks proven support for them, unverified), but the vocabulary maps one to one: datafy -> `(d/pull db pattern eid)`, nav -> `(walk db eid')` on any `:db/id` in the result.

## 5. Simple version (~35-60 lines)
```clojure
(defn pattern "pull pattern from the schema's :seon.render/units, rooted at the entity's schema"
  [schema] (mapv (fn [a] (if (ref-attr? a) {a ['*]} a)) (:seon.render/units (contract/properties schema))))
(defn walk "Entity eid on db: its schema-relevant attributes, its declared neighbours one hop, each part {:eid :schema :from :value}."
  [db eid]
  (let [own (d/pull db '[*] eid) s (entity-schema own nil)
        nb  (d/pull db (pattern s) eid)]
    {:root {:eid eid :schema s :value (relevant s own)}
     :nodes (for [[a v] nb, m (if (map? v) [v] v)
                  :let [ns (entity-schema m (forward a))]]
              {:eid (:db/id m) :schema ns :from {:eid eid :attr a} :value (relevant ns m)})}))
```
and nav is `walk` called again on a node's `:eid` (this is what the page's links already do via /walk/{eid}). Renderers stay `shown` + ui.clj layout (schema-declared renders); agent's `(my.agent/walk)` is `walk` + `data`.
Keep: `entity-schemas`, `entity-schema`, `forward`, `relevant`, the four schema properties (units, keys, prose, parts), the node schema, `page` (route/proc use it), `shown`, ui layout, routes.
Drop/move: `data`/`rendered` unless jolt/evaluations merges (it uses data); the `shown` provenance fields (ai-by/html-by) nobody reads; `cite` etc. should be a separate node-annotation step applied after walk (so walk is the simple part) rather than inside it; `merged`'s `t` ordering and `page` parts could be replaced by letting `:parts` just mean "walk these neighbours too" (depth 2 for flagged units) in one recursive function; the `/walk/{eid}` regex route stays (it is the location-by-eid; only rename in docs).

## Verdict: HYBRID (keep the walk, simplify)
The idea is right and already matches the target: schema properties declare edges and relevance, output is data, renders are separate. It is not garbage and not a duplicate producer. What is overbuilt: citation parsing fused into walk, cross-db merge + parts special case, unused `data`/`rendered`/render-provenance fields, and the fact that on main the AGENT does not use it (only the human page), so "one walk for both media" is currently false until jolt/evaluations' turn-0 walk lands. Est. savings if cleaned: ~-60 to -90 lines in render.clj (rendered/shown provenance ~-25, data if unused ~-15, cite split out neutral, merged/page tidy), no behaviour change to /agent and /walk.
