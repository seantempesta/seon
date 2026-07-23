(ns seon.host-preflight-writer-test
  "JVM-host repair policy and disposable symbol-preflight proofs."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.host.context :as context]
            [seon.host.eval :as host.eval]
            [seon.host.instrument :as instrument]
            [seon.host.preflight :as preflight]
            [seon.host.session :as session]
            [seon.repl.parse.repair :as candidates]
            [seon.schema :as schema]))

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
   :seon.config.repair/max-fixes-per-form 1
   :seon.config.repair/budget-ms 50})

(def ^:private normalized-default-policy
  (assoc default-policy
         :seon.repl.parse.repair/classes
         {:seon.repl.parse.repair/delimiters true
          :seon.repl.parse.repair/def-vs-defn true
          :seon.repl.parse.repair/undeclared-var true}))

(defn- registry-with-candidates []
  (let [registry (context/registry)]
    (context/register-wrappers!
     {::context/registry registry
      ::context/lib 'my.registry
      ::context/wrappers
      {'known {::context/wrapper-fn (constantly :known)}
       'thing-aa {::context/wrapper-fn (constantly :aa)}
       'thing-ab {::context/wrapper-fn (constantly :ab)}}})
    registry))

(defn- batch-session [ctx registry]
  (let [projection (schema/build-projection {} {})
        projection-state
        (atom {::context/database {:db-name "preflight-test" :t 1}
               ::context/projection projection})
        contexts (atom {"preflight" ctx})
        instrument-state
        (instrument/state
         {::context/registry registry
          ::context/projection-state projection-state
          :seon.host/contexts contexts})]
    {::session/ctx ctx
     ::session/writer nil
     ::session/startup (atom {:seon.execution/agent-id "preflight"})
     ::session/interrupt-lock (Object.)
     ::session/interrupt-fired? (atom false)
     ::session/worker-phase (atom :idle)
     ::session/contexts contexts
     ::instrument/state instrument-state}))

(deftest qualified-registry-resolution-has-a-stable-suggestion
  (let [ctx (context-with-known-symbols)
        registry (registry-with-candidates)
        result
        (preflight/preflight!
         ctx registry home-ns home-ns
         (assoc default-policy
                :seon.config.repair.class/undeclared-var? false)
         "(my.registry/thing-ac)")
        error (get-in result [:seon.host.preflight/envelope :seon/error])
        suggestions
        (get-in error
                [:seon.error/data :seon.repl.parse.repair/suggestions])]
    (is (= :terminal (:seon.host.preflight/status result)))
    (is (= :resolution
           (get-in error [:seon.error/data :seon.error.sci/class])))
    (is (= ["my.registry/thing-aa" "my.registry/thing-ab"]
           (mapv :seon.repl.parse.repair/to suggestions)))
    (is (re-find #"Did you mean my\.registry/thing-aa\?"
                 (:seon.error/message error)))))

(deftest qualified-resolution-failure-is-contained-per-form
  (let [ctx (context-with-known-symbols)
        registry (registry-with-candidates)
        result
        (host.eval/eval-batch-result
         (batch-session ctx registry)
         {:seon.eval/parsed
          [{:seon.repl/kind :form :seon.repl/source "(+ 1 2)"}
           {:seon.repl/kind :form
            :seon.repl/source "(my.registry/knwon)"}
           {:seon.repl/kind :form :seon.repl/source "(+ 3 4)"}]
          :seon.eval/starting-ns home-ns}
         (assoc default-policy
                :seon.config.render/database-edn-cap 16384
                :seon.config.repair.class/undeclared-var? false)
         {:db-name "preflight-test" :t 1}
         {})
        results (:seon.host/results result)]
    (is (= 2 (:seon.eval/n-ok result)) (pr-str result))
    (is (= 1 (:seon.eval/n-fail result)) (pr-str result))
    (is (= [] (:seon.eval/ids result)) (pr-str result))
    (is (= 3 (count results)) (pr-str result))
    (is (= [true false true] (mapv :seon.eval/ok? results)))
    (is (= [3 7]
           (mapv :seon.eval/value [(first results) (last results)])))
    (is (= :resolution
           (get-in results
                   [1 :seon/error :seon.error/data
                    :seon.error.sci/class])))
    (is (= "my.registry/known"
           (get-in results
                   [1 :seon/error :seon.error/data
                    :seon.repl.parse.repair/suggestions 0
                    :seon.repl.parse.repair/to])))))

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

(deftest durable-defn-requires-a-complete-contract-before-eval
  (let [ctx (context-with-known-symbols)
        source "(defn uncontracted [value] value)"
        result
        (preflight/preflight!
         ctx nil home-ns home-ns
         (assoc default-policy :seon.config.repair/level :off)
         source)]
    (is (= :terminal (:seon.host.preflight/status result)))
    (is (re-find #"register named data schemas first"
                 (get-in result
                         [:seon.host.preflight/envelope
                          :seon/error :seon.error/message])))
    (is (nil? (sci/resolve ctx 'my.agent.preflight/uncontracted))
        "the terminal preflight envelope leaves the form unexecuted"))
  (let [ctx (context-with-known-symbols)
        result
        (preflight/preflight!
         ctx nil 'user 'user
         (assoc default-policy :seon.config.repair/level :off)
         "(defn scratch-value [value] value)")]
    (is (= :skipped (:seon.host.preflight/status result))
        "the tee's exact scratch namespace set remains exempt")))

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
      (is (= normalized-default-policy (preflight/repair-policy {})))
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
                        :seon.config.repair.class/undeclared-var? false)
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
