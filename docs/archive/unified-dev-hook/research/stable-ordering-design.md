# Stable Document Ordering for Gemini Cache Optimization

**Date**: 2025-12-29
**Status**: Design
**Author**: Claude (design)

## Problem Statement

Gemini's implicit caching matches prefixes byte-exactly across the entire request. To maximize cache hits, we need to:
1. Order documents by stability (most stable first, most frequently changing last)
2. Keep the order consistent across requests
3. Structure the request to maximize cacheable prefixes

Currently, `stage-batch-review` in `bin/seon-hook` concatenates files in arbitrary order as a single string. This approach:
- Loses cache benefits when file order changes
- Concatenates everything into one message (less flexible)

## Key Insights from Gemini API

From the [Gemini implicit caching docs](https://developers.googleblog.com/en/gemini-2-5-models-now-support-implicit-caching/):

> "When you send a request to one of the Gemini 2.5 models, if the request shares a common prefix as one of previous requests, then it's eligible for a cache hit."

The request structure is:
```json
{
  "systemInstruction": {"parts": [{"text": "..."}]},
  "contents": [
    {"role": "user", "parts": [{"text": "message 1"}]},
    {"role": "user", "parts": [{"text": "message 2"}]}
  ]
}
```

**Caching works on the entire serialized request prefix**, including:
1. System instruction (first)
2. Contents array in order

## Design: Multi-Message Structure

Instead of concatenating all files into one string, use the **contents array** with separate messages:

```clojure
{:systemInstruction {:parts [{:text "Project conventions..."}]}
 :contents [
   {:role "user" :parts [{:text "=== src/seon/core.clj ===\n(ns seon.core...)"}]}
   {:role "user" :parts [{:text "=== src/seon/db/node.clj ===\n(ns seon.db.node...)"}]}
   {:role "user" :parts [{:text "=== src/seon/ai/gemini.clj ===\n(defn foo...)"}]}
   {:role "user" :parts [{:text "Review these code changes against project conventions."}]}
 ]}
```

**Benefits:**
- Stable files at the start of contents array get cached
- Dynamic files (just-edited) at the end don't break the prefix cache
- Each file is a separate message - cleaner structure for the model

## Stability Tracking in XTDB

### Extending Existing Entities

We already track `:edit-event` entities with `:edit/file` and `:edit/timestamp`. Rather than creating a new entity type, we can **query edit history** to derive stability:

```clojure
;; Count edits per file from edit-event history
(-> (from :edit-event [edit/file xt/id])
    (aggregate {:edit-count (count xt/id)} edit/file))

;; Get most recent edit per file
(-> (from :edit-event [edit/file edit/timestamp])
    (aggregate {:last-edit (max edit/timestamp)} edit/file))
```

### Optional: Add Denormalized Attributes

For performance, we could add stability attributes to a **`:file` entity** (new entity type, one per file):

```clojure
{:xt/id          :file/src/seon/foo.clj
 :entity/type    :file
 :file/path      "src/seon/foo.clj"
 :file/edit-count 12
 :file/last-edit  #inst "2025-12-29T10:30:00Z"
 :file/first-seen #inst "2025-12-20T08:00:00Z"}
```

But this may be premature optimization. Start with querying `:edit-event` history.

## Ordering Algorithm

```
Input: Set of file paths to include in review
Output: Vector of file paths in cache-optimized order

1. Categorize files:
   - static: CONVENTIONS.md, CLAUDE.md (hardcoded order)
   - regular: all other files not in current pending-edits
   - just-edited: files from current pending-edits (most recent last)

2. Sort each category:
   - static: by predefined order
   - regular: by stability score DESC, then alphabetically
   - just-edited: by edit timestamp ASC (oldest first)

3. Build contents array:
   - System instruction: CONVENTIONS.md + CLAUDE.md content
   - Contents[0..n-1]: regular files in stability order
   - Contents[n..m]: just-edited files
   - Contents[last]: the review prompt
```

### Stability Score

```clojure
(defn stability-score
  "Higher score = more stable = should come first.

   Score = days-since-first-seen / edit-count

   Examples:
   - File A: 10 days old, 2 edits -> score 5.0
   - File B: 10 days old, 20 edits -> score 0.5
   - File A is more stable, comes first"
  [edit-count first-seen-instant]
  (let [now (Instant/now)
        age-ms (- (.toEpochMilli now) (.toEpochMilli first-seen-instant))
        age-days (/ age-ms (* 1000 60 60 24))]
    (if (pos? edit-count)
      (/ age-days edit-count)
      Double/MAX_VALUE)))  ; Never edited = maximally stable
```

## Code Sketch

### Query Stability Data

```clojure
(defn file-stability-data
  "Query edit history to compute stability for each file.

   Returns map of {file-path {:edit-count N :first-seen Instant :last-edit Instant}}"
  [node]
  (let [;; Get edit count and timestamps per file
        results (node/xtql-query
                 node
                 '(-> (from :edit-event [edit/file edit/timestamp xt/id])
                      (aggregate {:edit-count (count xt/id)
                                  :first-seen (min edit/timestamp)
                                  :last-edit (max edit/timestamp)}
                                 edit/file)))]
    (into {}
          (map (fn [row]
                 [(:edit/file row)
                  {:edit-count (:edit-count row)
                   :first-seen (:first-seen row)
                   :last-edit (:last-edit row)}])
               results))))
```

### Order Files

```clojure
(def static-docs ["CONVENTIONS.md" "CLAUDE.md"])

(defn order-by-stability
  "Order files for cache-optimized Gemini request.

   Args:
     stability-data - Map from file-stability-data
     file-paths     - Set of files to order
     pending-files  - Set of files from current pending-edits

   Returns:
     Vector of file paths in optimal order"
  [stability-data file-paths pending-files]
  (let [pending-set (set pending-files)

        ;; Categorize
        regular (remove #(or (some #{%} static-docs)
                             (pending-set %))
                        file-paths)
        pending (filter pending-set file-paths)

        ;; Sort regular by stability (higher = earlier)
        regular-sorted (sort-by (fn [f]
                                  (let [data (get stability-data f)
                                        score (if data
                                                (stability-score (:edit-count data)
                                                                 (:first-seen data))
                                                0.0)]
                                    [(- score) f]))  ; negate for DESC
                                regular)

        ;; Sort pending by last-edit (oldest first)
        pending-sorted (sort-by (fn [f]
                                  (get-in stability-data [f :last-edit] (Instant/now)))
                                pending)]

    (vec (concat regular-sorted pending-sorted))))
```

### Build Multi-Message Request

```clojure
(defn build-review-contents
  "Build contents array with files as separate messages.

   Args:
     ordered-files - Vector of file paths in stability order
     review-prompt - The review instruction

   Returns:
     Vector of content maps for Gemini API"
  [ordered-files review-prompt]
  (let [file-messages (mapv (fn [f]
                              {:role "user"
                               :parts [{:text (str "=== " f " ===\n"
                                                   (slurp f))}]})
                            ordered-files)
        prompt-message {:role "user"
                        :parts [{:text review-prompt}]}]
    (conj file-messages prompt-message)))
```

### Extend generate* for Multi-Message

The current `build-request-body` only supports a single prompt string. We need to extend it:

```clojure
(defn- build-request-body
  "Build the JSON request body.

   Args:
     prompt-or-contents - Either a string (single prompt) or vector of content maps
     options            - Map with tools, thinking-level, system-instruction"
  [prompt-or-contents {:keys [tools thinking-level system-instruction response-schema]}]
  (cond-> {:contents (if (string? prompt-or-contents)
                       [{:parts [{:text prompt-or-contents}]}]
                       prompt-or-contents)}
    ;; ... rest unchanged
    ))
```

## Integration Point

### Changes to bin/seon-hook

In `stage-batch-review`, replace the current file concatenation with:

```clojure
(defn stage-batch-review
  [files-summary test-results]
  ;; 1. Query stability data
  (let [stability (nrepl-eval "(seon.dev.feedback/file-stability-data (user/xtdb-node))")

        ;; 2. Order files
        source-files (:files files-summary)
        test-files (keep source->test-path source-files)
        pending-files source-files  ; Current edits
        all-files (into source-files test-files)
        ordered-files (order-by-stability stability all-files pending-files)

        ;; 3. Build multi-message contents
        contents (build-review-contents ordered-files "Review these changes...")

        ;; 4. Call Gemini with contents array (new API)
        result (nrepl-eval
                (format "(seon.ai.gemini/review-code-multi
                          {:seon.ai.gemini/contents %s
                           :seon.ai.gemini/conventions (slurp \"CONVENTIONS.md\")})"
                        (pr-str contents)))]
    ...))
```

### New Function: review-code-multi

```clojure
(defn review-code-multi
  "Code review with multi-message contents for cache optimization.

   Request keys:
     ::contents    - Vector of {:role \"user\" :parts [{:text \"...\"}]} maps
     ::conventions - Static conventions (goes to system instruction)
     ::model       - Optional model name
     ::timeout     - Optional timeout
     ::api-key     - Optional API key

   Returns: String (review text)"
  [{::keys [contents conventions model timeout api-key]}]
  (let [key (resolve-api-key! api-key)
        system-instruction (str "You are a code reviewer for Clojure code.\n\n"
                                (when conventions
                                  (str "=== PROJECT CONVENTIONS ===\n" conventions)))]
    (generate* key contents  ; pass contents vector, not string
               {:model (or model default-model)
                :timeout (or timeout default-timeout-ms)
                :system-instruction system-instruction})))
```

## Cache Behavior Example

Given these files with stability scores:
```
CONVENTIONS.md   -> (in system instruction, always cached)
src/seon/core.clj       -> score 10.0 (rarely edited)
src/seon/config.clj     -> score 8.0
src/seon/db/node.clj    -> score 5.0
src/seon/ai/gemini.clj  -> score 2.0 (just edited)
src/seon/web/handlers.clj -> score 0.5 (just edited)
```

Request structure:
```
systemInstruction: "...CONVENTIONS.md..."    <- CACHED (static)
contents[0]: src/seon/core.clj               <- CACHED (stable)
contents[1]: src/seon/config.clj             <- CACHED (stable)
contents[2]: src/seon/db/node.clj            <- CACHED (stable)
contents[3]: src/seon/ai/gemini.clj          <- NOT CACHED (just edited)
contents[4]: src/seon/web/handlers.clj       <- NOT CACHED (just edited)
contents[5]: test/seon/ai/gemini_test.clj    <- NOT CACHED
contents[6]: "Review these changes..."       <- NOT CACHED (prompt)
```

The prefix (system instruction + first 3 files) remains stable across requests, so Gemini caches ~70% of the input tokens.

## Implementation Phases

### Phase 1: Query-based stability (minimal changes)
1. Add `file-stability-data` to `seon.dev.feedback`
2. Add `order-by-stability` function
3. Modify `stage-batch-review` to use ordering
4. Keep single-message structure for now

### Phase 2: Multi-message structure
1. Extend `build-request-body` for contents array
2. Add `review-code-multi` function
3. Update `stage-batch-review` to build contents array

### Phase 3: Optimization (if needed)
1. Add denormalized `:file` entity for faster queries
2. Cache stability scores in memory (refresh on edit)

## Sources

- [Gemini Context Caching Docs](https://ai.google.dev/gemini-api/docs/caching)
- [Gemini 2.5 Implicit Caching Announcement](https://developers.googleblog.com/en/gemini-2-5-models-now-support-implicit-caching/)
- [Vertex AI Context Cache Overview](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/context-cache/context-cache-overview)
