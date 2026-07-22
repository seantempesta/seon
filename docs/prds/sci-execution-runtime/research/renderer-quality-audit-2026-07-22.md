---
type: research
status: active
tags: [research, architecture, agent]
---

# Renderer-quality audit — sol read-only pass (2026-07-22)

Orchestrator-accepted; feeds the owner make-the-renderer-awesome
direction. HEADLINES: the recursive generic fallback raw-pprints
unbounded (render.cljs:899) while only eval blocks use the bounded
sampler — the one-walker promise is half-true; result/inline is a
fictitious continuation taught to agents; KB semantic recall is
structurally dead (embeddables=fn sources only ∩ recall scope=KB eids
= ∅). Coverage: 7 bespoke schemas, ~21 stored shapes generic incl.
run/turn/attempt/config; embed hits + kb recall generic. Rec ladder
R1..R8 queued (R1 = unify fallback + honest generic semantics).

# Read-only renderer and embedding audit

No files were edited.

## Evidence status

`bin/seon status` reported the default cluster ready. However, both read-only `eval_cljs` attempts were cancelled before execution, and direct loopback access was denied by the sandbox. Therefore:

- Live database-instance renders: **NOT GROUNDED**
- Live semantic-search visual: **NOT GROUNDED**
- Source traces: grounded
- Render transcripts below: produced by the current compiled renderer against the real resolved default configuration and constructed gnarly values. They are actual renderer output, but not live MCP/database output.

That limitation matters: I will not call representative fixture data “live.”

## Bottom line

The supposed generic renderer is currently two different systems:

| Entry | Generic AI behavior | Bounded? |
|---|---|---:|
| Recursive prompt walker, `seon.render/render` | Raw `pprint` of the whole node | **No** |
| Eval/value block, `seon.render/block` | `render.value` structural sampler | **Yes** |

The recursive fallback directly `pprint`s everything ([render.cljs:899](/Users/sean/src/seon/src/seon/render.cljs:899)). The bounded fallback routes through `render.value` ([render.cljs:789](/Users/sean/src/seon/src/seon/render.cljs:789), [render.cljs:811](/Users/sean/src/seon/src/seon/render.cljs:811)). Therefore improving only `render/value.cljc` does not improve generic agent, run, turn, config, or other entity nodes rendered by the recursive walker.

That is the highest-value finding.

# 1. Schema renderer coverage

Renderer metadata is copied from schema properties and selected by the general dispatch ([schema.cljc:302](/Users/sean/src/seon/src/seon/schema.cljc:302), [schema.cljc:456](/Users/sean/src/seon/src/seon/schema.cljc:456)). No renderer means generic fallback ([render.cljs:940](/Users/sean/src/seon/src/seon/render.cljs:940)).

Scope below is composite entity/value schemas worth rendering; scalar attribute schemas are intentionally omitted.

## Bespoke: 7 schemas, all with both twins

| Schema | AI renderer | HTML renderer |
|---|---|---|
| `:seon.eval` | `seon.render.handlers.eval/render-ai` | `…/render-html` ([agent.cljs:220](/Users/sean/src/seon/src/seon/agent.cljs:220)) |
| `:seon.fn` | `seon.render.handlers.fn/render-ai` | `…/render-html` ([agent.cljs:238](/Users/sean/src/seon/src/seon/agent.cljs:238)) |
| `:seon.schema` | `seon.render.handlers.schema/render-ai` | `…/render-html` ([agent.cljs:266](/Users/sean/src/seon/src/seon/agent.cljs:266)) |
| `:seon.ns` | `seon.render.handlers.ns/render-ai` | `…/render-html` ([agent.cljs:275](/Users/sean/src/seon/src/seon/agent.cljs:275)) |
| `:seon.test` | `seon.render.handlers.test/render-ai` | `…/render-html` ([runner.cljs:169](/Users/sean/src/seon/src/seon/test/runner.cljs:169)) |
| `:seon.agent.message` | `seon.render.handlers.message/render-ai` | `…/render-html` ([message.cljs:56](/Users/sean/src/seon/src/seon/agent/message.cljs:56)) |
| `:my.plan/plan-value` | `my.plan.internal/plan-ai` | `…/plan-html` ([plan.cljc:186](/Users/sean/src/seon/src/my/plan.cljc:186)) |

