---
type: issue
status: resolved
severity: friction
tags: [issue, agent, runtime, web]
---

# Context usage normalization missed Muse cache metadata

## Problem

`seon.agent.ctx.usage/extract` read DeepSeek's
`:prompt_cache_hit_tokens` but not the default Muse/Meta gateway's nested
`:prompt_tokens_details/:cached_tokens`. Missing cache metadata defaulted to
zero, so a valid Muse cache hit and a client-estimated stream-abort usage map
both looked like provider-reported 0% cache usage. Unknown and malformed maps
similarly normalized to plausible all-zero counts.

## Resolution

The retained normalization now derives one validated provider-independent
projection from the persisted EDN. DeepSeek's direct and nested cache fields
must agree; Muse's nested-only field is accepted; Anthropic input totals add
cache reads and cache creation; every count is a non-negative integer; and an
absent cache field remains absent. Malformed or unknown shapes produce a debug
diagnostic rather than numbers.

The existing debug turn projection exposes the normalized values or diagnostic.
The existing transcript HTML surface renders one compact usage row per captured
turn. A sibling `:seon.agent.turn/usage-estimated?` fact labels stream-abort
numbers explicitly as estimates, while a turn without usage produces no row.
Provider fixtures and focused debug/transcript tests cover each branch.
