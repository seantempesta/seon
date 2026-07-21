(ns seon.host-toolkit-writer-test
  "Dependency-ordered JVM-host toolkit loading and its honest ledger."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.host.context :as context]))

(defn- unconnected-writer []
  (context/writer-session
   {::context/writer-socket-path "tmp/unused-toolkit-test.sock"
    ::context/database-name "toolkit-test"}))

(deftest parsed-require-targets-determine-the-load-order
  (let [provider {::context/namespace 'my.probe.provider
                  ::context/require-edges #{}}
        consumer {::context/namespace 'my.probe.consumer
                  ::context/require-edges
                  #{{:seon.ns.require/target 'my.probe.provider}}}
        result (context/dependency-order [consumer provider])]
    (is (= ['my.probe.provider 'my.probe.consumer]
           (mapv ::context/namespace (::context/ordered result))))
    (is (empty? (::context/cycle result))))
  (let [left {::context/namespace 'my.probe.left
              ::context/require-edges
              #{{:seon.ns.require/target 'my.probe.right}}}
        right {::context/namespace 'my.probe.right
               ::context/require-edges
               #{{:seon.ns.require/target 'my.probe.left}}}
        result (context/dependency-order [right left])]
    (is (empty? (::context/ordered result)))
    (is (= ['my.probe.right 'my.probe.left] (::context/cycle result)))))

(deftest toolkit-report-ledgers-every-discovered-definition
  (let [report (::context/report (context/build-base! (unconnected-writer)))
        rows (::context/blocks report)
        failures (::context/failures report)
        status-counts (frequencies (map ::context/status rows))]
    (is (= (count rows)
           (+ (::context/loaded report)
              (::context/failed report)
              (::context/excluded report))))
    (is (= (::context/loaded report) (get status-counts :loaded 0)))
    (is (= (::context/failed report) (get status-counts :failed 0)))
    (is (= (::context/excluded report) (get status-counts :excluded 0)))
    (is (= (::context/failed report) (count failures)))
    (is (every? #(or (= :loaded (::context/status %))
                     (seq (::context/reason %)))
                rows))
    (is (every? #(and (string? (::context/source-path %))
                      (symbol? (::context/namespace %))
                      (seq (::context/block-name %)))
                rows))
    (testing "same-namespace definitions preserve source order"
      (let [canvas (vec (filter #(= 'my.canvas (::context/namespace %)) rows))
            position (zipmap (map ::context/block-name canvas) (range))]
        (is (< (position "button-class") (position "button")))))))