The first six are stored entity shapes. `:my.plan/plan-value` is an assembled projection; stored plan steps remain generic.

## Registered stored shapes falling to GENERIC

| Schema | AI / HTML | Traffic/value judgment |
|---|---|---|
| `:seon.agent` | GENERIC | Very high value, but deliberately excluded from the chronological AI window ([agent.cljs:285](/Users/sean/src/seon/src/seon/agent.cljs:285)). |
| `:seon.agent.run` | GENERIC | High-traffic lifecycle evidence ([run.cljs:77](/Users/sean/src/seon/src/seon/agent/run.cljs:77)). |
| `:seon.agent.turn` | GENERIC | High-traffic turn evidence ([turn.cljs:200](/Users/sean/src/seon/src/seon/agent/turn.cljs:200)). |
| `:seon.ai.attempt/entity` | GENERIC | High-volume provider evidence ([turn.cljs:166](/Users/sean/src/seon/src/seon/agent/turn.cljs:166)). |
| `:seon.config/singleton` | GENERIC | Wide 80-key operational configuration ([config.cljs:542](/Users/sean/src/seon/src/seon/config.cljs:542)). |
| `:my.plan/step` | GENERIC | Lower urgency because assembled plan-value is bespoke ([plan.cljc:325](/Users/sean/src/seon/src/my/plan.cljc:325)). |
| `:my.kb.shared/shared` | GENERIC | Shared instruction singleton ([shared.cljs:25](/Users/sean/src/seon/src/my/kb/shared.cljs:25)). |
| `:seon.ai/config` | GENERIC | Global model configuration ([ai.cljs:386](/Users/sean/src/seon/src/seon/ai.cljs:386)). |
| `:seon.ai/agent-config` | GENERIC | Per-agent model overrides ([ai.cljs:443](/Users/sean/src/seon/src/seon/ai.cljs:443)). |
| `:seon.agent.schedule` | GENERIC | Scheduling facts ([schedule.cljs:39](/Users/sean/src/seon/src/seon/agent/schedule.cljs:39)). |
| `:seon.typeahead/step` | GENERIC | Potentially noisy/high-volume telemetry ([typeahead.cljs:105](/Users/sean/src/seon/src/seon/ai/typeahead.cljs:105)). |
| `:seon.typeahead/policy` | GENERIC | Policy singleton ([menu.cljs:70](/Users/sean/src/seon/src/seon/agent/ctx/menu.cljs:70)). |
| `:seon.runtime.recovery` | GENERIC | Wide diagnostic payloads ([recovery.cljs:60](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:60)). |
| `:seon.route/route` | GENERIC | Route rows ([route.cljs:53](/Users/sean/src/seon/src/seon/route.cljs:53)). |
| `:seon.db.process/entity` | GENERIC | Stable process identities ([process.cljc:17](/Users/sean/src/seon/src/seon/db/process.cljc:17)). |
| `:seon.db.restore/current-completion` | GENERIC | Restore evidence ([schema.cljc:60](/Users/sean/src/seon/src/seon/db/restore/schema.cljc:60)). |
| `:seon.db.restore/legacy-completion` | GENERIC | Historical restore evidence ([schema.cljc:70](/Users/sean/src/seon/src/seon/db/restore/schema.cljc:70)). |
| `:seon.web.brand/brand` | GENERIC | Small singleton ([brand.cljs:47](/Users/sean/src/seon/src/seon/web/brand.cljs:47)). |
| `:seon.agent.ctx.namespaces/block` | GENERIC | Context-block specialization ([namespaces.cljs:50](/Users/sean/src/seon/src/seon/agent/ctx/namespaces.cljs:50)). |
| `:seon.user` | GENERIC | Small user singleton ([message.cljs:44](/Users/sean/src/seon/src/seon/agent/message.cljs:44)). |
| `:seon.ns.source/require-edge` | GENERIC | Also appears unable to enter the identity-gated entity catalog ([source.cljs:19](/Users/sean/src/seon/src/seon/ns/source.cljs:19), [schema.cljc:463](/Users/sean/src/seon/src/seon/schema.cljc:463)). |

