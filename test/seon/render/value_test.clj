(ns seon.render.value-test
  "The render floor is one adapter over the sealed print emitter."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- unit
  [raw]
  {:seon.cluster.agent/id "root"
   :seon.render.call/id [:seon.render.value-test/floor]
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

(defn- registered-unit
  [connection raw]
  (assoc (unit raw)
         :seon.db/db @connection
         :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
         :seon.render.value/root [:seon.render.value-test/registered raw]))

(defn- render-request
  [connection raw]
  (assoc (registered-unit connection raw)
         :seon.sci.eval/time-limit-ms 2000
         :seon.config/on-core-error :panic))

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
        original-admit admit/admit-value
        original-emit print/emit-both]
    (with-redefs [admit/admit-value (fn [request]
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

(deftest structural-option-skips-domain-projection-but-keeps-floor-emission
  (let [calls (atom 0)
        original render/project-node
        raw {:stored/attribute "value"}]
    (with-redefs [render/project-node
                  (fn [& args]
                    (swap! calls inc)
                    (apply original args))]
      (let [structural (value/prepare
                        (assoc (unit raw)
                               :seon.render.value/options
                               {:seon.render.value/structural? true}))
            default (value/prepare (unit raw))]
        (is (= 1 @calls))
        (is (str/includes? (value/render-ai-data structural) "#:stored{:attribute"))
        (is (str/includes? (hiccup/->string
                            (value/render-html-data structural))
                           "seon-print-keyword"))
        (is (= (value/render-ai-data structural)
               (value/render-ai-data default)))))))

(deftest unregistered-values-keep-the-existing-fitted-print-floor
  (let [raw {:unregistered/value 1 :unregistered/detail [2 3]}
        floor-unit (unit raw)
        projection (value/prepare floor-unit)
        html (hiccup/->string (value/render-html-data projection))]
    (is (= (pr-str raw) (value/render-ai-data projection)))
    (is (= :seon.print/map
           (:seon.print/face (:seon.render.value/tree projection))))
    (is (str/includes? html "seon-print-map"))
    (is (not (str/includes? html "seon-data-map")))))

(deftest declared-producers-still-have-absolute-precedence
  (support/with-database
   (fn [connection]
     (let [failure {:my.message/no-recipient true
                    :seon.error/message "A recipient is required."}
           request (render-request connection failure)
           declared-row {:seon.schema/key :fixture/declared-producer
                         :seon.render/ai 'fixture/render-ai
                         :seon.render/html 'fixture/render-html}]
       (with-redefs [schema/matching-shapes-in
                     (fn [_projection _value] [declared-row])]
         (is (= 'fixture/render-ai
                (#'seon.render/producer request
                 :seon.render/ai :seon.render/ai)))
         (is (= 'fixture/render-html
                (#'seon.render/producer request
                 :seon.render/html :seon.render/html))))))))

(deftest registered-floor-elisions-retain-their-requery-identity
  (support/with-database
   (fn [connection]
     (let [rows (mapv (fn [ordinal]
                        {:my.message/id (str "m-" ordinal)
                         :my.message/at (java.util.Date. (* 1000 ordinal))
                         :my.message/preview (str "message " ordinal)})
                      (range 3))
           root [:my.message/inbox "root"]
           profile (assoc (render/agent-render-profile
                           (support/effective-config))
                          :seon.render.profile/id :seon.render.profile/test
                          :seon.render.profile/token-budget 1024
                          :seon.render.profile/max-depth 8
                          :seon.render.profile/max-children 1
                          :seon.render.profile/composition :single-line)
           floor-unit (assoc (registered-unit connection rows)
                             :seon.render.value/root root
                             :seon.render/profile profile)
           projection (value/prepare floor-unit)
           root-elision (last (:seon.print/items
                               (:seon.render.value/tree projection)))
           ai (value/render-ai-data projection)
           html (hiccup/->string (value/render-html-data projection))]
       (is (= :seon.print/elided (:seon.print/face root-elision)))
       (is (= root (:seon.print/requery-id root-elision)))
       (is (= 2 (:seon.print/omitted root-elision)))
       (is (str/includes? ai "requery by [:my.message/inbox \"root\"]"))
       (is (str/includes? html "requery by [:my.message/inbox"))))))

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
           {:seon.render.call/id [:seon.render.value-test/stored]
            :seon.cluster.eval/result-edn stored
            :seon.sci.admit/caps caps}))]
    (is (= "(1 2 3)" (:seon.render.value/text projection)))
    (is (= "(1 2 3)"
           (lexical-hiccup-text
            (:seon.print/hiccup
             (print/emit-both (:seon.render.value/tree projection)
                              (:seon.render.value/options projection))))))))

(deftest anonymous-roots-refuse-instead-of-colliding
  (let [anonymous {:seon.cluster.agent/id "root"
                   :seon.render/value {:same/value 1}
                   :seon.sci.admit/caps caps}
        results [(value/render-html anonymous)
                 (value/render-html anonymous)]]
    (is (= [:seon.render.value/missing-root-identity
            :seon.render.value/missing-root-identity]
           (mapv :seon.error/kind results)))
    (is (every? #(= "A rendered value root requires a caller-supplied block id."
                    (:seon.error/message %))
                results))
    (is (not-any? vector? results)
        "no anonymous Hiccup root can carry a colliding invented id")))

