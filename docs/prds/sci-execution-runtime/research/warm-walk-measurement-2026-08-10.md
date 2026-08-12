---
type: research
status: active
tags: [research, render]
---

# Warm AI walk measurement

## Verdict

The AI projection **fails the unchanged-basis acquisition gate**. Its retained
producer-output subgate passes: all 96 unit outputs reused and zero producers
ran. The complete walk still recomputed traversal and producer-selection
evidence, taking a median **122.0 ms** while issuing 146 `seon.db/q` calls, 797
`seon.db/pull` calls, and 11,675 `seon.db/datoms` calls.

The three headline uninstrumented medians over five consecutive samples were:

- cold, empty retention: **197.0 ms**;
- warm, identical immutable database value: **122.0 ms**; and
- one in-memory message transaction later: **195.1 ms**.

A direct bulk read of the same session-context fact families took four queries
and a median **46.0 ms** at the unchanged basis. The current warm walk is thus
about 2.7 times the measured read floor before any new transcript assembly is
designed.

## Scope and method

I read the complete current transcript PRD,
[`repl-transcript-context-prd-2026-08-10.md`](../plan/repl-transcript-context-prd-2026-08-10.md),
end to end. I also read the complete current owners
`src/seon/render.clj`, `src/seon/render/walk.clj`, and
`src/seon/cluster/prompt.clj`, plus the prompt acceptance test and the render
proc's context acquisition boundary.

The reproducible probe is `tmp/warm_walk_measurement.clj`. It makes no durable
write. It acquired the live `default` connection and SCI context, dereferenced
one immutable database value at basis transaction 536871101, and used
`datahike.api/db-with` to derive the changed value at basis transaction
536871102. The speculative transaction added one logical message event with
identity, recipient, content, and arrival facts; it was never transacted to the
live connection.

Two runs were made for each timing claim:

- uninstrumented runs establish wall time; and
- instrumented runs wrap `seon.db/q`, `pull`, `pull-many`, and `datoms`, their
  Datahike evidence-bearing leaves, `render-call`, and `render-ai` to establish
  reads and reuse. Instrumented wall time is not reported as the production
  wall time.

The selected dependency identities were verified from both the root gitlink
and checkout:

| Dependency | Selected revision | Boundary used |
|---|---|---|
| Datahike | `10540578248e` | Immutable database values, `db-with`, pull/query evidence, and AVET/EAVT reads. |
| SCI | `6ee57c9c3e73` | The live acquired producer context used by `render-call`. |
| Malli | `80138076960e` | Contract-derived producer selection through the acquired schema projection. |

The `datahike` skill still names `56f1c621...` as current. That statement is
stale; measurement and this report use the verified selected revision above.

## Walk results

Wall times are medians of five consecutive uninstrumented runs. Ranges show
the minimum and maximum. Read counts and unit reuse are from a representative
instrumented run at the same immutable inputs; counts were stable across the
five timing samples.

| Case | Wall time, median (range) | Units | Reused | Recomputed | Producer invocations | `q` | `pull` | `pull-many` | `datoms` calls / returned datoms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Cold, no retained calls | 197.0 ms (194.3–214.5) | 96 | 0 | 96 | 139 | 288 | 1,266 | 3 | 11,675 / 1,660 |
| Warm, same database value | 122.0 ms (121.0–122.9) | 96 | 96 | 0 | 0 | 146 | 797 | 0 | 11,675 / 1,660 |
| One message change | 195.1 ms (189.6–202.2) | 97 | 95 | 2 | 46 | 168 | 1,099 | 3 | 11,797 / 1,668 |

The cold producer count exceeds the unit count because a top-level unit may
invoke nested producers. After the message change, only the root-agent unit
and the new message unit recomputed. The root renderer accounts for most of
the 46 producer invocations because it delegates into its nested transcript
values. The other 95 retained calls reused.

The changed database value is speculative and therefore has no committed
revision identity. `read-evidence-current?` honestly replayed retained reads
whose revisions could not establish currency. Consequently the changed run's
physical Datahike evidence calls were slightly higher than the public calls:
198 query-evidence calls and 1,179 pull-evidence calls versus 168 public
queries and 1,099 public pulls. The committed same-basis run required no such
replay.

