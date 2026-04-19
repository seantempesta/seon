(ns seon.trading.agent.functions
  "Agent-facing trading functions.

   All functions follow the pattern: (fn ctx {:option-key value})
   Returns maps with :seon.type/name identifying the result type.

   These wrap seon.trading.signals primitives with agent-friendly
   interfaces and automatic pretty-printing.

   NOTE: These functions require a real ctx atom with a db node,
   so they use regular defn (not mx/defn) to avoid generative testing
   which can't provide real infrastructure."
  (:require [malli.core :as m]
            [seon.trading.signals :as signals]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(def Ctx
  "Schema for the session context atom (deref'd value)."
  [:map
   [:db/node :any]
   [:session/id :string]
   [:session/frozen-time {:optional true} inst?]
   [:config/default-lookback {:optional true} pos-int?]])

(def Ticker
  "Stock ticker symbol."
  [:string {:min 1 :max 10}])

(def Lookback
  "Number of trading days to look back."
  [:int {:min 1 :max 1000}])

(def IVRankResult
  "Result of iv-rank calculation."
  [:map
   [:seon.type/name [:= :iv-rank]]
   [:iv-rank/ticker Ticker]
   [:iv-rank/value [:double {:min 0.0 :max 1.0}]]
   [:iv-rank/label [:enum :low :normal :elevated :high]]
   [:iv-rank/lookback Lookback]])

(def SkewResult
  "Result of skew calculation."
  [:map
   [:seon.type/name [:= :skew]]
   [:skew/ticker Ticker]
   [:skew/value :double]
   [:skew/label [:enum :cheap-puts :normal :expensive-puts]]])

(def TermStructureResult
  "Result of term structure calculation."
  [:map
   [:seon.type/name [:= :term-structure]]
   [:term-structure/ticker Ticker]
   [:term-structure/slope :double]
   [:term-structure/label [:enum :backwardation :flat :contango]]])

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn- iv-rank-label
  "Convert IV rank value to descriptive label."
  [value]
  (cond
    (< value 0.25) :low
    (< value 0.50) :normal
    (< value 0.75) :elevated
    :else :high))

(defn- skew-label
  "Convert skew value to descriptive label."
  [value]
  (cond
    (< value -0.02) :cheap-puts
    (> value 0.05)  :expensive-puts
    :else :normal))

(defn- term-structure-label
  "Convert term structure slope to descriptive label."
  [value]
  (cond
    (< value -0.0001) :backwardation
    (> value 0.0001)  :contango
    :else :flat))

;;; ---------------------------------------------------------------------------
;;; Agent Functions
;;; ---------------------------------------------------------------------------

(defn iv-rank
  "IV percentile rank - where current IV sits vs history.

   Returns 0.0-1.0:
     0.0 = IV lower than all history (cheap options)
     0.5 = IV at median (fair value)
     1.0 = IV higher than all history (expensive options)

   Labels:
     :low      - Below 25th percentile
     :normal   - 25th to 50th percentile
     :elevated - 50th to 75th percentile
     :high     - Above 75th percentile

   Options:
     :ticker   - Required. Uppercase symbol, e.g., \"SPY\"
     :lookback - Optional. Days of history. Default from ctx or 252"
  [ctx opts]
  (let [{:keys [db/node config/default-lookback session/frozen-time]} @ctx
        {:keys [ticker lookback]} opts
        lookback (or lookback default-lookback 252)
        as-of-opts (when frozen-time {:as-of frozen-time})
        value (signals/iv-rank node ticker lookback as-of-opts)]
    {:seon.type/name :iv-rank
     :iv-rank/ticker ticker
     :iv-rank/value value
     :iv-rank/label (iv-rank-label value)
     :iv-rank/lookback lookback}))

(defn skew
  "Put-call skew index - relative cost of downside vs upside protection.

   Measures the IV spread between 25-delta puts and 25-delta calls.

   Interpretation:
     Positive = puts more expensive (normal, market hedging)
     Negative = calls more expensive (unusual, bullish sentiment)

   Labels:
     :cheap-puts     - Skew < -2% (unusual)
     :normal         - Skew between -2% and 5%
     :expensive-puts - Skew > 5% (fear in market)

   Options:
     :ticker - Required. Uppercase symbol, e.g., \"SPY\""
  [ctx opts]
  (let [{:keys [db/node session/frozen-time]} @ctx
        {:keys [ticker]} opts
        as-of-opts (when frozen-time {:as-of frozen-time})
        value (signals/skew-index node ticker as-of-opts)]
    {:seon.type/name :skew
     :skew/ticker ticker
     :skew/value value
     :skew/label (skew-label value)}))

(defn term-structure
  "IV term structure slope - relationship between near and far IV.

   Measures how IV changes across expiration dates.

   Interpretation:
     Positive slope = contango (far IV > near IV, normal)
     Negative slope = backwardation (near IV > far IV, event fear)

   Labels:
     :backwardation - Slope < -0.01% per day
     :flat          - Slope between -0.01% and 0.01%
     :contango      - Slope > 0.01% per day

   Options:
     :ticker - Required. Uppercase symbol, e.g., \"SPY\""
  [ctx opts]
  (let [{:keys [db/node session/frozen-time]} @ctx
        {:keys [ticker]} opts
        as-of-opts (when frozen-time {:as-of frozen-time})
        value (signals/term-structure-slope node ticker as-of-opts)]
    {:seon.type/name :term-structure
     :term-structure/ticker ticker
     :term-structure/slope value
     :term-structure/label (term-structure-label value)}))

;;; ---------------------------------------------------------------------------
;;; Function Registry (for discovery)
;;; ---------------------------------------------------------------------------

(def functions
  "Registry of agent-facing functions with metadata."
  {:iv-rank {:fn iv-rank
             :doc (-> #'iv-rank meta :doc)
             :args [:ticker :lookback]}
   :skew {:fn skew
          :doc (-> #'skew meta :doc)
          :args [:ticker]}
   :term-structure {:fn term-structure
                    :doc (-> #'term-structure meta :doc)
                    :args [:ticker]}})

(defn list-functions
  "List available agent functions with their documentation."
  []
  (mapv (fn [[k v]]
          {:name (name k)
           :args (:args v)
           :doc (first (clojure.string/split-lines (or (:doc v) "")))})
        functions))

;;; ---------------------------------------------------------------------------
;;; Comment Block
;;; ---------------------------------------------------------------------------

(comment
  ;; Test with a session
  (require '[seon.trading.agent.session :as sess])

  (def ctx (sess/create-session nil {:goal "Test functions"}))

  ;; These will fail without a real db, but show the interface
  (iv-rank ctx {:ticker "SPY"})
  (iv-rank ctx {:ticker "SPY" :lookback 60})
  (skew ctx {:ticker "SPY"})
  (term-structure ctx {:ticker "SPY"})

  ;; List available functions
  (list-functions)

  nil)
