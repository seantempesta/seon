(ns seon.agent.ctx.transcript-test
  "Pure transcript formatting and database-value acquisition."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.agent.ctx.transcript :as transcript]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

(def ^:private database {:datahike/commit-id "transcript" :max-tx 7})

(def acquired-empty
  {:seon.agent/id "agent"
   :seon.agent/entity {:db/id 1 :seon.agent/id "agent"}
   :seon.render/node
   {:seon.agent.ctx.transcript/readline? false}
   :seon.config/repl-mode :batch
   :seon.derive/state :idle
   :seon.eval/ns 'my.agent.agent
   :seon.agent.ctx.transcript/turn-count 0
   :seon.agent.ctx.transcript/turns []
   :seon.agent.ctx.transcript/messages []
   :seon.agent.run/turn-count 0
   :seon.agent.run/form-count 0})

(deftest acquired-formatting-does-no-database-io
  (let [original-execute-many db/execute-many
        touched (atom false)]
    (try
      (set! db/execute-many
            (fn [& _]
              (reset! touched true)
              (throw (js/Error. "unexpected database read"))))
      (let [text (@#'transcript/format-transcript-block acquired-empty)]
        (is (str/includes? text "; seon · my.agent.agent · live REPL"))
        (is (false? @touched)))
      (finally
        (set! db/execute-many original-execute-many)))))

(deftest transcript-windows-rotate-in-complete-chunks
  (is (= 0 (transcript/turn-window-cutoff 49 50 25)))
  (is (= 25 (transcript/turn-window-cutoff 50 50 25)))
  (is (= 25 (transcript/turn-window-cutoff 74 50 25)))
  (is (= 50 (transcript/turn-window-cutoff 75 50 25))))

(deftest recent-html-window-bounds-message-only-history
  (let [events [{:seon.agent.ctx.transcript/at (js/Date. 100)}
                {:seon.agent.ctx.transcript/at (js/Date. 200)}
                {:seon.agent.ctx.transcript/at (js/Date. 300)}]]
    (is (= [200 300]
           (mapv #(.getTime ^js (:seon.agent.ctx.transcript/at %))
                 (transcript/recent-html-events [] 2 events))))))

(deftest current-database-value-is-acquired-once-for-both-stages
  (async done
    (let [requests (atom [])
          original-execute db/execute-many]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
                {::db/results
                 (mapv (fn [result]
                         {:seon.db.protocol/success? true
                          :seon.db.protocol/result result})
                       (if (= 1 (count @requests))
                         [{} 0 [] [] []]
                         [[]]))})))
      (-> (transcript/transcript-block
            {:seon.agent/id "agent" ::db/db database} nil)
          (.then (fn [_]
                   (is (every? #(identical? database (::db/db %)) @requests))
                   (is (every?
                        (fn [request]
                          (every? #(identical? database
                                               (::db/db %))
                                  (::db/members request)))
                        @requests)
                       "every grouped member names its database source")
                   (is (= ["agent"]
                          (-> @requests first ::db/members first
                              ::protocol/arguments))
                       "the database value is not a Datalog :in argument")))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (set! db/execute-many original-execute)
                      (done)))))))

(deftest host-telemetry-remains-bounded
  (is (str/starts-with? (transcript/host-telemetry) "; host · load ")))
