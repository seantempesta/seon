(ns seon.query
  "General, read-only executable Datahike query entities (`:seon.query/entity`):
   - Executes live against Datahike inside `datahike.reads/with-reads` (forwarding reads to any outer
     `di/*reads*` so embedded live queries react automatically via Datastar SSE).
   - Returns schema-tagged result values (`:seon.query/table`, `:seon.rule/trace`, `:seon.schema/fingerprint`,
     `:seon.health/report`, `:seon.walk/reads`, or the pulled entity), letting `seon.render/render-value`
     delegate to each result schema's own small, focused render function."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.index.interface :as di]
            [datahike.reads :as reads]
            [seon.render :as render]
            [seon.render.markdown :as rmd]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store.query :as sq]))

(defn query-summary
  "Pure metadata summary for a `:seon.query/entity`."
  {:malli/schema [:=> [:cat :seon.query/entity] :map]
   :cost/ticks 50000
   :cost/bytes 16384}
  [q-ent]
  (let [spec (or (:seon.query/spec q-ent) {})]
    {:id (:seon.query/id q-ent)
     :title (:seon.query/title q-ent)
     :kind (:seon.query/kind q-ent)
     :limit (long (or (:seon.query/limit q-ent) (:limit spec) 16))
     :has-choices? (boolean (seq (:choices spec)))}))

