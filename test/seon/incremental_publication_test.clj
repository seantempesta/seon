(ns seon.incremental-publication-test
  "Publication input ownership through the real source refresh boundary."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.test-support :as support]))

(defn- copy-tree! [from to]
  (io/make-parents to)
  (if (.isDirectory (io/file from))
    (#'support/clone-directory! (io/file from) (io/file to))
    (io/copy (io/file from) (io/file to))))

(defn- published-value [root f]
  (let [directory (:seon.boot/store-dir
                   (cluster/resolve-bootstrap {:seon.boot/root root}))]
    (with-open [held (support/closeable
                     (store/open-store! {:seon.store/dir directory})
                     store/release-store!)]
      (let [held-store @held
            current (source/current held-store)
            database (source/database held-store (:seon.source/commit-id current))]
        (f database)))))

(deftest ^{:seon.test/fixture-observation
           "Observes real source publication decisions and persisted schema/config facts after filesystem edits."
           :seon.test/long "Publishes a canonical fixture checkout and three incremental edits."}
  non-program-inputs-have-their-own-publication-owner
  (let [root (str "tmp/incremental-publication-" (random-uuid))
        checkout (io/file root "checkout")
        directory (.getCanonicalPath checkout)
        roots (assoc (#'cluster/publication-roots) :seon.fn/root directory)
        resource io/resource
        phases (atom [])
        progress-var (ns-resolve 'seon.cluster '*source-progress!*)]
    (try
      (doseq [path (distinct (concat (:seon.source/roots roots)
                                    ["resources/seon/schemas"]))]
        (copy-tree! (io/file (fs/source-directory) path) (io/file checkout path)))
      (with-redefs-fn
        {#'cluster/publication-roots (constantly roots)
         #'io/resource
         (fn [name & args]
           (cond
             (or (= name "seon/schemas") (str/starts-with? name "seon/schemas/"))
             (.toURL (.toURI (io/file checkout "resources" name)))
             (= name config/default-manifest-path)
             (.toURL (.toURI (io/file checkout name)))
             :else (apply resource name args)))}
        (fn []
          (cluster/refresh-source! root)
          (doseq [[path content check]
                  [["resources/seon/schemas/seon.publication.fixture.edn"
                    "{:seon.publication.fixture/value [:string {:seon.db/attribute true}]}\n"
                    (fn [database]
                      (is (some? (db/pull database '[*]
                                         [:seon.schema/key :seon.publication.fixture/value]))))]
                   [config/default-manifest-path
                    (pr-str
                     (update (edn/read-string (slurp (io/file checkout config/default-manifest-path)))
                             :seon.config/initialization
                             (fn [rows]
                               (mapv #(if (= "deepseek" (:seon.ai.model/provider-id %))
                                        (assoc % :seon.config.ai/endpoint "https://fixture.invalid/chat")
                                        %) rows))))
                    (fn [database]
                      (is (= "https://fixture.invalid/chat"
                             (:seon.config.ai/endpoint
                              (db/pull database [:seon.config.ai/endpoint]
                                       [:seon.ai.model/provider-id "deepseek"])))))]
                   ["publication-note.md" "# No program facts\n"
                    (fn [database]
                      (is (nil? (db/pull database [:db/id]
                                        [:seon.fn.file/relative-path "publication-note.md"]))))]]]
            (testing path
              (let [file (io/file checkout path)]
                (io/make-parents file)
                (spit file content)
                (reset! phases [])
                (with-bindings {progress-var #(swap! phases conj %)}
                  (cluster/refresh-source! root [(.getCanonicalPath file)]))
                (is (some #(str/starts-with? % "incremental") @phases) (pr-str @phases))
                (is (not-any? #(str/starts-with? % "complete publication") @phases))
                (published-value root check))))))
      (finally (support/delete-recursively! root)))))
