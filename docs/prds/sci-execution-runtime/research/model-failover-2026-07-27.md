---
type: research
status: active
tags: [prd, research]
---

# Model failover: primary, backup, and unpaid retry

## Executive recommendation

Give each cluster a required primary model-target ref and an optional backup
model-target ref. Give each agent the same two optional override refs; absence
inherits the cluster value. A model target is data: it names a model and refers
to one hosted provider descriptor row. The descriptor selects
`:openai-compat` or `:anthropic`; failover never adds an adapter arm.

For one run's model phase:

1. Acquire the resolved primary, optional backup, failover policy, and retry
   strategy at one immutable database value.
2. Commit a running model-attempt receipt before every network call.
3. Call the primary once under the HTTP request's one deadline.
4. Reduce the flat error value to one computed disposition:
   `:failover-now`, `:backoff`, or `:fail`.
5. If a backup exists and the disposition is `:failover-now`, call it
   immediately, with no primary retry and no sleep. Its system context includes
   a bounded, credential-free notice naming the primary target, the normalized
   failure, and the fact that this call is the configured backup.
6. If no backup exists and the disposition is `:backoff`, retry the primary
   using the acquired exponential strategy. Every retry is a new receipt and a
   new HTTP call with the same one per-call deadline.
7. Never make another paid or ambiguously paid call. A 2xx response, any
   response carrying usage or generated content, a request timeout, and a
   transport loss after transmission are terminal even if a backup exists.

This reconciles instant failover with “nothing retries paid calls.” The ruling
forbids replay after a lost or paid attempt, not a second call after a
conclusively unpaid refusal. The critical implementation prerequisite is finer
evidence inside the current five flat error kinds: transport phase, HTTP
status, whether any response/output/usage arrived, and a normalized error
class. The result remains one flat `:seon.error` value.

## Scope and dependency ledger

This report is source-grounded at:

- `reference-code/litellm-clj` revision
  `14bcdd949c0207d6c4988a3db887a1a7fa1c5522`
  (`v0.3.0-alpha.2-19-g14bcdd9`);
- `reference-code/again` revision
  `0b4f195ba4d980253d807f919ed4e0d4829352a9`
  (`0.1.0-51-g0b4f195`);
- the fresh JVM seam in `src/seon/ai.cljc`,
  `src/seon/cluster/loop.cljc`, `src/seon/config.cljc`, and their EDN schemas;
- the State A quarry in `src-old/seon/ai/`, `src-old/seon/retry.cljc`, and the
  last complete failover owner at
  `bd357aa57:src/seon/agent/turn.cljs`; and
- the owner rulings in
  `docs/prds/sci-execution-runtime/plan/README.md:412-435` and the hosted
  provider boundary in `AGENTS.md:905-917`.

The relevant first-party mechanisms are:

- config manifests are closed, schema-validated dial maps, reconciled to one
  singleton row; runtime reads the database
  (`src/seon/config.cljc:10-28`, `src/seon/config.cljc:81-94`,
  `src/seon/config.cljc:136-172`);
- one current call supplies endpoint, model, credential-variable name, prompt,
  and timeout to `seon.ai/complete`
  (`src/seon/schema/ai.edn:5-16`);
- `seon.ai/complete` emits five flat failures and never retries
  (`src/seon/ai.cljc:117-130`, `src/seon/ai.cljc:146-183`);
- the run loop currently turns any completion error into only a
  `:seon.cluster.run/error` string and closes the run
  (`src/seon/cluster/loop.cljc:309-345`);
- that string was deliberately added because the previous error evaporated
  (`src/seon/schema/run.edn:34-42`,
  `test/seon/cluster/turn_test.clj:202-228`); and
- hosted provider identity is already ruled to be component descriptor rows
  under the config singleton, selecting only the two wire cores
  (`AGENTS.md:905-917`; the older target description gives the descriptor
  boundary at `docs/seon/architecture/data-model.md:656-676`).

## What `litellm-clj` actually provides

### Provider and router shape

`litellm-clj` has two distinct configuration ideas:

