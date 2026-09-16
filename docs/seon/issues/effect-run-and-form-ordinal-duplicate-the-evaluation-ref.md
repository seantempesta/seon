---
type: defect
status: open
severity: cleanup
tags: [schema, database, effect, turn]
---

# `:seon.effect/run` and `/form-ordinal` duplicate `:seon.effect/eval`

## What

`:seon.effect/eval` (landed 2026-09-16) refs the `:seon.cluster.eval` entity a
capability request was made from. `:seon.effect/run` and
`:seon.effect/form-ordinal` are exactly the two parts of that evaluation's
identity — `seon.id/evaluation` derives the evaluation id from the turn id and
the ordinal (`src/seon/id.clj:55`) — stored beside the entity they name. That
is one fact in two spellings, which §2.5 of `AGENTS.md` rules out.

## Why it is still there

The retirement is a one-commit conversion of every reader, and the readers
outside `seon.effect` are in `src/seon/turn.clj`:

- `src/seon/turn.clj:1747` calls `seon.effect/interruption-stamps`, which
  queries `[?effect :seon.effect/run ?run]`;
- `src/seon/turn.clj:2005-2006` joins `[?effect :seon.effect/run ?turn]` with
  `[?effect :seon.effect/form-ordinal ?ordinal]` to decide
  `read-only-evaluation?` — with `:seon.effect/eval` that becomes one clause
  against the evaluation entity it already holds.

`src/seon/turn.clj` was concurrently edited by another lane when the ref
landed, so the effect-facts lane was explicitly barred from touching it.

## Also open

`:seon.effect/eval` is recorded only when the evaluation entity exists at the
writer (`seon.effect/evaluation-eid`). Every request from a real turn has one,
but several test fixtures construct a turn and no evaluation, so the ref is
absent there. When the fixtures carry evaluations, `open-call` should refuse a
request with no evaluation rather than record none.

## Done when

- `seon.turn`'s two readers join through `:seon.effect/eval`;
- `:seon.effect/run` and `:seon.effect/form-ordinal` are deleted from
  `resources/seon/schemas/seon.effect.edn` and from `seon.effect`'s writer and
  renderers, in the same commit;
- `open-call` refuses a request whose evaluation does not exist;
- the effect identity is respelled
  `(seon.id/id [(seon.id/evaluation turn-id ordinal) effect-ordinal])`, so it
  derives from the ref the entity stores (research §4).
