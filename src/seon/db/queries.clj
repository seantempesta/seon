(ns seon.db.queries
  "SQL query builders for financial data.

  XTDB v2 uses SQL as the primary query language for stability and performance.
  All queries use parameterized SQL to prevent injection.

  Provides composable query functions for:
  - Options chain queries
  - Historical IV surface queries
  - Greeks snapshots
  - Bitemporal time-travel queries"
  (:require [seon.db.node :as node]
            [xtdb.api :as xt]))

;;; ---------------------------------------------------------------------------
;;; Options Queries
;;; ---------------------------------------------------------------------------

(defn options-chain
  "Get the options chain for a ticker.

  Args:
    node - XTDB node
    ticker - Underlying ticker symbol
    opts - Optional map with:
           :expiry - Specific expiration date
           :current-time - Valid-time for temporal query

  Returns:
    Sequence of option quotes"
  ([node ticker]
   (options-chain node ticker {}))
  ([node ticker opts]
   (let [{:keys [expiry current-time]} opts
         query-opts (cond-> {:key-fn :kebab-case-keyword}
                      current-time (assoc :current-time current-time))]
     (if expiry
       (xt/q node
             ["SELECT _id, option$id, option$strike, option$type,
                      quote$iv, greeks$delta, greeks$gamma, greeks$theta, greeks$vega,
                      quote$bid, quote$ask
               FROM option_greeks
               WHERE asset$ticker = ? AND option$expiry = ?"
              ticker expiry]
             query-opts)
       (xt/q node
             ["SELECT _id, option$id, option$strike, option$type, option$expiry,
                      quote$iv, greeks$delta, greeks$gamma, greeks$theta, greeks$vega,
                      quote$bid, quote$ask
               FROM option_greeks
               WHERE asset$ticker = ?"
              ticker]
             query-opts)))))

(defn atm-options
  "Get at-the-money options for a ticker.

  Args:
    node - XTDB node
    ticker - Underlying ticker symbol
    spot - Current spot price
    tolerance - Strike tolerance (default 5%)
    opts - Optional map with :current-time

  Returns:
    Sequence of ATM option quotes"
  ([node ticker spot]
   (atm-options node ticker spot 0.05 {}))
  ([node ticker spot tolerance]
   (atm-options node ticker spot tolerance {}))
  ([node ticker spot tolerance opts]
   (let [lower (* spot (- 1 tolerance))
         upper (* spot (+ 1 tolerance))
         query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts)))]
     (xt/q node
           ["SELECT _id, option$id, option$strike, option$type, option$expiry,
                    quote$iv, greeks$delta, greeks$gamma, greeks$theta, greeks$vega,
                    quote$bid, quote$ask
             FROM option_greeks
             WHERE asset$ticker = ?
               AND option$strike >= ?
               AND option$strike <= ?"
            ticker lower upper]
           query-opts))))

(defn option-by-occ
  "Get a specific option by OCC symbol.

  Args:
    node - XTDB node
    occ-symbol - OCC option symbol (e.g., 'AAPL230616C00150000')
    opts - Optional map with :current-time

  Returns:
    Option quote or nil"
  ([node occ-symbol]
   (option-by-occ node occ-symbol {}))
  ([node occ-symbol opts]
   (let [query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts)))]
     (first
      (xt/q node
            ["SELECT _id, asset$ticker, option$strike, option$type, option$expiry,
                     quote$iv, greeks$delta, greeks$gamma, greeks$theta, greeks$vega,
                     quote$bid, quote$ask
              FROM option_greeks
              WHERE option$id = ?"
             occ-symbol]
            query-opts)))))

;;; ---------------------------------------------------------------------------
;;; Greeks Queries
;;; ---------------------------------------------------------------------------

(defn high-gamma-options
  "Find options with high gamma (for gamma scalping).

  Args:
    node - XTDB node
    ticker - Underlying ticker symbol
    min-gamma - Minimum gamma threshold
    opts - Optional map with :current-time

  Returns:
    Sequence of high-gamma options"
  ([node ticker min-gamma]
   (high-gamma-options node ticker min-gamma {}))
  ([node ticker min-gamma opts]
   (let [query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts)))]
     (xt/q node
           ["SELECT _id, option$id, option$strike, option$type, option$expiry,
                    quote$iv, greeks$delta, greeks$gamma, greeks$theta, greeks$vega,
                    quote$bid, quote$ask
             FROM option_greeks
             WHERE asset$ticker = ?
               AND greeks$gamma >= ?"
            ticker min-gamma]
           query-opts))))

(defn options-by-delta
  "Find options within a delta range.

  Useful for finding specific strikes (e.g., 25-delta puts for skew).

  Args:
    node - XTDB node
    ticker - Underlying ticker symbol
    option-type - :call or :put
    delta-min - Minimum delta
    delta-max - Maximum delta
    opts - Optional map with :current-time

  Returns:
    Sequence of options in delta range"
  ([node ticker option-type delta-min delta-max]
   (options-by-delta node ticker option-type delta-min delta-max {}))
  ([node ticker option-type delta-min delta-max opts]
   (let [query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts)))]
     (xt/q node
           ["SELECT _id, option$id, option$strike, option$expiry,
                    quote$iv, greeks$delta, greeks$gamma, greeks$theta, greeks$vega,
                    quote$bid, quote$ask
             FROM option_greeks
             WHERE asset$ticker = ?
               AND option$type = ?
               AND greeks$delta >= ?
               AND greeks$delta <= ?"
            ticker option-type delta-min delta-max]
           query-opts))))

