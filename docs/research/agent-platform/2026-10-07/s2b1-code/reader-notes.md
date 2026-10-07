---
title: S2b-1 landing — the reader rules in the one stepper
type: landing
status: landed
lane: s2b1-code
date: 2026-10-08
design: ../s2b1-reader/design-notes.md (v3, amended in this commit for the 2026-10-08 same-line ruling)
---

# S2b-1 landing notes

`seon.sci.forgive/step` is now the one stepper every reader entrant shares (structural `segments`, the live
row read, `seon.sci.batch/read-next`, and the JVM read through `seon.sci.forgive/failure-step`). The read
itself is a value (`:seon.batch/read-stage`): SCI `parse-next` with the agent's options for the live read,
edamame with options normalized once and token spans without reads for the structural pass. Parinfer, the
repairs family, `:seon.batch/refused`, the unread-from step, `seon.sci.batch/read-code` and `forms` are gone.

Path served: READ (text in, forms out). Owner reused: the existing stepper, edamame's failure containers, SCI's
reader. Deleted: Parinfer and the parinferish dependency (deps.edn edit left uncommitted for root), the trials,
the repair edits and their schema family, the refused carrier, the unread-from step variant.

## The owner ruling of 2026-10-08

Once a line starts a form, every form on that line runs. Implemented by joining the `code-end` clause to the
code-fence clause of `reply-step` (the rest of the line is read like a fence line: `;` skips to the line's end).
No-value constructs set the same `code-end`, so the rest of their line is code too, and the next line is
classified. Spec §4.3 and the design notes are amended in place. Consequences, shown as table rows in
`seon.sci.forgive-test/the-reader-reads-a-reply-by-its-line-rules`:

- `(+ 1 2) Done.` evaluates `Done.` (an unresolved symbol error), as at a REPL.
- `#1 priority is (launch!)`: `#1 priority` is a read error, and then `is` and `(launch!)` run. Before the
  ruling the rest of that line was prose. Root may want to weigh this one.
- `[] [] [999]` runs all three (repl-parity C7 keeps its expectation).
- On the recorded corpus (live 01-11, loop 1-2, run4, fences) the reversal changes no source.

## Design check

- The design held. The v3 prototype was re-earned line by line; the differences:
  - `version` is gone. A version is `{text pos}` plus region keys, and `reader-at` builds a region's reader
    lazily over `(subs text pos bound)`, so `:seon.batch/chars` never became a key. A region's reader is built
    once, so the copies are disjoint.
  - `next-form` takes the stage, not a ctx; `seon.sci.batch/read-stage` builds the live one.
  - `read-row` reads its row with `next-form` and keeps only its own carried, oversize and empty-row refusals.
  - JVM continuation is `seon.sci.forgive/failure-step` (structural `code-step` at the failure, then
    `seon.sci.reader/reader-error` of that span).
  - A failed read's span is never short of where the reader stopped. A spec-literal "to the closer" span
    dropped the reader and re-read prose in `:code` (live 06 split differently from v3); with the max, every
    source matches v3 on the corpus and readers are never rebuilt.
- Awkward:
  - The error render of `:seon.sci.reader/fabricated-response-error` shows only `:message` and `:offending`, so
    a test cannot read `:seon.error/type` from stored shown text. The regressions dig it by the result handle,
    as an agent would: `(:seon.error/type result/e…)`.
  - The reader rules make `##NaN` at a line start prose (Q1: `#` before `#` is a heading). The value zoo wraps it
    as `(identity ##NaN)`, as it already wrapped bare symbols.

## Measurements

Throwaway JVMs on the checkout classpath, platform thread, 190 extra frames deep, median of 5 after 3 warm
runs. Parent = `git archive` of HEAD 131082e0d (its own resources for arming); change = the working tree.
Armed = `seon.instrument/apply!` over every contracted var of `seon.sci.forgive` with the packaged projection.
Script: scratchpad `s2b1-code/bench2.clj`.

| Input | chars | mode | parent unarmed | change unarmed | parent armed | change armed |
|---|---|---|---|---|---|---|
| rr ×1 | 9,557 | reply | 7.9 ms, 6.61 MB | 1.7 ms, 0.61 MB | 9.9 ms, 7.75 MB, 2,119 calls | 3.1 ms, 1.40 MB, 2,058 calls |
| rr ×3 | 28,671 | reply | 15.3 ms, 19.64 MB | 2.7 ms, 1.81 MB | 20.1 ms, 23.02 MB, 6,307 calls | **10.5 ms, 4.09 MB**, 6,408 calls |
| rr ×8 | 76,456 | reply | 36.7 ms, 52.01 MB | 4.0 ms, 4.79 MB | 54.8 ms, 60.99 MB, 16,777 calls | 9.6 ms, 10.90 MB, 17,283 calls |
| rr ×3 | 28,671 | code | 1.0 ms, 2.33 MB (stops) | 2.6 ms, 0.90 MB | 1.6 ms, 3.03 MB | 3.3 ms, 1.70 MB |
| prose ×1,000 | 56,000 | code | 0.5 ms, 0.51 MB (stops at once) | **8.1 ms, 11.71 MB**, 10,000 sources | 0.5 ms | 26.6 ms, 42.36 MB, 96,999 calls |
| prose ×2,000 | 112,000 | code | 0.7 ms, 0.86 MB (stops) | 9.9 ms, 23.14 MB, 20,000 sources | 1.0 ms | 48.9 ms, 84.54 MB, 193,999 calls |
| prose ×1,000 | 56,000 | reply | 13.3 ms, 15.06 MB | 0.17 ms, 0.26 MB | 16.5 ms, 17.16 MB | 0.8 ms, 2.09 MB |
| stray `)` ×1,000 | 9,011 | reply | 45.1 ms, 84.15 MB | 2.0 ms, 4.21 MB | 65.0 ms, 94.58 MB | 6.9 ms, 10.76 MB |
| run4 7b254915ca94 | 12,176 | reply | 3.0 ms, 3.32 MB | 2.5 ms, 0.87 MB | 3.7 ms, 3.64 MB | 1.8 ms, 1.23 MB |

- Budgets: 28.7 KB reply armed 4.09 MB (under 5 MB); 56 KB prose through `:code` 11.7 MB unarmed (about 13);
  linear (×2,000 is twice ×1,000; rr ×1/×3/×8 grow with length).
- The `:code` prose rows are not like-for-like: the parent stopped reading at the first prose word.

S0 probe rows (`seon.pass-probe/run-mcp` sci 3 with a defn, jvm 3, `run-model` 2), each in a throwaway check
JVM, median of three runs per side:

| Row | parent | change |
|---|---|---|
| MCP SCI ordinary | 30.0 ms, 23.0 MB, 7,285 calls | 27.9 ms, 23.1 MB, 7,138 calls |
| MCP SCI defn | 346 ms, 738 MB, 87,256 calls | 345 ms, 739 MB, 87,247 calls |
| MCP SCI after defn | 58.2 ms, 75.4 MB | 60.9 ms, 75.8 MB |
| MCP SCI ordinary ×2 | 25.3 / 26.0 ms, 24.2 MB | 27.4 / 26.0 ms, 24.3 MB |
| MCP JVM ordinary ×3 | 20.7 / 186.5 / 14.6 ms; 15.7 / 449.9 / 14.3 MB | 19.3 / 184.3 / 14.4 ms; 17.7 / 449.7 / 14.4 MB |
| model ×2 | 570 / 331 ms, 945 / 490 MB | 579 / 338 ms, 934 / 494 MB |

Neutral within noise. The pass costs themselves (738 MB for a defn pass, 945 MB for a model pass, 450 MB for the
second JVM pass) are over every budget and pre-existing: task `seon.turn/each-form-of-a-pass-costs-megabytes`.
The read writes nothing to the store.

## Tests

Green on the candidate (bin/seon check, HEAD plus the slice):

- `seon.sci.forgive-test`, `seon.sci.reader-test`, `seon.run4-reader-test`, `seon.ai-test`, `seon.error-test`,
  `seon.render.web-debug-test`, `seon.cluster.host-test`, `seon.cluster.prompt-test`,
  `seon.render-simplification-test`;
- `seon.run-test`, every var except the pre-existing red D8;
- `seon.cluster.evaluate-source-test/a-failed-read-is-its-forms-error-and-reading-continues-in-sci-and-jvm`
  (new; tasks `seon.batch/an-error-recorded-while-showing-a-form-rides-the-next-form` and
  `seon.turn.batch/a-jvm-read-error-drops-the-form-and-every-later-form`);
- `seon.turn-test/a-refused-response-map-settles-as-an-unrun-evaluation`,
  `seon.turn-test/a-reply-keeps-its-dispatch-macro-forms-as-code`,
  `seon.turn-test/evaluation-ai-is-only-the-repl-session` (printer-fix patch);
- `seon.loop-proof-test/virtual-loop-end-to-end` (help-trial block converted);
- `seon.value-zoo-test/every-zoo-value-comes-back-through-both-entrants-and-the-page`.

Pre-existing reds, identical on HEAD (same vars, same assertions):

- `seon.run-test/d8-a-test-obtains-a-forms-declaration-row-from-the-entrys-change` (marked RED today in source);
- `seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent` (defn shown as the Var
  render map; refused-settlement blocks), plus a random red: task
  `seon.test-support/task-row-mints-an-unreadable-symbol-for-a-digit-leading-handle` (created);
- `seon.turn-test`: `a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`,
  `a-form-whose-facts-are-refused-says-it-was-not-admitted`,
  `settlement-keeps-unresolved-call-and-require-names-as-values`;
- `seon.cluster.turn-test`: `acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles`,
  `reasoning-starvation-persists-usage-finish-and-the-named-error`,
  `runtime-schema-key-changes-pass-the-one-usage-guarded-decision`,
  `runtime-schema-registration-commits-the-evaluated-form-and-attribute`, `streaming-writes-zero-datoms-test`,
  `two-drivers-of-one-opening-both-answer-and-it-settles-once`;
- `seon.fn-test`: `fixture-observations-survive-static-admission-and-the-fold`,
  `the-indexer-emits-no-attribute-the-program-entity-schema-drops`;
- `seon.cluster.agent-test`: `a-function-without-a-contract-is-admitted-and-stored`,
  `disarm-interrupts-an-in-flight-turn-and-records-it-interrupted`, `hot-reload-var-test`,
  `n-agent-parallel-turns-property`;
- `seon.cluster.evaluate-sources-test/ordered-evaluation-retains-one-explicit-basis-without-publication`,
  `seon.loop-proof-test` (its other assertions at :49-52, :490, :618), `seon.render.transcript-test/every-generated-history-is-ordered-and-total`
  (9.7 s over its 5 s bound), three `seon.sci.render-source-test` vars.

Not runnable: `seon.repl-parity-test` (C7). The runner refuses the namespace: task
`seon.test/a-namespace-of-defparity-tests-has-no-eligible-tests` (created). C7's input is a row of the forgive
table instead.

Deleted test of deleted machinery: `seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent`.

## Findings

- Default was `:faulted` at the start (3 open faults, 17 errored receipts); pre-existing.
- `docs/seon/issues/a-trailing-prose-word-after-a-fence-becomes-an-evaluation.md` describes behavior this slice
  fixes (`Done.` after a fence is now prose); the issue index is root's.
- The three tasks this slice proves were runtime-only and lost with the store; they are now EDN records:
  `seon.sci.forgive/discard-consumes-prose-and-fence-boundaries`,
  `seon.batch/an-error-recorded-while-showing-a-form-rides-the-next-form`,
  `seon.turn.batch/a-jvm-read-error-drops-the-form-and-every-later-form`.
- Machinery deleted for tasks `seon.sci.forgive/a-prose-repair-replays-the-whole-reply`,
  `seon.sci.forgive/structural-trials-refuse-var-quote-anonymous-fn-deref-regex-and-syntax-quote` and
  `seon.sci.forgive/a-prompt-marker-is-stripped-and-its-namespace-ignored` (a marker is now prose outside fences
  and an unresolved symbol inside them). Still open: `seon.sci.forgive/prose-after-the-last-form-is-dropped`
  (S4).