(deftest caller-supplied-block-ids-are-stable-and-distinct
  (let [raw {:same/value 1}
        left (assoc (unit raw) :seon.render.call/id [:test/block-a])
        right (assoc (unit raw) :seon.render.call/id [:test/block-b])
        left-id (value/node-id left [])
        right-id (value/node-id right [])]
    (is (string? left-id))
    (is (not= left-id right-id))
    (is (= left-id (value/node-id left [])))))

(deftest value-artifact-stores-only-the-print-node-source
  (let [admitted (admit/admit
                  {:seon.sci.admit/value {:alpha [1 2 3]}
                   :seon.sci.admit/interrupt-fn (fn [])
                   :seon.sci.admit/caps caps
                   :seon.config/on-core-error :record})
        artifact (value/artifact admitted)
        stored (value/artifact-edn artifact)
        restored (value/read-artifact stored)]
    (is (= #{:seon.sci.admit/print-node :seon.sci.admit/capped?}
           (set (keys artifact))))
    (is (= (:seon.sci.admit/value admitted)
           (value/artifact-value restored)))
    (is (= (:seon.cluster.eval/result-edn admitted)
           (value/artifact-result-edn restored)))))

(deftest profile-fit-supersedes-legacy-print-cuts-with-values
  (is (= "(1 2 3)" (value/render-ai (unit '(1 2 3)))))
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
    (is (str/includes? text "more children"))
    (is (str/includes? text "elided"))
    (is (str/includes? html "seon-print-elision"))
    (is (str/includes? html "seon-data-capped"))))

(deftest elision-is-a-requeryable-structural-value
  (let [digest (apply str (repeat 64 "a"))
        projection
        (value/prepare
         (assoc (unit (vec (range 100)))
                :seon.cluster.eval/result-blob digest
                :seon.sci.admit/caps
                (assoc caps :seon.config.eval.result/max-collection 4)))
        html (hiccup/->string
              (value/render-html
               (assoc (unit (vec (range 100)))
                      :seon.cluster.eval/result-blob digest
                      :seon.sci.admit/caps
                      (assoc caps
                             :seon.config.eval.result/max-collection 4))))
        elision (last (:seon.print/items
                       (:seon.render.value/tree projection)))]
    (is (= {:seon.print/omitted 96
            :seon.render.data/total 100
            :seon.render.data/path []
            :seon.render.data/next-offset 4
            :seon.render.profile/id :seon.render.profile/agent
            :seon.print/requery-id [:seon.blob/digest digest]}
           (select-keys elision
                        [:seon.print/omitted
                         :seon.render.data/total
                         :seon.render.data/path
                         :seon.render.data/next-offset
                         :seon.render.profile/id
                         :seon.print/requery-id])))
    (is (str/includes? (:seon.render.value/text projection)
                       "96 more children"))
    (is (str/includes? html "96 more children"))
    (is (str/includes? html digest))))

(deftest references-stay-opaque
  (let [projection (value/prepare (unit (atom {:private/value 42})))
        tree (:seon.render.value/tree projection)
        text (value/render-ai-data projection)]
    (is (= #{:seon.print/face :seon.print/class}
           (set (keys tree)))
        "an opaque reference carries no value or representation field")
    (is (= :seon.print/object (:seon.print/face tree)))
    (is (= "clojure.lang.Atom" (:seon.print/class tree)))
    (is (= "#object[clojure.lang.Atom]" text))))

(deftest routed-page-size-is-separate-from-print-length
  ;; a configured window of N shows N items — the lookahead slot detects
  ;; more? without costing a row (the 2026-08-01 off-by-one regression)
  (let [html (hiccup/->string
              (value/render-html (routed-unit (vec (range 40)) 3)))]
    (is (str/includes? html "showing 1–3 of 40"))
    (is (str/includes? html "offset=3"))
    (is (str/includes? html "seon-data-capped"))))

(deftest a-window-of-one-shows-one-item-not-an-empty-claim-of-more
  (let [window (value/window [:a :b :c] 0 1)]
    (is (= 1 (:seon.render.value/shown window)))
    (is (= [:a] (:seon.render.value/window window)))
    (is (true? (:seon.render.value/more? window)))))

(deftest a-window-beyond-a-counted-value-states-the-offset-and-length
  (let [window (value/window [:a :b :c] 9 2)]
    (is (= [] (:seon.render.value/window window)))
    (is (= 9 (:seon.render.value/offset window)))
    (is (= 3 (:seon.render.value/total window)))
    (is (true? (:seon.render.value/beyond-end? window)))))

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
