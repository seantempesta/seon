# Mocking Patterns

## System Component Mocking

Handlers often call system components that need mocking in tests.

### Common Components to Mock

| Component | Namespace | Mock Pattern |
|-----------|-----------|--------------|
| XTDB node | `jobs/get-node` | `(constantly mock-node)` |
| Job state | `jobs/get-state` | `(constantly (atom {}))` |
| Current time | `java.time.Instant/now` | Use `test-utils/with-fixed-time` |

### Basic with-redefs Pattern

```clojure
(deftest my-handler-test
  (with-redefs [jobs/get-node (constantly mock-node)
                jobs/get-state (constantly (atom {:status :idle}))]
    (let [response (handler request)]
      (is (= 200 (:status response))))))
```

### Creating Mock XTDB Node

```clojure
(require '[ml-options.test-utils :as tu])

;; Use the XTDB fixture for real database
(use-fixtures :each tu/with-xtdb)

;; Or create a minimal mock
(def mock-node
  (reify xtdb.api/PXtdb
    (query [_ q] [])
    (submit-tx [_ tx] {:tx-id 1})))
```

### Mocking HTTP Requests

```clojure
(defn json-request [body-map]
  {:body (io/input-stream (.getBytes (json/write-str body-map)))
   :headers {"content-type" "application/json"}})

(defn mock-request [method path]
  {:request-method method
   :uri path})
```

## Time Mocking

For time-sensitive tests:

```clojure
(require '[ml-options.test-utils :as tu])

(deftest time-dependent-test
  (tu/with-fixed-time #inst "2024-06-15T10:00:00Z"
    ;; All calls to Instant/now return the fixed time
    (is (= expected (compute-something)))))
```

## Async/Future Mocking

For handlers that spawn futures:

```clojure
;; Option 1: Mock to run synchronously
(with-redefs [future-call (fn [f] (f) (delay nil))]
  (handler request))

;; Option 2: Use promise for coordination
(let [done (promise)]
  (with-redefs [complete-callback (fn [_] (deliver done true))]
    (handler request)
    (is (deref done 1000 false))))
```

## State Atom Mocking

```clojure
(deftest stateful-handler-test
  (let [test-state (atom {:status :idle})]
    (with-redefs [jobs/get-state (constantly test-state)]
      (handler start-request)
      (is (= :running (:status @test-state)))
      (handler stop-request)
      (is (= :stopped (:status @test-state))))))
```
