---
type: prd
status: draft
tags: [prd, database]
---
# PRD: Delta-Based Ctx History (Undo/Redo)

## Overview

Browser-style undo/redo for `*ctx*` atoms using datom-style deltas instead of full snapshots. Every namespace instance gets a time machine -- users can step back and forward through every state change.

## Model Clarification

Clojure persistent data structures already do structural sharing in memory, so keeping full snapshots in the in-memory history sidecar is cheap. **Deltas are for Datalevin crash recovery only** — they minimize what gets written to disk. In memory, `go-back!`/`go-forward!` can simply swap the whole ctx value.

## Core Concept

Instead of storing full ctx snapshots on every change, store **deltas** (map diffs) between states:

```
T0: initial-state       -> base checkpoint (full state stored)
T1: user added squat    -> delta: {::added {::workouts [...]} }
T2: changed weight      -> delta: {::retracted {::weight 100}, ::added {::weight 120}}

Reconstruct T2 = apply(base, delta-T1, delta-T2)
Go back to T1 = apply(base, delta-T1)

```

The current ctx atom value is always the "present". Deltas are stored in Datalevin for persistence. A cursor tracks where we are in the timeline. Back/forward navigation applies/reverses deltas.

## Design Decisions

### 1. Granularity: Top-level keys only

Diff at top-level map keys. Ctx values per key are manageable-sized (a vector of workouts, a string narrative, etc). Deep-diffing nested structures adds complexity for little gain -- if a vector changes, store the whole new vector as the delta value.

### 2. Max history size: 100 deltas with compaction

Cap at 100 deltas per instance. When exceeded, compact the oldest 50 deltas into a new base checkpoint. This bounds memory and storage while keeping recent history granular.

### 3. Datalevin storage: EDN strings

Store deltas as EDN strings, matching current ctx persistence pattern. One entity per delta with instance-id, sequence number, timestamp, and EDN delta data. This is simple, proven (ctx already does this), and avoids schema complexity.

### 4. History metadata in ctx

A reserved key `::seon.ctx/history` in the ctx atom contains:

```clojure
{:cursor 5     ;; current position (0 = base)
 :total 5      ;; total deltas available
 :can-undo true
 :can-redo false}

```

This is enough for a breadcrumb/timeline renderer. The actual delta data stays in the history sidecar atom, not in ctx.

### 5. Interaction with existing persistence

Existing persistence continues unchanged -- it saves the full current state (the "present"). Delta history is an additional layer on top. On restart, the full state is restored from existing persistence, and delta history is restored from its own Datalevin entities. They are complementary, not competing.

## Datalevin Research Summary

Datalevin does NOT have built-in temporal/history support (unlike Datomic). Relevant capabilities:

- **`d/transact!`** -- append transactions, returns tx-report. Efficient for many small writes.
- **`d/listen!` / `d/unlisten!`** -- register callbacks on conn for tx notifications. Could auto-capture deltas on every write, but we don't need this since we control ctx writes through watches.
- **Schema types** -- `:db.type/string` for EDN data, `:db.type/instant` for timestamps, `:db.type/long` for sequence numbers. `:db/unique :db.unique/identity` for upsert semantics.
- **Performance** -- LMDB-backed, fast for append-only small transactions. No concern about storing 100 small EDN strings per instance.

Decision: Build our own delta history layer on top of Datalevin's simple key-value storage. No temporal features to leverage.

---

## Phases

### Phase 1: Pure diff utilities ✅ DONE

**Files:**

- `src/seon/ctx/history.clj` -- `map-diff`, `apply-delta`, `reverse-delta`, `empty-delta?`
- `test/seon/ctx/history_test.clj` -- unit tests including round-trip verification

**Status:** Complete and tested. All functions are pure, map-in/map-out, with Malli schemas. Tests verify round-trip (apply-delta of map-diff reconstructs original), empty-delta detection, and key addition/removal/change.

**Verification:**

```clojure
(require '[seon.ctx.history :as h])
(h/map-diff {::h/before {:a 1 :b 2} ::h/after {:a 1 :b 3 :c 4}})
;; => {::h/added {:b 3 :c 4} ::h/retracted {:b 2}}

```

---

### Phase 2: In-memory history sidecar — NOT STARTED

**Goal:** Extend `ctx/create!` to optionally track history in a companion atom. Watches on the ctx atom compute and store deltas automatically. Provide `go-back!`, `go-forward!`, and `history-reset!` functions.

**Files to modify:**

- `src/seon/ctx.clj` -- add `::history?` option to `create!`, history watch
- `src/seon/ctx/history.clj` -- add stateful history management functions

**New schemas:**

```clojure
::history?          ;; boolean, opt-in to history tracking
::max-deltas        ;; int, default 100
::cursor            ;; int, current position in timeline
::history-entry     ;; {:delta ::delta, :timestamp inst?}
::history-state     ;; {:base map, :deltas vec, :cursor int}

```

**New functions in `seon.ctx.history`:**

```clojure
(create-history!  {::instance-id "abc" ::base-state {:a 1}})
;; => atom holding {::base ... ::deltas [] ::cursor 0}

(record-delta!    {::instance-id "abc" ::delta {...}})
;; Appends delta, advances cursor, truncates future if mid-history

(go-back!         {::instance-id "abc"})
;; Moves cursor back, returns reversed delta to apply to ctx

(go-forward!      {::instance-id "abc"})
;; Moves cursor forward, returns delta to apply to ctx

(history-info     {::instance-id "abc"})
;; => {::cursor 3 ::total 5 ::can-undo true ::can-redo true}

```

