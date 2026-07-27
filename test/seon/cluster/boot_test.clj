(ns seon.cluster.boot-test
  "Sealed acceptance for the entry rung (B0).

  Orchestrator-authored (2026-07-27). The implementation lane makes
  these green by implementing the seon.cluster stubs ONLY — schemas and
  tests are byte-sealed; friction is reported, never resolved by
  weakening. The lifecycle tests are LIVE: they open real prepl
  sockets in this JVM and prove the REPL answers — the falsifier, not a
  fixture. Filesystem fixtures live under the project-local tmp/
  (never a system temp dir)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.cluster :as cluster]
            [seon.schema]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(defn- fresh-root []
  (let [root (str "tmp/boot-test/" (random-uuid))]
    (.mkdirs (io/file root))
    root))

(defn- delete-recursively! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [child (reverse (file-seq file))]
        (.delete ^java.io.File child)))))

(defn- prepl-eval
  "Open a real socket to `host:port`, evaluate `form-string` through
  io-prepl, return the :ret payload's :val string. The whole round trip
  is bounded by the socket timeout — a hang is a failure, not a wait."
  [host port form-string]
  (with-open [socket (java.net.Socket. ^String host (int port))]
    (.setSoTimeout socket 5000)
    (let [out (io/writer socket)
          in (io/reader socket)]
      (.write out (str form-string "\n"))
      (.flush out)
      (loop []
        (let [line (.readLine ^java.io.BufferedReader in)
              message (edn/read-string line)]
          (if (= :ret (:tag message))
            (:val message)
            (recur)))))))

;;; ---------------------------------------------------------------------------
;;; Bootstrap resolution — generative over the whole override domain
;;; ---------------------------------------------------------------------------

