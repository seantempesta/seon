> **Status: ARCHIVED** — Superseded by Datalevin-backed observatory

> **Status: ARCHIVED** — Superseded by Datalevin-backed observatory

# PRD: Observatory XTDB-Based Display

**Status:** Paused (Phase 1b.10 complete, 1b.13 in progress) - Pausing for MCP resilience work
**Priority:** High
**Branch:** feature/namespace-ui
**Parent:** docs/prds/namespace-ui/prd.md
**Related:** VISION.md (Layer 4), docs/prds/observatory-polish/prd.md

---

## Goals

1. **Use XTDB data** - Read structured messages from database, not parsed log files
2. **Claude Code-style density** - Scannable, expandable, follows the agent's work
3. **Linked tool calls** - TOOL and RESULT visually connected
4. **Clear narrative** - Understand what agent is doing at a glance

---

## Problem Statement

Current Observatory:
- Parses plain-text log files (`logs/agents/{id}.log`)
- Loses structured data (tool calls as parsed EDN strings)
- Rigid columns waste space
- TOOL+RESULT pairing is fragile (relies on log line matching)
- No syntax highlighting in results

We already persist rich structured data to XTDB via `persist-message!`:

```clojure
::tool-calls      ; [{:name "Read" :input {:file_path "..."}}]
::tool-results    ; [{:tool_use_id "..." :content "..."}]
::ai/content      ; message text
::raw-message     ; full SDK message

```

This data should drive the display.

---

## Data Available in XTDB

```clojure
;; Query messages for a session
(xt/q node
  "SELECT * FROM messages
   WHERE session_id = ?
   ORDER BY timestamp"
  session-id)

;; Message entity fields:
{:xt/id            "msg-uuid"
 ::ai/session-id   "ses-xxx"
 ::ai/role         "assistant"    ; or "user", "system"
 ::ai/content      "I'll read..." ; assistant text
 ::ai/timestamp    #inst "..."
 ::tool-calls      [{:id "toolu_xxx"
                     :name "Read"
                     :input {:file_path "/path/to/file"}}]
 ::tool-results    [{:tool_use_id "toolu_xxx"
                     :content "file contents..."}]
 ::raw-message     {...}}         ; full SDK message

```

---

## Design Ideas

### 1. Message-Centric View (not log-line-centric)

Instead of:

```
14:23 | TOOL    | Read  | src/foo.clj
14:23 | RESULT  | Read  | (ns foo...)
14:24 | MESSAGE | asst  | Now I'll edit...
14:24 | TOOL    | Edit  | src/foo.clj

```

Show:

```
▶ 14:23 Read src/foo.clj ✓
    (ns foo
      "docstring...")
    ... 45 more lines

  "Now I'll edit the function to add error handling."

▶ 14:24 Edit src/foo.clj ✓
    @@ -12,3 +12,5 @@
    - (defn old ...)
    + (defn new ...)

```

### 2. Tool Call Components

Each tool call is a collapsible block:
- **Header**: timestamp + tool name + target + status (✓/✗)
- **Collapsed**: 2-4 line preview
- **Expanded**: full input/output with syntax highlighting

### 3. Assistant Messages as Flow Text

Don't put in rigid columns. Show as natural text between tool blocks:

```
"I'll start by reading the file to understand the current implementation."

▶ Read src/seon/web/agents.clj ✓

```

### 4. Syntax Highlighting by Tool

| Tool | Highlight As |
|------|-------------|
| Read | File extension (clj, js, md) |
| Edit | diff |
| Bash | bash |
| Grep | file paths + matches |
| mcp__seon__eval | clojure |

---

## Implementation Phases

### Phase 1: Query XTDB for messages

Replace log file parsing with XTDB query:

```clojure
(defn- load-session-messages [session-id]
  (xt/q node
    "SELECT * FROM messages
     WHERE session_id = ?
     ORDER BY timestamp"
    session-id))

```

### Phase 2: Message renderer

