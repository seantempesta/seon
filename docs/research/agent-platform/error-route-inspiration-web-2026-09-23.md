---
type: research
created: 2026-09-23
status: evidence (web survey)
---

# Error route: web survey of libraries, error trackers and agent frameworks (2026-09-23)

Question (owner, verbatim): "Not sure if there are good open source packages for
handling this stuff better." Target: ONE error mechanism every catch calls, storing
each unhandled error in Datahike with its full cause chain, deduplicated by a
fingerprint with a counter, waking the responsible agent, with a `:panic`/`:record`
dial, database-down always panics, and panics visible to REPL/MCP coding agents.

Method: web pages and GitHub/Clojars metadata read on 2026-09-22/23 (dates below are
from the GitHub API and the Clojars API at that time), plus source read directly
where the docs were silent: Sentry's grouping strategy
(`getsentry/sentry` master at `371c09d88`), Sentry Java's dedupe and uncaught
handler, Telemere at `27219639e`, and Seon's vendored `reference-code/core.async`
at `dc35f3e0d` (v1.10.874-alpha3, the version in `deps.edn:23`). This file is
evidence, not a plan; siblings from the same day cover flow, Datahike/Clojure and
Malli/SCI/kondo prior art (`error-route-inspiration-*-2026-09-23.md`) and the catch
census (`swallowed-errors-census-2026-09-23.md`).

## Verdict first

No library does the job. Every candidate either (a) shapes errors at the throw site
(anomalies, ex, failjure, slingshot, Truss), (b) routes signals to asynchronous,
lossy handlers (Telemere, mulog), or (c) ships events to a SaaS (sentry-clj). None
stores into a database, none wakes an agent, and the two routers deliberately
**drop** under back-pressure, which contradicts "no swallowed errors". Adopting one
would be a second mechanism beside `seon.error`. **Recommendation: build on Seon's
existing pieces and borrow four designs**: Sentry's grouping rules (including its
Clojure `$fn__N` normalisation), Sentry Java's throwable-identity dedupe, a
Sentry-style buffered counter flush for bursts, and Clojure's own `agent`
`error-mode` / JVM uncaught-handler chaining for the backstop. Details in the last
section.

## 1. Clojure libraries

