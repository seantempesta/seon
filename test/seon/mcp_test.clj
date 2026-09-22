(ns seon.mcp-test
  "Total outward preparation through the real MCP PREPL."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.id :as id]
            [seon.operator.runtime :refer [running-instances]]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(defn- prepl-events
  [cluster-name effective form]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader.
                     (java.io.StringReader. (pr-str form)))
              writer (java.io.StringWriter.)]
    (binding [*in* reader *out* writer]
      (cluster/mcp-io-prepl cluster-name effective))
    (with-open [events (java.io.PushbackReader.
                       (java.io.StringReader. (str writer)))]
      (loop [result []]
        (let [event (edn/read {:eof ::end} events)]
          (if (= ::end event) result
              (recur (conj result event))))))))

(deftest outward-values-use-total-projection
  (support/with-database
   (fn [connection]
     (let [cluster-name (str "mcp-total-" (id/id))
           effective (support/effective-config)
           profile (render/agent-render-profile effective)
           database (db/db connection)
           ctx (support/fork-cluster-ctx connection)
           request {:seon.db/db database
                    :seon.sci.eval/ctx ctx
                    :seon.schema/projection (db/carried-projection database)
                    :seon.render/profile profile
                    :seon.render/value {"a" 1 "b" 2}
                    :seon.sci.admit/caps (config/result-caps effective)
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :record}]
       (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name cluster-name})
       (swap! running-instances assoc cluster-name
              {:seon.boot/cluster-connection connection
               :seon.sci.eval/ctx
               (env/carry-state {} (:seon.sci.eval/projection-state (meta database)))})
       (try
         (doseq [[form expected]
                 [['(sorted-map "a" 1 "b" 2) {"a" 1 "b" 2}]
                  ['{:seon.sci.admit/record {} :seon.eval/shown "ordinary"}
                   {:seon.sci.admit/record {} :seon.eval/shown "ordinary"}]
                  ['{:via [] :trace [] :cause "ordinary"}
                   {:via [] :trace [] :cause "ordinary"}]]]
           (let [events (prepl-events
                         cluster-name effective
                         (list 'do '(seon.cluster/project-next-prepl-value!) form))
                 terminal (filterv #(= :ret (:tag %)) events)]
             (is (= 1 (count terminal)) (pr-str events))
             (is (not (:exception (first terminal))) (pr-str events))
             (is (= expected
                    (:seon.dev.mcp/value
                     (edn/read-string (:val (first terminal))))))))
         (let [events (prepl-events cluster-name effective
                       '(do (seon.cluster/project-next-prepl-value!)
                            (throw (ex-info "expected failure" {}))))
               terminal (filterv #(= :ret (:tag %)) events)
               result (:seon.dev.mcp/value
                       (edn/read-string (:val (first terminal))))]
           (is (= 1 (count terminal)))
           (is (true? (:exception (first terminal))))
           (is (symbol? (:seon.error/exception-class result)))
           (is (not (contains? result :trace))))
         (let [visits (atom 0) emits (atom 0)
               visit @#'value/value-node emit print/emit-both]
           (with-redefs-fn
             {#'value/value-node (fn [& args] (swap! visits inc) (apply visit args))
              #'print/emit-both (fn [& args] (swap! emits inc) (apply emit args))}
             (fn []
               (is (string? (:seon.render.value/root-description (value/prepare request))))
               (is (= [0 0] [@visits @emits]))
               (let [prepared (value/prepare
                               (assoc request :seon.render.value/root [::root]))]
                 (is (= {"a" 1 "b" 2}
                        (edn/read-string (:seon.render.value/text prepared))))
                 (is (pos? @visits))
                 (is (= 1 @emits))))))
         (let [calls (atom [])
               invoke kernel/invoke
               failing (assoc request :seon.render.value/root [::root]
                              :seon.render/value
                              {:seon.render/ai 'seon.error/render-ai
                                :seon.error/message "probe"})]
           (with-redefs [kernel/invoke
                         (fn [invocation]
                           (if (= "seon.error/render-ai" (:seon.fn/sym invocation))
                             (do (swap! calls conj (:seon.fn/sym invocation))
                                 (throw (ex-info "declared producer failure" {})))
                             (invoke invocation)))]
             (let [prepared (value/prepare failing)
                   shown (edn/read-string (:seon.render.value/text prepared))
                   failures (filter #(and (map? %)
                                          (= "declared producer failure" (:seon.error/message %)))
                                    (tree-seq coll? seq shown))]
               (is (= ["seon.error/render-ai"] @calls))
               (is (= 1 (count failures)))
               (is (= "declared producer failure" (:seon.error/message shown))))))
         (let [calls (atom 0)]
           (with-redefs [value/prepare (fn [& _] (swap! calls inc)
                                        (throw (ex-info "preparation failed" {})))]
             (let [events (prepl-events cluster-name effective
                            '(do (seon.cluster/project-next-prepl-value!) 42))
                   terminal (filterv #(= :ret (:tag %)) events)
                   result (:seon.dev.mcp/value
                           (edn/read-string (:val (first terminal))))]
               (is (= 1 (count terminal)))
               (is (not (:exception (first terminal))))
               (is (= 1 @calls))
               (is (string? (:seon.dev.mcp/projection-offending-class result))))))
         (finally (swap! running-instances dissoc cluster-name)))))))
