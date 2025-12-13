(ns ml-options.data.validation
  "Validation layer for option Greeks and quote data.

  Validation Philosophy:
  - Missing value (nil) = OK (optional data, illiquid options)
  - Impossible value = REJECT (data corruption)
  - Unusual value = WARN but accept (edge cases exist)

  Provides:
  - Range validation for Greeks (delta, gamma, vega, theta, rho, IV)
  - Bid/ask sanity checks (only when both present)
  - Delta sign validation (calls >= 0, puts <= 0)
  - Rejection reason tracking for diagnostics

  Ranges based on AGENT_GUIDELINES.md and standard options theory."
  (:require [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Quiet Mode (suppress per-record logging in bulk loads)
;;; ---------------------------------------------------------------------------

(def ^:dynamic *quiet* false)

;;; ---------------------------------------------------------------------------
;;; Rejection Reason Tracking
;;; ---------------------------------------------------------------------------

(def rejection-counts
  "Atom tracking rejection reasons during bulk loads.
  Reset before each load, check after for diagnostics."
  (atom {}))

(defn reset-rejection-counts!
  "Reset rejection tracking. Call before starting a bulk load."
  []
  (reset! rejection-counts {}))

(defn track-rejection!
  "Track a rejection reason."
  [reason]
  (swap! rejection-counts update reason (fnil inc 0)))

(defn get-rejection-summary
  "Get summary of all rejection reasons."
  []
  @rejection-counts)

;;; ---------------------------------------------------------------------------
;;; Greeks Range Constants
;;; ---------------------------------------------------------------------------

(def greeks-ranges
  "Valid ranges for option Greeks.

  These are HARD limits - values outside are impossible/corrupted.
  Note: Vega and Rho are post /100 scaling (ThetaData divides by 100).

  Theta: Expanded to allow small positive values (edge cases near expiry).
  IV: Deep ITM options (delta = ±1.0) can have IV=0.0 (handled separately)."
  {:delta [-1.0 1.0]       ; -1 (deep ITM put) to +1 (deep ITM call)
   :gamma [0.0 50.0]       ; Non-negative, expanded max for edge cases
   :vega [0.0 200.0]       ; Post /100 scaling, expanded for long-dated options
   :theta [-100.0 10.0]    ; Usually negative, but small positive possible near expiry
   :rho [-200.0 200.0]     ; Post /100 scaling, expanded range
   :iv [0.0 10.0]})        ; 0% to 1000% IV (0.0 allowed for deep ITM, checked separately)

;;; ---------------------------------------------------------------------------
;;; Validation Functions
;;; ---------------------------------------------------------------------------

(defn validate-greeks
  "Validate that Greeks are within expected ranges.

  Logic:
  - nil value → PASS (optional field)
  - value in range → PASS
  - value out of range → FAIL

  Special case: IV=0.0 is allowed for deep ITM options (|delta|=1.0)

  Args:
    record - Map containing Greeks fields (:greeks/delta, :greeks/gamma, etc.)

  Returns:
    {:valid? boolean :errors [error-msgs] :warnings [warning-msgs]}"
  [record]
  (let [errors (atom [])
        warnings (atom [])
        delta (:greeks/delta record)
        iv (:quote/iv record)]

    ;; Check IV special case first
    (when (some? iv)
      (let [[min-iv max-iv] (:iv greeks-ranges)
            is-deep-itm? (and (some? delta)
                              (or (>= delta 0.99) (<= delta -0.99)))  ; Allow near ±1.0
            iv-valid? (or (and (= iv 0.0) is-deep-itm?)  ; IV=0 OK for deep ITM
                          (and (> iv 0.0) (<= iv max-iv)))]  ; Normal range (excluding 0)
        (when-not iv-valid?
          (let [reason (if (= iv 0.0)
                         :iv-zero-not-deep-itm
                         :iv-out-of-range)]
            (track-rejection! reason)
            (swap! errors conj
                   (format "IV invalid: %.4f (delta=%.2f, deep-ITM=%s)"
                           iv (or delta 0.0) is-deep-itm?))
            (when-not *quiet*
              (log/warn "IV validation failed"
                        {:iv iv :delta delta :deep-itm? is-deep-itm?
                         :record-id (:xt/id record)}))))))

    ;; Check other Greeks (skip IV, handled above)
    (doseq [[greek-key [min-val max-val]] {:greeks/delta (:delta greeks-ranges)
                                           :greeks/gamma (:gamma greeks-ranges)
                                           :greeks/vega (:vega greeks-ranges)
                                           :greeks/theta (:theta greeks-ranges)
                                           :greeks/rho (:rho greeks-ranges)}]
      (when-let [value (get record greek-key)]
        (when (or (< value min-val) (> value max-val))
          (let [reason (keyword (str (name greek-key) "-out-of-range"))]
            (track-rejection! reason)
            (swap! errors conj
                   (format "%s out of range: %.4f not in [%.2f, %.2f]"
                           (name greek-key) value min-val max-val))
            (when-not *quiet*
              (log/warn "Greek out of range"
                        {:field greek-key
                         :value value
                         :range [min-val max-val]
                         :record-id (:xt/id record)}))))))

    {:valid? (empty? @errors)
     :errors @errors
     :warnings @warnings}))

(defn validate-bid-ask
  "Validate bid/ask prices when BOTH are present.

  Logic:
  - Both nil → PASS (illiquid option, no quotes - normal)
  - One nil, one present → PASS with warning (partial quote)
  - bid > ask → FAIL (impossible, data corruption)
  - bid <= ask → PASS (valid quote)

  Args:
    record - Map with :quote/bid and :quote/ask

  Returns:
    {:valid? boolean :warnings [warning-msgs]}"
  [record]
  (let [bid (:quote/bid record)
        ask (:quote/ask record)]
    (cond
      ;; Both nil - fine, illiquid option
      (and (nil? bid) (nil? ask))
      {:valid? true :warnings []}

      ;; One present, one nil - unusual but OK
      (or (nil? bid) (nil? ask))
      {:valid? true
       :warnings [(format "Partial quote: bid=%s ask=%s" bid ask)]}

      ;; Both present but bid > ask - invalid
      (> bid ask)
      (do
        (track-rejection! :bid-greater-than-ask)
        (when-not *quiet*
          (log/warn "Invalid quote: bid > ask"
                    {:record-id (:xt/id record) :bid bid :ask ask}))
        {:valid? false
         :warnings []})

      ;; Both present and valid
      :else
      {:valid? true :warnings []})))

(defn validate-delta-sign
  "Validate delta sign matches option type.

  Logic:
  - nil delta → PASS (no validation possible)
  - Call with delta >= 0 → PASS
  - Put with delta <= 0 → PASS
  - Mismatch → FAIL (data corruption)

  Args:
    record - Map with :option/type and :greeks/delta

  Returns:
    {:valid? boolean :errors [error-msgs]}"
  [record]
  (let [option-type (:option/type record)
        delta (:greeks/delta record)]
    (cond
      ;; No delta, skip validation
      (nil? delta)
      {:valid? true :errors []}

      ;; No option type, skip validation
      (nil? option-type)
      {:valid? true :errors []}

      ;; Call with negative delta (allow small negative for rounding)
      (and (= option-type :call) (< delta -0.001))
      (do
        (track-rejection! :call-negative-delta)
        (when-not *quiet*
          (log/warn "Delta sign mismatch" {:record-id (:xt/id record)
                                           :type option-type
                                           :delta delta}))
        {:valid? false
         :errors [(format "Call option has negative delta: %.4f" delta)]})

      ;; Put with positive delta (allow small positive for rounding)
      (and (= option-type :put) (> delta 0.001))
      (do
        (track-rejection! :put-positive-delta)
        (when-not *quiet*
          (log/warn "Delta sign mismatch" {:record-id (:xt/id record)
                                           :type option-type
                                           :delta delta}))
        {:valid? false
         :errors [(format "Put option has positive delta: %.4f" delta)]})

      ;; Valid
      :else {:valid? true :errors []})))

(defn validate-required-fields
  "Validate that essential fields exist.

  Only truly required fields:
  - :xt/id - must have an ID
  - :option/id - OCC symbol

  Args:
    record - Option quote map

  Returns:
    {:valid? boolean :errors [error-msgs]}"
  [record]
  (let [errors (atom [])]
    (when (nil? (:xt/id record))
      (track-rejection! :missing-xt-id)
      (swap! errors conj "Missing :xt/id"))
    (when (nil? (:option/id record))
      (track-rejection! :missing-option-id)
      (swap! errors conj "Missing :option/id"))
    {:valid? (empty? @errors)
     :errors @errors}))

(defn validate-record
  "Compose all validation checks for a single record.

  Validation is STRICT for data integrity:
  - Missing optional fields → OK
  - Impossible values → REJECT

  Args:
    record - Option quote map to validate

  Returns:
    {:valid? boolean :warnings [warning-msgs] :errors [error-msgs]}"
  [record]
  (let [;; Required fields
        required-result (validate-required-fields record)

        ;; Bid/ask validation
        bid-ask-result (validate-bid-ask record)

        ;; Greeks range validation
        greeks-result (validate-greeks record)

        ;; Delta sign validation
        delta-result (validate-delta-sign record)

        ;; Combine all errors
        all-errors (into []
                         (concat
                          (:errors required-result)
                          (:errors greeks-result)
                          (:errors delta-result)
                          (when-not (:valid? bid-ask-result)
                            ["bid > ask"])))

        ;; Combine all warnings
        all-warnings (into []
                           (concat
                            (:warnings bid-ask-result)
                            (:warnings greeks-result)))]
    {:valid? (and (:valid? required-result)
                  (:valid? bid-ask-result)
                  (:valid? greeks-result)
                  (:valid? delta-result))
     :warnings all-warnings
     :errors all-errors}))

(defn filter-valid-records
  "Filter a sequence of records, keeping only valid ones.

  Logs summary of filtered records.

  Args:
    records - Sequence of option quote maps

  Returns:
    Vector of valid records only"
  [records]
  (let [total (count records)
        results (map (fn [record]
                       [record (validate-record record)])
                     records)
        valid-records (into []
                            (comp
                             (filter (fn [[_record validation]]
                                       (:valid? validation)))
                             (map first))
                            results)
        filtered-count (- total (count valid-records))]
    (when (pos? filtered-count)
      (log/info "Records filtered"
                {:total total
                 :valid (count valid-records)
                 :filtered filtered-count
                 :filter-rate (format "%.2f%%" (* 100.0 (/ filtered-count (max 1 total))))}))
    valid-records))

(comment
  ;; === Test validation with sample records ===

  ;; Reset tracking
  (reset-rejection-counts!)

  ;; Valid record with all fields
  (def valid-record
    {:xt/id "TEST-001"
     :option/id "AAPL20250117C00230000"
     :option/type :call
     :quote/bid 2.5
     :quote/ask 2.6
     :greeks/delta 0.52
     :greeks/gamma 0.05
     :greeks/vega 0.45
     :greeks/theta -0.15
     :greeks/rho 0.25
     :quote/iv 0.35})

  (validate-record valid-record)
  ;; => {:valid? true :warnings [] :errors []}

  ;; Valid record with NO bid/ask (illiquid option - should PASS now)
  (def illiquid-record
    {:xt/id "TEST-002"
     :option/id "AAPL20250117C00500000"
     :option/type :call
     :quote/bid nil
     :quote/ask nil
     :greeks/delta 0.02})

  (validate-record illiquid-record)
  ;; => {:valid? true :warnings [] :errors []}

  ;; Record with bid > ask (should FAIL)
  (def bad-quote-record
    {:xt/id "TEST-003"
     :option/id "AAPL20250117C00230000"
     :option/type :call
     :quote/bid 2.8
     :quote/ask 2.6})

  (validate-record bad-quote-record)
  ;; => {:valid? false :errors ["bid > ask"]}

  ;; Deep ITM with IV=0.0 (should PASS)
  (def deep-itm-record
    {:xt/id "TEST-004"
     :option/id "AAPL20250117C00165000"
     :option/type :call
     :quote/bid 69.3
     :quote/ask 70.6
     :greeks/delta 1.0
     :quote/iv 0.0})

  (validate-record deep-itm-record)
  ;; => {:valid? true}

  ;; ATM with IV=0.0 (should FAIL)
  (def atm-zero-iv-record
    {:xt/id "TEST-005"
     :option/id "AAPL20250117C00230000"
     :option/type :call
     :quote/bid 5.0
     :quote/ask 5.5
     :greeks/delta 0.5
     :quote/iv 0.0})

  (validate-record atm-zero-iv-record)
  ;; => {:valid? false :errors ["IV invalid: ..."]}

  ;; Positive theta (edge case near expiry - should PASS now)
  (def positive-theta-record
    {:xt/id "TEST-006"
     :option/id "AAPL20250117P00200000"
     :option/type :put
     :quote/bid 0.01
     :quote/ask 0.02
     :greeks/delta -0.01
     :greeks/theta 0.5})  ; Small positive theta near expiry

  (validate-record positive-theta-record)
  ;; => {:valid? true}

  ;; Check rejection summary
  (get-rejection-summary)
  ;; => {:iv-zero-not-deep-itm 1, :bid-greater-than-ask 1, ...}
  )
