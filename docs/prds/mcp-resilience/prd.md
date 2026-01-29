# MCP Server Resilience

## Status: Research Phase

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

## Hypotheses (Unverified)

### Hypothesis 1: Single-Threaded MCP Server

The MCP server appears to use a synchronous read loop. If `nrepl-eval` blocks waiting for a response, the server cannot read new incoming requests (including interrupts).

**Evidence:** Both new evals AND interrupt calls hang when in stuck state.

**Uncertainty:** We haven't traced the exact execution flow during cancellation.

### Hypothesis 2: State Cleared Prematurely

When the user cancels an MCP request, the `finally` block may run and clear `orchestrator-eval-state`, but the actual nREPL evaluation continues running.

**Evidence:** Concurrent check doesn't trigger on subsequent evals.

**Uncertainty:** We don't know exactly when/how MCP request cancellation propagates.

### Hypothesis 3: nREPL Session Queueing

We switched to a persistent nREPL session for interrupt support. If evals on the same session are serialized, new evals queue behind the blocked one.

**Evidence:** Behavior changed after adding persistent session.

**Uncertainty:** nREPL session threading model not fully understood.

### Hypothesis 4: Interrupt Requires Live Connection

The interrupt may require the MCP server to be able to make a new TCP connection to nREPL, which it can't do if blocked in its read loop.

**Evidence:** Interrupt hangs along with everything else.

**Uncertainty:** Haven't verified if the interrupt code path is even reached.

---

## What We Don't Know

1. **MCP cancellation semantics** - What happens at the protocol level when user cancels? Does the MCP server receive a signal?

2. **nREPL session model** - How does nREPL handle multiple concurrent evals on the same session? Different sessions?

3. **Babashka threading** - What threading primitives are available? Can we run interrupt handling on a separate thread?

4. **Failure modes** - Are there other scenarios that lead to stuck state? (timeouts, agent crashes, network issues)

5. **Recovery options** - Is there a way to forcibly reset nREPL session state? Kill specific evaluations?

---

## Success Criteria

1. **Interrupt always works** - `interrupt_eval(session_id="orchestrator")` unblocks the system within 5 seconds, regardless of what state it's in.

2. **Clear error messages** - When the system is blocked, new evals return immediately with an error explaining the situation and suggesting `interrupt_eval`.

3. **No silent hangs** - Every operation either completes, times out with a message, or returns an error. Nothing hangs indefinitely.

4. **Graceful degradation** - If one agent or eval fails, it doesn't take down the whole orchestrator.

5. **Observable state** - We can always query what's happening (what's blocked, why, how long).

---

## Proposed Approach

### Phase 0: Research & Instrumentation

**Goal:** Understand the actual failure modes before changing anything.

**Tasks:**
1. Add logging/tracing to MCP server to see exactly what happens during cancellation
2. Research nREPL session/threading model
3. Research Babashka concurrency options
4. Document the exact sequence of events in each failure scenario
5. Update this PRD with findings

**Output:** Updated PRD with verified understanding and proposed solution.

### Phase 1: Minimal Fix

**Goal:** Make interrupt work reliably.

Scope TBD based on Phase 0 findings. Possible directions:
- Separate thread/process for interrupt handling
- Different nREPL session for control operations
- Signal-based interrupt mechanism

### Phase 2: Robustness

**Goal:** Prevent stuck states proactively.

Scope TBD. Possible directions:
- Better state tracking that survives cancellation
- Watchdog that detects stuck states
- Automatic recovery mechanisms

### Phase 3: Observability

**Goal:** Always know what's happening.

Scope TBD. Possible directions:
- Health endpoint that works even when stuck
- Out-of-band status mechanism
- Better error messages throughout

---

## Testing Strategy

Each phase must include:

1. **Unit tests** - Individual functions behave correctly
2. **Integration tests** - Components work together
3. **Failure injection** - Deliberately trigger failure modes
4. **Manual verification** - Actually cancel evals, kill processes, etc.

Specific test scenarios to cover:
- Cancel eval at various points in execution
- Multiple rapid cancellations
- Cancel during agent startup vs. during agent work vs. during result collection
- Interrupt when nothing is running
- Interrupt when something is running
- Interrupt when already stuck
- Network issues between MCP server and nREPL
- nREPL server restart while eval in progress

---

## Related Work

### Recently Completed
- Added persistent orchestrator nREPL session for interrupt support
- Added `interrupt_eval(session_id="orchestrator")` tool
- Added concurrent eval detection (not working as intended)
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

## Open Questions

1. Should the MCP server be a long-running process, or spawn fresh per-request?
2. Is there a way to make the orchestrator REPL stateless/restartable without losing agent state?
3. Should we have a "supervisor" process that can restart the MCP server?
4. Are there patterns from other MCP implementations we can learn from?
5. What's the Claude Code team's recommended approach for long-running operations?

---

## Notes

- This is critical infrastructure - changes must be careful and well-tested
- The current workaround is restarting Claude Code entirely
- Agent state in XTDB survives restarts, but conversation context is lost
