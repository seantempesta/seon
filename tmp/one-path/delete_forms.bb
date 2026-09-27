;; Remove named top-level forms (defn/defn-/def/declare) from files; print the removed ranges.
(require '[clojure.string :as str])
(defn starts [lines]
  (keep-indexed (fn [i l] (when (re-find #"^\((defn-?|def|defmacro|declare|defonce|defmulti|defmethod|defrecord|deftype|defprotocol|ns|comment|schema.edn/load!|register!|.*\/register!) " l) i)) lines))
(defn remove-forms [path names]
  (let [lines (vec (str/split-lines (slurp path)))
        ss (vec (starts lines))
        n (count lines)
        ranges (for [[i j] (map vector ss (conj (vec (rest ss)) n))
                     :let [nm (second (re-find #"^\((?:defn-?|def|defmacro|declare|defonce) (?:\^\S+ |\^\{[^}]*\} )?(\S+)" (nth lines i)))]
                     :when (contains? names nm)]
                 [i j nm])
        dead (set (mapcat (fn [[i j _]] (range i j)) ranges))]
    (spit path (str (str/join "\n" (keep-indexed (fn [i l] (when-not (dead i) l)) lines)) "\n"))
    (doseq [[i j nm] ranges] (println (format "%s\t%d\t%d\t%s" path (inc i) (- j i) nm)))
    (let [missing (remove (set (map last ranges)) names)] (when (seq missing) (println "MISSING" path (vec missing))))))
(let [[root spec-file] *command-line-args*
      spec (read-string (slurp spec-file))]
  (doseq [[f names] spec] (remove-forms (str root "/" f) (set names))))
