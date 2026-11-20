(ns seon.render.chronicle
  "Time- and Datahike-tx-anchored Chronicle summary stream, tx-window distillation, compaction timeline builder, freshness triggers, and Living Canvas data engine."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [seon.health :as health]
            [seon.health.metrics :as hmetrics]))

(def ^:private utc-formatter
  (.withZone (java.time.format.DateTimeFormatter/ofPattern "MMM dd · HH:mm 'UTC'" java.util.Locale/ENGLISH)
             java.time.ZoneOffset/UTC))

(defn format-epoch-utc
  "Formats epoch ms into `MMM dd · HH:mm UTC`, or `\"\"` when nil/out-of-range."
  {:malli/schema [:=> [:cat [:maybe [:int {:min 0 :max 4102444800000}]]] :string]
   :cost/ticks 200000 :cost/bytes 262144}
  [ms]
  (if (and (integer? ms) (pos? (long ms)) (<= (long ms) 4102444800000))
    (.format ^java.time.format.DateTimeFormatter utc-formatter (java.time.Instant/ofEpochMilli (long ms)))
    ""))

(defn tx-range-label
  "Formats a Datahike tx range as `tx A..B` (or `tx B` for a single tx)."
  {:malli/schema [:=> [:cat [:maybe :int] [:maybe :int]] :string]
   :cost/ticks 100000 :cost/bytes 131072}
  [from-tx to-tx]
  (let [a (when (integer? from-tx) (long from-tx))
        b (when (integer? to-tx) (long to-tx))]
    (cond
      (and a b (pos? a) (pos? b) (not= a b)) (str "tx " (min a b) ".." (max a b))
      (and b (pos? b)) (str "tx " b)
      (and a (pos? a)) (str "tx " a)
      :else "")))

(defn record-summary-tx
  "Pure builder returning `[entity-map]` for `:seon.summary/entity` with deterministic UUID and zero nils."
  {:malli/schema [:=> [:cat :map] [:vector :map]]
   :cost/ticks 500000 :cost/bytes 1048576}
  [spec]
  (let [g (fn [k] (or (get spec (keyword "seon.summary" (name k))) (get spec k)))
        kind (let [k (g :kind)] (if (keyword? k) k :system-pulse))
        title (let [s (g :title)] (if (and (string? s) (not (str/blank? s))) (str/trim s) "System Summary"))
        body (let [b (g :body)] (if (string? b) (str/trim b) ""))
        ft (let [x (g :from-tx)] (long (if (integer? x) x (if (integer? (g :to-tx)) (g :to-tx) 0))))
        tt (let [x (g :to-tx)] (long (if (integer? x) x ft)))
        lo (min ft tt) hi (max ft tt)
        ms (long (let [x (g :created-ms)] (if (integer? x) x 0)))
        ag (let [x (g :agent)] (if (keyword? x) x :agent/root))
        id (let [x (g :id)] (if (uuid? x) x (hasch/uuid [:seon.summary kind ag lo hi title])))
        refs (when-let [r (g :refs)] (when (sequential? r) (vec (remove nil? r))))
        vis (g :visual)]
    [(cond-> {:seon.summary/id id :seon.summary/kind kind :seon.summary/title title
              :seon.summary/body body :seon.summary/from-tx lo :seon.summary/to-tx hi
              :seon.summary/created-ms ms :seon.summary/agent ag}
       (seq refs) (assoc :seon.summary/refs refs)
       (some? vis) (assoc :seon.summary/visual vis))]))

(defn chronicle-due?
  "Event-driven trigger predicate: wakes summarization on health transitions, merge batches, or broad refactors; never fires idle."
  {:malli/schema [:=> [:cat :map [:? :map]] :map]
   :cost/ticks 200000 :cost/bytes 262144}
  ([activity] (chronicle-due? activity {}))
  ([activity thresholds]
   (let [t (if (map? thresholds) thresholds {})
         i (fn [m k d] (let [v (get m k)] (if (integer? v) (long v) d)))
         merges (max 0 (i activity :merges-since 0))
         nss (max 0 (i activity :namespaces-since 0))
         span (max 0 (i activity :tx-span 0))
         hch? (boolean (:health-changed? activity))
         has? (if (contains? activity :has-summary?) (boolean (:has-summary? activity)) true)
         moved? (or (pos? merges) (pos? nss) hch?)]
     (cond
       hch? {:due? true :reason :health-transition :urgency :high}
       (and (not has?) moved?) {:due? true :reason :initial :urgency :normal}
       (>= merges (max 1 (i t :min-merges 3))) {:due? true :reason :merge-batch :urgency :normal}
       (>= nss (max 1 (i t :min-namespaces 4))) {:due? true :reason :broad-refactor :urgency :normal}
       (and (>= span (max 1 (i t :min-tx-span 400))) moved?) {:due? true :reason :tx-milestone :urgency :normal}
       :else {:due? false :reason nil :urgency :none}))))

