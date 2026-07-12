(ns seon.instrument-delta-test
  "Behavioral proof for exact boot and incremental instrumentation data."
  (:require
    [cljs.test :refer [deftest is testing]]
    [goog.object :as gobj]
    [malli.core :as m]
    [seon.instrument :as instrument]))

(defn probe-a [x] x)
(defn probe-b [x] x)

(def ^:private a-sym 'seon.instrument-delta-test/probe-a)
(def ^:private b-sym 'seon.instrument-delta-test/probe-b)
(def ^:private target-syms #{a-sym b-sym})

(defn- target [sym schema-form]
  {::instrument/sym sym ::instrument/schema-form schema-form})

(defn- trapped? [thunk]
  (try
    (thunk)
    false
    (catch :default _ true)))

(defn- live-fn [sym]
  (instrument/find-js-var (symbol (namespace sym)) (symbol (name sym))))

(deftest exact-data-and-delta-refresh-only-affected-wrappers
  (let [roster-before (m/function-schemas :cljs)
        initial [(target a-sym [:=> [:cat :int] :int])
                 (target b-sym [:=> [:cat :int] :int])]]
    (try
      (testing "cold instrumentation uses the complete supplied set"
        (let [result (instrument/instrument-targets! initial)]
          (when (:seon.instrument/enabled? result)
            (is (= target-syms (:seon.instrument/accepted-syms result)))
            (is (= 2 (:seon.instrument/n-instrumented result)))
            (is (= [] (:seon.instrument/rejected result)))
            (is (= 1 (probe-a 1)))
            (is (trapped? #(probe-a "wrong")))
            (is (trapped? #(probe-b "wrong"))))))

      (when (instrument/enabled?)
        (let [a-before (live-fn a-sym)
              b-before (live-fn b-sym)
              a-original (gobj/get a-before "malli$instrument$original")]
          (testing "one changed contract is refreshed without touching peers"
            (is (some? a-original))
            (is (nil? (gobj/get a-original "malli$instrument$original"))
                "wrapper depth is exactly one")
            (let [result
                  (instrument/instrument-delta!
                    {::instrument/changed-syms #{a-sym}
                     ::instrument/targets
                     [(target a-sym [:=> [:cat :string] :string])]})]
              (is (= 1 (:seon.instrument/n-unstrumented result)))
              (is (= 1 (:seon.instrument/n-instrumented result)))
              (is (= "ok" (probe-a "ok")))
              (is (trapped? #(probe-a 1)))
              (is (identical? b-before (live-fn b-sym))
                  "unaffected wrapper identity is stable")
              (is (not (identical? a-before (live-fn a-sym))))))

          (testing "spec removal restores the original function"
            (instrument/instrument-delta!
              {::instrument/changed-syms #{a-sym}
               ::instrument/targets []})
            (is (= :unspecced (probe-a :unspecced)))
            (is (nil? (gobj/get (live-fn a-sym)
                                "malli$instrument$original"))))))

      (testing "explicit data never becomes a second process-global roster"
        (is (= roster-before (m/function-schemas :cljs))))
      (finally
        (instrument/instrument-delta!
          {::instrument/changed-syms target-syms
           ::instrument/targets []})))))

(deftest unresolved-target-is-data-not-a-partial-wrapper
  (let [result
        (instrument/prepare-targets
          [(target a-sym [:=> [:cat :int] :missing/schema])])]
    (is (= #{} (:seon.instrument/accepted-syms result)))
    (is (= [{:seon.instrument/sym a-sym
             :seon.instrument/reason
             :seon.instrument/unresolvable-schema}]
           (:seon.instrument/rejected result)))))
