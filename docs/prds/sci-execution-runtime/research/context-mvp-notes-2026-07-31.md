---
type: research
status: active
tags: [prd, research, context, agent]
---

# Context MVP live-drive notes

## Dependency ledger

- Datahike is the maintained `reference-code/datahike` revision
  `9b3be9d59cb0`; the walk uses
  `datahike.api/datoms`, `q`, and `pull` against one immutable database
  value.
- core.async is the maintained `reference-code/core.async` revision
  `dc35f3e0`; the drive waits on Datahike listener events and the existing
  per-agent Flow graph.
- First-party seams are `seon.cluster.prompt/prompt`,
  `seon.render.walk/neighborhood` + `prose`,
  `seon.render.transcript/render-ai`, and the unchanged
  `seon.cluster.loop/turn` capture/provider boundary.

## Result

The reset-boundary scratch-cluster boot works after publishing its own
`current-src`. The d1 birth context is compact enough to read. The d2 birth
context is not sane: it expands the `seon.flow` source, raw function entities,
bindings, and one distance-cap marker per reached leaf. This run produced
81,130 bytes at d2.

The live DeepSeek attempt did not pass the behavioral exit. It saw the exact
first-turn context below, then returned prose and Markdown fences, used an
`ns` form, wrote a `defn` without `:malli/schema`, and tried host
introspection through `ns-publics`. It neither successfully called a toolkit
function nor sent the self-message, so no second turn existed. The context
failed it at four concrete points:

- the instruction advertises `(seon.render/walk)`, but that Var is absent;
- the walk shows agent-facing namespace names but not their callable contracted
  functions at d2;
- repeated leaf-level distance markers lead the useful facts; and
- the reply-as-forms rule was not strong enough to prevent prose/fenced output.

The Ollama-first attempt reached the configured local endpoint with the same
shape and returned `:seon.ai/unparseable-body`: the provider streamed no
assistant text. DeepSeek was then used as the pre-authorized real run.

## Full first-turn context

The following contexts and reply are verbatim pre-decision-11 evidence. They
remain unchanged so the failed drive is reproducible. Their `;;` render
headers, comment-framed notices, and instruction that prose is kept as comments
are superseded display behavior, not examples to copy: current target display
is the form followed by its actual value, while comments survive only in an
agent's input source.

````clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "context-mvp"] :depth 2}) => root=[:seon.cluster.agent/id "context-mvp"] basis=536870928 depth=2
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
my.message
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
my.run
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.block/data-prose
{:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY", :seon.config.eval/time-limit-ms 30000, :seon.config.eval.result/max-collection 64, :seon.config/cluster "context-mvp-f01777b0", :seon.config.render/coalesce-ms 16, :seon.config.error/escalate-to "root", :seon.config.ai/model "deepseek-chat", :seon.config.ai/timeout-ms 300000, :seon.config.eval.result/max-nodes 4096, :seon.config.ai.retry/base-delay-ms 500, :seon.config.run/max-episode-runs 100, :seon.config.ai.retry/jitter-fraction 0.25, :seon.config/on-core-error :panic, :seon.config.ai/max-tokens 8192, :seon.config/applied-manifest-digest "dea361d76ae4b6e83414c2b7e5c7d85a977ac2c5dd041da3f016e55ae2700d24", :seon.config.message/max-chain 16, :seon.config.ai/endpoint "https://api.deepseek.com/chat/completions", :seon.config.eval.result/max-string 4096, :db/id 4341, :seon.config.ai.retry/multiplier 2.0, :seon.config.ai.retry/maximum-total-delay-ms 3000, :seon.config.eval.result/max-depth 12, :seon.config.flow.compute/concurrency 18, :seon.config.error/recurrence-limit 3, :seon.config.flow.compute/queue-depth 10, :seon.config.ai.retry/maximum-delay-ms 4000, :seon.config.ai.retry/maximum-retries 2}
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [{:db/id 747}], :seon.cluster/name "context-mvp-f01777b0", :db/id 4343, :seon.cluster/toolkit [{:db/id 748} {:db/id 751}], :seon.cluster/config {:db/id 4341}}
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.agent/agent-ai
Agent root is idle.
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4345
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
(ns my.agents/context-mvp)