(def ^:private name-gen
  (gen/fmap #(str "c" %) gen/nat))

(def ^:private overrides-gen
  "Any subset of valid override keys."
  (gen/let [name? gen/boolean
            root? gen/boolean
            port? gen/boolean
            cluster-name name-gen
            port (gen/choose 0 65535)]
    (cond-> {}
      name? (assoc :seon.boot/cluster-name cluster-name)
      root? (assoc :seon.boot/root (str "tmp/boot-test/gen-" cluster-name))
      port? (assoc :seon.boot/prepl-port port))))

(deftest bootstrap-resolution-is-total-over-overrides
  (let [check
        (tc/quick-check
         100
         (prop/for-all [overrides overrides-gen]
           (let [config (cluster/resolve-bootstrap overrides)]
             (and
              ;; complete and valid against the closed schema
              (seon.schema/valid-candidate-value? :seon.boot/config config)
              ;; every supplied override wins verbatim
              (every? (fn [[k v]] (= v (get config k))) overrides)
              ;; defaults fill exactly the absent keys
              (= (get overrides :seon.boot/cluster-name "default")
                 (:seon.boot/cluster-name config))
              (= (get overrides :seon.boot/root "data/clusters")
                 (:seon.boot/root config))
              (string? (:seon.boot/log-dir config))
              ;; the process-root store every cluster branches from
              (string? (:seon.boot/store-dir config)))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "bootstrap resolution failed: " (pr-str check)))))

(deftest bootstrap-refuses-what-it-must
  (testing "an unknown key is refused, not ignored"
    (is (thrown? Exception
                 (cluster/resolve-bootstrap {:seon.boot/store-backend :file}))))
  (testing "an invalid value is refused"
    (is (thrown? Exception
                 (cluster/resolve-bootstrap {:seon.boot/cluster-name ""})))
    (is (thrown? Exception
                 (cluster/resolve-bootstrap {:seon.boot/prepl-port 99999}))))
  (testing "no overrides means the complete defaults document"
    (let [config (cluster/resolve-bootstrap {})]
      (is (= "default" (:seon.boot/cluster-name config)))
      (is (= "data/clusters" (:seon.boot/root config)))
      (is (= "127.0.0.1" (:seon.boot/prepl-host config)))
      (is (= 0 (:seon.boot/prepl-port config))))))

(deftest paths-derive-from-root-and-name-alone
  (let [check
        (tc/quick-check
         100
         (prop/for-all [cluster-name name-gen]
           (let [root "tmp/boot-test/paths"
                 paths (cluster/cluster-paths root cluster-name)
                 dir (:seon.boot/cluster-dir paths)]
             (and (str/starts-with? dir root)
                  (str/includes? dir cluster-name)
                  (every? #(str/starts-with? % dir)
                          [(:seon.boot/advertisement-file paths)
                           (:seon.boot/log-dir paths)])
                  ;; the store is NOT here: it is per process root
                  ;; (branch-per-cluster, b2-plan section 0)
                  (not (contains? paths :seon.boot/store-dir))
                  (not= (:seon.boot/advertisement-file paths)
                        (:seon.boot/log-dir paths)))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "path derivation failed: " (pr-str check)))))

;;; ---------------------------------------------------------------------------
;;; Root executors — one pair per JVM, shared
;;; ---------------------------------------------------------------------------

(deftest root-executors-are-one-shared-pair
  (let [first-pair (cluster/root-executors)
        second-pair (cluster/root-executors)]
    (is (identical? (:compute first-pair) (:compute second-pair))
        "repeated calls return the SAME compute executor")
    (is (identical? (:io first-pair) (:io second-pair))
        "repeated calls return the SAME io executor")))

;;; ---------------------------------------------------------------------------
;;; The live lifecycle — real sockets, real files, this JVM
;;; ---------------------------------------------------------------------------

(deftest repl-first-and-under-the-ten-second-bound
  (let [root (fresh-root)]
    (try
      (let [started-at (System/nanoTime)
            instance (cluster/start! {:seon.boot/cluster-name "solo"
                                      :seon.boot/root root})
            advertisement (:seon.boot/advertisement instance)
            answer (prepl-eval (:seon.boot/prepl-host advertisement)
                               (:seon.boot/prepl-port advertisement)
                               "(+ 20260727 1)")
            elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)]
        (try
          (testing "the REPL answers with the evaluated value"
            (is (= "20260728" answer)))
          (testing "start-to-answer beats the ten-second ruling"
            (is (< elapsed-ms 10000)
                (str "start!->REPL took " elapsed-ms " ms")))
          (testing "the advertisement validates and is discoverable"
            (is (seon.schema/valid-candidate-value?
                 :seon.boot/advertisement advertisement))
            (is (= advertisement
                   (cluster/read-advertisement root "solo"))))
          (finally
            (cluster/stop! instance))))
      (finally
        (delete-recursively! root)))))

(deftest two-instances-are-isolated
  (let [root (fresh-root)]
    (try
      (let [a (cluster/start! {:seon.boot/cluster-name "a"
                               :seon.boot/root root})
            b (cluster/start! {:seon.boot/cluster-name "b"
                               :seon.boot/root root})
            port-of #(get-in % [:seon.boot/advertisement
                                :seon.boot/prepl-port])]
        (try
          (testing "distinct coordinates, both answering by name"
            (is (not= (port-of a) (port-of b)))
            (is (= "\"a\"" (prepl-eval "127.0.0.1" (port-of a) "\"a\"")))
            (is (= "\"b\"" (prepl-eval "127.0.0.1" (port-of b) "\"b\""))))
          (testing "a second start! for a running cluster refuses"
            (is (thrown? Exception
                         (cluster/start! {:seon.boot/cluster-name "a"
                                          :seon.boot/root root}))))
          (testing "stopping a leaves b untouched"
            (cluster/stop! a)
            (is (nil? (cluster/read-advertisement root "a"))
                "a's advertisement is gone")
            (is (= "\"b\"" (prepl-eval "127.0.0.1" (port-of b) "\"b\"")))
            (is (some? (cluster/read-advertisement root "b"))))
          (testing "stop! is idempotent"
            (is (nil? (cluster/stop! a)))
            (is (nil? (cluster/stop! a))))
          (finally
            (cluster/stop! a)
            (cluster/stop! b))))
      (finally
        (delete-recursively! root)))))

(deftest stale-advertisements-read-as-absent
  (let [root (fresh-root)]
    (try
      (let [instance (cluster/start! {:seon.boot/cluster-name "stale"
                                      :seon.boot/root root})
            advertisement (:seon.boot/advertisement instance)
            file (io/file (:seon.boot/advertisement-file
                           (cluster/cluster-paths root "stale")))]
        (cluster/stop! instance)
        (testing "a wrong start-instant with a live pid reads as nil"
          (.mkdirs (.getParentFile file))
          (spit file
                (pr-str (assoc advertisement
                               :seon.boot/start-instant #inst "2000-01-01")))
          (is (nil? (cluster/read-advertisement root "stale"))))
        (testing "a dead pid reads as nil"
          (spit file (pr-str (assoc advertisement :seon.boot/pid 2)))
          (is (nil? (cluster/read-advertisement root "stale"))))
        (testing "garbage reads as nil, never as a throw"
          (spit file "{:not :an-advertisement")
          (is (nil? (cluster/read-advertisement root "stale")))))
      (finally
        (delete-recursively! root)))))

(deftest a-delayed-stop-never-kills-a-replacement
  ;; stops are instance-addressed: a stale stop! of an OLD instance
  ;; value must leave a same-named replacement fully alive
  (let [root (fresh-root)]
    (try
      (let [old-instance (cluster/start! {:seon.boot/cluster-name "swap"
                                          :seon.boot/root root})]
        (cluster/stop! old-instance)
        (let [replacement (cluster/start! {:seon.boot/cluster-name "swap"
                                           :seon.boot/root root})
              port (get-in replacement [:seon.boot/advertisement
                                        :seon.boot/prepl-port])]
          (try
            ;; the delayed second stop of the OLD value
            (is (nil? (cluster/stop! old-instance)))
            (is (= "\"alive\"" (prepl-eval "127.0.0.1" port "\"alive\""))
                "the replacement's REPL survived the stale stop")
            (is (some? (cluster/read-advertisement root "swap"))
                "the replacement's advertisement survived")
            (finally
              (cluster/stop! replacement)))))
      (finally
        (delete-recursively! root)))))