Render based on message type:
- Assistant message → flowing text
- Tool call → collapsible block with preview
- Tool result → attached to tool call

### Phase 3: Collapsible blocks with syntax highlighting

Click to expand, syntax highlight based on tool type.

---

## Files to Modify

| File | Changes |
|------|---------|
| `src/seon/web/agents.clj` | Replace log file read with XTDB query |
| `src/seon/ai/agent/views.clj` | New message-centric renderers |
| Query to write | Get messages by session from XTDB |

---

## Success Criteria

1. Observatory reads from XTDB, not log files
2. Tool calls show as collapsible blocks with preview
3. TOOL+RESULT properly linked (same tool_use_id)
4. Assistant text flows naturally between tool blocks
5. Syntax highlighting works based on tool type

---

## Research Findings (Completed)

Agent analyzed 82 sessions and 3,517 messages. Key findings:

### Actual Data Structure

```clojure
{:xt/id                         "msg-uuid"
 :seon.ai/type                  :message
 :seon.ai/session-id            "ses-uuid"
 :seon.ai/role                  "assistant"|"user"|"system"
 :seon.ai/content               "text content"
 :seon.ai/timestamp             #time/zoned-date-time "..."

 ;; Tool calls (on assistant messages)
 :seon.ai.claude/tool-calls     [{:id "toolu_xxx"
                                   :name "Read"
                                   :input {:file-path "..."}}]

 ;; Tool results (on user messages)
 :seon.ai.claude/tool-results   [{:tool-use-id "toolu_xxx"
                                   :content "result..."}]}

```

### Key Insights

1. **Tool calls and text are in SEPARATE messages** - not combined
2. **Linking via tool IDs** - `:tool-use-id` in results matches `:id` in calls
3. **1,276 tool calls → 1,270 results** - near 1:1 pairing confirmed
4. **Keys use kebab-case** - `:file-path` not `file_path`
5. **Token stats on result messages** - `:seon.ai/input-tokens`, etc.

### Recommended Pairing Function

```clojure
(defn pair-tool-calls-with-results
  "Match tool calls with their results by tool_use_id."
  [messages]
  (let [results-by-id (->> messages
                           (filter :seon.ai.claude/tool-results)
                           (mapcat (fn [msg]
                                     (for [r (:seon.ai.claude/tool-results msg)]
                                       [(:tool-use-id r) r])))
                           (into {}))]
    (map (fn [msg]
           (if-let [calls (:seon.ai.claude/tool-calls msg)]
             (assoc msg :seon.ai.claude/tool-calls
                    (mapv #(assoc % :result (get results-by-id (:id %))) calls))
             msg))
         messages)))

```

### Component Mapping

| Message Type | Has tool-calls? | Has tool-results? | Render As |
|-------------|-----------------|-------------------|-----------|
| System init | No | No | (skip) |
| Assistant text | No | No | Flowing text |
| Tool invocation | Yes | No | Collapsible tool block |
| Tool result | No | Yes | (embedded in tool block) |
| Final result | No | No | Session stats |

---

## COMPLETED (2026-01-28)

### Session Mapping: RESOLVED

The XTDB-based view now works. The `ai_sessions` table exists and contains the mapping:
- `ai_sessions._id` = full session ID (ses-xxx)
- `ai_sessions.seon$ai$agent_session_id` = 4-char hex ID

The query was correct; the issue was server caching.

### Implemented Features

1. **XTDB data source** - Reads structured messages from database, not parsed log files
2. **Session status** - Shows running/done/completed from XTDB session metadata
3. **Expanded by default** - Tool calls show content immediately like Claude Code
4. **Truncated paths** - `/Users/sean/src/seon/docs/foo.md` → `docs/foo.md`
5. **8-line preview** - More context visible in code/results
6. **TOOL+RESULT linked** - Results embedded in tool call blocks via tool_use_id pairing
7. **Collapsible blocks** - Click to collapse/expand individual tool calls
8. **Error highlighting** - Failed tools show ✗ with error background

### Remaining Visual Issues

