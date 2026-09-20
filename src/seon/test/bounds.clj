(ns seon.test.bounds
  "Declared test work bounds and explicit orchestrator override admission."
  (:require [clojure.string :as str]))

(def fixture-priming-ms
  "Largest measured worker priming cost, 2026-09-16: 19,760 ms.
   See docs/prds/steward-platform/research/test-preparation-costs-2026-09-16.md.
   Carried separately from test body allowances; never inferred from prose."
  19760)

(def ordinary-exchange-seconds 270)
(def reporter-grace-seconds 30)

(defn exchange-seconds
  "Bound declared body work plus measured fixture preparation."
  {:malli/schema [:=> [:cat [:int {:min 0}] [:int {:min 0}]] [:int {:min 1}]]}
  [long-ms preparation-ms]
  (+ (max ordinary-exchange-seconds (quot (+ long-ms 999) 1000))
     (quot (+ preparation-ms 999) 1000)))

(defn silence-seconds
  "Admit an explicit orchestrator override; lane identity always wins.
   The ordinary horizon includes measured priming and reporter settlement."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:int {:min 1}]]}
  [environment]
  (let [declared (+ (exchange-seconds 0 fixture-priming-ms) reporter-grace-seconds)
        configured (get environment "SEON_TEST_SILENCE_SECONDS")]
    (if (nil? configured)
      declared
      (do
        (when (or (not (str/blank? (get environment "SEON_CODEX_LANE")))
                  (not= "1" (get environment "SEON_TEST_ORCHESTRATOR")))
          (throw (ex-info
                  (str "SEON_TEST_SILENCE_SECONDS refused: declared silence bound " declared
                       "s; :seon.test/long with :seon.test/long-ms and measured fixture priming raise it; override requires SEON_TEST_ORCHESTRATOR=1 without lane identity.")
                  {::override-variable "SEON_TEST_SILENCE_SECONDS"
                   ::required-authority "SEON_TEST_ORCHESTRATOR=1 without lane identity"})))
        (let [seconds (try (Long/parseLong configured)
                           (catch NumberFormatException _ 0))]
          (when-not (pos? seconds)
            (throw (ex-info "SEON_TEST_SILENCE_SECONDS must be a positive integer."
                            {::override-variable "SEON_TEST_SILENCE_SECONDS"
                             ::offending-override configured})))
          (max declared seconds))))))
