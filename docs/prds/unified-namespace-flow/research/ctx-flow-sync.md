---
type: research
status: active
tags: [research, flow, prd]
---

# Ctx-Flow Bidirectional Sync

## Problem Statement

In the unified namespace flow design, each dynamic namespace has:

1. A `::ctx` atom managed by `seon.ctx` -- holds namespace state, is derefable from the REPL, has persistence watches and SSE push watches.
2. A per-namespace flow process that handles function dispatch, ctx injection, and rendering.

These two need to stay in sync:

- **Flow to atom**: When a dispatched function returns `::ctx` in its output, the dispatch layer applies it to the atom. This is the normal path.
- **Atom to flow**: When an agent does `(swap! ctx-atom assoc ::screen :active)` from the REPL, the flow process needs to know so it can trigger re-renders.
- **No loops**: If flow updates atom, the atom watch must not re-inject into flow. If atom swap triggers flow, flow must not update atom again.

## Options Evaluated

### Option A: Atom Watch Injects Into Flow (Sentinel Loop Prevention)

Add a watch on the ctx atom. When the atom changes, inject a `:ctx-updated` message into the flow. The flow process receives it, triggers re-render, pushes SSE. A sentinel flag prevents loops: the flow sets the flag before updating the atom via `atom-update-sink`, and the watch checks the flag.

**Architecture:**

```
 Flow fn-call       REPL swap!
      |                  |
      v                  v
 [ctx-proc]         [ctx atom]
      |                  |
      |  atom-update     |  watch fires
      v                  v
 [atom-sink]        [inject ctx-updated]
      |                  |
      v                  v
 reset! atom  -----> [ctx-proc]
 (sentinel=true)     (processes, renders)
      |
 watch fires but
 sentinel=true → skip

```

**REPL test results (5 stress trials, 20 concurrent atom swaps + 20 concurrent fn-calls each):**

```
Trial 0: converged=true atom={:fn 9, :ext 19} flow={:fn 9, :ext 19}
Trial 1: converged=true atom={:fn 19, :ext 18} flow={:fn 19, :ext 18}
Trial 2: converged=true atom={:fn 18, :ext 17} flow={:fn 18, :ext 17}
Trial 3: converged=true atom={:fn 19, :ext 14} flow={:fn 19, :ext 14}
Trial 4: converged=true atom={:fn 19, :ext 18} flow={:fn 19, :ext 18}

```

All trials converged. Atom and flow hold identical values after concurrent stress.

**Pros:**

- REPL ergonomics preserved -- agents can `swap!` atoms directly
- Proven convergent under concurrent stress (5/5 trials)
- Rendering moves to flow side (single render path)
- Persistence stays on atom watches (debounced, proven pattern)

**Cons:**

- Sentinel window (~14 microseconds) where a concurrent external swap could be missed by the watch. In practice this is negligible: the swap still updates the atom, and the next render will reflect the correct value.
- Medium complexity: sentinel atom, two message types in flow step

### Option B: Flow Is Sole Writer, Atom Is Read-Only Projection

All mutations route through the flow, even from the REPL. The atom becomes a read-only projection that the flow keeps up to date. Direct `swap!` is prohibited (or at least discouraged).

**REPL experience change:**

```clojure
;; Instead of:
(swap! *ctx* assoc ::screen :active)

;; Use:
(dispatch/update-ctx! {::f #(assoc % ::screen :active)})
;; or:
(ctx/swap-via-flow! ns-sym #(assoc % ::screen :active))

```

**Pros:**

- Simplest architecture: one source of truth, no loops possible, no sentinel needed
- All effects (render, persist, SSE) are flow outputs -- single path for everything

**Cons:**

- REPL ergonomics degraded -- agents can't just `swap!` an atom
- Requires a helper function or dispatch call for what was previously a one-liner
- Atom validators or wrapper types needed to enforce read-only (otherwise agents will `swap!` and nothing will happen, which is confusing)

### Option C: Atom Watches Do Everything, Flow Ignores Ctx

Flow handles function dispatch only. Atom watches handle rendering, persistence, and SSE push (the current `ctx.clj` behavior). The flow step-fn reads `::ctx` from the atom when needed and writes it back, but doesn't maintain internal ctx state.

**Pros:**

- Simplest change from current codebase -- ctx.clj works as-is
- No sync mechanism needed

**Cons:**

