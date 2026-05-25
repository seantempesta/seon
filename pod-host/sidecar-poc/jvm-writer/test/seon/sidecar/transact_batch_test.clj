(ns seon.sidecar.transact-batch-test
  "Integration tests for `transact-batch`: ordered multi-tx commit
   with one pub event per individual tx. Matches d/listen semantics
   exactly.

   Each test spawns its own JVM writer subprocess (memory backend) and
   tears it down. Same fixture pattern as protocol_extensions_test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.sidecar.client :as client])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

(def ^:dynamic *ctx* nil)

(defn- unique-sock [prefix]
  (str "/tmp/seon-poc-test-" prefix "-" (System/nanoTime) ".sock"))

(defn- writer-ready? [path]
  (try (with-open [ch (client/connect path)] (.isConnected ch))
       (catch Throwable _ false)))

(defn- wait-for-socket! [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (writer-ready? path) :ok
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "writer never came up" {:path path}))
        :else (do (Thread/sleep 200) (recur))))))

(defn- spawn-writer! []
  (let [req-sock (unique-sock "req")
        pub-sock (unique-sock "pub")
        cmd ["clojure" "-M:writer"
             "--backend" "memory"
             "--req-sock" req-sock
             "--pub-sock" pub-sock]
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectErrorStream true)
             (.redirectOutput (java.lang.ProcessBuilder$Redirect/to
                                (File. (str "logs/writer-test-" (System/nanoTime) ".log")))))
        _ (.mkdirs (File. "logs"))
        proc (.start pb)]
    (wait-for-socket! req-sock 60000)
    (wait-for-socket! pub-sock 60000)
    {:req-sock req-sock :pub-sock pub-sock :process proc}))

(defn- teardown-writer! [{:keys [^Process process req-sock pub-sock]}]
  (try (.destroy process) (catch Throwable _))
  (try (.waitFor process) (catch Throwable _))
  (try (.delete (File. ^String req-sock)) (catch Throwable _))
  (try (.delete (File. ^String pub-sock)) (catch Throwable _)))

(defn- with-fresh-writer [tfn]
  (let [ctx (spawn-writer!)]
    (try (binding [*ctx* ctx] (tfn))
         (finally (teardown-writer! ctx)))))

(use-fixtures :each with-fresh-writer)

(defn- req! [op extra]
  (with-open [ch (client/connect (:req-sock *ctx*))]
    (client/call! ch (merge {"op" op} extra))))

