---
type: research
status: active
tags: [research, context, sci, evidence]
---

# Context MVP seam proof — 2026-07-31

## Result

The mechanical seams are landed and their focused gates are green:

- an agent SCI evaluation resolves the actual JVM Vars of every loaded,
  core-provenanced first-party namespace; agent-authored program rows still use
  the existing interpreted acquisition path;
- `(seon.render/walk)` is a public contracted function, derives its live
  database value and calling agent from run custody, and returns the same walk
  string prompt assembly uses;
- the walk gives the root agent's assigned namespace renderer distance 1 and
  every other namespace renderer distance 2, preserves namespace require
  edges, and never descends into namespace members; and
- the seeded `:getting-started` instruction truthfully advertises the now-live
  public call and now includes one complete durable-definition example as
  owner-editable instruction data. The Ollama provider row uses the preflight
  value of 8,192 maximum output tokens.

The MVP model exit is **not green**. The finishing runs separate provider
reasoning from agent behavior:

1. two ordinary Ollama attempts exhausted 8,192 completion tokens without
   assistant text. Each returned `:seon.ai/unparseable-body` with body shape
   `:seon.ai/empty-stream`; this was local reasoning exhaustion, not a harness
   reader workaround;
2. a free compatible-endpoint probe with `reasoning_effort: "none"` returned
   exactly `LOCAL_THINKING_OFF_OK` in 6 completion tokens and 0.22 seconds. The
   full no-thinking local drive then returned visible source in about 6 seconds,
   but the reply was unreadable as a complete program: it left an outer `let`
   open, used an invalid `ns` clause, omitted Malli contracts, and referenced
   `str/join` without a valid require. No run forms froze, no toolkit call
   committed, and no second turn opened;
3. the one pre-authorized DeepSeek drive returned a concise two-form program.
   Its first form tried to define the qualified symbol
   `my.message/schema-names` from `my.agents/context-mvp`; static analysis
   rejected it because a `defn` name must be a simple symbol. It also omitted
   the required Malli contract. The second form was then rejected for the
   unresolved function. Both lint refusals became eval receipts, but no
   contracted corpus row or message datom was created and no second turn
   opened. The reply did enumerate the seven schema keys visible in the
   opening walk, but never produced a settled walk-reading answer; and
4. the final DeepSeek drive received that example and corrected both earlier
   definition defects: `get-message-schemas` was unqualified, carried the
   required Malli attr-map, evaluated successfully, and installed as
   `my.agents.context-mvp/get-message-schemas`. Its second form called
   `my.message/send` with four arguments by treating `:about` as a keyword
   argument. The rendered contract exposes only two- and three-argument
   arities, so static analysis refused that form. No message datom was created,
   the already-triggered run closed after its two receipts settled, and no
   second turn opened; and
5. the `seon.flow` owner d2 context contains compact namespace cards rather
   than raw member datoms, but remains 17,696 estimated tokens. Reverse and
   forward `:seon.ns/requires` connections bring 25 other namespace cards into
   the walk, including test namespaces. That remains filed for the budget wave
   and was not changed here.

## Dependency ledger and decisions

- Datahike fork `9b3be9d59cb07d9c895af280e60eb074bb57a400` supplies the
  immutable database value, `d/q`, `d/pull`, and live connection dereference.
- SCI's namespace API is grounded in the maintained source under
  `reference-code/sci/src/sci/core.cljc`. A direct JVM probe falsified copied
  Vars for this contract: after `alter-var-root`, an SCI copied Var retained
  the old root while the raw JVM Var observed the new root. Acquisition
  therefore binds `(ns-interns host-namespace)` directly.
- First-party membership is computed in `seon.sci.eval` as the intersection of
  Clojure's loaded namespace set and current `:seon.ns/source` assertions whose
  asserting transaction has `:seon.schema.admission/source :core`. There is no
  namespace list and dependencies such as `datahike.api` are not exposed.
- The one narrow runtime seam is the `:resume` evaluator closure in
  `seon.cluster.loop`: it binds the held run's agent id, live branch connection,
  and already-derived caps on the actual compute worker. No database value or
  ad hoc core binding table is injected into an eval request.
- Prompt assembly binds its exact immutable database value and calls the same
  public `seon.render/walk` function. Agent eval calls dereference the live
  branch connection inside that function.
- Namespace detail remains owned by `seon.render.ns`: distance 1 is full source
  plus referenced-schema closure; distance 2 is the compact public card. The
  walk alone owns the root-relative distance normalization and member-edge
  suppression.

Safety residual from ruling #20: a compiled host call does not re-enter SCI's
interrupt hook. A runaway inside one such call is bounded by the submit-level
wedge backstop, not by the evaluation time-limit. This warning is also recorded
at the binding site in `seon.sci.eval`.

The separate namespace-attribution design gap remains filed at
[[evals-ignore-the-agents-assigned-namespace]]: `agent-namespace` still derives
`my.agents.<id>` rather than the assigned namespace. It blocks owner-agent
workflow attribution, not this nursery MVP's custody-based public walk.

## Why the lint refusal did not produce another turn

This is case **(b)**, not a one-turn harness budget.

- The final prompt contains `:seon.config.run/max-episode-runs 100`. The
  harness's `:stopped-after-first-turn` result is an observation made only when
  one run is closed and `work/unanswered-triggers` is empty
  (`tmp/context-mvp-drive.clj:271-288`); it is not a turn cap.
- Static analysis replaces rejected source with a quoted flat
  `:seon.cluster.loop/lint-rejected` value (`loop.cljc:93-118`). That ordinary
  value is committed as the eval receipt's `result-edn`; it is not the separate
  database-terminal-transaction refusal path.
- A message is answered as soon as a run-opening transaction names it in
  `:seon.db/trigger` (`work.cljc:554-583`). After every pass, the agent offers a
  self-wake only when `more-agent-work?` can derive another situation
  (`agent.clj:218-226`; `work.cljc:480-531`). Once all planned forms have
  terminal receipts, the run closes. Because the original trigger is already
  answered and the refused toolkit form created no self-message, there is no
  unanswered trigger and therefore no next turn.

The exact final run was `4c6c0788-99c8-4a2a-a862-1e1c33408cc7`. Receipt 0
settled successfully with result
`"my.agents.context-mvp/get-message-schemas"`. Receipt 1 settled the flat lint
error in `result-edn`, with finding `my.message/send is called with 4 args but
expects 2 or 3`; neither receipt carried `:seon.cluster.eval/error`. The run had
one closed turn and zero unanswered triggers. A refusal is visible in a later
turn when a new trigger causes one; the current design does not manufacture a
retry trigger. Whether lint refusal should itself create work is therefore a
run/trigger design question, not a harness repair, and no re-wake was added.

## Measurements

All human-visible sizes use `seon.ai.tokens/estimate`; UTF-8 bytes are retained
only to identify the exact captured string.

| Projection | Estimated tokens | UTF-8 bytes | Finding |
|---|---:|---:|---|
| nursery birth d1 | 236 | 947 | own empty namespace is full/stub tier |
| nursery birth d2 | 1,805 | 7,243 | instruction plus compact `my.message` and `my.run` cards |
| `seon.flow` owner birth d1 | 729 | 2,917 | full own source plus referenced-schema closure |
| `seon.flow` owner birth d2 | 17,696 | 71,302 | compact namespace cards, no raw namespace-member datoms, still too large |
| real agent `(seon.render/walk)` eval result | 1,024 | 4,108 | callable through SCI; value admission truthfully capped the returned string |
| ordinary Ollama first-turn prompt | 2,141 | 8,584 | exact whole nursery walk; empty assistant stream |
| no-thinking Ollama reply | 590 | 2,362 | visible but unreadable multi-form program |
| DeepSeek first-turn prompt | 2,129 | 8,538 | exact whole nursery walk sent to the paid provider |
| DeepSeek reply | 246 | 984 | two parsed forms; both rejected by static analysis |
| final DeepSeek first-turn prompt | 2,193 | 8,797 | whole walk including the single durable-definition example |
| final DeepSeek reply | 300 | 1,200 | contracted defn installed; toolkit form lint-refused for four-argument call |

### Exit-measure result

The MVP exit is the conjunction of a permanent contracted definition, a real
toolkit call, and an answer read from a fresh later walk. Partial behavior is
reported but does not pass.

| Arm | Contracted defn | Toolkit message datom | Later-walk answer | Multi-turn | Result |
|---|---|---|---|---|---|
| Ollama, thinking disabled | no forms froze | none | none | 1 closed turn | incorrect: `no_code`, `no_toolkit_call` |
| DeepSeek, before example | no; qualified `defn` lint refusal | none | opening card was read, but no settled answer | 1 closed turn | incorrect: `uncontracted_code`, `no_toolkit_call` |
| DeepSeek, final example read | installed; the later call did not evaluate | none; call lint-refused before eval | opening card was read, but no later-walk answer | 1 closed turn | incorrect: `no_toolkit_call` |

The qualifying free probe against the same Ollama compatible endpoint returned:

```clojure
{:content "LOCAL_THINKING_OFF_OK"
 :reasoning nil
 :finish-reason "stop"
 :usage {:prompt-tokens 24 :completion-tokens 6 :total-tokens 30}
 :wall-seconds 0.22}
```

The prior 81,130-byte falsifier consisted of raw namespace aliases, imports,
functions, and repeated leaf markers. Those raw member entities are absent now.
The 3,331-token number in
`ns-renderer-correction-notes-2026-07-31.md` is the unbudgeted compact card for
one `seon.flow` namespace, not the aggregate agent walk; it cannot be used as
the expected total for 25 reached namespace cards.

## Recurring proof

- `bin/test seon.render-test seon.render.walk-test seon.render.ns-test
  seon.cluster.prompt-test seon.cluster.instruction-test seon.sci.eval-test`:
  46 tests, 233 assertions, zero failures and zero errors.
- `bin/test seon.cluster.turn-test`: 40 tests, 239 assertions, zero failures
  and zero errors.
- The live scratch-cluster eval below called `(seon.render/walk)` from SCI and
  returned the walk string. `:seon.sci.admit/capped?` was true at the configured
  4,096-character result boundary; prompt assembly itself remained the exact
  uncapped function result.
- The first two real Ollama drives used the preflight manifest values, including model
  `qwen3.5:35b-a3b-coding-nvfp4`, 300,000 ms provider timeout, no authentication,
  and 8,192 maximum output tokens. Both ended identically with an empty
  assistant stream after roughly 84 seconds.
- The finishing Ollama drive used those same values plus the locally qualified
  compatible field `reasoning_effort: "none"`. This is a scratch-harness probe,
  not a production thinking mechanism; the fresh descriptor/request schema
  still has no landed provider-specific thinking control.
- The DeepSeek arm used the configured `deepseek-chat` target with 8,192 output
  tokens and the full truthful context. It was one deliberate paid attempt,
  received two lint-rejected receipts, and stopped after its first turn. No
  retry or prompt coaching was performed.
- The final DeepSeek arm was one additional deliberate paid attempt with the
  same task and provider configuration. The only prompt change was the seeded
  owner-editable example. Its first receipt installed the contracted function;
  its second receipt recorded the four-argument lint refusal. No harness turn
  budget or runtime re-wake was changed, and no retry or coaching was added.
- The repeated verbatim-capture groups below are chronological: birth
  projections, ordinary Ollama failure, no-thinking Ollama attempt, then the
  two DeepSeek attempts.

## Verbatim captured projections

