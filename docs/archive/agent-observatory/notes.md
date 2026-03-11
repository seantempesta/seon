# Agent Observatory - Implementation Notes

## Phase 1: Structured Agent Log

**Completed:** 2026-01-20

### Implementation Summary

Created `seon.ai.agent.log` namespace with per-agent log files at `logs/agents/{session-id}.log`.

**Files modified:**
- `src/seon/ai/agent/log.clj` - New namespace for agent logging utilities
- `src/seon/ai/claude.clj` - Integrated logging into `launch-agent!` reader loop
- `test/seon/ai/agent/log_test.clj` - Unit tests for logging functions

### Key Design Decisions

1. **Separate log files per agent** - Each agent gets `logs/agents/{session-id}.log` rather than a single shared log. This enables:
   - `tail -f logs/agents/f602.log` for specific agent
   - `ls -lt logs/agents/` to see recent agents by modification time
   - Clean separation for debugging individual agents

2. **BufferedWriter with immediate flush** - Used direct file I/O rather than timbre appenders for:
   - Real-time tailing (flush on every write)
   - Simple per-agent file creation
   - No timbre configuration complexity

3. **No Malli schemas** - Functions in this namespace intentionally skip `:malli/schema` because they involve runtime objects (BufferedWriter) that cannot be property tested. This follows CONVENTIONS.md for opaque Java objects.

4. **Two-argument logging functions** - Functions like `log-message!` take `[logger event-data]` rather than a single map. This is a common pattern for stateful logging (similar to timbre, ring middleware) where the logger handle is passed separately from the event data.

### Log Format

```
2026-01-20T13:23:20Z | LAUNCH   | seon.trading | port=7892
2026-01-20T13:23:21Z | MESSAGE  | assistant | "I'll start by..."
2026-01-20T13:23:25Z | TOOL     | eval | "(xt/q node ...)"
2026-01-20T13:23:26Z | RESULT   | eval | "[{:_id ...}]"
2026-01-20T13:25:00Z | COMPLETE | subtype=success | cost=$0.45 | messages=84 | duration=100s

```

- ISO 8601 timestamps in UTC
- Event type left-padded to 8 chars for alignment
- Content truncated and newlines escaped for single-line format
- Pipe-delimited for easy parsing

### Gotchas for Future Agents

1. **Logger cleanup** - The logger is closed in both the reader's `finally` block AND the `close-fn`. This handles both normal completion and manual interruption.

2. **SDK message parsing** - The `log-sdk-message!` function handles the Claude SDK's nested message structure:
   - `{:type "assistant" :message {:role ... :content [...]}}`
   - Tool calls are nested in content blocks with `{:type "tool_use"}`
   - Tool results come as `{:type "user" :message {:content [{:type "tool_result"}]}}`

3. **keep_alive messages** - These are skipped in logging (and in XTDB persistence) since they're just heartbeats.

4. **Test directory** - Tests use `tmp/test-logs/` which is gitignored. The fixture creates and cleans up this directory.

### Usage

```bash
# Watch a specific agent
tail -f logs/agents/f602.log

# See recent agents (sorted by modification time)
ls -lt logs/agents/

# Watch all agents at once
tail -f logs/agents/*.log

```

### Future Improvements (Phase 2+)

- HOOK events for dev hook feedback (currently logged but not triggered)
- Web UI streaming via SSE
- Log rotation for long-running agents
- Structured JSON output option for programmatic access
