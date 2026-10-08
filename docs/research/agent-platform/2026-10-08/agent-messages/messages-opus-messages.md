---
title: Agent-facing messages audit
type: research
status: proposed (audit and slices; no code)
date: 2026-10-08
lane: messages-opus
---

> **Slices moved 2026-10-08.** Part 4's slices S1-S12 are rows `MS-` of [the agent-loop ledger](../../../../seon/schedule/agent-loop-ledger.md) §3.4, deduplicated (S2 → PR-02, S5 → P10, S6 → H-01, S9 → S1-06/S1-07a, S10 → PR-07; S1 and the same-pass part of S11 wait on `redo/pass-and-recording.md`; S7 keeps the test value and moves the summary into its render). The census and the rules stay as evidence.

# Agent-facing messages: census, rules, rewrites, slices (lane messages-opus)

Owner, 2026-10-08: every message Seon shows an AI agent should be optimal Clojure, short, and in the
vocabulary and tools agents are already trained on. This audit captured real replies through the MCP
entrance (`eval_clj`, SCI mode, agent `messages-opus` on its own branch `:agent-adb5e17ff2a1`; one JVM-mode
probe), measured each in estimated tokens (`seon.ai.tokens/estimate`: characters / 3.2, the shipped
calibration), and traced each to its producer. HEAD 76daa8fd5, default pid 72173.

Printer coordination: this audit follows `printer-rules/printer-rules-opus-printer-rules.md` (commit
032bc120a), rules R1-R14. Errors print through the value printer as their one flat map (R3). Messages are fixed
text, with values in data keys (R12). Elision forms are plain clojure.core on the live result symbol, with no
Seon helper (R6). The printer alone budgets and cuts (R1). Nothing here adds a printer, clipper or second
budget; the rewrites change what each producer hands to the printer. This audit adds one constraint to R6: a
cut is not emitted when its elision form prints longer than the member it hides (rule 7 below). Slices that
are pure printer work are left to the printer-rules slices and only referenced here.

## Broken things first

These were found while capturing the census. Each is recorded as a task (see "Tasks recorded").

1. **A refused definition's diagnostic prints its whole write attempt.** The defn below costs about 980
   tokens:

   ```clojure
   (defn h {:malli/schema [:=> [:cat :int] [:maybe :int]]} [x] x)
   ```

   The reply includes the request id, the producer digest, the basis (store, branch, commit, t), three host
   stack frames and the entire refused transaction. The rule the agent broke appears only in the message,
   and the printer cut that message mid-sentence (`"...an empty collection"…+27`). Cause:
   `seon.turn/with-diagnostic` prints the error whole with `render.value/core-text [error]` instead of
   projecting it with `seon.error/reply-value`, which is the projection evaluation errors use
   (`seon.sci.eval/shown-failure`). `seon.turn/not-admitted` also puts a fixed 40-token sentence in front of
   the producer's message.
2. **Dig forms cost more than the content they cut.** `(zipmap (range 2000) (map #(hash-map :id % ...) ...))`
   showed 1,388 tokens. About 810 of those were 29 copies of
   `…1 (seon.print/value-at result/e0306bdea19a8 '[[:seon.render.data/entry 3 :seon.render.data/val]])`,
   about 28 tokens each. Each one hid a single `:tags #{:a :b}` member, about 4 tokens. Cause:
   `seon.print/continuation` addresses a map member by entry index (a typed step, so it falls back to
   `seon.print/value-at` with an internal path). It does this even when the key is a number that `get-in`
   could take directly (`(get-in result/e… [558 :tags])`). Nothing stops a cut whose dig form is longer than
   what it cuts.
3. **An arity error names SCI's closure, not the function.** `(defn f [a] a) (f 1 2)` answers
   `"Wrong number of args (2) passed to: sci.impl.fns/fun/arity-1--73805"`. Clojure says `user/f`, and the
   reply's own stacktrace already starts with `[user/f 1 1]`.
4. **A JVM compile error leads with the wrapper's text.** JVM mode `(undefined-jvm-sym)` answers
   `:message "Syntax error compiling at (1:1)."` twice, with the actual cause last in `:chain`. Clojure's own
   `clojure.main/err->msg` (`reference-code/clojure/src/clj/clojure/main.clj:342`, rev 3bc2b3e9) gives the
   REPL's two lines: phase and location, then `Unable to resolve symbol: undefined-jvm-sym in this context`.
