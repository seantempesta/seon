# Research: Edit/Review Tracking Data Model Redesign

**Date:** 2025-12-29
**Status:** Research Complete - Ready for Implementation

---

## Executive Summary

The current "pending edits" model is conceptually flawed: it treats completed edits as transient state and deletes them after review. This research proposes a simpler **bounded window** approach where:
- Edit events are recorded but only recent ones matter
- A singleton tracks when the last review happened
- "What needs review" = recent edits since last review (bounded by time window)

Key safety feature: Even if reviews stop happening, we only ever look at a bounded time window of edits, preventing unbounded accumulation.

---

## Problem Statement

### Current Model Issues

1. **Semantic mismatch**: "Pending edits" implies something unfinished, but edits are already complete.

2. **Lost history**: After review, pending edits are deleted. No history of what was reviewed.

3. **Unbounded accumulation risk**: If reviews stop (bug, API down), pending edits could accumulate forever.

4. **Confusing timing**: Current debounce checks "time since oldest pending edit", not "time since last review".

### Safety Concern (User Raised)

> "If a bug happens and reviews aren't happening, edits will stack up. Then when it works again, we'll try sending hundreds of files, breaking the system."

**This is a critical design requirement.** The model must be bounded and resilient.

---

## Proposed Model: Bounded Window

### Core Principles

1. **Edit events are immutable facts** - Record them, never delete them
2. **Review state is a singleton** - Track when the last review happened
3. **Bounded queries** - Only look at edits in a recent time window (e.g., last 5 minutes)
4. **Graceful degradation** - If reviews stop, we just review what's recent, not everything

### Entity Types

#### 1. Edit Event (Immutable Fact)

```clojure
{:xt/id #uuid "..."
 :entity/type :edit-event
 :edit/file "/path/to/file.clj"
 :edit/namespace :seon.foo
 :edit/new-functions #{:bar}}

;; valid-time = when the edit happened (set by XTDB)

```

**Kept simple:**
- No `:edit/timestamp` - use XTDB's `xt/valid-from`
- Never deleted - but we only query recent ones
- Optional: `:edit/content-hash` for dedup (future optimization)

#### 2. Review State (Singleton)

```clojure
{:xt/id :seon.dev/review-state
 :entity/type :review-state
 :review/last-completed #inst "2025-12-29T10:00:00Z"}

```

**Purpose:**
- Single entity, always exists (upserted)
- Tells us "when did the last review finish?"
- Used for cooldown calculation

**Why not track watermark?** Simpler. We just need "time since last review" for cooldown. The "what to review" is always "recent edits in the window."

---

## Bounded Window Query

### The Key Insight

Instead of "all edits since last review watermark" (unbounded), we use:

> "Recent edits (last N minutes) that happened after the last review"

This is **doubly bounded**:
1. Time window limits how far back we look
2. Last review time filters out already-reviewed edits

### Query Logic

```clojure
(defn recent-unreviewed-edits
  "Get edits from the recent window that haven't been reviewed.

  Always bounded by:
  1. lookback-minutes - Only look at last N minutes of edits
  2. last-review-time - Only include edits after last review

  Even if no review has ever happened, we only get recent edits."
  [node {:keys [lookback-minutes] :or {lookback-minutes 5}}]
  (let [now (Instant/now)
        window-start (.minus now (Duration/ofMinutes lookback-minutes))
        last-review (get-last-review-time node)
        ;; Use whichever is more recent: window start or last review
        cutoff (if (and last-review (.isAfter last-review window-start))
                 last-review
                 window-start)]
    (query-edits-after node cutoff)))

```

### Safety Scenarios

| Scenario | What Happens |
|----------|--------------|
| Reviews working normally | Query returns edits since last review (usually a few files) |
| Reviews stopped for 1 hour | Query returns only last 5 minutes of edits (bounded) |
| Reviews never happened | Query returns only last 5 minutes of edits (bounded) |
| High edit volume | Query returns only last 5 minutes, capped at reasonable count |

---

## Timeline & State Transitions

```
Time ─────────────────────────────────────────────────────────────►

T1: Edit(foo.clj)
T2: Edit(bar.clj)
T3: Review completes     ─── review-state updated to T3

T4: Edit(foo.clj)
T5: Edit(baz.clj)

Query at T5 with 5-min window:
  - Window start: T5 - 5min
  - Last review: T3
  - Cutoff: max(window-start, T3) = T3
  - Returns: edits at T4, T5 (foo.clj, baz.clj)

─── Suppose reviews break for 2 hours ───

T6: Edit(a.clj)
T7: Edit(b.clj)
... many more edits ...
T100: Edit(z.clj)

Query at T100 with 5-min window:
  - Window start: T100 - 5min
  - Last review: T3 (stale)
  - Cutoff: max(window-start, T3) = window-start (window wins)
  - Returns: only edits in last 5 minutes (bounded!)

```

