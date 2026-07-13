---
name: seon-context-config
description: "Customize the startup manifest that seeds Seon's database-backed context, namespace policy, skill corpus, routes, and render caps. Use when editing config/system.edn or another SEON_CONFIG manifest, changing agent context blocks, or diagnosing why a block or namespace is present."
---

# Seon context and config

The startup manifest is desired-state input. At pod boot, Seon validates it,
resolves it, and reconciles the corresponding facts into the database. Runtime
code reads those database facts after the connection is attached.

Read these sources before changing configuration:

- src/seon/config.cljs
- config/system.edn
- src/seon/agent/ctx.cljs
- src/seon/agent/ctx/namespaces.cljs

## Select one manifest

config/system.edn is the default. SEON_CONFIG selects a different complete EDN
file. There is no active profile layer in the pod configuration path.

Every manifest key is optional. An absent file resolves as an empty map. Unknown
keys fail startup validation rather than being ignored.

## What boot reconciles

- :seon.config/agent-context is the base configuration copied into a newly
  created agent.
- :seon.config/root-context is a sparse override for the agent whose
  :seon.agent/id is "root". Block maps merge by :seon.agent.ctx/name.
- :seon.config/namespaces controls which namespace sources are available and
  whether the current namespace is shown.
- :seon.config/render supplies cluster-wide rendering limits.
- :seon.config/skills selects directories scanned into the pull-reference
  corpus. Corpus entries do not become standing context blocks.
- :seon.config/routes adds or removes database-backed route facts.

The boot seed also reconciles the :seon.config singleton. Once the database is
attached, accessors read that singleton instead of repeatedly treating the file
as runtime state.

## Agent context is explicit

:seon.agent/ctx is the complete base block vector. There is no hidden default
tree and no implicit skills block. The shipped minimal tree currently includes
namespaces, canvas, plan, and transcript blocks.

The other agent-context inputs are:

- :seon.eval/home-requires: the namespaces available in a fresh agent's home
  namespace. Each entry must use :as or :refer.
- :seon.agent.runtime/wake?: an optional wake-policy override.

A root block with the same :seon.agent.ctx/name updates that base block; a new
name appends a block. Root is selected by its id, not by an entity kind.

## Namespace rendering

:seon.config/always is the namespace-source storage superset. The agent's current
namespace and its requires determine what is shown. Per-agent namespace block
facts can further select full source. Do not add a second namespace allowlist or
a second renderer.

## Skills stay pull-based

The skills section only chooses corpus directories. A skill is available for
explicit lookup or import; it is not injected into every prompt. Keep the
skills context block absent unless a measured use case proves it belongs there.

## Apply and verify

For a manifest-only change:

    bin/seon restart
    bin/seon status
    bin/seon logs pod --follow

Existing agents retain the block entities copied at creation. Use a cluster
reset only when the work explicitly requires a completely fresh database:

    bin/seon cluster reset default

Do not use reset merely to hide a reconciliation bug. Query the resulting
database facts and fix the reconcile path.

## Durable rules

- Use fully namespaced keys.
- Treat an explicit :seon.agent/ctx vector as the complete base tree.
- Keep root customization sparse and merge it by block name.
- Store operational configuration as database facts after boot.
- Keep skills out of default context.
- Change context structure through block data and render functions, not by
  appending prose to the agent's system text.
- Display sizes as estimated tokens through seon.ai.tokens/estimate.
