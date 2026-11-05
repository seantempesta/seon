# The model

An agent's turns are answered by one function of a provider value and a request, ```seon.model/complete```, and which provider that is comes from the config profile: none unless one is named.

## No default model

A root started without `--profile NAME` ([config](config.md#layers)) has no model provider: an agent it starts ends at its first turn on `:seon.model/no-provider`, whose message names what to set. Production must never run a test fixture as if it were a model, so nothing is chosen for you.

The profiles (aero's `#profile`, read by ```seon.config/profile```) are:
- `deepseek`: the OpenAI-compatible endpoint of `:seon.config.ai/providers` (DeepSeek), model `:seon.config.ai/model` (deepseek-flash unless `SEON_AI_MODEL` says otherwise);
- `scripted`: the scripted provider, a test fixture (`seon.fixtures/reply`), which `bin/test` and `bin/check` run under; it makes no paid call.

```seon.config/provider``` builds the provider value a config names (`:none`, `:scripted` or `:openai-compat`), and ```seon.config/with-provider``` states one handed to root's start in place of the file's, so the facts root writes say what an agent really used. The models we use, their prices and ceilings are the manifest: [config](config.md#the-model-manifest).

## Running a curated session

A [curated session](decisions.md) is replayed for real through the scripted provider on a fresh branch: `--profile scripted --set :seon.config.ai/script=seon.fixtures/curated`. ```seon.fixtures/curated``` answers turn n of task `<ns>/<name>` with turn n of `<runs-dir>/curated/<ns>_<name>.edn` (`:seon.config.root/runs-dir`, the root's runs directory), a map whose `:turns` are `{:text :calls [{:intent :code}]}` (its other keys are provenance), so every result the agent sees is evaluated live, never copied. Past the last turn, or with no directory or file, it answers a message saying so, which ends the turn. The files hold answers: the runs directory is git-ignored and outside the program, so agents do not read them.

## One tool, eval

Every request declares one tool, `eval` (`seon.model/eval-tool`), which the model may call or not (`tool_choice` auto: a reply with no call is prose to the user): its code is the REPL's input, one form a call by the prompt's ask though several run in order (they answer `:forms` and `:value`, the vector of their values: `seon.repl/response`, [prompt](prompt.md)), so the model sees each evaluation's answer before its next call. A reply's prose is read as comments. Every request sets `temperature 0` and streams its reply with its usage (`:stream true`, `:stream_options {:include_usage true}`, `seon.model/request-body`), so no clock has to guess a reply's length. What a request holds and in which order is the context's: [render](render.md#the-models-context-a-cached-prefix-then-the-turn).

## One function answers a request

```seon.model/complete``` takes the provider value and the request and answers a completion or a typed failure, by the provider's kind: `:none` answers `:seon.model/no-provider`, `:scripted` the fixture's reply, `:openai-compat` posts `seon.model/request-body` to the endpoint's chat-completions path with the key read at the call. A 200 body is read by ```seon.model/parse-reply``` as a stream of server-sent events (```seon.model/sse-reply```: content and reasoning concatenated in order, each tool call's pieces joined by its index, the last finish reason, the usage the final chunk carries) or as one chat-completion JSON document, and either way becomes the one completion shape (`seon.model/reply-of`). Anything but 200 is a provider error with the response body ([failures](#failures)).

## Thinking is off by default

`:seon.config.ai/thinking` is `:disabled`: a task whose next step needs long reasoning is a context or instruction defect to fix, not a cost to pay every turn (owner: "if we can't have intuitive function calling based on the context our context is bad and instructions need help"). A model or a task turns it on through the resolver ([config](config.md#the-resolver)), with `:seon.config.ai/reasoning-effort`.

With thinking on, DeepSeek answers a `reasoning_content` beside the reply, and a reply that spent all of `:seon.config.ai/max-tokens` on reasoning is `:seon.model/reasoning-ate-reply` (```seon.model/reply-of```). DeepSeek also requires each earlier assistant message's `reasoning_content` sent back on the next request, so a reply's reasoning, stored on its first eval, goes back with it exactly as returned (```seon.model/wire-messages```, the provider's format of ```seon.context/request```'s messages). `:seon.config.ai/replay-reasoning` says whether it goes back: false leaves it out (it stays stored and shown), and a config with thinking on and replay false is refused at load (`:seon.config.ai/reasoning-pair`), because DeepSeek with tools answers 400 then (measured 2026-10-24, docs/model.md). With thinking off there is no reasoning to send.

## Usage is recorded, never assumed

A completion carries what the provider reported: prompt, completion and reasoning tokens, and the prompt tokens its prefix cache served and did not. ```seon.turn/usage``` records them on the turn's model call (`:seon.model-call/tokens`, `:seon.model-call/cache-hit-tokens`, `:seon.model-call/cache-miss-tokens`; [ctx](ctx.md#the-record-is-the-agents-work)); a count the provider did not send is absent, never 0. Prices per model are in the manifest, so spend is tokens times price.

## Failures

A model call fails as data, at its one edge ([failure points](failure-points.md)): HTTP 429 is `:seon.model/rate-limited`, which the `:model` owner keeps and sends again after the provider's Retry-After (one of the [clocks](clocks.md) exceptions); a connection that failed or dropped is `:seon.model/transport` and anything else `:seon.model/provider-error` with the whole response body, each answered to the asking agent, whose work ends `:error` with its message. Calls in flight are bounded by `:seon.config.ai/max-in-flight`.

## Paid runs

The API key is read from the environment variable the provider names, at the call, by root's `:model` owner only: it is never logged, printed or stored, and every agent unsets it at birth. Paid runs spend from the owner's standing budget and only on productive work, with the context checked first ([machine](machine.md#secrets-and-paid-runs)).

## The first-move judge

Until the context has a format agents respond well to, no live agent runs (owner, 2026-10-23; [decisions](decisions.md)). The format is measured instead with one model call per task, no loop, and nothing of the reply run. ```my.root/first-moves!``` sends each task's turn-0 request through root's `:model` owner: ```seon.judge/request```, the messages ```seon.context/render``` makes, as `seon.turn/act!` sends them, with the model and mode the task resolves to.

The reply's first move is scored from the compiler's facts, never by another model. The scoring runs in a disposable agent the pass spawns for each score, because reading a reply as loading does runs its ns form. ```seon.judge/form-facts``` reads with `*read-eval*` off and analyzes each form without running it. ```seon.judge/score``` then gives these measures against ```seon.judge/subject```:
- `:acted`: it defines a var or rewrites the plan, rather than only reading;
- `:right-var`: a form that is not a read names a cited var;
- `:right-test`: it defines a proof, or a form that is not a read names one;
- `:waste`: the vars whose source the context shows (cited at code depth) that it reads again;
- `:uncompiled`: its forms that do not compile (`:seon.judge/uncompiled`, [failure points](failure-points.md));
- `:green`: it defines a cited var or a proof, or rewrites the plan.

Each score is a `:seon.judge` fact on main, keyed by the digest of the request it answers (```seon.judge/digest```). The fact holds the version that served the context, the call's usage and its USD cost (```seon.model/cost```, off-peak prices). A pass reads that fact first and calls the model only for a request it holds none for, so an unchanged context costs nothing; a held reply scored by other scoring (```seon.judge/scorer```, the digest of the scoring's recorded sources) is scored again from its calls, with no model call. A paid call is made only while the pass has spent under `:seon.config.ai/judge-pass-usd`. At that bound the pass stops and writes `:seon.judge/stopped`. A reply whose usage cannot be priced ends the pass with `:seon.judge/cost-unknown`, since an unknown cost is never 0. Every call's cost counts toward the owner's budget above (`seon.judge-test/a-pass-scores-each-request-once`, `seon.judge-test/a-pass-stops-at-its-bound`).
