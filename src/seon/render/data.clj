(ns seon.render.data
  "Data-driven Datahike agent, user viewport, timeline history, and eval/message query helpers."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render :as render]
            [seon.store :as store]))

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

(defn agent-id->slug
  {:malli/schema [:=> [:cat [:maybe :keyword]] [:maybe :string]], :cost/ticks 500000, :cost/bytes 1048576}
  [aid]
  (when aid
    (if-let [ns-part (namespace aid)]
      (if (= "agent" ns-part)
        (name aid)
        (str ns-part "~" (name aid)))
      (name aid))))

(defn slug->agent-id
  {:malli/schema [:=> [:cat :string] [:maybe :keyword]], :cost/ticks 500000, :cost/bytes 1048576}
  [^String slug]
  (let [s (str/trim (str slug))]
    (cond
      (str/blank? s) nil
      (str/includes? s "~") (let [[ns-p nm] (str/split s #"~" 2)] (keyword ns-p nm))
      :else (keyword "agent" s))))

(defn agent-href
  {:malli/schema [:=> [:cat [:maybe :keyword]] :string], :cost/ticks 500000, :cost/bytes 1048576}
  [aid] (str "/agent/" (agent-id->slug aid)))

(defn debug-href
  {:malli/schema [:=> [:cat [:maybe :keyword]] :string], :cost/ticks 500000, :cost/bytes 1048576}
  [aid] (str "/agent/" (agent-id->slug aid) "/debug"))

(defn friendly-agent-name [aid]
  (cond
    (nil? aid) "Agent"
    (= "agent" (namespace aid)) (name aid)
    (namespace aid) (str (namespace aid) "/" (name aid))
    :else (name aid)))

(defn agent-label
  "Returns human display label from `:seon.agent/label` on an agent entity map or DB lookup,
   falling back to generic formatting of `aid`."
  ([ag] (agent-label ag (:seon.agent/id ag)))
  ([db-or-ag aid]
   (let [lbl (cond
               (and (map? db-or-ag) (contains? db-or-ag :seon.agent/id))
               (:seon.agent/label db-or-ag)

               (and db-or-ag aid
                    (contains? (:schema db-or-ag) :seon.agent/label)
                    (first (d/datoms db-or-ag :avet :seon.agent/id aid)))
               (:seon.agent/label (d/pull db-or-ag '[:seon.agent/label] [:seon.agent/id aid]))

               :else nil)]
     (or (not-empty lbl) (friendly-agent-name aid)))))

(defn router-agent?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean], :cost/ticks 500000, :cost/bytes 1048576}
  [ag]
  (boolean (or (:seon.agent/router? ag)
               (= :coordinator (:seon.agent/role ag)))))

(defn reads-main?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean], :cost/ticks 500000, :cost/bytes 1048576}
  [ag]
  (boolean (or (= :main (:seon.agent/read-branch ag))
               (:seon.agent/router? ag)
               (= :coordinator (:seon.agent/role ag)))))

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

(defn branch-db-for [main-db conn branch]
  (if (or (nil? branch) (= branch :main) (nil? conn))
    main-db
    (or (try (store/head conn branch) (catch Throwable _ nil)) main-db)))

(defn latest-attr-val [main-db branch-db ma ba attr]
  (let [mt (or (:tx (first (d/datoms main-db :eavt (:db/id ma) attr))) 0)
        bt (or (:tx (first (d/datoms branch-db :eavt (:db/id ba) attr))) 0)]
    (if (> bt mt) (get ba attr) (get ma attr))))

(defn pull-agent-entity [main-db branch-db agent-id]
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

(defn attr-tx-in [db agent-id attr]
  (when (and db agent-id (contains? (:schema db) attr))
    (when-let [eid (:db/id (d/pull db '[:db/id] [:seon.agent/id agent-id]))]
      (reduce max 0 (map :tx (d/datoms db :eavt eid attr))))))

(defonce ^:private ^:seon.flow/edge canvas-history-cache
  (store/lru-cache 32))

(defn canvas-history-txs
  "Returns sorted distinct Datahike transaction IDs on `bdb` where `agent-id`'s Canvas changed
   (`:seon.agent/canvas`, `:seon.agent/stage`, `:seon.agent/render-fn`, custom render-fn code edits,
   or non-tool-calling prose replies). Content-keyed by `[branch max-tx agent-id]` in an LRU cache."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:maybe [:vector :int]]]}
  [bdb agent-id]
  (when (and bdb agent-id)
    (let [ck (when (and (not (instance? datahike.db.AsOfDB bdb))
                        (not (instance? datahike.db.HistoricalDB bdb)))
               [(get-in bdb [:config :branch] :main) (:max-tx bdb) agent-id])
          compute
          (fn []
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
              (catch Throwable _ [])))]
      (if ck
        (store/lru-get! canvas-history-cache 32 ck compute)
        (compute)))))

(defn eval-historical-render-fn
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

(defn agent-evals-in
  {:malli/schema [:=> [:cat :seon.host/db :keyword] [:vector :map]]}
  [db agent-id]
  (->> (d/datoms db :avet :seon.agent.eval/agent agent-id)
       (map (fn [dt]
              (assoc (d/pull db '[:seon.agent.eval/id :seon.agent.eval/turn :seon.agent.eval/call-index
                                :seon.agent.eval/call-id :seon.agent.eval/handle :seon.agent.eval/result-schema
                                :seon.agent.eval/thought :seon.agent.eval/reasoning :seon.agent.eval/intent
                                :seon.agent.eval/code :seon.agent.eval/value :seon.agent.eval/out
                                :seon.agent.eval/error :seon.agent.eval/ms :seon.agent.eval/ticks :seon.agent.eval/bytes
                                :seon.agent.eval/prefix-hash
                                :seon.agent.eval/prompt-tokens :seon.agent.eval/completion-tokens
                                :seon.agent.eval/cache-hit-tokens :seon.agent.eval/cache-miss-tokens
                                :seon.agent.eval/seen-messages] (:e dt))
                     :seon.agent.eval/tx (:tx dt)
                     :seon.agent.eval/agent agent-id)))
       (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0)))
       vec))

(defn agent-messages-in [main-db agent-id]
  (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
       (map (fn [dt]
              (assoc (d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                                       :seon.agent.message/from :seon.agent.message/to
                                       :seon.agent.message/type :seon.agent.message/body] (:e dt))
                     :seon.agent.message/tx (:tx dt))))
       (sort-by :seon.agent.message/seq)
       vec))

(defn evals->turns
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

(defn first-doc-line
  {:malli/schema [:=> [:cat [:maybe :string] [:int {:min 0, :max 500}]] [:maybe :string]], :cost/ticks 500000, :cost/bytes 1048576}
  [^String s max-len]
  (when-not (str/blank? (str s))
    (let [line (-> (str s) str/trim str/split-lines first str/trim)]
      (if (> (count line) max-len)
        (str (subs line 0 max-len) "…")
        line))))

(defn compact-entity-preview [m max-len]
  (let [cleaned (into {}
                      (keep (fn [[k v]]
                              (when (and (not= k :db/id) (not (nil? v)))
                                (let [vs (pr-str v)]
                                  [k (if (> (count vs) 60) (str (subs vs 0 60) "…") v)]))))
                      (take 6 (sort-by (comp str key) m)))
        s (pr-str cleaned)]
    (if (> (count s) max-len) (str (subs s 0 max-len) "…") s)))