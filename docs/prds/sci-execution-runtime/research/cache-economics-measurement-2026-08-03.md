---
type: research
status: complete
tags: [research, ai, provider, caching, render]
---

# Cache economics measurement — 2026-08-03

## Result

DeepSeek automatic prefix caching became observable after volatile walk
metadata moved behind the stable rendered content. Two consecutive
`deepseek-v4-flash` turns on a cluster reforked from published source commit
`6a70a074-15d1-5655-b711-35de4115509d` produced 768
`prompt_cache_hit_tokens` on the second turn. The exact common prefix grew
from 124 bytes before the change to 20,469 bytes after it.

The provider reused fewer tokens than Seon's estimate of the common prefix.
DeepSeek persists and reports its own complete prefix units; its cache is
best-effort. The acceptance condition was a positive provider-reported hit,
not equality between the local estimate and provider usage.

## Dependency ledger and protocol

- DeepSeek's automatic prefix cache and usage fields are grounded in
  [LLM provider research](llm-provider-research-2026-08-03.md) and the primary
  provider sources linked there.
- Exact provider input is `:seon.context.capture/prompt`; the loop commits it
  before `seon.ai/complete` and persists the provider's open usage document on
  the attempt row (`src/seon/cluster/loop.clj`, `src/seon/context.clj`).
- The projection owner is `seon.render.walk/prose`, reached by the shared
  `seon.render/walk` AI/HTML mechanism (`src/seon/render/walk.clj`,
  `src/seon/render.clj`).
- The implementation commit is `412885b08` and changes only
  `src/seon/render/walk.clj` plus
  `test/seon/cluster/prompt_test.clj`.

Each measurement used a freshly reforked default cluster with zero attempt and
capture rows. A database listener was registered before each POST and completed
only when the attempt's `:seon.ai.attempt/usage-edn` datom committed. Turn two
was submitted after turn one's run carried `:seon.cluster.run/closed-at`.
Both messages requested the same one-form completion and differed only in the
terminal result string.

## Complete baseline capture diff

The exact pre-change captures were aligned by longest common subsequence over
all lines. They contain 252 identical lines, seven deletions, and fourteen
insertions across ten edit runs. No divergent line exists outside these
classes, listed by first appearance:

1. The top elision summary changed from 34 branches / 416 estimated tokens to
   35 branches / 428 estimated tokens.
2. Existing entity 11164's branch index changed from 4 to 5.
3. Existing message 11227's locator changed from depth 2 to depth 1 plus
   branch index 3; its content stayed identical.
4. Existing transaction 536870954's branch index changed from 6 to 8; its
   transaction value stayed identical.
5. Existing run 11228's locator changed from depth 1 / branch index 2 to depth
   2.
6. That same run changed semantically from running under the process to
   completed.
7. The second capture added message 11234 and the second measurement request.
8. It added transaction 536870961, including its transaction instant and
   process/user refs.
9. It added run 11235, including run identity, opened instant, and live process
   state.
10. The terminal REPL-state line changed basis transaction and transaction
    instant.

Classes 1, 2, 4, 5, and 10 exposed projection metadata. Classes 6 through 9
are changed database content and remain in the context. Depth is retained
beside each unit because it communicates semantic hierarchy. Exact branch
paths are ordering-sensitive drill coordinates: stable unit identity remains
beside the content while the exact path moves to the volatile suffix keyed by
that identity.

The elision notice is also load-bearing where the omission occurs. Stable
wording and the exact drill command therefore remain below the walk header;
only branch and token counts move to the suffix.

## Before

| Evidence | Turn one | Turn two |
|---|---:|---:|
| Capture entity | 11229 | 11236 |
| Basis transaction | 536870955 | 536870962 |
| Prompt bytes | 21,238 | 21,740 |
| Prompt SHA-256 | `c31ab42d0b29288446dc05a55a3a88fbae3cb03bc6e8269b093ad87093487cd2` | `8147f42f0a52b0add3fde66e96804e2981c21e41c681b5c83682dfa9be47727e` |
| Provider prompt tokens | 6,937 | 7,136 |
| `prompt_cache_hit_tokens` | 0 | 0 |
| `prompt_cache_miss_tokens` | 6,937 | 7,136 |

The first divergent UTF-8 byte was zero-based offset 124, inside the elision
summary. The common prefix was 124 bytes / approximately 31 tokens, SHA-256
`29f4eb20e4610250de591ba0acb22445b7b6bcd6cb30e94470a6ab8a54f1b67b`.

## Change

The stable prefix now contains:

- the stable walk header;
- stable elision guidance with the exact deeper-walk command;
- unit identities, semantic depths, and rendered instruction/document/program
  content; and
- changed database content in deterministic walk order.

One terminal region starts with `;; Volatile context metadata`, followed by
exact elided-branch/token counts, ordering-sensitive branch paths keyed by unit
identity, and the REPL-state namespace/basis/time line. No volatile assembly
metadata precedes the stable content.

## After

| Evidence | Turn one | Turn two |
|---|---:|---:|
| Capture entity | 11229 | 11236 |
| Basis transaction | 536870955 | 536870962 |
| Prompt bytes | 21,376 | 21,900 |
| Prompt SHA-256 | `90a6434b4dae063edf5273afb52b9e37aa4ca1361a29b94331e9867e8587bb78` | `7b3d12bcc29104f3b537bffb1556bff2782099c68e07ae5315715a2c9118986d` |
| Provider prompt tokens | 6,984 | 7,197 |
| `prompt_cache_hit_tokens` | 0 | **768** |
| `prompt_cache_miss_tokens` | 6,984 | **6,429** |

The first divergent UTF-8 byte moved to zero-based offset 20,469, at the first
semantically changed run unit near the context tail. The common prefix was
20,469 bytes / approximately 5,093 tokens, SHA-256
`d9d4d9852299e78b3d4ed20497cefaca775ecd11fa0a68efd96b7eeedec2b724`.

Both first lines were byte-identical and contained no basis transaction. Both
captures ended with their own basis transaction and transaction instant after
the volatile boundary. This pair used two paid calls; together with the two
baseline calls, the complete before/after measurement used four.

## Acceptance

The slice passes its provider-backed boundary:

- exact captures prove a multi-unit stable prefix;
- volatile assembly metadata is confined to one suffix region;
- the recurring prompt test proves local elision guidance and suffix ordering;
  and
- DeepSeek's durable usage row reports a positive cache hit on turn two.
