(ns ml-options.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[ml-options starting]=-"))
   :start      (fn []
                 (log/info "\n-=[ml-options started successfully]=-"))
   :stop       (fn []
                 (log/info "\n-=[ml-options has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :prod}})