5. **`(help)` sets off its own `:large-value` finding.** Its 1,105 tokens exceed the 1,000-token bound, so the
   agent is told "a query or function returns more than it should: select what you need" about the platform's
   own instructions. Every model agent's opening evaluates `(help)`.
6. **Ending a session is taught three different ways.** `help-value` says `(my.agent/done)` must be the last
   form, but `my.agent/done` takes a request map, and its doc example is `(my.agent/done {})`.
   `seon.cluster.agent/render-situation-ai` says every run ends with `my.turn/complete` or `my.turn/wait`.
7. **`my.test/check` in the same pass as the `deftest` says the test does not exist.**

   ```clojure
   (deftest inc3-fails ...)
   (my.test/check {:seon.test/changed ['scratch.msgs/inc3-fails]})
   ```

   This answers `"Changed identity has no definition, history or incoming edge."`, with
   `:expected :complete-comparable-program-evidence`. The cause is the documented one-settlement-per-pass
   rule: the deftest's facts land when the pass settles. The same call in the next pass runs the test. The
   message names graph internals instead of saying "defined in this pass; check it in the next one".
8. **The `:slow` fix answers a different question.** `(seon.profile/summary (seon.profile/cells) 5)` ranks the
   whole process's cumulative profile (`seon.cluster.boot/request! ... total 31751 ms`), not the slow
   evaluation. It returns the same five prose lines three times. On a slow evaluation, the reply's
   `:repeated` list adds about 446 tokens of Seon-internal function names that the agent cannot act on.
   Related to `seon.repl/a-slow-reply-names-nothing`.
9. **An unresolved symbol shows an empty neighbour list.** `:seon.error/in-namespace {user []}` appears on
   every unresolved-symbol error that has no near names.
10. **A delimiter repair is silent.** `(let [x (+ 1 2] x)` evaluated to `3`, with no note and the original text
    not stored (the source holds the repaired text). Commit c98ade404 says delivery of the repair beside the
    value is P7, so this is a pending step, not a new defect. It is listed because the agent cannot learn
    from a repair it never sees.

Platform cost observed in passing: `(my.test/check ...)` for one test took 1,166 ms and 86,375 armed calls
(`seon.schema/projection-cache-value` alone 20,477 calls). A warm repeat took 404 ms and 56,634 calls. This
is the class `seon.repl/loading-source-arms-tens-of-thousands-of-calls` / `hot-internals-pay-armed-validation`.

## Part 1: census

The 475 evaluations on this branch's store (forked from default) give frequency evidence:

| Measure | Count |
|---|---|
| Errors | 46 (9.7%) |
| Slow, over 1 s | 31 (6.5%) |
| Value over 1,000 tokens | 11 (2.3%) |
| Printed output | 8 |
| Definitions | 3 |
| Not-admitted diagnostics | 2 |
| Shown tokens in total | 21,687 |

Shown tokens by leading form:

| Leading form | Evaluations | Tokens |
|---|---|---|
| `seon.db/q` | 23 | 5,666 |
| `help` | 2 | 2,217 |
| `my.test/check` | 6 | 2,166 |
| `doc` | 6 | 2,021 |
| `dir` | 1 | 425 |

Each producer below is listed with its source and a real captured example. "Before" is the whole MCP reply
text. Token counts are characters / 3.2.