## Important generic non-entity result shapes

| Shape | Result |
|---|---|
| `:seon.embed/hit`, `pull-hit`, `hits` | GENERIC ([embed.cljs:48](/Users/sean/src/seon/src/seon/embed.cljs:48), [embed.cljs:68](/Users/sean/src/seon/src/seon/embed.cljs:68)) |
| `:my.kb/recall-response` | GENERIC ([kb.cljc:77](/Users/sean/src/seon/src/my/kb.cljc:77)) |
| `:seon.packages/ledger-row`, `rows` | GENERIC ([packages.cljc:31](/Users/sean/src/seon/src/seon/packages.cljc:31), [packages.cljc:49](/Users/sean/src/seon/src/seon/packages.cljc:49)) |
| KB findings | No registered composite entity schema to claim a renderer; only attributes/results exist ([kb.cljc:28](/Users/sean/src/seon/src/my/kb.cljc:28), [kb.cljc:91](/Users/sean/src/seon/src/my/kb.cljc:91)) |
| “Handles-to-be” | No registered package-handle shape found |

# 2. Actual compiled render transcripts

## Resolved default configuration through bounded `block`

Current bounds are depth 3, eight keys/items, 80-character strings, and a 1,500-character verbatim cap ([system.edn:136](/Users/sean/src/seon/config/system.edn:136)).

```clojure
{:seon.config.database.executor.knn/maximum-queued-by-database 2
  :seon.config.database.executor.hnsw/maximum-active 1
  :seon.config.database.executor.mutation/maximum-active 4
  :seon.config.repair/max-fixes-per-form 1
  :seon.config.database.executor.provider/maximum-queued-by-database 2
  :seon.config.render/value-max-items 8
  :seon.config.database.executor.hnsw/maximum-queued 1
  :seon.config.breaker/crash-count 3
  … +72 more keys}
; ‹partial view of map 80 keys› — the COMPLETE value is result/inline · keep: (my.blob/put! result/inline)  (get-in result/inline […]) · filter · count · take/drop
```

Verdict: bounded, but poor projection.

- It selects small values from a bounded candidate window, not semantic groups or important fields ([value.cljc:711](/Users/sean/src/seon/src/seon/render/value.cljc:711)).
- `:seon.config/id`, core-error policy, provider/model, and major caps may disappear.
- Shape/count comes after the sample.
- `result/inline` is fictitious: `block` passes `"inline"` as the eval ID, so the renderer teaches unusable continuation and persistence commands ([render.cljs:811](/Users/sean/src/seon/src/seon/render.cljs:811), [value.cljc:1102](/Users/sean/src/seon/src/seon/render/value.cljc:1102)).

This deserves a schema-claimed configuration summary.

## Same configuration through recursive `render`

```clojure
;; 
{:seon.config.render/database-edn-cap 16384,
 :seon.config/reactive-settle-ms 16,
 :seon.config.database.transport/codec-worker-queue-capacity 256,
 :seon.agent.web/policy :public-only,
 :seon.config.render/tabs :literal,
 :seon.config.render/line-numbers false,
 :seon.config.repair/budget-ms 50,
 :seon.config.database.executor.delivery/maximum-queued 68,
 :seon.agent.web/search-backend :gemini-grounding,
 :seon.config.repair/level :symbols,
 ...
 :seon.config.render/value-max-realized-items 1024}
```

Actual output length: 4,033 characters for only 80 scalar keys.

