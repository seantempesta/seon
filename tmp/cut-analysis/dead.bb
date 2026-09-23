;; Join the indexed zero-caller sets (tmp/cut-analysis/indexed-callers.edn, from default's
;; :seon.fn/calls + :seon.fn/references facts) with clj-kondo spans, then drop any symbol
;; whose qualified or bare name appears in resources/, config/, bin/, script/ or deps.edn (data callers).
(require '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.string :as str])
(def ic (edn/read-string (slurp "tmp/cut-analysis/indexed-callers.edn")))
(def ana (:analysis (edn/read-string {:default (fn [_ v] v)} (:out (p/sh ["clj-kondo" "--lint" "src" "--config" "{:output {:format :edn} :analysis true :linters ^:replace {}}"])))))
(def span (into {} (map (fn [d] [(symbol (str (:ns d)) (str (:name d))) [(str/replace (:filename d) "/Users/sean/src/seon/" "") (:row d) (inc (- (or (:end-row d) (:row d)) (:row d)))]])) (:var-definitions ana)))
(def data-text (str/join "\n" (map slurp (filter #(.isFile %) (mapcat #(file-seq (java.io.File. %)) ["resources" "config" "bin" "script" "deps.edn" "build.clj"])))))
(def src-text (into {} (map (fn [f] [(str f) (slurp f)]) (filter #(re-find #"\.clj[cs]?$" (str %)) (file-seq (java.io.File. "src"))))))
(defn data-ref? [s] (str/includes? data-text (str s)))
(defn string-ref? [s] (some (fn [[_ t]] (str/includes? t (str "'" s))) src-text))
(doseq [k [:dead :test-only]]
  (let [rows (->> (k ic) (keep #(when-let [sp (span %)] (into [%] sp))) (remove #(data-ref? (first %))) (remove #(string-ref? (first %))) (sort-by #(- (nth % 3))))]
    (println "#" k (count rows) "defs," (reduce + (map #(nth % 3) rows)) "lines")
    (doseq [[s f r n] rows] (println (format "%4d %s %s:%d" n s f r)))))
