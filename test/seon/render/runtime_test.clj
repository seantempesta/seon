(ns seon.render.runtime-test
  "The runtime HTML pair over the canonical database population."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.render.hiccup :as hiccup]
            [seon.render.transcript :as transcript]
            [seon.repl :as repl]
            [seon.test-support :as support]))

(defn- text-content [node]
  (cond
    (string? node) node
    (vector? node) (apply str (map text-content (drop (if (map? (second node)) 2 1) node)))
    (sequential? node) (apply str (map text-content node))
    :else ""))

(deftest runtime-html-is-human-readable-and-ai-bytes-stay-unchanged
  (support/with-database
    (fn [connection]
      (let [reply-digest (blob/put! connection "Older reply\nA separately stored continuation.")
            written
            (db/transact!
             connection
             [{:db/id "agent" :seon.agent/id "runtime-reader"
               :seon.agent/runtime
               {:seon.runtime/agent "agent"
                :seon.runtime/trigger "message"
                :seon.runtime/turns ["older" "newer"]
                :seon.runtime/listens [{:seon.listen/attribute :seon.message/inbox}]}}
              {:seon.agent/id "root"}
              {:db/id "message" :seon.message/id "private-message-id"
               :seon.message/from [:seon.agent/id "root"]
               :seon.message/to "agent"
               :seon.message/content "Which customer has the largest total?\nShow the calculation."}
              {:db/id "older" :seon.turn/id "private-older-id"
               :seon.turn/agent "agent" :seon.turn/opened-tx "datomic.tx"
               :seon.turn/closed-tx "datomic.tx" :seon.turn/reply-blob reply-digest}
              {:db/id "newer" :seon.turn/id "private-newer-id"
               :seon.turn/agent "agent" :seon.turn/opened-tx "datomic.tx"
               :seon.turn/trigger "message" :seon.turn/reply "Newest reply\nFull reply remains stored."}
              {:seon.cluster.eval/id "private-eval-id"
               :seon.cluster.eval/at (java.util.Date. 0)
               :seon.cluster.eval/run "newer" :seon.cluster.eval/ordinal 0
               :seon.cluster.eval/source "(+ 1 2)" :seon.eval/shown "3"}])
            _ (is (:db-after written) (pr-str written))
            opened (db/transact! connection
                                 [[:db/add [:seon.turn/id "private-newer-id"]
                                   :seon.turn/opened-tx "datomic.tx"]])
            _ (is (:db-after opened) (pr-str opened))
            database @connection
            unit {:seon.db/db database :seon.db/connection connection :seon.agent/id "runtime-reader"}
            expected-ai
            (str ";; I should follow my runtime's owner ref before pulling its turns, trigger, and listens.\n"
                 (repl/source-text
                  '(seon.db/pull
                    (quote [{:seon.agent/runtime
                             [{:seon.runtime/turns
                               [:seon.turn/id {:seon.turn/opened-tx [:db/txInstant]}
                                {:seon.turn/closed-tx [:db/txInstant]}]}
                              {:seon.runtime/trigger
                               [:seon.message/id :seon.message/content
                                {:seon.message/from [:seon.agent/id]}]}
                              {:seon.runtime/listens
                               [:seon.listen/attribute :seon.listen/entity :seon.listen/value]}]}])
                    [:seon.agent/id "runtime-reader"])))
            ai-before (transcript/render-runtime-ai unit)
            rendered (transcript/render-runtime-html unit)
            html (hiccup/->string rendered)
            text (text-content rendered)]
        (is (= expected-ai ai-before (transcript/render-runtime-ai unit)))
        (is (= :seon.db/not-found
               (:seon.error/kind (transcript/render-runtime-html
                                 (assoc unit :seon.agent/id "absent-runtime-owner")))))
        (is (= "1 min" (#'transcript/runtime-duration (java.util.Date. 0) (java.util.Date. 65000))))
        (is (= "Duration unavailable" (#'transcript/runtime-duration (java.util.Date. 100) (java.util.Date. 0))))
        (is (hiccup/hiccup? rendered))
        (is (str/includes? text "Turn open since "))
        (is (str/includes? html "toLocaleTimeString"))
        (is (str/includes? html "seon-message-entry") "Trigger goes through the message pair.")
        (is (str/includes? html "entity=%5B%3Aseon.message%2Fid"))
        (is (str/includes? text "Which customer has the largest total?"))
        (is (str/includes? text "Listening::seon.message/inbox"))
        (is (str/includes? text "Turns (2)"))
        (is (< (.indexOf text "Newest reply") (.indexOf text "Older reply")))
        (is (str/includes? text "1Newest reply"))
        (is (not (str/includes? text "Full reply remains stored.")))
        (is (not (str/includes? text "A separately stored continuation.")))
        (doseq [forbidden ["#inst" ":db/id" "private-older-id" "private-newer-id" "private-eval-id"]]
          (is (not (str/includes? html forbidden)) forbidden))
        (let [closed (db/transact! connection
                                  [[:db/add [:seon.turn/id "private-newer-id"]
                                    :seon.turn/closed-tx "datomic.tx"]])
              _ (is (:db-after closed) (pr-str closed))
              idle (text-content (transcript/render-runtime-html (assoc unit :seon.db/db @connection)))]
          (is (str/includes? idle "Idle since "))
          (is (str/includes? idle " ms")))))))
