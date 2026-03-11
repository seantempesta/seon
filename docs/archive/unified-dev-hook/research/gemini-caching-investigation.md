---
type: research
status: completed
tags: [research, archive]
---

# Gemini API Caching Investigation

**Date**: 2025-12-29
**Status**: Complete
**Author**: Claude (investigation)

## Executive Summary

**Gemini implicit context caching IS working correctly with our current implementation.** The system instruction approach we use in `review-code` enables automatic caching with 90% cost savings on cached tokens.

## Key Findings

### 1. Our Current Approach Works

Verified through REPL experiments:

```
;; First request - no caching
Code review tokens {:prompt 2188, :response 243, :cached 0}

;; Second request with same system instruction - cached!
Code review tokens {:prompt 2188, :response 290, :cached 2022}

;; Different code but same conventions - still cached!
Code review tokens {:prompt 2187, :response 286, :cached 2022}

```

The system instruction (containing CONVENTIONS.md at ~2150 tokens) is automatically cached after the first request.

### 2. How Gemini Implicit Caching Works

From 2025 Gemini API documentation:

| Feature | Value |
|---------|-------|
| **Enabled by default** | Yes, for Gemini 2.5+ and Gemini 3 models |
| **Min tokens (Flash)** | 1,024 tokens |
| **Min tokens (Pro)** | 4,096 tokens |
| **Discount rate** | 90% on cached tokens |
| **Storage fees** | None (implicit caching only) |
| **Guarantee** | No - cache can be evicted (LRU-based) |

### 3. Response Metadata Fields

The API returns caching information in `usageMetadata`:

```json
{
  "promptTokenCount": 2151,
  "candidatesTokenCount": 579,
  "totalTokenCount": 3144,
  "cachedContentTokenCount": 2021,  // <- Key field!
  "promptTokensDetails": [{"modality": "TEXT", "tokenCount": 2151}],
  "cacheTokensDetails": [{"modality": "TEXT", "tokenCount": 2021}],
  "thoughtsTokenCount": 414
}

```

- **`cachedContentTokenCount`**: Tokens served from cache (discounted 90%)
- **`cacheTokensDetails`**: Breakdown by modality (TEXT, IMAGE, etc.)

### 4. Best Practices for Caching

Per Gemini documentation:

1. **Prefix continuity**: Static content (system instruction) must be byte-identical
2. **Static first, dynamic last**: System instruction is ideal for caching
3. **No timestamps/unique IDs**: These break the cache prefix match
4. **Minimum threshold**: Content must exceed 1,024 tokens for Flash models

### 5. Our CONVENTIONS.md Size

```bash
$ wc -c CONVENTIONS.md
    7830 CONVENTIONS.md  # ~7.8KB

```

After tokenization: ~2,150 tokens - well above the 1,024 minimum for Flash models.

## Is Our Implementation Correct?

**Yes.** The `review-code` function places CONVENTIONS.md in the system instruction:

```clojure
(defn review-code [{::keys [prompt code conventions ...]}]
  (let [;; Static content -> system instruction (cacheable)
        system-instruction (str "You are a code reviewer..."
                                (when conventions
                                  (str "=== PROJECT CONVENTIONS ===\n" conventions)))
        ;; Dynamic content -> user prompt
        user-prompt (str prompt "\n\n" code)]
    ...))

```

This pattern:
1. Puts static conventions in system instruction
2. Puts variable code/prompt in user message
3. Enables Gemini to cache the system instruction prefix

## Multi-Document Caching

### Current Approach (Recommended)

For caching multiple documents:
- **Concatenate all documents into the system instruction**
- Keep the concatenation order identical across requests
- Total must exceed 1,024 tokens (easily achieved with multiple docs)

```clojure
(str "=== DOCUMENT 1 ===\n" doc1 "\n\n"
     "=== DOCUMENT 2 ===\n" doc2 "\n\n"
     "=== DOCUMENT 3 ===\n" doc3)

```

### Alternative: Explicit Caching API

For guaranteed long-term caching (1-24 hours), use the `cachedContents` endpoint:

```
POST https://generativelanguage.googleapis.com/v1beta/cachedContents

```

**Trade-offs:**
- **Pro**: Guaranteed cache hits, longer TTL
- **Con**: Storage fees (~$4.50/M tokens/hour), more complex API
- **Best for**: Very large documents (>50K tokens), high-traffic shared caches

**Our recommendation**: Stick with implicit caching. It's free, automatic, and works well for our use case.

## Verifying Cache Hits

### Option 1: Check Response Metadata (Current)

We already log this in `review-code`:

```clojure
(log/debug "Code review tokens" {:prompt (:promptTokenCount usage)
                                 :response (:candidatesTokenCount usage)
                                 :cached (:cachedContentTokenCount usage 0)})

```

### Option 2: Enhanced Logging (Recommended Enhancement)

Add cache hit rate tracking:

```clojure
(defn- log-cache-stats [usage]
  (let [prompt (:promptTokenCount usage 0)
        cached (:cachedContentTokenCount usage 0)
        hit-rate (if (pos? prompt)
                   (* 100.0 (/ cached prompt))
                   0.0)]
    (log/info "Gemini cache" {:prompt-tokens prompt
                              :cached-tokens cached
                              :cache-hit-rate (format "%.1f%%" hit-rate)
                              :estimated-savings (format "$%.4f" (* cached 0.0000005 0.9))})))

```

### Option 3: Metrics Dashboard (Future)

Track over time:
- Cache hit rate per hour/day
- Total tokens saved
- Cost savings estimate

## Recommendations

### No Code Changes Required

Our current implementation is correct. The system instruction approach works perfectly for implicit caching.

### Optional Enhancements

1. **Better logging**: Log cache hit rate, not just raw token counts
2. **Cost tracking**: Log estimated cost savings from caching
3. **Document size validation**: Warn if conventions < 1,024 tokens

### Multi-Document Pattern

To cache additional documents (e.g., CLAUDE.md + CONVENTIONS.md):

```clojure
(defn- build-system-instruction
  "Build cacheable system instruction from multiple documents."
  [docs-map]
  (str "You are a code reviewer for Clojure code.\n\n"
       (str/join "\n\n"
         (for [[name content] docs-map]
           (str "=== " name " ===\n" content)))
       "\n\nFormat: Start with a brief summary, then list any concerns."))

```

Usage:

```clojure
(review-code {::prompt "Review this"
              ::code "(defn foo ...)"
              ::conventions {"CONVENTIONS.md" (slurp "CONVENTIONS.md")
                            "CLAUDE.md" (slurp "CLAUDE.md")}})

```

## Cost Analysis

With implicit caching:
- First request: Full price (~$0.50/M input tokens)
- Subsequent requests: 90% discount on cached portion

For our CONVENTIONS.md (~2,150 tokens):
- **Uncached cost**: $0.001075 per request
- **Cached cost**: ~$0.0001075 per request (90% savings)

Over 100 code reviews:
- Without caching: ~$0.11
- With caching: ~$0.02 (82% total savings)

## Conclusion

1. **Implicit caching is working** - verified through REPL experiments
2. **Our implementation is correct** - system instruction approach is optimal
3. **No changes needed** - current approach maximizes cache hits
4. **Multi-document support is easy** - concatenate in system instruction

The key insight: Keep the system instruction identical across requests. Variable content (code, prompts) goes in the user message.
