(ns seon.dev.suggestions
  "Symbol suggestion module using Levenshtein distance.

   Provides 'Did you mean?' suggestions for unresolved symbols.
   Uses the same algorithm as Malli's spell-checking.

   Main functions:
   - suggest-symbol - Find closest match for an unknown symbol
   - enrich-findings - Add suggestions to clj-kondo unresolved-symbol findings

   Example usage:
     (require '[seon.dev.suggestions :as suggestions])

     ;; Find suggestion for misspelled symbol
     (suggestions/suggest-symbol
       {::suggestions/target \"undefiend\"
        ::suggestions/candidates [\"undefined\" \"define\" \"defn\"]})
     ;; => {::suggestions/suggestion \"undefined\" ::suggestions/distance 2}

     ;; Enrich lint findings with suggestions
     (suggestions/enrich-findings
       {::suggestions/findings [{:type :unresolved-symbol :message \"...\"}]
        ::suggestions/available-symbols [\"foo\" \"bar\"]})"
  (:require [clojure.string :as str]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

(schema/register! ::target
                  [:string {:description "The unknown symbol to find suggestions for"}])

(schema/register! ::candidates
                  [:vector {:description "Available symbols to search through"}
                   [:or :string :symbol :keyword]])

(schema/register! ::suggestion
                  [:maybe {:description "The suggested symbol, or nil if none found"} :string])

(schema/register! ::distance
                  [:maybe {:description "Levenshtein distance to suggestion, or nil"} :int])

(schema/register! ::max-distance
                  [:int {:min 1 :description "Maximum acceptable edit distance"}])

(schema/register! ::suggest-request
                  [:map
                   [::target ::target]
                   [::candidates ::candidates]
                   [::max-distance {:optional true} ::max-distance]])

(schema/register! ::suggest-symbol-response
                  [:map
                   [::suggestion ::suggestion]
                   [::distance ::distance]])

;;; ---------------------------------------------------------------------------
;;; Levenshtein Distance (from Malli's implementation)
;;; ---------------------------------------------------------------------------

(defn- next-row
  "Compute next row in Levenshtein distance matrix."
  [previous current other-seq]
  (reduce
   (fn [row [diagonal above other]]
     (let [update-val (if (= other current)
                        diagonal
                        (inc (min diagonal above (peek row))))]
       (conj row update-val)))
   [(inc (first previous))]
   (map vector previous (next previous) other-seq)))

(defn- levenshtein-distance
  "Calculate Levenshtein (edit) distance between two sequences.

   Returns the minimum number of single-character edits (insertions,
   deletions, or substitutions) needed to transform s1 into s2."
  [s1 s2]
  (cond
    (empty? s1) (count s2)
    (empty? s2) (count s1)
    :else (peek (reduce (fn [previous current]
                          (next-row previous current s2))
                        (range (inc (count s2)))
                        s1))))

(defn- length->threshold
  "Determine acceptable edit distance based on string length.

   Shorter strings require closer matches:
   - Length 1-2: exact match only (0)
   - Length 3-5: distance 1
   - Length 6: distance 2
   - Length 7-11: distance 3
   - Length 12-20: distance 4
   - Length 20+: 20% of length"
  [len]
  (condp #(<= %2 %1) len
    2 0
    5 1
    6 2
    11 3
    20 4
    (int (* 0.2 len))))

;;; ---------------------------------------------------------------------------
;;; Symbol Suggestions
;;; ---------------------------------------------------------------------------

(defn- normalize-symbol
  "Normalize a symbol/keyword/string to comparable string form."
  [sym]
  (let [s (str sym)]
    (cond-> s
      (str/starts-with? s ":") (subs 1))))

(defn suggest-symbol
  "Find the closest matching symbol for a target.

   Uses Levenshtein distance with length-aware thresholds.
   Only suggests symbols within acceptable edit distance.

   Request keys:
     ::target       - The unknown symbol (string)
     ::candidates   - Vector of available symbols to search
     ::max-distance - Optional override for max acceptable distance

   Response keys:
     ::suggestion - The best match (string), or nil if none found
     ::distance   - Edit distance to suggestion, or nil

   Example:
     (suggest-symbol
       {::target \"undefiend\"
        ::candidates [\"undefined\" \"define\" \"defn\"]})
     ;; => {::suggestion \"undefined\" ::distance 2}"
  {:malli/schema [:=> [:cat ::suggest-request] ::suggest-symbol-response]}
  [{::keys [target candidates max-distance]}]
  (if (or (str/blank? target) (empty? candidates))
    {::suggestion nil ::distance nil}
    (let [target-norm (normalize-symbol target)
          threshold (or max-distance (length->threshold (count target-norm)))
          matches (->> candidates
                       (map (fn [candidate]
                              (let [cand-norm (normalize-symbol candidate)
                                    dist (levenshtein-distance target-norm cand-norm)]
                                {:candidate (str candidate)
                                 :distance dist})))
                       (filter #(<= (:distance %) threshold))
                       (sort-by :distance))]
      (if-let [best (first matches)]
        {::suggestion (:candidate best)
         ::distance (:distance best)}
        {::suggestion nil ::distance nil}))))

;;; ---------------------------------------------------------------------------
;;; Finding Enrichment
;;; ---------------------------------------------------------------------------

(schema/register! ::finding
                  [:map
                   [:type :keyword]
                   [:message :string]
                   [:row :int]
                   [:col :int]])

(schema/register! ::findings
                  [:vector ::finding])

(schema/register! ::available-symbols
                  [:vector [:or :string :symbol]])

(schema/register! ::enrich-request
                  [:map
                   [::findings ::findings]
                   [::available-symbols {:optional true} ::available-symbols]])

(schema/register! ::enriched-finding
                  [:map
                   [:type :keyword]
                   [:message :string]
                   [:row :int]
                   [:col :int]
                   [::suggestion {:optional true} [:maybe :string]]
                   [::distance {:optional true} [:maybe :int]]])

(schema/register! ::enrich-findings-response
                  [:map
                   [::findings [:vector ::enriched-finding]]])

(defn- extract-symbol-from-message
  "Extract the unresolved symbol name from a clj-kondo message.
   Returns nil if not found."
  [message]
  (when message
    (or
     ;; 'Unresolved symbol: foo'
     (second (re-find #"Unresolved symbol:\s*(\S+)" message))
     ;; 'Unresolved var: ns/foo'
     (when-let [m (re-find #"Unresolved var:\s*(\S+)" message)]
       (let [full (second m)]
         (if (str/includes? full "/")
           (second (str/split full #"/"))
           full))))))

(defn enrich-findings
  "Add 'Did you mean?' suggestions to unresolved symbol findings.

   Processes clj-kondo findings and adds ::suggestion and ::distance
   keys to unresolved-symbol and unresolved-var findings.

   Request keys:
     ::findings          - Vector of clj-kondo findings
     ::available-symbols - Optional vector of known symbols to suggest from.
                           If not provided, uses common Clojure core symbols.

   Response keys:
     ::findings - Enriched findings with suggestions

   Example:
     (enrich-findings
       {::findings [{:type :unresolved-symbol
                     :message \"Unresolved symbol: mpa\"
                     :row 1 :col 5}]
        ::available-symbols [\"map\" \"mapv\" \"mapcat\"]})
     ;; => {::findings [{:type :unresolved-symbol
     ;;                  :message \"Unresolved symbol: mpa\"
     ;;                  :row 1 :col 5
     ;;                  ::suggestion \"map\"
     ;;                  ::distance 1}]}"
  {:malli/schema [:=> [:cat ::enrich-request] ::enrich-findings-response]}
  [{::keys [findings available-symbols]}]
  (let [candidates (or (seq available-symbols)
                       ;; Default: common Clojure symbols
                       '[def defn defn- defmacro let fn if when cond
                         map filter reduce mapv filterv into
                         first rest next cons conj assoc dissoc
                         get get-in update update-in merge
                         str format println prn
                         atom swap! reset! deref
                         require ns use import
                         try catch throw finally
                         loop recur doseq doall dorun
                         and or not nil? some? true? false?
                         inc dec + - * / mod rem
                         = == not= < > <= >=
                         count empty? seq vec set list hash-map
                         apply partial comp juxt identity constantly
                         sort sort-by reverse distinct dedupe
                         take drop take-while drop-while
                         keys vals name keyword symbol])]
    {::findings
     (mapv (fn [finding]
             (if (#{:unresolved-symbol :unresolved-var} (:type finding))
               (if-let [target (extract-symbol-from-message (:message finding))]
                 (let [{:keys [::suggestion ::distance]}
                       (suggest-symbol {::target target ::candidates candidates})]
                   (cond-> finding
                     suggestion (assoc ::suggestion suggestion ::distance distance)))
                 finding)
               finding))
           findings)}))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; Basic Levenshtein
  (levenshtein-distance "kitten" "sitting")  ;; => 3
  (levenshtein-distance "foo" "foo")         ;; => 0
  (levenshtein-distance "mpa" "map")         ;; => 1

  ;; Symbol suggestions
  (suggest-symbol {::target "mpa"
                   ::candidates ["map" "mapv" "mapcat" "filter"]})
  ;; => {::suggestion "map" ::distance 1}

  (suggest-symbol {::target "undefiend"
                   ::candidates ["undefined" "define" "defn"]})
  ;; => {::suggestion "undefined" ::distance 2}

  ;; No match (too different)
  (suggest-symbol {::target "xyz"
                   ::candidates ["abc" "def" "ghi"]})
  ;; => {::suggestion nil ::distance nil}

  ;; Enrich findings
  (enrich-findings
   {::findings [{:type :unresolved-symbol
                 :message "Unresolved symbol: mpa"
                 :row 1 :col 5 :level :error}]
    ::available-symbols ["map" "mapv" "filter"]})

  nil)
