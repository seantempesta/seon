---
title: "Card RP-11c: Delete the old reading list"
type: reference
status: active
created: 2026-10-08
---

# RP-11c Delete the old reading list

Ledger row RP-11c (group B). Design: `redo/pass-and-recording.md` §5 "Reading". Lane: Sonnet. Cited at HEAD
`e7f120672`.

## Delete (each with its last caller; `rg -n '<name>' src test` must answer nothing after)
- `seon.sci.forgive/segments` (`src/seon/sci/forgive.clj:440`) and its private helpers `owned` (`:426`),
  `comment-of` (`:413`), `no-value?` (`:403`), `conditional-options` (`:395-401`), and the `edamame` require if unused.
- `seon.turn/segmented-sources` (`src/seon/turn.clj:3017`), `planned-sources` (`:3044`), `code-sources` (`:3061`),
  `repair-data` (`:3008`) if no caller is left, `read-row` (`:4729`), `turn-rows-state` (`:4722`),
  `fold-evaluations` (`:4278`), `fold-source` (`:4301`), `repair-text` (redesign §1: `turn.clj:3019` at its HEAD; find
  it with `rg -n 'defn- repair-text' src`).
- The generated-opening and preview callers of `code-sources` (`preview-sources` `:5061`, `generate-turn` `:5348`):
  `preview-sources` reads at the cursor as RP-11a1 does; the generated opening is deleted by S4-02b, so if S4-02b has
  not landed, this row waits for it (the opening is its only remaining caller).
- Schemas: `:seon.cluster.reply/sources` and `:seon.turn/sources` members where no producer remains
  (`rg -n 'seon.turn/sources|seon.cluster.reply/sources' resources src`).

## Proof
D: zero calls on the deleted path; the reader-fix rows of `seon.repl-differential-reader-test` and the P6/P7 rows stay
green.

## Commit
`bin/lane commit <lane> -m "RP-11c: the pre-segmenting reader is deleted" --test seon.repl-differential-reader-test --test seon.repl-differential-p6-test --test seon.repl-differential-p7-test --test seon.sci.forgive-test --test seon.turn-test src/seon/sci/forgive.clj src/seon/turn.clj resources/seon/schemas/seon.turn.edn resources/seon/schemas/seon.cluster.reply.edn test/seon/sci/forgive_test.clj`
(add the test files whose deftests exercised `segments` directly; convert each to the cursor or delete it with its
machinery.)

## Cost
Deletion; source lines down (redesign §5 estimate ~1,100 out across the RP rows).

## Dependencies
RP-11b; S4-02b for the generated opening. Group B.

## Reset needs
None.
