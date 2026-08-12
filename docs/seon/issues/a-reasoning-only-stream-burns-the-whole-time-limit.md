---
type: issue
status: open
severity: blocker
tags: [issue, ai, agent, live-drive]
---

# Settle a turn whose provider stream carries only reasoning

## Problem

`deepseek-v4-flash` can answer 200 and then stream ONLY reasoning until the
client time limit fires, sending zero assistant text. The turn produces
nothing, the whole `:seon.config.ai/timeout-ms` (180 s) is spent, the attempt
records no usage document at all, and the run closes with a transport
message that describes the SYMPTOM (a closed socket) rather than what
happened (the model reasoned past the limit without answering).

Three of nine attempts in the 2026-08-12 minimum-context ablation ended this
way — in FULL, in QUARTER, and in FLOOR, on isolated roots, one of them with
no other JVM running. The recorded attempt error, verbatim:

```clojure
{:seon.error/kind    :seon.ai/stream-truncated
 :seon.error/message "The provider answered 200 and then ended the stream
                      before sending any assistant text. The transport ended
                      with: java.io.IOException: closed <-
                      java.net.http.HttpTimeoutException: request timed out"
 :seon.error/data    {:seon.ai/output-observed?    false
                      :seon.ai/error-class         :response
                      :seon.ai/http-status         200
                      :seon.ai/text-received       0
                      :seon.ai/reasoning-received  76249
                      :seon.ai/response-started?   true
                      :seon.ai/request-transmitted? true}
 :seon.ai.model/last-latency-ms 180012}
```

`:seon.ai/reasoning-received 76249` with `:seon.ai/text-received 0` is the
whole story, and the run's own error string never mentions it.

This is separate from
[a mid-stream disconnect discarding a turn](a-mid-stream-provider-disconnect-discards-the-whole-turn.md):
there is no partial answer to salvage here. Nothing was answered.

## The second half: thinking `:disabled` did not stop it

The effective settings recorded on those very attempts contain
`:seon.config.ai/thinking :disabled`, and a sibling attempt that DID answer
reports `"completion_tokens_details" {"reasoning_tokens" 955}` out of 974
completion tokens — 98% of the answer was reasoning the dial says is off.

`seon.ai/request-body` builds the documented toggle correctly (probed
2026-08-12 on this checkout):

```clojure
(ai/request-body {:seon.ai/model "deepseek-v4-flash" :seon.ai/prompt "hi"
                  :seon.ai/stream? true :seon.ai/thinking :disabled
                  :seon.ai/max-tokens 100})
;; {"thinking" {"type" "disabled"}, "model" "deepseek-v4-flash", …}
```

And the provider honours that toggle when called directly — two curl calls on
2026-08-12, non-streaming and streaming, with
`"thinking":{"type":"disabled"}`, returned zero `reasoning_content` and no
`reasoning_tokens`.

So the request builder is right and the provider is right, yet live drives
still received reasoning. Something between the resolved config and the
transmitted body is dropping or overriding the toggle. That is the defect to
find; the drives are the reproduction.

## Evidence

- `tmp/ablation/drive-roots/floor-01/clusters` — run
  `1e59daee-54e1-472a-b3c1-4feb5894bc5d`, the attempt error above.
- `tmp/ablation/drive-roots/full-02/clusters` — run
  `43d413c2-45cd-4469-ad9b-b57a95c698c3`, same shape; the retriggered turn
  then completed the task normally.
- `tmp/ablation/drive-roots/quarter-03/clusters` — run
  `2eb2af86-8290-4b7e-97e1-5d2de9309f8b`.
- Ablation write-up:
  [minimum-context ablation](../../prds/sci-execution-runtime/research/minimum-context-ablation-plan-2026-08-11.md).

## Acceptance

1. Capture the outbound body of one live drive attempt and show whether
   `"thinking"` is present; fix the owner that drops it, and prove it with a
   drive whose attempts record `reasoning_tokens 0`.
2. A stream that ends with text received = 0 but reasoning received > 0
   settles a turn whose error NAMES that: how much reasoning arrived, that no
   answer did, and that the time limit fired. The transport cause chain stays
   as data, not as the headline.
3. One regression asserting the class: a stream that delivers reasoning and
   no text produces that named refusal rather than a bare transport message.

## Confirmed mechanism and Phase 1 repair — 2026-08-12

The dropped toggle and the separately filed credential override were one
descriptor-resolution failure class in `seon.ai/resolved-target`:

- `model-details` returned Datahike's pulled cardinality-many thinking dials as
  a vector, while `resolved-target` used `contains?` as a membership test.
  Vector `contains?` tests indices, so every configured thinking keyword was
  removed before `request-body` ran.
- The same resolution associated the provider descriptor's credential variable
  unconditionally after per-agent settings had already resolved.

Phase 1 normalizes the pulled thinking dials to a set at `model-details`, makes
the descriptor credential a default only when the effective target has no
selection, and leaves provider endpoint and wire-key selection with the model
descriptor. Class regressions exercise supported thinking membership, explicit
credential preservation with a pre-network refusal, and descriptor fallback
when credential selection is absent.

