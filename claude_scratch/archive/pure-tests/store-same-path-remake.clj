(require 'seon.program-cost-test '[seon.db :as pdb] '[seon.program :as program] '[datahike.api :as d] '[clojure.java.shell :as sh])
(def acct @#'seon.program-cost-test/acct)
(defn mk [path n]
  (sh/sh "rm" "-rf" path)
  (let [{:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)
        _ (d/transact conn {:tx-data (vec (mapcat #(program/account-tx @conn (acct % "x") %) (range n)))})
        _ (d/branch! conn :db :branch)
        bc (d/connect (assoc cfg :branch :branch))]
    (d/transact bc {:tx-data (program/record-tx @bc (acct (quot n 2) "(inc x)"))})
    [conn bc]))
(let [path (str (System/getProperty "user.dir") "/target/tmp/divergence-x")]
  (dotimes [k 3]
    (let [[conn bc] (mk path 100)]
      (println "made" k) (flush)
      (d/release bc) (d/release conn)
      (println "released" k) (flush)
      (sh/sh "rm" "-rf" path))))
(println "ok")
(System/exit 0)
