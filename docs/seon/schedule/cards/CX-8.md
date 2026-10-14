---
title: "Card CX-8 Delete the system turn, virtual turns, compaction and the web context editor"
type: reference
status: done (55ede54a9)
created: 2026-10-08
---

# CX-8 Delete the system turn, virtual turns, compaction and the web context editor

Ledger row CX-8 (group D/B; replaces S4-07b1). Owner ruling round 10: system turns, virtual turns and compaction are
retired; one debug view (CX-7). Lane: Sonnet. Deletion.

## Deleted (by name; every caller converted in the same commit)

- `seon.render.web/change-context`, its dispatch table, `context-action-response` and its route, the system-turn
  action button.
- `seon.render`'s `:seon.render/context-action` branch.
- `seon.turn/system-turn`, `seon.turn/compact-call`, `seon.turn/compact!`, `seon.turn/virtual-turn!` and private
  helpers left without callers.
- `seon.sci.eval/rendered-evaluation` (its only caller is inside `system-turn`): a hunk for the `sci/eval.clj` owner
  if the file is held.
- Tests: `web_context_test` deftests that drive the context POST; `web_debug_test` deftests for the system-turn or
  virtual-turn buttons; `turn/compact!` steps inside `seon.turn-test/a-wake-meeting-an-open-turn-releases-without-a-fault`
  and `seon.loop-proof-test/virtual-loop-end-to-end` (keep the rest of each); `seon.turn-test/compaction-refuses-an-open-turn-at-the-writer`.
Net ≈ −350 source lines (`system-turn` alone is 209 at `6faffd0f7`).

## Proof

Existing `seon.render.web-context-test/the-context-view-is-computed-only-when-opened` stays green; D: a POST to the old
context-action route answers 404 data; no Var named `system-turn`, `virtual-turn!` or `compact!` resolves in
`seon.turn`.

## Dependencies and order

CX-3, CX-6a (they remove `system-turn`'s other callers), CX-7. Files: `src/seon/render/web.clj`,
`src/seon/render.clj`, `src/seon/turn.clj`, `src/seon/sci/eval.clj` (one function), the tests named.
