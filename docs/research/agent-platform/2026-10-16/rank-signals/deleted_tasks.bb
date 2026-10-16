;; For each candidate commit touching task files, the task entries deleted in that commit (name -> plan at the parent).
;; Usage: bb deleted_tasks.bb cand.jsonl out.jsonl
(require '[cheshire.core :as json] '[clojure.edn :as edn] '[clojure.java.shell :as sh] '[clojure.java.io :as io] '[clojure.string :as str])
(def repo "/Users/sean/src/seon")
(defn show [rev path] (let [r (sh/sh "git" "-C" repo "show" (str rev ":" path))] (when (zero? (:exit r)) (:out r))))
(defn tasks [text path]
  (when text
    (try (let [v (edn/read-string {:default (fn [_ v] v)} text)
               ns (-> path (str/replace #".*/" "") (str/replace #"\.edn$" ""))]
           (into {} (for [m (if (map? v) (or (:tasks v) [v]) v) :when (map? m)
                          :let [n (or (:seon.task/name m) (:name m) (some-> (:seon.task/id m) str))]
                          :when n]
                      [(str ns "/" n) {:plan (str/join "\n\n" (remove str/blank? (map str [(or (:seon.task/plan m) (:plan m) (:seon.task/intent m) (:intent m) "")
                                                                                    (or (:seon.task/evidence m) (:evidence m) "")])))
                                     :must-read (mapv str (or (:seon.task/must-read m) (:must-read m) []))}])))
         (catch Exception _ nil))))
(let [[in out] *command-line-args*]
  (with-open [w (io/writer out)]
    (doseq [line (line-seq (io/reader in))
            :let [c (json/parse-string line true)]
            :when (seq (:taskfiles c))]
      (let [del (into {} (for [p (:taskfiles c)
                               :let [old (tasks (show (:parent c) p) p) new (tasks (show (:commit c) p) p)]
                               [k v] old :when (not (contains? (or new {}) k))]
                           [k v]))]
        (when (seq del)
          (.write w (str (json/generate-string (assoc c :deleted del)) "\n")))))))
