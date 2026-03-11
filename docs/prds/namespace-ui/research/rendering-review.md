---
type: research
status: completed
tags: [prd, research, web]
---
# Architecture Review: Multi-Tier Rendering System

**Reviewer:** Principal Engineer Review

---

## Executive Summary

The proposed architecture is **overengineered**. It creates a parallel rendering system (separate registry, four multimethods per format, metadata-based dispatch) when the existing `seon.ns.view` already provides exactly the dispatch mechanism needed. The research documents answer good questions but arrive at solutions that duplicate existing infrastructure. The simpler path: extend what exists with progressive disclosure patterns, not a new rendering subsystem.

---

## 1. What's Good

### 1.1 Problem Identification is Correct

The problems are real and worth solving:

- Tool IDs like `toolu_014BHfdp9mzEGzddcQx6nJq5` are useless noise
- UTC timestamps waste horizontal space
- No syntax highlighting for code
- Single-tier display (no hover/expand)

### 1.2 Type-via-Metadata Pattern

The `typed` helper that attaches `:seon/view` metadata is clean:

```clojure
(defn typed [{::keys [view-type value]}]
  (vary-meta value assoc :seon/view view-type))
```

This is already implemented in `seon.ns.view` and working.

### 1.3 Tool-Specific Renderers

The `render-tool-html` multimethod in `views.clj` is the right pattern - a single dispatch point for tool-specific rendering. It exists and works:

```clojure
(defmulti render-tool-html
  (fn [tool-name _parsed-input _raw-input] tool-name))

(defmethod render-tool-html "Edit" [_ parsed _]
  ;; edit-specific rendering
  )
```

### 1.4 For-AI Recognition

The need for token-efficient AI representations is valid. The `:ai` format in `seon.ns.view/render*` already handles this.

---

## 2. Red Flags

### 2.1 Duplicate Dispatch Infrastructure

**The proposal creates four new multimethods:**

```clojure
(defmulti render-inline schema-of)
(defmulti render-hover schema-of)
(defmulti render-full schema-of)
(defmulti render-ai schema-of)
```

**But `seon.ns.view` already has:**

```clojure
(defmulti render* (fn [value format] [format (extract-view-type value)]))
```

This is the same thing with a different shape. The existing system dispatches on `[format view-type]` which gives you `[:html :agent.log/tool]`, `[:ai :agent.log/tool]`, etc.

**Red flag:** Why create a parallel system instead of extending the existing one?

### 2.2 Separate Registry Recommendation

From `malli-render-research.md`:

> **Recommendation:** Start with **Approach 2 (Separate Registry)** for simplicity

```clojure
(defonce *render-registry (atom {}))

(defn register-renderer! [schema-key render-map & {:keys [inherit]}]
  ...)
```

**Red flag:** Clojure already has a registry for dispatch - multimethods. The `defmethod` mechanism IS the registry. Creating an atom-based registry duplicates what `defmethod` provides with worse ergonomics (no hierarchies, no `prefer-method`, no `methods` introspection).

### 2.3 Tier Proliferation

The proposal introduces three HTML tiers: `:inline`, `:hover`, `:full`.

Looking at Reveal and Portal - neither has this distinction. They have:

- **Reveal:** One streaming format, client controls expansion
- **Portal:** One presentation, navigation reveals more
- **Clerk:** One viewer per type, `with-viewer` for customization

**Red flag:** The three-tier model assumes server-side rendering of different views. But hover cards and modals are **UI state**, not data representation. A single HTML render with progressive disclosure (CSS/JS) is simpler and more maintainable.

### 2.4 Schema Properties vs Registry False Dilemma

From `malli-render-research.md`:

> **Try both in Phase 1b.2, pick winner.**

This is analysis paralysis. The existing code uses multimethods with `[format view-type]` dispatch. That's the winner. It's already implemented, tested, and working.

### 2.5 Structured Event Logging (1b.4)

