#!/usr/bin/env bb
;; KT3 mechanical scorer — real Clojure reader (edamame), no regexes on code.
;;
;; TWO input modes on stdin (one scorer, no v2 file):
;;
;; 1. LEGACY (KT3/KT3b drivers, unchanged byte-stable behavior):
;;    JSON array of {"id": <int>, "target": <str>, "prediction": <str>}
;;    -> JSON array of per-row analyses (see `score-row`).
;;
;; 2. EXTENDED (KT3-redux, scoring v2):
;;    JSON map {"index-syms": [<qualified fn syms>],
;;              "rows": [{"id","target","prediction","context","card-names"}]}
;;    -> JSON array of per-row analyses with the extra `decomp` block.
;;    Differences from legacy, all documented below:
;;      - pairing is BEST-MATCH set-union (per target call, the unused
;;        prediction call with the same fn and max arg-key overlap; ties
;;        -> earliest), not greedy-first — the primary lens is the turn's
;;        full form SET, order-insensitive.
;;      - every prediction call is classified: matched | wrong-fn
;;        (grounded but not a target fn) | hallucinated-fn (head absent
;;        from index ∪ row cards ∪ context ∪ prediction's own defs ∪
;;        clojure.core ∪ special forms/interop).
;;      - every matched pair decomposes: right-args | wrong-arg-keys
;;        (missing/extra by NAME, or ns-qualification mismatch on a
;;        shared name) | wrong-arg-values (shared keys whose printed
;;        values differ).
;;      - confusion pairs: leftover target calls zipped in reading order
;;        with leftover prediction calls ("predicted X where Y expected").
;;      - id lens: id-shaped string VALUES (from parsed forms, not text)
;;        — target-id recall, spurious split into grounded-in-context vs
;;        invented (id-shaped-but-invalid).
;;
;; Scoring model (the documented "useful match" formula, both modes):
;;   - Parse both sides with edamame (:all true). Prediction that fails to
;;     read => useful 0 (parsed false), everything else still reported.
;;   - A CALL = any list form whose head is a symbol; quoted forms are
;;     skipped (datalog vectors etc. carry no call heads anyway).
;;   - Symbols normalize to {name, ns-suffix} where ns-suffix is the LAST
;;     dot segment ("my.plan/done!" and "plan/done!" both => plan/done!).
;;     Two calls match when names are equal and ns-suffixes are equal or
;;     either side has none (alias-insensitive, mechanical).
;;   - Per-target-call credit: 0 unmatched; 1.0 matched with no map arg in
;;     the target; matched with a keyword-keyed map arg =>
;;     0.5 + 0.5 * (|target keys ∩ pred keys| / |target keys|), keys
;;     compared by keyword NAME (namespace-insensitive: :my.plan/id ~ ::id).
;;   - recall-credit = mean credit over target calls;
;;     precision = matched prediction calls / all prediction calls;
;;     useful = harmonic mean of recall-credit and precision (F1-style).
;;   - The same computation restricted to non-:ns-move calls is reported
;;     as :substantive (in-ns/require boilerplate is trivially predictable
;;     and would otherwise inflate the ceiling).
;;
;; Form kinds: :ns-move :plan :register :transact :query :defn :other.

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[edamame.core :as e])

(defn parse-forms [s]
  (try {:ok true
        :forms (e/parse-string-all s {:all true
                                      :auto-resolve (fn [k] (if (= :current k) "CURNS" (str k)))})}
       (catch Exception ex
         {:ok false :error (.getMessage ex)})))