- named configs in a process-global atom, where a simple config is
  `{:provider ... :model ... :config ...}` and a dynamic router is a function
  returning provider and model
  (`reference-code/litellm-clj/src/litellm/config.clj:9-35`);
- a request-time resolver that returns exactly provider, model, and provider
  config (`reference-code/litellm-clj/src/litellm/config.clj:65-95`).

Its router uses that one resolved target for one core completion
(`reference-code/litellm-clj/src/litellm/router.clj:72-107`). The core validates
the provider and request, dispatches through provider multimethods, waits for
one response, and transforms it
(`reference-code/litellm-clj/src/litellm/core.clj:42-110`). DeepSeek, Kimi, and
Z.AI are separate provider multimethod implementations but reuse the
OpenAI-compatible HTTP core
(`reference-code/litellm-clj/src/litellm/providers/deepseek.clj:88-120`,
`reference-code/litellm-clj/src/litellm/providers/kimi.clj:105-136`,
`reference-code/litellm-clj/src/litellm/providers/zai.clj:100-132`). Seon's
descriptor-row rule is simpler: those identities remain data interpreted by
one `:openai-compat` core.

The library declares router schemas for routing strategy, fallback lists, and
retry config
(`reference-code/litellm-clj/src/litellm/specs.clj:165-184`;
`reference-code/litellm-clj/src/litellm/schemas.clj:231-255`), and provider
defaults include `:max-retries 3`
(`reference-code/litellm-clj/src/litellm/providers/core.clj:670-681`).
However, no source consumer connects those declarations or that default to
router execution. Fallback and retry live in optional wrappers, not the router.
The declarations are useful vocabulary, not an implemented policy engine.

### Fallback behavior

`with-fallback` accepts an ordered vector of config names. It calls the same
completion function with the same request map until a call stops throwing
(`reference-code/litellm-clj/src/litellm/wrappers.clj:11-50`).

Its important limitations are:

- every `Exception` triggers the next config; there is no error-class
  predicate, paid-call fence, or distinction between request and provider
  faults (`reference-code/litellm-clj/src/litellm/wrappers.clj:35-49`);
- it accumulates only config name and exception message for the final thrown
  error (`reference-code/litellm-clj/src/litellm/wrappers.clj:27-33`,
  `reference-code/litellm-clj/src/litellm/wrappers.clj:46-50`);
- it passes the original `request-map` byte-for-shape to every fallback, so the
  backup is not told that the primary failed; and
- it has no attempt receipt or durable observability.

An example implements a narrower policy outside the wrapper: client errors
stop, provider errors advance
(`reference-code/litellm-clj/examples/05_error_handling_example.clj:262-287`).
Even that example preserves the original message and only logs the first
failure. There is no library mechanism that injects the failure reason into the
fallback prompt.

### Retry behavior

`with-retry` defaults to three attempts, a 1,000 ms base, a 30,000 ms per-wait
cap, and a predicate that retries every exception
(`reference-code/litellm-clj/src/litellm/wrappers.clj:61-96`). Its delay is
`min(max, base * 2^(attempt-1))`; it has no jitter and ignores
`Retry-After` (`reference-code/litellm-clj/src/litellm/wrappers.clj:56-59`,
`reference-code/litellm-clj/src/litellm/wrappers.clj:96-110`). It sleeps the
calling thread.

The error namespace is substantially better than the generic wrapper. It marks
authentication, authorization, model/config/request faults, and quota
exhaustion as client errors
(`reference-code/litellm-clj/src/litellm/errors.clj:17-26`); rate limit,
timeout, connection, server, and generic provider failures are provider errors
(`reference-code/litellm-clj/src/litellm/errors.clj:28-34`). Constructors mark
auth and quota non-recoverable
(`reference-code/litellm-clj/src/litellm/errors.clj:87-106`,
`reference-code/litellm-clj/src/litellm/errors.clj:138-148`) and rate-limit,
timeout, connection, and server errors recoverable
(`reference-code/litellm-clj/src/litellm/errors.clj:154-195`).

