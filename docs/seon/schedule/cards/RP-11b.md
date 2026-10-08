---
title: "Card RP-11b: The same cursor for model replies"
type: reference
status: active
created: 2026-10-08
---

# RP-11b The same cursor for model replies

Ledger row RP-11b (S6-03, group B). Design: `redo/pass-and-recording.md` §2 READ (reply mode: one forgiveness scan
per text, then `sci/parse-next` at the cursor), §3. Lane: Sonnet. Cited at HEAD `e7f120672`.

## What changes
`call-turn`'s `freeze!` (after RP-10) segments the reply with `planned-sources` (`src/seon/turn.clj:3044`) and puts
`:seon.turn/sources` in the submission. It now puts `:seon.batch/text reply-text :seon.batch/mode :reply` instead, and
`evaluate-sources` (RP-11a1) reads at the cursor in reply mode (`forgive/read-state` and `sci.batch/read-next` already
apply the reply line rules for `:reply`: `seon.sci.forgive/step` `src/seon/sci/forgive.clj:320`). A prose-only reply
(no form) is answered as today: one call row with the no-form diagnostic and no evaluation (agent-loop.md §3.3); the
fold's `:seon.batch/unread`/no-entry result is that case: `freeze!` checks `(empty? (:seon.batch/entries batch))`
after evaluation instead of `no-forms?` before it, and records the call row with the diagnostic through the same
settlement (`settle` with zero entries records the call row only, RP-7's call-row-alone path).
Repairs attach per entry from the read step (`:seon.batch/repairs` on the read step, as `code-request` does).

## Files
`src/seon/turn.clj`, `test/seon/cluster/turn_test.clj`.

## Functions
- CHANGE `seon.turn/call-turn` (`:4430`) `freeze!`: drop `planned-sources`, `prepared`, `no-forms?`, `sources`; the
  submission carries text and mode; the `(empty? sources)` → `:released` arm becomes "empty reply text with
  `:seon.config.ai/no-provider`" only.
- `seon.turn/planned-sources`, `segmented-sources` stay for RP-11c's deletion.

## Proof
The RP-11a rows through the fixture model (`support/with-provider` + `support/reply-queue`):
`seon.cluster.turn-test/a-model-reply-reads-at-the-cursor` (new): reply ``"Text.\n```clojure\n(require '[clojure.string :as s])\n(s/upper-case \"a\")\n```"``
answers `"A"` for the second form (the alias required by the first form resolves), and the prose line is the first
form's comment (`:seon.cluster.eval/comment` of ordinal 0 = `";; Text."` or as the reply rules store it: read it once
at the REPL and assert that exact value).

## Commit
`bin/lane commit <lane> -m "RP-11b: model replies read at the live cursor" --test seon.cluster.turn-test --test seon.turn-test --test seon.sci.forgive-test src/seon/turn.clj test/seon/cluster/turn_test.clj`

## Cost
As RP-11a; the model eval request's reply is scanned once.

## Dependencies
RP-11a1, RP-10, RP-7 (call row alone). Group B.

## Reset needs
None.
