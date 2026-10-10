(ns seon.census
  "Source censuses: read files with edamame and answer sites as data.
  A site is `{:file path :in top-level-symbol :use symbol}`; no printed text."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]))

(def ^:private read-options
  {:all true
   :read-cond :allow
   :features #{:clj}
   :auto-resolve (fn [alias] (if (= :current alias) 'user (symbol (str alias))))
   :readers (fn [_] (fn [form] form))
   :syntax-quote {:resolve-symbol identity}})

(defn files
  "Relative paths of the Clojure files under `roots`, sorted."
  [roots extensions]
  (->> roots
       (mapcat #(file-seq (io/file %)))
       (filter #(and (.isFile ^java.io.File %) (some (fn [e] (str/ends-with? (.getName ^java.io.File %) e)) extensions)))
       (map #(.getPath ^java.io.File %))
       sort
       vec))

(defn top-level-forms
  "The forms of `path`, or `[]` with the path unreadable noted by `unreadable?`."
  [path]
  (try (edamame/parse-string-all (slurp path) read-options)
       (catch Exception _ ::unreadable)))

(defn- owner
  [form]
  (let [name-form (when (seq? form) (second form))]
    (if (symbol? name-form) name-form (if (seq? form) (first form) '<top>))))

(defn uses
  "Every symbol occurrence in `form` as `[symbol head?]`."
  [form]
  (let [walk (fn walk [x head?]
               (cond (symbol? x) [[x head?]]
                     (seq? x) (concat (when (seq x) (walk (first x) true)) (mapcat #(walk % false) (rest x)))
                     (map? x) (mapcat #(concat (walk (key %) false) (walk (val %) false)) x)
                     (coll? x) (mapcat #(walk % false) x)
                     :else nil))]
    (walk form false)))

(defn bare
  "The unqualified name of symbol `s` when it is bare or `clojure.core`-qualified, else nil."
  [s]
  (when (contains? #{nil "clojure.core"} (namespace s)) (name s)))

(defn scan
  "Scan `paths`: answer `{:files n :unreadable [path] :sites [site]}` where a site is every top-level
  form (`:file`, `:in`) with each symbol use satisfying `(use? symbol head?)`, as `:use`; `:forms` holds the form."
  [paths use?]
  (let [read (map (juxt identity top-level-forms) paths)]
    {:files (count paths)
     :unreadable (vec (for [[path forms] read :when (= ::unreadable forms)] path))
     :sites (vec (for [[path forms] read :when (not= ::unreadable forms)
                       form forms
                       :let [used (filter (fn [[s head?]] (use? s head?)) (uses form))]
                       :when (seq used)]
                   {:file path :in (owner form) :uses (vec (distinct (map first used))) :form form}))}))

(defn without-forms
  "Sites without their (large) forms, as plain data."
  [sites]
  (mapv #(dissoc % :form) sites))

;;; Prose in source: comments and docstrings, read by rewrite-clj so strings and code are never confused.

(def ^:private definers
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defonce" "defprotocol" "ns" "deftest" "defrecord"})

(defn- string-node?
  [n]
  (or (= :multi-line (node/tag n))
      (and (= :token (node/tag n)) (str/starts-with? (node/string n) "\""))))

(defn- significant
  [n]
  (remove #(or (node/whitespace-or-comment? %) (= :comma (node/tag %))) (node/children n)))

(defn- list-name
  [n]
  (when (= :list (node/tag n))
    (let [[head name-node] (significant n)]
      {:head (some-> head node/string) :name (some-> name-node node/string symbol)})))

(defn- prose-in
  "Every comment and docstring node under `n` as the node's text with its kind."
  [n]
  (concat (when (= :comment (node/tag n)) [[:comment (node/string n)]])
          (when (and (= :list (node/tag n)) (contains? definers (:head (list-name n))))
            (let [third (nth (vec (significant n)) 2 nil)]
              (when (and third (string-node? third)) [[:docstring (node/string third)]])))
          (when (node/inner? n) (mapcat prose-in (node/children n)))))

(defn prose
  "Comments and docstrings of `paths` as `{:file :in :kind :text}`, `:in` the top-level form's symbol."
  [paths]
  (vec (for [path paths
             top (node/children (parser/parse-file-all path))
             :let [in (or (:name (list-name top)) '<top>)]
             [kind text] (prose-in top)]
         {:file path :in in :kind kind :text text})))
