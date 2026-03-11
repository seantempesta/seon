# Live Widgets Research

**Date:** 2026-01-20
**Agent:** ade549a (research agent)

---

## 1. Datastar Push vs Polling

**Can Datastar push updates when events happen (vs polling)?**

**Yes.** The Datastar SDK is designed for server-initiated push updates.

**Pattern for server-initiated updates:**

Store SSE generator references when connections open, then push to them from anywhere.

From `reference-code/datastar-clojure/src/dev/examples/broadcast_http_kit.clj`:

```clojure
;; 1. Store connections when they open
(defonce !conns (atom #{}))

(defn long-connection [req]
  (->sse-response req
    {:on-open
     (fn [sse]
       (swap! !conns conj sse)  ; <-- Store the SSE generator
       (d*/console-log! sse "'connected'"))
     :on-close
     (fn on-close [sse status-code]
       (swap! !conns disj sse))}))  ; <-- Remove on disconnect

;; 2. Push to all connections from anywhere
(defn broadcast-number! [n]
  (doseq [conn @!conns]
    (try
      (d*/console-log! conn (str "n: " n))  ; <-- Push to each client
      (catch Exception e
        (println "Error: " e)))))
```

**Available push methods (from SDK api.clj):**

- `(d*/patch-elements! sse "<div>HTML</div>" opts)` - Merge HTML fragments
- `(d*/patch-signals! sse {:key "value"} opts)` - Update client-side signals
- `(d*/execute-script! sse "console.log('hi')" opts)` - Run JavaScript
- `(d*/remove-element! sse "#element-id" opts)` - Remove elements

**Selectors and modes for `patch-elements!`:**

```clojure
{d*/selector "#target-element"    ; CSS selector
 d*/patch-mode d*/pm-outer}       ; :outer, :inner, :append, :prepend, :before, :after, :replace, :remove
```

**How Seon currently does it (`src/seon/web/sse.clj`):**

Seon uses a **broadcast-all pattern** via `core.async/mult`:

```clojure
;; Broadcast to ALL clients
(defn refresh-all! [& _opts]
  (when-let [<refresh-ch @refresh-ch_]
    (a/>!! <refresh-ch :refresh-event)))  ; <-- Notifies all tapped channels
```

Each SSE handler taps the mult and re-renders its view when notified. This is **polling-style semantics** (signal to re-render) rather than **targeted push** (send specific content to specific clients).

**To push to a specific client:**

```clojure
(defonce agent-connections (atom {}))  ; {agent-id -> sse-gen}

;; On connect
(fn [sse]
  (let [agent-id (get-agent-id req)]
    (swap! agent-connections assoc agent-id sse)))

;; Push to specific agent
(defn push-to-agent! [agent-id html]
  (when-let [sse (get @agent-connections agent-id)]
    (d*/patch-elements! sse html {d*/selector "#agent-status"})))
```

---

## 2. TodoWrite Tool Output

**What does the hook receive when TodoWrite is called?**

The hook receives a JSON payload on stdin with structure:

```json
{
  "hook_event_name": "PostToolUse",
  "tool_name": "TodoWrite",
  "tool_input": {
    "todos": [
      {"content": "...", "status": "in_progress", "activeForm": "..."},
      {"content": "...", "status": "pending", "activeForm": "..."}
    ]
  },
  "session_id": "..."
}
```

**Verified from `logs/hook-debug.log`:**

```
tool=TodoWrite | session=8d219ee9-ecce-4736-865c-93642c3a5617
```

**And from agent log (e84d):**

```clojure
{:todos [{:content "Research proper Clojure hot reload patterns..."
          :status "in_progress"
          :activeForm "Researching Clojure hot reload patterns"}
         {:content "Fix Datastar expand/collapse for log lines"
          :status "pending"
          :activeForm "Fixing Datastar expand/collapse"}
         ...]}
```

**Current limitation:** The hook only handles Edit and Write tools (see `hook.clj` line 47-49):

```clojure
(schema/register! ::tool-name
                  [:enum {:description "The tool that triggered the event"}
                   "Edit" "Write"])
```

**To capture TodoWrite:**

1. Add "TodoWrite" to the `::tool-name` enum
2. Extract `:todos` from `tool_input`
3. Store to XTDB as `todo_event` table

---

## 3. MCP Eval Timeout Analysis

**How does eval handle timeouts?**

From `bin/mcp-server` (lines 42, 117, 193-248):

**Yes, there is a timeout mechanism:**

```clojure
(def default-timeout-ms 30000)  ;; 30 second default
(def connect-timeout-ms 5000)   ;; 5 second connection timeout
```

**The timeout implementation:**

```clojure
(defn nrepl-eval [port code timeout-ms nrepl-session-id]
  (let [timeout-ms (or timeout-ms default-timeout-ms)
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (try
      (with-open [sock (connect-with-timeout "localhost" port connect-timeout-ms)]
        ;; ... eval loop ...
        (loop [result {...}]
          (let [remaining (- deadline (System/currentTimeMillis))]
            (if (<= remaining 0)
              ;; Total timeout exceeded
              {:err (str "Timeout: evaluation took longer than " timeout-ms "ms")
               :ex "timeout"}
              (do
                (.setSoTimeout sock (int (min remaining 5000)))
                ;; ... read response ...)))))
```

**What happens if eval hangs?**

1. **Connection timeout (5s):** If nREPL can't be reached within 5 seconds, fails with connection error
2. **Eval timeout (30s default):** If eval doesn't complete within deadline, returns timeout error
3. **But the eval thread on nREPL continues running!**

**The problem:** Timeout only applies to **waiting for responses**. If the REPL is truly blocked (infinite loop, deadlock), the MCP server will return a timeout error but the **eval continues running**.

**Interrupt capability exists (lines 438-462):**

```clojure
(defn execute-interrupt-eval [{:keys [session_id]}]
  (if-let [info (get-session-info session_id)]
    (let [port (:port info)
          nrepl-session-id (:nrepl-session-id info)]
      (if nrepl-session-id
        (let [result (nrepl-interrupt port nrepl-session-id)]
          ;; ... return result ...)
        {:content [...] :text "Session has no persistent nREPL session ID..."}))))
```

**Fix recommendation:**

Add automatic interrupt after timeout:

```clojure
;; After timeout, also send interrupt
(when (= (:ex result) "timeout")
  (nrepl-interrupt port nrepl-session-id))
```

---

## Summary

| Question | Answer |
|----------|--------|
| **Datastar push** | Yes, store `sse-gen` references and call `patch-elements!` directly. Seon currently uses broadcast-all pattern. |
| **TodoWrite extraction** | Full todo list available in `tool_input.todos`. Need to add "TodoWrite" to hook enum. |
| **MCP eval timeout** | 30s default timeout exists and works, but hung eval keeps running. Add auto-interrupt on timeout. |
