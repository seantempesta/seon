(ns seon.rename-task-intent
  "The `:seon.task/intent` to `:seon.task/plan` sweep (TASK-CHAIN S1a), by syntax with rewrite-clj.

  clojure -Sdeps '{:paths [\"script\" \"src\" \"resources\"]}' -M -m seon.rename-task-intent report ROOT
  ... -m seon.rename-task-intent write ROOT
  ... -m seon.rename-task-intent compare OLD-ROOT NEW-ROOT

  `report` counts, writes nothing. `write` rewrites the Clojure and EDN files under ROOT's sweep
  directories: keyword nodes `:seon.task/intent`, the keys `:intent` of `#:seon.task{...}` maps,
  the symbol `intent` in a `:seon.task/keys [...]` destructuring vector, and the same inside string
  values that read as Clojure (fixtures, task text that cites the key). `compare` reads both trees
  token by token and answers every difference that is not the rename. Markdown is edited by hand.
  Answers are data: counts per file category and the hand list (comments, strings that do not
  read, auto-resolved keywords)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [seon.render.value :as value]))

(def ^:private old-name "intent")
(def ^:private new-name "plan")
(def ^:private old-key :seon.task/intent)
(def ^:private new-key :seon.task/plan)
(def ^:private qualifier ":seon.task")

(def ^:private keyword-renames
  "Every keyword the sweep renames: the attribute, and the file admission's refusal variant naming it."
  {old-key new-key
   :no-intent :no-plan})

(def sweep-dirs
  "The directories the sweep rewrites, under the root."
  ["resources/seon/tasks" "resources/seon/schemas" "src" "test" "script" "bench" "config"])

(def ^:private self "script/seon/rename_task_intent.clj")

(defn- skipped?
  "Whether `node` takes no place in a map's key/value pairing."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [node]
  (boolean (or (n/whitespace-or-comment? node) (= :uneval (n/tag node)))))

(defn- keyword-node?
  "Whether `node` is the keyword `k`, written in full (never auto-resolved)."
  {:malli/schema [:=> [:cat :any :keyword] :boolean]}
  [node k]
  (and (= :token (n/tag node)) (keyword? (:k node)) (not (:namespaced? node)) (= k (:k node))))

(defn- symbol-node?
  "Whether `node` is the symbol named `s`."
  {:malli/schema [:=> [:cat :any :string] :boolean]}
  [node s]
  (and (= :token (n/tag node)) (symbol? (:value node)) (= s (str (:value node)))))

(defn- string-node?
  "Whether `node` is a string literal (rewrite-clj tags a one-line string :token)."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [node]
  (contains? node :lines))

