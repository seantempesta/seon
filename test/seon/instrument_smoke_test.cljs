(ns seon.instrument-smoke-test
  "Focused proof that current eval/render schemas are live instrumentation."
  (:require
   [cljs.test :refer [deftest is use-fixtures]]
   [malli.core :as m]
   [seon.config :as config]
   [seon.error.instrument :as error.instrument]
   [seon.eval :as eval]
   [seon.instrument :as instrument]
   [seon.render.value :as value]))

(def ^:private configuration (config/resolve-config-singleton {}))

(def ^:private target-vars
  [['seon.render.value/sample #'value/sample]
   ['seon.render.value/render-ai #'value/render-ai]
   ['seon.eval/cap-edn #'eval/cap-edn]
   ['seon.eval/render-result-edn #'eval/render-result-edn]])

(def ^:private target-syms (into #{} (map first) target-vars))

(def ^:private targets
  (mapv
   (fn [[sym var]]
     {::instrument/sym sym
      ::instrument/schema-form (:malli/schema (meta var))})
   target-vars))

(def ^:private function-schemas-before (m/function-schemas :cljs))
(def ^:private !instrumentation (atom nil))

(defn- instrument-targets! []
  (reset! !instrumentation
          (instrument/instrument-delta!
           {::instrument/changed-syms target-syms
            ::instrument/targets targets})))

(defn- uninstrument-targets! []
  (instrument/instrument-delta!
   {::instrument/changed-syms target-syms
    ::instrument/targets []}))

(use-fixtures :once
  {:before instrument-targets!
   :after uninstrument-targets!})

(defn- instrumentation-error [thunk]
  (try
    (thunk)
    nil
    (catch :default error
      (when (error.instrument/instrument-error? (ex-data error))
        (ex-data error)))))

(deftest current-targets-are-instrumented-from-their-real-schemas
  (is (true? (instrument/enabled?)))
  (is (= target-syms (::instrument/accepted-syms @!instrumentation)))
  (is (= [] (::instrument/rejected @!instrumentation)))
  (is (= function-schemas-before (m/function-schemas :cljs))
      "exact-data instrumentation does not create a second schema registry"))

(deftest current-invalid-inputs-hit-the-live-wrapper
  (let [sample-error
        (instrumentation-error #(value/sample configuration 42 nil))
        cap-error
        (instrumentation-error #(eval/cap-edn "result" :not-an-int))]
    (is (= 'seon.render.value/sample
           (:seon.error.malli/fn-sym sample-error)))
    (is (= 'seon.eval/cap-edn
           (:seon.error.malli/fn-sym cap-error)))
    (is (error.instrument/instrument-error? sample-error))
    (is (error.instrument/instrument-error? cap-error))))

(deftest current-configuration-aware-render-and-eval-calls-stay-valid
  (let [wide (vec (range 100))
        sampled (value/sample configuration wide {:max-items 8})
        rendered (value/render-ai configuration "smoke-render" wide)
        result-edn (eval/render-result-edn
                    configuration "smoke-eval" {:seon.test/value wide})
        capped (eval/cap-edn "result" 3)]
    (is (map? sampled))
    (is (string? rendered))
    (is (string? result-edn))
    (is (string? capped))))
