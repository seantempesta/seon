# Durable Atoms Research: Clojure Libraries and Patterns

**Date:** 2026-01-28
**Status:** Research Complete (Hands-On Testing Done)
**Purpose:** Find existing durable atom implementations for Seon's agent context persistence

---

## Requirements

Seon needs a durable atom for agent context with:

1. **Versioned state with time-travel** - Access previous states
2. **Persistence to disk** - Survive restarts
3. **Structural sharing** - Nice to have, not required
4. **Skip non-serializable keys** - Must filter before persistence

This supports the append-only snapshot strategy from `temporal-strategy.md` (Option B).

---

## Hands-On Testing Results (2026-01-28)

Added both libraries to `deps.edn` under `:dev` alias and tested in REPL.

### Duratom - RECOMMENDED

**Namespace:** `duratom.core`
**Clojars:** `duratom/duratom {:mvn/version "0.5.9"}`

#### API Ergonomics: Excellent

```clojure
(require '[duratom.core :as dur])

;; Create - drop-in atom replacement
(def my-atom (dur/duratom :local-file
                          :file-path "tmp/test.edn"
                          :commit-mode :sync  ; or :async (default)
                          :init {}))

;; Standard atom operations work!
(swap! my-atom assoc :foo "bar")  ; WORKS
(reset! my-atom {:new "state"})   ; WORKS
@my-atom                          ; WORKS
(add-watch my-atom :key fn)       ; WORKS
```

#### Key Findings

1. **Implements IAtom** - Standard `swap!`, `reset!`, `@` all work
2. **Async by default** - File writes happen via agent, ~100ms delay
3. **Sync mode available** - `:commit-mode :sync` for immediate writes
4. **Watchers work** - Can add versioning via `add-watch`
5. **Custom serializers** - `:rw` option for filtering non-serializable values

#### Persistence Modes

```clojure
;; Async (default) - faster, eventual consistency
(dur/duratom :local-file :file-path "f.edn" :init {})

;; Sync - slower, immediate durability
(dur/duratom :local-file :file-path "f.edn" :commit-mode :sync :init {})
```

#### Non-Serializable Value Handling

**Problem:** Both libraries write `#object[...]` tags that can't be read back.

```clojure
(swap! my-atom assoc :conn (Object.))
;; File contains: {:conn #object[java.lang.Object 0x1234 "..."]}
;; This breaks on read!
```

**Solution:** Custom write function with filtering:

```clojure
(defn serializable-edn? [v]
  (try
    (not (re-find #"#object\[" (pr-str v)))
    (catch Exception _ false)))

(defn filter-for-persistence [m]
  (into {} (filter (fn [[k v]]
                     (and (serializable-edn? k)
                          (serializable-edn? v))) m)))

(def filtered-atom
  (dur/duratom :local-file
               :file-path "tmp/filtered.edn"
               :commit-mode :sync
               :rw {:read #(clojure.edn/read-string (slurp %))
                    :write (fn [path data]
                             (spit path (pr-str (filter-for-persistence data))))}
               :init {}))
```

#### Nippy Backend (Binary, 25% smaller)

```clojure
(require '[taoensso.nippy :as nippy])

(def nippy-atom
  (dur/duratom :local-file
               :file-path "tmp/state.bin"
               :commit-mode :sync
               :rw {:read nippy/thaw-from-file
                    :write nippy/freeze-to-file}
               :init {}))
```

File size comparison (1000-element vector):

- EDN: 3906 bytes
- Nippy: 2901 bytes (25.7% smaller)

---

### Enduro - NOT RECOMMENDED

**Namespace:** `alandipert.enduro`
**Clojars:** `alandipert/enduro {:mvn/version "1.2.0"}`

#### API Ergonomics: Poor

```clojure
(require '[alandipert.enduro :as enduro])

(def my-atom (enduro/file-atom {} "tmp/test.edn"))

;; MUST use enduro's functions!
(enduro/swap! my-atom assoc :foo "bar")  ; WORKS
(swap! my-atom assoc :foo "bar")         ; FAILS! ClassCastException

;; Standard operations
@my-atom                                  ; WORKS
(add-watch my-atom :key fn)              ; WORKS
```

#### Critical Issue

**Does NOT implement IAtom** - Cannot use standard `clojure.core/swap!`

This is a dealbreaker because:

- Existing code won't work
- Libraries expecting atoms won't work
- Easy to forget and use wrong function

---

## Comparison Table

