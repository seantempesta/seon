(ns seon.fn
  "Build-time indexing of the Clojure program graph through the one reader."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema.edn :as schema.edn]
            [seon.sci.reader :as reader]))

(schema.edn/load! {})

(def source-roots
  "The complete first-party source corpus admitted to the ancestor."
  ["src" "test"])

(defn- source-file?
  [file]
  (and (.isFile ^java.io.File file)
       (or (str/ends-with? (.getName ^java.io.File file) ".clj")
           (str/ends-with? (.getName ^java.io.File file) ".cljc"))))

(defn- durable-row
  [event]
  (cond
    (:seon.ns/name event)
    (select-keys event [:seon.ns/name :seon.ns/source :seon.ns/doc
                        :seon.ns/require-edges])

    (and (:seon.fn/sym event) (:seon.fn/spec event))
    (select-keys event [:seon.fn/sym :seon.fn/ns :seon.fn/source
                        :seon.fn/arglists :seon.fn/doc :seon.fn/private?
                        :seon.fn/spec :seon.fn/workload])

    (:seon.schema/key event)
    (select-keys event [:seon.schema/key :seon.schema/ns :seon.schema/form])

    (:seon.test/sym event)
    (select-keys event [:seon.test/sym :seon.test/ns :seon.test/source])

    :else nil))

(defn rows
  "Canonical program rows read from the declared source roots."
  {:malli/schema [:=> [:cat :seon.fn/index-request] [:vector :map]]}
  [{roots :seon.fn/roots}]
  (into
   []
   (comp
    (mapcat (fn [root]
              (->> (file-seq (io/file root))
                   (filter source-file?)
                   (sort-by (fn [file]
                              (.getCanonicalPath ^java.io.File file))))))
    (mapcat
     (fn [file]
       (let [events
             (reader/read
              {:seon.sci.reader/text (slurp file)
               :seon.sci.reader/features #{:clj}
               ;; Standard data literals are source data, not an escape
               ;; hatch. The index needs their forms, never host objects.
               :seon.sci.reader/tags {'inst identity
                                      'uuid identity}})]
         (when (map? events)
           (throw (ex-info (:seon.error/message events)
                           (assoc (:seon.error/data events)
                                  :seon.fn/file
                                  (.getCanonicalPath ^java.io.File file)))))
         events)))
    (keep durable-row))
   roots))

(defn index!
  "Index the declared roots into an already schema-populated ancestor."
  {:malli/schema [:=> [:cat :seon.fn/index-request] :nil]}
  [{connection :seon.store/branch-connection
    process :seon.db/process
    :as request}]
  (let [program-rows (rows request)
        namespace-rows (filterv :seon.ns/name program-rows)
        declaration-rows (remove :seon.ns/name program-rows)
        transaction (fn [rows]
                      (cond-> {:tx-data (vec rows)}
                        process (assoc :tx-meta {:seon.db/process process})))]
    (when (seq namespace-rows)
      (d/transact connection (transaction namespace-rows)))
    (when (seq declaration-rows)
      (d/transact connection (transaction declaration-rows)))
    nil))
