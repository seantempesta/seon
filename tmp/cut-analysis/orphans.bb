;; Namespaces in src/ that no other src/, script/ or resources/ namespace requires.
;;   bb tmp/cut-analysis/orphans.bb
(require '[babashka.process :as p] '[clojure.edn :as edn])
(defn ana [paths] (:analysis (edn/read-string {:default (fn [_ v] v)}
  (:out (p/sh (concat ["clj-kondo" "--lint"] paths ["--config" "{:output {:format :edn} :analysis true :linters ^:replace {}}"]))))))
(def a (ana ["src" "script" "resources/seon/operator" "dev"]))
(def a-test (ana ["test"]))
(def src-ns (->> (:namespace-definitions a) (filter #(.startsWith (str (:filename %)) "/Users/sean/src/seon/src/")) (map :name) set))
(def used (->> (:namespace-usages a) (remove #(= (:from %) (:to %))) (map :to) set))
(def used-test (->> (:namespace-usages a-test) (map :to) set))
;; also symbol-qualified var usages (requiring-resolve etc. show as var-usages with :to)
(def var-used (->> (:var-usages a) (remove #(= (:from %) (:to %))) (map :to) set))
(doseq [n (sort (remove (into used var-used) src-ns))]
  (println n (if (used-test n) "(tests only)" "(no requirer)")))
