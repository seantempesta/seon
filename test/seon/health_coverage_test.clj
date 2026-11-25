(ns seon.health-coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.health.coverage :as cov]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.test-fixture :refer [with-test-store]]))

(def ^:private demo-schemas-tx
  [{:seon.schema/key :seon.cov.demo/id
    :seon.schema/form [:and {:seon.db/identity true} :keyword]}
   {:seon.schema/key :seon.cov.demo/label
    :seon.schema/form :string}
   {:seon.schema/key :seon.cov.demo/extra
    :seon.schema/form :string}
   {:seon.schema/key :seon.cov.demo/widget
    :seon.schema/form [:map {:seon.db/attributes true}
                       [:seon.cov.demo/id :seon.cov.demo/id]
                       [:seon.cov.demo/label :seon.cov.demo/label]]}
   {:seon.schema/key :seon.cov.demo/card
    :seon.schema/form [:map {:seon.db/attributes true}
                       [:seon.cov.demo/id :seon.cov.demo/id]
                       [:seon.cov.demo/label :seon.cov.demo/label]]}])

(def ^:private synth-ns-src
  "(ns seon.cov.synth)
(defn render-widget-a
  {:malli/schema [:=> [:cat [:map [:seon.cov.demo/widget :seon.cov.demo/widget]]]
                      [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}
  [{w :seon.cov.demo/widget}]
  {:seon.ui/hiccup [:div {:class \"card\" :data-min-width 360}
                     [:strong (str (:seon.cov.demo/id w))]
                     [:p (str \"Widget A: \" (:seon.cov.demo/label w))]]})
(defn render-widget-b
  {:malli/schema [:=> [:cat [:map [:seon.cov.demo/widget :seon.cov.demo/widget]]]
                      [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}
  [{w :seon.cov.demo/widget}]
  {:seon.ui/hiccup [:div {:class \"card\" :data-min-width 380}
                     [:strong (str (:seon.cov.demo/id w))]
                     [:p (str \"Widget B: \" (:seon.cov.demo/label w))]]})
(defn render-card-chip
  {:malli/schema [:=> [:cat [:map [:seon.cov.demo/card :seon.cov.demo/card]]]
                      [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}
  [{c :seon.cov.demo/card}]
  {:seon.ui/hiccup [:span {:class \"ent-chip\" :data-min-width 160}
                     (str (:seon.cov.demo/id c))]})
(defn render-card-full
  {:malli/schema [:=> [:cat [:map [:seon.cov.demo/card :seon.cov.demo/card]]]
                      [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}
  [{c :seon.cov.demo/card}]
  {:seon.ui/hiccup [:div {:class \"card ent-full\" :data-min-width 520}
                     [:h3 (str \"Detailed Card Overview: \" (:seon.cov.demo/id c))]
                     [:p (str \"Full label payload for inspection: \" (:seon.cov.demo/label c))]
                     [:p \"Extra multi-line diagnostic context separating compact chip from full card.\"]]})")

(deftest missing-and-single-footprint-renderer-test
  (testing "Unrendered stored entity schema triggers :missing-entity-renderer; 1 renderer triggers :single-footprint-renderer"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data demo-schemas-tx})
      (let [db1 @conn
            m1 (cov/schema-renderer-matrix db1)
            holes1 (cov/detect-renderer-holes db1 m1 [])
            w-miss (filterv #(and (= :missing-entity-renderer (:class %))
                                  (= :seon.cov.demo/widget (:schema %))) holes1)]
        (is (= 1 (count w-miss)))
        (is (= :warning (:severity (first w-miss)))))
      ;; Import 1 pure renderer for :seon.cov.demo/widget
      (let [one-src "(ns seon.cov.single-synth)
(defn render-widget-only
  {:malli/schema [:=> [:cat [:map [:seon.cov.demo/widget :seon.cov.demo/widget]]]
                      [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}
  [{w :seon.cov.demo/widget}]
  {:seon.ui/hiccup [:div {:data-min-width 320} (str (:seon.cov.demo/id w))]})"]
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/cov/single_synth.clj" one-src false)})
        (let [db2 @conn
              m2 (cov/schema-renderer-matrix db2)
              holes2 (cov/detect-renderer-holes db2 m2 [])
              w-miss2 (filterv #(and (= :missing-entity-renderer (:class %))
                                     (= :seon.cov.demo/widget (:schema %))) holes2)
              w-single (filterv #(and (= :single-footprint-renderer (:class %))
                                      (= :seon.cov.demo/widget (:schema %))) holes2)]
          (is (empty? w-miss2))
          (is (= 1 (count w-single)))
          (is (= :info (:severity (first w-single))))
          (is (= 'seon.cov.single-synth/render-widget-only (:renderer (first w-single)))))))))

(deftest ambiguous-renderer-pair-and-disambiguation-test
  (testing "Overlapping renderers trigger :ambiguous-renderer-pair; distinct tiers, distinct optional inputs, or rule :fn/:params clear it"
    (with-test-store [{:keys [conn]}]
      (try
        (load-string synth-ns-src)
        (d/transact conn {:tx-data demo-schemas-tx})
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/cov/synth.clj" synth-ns-src false)})
        (let [db1 @conn
              m1 (cov/schema-renderer-matrix db1)
              w-row (first (filter #(= :seon.cov.demo/widget (:schema %)) m1))
              c-row (first (filter #(= :seon.cov.demo/card (:schema %)) m1))
              holes1 (cov/detect-renderer-holes db1 m1 [])
              amb1 (filterv #(= :ambiguous-renderer-pair (:class %)) holes1)]
          (is (= 2 (:renderer-count w-row)))
          (is (every? :measured? (:renderers w-row)) "Live renderers measured on seed=42 samples")
          (is (= 2 (:renderer-count c-row)))
          (is (= [:seon.cov.demo/widget] (mapv :schema amb1))
              "Overlapping widget-a/b is ambiguous while distinct chip/full card tiers have 0 ambiguity")
          (is (= :warning (:severity (first amb1))))

          ;; Pure contract check: distinct :optional-inputs clears ambiguity even on identical footprints
          (let [distinct-opt-m [{:schema :seon.cov.demo/widget
                                 :renderer-count 2
                                 :renderers [{:fn 'a/r1 :inputs #{:seon.cov.demo/widget} :optional-inputs #{} :min-width 360 :tokens 12}
                                             {:fn 'a/r2 :inputs #{:seon.cov.demo/widget} :optional-inputs #{:seon.cov.demo/extra} :min-width 360 :tokens 12}]}]]
            (is (empty? (cov/detect-renderer-holes db1 distinct-opt-m []))))

          ;; Pure unmeasured pair with identical inputs and no rule triggers ambiguity
          (let [unmeas-m [{:schema :seon.cov.demo/widget
                           :renderer-count 2
                           :renderers [{:fn 'u/r1 :inputs #{:seon.cov.demo/widget} :optional-inputs #{} :measured? false}
                                       {:fn 'u/r2 :inputs #{:seon.cov.demo/widget} :optional-inputs #{} :measured? false}]}]]
            (is (= 1 (count (cov/detect-renderer-holes db1 unmeas-m [])))))

          ;; Ranking-only :params (:rank/boost) does NOT disambiguate
          (d/transact conn {:tx-data [{:seon.rule/id :rule.cov/rank-only
                                       :seon.rule/stratum 20 :seon.rule/category :context
                                       :seon.rule/target :any :seon.rule/enabled? true
                                       :seon.rule/query '[:find ?w :in $branch :where [$branch ?w :seon.cov.demo/id _]]
                                       :seon.rule/assert '[{:entity ?w :pull [:seon.cov.demo/id] :zone :workspace
                                                            :priority 80 :params {:rank/boost 0.2}}]
                                       :seon.rule/doc "Rank boost only"}]})
          (let [db-rank @conn
                amb-rank (filterv #(and (= :ambiguous-renderer-pair (:class %))
                                        (= :seon.cov.demo/widget (:schema %)))
                                  (cov/detect-renderer-holes db-rank m1 (cov/rule-atlas db-rank)))]
            (is (= 1 (count amb-rank)) "Ranking-only :params must not suppress ambiguity"))

          ;; Explicit :fn selection clears ambiguity
          (d/transact conn {:tx-data [{:seon.rule/id :rule.cov/select-fn
                                       :seon.rule/stratum 20 :seon.rule/category :context
                                       :seon.rule/target :any :seon.rule/enabled? true
                                       :seon.rule/query '[:find ?w :in $branch :where [$branch ?w :seon.cov.demo/id _]]
                                       :seon.rule/assert '[{:entity ?w :zone :workspace :priority 85
                                                            :fn seon.cov.synth/render-widget-a}]
                                       :seon.rule/doc "Explicit :fn selection"}]})
          (let [db-fn @conn
                amb-fn (filterv #(and (= :ambiguous-renderer-pair (:class %))
                                      (= :seon.cov.demo/widget (:schema %)))
                                (cov/detect-renderer-holes db-fn m1 (cov/rule-atlas db-fn)))]
            (is (empty? amb-fn) "Explicit rule :fn clears ambiguity"))

          ;; Disable :fn rule and add UI-disambiguating :params rule -> also clears ambiguity
          (d/transact conn {:tx-data [{:seon.rule/id :rule.cov/select-fn :seon.rule/enabled? false}
                                      {:seon.rule/id :rule.cov/select-params
                                       :seon.rule/stratum 20 :seon.rule/category :context
                                       :seon.rule/target :any :seon.rule/enabled? true
                                       :seon.rule/query '[:find ?w :in $branch :where [$branch ?w :seon.cov.demo/id _]]
                                       :seon.rule/assert '[{:entity ?w :pull [:seon.cov.demo/id :seon.cov.demo/label]
                                                            :zone :workspace :priority 85
                                                            :params {:seon.ui/variant :compact}}]
                                       :seon.rule/doc "UI param disambiguation"}]})
          (let [db-p @conn
                amb-p (filterv #(and (= :ambiguous-renderer-pair (:class %))
                                     (= :seon.cov.demo/widget (:schema %)))
                               (cov/detect-renderer-holes db-p m1 (cov/rule-atlas db-p)))]
            (is (empty? amb-p) "Disambiguating rule :params clears ambiguity")))
        (finally
          (when (find-ns 'seon.cov.synth) (remove-ns 'seon.cov.synth)))))))

(deftest rule-holes-detection-test
  (testing "Orphan $wm consumer, dead rule :fn, and conflicting rule priority are detected cleanly"
    (with-test-store [{:keys [conn]}]
      (let [live-src "(ns seon.cov.live-fns)
(defn render-a {:malli/schema [:=> [:cat :map] :map]} [m] m)
(defn render-b {:malli/schema [:=> [:cat :map] :map]} [m] m)"]
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/cov/live_fns.clj" live-src false)})
        (d/transact conn {:tx-data
                          [{:seon.rule/id :rule.cov/orphan-consumer
                            :seon.rule/stratum 20 :seon.rule/category :context :seon.rule/target :any :seon.rule/enabled? true
                            :seon.rule/query '[:find ?e :in $wm :where [$wm ?e :wm/ghost-attr true]]
                            :seon.rule/assert '[{:entity ?e :zone :workspace :priority 80}]
                            :seon.rule/doc "Reads unproduced WM attr"}
                           {:seon.rule/id :rule.cov/producer
                            :seon.rule/stratum 10 :seon.rule/category :context :seon.rule/target :any :seon.rule/enabled? true
                            :seon.rule/query '[:find ?e :in $wm :where [$wm ?e :world/connected true]]
                            :seon.rule/assert '[[?e :wm/live-attr true]]
                            :seon.rule/doc "Produces :wm/live-attr from runtime seeder"}
                           {:seon.rule/id :rule.cov/good-consumer
                            :seon.rule/stratum 20 :seon.rule/category :context :seon.rule/target :any :seon.rule/enabled? true
                            :seon.rule/query '[:find ?e :in $wm :where [$wm ?e :wm/live-attr true]]
                            :seon.rule/assert '[{:entity ?e :zone :workspace :priority 70}]
                            :seon.rule/doc "Reads produced :wm/live-attr"}
                           {:seon.rule/id :rule.cov/dead-fn-rule
                            :seon.rule/stratum 25 :seon.rule/category :present :seon.rule/target :any :seon.rule/enabled? true
                            :seon.rule/query '[:find ?t :in $branch :where [$branch ?t :seon.task/id _]]
                            :seon.rule/assert '[{:entity ?t :zone :workspace :priority 80 :fn seon.missing.ns/nope}]
                            :seon.rule/doc "Points to nonexistent fn"}
                           {:seon.rule/id :rule.cov/conflict-a
                            :seon.rule/stratum 30 :seon.rule/category :present :seon.rule/target :any :seon.rule/enabled? true
                            :seon.rule/query '[:find ?t :in $branch :where [$branch ?t :seon.task/done? false]]
                            :seon.rule/assert '[{:entity ?t :zone :workspace :priority 80 :fn seon.cov.live-fns/render-a}]
                            :seon.rule/doc "Conflict rule A"}
                           {:seon.rule/id :rule.cov/conflict-b
                            :seon.rule/stratum 30 :seon.rule/category :present :seon.rule/target :wire :seon.rule/enabled? true
                            :seon.rule/query '[:find ?task :in $branch :where [$branch ?task :seon.task/done? false]]
                            :seon.rule/assert '[{:entity ?task :zone :workspace :priority 80 :fn seon.cov.live-fns/render-b}]
                            :seon.rule/doc "Conflict rule B (alpha-equivalent pattern, different :fn)"}
                           {:seon.rule/id :rule.cov/disjoint-not
                            :seon.rule/stratum 30 :seon.rule/category :present :seon.rule/target :wire :seon.rule/enabled? true
                            :seon.rule/query '[:find ?task :in $branch :where (not [$branch ?task :seon.task/done? false])]
                            :seon.rule/assert '[{:entity ?task :zone :workspace :priority 80 :fn seon.cov.live-fns/render-a}]
                            :seon.rule/doc "Disjoint negated pattern must not conflict"}]})
        (let [db @conn
              atlas (cov/rule-atlas db)
              holes (cov/detect-rule-holes db atlas)
              by-c (group-by :class holes)]
          (is (= [[:rule.cov/orphan-consumer :wm/ghost-attr]]
                 (mapv (juxt :rule :attr) (:orphan-wm-consumer by-c)))
              "Flags only unproduced WM attr; allows runtime seeders and rule-produced attrs")
          (is (= [[:rule.cov/dead-fn-rule 'seon.missing.ns/nope]]
                 (mapv (juxt :rule :fn) (:dead-rule-fn by-c))))
          (is (= :error (:severity (first (:dead-rule-fn by-c)))))
          (is (= 1 (count (:conflicting-rule-priority by-c)))
              "Detects alpha-equivalent conflicting :fn pair and ignores disjoint negated pattern")
          (is (= [:rule.cov/conflict-a :rule.cov/conflict-b]
                 (:rules (first (:conflicting-rule-priority by-c))))))))))

(deftest real-seon-db-coverage-report-test
  (testing "coverage-report runs fast (< 500 ms) on full Seon rules + schemas and surfaces real holes & atlas"
    (with-test-store [{:keys [conn]}]
      (let [starter (or rules/*base-rules-override* (rseed/starter-rules))]
        (d/transact conn {:tx-data starter})
        (let [db @conn
              t0 (System/nanoTime)
              rep (cov/coverage-report db)
              ms (/ (double (- (System/nanoTime) t0)) 1e6)
              slim (cov/coverage-report db {:include-atlas? false})
              atlas (:rule-atlas rep)
              req-keys #{:id :stratum :category :target :enabled? :zones :priorities :wm-reads :wm-writes :db-sources :doc}]
          (is (< ms 500.0) (str "coverage-report took " ms " ms (must be < 500 ms)"))
          (is (= :seon.health/coverage (:seon/schema (meta rep))))
          (is (string? (:summary rep)))
          (is (true? (:ok? rep)) (str "Expected 0 rule/ambiguity holes, got: " (pr-str (:rule-holes rep))))
          (is (>= (count atlas) 20) "Full rule atlas covers all starter rules")
          (is (every? #(every? (fn [k] (contains? % k)) req-keys) atlas))
          (is (= atlas (vec (sort-by (juxt :stratum #(str (:id %))) atlas))) "Atlas is deterministically sorted")
          (is (pos? (count (:missing-renderers rep))))
          (is (empty? (:rule-holes rep)) (pr-str (:rule-holes rep)))
          (is (not (contains? slim :rule-atlas)) "include-atlas? false omits atlas for compact REPL output")
          (is (= (:counts rep) (:counts slim))))))
    ;; When executed inside an interactive Seon REPL where user/main-db is bound, verify live :main too!
    (when-let [mdb (try (when-let [v (resolve 'user/main-db)] (when (bound? v) (@v)))
                        (catch Throwable _ nil))]
      (let [t0 (System/nanoTime)
            rep (cov/coverage-report mdb)
            ms (/ (double (- (System/nanoTime) t0)) 1e6)]
        (is (< ms 500.0) (str "Live main-db coverage-report took " ms " ms (< 500 ms)"))
        (is (= :seon.health/coverage (:seon/schema (meta rep))))
        (is (>= (get-in rep [:counts :entity-schemas]) 20))
        (is (>= (count (:rule-atlas rep)) 20))
        (is (seq (:missing-renderers rep)))
        (is (seq (:rule-holes rep)))))))
