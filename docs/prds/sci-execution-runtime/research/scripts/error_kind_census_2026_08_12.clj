(ns error-kind-census-2026-08-12
  "Reproduce the source and declared-class census for the 2026-08-12 audit."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]))

(defn- source-file?
  [file]
  (and (.isFile file)
       (or (str/ends-with? (.getName file) ".clj")
           (str/ends-with? (.getName file) ".cljc"))))

(defn- source-files
  []
  (->> (file-seq (io/file "src"))
       (filter source-file?)
       (sort-by str)))

(defn- locations
  [file]
  (take-while (complement z/end?)
              (iterate z/next (z/of-file file))))

(defn- sexpr
  [location]
  (try
    (z/sexpr location)
    (catch Throwable _ ::unreadable)))

(defn- kind-location?
  [location]
  (= :seon.error/kind (sexpr location)))

(defn- namespace-form
  [file]
  (some (fn [location]
          (let [form (sexpr location)]
            (when (and (seq? form) (= 'ns (first form))) form)))
        (locations file)))

(defn- aliases
  [form]
  (into {}
        (keep (fn [value]
                (when (and (vector? value)
                           (symbol? (first value)))
                  (let [entries (partition 2 1 value)
                        alias-symbol (some (fn [[left right]]
                                             (when (= :as left) right))
                                           entries)]
                    (when (symbol? alias-symbol)
                      [alias-symbol (first value)])))))
        (tree-seq coll? seq form)))

(defn- placeholder-alias
  [namespace-name]
  (when (and (string? namespace-name)
             (str/starts-with? namespace-name "??_")
             (str/ends-with? namespace-name "_??"))
    (subs namespace-name 3 (- (count namespace-name) 3))))

(defn- resolve-keyword
  [namespace-name alias-map value]
  (if-not (keyword? value)
    value
    (let [value-namespace (namespace value)]
      (cond
        (= "?_current-ns_?" value-namespace)
        (keyword (str namespace-name) (name value))

        (placeholder-alias value-namespace)
        (if-let [target (get alias-map
                            (symbol (placeholder-alias value-namespace)))]
          (keyword (str target) (name value))
          value)

        :else value))))

(defn- list-head
  [form]
  (when (seq? form) (first form)))

(defn- value-after
  [form target]
  (some (fn [[left right]]
          (when (= target left) right))
        (partition 2 1 form)))

(defn- classify
  [location]
  (let [parent (z/up location)
        tag (z/tag parent)
        form (sexpr parent)]
    (cond
      (= :map tag) :map-write
      (and (= :list tag) (= 'assoc (list-head form))) :assoc-write
      (and (= :list tag) (= :seon.error/kind (list-head form))) :direct-read
      (and (= :list tag) (= 'get-else (list-head form))) :query-read
      (= :vector tag) :vector-use
      :else tag)))

(defn- written-kind-value
  [location]
  (let [parent (z/up location)
        form (sexpr parent)]
    (case (classify location)
      :map-write (get form :seon.error/kind)
      :assoc-write (value-after form :seon.error/kind)
      nil)))

(defn- source-rows
  []
  (mapcat
   (fn [file]
     (let [ns-form (namespace-form file)
           namespace-name (second ns-form)
           alias-map (aliases ns-form)]
       (for [location (locations file)
             :when (kind-location? location)]
         (let [classification (classify location)
               written (written-kind-value location)]
           {:file (str file)
            :row (:row (meta (z/node location)))
            :classification classification
            :written-value
            (when (#{:map-write :assoc-write} classification)
              (resolve-keyword namespace-name alias-map written))}))))
   (source-files)))

(defn- class-marker
  [row]
  (let [markers (disj (:seon.schema/required-attrs row)
                      :seon.error/message)]
    (when (= 1 (count markers))
      (first markers))))

(defn- declared-class-markers
  []
  (let [projection (schema/activate! (schema.edn/packaged-forms))
        forms (:seon.schema.projection/forms projection)]
    (into #{}
          (keep (fn [[schema-key row]]
                  (let [properties
                        (schema.form/namespaced-properties
                         (get forms schema-key))]
                    (when (true? (:seon.error/class properties))
                      (class-marker row)))))
          (:seon.schema.projection/shape-rows projection))))

(defn -main
  "Print the current error-kind source and schema census."
  [& _]
  (let [rows (vec (source-rows))
        writes (filterv #(#{:map-write :assoc-write}
                           (:classification %))
                        rows)
        literal-kinds (into #{} (keep (fn [{kind-value :written-value}]
                                        (when (keyword? kind-value)
                                          kind-value)))
                            writes)
        markers (declared-class-markers)]
    (prn
     {:source-files (count (set (map :file rows)))
      :executable-and-contract-occurrences (count rows)
      :classifications (into (sorted-map)
                             (frequencies (map :classification rows)))
      :write-sites (count writes)
      :literal-write-sites (count (filter (comp keyword? :written-value) writes))
      :dynamic-write-sites (count (remove (comp keyword? :written-value) writes))
      :distinct-literal-kinds (count literal-kinds)
      :declared-error-class-markers (count markers)
      :literal-kinds-with-same-named-marker (count (filter markers literal-kinds))
      :literal-kinds-without-same-named-marker
      (vec (sort-by str (remove markers literal-kinds)))})))

(apply -main *command-line-args*)
