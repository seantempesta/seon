(ns seon.agent.web-portable-test
  "Portable contract tests for the web capability family."
  (:refer-clojure :exclude [fetch])
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [seon.agent.web :as web]
   [seon.agent.web.core :as internal]
   [seon.config.resolve]
   #?(:cljs [cljs.test :refer [async deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])))

(deftest portable-call-shapes-and-effect-classes
  (is (= :read (:seon.capability/effect (meta #'web/grants))))
  (is (= :external (:seon.capability/effect (meta #'web/fetch))))
  (is (= :external (:seon.capability/effect (meta #'web/search))))
  (is (m/validate :seon.agent.web/grants-request {}))
  (is (m/validate :seon.agent.web/fetch-request
                  {:seon.agent.web/url "https://example.com"
                   :seon.agent.web/timeout-ms 50
                   :seon.agent.web/max-preview-tokens 12
                   :seon.agent.web/max-age-ms 0}))
  (is (m/validate :seon.agent.web/search-request
                  {:seon.agent.web/query "portable web"
                   :seon.agent.web/max-results 20
                   :seon.agent.web/timeout-ms 50})))

(deftest resource-caps-and-closed-request-drift
  (is (= :seon.config.web/maximum-response-bytes
         (second internal/fetch-limit-keys)))
  (is (= :seon.config.web/maximum-search-results
         (last internal/search-limit-keys)))
  (is (= :seon.config.web/default-timeout-ms
         (internal/missing-limit-key {} internal/fetch-limit-keys)))
  (is (= :seon.config.web/default-timeout-ms
         (get-in
          (internal/fetch-config-error
           "https://example.com"
           :seon.config.web/default-timeout-ms)
          [:seon.error/data :seon.config/key])))
  (is (false? (m/validate :seon.agent.web/fetch-request
                          {:seon.agent.web/url "https://example.com"
                           :seon.agent.web/max-bytes 1})))
  (is (false? (m/validate :seon.agent.web/search-request
                          {:seon.agent.web/query "x"
                           :seon.agent.web/max-results 21}))))

(deftest portable-policy-and-response-interpretation
  (is (nil? (internal/host-policy-decision
             {:seon.agent.web/policy :public-only
              :seon.agent.web/allowed-domains []}
             "example.com"
             ["93.184.216.34"])))
  (is (str/includes?
       (internal/host-policy-decision
        {:seon.agent.web/policy :public-only
         :seon.agent.web/allowed-domains []}
        "localhost"
        ["127.0.0.1"])
       "blocked"))
  (is (= {:seon.agent.web/retry? false
          :seon.capability/effect :external}
         (internal/retry-decision {:seon.capability/effect :external})))
  (let [parsed (internal/parse-serper
                {:organic [{:link "https://example.com" :position 1}
                           {:link "https://example.org" :position 2}]}
                1)]
    (is (= 1 (count (:seon.agent.web/results parsed))))
    (is (= 2 (:seon.agent.web/result-count parsed)))))

#?(:cljs
   (deftest flat-error-envelopes-resolve-for-frozen-entry-calls
     (async done
       (let [prior (aget (.-env js/process) "SEON_WEB")]
         (aset (.-env js/process) "SEON_WEB" "1")
         (-> (web/fetch {:seon.agent.web/url ""})
             (.then
              (fn [fetch-error]
                (is (false? (:seon.agent.web/ok? fetch-error)))
                (is (string? (:seon.error/message fetch-error)))
                (is (not (contains? fetch-error :seon/error)))
                (web/search {:seon.agent.web/query ""})))
             (.then
              (fn [search-error]
                (is (false? (:seon.agent.web/ok? search-error)))
                (is (string? (:seon.error/message search-error)))
                (is (not (contains? search-error :seon/error)))))
             (.catch (fn [error]
                       (is false (str "entry call rejected — " error))))
             (.finally
              (fn []
                (if (some? prior)
                  (aset (.-env js/process) "SEON_WEB" prior)
                  (js-delete (.-env js/process) "SEON_WEB"))
                (done))))))))