`should-retry?` admits only recoverable rate-limit, timeout, server, and
connection errors
(`reference-code/litellm-clj/src/litellm/errors.clj:328-342`). Its delay logic
honors `Retry-After` for 429, otherwise uses exponential delay for 429,
exponential plus jitter for server errors, and linear delay for timeout and
connection errors
(`reference-code/litellm-clj/src/litellm/errors.clj:344-367`). This
classification is not wired into `with-retry` by default; a caller must pass
it as the predicate.

HTTP mapping is explicit:

- 400 invalid request;
- 401 authentication;
- 403 authorization;
- 404 model not found;
- 429 quota when the body mentions quota, otherwise rate limit;
- 408 timeout;
- 500, 502, 503, and 504 server error; and
- other statuses generic, recoverable only at 5xx

(`reference-code/litellm-clj/src/litellm/errors.clj:451-470`). Socket timeout,
connect, DNS, I/O, and SSL failures are normalized as timeout or connection
errors (`reference-code/litellm-clj/src/litellm/errors.clj:476-506`).
OpenAI-compatible responses retain provider code, request id, and
`Retry-After` before using the common mapping
(`reference-code/litellm-clj/src/litellm/providers/openai_compatible.clj:223-242`).

This classification is useful input, but its “recoverable” flag is too broad
for Seon's payment ruling. A timeout or connection loss can happen after the
provider accepted and billed the request. “Might succeed if tried again” is not
the same predicate as “safe to call again.”

### Cooldown and context propagation

A whole-tree source search finds no cooldown implementation. The only
persistent dependency suppression in the two mined libraries is `again`'s
circuit breaker, not `litellm-clj`. `litellm-clj` also has no propagation of
the primary failure into a fallback request. Its wrappers provide sequencing,
logs, and a terminal vector of messages only.

Recommendation: mine its status taxonomy, `Retry-After` handling, and ordered
target idea. Do not copy its mutable registry, generic catch-all wrappers,
timeout wrapper, or provider-per-multimethod architecture.

## What `again` contributes

An `again` retry strategy is just a lazy sequence of millisecond delays.
Builders produce constant, immediate, additive, or multiplicative sequences
(`reference-code/again/src/again/core.clj:3-37`). Pure manipulators add jitter,
retry count, per-delay clamp, per-delay cutoff, or cumulative delay budget
(`reference-code/again/src/again/core.clj:39-88`). The exponential example is
ordinary composition: multiplicative → jitter → max retries → total delay
budget (`reference-code/again/README.md:268-284`).

The executor makes one attempt plus one attempt per delay. Its callback sees
attempt count, accumulated sleep, exception, status, and optional caller
context; returning `::fail` stops early
(`reference-code/again/src/again/core.clj:120-174`,
`reference-code/again/src/again/core.clj:176-225`). `InterruptedException`
restores the interrupt flag and never retries
(`reference-code/again/src/again/core.clj:151-169`).

This is data-oriented because policy is a value before execution:

- a strategy can be printed, tested, truncated, mapped, and acquired from
  config-derived facts;
- composition is independent of the operation;
- the executor reports every attempt through one map-shaped observation hook;
  and
- early termination is a predicate over observed error data, not a second
  control-flow hierarchy.

`again` also has an immutable policy inside a shared stateful circuit breaker.
It admits one half-open probe and emits success, failure, short-circuit, and
state-transition events
(`reference-code/again/src/again/core.clj:227-284`,
`reference-code/again/src/again/core.clj:291-396`). That is not needed for this
ask. A process-local breaker would hide cluster-wide provider state in an atom;
a durable breaker would introduce a new product policy. Do not add cooldown or
a breaker until measured repeated failures justify it.

Recommendation: adopt the strategy-as-delay-data shape, not the exception
macro. Seon already returns failures as values, so the executor should reduce
over flat completion values and record attempt receipts. Do not adopt
`max-wall-clock-duration`: the HTTP request already owns the one legitimate
deadline, and retry count plus delay caps bound the additional waits without a
second racing clock.

## Current Seon seam and the State A lesson

The fresh seam is intentionally small. A request has one endpoint, model,
credential-variable name, prompt, optional system text, and timeout
(`src/seon/schema/ai.edn:5-16`). The wire body is one optional system message
plus one user message (`src/seon/ai.cljc:61-75`). The leaf reads the credential
from the named environment variable, creates one JVM HTTP request with one
request timeout, and sends it once
(`src/seon/ai.cljc:100-115`, `src/seon/ai.cljc:131-152`).

