;; Static audit only: EDN tasks and metadata read with edamame, never evaluated.
(require '[babashka.fs :as fs] '[clojure.edn :as edn] '[clojure.string :as str]
         '[babashka.process :as process] '[edamame.core :as e] '[cheshire.core :as json])
(defn unquote-symbol [x] (if (and (seq? x) (= 'quote (first x))) (second x) x))
(def revision (first *command-line-args*))
(defn read-file [p] (if revision (:out (process/shell {:out :string} "git" "show" (str revision ":" p))) (slurp (str p))))
(defn paths [roots extensions]
  (filter (fn [p] (some #(str/ends-with? p %) extensions))
          (if revision (str/split-lines (:out (apply process/shell {:out :string} "git" "ls-tree" "-r" "--name-only" revision "--" roots)))
              (map str (mapcat #(fs/glob % "**") roots)))))
(def tasks
  (vec (for [p (sort (paths ["resources/seon/tasks"] [".edn"]))
             t (edn/read-string (read-file p))]
         (assoc t :file (str p) :symbol (symbol (str (or (:seon.task/ns t) (fs/strip-ext (fs/file-name p))))
                                                               (str (:seon.task/name t)))))))
(def parsed
  (mapv (fn [p]
          (try
            (let [forms (e/parse-string-all (read-file p) {:all true :read-cond :allow :features #{:clj}
                                                           :auto-resolve (fn [alias] (if (= alias :current) 'audit.reader (symbol (str alias))))})
                  ns-name (second (first (filter #(and (seq? %) (= 'ns (first %))) forms)))]
              {:file (str p) :definitions
               (vec (for [f forms :when (and (seq? f) (symbol? (second f))
                                          (contains? '#{defn defn- def defonce defmacro deftest} (first f)))
                          :let [n (second f) attrs (first (filter map? (take 2 (drop 2 f))))
                                metadata (merge (meta n) attrs)]]
                      {:symbol (symbol (str ns-name) (name n)) :kind (first f)
                       :tasks (mapv unquote-symbol (:seon/tasks metadata))
                       :contract (contains? metadata :malli/schema)}))})
            (catch Exception ex {:file (str p) :error (ex-message ex)})))
        (sort (paths ["src" "test" "script"] [".clj" ".cljc"]))))
(def definitions (vec (mapcat (fn [p] (map #(assoc % :file (:file p)) (:definitions p))) parsed)))
(println (json/generate-string {:tasks tasks :definitions definitions :parse-errors (filterv :error parsed)} {:pretty true}))
