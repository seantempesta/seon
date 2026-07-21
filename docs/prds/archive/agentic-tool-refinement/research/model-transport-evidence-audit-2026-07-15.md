---
type: research
status: active
tags: [research, agent, database]
---

# Model transport evidence audit — 2026-07-15

## Decision

The current admitted Inspect artifact can reconstruct the AI configuration at
the final `/agents/run` database coordinate, but it cannot prove the effective
configuration of any provider attempt. Formal P0 capability and model-comparison
claims therefore remain blocked by
[[../../../seon/issues/inspect-model-transport-evidence-is-incomplete]].

The repair belongs at the provider-attempt boundary, not in Inspect. One
immutable database value must resolve one effective request configuration; the
adapter must consume that value without rereading mutable state and return a
small attempt-evidence value. The turn stores those attempt facts, the existing
`/agents/run` projection returns them, and `seon_inspect.solver` copies them
unchanged into native sample metadata. Inspect remains the log authority and a
lossless consumer, never a second config resolver or database client.

## Dependency ledger

- Seon source was inspected at superproject commit
  `de5e606d389c2bb058030ab14745349308df71a7`. The owners are
  `seon.ai/resolved-config`, `seon.ai.dispatch/llm-fn`,
  `seon.ai.openai-compat/complete`, `seon.agent.turn/call-llm!`, and
  `seon.web.serve/run-agent-task!`.
- OpenAI Node is selected at
  `reference-code/openai-node@6f849f4ff24f70167bf82d37c8c83e3f8b1c5472`;
  the locked installed package is `openai` `6.42.0`. The pod, not Inspect's
  Python OpenAI package, performs the evaluated model calls.
- Inspect AI is selected at
  `reference-code/inspect-ai@05322696a0f784ec399ef6abbafd3d2a250ea9cc`;
  the installed distribution is
  `0.3.247.dev0+g05322696a.d20260715`. `TaskState.metadata` is copied into the
  full native `EvalSample.metadata` by
  `reference-code/inspect-ai/src/inspect_ai/_eval/task/run.py`. The thin
  `EvalSampleSummary` is not the evidence authority.
- `src-inspect-ai/src/seon_inspect/solver.py::_record_result` is the one current
  response-to-sample projection. It already preserves the final complete
  database coordinate, ordered turn and eval evidence, effective run timeout,
  and the final `model_config` without opening Datahike.
- The model-serving constraints are grounded in
  [[local-model-serving-inventory-2026-07-15]] and
  [[inspect-reproducibility-boundary-2026-07-15]]. MLX-LM `0.31.3` may switch
  models from the request body; its process `--model` and `/v1/models` response
  do not prove the bytes used by one request.
- Existing turn persistence and the public Inspect handoff are grounded in
  [[turn-evidence-retention-2026-07-15]]. The first admitted 0.5B diagnostic is
  [[qwen25-coder-05b-database-diagnostic-2026-07-15]].

## What the current artifact proves

The native sample retains `pod_database_coordinate` as the complete
`{database_id, branch, commit_id, t}` returned from the final immutable
database value. While that commit remains retained, a forensic operator can
resolve it through `seon.db/at-coordinate` and call `seon.ai/resolved-config`.
That reconstructs the final provider, model, temperature, maximum output
tokens, and thinking value. Direct `seon.ai/current` inspection at the same
historical value can additionally find the configured base URL and adapter
timeout.

This is useful historical reconstruction of final database intent. It is not
per-call proof:

- `/agents/run` resolves `model_config` only after the run reaches its terminal
  poll. A configuration transaction between an earlier model call and that
  poll makes the final projection describe new intent, not the earlier call.
- `seon.ai.openai-compat/complete` reads reactive configuration repeatedly:
  provider/endpoint and credential selection occur before the `try`, timeout is
  read again inside it, and `request-params` plus `request-extra-body` read the
  row again. One request can therefore be assembled from more than one
  database value.
- Retries call the adapter again after backoff and may legitimately observe a
  different database head. Only the retry count is stored; no attempt identity
  or coordinate is retained.
- The adapter response discards the provider's known top-level `model` and
  `system_fingerprint` fields. `:seon.ai/provider-fields` preserves only
  unrecognized fields, so the server's response cannot corroborate the
  requested model.
- The outer attempt cap from `seon.config/llm-attempt-timeout-ms` and the
  OpenAI adapter's own timeout are distinct bounds. The `.eval` currently
  proves neither. `pod_effective_timeout_ms` is the whole `/agents/run` wait
  budget and must not be relabeled as either one.

