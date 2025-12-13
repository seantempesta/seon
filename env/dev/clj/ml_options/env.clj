(ns ml-options.env
  (:require
   [clojure.tools.logging :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[ml-options starting using the development profile]=-"))
   :start      (fn []
                 (log/info "\n-=[ml-options started successfully using the development profile]=-"))
   :stop       (fn []
                 (log/info "\n-=[ml-options has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :dev}})