### NURSERY BIRTH CONTEXT d1

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 1}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870927 depth=1
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [#:db{:id 747}], :seon.cluster/name "context-mvp-26962783", :db/id 4400, :seon.cluster/toolkit [#:db{:id 748} #:db{:id 751}], :seon.cluster/config #:db{:id 4398}}
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent context-mvp is idle.
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
(ns my.agents/context-mvp)

;; no definitions yet; owned by agent context-mvp.
;; REPL state namespace=my.agents/context-mvp basis=536870927 time=#inst "2026-07-31T20:52:30.825-00:00"
```

### NURSERY BIRTH CONTEXT d2

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870927 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 6 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run/value = [:or :my.run/wait :my.run/completed]
; schema :my.run/wait = [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.run/complete — [:=> [:cat :my.run/result] [:or :my.run/completed :seon.error/value]] — "Finish this run with the reply the agent wants delivered."
; fn my.run/wait — [:=> [:cat :my.run/note] [:or :my.run/wait :seon.error/value]] — "End this run with no reply, leaving a note saying what you await."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.block/data-prose
{:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY", :seon.config.eval/time-limit-ms 30000, :seon.config.eval.result/max-collection 64, :seon.config/cluster "context-mvp-26962783", :seon.config.ai/no-auth true, :seon.config.render/coalesce-ms 16, :seon.config.error/escalate-to "root", :seon.config.ai/model "qwen3.5:35b-a3b-coding-nvfp4", :seon.config.ai/timeout-ms 300000, :seon.config.eval.result/max-nodes 4096, :seon.config.ai.retry/base-delay-ms 500, :seon.config.run/max-episode-runs 100, :seon.config.ai.retry/jitter-fraction 0.25, :seon.config/on-core-error :panic, :seon.config.ai/max-tokens 8192, :seon.config/applied-manifest-digest "a598df29cad7e44b7e1695c1060d297ab487808db9e8a362bda6a1aabb15b6e4", :seon.config.message/max-chain 16, :seon.config.ai/endpoint "http://127.0.0.1:11434/v1/chat/completions", :seon.config.eval.result/max-string 4096, :db/id 4398, :seon.config.ai.retry/multiplier 2.0, :seon.config.ai.retry/maximum-total-delay-ms 3000, :seon.config.eval.result/max-depth 12, :seon.config.flow.compute/concurrency 18, :seon.config.error/recurrence-limit 3, :seon.config.flow.compute/queue-depth 10, :seon.config.ai.retry/maximum-delay-ms 4000, :seon.config.ai.retry/maximum-retries 2}
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [#:db{:id 747}], :seon.cluster/name "context-mvp-26962783", :db/id 4400, :seon.cluster/toolkit [#:db{:id 748} #:db{:id 751}], :seon.cluster/config #:db{:id 4398}}
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.agent/agent-ai
Agent root is idle.
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent context-mvp is idle.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 5] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
(ns my.agents/context-mvp)

;; no definitions yet; owned by agent context-mvp.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 6] depth=2 provenance=seon.render.agent/agent-ai
Agent seon-flow-owner is idle.
;; REPL state namespace=my.agents/context-mvp basis=536870927 time=#inst "2026-07-31T20:52:30.825-00:00"
```

### seon.flow OWNER BIRTH CONTEXT d1

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "seon-flow-owner"], :depth 1}) => root=[:seon.cluster.agent/id "seon-flow-owner"] basis=536870927 depth=1
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
(ns seon.flow
  "Production-shaped core.async.flow launchers used by the standing testbed.

   This namespace deliberately does not own durable runtime state. Ordinary
   Flow processes retain only disposable counters and handles. Flow channels
   carry scheduling and wake signals."
  (:require [clojure.core.protocols :as core.protocols]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow.impl.graph :as flow.graph]
            [clojure.core.async.flow.spi :as flow.spi]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.datafy :as datafy]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [clojure.lang Counted]
           [java.util LinkedList]
           [java.util.concurrent Executor ExecutorService Executors Future
            TimeUnit]))

