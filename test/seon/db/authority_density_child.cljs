(ns seon.db.authority-density-child
  "One real Bun client used only by the authority retained-state proof."
  (:require [seon.db :as db]))

(def query-form
  '[:find ?left
    :where
    [?left :seon.authority-density/group "shared"]
    [?right :seon.authority-density/group "shared"]])

(defn- wait-until [target-ms]
  (js/Promise.
   (fn [resolve _]
     (js/setTimeout resolve (max 0 (- target-ms (.now js/Date)))))))

(defn- emit! [value]
  (println (pr-str value)))

(defn ^:async probe!
  "Open one canonical session and issue the same query twice."
  [socket-path database-name barrier-ms]
  (let [opened
        (await
         (db/open-session!
          {:seon.db/socket-path socket-path
           :seon.db/database-name database-name
           :seon.db/backend :memory}))
        coordinate (::db/coordinate opened)]
    (emit! {:seon.authority-density/phase :ready
            :seon.authority-density/pid (.-pid js/process)
            ::db/coordinate coordinate})
    (await (wait-until barrier-ms))
    (let [request {::db/coordinate coordinate
                   ::db/query query-form
                   ::db/args []
                   ::db/max-work 250000
                   ::db/max-results 1000
                   ::db/max-result-weight 1048576}
          first-result (await (db/query-with-evidence request))
          second-result (await (db/query-with-evidence request))]
      (emit! {:seon.authority-density/phase :complete
              :seon.authority-density/pid (.-pid js/process)
              :seon.authority-density/first first-result
              :seon.authority-density/second second-result})
      (db/close-session!)
      true)))

(defn -main
  "Run the retained-state probe from Shadow's command-line entrypoint."
  [& [socket-path database-name barrier-ms]]
  (-> (probe! socket-path database-name (js/parseInt barrier-ms 10))
      (.then (fn [_] (.exit js/process 0)))
      (.catch
       (fn [error]
         (emit! {:seon.authority-density/phase :failed
                 :seon.authority-density/pid (.-pid js/process)
                 :seon.error/message (or (.-message error) (str error))})
         (.exit js/process 1)))))