### Per-unit read pattern

At the unchanged basis, retained units still paid this producer-selection
pattern:

| Per-unit public reads before output reuse | Units |
|---|---:|
| 1 query + 1 pull | 54 |
| 2 queries + 2 pulls | 42 |

Those 138 queries and 138 pulls occur inside `render-call`. Traversal outside
the calls contributed the remaining 8 queries, 659 pulls, and all 11,675
`datoms` calls. Thus zero producer invocation is not zero walk work.

The one-versus-two split follows the source. `render-call` selects a producer
and constructs static evidence *before* checking the retained entry
(`src/seon/render.clj:411-425`). Static evidence constructs a render argument,
and a contract-candidate selection constructs another. Each absent explicit
profile calls `request-profile`, which queries the agent's cluster and pulls
effective configuration (`src/seon/render.clj:57-74,76-108`). Explicit or
schema-selected producers avoid one of those two argument constructions;
contract-selected units pay both.

## Why the traversal reads this way

The cost is the direct result of a generic recursive entity-neighbourhood
design, not an inherent cost of assembling one agent's context.

1. Every node derives last change with an EAVT scan
   (`src/seon/render/walk.clj:219-225`).
2. A child target is fully pulled once merely to turn its entity id into a
   lookup (`src/seon/render/walk.clj:253-258,340-351`). The node then fully
   pulls it again (`src/seon/render/walk.clj:394-398`).
3. Ownership pulls `:seon.ns/name` once for every direct forward ref
   (`src/seon/render/walk.clj:249-251,260-277`).
4. `refs` fully pulls the same entity yet again
   (`src/seon/render/walk.clj:182-206`).
5. Reverse-ref discovery loops over **every installed ref attribute** and
   performs one exact AVET slice for each node and attribute
   (`src/seon/render/walk.clj:81-137`). Empty slices are cheap individually but
   dominate the call count when multiplied by the walked nodes.
6. Only after change derivation, entity/ownership pulls, and renderer
   selection does `render-call` test retained read evidence. Connection
   discovery then runs regardless of reuse (`src/seon/render/walk.clj:366-442`).

The walk rendered 96 logical units: one agent lookup, nine namespace lookups,
and 86 raw entity-id lookups. The nine namespaces were `my.background`,
`my.edit`, `my.fs`, `my.message`, `my.run`, `my.shell`, `my.web`,
`my.agents.root`, and `clojure.string`.

The existing namespace-page baseline (3,859 pulls and 21,560 `datoms` reads)
and this AI measurement therefore share the same per-node design pattern, but
not the same graph size. The root AI walk is smaller: 797 pulls and 11,675
per-attribute `datoms` calls even when every producer output reuses.

## Minimal read plan

The null hypothesis holds. The semantic inputs to root's session context are
available in four bounded family reads over one immutable database value:

1. agent, cluster, instruction, root namespace, and cluster toolkit namespace
   rows;
2. messages addressed to the agent;
3. the agent's runs, with component forms and reverse receipt results in one
   nested pull query; and
4. public API rows for the nine selected namespaces in one collection-input
   query.

The probe uses these actual query shapes; selectors are ordinary input data and
are shown in full in `tmp/warm_walk_measurement.clj`:

```clojure
[:find (pull ?agent selector) .
 :in $ ?agent-id selector
 :where [?agent :seon.cluster.agent/id ?agent-id]]

[:find [(pull ?message selector) ...]
 :in $ ?agent-id selector
 :where
 [?agent :seon.cluster.agent/id ?agent-id]
 [?message :seon.cluster.message/to ?agent]]

[:find [(pull ?run selector) ...]
 :in $ ?agent-id selector
 :where
 [?agent :seon.cluster.agent/id ?agent-id]
 [?run :seon.cluster.run/agent ?agent]]

[:find [(pull ?function selector) ...]
 :in $ [?namespace ...] selector
 :where
 [?function :seon.fn/ns ?namespace]
 [?function :seon.fn/private? false]]
```