Consequently a manual read of the final coordinate may explain a diagnostic,
but it cannot graduate that diagnostic into a formal result. If the database
coordinate is incomplete, belongs to another attachment, names an unretained
commit, or fails exact `t` validation, even reconstruction is unavailable.

## Missing fields and their authorities

| evidence | exact authority | current gap |
|---|---|---|
| Complete attempt database coordinate | The immutable `db` value captured immediately before an attempt in `seon.agent.turn` | Only the final run coordinate and prompt-render coordinates are retained. Neither necessarily equals the provider-call config value. |
| Provider, requested model, temperature, maximum output tokens, thinking | `seon.ai/resolved-config` over that attempt value, including explicit request overrides | A final response-time projection exists; no attempt projection exists. |
| Configured base URL and adapter timeout | The same resolver over that attempt value, including provider defaults | Both are excluded from `::resolved-config`; OpenAI defaults remain adapter-private. |
| Actual normalized request endpoint | The adapter after its existing `sdk-base-url` normalization | A configured legacy `/chat/completions` URL and `/v1` root can be equivalent; the actual request URL is not returned. Userinfo, query, and fragment must never be retained. |
| Effective extra request data | Explicit request options over the database `extra-body-edn` value | Material chat-template and provider parameters are omitted. Retain a canonical content digest and bounded canonical value only when it passes the ordinary evidence cap. |
| Adapter and outer attempt timeouts | OpenAI adapter resolution; `seon.config/llm-attempt-timeout-ms` at the attempt racer | Neither is retained, and they are semantically different from the run deadline. |
| Stream mode and attempt ordinal | The turn's retry thunk | Only aggregate retry count is retained. |
| Response-reported model, system fingerprint, and request id when present | The provider response parsed by the adapter | Known OpenAI response identity fields are currently discarded. Absence must remain absence. |
| Provider adapter/client identity | The pod artifact and adapter owner | Operator admission identifies pod bytes; the attempt value should name the adapter. Client name/version may be a once-per-run projection referenced by digest rather than repeated on every attempt. |
| Server implementation, immutable model revision or weights digest, quantization | The owner that launches/configures the server, reconciled as declared non-secret identity and checked by its health/request boundary | The database model string and listener command are insufficient. Paid endpoints that expose no immutable weights must say `externally-mutable`; they cannot claim byte-identical weights. |

Credentials, bearer headers, raw environment values, signed query parameters,
and response bodies are explicitly not transport evidence. A credential-source
class may be retained, but never the credential or a reusable fingerprint.

## One-mechanism design

### 1. Make `resolved-config` complete

Extend the existing `seon.ai/resolved-config` value and per-key provenance to
include `:seon.ai/base-url`, `:seon.ai/timeout-ms`, and the other material
provider-selection fields already owned by the row, especially
`:seon.ai/dg-backend` and a digest of effective extra-body data. Move genuine
provider defaults needed for this projection out of adapter-private constants
into one colocated default map consumed by both resolver and adapter. Do not add
an Inspect-side resolver or a stored resolved-config row.

Provider-specific validation remains explicit: `:openai-compat` requires a
non-empty endpoint and positive adapter timeout; a provider for which an
endpoint is inapplicable leaves the attribute absent rather than storing nil or
inventing a placeholder.

### 2. Freeze once per attempt

At the existing retry thunk in `seon.agent.turn/call-llm!`, capture `@db/*conn*`
once, retain its complete head coordinate, resolve the named agent's effective
configuration, and pass that immutable value in the existing LLM request map.
`seon.ai.dispatch` selects the adapter from that value. Each adapter derives its
wire request from that value and explicit request overrides without calling
`seon.ai/current`, `seon.ai/provider`, or `seon.ai/env-row` again.

This is a data-flow correction, not config pinning for the whole run. A retry
may capture a newer database value, but the difference becomes explicit
attempt evidence. Credentials may still be read at call time; only their
non-secret resolution class is reported.

### 3. Return and persist bounded attempt facts

Every adapter result, including error values and outer attempt timeouts, carries
one `:seon.ai/attempt-evidence` map. `call-llm!` accumulates the ordered maps
across retries. Store them as queryable component facts connected to the turn,
not as an opaque console message or a second filesystem log. Each attempt has:

- zero-based ordinal and complete database coordinate;
- provider, adapter, requested model, sampling values, thinking, and stream
  mode;
- redacted normalized endpoint, adapter timeout, outer attempt timeout, and
  effective-extra-body digest;
- response-reported model, fingerprint, and request id when present; and
- bounded outcome classification: success, provider error status, adapter
  timeout, or outer timeout.

