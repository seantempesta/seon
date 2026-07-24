---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# JVM claimant LLM diagnostic-loss audit (2026-07-24)

## Scope and ownership

This audit covers only the diagnostic path named by
[[../../../seon/issues/jvm-claimant-provider-errors-drop-the-diagnostic-cause]].
It traces the JVM HTTP leaf's error value through the claimant deadline wrapper
and durable attempt projection. It does not diagnose or change the underlying
long-lived transport failure.

The only path owned by this audit lane is this report. Source, tests, issue
notes, architecture, roadmaps, and the running cluster remain unmodified.

## Verdict

There are two independent losses:

1. `seon.ai.http/request!` catches JVM exceptions but discards each
   `Throwable`, retaining only a generic category message. The exception class
   and concrete exception message therefore never enter ordinary data. A 2xx
   response whose JSON is invalid also loses the response body when parsing
   throws.
2. `seon.agent.turn.llm/attempt-row` receives the resulting
   `:seon.ai/error` value but projects only `:seon.ai/status`. It drops the
   already-produced `:seon.ai/msg`, `:seon.ai/transport?`, and
   `:seon.ai/raw-body`. `terminal-attempt-tx-data` faithfully persists whatever
   `attempt-row` supplies, so it is not the loss point.

The smallest class-killing regression is a portable `attempt-row` test that
supplies every normalized failure field and asserts the complete bounded flat
receipt projection. A JVM real-socket matrix is additionally required to prove
that `request!` produces those fields for each leaf failure class.

## Dependency ledger

| Dependency or mechanism | Selected revision | Grounding used here |
|---|---|---|
| Seon source | `404bd02be0ff6f9d0de28166ade76ed304a56b86` on `codex/runtime-reliability-refactor` | `src/seon/agent/turn/llm.cljc`, `src/seon/agent/turn/core.cljc`, `src/seon/agent/driver/host.clj`, `src/seon/ai/http.clj`, `src/seon/ai/core.cljc`, `src/seon/agent/turn.cljs` |
| U6b JVM HTTP leaf | commit `200e847e9` | Establishes one process-shared `HttpClient`, call-time environment lookup, and the original catch-to-value mapping |
| Clojure | `1.12.0` in `deps.edn` | Existing JVM interop and `ex-message`; no new exception mechanism is required |
| OpenJDK | running `26.0.1` | `java.net.http.HttpClient`, `HttpResponse`, `HttpTimeoutException`, `ConnectException`, `IOException`, and `Throwable.getClass().getName()`; installed source archive is `/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home/lib/src.zip` |
| Jsonista | selected `0.3.14` in the `:writer:host` dependency tree | `jsonista.core/read-value` at `src/seon/ai/http.clj:91-93`; this audit relies only on the first-party catch boundary, not undocumented Jsonista behavior |
| Malli | `0.20.0` in `deps.edn`; reference checkout `80138076960e7820523b4cb932c5b5d1936d4e7f` | Optional scalar receipt fields follow the existing `:map` entries at `src/seon/agent/turn.cljs:201-239`; reference map optionality is in `reference-code/malli/src/malli/core.cljc:1210-1299` |
| Datahike | local root `reference-code/datahike` at `caf526850084a9d5846ccd9ea34251fe411e0d6b` | The terminal open-to-outcome CAS remains authoritative; Datahike's CAS implementation is `reference-code/datahike/src/datahike/db/transaction.cljc:963-985` |

The maintained design authority is
[[llm-http-io-design-2026-07-23]], especially its failure table: transport
reset must retain the transport classification, HTTP failures must retain
status, malformed responses must retain bounded body evidence, and every
attempt terminal is durable data.

## Shortest falsifier

Evaluating the current `attempt-row` directly with this already-normalized
failure:

```clojure
{:seon.ai/error
 {:seon.ai/msg "connection reset by peer"
  :seon.ai/transport? true
  :seon.ai/status 503
  :seon.ai/raw-body "provider diagnostic body"}}

```

returned:

