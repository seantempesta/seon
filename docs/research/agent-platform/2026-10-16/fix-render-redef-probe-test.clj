(ns seon.scratch.redef-probe-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [sci.impl.vars]
            [seon.db :as db]
            [seon.render]
            [seon.sci.eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]
            [seon.render-simplification-test :as rst]))

(deftest probe
  (support/with-database
   (fn [connection]
     (#'rst/declare-fixture-subjects! connection)
     (let [fa 'seon.render-simplification.fixture-a
           ctx (support/fork-cluster-ctx connection)
           sym 'seon.render-simplification.fixture-a/namespace-ai
           req #(assoc (#'rst/render-request (db/db connection) ctx fa {:seon.ns/name fa}) :seon.render/output :seon.render/ai)
           look (fn [] (let [v (sci/resolve ctx sym)
                             b (when v (sci.impl.vars/context-binding ctx v))]
                         {:own (when v (#'seon.sci.eval/own-binding? ctx v))
                          :installed (contains? @(:seon.sci.kernel/installed-functions ctx) sym)
                          :cd (get-in @(:seon.sci.kernel/program-snapshot ctx) [:functions sym :seon.sci.eval/context-defined?])
                          :root (some-> b .-root class str)
                          :meta-keys (some-> b .-meta keys sort vec)}))
           _ (sci/binding [sci/ns (sci/create-ns fa)]
               (sci/eval-form ctx '(defn namespace-ai [value] (str "private:" (:seon.ns/name value)))))
           s0 (look)
           r1 (seon.render/render-call (req))
           s1 (look)
           _ (support/transacted!
              connection
              [(support/revision (db/db connection) [:seon.fn/sym sym]
                                 {:seon.fn/source "(defn namespace-ai [value] (str \"program:\" (:seon.ns/name value)))"})])
           _ (support/evaluate {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                                :seon.cluster.eval/ns [:seon.ns/name fa] :seon.cluster.eval/source "1"
                                :seon.sci.admit/caps (:seon.sci.admit/caps (req)) :seon.sci.eval/time-limit-ms 2000})
           s2 (look)
           r2 (seon.render/render-call (req))
           s3 (look)]
       (is (= :probe {:s0 s0 :r1 r1 :s1 s1 :s2 s2 :r2 (if (string? r2) r2 (get-in r2 [:seon.error/offending :seon.error/message])) :s3 s3}))))))
