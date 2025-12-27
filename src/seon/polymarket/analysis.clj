(ns seon.polymarket.analysis
  "Analysis functions for Polymarket trading data.

  Provides aggregation and summary functions for activity records downloaded
  via the API client. Designed to work with pre-loaded data to avoid
  repeatedly reading the large activity.edn file (142MB, 171k records)."
  (:require [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Basic Aggregations
;;; ---------------------------------------------------------------------------

(defn summarize-activity
  "Calculate summary statistics for activity data.

  Args:
    data - Sequence of activity records

  Returns:
    Map with:
      :total - Total number of records
      :by-type - Count by activity type (TRADE, REDEEM, etc.)
      :by-side - Count by side (BUY, SELL)
      :date-range - Earliest and latest timestamps
      :unique-markets - Count of unique markets (conditionIds)
      :unique-outcomes - Count of unique outcome strings

  Example:
    (summarize-activity (api/load-activity \"data/polymarket/rn1/activity.edn\"))"
  [data]
  (let [records (seq data)]
    (when records
      (let [timestamps (keep :timestamp records)
            min-ts (when (seq timestamps) (apply min timestamps))
            max-ts (when (seq timestamps) (apply max timestamps))]
        {:total (count records)
         :by-type (frequencies (map :type records))
         :by-side (frequencies (keep :side records))
         :date-range {:earliest-timestamp min-ts
                      :latest-timestamp max-ts
                      :earliest-date (when min-ts
                                       (java.time.Instant/ofEpochSecond min-ts))
                      :latest-date (when max-ts
                                     (java.time.Instant/ofEpochSecond max-ts))}
         :unique-markets (count (distinct (keep :conditionId records)))
         :unique-outcomes (count (distinct (keep :outcome records)))}))))

(defn group-by-market
  "Group activity records by market (conditionId).

  Args:
    data - Sequence of activity records

  Returns:
    Map from conditionId to vector of records for that market.

  Example:
    (def by-market (group-by-market data))
    (count by-market)  ;; Number of unique markets
    (count (get by-market \"0x899646...\"))  ;; Records for specific market"
  [data]
  (group-by :conditionId data))

(defn group-by-type
  "Group activity records by type (TRADE, REDEEM, etc.).

  Args:
    data - Sequence of activity records

  Returns:
    Map from type string to vector of records of that type.

  Example:
    (def by-type (group-by-type data))
    (count (:TRADE by-type))  ;; Number of trades
    (count (:REDEEM by-type)) ;; Number of redemptions"
  [data]
  (group-by (comp keyword :type) data))

(defn group-by-slug
  "Group activity records by market slug (human-readable name).

  Args:
    data - Sequence of activity records

  Returns:
    Map from slug to vector of records for that market.

  Example:
    (def by-slug (group-by-slug data))
    (keys by-slug)  ;; All market slugs"
  [data]
  (group-by :slug data))

;;; ---------------------------------------------------------------------------
;;; Financial Calculations
;;; ---------------------------------------------------------------------------

(defn calculate-totals
  "Calculate total volume and trade counts.

  Args:
    data - Sequence of activity records

  Returns:
    Map with:
      :total-volume-usdc - Sum of all usdcSize values
      :total-shares - Sum of all size values
      :total-trades - Count of TRADE records
      :total-redeems - Count of REDEEM records
      :buy-volume-usdc - Sum of usdcSize for BUY trades
      :sell-volume-usdc - Sum of usdcSize for SELL trades
      :buy-count - Number of BUY trades
      :sell-count - Number of SELL trades

  Example:
    (calculate-totals data)"
  [data]
  (let [records (seq data)
        trades (filter #(= "TRADE" (:type %)) records)
        redeems (filter #(= "REDEEM" (:type %)) records)
        buys (filter #(= "BUY" (:side %)) trades)
        sells (filter #(= "SELL" (:side %)) trades)]
    {:total-volume-usdc (reduce + 0 (keep :usdcSize records))
     :total-shares (reduce + 0 (keep :size records))
     :total-trades (count trades)
     :total-redeems (count redeems)
     :buy-volume-usdc (reduce + 0 (keep :usdcSize buys))
     :sell-volume-usdc (reduce + 0 (keep :usdcSize sells))
     :buy-count (count buys)
     :sell-count (count sells)}))

(defn market-summary
  "Calculate summary for a single market.

  Args:
    market-records - Sequence of activity records for one market

  Returns:
    Map with market title, slug, trade counts, and volume."
  [market-records]
  (let [first-record (first market-records)
        trades (filter #(= "TRADE" (:type %)) market-records)
        redeems (filter #(= "REDEEM" (:type %)) market-records)]
    {:title (:title first-record)
     :slug (:slug first-record)
     :condition-id (:conditionId first-record)
     :total-records (count market-records)
     :total-trades (count trades)
     :total-redeems (count redeems)
     :volume-usdc (reduce + 0 (keep :usdcSize market-records))
     :outcomes (distinct (keep :outcome market-records))}))

(defn top-markets-by-volume
  "Get the top N markets by USDC volume.

  Args:
    data - Sequence of activity records
    n - Number of top markets to return (default: 10)

  Returns:
    Vector of market summaries sorted by volume descending."
  ([data] (top-markets-by-volume data 10))
  ([data n]
   (->> (group-by-market data)
        vals
        (map market-summary)
        (sort-by :volume-usdc >)
        (take n)
        vec)))

(defn top-markets-by-trades
  "Get the top N markets by trade count.

  Args:
    data - Sequence of activity records
    n - Number of top markets to return (default: 10)

  Returns:
    Vector of market summaries sorted by trade count descending."
  ([data] (top-markets-by-trades data 10))
  ([data n]
   (->> (group-by-market data)
        vals
        (map market-summary)
        (sort-by :total-trades >)
        (take n)
        vec)))

;;; ---------------------------------------------------------------------------
;;; Time-based Analysis
;;; ---------------------------------------------------------------------------

(defn- timestamp->date-str
  "Convert Unix timestamp to date string (YYYY-MM-DD)."
  [ts]
  (when ts
    (-> (java.time.Instant/ofEpochSecond ts)
        (.atZone (java.time.ZoneId/of "UTC"))
        (.toLocalDate)
        str)))

(defn group-by-date
  "Group activity records by date.

  Args:
    data - Sequence of activity records

  Returns:
    Map from date string (YYYY-MM-DD) to vector of records."
  [data]
  (->> data
       (group-by #(timestamp->date-str (:timestamp %)))))

(defn daily-volume
  "Calculate daily USDC volume.

  Args:
    data - Sequence of activity records

  Returns:
    Map from date string to total volume for that day, sorted by date."
  [data]
  (->> (group-by-date data)
       (map (fn [[date records]]
              [date (reduce + 0 (keep :usdcSize records))]))
       (sort-by first)
       (into (sorted-map))))

(defn daily-trade-count
  "Calculate daily trade counts.

  Args:
    data - Sequence of activity records

  Returns:
    Map from date string to trade count for that day, sorted by date."
  [data]
  (->> (group-by-date data)
       (map (fn [[date records]]
              [date (count (filter #(= "TRADE" (:type %)) records))]))
       (sort-by first)
       (into (sorted-map))))

;;; ---------------------------------------------------------------------------
;;; Outcome Analysis
;;; ---------------------------------------------------------------------------

(defn outcome-summary
  "Summarize trading activity by outcome within a market.

  Args:
    market-records - Sequence of activity records for one market

  Returns:
    Map from outcome to {:buys N :sells N :volume-usdc N}"
  [market-records]
  (->> (filter #(= "TRADE" (:type %)) market-records)
       (group-by :outcome)
       (map (fn [[outcome records]]
              (let [buys (filter #(= "BUY" (:side %)) records)
                    sells (filter #(= "SELL" (:side %)) records)]
                [outcome {:buys (count buys)
                          :sells (count sells)
                          :buy-volume (reduce + 0 (keep :usdcSize buys))
                          :sell-volume (reduce + 0 (keep :usdcSize sells))
                          :avg-buy-price (when (seq buys)
                                           (/ (reduce + 0 (keep :price buys))
                                              (count buys)))
                          :avg-sell-price (when (seq sells)
                                            (/ (reduce + 0 (keep :price sells))
                                               (count sells)))}])))
       (into {})))

;;; ---------------------------------------------------------------------------
;;; Rich Comment Block - REPL Exploration
;;; ---------------------------------------------------------------------------

(comment
  ;; Load data ONCE per REPL session (takes 30+ seconds for 171k records)
  (require '[seon.polymarket.api :as api])
  (def data (api/load-activity "data/polymarket/rn1/activity.edn"))
  (count data)

  ;; Basic summary
  (summarize-activity data)

  ;; Group by type
  (def by-type (group-by-type data))
  (keys by-type)
  (count (:TRADE by-type))
  (count (:REDEEM by-type))

  ;; Calculate totals
  (calculate-totals data)

  ;; Top markets by volume
  (top-markets-by-volume data 5)

  ;; Top markets by trade count
  (top-markets-by-trades data 5)

  ;; Daily volume (recent days)
  (->> (daily-volume data)
       (take-last 10))

  ;; Daily trade count (recent days)
  (->> (daily-trade-count data)
       (take-last 10))

  ;; Group by market slug
  (def by-slug (group-by-slug data))
  (count by-slug)  ;; Number of unique markets

  ;; Analyze a specific market
  (def nfl-market (get by-slug "nfl-chiefs-texans-2025-01-18"))
  (market-summary nfl-market)
  (outcome-summary nfl-market))
