(ns seon.render.chat-test
  "Tests for seon.render.chat — the consumer bubble surface
   (live-tiles PRD 2026-06-11, U2):

     • message-kind — from-kind label classification
     • bubble — human right/amber, agent markdown-rendered, peer
       inline/dimmer/smaller labeled `agent-<id>`
     • bubble-stream — ordered column, empty-state invitation
     • conversation — DERIVED from the real message log (from = me OR
       to ∋ me) on a fresh seeded conn; nothing stored per-view

   Fresh isolated :memory conn per integration test — NEVER the live
   pod conn. Root-conn `set!` swap (not `binding`) per the async-IIFE
   binding-pop gotcha documented in seon.agent.message-test."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.render.chat :as chat]
    [seon.render.live-tile :as tile]))

;; ============================================================
;; message-kind — label classification.
;; ============================================================

(deftest message-kind-classifies-from-kind-labels
  (is (= ::chat/human (chat/message-kind "user")))
  (is (= ::chat/agent (chat/message-kind "assistant")))
  (is (= ::chat/peer  (chat/message-kind "agent-b2-000001"))
      "another agent's label is a peer")
  (is (= ::chat/peer  (chat/message-kind "→ agent-b2-000001"))
      "an OUTGOING peer send is also peer traffic — direction lives in the label")
  (is (= ::chat/peer  (chat/message-kind "unknown"))
      "unknown senders render subordinate, never as the human"))

;; ============================================================
;; bubble — one hiccup card per kind.
;; ============================================================

(defn- hiccup-strings [h] (filter string? (flatten h)))

(defn- hiccup-classes [h] (->> (flatten h) (filter map?) (keep :class)))

(def ^:private at (js/Date. 2026 5 11 14 30 0))

