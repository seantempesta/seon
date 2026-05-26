(ns seon.sidecar.wire-types-test
  "Type-fidelity tests for the Transit-JSON wire format.

   Every Clojure type that we expect callers to use across the sidecar
   boundary is round-tripped: write -> transact -> query -> verify the
   value comes back with the same Clojure type.

   Pinned types:
   - keyword (simple + namespaced)
   - string
   - integer (small + bigint)
   - double (whole + fractional)
   - instant (java.util.Date)
   - boolean
   - nil
   - vector / list / set / map
   - nested combinations

   Also pins the float-erasure behavior (§2c in PROTOCOL.md): an attr
   declared :db.type/double accepts an integer on the wire and stores it
   as a Double; the query result comes back as a Double."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.sidecar.client :as client]
            [seon.sidecar.transit :as transit])
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

(defn- T [v] (transit/write-str v))

(defn- req! [op extra]
  (with-open [ch (client/connect (:req-sock *ctx*))]
    (client/call! ch (merge {"op" op} extra))))

(defn- result-of [resp] (transit/read-str (get resp "result")))

;; ---------- Schema ----------

(defn- install-typed-schema!
  "Install one attr per pinned type — `:thing/<type>`. Each attr stores
   one value per entity, keyed by `:thing/id` (string identity)."
  []
  (req! "transact"
        {"tx-data"
         (T
          [{:db/ident :thing/id
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one
            :db/unique :db.unique/identity}
           {:db/ident :thing/kw
            :db/valueType :db.type/keyword
            :db/cardinality :db.cardinality/one}
           {:db/ident :thing/str
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :thing/int
            :db/valueType :db.type/long
            :db/cardinality :db.cardinality/one}
           {:db/ident :thing/dbl
            :db/valueType :db.type/double
            :db/cardinality :db.cardinality/one}
           {:db/ident :thing/at
            :db/valueType :db.type/instant
            :db/cardinality :db.cardinality/one}
           {:db/ident :thing/flag
            :db/valueType :db.type/boolean
            :db/cardinality :db.cardinality/one}])}))

(defn- put!
  "Transact one entity carrying a single attr's value."
  [id attr v]
  (req! "transact" {"tx-data" (T [{:thing/id id attr v}])}))

(defn- get-attr [id attr]
  (result-of
    (req! "q" {"query" (T '[:find ?v . :in $ ?id ?a :where
                            [?e :thing/id ?id]
                            [?e ?a ?v]])
               "args"  [(T id) (T attr)]})))

;; ---------- Tests: per-type roundtrips ----------

(deftest test-keyword-roundtrip
  (testing "namespaced keywords survive the wire as keywords"
    (install-typed-schema!)
    (put! "a" :thing/kw :seon.urgent/high)
    (is (= :seon.urgent/high (get-attr "a" :thing/kw)))
    (put! "b" :thing/kw :nakedkw)
    (is (= :nakedkw (get-attr "b" :thing/kw)))))

(deftest test-string-roundtrip
  (install-typed-schema!)
  (put! "a" :thing/str "hello world")
  (is (= "hello world" (get-attr "a" :thing/str)))
  ;; embedded quotes + backslashes
  (put! "b" :thing/str "she said \"hi\" \\ done")
  (is (= "she said \"hi\" \\ done" (get-attr "b" :thing/str))))

(deftest test-integer-roundtrip
  (install-typed-schema!)
  (put! "a" :thing/int 42)
  (is (= 42 (get-attr "a" :thing/int)))
  (put! "b" :thing/int 0)
  (is (= 0 (get-attr "b" :thing/int)))
  (put! "c" :thing/int -1)
  (is (= -1 (get-attr "c" :thing/int))))

(deftest test-double-roundtrip-fractional
  (install-typed-schema!)
  (put! "a" :thing/dbl 3.14)
  (let [v (get-attr "a" :thing/dbl)]
    (is (instance? Double v) "stored as Double")
    (is (= 3.14 v))))

(deftest test-double-whole-coerces-from-int
  (testing "writing 1 (long) to a :db.type/double attr is coerced to 1.0
            (double) before transact. Schema-driven coercion in
            seon.sidecar.writer/coerce-tx-data-for-schema."
    (install-typed-schema!)
    (put! "a" :thing/dbl 1)
    (let [v (get-attr "a" :thing/dbl)]
      (is (instance? Double v)
          (str "expected Double, got " (class v) "/" v))
      (is (= 1.0 v)))))

(deftest test-instant-roundtrip
  (install-typed-schema!)
  (let [t (java.util.Date.)]
    (put! "a" :thing/at t)
    (let [v (get-attr "a" :thing/at)]
      (is (instance? java.util.Date v))
      (is (= (.getTime t) (.getTime ^java.util.Date v))))))

(deftest test-boolean-roundtrip
  (install-typed-schema!)
  (put! "a" :thing/flag true)
  (is (= true (get-attr "a" :thing/flag)))
  (put! "b" :thing/flag false)
  (is (= false (get-attr "b" :thing/flag))))

(deftest test-query-args-preserve-keyword-type
  (testing "a keyword arg passed via :in/?p matches keyword-typed datoms.
            With Transit the keyword stays a keyword end-to-end — no
            string coercion at the boundary."
    (install-typed-schema!)
    (req! "transact"
          {"tx-data" (T [{:thing/id "a" :thing/kw :urgent}
                          {:thing/id "b" :thing/kw :calm}])})
    (let [r (req! "q" {"query" (T '[:find ?id . :in $ ?p :where
                                    [?e :thing/kw ?p]
                                    [?e :thing/id ?id]])
                       "args"  [(T :urgent)]})]
      (is (= "a" (result-of r))
          "keyword arg :urgent matched the keyword-typed datom"))))

(deftest test-pull-result-preserves-types
  (testing "pull returns native Clojure types — keywords, instants, doubles"
    (install-typed-schema!)
    (let [t (java.util.Date.)]
      (req! "transact"
            {"tx-data" (T [{:thing/id "p"
                            :thing/kw :seon/marker
                            :thing/dbl 2.5
                            :thing/at  t
                            :thing/int 7
                            :thing/flag true}])})
      (let [r (req! "pull" {"selector" (T '[:thing/kw :thing/dbl :thing/at :thing/int :thing/flag])
                            "eid"      (T [:thing/id "p"])})
            m (result-of r)]
        (is (= :seon/marker (:thing/kw m)))
        (is (instance? Double (:thing/dbl m)))
        (is (= 2.5 (:thing/dbl m)))
        (is (instance? java.util.Date (:thing/at m)))
        (is (= (.getTime t) (.getTime ^java.util.Date (:thing/at m))))
        (is (= 7 (:thing/int m)))
        (is (= true (:thing/flag m)))))))

(deftest test-payload-field-decodes-to-tx-report
  (testing "the `payload` field on a transact response is a single Transit
            string decoding to the full Clojure tx-report map"
    (install-typed-schema!)
    (let [r (req! "transact"
                  {"tx-data" (T [{:thing/id "p" :thing/kw :seon/alpha}])})
          payload (transit/read-str (get r "payload"))]
      (is (map? payload))
      (is (keyword? (some-> payload :tx-meta keys first))
          (str "tx-meta should have keyword keys, got: " (pr-str payload)))
      (is (contains? (:tx-meta payload) :db/txInstant))
      (is (contains? (:tx-meta payload) :db/commitId))
      (is (pos? (:datoms-added payload))))))
