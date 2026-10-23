;; Round 2's rankings unblinded (task: printer-eval): each scorer's ranking (scores/rank*.json) joined
;; to key2.edn; per candidate, each variant's first places and mean rank (1 best), and per entry.
;; Run: HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/tally2.clj
(require '[seon.edn :as edn] '[clojure.data.json :as json] '[clojure.string :as str] '[clojure.java.io :as io])

(def key2 (edn/decode (slurp "notes/printer-eval/key2.edn")))
(def rows
  (for [[packet slots] key2
        :let [f (io/file (str "notes/printer-eval/scores/" packet ".json"))] :when (.exists f)
        s (json/read-str (slurp f))
        :let [[id labels] (get slots (long (get s "section")))]]
    {:id id :ranks (into {} (map-indexed (fn [i l] [(labels l) (inc i)]) (get s "ranking"))) :why (get s "why")}))

(doseq [[c rs] (sort (group-by #(subs (:id %) 0 2) rows))]
  (println "==" c (count rs) "rankings")
  (doseq [k (sort (distinct (mapcat (comp keys :ranks) rs)))]
    (let [ranks (keep #(get-in % [:ranks k]) rs)]
      (println " " k "first" (count (filter #{1} ranks)) "last" (count (filter #{3} ranks)) "mean rank" (/ (Math/round (* 100.0 (/ (reduce + ranks) (count ranks)))) 100.0)))))
(println "\n== by entry")
(doseq [[id rs] (sort (group-by :id rows))]
  (println id (str/join " | " (for [r rs] (str/join " " (map (comp name key) (sort-by val (:ranks r))))))))
