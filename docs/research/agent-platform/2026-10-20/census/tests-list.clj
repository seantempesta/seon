;; Builds the cited-test list per idea (wildcards expanded) from ideas.md and tests.edn.
(require '[clojure.edn :as edn] '[clojure.string :as str])
(def cdir "docs/research/agent-platform/2026-10-20/census/")
(def ts (edn/read-string (slurp (str cdir "tests.edn"))))
(def secs (rest (str/split (slurp (str cdir "ideas.md")) #"(?m)^## (?=\d+\. )")))
(def idea-syms
  (into (sorted-map)
    (for [s secs :let [n (parse-long (re-find #"^\d+" s))
                       syms (map (comp symbol second) (re-seq #"`([a-z0-9.\-]+-test/[a-z0-9\-?!*<>=]+)`" s))
                       exp (mapcat (fn [x] (if (= "*" (name x))
                                             (map :census/sym (filter #(= (str (namespace x)) (str (:census/ns %))) ts))
                                             [x])) syms)]]
      [n (vec (distinct exp))])))
(spit (str cdir "tests-idea-map.edn") (pr-str idea-syms))
(def all (vec (distinct (mapcat val idea-syms))))
(println (count all) (frequencies (map #(count (val %)) idea-syms)))
(spit "/private/tmp/claude-501/-Users-sean-src-seon/1b1c4b04-347e-4da0-8ce2-a44f5b41bc76/scratchpad/all.txt" (str/join "\n" all))
(let [by (into {} (map (juxt :census/sym identity)) ts)]
  (println "long" (count (filter #(:census/long? (by %)) all)) "unknown" (remove by all)))
