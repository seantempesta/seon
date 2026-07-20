---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, component]
---

# file-block was a live mechanism with zero manifest usage

## Problem

`seon.agent.ctx/file-block{,-ai,-html}` — the one mechanism for turning an
on-disk markdown file into a renderable context section — had ZERO usage:
no shipped manifest declared a file-backed block since the identity-file
seeding was deleted (`c35677fa`, soul-off default). Keep-or-delete was an
open owner question in
`docs/prds/source-cleanup/deletions-and-wiring.md`, and it was unproven
whether the manifest decode path even preserved a declared
`:seon.agent.ctx/file-path` block.

## Resolution (owner ruling 2026-07-20: KEEP as the general mechanism)

The manifest → file-block path already works end to end with no code
change. Traced and proven:

- `seon.config/resolve-agent-context` decodes a block map carrying
  `:seon.agent.ctx/file-path` plus the two render symbols VERBATIM (the
  block vector is the documented loose `[:vector :map]` leaf; the
  `:seon.agent.ctx/block` map schema is open and `file-path` is a
  registered attribute, so seed-copy transacts it and the wildcard
  `{:seon.agent/ctx [*]}` prompt pull returns it to the slot fns).
- Behavioral test:
  `seon.ctx-test/manifest-file-block-renders-fresh-and-omits-when-absent`
  (decode preserved · file present → section renders priority-ordered ·
  file edited → fresh re-read next render · file absent → section
  omitted, no fallback).
- Live proof on the default cluster: a scratch manifest declaring a
  `:notes` block (`tmp/NOTES.md`, priority 30) was applied via
  `bin/seon config apply`; a fresh agent (`tame-shoes-raise`) rendered
  `┌─ notes ─` between `:namespaces` (20) and `:canvas` (35) in
  `seon.agent.debug/ctx-preview`; editing the file changed the next
  render; deleting it removed the section entirely. Cluster config was
  restored to `config/system.edn` and the test agent terminated.

A documented commented-out example of the general shape now lives in the
CONTEXT TREE section of `config/system.edn`.
