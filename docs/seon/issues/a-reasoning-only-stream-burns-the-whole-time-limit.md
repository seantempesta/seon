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

## Remaining Phase 2 boundary

This issue remains open for exactly one implementation remainder and its live
proof:

- Record the actual serialized outbound body on the durable attempt fact. The
  JSON exists in `seon.ai/complete`, but attempt transaction data is assembled
  by `record-attempt!` at `src/seon/cluster/loop.clj:674`; the new declaration
  belongs in `resources/seon/schemas/seon.ai.attempt.edn`. The
  `defs-rename-sweep` lane currently owns `loop.clj`, so these files await
  orchestrator reassignment.

The Phase 1 live proof uses the existing attempt usage fact: one isolated Flash
call must persist effective thinking `:disabled` and zero reasoning tokens. It
does not claim exact outbound-body proof until the Phase 2 fact lands.

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
Flash call whose persisted attempt settings say `:disabled` and whose persisted
usage has zero reasoning tokens.
