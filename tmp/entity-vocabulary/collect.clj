;; Collect every symbol token whose name has a `row`/`rows` word segment,
;; with file:line and the enclosing top-level form's first symbol, and every
;; keyword with such a segment. Run: bb tmp/entity-vocabulary/collect.clj <files...>
(require '[rewrite-clj.parser :as p]
         '[rewrite-clj.node :as n]
         '[clojure.string :as str]
         '[clojure.pprint :as pp])

(def segment #"(?:^|[^a-zA-Z])rows?(?:$|[^a-zA-Z])")

(defn row-name? [s] (boolean (re-find segment s)))

(defn walk [node f]
  (f node)
  (when (n/inner? node)
    (doseq [c (n/children node)] (walk c f))))

(def out (atom {:symbols {} :keywords {}}))

(defn keys-vector-symbols
  "Symbols tied to keywords by :keys/:syms destructuring inside `node`."
  [node]
  (let [acc (atom #{})]
    (walk node
          (fn [m]
            (when (= :map (n/tag m))
              (doseq [[k v] (partition 2 (remove n/whitespace-or-comment? (n/children m)))]
                (when (and (= :token (n/tag k))
                           (keyword? (n/sexpr k))
                           (#{"keys" "syms"} (name (n/sexpr k)))
                           (= :vector (n/tag v)))
                  (doseq [s (n/children v)
                          :when (and (= :token (n/tag s)) (symbol? (n/sexpr s)))]
                    (swap! acc conj (name (n/sexpr s)))))))))
    @acc))

(doseq [file *command-line-args*]
  (let [root (try (p/parse-file-all file)
                  (catch Exception e (binding [*out* *err*] (println "PARSE-FAIL" file (ex-message e))) nil))]
    (when root
      (doseq [top (n/children root)]
        (let [tied (keys-vector-symbols top)
              head (when (n/inner? top)
                     (some->> (n/children top) (remove n/whitespace-or-comment?) second
                              (#(when (= :token (n/tag %)) (str (n/string %))))))]
          (walk top
                (fn [node]
                  (when (= :token (n/tag node))
                    (let [v (try (n/sexpr node) (catch Exception _ nil))
                          {:keys [row]} (meta node)]
                      (cond
                        (and (symbol? v) (row-name? (name v)))
                        (swap! out update-in [:symbols (str v)] (fnil conj [])
                               {:file file :line row :top head :tied (contains? tied (name v))})
                        (and (keyword? v) (row-name? (name v)))
                        (swap! out update-in [:keywords (str (n/string node))] (fnil conj [])
                               {:file file :line row})))))))))))

(spit (or (System/getenv "OUT") "/dev/stdout")
      (with-out-str (pp/pprint @out)))