(defn- clip-str [s max-n]
  (let [t (str/replace (str/trim (str (or s ""))) #"\s+" " ")]
    (if (<= (count t) max-n) t (str (subs t 0 (max 0 (- max-n 1))) "…"))))

(defn- friendly-kw [k]
  (cond
    (keyword? k) (if-let [ns-p (namespace k)] (if (= "agent" ns-p) (name k) (str ns-p "/" (name k))) (name k))
    :else (str k)))

(defn synthesize-summary
  "Pure synthesizer turning a `distill-tx-window` snapshot into a concise `:seon.summary/entity` map."
  {:malli/schema [:=> [:cat :map [:? :map]] :map]
   :cost/ticks 1500000 :cost/bytes 3145728}
  ([distilled] (synthesize-summary distilled {}))
  ([distilled opts]
   (let [d (if (map? distilled) distilled {})
         o (if (map? opts) opts {})
         merges (if (sequential? (:merges d)) (vec (filter map? (:merges d))) [])
         nss (if (sequential? (:touched-nss d)) (vec (filter map? (:touched-nss d))) [])
         schemas (if (sequential? (:touched-schemas d)) (vec (:touched-schemas d)) [])
         h (if (map? (:health d)) (:health d) {:ok? true :errors 0 :warnings 0})
         lm (when (map? (:learning-metrics d)) (:learning-metrics d))
         top-ns (mapv #(str (:ns %)) (take 3 (filter :ns nss)))
         mc (count merges)
         fc (long (if (integer? (:touched-forms d)) (:touched-forms d) 0))
         title (or (when (and (string? (:title o)) (not (str/blank? (:title o)))) (str/trim (:title o)))
                   (cond
                     (and (pos? mc) (seq top-ns)) (str "System Pulse · " mc " merge" (when (not= 1 mc) "s") " across " (str/join ", " top-ns))
                     (pos? mc) (str "System Pulse · " mc " recent merge" (when (not= 1 mc) "s"))
                     (seq top-ns) (str "Codebase Activity · " fc " form" (when (not= 1 fc) "s") " in " (str/join ", " top-ns))
                     (false? (:ok? h)) (str "Health Attention · " (:errors h 0) " error(s)")
                     :else "System Pulse · Quiet & Healthy"))
         mlines (mapv (fn [m]
                        (let [tx (or (:main-tx m) (:merged-tx m)) g (clip-str (:goal m) 104)]
                          (str "- **" (friendly-kw (:id m)) "**" (when (integer? tx) (str " (`tx " tx "`)"))
                               (when-not (str/blank? g) (str ": " g)))))
                      (take 3 merges))
         nline (when (seq nss)
                 (str "- **Subsystems:** " (str/join ", " (map #(str "`" (:ns %) "` (" (:forms % 0) ")") (take 5 nss)))
                      (when (> (count nss) 5) (str " +" (- (count nss) 5) " more"))
                      (when (seq schemas) (str " · " (count schemas) " schema(s)"))))
         hline (str "- **Health:** " (if (:ok? h true) "Healthy" (str (:errors h 0) " error(s)"))
                    (when (pos? (long (if (integer? (:warnings h)) (:warnings h) 0))) (str " (" (:warnings h) " advisories)"))
                    (when (and lm (integer? (:merged lm)) (integer? (:attempted lm)) (pos? (:attempted lm)))
                      (str " · **Velocity:** " (:merged lm) "/" (:attempted lm) " merged"
                           (when (integer? (:kv-cache-hit-pct lm)) (str ", " (:kv-cache-hit-pct lm) "% KV-cache")))))
         body (or (when (and (string? (:body o)) (not (str/blank? (:body o)))) (:body o))
                  (str/join "\n" (remove nil? (concat mlines [nline hline]))))]
     (first (record-summary-tx
             {:id (:id o) :kind (or (:kind o) :system-pulse) :title title :body body
              :from-tx (or (:from-tx o) (:from-tx d) 0) :to-tx (or (:to-tx o) (:to-tx d) 0)
              :created-ms (or (:created-ms o) (:to-ms d) 0) :agent (or (:agent o) :agent/root)
              :refs (if (sequential? (:refs o)) (:refs o) (:refs d)) :visual (:visual o)})))))

(defn format-compaction-timeline
  "Token-budgeted chronological formatter for agent compaction memory preserving exact `tx A..B` and `d/as-of` pointers."
  {:malli/schema [:=> [:cat [:sequential :map] [:? :map]] :map]
   :cost/ticks 2500000 :cost/bytes 4194304}
  ([entries] (format-compaction-timeline entries {}))
  ([entries opts]
   (let [o (if (map? opts) opts {})
         max-ch (max 160 (long (if (integer? (:max-chars o)) (:max-chars o) 2400)))
         det-n (max 1 (long (if (integer? (:detail-count o)) (:detail-count o) 3)))
         tail (when (and (string? (:tail-note o)) (not (str/blank? (:tail-note o)))) (str "- " (str/trim (:tail-note o))))
         num (fn [e k] (let [v (get e k)] (if (integer? v) (long v) 0)))
         sorted (vec (sort-by #(vector (num % :seon.summary/from-tx) (num % :seon.summary/to-tx) (num % :seon.summary/created-ms))
                              (filter map? entries)))
         n (count sorted)
         items (mapv (fn [idx]
                       (let [e (nth sorted idx)
                             det? (>= idx (- n det-n))
                             ts (format-epoch-utc (when (integer? (:seon.summary/created-ms e)) (:seon.summary/created-ms e)))
                             txs (tx-range-label (:seon.summary/from-tx e) (:seon.summary/to-tx e))
                             knd (if (keyword? (:seon.summary/kind e)) (name (:seon.summary/kind e)) "pulse")
                             ttl (clip-str (or (:seon.summary/title e) "Summary") 84)
                             rfs (when (sequential? (:seon.summary/refs e)) (take 4 (:seon.summary/refs e)))
                             bdy (when det? (clip-str (:seon.summary/body e) 190))]
                         {:entry e
                          :line (str "- [" (str/join " · " (remove str/blank? [ts txs knd])) "] " ttl
                                     (when (seq rfs) (str " (" (str/join ", " (map friendly-kw rfs)) ")"))
                                     (when (and det? (not (str/blank? bdy))) (str "\n  " bdy)))}))
                     (range n))
         hdr "Chronicle timeline (drill in via `(d/as-of (user/main-db) <to-tx>)`):"
         budget (max 64 (- max-ch (+ (count hdr) 1 (if tail (+ 1 (count tail)) 0))))
         [kept trunc?] (loop [i (dec n) acc [] used 0]
                         (if (< i 0)
                           [(vec (reverse acc)) false]
                           (let [it (nth items i) l (+ (count (:line it)) (if (seq acc) 1 0))]
                             (if (or (empty? acc) (<= (+ used l) budget))
                               (recur (dec i) (conj acc it) (+ used l))
                               [(vec (reverse acc)) true]))))
         ke (mapv :entry kept)
         raw (str/join "\n" (cond-> [hdr]
                              trunc? (conj (str "- … (" (- n (count kept)) " earlier entries omitted; query `d/history`)"))
                              (seq kept) (into (map :line kept))
                              (and (empty? kept) (nil? tail)) (conj "- (no recorded summaries yet)")
                              tail (conj tail)))
         txt (if (> (count raw) max-ch) (str (subs raw 0 (dec max-ch)) "…") raw)
         txs (keep #(when (integer? %) (long %)) (concat (map :seon.summary/from-tx ke) (map :seon.summary/to-tx ke)))]
     {:text txt :entries ke :from-tx (if (seq txs) (apply min txs) 0) :to-tx (if (seq txs) (apply max txs) 0)
      :truncated? (boolean (or trunc? (> (count raw) max-ch))) :chars (count txt)})))

(defn merge-chronicle-entries
  "Unifies recorded `:seon.summary/*` entries with un-summarized recent merges (newest first, zero duplicates)."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] [:vector :map]]
   :cost/ticks 1500000 :cost/bytes 2097152}
  [summaries recent-merges]
  (let [s-list (vec (filter map? summaries))
        cov? (fn [tx] (boolean (and (integer? tx)
                                    (some (fn [s] (let [a (:seon.summary/from-tx s) b (:seon.summary/to-tx s)]
                                                    (and (integer? a) (integer? b) (<= (long a) (long tx) (long b)))))
                                          s-list))))
        se (mapv (fn [s]
                   {:entry-kind (if (keyword? (:seon.summary/kind s)) (:seon.summary/kind s) :system-pulse)
                    :source :summary :id (:seon.summary/id s) :title (or (:seon.summary/title s) "Summary")
                    :body (or (:seon.summary/body s) "")
                    :from-tx (long (if (integer? (:seon.summary/from-tx s)) (:seon.summary/from-tx s) 0))
                    :to-tx (long (if (integer? (:seon.summary/to-tx s)) (:seon.summary/to-tx s) 0))
                    :ms (long (if (integer? (:seon.summary/created-ms s)) (:seon.summary/created-ms s) 0))
                    :agent (when (keyword? (:seon.summary/agent s)) (:seon.summary/agent s))
                    :refs (if (sequential? (:seon.summary/refs s)) (vec (:seon.summary/refs s)) [])
                    :visual (:seon.summary/visual s)})
                 s-list)
        me (keep (fn [m]
                   (let [mtx (or (:main-tx m) (:merged-tx m))]
                     (when (and (map? m) (not (cov? mtx)))
                       (let [aid (:id m) tx (long (if (integer? mtx) mtx 0))]
                         {:entry-kind :merge :source :merge :id (or aid tx)
                          :title (friendly-kw (or aid :branch/merged)) :body (str (or (:goal m) ""))
                          :from-tx tx :to-tx tx :ms (long (if (integer? (:ms m)) (:ms m) 0))
                          :agent (when (keyword? aid) aid) :refs (vec (remove nil? [aid (:scope m)]))}))))
                 recent-merges)]
    (vec (sort-by #(vector (- (long (:to-tx % 0))) (- (long (:ms % 0))) (if (= :summary (:source %)) 0 1))
                  (concat se me)))))

(defn tx-instant-ms
  "Returns epoch milliseconds for Datahike transaction id `tx` via `:db/txInstant`, or `nil`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :int]] [:maybe :int]]}
  [db tx]
  (when (and db (integer? tx) (pos? (long tx)))
    (when-let [inst (:db/txInstant (try (d/pull db '[:db/txInstant] (long tx)) (catch Throwable _ nil)))]
      (when (instance? java.util.Date inst)
        (.getTime ^java.util.Date inst)))))

(defn latest-summaries
  "Returns recorded `:seon.summary/*` entities from `db` sorted newest-first (`to-tx` desc).
   Supports `{:limit 12 :kind kw-or-set :agent kw :since-tx int :until-tx int}`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db :map] [:vector :map]]]}
  ([db] (latest-summaries db {}))
  ([db {:keys [limit kind agent since-tx until-tx] :or {limit 12}}]
   (if-not (and db (contains? (:schema db) :seon.summary/id) (contains? (:schema db) :seon.summary/to-tx))
     []
     (let [lim (max 1 (long (or limit 12)))
           kind-set (cond (set? kind) kind (keyword? kind) #{kind} :else nil)
           cands (->> (d/datoms db :aevt :seon.summary/to-tx)
                      (filter (fn [dt]
                                (let [v (long (:v dt))]
                                  (and (or (nil? since-tx) (> v (long since-tx)))
                                       (or (nil? until-tx) (<= v (long until-tx)))))))
                      (sort-by (fn [dt] [(- (long (:v dt))) (- (long (:tx dt)))])))]
       (->> cands
            (map #(dissoc (d/pull db '[*] (:e %)) :db/id))
            (filter :seon.summary/id)
            (filter #(or (nil? kind-set) (contains? kind-set (:seon.summary/kind %))))
            (filter #(or (nil? agent) (= agent (:seon.summary/agent %))))
            (sort-by (fn [s] [(- (long (or (:seon.summary/to-tx s) 0)))
                              (- (long (or (:seon.summary/created-ms s) 0)))]))
            (take lim)
            vec)))))

(defn- health-counts [db]
  (let [rep (try (health/report db) (catch Throwable _ {:ok? true :problems []}))
        probs (:problems rep)
        errs (count (filter #(= :error (:severity %)) probs))]
    {:ok? (boolean (:ok? rep)) :errors errs :warnings (- (count probs) errs)}))

(defn- merged-agents-in-range [db lo hi limit]
  (if-not (and db (contains? (:schema db) :seon.agent/merged-tx))
    []
    (->> (d/datoms db :aevt :seon.agent/merged-tx)
         (filter (fn [dt]
                   (let [t (long (:tx dt))]
                     (and (> t lo) (<= t hi)))))
         (sort-by (comp - :tx))
         (take limit)
         (mapv (fn [dt]
                 (let [p (d/pull db '[:seon.agent/id :seon.agent/branch :seon.agent/scope
                                      :seon.agent/goal :seon.agent/task] (:e dt))
                       mtx (long (:tx dt))]
                   {:id (:seon.agent/id p)
                    :branch (:seon.agent/branch p)
                    :scope (:seon.agent/scope p)
                    :goal (:seon.agent/goal p)
                    :task (:seon.agent/task p)
                    :branch-tx (long (:v dt))
                    :main-tx mtx
                    :ms (tx-instant-ms db mtx)}))))))

(defn distill-tx-window
  "Single-pass O(F + A) Datahike distillation over `(from-tx .. to-tx]`: merges, touched namespaces/schemas, health, and metrics."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] :map]
                  [:=> [:cat :seon.host/db :map] :map]
                  [:=> [:cat :seon.host/db [:maybe :int] [:maybe :int]] :map]]}
  ([main-db] (distill-tx-window main-db {}))
  ([main-db from-tx to-tx] (distill-tx-window main-db {:from-tx from-tx :to-tx to-tx}))
  ([main-db {:keys [from-tx to-tx conn include-health-delta? merge-limit preloaded-merges]
             :or {include-health-delta? false merge-limit 12}}]
   (let [max-tx (long (or (:max-tx main-db) 0))
         hi (long (if (integer? to-tx) (min max-tx (long to-tx)) max-tx))
         eff-db (if (< hi max-tx) (try (d/as-of main-db hi) (catch Throwable _ main-db)) main-db)
         prev-to (when (nil? from-tx) (:seon.summary/to-tx (first (latest-summaries eff-db {:limit 1 :until-tx (dec hi)}))))
         lo (long (cond (integer? from-tx) from-tx (integer? prev-to) prev-to :else (max 0 (- hi 500))))
         merges (if preloaded-merges
                  (vec (take (long merge-limit) (filter #(let [t (long (:main-tx % 0))] (and (> t lo) (<= t hi))) preloaded-merges)))
                  (merged-agents-in-range eff-db lo hi (long merge-limit)))
         has-fh? (contains? (:schema eff-db) :seon.code.form/hash)
         f-datoms (if has-fh? (vec (d/datoms eff-db :aevt :seon.code.form/hash)) [])
         t-eids (into #{} (comp (filter #(let [t (long (:tx %))] (and (> t lo) (<= t hi)))) (map :e)) f-datoms)
         ns-freqs (if (and (seq t-eids) (contains? (:schema eff-db) :seon.code.form/ns))
                    (reduce (fn [acc dt] (if (contains? t-eids (:e dt)) (update acc (:v dt) (fnil inc 0)) acc))
                            {} (d/datoms eff-db :aevt :seon.code.form/ns))
                    {})
         touched-nss (mapv (fn [[n c]] {:ns n :forms c}) (sort-by (fn [[n c]] [(- c) (str n)]) ns-freqs))
         s-datoms (if (contains? (:schema eff-db) :seon.schema/form) (vec (d/datoms eff-db :aevt :seon.schema/form)) [])
         t-seids (into #{} (comp (filter #(let [t (long (:tx %))] (and (> t lo) (<= t hi)))) (map :e)) s-datoms)
         touched-schemas (if (and (seq t-seids) (contains? (:schema eff-db) :seon.schema/key))
                           (->> (d/datoms eff-db :aevt :seon.schema/key)
                                (keep #(when (contains? t-seids (:e %)) (:v %)))
                                distinct sort vec)
                           [])
         h-now (health-counts eff-db)
         h-delta (when (and include-health-delta? (pos? lo) (< lo hi))
                   (when-let [pdb (try (d/as-of main-db lo) (catch Throwable _ nil))]
                     (let [hp (health-counts pdb)]
                       {:before hp :after h-now :delta-errors (- (:errors h-now) (:errors hp))
                        :delta-warnings (- (:warnings h-now) (:warnings hp)) :changed? (not= (:ok? h-now) (:ok? hp))})))
         lm (when conn (try (select-keys (hmetrics/agent-learning-metrics conn eff-db {:window 10})
                                         [:attempted :merged :merge-rate-pct :effective-turns-per-merged
                                          :median-turns-merged :kv-cache-hit-pct :eval-error-rate-pct])
                            (catch Throwable _ nil)))
         refs (vec (distinct (concat (keep :id (take 5 merges)) (map :ns (take 5 touched-nss)))))]
     (cond-> {:from-tx lo :to-tx hi :from-ms (tx-instant-ms main-db lo) :to-ms (tx-instant-ms main-db hi)
              :merges merges :touched-forms (count t-eids) :touched-nss touched-nss
              :touched-schemas touched-schemas :total-forms (count f-datoms)
              :total-schemas (count s-datoms) :health h-now :refs refs}
       h-delta (assoc :health-delta h-delta)
       lm (assoc :learning-metrics lm)))))

(defn record-summary!
  "Records a time- and tx-anchored `:seon.summary/entity` into Datahike `conn`.
   Auto-fills `:from-tx`, `:to-tx`, `:created-ms`, and distills `:title`/`:body`/`:refs` from
   Datahike whenever omitted (or when `:auto? true`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/conn] :map]
                  [:=> [:cat :seon.host/conn :map] :map]]}
  ([conn] (record-summary! conn {}))
  ([conn spec]
   (let [db @conn]
     (if-not (contains? (:schema db) :seon.summary/id)
       {:ok? false :error :summary-schema-not-installed}
       (let [s (if (map? spec) spec {})
             max-tx (long (or (:max-tx db) 0))
             prev-to (:seon.summary/to-tx (first (latest-summaries db {:limit 1})))
             from-tx (long (or (:seon.summary/from-tx s) (:from-tx s) prev-to (max 0 (- max-tx 200))))
             to-tx (long (or (:seon.summary/to-tx s) (:to-tx s) max-tx))
             now-ms (long (or (:seon.summary/created-ms s) (:created-ms s) (System/currentTimeMillis)))
             need-auto? (or (:auto? s)
                            (str/blank? (str (or (:seon.summary/title s) (:title s))))
                            (str/blank? (str (or (:seon.summary/body s) (:body s)))))
             ent (if need-auto?
                   (let [dist (distill-tx-window db {:from-tx from-tx :to-tx to-tx :conn conn
                                                     :include-health-delta? (boolean (:include-health-delta? s true))})]
                     (synthesize-summary dist (assoc s :from-tx from-tx :to-tx to-tx :created-ms now-ms)))
                   (first (record-summary-tx (assoc s :from-tx from-tx :to-tx to-tx :created-ms now-ms))))
             r (d/transact conn {:tx-data [ent]})]
         {:ok? true
          :id (:seon.summary/id ent)
          :from-tx (:seon.summary/from-tx ent)
          :to-tx (:seon.summary/to-tx ent)
          :tx (:max-tx (:db-after r))
          :entity ent})))))

(defn chronicle-for-compaction
  "Builds a crisp, token-budgeted chronological timeline of `tx`-anchored summaries for `agent-id`
   compaction (`user/compact!`), preserving exact `from-tx..to-tx` pointers and un-summarized tail
   activity so any agent can query `(d/as-of db to-tx)` for full details on demand."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :keyword]] :map]
                  [:=> [:cat :seon.host/db [:maybe :keyword] :map] :map]]}
  ([db agent-id] (chronicle-for-compaction db agent-id {}))
  ([db agent-id {:keys [limit max-chars detail-count agent-only? kind since-tx]
                 :or {limit 24 max-chars 2400 detail-count 3}}]
   (let [raw (latest-summaries db (cond-> {:limit limit}
                                    kind (assoc :kind kind)
                                    since-tx (assoc :since-tx since-tx)
                                    (and agent-only? agent-id) (assoc :agent agent-id)))
         entries (if (and agent-id (not agent-only?))
                   (filterv #(or (not= :compaction (:seon.summary/kind %))
                                 (= agent-id (:seon.summary/agent %)))
                            raw)
                   raw)
         latest-to (long (or (:seon.summary/to-tx (first entries)) 0))
         max-tx (long (or (:max-tx db) 0))
         tail-merges (if (< latest-to max-tx)
                       (merged-agents-in-range db latest-to max-tx 6)
                       [])
         tail-note (when (seq tail-merges)
                     (str "[Un-summarized · " (tx-range-label (inc latest-to) max-tx) "] "
                          (count tail-merges) " merge" (when (not= 1 (count tail-merges)) "s")
                          " (" (str/join ", " (map #(friendly-kw (:id %)) (take 4 tail-merges))) ")"))
         res (format-compaction-timeline entries {:max-chars max-chars
                                                  :detail-count detail-count
                                                  :tail-note tail-note})]
     (assoc res :tail-merges (count tail-merges)))))

(defn chronicle-freshness
  "Computes un-summarized activity since the latest Chronicle summary and evaluates `chronicle-due?`."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map] [:=> [:cat :seon.host/db :map] :map]]}
  ([main-db] (chronicle-freshness main-db {}))
  ([main-db thresholds]
   (let [latest (first (latest-summaries main-db {:limit 1}))
         max-tx (long (or (:max-tx main-db) 0))
         from-tx (long (or (:seon.summary/to-tx latest) 0))
         dist (distill-tx-window main-db {:from-tx from-tx :to-tx max-tx :include-health-delta? false})
         stats {:has-summary? (some? latest) :last-summary-tx from-tx :main-tx max-tx
                :tx-span (max 0 (- max-tx from-tx)) :merges-since (count (:merges dist))
                :namespaces-since (count (:touched-nss dist)) :forms-since (:touched-forms dist)
                :health-changed? false}]
     (merge stats (chronicle-due? stats thresholds)))))

(defn chronicle-tail
  "Optional `:seon.agent/tail-renders` block for `:agent/root` or a chronicler agent: surfaces the
   latest `tx`-anchored Chronicle pointers and gently prompts for a fresh summary ONLY when
   `chronicle-due?` is true (returns `nil` / 0 tokens when nothing changed)."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [main-db]}]
  (when main-db
    (let [recent (latest-summaries main-db {:limit 3})
          fresh (chronicle-freshness main-db)]
      (when (or (seq recent) (:due? fresh))
        (let [lines (cond-> []
                      (seq recent)
                      (into (map (fn [s]
                                   (str "  - [" (tx-range-label (:seon.summary/from-tx s) (:seon.summary/to-tx s))
                                        " · " (name (:seon.summary/kind s :system-pulse)) "] "
                                        (clip-str (:seon.summary/title s) 84)))
                                 recent))
                      (:due? fresh)
                      (conj (str "Chronicle due (" (name (:reason fresh)) ": "
                                 (:merges-since fresh) " merges, " (:namespaces-since fresh)
                                 " namespaces since tx " (:last-summary-tx fresh)
                                 ") — call (seon.render.chronicle/record-summary! (user/conn) {...}).")))]
          {:tag "chronicle"
           :text (str "Recent Chronicle anchors:\n" (str/join "\n" lines))})))))

(defn- active-workers-in [db self-aid agent-states]
  (if-not (and db (contains? (:schema db) :seon.agent/id))
    []
    (let [live-ids (into #{} (keep (fn [[k v]] (when (= :active v) k))) (when (map? agent-states) agent-states))
          ready-eids (if (contains? (:schema db) :seon.agent/ready?)
                       (into #{} (comp (filter :v) (map :e)) (d/datoms db :aevt :seon.agent/ready?))
                       #{})
          merged-eids (if (contains? (:schema db) :seon.agent/merged-tx)
                        (into #{} (map :e) (d/datoms db :aevt :seon.agent/merged-tx))
                        #{})
          cand-eids (into (set (remove merged-eids ready-eids))
                          (keep #(:e (first (d/datoms db :avet :seon.agent/id %))) live-ids))]
      (->> cand-eids
           (map #(d/pull db '[:seon.agent/id :seon.agent/role :seon.agent/branch :seon.agent/scope
                              :seon.agent/archived? :seon.agent/paused?] %))
           (remove #(let [aid (:seon.agent/id %)]
                      (or (nil? aid) (= self-aid aid) (:seon.agent/archived? %) (:seon.agent/paused? %)
                          (= :repl (:seon.agent/role %)) (#{"repl" "example"} (namespace aid)))))
           (sort-by #(str (:seon.agent/id %)))
           (take 8)
           vec))))

(defn living-canvas-data
  "Fast (< 3 ms) Datahike snapshot powering Living System Understanding and Chronicle on coordinator Canvases."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/conn] :keyword :any [:maybe :int]] :map]}
  [eff-main-db conn aid agent-states as-of-tx]
  (let [max-tx (long (or (:max-tx eff-main-db) 0))
        main-ms (tx-instant-ms eff-main-db max-tx)
        summaries (latest-summaries eff-main-db {:limit 10})
        latest (first summaries)
        last-tx (long (or (:seon.summary/to-tx latest) 0))
        merges (merged-agents-in-range eff-main-db 0 max-tx 12)
        dist (distill-tx-window eff-main-db {:from-tx (if latest last-tx (max 0 (- max-tx 500)))
                                             :to-tx max-tx :conn conn :include-health-delta? false
                                             :preloaded-merges merges})
        active (if (nil? as-of-tx) (active-workers-in eff-main-db aid agent-states) [])
        msince (if latest (count (filter #(> (long (:main-tx % 0)) last-tx) merges)) (count merges))
        fstats {:has-summary? (some? latest) :last-summary-tx last-tx :main-tx max-tx
                :tx-span (max 0 (- max-tx last-tx)) :merges-since msince
                :namespaces-since (count (:touched-nss dist)) :health-changed? false}
        h (:health dist) lm (:learning-metrics dist)
        metrics [{:id :health :label "Health"
                  :value (if (:ok? h) "Healthy" (str (:errors h) " err"))
                  :sub (str (:warnings h 0) " advisories") :tone (if (:ok? h) :green :red)
                  :action '(seon.web/set-view-mode! :debug)}
                 {:id :velocity :label (if (and lm (pos? (long (:attempted lm 0)))) "Merged (last 10)" "Recent Merges")
                  :value (if (and lm (pos? (long (:attempted lm 0))))
                           (str (:merged lm) "/" (:attempted lm) " (" (:merge-rate-pct lm 0) "%)")
                           (str (count merges)))
                  :sub (if (and lm (:median-turns-merged lm)) (str "median " (:median-turns-merged lm) " turns")
                           (str (count (:touched-nss dist)) " ns touched")) :tone :amber}
                 {:id :cache :label (if (and lm (:kv-cache-hit-pct lm)) "KV Cache Hit" "Codebase")
                  :value (if (and lm (:kv-cache-hit-pct lm)) (str (:kv-cache-hit-pct lm) "%")
                             (str (:total-forms dist 0) " forms"))
                  :sub (if (and lm (:eval-error-rate-pct lm)) (str (:eval-error-rate-pct lm) "% eval err")
                           (str (:total-schemas dist 0) " schemas")) :tone :blue}
                 {:id :snapshot :label (if as-of-tx "Snapshot" "Head") :value (str "tx " max-tx)
                  :sub (or (not-empty (format-epoch-utc main-ms)) (str (:total-forms dist 0) " forms"))
                  :tone (if as-of-tx :amber :muted)}]]
    {:summaries summaries :latest-summary latest
     :hero-summary (or latest (synthesize-summary dist {:agent aid :created-ms (or main-ms 0)}))
     :synthesized? (nil? latest) :distilled dist :freshness (merge fstats (chronicle-due? fstats))
     :entries (merge-chronicle-entries summaries merges) :active active :recent-merges merges
     :latest-merge-tx (long (or (:main-tx (first merges)) 0)) :health h :metrics metrics
     :main-tx max-tx :main-ms main-ms :as-of-tx as-of-tx}))
