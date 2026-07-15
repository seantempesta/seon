---
type: research
status: completed
tags: [research, agent, flow]
---

# Provider-attempt cancellation exact-source audit

## Scope and verdict

This audit makes ordered work item 3 implementation-ready without editing the
dirty runtime owners. `seon.agent.turn/call-llm!` remains the sole retry owner.
Each invocation of its attempt thunk needs one fresh `AbortController`; the
attempt timeout and a future run-lifecycle signal cancel that controller, and
the same signal must reach every provider selected by `seon.ai.dispatch`.

The current `seon.eval/race-timeout` only releases its awaiter. It does not
cancel the losing Promise, and the provider request can continue consuming
tokens, a socket, or a remote worker after the turn has recorded a timeout.
The smallest safe source slice is therefore active timeout cancellation plus a
canonical request-map signal threaded through dispatch, OpenAI-compatible,
Anthropic, DiffusionGemma, and typeahead. Lifecycle cancellation depends on the
dirty loop/client lifecycle owners landing; it is a distinct following slice,
not a reason to create a registry or second retry path now.

One correction to [[research/agent-runtime-source-audit-2026-07-14]] is
material: OpenAI repl-mode's local `stream.abort()` after the first complete
form is a successful consumer stop. It must remain local. Aborting the shared
attempt controller on that success would contaminate a successful attempt with
an abort signal and can race the assembled response into `APIUserAbortError`.
The local stream stop owns no timeout and no retry, so it is not a competing
cancellation authority.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source and relevant behavior |
|---|---|---|
| ClojureScript | `1.12.145`; official tag `r1.12.145`, commit `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | `deps.edn` `:cljs`; `reference-code/clojurescript`. `js/AbortController` and `js/Promise` are direct host interop; no CLJS cancellation abstraction is interposed. |
| Node.js | live `v26.4.0` | Host `AbortController`, `AbortSignal.any`, `AbortSignal.timeout`, `fetch`, and Promise scheduling were probed below. |
| OpenAI Node SDK | `6.42.0`, tag `v6.42.0`, commit `6f849f4ff24f70167bf82d37c8c83e3f8b1c5472` | `reference-code/openai-node/src/internal/request-options.ts` declares `signal`; `src/resources/chat/completions/completions.ts` passes a second `RequestOptions` argument to `.stream`; `src/lib/ChatCompletionStream.ts` chains it to the stream controller; `src/client.ts` checks pre-abort, links the signal to its fetch controller, clears its internal timer, and maps external abort to `APIUserAbortError`. |
| Anthropic TypeScript SDK | `0.104.2`, tag `sdk-v0.104.2`, commit `fbee0d149ce08532885d766d9b1dc99133181d8e` | `reference-code/anthropic-sdk-typescript/src/internal/request-options.ts` declares `signal`; `src/resources/messages/messages.ts` passes a second `RequestOptions` argument to `.stream`; `src/lib/MessageStream.ts` links and removes the external listener; `src/client.ts` checks pre-abort and maps it to `APIUserAbortError`. |
| `again` design | local port grounded at `reference-code/again` commit `0b4f195ba4d980253d807f919ed4e0d4829352a9` | `src/seon/retry.cljs` ports its strategies to async errors-as-values. `with-retry!` alone decides whether another attempt starts. It currently has no cancellation-aware backoff sleep. |
| Seon attempt/retry | current branch | `src/seon/agent/turn.cljs`: `bounded-llm-attempt!` owns one per-attempt cap; `call-llm!` owns retry classification, delay, exhaustion, and telemetry. `test/seon/agent_retry_test.cljs` and the async-park section of `test/seon/agent_loop_test.cljs` are the existing behavioral proof. |
| Seon provider boundary | current branch | `src/seon/ai.cljs` owns shared request vocabulary; `src/seon/ai/dispatch.cljs` selects OpenAI-compatible, Anthropic, DiffusionGemma, typeahead, or stub; the four provider namespaces own their transports. |
| Native HTTP provider | current branch | `src/seon/ai/diffusiongemma.cljs` uses `js/fetch`, a poll timer, and a remote RunPod async job. Existing research grounds remote cancellation as `POST /cancel/{job-id}` in `docs/prds/diffusion-dynamic-context/research/runpod-flash-grounding-2026-06-28.md`. |

First-party idioms to preserve are errors-as-values in both SDK adapters,
`maxRetries 0` in both clients, `seon.retry/with-retry!` as the one executor,
and OpenAI `stream-until-form!` returning an explicit successful
`::aborted? true` result after its local stream stop.

## Current owner graph and exact gap

```text
call-llm! (only retry decision and backoff)
  -> bounded-llm-attempt! (one attempt cap; currently caller-only race)
     -> llm-fn request argument
        -> dispatch adapter
           -> OpenAI .stream(params)              [no RequestOptions signal]
           -> Anthropic .stream(params)           [no RequestOptions signal]
           -> DiffusionGemma fetch + poll + sleep [no signal; remote job lives]
           -> typeahead repeated dg/complete      [no signal between rounds]
