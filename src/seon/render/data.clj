(ns seon.render.data
  "Data-driven Datahike agent, user viewport, inspector trail, lab preferences,
   timeline history, lookup-ref, and eval/message query helpers."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]))

(defn- db-schema [db]
  (if (instance? datahike.db.interface.ISearch db)
    (or (:schema db) (d/schema db) {})
    {}))

(defn canonical-view-mode
  "Normalizes holistic and legacy view modes (`:canvas`->`:session`, `:debug`->`:context`, `:workspace`->`:system`)."
  {:malli/schema [:=> [:cat [:maybe :keyword]] :keyword] :cost/ticks 50000}
  [mode]
  (case mode
    (:session :canvas) :session
    (:context :debug) :context
    (:system :workspace) :system
    :inspect :inspect
    :session))

(defn normalize-inspect-ref
  "Normalizes an entity lookup ref `[attr val]` (unwrapping quoted symbols), or returns nil."
  {:malli/schema [:=> [:cat :any] [:maybe [:vector :any]]] :cost/ticks 50000}
  [r]
  (when (and (vector? r) (= 2 (count r)) (qualified-keyword? (first r)) (some? (second r)))
    (let [v (second r)]
      [(first r) (if (and (seq? v) (= 'quote (first v))) (second v) v)])))

(def ^:private id-attrs
  [:seon.code.var/name :seon.code.ns/name :seon.task/id :seon.rule/id
   :seon.schema/key :seon.agent/id :seon.agent.eval/id :seon.error/id
   :seon.fix/id :seon.test/digest :seon.code.doc/id :seon.branch/name
   :seon.signal/kind])

(defn entity-lookup-ref
  "Derives the canonical Datahike lookup ref `[id-attr val]` from pulled entity map `ent`."
  {:malli/schema [:=> [:cat :any] [:maybe [:vector :any]]] :cost/ticks 100000}
  [ent]
  (when (map? ent)
    (or (some #(when-let [v (get ent %)] [% v]) id-attrs)
        (when (integer? (:db/id ent)) [:db/id (:db/id ent)]))))

(defn entity-short-label
  "Formats a crisp human-readable label for a lookup ref `[attr val]` or pulled entity map."
  {:malli/schema [:=> [:cat :any] :string] :cost/ticks 100000}
  [x]
  (let [s8 #(let [s (str %)] (if (> (count s) 8) (subs s 0 8) s))]
    (cond
      (and (vector? x) (= 2 (count x)))
      (let [[a v] x]
        (case a
          :seon.signal/kind (str "Signal · " (str/capitalize (name v)))
          :seon.branch/name (str "Branch · " v)
          :seon.agent.eval/id (str "Eval · " (s8 v))
          :seon.error/id (str "Error · " (s8 v))
          :seon.fix/id (str "Fix · " (s8 v))
          :seon.test/digest (str "Test · " (s8 v))
          (str v)))
      (map? x)
      (or (some-> (:seon.code.var/name x) str) (some-> (:seon.code.ns/name x) str)
          (some-> (:seon.task/title x) not-empty) (some-> (:seon.task/id x) str)
          (some-> (:seon.rule/id x) str) (some-> (:seon.schema/key x) str)
          (some-> (:seon.agent/label x) not-empty) (some-> (:seon.agent/id x) str)
          (some-> (:seon.code.doc/heading x) not-empty) (some-> (:seon.code.doc/id x) str)
          (some-> (:seon.error/kind x) str) (some-> (:seon.fix/error-class x) str)
          (some-> (:seon.test/var x) str) (some-> (:seon.agent.eval/handle x) str)
          (when-let [r (entity-lookup-ref x)] (entity-short-label r))
          "entity")
      :else (str (or x "entity")))))

(def lab-weight-presets
  "Named 4-signal ranking weight presets for the Context & Cache lab."
  {:balanced       {:urgency 1.0  :recency 0.75 :convergence 0.50 :semantic 0.50}
   :urgency        {:urgency 1.5  :recency 0.30 :convergence 0.20 :semantic 0.25}
   :recency        {:urgency 0.30 :recency 1.50 :convergence 0.40 :semantic 0.25}
   :semantic-heavy {:urgency 0.45 :recency 0.35 :convergence 0.25 :semantic 1.50}
   :turn0-cold     {:urgency 1.0  :recency 0.25 :convergence 0.25 :semantic 1.0}})

(defn normalize-lab-prefs
  "Normalizes `:seon.user/lab-prefs` into validated Context Lab & Inspector settings."
  {:malli/schema [:=> [:cat [:maybe :map]] :map] :cost/ticks 200000}
  [raw]
  (let [p (if (map? raw) raw {})
        preset (if (contains? lab-weight-presets (:weight-preset p)) (:weight-preset p) :balanced)
        bw (get lab-weight-presets preset)
        wi (when (map? (:weights p)) (:weights p))
        nf (fn [x d] (if (number? x) (let [v (double x)] (if (and (== v v) (not (Double/isInfinite v))) (max 0.0 (min 5.0 v)) (double d))) (double d)))
        cl (fn [x lo hi d] (if (number? x) (let [v (double x)] (if (and (== v v) (not (Double/isInfinite v))) (max (long lo) (min (long hi) (long (Math/round v)))) (long d))) (long d)))]
    {:target (if (= :html (:target p)) :html :wire)
     :token-budget (cl (:token-budget p) 100 64000 2400)
     :avail-w (cl (:avail-w p) 240 2400 960)
     :strategy (if (= :depth-first (:strategy p)) :depth-first :breadth-first)
     :weight-preset preset
     :weights {:urgency (nf (:urgency wi) (:urgency bw)) :recency (nf (:recency wi) (:recency bw))
               :convergence (nf (or (:convergence wi) (:touches wi)) (:convergence bw))
               :semantic (nf (:semantic wi) (:semantic bw))}
     :disabled-rules (into #{} (filter qualified-keyword?) (:disabled-rules p))
     :inspect-target (if (= :wire (:inspect-target p)) :wire :html)
     :inspect-budget (cl (:inspect-budget p) 100 16000 2400)
     :inspect-width (cl (:inspect-width p) 240 1600 640)
     :inspect-forward (vec (keep normalize-inspect-ref (:inspect-forward p)))}))

(defn entry-agent-id
  "Resolves the user's default/home entry agent purely from Datahike data."
  {:malli/schema [:=> [:cat :any] [:maybe :keyword]] :cost/ticks 5000000}
  [db]
  (let [dh (db-schema db)]
    (when (and db (contains? dh :seon.agent/id))
      (or (when (contains? dh :seon.agent/entry?)
            (->> (d/datoms db :avet :seon.agent/entry? true)
                 (keep #(:seon.agent/id (d/pull db '[:seon.agent/id] (:e %))))
                 (sort-by str) first))
          (->> (d/datoms db :aevt :seon.agent/id)
               (map #(d/pull db '[:seon.agent/id :seon.agent/parent :seon.agent/role] (:e %)))
               (remove #(let [aid (:seon.agent/id %)]
                          (or (nil? aid) (= :repl (:seon.agent/role %))
                              (= "example" (namespace aid)) (some? (:seon.agent/parent %)))))
               (sort-by #(str (:seon.agent/id %))) first :seon.agent/id)))))

(defn agent-id->slug
  {:malli/schema [:=> [:cat [:maybe :keyword]] [:maybe :string]], :cost/ticks 500000, :cost/bytes 1048576}
  [aid]
  (when aid
    (if-let [ns-part (namespace aid)]
      (if (= "agent" ns-part) (name aid) (str ns-part "~" (name aid)))
      (name aid))))

(defn slug->agent-id
  {:malli/schema [:=> [:cat :string] [:maybe :keyword]], :cost/ticks 500000, :cost/bytes 1048576}
  [^String slug]
  (let [s (str/trim (str slug))]
    (cond (str/blank? s) nil
          (str/includes? s "~") (let [[ns-p nm] (str/split s #"~" 2)] (keyword ns-p nm))
          :else (keyword "agent" s))))

(defn agent-href
  {:malli/schema [:=> [:cat [:maybe :keyword]] :string], :cost/ticks 500000, :cost/bytes 1048576}
  [aid] (str "/agent/" (agent-id->slug aid)))

(defn debug-href
  {:malli/schema [:=> [:cat [:maybe :keyword]] :string], :cost/ticks 500000, :cost/bytes 1048576}
  [aid] (str "/agent/" (agent-id->slug aid) "/debug"))

(defn friendly-agent-name [aid]
  (cond (nil? aid) "Agent"
        (= "agent" (namespace aid)) (name aid)
        (namespace aid) (str (namespace aid) "/" (name aid))
        :else (name aid)))

(defn agent-label
  "Returns human display label from `:seon.agent/label` on an agent entity map or DB lookup."
  {:malli/schema [:function [:=> [:cat [:maybe :map]] :string] [:=> [:cat :any [:maybe :keyword]] :string]]
   :cost/ticks 2000000}
  ([ag] (agent-label ag (when (map? ag) (let [id (:seon.agent/id ag)] (when (keyword? id) id)))))
  ([db-or-ag aid]
   (let [lbl (cond
               (and (map? db-or-ag) (contains? db-or-ag :seon.agent/id))
               (let [l (:seon.agent/label db-or-ag)] (when (string? l) l))
               (and db-or-ag aid (contains? (db-schema db-or-ag) :seon.agent/label)
                    (first (d/datoms db-or-ag :avet :seon.agent/id aid)))
               (:seon.agent/label (d/pull db-or-ag '[:seon.agent/label] [:seon.agent/id aid]))
               :else nil)]
     (or (not-empty lbl) (friendly-agent-name aid)))))

(defn router-agent?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean], :cost/ticks 500000, :cost/bytes 1048576}
  [ag]
  (boolean (or (:seon.agent/router? ag) (= :coordinator (:seon.agent/role ag)))))

(defn reads-main?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean], :cost/ticks 500000, :cost/bytes 1048576}
  [ag]
  (boolean (or (= :main (:seon.agent/read-branch ag)) (:seon.agent/router? ag) (= :coordinator (:seon.agent/role ag)))))

(defn user-view-state
  "Reads the user's persistent viewport, inspector stack, and lab prefs (`:user/default`) from Datahike `db`."
  {:malli/schema [:=> [:cat :any] :map], :cost/ticks 5000000}
  [db]
  (let [dh (db-schema db)
        entry-id (entry-agent-id db)
        insp? (every? dh [:seon.user/inspect-ref :seon.user/inspect-trail :seon.user/lab-prefs])
        pat (cond-> '[:seon.user/id :seon.user/view-mode :seon.user/sidebar-open?
                      :seon.user/expanded-agents :seon.user/selected-branch
                      :seon.user/as-of-tx :seon.user/theme :seon.user/viewport-w :seon.user/viewport-h
                      {:seon.user/viewing [:seon.agent/id]}]
              insp? (into [:seon.user/inspect-ref :seon.user/inspect-trail :seon.user/lab-prefs]))
        u (when (and db (contains? dh :seon.user/id) (first (d/datoms db :avet :seon.user/id :user/default)))
            (d/pull db pat [:seon.user/id :user/default]))
        viewing-id (or (get-in u [:seon.user/viewing :seon.agent/id]) entry-id)
        raw-lp (when (map? (:seon.user/lab-prefs u)) (:seon.user/lab-prefs u))]
    {:user-id (or (:seon.user/id u) :user/default)
     :entry-id entry-id
     :viewing-id viewing-id
     :view-mode (or (:seon.user/view-mode u) :session)
     :sidebar-open? (boolean (:seon.user/sidebar-open? u))
     :expanded-agents (cond-> (set (:seon.user/expanded-agents u)) entry-id (conj entry-id))
     :selected-branch (:seon.user/selected-branch u)
     :as-of-tx (:seon.user/as-of-tx u)
     :theme (or (:seon.user/theme u) :auto)
     :viewport-w (or (:seon.user/viewport-w u) 1280)
     :viewport-h (or (:seon.user/viewport-h u) 820)
     :inspect-ref (normalize-inspect-ref (:seon.user/inspect-ref u))
     :inspect-trail (vec (keep normalize-inspect-ref (:seon.user/inspect-trail u)))
     :inspect-forward (vec (keep normalize-inspect-ref (:inspect-forward raw-lp)))
     :lab-prefs (or raw-lp {})}))

(defn branch-db-for [main-db conn branch]
  (if (or (nil? branch) (= branch :main) (nil? conn))
    main-db
    (or (try (store/head conn branch) (catch Throwable _ nil)) main-db)))

(defn latest-attr-val [main-db branch-db ma ba attr]
  (let [mt (or (:tx (first (d/datoms main-db :eavt (:db/id ma) attr))) 0)
        bt (or (:tx (first (d/datoms branch-db :eavt (:db/id ba) attr))) 0)]
    (if (> bt mt) (get ba attr) (get ma attr))))

(defn pull-agent-entity
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] :map]}
  [main-db branch-db agent-id]
  (let [ma (when (and main-db agent-id (first (d/datoms main-db :avet :seon.agent/id agent-id)))
             (d/pull main-db '[*] [:seon.agent/id agent-id]))
        ba (when (and branch-db agent-id (not= branch-db main-db)
                      (first (d/datoms branch-db :avet :seon.agent/id agent-id)))
             (d/pull branch-db '[*] [:seon.agent/id agent-id]))
        base (merge {:seon.agent/id agent-id :seon.agent/branch :main :seon.agent/role :worker :seon.agent/pinned []} ma ba)]
    (if (and ma ba (not= branch-db main-db))
      (assoc base
             :seon.agent/pinned (or (latest-attr-val main-db branch-db ma ba :seon.agent/pinned) (:seon.agent/pinned base))
             :seon.agent/memory (or (latest-attr-val main-db branch-db ma ba :seon.agent/memory) (:seon.agent/memory base))
             :seon.agent/context-prefs (or (latest-attr-val main-db branch-db ma ba :seon.agent/context-prefs) (:seon.agent/context-prefs base)))
      base)))

(defn attr-tx-in
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword] :keyword] [:maybe :int]] :cost/ticks 2000000}
  [db agent-id attr]
  (when (and db agent-id (contains? (db-schema db) attr))
    (when-let [eid (:db/id (d/pull db '[:db/id] [:seon.agent/id agent-id]))]
      (reduce max 0 (map :tx (d/datoms db :eavt eid attr))))))

(defonce ^:private ^:seon.flow/edge view-history-cache (store/lru-cache 32))

(def ^:private agent-control-attrs
  #{:seon.agent/id :seon.agent/branch :seon.agent/role :seon.agent/label :seon.agent/entry?
    :seon.agent/parent :seon.agent/waiting? :seon.agent/paused? :seon.agent/ready?
    :seon.agent/awaiting :seon.agent/archived? :seon.agent/auto-merge? :seon.agent/read-branch
    :seon.agent/router? :seon.agent/tested-tx :seon.agent/merged-tx :seon.agent/last-proofs
    :seon.agent/hero-title :seon.agent/system-prompt :seon.agent/scope :seon.agent/focus})

(defn- resolve-ref-eid [bdb mdb x]
  (cond
    (integer? x) x
    (and (vector? x) (= 2 (count x)) (qualified-keyword? (first x)))
    (let [[a v] x]
      (or (when (and bdb (contains? (db-schema bdb) a)) (:e (first (d/datoms bdb :avet a v))))
          (when (and mdb (not= mdb bdb) (contains? (db-schema mdb) a)) (:e (first (d/datoms mdb :avet a v))))))
    :else nil))

(defn- unpack-refs [v]
  (cond (and (vector? v) (= 2 (count v)) (qualified-keyword? (first v))) [v]
        (coll? v) v
        (some? v) [v]
        :else []))

(defn- linked-agent-eids [bdb mdb hdb hmdb eid meid agent-id]
  (let [bs (db-schema bdb) ms (db-schema mdb)
        raw-refs (concat
                  (when (and eid (bs :seon.agent/task)) (map #(vector :seon.task/id (:v %)) (d/datoms bdb :eavt eid :seon.agent/task)))
                  (when (and meid (ms :seon.agent/task)) (map #(vector :seon.task/id (:v %)) (d/datoms mdb :eavt meid :seon.agent/task)))
                  (when (and eid (bs :seon.agent/pinned)) (mapcat #(unpack-refs (:v %)) (filter :added (d/datoms hdb :eavt eid :seon.agent/pinned))))
                  (when (and meid (ms :seon.agent/pinned)) (mapcat #(unpack-refs (:v %)) (filter :added (d/datoms hmdb :eavt meid :seon.agent/pinned))))
                  (when (and eid (bs :seon.agent/curated)) (mapcat #(unpack-refs (:v %)) (filter :added (d/datoms hdb :eavt eid :seon.agent/curated)))))
        err-eids (concat
                  (when (bs :seon.error/agent) (map :e (d/datoms bdb :avet :seon.error/agent agent-id)))
                  (when (and (not= mdb bdb) (ms :seon.error/agent)) (map :e (d/datoms mdb :avet :seon.error/agent agent-id))))]
    (into (set err-eids) (keep #(resolve-ref-eid bdb mdb %)) raw-refs)))

(defn- entity-mutation-txs [hdb hmdb eids]
  (mapcat (fn [e]
            (concat (keep #(when (:added %) (:tx %)) (d/datoms hdb :eavt e))
                    (when-not (identical? hmdb hdb) (keep #(when (:added %) (:tx %)) (d/datoms hmdb :eavt e)))))
          (take 32 eids)))

(defn view-history-txs
  "Generic entity-derived `d/as-of` history across projected entities, agent-linked entities, messages, and replies."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:maybe [:vector :int]]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:maybe [:vector :int]]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword] [:maybe [:sequential :any]]] [:maybe [:vector :int]]]]
   :cost/ticks 15000000}
  ([bdb agent-id] (view-history-txs bdb bdb agent-id nil))
  ([bdb main-db agent-id] (view-history-txs bdb main-db agent-id nil))
  ([bdb main-db agent-id entity-ids]
   (when (and bdb agent-id)
     (let [mdb (or main-db bdb)
           eff-eids (vec (distinct (keep #(resolve-ref-eid bdb mdb %) entity-ids)))
           ck (when (and (not (instance? datahike.db.AsOfDB bdb)) (not (instance? datahike.db.HistoricalDB bdb))
                         (not (instance? datahike.db.AsOfDB mdb)) (not (instance? datahike.db.HistoricalDB mdb)))
                [(get-in bdb [:config :branch] :main) (:max-tx bdb) (:max-tx mdb) agent-id eff-eids])
           compute
           (fn []
             (let [bs (db-schema bdb) ms (db-schema mdb)
                   hdb (d/history bdb) hmdb (if (identical? mdb bdb) hdb (d/history mdb))
                   eid (when (bs :seon.agent/id) (:e (first (d/datoms bdb :avet :seon.agent/id agent-id))))
                   meid (if (identical? mdb bdb) eid (when (ms :seon.agent/id) (:e (first (d/datoms mdb :avet :seon.agent/id agent-id)))))
                   ag-txs (concat
                           (when eid (keep #(when (and (:added %) (not (agent-control-attrs (:a %)))) (:tx %)) (d/datoms hdb :eavt eid)))
                           (when (and meid (not (identical? hmdb hdb)))
                             (keep #(when (and (:added %) (not (agent-control-attrs (:a %)))) (:tx %)) (d/datoms hmdb :eavt meid))))
                   all-eids (into (set eff-eids) (linked-agent-eids bdb mdb hdb hmdb eid meid agent-id))
                   ent-txs (entity-mutation-txs hdb hmdb all-eids)
                   msg-txs (when (ms :seon.agent.message/to)
                             (for [dt (d/datoms hmdb :avet :seon.agent.message/to agent-id) :when (:added dt)
                                   :let [m (d/pull mdb '[:seon.agent.message/type :seon.agent.message/from] (:e dt))]
                                   :when (not (#{:nudge :system} (:seon.agent.message/type m)))]
                               (:tx dt)))
                   reply-txs (keep #(when (and (str/blank? (str (:seon.agent.eval/code %)))
                                               (not (str/blank? (str (:seon.agent.eval/thought %)))))
                                      (:seon.agent.eval/tx %))
                                   (sort-by :seon.agent.eval/tx (store/agent-historical-evals bdb agent-id)))]
               (->> (concat ag-txs ent-txs msg-txs reply-txs) (filter integer?) distinct sort vec)))]
       (if ck (store/lru-get! view-history-cache 32 ck compute) (compute))))))

(defn canvas-history-txs
  "Delegates to generic entity-derived `view-history-txs` for `d/as-of` rewind across all views."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:maybe [:vector :int]]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword]] [:maybe [:vector :int]]]]
   :cost/ticks 15000000}
  ([bdb agent-id] (view-history-txs bdb bdb agent-id nil))
  ([bdb main-db agent-id] (view-history-txs bdb main-db agent-id nil)))

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
                                  :seon.agent.eval/prefix-hash :seon.agent.eval/prompt-tokens :seon.agent.eval/completion-tokens
                                  :seon.agent.eval/cache-hit-tokens :seon.agent.eval/cache-miss-tokens
                                  :seon.agent.eval/seen-messages] (:e dt))
                     :seon.agent.eval/tx (:tx dt) :seon.agent.eval/agent agent-id)))
       (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0)))
       vec))

(defn agent-messages-in [main-db agent-id]
  (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
       (map (fn [dt]
              (assoc (d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq :seon.agent.message/from
                                       :seon.agent.message/to :seon.agent.message/type :seon.agent.message/body] (:e dt))
                     :seon.agent.message/tx (:tx dt))))
       (sort-by :seon.agent.message/seq) vec))

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
                                  (->> seen-ids (keep #(get msgs-by-id %)) (sort-by :seon.agent.message/seq) vec))]
                  (cond-> {:turn turn :thought (:seon.agent.eval/thought first-e) :reasoning (:seon.agent.eval/reasoning first-e)
                           :calls (mapv (fn [e]
                                          {:id (or (:seon.agent.eval/call-id e) (str "t" turn "-c" (or (:seon.agent.eval/call-index e) 0)))
                                           :handle (:seon.agent.eval/handle e) :result-schema (:seon.agent.eval/result-schema e)
                                           :intent (:seon.agent.eval/intent e) :code (:seon.agent.eval/code e)
                                           :value (:seon.agent.eval/value e) :out (:seon.agent.eval/out e)
                                           :error (:seon.agent.eval/error e) :ms (:seon.agent.eval/ms e) :ticks (:seon.agent.eval/ticks e)})
                                        ordered)}
                    (seq turn-msgs) (assoc :messages turn-msgs))))))))

(defn first-doc-line
  {:malli/schema [:=> [:cat [:maybe :string] [:int {:min 0, :max 500}]] [:maybe :string]], :cost/ticks 500000, :cost/bytes 1048576}
  [^String s max-len]
  (when-not (str/blank? (str s))
    (let [line (-> (str s) str/trim str/split-lines first str/trim)]
      (if (> (count line) max-len) (str (subs line 0 max-len) "…") line))))

(defn compact-entity-preview [m max-len]
  (let [cleaned (into {} (keep (fn [[k v]]
                                 (when (and (not= k :db/id) (some? v))
                                   (let [vs (pr-str v)] [k (if (> (count vs) 60) (str (subs vs 0 60) "…") v)]))))
                      (take 6 (sort-by (comp str key) m)))
        s (pr-str cleaned)]
    (if (> (count s) max-len) (str (subs s 0 max-len) "…") s)))

(defn- branch-test-summary [main-db branch]
  (when (and main-db branch ((db-schema main-db) :seon.test.run/branch))
    (when-let [dt (first (d/datoms main-db :avet :seon.test.run/branch branch))]
      (let [r (d/pull main-db '[:seon.test.run/pass? :seon.test.run/ran :seon.test.run/cached :seon.test.run/failed-digests] (:e dt))
            failed (count (:seon.test.run/failed-digests r))
            total (+ (long (or (:seon.test.run/ran r) 0)) (long (or (:seon.test.run/cached r) 0)))]
        {:status (cond (:seon.test.run/pass? r) :pass (pos? failed) :fail :else :unrun) :total total :failed failed}))))

(defn agent-branch-progress
  "Derives universal merge-readiness facts (evals, latest intent, tests, ready/merge state) for agent `aid`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/conn] [:maybe :keyword]] :map]}
  [main-db conn aid]
  (let [ag (when (and main-db aid ((db-schema main-db) :seon.agent/id))
             (d/pull main-db '[:seon.agent/id :seon.agent/branch :seon.agent/scope :seon.agent/parent
                               :seon.agent/ready? :seon.agent/tested-tx :seon.agent/merged-tx] [:seon.agent/id aid]))
        br (:seon.agent/branch ag)
        bdb (when (and conn br (not= br :main)) (try (store/head conn br) (catch Throwable _ nil)))
        eval-db (or bdb main-db)
        eval-dts (when (and eval-db aid ((db-schema eval-db) :seon.agent.eval/agent))
                   (vec (d/datoms eval-db :avet :seon.agent.eval/agent aid)))
        eval-count (count eval-dts)
        last-dt (when (seq eval-dts) (reduce #(if (>= (:tx %2) (:tx %1)) %2 %1) eval-dts))
        last-ev (when last-dt (d/pull eval-db '[:seon.agent.eval/intent :seon.agent.eval/thought :seon.agent.eval/error :seon.agent.eval/tx] (:e last-dt)))
        last-intent (or (not-empty (str/trim (str (or (:seon.agent.eval/intent last-ev) ""))))
                        (not-empty (str/trim (str (or (:seon.agent.eval/thought last-ev) "")))))
        last-err (not-empty (str/trim (str (or (:seon.agent.eval/error last-ev) ""))))
        test-sum (when br (branch-test-summary main-db br))
        b-max-tx (long (or (:max-tx bdb) (:seon.agent.eval/tx last-ev) 0))
        merged-tx (long (or (:seon.agent/merged-tx ag) 0))
        ready? (boolean (:seon.agent/ready? ag))
        merged? (and (pos? merged-tx) (or (zero? b-max-tx) (>= merged-tx b-max-tx)))
        stage (cond merged? :merged (= :fail (:status test-sum)) :tests-red
                    (and ready? (= :pass (:status test-sum))) :ready-to-merge
                    ready? :testing (pos? eval-count) :working :else :starting)]
    {:agent-id aid :branch br :parent (:seon.agent/parent ag) :scope (:seon.agent/scope ag)
     :evals eval-count :latest-intent (when last-intent (first-doc-line last-intent 84))
     :last-error (when last-err (first-doc-line last-err 84))
     :tests (or test-sum {:status :unrun :total 0 :failed 0}) :ready? ready? :merged? merged? :stage stage}))

(defn parent-request-data
  "Pulls what `agent-id`'s optional parent requested: `:parent-id`, assigned `:task`, and incoming calls."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] [:maybe :map]]}
  [main-db agent-id]
  (let [dh (db-schema main-db)]
    (when (and main-db agent-id (dh :seon.agent/id))
      (let [ag (d/pull main-db '[:seon.agent/parent :seon.agent/task :seon.agent/goal] [:seon.agent/id agent-id])
            parent-id (:seon.agent/parent ag)
            tid (:seon.agent/task ag)
            task-ent (when (and tid (dh :seon.task/id))
                       (d/pull main-db '[:seon.task/id :seon.task/title :seon.task/plan :seon.task/scope :seon.task/done?] [:seon.task/id tid]))
            incoming (when (dh :seon.agent.call/callee)
                       (let [all (->> (d/datoms main-db :avet :seon.agent.call/callee agent-id)
                                      (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/status
                                                              :seon.agent.call/prompt :seon.agent.call/result-schema :seon.agent.call/created-tx] (:e %)))
                                      (sort-by #(or (:seon.agent.call/created-tx %) 0)) vec)
                             pend (filterv #(= :pending (:seon.agent.call/status %)) all)]
                         (if (seq pend) pend (vec (take-last 2 all)))))]
        (when (or parent-id (:seon.task/id task-ent) (seq incoming))
          {:parent-id (or parent-id (:seon.agent.call/caller (first incoming)))
           :task (when (:seon.task/id task-ent) task-ent) :incoming (vec incoming)})))))

(defn delegation-progress-tree
  "Builds a recursive work-progress tree for `agent-id` from outgoing calls and `:seon.agent/parent` child agents."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/conn] [:maybe :keyword]] [:maybe [:vector :map]]]}
  [main-db conn agent-id]
  (let [dh (db-schema main-db)]
    (when (and main-db agent-id)
      (let [direct-calls (if (dh :seon.agent.call/caller)
                           (->> (d/datoms main-db :avet :seon.agent.call/caller agent-id)
                                (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/callee
                                                        :seon.agent.call/parent-call :seon.agent.call/status :seon.agent.call/prompt
                                                        :seon.agent.call/created-tx] (:e %)))
                                (sort-by #(or (:seon.agent.call/created-tx %) 0)) vec)
                           [])
            pending-calls (filterv #(= :pending (:seon.agent.call/status %)) direct-calls)
            call-roots (if (seq pending-calls)
                         pending-calls
                         (when (seq direct-calls)
                           (let [ltx (or (:seon.agent.call/created-tx (peek direct-calls)) 0)]
                             (filterv #(>= (or (:seon.agent.call/created-tx %) 0) (- ltx 8)) direct-calls))))
            seen-callees (into #{} (keep :seon.agent.call/callee) call-roots)
            parent-children (when (dh :seon.agent/parent)
                              (->> (filter #(= agent-id (:v %)) (d/datoms main-db :aevt :seon.agent/parent))
                                   (keep (fn [dt]
                                           (let [c (d/pull main-db '[:seon.agent/id :seon.agent/goal :seon.agent/archived?] (:e dt))
                                                 cid (:seon.agent/id c)]
                                             (when (and cid (not (:seon.agent/archived? c)) (not (seen-callees cid)))
                                               {:seon.agent.call/callee cid :seon.agent.call/status :child
                                                :seon.agent.call/prompt (:seon.agent/goal c "")})))) vec))
            has-pidx? (dh :seon.agent.call/parent-call)
            enrich (fn enrich [c depth]
                     (let [callee (:seon.agent.call/callee c)
                           prog (agent-branch-progress main-db conn callee)
                           kids (when (and has-pidx? (:seon.agent.call/id c) (< depth 4))
                                  (->> (d/datoms main-db :avet :seon.agent.call/parent-call (:seon.agent.call/id c))
                                       (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/callee
                                                               :seon.agent.call/parent-call :seon.agent.call/status :seon.agent.call/prompt
                                                               :seon.agent.call/created-tx] (:e %)))
                                       (sort-by #(or (:seon.agent.call/created-tx %) 0))
                                       (mapv #(enrich % (inc depth)))))]
                       (cond-> (assoc c :progress prog) (seq kids) (assoc :children kids))))
            all-roots (into (vec (take-last 8 call-roots)) (take 6 parent-children))]
        (when (seq all-roots) (mapv #(enrich % 0) all-roots))))))