| # | Producer (owner) | Captured example (abridged) | Before (tokens) |
|---|---|---|---|
| P1 | Opening and `(help)` (`seon.bootstrap/help-value`, `render-help-ai`) | 14 prose lines plus the reply's own `:large-value` finding | 1,180 |
| P2 | Not-admitted diagnostic (`seon.turn/not-admitted`, `with-diagnostic`) | `:diagnostics [{...request-id, producer-digest, basis, chain with frames, the whole transaction...}]` | 979 |
| P3 | Large value with map cuts (`seon.print/continuation`) | 29 times `…1 (seon.print/value-at result/e… '[[:seon.render.data/entry N :seon.render.data/val]])` | 1,460 |
| P4 | Large output (`seon.repl/shown-output`, `:large-output` finding) | 153 lines, then `…+90226 (subs (:seon.cluster.eval/output (seon.db/pull '[:seon.cluster.eval/output] [:seon.cluster.eval/id "71358f44cc5a"])) 4664)` | 1,560 |
| P5 | `:slow` finding plus `:repeated` (`seon.config.eval` bounds, `seon.repl/response-entries`) | 60-token fix prose, plus 33 `[seon.schema.datahike/... n ms]` rows | 566 |
| P6 | Test results (`my.test/run`, `my.test/check`) | The failure appears three times (`:out`, `:failure-message`, `:reports`), with 5 SHA-256 digests, tx refs and timings | 470 (run); 1,240 (check, slow) |
| P7 | `runtime_status` (`seon.cluster.status`, `seon.health/report`) | Health top-5 with clues, counts, units, store error map with `:at`/`:layer` | 1,050 |
| P8 | `def` note (`seon.repl/def-note`) | `:note "x lives only in your SCI context and is lost when the JVM restarts. ..."` | 117 (note alone 93) |
| P9 | `doc` (`seon.bootstrap/doc`) | `{:supplied ... :summary ... :body "...## Costs\n\nOne transaction at the task home (measured in the landing note)." :in ... :out ...}` | 253 |
| P10 | `dir` (`seon.bootstrap/dir`) | Six maps, each with `:summary` prose and arglists showing supplied world keys `{connection :seon.db/connection, agent-id :seon.agent/id, :as request}` | 425 |
| P11 | Contract refusal (`seon.instrument` message, `seon.error/problem-sentence`) | `"my.task/plan! refused argument 0 \`request\` at [:seon.task/steps]: expected :seon.task/plan-request (a vector of count at least 1), got a string. Fix: Supply a vector of count at least 1 at [:seon.task/steps]. Contract: :seon.task/plan-request."` plus problems with a generated `:example ["5b"]` | 177 |
| P12 | JVM compile error (`seon.turn.batch/jvm-request` normalization) | `:message "Syntax error compiling at (1:1)."` twice, the cause last in `:chain` | 185 |
| P13 | Refused write (`seon.db/transact!` refusal) | `"seon.db/transact! refused transaction data at [0 :seon.task/nope]: expected an installed attribute, got an undeclared attribute ..."`, `:expected :qualified-keyword :example :N/j` | 138 |
| P14 | `:large-value` finding (`seon.config.eval` bounds) | `{:seon.repl/finding :large-value, :seon.eval/value-tokens 78391, :seon.eval/shown-tokens 1388, :seon.repl/fix "A value this big usually means ..."}` | 75 per occurrence |
| P15 | Arity error (SCI's exception, via `seon.error/reply-value`) | `"Wrong number of args (2) passed to: sci.impl.fns/fun/arity-1--73805"` | 82 |
| P16 | `ex-info` thrown (`seon.error/reply-value`) | `:message "boom"`, then `:chain [{:throwable-class "clojure.lang.ExceptionInfo" :message "boom" :data {:a 1}}]` | 79 |
| P17 | Tool refusal returned as value (`my.task/steps`) | `"Agent messages-opus holds no task, so it has no steps."` plus a prose fix wrapping a form | 100 |
| P18 | Unresolved symbol (`seon.error/reply-value`) | `{:offending undefined-sym :message "Unable to resolve symbol: undefined-sym" :in-namespace {user []} :stacktrace [[user 1 1]]}` | 74 |
| P19 | Reply envelope (`seon.repl/response-order`) | `#:seon.repl{:result result/e…, :ms 0, :calls 6, ...}`; `:calls` (armed internal calls) on every reply | 15 per reply, `:calls` 4 |
| P20 | Situation render (`seon.cluster.agent/render-situation-ai`) | Prose: "You are agent ... Your opening is generated from live facts. ... Every run ends with my.turn/complete or my.turn/wait" | about 150 |
| P21 | `:seon.render/operations` (var, task, settings, identity, error finding, instrument) | `[(my.test/check {:seon.test/changed [sym]})]`, `[(doc f)]`, `[(seon.db/pull '[*] [:seon.cluster.eval/id …])]` | 10-40 |
| P22 | Elision of a long sequence (`seon.print/continuation`) | `…? (drop 32 result/e8783cbcc809f)` | 10 (already right) |
| P23 | Divide by zero, `require` miss | `{:exception-class java.lang.ArithmeticException :message "Divide by zero" :stacktrace [[clojure.core// 1022 1] [user 1 1]]}` | 60 (fine) |

Producers that are already right, and are the model for the rest:

- `Unable to resolve symbol: x` keeps Clojure's own message.
- The `…? (drop 32 result/e…)` sequence cut is Clojure.
- The Var render `{:seon.render.var/symbol user/x}` is small. Clojure would print `#'user/x`.
- `my.program/source` returns `{:sym :kind :source :contract}`, which is small and direct.

## Part 2: rules for agent-facing messages

1. **Data first.** A reply is one map. Anything an agent might act on is a key: the value, the offending
   input, the path, the expected schema, a fix form. Prose is one short sentence at most.
2. **One fixed message per error variant.** Use Clojure's wording where Clojure has the case:
   - `Wrong number of args (2) passed to: user/f`
   - `Unable to resolve symbol: x`
   - `clojure.main/err->msg` for host exceptions
   - Malli's `malli.error/humanize` phrases (`"should be an integer"`) for contracts

   A message never contains a printed value. The values are data keys beside it, printed once by the value
   printer.
3. **The fix is a runnable form,** for example `(doc my.task/plan!)` or
   `(my.task/plan! {:seon.task/steps ["…"]})`. If no form can be built, use no fix rather than a prose fix.
   Never wrap a form inside a sentence.
4. **Use Clojure's and the dependency's shapes.**
   - Contract problems use Malli's explain error keys `{:in :value :schema}`.
   - Test results use clojure.test's summary `{:test :pass :fail :error}`, plus failures as
     `{:test :expected :actual}`.
   - `doc` and `dir` use Clojure's var-meta names (`:name :arglists :doc`).
   - Dig paths are `get-in`, `drop` and `subs` on the result symbol.
5. **No boilerplate repeated on every reply.** A rule that holds every time belongs once in `(help)`, not in a
   `:note` or `:fix` on each reply. Findings appear only for the agent's own values, never for the platform's
   (help, render data).
6. **No internal names the agent cannot act on.** Leave out:
   - digests, request ids, tx refs, bases, layers, `:at` instants
   - host frames, and `file:line` of Seon sources (`eval.clj:298`)
   - `:seon.render.data/*` paths
   - SCI closure names
   - Seon-internal function rankings

   An identity is shown only when the agent passes it back (a result symbol, a task handle).
7. **A cut costs less than what it hides.** A dig form is at most about 15 tokens and is never emitted for a
   member that prints smaller than the form. Otherwise the member prints whole.
8. **Each value is shown once.** The same failure must not appear in `:out`, in a message string and in a
   report.
9. **A world-supplied input is invisible.** Arglists, `doc` and `dir` show what the agent passes
   (`[{:seon.task/keys [steps intent]}]`), never the connection or agent id the world supplies.
10. **Budgets per message class,** in estimated tokens, asserted in each class's MCP regression:

| Class | Budget |
|---|---|
| Envelope keys beyond `:value` | ≤ 20 |
| One error (message ≤ 15) | ≤ 80 |
| Contract refusal with problems | ≤ 100 |
| One finding | ≤ 30 |
| A `:note` | 0 (no notes) |
| A dig or elision form | ≤ 15 |
| `doc` | ≤ 150 |
| `dir` per namespace | ≤ 150 |
| `(help)` | ≤ 300 |
| A test result for one test | ≤ 80 |
| `runtime_status` | ≤ 250 |
| A `:diagnostics` entry | ≤ 100 |
| Shown `:out` | ≤ 250 |

## Part 3: rewrites, ranked

Rank = observed frequency times the tokens saved. The P1 opening counts once per model agent, and it stays
in that agent's context, so it is paid again every turn. "After" figures are measured on the proposed text,
except those marked `~`, which are budget targets.

| Rank | Producer | Before | After | Proposed |
|---|---|---|---|---|
| 1 | P1 opening and `(help)` | 1,180 | 237 | Data map, one entry per job (below). One session-end form, no findings on platform values. |
| 2 | P5 `:slow` plus `:repeated` | 566 | 49 | `{:seon.repl/finding :slow :ms 1506 :fix (seon.profile/of result/e…)}`. `of` (new) ranks this evaluation's own armed calls as `[[sym calls ms] ...]`, top 5. Drop `:repeated` from the reply; it stays on the record. |
| 3 | P3 large-value map cuts | 1,460 | ~450 | No per-entry dig when the member prints smaller than its form. Otherwise `(get-in result/e… [558 :tags])`. One tail cut `…1968 result/e…`. |
| 4 | P4 large output | 1,560 | ~300 | Head of `:out` within 250 tokens, then the printer-rules R2 elision on the live output (no `seon.db/pull` re-read). The finding says `{:finding :large-output :tokens 29653}` with no prose. |
| 5 | P2 not-admitted diagnostic | 979 | 93 | `{:seon.error/type :seon.schema/nilable-return :seon.error/message "Not admitted: user/h returns [:maybe :int]" :seon.error/offending [:=> [:cat :int] [:maybe :int]] :seon.error/fix "[:or :int :seon.error/value]"}` |
| 6 | P6 test results | 470 / 1,240 | 59 | `{:test 1 :pass 0 :fail 1 :error 0 :failures [{:test scratch.msgs/inc3-fails :expected (= 5 (inc3 1)) :actual (not (= 5 4))}]}`, with no `:out` copy and no digests. |
| 7 | P7 `runtime_status` | 1,050 | ~250 | `{:state :faulted :top [[subject score] ...3] :counts {...} :fix [(seon.health/report ...)]}`. Drop `:seon.error/at` and `:layer` from the store entry. |
| 8 | P10 `dir` | 425 | ~120 | `[my.task/claim! my.task/done! my.task/open my.task/plan! my.task/step! my.task/steps]`, as `clojure.repl/dir` gives. `(doc sym)` is one call away. |
| 9 | P8 `def` note | 117 | 20 | Delete the note. `#'user/x` is the value. Help states the persistence rule once. |
| 10 | P11 contract refusal | 177 | 98 | `{:seon.error/type :seon.instrument/invalid-input :seon.error/message "Invalid argument 0 to my.task/plan!" :seon.error/problems [{:in [0 :seon.task/steps] :value "oops" :schema [:vector {:min 1} [:string {:min 1}]]}] :seon.error/fix (doc my.task/plan!)}`. No generated `:example`, no `Contract:` and `Called from` prose. |
| 11 | P9 `doc` | 253 | 149 | `{:name my.task/plan! :arglists ([{:seon.task/keys [steps intent]}]) :doc "<first paragraph>" :malli/schema [...] :example (my.task/plan! {...})}`. No `## Costs` body, no `:supplied`. |
| 12 | P12 JVM compile error | 185 | 60 | `{:seon.error/type :clojure/compile-syntax-check :seon.error/message "Unable to resolve symbol: undefined-jvm-sym in this context" :seon.error/at [user 1 1]}`, from `clojure.main/ex-triage`. |
| 13 | P14 `:large-value` finding | 75 | 25 | `{:seon.repl/finding :large-value :tokens 78391 :shown 1388}`. The fix rule is in help. |
| 14 | P13 refused write | 138 | 74 | `{:seon.error/type :seon.db/unknown-attribute :seon.error/message "Unknown attribute: :seon.task/nope" :seon.error/in [0 :seon.task/nope] :seon.error/candidates [...]}`. Candidates come from `malli.error/with-spell-checking` (`reference-code/malli/src/malli/error.cljc:339`, rev 43c6dca8), not `:example :N/j`. |
| 15 | P17 no-task | 100 | 55 | `{:seon.error/type :my.task/no-task :seon.error/message "No task" :seon.error/fix (my.task/plan! {:seon.task/steps ["…"]})}` |
| 16 | P16 `ex-info` | 79 | 43 | `{:seon.error/message "boom" :seon.error/data {:a 1}}`. Drop the chain link that repeats the message. |
| 17 | P15 arity | 82 | 58 | `"Wrong number of args (2) passed to: user/f"`. The fix is correctness more than size. |
| 18 | P20 situation | ~150 | ~50 | Delete it, or make it data that agrees with help: `{:agent ... :ns ... :unread 0 :turns-left 12 :end (my.agent/done {})}`. |
| 19 | P19 envelope `:calls` | 4 per reply | 0 | Show `:calls` only when `:slow` fires, where it explains the cost. |
| 20 | P18 unresolved symbol | 74 | 66 | Omit `:in-namespace` when it is empty. |

The proposed P1 help (757 characters, 237 tokens):

```clojure
{:repl "Reply with Clojure forms only; prose goes in ;; comments. Each form's :value or :error arrives next turn; result/e… is bound to the value."
 :find [(dir my.task) (doc my.task/plan!) (apropos "step") (source my.task/plan!)]
 :task [(my.task/steps {}) (my.task/plan! {:seon.task/steps ["…"]}) (my.task/done! {})]
 :db [(seon.db/pull '[*] [:seon.agent/id "me"]) (seon.db/q '[:find ...]) (seon.db/transact! [...])]
 :define (defn inc1 {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))
 :test [(deftest inc1-test (is (= 2 (inc1 1)))) (my.test/check {:seon.test/changed ['user/inc1]})]
 :send (my.message/send {:my.message/to "root" :my.message/content "…"})
 :end (my.agent/done {})
 :ns [my.task my.test my.program my.message my.agent seon.db]}
```

The help forms render as data and are shown, never run. The persistence rule ("def and atoms are temporary;
defn, deftest and transactions persist") becomes one more `:repl` clause. The `:ns` line should come from
`seon.cluster.instruction/toolkit-namespaces` (task `seon.bootstrap/help-lists-no-tools-for-an-agent`).

## Part 4: slices

Each slice takes about 15 minutes and is one owner's file set. Each proof goes through `seon.mcp/call` (as in
`test/seon/mcp_entrance_test.clj`'s `answer` helper). The assertion reads the stored evaluation or the reply's
EDN map. It checks the data keys, plus `(<= (seon.ai.tokens/estimate reply) budget)`, and never matches
prose. Files currently held by lanes are marked; such a slice waits for the release or goes to the holder as
a patch.

1. **S1: not-admitted diagnostic through `reply-value`.**
   - Files: `src/seon/turn.clj` (held by repl-p7a at writing), `test/seon/mcp_entrance_test.clj` (held by repl-p7a at writing).
   - Change: `with-diagnostic` prints `(seon.error/reply-value db error)`. `not-admitted` keeps the producer's
     message and marks `:seon.turn/not-admitted true` instead of the prefix sentence.
   - Proof: `a-not-admitted-defn-answers-its-rule-as-data`. Defining `[:maybe :int]` gives `:diagnostics`
     with exactly one map whose `:seon.error/type` is `:seon.schema/nilable-return`, with no `:seon.error/basis`
     or `:seon.error/chain`, and the whole reply is at most 150 tokens.
   - Task: `seon.turn/a-not-admitted-diagnostic-prints-its-write-attempt`.
2. **S2: map-member dig forms.**
   - Files: `src/seon/print.cljc`, `test/seon/print_test.clj`.
   - Change: `continuation` uses the entry's key in `get-in` when the key is a keyword, number, string or
     symbol. The cut decision keeps a member whole when its printed size is at most its dig form's.
   - Proof: an MCP `(zipmap (range 2000) ...)` reply has no `seon.print/value-at` form. Every continuation is
     `get-in`, `drop`, `subs` or the bare handle, and the shown text is at most the profile budget plus 10%.
   - Task: `seon.print/a-member-cut-costs-more-than-it-hides` (with `seon.print/elision-forms-call-a-seon-helper`).
3. **S3: Clojure's arity and host-exception messages.**
   - Files: `src/seon/error.clj` (`reply-value`), `src/seon/turn/batch.clj`.
   - Change: SCI arity uses the first agent frame's symbol in Clojure's sentence. JVM-mode failures take the
     message from `clojure.main/ex-triage` and `ex-str`, the same as Clojure's REPL.
   - Proof: `(defn f [a] a) (f 1 2)` has `:seon.error/message` equal to
     `"Wrong number of args (2) passed to: user/f"`. JVM `(undefined-jvm-sym)` has the message equal to
     `(clojure.main/err->msg the-throwable)`'s cause line. Each reply is at most 80 tokens.
   - Tasks: `seon.sci.eval/an-arity-error-names-the-sci-closure`,
     `seon.turn.batch/a-jvm-compile-error-shows-the-wrapper-message`.
4. **S4: findings without prose.**
   - Files: `resources/seon/schemas/seon.config.eval.edn`, `src/seon/repl.clj` (held by repl-p7a at writing).
   - Change: the `:fix` strings go. `:large-value` and `:large-output` carry numbers only. `:slow` carries
     `(seon.profile/of result/e…)` once that per-evaluation read exists; until then, no fix. `:repeated` and
     `:calls` leave the reply (they stay on the record).
   - Proof: `(Thread/sleep 1100)` gives `:findings` equal to one map with keys `#{:seon.repl/finding :ms}`, and
     the whole reply is at most 40 tokens.
   - Task: `seon.repl/the-slow-fix-ranks-the-whole-process`.
5. **S5: delete the `def` note.**
   - Files: `src/seon/repl.clj` (held by repl-p7a at writing), `src/seon/bootstrap.clj`.
   - Change: delete `def-note` and its key. Help gains the persistence clause.
   - Proof: an MCP `(def x 1)` reply has no `:note` key and is at most 25 tokens.
   - Task: `seon.repl/every-def-repeats-the-same-persistence-note`.
6. **S6: help as data.**
   - Files: `src/seon/bootstrap.clj`, `src/seon/cluster/agent.clj` (`render-situation-ai`), the
     `seon.help-test` tests.
   - Change: `help-value` returns the map above. The situation render is deleted, or reduced to data that
     names `(my.agent/done {})`.
   - Proof: an MCP `(help)` reply has no `:findings`. Every symbol in head position of the help forms
     resolves in the agent's context, the reply is at most 300 tokens, and exactly one session-end form
     appears.
   - Tasks: `seon.bootstrap/help-is-flagged-as-a-large-value`, `seon.bootstrap/session-end-is-taught-three-ways`.
7. **S7: test results as clojure.test summaries.**
   - Files: `src/my/test.clj`, `src/seon/render/test.clj`.
   - Change: `my.test/run` and `check` return `{:test :pass :fail :error :failures [{:test :expected :actual}]}`
     plus `:seon.test.run/id` for digging. The runner's `:out` copy of the failure is not echoed into the reply.
   - Proof: one failing deftest gives a value with exactly those keys and no `:out`, at most 80 tokens.
   - Task: `my.test/run-owned-reply-repeats-its-output` (same class).
8. **S8: compact contract refusals.**
   - Files: `src/seon/instrument.clj`, `src/seon/error.clj` (`problem-sentence`).
   - Change: the message is a fixed `"Invalid argument N to f"`, `"Invalid return from f"` or
     `"Wrong number of args (n) passed to: f"`. Problems use Malli explain keys. No generated `:example`, no
     `Contract:` or `Called from` prose (the caller is already a stacktrace frame). The fix is `(doc f)`.
   - Proof: `(my.task/plan! {:seon.task/steps "oops"})` gives `:seon.error/problems` equal to
     `[{:in [0 :seon.task/steps] :value "oops" :schema [:vector ...]}]`, a reply of at most 100 tokens, and
     no `:example` key.
   - Tasks: `seon.instrument/contract-refusal-carries-wrapper-bookkeeping`, `seon.error/a-missing-key-message-reads-badly`.
   - This must land with, or after, the errors cut's constructor.
9. **S9: `doc` and `dir` in Clojure's shape.**
   - Files: `src/seon/bootstrap.clj` (`doc`, `dir`).
   - Change: `dir` returns sorted public symbols. `doc` returns var-meta keys, with the first docstring
     paragraph and no `## Costs` section. World-supplied keys are hidden from `:arglists`.
   - Proof: `(doc my.task/plan!)` has keys within `#{:name :arglists :doc :malli/schema :example}` and is at
     most 150 tokens. `(dir my.task)` is a vector of symbols of at most 60 tokens.
   - Tasks: `seon.sci.eval/doc-and-dir-erase-request-keys`, `doc-prints-contracts-expanded-with-keys-garbled`.
10. **S10: printed output.**
    - Files: `src/seon/repl.clj` (`shown-output`, held by repl-p7a at writing), `src/seon/render/value.clj`
      (held by out-fix).
    - Change: this is the printer-rules R2 slice. The `:out` head stays within 250 tokens, and its elision form
      reads the live output, never a `seon.db/pull` of an internal attribute.
    - Proof: `(dotimes [i 3000] (println i))` gives an `:out` of at most 260 tokens, and its continuation
      form, when evaluated, returns the remaining lines.
    - Task: `seon.repl/printed-output-reaches-the-reply-unbounded`, `the-out-elision-form-re-pulls-the-database` (coordinate with out-fix).
11. **S11: small refusals.**
    - Files: `src/seon/error.clj` (empty `:in-namespace`), `src/seon/test.clj` (`selection-seeds` message for an
      identity defined in this pass), `src/seon/db.clj` (unknown-attribute refusal with spelling candidates).
    - Proofs, one per behaviour:
      - An unresolved symbol has no `:seon.error/in-namespace` key when nothing is near.
      - A same-pass check refusal has `:seon.error/type :seon.test/identity-unsettled`.
      - `(seon.db/transact! [{:seon.task/nope 1}])` has `:seon.error/candidates` non-empty and no `:example`.
    - Tasks: `seon.error/an-unresolved-symbol-shows-an-empty-neighbour-list`,
      `my.test/check-in-the-defining-pass-says-the-test-does-not-exist`.
12. **S12: `runtime_status` focus.**
    - Files: `src/seon/cluster/status.clj`, `src/seon/health.clj`.
    - Change: an AI face of at most 250 tokens: state, top 3 `[subject score]`, counts, and the
      `:seon.render/operations` already offered.
    - Proof: an MCP `runtime_status` reply read as EDN has those keys and is at most 250 tokens.

## Tasks recorded

New tasks, added to `resources/seon/tasks/` in this commit:

- `seon.turn/a-not-admitted-diagnostic-prints-its-write-attempt`
- `seon.print/a-member-cut-costs-more-than-it-hides` (with `seon.print/elision-forms-call-a-seon-helper`)
- `seon.sci.eval/an-arity-error-names-the-sci-closure`
- `seon.turn.batch/a-jvm-compile-error-shows-the-wrapper-message`
- `seon.bootstrap/help-is-flagged-as-a-large-value`
- `seon.bootstrap/session-end-is-taught-three-ways`
- `seon.repl/the-slow-fix-ranks-the-whole-process`
- `seon.error/an-unresolved-symbol-shows-an-empty-neighbour-list`
- `my.test/check-in-the-defining-pass-says-the-test-does-not-exist`

Existing tasks this evidence extends:

- `seon.repl/every-def-repeats-the-same-persistence-note`
- `seon.repl/a-slow-reply-names-nothing`
- `seon.repl/printed-output-reaches-the-reply-unbounded`
- `seon.instrument/contract-refusal-carries-wrapper-bookkeeping`
- `seon.sci.eval/clojure-test-parity-residue` (FAIL now names `eval.clj:298`)
- `seon.sci.eval/doc-and-dir-erase-request-keys`
- `my.test/run-owned-reply-repeats-its-output`
- `seon.bootstrap/help-lists-no-tools-for-an-agent`

## Verification limits

- Frequencies come from one branch's 475 evaluations after a reset. They are not long-run telemetry.
- The `~` after-figures are budget targets, not measured output.
- The P7 status figure is `bin/seon status` (3,356 characters), which the MCP `runtime_status` tool prints
  the same way.
- No model-agent context was captured live, because default runs with no provider. The opening's cost is
  derived from `seon.repl-grammar-test/stored-help-keeps-its-renderer-and-bare-response`, which asserts that
  the opening is `;; I should understand how this REPL works before I act.` followed by `(help)` and the help
  text.