```

`bounded-llm-attempt!` currently sends a bare string for batch and a map only
for stream mode. That dual internal shape is the obstacle to a uniform signal.
The canonical internal request must be one namespaced map containing
`:seon.ai/ctx`, optional `:seon.ai/stream?`, and optional
`:seon.ai/abort-signal`. The signal is an intentional `:any` third-party host
boundary, like SDK error objects; it is process-local and is never stored.
`llm-arg->ctx` may continue accepting a legacy string at the public adapter
edge during migration, but `bounded-llm-attempt!` must stop producing it.

Both SDK clients also retain their configured fetch timeout (currently 60 s)
and `maxRetries 0`. That SDK timeout is a transport safeguard, not another
Seon retry loop. The Seon attempt cap remains the orchestration owner and must
actively abort. This slice must not add another timer beside
`seon.eval/race-timeout`; strengthen that existing racer with a timeout callback
or equivalent owned cancellation hook, then delete the caller-only wording and
behavior. A later cleanup may unify the two timeout settings, but changing the
documented provider transport timeout is not required to stop leaked work.

## Executable host and ClojureScript probes

The live MCP `eval_cljs` probe was attempted first and returned `Connection
refused`; no inference was made from an unavailable pod. The same exact
ClojureScript dependency was then run through `cljs.main` on Node:

```clojure
(let [events (atom [])
      c (js/AbortController.)
      s (.-signal c)]
  (.addEventListener s "abort"
    (fn [] (swap! events conj [(.-aborted s) (.-reason s)])))
  (.abort c "cljs-attempt-timeout")
  {:events @events :aborted? (.-aborted s) :reason (.-reason s)})
;; => {:events [[true "cljs-attempt-timeout"]],
;;     :aborted? true,
;;     :reason "cljs-attempt-timeout"}
```

A Node `v26.4.0` probe established four race facts:

- `controller.abort(reason)` updates `aborted` and invokes listeners
  synchronously; the listener ran before the statement after `abort`.
- a timer winning `Promise.race` does not cancel its loser; the losing Promise
  completed later;
- `AbortSignal.any` over an already-aborted signal is immediately aborted and
  retains the first signal's reason; and
- local Node `fetch` rejected with the exact `DOMException("attempt cap",
  "TimeoutError")` supplied as the abort reason.

The implementation must nevertheless classify by its own attempt state, not by
provider message text or arbitrary `signal.reason`: both SDKs deliberately
normalize an external abort to `APIUserAbortError` and discard the outer
reason.

## Target contract and errors as data

One attempt creates one controller and one immutable request map. The timeout
callback first records the attempt outcome as timeout, then aborts the
controller. Whichever terminal outcome wins is accepted once; timer and abort
listeners are removed in `finally`. Each retry creates a fresh controller so a
prior aborted signal can never poison the next request.

The result vocabulary should distinguish control cancellation from provider
failure without parsing exception text:

- attempt cap: existing `:seon.ai/error` with `:seon.ai/timeout? true`, never
  retryable;
- run/lifecycle invalidation: `:seon.ai/error` with a new schema-owned
  cancellation marker and reason such as `:superseded`, `:deadline`, or
  `:interrupted`, never retryable;
- SDK internal timeout: existing `:seon.ai/timeout? true`, never retryable;
- provider transport/429/5xx: unchanged transient envelope and sole
  `call-llm!` retry decision;
- OpenAI first-form stream stop: successful response with estimated usage,
  never a cancellation error; and
- DiffusionGemma remote cancel failure: retain the original cancellation result
  and record/log cleanup failure as separate operational evidence. Never turn a
  cancelled attempt into a retryable provider error.

The controller is a process-local capability, not database authority. Durable
truth remains the run/turn close reason and committed evidence.

## Retry and cancellation race matrix

| Race | Required winner and cleanup | Retry? |
|---|---|---|
| Provider succeeds before cap | Return exact response; clear timer/listeners; signal remains live but unreachable. | No |
| Transient provider error before cap | Return existing transient value; clean attempt; `call-llm!` may wait and start a fresh controller. | Only `call-llm!` decides |
| Cap fires while request is in flight | Atomically choose timeout, abort signal, clear transport/poll work, ignore any late provider settlement. | No |
| Lifecycle closes/supersedes while request is in flight | Atomically choose cancellation reason, abort signal, ignore late settlement; CAS remains the publication backstop. | No |
| Cap and lifecycle fire together | First terminal transition wins; result is one timeout or cancellation value, never both and never a throw. | No |
| Cancellation during retry backoff | Abort-aware sleep wakes and exits with cancellation; no next thunk invocation. | No |
| Prior attempt abort races next retry | Impossible by construction: `with-retry!` receives the settled first result before sleeping, and the next thunk creates a fresh controller. | As classified |
| OpenAI first form completes while cap is live | Adapter calls local `stream.abort()`, returns success, outer attempt clears its timer without aborting its controller. | No |
| DiffusionGemma abort before job id | Abort fetch/poll timer; no remote cancel is possible. | No |
| DiffusionGemma abort after job id | Abort local fetch/sleep and best-effort `POST /cancel/{id}` once; do not await it beyond the attempt's close path. | No |
| Typeahead abort between worker rounds | Check the shared signal before the next `dg/complete` and before projection writes; stop with cancellation data. | No |

## Ordered implementation and deletion boundary

### Slice A: active timeout cancellation, no dirty lifecycle owners

1. In `seon.ai`, register `::abort-signal :any`, add a pure extractor, and
   make the internal request map canonical.
2. In `seon.ai.dispatch`, widen `::request` with the optional signal. The stub
   ignores it and still returns deterministically.
3. In `seon.agent.turn/bounded-llm-attempt!`, create one controller per thunk
   invocation, always pass the canonical request map, and use the existing
   `seon.eval/race-timeout` owner with an abort callback. Preserve
   `call-llm!`, `llm-retryable?`, and `seon.retry/with-retry!` unchanged.
4. In OpenAI and Anthropic adapters, preserve the whole request map through
   `complete+wrap` and pass `#js {:signal signal}` as `.stream`'s second
   argument. Keep both SDKs at `maxRetries 0`.
