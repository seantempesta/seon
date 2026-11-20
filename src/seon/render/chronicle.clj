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
  {:malli/schema [:=> [:cat :map] [:vector :map]] :cost/ticks 500000 :cost/bytes 1048576}
  [spec]
  (let [g #(or (get spec (keyword "seon.summary" (name %))) (get spec %))
        kind (let [k (g :kind)] (if (keyword? k) k :system-pulse))
        title (let [s (g :title)] (if (and (string? s) (not (str/blank? s))) (str/trim s) "System Summary"))
        body (let [b (g :body)] (if (string? b) (str/trim b) ""))
        ft (int-or (g :from-tx) (int-or (g :to-tx) 0))
        tt (int-or (g :to-tx) ft)
        lo (min ft tt) hi (max ft tt)
        ms (int-or (g :created-ms) 0)
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
  {:malli/schema [:=> [:cat :map [:? :map]] :map] :cost/ticks 200000 :cost/bytes 262144}
  ([activity] (chronicle-due? activity {}))
  ([activity thresholds]
   (let [t (if (map? thresholds) thresholds {})
         merges (max 0 (int-or (:merges-since activity) 0))
         nss (max 0 (int-or (:namespaces-since activity) 0))
         span (max 0 (int-or (:tx-span activity) 0))
         hch? (boolean (:health-changed? activity))
         has? (if (contains? activity :has-summary?) (boolean (:has-summary? activity)) true)
         moved? (or (pos? merges) (pos? nss) hch?)]
     (cond
       hch? {:due? true :reason :health-transition :urgency :high}
       (and (not has?) moved?) {:due? true :reason :initial :urgency :normal}
       (>= merges (max 1 (int-or (:min-merges t) 3))) {:due? true :reason :merge-batch :urgency :normal}
       (>= nss (max 1 (int-or (:min-namespaces t) 4))) {:due? true :reason :broad-refactor :urgency :normal}
       (and (>= span (max 1 (int-or (:min-tx-span t) 400))) moved?) {:due? true :reason :tx-milestone :urgency :normal}
       :else {:due? false :reason nil :urgency :none}))))

(defn- clip-str [s max-n]
  (let [t (str/replace (str/trim (str (or s ""))) #"\s+" " ")]
    (if (<= (count t) max-n) t (str (subs t 0 (max 0 (- max-n 1))) "…"))))

(defn- friendly-kw [k]
  (cond
    (keyword? k) (if-let [ns-p (namespace k)] (if (= "agent" ns-p) (name k) (str ns-p "/" (name k))) (name k))
    :else (str k)))

(defn synthesize-summary
  "Turns a `distill-tx-window` snapshot into a concise `:seon.summary/entity` map."
  {:malli/schema [:=> [:cat :map [:? :map]] :map] :cost/ticks 1500000 :cost/bytes 3145728}
  ([distilled] (synthesize-summary distilled {}))
  ([distilled opts]
   (let [d (if (map? distilled) distilled {}) o (if (map? opts) opts {})
         merges (if (sequential? (:merges d)) (vec (filter map? (:merges d))) [])
         nss (if (sequential? (:touched-nss d)) (vec (filter map? (:touched-nss d))) [])
         h (if (map? (:health d)) (:health d) {:ok? true :errors 0 :warnings 0})
         lm (when (map? (:learning-metrics d)) (:learning-metrics d))
         tns (mapv #(str (:ns %)) (take 3 (filter :ns nss)))
         mc (count merges) fc (int-or (:touched-forms d) 0)
         title (or (when (and (string? (:title o)) (not (str/blank? (:title o)))) (str/trim (:title o)))
                   (cond (and (pos? mc) (seq tns)) (str "System Pulse · " mc " merge" (when (not= 1 mc) "s") " across " (str/join ", " tns))
                         (pos? mc) (str "System Pulse · " mc " recent merge" (when (not= 1 mc) "s"))
                         (seq tns) (str "Codebase Activity · " fc " forms in " (str/join ", " tns))
                         (false? (:ok? h)) (str "Health Attention · " (:errors h 0) " error(s)")
                         :else "System Pulse · Quiet & Healthy"))
         ml (for [m (take 3 merges) :let [tx (or (:main-tx m) (:merged-tx m)) g (clip-str (:goal m) 104)]]
              (str "- **" (friendly-kw (:id m)) "**" (when (integer? tx) (str " (`tx " tx "`)")) (when-not (str/blank? g) (str ": " g))))
         nl (when (seq nss)
              (str "- **Subsystems:** " (str/join ", " (map #(str "`" (:ns %) "` (" (:forms % 0) ")") (take 5 nss)))
                   (when (> (count nss) 5) (str " +" (- (count nss) 5) " more"))))
         hl (str "- **Health:** " (if (:ok? h true) "Healthy" (str (:errors h 0) " error(s)"))
                 (when (pos? (int-or (:warnings h) 0)) (str " (" (:warnings h) " advisories)"))
                 (when (and lm (pos? (int-or (:attempted lm) 0)))
                   (str " · **Velocity:** " (int-or (:merged lm) 0) "/" (:attempted lm) " merged"
                        (when (integer? (:kv-cache-hit-pct lm)) (str ", " (:kv-cache-hit-pct lm) "% KV-cache")))))
         body (or (when (and (string? (:body o)) (not (str/blank? (:body o)))) (:body o))
                  (str/join "\n" (remove nil? (concat ml [nl hl]))))]
     (first (record-summary-tx (merge {:kind :system-pulse :title title :body body :from-tx (:from-tx d) :to-tx (:to-tx d)
                                       :created-ms (:to-ms d) :agent :agent/root :refs (:refs d)} o))))))

(defn format-compaction-timeline
  "Token-budgeted chronological formatter for compaction memory keeping exact `tx A..B` and `d/as-of` pointers."
  {:malli/schema [:=> [:cat [:sequential :map] [:? :map]] :map] :cost/ticks 2500000 :cost/bytes 4194304}
  ([entries] (format-compaction-timeline entries {}))
  ([entries opts]
   (let [o (if (map? opts) opts {}) mx (max 160 (int-or (:max-chars o) 2400)) dn (max 1 (int-or (:detail-count o) 3))
         tail (when (and (string? (:tail-note o)) (not (str/blank? (:tail-note o)))) (str "- " (str/trim (:tail-note o))))
         es (vec (sort-by #(vector (int-or (:seon.summary/from-tx %) 0) (int-or (:seon.summary/to-tx %) 0) (int-or (:seon.summary/created-ms %) 0))
                          (filter map? entries)))
         n (count es)
         items (mapv (fn [i]
                       (let [e (nth es i) det? (>= i (- n dn))
                             ts (format-epoch-utc (when (integer? (:seon.summary/created-ms e)) (:seon.summary/created-ms e)))
                             txs (tx-range-label (:seon.summary/from-tx e) (:seon.summary/to-tx e))
                             knd (if (keyword? (:seon.summary/kind e)) (name (:seon.summary/kind e)) "pulse")
                             rfs (when (sequential? (:seon.summary/refs e)) (take 4 (:seon.summary/refs e)))
                             bdy (when det? (clip-str (:seon.summary/body e) 180))]
                         {:entry e :line (str "- [" (str/join " · " (remove str/blank? [ts txs knd])) "] "
                                              (clip-str (or (:seon.summary/title e) "Summary") 84)
                                              (when (seq rfs) (str " (" (str/join ", " (map friendly-kw rfs)) ")"))
                                              (when (and det? (not (str/blank? bdy))) (str "\n  " bdy)))}))
                     (range n))
         hdr "Chronicle timeline (drill in via `(d/as-of (user/main-db) <to-tx>)`):"
         bgt (max 64 (- mx (+ (count hdr) 1 (if tail (+ 1 (count tail)) 0))))
         [kept tr?] (loop [i (dec n) a [] u 0]
                      (if (< i 0) [(vec (reverse a)) false]
                          (let [it (nth items i) l (+ (count (:line it)) (if (seq a) 1 0))]
                            (if (or (empty? a) (<= (+ u l) bgt)) (recur (dec i) (conj a it) (+ u l)) [(vec (reverse a)) true]))))
         ke (mapv :entry kept)
         raw (str/join "\n" (cond-> [hdr] tr? (conj (str "- … (" (- n (count kept)) " earlier omitted; query `d/history`)"))
                              (seq kept) (into (map :line kept)) (and (empty? kept) (nil? tail)) (conj "- (no summaries yet)") tail (conj tail)))
         txt (if (> (count raw) mx) (str (subs raw 0 (dec mx)) "…") raw)
         txs (keep #(when (integer? %) (long %)) (concat (map :seon.summary/from-tx ke) (map :seon.summary/to-tx ke)))]
     {:text txt :entries ke :from-tx (if (seq txs) (apply min txs) 0) :to-tx (if (seq txs) (apply max txs) 0)
      :truncated? (boolean (or tr? (> (count raw) mx))) :chars (count txt)})))

(defn merge-chronicle-entries
  "Unifies recorded summaries with un-summarized merges (newest first, zero duplicates)."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] [:vector :map]] :cost/ticks 1500000 :cost/bytes 2097152}
  [summaries recent-merges]
  (let [sl (vec (filter map? summaries))
        cov? (fn [tx] (boolean (and (integer? tx) (some #(let [a (:seon.summary/from-tx %) b (:seon.summary/to-tx %)]
                                                           (and (integer? a) (integer? b) (<= (long a) (long tx) (long b)))) sl))))
        se (mapv #(hash-map :entry-kind (if (keyword? (:seon.summary/kind %)) (:seon.summary/kind %) :system-pulse)
                            :source :summary :id (:seon.summary/id %) :title (or (:seon.summary/title %) "Summary")
                            :body (or (:seon.summary/body %) "") :from-tx (int-or (:seon.summary/from-tx %) 0)
                            :to-tx (int-or (:seon.summary/to-tx %) 0) :ms (int-or (:seon.summary/created-ms %) 0)
                            :agent (when (keyword? (:seon.summary/agent %)) (:seon.summary/agent %))
                            :refs (if (sequential? (:seon.summary/refs %)) (vec (:seon.summary/refs %)) [])
                            :visual (:seon.summary/visual %)) sl)
        me (keep #(when (map? %) (let [mtx (or (:main-tx %) (:merged-tx %))]
                                   (when-not (cov? mtx)
                                     (let [aid (:id %) tx (int-or mtx 0)]
                                       {:entry-kind :merge :source :merge :id (or aid tx) :title (friendly-kw (or aid :branch/merged))
                                        :body (str (or (:goal %) "")) :from-tx tx :to-tx tx :ms (int-or (:ms %) 0)
                                        :agent (when (keyword? aid) aid) :refs (vec (remove nil? [aid (:scope %)]))})))) recent-merges)]
    (vec (sort-by #(vector (- (int-or (:to-tx %) 0)) (- (int-or (:ms %) 0)) (if (= :summary (:source %)) 0 1)) (concat se me)))))

(defn tx-instant-ms
  "Returns epoch milliseconds for Datahike transaction id `tx` via `:db/txInstant`, or `nil`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :int]] [:maybe :int]]}
  [db tx]
  (when (and db (integer? tx) (pos? (long tx)))
    (when-let [inst (:db/txInstant (try (d/pull db '[:db/txInstant] (long tx)) (catch Throwable _ nil)))]
      (when (instance? java.util.Date inst)
        (.getTime ^java.util.Date inst)))))

(defn latest-summaries
  "Recorded `:seon.summary/*` entities newest-first (`to-tx` desc). Opts: `:limit :kind :agent :since-tx :until-tx`."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :map]] [:=> [:cat :seon.host/db :map] [:vector :map]]]}
  ([db] (latest-summaries db {}))
  ([db {:keys [limit kind agent since-tx until-tx] :or {limit 12}}]
   (if-not (and db (contains? (:schema db) :seon.summary/id) (contains? (:schema db) :seon.summary/to-tx))
     []
     (let [ks (cond (set? kind) kind (keyword? kind) #{kind} :else nil)]
       (->> (d/datoms db :aevt :seon.summary/to-tx)
            (filter #(let [v (long (:v %))]
                       (and (or (nil? since-tx) (> v (long since-tx)))
                            (or (nil? until-tx) (<= v (long until-tx))))))
            (sort-by #(vector (- (long (:v %))) (- (long (:tx %)))))
            (map #(dissoc (d/pull db '[*] (:e %)) :db/id))
            (filter #(and (:seon.summary/id %)
                          (or (nil? ks) (contains? ks (:seon.summary/kind %)))
                          (or (nil? agent) (= agent (:seon.summary/agent %)))))
            (sort-by #(vector (- (long (or (:seon.summary/to-tx %) 0)))
                              (- (long (or (:seon.summary/created-ms %) 0)))))
            (take (max 1 (long (or limit 12))))
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
  "Single-pass O(F + A) Datahike distillation over `(from-tx .. to-tx]`: merges, touched namespaces/schemas, health, metrics."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map] [:=> [:cat :seon.host/db :map] :map] [:=> [:cat :seon.host/db [:maybe :int] [:maybe :int]] :map]]}
  ([main-db] (distill-tx-window main-db {}))
  ([main-db from-tx to-tx] (distill-tx-window main-db {:from-tx from-tx :to-tx to-tx}))
  ([main-db {:keys [from-tx to-tx conn include-health-delta? merge-limit preloaded-merges] :or {include-health-delta? false merge-limit 12}}]
   (let [mx (int-or (:max-tx main-db) 0)
         hi (if (integer? to-tx) (min mx (long to-tx)) mx)
         edb (if (< hi mx) (try (d/as-of main-db hi) (catch Throwable _ main-db)) main-db)
         lo (cond (integer? from-tx) (long from-tx)
                  :else (long (or (:seon.summary/to-tx (first (latest-summaries edb {:limit 1 :until-tx (dec hi)}))) (max 0 (- hi 500)))))
         in-win? #(let [t (long (:tx %))] (and (> t lo) (<= t hi)))
         merges (if preloaded-merges
                  (vec (take (long merge-limit) (filter #(let [t (int-or (:main-tx %) 0)] (and (> t lo) (<= t hi))) preloaded-merges)))
                  (merged-agents-in-range edb lo hi (long merge-limit)))
         fd (if (contains? (:schema edb) :seon.code.form/hash) (vec (d/datoms edb :aevt :seon.code.form/hash)) [])
         te (into #{} (comp (filter in-win?) (map :e)) fd)
         nf (if (and (seq te) (contains? (:schema edb) :seon.code.form/ns))
              (reduce #(if (contains? te (:e %2)) (update %1 (:v %2) (fnil inc 0)) %1) {} (d/datoms edb :aevt :seon.code.form/ns)) {})
         tnss (mapv (fn [[n c]] {:ns n :forms c}) (sort-by (fn [[n c]] [(- c) (str n)]) nf))
         sd (if (contains? (:schema edb) :seon.schema/form) (vec (d/datoms edb :aevt :seon.schema/form)) [])
         tse (into #{} (comp (filter in-win?) (map :e)) sd)
         tsch (if (and (seq tse) (contains? (:schema edb) :seon.schema/key))
                (vec (sort (distinct (keep #(when (contains? tse (:e %)) (:v %)) (d/datoms edb :aevt :seon.schema/key))))) [])
         hn (health-counts edb)
         hd (when (and include-health-delta? (pos? lo) (< lo hi))
              (when-let [pdb (try (d/as-of main-db lo) (catch Throwable _ nil))]
                (let [hp (health-counts pdb)]
                  {:before hp :after hn :delta-errors (- (:errors hn) (:errors hp))
                   :delta-warnings (- (:warnings hn) (:warnings hp)) :changed? (not= (:ok? hn) (:ok? hp))})))
         lm (when conn (try (select-keys (hmetrics/agent-learning-metrics conn edb {:window 10})
                                         [:attempted :merged :merge-rate-pct :effective-turns-per-merged
                                          :median-turns-merged :kv-cache-hit-pct :eval-error-rate-pct]) (catch Throwable _ nil)))]
     (cond-> {:from-tx lo :to-tx hi :from-ms (tx-instant-ms main-db lo) :to-ms (tx-instant-ms main-db hi)
              :merges merges :touched-forms (count te) :touched-nss tnss :touched-schemas tsch
              :total-forms (count fd) :total-schemas (count sd) :health hn
              :refs (vec (distinct (concat (keep :id (take 5 merges)) (map :ns (take 5 tnss)))))}
       hd (assoc :health-delta hd) lm (assoc :learning-metrics lm)))))

(defn record-summary!
  "Records a `:seon.summary/entity` into Datahike `conn`, auto-distilling omitted fields from `[last-to-tx .. max-tx]`."
  {:malli/schema [:function [:=> [:cat :seon.host/conn] :map] [:=> [:cat :seon.host/conn :map] :map]]}
  ([conn] (record-summary! conn {}))
  ([conn spec]
   (let [db @conn]
     (if-not (contains? (:schema db) :seon.summary/id) {:ok? false :error :summary-schema-not-installed}
       (let [s (if (map? spec) spec {}) mx (int-or (:max-tx db) 0)
             prev (:seon.summary/to-tx (first (latest-summaries db {:limit 1})))
             ft (long (or (:seon.summary/from-tx s) (:from-tx s) prev (max 0 (- mx 200))))
             tt (long (or (:seon.summary/to-tx s) (:to-tx s) mx))
             ms (long (or (:seon.summary/created-ms s) (:created-ms s) (System/currentTimeMillis)))
             auto? (or (:auto? s) (str/blank? (str (or (:seon.summary/title s) (:title s)))) (str/blank? (str (or (:seon.summary/body s) (:body s)))))
             s1 (assoc s :from-tx ft :to-tx tt :created-ms ms)
             ent (if auto?
                   (synthesize-summary (distill-tx-window db {:from-tx ft :to-tx tt :conn conn :include-health-delta? (boolean (:include-health-delta? s true))}) s1)
                   (first (record-summary-tx s1)))
             r (d/transact conn {:tx-data [ent]})]
         {:ok? true :id (:seon.summary/id ent) :from-tx (:seon.summary/from-tx ent)
          :to-tx (:seon.summary/to-tx ent) :tx (:max-tx (:db-after r)) :entity ent})))))

(defn chronicle-for-compaction
  "Builds a token-budgeted chronological summary timeline for `user/compact!` with `from-tx..to-tx` pointers."
  {:malli/schema [:function [:=> [:cat :seon.host/db [:maybe :keyword]] :map] [:=> [:cat :seon.host/db [:maybe :keyword] :map] :map]]}
  ([db agent-id] (chronicle-for-compaction db agent-id {}))
  ([db agent-id {:keys [limit max-chars detail-count agent-only? kind since-tx] :or {limit 24 max-chars 2400 detail-count 3}}]
   (let [raw (latest-summaries db (cond-> {:limit limit} kind (assoc :kind kind) since-tx (assoc :since-tx since-tx)
                                          (and agent-only? agent-id) (assoc :agent agent-id)))
         es (if (and agent-id (not agent-only?))
              (filterv #(or (not= :compaction (:seon.summary/kind %)) (= agent-id (:seon.summary/agent %))) raw) raw)
         lt (long (or (:seon.summary/to-tx (first es)) 0)) mx (long (or (:max-tx db) 0))
         tm (if (< lt mx) (merged-agents-in-range db lt mx 6) [])
         tn (when (seq tm) (str "[Un-summarized · " (tx-range-label (inc lt) mx) "] " (count tm) " merge"
                                (when (not= 1 (count tm)) "s") " (" (str/join ", " (map #(friendly-kw (:id %)) (take 4 tm))) ")"))]
     (assoc (format-compaction-timeline es {:max-chars max-chars :detail-count detail-count :tail-note tn}) :tail-merges (count tm)))))

(defn chronicle-freshness
  "Computes un-summarized activity since the latest Chronicle summary and evaluates `chronicle-due?`."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map] [:=> [:cat :seon.host/db :map] :map]]}
  ([main-db] (chronicle-freshness main-db {}))
  ([main-db thresholds]
   (let [latest (first (latest-summaries main-db {:limit 1})) mx (int-or (:max-tx main-db) 0)
         ft (int-or (:seon.summary/to-tx latest) 0)
         d (distill-tx-window main-db {:from-tx ft :to-tx mx :include-health-delta? false})
         st {:has-summary? (some? latest) :last-summary-tx ft :main-tx mx :tx-span (max 0 (- mx ft))
             :merges-since (count (:merges d)) :namespaces-since (count (:touched-nss d))
             :forms-since (:touched-forms d) :health-changed? false}]
     (merge st (chronicle-due? st thresholds)))))

(defn chronicle-tail
  "Optional `:seon.agent/tail-renders` block surfacing recent Chronicle anchors & nudging only when `chronicle-due?`."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [main-db]}]
  (when main-db
    (let [rec (latest-summaries main-db {:limit 3}) fr (chronicle-freshness main-db)]
      (when (or (seq rec) (:due? fr))
        {:tag "chronicle"
         :text (str "Recent Chronicle anchors:\n"
                    (str/join "\n" (cond-> (mapv #(str "  - [" (tx-range-label (:seon.summary/from-tx %) (:seon.summary/to-tx %))
                                                        " · " (name (:seon.summary/kind % :system-pulse)) "] " (clip-str (:seon.summary/title %) 84)) rec)
                                      (:due? fr) (conj (str "Chronicle due (" (name (:reason fr)) ": " (:merges-since fr) " merges, "
                                                            (:namespaces-since fr) " ns since tx " (:last-summary-tx fr) ") — record via record-summary!.")))))}))))

(defn living-canvas-data
  "Fast (< 2 ms) Datahike snapshot powering Living System Understanding and Chronicle on coordinator Canvases."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/conn] :keyword :any [:maybe :int]] :map]}
  [eff-main-db conn aid active-workers as-of-tx]
  (let [mx (int-or (:max-tx eff-main-db) 0) ms (tx-instant-ms eff-main-db mx)
        sums (latest-summaries eff-main-db {:limit 10}) latest (first sums)
        ltx (int-or (:seon.summary/to-tx latest) 0)
        merges (merged-agents-in-range eff-main-db 0 mx 12)
        dist (distill-tx-window eff-main-db {:from-tx (if latest ltx (max 0 (- mx 500))) :to-tx mx
                                             :conn conn :include-health-delta? false :preloaded-merges merges})
        msince (if latest (count (filter #(> (int-or (:main-tx %) 0) ltx) merges)) (count merges))
        fs {:has-summary? (some? latest) :last-summary-tx ltx :main-tx mx :tx-span (max 0 (- mx ltx))
            :merges-since msince :namespaces-since (count (:touched-nss dist)) :health-changed? false}]
    {:summaries sums :latest-summary latest
     :hero-summary (or latest (synthesize-summary dist {:agent aid :created-ms (or ms 0)}))
     :synthesized? (nil? latest) :distilled dist :freshness (merge fs (chronicle-due? fs))
     :entries (merge-chronicle-entries sums merges)
     :active (if (and (nil? as-of-tx) (sequential? active-workers)) (vec active-workers) [])
     :recent-merges merges :latest-merge-tx (int-or (:main-tx (first merges)) 0)
     :health (:health dist) :learning-metrics (:learning-metrics dist)
     :main-tx mx :main-ms ms :as-of-tx as-of-tx}))

(defn- int-or [v d] (if (integer? v) (long v) (long d)))
