(ns seon.render.canvas-test
  "Tests for seon.render.canvas — the tile key, resolution
   provenance, the welcome, and the legible-error contract — plus the
   render-agent-canvas integration (canvas PRD 2026-06-11, U1):

     • greeting — time-of-day boundaries
     • wired-content — ::content wins (legacy :seon.render/html tile
       fallback DELETED, PRD §8.1) → welcome default; pr-str-encoded
       values decode
     • welcome — .seon-card compact+expanded blocks, date, purpose,
       tile line, :seon.render/ai render
     • error-response — fallback tile + envelope + render (never vanish)
     • render-agent-canvas — unwired→welcome, literal hiccup via the new
       key, throwing fn → error-response, ::content EDN roundtrip

   Fresh isolated conn per integration test (client/open-agent-conn!)
   — NEVER the live pod conn."
  (:require
    [cljs.reader :as reader]
    [cljs.test :as t :refer [deftest is testing async]]
    [clojure.string :as str]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.error :as error]
    [seon.render :as render]
    [seon.render.canvas :as canvas]
    [seon.repl.internal :as repl.internal]
    [seon.schema :as schema]
    [seon.ui.html :as html]))

;; The render-agent-canvas degradation tests assert the graceful PROD fallback
;; (throw / broken hiccup → calm banner, never a crash). Under the harness
;; strict default (SEON_RENDER_STRICT=1) those renders THROW by design, so
;; force the fail-loud dial OFF for this ns (process-global env, async-safe —
;; a scoped with-redefs would restore before an async body runs). Restore the
;; CALLER's value, not a hardcoded "1" — an isolated bare-node run (env unset)
;; must not leave the dial flipped ON for whatever runs next.
(defonce ^:private prior-strict-env
  (atom nil))

(t/use-fixtures :once
  {:before (fn []
             (reset! prior-strict-env
                     (.. js/globalThis -process -env -SEON_RENDER_STRICT))
             (set! (.. js/globalThis -process -env -SEON_RENDER_STRICT) "0"))
   :after  (fn []
             (set! (.. js/globalThis -process -env -SEON_RENDER_STRICT)
                   (or @prior-strict-env "")))})

;; ============================================================
;; greeting — pure time-of-day boundaries.
;; ============================================================

(deftest greeting-time-of-day-boundaries
  (is (= "Good morning"   (canvas/greeting 5)))
  (is (= "Good morning"   (canvas/greeting 11)))
  (is (= "Good afternoon" (canvas/greeting 12)))
  (is (= "Good afternoon" (canvas/greeting 16)))
  (is (= "Good evening"   (canvas/greeting 17)))
  (is (= "Good evening"   (canvas/greeting 21)))
  (is (= "Good night"     (canvas/greeting 22)))
  (is (= "Good night"     (canvas/greeting 4))))

;; ============================================================
;; wired-content — resolution order + provenance.
;; Values arrive pr-str-encoded from the mixed-:or bridge.
;; ============================================================

