---
type: research
created: 2026-09-23
status: evidence (read-only survey)
source: Opus 5.5 read-only survey (Explore) of reference-code/ — clojure, datahike (Seon fork), konserve, babashka, langchain4clj, datastar-clojure, editscript
purpose: prior art for the one error route (fix schedule row 0; AGENTS.md "The error policy")
---

# Error-route prior art: clojure, datahike, konserve, babashka, langchain4clj, datastar-clojure, editscript

Paths are under `reference-code/` unless marked `src/`. Read directly; nothing was run.

> **Owner ruling overrides one suggestion below (2026-09-23):** "If the db is down I think it's fair to just panic … even in production." The overflow-journal fallback in §6 is REJECTED. Database down means panic, with no fallback store or replay file.

## 1. clojure: the model for one shaping function
- **Data layer:** `ex-info` with `(msg map cause)`, and `ex-data`/`ex-message`/`ex-cause` (`clojure/src/clj/clojure/core.clj:4924-4955`).
- **`Throwable->map`** (`clojure/src/clj/clojure/core_print.clj:473-506`) returns `{:via [{:type :message :data :at}] :trace :cause :data :phase}`: the full chain, each link's ex-data, and the root's stack. Its `.getCause` loop (`:494`) has NO cycle guard.
- **Context by wrapping, never by logging:**
  - The REPL wraps read and print failures with `:clojure.error/phase` (`clojure/src/clj/clojure/main.clj:435,444`).
  - The compiler adds `:clojure.error/{source,line,column,phase,symbol}` (`Compiler.java:7407-7445`).
  - The original is always kept as the cause.
- **Shaping, in three pure steps:**
  - `ex-triage` (`main.clj:207-266`) maps the datafied throwable to a flat `:clojure.error/*` map. For `:execution` it drops core frames to find the user frame (`:247-257`).
  - `ex-str` (`:268-340`) builds one headline per phase.
  - `err->msg` = `(-> e Throwable->map ex-triage ex-str)` (`:342-345`).
- **One policy hook:** `repl-caught` (`:347-352`) is the default `:caught` of `repl` (`:395,424`). Every read/eval/print failure passes one `catch Throwable` → `(caught e)` → `(set! *e e)` (`:445-447`). The reader gets a short triaged message; the full object stays in `*e`.
- **Backstop:** `report-error` (`:585-615`), used by `main` (`:651-675`), writes the full triage and trace to a temp `.edn` and prints a one-line summary with the path; stderr is the fallback.
- **Core leaks:**
  - The agent `:error-handler` silently drops its own errors (`Agent.java:100,136`).
  - `future-call` holds the error until deref (`core.clj:7202`).
- **Default uncaught handler:** never set in clojure, datahike or babashka; only core.async's docstring mentions it (`async.clj:16`). `dispatch/ex-handler` (`impl/dispatch.clj:63-69`) reaches it for `run` and xform errors, and plain go blocks reach it via `.execute`.
- **Three things that never reach it:** futures, `go-try` bodies (the error becomes the channel value), and flow step errors (sliding-100 `::flow/error`, `flow/impl.clj:102,108,312-319`).

## 2. datahike (the Seon fork)
- **`raise` is `replikativ.logging/raise`,** a macro that logs, then throws `ex-info` WITHOUT a cause (`replikativ/logging.cljc:82-96`, in `~/.m2`).
  - It double-reports (logs at the throw site and again when caught).
  - Line and column go to the log only.
  - About 170 call sites.
  - The log backend is global: `trove/set-log-fn!` (`datahike/src/datahike/cli.clj:29,138-141`).
- **The error key is ad hoc:** `{:error :query/where}`, `{:error :transaction/stale-basis}` etc. (`query.cljc:418,476`; `writing.cljc:886`; `db/transaction.cljc:455`), but `:type` elsewhere (`writer.cljc:23`).
- **Writer seam** (`writer.cljc:143-170`), one `catch Throwable` around the op:
  1. It delivers to the caller first (`:146-157`); an NPE is re-wrapped with the original under `:error e` in the data, NOT as the cause.
  2. It logs `:datahike/write-error` via `write-error-log` (`:85-104`), which copies identity keys (`:seon.cluster/name`, `:seon.test/sym`, `:seon.test.run/id`, `:seon.error/id`), op, branch and commit, and strips all `:data`.
  3. A JVM `Error` closes the queues and rethrows (`:165-168`).
  4. Otherwise it continues.
- **Failures surface:**
  - Queued work is failed, never left hanging (`:26-40,213,275`).
  - A commit failure is terminal (`:262-281`).
  - Listener errors are only logged (`:385-387`).
- **The caller's top ex-data can be `{}`.** `throwable-promise` (`tools.cljc:93-124`) → `CompletableFuture.get` → `ExecutionException` → superv `throw-if-exception-` wraps it with `(or (ex-data x) {})` (superv `async.cljc:87-98`). So fingerprinting must walk `:via`.
- **Fatal loop errors** land only in superv's `S` supervisor, which after the 10 s stale timeout `println`s (`async.cljc:48-77,109`). That is the only backstop, and it bypasses log and handler.
- **Cheap repeated writes:**
  - `:db/noHistory` skips only the temporal index (`db/transaction.cljc:447,546-573`); eavt/aevt (and avet if indexed) are still rewritten.
  - A `:db.unique/identity` upsert resolves to one entity (`:1310`).
  - Every commit writes index nodes plus a commit blob (`writing.cljc:49-110`), so ONE TRANSACTION PER OCCURRENCE THRASHES storage.
  - The commit loop batches queued txs (`writer.cljc:227`); `commit-wait-time` (`:83,282`) defaults to 0.
  - Seon already does a noHistory gauge upsert by identity (`src/seon/ai.clj:1028-1045`).

