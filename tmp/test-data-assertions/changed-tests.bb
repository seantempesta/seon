;; Deftests whose text differs between two revisions of a file: bb changed-tests.bb REV-A REV-B PATH
(require '[clojure.string :as str] '[babashka.process :refer [shell]])
(let [[a b path] *command-line-args*
      text (fn [rev] (:out (shell {:out :string} "git" "show" (str rev ":" path))))
      tests (fn [t] (into {} (keep (fn [chunk] (when-let [[_ n] (re-find #"^(?:\^\{[^}]*\}\s*)?(?:\^:\S+\s+)*\n?\s*([^\s\]\[]+)" chunk)]
                                                  [n chunk])))
                          (rest (str/split t #"\n\(deftest\s+"))))
      before (tests (text a)) after (tests (text b))]
  (doseq [[n body] (sort after) :when (not= body (get before n))] (println n)))