> **Modify:** `seon.ai.agent.log` to emit structured events (not strings).

The log files currently contain parseable text. The proposal wants to change them to structured events. But the current `parse-tool-input` in `views.clj` already extracts structure from the text:

```clojure
(defn parse-tool-input [input-str]
  (when (and input-str (string? input-str))
    (try
      (let [s (str/trim input-str)
            stripped (if (and (str/starts-with? s "\"") ...)
                       (subs s 1 (dec (count s)))
                       s)]
        (when (str/starts-with? stripped "{")
          (edn/read-string stripped)))
      (catch Exception _ nil))))
```

**Red flag:** Changing the log format is high risk for low reward. The current format works. Derive structure at read time, not write time.

---

## 3. Prior Art We're Ignoring

### 3.1 Reveal's Streaming Architecture

Reveal uses a single `defstream` macro that produces one representation:

```clojure
(defstream IPersistentMap [m]
  (horizontal
    (raw-string "{" {:fill :object})
    (entries m)
    (raw-string "}" {:fill :object})))
```

Then the **client** decides how to display it - collapsed, expanded, in a popup. The server doesn't need to know about "hover" vs "full".

**Lesson:** One render function per type. UI state (expanded/collapsed) is client-side.

### 3.2 Clerk's Viewer Selection

Clerk viewers are maps with `:pred` for matching and `:render-fn` for display:

```clojure
{:pred map?
 :render-fn '(fn [m] [:div.map ...])}
```

Viewers compose via `with-viewer` and `with-viewers`. There's no "inline vs hover vs full" - there's ONE viewer per type.

**Lesson:** Don't multiply rendering paths. One canonical render, let the UI framework handle presentation variants.

### 3.3 CSS-Based Progressive Disclosure

The current code already uses `<details>` for expand/collapse:

```clojure
[:details {:class "..." :data-preserve-attr "open"}
 [:summary "preview"]
 [:div "full content"]]
```

This is the right pattern. Hover cards are just CSS:

```css
.log-line { position: relative; }
.log-line:hover .hover-card { display: block; }
.hover-card { display: none; position: absolute; ... }
```

No server-side "hover format" needed.

---

## 4. Simpler Alternative

### 4.1 Extend Existing View System

Instead of creating `seon.render`, extend `seon.ns.view`:

```clojure
;; Already exists:
(defmethod render* [:html :agent.log/tool] [entry _format]
  ...)

;; Just add more formats to existing dispatch:
(defmethod render* [:summary :agent.log/tool] [entry _format]
  ;; One-line summary for inline display
  )
```

If you want a "summary" format distinct from `:html`, add it as another format key, not a separate multimethod.

### 4.2 Progressive Disclosure in HTML

Render once with all tiers embedded:

```clojure
(defmethod view/render* [:html :agent.log/edit]
  [entry _]
  (let [{:keys [file-path old-string new-string]} (parse-edit entry)]
    [:div {:class "log-line group"}
     ;; Always visible - inline summary
     [:span {:class "inline-summary"}
      [:span.tool "Edit"]
      [:span.file (basename file-path)]
      [:span.stats (diff-stats old-string new-string)]]

     ;; Hover card - shown on :hover via CSS
     [:div {:class "hover-card hidden group-hover:block"}
      [:pre.diff (render-diff old-string new-string)]]

     ;; Click to expand - full detail
     [:details
      [:summary {:class "sr-only"} "expand"]
      [:div.full-view
       (render-side-by-side-diff old-string new-string)]]]))
```

CSS handles visibility:

```css
.hover-card { @apply hidden absolute z-10 ...; }
.group:hover .hover-card { @apply block; }
```

**One render. Three visibility states. Zero server changes.**

### 4.3 Fix the Actual Problems

**Problem 1: Verbose tool IDs**

Current RESULT lines show `toolu_014BHfdp9mzEGzddcQx6nJq5`. Fix it in `log-sdk-message!`:

