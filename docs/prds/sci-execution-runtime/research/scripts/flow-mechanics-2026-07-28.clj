;;; Flow mechanics probes for flow-mechanics-2026-07-28.md.
;;; Run one section per fresh JVM for clean thread/memory numbers:
;;;   clojure -M:dev docs/prds/sci-execution-runtime/research/scripts/flow-mechanics-2026-07-28.clj idle
;;;   ... pause | lifecycle | diagnostics | buffers | dbvchan | hotreload
;; Research probe: this measures the dependency (core.async flow, datahike)
;; directly; datahike.api use here is a measurement of the library, not a
;; production call site.

(ns flow-mechanics-probe
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files Path]))

(set! *warn-on-reflection* true)

;;; ---------------------------------------------------------------- helpers

(defn nano-now [] (System/nanoTime))

(defn ms [start] (/ (- (System/nanoTime) start) 1e6))

(defn gc-used-mb
  "Force GC and return used heap MB."
  []
  (System/gc)
  (Thread/sleep 200)
  (System/gc)
  (Thread/sleep 200)
  (let [rt (Runtime/getRuntime)]
    (/ (- (.totalMemory rt) (.freeMemory rt)) 1048576.0)))

(defn platform-thread-count []
  (.getThreadCount (ManagementFactory/getThreadMXBean)))

