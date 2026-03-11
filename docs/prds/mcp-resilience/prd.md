# MCP Server Resilience

## Status: Phase 1 Complete - Async Request Processing Implemented

## Problem Statement

The orchestrator's MCP-based REPL becomes unrecoverable when certain failure modes occur. When this happens, the only recovery is restarting Claude Code entirely, losing conversation context and disrupting workflow.

This is a critical infrastructure issue because the orchestrator is the primary interface for all agent coordination work.

---

## Observed Behaviors

### Scenario 1: Cancelled Blocking Eval

**Steps to reproduce:**

1. Launch a blocking agent: `(user/launch-agent!! 'ns "prompt")` with long timeout
2. User cancels the MCP eval (Ctrl+C or UI cancel)
3. Attempt any new eval: `(user/agents)`

**Observed:** New eval hangs indefinitely. No error message. No timeout.

**Expected:** Either the eval works, or we get an immediate error explaining the situation with recovery options.

### Scenario 2: Interrupt Tool Fails When Needed

**Steps to reproduce:**

1. Get into the stuck state from Scenario 1
2. Call `interrupt_eval(session_id="orchestrator")`

**Observed:** Interrupt tool also hangs indefinitely.

**Expected:** Interrupt should work independently and unblock the system.

### Scenario 3: Concurrent Eval Detection Bypassed

**Context:** We implemented concurrent eval detection that should return an error if the orchestrator is already busy.

**Observed:** The detection doesn't trigger in Scenario 1. The new eval hangs instead of returning the "Orchestrator busy" error.

**Expected:** The concurrent check should detect the blocked state and return immediately with guidance.

---

## Current Architecture (As We Understand It)

### Components Involved

```
Claude Code CLI
    ↓ (spawns)
bin/mcp-server (Babashka process)
    ↓ (nREPL bencode over TCP)
Seon nREPL Server (port 7888)
    ↓ (Clojure evaluation)
seon.ai.claude (agent management)
```

### Key Files

| File | Role |
|------|------|
| `bin/mcp-server` | MCP protocol handler, nREPL client |
| `src/seon/ai/claude.clj` | Agent lifecycle, blocking waits |
| `src/seon/ai/claude/sdk.clj` | Claude CLI process management |
| `env/dev/clj/user.clj` | User-facing helper functions |

### State Tracking (Current)

- `orchestrator-eval-state` atom in MCP server - tracks active MCP request
- `orchestrator-nrepl-session` atom - persistent nREPL session ID for interrupts
- Agent registry in `seon.ai.agent` - tracks running agents

---

## Research Findings (Phase 0 Complete)

### nREPL Session Model - VERIFIED

**Key finding:** nREPL strictly serializes all evaluations within a single session.

