(ns seon.schema.stress
  "Pure Malli `seed = 42` generative stress runner, deterministic argument shrinker, and EDN-safe IPC sanitizer."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]))

(defn- shrink-step [v]
  (cond
    (and (integer? v) (not (zero? v)))
    (let [vl (long v)] (distinct [0 (quot vl 2) (if (pos? vl) (dec vl) (inc vl))]))
    (and (string? v) (pos? (count v)))
    (let [n (count v)] (distinct ["" (subs v 0 (quot n 2)) (subs v 0 (dec n))]))
    (and (vector? v) (pos? (count v)))
    (let [n (count v)] (distinct [[] (subvec v 0 (quot n 2)) (subvec v 0 (dec n))]))
    (and (map? v) (pos? (count v)))
    (into [{}] (map #(dissoc v %) (take 4 (sort-by pr-str (keys v)))))
    :else nil))

(defn shrink-failing-args
  "Deterministically shrinks failing argument vector `args` while `(fails? cand)` remains truthy."
  {:malli/schema [:=> [:cat [:vector :any] :seon.host/fn [:? pos-int?]] [:vector :any]], :cost/ticks 15000000, :seon.flow/edge true}
  ([args fails?] (shrink-failing-args args fails? 24))
  ([args fails? max-steps]
   (loop [cur (vec args) steps 0]
     (if (>= steps (long max-steps))
       cur
       (let [cands (for [i (range (count cur)) sv (shrink-step (nth cur i))] (assoc cur i sv))
             better (some (fn [c] (when (try (boolean (fails? c)) (catch Throwable _ false)) c)) cands)]
         (if (and better (not= better cur)) (recur better (inc steps)) cur))))))

(defn generatable-function-schema?
  "True when `sch` is a single `[:=> [:cat ...] ret]` without `:seon.host/*` args, OR a multi-arity `[:function [:=> ...] ...]` whose `:=>` clauses have concrete non-`:seon.host/*`, non-`:any` args."
  {:malli/schema [:=> [:cat :any] [:maybe :boolean]] :cost/ticks 150000}
  [sch]
  (let [no-host? (fn [args-form] (not (some #(and (qualified-keyword? %) (= "seon.host" (namespace %))) (tree-seq coll? seq args-form))))
        no-any? (fn [args-form] (not (some #(= :any %) (tree-seq coll? seq args-form))))
        arity-ok? (fn [strict-any? s]
                    (when (and (vector? s) (= :=> (first s)))
                      (let [af (if (map? (second s)) (nth s 2 nil) (second s))]
                        (and (no-host? af) (or (not strict-any?) (no-any? af))))))]
    (cond
      (and (vector? sch) (= :=> (first sch))) (boolean (arity-ok? false sch))
      (and (vector? sch) (= :function (first sch)))
      (let [ars (filterv vector? (rest sch))]
        (boolean (and (seq ars) (every? #(arity-ok? true %) ars))))
      :else nil)))

(defn safe-example
  {:malli/schema [:=> [:cat :any :map] :any], :cost/ticks 500000, :seon.flow/edge true}
  [input-sch opts]
  (try
    (mg/generate input-sch (assoc opts :seed 42 :size 4))
    (catch Throwable _ nil)))

(defn edn-safe-ipc
  {:malli/schema [:=> [:cat :any] :any], :cost/ticks 500000}
  [x]
  (cond
    (or (nil? x) (boolean? x) (integer? x) (string? x) (keyword? x) (symbol? x) (uuid? x) (inst? x)) x
    (float? x) (let [d (double x)] (if (or (Double/isNaN d) (Double/isInfinite d)) (str d) x))
    (map? x) (into {} (map (fn [[k v]] [(edn-safe-ipc k) (edn-safe-ipc v)])) x)
    (vector? x) (mapv edn-safe-ipc x)
    (set? x) (into #{} (map edn-safe-ipc) x)
    (sequential? x) (mapv edn-safe-ipc x)
    :else (pr-str x)))

(defn ^:seon.flow/edge stress-arity
  "Runs `:examples` then `samples` deterministic `seed` inputs on a single Malli `:=>` arity, shrinking failures and enforcing `:cost/*` budgets."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.host/fn :map :any :any [:vector :any] :int :int [:maybe :seon.host/fn] :map] :map]}
  [fn-sym target-fn opts registry ar-sch top-examples samples seed measure-fn budgets]
  (let [[cat-s ret-s guard-s] (m/children ar-sch)
        valid-in? (m/validator cat-s opts) valid-ret? (m/validator ret-s opts)
        valid-guard? (when guard-s (m/validator guard-s opts))
        {:keys [ticks-budget bytes-budget ms-budget]} budgets
        repro? (fn [ca] (and (valid-in? ca)
                             (try (let [rv (apply target-fn ca)]
                                    (or (not (valid-ret? rv)) (and valid-guard? (not (valid-guard? [ca rv])))))
                                  (catch Throwable _ true))))
        check1 (fn [a [tag sargs exp]]
                 (let [fail (fn [extra rfn]
                              (let [sh (when (and (= tag :sample) (sequential? sargs)) (shrink-failing-args (vec sargs) repro? 24))
                                    ea (or sh sargs)]
                                (reduced (edn-safe-ipc
                                          (cond-> (merge {:ok? false :var fn-sym :args ea :reason (rfn ea)} extra)
                                            (and sh (not= sh sargs)) (assoc :shrunk-args sh :original-args sargs))))))]
                   (try
                     (let [run1 #(if measure-fn (measure-fn (fn [] (apply target-fn sargs))) {:val (apply target-fn sargs) :ticks 0 :bytes 0 :ms 0.0})
                           r0 (run1)
                           {:keys [val ticks bytes ms]} (if (and measure-fn (or (and ticks-budget (> (:ticks r0) ticks-budget)) (and bytes-budget (> (:bytes r0) bytes-budget)) (and ms-budget (:ms r0) (> (:ms r0) ms-budget)))) (run1) r0)]
                       (cond
                         (and (= tag :example) (not= exp val)) (fail {:example? true :expected exp :return val} #(str "Example failed on " fn-sym " args " (pr-str %) ": expected " (pr-str exp) ", got " (pr-str val)))
                         (not (valid-ret? val)) (fail {:return val :blame :output-contract :human (me/humanize (m/explain ret-s val opts))} #(str "Return value failed schema " (pr-str (m/form ret-s)) " on args " (pr-str %) ": got " (pr-str val)))
                         (and valid-guard? (not (valid-guard? [sargs val]))) (fail {:return val :blame :output-contract :relational-guard-failed? true} #(str "Relational postcondition failed on " fn-sym " args " (pr-str %) " -> " (pr-str val)))
                         (and ticks-budget (> ticks ticks-budget)) (fail {:ticks ticks :ticks-budget ticks-budget} #(str "Cost overrun on " fn-sym ": " ticks " ticks > :cost/ticks " ticks-budget " on args " (pr-str %)))
                         (and bytes-budget (> bytes bytes-budget)) (fail {:bytes bytes :bytes-budget bytes-budget} #(str "Allocation overrun on " fn-sym ": " bytes " bytes > :cost/bytes " bytes-budget " on args " (pr-str %)))
                         (and ms-budget ms (> ms ms-budget)) (fail {:call-ms ms :ms-budget ms-budget} #(str "Time overrun on " fn-sym ": " ms " ms > :cost/ms " ms-budget " on args " (pr-str %)))
                         :else (-> a (update :ran inc) (update :max-ticks max (long (or ticks 0))) (update :max-bytes max (long (or bytes 0))))))
                     (catch Throwable ex
                       (fail {:exception (.getMessage ex) :blame :exception} #(str "Threw exception on " (if (= tag :example) "example" (str "seed=" seed)) " args " (pr-str %) ": " (.getMessage ex)))))))
        ex-out (reduce check1 {:ok? true :var fn-sym :ran 0 :max-ticks 0 :max-bytes 0} (map (fn [[a e]] [:example a e]) top-examples))]
    (if (or (not (:ok? ex-out)) (not (pos? (long samples))))
    ex-out
    (let [batches (try (mg/generate [:vector {:min samples :max samples} cat-s] {:registry registry :seed seed :size 8})
                       (catch Throwable t {:gen-error (.getMessage t)}))]
      (if (map? batches)
        {:ok? false :var fn-sym :reason (str "Generator failed for " fn-sym ": " (:gen-error batches))}
        (reduce check1 ex-out (map (fn [a] [:sample a nil]) batches)))))))
