# Agent Stuck Analysis

**Date:** 2026-01-29
**Investigated by:** Agent 6e8b (seon.ai.diagnostics)

## Executive Summary

Agents consistently get stuck after exactly **101 messages**. The root cause appears to be that Claude Code CLI has a **default max-turns limit of ~50 turns** (counting turns, not messages) when running in stream-json mode without explicitly setting `--max-turns`. Additionally, stderr is not being captured, so any error messages from the CLI are lost.

## Evidence

### Stuck Sessions Analyzed

| Session | Messages | Last Activity | Status |
|---------|----------|---------------|--------|
| 1684 | 101 | 2026-01-29T08:54:35Z | Pending Read tool call |
| babe | 101 | 2026-01-29T09:29:13Z | Pending Grep tool call |
| 5f68 | 101 | 2026-01-29T09:36:50Z | No pending tool call |

All stuck sessions have exactly 101 messages persisted to XTDB.

### Comparison with Successful Sessions

| Session | Messages | Cost | Status |
|---------|----------|------|--------|
| 6ce0 | 99 | $2.23 | completed |
| aa82 | 95 | $2.49 | completed |
| c9b4 | 92 | $1.20 | completed |
| 0bc1 | 90 | $2.27 | completed |

Successful sessions complete with 90-99 messages, while stuck ones hit exactly 101.

### Last Messages Before Stuck

For session 1684:
```
2026-01-29T08:54:34Z | MESSAGE  | assistant | "Now let me look at what Phase 2 involves..."
2026-01-29T08:54:35Z | TOOL     | Read | "{:file_path \"/Users/sean/src/seon/src/seon/web/agents.clj\"}"
```

The pattern is consistent: assistant makes a tool call, but the tool result never arrives. No `result` message is received from the CLI.

### Message Timing Analysis

Time deltas between messages in stuck session show normal flow (1-3 seconds) right up until the end - no timeouts or delays.

## Root Cause Analysis

### 1. Missing `--max-turns` Flag (PRIMARY)

The SDK's `build-args` function does NOT pass `--max-turns` unless explicitly set:

```clojure
;; src/seon/ai/claude/sdk.clj:143
max-turns
(into ["--max-turns" (str max-turns)])
```

Since `max-turns` is nil by default, no flag is passed. Claude Code CLI likely has an internal default of ~50 turns (each turn = request + response ≈ 2 messages, so 50 turns ≈ 100 messages).

### 2. No stderr Capture (SECONDARY)

The SDK captures stderr but never reads it:

```clojure
;; sdk.clj:247 - returned but never used
:stderr (process/stderr proc)
```

When the process dies due to hitting max-turns, any error message goes to stderr - which we ignore. This makes debugging impossible.

### 3. No Process Exit Handling (CONTRIBUTING)

While there is `onExit` handling, it only closes stdout and updates status. It doesn't capture why the process exited.

## Why 101 Messages Specifically?

The count of 101 (not exactly 100) likely comes from:
- 50 turns × 2 messages/turn = 100 messages
- Plus 1 extra partial message (the tool call that never got a response)

The discrepancy between sessions could be due to how "turns" are counted internally vs. how we count messages.

## Proposed Fixes

### Fix 1: Set Explicit High max-turns (CRITICAL)

Modify `launch-agent!` to always set a high `max-turns`:

```clojure
;; In claude.clj launch-agent!
(sdk/spawn-claude-code {::sdk/model (or model sdk/default-model)
                        ::sdk/permission-mode (or permission-mode "bypassPermissions")
                        ::sdk/max-turns (or max-turns 500)  ; <-- ADD DEFAULT
                        ...})
```

Or in sdk.clj:

```clojure
(def ^:const default-max-turns 500)

;; In build-args
(let [max-t (or max-turns default-max-turns)]
  (cond-> [cmd ...]
    max-t
    (into ["--max-turns" (str max-t)])
    ...))
```

### Fix 2: Add stderr Reader (HIGH)

Create a reader thread for stderr that logs any errors:

```clojure
;; In launch-agent!, after spawning
(future
  (try
    (with-open [rdr (io/reader stderr)]
      (loop []
        (when-let [line (.readLine rdr)]
          (log/warn "Claude stderr:" {:session-id id :line line})
          (agent-log/log-error! agent-logger line)
          (recur))))
    (catch Exception e
      (log/debug "Stderr reader closed" {:session-id id}))))
```

### Fix 3: Add Heartbeat/Diagnostic Persistence (MEDIUM)

Store periodic heartbeats to XTDB for post-mortem analysis:

```clojure
(schema/register! ::ai/heartbeat
  [:map
   [:seon.ai/session-id :string]
   [:seon.ai/timestamp inst?]
   [:seon.ai/message-count :int]
   [:seon.ai/last-tool-call {:optional true} :string]])
```

Update every N messages or every 30 seconds.

### Fix 4: Add Stuck Detection + Auto-Recovery (LOW)

Monitor agents for lack of progress:

```clojure
(defn check-stuck-agents! []
  (doseq [agent (agents {})]
    (when (and (= :running (:status agent))
               (> (- (System/currentTimeMillis) (:last-activity agent))
                  (* 5 60 1000))) ; 5 minutes no activity
      (log/warn "Agent appears stuck" {:session-id (:session-id agent)})
      ;; Optionally interrupt and restart
      )))
```

## Immediate Action Items

1. **[P0]** Add default `max-turns` of 500 to `build-args` or `launch-agent!`
2. **[P1]** Add stderr reader to capture CLI errors
3. **[P2]** Clean up stuck sessions in XTDB (mark as :stuck or :failed)
4. **[P2]** Add heartbeat persistence for diagnostics

## Testing the Fix

After implementing Fix 1:

```clojure
;; Launch a test agent with explicit high max-turns
(claude/launch-agent! {::ai/node node
                       ::ai/namespace 'seon.test.long
                       ::ai/prompt "Count from 1 to 200, one message at a time"
                       ::sdk/max-turns 300})
```

Should complete without getting stuck at 100 messages.

## Related Files

| File | Purpose |
|------|---------|
| `src/seon/ai/claude/sdk.clj` | CLI argument building |
| `src/seon/ai/claude.clj` | Agent launch and message handling |
| `env/dev/clj/user.clj` | Orchestrator helpers |

## Appendix: XTDB Queries Used

```clojure
;; Get stuck sessions
(xt/q node "SELECT * FROM ai_sessions WHERE _id LIKE 'ses-%' ORDER BY _valid_from DESC LIMIT 50")

;; Count messages per session
(group-by :seon.ai/session-id all-messages)

;; Check for result messages
(filter #(= "result" (:seon.ai.claude/message-type %)) all-messages)
```
