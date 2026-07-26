(ns seon.dev.apply-release-config
  "JVM entry point for the closed-database release config transaction."
  (:require [clojure.edn :as edn]
            [seon.agent.ctx]
            [seon.agent.home]
            [seon.config.resolve]
            [seon.db.program :as program]))

(defn -main
  "Read one release-config request from stdin and print its result."
  [& _arguments]
  (prn (program/apply-release-config! (edn/read-string (slurp *in*)))))