Identity strings must have schema limits. Oversized or invalid identity data is
an evidence error, never silently truncated into a plausible identity. Large
optional canonical data uses the existing blob mechanism and a content digest;
the ordinary response carries only the bounded projection. With the configured
retry ceiling, one attempt record is fixed-size and the request's evidence size
is bounded by its retained turn/attempt counts.

### 4. Project once; Inspect copies and validates

Extend `turn-evidence` in `seon.web.serve` to include each turn's ordered
attempt facts. Derive the response's compatibility `model_config` from the
attempts only when every attempt has one identical comparable configuration;
otherwise report the ordered configurations and mark transport drift. The final
database coordinate remains final-run state and is never presented as the
attempt coordinate.

`seon_inspect.solver::_record_result` copies the pod projection unchanged to
`pod_model_transport_evidence`. The capability admission check requires the
field, validates complete coordinates and required OpenAI-compatible fields,
and rejects transport drift before the task scorer runs. It does not query the
database, normalize URLs, infer defaults, or repair old logs.

The external server identity is joined by declared identity digest. For a
dedicated local listener, the selected absolute snapshot/revision or weights
digest and quantization must match the request model and admitted launcher
identity. The response-reported model is corroboration, not a substitute for
the immutable artifact identity. Static source/target admission continues to
guard the pod artifact; it does not prove a separately managed model server.

## Short falsifiers

1. **Historical reconstruction only:** transact row A, capture its complete
   coordinate, transact distinct row B, and resolve A. A must reconstruct A's
   base URL and adapter timeout. This proves resolver time travel, not a call.
2. **Split-read request:** make the fake OpenAI fetch transact B after the
   attempt snapshot but before request assembly. The captured request and
   attempt evidence must remain entirely A. Any B field falsifies atomic
   request resolution.
3. **Retry drift:** first fake attempt returns a retryable transport error,
   then transact B before retry. Persist ordered A and B attempt records and
   reject the sample as transport drift rather than assigning a capability
   score.
4. **Response identity:** return a response model different from the requested
   immutable artifact. Preserve both values and reject formal comparison;
   neither value overwrites the other.
5. **Coordinate failure:** remove one coordinate field, use another database
   attachment, provide a wrong `t`, or prune the commit. Evidence validation
   must fail loudly without falling back to the current database.
6. **Endpoint secrecy:** configure URL userinfo, query, and fragment. The
   request may use the supported configured form, but retained evidence must
   contain only the validated scheme/host/port/path projection. No credential
   bytes appear in the native log.
7. **Legacy pod:** return the old `model_config` with no attempt evidence. The
   diagnostic solver may retain it as incomplete evidence; the capability
   solver must reject it before scoring.
8. **Native round trip:** finalize, copy, reopen, and hash one real `.eval`.
   Every attempt map and absence must survive byte-for-data equality through
   Inspect's public log API.

## Ordered implementation and acceptance path

1. Strengthen `seon.ai/resolved-config` and its schemas/tests so one historical
   database value yields complete behavioral and transport configuration with
   provenance. Prove A-after-B time travel first.
2. Refactor the OpenAI-compatible call to consume one resolved attempt value.
   Use the fake-fetch split-read falsifier and preserve existing request-shape,
   timeout, abort, stream, and error behavior.
3. Add the bounded attempt evidence response and turn component facts. Prove
   success, provider error, adapter timeout, outer timeout, and two-attempt
   retry ordering through focused turn tests and live database queries.
4. Extend the one `/agents/run` turn projection and the lossless solver copy.
   Older/incomplete responses remain diagnostic-only; capability admission
   fails before scoring.
5. Reconcile a dedicated local server's declared immutable identity with the
   database/run evidence. For MLX, use the same absolute revision-pinned
   snapshot in the dedicated listener and request, plus admitted package/server
   identity; do not trust `/v1/models` alone.
6. Run one focused native Inspect sample, finalize it, reopen it through
   Inspect's public API, and mechanically prove complete source/target
   admission, final database coordinate, every per-attempt coordinate,
   endpoint, both timeout layers, immutable artifact identity, and unchanged
   sample metadata.
7. Only after that proof close the blocker and admit the sample as formal P0b
   evidence. Existing logs are never backfilled; the current 0.5B result stays
   an admitted diagnostic.

The earliest settled boundary is step 1 plus the split-read falsifier. The
graduation gate is not “the final row can be reconstructed”; it is that every
provider attempt in a finalized native `.eval` proves the immutable request
configuration and server/model identity actually used, with any drift rejected
before capability scoring.