(defn norm-sym [sym]
  (let [ns' (namespace sym)]
    {:name (name sym)
     :ns (when ns' (last (str/split ns' #"\.")))}))

(defn call-kind [{n :name ns' :ns}]
  (cond
    (#{"in-ns" "ns" "require" "use" "refer" "load-file"} n) :ns-move
    (#{"defn" "defn-" "def" "defonce" "defmacro" "defmethod" "defmulti"} n) :defn
    (= n "register!") :register
    (= n "transact!") :transact
    (and (#{"query" "q" "pull" "pull-by-name" "pull-many" "entity" "datoms" "history" "as-of"} n)
         (or (nil? ns') (= ns' "db"))) :query
    (= ns' "plan") :plan
    :else :other))

(defn map-arg-keys
  "Keyword key NAMES of the first map argument of a call form, else nil."
  [form]
  (when-let [m (first (filter map? (rest form)))]
    (let [ks (->> (keys m) (filter keyword?) (map name) set)]
      (when (seq ks) ks))))

(defn map-arg-entries
  "{key-NAME {:kw \"full keyword string\" :val \"pr-str of value\"}} of the
  first map argument of a call form, else nil."
  [form]
  (when-let [m (first (filter map? (rest form)))]
    (let [entries (->> m
                       (filter (fn [[k _]] (keyword? k)))
                       (map (fn [[k v]] [(name k) {:kw (str k) :val (pr-str v)}]))
                       (into {}))]
      (when (seq entries) entries))))

(defn collect-calls
  "All call sites in reading order: [{:sym {:name :ns} :kind k :arg-keys #{...}}]."
  [form]
  (cond
    (seq? form)
    (let [head (first form)]
      (if (= 'quote head)
        []
        (let [self (when (symbol? head)
                     (let [nsym (norm-sym head)]
                       [{:sym nsym :kind (call-kind nsym)
                         :arg-keys (map-arg-keys form)
                         :arg-entries (map-arg-entries form)
                         :pos-args (mapv pr-str (rest form))}]))]
          (into (vec self) (mapcat collect-calls (rest form))))))
    (coll? form) (vec (mapcat collect-calls form))
    :else []))

(defn sym-match? [a b]
  (and (= (:name a) (:name b))
       (or (nil? (:ns a)) (nil? (:ns b)) (= (:ns a) (:ns b)))))

(defn pair-calls
  "LEGACY greedy pairing of target calls to unused prediction calls."
  [t-calls p-calls]
  (loop [ts t-calls, used #{}, out []]
    (if-let [t (first ts)]
      (let [idx (first (keep-indexed
                        (fn [i p] (when (and (not (used i)) (sym-match? (:sym t) (:sym p))) i))
                        p-calls))]
        (recur (rest ts)
               (if idx (conj used idx) used)
               (conj out [t (when idx (nth p-calls idx))])))
      {:pairs out :used used})))

(defn key-overlap
  "Arg-key overlap fraction for best-match ranking (name-insensitive keys)."
  [t p]
  (let [tk (:arg-keys t) pk (:arg-keys p)]
    (cond
      (and tk pk) (/ (count (filter pk tk)) (double (max 1 (count tk))))
      (and (nil? tk) (nil? pk)) 0.5
      :else 0.0)))

(defn pair-calls-v2
  "SET-UNION best-match pairing: per target call (in order), the unused
  prediction call with the same fn and maximal arg-key overlap; ties ->
  earliest prediction. Order-insensitive w.r.t. prediction position."
  [t-calls p-calls]
  (loop [ts t-calls, used #{}, out []]
    (if-let [t (first ts)]
      (let [cands (keep-indexed
                   (fn [i p] (when (and (not (used i)) (sym-match? (:sym t) (:sym p)))
                               [i p (key-overlap t p)]))
                   p-calls)
            best (reduce (fn [acc [i p s]]
                           (if (or (nil? acc) (> s (nth acc 2))) [i p s] acc))
                         nil cands)
            idx (first best)]
        (recur (rest ts)
               (if idx (conj used idx) used)
               (conj out [t (when idx (nth p-calls idx))])))
      {:pairs out :used used})))

(defn credit [[t p]]
  (cond
    (nil? p) 0.0
    (nil? (:arg-keys t)) 1.0
    :else (let [tk (:arg-keys t)
                pk (or (:arg-keys p) #{})]
            (+ 0.5 (* 0.5 (/ (count (filter pk tk)) (double (count tk))))))))

(defn f1 [r p] (if (or (zero? r) (zero? p)) 0.0 (/ (* 2 r p) (+ r p))))

(defn score-calls [t-calls p-calls pair-fn]
  (let [{:keys [pairs used]} (pair-fn t-calls p-calls)
        credits (map credit pairs)
        recall (if (seq t-calls) (/ (reduce + 0.0 credits) (count t-calls)) nil)
        precision (if (seq p-calls) (/ (count used) (double (count p-calls))) 0.0)]
    {:n-target (count t-calls)
     :n-pred (count p-calls)
     :matched (count used)
     :recall (some-> recall double)
     :precision precision
     :useful (when recall (f1 recall precision))
     :pairs pairs
     :used used}))

;;; ---------------------------------------------------------------------
;;; Extended mode — decomposition
;;; ---------------------------------------------------------------------

;; Special forms are a fixed language constant (clojure.lang.Compiler's
;; table + the pod's async surface), not a maintained project list.
(def special-forms
  #{"if" "do" "def" "quote" "var" "recur" "throw" "try" "catch" "finally"
    "set!" "new" "." "let*" "fn*" "loop*" "letfn*" "case*" "await" "js*"
    "monitor-enter" "monitor-exit" "reify*" "deftype*"})

(def core-names
  (set (map name (keys (ns-publics 'clojure.core)))))

(def id-re #"[A-Za-z0-9]{3}-\d{10}")

(defn id-shaped? [s] (boolean (re-matches id-re s)))

(defn collect-string-values
  "All string leaves in a parsed form tree (reader-based, not textual)."
  [form]
  (cond
    (string? form) [form]
    (coll? form) (vec (mapcat collect-string-values (seq form)))
    :else []))

(defn form-ids [forms]
  (->> forms (mapcat collect-string-values) (filter id-shaped?) set))

(defn full-kw-mismatch?
  "Shared-name key whose FULL keyword differs (ns-qualification error).
  CURNS (::auto-resolved) on either side is a wildcard — unresolvable
  mechanically, so never flagged."
  [t-entry p-entry]
  (let [tk (:kw t-entry) pk (:kw p-entry)]
    (and tk pk
         (not (str/includes? tk "CURNS"))
         (not (str/includes? pk "CURNS"))
         (not= tk pk))))

(defn classify-pair
  "Decompose one matched [target pred] pair."
  [t p]
  (let [te (:arg-entries t) pe (:arg-entries p)]
    (if (nil? (:arg-keys t))
      ;; no keyword-map arg in the target: compare positional printed args
      (if (= (:pos-args t) (:pos-args p))
        {:outcome "right-args"}
        {:outcome "wrong-arg-values" :positional true})
      (let [tk (set (keys te)) pk (set (keys (or pe {})))
            missing (vec (sort (remove pk tk)))
            extra (vec (sort (remove tk pk)))
            shared (filter pk tk)
            ns-mism (vec (sort (filter #(full-kw-mismatch? (te %) (get pe %)) shared)))
            val-mism (vec (sort (filter #(and (te %) (get pe %)
                                              (not= (:val (te %)) (:val (get pe %))))
                                        shared)))]
        (cond
          (or (seq missing) (seq extra) (seq ns-mism))
          {:outcome "wrong-arg-keys"
           :missing-keys missing :extra-keys extra :ns-mismatch-keys ns-mism
           :value-mismatch-keys val-mism}
          (seq val-mism)
          {:outcome "wrong-arg-values" :value-mismatch-keys val-mism}
          :else {:outcome "right-args"})))))

(defn head-str [{:keys [sym]}]
  (str (or (:ns sym) "") "/" (:name sym)))

(defn grounded?
  "Is a prediction head a REAL fn from the model's visible world?
  index (alias-insensitive) ∪ row cards ∪ context text ∪ the prediction's
  own def-names ∪ clojure.core ∪ special forms ∪ interop."
  [{:keys [sym]} index-by-name card-names context own-defs]
  (let [n (:name sym) ns' (:ns sym)]
    (or (when-let [suffixes (index-by-name n)]
          (or (nil? ns') (contains? suffixes ns')))
        (contains? card-names n)
        (contains? own-defs n)
        (contains? core-names n)
        (contains? special-forms n)
        (str/starts-with? n ".")
        (= ns' "js")
        (and context (str/includes? context n)))))

(defn own-def-names
  "Names DEFINED inside the prediction itself (defn/def/... second position)."
  [forms]
  (->> forms
       (mapcat collect-calls)
       (filter #(= :defn (:kind %)))
       (keep (fn [_] nil))                      ; heads carry no def-name; walk forms below
       set
       (into (->> forms
                  (mapcat (fn walk [f]
                            (cond
                              (and (seq? f) (symbol? (first f))
                                   (#{"defn" "defn-" "def" "defonce" "defmacro"
                                      "defmethod" "defmulti"} (name (first f)))
                                   (symbol? (second f)))
                              (into [(name (second f))] (mapcat walk (rest f)))
                              (coll? f) (mapcat walk (seq f))
                              :else [])))
                  set))))

(defn decompose
  "The scoring-v2 decomposition block for one row."
  [t-calls p-calls pairs used index-by-name card-names context t-forms p-forms]
  (let [target-outcomes
        (mapv (fn [[t p]]
                (merge {:head (head-str t) :kind (name (:kind t))}
                       (if p
                         (assoc (classify-pair t p) :pred-head (head-str p))
                         {:outcome "missing"})))
              pairs)
        matched-pred-idx used
        unmatched-preds (vec (keep-indexed (fn [i p] (when-not (matched-pred-idx i) p)) p-calls))
        pred-outcomes
        (let [own-defs (own-def-names p-forms)]
          (mapv (fn [p]
                  (let [matched? (some #(identical? p (second %)) pairs)]
                    {:head (head-str p)
                     :kind (name (:kind p))
                     :outcome (cond matched? "matched"
                                    (grounded? p index-by-name card-names context own-defs) "wrong-fn"
                                    :else "hallucinated-fn")}))
                p-calls))
        unmatched-targets (vec (keep (fn [[t p]] (when-not p t)) pairs))
        confusions (mapv (fn [t p] [(head-str t) (head-str p)])
                         unmatched-targets unmatched-preds)
        t-ids (form-ids t-forms)
        p-ids (form-ids p-forms)
        spurious (remove t-ids p-ids)
        {grounded-sp true invented-sp false}
        (group-by #(boolean (and context (str/includes? context %))) spurious)]
    {:target-outcomes target-outcomes
     :pred-outcomes pred-outcomes
     :confusions confusions
     :ids {:target (vec (sort t-ids))
           :recalled (vec (sort (filter p-ids t-ids)))
           :spurious-grounded (vec (sort (or grounded-sp [])))
           :spurious-invented (vec (sort (or invented-sp [])))}}))

;;; ---------------------------------------------------------------------
;;; Row scoring
;;; ---------------------------------------------------------------------

(defn score-row [{:keys [id target prediction context card-names]} extended? index-by-name]
  (let [pair-fn (if extended? pair-calls-v2 pair-calls)
        tp (parse-forms target)
        pp (parse-forms prediction)
        t-calls (when (:ok tp) (vec (mapcat collect-calls (:forms tp))))
        p-calls (if (:ok pp) (vec (mapcat collect-calls (:forms pp))) [])
        all (score-calls t-calls p-calls pair-fn)
        subst (score-calls (vec (remove #(= :ns-move (:kind %)) t-calls))
                           (vec (remove #(= :ns-move (:kind %)) p-calls))
                           pair-fn)
        {:keys [pairs used]} all
        kinds (reduce (fn [acc [t p]]
                        (update acc (name (:kind t))
                                (fnil (fn [{:keys [n matched credit-sum]}]
                                        {:n (inc n)
                                         :matched (+ matched (if p 1 0))
                                         :credit-sum (+ credit-sum (credit [t p]))})
                                      {:n 0 :matched 0 :credit-sum 0.0})))
                      {} pairs)
        base {:id id
              :parsed (:ok pp)
              :parse-error (when-not (:ok pp) (:error pp))
              :target-parsed (:ok tp)
              :n-target (:n-target all) :n-pred (:n-pred all) :matched (:matched all)
              :recall (:recall all) :precision (:precision all)
              :useful (if (:ok pp) (or (:useful all) 0.0) 0.0)
              :substantive (when (pos? (:n-target subst))
                             {:recall (:recall subst) :precision (:precision subst)
                              :useful (if (:ok pp) (or (:useful subst) 0.0) 0.0)})
              :kinds kinds
              :target-heads (mapv head-str t-calls)
              :pred-heads (mapv head-str p-calls)}]
    (if extended?
      (assoc base :decomp
             (decompose t-calls p-calls pairs used index-by-name
                        (set (or card-names [])) context
                        (when (:ok tp) (:forms tp))
                        (if (:ok pp) (:forms pp) [])))
      base)))

(defn index-by-name
  "qualified index syms -> {fn-name #{ns-suffixes}} (alias-insensitive)."
  [syms]
  (reduce (fn [acc s]
            (let [[ns' n] (str/split s #"/" 2)
                  n (or n ns')
                  suffix (when (and n (not= n ns')) (last (str/split ns' #"\.")))]
              (update acc n (fnil conj #{}) suffix)))
          {} syms))

(let [input (json/parse-stream *in* true)
      extended? (map? input)
      rows (if extended? (:rows input) input)
      idx (when extended? (index-by-name (:index-syms input)))]
  (json/generate-stream (mapv #(score-row % extended? idx) rows) *out*)
  (flush))
