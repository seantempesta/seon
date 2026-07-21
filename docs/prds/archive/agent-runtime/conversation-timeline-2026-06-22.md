---
type: prd
status: draft
tags: [prd, agent, flow]
---

# PRD: One chronological conversation timeline (kill the answer-accounting)

**Status: draft — for review, then a focused implementation session.**
NEVER name the downstream consumer in code/docs — use `acme`.

## Problem

The agent "sees things multiple times": it re-answers messages it already
answered, and in its reasoning it goes nuts trying to make sure every user
message has a matching "answer." Root cause: the substrate tries to TRACK and
ACCOUNT for which message has been answered (a count of inbounds-vs-replies, an
"unanswered" framing), and the conversation isn't shown as one plain
time-ordered chat — so an already-answered message can read as new, and the
agent is pushed to reconcile answers it can't reliably pair.

Every attempt to make "answered?" precise has failed because it's a fiction —
with concurrent messages the substrate cannot know which reply addressed which
question:
- timestamp halt (`replied-since-inbound?` comparing latest out vs latest in) →
  one reply emitted after a 2nd message's `:at` looked like it answered both →
  dropped the 2nd.
- count halt (`unanswered-live-inbound?`, `#inbounds > #replies`) → balanced on
  reply-count, so a model that double-answered one question let another go
  unanswered and the loop halted anyway.

## Principle

**The reactive context IS the truth, derived from the DB. Show the conversation
as one plain chronological log — like a human reading a chat. The agent reads it
and decides what to say. The substrate does NOT track or enforce "each message
has an answer."** No accounting, no markers, no games.

## Design — ONE chronological timeline

Replace the separate `<past-evals>` eval-turn-blocks AND the conversation
rendering with ONE section: every user message, every agent reply, and every
agent eval (the command + its reader-safe result), interleaved strictly by
`:at` timestamp, clearly attributed.

```
<conversation>
;; Everything you've exchanged, in time order. Reply to the user when you have
;; something to say.
[t1 user] What's in the store?
[t1]      (seon.db/store-inventory) => […] ;; result/abc
[t2 assistant→user] 5 kinds, 9 datoms.
[t3 user] And the instructions?
</conversation>
```

- The data is already there, no schema change: messages carry
  `:seon.agent.message/at`, evals carry `:seon.eval/at`; a turn already bundles
  `:seon.agent.turn/woken-by` + `:seon.agent.turn/evals` +
  `:seon.agent.turn/messages`. Merge all messages + evals into one `:at`-sorted
  stream and render attributed lines.
- Keep the existing reader-safe result projection (compact values,
  `result/<id>` handles, the opaque-tag summary) and the transcript char budget
  (drop oldest first).
- Keep it pure-derived: same DB → same render every time. No stored state.

## Loop halt — dead simple

Halt when the agent has sent a reply to the user since it was woken (latest
outbound-to-user `:at` > latest inbound `:at`). Otherwise recur; `turns-cap`
bounds runaway. That is the whole stop policy. (Restore the simple
`replied-since-inbound?` shape; DELETE the count predicate.)

## Non-goals — RIP OUT, do not keep

- `unanswered-live-inbound?` and the inbounds-vs-replies count (`agent.cljs`).
- Any "answered/unanswered" marker, per-message answered-tracking, or "you have
  N unanswered messages" guidance.
- The two-section split (`<past-evals>` + a separate conversation) → collapsed
  into the one timeline.
- No queue of pending messages, no stored "answered" flag.

Accepted tradeoff (simple > perfect, per the owner): with the simple halt, if
the agent neglects a concurrent message, that's the agent's reading of a chat it
fully saw — NOT a substrate drop. We do not add machinery to prevent it.

## Files

- `seon.ctx.transcript` (and `ctx.cljs` where the section is wired/`system-text`
  lives) — the timeline render (merge messages + evals by `:at`); strip any
  "account for each message"/"unanswered" wording from `system-text`, replace
  with "here's the conversation in time order; reply when you have something to
  say."
- `agent.cljs` — `run-agentic-loop!` halt: restore simple `replied-since-inbound?`;
  delete `unanswered-live-inbound?` and any "coarse count."
- Tests: rewrite/delete those pinning the old two-section layout or the count
  predicate.

## Current tree state (for the fresh session)

- HEAD baseline already has: the curated namespace render (−78% `<namespaces>`),
  the reader-safe result projection, the simplified wake/turn core (#40-43 +
  #49/#50/#51 work), all committed.
- **Uncommitted right now: `src/seon/agent.cljs` + `src/seon/ctx/transcript.cljs`**
  — a partial, over-complicated attempt at this from the current session.
  **Recommend: `git checkout -- src/seon/agent.cljs src/seon/ctx/transcript.cljs`
  to discard it and implement this PRD clean from the committed baseline.**
- A separate `acme` packaging/harness track is also active in this shared tree;
  steer clear of `acme/`, `client.cljs`, `store/*`, `shadow-cljs.edn`.

## Verification

- The timeline renders messages + evals chronologically, clearly attributed; an
  already-answered message never appears as a fresh question; it reads like a chat.
- `grep` confirms NO `unanswered`/count/answered-tracking logic remains
  (`agent.cljs` + ctx).
- The loop halts when the agent has replied since being woken.
- `bin/test-cljs` green.
- Live (in the isolated `acme` harness, not the default cluster): two concurrent
  messages → the agent sees BOTH in the timeline; a re-wake shows the same chat
  including its own replies → no re-answering.

## Why dead-simple (the lesson)

Three prior attempts tried to make "answered?" precise and each failed at the
concurrent case. The substrate cannot reliably pair a reply to a question, so it
shouldn't try. Show the chat in time order; let the agent read it. Resist any
reviewer/agent instinct to "handle the edge" — that instinct is what produced
the count/answered machinery being removed here.