Verdict: garbage as prompt data. It is unbounded, unsummarized, unordered by meaning, offers no continuation, and starts with an empty ID header. The header is hard-coded to five known identity attributes plus `:db/id` ([render.cljs:883](/Users/sean/src/seon/src/seon/render.cljs:883)).

## Long homogeneous vector through bounded `block`

```clojure
[{:audit/id 0
    :audit/status :running
    :audit/payload "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx…"⟨23 tokens⟩
    :audit/ref {:db/id 1000, :seon.agent/id "agent-0", }
    }
  {:audit/id 1
    :audit/status :done
    :audit/payload "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx…"⟨23 tokens⟩
    :audit/ref {:db/id 1001, :seon.agent/id "agent-1", }
    }
  ...
  … +22 more each {:audit/id :audit/payload :audit/ref :audit/status}]
; ‹partial view of vector 30 items› — the COMPLETE value is result/inline · keep: (my.blob/put! result/inline)  (get-in result/inline […]) · filter · count · take/drop
```

Verdict: mostly useful skeleton and honestly bounded, but:

- Again teaches a nonexistent `result/inline`.
- “each” is not honest. The implementation computes a union of sampled keys, not their intersection ([value.cljc:518](/Users/sean/src/seon/src/seon/render/value.cljc:518), [value.cljc:875](/Users/sean/src/seon/src/seon/render/value.cljc:875)).
- Shape/count is last, though it is the most useful first fact.

## Deep map below the verbatim cap

```clojure
{:audit/id "deep-1",
 :audit/root {:level-1 {:level-2 {:level-3 {:level-4 {:level-5
 {:answer 42,
  :text "long-long-long-long-long-long-long-long-long-..."}}}}}},
 :audit/items [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19]}
```

Verdict: “small” values are printed verbatim, even when structurally deep. The 1,500-character cap wins over shape-first presentation ([value.cljc:1061](/Users/sean/src/seon/src/seon/render/value.cljc:1061)). There is no top-level shape summary and no path guidance because the value was judged complete.

## Mixed refs through bounded `block`

```clojure
{:audit/id "mix-1",
 :audit/agent {:db/id 101, :seon.agent/id "root"},
 :audit/run {:db/id 202,
             :seon.agent.run/id "RUN00000000001",
             :seon.agent.run/status :running},
 :audit/rows
 [1 "two" :three
  {:db/id 303,
   :my.kb.finding/id "finding-1",
   :my.kb.finding/claim "claim claim claim claim ..."}
  #{:a :b :c}
  [9 8 7]]}
```

Verdict: readable only because it fits under the verbatim cap. It does not distinguish refs from nested domain data, summarize the referenced identities, or teach useful paths. On a larger pulled graph, it becomes the eight-item skeleton and can omit semantically central long fields.

# 3. Embedding path

## Current result route

`seon.embed/search`:

1. Acquires one immutable database value.
2. Resolves optional Datalog `:where` clauses to an eid scope.
3. Calls `db/knn-search!`.
4. Returns distance-ascending hits ([embed.cljs:133](/Users/sean/src/seon/src/seon/embed.cljs:133)):

```clojure
{:seon.embed/hits
 [{:seon.embed/eid 123
   :seon.embed/distance 0.087}
  ...]}
```

`search-pull` enriches those hits through one ordered `pull-many` at the same database value ([embed.cljs:171](/Users/sean/src/seon/src/seon/embed.cljs:171)):

```clojure
{:seon.embed/hits
 [{:seon.embed/eid 123
   :seon.embed/distance 0.087
   :seon.embed/entity {...}}
  ...]}
```

The writer embeds the query and executes KNN ([writer.clj:2225](/Users/sean/src/seon/src/seon/db/writer.clj:2225)).

The advertised agent-facing door is `my.kb/recall` ([kb.cljc:348](/Users/sean/src/seon/src/my/kb.cljc:348)). It does deterministic token matching first, then fills remaining slots from semantic hits, flattening hit entities into `:seon.items/items` with `:my.kb/match :semantic` and distance ([kb.cljc:397](/Users/sean/src/seon/src/my/kb.cljc:397)).