```clojure
{:seon.ai.attempt/outcome :provider-error
 :seon.ai.attempt/error-status 503}

```

Selecting the natural flat diagnostic attributes returned `{}`. The probe ran
through `clojure -M:writer:host` against the source revision above. It proves
that status is the only failure detail currently crossing the projection even
when the leaf response already contains the other details.

## End-to-end trace

### 1. JVM leaf produces or suppresses the cause

`src/seon/ai/http.clj:27-31` owns the one leaf failure constructor. The
currently available normalized non-secret vocabulary is registered at
`src/seon/ai/core.cljc:17-48`:

| Producer field | Meaning | Current producer evidence |
|---|---|---|
| `:seon.ai/msg` | human-readable error message | Present on every leaf failure |
| `:seon.ai/transport?` | request failed before an HTTP status; retryable transport class | Shared-client mismatch, timeout, connect failure, and non-interrupt `IOException` |
| `:seon.ai/status` | HTTP response status | Produced by `status-failure` |
| `:seon.ai/raw-body` | HTTP response body | Produced by non-2xx `status-failure` |
| `:seon.ai/timeout?` | adapter/request timeout classification | Produced for `HttpTimeoutException` |
| `:seon.ai/retry-after-ms` | parsed provider retry delay | Produced by `status-failure` when present |
| `:seon.ai/outer-timeout?` | claimant deadline classification | Produced by `bounded-llm-transport!`; not yet registered in `:seon.ai/error` |

Exception class is available at every JVM catch as
`(.getName ^Class (class throwable))`, an idiom already used at
`src/seon/db/transport/uds.cljc:207-210`, but no `:seon.ai/*` field currently
owns it. The catch clauses at `src/seon/ai/http.clj:188-214` bind `_`, so both
the class and `ex-message` are discarded.

The individual leaf cases are:

- `process-client`, `src/seon/ai/http.clj:33-48`: a process-shared client whose
  connect-timeout differs from the current request returns an immediate error
  with an exact diagnostic message and `:seon.ai/transport? true`. That message
  is sufficient to distinguish this long-lived-client state from provider
  rejection, but `attempt-row` currently drops it. The observed fast repeated
  failures are compatible with this path, but this audit does not claim that it
  is the live root cause.
- Credential resolution, lines 50-57 and 215-218: the environment is read at
  call time. No-credential returns an exact message and config evidence; it is
  correctly not transport-flagged.
- HTTP non-2xx, lines 79-89: status, raw body, and optional Retry-After are
  already ordinary data.
- Batch JSON parsing, lines 91-104: the complete successful HTTP body is still
  in local `body` when `read-json` throws, but the outer generic catch cannot
  recover it.
- Stream JSON parsing, lines 111-148: the current SSE `data` value is available
  when `read-json` throws but is similarly lost.
- JVM exception mapping, lines 186-214: timeout, connection, I/O, invalid
  request, and invalid response categories return values, but every concrete
  exception cause is thrown away.

The API key is resolved separately and is used only to build a header at
`src/seon/ai/http.clj:59-73`; neither the key nor headers belong in any
diagnostic field. The request endpoint is produced by
`seon.ai.core/openai-chat-endpoint`, which reconstructs scheme, host, port, and
path without URI user info (`src/seon/ai/core.cljc:101-135`).

### 2. Provider core preserves the leaf value

`seon.ai.openai-compat.core/complete` returns a native leaf error unchanged at
`src/seon/ai/openai_compat/core.cljc:189-233`. The Anthropic core does the same
at `src/seon/ai/anthropic/core.cljc:110-155`. Neither is a diagnostic loss
point.

### 3. Claimant deadline wrapper preserves ordinary leaf failures

`seon.agent.driver.host/bounded-llm-transport!` at
`src/seon/agent/driver/host.clj:98-125` invokes the installed leaf directly. It
only replaces a watchdog-caused `InterruptedException` with a flat outer
timeout value containing:

- an exact `:seon.ai/msg`;
- `:seon.ai/timeout? true`; and
- `:seon.ai/outer-timeout? true`.

