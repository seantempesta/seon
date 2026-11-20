(ns seon.render.chronicle
  "Time- and Datahike-tx-anchored Chronicle summary stream, tx-window distillation, compaction timeline builder, freshness triggers, and Living Canvas data engine."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [seon.health :as health]
            [seon.health.metrics :as hmetrics]))

(def summary-schema-forms
  "Canonical Malli schema forms for `:seon.summary/*` (single source of truth for tests & schema derivation)."
  {:seon.summary/id [:and {:seon.db/identity true :seon.db/content-id true} :uuid]
   :seon.summary/kind [:and {:seon.db/index true :gen/elements [:system-pulse :milestone :compaction :architecture]} :keyword]
   :seon.summary/title [:string {:min 1}]
   :seon.summary/body :string
   :seon.summary/from-tx [:and {:seon.db/index true} :int]
   :seon.summary/to-tx [:and {:seon.db/index true} :int]
   :seon.summary/created-ms [:and {:seon.db/index true} :int]
   :seon.summary/agent [:and {:seon.db/index true} :keyword]
   :seon.summary/refs [:vector {:gen/elements [[] [:agent/root 'seon.render.canvas]]} :any]
   :seon.summary/visual [:any {:gen/elements [[:div {:class "pill-row"} [:span {:class "badge green"} "Healthy"]] [:span {:class "badge blue"} "Snapshot"]]}]
   :seon.summary/entity
   [:map {:seon.db/attributes true
          :seon.render/hiccup 'seon.render.tiles/summary-tile
          :seon.schema/doc "Time- and Datahike-tx-anchored system or compaction summary in the Chronicle stream."}
    [:seon.summary/id :seon.summary/id]
    [:seon.summary/kind :seon.summary/kind]
    [:seon.summary/title :seon.summary/title]
    [:seon.summary/body :seon.summary/body]
    [:seon.summary/from-tx :seon.summary/from-tx]
    [:seon.summary/to-tx :seon.summary/to-tx]
    [:seon.summary/created-ms :seon.summary/created-ms]
    [:seon.summary/agent :seon.summary/agent]
    [:seon.summary/refs {:optional true} :seon.summary/refs]
    [:seon.summary/visual {:optional true} :seon.summary/visual]]})

(def ^:private utc-formatter
  (.withZone (java.time.format.DateTimeFormatter/ofPattern "MMM dd · HH:mm 'UTC'" java.util.Locale/ENGLISH)
             java.time.ZoneOffset/UTC))

(defn format-epoch-utc
  "Formats epoch milliseconds `ms` into a concise deterministic UTC timestamp (`Nov 20 · 11:23 UTC`), or `\"\"` when nil/non-positive."
  {:malli/schema [:=> [:cat [:maybe [:int {:min 0 :max 4102444800000}]]] :string]
   :cost/ticks 200000
   :cost/bytes 262144}
  [ms]
  (if (and (integer? ms) (pos? (long ms)) (<= (long ms) 4102444800000))
    (.format ^java.time.format.DateTimeFormatter utc-formatter (java.time.Instant/ofEpochMilli (long ms)))
    ""))

(defn tx-range-label
  "Formats a Datahike transaction range as `tx A..B` (or `tx B` when single-point)."
  {:malli/schema [:=> [:cat [:maybe :int] [:maybe :int]] :string]
   :cost/ticks 100000
   :cost/bytes 131072}
  [from-tx to-tx]
  (let [a (when (integer? from-tx) (long from-tx))
        b (when (integer? to-tx) (long to-tx))]
    (cond
      (and a b (pos? a) (pos? b) (not= a b)) (str "tx " (min a b) ".." (max a b))
      (and b (pos? b)) (str "tx " b)
      (and a (pos? a)) (str "tx " a)
      :else "")))

(defn record-summary-tx
  "Pure builder turning a summary spec (short or `:seon.summary/*` keys) into a valid `[entity-map]`
   Datahike transaction vector with deterministic UUID identity and zero nil attributes."
  {:malli/schema [:=> [:cat :map] [:vector :map]]
   :cost/ticks 500000
   :cost/bytes 1048576}
  [spec]
  (let [raw-kind (or (:seon.summary/kind spec) (:kind spec))
        kind (if (keyword? raw-kind) raw-kind :system-pulse)
        raw-title (or (:seon.summary/title spec) (:title spec))
        title (let [s (when (string? raw-title) (str/trim raw-title))]
                (if (not (str/blank? s)) s "System Summary"))
        raw-body (or (:seon.summary/body spec) (:body spec))
        body (if (string? raw-body) (str/trim raw-body) "")
        raw-from (or (:seon.summary/from-tx spec) (:from-tx spec))
        raw-to (or (:seon.summary/to-tx spec) (:to-tx spec))
        from-tx (long (cond (integer? raw-from) raw-from (integer? raw-to) raw-to :else 0))
        to-tx (long (cond (integer? raw-to) raw-to :else from-tx))
        lo (min from-tx to-tx)
        hi (max from-tx to-tx)
        raw-ms (or (:seon.summary/created-ms spec) (:created-ms spec))
        created-ms (long (if (integer? raw-ms) raw-ms 0))
        raw-ag (or (:seon.summary/agent spec) (:agent spec))
        agent-id (if (keyword? raw-ag) raw-ag :agent/root)
        raw-id (or (:seon.summary/id spec) (:id spec))
        sid (if (uuid? raw-id) raw-id (hasch/uuid [:seon.summary kind agent-id lo hi title]))
        raw-refs (or (:seon.summary/refs spec) (:refs spec))
        refs (when (and (sequential? raw-refs) (seq raw-refs))
               (vec (remove nil? raw-refs)))
        visual (or (:seon.summary/visual spec) (:visual spec))
        ent (cond-> {:seon.summary/id sid
                     :seon.summary/kind kind
                     :seon.summary/title title
                     :seon.summary/body body
                     :seon.summary/from-tx lo
                     :seon.summary/to-tx hi
                     :seon.summary/created-ms created-ms
                     :seon.summary/agent agent-id}
              (seq refs) (assoc :seon.summary/refs refs)
              (some? visual) (assoc :seon.summary/visual visual))]
    [ent]))

(def default-trigger-thresholds
  {:min-merges 3
   :min-namespaces 4
   :min-tx-span 400})

(defn chronicle-due?
  "Pure, event-driven trigger predicate deciding whether Root or a chronicler should author a new
   Chronicle summary. Never triggers when idle (nothing changed, nothing runs)."
  {:malli/schema [:=> [:cat :map [:? :map]] :map]
   :cost/ticks 200000
   :cost/bytes 262144}
  ([activity] (chronicle-due? activity default-trigger-thresholds))
  ([activity thresholds]
   (let [th (merge default-trigger-thresholds (when (map? thresholds) thresholds))
         min-m (max 1 (long (if (integer? (:min-merges th)) (:min-merges th) 3)))
         min-ns (max 1 (long (if (integer? (:min-namespaces th)) (:min-namespaces th) 4)))
         min-span (max 1 (long (if (integer? (:min-tx-span th)) (:min-tx-span th) 400)))
         merges (long (if (integer? (:merges-since activity)) (max 0 (:merges-since activity)) 0))
         nss (long (if (integer? (:namespaces-since activity)) (max 0 (:namespaces-since activity)) 0))
         span (long (if (integer? (:tx-span activity)) (max 0 (:tx-span activity)) 0))
         health-ch? (boolean (:health-changed? activity))
         has-sum? (if (contains? activity :has-summary?) (boolean (:has-summary? activity)) true)
         any-change? (or (pos? merges) (pos? nss) health-ch?)]
     (cond
       health-ch? {:due? true :reason :health-transition :urgency :high}
       (and (not has-sum?) any-change?) {:due? true :reason :initial :urgency :normal}
       (>= merges min-m) {:due? true :reason :merge-batch :urgency :normal}
       (>= nss min-ns) {:due? true :reason :broad-refactor :urgency :normal}
       (and (>= span min-span) any-change?) {:due? true :reason :tx-milestone :urgency :normal}
       :else {:due? false :reason nil :urgency :none}))))

(defn- clip-str [s max-n]
  (let [t (str/replace (str/trim (str (or s ""))) #"\s+" " ")]
    (if (<= (count t) max-n) t (str (subs t 0 (max 0 (- max-n 1))) "…"))))

(defn- friendly-kw [k]
  (cond
    (keyword? k) (if-let [ns-p (namespace k)] (if (= "agent" ns-p) (name k) (str ns-p "/" (name k))) (name k))
    :else (str k)))

(defn synthesize-summary
  "Pure synthesizer turning a `distill-tx-window` snapshot into a concise `:seon.summary/entity` map
   (headline, scannable Markdown bullets, refs, and deterministic UUID)."
  {:malli/schema [:=> [:cat :map [:? :map]] :map]
   :cost/ticks 1500000
   :cost/bytes 3145728}
  ([distilled] (synthesize-summary distilled {}))
  ([distilled opts]
   (let [d (if (map? distilled) distilled {})
         o (if (map? opts) opts {})
         merges (if (sequential? (:merges d)) (vec (filter map? (:merges d))) [])
         nss (if (sequential? (:touched-nss d)) (vec (filter map? (:touched-nss d))) [])
         schemas (if (sequential? (:touched-schemas d)) (vec (:touched-schemas d)) [])
         h (if (map? (:health d)) (:health d) {:ok? true :errors 0 :warnings 0})
         lm (when (map? (:learning-metrics d)) (:learning-metrics d))
         top-ns-names (mapv #(str (:ns %)) (take 3 (filter :ns nss)))
         m-count (count merges)
         f-count (long (if (integer? (:touched-forms d)) (:touched-forms d) 0))
         title (or (when (and (string? (:title o)) (not (str/blank? (:title o)))) (str/trim (:title o)))
                   (cond
                     (and (pos? m-count) (seq top-ns-names))
                     (str "System Pulse · " m-count " merge" (when (not= 1 m-count) "s")
                          " across " (str/join ", " top-ns-names))
                     (pos? m-count)
                     (str "System Pulse · " m-count " recent merge" (when (not= 1 m-count) "s"))
                     (seq top-ns-names)
                     (str "Codebase Activity · " f-count " form" (when (not= 1 f-count) "s")
                          " in " (str/join ", " top-ns-names))
                     (false? (:ok? h))
                     (str "Health Attention · " (:errors h 0) " error" (when (not= 1 (:errors h 0)) "s"))
                     :else "System Pulse · Quiet & Healthy"))
         merge-lines (mapv (fn [m]
                             (let [aid (:id m)
                                   mtx (or (:main-tx m) (:merged-tx m))
                                   goal (clip-str (:goal m) 110)]
                               (str "- **" (friendly-kw aid) "**"
                                    (when (integer? mtx) (str " (`tx " mtx "`)"))
                                    (when-not (str/blank? goal) (str ": " goal)))))
                           (take 3 merges))
         ns-line (when (seq nss)
                   (str "- **Subsystems:** "
                        (str/join ", " (map #(str "`" (:ns %) "` (" (:forms % 0) ")") (take 5 nss)))
                        (when (> (count nss) 5) (str " +" (- (count nss) 5) " more"))
                        (when (seq schemas)
                          (str " · " (count schemas) " schema" (when (not= 1 (count schemas)) "s") " updated"))))
         health-line (str "- **Health:** "
                          (if (:ok? h true) "Healthy" (str (:errors h 0) " error(s)"))
                          (when (pos? (long (if (integer? (:warnings h)) (:warnings h) 0)))
                            (str " (" (:warnings h) " advisories)"))
                          (when (and lm (integer? (:merged lm)) (integer? (:attempted lm)) (pos? (:attempted lm)))
                            (str " · **Velocity:** " (:merged lm) "/" (:attempted lm) " merged"
                                 (when (integer? (:kv-cache-hit-pct lm))
                                   (str ", " (:kv-cache-hit-pct lm) "% KV-cache")))))
         body (or (when (and (string? (:body o)) (not (str/blank? (:body o)))) (:body o))
                  (str/join "\n" (remove nil? (concat merge-lines [ns-line health-line]))))
         refs (if (sequential? (:refs o)) (vec (:refs o)) (when (sequential? (:refs d)) (vec (:refs d))))]
     (first (record-summary-tx
             {:id (:id o)
              :kind (or (:kind o) :system-pulse)
              :title title
              :body body
              :from-tx (or (:from-tx o) (:from-tx d) 0)
              :to-tx (or (:to-tx o) (:to-tx d) 0)
              :created-ms (or (:created-ms o) (:to-ms d) 0)
              :agent (or (:agent o) :agent/root)
              :refs refs
              :visual (:visual o)})))))

(defn format-compaction-timeline
  "Pure token-budgeted chronological formatter for agent compaction memory. Keeps newest entries
   in detail, folds older ones to 1-line `tx A..B` anchors, and preserves `d/as-of` drill-down pointers."
  {:malli/schema [:=> [:cat [:sequential :map] [:? :map]] :map]
   :cost/ticks 2500000
   :cost/bytes 4194304}
  ([entries] (format-compaction-timeline entries {}))
  ([entries opts]
   (let [o (if (map? opts) opts {})
         max-chars (max 160 (long (if (integer? (:max-chars o)) (:max-chars o) 2400)))
         detail-n (max 1 (long (if (integer? (:detail-count o)) (:detail-count o) 3)))
         tail-note (when (and (string? (:tail-note o)) (not (str/blank? (:tail-note o))))
                     (str/trim (:tail-note o)))
         sorted (->> entries
                     (filter map?)
                     (sort-by (fn [e]
                                [(long (if (integer? (:seon.summary/from-tx e)) (:seon.summary/from-tx e) 0))
                                 (long (if (integer? (:seon.summary/to-tx e)) (:seon.summary/to-tx e) 0))
                                 (long (if (integer? (:seon.summary/created-ms e)) (:seon.summary/created-ms e) 0))]))
                     vec)
         total (count sorted)
         fmt-entry (fn [idx e]
                     (let [detailed? (>= idx (- total detail-n))
                           ts (format-epoch-utc (when (integer? (:seon.summary/created-ms e)) (:seon.summary/created-ms e)))
                           tx-s (tx-range-label (:seon.summary/from-tx e) (:seon.summary/to-tx e))
                           kind (if (keyword? (:seon.summary/kind e)) (name (:seon.summary/kind e)) "pulse")
                           title (clip-str (or (:seon.summary/title e) "Summary") 88)
                           body (when detailed? (clip-str (:seon.summary/body e) 200))
                           refs (when (sequential? (:seon.summary/refs e))
                                  (take 4 (:seon.summary/refs e)))
                           hdr (str "[" (str/join " · " (remove str/blank? [ts tx-s kind])) "] " title)]
                       (str "- " hdr
                            (when (seq refs) (str " (" (str/join ", " (map friendly-kw refs)) ")"))
                            (when (and detailed? (not (str/blank? body))) (str "\n  " body)))))
         rendered (mapv (fn [idx] {:entry (nth sorted idx) :line (fmt-entry idx (nth sorted idx))})
                        (range total))
         hdr-line "Chronicle timeline (query exact state via `(d/as-of (user/main-db) <to-tx>)`):"
         footer (when tail-note (str "- " tail-note))
         overhead (+ (count hdr-line) 1 (if footer (+ 1 (count footer)) 0))
         budget (max 64 (- max-chars overhead))
         [kept truncated?]
         (loop [idx (dec total) acc [] used 0]
           (if (< idx 0)
             [(vec (reverse acc)) false]
             (let [item (nth rendered idx)
                   len (+ (count (:line item)) (if (seq acc) 1 0))]
               (if (or (empty? acc) (<= (+ used len) budget))
                 (recur (dec idx) (conj acc item) (+ used len))
                 [(vec (reverse acc)) true]))))
         kept-entries (mapv :entry kept)
         lines (cond-> [hdr-line]
                 truncated? (conj (str "- … (" (- total (count kept)) " earlier entries omitted; inspect via `d/history`)"))
                 (seq kept) (into (map :line kept))
                 (and (empty? kept) (nil? footer)) (conj "- (no recorded summaries yet)")
                 footer (conj footer))
         raw-text (str/join "\n" lines)
         final-text (if (> (count raw-text) max-chars)
                      (str (subs raw-text 0 (max 0 (- max-chars 1))) "…")
                      raw-text)
         tx-vals (keep #(when (integer? %) (long %))
                       (concat (map :seon.summary/from-tx kept-entries)
                               (map :seon.summary/to-tx kept-entries)))]
     {:text final-text
      :entries kept-entries
      :from-tx (if (seq tx-vals) (apply min tx-vals) 0)
      :to-tx (if (seq tx-vals) (apply max tx-vals) 0)
      :truncated? (boolean (or truncated? (> (count raw-text) max-chars)))
      :chars (count final-text)})))

(defn merge-chronicle-entries
  "Unifies recorded `:seon.summary/*` entries with un-summarized recent `:main` merges into one
   deduplicated reverse-chronological timeline (newest first). Merges already covered by a recorded
   summary's `[from-tx .. to-tx]` window are omitted to avoid clutter."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] [:vector :map]]
   :cost/ticks 1500000
   :cost/bytes 2097152}
  [summaries recent-merges]
  (let [s-list (vec (filter map? summaries))
        covered? (fn [mtx]
                   (boolean
                    (and (integer? mtx)
                         (some (fn [s]
                                 (let [lo (:seon.summary/from-tx s)
                                       hi (:seon.summary/to-tx s)]
                                   (and (integer? lo) (integer? hi)
                                        (<= (long lo) (long mtx) (long hi)))))
                               s-list))))
        s-entries (mapv (fn [s]
                          {:entry-kind (if (keyword? (:seon.summary/kind s)) (:seon.summary/kind s) :system-pulse)
                           :source :summary
                           :id (:seon.summary/id s)
                           :title (or (:seon.summary/title s) "Summary")
                           :body (or (:seon.summary/body s) "")
                           :from-tx (long (if (integer? (:seon.summary/from-tx s)) (:seon.summary/from-tx s) 0))
                           :to-tx (long (if (integer? (:seon.summary/to-tx s)) (:seon.summary/to-tx s) 0))
                           :ms (long (if (integer? (:seon.summary/created-ms s)) (:seon.summary/created-ms s) 0))
                           :agent (when (keyword? (:seon.summary/agent s)) (:seon.summary/agent s))
                           :refs (if (sequential? (:seon.summary/refs s)) (vec (:seon.summary/refs s)) [])
                           :visual (:seon.summary/visual s)})
                        s-list)
        m-entries (->> recent-merges
                       (filter map?)
                       (keep (fn [m]
                               (let [mtx (or (:main-tx m) (:merged-tx m))]
                                 (when-not (covered? mtx)
                                   (let [aid (:id m)
                                         scope (:scope m)
                                         refs (vec (remove nil? [aid scope]))]
                                     {:entry-kind :merge
                                      :source :merge
                                      :id (or aid mtx)
                                      :title (friendly-kw (or aid :branch/merged))
                                      :body (str (or (:goal m) ""))
                                      :from-tx (long (if (integer? mtx) mtx 0))
                                      :to-tx (long (if (integer? mtx) mtx 0))
                                      :ms (long (if (integer? (:ms m)) (:ms m) 0))
                                      :agent (when (keyword? aid) aid)
                                      :refs refs}))))))]
    (->> (concat s-entries m-entries)
         (sort-by (fn [e] [(- (long (:to-tx e 0))) (- (long (:ms e 0))) (if (= :summary (:source e)) 0 1)]))
         vec)))

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
  "Distills system activity across `(from-tx .. to-tx]` directly from Datahike facts in a single
   pass (merged agents, touched namespaces/forms, updated schemas, health snapshot/delta, and optional
   learning-metrics snapshot)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] :map]
                  [:=> [:cat :seon.host/db :map] :map]
                  [:=> [:cat :seon.host/db [:maybe :int] [:maybe :int]] :map]]}
  ([main-db] (distill-tx-window main-db {}))
  ([main-db from-tx to-tx] (distill-tx-window main-db {:from-tx from-tx :to-tx to-tx}))
  ([main-db {:keys [from-tx to-tx conn include-health-delta? merge-limit]
             :or {include-health-delta? false merge-limit 12}}]
   (let [max-tx (long (or (:max-tx main-db) 0))
         hi (long (if (integer? to-tx) (min max-tx (long to-tx)) max-tx))
         eff-db (if (< hi max-tx) (try (d/as-of main-db hi) (catch Throwable _ main-db)) main-db)
         prev-to (when (nil? from-tx)
                   (:seon.summary/to-tx (first (latest-summaries eff-db {:limit 1 :until-tx (dec hi)}))))
         lo (long (cond
                    (integer? from-tx) (long from-tx)
                    (integer? prev-to) (long prev-to)
                    :else (max 0 (- hi 500))))
         merges (merged-agents-in-range eff-db lo hi (long (or merge-limit 12)))
         form-datoms (if (contains? (:schema eff-db) :seon.code.form/hash)
                       (vec (d/datoms eff-db :aevt :seon.code.form/hash))
                       [])
         touched-form-eids (into [] (comp (filter #(let [t (long (:tx %))] (and (> t lo) (<= t hi)))) (map :e)) form-datoms)
         ns-freqs (frequencies (keep #(:seon.code.form/ns (d/pull eff-db '[:seon.code.form/ns] %)) touched-form-eids))
         touched-nss (->> ns-freqs
                          (sort-by (fn [[nsym cnt]] [(- cnt) (str nsym)]))
                          (mapv (fn [[nsym cnt]] {:ns nsym :forms cnt})))
         schema-datoms (if (contains? (:schema eff-db) :seon.schema/form)
                         (vec (d/datoms eff-db :aevt :seon.schema/form))
                         [])
         touched-schemas (->> schema-datoms
                              (filter #(let [t (long (:tx %))] (and (> t lo) (<= t hi))))
                              (keep #(:seon.schema/key (d/pull eff-db '[:seon.schema/key] (:e %))))
                              distinct sort vec)
         h-now (health-counts eff-db)
         h-delta (when (and include-health-delta? (pos? lo) (< lo hi))
                   (when-let [prev-db (try (d/as-of main-db lo) (catch Throwable _ nil))]
                     (let [h-prev (health-counts prev-db)]
                       {:before h-prev
                        :after h-now
                        :delta-errors (- (:errors h-now) (:errors h-prev))
                        :delta-warnings (- (:warnings h-now) (:warnings h-prev))
                        :changed? (not= (:ok? h-now) (:ok? h-prev))})))
         lm (when conn
              (try
                (select-keys (hmetrics/agent-learning-metrics conn eff-db {:window 10})
                             [:attempted :merged :merge-rate-pct :effective-turns-per-merged
                              :median-turns-merged :kv-cache-hit-pct :eval-error-rate-pct])
                (catch Throwable _ nil)))
         refs (vec (distinct (concat (keep :id (take 5 merges))
                                     (map :ns (take 5 touched-nss)))))]
     (cond-> {:from-tx lo
              :to-tx hi
              :from-ms (tx-instant-ms main-db lo)
              :to-ms (tx-instant-ms main-db hi)
              :merges merges
              :touched-forms (count touched-form-eids)
              :touched-nss touched-nss
              :touched-schemas touched-schemas
              :total-forms (count form-datoms)
              :total-schemas (count schema-datoms)
              :health h-now
              :refs refs}
       h-delta (assoc :health-delta h-delta)
       lm (assoc :learning-metrics lm)))))
