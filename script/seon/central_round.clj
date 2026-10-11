(ns seon.central-round
  "Run the full compiled tier on an isolated root, as `seon.interpreted-census` does for one list.

  bb -cp script:src -m seon.central-round ROOT OUT-EDN [BATCH] [SYMBOLS-EDN]

  ROOT is a running isolated operator root (`bin/seon --root ROOT start`, which must stay alive while
  this runs). Without SYMBOLS-EDN every stored `:seon.test/sym` is run, less fixture material, which
  `seon.test/run` refuses as a whole batch. Each batch is one request of root on the cluster's own
  execution. A refused batch (the root is not ready, or a member hung past the check limit) waits for the
  cluster handle and is tried once more; a second refusal is recorded per symbol. One EDN line per symbol is
  appended to OUT-EDN (class `:central`, counts and whole failure message, or `:unrun`/`:refusal`)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.interpreted-census :as census]
            [seon.operator :as operator]))

(def ^:private fixture-material #"test-resolution-fixture|test-runner-failure-fixture")

(defn- value [root form]
  (try (:seon.operator/value (operator/live-root-value! root form {:seon.operator/command :check}))
       (catch Throwable e {:seon.error/message (str "driver: " (ex-message e))})))

(defn- test-symbols [root]
  (->> (value root (pr-str '(let [handle (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))]
                              (vec (sort (map first (datahike.api/q '[:find ?s :where [?e :seon.test/sym ?s]]
                                                                     @(:seon.db/connection handle))))))))
       (remove #(re-find fixture-material (str %)))
       vec))

(defn- ready? [root]
  (boolean (value root (pr-str '(some? (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default")))))))

(defn- run-batch [root part]
  (let [answer (value root (pr-str (#'census/batch-form "default" "root" part)))]
    (if (map? answer) answer {:refusal (pr-str answer)})))

(defn -main
  "Run every batch and append its members to OUT-EDN."
  [root out & [batch symbols-file]]
  (let [syms (if symbols-file (edn/read-string (slurp symbols-file)) (test-symbols root))
        size (Long/parseLong (or batch "25"))]
    (println (count syms) "tests")
    (doseq [[n part] (map-indexed vector (partition-all size syms))]
      (let [answer (loop [attempt 0]
                     (let [answer (run-batch root part)]
                       (if (and (< attempt 1) (or (nil? (:results answer)) (:refusal answer) (:seon.error/message answer)))
                         (do (while (not (ready? root)) (Thread/sleep 3000)) (recur (inc attempt)))
                         answer)))
            lines (census/batch-lines (constantly :central) part
                                      (cond-> answer (:seon.error/message answer) (assoc :refusal (:seon.error/message answer))))]
        (spit out (str/join (map #(str (pr-str %) "\n") lines)) :append true)
        (println "batch" n (count part) "tests" (:ms answer) "ms"
                 (count (filter #(pos? (+ (:seon.test/fail-count % 0) (:seon.test/error-count % 0))) (:results answer))) "red")))))
