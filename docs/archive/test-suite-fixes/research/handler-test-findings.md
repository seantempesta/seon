---
type: research
status: completed
tags: [research, archive]
---

# Handler Test Failures - Research Findings

## Executive Summary

The handler tests fail due to **4 distinct issues**:
1. Null pointer from `slurp(nil)` when request body is nil
2. Missing input validation - NPE when required fields missing
3. API mismatch between test mocks and handler implementation
4. Job state lifecycle assumptions don't match reality

**Primary Fix**: Add input validation to handlers before parsing.

---

## Issue 1: Null Pointer - slurp() on nil body

### Location

`src/ml_options/web/handlers.clj:56`

### Code

```clojure
(let [body-str (slurp (:body request))  ;; FAILS if :body is nil

```

### Problem

When `:body` is `nil`, `slurp` attempts to call `.length()` on null:

```
Cannot invoke "java.lang.CharSequence.length()" because "this.text" is null

```

### When It Occurs

- Test's `mock-request` defaults `:body` to `nil` (line 31)
- If test doesn't override with `json-body`, handler gets nil
- `slurp(nil)` throws NPE

### Fix

```clojure
(let [body-str (if (:body request)
                 (slurp (:body request))
                 "{}")]  ;; Default to empty JSON

```

---

## Issue 2: Missing Input Validation

### Location

`src/ml_options/web/handlers.clj:62-71`

### Code

```clojure
symbols-str (:symbols body)                    ;; Can be nil
start-date-str (:startDate body)               ;; Can be nil
end-date-str (:endDate body)                   ;; Can be nil

symbols (vec (map str/trim (str/split symbols-str #",")))  ;; FAILS if nil
start-date (LocalDate/parse start-date-str)    ;; FAILS if nil
end-date (LocalDate/parse end-date-str)        ;; FAILS if nil

```

### Problem

No validation that required fields are present before parsing.

### When It Occurs

Test line 260 sends `{:symbols "AAPL"}` (missing dates):
1. JSON parses successfully
2. `:startDate` lookup returns `nil`
3. `LocalDate/parse nil` throws NPE

### What Tests Expect

Line 254 expects error containing "could not be parsed" - but actual exception is NPE with different message.

### Fix

```clojure
(when (or (str/blank? symbols-str)
          (str/blank? start-date-str)
          (str/blank? end-date-str))
  (throw (ex-info "Missing required fields"
                  {:missing (cond-> []
                              (str/blank? symbols-str) (conj :symbols)
                              (str/blank? start-date-str) (conj :startDate)
                              (str/blank? end-date-str) (conj :endDate))})))

```

---

## Issue 3: API Mismatch - Test Mock vs Reality

### Test Pattern

```clojure
(with-redefs [jobs/start-import! (fn [node symbols start end opts]
                                    (swap! jobs/job-state ...)
                                    {:ok "job-123"})]
  (handlers/start-import request))

```

### Handler Reality (line 74-76)

```clojure
node (jobs/get-node)  ;; Gets node first
result (jobs/start-import! node symbols start-date end-date {:parallelism 4})

{:status 200
 :headers {"Content-Type" "application/json"}
 :body (json/write-value-as-string result)}

```

### The Mismatch

1. Handler calls `jobs/get-node` before `jobs/start-import!`
2. Tests mock `jobs/start-import!` but may not account for node retrieval
3. If `jobs/get-node` fails or returns unexpected value, chain breaks

### Test That Works (line 182-188)

```clojure
(with-redefs [jobs/start-import! (fn [node symbols start end opts]
                                    {:ok "job-123"})]
  ;; This works because we don't care about the node
  ...)

```

### Integration Test Issue (line 369-409)

The test assumes job state is modified during `with-redefs`, but timing may be off.

---

## Issue 4: Job State Lifecycle

### Location

`test/ml_options/web/handlers_test.clj:369-409`

### Test Sequence

```
1. Get initial status (expects no current job)
2. Start job with mocked jobs/start-import!
3. Get status (expects job in :current)
4. Stop job
5. Get final status

```

### The Problem

Test redefines `jobs/start-import!` to:

```clojure
(fn [node symbols start end opts]
  (swap! jobs/job-state assoc :current
         {:id "job-123" :status :running :symbols symbols})
  {:ok "job-123"})

```

