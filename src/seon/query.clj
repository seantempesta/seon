(ns seon.query
  "General, read-only executable Datahike query entities (`:seon.query/entity`):
   - Any agent can store, share, evaluate, or pin a `:seon.query/entity` and watch it execute live
     against Datahike inside `datahike.reads/with-reads` with latency + exact datom read-set telemetry.
   - Co-located renderer `seon.query/render` handles both `:mode :human` (interactive parameter
     switchers, live tables, recursive sub-entity rendering) and `:mode :agent` / `:model`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.render :as render]
            [seon.render.print :as rprint]
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
    {:display :table
     :columns cols
     :total (count rows)
     :rows (vec (take lim rows))
     :more? (> (count rows) lim)}))

(defn- exec-index-slice [db spec lim]
  (if-let [pats (seq (:patterns spec))]
    (let [rows (mapv (fn [{:keys [label pattern note]}]
                       [(or label (pr-str pattern))
                        (sq/count-range db pattern)
                        (or note (pr-str pattern))])
                     pats)]
      {:display :table
       :columns ["Entity / Fact Class" "Live Count" "Index Pattern"]
       :total (count rows)
       :rows rows
       :more? false})
    (let [pat (vec (or (:pattern spec) [nil :seon.schema/key nil]))
          pv (sq/preview-slice db pat {:limit lim
                                       :offset (long (or (:offset spec) 0))
                                       :reverse? (boolean (:reverse? spec))})
          rows (mapv (fn [dt] [(:e dt) (:a dt) (:v dt) (:tx dt)]) (:datoms pv))]
      {:display :table
       :columns ["eid" "attr" "value" "tx"]
       :total (:total pv)
       :rows rows
       :more? (:more? pv)
       :pattern pat})))

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
        fired (filterv #(pos? (long (:fired % 0))) (:runs res))
        tile-rows (mapv (fn [t]
                          [(:id t) (:schema t) (:source t) (:zone t) (:layout t) (:view-fn t) (:priority t)])
                        (take lim (:all-tiles res)))]
    {:display :rule-trace
     :agent aid
     :target target
     :rules-count (:rules-count res)
     :fired-count (count fired)
     :facts-count (:facts-count res)
     :tiles-count (count (:all-tiles res))
     :derive-ms (:ms res)
     :fired-rows (mapv (fn [r] [(:stratum r) (:rule-id r) (:category r) (:fired r) (:asserted-count r) (:reads-count r)])
                       (take lim fired))
     :tile-rows tile-rows}))

(defn- exec-schema-fingerprint [db main-db spec]
  (let [ref (:entity spec)
        mdb (or main-db db)
        ix (schema/entity-index (or mdb db))
        found (pull-ref db mdb ref)
        ent (:ent found)]
    (if-not (map? ent)
      {:display :value :value {:entity ref :found? false}}
      (let [fs (six/facets-of ix ent)
            ms (six/most-specific ix ent)
            primary (if (qualified-keyword? ms) ms (:schema (first fs)))
            rfn (when primary (six/schema-property ix primary :seon.render/hiccup))
            outgoing (->> (keys ent)
                          (keep (fn [k] (let [ts (six/targets ix k)] (when (seq ts) [k ts]))))
                          (sort-by (comp str first))
                          vec)
            incoming (when primary (six/reaching ix primary))]
        {:display :fingerprint
         :ref ref
         :eid (:eid found)
         :primary primary
         :renderer (or rfn :seon.render/self-printer)
         :facets fs
         :outgoing outgoing
         :incoming (vec incoming)
         :entity (with-meta ent (when primary {:seon/schema primary}))}))))

(defn- exec-health [db main-db lim]
  (let [health-fn (requiring-resolve 'seon.health/report)
        rep (health-fn (or main-db db))
        errs (vec (:errors rep))
        warns (vec (:warnings rep))
        by-cls (->> (concat errs warns)
                    (group-by :class)
                    (map (fn [[cls xs]] [cls (name (:severity (first xs) :warning)) (count xs)]))
                    (sort-by (fn [[cls _ n]] [(- n) (str cls)]))
                    vec)]
    {:display :health
     :ok? (:ok? rep)
     :errors-count (count errs)
     :warnings-count (count warns)
     :rows (vec (take lim by-cls))}))

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
    {:display :walk-reads
     :subject (or ref (:agent spec) :agent/guide)
     :ms ms
     :reads-count (count rs)
     :indexed-keys (count ridx)
     :summary val
     :rows (mapv (fn [[a n]] [a n]) attrs)}))

