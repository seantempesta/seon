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
