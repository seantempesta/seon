# The model

An agent's turns are answered by one function of a provider value and a request, `seon.model/complete`; the provider comes from the config profile, and there is none unless one is named.

## No default model

A root started without `--profile NAME` ([config](config.md#layers)) has no model provider: an agent it starts ends at its first turn on `:seon.model/no-provider`, whose message names what to set. A test fixture must never run in production as if it were a model, so nothing is chosen for you.

The profiles (aero's `#profile`, read by `seon.config/profile`):
- `deepseek`: the OpenAI-compatible endpoint of `:seon.config.ai/providers`, model `:seon.config.ai/model` (deepseek-flash unless `SEON_AI_MODEL` says otherwise);
- `scripted`: the scripted provider, a test fixture (`seon.fixtures/reply`), which `bin/test` and `bin/check` run under; no paid call.

`seon.config/provider` builds the provider value a config names (`:none`, `:scripted` or `:openai-compat`), and `seon.config/with-provider` states one handed to root's start, so the facts root writes say what an agent really used. Models, prices and ceilings: [config](config.md#the-model-manifest).

## Running a curated session

A [curated session](decisions.md) is replayed through the scripted provider on a fresh branch: `--profile scripted --set :seon.config.ai/script=seon.fixtures/curated`. `seon.fixtures/curated` answers turn n of task `<ns>/<name>` with turn n of `<runs-dir>/curated/<ns>_<name>.edn` (`:seon.config.root/runs-dir`), a map whose `:turns` are `{:text :calls [{:intent :code}]}`, so every result the agent sees is evaluated live. Past the last turn, or with no file, it answers a message saying so, which ends the turn. The runs directory is git-ignored, so agents do not read the answers.

## One tool, repl

Every request declares one tool, `repl` (`seon.model/eval-tool`, named by `seon.model/tool-name`; not a Clojure function's name, so the model does not wrap its code in `(eval '...)`), with `tool_choice` auto. A reply is its text and its calls ([the ruling](decisions.md#a-reply-is-its-text-and-its-calls-the-message-is-written-when-there-is-something-to-say-owner-2026-11-08)):
- the text is the message to the user, in Markdown; a reply of text alone stops the agent (`seon.agent-test/a-reply-with-no-evals-stops-with-turns-left`);
- each call is one eval, `{intent, code}`, both required; its code is one or more forms run in order ([prompt](prompt.md));
- the calls run in order, every one, whatever the one before did (`seon.repl/run-calls`), so the model sees all of a reply's answers before its next reply;
- each eval is its own entity with its intent and its place `:seon.model/at [k 0]`; the reply's message is `:seon.model-call/reply`;
- another tool's name, arguments that do not read, code that is not a string, or no intent is a typed answer to that call, not run (`:seon.turn/invalid-call`);
- Seon's own inserted evals are replies with an empty message and no intent.

On the wire (`seon.model/wire-messages`, by `seon.model/wire-groups`) a reply goes back as one assistant message with one tool call per eval, then one tool message per call. A call's arguments are one flat object of strings, so the stream reader (`seon.model/json-feed`) holds only the key and the string it is reading. Every request streams its reply with its usage (`seon.model/request-body`), so no clock guesses a reply's length. What a request holds and in which order: [render](render.md#the-models-context-a-cached-prefix-then-the-turn).

## One function answers a request

`seon.model/complete` answers a completion or a typed failure by the provider's kind: `:none` answers `:seon.model/no-provider`, `:scripted` the fixture's reply, `:openai-compat` posts `seon.model/request-body` to the chat-completions path with the key read at the call. A 200 body is read by `seon.model/parse-reply`, as server-sent events (`seon.model/sse-reply`) or one JSON document, into the one completion shape (`seon.model/reply-of`). Anything else is a provider error with the response body ([failures](#failures)).

## Temperature

`:seon.config.ai/temperature` is absent, so a request sends none and the provider's default applies. At 0 an agent copied its last reply: on 5 replayed loop starts, 11 of 15 repeated at 0, 3 of 15 at 0.5, 0 of 15 at the default.

## Thinking is on at low effort

`:seon.config.ai/thinking` is `:enabled` and `:seon.config.ai/reasoning-effort` is `:low`: the reasoning shows what the model found confusing in its context, which is then fixed ([decisions](decisions.md)). A model or a task changes either through the resolver ([config](config.md#the-resolver)); the efforts are the schema's (`:low`, `:medium`, `:high`), and a task that turns thinking `:disabled` is sent no effort.

With thinking on, a reply that spent all of `:seon.config.ai/max-tokens` on reasoning is `:seon.model/reasoning-ate-reply`. It is a turn's error the agent's next turn reads, never the end of its run ([failure points](failure-points.md)). DeepSeek with tools requires each earlier assistant message's `reasoning_content` sent back (a 400 otherwise), so a reply's reasoning, stored on its first eval, goes back as returned (`seon.model/wire-messages`). `:seon.config.ai/replay-reasoning` false leaves it out, and thinking on with replay false is refused at load (`:seon.config.ai/reasoning-pair`).

## Usage is recorded, never assumed

A completion carries what the provider reported: prompt, completion and reasoning tokens, and the prompt tokens its prefix cache served and did not. `seon.turn/usage` records them on the model call (`:seon.model-call/tokens`, `:seon.model-call/cache-hit-tokens`, `:seon.model-call/cache-miss-tokens`; [ctx](ctx.md#the-agents-entity-holds-its-work)); a count the provider did not send is absent, never 0. Spend is tokens times the manifest's price.

## Failures

A model call fails as data at its one edge ([failure points](failure-points.md)): HTTP 429 is `:seon.model/rate-limited`, which the `:model` owner sends again after the provider's Retry-After (one of the [clocks](clocks.md) exceptions); a failed or dropped connection is `:seon.model/transport`; anything else is `:seon.model/provider-error` with the whole body. Each is answered to the asking agent, whose work ends `:error`. Calls in flight are bounded by `:seon.config.ai/max-in-flight`.

## Paid runs

The API key is read from the environment variable the provider names, at the call, by root's `:model` owner only: never logged, printed or stored, and every agent unsets it at birth. Paid runs spend from the owner's standing budget, on productive work only, with the context checked first ([machine](machine.md#secrets-and-paid-runs)).

## The first-move judge

The context's format is measured with one model call per task, no loop, and nothing of the reply run. `my.root/first-moves!` sends each task's turn-0 request (`seon.judge/request`, the messages `seon.ctx.wire/render` makes) through root's `:model` owner, with the model and mode the task resolves to.

The reply's first move is scored from the compiler's facts, never by another model, in a disposable agent (reading a reply runs its ns form). `seon.judge/form-facts` reads with `*read-eval*` off and analyzes each form without running it; `seon.judge/score` measures, against `seon.judge/subject`:
- `:acted`: it defines a var or rewrites the plan, rather than only reading;
- `:right-var`: a form that is not a read names a cited var;
- `:right-test`: it defines a proof, or a non-read form names one;
- `:waste`: the vars whose source the context shows that it reads again;
- `:uncompiled`: its forms that do not compile (`:seon.judge/uncompiled`);
- `:green`: it defines a cited var or a proof, or rewrites the plan.

Each score is a `:seon.judge` fact on main keyed by the request's digest (`seon.judge/digest`), with the version that served the context, the usage and the USD cost (`seon.model/cost`). A pass calls the model only for a request it holds no fact for; a held reply under changed scoring (`seon.judge/scorer`) is scored again with no call. Paid calls stop at `:seon.config.ai/judge-pass-usd` (`:seon.judge/stopped`), and a reply that cannot be priced ends the pass with `:seon.judge/cost-unknown` (`seon.judge-test/a-pass-scores-each-request-once`, `a-pass-stops-at-its-bound`).
