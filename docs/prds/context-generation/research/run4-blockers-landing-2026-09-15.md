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
which originally ran after evaluation had interned and rendered the Var.
That caller was initially excluded; the owner authorized its narrow change
in the continuation below. The backstop and other excluded owners remain
untouched.

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

## Commits and scope

- `4a559d658`: part 3, reader diagnostics and exact captured evidence.
- `ae2c180a4`: part 2, stop default, attempt persistence and replay.
- Part 1 was initially deferred at the explicitly excluded `src/seon/turn.clj`.
  The owner subsequently authorized the narrow caller change; the continuation
  below records that implementation. No backstop changes were made.

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

## Authorized part 1 continuation

The owner authorized the narrow `turn.clj` install seam. The file was read
immediately before patching; unrelated work was preserved.

`evaluate-for-install` hands the parsed event to the ordinary evaluator and
uses the existing candidate fork for function declarations. The existing
gate now decides each function before the next form executes. It reuses the
candidate evaluation, checks the analyzed function, and transfers only an
accepted function root through the same transfer helper used after commit.
Refusal discards candidate bindings and runs the error value through the
existing shown-result projection before the result handle is bound.

The error has the install-refused kind, generated arguments, expected and
actual evidence, and `Fix the contract or the function and re-evaluate the
defn.` as its message. The result retains complete diagnostic data; the AI
render displays a nested contract error by kind and message instead of its
large instrumentation map. Associated optional map entries were declared in
the existing evaluation and candidate/error schemas; no durable entity or
new gate was introduced.

The real graph regression uses captured definition `faef54087471`, then
`(dir my.agents.juniper)` in the same reply. It verifies one error, no
function row, no callable SCI Var, a real directory value without that
function, successful subsequent installation, and preservation of its root
and source after a refused replacement. Initial regression lookup/arity
mistakes were corrected using the actual turn ref and `dir` signature.
Focused fast verification passed 11 tests / 55 assertions.

The combined fast gate at `f9ed564ef` ran 16 tests / 430 assertions. Two
failures came from the since-corrected `dir` test invocation; the other six
are the committed continuation test's retired no-form message expectations,
documented in `docs/seon/issues/archive/turn-continuation-test-expects-retired-no-form-text.md`.
The loop proof and remaining accretion checks passed. The first isolated gate
confirmed only those six stale expectation failures (16 tests / 434 assertions).
After the owning correction `e7bf60541`, the final isolated gate at
`dabd311d0` passed 16 tests / 434 assertions, zero failures and errors.
The final platform gate passed 84 tests / 505 assertions, zero failures and
errors. The final fast gate passed 16 tests / 430 assertions, zero failures
and errors. All lane-launched shells ended; the failed isolated root was
removed after checking holders and verifying the owning expectation fix.
Successful roots were removed by the runner, and lane logs were deleted
after their results were recorded here.

The part 1 gates select only these changed production/test paths:
`src/seon/sci/eval.clj`, `src/seon/test/accretion.clj`, `src/seon/turn.clj`,
`resources/seon/schemas/seon.sci.eval.edn`,
`resources/seon/schemas/seon.test.accretion.edn`, and
`test/seon/run4_install_test.clj`. Explicit namespaces are
`seon.run4-install-test seon.test.accretion-test seon.loop-proof-test seon.turn-continue-test`;
the final platform gate uses the same paths with `--platform`.

### Live JVM verification

The hot-reloaded default JVM was probed through `turn/evaluate-sources`,
using its current database and a disposable candidate context. This did not
write a turn, alter Juniper's retained context, or call a provider; durable
turn/row/result-handle assertions belong to the real graph regression above.
An initial `user` namespace probe refused because no such program namespace
row existed; the verified form uses the existing Juniper namespace.

The final result was `:kind :seon.test.accretion/install-refused`,
`:arguments [[]]`, `:expected "[:map [:customer :string] [:total :int]]"`,
`:actual-kind :seon.instrument/contract-violated`, `:errors 1`,
`:callable? false`, `:directory-returned? true`, `:listed? false`.
The actual field honestly retains the output-contract refusal returned by
the instrumented invocation. It does not claim a recovered raw nil value.
Exact reproducer:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
      connection (seon.operator/connection "default")
      database @connection
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [ctx (seon.sci.eval/fork-candidate-ctx
                {:seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
                 :seon.db/db database :seon.db/connection connection
                 :seon.agent/id "juniper"})
           source (some (fn [[id text]] (when (= id "faef54087471") text))
                        (:run4/evaluations
                         (clojure.edn/read-string (slurp "test/seon/run4_replies.edn"))))
           items (seon.turn/evaluate-sources
                  {:seon.turn.loop/cluster (:seon.turn.loop/cluster instance)
                   :seon.db/db database :seon.sci.eval/ctx ctx
                   :seon.agent/id "juniper" :seon.cluster.eval/ordinal 0
                   :seon.ns/name 'my.agents.juniper
                   :seon.cluster.reply/sources
                   [{:seon.cluster.eval/source source :seon.ns/name 'my.agents.juniper}
                    {:seon.cluster.eval/source "(dir my.agents.juniper)"
                     :seon.ns/name 'my.agents.juniper}]})
           outcomes (mapv :seon.sci.eval/evaluation items)
           value (:seon.sci.admit/value (first outcomes))
           directory (:seon.sci.admit/value (second outcomes))]
       {:kind (:seon.error/kind value)
        :arguments (:seon.test.accretion/arguments value)
        :expected (:seon.test.accretion/expected value)
        :actual-kind (:seon.error/kind (:seon.test.accretion/actual value))
        :shown (:seon.eval/shown (first outcomes))
        :errors (count (filter :seon.cluster.eval/error outcomes))
        :callable? (boolean (sci.core/resolve ctx 'my.agents.juniper/largest-customer))
        :directory-returned? (vector? (:functions directory))
        :listed? (boolean (some #(= 'my.agents.juniper/largest-customer (:sym %))
                               (:functions directory)))}))))
```
