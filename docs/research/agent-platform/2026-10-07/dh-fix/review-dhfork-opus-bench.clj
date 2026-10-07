;; review-dhfork-opus: dh-fix bench.clj plus a plan-cold column. java -cp CP clojure.main THIS DIR (DIR holds bench.clj and store/, an APFS clone of data/store)
(System/setProperty "dhfix.scratch" (first *command-line-args*))
(load-file (str (first *command-line-args*) "/bench.clj"))
(in-ns 'bench)
(defn plan-cold
  "Empty plan cache, fresh connection, store cache 1: planning plus one execution."
  [ins label]
  (let [xs (vec (for [_ (range 5)]
                  (do (vreset! @#'q/plan-cache (lru/lru 100))
                      (with-conn [c] (let [db @c stats (-> db :store :storage :stats)]
                                       (measure1 db stats (shapes label) (ins label)))))))]
    {:shape label :plan-cold {:ms (median-by :ms xs) :bytes (median-by :bytes xs) :reads (median-by :reads xs)}}))
(let [labels [:receipts :analysis :next-ordinal :latest-turn :affected]
      ins (with-conn [c] (inputs @c))]
  (run-all) ;; JIT warm-up
  (doseq [round [1 2]]
    (doseq [r (run-all)] (prn :round round r))
    (doseq [l labels] (prn :round round (plan-cold ins l)))))
(shutdown-agents)
(System/exit 0)