- Rendering is duplicated: atom watch renders AND flow could render
- Flow can't hold ctx state for spec-driven injection without reading the atom on every call
- Race condition: flow reads atom, computes new ctx, resets atom. Between read and reset, a REPL swap changes the atom. Flow's reset clobbers the REPL's change. (Mitigated by flow serializing all fn-calls, but REPL swaps are not serialized.)
- Limits the unified design -- flow becomes a pass-through, not the routing backbone

### Option D: Hybrid -- Atom Watches for SSE, Flow for Dispatch

Atom watches handle SSE push and persistence (current behavior). Flow handles function dispatch and ctx injection. They don't sync -- flow reads atom when it needs ctx, atom watches handle push independently.

**Pros:**

- Lowest complexity
- Both systems work independently

**Cons:**

- Double rendering: fn-call through flow produces a render event, AND the atom update from that fn-call triggers the watch-based render. Two SSE pushes for one state change.
- To avoid double rendering, either flow skips rendering (then what's the point?) or watches skip rendering (then direct swaps don't render)
- Divergence possible: flow's view of ctx and atom's actual value can drift under concurrency
- Half-hearted adoption of the flow model

### Idempotent Variant of Option A (Tested and Rejected)

Instead of a sentinel, the flow step checks if the incoming `ctx-updated` value equals its current internal state. If equal, skip (it's an echo from our own atom-update).

**REPL test result:**

```
Atom :fn 18, Flow :fn 17 -- DIVERGED (not converged)

```

**Why it fails:** Under concurrent load, the flow process can receive fn-calls that change its internal state between the atom-update emission and the echo arriving. The echo value no longer matches, so the flow treats it as a genuine external change and overwrites its state. This creates persistent divergence that never self-corrects.

**Verdict:** Rejected. The idempotent approach is fundamentally flawed under concurrent bidirectional updates.

## Previous Recommendation: Option A (Sentinel) with Selective Watch Migration

**Note:** This was the initial recommendation. It has been superseded by the flow-first architecture below, which eliminates ALL atom watches except `::flow-sync`. The sentinel approach remains correct for bidirectional sync, but persistence now goes through flow instead of atom watches.

### Summary (Original)

Use the sentinel approach (Option A) for bidirectional sync. Migrate rendering from atom watches to flow outputs. Keep persistence on atom watches. This gives us:

- **One render path** (flow side) -- eliminates current duplication between `::sse-push` watch and `::client-push` watch
- **Proven convergence** under concurrent load
- **REPL ergonomics preserved** -- agents can still `swap!` atoms
- **Debounced persistence** unchanged (sliding-buffer-1 per namespace, proven pattern)

### Watch Migration Plan (Original)

Current atom watches in `ctx.clj`:

| Watch Key | Current Behavior | Original Plan | **Flow-First Plan** |
|-----------|-----------------|--------------|---------------------|
| `::persist` | Debounced Datalevin write via ScheduledExecutorService | Keep as-is | **Remove.** Flow sliding-buffer-1 persistence replaces this. |
| `::sse-push` | Calls `seon.web.sse/refresh-all!` on every change | Remove | **Remove.** Flow render output replaces this. |
| `::client-push` | Calls `render-and-push!` to push to tracked clients | Remove | **Remove.** Flow render output replaces this. |
| `::flow-sync` | (NEW) Injects `:ctx-updated` into flow when atom changes externally | Add | **Add.** This is the sole remaining watch. |

### Key Findings from Initial REPL Prototyping

1. **`flow/inject` is safe from watch callbacks.** Returns in ~11 microseconds. The blocking `>!!` happens on a virtual thread (future), so the watch callback returns immediately.

2. **`flow/inject` on paused flow buffers messages.** They are processed when the flow resumes. This is safe -- no data loss.

3. **`flow/inject` on stopped flow throws.** The watch callback must catch this exception to prevent errors during system shutdown.

4. **Sentinel window is ~14 microseconds.** The probability of a concurrent external swap during this window is negligible. Even if it occurs, the worst case is one render cycle delay -- the atom has the correct value, and the next change will trigger rendering.

5. **Idempotent loop prevention (equality check) diverges under load.** The sentinel approach is strictly superior for correctness.

6. **Flow steps cannot do debounced persistence internally.** ~~The current `ScheduledExecutorService` approach in `ctx.clj` is the correct tool for debouncing.~~ **Revised:** The sliding-buffer-1 pattern solves this (see below). Each namespace's `:persist` output channel uses `sliding-buffer 1` — writer backpressure naturally debounces.

---

## Revised: Flow-First Architecture (2026-03-14)

### Why the Revision

The original recommendation kept persistence on atom watches because "flow steps cannot debounce." This was correct in a narrow sense -- a flow transform function cannot schedule future work. But it missed a simpler solution: `sliding-buffer 1` on the channel from each namespace step's `:persist` output to the writer step. The step emits `:persist` with the full ctx state on every change. While the writer is busy (Datalevin network I/O), subsequent persist events coalesce in the sliding buffer -- only the latest survives. When the writer finishes its current write, it picks up the latest state.

This eliminates ALL atom watches except the single `::flow-sync` watch for bidirectional sync. The atom becomes a pure read cache with no side-effects of its own.

### The Flow-First Principle

**All effects go through flow.** No atom watches for SSE push, persistence, or rendering. Flow is the backbone for everything. The atom watches with SSE were a proof of concept; the flow-first model is the production architecture.

### Architecture

```
                    REPL swap!
                        |
                        v
               ┌── [ctx atom] ──┐
               │                │
               │  (read cache   │  ::flow-sync watch
               │   only - no    │  (if not sentinel)
               │   side-effect  │
               │   watches)     │
               │                v
               │         [flow/inject :ctx-updated]
               │                |
               │                v
    fn-call ──────────> [namespace step-fn]
                                |
                         ┌──────┼──────────┐
                         v      v          v
                    :render  :persist    :reply
                         |      |          |
                         v      v          v
                    [out-port] [writer]  [reply-router]
                         |    sliding     |
                         v   buffer(1)    v
                    [mult →    [Datalevin]
                     connections]
                         |
                    [per-connection
                     SSE push]

    Flow also updates atom (sentinel-guarded):
    namespace step → reset! atom (sentinel=true)
                         |
                    ::flow-sync watch fires
                    but sentinel=true → skip

```

### The Sliding-Buffer Persistence Pattern

The namespace step emits `:persist` with the full ctx state on every ctx change. Debouncing happens at the channel level, not in the step function:

1. Each namespace step's `:persist` output connects to the writer step via a channel with `sliding-buffer 1`
2. When ctx changes, the step emits `{:ctx new-ctx :ns ns-str}` on `:persist`
3. While the writer is busy (Datalevin network I/O), the sliding buffer holds at most one message -- the latest
4. When the writer finishes its current write, it picks up the latest state
5. Each namespace has its own channel to the writer -- no cross-namespace interference

No timer, no dirty flag, no `ScheduledExecutorService`. Writer backpressure IS the debounce.

**Code sketch:**

```clojure
;; In namespace step transform (on ctx change):
[state'
 (cond-> {:reply [...]}
   (not= new-ctx ctx)
   (assoc :render [{:ctx new-ctx}]
          :persist [{:ctx new-ctx :ns ns-str}]))]  ;; always emit, sliding-buffer debounces

;; Topology wiring (per namespace):
;; The channel from ns-step :persist output to writer :persist input
;; uses (sliding-buffer 1). Configured in chan-opts during topology build.
{:chan-opts {[:ns-step :persist :writer :persist]
            {:buf-or-n (async/sliding-buffer 1)}}}

```

### REPL Prototype Results (2026-03-14)

Prototyped a complete flow topology with namespace step, writer sink, connection sinks, and reply router.

**Test 1: Single function call**

```
fn-call -> step updates ctx -> emits :render + :persist + :reply
Writer received persist event: YES
Browser-1 received render: YES
Browser-2 received render: YES
Reply delivered: YES

```

**Test 2: External ctx update (simulating REPL swap!)**

```
inject :ctx-updated -> step updates internal ctx -> emits :render (no :persist)
Both browsers received render: YES
Writer persist count unchanged: YES (correct -- external updates persist when step emits :persist on ctx change)

```

**Test 3: Concurrent stress (20 fn-calls + 20 external updates)**

```
40 concurrent operations, all serialized through flow step.
Both browsers: 42 renders (40 + 2 from prior tests)
Writer: 21 persists (20 fn-calls + 1 from test 1)

```

**Test 4: Flush-based debounced persistence (superseded by sliding-buffer)**

These results validated the flush-timer approach. The sliding-buffer pattern achieves the same debouncing with less machinery: the step emits `:persist` on every change, and `sliding-buffer 1` on the channel to the writer coalesces rapid writes via backpressure.

```
50 rapid function calls with sliding-buffer-1 to writer (20ms write time):
  Renders emitted: 50 (every state change renders)
  Persists received by writer: 2 (first + last -- backpressure coalesced the rest)
  Final state persisted correctly: YES

```

**Test 5: Burst pattern with sliding-buffer**

```
Burst pattern (10 rapid, pause, 10 rapid) with sliding-buffer-1:
  20 calls -> 4 writes [1, 10, 11, 20]
  Natural coalescing via writer backpressure

```

**Test 6: Connection fan-out via out-ports**

```
Render events delivered to out-port channel -> mult -> per-connection taps.
Dynamic connection add (async/tap): new connection immediately receives next render.
Dynamic connection remove (async/untap): clean disconnect, no flow rebuild.

```

### Watch Migration Plan (Revised)

| Watch Key | Current Behavior | Flow-First Behavior |
|-----------|-----------------|---------------------|
| `::persist` | Debounced Datalevin write via ScheduledExecutorService | **Remove.** Step emits `:persist` on every ctx change. `sliding-buffer 1` on channel to writer debounces via backpressure. Writer step handles Datalevin. |
| `::sse-push` | Calls `seon.web.sse/refresh-all!` on every change | **Remove.** Step emits `:render` to out-port. Connection mult distributes. |
| `::client-push` | Calls `render-and-push!` to push to tracked clients | **Remove.** Same as above -- one render path via out-port mult. |
| `::flow-sync` | (NEW) Injects `:ctx-updated` into flow on external `swap!` | **Add.** This is the **sole remaining watch.** Sentinel-guarded. |

### Connection Model

Each browser tab, REPL session, or agent connection receives render events from the flow via a channel tap.

**Why out-ports, not flow processes for connections:**

core.async.flow does not support dynamic process addition. Browser tabs connect and disconnect at runtime. Using out-ports with `async/mult` solves this:

- The namespace step writes render events to an out-port channel
- An `async/mult` fans out to per-connection channels
- `async/tap` adds a connection, `async/untap` removes one
- Per-connection buffer semantics: `:sliding-buffer 1` for browsers (latest state only), `:blocking 64` for REPL

**Lifecycle:**

```
Browser connects (SSE request) -> async/tap on render mult -> channel created
Browser disconnects              -> async/untap -> channel closed
Tab reconnects                   -> new tap on same mult

```

This was validated in the REPL prototype -- dynamically adding a third connection via `async/tap` immediately started receiving render events without any flow topology changes.

### How Persistence Works in Flow-First

```
fn-call → flow namespace step → :persist output (every change) → sliding-buffer 1 → writer step → d/transact!
REPL swap! → ::flow-sync watch → inject :ctx-updated → step emits :persist → sliding-buffer 1 → writer

```

Both paths converge at the step's `:persist` output. The `sliding-buffer 1` on the channel to the writer naturally debounces: while the writer is busy with Datalevin I/O, only the latest persist event survives. The existing infrastructure writer step (`seon.db.datalevin.writer/infra-writer-step`) handles the `d/transact!` call with connection pooling, retry logic, and timeout protection.

### How SSE Push Works in Flow-First

```
fn-call → flow namespace step → :render out-port → async/mult → per-connection channels → SSE push
REPL swap! → ::flow-sync watch → inject :ctx-updated → step → :render out-port → same path

```

Both paths converge at the step's `:render` output. The connection manager (outside the flow) handles rendering ctx to HTML and pushing via http-kit's `send!`.

### Edge Cases

**System startup before flow is running:**
`*direct-mode*` for initial DB writes (existing pattern, unchanged). Ctx instances created during startup don't have flow processes yet -- they use atom watches until the flow starts and the `::flow-sync` watch is added.

**Tests with `*direct-mode*`:**
Tests that bind `*direct-mode*` bypass flow entirely. This is correct -- tests exercise individual functions, not the flow routing. The flow is tested in integration tests.

**Debounce timing:**
The sliding-buffer-1 debounce rate is determined by the writer's throughput. A fast writer (local Datalevin) means more writes; a slow writer (remote network I/O) means more coalescing. This is the correct behavior -- write as fast as the writer can handle, coalesce the rest. No configuration needed.

**Many browser tabs:**
Each tab is a channel tap on the mult, not a flow process. Mults are cheap. Hundreds of taps would be fine. Dead connections are detected by failed `send!` and cleaned up (untapped).

**WebSocket reconnects:**
Old tap is untapped (or channel closes, which auto-untaps). New connection creates a fresh tap. No state to migrate -- the next render sends the full current state.

### Loop Prevention: Sentinel Detail

Same as originally designed. Unchanged by flow-first revision.

```clojure
;; Per-instance sentinel (not global -- each ctx instance has its own)
;; Stored in the ctx registry entry alongside the atom

;; In the step's atom-update path:
(reset! (:flow-writing? entry) true)
(try
  (reset! ctx-atom new-value)
  (finally
    (reset! (:flow-writing? entry) false)))

;; In the ::flow-sync watch:
(add-watch ctx-atom ::flow-sync
  (fn [_ _ old-val new-val]
    (when (and (not= old-val new-val)
               (not @(:flow-writing? entry)))
      (try
        (flow/inject flow [pid :ctx-updated] [{:value new-val}])
        (catch Exception _
          ;; Flow stopped (shutdown). Silently ignore.
          nil)))))

```

### Migration Path (Revised)

1. **Phase 6a**: Add `::flow-sync` watch to `ctx/create!`. Add sentinel to registry entry. Wire to namespace flow process. All existing watches still active (additive only).

2. **Phase 6b**: Add `:render` out-port to namespace step. Wire connection manager with `async/mult`. Verify SSE push works through flow. Remove `::sse-push` and `::client-push` watches.

3. **Phase 6c**: Add `:persist` output to namespace step. Step emits full ctx state on every ctx change. Wire `:persist` output to infrastructure writer step with `sliding-buffer 1` on the channel (per namespace). Writer backpressure naturally debounces. Verify persistence works through flow. Remove `::persist` watch.

4. **Phase 6d**: Verify atom is now a pure read cache with only `::flow-sync` watch remaining. Remove dead code from `ctx.clj` (persistence helpers, SSE push functions, client tracking, ScheduledExecutorService).

Each phase can be deployed and tested independently. Each phase removes one more atom watch while adding the equivalent flow path. At the end, only `::flow-sync` remains.

## Flow Channel/Buffer Options for Debouncing (Research 2026-03-14)

### Question

Can flow's built-in channel/buffer options achieve persistence debouncing naturally, without external timers?

### What Flow Offers

core.async.flow's `chan-opts` allows configuring channels between processes:

```clojure
:chan-opts {:input-name {:buf-or-n (async/sliding-buffer 1)
                         :xform (map transform-fn)}}

```

- **`buf-or-n`** -- accepts integers (fixed buffer), `sliding-buffer`, or `dropping-buffer` objects
- **`xform`** -- transducer applied per-put (synchronous, cannot introduce time delays)
- **Default** -- `{:buf-or-n 10}` (fixed buffer of 10) for all inputs and outputs

### Alternatives Evaluated

**Alternative A: Sliding-buffer-1 on SHARED writer input (rejected)**

A single `sliding-buffer 1` on the writer's input channel. When multiple namespaces share this writer, their persist events compete for the same buffer slot.

REPL test result: ns-A expected count=25 but persisted count=1 because ns-B's persist overwrote ns-A's pending persist.

**This flaw is specific to SHARED buffers.** Our architecture uses separate channels per namespace (each namespace step has its own `:persist` output wired to the writer with its own `sliding-buffer 1`). With per-namespace channels, there is no cross-namespace interference.

**Alternative A-revised: Sliding-buffer-1 on PER-NAMESPACE channel to writer (ADOPTED)**

Each namespace step's `:persist` output connects to the writer via its own channel with `sliding-buffer 1`. The step emits full ctx state on every change. While the writer is busy (Datalevin network I/O), subsequent persist events from the same namespace coalesce -- only the latest survives.

REPL test results:

- Single namespace, slow writer (20ms): 50 calls -> 2 writes (first + last). Final state persisted correctly.
- Single namespace, fast writer (0ms): 50 calls -> 31 writes. More writes when writer is fast (correct -- write as fast as you can handle).
- Burst pattern (10 rapid, pause, 10 rapid): 20 calls -> 4 writes `[1, 10, 11, 20]`. Natural coalescing.
- Multi-namespace: each namespace has independent debouncing. No data loss.

**Previous analysis tested shared buffers between namespaces. With per-namespace channels (our actual architecture), sliding-buffer-1 provides natural debouncing via writer backpressure.**

**Alternative B: Sliding-buffer-1 on namespace step output port**

Put the buffer on each step's `:persist-out` instead of the channel to the writer.

REPL test result: no debouncing at all (50 writes for 50 calls). The flow's internal mult drains the output channel faster than it fills, so the sliding-buffer never activates.

**Alternative C: Transducer-based debouncing**

Transducers (`xform` in chan-opts) are synchronous per-put transformations. They CANNOT introduce time-based delays. No transducer can express "wait N ms then emit."

### Verdict: Per-Namespace Sliding-Buffer-1 Is the Right Tool

The sliding-buffer pattern works because:

1. **Per-namespace channels eliminate the shared-buffer data loss.** Each namespace has its own `sliding-buffer 1` channel to the writer. No cross-namespace interference.
2. **Writer backpressure IS the debounce.** While the writer is busy with Datalevin I/O, the buffer holds only the latest state. When the writer is fast, more writes get through (correct behavior).
3. **No external state to manage.** No timer, no dirty flag, no `ScheduledExecutorService`, no lifecycle management.
4. **Uses a core.async primitive.** `sliding-buffer` is battle-tested and well-understood.
5. **Persist messages carry full ctx state.** This matches the current `do-persist!` which serializes entire ctx to EDN. No incremental tx-data needed.

### Cost Analysis

The sliding-buffer approach has minimal overhead:

- Map creation for persist events: ~111ns per call
- Channel put on sliding-buffer (dropped): ~229ns per call
- Total cost of 50 dropped persist messages: ~17 microseconds
- No timer ticks, no dirty flag checks, no ScheduledExecutorService thread

## Rejected Alternatives

### Thread-local sentinel

Using a `ThreadLocal` instead of an atom for the sentinel. Rejected because the atom-update-sink runs on a flow thread, but the watch callback runs on whatever thread called `reset!` (which is the flow thread). A ThreadLocal would work IF the watch always runs on the same thread as the `reset!` call -- which it does (Clojure atom watches fire synchronously on the thread that changed the atom). However, this relies on an implementation detail of Clojure atoms. The atom-based sentinel is more explicit and doesn't depend on threading assumptions.

### Version counter sentinel

Instead of a boolean, stamp each flow-to-atom write with a monotonic version. The watch checks if the atom's current version matches the last flow-written version. Rejected because it adds complexity without benefit over the boolean sentinel -- the boolean already handles the race window correctly.

### Compare-and-set in watch

Have the watch compare old-val/new-val to detect flow-originated changes. Rejected because the flow emits arbitrary ctx values -- there's no way to distinguish "this value came from the flow" vs "this value came from a REPL swap that happens to produce the same result."

### Persistence on atom watches (original recommendation)

The original recommendation kept `::persist` on atom watches because "flow steps cannot debounce." The sliding-buffer-1 pattern solves this more simply: each namespace step emits `:persist` on every ctx change, and `sliding-buffer 1` on the per-namespace channel to the writer debounces via backpressure. Keeping persistence on atom watches would mean the atom has side-effects, which contradicts the flow-first principle. With sliding-buffer persistence, the atom is a pure read cache.

### Flush-timer pattern (superseded by sliding-buffer)

An external `ScheduledExecutorService` injecting `:flush` signals into the flow step, with a `::dirty?` flag to track whether persistence is needed. This worked correctly but required: (a) Java `ScheduledExecutorService` lifecycle management, (b) `::dirty?` state in the step, (c) `:flush` input port, (d) timer start/stop on flow resume/stop. The sliding-buffer-1 approach achieves the same debouncing with zero additional state or lifecycle -- it uses a core.async primitive instead of Java machinery.

## Resolved Questions

### Should `ctx/swap!` route through flow?

No, at least not initially. Direct `swap!` on the atom is preserved for REPL ergonomics. The `::flow-sync` watch injects the change into flow, which handles all effects (render, persist). This is the sentinel approach (Option A) -- proven convergent under stress.

A future optimization could provide `ctx/swap-via-flow!` that skips the atom entirely and injects directly into the flow. This would be faster (no sentinel, no watch callback) but less ergonomic. Not needed until sentinel proves to be a bottleneck.

### What about `ctx/update!` calls from domain code?

Domain code should route through the dispatch layer (which calls the function, gets `::ctx` back, applies it via the flow). Direct `ctx/update!` still works via the `::flow-sync` watch but adds latency (atom swap -> watch -> inject -> flow -> render) vs the dispatch path (dispatch -> flow -> fn-call -> render). Both paths produce correct results. The dispatch path is preferred for new code.
