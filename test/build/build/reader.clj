(ns build.reader
  "A build that reads with clojure.tools.reader."
  (:require [clojure.tools.reader :as r]))

(defn -main [& _] (println (r/read-string "[1 2]")))