1. ✅ **Render markdown** - COMPLETE
   - Added markdown-clj v1.12.4 for markdown-to-HTML conversion
   - Built local Tailwind with @tailwindcss/typography plugin
   - Prose classes now properly style headers (h2=20px), bold, code, lists
   - Dark theme prose customization in input.css

2. ✅ **Smart auto-scroll** - COMPLETE
   - MutationObserver-based auto-scroll in agent-detail-skeleton
   - Tracks user scroll position, only auto-scrolls when pinned to bottom (within 150px)
   - Respects user reading history by pausing when scrolled up

3. **TodoWrite as component** - Not raw message, show as checkbox list (future)

4. **Syntax highlighting** - Already working via highlight.js (highlight.min.js CDN)

---

## Phase 1b.11: Local Tailwind Build - ✅ COMPLETE

**Problem:** Tailwind Browser CDN doesn't include the `@tailwindcss/typography` plugin which provides the `prose` classes for styled markdown rendering. Headers, bold, code, lists all render but without visual distinction.

**Solution:** Built Tailwind locally with the typography plugin.

### Tasks

1. **Initialize npm project** (if not exists)

   ```bash
   npm init -y
   npm install -D tailwindcss @tailwindcss/typography
   npx tailwindcss init

   ```

2. **Configure Tailwind** (`tailwind.config.js`)

   ```javascript
   module.exports = {
     content: ['./src/**/*.clj', './resources/**/*.html'],
     theme: {
       extend: {
         // Custom colors from html.clj custom-theme
         colors: {
           base: { 950: '#0d0d0c', 900: '#1a1918', 850: '#252422', 800: '#302e2b', 700: '#3d3a36' },
           text: { 50: '#faf9f7', 200: '#d4d0c8', 400: '#8c8578', 500: '#6b6459' },
           signal: '#f0b429',
           success: '#34d399',
           error: '#f87171',
           // ... rest of theme
         },
         fontFamily: {
           mono: ['JetBrains Mono', 'SF Mono', 'ui-monospace', 'monospace'],
         },
       },
     },
     plugins: [require('@tailwindcss/typography')],
   }

   ```

3. **Create input CSS** (`resources/public/css/input.css`)

   ```css
   @tailwind base;
   @tailwind components;
   @tailwind utilities;

   ```

4. **Build script** (add to `package.json` or `bin/`)

   ```bash
   npx tailwindcss -i resources/public/css/input.css -o resources/public/css/output.css --watch

   ```

5. **Update html.clj** - Replace CDN with local CSS file

   ```clojure
   ;; Remove: tailwind-cdn script
   ;; Add: [:link {:rel "stylesheet" :href "/css/output.css"}]

   ```

6. **Add to .gitignore**

   ```
   node_modules/
   resources/public/css/output.css

   ```

7. **Add build to startup** - Run Tailwind build before/during dev

### Success Criteria

- `prose` classes properly style markdown headers, bold, code, lists
- No CDN dependency for Tailwind
- Build integrates with existing dev workflow
- Theme colors preserved from current implementation

### Implementation Notes (2026-01-28)

Used Tailwind v4 CSS-based configuration (no `tailwind.config.js`):

**Files created:**
- `package.json` - npm project with tailwindcss, @tailwindcss/cli, @tailwindcss/typography
- `resources/public/css/input.css` - Tailwind directives + @theme + prose customization
- `resources/public/css/output.css` - Built CSS (gitignored)

**Files modified:**
- `src/seon/web/html.clj` - Replaced CDN script with local CSS link
- `src/seon/web/routes.clj` - Added static file serving for /css/*
- `.gitignore` - Added node_modules/ and output.css

**Build commands:**

```bash
npm run css:build   # One-time build
npm run css:watch   # Watch mode for development

```

**Key difference from plan:** Tailwind v4 uses `@plugin` directive in CSS instead of JS config:

```css
@import "tailwindcss";
@plugin "@tailwindcss/typography";
@source "../../../src/**/*.clj";

