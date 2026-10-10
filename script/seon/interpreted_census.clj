(ns seon.interpreted-census
  "Run test symbols interpreted, as a non-root agent on its own branch of the live root, in batches.

  bb -cp script:src -m seon.interpreted-census ROOT CLUSTER AGENT SYMBOLS-EDN OUT-EDN [BATCH]

  SYMBOLS-EDN holds a vector of test symbols, or of [class symbol] pairs. Each batch is one
  `seon.test/run` request of AGENT's acquired world (the request `my.test/check` makes, with
  `:seon.test/rerun? true` and declared-long tests included), sent over the root's prepl; one EDN
  line per member is appended to OUT-EDN: its class, symbol, counts and the first line of its
  failure message, or the batch's refusal."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.operator :as operator]))

(defn- batch-form
  "The form the live root evaluates for one batch of `symbols`."
  [cluster agent-id symbols]
  `(let [handle# (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances ~cluster))
         execution# (seon.cluster.agent/acquire-context! handle# ~agent-id)
         connection# (:seon.db/connection execution#)
         began# (System/nanoTime)
         run# (seon.db/call-with-custody
               {:seon.db/connection connection#}
               #(seon.test/run {:seon.test/execution execution# :seon.test/recording-connection connection#
                                :seon.test/policy :named :seon.test/identities '~(set symbols)
                                :seon.test/rerun? true :seon.test/include-long? true
                                :seon.test/check-time-limit-ms 240000}))]
     {:ms (long (/ (- (System/nanoTime) began#) 1e6))
      :refusal (:seon.error/message run#)
      :unrun (into [] cat (vals (select-keys run# [:seon.test/pending :seon.test/unfinished])))
      :excluded (mapv :seon.test/sym (concat (:seon.test/destructive-excluded run#) (:seon.test/deferred run#)))
      :results (mapv (fn [result#]
                       (assoc (select-keys result# [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count])
                              :message (some-> (:seon.test/failure-message result#) str/split-lines
                                               (->> (remove str/blank?) (take 3) (str/join " | ")))))
                     (:seon.test/results run#))}))

(defn -main
  "Run every batch of SYMBOLS-EDN and append its members to OUT-EDN."
  [root cluster agent-id symbols-file out & [batch]]
  (let [entries (edn/read-string (slurp symbols-file))
        classed (mapv #(if (vector? %) % [:unclassified %]) entries)
        class-of (into {} (map (fn [[c s]] [s c])) classed)
        size (Long/parseLong (or batch "25"))]
    (doseq [[n part] (map-indexed vector (partition-all size (map second classed)))]
      (let [live (operator/live-root-value! root (pr-str (batch-form cluster agent-id part))
                                            {:seon.operator/command :check})
            answer (:seon.operator/value live)
            lines (if (:results answer)
                    (concat (map #(assoc % :class (class-of (:seon.test/sym %))) (:results answer))
                            (map (fn [s] {:seon.test/sym s :class (class-of s) :unrun true}) (:unrun answer))
                            (map (fn [s] {:seon.test/sym s :class (class-of s) :excluded true}) (:excluded answer)))
                    (map (fn [s] {:seon.test/sym s :class (class-of s) :refusal (or (:refusal answer) (pr-str answer))}) part))]
        (spit out (str/join (map #(str (pr-str %) "\n") lines)) :append true)
        (println "batch" n (count part) "tests" (:ms answer) "ms"
                 (count (filter #(pos? (+ (:seon.test/fail-count % 0) (:seon.test/error-count % 0))) (:results answer))) "red")))))