## 3. konserve
- `async+sync` (`konserve/src/konserve/utils.cljc:71-88`, `:90-97`) serves both modes: errors are channel values when async and throws when sync. There is no handler seam.
- The cause is put in data instead of the cause slot: `{:error e}` (`impl/defaults.cljc:136-140,252-256`), `{:exception e}` (`:211-212,217,220`), `{:cause e}` (`core.cljc:406-413`). All of these hide it from `:via`.
- It catches `Exception`, not Throwable (`filestore.clj:112-119`; `:84,88` swallow). Write hooks log a warning and swallow (`utils.cljc:52-68`). `multi-assoc` (`core.cljc:435`) is the batching primitive.

## 4. babashka
- **One `error-handler`** (`babashka/src/babashka/impl/error_handler.clj:99-154`) for every top-level entry (`main.clj:1031,1045,1058,1114`).
  - It prints labelled sections (Type, Message, Data, Location, Phase), a source snippet with `^---` (`:30-62`), a short stack (`:12-28`), and the full trace with `--debug`.
  - `:babashka/exit` in ex-data means "message plus that exit code" (`:102-118`), a data-driven policy switch.
- **Context comes from SCI's** `rethrow-with-location-of-node` (`sci/src/sci/impl/utils.cljc:121-181`), which wraps once and keeps the cause.
- **It is not fully uniform:** the REPL has its own `repl-caught` (`babashka/src/babashka/impl/repl.clj:29-45`), so there are two shapers.

## 5. langchain4clj, datastar-clojure, editscript
- **langchain4clj** (`langchain4clj/src/langchain4clj`):
  - It classifies by message text (`resilience.clj:15-66`).
  - "All providers failed" has no cause and the last error is dropped (`:72,143,167,381,408`).
  - Listeners flatten to strings (`listeners.clj:173-183`) and swallow handler errors (`:211-232`).
- **datastar-clojure** has the best callback seam of the three:
  - `:d*.sse/on-exception (fn [sse-gen e ctx])` (`adapter/common.clj:380-403`), with ctx carrying event type, data lines and opts (`http_kit/impl.cljc:76-88`; `ring/impl.clj:60`).
  - The default treats `IOException` as a quiet close and rethrows everything else as `(ex-info msg ctx e)` (`:406-413`).
  - `close-sse!` collects errors from both closers and rethrows the first JVM `Error` (`:296-310`).
- **editscript** throws `ex-info` with its inputs; no routing.

## 6. Assessment for Seon
- **Shaping:** `ex-triage` + `err->msg` is the model for the one shaping function. A pure datafied error goes to a flat namespaced classification, with printing as a separate step. Use a bounded, cycle-guarded `Throwable->map`, and store the result in the database. The one hook is `:caught`: every catch calls one function; `*e` keeps the full object.
- **Fingerprint (A):**
  - Hash `[phase class first-non-core-symbol source line]` plus a stable error key from the chain.
  - EXCLUDE the message and ex-data values.
  - Store as `:db.unique/identity`, with count and last-seen as `:db/noHistory`.
  - Coalesce increments in memory and flush one transaction per N ms.
  - Record the first occurrence in full, with its chain; later occurrences bump the count.
- **Cause, never the data map:** require the 3-arity `ex-info` at the seam. Avoid `{:error e}` (datahike writer, konserve), cause-less `raise` (replikativ), and dropped last errors (langchain4clj). Walk the chain for identity keys as `write-error-log` does.
- **Order of operations (copy the writer, `writer.cljc:143-168`):**
  1. deliver to the waiting caller first;
  2. record, with recording guarded so its own failure cannot block step 1;
  3. apply the dial: `Error` is fatal; `:panic` rethrows after recording; `:record` returns the refusal value.
- **Database down:** ~~overflow journal plus replay~~ REJECTED by the owner's ruling (see top). The route panics with the ex-str headline and the full chain to the caller, the REPL (`*e`) and stderr.
- **Backstop:**
  - Install `Thread/setDefaultUncaughtExceptionHandler` once at boot, routed to the seam; it covers threads and plain go blocks.
  - Drain flow `::flow/error` into the seam.
  - Replace datahike's superv `S` with a `simple-supervisor` whose `:error-fn` is the seam.
  - Futures and `go-try` channels still need their consumers to route.
- **Visible to agents (B):**
  - Show phase, `file:line:col`, class, message, the chain as `{type message error-key}` and the source snippet.
  - Expose the latest raw error as a `*e`-like value in MCP runtime status, and each fingerprint's first chain via the REPL.
  - Under `:panic`, print the headline plus the entity id; never just "error logged".
- **The dial:** one config fact read by the seam, like `:caught`. A data key can pick the policy for one error (datastar `on-exception`, `:babashka/exit`).
- **Doesn't fit:** a log-and-throw `raise` (double report), classification by message text, and handlers that silently drop their own errors (`Agent.java:100`, `listeners.clj:231`, konserve hooks).
- **Check `src/seon/error.clj` first.** It is 2,334 lines and already has `signature` (`:151`), `root-cause` (`:105`), bounded admission (`:247-316`) and `prepare` (`:515`); look for overlap before adding a seam.
