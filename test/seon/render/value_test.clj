(ns seon.render.value-test
  "Behavior classes for the one admission-backed structural floor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.sci.admit :as admit]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- unit
  ([raw] (unit raw []))
  ([raw path]
   {:seon.cluster.agent/id "root"
    :seon.render.value/root [:seon.cluster.agent/id "root"]
    :seon.render.value/route-base "/agent/root/debug"
    :seon.render.data/cursor {:seon.render.data/path path
                              :seon.render.data/offset 0}
    :seon.render/value raw
    :seon.sci.admit/caps caps}))

(deftest one-admission-produces-both-floor-data-twins
  (let [calls (atom 0)
        original admit/admit]
    (with-redefs [admit/admit (fn [request]
                               (swap! calls inc)
                               (original request))]
      (let [projection (value/prepare (unit {:a [1 2 3]}))]
        (is (= 1 @calls))
        (is (identical? projection (value/render-html-data projection)))
        (is (string? (value/render-ai-data projection)))))))

(deftest caps-are-loud-and-lazy-safe
  (let [realized (atom 0)
        raw (map (fn [number] (swap! realized inc) number) (range))
        html (hiccup/->string (value/render-html (unit raw)))]
    (is (<= @realized (inc (:seon.config.eval.result/max-collection caps))))
    (is (str/includes? html "elided")))
  (testing "a realization failure becomes visible data"
    (let [raw (map (fn [_] (throw (ex-info "poison" {}))) [1])
          output (value/render-ai (unit raw))]
      (is (string? output))
      (is (or (str/includes? output "projection-error")
              (str/includes? output "poison"))))))

(deftest retained-paths-and-root-identity-stay-navigable
  (let [html (hiccup/->string
              (value/render-html
               (unit {:api/results [{:user/name "Jane"}]})))
        jane-path [:api/results 0 :user/name]
        jane-id (value/node-id (unit nil jane-path) jane-path)]
    (is (str/includes? html "/agent/root/debug?path="))
    (is (str/includes? html "%3Aapi%2Fresults"))
    (is (= jane-id (value/node-id (unit nil jane-path) jane-path)))
    (is (not= jane-id
              (value/node-id (assoc (unit nil jane-path)
                                    :seon.cluster.agent/id "other")
                             jane-path)))))

(deftest five-megabyte-string-is-capped-with-an-inspect-handle
  (let [raw (apply str (repeat (* 5 1024 1024) "x"))
        html (hiccup/->string (value/render-html (unit raw)))]
    (is (< (count html) 100000))
    (is (str/includes? html "1310720 tokens"))
    (is (str/includes? html "inspect"))
    (is (str/includes? html "elided"))))

(deftest structural-kinds-and-opaque-handles-remain-legible
  (let [reference (atom {:too "private"})
        html (hiccup/->string
              (value/render-html
               (unit {:map {:a 1}
                      :set #{1 2}
                      :vector [1 2]
                      :reference reference})))]
    (is (str/includes? html "{} 4 keys"))
    (is (str/includes? html "#{} 2 members"))
    (is (str/includes? html "[] 2 items"))
    (is (str/includes? html "reference"))))
