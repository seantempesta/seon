#!/usr/bin/env bb
;; KT3 mechanical scorer — real Clojure reader (edamame), no regexes.
;;
;; stdin:  JSON array of {"id": <int>, "target": <str>, "prediction": <str>}
;; stdout: JSON array of per-row analyses (see `score-row`).
;;
;; Scoring model (the documented "useful match" formula):
;;   - Parse both sides with edamame (:all true). Prediction that fails to
;;     read => useful 0 (parsed false), everything else still reported.
;;   - A CALL = any list form whose head is a symbol; quoted forms are
;;     skipped (datalog vectors etc. carry no call heads anyway).
;;   - Symbols normalize to {name, ns-suffix} where ns-suffix is the LAST
;;     dot segment ("my.plan/done!" and "plan/done!" both => plan/done!).
;;     Two calls match when names are equal and ns-suffixes are equal or
;;     either side has none (alias-insensitive, mechanical).
;;   - Target->prediction pairing is greedy in target order, each
;;     prediction call used at most once.
;;   - Per-target-call credit: 0 unmatched; 1.0 matched with no map arg in
;;     the target; matched with a keyword-keyed map arg =>
;;     0.5 + 0.5 * (|target keys ∩ pred keys| / |target keys|), keys
;;     compared by keyword NAME (namespace-insensitive: :my.plan/id ~ ::id).
;;   - recall-credit = mean credit over target calls;
;;     precision = matched prediction calls / all prediction calls;
;;     useful = harmonic mean of recall-credit and precision (F1-style).
;;   - The same computation restricted to non-:ns-move calls is reported
;;     as :substantive (in-ns/require boilerplate is trivially predictable
;;     and would inflate the ceiling).
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
                       [{:sym nsym :kind (call-kind nsym) :arg-keys (map-arg-keys form)}]))]
          (into (vec self) (mapcat collect-calls (rest form))))))
    (coll? form) (vec (mapcat collect-calls form))
    :else []))

(defn sym-match? [a b]
  (and (= (:name a) (:name b))
       (or (nil? (:ns a)) (nil? (:ns b)) (= (:ns a) (:ns b)))))

(defn pair-calls
  "Greedy pairing of target calls to unused prediction calls."
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

(defn credit [[t p]]
  (cond
    (nil? p) 0.0
    (nil? (:arg-keys t)) 1.0
    :else (let [tk (:arg-keys t)
                pk (or (:arg-keys p) #{})]
            (+ 0.5 (* 0.5 (/ (count (filter pk tk)) (double (count tk))))))))

(defn f1 [r p] (if (or (zero? r) (zero? p)) 0.0 (/ (* 2 r p) (+ r p))))

(defn score-calls [t-calls p-calls]
  (let [{:keys [pairs used]} (pair-calls t-calls p-calls)
        credits (map credit pairs)
        recall (if (seq t-calls) (/ (reduce + 0.0 credits) (count t-calls)) nil)
        precision (if (seq p-calls) (/ (count used) (double (count p-calls))) 0.0)]
    {:n-target (count t-calls)
     :n-pred (count p-calls)
     :matched (count used)
     :recall (some-> recall double)
     :precision precision
     :useful (when recall (f1 recall precision))
     :pairs pairs}))

(defn score-row [{:keys [id target prediction]}]
  (let [tp (parse-forms target)
        pp (parse-forms prediction)
        t-calls (when (:ok tp) (vec (mapcat collect-calls (:forms tp))))
        p-calls (if (:ok pp) (vec (mapcat collect-calls (:forms pp))) [])
        all (score-calls t-calls p-calls)
        subst (score-calls (vec (remove #(= :ns-move (:kind %)) t-calls))
                           (vec (remove #(= :ns-move (:kind %)) p-calls)))
        {:keys [pairs]} all
        kinds (reduce (fn [acc [t p]]
                        (update acc (name (:kind t))
                                (fnil (fn [{:keys [n matched credit-sum]}]
                                        {:n (inc n)
                                         :matched (+ matched (if p 1 0))
                                         :credit-sum (+ credit-sum (credit [t p]))})
                                      {:n 0 :matched 0 :credit-sum 0.0})))
                      {} pairs)]
    {:id id
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
     :target-heads (mapv #(str (or (:ns (:sym %)) "") "/" (:name (:sym %))) t-calls)
     :pred-heads (mapv #(str (or (:ns (:sym %)) "") "/" (:name (:sym %))) p-calls)}))

(let [rows (json/parse-stream *in* true)]
  (json/generate-stream (mapv score-row rows) *out*)
  (flush))
