---
type: research
created: 2026-09-23
status: evidence (read-only survey)
source: Opus 5.5 read-only survey (Explore) of reference-code/ — core.async, core.async.flow, flow-monitor, http-kit, hyperlith, babashka-process
purpose: prior art for the one error route (fix schedule row 0; AGENTS.md "The error policy")
---

# Error-route prior art: core.async/flow, flow-monitor, http-kit, hyperlith, babashka-process

Paths are relative to `reference-code/` unless they start with `src/`. core.async paths are under `core.async/src/main/clojure/clojure/core/async/`.

## 1. core.async and core.async.flow

- **The seam.** A proc's run loop has two `catch Throwable` blocks: around the step at `flow/impl.clj:312-316`, and around control and pause handling at `flow/impl.clj:317-320`. Both do `(async/>!! (outs ::flow/error) ...)`. Every proc gets the same `error-chan` in its `:outs` (`flow/impl.clj:161`), created once per graph at `flow/impl.clj:102`.
- **Other entries into the same channel:**
  - xform failures: the `make-chan` ex-handler (`flow/impl.clj:105-110`) sends `#::flow{:ex :pid :cid :xform}`.
  - Compute timeouts: `.get` at `flow/impl.clj:259-260` throws `TimeoutException` into the step catch; the timed-out future keeps running.
  - Start failures bypass the channel: `start-proc` catches, stops all procs and rethrows from `flow/start` (`flow/impl.clj:163-165`).
- **Call site:** nothing. Step fns just throw. The contract (`flow/spi.clj:56-58`): a proc "must report it on the ::flow/error channel ... and attempt to continue". `flow.clj:116-120` promises that every exception on any flow thread appears there.
- **Policy:** none in the library. The proc keeps its pre-step state and continues (`flow/impl.clj:316`). The reader of `:error-chan` decides.
- **Context:**
  - Step errors carry `::flow/pid :status :state :count :cid :msg :op :step :ex` (`flow/impl.clj:314-315`). `:state` is the whole pre-step state, which can be large or unserializable.
  - Outer-loop errors carry no `:cid`, `:msg` or `:op` (`:319`).
  - `:ex` is the live Throwable, so the chain is intact.
- **Drops: yes.**
  - `error-chan` and `report-chan` use `sliding-buffer 100` (`flow/impl.clj:101-102`). Unread errors are evicted silently; `>!!` never blocks.
  - It is a single-consumer channel, so two readers split the errors between them.
- **Base core.async:** the last resort is `dispatch/ex-handler` → the thread's uncaught handler (`impl/dispatch.clj:63-69`; used by `impl/channels.clj:283-287`, `impl/dispatch.clj:122`, `async.clj:576`). Per-channel override: `(chan buf xform ex-handler)` (`async.clj:121-131`). JVM-wide: `Thread/setDefaultUncaughtExceptionHandler` (`async.clj:16`).

## 2. core.async.flow-monitor

- **The seam:** `report-monitoring` (`core.async.flow-monitor/src/clojure/core/async/flow_monitor.clj:122-130`), an `alts!!` over the report and error channels, obtained through datafy metadata (`:153-154`).
- **Policy:** `pprint` to a string (`:128`), pushed to websockets (`:67-69`), turned into an alert in the UI (`flow_monitor_ui/events.cljs:130`).
- **Loss:** structure and the Throwable are lost. It competes with other readers, and with no browser connected an error is dropped. Nothing is persisted.
- **For Seon:** a viewer, not a seam. Seon already gives it its own sliding tap off a mult (`src/seon/flow.clj:1215-1253`).

## 3. http-kit

- **The seam:** `HttpHandler.handleError` (`http-kit/src/java/org/httpkit/server/RingHandler.java:196-200`). It calls `errorLogger.log(method + " " + uri, e)`, records the 500 event, and sends a 500 whose body is `e.getMessage()` (the message leaks to the client).
- **Reached from:**
  - sync handlers (`:144-149`) and async/raise handlers (`:152-173`);
  - websocket frames (`:255-257`), executor rejections (`:312,352,396`) and on-close (`:372,392`);
  - the accept path (`HttpServer.java:185`) and the selector loop (`:464-467`, which also sets STOPPED).
