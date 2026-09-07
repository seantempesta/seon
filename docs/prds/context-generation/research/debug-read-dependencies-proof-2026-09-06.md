---
type: research
status: active
tags: [research, render, database]
---

# Inspect retained database reads — 2026-09-06

The renderer's existing retained call carries `:seon.render.call/read-evidence`.
`seon.db/read-evidence` supplies ordinary read requests, dependency plans and
revisions; stable read results are optional process-local evidence. None of
these require a new query, evaluator, registry or database fact to display.

Dependency grounding: `src/seon/db.clj` owns `append-pull-evidence!` and
`read-evidence`; `resources/seon/schemas/seon.db.edn` declares their exact
shapes. Datahike's pull planner supplies the selector and dependency plan via
`reference-code/datahike/src/datahike/pull_api.cljc`. The existing
`seon.render/render-call` records that evidence and refreshes dependency
revisions when a cached result remains valid.

The candidate disclosure uses those retained values. It counts evidence entries,
not queries: one query can have several database sources. A present empty vector
and absent evidence have distinct messages. Read results appear only when they
were actually retained; the page never executes a query to fill in missing data.
Requests are displayed as data, not falsely labelled as executed source strings.

## Live probe before UI integration

MCP captured `seon.db/pull` of `[:my.plan.item/title]` at entity 32011 on
`lab-browser-0906`. The retained request was
`{:seon.db/read-operation :pull
  :seon.db/pull-arguments [[:my.plan.item/title] 32011]}`.
Its revision identified the connection, generation and title attribute revision.
No read result was retained, even with the process-local retention option;
therefore the UI must distinguish that absence from a nil query result.

The browser regression is `debug_read_dependencies_probe_2026_09_06.cjs`.
It checks a plan-item renderer with no retained reads and a namespace renderer
with an actual function query and dependency revisions. Final browser and focused
test results remain to be recorded after the implementation is loaded.
