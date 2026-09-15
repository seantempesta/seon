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
  scoring, recorded in `docs/seon/issues/archive/help-trial-score-queries-a-nil-schema-database.md`.
- The broader diagnostic SCI namespace run had 118 tests / 717 assertions,
  59 failures and 11 errors, including the already filed retired-storage
  expectations. It is unchanged by this lane.
- Live JVM observation after development hot reload returned:
  `Unmatched delimiter: ) in reply text "). stray delimiter". The reader reads each reply from scratch; nothing is buffered between turns.`
  and `Your reply had no form; only comments/prose. Send a form.`
  The following `(+ 1 1)` read yielded the ordinary form.

### Part 2 — provider stop

The existing schema, per-agent projection, wire mapping and attempt settings
storage already support stop. The production change enables the default in
`config/default.edn`; no second mapping or attempt attribute was added.

The first passing fast gate ran 61 tests / 303 assertions, zero failures
and errors. It includes real attempt persistence for the default and an
agent override. Seven captured replies yield 13 evaluated forms and zero
fabricated-response errors when the provider stop is applied. The regression
also asserts that every capture retains forms and every evaluation returns
a value, so missing evidence cannot pass.

The formerly failing help-trial score test passed after the independent
database fix `eef44fcc3`. The strengthened combined fast gate passed 106
tests / 699 assertions. The first isolated AI/grammar/help gate passed 61
tests / 307 assertions. The final combined isolated gate at `d2af195bf`
passed 106 tests / 704 assertions, including every reader regression and
the strengthened replay. The final platform gate at `276c4aeea` passed
84 tests / 505 assertions, zero failures and errors.

Source adoption twice reported `Source changed during development adoption;
the next edit must converge it.` This is a source-publication observation,
not the cause of the missing live stop setting: development adoption and
configuration application are separate operations. A live comparison of
`config/compile-manifest` with `config/effective` found exactly one differing
effective key, `:seon.config.ai/stop`. The lane then invoked the ordinary
`bin/seon config apply default config/default.edn` operation.

The application completed with five reconciliation operations. The exact
live read-back below returned
`{:seon.config.ai/stop ["#:seon.repl"], :provider-stop ["#:seon.repl"]}`.
It constructs the body without calling the provider.

```clojure
(let [database @(seon.operator/connection "default")
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [effective (seon.config/effective database "default")
           target (:seon.ai/primary
                   (seon.ai/targets (seon.ai/settings effective {})))
           body (seon.ai/request-body
                 (assoc target :seon.ai/prompt "(+ 1 1)"))]
       {:seon.config.ai/stop (:seon.config.ai/stop effective)
        :provider-stop (get body "stop")}))))
```

## Commits and remaining assignment

- `4a559d658`: part 3, reader diagnostics and exact captured evidence.
- `ae2c180a4`: part 2, stop default, attempt persistence and replay.
- Part 1 remains unimplemented because its existing decision owner is in
  the explicitly excluded `src/seon/turn.clj`. The issue records the exact
  seam and the candidate-context option; the owner must assign that narrow
  caller change before this lane can finish it. No backstop changes were made.

No default restart, refork, or reseed performed.
All lane-launched commands ended. Successful gate roots were removed by
the runner; the earlier failed reader root was deleted only after checking
that no test runner held it and its failure had been re-observed and cleared.
Lane scratch logs were deleted after recording the results here. No scratch
cluster or worktree was created.

## Reproduction

The final gates used HEAD plus only the remaining uncommitted stop paths;
the reader implementation was already committed in HEAD.

```sh
bin/test-fast --paths config/default.edn test/seon/ai_test.clj -- seon.ai-test seon.run4-reader-test seon.sci.reader-test seon.cluster.reply-test seon.repl-grammar-test seon.help-trial-test
bin/test --paths config/default.edn test/seon/ai_test.clj -- seon.ai-test seon.run4-reader-test seon.sci.reader-test seon.cluster.reply-test seon.repl-grammar-test seon.help-trial-test
bin/test --platform --paths config/default.edn test/seon/ai_test.clj
```
