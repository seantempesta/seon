(ns seon.program-indexer-test
  "Properties over the source-current program graph emitted by the JVM indexer."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [seon.db.datahike.schema :as datahike.schema]
            [seon.program.edge :as edge]))

(defn- emitted-artifact [manifest-key]
  (let [manifest-path (System/getenv "SEON_WRITER_ARTIFACT_MANIFEST")
        manifest (edn/read-string (slurp manifest-path))
        checkout (System/getProperty "user.dir")
        row-path (io/file checkout
                          (get manifest manifest-key))]
    (edn/read-string (slurp row-path))))

(defn- emitted-function-rows []
  (:seon.dev.artifact/program-rows
   (emitted-artifact :seon.dev.artifact/program-row-path)))

(defn- emitted-pages []
  (get-in
   (emitted-artifact :seon.dev.artifact/page-plan-path)
   [:seon.dev.artifact/page-plan :seon.db/initialization-pages]))

(defn- transaction-attributes [row]
  (cond
    (map? row)
    (disj (set (keys row)) :db/id)

    (and (vector? row) (keyword? (second row)))
    #{(second row)}

    :else
    #{}))

(defn- pure-call-graph? [root bundles]
  (loop [pending (list root)
         seen #{}]
    (if-let [function-symbol (first pending)]
      (if (contains? seen function-symbol)
        (recur (next pending) seen)
        (if-let [bundle (get bundles function-symbol)]
          (let [terminal-effects
                (into {} (map (juxt ::edge/terminal-symbol ::edge/effect))
                      (::edge/terminals bundle))
                calls (::edge/calls bundle)
                indexed-calls (filter bundles calls)
                terminal-calls (remove bundles calls)]
            (if (or (seq (::edge/uncertainties bundle))
                    (some #(not= :pure (get terminal-effects %))
                          terminal-calls))
              false
              (recur (concat indexed-calls (next pending))
                     (conj seen function-symbol))))
          false))
      true)))

(deftest indexer-emitted-capability-edge-is-never-pure
  (let [bundles (into {}
                      (map (juxt ::edge/function-symbol identity))
                      (edge/reconstruct-bundles
                       (emitted-function-rows)))
        root "seon.db/bind-leaf"
        bundle (get bundles root)
        transact-terminal
        (some #(when (= "seon.db/transact!"
                        (::edge/terminal-symbol %))
                 %)
              (::edge/terminals bundle))]
    (is (= :idempotent (::edge/effect transact-terminal))
        "the emitted graph retains the guarded transaction capability edge")
    (is (false? (pure-call-graph? root bundles))
        "a graph that reaches that capability is never classified pure")))

(deftest every-emitted-page-attribute-has-a-canonical-schema-form
  (let [pages (emitted-pages)
        canonical-schema-keys
        (into #{}
              (keep :seon.schema/key)
              (mapcat :seon.db/program pages))
        canonical-schema-forms
        (into {}
              (keep
               (fn [{:seon.schema/keys [key form]}]
                 (when (and key form)
                   [key (edn/read-string form)])))
              (mapcat :seon.db/program pages))
        declared-attributes
        (set (mapcat :seon.db/attributes pages))
        compiled-schema-attributes
        (into #{}
              (comp
               (mapcat
                (fn [attribute]
                  (keys
                   (datahike.schema/malli-form->datahike-attribute
                    canonical-schema-forms
                    attribute
                    (get canonical-schema-forms attribute)))))
               (filter qualified-keyword?)
               (remove #(= "db" (namespace %))))
              declared-attributes)
        referenced-attributes
        (into #{}
              (concat
               declared-attributes
               compiled-schema-attributes
               (mapcat transaction-attributes
                       (mapcat :seon.db/program pages))
               (mapcat transaction-attributes
                       (mapcat :seon.db/initial-data pages))))
        missing
        (set/difference referenced-attributes canonical-schema-keys)]
    (is (empty? missing)
        (str "Every attribute referenced by emitted page transaction data "
             "must have a canonical schema form in the same pages; missing "
             (pr-str (vec (sort missing)))))))