| Feature | Duratom | Enduro |
|---------|---------|--------|
| Drop-in atom replacement | YES | NO |
| Standard `swap!` works | YES | NO (ClassCastException) |
| Persistence modes | async, sync | sync only |
| Backends | file, postgres, sqlite, s3, redis | file, postgres |
| Custom serializers | YES (`:rw` option) | NO |
| Watchers | YES | YES |
| Built-in versioning | NO | NO |
| Built-in filtering | NO | NO |
| Maintenance | Active (Dec 2023) | Dormant |
| Clojars downloads | 12.8k | ~1k |

---

## Recommended Pattern: Duratom + Watcher Versioning

Neither library provides versioning, but duratom's drop-in API makes it easy to add via watchers:

```clojure
(ns seon.agent.durable-ctx
  "Durable, versioned atom for agent context"
  (:require [duratom.core :as dur]
            [clojure.java.io :as io]))

(defn serializable-edn? [v]
  (try
    (not (re-find #"#object\\[" (pr-str v)))
    (catch Exception _ false)))

(defn filter-for-persistence [m]
  (into {} (filter (fn [[k v]]
                     (and (serializable-edn? k)
                          (serializable-edn? v))) m)))

(defn make-versioned-ctx
  "Create a durable atom with in-memory version history.

   Options:
   - :file-path - required, persistence file path
   - :init - initial value (default {})
   - :max-versions - history limit (default 100)"
  [{:keys [file-path init max-versions]
    :or {init {} max-versions 100}}]

  (let [the-atom (dur/duratom :local-file
                              :file-path file-path
                              :commit-mode :sync
                              :rw {:read #(clojure.edn/read-string (slurp %))
                                   :write (fn [p d]
                                            (spit p (pr-str (filter-for-persistence d))))}
                              :init init)
        versions (atom [])]

    (add-watch the-atom ::versioning
      (fn [_ _ old new]
        (when (not= old new)
          (swap! versions
                 (fn [v]
                   (let [v' (conj v {:timestamp (java.time.Instant/now)
                                     :state new})]
                     (if (> (count v') max-versions)
                       (vec (take-last max-versions v'))
                       v')))))))

    {:atom the-atom
     :versions versions
     :state-at (fn [instant]
                 (->> @versions
                      (filter #(not (.isAfter (:timestamp %) instant)))
                      last
                      :state))
     :history (fn [] @versions)
     :close! (fn []
               (remove-watch the-atom ::versioning)
               (dur/destroy the-atom))}))

;; Usage
(def ctx (make-versioned-ctx
          {:file-path "tmp/agent-ctx.edn"
           :init {:status :pending}
           :max-versions 50}))

;; Standard atom operations
(swap! (:atom ctx) assoc :status :running)
(swap! (:atom ctx) assoc :progress 0.5)

;; Time-travel
((:state-at ctx) some-past-instant)

;; Get history
((:history ctx))
```

---

## Final Recommendation

**Use Duratom for current agent `*ctx*` persistence.**

Rationale:

1. **Drop-in atom API** - No code changes needed for existing `swap!`/`@` usage
2. **Custom serializers** - Can filter non-serializable values
3. **Sync mode** - Immediate durability when needed
4. **Active maintenance** - Still receiving updates
5. **Versioning via watchers** - Easy to add in-memory history

**Later, for full persistence:** Migrate to Datalevin append-only pattern (already planned) which provides:

- Database-level versioning
- Query capabilities
- Compaction
- Multi-agent coordination

The duratom pattern is a good bridge - we can add persistence now without changing the atom-based API, then migrate to Datalevin incrementally.

---

## Library Added to deps.edn

```clojure
;; In :dev alias
duratom/duratom {:mvn/version "0.5.9"}
```

**Note:** Enduro was tested but removed - it doesn't implement IAtom so standard `swap!` fails.

---

## References

- [duratom](https://github.com/jimpil/duratom) - Most mature durable atom
- [enduro](https://github.com/alandipert/enduro) - Alan Dipert's original
- [perdure](https://github.com/pesterhazy/perdure) - Git-backed versioning (unmaintained)
- [durable-ref](https://github.com/riverford/durable-ref) - Distributed references
- [historian](https://github.com/reagent-project/historian) - In-memory undo/redo
- [Clojure Atoms](https://clojure.org/reference/atoms) - Core reference
- [add-watch](https://clojuredocs.org/clojure.core/add-watch) - Watcher pattern
