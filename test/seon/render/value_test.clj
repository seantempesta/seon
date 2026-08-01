(ns seon.render.value-test
  "Behavior classes for the one admission-backed structural floor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.sci.admit :as admit]
            [seon.test-support :as test-support]))

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

(defn- rendered-twins
  ([raw bounded-caps]
   (rendered-twins raw bounded-caps {}))
  ([raw bounded-caps options]
   (let [bounded-unit (assoc (unit raw)
                             :seon.sci.admit/caps bounded-caps
                             :seon.render.value/options options)]
     [(value/render-ai bounded-unit)
      (hiccup/->string (value/render-html bounded-unit))])))

(defn- cut-marker?
  [projection]
  (cond
    (= ::admit/elided projection) true
    (map? projection)
    (or (contains? projection ::admit/truncated-string)
        (= true (::admit/elided projection))
        (some cut-marker? (mapcat identity projection)))
    (coll? projection) (some cut-marker? projection)
    :else false))

(defn- loud-twins?
  ([raw bounded-caps]
   (loud-twins? raw bounded-caps {}))
  ([raw bounded-caps options]
   (and (cut-marker?
         (:seon.render.value/tree
          (value/prepare
           (assoc (unit raw)
                  :seon.sci.admit/caps bounded-caps
                  :seon.render.value/options options))))
        (every? #(str/includes? % "elided")
                (rendered-twins raw bounded-caps options)))))

(defn- quiet-twins?
  ([raw bounded-caps]
   (quiet-twins? raw bounded-caps {}))
  ([raw bounded-caps options]
   (and (not (cut-marker?
              (:seon.render.value/tree
               (value/prepare
                (assoc (unit raw)
                       :seon.sci.admit/caps bounded-caps
                       :seon.render.value/options options)))))
        (every? #(not (str/includes? % "elided"))
                (rendered-twins raw bounded-caps options)))))

(defn- assert-cap-property!
  [seed property label]
  (test-support/assert-check!
   (tc/quick-check 80 property :seed seed)
   label))

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
    (is (<= @realized
            (inc (:seon.render.value/max-collection
                  (:seon.render.value/options
                   (value/prepare (unit :probe)))))))
    (is (str/includes? html "elided")))
  (testing "a realization failure becomes visible data"
    (let [raw (map (fn [_] (throw (ex-info "poison" {}))) [1])
          output (value/render-ai (unit raw))]
      (is (string? output))
      (is (or (str/includes? output "projection-error")
              (str/includes? output "poison"))))))

(deftest depth-cuts-are-in-band-in-both-twins
  (assert-cap-property!
   202607310201
   (prop/for-all [limit (gen/choose 1 8)]
     (let [bounded-caps (assoc caps
                               :seon.config.eval.result/max-depth limit)
           nested (fn [depth]
                    (reduce (fn [value _] [value]) :leaf (range depth)))]
       (and (loud-twins? (nested (inc limit)) bounded-caps
                         {:seon.render.value/max-depth limit})
            (quiet-twins? (nested limit) bounded-caps
                          {:seon.render.value/max-depth limit}))))
   "depth cuts must be in band"))

(deftest collection-cuts-are-in-band-in-both-twins
  (assert-cap-property!
   202607310202
   (prop/for-all [limit (gen/choose 1 20)]
     (let [bounded-caps (assoc caps
                               :seon.config.eval.result/max-collection limit)
           with-window-size
           (fn [raw]
             (assoc (unit raw)
                    :seon.render.value/options
                    {:seon.render.value/max-collection limit}))
           loud?
           (fn [raw]
             (let [bounded-unit
                   (assoc (with-window-size raw)
                          :seon.sci.admit/caps bounded-caps)]
               (and (cut-marker?
                     (:seon.render.value/tree (value/prepare bounded-unit)))
                    (every? #(str/includes? % "elided")
                            [(value/render-ai bounded-unit)
                             (hiccup/->string
                              (value/render-html bounded-unit))]))))
           quiet?
           (fn [raw]
             (let [bounded-unit
                   (assoc (with-window-size raw)
                          :seon.sci.admit/caps bounded-caps)]
               (and (not (cut-marker?
                          (:seon.render.value/tree
                           (value/prepare bounded-unit))))
                    (every? #(not (str/includes? % "elided"))
                            [(value/render-ai bounded-unit)
                             (hiccup/->string
                              (value/render-html bounded-unit))]))))]
       (and (loud? (vec (range (inc limit))))
            (quiet? (vec (range limit))))))
   "collection cuts must be in band"))

(deftest string-cuts-are-in-band-in-both-twins
  (assert-cap-property!
   202607310203
   (prop/for-all [limit (gen/choose 1 40)]
     (let [bounded-caps (assoc caps
                               :seon.config.eval.result/max-string limit)]
       (and (loud-twins? (apply str (repeat (inc limit) "x")) bounded-caps)
            (quiet-twins? (apply str (repeat limit "x")) bounded-caps))))
   "string cuts must be in band"))

