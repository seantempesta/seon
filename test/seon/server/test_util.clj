(ns seon.server.test-util
  "Shared in-process wire-server fixture for the seon.server.* protocol tests.

   The `clojure -M:writer` subprocess alias was removed when the V2
   jvm-writer PoC was folded into `src/seon/server` (Wave 4a). These
   tests now start the wire-server IN-PROCESS against a fresh `:memory`
   datahike store, driven through the public request handlers in
   `seon.server.wire`.

   Isolation: each `spawn-writer!` builds its store via
   `seon.server.store/config-for` with a UNIQUE per-invocation db-name
   (`:test/wire<gensym>`). config-for derives a deterministic per-name
   store `:id`, so two fixtures never collide on one process-global
   in-memory store (the bug that `wire/store-config`'s hardcoded id
   caused before consolidation)."
  (:require [seon.server.client :as client]
            [seon.server.registry :as registry]
            [seon.server.wire :as wire]
            [seon.server.broadcast :as bcast])
  (:import [java.io File]
           [java.nio.channels ServerSocketChannel]))

(set! *warn-on-reflection* true)

(def ^:dynamic *ctx* nil)

(defn unique-sock [prefix]
  (str "/tmp/seon-poc-test-" prefix "-" (System/nanoTime) ".sock"))

(defn- writer-ready? [path]
  (try (with-open [ch (client/connect path)] (.isConnected ch))
       (catch Throwable _ false)))

(defn- wait-for-socket! [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (writer-ready? path) :ok
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "writer never came up" {:path path}))
        :else (do (Thread/sleep 200) (recur))))))

(defn spawn-writer!
  "Start the wire-server IN-PROCESS on a fresh, isolated :memory store
   and unique UDS sockets. Returns a ctx map for `teardown-writer!`."
  []
  (let [req-sock (unique-sock "req")
        pub-sock (unique-sock "pub")
        db-name  (keyword "test" (str (gensym "wire")))
        ;; The registry is the ONE open mechanism (same path -main uses):
        ;; ensure-db! creates the :memory store, connects, and fires the
        ;; on-ensure-db hooks (::raw-broadcast + base schema).
        entry    (registry/ensure-db!
                  {:seon.server.registry/db-name db-name
                   :seon.server.registry/backend :memory})
        conn     (:seon.server.registry/conn entry)
        pub-srv  (bcast/start-pub-server! pub-sock)
        req-srv  (#'wire/start-req-server! conn req-sock)]
    (wait-for-socket! req-sock 60000)
    (wait-for-socket! pub-sock 60000)
    {:req-sock req-sock :pub-sock pub-sock :db-name db-name
     :conn conn :req-srv req-srv :pub-srv pub-srv}))

(defn teardown-writer! [{:keys [db-name req-srv pub-srv req-sock pub-sock]}]
  (try (.close ^ServerSocketChannel req-srv) (catch Throwable _))
  (try (.close ^ServerSocketChannel pub-srv) (catch Throwable _))
  ;; releases the conn AND drops the registry entry (no cross-test leak).
  (try (registry/remove-db! {:seon.server.registry/db-name db-name})
       (catch Throwable _))
  (try (.delete (File. ^String req-sock)) (catch Throwable _))
  (try (.delete (File. ^String pub-sock)) (catch Throwable _)))

(defn with-fresh-writer
  "use-fixtures :each helper. Binds *ctx* for the test body."
  [tfn]
  (let [ctx (spawn-writer!)]
    (try (binding [*ctx* ctx] (tfn))
         (finally (teardown-writer! ctx)))))

(defn request
  "Build a current wire request, minting each logical transact identity."
  [op extra]
  (cond-> (assoc extra :seon.store.wire/op op)
    (and (= "transact" op)
         (not (contains? extra :seon.store.wire/id)))
    (assoc :seon.store.wire/id (str (random-uuid)))))

(defn req!
  "Send one request envelope over the ctx's req socket, return the response.
   `op` is the op string; `extra` is a map of `:seon.store.wire/*` keys."
  [op extra]
  (with-open [ch (client/connect (:req-sock *ctx*))]
    (client/call! ch (request op extra))))