5. In DiffusionGemma, pass the signal to submit/status fetches, make poll sleep
   abort-aware, retain the job id, and issue one best-effort remote cancel when
   an admitted job is abandoned. Thread the same signal through every
   typeahead worker call and check it between rounds.
6. Delete the bare-string shape produced by `bounded-llm-attempt!`, the
   caller-only timeout documentation, and tests asserting that provider work
   continues after attempt timeout. Do **not** delete OpenAI's local
   first-complete-form `.abort()`.

This is the smallest non-overlapping source slice. It does not edit
`src/seon/agent/loop.cljs` or `src/seon/client.cljs` and can land after their
current dirty owners without inventing lifecycle state.

### Slice B: lifecycle signal after loop/client ownership lands

1. Create the run-scoped controller at the existing lifecycle owner, not in a
   global registry; pass only its signal through `run-loop!` → `run-turn!` →
   `call-llm!`.
2. Compose the run signal with the per-attempt controller using explicit
   listeners or `AbortSignal.any`; always detach listeners after a fast
   attempt.
3. Abort on the already-owned transitions: superseded run, deadline closure,
   explicit interruption/termination, admission publication loss, and client
   detach. Keep the database CAS fence as final publication authority.
4. Make `seon.retry` backoff sleep cancellation-aware so lifecycle loss cannot
   start another attempt after a transient failure.

Delete after proof: the outer loop timeout path that only releases the turn
awaiter where the new run signal now actively cancels the owned attempt. Do not
remove the run deadline watchdog or transaction fence; they protect different
failure boundaries.

## Proof plan

Focused deterministic CLJS proof comes before any paid/provider run:

- `seon.eval.race-timeout-test`: timeout callback fires once, fast completion
  clears the timer, callback exceptions cannot escape the agent boundary.
- `seon.agent-retry-test`: attempt 1 observes abort before attempt 2 begins;
  each retry sees a different signal; timeout/cancellation are nonretryable;
  ordinary transport/429/5xx behavior and retry counts are unchanged.
- `seon.agent-loop-test`: the never-settling fake provider observes signal
  abort and the run closes honestly; later lifecycle slice proves supersession
  and deadline abort before stale publication.
- OpenAI/Anthropic adapter tests: injected fetch observes the exact external
  signal, pre-abort performs no request, `APIUserAbortError` remains a value,
  and `maxRetries` remains zero.
- OpenAI stream tests: first form still calls local `.abort()` and returns
  successful estimated usage; external abort before form completion returns a
  cancellation/timeout value.
- DiffusionGemma/typeahead tests: fetch and poll sleep observe abort; a known
  job id receives exactly one cancel request; no later status poll, step call,
  or projection occurs.

Then verify the running default cluster through `eval_cljs`: call a fake
non-billing provider, inspect the signal transition and returned envelope, and
query the run/turn facts proving one honest terminal result. Finally add an
offline Inspect scorer whose fake provider exposes request-start, abort, late
settlement, retry count, and final run reason. The scorer must falsify leaked
work and stale success; paid or small-model trials add no cancellation proof
until that deterministic scorer is green.

## Remaining risks

- RunPod remote cancellation is semantically stronger than aborting fetch. The
  cancel request must be grounded again in the selected live API behavior when
  Slice A implements it; local fetch cancellation alone is insufficient.
- SDK timeout configuration and the outer attempt cap are currently separate
  settings. This audit preserves compatibility while assigning orchestration
  authority to the outer attempt; a later config PRD should decide whether one
  user-facing timeout value should replace both.
- A lifecycle signal cannot be inferred from database polling without adding
  latency and mutable duplication. Slice B must wait for and strengthen the
  actual run/client owners.
