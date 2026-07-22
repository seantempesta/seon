(ns seon.host-preflight-writer-test
  "JVM-host repair policy and disposable symbol-preflight proofs."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.host.preflight :as preflight]
            [seon.repl.parse.repair :as candidates]))

(defn- context-with-known-symbols []
  (let [ctx (sci/init {})]
    (sci/eval-string*
     ctx
     (str "(ns my.agent.preflight)"
          "(defn known [] :known)"
          "(defn thing-aa [] :aa)"
          "(defn thing-ab [] :ab)"
          "(defn known-one [] 1)"
          "(defn known-two [] 2)"))
    ctx))

(def ^:private home-ns 'my.agent.preflight)

(def ^:private default-policy
  {:seon.config.repair/level :symbols
   :seon.config.repair/classes {}
   :seon.config.repair/max-fixes-per-form 1
   :seon.config.repair/budget-ms 50})

(deftest pick-winner-is-synchronous-on-the-jvm
  (let [near {:seon.repl.parse.repair/to "near" :seon.repl.parse.repair/distance 1}
        peer {:seon.repl.parse.repair/to "peer" :seon.repl.parse.repair/distance 1}
        far {:seon.repl.parse.repair/to "far" :seon.repl.parse.repair/distance 2}
        seen (atom [])
        choose (fn [passing]
                 (candidates/pick-winner
                  {:seon.repl.parse.repair/cands [near peer far]
                   :seon.repl.parse.repair/over? (constantly false)
                   :seon.repl.parse.repair/passes?
                   (fn [candidate]
                     (swap! seen conj (:seon.repl.parse.repair/to candidate))
                     (contains? passing (:seon.repl.parse.repair/to candidate)))}))]
    (is (= near (:seon.repl.parse.repair/winner (choose #{"near"}))))
    (is (= ["near" "peer"] @seen)
        "the farther tier is never trialled")
    (reset! seen [])
    (is (= [near peer] (:seon.repl.parse.repair/ambiguous
                        (choose #{"near" "peer"}))))
    (is (= ["near" "peer"] @seen))))

(deftest disposable-analysis-never-mutates-the-retained-context
  (let [ctx (context-with-known-symbols)
        result (preflight/analyze-disposable!
                ctx home-ns "(defn disposable-definition [] 42)")]
    (is (true? (:seon.host.preflight/ok? result)))
    (is (nil? (sci/resolve ctx 'my.agent.preflight/disposable-definition)))))

(deftest delimiter-repair-emits-owner-defined-changes-and-exact-source
  (let [ctx (context-with-known-symbols)
        repaired (preflight/repair-read-entry
                  ctx home-ns default-policy
                  {:seon.repl/kind :read
                   :seon.repl/ok? false
                   :seon.repl/source "(+ 1 2"})
        entry (first (:seon.host.preflight/entries repaired))]
    (is (= "(+ 1 2)" (:seon.repl/source entry)))
    (is (seq (:seon.repl.parse.repair/changes entry)))
    (is (nil? (:seon.repl.parse.repair/fixes entry)))
    (is (re-find #"auto-balanced" (:seon.repl/narration entry)))))

(deftest repair-policy-table-controls-the-host-tier
  (let [ctx (context-with-known-symbols)
        run (fn [configuration source]
              (preflight/preflight!
               ctx nil home-ns home-ns configuration source))]
    (testing "absent configuration uses the owner defaults"
      (is (= default-policy (preflight/repair-policy {})))
      (is (= :fixed (:seon.host.preflight/status (run {} "(knwon)")))))

    (testing ":off disables delimiter and symbol repair"
      (is (= :skipped
             (:seon.host.preflight/status
              (run (assoc default-policy :seon.config.repair/level :off)
                   "(knwon)"))))
      (is (nil? (preflight/repair-read-entry
                 ctx home-ns
                 (assoc default-policy :seon.config.repair/level :off)
                 {:seon.repl/kind :read :seon.repl/source "(+ 1 2"}))))

    (testing "a disabled symbol class never applies a candidate"
      (let [result
            (run (assoc default-policy
                        :seon.config.repair/classes
                        {:seon.repl.parse.repair/undeclared-var false})
                 "(knwon)")]
        (is (= :terminal (:seon.host.preflight/status result)))
        (is (nil? (:seon.repl.parse.repair/fixes result)))))

    (testing "max-fixes terminalizes a still-unresolved chained typo"
      (let [result (run default-policy "(vector (knwon-one) (knwon-two))")]
        (is (= :terminal (:seon.host.preflight/status result)))))

    (testing "budget exhaustion refuses instead of evaluating"
      (let [original (var-get #'preflight/analyze-disposable!)]
        (with-redefs-fn
          {#'preflight/analyze-disposable!
           (fn [& arguments]
             (let [result (apply original arguments)]
               (Thread/sleep 5)
               result))}
          (fn []
            (is (= :terminal
                   (:seon.host.preflight/status
                    (run (assoc default-policy
                                :seon.config.repair/budget-ms 1)
                         "(knwon)"))))))))))
