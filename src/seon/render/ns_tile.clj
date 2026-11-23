(ns seon.render.ns-tile
  "Level-zoomed namespace & router subsystem-table tile renderer (`ns-tile` + working-schema/prefix indexers)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.health.efficiency :as heff]
            [seon.render.data :as rdata :refer [first-doc-line reads-main? router-agent?]]
            [seon.render.markdown :as rmd]
            [seon.store :as store]))

(defn ns-working-schemas
  "Derives the Malli schemas that namespace `nsym` (and its immediate child namespaces) owns or
   reads/writes using flat linear AEVT scans (zero per-var pulls, pulls only top `max-n` schemas)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :symbol] [:vector :map]] [:=> [:cat :seon.host/db :symbol :int] [:vector :map]]]}
  ([db nsym] (ns-working-schemas db nsym 14))
  ([db nsym max-n]
   (when (and db nsym (contains? (:schema db) :seon.schema/key))
     (let [freq (volatile! {})
           bump! (fn [k w] (when (qualified-keyword? k) (vswap! freq update k (fnil + 0) w)))
           has-own-vars? (boolean (and (contains? (:schema db) :seon.code.var/ns)
                                       (first (d/datoms db :avet :seon.code.var/ns nsym))))
           nid (when-not has-own-vars? (:db/id (d/pull db '[:db/id] [:seon.code.ns/name nsym])))
           child-nss (when (and nid (not= nsym 'seon))
                       (mapv #(:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e %)))
                             (d/datoms db :avet :seon.code.ns/parent nid)))
           scan-ns-set (into #{nsym} (remove nil? child-nss))
           ns-str (name nsym)
           owned-prefix (str ns-str ".")
           schema-eid-by-key (volatile! {})
           schema-key-by-eid (volatile! {})
           _ (doseq [dt (d/datoms db :aevt :seon.schema/key)]
               (let [k (:v dt), e (:e dt), kns (namespace k)]
                 (when (and kns (or (= kns ns-str) (str/starts-with? kns owned-prefix)))
                   (bump! k 2))
                 (vswap! schema-eid-by-key assoc k e)
                 (vswap! schema-key-by-eid assoc e k)))
           skey-map @schema-key-by-eid
           io-set #{:seon.code.var/input :seon.code.var/optional-input :seon.code.var/output}
           _ (when (contains? (:schema db) :seon.code.var/ns)
               (doseq [sn scan-ns-set
                       dt (d/datoms db :avet :seon.code.var/ns sn)
                       vdt (d/datoms db :eavt (:e dt))
                       :let [a (:a vdt)]]
                 (cond
                   (contains? io-set a) (when-let [sk (get skey-map (:v vdt))] (bump! sk 3))
                   (= :seon.code.var/keywords a) (bump! (:v vdt) 1))))
           schema-eid-by-key @schema-eid-by-key
           top-pairs (->> @freq
                          (filter #(contains? schema-eid-by-key (key %)))
                          (sort-by (fn [[k w]] [(- w) (str k)]))
                          (take max-n))]
       (mapv (fn [[k w]]
               (let [ent (d/pull db '[:seon.schema/key :seon.schema/form :seon.schema/doc] (get schema-eid-by-key k))
                     dh (get (:schema db) k)]
                 {:key k
                  :weight w
                  :form (:seon.schema/form ent)
                  :doc (:seon.schema/doc ent)
                  :dh-type (when dh (:db/valueType dh))
                  :dh-card (when dh (:db/cardinality dh))}))
             top-pairs)))))

(defn ns-schema-prefixes
  "Returns top distinct schema namespaces (e.g. `seon.agent`, `seon.user`) touched by `nsym`
   in O(|vars in nsym|), reusing a precomputed `known-schema-keys` set (zero schema-form pulls!)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] [:maybe :symbol] :int] [:maybe [:vector :string]]] [:=> [:cat [:maybe :seon.host/db] [:set :qualified-keyword] [:maybe :symbol] :int] [:maybe [:vector :string]]]]}
  ([db nsym max-n]
   (let [known (if (and db (contains? (:schema db) :seon.schema/key))
                 (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))
                 #{})]
     (ns-schema-prefixes db known nsym max-n)))
  ([db known-schema-keys nsym max-n]
   (when (and db nsym (seq known-schema-keys))
     (let [has-kw? (contains? (:schema db) :seon.code.var/keywords)
           freq (volatile! {})
           bump! (fn [k w]
                   (when (contains? known-schema-keys k)
                     (vswap! freq update (namespace k) (fnil + 0) w)))
           ns-str (name nsym)
           owned-prefix (str ns-str ".")]
       (doseq [k known-schema-keys
               :let [kns (namespace k)]
               :when (and kns (or (= kns ns-str) (str/starts-with? kns owned-prefix)))]
         (bump! k 2))
       (let [io-attrs (filterv #(contains? (:schema db) %) [:seon.code.var/input :seon.code.var/optional-input :seon.code.var/output])
             skey-by-e (when (seq io-attrs) (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.schema/key)))]
         (doseq [dt (d/datoms db :avet :seon.code.var/ns nsym)
                 :let [ve (:e dt)]]
           (doseq [a io-attrs
                   idt (d/datoms db :eavt ve a)]
             (when-let [sk (get skey-by-e (:v idt))] (bump! sk 3)))
           (when has-kw?
             (doseq [kdt (d/datoms db :eavt ve :seon.code.var/keywords)]
               (bump! (:v kdt) 1)))))
       (->> @freq
            (sort-by (fn [[kns w]] [(- w) kns]))
            (map first)
            (take max-n)
            vec)))))

(defn all-ns-schema-prefixes
  "Precomputes `{nsym [top-schema-ns ...]}` across all namespaces in `db` using 3 linear AEVT scans
   (zero per-var `d/pull` calls — replaces O(N) pulls inside router `ns-tile`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:set :qualified-keyword] :int] :map]}
  [db known-schema-keys max-n]
  (if (or (nil? db) (empty? known-schema-keys) (not (contains? (:schema db) :seon.code.var/ns)))
    {}
    (let [var-ns-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.var/ns))
          freq-by-ns (volatile! {})
          bump! (fn [nsym k w]
                  (when (and nsym (contains? known-schema-keys k))
                    (let [kns (namespace k)]
                      (vswap! freq-by-ns update-in [nsym kns] (fnil + 0) w))))]
      (let [dh (:schema db)
            skey-by-e (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.schema/key))
            scan-io! (fn [dts]
                       (doseq [dt dts]
                         (when-let [sk (get skey-by-e (:v dt))]
                           (bump! (get var-ns-by-e (:e dt)) sk 3))))]
        (when (contains? dh :seon.code.var/input) (scan-io! (d/datoms db :aevt :seon.code.var/input)))
        (when (contains? dh :seon.code.var/optional-input) (scan-io! (d/datoms db :aevt :seon.code.var/optional-input)))
        (when (contains? dh :seon.code.var/output) (scan-io! (d/datoms db :aevt :seon.code.var/output))))
      (when (contains? (:schema db) :seon.code.var/keywords)
        (doseq [dt (d/datoms db :aevt :seon.code.var/keywords)]
          (bump! (get var-ns-by-e (:e dt)) (:v dt) 1)))
      (let [base @freq-by-ns]
        (into {}
              (for [nsym (distinct (vals var-ns-by-e))
                    :let [ns-str (name nsym)
                          owned-prefix (str ns-str ".")
                          own-bumps (reduce (fn [m k]
                                              (let [kns (namespace k)]
                                                (if (and kns (or (= kns ns-str) (str/starts-with? kns owned-prefix)))
                                                  (update m kns (fnil + 0) 2)
                                                  m)))
                                            (get base nsym {})
                                            known-schema-keys)]]
                [nsym (->> own-bumps
                           (sort-by (fn [[kns w]] [(- w) kns]))
                           (map first)
                           (take max-n)
                           vec)]))))))

