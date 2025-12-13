(ns nrepl
  "nREPL server entry point for development.

  Starts an nREPL server on port 7888 with CIDER middleware,
  loads the user namespace, and waits for connections.

  Usage: clj -M:dev:nrepl

  Once connected, use (go) to start the Integrant system."
  (:require [nrepl.server :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]))

(def ^:private port 7888)

(defn -main
  "Start nREPL server and load user namespace."
  [& _args]
  ;; Start nREPL server
  (let [server (nrepl/start-server :port port
                                   :bind "127.0.0.1"
                                   :handler cider-nrepl-handler)]
    ;; Write .nrepl-port for tooling discovery
    (spit ".nrepl-port" (str port))

    (println "")
    (println "================================================================================")
    (println "nREPL server started on port" port)
    (println "================================================================================")
    (println "")
    (println "Connect with:")
    (println "  clj-nrepl-eval -p" port "\"(go)\"")
    (println "  # or connect your editor to localhost:" port)
    (println "")

    ;; Load user namespace so (go), (halt), (reset) are available
    (require 'user)

    ;; Block forever
    @(promise)))