The stream owner can also name the silent burn without touching the loop. A
truncated stream with reasoning and no assistant text now reports the reasoning
character count, the missing assistant text, and whether the configured HTTP
time limit fired. Reasoning is counted as observed paid output even when text is
empty. The regression drives an SSE reasoning chunk followed by the JDK timeout
cause shape.

## Phase 2 repair — 2026-08-12

Commit `23cd25fb4` records the exact serialized provider request body on every
network outcome:

- `seon.ai/http-request-data` serializes the JSON once. The JDK body publisher
  and the returned `:seon.ai/sent-body` use that same string, so a later
  projection cannot disagree with the bytes handed to HTTP.
- `seon.cluster.loop/attempt-evidence` carries that value into
  `record-attempt!`, which persists it as the no-history
  `:seon.ai.attempt/sent-body` fact.
- The ordinary attempt render omits the request body, like reasoning, so the
  forensic fact does not recursively enter later prompts.

The class regression uses a loopback HTTP server to compare the captured raw
body byte-for-byte with both the completion value and the durable attempt fact.
It also asserts that the recorded JSON contains
`"thinking":{"type":"disabled"}`. The turn-level regression proves that a
reasoning-only timeout reaches the durable attempt error as the typed
`:seon.ai/reasoning-without-answer` diagnostic, including the received
reasoning count, zero assistant text, and the fired time limit.

### Live-proof boundary observed on 2026-08-12

Two isolated-root proof attempts made zero provider calls and therefore do not
count as the required live evidence:

1. The public-message path opened a run but failed prompt rendering before the
   provider seam with `seon.render.walk/root-acquisition` invalid input:
   required `:seon.render/output` was missing. The scratch database contained
   zero attempt entities.
2. A fresh-root direct HTTP-owner proof could not start its cluster because
   shared source inputs changed during the coordinated build checkpoint;
   development dependency-cache discovery then failed. This is foreign
   in-flight source churn, not evidence about `seon.ai`.

Per the shared-tree stop rule, Phase 1 does not retry either foreign boundary.
After the owning lanes settle, the remaining live exit is still exactly one
Flash call whose persisted attempt settings say `:disabled`, whose sent-body
fact says `thinking.type = "disabled"`, and whose persisted usage has zero
reasoning tokens.

Phase 2 made that one call on isolated root
`tmp/flash-sent-body-proof-20260812-01`. The message endpoint accepted it with
HTTP 204, but the call never reached the HTTP owner: the cluster log records a
development core fault at `seon.render.walk/root-acquisition` because required
`:seon.render/output` was missing, followed by failure to record that fault
because `seon.schema.datahike/resolve-datahike-form-in` received an invalid nil
form. The database therefore still has zero attempt entities. Read-only prepl
inspection subsequently timed out at 30 seconds although operator status
reported PID 90278 alive. No second provider call was submitted.

The requested changed-path gate also produced no green verdict. Its shared-base
preparation runner exited zero, but the selected runner remained CPU-bound for
61 minutes without a failure marker or completion and did not answer a
virtual-thread-aware `jcmd` attach. Terminating only that gate's launcher caused
its own runner to be reaped with exit 137; evidence is retained under
`tmp/test-runs/run.LksNlc`. This is a verification boundary, not a test failure
attributed to this change.

Per the foreign-lane boundary rule, this issue stays open. The remaining exit
is a fresh isolated-root one-call proof after the projection owner restores
root acquisition, plus a completed green `bin/test --changed` verdict; neither
requires another AI implementation change.

### Spine blocker repaired — 2026-08-12

The pre-provider boundary is repaired at its two owners. Commit `66bf3fca3`
makes root acquisition's declared request projection-neutral; commit
`305be0b29` supplies the database-derived schema forms throughout transaction
encoding so an instrumentation fault commits even on a Flow thread with no
ambient projection. The focused proof passed four owner tests with 83
assertions and zero failures/errors.

The retained 61-minute changed-path run was independently attributed to the
already-open root-pull parsing/allocation defect, not to the test runner. Its
virtual-thread-aware dumps and heap evidence are recorded in
[cold root pull is slower than the four-query floor](cold-root-pull-is-slower-than-the-four-query-floor.md).
This AI issue remains open only for its paid one-call provider proof; the
missing-output and unrecordable-fault blockers are closed in their archived
issue records. The requested changed-path gate reached its owners green but
could not publish a terminal verdict because the separately owned root-pull
allocation defect recurred; that exact boundary is recorded in
[cold root pull is slower than the four-query floor](cold-root-pull-is-slower-than-the-four-query-floor.md).

The no-paid-call acceptance proof subsequently passed at `d4e1ec9c6`: one
scratch-root message reached a stubbed AI request with a non-empty derived
prompt and zero contract-violation facts under whole-image instrumentation (1
test, 76 assertions). This closes the pre-provider spine blocker. It does not
satisfy this issue's remaining paid one-call provider evidence.