(defn- pull-ref [db main-db ref]
  (when (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)))
    (let [[a v] ref
          try-db (fn [adb]
                   (when (and adb (contains? (:schema adb) a))
                     (when-let [e (:e (first (d/datoms adb :avet a v)))]
                       {:eid e :db adb :ent (dissoc (d/pull adb '[*] e) :db/id)})))]
      (or (try-db db) (when (and main-db (not= main-db db)) (try-db main-db))))))

(defn- exec-datalog [db spec lim]
  (let [q-form (:q spec)
        args (vec (or (:args spec) []))
        raw (apply d/q q-form db args)
        cols (vec (or (:columns spec)
                      (when (vector? q-form)
                        (->> (take-while #(not (keyword? %)) (rest q-form))
                             (mapv (fn [x] (if (sequential? x) (str/join " " x) (str x))))))))
        rows (->> raw
                  (sort-by (fn [row]
                             (if (and (sequential? row) (>= (count row) 2) (number? (second row)))
                               [(- (long (second row))) (str (first row))]
                               [0 (pr-str row)])))
                  vec)]
    (with-meta {:columns cols :total (count rows) :rows (vec (take lim rows)) :more? (> (count rows) lim)}
      {:seon/schema :seon.query/table})))

(defn- exec-index-slice [db spec lim]
  (if-let [pats (seq (:patterns spec))]
    (let [rows (mapv (fn [{:keys [label pattern note]}]
                       [(or label (pr-str pattern))
                        (sq/count-range db pattern)
                        (or note (pr-str pattern))])
                     pats)]
      (with-meta {:columns ["Entity / Fact Class" "Live Count" "Index Pattern"]
                  :total (count rows) :rows rows :more? false}
        {:seon/schema :seon.query/table}))
    (let [pat (vec (or (:pattern spec) [nil :seon.schema/key nil]))
          pv (sq/preview-slice db pat {:limit lim
                                       :offset (long (or (:offset spec) 0))
                                       :reverse? (boolean (:reverse? spec))})
          rows (mapv (fn [dt] [(:e dt) (:a dt) (:v dt) (:tx dt)]) (:datoms pv))]
      (with-meta {:columns ["eid" "attr" "value" "tx"]
                  :total (:total pv) :rows rows :more? (:more? pv) :pattern pat}
        {:seon/schema :seon.query/table}))))

(defn- exec-rule-trace [db main-db spec lim]
  (let [derive-fn (requiring-resolve 'seon.rule.context/derive-context)
        health-fn (requiring-resolve 'seon.health/report)
        aid (or (:agent spec) :agent/root)
        target (or (:target spec) :html)
        mdb (or main-db db)
        ag (or (when (contains? (:schema db) :seon.agent/id)
                 (let [p (d/pull db '[*] [:seon.agent/id aid])] (when (:seon.agent/id p) p)))
               (when (contains? (:schema mdb) :seon.agent/id)
                 (let [p (d/pull mdb '[*] [:seon.agent/id aid])] (when (:seon.agent/id p) p)))
               {:seon.agent/id aid :seon.agent/role :worker})
        res (derive-fn mdb db ag [] health-fn {:include-evals? true :include-children? true :target target})
        fired (filterv #(pos? (long (:fired % 0))) (:runs res))]
    (with-meta
      {:agent aid
       :target target
       :rules-count (:rules-count res)
       :fired-count (count fired)
       :facts-count (:facts-count res)
       :tiles-count (count (:all-tiles res))
       :derive-ms (:ms res)
       :fired-rows (mapv (fn [r] [(:stratum r) (:rule-id r) (:category r) (:fired r) (:asserted-count r) (:reads-count r)])
                         (take lim fired))
       :tile-rows (mapv (fn [t] [(:id t) (:schema t) (:source t) (:zone t) (:layout t) (:view-fn t) (:priority t)])
                        (take lim (:all-tiles res)))}
      {:seon/schema :seon.rule/trace})))

(defn- exec-schema-fingerprint [db main-db spec]
  (let [ref (:entity spec)
        mdb (or main-db db)
        ix (schema/entity-index (or mdb db))
        found (pull-ref db mdb ref)
        ent (:ent found)]
    (if-not (map? ent)
      {:entity ref :found? false}
      (let [fs (six/facets-of ix ent)
            ms (six/most-specific ix ent)
            primary (if (qualified-keyword? ms) ms (:schema (first fs)))
            rfn (when primary (six/schema-property ix primary :seon.render/hiccup))]
        (with-meta
          {:ref ref
           :eid (:eid found)
           :primary primary
           :renderer (or rfn :seon.render/self-printer)
           :facets fs
           :entity (with-meta ent (when primary {:seon/schema primary}))}
          {:seon/schema :seon.schema/fingerprint})))))

(defn- exec-health [db main-db]
  (let [health-fn (requiring-resolve 'seon.health/report)
        rep (health-fn (or main-db db))]
    (with-meta rep {:seon/schema :seon.health/report})))

(defn- exec-walk-reads [db main-db spec lim]
  (let [mdb (or main-db db)
        ref (:entity spec)
        t0 (System/nanoTime)
        [val rs] (reads/with-reads
                   #(if ref
                      (let [found (pull-ref db mdb ref)]
                        {:pulled-keys (count (:ent found)) :eid (:eid found) :ref ref})
                      (let [derive-fn (requiring-resolve 'seon.rule.context/derive-context)
                            aid (or (:agent spec) :agent/guide)
                            ag (or (d/pull mdb '[*] [:seon.agent/id aid]) {:seon.agent/id aid})]
                        (select-keys (derive-fn mdb db ag [] nil {:target :html})
                                     [:rules-count :facts-count :ms]))))
        ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
        ridx (reads/reads-index rs)
        attrs (->> rs (keep :a) frequencies (sort-by (fn [[a n]] [(- n) (str a)])) (take lim) vec)]
    (with-meta
      {:subject (or ref (:agent spec) :agent/guide)
       :ms ms
       :reads-count (count rs)
       :indexed-keys (count ridx)
       :summary val
       :rows (mapv (fn [[a n]] [a n]) attrs)}
      {:seon/schema :seon.walk/reads})))

(defn- exec-entity-pull [db main-db spec]
  (let [ref (:entity spec)
        mdb (or main-db db)
        ix (schema/entity-index (or mdb db))
        found (pull-ref db mdb ref)
        sk (when (vector? ref) (get-in ix [:identity-schemas (first ref)]))]
    (when-let [ent (:ent found)]
      (with-meta ent (when sk {:seon/schema sk})))))

(defn- exec-instrument [db main-db spec]
  (let [read-fn (requiring-resolve 'seon.instruments/read-instrument)]
    (read-fn (or main-db db) (or (:instrument spec) :tachometer))))

(defn run-query
  "Executes a read-only `:seon.query/entity` live against Datahike inside `datahike.reads/with-reads`.
   Returns `{:ok? :kind :ms :reads-count :result}` where `:result` carries its own `:seon/schema`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :seon.query/entity] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :seon.query/entity] :map]]
   :cost/ticks 40000000
   :cost/bytes 33554432}
  ([db q-ent] (run-query db nil q-ent))
  ([db main-db q-ent]
   (let [kind (:seon.query/kind q-ent)
         spec (or (:seon.query/spec q-ent) {})
         lim (max 1 (min 64 (long (or (:seon.query/limit q-ent) (:limit spec) 16))))
         eff-db (if (= :branch (:db spec)) db (or main-db db))
         t0 (System/nanoTime)]
     (try
       (let [outer-reads di/*reads*
             [res rs]
             (reads/with-reads
               #(case kind
                  :datalog            (exec-datalog eff-db spec lim)
                  :index-slice        (exec-index-slice eff-db spec lim)
                  :rule-trace         (exec-rule-trace db main-db spec lim)
                  :schema-fingerprint (exec-schema-fingerprint db main-db spec)
                  :health             (exec-health db main-db)
                  :walk-reads         (exec-walk-reads db main-db spec lim)
                  :entity-pull        (exec-entity-pull db main-db spec)
                  :instrument         (exec-instrument db main-db spec)
                  {:unsupported-kind kind}))
             _ (when (and outer-reads (seq rs)) (swap! outer-reads into rs))
             ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)]
         {:ok? true
          :kind kind
          :ms ms
          :reads-count (count rs)
          :reads-sample (vec (take 8 (map #(select-keys % [:index :a :e :v]) rs)))
          :result res})
       (catch Throwable ex
         {:ok? false
          :kind kind
          :ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
          :reads-count 0
          :error (or (.getMessage ex) (str ex))})))))

(defn- render-param-switchers [qid spec]
  (when-let [choices (seq (:choices spec))]
    [:div {:class "query-params"}
     (for [{:keys [param label options]} choices
           :let [cur (get spec param)]]
       [:div {:class "pill-row"}
        [:span {:class "muted tiny mono"} (str (or label (name param)) ":")]
        (for [opt options
              :let [val (if (map? opt) (:value opt) opt)
                    lbl (if (map? opt) (:label opt) (pr-str opt))
                    active? (= cur val)]]
          [:button {:type "button"
                    :class (str "btn sm" (when active? " primary"))
                    :on-click (list 'seon.web/set-query-param! qid param val)}
           lbl])])]))

(defn render
  "Thin, composable schema renderer (`:seon.render/hiccup`) for `:seon.query/entity`.
   Executes the query live and delegates rendering of `:result` cleanly via `seon.render/render-value`."
  {:malli/schema [:=> [:cat [:map [:db {:optional true} [:maybe :seon.host/db]]
                                  [:main-db {:optional true} [:maybe :seon.host/db]]]
                            :seon.query/entity] :any]
   :cost/ticks 45000000
   :cost/bytes 33554432}
  [{:keys [db main-db mode board-id] :as opts} q-ent]
  (let [eff-db (or main-db db)
        qid (:seon.query/id q-ent)
        live-q (if (and eff-db qid (contains? (:schema eff-db) :seon.query/id))
                 (let [p (d/pull eff-db '[*] [:seon.query/id qid])]
                   (if (:seon.query/id p) p q-ent))
                 q-ent)
        spec (or (:seon.query/spec live-q) {})
        exec (when (or db main-db) (run-query (or db main-db) main-db live-q))
        refs (:seon.query/refs live-q)
        human? (= :human (or mode :human))]
    [:div {:class "card tile-card query-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge blue mono"} (str "query/" (name (or (:seon.query/kind live-q) :unknown)))]
       [:strong (or (:seon.query/title live-q) (str qid))]]
      [:div {:class "pill-row"}
       (when exec
         (list
          [:span {:class "badge green mono"} (str (:ms exec) " ms")]
          [:span {:class "pill mono"} (str (:reads-count exec 0) " reads")]))
       (when qid [:span {:class "muted tiny mono"} (str qid)])]]
     (when-let [doc (:seon.query/doc live-q)]
       (when-not (str/blank? doc)
         [:p {:class "sm-copy muted"} (rmd/render-inline-prose doc)]))
     (when (and human? qid)
       (render-param-switchers qid spec))
     (cond
       (nil? exec)
       [:p {:class "muted tiny"} "No live DB attached to execute query."]

       (not (:ok? exec))
       [:div {:class "notice red mono tiny"} (str "Query error: " (:error exec))]

       :else
       (render/render-value eff-db (:result exec)
                            (assoc opts :db eff-db :main-db eff-db
                                   :layout (if (= :health (:seon.query/kind live-q)) :compact-summary (:layout opts)))))
     (when (and human? (seq refs))
       [:div {:class "pill-row" :style "margin-top:8px"}
        [:span {:class "muted tiny mono"} "Live refs:"]
        (for [r (take 8 refs)]
          (if board-id
            [:button {:type "button" :class "crumb-btn mono"
                      :on-click (list 'seon.web/set-board-state! board-id {:inspect r})}
             (pr-str r)]
            [:span {:class "pill mono"} (pr-str r)]))])
     (when human?
       [:details {:class "fold" :style "margin-top:8px"}
        [:summary {:class "muted tiny mono"} "Inspect live query spec & datahike.reads"]
        [:pre {:class "code-box tiny"} [:code (pr-str (dissoc spec :choices))]]
        (when (seq (:reads-sample exec))
          [:pre {:class "code-box tiny" :style "margin-top:6px"}
           [:code (str ";; datahike.reads sample (" (:reads-count exec) " total):\n"
                       (pr-str (:reads-sample exec)))]])])]))