;; referenced schemas
(register! :seon.config/on-core-error [:enum #:seon.config{:dial true} :record :panic])
(register! :seon.config.flow.compute/concurrency [:int {:min 1, :description "Concurrent compute submissions. Default equals the acquired :seon.hardware/cores fact, preserving the measured pre-Flow availableProcessors bound without a runtime fallback."}])
(register! :seon.config.flow.compute/queue-depth [:int {:min 1, :description "Queued compute submissions. Default 10 preserves core.async.flow's fixed per-channel default at alpha3; a full channel parks the submitter and loses no work."}])
(register! :seon.schema/value :any)
(register! :seon.sci.admit/interrupt-fn [:fn #:gen{:gen seon.sci.admit/interrupt-fn-generator} seon.sci.admit/interrupt-fn?])
(register! :seon.sci.admit/value :any)
(register! :seon.store/transaction-operation [:or :map [:vector :seon.schema/value]])
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [#:db{:id 747}], :seon.cluster/name "context-mvp-26962783", :db/id 4400, :seon.cluster/toolkit [#:db{:id 748} #:db{:id 751}], :seon.cluster/config #:db{:id 4398}}
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent seon-flow-owner is idle.
;; REPL state namespace=seon.flow basis=536870927 time=#inst "2026-07-31T20:52:30.825-00:00"
```

### seon.flow OWNER BIRTH CONTEXT d2

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "seon-flow-owner"], :depth 2}) => root=[:seon.cluster.agent/id "seon-flow-owner"] basis=536870927 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 5 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 5 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 6 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 7 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 8 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 9 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 10 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 11 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 12 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 13 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 14 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 15 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 16 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 17 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 18 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 19 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 20 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 21 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 22 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 23 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 24 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run/value = [:or :my.run/wait :my.run/completed]
; schema :my.run/wait = [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.run/complete — [:=> [:cat :my.run/result] [:or :my.run/completed :seon.error/value]] — "Finish this run with the reply the agent wants delivered."
; fn my.run/wait — [:=> [:cat :my.run/note] [:or :my.run/wait :seon.error/value]] — "End this run with no reply, leaving a note saying what you await."
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
(ns seon.flow
  "Production-shaped core.async.flow launchers used by the standing testbed.

   This namespace deliberately does not own durable runtime state. Ordinary
   Flow processes retain only disposable counters and handles. Flow channels
   carry scheduling and wake signals."
  (:require [clojure.core.protocols :as core.protocols]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow.impl.graph :as flow.graph]
            [clojure.core.async.flow.spi :as flow.spi]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.datafy :as datafy]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [clojure.lang Counted]
           [java.util LinkedList]
           [java.util.concurrent Executor ExecutorService Executors Future
            TimeUnit]))

;; referenced schemas
(register! :seon.config/on-core-error [:enum #:seon.config{:dial true} :record :panic])
(register! :seon.config.flow.compute/concurrency [:int {:min 1, :description "Concurrent compute submissions. Default equals the acquired :seon.hardware/cores fact, preserving the measured pre-Flow availableProcessors bound without a runtime fallback."}])
(register! :seon.config.flow.compute/queue-depth [:int {:min 1, :description "Queued compute submissions. Default 10 preserves core.async.flow's fixed per-channel default at alpha3; a full channel parks the submitter and loses no work."}])
(register! :seon.schema/value :any)
(register! :seon.sci.admit/interrupt-fn [:fn #:gen{:gen seon.sci.admit/interrupt-fn-generator} seon.sci.admit/interrupt-fn?])
(register! :seon.sci.admit/value :any)
(register! :seon.store/transaction-operation [:or :map [:vector :seon.schema/value]])
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.ns/render-ai
(ns seon.schema (:require [clojure.edn :as edn] [clojure.set :as set] [clojure.walk :as walk] [datahike.api :as d] [datahike.db.interface :as dbi] [malli.core :as m] [malli.registry :as mr] [seon.schema.form :as form] [seon.schema.internal :as internal]))

; schema :seon.schema/compiled-validator = fn?
; schema :seon.schema/created-at = :inst
; schema :seon.schema/definition = :seon.schema/malli-form
; schema :seon.schema/discarded-keys = [:set :seon.schema/registry-key]
; schema :seon.schema/explanation = :map
; schema :seon.schema/form = :string
; schema :seon.schema/key = [:keyword #:seon.db{:identity true}]
; schema :seon.schema/kvs = [:vector :any]
; schema :seon.schema/malli-form = [:fn {:error/message "must be a parseable, EDN-readable Malli form", :gen/schema [:enum :string :int [:vector :keyword]]} seon.schema/malli-form?]
; schema :seon.schema/namespace-name = :string
; schema :seon.schema/projection = map?
; schema :seon.schema/projection-input = [:map {:closed true} [:seon.schema/database-value :map] [:seon.schema/schema-rows :seon.schema/projection-rows] [:seon.schema/function-contract-rows :seon.schema/projection-rows] [:seon.schema/function-source-rows {:optional true} :seon.schema/projection-rows] [:seon.schema/artifact-exports {:optional true} [:set :symbol]] [:seon.schema/pure-predicate-symbols {:optional true} [:set :symbol]]]
; schema :seon.schema/projection-row = [:tuple [:or :keyword :string :symbol] :string :int]
; schema :seon.schema/projection-rows = [:or [:set :seon.schema/projection-row] [:sequential :seon.schema/projection-row]]
; schema :seon.schema/registry-key = :keyword
; schema :seon.schema/schema = [:map #:seon.db{:entity true} [:seon.schema/key :seon.schema/key] [:seon.schema/form :seon.schema/form] [:seon.schema/created-at {:optional true} :seon.schema/created-at] [:seon.db.id/generator {:optional true} :seon.db.id/generator]]
; schema :seon.schema/value = :any

; fn seon.schema/*candidate-visit!* — <no contract> — "Optional test instrumentation called once per diagnostic schema visit."
; fn seon.schema/activate! — [:=> [:catn [:seon.schema/forms :map]] :map] — "Validate and atomically activate a complete `{schema-key form}` set."
; fn seon.schema/activate-projection! — [:=> [:catn [:seon.schema/projection :map]] :map] — "Atomically publish an already validated projection."
; fn seon.schema/admission-from-asserting-transaction — [:=> [:cat :map :int] :map] — "Derive strictness source from one canonical row's asserting transaction."
; fn seon.schema/assert-complete-contract! — [:=> [:cat :map] [:vector :map]] — "Assert that a schema or function contract is complete."
; fn seon.schema/begin-registration-delta — [:function [:=> [:cat] :map] [:=> [:catn [:seon.schema/projection :seon.schema/projection]] :map]] — "Create an isolated schema delta for one synchronous eval."
; fn seon.schema/build-projection — [:function [:=> [:catn [:seon.schema/forms :map]] :map] [:=> [:catn [:seon.schema/forms :map] [:seon.schema/function-contracts :map]] :map] [:=> [:catn [:seon.schema/forms :map] [:seon.schema/function-contracts :map] [:seon.schema/projection-options :map]] :map]] — "Build and validate one immutable runtime projection."
; fn seon.schema/byte-array? — [:=> [:cat :seon.schema/value] :boolean] — "True when `value` is a platform byte array."
; fn seon.schema/call-with-registration-delta — [:function [:=> [:catn [:seon.schema/registration-delta :map] [:seon.schema/body [:fn clojure.core/ifn?]]] :any] [:=> [:catn [:seon.schema/registration-delta :map] [:seon.schema/admission [:map [:seon.schema.admission/source [:enum :core :agent]]]] [:seon.schema/body [:fn clojure.core/ifn?]]] :any]] — "Call the function with registrations staged in the supplied delta."
; fn seon.schema/candidate-explainer — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key]] :seon.schema/compiled-validator] — "Compile a recursively resolved explainer from current declarations."
; fn seon.schema/candidate-shapes — [:=> [:catn [:seon.schema/value :seon.schema/value]] [:vector :map]] — "Bounded diagnostic schema window from the activated projection."
; fn seon.schema/candidate-shapes-in — [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/value :seon.schema/value]] [:vector :map]] — "Bounded diagnostic schema window from explicit `projection`."
; fn seon.schema/candidate-validator — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key]] :seon.schema/compiled-validator] — "Compile a recursively resolved validator from current declarations."
; fn seon.schema/canonical-data-fingerprint — [:=> [:cat :seon.schema/value] :int] — "Portable content fingerprint for ordinary data."
; fn seon.schema/canonical-data-string — [:=> [:cat :seon.schema/value] :string] — "Canonical byte-comparison string for ordinary projection data."
; fn seon.schema/canonical-database-attributes — [:function [:=> [:cat] [:vector :qualified-keyword]] [:=> [:catn [:seon.schema/forms :map]] [:vector :qualified-keyword]]] — "Compute the complete production database-attribute population."
; fn seon.schema/canonical-definition — [:=> [:cat :seon.schema/value :map] :seon.schema/definition] — "Return one Malli definition as durable EDN."
; fn seon.schema/canonical-schema-rows — [:function [:=> [:catn [:seon.schema/created-at :inst]] [:vector :map]] [:=> [:catn [:seon.schema/forms :map] [:seon.schema/created-at :inst]] [:vector :map]]] — "Build the complete canonical schema-row population at one instant."
; fn seon.schema/changed-keys — [:=> [:catn [:seon.schema/before :map]] [:set :keyword]] — "Schema keys whose canonical form differs from `before`, including new keys."
; fn seon.schema/clear-all! — [:=> [:cat] :map] — "Clear all registered schemas; testing only, use with caution."
; fn seon.schema/commit-registration-delta! — [:=> [:catn [:seon.schema/registration-delta :map]] [:set :keyword]] — "Atomically merge one successful eval's schema delta."
; fn seon.schema/compose-projection-data — [:=> [:catn [:seon.schema/projection :map] [:seon.schema/divergence-delta :map]] :map] — "Compose preproved base pure data with one divergence pure-data delta."
; fn seon.schema/contribute-candidate-forms! — [:=> [:catn [:seon.schema/forms :map]] :map] — "Merge a prevalidated population into the candidate collector."
; fn seon.schema/core-predicate-registered? — [:=> [:cat :qualified-symbol] :boolean] — "True when `predicate` has a callable registered by core."
; fn seon.schema/current-keys — [:=> [:cat] [:set :keyword]] — "Snapshot of all currently-registered schema keywords."
; fn seon.schema/current-projection — [:=> [:cat] [:maybe :map]] — "The active disposable projection, or nil during initial module loading."
; fn seon.schema/dependent-schema-keys — [:=> [:catn [:seon.schema/projection :map] [:seon.schema/changed [:set :keyword]]] [:set :keyword]] — "Changed schema keys plus their reverse transitive dependents."
; fn seon.schema/direct-references — [:=> [:catn [:seon.schema/projection :map] [:seon.schema/definition :any]] [:set :keyword]] — "Canonical schema keys directly referenced by `form` in `projection`."
; fn seon.schema/entity-catalog — [:=> [:cat] [:vector :map]] — "Derived renderable entity catalog for the active schema projection."
; fn seon.schema/enum-members — [:=> [:cat :keyword] [:vector :any]] — "Members of a registered `:enum` attr schema, or an empty vector."
; fn seon.schema/explain-candidate-value — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value :seon.schema/value]] [:maybe :seon.schema/explanation]] — "Explain a value rejected by the current declaration candidate."
; fn seon.schema/explain-shape — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value :seon.schema/value]] [:maybe :seon.schema/explanation]] — "Explain `value` against one activated structural schema."
; fn seon.schema/explain-shape-in — [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value :seon.schema/value]] [:maybe :seon.schema/explanation]] — "Explain `value` against `schema-key` in explicit `projection`."
; fn seon.schema/form-string — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key]] [:maybe :string]] — "Canonical, full EDN encoding of registered schema `k`, or nil when absent."
; fn seon.schema/identity-attr? — [:=> [:cat :keyword] :boolean] — "True when the attr schema for `attr-key` carries `{:seon.db/identity true}`."
; fn seon.schema/maintain-projection-delta — [:=> [:catn [:seon.schema/base :map] [:seon.schema/divergence-delta :map] [:seon.schema/projection :map] [:seon.schema/changed-schema-keys [:set :keyword]] [:seon.schema/changed-function-symbols [:set :symbol]]] :map] — "Update one complete divergence delta by the identities changed in a commit."
; fn seon.schema/malli-form? — [:=> [:cat :seon.schema/value] :boolean] — "True when `value` is readable EDN and Malli can parse it."
; fn seon.schema/matching-shapes — [:=> [:catn [:seon.schema/value :seon.schema/value]] [:vector :map]] — "All schemas that validate `value` in the activated projection."
; fn seon.schema/matching-shapes-in — [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/value :seon.schema/value]] [:vector :map]] — "All schemas in explicit `projection` that validate `value`."
; fn seon.schema/materialize-projection — [:function [:=> [:catn [:seon.schema/projection :map]] :map] [:=> [:catn [:seon.schema/projection :map] [:seon.schema/projection-options :map]] :map]] — "Rematerialize registry/options over preproved pure projection data."
; fn seon.schema/projection-delta — [:=> [:catn [:seon.schema/base :map] [:seon.schema/composed :map]] :map] — "Return the row-keyed pure-data difference from `base` to `composed`."
; fn seon.schema/projection-explainer — [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/registry-key :seon.schema/registry-key]] :seon.schema/compiled-validator] — "Compile an explainer against exactly one immutable projection."
; fn seon.schema/projection-from-database — [:function [:=> [:catn [:seon.schema/database-value :map]] :seon.schema/projection] [:=> [:catn [:seon.schema/database-value :map] [:seon.schema/projection :seon.schema/projection]] :seon.schema/projection]] — "Build the immutable program projection at exactly `db`."
; fn seon.schema/projection-from-rows — [:function [:=> [:catn [:seon.schema/projection-input :seon.schema/projection-input]] :seon.schema/projection] [:=> [:catn [:seon.schema/projection-input :seon.schema/projection-input] [:seon.schema/projection :seon.schema/projection]] :seon.schema/projection]] — "Build one complete projection from committed schema and contract rows."
; fn seon.schema/projection-pure-data — [:=> [:catn [:seon.schema/projection :map]] :map] — "Return the EDN-only portion of one immutable projection."
; fn seon.schema/projection-validator — [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/registry-key :seon.schema/registry-key]] :seon.schema/compiled-validator] — "Compile a validator against exactly one immutable projection."
; fn seon.schema/projection-with-function-contract — [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/function-symbol :qualified-symbol] [:seon.schema/definition :seon.schema/definition] [:seon.schema/admission :map]] :seon.schema/projection] — "Validate the projection produced by one function-contract replacement."
; fn seon.schema/projection-with-schema — [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/definition :seon.schema/definition] [:seon.schema/admission :map]] :seon.schema/projection] — "Validate the projection produced by exactly one schema replacement."
; fn seon.schema/projection-without-schema — [:=> [:catn [:seon.schema/projection :seon.schema/projection] [:seon.schema/registry-key :seon.schema/registry-key]] :seon.schema/projection] — "Validate the projection produced by removing one unused schema."
; fn seon.schema/register! — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/definition :seon.schema/definition]] :seon.schema/registry-key] — "Define a new attribute so facts using it can be saved and queried."
; fn seon.schema/register-all! — [:=> [:catn [:seon.schema/kvs [:* :any]]] [:set :keyword]] — "Register multiple schemas at once from keyword/definition pairs."
; fn seon.schema/register-core-predicate! — [:=> [:cat :qualified-symbol [:fn clojure.core/ifn?]] :qualified-symbol] — "Cache one host-authored predicate function for portable Malli compilation."
; fn seon.schema/registered-schemas — [:=> [:cat] :map] — "A map of all registered domain schemas (Malli's built-ins excluded)."
; fn seon.schema/registered? — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key]] :boolean] — "Check if a schema keyword is registered."
; fn seon.schema/registration-delta-form — [:=> [:catn [:seon.schema/registration-delta :map] [:seon.schema/registry-key :seon.schema/registry-key]] [:maybe :seon.schema/definition]] — "The evaluated canonical form registered for `schema-key`, or nil."
; fn seon.schema/relink-registry! — [:=> [:cat] :boolean] — "Repoint Malli's convenience default to Seon's stable registry facade."
; fn seon.schema/restore! — [:=> [:catn [:seon.schema/before :map]] :nil] — "Revert only the schema delta represented by `before`."
; fn seon.schema/restore-state! — [:=> [:catn [:seon.schema/state :any]] :nil] — "Restore an exact schema-state snapshot captured by [[snapshot-state]]."
; fn seon.schema/schema-definition — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key]] :any] — "The raw definition for a registered schema, or nil if not registered."
; fn seon.schema/schema-removal-blockers — [:=> [:catn [:seon.schema/projection :map] [:seon.schema/registry-key :keyword]] [:map [:seon.schema.blockers/schema-keys [:set :keyword]] [:seon.schema.blockers/function-symbols [:set :qualified-symbol]]]] — "Schema and function identities preventing removal of `schema-key`."
; fn seon.schema/schemas-in-namespace — [:=> [:catn [:seon.schema/namespace-name :seon.schema/namespace-name]] :map] — "The `{keyword definition}` map of schemas registered under `ns-name`."
; fn seon.schema/sha-256 — [:=> [:cat [:sequential [:fn seon.schema/byte-array?]]] [:string {:min 64, :max 64}]] — "Lowercase SHA-256 hex digest of ordered byte arrays."
; fn seon.schema/snapshot — [:=> [:cat] :map] — "Immutable `{schema-key form}` snapshot for one eval transition."
; fn seon.schema/snapshot-state — [:=> [:cat] :any] — "Capture the exact process-local schema state for isolated test restoration."
; fn seon.schema/unregister! — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key]] :seon.schema/registry-key] — "Stage removal of one schema from the current evaluation delta."
; fn seon.schema/valid-candidate-value? — [:=> [:catn [:seon.schema/registry-key :seon.schema/registry-key] [:seon.schema/value :seon.schema/value]] :boolean] — "True when `value` satisfies `schema-key` in the current candidate."
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 1] depth=2 provenance=seon.render.ns/render-ai
(ns seon.schema.edn (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str] [seon.schema :as schema] [seon.schema.form :as schema.form]))

; schema :seon.schema.edn/files = [:vector [:string {:min 1}]]
; schema :seon.schema.edn/keys = [:int {:min 0}]
; schema :seon.schema.edn/load-request = [:map {:closed true} [:seon.schema.edn/resource-dir {:optional true} :seon.schema.edn/resource-dir]]
; schema :seon.schema.edn/loaded = [:map {:closed true} [:seon.schema.edn/files :seon.schema.edn/files] [:seon.schema.edn/keys :seon.schema.edn/keys]]
; schema :seon.schema.edn/resource-dir = [:string {:min 1}]

; fn seon.schema.edn/admit — [:=> [:cat [:map [:seon.schema/forms :map]]] [:vector :map]] — "THE one admission gate over a complete candidate population."
; fn seon.schema.edn/config-registration-defaults — [:=> [:cat :map] :map] — "Defaults declared directly by config attribute registrations."
; fn seon.schema.edn/derive-config-forms — [:=> [:cat :map] :map] — "Derive every config composite from registered config attributes."
; fn seon.schema.edn/load! — [:=> [:cat :seon.schema.edn/load-request] :seon.schema.edn/loaded] — "Merge every `<resource-dir>/*.edn` on the classpath into candidates."
; fn seon.schema.edn/packaged-forms — [:=> [:cat] :map] — "Canonical schema forms declared by Seon's bootstrap and schema resources."
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns clojure.core.async)

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns clojure.core.async.flow)

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.ns/render-ai
(ns clojure.core.async.flow.impl.graph)

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 5] depth=2 provenance=seon.render.ns/render-ai
(ns clojure.core.async.flow.spi)

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 6] depth=2 provenance=seon.render.ns/render-ai
(ns clojure.core.async.impl.protocols)

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 7] depth=2 provenance=seon.render.ns/render-ai
(ns clojure.core.protocols)

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 8] depth=2 provenance=seon.render.ns/render-ai
(ns clojure.datafy)

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 9] depth=2 provenance=seon.render.ns/render-ai
(ns clojure.test.check.generators)

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 10] depth=2 provenance=seon.render.ns/render-ai
(ns clojure.walk)

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 11] depth=2 provenance=seon.render.ns/render-ai
(ns seon.cluster (:require [clojure.core.async :as async] [clojure.core.async.flow :as flow.core] clojure.core.server [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str] [clojure.test.check.generators :as gen] [datahike.api :as d] [seon.ai :as ai] [seon.cluster.agent :as cluster.agent] [seon.cluster.instruction :as instruction] [seon.cluster.process :as cluster.process] [seon.cluster.registry :as registry] [seon.cluster.run :as run] [seon.cluster.source :as source] [seon.cluster.store :as store] [seon.cluster.wake :as wake] [seon.config :as config] [seon.error :as error] [seon.flow :as flow] [seon.fn :as seon.fn] [seon.problems :as problems] [seon.render.block :as block] [seon.render.web :as web] [seon.schema :as schema] [seon.schema.datahike :as schema.datahike] [seon.schema.edn :as schema.edn] [taoensso.timbre :as log]))

; schema :seon.cluster/cluster = [:map #:seon.db{:entity true} [:seon.cluster/name :seon.cluster/name] [:seon.cluster/config :seon.cluster/config] [:seon.cluster/instructions :seon.cluster/instructions] [:seon.cluster/toolkit :seon.cluster/toolkit]]
; schema :seon.cluster/config = :seon.db/ref
; schema :seon.cluster/created? = :boolean
; schema :seon.cluster/instructions = [:set :seon.db/ref]
; schema :seon.cluster/name = [:string {:min 1, :seon.db/identity true}]
; schema :seon.cluster/toolkit = [:set :seon.db/ref]
; schema :seon.cluster/toolkit-namespaces = [:vector :seon.ns/name]

; referenced schemas
; (register! :seon.ai/max-tokens [:int {:min 1}])
; (register! :seon.ai.retry/base-delay-ms [:int {:min 1}])
; (register! :seon.ai.retry/jitter-fraction [:double {:min 0.0, :max 1.0}])
; (register! :seon.block/count [:int {:min 0}])
; (register! :seon.boot/advertisement [:map {:closed true} [:seon.boot/cluster-name :seon.boot/cluster-name] [:seon.boot/prepl-host :seon.boot/prepl-host] [:seon.boot/prepl-port [:int {:min 1, :max 65535}]] [:seon.boot/pid :seon.boot/pid] [:seon.boot/start-instant :seon.boot/start-instant] [:seon.render.web/url {:optional true} :seon.render.web/url] [:seon.render.web/port {:optional true} :seon.render.web/port]])
; (register! :seon.boot/cluster-name [:string {:min 1}])
; (register! :seon.boot/config [:map {:closed true} [:seon.boot/cluster-name :seon.boot/cluster-name] [:seon.boot/root :seon.boot/root] [:seon.boot/prepl-host :seon.boot/prepl-host] [:seon.boot/prepl-port :seon.boot/prepl-port] [:seon.boot/log-dir :seon.boot/log-dir] [:seon.boot/store-dir :seon.boot/store-dir]])
; (register! :seon.boot/executors [:map {:closed true} [:compute [:fn #:gen{:gen seon.flow/executor-generator} seon.flow/executor?]] [:io [:fn #:gen{:gen seon.flow/executor-generator} seon.flow/executor?]]])
; (register! :seon.boot/instance [:map {:closed true} [:seon.boot/config :seon.boot/config] [:seon.boot/advertisement :seon.boot/advertisement] [:seon.boot/prepl-server [:fn #:gen{:gen seon.cluster/socket-server-generator} seon.cluster/socket-server?]] [:seon.boot/executors :seon.boot/executors] [:seon.store/store {:optional true} :seon.store/store] [:seon.boot/cluster-connection {:optional true} :seon.store/connection] [:seon.boot/config-result {:optional true} :seon.reconcile/result] [:seon.boot/recovered-runs {:optional true} :seon.boot/recovered-runs] [:seon.boot/recovery-operations {:optional true} :seon.boot/recovery-operations] [:seon.cluster.loop/cluster {:optional true} :seon.cluster.loop/cluster] [:seon.flow/graph {:optional true} :seon.flow/graph] [:seon.flow/error-fanout {:optional true} :seon.flow/error-fanout] [:seon.error/drops {:optional true} :seon.error/drops] [:seon.render.web/view {:optional true} :seon.render.web/view] [:seon.render.web/served {:optional true} :seon.render.web/server] [:seon.cluster.agent/routing {:optional true} :seon.cluster.agent/routing] [:seon.boot/ready-ms {:optional true} [:int {:min 0}]]])
; (register! :seon.boot/log-dir [:string {:min 1}])
; (register! :seon.boot/overrides [:map {:closed true} [:seon.boot/cluster-name {:optional true} :seon.boot/cluster-name] [:seon.boot/root {:optional true} :seon.boot/root] [:seon.boot/prepl-host {:optional true} :seon.boot/prepl-host] [:seon.boot/prepl-port {:optional true} :seon.boot/prepl-port] [:seon.boot/log-dir {:optional true} :seon.boot/log-dir] [:seon.boot/store-dir {:optional true} :seon.boot/store-dir]])
; (register! :seon.boot/pid [:int {:min 1}])
; (register! :seon.boot/prepl-host [:string {:min 1}])
; (register! :seon.boot/prepl-port [:int {:min 0, :max 65535}])
; (register! :seon.boot/readiness [:map {:closed true} [:seon.boot/cluster-name :seon.boot/cluster-name] [:seon.boot/pid :seon.boot/pid] [:seon.boot/prepl-port [:int {:min 1, :max 65535}]] [:seon.cluster.agent/count :seon.cluster.agent/count] [:seon.block/count :seon.block/count] [:seon.problems/problems :seon.problems/problems] [:seon.render.web/url {:optional true} :seon.render.web/url] [:seon.render.web/wanted-port {:optional true} :seon.render.web/wanted-port] [:seon.boot/recovered-runs {:optional true} :seon.boot/recovered-runs] [:seon.boot/ready-ms {:optional true} [:int {:min 0}]] [:seon.instrument/instrumented {:optional true} [:int {:min 0}]]])
; (register! :seon.boot/recovered-runs [:int {:min 0}])
; (register! :seon.boot/root [:string {:min 1}])
; (register! :seon.boot/start-instant :inst)
; (register! :seon.boot/start-request [:map {:closed true} [:seon.boot/cluster-name {:optional true} :seon.boot/cluster-name] [:seon.boot/root {:optional true} :seon.boot/root] [:seon.boot/prepl-host {:optional true} :seon.boot/prepl-host] [:seon.boot/prepl-port {:optional true} :seon.boot/prepl-port] [:seon.boot/log-dir {:optional true} :seon.boot/log-dir] [:seon.boot/store-dir {:optional true} :seon.boot/store-dir] [:seon.config/manifest {:optional true} :seon.config/manifest] [:seon.config/environment {:optional true} :seon.config/manifest]])
; (register! :seon.boot/store-dir [:string {:min 1}])
; (register! :seon.cluster.agent/count [:int {:min 0}])
; (register! :seon.cluster.agent/creation-request [:map {:closed true} [:seon.cluster.agent/id :seon.cluster.agent/id] [:seon.ns/name :seon.ns/name] [:seon.cluster/name :seon.cluster/name]])
; (register! :seon.cluster.agent/id [:string {:min 1, :seon.db/identity true}])
; (register! :seon.cluster.registry/branch-result [:map {:closed true} [:seon.store/branch :seon.store/branch] [:seon.cluster/created? :seon.cluster/created?]])
; (register! :seon.cluster.run/process [:string {:min 1}])
; (register! :seon.config/manifest [:map {:closed true} [:seon.config.ai.backup/api-key-variable {:optional true} [:or :seon.config.ai.backup/api-key-variable [:= :seon.config/absent]]] [:seon.config.ai.backup/endpoint {:optional true} [:or :seon.config.ai.backup/endpoint [:= :seon.config/absent]]] [:seon.config.ai.backup/model {:optional true} [:or :seon.config.ai.backup/model [:= :seon.config/absent]]] [:seon.config.ai.backup/timeout-ms {:optional true} [:or :seon.config.ai.backup/timeout-ms [:= :seon.config/absent]]] [:seon.config.ai.retry/base-delay-ms {:optional true} [:or :seon.config.ai.retry/base-delay-ms [:= :seon.config/absent]]] [:seon.config.ai.retry/jitter-fraction {:optional true} [:or :seon.config.ai.retry/jitter-fraction [:= :seon.config/absent]]] [:seon.config.ai.retry/maximum-delay-ms {:optional true} [:or :seon.config.ai.retry/maximum-delay-ms [:= :seon.config/absent]]] [:seon.config.ai.retry/maximum-retries {:optional true} [:or :seon.config.ai.retry/maximum-retries [:= :seon.config/absent]]] [:seon.config.ai.retry/maximum-total-delay-ms {:optional true} [:or :seon.config.ai.retry/maximum-total-delay-ms [:= :seon.config/absent]]] [:seon.config.ai.retry/multiplier {:optional true} [:or :seon.config.ai.retry/multiplier [:= :seon.config/absent]]] [:seon.config.ai/api-key-variable {:optional true} [:or :seon.config.ai/api-key-variable [:= :seon.config/absent]]] [:seon.config.ai/endpoint {:optional true} [:or :seon.config.ai/endpoint [:= :seon.config/absent]]] [:seon.config.ai/max-tokens {:optional true} [:or :seon.config.ai/max-tokens [:= :seon.config/absent]]] [:seon.config.ai/model {:optional true} [:or :seon.config.ai/model [:= :seon.config/absent]]] [:seon.config.ai/no-auth {:optional true} [:or :seon.config.ai/no-auth [:= :seon.config/absent]]] [:seon.config.ai/timeout-ms {:optional true} [:or :seon.config.ai/timeout-ms [:= :seon.config/absent]]] [:seon.config.error/escalate-to {:optional true} [:or :seon.config.error/escalate-to [:= :seon.config/absent]]] [:seon.config.error/recurrence-limit {:optional true} [:or :seon.config.error/recurrence-limit [:= :seon.config/absent]]] [:seon.config.eval.result/max-collection {:optional true} [:or :seon.config.eval.result/max-collection [:= :seon.config/absent]]] [:seon.config.eval.result/max-depth {:optional true} [:or :seon.config.eval.result/max-depth [:= :seon.config/absent]]] [:seon.config.eval.result/max-nodes {:optional true} [:or :seon.config.eval.result/max-nodes [:= :seon.config/absent]]] [:seon.config.eval.result/max-string {:optional true} [:or :seon.config.eval.result/max-string [:= :seon.config/absent]]] [:seon.config.eval/time-limit-ms {:optional true} [:or :seon.config.eval/time-limit-ms [:= :seon.config/absent]]] [:seon.config.flow.compute/concurrency {:optional true} [:or :seon.config.flow.compute/concurrency [:= :seon.config/absent]]] [:seon.config.flow.compute/queue-depth {:optional true} [:or :seon.config.flow.compute/queue-depth [:= :seon.config/absent]]] [:seon.config.message/max-chain {:optional true} [:or :seon.config.message/max-chain [:= :seon.config/absent]]] [:seon.config.render/coalesce-ms {:optional true} [:or :seon.config.render/coalesce-ms [:= :seon.config/absent]]] [:seon.config.run/max-episode-runs {:optional true} [:or :seon.config.run/max-episode-runs [:= :seon.config/absent]]] [:seon.config.web/port {:optional true} [:or :seon.config.web/port [:= :seon.config/absent]]] [:seon.config/on-core-error {:optional true} [:or :seon.config/on-core-error [:= :seon.config/absent]]]])
; (register! :seon.config/on-core-error [:enum #:seon.config{:dial true} :record :panic])
; (register! :seon.config.ai/api-key-variable [:string {:min 1}])
; (register! :seon.config.ai/endpoint [:string {:min 1}])
; (register! :seon.config.ai/max-tokens :seon.ai/max-tokens)
; (register! :seon.config.ai/model [:string {:min 1}])
; (register! :seon.config.ai/no-auth [:= #:seon.config{:optional true} true])
; (register! :seon.config.ai/timeout-ms [:int {:min 1}])
; (register! :seon.config.ai.backup/api-key-variable [:string {:min 1, :seon.config/optional true}])
; (register! :seon.config.ai.backup/endpoint [:string {:min 1, :seon.config/optional true}])
; (register! :seon.config.ai.backup/model [:string {:min 1, :seon.config/optional true}])
; (register! :seon.config.ai.backup/timeout-ms [:int {:min 1, :seon.config/optional true}])
; (register! :seon.config.ai.retry/base-delay-ms :seon.ai.retry/base-delay-ms)
; (register! :seon.config.ai.retry/jitter-fraction :seon.ai.retry/jitter-fraction)
; (register! :seon.config.ai.retry/maximum-delay-ms :seon.ai.retry/maximum-delay-ms)
; 40+ referenced schemas capped; more reachable via the db

; fn seon.cluster/banner — [:=> [:cat :seon.boot/readiness] :string] — "`readiness` as the block a person reads at a terminal."
; fn seon.cluster/cluster-paths — [:=> [:cat :seon.boot/root :seon.boot/cluster-name] [:map {:closed true} [:seon.boot/cluster-dir :string] [:seon.boot/advertisement-file :string] [:seon.boot/log-dir :string]]] — "Derive every per-cluster path from (root, cluster-name)."
; fn seon.cluster/ensure-cluster-entity! — [:=> [:cat :seon.store/branch-connection :seon.cluster/name :seon.db.process/id] :nil] — "Exactly converge the branch-local cluster entity's shared base set."
; fn seon.cluster/ensure-entity! — [:=> [:cat :seon.store/branch-connection :seon.db.process/id :seon.cluster.agent/creation-request] [:or [:map] :seon.error/value]] — "Create one absent agent atomically; an existing agent resumes untouched."
; fn seon.cluster/ensure-entity-call — [:=> [:cat :seon.db/database-value :seon.cluster.agent/creation-request] :seon.store/transaction-data] — "Create an absent agent inside the transaction; otherwise change nothing."
; fn seon.cluster/populate-source! — [:=> [:cat [:map [:seon.store/branch-connection :seon.store/branch-connection]]] :nil] — "The default `current-src` content: this code's schema and program rows."
; fn seon.cluster/process-identity — [:=> [:cat :seon.boot/advertisement] :seon.cluster.run/process] — "This process's identity as a run holder: `<pid>-<start-millis>`."
; fn seon.cluster/read-advertisement — [:=> [:cat :seon.boot/root :seon.boot/cluster-name] [:maybe :seon.boot/advertisement]] — "Read and validate one cluster's advertisement, or nil."
; fn seon.cluster/readiness — [:=> [:cat :seon.boot/instance] :seon.boot/readiness] — "The boot banner, DERIVED from a started instance. Never duplicated."
; fn seon.cluster/refork! — [:=> [:cat :seon.boot/instance] :seon.cluster.registry/branch-result] — "Destroy one cluster branch and refork the published source commit."
; fn seon.cluster/refresh-source! — [:function [:=> [:cat :seon.boot/root] :seon.source/published] [:=> [:cat :seon.boot/root [:vector :string]] :seon.source/published]] — "Publish the current source tree onto the one `current-src` branch."
; fn seon.cluster/resolve-bootstrap — [:=> [:cat :seon.boot/overrides] :seon.boot/config] — "Resolve overrides into one complete bootstrap configuration."
; fn seon.cluster/root-executors — [:=> [:cat] :seon.boot/executors] — "The process root's two shared executors."
; fn seon.cluster/socket-server? — [:=> [:cat :seon.schema/value] :boolean] — "True for the java.net.ServerSocket an io-prepl listens on."
; fn seon.cluster/source-artifact-file — [:=> [:cat :seon.boot/root] :string] — "The per-store artifact that describes the published source commit."
; fn seon.cluster/start! — [:=> [:cat :seon.boot/start-request] :seon.boot/instance] — "Start one cluster instance in this JVM, REPL FIRST, then the tower."
; fn seon.cluster/stop! — [:=> [:cat :seon.boot/instance] :nil] — "Stop exactly THIS instance, instance-addressed never name-addressed."
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 12] depth=2 provenance=seon.render.ns/render-ai
(ns seon.cluster.agent (:require [clojure.core.async :as async] [clojure.core.async.flow :as flow] [clojure.core.async.impl.protocols :as async.protocols] [datahike.api :as d] [seon.cluster.loop :as cluster.loop] [seon.cluster.work :as work] [seon.flow :as seon.flow] [seon.schema.edn :as schema.edn]))

; schema :seon.cluster.agent/agent = [:map {:seon.db/entity true, :seon.render/ai seon.render.agent/agent-ai, :seon.render/html seon.render.agent/agent-html} [:seon.cluster.agent/id :seon.cluster.agent/id] [:seon.cluster.agent/namespace {:optional true} :seon.cluster.agent/namespace] [:seon.cluster.agent/run {:optional true} :seon.cluster.agent/run]]
; schema :seon.cluster.agent/arm-request = [:map {:closed true} [:seon.cluster.loop/cluster :seon.cluster.loop/cluster] [:seon.cluster.agent/id :seon.cluster.agent/id] [:seon.cluster.agent/routing :seon.cluster.agent/routing]]
; schema :seon.cluster.agent/armed = [:map {:closed true} [:seon.cluster.agent/id :seon.cluster.agent/id] [:seon.cluster.agent/eid :int] [:seon.flow/graph :seon.flow/graph] [:seon.cluster.wake/channel :seon.flow/channel] [:seon.cluster.loop/completion :seon.flow/channel]]
; schema :seon.cluster.agent/blocks = [:set #:seon.db{:component true} :seon.db/ref]
; schema :seon.cluster.agent/blueprint-request = [:map {:closed true} [:seon.cluster.loop/cluster :seon.cluster.loop/cluster] [:seon.cluster.agent/id :seon.cluster.agent/id]]
; schema :seon.cluster.agent/cluster = :seon.db/ref
; schema :seon.cluster.agent/context-links = [:map #:seon.db{:entity true} [:seon.cluster.agent/id :seon.cluster.agent/id] [:seon.cluster.agent/cluster :seon.cluster.agent/cluster] [:seon.cluster.agent/instructions {:optional true} :seon.cluster.agent/instructions]]
; schema :seon.cluster.agent/count = [:int {:min 0}]
; schema :seon.cluster.agent/creation-request = [:map {:closed true} [:seon.cluster.agent/id :seon.cluster.agent/id] [:seon.ns/name :seon.ns/name] [:seon.cluster/name :seon.cluster/name]]
; schema :seon.cluster.agent/creation-tx = [:vector :map]
; schema :seon.cluster.agent/disarm-request = [:map {:closed true} [:seon.cluster.agent/id :seon.cluster.agent/id] [:seon.cluster.agent/routing :seon.cluster.agent/routing]]
; schema :seon.cluster.agent/eid = :int
; schema :seon.cluster.agent/id = [:string {:min 1, :seon.db/identity true}]
; schema :seon.cluster.agent/instructions = [:set :seon.db/ref]
; schema :seon.cluster.agent/namespace = [:and #:seon.db{:unique true} :seon.db/ref]
; schema :seon.cluster.agent/routing = [:fn #:gen{:gen seon.flow/atom-reference-generator} seon.flow/atom-reference?]
; schema :seon.cluster.agent/run = :seon.db/ref

; referenced schemas
; (register! :seon.ai/api-key-variable [:string {:min 1}])
; (register! :seon.ai/endpoint [:string {:min 1}])
; (register! :seon.ai/max-tokens [:int {:min 1}])
; (register! :seon.ai/model [:string {:min 1}])
; (register! :seon.ai/target [:or [:map {:closed true} [:seon.ai/endpoint :seon.ai/endpoint] [:seon.ai/model :seon.ai/model] [:seon.ai/max-tokens :seon.ai/max-tokens] [:seon.ai/api-key-variable :seon.ai/api-key-variable] [:seon.ai/timeout-ms :seon.ai/timeout-ms]] [:map {:closed true} [:seon.ai/endpoint :seon.ai/endpoint] [:seon.ai/model :seon.ai/model] [:seon.ai/max-tokens :seon.ai/max-tokens] [:seon.config.ai/no-auth :seon.config.ai/no-auth] [:seon.ai/timeout-ms :seon.ai/timeout-ms]]])
; (register! :seon.ai/timeout-ms [:int {:min 1}])
; (register! :seon.ai.retry/base-delay-ms [:int {:min 1}])
; (register! :seon.ai.retry/jitter-fraction [:double {:min 0.0, :max 1.0}])
; (register! :seon.ai.retry/maximum-delay-ms [:int {:min 1}])
; (register! :seon.ai.retry/maximum-retries [:int {:min 0}])
; (register! :seon.ai.retry/maximum-total-delay-ms [:int {:min 0}])
; (register! :seon.ai.retry/multiplier [:double {:min 1.0}])
; (register! :seon.ai.retry/strategy [:map {:closed true} [:seon.ai.retry/base-delay-ms :seon.ai.retry/base-delay-ms] [:seon.ai.retry/multiplier :seon.ai.retry/multiplier] [:seon.ai.retry/jitter-fraction :seon.ai.retry/jitter-fraction] [:seon.ai.retry/maximum-delay-ms :seon.ai.retry/maximum-delay-ms] [:seon.ai.retry/maximum-retries :seon.ai.retry/maximum-retries] [:seon.ai.retry/maximum-total-delay-ms :seon.ai.retry/maximum-total-delay-ms]])
; (register! :seon.cluster/name [:string {:min 1, :seon.db/identity true}])
; (register! :seon.cluster.loop/cluster [:map {:closed true} [:seon.store/branch-connection :seon.store/branch-connection] [:seon.cluster.run/process :seon.cluster.run/process] [:seon.cluster.wake/channel :seon.flow/channel] [:seon.cluster.loop/stream-channel {:optional true} :seon.flow/channel] [:seon.cluster.loop/completion :seon.flow/channel] [:seon.ai/primary :seon.ai/target] [:seon.ai/backup {:optional true} :seon.ai/target] [:seon.ai.retry/strategy :seon.ai.retry/strategy] [:seon.cluster.loop/evaluate :qualified-symbol] [:seon.sci.admit/caps :seon.sci.admit/caps] [:seon.config.eval/time-limit-ms :seon.config.eval/time-limit-ms] [:seon.config/on-core-error :seon.config/on-core-error] [:seon.config.error/recurrence-limit :seon.config.error/recurrence-limit] [:seon.config.message/max-chain :seon.config.message/max-chain] [:seon.config.error/escalate-to {:optional true} :seon.config.error/escalate-to] [:clojure.core.async.flow/pid {:optional true} :keyword]])
; (register! :seon.cluster.run/process [:string {:min 1}])
; (register! :seon.config/on-core-error [:enum #:seon.config{:dial true} :record :panic])
; (register! :seon.config.ai/no-auth [:= #:seon.config{:optional true} true])
; (register! :seon.config.error/escalate-to [:string {:min 1, :seon.config/optional true}])
; (register! :seon.config.error/recurrence-limit [:int {:min 1}])
; (register! :seon.config.eval/time-limit-ms [:int {:min 1}])
; (register! :seon.config.eval.result/max-collection [:int {:min 1}])
; (register! :seon.config.eval.result/max-depth [:int {:min 1}])
; (register! :seon.config.eval.result/max-nodes [:int {:min 1}])
; (register! :seon.config.eval.result/max-string [:int {:min 1}])
; (register! :seon.config.message/max-chain [:int {:min 1}])
; (register! :seon.db/database-value [:fn #:gen{:gen seon.cluster.store/database-value-generator} seon.cluster.store/database-value?])
; (register! :seon.flow/channel [:fn #:gen{:gen seon.flow/channel-generator} seon.flow/channel?])
; (register! :seon.flow/graph [:fn #:gen{:gen seon.flow/graph-generator} seon.flow/graph?])
; (register! :seon.ns/name [:symbol #:seon.db{:identity true}])
; (register! :seon.sci.admit/caps [:map {:closed true} [:seon.config.eval.result/max-depth :seon.config.eval.result/max-depth] [:seon.config.eval.result/max-collection :seon.config.eval.result/max-collection] [:seon.config.eval.result/max-string :seon.config.eval.result/max-string] [:seon.config.eval.result/max-nodes :seon.config.eval.result/max-nodes]])
; (register! :seon.store/branch-connection [:fn #:gen{:gen seon.cluster.store/connection-generator} seon.cluster.store/connection?])

; fn seon.cluster.agent/arm! — [:=> [:cat :seon.cluster.agent/arm-request] :seon.cluster.agent/armed] — "Arm one agent's graph: stamp → start → resume → route → prime."
; fn seon.cluster.agent/armed — [:=> [:cat :seon.cluster.agent/routing :seon.cluster.agent/id] [:maybe :seon.cluster.agent/armed]] — "The armed entry for `agent-id`, or nil."
; fn seon.cluster.agent/armer-step — [:function [:=> [:cat] [:map]] [:=> [:cat :map] :map] [:=> [:cat :map :keyword] :map] [:=> [:cat :map :keyword :any] [:tuple :map [:maybe [:map-of :keyword [:vector :some]]]]]] — "The armer transform, in Flow's four arities."
; fn seon.cluster.agent/channels — [:=> [:cat :seon.cluster.agent/routing] [:map-of :int :seon.flow/channel]] — "The current entity-id → mailbox-channel map, for `wake/route!`."
; fn seon.cluster.agent/creation-tx — [:=> [:cat :seon.cluster.agent/creation-request] :seon.cluster.agent/creation-tx] — "Create one agent with its namespace and cluster connection."
; fn seon.cluster.agent/disarm! — [:=> [:cat :seon.cluster.agent/disarm-request] :nil] — "Orderly stop of one agent's graph, idempotent."
; fn seon.cluster.agent/fenced-route? — [:=> [:cat :seon.cluster.agent/routing :int :seon.flow/channel] :boolean] — "True when `channel` is the CURRENT route for `agent-eid` and closed."
; fn seon.cluster.agent/fenced? — [:=> [:cat :seon.cluster.agent/routing :seon.cluster.agent/id] :boolean] — "True when this agent is QUARANTINED: armed, routed, mailbox closed."
; fn seon.cluster.agent/graph-definition — [:=> [:cat :seon.cluster.agent/blueprint-request] :map] — "The ONE blueprint: (agent-id, handle) → a `create-flow` definition."
; fn seon.cluster.agent/mailbox-step — [:function [:=> [:cat] [:map]] [:=> [:cat :map] :map] [:=> [:cat :map :keyword] :map] [:=> [:cat :map :keyword :any] [:tuple :map [:maybe [:map-of :keyword [:vector :some]]]]]] — "The mailbox transform, in Flow's four arities."
; fn seon.cluster.agent/owner-of — [:=> [:cat :seon.db/database-value :seon.ns/name] [:maybe :seon.cluster.agent/id]] — "The agent id assigned to `namespace-name`, or nil."
; fn seon.cluster.agent/routing — [:=> [:cat] :seon.cluster.agent/routing] — "A fresh routing entry: the process-local map atom arming rebuilds."
; fn seon.cluster.agent/turn-step — [:function [:=> [:cat] [:map]] [:=> [:cat :map] :map] [:=> [:cat :map :keyword] :map] [:=> [:cat :map :keyword :any] [:tuple :map [:maybe [:map-of :keyword [:vector :some]]]]]] — "The turn transform, in Flow's four arities: ONE episode pass."
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 13] depth=2 provenance=seon.render.ns/render-ai
(ns seon.cluster.loop (:require [clojure.core.async :as async] [clojure.core.async.flow :as flow] [datahike.api :as d] [sci.core :as sci] [seon.ai :as ai] [seon.cluster.message :as message] [seon.cluster.prompt :as prompt] [seon.cluster.reply :as reply] [seon.cluster.run :as run] [seon.cluster.store :as store] [seon.cluster.wake :as wake] [seon.cluster.work :as work] [seon.context :as context] [seon.error :as error] [seon.flow :as seon.flow] [seon.fn.analyzer :as fn.analyzer] [seon.problems :as problems] [seon.render :as render] [seon.schema :as schema] [seon.schema.edn :as schema.edn] [seon.sci.admit :as admit] [seon.sci.eval :as sci.eval]))

; schema :seon.cluster.loop/cluster = [:map {:closed true} [:seon.store/branch-connection :seon.store/branch-connection] [:seon.cluster.run/process :seon.cluster.run/process] [:seon.cluster.wake/channel :seon.flow/channel] [:seon.cluster.loop/stream-channel {:optional true} :seon.flow/channel] [:seon.cluster.loop/completion :seon.flow/channel] [:seon.ai/primary :seon.ai/target] [:seon.ai/backup {:optional true} :seon.ai/target] [:seon.ai.retry/strategy :seon.ai.retry/strategy] [:seon.cluster.loop/evaluate :qualified-symbol] [:seon.sci.admit/caps :seon.sci.admit/caps] [:seon.config.eval/time-limit-ms :seon.config.eval/time-limit-ms] [:seon.config/on-core-error :seon.config/on-core-error] [:seon.config.error/recurrence-limit :seon.config.error/recurrence-limit] [:seon.config.message/max-chain :seon.config.message/max-chain] [:seon.config.error/escalate-to {:optional true} :seon.config.error/escalate-to] [:clojure.core.async.flow/pid {:optional true} :keyword]]
; schema :seon.cluster.loop/completion = :seon.flow/channel
; schema :seon.cluster.loop/evaluation = [:map {:closed true} [:seon.sci.admit/value :any] [:seon.cluster.eval/result-edn {:optional true} :seon.cluster.eval/result-edn] [:seon.cluster.eval/error {:optional true} :seon.cluster.eval/error] [:seon.cluster.eval/interrupted-at {:optional true} :seon.cluster.eval/interrupted-at] [:seon.cluster.eval/output {:optional true} :seon.cluster.eval/output] [:seon.cluster.eval/ns {:optional true} :seon.cluster.eval/ns] [:seon.sci.eval/ending-ns {:optional true} :seon.sci.eval/ending-ns] [:seon.sci.eval/program-row {:optional true} :seon.sci.eval/program-row]]
; schema :seon.cluster.loop/forms-run = [:int {:min 0}]
; schema :seon.cluster.loop/outcome = [:enum :closed :released :error :interrupted]
; schema :seon.cluster.loop/terminal-request = [:map {:closed true} [:seon.cluster.run/id :seon.cluster.run/id] [:seon.cluster.run/process :seon.cluster.run/process] [:seon.cluster.run.form/ordinal :seon.cluster.run.form/ordinal] [:seon.cluster.eval/result-edn {:optional true} :seon.cluster.eval/result-edn] [:seon.cluster.eval/error {:optional true} :seon.cluster.eval/error] [:seon.cluster.eval/interrupted-at {:optional true} :seon.cluster.eval/interrupted-at] [:seon.error/kind {:optional true} :seon.error/kind] [:seon.cluster.eval/output {:optional true} :seon.cluster.eval/output] [:seon.cluster.eval/ns {:optional true} :seon.cluster.eval/ns] [:seon.sci.eval/program-row {:optional true} :seon.sci.eval/program-row] [:my.run/value {:optional true} :my.run/value]]
; schema :seon.cluster.loop/turn-report = [:map {:closed true} [:seon.cluster.run/id {:optional true} :seon.cluster.run/id] [:seon.cluster.agent/id :seon.cluster.agent/id] [:seon.cluster.work/situation :seon.cluster.work/situation] [:seon.cluster.loop/forms-run [:int {:min 0}]] [:seon.cluster.loop/outcome [:enum :closed :released :error :interrupted]]]
; schema :seon.cluster.loop/turn-request = [:map {:closed true} [:seon.cluster.loop/cluster :seon.cluster.loop/cluster] [:seon.cluster.work/next :seon.cluster.work/next]]

; referenced schemas
; (register! :my.message/about [:string {:min 1}])
; (register! :my.message/content [:string {:min 1}])
; (register! :my.message/declination [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]])
; (register! :my.message/message [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]])
; (register! :my.message/reason [:string {:min 1}])
; (register! :my.message/to [:string {:min 1}])
; (register! :my.message/value [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]])
; (register! :my.run/completed [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]])
; (register! :my.run/note [:string {:min 1}])
; (register! :my.run/result [:string {:min 1}])
; (register! :my.run/value [:or :my.run/wait :my.run/completed])
; (register! :my.run/wait [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]])
; (register! :seon.cluster.reply/form [:map {:closed true} [:seon.cluster.run.form/source :seon.cluster.run.form/source] [:seon.ns/name {:optional true} :seon.ns/name]])
; (register! :seon.cluster.run.form/source [:string {:min 1}])
; (register! :seon.ns/name [:symbol #:seon.db{:identity true}])

; fn seon.cluster.loop/committed-attributes — [:=> [:cat] [:set :keyword]] — "Every attribute the loop's own transactions assert."
; fn seon.cluster.loop/disposition — [:=> [:cat :any] [:maybe :my.run/value]] — "The disposition an admitted eval value carries, or nil."
; fn seon.cluster.loop/lint-form — [:=> [:cat [:map {:closed true} [:seon.ns/name :seon.ns/name] [:seon.cluster.loop/namespace-row {:optional true} :map] [:seon.cluster.loop/available-functions {:optional true} [:vector :map]] [:seon.cluster.loop/source :seon.cluster.reply/form]]] :seon.cluster.reply/form] — "Replace one error-bearing source form with a flat lint refusal."
; fn seon.cluster.loop/messages — [:=> [:cat :any] [:maybe :my.message/value]] — "The messages an admitted eval value asks to send, or nil."
; fn seon.cluster.loop/settle-interruption! — [:=> [:cat :seon.cluster.loop/cluster :seon.cluster.run/id :inst] :boolean] — "Bury one orphaned run so its agent stops being busy."
; fn seon.cluster.loop/terminal-tx — [:=> [:cat :seon.cluster.loop/terminal-request :inst] [:vector :some]] — "The ONE transaction ending a form: its receipt AND the disposition."
; fn seon.cluster.loop/turn — [:=> [:cat :seon.cluster.loop/turn-request :inst] :seon.cluster.loop/turn-report] — "Run one turn to its next durable boundary; returns the turn report."
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 14] depth=2 provenance=seon.render.ns/render-ai
(ns seon.cluster.agent-test (:require [clojure.core.async :as async] [clojure.core.async.flow :as flow] [clojure.string :as str] clojure.test [clojure.test.check :as tc] [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop] [datahike.api :as d] [my.run :as my.run] [seon.ai :as ai] [seon.cluster :as cluster] [seon.cluster.agent :as agent] [seon.cluster.run :as run] [seon.cluster.wake :as wake] [seon.cluster.work :as work] [seon.flow :as seon.flow] [seon.problems :as problems] [seon.schema :as schema] [seon.schema.datahike :as schema.datahike]))

; fn seon.cluster.agent-test/fake-evaluate — <no contract> — "The injected evaluator, decided by the SOURCE it is handed — no"
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 15] depth=2 provenance=seon.render.ns/render-ai
(ns seon.cluster.boot-test (:require [clojure.core.async :as async] [clojure.core.async.flow :as flow] [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str] clojure.test [clojure.test.check :as tc] [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop] [datahike.api :as d] [seon.cluster :as cluster] seon.cluster.agent [seon.cluster.process :as cluster.process] [seon.cluster.registry :as registry] [seon.cluster.source :as source] [seon.cluster.store :as store] [seon.cluster.work :as work] [seon.config :as config] [seon.flow :as seon.flow] [seon.fn :as seon.fn] [seon.program :as program] [seon.schema :as schema] [seon.schema.edn :as schema.edn] [seon.test-support :as test-support]))

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 16] depth=2 provenance=seon.render.ns/render-ai
(ns seon.cluster.turn-test (:require [clojure.core.async :as async] [clojure.core.async.flow :as flow.core] [clojure.edn :as edn] [clojure.string :as str] clojure.test [clojure.test.check :as tc] [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop] [datahike.api :as d] [my.run :as my.run] [sci.core :as sci.core] [seon.ai :as ai] [seon.cluster :as cluster] [seon.cluster.loop :as cluster.loop] [seon.cluster.message :as message] [seon.cluster.prompt :as prompt] [seon.cluster.run :as run] [seon.cluster.store :as store] [seon.cluster.work :as work] [seon.error :as error] [seon.flow :as seon.flow] [seon.instrument :as instrument] [seon.render :as render] [seon.render.web :as web] [seon.schema :as schema] [seon.schema.datahike :as schema.datahike] [seon.sci.eval :as sci.eval] [seon.test-support :as test-support]))

; fn seon.cluster.turn-test/fake-evaluate — <no contract> — "The stand-in evaluator, for the cases that pin an exact value."
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 17] depth=2 provenance=seon.render.ns/render-ai
(ns seon.config-application-test (:require [clojure.edn :as edn] [clojure.java.io :as io] clojure.test [seon.cluster :as cluster] [seon.config :as config] [seon.flow :as flow] [seon.render.web :as web]))

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 18] depth=2 provenance=seon.render.ns/render-ai
(ns seon.context-test (:require [clojure.set :as set] [clojure.string :as str] clojure.test [clojure.test.check :as tc] [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop] [datahike.api :as d] [seon.ai :as ai] [seon.cluster :as cluster] [seon.cluster.loop :as cluster.loop] [seon.cluster.message :as message] [seon.cluster.prompt :as prompt] [seon.cluster.store :as store] [seon.cluster.work :as work] [seon.context :as context] [seon.flow :as seon.flow] [seon.render :as render] [seon.render.block :as block] [seon.render.hiccup :as hiccup] [seon.schema :as schema] [seon.test-support :as support]))

; fn seon.context-test/echo-ai — <no contract> — "Says its planted marker content; omits itself when none is planted."
; fn seon.context-test/flat-error-ai — <no contract> — "Returns a flat error VALUE — failure as data, never a throw."
; fn seon.context-test/omitting-ai — <no contract>
; fn seon.context-test/omitting-html — <no contract>
; fn seon.context-test/scoped-ai — <no contract> — "Reads exactly the messages addressed to the unit's agent, plus the"
; fn seon.context-test/speaking-ai — <no contract>
; fn seon.context-test/speaking-html — <no contract>
; fn seon.context-test/throwing-ai — <no contract>
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 19] depth=2 provenance=seon.render.ns/render-ai
(ns seon.flow.loop-test (:require [clojure.core.async :as async] [clojure.core.async.flow :as flow] [clojure.set :as set] clojure.test [clojure.test.check :as tc] [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop] [datahike.api :as d] [seon.flow :as sut] [seon.test-support :as test-support]))

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 20] depth=2 provenance=seon.render.ns/render-ai
(ns seon.flow-configuration-test (:require [clojure.core.async :as async] [clojure.core.async.flow.spi :as flow.spi] clojure.test seon.cluster seon.cluster.agent [seon.flow :as sut]))

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 21] depth=2 provenance=seon.render.ns/render-ai
(ns seon.flow-test (:require [clojure.core.async :as async] [clojure.core.async.flow :as flow] [clojure.core.async.flow-monitor :as flow-monitor] [clojure.datafy :as datafy] [clojure.string :as str] clojure.test [datahike.api :as d] [datahike.core :as datahike] [malli.core :as m] [malli.generator :as mg] [seon.cluster.loop :as cluster.loop] [seon.config :as config] [seon.flow :as sut] [seon.schema :as schema] [seon.sci.eval :as sci.eval] [seon.test-support :as test-support]))

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 22] depth=2 provenance=seon.render.ns/render-ai
(ns seon.gen.loop-test (:require [clojure.core.async :as async] [clojure.string :as str] clojure.test [datahike.api :as d] [seon.ai :as ai] [seon.cluster :as cluster] [seon.cluster.agent :as agent] [seon.cluster.loop :as cluster.loop] [seon.cluster.message :as message] [seon.cluster.work :as work] [seon.flow :as seon.flow] [seon.test-support :as test-support]))

;; no definitions yet.
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 23] depth=2 provenance=seon.render.ns/render-ai
(ns seon.instrument-test (:require clojure.test [malli.instrument :as mi] [seon.dev.docstring :as docstring] [seon.dev.markdown :as markdown] [seon.error :as error] [seon.flow :as flow] [seon.instrument :as instrument] seon.schema))

; fn seon.instrument-test/doubled — [:=> [:cat :int] :int]
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 24] depth=2 provenance=seon.render.ns/render-ai
(ns seon.render.web-test (:require [clojure.core.async :as async] [clojure.core.async.flow :as flow.core] [clojure.string :as str] clojure.test [clojure.test.check :as tc] [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop] [datahike.api :as d] [org.httpkit.server :as http] [seon.cluster.agent :as cluster.agent] [seon.cluster.wake :as wake] [seon.config :as config] [seon.flow :as flow] [seon.render.agent :as render.agent] [seon.render.block :as block] [seon.render.root :as root] [seon.render.web :as web] [seon.test-support :as support]))

; fn seon.render.web-test/banner-html — <no contract> — "Reads nothing, so it must never be repainted."
; fn seon.render.web-test/broken-html — <no contract>
; fn seon.render.web-test/counter-html — <no contract> — "Reads the agent count, so it repaints exactly when that changes."
; fn seon.render.web-test/omitted-html — <no contract> — "Returns nil when this block has nothing to say."
; fn seon.render.web-test/stalled-html — <no contract> — "A complete large morph whose bytes change with the agent count."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.block/data-prose
{:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY", :seon.config.eval/time-limit-ms 30000, :seon.config.eval.result/max-collection 64, :seon.config/cluster "context-mvp-26962783", :seon.config.ai/no-auth true, :seon.config.render/coalesce-ms 16, :seon.config.error/escalate-to "root", :seon.config.ai/model "qwen3.5:35b-a3b-coding-nvfp4", :seon.config.ai/timeout-ms 300000, :seon.config.eval.result/max-nodes 4096, :seon.config.ai.retry/base-delay-ms 500, :seon.config.run/max-episode-runs 100, :seon.config.ai.retry/jitter-fraction 0.25, :seon.config/on-core-error :panic, :seon.config.ai/max-tokens 8192, :seon.config/applied-manifest-digest "a598df29cad7e44b7e1695c1060d297ab487808db9e8a362bda6a1aabb15b6e4", :seon.config.message/max-chain 16, :seon.config.ai/endpoint "http://127.0.0.1:11434/v1/chat/completions", :seon.config.eval.result/max-string 4096, :db/id 4398, :seon.config.ai.retry/multiplier 2.0, :seon.config.ai.retry/maximum-total-delay-ms 3000, :seon.config.eval.result/max-depth 12, :seon.config.flow.compute/concurrency 18, :seon.config.error/recurrence-limit 3, :seon.config.flow.compute/queue-depth 10, :seon.config.ai.retry/maximum-delay-ms 4000, :seon.config.ai.retry/maximum-retries 2}
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [#:db{:id 747}], :seon.cluster/name "context-mvp-26962783", :db/id 4400, :seon.cluster/toolkit [#:db{:id 748} #:db{:id 751}], :seon.cluster/config #:db{:id 4398}}
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.agent/agent-ai
Agent root is idle.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 5] depth=2 provenance=seon.render.agent/agent-ai
Agent context-mvp is idle.
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent seon-flow-owner is idle.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 6] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4405
;; REPL state namespace=seon.flow basis=536870927 time=#inst "2026-07-31T20:52:30.825-00:00"
```

## Verbatim captured projections

### REAL AGENT EVAL (seon.render/walk) RESULT

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870926 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run/value = [:or :my.run/wait :my.run/completed]
; schema :my.run/wait = [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (reg
```

### TURN/ATTEMPT 1 CONTEXT

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870929 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run/value = [:or :my.run/wait :my.run/completed]
; schema :my.run/wait = [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.run/complete — [:=> [:cat :my.run/result] [:or :my.run/completed :seon.error/value]] — "Finish this run with the reply the agent wants delivered."
; fn my.run/wait — [:=> [:cat :my.run/note] [:or :my.run/wait :seon.error/value]] — "End this run with no reply, leaving a note saying what you await."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.block/data-prose
{:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY", :seon.config.eval/time-limit-ms 30000, :seon.config.eval.result/max-collection 64, :seon.config/cluster "context-mvp-c0b46d73", :seon.config.ai/no-auth true, :seon.config.render/coalesce-ms 16, :seon.config.error/escalate-to "root", :seon.config.ai/model "qwen3.5:35b-a3b-coding-nvfp4", :seon.config.ai/timeout-ms 300000, :seon.config.eval.result/max-nodes 4096, :seon.config.ai.retry/base-delay-ms 500, :seon.config.run/max-episode-runs 100, :seon.config.ai.retry/jitter-fraction 0.25, :seon.config/on-core-error :panic, :seon.config.ai/max-tokens 8192, :seon.config/applied-manifest-digest "a598df29cad7e44b7e1695c1060d297ab487808db9e8a362bda6a1aabb15b6e4", :seon.config.message/max-chain 16, :seon.config.ai/endpoint "http://127.0.0.1:11434/v1/chat/completions", :seon.config.eval.result/max-string 4096, :db/id 4398, :seon.config.ai.retry/multiplier 2.0, :seon.config.ai.retry/maximum-total-delay-ms 3000, :seon.config.eval.result/max-depth 12, :seon.config.flow.compute/concurrency 18, :seon.config.error/recurrence-limit 3, :seon.config.flow.compute/queue-depth 10, :seon.config.ai.retry/maximum-delay-ms 4000, :seon.config.ai.retry/maximum-retries 2}
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [{:db/id 747}], :seon.cluster/name "context-mvp-c0b46d73", :db/id 4400, :seon.cluster/toolkit [{:db/id 748} {:db/id 751}], :seon.cluster/config {:db/id 4398}}
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.agent/agent-ai
Agent root is idle.
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4402
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
(ns my.agents/context-mvp)

;; no definitions yet; owned by agent context-mvp.
;; path=[:seon.render.walk/neighbours 3] depth=1 provenance=seon.cluster.message/render-ai
Agent root said to context-mvp: Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent context-mvp is running now.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 5] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 2] depth=1 provenance=seon.cluster.run/render-ai
Run 488721ac-69ee-433f-8858-315b5af951b1, opened #inst "2026-07-31T20:53:39.097-00:00". It is running now, held by 13209-1785531194142.
;; path=[:seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 1] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 4] depth=1 provenance=seon.render.transcript/render-ai
;; transcript/entry :message "501d4f4f-01a0-4b92-85de-ec87fbe57c75" :full
;; at #inst "2026-07-31T20:53:38.877-00:00"
;; Agent root sent this to you.
(my.message/send "context-mvp" "Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.")
;; REPL state namespace=my.agents/context-mvp basis=536870929 time=#inst "2026-07-31T20:53:39.101-00:00"
```

### TURN/ATTEMPT 1 PROVIDER VALUE

```clojure
{:seon.error/kind :seon.ai/unparseable-body, :seon.error/message "The provider streamed no assistant text.", :seon.error/data {:seon.ai/body-shape :seon.ai/empty-stream}}
```

## Verbatim captured projections

### REAL AGENT EVAL (seon.render/walk) RESULT

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870926 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run/value = [:or :my.run/wait :my.run/completed]
; schema :my.run/wait = [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (reg
```

### TURN/ATTEMPT 1 CONTEXT

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870929 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run/value = [:or :my.run/wait :my.run/completed]
; schema :my.run/wait = [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.run/complete — [:=> [:cat :my.run/result] [:or :my.run/completed :seon.error/value]] — "Finish this run with the reply the agent wants delivered."
; fn my.run/wait — [:=> [:cat :my.run/note] [:or :my.run/wait :seon.error/value]] — "End this run with no reply, leaving a note saying what you await."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.block/data-prose
{:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY", :seon.config.eval/time-limit-ms 30000, :seon.config.eval.result/max-collection 64, :seon.config/cluster "context-mvp-36fbebfa", :seon.config.ai/no-auth true, :seon.config.render/coalesce-ms 16, :seon.config.error/escalate-to "root", :seon.config.ai/model "qwen3.5:35b-a3b-coding-nvfp4", :seon.config.ai/timeout-ms 300000, :seon.config.eval.result/max-nodes 4096, :seon.config.ai.retry/base-delay-ms 500, :seon.config.run/max-episode-runs 100, :seon.config.ai.retry/jitter-fraction 0.25, :seon.config/on-core-error :panic, :seon.config.ai/max-tokens 8192, :seon.config/applied-manifest-digest "a598df29cad7e44b7e1695c1060d297ab487808db9e8a362bda6a1aabb15b6e4", :seon.config.message/max-chain 16, :seon.config.ai/endpoint "http://127.0.0.1:11434/v1/chat/completions", :seon.config.eval.result/max-string 4096, :db/id 4398, :seon.config.ai.retry/multiplier 2.0, :seon.config.ai.retry/maximum-total-delay-ms 3000, :seon.config.eval.result/max-depth 12, :seon.config.flow.compute/concurrency 18, :seon.config.error/recurrence-limit 3, :seon.config.flow.compute/queue-depth 10, :seon.config.ai.retry/maximum-delay-ms 4000, :seon.config.ai.retry/maximum-retries 2}
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [{:db/id 747}], :seon.cluster/name "context-mvp-36fbebfa", :db/id 4400, :seon.cluster/toolkit [{:db/id 748} {:db/id 751}], :seon.cluster/config {:db/id 4398}}
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.agent/agent-ai
Agent root is idle.
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4402
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
(ns my.agents/context-mvp)

;; no definitions yet; owned by agent context-mvp.
;; path=[:seon.render.walk/neighbours 3] depth=1 provenance=seon.cluster.message/render-ai
Agent root said to context-mvp: Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent context-mvp is running now.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 5] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 2] depth=1 provenance=seon.cluster.run/render-ai
Run 10c33154-eb8e-48de-b8c3-56e10c588092, opened #inst "2026-07-31T20:59:37.166-00:00". It is running now, held by 14877-1785531558704.
;; path=[:seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 1] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 4] depth=1 provenance=seon.render.transcript/render-ai
;; transcript/entry :message "ad241f7a-64f3-41fa-ab35-d60b78d02eac" :full
;; at #inst "2026-07-31T20:59:37.080-00:00"
;; Agent root sent this to you.
(my.message/send "context-mvp" "Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.")
;; REPL state namespace=my.agents/context-mvp basis=536870929 time=#inst "2026-07-31T20:59:37.168-00:00"
```

### TURN/ATTEMPT 1 MODEL REPLY

```clojure
(ns my.agents/context-mvp
  :require [my.message :as msg]
            [my.run :as run]
            [seon.render.walk :as walk])

;; Define a permanent contracted function that returns the names of the schemas used by my.message
(defn message-schema-names []
  "Returns the keyword names of the schemas defined within the my.message namespace."
  [:my.message/about
   :my.message/content
   :my.message/declination
   :my.message/message
   :my.message/reason
   :my.message/to
   :my.message/value])

;; Create a self-message asking what those schemas are
(let [msg-content "What are the specific schema keywords used in my.message?"]
  ;; Call the helper to generate the name list
  (defn inspect-and-answer []
    "Inspect the current walk context and answer the message regarding schema names."
    (let [schema-list (message-schema-names)]
      ;; Return a vector containing the self-message request and the run completion
      [(msg/send "context-mvp" (str "Inspecting walk: The schemas in my.message are " (str/join ", " schema-list)))
       (run/complete (str "Finished inspection. Found " (count schema-list) " schemas."))]))

;; Trigger the inspection and answer immediately
(inspect-and-answer)

;; The above block defines the function and executes it, returning the vector containing:
;; 1. A message asking for the schemas (which triggers the next turn).
;; 2. A completion object for the current run.

;; Note: In a real REPL loop, the `(my.message/send ...)` would be the output that the agent receives back,
;; but here we are constructing the return value for the `my.run/complete` to signal the end of this turn.
;; The instructions asked to "return one vector containing a self-message... and a my.run/complete value".
;; However, since we are in a `defn` context for permanent code, we must ensure the *result* of the evaluation
;; is the vector required by the runner.

;; Re-evaluating the specific instruction: "Define... then return one vector..."
;; The `defn` above defines the logic. We need to ensure the namespace evaluates to the vector described.

[
 (msg/send "context-mvp" "What are the names of the schemas used by my.message?")
 (run/complete "The schemas used by my.message are: :my.message/about, :my.message/content, :my.message/declination, :my.message/message, :my.message/reason, :my.message/to, :my.message/value.")
]
```

## Verbatim captured projections

### REAL AGENT EVAL (seon.render/walk) RESULT

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870926 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run/value = [:or :my.run/wait :my.run/completed]
; schema :my.run/wait = [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (reg
```

### TURN/ATTEMPT 1 CONTEXT

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870929 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run/value = [:or :my.run/wait :my.run/completed]
; schema :my.run/wait = [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.run/complete — [:=> [:cat :my.run/result] [:or :my.run/completed :seon.error/value]] — "Finish this run with the reply the agent wants delivered."
; fn my.run/wait — [:=> [:cat :my.run/note] [:or :my.run/wait :seon.error/value]] — "End this run with no reply, leaving a note saying what you await."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.block/data-prose
{:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY", :seon.config.eval/time-limit-ms 30000, :seon.config.eval.result/max-collection 64, :seon.config/cluster "context-mvp-dbb3715f", :seon.config.render/coalesce-ms 16, :seon.config.error/escalate-to "root", :seon.config.ai/model "deepseek-chat", :seon.config.ai/timeout-ms 300000, :seon.config.eval.result/max-nodes 4096, :seon.config.ai.retry/base-delay-ms 500, :seon.config.run/max-episode-runs 100, :seon.config.ai.retry/jitter-fraction 0.25, :seon.config/on-core-error :panic, :seon.config.ai/max-tokens 8192, :seon.config/applied-manifest-digest "dea361d76ae4b6e83414c2b7e5c7d85a977ac2c5dd041da3f016e55ae2700d24", :seon.config.message/max-chain 16, :seon.config.ai/endpoint "https://api.deepseek.com/chat/completions", :seon.config.eval.result/max-string 4096, :db/id 4398, :seon.config.ai.retry/multiplier 2.0, :seon.config.ai.retry/maximum-total-delay-ms 3000, :seon.config.eval.result/max-depth 12, :seon.config.flow.compute/concurrency 18, :seon.config.error/recurrence-limit 3, :seon.config.flow.compute/queue-depth 10, :seon.config.ai.retry/maximum-delay-ms 4000, :seon.config.ai.retry/maximum-retries 2}
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [{:db/id 747}], :seon.cluster/name "context-mvp-dbb3715f", :db/id 4400, :seon.cluster/toolkit [{:db/id 748} {:db/id 751}], :seon.cluster/config {:db/id 4398}}
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.agent/agent-ai
Agent root is idle.
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4402
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
(ns my.agents/context-mvp)

;; no definitions yet; owned by agent context-mvp.
;; path=[:seon.render.walk/neighbours 3] depth=1 provenance=seon.cluster.message/render-ai
Agent root said to context-mvp: Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent context-mvp is running now.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 5] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 2] depth=1 provenance=seon.cluster.run/render-ai
Run 5180a7ef-a6b2-4ca2-b134-afad175f8f00, opened #inst "2026-07-31T21:00:39.849-00:00". It is running now, held by 15135-1785531621260.
;; path=[:seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 1] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4404
;; path=[:seon.render.walk/neighbours 4] depth=1 provenance=seon.render.transcript/render-ai
;; transcript/entry :message "b14a48dd-be3b-49f2-8b72-53df904170bc" :full
;; at #inst "2026-07-31T21:00:39.698-00:00"
;; Agent root sent this to you.
(my.message/send "context-mvp" "Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.")
;; REPL state namespace=my.agents/context-mvp basis=536870929 time=#inst "2026-07-31T21:00:39.851-00:00"
```

### TURN/ATTEMPT 1 MODEL REPLY

```clojure
;; I need to:
;; 1. Define a permanent contracted function that returns the names of the schemas used by my.message
;; 2. Return a vector containing a self-message asking what those schemas are and a my.run/complete value
;;
;; From my walk, I can see my.message uses these schemas:
;; - :my.message/about
;; - :my.message/content
;; - :my.message/declination
;; - :my.message/message
;; - :my.message/reason
;; - :my.message/to
;; - :my.message/value

(defn my.message/schema-names []
  "Returns the names of the schemas used by my.message."
  [:my.message/about
   :my.message/content
   :my.message/declination
   :my.message/message
   :my.message/reason
   :my.message/to
   :my.message/value])

;; Now create the vector with self-message and complete value
[(my.message/send "context-mvp" (str "The schemas used by my.message are: " (my.message/schema-names)))
 (my.run/complete "I have defined my.message/schema-names and sent myself a message asking what schemas are used.")]
```

## Verbatim captured projections

### REAL AGENT EVAL (seon.render/walk) RESULT

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870926 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.

```clojure

;; unqualified name — it lands in YOUR namespace
;; the :malli/schema attr-map is what makes it permanent; without it this is scratch
(defn greet
  "Say hello."
  {:malli/schema [:=> [:cat :string] :string]}
  [name]
  (str "Hello, " name))

```
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run
```

### TURN/ATTEMPT 1 CONTEXT

```clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"], :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870929 depth=2
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.

```clojure

;; unqualified name — it lands in YOUR namespace
;; the :malli/schema attr-map is what makes it permanent; without it this is scratch
(defn greet
  "Say hello."
  {:malli/schema [:=> [:cat :string] :string]}
  [name]
  (str "Hello, " name))

```
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
(ns my.message (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.message/about = [:string {:min 1}]
; schema :my.message/content = [:string {:min 1}]
; schema :my.message/declination = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/about :my.message/about] [:my.message/reason :my.message/reason]]
; schema :my.message/message = [:map {:closed true} [:my.message/to :my.message/to] [:my.message/content :my.message/content] [:my.message/about {:optional true} :my.message/about]]
; schema :my.message/reason = [:string {:min 1}]
; schema :my.message/to = [:string {:min 1}]
; schema :my.message/value = [:or :my.message/message :my.message/declination [:vector {:min 1} [:or :my.message/message :my.message/declination]]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.message/decline — [:=> [:cat :my.message/to :my.message/about :my.message/reason] [:or :my.message/declination :seon.error/value]] — "Decline the assignment about `about`, giving `to` the reason."
; fn my.message/send — [:function [:=> [:cat :my.message/to :my.message/content] [:or :my.message/message :seon.error/value]] [:=> [:cat :my.message/to :my.message/content :my.message/about] [:or :my.message/message :seon.error/value]]] — "Address `content` to `to`, optionally naming what it concerns."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
(ns my.run (:require [clojure.string :as str] [seon.schema.edn :as schema.edn]))

; schema :my.run/completed = [:map {:closed true} [:my.run/disposition [:= :completed]] [:my.run/result :my.run/result]]
; schema :my.run/disposition = [:enum :wait :completed]
; schema :my.run/note = [:string {:min 1}]
; schema :my.run/result = [:string {:min 1}]
; schema :my.run/value = [:or :my.run/wait :my.run/completed]
; schema :my.run/wait = [:map {:closed true} [:my.run/disposition [:= :wait]] [:my.run/note :my.run/note]]

; referenced schemas
; (register! :seon.error/data :map)
; (register! :seon.error/kind :keyword)
; (register! :seon.error/message [:string {:min 1}])
; (register! :seon.error/value [:map {:closed true} [:seon.error/kind :seon.error/kind] [:seon.error/message :seon.error/message] [:seon.error/data {:optional true} :seon.error/data]])

; fn my.run/complete — [:=> [:cat :my.run/result] [:or :my.run/completed :seon.error/value]] — "Finish this run with the reply the agent wants delivered."
; fn my.run/wait — [:=> [:cat :my.run/note] [:or :my.run/wait :seon.error/value]] — "End this run with no reply, leaving a note saying what you await."
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.block/data-prose
{:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY", :seon.config.eval/time-limit-ms 30000, :seon.config.eval.result/max-collection 64, :seon.config/cluster "context-mvp-138f0566", :seon.config.render/coalesce-ms 16, :seon.config.error/escalate-to "root", :seon.config.ai/model "deepseek-chat", :seon.config.ai/timeout-ms 300000, :seon.config.eval.result/max-nodes 4096, :seon.config.ai.retry/base-delay-ms 500, :seon.config.run/max-episode-runs 100, :seon.config.ai.retry/jitter-fraction 0.25, :seon.config/on-core-error :panic, :seon.config.ai/max-tokens 8192, :seon.config/applied-manifest-digest "dea361d76ae4b6e83414c2b7e5c7d85a977ac2c5dd041da3f016e55ae2700d24", :seon.config.message/max-chain 16, :seon.config.ai/endpoint "https://api.deepseek.com/chat/completions", :seon.config.eval.result/max-string 4096, :db/id 4404, :seon.config.ai.retry/multiplier 2.0, :seon.config.ai.retry/maximum-total-delay-ms 3000, :seon.config.eval.result/max-depth 12, :seon.config.flow.compute/concurrency 18, :seon.config.error/recurrence-limit 3, :seon.config.flow.compute/queue-depth 10, :seon.config.ai.retry/maximum-delay-ms 4000, :seon.config.ai.retry/maximum-retries 2}
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [{:db/id 747}], :seon.cluster/name "context-mvp-138f0566", :db/id 4406, :seon.cluster/toolkit [{:db/id 748} {:db/id 751}], :seon.cluster/config {:db/id 4404}}
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.agent/agent-ai
Agent root is idle.
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4408
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
(ns my.agents/context-mvp)

;; no definitions yet; owned by agent context-mvp.
;; path=[:seon.render.walk/neighbours 3] depth=1 provenance=seon.cluster.message/render-ai
Agent root said to context-mvp: Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent context-mvp is running now.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 5] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4410
;; path=[:seon.render.walk/neighbours 2] depth=1 provenance=seon.cluster.run/render-ai
Run 4c6c0788-99c8-4a2a-a862-1e1c33408cc7, opened #inst "2026-07-31T21:15:45.249-00:00". It is running now, held by 17212-1785532526371.
;; path=[:seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4410
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 1] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4410
;; path=[:seon.render.walk/neighbours 4] depth=1 provenance=seon.render.transcript/render-ai
;; transcript/entry :message "2f20d72f-a265-4331-b325-40575d183915" :full
;; at #inst "2026-07-31T21:15:45.169-00:00"
;; Agent root sent this to you.
(my.message/send "context-mvp" "Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.")
;; REPL state namespace=my.agents/context-mvp basis=536870929 time=#inst "2026-07-31T21:15:45.252-00:00"
```

### TURN/ATTEMPT 1 MODEL REPLY

```clojure
;; Evaluating the task: I need to define a permanent contracted function that returns the names of schemas used by my.message, call it, then return a vector with a self-message asking about those schemas and a my.run/complete value.

;; First, let me inspect what schemas are in my.message. From the walk output, I can see these schemas:
;; - :my.message/about
;; - :my.message/content
;; - :my.message/declination
;; - :my.message/message
;; - :my.message/reason
;; - :my.message/to
;; - :my.message/value

;; Let me define a permanent function that returns these schema names:

(defn get-message-schemas
  "Returns the names of schemas used by my.message."
  {:malli/schema [:=> [:cat] [:vector :keyword]]}
  []
  [:my.message/about
   :my.message/content
   :my.message/declination
   :my.message/message
   :my.message/reason
   :my.message/to
   :my.message/value])

;; Now let me call it to verify it works, then use the result:

(let [schema-names (get-message-schemas)
      self-msg (my.message/send "context-mvp" (str "What are the schemas used by my.message? " schema-names) :about "schemas")
      completion (my.run/complete (str "Schema names: " schema-names))]
  [self-msg completion])
```
