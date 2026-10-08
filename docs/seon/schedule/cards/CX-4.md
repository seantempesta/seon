---
title: "Card CX-4 The transcript shows forms, not a prompt the model copies"
type: reference
status: active (Q-CX1 default applies unless the owner picks otherwise)
created: 2026-10-08
---

# CX-4 The transcript shows forms, not a prompt the model copies

Ledger row CX-4 (group A). Spec: agent-loop.md §3.4 "Presentations: REPL is the form as sent, then its envelope" (no
prompt). Lane: Sonnet.

## Evidence

`seon.repl/text` writes every history evaluation as `<ns>=> <source>` then its envelope. Models copy it: the first
live pass (agent `live-flash`) was cut by the old `"user=>"` stop (fixed `2dbb3face`: the stop is now
`"#:seon.repl{"`). But a reply that copies the prompt is still read as code: probed 2026-10-08 at an SCI agent's REPL,
`user=> (+ 1 2)` answers `Unable to resolve symbol: user=>` beside `3`, one error per copied form.

## What becomes true (Q-CX1 option 1, recommended)

`seon.repl/input-text` no longer prints `<ns>=> `; the evaluation's namespace shows where it changed (the envelope's
`:ns`, as today). The model never sees a prompt to copy. The stop marker test of `2dbb3face`
(`seon.repl-test/the-model-stop-sequences-never-cut-a-form-the-transcript-teaches`) keeps passing.

## Functions

- CHANGE `seon.repl/input-text`: drop `(or prompt-ns 'user) "=> "`. Contract unchanged.

## Deleted

Three lines. Net ≈ −3 source, +15 test.

## Proof

`seon.repl-test/the-transcript-teaches-no-prompt` (new): `(seon.repl/text {:seon.cluster.eval/source "(f)"
:seon.ns/name 'user :seon.eval/shown "3"})`'s first line `=` `"(f)"`. Golden transcripts that print a prompt are
converted.

## Cost

D.

## Dependencies and order

After CX-1 (history is built by `seon.repl/text`). Files: `src/seon/repl.clj`, `test/seon/repl_test.clj`, the golden
transcripts the lane finds (`rg -l '=> ' test/`).
