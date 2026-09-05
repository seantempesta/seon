---
name: datastar-web-ui
description: "Work on Seon's Datastar web renderer, route table, namespace pages, debug pages, SSE feeds, message submission, block morphs, backpressure, layout, or render cost. Load before changing seon.render.web, seon.render.route, or another seon.render.* web owner, and before proposing a broader canvas or control surface."
---

# Datastar web UI

Work on the cluster JVM renderer in `src/seon/render/web.clj`; do not restore
the deleted CLJS pod (`AGENTS.md:20-31`). Discover the selected cluster's bound
URL with the one operator:

```text
bin/seon status
bin/seon open NAME
```

The operator commands are defined at `AGENTS.md:1071-1091`. Boot writes the
server's actual URL and port into the cluster advertisement
(`src/seon/cluster.clj:1364-1386`). The server derives a preferred port from
the cluster name, falls back to an ephemeral port on collision, and returns the
bound result (`src/seon/render/web.clj:1271-1330`). Do not hard-code a port.

## Ground the route owner

The selected HTTP dependency is `metosin/reitit-ring` 0.10.1
(`deps.edn:56-67`). `seon.render.route/routes` is the one live route table and
`seon.render.web/handler` binds every symbolic handler into one Reitit Ring
handler (`src/seon/render/route.clj:5-34`,
`src/seon/render/web.clj:1176-1220`). Route facts are Clojure data in that Var,
not database facts.

| method/path | live handler behavior |
|---|---|
| `GET /` | alias to the configured root agent's namespace page |
| `GET /ns/{namespace}` | canonical namespace page |
| `GET /ns/{namespace}/debug` | canonical namespace debug surface |
| `GET /agent/{id}` | alias to that agent's namespace page |
| `GET /agent/{id}/debug` | alias to that agent's debug surface |
| `POST /agent/{id}/message` | same-origin inbound-message commit |
| `GET /feed/{id}` | that agent's Datastar SSE feed; debug surfaces use `?debug=true` |
| `GET /data` | schema/entity `get-in` surface |
| `GET /css/{*path}`, `GET /js/{*path}` | packaged public resources |

Verify the exact methods and paths at `src/seon/render/route.clj:5-27`, the
namespace/root/agent alias behavior at `src/seon/render/web.clj:931-1102`, and
the message/feed/data/static bindings at `src/seon/render/web.clj:1104-1220`.
Do not invent an agent-creation route, stop/resume route, `/call`, or a
generalized action endpoint: none appears in the one route table
(`src/seon/render/route.clj:5-27`).

## Keep the namespace page on the one walk

Resolve a canonical namespace route through its namespace owner. An absent
owner is ensured through the existing agent-creation transaction; an unknown
or malformed namespace returns 404 (`src/seon/render/web.clj:931-983,1074-1087`).
The `/agent/{id}` forms are aliases and return 404 when the agent has no
assigned namespace (`src/seon/render/web.clj:1089-1102`).

Keep AI context and namespace-page HTML on the same visible walk. HTML `page-of` calls
`seon.render.walk/neighborhood` and `units`; the AI boundary calls
`seon.render/walk`, which calls the same neighborhood and assembles the AI projection
(`src/seon/render/web.clj:300-350,988-1009`,
`src/seon/render.clj:147-226`, `src/seon/render/walk.clj:693-876`). The debug
surface derives both projections from the same database value and shows AI on the
left and every walked HTML unit on the right
(`src/seon/render/web.clj:428-441,1041-1072`). Do not add a parallel web
renderer or a debug-only traversal.

AI and HTML remain distinct projections: AI returns text and HTML returns
Hiccup. Recursive render-function selection applies at every admitted value depth,
and selected render-function output is terminal (`src/seon/render.clj:300-334,344-369`).
Generic preparation enriches elisions and calls the single `seon.print/fit`
owner (`src/seon/render/value.clj:220-269`;
`src/seon/print.cljc:669-675,750-785`). The current agent profile derives from
config facts (`src/seon/render.clj:37-57`; `config/default.edn:60-70`).
The MCP projection already applies its own explicit `:seon.render.profile/mcp`
fit profile (`src/seon/cluster.clj:377-389`; `test/seon/cluster/mcp_test.clj:156-171`).
Operator, runner, and log profiles remain **[TARGET]**; do not add local caps
for those consumers while their output-floor conversions are pending.

