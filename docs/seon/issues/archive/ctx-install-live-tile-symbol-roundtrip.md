---
type: issue
status: resolved
severity: friction
tags: [issue, agent]
---

# `ctx/install!` broken for any agent whose ctx carries a `:canvas` block

Found 2026-07-06 during the multi-agent context unit's live proof (installing
the new `:subagents` block onto root).

**Symptom:** `ctx/install!` on root fails validation and can never add a
block. Its upsert path re-transacts ALL kept blocks, but a kept
`:seon.render.canvas/content` reads back from the entity as a STRING
(`"seon.render.system/system-view"`) while the schema requires `:symbol` —
so the re-transact of untouched blocks is rejected.

**Root cause:** a symbol/EDN-string round-trip asymmetry between the storage
bridge (writes the symbol) and `ctx-entities`' read in `install!`'s kept
path (`src/seon/agent/ctx.cljs:1987-1995` at time of filing).

**What it should be:** kept blocks either aren't re-transacted at all
(append/replace only the delta), or the read path round-trips symbols
faithfully. Fix the bridge/read asymmetry, not the call sites.

**Workaround used (live proof):** plain validated transact appending the new
blocks to the cardinality-many `:seon.agent/ctx` component vector — exact
config block shapes, no replace.

**Suspect every `install!` caller** on agents whose ctx contains any
symbol-valued block attr — not just root/canvas.