(deftest wired-content-new-key-wins
  (let [{:seon.render.canvas/keys [source value]}
        (canvas/wired-content
          {:seon.render/entity
           {:seon.agent/id "wired-22060001"
            :seon.render.canvas/content (pr-str 'my.ns/tile-fn)
            :seon.render/html              (pr-str 'other.ns/old-fn)}})]
    (is (= :seon.render.canvas/content source))
    (is (= 'my.ns/tile-fn value) "new key wins AND decodes")))

(deftest wired-content-ignores-legacy-html-slot
  ;; Render sweep 2026-06-11 (PRD §8.1, no legacy): :seon.render/html
  ;; is the generic entity-card slot ONLY — never the tile.
  (let [{:seon.render.canvas/keys [source value]}
        (canvas/wired-content
          {:seon.render/entity
           {:seon.agent/id    "wired-22060002"
            :seon.render/html (pr-str [:h1 "legacy"])}})]
    (is (= :seon.render.canvas/welcome source))
    (is (= canvas/welcome-sym value)
        "the card slot never wires the tile — welcome renders")))

(deftest wired-content-unwired-defaults-to-welcome
  (let [{:seon.render.canvas/keys [source value]}
        (canvas/wired-content
          {:seon.render/entity {:seon.agent/id "wired-22060003"}})]
    (is (= :seon.render.canvas/welcome source))
    (is (= canvas/welcome-sym value))))

(deftest wired-content-pin-beats-derived-beats-welcome
  (testing "no pin + a derived last-updated tile → the derived fn"
    (let [{:seon.render.canvas/keys [source value]}
          (canvas/wired-content
            {:seon.render/entity {:seon.agent/id "wired-22060004"}
             :seon.render.canvas/derived 'my.agent.x/plan-tile})]
      (is (= :seon.render.canvas/derived source))
      (is (= 'my.agent.x/plan-tile value))
      (testing "the label names the derivation and how to pin"
        (let [label (canvas/wired-label
                      {:seon.render.canvas/source source
                       :seon.render.canvas/value  value})]
          (is (str/includes? label "my.agent.x/plan-tile"))
          (is (str/includes? label "derived"))
          (is (str/includes? label ":seon.render.canvas/content"))))))
  (testing "a stored pin wins over the derived default"
    (let [{:seon.render.canvas/keys [source value]}
          (canvas/wired-content
            {:seon.render/entity
             {:seon.agent/id "wired-22060005"
              :seon.render.canvas/content (pr-str 'my.ns/pinned-tile)}
             :seon.render.canvas/derived 'my.agent.x/plan-tile})]
      (is (= :seon.render.canvas/content source))
      (is (= 'my.ns/pinned-tile value)
          "pin regardless of recency — the override"))))

;; ============================================================
;; welcome — time-aware, purpose-aware, twin-carrying, tagged blocks.
;; ============================================================

(defn- hiccup-strings
  "Every string anywhere in a hiccup tree, one flat seq."
  [h]
  (filter string? (flatten h)))

(deftest welcome-emits-tagged-blocks-and-twin
  (let [{:seon.render/keys [hiccup ai]}
        (canvas/welcome {:seon.db/db nil :seon.agent/id "wlcm-2206110001"})
        classes (->> (flatten hiccup) (filter map?) (keep :class))]
    (is (canvas/valid-hiccup? hiccup))
    (is (= "seon-card" (:class (second hiccup)))
        "wrapped in the .seon-card container")
    (is (some #(re-find #"seon-card-compact" %) classes))
    (is (some #(re-find #"seon-card-expanded" %) classes)
        "ONE render carries BOTH zoom blocks")
    (testing "time-aware greeting matches the wall clock"
      (let [expected (canvas/greeting (.getHours (js/Date.)))]
        (is (some #(re-find (re-pattern expected) %) (hiccup-strings hiccup)))
        (is (re-find (re-pattern expected) ai))))
    (testing "today's date renders"
      (let [date-str (.toLocaleDateString (js/Date.) "en-US"
                                          #js {:weekday "long"
                                               :month   "long"
                                               :day     "numeric"})]
        (is (some #(re-find (re-pattern date-str) %) (hiccup-strings hiccup)))))
    (testing "the double-duty tile line is present in BOTH renders"
      (is (some #(= canvas/welcome-line %) (hiccup-strings hiccup)))
      (is (str/includes? ai canvas/welcome-line)
          "the ai render surfaces the welcome-line verbatim"))))

(deftest welcome-uses-purpose-when-present
  (let [{:seon.render/keys [hiccup ai]}
        (canvas/welcome {:seon.db/db nil
                       :seon.agent/id "wlcm-2206110002"
                       :seon.render/entity
                       {:seon.agent/id      "wlcm-2206110002"
                        :seon.agent/purpose "track your workouts"}})]
    (is (some #(re-find #"track your workouts" %) (hiccup-strings hiccup)))
    (is (re-find #"track your workouts" ai)
        "the twin tells the agent its purpose is on display")))

(deftest welcome-generic-without-purpose-or-name
  (let [{:seon.render/keys [hiccup]}
        (canvas/welcome {:seon.db/db nil :seon.agent/id "wlcm-2206110003"})]
    (is (some #(re-find #"finding my purpose" %) (hiccup-strings hiccup))
        "gracefully generic — no purpose, no name, still elegant")))

(deftest welcome-compact-shows-purpose-id-and-truthful-twin
  (let [{:seon.render/keys [hiccup ai]}
        (canvas/welcome {:seon.db/db nil
                       :seon.agent/id "wlcm-2206110004"
                       :seon.render/entity
                       {:seon.agent/id      "wlcm-2206110004"
                        :seon.agent/purpose "track your workouts"}})]
    (is (some #(= "wlcm-2206110004" %) (hiccup-strings hiccup))
        "the agent's id renders on the default tile (canvas U3)")
    (testing "the twin is TRUTHFUL — every minted agent IS wired (to welcome)"
      (is (not (re-find #"haven't wired" ai))
          "the old wording lied: creation wires every agent to welcome")
      (is (re-find #"core default" ai))
      (is (re-find #":seon.render.canvas/content" ai)
          "the twin always says HOW to repoint the tile"))))

;; ============================================================
;; error-response — a broken tile is LEGIBLE on both sides.
;; ============================================================

(deftest error-response-never-vanishes
  (let [env {:seon.error/message "boom from tile fn"}
        {:seon.render/keys [hiccup ai error]}
        (canvas/error-response
          {:seon.db/error                 env
           :seon.render.canvas/content 'my.ns/broken-tile})]
    (is (canvas/valid-hiccup? hiccup) "human sees a card, not a blank")
    ;; ISOLATION CONTRACT (tile-isolation Layer 1), asserted as MECHANISM not
    ;; placeholder wording: the failure is partitioned to the agent-facing
    ;; channels (the :seon.render/ai twin + the :seon.render/error envelope)
    ;; and NEVER leaks into the human hiccup. So the human card is structurally
    ;; a normal .seon-card, indistinguishable from a healthy canvas, while the
    ;; twin + envelope carry the SAME message the human never sees.
    (let [human-strings (hiccup-strings hiccup)]
      (is (= "seon-card" (:class (second hiccup)))
          "the human card is a normal .seon-card — a failure is indistinguishable from a healthy canvas")
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
  (is (nil? (canvas/hiccup-structure-error [:div "plain"])))
  (is (nil? (canvas/hiccup-structure-error
              [:div {:class "x"} 3.14 nil false
               (list [:li "a"] [:li "b"])
               (html/raw "<b>pre-escaped</b>")
               'a-symbol-child])))
  (is (nil? (canvas/hiccup-structure-error
              (:seon.render/hiccup
                (canvas/welcome {:seon.db/db nil
                               :seon.agent/id "structok-000001"}))))
      "the core welcome passes its own gate"))

(deftest structure-error-locates-vector-of-vectors
  (let [{:seon.render.canvas/keys [structure-path structure-message]}
        (canvas/hiccup-structure-error vov-repro-hiccup)]
    (is (= [2] structure-path) "the defect's path, not just 'somewhere'")
    (is (re-find #"vector-of-vectors" structure-message))
    (is (re-find #"Splice" structure-message)
        "the message teaches the fix, not just the failure"))
  (testing "nested + behind an attrs map — path offsets account for attrs"
    (let [{:seon.render.canvas/keys [structure-path]}
          (canvas/hiccup-structure-error
            [:div {:class "x"} [:span "ok"] [:div [[:b "deep"]]]])]
      (is (= [3 1] structure-path)))))

(deftest structure-error-locates-invalid-tag
  (let [{:seon.render.canvas/keys [structure-path structure-message]}
        (canvas/hiccup-structure-error [:div [123 "not a tag"]])]
    (is (= [1] structure-path))
    (is (re-find #"invalid tag" structure-message))
    (is (re-find #"123" structure-message) "quotes the offending value")))

(deftest structure-error-locates-misplaced-attrs
  ;; #42 — the unambiguous displaced-attrs case: the 2nd slot is a
  ;; non-map child AND an attrs-looking map sits at child index ≥ 1, so
  ;; the serializer reads it as garbage content instead of attrs.
  (let [{:seon.render.canvas/keys [structure-path structure-message]}
        (canvas/hiccup-structure-error [:div "title" {:class "c"} "body"])]
    (is (= [2] structure-path) "the misplaced map's vector index")
    (is (re-find #"misplaced attrs map" structure-message))
    (is (re-find #"SECOND element" structure-message)
        "the message names the attrs-position rule")
    (is (re-find #"child index 1" structure-message)
        "the message names the offending child index")
    (is (re-find #"\{:class" structure-message) "quotes the offending map"))
  (testing "nested — the path descends into the offending child"
    (let [{:seon.render.canvas/keys [structure-path]}
          (canvas/hiccup-structure-error [:div [:span "a" {:k 1}]])]
      (is (= [1 2] structure-path))))
  (testing "CONSERVATIVE — valid tiles never trip the misplaced-attrs rule"
    ;; correct attrs in 2nd position
    (is (nil? (canvas/hiccup-structure-error [:div {:k 1} "x"])))
    ;; no map at all
    (is (nil? (canvas/hiccup-structure-error [:h3 "x"])))
    (is (nil? (canvas/hiccup-structure-error [:div [:h3 "x"] [:p "y"]])))
    ;; bare tag
    (is (nil? (canvas/hiccup-structure-error [:hr])))
    ;; a raw map as content is fine (raw? excluded)
    (is (nil? (canvas/hiccup-structure-error
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
  (doseq [k [:seon.render.canvas/hiccup
             :seon.render.canvas/content
             :seon.render.canvas/wired-response
             :seon.render.canvas/error-request
             :seon.render/html
             :seon.render/html-response
             :seon.render/ai-response
             :seon.agent.ctx/render-namespace-response
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
                  (m/schema :seon.render.canvas/content)
                  %)]
    (is (valid? 'my.ns/tile-fn) "qualified fn symbol")
    (is (valid? [:h1 {:class "x"} "hi" [:span "nested"]]) "literal hiccup")
    (is (not (valid? [])) "empty vector is not hiccup")
    (is (not (valid? '(:h1 "x"))) "a list is not hiccup")
    (is (not (valid? "string")) "bare string rejected")))

;; ============================================================
;; render-agent-canvas integration — fresh conn, never the live pod.
;; ============================================================

(defn throwing-tile
  "Test tile renderer that always throws — the error-envelope target."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_input]
  (throw (ex-info "deliberate tile failure" {:seon.render.canvas/test true})))

(defn twin-tile
  "Test tile renderer returning BOTH twins — the twin-contract target."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [_input]
  {:seon.render/hiccup [:div.seon-card [:span "3 workouts this week"]]
   :seon.render/ai     "3 workouts this week: Mon, Wed, Fri — trending up."})

(defn- with-agent-conn
  "Open a fresh conn, seed one agent row, and call `body` with the conn HELD via set! for the WHOLE
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
                        [{:seon.agent/id agent-id}]})
                     (.then (fn [_] (body conn)))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(deftest render-agent-canvas-unwired-renders-welcome
  (async done
    (-> (with-agent-conn "tilewlc-000001"
          (fn [conn]
            (let [{:seon.render/keys [hiccup ai]}
                  (render/render-agent-canvas {:seon.db/db @conn
                                             :seon.agent/id "tilewlc-000001"})]
              ;; DISPATCH MECHANISM, not the greeting prose: an unwired agent
              ;; resolves to the welcome renderable, which ALWAYS returns the
              ;; html-response twin pair. Assert the twin is present and
              ;; non-blank, and that it's the WELCOME twin specifically — its
              ;; stable contract is naming how to repoint the tile
              ;; (:seon.render.canvas/content), not any time-of-day wording.
              (is (= "seon-card" (:class (second hiccup)))
                  "unwired agent dispatches to the core welcome renderable")
              (is (and (string? ai) (seq ai))
                  "the welcome twin (the ai-format string) rides the response")
              (is (str/includes? ai ":seon.render.canvas/content")
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
                            (canvas/welcome
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

(deftest render-agent-canvas-content-key-literal-hiccup
  (async done
    (-> (with-agent-conn "tilelit-000001"
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id "tilelit-000001"
                     :seon.render.canvas/content [:h1 "wired!"]}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (let [{:seon.render/keys [hiccup]}
                                 (render/render-agent-canvas
                                   {:seon.db/db @conn
                                    :seon.agent/id "tilelit-000001"})]
                             (is (= [:h1 "wired!"] hiccup)
                                 "literal hiccup on ::content roundtrips the EDN bridge and renders as-is"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-canvas-literal-hiccup-interactive-gets-transform
  ;; #22 B.1 — a LITERAL-HICCUP tile with an :on-click handler is
  ;; agent-authored too, so its handler MUST be rewritten to a Datastar
  ;; @post pointing at the agent's OWN /call door. Before the fix the
  ;; transform gated on `agent-authored-sym?` (a SYMBOL), so literal
  ;; hiccup fell through untouched → a dead button.
  (async done
    (-> (with-agent-conn "tileint-000001"
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id "tileint-000001"
                     :seon.render.canvas/content
                     [:button {:on-click (list 'bump! "row-1")} "+1"]}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (let [{:seon.render/keys [hiccup]}
                                 (render/render-agent-canvas
                                   {:seon.db/db @conn
                                    :seon.agent/id "tileint-000001"})
                                 attrs (second hiccup)
                                 action (:data-on:click attrs)]
                             (is (nil? (:on-click attrs))
                                 "the raw :on-click slot is gone — rewritten, not emitted verbatim")
                             (is (string? action)
                                 "a literal-hiccup :on-click becomes a Datastar @post (no dead button)")
                             (is (str/includes?
                                   action "@post('/agent/tileint-000001/call?fn=")
                                 "routes to the agent's OWN /call door")
                             (is (str/includes?
                                   action "my.agent.tileint-000001%2Fbump!")
                                 "the bare handler qualifies to the agent's home ns")
                             (is (str/includes? action "args=")
                                 "the fn-CALL render-time arg rides ?args="))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-canvas-twin-fn-carries-both-keys
  (async done
    (-> (with-agent-conn "tiletwn-000001"
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id "tiletwn-000001"
                     :seon.render.canvas/content
                     'seon.render.canvas-test/twin-tile}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (let [{:seon.render/keys [hiccup ai]}
                                 (render/render-agent-canvas
                                   {:seon.db/db @conn
                                    :seon.agent/id "tiletwn-000001"})]
                             (is (= [:div.seon-card [:span "3 workouts this week"]]
                                    hiccup))
                             (is (= "3 workouts this week: Mon, Wed, Fri — trending up."
                                    ai)
                                 "response carries BOTH twins"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-canvas-throwing-fn-is-legible
  (async done
    (-> (with-agent-conn "tileerr-000001"
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id "tileerr-000001"
                     :seon.render.canvas/content
                     'seon.render.canvas-test/throwing-tile}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           ;; throwing-tile is seon.* → :core fault; deliberate,
                           ;; so bracket EXPECTED (render is sync; the alias
                           ;; resolves before the local `error` destructure).
                           (let [{:seon.render/keys [hiccup ai error]}
                                 (error/expecting-core-fault!
                                   (fn []
                                     (render/render-agent-canvas
                                       {:seon.db/db @conn
                                        :seon.agent/id "tileerr-000001"})))]
                             (is (= "seon-card" (:class (second hiccup)))
                                 "human sees a valid fallback card rather than a vanish")
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
               :seon.render.canvas/content content}]})
          (.then
            (fn [_]
              (binding [db/*conn* conn]
                ;; A structurally-broken tile is a :core fault (the render
                ;; machinery is seon.*); deliberate across vov / literal-broken
                ;; / backstop, so bracket EXPECTED (sync render).
                (let [{:seon.render/keys [hiccup ai error]}
                      (error/expecting-core-fault!
                        (fn []
                          (render/render-agent-canvas
                            {:seon.db/db @conn :seon.agent/id agent-id})))]
                  (is (= "seon-card" (:class (second hiccup)))
                      "human sees a valid fallback card and the page never 500s")
                  (is (re-find re (:seon.error/message error))
                      "envelope carries the legible structure error")
                  (is (re-find re (str ai))
                      "the twin tells the agent exactly what broke")
                  (is (string? (html/->string hiccup))
                      "the fallback hiccup itself serializes")))))))))

(deftest render-agent-canvas-vector-of-vectors-degrades-to-banner
  (async done
    (-> (tile-degrades-legibly
          "tilevov-000001"
          'seon.render.canvas-test/vector-of-vectors-tile
          #"vector-of-vectors")
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-canvas-literal-broken-hiccup-degrades-to-banner
  ;; The literal-hiccup arm of html-render never CALLS anything — the
  ;; old guard couldn't fire at all. Same seam covers it.
  (async done
    (-> (tile-degrades-legibly
          "tilevov-000002" vov-repro-hiccup #"vector-of-vectors")
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-canvas-serializer-backstop-catches-walk-misses
  ;; Falsifies the backstop layer specifically: a defect the walk
  ;; doesn't model (unparseable keyword tag) still degrades.
  (async done
    (-> (tile-degrades-legibly
          "tilevov-000003"
          'seon.render.canvas-test/unparseable-tag-tile
          #"Unparseable tag")
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; CSS contract — the core stylesheet honors what the ns
;; docstring teaches (CSS-correctness unit, 2026-06-11):
;;   • .seon-card-compact is CLAMPED (bounded height, overflow
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
  "The utility vocabulary the canvas ns docstring teaches agents —
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
  (let [[block] (re-find #"\.seon-card-compact \{([^}]*)\}" input-css)]
    (is (some? block) ".seon-card-compact rule exists")
    (is (re-find #"max-height" (str block))
        "compact block has a bounded height — tiles never grow the grid cell")
    (is (re-find #"overflow:\s*hidden" (str block))
        "compact overflow clips")
    (is (re-find #"\.seon-card-reply" input-css)
        "the last-reply line-clamp rule survives")))

(deftest docstring-vocabulary-is-safelisted
  (is (seq safelisted-classes) "@source inline(...) safelist exists")
  (doseq [cls docstring-vocabulary]
    (is (contains? safelisted-classes cls)
        (str cls " is taught in the ns docstring but NOT safelisted — "
             "agents emitting it at runtime get a class that doesn't exist"))))

(deftest base-content-layer-covers-semantic-html
  (let [scope (re-find #":is\(\.seon-card, \.seon-bubble, \.markdown, \.seon-agent-content\)"
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
        parsed   (repl.internal/parse-forms (canvas/wiring-source agent-id))
        {:seon.repl/keys [kind narration form]} (first parsed)]
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
        (is (= (list 'quote canvas/welcome-sym)
               (:seon.render.canvas/content tx-map))
            "wires the quoted core welcome symbol")))))
