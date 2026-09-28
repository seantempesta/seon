;; store-structure-opus: after an Error inside one konserve write kills the
;; writer, release-store! reports the failure and the same process reopens the
;; store at its prior head (seon.cluster.store-test/an-error-inside-one-store-write...).
;; Run: clojure -M tmp/store-structure/release_after_writer_error.clj
(require '[datahike.api :as d]
         '[konserve.core :as k]
         '[seon.cluster.store :as store])
(when (= "parent" (first *command-line-args*))
  (load-file "/Users/sean/src/seon/tmp/store-structure/store-head.clj.txt"))

(let [dir (str "/Users/sean/src/seon/tmp/store-structure/release-probe/" (random-uuid) "/store")
      opened (store/open-store! {:seon.store/dir dir})
      conn (:seon.store/connection-object opened)
      _ (d/transact conn [{:db/ident :m/k :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
      _ (d/transact conn [{:m/k "before"}])
      before (d/commit-id @conn)
      armed (atom true)
      _ (k/add-write-hook! (:store @conn) ::inject
                           (fn [{written :key}]
                             (when (and (uuid? written) (compare-and-set! armed true false))
                               (throw (AssertionError. "injected Error in one store write")))))
      outcome (try (d/transact conn [{:m/k "lost"}]) (catch Throwable t (class t)))
      release (try (store/release-store! opened) :released
                   (catch Throwable t (loop [e t] (if (ex-cause e) (recur (ex-cause e)) (ex-message e)))))
      reopen (try (let [again (store/open-store! {:seon.store/dir dir})
                        db @(:seon.store/connection-object again)
                        r {:head-is-prior (= before (d/commit-id db))
                           :markers (d/q '[:find [?v ...] :where [_ :m/k ?v]] db)}]
                    (store/release-store! again)
                    r)
                  (catch Throwable t {:reopen-refused (ex-message t) :data (select-keys (ex-data t) [::store/rule])}))]
  (prn {:fired (not @armed) :outcome outcome :release release :reopen reopen})
  (shutdown-agents)
  (System/exit 0))