;;; ---------------------------------------------------------------------------
;;; IV Surface Queries
;;; ---------------------------------------------------------------------------

(defn iv-surface
  "Get the IV surface for a ticker.

  Args:
    node - XTDB node
    ticker - Underlying ticker symbol
    opts - Optional map with :current-time

  Returns:
    IV surface entity or nil"
  ([node ticker]
   (iv-surface node ticker {}))
  ([node ticker opts]
   (let [query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts)))]
     (first
      (xt/q node
            ["SELECT _id, timestamp, surface_data, atm_iv, term_structure, skew
              FROM iv_surfaces
              WHERE ticker = ?"
             ticker]
            query-opts)))))

(defn iv-term-structure
  "Get IV term structure (ATM IV across expirations).

  Args:
    node - XTDB node
    ticker - Underlying ticker symbol
    opts - Optional map with :current-time

  Returns:
    Sequence of {:expiry :iv} maps sorted by expiry"
  ([node ticker]
   (iv-term-structure node ticker {}))
  ([node ticker opts]
   (let [query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts)))
         ;; ATM options have delta near 0.5 (calls) or -0.5 (puts)
         results (xt/q node
                       ["SELECT option$expiry, quote$iv, greeks$delta
                         FROM option_greeks
                         WHERE asset$ticker = ?
                           AND greeks$delta > 0.4
                           AND greeks$delta < 0.6
                         ORDER BY option$expiry"
                        ticker]
                       query-opts)]
     (map (fn [row]
            {:expiry (:option/expiry row) :iv (:quote/iv row)})
          results))))

;;; ---------------------------------------------------------------------------
;;; Bitemporal Queries
;;; ---------------------------------------------------------------------------

(defn entity-at-time
  "Get an entity as it was known at a specific time.

  This is the core 'T0 environment' query for the Student LLM.

  Args:
    node - XTDB node
    table - Table keyword (e.g., :option-quotes)
    id - Entity ID
    valid-time - The business time (when the fact was true)
    system-time - Optional: when the data was recorded

  Returns:
    Entity map as of the specified time"
  ([node table id valid-time]
   (entity-at-time node table id valid-time nil))
  ([node table id valid-time system-time]
   (node/entity node table id
                {:current-time valid-time
                 :snapshot-time system-time})))

(defn query-at-time
  "Execute a query as of a specific time.

  Args:
    node - XTDB node
    query-form - SQL query to execute
    args - Query arguments
    valid-time - The business time
    system-time - Optional: when the data was recorded

  Returns:
    Query results as of the specified time"
  ([node query-form args valid-time]
   (query-at-time node query-form args valid-time nil))
  ([node query-form args valid-time system-time]
   (node/query node query-form
               {:current-time valid-time
                :snapshot-time system-time})))

;;; ---------------------------------------------------------------------------
;;; Historical Queries (for DSL primitives)
;;; ---------------------------------------------------------------------------

(defn historical-ivs
  "Get historical IV values for a ticker.

  Used by iv-rank and iv-percentile primitives.

  Args:
    node - XTDB node
    ticker - Underlying ticker symbol
    lookback-days - Number of days to look back

  Returns:
    Sequence of IV values"
  [node ticker lookback-days]
  (let [;; Calculate the start date for lookback
        now (java.time.Instant/now)
        start-date (.minus now lookback-days java.time.temporal.ChronoUnit/DAYS)
        ;; Query all valid-time history within the lookback period
        results (xt/q node
                      ["SELECT asset$ticker, quote$iv, greeks$delta, _valid_from, _valid_to
                        FROM option_greeks FOR ALL VALID_TIME
                        WHERE asset$ticker = ?
                          AND greeks$delta > 0.4
                          AND greeks$delta < 0.6
                          AND _valid_from >= ?"
                       ticker start-date]
                      {:key-fn :kebab-case-keyword})]
    (map :quote/iv results)))

(defn aggregate-open-interest
  "Aggregate open interest by strike for a ticker.

  Args:
    node - XTDB node
    ticker - Underlying ticker symbol
    opts - Optional map with :current-time

  Returns:
    Sequence of {:strike :oi} maps"
  ([node ticker]
   (aggregate-open-interest node ticker {}))
  ([node ticker opts]
   ;; Note: open_interest is not available in our :option-greeks schema
   ;; This function is a placeholder and will return empty results
   (let [query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts)))]
     (xt/q node
           ["SELECT option$strike
             FROM option_greeks
             WHERE asset$ticker = ?
             ORDER BY option$strike"
            ticker]
           query-opts))))