Every ordinary value from `seon.ai.http/complete` passes through unchanged.
An external interrupt is rethrown deliberately. This wrapper is therefore not
the ordinary provider-error loss point, although its outer-timeout fields must
also survive the terminal projection.

The production installation is one mechanism:
`src/seon/host.clj:336` installs `seon.ai.http/complete`, and
`src/seon/agent/driver/host.clj:454-480` passes the bounded wrapper to the
portable LLM phase.

### 4. `attempt-row` drops the normalized error

`src/seon/agent/turn/llm.cljc:187-268` is the decisive loss:

- lines 214 and 254-255 retain only `:seon.ai/status` as
  `:seon.ai.attempt/error-status`;
- no code reads `:seon.ai/msg`;
- no code reads `:seon.ai/transport?`;
- no code reads `:seon.ai/raw-body`;
- no code reads an exception class; and
- timeout booleans influence only `attempt-outcome` at lines 172-185.

The applied timeout values are already durable:
`:seon.ai.attempt/outer-timeout-ms` is always written, and
`:seon.ai.attempt/adapter-timeout-ms` is written when configured. The terminal
outcome already distinguishes `:adapter-timeout` from `:outer-timeout`.

The existing `:seon.ai.attempt/evidence-error` is not a replacement for the
provider error message. Lines 204-211 reserve it for endpoint normalization or
bounded response-identity failures. Conflating transport cause with response
identity would make queries ambiguous.

### 5. Receipt persistence is faithful

`durable-attempt!` computes the terminal row at
`src/seon/agent/turn/llm.cljc:373-399` and passes it to
`terminal-attempt-tx-data`. The latter merges every supplied terminal field
onto the attempt entity under the run fence and open-to-terminal CAS
(`src/seon/agent/turn/core.cljc:97-117`). No extra allowlist drops fields here.

The receipt schema at `src/seon/agent/turn.cljs:157-239` currently registers
status and evidence-error but has no message, exception-class, transport, or
body attributes. Adding row keys without registering these attributes would
make the transaction fail admission, so schema and projection must land
together.

## Minimal flat receipt contract

Keep the normalized leaf vocabulary and add only the direct scalar projection
needed at the durable boundary:

| Attempt attribute | Direct source | Shape and bound |
|---|---|---|
| `:seon.ai.attempt/error-message` | `:seon.ai/msg` | optional non-empty string, capped with the resolved `response-identity-cap` before transaction data |
| `:seon.ai.attempt/exception-class` | `:seon.ai/exception-class` | optional non-empty JVM class-name string; absent for ordinary HTTP responses and configuration values |
| `:seon.ai.attempt/transport?` | `:seon.ai/transport?` | optional boolean; store only when the leaf supplied it |
| `:seon.ai.attempt/error-status` | `:seon.ai/status` | already implemented |
| `:seon.ai.attempt/error-body` | `:seon.ai/raw-body` | optional string capped with the same resolved evidence cap before transaction data |
| `:seon.ai.attempt/outcome` | timeout flags through `attempt-outcome` | already distinguishes adapter and outer timeout |
| `:seon.ai.attempt/adapter-timeout-ms` and `/outer-timeout-ms` | frozen resolution and admitted deadline | already implemented |

At the leaf boundary, register optional `:seon.ai/exception-class` and
`:seon.ai/outer-timeout?` in the existing `:seon.ai/error` shape. The exception
message should remain the existing `:seon.ai/msg`, with the concrete
`ex-message` appended to the category headline; a parallel nested cause map is
unnecessary. The class is producer vocabulary directly grounded in Java's
exception class, not an entity taxonomy.

`error-message`, `exception-class`, `transport?`, and `error-body` remain
absent on success. Do not store nil, a Throwable, headers, request body,
credential value, whole configuration, or a serialized nested error map.

## Exact insertion points

1. `src/seon/ai/core.cljc:17-48`
   - Register `:seon.ai/exception-class` and
     `:seon.ai/outer-timeout?`.
   - Add them as optional leaves of the existing `:seon.ai/error` map.
