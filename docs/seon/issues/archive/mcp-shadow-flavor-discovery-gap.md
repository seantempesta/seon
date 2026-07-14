---
type: issue
status: resolved
severity: friction
tags: [issue, component, cljs, agent]
---

# Discover the selected Shadow flavor in MCP

## Problem

The operator now isolates default and ACME in separate Shadow cache roots and
servers, but the unified MCP adapter always reads the default checkout path
`.shadow-cljs/nrepl.port`. It can evaluate CLJ in another cluster through that
cluster's writer port file, yet it cannot address a CLJS pod attached only to
the ACME Shadow server.

Adding a second client registration is insufficient because the adapter has no
input or derived configuration for the artifact flavor's Shadow cache root.

## Evidence

- `script/seon/dev/config.clj` maps the default flavor to `.shadow-cljs` and
  ACME to `tmp/shadow/acme`, and selects that cache before the Shadow JVM starts.
- `script/seon/dev/mcp.clj` defines `shadow-port-file` unconditionally as
  `<project-root>/.shadow-cljs/nrepl.port`; `read-shadow-port`, CLJS sessions,
  runtime advertisements, and `eval_cljs` all consume that one file.
- `.codex/config.toml` and `.mcp.json` each register the same launcher without
  an artifact-flavor or Shadow-cache coordinate. Claude's `seon_cljs` name is a
  valid compatibility alias, but both registrations still target the default
  Shadow server.
- Vendored Shadow publishes `nrepl.port` under its configured cache root
  (`shadow.cljs.devtools.server/make-port-files`). Separate cache roots
  therefore intentionally produce separate discovery files and runtime sets.
- Current live proof has distinct default and ACME nREPL files. That proves the
  operator isolation fix while also making the adapter's default-only reach
  observable.

## Owner

The one development MCP adapter plus the operator's artifact-flavor/runtime
coordinates. The fix should derive or explicitly select an owned Shadow server;
it must not merge the two watchers or restore a shared mutable cache.

## Acceptance

- The default Codex and Claude registrations remain compatible and reach the
  default CLJ and CLJS runtimes after restart.
- An explicitly selected ACME registration or tool coordinate discovers
  `tmp/shadow/acme/nrepl.port`, routes `acme/<agent-id>` only through that
  server, and reaches ACME CLJ through its cluster writer port file.
- Bare or conflicting cluster/flavor selections fail with candidate identities;
  no adapter guesses by latest connection or fixed port.
- Status reports the selected checkout, cluster, artifact flavor, cache root,
  build id, and actual CLJ/CLJS endpoints.
- Focused tests and a concurrent default/ACME live probe prove isolation across
  watcher restarts without adding another MCP implementation.

## Resolution — 2026-07-14

`seon.dev.config/artifact-configurations` is now the shared source of every
development flavor's Shadow cache coordinate. The existing MCP server reads
those port files on demand, probes all reachable watchers, carries the selected
port through pinned session state, and reports all endpoint/build/runtime
identities. The focused MCP gate passes 12 tests/44 assertions.

Concurrent live proof found default `:client` on port 61576 and ACME
`:acme-client` on port 62266, evaluated both cluster-qualified roots, and
rejected bare `root` as ambiguous. Both CLJ writers were also reached through
their cluster-qualified dynamic port files.