---

## Configuration

```clojure
;; .claude/seon-hook.edn
{:gemini {:enabled true
          :debounce-seconds 5       ; Quiet period after last edit
          :cooldown-seconds 30      ; Minimum time between reviews
          :lookback-minutes 5}}     ; How far back to look for edits

```

| Config | Purpose | Default |
|--------|---------|---------|
| `debounce-seconds` | Wait for user to stop typing | 5 |
| `cooldown-seconds` | Wait between reviews | 30 |
| `lookback-minutes` | Max edit history to consider | 5 |

---

## Trigger Logic

```clojure
(defn should-trigger-review?
  "Determine if a review should trigger now.

  Returns true if ALL conditions met:
  1. There are unreviewed edits in the recent window
  2. User has stopped typing (debounce met)
  3. Enough time since last review (cooldown met)

  Args:
    node - XTDB node
    opts - {:debounce-seconds 5
            :cooldown-seconds 30
            :lookback-minutes 5}"
  [node opts]
  (let [{:keys [debounce-seconds cooldown-seconds lookback-minutes]
         :or {debounce-seconds 5 cooldown-seconds 30 lookback-minutes 5}} opts
        now (Instant/now)
        last-edit-time (get-last-edit-time node)
        last-review-time (get-last-review-time node)
        recent-edits (recent-unreviewed-edits node {:lookback-minutes lookback-minutes})]

    (and
      ;; 1. Have something to review
      (seq recent-edits)

      ;; 2. User stopped typing (debounce)
      (or (nil? last-edit-time)
          (> (seconds-since last-edit-time now) debounce-seconds))

      ;; 3. Not reviewing too frequently (cooldown)
      (or (nil? last-review-time)
          (> (seconds-since last-review-time now) cooldown-seconds)))))

```

---

## Function Signatures

### Recording Events

```clojure
(defn record-edit!
  "Record an edit event. Idempotent if same file edited multiple times quickly.

  Uses XTDB valid-time for timestamp (automatic).

  Args:
    node - XTDB node
    file-path - Absolute path to edited file
    ns-sym - Namespace symbol (e.g., 'seon.foo)
    opts - Optional {:new-functions #{...}}

  Returns:
    Transaction result"
  [node file-path ns-sym & [opts]]
  ...)

(defn record-review-completed!
  "Update the review state singleton to now.

  Args:
    node - XTDB node

  Returns:
    Transaction result"
  [node]
  ...)

```

### Querying State

```clojure
(defn get-last-edit-time
  "Get timestamp of most recent edit, or nil if none."
  [node]
  ...)

(defn get-last-review-time
  "Get timestamp of last completed review, or nil if never reviewed."
  [node]
  ...)

(defn recent-unreviewed-edits
  "Get edits from recent window that haven't been reviewed.

  Args:
    node - XTDB node
    opts - {:lookback-minutes 5}

  Returns:
    Vector of edit event maps, oldest first"
  [node & [opts]]
  ...)

(defn unreviewed-summary
  "Get summary of unreviewed edits for review context.

  Returns:
    {:files #{...}
     :namespaces #{...}
     :new-fns #{...}
     :edit-count N}"
  [node & [opts]]
  ...)

```

### Trigger Logic

```clojure
(defn should-trigger-review?
  "Check if review should trigger now.

  Args:
    node - XTDB node
    opts - {:debounce-seconds 5
            :cooldown-seconds 30
            :lookback-minutes 5}

  Returns:
    true if review should trigger"
  [node & [opts]]
  ...)

```

---

## Migration Path

### Phase 1: Add New Functions

Add alongside existing functions (non-breaking):
- `record-edit!` - New, records `:edit-event`
- `record-review-completed!` - Already exists, keep it
- `recent-unreviewed-edits` - New
- `unreviewed-summary` - New
- `should-trigger-review?` - Update logic

### Phase 2: Update Hook

Modify `bin/seon-hook`:
1. Replace `stage-record-pending-edit` with `stage-record-edit`
2. Update `stage-should-review?` to use new config options
3. Replace `stage-clear-pending` with `stage-record-review-completed`
4. Replace `stage-get-pending-summary` with `stage-get-unreviewed-summary`

### Phase 3: Remove Old Functions

After confirming it works:
- Remove `record-pending-edit!`
- Remove `pending-edits`
- Remove `oldest-pending-edit-age`
- Remove `clear-pending-edits!`
- Remove `pending-edits-summary`

