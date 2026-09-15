---
type: research
status: active
tags: [sci, ai, test]
date: 2026-09-15
---

# Run 4 blockers

Read AGENTS.md, the three assigned issues, the model's complete `:text`
account, and the turn-loop PRD end to end, including §§18b and 18d.

## Dependency ledger

- SCI `fork` provides copy-on-write Vars:
  `reference-code/sci/src/sci/core.cljc:345`; the existing candidate owner is
  `src/seon/sci/eval.clj`, `evaluate-candidate`.
- Edamame reports delimiter and cursor facts:
  `reference-code/edamame/src/edamame/impl/parser.cljc:755`; the reader's
  `parse-classification` consumes those facts. Error messages now name the
  complete reply line and explain per-reply reading.
- AI stop already has a vector schema, per-agent metadata and wire mapping
  in `resources/seon/schemas/seon.config.ai.edn`. `seon.ai/wire-settings`
  derives the provider field; `seon.turn/record-attempt!` records effective
  settings. No second field mapping is needed.
- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)
  documents string/array stop sequences (checked 2026-09-15). Firecrawl CLI
  was unauthenticated; official documentation was read through web browsing.

## Live evidence and boundaries

Inherited default pid 23729 was alive. JVM MCP `(+ 1 1)` returned 2.
Runtime status failed at `RT/dissoc` with a MapEntry ClassCastException;
recorded in `docs/seon/issues/runtime-status-throws-on-a-map-entry.md`.
One broad capture timed out after 20 seconds; separate source/reply queries
completed, and selected exact strings are in `test/seon/run4_replies.edn`.
Reply blobs were read through `seon.blob/get`, not reconstructed from forms.

The retained live defn `faef54087471` has a sequential tuple input contract,
whereas the issue's abbreviated example names a vector of order maps.
The captured source is the regression authority for that live evaluation.

Part 1's install decision is owned by `seon.turn/gate-function-install`,
after evaluation has already interned and rendered the Var. A narrow caller
change is awaiting the owner's response because turn.clj is explicitly
excluded. The backstop owner and all other excluded paths remain untouched.

## Verification

### Part 3 — reader errors

- Fast: 51 tests / 416 assertions; 12 failures, zero errors, all in
  `seon.help-trial-test/trial-scores-actual-forms-and-fails-on-absence`.
- Isolated: 51 tests / 420 assertions; the same 12 failures, zero errors,
  confirmed in a fresh worker. Reader, reply, and REPL grammar passed.
- The isolated gate used HEAD `6785c980c` plus only the owned reader paths.
  Exact foreign boundary: `seon.db/schema-database` receives `[nil]` from
  scoring, recorded in `docs/seon/issues/help-trial-score-queries-a-nil-schema-database.md`.
- The broader diagnostic SCI namespace run had 118 tests / 717 assertions,
  59 failures and 11 errors, including the already filed retired-storage
  expectations. It is unchanged by this lane.
- Live JVM observation after development hot reload returned:
  `Unmatched delimiter: ) in reply text "). stray delimiter". The reader reads each reply from scratch; nothing is buffered between turns.`
  and `Your reply had no form; only comments/prose. Send a form.`
  The following `(+ 1 1)` read yielded the ordinary form.

No default restart, refork, or reseed performed. Final platform gate pending.