## Preserve the live delivery path

The cluster graph owns one `:io` render proc. It derives revisioned packages
for watched agents, suppresses unchanged pages, and publishes through a `mult`
(`src/seon/render/web.clj:989-1072`). The cluster's one Datahike listener
offers at most one payload-free render wake per transaction report when the
report intersects the derived render interest
(`src/seon/cluster/wake.clj:163-228`).

For each tab, preserve this sequence:

1. Register interest, tap the `mult` with `(sliding-buffer 1)`, and paint the
   current keyframe from the current database value.
2. Consume the newest complete revisioned package.
3. Select its delta when the delivered revision is contiguous; otherwise use
   its complete keyframe.
4. Send the selected Datastar patch event.
5. Park the connection-owned virtual thread on http-kit's drain-or-close
   completion before the next event.

The implementation is `src/seon/render/web.clj:1390-1573`. Complete packages
make sliding-1 loss safe: a displaced package is superseded by the newer
package, whose keyframe repairs any revision gap. The maintained http-kit fork exposes pending bytes and the
drain-or-close completion at
`reference-code/http-kit/src/org/httpkit/server.clj:321-326`; do not infer
drain from `send!` alone.

## Keep human input stable

Keep the fixed message form and hidden feed opener stable. Transient text,
request progress, and refusal prose live in Datastar signals; a successful POST
commits the admitted message and returns 204 without painting
(`src/seon/render/web.clj:132-218,883-913`). The commit wakes the ordinary
render path. Use Datastar's colon-form attributes such as `data-on:submit`; do
not add an action-specific refresh channel.

## Verify the namespace page and feed

Use a browser for layout, stable IDs, form behavior, and console errors. If a
browser bridge does not hold the SSE connection, verify `/feed/{id}` with a
server-side HTTP client and inspect the selected cluster log through the
operator commands in `AGENTS.md:1071-1091`.

## Separate current behavior from target work

These mechanisms are current:

- canonical namespace pages plus root and agent aliases
  (`src/seon/render/route.clj:5-16`);
- namespace and agent debug variants over the AI/HTML walk
  (`src/seon/render/web.clj:1041-1102`);
- one cluster render proc publishing revisioned packages carrying changed-block
  deltas and complete keyframes,
  with feed-side contiguous-revision selection
  (`src/seon/render/web.clj:708-755,989-1072,1431-1573`); and
- the fixed message form, `/data`, feed, and static-resource handlers
  (`src/seon/render/web.clj:1104-1220`).

Keep these distinct and explicitly **[TARGET]**:

- agent-owned `::renders`: the live agent blueprint still contains only
  mailbox and turn (`src/seon/cluster/agent.clj:240-264`);
- a generalized `my.canvas`/control API and guarded `/call` action route: the
  current fixed controls and the complete route table provide neither
  (`src/seon/render/web.clj:132-169,1027-1037`,
  `src/seon/render/route.clj:5-27`);
- database-derived route trees: the live table is the `route/routes` Var
  (`src/seon/render/route.clj:5-34`).

Do not bolt a target mechanism beside the live owner. Convert the existing
owner in place only after its target contract is settled.

## Design and measurement

Preserve stable block IDs, semantic hiccup, server-rendered content, and the
maintained Phosphor tokens. `seon.render.block/surface-id` owns stable DOM IDs
(`src/seon/render/block.clj:72-107`); `resources/public/css/input.css:54-103`
owns the palette and typography tokens. Read
`references/design-principles.md` before visual changes.

For protocol or performance work, also load
`seon-flow-architecture/references/render-delivery.md`. It records the live
package/delta/keyframe delivery path and the measured delivery probes.
