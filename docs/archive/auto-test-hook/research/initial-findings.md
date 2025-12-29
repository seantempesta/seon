# Initial Research Findings: Auto-Test Hook

## Hook Architecture Discovery

### Project-Level Hooks ARE Supported

Claude Code hooks can be configured at multiple levels:
- **Global**: `~/.claude/settings.json` - applies to all projects
- **Project (shared)**: `.claude/settings.json` - checked into version control
- **Project (local)**: `.claude/settings.local.json` - gitignored, machine-specific

Hooks from all levels **run in parallel** for the same event.

### Current Global Hook

The existing `clj-paren-repair-claude-hook` is installed globally and:
- Runs on `PreToolUse` and `PostToolUse` for `Write|Edit` operations
- Fixes delimiter errors before write, restores from backup if unfixable
- Runs cljfmt formatting after successful edits
- Uses babashka for fast startup (~instant)

### Hook Input Data Available

Every hook receives via stdin:
```json
{
  "session_id": "string",
  "cwd": "string",                    // Current working directory!
  "hook_event_name": "EventName",
  "tool_name": "string",
  "tool_input": {
    "file_path": "...",               // The file being edited
    "content": "..." or "old_string"/"new_string"
  }
}
```

This means a test hook can:
1. Know exactly which file was changed
2. Map that to test namespace(s)
3. Run targeted tests

### Hook Output Options

Hooks can return:
- Exit code 0 = success, continue
- Exit code 2 = **blocking error** (stops the operation, stderr shown to Claude)
- JSON response with `continue`, `stopReason`, `systemMessage`, etc.

**Key insight**: We probably DON'T want to block on test failures during multi-file edits.

## Key Design Questions to Resolve

### 1. Blocking vs Non-Blocking

If agent is editing 5 files to fix an issue, we don't want to block after file 1 fails tests.

Options:
- **Fully non-blocking**: Tests run in background, results appear later
- **Non-blocking with notification**: Tests run async, result summary injected into context
- **Blocking only on final edit**: Track edit session, run tests when agent stops editing

### 2. Test Runner Approach

Options:
- **nREPL-based** (fast, uses running JVM): `clj-nrepl-eval -p 7888 "(require 'foo-test) (clojure.test/run-tests 'foo-test)"`
- **Kaocha with --focus** (slower, new JVM each time): `clj -M:test -m kaocha.runner --focus foo-test`
- **Integrant component** (keeps test runner warm in memory): Most complex but fastest

### 3. File → Test Mapping

Need to determine which tests to run when a file changes:
- `src/ml_options/foo.clj` → run `test/ml_options/foo_test.clj`
- `test/ml_options/foo_test.clj` → run itself
- But what about transitive dependencies? If `bar.clj` uses `foo.clj` and we change `foo.clj`, should we run `bar_test.clj`?

Tools available:
- `clojure.tools.namespace` - can track namespace dependencies
- Simple path convention mapping (fastest, might miss transitive deps)

### 4. Output Management

Full test output can be 100+ lines. We need:
- **Summary for context**: "3 tests, 2 passed, 1 failed: foo-test/test-bar"
- **Full output saved somewhere**: Log file? XTDB? Temp file?
- **Breadcrumb for debugging**: "Full output: /tmp/test-results/session-123.txt"

### 5. Generative Tests

Property-based tests with malli generators can be slow (seconds vs milliseconds).

Options:
- Run with reduced iterations on hook (e.g., 10 instead of 100)
- Skip generative tests on hook, run separately
- Metadata-based filtering (`:quick` vs `:thorough` tags)

## Next Research Steps

1. **Prototype nREPL-based test running** - How fast is it really?
2. **Explore clojure.tools.namespace** - Can we get dependency graph?
3. **Kaocha filtering** - What metadata/focus options exist?
4. **Hook response format** - What's the best way to inject non-blocking feedback?

## Current Test Structure

```
test/ml_options/
├── db/schema_test.clj
├── data/
│   ├── bulk_load_test.clj
│   ├── ingest_test.clj
│   ├── ingestion_state_test.clj
│   ├── thetadata_test.clj
│   └── validation_test.clj
├── log_parsing_test.clj
└── web/
    ├── handlers_test.clj
    └── stats_test.clj
```

Convention is clear: `src/ml_options/foo.clj` → `test/ml_options/foo_test.clj`