From [nREPL documentation](https://nrepl.org/nrepl/ops.html):
> "All requests within the same session are serialized, so if you want to evaluate two expressions simultaneously you'll have to do this in separate sessions."

Each session has a dedicated execution thread. Evaluations queue up and execute one at a time.

**Interrupt mechanism:**

- Interrupt CAN use a separate TCP connection - sessions are "persistent, cross-connection REPL sessions"
- Interrupt sends a signal, waits 100ms, then 5000ms, then forcibly stops the thread
- A new execution thread is spawned with preserved dynamic bindings
- **Ephemeral sessions cannot be interrupted** - only persistent/cloned sessions work

### Babashka Threading - VERIFIED

**Key finding:** Babashka supports real JVM threads via `future`.

From [Babashka documentation](https://book.babashka.org/):
> "Babashka supports real JVM threads and like Clojure, supports futures and dynamic thread-locally bound vars."

Available primitives:

- `future` - for async computation ✓
- `promise` - for coordination ✓
- `clojure.core.async` - full support ✓
- `atom` - for thread-safe state ✓

### Root Cause Analysis - CONFIRMED

The MCP server main loop (lines 718-728) is **synchronous**:

```clojure
(loop []
  (when-let [line (.readLine reader)]  ; 1. Read request
    (handle-request request)            ; 2. BLOCKS here during eval
    (recur)))                           ; 3. Can't reach until step 2 completes
```

When `handle-request` → `execute-eval` → `nrepl-eval` is called:

1. It opens a TCP socket to nREPL
2. Sends the eval request
3. **Blocks reading responses** until "done" status received
4. The main loop cannot read new stdin until this returns

When user cancels:

1. Claude Code stops waiting for the MCP response
2. MCP server process continues running, still blocked in socket read
3. New requests from Claude Code arrive on stdin
4. MCP server **cannot read them** because it's still in step 3
5. Everything hangs

**Why concurrent detection doesn't help:** The detection code runs INSIDE `execute-eval`. But we never get to `execute-eval` for the new request because we can't even read it from stdin.

**Why interrupt hangs:** The interrupt request arrives on stdin, but the main loop is blocked reading the previous request's nREPL response. The interrupt code path is never reached.

### Hypotheses - Resolved

| Hypothesis | Status | Explanation |
|------------|--------|-------------|
| Single-threaded MCP server | **CONFIRMED** | Main loop blocks on nrepl-eval |
| State cleared prematurely | **SECONDARY** | True, but not the root cause |
| nREPL session queueing | **CONFIRMED** | Sessions serialize evals |
| Interrupt requires live connection | **NOT THE ISSUE** | Issue is main loop can't read request |

---

## Success Criteria

1. **Interrupt always works** - `interrupt_eval(session_id="orchestrator")` unblocks the system within 5 seconds, regardless of what state it's in.

2. **Clear error messages** - When the system is blocked, new evals return immediately with an error explaining the situation and suggesting `interrupt_eval`.

3. **No silent hangs** - Every operation either completes, times out with a message, or returns an error. Nothing hangs indefinitely.

4. **Graceful degradation** - If one agent or eval fails, it doesn't take down the whole orchestrator.

5. **Observable state** - We can always query what's happening (what's blocked, why, how long).

---

## Proposed Solution

### Architecture Change: Async Request Processing

**Core idea:** Process eval requests in a `future` so the main loop stays responsive.

```
Current (broken):
  stdin → read → handle-request (BLOCKS) → respond → read next

Proposed (fixed):
  stdin → read → dispatch to future → read next (RESPONSIVE)
                      ↓
              handle-request
                      ↓
              respond (via atom + flush)
```

### Phase 1: Async Eval Processing (Minimal Fix)

**Goal:** Make the main loop non-blocking so interrupt requests can be processed.

**Changes to `bin/mcp-server`:**

1. **Add response queue/atom:**

   ```clojure
   (def response-queue (atom []))
   ```

2. **Wrap blocking operations in `future`:**

   ```clojure
   (defn handle-tools-call-async [id {:keys [name arguments]}]
     (future
       (try
         ;; existing tool dispatch logic
         (let [result (case name ...)]
           (swap! response-queue conj [:result id result]))
         (catch Exception e
           (swap! response-queue conj [:error id e])))))
   ```

3. **Non-blocking main loop:**

   ```clojure
   (loop []
     ;; Flush any pending responses first
     (doseq [[type id payload] @response-queue]
       (case type
         :result (send-result id payload)
         :error (send-error id ...)))
     (reset! response-queue [])

     ;; Non-blocking read with short timeout
     (when (.ready reader)
       (when-let [line (.readLine reader)]
         (handle-request request)))  ; dispatch, don't block

     (Thread/sleep 10)  ; prevent busy loop
     (recur))
   ```

4. **Track running eval for interrupt:**

   ```clojure
   (def running-eval (atom nil))  ; {:future f :session-id sid}
   ```

**Why this works:**

- Main loop can always read new requests (including interrupts)
- Interrupt can cancel the `future` and send nREPL interrupt
- Concurrent detection works because we can read the new request
- Responses are serialized through the atom

**Estimated scope:** ~50-100 lines of changes to `bin/mcp-server`

### Phase 2: Robustness (Optional)

After Phase 1 is working:

1. **Watchdog timer** - Auto-interrupt evals that exceed timeout
2. **Graceful shutdown** - Handle MCP server shutdown cleanly
3. **Connection recovery** - Reconnect to nREPL if connection drops

### Phase 3: Observability (Optional)

1. **Health endpoint** - Out-of-band HTTP endpoint for status
2. **Structured logging** - JSON logs for debugging
3. **Metrics** - Eval counts, durations, errors

---

## Implementation Notes

### Thread Safety Considerations

1. **Response serialization** - Only one thread writes to stdout at a time
2. **State consistency** - Use atoms for all shared state
3. **Exception handling** - Catch all exceptions in futures to prevent silent failures

### Testing Strategy

1. **Unit test:** Verify async dispatch doesn't break normal eval flow
2. **Integration test:** Cancel eval mid-execution, verify interrupt works
3. **Stress test:** Rapid cancellation, multiple concurrent requests
4. **Manual test:** Reproduce original failure scenario, verify fix

### Rollback Plan

Keep the old synchronous code path as a fallback (env var toggle) in case async approach introduces unexpected issues.

---

## Implementation Summary (Phase 1)

**Version:** MCP Server v0.3.0 (async mode)

**Changes to `bin/mcp-server`:**

1. **Response queue atom** - Futures push responses here, main loop sends them
2. **Running eval tracking** - Tracks current eval future for interrupt support
3. **Thread-safe stdout** - Uses `locking` to prevent interleaved output
4. **Async tool dispatch** - Blocking tools (`eval`, `create_session`) run in `future`
5. **Non-blocking main loop** - Uses `.ready()` check, processes interrupts immediately
6. **Enhanced interrupt** - Cancels running future AND sends nREPL interrupt

**Key functions added:**

- `queue-response!`, `queue-error!` - Queue responses from futures
- `execute-tool-sync` - Synchronous tool execution
- `blocking-tool?` - Identifies tools that need async handling
- `flush-response-queue!` - Sends queued responses from main loop
- `log-info` - Info-level logging for startup messages

**Behavior change:**

- Main loop now polls stdin with 10ms sleep (100 checks/sec)
- Blocking evals run in background threads
- Interrupt requests are processed immediately, even during long-running evals

---

## Related Work

### Recently Completed (This PR)

- **Phase 0:** Research confirmed root cause (single-threaded blocking main loop)
- **Phase 1:** Implemented async request processing with futures
- MCP Server version bumped to 0.3.0

### Previously Completed

- Added persistent orchestrator nREPL session for interrupt support
- Added `interrupt_eval(session_id="orchestrator")` tool
- Added concurrent eval detection (now works with async processing)
- Improved "busy" error message with guidance

### In Progress (Paused)

- Agent stuck detection improvements (task #5)
  - Expose result subtype ("error_max_turns")
  - Add last-activity-at tracking
  - Add process liveness checks
  - Agent health helper function

### Related But Separate

- 10000 max-turns fix (needs verification)
- Observatory XTDB view consolidation

---

## Remaining Questions

1. **MCP cancellation semantics** - Does Claude Code send a signal when user cancels? (Probably not - likely just closes stdin or stops reading stdout)
2. **Response ordering** - Does JSON-RPC require responses in request order? (Research suggests no, but verify)
3. **Timeout behavior** - Should async evals auto-cancel after timeout, or just return timeout error while continuing?

## Resolved Questions

| Question | Answer |
|----------|--------|
| Should MCP server be long-running or per-request? | Long-running is fine if we fix the blocking issue |
| Can we make orchestrator restartable? | Not needed if interrupt works |
| Need supervisor process? | Not needed if interrupt works |
| Claude Code team patterns? | Not needed - standard async pattern solves it |

---

## Notes

- This is critical infrastructure - changes must be careful and well-tested
- The current workaround is restarting Claude Code entirely
- Agent state in XTDB survives restarts, but conversation context is lost