(defn ns-require-aliases
  "Parses a namespace's `(ns ... (:require ...))` source into sorted compact require specs
   like `[clojure.string :as str]` so workers see every alias in scope on Turn 0."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :string]]
   :cost/ticks 50000}
  [ns-src]
  (if (str/blank? ns-src)
    []
    (try
      (let [form (binding [*read-eval* false] (read-string ns-src))
            req-clauses (when (and (seq? form) (= 'ns (first form)))
                          (filter #(and (seq? %) (= :require (first %))) (rest form)))
            specs (mapcat rest req-clauses)]
        (->> specs
             (keep (fn [sp]
                     (cond
                       (vector? sp)
                       (let [lib (first sp)
                             tail (rest sp)
                             opts (if (even? (count tail)) (apply hash-map tail) {})
                             al (:as opts)
                             rf (:refer opts)]
                         (cond
                           (and lib al) (str "[" lib " :as " al "]")
                           (and lib rf) (str "[" lib " :refer " (pr-str rf) "]")
                           lib (str lib)))
                       (symbol? sp) (str sp))))
             distinct
             sort
             vec))
      (catch Throwable _ []))))

(defn ns-summary-strip
  "Ultra-compact 3-line summary strip for a broad parent namespace demoted by rules (< 0.5 ms, ~250 chars)."
  {:malli/schema [:=> [:cat :map :map] [:vector :any]]
   :cost/ticks 500000}
  [{:keys [db main-db mode agent tags why]} ns-ent]
  (let [nsym (:seon.code.ns/name ns-ent)
        eff-db (if (reads-main? agent) (or main-db db) db)
        var-n (if (and eff-db nsym) (count (d/datoms eff-db :avet :seon.code.var/ns nsym)) 0)
        kids (when (and eff-db (:db/id ns-ent))
               (->> (d/datoms eff-db :avet :seon.code.ns/parent (:db/id ns-ent))
                    (keep #(:seon.code.ns/name (d/pull eff-db '[:seon.code.ns/name] (:e %))))
                    sort (take 10) vec))]
    [:div {:class "card tile-card compact-tile"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge muted"} "ns summary"]
       [:strong {:class "mono"} (str nsym)]
       (for [tg (sort tags)] [:span {:class "badge amber mono"} (name tg)])]
      [:span {:class "muted tiny mono"} (str var-n " vars · " (:seon.code.ns/path ns-ent ""))]]
     (when-let [doc (:seon.code.ns/doc ns-ent)]
       [:p {:class "muted sm-copy"} (let [d (first-doc-line doc 140)] (if (= mode :agent) d (rmd/render-inline-prose d)))])
     (when (seq kids)
       [:div {:class "pill-row"}
        [:span {:class "muted tiny"} "Sub-namespaces:"]
        (for [k kids] [:span {:class "pill mono"} (str k)])])
     (when (and (seq why) (not= mode :agent))
       [:p {:class "tiny muted"} (str/join " · " why)])]))

(defonce ^:private ^:seon.flow/edge ns-tile-data-cache
  (store/lru-cache 64))

(defn- db-snap-key [db]
  (when (and db
             (not (instance? datahike.db.AsOfDB db))
             (not (instance? datahike.db.HistoricalDB db)))
    (when-let [cid (try (d/commit-id db) (catch Throwable _ nil))]
      [(get-in db [:config :store :id])
       (get-in db [:config :branch] :main)
       cid
       (:max-tx db)])))

(defn- fast-reaching-tests [schema-db vars]
  (when (and schema-db (contains? (:schema schema-db) :seon.code.var/refs))
    (let [seeds (into #{} (keep :seon.code.var/name) vars)]
      (loop [hop 0 frontier seeds seen seeds seen-e #{} tests #{}]
        (if (or (empty? frontier) (>= hop 2) (>= (count tests) 6))
          (vec (sort tests))
          (let [fe (into #{} (comp (mapcat #(d/datoms schema-db :avet :seon.code.var/refs %))
                                   (map :e) (remove seen-e) (take 64))
                         (if (pos? hop) (take 16 frontier) frontier))
                [nt nf] (reduce (fn [[ts nf] e]
                                  (let [vn (:v (first (d/datoms schema-db :eavt e :seon.code.var/name)))]
                                    (cond
                                      (nil? vn) [ts nf]
                                      (true? (:v (first (d/datoms schema-db :eavt e :seon.code.var/test?)))) [(conj ts vn) nf]
                                      (not (seen vn)) [ts (conj nf vn)]
                                      :else [ts nf])))
                                [tests #{}] fe)]
            (recur (inc hop) nf (into seen nf) (into seen-e fe) nt)))))))

(defn- fast-linked-docs [schema-db nsym ns-ent]
  (let [dh (:schema schema-db)
        seg2 (second (str/split (name nsym) #"\."))
        stem (when seg2 (str "docs/" seg2 ".md"))
        eids (distinct (concat (when (and (:db/id ns-ent) (contains? dh :seon.code.doc/references))
                                 (map :e (take 10 (d/datoms schema-db :avet :seon.code.doc/references (:db/id ns-ent)))))
                               (when (and stem (contains? dh :seon.code.doc/path))
                                 (map :e (take 6 (d/datoms schema-db :avet :seon.code.doc/path stem))))))
        indexed (vec (take 6 (sort-by :id (keep (fn [e]
                                                  (let [p (d/pull schema-db '[:seon.code.doc/id :seon.code.doc/path :seon.code.doc/heading] e)]
                                                    (when (:seon.code.doc/id p)
                                                      {:id (:seon.code.doc/id p) :path (:seon.code.doc/path p) :heading (:seon.code.doc/heading p)})))
                                                eids))))]
    (if (seq indexed) indexed (code/ns-linked-docs schema-db nsym 6))))

(defn- compute-ns-tile-data
  [db eff-db main-db schema-db agent params ns-ent max-schemas]
  (let [nsym (:seon.code.ns/name ns-ent)
        vars (when (and eff-db nsym)
               (->> (d/datoms eff-db :avet :seon.code.var/ns nsym)
                    (map (fn [dt]
                           (reduce (fn [m vdt]
                                     (case (:a vdt)
                                       (:seon.code.var/name :seon.code.var/arglists :seon.code.var/doc
                                        :seon.code.var/schema :seon.code.var/private? :seon.code.var/test?)
                                       (assoc m (:a vdt) (:v vdt))
                                       m))
                                   {} (d/datoms eff-db :eavt (:e dt)))))
                    (sort-by #(str (:seon.code.var/name %)))
                    vec))
        pub-vars (filterv #(and (not (:seon.code.var/private? %)) (not (:seon.code.var/test? %))) vars)
        priv-vars (filterv #(and (:seon.code.var/private? %) (not (:seon.code.var/test? %))) vars)
        own-test-vars (filterv :seon.code.var/test? vars)
        children (when (and eff-db (:db/id ns-ent))
                   (->> (d/datoms eff-db :avet :seon.code.ns/parent (:db/id ns-ent))
                        (map #(d/pull eff-db '[:seon.code.ns/name :seon.code.ns/doc :seon.code.ns/path] (:e %)))
                        (sort-by #(str (:seon.code.ns/name %)))
                        vec))
        router-view? (and (seq children) (or (empty? vars) (router-agent? agent)))
        agent-by-scope (when (and router-view? main-db (seq children))
                         (into {}
                               (keep (fn [dt]
                                       (let [a (d/pull main-db '[:seon.agent/id :seon.agent/scope] (:e dt))]
                                         (when (and (:seon.agent/scope a)
                                                    (not (str/starts-with? (str (namespace (:seon.agent/id a))) "example")))
                                           [(:seon.agent/scope a) (:seon.agent/id a)]))))
                               (d/datoms main-db :aevt :seon.agent/id)))
        schemas (when (and schema-db nsym (not router-view?))
                  (ns-working-schemas schema-db nsym max-schemas))
        reaching-tests (when (and schema-db nsym (not router-view?) (empty? own-test-vars))
                         (fast-reaching-tests schema-db vars))
        linked-docs (when (and schema-db nsym (not router-view?))
                      (fast-linked-docs schema-db nsym ns-ent))
        ns-form-eids (when (and schema-db nsym (not router-view?) (seq vars))
                       (mapv :e (d/datoms schema-db :avet :seon.code.form/ns nsym)))
        est-tokens (when (seq ns-form-eids)
                     (quot (reduce + 0
                                   (map #(count (str (:v (first (d/datoms schema-db :eavt % :seon.code.form/src)))))
                                        ns-form-eids))
                           4))
        static-alerts (when (and schema-db nsym (not (router-agent? agent)) (not (false? (:show-health? params))) (= nsym (:seon.agent/scope agent)))
                        (if (empty? vars)
                          (heff/static-efficiency-warnings schema-db nsym)
                          (cond-> []
                            (and est-tokens (> est-tokens 8000))
                            (conj {:class :oversized-namespace :severity :warning :kind :static :ns nsym
                                   :subject (str nsym) :message (str "Namespace " nsym " (~" est-tokens " tok > 8000).")}))))
        req-aliases (when (and eff-db nsym (not router-view?))
                      (ns-require-aliases (:seon.code.form/src (d/pull eff-db '[:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")]))))
        caller-nss (when (and db (seq pub-vars) (not router-view?))
                     (let [caller-eids (into #{} (comp (mapcat #(d/datoms db :avet :seon.code.var/refs (:seon.code.var/name %))) (map :e)) pub-vars)]
                       (->> caller-eids
                            (keep #(:v (first (d/datoms db :eavt % :seon.code.var/ns))))
                            (remove #(= % nsym))
                            distinct sort (take 8) vec)))
        tlist (if (seq own-test-vars) (mapv :seon.code.var/name own-test-vars) reaching-tests)
        test-nss (->> tlist (keep #(when-let [s (namespace %)] (symbol s))) distinct (take 2) vec)
        test-reqs (when (and (not router-view?) (empty? own-test-vars) (seq test-nss))
                    (vec (keep (fn [tns]
                                 (let [as (ns-require-aliases (:seon.code.form/src (d/pull eff-db '[:seon.code.form/src] [:seon.code.form/key (str tns "#ns")])))]
                                   (when (seq as) (str tns " " (str/join " " (take 8 as))))))
                               test-nss)))
        known-schemas (when (and router-view? schema-db (contains? (:schema schema-db) :seon.schema/key))
                        (into #{} (map :v) (d/datoms schema-db :aevt :seon.schema/key)))
        sprefs-by-ns (when router-view? (all-ns-schema-prefixes schema-db known-schemas 3))
        var-counts (when router-view?
                     (if (and schema-db (contains? (:schema schema-db) :seon.code.var/ns))
                       (frequencies (map :v (d/datoms schema-db :aevt :seon.code.var/ns)))
                       (into {} (for [c children :let [cn (:seon.code.ns/name c)]] [cn (count (d/datoms db :avet :seon.code.var/ns cn))]))))
        child-var-counts (when (and (seq children) (not router-view?))
                           (into {} (for [c children :let [cn (:seon.code.ns/name c)]] [cn (count (d/datoms db :avet :seon.code.var/ns cn))])))]
    {:vars vars :pub-vars pub-vars :priv-vars priv-vars :own-test-vars own-test-vars
     :children children :agent-by-scope agent-by-scope :router-view? router-view?
     :schemas schemas :reaching-tests reaching-tests :linked-docs linked-docs :est-tokens est-tokens
     :undoc-pub (when (and (not router-view?) (seq pub-vars)) (count (filter #(str/blank? (str (:seon.code.var/doc %))) pub-vars)))
     :schemed-pub (when (and (not router-view?) (seq pub-vars)) (count (filter :seon.code.var/schema pub-vars)))
     :static-alerts static-alerts :req-aliases req-aliases :caller-nss caller-nss
     :test-reqs test-reqs :sprefs-by-ns sprefs-by-ns :var-counts (or var-counts child-var-counts)}))

(defn- ns-children-section
  [children {:keys [router-view? var-counts sprefs-by-ns agent-by-scope]} mode]
  (when (seq children)
    (if router-view?
      (let [prod-children (remove #(str/ends-with? (name (:seon.code.ns/name %)) "-test") children)
            test-children (filter #(str/ends-with? (name (:seon.code.ns/name %)) "-test") children)]
        [:div {:class "stack-sm" :style "margin-top:8px"}
         [:div {:class "table-scroll"}
          [:table {:class "data-table"}
           [:thead [:tr [:th "Subsystem"] [:th "Vars"] [:th "Schemas"] [:th "Specialist"] [:th "Role"]]]
           [:tbody
            (for [c prod-children
                  :let [cn (:seon.code.ns/name c)
                        vc (get var-counts cn 0)
                        sprefs (get sprefs-by-ns cn)
                        spec (get agent-by-scope cn)]]
              [:tr
               [:td {:class "mono tiny"} (str cn)]
               [:td {:class "mono tiny"} (str vc)]
               [:td {:class "mono tiny muted"} (if (seq sprefs) (str/join ", " sprefs) "—")]
               [:td {:class "mono tiny"} (if spec (str spec) (str ":ns/" cn " (lazy)"))]
               [:td {:class "tiny"} (let [d (or (first-doc-line (:seon.code.ns/doc c) 95) "—")] (if (= mode :agent) d (rmd/render-inline-prose d)))]])]]]
         (when (seq test-children)
           [:p {:class "muted tiny"}
            (str "Test namespaces: "
                 (str/join ", " (map (fn [tc]
                                       (let [cn (:seon.code.ns/name tc)]
                                         (str cn " (" (get var-counts cn 0) ")")))
                                     test-children)))])])
      [:div {:class "stack-sm" :style "margin-top:8px"}
       [:span {:class "kicker"} (str "Sub-namespaces (" (count children) ")")]
       [:ul {:style "margin:4px 0 0 16px;padding:0"}
        (for [c children :let [cn (:seon.code.ns/name c) vc (get var-counts cn 0)]]
          [:li {:class "tiny"}
           [:strong {:class "mono"} (str cn)]
           (str " (" vc " vars): " (or (first-doc-line (:seon.code.ns/doc c) 110) "—"))])]])))

(defn- ns-pub-vars-section
  [pub-vars max-vars]
  (when (seq pub-vars)
    [:div {:class "stack-sm" :style "margin-top:8px"}
     [:span {:class "kicker"} (str "Public API (" (min max-vars (count pub-vars)) "/" (count pub-vars) ")")]
     [:ul {:style "margin:4px 0 0 16px;padding:0"}
      (for [v (take max-vars pub-vars)
            :let [sc (:seon.code.var/schema v)
                  ex-list (when (and (vector? sc) (map? (second sc))) (:examples (second sc)))
                  clean-sc (if (and (vector? sc) (map? (second sc)) (contains? (second sc) :examples))
                             (let [p (dissoc (second sc) :examples)]
                               (if (empty? p) (into [(first sc)] (drop 2 sc)) (assoc sc 1 p)))
                             sc)
                  sc-str (when clean-sc
                           (let [s (pr-str clean-sc)]
                             (if (> (count s) 72) (str (subs s 0 72) "…") s)))
                  ex-str (when-let [[ex-args ex-out] (first ex-list)]
                           (let [call-s (str "(" (name (:seon.code.var/name v))
                                             (when (seq ex-args) (str " " (str/join " " (map pr-str ex-args))))
                                             ") => " (pr-str ex-out))]
                             (if (> (count call-s) 88) (str (subs call-s 0 88) "…") call-s)))]]
        [:li {:class "tiny"}
         [:code {:class "mono"}
          (str (name (:seon.code.var/name v))
               (when (seq (:seon.code.var/arglists v))
                 (str " " (pr-str (:seon.code.var/arglists v)))))]
         (when sc-str (str " " sc-str))
         (when-let [d (first-doc-line (:seon.code.var/doc v) 95)]
           (str " — " d))
         (when ex-str (str " · ex: `" ex-str "`"))])]]))

(defn- ns-tile-full
  [{:keys [db main-db mode agent params]} ns-ent]
  (let [nsym (:seon.code.ns/name ns-ent)
        eff-db (if (reads-main? agent) (or main-db db) db)
        prefs (or (:seon.agent/context-prefs agent) {})
        max-vars (long (or (:max-vars params) (:max-vars-shown prefs) 24))
        max-schemas (long (or (:max-schemas-shown prefs) 14))
        schema-db (if (and eff-db (contains? (:schema eff-db) :seon.code.var/keywords)
                           (first (d/datoms eff-db :aevt :seon.code.var/keywords)))
                    eff-db
                    (or main-db eff-db))
        dk (when-let [ek (db-snap-key eff-db)]
             (when-let [sk (db-snap-key schema-db)]
               [nsym max-schemas (router-agent? agent) (reads-main? agent)
                (and (not (router-agent? agent)) (not (false? (:show-health? params))) (= nsym (:seon.agent/scope agent)))
                ek sk (db-snap-key db) (db-snap-key main-db)]))
        data (if dk
               (store/lru-get! ns-tile-data-cache 64 dk #(compute-ns-tile-data db eff-db main-db schema-db agent params ns-ent max-schemas))
               (compute-ns-tile-data db eff-db main-db schema-db agent params ns-ent max-schemas))
        {:keys [vars pub-vars priv-vars own-test-vars children router-view?
                schemas reaching-tests linked-docs est-tokens undoc-pub schemed-pub
                static-alerts req-aliases caller-nss test-reqs]} data
        reqs (->> (:seon.code.ns/requires ns-ent) (filter #(str/starts-with? (name %) "seon.")) sort (take 10) vec)]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge blue"} (if router-view? "subtree" "ns")]
       [:strong {:class "mono"} (str nsym)]]
      [:span {:class "muted tiny mono"}
       (if router-view?
         (str (count children) " subsystems")
         (str (count pub-vars) " public · " (count vars) " total · " (:seon.code.ns/path ns-ent "")))]]
     (when-let [doc (:seon.code.ns/doc ns-ent)]
       [:p {:class "doc-box sm-copy"} (let [d (first-doc-line doc 180)] (if (= mode :agent) d (rmd/render-inline-prose d)))])
     (ns-children-section children data mode)
     (when (not router-view?) (ns-pub-vars-section pub-vars max-vars))
     (when (and (not router-view?) (or (seq own-test-vars) (seq reaching-tests) (seq pub-vars)))
       (let [tlist (if (seq own-test-vars) (mapv :seon.code.var/name own-test-vars) reaching-tests)]
         [:div {:class "stack-sm" :style "margin-top:6px"}
          [:div {:class "pill-row"}
           [:span {:class "muted tiny"} (if (seq own-test-vars) (str "Tests (" (count tlist) "):") (str "Covering tests (" (count tlist) "):"))]
           (if (seq tlist)
             (for [tsym (take 10 tlist)] [:span {:class "pill mono green-text"} (str tsym)])
             [:span {:class "muted tiny"} "none — add a reaching deftest!"])]
          (when (seq test-reqs)
            [:p {:class "tiny muted mono" :style "margin:2px 0 0"} (str "Test ns requires: " (str/join " | " test-reqs))])]))
     (when (and (not router-view?) (seq linked-docs))
       [:div {:class "stack-sm" :style "margin-top:6px"}
        [:span {:class "kicker"} (str "Linked Docs (" (count linked-docs) ")")]
        [:ul {:style "margin:4px 0 0 16px;padding:0"}
         (for [{:keys [id heading]} linked-docs]
           [:li {:class "tiny"} [:code {:class "mono"} id] (when-not (str/blank? heading) (str " — " heading))])]])
     (when (and (not router-view?) (seq priv-vars))
       [:p {:class "tiny muted mono" :style "margin:4px 0 0"}
        (str "Private helpers (" (count priv-vars) "): " (str/join ", " (map #(name (:seon.code.var/name %)) (take 20 priv-vars))))])
     (when (and (not router-view?) (or (seq req-aliases) (seq reqs) (seq caller-nss)))
       [:div {:class "pill-row" :style "margin-top:6px"}
        (when (or (seq req-aliases) (seq reqs))
          [:span {:class "muted tiny mono"}
           (if (seq req-aliases) (str "Requires: " (str/join " " (take 14 req-aliases))) (str "Calls: " (str/join ", " (map str reqs))))])
        (when (seq caller-nss)
          [:span {:class "muted tiny"} (str " · Callers: " (str/join ", " (map str caller-nss)))])])
     (when (seq schemas)
       [:div {:class "stack-sm" :style "margin-top:8px"}
        [:span {:class "kicker"} (str "Working Schemas (" (count schemas) ")")]
        [:ul {:style "margin:4px 0 0 16px;padding:0"}
         (for [{:keys [key form doc dh-type dh-card]} schemas :let [fstr (pr-str form)]]
           [:li {:class "tiny"} [:code {:class "mono"} (str key)]
            (when dh-type (str " [" (name dh-type) (when (= :db.cardinality/many dh-card) " many") "]"))
            (str " " (if (> (count fstr) 80) (str (subs fstr 0 80) "…") fstr))
            (when-let [dl (first-doc-line doc 72)] (str " — " dl))])]])
     (when (seq static-alerts)
       [:div {:class "stack-sm" :style "margin-top:8px"}
        [:span {:class "kicker"} (str "Health & Efficiency Alerts (" (count static-alerts) ")")]
        [:ul {:style "margin:4px 0 0 16px;padding:0"}
         (for [p (take 8 static-alerts)]
           [:li {:class "tiny"} [:span {:class (str "badge " (if (= :error (:severity p)) "red" "amber")) :style "margin-right:4px"} (name (:class p))] [:code {:class "mono"} (:subject p)] (str " — " (:message p))])]])
     (when (and (not router-view?) (seq pub-vars))
       [:p {:class "tiny muted" :style "margin-top:6px"}
        (str "Context hygiene: " (count pub-vars) " public vars (" (- (count pub-vars) (or undoc-pub 0)) " documented, " (or schemed-pub 0) " :malli/schema) · " (count (or reaching-tests [])) " covering tests"
             (when (pos? (or est-tokens 0)) (str " · ~" est-tokens " tok" (when (> est-tokens 6000) " (oversized: split into child sub-namespaces!)"))))])
     (when (= :agent mode)
       [:p {:class "tiny muted"}
        (if router-view?
          "Route subsystem questions/work via (user/ask! :ns/<subsystem> \"...\") + (user/await! [...]). Specialist agents lazy-spawn on first call and keep their neighborhood context warm."
          (str "REPL: (user/source '" nsym "/fn | \"docs/...\" | :seon.schema/k), (user/callers '" nsym "/fn), (user/tests-for '" nsym "), (user/doc! \"docs/...\" \"...\"), (user/register-schema! ...)."))])]))

(defn ns-tile
  "Level-zoomed schema-attached render for `:seon.code.ns/entity` — dispatches to `ns-summary-strip`
   when rules demote a broad parent namespace (`:layout :summary-strip`), else `ns-tile-full`."
  {:malli/schema [:=> [:cat [:map [:db :seon.host/db]] :map] :any]}
  [{:keys [layout] :as opts} ns-ent]
  (if (= layout :summary-strip)
    (ns-summary-strip opts ns-ent)
    (ns-tile-full opts ns-ent)))