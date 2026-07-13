(ns seon.instrument-delta-test
  "Behavioral proof for exact boot and incremental instrumentation data."
  (:require
    [cljs.test :refer [deftest is testing]]
    [goog.object :as gobj]
    [malli.core :as m]
    [seon.db :as db]
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

(defn- set-live-fn! [sym f]
  (let [ns-object (js/goog.getObjectByName
                    (cljs.core/munge (namespace sym)) js/goog.global)]
    (gobj/set ns-object (cljs.core/munge (name sym)) f)))

(deftest exact-data-and-delta-refresh-only-affected-wrappers
  (let [function-schemas-before (m/function-schemas :cljs)
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

      (testing "explicit data never becomes a second process-global registry"
        (is (= function-schemas-before (m/function-schemas :cljs))))
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

(deftest reload-refresh-instruments-only-new-live-gaps-once
  (let [schema-form [:=> [:cat :int] :int]
        rows [[(str a-sym) (pr-str schema-form)]
              [(str b-sym) (pr-str schema-form)]]
        a-base (live-fn a-sym)
        b-base (live-fn b-sym)]
    (try
      (instrument/instrument-targets!
        [(target a-sym schema-form) (target b-sym schema-form)])
      (when (instrument/enabled?)
        (let [b-wrapper (live-fn b-sym)]
          ;; Model Shadow replacing exactly one compiled var with a fresh raw
          ;; function. Its after-load hook does not tell application hooks
          ;; which source files were imported.
          (set-live-fn! a-sym (fn [x] x))
          (with-redefs [db/query (fn [& _] rows)]
            (let [first-refresh (instrument/refresh-live-coverage! ::fake-db)
                  a-wrapper (live-fn a-sym)]
              (is (= 2 (:seon.instrument/n-scanned first-refresh)))
              (is (= 1 (:seon.instrument/n-gaps first-refresh)))
              (is (= 1 (:seon.instrument/n-instrumented first-refresh)))
              (is (trapped? #(probe-a "wrong"))
                  "the freshly emitted raw function is wrapped again")
              (is (identical? b-wrapper (live-fn b-sym))
                  "the healthy peer wrapper is never replaced")
              (let [second-refresh
                    (instrument/refresh-live-coverage! ::fake-db)]
                (is (= 0 (:seon.instrument/n-gaps second-refresh)))
                (is (= 0 (:seon.instrument/n-instrumented second-refresh)))
                (is (identical? a-wrapper (live-fn a-sym))
                    "one definition is instrumented once, not on every scan")
                (is (identical? b-wrapper (live-fn b-sym))))))))
      (finally
        (instrument/instrument-delta!
          {::instrument/changed-syms target-syms
           ::instrument/targets []})
        (set-live-fn! a-sym a-base)
        (set-live-fn! b-sym b-base)))))
