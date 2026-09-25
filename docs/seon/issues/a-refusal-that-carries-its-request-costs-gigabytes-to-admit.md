---
type: issue
status: open
severity: performance
created: 2026-09-25
tags: [issue, render, one-render-path]
---

# A refusal that carries its request costs gigabytes to admit

A floor render of a value with no block id costs 4.6 s and 22.8 GB, then refuses: the refusal puts the whole request (SCI ctx and database included) in `:seon.error/offending` (render/value.clj:249-257), and admission walks all of it (kernel.clj:642). Offending values must name the member and a bounded value, never the world.

Evidence: docs/research/agent-platform/render-graph-research-2026-09-25.md §8 (research-render-graph-opus, default pid 59566).
