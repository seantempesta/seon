---
type: issue
status: open
severity: friction
tags: [issue, runtime, wave/provider-context]
---

# Scratch root has agent-authored errors without provider attempt facts

The cookbook scratch JVM started at 2026-09-09T23:20:35Z. Before Juniper was
seeded, runtime status reported 20 errored evaluations for root. Examples have
`:seon.cluster.eval/author :agent` at 23:20:52Z: invalid collection-find syntax
and fabricated `#:seon.repl` responses, correctly refused by their boundaries.

At basis 536871019, a query over installed `:seon.ai.attempt/id` returned `[]`.
A separate pull through root turns' `:seon.turn/attempts` also returned no
attempts. These observations do not establish how the reply originated or
whether a provider was called. The origin needs investigation; no cause is
attributed here. The later explicit comprehension HTTP request is separate.

Evidence: `docs/prds/context-generation/research/context_cookbook_scratch_root_errors_2026_09_09.edn`.
Juniper was independently reseeded and its trial preconditions verified one
system turn, eight unique reads, four orders, one incoming fixture message,
twenty remaining turns, and no errored evaluations. The scratch cluster was
disposable; reproducing the root observation requires a new isolated root,
never resetting default for this investigation.
