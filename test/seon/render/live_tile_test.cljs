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
    [clojure.string :as str]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.render :as render]
    [seon.render.live-tile :as tile]
    [seon.repl.internal :as repl.internal]
    [seon.schema :as schema]
    [seon.ui.html :as html]))

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
      (is (re-find #"core default" ai))
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
    ;; ISOLATION CONTRACT (tile-isolation Layer 1), asserted as MECHANISM not
    ;; placeholder wording: the failure is partitioned to the agent-facing
    ;; channels (the :seon.render/ai twin + the :seon.render/error envelope)
    ;; and NEVER leaks into the human hiccup. So the human card is structurally
    ;; a normal .seon-tile, indistinguishable from a healthy tile, while the
    ;; twin + envelope carry the SAME message the human never sees.
    (let [human-strings (hiccup-strings hiccup)]
      (is (= "seon-tile" (:class (second hiccup)))
          "the human card is a normal .seon-tile — a failure is indistinguishable from a healthy tile")
      (is (not-any? #(str/includes? % (:seon.error/message env)) human-strings)
          "the wired fn's failure message is ABSENT from the human card")
      (is (not-any? #(re-find #"(?i)error" %) human-strings)
          "no error text leaks to the human — the error is routed to the agent twin"))
    (is (= env error) "response carries the :seon.error/* envelope verbatim")
    (is (str/includes? ai "my.ns/broken-tile")
        "twin names the wired value that broke")
    (is (str/includes? ai (:seon.error/message env))
        "the SAME envelope message the human never sees rides the agent twin")))

;; ============================================================
;; hiccup-structure-error — serializer-faithful, NOT valid-hiccup?:
;; everything ->string tolerates passes; everything that makes
;; ->string THROW is located with a path (serialization-boundary hardening).
;; ============================================================

(def vov-repro-hiccup
  "The EXACT shape from the vector-of-vectors incident: a
   vector-of-vectors child (`[:div hdr [[:div …] [:div …]]]`) — the fn
   call succeeds, then page serialization throws Invalid tag and 500s
   /agent/<id>."
  [:div "Header" [[:div "row-a"] [:div "row-b"]]])

(deftest structure-error-nil-on-serializable-hiccup
  ;; Serializer-tolerant shapes valid-hiccup? REJECTS must pass here —
  ;; gating the render path on the strict authoring shape would
  ;; falsely error legitimate tiles.
  (is (nil? (tile/hiccup-structure-error [:div "plain"])))
  (is (nil? (tile/hiccup-structure-error
              [:div {:class "x"} 3.14 nil false
               (list [:li "a"] [:li "b"])
               (html/raw "<b>pre-escaped</b>")
               'a-symbol-child])))
  (is (nil? (tile/hiccup-structure-error
              (:seon.render/hiccup
                (tile/welcome {:seon.db/db nil
                               :seon.agent/id "structok-000001"}))))
      "the core welcome passes its own gate"))

(deftest structure-error-locates-vector-of-vectors
  (let [{:seon.render.live-tile/keys [structure-path structure-message]}
        (tile/hiccup-structure-error vov-repro-hiccup)]
    (is (= [2] structure-path) "the defect's path, not just 'somewhere'")
    (is (re-find #"vector-of-vectors" structure-message))
    (is (re-find #"Splice" structure-message)
        "the message teaches the fix, not just the failure"))
  (testing "nested + behind an attrs map — path offsets account for attrs"
    (let [{:seon.render.live-tile/keys [structure-path]}
          (tile/hiccup-structure-error
            [:div {:class "x"} [:span "ok"] [:div [[:b "deep"]]]])]
      (is (= [3 1] structure-path)))))

(deftest structure-error-locates-invalid-tag
  (let [{:seon.render.live-tile/keys [structure-path structure-message]}
        (tile/hiccup-structure-error [:div [123 "not a tag"]])]
    (is (= [1] structure-path))
    (is (re-find #"invalid tag" structure-message))
    (is (re-find #"123" structure-message) "quotes the offending value")))

(deftest structure-error-locates-misplaced-attrs
  ;; #42 — the unambiguous displaced-attrs case: the 2nd slot is a
  ;; non-map child AND an attrs-looking map sits at child index ≥ 1, so
  ;; the serializer reads it as garbage content instead of attrs.
  (let [{:seon.render.live-tile/keys [structure-path structure-message]}
        (tile/hiccup-structure-error [:div "title" {:class "c"} "body"])]
    (is (= [2] structure-path) "the misplaced map's vector index")
    (is (re-find #"misplaced attrs map" structure-message))
    (is (re-find #"SECOND element" structure-message)
        "the message names the attrs-position rule")
    (is (re-find #"child index 1" structure-message)
        "the message names the offending child index")
    (is (re-find #"\{:class" structure-message) "quotes the offending map"))
  (testing "nested — the path descends into the offending child"
    (let [{:seon.render.live-tile/keys [structure-path]}
          (tile/hiccup-structure-error [:div [:span "a" {:k 1}]])]
      (is (= [1 2] structure-path))))
  (testing "CONSERVATIVE — valid tiles never trip the misplaced-attrs rule"
    ;; correct attrs in 2nd position
    (is (nil? (tile/hiccup-structure-error [:div {:k 1} "x"])))
    ;; no map at all
    (is (nil? (tile/hiccup-structure-error [:h3 "x"])))
    (is (nil? (tile/hiccup-structure-error [:div [:h3 "x"] [:p "y"]])))
    ;; bare tag
    (is (nil? (tile/hiccup-structure-error [:hr])))
    ;; a raw map as content is fine (raw? excluded)
    (is (nil? (tile/hiccup-structure-error
                [:div "txt" (html/raw "<b>x</b>")])))))

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
                              [{:seon.agent/id    agent-id}])})
                     (.then (fn [_] (body conn)))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(deftest render-agent-tile-unwired-renders-welcome
  (async done
    (-> (with-agent-conn "tilewlc-000001"
          (fn [conn]
            (let [{:seon.render/keys [hiccup ai]}
                  (render/render-agent-tile {:seon.db/db @conn
                                             :seon.agent/id "tilewlc-000001"})]
              ;; DISPATCH MECHANISM, not the greeting prose: an unwired agent
              ;; resolves to the welcome renderable, which ALWAYS returns the
              ;; html-response twin pair. Assert the twin is present and
              ;; non-blank, and that it's the WELCOME twin specifically — its
              ;; stable contract is naming how to repoint the tile
              ;; (:seon.render.live-tile/content), not any time-of-day wording.
              (is (= "seon-tile" (:class (second hiccup)))
                  "unwired agent dispatches to the core welcome renderable")
              (is (and (string? ai) (seq ai))
                  "the welcome twin (the ai-format string) rides the response")
              (is (str/includes? ai ":seon.render.live-tile/content")
                  "the welcome twin teaches HOW to repoint the tile — its stable contract"))))
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
                             (is (some #(re-find #"Updating this panel" %)
                                       (hiccup-strings hiccup))
                                 "human sees the calm placeholder card — NOT a vanish, NOT a scary error")
                             (is (re-find #"deliberate tile failure"
                                          (:seon.error/message error))
                                 "response carries the error envelope")
                             (is (re-find #"throwing-tile" (str ai))
                                 "twin names the broken renderer"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; Serialization errors join the guarded path (serialization-boundary hardening): a
;; tile whose FN CALL succeeds but whose hiccup can't serialize must
;; degrade to error-response — the page (and the chat on it) never
;; 500s for a tile problem, and the response hiccup itself is PROVEN
;; serializable.
;; ============================================================

(defn vector-of-vectors-tile
  "Test tile reproducing the vector-of-vectors incident: the call
   SUCCEEDS, the hiccup is structurally broken."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_input]
  {:seon.render/hiccup vov-repro-hiccup
   :seon.render/ai     "header rows"})

(defn unparseable-tag-tile
  "Test tile whose hiccup passes the structural walk (keyword tag)
   but still makes ->string throw (`:#foo` has no tag name) — the
   backstop-serialization target."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_input]
  ;; (keyword "#foo") — the literal `:#foo` is reader-undefined.
  {:seon.render/hiccup [:div [(keyword "#foo") "no tag name"]]})

(defn- tile-degrades-legibly
  "Shared assertion body: wire `content` onto a fresh agent, render,
   and require the FULL degradation contract — error-response card,
   `re` in both the twin and the envelope message, and a response
   hiccup that PROVABLY serializes (the page can embed it safely)."
  [agent-id content re]
  (with-agent-conn agent-id
    (fn [conn]
      (-> (db/transact!
            {:seon.db/tx-data
             [{:seon.agent/id                 agent-id
               :seon.render.live-tile/content content}]})
          (.then
            (fn [_]
              (binding [db/*conn* conn]
                (let [{:seon.render/keys [hiccup ai error]}
                      (render/render-agent-tile
                        {:seon.db/db @conn :seon.agent/id agent-id})]
                  (is (some #(re-find #"Updating this panel" %)
                            (hiccup-strings hiccup))
                      "human sees the calm placeholder card — the page never 500s")
                  (is (re-find re (:seon.error/message error))
                      "envelope carries the legible structure error")
                  (is (re-find re (str ai))
                      "the twin tells the agent exactly what broke")
                  (is (string? (html/->string hiccup))
                      "the fallback hiccup itself serializes")))))))))

(deftest render-agent-tile-vector-of-vectors-degrades-to-banner
  (async done
    (-> (tile-degrades-legibly
          "tilevov-000001"
          'seon.render.live-tile-test/vector-of-vectors-tile
          #"vector-of-vectors")
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-tile-literal-broken-hiccup-degrades-to-banner
  ;; The literal-hiccup arm of html-render never CALLS anything — the
  ;; old guard couldn't fire at all. Same seam covers it.
  (async done
    (-> (tile-degrades-legibly
          "tilevov-000002" vov-repro-hiccup #"vector-of-vectors")
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-tile-serializer-backstop-catches-walk-misses
  ;; Falsifies the backstop layer specifically: a defect the walk
  ;; doesn't model (unparseable keyword tag) still degrades.
  (async done
    (-> (tile-degrades-legibly
          "tilevov-000003"
          'seon.render.live-tile-test/unparseable-tag-tile
          #"Unparseable tag")
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; CSS contract — the core stylesheet honors what the ns
;; docstring teaches (CSS-correctness unit, 2026-06-11):
;;   • .seon-tile-compact is CLAMPED (bounded height, overflow
;;     clipped) — grid tiles never grow vertically;
;;   • every utility in the documented vocabulary is SAFELISTED via
;;     @source inline(...) — runtime-generated classes aren't
;;     scanned from source, so an undocumented==unsafelisted class
;;     silently doesn't exist;
;;   • the base content layer styles semantic HTML in the agent
;;     content containers (preflight strips element styling).
;; Node-side file read — the pod runs from the repo root.
;; ============================================================

(def ^:private input-css
  (.readFileSync (js/require "fs") "resources/public/css/input.css" "utf8"))

(defn- expand-braces
  "Expand ONE level of {a,b,c} groups in a safelist pattern token:
   \"p-{1,2}\" → (\"p-1\" \"p-2\"). Tokens without braces pass through."
  [token]
  (if-let [[_ pre alts post] (re-find #"^([^{]*)\{([^}]*)\}(.*)$" token)]
    (mapcat #(expand-braces (str pre % post))
            (.split alts ","))
    [token]))

(def ^:private safelisted-classes
  (->> (re-seq #"@source inline\(\"([^\"]+)\"\)" input-css)
       (mapcat (fn [[_ pattern]] (.split pattern " ")))
       (mapcat expand-braces)
       set))

(def ^:private docstring-vocabulary
  "The utility vocabulary the live-tile ns docstring teaches agents —
   every entry MUST be safelisted or agents emit classes that
   silently don't exist. Keep in sync with the docstring AND the
   @source inline(...) block in resources/public/css/input.css."
  ["flex" "flex-col" "flex-row" "flex-wrap" "flex-1" "shrink-0"
   "grid" "grid-cols-2" "grid-cols-3" "grid-cols-4"
   "items-center" "items-start" "items-baseline"
   "justify-between" "justify-end" "justify-center"
   "gap-1" "gap-2" "gap-3" "gap-4" "w-full" "h-full" "min-w-0"
   "p-0" "p-1" "p-2" "p-3" "p-4"
   "px-1" "px-2" "px-3" "px-4" "py-1" "py-2" "py-3" "py-4"
   "mt-1" "mt-2" "mb-1" "mb-2"
   "text-xs" "text-sm" "text-base" "text-lg"
   "text-left" "text-center" "text-right"
   "font-mono" "font-semibold" "font-bold"
   "italic" "uppercase" "tracking-wider" "tabular-nums"
   "whitespace-pre-wrap" "truncate"
   "text-text-50" "text-text-100" "text-text-200" "text-text-300"
   "text-text-400" "text-text-500"
   "text-signal" "text-success" "text-error" "text-warning" "text-info"
   "text-amber-300" "text-amber-400" "text-amber-500"
   "bg-base-800" "bg-base-850" "bg-base-900" "bg-base-950"
   "border" "border-t" "border-b" "border-base-700" "border-base-800"
   "rounded" "rounded-md" "divide-y" "divide-base-800"
   "overflow-hidden" "overflow-auto" "overflow-x-auto"])

(deftest compact-block-is-clamped
  (let [[block] (re-find #"\.seon-tile-compact \{([^}]*)\}" input-css)]
    (is (some? block) ".seon-tile-compact rule exists")
    (is (re-find #"max-height" (str block))
        "compact block has a bounded height — tiles never grow the grid cell")
    (is (re-find #"overflow:\s*hidden" (str block))
        "compact overflow clips")
    (is (re-find #"\.seon-tile-reply" input-css)
        "the last-reply line-clamp rule survives")))

(deftest docstring-vocabulary-is-safelisted
  (is (seq safelisted-classes) "@source inline(...) safelist exists")
  (doseq [cls docstring-vocabulary]
    (is (contains? safelisted-classes cls)
        (str cls " is taught in the ns docstring but NOT safelisted — "
             "agents emitting it at runtime get a class that doesn't exist"))))

(deftest base-content-layer-covers-semantic-html
  (let [scope (re-find #":is\(\.seon-tile, \.seon-bubble, \.markdown, \.seon-agent-content\)"
                       input-css)]
    (is (some? scope)
        "base content layer scoped to the agent-content containers"))
  (doseq [el ["h1" "h2" "h3" "h4" "p" "ul, & ol" "table" "th" "td"
              "code" "pre" "blockquote" "dl" "dt" "dd" "hr" "a"
              "strong" "em"]]
    (is (re-find (re-pattern (str "& " el " \\{")) input-css)
        (str "base content layer styles <" (first (.split el ",")) ">"))))

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
            "wires the quoted core welcome symbol")))))
