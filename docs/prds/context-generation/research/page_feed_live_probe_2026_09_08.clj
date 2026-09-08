(ns page-feed-live-probe-2026-09-08
  "Explicit-cluster observations for caller-owned page and context derivation."
  (:require [seon.db :as db]
            [seon.operator.runtime :as runtime]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.sci.kernel :as kernel]
            [seon.turn :as turn]))

(defn write-load!
  "Write at one-second target intervals and record actual completion and open turns."
  [cluster-name count-writes output-path]
  (let [instance (get @runtime/running-instances cluster-name)
        handle (:seon.cluster.loop/cluster instance)
        connection (:seon.db/connection handle)]
    (schema/call-with-projection
     (kernel/context-projection (:seon.sci.eval/ctx handle))
     (fn []
       (let [start (System/currentTimeMillis)
             rows (mapv
                   (fn [n]
                     (Thread/sleep (long (max 0 (- (+ start (* 1000 n))
                                                  (System/currentTimeMillis)))))
                     (let [result (db/transact! connection {:tx-data []})
                           database @connection]
                       {:at (System/currentTimeMillis)
                        :basis (db/basis-t database)
                        :error (:seon.error/kind result)
                        :open (db/q '[:find ?agent-id ?run-id
                                      :where [?a :seon.cluster.agent/id ?agent-id]
                                      [?a :seon.cluster.agent/run ?r]
                                      [?r :seon.cluster.run/id ?run-id]
                                      (not [?r :seon.cluster.run/closed-at])]
                                    database)}))
                   (range count-writes))]
         (spit output-path (pr-str rows))
         {:writes (count rows) :first (first rows) :last (last rows)
          :errors (count (filter :error rows))})))))

(defn submit-load!
  "Submit finite computation to the two explicitly seeded probe agents."
  [cluster-name]
  (let [instance (get @runtime/running-instances cluster-name)]
    (mapv #(turn/virtual-turn!
            {:seon.cluster.loop/cluster (:seon.cluster.loop/cluster instance)
             :seon.cluster.agent/routing (:seon.cluster.agent/routing instance)
             :seon.cluster.agent/id %
             :seon.cluster.reply/text
             "(loop [n 1000000000] (if (zero? n) :page-feed-probe-complete (recur (dec n))))"})
          ["juniper" "root"])))

(defn observe-load!
  "Record open turns once per second without introducing write traffic."
  [cluster-name samples output-path]
  (let [handle (:seon.cluster.loop/cluster
                (get @runtime/running-instances cluster-name))
        connection (:seon.db/connection handle)]
    (schema/call-with-projection
     (kernel/context-projection (:seon.sci.eval/ctx handle))
     (fn []
       (let [rows (mapv
                   (fn [_]
                     (let [row {:at (System/currentTimeMillis)
                                :open (db/q '[:find ?agent-id ?run-id
                                              :where [?a :seon.cluster.agent/id ?agent-id]
                                              [?r :seon.cluster.run/agent ?a]
                                              [?r :seon.cluster.run/id ?run-id]
                                              (not [?r :seon.cluster.run/closed-at])]
                                            @connection)}]
                       (Thread/sleep 1000)
                       row))
                   (range samples))]
         (spit output-path (pr-str rows))
         {:samples (count rows)
          :two-open (count (filter #(<= 2 (count (:open %))) rows))
          :first (first rows) :last (last rows)})))))

(defn context-comparison!
  "Compare the historical context derivation with the direct entry on one DB.
  The historical timing excludes its old proc queue; it is the stricter baseline."
  [cluster-name agent-id run-id output-path]
  (let [handle (:seon.cluster.loop/cluster
                (get @runtime/running-instances cluster-name))
        ctx (:seon.sci.eval/ctx handle)
        database @(:seon.db/connection handle)
        request (merge handle
                       {:seon.db/db database
                        :seon.cluster.agent/id agent-id
                        :seon.cluster.run/id run-id
                        :seon.render/distance 1
                        :seon.sci.eval/time-limit-ms
                        (:seon.config.eval/time-limit-ms (seon.config/defaults))
                        :seon.render/profile
                        (render/agent-render-profile (seon.config/defaults))})
        source (:out ((requiring-resolve 'clojure.java.shell/sh)
                      "git" "show" "1806ee596:src/seon/render/web.clj"))
        historical
        (binding [*ns* (the-ns 'seon.render.web)]
          (with-open [reader (java.io.PushbackReader. (java.io.StringReader. source))]
            (loop []
              (let [form (read {:eof ::end} reader)]
                (when (= ::end form)
                  (throw (ex-info "Historical context-pass definition missing" {})))
                (if (and (seq? form) (= 'defn- (first form))
                         (= 'context-pass (second form)))
                  (eval (cons 'fn (drop 2 form)))
                  (recur))))))]
    (schema/call-with-projection
     (kernel/context-projection ctx)
     (fn []
       (let [started (System/nanoTime)
             initial (render/acquire-context! request)
             cold-ms (/ (- (System/nanoTime) started) 1e6)
             _ (when-not (string? (:seon.cluster.prompt/text initial))
                 (throw (ex-info "Context acquisition refused" initial)))
             rows (mapv
                   (fn [_]
                     (let [state @(render/shared-cache ctx)
                           start (System/nanoTime)
                           [_ before] (historical state {:seon.render.context/request request})
                           before-ms (/ (- (System/nanoTime) start) 1e6)
                           start (System/nanoTime)
                           after (render/acquire-context! request)]
                       {:historical-ms before-ms
                        :direct-ms (/ (- (System/nanoTime) start) 1e6)
                        :same-text? (= (:seon.cluster.prompt/text before)
                                       (:seon.cluster.prompt/text after))}))
                   (range 12))
             result {:cold-ms cold-ms
                     :characters (count (:seon.cluster.prompt/text initial))
                     :samples rows}]
         (spit output-path (pr-str result))
         result)))))
