(ns seon.render.live-tile-test
  "Tests for seon.render.live-tile — the tile key, resolution
   provenance, the welcome, and the legible-error contract — plus the
   render-agent-tile integration (live-tiles PRD 2026-06-11, U1):

     • greeting — time-of-day boundaries
     • wired-content — ::content wins (legacy :seon.render/html tile
       fallback DELETED, PRD §8.1) → welcome default; pr-str-encoded
       values decode
     • welcome — .seon-tile compact+expanded blocks, date, purpose,
       panel line, :seon.render/ai twin
     • error-response — fallback card + envelope + twin (never vanish)
     • render-agent-tile — unwired→welcome, literal hiccup via the new
       key, throwing fn → error-response, ::content EDN roundtrip

   Fresh isolated conn per integration test (client/open-agent-conn!)
   — NEVER the live pod conn."
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [deftest is testing async]]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.render :as render]
    [seon.render.live-tile :as tile]
    [seon.repl.internal :as repl.internal]
    [seon.schema :as schema]))

;; ============================================================
;; greeting — pure time-of-day boundaries.
;; ============================================================

(deftest greeting-time-of-day-boundaries
  (is (= "Good morning"   (tile/greeting 5)))
  (is (= "Good morning"   (tile/greeting 11)))
  (is (= "Good afternoon" (tile/greeting 12)))
  (is (= "Good afternoon" (tile/greeting 16)))
  (is (= "Good evening"   (tile/greeting 17)))
  (is (= "Good evening"   (tile/greeting 21)))
  (is (= "Good night"     (tile/greeting 22)))
  (is (= "Good night"     (tile/greeting 4))))

;; ============================================================
;; wired-content — resolution order + provenance.
;; Values arrive pr-str-encoded from the mixed-:or bridge.
;; ============================================================

