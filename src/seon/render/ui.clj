(ns seon.render.ui
  "Inside-Out Reagent/Hiccup UI driven 100% by Datahike (`:user/default` -> `:seon.user/*`) +
   schema-attached entity tiles powering BOTH the live 120fps Web UI AND the agent's wire context:
   - Everything about where the user is (`:seon.user/viewing`, `:seon.user/view-mode`,
     `:seon.user/sidebar-open?`, `:seon.user/expanded-agents`, `:seon.user/selected-branch`) lives
     in Datahike — restoring LMDB restores the exact screen, and any agent can move the user's view
     via `(user/show-user! ...)`!
   - Collapsible recursive left sidebar (`#seon-sidebar`) shows the full Root -> sub-agent ->
     sub-sub-agent hierarchy via `:seon.agent/parent` refs.
   - Three views of the viewed agent: `:canvas` (clean editorial user canvas), `:workspace`
     (2-panel chat + live `@ctx`/eval tiles), and `:debug` (exact wire request + diagnostics)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datahike.api :as d]
            [fipp.edn :as fipp]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.health :as health]
            [seon.merge :as merge]
            [seon.model :as model]
            [seon.reconciler :as reconciler]
            [seon.render :as render]
            [seon.store :as store]))

;; --- Inside-Out User Viewport State (Read Directly From Datahike `:user/default`) ---

(defn agent-id->slug [aid]
  (when aid
    (if-let [ns-part (namespace aid)]
      (if (= "agent" ns-part)
        (name aid)
        (str ns-part "~" (name aid)))
      (name aid))))

