---
type: research
status: completed
tags: [research, archive]
---

# Phase 6 Design: Hook Refactor Architecture

**Date:** 2025-12-29
**Status:** Design Complete
**Author:** Agent (Phase 6 Design Task)

---

## Executive Summary

Move hook logic from `bin/seon-hook` (850 lines of Babashka) into proper Clojure code. The refactor enables:

- Testable code with our test framework
- Malli schemas at all public boundaries
- Unified configuration (no split-brain)
- Better BB<->Clojure communication (EDN, not text parsing)
- Paren repair ported into Seon

**Key simplification:** The "debounce" concept was overengineered. The real need is just **rate limiting reviews** - at most one review per N seconds. This is trivial with XTDB temporal queries.

---

## 1. Namespace Structure

### Domain: `seon.dev.*`

```
src/seon/dev/
├── hook.clj          ; Main entry: process-hook-event!
├── context.clj       ; Edit/review events (XTDB)
├── codebase.clj      ; File introspection, ns mapping
├── verify.clj        ; Test orchestration
├── review.clj        ; AI review + context building
└── repair.clj        ; Delimiter repair (ported)

```

### Responsibilities

| Namespace | What It Does |
|-----------|--------------|
| `seon.dev.hook` | Orchestrate pipeline, single public entry point |
| `seon.dev.context` | Record edits, record reviews, query timing |
| `seon.dev.codebase` | `file->namespace`, `clojure-file?`, read source |
| `seon.dev.verify` | Run unit tests, run gen tests, format results |
| `seon.dev.review` | Build context, call Gemini, format output |
| `seon.dev.repair` | Detect delimiter errors, fix with parinferish |

### Rename: `feedback.clj` -> `context.clj`

Better name for what it does - maintains agent context of edits and reviews.

---

## 2. Review Rate Limiting (Simplified)

### The Actual Requirement

> "I just want a reliable way to limit reviews to not happening more than 1 per minute."

The LLM agent makes many rapid edits. We want to batch them into periodic reviews, not spam Gemini on every edit.

### The Simple Solution

```clojure
(defn should-review?
  "Should we trigger a review right now?

   Returns true if:
   - Last review was more than `interval-seconds` ago (default 60)
   - There are edits since the last review

   That's it. No debounce, no complicated timing."
  [db {:keys [interval-seconds] :or {interval-seconds 60}}]
  (let [last-review (get-last-review-time db)
        last-edit (get-last-edit-time db)
        now (Instant/now)]
    (and
      ;; Have edits to review
      (some? last-edit)
      ;; Either never reviewed, or interval has passed
      (or (nil? last-review)
          (> (seconds-between last-review now) interval-seconds))
      ;; Have edits since last review
      (or (nil? last-review)
          (> (seconds-between last-review last-edit) 0)))))

```

### Timeline Example

```
T+0s:   Edit A   -> should-review? false (never reviewed, but just started)
T+5s:   Edit B   -> should-review? false (still no review, interval not met... wait)

Actually, if never reviewed, we SHOULD review immediately. Let me reconsider:

T+0s:   Edit A   -> should-review? true (never reviewed) -> REVIEW
T+5s:   Edit B   -> should-review? false (reviewed 5s ago, need 60s)
T+30s:  Edit C   -> should-review? false (reviewed 30s ago)
T+65s:  Edit D   -> should-review? true (reviewed 65s ago) -> REVIEW
T+70s:  Edit E   -> should-review? false (reviewed 5s ago)

```

### XTDB Events

Just two event types:

```clojure
;; Edit event - recorded on every file edit
{:xt/id #uuid "..."
 :entity/type :edit-event
 :edit/file "/path/to/file.clj"
 :edit/namespace :seon.foo}
;; _valid_from = when it happened

;; Review event - recorded when review completes
{:xt/id #uuid "..."
 :entity/type :review-event
 :review/files #{"/path/to/a.clj" "/path/to/b.clj"}
 :review/edit-count 5}
;; _valid_from = when review completed

```

### Queries (SQL)

```clojure
(defn get-last-review-time [db]
  (-> (xt/q db "SELECT _valid_from FROM review_event ORDER BY _valid_from DESC LIMIT 1")
      first
      :xt/valid-from))

(defn get-last-edit-time [db]
  (-> (xt/q db "SELECT _valid_from FROM edit_event ORDER BY _valid_from DESC LIMIT 1")
      first
      :xt/valid-from))

(defn edits-since-review [db]
  (let [last-review (get-last-review-time db)]
    (if last-review
      (xt/q db ["SELECT * FROM edit_event WHERE _valid_from > ? ORDER BY _valid_from"
                last-review])
      (xt/q db "SELECT * FROM edit_event ORDER BY _valid_from"))))

```

