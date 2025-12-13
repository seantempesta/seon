(ns seon.db.queries
  "XTQL query builders for financial data.

  XTDB v2 uses XTQL (composable, data-oriented queries) instead of Datalog.
  All queries execute directly on the node - no 'db value' concept.

  XTQL Patterns:
  - Use inline binding {:field ~value} in (from) for equality filters
  - Use xt/template macro to inject dynamic values
  - Use (where) for range conditions (>, <, >=, <=)

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
   (let [{:keys [expiry current-time]} opts]
     (if expiry
       (node/query node
                   (xt/template
                    (-> (from :option-greeks [{:asset/ticker ~ticker :option/expiry ~expiry}
                                              xt/id option/id option/strike option/type
                                              quote/iv greeks/delta greeks/gamma greeks/theta greeks/vega
                                              quote/bid quote/ask])))
                   {:current-time current-time})
       (node/query node
                   (xt/template
                    (-> (from :option-greeks [{:asset/ticker ~ticker}
                                              xt/id option/id option/strike option/type option/expiry
                                              quote/iv greeks/delta greeks/gamma greeks/theta greeks/vega
                                              quote/bid quote/ask])))
                   {:current-time current-time})))))

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
         upper (* spot (+ 1 tolerance))]
     (node/query node
                 (xt/template
                  (-> (from :option-greeks [{:asset/ticker ~ticker}
                                            xt/id option/id option/strike option/type option/expiry
                                            quote/iv greeks/delta greeks/gamma greeks/theta greeks/vega
                                            quote/bid quote/ask])
                      (where (>= option/strike ~lower)
                             (<= option/strike ~upper))))
                 {:current-time (:current-time opts)}))))

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
   (first
    (node/query node
                (xt/template
                 (-> (from :option-greeks [{:option/id ~occ-symbol}
                                           xt/id asset/ticker option/strike option/type option/expiry
                                           quote/iv greeks/delta greeks/gamma greeks/theta greeks/vega
                                           quote/bid quote/ask])))
                {:current-time (:current-time opts)}))))

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
   (node/query node
               (xt/template
                (-> (from :option-greeks [{:asset/ticker ~ticker}
                                          xt/id option/id option/strike option/type option/expiry
                                          quote/iv greeks/delta greeks/gamma greeks/theta greeks/vega
                                          quote/bid quote/ask])
                    (where (>= greeks/gamma ~min-gamma))))
               {:current-time (:current-time opts)})))

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
   (node/query node
               (xt/template
                (-> (from :option-greeks [{:asset/ticker ~ticker :option/type ~option-type}
                                          xt/id option/id option/strike option/expiry
                                          quote/iv greeks/delta greeks/gamma greeks/theta greeks/vega
                                          quote/bid quote/ask])
                    (where (>= greeks/delta ~delta-min)
                           (<= greeks/delta ~delta-max))))
               {:current-time (:current-time opts)})))

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
   (first
    (node/query node
                (xt/template
                 (-> (from :iv-surfaces [{:ticker ~ticker}
                                         xt/id timestamp surface_data
                                         atm_iv term_structure skew])))
                {:current-time (:current-time opts)}))))

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
   (->> (node/query node
                    (xt/template
                     (-> (from :option-greeks [{:asset/ticker ~ticker}
                                               option/expiry quote/iv greeks/delta])
                         ;; ATM options have delta near 0.5 (calls) or -0.5 (puts)
                         (where (> greeks/delta 0.4)
                                (< greeks/delta 0.6))
                         (order-by option/expiry)))
                    {:current-time (:current-time opts)})
        (map (fn [row]
               {:expiry (:option/expiry row) :iv (:quote/iv row)})))))

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
    query-form - XTQL or SQL query to execute
    args - Query arguments (unused in XTQL - use xt/template)
    valid-time - The business time
    system-time - Optional: when the data was recorded

  Returns:
    Query results as of the specified time"
  ([node query-form _args valid-time]
   (query-at-time node query-form nil valid-time nil))
  ([node query-form _args valid-time system-time]
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
        results (node/query node
                            (xt/template
                             (-> (from :option-greeks
                                       {:for-valid-time :all-time
                                        :bind [asset/ticker quote/iv greeks/delta
                                               xt/valid-from xt/valid-to]})
                                 ;; Filter by ticker and delta range
                                 (where (= asset/ticker ~ticker)
                                        (> greeks/delta 0.4)
                                        (< greeks/delta 0.6)
                                        ;; Filter to lookback period
                                        (>= xt/valid-from ~start-date)))))]
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
   (node/query node
               (xt/template
                (-> (from :option-greeks [{:asset/ticker ~ticker}
                                          option/strike])
                    (order-by option/strike)))
               {:current-time (:current-time opts)})))
