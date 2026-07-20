#!/usr/bin/env bb
;; bb variant probe: can bb's JVM-sci run agent-authored CLJS forms?
;; Three representative forms + a sci-context footprint idle phase.
(require '[sci.core :as sci])

(defn try-eval [ctx src]
  (try {:ok (sci/eval-string* ctx src)}
       (catch Throwable e {:err (.getMessage e)})))

(def ctx
  (sci/init {:namespaces
             {'seon.db {'transact! (fn [tx] {:seon.db/ok? true :tx-data tx})
                        'query (fn [_] [])}}}))

;; 1. pure data transform (my.plan-shaped)
(prn :pure
     (try-eval ctx
       "(let [plan (vec (for [i (range 100)]
                          {:my.plan/id i :my.plan/status :todo}))]
          (count (filter #(= :todo (:my.plan/status %)) plan)))"))

;; 2a. db call, plain (host fn is synchronous on the JVM)
(prn :db-plain (try-eval ctx "(:seon.db/ok? (seon.db/transact! [[:db/add 1 :a 1]]))"))

;; 2b. db call, Promise idiom as agents write it today
(prn :db-then (try-eval ctx "(.then (seon.db/transact! [[:db/add 1 :a 1]]) (fn [r] r))"))

;; 2c. ^:async/await idiom
(prn :async-await
     (try-eval ctx "(defn ^:async f [] (await (seon.db/transact! [:x]))) (f)"))

;; 3. js interop
(prn :js-date (try-eval ctx "(js/Date.now)"))
(prn :js-method (try-eval ctx "(.toFixed 3.14159 2)"))

;; idle for external vmmap
(println "IDLE pid" (.pid (java.lang.ProcessHandle/current)))
(flush)
(Thread/sleep 8000)