```

---

## Phase 1b.12: Tailwind Watcher as Integrant Component

**Problem:** Shell script approaches to starting Tailwind watcher are unreliable (process gets orphaned, signal handling issues with npm).

**Solution:** Add Tailwind watcher as an Integrant component so it:
- Starts automatically with the system
- Stops cleanly on `(reset)` or system shutdown
- Survives code reloads
- Gets killed properly when JVM exits (child process)

### Implementation

1. **Create component** in `src/seon/web/tailwind.clj`:

   ```clojure
   (ns seon.web.tailwind
     "Tailwind CSS watcher component.
      Spawns tailwindcss --watch as a child process, managed by Integrant.")

   (defmethod ig/init-key :seon.web/tailwind-watcher [_ opts]
     ;; Spawn: node_modules/.bin/tailwindcss -i input.css -o output.css --watch
     ;; Return process handle
     )

   (defmethod ig/halt-key! :seon.web/tailwind-watcher [_ state]
     ;; Kill the process
     )

   ```

2. **Add to system.clj**:

   ```clojure
   :seon.web/tailwind-watcher {:enabled? (not= profile :prod)}

   ```

3. **Process management**:
   - Use `ProcessBuilder` to spawn the process
   - Redirect stdout/stderr to `logs/tailwind.log`
   - On halt: call `.destroy()` or `.destroyForcibly()`

### Behavior

- **Dev mode**: Watcher runs, rebuilds CSS on file changes
- **Prod mode**: Disabled (CSS pre-built during deployment)
- **Reset**: Process killed and restarted (picks up any config changes)
- **pkill JVM**: Child process dies with parent

### Success Criteria

- `./bin/run` starts Tailwind watcher automatically
- `(reset)` in REPL restarts watcher cleanly
- No orphaned processes after shutdown
- CSS rebuilds when `.clj` files or `input.css` change

---

## Phase 1b.13: Consolidate Agent View Rendering to XTDB

**Status:** In Progress
**Problem:** Two separate rendering pipelines exist for agent tool calls - this is confusing and brittle.

| Path | Location | Used When |
|------|----------|-----------|
| **XTDB messages** | `agents.clj` `render-tool-call` | `use-xtdb?` is true |
| **Log file fallback** | `agent-views` multimethod | `use-xtdb?` is false |

**Decision:** Migrate everything to XTDB. The log file fallback is brittle.

### Tasks

1. **Phase 1: Add multimethod dispatch + hover cards**
   - Create multimethods in `agents.clj`: `render-tool-inline`, `render-tool-hover`, `render-tool-expanded`
   - Port hover card helpers from views.clj: `hover-card`, `hover-line`, `hover-code-block`
   - Implement methods for each tool type (REPL, Edit, Read, Grep, Bash, Task)
   - Keep `render-tool-call` as orchestrator that calls multimethods
   - Test: Same visual output, hover cards work

2. **Phase 2: Improve loading/empty states**
   - Show agent metadata from registry (ID, namespace, status, nREPL port) while waiting
   - Show "Waiting for first message..." with pulse animation
   - Show skeleton for content area

3. **Phase 3: Remove log file fallback**
   - Remove `log-line-component`, `paired-log-line-component`, `render-log-item`
   - Remove type filter controls (log-file specific)
   - Remove `use-xtdb?` conditional branches
   - Remove `parse-log-line`, `tail-log-file` helpers

4. **Phase 4: Clean up agent-views.clj**
   - Keep `:agent.log/*` view types for list view rendering
   - Remove `render-tool-html` multimethod (replaced by agents.clj version)
   - Remove log-file specific helpers

5. **Phase 5: Final cleanup**
   - Change "REPL-TEST" back to "REPL"
   - Remove debug logging
   - Run full test suite
   - Commit

### Files to Modify

| File | Action |
|------|--------|
| `src/seon/web/agents.clj` | Port hover cards, remove log file fallback |
| `src/seon/ai/agent/views.clj` | Clean up unused log-file code |

---

## Phase 1b.14: Make launch-agent!! Interruptible

**Status:** Planned
**Problem:** When the orchestrator runs `launch-agent!!` and the MCP call is canceled (user presses Escape), the nREPL thread stays blocked waiting for the agent to complete. This locks up the orchestrator.

### Root Cause Analysis (2026-01-29)

1. `launch-agent!!` blocks on `(async/<!! result-ch)` at line 943 of `claude.clj`
2. When MCP client aborts, the server-side eval continues running
3. The nREPL thread stays blocked until agent completes or is killed
4. New MCP evals may use the same nREPL session, queueing behind the blocked eval

### Solution Design

**Option A: Use interruptible blocking with timeout (Recommended)**
- Replace `async/<!!` with `async/alt!!` that includes a interrupt check
- Use a `future` with `.cancel(true)` support
- When MCP abort comes in, the MCP server can interrupt the eval

**Option B: Always non-blocking with polling**
- `launch-agent!!` starts agent but doesn't block
- Returns immediately with agent handle
- Caller polls for completion
- Less elegant API but inherently interruptible

**Option C: Separate thread pool**
- Run blocking agent waits in a separate thread pool
- MCP evals use main pool
- Prevents blocking main nREPL

### Tasks

1. **Research `clojure.java.process`** - Clojure 1.12 has new process API, may have better cancellation
2. **Add interrupt support to launch-agent!!** - Track thread, support interruption
3. **Update MCP server** - On abort, call interrupt API
4. **Test cancellation** - Verify orchestrator stays responsive after cancel

---

## Phase 1b.15: Agent Visibility Improvements

**Status:** Planned
**Problem:** When an agent is "thinking" (waiting for Anthropic API response), there's no visibility. The UI shows "stuck" after 2 minutes of no log activity, but the agent may be legitimately waiting.

### Issues Identified (2026-01-29)

1. **"Stuck" detection is log-based** - No activity in log = stuck, even if agent is waiting for API
2. **No token streaming** - Token counts only appear in final "result" message
3. **No "thinking" indicator** - Can't tell if agent is working vs truly stuck
4. **TCP monitoring via shell** - We use `lsof` to check connections, should be from Clojure

### Proposed Features

#### 1. "Thinking" Indicator in UI

Show distinct states:
- `● running` - Normal, recent activity
- `● thinking` - No activity but process alive with open TCP connections
- `● stuck` - No activity, no TCP connections, may be deadlocked
- `● completed` - Agent finished

#### 2. Token Streaming (if possible)

The Claude CLI may emit streaming events we're not capturing. Research:
- What events does Claude CLI emit during streaming?
- Can we get partial token counts?
- Can we show a "tokens received" counter?

#### 3. Process Monitoring from Clojure

Replace shell-based monitoring with JVM APIs:
- `ProcessHandle` for process info (Clojure 1.12)
- Java NIO for network connections
- Or use `clojure.java.process` utilities

#### 4. Activity Heartbeat

Add heartbeat from agent process:
- Agent periodically writes timestamp to shared atom/file
- UI checks heartbeat freshness
- More reliable than log activity

### Tasks

1. **Research Claude CLI streaming** - What events are emitted?
2. **Implement process monitoring in Clojure** - Replace `lsof` with JVM APIs
3. **Add "thinking" state to status computation** - Process alive + connections = thinking
4. **Add heartbeat mechanism** - Agent writes periodic activity marker
5. **Update UI** - Show thinking indicator, token progress if available

### Research Questions

- Does Claude CLI emit `content_block_delta` or similar events during streaming?
- Can we monitor TCP connections from JVM without shelling out?
- What's the best way to share state between agent process and orchestrator?

---

## Future Enhancements

1. **Token totals in agent header** - Running total of input/output tokens
2. **Cost estimation during run** - Approximate cost based on tokens so far
3. **Cache hit indicator** - Show when prompt caching is active
4. **Conversation tree view** - Visualize multi-turn conversation structure