---

## 3. Communication: BB <-> Clojure

### Problem

Current approach shells out to `clj-nrepl-eval` and parses text. Brittle.

### Solution: Direct nREPL with Bencode

Babashka has `bencode.core`. Use it to talk nREPL directly:

```clojure
;; In bin/seon-hook
(require '[bencode.core :as b])

(defn nrepl-eval [code]
  (with-open [sock (java.net.Socket. "localhost" 7888)]
    (let [out (java.io.BufferedOutputStream. (.getOutputStream sock))
          in (java.io.PushbackInputStream. (.getInputStream sock))
          id (str (random-uuid))]
      (b/write-bencode out {"op" "eval" "code" code "id" id})
      (.flush out)
      (loop [result {}]
        (let [msg (decode-msg (b/read-bencode in))]
          (if (contains? (:status msg) "done")
            (when-let [v (:value result)]
              (edn/read-string v))
            (recur (merge result msg))))))))

```

Benefits:
- EDN in, EDN out
- No text parsing
- No external process spawn

---

## 4. Unified Configuration

### Single Source of Truth: `.claude/seon-hook.edn`

```clojure
{:repair {:enabled true
          :cljfmt true}

 :tests {:unit {:enabled true
                :block-on-fail true
                :timeout-seconds 30}
         :generative {:enabled true
                      :num-tests 10
                      :block-on-fail true}}

 :review {:enabled true
          :interval-seconds 60   ; At most one review per minute
          :max-code-length 12000}}

```

BB hook reads this once, passes to Clojure. No defaults scattered around.

---

## 5. Porting Paren Repair

### What to Port

From `clojure-mcp-light`:
- `delimiter_repair.clj` - Detection (edamame) and repair (parinferish)

### New Namespace: `seon.dev.repair`

```clojure
(ns seon.dev.repair
  (:require [edamame.core :as e]
            [parinferish.core :as parinferish]
            [cljfmt.core :as cljfmt]))

(defn delimiter-error?
  "Returns true if content has unbalanced delimiters."
  [content]
  (try
    (e/parse-string-all content {:all true :read-cond :allow})
    false
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (and (= :edamame/error (:type data))
             (contains? data :edamame/opened-delimiter))))))

(defn repair
  "Attempt to fix delimiter errors. Returns fixed content or nil."
  [content]
  (when (delimiter-error? content)
    (let [result (parinferish/flatten
                   (parinferish/parse content {:mode :indent}))]
      (when-not (delimiter-error? result)
        result))))

(defn repair-and-format
  "Fix delimiters and format. Returns {:success bool :content str}."
  [content {:keys [format?]}]
  (let [fixed (or (repair content) content)
        formatted (if format? (cljfmt/reformat-string fixed) fixed)]
    {:success (not (delimiter-error? formatted))
     :content formatted}))

```

### Dependency to Add

```clojure
;; deps.edn
parinferish/parinferish {:mvn/version "0.8.0"}

```

### No More PreToolUse

With repair in Seon, we only need PostToolUse:
1. Edit happens (file written)
2. Read file, check for delimiter errors
3. If error: repair, write back
4. If unfixable: backup and restore, block
5. Continue with tests and review

---

## 6. Entity Schemas (XTDB)

### `edit_event`

```clojure
{:xt/id #uuid "..."
 :entity/type :edit-event
 :edit/file "/abs/path/to/file.clj"
 :edit/namespace :seon.foo}
;; _valid_from = timestamp

```

### `review_event`

```clojure
{:xt/id #uuid "..."
 :entity/type :review-event
 :review/files #{...}
 :review/edit-count N}
;; _valid_from = when review completed

```

### `function` (existing, unchanged)

```clojure
{:xt/id :seon.foo/bar
 :entity/type :function
 :fn/namespace :seon.foo
 :fn/name :bar
 :fn/schema [...]
 :fn/first-seen #inst "..."}

```

---

## 7. Malli Schemas

Following CONVENTIONS.md exactly.

### `seon.dev.hook`

```clojure
(schema/register! ::hook-event
  [:map
   [:hook_event_name [:enum "PreToolUse" "PostToolUse"]]
   [:tool_name [:enum "Edit" "Write"]]
   [:tool_input {:optional true} :map]
   [:session_id {:optional true} :string]])

(schema/register! ::process-request
  [:map
   [::event ::hook-event]
   [::config :map]])

(schema/register! ::process-response
  [:map
   [::continue {:optional true} :boolean]
   [::decision {:optional true} [:enum "block"]]
   [::reason {:optional true} :string]
   [::feedback {:optional true} [:vector :string]]])

```

