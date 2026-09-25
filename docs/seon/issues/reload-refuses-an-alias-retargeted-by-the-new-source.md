---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, reload, adoption, aliases]
---

# Reload refuses an alias retargeted by the new source

When a namespace's new source retargets an alias (S-process cut: `seon.test.cache` and `seon.issue` changed `process` from
`babashka.process` to the process owner), `require :reload` in development adoption refuses "Alias process already exists in
namespace seon.test.cache, aliasing babashka.process" (`seon.cluster/load-development-definitions!`), and adoption fails.
Root unblocked 2026-09-25 23:15Z with `(ns-unalias ns 'process)` per namespace. Owner fix: adoption diffs each reloaded
namespace's alias table against its new `ns` form and removes retargeted/removed aliases before `require :reload`; regression =
adopting a source that retargets an alias. Related: stale `:refer`s likewise.
