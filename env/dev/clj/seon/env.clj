(ns seon.env
  (:require
   [taoensso.timbre :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[seon starting using the development profile]=-"))
   :start      (fn []
                 (log/info "\n-=[seon started successfully using the development profile]=-"))
   :stop       (fn []
                 (log/info "\n-=[seon has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :dev}})