- **Swallowed:** the stop callback, `catch (Throwable t) { }` (`HttpServer.java:540`).
- **Global override, per server:** `:error-logger (fn [msg ex])`, plus warn and event loggers (`http-kit/src/org/httpkit/server.clj:105-107`), reified at `:150-163`. The default prints the stack trace to stderr (`ContextLogger.java`; `HttpUtils.java:348-356`).
- **Context:** a string only; no request map or id.
- **Client:** errors are data, `{:opts :error}` (`client.clj:316,321,345,349`), dropped unless the caller checks.

## 4. hyperlith (`hyperlith/src/hyperlith/`)

- **The seam** is all of `impl/error.clj:4-20`: `(defonce on-error_ (atom nil))`, a `try-on-error` macro whose catch calls `(@on-error_ t)` and returns nil, `wrap-error` turning nil into 500, and a default that pprints.
- **Call site:** `(er/try-on-error (render-fn req))`, as middleware (`core.clj:141`) and in the SSE render loop (`impl/datastar.clj:164`).
- **Global change:** `start-app :on-error` resets the atom (`core.clj:121-128`). This is exactly the "one required fn, policy swapped once" shape.
- **Weaknesses:**
  - The handler gets only `t`, no request context.
  - Leaks: `extras/batch.clj:36-39` printlns; `util/thread` (`impl/util.clj:27-32`) has no catch.

## 5. babashka-process (`babashka-process/src/babashka/process.cljc`)

- **The seam:** `check` (`:95-114`). It derefs the process and throws `ex-info` on a non-zero exit (message = stderr), with ex-data = the whole process record plus `:type ::error`.
- **Default is failure as data:** `process`/`sh` never check (`:611-621`); `shell` checks unless `:continue` (`:680-708`).
- **Dropped:**
  - A failing `:in` copy only prints (`:419-421`).
  - An `:exit-fn` exception dies inside a `CompletableFuture` stage (`:446-450`).
  - An unchecked non-zero exit is invisible.

## 6. What fits Seon

Seon already has most of the parts:
- chain capture: `src/seon/error/refusal.clj:74-113`;
- a Datahike dial: `src/seon/db.clj:2725-2730`;
- throw after commit: `src/seon/db.clj:3947-3951`;
- one committer graph with mult taps and counted-drop buffers: `src/seon/flow.clj:999,1192-1263`;
- N graphs into one fault channel: `src/seon/flow.clj:1266-1295`.

There are 238 `(catch ` sites under `src/seon`.

1. **hyperlith's shape, with required context:** `(seon.error/record! observation throwable)` plus a `try-record` macro. The observation must carry the layer, operation and responsible agent. It builds via `diagnostic`/`chain` and hands off to the ONE fault channel. The mode stays the Datahike fact `:seon.config/on-core-error`.
2. **Inside procs, do not catch:** flow's step catch (`flow/impl.clj:312`) is the call site and already attaches pid, cid and msg. The committer maps pid → agent. Strip `::flow/state`; keep a `ping-map-fn`-style projection instead.
3. **Everything else into the same channel:**
   - http-kit `:error-logger` → `record!`, with a request id; stop returning `e.getMessage()` in 500 bodies.
   - A wrapped `babashka.process/check` → `record!`; forbid unchecked `process`/`sh`.
   - `Thread/setDefaultUncaughtExceptionHandler` → `record!`, for go blocks, virtual threads and xform escapes.
4. **`:panic` cannot be a rethrow inside a proc,** because flow's catch swallows it.
   - In procs: the committer commits first, then `flow/stop`s the source graph and fails loudly.
   - Outside procs: `record!` rethrows after committing, as `db.clj:3947` does.
   - `:record` means commit and continue, which is flow's own default.
5. **Wake from the committer after commit** (`flow/inject` or a db listener), so the stored error is the source of truth. The committer's own failures must not wake an agent; they stay on `report-committer-loss!` (`seon/flow.clj:1164-1180`).
6. **Drops:**
   - Flow's `sliding-buffer 100` evicts before Seon's mult sees the error.
   - Keep the counted-drop fault (`seon/flow.clj:1206-1208`) as the only allowed loss.
   - Never read a source `:error-chan` from anywhere else; flow-monitor shows how a second reader steals errors.
7. **Rule out:** stringified errors (flow-monitor), string-only context (http-kit), unchecked failure-as-data (bb-process), and `println` side paths (hyperlith `extras/batch.clj:38`).
8. **Enforcement:** a clj-kondo hook flagging any `catch` whose body neither calls `record!` nor rethrows, plus a test counting non-compliant catch sites.
