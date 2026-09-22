(ns seon.dev.base-export-bound-test
  (:require [clojure.core.server :as server]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.id :as id]
            [seon.operator :as operator]
            [seon.test.cache :as cache]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Canonical physical published-root copy/re-identification, then real PREPL event-bound proof; the source export was observed still running at 491 s."
           :seon.test/long-ms 600000}
  publication-selects-operation-bounds-and-progress-renews-the-read
  (let [root (.getCanonicalPath (io/file "tmp" (str "base-export-bound-" (id/id))))
        name (str "base-export-bound-" (id/id))
        manifest {:seon.config.operator/event-silence-backstop-ms 1000
                  :seon.config.operator/boot-bound-ms 2000
                  :seon.config.operator/export-bound-ms 3000}
        received (atom [])
        send! operator/prepl-value!]
    (.mkdirs (io/file root))
    (try
      (support/populate-published-operator-root!
       root {:seon.test/fixture-observation
             "The preparation client discovers a real root advertisement and sends bounded requests over an actual io-prepl socket."})
      (let [socket (server/start-server {:name name :address "127.0.0.1" :port 0
                                         :accept 'clojure.core.server/io-prepl})
            endpoint (merge (operator/identity-of (java.lang.ProcessHandle/current))
                            {:seon.boot/cluster-name "bound-proof"
                             :seon.boot/prepl-host "127.0.0.1"
                             :seon.boot/prepl-port (.getLocalPort socket)})
            advertisement (io/file root "data/clusters/bound-proof/prepl.edn")]
        (io/make-parents advertisement)
        (spit advertisement (pr-str endpoint))
        (try
          (with-redefs [operator/prepl-value!
                        (fn observe-send!
                          ([target form]
                           (observe-send! target form (operator/operator-silence-backstop-ms {}) nil))
                          ([target form bound]
                           (observe-send! target form bound nil))
                          ([target form bound observe!]
                           (swap! received conj bound)
                           (send! target form bound observe!)))]
            (doseq [[operation expected] [[:init 2000] [:export 3000] [:status 1000]]
                    observer? [false true]]
              (let [options (cond-> {:seon.operator/command operation
                                    :seon.config/manifest manifest}
                              observer? (assoc :seon.operator/observe-output! identity))]
                (is (= 2 (:seon.operator/value (operator/live-root-value! root "(+ 1 1)" options))))
                (is (= expected (last @received)) "An observer does not choose the operation's bound."))))
          (testing "The real export owner emits completed-record progress over PREPL"
            (let [output (atom "")
                  path (str root "/data/store")
                  result (operator/live-root-value!
                          root (pr-str `(seon.cluster.export/reidentify! ~path))
                          {:seon.operator/command :export
                           :seon.config/manifest manifest
                           :seon.operator/observe-output! #(swap! output str %)})]
              (is (= path (:seon.operator/value result)))
              (is (str/includes? @output "re-identified records:"))))
          (testing "Elapsed duration can exceed the read interval when real out events continue"
            (let [output (atom "")
                  started (System/nanoTime)
                  result (operator/live-root-value!
                          root
                          "(do (dotimes [i 4] (println :completed i) (flush) (Thread/sleep 400)) :finished)"
                          {:seon.operator/command :export
                           :seon.config/manifest (assoc manifest :seon.config.operator/export-bound-ms 1000)
                           :seon.operator/observe-output! #(swap! output str %)})]
              (is (= :finished (:seon.operator/value result)))
              (is (> (/ (- (System/nanoTime) started) 1000000.0) 1000))
              (is (str/includes? @output ":completed 3"))))
          (testing "The base preparation identifies its compound publication/export request"
            (let [options (atom nil)
                  destination (str root "/prepared")]
              (with-redefs [operator/live-root-value!
                            (fn [_ _ supplied]
                              (reset! options supplied)
                              {:seon.operator/live-process? true :seon.operator/value destination})]
                (is (= destination (cache/prepare-base! root "." destination {}))))
              (is (= :export (:seon.operator/command @options)))
              (is (fn? (:seon.operator/observe-output! @options)))))
          (finally (server/stop-server name))))
      (finally (support/delete-recursively! root)))))
