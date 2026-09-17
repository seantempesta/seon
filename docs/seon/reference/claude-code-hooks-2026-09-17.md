# Claude Code Hooks Reference — Verified Against Official Docs
**Date**: 2026-09-17  
**Source**: https://code.claude.com/docs/en/hooks.md and https://code.claude.com/docs/en/hooks-guide.md  
**Purpose**: Exact specifications for this project's hook configuration (bin/seon-hook, `.claude/settings.json` PreToolUse/PostToolUse matchers)

---

## (1) All Hook Events and stdin JSON Payload Fields

### Complete Event Lifecycle
Per [hooks-guide.md:480-517](https://code.claude.com/docs/en/hooks-guide.md):
> "Claude Code fires hook events at specific points in its lifecycle. When an event fires, Claude Code runs all matching hooks in parallel"

**All available hook events** (in order of occurrence):

| Event | Fires | Supports Blocking |
|-------|-------|-------------------|
| SessionStart | When a session begins or resumes | No |
| Setup | When you start Claude Code with --init-only, or with --init or --maintenance in -p mode | No |
| UserPromptSubmit | When you submit a prompt, before Claude processes it | Yes (exit 2) |
| UserPromptExpansion | When a user-typed command expands into a prompt, before it reaches Claude | Yes (exit 2) |
| PreToolUse | Before a tool call executes | Yes (exit 2 or permissionDecision: deny) |
| PermissionRequest | When a tool call needs a permission decision | No |
| PermissionDenied | When auto mode denies a tool call | No |
| PostToolUse | After a tool call succeeds | No |
| PostToolUseFailure | After a tool call fails | No |
| PostToolBatch | After a full batch of parallel tool calls resolves | No |
| Notification | When Claude Code sends a notification | No |
| MessageDisplay | While assistant message text is displayed | No |
| SubagentStart | When a subagent is spawned | No |
| SubagentStop | When a subagent finishes | No |
| TaskCreated | When a task is being created via TaskCreate | No |
| TaskCompleted | When a task is being marked as completed | No |
| Stop | When Claude finishes responding | Yes (exit 2) |
| StopFailure | When the turn ends due to an API error | No |
| TeammateIdle | When an agent team teammate is about to go idle | No |
| InstructionsLoaded | When a CLAUDE.md or .claude/rules/*.md file is loaded into context | No |
| ConfigChange | When a configuration file changes during a session | Yes (exit 2) |
| CwdChanged | When the working directory changes (e.g., cd command) | No |
| DirectoryAdded | When a working directory is added mid-session | No |
| FileChanged | When a watched file changes on disk | No |
| WorktreeCreate | When a worktree is being created | Yes (any non-zero exit) |
| WorktreeRemove | When a worktree is being removed | Yes (any non-zero exit) |
| PreCompact | Before context compaction | No |
| PostCompact | After context compaction completes | No |
| PreModelSwitch | Before Claude Code applies a model switch | Yes (exit 2 or permissionDecision) |
| PostModelSwitch | After the session's model changes | No |
| Elicitation | When an MCP server requests user input during a tool call | No |
| ElicitationResult | After a user responds to an MCP elicitation | No |
| SessionEnd | When a session terminates | No |

### Common stdin JSON Fields (All Events)
Per [hooks.md](https://code.claude.com/docs/en/hooks.md), every hook receives:

```json
{
  "session_id": "abc123",
  "prompt_id": "550e8400-e29b-41d4-a716-446655440000",
  "transcript_path": "/path/to/transcript.jsonl",
  "cwd": "/current/working/directory",
  "scratchpad_dir": "/tmp/scratchpad/path",
  "permission_mode": "default|plan|acceptEdits|auto|dontAsk|bypassPermissions",
  "effort": { "level": "low|medium|high|xhigh|max" },
  "hook_event_name": "PreToolUse",
  "agent_id": "subagent-uuid",
  "agent_type": "Explore|security-reviewer|custom-name"
}
```

### Event-Specific stdin Fields

#### Tool Events: PreToolUse, PostToolUse, PostToolUseFailure, PermissionRequest, PermissionDenied
Per [hooks.md](https://code.claude.com/docs/en/hooks.md):
```json
{
  "tool_name": "Bash|Edit|Write|MultiEdit|PowerShell|mcp__server__tool",
  "tool_input": {
    "command": "git push",
    "file_path": "src/main.ts",
    "description": "User-provided description"
  },
  "tool_use_id": "toolu_01ABC123...",
  "tool_response": "Output from successful tool execution",
  "tool_error": "Error message if tool failed"
}
```

#### UserPromptSubmit
Per [hooks-guide.md:583](https://code.claude.com/docs/en/hooks-guide.md):
> "UserPromptSubmit hooks get the prompt text instead"

```json
{
  "user_input": "Debug this error",
  "permission_mode": "default"
}
```

#### UserPromptExpansion
Per [hooks.md](https://code.claude.com/docs/en/hooks.md):
```json
{
  "command_name": "my-skill",
  "command_input": "command arguments"
}
```

#### SessionStart
Per [hooks-guide.md:583](https://code.claude.com/docs/en/hooks-guide.md):
> "SessionStart hooks get a source of startup, resume, clear, compact, or fork"

```json
{
  "session_id": "abc123",
  "startup_type": "startup|resume|clear|compact|fork",
  "model": "claude-3-5-sonnet-20241022"
}
```

#### SessionEnd
```json
{
  "session_id": "abc123",
  "exit_reason": "clear|resume|logout|prompt_input_exit|other"
}
```

#### Stop
```json
{
  "last_assistant_message": "Full assistant text",
  "stop_reason": "end_turn|max_tokens|...",
  "stop_hook_active": false
}
```

#### CwdChanged
```json
{
  "old_cwd": "/home/user/project",
  "new_cwd": "/home/user/project/subdir",
  "cwd": "/home/user/project/subdir"
}
```

#### FileChanged
```json
{
  "file_path": "relative/path/to/file",
  "change_type": "modified|created|deleted"
}
```

#### ConfigChange
```json
{
  "config_source": "user_settings|project_settings|local_settings|policy_settings|skills"
}
```

#### InstructionsLoaded
```json
{
  "file_path": "CLAUDE.md",
  "load_reason": "session_start|nested_traversal|path_glob_match|include|compact"
}
```

#### SubagentStart / SubagentStop
```json
{
  "agent_id": "subagent-uuid",
  "agent_type": "Explore|Plan|security-reviewer",
  "task_description": "User's task for the subagent"
}
```

#### PreModelSwitch / PostModelSwitch
```json
{
  "from_model": "claude-3-5-sonnet-20241022",
  "to_model": "claude-opus-5"
}
```

#### Notification
```json
{
  "notification_type": "permission_prompt|idle_prompt|auth_success|elicitation_dialog"
}
```

#### WorktreeCreate / WorktreeRemove
```json
{
  "worktree_path": "/path/to/worktree"
}
```

#### Elicitation / ElicitationResult
```json
{
  "mcp_server": "server-name",
  "prompt": "What should I do?"
}
```

#### StopFailure
```json
{
  "error_type": "rate_limit|overloaded|authentication_failed|server_error"
}
```

---

## (2) Exit Codes and stdout JSON Response Contract

### Exit Code Behavior
Per [hooks-guide.md:602-612](https://code.claude.com/docs/en/hooks-guide.md):

> "The exit code determines what happens next:
> * Exit 0: your hook reports no objection through its exit code.
> * Exit 2: Claude Code blocks the action. Write a reason to stderr.
> * Any other exit code: for most events, the outcome depends on what your hook printed to stdout"

#### Exit Code 0 (No objection)
- Hook reports no decision via exit code
- Stdout plain text is added to Claude's context (for UserPromptSubmit, UserPromptExpansion, SessionStart, PostModelSwitch)
- Or stdout is treated as JSON if it parses
- stderr is ignored

#### Exit Code 2 (Blocking error)
- Claude Code blocks the action unconditionally
- Reason comes from JSON permissionDecision field or stderr message
- Exit 2 takes precedence over any JSON that would allow the action

#### Other exit codes (non-blocking)
- Action proceeds
- If stdout is valid JSON matching schema: JSON alone decides outcome, exit code ignored
- If stdout is invalid JSON or plain text: logged as error notice with stderr message

### Standard JSON Output Schema
Per [hooks-guide.md:614-619](https://code.claude.com/docs/en/hooks-guide.md):

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "allow|deny|request",
    "permissionDecisionReason": "Reason for decision",
    "additionalContext": "Context for Claude",
    "decision": true,
    "stopReason": "user_requested|success|failure",
    "suppressOutput": false,
    "retry": false,
    "updatedInput": {
      "command": "modified command"
    }
  },
  "systemMessage": "Message displayed in transcript",
  "terminalSequence": "escape sequence"
}
```

### Decision Field Semantics by Event

#### PreToolUse (and PreModelSwitch)
Per [hooks-guide.md:633-641](https://code.claude.com/docs/en/hooks-guide.md):

> "On PreToolUse, Claude Code handles each permissionDecision value as follows:
> * allow: skip the interactive permission prompt
> * deny: cancel the tool call and send the reason to Claude
> * ask: show the permission prompt to the user as normal"

Example:
```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "deny",
    "permissionDecisionReason": "Use rg instead of grep"
  }
}
```

#### PostToolUse and Stop
Per [hooks-guide.md:645](https://code.claude.com/docs/en/hooks-guide.md):
> "PostToolUse and Stop hooks use a top-level decision: block field"

#### PermissionRequest
Per [hooks-guide.md:441](https://code.claude.com/docs/en/hooks-guide.md):
```json
{
  "hookSpecificOutput": {
    "hookEventName": "PermissionRequest",
    "decision": {
      "behavior": "allow|deny|ask"
    }
  }
}
```

#### UserPromptSubmit
Per [hooks-guide.md:645-658](https://code.claude.com/docs/en/hooks-guide.md):
> "For UserPromptSubmit hooks, use hookSpecificOutput.additionalContext instead to inject text"

Example:
```json
{
  "hookSpecificOutput": {
    "hookEventName": "UserPromptSubmit",
    "additionalContext": "Current branch: release-42"
  }
}
```

---

## (3) Matcher Semantics, Execution Order, Timeout

### Matcher Rules
Per [hooks-guide.md:680-681](https://code.claude.com/docs/en/hooks-guide.md):

> "The Edit|Write matcher fires only when Claude uses the Edit or Write tool. On Claude Code v2.1.191 or later, a comma separates alternatives the same way."

### Matcher Pattern Evaluation

| Matcher Value | Evaluated As | Behavior |
|---------------|--------------|----------|
| "" (empty string) | Match all | Fires on every occurrence |
| Omitted field | Match all | Fires on every occurrence |
| "*" | Match all | Fires on every occurrence |
| Simple text | Exact match or pipe-separated list | Bash, Edit|Write |
| Other characters | JavaScript regex (unanchored) | .+, ^Notebook, mcp__.*, .* |

**Regex matching** uses JavaScript RegExp.prototype.test(), which matches anywhere unless anchored.

**Case-sensitive**: Per [hooks-guide.md:976](https://code.claude.com/docs/en/hooks-guide.md):
> "Matchers are case-sensitive"

### Multiple Hook Handlers and Execution Order
Per [hooks-guide.md:525-530](https://code.claude.com/docs/en/hooks-guide.md):

> "When multiple hooks match the same event, every hook's command runs to completion. After all matching hooks finish, Claude Code combines their outputs. For PreToolUse permission decisions, the most restrictive answer applies, in the order deny, defer, ask, allow."

### Timeout Defaults and Configurability
Per [hooks-guide.md:952-957](https://code.claude.com/docs/en/hooks-guide.md):

> "Hook timeouts vary by type. Override per hook with the timeout field in seconds.
> * command, http, mcp_tool: 10 minutes [600 seconds]
> * Claude Code lowers this default to 30 seconds for UserPromptSubmit, PreModelSwitch, and PostModelSwitch
> * prompt: 30 seconds
> * agent: 60 seconds
> * SessionEnd hooks share 1.5-second budget"

---

## (4) Session Reload Behavior

### Settings File Watching
Per [hooks-guide.md:841](https://code.claude.com/docs/en/hooks-guide.md):

> "If you edit settings files directly while Claude Code is running, the file watcher normally picks up hook changes automatically."

**Specifics**: Exact timing not explicitly documented. Hook changes are picked up on the running session without requiring a restart.

---

## (5) Tool Names and Subagent Tool Call Reporting

### Built-in Tool Names
Per [hooks-guide.md:691](https://code.claude.com/docs/en/hooks-guide.md):

| Tool | tool_name Value |
|------|-----------------|
| Bash | Bash |
| PowerShell | PowerShell |
| Edit | Edit |
| Write | Write |
| MultiEdit | MultiEdit |

**apply_patch**: Not documented in the hooks reference.
**Task/Agent tools**: TaskCreate event exists, but tool_name not documented.

### MCP Tool Names
Per [hooks-guide.md:735-756](https://code.claude.com/docs/en/hooks-guide.md):

> "MCP tools use naming pattern: mcp__<server>__<tool>. For example, mcp__github__search_repositories. Tools from a plugin-bundled server use mcp__plugin_<plugin-name>_<server>__<tool>."

### Subagent Tool Call Reporting
Per [hooks.md](https://code.claude.com/docs/en/hooks.md):

> "When a hook fires inside a subagent call:
> - agent_id field: Unique identifier for that subagent run
> - agent_type field: Subagent's agent type
> - Tool events include these fields
> - Main-thread hooks can distinguish subagent calls by checking agent_id"

**Behavior**: Subagent tool calls fire the parent session's hooks with agent_id and agent_type fields populated.

---

## (6) Security and Scope

### Project vs User Settings Precedence
Per [hooks-guide.md:825-839](https://code.claude.com/docs/en/hooks-guide.md):

| Location | Scope | Shareable |
|----------|-------|-----------|
| ~/.claude/settings.json | All projects | No, local only |
| .claude/settings.json | Single project | Yes, can be committed |
| .claude/settings.local.json | Single project | No, gitignored |
| Managed policy settings | Organization-wide | Yes, admin-controlled |
| Plugin hooks/hooks.json | When plugin enabled | Yes, bundled |
| Skill frontmatter | Rest of session | Yes, in skill file |
| Subagent frontmatter | While subagent runs | Yes, in subagent file |

**Merging behavior**: Hooks from user, project, and local settings merge rather than replace.

### disableAllHooks
Per [hooks-guide.md:839](https://code.claude.com/docs/en/hooks-guide.md):

> "To disable hooks, set disableAllHooks: true. A project's settings file can override yours."

**Scope**: User, project, local, or managed settings.
**Behavior**: Disables hooks, custom status line, and custom file suggestion command.

### Plugin Hooks
Per [hooks-guide.md:833-834](https://code.claude.com/docs/en/hooks-guide.md):

Plugins can bundle hooks in hooks/hooks.json. These run when the plugin is enabled.

### Skill and Subagent Hooks
Skills can define hooks in YAML frontmatter. Active for rest of session after skill invoked.

Subagents can define hooks in YAML frontmatter. Active only while subagent is running.

**Trust**: Project skill/subagent hooks require workspace trust.

---

## Summary for This Project

This project's hook configuration in .claude/settings.json registers PreToolUse and PostToolUse events with matcher: .* (all tools).

**Key facts**:
1. **PreToolUse matcher .***: Every tool call evaluated before execution
2. **PostToolUse matcher .***: Every successful tool call evaluated after execution
3. **Settings reload**: File watcher picks up changes automatically; no restart needed
4. **Subagent tool calls**: Fire parent session's hooks with agent_id and agent_type fields
5. **Exit code 2 blocks unconditionally**: Blocks action before it runs
6. **Timeout**: Default 600 seconds (10 minutes), configurable per hook
7. **Decision precedence**: Most restrictive wins (deny > ask > allow)