The five current error kinds are:

- `:seon.ai/no-credential`;
- `:seon.ai/timeout`;
- `:seon.ai/transport-failure`;
- `:seon.ai/provider-error`; and
- `:seon.ai/unparseable-body`

(`src/seon/ai.cljc:117-129`). Provider errors retain status and a bounded body;
timeouts retain the configured deadline; transport failures currently retain
only endpoint (`src/seon/ai.cljc:153-183`). The last point is insufficient for
safe retry because it cannot distinguish connect-before-send from
disconnect-after-send.

The fresh loop does not preserve the structured failure. It stores only the
message string, closes the run, and reports `:error`
(`src/seon/cluster/loop.cljc:313-345`). That is enough for one terminal error,
but it cannot tell an operator that the primary failed and the backup
succeeded, nor prove whether a second paid call was allowed.

State A had useful raw material:

- hosted provider descriptors were ordinary entity rows selecting only
  `:openai-compat` or `:anthropic`
  (`src-old/seon/ai/provider.cljc:26-45`,
  `src-old/seon/ai/provider.cljc:83-148`);
- model resolution selected descriptor rows and an optional fallback model
  variant from config data (`src-old/seon/ai/core.cljc:528-592`);
- the HTTP leaf performed one attempt and preserved status,
  `Retry-After`, timeout, transport, credential-source, and bounded response
  evidence as values (`src-old/seon/ai/http.clj:85-120`,
  `src-old/seon/ai/http.clj:172-252`);
- attempt rows captured target, deadline layers, outcome, status, response
  identity, credential class, and usage
  (`src-old/seon/ai/attempt.cljc:8-75`); and
- the port of `again` expressed exponential delays, jitter, clamp, count,
  total-delay cap, value predicates, `Retry-After` override, and an observation
  hook (`src-old/seon/retry.cljc:1-29`,
  `src-old/seon/retry.cljc:71-131`,
  `src-old/seon/retry.cljc:152-208`).

The last complete State A caller did the wrong sequencing for this ask: it
retried the primary to exhaustion and only then ran the fallback
(`bd357aa57:src/seon/agent/turn.cljs:910-965`). It passed the same prompt and
system text to both calls, so the fallback never learned why it was running
(`bd357aa57:src/seon/agent/turn.cljs:923-959`). It also treated every transport
error as retryable and timeout as fallback-eligible
(`bd357aa57:src/seon/agent/turn.cljs:718-758`), which is incompatible with the
new no-paid-call ruling because both can be ambiguous after transmission.

The conversion lesson is selective: keep rows, pure resolution, normalized
errors, delay composition, and receipts. Delete primary retry before fallback,
mutable attempt accumulation, dual timeout layers, and context-blind fallback.

## Recommended design

### Provider descriptors and primary/backup selection

#### Options

1. Flatten primary and backup endpoint/model/auth fields onto the config and
   agent entities. This is easy to read but duplicates every provider field
   twice and makes agent inheritance a large per-attribute overlay.
2. Keep provider descriptor rows plus named model-target rows, then store
   primary and optional backup refs on cluster config and agent entities. This
   separates wire facts from role selection and reuses rows.
3. Store an ordered cardinality-many fallback chain. This generalizes beyond
   the ask, but Datahike cardinality-many is a set, so order requires ordinals
   or a tuple and introduces complexity for a contract that has exactly two
   roles.

#### Recommendation

Choose option 2.

A hosted provider descriptor row should carry the State A wire facts that the
two adapter cores interpret:

```clojure
{:seon.ai.provider/id :deepseek
 :seon.ai.provider/adapter-core :openai-compat
 :seon.ai.provider/base-url "https://api.deepseek.com"
 :seon.ai.provider/credential-env "DEEPSEEK_API_KEY"
 :seon.ai.provider/credential-header "Authorization"
 :seon.ai.provider/credential-prefix "Bearer "
 :seon.ai.provider/endpoint-policy :openai-chat-completions
 :seon.ai.provider/retry-after-header "retry-after"}
```