```clojure
;; Instead of logging the tool_use_id as tool-name, track a mapping:
(defn- tool-name-for-id [tool-use-id assistant-msg]
  ;; Find the tool_use block with matching id, return its :name
  (->> (:content assistant-msg)
       (filter #(and (= "tool_use" (:type %))
                     (= tool-use-id (:id %))))
       first
       :name))
```

**Problem 2: UTC timestamps**

Fix in the view layer, not the logging layer:

```clojure
(defn- format-local-time [iso-timestamp]
  ;; "2026-01-23T14:23:20Z" -> "14:23:20" (local)
  (let [inst (Instant/parse iso-timestamp)
        local (.atZone inst (ZoneId/systemDefault))]
    (str (.getHour local) ":"
         (format "%02d" (.getMinute local)) ":"
         (format "%02d" (.getSecond local)))))
```

**Problem 3: No syntax highlighting**

Add highlight.js to the page (already recommended in PRD) and apply language classes:

```clojure
[:pre {:class "language-clojure"} code-content]
```

**Problem 4: TOOL+RESULT pairing**

This is a view concern, not a data model change. When rendering the log, group consecutive TOOL/RESULT pairs:

```clojure
(defn- pair-tool-results [log-lines]
  ;; Group TOOL line with following RESULT that shares the tool-use-id
  ;; Return seq of {:type :tool-with-result :tool ... :result ...}
  )
```

### 4.4 Keep It Data-Oriented

The proposal wants to change logging to emit structured events. Don't.

The log file is a human-readable artifact. Parsing happens at read time:

```clojure
(defn parse-log-line [line]
  (let [[timestamp _ type & rest] (str/split line #" \| ")]
    (case (str/trim type)
      "TOOL" (parse-tool-line timestamp rest)
      "RESULT" (parse-result-line timestamp rest)
      ...)))
```

This is what `seon.ai.agent` already does. Keep the log format stable.

---

## 5. Recommendation

### Do Not Build

1. Do not create `seon.render` namespace with separate multimethods
2. Do not create a parallel registry for render functions
3. Do not change the log format to structured events
4. Do not add inline/hover/full as separate server-side formats

### Do Build

1. **Fix RESULT tool names** - Track tool_use_id -> tool_name mapping in the reader loop, use it when logging RESULT
2. **Add local timestamps** - Format in view layer, not log layer
3. **Add hover cards** - CSS-only, using Tailwind's `group-hover`
4. **Add highlight.js** - CDN include in `html.clj`
5. **Expand render-tool-html coverage** - Add missing tool types to existing multimethod

### Implementation Order

1. **Week 1:** Fix RESULT tool names (log.clj change) + local timestamps (views.clj change)
2. **Week 2:** Add hover cards via CSS (views.clj + html.clj CSS)
3. **Week 3:** Add highlight.js, syntax highlighting for code blocks
4. **Week 4:** Expand render-tool-html for remaining tools, polish

### Files to Modify

| File | Changes |
|------|---------|
| `src/seon/ai/agent/log.clj` | Track tool_use_id -> name mapping |
| `src/seon/ai/agent/views.clj` | Local timestamps, hover card markup |
| `src/seon/web/html.clj` | highlight.js CDN, hover card CSS |

### Files NOT to Create

- `src/seon/render.clj` - Not needed
- `src/seon/render/tools.clj` - Not needed
- `src/seon/web/modals.clj` - Use `<details>` instead

---

## 6. Closing Thoughts

The research documents are thorough and ask good questions. But they arrive at enterprise-architecture solutions for problems that have simpler answers.

Clojure's strength is composing simple things. The existing `view/render*` multimethod with `[format view-type]` dispatch IS the rendering system. Extend it. Don't replace it.

The three-tier (inline/hover/full) distinction is a UI concern, not a data representation concern. One HTML render with CSS visibility states is simpler, faster to implement, and easier to maintain.

When in doubt: can we solve this with CSS? If yes, do that.
