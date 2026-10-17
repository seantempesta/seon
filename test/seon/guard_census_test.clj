(ns seon.guard-census-test
  "The error law's ratchet (AGENTS.md, Nothing crashes; owner 2026-10-17): every function whose definition changed
  since the law's commit holds no guard. Old guards leave with their code; none enters with a change."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [edamame.core :as edamame]))

(def ^:private law-commit
  "The commit that introduced the error law: functions changed since it are judged."
  "1821d961f")

(def ^:private general-catches
  "Exception classes only a guard or the entrant boundary catches: an interop edge catches its own exception."
  #{'Throwable 'Exception 'RuntimeException 'Error 'Object
    'java.lang.Throwable 'java.lang.Exception 'java.lang.RuntimeException 'java.lang.Error 'java.lang.Object})

(defn- git
  "Run git with `args` in the checkout `repository`: its standard output."
  {:malli/schema [:=> [:cat :string [:* :string]] :string]}
  [repository & args]
  (let [{:keys [exit out err]} (apply shell/sh "git" "-C" repository args)]
    (when-not (zero? exit) (throw (ex-info err {:git args})))
    out))

(defn- blob-id
  "Git's blob id of `bytes`: the SHA-1 of its `blob <size>\\0` header and content."
  {:malli/schema [:=> [:cat bytes?] :string]}
  [^bytes bytes]
  (let [digest (java.security.MessageDigest/getInstance "SHA-1")]
    (.update digest (.getBytes (str "blob " (alength bytes) "\u0000") "UTF-8"))
    (.update digest bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))

(defn- top-level-functions
  "Each `defn` and `defn-` of Clojure `text`, by name: its namespace and its form, read structurally (whitespace and
  comments never count as a change)."
  {:malli/schema [:=> [:cat :string] [:map-of :symbol [:tuple [:maybe :symbol] :seon.batch/form]]]}
  [text]
  (let [forms (edamame/parse-string-all text {:all true :auto-resolve (fn [alias] (if (= :current alias) 'user alias))
                                              :readers (fn [_] identity) :read-cond :allow :features #{:clj}})
        ns-name (some #(when (and (seq? %) (= 'ns (first %))) (second %)) forms)]
    (into {} (keep (fn [form]
                     (when (and (seq? form) (#{'defn 'defn-} (first form)) (symbol? (second form)))
                       [(second form) [ns-name form]])))
          forms)))

(defn- changed-functions
  "Every function of the program's `src` whose form differs from the law's commit's, or which it lacks: each as its
  namespace, name and form. Only files whose bytes differ are read twice."
  {:malli/schema [:=> [:cat :string :string] [:vector [:tuple [:maybe :symbol] :symbol :seon.batch/form]]]}
  [program repository]
  (let [before (into {} (keep (fn [line] (let [[meta path] (str/split line #"\t")]
                                           (when (str/ends-with? path ".clj") [path (last (str/split meta #" "))]))))
                     (str/split-lines (git repository "ls-tree" "-r" law-commit "src")))
        files (filter #(str/ends-with? (.getName ^java.io.File %) ".clj") (file-seq (io/file program "src")))]
    (into [] (mapcat (fn [^java.io.File file]
                       (let [path (subs (.getPath file) (inc (count program)))
                             bytes (java.nio.file.Files/readAllBytes (.toPath file))
                             old-blob (before path)]
                         (when (not= old-blob (blob-id bytes))
                           (let [now (top-level-functions (String. bytes "UTF-8"))
                                 then (if old-blob (top-level-functions (git repository "cat-file" "-p" old-blob)) {})]
                             (for [[name [ns-name form]] (sort-by key now)
                                   :when (not= form (second (then name)))]
                               [ns-name name form]))))))
          files)))

(defn- schema-definition
  "The form the schema resource `resources/seon/schemas/<ns>.edn` declares for the named schema `k`, if any."
  {:malli/schema [:=> [:cat :keyword] [:maybe :seon.batch/form]]}
  [k]
  (let [file (io/file "resources/seon/schemas" (str (namespace k) ".edn"))]
    (when (.exists file)
      (get (edamame/parse-string (slurp file) {:readers (fn [_] identity) :auto-resolve name}) k))))

(defn- error-variant?
  "Whether the output schema form `schema` names an error variant: a key of `seon.error`, or one whose name says
  error or refusal, found in the form or through a named schema whose definition is a union holding one."
  {:malli/schema [:=> [:cat :seon.batch/form] :boolean]}
  [schema]
  (letfn [(variant-name? [k] (and (qualified-keyword? k)
                                  (or (str/starts-with? (namespace k) "seon.error")
                                      (some (fn [word] (str/includes? (name k) word)) ["error" "refusal"]))))
          ;; a named schema is followed only where it stands as the whole value or a union member
          (union-members [form seen]
            (cond (and (qualified-keyword? form) (not (seen form)))
                  (if-let [definition (schema-definition form)] (cons form (union-members definition (conj seen form))) [form])
                  (and (vector? form) (#{:or :and :multi} (first form)))
                  (mapcat #(union-members % seen) (rest form))
                  :else []))]
    (boolean (or (some variant-name? (tree-seq coll? seq schema))
                 (some variant-name? (union-members schema #{}))))))

(defn- outputs
  "The output schema forms of a `:malli/schema` function schema form."
  {:malli/schema [:=> [:cat :seon.batch/form] [:vector :seon.batch/form]]}
  [schema]
  (cond (and (vector? schema) (= :=> (first schema))) [(nth schema 2 nil)]
        (and (vector? schema) (= :function (first schema))) (into [] (mapcat outputs) (rest schema))
        :else []))

(defn- producers
  "The calls that may produce `form`'s value: the call itself, or a conditional's branches."
  {:malli/schema [:=> [:cat :seon.batch/form] [:vector :seon.batch/form]]}
  [form]
  (if (and (seq? form) (#{'when 'when-not 'if 'if-not 'when-let 'if-let 'some->} (first form)))
    (into [] (mapcat producers) (if (#{'if 'if-not 'if-let} (first form)) (drop 2 form) [(last form)]))
    [form]))

(defn- guards
  "The guards function `form` of namespace `ns-name` holds: each catch of a general class (only an interop edge's own
  translation catches, and only a function declaring `:seon/entrant` is the entrant boundary), and each error-shape
  check (`:seon.error/at`, `:seon.error/type`) of a value whose producer's contract declares no error variant."
  {:malli/schema [:=> [:cat [:maybe :symbol] :seon.batch/form] [:vector :seon.batch/form]]}
  [ns-name form]
  (let [entrant? (some #(and (map? %) (:seon/entrant %)) form)
        nodes (tree-seq coll? seq form)
        bound (reduce (fn [held [name init]] (update held name (fnil conj []) init)) {}
                      (sequence (comp (filter #(and (seq? %) (#{'let 'when-let 'if-let 'loop} (first %)) (vector? (second %))))
                                      (mapcat #(partition 2 (second %)))
                                      (filter (comp symbol? first)))
                                nodes))
        catches (when-not entrant?
                  (filter #(and (seq? %) (= 'catch (first %)) (general-catches (second %))) nodes))
        call-declares-none? (fn [call]
                              (let [head (when (seq? call) (first call))
                                    v (when (and (symbol? head) ns-name (find-ns ns-name)) (ns-resolve (the-ns ns-name) head))
                                    schema (when (var? v) (:malli/schema (meta v)))]
                                (boolean (and schema (not-any? error-variant? (outputs schema))))))
        ;; a name bound more than once is judged only when every binding's producer declares no error variant
        declares-none? (fn [producer]
                         (if (symbol? producer)
                           (let [inits (bound producer)]
                             (boolean (and (seq inits) (every? #(some call-declares-none? (producers %)) inits))))
                           (call-declares-none? producer)))
        checks (filter #(and (seq? %) (#{:seon.error/at :seon.error/type} (first %))
                             (some declares-none? (producers (second %))))
                       nodes)]
    (vec (concat catches checks))))

(deftest a-changed-function-holds-no-guard
  ;; a ratchet with no hand list: only functions whose definition changed since the law's commit are judged
  (let [program (System/getProperty "user.dir")
        repository (or (System/getenv "SEON_CHECKOUT") program)
        changed (changed-functions program repository)
        found (into {} (keep (fn [[ns-name name form]]
                               (let [held (guards ns-name form)]
                                 (when (seq held) [(symbol (str ns-name) (str name)) held]))))
                    changed)]
    (is (seq changed) "the census judges the functions changed since the law")
    (is (= {} found) "no changed function catches a general exception or re-checks what its producer's contract guarantees")))