A model target is a separate row:

```clojure
{:seon.ai.model-target/id :deepseek-primary
 :seon.ai.model-target/provider [:seon.ai.provider/id :deepseek]
 :seon.ai.model-target/model "deepseek-v4-pro"
 :seon.ai.model-target/timeout-ms 120000}
```

The config singleton has required `:seon.config.ai/primary` and optional
`:seon.config.ai/backup` refs. The agent has optional
`:seon.ai/primary` and `:seon.ai/backup` refs; each role independently
inherits from the cluster when absent. The connections express the roles;
there is no stored `:type` or `:kind`.

Model-target rows allow two roles to share one provider descriptor without
copying auth/wire data. Descriptor rows remain component data under the config
singleton. The resolved request freezes the two targets at the run's acquired
database value. Credentials remain environment values; only their variable
names become facts.

The failover policy is also data on the config singleton, not descriptor code:
retry base delay, multiplier, jitter fraction, maximum delay, maximum retries,
and maximum cumulative delay. Error classification is code derived from the
flat error value; do not put raw status allowlists on provider rows.

### Computed error-class rule

#### Options

1. Fail over on every primary error. This meets a literal reading of
   “primary failure” but duplicates paid/ambiguous work and repeats
   request-local failures.
2. Copy `litellm-clj`'s recoverable flag. This correctly excludes most 4xx
   faults but incorrectly retries timeout and generic connection failures
   without proving the first call was unpaid.
3. Compute disposition from normalized failure scope plus payment evidence.
   This is slightly more classification work and is the only option consistent
   with both instant failover and no paid retry.

#### Recommendation

Choose option 3. Preserve the five public error kinds, and add bounded,
namespaced evidence in `:seon.error/data`:

- `:seon.ai/error-class` — normalized class such as `:credential`,
  `:request`, `:authentication`, `:authorization`, `:model`, `:rate-limit`,
  `:quota`, `:server`, `:transport-before-send`,
  `:transport-unknown`, `:timeout`, or `:response`;
- `:seon.ai/http-status` when present;
- `:seon.ai/request-transmitted?`;
- `:seon.ai/response-started?`;
- `:seon.ai/output-observed?`;
- bounded `:seon.ai/usage` or a boolean usage-observed projection; and
- bounded provider request id and `Retry-After` when available.

These fields classify one value; they do not create nested error envelopes.
The leaf derives them from actual transport events and response fields. The
policy reducer returns one of three ordinary values.

| Current flat kind | Required evidence | Backup configured | No backup |
|---|---|---|---|
| `::no-credential` | No network call occurred | `:failover-now`; if backup resolves the same missing credential, fail without calling | `:fail` |
| `::transport-failure` | DNS/connect/TLS failure before request transmission | `:failover-now` | `:backoff` |
| `::transport-failure` | Transmitted or unknown phase | `:fail` | `:fail` |
| `::provider-error` | Explicit 408, 429, or 5xx rejection; no output or usage | `:failover-now` | `:backoff` |
| `::provider-error` | 401, 403, 404, or quota rejection; no output or usage | `:failover-now` | `:fail` |
| `::provider-error` | Request/content error such as 400, 409, 413, or 422 | `:fail` | `:fail` |
| `::provider-error` | Any output or usage evidence | `:fail` | `:fail` |
| `::timeout` | Deadline fired before a trustworthy terminal response | `:fail` | `:fail` |
| `::unparseable-body` | 2xx response exists | `:fail` | `:fail` |

“Timeout before response” is not proof of “before provider work.” Once a
request may have been transmitted, it is ambiguously paid and terminal. This
is the strict reconciliation of the owner ruling. If the HTTP client cannot
prove transmission phase, classify the transport failure as unknown and stop.

With a backup, the primary gets no retries: eligible errors switch immediately.
The backup itself is one call. The “no secondary” backoff path is the only
place the retry strategy runs.

### Backup context

#### Options

1. Reuse the original request unchanged, as `litellm-clj` and State A did. This
   violates the ask.
2. Append prose to the user's prompt. This makes the notice look like user
   intent and disturbs the most recent request.