(defn- install-schema! []
  (req! "transact"
        {"tx-data"
         "[{:db/ident :item/id
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one
            :db/unique :db.unique/identity}
           {:db/ident :item/n
            :db/valueType :db.type/long
            :db/cardinality :db.cardinality/one}]"}))

(deftest test-batch-all-succeed
  (testing "transact-batch applies all entries in order and reports per-tx data"
    (install-schema!)
    (let [r (req! "transact-batch"
                  {"tx-data-list"
                   ["[{:item/id \"a\" :item/n 1}]"
                    "[{:item/id \"b\" :item/n 2}]"
                    "[{:item/id \"c\" :item/n 3}]"]})]
      (is (= true (get r "ok")))
      (is (= 3 (get r "applied")))
      (is (= 3 (get r "total")))
      (is (nil? (get r "failed-at")))
      (let [reports (get r "reports")]
        (is (= 3 (count reports)))
        (is (= [0 1 2] (mapv #(get % "index") reports)))
        ;; basis-t advances monotonically across the batch
        (let [bts (mapv #(get % "basis-t") reports)]
          (is (apply < bts) (str "basis-t should be strictly increasing: " bts)))
        ;; each report carries the wire-shape tx-data
        (doseq [rep reports]
          (is (vector? (get rep "tx-data")))
          (is (pos? (get rep "datoms-added"))))))))

(deftest test-batch-preserves-order-in-db
  (testing "after the batch, all entries are queryable with expected values"
    (install-schema!)
    (req! "transact-batch"
          {"tx-data-list"
           ["[{:item/id \"x\" :item/n 10}]"
            "[{:item/id \"y\" :item/n 20}]"
            "[{:item/id \"z\" :item/n 30}]"]})
    (let [r (req! "q" {"query" "[:find ?id ?n :where [?e :item/id ?id] [?e :item/n ?n]]"
                       "args"  []})
          result (get r "result")
          by-id  (into {} (mapv (fn [[id n]] [id n]) result))]
      (is (= true (get r "ok")))
      (is (= {"x" 10 "y" 20 "z" 30} by-id)))))

(deftest test-batch-tx-meta-per-entry
  (testing "each report carries datahike-issued tx-meta (db/txInstant + db/commitId)"
    ;; Datahike's :schema-flexibility :write requires user-supplied tx-meta
    ;; attrs to be installed in schema too — out of scope for this batch
    ;; test. Just verify the batch path preserves the datahike-issued
    ;; tx-meta shape, the same way single-tx does in
    ;; protocol_integration_test.clj/test-tx-meta-shape.
    (install-schema!)
    (let [r (req! "transact-batch"
                  {"tx-data-list" ["[{:item/id \"a\" :item/n 1}]"
                                   "[{:item/id \"b\" :item/n 2}]"]})]
      (is (= true (get r "ok")))
      (is (nil? (get r "failed-at")))
      (let [reports (get r "reports")
            metas   (mapv #(get % "tx-meta") reports)]
        (is (= 2 (count metas)))
        (doseq [m metas]
          (is (contains? m "db/txInstant"))
          (is (contains? m "db/commitId")))
        ;; All commitIds must be distinct (each tx is a separate commit)
        (is (= 2 (count (into #{} (map #(get % "db/commitId")) metas))))))))

(deftest test-batch-partial-failure-stops-after-bad-entry
  (testing "entry 1 references an unknown attr — entries 0 applies, 1 fails, 2 NOT applied"
    (install-schema!)
    (let [r (req! "transact-batch"
                  {"tx-data-list"
                   ["[{:item/id \"good-a\" :item/n 1}]"
                    "[{:item/id \"bad-b\" :unknown/attr 2}]"  ; bad — unknown attr
                    "[{:item/id \"good-c\" :item/n 3}]"]})]
      (is (= true (get r "ok")) "op succeeds even though one entry failed")
      (is (= 1 (get r "applied")))
      (is (= 3 (get r "total")))
      (is (= 1 (get r "failed-at")))
      (is (some? (get r "error")))
      (is (= 1 (count (get r "reports"))))
      ;; entry 2 must NOT be in the DB
      (let [q (req! "q" {"query" "[:find ?id :where [?e :item/id ?id]]" "args" []})
            ids (into #{} (map first) (get q "result"))]
        (is (contains? ids "good-a"))
        (is (not (contains? ids "good-c")) "entry after the failure must not be applied")
        (is (not (contains? ids "bad-b")))))))

(deftest test-batch-empty-is-a-noop
  (testing "empty batch returns applied=0 total=0 with no error"
    (install-schema!)
    (let [r (req! "transact-batch" {"tx-data-list" []})]
      (is (= true (get r "ok")))
      (is (= 0 (get r "applied")))
      (is (= 0 (get r "total")))
      (is (nil? (get r "failed-at")))
      (is (= [] (get r "reports"))))))

(deftest test-batch-request-ids-roundtrip
  (testing "request-ids list echoes per-entry on each report"
    (install-schema!)
    (let [r (req! "transact-batch"
                  {"tx-data-list" ["[{:item/id \"r1\" :item/n 1}]"
                                   "[{:item/id \"r2\" :item/n 2}]"]
                   "request-ids"  ["req-aaa" "req-bbb"]})
          reports (get r "reports")]
      (is (= "req-aaa" (get (first reports) "request-id")))
      (is (= "req-bbb" (get (second reports) "request-id"))))))