Neither embedding results nor recall results have bespoke renderers. Immediate eval output therefore uses the bounded general value renderer ([eval.cljs:2897](/Users/sean/src/seon/src/seon/eval.cljs:2897)); later transcript rendering collapses it to the eval handler’s summary.

## Serious functional gap

The shipped embedding corpus contains functions only: `default-embeddables` supplies only `:seon.fn/source` ([embed.clj:468](/Users/sean/src/seon/src/seon/embed.clj:468), [embed.clj:486](/Users/sean/src/seon/src/seon/embed.clj:486)).

But `my.kb/recall` scopes its KNN query to KB entity IDs ([kb.cljc:300](/Users/sean/src/seon/src/my/kb.cljc:300), [kb.cljc:397](/Users/sean/src/seon/src/my/kb.cljc:397)). With the source-defined pipeline:

```text
indexed function eids ∩ requested KB eids = empty
```

So KB semantic top-up is structurally ineffective unless an external extension exists outside the audited source. The code itself describes additional projections as future extension ([embed.clj:492](/Users/sean/src/seon/src/seon/embed.clj:492)).

`SEON_EMBED` is presence-gated and off by default ([embed.cljs:100](/Users/sean/src/seon/src/seon/embed.cljs:100), [.env.example:126](/Users/sean/src/seon/.env.example:126)). Because live MCP execution was unavailable, the search-result visual remains **NOT GROUNDED**.

# 4. Ranked recommendations

1. **Make the recursive generic fallback use `render.value`.**  
   This closes the biggest projection hole: generic entity nodes currently bypass every depth, breadth, string, and token safeguard and raw-`pprint` the entire value. Keep one guarded walker, but give its fallback the same bounded projection as `block`.

2. **Fix generic AI semantics before adding many special cases.**

   - Emit schema key, stable identity, top-level type, and count first.
   - Emit the structural sample second.
   - Emit continuation last.
   - Never emit `result/inline`; continuation must carry a real retained selector or say “partial; no live continuation.”
   - Change `each {...}` to either an actual intersection or “sampled columns.”
   - Stop ranking primarily by short printed size; explicitly preserve identity, status, summary/title, error, and source/provenance fields.

3. **Wire the desired domains into the one embedding pipeline.**  
   KB rendering is secondary while KB eids cannot produce semantic hits. Add selected KB and other owner-approved trigger attributes to `default-embeddables` before judging search quality.

4. **Add schema-claimed renderers for embedding/search results and KB recall.**  
   The AI view should show:

   - query and scope;
   - total hits and returned count;
   - rank, normalized distance, identity/title;
   - match mode;
   - one clipped claim/source excerpt;
   - an honest path/selector for the full entity.

   HTML should expose the same information as a sortable/drillable result list.

5. **Add lifecycle renderers: run → turn → attempt.**  
   These are high-traffic operational facts. Summarize status, agent, trigger, timing, progress/turn count, result/error, provider outcome, and important refs. Do not dump every transport field.

6. **Give configuration a schema-owned grouped projection.**  
   Group provider/model, render limits, execution queues, database limits, repair policy, and agent defaults. Show non-default overrides first. An arbitrary eight-key sample is actively misleading.

7. **Treat `:seon.agent` specially.**  
   Do not blindly add schema AI metadata: source deliberately keeps it out of the chronological window. Create an explicit bounded agent-summary projection for places that request it, showing identity, derived state, purpose, active run, context-block count, and parent—not raw `:seon.agent/ctx`.

8. **Then handle packages and future handles.**  
   Promote ledger rows/handles into named composite schemas with renderers that expose package identity, host, requested/resolved version, generation, integrity summary, and actionable status.

The north-star conclusion is blunt: bespoke renderers are sparse but reasonably idiomatic; the dangerous defect is that the recursive “universal” fallback is not the excellent bounded renderer the architecture suggests. It is raw `pprint`.