3. Derive a bounded system-context notice from the normalized primary failure
   and prepend/append it to the backup's system message. This preserves the
   user's request and clearly assigns runtime provenance.

#### Recommendation

Choose option 3. A pure function in the portable `seon.ai` core—not the run
loop and not either wire leaf—takes:

```clojure
{:seon.ai/original-request ...
 :seon.ai/primary-target ...
 :seon.error/value ...
 :seon.ai/backup-target ...}
```

and returns the backup request. The wire cores continue to serialize ordinary
messages. The derived notice should have a stable shape:

> Failover notice: the configured primary model `<target>` did not produce a
> usable response. It failed with `<class>` (`HTTP <status>` when present):
> `<bounded message>`. You are running as the configured backup model. Continue
> from the original context; no primary answer is available.

Include provider/model target ids, normalized class, status, bounded message,
and request id only when non-secret. Exclude endpoint query strings, headers,
credential values, raw bodies, stack traces, and arbitrary exception data.
The original system text and prompt stay unchanged as their own fields; the
notice is an additional system-context segment. A backup-target descriptor may
change wire format, but it never owns this derivation.

The notice is generated only after the primary receipt is terminal. The backup
receipt links to the primary receipt, proving which flat failure supplied its
context. A deterministic notice function means the operator can rederive the
text from facts; storing a second prompt copy is unnecessary.

### Backoff when no backup exists

#### Options

1. Hard-code delays in the call loop. This hides policy, repeats State A's
   magic constants, and makes config changes require code.
2. Store the realized delay vector. This is simple but stores a derived
   projection and cannot express jitter without freezing every future delay.
3. Store strategy parameters as config facts and derive the finite delay
   sequence at the acquired database value.

#### Recommendation

Choose option 3, using the `again` composition:

```clojure
(-> (multiplicative-strategy base-delay-ms multiplier)
    (randomize-strategy jitter-fraction)
    (clamp-delay maximum-delay-ms)
    (max-retries maximum-retries)
    (max-duration maximum-total-delay-ms))
```

The corresponding singleton dials are positive numeric facts with units and
provenanced defaults:

- `:seon.config.ai.retry/base-delay-ms`;
- `:seon.config.ai.retry/multiplier`;
- `:seon.config.ai.retry/jitter-fraction`;
- `:seon.config.ai.retry/maximum-delay-ms`;
- `:seon.config.ai.retry/maximum-retries`; and
- `:seon.config.ai.retry/maximum-total-delay-ms`.

`Retry-After`, when present on an eligible rejection, replaces the next
strategy delay but is clamped by the same maximum delay and cumulative budget.
No separate wall-clock retry deadline exists. Each network attempt has the one
HTTP deadline because remote I/O is unobservable; retry waits are bounded by
finite data.

The exact safety boundary is:

- may retry: failure before request transmission, or an explicit non-2xx
  transient rejection with no output/usage evidence;
- must never retry: any 2xx response, any observed generation or usage, any
  timeout after possible transmission, any transport failure after
  transmission or with unknown phase, and any interrupted/crashed attempt;
- never auto-restart after process loss: an open receipt becomes
  `:interrupted`, and the next agent turn adapts from the derived warning.

This boundary is narrower than both `litellm-clj` and State A. It treats
payment ambiguity as terminal.

### Durable attempt facts

#### Options

1. Keep only the final run error string. Backup success erases the primary
   failure story, and no-paid-call compliance is unprovable.
2. Store a vector or EDN dump on the run. This is opaque, duplicates request
   data, and cannot be queried by provider/status/outcome.
3. Add component model-attempt receipts connected to the run, with a running
   row before the call and a terminal transition afterward.

#### Recommendation

Choose option 3. State A's attempt schema is a useful inventory but too broad
to copy. The fresh minimal receipt needs:

- identity, run ref, ordinal, and `:primary`/`:backup` role expressed by
  connection (`:seon.ai.attempt/failover-from` on a backup), not an entity
  kind;
- resolved model-target ref and provider-descriptor ref;
- status `:running`, `:success`, `:error`, or `:interrupted`;
- normalized error class, bounded message, HTTP status, provider request id,
  and `Retry-After` when present;
