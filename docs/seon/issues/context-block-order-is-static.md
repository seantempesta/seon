---
type: issue
status: open
severity: friction
tags: [issue, agent, architecture]
---

# Context block order is static

## Problem

The prompt renderer sorts top-level blocks by a hand-set integer
`:seon.agent.ctx/priority`. New blocks therefore require a human to predict
their future volatility, and blocks do not naturally migrate toward the stable
prefix or volatile tail from observed behavior. This falls short of the
architecture's database-derived cache gradient.

## Evidence

`seon.agent.ctx/agent-blocks` and `context-root` sort by
`(juxt :seon.agent.ctx/priority :seon.agent.ctx/name)`. The cache breakpoint
then partitions that same static priority. Namespace members and render
surfaces already query source/data transaction recency, and
`block-chain-keys` already identifies the first changed rendered block, but no
turn records per-block content hashes and no general ordering query consumes a
block changelog.

## Owner

`seon.agent.ctx` prompt assembly and `seon.agent.turn` observability capture.
The database owns historical block observations; current stability estimates
and order are derived projections, never mutable fields on a block.

## Acceptance

- Each sent turn records block name, content hash, estimated tokens, position,
  and cache band alongside its prompt coordinate.
- Ordering derives a smoothed change probability from those observations and
  sorts within semantic bands by change risk per cacheable token. Configured
  priority is the bootstrap prior and name is the final stable tie-break.
- Reordering happens only at a measured epoch boundary and clears a hysteresis
  margin. The stable body, transcript window, and free dynamic tail cannot
  cross bands.
- Two renders of one database coordinate produce the same database-derived
  order and body bytes.
- Inspect drives plus provider usage compare static priority with candidate
  history windows, priors, epochs, and margins on cached tokens, total tokens,
  latency, and task outcome before the dynamic policy becomes the default.
