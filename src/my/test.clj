(ns ^{:seon.ns/context-relevant? true} my.test
  "Run the tests declared in my namespace."
  (:require [seon.test]))

(defn check
  "Check the tests reaching my change as one request and return its answer.

  My acquired context and connection are supplied by call preparation. Each
  reaching test runs on its own branch off my current commit, isolated from my
  writes and from the other tests; its result is recorded on my connection.
  `:seon.test/passed?` is true only when every reaching test has green
  evidence. Failure data names the test and its claim.

  Example:
  (my.test/check {:seon.test/changed ['my.note/add!]})"
  {:malli/schema [:=> [:cat :my.test/check-request]
                  [:or :seon.test/run-result :seon.error/value]]}
  [{context :my.program/context connection :seon.db/connection :as request}]
  (seon.test/run
   (cond-> {:seon.test/execution context
            :seon.test/recording-connection connection
            :seon.test/policy :incremental}
     (seq (:seon.test/changed request))
     (assoc :seon.test/changed (vec (:seon.test/changed request))))))

(defn run-owned
  "Run my namespace's declared tests as one named request.
  Returns each test's result; [] means none are declared."
  {:malli/schema [:=> [:cat :my.test/run-request]
                  [:or :seon.test/results :seon.error/value]]}
  [{context :my.program/context connection :seon.db/connection :as request}]
  (let [symbols (seon.test/owned-symbols request)]
    (if (empty? symbols)
      []
      (let [result (seon.test/run {:seon.test/execution context
                                   :seon.test/recording-connection connection
                                   :seon.test/policy :named
                                   :seon.test/identities (set symbols)})]
        (if (:seon.error/at result) result (:seon.test/results result))))))

(defmacro run
  "Run my namespace's declared tests and store their results.

  Takes an optional request map; my context, connection, database and agent
  identity are supplied. Returns a vector of :seon.test/sym,
  :seon.test/pass-count, :seon.test/fail-count and :seon.test/error-count
  maps. [] means no tests are declared; it is not evidence that any test
  passed.

  My tests run as isolated agents: each gets its own branch off my current
  commit, runs there, and its branch is unlinked after its body exits. Its
  result is recorded on my connection. An unchanged green test returns its
  recorded result with :seon.test/unchanged and no execution.

  Example:
  (my.test/run)"
  ([] (list 'my.test/run {}))
  ([request]
   `(my.test/run-owned ~request)))