;; no definitions yet; owned by agent context-mvp.
;; path=[:seon.render.walk/neighbours 3] depth=1 provenance=seon.cluster.message/render-ai
Agent root said to context-mvp: Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent context-mvp is running now.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 5] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4347
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4347
;; path=[:seon.render.walk/neighbours 2] depth=1 provenance=seon.cluster.run/render-ai
Run 1dd31ced-ef65-40e9-93ed-5f9fa00ded9b, opened #inst "2026-07-31T19:28:22.041-00:00". It is running now, held by 91344-1785526084103.
;; path=[:seon.render.walk/neighbours 2 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4347
;; path=[:seon.render.walk/neighbours 3 :seon.render.walk/neighbours 1] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4347
;; path=[:seon.render.walk/neighbours 4] depth=1 provenance=seon.render.transcript/render-ai
;; transcript/entry :message "7811fd38-43c2-4865-8058-c5340a8291df" :full
;; at #inst "2026-07-31T19:28:21.955-00:00"
;; Agent root sent this to you.
(my.message/send "context-mvp" "Define and call a permanent contracted function that returns the names of the schemas used by my.message, then return one vector containing a self-message made with my.message/send asking what those schemas are and a my.run/complete value; when that message starts your next turn, inspect your fresh walk and answer it.")
;; REPL state namespace=my.agents/context-mvp basis=536870928 time=#inst "2026-07-31T19:28:22.045-00:00"
````

## Full first model reply

````text
I'll start by defining a permanent contracted function that returns the names of the schemas used by my.message, then send a self-message.

```clojure

(defn get-message-schemas
  "Returns the names of the schemas used by my.message"
  []
  (my.message/schemas))

```

Now I'll send the self-message and complete:

```clojure

[(my.message/send "context-mvp" "What schemas does my.message use?")
 (my.run/complete {:status :ok :schemas (get-message-schemas)})]

```
````

## seon.flow owner birth context at d1

````clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "seon-flow-owner"] :depth 1}) => root=[:seon.cluster.agent/id "seon-flow-owner"] basis=536870926 depth=1
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=2 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1] depth=1 provenance=seon.render.ns/render-ai
seon.flow
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [#:db{:id 747}], :seon.cluster/name "context-mvp-10046cd1", :db/id 4343, :seon.cluster/toolkit [#:db{:id 748} #:db{:id 751}], :seon.cluster/config #:db{:id 4341}}
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent seon-flow-owner is idle.
````

## seon.flow owner birth context at d2

````clojure
;; (seon.render/walk {:root [:seon.cluster.agent/id "seon-flow-owner"] :depth 2}) => root=[:seon.cluster.agent/id "seon-flow-owner"] basis=536870926 depth=2
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
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 25 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 26 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 27 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 28 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 30 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 31 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 32 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 33 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 34 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 35 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 36 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 37 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 38 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 39 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 40 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 41 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 42 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 43 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 44 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 45 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 46 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 47 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 48 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 49 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 50 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 51 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 52 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 53 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 54 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 55 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 56 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 57 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 58 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 59 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 60 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 61 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 62 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 63 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 64 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 65 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 66 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 67 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 68 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 69 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 70 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 71 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 72 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 73 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 74 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 75 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 76 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 77 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 78 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 79 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 80 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 81 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 82 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 83 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 84 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 85 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 86 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 87 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 88 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 89 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 90 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 91 :seon.render.walk/neighbours 0] depth=3 provenance=:seon.render.walk/elided
elided connections at the requested distance cap
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 1] depth=2 provenance=seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send! …)`. Prose lines are kept as `;;` comments.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.ns/render-ai
my.message
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.ns/render-ai
my.run
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
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.block/data-prose
{:db/id 947, :seon.ns.alias/local gen, :seon.ns.alias/target-ns clojure.test.check.generators}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 1] depth=2 provenance=seon.render.block/data-prose
{:db/id 948, :seon.ns.alias/local schema, :seon.ns.alias/target-ns seon.schema}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 2] depth=2 provenance=seon.render.block/data-prose
{:db/id 949, :seon.ns.alias/local flow, :seon.ns.alias/target-ns clojure.core.async.flow}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 3] depth=2 provenance=seon.render.block/data-prose
{:db/id 950, :seon.ns.alias/local flow.spi, :seon.ns.alias/target-ns clojure.core.async.flow.spi}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.block/data-prose
{:db/id 951, :seon.ns.alias/local schema.edn, :seon.ns.alias/target-ns seon.schema.edn}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 5] depth=2 provenance=seon.render.block/data-prose
{:db/id 952, :seon.ns.alias/local walk, :seon.ns.alias/target-ns clojure.walk}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 6] depth=2 provenance=seon.render.block/data-prose
{:db/id 953, :seon.ns.alias/local core.protocols, :seon.ns.alias/target-ns clojure.core.protocols}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 7] depth=2 provenance=seon.render.block/data-prose
{:db/id 954, :seon.ns.alias/local datafy, :seon.ns.alias/target-ns clojure.datafy}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 8] depth=2 provenance=seon.render.block/data-prose
{:db/id 955, :seon.ns.alias/local async.impl, :seon.ns.alias/target-ns clojure.core.async.impl.protocols}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 9] depth=2 provenance=seon.render.block/data-prose
{:db/id 956, :seon.ns.alias/local flow.graph, :seon.ns.alias/target-ns clojure.core.async.flow.impl.graph}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 10] depth=2 provenance=seon.render.block/data-prose
{:db/id 957, :seon.ns.alias/local async, :seon.ns.alias/target-ns clojure.core.async}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 11] depth=2 provenance=seon.render.block/data-prose
{:db/id 958, :seon.ns.import/local Future, :seon.ns.import/target-class java.util.concurrent.Future}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 12] depth=2 provenance=seon.render.block/data-prose
{:db/id 959, :seon.ns.import/local Executor, :seon.ns.import/target-class java.util.concurrent.Executor}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 13] depth=2 provenance=seon.render.block/data-prose
{:db/id 960, :seon.ns.import/local Executors, :seon.ns.import/target-class java.util.concurrent.Executors}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 14] depth=2 provenance=seon.render.block/data-prose
{:db/id 961, :seon.ns.import/local Counted, :seon.ns.import/target-class clojure.lang.Counted}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 15] depth=2 provenance=seon.render.block/data-prose
{:db/id 962, :seon.ns.import/local LinkedList, :seon.ns.import/target-class java.util.LinkedList}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 16] depth=2 provenance=seon.render.block/data-prose
{:db/id 963, :seon.ns.import/local TimeUnit, :seon.ns.import/target-class java.util.concurrent.TimeUnit}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 17] depth=2 provenance=seon.render.block/data-prose
{:db/id 964, :seon.ns.import/local ExecutorService, :seon.ns.import/target-class java.util.concurrent.ExecutorService}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 18] depth=2 provenance=seon.render.ns/render-ai
seon.schema
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 19] depth=2 provenance=seon.render.ns/render-ai
seon.schema.edn
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 20] depth=2 provenance=seon.render.ns/render-ai
clojure.core.async
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 21] depth=2 provenance=seon.render.ns/render-ai
clojure.core.async.flow
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 22] depth=2 provenance=seon.render.ns/render-ai
clojure.core.async.flow.impl.graph
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 23] depth=2 provenance=seon.render.ns/render-ai
clojure.core.async.flow.spi
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 24] depth=2 provenance=seon.render.ns/render-ai
clojure.core.async.impl.protocols
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 25] depth=2 provenance=seon.render.ns/render-ai
clojure.core.protocols
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 26] depth=2 provenance=seon.render.ns/render-ai
clojure.datafy
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 27] depth=2 provenance=seon.render.ns/render-ai
clojure.test.check.generators
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 28] depth=2 provenance=seon.render.ns/render-ai
clojure.walk
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 78] depth=2 provenance=seon.render.ns/render-ai
seon.cluster
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 79] depth=2 provenance=seon.render.ns/render-ai
seon.cluster.agent
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 80] depth=2 provenance=seon.render.ns/render-ai
seon.cluster.loop
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 81] depth=2 provenance=seon.render.ns/render-ai
seon.cluster.agent-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 82] depth=2 provenance=seon.render.ns/render-ai
seon.cluster.boot-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 83] depth=2 provenance=seon.render.ns/render-ai
seon.cluster.turn-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 84] depth=2 provenance=seon.render.ns/render-ai
seon.config-application-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 85] depth=2 provenance=seon.render.ns/render-ai
seon.context-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 86] depth=2 provenance=seon.render.ns/render-ai
seon.flow.loop-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 87] depth=2 provenance=seon.render.ns/render-ai
seon.flow-configuration-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 88] depth=2 provenance=seon.render.ns/render-ai
seon.flow-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 89] depth=2 provenance=seon.render.ns/render-ai
seon.gen.loop-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 90] depth=2 provenance=seon.render.ns/render-ai
seon.instrument-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 91] depth=2 provenance=seon.render.ns/render-ai
seon.render.web-test
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 30] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([value])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- executor?\n  [value]\n  (instance? Executor value))", :seon.fn/sym "seon.flow/executor?", :db/id 2537, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 31] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([value])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- atom-reference?\n  [value]\n  (instance? clojure.lang.IAtom value))", :seon.fn/sym "seon.flow/atom-reference?", :db/id 2538, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 32] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([value])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- java-future?\n  [value]\n  (instance? Future value))", :seon.fn/sym "seon.flow/java-future?", :db/id 2539, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 33] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([value])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- proc-launcher?\n  [value]\n  (satisfies? flow.spi/ProcLauncher value))", :seon.fn/sym "seon.flow/proc-launcher?", :db/id 2540, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 34] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([value])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- graph?\n  [value]\n  (satisfies? flow.graph/Graph value))", :seon.fn/sym "seon.flow/graph?", :db/id 2541, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 35] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([value])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- channel?\n  [value]\n  (satisfies? async.impl/Channel value))", :seon.fn/sym "seon.flow/channel?", :db/id 2542, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 37] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([parallelism])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn bounded-platform-executor\n  \"Create a bounded executor whose workers are platform threads.\"\n  {:malli/schema [:=> [:catn [::parallelism ::parallelism]] ::executor]}\n  [parallelism]\n  (Executors/newFixedThreadPool (int parallelism)))", :seon.fn/spec "[:=> [:catn [:seon.flow/parallelism :seon.flow/parallelism]] :seon.flow/executor]", :seon.fn/sym "seon.flow/bounded-platform-executor", :db/id 2544, :seon.fn/private? false, :seon.fn/doc "Create a bounded executor whose workers are platform threads."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 38] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- virtual-task-executor\n  []\n  (Executors/newVirtualThreadPerTaskExecutor))", :seon.fn/sym "seon.flow/virtual-task-executor", :db/id 2545, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 39] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([parallelism active-work])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- capacity-facts\n  [parallelism active-work]\n  (let [active @active-work\n        compute-active\n        (into {}\n              (filter (fn [[_ facts]]\n                        (= :compute (::workload facts))))\n              active)]\n    {::active-submissions (set (keys compute-active))\n     ::active-procs (set (keys compute-active))\n     ::wedged-submissions\n     (into #{}\n           (keep (fn [[submission-id facts]]\n                   (when (::wedged? facts) submission-id)))\n           compute-active)\n     ::wedged-procs\n     (into #{}\n           (keep (fn [[submission-id facts]]\n                   (when (::wedged? facts) submission-id)))\n           compute-active)\n     ::available-capacity (- parallelism (count compute-active))\n     ::available-permits (- parallelism (count compute-active))\n     ::platform-threads?\n     (every? ::platform-thread? (vals compute-active))}))", :seon.fn/sym "seon.flow/capacity-facts", :db/id 2546, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 43] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([pid compute-executor error report completion active-work {::keys [submission-id work-fn result status] :as work}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- execute-work!\n  [pid compute-executor error report completion active-work\n   {::keys [submission-id work-fn result status] :as work}]\n  (if-not (compare-and-set! status ::queued ::running)\n    (async/offer!\n     completion\n     {::submission-id submission-id\n      ::workload :compute})\n    (do\n      (swap! active-work\n             assoc submission-id\n             {::workload :compute\n              ::wedged? false\n              ::platform-thread? false})\n      (try\n        (.execute\n         ^Executor compute-executor\n         ^Runnable\n         (fn []\n           (swap! active-work\n                  update submission-id\n                  assoc\n                  ::platform-thread?\n                  (not (.isVirtual (Thread/currentThread))))\n           (let [started-at (volatile! nil)\n                 started!\n                 (fn []\n                   (when (nil? @started-at)\n                     (vreset! started-at (System/nanoTime))))]\n             (try\n               (let [value (work-fn {::started! started!})]\n                 (started!)\n                 (deliver result {::started-at @started-at\n                                  ::value value})\n                 (async/offer!\n                  report\n                  {::pid pid\n                   ::event ::work-complete\n                   ::submission-id submission-id\n                   ::value value}))\n               (catch Throwable throwable\n                 (started!)\n                 (deliver result {::started-at @started-at\n                                  ::throwable throwable})\n                 (async/>!!\n                  error\n                  #::flow{:pid pid\n                          :status :running\n                          :cid ::compute-submission\n                          :msg (dissoc work ::work-fn ::result ::status)\n                          :op ::work\n                          :ex throwable}))\n               (finally\n                 (reset! status ::completed)\n                 (swap! active-work dissoc submission-id)\n                 (when-not\n                  (async/offer!\n                   completion\n                   {::submission-id submission-id\n                    ::workload :compute})\n                   (async/offer!\n                    error\n                    #::flow{:pid pid\n                            :status :running\n                            :cid ::compute-submission\n                            :op ::completion-overflow\n                            :ex\n                            (ex-info\n                             \"The launcher completion channel overflowed.\"\n                             {::submission-id submission-id})})))))))\n        (catch Throwable throwable\n          (reset! status ::completed)\n          (swap! active-work dissoc submission-id)\n          (deliver result {::started-at (System/nanoTime)\n                           ::throwable throwable})\n          (async/offer!\n           completion\n           {::submission-id submission-id\n            ::workload :compute}))))))", :seon.fn/sym "seon.flow/execute-work!", :db/id 2550, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 44] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- work-launcher-description\n  []\n  {:ins {::compute-submission\n         \"One disposable compute submission backed by durable work.\"}\n   :workload :io})", :seon.fn/sym "seon.flow/work-launcher-description", :db/id 2551, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 46] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([configuration])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- required-launcher-configuration\n  [configuration]\n  (let [selected\n        (select-keys configuration flow-workload-attributes)\n        required flow-workload-attributes\n        missing (remove #(contains? selected %) required)]\n    (when (seq missing)\n      (throw\n       (ex-info\n        \"The compute work launcher is not ready: required config facts are missing.\"\n        {:seon.error/kind :configuration\n         ::missing-config-facts (vec missing)})))\n    selected))", :seon.fn/sym "seon.flow/required-launcher-configuration", :db/id 2553, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 49] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [graph compute-executor]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn stop-work-launcher!\n  \"Stop a work launcher and interrupt its owned compute executor.\"\n  {:malli/schema [:=> [:cat :seon.flow/work-launcher] :nil]}\n  [{::keys [graph compute-executor]}]\n  (when graph\n    (flow/stop graph))\n  (when compute-executor\n    (.shutdownNow ^ExecutorService compute-executor))\n  nil)", :seon.fn/spec "[:=> [:cat :seon.flow/work-launcher] :nil]", :seon.fn/sym "seon.flow/stop-work-launcher!", :db/id 2556, :seon.fn/private? false, :seon.fn/doc "Stop a work launcher and interrupt its owned compute executor."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 52] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn current-work-launcher\n  \"Return the installed work launcher or fail the readiness check.\"\n  {:malli/schema [:=> [:cat] :seon.flow/work-launcher]}\n  []\n  (or @installed-work-launcher\n      (throw\n       (ex-info\n        \"The bounded work launcher is not installed.\"\n        {:seon.error/kind :configuration}))))", :seon.fn/spec "[:=> [:cat] :seon.flow/work-launcher]", :seon.fn/sym "seon.flow/current-work-launcher", :db/id 2559, :seon.fn/private? false, :seon.fn/doc "Return the installed work launcher or fail the readiness check."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 54] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([buffer capacity drop!])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(deftype CountedDroppingBuffer\n  [^LinkedList buffer ^long capacity drop!]\n  async.impl/UnblockingBuffer\n  async.impl/Buffer\n  (full? [_]\n    false)\n  (remove! [_]\n    (.removeLast buffer))\n  (add!* [this value]\n    (if (>= (.size buffer) capacity)\n      (drop! value)\n      (.addFirst buffer value))\n    this)\n  (close-buf! [_])\n  Counted\n  (count [_]\n    (.size buffer))\n  async.impl/Capacity\n  (capacity [_]\n    capacity)\n  core.protocols/Datafiable\n  (datafy [_]\n    {:type 'CountedDroppingBuffer\n     :count (.size buffer)\n     :capacity capacity}))", :seon.fn/sym "seon.flow/->CountedDroppingBuffer", :db/id 2561, :seon.fn/private? false}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 55] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([capacity drop!])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- counted-dropping-buffer\n  [capacity drop!]\n  (CountedDroppingBuffer. (LinkedList.) (long capacity) drop!))", :seon.fn/sym "seon.flow/counted-dropping-buffer", :db/id 2562, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 56] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([] [{::keys [fault-channel] :as args}] [{::keys [completion] :as state} transition] [{::keys [read-core-error-mode commit-fault! panic!] :as state} _input fault])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- fault-committer-step\n  ([]\n   {:workload :io\n    :ping-map-fn #(select-keys % [::committed ::panicked])})\n  ([{::keys [fault-channel] :as args}]\n   (assoc args\n          ::flow/in-ports {::core-fault fault-channel}\n          ::committed 0\n          ::panicked 0))\n  ([{::keys [completion] :as state} transition]\n   (when (= ::flow/stop transition)\n     ;; Flow observes this transition only after an active transform\n     ;; returns, so the marker joins an in-flight durable commit without\n     ;; inventing a clock.\n     (async/put! completion ::stopped))\n   state)\n  ([{::keys [read-core-error-mode commit-fault! panic!] :as state}\n    _input fault]\n   ;; A closed source error channel presents one terminal nil before\n   ;; Flow removes that input. It is lifecycle, not a core fault.\n   (if (nil? fault)\n     [state nil]\n     (case (read-core-error-mode)\n       :record\n       (do\n         (commit-fault! fault)\n         [(update state ::committed inc) nil])\n\n       :panic\n       (do\n         (panic! fault)\n         [(update state ::panicked inc) nil])\n\n       (throw\n        (ex-info\n         \"Unknown fake :seon.config/on-core-error value.\"\n         {::core-error-mode (read-core-error-mode)}))))))", :seon.fn/sym "seon.flow/fault-committer-step", :db/id 2563, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 58] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([graph report-channel error-channel])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- monitor-graph\n  [graph report-channel error-channel]\n  (reify\n    core.protocols/Datafiable\n    (datafy [_]\n      (-> (datafy/datafy graph)\n          (assoc-in [:chans :report] (datafy/datafy report-channel))\n          (assoc-in [:chans :error] (datafy/datafy error-channel))))\n\n    flow.graph/Graph\n    (start [_] (flow.graph/start graph))\n    (stop [_] (flow.graph/stop graph))\n    (pause [_] (flow.graph/pause graph))\n    (resume [_] (flow.graph/resume graph))\n    (ping [_ timeout-ms] (flow.graph/ping graph timeout-ms))\n    (pause-proc [_ pid] (flow.graph/pause-proc graph pid))\n    (resume-proc [_ pid] (flow.graph/resume-proc graph pid))\n    (ping-proc [_ pid timeout-ms]\n      (flow.graph/ping-proc graph pid timeout-ms))\n    (command-proc [_ pid command more-kvs]\n      (flow.graph/command-proc graph pid command more-kvs))\n    (inject [_ coordinate messages]\n      (flow.graph/inject graph coordinate messages))))", :seon.fn/sym "seon.flow/monitor-graph", :db/id 2565, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 61] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [started fault-channel tag]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn join-error-fanout!\n  \"Feed one more started graph's errors into an existing fan-out.\n  The N-source generalization (F1 §6): every agent graph's error\n  channel joins the cluster's ONE counted-dropping fault channel,\n  tagged with structural provenance —\n  `(async/pipeline 1 fault-channel (map #(merge % tag)) error-chan\n  false)`. `close?` is false so one source graph's stop never closes\n  the committer's inbox; the pipeline itself ends when the source's\n  error channel closes (its graph stopped), so nothing needs an\n  explicit unjoin. Returns the pipeline's result channel.\"\n  {:malli/schema [:=> [:cat ::join-error-request] ::channel]}\n  [{::keys [started fault-channel tag]}]\n  (async/pipeline 1\n                  fault-channel\n                  (map #(merge % tag))\n                  (:error-chan started)\n                  false))", :seon.fn/spec "[:=> [:cat :seon.flow/join-error-request] :seon.flow/channel]", :seon.fn/sym "seon.flow/join-error-fanout!", :db/id 2568, :seon.fn/private? false, :seon.fn/doc "Feed one more started graph's errors into an existing fan-out.\n  The N-source generalization (F1 §6): every agent graph's error\n  channel joins the cluster's ONE counted-dropping fault channel,\n  tagged with structural provenance —\n  `(async/pipeline 1 fault-channel (map #(merge % tag)) error-chan\n  false)`. `close?` is false so one source graph's stop never closes\n  the committer's inbox; the pipeline itself ends when the source's\n  error channel closes (its graph stopped), so nothing needs an\n  explicit unjoin. Returns the pipeline's result channel."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 62] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [fault-graph report-mult error-mult completion application-report-channel monitor-report-channel monitor-error-channel fault-channel]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn stop-error-fanout!\n  \"Detach and stop one error fan-out without stopping its source graph.\"\n  {:malli/schema\n   [:=> [:catn [::fanout ::error-fanout]] :boolean]}\n  [{::keys [fault-graph report-mult error-mult completion\n            application-report-channel monitor-report-channel\n            monitor-error-channel fault-channel]}]\n  (let [stopped? (boolean (flow/stop fault-graph))]\n    ;; Join the proc's lifecycle event before its caller can release the\n    ;; database connection used by an active fault commit.\n    (async/<!! completion)\n    (async/untap report-mult application-report-channel)\n    (async/untap report-mult monitor-report-channel)\n    (async/untap error-mult monitor-error-channel)\n    (async/untap error-mult fault-channel)\n    (doseq [channel [application-report-channel monitor-report-channel\n                     monitor-error-channel]]\n      (async/close! channel))\n    stopped?))", :seon.fn/spec "[:=> [:catn [:seon.flow/fanout :seon.flow/error-fanout]] :boolean]", :seon.fn/sym "seon.flow/stop-error-fanout!", :db/id 2569, :seon.fn/private? false, :seon.fn/doc "Detach and stop one error fan-out without stopping its source graph."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 63] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [seed owner-ordinal attempt]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn seeded-outcome\n  \"Return a deterministic fake-owner outcome for an explicit seed.\"\n  {:malli/schema\n   [:=> [:catn [::request ::seeded-outcome-request]] ::fix-outcome]}\n  [{::keys [seed owner-ordinal attempt]}]\n  (let [mixed-seed\n        (bit-xor\n         (long seed)\n         (unchecked-multiply\n          6364136223846793005\n          (long (inc owner-ordinal)))\n         (unchecked-multiply\n          1442695040888963407\n          (long (inc attempt))))\n        random (java.util.SplittableRandom. mixed-seed)]\n    (nth [::fix-succeeds ::fix-fails ::fix-breaks-other-namespace\n          ::no-response]\n         (.nextInt random 4))))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/seeded-outcome-request]] :seon.flow/fix-outcome]", :seon.fn/sym "seon.flow/seeded-outcome", :db/id 2570, :seon.fn/private? false, :seon.fn/doc "Return a deterministic fake-owner outcome for an explicit seed."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 64] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [owner-count successful-owners escalated? admitted?]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn lineage-status\n  \"Derive the prototype lineage status from current facts.\"\n  {:malli/schema\n   [:=> [:catn [::request ::lineage-status-request]] ::lineage-status]}\n  [{::keys [owner-count successful-owners escalated? admitted?]}]\n  (cond\n    escalated? ::escalated\n    (and admitted? (= owner-count (count successful-owners))) ::done\n    :else ::iterating))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/lineage-status-request]] :seon.flow/lineage-status]", :seon.fn/sym "seon.flow/lineage-status", :db/id 2571, :seon.fn/private? false, :seon.fn/doc "Derive the prototype lineage status from current facts."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 65] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [turn-count failure-count max-turns max-failures]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn escalate-lineage?\n  \"Whether an open prototype lineage has exhausted either fact budget.\"\n  {:malli/schema\n   [:=> [:catn [::request ::escalation-request]] :boolean]}\n  [{::keys [turn-count failure-count max-turns max-failures]}]\n  (or (>= turn-count max-turns)\n      (>= failure-count max-failures)))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/escalation-request]] :boolean]", :seon.fn/sym "seon.flow/escalate-lineage?", :db/id 2572, :seon.fn/private? false, :seon.fn/doc "Whether an open prototype lineage has exhausted either fact budget."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 66] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([] [args] [state _transition] [{::keys [plan-step-fn] :as state} _input message])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- planner-step\n  ([]\n   {:ins {::planner-wake \"A fact-derived planner wake value.\"}\n    :workload :io\n    :ping-map-fn #(select-keys % [::attempts])})\n  ([args]\n   (assoc args ::attempts 0))\n  ([state _transition]\n   state)\n  ([{::keys [plan-step-fn] :as state} _input message]\n   (let [result (plan-step-fn message)]\n     [(update state ::attempts inc)\n      {::flow/report\n       [{::event ::planner-attempt\n         ::result result}]}])))", :seon.fn/sym "seon.flow/planner-step", :db/id 2573, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 68] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([] [args] [state _transition] [{::keys [fix-step-fn] :as state} _input message])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- namespace-owner-step\n  ([]\n   {:ins {::owner-step\n          \"An initial fact-derived wake or open-run continuation.\"}\n    :workload :io\n    :ping-map-fn #(select-keys % [::attempts])})\n  ([args]\n   (assoc args ::attempts 0))\n  ([state _transition]\n   state)\n  ([{::keys [fix-step-fn] :as state} _input message]\n   (let [outcome (fix-step-fn message)]\n     [(update state ::attempts inc)\n      {::flow/report\n       [{::event ::owner-attempt\n         ::outcome outcome}]}])))", :seon.fn/sym "seon.flow/namespace-owner-step", :db/id 2575, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 70] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([] [{::keys [read-sources]}] [state _transition] [state _input message])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- source-enumerator-step\n  ([]\n   {:ins {::source-event\n          \"A full-drain request or one changed namespace source.\"}\n    :outs {::index-request\n           \"One namespace source submitted to the indexer.\"}\n    :workload :io\n    :ping-map-fn #(select-keys % [::emitted ::sources])})\n  ([{::keys [read-sources]}]\n   {::sources (read-sources)\n    ::emitted 0})\n  ([state _transition]\n   state)\n  ([state _input message]\n   (let [changed-namespace (::changed-namespace message)\n         sources\n         (cond-> (::sources state)\n           changed-namespace\n           (assoc changed-namespace (::changed-source message)))\n         selected\n         (if changed-namespace\n           [changed-namespace]\n           (sort (keys sources)))\n         requests\n         (mapv\n          (fn [namespace]\n            {::changed-namespace namespace\n             ::changed-source (get sources namespace)})\n          selected)]\n     [(-> state\n          (assoc ::sources sources)\n          (update ::emitted + (count requests)))\n      {::index-request requests\n       ::flow/report\n       [{::event ::source-enumerated\n         ::namespaces (set selected)}]}])))", :seon.fn/sym "seon.flow/source-enumerator-step", :db/id 2577, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 72] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([] [args] [state _transition] [{::keys [compile-namespace-fn] :as state} _input request])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- indexer-step\n  ([]\n   {:ins {::index-request \"One namespace source to compile.\"}\n    :outs {::tx-page \"Transaction data for the database committer.\"}\n    :workload :compute\n    :ping-map-fn #(select-keys % [::compiled])})\n  ([args]\n   (assoc args ::compiled 0))\n  ([state _transition]\n   state)\n  ([{::keys [compile-namespace-fn] :as state} _input request]\n   (let [page (compile-namespace-fn request)]\n     [(update state ::compiled inc)\n      {::tx-page [page]\n       ::flow/report\n       [{::event ::namespace-indexed\n         ::namespace (::changed-namespace request)}]}])))", :seon.fn/sym "seon.flow/indexer-step", :db/id 2579, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 74] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([] [{pid ::flow/pid :as args}] [state transition] [{::keys [pid active-evals] :as state} _input {::keys [work-fn wedged? interrupt-fn]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- eval-step\n  ([]\n   {:ins {::submission \"A process-local evaluation submission.\"}\n    :outs {::result \"The disposable result delivered downstream.\"}\n    :workload :compute\n    :ping-map-fn\n    (fn [{::keys [pid active-evals] :as state}]\n      (assoc (select-keys state [::pid ::completed])\n             ::active? (contains? @active-evals pid)))})\n  ([{pid ::flow/pid :as args}]\n   (assoc args\n          ::pid pid\n          ::completed 0))\n  ([state transition]\n   (assoc state ::transition transition))\n  ([{::keys [pid active-evals] :as state} _input\n    {::keys [work-fn wedged? interrupt-fn]}]\n   (let [armed? (atom true)\n         deadline-interrupt-fn (or interrupt-fn (constantly nil))\n         armed-interrupt-fn\n         (fn []\n           (deadline-interrupt-fn)\n           (when-not @armed?\n             (throw\n              (ex-info\n               \"The fake interrupt function was called after disarm.\"\n               {::pid pid}))))]\n     (swap! active-evals\n            assoc pid\n            {::workload :compute\n             ::interrupt-fn armed-interrupt-fn\n             ::platform-thread? (not (.isVirtual (Thread/currentThread)))\n             ::wedged? (true? wedged?)})\n     (try\n       (armed-interrupt-fn)\n       (let [result\n             (try\n               (work-fn {::interrupt-fn armed-interrupt-fn})\n               (catch clojure.lang.ExceptionInfo throwable\n                 (if (::interrupted? (ex-data throwable))\n                   {:seon.error/message \"Synthetic interrupt fired.\"\n                    :seon.error/kind :timeout\n                    :seon.error/data {::pid pid}}\n                   (throw throwable))))\n             next-state (update state ::completed inc)]\n         [next-state\n          {::flow/report\n           [{::pid pid\n             ::event ::eval-complete\n             ::result result}]\n           ::result [result]}])\n       (finally\n         (reset! armed? false)\n         (swap! active-evals dissoc pid))))))", :seon.fn/sym "seon.flow/eval-step", :db/id 2581, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 76] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([] [args] [state _transition] [{::keys [deliver!] :as state} _input message])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- mailbox-step\n  ([]\n   {:ins {::mailbox \"A presentation (sliding-buffer 1) tap.\"}\n    :workload :io\n    :ping-map-fn #(select-keys % [::delivered])})\n  ([args]\n   (assoc args ::delivered 0))\n  ([state _transition]\n   state)\n  ([{::keys [deliver!] :as state} _input message]\n   (deliver! message)\n   [(update state ::delivered inc) nil]))", :seon.fn/sym "seon.flow/mailbox-step", :db/id 2583, :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 36] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([step-var workload args] [step-var workload args options])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn var-process\n  \"Build one Flow proc launcher from a step VAR and a pinned workload.\n  THE construction door for every proc in the system (F0(a), the F1\n  blueprint): it REFUSES a non-var step — an anonymous step captures\n  its closures and hot reload silently stops applying to running\n  graphs — and REFUSES a missing or `:mixed` workload, because the\n  `:mixed` default pins one platform thread per proc forever and is\n  the one measured scaling cliff (flow-mechanics 2026-07-28 §1). Both\n  refusals are construction-time throws, never review items. `args`\n  merge into the start options' `:args` so `create-flow` definitions\n  stay pure data.\"\n  {:malli/schema\n   [:function\n    [:=> [:cat :any [:enum :io :compute] :map] ::launcher]\n    [:=> [:cat :any [:enum :io :compute] :map :map] ::launcher]]}\n  ([step-var workload args]\n   (var-process step-var workload args {}))\n  ([step-var workload args options]\n   (when-not (var? step-var)\n     (throw\n      (ex-info\n       \"A Flow proc step must be a Var so running graphs hot reload.\"\n       {::step step-var})))\n   (when-not (contains? #{:io :compute} workload)\n     (throw\n      (ex-info\n       \"A Flow proc must declare either :io or :compute workload.\"\n       {::step step-var\n        ::workload workload})))\n   (let [launcher\n         (flow/process\n          step-var\n          (assoc options :workload workload))]\n     (reify\n       core.protocols/Datafiable\n       (datafy [_]\n         (datafy/datafy launcher))\n\n       flow.spi/ProcLauncher\n       (describe [_]\n         (flow.spi/describe launcher))\n       (start [_ start-options]\n         (flow.spi/start\n          launcher\n          (update start-options :args #(merge args %))))))))", :seon.fn/spec "[:function [:=> [:cat :any [:enum :io :compute] :map] :seon.flow/launcher] [:=> [:cat :any [:enum :io :compute] :map :map] :seon.flow/launcher]]", :seon.fn/sym "seon.flow/var-process", :db/id 2543, :seon.fn/calls [#:db{:id 2543}], :seon.fn/private? false, :seon.fn/doc "Build one Flow proc launcher from a step VAR and a pinned workload.\n  THE construction door for every proc in the system (F0(a), the F1\n  blueprint): it REFUSES a non-var step — an anonymous step captures\n  its closures and hot reload silently stops applying to running\n  graphs — and REFUSES a missing or `:mixed` workload, because the\n  `:mixed` default pins one platform thread per proc forever and is\n  the one measured scaling cliff (flow-mechanics 2026-07-28 §1). Both\n  refusals are construction-time throws, never review items. `args`\n  merge into the start options' `:args` so `create-flow` definitions\n  stay pure data."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 40] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([] [args] [state _transition] [{::keys [parallelism active-work] :as state} _input _message])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- capacity-observer-step\n  ([]\n   {:ins {::observe \"A process-local request to refresh observations.\"}\n    :workload :compute\n    :ping-map-fn\n    (fn [{::keys [parallelism active-work]}]\n      (capacity-facts parallelism active-work))})\n  ([args]\n   (assoc args ::observations 0))\n  ([state _transition]\n   state)\n  ([{::keys [parallelism active-work] :as state} _input _message]\n   [(update state ::observations inc)\n    {::flow/report\n     [(assoc (capacity-facts parallelism active-work)\n             ::event ::capacity)]}]))", :seon.fn/sym "seon.flow/capacity-observer-step", :db/id 2547, :seon.fn/calls [#:db{:id 2546}], :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 41] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([request])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn capacity-observer-proc\n  \"Create a responsive proc that reports current compute occupancy.\"\n  {:malli/schema\n   [:=> [:catn [::request ::capacity-observer-request]] ::launcher]}\n  [request]\n  (var-process #'capacity-observer-step :compute request))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/capacity-observer-request]] :seon.flow/launcher]", :seon.fn/sym "seon.flow/capacity-observer-proc", :db/id 2548, :seon.fn/calls [#:db{:id 2543}], :seon.fn/private? false, :seon.fn/doc "Create a responsive proc that reports current compute occupancy."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 42] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([pid status count ins outs parallelism active-work])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- launcher-ping\n  [pid status count ins outs parallelism active-work]\n  (walk/postwalk\n   datafy/datafy\n   #::flow{:pid pid\n           :status status\n           :count count\n           :ins (dissoc ins ::flow/control ::flow/casts)\n           :outs (dissoc outs ::flow/error ::flow/report)\n           :state (capacity-facts parallelism active-work)}))", :seon.fn/sym "seon.flow/launcher-ping", :db/id 2549, :seon.fn/calls [#:db{:id 2546}], :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 45] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [parallelism active-work task-executor]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- work-launcher-proc\n  [{::keys [parallelism active-work task-executor]}]\n  (reify\n    core.protocols/Datafiable\n    (datafy [_]\n      {::launcher ::work-launcher\n       :desc (work-launcher-description)})\n\n    flow.spi/ProcLauncher\n    (describe [_]\n      (work-launcher-description))\n    (start [_ {:keys [pid ins outs resolver]}]\n      (let [control (::flow/control ins)\n            submission (::compute-submission ins)\n            error (::flow/error outs)\n            report (::flow/report outs)\n            completion (async/chan parallelism)\n            compute-executor\n            (or task-executor (flow.spi/get-exec resolver :compute))]\n        (.execute\n         ^Executor (flow.spi/get-exec resolver :io)\n         ^Runnable\n         (fn []\n           ;; A channel is a scheduling buffer over durable work, never the\n           ;; record of the work. Its fixed buffer parks submitters. Only this\n           ;; loop consumes class channels and accounts live compute slots.\n           (loop [status :paused\n                  count 0\n                  active-count 0]\n             (let [channels\n                   (cond-> [control completion]\n                     (and (= status :running)\n                          (< active-count parallelism))\n                     (conj submission))\n                   [message channel] (async/alts!! channels)]\n               (cond\n                 (= channel completion)\n                 (recur status count (dec active-count))\n\n                 (= channel submission)\n                 (do\n                   (execute-work!\n                    pid compute-executor error report completion active-work\n                    message)\n                   (recur status (inc count) (inc active-count)))\n\n                 (= channel control)\n                 (let [command (::flow/command message)\n                       addressed?\n                       (contains? #{pid ::flow/all} (::flow/to message))]\n                   (cond\n                     (not addressed?)\n                     (recur status count active-count)\n\n                     (= command ::flow/stop)\n                     nil\n\n                     (= command ::flow/pause)\n                     (recur :paused count active-count)\n\n                     (= command ::flow/resume)\n                     (recur :running count active-count)\n\n                     (= command ::flow/ping)\n                     (do\n                       (async/>!!\n                        (::flow/reply-chan message)\n                        (launcher-ping\n                         pid status count ins outs parallelism active-work))\n                       (recur status count active-count))\n\n                     :else\n                     (recur status count active-count)))\n\n                 :else\n                 nil)))))))))", :seon.fn/sym "seon.flow/work-launcher-proc", :db/id 2552, :seon.fn/calls [#:db{:id 2549} #:db{:id 2550} #:db{:id 2551}], :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 47] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [parallelism active-work queue-depth compute-executor io-executor task-executor]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- work-launcher-graph-definition\n  [{::keys [parallelism active-work queue-depth compute-executor\n            io-executor task-executor]}]\n  {:procs\n   {::work-launcher\n    {:proc\n     (work-launcher-proc\n      {::parallelism parallelism\n       ::active-work active-work\n       ::task-executor task-executor})\n     :chan-opts\n     {::compute-submission {:buf-or-n queue-depth}}}\n    ::capacity-observer\n    {:proc\n     (capacity-observer-proc\n      {::parallelism parallelism\n       ::active-work active-work})}}\n   :conns []\n   :compute-exec compute-executor\n   :io-exec io-executor})", :seon.fn/sym "seon.flow/work-launcher-graph-definition", :db/id 2554, :seon.fn/calls [#:db{:id 2548} #:db{:id 2552}], :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 48] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [configuration]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn start-work-launcher!\n  \"Start the one bounded work launcher from acquired config facts.\"\n  {:malli/schema\n   [:=> [:cat :seon.flow/work-launcher-request]\n    :seon.flow/work-launcher]}\n  [{::keys [configuration]}]\n  (let [configuration (required-launcher-configuration configuration)\n        queue-depth\n        (:seon.config.flow.compute/queue-depth configuration)\n        parallelism\n        (:seon.config.flow.compute/concurrency configuration)\n        active-work (atom {})\n        root-executors\n        ((requiring-resolve 'seon.cluster/root-executors))\n        task-executor (virtual-task-executor)\n        graph\n        (flow/create-flow\n         (work-launcher-graph-definition\n           {::parallelism parallelism\n           ::active-work active-work\n           ::queue-depth queue-depth\n           ::compute-executor (:compute root-executors)\n           ::io-executor (:io root-executors)\n           ::task-executor task-executor}))\n        started (flow/start graph)]\n    (flow/resume graph)\n    {::graph graph\n     ::started started\n     ::active-work active-work\n     ::compute-executor task-executor\n     ::configuration configuration}))", :seon.fn/spec "[:=> [:cat :seon.flow/work-launcher-request] :seon.flow/work-launcher]", :seon.fn/sym "seon.flow/start-work-launcher!", :db/id 2555, :seon.fn/calls [#:db{:id 2545} #:db{:id 2553} #:db{:id 2554}], :seon.fn/private? false, :seon.fn/doc "Start the one bounded work launcher from acquired config facts."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 50] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [configuration]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn install-work-launcher!\n  \"Replace the process work launcher with one built from acquired facts.\"\n  {:malli/schema\n   [:=> [:cat :seon.flow/work-launcher-request]\n    :seon.flow/work-launcher]}\n  [{::keys [configuration]}]\n  (let [launcher\n        (start-work-launcher! {::configuration configuration})\n        [previous _]\n        (swap-vals! installed-work-launcher (constantly launcher))]\n    (when previous\n      (stop-work-launcher! previous))\n    launcher))", :seon.fn/spec "[:=> [:cat :seon.flow/work-launcher-request] :seon.flow/work-launcher]", :seon.fn/sym "seon.flow/install-work-launcher!", :db/id 2557, :seon.fn/calls [#:db{:id 2555} #:db{:id 2556}], :seon.fn/private? false, :seon.fn/doc "Replace the process work launcher with one built from acquired facts."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 51] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn stop-installed-work-launcher!\n  \"Stop and forget the process work launcher.\"\n  {:malli/schema [:=> [:cat] :nil]}\n  []\n  (when-let [launcher\n             (first\n              (swap-vals! installed-work-launcher (constantly nil)))]\n    (stop-work-launcher! launcher))\n  nil)", :seon.fn/spec "[:=> [:cat] :nil]", :seon.fn/sym "seon.flow/stop-installed-work-launcher!", :db/id 2558, :seon.fn/calls [#:db{:id 2556}], :seon.fn/private? false, :seon.fn/doc "Stop and forget the process work launcher."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 53] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [submission-id workload work-fn time-limit-ms]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn submit!!\n  \"Submit compute work with fixed-buffer backpressure and await its result.\"\n  {:malli/schema\n   [:=> [:cat :seon.flow/work-submission] :seon.flow/work-result]}\n  [{::keys [submission-id workload work-fn time-limit-ms]}]\n  (let [{::keys [graph active-work]} (current-work-launcher)\n        result (promise)\n        status (atom ::queued)\n        work-fn (bound-fn* work-fn)\n        submitted-at (System/nanoTime)\n        injection\n        (flow/inject\n         graph\n         [::work-launcher ::compute-submission]\n         [{::submission-id submission-id\n           ::workload workload\n           ::work-fn work-fn\n           ::result result\n           ::status status}])]\n    (.get ^Future injection)\n    (let [settled (deref result time-limit-ms ::time-limit)\n          settled-at (System/nanoTime)\n          submission-wait-ms\n          (quot (- (long (if (= ::time-limit settled)\n                           settled-at\n                           (::started-at settled)))\n                   submitted-at)\n                1000000)]\n      (if (= ::time-limit settled)\n        (do\n          (when-not (compare-and-set! status ::queued ::cancelled)\n            (swap! active-work\n                   (fn [active]\n                     (if (contains? active submission-id)\n                       (assoc-in active [submission-id ::wedged?] true)\n                       active))))\n          {::outcome ::time-limit\n           ::submission-wait-ms submission-wait-ms})\n        (if-let [throwable (::throwable settled)]\n          (throw throwable)\n          {::outcome ::completed\n           ::value (::value settled)\n           ::submission-wait-ms submission-wait-ms})))))", :seon.fn/spec "[:=> [:cat :seon.flow/work-submission] :seon.flow/work-result]", :seon.fn/sym "seon.flow/submit!!", :db/id 2560, :seon.fn/calls [#:db{:id 2559}], :seon.fn/private? false, :seon.fn/doc "Submit compute work with fixed-buffer backpressure and await its result."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 57] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([request])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn fault-committer-proc\n  \"Create the Flow proc that turns core faults into durable facts.\n\n   The supplied mode reader represents the database-backed\n   :seon.config/on-core-error decision. Record mode commits the fault; panic\n   mode invokes the supplied development panic function.\"\n  {:malli/schema\n   [:=> [:catn [::request ::fault-committer-proc-request]] ::launcher]}\n  [request]\n  (var-process #'fault-committer-step :io request))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/fault-committer-proc-request]] :seon.flow/launcher]", :seon.fn/sym "seon.flow/fault-committer-proc", :db/id 2564, :seon.fn/calls [#:db{:id 2543}], :seon.fn/private? false, :seon.fn/doc "Create the Flow proc that turns core faults into durable facts.\n\n   The supplied mode reader represents the database-backed\n   :seon.config/on-core-error decision. Record mode commits the fault; panic\n   mode invokes the supplied development panic function."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 59] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([request])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn- fault-graph-definition\n  [request]\n  {:procs\n   {::fault-committer\n    {:proc (fault-committer-proc request)}}\n   :conns []})", :seon.fn/sym "seon.flow/fault-graph-definition", :db/id 2566, :seon.fn/calls [#:db{:id 2564}], :seon.fn/private? true}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 60] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [graph started fault-buffer-capacity monitor-buffer-capacity read-core-error-mode commit-fault! commit-drop! panic!]}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn start-error-fanout!\n  \"Own report/error fan-out for one already-started Flow graph.\n\n   Core faults enter a bounded nonblocking tap. Every admitted fault is\n   processed by a dedicated Flow proc; each overflow calls commit-drop! with\n   the dropped fault. Flow Monitor receives independent sliding-buffer taps\n   through the returned datafiable graph, so it never competes for the source\n   channels or delays fault commitment.\"\n  {:malli/schema\n   [:=> [:catn [::request ::error-fanout-request]] ::error-fanout]}\n  [{::keys [graph started fault-buffer-capacity monitor-buffer-capacity\n            read-core-error-mode commit-fault! commit-drop! panic!]}]\n  (let [report-mult (async/mult (:report-chan started))\n        error-mult (async/mult (:error-chan started))\n        application-report-channel\n        (async/chan (async/sliding-buffer monitor-buffer-capacity))\n        monitor-report-channel\n        (async/chan (async/sliding-buffer monitor-buffer-capacity))\n        monitor-error-channel\n        (async/chan (async/sliding-buffer monitor-buffer-capacity))\n        fault-channel\n        (async/chan\n         (counted-dropping-buffer fault-buffer-capacity commit-drop!))\n        completion (async/promise-chan)\n        fault-graph\n        (flow/create-flow\n         (fault-graph-definition\n          {::fault-channel fault-channel\n           ::completion completion\n           ::read-core-error-mode read-core-error-mode\n           ::commit-fault! commit-fault!\n           ::panic! panic!}))\n        _ (flow/start fault-graph)\n        _ (flow/resume fault-graph)\n        monitor-view\n        (monitor-graph\n         graph monitor-report-channel monitor-error-channel)]\n    (async/tap report-mult application-report-channel)\n    (async/tap report-mult monitor-report-channel)\n    (async/tap error-mult fault-channel)\n    (async/tap error-mult monitor-error-channel)\n    {::graph monitor-view\n     ::source-graph graph\n     ::fault-graph fault-graph\n     ::report-mult report-mult\n     ::error-mult error-mult\n     ::application-report-channel application-report-channel\n     ::monitor-report-channel monitor-report-channel\n     ::monitor-error-channel monitor-error-channel\n     ::fault-channel fault-channel\n     ::completion completion}))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/error-fanout-request]] :seon.flow/error-fanout]", :seon.fn/sym "seon.flow/start-error-fanout!", :db/id 2567, :seon.fn/calls [#:db{:id 2562} #:db{:id 2565} #:db{:id 2566}], :seon.fn/private? false, :seon.fn/doc "Own report/error fan-out for one already-started Flow graph.\n\n   Core faults enter a bounded nonblocking tap. Every admitted fault is\n   processed by a dedicated Flow proc; each overflow calls commit-drop! with\n   the dropped fault. Flow Monitor receives independent sliding-buffer taps\n   through the returned datafiable graph, so it never competes for the source\n   channels or delays fault commitment."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 67] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([request])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn planner-proc\n  \"Create a fake planner proc whose supplied step returns ordinary data.\"\n  {:malli/schema\n   [:=> [:catn [::request ::planner-proc-request]] ::launcher]}\n  [request]\n  (var-process #'planner-step :io request))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/planner-proc-request]] :seon.flow/launcher]", :seon.fn/sym "seon.flow/planner-proc", :db/id 2574, :seon.fn/calls [#:db{:id 2543}], :seon.fn/private? false, :seon.fn/doc "Create a fake planner proc whose supplied step returns ordinary data."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 69] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([request])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn namespace-owner-proc\n  \"Create a fake namespace-owner proc returning one scripted outcome.\"\n  {:malli/schema\n   [:=> [:catn [::request ::namespace-owner-proc-request]] ::launcher]}\n  [request]\n  (var-process #'namespace-owner-step :io request))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/namespace-owner-proc-request]] :seon.flow/launcher]", :seon.fn/sym "seon.flow/namespace-owner-proc", :db/id 2576, :seon.fn/calls [#:db{:id 2543}], :seon.fn/private? false, :seon.fn/doc "Create a fake namespace-owner proc returning one scripted outcome."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 71] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([request])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn source-enumerator-proc\n  \"Create an I/O proc that emits fixture namespaces and changed events.\"\n  {:malli/schema\n   [:=> [:catn [::request ::source-enumerator-proc-request]] ::launcher]}\n  [request]\n  (var-process #'source-enumerator-step :io request))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/source-enumerator-proc-request]] :seon.flow/launcher]", :seon.fn/sym "seon.flow/source-enumerator-proc", :db/id 2578, :seon.fn/calls [#:db{:id 2543}], :seon.fn/private? false, :seon.fn/doc "Create an I/O proc that emits fixture namespaces and changed events."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 73] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [compute-timeout-ms] :as request}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn indexer-proc\n  \"Create a compute proc that compiles one namespace into transaction data.\"\n  {:malli/schema\n   [:=> [:catn [::request ::indexer-proc-request]] ::launcher]}\n  [{::keys [compute-timeout-ms] :as request}]\n  (var-process\n   #'indexer-step :compute request\n   {:compute-timeout-ms compute-timeout-ms}))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/indexer-proc-request]] :seon.flow/launcher]", :seon.fn/sym "seon.flow/indexer-proc", :db/id 2580, :seon.fn/calls [#:db{:id 2543}], :seon.fn/private? false, :seon.fn/doc "Create a compute proc that compiles one namespace into transaction data."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 75] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([{::keys [compute-timeout-ms] :as request}])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn eval-proc\n  \"Create a compute proc that simulates one guarded evaluation.\"\n  {:malli/schema\n   [:=> [:catn [::request ::eval-proc-request]] ::launcher]}\n  [{::keys [compute-timeout-ms] :as request}]\n  (var-process\n   #'eval-step :compute request\n   {:compute-timeout-ms compute-timeout-ms}))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/eval-proc-request]] :seon.flow/launcher]", :seon.fn/sym "seon.flow/eval-proc", :db/id 2582, :seon.fn/calls [#:db{:id 2543}], :seon.fn/private? false, :seon.fn/doc "Create a compute proc that simulates one guarded evaluation."}
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 77] depth=2 provenance=seon.render.block/data-prose
{:seon.fn/arglists "([request])", :seon.fn/ns #:db{:id 946}, :seon.fn/source "(defn mailbox-proc\n  \"Create a proc that delivers each message to a supplied sink.\"\n  {:malli/schema\n   [:=> [:catn [::request ::mailbox-request]] ::launcher]}\n  [request]\n  (var-process #'mailbox-step :io request))", :seon.fn/spec "[:=> [:catn [:seon.flow/request :seon.flow/mailbox-request]] :seon.flow/launcher]", :seon.fn/sym "seon.flow/mailbox-proc", :db/id 2584, :seon.fn/calls [#:db{:id 2543}], :seon.fn/private? false, :seon.fn/doc "Create a proc that delivers each message to a supplied sink."}
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0] depth=2 provenance=seon.render.block/data-prose
{:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY", :seon.config.eval/time-limit-ms 30000, :seon.config.eval.result/max-collection 64, :seon.config/cluster "context-mvp-10046cd1", :seon.config.ai/no-auth true, :seon.config.render/coalesce-ms 16, :seon.config.error/escalate-to "root", :seon.config.ai/model "qwen3.5:35b-a3b-coding-nvfp4", :seon.config.ai/timeout-ms 300000, :seon.config.eval.result/max-nodes 4096, :seon.config.ai.retry/base-delay-ms 500, :seon.config.run/max-episode-runs 100, :seon.config.ai.retry/jitter-fraction 0.25, :seon.config/on-core-error :panic, :seon.config.ai/max-tokens 8192, :seon.config/applied-manifest-digest "a598df29cad7e44b7e1695c1060d297ab487808db9e8a362bda6a1aabb15b6e4", :seon.config.message/max-chain 16, :seon.config.ai/endpoint "http://127.0.0.1:11434/v1/chat/completions", :seon.config.eval.result/max-string 4096, :db/id 4341, :seon.config.ai.retry/multiplier 2.0, :seon.config.ai.retry/maximum-total-delay-ms 3000, :seon.config.eval.result/max-depth 12, :seon.config.flow.compute/concurrency 18, :seon.config.error/recurrence-limit 3, :seon.config.flow.compute/queue-depth 10, :seon.config.ai.retry/maximum-delay-ms 4000, :seon.config.ai.retry/maximum-retries 2}
;; path=[:seon.render.walk/neighbours 0] depth=1 provenance=seon.render.block/data-prose
{:seon.cluster/instructions [#:db{:id 747}], :seon.cluster/name "context-mvp-10046cd1", :db/id 4343, :seon.cluster/toolkit [#:db{:id 748} #:db{:id 751}], :seon.cluster/config #:db{:id 4341}}
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 4] depth=2 provenance=seon.render.agent/agent-ai
Agent root is idle.
;; path=[] depth=0 provenance=seon.render.agent/agent-ai
Agent seon-flow-owner is idle.
;; path=[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 5] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4346
;; path=[:seon.render.walk/neighbours 1 :seon.render.walk/neighbours 29] depth=2 provenance=:seon.render.walk/unknown
back-reference to 4346
````
