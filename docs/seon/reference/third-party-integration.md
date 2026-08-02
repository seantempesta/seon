---
type: reference
status: active
tags: [reference, agent, web, database]
---

# Seon integration guide

Fresh Seon is one JVM process that can host multiple sovereign clusters. Each
agent owns its own `core.async.flow` graph; the process also hosts the
cluster-specific database connections, web renderer, and shared root
executors. There is no ClojureScript pod, separate writer process, preload
door, or downstream source-injection environment variable.

## Run a cluster

```sh
bin/seon start
bin/seon status
bin/seon open default
bin/seon stop default
```

`start` defaults to the `default` cluster. A running JVM may host another named
cluster:

```sh
bin/seon start research --config config/research.edn
```

Use `bin/seon --root PATH ...` for a deployment or destructive proof that must
be isolated from the shared operator root. The selected root owns its process
records, advertisements, logs, and Datahike store.

## Supply configuration

`config/default.edn` is the complete shipped decision map. A supplied plain-EDN
manifest is a sparse overlay over those defaults:

```sh
bin/seon start research --config config/research.edn
bin/seon config apply research config/research.edn
```

Runtime code reads the reconciled `:seon.config/cluster` database entity, not
the manifest. An omitted overlay key inherits the shipped decision;
`:seon.config/absent` explicitly removes an optional dial. See
[[config-operations]] for the maintained configuration contract and
[[llm-adapters]] for the current AI dials.

## Publish and fork program source

`bin/seon init` publishes the current `src/`, `test/`, and schema resources to
the non-executing `current-src` branch. `bin/seon init --changed PATH...`
publishes safe same-identity edits incrementally and falls back to a complete
publication when necessary. A new cluster forks the published commit:

```sh
bin/seon init research
bin/seon start research
```

An existing cluster remains on its own program commit. Refork it only through
the explicitly destructive `bin/seon init research --force` path.

Downstream product source and domain models stay in their own repository. They
do not join Seon's classpath through `SEON_EXTRA_SRC`, `SEON_EXTRA_PRELOAD`, or
a ClojureScript build. Extend the program through published program-graph
facts and ordinary agent-authored functions; every agent may call every
function in its cluster's program graph.

## Web boundary

The current web UI is served in-process by `seon.render.web`. Database
transactions wake one Flow render proc; that proc derives complete page
snapshots from one database value, suppresses equal snapshots, and fans the
latest complete snapshot to per-tab `(sliding-buffer 1)` taps. Each tab sends
Datastar element morphs over one SSE connection. There is no
`seon.web.sse/refresh-all!` API or atom-watch refresh path.

Canonical namespace pages use `/ns/{namespace}`. `/` is the root namespace
page; `/agent/{id}` and `/agent/{id}/debug` resolve agent-owned pages. Route
truth lives in `seon.render.route` and `seon.render.web`, so integrations must
derive links through the route owner rather than copy a route list from this
page.

## Sources checked

- `bin/seon` and `script/seon/fresh_operator.clj` — lifecycle and publication
  commands.
- `src/seon/cluster.clj`, `src/seon/cluster/agent.clj`, and `src/seon/flow.clj`
  — process, cluster, and per-agent Flow topology.
- `src/seon/config.cljc` and `config/default.edn` — configuration ownership.
- `src/seon/render/route.clj` and `src/seon/render/web.clj` — routes and SSE
  delivery.
