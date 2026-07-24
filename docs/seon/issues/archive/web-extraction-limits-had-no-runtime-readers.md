---
type: issue
status: resolved
severity: friction
tags: [issue, runtime]
---

# Web extraction limits had no runtime readers

Three web limits validated and persisted while the Bun extraction leaf used
literal link, HTML-size, and nesting caps. Overrides therefore appeared valid
without changing behavior.

Resolved on 2026-07-23 by threading the frozen operation configuration into
the extraction boundary, reading each fact at its enforcement point, and
including all three in the loud missing-limit check. Focused regressions prove
each override changes the corresponding bound.
