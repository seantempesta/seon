(ns seon.render.web-prompt-test
  "Tests the debug prompt comparison's independent evidence labels."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.render.web]
            [seon.test-support :as support]))

(def ^:private debug-prompt
  (ns-resolve 'seon.render.web 'debug-prompt))
(def ^:private debug-ai-html
  (ns-resolve 'seon.render.web 'debug-ai-html))

(deftest debug-prompt-compares-a-real-capture-with-the-prospective-result
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.cluster.agent/id "agent-1"}
       {:seon.cluster.run/id "run-1"
        :seon.cluster.run/agent [:seon.cluster.agent/id "agent-1"]}
       {:seon.context.capture/id "capture-42"
        :seon.context.capture/run [:seon.cluster.run/id "run-1"]
        :seon.context.capture/basis-t 42
        :seon.context.capture/prompt "captured text"}])
     (with-redefs-fn
       {#'seon.render.web/prospective-prompt
        (constantly {:seon.render.debug/prompt "prospective text"
                     :seon.render.debug/prompt-kind :prospective
                     :seon.render.debug/prompt-basis-t 43})}
       #(let [comparison (debug-prompt (db/db connection)
                                       connection "agent-1" :caps :context)]
          (is (= "captured text"
                 (get-in comparison
                         [:seon.render.debug/captured
                          :seon.render.debug/prompt])))
          (is (= 42 (get-in comparison
                            [:seon.render.debug/captured
                             :seon.render.debug/prompt-basis-t])))
          (is (= "capture-42"
                 (get-in comparison
                         [:seon.render.debug/captured
                          :seon.render.debug/prompt-id])))
          (is (= "prospective text"
                 (get-in comparison
                         [:seon.render.debug/prospective
                          :seon.render.debug/prompt])))
          (is (= 43 (get-in comparison
                            [:seon.render.debug/prospective
                             :seon.render.debug/prompt-basis-t]))))))))

(deftest debug-prompt-labels-an-unavailable-prospective-result-without-a-capture
  (support/with-database
   (fn [connection]
     (with-redefs-fn
       {#'seon.render.web/prospective-prompt
        (constantly {:seon.render.debug/prompt-kind :unavailable
                     :seon.render.debug/prompt-basis-t 7
                     :seon.error/value
                     {:seon.error/kind :seon.render.web/prospective-failed
                      :seon.error/data
                      {:seon.error/diagnostic-cause "test failure"}}})}
       #(let [comparison (debug-prompt (db/db connection)
                                       connection "missing-agent" :caps :context)
              html (debug-ai-html "missing-agent" comparison)]
          (is (nil? (:seon.render.debug/captured comparison)))
          (is (= :unavailable
                 (:seon.render.debug/prompt-kind comparison)))
          (is (.contains html "historical captured prompt"))
          (is (.contains html "newly computed prospective prompt"))
          (is (.contains html "database basis 7"))
          (is (.contains html "prospective-failed")))))))
