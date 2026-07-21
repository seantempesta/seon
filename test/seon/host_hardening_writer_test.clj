(ns seon.host-hardening-writer-test
  "Pure JVM proofs for W0.6 host-boundary caps and fault recording."
  (:require [clojure.test :refer [deftest is]]
            [seon.ai.tokens :as tokens]
            [seon.db.branch :as branch]
            [seon.error :as error]
            [seon.host.record :as record]))

(defn- counted-seq [seen limit]
  (letfn [(step [index]
            (lazy-seq
             (when (< index limit)
               (swap! seen inc)
               (cons index (step (inc index))))))]
    (step 0)))

(deftest jvm-bounded-printer-stops-before-realizing-a-huge-value
  (let [seen (atom 0)
        result (tokens/bounded-pr-str-result
                (counted-seq seen 1000000) 32)]
    (is (true? (::tokens/character-truncated? result)))
    (is (<= (tokens/estimate (::tokens/text result)) 32))
    (is (< @seen 1000) "printing work stays proportional to the cap")))

(deftest huge-terminal-value-recording-is-bounded-and-work-limited
  (let [seen (atom 0)
        tx-data
        (record/terminal-tx-data
         {:seon.eval/id "huge-terminal"
          ::record/envelope
          {:seon.eval/ok? true
           :seon.eval/value (counted-seq seen 1000000)}
          ::record/at (java.util.Date.)
          ::record/duration-ms 1
          ::record/source "(range 1000000)"
          ::record/narration ""
          ::record/ns-sym 'my.agent.hardening})
        row (second tx-data)
        text (:seon.eval/result-edn row)]
    (is (string? text))
    (is (<= (tokens/estimate text) 2048))
    (is (< @seen 1000) "terminal recording never realizes the whole value")))

(deftest jvm-record-entry-uses-the-shared-sync-hook-and-branch-projection
  (let [persisted (atom [])
        head {::branch/store-id #uuid "ec6d9882-c185-4b6b-a476-3e8ccb5ca751"
              ::branch/name :db
              ::branch/commit-id #uuid "c3ab5d9f-134b-47af-9c12-aa046a102797"
              ::branch/basis-t 536870929}
        throwable (ex-info (apply str (repeat 1000 "failure"))
                           {:seon.error/kind :core-bug})]
    (try
      (error/set-db-hooks!
       {:seon.error/transact!
        (fn [entities]
          (swap! persisted into entities)
          {:seon.db/ok? true})
        :seon.error/branch-head (constantly head)})
      (let [envelope (error/record!
                      {:seon.error/raw throwable :seon.error/fault :core})
            projection (first @persisted)]
        (is (= :core (:seon.error/fault envelope)))
        (is (error/recorded? throwable))
        (is (= :core (:seon.error/fault projection)))
        (is (<= (tokens/estimate (:seon.error/message projection)) 100))
        (is (= (::branch/store-id head) (:seon.error/store-id projection)))
        (is (= (::branch/commit-id head) (:seon.error/commit-id projection)))
        (is (= (::branch/basis-t head) (:seon.error/basis-t projection))))
      (finally (error/set-db-hooks! {})))))
