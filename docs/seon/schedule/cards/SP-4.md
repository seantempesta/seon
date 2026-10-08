---
title: "Card SP-4 Model reasoning takes the one threshold in bytes"
type: reference
status: active
created: 2026-10-08
---

# SP-4 Model reasoning takes the one threshold in bytes

Design: `docs/research/agent-platform/2026-10-08/storage-point/storage-point.md` §1 (the sixth decision). Lane:
Sonnet.

The model-attempt recorder (`src/seon/turn.clj:4203-4212`, `:4253-4257`) compares the reasoning's `count`
(characters) with the blob threshold and stages by hand, so a reasoning of 4,096 characters of three-byte text (12 KB)
stays inline, and `:seon.ai.attempt/reasoning-size` is in characters where every other `*-size` is UTF-8 bytes. The
reply beside it already uses `seon.blob/inline-or-stage` (`turn.clj:83`, SR-B); reasoning takes the same call.

## Files

`src/seon/turn.clj`, `resources/seon/schemas/seon.ai.attempt.edn`, `test/seon/cluster/turn_test.clj`.

## Functions

- CHANGE the attempt recorder in `turn.clj`: delete `reasoning-size` (`:4203-4204`), the `threshold` query
  (`:4205-4208`), `reasoning-stage` and `reasoning-blob` (`:4209-4212`); bind
  `{reasoning-fact :seon.blob/fact reasoning-writes :seon.blob/staged-writes}` from
  `(when (seq reasoning-content) (blob/inline-or-stage connection reasoning-content :seon.ai.attempt/reasoning :seon.ai.attempt/reasoning-size :seon.ai.attempt/reasoning-blob))`;
  `(merge row reasoning-fact)` replaces the two `cond->` clauses (`:4253-4257`); the staged writes at `:4272` become
  `(into ... reasoning-writes)`.
- SCHEMA `seon.ai.attempt.edn`: `:reasoning-size [:int {:min 0 :seon.wake/context-inert true :description "UTF-8 bytes of the reasoning."}]`;
  `:reasoning [:and {:seon.wake/context-inert true} :seon.blob/inline-text]` (the writer's bound, SP-2).

## Proof

`seon.cluster.turn-test/reasoning-over-the-threshold-in-bytes-is-a-blob` (new): a stubbed provider reply (the test's
existing external-effect substitution) whose reasoning is 2,000 copies of `"é"` (2,000 characters, 4,000 bytes) is
inline with `reasoning-size` 4000; 2,100 copies (4,200 bytes) store `reasoning-blob` and `reasoning-size` 4200 and no
`:seon.ai.attempt/reasoning`.

## Other tests reaching it

`seon.cluster.turn-test`, `seon.ai-test`, `seon.render.transcript-test`.

## Cost

One byte count instead of one character count; no change in passes.

## Deleted

The hand-staged reasoning path and its threshold query.

## Commit

`bin/lane commit <lane> -m "SP-4: model reasoning takes the one threshold in bytes" --test seon.cluster.turn-test seon.ai-test seon.render.transcript-test src/seon/turn.clj resources/seon/schemas/seon.ai.attempt.edn test/seon/cluster/turn_test.clj`

## Dependencies

SP-2 (declares `:seon.blob/inline-text`). `src/seon/turn.clj` and `test/seon/cluster/turn_test.clj` are held by lane
`f4a`: start after it releases. SR-G (usage and settings as data) touches the same `row`: serialize by claim.

## Reset

None.
