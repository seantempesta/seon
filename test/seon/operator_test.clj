(ns seon.operator-test
  "The in-JVM operator surface stays a thin, error-valued delegation."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.instrument :as instrument]
            [seon.operator :as operator]
            [seon.operator.runtime :as runtime]))

(defn- caught
  [f]
  (try
    (f)
    nil
    (catch Throwable error
      error)))

(deftest start-refusals-are-flat-and-never-stop-the-running-instance
  (let [stop-calls (atom [])
        existing {:seon.boot/config
                  {:seon.boot/cluster-name "already-running"}}
        failure-data {:seon.error/kind :seon.boot/refused
                      :seon.boot/offense
                      {:seon.boot/cluster-name "already-running"}
                      :seon.boot/instance existing}]
    (with-redefs [cluster/start!
                  (fn [_]
                    (throw (ex-info "The cluster already has an instance."
                                    failure-data)))
                  cluster/stop! (fn [instance] (swap! stop-calls conj instance))]
      (let [result (operator/start!
                    {:seon.boot/cluster-name "already-running"})]
        (is (= :seon.boot/refused (:seon.error/kind result)))
        (is (= "The cluster already has an instance."
               (:seon.error/message result)))
        (is (identical? existing
                        (get-in result
                                [:seon.error/data :seon.boot/instance])))
        (is (empty? @stop-calls)
            "a refused or degraded boot is left up for diagnosis")))))

(deftest lifecycle-verbs-only-call-their-delegates
  (let [request {:seon.boot/cluster-name "second"
                 :seon.boot/root "tmp/operator-test"}
        original {:seon.boot/config request}
        replacement {:seon.boot/config request :seon.boot/ready-ms 1}
        calls (atom [])]
    (with-redefs [cluster/start!
                  (fn [value]
                    (swap! calls conj [:start value])
                    replacement)
                  cluster/stop!
                  (fn [value]
                    (swap! calls conj [:stop value])
                    nil)
                  cluster/refork!
                  (fn [value]
                    (swap! calls conj [:refork value])
                    {:seon.store/branch :cluster-second
                     :seon.cluster/created? true})]
      (is (identical? replacement (operator/start! request)))
      (is (nil? (operator/stop! original)))
      (is (identical? replacement (operator/restart! original)))
      (is (= {:seon.store/branch :cluster-second
              :seon.cluster/created? true}
             (operator/refork! original)))
      (is (= [[:start request]
              [:stop original]
              [:stop original]
              [:start request]
              [:refork original]]
             @calls)))))

(deftest status-banner-and-census-derive-current-runtime-values
  (let [instances-before @runtime/running-instances
        stores-before @runtime/root-store-holder
        store-a {:seon.operator-test/store :a}
        store-b {:seon.operator-test/store :b}
        ready-a {:seon.boot/cluster-name "a"
                 :seon.boot/pid 1
                 :seon.boot/prepl-port 1001
                 :seon.cluster.agent/count 0
                 :seon.problems/problems {}}
        ready-b (assoc ready-a
                       :seon.boot/cluster-name "b"
                       :seon.boot/prepl-port 1002)]
    (try
      (reset! runtime/running-instances
              {"b" {:seon.boot/advertisement
                    {:seon.boot/cluster-name "b"
                     :seon.boot/prepl-host "127.0.0.1"
                     :seon.boot/prepl-port 1002
                     :seon.boot/pid 1
                     :seon.boot/start-instant (java.util.Date. 0)}}
               "a" {:seon.boot/advertisement
                    {:seon.boot/cluster-name "a"
                     :seon.boot/prepl-host "127.0.0.1"
                     :seon.boot/prepl-port 1001
                     :seon.boot/pid 1
                     :seon.boot/start-instant (java.util.Date. 0)}}})
      (reset! runtime/root-store-holder
              {"a" {:seon.store/store store-a}
               "b" {:seon.store/store store-b}})
      (with-redefs [cluster/mcp-runtime-observation
                    (fn [cluster-name]
                      {:seon.dev.mcp/cluster cluster-name
                       :seon.dev.mcp/health :observed
                       :seon.dev.mcp/flow
                       {:seon.oversight/plumbing cluster-name}
                       :seon.dev.mcp/readiness
                       (if (= "a" cluster-name) ready-a ready-b)})
                    cluster/banner
                    (fn [ready] (str "ready " (:seon.boot/cluster-name ready)))
                    registry/roster
                    (fn [store]
                      (if (= store store-a)
                        #{:current-src :cluster-a}
                        #{:current-src :cluster-b}))]
        (testing "status is ordered and freshly derived"
          (is (= ["a" "b"]
                 (mapv :seon.boot/cluster-name
                       (:seon.operator/clusters (operator/status))))))
        (testing "banner delegates each derived readiness value"
          (is (= "ready a\n\nready b" (operator/banner))))
        (testing "clusters joins only advertisements and registry facts"
          (let [census (operator/clusters)]
            (is (= ["a" "b"]
                   (mapv :seon.boot/cluster-name
                         (:seon.operator/advertisements census))))
            (is (= #{:current-src :cluster-a :cluster-b}
                   (:seon.operator/branches census))))))
      (finally
        (reset! runtime/running-instances instances-before)
        (reset! runtime/root-store-holder stores-before)))))

(deftest publication-delegates-complete-and-incremental-requests
  (let [calls (atom [])
        published {:seon.source/branch :current-src
                   :seon.source/commit-id (random-uuid)
                   :seon.source/digest
                   "0000000000000000000000000000000000000000000000000000000000000000"
                   :seon.source/built? true}]
    (with-redefs [cluster/refresh-source!
                  (fn
                    ([root]
                     (swap! calls conj [root])
                     published)
                    ([root paths]
                     (swap! calls conj [root paths])
                     published))]
      (is (= published (operator/publish! {:seon.boot/root "root"})))
      (is (= published
             (operator/publish!
              {:seon.boot/root "root"
               :seon.operator/changed-paths ["src/seon/operator.clj"]})))
      (is (= [["root"] ["root" ["src/seon/operator.clj"]]] @calls)))))

(deftest public-contracts-refuse-invalid-input-and-output
  (let [delegate-calls (atom 0)]
    (try
      (instrument/apply! {:seon.config/on-core-error :panic})
      (with-redefs [cluster/start!
                    (fn [_]
                      (swap! delegate-calls inc)
                      :not-an-instance)]
        (testing "invalid input is refused before delegation"
          (let [failure (caught #(operator/start!
                                 {:seon.boot/cluster-name 42}))]
            (is (= :seon.instrument/contract-violated
                   (:seon.error/kind (ex-data failure))))
            (is (= 0 @delegate-calls))))
        (testing "invalid delegate output is refused at the public boundary"
          (let [failure (caught #(operator/start!
                                 {:seon.boot/cluster-name "valid"}))]
            (is (= :seon.instrument/contract-violated
                   (:seon.error/kind (ex-data failure))))
            (is (= 1 @delegate-calls)))))
      (finally
        (instrument/remove!)))))