(defn thread-dump-counts
  "jcmd JSON thread dump of our own pid: [platform-count virtual-count]."
  []
  (let [pid (.pid (java.lang.ProcessHandle/current))
        file (str "/tmp/flow-probe-dump-" (System/nanoTime) ".json")
        p (.start (ProcessBuilder.
                   ^"[Ljava.lang.String;"
                   (into-array String ["jcmd" (str pid)
                                       "Thread.dump_to_file" "-format=json" file])))]
    (.waitFor p)
    (try
      (let [text (slurp file)
            total (count (re-seq #"\"tid\":" text))
            virtual (count (re-seq #"\"virtual\": ?true" text))]
        {:total total :virtual virtual :platform (- total virtual)})
      (finally
        (Files/deleteIfExists (Path/of file (into-array String [])))))))

(defn echo-step
  "Minimal step-fn: one in, one out, counts messages."
  ([] {:ins {:in "input"} :outs {:out "output"}
       :ping-map-fn identity})
  ([_arg-map] {:n 0})
  ([state _transition] state)
  ([state _in msg] [(update state :n (fnil inc 0)) {:out [msg]}]))

(defn tiny-graph
  "One-proc graph.
   workload :io -> loop on a virtual thread;
   :mixed -> loop on the cached platform pool."
  [workload]
  (flow/create-flow
   {:procs {:p {:proc (flow/process #'echo-step {:workload workload})}}
    :conns []}))

;;; ------------------------------------------------------------ 1 idle cost

(defn idle-probe [n workload]
  (println (format "\n== idle: %d graphs, workload %s ==" n workload))
  (let [mem0 (gc-used-mb)
        t0 (nano-now)
        graphs (vec (repeatedly n #(tiny-graph workload)))
        create-ms (ms t0)
        dump-created (thread-dump-counts)
        t1 (nano-now)
        _ (run! flow/start graphs)
        start-ms (ms t1)
        _ (Thread/sleep 500)
        dump-started (thread-dump-counts)
        mem1 (gc-used-mb)
        t2 (nano-now)
        _ (run! flow/stop graphs)
        stop-ms (ms t2)
        _ (Thread/sleep 1000)
        dump-stopped (thread-dump-counts)
        mem2 (gc-used-mb)]
    (println (format "create: %.1f ms total (%.3f ms/graph); threads after create %s"
                     create-ms (/ create-ms n) dump-created))
    (println (format "start:  %.1f ms total (%.3f ms/graph); threads parked %s; heap %.1f -> %.1f MB (%.1f KB/graph)"
                     start-ms (/ start-ms n) dump-started
                     mem0 mem1 (/ (* 1024 (- mem1 mem0)) n)))
    (println (format "stop:   %.1f ms total; threads after stop %s; heap %.1f MB"
                     stop-ms dump-stopped mem2))))

;;; -------------------------------------------------- 2 pause/resume/stop io

(def timeline (atom []))
(defn mark! [k] (swap! timeline conj [k (System/currentTimeMillis)]))

(defn slow-io-step
  "Fake model call: transform blocks 2000 ms."
  ([] {:ins {:in "req"} :outs {:out "reply"} :workload :io})
  ([_] {})
  ([state transition] (mark! [:transition transition]) state)
  ([state _in msg]
   (mark! [:transform-start msg])
   (Thread/sleep 2000)
   (mark! [:transform-end msg])
   [state {:out [msg]}]))

(defn sink-step
  ([] {:ins {:in "x"}})
  ([_] {})
  ([state _] state)
  ([state _in msg] (mark! [:sink-received msg]) [state nil]))

(defn pause-probe []
  (println "\n== pause with in-flight :io transform ==")
  (let [g (flow/create-flow
           {:procs {:model {:proc (flow/process #'slow-io-step)}
                    :sink {:proc (flow/process #'sink-step)}}
            :conns [[[:model :out] [:sink :in]]]})]
    (flow/start g)
    (flow/resume g)
    (reset! timeline [])
    (mark! :inject-1)
    (.get ^java.util.concurrent.Future (flow/inject g [:model :in] [:m1]))
    (Thread/sleep 200)
    (mark! :pause-call)
    (flow/pause g)
    (mark! :pause-returned)
    ;; second message injected while paused: buffered, not processed
    (.get ^java.util.concurrent.Future (flow/inject g [:model :in] [:m2]))
    (mark! :inject-2-done)
    (Thread/sleep 3000) ; well past m1's 2s sleep and any m2 processing
    (mark! :pre-resume)
    (flow/resume g)
    (Thread/sleep 2500)
    (mark! :stop-call)
    (flow/stop g)
    (Thread/sleep 300)
    (let [t0 (second (first @timeline))]
      (doseq [[k t] @timeline]
        (println (format "%6d ms  %s" (- t t0) (pr-str k)))))))

;;; ------------------------------------------------------ 3 lifecycle churn

(defn lifecycle-probe [cycles]
  (println (format "\n== lifecycle: %d create/start/resume/stop cycles (3-proc graph) ==" cycles))
  (let [mem0 (gc-used-mb)
        d0 (thread-dump-counts)
        make (fn []
               (flow/create-flow
                {:procs {:a {:proc (flow/process #'echo-step {:workload :io})}
                         :b {:proc (flow/process #'echo-step {:workload :io})}
                         :c {:proc (flow/process #'echo-step {:workload :io})}}
                 :conns [[[:a :out] [:b :in]] [[:b :out] [:c :in]]]}))
        t0 (nano-now)
        _ (dotimes [_ cycles]
            (let [g (make)]
              (flow/start g)
              (flow/resume g)
              (flow/stop g)))
        total (ms t0)
        _ (Thread/sleep 2000)
        mem1 (gc-used-mb)
        d1 (thread-dump-counts)]
    (println (format "%.1f ms total, %.3f ms/cycle" total (/ total cycles)))
    (println (format "threads before %s after %s; heap before %.1f after %.1f MB"
                     d0 d1 mem0 mem1))))

;;; --------------------------------------------- 4 diagnostics + error fanout

(defn faulty-step
  ([] {:ins {:in "x"} :ping-map-fn #(select-keys % [:handled])})
  ([_] {:handled 0})
  ([state _] state)
  ([state _in msg]
   (if (= msg :boom)
     (throw (ex-info "agent proc fault" {:msg msg}))
     [(update state :handled inc) nil])))

(defn diagnostics-probe []
  (println "\n== per-graph ping + error fan-in to one fault channel ==")
  (let [fault-chan (async/chan 16) ; cluster fault committer's inbox
        agents
        (into {}
              (for [id [:agent-1 :agent-2 :agent-3]]
                (let [g (flow/create-flow
                         {:procs {:loop {:proc (flow/process #'faulty-step)}}
                          :conns []})
                      {:keys [error-chan report-chan]} (flow/start g)]
                  ;; per-agent error channel -> shared fault channel,
                  ;; tagged with the agent id; the pipe never closes the
                  ;; shared channel (close? false).
                  (async/pipeline 1 fault-chan (map #(assoc % :agent id))
                                  error-chan false)
                  (flow/resume g)
                  [id {:graph g :report report-chan}])))]
    (.get ^java.util.concurrent.Future
          (flow/inject (:graph (agents :agent-2)) [:loop :in] [:ok :ok]))
    (Thread/sleep 100)
    (println "ping agent-2:" (pr-str (flow/ping (:graph (agents :agent-2)))))
    (.get ^java.util.concurrent.Future
          (flow/inject (:graph (agents :agent-2)) [:loop :in] [:boom]))
    (.get ^java.util.concurrent.Future
          (flow/inject (:graph (agents :agent-3)) [:loop :in] [:boom]))
    (dotimes [_ 2]
      (let [fault (async/<!! fault-chan)]
        (println "fault-committer received:"
                 (pr-str (-> fault
                             (select-keys [:agent
                                           :clojure.core.async.flow/pid
                                           :clojure.core.async.flow/cid
                                           :clojure.core.async.flow/msg])
                             (assoc :ex (str (:clojure.core.async.flow/ex fault))))))))
    ;; the faulted proc is still alive:
    (.get ^java.util.concurrent.Future
          (flow/inject (:graph (agents :agent-2)) [:loop :in] [:ok]))
    (Thread/sleep 100)
    (println "ping agent-2 after fault:"
             (pr-str (get-in (flow/ping (:graph (agents :agent-2)))
                             [:loop :clojure.core.async.flow/state])))
    (run! #(flow/stop (:graph %)) (vals agents))))

;;; ------------------------------------------- 5 buffers and backpressure

(defn fast-producer-step
  ([] {:ins {:in "kick"} :outs {:out "tokens"}})
  ([_] {:sent 0})
  ([state _] state)
  ([state _in n]
   ;; emit n messages in one transform; send-outputs parks on the conn
   [(update state :sent + n) {:out (vec (range n))}]))

(defn slow-consumer-step
  ([] {:ins {:in "tokens"} :ping-map-fn identity})
  ([_] {:got 0})
  ([state _] state)
  ([state _in _msg]
   (Thread/sleep 50)
   [(update state :got inc) nil]))

(defn backpressure-probe []
  (println "\n== backpressure: fixed conn buffer 4, consumer 50 ms/msg ==")
  (let [g (flow/create-flow
           {:procs {:prod {:proc (flow/process #'fast-producer-step)
                           :chan-opts {:out {:buf-or-n 4}}}
                    :cons {:proc (flow/process #'slow-consumer-step)
                           :chan-opts {:in {:buf-or-n 4}}}}
            :conns [[[:prod :out] [:cons :in]]]})]
    (flow/start g)
    (flow/resume g)
    (.get ^java.util.concurrent.Future (flow/inject g [:prod :in] [100]))
    (doseq [t [200 1000 2000]]
      (Thread/sleep (if (= t 200) 200 (- t (if (= t 1000) 200 1000))))
      (let [pong (flow/ping g)]
        (println (format "t=%4d ms  producer count=%s  consumer got=%s"
                         t
                         (get-in pong [:prod :clojure.core.async.flow/count])
                         (get-in pong [:cons :clojure.core.async.flow/state :got])))))
    (flow/stop g))
  (println "\n== sliding-buffer 1: streamed-token presentation tap ==")
  (let [c (async/chan (async/sliding-buffer 1))
        produced 100000
        t0 (nano-now)]
    (dotimes [i produced] (async/>!! c i))
    (let [put-ms (ms t0)
          latest (async/poll! c)]
      (println (format "%d puts in %.1f ms (%.0f puts/ms), consumer sees only latest=%s, producer never parked"
                       produced put-ms (/ produced put-ms) latest)))))

;;; ------------------------------------- 5b large value: channel vs datahike

(defn dbvchan-probe []
  (println "\n== 8 MB string: channel hand-off vs datahike transact ==")
  (let [big (apply str (repeat (* 8 1024 1024) \x)) ; 8M chars
        c (async/chan 1)
        rounds 100
        t0 (nano-now)]
    (dotimes [_ rounds]
      (async/>!! c big)
      (async/<!! c))
    (let [chan-ms (/ (ms t0) rounds)]
      (println (format "channel: %.4f ms per hand-off (reference pass, no copy)" chan-ms)))
    (require '[datahike.api :as d])
    (let [d-create (resolve 'datahike.api/create-database)
          d-connect (resolve 'datahike.api/connect)
          d-transact (resolve 'datahike.api/transact)
          d-delete (resolve 'datahike.api/delete-database)
          d-release (resolve 'datahike.api/release)
          probe (fn [label cfg]
                  (when (:store cfg) (try (d-delete cfg) (catch Exception _)))
                  (d-create cfg)
                  (let [conn (d-connect cfg)]
                    (try
                      (let [_ (d-transact
                               conn
                               [{:db/ident :probe/blob
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one}])
                            one (fn [s]
                                  (let [t (nano-now)]
                                    (d-transact conn [{:db/id -1 :probe/blob s}])
                                    (ms t)))
                            small (apply str (repeat 65536 \x))
                            warm (one small)
                            t64k (one small)
                            t8m (one big)
                            t8m2 (one big)]
                        (println
                         (format "%s: 64KB %.1f ms | 8MB %.1f ms, again %.1f ms (warmup %.1f)"
                                 label t64k t8m t8m2 warm)))
                      (finally
                        (d-release conn)
                        (d-delete cfg)))))]
      (probe "datahike :mem "
             {:store {:backend :memory :id (java.util.UUID/randomUUID)}
              :schema-flexibility :write})
      (probe "datahike :file"
             {:store {:backend :file :path "tmp/flow-probe-db"
                      :id (java.util.UUID/randomUUID)}
              :schema-flexibility :write}))))

;;; ------------------------------------------------------------- hot reload

(defn reloadable-step
  ([] {:ins {:in "x"} :outs {:out "y"}})
  ([_] {})
  ([state _] state)
  ([state _in msg] [state {:out [[:v1 msg]]}]))

(defn hotreload-probe []
  (println "\n== hot reload: var step-fn vs captured closure ==")
  (let [out (atom [])
        sink (fn ([] {:ins {:in "x"}}) ([_] {}) ([s _] s)
               ([s _in m] (swap! out conj m) [s nil]))
        g (flow/create-flow
           {:procs {:p {:proc (flow/process #'reloadable-step)}
                    :closure {:proc (flow/process
                                     ;; captured VALUE of the var, not the var
                                     (let [f @#'reloadable-step]
                                       (fn ([] (f)) ([a] (f a)) ([s t] (f s t))
                                         ([s i m] (f s i m)))))}
                    :sink {:proc (flow/process sink)}}
            :conns [[[:p :out] [:sink :in]]
                    [[:closure :out] [:sink :in]]]})]
    (flow/start g)
    (flow/resume g)
    (.get ^java.util.concurrent.Future (flow/inject g [:p :in] [:a]))
    (.get ^java.util.concurrent.Future (flow/inject g [:closure :in] [:a]))
    (Thread/sleep 200)
    ;; "hot reload": re-def the step-fn
    (alter-var-root #'reloadable-step
                    (constantly
                     (fn ([] {:ins {:in "x"} :outs {:out "y"}})
                       ([_] {})
                       ([state _] state)
                       ([state _in msg] [state {:out [[:v2 msg]]}]))))
    (.get ^java.util.concurrent.Future (flow/inject g [:p :in] [:b]))
    (.get ^java.util.concurrent.Future (flow/inject g [:closure :in] [:b]))
    (Thread/sleep 200)
    (println "sink saw:" (pr-str @out)
             " (var proc switched to :v2; closure proc stayed :v1)")
    (flow/stop g)))

;;; ---------------------------------------------------------------- driver

(let [section (first *command-line-args*)]
  (case section
    "idle" (do (idle-probe 10 :io)
               (idle-probe 100 :io)
               (idle-probe 1000 :io)
               (idle-probe 100 :mixed))
    "pause" (pause-probe)
    "lifecycle" (lifecycle-probe 1000)
    "diagnostics" (diagnostics-probe)
    "buffers" (backpressure-probe)
    "dbvchan" (dbvchan-probe)
    "hotreload" (hotreload-probe)
    (println "usage: idle | pause | lifecycle | diagnostics | buffers | dbvchan | hotreload"))
  (shutdown-agents)
  (System/exit 0))
