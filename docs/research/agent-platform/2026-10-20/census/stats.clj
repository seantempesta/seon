;; Prints the aggregate numbers quoted in static.md. Run after analyze.clj.
(require '[clojure.edn :as edn] '[clojure.string :as str])
(def dir "docs/research/agent-platform/2026-10-20/census/")
(def fs (edn/read-string (slurp (str dir "functions.edn"))))
(def ts (edn/read-string (slurp (str dir "tests.edn"))))
(def nss (edn/read-string (slurp (str dir "namespaces.edn"))))
(defn p [& xs] (apply println xs))
(defn fr [k xs] (into (sorted-map) (frequencies (map k xs))))
(p "TOTAL fns" (count fs) "lines" (reduce + (keep :census/lines fs)) "ns" (count nss) "tests" (count ts))
(p "src fns" (count (filter #(str/starts-with? (:census/file %) "src/") fs)) "script fns" (count (filter #(str/starts-with? (:census/file %) "script/") fs)))
(p "kind" (fr :census/kind fs))
(p "private" (fr :census/private? fs) "host-bound" (fr :census/host-bound? fs))
(p "contract" (fr :census/contract fs))
(let [c (filter #(#{:fn :macro} (:census/kind %)) fs)] (p "contractable" (count c) (fr :census/contract c)))
(p "verdict: ns" (fr :census/verdict nss))
(doseq [v [:replaced :port :unclear]]
  (let [f (filter #(= v (:census/verdict %)) fs) c (filter #(#{:fn :macro} (:census/kind %)) f)]
    (p v "ns" (count (filter #(= v (:census/verdict %)) nss)) "fns" (count f) "lines" (reduce + (keep :census/lines f)) "contract" (fr :census/contract c)
       "lookups" (count (filter :census/lookups f)) "try" (count (filter :census/try? f)) "dead" (fr :census/dead f)
       "tests-reaching-sum" (reduce + (map :census/reaching-tests f)))))
(p "jolt-status for port ns" (frequencies (map :census/jolt-status (filter #(= :port (:census/verdict %)) nss))))
(p "port lines by jolt-status"
   (let [m (into {} (map (juxt :census/ns :census/jolt-status)) nss)]
     (into {} (map (fn [[k v]] [k (reduce + (keep :census/lines v))]) (group-by (fn [f] (m (:census/ns f))) (filter #(= :port (:census/verdict %)) fs))))))
