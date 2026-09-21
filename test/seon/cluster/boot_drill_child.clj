(ns seon.cluster.boot-drill-child
  "Test-only child controls at the real destructive store and boot seams."
  (:require [clojure.core.server :as server]
            [clojure.java.io :as io]))

(defn -main [root callback-port bound mode cluster-name]
  (let [bound (Long/parseLong bound)
        listener (server/start-server {:name (str "seon.cluster/" cluster-name) :accept 'clojure.core.server/io-prepl
                                        :address "127.0.0.1" :port 0})
        identity {:seon.boot/pid (.pid (java.lang.ProcessHandle/current))
                  :seon.boot/start-instant (java.util.Date/from (.get (.startInstant (.info (java.lang.ProcessHandle/current)))))}]
    (with-open [socket (java.net.Socket. "127.0.0.1" (Integer/parseInt callback-port))
                writer (io/writer socket)
                reader (java.io.BufferedReader. (io/reader socket))]
      (.setSoTimeout socket (int bound))
      (let [send! (fn [value] (.write writer (str (pr-str value) "\n")) (.flush writer))]
        (send! (merge identity {:seon.boot/cluster-name cluster-name :seon.boot/prepl-host "127.0.0.1"
                                :seon.boot/prepl-port (.getLocalPort listener)}))
        (require 'seon.cluster.boot 'seon.fs 'seon.operator.runtime)
        (let [delete-var (ns-resolve 'seon.fs 'delete-recursively!)
              delete @delete-var
              captured (atom nil)
              acquire-var (ns-resolve 'seon.cluster 'acquire-root-store!)
              acquire @acquire-var
              lock-path (str root "/data/store.lock")
              hold! (fn [event lock]
                      (reset! captured {:seon.probe/lock lock :seon.probe/channel (.channel lock)})
                      (send! {:seon.probe/event event :seon.probe/lock-valid? (.isValid lock)})
                      (when-not (= "continue" (.readLine reader))
                        (throw (ex-info "Missing fixture continuation." {}))))]
          (try
            (with-redefs-fn
              {acquire-var
               (fn [& arguments]
                 (let [opened (apply acquire arguments)]
                   (when (and (= "start" mode) (nil? @captured))
                     (hold! :store-entry (:seon.store/lock opened)))
                   opened))
               delete-var
               (fn [& arguments]
                 (when (and (= "destroy" mode) (= (str root "/data/store") (first arguments)) (nil? @captured))
                   (let [lock (get @@(ns-resolve 'seon.operator.runtime 'held-flocks) lock-path)]
                     (hold! :delete-entry lock)))
                 (apply delete arguments))}
              (fn []
                (let [instance ((ns-resolve 'seon.cluster.boot 'start!)
                                {:seon.boot/root (str root "/data/clusters") :seon.boot/cluster-name cluster-name
                                 :seon.boot/prepl-server listener :seon.store/destroy? (= "destroy" mode)})
                      lock (get-in instance [:seon.store/store :seon.store/lock])]
                  (send! {:seon.probe/event :ready
                          :seon.probe/same-lock? (identical? lock (:seon.probe/lock @captured))
                          :seon.probe/same-channel? (identical? (.channel lock) (:seon.probe/channel @captured))
                          :seon.boot/readiness ((ns-resolve 'seon.cluster.boot 'readiness) instance)}))))
            (catch Throwable cause
              (send! {:seon.probe/event :failed :seon.error/message (ex-message cause)})))))
      ;; Test controls own the child lifetime; its exact ProcessHandle is reaped
      ;; by the parent even if readiness or this final control never arrives.
      (when-not (= "exit" (.readLine reader))
        (.halt (Runtime/getRuntime) 2))
      (.halt (Runtime/getRuntime) 0))))
