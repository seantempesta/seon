(ns seon.env
  (:require [taoensso.timbre :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[seon starting]=-"))
   :start      (fn []
                 (log/info "\n-=[seon started successfully]=-"))
   :stop       (fn []
                 (log/info "\n-=[seon has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :prod}})
