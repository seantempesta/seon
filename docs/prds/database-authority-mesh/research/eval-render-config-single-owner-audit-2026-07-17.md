---
title: Eval render config single-owner audit
type: research
status: completed
tags: [research, prd, database, agent, cljs, flow]
---

# Eval render config single-owner audit

## Decision

The execution operation is the one configuration-acquisition owner for prompt
rendering, eval, and agent-page rendering. It acquires the flat
`:seon.config/singleton` once at the operation's immutable `:seon.db/db` and
passes that exact ordinary map through the existing render, eval, and error
mechanisms.

This is an in-place cut:

- no versioned function or namespace;
- no compatibility arity or optional post-attachment fallback;
- no config cache, injected database reader, or leaf database read;
- no second renderer or eval path; and
- no config contribution to authored-program identity or compiler reload.

The async operation edge performs database acquisition. Inner config
accessors and render/eval transformations remain pure over ordinary data.

## Dependency ledger

| Dependency or owner | Selected source | Constraint used |
|---|---|---|
| Current Seon checkout | `ee7a870dfb05a0e9a238a40e3750eb658784133d` plus the active config-owner source freeze | `seon.config` accessors accept one explicit ordinary singleton; the removed phantom reader must not return through another seam. |
| Execution child | `src/seon/execution.cljs:470-490,563-570,705-805` | Compiled render/eval/view operations already run under the invocation's exact immutable database value. Program preparation already has a grouped acquisition edge. |
| Execution composition | `src/seon/execution/runtime.cljs:37-255,318-425` | Prompt and agent view already own grouped `execute-many` requests and selected-function input construction; eval already receives the prepared compiler/program input. |
| Config singleton | `src/seon/config.cljs:356,398-455,704-760,919-1147` | The singleton is one flat ordinary map. Database-backed accessors require that map and keep literal shipped defaults only for absent datoms. |
| Render and context | `src/seon/render/value.cljs`, `src/seon/render.cljs`, `src/seon/agent/ctx.cljs`, `src/seon/agent/ctx/transcript.cljs`, `src/seon/agent/ctx/render_fns.cljs` | Rendering already consumes ordinary request maps. Transcript currently repeats a config pull that the prompt owner can supply. |
| Eval | `src/seon/eval.cljs:2390-2405,2927-3035,3064-3332,3897-4090,5040-5168` | Caps and repair policy affect one batch and its persisted result projection; they must be frozen with that operation rather than read at namespace load or at leaves. |
| Error scopes | `src/seon/error.cljs:390-506,533-590` and `src/seon/instrument.cljc:280-355` | Error recording is synthesized by instrumentation and process nets, so explicit arguments cannot be added at every arbitrary function call. The existing fiber-local error scope is the narrow propagation seam. |
| ClojureScript | `reference-code/clojurescript` at `946d75f3483c0c8e784e6668bff2c71a25619a77`; `src/main/clojure/cljs/core.cljc:975-976` | `await` is valid only in an async environment. Database acquisition therefore stays at async operation edges. |
| Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae`; `src/datahike/query.cljc:121`, `src/datahike/core.cljc:243` | Queries consume explicit inputs, and `db` returns an immutable database value. One acquisition can supply every pure consumer consistently. |
| Bun | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | Async context propagation is available across Promise work; no socket or Bun-native owner needs to enter configuration data. |

## Existing operation owners

### Prompt rendering

`seon.execution.runtime/render-prompt!` already submits one grouped request for
the agent, cluster config, and AI config. The cluster-config member currently
selects only model transport, system text, and REPL mode. The same operation
then constructs `block-call` inputs without the configuration.

Strengthen that request in place:

1. Pull the complete flat singleton from
   `[:seon.config/id seon.config/cluster-config-id]` in the existing member.
2. Bind that ordinary map once.
3. Put it under `:seon.config/configuration` in every existing selected block
   input, including the whole-prompt call.
4. Pass the same map to `rendered-context-from-entity`.
5. Remove transcript's stage-one config pull and consume the supplied map.

The prompt remains one outer grouped acquisition. Nested transcript event
queries remain legitimate dependent reads at the same database value, but
they do not rediscover configuration.

### Agent-page rendering

`render-agent-view!` already owns the agent projection and agent-count
acquisition. Add the same singleton pull as one member of that request and put
the acquired map in every existing `html-call` input. Generic
`seon.render` remains a pure dispatch layer and never acquires a database.

### Eval

`prepare-eval-program!` already prepares the exact invocation database's
program before `seon.execution.runtime/eval-batch!` calls `seon.eval/eval-batch!`.
Do not add a later standalone config round trip.

Factor the existing program member vector and result decoder so eval
preparation appends one full-singleton pull member to the same
`execute-many`. Return the configuration beside `::compile-state` and
`::program`; pass it through the existing closed eval options map. The program
digest is computed only from program facts. A config transaction changes the
next operation's behavior without retiring a child or reloading its compiler.

## Values that must stop being namespace-load constants

### Eval

The following values belong to one eval operation:

- database EDN cap;
- eval source/output cap;
- result-body cap;
- repair level and class switches;
- repair wall-clock budget; and
- maximum fixes per form.

Thread the same configuration through read-error projection, preflight repair,
result preparation, `record-eval!`, failure logging, and error recording.
`config/result-vars-cap` is different: it is a process-local retained-var count
read from the environment and may remain a load-time value.

### Structural value rendering

`seon.render.value/default-opts`, `verbatim-cap`, the dependent probe options,
and `width` currently freeze database-managed settings at namespace load.
Whitespace, tab, trailing-whitespace, and line-number helpers also read config
at their leaves.

Make configuration part of the existing render operation input. Derive
sampling options from it once and pass them through `sample`, `prepare-ai`,
`visible-whitespace`, `render-ai`, and `render-html-data`. `format-ai` remains
configuration-free because it formats an already prepared immutable map.

Do not keep old arities that silently substitute defaults. Tests and direct
internal callers move to the one explicit signature.

### Context and selected render functions

Delete the namespace-load `eval-render-cap`, `result-body-render-cap`, and
`message-render-cap` values in `seon.agent.ctx`. Existing row, event, and block
input maps carry the acquired configuration or the derived limit required by
their pure formatter. The existing age-decay result cap remains a derived
per-row value.

`render-fn-block-ai` reads its token cap from the same selected-call input.
There is no separate auto-render configuration mechanism.

## Operation-local error-scope tradeoff

`seon.error/record!` is synchronous and must never throw. It also has callers
that cannot receive a new application argument naturally:

- Malli instrumentation generates rejection and output/guard catch arms around
  arbitrary functions; and
- the process `unhandledRejection` and `uncaughtException` nets receive only
  host error values.

Requiring every `record!` caller to pass configuration would therefore expand
through instrumentation and every instrumented signature. Letting `record!`
omit it and silently use `:gate` would restore the forbidden post-attachment
fallback.

The smallest coherent seam is the existing `seon.error` AsyncLocalStorage
scope used by expected-core-fault and dev-eval brackets. Add the operation's
immutable configuration to that same scope and wrap each existing execution
invocation once. `record!` reads only this fiber-local operation input, then
passes the map explicitly to pure `config/on-core-error`. Promise work spawned
inside the operation inherits it; unrelated operations do not.

This is not a database reader or cache: it performs no I/O, retains no
cross-operation singleton, and cannot return a newer value than the operation
acquired. The client startup/runtime owner must likewise wrap its already
resolved pre-attachment configuration and, after attachment, its acquired
singleton. If operation-local error scope is rejected, the only honest
alternative is the larger all-record-sites and instrumentation API cut. An
optional `record!` configuration with a default is not acceptable.

## Dependency and cycle constraints

- `seon.error` must not require `seon.db`, execution, eval, or render to fetch
  configuration. Those layers already depend on error, and synchronous
  `record!` cannot perform an async database read.
- `seon.render.value` must not require `seon.agent.ctx`; eval depends on value
  rendering and context depends on eval/render. Pass ordinary data down.
- `seon.render` must not become a database acquisition owner.
- Configuration must not enter the authored-program digest or child identity.
- AsyncLocalStorage must not become a general config API. Only error escalation
  uses the existing cross-cutting error scope; render and eval receive explicit
  data in their ordinary inputs.

## Exact implementation path set

The cohesive source cut owns:

- `src/seon/execution.cljs`;
- `src/seon/execution/runtime.cljs`;
- `src/seon/eval.cljs`;
- `src/seon/render/value.cljs`;
- `src/seon/render.cljs`;
- `src/seon/agent/ctx.cljs`;
- `src/seon/agent/ctx/transcript.cljs`;
- `src/seon/agent/ctx/render_fns.cljs`;
- `src/seon/error.cljs`; and
- `src/seon/instrument.cljc` only if its generated record call needs an
  explicit scope-preservation change.

The client lifecycle wrapper overlaps the lifecycle/config owner and remains
in `src/seon/client.cljs`; it should be integrated at that owner's source-freeze
boundary rather than edited concurrently.

Focused proofs belong in:

- `test/seon/execution/runtime_test.cljs`;
- `test/seon/render/value_test.cljs`;
- `test/seon/instrument_smoke_test.cljs`;
- `test/seon/error_record_test.cljs`;
- `test/seon/eval/memory_safety_test.cljs`; and
- the existing focused eval repair, read-error, and receipt tests selected by
  the changed-test gate.

No other context-block namespace needs a config acquisition path. The standard
selected-function input already reaches it.

## Implementation order

1. Extend the existing error scope to carry one immutable configuration and
   prove concurrent-scope isolation without touching database acquisition.
2. Strengthen prompt and agent-view grouped acquisitions, pass the singleton
   through selected-call inputs, and delete transcript's config member.
3. Add the singleton to eval's existing program-preparation acquisition and
   closed options map.
4. Convert eval caps and repair settings from load/leaf reads to operation
   inputs.
5. Convert structural value rendering and context formatters to explicit
   configuration or derived limits; delete obsolete arities and constants.
6. Update instrumentation schemas and focused tests, then run the static
   no-phantom-reader gate before the focused CLJS proof.

This order settles propagation before leaf deletion and keeps exactly one
working mechanism at every boundary.

## Shortest falsifiers

### Prompt and transcript

Stub prompt `execute-many` with a singleton containing deliberately tiny caps
and non-default whitespace settings. Assert:

- exactly one config member is submitted;
- every selected call receives the same configuration value;
- transcript submits no config request; and
- rendered output reflects those settings.

### Eval freeze

Return configuration A from eval program preparation, make configuration B
current before the batch completes, and assert read errors, repair decisions,
stored result clipping, and core-error escalation all use A. Any leaf database
request or B-derived output fails the proof.

### Error isolation

Run two concurrent error scopes with different `:seon.config/on-core-error`
values. Settle rejected Promises after asynchronous hops and assert no
cross-fiber setting leak. The test-safe expected-core-fault bracket covers the
`:crash` branch without exiting the runner.

### Static reachability

Fail when source contains:

- a zero-argument call to a database-backed config accessor;
- a config-derived top-level value in eval, value rendering, or context;
- the removed config reader or a new config atom/memoized singleton;
- a compatibility or versioned render/eval/config name; or
- a second config acquisition below an operation that already owns the map.

## Graduation evidence

The cut graduates when the focused tests prove one acquisition and one value
per operation, concurrent errors preserve their own dial, and a source scan
finds no phantom database-backed config reader or load-time derived setting.
The subsequent integrated CLJS checkpoint must contain no config arity warning
and no Promise, Datahike value, or host-native value in an agent-facing result.
