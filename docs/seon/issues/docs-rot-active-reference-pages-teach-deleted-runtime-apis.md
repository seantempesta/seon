---
type: issue
status: open
severity: friction
tags: [issue, reference, web, flow, agent]
---

# Demote active reference pages that teach deleted runtime APIs

## Problem

Several `docs/seon/reference/` pages retain `status: active` while presenting
old project-specific runtime recipes as current. Their frontmatter and prose
turn historical research into copy-paste guidance for APIs and processes that
no longer exist.

## Evidence

- `third-party-integration.md:7-31` describes a supervised CLJS pod plus JVM
  writer, `bin/seon up|restart`, `SEON_CONFIG`, and `config/system.edn`.
  `bin/seon` now exposes start/config/status/init/stop/down/reset over the fresh
  operator, and the live manifest is `config/default.edn`.
- The same page's override section (`:55-110`) instructs a CLJS preload through
  `SEON_EXTRA_SRC`/`SEON_EXTRA_PRELOAD` and says agents cannot override core.
  This contradicts CLJS-off and ruling #20; it is the live reader keeping
  `examples/third-party-override` looking current.
- `config-operations.md:9-160` documents writer reconstruction, pod
  reconnection, UDS transport/executor dials, old reset commands, and
  environment-only turn/watchdog bounds. Those attributes are absent from the
  fresh `resources/seon/schema/config.edn`; current dials include the fresh
  eval, render, AI, error, message, store, and flow settings.
- `datastar-quick-reference.md:13-175` calls atom watches,
  `seon.web.sse/render-handler`, `refresh-all!`, whole-view rerenders, and
  direct HTML responses current production patterns. No fresh `seon.web.sse`
  namespace exists; `src/seon/render/web.clj` owns one transaction-woken Flow,
  shared complete snapshots, per-tab diffs, and Datastar morphs.
- `flow-foundation.md:10-105` actively concludes that Flow cannot be Seon's
  foundation because agents are external Claude/nREPL processes and Integrant
  must own resources. Current source makes every agent its own Flow graph in
  `src/seon/cluster/agent.clj` and `src/seon/flow.clj`.
- `separate-jvm-exploration.md` remains active despite describing the rejected
  per-agent JVM model, and `hyperlith-comparison.md` remains active despite
  recommending atom-watch refresh and route patterns rejected by the current
  render flow.

Reader chasing shows these pages are not sealed archaeology.
`durable-ctx-design.md:8,353` calls `datastar-quick-reference.md` the current
production guide; that quick reference calls `docs/conventions.md` SSE ground
truth and links the Datastar deep dive. `docs/seon/concepts/feeds.md` cites
`flow-foundation.md` for the signal pattern. `third-party-integration.md`
points onward to `llm-adapters`, `examples/third-party-override`, ACME, and the
CLJS component docs. The archived `stale-reference-docs.md` issue declared
several of these fixed, which gives later readers false confidence while their
mechanisms have since changed again.

## Owner

`docs/seon/reference/` owns maintained operator and implementation references.
Historical research may remain, but its frontmatter and first screen must make
it impossible to treat as current API guidance.

## Acceptance

- Re-audit every active reference page against fresh entry points and demote,
  archive, or rewrite pages whose project-specific mechanism is gone.
- Copy-paste examples name only existing namespaces, commands, files, config
  attributes, and process topology.
- Chase inbound links in current concepts, draft references, component docs,
  examples, and runbooks so no outer reader re-promotes a demoted page.
- Preserve genuinely useful dependency research as clearly historical source
  material rather than deleting it blindly.

## Progress — 2026-08-01

Commit `116cc7854` rewrote the maintained operator, integration, Datastar, and
Flow references against fresh source; corrected the active linting examples;
and replaced the abandoned project-specific recipes with short fail-closed
historical notes. It removed 7,784 lines of deleted pod, writer, atom-watch,
`refresh-all!`, external-agent, and separate-JVM guidance.

Proof:

- `seon.dev.markdown/validate-file` reports no violations for all fourteen
  changed reference pages.
- The only remaining old runtime names in `docs/seon/reference/` occur in
  explicit statements that those APIs do not exist.
- The active implementation claims were checked against
  `script/seon/fresh_operator.clj`, `src/seon/{config,flow}.clj*`,
  `src/seon/cluster/agent.clj`, `src/seon/render/{route,web}.clj`,
  `resources/seon/schema/config.edn`, `config/default.edn`, and the pinned
  Datastar and core.async sources.

One protected inbound remains before this issue can close:
`docs/seon/concepts/feeds.md:22` says `flow-foundation.md` contains a
`status-aggregator-step`/`::agent-heartbeat` signal pattern. The fresh Flow
reference and source contain no such mechanism. This lane does not own
`docs/seon/concepts/**`.

An independent acceptance pass found that the first rewrite had retained the
obsolete standalone-lint narrative and named a nonexistent
`seon.render.web/block-fragment`. Commit `c9d428250` replaced the lint page
from `bin/lint`, `bin/seon-hook`, and `seon.fn.analyzer`, and corrected the
render boundary to the actual public `seon.render.web/surface-html` and
`seon.render.web/feed` functions. Both pages pass
`seon.dev.markdown/validate-file`.
