---
title: "Card RP-17a: Convert the seon.sci.reader/read callers"
type: reference
status: active
created: 2026-10-08
---

# RP-17a Convert the `seon.sci.reader/read` callers

Ledger row RP-17a-b (P13; group B/C; the declaration deletion itself is UL-1d's under the "know what things are"
ruling). AGENTS.md words: SCI's reader for agent forms, never a second reader. Lane: Sonnet. Cited at HEAD
`e7f120672`.

## The callers (verified: `rg -n 'reader/read \{' src`)
- `src/seon/turn.clj:1701` (`unit-read`, the refresh's read: deleted by S4-06 with the refresh; skip if gone),
  `:2333` (`evaluable-source?`).
- `src/seon/sci/eval.clj:1934`, `:3697` (`one-event`'s read).
- `src/seon/cluster/agent.clj:185`.
- `src/seon/render/ns.clj:246`, `src/seon/render/value.clj:1781` (display reads of stored text).

## Rule (decided here)
Every caller reads with SCI's own parser at a cursor: `(seon.sci.forgive/next-form (seon.sci.batch/read-stage ctx) :code (seon.sci.forgive/read-state text))`
(one form) or a loop of it until `:seon.batch/eof` (all forms), inside `(sci/binding [sci/ns (sci/create-ns ns)] …)`
for the namespace the text belongs to, as `seon.turn/read-row` does (`turn.clj:4752-4754`). The `ctx` is the caller's
own when it has one; the render callers take the viewing agent's ctx from their render request (the renders already
receive `:seon.sci.eval/ctx` in their profile or world: `rg -n 'seon.sci.eval/ctx' src/seon/render/ns.clj src/seon/render/value.clj`);
where none is reachable, the cluster base ctx (`seon.sci.eval/base-ctx`) of the database value they hold. A read
failure is the step's `:seon.batch/read-error` (an error value), handled where the caller handles today's
`:seon.sci.reader/error`.

## Proof
`seon.sci.reader-test/stored-source-reads-as-it-was-evaluated` (new; ledger name): for a stored definition using an
alias and `#?(:clj …)`, each converted caller's read of its `:seon.fn/source` yields the same form SCI evaluated
(`=` on the forms). Plus each caller's existing tests green.

## Commit
`bin/lane commit <lane> -m "RP-17a: every reader caller reads with SCI at a cursor" --test seon.sci.reader-test --test seon.turn-test --test seon.sci.eval-test --test seon.render.ns-test --test seon.render.value-test --test seon.cluster.agent-test src/seon/turn.clj src/seon/sci/eval.clj src/seon/cluster/agent.clj src/seon/render/ns.clj src/seon/render/value.clj test/seon/sci/reader_test.clj`
(Split per file group if the claims are held by different lanes: `render/*` is group A.)

## Dependencies
RP-11c, RP-6a. Reset: none.