| Library | Latest (date) | Activity | Call-site shape | Policy centralised? | Dedup / fingerprint | Fit |
|---|---|---|---|---|---|---|
| [cognitect.anomalies](https://github.com/cognitect-labs/anomalies) | `com.cognitect/anomalies 0.1.12` on Maven Central (2017) | README touched 2024-07; otherwise static | a map with `::anom/category` (9 categories: `:unavailable :interrupted :busy :incorrect :forbidden :unsupported :not-found :conflict :fault`) | no; it is a vocabulary with a retryable?/fix table | none | vocabulary only. Seon already declares its own error schemas; adding categories is a taxonomy, which AGENTS.md forbids as a discriminator stamp |
| [exoscale/ex](https://github.com/exoscale/ex) | 0.4.2 (2025-03-10) | commits into 2026-09 (CI) | `ex/try+` with `(catch ::type {:keys ...} ...)`; `ex/derive` builds a keyword hierarchy; anomalies-derived categories | no central handler; matching by type hierarchy | none | a `try+` dialect plus a type hierarchy = a kind taxonomy. No storage or routing |
| [failjure](https://github.com/adambard/failjure) | 2.3.70 (2026-09-22) | active, small | `f/fail`, `f/attempt-all`, `f/try*`, `HasFailed` protocol: errors as returned values | no | none | value-returning style; conflicts with Seon's "invalid output refuses" contracts and would need a second path at every boundary |
| [slingshot](https://github.com/scgilardi/slingshot) | 0.12.2 (2015-02-09) | last commit 2019-10 ("Status note, path to 1.0") | `try+`/`throw+` over arbitrary maps | no | none | superseded by `ex-info`; unmaintained |
| [Telemere](https://github.com/taoensso/telemere) (Timbre successor) | 1.4.0 (2026-07-29) | active (commit 2026-09-17) | `error!`, `catch->error!`, `trace!`, `spy!`, `signal!`; context via `with-ctx`/`*ctx*`; `uncaught->error!` installs the JVM default handler (`telemere.cljc:382-407`) | yes: signals → `add-handler!` handlers with per-handler filters, transforms (`:xfn`), `:error-fn`, `:backp-fn`, async dispatch with buffer and threads, `get-handlers-stats` ([Handlers wiki](https://github.com/taoensso/telemere/wiki/4-Handlers)) | rate limiting, not dedup: `:limit #{"4/1h"}` with `:limit-by <any value>` enforces the limit per key (`main/resources/docs/signal-options.txt:33-34`); `:sample` drops randomly | closest in spirit (one call, one policy). But a rate-limited or sampled signal is **dropped**, handler dispatch is async and may drop, and content is evaluated lazily on the handler thread. As the mechanism it would be a second router beside `seon.error`; as a handler target it adds nothing a Datahike transaction does not. Borrow `:limit-by` (limit keyed by fingerprint) as an idea only |
| [mulog](https://github.com/brunobonacci/mulog) | 0.10.1 (2026-02-06) | "feature complete"; core PRs not accepted | `μ/log ::event :k v`, `μ/trace ::op [ctx] body` (records `:mulog/outcome :error` + `:exception`, rethrows), `with-context`, `set-global-context!` | yes: publishers (console, file, Kafka, ES, …) behind a protocol | none; ring buffer where dropping is "preferable to crashing" | same objection as Telemere: lossy by design, no dedup, no storage of a cause chain as facts |
| [Truss](https://github.com/taoensso/truss) | 2.5.1 (2026-07-29) | active | `(have pred x)` assertions; `truss/ex-info` with timestamp, predicate, argument, callsite, `*ctx*` | no | none | Seon's armed Malli contracts already give predicate + value + location |
| [Guardrails](https://github.com/fulcrologic/guardrails) | 1.3.4 (2026-09-05) | active | `>defn` with inline spec/Malli gspec; `guardrails.edn` `:throw?` makes failures advisory or fatal | one EDN dial (`:throw?`), disabled unless a JVM option enables it | none | a parallel function-contract system to Seon's `:malli/schema` + instrumentation. Its advisory/fatal dial is the same idea as `:seon.config/on-core-error` |
| [sentry-clj](https://github.com/getsentry/sentry-clj) | 8.34.247 (2026-03-05), wraps sentry-java 8.x (sentry-java latest 8.57.0, 2026-09-16) | maintained by Sentry | `(sentry/send-event {:message .. :throwable e :extra .. :fingerprints [..]})`; `:in-app-includes/-excludes`; `:enable-uncaught-exception-handler` default true | Sentry server-side | Sentry server grouping (below); client-side `DuplicateEventDetectionEventProcessor` | SaaS/self-host Sentry is an external store; Seon's authority is Datahike. Useful as a source of algorithms, not as a dependency |
| [Integrant](https://github.com/weavejester/integrant) | 1.0.1 (2025-10-02) | maintained | `ig/init` throws `ex-info` with `:reason ::build-threw-exception`, `:system` (the partial system), `:key`, so the caller can `ig/halt!` what was built (`integrant/core.cljc:411-412`; also `::run-threw-exception` at `:358`) | lifecycle only | none | pattern worth noting: a failure carries the partially built value so the owner can release it. Seon's boot already publishes readiness per layer |

Also in the space, not libraries: Clojure's own `agent` has `:error-mode` `:fail`
(agent stops, `agent-error` shows the exception, `restart-agent` resumes) or
`:continue` with an `:error-handler` fn. That is exactly the `:panic`/`:record`
dial, in core, with a positively visible failed state.

**"Error as data + one handler" libraries.** None found beyond the above. The
pattern in the ecosystem is "ex-info + ex-data" at the throw, plus Telemere/mulog
(or tools.logging) as the one handler. The piece Seon needs that nobody ships is the
handler whose sink is a transacted, deduplicated, queryable fact.

## 2. Error-tracker grouping and counting

### Sentry (the most complete, source read)

Precedence: fingerprint first, then stack trace, then exception, then message
([event grouping](https://docs.sentry.io/concepts/data-management/event-grouping/)).
From `src/sentry/grouping/strategies/newstyle.py`:

- **A frame contributes module, filename (only if no module), function, and the
  context line where the platform supplies one. Line numbers never contribute**
  (`frame()`, lines 304-380; `lineno` appears only in `RECURSION_COMPARISON_FIELDS`
  for collapsing recursive frames). Rollbar also drops line numbers "because they
  often change due to unrelated code changes"
  ([Rollbar grouping](https://docs.rollbar.com/docs/grouping-algorithm)).
- **Java normalisation** (`get_module_component`, 169-211; `get_function_component`,
  213-300): a module containing `$$Lambda$` does not contribute; a function starting
  `lambda$` does not contribute; `GeneratedMethodAccessorN` collapses; CGLIB,
  Javassist, Guice `EnhancerBy`/`FastClassBy`, Hibernate proxy suffixes become
  `<auto>`; and **Clojure anonymous functions `myapp.mymodule$fn__12345` become
  `$fn__<auto>`** (`_clojure_enhancer_re`, line 74). Seon would need the same for
  `$fn__N`, `$eval1234`, `$reify__N` and SCI-generated frames.
- **In-app**: two hashes per event, an "app" hash over in-app frames only and a
  "system" hash over all; either matching an existing group suffices
  ([grouping dev docs](https://develop.sentry.dev/backend/application-domains/grouping/)).
  A stack with no in-app frames does not contribute to the app variant (`stacktrace()`,
  462-476).
- **Exception**: type + value + stacktrace, but **the value (message) is dropped
  whenever the stacktrace contributes** ("ignored because stacktrace takes
  precedence", `single_exception`, 554-640). Without a stack, the message is
  parameterised (numbers, ids and similar replaced by placeholders; trimmed to two
  lines; `grouping/utils.py:57`).
- **Chained exceptions**: every exception in the chain contributes its own
  component, wrapped in a `ChainedExceptionGroupingComponent` (`chained_exception`,
  645-718); exception-group wrappers with one inner exception are discarded;
  RxJava/Kotlin diagnostic wrappers are skipped when choosing the main exception
  (998-1058).
- **Versioned config**: "Each time default error grouping behavior is modified,
  Sentry releases it as a new version, which is only applied to new events going
  forward." Changing the algorithm never regroups history. In Seon terms the
  fingerprint derives from a declared algorithm version (the definition digest).
- **Custom rules**: server fingerprint rules `error.type:… stack.function:… ->
  {{ default }} extra` and stack-trace rules `stack.module:… -app / -group`
  ([fingerprint rules](https://docs.sentry.io/concepts/data-management/event-grouping/fingerprint-rules/),
  [stack trace rules](https://docs.sentry.io/concepts/data-management/event-grouping/stack-trace-rules/)).
  The SDK equivalent is `:fingerprints` on the event (sentry-clj README).

### Others

- **Rollbar**: SHA1 over filenames + method names of **all** frames plus the
  exception class; strips dates and SHAs from paths, integers of 2+ digits from method
  names, the server root, framework boilerplate frames; message excluded by default.
- **Honeybadger**: "file name, method name, and line number of the error's location"
  + exception class + component
  ([Honeybadger](https://docs.honeybadger.io/guides/errors/)). Line-number sensitive,
  so every edit splits a fault.
- **Bugsnag**: error class + file + line number of the **top in-project frame** of
  the innermost exception; overridable by a custom grouping hash or discriminator
  ([Bugsnag](https://docs.bugsnag.com/product/error-grouping/)).

Consensus rule for a JVM exception: class of each exception in the cause chain (or of
the root cause) + the ordered in-app frames as `class/namespace + method` with
generated-name suffixes normalised and line numbers excluded; message excluded when
a stack exists. Seon's D13 `signature` (`src/seon/error.clj:151-177`) already
excludes message, time and process and includes layer, operation, declared schema,
throwable class, one frame, expected key/shape and location path. It is a site
identity; compared with Sentry it is coarser on the stack (one frame, one class) and
richer on declared context. The gap to test: two different root causes behind the
same wrapper class at the same site collapse together.

### Counting cheaply

- **Sentry server**: counters are incremented through a write buffer, not per event:
  `buffer_incr(Model, {"times_seen": 1}, …)` (`src/sentry/event_manager.py:321`,
  `:1003`), flushed by `sentry.tasks.process_buffer`. The first event of a new group
  creates the group row immediately (`times_seen = 1`, line 1596).
- **Spike protection**: per-project hourly limit = max(`3 × quota / (720 × projects)`,
  a seasonality-weighted 7-day projection × 3-6 standard deviations); over the limit
  events are **discarded** and dropped events decay to ~10 % weight in 24 h
  ([spike protection](https://docs.sentry.io/pricing/quotas/spike-protection/)).
  Useful only as the lesson that a billing product drops; Seon must not.
- **Sentry Java client dedupe**: `DuplicateEventDetectionEventProcessor` keeps a
  synchronized `WeakHashMap` of captured throwables and drops an event whose throwable,
  **or any of its causes**, was already captured
  ([source](https://github.com/getsentry/sentry-java/blob/main/sentry/src/main/java/io/sentry/DuplicateEventDetectionEventProcessor.java)).
  This is the answer to "every catch calls the mechanism": a caught, recorded,
  wrapped and rethrown exception is recorded once, not at every enclosing catch.
- **Telemere** `:limit`/`:limit-by`, **mulog** ring buffer, **core.async.flow**
  `error-chan` (below): all drop.

## 3. JVM backstops

- `Thread/setDefaultUncaughtExceptionHandler` is the process backstop. Practice
  (Sentry Java `UncaughtExceptionHandlerIntegration`,
  [source](https://github.com/getsentry/sentry-java/blob/main/sentry/src/main/java/io/sentry/UncaughtExceptionHandlerIntegration.java)):
  capture the previous handler and chain to it (lines 89-94, 150-152), mark the event
  `handled=false` (166), block until the event is flushed to disk with a bound and
  log if the flush timed out (134-140), and remove itself from the chain on close
  (181, 206). Telemere's `uncaught->handler!` does the same install
  (`telemere.cljc:382-399`).
- **Where the handler is and is not reached.** `ExecutorService.submit` and
  `clojure.core/future` capture the throwable in the Future: it surfaces only on
  deref and never reaches the uncaught handler. `Executor.execute` and a raw `Thread`
  do reach it. Virtual threads use the default handler unless a
  per-thread handler is set through `Thread.Builder.uncaughtExceptionHandler`
  ([Thread.Builder](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.Builder.html),
  [JEP 444](https://openjdk.org/jeps/444)).
- **core.async 1.10.874-alpha3 (vendored).** `dispatch/ex-handler` hands a throwable
  to the current thread's uncaught handler and returns nil
  (`impl/dispatch.clj:63-69`); `run` catches Throwable around on-caller runnables
  into it (`:122`). A channel's transducer exception goes to the channel's
  `ex-handler` or that default (`impl/channels.clj:284`); `thread-call` runs its body
  with only a `finally close!` (`async.clj:509-529`), so a throw escapes to the
  executor thread's handler, and `:io` work runs on virtual threads when available
  (`impl/dispatch.clj:75-91`). The ns docstring itself says to use
  `Thread.setDefaultUncaughtExceptionHandler()` for go-checking throws
  (`async.clj:10-17`). A `go` block's throw therefore reaches the default handler,
  and its channel closes with no value: a caller sees nil, i.e. silence, unless the
  body catches.
- **core.async.flow.** A proc step's throw is caught and sent to `::flow/error` as
  `{:pid :status :state :count :cid :msg :op :step :ex}`, and **the proc keeps
  running** with its old state (`flow/impl.clj:301-319`). Transducer throws on
  in-chans are `put!` to the same error chan (`:101-109`). The error chan is
  `(async/sliding-buffer 100)` (`:102`): past 100 unread errors the oldest are
  silently dropped. A `:panic` that "stops the failing graph" is not flow's default;
  Seon's error-chan reader must stop the graph itself, and must read promptly or lose
  errors. The flow sibling note covers this in depth.

## 4. How agent frameworks surface runtime errors to the model

- **MCP** separates protocol errors (JSON-RPC `error`, e.g. unknown tool, invalid
  arguments) from tool execution errors, returned as a normal result with
  `isError: true` and text content so the model sees them; tools may also return
  `structuredContent` validated against an `outputSchema`
  ([MCP tools spec 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)).
- **Claude API**: `tool_result` with `is_error: true`; "Write instructive error
  messages" naming what went wrong and what to try next; Claude retries invalid calls
  2-3 times
  ([handle tool calls](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)).
  Anthropic's tool-writing guide asks for "specific and actionable improvements"
  over opaque codes or tracebacks
  ([writing tools for agents](https://www.anthropic.com/engineering/writing-tools-for-agents)).
- **OpenAI Agents SDK**: a raised tool exception is passed to `failure_error_function`,
  whose string becomes the tool output the model sees
  ([tools](https://openai.github.io/openai-agents-python/tools/)).
- **LangGraph** `ToolNode(handle_tool_errors=…)`: `True`, a string, a callable or an
  exception type; the error returns as a `ToolMessage`. Default handling changed
  after 1.0.1 ([issue #6486](https://github.com/langchain-ai/langgraph/issues/6486),
  [reference](https://reference.langchain.com/python/langgraph.prebuilt/tool_node/ToolNode)).
  Lesson: a changed default silently hid errors from agents.
- **Sentry MCP** exposes `search_issues`, `get_issue_details`, `search_issue_events`,
  `get_trace_details`, and Seer analysis, used from coding agents such as Claude Code
  ([Sentry MCP](https://docs.sentry.io/ai/mcp/),
  [cookbook](https://sentry.io/cookbook/debug-with-sentry-mcp-claude-code/)).
  The shape is "grouped issue + latest event + counts + tags + trace", queried by the
  agent, not pushed.

Common pattern: the error the agent sees is a value in the same channel as success,
flagged, short, instructive, with an identifier to query for more. For Seon: the MCP
`eval_clj`/`runtime_status` result should carry the panic's error entity id,
fingerprint, count, first/last-at, operation, root-cause class and message, the
in-app frames, and the query or function to pull the full chain, rendered by the
error schema's AI render pair (which already exists as `seon.error/render-ai`), plus
a `runtime_status` member listing unresolved panics since the caller's basis. An
agent chasing an error then queries the fact, which is the Sentry MCP shape without
a second store.

## Recommendation

**Build on Seon's existing pieces; adopt no library.** `seon.error` already has the
refusal/diagnostic value with `:seon.error/chain`, the D13 `signature`, occurrence
count and last-at with `:db/noHistory`, a fault committer graph, an AI render pair
and the `:seon.config/on-core-error` dial (read at `src/seon/db.clj:2725-2730`).
Every library surveyed would either duplicate one of these or introduce a lossy
router. Borrow designs, each with its source:

1. **Fingerprint = versioned function of the value** (Sentry newstyle): D13's site
   tuple plus, per exception in the cause chain, class name + ordered in-app frames
   (`ns$fn` + method), line numbers excluded, `$fn__N`/`$eval…`/`$reify__N`/lambda
   and SCI-generated frames normalised, recursion collapsed, message excluded when a
   stack exists. In-app = namespaces of the cluster's program rows (a fact Seon
   already has), not a hand list. Version the algorithm by its definition digest so
   a change groups new occurrences only. Whether one frame or all in-app frames is
   right is a REPL measurement over the stored errors, not a guess.
2. **Record once per throwable** (Sentry Java dedupe): a weak identity set of
   recorded throwables and their causes; a catch that sees an already-recorded
   throwable (or one wrapping it) adds nothing. This is what makes "every catch calls
   the one function" safe.
3. **Bursts: first occurrence transacts, repeats coalesce** (Sentry `buffer_incr`):
   the first occurrence of a fingerprint transacts immediately (it is the wake
   event); later occurrences increment an in-memory count keyed by fingerprint and
   flush as one `occurrence-count`/`last-at` transaction per bounded window, through
   the existing fault committer. Unlike Sentry, Telemere, mulog and flow, **never
   drop**: the count is the record. A flush failure is a database failure.
4. **Dial and backstop** (Clojure `agent` `:error-mode`, Sentry Java handler): one
   installed default uncaught handler, chained to the previous one, calling the same
   function; the flow `::flow/error` reader calls it too and, under `:panic`, stops
   the graph (flow will not). Database down (the transaction or its bounded flush
   fails) panics regardless of the dial, since the record itself cannot exist.
   Catches that return a Future or a go channel must still reach the function; the
   census lists those sites.

Vendoring: nothing to vendor for adoption. If a lane wants to read Telemere's
handler/rate-limiter or Sentry's grouping source while implementing, clone it into
`reference-code/` as read-only reference, pinned to the revisions named above.

Limits of this survey: library APIs were checked from READMEs and, for Telemere,
Sentry and core.async, source; none was run. Maintenance status is from GitHub and
Clojars metadata on 2026-09-22/23. The virtual-thread and Future behaviour is from
JDK documentation, not probed in Seon's JVM.