(defn- string-leaf?
  "Whether a leaf tag is a string's."
  {:malli/schema [:=> [:cat :keyword] :boolean]}
  [tag]
  (contains? #{:token :multi-line} tag))

(defn- map-keys-renamed
  "The children of a map with every key position satisfying `key?` renamed by `rename`."
  {:malli/schema [:=> [:cat [:sequential :any] ifn? ifn?] [:sequential :any]]}
  [children key? rename]
  (loop [out [] [c & more] children i 0]
    (cond (nil? c) out
          (skipped? c) (recur (conj out c) more i)
          :else (recur (conj out (if (and (even? i) (key? c)) (rename c) c)) more (inc i)))))

(defn- task-nsmap-index
  "The index of the map inside a `#:seon.task` namespaced-map node's children (whitespace may stand
  between the qualifier and the map), or nil when the node is another namespaced map."
  {:malli/schema [:=> [:cat [:sequential :any]] [:maybe :int]]}
  [children]
  (let [[q & more] children
        i (first (keep-indexed (fn [i c] (when (= :map (n/tag c)) (inc i))) more))]
    (when (and (= :map-qualifier (n/tag q)) (= qualifier (n/string q)) i) i)))

(declare rewrite-node)

(defn- rewrite-string
  "A string node whose value reads as Clojure and cites the old name, rewritten as forms and
  escaped again; `node` itself when the value does not read or nothing changes."
  {:malli/schema [:=> [:cat :any] :any]}
  [node]
  (let [value (n/sexpr node)]
    (if-not (str/includes? value old-name)
      node
      (let [forms (try (p/parse-string-all value) (catch Exception _ nil))
            new-value (some-> forms rewrite-node n/string)]
        (if (or (nil? new-value) (= value new-value))
          node
          (let [esc (fn [s] (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")))
                multi? (= :multi-line (n/tag node))
                lines (if multi?
                        (mapv esc (str/split new-value #"\n" -1))
                        [(str/replace (esc new-value) "\n" "\\n")])
                out (n/string-node lines)]
            (when-not (= new-value (n/sexpr out))
              (throw (ex-info "A rewritten string does not read back to its value." {:value new-value})))
            out))))))

(defn rewrite-node
  "`node` with the rename applied everywhere its syntax names the task's intent."
  {:malli/schema [:=> [:cat :any] :any]}
  [node]
  (cond
    (some #(when (keyword-node? node %) %) (keys keyword-renames))
    (n/keyword-node (keyword-renames (:k node)))
    (string-node? node) (rewrite-string node)
    (n/inner? node)
    (let [children (mapv rewrite-node (n/children node))
          children (case (n/tag node)
                     :namespaced-map
                     (if-let [i (task-nsmap-index children)]
                       (update children i (fn [m] (n/replace-children
                                                   m (map-keys-renamed (n/children m) #(keyword-node? % :intent)
                                                                       (fn [_] (n/keyword-node :plan))))))
                       children)
                     children)]
      (n/replace-children node children))
    :else node))

(defn- destructuring-renamed
  "`node` with the symbol `intent` renamed in the vector value of every `:seon.task/keys` key of a map."
  {:malli/schema [:=> [:cat :any] :any]}
  [node]
  (if-not (n/inner? node)
    node
    (let [children (mapv destructuring-renamed (n/children node))
          children
          (if (= :map (n/tag node))
            (loop [out [] [c & more] children prev-keys? false]
              (cond (nil? c) out
                    (skipped? c) (recur (conj out c) more prev-keys?)
                    prev-keys? (recur (conj out (if (= :vector (n/tag c))
                                                  (n/replace-children
                                                   c (mapv #(if (symbol-node? % old-name) (n/token-node (symbol new-name)) %)
                                                           (n/children c)))
                                                  c))
                                      more false)
                    :else (recur (conj out c) more (keyword-node? c :seon.task/keys))))
            children)]
      (n/replace-children node children))))

(def plan-description
  "The corrected schema description of `:seon.task/plan` (TASK-CHAIN section 2.2): what the text is, then what HEAD still does with it."
  (str "The plan: Markdown that starts as the user's request and that every task improves, in blocks such as "
       "## Problem, ## What exists and ## Approach, with the references it cites (seon.ref/names). Declared on the subject "
       "(required on a task with no parent), inherited down :seon.task/children, a child's own value overwriting "
       "(seon.task/effective)."))

(defn- value-replaced
  "The children of a map with the value after the key `k` replaced by `value-node`."
  {:malli/schema [:=> [:cat [:sequential :any] :keyword :any] [:sequential :any]]}
  [children k value-node]
  (loop [out [] [c & more] children i 0]
    (cond (nil? c) out
          (skipped? c) (recur (conj out c) more i)
          (and (odd? i) (keyword-node? (nth (remove skipped? out) (dec i)) k)) (recur (conj out value-node) more (inc i))
          :else (recur (conj out c) more (inc i)))))

(defn plan-described
  "The parsed schema file `root` with the `:plan` description of `#:seon.task` replaced by `plan-description`."
  {:malli/schema [:=> [:cat :any] :any]}
  [root]
  (letfn [(in-vector [vec-node]
            (n/replace-children vec-node
                                (mapv #(if (= :map (n/tag %))
                                         (n/replace-children % (value-replaced (n/children %) :description (n/string-node [plan-description])))
                                         %)
                                      (n/children vec-node))))
          (in-map [m]
            (n/replace-children m (loop [out [] [c & more] (n/children m) i 0]
                                    (cond (nil? c) out
                                          (skipped? c) (recur (conj out c) more i)
                                          (and (odd? i) (keyword-node? (nth (remove skipped? out) (dec i)) :plan))
                                          (recur (conj out (in-vector c)) more (inc i))
                                          :else (recur (conj out c) more (inc i))))))
          (walk [node]
            (cond (and (= :namespaced-map (n/tag node)) (task-nsmap-index (n/children node)))
                  (let [cs (vec (n/children node))] (n/replace-children node (update cs (task-nsmap-index cs) in-map)))
                  (n/inner? node) (n/replace-children node (mapv walk (n/children node)))
                  :else node))]
    (walk root)))

(def ^:private schema-file "resources/seon/schemas/seon.task.edn")

(defn rewrite-forms
  "The whole rewrite of one parsed file or string: keywords, namespaced-map keys, destructuring."
  {:malli/schema [:=> [:cat :any] :any]}
  [root]
  (destructuring-renamed (rewrite-node root)))

(defn rewrite-file-forms
  "`rewrite-forms` of file `rel`'s parsed root, with the schema file's plan description corrected."
  {:malli/schema [:=> [:cat :string :any] :any]}
  [rel root]
  (cond-> (rewrite-forms root)
    (= rel schema-file) plan-described))

(defn occurrences
  "The places in `node` that the sweep owns: keyword uses, `#:seon.task` map keys, destructured
  symbols, and the same inside strings that read as Clojure. Answers {kind count}."
  {:malli/schema [:=> [:cat :any] [:map-of :keyword :int]]}
  [node]
  (let [here (cond
               (some #(keyword-node? node %) (keys keyword-renames)) {:keyword 1}
               (string-node? node)
               (let [v (n/sexpr node)
                     forms (when (str/includes? v old-name) (try (p/parse-string-all v) (catch Exception _ nil)))]
                 (if forms
                   (occurrences forms)
                   (if (re-find #"seon\.task/intent" v) {:string-unread 1} {})))
               :else {})
        inner (when (n/inner? node)
                (let [cs (n/children node)
                      own (case (n/tag node)
                            :namespaced-map
                            (if-let [i (task-nsmap-index cs)]
                              {:nsmap-key (count (filter #(keyword-node? % :intent)
                                                         (take-nth 2 (remove skipped? (n/children (nth cs i))))))}
                              {})
                            :map (let [kvs (remove skipped? cs)]
                                   (if (some #(keyword-node? % :seon.task/keys) (take-nth 2 kvs))
                                     {:destructuring (count (filter #(and (= :vector (n/tag %))
                                                                          (some (fn [s] (symbol-node? s old-name)) (n/children %)))
                                                                    (take-nth 2 (rest kvs))))}
                                     {}))
                            {})]
                  (apply merge-with + own (map occurrences cs))))]
    (into {} (remove (comp zero? val)) (merge-with + here inner))))

(defn- comments-citing
  "The comment nodes of `node` that cite the key by name, as text."
  {:malli/schema [:=> [:cat :any] [:sequential :string]]}
  [node]
  (let [own (when (and (= :comment (n/tag node)) (re-find #"seon\.task/intent|:intent\b" (n/string node)))
              [(str/trim (n/string node))])]
    (concat own (when (n/inner? node) (mapcat comments-citing (n/children node))))))

(defn- unread-strings
  "The strings of `node` that cite the key and do not read as Clojure, as short snippets for the hand list."
  {:malli/schema [:=> [:cat :any] [:sequential :string]]}
  [node]
  (let [v (when (string-node? node) (n/sexpr node))
        unread? (and v (str/includes? v old-name) (re-find #"seon\.task/intent" v)
                     (nil? (try (p/parse-string-all v) (catch Exception _ nil))))]
    (concat (when unread? [(re-find #".{0,50}seon\.task/intent.{0,30}" (str/replace v "\n" " "))])
            (when (n/inner? node) (mapcat unread-strings (n/children node))))))

(defn- sweep-file?
  "Whether `rel` is a file the sweep reads."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [rel]
  (boolean (and (re-find #"\.(clj|cljc|edn)$" rel) (not= rel self))))

(defn sweep-files
  "The relative paths of the files under `root` the sweep reads, sorted."
  {:malli/schema [:=> [:cat :string] [:sequential :string]]}
  [root]
  (->> sweep-dirs
       (mapcat (fn [d] (let [dir (io/file root d)]
                         (when (.isDirectory dir)
                           (map #(str d "/" (.getPath (.relativize (.toURI dir) (.toURI %))))
                                (filter #(.isFile %) (file-seq dir)))))))
       (filter sweep-file?)
       sort))

(defn category
  "The file's category for the inventory."
  {:malli/schema [:=> [:cat :string] :keyword]}
  [rel]
  (cond (str/starts-with? rel "resources/seon/tasks/") :task-files
        (str/starts-with? rel "resources/seon/schemas/") :schemas
        (str/starts-with? rel "src/") :src
        (str/starts-with? rel "test/") :test
        (str/starts-with? rel "script/") :script
        :else :other))

(defn file-report
  "The inventory of one file: its text, parsed root, occurrences and hand list; nil when it holds none."
  {:malli/schema [:=> [:cat :string :string] [:maybe :map]]}
  [root rel]
  (let [text (slurp (io/file root rel))]
    (when (str/includes? text old-name)
      (let [parsed (p/parse-string-all text)
            _ (when-not (= text (n/string parsed))
                (throw (ex-info "A file does not round-trip through rewrite-clj." {:file rel})))
            found (occurrences parsed)
            comments (vec (comments-citing parsed))]
        (when (or (seq found) (seq comments))
          {:file rel :text text :root parsed :occurrences found :comments comments :unread (vec (unread-strings parsed))})))))

(defn report
  "The inventory under `root`: counts per category and the hand list."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (let [reports (keep #(file-report root %) (sweep-files root))]
    {:files (count reports)
     :by-category (into (sorted-map)
                        (map (fn [[c rs]] [c {:files (count rs) :occurrences (apply merge-with + (map :occurrences rs))}]))
                        (group-by (comp category :file) reports))
     :hand (into [] (mapcat (fn [r] (concat (map (fn [c] {:file (:file r) :comment c}) (:comments r))
                                            (map (fn [u] {:file (:file r) :string-unread u}) (:unread r)))))
                 reports)}))

(defn- leaves
  "The non-whitespace leaf tokens of `node` in order, as [tag text]."
  {:malli/schema [:=> [:cat :any] [:sequential [:tuple :keyword :string]]]}
  [node]
  (cond (n/whitespace? node) []
        (n/inner? node) (into [[(n/tag node) (if (= :namespaced-map (n/tag node)) (n/string (first (n/children node))) "")]]
                              (mapcat leaves (n/children node)))
        :else [[(n/tag node) (n/string node)]]))

(defn- rename-pair?
  "Whether `old` and `revised` are the texts of one renamed token."
  {:malli/schema [:=> [:cat :string :string] :boolean]}
  [old revised]
  (contains? (into #{[(str :intent) (str :plan)] [old-name new-name]}
                   (map (fn [[o r]] [(str o) (str r)])) keyword-renames)
             [old revised]))

(defn- string-leaf-kind
  "How two string leaves differ: :same, :renamed (their values differ only by the rename) or :other."
  {:malli/schema [:=> [:cat :string :string] :keyword]}
  [old revised]
  (if (= old revised)
    :same
    (let [parse (fn [leaf] (try (p/parse-string-all (n/sexpr (p/parse-string leaf))) (catch Exception _ nil)))
          o (parse old) nw (parse revised)]
      (if (and o nw (= (count (leaves o)) (count (leaves nw)))
               (every? (fn [[a b]] (or (= a b) (rename-pair? (second a) (second b))))
                       (map vector (leaves o) (leaves nw))))
        :renamed
        :other))))

(defn compare-files
  "Compare the leaf tokens of one file in two trees: counts of identical leaves, renamed keywords,
  nsmap keys, destructured symbols and renamed strings, and every other difference."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [old-root new-root rel]
  (let [old-l (leaves (p/parse-string-all (slurp (io/file old-root rel))))
        new-l (leaves (p/parse-string-all (slurp (io/file new-root rel))))]
    (if (not= (count old-l) (count new-l))
      {:file rel :other [{:structure [(count old-l) (count new-l)]}]}
      (reduce (fn [acc [[ot os] [nt nw-text]]]
                (cond (and (= ot nt) (= os nw-text)) (update acc :same inc)
                      (rename-pair? os nw-text) (update acc (get {(str :intent) :nsmap-key old-name :symbol} os :keyword) inc)
                      (and (= ot nt) (string-leaf? ot) (= :renamed (string-leaf-kind os nw-text))) (update acc :string inc)
                      :else (update acc :other conj {:old os :new nw-text})))
              {:file rel :same 0 :keyword 0 :nsmap-key 0 :symbol 0 :string 0 :other []}
              (map vector old-l new-l)))))

(defn compare-trees
  "Compare every swept file of two trees; answers the sums and the files with a difference that is not the rename."
  {:malli/schema [:=> [:cat :string :string] :map]}
  [old-root new-root]
  (let [results (mapv #(compare-files old-root new-root %) (sweep-files old-root))]
    {:files (count results)
     :same (transduce (map :same) + 0 (remove :structure results))
     :keyword (transduce (map :keyword) + 0 results)
     :nsmap-key (transduce (map :nsmap-key) + 0 results)
     :symbol (transduce (map :symbol) + 0 results)
     :string (transduce (map :string) + 0 results)
     :unexplained (into [] (filter #(seq (:other %))) results)}))

(defn -main
  "report ROOT | write ROOT | compare OLD NEW"
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& args]
  (let [[mode a b] args]
    (println
     (value/core-text
     (case mode
           "report" (report a)
           "write" (let [changed (doall (for [rel (sweep-files a)
                                              :let [r (file-report a rel)]
                                              :when r
                                              :let [new-text (n/string (rewrite-file-forms rel (:root r)))]
                                              :when (not= (:text r) new-text)]
                                          (do (spit (io/file a rel) new-text) rel)))]
                     {:changed (count changed) :files (vec changed)})
           "compare" (compare-trees a b))
     0))
    (shutdown-agents)))
