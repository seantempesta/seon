(ns seon.check-corpus
  "Count the packaged schema corpus: per key generate one value with a fixed
  seed and size and validate it, in a throwaway JVM with Seon's namespaces
  loaded (never default's).

  clojure -M -i script/seon/check_corpus.clj -e \"(seon.check-corpus/-main \\\"OUT-EDN\\\")\"

  Each key is classified :ok, :such-that, :no-generator, :invalid, :timeout,
  :invalid-sample or :error (any other failure, with its ex-data type). The
  output holds the totals, the milliseconds spent per class and the keys of
  every class but :ok."
  (:require [clojure.pprint :as pprint]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(def ^:private seed 0)
(def ^:private size 10)
(def ^:private timeout-ms 3000)

(defn- classify
  "The class of generating and validating one value of `compiled`."
  [compiled]
  (let [work (future
               (try
                 (if (m/validate compiled (mg/generate compiled {:seed seed :size size}))
                   :ok
                   :invalid-sample)
                 (catch clojure.lang.ExceptionInfo failure
                   (let [type (:type (ex-data failure))]
                     (case type
                       ::mg/such-that-failure :such-that
                       ::mg/no-generator :no-generator
                       ::m/invalid-schema :invalid
                       [:error type])))
                 (catch Throwable failure
                   [:error (class failure)])))
        result (deref work timeout-ms ::timeout)]
    (when (= ::timeout result) (future-cancel work))
    (if (= ::timeout result) :timeout result)))

(defn count-corpus
  "Classify every packaged key: totals, ms per class and the keys per class."
  [forms projection]
  (let [rows (mapv (fn [k]
                     (let [began (System/nanoTime)
                           class (try (classify (mr/schema (:seon.schema.projection/registry projection) k))
                                      (catch Throwable failure
                                        (if (= ::m/invalid-schema (:type (ex-data failure)))
                                          :invalid
                                          [:error (class failure)])))]
                       {:key k :class class :ms (/ (- (System/nanoTime) began) 1e6)}))
                   (sort (keys forms)))
        by-class (group-by :class rows)]
    {:seed seed :size size :timeout-ms timeout-ms
     :keys (count rows)
     :totals (into (sorted-map-by #(compare (str %1) (str %2)))
                   (map (fn [[c rs]] [c (count rs)])) by-class)
     :ms (into (sorted-map-by #(compare (str %1) (str %2)))
               (map (fn [[c rs]] [c (Math/round (double (reduce + (map :ms rs))))])) by-class)
     :failing (into (sorted-map-by #(compare (str %1) (str %2)))
                    (comp (remove (fn [[c _]] (= :ok c)))
                          (map (fn [[c rs]] [c (vec (sort (map :key rs)))])))
                    by-class)}))

(defn -main
  "Write the count of the packaged corpus to `out`."
  [out]
  (let [forms (schema.edn/packaged-forms)
        projection (schema/declaration-projection forms)
        result (count-corpus forms projection)]
    (spit out (with-out-str (pprint/pprint result)))
    (println (select-keys result [:keys :totals :ms]))
    (shutdown-agents)))
