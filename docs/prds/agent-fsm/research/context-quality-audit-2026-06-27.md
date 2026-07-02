---
type: research
status: active
tags: [research, agent, flow]
---

# Context-quality audit — overlap / repetition / colocation (initial live pass)

Owner directive (2026-06-27): "keep an eye out for bad context and also
overlapping context… colocate relevant context and not repeat ourselves." This is
the INITIAL pass, grounded in the LIVE pod (agent `dVB-2606270309`), to seed the
build-phase audit that pairs with the live DeepSeek drives. Method: dumped the
fixed system-text + each rendered block and compared.

## The live context, measured

Real rendered prompt ≈ **89k chars / ~22k tokens**, from:

| surface | chars | ~tokens | notes |
|---|---|---|---|
| system-text (FIXED, non-overridable) | 12,917 | 3.2k | the system role; `seon.ai/effective-system-prompt` |
| transcript (block, prio 100) | 53,438 | 13.4k | the whole bottom; rolling-window candidate |
| namespaces (block, prio 20) | 18,611 | 4.6k | my.* in full |
| warnings (block, prio 40) | 2,894 | 0.7k | reactive |
| live-tile (block, prio 35) | 1,034 | 0.3k | |
| inventory (block, prio 97) | 442 | 0.1k | |

**Blocks that render BLANK and vanish** (reactive self-healing, working as
designed): `shared-instructions` (my.kb has no rows), `soul`/`agents` (no
SOUL.md/AGENTS.md), `open-todos` (none open), `relevant-source` (SEON_EMBED off).

## Overlaps found (one fact, two homes — prune to one)

1. **system-text §"THE TRANSCRIPT IS ONE EVAL'ABLE REPL SESSION" ↔ the transcript
   masthead.** Both teach: live REPL, re-derives every turn / never stale, you
   write forms + `;` comments, the `;=>` value arrives next turn. → Let the FIXED
   system-text own the REPL-session framing; trim the masthead to block-specific
   cues only (oldest-first, "append below").
2. **system-text §"THE NAMESPACES BELOW" ↔ the namespaces block preamble.** Both
   say: my.* shown in FULL, the framework is not dumped / stays queryable, recency
   order. → system-text owns the policy; the block preamble keeps only "recency
   order, append" or drops entirely.
3. **Internal system-text repetition of "write form → read `;=>` next turn"** — in
   §TRANSCRIPT, §EVAL MECHANICS, AND §"WRITE FORMS; READ VALUES" (≥3×). Consolidate
   to one statement.
4. **Two standing-guidance surfaces** — system-text §STANDING TEACHINGS vs the
   `my.kb.shared` instructions block. Today the block is empty, but its PURPOSE
   (cluster-wide standing guidance) overlaps the fixed teachings. → Make the
   boundary explicit: system-text = FIXED mechanics/laws; `my.kb.shared` =
   cluster-appendable knowledge. Don't restate one in the other.

## Colocation / "bad context" notes

- system-text (3.2k tokens) is large for a FIXED surface that can never be pruned
  per-agent. Removing the duplicated framing (1-3 above) is the cheapest win.
- The masthead + namespaces preamble re-teaching system-text content means an edit
  to the framing must touch THREE places — the exact drift the no-repeat rule
  prevents.

## How to act on this (pairs with the live DeepSeek drives)

This is a TRIM-then-OBSERVE task, not a blind delete: per the roadmap milestone,
make a candidate trim (e.g. de-dup the masthead vs system-text), then drive a live
DeepSeek agent and READ its behavior — did removing the repetition hurt
comprehension? The standing guidance is show-don't-tell + align-context-with-runtime:
after any trim, re-read the actual agent-facing output. Deferred until Phases 1-2
land (the seed-copy model is where blocks become per-agent editable, which changes
who owns each fact).
