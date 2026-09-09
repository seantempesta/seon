---
type: issue
status: open
severity: friction
tags: [issue, render, schema, wave/agent-context]
---

# Raw identity projection hides a selected steward

The 2026-09-09 default fixture capture in
`docs/prds/context-generation/research/context-blocks-final-prompt-2026-09-09.txt`
explicitly pulls the namespace name and its steward. Its AI value instead
prints the namespace lookup ref `[:seon.ns/name my.agents.juniper]`, losing
the selected nested steward. The generated comment promises that identity
and steward will be visible.

The same capture runs `(dir my.agents.juniper)` after declaring four schemas,
but returns `[]`. The data-first namespace block's comment promises functions
and schemas; acquired dir currently returns function documentation only.

The namespace/source-render owners are concurrently edited by the data-first
lane. This note records the exact remaining presentation boundary without
changing that lane's files. Acceptance: selected raw pull data remains visible,
and directory data includes declared schemas once as required by §18c.