(deftest wired-content-new-key-wins
  (let [{:seon.render.live-tile/keys [source value]}
        (tile/wired-content
          {:seon.render/entity
           {:seon.agent/id "wired-22060001"
            :seon.render.live-tile/content (pr-str 'my.ns/tile-fn)
            :seon.render/html              (pr-str 'other.ns/old-fn)}})]
    (is (= :seon.render.live-tile/content source))
    (is (= 'my.ns/tile-fn value) "new key wins AND decodes")))

(deftest wired-content-ignores-legacy-html-slot
  ;; Render sweep 2026-06-11 (PRD §8.1, no legacy): :seon.render/html
  ;; is the generic entity-card slot ONLY — never the tile.
  (let [{:seon.render.live-tile/keys [source value]}
        (tile/wired-content
          {:seon.render/entity
           {:seon.agent/id    "wired-22060002"
            :seon.render/html (pr-str [:h1 "legacy"])}})]
    (is (= :seon.render.live-tile/welcome source))
    (is (= tile/welcome-sym value)
        "the card slot never wires the tile — welcome renders")))

(deftest wired-content-unwired-defaults-to-welcome
  (let [{:seon.render.live-tile/keys [source value]}
        (tile/wired-content
          {:seon.render/entity {:seon.agent/id "wired-22060003"}})]
    (is (= :seon.render.live-tile/welcome source))
    (is (= tile/welcome-sym value))))

;; ============================================================
;; welcome — time-aware, purpose-aware, twin-carrying, tagged blocks.
;; ============================================================

(defn- hiccup-strings
  "Every string anywhere in a hiccup tree, one flat seq."
  [h]
  (filter string? (flatten h)))

(deftest welcome-emits-tagged-blocks-and-twin
  (let [{:seon.render/keys [hiccup ai]}
        (tile/welcome {:seon.db/db nil :seon.agent/id "wlcm-2206110001"})
        classes (->> (flatten hiccup) (filter map?) (keep :class))]
    (is (tile/valid-hiccup? hiccup))
    (is (= "seon-tile" (:class (second hiccup)))
        "wrapped in the .seon-tile container")
    (is (some #(re-find #"seon-tile-compact" %) classes))
    (is (some #(re-find #"seon-tile-expanded" %) classes)
        "ONE render carries BOTH zoom blocks")
    (testing "time-aware greeting matches the wall clock"
      (let [expected (tile/greeting (.getHours (js/Date.)))]
        (is (some #(re-find (re-pattern expected) %) (hiccup-strings hiccup)))
        (is (re-find (re-pattern expected) ai))))
    (testing "today's date renders"
      (let [date-str (.toLocaleDateString (js/Date.) "en-US"
                                          #js {:weekday "long"
                                               :month   "long"
                                               :day     "numeric"})]
        (is (some #(re-find (re-pattern date-str) %) (hiccup-strings hiccup)))))
    (testing "the double-duty panel line is present in BOTH twins"
      (is (some #(= tile/panel-line %) (hiccup-strings hiccup)))
      (is (re-find #"update this panel" ai)))))

(deftest welcome-uses-purpose-when-present
  (let [{:seon.render/keys [hiccup ai]}
        (tile/welcome {:seon.db/db nil
                       :seon.agent/id "wlcm-2206110002"
                       :seon.render/entity
                       {:seon.agent/id      "wlcm-2206110002"
                        :seon.agent/purpose "track your workouts"}})]
    (is (some #(re-find #"track your workouts" %) (hiccup-strings hiccup)))
    (is (re-find #"track your workouts" ai)
        "the twin tells the agent its purpose is on display")))

(deftest welcome-generic-without-purpose-or-name
  (let [{:seon.render/keys [hiccup]}
        (tile/welcome {:seon.db/db nil :seon.agent/id "wlcm-2206110003"})]
    (is (some #(re-find #"finding my purpose" %) (hiccup-strings hiccup))
        "gracefully generic — no purpose, no name, still elegant")))

(deftest welcome-compact-shows-purpose-id-and-truthful-twin
  (let [{:seon.render/keys [hiccup ai]}
        (tile/welcome {:seon.db/db nil
                       :seon.agent/id "wlcm-2206110004"
                       :seon.render/entity
                       {:seon.agent/id      "wlcm-2206110004"
                        :seon.agent/purpose "track your workouts"}})]
    (is (some #(= "wlcm-2206110004" %) (hiccup-strings hiccup))
        "the agent's id renders on the default tile (live-tiles U3)")
    (testing "the twin is TRUTHFUL — every minted agent IS wired (to welcome)"
      (is (not (re-find #"haven't wired" ai))
          "the old wording lied: creation wires every agent to welcome")
      (is (re-find #"substrate default" ai))
      (is (re-find #":seon.render.live-tile/content" ai)
          "the twin always says HOW to repoint the tile"))))

;; ============================================================
;; error-response — a broken tile is LEGIBLE on both sides.
;; ============================================================

(deftest error-response-never-vanishes
  (let [env {:seon.error/message "boom from tile fn"}
        {:seon.render/keys [hiccup ai error]}
        (tile/error-response
          {:seon.db/error                 env
           :seon.render.live-tile/content 'my.ns/broken-tile})]
    (is (tile/valid-hiccup? hiccup) "human sees a card, not a blank")
    (is (some #(re-find #"tile error" %) (hiccup-strings hiccup)))
    (is (some #(re-find #"the agent has been shown the failure" %)
              (hiccup-strings hiccup)))
    (is (= env error) "response carries the :seon.error/* envelope")
    (is (re-find #"my\.ns/broken-tile" ai)
        "twin names the wired value that broke")
    (is (re-find #"boom from tile fn" ai)
        "twin carries the exception's message")))

;; ============================================================
;; Pure-data platform law (sci-not-available regression, 2026-06-11):
;; registered schema forms must survive the form round-trip —
;; pr-str → read-string → m/schema — WITHOUT evaluation. The pod has
;; no sci; a fn object in a registered form serialized as a symbol or
;; #object[...] killed boot + the context-test family. Guards the
;; exact shape of that bug for every key this fix owns.
;; ============================================================

(deftest registered-forms-are-pure-data
  (doseq [k [:seon.render.live-tile/hiccup
             :seon.render.live-tile/content
             :seon.render.live-tile/wired-response
             :seon.render.live-tile/error-request
             :seon.render/html
             :seon.render/html-response
             :seon.render/ai-response
             :seon.ctx/render-namespace-response
             :seon.db/db-val
             :seon.db/listen-request]]
    (testing (str k)
      (let [form (schema/schema-definition k)
            s    (pr-str form)]
        (is (not (re-find #"#object" s))
            "no fn object in the registered form")
        (is (some? (m/schema (reader/read-string s)))
            "re-read form compiles WITHOUT sci")))))

(deftest content-shape-semantics
  (let [valid? #(m/validate
                  (m/schema :seon.render.live-tile/content)
                  %)]
    (is (valid? 'my.ns/tile-fn) "qualified fn symbol")
    (is (valid? [:h1 {:class "x"} "hi" [:span "nested"]]) "literal hiccup")
    (is (not (valid? [])) "empty vector is not hiccup")
    (is (not (valid? '(:h1 "x"))) "a list is not hiccup")
    (is (not (valid? "string")) "bare string rejected")))

;; ============================================================
;; render-agent-tile integration — fresh conn, never the live pod.
;; ============================================================

(defn throwing-tile
  "Test tile renderer that always throws — the error-envelope target."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_input]
  (throw (ex-info "deliberate tile failure" {:seon.render.live-tile/test true})))

(defn twin-tile
  "Test tile renderer returning BOTH twins — the twin-contract target."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_input]
  {:seon.render/hiccup [:div.seon-tile [:span "3 workouts this week"]]
   :seon.render/ai     "3 workouts this week: Mon, Wed, Fri — trending up."})

(defn- with-agent-conn
  "Open a fresh conn, seed the :seon.agent kind schema entity + one
   agent row, call `body` with the conn HELD via set! for the WHOLE
   promise chain (restored in .finally) — `binding` unwinds before an
   async body's awaits run (the *conn*-unbound trap chat_test's
   with-conn already dodges). Returns a Promise."
  [agent-id body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (db/transact!
                       {:seon.db/tx-data
                        (into (vec (schema/entity-schema-tx-data :seon.agent))
                              [{:seon.agent/id    agent-id
                                :seon.agent/state :idle}])})
                     (.then (fn [_] (body conn)))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(deftest render-agent-tile-unwired-renders-welcome
  (async done
    (-> (with-agent-conn "tilewlc-000001"
          (fn [conn]
            (let [{:seon.render/keys [hiccup ai]}
                  (render/render-agent-tile {:seon.db/db @conn
                                             :seon.agent/id "tilewlc-000001"})]
              (is (= "seon-tile" (:class (second hiccup)))
                  "unwired agent gets the substrate welcome")
              (is (re-find #"Good (morning|afternoon|evening|night)" (str ai))
                  "welcome's twin rides the response"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest welcome-compact-shows-the-last-reply
  (async done
    (-> (with-agent-conn "wlcmrpl-000001"
          (fn [conn]
            (-> (db/transact! {:seon.db/tx-data [{:seon.user/id "user"}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (agent/message!
                             {:seon.agent.message/from    agent/user-ref
                              :seon.agent.message/to      [:seon.agent/id "wlcmrpl-000001"]
                              :seon.agent.message/content "how's it going?"}))))
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (agent/message!
                             {:seon.agent.message/from    [:seon.agent/id "wlcmrpl-000001"]
                              :seon.agent.message/to      [:seon.user/id "user"]
                              :seon.agent.message/content "I found 3 workouts this week"}))))
                (.then (fn [_]
                         ;; the per-turn transcript SELF-message (from =
                         ;; to = the agent, raw eval source) lands AFTER
                         ;; the reply — the kXQ root-tile bug 2026-06-11:
                         ;; this row rendered as the "last reply"
                         (binding [db/*conn* conn]
                           (agent/message!
                             {:seon.agent.message/from    [:seon.agent/id "wlcmrpl-000001"]
                              :seon.agent.message/to      [:seon.agent/id "wlcmrpl-000001"]
                              :seon.agent.message/content ";; The user asked …\n(seon.agent/reply! {…})"}))))
                (.then
                  (fn [_]
                    (binding [db/*conn* conn]
                      (let [{:seon.render/keys [hiccup ai]}
                            (tile/welcome
                              {:seon.db/db @conn
                               :seon.agent/id "wlcmrpl-000001"
                               :seon.render/entity {:seon.agent/id "wlcmrpl-000001"}})]
                        (is (some #(= "I found 3 workouts this week" %)
                                  (hiccup-strings hiccup))
                            "the last REPLY renders as readable text (not raw message data)")
                        (is (not-any? #(re-find #"reply!" (str %))
                                      (hiccup-strings hiccup))
                            "transcript self-narration never shows as the last reply")
                        (is (re-find #"I found 3 workouts this week" ai)
                            "the twin tells the agent its reply is on display"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-tile-content-key-literal-hiccup
  (async done
    (-> (with-agent-conn "tilelit-000001"
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id "tilelit-000001"
                     :seon.render.live-tile/content [:h1 "wired!"]}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (let [{:seon.render/keys [hiccup]}
                                 (render/render-agent-tile
                                   {:seon.db/db @conn
                                    :seon.agent/id "tilelit-000001"})]
                             (is (= [:h1 "wired!"] hiccup)
                                 "literal hiccup on ::content roundtrips the EDN bridge and renders as-is"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-tile-twin-fn-carries-both-keys
  (async done
    (-> (with-agent-conn "tiletwn-000001"
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id "tiletwn-000001"
                     :seon.render.live-tile/content
                     'seon.render.live-tile-test/twin-tile}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (let [{:seon.render/keys [hiccup ai]}
                                 (render/render-agent-tile
                                   {:seon.db/db @conn
                                    :seon.agent/id "tiletwn-000001"})]
                             (is (= [:div.seon-tile [:span "3 workouts this week"]]
                                    hiccup))
                             (is (= "3 workouts this week: Mon, Wed, Fri — trending up."
                                    ai)
                                 "response carries BOTH twins"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-tile-throwing-fn-is-legible
  (async done
    (-> (with-agent-conn "tileerr-000001"
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id "tileerr-000001"
                     :seon.render.live-tile/content
                     'seon.render.live-tile-test/throwing-tile}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (let [{:seon.render/keys [hiccup ai error]}
                                 (render/render-agent-tile
                                   {:seon.db/db @conn
                                    :seon.agent/id "tileerr-000001"})]
                             (is (some #(re-find #"tile error" %)
                                       (hiccup-strings hiccup))
                                 "human sees the fallback card — NOT a vanish")
                             (is (re-find #"deliberate tile failure"
                                          (:seon.error/message error))
                                 "response carries the error envelope")
                             (is (re-find #"throwing-tile" (str ai))
                                 "twin names the broken renderer"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; wiring-source — the canonical creation-time wiring eval (U4).
;; One definition shared by seon.client/creation-evals! and these
;; assertions: the source must parse as ONE form whose narration is
;; the §3.1 tutorial and whose form transacts welcome-sym onto the
;; agent's own lookup ref.
;; ============================================================

(deftest wiring-source-parses-as-one-tutorial-form
  (let [agent-id "AGTwiresrc0001"
        parsed   (repl.internal/parse-forms (tile/wiring-source agent-id))
        {:keys [kind narration form]} (first parsed)]
    (is (= 1 (count parsed)) "exactly ONE form — the wiring transact")
    (is (= :form kind) "it parses cleanly (:form, not :read)")
    (testing "the tutorial comments ride as narration (context-v4 §3.1)"
      (is (re-find #"I am an entity in the shared store" (str narration)))
      (is (re-find #"lookup ref" (str narration))))
    (testing "the form IS the transact onto the agent's own lookup ref"
      (is (= 'seon.db/transact! (first form)))
      (let [tx-map (first (:seon.db/tx-data (second form)))]
        (is (= agent-id (:seon.agent/id tx-map))
            "identity attr addresses the agent's OWN entity")
        (is (= (list 'quote tile/welcome-sym)
               (:seon.render.live-tile/content tx-map))
            "wires the quoted substrate welcome symbol")))))