- request-transmitted, response-started, output-observed, and usage-observed
  evidence needed to audit the computed disposition;
- response model and bounded usage on success when supplied by the provider;
  and
- retry delay before the attempt when it is a same-primary backoff retry.

Transaction metadata supplies process, user, and time. The request prompt,
system text, endpoint, and model are derivable from the run's acquired
database value and referenced target rows; do not copy them onto every
receipt. Credential values never become facts. A credential-variable name is
already non-secret config data and need not be copied if the descriptor ref
can recover it.

Commit the running receipt before sending. Terminalize it after the result. On
process recovery, mark a running attempt `:interrupted`; never call either
target automatically. On backup success, continue the same run while retaining
the primary error receipt. On terminal failure, keep
`:seon.cluster.run/error` as the agent-readable summary, but derive the complete
operator story from attempt receipts.

The receipt is warranted despite the crash model's general aversion to replay
ledgers: a remote call's occurrence, result identity, and payment-safety
evidence are non-derivable facts. The receipt authorizes no replay.

### Sealed falsifiers

#### Options

1. Unit-test a few status predicates. This can leave config acquisition,
   persistence, prompt derivation, and crash behavior unproved.
2. Mock only the final completion function. This cannot prove transport-phase
   classification or the absence of a second call.
3. Seal pure policy properties plus one instrumented HTTP boundary and one
   database-backed run-loop proof.

#### Recommendation

Choose option 3. The design is not sealed until all of these falsifiers recur
under the maintained test runner:

1. Config reconciliation refuses an unknown target or descriptor, resolves
   cluster primary plus optional backup, and applies each agent override
   independently. A compatible provider added as one descriptor row reaches
   an existing wire core with no provider-specific dispatch branch.
2. Primary success performs exactly one HTTP call, writes exactly one terminal
   attempt receipt, and adds no failover notice.
3. Eligible primary failure with a backup performs exactly two calls and zero
   sleeps. The backup request contains the original prompt unchanged and a
   bounded system notice naming the primary failure and backup role.
4. Missing primary credential fails over only when the backup does not resolve
   to the same absent credential. No secret value appears in request logs,
   prompt notice, receipts, or database.
5. With no backup, a seeded/injected-randomness property proves the finite
   exponential sequence, jitter range, per-delay clamp, retry count, total
   delay budget, and clamped `Retry-After`.
6. 429 and 503 responses with no output/usage fail over immediately or back off
   when no backup exists. 401/403/404 can fail over but never back off. 400 and
   422 do neither.
7. A DNS/connect failure proven before transmission may fail over/back off. A
   reset after transmission and an unknown-phase I/O exception make exactly
   one call and terminate.
8. A request timeout makes exactly one call even with a backup. A malformed
   2xx response, a 2xx response carrying usage but no readable assistant text,
   and any partial-output failure also make exactly one call.
9. Primary failure followed by backup success leaves two queryable receipts,
   the backup links to the primary, and the run proceeds. Two failures leave
   both receipts and close the run with one readable summary.
10. Killing the process with a running attempt marks it interrupted on reopen,
    performs zero automatic calls, and produces the existing derived
    interruption guidance on the next trigger.
11. The database proof can answer, without logs: which target ran, why the
    second call was allowed, what failure text the backup saw, whether any
    output/usage preceded failover, and which call ultimately supplied the
    reply.
12. A structural grep/test proves there is one failover policy reducer, one
    HTTP deadline per attempt, no retry in either wire core, and no provider id
    in adapter dispatch beyond descriptor selection.

## Decision summary

The useful idea from `litellm-clj` is ordered targets plus normalized provider
errors. The useful idea from `again` is retry policy as a finite derivation of
delay data with an observation hook. The useful idea from State A is
descriptor rows and per-attempt evidence.

The recommended system is simpler than State A:

- primary once;
- immediate backup once when conclusively safe;
- otherwise, and only without a backup, bounded retry of conclusively unpaid
  transient failures;
- one pure backup-context derivation;
- one computed disposition reducer;
- one receipt shape; and
- the same two wire cores.

There is no fallback adapter, retry adapter, cooldown registry, process-local
router, or replay mechanism.