### `seon.dev.context`

```clojure
(schema/register! ::review-options
  [:map
   [::interval-seconds {:optional true} [:int {:min 1}]]])

```

---

## 8. Public API

### `seon.dev.hook/process-hook-event!`

The only public entry point:

```clojure
(defn process-hook-event!
  "Process Claude Code hook event.

   Request:
     ::event  - Parsed hook JSON
     ::config - Full config from .claude/seon-hook.edn

   Response (for Claude Code JSON):
     ::continue - true to proceed
     ::decision - \"block\" to stop
     ::reason   - Why blocked
     ::feedback - Messages for additionalContext"
  {:malli/schema [:=> [:cat ::process-request] ::process-response]}
  [{::keys [event config]}]
  ...)

```

### `seon.dev.context`

```clojure
(defn record-edit! [db file-path ns-sym]
  "Record an edit event.")

(defn record-review! [db files]
  "Record a review completion.")

(defn should-review? [db opts]
  "True if interval has passed since last review and there are new edits.")

(defn edits-since-last-review [db]
  "Get all edits since the last review.")

```

---

## 9. Thin Babashka Hook

~80 lines of clean Babashka:

```clojure
#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[bencode.core :as b]
         '[cheshire.core :as json]
         '[clojure.edn :as edn])

(def config-path ".claude/seon-hook.edn")
(def nrepl-port 7888)

(defn load-config []
  (edn/read-string (slurp config-path)))

(defn decode-msg [m]
  ;; bencode bytes -> clojure map
  ...)

(defn nrepl-eval [code]
  ;; Direct nREPL communication
  ...)

(defn -main []
  (try
    (let [config (load-config)
          event (json/parse-string (slurp *in*) true)
          code (format "(seon.dev.hook/process-hook-event!
                          {:seon.dev.hook/event %s
                           :seon.dev.hook/config %s})"
                       (pr-str event)
                       (pr-str config))
          result (nrepl-eval code)
          response (format-response result)]
      (when (seq response)
        (println (json/generate-string response))))
    (catch Exception e
      ;; Never crash
      nil)))

(-main)

```

---

## 10. Migration Plan

### Phase 6a: Create Namespaces

1. `seon.dev.context` - From feedback.clj + simplified timing
2. `seon.dev.codebase` - Extract from hook
3. `seon.dev.verify` - Extract from hook
4. `seon.dev.review` - Extract from hook
5. `seon.dev.repair` - Port from clojure-mcp-light
6. `seon.dev.hook` - New orchestrator

### Phase 6b: Add Dependency

```clojure
parinferish/parinferish {:mvn/version "0.8.0"}

```

### Phase 6c: Replace BB Hook

Write new thin hook, test, update settings.

### Phase 6d: Cleanup

Delete old feedback.clj, old 850-line hook.

### Files

| Action | File |
|--------|------|
| Create | `src/seon/dev/hook.clj` |
| Create | `src/seon/dev/context.clj` |
| Create | `src/seon/dev/codebase.clj` |
| Create | `src/seon/dev/verify.clj` |
| Create | `src/seon/dev/review.clj` |
| Create | `src/seon/dev/repair.clj` |
| Replace | `bin/seon-hook` |
| Modify | `deps.edn` |
| Delete | `src/seon/dev/feedback.clj` |

---

## 11. Testing

### Agent Must:

1. Write tests for each namespace
2. Verify via REPL
3. Document discoveries in `notes.md`
4. Record decisions in `decisions.md`
5. Run full suite: `clj -M:test -m kaocha.runner`

### Key Scenarios

1. **Rate limiting works** - Review triggers after interval, not before
2. **Edits accumulate** - Multiple edits batched into one review
3. **Config flows through** - BB passes config correctly
4. **Errors handled** - Graceful failures, valid JSON always

---

## 12. Summary

Key changes:

1. **Simplified timing** - Just rate limit reviews to 1/minute
2. **Pure functions** - Query XTDB temporal data, no background threads
3. **Unified config** - Single `.claude/seon-hook.edn`
4. **Direct nREPL** - EDN communication, no text parsing
5. **Ported repair** - No external clj-paren-repair dependency
6. **PostToolUse only** - No PreToolUse complexity

The design is much simpler now that we understand the actual requirement: prevent spamming Gemini, not detect "user stopped typing" (which doesn't apply since the agent is doing the typing).