(defn- exec-entity-pull [db main-db spec]
  (let [ref (:entity spec)
        mdb (or main-db db)
        ix (schema/entity-index (or mdb db))
        found (pull-ref db mdb ref)
        sk (when (vector? ref) (get-in ix [:identity-schemas (first ref)]))]
    {:display :entity
     :ref ref
     :schema sk
     :value (when-let [ent (:ent found)]
              (with-meta ent (when sk {:seon/schema sk})))}))

(defn run-query
  "Executes a read-only `:seon.query/entity` live against Datahike inside `datahike.reads/with-reads`.
   Returns `{:ok? :kind :ms :reads-count :result}` (or `{:ok? false :error ...}` on fault)."
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
       (let [[res rs]
             (reads/with-reads
               #(case kind
                  :datalog            (exec-datalog eff-db spec lim)
                  :index-slice        (exec-index-slice eff-db spec lim)
                  :rule-trace         (exec-rule-trace db main-db spec lim)
                  :schema-fingerprint (exec-schema-fingerprint db main-db spec)
                  :health             (exec-health db main-db lim)
                  :walk-reads         (exec-walk-reads db main-db spec lim)
                  :entity-pull        (exec-entity-pull db main-db spec)
                  {:display :value :value {:unsupported-kind kind}}))
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

(defn- cell-hiccup [v]
  (cond
    (keyword? v) [:code {:class "mono"} (str v)]
    (symbol? v) [:code {:class "mono"} (str v)]
    (number? v) [:span {:class "mono strong"} (str v)]
    (boolean? v) [:span {:class (str "badge " (if v "green" "red"))} (str v)]
    (string? v) v
    :else [:code {:class "mono tiny"} (let [s (pr-str v)] (if (> (count s) 88) (str (subs s 0 88) "…") s))]))

(defn- render-result-table [cols rows total more?]
  [:div {:class "table-scroll"}
   [:table {:class "data-table"}
    (when (seq cols)
      [:thead [:tr (for [c cols] [:th (str c)])]])
    [:tbody
     (for [row rows]
       [:tr (for [cell (if (sequential? row) row [row])]
              [:td (cell-hiccup cell)])])]]
   (when more?
     [:div {:class "muted tiny mono" :style "margin-top:4px"}
      (str "Showing " (count rows) " of " total " live rows")])])

(defn- render-query-result [db main-db res opts]
  (let [mode (:mode opts :human)
        board-id (:board-id opts)]
    (case (:display res)
      :table
      (render-result-table (:columns res) (:rows res) (:total res) (:more? res))

      :rule-trace
      [:div {:class "stack-sm"}
       [:div {:class "pill-row"}
        [:span {:class "badge blue mono"} (str "agent " (:agent res))]
        [:span {:class "badge purple mono"} (str "target " (:target res))]
        [:span {:class "pill mono"} (str (:rules-count res) " active rules")]
        [:span {:class "pill mono"} (str (:fired-count res) " fired")]
        [:span {:class "pill mono"} (str (:facts-count res) " WM facts")]
        [:span {:class "pill mono"} (str (:tiles-count res) " tiles")]
        [:span {:class "badge green mono"} (str (:derive-ms res) " ms")]]
       (when (seq (:fired-rows res))
         (render-result-table ["Stratum" "Rule" "Category" "Fired" "Asserts" "DB Reads"]
                              (:fired-rows res) (:fired-count res) (> (:fired-count res) (count (:fired-rows res)))))
       (when (and (= mode :human) (seq (:tile-rows res)))
         [:details {:class "fold"}
          [:summary {:class "muted tiny mono"} (str "Projected tiles (" (:tiles-count res) ")")]
          (render-result-table ["Tile ID" "Schema" "Source" "Zone" "Layout" "View Fn" "Pri"]
                               (:tile-rows res) (:tiles-count res) false)])]

      :fingerprint
      [:div {:class "stack-sm"}
       [:div {:class "pill-row"}
        [:span {:class "badge amber mono"} (str "primary " (:primary res))]
        [:span {:class "badge blue mono"} (str "renderer " (:renderer res))]
        [:span {:class "pill mono"} (str (count (:facets res)) " facet(s)")]
        (when (:eid res) [:span {:class "pill mono"} (str "eid " (:eid res))])]
       (when (seq (:facets res))
         (render-result-table ["Facet Schema" "Kind" "Attr Hits" "Identity?" "Drawn?" "Complete?"]
                              (mapv (fn [f] [(:schema f) (:kind f) (:hits f) (:has-identity? f) (:drawn? f) (:complete? f)])
                                    (:facets res))
                              (count (:facets res)) false))
       (when (and (map? (:entity res)) (= mode :human))
         [:details {:class "fold"}
          [:summary {:class "muted tiny mono"} "Live 4-Stage Self-Printer Output (Tree → Hiccup)"]
          (rprint/format-value-hiccup (or main-db db) (:entity res) {:mode :human :limit 10})])]

      :health
      [:div {:class "stack-sm"}
       [:div {:class "pill-row"}
        [:span {:class (str "badge " (if (:ok? res) "green" "red"))} (if (:ok? res) "HEALTH OK" "HEALTH ERRORS")]
        [:span {:class "pill mono"} (str (:errors-count res) " errors")]
        [:span {:class "pill mono"} (str (:warnings-count res) " advisories")]]
       (if (seq (:rows res))
         (render-result-table ["Class" "Severity" "Count"] (:rows res) (count (:rows res)) false)
         [:p {:class "muted tiny"} "Zero open warnings or errors."])]

      :walk-reads
      [:div {:class "stack-sm"}
       [:div {:class "pill-row"}
        [:span {:class "badge amber mono"} (str "subject " (:subject res))]
        [:span {:class "badge green mono"} (str (:reads-count res) " datom reads")]
        [:span {:class "pill mono"} (str (:indexed-keys res) " indexed keys")]
        [:span {:class "pill mono"} (str (:ms res) " ms")]]
       (when (seq (:rows res))
         (render-result-table ["Attribute Read (datahike.reads)" "Reads"] (:rows res) (count (:rows res)) false))]

      :entity
      (if-let [v (:value res)]
        (if (= mode :human)
          (render/render-value (or main-db db) v (assoc opts :board-id board-id))
          [:pre {:class "code-box tiny"} [:code (rprint/format-value-md (or main-db db) v {:render-root? true :limit 12})]])
        [:span {:class "muted tiny"} (str "Entity not found: " (pr-str (:ref res)))])

      (if (= mode :human)
        (rprint/format-value-hiccup (or main-db db) (:value res) {:mode :human :limit 12})
        [:pre {:class "code-box tiny"} [:code (rprint/format-value-md (or main-db db) (:value res) {:limit 12})]]))))

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
  "Co-located dual-mode schema renderer (`:seon.render/hiccup`) for `:seon.query/entity`.
   Executes the query live against Datahike and renders interactive controls + live results."
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
        refs (:seon.query/refs live-q)]
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
         [:p {:class "sm-copy muted"} doc]))
     (when (and (= :human (or mode :human)) qid)
       (render-param-switchers qid spec))
     (cond
       (nil? exec)
       [:p {:class "muted tiny"} "No live DB attached to execute query."]

       (not (:ok? exec))
       [:div {:class "notice red mono tiny"} (str "Query error: " (:error exec))]

       :else
       (render-query-result db main-db (:result exec) opts))
     (when (and (= :human (or mode :human)) (seq refs))
       [:div {:class "pill-row" :style "margin-top:8px"}
        [:span {:class "muted tiny mono"} "Live refs:"]
        (for [r (take 8 refs)]
          (if board-id
            [:button {:type "button" :class "crumb-btn mono"
                      :on-click (list 'seon.web/set-board-state! board-id {:inspect r})}
             (pr-str r)]
            [:span {:class "pill mono"} (pr-str r)]))])
     (when (= :human (or mode :human))
       [:details {:class "fold" :style "margin-top:8px"}
        [:summary {:class "muted tiny mono"} "Inspect live query spec & datahike.reads"]
        [:pre {:class "code-box tiny"} [:code (pr-str (dissoc spec :choices))]]
        (when (seq (:reads-sample exec))
          [:pre {:class "code-box tiny" :style "margin-top:6px"}
           [:code (str ";; datahike.reads sample (" (:reads-count exec) " total):\n"
                       (pr-str (:reads-sample exec)))]])])]))
