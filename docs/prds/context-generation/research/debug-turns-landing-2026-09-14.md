---
type: research
status: active
tags: [research, render, web, agent]
---

# Debug turns — 2026-09-14

## Grounding and dependency ledger

Read end to end: `AGENTS.md`, the live-run-2 landing, the requested turn PRD
§§14–15 and §§18–18d (also §16), `src/seon/render/web.clj`,
`src/seon/render/transcript.clj`, `src/seon/cluster/prompt.clj`, and the Juniper
fixture script. Read the roadmap entry and working edge, UI architecture,
and the owning acquisition, turn-opening, settlement, and evaluation queries.

- Datahike temporal database values: `reference-code/datahike/src/datahike/db.cljc`,
  through `src/seon/turn.clj` `opening-db`. The loop supplies this opening
  database to prompt acquisition; a turn id alone does not select history.
- Ordered shown-text history: `src/seon/eval.clj` `of-agent`,
  `src/seon/render/walk.clj` `history`, `src/seon/render/web.clj`
  `derive-context!`, and `src/seon/cluster/prompt.clj` `prompt`.
  Use the returned segments, including separators, for byte contributions.
- Reitit: `reference-code/reitit/modules/reitit-core/src/reitit/core.cljc`,
  through `src/seon/render/route.clj` `path`; reuse the debug route query.
- Datastar's shipped `resources/public/js/datastar.js` handles HTML responses
  as element patches and preserves `data-ignore-morph` subtrees. Existing
  `src/seon/render/web.clj` uses stable block ids and GET actions.
- Reuse `runtime-turn-table`, local time, duration, and the message pair in
  `src/seon/render/transcript.clj`; evaluations use `seon.repl/render-html`.

Chronological turns keep the opening first and expose additive growth.
The comparison remains always present, including on `?prompt=true`, per §18d.

## Inherited verification boundary

Default is PID 23557, HTTP 7994. MCP runtime health timed out; JVM queries
answered. Recorded in
[the existing issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
Unrelated untracked `build/`, `workers/`, and `config/virtual-turns.edn`
were preserved. No default restart, refork, reseed, or agent message.

## In-flight outage and repair

The owner observed HTTP 500 in approximately 17 seconds during this lane's
in-flight adoption: `render-debug-turns` failed its Hiccup output contract.
The nested `surface-id` calls incorrectly supplied vectors; its contract
requires keywords. Corrected both calls to qualified keywords, hot-reloaded
`seon.render.transcript`, and re-armed the current projection through MCP.
The next curl returned HTTP 200, 643091 bytes, in 10.629483 seconds.
This restored availability but did not meet the latency requirement.
The turn query was then batched and identical trigger messages rendered once
per page derivation. Final measurements follow below.
