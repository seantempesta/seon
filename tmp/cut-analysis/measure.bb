#!/usr/bin/env bb
;; Cut-analysis measurement (2026-09-23). Run from the repo root:
;;   bb tmp/cut-analysis/measure.bb > tmp/cut-analysis/measure.out
;; Uses clj-kondo analysis (var-definitions row/end-row) over src/.
(require '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.string :as str])
(def raw (:out (p/sh ["clj-kondo" "--lint" "src" "--config"
                      "{:output {:format :edn} :analysis {:var-definitions {:shallow false}} :linters ^:replace {}}"])))
(def ana (:analysis (edn/read-string {:default (fn [_ v] v)} raw)))
(def defs (->> (:var-definitions ana)
               (filter :end-row)
               (map #(assoc % :lines (inc (- (:end-row %) (:row %)))))))
(def file-lines (memoize (fn [f] (vec (str/split-lines (slurp f))))))
(def branch-re #"\((?:if|if-let|if-some|when|when-let|when-some|when-not|if-not|cond|condp|case|cond->|cond->>|some->|try|catch)[\s\)]")
(defn span-text [{:keys [filename row end-row]}]
  (str/join "\n" (subvec (file-lines filename) (dec row) (min end-row (count (file-lines filename))))))
(defn max-depth [s]
  (loop [cs (seq s) d 0 m 0 in-str false esc false comment false]
    (if-let [c (first cs)]
      (cond comment (recur (rest cs) d m in-str false (not= c \newline))
            esc (recur (rest cs) d m in-str false false)
            in-str (recur (rest cs) d m (not= c \") (= c \\) false)
            (= c \\) (recur (rest (rest cs)) d m false false false)
            (= c \") (recur (rest cs) d m true false false)
            (= c \;) (recur (rest cs) d m false false true)
            (#{\( \[ \{} c) (recur (rest cs) (inc d) (max m (inc d)) false false false)
            (#{\) \] \}} c) (recur (rest cs) (dec d) m false false false)
            :else (recur (rest cs) d m false false false))
      m)))
(defn metrics [d] (let [t (span-text d)] (assoc d :branches (count (re-seq branch-re t)) :depth (max-depth t))))
(defn row [d] (format "%5d %4d %3d  %s/%s  %s:%d" (:lines d) (or (:branches d) 0) (or (:depth d) 0) (:ns d) (:name d) (:filename d) (:row d)))
(println "# 40 longest top-level definitions (lines branches max-depth  var  file:row)")
(let [top (->> defs (sort-by :lines >) (take 40) (map metrics))] (doseq [d top] (println (row d))))
(println "\n# 25 most-branching definitions")
(doseq [d (->> defs (filter #(> (:lines %) 15)) (map metrics) (sort-by :branches >) (take 25))] (println (row d)))
(println "\n# 15 deepest-nesting definitions")
(doseq [d (->> defs (filter #(> (:lines %) 15)) (map metrics) (sort-by :depth >) (take 15))] (println (row d)))
(println "\n# per-file lines (all src files, desc)")
(let [files (->> (file-seq (java.io.File. "src")) (filter #(re-find #"\.clj[cs]?$" (.getName %))) (map str))]
  (doseq [[f n] (->> files (map (fn [f] [f (count (file-lines f))])) (sort-by second >))] (println (format "%6d %s" n f)))
  (println (format "\ntotal src lines %d in %d files; %d var definitions" (reduce + (map (comp count file-lines) files)) (count files) (count defs))))
(println "\n# definition-line histogram: defs >100 lines, >50, >30")
(println (map (fn [k] [k (count (filter #(> (:lines %) k) defs)) (reduce + (map :lines (filter #(> (:lines %) k) defs)))]) [100 50 30]))
