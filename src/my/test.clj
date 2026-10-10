(ns ^{:seon.ns/context-relevant? true} my.test
  "Run the tests declared in my namespace."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [my.program]
            [seon.db :as db]
            [seon.error.refusal]
            [seon.test]
            [seon.test.platform :as platform]))

(defn- check-now
  "Run the tests named in `tests`, else those reaching `changed` (else my program's change), as one request;
  a change no test reaches names it unreached. A named test always runs or answers its recorded result, unless
  `rerun?` asks it to run again."
  {:malli/schema [:=> [:cat :my.program/context :seon.db/connection [:or :nil :seon.test/changed]
                       [:or :nil [:vector :seon.test/sym]] :boolean]
                  [:or :seon.test/run-result :my.test/unreached-result :seon.error/value]]}
  [context connection changed tests rerun?]
  (if (seq tests)
    (seon.test/run (cond-> {:seon.test/execution context :seon.test/recording-connection connection
                            :seon.test/policy :named :seon.test/identities (set tests)}
                     rerun? (assoc :seon.test/rerun? true)))
    (let [changed (or (not-empty changed) (seon.test/changes context connection))]
      (if (:seon.error/at changed)
        changed
        (let [result (seon.test/run (cond-> {:seon.test/execution context
                                             :seon.test/recording-connection connection
                                             :seon.test/policy :named
                                             :seon.test/changed (vec changed)}
                                      rerun? (assoc :seon.test/rerun? true)))]
          (if (and (nil? (:seon.error/at result))
                   (empty? (:seon.test/results result))
                   (not-any? result [:seon.test/pending :seon.test/unfinished :seon.test/recording-refusal
                                     :seon.test/destructive-excluded :seon.test/deferred
                                     :seon.test/long-excluded]))
            (-> result (dissoc :seon.test/passed?) (assoc :seon.test/unreached (vec changed)))
            result))))))

(defn check
  "Check the tests reaching my change as one request and return its answer.

  My change is `:seon.test/changed` when I name it, otherwise every
  declaration my program holds at another digest than my base: the commit my
  branch was forked at, or on the cluster's own branch the published program
  (`seon.test/changes`). Tests I name under `:seon.test/identities` run
  themselves instead, declared-long ones included; an unchanged green one
  answers its recorded result. My acquired context and
  connection are supplied by call preparation. Each reaching test runs on its
  own branch off my current commit, isolated from my writes and from the
  other tests; its result is recorded on my connection. `:seon.test/rerun? true`
  runs them again instead of answering recorded results. `:seon.test/passed?`
  is true only when every reaching test has green evidence. Failure data
  names the test and its claim. A name an earlier form of this same request
  defined has no facts until the request records: the answer lists it under
  `:my.test/after-recording`, and the pass's test step runs its tests right
  after recording, in the same reply.

  Example:
  (my.test/check {:seon.test/changed ['my.turn/complete]})"
  {:malli/schema [:=> [:cat :my.test/check-request]
                  [:or :seon.test/run-result :my.test/unreached-result :my.test/after-recording-result
                   :seon.error/value]]}
  [{context :my.program/context connection :seon.db/connection defined :seon.batch/defined :as request}]
  (let [tests (some-> (:seon.test/identities request) sort vec)
        named (or tests (:seon.test/changed request))
        after (filterv (or defined #{}) named)
        now (vec (remove (set after) named))
        [changed tests] (if tests [nil now] [now nil])
        rerun? (true? (:seon.test/rerun? request))]
    (cond (empty? after) (check-now context connection changed tests rerun?)
          (empty? now) {:my.test/after-recording after}
          :else (assoc (check-now context connection changed tests rerun?) :my.test/after-recording after))))

(defn check-platform
  "Prove my branch's host-bound change in a JVM loaded from it and record the
  result on my branch as ordinary test evidence.

  My branch's declarations (or the `:seon.program/identities` I name) are
  written into a copy of the published archive, committed on top of the
  published commit, and the tests (`:seon.test/identities`, else those
  reaching my change) run by `bin/seon check --at` in a throwaway JVM. Their
  results are recorded on my branch keyed by its reach digests, the run naming
  the candidate commit, so `bin/lane commit --branch-evidence` accepts my
  host-bound change until my branch changes again. Synchronous: it takes the
  throwaway JVM's boot plus the tests.

  Example:
  (my.test/check-platform {:seon.program/identities #{[:seon.fn/sym 'seon.test/tally]}})"
  {:malli/schema [:=> [:cat :my.test/check-platform-request] [:or :seon.test.platform/answer [:map [:seon.test/unreached :seon.test/unreached]] :seon.error/value]]}
  [{context :my.program/context connection :seon.db/connection
    tests :seon.test/identities identities :seon.program/identities}]
  (let [began (System/nanoTime)
        tested (db/db connection)
        published (platform/published-source)
        changed (when-not (:seon.error/at published)
                  (or (some->> identities (keep #(when (= :seon.fn/sym (first %)) (second %))) vec)
                      (seon.test/changes context connection)))
        tests (cond (seq tests) tests
                    (:seon.error/at changed) changed
                    (seq changed) (seon.test/reaching {:seon.db/db tested :seon.test/changed (vec changed)}))]
    (cond
      (:seon.error/at published) published
      (nil? (:seon.cluster/name context))
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.test/platform 'my.test/check-platform
                                     {:seon.error/message "My context names no cluster to record the platform run in."})
      (:seon.error/at changed) changed
      (:seon.error/at tests) tests
      (empty? tests) {:seon.test/unreached (vec changed)}
      :else
      (let [snapshot (platform/snapshot! tested published (str (io/file (:seon.test.platform/checkout published) "tmp")))]
        (try
          (let [written (my.program/write-branch! (cond-> {:my.program/context context :seon.fn/root snapshot}
                                                    identities (assoc :seon.program/identities identities)))
                paths (:seon.source/changed-paths written)
                candidate (when (seq paths) (platform/candidate-commit! published snapshot paths))
                checked (when candidate (platform/run-check! tested published candidate snapshot (set tests)))
                recorded (when (and checked (not (:seon.error/at checked))) (platform/record! connection tested (:seon.cluster/name context) checked))]
            (cond
              (not (seq paths)) written
              (:seon.error/at checked) checked
              (:seon.error/at recorded) recorded
              :else (cond-> {:seon.test.run/git-sha candidate
                             :seon.source/changed-paths paths
                             :seon.test/passed? (:seon.test/passed? checked)
                             :seon.test/results recorded
                             :seon.test.platform/check-ms (:seon.test.platform/check-ms checked)
                             :seon.test.platform/ms (long (/ (- (System/nanoTime) began) 1e6))}
                      (seq (:seon.test.platform/excluded checked))
                      (assoc :seon.test.platform/excluded (:seon.test.platform/excluded checked)))))
          (finally (fs/delete-tree snapshot)))))))

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