2. `src/seon/ai/http.clj:27-31`
   - Strengthen the one `failure` mechanism with a private Throwable-to-fields
     helper; bind the actual exception at lines 188-214.
   - Preserve the concrete `ex-message` in `:seon.ai/msg` and class name in
     `:seon.ai/exception-class`, while retaining the current category flags.
3. `src/seon/ai/http.clj:91-104` and `:125-147`
   - Convert JSON parse failures where the response text is still available
     into the same failure value and include that text as
     `:seon.ai/raw-body`. This is required for the invalid-response acceptance
     case; the outer catch cannot reconstruct the body.
4. `src/seon/agent/turn/llm.cljc:191-268`
   - Bind the normalized failure once.
   - Bound message and body with the already-resolved
     `:seon.config.model-transport/response-identity-cap`.
   - Conditionally project the four new flat attempt fields alongside the
     existing status.
5. `src/seon/agent/turn.cljs:193-239`
   - Register the new attempt scalar attributes and add them as optional keys
     of `:seon.ai.attempt/entity`.
6. `src/seon/web/serve.cljs:1001-1076`
   - If the existing attempt-evidence JSON is part of the operator proof, add
     the new attributes to its pull pattern and direct JSON projection. This is
     observability of the same receipt, not a second diagnostic store.

No change is needed in `terminal-attempt-tx-data`, retry classification, the
attempt CAS, claimant dispatch, or provider cores.

## Regression design

### Smallest regression that kills diagnostic projection loss

Extend `attempt-terminal-evidence-is-portable-and-bounded` in
`test/seon/agent/turn_llm_test.cljc:26-38`. Feed one normalized error containing
message, exception class, transport flag, status, raw body, timeout flag, and a
sentinel credential-like substring longer than the configured evidence cap.
Assert:

- exact terminal outcome and existing status;
- message, class, transport flag, and body are present under their flat attempt
  attributes;
- message and body do not exceed the resolved cap;
- adapter and outer timeout values/outcomes remain correct; and
- the receipt contains neither config secrets nor nested `:seon.ai/error`.

Because this test is `.cljc`, both maintained runners claim the same portable
projection contract. One test is enough to make future field-dropping at
`attempt-row` impossible to miss.

### JVM leaf producer matrix

Add one table-oriented real-socket test beside
`test/seon/ai/http_test.clj:77-213`, reusing its local `HttpServer` and the
actual `bounded-llm-transport!`. Drive the same long-lived host/client state
through:

- no credential;
- changed process-shared connect timeout;
- invalid request construction;
- closed-port transport failure;
- HTTP non-2xx with status and body;
- HTTP 2xx with invalid JSON; and
- watchdog timeout.

For each case assert the normalized error message plus applicable exception
class, transport flag, status/body, or timeout flag. Then pass that response to
`attempt-row` and assert the same applicable evidence is present on the
terminal receipt projection. The changed-timeout case must perform both calls
in one process; a fresh process cannot exercise the long-lived-client branch.

The existing `watchdog-interrupt-of-hung-http-is-a-flat-timeout` proves only
that a map with `:seon.ai/timeout?` returns. It does not assert the message,
outer classification, exception evidence, or durable receipt.

## Acceptance proof

Diagnostics are restored when all of the following are observed:

- the portable projection regression is green on both JVM and CLJS runners;
- the real-socket JVM matrix distinguishes all six requested failure classes
  without exposing a credential or header;
- a real claimant attempt persists a non-success component receipt containing
  its bounded flat cause and applicable classification;
- an HTTP failure retains status and bounded body, while a transport failure
  has no invented status;
- adapter and outer timeouts retain both their outcome and the already-frozen
  timeout layer values; and
- the next live DeepSeek attempt exposes a concrete cause from the long-lived
  claimant, enabling the separate transport-root-cause unit.

The final provider success and multi-turn alive proof belong to the parent
claimant-transport blocker, not to this diagnostics-only audit.