(deftest node-budget-cuts-are-in-band-in-both-twins
  (assert-cap-property!
   202607310204
   (prop/for-all [limit (gen/choose 3 24)]
     (let [bounded-caps (assoc caps
                               :seon.config.eval.result/max-nodes limit)
           matrix (vec (repeat limit (vec (repeat limit :x))))]
       (and (loud-twins? matrix bounded-caps)
            (quiet-twins? :x bounded-caps))))
   "node-budget cuts must be in band"))

(deftest audit-floor-falsifiers-name-the-exact-cut
  (let [matrix (vec (repeat 5 (vec (repeat 5 :x))))
        matrix-tree (:seon.render.value/tree
                     (value/prepare
                      (assoc (unit matrix)
                             :seon.sci.admit/caps
                             (assoc caps
                                    :seon.config.eval.result/max-nodes 8))))
        map-tree (:seon.render.value/tree
                  (value/prepare
                   (assoc (unit {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6})
                          :seon.sci.admit/caps
                          (assoc caps
                                 :seon.config.eval.result/max-nodes 4))))
        list-tree (:seon.render.value/tree
                   (value/prepare
                    (assoc (unit (list "abcdef"))
                           :seon.sci.admit/caps
                           (assoc caps
                                  :seon.config.eval.result/max-string 3))))]
    (is (= ::admit/elided (second matrix-tree))
        "the exhausted matrix row is a marker, never []")
    (is (= true (::admit/elided map-tree))
        "the partial map carries its marker entry")
    (is (= "abc" (::admit/truncated-string (first list-tree)))
        "a clipped string inside a list remains visible and marked")
    (is (str/includes?
         (hiccup/->string
          (value/render-html
           (assoc (unit (list "abcdef"))
                  :seon.sci.admit/caps
                  (assoc caps :seon.config.eval.result/max-string 3))))
         "inspect")
        "sequential raw children retain the clipped string handle")))

(deftest ai-does-not-apply-the-html-cursor-window
  (let [raw (into {} (map (fn [number] [number number]) (range 40)))
        cursor-unit (assoc (unit raw)
                           :seon.render.data/cursor
                           {:seon.render.data/path []
                            :seon.render.data/offset 500})
        ai (value/render-ai cursor-unit)
        html (hiccup/->string (value/render-html cursor-unit))]
    (is (not= "{}" ai))
    (is (str/includes? ai "39"))
    (is (str/includes? html ":seon.sci.admit/elided"))
    (is (str/includes? html "showing 0 of 40"))))

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
                             jane-path)))
    (testing "non-debug entity floors do not duplicate DOM ids"
      (let [anonymous (dissoc (unit nil jane-path)
                              :seon.cluster.agent/id
                              :seon.render.value/root)]
        (is (not= (value/node-id (assoc anonymous :db/id 1) jane-path)
                  (value/node-id (assoc anonymous :db/id 2) jane-path)))))))

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

(deftest composite-entries-have-distinct-stable-local-identities
  (let [subject {[:first] {:value 1}
                 [:second] {:value 2}
                 :members #{[:a] [:b]}}
        html (hiccup/->string (value/render-html (unit subject)))
        ids (map second (re-seq #"id=\"(seon-value-[a-f0-9]+)\"" html))]
    (is (= (count ids) (count (distinct ids)))
        "non-drillable keys and set members never reuse their parent's id")))

(deftest map-pages-are-independent-of-insertion-order
  (let [left (array-map :d 4 :a 1 :c 3 :b 2)
        right (array-map :b 2 :c 3 :a 1 :d 4)
        page-unit (fn [subject]
                    (assoc (unit subject)
                           :seon.sci.admit/caps
                           (assoc caps
                                  :seon.config.eval.result/max-collection 2)))]
    (is (= (hiccup/->string (value/render-html (page-unit left)))
           (hiccup/->string (value/render-html (page-unit right)))))))

(deftest render-options-size-the-window-beneath-the-admission-ceiling
  (let [raw (vec (range 40))
        default-html (hiccup/->string (value/render-html (unit raw)))
        wider-html
        (hiccup/->string
         (value/render-html
          (assoc (unit raw)
                 :seon.render.value/options
                 {:seon.render.value/max-collection 12})))
        safety-html
        (hiccup/->string
         (value/render-html
          (-> (unit raw)
              (assoc :seon.render.value/options
                     {:seon.render.value/max-collection 12})
              (assoc-in [:seon.sci.admit/caps
                         :seon.config.eval.result/max-collection]
                        4))))]
    (is (str/includes? default-html "showing 1–7 of 40")
        "no explicit options use the registered presentation default")
    (is (str/includes? wider-html "showing 1–11 of 40")
        "an explicit options map widens the routed window")
    (is (str/includes? safety-html "showing 1–3 of 40")
        "the admission cap remains the outer safety bound")))

(deftest stored-result-windows-consume-the-presentation-options
  (let [large-string (pr-str (apply str (repeat 10000 "x")))
        string-window (value/result-window-edn (unit :unused) large-string)
        collection-window
        (value/result-window-edn (unit :unused) (pr-str (vec (range 40))))]
    (is (< (count string-window) (count large-string))
        "a large scalar cannot be stored whole beside its blob")
    (is (str/includes? string-window "truncated-string"))
    (is (str/includes? collection-window "elided"))
    (is (< (count collection-window) 200))))
