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
    [seon.render.live-tile :as tile]
    [seon.ui.html :as html]))

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

(deftest bubble-agent-renders-markdown-server-side
  (let [content (str "## status\n\n**bold** reply with `inline`\n\n"
                     "- item one\n- item two\n\n"
                     "1. first\n2. second\n\n"
                     "```clojure\n(+ 1 2)\n```\n\n"
                     "<script>alert(1)</script>")
        h (chat/bubble {::chat/at at ::chat/kind ::chat/agent
                        ::chat/label "assistant" ::chat/content content})
        s (html/->string h)]
    (is (tile/valid-hiccup? h)
        "md->hiccup output satisfies the strict authoring shape")
    (is (some #(re-find #"justify-start" %) (hiccup-classes h)))
    (is (re-find #"<strong[^>]*>bold</strong>" s)
        "**bold** converts to <strong> SERVER-SIDE — curl sees it")
    (is (re-find #"<h2" s) "headings render")
    (is (re-find #"<ul" s) "bullet lists render")
    (is (re-find #"<ol" s) "numbered lists render")
    (is (re-find #"<code" s) "inline code renders")
    (is (re-find #"<pre" s) "code fences render")
    (is (not (re-find #"<script" s))
        "raw HTML NEVER passes through — agent content is untrusted")
    (is (re-find #"&lt;script&gt;alert\(1\)&lt;/script&gt;" s)
        "the script tag degrades to escaped visible text")))

(deftest bubble-human-renders-markdown-too
  ;; Symmetry (a21): the human's words get the same structure.
  (let [h (chat/bubble {::chat/at at ::chat/kind ::chat/human
                        ::chat/label "user"
                        ::chat/content "please **check** the list"})
        s (html/->string h)]
    (is (tile/valid-hiccup? h))
    (is (re-find #"<strong[^>]*>check</strong>" s))
    (is (some #(re-find #"justify-end" %) (hiccup-classes h))
        "still the human's side")))

(deftest bubble-links-are-nofollow-and-scheme-guarded
  (let [safe   (html/->string
                 (chat/bubble {::chat/at at ::chat/kind ::chat/agent
                               ::chat/label "assistant"
                               ::chat/content "[docs](https://example.com/d)"}))
        unsafe (html/->string
                 (chat/bubble {::chat/at at ::chat/kind ::chat/agent
                               ::chat/label "assistant"
                               ::chat/content "[x](javascript:alert(1))"}))]
    (is (re-find #"<a [^>]*href=\"https://example.com/d\"" safe))
    (is (re-find #"rel=\"nofollow noopener\"" safe))
    (is (re-find #"target=\"_blank\"" safe))
    (is (not (re-find #"javascript:" unsafe))
        "javascript: hrefs never render — text-only degradation")))

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
                                           {:seon.agent/id a-id}
                                           {:seon.agent/id b-id}]})))
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

;; The EXTRACTION mechanism, not the literal text: bind the reply, the peer
;; ping, and the self-narration as named fixture data, then assert last-reply
;; picks the agent→HUMAN row and EXCLUDES the others — even though the peer
;; ping and the self-narration are both NEWER in the log. The reply string
;; itself is arbitrary fixture; the contract is the from=me ∧ to∋user filter.
(def ^:private the-reply "on it — 3 workouts logged")
(def ^:private peer-ping-content "peer ping")
(def ^:private self-narr ";; The user asked …\n(seon.agent/reply! {…})")

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
                            :seon.agent.message/content the-reply})))
                (.then (fn [_]
                         (agent/message!
                           {:seon.agent.message/from    [:seon.agent/id b-id]
                            :seon.agent.message/to      [:seon.agent/id a-id]
                            :seon.agent.message/content peer-ping-content})))
                (.then (fn [_]
                         ;; the per-turn transcript SELF-message lands
                         ;; AFTER the reply — the kXQ root-tile bug
                         ;; (2026-06-11) was this row, raw eval source,
                         ;; rendering as the "last reply"
                         (agent/message!
                           {:seon.agent.message/from    [:seon.agent/id a-id]
                            :seon.agent.message/to      [:seon.agent/id a-id]
                            :seon.agent.message/content self-narr})))
                (.then
                  (fn [_]
                    (let [{::chat/keys [last-reply]}
                          (chat/last-reply {:seon.agent/id a-id
                                            :seon.db/db @conn})]
                      ;; picks the agent→human row (the named fixture reply)…
                      (is (= the-reply last-reply)
                          "extracts the newest agent→HUMAN message as MY reply")
                      ;; …NOT the newer peer ping (to a peer, not the human)…
                      (is (not= peer-ping-content last-reply)
                          "peer traffic — newer in the log — is filtered out")
                      ;; …NOT the newest-of-all self-narration (from=to=me).
                      (is (not= self-narr last-reply)
                          "transcript self-narration — the NEWEST row — never counts as MY reply")))))))
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

;; ============================================================
;; ::system — turn-level provider failures become chat-visible
;; (agent-robustness unit, 2026-06-11: a transient provider `fetch
;; failed` ended the wake with NO user-visible notice).
;; ============================================================

(deftest provider-failure-renders-a-system-line
  (async done
    (-> (with-conn
          (fn [conn]
            (let [t0 (js/Date.)
                  t+ (fn [ms] (js/Date. (+ (.getTime t0) ms)))]
              (-> (agent/message!
                    {:seon.agent.message/from    agent/user-ref
                     :seon.agent.message/to      [:seon.agent/id a-id]
                     :seon.agent.message/content "are you there?"})
                  (.then
                    (fn [_]
                      ;; the turn log exactly as seon.agent writes it: turns
                      ;; record STATUS only (the error notice is synthesized
                      ;; downstream from the status — turns store no message
                      ;; of their own). Only the :error turn may surface; the
                      ;; :done turn never does. RAW d/transact! like the
                      ;; fixture itself — the fixture's 16-char agent ids
                      ;; predate the 14-char :seon.db/id gate, so
                      ;; db/transact!'s validation would reject any tx-map
                      ;; carrying :seon.agent/id.
                      ;; Run model: turns are STANDALONE entities that point
                      ;; UP to a run (`:seon.agent.turn/run`), the run UP to the
                      ;; agent (`:seon.agent.run/agent`). The `run` tempid links
                      ;; them in one tx.
                      (d/transact!
                        db/*conn*
                        {:tx-data
                         [{:db/id "run"
                           :seon.agent.run/id "RUNchattest001"
                           :seon.agent.run/agent [:seon.agent/id a-id]
                           :seon.agent.run/started-at t0
                           :seon.agent.run/trigger :message
                           :seon.agent.run/status :open
                           :seon.agent.run/turn-limit 20
                           :seon.agent.run/deadline (t+ 9999999)}
                          {:seon.agent.turn/id "TRNchatterr001"
                           :seon.agent.turn/at (t+ 100)
                           :seon.agent.turn/status :error
                           :seon.agent.turn/run "run"}
                          {:seon.agent.turn/id "TRNchattdone01"
                           :seon.agent.turn/at (t+ 200)
                           :seon.agent.turn/status :done
                           :seon.agent.turn/run "run"}]})))
                  (.then
                    (fn [_]
                      (let [{::chat/keys [messages]}
                            (chat/conversation {:seon.agent/id a-id
                                                :seon.db/db @conn})
                            sys (filterv #(= ::chat/system (::chat/kind %))
                                         messages)]
                        (is (= 1 (count sys))
                            (str "exactly the :error turn surfaces — the"
                                 " :done turn's self-message never does"))
                        (let [{::chat/keys [label content at]} (first sys)]
                          (is (= "system" label))
                          (is (str/includes? content
                                             "resume on your next message")
                              "the synthesized notice tells the human how to wake the agent")
                          (is (instance? js/Date at)))
                        (testing "merged into the stream in time order"
                          (is (= [::chat/human ::chat/system]
                                 (mapv ::chat/kind messages))
                              "user msg first, failure line after")))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest bubble-system-is-centered-and-amber-edged
  (let [h (chat/bubble {::chat/at at ::chat/kind ::chat/system
                        ::chat/label "system"
                        ::chat/content "agent's turn failed: provider unreachable — it will resume on your next message"})]
    (is (tile/valid-hiccup? h))
    (is (some #(re-find #"justify-center" %) (hiccup-classes h))
        "system lines sit centered — neither voice in the conversation")
    (is (some #(re-find #"amber" %) (hiccup-classes h)))
    (is (some #(re-find #"resume on your next message" %)
              (hiccup-strings h)))
    (is (some #(= "system" %) (hiccup-strings h)) "labeled")))