**Integration with `ctx/create!`:**

- When `::history? true` is passed, create a history sidecar atom
- Add a watch that calls `map-diff` on old/new values and `record-delta!`
- Store sidecar ref in the registry entry
- `go-back!`/`go-forward!` modify the ctx atom (which triggers persistence/SSE as usual)
- Inject `::seon.ctx/history` metadata into ctx state for rendering

**Key detail:** When `go-back!`/`go-forward!` modify the ctx atom, the history watch must NOT record that change as a new delta. Use a flag (e.g., `::navigating?` atom) to suppress recording during navigation.

**Verification:**

```clojure
(def *ctx* (ctx/create! {::ctx/instance-id "test"
                          ::ctx/history? true
                          ::ctx/persist? false
                          ::ctx/sse-push? false}))
(swap! *ctx* assoc :foo/bar 1)
(swap! *ctx* assoc :foo/bar 2)
(swap! *ctx* assoc :foo/baz 3)
;; History has 3 deltas, cursor at 3

(ctx.history/go-back! {::ctx/instance-id "test"})
;; ctx now has :foo/bar 2, no :foo/baz
;; cursor at 2, can-redo true

(ctx.history/go-forward! {::ctx/instance-id "test"})
;; ctx now has :foo/bar 2, :foo/baz 3
;; cursor at 3, can-redo false

```

---

### Phase 3: Datalevin persistence for deltas — NOT STARTED

**Goal:** Persist delta history to Datalevin so it survives server restarts. Debounced like current ctx persistence.

**Datalevin schema additions (merge into ctx's schema):**

```clojure
{:seon.ctx.history/instance-id {:db/valueType :db.type/string}
 :seon.ctx.history/seq         {:db/valueType :db.type/long}
 :seon.ctx.history/delta-edn   {:db/valueType :db.type/string}
 :seon.ctx.history/base-edn    {:db/valueType :db.type/string}
 :seon.ctx.history/timestamp   {:db/valueType :db.type/instant}
 :seon.ctx.history/entry-id    {:db/valueType :db.type/string
                                :db/unique :db.unique/identity}}

```

Each delta becomes one entity: `entry-id = "{instance-id}-{seq}"`.

**New functions:**

```clojure
(persist-delta!   {::conn conn ::instance-id "abc" ::seq 3 ::delta {...}})
(persist-base!    {::conn conn ::instance-id "abc" ::base-state {...}})
(load-history!    {::conn conn ::instance-id "abc"})
;; => {::base-state {...} ::deltas [...] ::cursor N}
(compact-history! {::conn conn ::instance-id "abc"})
;; Compacts oldest deltas into new base checkpoint

```

**Integration:**

- On `ctx/create!` with `::history? true` and `::conn`, load history from Datalevin
- On each delta, debounce-persist to Datalevin (separate debounce from state persistence)
- On compaction trigger (> max-deltas), compact and clean up old entities

**Verification:**

```clojure
;; Make changes, restart server
;; History should survive:
(ctx.history/history-info {::ctx/instance-id "test"})
;; => {::cursor 3 ::total 3 ::can-undo true ::can-redo false}

```

---

### Phase 4: History-aware rendering — NOT STARTED

**Goal:** A timeline/breadcrumb component showing history state, clickable to jump to any point.

**Files:**

- `src/seon/render/history.clj` -- history timeline component
- `src/seon/render/default_page.clj` -- integrate history component

**Component design:**

- Horizontal breadcrumb bar below the page header
- Shows dots or small markers for each history point
- Current position highlighted
- Left/right arrows for undo/redo
- Keyboard shortcuts: Ctrl+Z / Ctrl+Shift+Z
- Tooltip on hover showing timestamp and changed keys

**Data source:** The `::seon.ctx/history` key injected into ctx by Phase 2.

**Verification:**

- Navigate to any namespace page with history enabled
- Make changes, see breadcrumb update
- Click back/forward, see state change
- Browser: `curl <http://localhost:8080/ns/seon.health.workout`> should show history bar

---

### Phase 5: URL integration — NOT STARTED

**Goal:** Map history position to URL query params for deep-linking and browser back/forward.

**URL format:** `/ns/seon.health.workout?t=3` -- view state at history position 3.

**Implementation:**

- Route handler reads `?t=N` query param
- If present, reconstruct state at position N (apply base + deltas 1..N)
- Render with that reconstructed state (read-only view, not modifying ctx)
- Browser back/forward via `pushState` when clicking timeline

**Files:**

- `src/seon/ns/routes.clj` -- read `?t=` param, pass to renderer
- `src/seon/render/history.clj` -- timeline links use `?t=N` hrefs

**Verification:**

- `curl <http://localhost:8080/ns/seon.health.workout?t=2`> shows state at T2
- Clicking timeline dot navigates browser to `?t=N`
- Browser back button returns to previous `?t=` view

---

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/ctx/history.clj` | Pure diff utilities + stateful history management |
| `test/seon/ctx/history_test.clj` | Unit tests for diff utilities |
| `src/seon/ctx.clj` | Ctx lifecycle (modified in Phase 2) |
| `src/seon/render/history.clj` | Timeline UI component (Phase 4) |
| `src/seon/render/default_page.clj` | Default page template (Phase 4) |
| `src/seon/ns/routes.clj` | Namespace routes (Phase 5) |

## Non-Goals

- Deep diffing of nested structures (top-level keys sufficient)
- Collaborative/multi-user history (single-user system)
- Branching history (linear timeline only -- new changes while mid-history truncate future)
- Diffing non-map ctx values (ctx is always a map)
