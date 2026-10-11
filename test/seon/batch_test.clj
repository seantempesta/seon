(ns seon.batch-test
  "The world an evaluation runs in (`seon.batch/*world*`) has one constructor,
  `seon.turn.world/world`: a census over the loaded program and its source."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [seon.census :as census]))

(def ^:private world-var
  "The dynamic Var the fold binds, as source names it."
  'seon.batch/*world*)

(def ^:private constructor
  'seon.turn.world/world)

(defn- aliases
  "The file's `:as` aliases from its ns form, alias -> namespace symbol."
  [forms]
  (into {} (for [form forms
                 :when (and (seq? form) (= 'ns (first form)))
                 clause (rest form)
                 :when (and (seq? clause) (= :require (first clause)))
                 spec (rest clause)
                 :when (vector? spec)
                 :let [{alias :as} (apply hash-map (rest spec))]
                 :when alias]
             [alias (first spec)])))

(defn- resolved
  "`s` with its alias resolved to its namespace; `*world*` inside seon.batch is the Var."
  [s ns-name alias->ns]
  (cond (not (symbol? s)) s
        (and (= 'seon.batch ns-name) (= '*world* s)) world-var
        (namespace s) (symbol (str (get alias->ns (symbol (namespace s)) (namespace s))) (name s))
        :else s))

(defn- world-key?
  "The form naming the world Var as a binding target: the symbol or its var-quote."
  [form ns-name alias->ns]
  (= world-var (resolved (if (and (seq? form) (= 'var (first form))) (second form) form) ns-name alias->ns)))

(defn- bound-values
  "Every value `form` binds to the world Var: a `binding` vector's pairs, a map
  literal's entries and an `assoc`'s key/value pairs whose key is the Var."
  [form ns-name alias->ns]
  (let [hits (volatile! [])
        pairs (fn [kvs] (doseq [[k v] (partition 2 kvs) :when (world-key? k ns-name alias->ns)] (vswap! hits conj v)))]
    (walk/prewalk (fn [x]
                    (cond (and (seq? x) (= 'binding (first x)) (vector? (second x))) (pairs (second x))
                          (and (seq? x) (= 'assoc (first x))) (pairs (drop 2 x))
                          (map? x) (pairs (mapcat identity x)))
                    x)
                  form)
    @hits))

(defn- built-by-constructor?
  "Whether a value bound to the world is a world already built (a symbol, the
  bound world itself), the constructor's value, or a stage's addition onto a
  built world (`assoc`, a threading form or a branch whose arms are such)."
  [v ns-name alias->ns]
  (let [ok? #(built-by-constructor? % ns-name alias->ns)
        head (when (seq? v) (resolved (first v) ns-name alias->ns))]
    (cond (or (nil? v) (symbol? v)) true
          (not (seq? v)) false
          (= constructor head) true
          (contains? '#{assoc cond-> some-> merge} head) (ok? (second v))
          (contains? '#{if if-let if-some} head) (every? ok? (drop 2 v))
          (contains? '#{when when-let when-some} head) (ok? (last v))
          :else false)))

(defn- files-naming
  "The source files under `roots` whose text holds every one of `needles`: only they can hold the site."
  [roots & needles]
  (filterv (fn [path] (let [text (slurp path)] (every? #(str/includes? text %) needles)))
           (census/files roots [".clj" ".cljc"])))

(defn- foreign-bindings
  "The bindings of the world Var in `paths` whose value is not built by the one constructor."
  [paths]
  (vec (for [path paths
             :let [forms (census/top-level-forms path)]
             :when (sequential? forms)
             :let [alias->ns (aliases forms)
                   ns-name (some #(when (and (seq? %) (= 'ns (first %))) (second %)) forms)]
             form forms
             v (bound-values form ns-name alias->ns)
             :when (not (built-by-constructor? v ns-name alias->ns))]
         {:file path :value v})))

(defn- maps-outside-constructor
  "The map literals in `form` that are not the constructor's request (nor inside it)."
  [form ns-name alias->ns]
  (cond (and (seq? form) (= constructor (resolved (first form) ns-name alias->ns))) []
        (map? form) (cons form (mapcat #(maps-outside-constructor % ns-name alias->ns) (mapcat identity form)))
        (coll? form) (mapcat #(maps-outside-constructor % ns-name alias->ns) form)
        :else []))

(defn- world-literals
  "Map literals in `paths`, outside the constructor and its requests, shaped as
  a world: a connection beside a call sink or the writer."
  [paths]
  (vec (for [path paths
             :let [forms (census/top-level-forms path)]
             :when (sequential? forms)
             :let [alias->ns (aliases forms)
                   ns-name (some #(when (and (seq? %) (= 'ns (first %))) (second %)) forms)]
             form forms
             :when (not (and (seq? form) (= 'world (second form)) (str/ends-with? path "seon/turn/world.clj")))
             m (maps-outside-constructor form ns-name alias->ns)
             :when (and (contains? m :seon.db/connection)
                        (or (contains? m :seon.batch/sink) (contains? m :seon.db/writer))
                        ;; a table of names (`seon.batch/world-members`) builds nothing
                        (not-every? keyword? (vals m)))]
         {:file path :keys (vec (sort (filter keyword? (keys m))))})))

(defn- world-schema?
  "Whether `output` declares a world (or maybe one)."
  [output]
  (let [form (try (m/form (m/schema output)) (catch Exception _ output))
        form (if (and (vector? form) (= :maybe (first form))) (second form) form)]
    (contains? #{:seon.batch/world :seon.batch/world-base} form)))

(defn- world-producers
  "Loaded Vars whose declared output is a world while no input is one (or a
  request holding one): each builds a world of its own."
  []
  (vec (for [n (all-ns)
             v (vals (ns-interns n))
             :let [declared (:malli/schema (meta v))
                   [_ [_ & inputs] output] (when (and (vector? declared) (= :=> (first declared))) declared)]
             :when (and output
                        (world-schema? output)
                        (not-any? #(contains? #{:seon.batch/world :seon.batch/request :seon.batch/world-base} %)
                                  (tree-seq coll? seq (vec inputs))))
             :let [sym (symbol v)]
             :when (not= constructor sym)]
         sym)))

(deftest ^{:seon/tasks #{'seon.batch/every-world-is-built-by-one-constructor}}
  every-world-is-built-by-one-constructor
  (is (= [] (world-producers))
      "Only seon.turn.world/world produces a world from no world.")
  (is (= [] (foreign-bindings (files-naming ["src"] "*world*")))
      "Every binding of seon.batch/*world* is the constructor's value or a stage's addition onto a built world.")
  (is (= [] (world-literals (files-naming ["src"] ":seon.db/connection")))
      "No map literal outside the constructor builds a world."))
