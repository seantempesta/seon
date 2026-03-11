# Investigation: Hooks in SDK Mode

## Status: RESOLVED - Hooks DO work in stream-json mode

## Summary

Previous investigation concluded hooks don't fire in `--output-format stream-json` mode. **This was incorrect.** The actual issue was a bug in `bin/seon-hook` that caused silent failures.

## The Bug

Line 259 of `bin/seon-hook` referenced `session-id` which was undefined:

```clojure
;; BUG: session-id was never defined in the let block
" | COMPLETE | session=" (or session-id "orchestrator")

;; FIX: Use claude-session-id which IS defined
" | COMPLETE | session=" (or claude-session-id "orchestrator")

```

This caused the hook script to crash after logging the first few lines, making it appear that hooks weren't firing when they actually were.

## Verification

After fixing the bug, hooks fire correctly in both modes:

### Orchestrator (interactive mode)

```
Wed Jan 14 12:11:38 | PostToolUse | tool=Edit | session=2e45b096-af8e-4317-8f65-1bc78ad0e53b

```

### SDK mode (stream-json)

```bash
echo '{"type":"user",...}' | claude --output-format stream-json --input-format stream-json \
  --setting-sources project,local --settings .claude/settings.json ...

```

```
Wed Jan 14 12:12:20 | PreToolUse | tool=Write | session=aba77512-5eb5-4a81-9ac0-efe3cd6d71a6
Wed Jan 14 12:12:20 | PostToolUse | tool=Write | session=aba77512-5eb5-4a81-9ac0-efe3cd6d71a6

```

Both PreToolUse and PostToolUse hooks fire correctly in stream-json mode.

## What We Built (All Working)

### 1. Hook routing for agent sessions

- `bin/seon-hook` routes to correct nREPL port based on session_id
- Maps Claude's UUID to Seon's 4-char session_id via `logs/session-map.edn`

### 2. Session mapping in SDK

- SDK captures Claude's session_id from first message
- Writes mapping: `{"claude-uuid" "seon-session-id"}` to `logs/session-map.edn`

### 3. Logging infrastructure

- `logs/hook-debug.log` - detailed hook invocation logging
- Shows: timestamp, event type, tool, session, file, port, decision

## Files Modified

| File | Change |
|------|--------|
| `bin/seon-hook` | Dynamic port routing + session UUID mapping + **bug fix** |
| `src/seon/claude/sdk.clj` | `--settings`, `--setting-sources`, session mapping |
| `src/seon/db/multi.clj` | `munge` for namespace→db-name (hyphen fix) |
| `.gitignore` | Added `tmp/` |
| `CLAUDE.md` | Added File Locations section (no /tmp usage) |

## SDK Hook Alternatives

The TypeScript SDK shows two approaches to hooks:

### 1. Shell-based hooks (settings.json) - WORKS

```json
{
  "hooks": {
    "PreToolUse": [{ "matcher": "(Edit|Write)", "hooks": [{"type": "command", "command": "./bin/seon-hook"}] }]
  }
}

```
Requires `--setting-sources project,local --settings .claude/settings.json` flags.

### 2. Function-based hooks (SDK options) - TypeScript/Python only

```typescript
hooks: {
  PreToolUse: [{
    matcher: "Write|Edit",
    hooks: [async (input) => { return { continue: true }; }]
  }]
}

```
Not available in Clojure SDK since we shell out to the CLI.

## Session ID Architecture

- **Claude's session_id**: UUID like `aba77512-5eb5-4a81-9ac0-efe3cd6d71a6`
- **Seon's session_id**: 4-char hex like `fbdd`
- **Mapping**: `logs/session-map.edn` stores `{"claude-uuid" "seon-id"}`

The hook reads Claude's session_id, looks it up in the map, and routes to the correct nREPL port.

## Next Steps

1. Test full agent launch with hooks
2. Verify hook routing to agent-specific nREPL ports
3. Test blocking hooks (test failures should block)
