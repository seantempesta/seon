(ns ^{:seon.ns/context-relevant? true} my.test
  "Run the tests declared in my namespace."
  (:require [seon.db :as db]
            [seon.test]))

(defn check
  "Check the tests reaching my change as one request and return its answer.

  My change is `:seon.test/changed` when I name it, otherwise every
  declaration my program holds at another digest than the published program
  my tests start from (`seon.test/changes`). My acquired context and
  connection are supplied by call preparation. Each reaching test runs on its
  own branch off my current commit, isolated from my writes and from the
  other tests; its result is recorded on my connection. `:seon.test/passed?`
  is true only when every reaching test has green evidence. Failure data
  names the test and its claim.

  Example:
  (my.test/check {:seon.test/changed ['my.turn/complete]})"
  {:malli/schema [:=> [:cat :my.test/check-request]
                  [:or :seon.test/run-result :seon.error/value]]}
  [{context :my.program/context connection :seon.db/connection :as request}]
  (let [changed (or (not-empty (:seon.test/changed request)) (seon.test/changes context connection))]
    (if (:seon.error/at changed)
      changed
      (seon.test/run {:seon.test/execution context
                      :seon.test/recording-connection connection
                      :seon.test/policy :named
                      :seon.test/changed (vec changed)}))))

(defn run-owned
  "Run one namespace's declared tests as one named request.
  The namespace is :seon.ns/name, else my assigned namespace.
  Returns each test's result; [] means none are declared.

  Example:
  (my.test/run-owned {})"
  {:malli/schema [:=> [:cat :my.test/run-request]
                  [:or :seon.test/results :seon.error/value]]}
  [{context :my.program/context connection :seon.db/connection
    database :seon.db/db namespace-name :seon.ns/name :as request}]
  (let [symbols (if namespace-name
                  (vec (sort (db/q '[:find [?symbol ...] :in $ ?name
                                     :where [?namespace :seon.ns/name ?name]
                                            [?test :seon.test/ns ?namespace]
                                            [?test :seon.test/sym ?symbol]]
                                   database namespace-name)))
                  (seon.test/owned-symbols request))]
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
  identity are supplied. The namespace is :seon.ns/name, else *ns*. Returns a vector of :seon.test/sym,
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
   `(my.test/run-owned (merge {:seon.ns/name (ns-name *ns*)} ~request))))
