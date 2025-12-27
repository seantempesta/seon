(ns seon.polymarket.api
  "Polymarket Data API client.

  Connects to the Polymarket Data API at data-api.polymarket.com.
  Provides functions for fetching:
  - Trading activity (buys, sells, redemptions)
  - Trade history
  - Current positions
  - Total position value

  No authentication required for read access.
  Max limit per request is 500.

  Reference: https://docs.polymarket.com/developers/CLOB/trades/trades-data-api"
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def base-url
  "Polymarket Data API base URL."
  "https://data-api.polymarket.com")

(def default-timeout-ms
  "Default HTTP timeout in milliseconds."
  30000)

(def max-limit
  "Maximum records per request."
  500)

;;; ---------------------------------------------------------------------------
;;; HTTP Utilities
;;; ---------------------------------------------------------------------------

(defn- make-request
  "Make an HTTP GET request to Polymarket Data API.

  Args:
    endpoint - API endpoint path (e.g., '/activity')
    params - Query parameters as a map

  Returns:
    Parsed JSON response as Clojure data (vector for lists), or nil on error"
  [endpoint params]
  (try
    (let [url (str base-url endpoint)
          ;; Remove nil values from params
          clean-params (into {} (remove (comp nil? val) params))
          response (http/get url
                             {:query-params clean-params
                              :timeout default-timeout-ms
                              :as :text
                              :http-client {:redirect-policy :normal}})]
      (case (:status response)
        200 (let [parsed (json/parse-string (:body response) true)]
              ;; Convert sequences to vectors for consistency
              (if (sequential? parsed)
                (vec parsed)
                parsed))
        (do
          (log/error "Polymarket API request failed"
                     {:endpoint endpoint
                      :status (:status response)
                      :body (:body response)})
          nil)))
    (catch Exception e
      (log/error e "Polymarket API request exception" {:endpoint endpoint :params params})
      nil)))

;;; ---------------------------------------------------------------------------
;;; Public API Functions
;;; ---------------------------------------------------------------------------

