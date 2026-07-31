(ns konserve-multi-assoc-datahike-kill
  (:require [clojure.java.io :as io]
            [datahike.api :as d])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(def ^:private probe-dir
  (.getParentFile (io/file *file*)))

(defn- config
  [path]
  {:store {:backend :file
           :path path
           :id (UUID/nameUUIDFromBytes (.getBytes ^String path StandardCharsets/UTF_8))}
   :writer {:backend :self}
   :keep-history? true
   :schema-flexibility :write})

(defn- child-command
  [path stage]
  [(str (System/getProperty "java.home") "/bin/java")
   "-cp" (System/getProperty "java.class.path")
   "clojure.main"
   "-i" (.getCanonicalPath
          (io/file probe-dir "konserve_multi_assoc_datahike_child.clj"))
   "-m" "konserve-multi-assoc-datahike-child"
   path (name stage)])

(defn- kill-at-stage!
  [path stage]
  (let [process (-> (ProcessBuilder. ^java.util.List (child-command path stage))
                    (.redirectErrorStream true)
                    (.start))
        reader (io/reader (.getInputStream process))
        child-result (deref
                      (future
                        (loop [lines []]
                          (if-let [line (.readLine reader)]
                            (if (= (str "READY " (name stage)) line)
                              {:ready? true :lines (conj lines line)}
                              (recur (conj lines line)))
                            {:ready? false :lines lines})))
                      30000
                      {:ready? false :timeout? true})]
    (when-not (:ready? child-result)
      (.destroyForcibly process)
      (throw (ex-info "Datahike crash child did not reach requested batch stage."
                      {:stage stage :child-result child-result})))
    (.destroyForcibly process)
    (when-not (.waitFor process 30 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Datahike crash child did not terminate."
                      {:stage stage})))
    (when (zero? (.exitValue process))
      (throw (ex-info "Datahike crash child exited normally instead of being killed."
                      {:stage stage})))))

(defn- seed!
  [cfg]
  (d/create-database cfg)
  (let [conn (d/connect cfg)]
    (try
      (d/transact conn [{:db/ident :probe/id
                         :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one
                         :db/unique :db.unique/identity}
                        {:db/ident :probe/n
                         :db/valueType :db.type/long
                         :db/cardinality :db.cardinality/one}])
      (d/transact conn [{:probe/id "subject" :probe/n 0}])
      (d/commit-id @conn)
      (finally
        (d/release conn)))))

(defn- reopened-state
  [cfg]
  (let [conn (d/connect cfg)]
    (try
      (let [db @conn]
        {:commit-id (d/commit-id db)
         :subject-n (d/q '[:find ?n .
                           :where
                           [?e :probe/id "subject"]
                           [?e :probe/n ?n]]
                         db)
         :marker? (boolean
                   (d/q '[:find ?e .
                          :where [?e :probe/id "new-marker"]]
                        db))})
      (finally
        (d/release conn)))))

(defn- run-probe!
  []
  (doseq [stage [:staged :after-first-move :before-last-move :after-last-move]]
    (let [path (str (.getCanonicalPath probe-dir)
                    "/datahike-kill-" (name stage) "-" (UUID/randomUUID))
          cfg (config path)]
      (try
        (let [old-cid (seed! cfg)]
          (kill-at-stage! path stage)
          (let [{:keys [commit-id subject-n marker?] :as state}
                (reopened-state cfg)
                old? (and (= old-cid commit-id)
                          (= 0 subject-n)
                          (false? marker?))
                new? (and (not= old-cid commit-id)
                          (= 1 subject-n)
                          marker?)]
            (when-not (or old? new?)
              (throw (ex-info "Torn Datahike branch head/facts after forced kill."
                              {:stage stage :old-commit-id old-cid :state state})))
            (println {:stage stage
                      :outcome (if old? :fully-old :fully-new)
                      :old-commit-id old-cid
                      :reopened-commit-id commit-id})))
        (finally
          (when (d/database-exists? cfg)
            (d/delete-database cfg))))))
  (println :datahike-kill-probe :pass))

(run-probe!)
(shutdown-agents)
(System/exit 0)
