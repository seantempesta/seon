---
type: issue
status: open
severity: performance
created: 2026-09-25
tags: [issue, render, one-render-path]
---

# The turn renderer queries the database per turn

Rendering root's 42 turns issues 13 queries per turn: 4.5 s and 18.9 GB. Renderers read no database (render law); the walk supplies node values; one turn renders in 1.4–1.9 s and caps any parallel render.

Evidence: docs/research/agent-platform/render-graph-research-2026-09-25.md §8 (research-render-graph-opus, default pid 59566).
