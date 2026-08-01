(ns seon.render.value-test
  "The render floor is one adapter over the sealed print emitter."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.sci.admit :as admit]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- unit
  [raw]
  {:seon.cluster.agent/id "root"
   :seon.render/value raw
   :seon.sci.admit/caps caps})

(defn- routed-unit
  [raw size]
  (assoc (unit raw)
         :seon.render.value/root [:seon.cluster.agent/id "root"]
         :seon.render.value/route-base "/data"
         :seon.render.data/cursor {:seon.render.data/path []
                                   :seon.render.data/offset 0}
         :seon.render.value/options
         {:seon.render.value/max-collection size}))

(defn- lexical-hiccup-text
  [form]
  (cond
    (string? form) form
    (sequential? form)
    (let [[tag & body] form
          body (if (map? (first body)) (next body) body)]
      (if (contains? #{:summary :nav} tag)
        ""
        (apply str (map lexical-hiccup-text body))))
    :else ""))

(deftest one-admission-and-one-tee-produce-the-floor-twins
  (let [admissions (atom 0)
        emissions (atom 0)
        original-admit admit/admit
        original-emit print/emit-both]
    (with-redefs [admit/admit (fn [request]
                               (swap! admissions inc)
                               (original-admit request))
                  print/emit-both (fn [node options]
                                    (swap! emissions inc)
                                    (original-emit node options))]
      (let [projection (value/prepare (unit {:a [1 2 3]}))]
        (is (= 1 @admissions))
        (is (= 1 @emissions))
        (is (= "{:a [1 2 3]}" (value/render-ai-data projection)))
        (is (vector? (value/render-html-data projection)))))))

(deftest stored-print-data-feeds-both-sinks-without-readmission
  (let [stored (:seon.cluster.eval/result-edn
                (admit/admit
                 {:seon.sci.admit/value '(1 2 3)
                  :seon.sci.admit/interrupt-fn (fn [])
                  :seon.sci.admit/caps caps
                  :seon.config/on-core-error :record}))
        projection
        (with-redefs [admit/admit (fn [_]
                                   (throw (ex-info "readmitted" {})))]
          (value/prepare
           {:seon.cluster.eval/result-edn stored
            :seon.sci.admit/caps caps}))]
    (is (= "(1 2 3)" (:seon.render.value/text projection)))
    (is (= "(1 2 3)"
           (lexical-hiccup-text
            (:seon.print/hiccup
             (print/emit-both (:seon.render.value/tree projection)
                              (:seon.render.value/options projection))))))))

(deftest print-options-merge-over-declared-stock-defaults
  (is (= "(1 2 3)" (value/render-ai (unit '(1 2 3)))))
  (is (= "(...)"
         (value/render-ai
          (assoc (unit '(1 2 3))
                 :seon.print/options {:seon.print/length 0
                                      :seon.print/level nil}))))
  (is (= "#"
         (value/render-ai
          (assoc (unit '(1 2 3))
                 :seon.print/options {:seon.print/length nil
                                      :seon.print/level 0}))))
  (is (str/includes?
       (value/render-ai
        (assoc (unit (vec (range 20)))
               :seon.print/options {:seon.print/width 20}))
       "\n")))

(deftest admission-caps-stay-the-outer-safety-bound
  (let [bounded (assoc (unit (vec (range 20)))
                       :seon.sci.admit/caps
                       (assoc caps
                              :seon.config.eval.result/max-collection 4))
        text (value/render-ai bounded)
        html (hiccup/->string (value/render-html bounded))]
    (is (str/includes? text "..."))
    (is (str/includes? text "elided"))
    (is (str/includes? html "seon-print-elision"))
    (is (str/includes? html "seon-data-capped"))))

(deftest references-stay-opaque
  (let [text (value/render-ai (unit (atom 42)))]
    (is (re-matches #"#object\[clojure\.lang\.Atom 0x[0-9a-f]+\]" text))
    (is (not (str/includes? text "42")))))

(deftest routed-page-size-is-separate-from-print-length
  (let [html (hiccup/->string
              (value/render-html (routed-unit (vec (range 40)) 3)))]
    (is (str/includes? html "showing 1–2 of 40"))
    (is (str/includes? html "offset=2"))
    (is (str/includes? html "seon-data-capped"))))

(deftest realization-failure-is-visible-data
  (let [raw (map (fn [_] (throw (ex-info "poison" {}))) [1])
        text (value/render-ai (routed-unit raw 3))]
    (is (str/includes? text "window-failed"))
    (is (str/includes? text "poison"))))

(deftest oversized-result-window-remains-tagged-data
  (let [full (:seon.cluster.eval/result-edn
              (admit/admit
               {:seon.sci.admit/value (vec (range 40))
                :seon.sci.admit/interrupt-fn (fn [])
                :seon.sci.admit/caps caps
                :seon.config/on-core-error :record}))
        window (value/result-window-edn (routed-unit :unused 3) full)
        node (edn/read-string window)]
    (is (< (count window) (count full)))
    (is (= [0 1 :seon.print/elided]
           (mapv #(or (:seon.print/value %) (:seon.print/face %))
                 (:seon.print/items node))))))
