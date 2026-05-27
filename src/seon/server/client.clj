(ns seon.server.client
  "Tiny smoke client. Connects to the writer's req socket and runs a sequence
   of operations; also connects to the pub socket and prints tx events.

   Run with:
     clj -M:client
     clj -M:client --req-sock /tmp/seon-poc-req.sock --pub-sock /tmp/seon-poc-pub.sock"
  (:require [seon.server.codec :as codec])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels SocketChannel Channels])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- parse-args [args]
  (loop [acc {:req-sock "/tmp/seon-poc-req.sock"
              :pub-sock "/tmp/seon-poc-pub.sock"}
         xs args]
    (case (first xs)
      "--req-sock" (recur (assoc acc :req-sock (second xs)) (drop 2 xs))
      "--pub-sock" (recur (assoc acc :pub-sock (second xs)) (drop 2 xs))
      nil acc
      (do (println "Unknown arg:" (first xs)) (System/exit 2)))))

(defn connect
  "Open a UDS SocketChannel against `path`. Caller owns close."
  ^java.nio.channels.SocketChannel [^String path]
  (let [addr (UnixDomainSocketAddress/of path)
        ch (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect ch addr)
    ch))

(defn call!
  "Send one CBOR-encoded request on `ch`, wait for one response. Both are
   maps with string keys per PROTOCOL.md."
  [^java.nio.channels.SocketChannel ch req]
  (let [in  (Channels/newInputStream ch)
        out (Channels/newOutputStream ch)]
    (codec/write-frame! out req)
    (codec/read-frame in)))

(defn start-pub-collector!
  "Connect to the pub socket and conj every received event onto `events`
   (an atom holding a vector). Returns the channel — caller closes to stop.
   Optional `:on-event` fn is also called per event for ad-hoc assertions."
  ([^String path events]
   (start-pub-collector! path events nil))
  ([^String path events on-event]
   (let [ch (connect path)
         in (Channels/newInputStream ch)]
     (doto (Thread. ^Runnable
                    (fn []
                      (try
                        (loop []
                          (when-let [ev (codec/read-frame in)]
                            (swap! events conj ev)
                            (when on-event (try (on-event ev) (catch Throwable _)))
                            (recur)))
                        (catch Throwable _)))
                    "client-pub-collector")
       (.setDaemon true)
       (.start))
     ch)))

(defn- start-pub-reader! [^String path]
  (start-pub-collector! path
                        (atom [])
                        (fn [ev] (println "[pub-event]" (pr-str ev)))))

(defn- print-step [label resp]
  (println (format "%-30s %s" label (pr-str resp)))
  resp)

(defn -main [& args]
  (let [opts (parse-args args)
        _ (println "[client] connecting" opts)
        _ (start-pub-reader! (:pub-sock opts))
        ch (connect (:req-sock opts))]

    (print-step "ping" (call! ch {"op" "ping"}))

    ;; Install a tiny schema
    (print-step "schema install"
                (call! ch {"op" "transact"
                           "tx-data"
                           "[{:db/ident :person/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                              {:db/ident :person/age  :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}]"}))

    ;; Add a person
    (print-step "transact alice"
                (call! ch {"op" "transact"
                           "tx-data"
                           "[{:person/name \"alice\" :person/age 33}]"}))

    ;; Query
    (print-step "q alice"
                (call! ch {"op" "q"
                           "query" "[:find ?e ?n ?a :where [?e :person/name ?n] [?e :person/age ?a]]"
                           "args"  []}))

    ;; Pull by lookup ref
    (print-step "pull alice"
                (call! ch {"op" "pull"
                           "selector" "[:db/id :person/name :person/age]"
                           "eid"      "[:person/name \"alice\"]"}))

    ;; Wait briefly to let pub events flush
    (Thread/sleep 200)
    (println "[client] done.")
    (System/exit 0)))
