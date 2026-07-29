---
type: research
status: active
tags: [prd, agent, context, transcript, planning]
---

# Transcript aging and `my.plan` quarry

## Scope and dependency ledger

This is quarry evidence for the next design session, not an implementation
plan. It distinguishes three things that are easy to conflate:

1. the measured four-resolution transcript prototype;
2. the later mechanism that actually shipped in State A and now survives under
   `src-old/`; and
3. the fresh architecture and the owner's 2026-07-29 rulings that decide which
   lessons may survive.

The mechanism depended only on first-party transcript facts and rendering:
stored `:seon.agent.turn/*`, `:seon.eval/*`, and
`:seon.agent.message/*` facts; `seon.ai.tokens/estimate`; the one recursive
render path; and database-derived context blocks. There is no external library
semantic to import. Relevant source lineage is commit `9eb137228` (measured
prototype), `b563280ca` (first active age-band eviction), `4d31c47f6`
(three-level result decay), `5cfc0127e` (bounded total transcript), and
`b2d701ccd` (bounded newest-turn acquisition).

## Executive finding

The “pretty cool” work was real, but it has two layers. The measured prototype
assigned detail by **event age**—6 full events, then 10 light, then 16
pointer-only, then a permanent one-line summary head—and proved that the
summary prefix grew append-only and stayed byte-identical
(`docs/prds/archive/agent-fsm/research/tiered_transcript_proto.cljs:49-98`,
`docs/prds/archive/agent-fsm/research/transcript-dynamic-cache-aware-2026-06-28.md:28-38`).
That four-resolution gradient remained a prototype, explicitly outside every
classpath and test runner
(`docs/prds/archive/agent-fsm/research/tiered_transcript_proto.cljs:1-7`);
State A instead shipped **turn-aged result-body caps plus omission**, never a
stored or model-written summary.

## The measured prototype: four detail levels, not shipped

The prototype's tier was a pure function of the count of newer events. Its
recommended schedule was:

| Event age | Detail | Exact projection |
|---:|---|---|
| 0–5 | `:full` | the then-current full eval or message render |
| 6–15 | `:light` | narration ≤240 chars, source ≤300, value ≤400, error ≤200; retained `result/<id>` |
| 16–31 | `:pointer` | narration ≤140, source ≤160, result replaced by a type/size hint plus `result/<id>`; error ≤120 |
| 32+ | `:summary` | one line: first source line ≤60 plus result shape or failure; no result handle |

The exact projections are in the runnable research artifact
(`docs/prds/archive/agent-fsm/research/tiered_transcript_proto.cljs:54-80`);
the age calculation and fallback to `:summary` are
`tiered_transcript_proto.cljs:84-98`. Messages stayed full through the pointer
bands and clipped to 90 characters only in the summary band
(`tiered_transcript_proto.cljs:55-58`).

On the real root transcript (146 events, 21,843 estimated tokens), the
recommended schedule rendered 3,718 tokens: 1,673 tokens across 114 frozen
summary events and 2,045 tokens in the volatile 32-event tail. The research
proved the 140-event summary prefix was an exact prefix of the 143-event
version, which was an exact prefix of the 146-event version
(`docs/prds/archive/agent-fsm/research/transcript-dynamic-cache-aware-2026-06-28.md:97-106`,
`:166-205`). The important insight was not merely clipping: once an event
entered the terminal summary band its bytes never changed again, so the oldest
prefix only grew.

This design was explicitly display-only. Raw eval/message facts remained
unchanged, detail was recomputed during render, and re-reading a
`result/<id>` would create a new recent eval at full detail
(`transcript-dynamic-cache-aware-2026-06-28.md:232-234`,
`:272-279`). It proposed splitting frozen history into a cached block and
recent transcript into a volatile block (`:108-128`, `:236-263`), but that
split did not become the surviving runtime.

## State A as it actually worked

### Result detail decayed at turn offsets

The shipped transcript block carried three database-configurable decay levels:

| Offset from newest retained turn | Result-body cap |
|---:|---:|
| 0–1 | 4,096 estimated tokens |
| 2–4 | 1,024 estimated tokens |
| 5+ | 512 estimated tokens |

Those are the surviving defaults in
`src-old/seon/agent/ctx/transcript.cljc:60-70`. Selection took the level with
the largest `from-turn-offset` not exceeding the eval's offset
(`src-old/seon/agent/ctx/transcript.cljc:118-134`). During each render, the
renderer found the newest retained turn, calculated
`newest-turn-index - eval-turn-index`, and injected the selected cap into the
ordinary eval renderer (`src-old/seon/agent/ctx/transcript.cljc:1357-1392`).
Nothing changed in the stored eval; only its rendered result-body detail did.

The first active schedule, commit `4d31c47f6`, was 16,384 at offsets 0–1,
1,500 at offsets 2–4, and 200 at offset 5+. Commit `5cfc0127e` revised that to
4,096 / 1,024 / 512 because 200 tokens was too thin for identity and useful
diagnostics; the current source's `git blame` attributes the threshold change
to that commit. The detail transition happened only when a newer **turn**
crossed offsets 2 or 5, so an aged row was byte-identical between boundaries.

### Old history was omitted, not summarized

The surviving acquisition reads at most the newest 50 turns by a reverse
`:seon.agent.turn/at` index scan
(`src-old/seon/agent/ctx/transcript.cljc:969-1061`,
`:1121-1155`). It then reads only messages at or after the oldest retained
turn, except that the message opening the current run remains included even
when older (`src-old/seon/agent/ctx/transcript.cljc:1063-1096`,
`:1189-1201`). Older history is represented by one honest omission notice;
its events are not rewritten into a compacted story
(`src-old/seon/agent/ctx/transcript.cljc:1405-1415`).

Within the retained window, the newest 25 turns form an uncharged append-only
chunk. Events in the older retained chunk share an 8,192-token budget, charged
newest-first against each event's **complete rendered text**—source, narration,
result, and error—and an event that does not fit is omitted whole
(`src-old/seon/agent/ctx/transcript.cljc:75-80`, `:249-275`,
`:1393-1404`). Thus the actual expiry triggers were:

- a turn becoming 50+ turns older than the newest retained turn, at which point
  acquisition stopped reading it;
- an event in the settled 25-turn chunk failing the shared 8,192-token budget,
  at which point that whole rendered event disappeared; and
- a result crossing turn offsets 2 or 5, which reduced only its result-body
  detail rather than expiring the event.

The earlier `::tiers`, `clip-events-by-tiers`,
`clip-events-by-turn-window`, and `turn-window-cutoff` functions remain in the
quarry, but the live formatter no longer calls them. `rg` finds their only
callers in their own definitions and old tests; the render path uses bounded
acquisition plus `clip-rendered-events-by-settled-budget`. This matters: the
comments about a 50/25 “rotation” describe the July 14 predecessor, while the
final State A path enforces the same practical bound at acquisition.

### What triggered recomputation

There was no compaction job, timer, summarizer, or expiry transaction. A prompt
render against a database value re-acquired the bounded event window and
derived every cap and omission from that value plus the block's policy datoms
(`src-old/seon/agent/ctx/transcript.cljc:1121-1266`,
`:1270-1275`). Changing the block policy changed the next render; adding a
turn changed offsets; neither action stamped an event with a tier or deleted
its underlying facts.

