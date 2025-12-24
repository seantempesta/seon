# Test Utilities

Location: `test/ml_options/test_utils.clj`

## XTDB Fixtures

### Per-Test Database

```clojure
(use-fixtures :each tu/with-xtdb)

(deftest my-db-test
  ;; Fresh XTDB node available via tu/*node*
  (let [node tu/*node*]
    (xt/submit-tx node [[:put-docs :my-table {:xt/id "1" :data "test"}]])
    (is (= 1 (count (node/query node '(from :my-table [xt/id])))))))
```

### Shared Database (Once per Namespace)

```clojure
(use-fixtures :once tu/with-xtdb-once)

;; All tests share the same node - faster but tests may interfere
```

## Time Helpers

### Fixed Time

```clojure
(tu/with-fixed-time #inst "2024-06-15T10:00:00Z"
  ;; java.time.Instant/now returns fixed time
  (is (= #inst "2024-06-15T10:00:00Z" (java.time.Instant/now))))
```

### Time Assertions

```clojure
(tu/assert-within-ms expected actual tolerance-ms)
;; Asserts timestamps are within tolerance
```

## Request Helpers

### JSON Body Request

```clojure
(defn json-body [data]
  (io/input-stream (.getBytes (json/write-str data))))

(defn json-request [body-map]
  {:body (json-body body-map)
   :headers {"content-type" "application/json"}})
```

### Form Request

```clojure
(defn form-request [params]
  {:form-params params
   :headers {"content-type" "application/x-www-form-urlencoded"}})
```

## Assertion Helpers

### Deep Equality with Tolerance

```clojure
(tu/assert-maps-equal expected actual :ignore-keys [:timestamp :id])
```

### Collection Assertions

```clojure
(tu/assert-contains-all expected-items actual-coll)
(tu/assert-count n coll)
```

## Generator Helpers

```clojure
(require '[ml-options.generators :as gen])

;; Generate single sample
(gen/sample gen/gen-option-greeks)

;; Generate n samples
(gen/sample gen/gen-option-greeks 10)

;; Property-based testing
(defspec greeks-always-valid 100
  (prop/for-all [greeks gen/gen-option-greeks]
    (m/validate OptionGreeksSchema greeks)))
```

## Common Test Patterns

### Setup/Teardown

```clojure
(deftest with-setup-test
  (let [test-data (setup-test-data)]
    (try
      (is (valid? (process test-data)))
      (finally
        (cleanup test-data)))))
```

### Async Testing

```clojure
(deftest async-operation-test
  (let [result (promise)]
    (start-async-op #(deliver result %))
    (is (= expected (deref result 5000 :timeout)))))
```