This should work, but assertions fail because:
- Test expects `:ok` value to be `"job-123"`
- Test gets `nil` instead
- The response parsing or handler return format may not match expectations

---

## Current Handler API

### handlers/start-import

**Input**: Ring request with JSON body

```json
{"symbols": "AAPL,SPY", "startDate": "2024-01-01", "endDate": "2024-12-31"}

```

**Processing**:
1. Slurp request body (FAILS if nil)
2. Parse JSON
3. Extract symbols, dates (FAILS if missing - NPE)
4. Parse dates to LocalDate (FAILS if invalid format)
5. Get XTDB node
6. Call `jobs/start-import!`

**Output**:
- Success: `{:status 200 :body "{\"ok\":\"job-id\"}"}`
- Error: `{:status 400 :body "{\"error\":\"message\"}"}`

### handlers/job-status

**Input**: Ring request (unused)
**Output**: `{:status 200 :body JSON_STRING}` with job state

### handlers/stop-import

**Input**: Ring request (unused)
**Output**: Same format as start-import

---

## Test Expectations vs Reality

| Test Case | Test Expects | Handler Does | Status |
|-----------|--------------|--------------|--------|
| Valid start | 200, `{:ok job-id}` | Returns mocked result | PASS |
| Missing startDate | 400 with error | NPE in LocalDate/parse | FAIL |
| Invalid date | 400 with "could not be parsed" | DateTimeParseException | PASS* |
| Malformed JSON | 400 | Exception in json/read | PASS* |
| nil body | 400 | NPE in slurp | FAIL |

*If body is valid

---

## Recommended Fixes

### Fix 1: Handle nil body

```clojure
(defn start-import [request]
  (try
    (let [body-str (if-let [body (:body request)]
                     (slurp body)
                     (throw (ex-info "Request body is required" {})))

```

### Fix 2: Validate required fields

```clojure
(let [body (json/read-value body-str json/keyword-keys-object-mapper)
      symbols-str (:symbols body)
      start-date-str (:startDate body)
      end-date-str (:endDate body)]

  (when (str/blank? symbols-str)
    (throw (ex-info "symbols is required" {:field :symbols})))
  (when (str/blank? start-date-str)
    (throw (ex-info "startDate is required" {:field :startDate})))
  (when (str/blank? end-date-str)
    (throw (ex-info "endDate is required" {:field :endDate})))

  ;; Now safe to parse
  ...)

```

### Fix 3: Consistent error messages

Ensure error messages in handler match what tests expect:

```clojure
(catch DateTimeParseException e
  {:status 400
   :body (json/write-value-as-string
          {:error (str "Date could not be parsed: " (.getMessage e))})})

```

### Fix 4: Update tests OR fix job state management

Either:
- Update tests to match actual job state flow
- Fix job state mutations to happen when expected

---

## Key Code Snippets

### Test mock-request helper (line 23-32)

```clojure
(defn mock-request
  ([overrides]
   (merge {:request-method :get
           :uri "/"
           :headers {}
           :body nil}          ;; <-- Defaults to nil!
          overrides)))

```

### Handler start-import (line 52-86)

```clojure
(defn start-import [request]
  (try
    (let [body-str (slurp (:body request))     ;; <-- FAILS if nil
          ...
          symbols-str (:symbols body)          ;; <-- Can be nil
          start-date-str (:startDate body)     ;; <-- Can be nil
          ...
          start-date (LocalDate/parse start-date-str)  ;; <-- FAILS if nil

```

### Test with missing fields (line 259-264)

```clojure
(testing "Returns 400 for missing required fields"
  (let [request (mock-request
                 {:body (json-body {:symbols "AAPL"})})  ;; Missing dates!
        response (handlers/start-import request)]
    (is (= 400 (:status response)))))

```

---

## Files Involved

| File | Purpose |
|------|---------|
| `src/ml_options/web/handlers.clj` | Handler implementations |
| `src/ml_options/web/jobs.clj` | Job state and start-import! |
| `test/ml_options/web/handlers_test.clj` | All handler tests |

---

## Conclusion

The handler test failures are caused by **missing input validation** in the handlers, not test bugs. The handlers assume valid input and throw NPEs instead of returning proper 400 errors.

**Fix Priority**:
1. Add nil body check
2. Add required field validation with clear error messages
3. Ensure error messages match test expectations
4. Review integration test job state assumptions
