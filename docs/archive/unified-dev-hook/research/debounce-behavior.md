# Research: Gemini Review Debounce Behavior

**Date:** 2025-12-29
**Status:** Research Complete

---

## User's Desired Behavior

1. **If no recent edit** - Immediately trigger a review (don't wait)
2. **After a review completes** - Wait at least 30-60 seconds before the next review
3. **During the wait** - Accumulate edits that haven't been reviewed yet
4. **When reviewing** - Send ALL accumulated files + their test files to Gemini for a batch review
5. **Review current code** - So if issues were already fixed, they won't be flagged on stale code

---

## Current Implementation Summary

### Data Model (XTDB)

**Entity type: `:pending-edit`**

```clojure
{:xt/id #uuid "..."
 :entity/type :pending-edit
 :edit/file "/path/to/file.clj"
 :edit/namespace :seon.foo
 :edit/timestamp #xt/zdt "2025-12-29T..."
 :edit/code-diff nil  ;; Not currently used
 :edit/new-functions #{}}
```

Each edit creates a new `:pending-edit` entity. When a review triggers, ALL pending edits are cleared.

### Key Functions in `seon.dev.feedback`

| Function | Purpose |
|----------|---------|
| `record-pending-edit!` | Store a new pending edit with current timestamp |
| `pending-edits` | Query all pending edits, ordered oldest-first |
| `oldest-pending-edit-age` | Seconds since the oldest pending edit |
| `should-trigger-review?` | Returns true if oldest pending edit age > debounce threshold |
| `pending-edits-summary` | Aggregate info: files, namespaces, new-fns, count, age |
| `clear-pending-edits!` | Delete all pending-edit entities after review |

### Key Functions in `bin/seon-hook`

| Function | Purpose |
|----------|---------|
| `stage-record-pending-edit` | Records each edit via REPL call |
| `stage-should-review?` | Checks if debounce timer expired |
| `stage-get-pending-summary` | Gets accumulated files for batch review |
| `stage-batch-review` | Sends files + test files to Gemini |
| `stage-clear-pending` | Clears pending after review |

### Current Debounce Logic

```
1. Every file edit: record-pending-edit! (always)
2. After edit: should-trigger-review? checks if oldest edit > N seconds
3. If true:
   a. Get all pending files
   b. Read current code from disk (fresh, not stale)
   c. Send to Gemini
   d. Clear all pending edits
```

**The debounce checks "oldest pending edit age"** - NOT "time since last review".

---

## REPL Experiments

### Experiment 1: What happens with no pending edits?

```clojure
;; Clear pending edits
(clear-pending-edits! (user/xtdb-node))

;; Check if review should trigger
(should-trigger-review? (user/xtdb-node) 5)
;; => nil (not true, not false - nil because no age to compare)
```

**Finding:** When there are no pending edits, `should-trigger-review?` returns `nil` (falsy), so no review triggers. This is correct - nothing to review.

### Experiment 2: Immediate edit after review

After a review clears pending edits:

```clojure
;; First edit comes in
(record-pending-edit! node "/path/a.clj" 'seon.a {})
;; timestamp = now

;; Immediately check
(oldest-pending-edit-age node)
;; => 0.123 (fractions of a second)

(should-trigger-review? node 30)
;; => false (0.123 < 30)
```

**Finding:** New edits after review must wait the full debounce period again.

### Experiment 3: Stale pending edits

```clojure
;; Pending edit from 5 minutes ago
(oldest-pending-edit-age node)
;; => 329.078 seconds (~5.5 minutes)

(should-trigger-review? node 30)
;; => true
```

**Finding:** Very old pending edits will always trigger review, which is correct.

---

## Gap Analysis

| Desired Behavior | Current Behavior | Gap? |
|-----------------|------------------|------|
| If no recent edit -> immediate review | Checks if oldest pending > threshold | **YES** - No concept of "time since last review" |
| After review -> wait 30-60s | Clears pending, new edits start at age=0 | Partially works but not quite right |
| Accumulate edits during wait | Each edit creates pending-edit entity | Works correctly |
| Batch review all accumulated | Gets all pending files, reads current code | Works correctly |
| Review current code (not stale) | Reads files from disk at review time | Works correctly |

### The Core Gap

**Current logic:**
- Review triggers when `oldest-pending-edit-age > debounce-seconds`
- This means: "wait N seconds after FIRST edit before reviewing"

**Desired logic:**
- Review triggers when `time-since-last-review > debounce-seconds` (AND there are pending edits)
- This means: "wait N seconds after LAST REVIEW before reviewing again"

**Practical difference:**

| Scenario | Current | Desired |
|----------|---------|---------|
| First edit ever | Wait 30s | Immediate (no recent review) |
| Edit 1s after previous review | Wait 30s from edit | Wait 29s (30s from review) |
| Edit 60s after previous review | Wait 30s from edit | Immediate (>30s since review) |
| Rapid edits (every 2s for 40s) | Review at 30s | Review at 30s |

---

## Recommended Changes

### 1. Track Last Review Timestamp

Add a singleton entity to XTDB tracking when the last review completed:

```clojure
{:xt/id :seon.dev/review-state
 :entity/type :review-state
 :review/last-completed #inst "2025-12-29T..."}
```

### 2. New Function: `time-since-last-review`

```clojure
(defn time-since-last-review
  "Get seconds since last review completed, or nil if never reviewed."
  [node]
  (let [result (first (node/xtql-query
                       node
                       '(from :review-state [{:xt/id :seon.dev/review-state}
                                             review/last-completed])))]
    (when-let [ts (:review/last-completed result)]
      (let [then (if (instance? Instant ts)
                   (.toEpochMilli ts)
                   (.toEpochMilli (.toInstant ts)))
            now (.toEpochMilli (Instant/now))]
        (/ (- now then) 1000.0)))))
```

### 3. New Function: `record-review-completed!`

```clojure
(defn record-review-completed!
  "Record that a review just completed."
  [node]
  (node/execute-tx! node
    [[:put-docs :review-state
      {:xt/id :seon.dev/review-state
       :entity/type :review-state
       :review/last-completed (Instant/now)}]]))
```

### 4. Update `should-trigger-review?`

```clojure
(defn should-trigger-review?
  "Check if a review should trigger.

   Returns true if:
   - There are pending edits AND
   - Either:
     a) Never reviewed before (time-since-last-review is nil), OR
     b) At least debounce-seconds have passed since last review"
  ([node] (should-trigger-review? node review-debounce-seconds))
  ([node debounce-secs]
   (let [has-pending (seq (pending-edits node))
         time-since (time-since-last-review node)]
     (when has-pending
       (or (nil? time-since)           ;; Never reviewed -> immediate
           (> time-since debounce-secs)))))) ;; Enough time passed
```

### 5. Update `bin/seon-hook` to Record Completion

After `stage-batch-review` and `stage-clear-pending`, add:

```clojure
;; In the batch review block:
(let [review-text (stage-batch-review summary test-result)]
  (stage-clear-pending)
  (stage-record-review-completed)  ;; NEW: track completion time
  (add-feedback! (str "Gemini: " (truncate review-text max-summary-length))))
```

Add the new stage function:

```clojure
(defn stage-record-review-completed
  "Record that review just completed."
  []
  (nrepl-eval "(seon.dev.feedback/record-review-completed! (user/xtdb-node))"))
```

---

## Alternative Approaches Considered

### A: Use oldest-pending-edit-age directly (current approach)

**Pros:** Simple, no additional state
**Cons:** Doesn't match desired "time since last review" semantics

### B: Track last review time in-memory (atom)

**Pros:** Simple, no XTDB overhead
**Cons:** Lost on restart, not visible in REPL for debugging

### C: Track in config file

**Pros:** Persists across restarts
**Cons:** File I/O, not temporal, can't query history

### D: Track in XTDB (recommended)

**Pros:**
- Temporal history (when did reviews happen?)
- Queryable from REPL for debugging
- Consistent with "all state in XTDB" architecture
- Survives restarts

**Cons:**
- Extra entity type
- XTDB round-trip on every check

The XTDB round-trip is negligible (~1-2ms) compared to the review itself (~10-15s).

---

## Testing Plan

After implementation:

1. **No pending edits, never reviewed** -> No review (nothing to review)
2. **First edit, never reviewed** -> Immediate review
3. **Edit 5s after review (debounce=30)** -> No review (wait 25 more seconds)
4. **Edit 35s after review (debounce=30)** -> Immediate review
5. **Multiple rapid edits** -> Batch all accumulated when threshold passes
6. **Server restart** -> Review state preserved in XTDB

---

## Implementation Estimate

| Change | Effort |
|--------|--------|
| Add `review-state` entity functions | 15 min |
| Update `should-trigger-review?` | 10 min |
| Add `stage-record-review-completed` to hook | 5 min |
| Wire it up in batch review flow | 5 min |
| Test all scenarios | 20 min |
| **Total** | ~1 hour |

---

## Files to Modify

1. **`src/seon/dev/feedback.clj`**
   - Add `time-since-last-review`
   - Add `record-review-completed!`
   - Update `should-trigger-review?`

2. **`bin/seon-hook`**
   - Add `stage-record-review-completed`
   - Call it after successful batch review