---

## Example XTQL Queries

### Get Recent Edits (Bounded)

```clojure
;; Using temporal query with window
(defn query-edits-after
  "Get edit events after a cutoff time."
  [node cutoff-instant]
  (node/xtql-query
   node
   '(-> (from :edit-event [xt/id edit/file edit/namespace
                           edit/new-functions xt/valid-from])
        (where (> xt/valid-from cutoff-instant))
        (order-by xt/valid-from))))

```

**Note:** In practice, we'll use `{:for-valid-time (from cutoff)}` for efficiency.

### Get Last Review Time

```clojure
(defn get-last-review-time [node]
  (let [result (first (node/xtql-query
                       node
                       '(from :review-state
                              [{:xt/id :seon.dev/review-state}
                               review/last-completed])))]
    (:review/last-completed result)))

```

### Get Last Edit Time

```clojure
(defn get-last-edit-time [node]
  (let [result (first (node/xtql-query
                       node
                       '(-> (from :edit-event [xt/valid-from])
                            (order-by {:val xt/valid-from :dir :desc})
                            (limit 1))))]
    (:xt/valid-from result)))

```

---

## Comparison: Old vs New

| Aspect | Old Model | New Model |
|--------|-----------|-----------|
| **Entity name** | `:pending-edit` | `:edit-event` |
| **Lifecycle** | Create then delete | Append only |
| **Accumulation** | Unbounded (bug risk) | Bounded by time window |
| **"Needs review"** | All pending edits | Recent window since last review |
| **History** | Lost on delete | Preserved forever |
| **Debounce** | Time since oldest pending | Time since last edit |
| **Cooldown** | Not supported | First-class config |
| **Crash safety** | Orphaned edits possible | Clean state always |

---

## Implementation Estimate

| Task | Effort |
|------|--------|
| Add `record-edit!`, update `record-review-completed!` | 30 min |
| Add `recent-unreviewed-edits`, `unreviewed-summary` | 30 min |
| Update `should-trigger-review?` with new logic | 30 min |
| Update hook script | 30 min |
| Update config file and documentation | 15 min |
| Testing | 30 min |
| Remove deprecated functions (later PR) | 15 min |
| **Total** | ~3 hours |

---

## Files to Modify

1. **`src/seon/dev/feedback.clj`**
   - Add: `record-edit!`
   - Add: `recent-unreviewed-edits`, `unreviewed-summary`
   - Update: `should-trigger-review?` (new logic)
   - Keep: `record-review-completed!` (already exists)
   - Deprecate: `record-pending-edit!`, `pending-edits`, `clear-pending-edits!`

2. **`bin/seon-hook`**
   - Update stage functions to use new names
   - Pass new config options (debounce, cooldown, lookback)

3. **`.claude/seon-hook.edn`**
   - Add `:cooldown-seconds` and `:lookback-minutes`
   - Document new semantics

---

## Safety Analysis

### Worst Case: Reviews Stop for Days

1. Edits accumulate as `:edit-event` entities (normal, expected)
2. When hook checks `should-trigger-review?`:
   - Gets recent edits with `lookback-minutes: 5`
   - Only sees last 5 minutes of edits
   - Triggers review with manageable file count
3. Review completes, updates `review-state`
4. Next cycle continues normally

**Result:** System self-heals, never tries to review unbounded history.

### Worst Case: Rapid Editing

1. User edits many files rapidly
2. Each edit records an `:edit-event`
3. `should-trigger-review?` checks debounce
4. Debounce not met (last edit too recent) -> no review
5. Eventually user pauses -> debounce met -> review triggers
6. Review covers all files in recent window (capped by time)

**Result:** Debounce prevents mid-typing reviews, window caps file count.

---

## Open Questions (Resolved)

1. **Content hash deduplication?**
   - Decision: Not in v1. Add later if edit volume is high.

2. **Review event granularity?**
   - Decision: Single singleton `:review-state`. We don't need detailed review history for the debounce use case. Add `:review-event` later for analytics.

3. **What about the existing `record-review-completed!`?**
   - Decision: Keep it! It already does what we need. Just ensure it's called after reviews.

---

## Summary

This redesign transforms the model from **state-based** (pending edits that get deleted) to **event-based** (immutable edit facts with bounded queries). The key safety feature is the time window that prevents unbounded accumulation even if reviews stop working.

The model is simple:
- Record edits as facts (never delete)
- Track when last review happened (singleton)
- Query recent edits since last review (bounded by time window)
- Trigger review when debounce and cooldown conditions are met
