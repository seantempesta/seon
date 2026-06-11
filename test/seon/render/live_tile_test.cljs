(ns seon.render.live-tile-test
  "Tests for seon.render.live-tile — the tile key, resolution
   provenance, the welcome, and the legible-error contract — plus the
   render-agent-tile integration (live-tiles PRD 2026-06-11, U1):

     • greeting — time-of-day boundaries
     • wired-content — ::content wins → legacy :seon.render/html →
       welcome default; pr-str-encoded values decode
     • welcome — .seon-tile compact+expanded blocks, date, purpose,
       panel line, :seon.render/ai twin
     • error-response — fallback card + envelope + twin (never vanish)
     • render-agent-tile — unwired→welcome, literal hiccup via the new
       key, throwing fn → error-response, ::content EDN roundtrip

   Fresh isolated conn per integration test (client/open-agent-conn!)
   — NEVER the live pod conn."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.client :as client]
    [seon.db :as db]
    [seon.render :as render]
    [seon.render.live-tile :as tile]
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

(deftest wired-content-legacy-html-second
  (let [{:seon.render.live-tile/keys [source value]}
        (tile/wired-content
          {:seon.render/entity
           {:seon.agent/id    "wired-22060002"
            :seon.render/html (pr-str [:h1 "legacy"])}})]
    (is (= :seon.render/html source))
    (is (= [:h1 "legacy"] value) "per-entity legacy slot honored")))

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
    (is (render/valid-hiccup? hiccup))
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

;; ============================================================
;; error-response — a broken tile is LEGIBLE on both sides.
;; ============================================================

(deftest error-response-never-vanishes
  (let [env {:seon.error/message "boom from tile fn"}
        {:seon.render/keys [hiccup ai error]}
        (tile/error-response
          {:seon.db/error                 env
           :seon.render.live-tile/content 'my.ns/broken-tile})]
    (is (render/valid-hiccup? hiccup) "human sees a card, not a blank")
    (is (some #(re-find #"tile error" %) (hiccup-strings hiccup)))
    (is (some #(re-find #"the agent has been shown the failure" %)
              (hiccup-strings hiccup)))
    (is (= env error) "response carries the :seon.error/* envelope")
    (is (re-find #"my\.ns/broken-tile" ai)
        "twin names the wired value that broke")
    (is (re-find #"boom from tile fn" ai)
        "twin carries the exception's message")))

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
   agent row, call `body` with the conn bound. Returns a Promise."
  [agent-id body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact!
                       {:seon.db/tx-data
                        (into (vec (schema/entity-schema-tx-data :seon.agent))
                              [{:seon.agent/id    agent-id
                                :seon.agent/state :idle}])})
                     (.then (fn [_]
                              (binding [db/*conn* conn]
                                (body conn))))))))))

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
