;; Fold every task entry's :seon.task/must-read and :seon.task/evidence into its intent as inline
;; references, then remove both keys. rewrite-clj edits the EDN; nothing is regex.
;; Usage (from the checkout): clojure -Sdeps '{:deps {rewrite-clj/rewrite-clj {:local/root "reference-code/rewrite-clj"}}}' -M -i docs/research/agent-platform/2026-10-12/task-writeup-fold.clj -e '(fold/fold-dir! "resources/seon/tasks")'
;; Measured at 1759ec3ae on a copy: 807 entries, 1,915 must-read symbols and 737 evidence paths folded, none lost, no other member changed.
(ns fold
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

(defn- key-name [loc]
  (let [k (z/sexpr loc)]
    (when (and (keyword? k) (contains? #{nil "seon.task"} (namespace k))) (name k))))

(defn- entries
  "The key locs of a map loc's direct children, by key name."
  [map-loc]
  (loop [loc (z/down map-loc) found {}]
    (if (nil? loc)
      found
      (let [found (if-let [kn (key-name loc)] (assoc found kn loc) found)]
        (recur (some-> loc z/right z/right) found)))))

(defn- spoken
  "\"a\", \"a and b\", \"a, b and c\"."
  [items]
  (if (< (count items) 2)
    (first items)
    (str (str/join ", " (butlast items)) " and " (last items))))

(defn- folded-intent
  "The intent with each must-read symbol and evidence path it does not already cite as a code span appended."
  [intent must-read evidence]
  (let [cites? #(str/includes? intent (str "`" % "`"))
        added (->> (concat (sort-by str must-read) (sort evidence))
                   (map str)
                   (remove cites?)
                   (map #(str "`" % "`"))
                   vec)]
    (if (empty? added)
      intent
      (str (str/trimr intent) (if (str/ends-with? (str/trimr intent) ".") " " ". ") "Read " (spoken added) "."))))

(defn- remove-entry
  "The map loc with the key `kn` and its value removed."
  [map-loc kn]
  (let [k (get (entries map-loc) kn)]
    (if k
      (loop [l (-> k z/right z/remove z/remove)] (if (= :map (z/tag l)) l (recur (z/up l))))
      map-loc)))

(defn- fold-map [map-loc]
  (let [{:strs [intent must-read evidence]} (entries map-loc)]
    (if (and intent (or must-read evidence))
      (let [text (z/sexpr (z/right intent))
            folded (folded-intent text (some-> must-read z/right z/sexpr) (some-> evidence z/right z/sexpr))
            loc (-> (z/right intent) (z/replace (n/string-node (let [printed (pr-str folded)] (subs printed 1 (dec (count printed)))))) z/up)]
        (-> loc (remove-entry "must-read") (remove-entry "evidence")))
      map-loc)))

(defn fold-text [text]
  (loop [loc (z/of-string text {:track-position? false})]
    (cond
      (z/end? loc) (z/root-string loc)
      (= :map (z/tag loc)) (let [loc (fold-map loc)] (recur (z/next loc)))
      :else (recur (z/next loc)))))

(defn fold-dir! [dir]
  (doseq [f (sort (.listFiles (io/file dir))) :when (str/ends-with? (.getName f) ".edn")]
    (let [text (slurp f) out (fold-text text)]
      (when (not= text out) (spit f out) (println "folded" (.getPath f))))))
