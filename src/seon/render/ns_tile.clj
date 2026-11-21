(ns seon.render.ns-tile
  "Level-zoomed namespace & router subsystem-table tile renderer (`ns-tile` + working-schema/prefix indexers)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.health :as health]
            [seon.render.data :as rdata :refer [first-doc-line reads-main? router-agent?]]
            [seon.render.markdown :as rmd]))

(defn ns-working-schemas
  "Derives the Malli schemas that namespace `nsym` (and its immediate child namespaces) owns or
   reads/writes using flat linear AEVT scans (zero per-var pulls, pulls only top `max-n` schemas)."
  ([db nsym] (ns-working-schemas db nsym 14))
  ([db nsym max-n]
   (when (and db nsym (contains? (:schema db) :seon.schema/key))
     (let [freq (volatile! {})
           bump! (fn [k w] (when (qualified-keyword? k) (vswap! freq update k (fnil + 0) w)))
           nid (:db/id (d/pull db '[:db/id] [:seon.code.ns/name nsym]))
           child-nss (when (and nid (not= nsym 'seon))
                       (mapv #(:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e %)))
                             (d/datoms db :avet :seon.code.ns/parent nid)))
           scan-ns-set (into #{nsym} (remove nil? child-nss))
           scan-eids (if (contains? (:schema db) :seon.code.var/ns)
                       (reduce (fn [s dt] (if (contains? scan-ns-set (:v dt)) (conj s (:e dt)) s))
                               #{}
                               (d/datoms db :aevt :seon.code.var/ns))
                       #{})
           _ (when (and (seq scan-eids) (contains? (:schema db) :seon.code.var/schema-refs))
               (doseq [dt (d/datoms db :aevt :seon.code.var/schema-refs)]
                 (when (contains? scan-eids (:e dt))
                   (bump! (:v dt) 3))))
           _ (when (and (seq scan-eids) (contains? (:schema db) :seon.code.var/keywords))
               (doseq [dt (d/datoms db :aevt :seon.code.var/keywords)]
                 (when (contains? scan-eids (:e dt))
                   (bump! (:v dt) 1))))
           ns-str (name nsym)
           owned-prefix (str ns-str ".")
           schema-eid-by-key (reduce (fn [m dt]
                                       (let [k (:v dt)
                                             kns (namespace k)]
                                         (when (and kns (or (= kns ns-str) (str/starts-with? kns owned-prefix)))
                                           (bump! k 2))
                                         (assoc m k (:e dt))))
                                     {}
                                     (d/datoms db :aevt :seon.schema/key))
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
       (doseq [dt (d/datoms db :avet :seon.code.var/ns nsym)
               :let [v (d/pull db (if has-kw?
                                    '[:seon.code.var/keywords :seon.code.var/schema-refs]
                                    '[:seon.code.var/schema-refs])
                               (:e dt))]]
         (doseq [k (:seon.code.var/schema-refs v)] (bump! k 3))
         (doseq [k (:seon.code.var/keywords v)] (bump! k 1)))
       (->> @freq
            (sort-by (fn [[kns w]] [(- w) kns]))
            (map first)
            (take max-n)
            vec)))))

(defn all-ns-schema-prefixes
  "Precomputes `{nsym [top-schema-ns ...]}` across all namespaces in `db` using 3 linear AEVT scans
   (zero per-var `d/pull` calls — replaces O(N) pulls inside router `ns-tile`)."
  [db known-schema-keys max-n]
  (if (or (nil? db) (empty? known-schema-keys) (not (contains? (:schema db) :seon.code.var/ns)))
    {}
    (let [var-ns-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.var/ns))
          freq-by-ns (volatile! {})
          bump! (fn [nsym k w]
                  (when (and nsym (contains? known-schema-keys k))
                    (let [kns (namespace k)]
                      (vswap! freq-by-ns update-in [nsym kns] (fnil + 0) w))))]
      (when (contains? (:schema db) :seon.code.var/schema-refs)
        (doseq [dt (d/datoms db :aevt :seon.code.var/schema-refs)]
          (bump! (get var-ns-by-e (:e dt)) (:v dt) 3)))
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

(defn- ns-tile-full
  [{:keys [db main-db mode agent params]} ns-ent]
  (let [nsym (:seon.code.ns/name ns-ent)
        eff-db (if (reads-main? agent) (or main-db db) db)
        prefs (or (:seon.agent/context-prefs agent) {})
        max-vars (long (or (:max-vars params) (:max-vars-shown prefs) 24))
        max-schemas (long (or (:max-schemas-shown prefs) 14))
        vars (when (and eff-db nsym)
               (->> (d/datoms eff-db :avet :seon.code.var/ns nsym)
                    (map #(d/pull eff-db '[:seon.code.var/name :seon.code.var/arglists :seon.code.var/doc
                                           :seon.code.var/schema :seon.code.var/private? :seon.code.var/test?] (:e %)))
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
        agent-by-scope (when (and main-db (seq children))
                         (into {}
                               (keep (fn [dt]
                                       (let [a (d/pull main-db '[:seon.agent/id :seon.agent/scope] (:e dt))]
                                         (when (and (:seon.agent/scope a)
                                                    (not (str/starts-with? (str (namespace (:seon.agent/id a))) "example")))
                                           [(:seon.agent/scope a) (:seon.agent/id a)]))))
                               (d/datoms main-db :aevt :seon.agent/id)))
        router-view? (and (seq children) (or (empty? vars) (router-agent? agent)))
        schema-db (if (and eff-db (contains? (:schema eff-db) :seon.code.var/keywords)
                           (first (d/datoms eff-db :aevt :seon.code.var/keywords)))
                    eff-db
                    (or main-db eff-db))
        schemas (when (and schema-db nsym (not router-view?))
                  (ns-working-schemas schema-db nsym max-schemas))
        reaching-tests (when (and schema-db nsym (not router-view?) (empty? own-test-vars))
                         (code/reaching-tests schema-db nsym))
        linked-docs (when (and schema-db nsym (not router-view?))
                      (code/ns-linked-docs schema-db nsym 6))
        est-tokens (when (and schema-db nsym (not router-view?) (seq vars))
                     (quot (reduce + 0
                                   (map #(count (str (:seon.code.form/src (d/pull schema-db '[:seon.code.form/src] (:e %)))))
                                        (d/datoms schema-db :avet :seon.code.form/ns nsym)))
                           4))
        undoc-pub (when (and (not router-view?) (seq pub-vars))
                    (count (filter #(str/blank? (str (:seon.code.var/doc %))) pub-vars)))
        schemed-pub (when (and (not router-view?) (seq pub-vars))
                      (count (filter :seon.code.var/schema pub-vars)))
        static-alerts (when (and schema-db nsym (not (router-agent? agent)) (not (false? (:show-health? params))) (= nsym (:seon.agent/scope agent)))
                        (health/static-problems-for-scope schema-db nsym))
        req-aliases (when (and eff-db nsym (not router-view?))
                      (ns-require-aliases (:seon.code.form/src (d/pull eff-db '[:seon.code.form/src] [:seon.code.form/key (str nsym "#ns")]))))
        reqs (->> (:seon.code.ns/requires ns-ent)
                  (filter #(str/starts-with? (name %) "seon."))
                  sort
                  (take 10)
                  vec)
        caller-nss (when (and db (seq pub-vars) (not router-view?))
                     (->> pub-vars
                          (mapcat #(d/datoms db :avet :seon.code.var/refs (:seon.code.var/name %)))
                          (keep #(:seon.code.var/ns (d/pull db '[:seon.code.var/ns] (:e %))))
                          (remove #(= % nsym))
                          distinct sort (take 8) vec))]
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
     (when (seq children)
       (if router-view?
         (let [prod-children (remove #(str/ends-with? (name (:seon.code.ns/name %)) "-test") children)
               test-children (filter #(str/ends-with? (name (:seon.code.ns/name %)) "-test") children)
               known-schemas (if (and schema-db (contains? (:schema schema-db) :seon.schema/key))
                               (into #{} (map :v) (d/datoms schema-db :aevt :seon.schema/key))
                               #{})
               sprefs-by-ns (all-ns-schema-prefixes schema-db known-schemas 3)
               var-counts (if (and schema-db (contains? (:schema schema-db) :seon.code.var/ns))
                            (frequencies (map :v (d/datoms schema-db :aevt :seon.code.var/ns)))
                            {})]
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
           (for [c children
                 :let [cn (:seon.code.ns/name c)
                       vc (count (d/datoms db :avet :seon.code.var/ns cn))]]
             [:li {:class "tiny"}
              [:strong {:class "mono"} (str cn)]
              (str " (" vc " vars): " (or (first-doc-line (:seon.code.ns/doc c) 110) "—"))])]]))
     (when (and (not router-view?) (seq pub-vars))
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
          (when ex-str (str " · ex: `" ex-str "`"))])]])
     (when (and (not router-view?) (or (seq own-test-vars) (seq reaching-tests) (seq pub-vars)))
       (let [tlist (if (seq own-test-vars) (mapv :seon.code.var/name own-test-vars) reaching-tests)
             test-nss (->> tlist (keep #(when-let [s (namespace %)] (symbol s))) distinct (take 2) vec)
             test-reqs (when (and (empty? own-test-vars) (seq test-nss))
                         (keep (fn [tns]
                                 (let [as (ns-require-aliases (:seon.code.form/src (d/pull eff-db '[:seon.code.form/src] [:seon.code.form/key (str tns "#ns")])))]
                                   (when (seq as) (str tns " " (str/join " " (take 8 as))))))
                               test-nss))]
       [:div {:class "stack-sm" :style "margin-top:6px"}
        [:div {:class "pill-row"}
         [:span {:class "muted tiny"}
          (if (seq own-test-vars)
            (str "Tests (" (count tlist) "):")
            (str "Covering tests (" (count tlist) "):"))]
         (if (seq tlist)
           (for [tsym (take 10 tlist)]
             [:span {:class "pill mono green-text"} (str tsym)])
           [:span {:class "muted tiny"} "none — add a reaching deftest!"])]
        (when (seq test-reqs)
          [:p {:class "tiny muted mono" :style "margin:2px 0 0"}
           (str "Test ns requires: " (str/join " | " test-reqs))])]))
     (when (and (not router-view?) (seq linked-docs))
       [:div {:class "stack-sm" :style "margin-top:6px"}
        [:span {:class "kicker"} (str "Linked Docs (" (count linked-docs) ")")]
        [:ul {:style "margin:4px 0 0 16px;padding:0"}
         (for [{:keys [id heading]} linked-docs]
           [:li {:class "tiny"}
            [:code {:class "mono"} id]
            (when-not (str/blank? heading) (str " — " heading))])]])
     (when (and (not router-view?) (seq priv-vars))
       [:p {:class "tiny muted mono" :style "margin:4px 0 0"}
        (str "Private helpers (" (count priv-vars) "): "
             (str/join ", " (map #(name (:seon.code.var/name %)) (take 20 priv-vars))))])
     (when (and (not router-view?) (or (seq req-aliases) (seq reqs) (seq caller-nss)))
       [:div {:class "pill-row" :style "margin-top:6px"}
        (when (or (seq req-aliases) (seq reqs))
          [:span {:class "muted tiny mono"}
           (if (seq req-aliases)
             (str "Requires: " (str/join " " (take 14 req-aliases)))
             (str "Calls: " (str/join ", " (map str reqs))))])
        (when (seq caller-nss)
          [:span {:class "muted tiny"} (str " · Callers: " (str/join ", " (map str caller-nss)))])])
     (when (seq schemas)
       [:div {:class "stack-sm" :style "margin-top:8px"}
        [:span {:class "kicker"} (str "Working Schemas (" (count schemas) ")")]
        [:ul {:style "margin:4px 0 0 16px;padding:0"}
         (for [{:keys [key form doc dh-type dh-card]} schemas
               :let [fstr (pr-str form)]]
           [:li {:class "tiny"}
            [:code {:class "mono"} (str key)]
            (when dh-type (str " [" (name dh-type) (when (= :db.cardinality/many dh-card) " many") "]"))
            (str " " (if (> (count fstr) 80) (str (subs fstr 0 80) "…") fstr))
            (when-let [dl (first-doc-line doc 72)] (str " — " dl))])]])
     (when (seq static-alerts)
       [:div {:class "stack-sm" :style "margin-top:8px"}
        [:span {:class "kicker"} (str "Health & Efficiency Alerts (" (count static-alerts) ")")]
        [:ul {:style "margin:4px 0 0 16px;padding:0"}
         (for [p (take 8 static-alerts)]
           [:li {:class "tiny"}
            [:span {:class (str "badge " (if (= :error (:severity p)) "red" "amber")) :style "margin-right:4px"}
             (name (:class p))]
            [:code {:class "mono"} (:subject p)]
            (str " — " (:message p))])]])
     (when (and (not router-view?) (seq pub-vars))
       [:p {:class "tiny muted" :style "margin-top:6px"}
        (str "Context hygiene: "
             (count pub-vars) " public vars ("
             (- (count pub-vars) (or undoc-pub 0)) " documented, "
             (or schemed-pub 0) " :malli/schema) · "
             (count (or reaching-tests [])) " covering tests"
             (when (pos? (or est-tokens 0))
               (str " · ~" est-tokens " tok"
                    (when (> est-tokens 6000) " (oversized: split into child sub-namespaces!)"))))])
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