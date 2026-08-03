(ns seon.schema-split
  "Mechanically split and verify Seon's schema declaration resources."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn- filesystem-safe-namespace?
  [schema-namespace]
  (and (seq schema-namespace)
       (not (#{"." ".."} schema-namespace))
       (every? #(or (Character/isLetterOrDigit ^char %)
                    (#{\. \- \_} %))
               schema-namespace)))

(defn- require-safe-namespace!
  [schema-namespace registry-key]
  (when-not (filesystem-safe-namespace? schema-namespace)
    (throw
     (ex-info
      (str "Schema key namespace is not safe as a verbatim filename: "
           registry-key)
      {:key registry-key
       :namespace schema-namespace}))))

(defn- key-namespace!
  [registry-key]
  (let [schema-namespace (when (qualified-keyword? registry-key)
                           (namespace registry-key))]
    (require-safe-namespace! schema-namespace registry-key)
    schema-namespace))

(defn- read-map
  [file]
  (let [value (edn/read-string (slurp file))]
    (when-not (map? value)
      (throw (ex-info "Schema resource must contain one map."
                      {:file (str file)})))
    value))

(defn- split-files
  [directory]
  (->> (.listFiles (io/file directory))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (sort-by #(.getName %))))

(defn- merge-files
  [files]
  (reduce
   (fn [{:keys [forms files-by-key]} file]
     (let [filename (.getName file)
           file-namespace (subs filename 0 (- (count filename) 4))]
       (require-safe-namespace! file-namespace filename)
       (reduce-kv
        (fn [state registry-key form]
          (let [schema-namespace (key-namespace! registry-key)]
            (when-not (= file-namespace schema-namespace)
              (throw
               (ex-info (str "Schema declaration " registry-key " is in "
                             filename " but belongs in " schema-namespace
                             ".edn.")
                        {:key registry-key
                         :file (str file)
                         :expected-file (str schema-namespace ".edn")})))
            (when-let [first-file (get files-by-key registry-key)]
              (throw
               (ex-info (str "Duplicate schema declaration " registry-key " in "
                             first-file " and " file ".")
                        {:key registry-key
                         :files [(str first-file) (str file)]})))
            {:forms (assoc (:forms state) registry-key form)
             :files-by-key (assoc (:files-by-key state) registry-key (str file))}))
        {:forms forms :files-by-key files-by-key}
        (read-map file))))
   {:forms {} :files-by-key {}}
   files))

(defn- write-split!
  [text directory]
  (let [monolith (edn/read-string text)
        directory-file (io/file directory)]
    (when-not (map? monolith)
      (throw (ex-info "Schema monolith must contain one map." {})))
    (.mkdirs directory-file)
    (doseq [file (split-files directory-file)]
      (when-not (.delete file)
        (throw (ex-info "Could not remove superseded schema resource."
                        {:file (str file)}))))
    (let [by-namespace
          (group-by (comp key-namespace! key) monolith)]
      (doseq [[schema-namespace entries] (sort-by key by-namespace)]
        (let [file (io/file directory-file (str schema-namespace ".edn"))
              forms (into (sorted-map) entries)]
          (with-open [writer (io/writer file)]
            (binding [*out* writer]
              (pprint/pprint forms)))
          (when-not (= forms (read-map file))
            (throw (ex-info "Generated schema resource did not re-read exactly."
                            {:file (str file)})))))
      (println "Wrote and re-read" (count by-namespace)
               "verbatim namespace schema resources."))))

(defn- check-forms!
  [monolith directory]
  (let [files (split-files directory)
        merged (:forms (merge-files files))]
    (when-not (= monolith merged)
      (throw
       (ex-info "Split schema declarations differ from the monolith."
                {:monolith-keys (count monolith)
                 :split-keys (count merged)
                 :missing (sort (remove merged (keys monolith)))
                 :extra (sort (remove monolith (keys merged)))
                 :changed (sort (filter #(and (contains? merged %)
                                              (not= (get monolith %)
                                                    (get merged %)))
                                        (keys monolith)))})))
    (println "Schema declaration equality:"
             (count monolith) "keys across" (count files) "files.")))

(defn- check!
  [monolith-file directory]
  (check-forms! (read-map monolith-file) directory))

(defn- check-stdin!
  [directory]
  (let [monolith (edn/read-string (slurp *in*))]
    (when-not (map? monolith)
      (throw (ex-info "Schema monolith must contain one map." {})))
    (check-forms! monolith directory)))

(defn -main
  "Write or verify the mechanically split schema resources."
  [& [operation first-path second-path]]
  (case operation
    "write-stdin" (write-split! (slurp *in*) first-path)
    "check-stdin" (check-stdin! first-path)
    "check" (check! first-path second-path)
    (throw (ex-info "Use write-stdin DIRECTORY, check-stdin DIRECTORY, or check MONOLITH DIRECTORY."
                    {:operation operation}))))
