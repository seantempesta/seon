(ns ml-options.runner
  "Long-running server entry point.

  IMPORTANT: This namespace now delegates to ml-options.core for all
  system lifecycle management. This ensures unified state management
  between runner and REPL.

  The runner is now just a thin wrapper that calls core/start-app
  and core/stop-app.

  Usage:
    clj -M:dev:run       # Dev profile with nREPL
    clj -M:run           # Dev profile (default)
    clj -M:run prod      # Prod profile (no nREPL)"
  (:require
   [ml-options.core :as core])
  (:gen-class))

(defn -main
  "Main entry point for standalone system.
  Delegates to ml-options.core/-main for unified lifecycle management.

  Args:
    profile - Optional profile name (default: dev)
              Use 'prod' for production settings"
  [& args]
  (apply core/-main args))