(defn fetch-activity
  "Fetch trading activity for a wallet address.

  Activity includes all on-chain events: trades, redemptions, etc.

  Args:
    wallet - Ethereum wallet address (hex string starting with 0x)
    opts - Optional parameters map:
      :limit - Number of records (default: 100, max: 500)
      :offset - Pagination offset (default: 0)
      :type - Filter by activity type (e.g., 'TRADE', 'REDEEM')
      :start - Start timestamp (ISO 8601)
      :end - End timestamp (ISO 8601)

  Returns:
    Vector of activity records, or nil on error

  Example:
    (fetch-activity \"0x2005d16a84ceefa912d4e380cd32e7ff827875ea\" {:limit 10})"
  ([wallet] (fetch-activity wallet {}))
  ([wallet {:keys [limit offset type start end]
            :or {limit 100 offset 0}}]
   (make-request "/activity"
                 {:user wallet
                  :limit (min limit max-limit)
                  :offset offset
                  :type type
                  :start start
                  :end end})))

(defn fetch-trades
  "Fetch trade history for a wallet address.

  Trades are a subset of activity - only buy/sell events.

  Args:
    wallet - Ethereum wallet address (hex string starting with 0x)
    opts - Optional parameters map:
      :limit - Number of records (default: 100, max: 500)
      :offset - Pagination offset (default: 0)
      :side - Filter by side ('BUY' or 'SELL')
      :market - Filter by market ID

  Returns:
    Vector of trade records, or nil on error

  Example:
    (fetch-trades \"0x2005d16a84ceefa912d4e380cd32e7ff827875ea\" {:limit 10})"
  ([wallet] (fetch-trades wallet {}))
  ([wallet {:keys [limit offset side market]
            :or {limit 100 offset 0}}]
   (make-request "/trades"
                 {:user wallet
                  :limit (min limit max-limit)
                  :offset offset
                  :side side
                  :market market})))

(defn fetch-positions
  "Fetch current positions for a wallet address.

  Returns all open positions with their current values.

  Args:
    wallet - Ethereum wallet address (hex string starting with 0x)
    opts - Optional parameters map:
      :sizeThreshold - Minimum position size to include
      :sortBy - Sort field (e.g., 'value', 'size')

  Returns:
    Vector of position records, or nil on error

  Example:
    (fetch-positions \"0x2005d16a84ceefa912d4e380cd32e7ff827875ea\")"
  ([wallet] (fetch-positions wallet {}))
  ([wallet {:keys [sizeThreshold sortBy]}]
   (make-request "/positions"
                 {:user wallet
                  :sizeThreshold sizeThreshold
                  :sortBy sortBy})))

(defn fetch-value
  "Fetch total position value for a wallet address.

  Returns the sum of all open position values in USDC.

  Args:
    wallet - Ethereum wallet address (hex string starting with 0x)

  Returns:
    Map with :value and :user keys, or nil on error

  Example:
    (fetch-value \"0x2005d16a84ceefa912d4e380cd32e7ff827875ea\")"
  [wallet]
  (let [result (make-request "/value" {:user wallet})]
    ;; API returns a vector with one map, extract the map
    (when (and result (vector? result) (seq result))
      (first result))))

;;; ---------------------------------------------------------------------------
;;; Pagination Helpers
;;; ---------------------------------------------------------------------------

(def ^:private page-delay-ms
  "Delay between paginated requests to avoid rate limiting."
  100)

(defn- paginate
  "Generic pagination helper. Returns a lazy sequence of all records.

  Args:
    fetch-fn - Function that takes offset and returns a page of records
    page-size - Number of records per page (default: max-limit)

  Returns:
    Lazy sequence of all records"
  ([fetch-fn] (paginate fetch-fn max-limit))
  ([fetch-fn page-size]
   (letfn [(fetch-page [offset page-num]
             (when (pos? page-num)
               (Thread/sleep page-delay-ms))
             (log/info "Fetching page" {:page page-num :offset offset})
             (let [records (fetch-fn offset)]
               (when (seq records)
                 (lazy-cat records
                           (when (= (count records) page-size)
                             (fetch-page (+ offset page-size) (inc page-num)))))))]
     (fetch-page 0 1))))

;;; ---------------------------------------------------------------------------
;;; Paginated Fetch Functions
;;; ---------------------------------------------------------------------------

(defn fetch-all-activity
  "Fetch all trading activity for a wallet address.

  Returns a lazy sequence that automatically paginates through all records.
  Each page fetches 500 records.

  Args:
    wallet - Ethereum wallet address (hex string starting with 0x)
    opts - Optional parameters map:
      :type - Filter by activity type (e.g., 'TRADE', 'REDEEM')
      :start - Start timestamp (ISO 8601)
      :end - End timestamp (ISO 8601)

  Returns:
    Lazy sequence of all activity records

  Example:
    (take 10 (fetch-all-activity \"0x2005d16a84ceefa912d4e380cd32e7ff827875ea\"))
    (count (fetch-all-activity \"0x2005d16a84ceefa912d4e380cd32e7ff827875ea\"))"
  ([wallet] (fetch-all-activity wallet {}))
  ([wallet {:keys [type start end]}]
   (paginate
    (fn [offset]
      (fetch-activity wallet {:limit max-limit
                              :offset offset
                              :type type
                              :start start
                              :end end})))))

(defn fetch-all-trades
  "Fetch all trades for a wallet address.

  Returns a lazy sequence that automatically paginates through all records.
  Each page fetches 500 records.

  Args:
    wallet - Ethereum wallet address (hex string starting with 0x)
    opts - Optional parameters map:
      :side - Filter by side ('BUY' or 'SELL')
      :market - Filter by market ID

  Returns:
    Lazy sequence of all trade records

  Example:
    (take 10 (fetch-all-trades \"0x2005d16a84ceefa912d4e380cd32e7ff827875ea\"))
    (count (fetch-all-trades \"0x2005d16a84ceefa912d4e380cd32e7ff827875ea\"))"
  ([wallet] (fetch-all-trades wallet {}))
  ([wallet {:keys [side market]}]
   (paginate
    (fn [offset]
      (fetch-trades wallet {:limit max-limit
                            :offset offset
                            :side side
                            :market market})))))

;;; ---------------------------------------------------------------------------
;;; EDN Persistence
;;; ---------------------------------------------------------------------------

(defn- ensure-parent-dir!
  "Ensure the parent directory of a path exists."
  [path]
  (let [parent (.getParentFile (io/file path))]
    (when parent
      (.mkdirs parent))))

(defn save-activity!
  "Download all activity for a wallet and save to EDN file.

  This realizes the full lazy sequence and writes all records to disk.
  Progress is logged during download.

  Args:
    wallet - Ethereum wallet address
    path - Path to save EDN file

  Returns:
    Map with :count and :path keys

  Example:
    (save-activity! \"0x2005...\" \"data/polymarket/rn1/activity.edn\")"
  [wallet path]
  (log/info "Starting activity download" {:wallet wallet :path path})
  (ensure-parent-dir! path)
  (let [start-time (System/currentTimeMillis)
        activity (doall (fetch-all-activity wallet))
        count (count activity)
        elapsed-s (/ (- (System/currentTimeMillis) start-time) 1000.0)]
    (spit path (pr-str activity))
    (log/info "Activity download complete"
              {:count count
               :elapsed-seconds elapsed-s
               :path path})
    {:count count :path path}))

(defn save-positions!
  "Download current positions for a wallet and save to EDN file.

  Args:
    wallet - Ethereum wallet address
    path - Path to save EDN file

  Returns:
    Map with :count and :path keys

  Example:
    (save-positions! \"0x2005...\" \"data/polymarket/rn1/positions.edn\")"
  [wallet path]
  (log/info "Fetching positions" {:wallet wallet :path path})
  (ensure-parent-dir! path)
  (let [positions (fetch-positions wallet)
        count (count positions)]
    (spit path (pr-str positions))
    (log/info "Positions saved" {:count count :path path})
    {:count count :path path}))

(defn load-activity
  "Load activity records from an EDN file.

  Args:
    path - Path to EDN file

  Returns:
    Vector of activity records, or nil if file doesn't exist

  Example:
    (load-activity \"data/polymarket/rn1/activity.edn\")"
  [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn load-positions
  "Load positions from an EDN file.

  Args:
    path - Path to EDN file

  Returns:
    Vector of position records, or nil if file doesn't exist

  Example:
    (load-positions \"data/polymarket/rn1/positions.edn\")"
  [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

;;; ---------------------------------------------------------------------------
;;; Health Check
;;; ---------------------------------------------------------------------------

(defn health-check
  "Check if Polymarket Data API is reachable.

  Returns:
    true if API is responding, false otherwise"
  []
  (try
    (let [response (http/get (str base-url "/value")
                             {:query-params {:user "0x0000000000000000000000000000000000000000"}
                              :timeout 5000
                              :as :text})]
      (= 200 (:status response)))
    (catch Exception e
      (log/error "Polymarket Data API is not reachable" {:error (.getMessage e)})
      false)))

(comment
  ;; Health check - verify API is reachable
  (health-check)

  ;; RN1 wallet address
  (def rn1-wallet "0x2005d16a84ceefa912d4e380cd32e7ff827875ea")

  ;; Fetch recent activity (first 10 records)
  (fetch-activity rn1-wallet {:limit 10})

  ;; Fetch trades only
  (fetch-trades rn1-wallet {:limit 10})

  ;; Fetch current positions
  (fetch-positions rn1-wallet)

  ;; Fetch total position value
  (fetch-value rn1-wallet)

  ;; Pagination example
  (fetch-activity rn1-wallet {:limit 100 :offset 0})   ; First page
  (fetch-activity rn1-wallet {:limit 100 :offset 100}) ; Second page

  ;; Filter by type
  (fetch-activity rn1-wallet {:limit 10 :type "TRADE"})

  ;; ---------------------------------------------------------------------------
  ;; Full Paginated Fetch (Stage 2)
  ;; ---------------------------------------------------------------------------

  ;; Fetch ALL activity (lazy sequence, paginates automatically)
  (count (fetch-all-activity rn1-wallet))  ; Should return 13000+ records

  ;; Take first 10 from lazy sequence (only fetches first page)
  (take 10 (fetch-all-activity rn1-wallet))

  ;; Fetch all trades (lazy)
  (count (fetch-all-trades rn1-wallet))

  ;; Save all activity to EDN (realizes full sequence)
  (save-activity! rn1-wallet "data/polymarket/rn1/activity.edn")

  ;; Save current positions to EDN
  (save-positions! rn1-wallet "data/polymarket/rn1/positions.edn")

  ;; Load saved data
  (count (load-activity "data/polymarket/rn1/activity.edn"))
  (count (load-positions "data/polymarket/rn1/positions.edn")))
