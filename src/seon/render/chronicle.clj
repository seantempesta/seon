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