The run selector pulls `:seon.cluster.run/forms` and
`:seon.cluster.eval/_run` together, preserving the run-plus-ordinal join
without a per-form read.

| Case | Queries | Wall time, median (range) | Rows/facts acquired |
|---|---:|---:|---|
| First bulk plan | 4 | 46.8 ms (45.9–48.6) | 1 agent, 1 instruction, 9 namespaces, 8 messages, 9 runs, 35 forms, 35 results, 24 public functions |
| Same database value | 4 | 46.0 ms (45.3–47.9) | Same facts |
| One message change | 4 | 49.2 ms (48.5–51.2) | Same, plus the ninth message |

This is a **read floor**, not a completed renderer: it acquires every fact
family named by the transcript design but does not project or fit values. It
also intentionally does not reproduce the legacy neighbourhood's separate
maintenance-request, schedule, error, and provenance neighbour units. Those
are generic-walk additions, whereas their session-relevant message/form/result
facts are already in the four families above.

The exact design input is therefore: **this root session is answerable from
four family queries in 46.0 ms at an unchanged basis; the current unchanged
AI walk spends 146 queries, 797 pulls, 11,675 per-attribute datom scans, and
122.0 ms.** The gap is caused by recursive per-node entity pulling,
per-installed-ref-attribute reverse scans, and a retained-output check placed
after traversal and producer-selection work.

## Prompt acquisition: what “one retained walk” means

`prompt` does not itself retain a completed walk. It requests context through
`render/acquire-context!` (`src/seon/cluster/prompt.clj:155-164`). The render
proc's `context-pass` runs `render/walk` on every request, supplies the prior
per-agent `::ai-calls` map as `:seon.render/retained-calls`, captures the new
per-call map, and replaces the retained map after the walk
(`src/seon/render/web.clj:786-800`).

One direct pair through that exact live context channel measured:

| Acquisition | Wall time | Render calls | Producer invocations | Bytes |
|---|---:|---:|---:|---:|
| First | 295.0 ms | 96 | 139 | 98,617 characters |
| Second, same database value | 141.0 ms | 96 | 0 | 98,617 characters |

The text was byte-equal. “Retained” therefore means **retained outputs for 96
identified render calls**, not a retained walk tree, retained traversal, or
retained assembled prompt. Every acquisition re-enters all 96 `render-call`s
and rebuilds the walk text. If the budget rejects distance 2, `prompt` asks for
another complete acquisition at a smaller distance
(`src/seon/cluster/prompt.clj:155-200`).

## Live-default boundary encountered

During measurement, another in-flight change made the live default branch's
effective config incomplete: `config/effective` reported that cluster
`"default"` lacked required fact
`:seon.config.ai/chars-per-token-prior`. MCP return-value projection then
failed at `seon.sci.admit/admit*` because result caps were absent. Ordinary
prepl stdout remained truthful, so the read-only walk and context-channel
measurements completed and their maps were captured there.

I did not apply config, restart/refork the cluster, edit another lane's files,
or run a durable prompt turn. Consequently the prompt evidence above proves
the exact acquisition/retention mechanism, while a full
`seon.cluster.prompt/prompt` call through validation, calibration, and budget
is blocked at the foreign incomplete-config boundary.

## Gate assessment

| Gate | Result | Evidence |
|---|---|---|
| Same-basis producer invocations are zero | **Pass** | 0 invocations; 96/96 retained outputs reused. |
| Same-basis walk performs zero queries/pulls | **Fail** | 146 queries, 797 pulls, 11,675 `datoms` calls. |
| Same-basis acquisition is in the established HTML warm class | **Fail** | AI median 122.0 ms versus the cited HTML ~17 ms. |
| One change rerenders only affected units | **Pass for outputs** | 2 recomputed, 95 reused, 1 new unit; traversal still reran globally. |
| Context facts require thousands of reads | **Refuted** | Four family queries acquire the session inputs in 46.0 ms warm. |

The binary result requested for the AI projection is therefore **FAIL**. The
per-unit output cache works, but it is inside the expensive boundary. Passing
requires an unchanged-basis/read-evidence check before session acquisition and
a changed-basis family read plan that does not reconstruct context through a
generic per-node pull cascade.