(defn slug->agent-id [^String slug]
  (let [s (str/trim (str slug))]
    (cond
      (str/blank? s) nil
      (str/includes? s "~") (let [[ns-p nm] (str/split s #"~" 2)] (keyword ns-p nm))
      :else (keyword "agent" s))))

(defn agent-href [aid] (str "/agent/" (agent-id->slug aid)))
(defn debug-href [aid] (str "/agent/" (agent-id->slug aid) "/debug"))

(defn user-view-state
  "Reads the user's persistent viewport state (`:user/default`) directly from Datahike `db`."
  [db]
  (let [entry-id (entry-agent-id db)
        u (when (and db (contains? (:schema db) :seon.user/id)
                     (first (d/datoms db :avet :seon.user/id :user/default)))
            (d/pull db '[:seon.user/id :seon.user/view-mode :seon.user/sidebar-open?
                         :seon.user/expanded-agents :seon.user/selected-branch
                         :seon.user/as-of-tx :seon.user/theme {:seon.user/viewing [:seon.agent/id]}]
                    [:seon.user/id :user/default]))
        viewing-id (or (get-in u [:seon.user/viewing :seon.agent/id]) entry-id)]
    {:user-id (or (:seon.user/id u) :user/default)
     :entry-id entry-id
     :viewing-id viewing-id
     :view-mode (or (:seon.user/view-mode u) :canvas)
     :sidebar-open? (boolean (:seon.user/sidebar-open? u))
     :expanded-agents (cond-> (set (:seon.user/expanded-agents u))
                        entry-id (conj entry-id))
     :selected-branch (:seon.user/selected-branch u)
     :as-of-tx (:seon.user/as-of-tx u)
     :theme (or (:seon.user/theme u) :auto)}))

;; --- Branch, Agent & Datahike History Time-Travel Helpers ---

(defn- branch-db-for [main-db conn branch]
  (if (or (nil? branch) (= branch :main) (nil? conn))
    main-db
    (or (try (store/head conn branch) (catch Throwable _ nil)) main-db)))

(defn- latest-attr-val [main-db branch-db ma ba attr]
  (let [mt (or (:tx (first (d/datoms main-db :eavt (:db/id ma) attr))) 0)
        bt (or (:tx (first (d/datoms branch-db :eavt (:db/id ba) attr))) 0)]
    (if (> bt mt) (get ba attr) (get ma attr))))

(defn- pull-agent-entity [main-db branch-db agent-id]
  (let [ma (when (and main-db agent-id (first (d/datoms main-db :avet :seon.agent/id agent-id)))
             (d/pull main-db '[*] [:seon.agent/id agent-id]))
        ba (when (and branch-db agent-id (not= branch-db main-db)
                      (first (d/datoms branch-db :avet :seon.agent/id agent-id)))
             (d/pull branch-db '[*] [:seon.agent/id agent-id]))
        base (merge {:seon.agent/id agent-id
                     :seon.agent/branch :main
                     :seon.agent/role :worker
                     :seon.agent/pinned []}
                    ma
                    ba)]
    (if (and ma ba (not= branch-db main-db))
      (assoc base
             :seon.agent/pinned (or (latest-attr-val main-db branch-db ma ba :seon.agent/pinned)
                                    (:seon.agent/pinned base))
             :seon.agent/stage (or (latest-attr-val main-db branch-db ma ba :seon.agent/stage)
                                   (:seon.agent/stage base))
             :seon.agent/canvas (or (latest-attr-val main-db branch-db ma ba :seon.agent/canvas)
                                    (:seon.agent/canvas base))
             :seon.agent/render-fn (latest-attr-val main-db branch-db ma ba :seon.agent/render-fn)
             :seon.agent/memory (or (latest-attr-val main-db branch-db ma ba :seon.agent/memory)
                                    (:seon.agent/memory base))
             :seon.agent/context-prefs (or (latest-attr-val main-db branch-db ma ba :seon.agent/context-prefs)
                                           (:seon.agent/context-prefs base)))
      base)))

(defn- attr-tx-in [db agent-id attr]
  (when (and db agent-id (contains? (:schema db) attr))
    (when-let [eid (:db/id (d/pull db '[:db/id] [:seon.agent/id agent-id]))]
      (reduce max 0 (map :tx (d/datoms db :eavt eid attr))))))

(defn canvas-history-txs
  "Returns sorted distinct Datahike transaction IDs on `bdb` where `agent-id`'s Canvas changed
   (`:seon.agent/canvas`, `:seon.agent/stage`, `:seon.agent/render-fn`, custom render-fn code edits,
   or non-tool-calling prose replies). Folds intermediate `canvas!` txs into their same-exchange
   settled reply tx so every `◀ / ▶` step is visually distinct."
  [bdb agent-id]
  (when (and bdb agent-id)
    (try
      (let [hdb (d/history bdb)
            eid (:db/id (d/pull bdb '[:db/id] [:seon.agent/id agent-id]))
            attr-txs (when eid
                       (for [a [:seon.agent/canvas :seon.agent/stage :seon.agent/render-fn]
                             :when (contains? (:schema bdb) a)
                             dt (d/datoms hdb :eavt eid a)
                             :when (:added dt)]
                         (:tx dt)))
            rfn-syms (when (and eid (contains? (:schema bdb) :seon.agent/render-fn))
                       (distinct (for [dt (d/datoms hdb :eavt eid :seon.agent/render-fn)
                                       :when (and (:added dt)
                                                  (qualified-symbol? (:v dt))
                                                  (not (contains? default-canvas-syms (:v dt))))]
                                   (str (:v dt)))))
            code-txs (for [fkey rfn-syms
                           :let [feid (:db/id (d/pull bdb '[:db/id] [:seon.code.form/key fkey]))]
                           :when feid
                           dt (d/datoms hdb :eavt feid :seon.code.form/src)
                           :when (:added dt)]
                       (:tx dt))
            eval-rows (->> (store/agent-historical-evals bdb agent-id)
                           (sort-by :seon.agent.eval/tx)
                           vec)
            reply-txs (keep (fn [e]
                              (when (and (str/blank? (str (:seon.agent.eval/code e)))
                                         (not (str/blank? (str (:seon.agent.eval/thought e)))))
                                (:seon.agent.eval/tx e)))
                            eval-rows)
            kept-attr-txs (remove (fn [atx]
                                    (let [after (drop-while #(<= (or (:seon.agent.eval/tx %) 0) atx) eval-rows)
                                          same-ex (when (seq after)
                                                    (cons (first after)
                                                          (take-while #(empty? (:seon.agent.eval/seen-messages %))
                                                                      (rest after))))]
                                      (some #(and (str/blank? (str (:seon.agent.eval/code %)))
                                                  (not (str/blank? (str (:seon.agent.eval/thought %)))))
                                            same-ex)))
                                  attr-txs)]
        (->> (concat kept-attr-txs code-txs reply-txs)
             (filter some?)
             distinct
             sort
             vec))
      (catch Throwable _ []))))

(defn- eval-historical-render-fn
  "When stepping back to `as-of-tx`, if `rfn-sym`'s `:seon.code.form/src` at `as-of-tx` differs
   from the current head, evaluates that historical `(defn ...)` as a pure anonymous `(fn ...)`
   without mutating any live Var."
  [head-db as-of-db rfn-sym]
  (when (and head-db as-of-db (qualified-symbol? rfn-sym))
    (try
      (let [fkey (str rfn-sym)
            head-h (:seon.code.form/hash (d/pull head-db '[:seon.code.form/hash] [:seon.code.form/key fkey]))
            hist-f (d/pull as-of-db '[:seon.code.form/hash :seon.code.form/src :seon.code.form/ns] [:seon.code.form/key fkey])]
        (when (and (:seon.code.form/src hist-f) (not= head-h (:seon.code.form/hash hist-f)))
          (let [form (read-string (:seon.code.form/src hist-f))]
            (when (and (seq? form) (= 'defn (first form)))
              (let [ns-obj (or (find-ns (:seon.code.form/ns hist-f)) *ns*)
                    tail (drop 2 form)
                    tail (if (string? (first tail)) (rest tail) tail)
                    tail (if (map? (first tail)) (rest tail) tail)
                    fn-form (cons 'fn tail)]
                (binding [*ns* ns-obj]
                  (eval fn-form)))))))
      (catch Throwable _ nil))))

(defn- agent-evals-in [db agent-id]
  (->> (d/datoms db :avet :seon.agent.eval/agent agent-id)
       (map (fn [dt]
              (assoc (d/pull db '[:seon.agent.eval/id :seon.agent.eval/turn :seon.agent.eval/call-index
                                  :seon.agent.eval/call-id :seon.agent.eval/handle :seon.agent.eval/result-schema
                                  :seon.agent.eval/thought :seon.agent.eval/reasoning :seon.agent.eval/intent
                                  :seon.agent.eval/code :seon.agent.eval/value :seon.agent.eval/out
                                  :seon.agent.eval/error :seon.agent.eval/ms :seon.agent.eval/ticks
                                  :seon.agent.eval/prompt-tokens :seon.agent.eval/completion-tokens
                                  :seon.agent.eval/cache-hit-tokens :seon.agent.eval/cache-miss-tokens
                                  :seon.agent.eval/seen-messages] (:e dt))
                     :seon.agent.eval/tx (:tx dt))))
       (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0)))
       vec))

(defn- agent-messages-in [main-db agent-id]
  (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
       (map (fn [dt]
              (assoc (d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                                       :seon.agent.message/from :seon.agent.message/to
                                       :seon.agent.message/type :seon.agent.message/body] (:e dt))
                     :seon.agent.message/tx (:tx dt))))
       (sort-by :seon.agent.message/seq)
       vec))

(defn- evals->turns
  ([evals] (evals->turns evals nil))
  ([evals msgs-by-id]
   (->> (group-by :seon.agent.eval/turn evals)
        (sort-by key)
        (mapv (fn [[turn es]]
                (let [ordered (sort-by #(or (:seon.agent.eval/call-index %) 0) es)
                      first-e (first ordered)
                      seen-ids (into #{} (mapcat :seon.agent.eval/seen-messages) ordered)
                      turn-msgs (when (and msgs-by-id (seq seen-ids))
                                  (->> seen-ids
                                       (keep #(get msgs-by-id %))
                                       (sort-by :seon.agent.message/seq)
                                       vec))]
                  (cond-> {:turn turn
                           :thought (:seon.agent.eval/thought first-e)
                           :reasoning (:seon.agent.eval/reasoning first-e)
                           :calls (mapv (fn [e]
                                          {:id (or (:seon.agent.eval/call-id e) (str "t" turn "-c" (or (:seon.agent.eval/call-index e) 0)))
                                           :handle (:seon.agent.eval/handle e)
                                           :result-schema (:seon.agent.eval/result-schema e)
                                           :intent (:seon.agent.eval/intent e)
                                           :code (:seon.agent.eval/code e)
                                           :value (:seon.agent.eval/value e)
                                           :out (:seon.agent.eval/out e)
                                           :error (:seon.agent.eval/error e)
                                           :ms (:seon.agent.eval/ms e)
                                           :ticks (:seon.agent.eval/ticks e)})
                                        ordered)}
                    (seq turn-msgs) (assoc :messages turn-msgs))))))))

(defn- first-doc-line [^String s max-len]
  (when-not (str/blank? (str s))
    (let [line (-> (str s) str/trim str/split-lines first str/trim)]
      (if (> (count line) max-len)
        (str (subs line 0 max-len) "…")
        line))))

(defn- ns-working-schemas
  "Derives the Malli schemas that namespace `nsym` (and its immediate child namespaces) owns or
   reads/writes in O(|vars in neighborhood|), via `:seon.code.var/keywords` + `:seon.code.var/schema-refs`."
  ([db nsym] (ns-working-schemas db nsym 14))
  ([db nsym max-n]
   (when (and db nsym (contains? (:schema db) :seon.schema/key))
     (let [has-kw? (contains? (:schema db) :seon.code.var/keywords)
           freq (volatile! {})
           bump! (fn [k w] (when (qualified-keyword? k) (vswap! freq update k (fnil + 0) w)))
           nid (:db/id (d/pull db '[:db/id] [:seon.code.ns/name nsym]))
           child-nss (when (and nid (not= nsym 'seon))
                       (mapv #(:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e %)))
                             (d/datoms db :avet :seon.code.ns/parent nid)))
           scan-nss (cons nsym (remove nil? child-nss))
           _ (doseq [sn scan-nss
                     dt (d/datoms db :avet :seon.code.var/ns sn)
                     :let [v (d/pull db (if has-kw?
                                          '[:seon.code.var/keywords :seon.code.var/schema-refs]
                                          '[:seon.code.var/schema-refs])
                                     (:e dt))]]
               (doseq [k (:seon.code.var/schema-refs v)] (bump! k 3))
               (doseq [k (:seon.code.var/keywords v)] (bump! k 1)))
           ;; Also include schemas whose namespace prefix starts with this ns (e.g. `seon.test` -> `:seon.test/*`)
           ns-str (name nsym)
           owned-prefix (str ns-str ".")
           _ (doseq [dt (d/datoms db :aevt :seon.schema/key)
                     :let [k (:v dt)
                           kns (namespace k)]
                     :when (and kns (or (= kns ns-str) (str/starts-with? kns owned-prefix)))]
               (bump! k 2))
           matched (->> @freq
                        (keep (fn [[k w]]
                                (when-let [sdt (first (d/datoms db :avet :seon.schema/key k))]
                                  (let [ent (d/pull db '[:seon.schema/key :seon.schema/form :seon.schema/doc] (:e sdt))
                                        dh (get (:schema db) k)]
                                    {:key k
                                     :weight w
                                     :form (:seon.schema/form ent)
                                     :doc (:seon.schema/doc ent)
                                     :dh-type (when dh (:db/valueType dh))
                                     :dh-card (when dh (:db/cardinality dh))}))))
                        (sort-by (fn [x] [(- (:weight x)) (str (:key x))]))
                        (take max-n)
                        vec)]
       matched))))

(defn- ns-schema-prefixes
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

(defn- compact-entity-preview [m max-len]
  (let [cleaned (into {}
                      (keep (fn [[k v]]
                              (when (and (not= k :db/id) (not (nil? v)))
                                (let [vs (pr-str v)]
                                  [k (if (> (count vs) 60) (str (subs vs 0 60) "…") v)]))))
                      (take 6 (sort-by (comp str key) m)))
        s (pr-str cleaned)]
    (if (> (count s) max-len) (str (subs s 0 max-len) "…") s)))

(defn- neighborhood-recent-activity
  "Builds a concise ephemeral tail summary of LIVE activity relevant to this agent's neighborhood:
   1. Active incoming & outgoing `:seon.agent.call/*` entries (always fresh, zero KV-cache churn).
   2. Recent live Datahike records matching this agent's working schemas (capped cleanly).
   All schema reads use capped, indexed `:avet` lookups — no nested full-DB scans, no schema-form pulls."
  [main-db branch-db ag]
  (let [aid (:seon.agent/id ag)
        scope (:seon.agent/scope ag)
        prefs (or (:seon.agent/context-prefs ag) {})
        rec-limit (long (or (:recent-data-limit prefs) 4))
        ;; Lightweight (keys-only) derivation of the schema keys `sc` (and its immediate child
        ;; namespaces) owns or reads/writes. Indexed `:avet` lookups, pulling only
        ;; `:seon.code.var/keywords` + `:seon.code.var/schema-refs` — never the heavy schema forms.
        scope-schema-keys
        (fn [db sc]
          (when (and db sc (not= sc 'seon) (contains? (:schema db) :seon.schema/key))
            (let [has-kw? (contains? (:schema db) :seon.code.var/keywords)
                  freq (volatile! {})
                  bump! (fn [k w] (when (qualified-keyword? k) (vswap! freq update k (fnil + 0) w)))
                  nid (when (contains? (:schema db) :seon.code.ns/name)
                        (:db/id (d/pull db '[:db/id] [:seon.code.ns/name sc])))
                  child-nss (when (and nid (contains? (:schema db) :seon.code.ns/parent))
                              (mapv #(:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e %)))
                                    (d/datoms db :avet :seon.code.ns/parent nid)))
                  scan-nss (cons sc (remove nil? child-nss))
                  proj (if has-kw? '[:seon.code.var/keywords :seon.code.var/schema-refs]
                           '[:seon.code.var/schema-refs])
                  ns-str (name sc)
                  owned-prefix (str ns-str ".")]
              (doseq [sn scan-nss
                      dt (d/datoms db :avet :seon.code.var/ns sn)
                      :let [v (d/pull db proj (:e dt))]]
                (doseq [k (:seon.code.var/schema-refs v)] (bump! k 3))
                (doseq [k (:seon.code.var/keywords v)] (bump! k 1)))
              (doseq [dt (d/datoms db :avet :seon.schema/key)
                      :let [k (:v dt)
                            kns (namespace k)]
                      :when (and kns (or (= kns ns-str) (str/starts-with? kns owned-prefix)))]
                (bump! k 2))
              (->> @freq
                   (sort-by (fn [[k w]] [(- w) (str k)]))
                   (take 80)
                   (mapv key)))))
        lines (volatile! [])
        add! (fn [s] (when-not (str/blank? s) (vswap! lines conj s)))]
    ;; 1. Live calls involving this agent
    (when (and aid (contains? (:schema main-db) :seon.agent.call/callee))
      (let [incoming (->> (d/datoms main-db :avet :seon.agent.call/callee aid)
                          (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                  :seon.agent.call/status :seon.agent.call/prompt
                                                  :seon.agent.call/result-schema] (:e %)))
                          (filter #(= :pending (:seon.agent.call/status %)))
                          vec)
            outgoing (->> (d/datoms main-db :avet :seon.agent.call/caller aid)
                          (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/callee
                                                  :seon.agent.call/status :seon.agent.call/created-tx] (:e %)))
                          (sort-by #(or (:seon.agent.call/created-tx %) 0))
                          (take-last 5)
                          vec)]
        (when (seq incoming)
          (add! "Open incoming calls to answer via (user/answer! call-id val):")
          (doseq [c incoming]
            (add! (str "  - " (pr-str (:seon.agent.call/id c)) " from " (:seon.agent.call/caller c)
                       (when (:seon.agent.call/result-schema c) (str " schema=" (:seon.agent.call/result-schema c)))
                       ": " (first-doc-line (:seon.agent.call/prompt c) 100)))))
        (when (seq outgoing)
          (add! "Recent outgoing calls:")
          (doseq [c outgoing]
            (add! (str "  - " (pr-str (:seon.agent.call/id c)) " -> " (:seon.agent.call/callee c)
                       " [" (name (:seon.agent.call/status c :pending)) "]"))))))
    ;; 2. Recent live data matching this agent's working schemas (diversified across entity
    ;;    types, skipping noisy eval logs by default). Each attribute is read via its indexed
    ;;    `:avet` lookup, capped at 250 datoms, keeping only the 2 newest.
    (when (and scope (pos? rec-limit))
      (let [is-meta-scope? (or (= scope 'seon.code) (= scope 'seon.schema))
            extra-set (set (:extra-schemas prefs))
            root-defaults (when (= scope 'seon)
                            [:seon.agent.call/id :seon.task/id :seon.test.run/branch
                             :seon.health.fault/id :seon.user/id :seon.agent/id])
            working-db (if (and branch-db (contains? (:schema branch-db) :seon.code.var/keywords)
                                (first (d/datoms branch-db :avet :seon.code.var/keywords)))
                         branch-db
                         main-db)
            working (concat (map (fn [k] {:key k}) (:extra-schemas prefs))
                            (map (fn [k] {:key k}) root-defaults)
                            (map (fn [k] {:key k}) (scope-schema-keys working-db scope)))
            candidate-attrs (->> working
                                 (map :key)
                                 distinct
                                 (filter #(and (contains? (:schema main-db) %)
                                               (first (take 1 (d/datoms main-db :avet %)))))
                                 (remove (fn [k]
                                           (and (not (contains? extra-set k))
                                                (let [ns-s (namespace k)]
                                                  (or (= ns-s "seon.agent.eval")
                                                      (= ns-s "seon.config")
                                                      (and (not is-meta-scope?)
                                                           (or (str/starts-with? ns-s "seon.code")
                                                               (= ns-s "seon.schema"))))))))
                                 ;; Diversify: at most 1 attribute per schema namespace prefix
                                 (reduce (fn [[seen-ns acc] k]
                                           (let [kns (namespace k)]
                                             (if (or (>= (count acc) 6) (contains? seen-ns kns))
                                               [seen-ns acc]
                                               [(conj seen-ns kns) (conj acc k)])))
                                         [#{} []])
                                 second)
            ;; One capped indexed lookup per candidate attribute; keep the 2 newest datoms each.
            newest-2 (fn [a]
                       (reduce (fn [acc dt]
                                 (if (< (count acc) 2)
                                   (conj acc dt)
                                   (vec (rest (sort-by :tx (conj acc dt))))))
                               []
                               (take 250 (d/datoms main-db :avet a))))
            samples (->> candidate-attrs
                         (mapcat (fn [a]
                                   (map (fn [dt] {:attr a :tx (:tx dt) :eid (:e dt)})
                                        (newest-2 a))))
                         (sort-by (comp - :tx))
                         (reduce (fn [[seen acc] item]
                                   (if (or (>= (count acc) rec-limit) (contains? seen (:eid item)))
                                     [seen acc]
                                     [(conj seen (:eid item)) (conj acc item)]))
                                 [#{} []])
                         second)]
        (when (seq samples)
          (add! (str "Recent live data matching " scope " schemas:"))
          (doseq [{:keys [attr tx eid]} samples]
            (let [ent (d/pull main-db '[*] eid)]
              (add! (str "  - tx " tx " (" attr "): " (compact-entity-preview ent 150))))))))
    ;; 3. Live runtime, test, perf-outlier & eval-efficiency health alerts routed to this agent's scope!
    (when scope
      (let [live-probs (health/live-problems-for-scope main-db branch-db scope aid)]
        (when (seq live-probs)
          (add! (if (= scope 'seon)
                  "Live system health & efficiency alerts (delegate to owning :ns/<subsys> specialist):"
                  (str "Live health & efficiency alerts for " scope ":")))
          (doseq [p (take 6 live-probs)
                  :let [owner (health/owner-specialist-id (:ns p))]]
            (add! (str "  - [" (name (:severity p :warning)) " :" (name (:class p)) "] "
                       (:subject p)
                       (when (and (= scope 'seon) owner) (str " (owner " owner ")"))
                       " — " (:message p)))))))
    (when (seq @lines)
      (str/join "\n" @lines))))

(defn build-live-wire-request
  "Single source of truth for an agent's LLM context AND Debug View:
   Walks `branch-db`, projects discovered tiles via `render/render-value` -> `render/hiccup->text`,
   frames `:seon.agent/examples` in `<session kind=\"example\">...</session>`, and attaches ephemeral
   tail `<neighborhood-activity>` + `<live-viewport>` without polluting the static KV-cache prefix."
  [main-db branch-db agent-id {:keys [viewport-screenshot vision?]}]
  (let [ag (pull-agent-entity main-db branch-db agent-id)
        evals (agent-evals-in branch-db agent-id)
        msgs (agent-messages-in main-db agent-id)
        seen-ids (store/agent-seen-message-ids branch-db agent-id)
        unread (filterv #(not (contains? seen-ids (:seon.agent.message/id %))) msgs)
        tiles (render/discover-renderable-tiles main-db branch-db ag evals health/report)
        tile-projections
        (mapv (fn [{:keys [val] :as tile}]
                (let [h (render/render-value branch-db val {:mode :agent :compact? true :main-db main-db :agent ag})
                      txt (render/hiccup->text h)]
                  (assoc tile :text txt)))
              tiles)
        walk-text (str/join "\n\n---\n\n"
                            (map (fn [{:keys [schema source handle text]}]
                                   (str "### [" (name source)
                                        (when handle (str " " handle))
                                        " · " schema "]\n" text))
                                 tile-projections))
        activity-text (neighborhood-recent-activity main-db branch-db ag)
        examples (vec
                  (for [ex-id (sort (:seon.agent/examples ag))
                        :when (first (d/datoms main-db :avet :seon.agent/id ex-id))
                        :let [ex-ag (d/pull main-db '[:seon.agent/id :seon.agent/goal] [:seon.agent/id ex-id])
                              ex-evals (agent-evals-in main-db ex-id)]]
                    {:id ex-id
                     :goal (:seon.agent/goal ex-ag)
                     :turns (evals->turns ex-evals)}))
        wire (model/build-agent-wire-messages
              {:thinking? true :replay-reasoning? true :vision? (boolean vision?)}
              {:agent-id agent-id
               :branch (:seon.agent/branch ag :main)
               :role (:seon.agent/role ag :worker)
               :goal (:seon.agent/goal ag)
               :system-prompt (:seon.agent/system-prompt ag)
               :examples examples
               :walk-text walk-text
               :activity-text activity-text
               :history (evals->turns evals (into {} (map (juxt :seon.agent.message/id identity)) msgs))
               :unread unread
               :viewport-screenshot viewport-screenshot})]
    (assoc wire
           :agent ag
           :tiles tile-projections
           :activity-text activity-text
           :evals evals
           :messages-inbox msgs
           :unread unread)))

;; --- Schema-Attached Entity Tile Renderers (`:seon.render/hiccup`) ---

(defn ctx-tile
  "Schema-attached render for `:seon.ctx/snapshot` — shows the agent's live `@seon.ctx/ctx` state
   in `:mode :human`, and in `:mode :agent` keeps the `<walk-context>` prefix 100% static/byte-identical
   across turns (for 90%+ LLM KV-cache hit rates — dynamic `tx` and live canvas pixels arrive in the
   ephemeral tail `<live-viewport>` instead!)."
  [{:keys [mode]} snap]
  (let [aid (:seon.agent/id snap)
        branch (:seon.agent/branch snap :main)
        rfn (:seon.agent/render-fn snap 'seon.render.ui/root-canvas)
        canvas (:seon.agent/canvas snap)
        pinned (vec (or (:seon.agent/pinned snap) []))
        memory (vec (or (:seon.agent/memory snap) []))
        prefs (:seon.agent/context-prefs snap)]
    (if (= :agent mode)
      [:div {:class "card tile-card"}
       [:div {:class "card-head"}
        [:div {:class "pill-row"}
         [:span {:class "badge amber"} "@ctx"]
         [:strong {:class "mono"} (str aid)]]
        [:span {:class "muted tiny mono"} (str branch)]]
       [:dl {:class "kv-grid"}
        [:dt ":seon.agent/role"] [:dd (str (:seon.agent/role snap :worker))]
        (when (seq prefs)
          (list [:dt ":seon.agent/context-prefs"] [:dd (pr-str prefs)]))]
       (when (seq memory)
         [:div {:class "doc-box sm-copy" :style "margin-top:8px"}
          [:p "Durable Datahike memory (persisted across compactions):"]
          [:ul
           (for [m memory] [:li (if (string? m) m (pr-str m))])]])
       [:div {:class "doc-box sm-copy" :style "margin-top:8px"}
        [:p "Live REPL affordances colocated on @seon.ctx/ctx (also bound as user/ctx):"]
        [:ul
         [:li "@ctx is a reactive Datahike CtxAtom — read with @ctx, update with (swap! ctx assoc ...). Never shadow it with (def ctx ...)."]
         [:li "Replace the user's live Canvas cleanly in 1 call (every update automatically becomes a Back/Forward step in Datahike history!): (user/canvas! {:title \"Headline\" :summary \"Concise takeaway\" :body [:svg {:viewBox \"0 0 640 280\"} ...]}) (Phosphor CSS vars: var(--signal), var(--text-100), var(--base-800), var(--success), var(--info))."]
         [:li "Or replace the entire Canvas render function live on your branch: define (ns my.ui) (defn canvas [ctx] [:div ...]) and call (user/set-render! 'my.ui/canvas)."]
         [:li "Query & navigate Datahike directly: (d/datoms (user/db) :aevt :attr), (d/pull (user/db) '[*] ref), (user/source 'ns/fn | 'ns | \"docs/file.md#sec\" | :seon.schema/key), (user/search \"term\"), (user/callers 'ns/fn), (user/callees 'ns/fn), (user/tests-for 'ns)."]
         [:li "When a <live-viewport> screenshot is attached at the end of your context, inspect the actual rendered pixels & DOM telemetry and fix any clipping, overlap, contrast, or stray SVG artifacts before finishing."]
         [:li "Delegate work or questions via Flow-native calls (zero timers/waits): (user/ask! :ns/seon.render \"question\") returns {:call-id ...} asynchronously (lazy-spawning :ns/<ns> if needed), (user/await! [c1 c2]) suspends cleanly until all settle, (user/call! :ns/seon.render \"...\") is ask!+await!. Settle incoming calls with (user/answer! call-id val) or (user/fail! call-id {:class :out-of-scope :message \"...\"})."]
         [:li "Refine context via Datahike & trim stale history cleanly: (user/remember! \"durable fact\"), (user/tune-context! {:extra-namespaces ['seon.web] :extra-schemas [:seon.user/viewing] :recent-data-limit 5}), (user/compact! \"concise summary\") (retracts old evals from current DB; full history remains queryable via Datahike time-travel: (user/past-evals {:grep \"...\"}) / (user/as-of tx) / (user/history 'ns/fn))."]
         [:li "Improve code, schemas, docs & tests directly in Datahike (never spit files on disk, never :reload-all): edit existing namespaces via (in-ns 'target.ns) then (defn ...)/(deftest ...), create a NEW child namespace with (ns target.child (:require ...)) + its (defn ...)s in ONE eval (never bare in-ns + require, since (ns ...) persists the file header!), remove moved vars safely via (user/remove-var! 'ns/var), inspect your branch changes & health errors via (user/diff), update docs via (user/doc! \"docs/file.md#section\" \"body\"), tighten schemas via (user/register-schema! :seon.area/key form {:doc \"...\"}), run one test via (clojure.test/test-vars [#'ns/test-name]), settle calls via (user/answer! ...), and call (user/ready!) to auto-test, 3-way merge, hot-reload, and write back to disk & Git. Move the user's view with (user/show-user! {:viewing " (str aid) " :mode :canvas|:workspace|:debug})."]]]]
      [:div {:class "card tile-card"}
       [:div {:class "card-head"}
        [:div {:class "pill-row"}
         [:span {:class "badge amber"} "@ctx"]
         [:strong {:class "mono"} (str aid)]]
        [:span {:class "muted tiny mono"} (str branch " · tx " (:seon.ctx/tx snap 0))]]
       [:dl {:class "kv-grid"}
        [:dt ":seon.agent/role"] [:dd (str (:seon.agent/role snap :worker))]
        [:dt ":seon.agent/render-fn"] [:dd (str rfn)]
        [:dt ":seon.agent/canvas"] [:dd (if (seq canvas)
                                          (pr-str (select-keys canvas [:title :summary]))
                                          "{} — call (user/canvas! {:title \"...\" :summary \"...\" :body [:svg ...]})")]
        [:dt ":seon.agent/pinned"] [:dd (pr-str pinned)]
        (when (seq memory)
          (list [:dt ":seon.agent/memory"] [:dd (str (count memory) " durable note(s)")]))]])))

(defn health-panel
  "Schema-attached render for `:seon.health/report` (`{:seon.render/hiccup seon.render.ui/health-panel}`)."
  [{:keys [mode]} report]
  (let [ok? (:ok? report)
        probs (:problems report)
        counts (:counts report)
        err-n (count (filter #(= :error (:severity %)) probs))
        warn-n (- (count probs) err-n)
        err-classes (into #{} (comp (filter #(= :error (:severity %))) (map :class)) probs)]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:h2 "System Health"]
       [:span {:class (str "badge " (if ok? "green" "red"))}
        (if ok? "HEALTHY" (str err-n " ERROR(S)"))]
       (when (pos? warn-n)
         [:span {:class "badge amber"} (str warn-n " ADVISORY")])]
      [:button {:type "button" :class "btn primary sm"
                :on-click '(seon.web/run-tests! :main)}
       "▶ Run Reach Tests"]]
     (when (seq counts)
       [:div {:class "pill-row" :style "margin-bottom:8px"}
        (for [[cls n] (sort-by (comp str key) counts)]
          [:span {:class (str "badge " (if (contains? err-classes cls) "red" "amber"))}
           (str (name cls) ": " n)])])
     (if (empty? probs)
       [:p {:class "muted sm-copy"} "All structural checks green, zero failing branch tests, zero open faults or efficiency warnings."]
       [:table {:class "data-table"}
        [:thead [:tr [:th "Class"] [:th "Owner"] [:th "Subject"] [:th "Detail"] [:th ""]]]
        [:tbody
         (for [[idx p] (map-indexed vector probs)
               :let [owner (or (health/owner-specialist-id (:ns p)) (:agent p) (:branch p :main))]]
           [:tr {:id (str "hp-" idx)}
            [:td [:span {:class (str "badge " (if (= :error (:severity p)) "red" "amber"))} (name (:class p))]]
            [:td {:class "mono tiny"} (str owner)]
            [:td {:class "mono tiny"} (:subject p)]
            [:td {:class "tiny"} (:message p)]
            [:td [:button {:type "button" :class "btn sm"
                           :on-click (list 'seon.web/assign-health-fix! (:class p) (:subject p) (:message p))}
                  "Fix"]]])]])
     (when (= :agent mode)
       [:p {:class "tiny muted"} "REPL: (user/health) recomputes live health; delegate advisories via (user/ask! :ns/<owner> \"...\")."])]))

(defn ns-tile
  "Level-zoomed schema-attached render for `:seon.code.ns/entity`:
   - Internal/router nodes (e.g. `seon` at root): renders a compact Subsystem Routing Table of
     child namespaces (1-line doc + var count + schema prefixes + existing specialist agent) so
     root can route in 1 turn without loading 400 leaf vars!
   - Subsystem/leaf nodes (e.g. `seon.render`, `seon.render.ui`): renders Public API signatures +
     1-line docs, sub-namespace summaries, boundary callers/callees, and working Malli schemas."
  [{:keys [db main-db mode agent]} ns-ent]
  (let [nsym (:seon.code.ns/name ns-ent)
        eff-db (if (= :coordinator (:seon.agent/role agent)) (or main-db db) db)
        prefs (or (:seon.agent/context-prefs agent) {})
        max-vars (long (or (:max-vars-shown prefs) 24))
        max-schemas (long (or (:max-schemas-shown prefs) 14))
        vars (when (and eff-db nsym)
               (->> (d/datoms eff-db :avet :seon.code.var/ns nsym)
                    (map #(d/pull eff-db '[:seon.code.var/name :seon.code.var/arglists :seon.code.var/doc
                                           :seon.code.var/schema :seon.code.var/private? :seon.code.var/test?] (:e %)))
                    (sort-by #(str (:seon.code.var/name %)))
                    vec))
        pub-vars (filterv #(and (not (:seon.code.var/private? %)) (not (:seon.code.var/test? %))) vars)
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
        router-view? (and (seq children) (or (empty? vars) (= :coordinator (:seon.agent/role agent))))
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
        static-alerts (when (and schema-db nsym (not= nsym 'seon))
                        (health/static-problems-for-scope schema-db nsym))
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
       [:p {:class "doc-box sm-copy"} (first-doc-line doc 180)])
     (when (seq children)
       (if router-view?
         (let [prod-children (remove #(str/ends-with? (name (:seon.code.ns/name %)) "-test") children)
               test-children (filter #(str/ends-with? (name (:seon.code.ns/name %)) "-test") children)
               known-schemas (if (and schema-db (contains? (:schema schema-db) :seon.schema/key))
                               (into #{} (map :v) (d/datoms schema-db :aevt :seon.schema/key))
                               #{})]
           [:div {:class "stack-sm" :style "margin-top:8px"}
            [:div {:class "table-scroll"}
             [:table {:class "data-table"}
              [:thead [:tr [:th "Subsystem"] [:th "Vars"] [:th "Schemas"] [:th "Specialist"] [:th "Role"]]]
              [:tbody
               (for [c prod-children
                     :let [cn (:seon.code.ns/name c)
                           vc (count (d/datoms schema-db :avet :seon.code.var/ns cn))
                           sprefs (ns-schema-prefixes schema-db known-schemas cn 3)
                           spec (get agent-by-scope cn)]]
                 [:tr
                  [:td {:class "mono tiny"} (str cn)]
                  [:td {:class "mono tiny"} (str vc)]
                  [:td {:class "mono tiny muted"} (if (seq sprefs) (str/join ", " sprefs) "—")]
                  [:td {:class "mono tiny"} (if spec (str spec) (str ":ns/" cn " (lazy)"))]
                  [:td {:class "tiny"} (or (first-doc-line (:seon.code.ns/doc c) 95) "—")]])]]]
            (when (seq test-children)
              [:p {:class "muted tiny"}
               (str "Test namespaces: "
                    (str/join ", " (map (fn [tc]
                                          (let [cn (:seon.code.ns/name tc)]
                                            (str cn " (" (count (d/datoms db :avet :seon.code.var/ns cn)) ")")))
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
               :let [sc-str (when-let [sc (:seon.code.var/schema v)]
                              (let [s (pr-str sc)]
                                (if (> (count s) 72) (str (subs s 0 72) "…") s)))]]
           [:li {:class "tiny"}
            [:code {:class "mono"}
             (str (name (:seon.code.var/name v))
                  (when (seq (:seon.code.var/arglists v))
                    (str " " (pr-str (:seon.code.var/arglists v)))))]
            (when sc-str (str " " sc-str))
            (when-let [d (first-doc-line (:seon.code.var/doc v) 95)]
              (str " — " d))])]])
     (when (and (not router-view?) (or (seq own-test-vars) (seq reaching-tests) (seq pub-vars)))
       (let [tlist (if (seq own-test-vars) (mapv :seon.code.var/name own-test-vars) reaching-tests)]
         [:div {:class "pill-row" :style "margin-top:6px"}
          [:span {:class "muted tiny"}
           (if (seq own-test-vars)
             (str "Tests (" (count tlist) "):")
             (str "Covering tests (" (count tlist) "):"))]
          (if (seq tlist)
            (for [tsym (take 10 tlist)]
              [:span {:class "pill mono green-text"} (str tsym)])
            [:span {:class "muted tiny"} "none — add a reaching deftest!"])]))
     (when (and (not router-view?) (seq linked-docs))
       [:div {:class "stack-sm" :style "margin-top:6px"}
        [:span {:class "kicker"} (str "Linked Docs (" (count linked-docs) ")")]
        [:ul {:style "margin:4px 0 0 16px;padding:0"}
         (for [{:keys [id heading]} linked-docs]
           [:li {:class "tiny"}
            [:code {:class "mono"} id]
            (when-not (str/blank? heading) (str " — " heading))])]])
     (when (and (not router-view?) (or (seq reqs) (seq caller-nss)))
       [:div {:class "pill-row" :style "margin-top:6px"}
        (when (seq reqs)
          [:span {:class "muted tiny"} (str "Calls: " (str/join ", " (map str reqs)))])
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
     (when (and (not= nsym 'seon) (seq static-alerts))
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

(defn var-tile
  "Schema-attached render for `:seon.code.var/entity`."
  [{:keys [db]} v]
  (let [vsym (:seon.code.var/name v)
        src (when (and db (:seon.code.var/form v))
              (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (:seon.code.var/form v)])))
        callers (when (and db vsym)
                  (->> (d/datoms db :avet :seon.code.var/refs vsym)
                       (map #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                       sort vec))]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class (str "badge " (if (:seon.code.var/test? v) "green" "blue"))}
        (if (:seon.code.var/test? v) "test" "var")]
       [:strong {:class "mono"} (str vsym)]]
      (when (seq (:seon.code.var/arglists v))
        [:span {:class "muted tiny mono"} (pr-str (:seon.code.var/arglists v))])]
     (when-let [doc (:seon.code.var/doc v)]
       [:p {:class "doc-box sm-copy"} doc])
     (when-let [sc (:seon.code.var/schema v)]
       [:pre {:class "code-box tiny"} [:code (pr-str sc)]])
     (when (seq callers)
       [:div {:class "pill-row" :style "margin-top:6px"}
        [:span {:class "muted tiny"} (str "Callers (" (count callers) "):")]
        (for [c (take 8 callers)] [:span {:class "pill mono"} (str c)])])
     (when src
       [:details {:class "fold" :style "margin-top:8px"}
        [:summary {:class "muted tiny"} "Source form"]
        [:pre {:class "code-box tiny"} [:code src]]])]))

(defn doc-tile
  "Schema-attached render for `:seon.code.doc/entity`."
  [_opts doc-ent]
  [:div {:class "card tile-card"}
   [:div {:class "card-head"}
    [:div {:class "pill-row"}
     [:span {:class "badge amber"} "doc"]
     [:strong (or (:seon.code.doc/heading doc-ent) (:seon.code.doc/id doc-ent))]]
    [:span {:class "muted tiny mono"} (or (:seon.code.doc/path doc-ent) "")]]
   [:div {:class "doc-box sm-copy"}
    (let [b (str (:seon.code.doc/body doc-ent ""))]
      (if (> (count b) 700)
        [:div
         [:p (subs b 0 700) "…"]
         [:details {:class "fold"}
          [:summary {:class "muted tiny"} (str "Show full section (" (count b) " chars)")]
          [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} b]]]
        [:div {:style "white-space:pre-wrap"} b]))]])

(defn schema-tile
  "Schema-attached render for `:seon.schema/entity`."
  [{:keys [db]} sc-ent]
  (let [sk (:seon.schema/key sc-ent)
        dh (when db (get (:schema db) sk))]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge blue"} "schema"]
       [:strong {:class "mono"} (str sk)]]
      (when dh
        [:span {:class "badge green mono"} (str (name (:db/valueType dh)) " · " (name (:db/cardinality dh)))])]
     (when-let [doc (:seon.schema/doc sc-ent)]
       [:p {:class "muted sm-copy"} doc])
     [:pre {:class "code-box tiny"} [:code (pr-str (:seon.schema/form sc-ent))]]]))

(defn call-tile
  "Schema-attached render for `:seon.agent.call/entity`."
  [_opts c]
  (let [st (:seon.agent.call/status c :pending)
        badge-cls (case st :resolved "green" :failed "red" :cancelled "muted" "amber")]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class (str "badge " badge-cls)} (name st)]
       [:strong {:class "mono"} (str (:seon.agent.call/caller c) " → " (:seon.agent.call/callee c))]]
      [:span {:class "muted tiny mono"} (str (:seon.agent.call/id c))]]
     (when-let [p (:seon.agent.call/prompt c)]
       [:p {:class "sm-copy"} p])
     (when (contains? c :seon.agent.call/result)
       [:pre {:class "code-box tiny green-text"} [:code (pr-str (:seon.agent.call/result c))]])
     (when (contains? c :seon.agent.call/error)
       [:div {:class "notice red mono tiny"} (pr-str (:seon.agent.call/error c))])]))

(defn task-tile
  "Schema-attached render for `:seon.task/entity`."
  [_opts t]
  [:div {:class "card tile-card"}
   [:div {:class "card-head"}
    [:div {:class "pill-row"}
     [:span {:class (str "badge " (if (:seon.task/done? t) "green" "blue"))}
      (if (:seon.task/done? t) "done" "task")]
     [:strong (:seon.task/title t)]]
    [:span {:class "muted tiny mono"} (str (:seon.task/id t))]]
   (when-let [p (:seon.task/plan t)]
     [:p {:class "sm-copy"} p])
   (when (seq (:seon.task/tests t))
     [:div {:class "pill-row"}
      (for [ts (:seon.task/tests t)] [:span {:class "pill mono"} (str ts)])])])

(defn agent-tile
  "Schema-attached render for `:seon.agent/entity` — updates `:seon.user/viewing` in Datahike on click!"
  [{:keys [mode]} ag]
  (let [aid (:seon.agent/id ag)]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge blue"} (name (or (:seon.agent/role ag) :worker))]
       [:strong {:class "mono"} (str aid)]]
      [:div {:class "pill-row"}
       [:button {:type "button" :class "btn sm primary"
                 :on-click (list 'seon.web/view-agent! aid :canvas)} "Canvas"]
       [:button {:type "button" :class "btn sm"
                 :on-click (list 'seon.web/view-agent! aid :workspace)} "Workspace"]
       [:button {:type "button" :class "btn sm"
                 :on-click (list 'seon.web/view-agent! aid :debug)} "Debug"]]]
     [:p {:class "muted tiny mono" :style "margin:2px 0 6px"}
      (str "branch " (:seon.agent/branch ag :main)
           (when (:seon.agent/scope ag) (str " · scope " (:seon.agent/scope ag))))]
     (when (not (str/blank? (str (:seon.agent/goal ag))))
       [:p {:class "sm-copy"} (:seon.agent/goal ag)])
     (when (= :agent mode)
       [:p {:class "tiny muted"} (str "REPL: (user/send-message! " aid " :chat \"...\") or (user/show-user! {:viewing " aid " :mode :canvas}).")])]))

;; --- Crisp Minimal SVG Icons & Human-Friendly Agent Labels ---

(defn- friendly-agent-name [aid]
  (cond
    (nil? aid) "Agent"
    (= "agent" (namespace aid)) (name aid)
    (namespace aid) (str (namespace aid) "/" (name aid))
    :else (name aid)))

(def ^:private svg-sidebar
  [:svg {:width "15" :height "15" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"}
   [:rect {:x "1.75" :y "2.5" :width "12.5" :height "11" :rx "2"}]
   [:line {:x1 "5.75" :y1 "2.5" :x2 "5.75" :y2 "13.5"}]])

(def ^:private svg-sun
  [:svg {:width "15" :height "15" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :stroke-linecap "round"}
   [:circle {:cx "8" :cy "8" :r "3"}]
   [:line {:x1 "8" :y1 "1.5" :x2 "8" :y2 "3"}]
   [:line {:x1 "8" :y1 "13" :x2 "8" :y2 "14.5"}]
   [:line {:x1 "1.5" :y1 "8" :x2 "3" :y2 "8"}]
   [:line {:x1 "13" :y1 "8" :x2 "14.5" :y2 "8"}]
   [:line {:x1 "3.4" :y1 "3.4" :x2 "4.5" :y2 "4.5"}]
   [:line {:x1 "11.5" :y1 "11.5" :x2 "12.6" :y2 "12.6"}]
   [:line {:x1 "12.6" :y1 "3.4" :x2 "11.5" :y2 "4.5"}]
   [:line {:x1 "4.5" :y1 "11.5" :x2 "3.4" :y2 "12.6"}]])

(def ^:private svg-moon
  [:svg {:width "14" :height "14" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M13.5 9.8A5.5 5.5 0 0 1 6.2 2.5a5.5 5.5 0 1 0 7.3 7.3Z"}]])

(def ^:private svg-chevron-left
  [:svg {:width "14" :height "14" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.75" :stroke-linecap "round" :stroke-linejoin "round"}
   [:polyline {:points "10 3.5 5.5 8 10 12.5"}]])

(def ^:private svg-chevron-right
  [:svg {:width "14" :height "14" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.75" :stroke-linecap "round" :stroke-linejoin "round"}
   [:polyline {:points "6 3.5 10.5 8 6 12.5"}]])

(def ^:private svg-arrow-up
  [:svg {:width "16" :height "16" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}
   [:line {:x1 "8" :y1 "13" :x2 "8" :y2 "3.5"}]
   [:polyline {:points "3.75 7.5 8 3.25 12.25 7.5"}]])

;; --- Quiet Phosphor Top Bar (`id="seon-header"` — Always 1 Calm Row, Even at Half-Screen!) ---

(defn header-unit [db {:keys [boot-seq agent-states]}]
  (let [{:keys [viewing-id view-mode sidebar-open? theme]} (user-view-state db)
        h (health/report db)
        ok? (:ok? h)
        err-n (count (filter #(= :error (:severity %)) (:problems h)))
        warn-n (- (count (:problems h)) err-n)
        active? (= :active (get agent-states viewing-id))
        mode-btn (fn [m label]
                   [:button {:type "button"
                             :class (str "nav-pill" (when (= view-mode m) " active"))
                             :on-click (list 'seon.web/set-view-mode! m)}
                    label])]
    [:header {:id "seon-header" :class "topbar"
              :data-boot-seq (str (or boot-seq 1))
              :data-theme (name (or theme :auto))}
     [:script {:id "boot-meta" :type "application/json"}
      (render/raw-html (str "{\"bootSeq\":" (or boot-seq 1) ",\"mainTx\":" (:max-tx db) "}"))]
     [:div {:class "brand-row"}
      [:div {:class "brand"}
       [:button {:type "button"
                 :class (str "icon-btn sidebar-toggle" (when sidebar-open? " active"))
                 :aria-label (if sidebar-open? "Hide sidebar" "Show sidebar")
                 :title (if sidebar-open? "Hide sidebar" "Show sidebar")
                 :on-click '(seon.web/toggle-sidebar!)}
        svg-sidebar]
       [:button {:type "button" :class "brand-link"
                 :on-click '(seon.web/view-agent! :agent/root :canvas)}
        [:span {:class "diamond"} "◆"]
        [:strong {:class "wordmark"} "seon"]]
       [:span {:class "crumb-sep"} "/"]
       [:span {:class "crumb" :title (str viewing-id)} (friendly-agent-name viewing-id)]
       (when active?
         [:span {:class "live-pill"}
          [:span {:class "dot pulsing"}]
          "working"])]
      [:div {:class "stats-strip"}
       [:button {:type "button"
                 :class "icon-btn theme-toggle"
                 :aria-label (if (= theme :light) "Dark theme" "Light theme")
                 :title (if (= theme :light) "Switch to dark theme" "Switch to light theme")
                 :on-click '(seon.web/toggle-theme!)}
        (if (= theme :light) svg-moon svg-sun)]
       [:button {:type "button"
                 :class (str "status-pill " (if ok? "ok" "bad"))
                 :title (if ok?
                          (if (pos? warn-n)
                            (str "System healthy (" warn-n " efficiency/structure advisories) — open Debug")
                            "System healthy — open Debug")
                          (str err-n " error(s) — open Debug"))
                 :on-click '(seon.web/set-view-mode! :debug)}
        [:span {:class (str "dot " (if ok? "live" "bad"))}]
        [:span {:class "status-label"}
         (if ok? "Healthy" (str err-n " issue" (when (not= 1 err-n) "s")))]]
       [:div {:class "mode-group"}
        (mode-btn :canvas "Canvas")
        (mode-btn :workspace "Workspace")
        (mode-btn :debug "Debug")]]]]))

;; --- Collapsible Recursive Left Agent-Tree Sidebar (`id="seon-sidebar"` — Clean, Calm Real App) ---

(defn- render-agent-tree-node [children-by-parent viewing-id expanded-set agent-states depth ag]
  (let [aid (:seon.agent/id ag)
        kids (get children-by-parent aid)
        has-kids? (boolean (seq kids))
        expanded? (contains? expanded-set aid)
        selected? (= aid viewing-id)
        working? (= :active (get agent-states aid))]
    [:div {:class "tree-branch"}
     [:div {:class (str "tree-row" (when selected? " active"))
            :style (str "padding-left:" (+ 8 (* depth 14)) "px")}
      (if has-kids?
        [:button {:type "button" :class "tree-exp"
                  :on-click (list 'seon.web/toggle-expand! aid)}
         (if expanded? "▾" "▸")]
        [:span {:class "tree-exp-spacer"} "·"])
      [:button {:type "button" :class "tree-select"
                :title (str aid)
                :on-click (list 'seon.web/view-agent! aid :canvas)}
       [:span {:class (str "dot " (if working? "pulsing" "live"))}]
       [:span {:class "tree-name"} (friendly-agent-name aid)]
       (when working?
         [:span {:class "badge amber tree-role"} "live"])]]
     (when (and has-kids? expanded?)
       (for [child kids]
         (render-agent-tree-node children-by-parent viewing-id expanded-set agent-states (inc depth) child)))]))

(defn sidebar-unit [db {:keys [agent-states]}]
  (let [{:keys [viewing-id sidebar-open? expanded-agents]} (user-view-state db)
        all-agents (->> (d/datoms db :aevt :seon.agent/id)
                        (map #(d/pull db '[:seon.agent/id :seon.agent/parent :seon.agent/branch
                                           :seon.agent/role :seon.agent/scope :seon.agent/goal] (:e %)))
                        (remove #(str/starts-with? (str (namespace (:seon.agent/id %))) "example"))
                        (sort-by (juxt #(if (= :agent/root (:seon.agent/id %)) 0 1) #(str (:seon.agent/id %))))
                        vec)
        by-id (into {} (map (juxt :seon.agent/id identity)) all-agents)
        children-by-parent (group-by :seon.agent/parent all-agents)
        roots (filterv (fn [a]
                         (let [p (:seon.agent/parent a)]
                           (or (nil? p) (not (contains? by-id p)))))
                       all-agents)]
    [:aside {:id "seon-sidebar" :class (str "sidebar" (if sidebar-open? " open" " closed"))}
     (when sidebar-open?
       [:div {:class "sidebar-inner"}
        [:div {:class "sidebar-head"}
         [:span {:class "kicker"} (str "Agents · " (count all-agents))]]
        [:div {:class "tree-scroll"}
         (if (empty? roots)
           [:p {:class "muted tiny"} "No agents yet."]
           (for [r roots]
             (render-agent-tree-node children-by-parent viewing-id expanded-agents agent-states 0 r)))]
        [:details {:class "sidebar-spawn"}
         [:summary {:class "kicker"} "+ New agent"]
         [:form {:class "form-stack" :style "margin-top:8px"
                 :on-submit (list 'seon.web/spawn-agent-form! viewing-id)}
          [:input {:class "inp sm" :name "agent_id" :placeholder "Name (e.g. :ui/polish)"}]
          [:input {:class "inp sm" :name "scope" :placeholder "Scope (e.g. seon.web)"}]
          [:input {:class "inp sm" :name "goal" :placeholder "What should it work on?"}]
          [:button {:type "submit" :class "btn primary sm"} (str "Create under " (friendly-agent-name viewing-id))]]]])]))

;; --- In-Place 120fps Live Stream Dock (`id="seon-stream-unit"` — 0 DB Reads, ZERO Front-Truncation!) ---

(defn stream-unit [_db {:keys [live-streams errors]}]
  (let [active? (or (seq live-streams) (seq errors))]
    [:section {:id "seon-stream-unit" :class (str "stream-dock" (if active? " active" " idle"))}
     (when active?
       [:div {:class "stack-sm"}
        (for [[aid sinfo] (sort-by (comp str key) live-streams)
              :let [rtxt (str (:reasoning sinfo ""))
                    txt  (str (:text sinfo ""))
                    has-r? (not (str/blank? rtxt))
                    has-t? (not (str/blank? txt))
                    phase (case (:status sinfo)
                            :reasoning "Thinking"
                            :running-tools "Running tools"
                            :token "Responding"
                            "Working")]]
          [:div {:id (str "stream-" (agent-id->slug aid)) :class "live-stream-card"}
           [:div {:class "live-stream-head"}
            [:div {:class "pill-row"}
             [:span {:class "dot pulsing"}]
             [:span {:class "mono tiny strong"} (if (= aid :agent/root) "root" (str aid))]
             [:span {:class "muted tiny mono"} (str "· " phase "…")]]
            (when has-r?
              [:button {:type "button" :class "thinking-toggle"
                        :data-on:click "$showThinking = !$showThinking"}
               [:span {:class "when-closed"} "Show thinking"]
               [:span {:class "when-open"} "Hide thinking"]])]
           ;; Visibility driven 100% by CSS on un-morphed `#seon-app[data-thinking='open'|'closed']`
           ;; -> ZERO inline `style="display:none"` morph fighting, ZERO flicker at 120fps!
           (when has-r?
             [:div {:class "live-reasoning"} rtxt])
           (when has-t?
             [:div {:class "live-prose"} txt [:span {:class "cursor"} "▍"]])])
        (when (seq errors)
          [:div {:class "notice red mono tiny"} (pr-str (first errors))])])]))

;; --- Docked Canvas Composer (`id="seon-composer-unit"` — Never Overlaps Canvas, Never Interrupted!) ---

(defn composer-unit [db _opts]
  (let [{:keys [viewing-id view-mode]} (user-view-state db)
        show? (= view-mode :canvas)
        label (friendly-agent-name viewing-id)]
    [:section {:id "seon-composer-unit" :class (str "composer-dock" (if show? " active" " idle"))}
     (when show?
       [:form {:class "composer-shell" :on-submit (list 'seon.web/chat-root-form! viewing-id)}
        [:textarea {:class "composer-inp" :name "text" :rows 1
                    :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                    :placeholder (str "Message " label "…")}]
        [:button {:type "submit" :class "send-icon-btn" :aria-label "Send" :title "Send (Enter)"}
         svg-arrow-up]])]))

;; --- Helper: Tile Wrapper ---

(defn- render-tile-card
  ([branch-db tile agent-id] (render-tile-card branch-db nil tile agent-id))
  ([branch-db main-db {:keys [id source handle ref val]} agent-id]
   [:div {:id id :class "tile-wrap"}
    [:div {:class "tile-meta"}
     [:div {:class "pill-row"}
      [:span {:class (str "badge " (case source :eval "green" :pinned "amber" :ctx "amber" :child "blue" "muted"))}
       (name source)]
      (when handle
        [:span {:class "badge mono handle-pill"} (str handle)])]
     (when (and (= source :pinned) ref)
       [:button {:type "button" :class "link-muted tiny stage-remove"
                 :on-click (list 'seon.web/unpin-tile! agent-id ref)}
        "× unpin"])]
    (render/render-value branch-db val {:agent-id agent-id :main-db main-db})]))

(defn- interleave-timeline
  "Interleaves inbox messages and evals in true conversational order using `:seon.agent.eval/seen-messages`:
   messages consumed by turn T appear directly above turn T's evals/reply; unseen messages appear at the end.
   When `already-seen-ids` (from Datahike `d/history`) is provided, messages consumed by retracted/compacted
   evals are excluded from trailing unread bubbles."
  ([msgs evals] (interleave-timeline msgs evals nil))
  ([msgs evals already-seen-ids]
   (let [msgs-by-id (into {} (map (juxt :seon.agent.message/id identity)) msgs)
         by-turn (->> (group-by #(or (:seon.agent.eval/turn %) 0) evals)
                      (sort-by key))
         [items emitted-ids]
         (reduce
          (fn [[acc seen] [_turn es]]
            (let [ordered (sort-by #(or (:seon.agent.eval/call-index %) 0) es)
                  turn-seen-ids (->> (mapcat :seon.agent.eval/seen-messages ordered)
                                     distinct
                                     (remove seen))
                  turn-msgs (->> turn-seen-ids
                                 (keep #(get msgs-by-id %))
                                 (sort-by :seon.agent.message/seq))]
              [(-> acc
                   (into (map (fn [m] {:kind :msg :data m}) turn-msgs))
                   (into (map (fn [e] {:kind :eval :data e}) ordered)))
               (into seen (map :seon.agent.message/id turn-msgs))]))
          [[] #{}]
          by-turn)
         skip-ids (if (seq already-seen-ids) (into emitted-ids already-seen-ids) emitted-ids)
         trailing (->> msgs
                       (remove #(contains? skip-ids (:seon.agent.message/id %)))
                       (sort-by :seon.agent.message/seq)
                       (map (fn [m] {:kind :msg :data m})))]
     (into items trailing))))

(defn- render-emphasis
  "Parses `**bold**`, `*italic*`, and `~~strikethrough~~` inside non-code prose segments into Hiccup nodes."
  [^String s]
  (if (or (str/blank? s) (not (or (str/includes? s "*") (str/includes? s "~~"))))
    [s]
    (let [pat #"\*\*([^\s*](?:[^*]*?[^\s*])?)\*\*|\*([^\s*](?:[^*]*?[^\s*])?)\*|~~([^\s~](?:[^~]*?[^\s~])?)~~"
          m (re-matcher pat s)]
      (loop [pos 0 acc []]
        (if (.find m)
          (let [start (.start m)
                end (.end m)
                bold (.group m 1)
                ital (.group m 2)
                strike (.group m 3)
                acc (if (> start pos) (conj acc (subs s pos start)) acc)
                node (cond bold [:strong bold]
                           ital [:em ital]
                           strike [:del strike])]
            (recur end (conj acc node)))
          (if (< pos (count s))
            (conj acc (subs s pos))
            acc))))))

(defn render-inline-prose
  "Formats inline backtick code spans, `**bold**`, and `*italic*` inside editorial prose."
  [s]
  (let [text (str s)
        parts (str/split text #"`" -1)
        nodes (vec
               (mapcat (fn [[i seg]]
                         (if (and (odd? i) (< i (dec (count parts))))
                           [[:code {:class "inline-code"} seg]]
                           (render-emphasis seg)))
                       (map-indexed vector parts)))]
    (if (and (= 1 (count nodes)) (string? (first nodes)))
      (first nodes)
      (vec (cons :span nodes)))))

(defn render-markdown-blocks
  "Lightweight pure-Clojure Markdown -> Hiccup block renderer for editorial Canvas & Workspace prose:
   handles paragraphs, bullet lists (`- ` / `* `), numbered lists (`1. `), headings (`#`/`##`/`###`),
   fenced code blocks (```), inline `` `code` ``, `**bold**`, and `*italic*`."
  [s]
  (let [lines (str/split-lines (str (or s "")))
        flush-para (fn [acc para]
                     (if (seq para)
                       (let [joined (vec (interpose [:br] (map render-inline-prose para)))]
                         (conj acc (vec (cons :p (cons {:class "prose-p"} joined)))))
                       acc))
        flush-list (fn [acc ltype items]
                     (if (seq items)
                       (conj acc (vec (cons ltype (cons {:class "prose-list"}
                                                        (mapv (fn [it] [:li (render-inline-prose it)]) items)))))
                       acc))
        blocks
        (loop [ls lines
               acc []
               para []
               ltype nil
               litems []
               in-code? false
               code-lines []]
          (if (empty? ls)
            (let [acc (if in-code?
                        (conj acc [:pre {:class "code-box tiny"} [:code (str/join "\n" code-lines)]])
                        acc)]
              (-> acc (flush-para para) (flush-list ltype litems)))
            (let [line (first ls)
                  trimmed (str/trim line)
                  rest-ls (rest ls)]
              (cond
                in-code?
                (if (str/starts-with? trimmed "```")
                  (recur rest-ls
                         (conj acc [:pre {:class "code-box tiny"} [:code (str/join "\n" code-lines)]])
                         [] nil [] false [])
                  (recur rest-ls acc [] nil [] true (conj code-lines line)))

                (str/starts-with? trimmed "```")
                (recur rest-ls (-> acc (flush-para para) (flush-list ltype litems))
                       [] nil [] true [])

                (str/blank? trimmed)
                (recur rest-ls (-> acc (flush-para para) (flush-list ltype litems))
                       [] nil [] false [])

                (re-find #"^#{1,3}\s+" trimmed)
                (let [heading (str/replace-first trimmed #"^#{1,3}\s+" "")]
                  (recur rest-ls
                         (-> acc (flush-para para) (flush-list ltype litems)
                             (conj [:h3 {:class "prose-h3"} (render-inline-prose heading)]))
                         [] nil [] false []))

                (re-find #"^[-*]\s+" trimmed)
                (let [item (str/replace-first trimmed #"^[-*]\s+" "")
                      acc (cond-> (flush-para acc para)
                            (and ltype (not= ltype :ul)) (flush-list ltype litems))]
                  (recur rest-ls acc [] :ul (if (= ltype :ul) (conj litems item) [item]) false []))

                (re-find #"^\d+\.\s+" trimmed)
                (let [item (str/replace-first trimmed #"^\d+\.\s+" "")
                      acc (cond-> (flush-para acc para)
                            (and ltype (not= ltype :ol)) (flush-list ltype litems))]
                  (recur rest-ls acc [] :ol (if (= ltype :ol) (conj litems item) [item]) false []))

                :else
                (recur rest-ls (flush-list acc ltype litems)
                       (conj para line) nil [] false [])))))]
    (if (= 1 (count blocks))
      (first blocks)
      (vec (cons :div (cons {:class "prose-blocks"} blocks))))))

(defn- prompt-msg? [item]
  (and (= :msg (:kind item))
       (let [d (:data item)]
         (or (= :user (:seon.agent.message/from d))
             (contains? #{:delegate :task} (:seon.agent.message/type d))))))

(defn- split-exchanges
  "Groups an interleaved timeline into conversational exchanges (each starting at a user/task/delegate prompt)."
  [timeline]
  (reduce
   (fn [acc item]
     (if (prompt-msg? item)
       (conj acc [item])
       (if (seq acc)
         (conj (pop acc) (conj (peek acc) item))
         [[item]])))
   []
   timeline))

(defn- render-activity-fold
  "Chronological collapsed `▸ Thinking & actions` summary pill placed ABOVE the response prose
   (both in Canvas and in Workspace). Collapsed by default so typography and visuals breathe."
  [action-evals id-prefix]
  (when (seq action-evals)
    (let [total-ms (Math/round (double (reduce + 0.0 (map #(or (:seon.agent.eval/ms %) 0.0) action-evals))))
          n (count action-evals)]
      [:details {:id (str id-prefix "-act") :class "activity-fold"}
       [:summary
        [:span (str "Thinking & actions · " n " step" (when (not= 1 n) "s")
                    (when (pos? total-ms) (str " · " total-ms " ms")))]]
       [:div {:class "activity-body"}
        (for [[idx e] (map-indexed vector action-evals)
              :let [has-code? (not (str/blank? (str (:seon.agent.eval/code e))))
                    narration (when has-code? (not-empty (str/trim (str (:seon.agent.eval/thought e "")))))]]
          [:div {:id (str id-prefix "-step-" idx) :class "stack-sm"}
           (when narration
             [:p {:class "muted tiny" :style "margin:0"} narration])
           (when (not (str/blank? (str (:seon.agent.eval/reasoning e))))
             [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} (:seon.agent.eval/reasoning e)])
           (when has-code?
             [:details {:class "eval-fold"}
              [:summary {:class "eval-summary"}
               [:span {:class "badge mono handle-pill"} (str (or (:seon.agent.eval/handle e) 'result/e?))]
               (when-let [rs (:seon.agent.eval/result-schema e)]
                 [:span {:class "badge green tiny mono"} (name rs)])
               [:span {:class "mono tiny truncate"}
                (or (not-empty (:seon.agent.eval/intent e)) (:seon.agent.eval/code e))]
               [:span {:class (str "mono tiny " (if (:seon.agent.eval/error e) "red-text" "green-text"))}
                (if (:seon.agent.eval/error e)
                  "error"
                  (let [vs (str (:seon.agent.eval/value e ""))]
                    (str "=> " (if (> (count vs) 56) (str (subs vs 0 56) "…") vs))))]
               [:span {:class "muted tiny mono"} (str (:seon.agent.eval/ms e 0.0) " ms")]]
              [:div {:class "eval-detail"}
               [:pre {:class "code-box tiny"} [:code (:seon.agent.eval/code e)]]
               (when-let [err (:seon.agent.eval/error e)]
                 [:div {:class "notice red mono tiny"} err])
               (when (not (str/blank? (str (:seon.agent.eval/out e))))
                 [:pre {:class "code-box tiny"} [:code (:seon.agent.eval/out e)]])
               (when-let [v (:seon.agent.eval/value e)]
                 [:pre {:class "code-box tiny green-text"} [:code (str "=> " v)]])]])])]])))

;; --- VIEW 1: Pure Self-Replacing Live Canvas (`:view-mode :canvas` — `default-canvas [ctx]`) ---

(defn- windowed-history-indices
  "Selects up to 14 well-spaced step indices (always keeping first, current neighborhood, and latest)
   so the interactive timeline scrubber never overflows even at 50+ history steps or half-screen."
  [n cur-idx]
  (if (<= n 14)
    (vec (range n))
    (let [lo (max 1 (- cur-idx 5))
          hi (min (- n 2) (+ cur-idx 5))]
      (vec (distinct (concat [0] (range lo (inc hi)) [(dec n)]))))))

(defn- render-canvas-timeline
  "Sleek interactive Datahike history bar: real SVG Left/Right buttons + clickable timeline pips +
   step counter & Return to Live pill (only rendered when > 1 historical Canvas snapshot exists)."
  [hist-txs cur-idx as-of-tx]
  (let [n (count hist-txs)]
    (when (> n 1)
      (let [idx (or cur-idx (dec n))
            at-past? (boolean (and as-of-tx (< idx (dec n))))
            shown-idxs (windowed-history-indices n idx)]
        [:div {:class (str "canvas-timeline-bar" (when at-past? " historical"))
               :title "Time-travel through Canvas history via Datahike d/as-of"}
         [:div {:class "tl-nav"}
          [:button {:type "button" :class "tl-btn"
                    :aria-label "Previous step"
                    :title "Previous Canvas snapshot"
                    :disabled (zero? idx)
                    :on-click '(seon.web/step-canvas-history! -1)}
           svg-chevron-left]
          [:button {:type "button" :class "tl-btn"
                    :aria-label "Next step"
                    :title "Next Canvas snapshot"
                    :disabled (>= idx (dec n))
                    :on-click '(seon.web/step-canvas-history! 1)}
           svg-chevron-right]]
         [:div {:class "tl-track" :role "tablist" :aria-label "Canvas history timeline"}
          (for [i shown-idxs
                :let [tx (nth hist-txs i)
                      active? (= i idx)
                      past? (< i idx)]]
            [:button {:type "button"
                      :class (str "tl-pip" (cond active? " active" past? " past" :else ""))
                      :title (str "Step " (inc i) " of " n (when (= i (dec n)) " (Live)") " · tx " tx)
                      :aria-label (str "Jump to step " (inc i) " of " n)
                      :on-click (list 'seon.web/jump-canvas-tx! tx)}])]
         [:div {:class "tl-meta"}
          [:span (str (if at-past? "Time-travel · " "") (inc idx) " / " n)]
          (when at-past?
            [:button {:type "button" :class "step-live" :on-click '(seon.web/jump-canvas-live!)}
             "Live"])]]))))

(defn default-canvas
  "Default Canvas template taking the agent's live `ctx` map (at head OR historical `d/as-of` tx!):
   - Self-replacing presentation surface (zero chat log, zero pinned clutter pushing visuals down).
   - Instant feedback: the moment the user sends a message (`:seon.canvas/pending-prompt`), Canvas
     immediately replaces itself with the user's prompt + calm pulsing status while the agent thinks
     and builds the replacement UI.
   - Reads `:seon.agent/canvas` (`{:title :summary :body}`), falling back seamlessly to the latest
     `:seon.agent/stage` visual and/or the latest non-tool-calling prose response in crisp typography.
   - Fallback when the agent replies with plain text instead of calling `(user/canvas! ...)`:
     replaces the prompt cleanly with formatted Markdown prose, keeping any prior visual below a
     subtle `Previous visual` divider.
   - Any agent can replace this completely by defining `(defn my.ui/canvas [ctx] ...)` and calling
     `(user/set-render! 'my.ui/canvas)`."
  [ctx]
  (let [aid (:seon.agent/id ctx :agent/root)
        branch (:seon.agent/branch ctx :main)
        canvas (or (:seon.agent/canvas ctx) {})
        latest-stage (last (:seon.agent/stage ctx))
        title (or (not-empty (:title canvas)) (:title latest-stage))
        body (or (:body canvas) (:hiccup latest-stage))
        prose-list (:seon.canvas/latest-prose ctx)
        prose-text (when (seq prose-list) (str/join "\n\n" prose-list))
        newer-prose? (boolean (:seon.canvas/newer-prose? ctx))
        pending-prompt (:seon.canvas/pending-prompt ctx)
        reply-prompt (:seon.canvas/reply-prompt ctx)
        active? (boolean (:seon.canvas/active? ctx))
        summary (if newer-prose?
                  prose-text
                  (or (not-empty (:summary canvas)) prose-text))
        actions (:seon.canvas/latest-actions ctx)
        as-of-tx (:seon.canvas/as-of-tx ctx)
        hist-txs (:seon.canvas/history-txs ctx)
        step-idx (:seon.canvas/step-idx ctx)
        branch-feedback (:seon.canvas/feedback ctx)
        fresh? (and (nil? pending-prompt) (nil? title) (nil? body) (str/blank? (str summary)))]
    [:div {:id "seon-view-unit" :class "root-canvas"}
     (render-canvas-timeline hist-txs step-idx as-of-tx)

     (when (and branch-feedback (not (:ok? branch-feedback)))
       [:div {:class "notice red row-between"}
        [:span (:text branch-feedback)]
        [:button {:type "button" :class "link-muted tiny" :on-click '(seon.web/dismiss-feedback!)} "×"]])

     (when (and (nil? pending-prompt) (seq actions))
       (render-activity-fold actions "cv"))

     (cond
       pending-prompt
       [:div {:class "canvas-pending"}
        [:div {:class "root-kicker"} (str "You asked · " (friendly-agent-name aid))]
        [:h1 {:class "canvas-prompt-headline"} pending-prompt]
        [:div {:class "canvas-pending-status"}
         [:span {:class "dot pulsing"}]
         [:span (if active? "Thinking & building replacement view…" "Sent — waking agent…")]]
        (when (seq actions)
          (render-activity-fold actions "cv-pend"))
        (when body
          [:details {:class "prev-peek"}
           [:summary (str "Previous visual" (when title (str " · " title)))]
           [:div {:class "canvas-surface" :style "margin-top:10px"} body]])]

       fresh?
       (if (and (seq actions) (not (str/blank? (str reply-prompt))))
         [:div {:class "canvas-pending"}
          [:div {:class "root-kicker"} (str "You asked · " (friendly-agent-name aid))]
          [:h1 {:class "canvas-prompt-headline"} reply-prompt]
          [:p {:class "root-sub"}
           (str "Paused after " (count actions) " tool step" (when (not= 1 (count actions)) "s")
                " without a final response. Expand Thinking & actions above to inspect what ran, or reply below to continue.")]]
         [:div {:class "root-hero"}
          [:div {:class "root-kicker"} (str (friendly-agent-name aid) " · " branch)]
          [:h1 {:class "root-title"}
           (if (= aid :agent/root)
             "What would you like to build or visualize?"
             (str (friendly-agent-name aid) " is ready"))]
          [:p {:class "root-sub"}
           (or (not-empty (:seon.agent/goal ctx))
               "Ask anything below — Canvas updates live with every answer and visual, and Workspace keeps the full conversation.")]])

       newer-prose?
       [:div {:class "stack"}
        (when (not (str/blank? (str prose-text)))
          [:section {:class "canvas-reply"}
           (when (not (str/blank? (str reply-prompt)))
             [:div {:class "canvas-re-prompt"}
              (str "Re: " (if (> (count reply-prompt) 90) (str (subs reply-prompt 0 90) "…") reply-prompt))])
           (render-markdown-blocks prose-text)])
        (when body
          [:section {:class "canvas-prev-visual"}
           [:div {:class "prev-visual-head"}
            [:span {:class "kicker"}
             (str "Previous visual" (when title (str " · " title)))]]
           [:div {:class "canvas-surface"}
            body]])]

       :else
       [:div {:class "stack"}
        (when (or title (not (str/blank? (str summary))))
          [:header {:class "canvas-header"}
           (when title
             [:h1 {:class "canvas-title"} title])
           (when (not (str/blank? (str summary)))
             [:div {:class "canvas-summary"} (render-markdown-blocks summary)])])
        (when body
          [:section {:class "canvas-surface"}
           body])])]))

(defn- exchange-prose [ex]
  (->> ex
       (filter #(= :eval (:kind %)))
       (map :data)
       (filter #(str/blank? (str (:seon.agent.eval/code %))))
       (map :seon.agent.eval/thought)
       (remove #(str/blank? (str %)))
       vec))

(defn- exchange-max-eval-tx [ex]
  (->> ex
       (filter #(= :eval (:kind %)))
       (keep #(get-in % [:data :seon.agent.eval/tx]))
       (reduce max 0)))

(defn- exchange-user-msg [ex]
  (last (filter prompt-msg? ex)))

(defn- build-canvas-ctx [main-db bdb head-bdb aid {:keys [as-of-tx branch-feedback agent-states]}]
  (let [eff-main-db (if as-of-tx (try (d/as-of main-db as-of-tx) (catch Throwable _ main-db)) main-db)
        ag (pull-agent-entity eff-main-db bdb aid)
        evals (agent-evals-in bdb aid)
        msgs (agent-messages-in eff-main-db aid)
        seen-ids (store/agent-seen-message-ids bdb aid)
        timeline (interleave-timeline msgs evals seen-ids)
        exchanges (split-exchanges timeline)
        canvas-tx (max (or (attr-tx-in bdb aid :seon.agent/canvas) 0)
                       (or (attr-tx-in eff-main-db aid :seon.agent/canvas) 0)
                       (or (attr-tx-in bdb aid :seon.agent/stage) 0)
                       (or (attr-tx-in eff-main-db aid :seon.agent/stage) 0))
        latest-ex (peek exchanges)
        latest-user-msg (exchange-user-msg latest-ex)
        latest-msg-id (get-in latest-user-msg [:data :seon.agent.message/id])
        msg-unread? (boolean (and latest-msg-id (not (contains? seen-ids latest-msg-id))))
        active? (= :active (get agent-states aid))
        latest-prev-max-tx (if (> (count exchanges) 1)
                             (reduce max 0 (map exchange-max-eval-tx (pop exchanges)))
                             0)
        latest-updated-canvas? (and (pos? canvas-tx) (> canvas-tx latest-prev-max-tx))
        pending-prompt (when (and (nil? as-of-tx)
                                  latest-user-msg
                                  (empty? (exchange-prose latest-ex))
                                  (not latest-updated-canvas?)
                                  (or msg-unread? active?))
                         (str (:seon.agent.message/body (:data latest-user-msg))))
        settled-idx (last (keep-indexed (fn [i ex] (when (seq (exchange-prose ex)) i)) exchanges))
        display-ex (cond
                     pending-prompt latest-ex
                     (some? settled-idx) (nth exchanges settled-idx)
                     :else latest-ex)
        cur-evals (map :data (filter #(= :eval (:kind %)) display-ex))
        prose-parts (if pending-prompt [] (exchange-prose display-ex))
        reply-prompt (some-> (exchange-user-msg display-ex) :data :seon.agent.message/body)
        action-evals (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                   (not (str/blank? (str (:seon.agent.eval/reasoning %)))))
                              cur-evals)
        reply-tx (->> cur-evals
                      (filter #(and (str/blank? (str (:seon.agent.eval/code %)))
                                    (not (str/blank? (str (:seon.agent.eval/thought %))))))
                      (keep :seon.agent.eval/tx)
                      (reduce max 0))
        prev-ex-max-tx (if (and (some? settled-idx) (pos? settled-idx))
                         (reduce max 0 (map exchange-max-eval-tx (subvec exchanges 0 settled-idx)))
                         0)
        newer-prose? (boolean (and (nil? pending-prompt)
                                   (pos? reply-tx)
                                   (> reply-tx canvas-tx)
                                   (or (zero? canvas-tx) (<= canvas-tx prev-ex-max-tx))))
        hist-txs (canvas-history-txs head-bdb aid)
        step-idx (when (seq hist-txs)
                   (if as-of-tx
                     (or (first (keep-indexed (fn [i tx] (when (= tx as-of-tx) i)) hist-txs)) (dec (count hist-txs)))
                     (dec (count hist-txs))))]
    (assoc ag
           :seon.ctx/db bdb
           :seon.ctx/main-db main-db
           :seon.ctx/tx (:max-tx bdb)
           :seon.canvas/latest-prose prose-parts
           :seon.canvas/latest-actions action-evals
           :seon.canvas/canvas-tx canvas-tx
           :seon.canvas/reply-tx reply-tx
           :seon.canvas/newer-prose? newer-prose?
           :seon.canvas/pending-prompt pending-prompt
           :seon.canvas/reply-prompt reply-prompt
           :seon.canvas/active? active?
           :seon.canvas/as-of-tx as-of-tx
           :seon.canvas/history-txs hist-txs
           :seon.canvas/step-idx step-idx
           :seon.canvas/step-label (when (some? step-idx) (str "Step " (inc step-idx) " / " (count hist-txs)))
           :seon.canvas/feedback branch-feedback)))

(defn root-canvas [main-db {:keys [conn viewing-id as-of-tx branch] :as opts}]
  (let [aid (or viewing-id :agent/root)
        branch (or branch (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        head-bdb (branch-db-for main-db conn branch)
        eff-bdb (if as-of-tx (try (d/as-of head-bdb as-of-tx) (catch Throwable _ head-bdb)) head-bdb)
        ctx-map (build-canvas-ctx main-db eff-bdb head-bdb aid opts)]
    (default-canvas ctx-map)))

;; --- VIEW 2: Clean Traditional Chronological Chat + Live Context Tiles (`:view-mode :workspace`) ---

(defn- render-ws-exchanges [prefix exchanges]
  (for [[ex-idx ex] (map-indexed vector exchanges)
        :let [ex-msgs (filter #(= :msg (:kind %)) ex)
              ex-evals (map :data (filter #(= :eval (:kind %)) ex))
              prose-parts (->> ex-evals
                               (filter #(str/blank? (str (:seon.agent.eval/code %))))
                               (map :seon.agent.eval/thought)
                               (remove #(str/blank? (str %)))
                               vec)
              action-evals (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                         (not (str/blank? (str (:seon.agent.eval/reasoning %)))))
                                    ex-evals)]]
    [:div {:id (str prefix "-" ex-idx) :class "ws-exchange"}
     (for [[m-idx m-item] (map-indexed vector ex-msgs)
           :let [m (:data m-item)]]
       [:div {:id (str prefix "-" ex-idx "-m-" m-idx) :class "ws-user-bubble"}
        (:seon.agent.message/body m)])
     (when (or (seq action-evals) (seq prose-parts))
       [:div {:class (str "ws-agent-turn" (when (seq prose-parts) " has-prose"))}
        (render-activity-fold action-evals (str prefix "-" ex-idx))
        (for [[p-idx p] (map-indexed vector prose-parts)]
          [:div {:id (str prefix "-" ex-idx "-p-" p-idx) :class "chat-body"}
           (render-markdown-blocks p)])])]))

(defn agent-workspace [main-db {:keys [conn branch-feedback viewing-id]}]
  (let [aid (or viewing-id :agent/root)
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        ag (pull-agent-entity main-db bdb aid)
        evals (agent-evals-in bdb aid)
        msgs (agent-messages-in main-db aid)
        seen-ids (store/agent-seen-message-ids bdb aid)
        timeline (interleave-timeline msgs evals seen-ids)
        exchanges (split-exchanges timeline)
        retracted-evals (filterv :seon.agent.eval/retracted-tx (store/agent-historical-evals bdb aid))
        retracted-exchanges (when (seq retracted-evals)
                              (let [r-seen (into #{} (mapcat :seon.agent.eval/seen-messages) retracted-evals)
                                    r-msgs (filterv #(contains? r-seen (:seon.agent.message/id %)) msgs)]
                                (split-exchanges (interleave-timeline r-msgs retracted-evals))))
        tiles (render/discover-renderable-tiles main-db bdb ag evals health/report)
        derived (when (and conn (first (d/datoms main-db :avet :seon.agent/id aid)))
                  (reconciler/derive-agent-status main-db conn {} #{} #{} aid))]
    [:div {:id "seon-view-unit" :class "workspace-grid"}
     [:section {:class "ws-left"}
      [:div {:class "ws-head"}
       [:div
        [:div {:class "pill-row"}
         [:h2 {:class "mono"} (str aid)]
         [:span {:class "badge amber"} (name (or (:seon.agent/role ag) :worker))]
         (when derived
           [:span {:class (str "badge " (case (:status derived) :merged "green" :running "amber" "blue"))}
            (name (:status derived))])]
        [:div {:class "muted tiny mono" :style "margin-top:3px"}
         (str "branch " branch " @ tx " (:max-tx bdb)
              (when (:seon.agent/scope ag) (str " · scope " (:seon.agent/scope ag))))]]
       [:div {:class "pill-row"}
        [:button {:type "button" :class "btn sm" :on-click (list 'seon.web/run-tests! branch)} "Test Branch"]
        (when (not= branch :main)
          [:button {:type "button" :class "btn sm success" :on-click (list 'seon.web/merge-branch! branch)} "Merge → :main"])]]

      (when branch-feedback
        [:div {:class (str "notice row-between " (if (:ok? branch-feedback) "green" "red"))}
         [:span (:text branch-feedback)]
         [:button {:type "button" :class "link-muted tiny" :on-click '(seon.web/dismiss-feedback!)} "×"]])

      [:div {:class "ws-timeline" :data-agent (str aid)}
       (when (not (str/blank? (str (:seon.agent/goal ag))))
         [:div {:class "goal-banner"}
          [:span {:class "kicker"} "Goal"]
          [:p {:class "sm-copy" :style "margin:3px 0 0"} (:seon.agent/goal ag)]])

       (when (seq retracted-exchanges)
         [:details {:id "ws-retracted-history" :class "activity-fold" :style "margin-bottom:10px"}
          [:summary {:class "activity-summary"}
           [:span {:class "activity-label"}
            (str "Earlier history (d/history) · " (count retracted-evals) " compacted eval(s)")]]
          [:div {:class "stack-sm" :style "padding:8px 0 4px 10px;border-left:1px solid var(--border-subtle)"}
           (render-ws-exchanges "whx" retracted-exchanges)]])

       (if (empty? exchanges)
         [:p {:class "muted sm-copy" :style "padding:12px 0"}
          (if (seq retracted-exchanges)
            "Working history compacted (expand Earlier history above or time-travel in Canvas). Send a message to continue."
            "No conversation on this branch yet. Send a message or evaluate a Clojure form below.")]
         (render-ws-exchanges "wx" exchanges))]

      [:form {:class "ws-composer" :on-submit (list 'seon.web/eval-agent-form! aid branch)}
       [:div {:class "composer-shell"}
        [:textarea {:class "composer-inp" :name "code" :rows 1
                    :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                    :placeholder (str "Message " (friendly-agent-name aid) " or (eval Clojure)…")}]
        [:button {:type "submit" :class "send-icon-btn" :aria-label "Send" :title "Send (Enter)"}
         svg-arrow-up]]]]

     [:section {:class "ws-right"}
      [:div {:class "ws-head"}
       [:div
        [:h2 (str "Live Context Tiles (" (count tiles) ")")]
        [:span {:class "muted tiny"} "Everything pulled in @seon.ctx/ctx or returned by evals with a :seon.render/hiccup renderer."]]
       [:button {:type "button" :class "nav-pill tiny"
                 :on-click '(seon.web/set-view-mode! :debug)} "Wire Debug →"]]

      [:form {:class "row-form" :style "margin-bottom:10px" :on-submit (list 'seon.web/pin-ref-form! aid)}
       [:input {:class "inp sm mono flex-1" :name "ref"
                :placeholder "Pin ref, e.g. [:seon.code.ns/name 'seon.web]"}]
       [:button {:type "submit" :class "btn sm"} "+ Pin to Walk"]]

      [:div {:class "tiles-scroll"}
       (if (empty? tiles)
         [:div {:class "card muted sm-copy"}
          "No renderable tiles in this agent's walk yet. Evaluate " [:code "(user/health)"] " or "
          [:code "[:seon.code.ns/name 'seon.render]"] " on the left to watch it appear here live."]
         (for [tile tiles]
           (render-tile-card bdb main-db tile aid)))]]]))

;; --- VIEW 3: Zero-Cost Wire & System Debug (`:view-mode :debug`) ---

(defn- current-test-verdicts [db]
  (let [live-test-vars (into #{}
                             (keep (fn [dt]
                                     (let [v (d/pull db '[:seon.code.var/name :seon.code.var/test?] (:e dt))]
                                       (when (:seon.code.var/test? v) (:seon.code.var/name v)))))
                             (d/datoms db :aevt :seon.code.var/name))
        all (->> (d/datoms db :aevt :seon.test/digest)
                 (map #(d/pull db '[:seon.test/digest :seon.test/var :seon.test/kind
                                    :seon.test/pass? :seon.test/ms :seon.test/ticks :seon.test/report] (:e %)))
                 (filter #(contains? live-test-vars (:seon.test/var %))))
        latest-by-var (->> all
                           (group-by :seon.test/var)
                           (map (fn [[_ vs]] (last (sort-by #(or (:seon.test/pass? %) false) vs))))
                           (sort-by (juxt #(if (:seon.test/pass? %) 1 0) #(str (:seon.test/var %))))
                           vec)]
    {:current latest-by-var
     :historical (vec (sort-by #(str (:seon.test/var %)) all))}))

(defn- wire-role-badge [role]
  (case role
    "system"    "badge amber"
    "user"      "badge blue"
    "assistant" "badge green"
    "tool"      "badge purple"
    "badge"))

(defn- detect-xml-tags [s]
  (when (string? s)
    (vec (distinct (map second (re-seq #"<(/?(?:session|agent|walk-context|walk-diff|message|live-viewport))\b" s))))))

(defn- pretty-edn [x]
  (try
    (let [s (with-out-str (fipp/pprint x {:width 88}))]
      (if (> (count s) 12000) (str (subs s 0 12000) "\n… (truncated)") s))
    (catch Throwable _ (pr-str x))))

(defn debug-view [main-db {:keys [conn walk-summary branch-feedback viewing-id selected-branch viewport-screenshot]}]
  (let [aid (or viewing-id :agent/root)
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        wire (build-live-wire-request main-db bdb aid {:viewport-screenshot viewport-screenshot
                                                       :vision? (boolean viewport-screenshot)})
        ctx-snap (or (try (ctx/build bdb [:seon.agent/id aid]) (catch Throwable _ nil))
                     (try (ctx/build main-db [:seon.agent/id aid]) (catch Throwable _ nil)))
        req-body (model/build-request-body {:model (store/config-val main-db aid :seon.config/ai-model)
                                            :messages (:messages wire)
                                            :thinking (store/config-val main-db aid :seon.config/ai-thinking)}
                                           nil nil nil)
        {:keys [current historical]} (current-test-verdicts bdb)
        branches (when conn (sort (d/branches conn)))
        sel-br (or selected-branch (first (remove #{:main} branches)))
        diff-info (when (and conn sel-br (not= sel-br :main))
                    (try (merge/diff-branch conn :main sel-br) (catch Throwable _ nil)))
        h (health/report bdb)]
    [:div {:id "seon-view-unit" :class "debug-wrap"}
     (when branch-feedback
       [:div {:class (str "notice " (if (:ok? branch-feedback) "green" "red"))}
        (:text branch-feedback)])

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:div
        [:h2 (str "Exact Wire Request for " aid " (branch " branch ")")]
        [:span {:class "muted tiny"} "Built by model/build-agent-wire-messages — zero second interpretation. Prefix messages stay byte-identical for KV cache."]]
       [:div {:class "pill-row"}
        (when viewport-screenshot
          [:span {:class "badge amber mono"}
           (str "WKWebView " (:width viewport-screenshot) "×" (:height viewport-screenshot)
                (when (:ms viewport-screenshot) (str " · " (:ms viewport-screenshot) "ms")))])
        [:span {:class "badge blue mono"} (str "~" (:total-tokens wire) " tokens")]
        [:span {:class "badge green mono"} (str (count (:messages wire)) " wire msgs")]
        [:span {:class "badge mono"} (str "model: " (:model req-body))]]]

      [:div {:class "wire-list"}
       (for [[idx [m toks]] (map-indexed vector (map vector (:messages wire) (:message-tokens wire)))
             :let [role (:role m)
                   content (:content m)
                   img-url (when (sequential? content)
                             (some #(when (= "image_url" (:type %)) (get-in % [:image_url :url])) content))
                   text-body (if (sequential? content)
                               (str/join "\n" (map #(if (= "image_url" (:type %))
                                                      (str "[IMAGE_URL: ephemeral live WKWebView viewport"
                                                           (when (:bytes viewport-screenshot)
                                                             (str " · " (quot (:bytes viewport-screenshot) 1024) " KB"))
                                                           "]")
                                                      (:text %))
                                                   content))
                               (str content))
                   xml-tags (detect-xml-tags text-body)
                   preview (let [line (first (remove str/blank? (str/split-lines text-body)))]
                             (if (> (count (str line)) 120) (str (subs line 0 120) "…") (str line)))]]
         [:details {:id (str "wm-" idx) :class "wire-row" :open (or (< idx 4) (some? img-url))}
          [:summary {:class "wire-summary"}
           [:span {:class "muted tiny mono"} (str "#" idx)]
           [:span {:class (wire-role-badge role)} (str/upper-case role)]
           (for [tag xml-tags]
             [:span {:class "badge mono xml-tag"} (str "<" tag ">")])
           (when img-url
             [:span {:class "badge amber mono"} "image_url"])
           (when (seq (:tool_calls m))
             [:span {:class "badge green mono"} (str (count (:tool_calls m)) " tool_call(s)")])
           (when (:tool_call_id m)
             [:span {:class "badge mono"} (str "call_id=" (:tool_call_id m))])
           [:span {:class "muted tiny truncate flex-1"} preview]
           [:span {:class "mono tiny muted"} (str "~" toks " tok")]]
          [:div {:class "wire-body"}
           (when (:reasoning_content m)
             [:div {:style "margin-bottom:6px"}
              [:span {:class "kicker"} "reasoning_content"]
              [:pre {:class "code-box tiny"} (:reasoning_content m)]])
           (when (not (str/blank? text-body))
             [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} text-body])
           (when img-url
             [:img {:class "wire-img" :src img-url :alt "Live WKWebView ephemeral tail screenshot"}])
           (when (seq (:tool_calls m))
             [:div {:style "margin-top:6px"}
              [:span {:class "kicker"} "tool_calls"]
              [:pre {:class "code-box tiny"} (json/write-str (:tool_calls m))]])]])]

      [:details {:class "fold" :style "margin-top:10px"}
       [:summary {:class "muted tiny"} "Raw OpenAI-Compat / DeepSeek Request JSON"]
       [:pre {:class "code-box tiny" :style "max-height:360px;overflow:auto"}
        (str/replace (json/write-str req-body)
                     #"data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]{80,}"
                     "data:image/jpeg;base64,<live-wkwebview-screenshot-redacted-for-display>")]]]

     [:div {:class "grid-2"}
      [:section {:class "card"}
       [:div {:class "card-head"}
        [:div
         [:h2 (str "Live @seon.ctx/ctx Atom Value (" aid ")")]
         [:span {:class "muted tiny"} "Live incremental Datahike pull backing @seon.ctx/ctx inside this agent's REPL"]]
        [:div {:class "pill-row"}
         [:span {:class "badge amber mono"} (str (count (:entries ctx-snap)) " pull clauses")]
         [:span {:class "badge mono"} (str "tx " (or (:tx ctx-snap) (:max-tx bdb)))]]]
       (if ctx-snap
         [:div {:class "stack-sm"}
          [:details {:class "fold"}
           [:summary {:class "muted tiny mono"}
            (str "Active :seon.ctx/pattern (" (count (:pattern ctx-snap)) " items)")]
           [:pre {:class "code-box tiny ctx-pre"} [:code (pretty-edn (:pattern ctx-snap))]]]
          [:pre {:class "code-box tiny ctx-pre"} [:code (pretty-edn (:val ctx-snap))]]]
         [:p {:class "muted sm-copy"} "No agent entity found for @seon.ctx/ctx."])]

      [:section {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Live datahike.reads Read-Sets (Incremental Walk)"]
        [:span {:class "muted tiny"} "0 DB work on untouched units"]]
       [:div {:class "table-scroll"}
        [:table {:class "data-table"}
         [:thead [:tr [:th "Mounted Unit"] [:th "Reads"] [:th "Sample Read Index Keys"]]]
         [:tbody
          (for [{:keys [id reads-count reads-idx]} walk-summary]
            [:tr
             [:td {:class "mono"} (str "#" id)]
             [:td {:class "mono"} (str (or reads-count 0))]
             [:td {:class "mono tiny muted"} (pr-str (vec (take 6 reads-idx)))]])]]]]]

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:div
        [:h2 (str "Rendered Versions — Human HTML vs Model Text (" (count (:tiles wire)) " tiles)")]
        [:span {:class "muted tiny"} "Single-source Hiccup rendered live on the left (Datastar HTML) and projected via render/hiccup->text on the right (sent to LLM)"]]
       [:span {:class "badge purple mono"} "1 Hiccup → 2 targets"]]
      (if (empty? (:tiles wire))
        [:p {:class "muted sm-copy"} "No renderable tiles discovered for this agent yet."]
        [:div {:class "stack"}
         (for [[idx t] (map-indexed vector (:tiles wire))]
           [:div {:id (str "dbg-tile-" idx) :class "debug-tile-pair"}
            [:div {:class "debug-tile-col"}
             [:div {:class "row-between"}
              [:span {:class "kicker"} "Human HTML (Browser)"]
              [:div {:class "pill-row"}
               [:span {:class (str "badge " (case (:source t) :eval "green" :pinned "amber" :child "blue" "muted"))}
                (name (:source t))]
               (when (:handle t)
                 [:span {:class "badge mono handle-pill"} (str (:handle t))])
               [:span {:class "badge mono"} (str (:schema t))]]]
             (render/render-value bdb (:val t) {:agent-id aid :main-db main-db})]
            [:div {:class "debug-tile-col"}
             [:div {:class "row-between"}
              [:span {:class "kicker"} "Model Text (render/hiccup->text)"]
              [:span {:class "mono tiny muted"} (str (count (:text t "")) " chars")]]
             [:pre {:class "code-box tiny ctx-pre"} (:text t)]]])])]

     [:div {:class "grid-2"}
      (render/render-value bdb h)

      [:section {:class "card"}
       [:div {:class "card-head"}
        [:h2 (str "Branches & 3-Way Git Diff (" (count branches) ")")]
        [:span {:class "muted tiny"} "0 worktrees · refs/heads/seon/*"]]
       [:div {:class "pill-row" :style "margin-bottom:8px"}
        (for [b branches]
          [:button {:type "button"
                    :class (str "btn sm" (when (= b sel-br) " primary"))
                    :on-click (list 'seon.web/select-branch! b)}
           (str b)])]
       (if diff-info
         [:div {:class "stack-sm"}
          [:div {:class "pill-row"}
           [:span {:class "badge green"} (str "+" (count (:added diff-info)) " added")]
           [:span {:class "badge blue"} (str "~" (count (:changed diff-info)) " changed")]
           [:span {:class "badge red"} (str "-" (count (:removed diff-info)) " removed")]
           [:button {:type "button" :class "btn sm success" :on-click (list 'seon.web/merge-branch! sel-br)}
            (str "Merge " sel-br " → :main")]]
          (when (seq (:changed diff-info))
            [:pre {:class "code-box tiny"} (pr-str (:changed diff-info))])]
         [:p {:class "muted sm-copy"} "All branches are synced with :main (or select a branch above to inspect its 3-way diff)."])]]

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:h2 "Register Global Malli Schema (→ Auto Datahike)"]
       [:span {:class "muted tiny"} (str (count (d/datoms main-db :aevt :seon.schema/key)) " schemas · " (count (:schema main-db)) " DH attrs")]]
      [:form {:class "form-stack" :on-submit '(seon.web/register-schema-form!)}
       [:div {:class "row-form"}
        [:input {:class "inp sm mono flex-1" :name "key" :placeholder ":seon.app/amount"}]
        [:input {:class "inp sm mono flex-1" :name "form" :placeholder "[:double {:min 0.0}]"}]
        [:input {:class "inp sm flex-1" :name "doc" :placeholder "Description..."}]
        [:button {:type "submit" :class "btn sm primary"} "Register Schema"]]]]

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:h2 (str "Current Reach-Cached Test Verdicts (" (count current) ")")]
       [:span {:class "muted tiny"} "Filtered to live test vars"]]
      (if (empty? current)
        [:p {:class "muted sm-copy"} "No test digests recorded yet."]
        [:div {:class "table-scroll"}
         [:table {:class "data-table"}
          [:thead [:tr [:th "Test Var"] [:th "Verdict"] [:th "Cost"]]]
          [:tbody
           (for [t current]
             [:tr
              [:td {:class "mono tiny"} (str (:seon.test/var t))]
              [:td [:span {:class (str "badge " (if (:seon.test/pass? t) "green" "red"))}
                    (if (:seon.test/pass? t) "PASS" "FAIL")]]
              [:td {:class "mono tiny muted"} (str (:seon.test/ms t) " ms · " (quot (long (:seon.test/ticks t 0)) 1000) "k ticks")]])]]])
      (when (> (count historical) (count current))
        [:details {:class "fold" :style "margin-top:8px"}
         [:summary {:class "muted tiny"} (str "Show " (count historical) " historical cached digests")]
         [:pre {:class "code-box tiny"}
          (str/join "\n" (map #(str (:seon.test/var %) " " (subs (:seon.test/digest %) 0 10) " " (if (:seon.test/pass? %) "PASS" "FAIL")) historical))]])]

     [:details {:class "card fold"}
      [:summary {:class "muted tiny mono"} "Diagnostics & Crash-Recovery Drill"]
      [:div {:class "row-between" :style "margin-top:10px"}
       [:span {:class "muted tiny"} "Simulates sudden power loss (SIGKILLs forked children and cold-reboots the store while keeping live SSE tabs connected)."]
       [:button {:type "button" :class "btn sm danger" :on-click '(seon.web/power-loss-reboot!)} "Cold Reboot Drill"]]]]))

;; --- Active View Unit Dispatching Purely From Datahike `:user/default` ---

(defn- invoke-canvas-render-fn [rfn ctx-map db opts]
  (try
    (rfn ctx-map)
    (catch clojure.lang.ArityException _
      (rfn db opts))))

(defn view-unit [db {:keys [conn branch-renders] :as opts}]
  (let [{:keys [viewing-id view-mode selected-branch as-of-tx]} (user-view-state db)
        view-opts (assoc opts :viewing-id viewing-id :selected-branch selected-branch :as-of-tx as-of-tx)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        head-bdb (branch-db-for db conn branch)
        eff-bdb (if (and (= view-mode :canvas) as-of-tx)
                  (try (d/as-of head-bdb as-of-tx) (catch Throwable _ head-bdb))
                  head-bdb)
        ag (pull-agent-entity db eff-bdb viewing-id)
        custom-sym (:seon.agent/render-fn ag)
        br-render (when (and (= view-mode :canvas) (nil? as-of-tx))
                    (get branch-renders viewing-id))
        use-br-render? (and br-render
                            (= (:branch br-render) branch)
                            (or (nil? custom-sym) (= (:render-fn br-render) custom-sym)))
        hist-fn (when (and (= view-mode :canvas) as-of-tx
                           (qualified-symbol? custom-sym)
                           (not= custom-sym 'seon.render.ui/root-canvas))
                  (eval-historical-render-fn head-bdb eff-bdb custom-sym))
        custom-fn (or hist-fn
                      (when (and (= view-mode :canvas)
                                 (not use-br-render?)
                                 (qualified-symbol? custom-sym)
                                 (not= custom-sym 'seon.render.ui/root-canvas))
                        (or (try (resolve custom-sym) (catch Throwable _ nil))
                            (try (requiring-resolve custom-sym) (catch Throwable _ nil)))))]
    (cond
      use-br-render?
      [:div {:id "seon-view-unit" :class "root-canvas"}
       [:div {:class "branch-preview-bar"}
        [:span (str "Live branch UI · " (:render-fn br-render) " on " branch " @ tx " (:branch-tx br-render))]
        [:div {:class "pill-row"}
         [:button {:type "button" :class "btn sm success"
                   :on-click (list 'seon.web/merge-branch! branch)} "Merge → :main"]
         [:button {:type "button" :class "btn sm"
                   :on-click '(seon.web/set-view-mode! :workspace)} "Workspace"]]]
       (if (:error br-render)
         [:div {:class "card notice red"}
          [:strong (str "Branch render fault (" (:render-fn br-render) "): ")]
          [:span {:class "mono"} (:error br-render)]]
         (:hiccup br-render))]

      custom-fn
      (let [ctx-map (build-canvas-ctx db eff-bdb head-bdb viewing-id view-opts)
            rendered (invoke-canvas-render-fn custom-fn ctx-map db (assoc view-opts :branch-db eff-bdb :agent ag))
            tl (render-canvas-timeline (:seon.canvas/history-txs ctx-map) (:seon.canvas/step-idx ctx-map) as-of-tx)]
        (if tl
          [:div {:id "seon-view-unit" :class "root-canvas"}
           tl
           rendered]
          rendered))

      (= view-mode :canvas)    ((var-get #'root-canvas) db view-opts)
      (= view-mode :workspace) ((var-get #'agent-workspace) db view-opts)
      (= view-mode :debug)     ((var-get #'debug-view) db view-opts)
      :else                    ((var-get #'root-canvas) db view-opts))))

;; --- Incremental Walk Unit Specs (5 Units: Header, Sidebar, View, 120fps Stream, Docked Composer) ---

(defn page-unit-specs
  "Returns the 5 mounted incremental-walk units — 100% driven by Datahike `:user/default`:
   1. `seon-header`: quiet single-row Phosphor top bar (clean even at half-screen)
   2. `seon-sidebar`: collapsible recursive agent-tree sidebar (floating drawer <= 960px)
   3. `seon-view-unit`: active `:canvas` (with interactive timeline scrubber), `:workspace`, or `:debug`
   4. `seon-stream-unit`: 120fps zero-flicker live stream dock (0 DB reads)
   5. `seon-composer-unit`: sleek minimal message bar (never overlaps Canvas)."
  [st]
  [{:id "seon-header"
    :comp #'header-unit
    :args [(select-keys st [:boot-seq :agent-states])]}
   {:id "seon-sidebar"
    :comp #'sidebar-unit
    :args [(select-keys st [:agent-states])]}
   {:id "seon-view-unit"
    :comp #'view-unit
    :args [(select-keys st [:conn :branch-feedback :walk-summary :agent-states :branch-renders :viewport-screenshot])]}
   {:id "seon-stream-unit"
    :comp #'stream-unit
    :args [(select-keys st [:live-streams :errors])]}
   {:id "seon-composer-unit"
    :comp #'composer-unit
    :args [{}]}])

(def default-canvas-syms
  #{'seon.render.ui/agent-canvas
    'seon.render.ui/root-canvas
    'seon.render.ui/default-canvas})

(defn entry-agent-id
  "Resolves the user's default/home entry agent purely from Datahike data:
   1. Any agent marked `:seon.agent/entry? true`
   2. Else the first top-level non-example agent with nil `:seon.agent/parent`
   3. Else nil (when no agents exist yet)."
  [db]
  (when (and db (contains? (:schema db) :seon.agent/id))
    (or (when (contains? (:schema db) :seon.agent/entry?)
          (->> (d/datoms db :avet :seon.agent/entry? true)
               (keep #(:seon.agent/id (d/pull db '[:seon.agent/id] (:e %))))
               (sort-by str)
               first))
        (->> (d/datoms db :aevt :seon.agent/id)
             (map #(d/pull db '[:seon.agent/id :seon.agent/parent :seon.agent/role] (:e %)))
             (remove #(let [aid (:seon.agent/id %)]
                        (or (nil? aid)
                            (= :repl (:seon.agent/role %))
                            (= "example" (namespace aid))
                            (some? (:seon.agent/parent %)))))
             (sort-by #(str (:seon.agent/id %)))
             first
             :seon.agent/id))))

(defn agent-label
  "Returns human display label from `:seon.agent/label` on an agent map or DB lookup,
   falling back to generic formatting of `aid`."
  ([ag] (agent-label ag (:seon.agent/id ag)))
  ([db-or-ag aid]
   (let [lbl (cond
               (map? db-or-ag) (:seon.agent/label db-or-ag)
               (and db-or-ag aid
                    (contains? (:schema db-or-ag) :seon.agent/label)
                    (first (d/datoms db-or-ag :avet :seon.agent/id aid)))
               (:seon.agent/label (d/pull db-or-ag '[:seon.agent/label] [:seon.agent/id aid]))
               :else nil)]
     (or (not-empty lbl) (friendly-agent-name aid)))))
