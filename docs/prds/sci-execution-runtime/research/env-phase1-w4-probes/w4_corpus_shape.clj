(ns w4-corpus-shape
  "W4 step 1 — what does a REALISTIC defn look like?

  The synthetic corpora in w4_fork_install_cost.clj must be sized from
  evidence, not taste. This reads every top-level defn/defn- form in
  first-party src/ with the Clojure reader (no regex over source text) and
  reports the distribution of source length and reader-node count, so the
  synthetic generator can be pinned to the measured median and p90.

  Run: see docs/prds/sci-execution-runtime/research/env-phase1-w4-probes/RUN.md"
  (:require [clojure.java.io :as io]))

(defn- source-file? [^java.io.File f]
  (let [n (.getName f)]
    (and (.isFile f)
         (or (.endsWith n ".clj") (.endsWith n ".cljc")))))

(defn- clj-files [root]
  (filterv source-file? (file-seq (io/file root))))

(defn- node-count [form]
  (if (coll? form)
    (reduce + 1 (map node-count (if (map? form) (apply concat form) (seq form))))
    1))

(defn- top-level-defns
  "Read one file's top-level forms; keep defn/defn- and record size facts.

  Reads with the Clojure reader in :clj feature mode with reader
  conditionals allowed and *read-eval* off, so first-party reader macros do
  not abort the walk; forms that cannot be read at all are counted, not
  fatal."
  [^java.io.File f]
  (let [text (slurp f)
        rdr (java.io.PushbackReader. (java.io.StringReader. text))
        opts {:eof ::eof :read-cond :allow :features #{:clj}}]
    (loop [acc [] unread 0]
      (let [form (try (binding [*read-eval* false] (read opts rdr))
                      (catch Exception _ ::unreadable))]
        (cond
          (= form ::eof) {:defns acc :unreadable unread}
          (= form ::unreadable) {:defns acc :unreadable (inc unread)}
          (and (seq? form)
               (contains? #{'defn 'defn-} (first form)))
          (recur (conj acc {:sym (second form)
                            :nodes (node-count form)
                            :chars (count (pr-str form))})
                 unread)
          :else (recur acc unread))))))

(defn- quantile [sorted-values q]
  (when (seq sorted-values)
    (nth sorted-values (min (dec (count sorted-values))
                            (long (Math/floor (* q (count sorted-values))))))))

(defn- summarize [label values]
  (let [s (vec (sort values))]
    {:label label
     :n (count s)
     :min (first s)
     :p25 (quantile s 0.25)
     :median (quantile s 0.50)
     :p75 (quantile s 0.75)
     :p90 (quantile s 0.90)
     :p99 (quantile s 0.99)
     :max (peek s)
     :mean (when (seq s) (long (/ (reduce + s) (count s))))}))

(defn run []
  (let [files (clj-files "src")
        results (map top-level-defns files)
        defns (mapcat :defns results)
        unreadable (reduce + (map :unreadable results))]
    {:probe/name "w4 corpus shape — realistic defn size, measured from src/"
     :probe/files (count files)
     :probe/unreadable-forms unreadable
     :probe/defns (count defns)
     :probe/chars (summarize :source-chars (map :chars defns))
     :probe/nodes (summarize :reader-nodes (map :nodes defns))}))
