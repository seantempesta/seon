# PRD: Observatory XTDB-Based Display

**Status:** Complete (Phase 1b.10) - Core XTDB view working
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

### Remaining Visual Issues (Future Work)

1. **Render markdown** - Assistant text with `##`, `**`, lists should render as HTML
   - Use a lightweight markdown-to-HTML converter (clj-markdown or similar)
   - Apply to `::ai/content` in assistant messages

2. **Smart auto-scroll** - Follow new content without fighting user
   - Auto-scroll to bottom when user is near bottom
   - Stop auto-scrolling if user scrolls up to read history
   - Resume auto-scroll when user scrolls back to bottom
   - Consider a "Jump to latest" button when not at bottom

3. **TodoWrite as component** - Not raw message, show as checkbox list

4. **Syntax highlighting** - Add highlight.js for code blocks
