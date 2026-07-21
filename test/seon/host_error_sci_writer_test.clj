(ns seon.host-error-sci-writer-test
  "Structural SCI error classification at the JVM host boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [sci.interrupt :as interrupt]
            [seon.ai.tokens :as tokens]
            [seon.error.instrument :as instrument]
            [seon.error.sci :as error.sci]))

(def ^:private home-ns 'my.agent.hostile)

(defn- request
  [ctx throwable]
  {::error.sci/throwable throwable
   ::error.sci/context ctx
   ::error.sci/home-ns home-ns})

(defn- thrown-by
  [ctx source]
  (try
    (sci/eval-string* ctx source)
    (throw (ex-info "Expected SCI evaluation to throw."
                    {:seon.error/kind :core-bug}))
    (catch Throwable throwable
      throwable)))

(defn- classified-source
  [ctx source]
  (let [throwable (thrown-by ctx source)]
    [throwable (error.sci/classify (request ctx throwable))]))

(defn- error-data
  [classified]
  (:seon.error/data classified))

(deftest hostile-sci-errors-classify-from-producer-data
  (let [ctx (sci/init {})]
    (sci/eval-string*
     ctx
     "(ns my.agent.hostile) (defn total [row] row)")

    (testing "unresolved symbols carry the SCI symbol and ranked candidates"
      (let [[_throwable classified]
            (classified-source
             ctx "(in-ns 'my.agent.hostile) (totl {:amount 1})")
            data (error-data classified)]
        (is (= :resolution (::error.sci/class data)))
        (is (= 'totl (::error.sci/symbol data)))
        (is (int? (::error.sci/line data)))
        (is (seq (:seon.repair/suggestions data)))
        (is (= "my.agent.hostile/total"
               (:seon.repair/to (first (:seon.repair/suggestions data)))))))

    (testing "wrong arity uses ArityException and the var's arglists metadata"
      (let [[_throwable classified]
            (classified-source ctx "(in-ns 'my.agent.hostile) (total)")
            data (error-data classified)]
        (is (= :arity (::error.sci/class data)))
        (is (= 'my.agent.hostile/total (::error.sci/symbol data)))
        (is (= 0 (:seon.error.malli/arity data)))
        (is (= #{{:min 1 :max 1}}
               (:seon.error.malli/arities data)))))

    (testing "built-in root mutation refusal carries the refused SCI var"
      (let [[_throwable classified]
            (classified-source
             ctx "(alter-var-root (var clojure.core/+) (fn [_] 1))")
            data (error-data classified)]
        (is (= :refusal (::error.sci/class data)))
        (is (= 'clojure.core/+ (::error.sci/refused-var data)))))

    (testing "plain runtime throws carry normalized public SCI frames"
      (let [[throwable classified]
            (classified-source
             ctx "(in-ns 'my.agent.hostile) (defn ratio [x] (/ x 0)) (ratio 4)")
            data (error-data classified)
            frames (::error.sci/callstack-head data)
            detail (error.sci/detail (request ctx throwable))]
        (is (= :runtime (::error.sci/class data)))
        (is (<= (count frames) 3))
        (is (every? map? frames))
        (is (some ::error.sci/fn-sym frames))
        (is (= :runtime (::error.sci/class detail)))
        (is (seq (::error.sci/frames detail)))
        (is (seq (::error.sci/ex-chain detail)))))))

(deftest deadline-interrupt-classifies-by-identical-marker
  (let [ctx (sci/init
             {:namespaces {'clojure.core interrupt/clojure-core
                           'clojure.string interrupt/clojure-string}
              :interrupt-fn
              (fn []
                (when (.isInterrupted (Thread/currentThread))
                  (interrupt/interrupt! "deadline fired"
                                        {:seon.error/kind :timeout})))})
        outcome (promise)
        worker (Thread.
                (fn []
                  (deliver outcome
                           (try
                             (sci/eval-string* ctx "(reduce + (range))")
                             :unexpected-success
                             (catch Throwable throwable throwable)))))]
    (.start worker)
    (Thread/sleep 25)
    (.interrupt worker)
    (let [throwable (deref outcome 2000 ::timed-out)]
      (is (instance? Throwable throwable))
      (when (instance? Throwable throwable)
        (let [classified (error.sci/classify (request ctx throwable))
              data (error-data classified)]
          (is (= :interrupt (::error.sci/class data)))
          (is (= :timeout (:seon.error/kind data)))
          (is (not (contains? data :sci.impl/interrupt))))))))

(deftest malli-envelope-is-preserved-with-only-the-sci-class-added
  (let [ctx (sci/init {})
        throwable
        (try
          (instrument/report-fn
           :malli.core/invalid-input
           {:input [:cat :int]
            :args ["not-an-int"]
            :schema [:=> [:cat :int] :int]
            :fn-name 'my.agent.hostile/accept-int})
          (catch Throwable throwable throwable))
        producer-data (ex-data throwable)
        classified (error.sci/classify (request ctx throwable))
        data (error-data classified)]
    (is (= :schema-input (::error.sci/class data)))
    (is (= producer-data (dissoc data ::error.sci/class)))
    (is (instrument/instrument-error? data))))

(deftest steering-head-obeys-the-named-token-budget
  (let [ctx (sci/init {})
        [_throwable classified]
        (classified-source ctx "missing-with-a-deliberately-long-name")
        budget 8
        head (error.sci/steering-head classified budget)]
    (is (string? head))
    (is (<= (tokens/estimate head) budget))))
