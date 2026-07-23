(ns seon.agent.driver-pod-test
  (:require [cljs.test :refer [async deftest is]]
            [seon.agent.driver :as driver]
            [seon.agent.driver.pod :as driver.pod]))

(deftest dispatch-run-coalesces-process-local-fibers
  (async done
    (let [calls (atom 0)
          finish! (atom nil)
          work (js/Promise. (fn [resolve _reject] (reset! finish! resolve)))
          request {:seon.agent/id "agent"
                   :seon.agent.run/id "coalesced-run"}]
      (with-redefs [driver/drive-run!
                    (fn [_]
                      (swap! calls inc)
                      work)]
        (let [first-handle (driver.pod/dispatch-run! request)
              second-handle (driver.pod/dispatch-run! request)]
          (is (identical? first-handle second-handle))
          (is (= 1 @calls))
          (@finish! :finished)
          (-> (js/Promise.all #js [first-handle second-handle])
              (.then
               (fn [results]
                 (is (= [:finished :finished] (vec results)))
                 (done)))
              (.catch
               (fn [error]
                 (is false (str error))
                 (done)))))))))
