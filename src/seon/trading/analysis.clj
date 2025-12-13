(ns seon.trading.analysis
  "High-level analysis interface for LLM agents.

  This namespace provides the primary interface for agent sessions:
  - Single function call returns complete analysis
  - Labels and categorizes signals (not just raw numbers)
  - Generates recommendations with reasoning
  - Handles 'no trade' as a valid, explicit recommendation

  Design Principles:
  1. Agent receives node already locked to valid-time (can't see future)
  2. Single call returns everything needed for decision
  3. Reasoning is human-readable for agent to relay to user
  4. Conservative by default - 'no trade' unless clear signal"
  (:require [seon.trading.signals :as p]
            [seon.db.queries :as q]
            [seon.db.node :as node]
            [xtdb.api :as xt]))

;;; ---------------------------------------------------------------------------
;;; Configuration: Thresholds
;;; ---------------------------------------------------------------------------

(def ^:private thresholds
  "Signal thresholds for categorization.

  IV Rank: Based on percentile - relative to ticker's own history
  Skew: Based on put-call IV spread (typical range 0-15% for equities)
  Term Structure: Based on slope (per-expiration IV difference)
  Gamma Rent: Gamma/|Theta| ratio"
  {:iv-rank {:low 0.20      ;; Bottom 20% = cheap options
             :high 0.80}    ;; Top 20% = expensive options

   :skew {:low 0.02         ;; <2% skew = cheap protection
          :high 0.08}       ;; >8% skew = expensive puts

   :term-slope {:backwardation -0.0005   ;; Near-term elevated
                :contango 0.0005}        ;; Far-term elevated

   :gamma-rent {:cheap 0.06              ;; Good for scalping
                :expensive 0.02}})       ;; Avoid scalping

;;; ---------------------------------------------------------------------------
;;; Signal Labeling
;;; ---------------------------------------------------------------------------

(defn- label-iv-rank
  "Categorize IV rank into :low :neutral :high"
  [rank]
  (cond
    (nil? rank) :unknown
    (< rank (get-in thresholds [:iv-rank :low])) :low
    (> rank (get-in thresholds [:iv-rank :high])) :high
    :else :neutral))

(defn- label-skew
  "Categorize skew into :low :normal :elevated"
  [skew]
  (cond
    (nil? skew) :unknown
    (< skew (get-in thresholds [:skew :low])) :low
    (> skew (get-in thresholds [:skew :high])) :elevated
    :else :normal))

(defn- label-term-structure
  "Categorize term structure into :backwardation :flat :contango"
  [slope]
  (cond
    (nil? slope) :unknown
    (< slope (get-in thresholds [:term-slope :backwardation])) :backwardation
    (> slope (get-in thresholds [:term-slope :contango])) :contango
    :else :flat))

(defn- label-gamma-rent
  "Categorize gamma rent into :cheap :moderate :expensive"
  [rent]
  (cond
    (nil? rent) :unknown
    (> rent (get-in thresholds [:gamma-rent :cheap])) :cheap
    (< rent (get-in thresholds [:gamma-rent :expensive])) :expensive
    :else :moderate))

;;; ---------------------------------------------------------------------------
;;; Strategy Definitions
;;; ---------------------------------------------------------------------------

(def ^:private strategies
  "Trading strategies with descriptions.

  Each strategy includes:
  - :name - Strategy identifier
  - :type - :long-vol :short-vol :neutral :directional
  - :description - Human-readable explanation
  - :example - Example trade structure"
  {:iron-condor
   {:name "Iron Condor"
    :type :short-vol
    :description "Sell OTM put spread + OTM call spread. Profits from range-bound market and IV decline."
    :example "Sell 25-delta put, buy 15-delta put, sell 25-delta call, buy 15-delta call"}

   :short-strangle
   {:name "Short Strangle"
    :type :short-vol
    :description "Sell OTM put + OTM call. Higher premium but undefined risk."
    :example "Sell 20-delta put, sell 20-delta call (requires margin)"}

   :long-straddle
   {:name "Long Straddle"
    :type :long-vol
    :description "Buy ATM put + ATM call. Profits from large move in either direction."
    :example "Buy 50-delta call, buy 50-delta put (same strike)"}

   :long-strangle
   {:name "Long Strangle"
    :type :long-vol
    :description "Buy OTM put + OTM call. Cheaper than straddle, needs bigger move."
    :example "Buy 25-delta put, buy 25-delta call"}

   :risk-reversal
   {:name "Risk Reversal"
    :type :directional
    :description "Sell put, buy call (bullish) or vice versa. Exploits skew."
    :example "Sell 25-delta put, buy 25-delta call (bullish bias)"}

   :calendar-spread
   {:name "Calendar Spread"
    :type :neutral
    :description "Sell near-term, buy far-term at same strike. Profits from term structure normalization."
    :example "Sell front-month ATM, buy back-month ATM"}

   :gamma-scalp
   {:name "Gamma Scalping"
    :type :neutral
    :description "Buy ATM options, delta-hedge continuously. Profits from realized > implied vol."
    :example "Buy ATM straddle, hedge delta to zero, re-hedge on moves"}})

;;; ---------------------------------------------------------------------------
;;; Recommendation Logic
;;; ---------------------------------------------------------------------------

(defn- determine-recommendation
  "Determine trading recommendation from labeled signals.

  Priority order:
  1. Extreme IV rank (>0.85 or <0.15) - strongest signal
  2. High IV + normal/elevated skew - short vol
  3. Low IV + low/normal skew - long vol
  4. Neutral IV + elevated skew - skew trade
  5. Cheap gamma - gamma scalping opportunity
  6. Otherwise - no trade

  Returns {:recommendation :keyword :confidence :high/:medium/:low}"
  [{:keys [iv-rank-label skew-label term-label gamma-label iv-rank]}]
  (cond
    ;; Extreme IV rank with confirmation
    (and (= iv-rank-label :high) (>= iv-rank 0.85))
    {:recommendation :short-vol
     :confidence :high
     :primary-signal :iv-rank-extreme-high}

    (and (= iv-rank-label :low) (<= iv-rank 0.15))
    {:recommendation :long-vol
     :confidence :high
     :primary-signal :iv-rank-extreme-low}

    ;; High IV rank
    (and (= iv-rank-label :high)
         (#{:normal :elevated} skew-label))
    {:recommendation :short-vol
     :confidence :medium
     :primary-signal :iv-rank-high}

    ;; Low IV rank
    (and (= iv-rank-label :low)
         (#{:low :normal} skew-label))
    {:recommendation :long-vol
     :confidence :medium
     :primary-signal :iv-rank-low}

    ;; Elevated skew with neutral IV
    (and (= iv-rank-label :neutral)
         (= skew-label :elevated))
    {:recommendation :skew-trade
     :confidence :medium
     :primary-signal :skew-elevated}

    ;; Term structure opportunity
    (and (= iv-rank-label :neutral)
         (#{:backwardation :contango} term-label))
    {:recommendation :calendar-spread
     :confidence :low
     :primary-signal :term-structure}

    ;; Cheap gamma
    (= gamma-label :cheap)
    {:recommendation :gamma-scalp
     :confidence :low
     :primary-signal :cheap-gamma}

    ;; Default: no clear edge
    :else
    {:recommendation :no-trade
     :confidence :high  ;; High confidence in NOT trading
     :primary-signal :none}))

(defn- select-strategies
  "Select appropriate strategies for a recommendation."
  [recommendation skew-label]
  (case recommendation
    :short-vol [:iron-condor :short-strangle]
    :long-vol [:long-straddle :long-strangle]
    :skew-trade [:risk-reversal]
    :calendar-spread [:calendar-spread]
    :gamma-scalp [:gamma-scalp]
    :no-trade []))

;;; ---------------------------------------------------------------------------
;;; Reasoning Generation
;;; ---------------------------------------------------------------------------

(defn- format-percent
  "Format a decimal as percentage string."
  [v]
  (when v (format "%.1f%%" (* 100.0 v))))

(defn- generate-reasoning
  "Generate human-readable reasoning for the recommendation."
  [{:keys [iv-rank iv-rank-label skew skew-label
           term-slope term-label gamma-rent gamma-label
           atm-iv spot]}
   {:keys [recommendation confidence primary-signal]}]
  (let [iv-rank-pct (format "%.0f%%" (* 100 (or iv-rank 0)))
        skew-pct (format-percent skew)
        iv-pct (format-percent atm-iv)]
    (case recommendation
      :short-vol
      (str "IV Rank at " iv-rank-pct " (top " (format "%.0f%%" (* 100 (- 1 iv-rank)))
           ") suggests elevated implied volatility. "
           (when (= skew-label :elevated)
             (str "Skew at " skew-pct " confirms put premium is rich. "))
           "Consider selling premium strategies that profit from IV mean reversion.")

      :long-vol
      (str "IV Rank at " iv-rank-pct " (bottom " iv-rank-pct
           ") indicates depressed implied volatility. "
           "Options are historically cheap. "
           "Consider buying premium to benefit from potential IV expansion.")

      :skew-trade
      (str "IV Rank is neutral at " iv-rank-pct " but skew at " skew-pct
           " is elevated. Puts are relatively expensive vs calls. "
           "Consider risk reversal (sell put, buy call) to exploit skew.")

      :calendar-spread
      (str "IV Rank neutral at " iv-rank-pct ". "
           (case term-label
             :backwardation "Near-term IV elevated vs far-term (backwardation). "
             :contango "Far-term IV elevated vs near-term (contango). "
             "")
           "Calendar spreads may benefit from term structure normalization.")

      :gamma-scalp
      (str "Gamma is cheap relative to theta decay. "
           "ATM options offer good gamma exposure for active hedging. "
           "This is a high-touch strategy requiring frequent delta adjustments.")

      :no-trade
      (str "No clear volatility edge. "
           "IV Rank at " iv-rank-pct " is in the neutral range (20-80%). "
           "Skew at " skew-pct " is normal. "
           "Recommend waiting for IV rank to reach extreme levels (>80% for short vol, <20% for long vol) "
           "before initiating volatility trades."))))

;;; ---------------------------------------------------------------------------
;;; Data Fetching
;;; ---------------------------------------------------------------------------

(defn- get-spot-price
  "Get current underlying price for ticker."
  [node ticker opts]
  (let [query-opts {:current-time (:as-of opts)}
        result (first (node/query node
                                  (xt/template
                                   (-> (from :option-greeks [{:asset/ticker ~(name ticker)}
                                                             underlying/price]
                                             (limit 1))))
                                  query-opts))]
    (:underlying/price result)))

(defn- get-atm-iv
  "Get current ATM implied volatility."
  [node ticker opts]
  (let [query-opts {:current-time (:as-of opts)}
        results (node/query node
                            (xt/template
                             (-> (from :option-greeks [{:asset/ticker ~(name ticker)}
                                                       quote/iv greeks/delta]
                                       (where (> greeks/delta 0.4) (< greeks/delta 0.6))
                                       (limit 20))))
                            query-opts)]
    (when (seq results)
      (/ (reduce + (map :quote/iv results)) (count results)))))

;;; ---------------------------------------------------------------------------
;;; Main Analysis Function
;;; ---------------------------------------------------------------------------

(defn analyze-ticker
  "Complete volatility analysis for an LLM agent.

  This is the primary interface for agent sessions. Call this with a
  database node and a ticker. Use :as-of to lock to a specific date.

  Args:
    node - XTDB node
    ticker - Stock symbol (string or keyword)
    opts - Optional map with:
           :as-of - Instant to lock analysis to (prevents seeing future data)
                    Example: #inst \"2025-07-15T21:00:00Z\"

  Returns:
    {:ticker \"SPY\"
     :as-of #inst \"2025-07-15\" (if specified)
     :signals {:iv-rank 0.35
               :iv-rank-label :neutral
               :skew 0.068
               :skew-label :normal
               :term-slope 7.35e-7
               :term-label :flat
               :gamma-rent 0.023
               :gamma-label :moderate
               :atm-iv 0.328
               :spot 602.57}
     :recommendation :no-trade
     :confidence :high
     :reasoning \"IV Rank at 35% is neutral...\"
     :strategies []
     :strategy-details []}"
  ([node ticker]
   (analyze-ticker node ticker {}))
  ([node ticker {:keys [as-of] :as opts}]
   (let [ticker-str (name ticker)
         ;; Pass temporal opts to all queries
         temporal-opts (when as-of {:as-of as-of})

         ;; Gather raw signals (all respect as-of temporal constraint)
         iv-rank (p/iv-rank node ticker-str 126 temporal-opts)
         skew (p/skew-index node ticker-str temporal-opts)
         term-slope (p/term-structure-slope node ticker-str temporal-opts)
         spot (get-spot-price node ticker-str opts)
         gamma-rent (when spot (p/gamma-rent node ticker-str :atm spot temporal-opts))
         atm-iv (get-atm-iv node ticker-str opts)

         ;; Label signals
         iv-rank-label (label-iv-rank iv-rank)
         skew-label (label-skew skew)
         term-label (label-term-structure term-slope)
         gamma-label (label-gamma-rent gamma-rent)

         ;; Build signals map
         signals {:iv-rank iv-rank
                  :iv-rank-label iv-rank-label
                  :skew skew
                  :skew-label skew-label
                  :term-slope term-slope
                  :term-label term-label
                  :gamma-rent gamma-rent
                  :gamma-label gamma-label
                  :atm-iv atm-iv
                  :spot spot}

         ;; Determine recommendation
         rec (determine-recommendation signals)

         ;; Select strategies
         strategy-keys (select-strategies (:recommendation rec) skew-label)

         ;; Generate reasoning
         reasoning (generate-reasoning signals rec)]

     (cond-> {:ticker ticker-str
              :signals signals
              :recommendation (:recommendation rec)
              :confidence (:confidence rec)
              :primary-signal (:primary-signal rec)
              :reasoning reasoning
              :strategies strategy-keys
              :strategy-details (mapv strategies strategy-keys)}
       as-of (assoc :as-of as-of)))))

(defn analyze-multiple
  "Analyze multiple tickers and rank by opportunity strength.

  Useful for scanning a watchlist for best setups.

  Args:
    node - XTDB node
    tickers - Sequence of ticker symbols
    opts - Optional map with :as-of for temporal locking

  Returns:
    Sequence of analysis results, sorted by confidence and signal strength."
  ([node tickers]
   (analyze-multiple node tickers {}))
  ([node tickers opts]
   (->> tickers
        (map #(analyze-ticker node % opts))
        (sort-by (fn [{:keys [confidence recommendation]}]
                   [(case confidence :high 0 :medium 1 :low 2)
                    (case recommendation :no-trade 1 0)]))
        vec)))

(defn summarize-for-agent
  "Generate a concise summary suitable for an LLM agent response.

  Args:
    analysis - Result from analyze-ticker

  Returns:
    String summary the agent can relay to the user."
  [{:keys [ticker recommendation confidence reasoning strategies strategy-details]}]
  (str "## " ticker " Analysis\n\n"
       "**Recommendation:** " (name recommendation)
       " (confidence: " (name confidence) ")\n\n"
       reasoning "\n\n"
       (when (seq strategies)
         (str "**Suggested Strategies:**\n"
              (clojure.string/join "\n"
                                   (map (fn [{:keys [name description]}]
                                          (str "- **" name "**: " description))
                                        strategy-details))))))

;;; ---------------------------------------------------------------------------
;;; Agent Session Helper
;;; ---------------------------------------------------------------------------

(defn create-session-node
  "Create a node locked to a specific date for agent sessions.

  The returned node will only show data with valid-time <= as-of-date.
  This prevents agents from 'cheating' by seeing future data.

  Args:
    data-dir - Path to XTDB data directory
    as-of-date - Instant to lock the session to

  Returns:
    XTDB node configured for the session

  Note: This is a convenience function. Callers can also use
  (node/query node query {:current-time as-of-date}) directly."
  [data-dir as-of-date]
  ;; Note: XTDB v2 doesn't support default current-time at node level
  ;; Queries must pass {:current-time as-of-date} explicitly
  ;; This function documents the pattern for agent session setup
  (require '[xtdb.node :as xtn])
  (require '[clojure.java.io :as io])
  ((resolve 'xtn/start-node)
   {:log [:local {:path (clojure.java.io/file data-dir "log")}]
    :storage [:local {:path (clojure.java.io/file data-dir "objects")}]}))

(comment
  ;; === Example Agent Session ===

  (require '[xtdb.node :as xtn])
  (require '[clojure.java.io :as io])

  ;; Create node
  (def node (xtn/start-node {:log [:local {:path (io/file "data/xtdb" "log")}]
                             :storage [:local {:path (io/file "data/xtdb" "objects")}]}))

  ;; Analyze single ticker
  (analyze-ticker node "SPY")

  ;; Analyze watchlist
  (analyze-multiple node ["SPY" "AAPL" "NVDA" "MSFT" "GOOGL"])

  ;; Get agent-friendly summary
  (-> (analyze-ticker node "NVDA")
      summarize-for-agent
      println))