(deftest bubble-human-is-right-aligned-amber
  (let [h (chat/bubble {::chat/at at ::chat/kind ::chat/human
                        ::chat/label "user" ::chat/content "hello there"})]
    (is (tile/valid-hiccup? h))
    (is (some #(re-find #"justify-end" %) (hiccup-classes h))
        "the human's words sit on the right")
    (is (some #(re-find #"amber" %) (hiccup-classes h)))
    (is (some #(= "hello there" %) (hiccup-strings h)))
    (is (some #(re-find #"14:30" %) (hiccup-strings h)) "timestamp renders")))

(deftest bubble-agent-renders-markdown-with-degradation
  (let [content "**bold** reply"
        h (chat/bubble {::chat/at at ::chat/kind ::chat/agent
                        ::chat/label "assistant" ::chat/content content})
        md-attrs (->> (flatten h) (filter map?)
                      (some #(when (contains? % :data-markdown) %)))]
    (is (tile/valid-hiccup? h))
    (is (some #(re-find #"justify-start" %) (hiccup-classes h)))
    (is (= content (:data-markdown md-attrs))
        "agent bubbles carry the raw markdown for the client-side pass")
    (is (some #(= content %) (hiccup-strings h))
        "raw text child — the bubble degrades to plain text without JS")))

(deftest bubble-peer-is-inline-dimmer-smaller-labeled
  (let [h (chat/bubble {::chat/at at ::chat/kind ::chat/peer
                        ::chat/label "agent-b2-000001"
                        ::chat/content "peer ping"})]
    (is (tile/valid-hiccup? h))
    (is (some #(= "agent-b2-000001" %) (hiccup-strings h))
        "labeled with the peer's id")
    (is (some #(re-find #"text-xs" %) (hiccup-classes h)) "smaller")
    (is (some #(re-find #"text-text-400" %) (hiccup-classes h)) "dimmer")
    (is (not-any? #(re-find #"justify-end" %) (hiccup-classes h))
        "never on the human's side")))

;; ============================================================
;; bubble-stream — the column.
;; ============================================================

(deftest bubble-stream-orders-and-validates
  (let [{:seon.render/keys [hiccup]}
        (chat/bubble-stream
          {::chat/messages
           [{::chat/at at ::chat/kind ::chat/human
             ::chat/label "user" ::chat/content "first"}
            {::chat/at at ::chat/kind ::chat/agent
             ::chat/label "assistant" ::chat/content "second"}]})
        s (str/join " " (hiccup-strings hiccup))]
    (is (tile/valid-hiccup? hiccup))
    (is (< (str/index-of s "first") (str/index-of s "second"))
        "stream preserves conversation order")))

(deftest bubble-stream-empty-renders-invitation
  (let [{:seon.render/keys [hiccup]}
        (chat/bubble-stream {::chat/messages []})]
    (is (some #(re-find #"no messages yet" %) (hiccup-strings hiccup))
        "an invitation, never a blank pane")))

;; ============================================================
;; conversation — derived from the real message log.
;; ============================================================

(def ^:private a-id "chattest-agent-a")
(def ^:private b-id "chattest-agent-b")

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + the
   user entity + agents A and B (mirrors seon.agent.message-test)."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         client/agent-bootstrap-attrs)
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data [{:seon.user/id "user"}
                                           {:seon.agent/id a-id
                                            :seon.agent/state :idle}
                                           {:seon.agent/id b-id
                                            :seon.agent/state :idle}]})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Open a fresh seeded conn, `set!` it as the ROOT `db/*conn*` for the
   duration of `body` (conn → Promise), restore the prior root after
   (`binding` pops at the first microtask inside ^:async fns — see
   seon.agent.message-test)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(deftest conversation-derives-kinds-from-the-message-log
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message!
                  {:seon.agent.message/from    agent/user-ref
                   :seon.agent.message/to      [:seon.agent/id a-id]
                   :seon.agent.message/content "hello A"})
                (.then (fn [_]
                         (agent/message!
                           {:seon.agent.message/from    [:seon.agent/id a-id]
                            :seon.agent.message/to      [:seon.user/id "user"]
                            :seon.agent.message/content "hi human"})))
                (.then (fn [_]
                         (agent/message!
                           {:seon.agent.message/from    [:seon.agent/id b-id]
                            :seon.agent.message/to      [:seon.agent/id a-id]
                            :seon.agent.message/content "peer ping"})))
                (.then (fn [_]
                         ;; outgoing peer send — from me, to a peer
                         (agent/message!
                           {:seon.agent.message/from    [:seon.agent/id a-id]
                            :seon.agent.message/to      [:seon.agent/id b-id]
                            :seon.agent.message/content "pong back at you"})))
                (.then (fn [_]
                         ;; transcript SELF-message — raw LLM output, from
                         ;; me to me (what the loop logs per turn)
                         (agent/message!
                           {:seon.agent.message/from    [:seon.agent/id a-id]
                            :seon.agent.message/to      [:seon.agent/id a-id]
                            :seon.agent.message/content ";; thinking out loud\n(seon.agent/reply! {…})"})))
                (.then
                  (fn [_]
                    (let [{::chat/keys [messages]}
                          (chat/conversation {:seon.agent/id a-id
                                              :seon.db/db @conn})
                          by-content (into {} (map (juxt ::chat/content
                                                         identity))
                                           messages)]
                      (is (= 4 (count messages))
                          (str "from = me OR to ∋ me, MINUS the self-"
                               "narration row — transcript noise never"
                               " becomes a bubble"))
                      (is (nil? (by-content ";; thinking out loud\n(seon.agent/reply! {…})"))
                          "the agent→self transcript row is excluded")
                      (is (= ::chat/human
                             (::chat/kind (by-content "hello A"))))
                      (is (= ::chat/agent
                             (::chat/kind (by-content "hi human"))))
                      (testing "the incoming peer message is a labeled ::peer"
                        (let [m (by-content "peer ping")]
                          (is (= ::chat/peer (::chat/kind m)))
                          (is (= (str "agent-" b-id) (::chat/label m)))))
                      (testing "the OUTGOING peer message is a direction-labeled ::peer"
                        (let [m (by-content "pong back at you")]
                          (is (= ::chat/peer (::chat/kind m)))
                          (is (= (str "→ agent-" b-id) (::chat/label m)))))
                      (is (every? #(instance? js/Date (::chat/at %)) messages)
                          "every bubble carries its timestamp")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; last-reply — the default root tile's derived read (live-tiles U3).
;; ============================================================

(deftest last-reply-is-the-newest-assistant-message
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message!
                  {:seon.agent.message/from    agent/user-ref
                   :seon.agent.message/to      [:seon.agent/id a-id]
                   :seon.agent.message/content "hello A"})
                (.then (fn [_]
                         (agent/message!
                           {:seon.agent.message/from    [:seon.agent/id a-id]
                            :seon.agent.message/to      [:seon.user/id "user"]
                            :seon.agent.message/content "on it — 3 workouts logged"})))
                (.then (fn [_]
                         (agent/message!
                           {:seon.agent.message/from    [:seon.agent/id b-id]
                            :seon.agent.message/to      [:seon.agent/id a-id]
                            :seon.agent.message/content "peer ping"})))
                (.then (fn [_]
                         ;; the per-turn transcript SELF-message lands
                         ;; AFTER the reply — the kXQ root-tile bug
                         ;; (2026-06-11) was this row, raw eval source,
                         ;; rendering as the "last reply"
                         (agent/message!
                           {:seon.agent.message/from    [:seon.agent/id a-id]
                            :seon.agent.message/to      [:seon.agent/id a-id]
                            :seon.agent.message/content ";; The user asked …\n(seon.agent/reply! {…})"})))
                (.then
                  (fn [_]
                    (let [{::chat/keys [last-reply]}
                          (chat/last-reply {:seon.agent/id a-id
                                            :seon.db/db @conn})]
                      (is (= "on it — 3 workouts logged" last-reply)
                          (str "the newest agent→HUMAN message — the"
                               " human's messages, peer traffic, and"
                               " transcript self-narration never count"
                               " as MY reply"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest last-reply-absent-when-never-replied
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message!
                  {:seon.agent.message/from    agent/user-ref
                   :seon.agent.message/to      [:seon.agent/id a-id]
                   :seon.agent.message/content "anyone home?"})
                (.then
                  (fn [_]
                    (is (= {} (chat/last-reply {:seon.agent/id a-id
                                                :seon.db/db @conn}))
                        "optional = absent — no key when the agent has never replied"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest conversation-limit-bounds-the-tail
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (js/Promise.all
                  (clj->js
                    (mapv (fn [i]
                            (agent/message!
                              {:seon.agent.message/from    agent/user-ref
                               :seon.agent.message/to      [:seon.agent/id a-id]
                               :seon.agent.message/content (str "msg " i)}))
                          (range 5))))
                (.then
                  (fn [_]
                    (let [{::chat/keys [messages]}
                          (chat/conversation {:seon.agent/id a-id
                                              :seon.db/db @conn
                                              ::chat/limit 2})]
                      (is (= 2 (count messages))
                          "::limit takes the newest tail")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
