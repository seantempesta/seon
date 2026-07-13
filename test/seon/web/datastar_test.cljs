(ns seon.web.datastar-test
  "Behavior + mechanism regressions for the datastar whole-view SSE streamer
   (`seon.web.datastar`) — the hyperlith `view = f(db)` views and the
   `datastar-patch-elements` wire framing that morphs `#app-view`.

   Style: assert MECHANISM — the structural
   SSE framing markers, presence/absence of an agent in the agents view,
   pure-fn-of-db determinism, and NEVER-CRASH — via `str/includes?` /
   line-splitting. NEVER pin the exact rendered HTML or prose (these are
   refactoring surfaces). The db is a fresh ISOLATED `:memory` conn carrying
   the pod's full schema (`client/open-agent-conn!`), never the live cluster
   store — so the pod's state is irrelevant and the test is self-contained.

   The bare-agent + throwing-derive cases are the never-crash floor: the
   whole-view morph engine can never abort on one under-populated or failing
   agent. The guard is `agent-card`'s per-card try/catch — proven here by
   forcing `derive-state` to throw and asserting the OTHER agent + the
   degraded card still renders (the whole-view error fallback is NOT hit)."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop :include-macros true]
    [datahike.api :as d]
    [datahike.db :as datahike]
    [seon.client :as client]
    [seon.agent.debug :as agent-debug]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.render.surface :as surface]
    [seon.ui.agent-view :as agent-view]
    [seon.ui.html :as html]
    [seon.web.brand :as brand]
    [seon.web.datastar :as datastar]
    [seon.web.debug :as debug]))

(defn- check-property
  "Assert a deterministic test.check property and surface its shrink."
  [n property]
  (let [{:keys [result shrunk]} (tc/quick-check n property)]
    (is (true? result) (pr-str shrunk))))

;; Valid 14-char ids (`:seon.db/id` is [:string {:min 14 :max 14}]).
(def ^:private agent-a "view-aaaa00001")
(def ^:private agent-b "view-bbbb00002")

(def ^:private coordinate-gen
  (gen/fmap
    (fn [[agent-id block-name face page]]
      {:seon.agent/id agent-id
       :seon.agent.ctx/name block-name
       :seon.web.unit/face face
       :seon.web.data/page page})
    (gen/tuple
      (gen/elements [agent-a agent-b "root"])
      (gen/elements [:seon.agent.ctx/transcript
                     :seon.agent.ctx/plan
                     :seon.agent.ctx/namespaces])
      (gen/elements [:seon.web.unit.face/expanded
                     :seon.web.unit.face/compact
                     :seon.web.unit.face/detail])
      (gen/choose 0 20))))

(defn- with-conn
  "Fresh isolated `:memory` conn (full pod schema). Transact each id in
   `agent-ids` as a BARE agent (only `:seon.agent/id`), then call
   `(body conn)` (→ Promise|value). Returns a Promise. No `db/*conn*`
   juggling — `agents-view` reads the explicit db value we hand it, so the
   tests stay pure functions of a db value."
  [agent-ids body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (if (seq agent-ids)
                 (-> (db/transact!
                       {:seon.db/conn    conn
                        :seon.db/tx-data (mapv (fn [id] {:seon.agent/id id}) agent-ids)})
                     (.then (fn [_] (body conn))))
                 (body conn))))))

(defn- response-probe
  "Node response double plus its fully namespaced observation atom."
  []
  (let [observed (atom {})
        res #js {:writeHead
                 (fn [status headers]
                   (swap! observed assoc
                          ::response-status status
                          ::response-headers (js->clj headers)))
                 :end
                 (fn [body]
                   (swap! observed assoc ::response-body (or body "")))}]
    [res observed]))

(defn- unit-ring-request
  "Minimal Ring boundary request for one unit-control call."
  [res view-id token active]
  {:query-string (str "view=" view-id "&unit=" token "&active=" active)
   :seon.http/node-res res})

(defn- test-feed
  "Minimal normalized-feed input with explicit test overrides."
  [view-id overrides]
  (merge
    {:seon.web.feed/key [:seon.web.feed/agent agent-a]
     :seon.web.feed/live? true
     :seon.web.feed/render-full (fn [] [:main {:id "app-view"}])
     :seon.web.feed/render-change
     (fn [_subscription _change] {::datastar/elements []})
     ::datastar/view-id view-id
     ::datastar/catalog []
     ::datastar/active-tokens #{}
     :seon.web.feed/id (random-uuid)}
    overrides))

(defn- feed-registry
  "Registry whose socket consumers share normalized subscription authority."
  [feeds]
  (reduce @#'datastar/attach-feed
          @#'datastar/empty-feed-registry
          feeds))

(defn- registry-view [registry view-id]
  (get-in registry [::datastar/views view-id]))

;; ============================================================
;; 1. View-unit contracts — stable coordinates and zero speculative work.
;; ============================================================

(deftest unit-coordinate-token-is-canonical-and-stable
  (let [coordinate-a (array-map
                       :seon.agent/id agent-a
                       :seon.agent.ctx/name :seon.agent.ctx/transcript
                       :seon.render/view :html
                       :seon.web.unit/face :seon.web.unit.face/expanded)
        coordinate-b (array-map
                       :seon.web.unit/face :seon.web.unit.face/expanded
                       :seon.render/view :html
                       :seon.agent.ctx/name :seon.agent.ctx/transcript
                       :seon.agent/id agent-a)]
    (is (= (datastar/unit-token coordinate-a)
           (datastar/unit-token coordinate-b))
        "map insertion order does not change a coordinate token")
    (is (= (datastar/unit-dom-id coordinate-a)
           (datastar/unit-dom-id coordinate-b))
        "map insertion order does not change the stable DOM target")
    (is (str/starts-with? (datastar/unit-dom-id coordinate-a) "seon-unit-")
        "unit DOM ids live in one explicit id namespace")))

(deftest representative-distinct-coordinates-have-distinct-tokens
  (check-property
    250
    (prop/for-all [left coordinate-gen
                   right coordinate-gen]
      (or (= left right)
          (not= (datastar/unit-token left)
                (datastar/unit-token right))))))

(deftest catalog-and-inactive-stubs-never-invoke-producers
  (let [renders (atom 0)
        definitions [{::datastar/coordinate
                      {:seon.agent/id agent-a
                       :seon.agent.ctx/name :seon.agent.ctx/transcript}
                      ::datastar/label "transcript"
                      ::datastar/producer #(swap! renders inc)}]
        catalog (datastar/unit-catalog definitions)
        descriptor (first catalog)]
    (is (= 1 (count catalog)) "catalog construction preserves the unit")
    (is (vector? (datastar/inactive-stub descriptor))
        "an inactive unit is a complete hiccup target")
    (is (zero? @renders)
        "catalog and stub construction never speculate behind the unit door")))

(deftest unit-element-and-catalog-reconciliation-pay-only-for-active-content
  (let [renders (atom 0)
        catalog (datastar/unit-catalog
                  [{::datastar/coordinate
                    {:seon.agent/id agent-a
                     :seon.agent.ctx/name :seon.agent.ctx/transcript}
                    ::datastar/producer
                    #(do (swap! renders inc) [:div "rendered"])}])
        descriptor (first catalog)
        token (::datastar/token descriptor)]
    (is (vector? (datastar/unit-element descriptor false)))
    (is (zero? @renders) "an inactive unit is only a stub")
    (is (vector? (datastar/unit-element descriptor true)))
    (is (= 1 @renders) "activation crosses exactly one producer boundary")
    (reset! renders 0)
    (with-redefs [datastar/!feeds
                  (atom
                    (feed-registry
                      [(test-feed
                         "catalog-view"
                         {::datastar/catalog catalog
                          ::datastar/active-tokens #{token "removed-token"}})]))]
      (is (= #{token}
             (datastar/reconcile-view-catalog!
               {::datastar/view-id "catalog-view"
                ::datastar/catalog catalog})))
      (is (zero? @renders)
          "catalog refresh intersects active membership without rendering"))))

(deftest debug-page-get-is-a-shell-and-never-renders-debug-content
  (async done
    (-> (with-conn [agent-a]
          (fn [conn]
            (let [original db/*conn*
                  calls (atom 0)
                  [res observed] (response-probe)]
              (set! db/*conn* conn)
              (try
                (with-redefs [agent-debug/ctx-preview
                              (fn [_]
                                (swap! calls inc)
                                {:seon.agent.debug/ok? true
                                 :seon.render/text "must-not-render"})]
                  (debug/debug-page!
                    {:seon.http/node-res res
                     :path-params {:id agent-a}})
                  (is (= 200 (::response-status @observed)))
                  (is (str/includes? (::response-body @observed) "/debug/feed?view="))
                  (is (not (str/includes? (::response-body @observed)
                                          "must-not-render")))
                  (is (zero? @calls)
                      "opening the page shell performs no AI or HTML projection"))
                (finally
                  (set! db/*conn* original))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest debug-feed-projection-renders-ai-once-and-html-only-when-active
  (async done
    (-> (with-conn [agent-a]
          (fn [conn]
            (let [original db/*conn*
                  ai-renders (atom 0)
                  html-renders (atom 0)
                  !snapshot (atom {})]
              (set! db/*conn* conn)
              (try
                (with-redefs
                  [agent-debug/ctx-preview
                   (fn [request]
                     (is (= #{:ai} (:seon.render/formats request)))
                     (swap! ai-renders inc)
                     {:seon.agent.debug/ok? true
                      :seon.render/text "ai"
                      :seon.render/token-estimate 5
                      :seon.agent.ctx/rendered-blocks
                      [{:seon.agent.ctx/name :probe
                        :seon.agent.ctx/priority 1
                        :seon.render/text "ai"}]})
                   surface/surface-catalog
                   (fn [_ _]
                     [{::surface/selection "context-probe"
                       ::surface/label "probe"
                       ::surface/read-attrs #{}
                       ::surface/touch 0
                       ::surface/focus-touch 0}
                      {::surface/selection "canvas"
                       ::surface/label "canvas"
                       ::surface/read-attrs #{}
                       ::surface/touch 0
                       ::surface/focus-touch 0}])
                   surface/materialize-surface
                   (fn [_]
                     (swap! html-renders inc)
                     [:div "html"])]
                  (let [projection (@#'debug/debug-projection agent-a !snapshot)
                        catalog (:seon.web.debug/catalog projection)
                        exact-unit (some #(when (= -1
                                                    (get (::datastar/coordinate %)
                                                         :seon.web.debug/debug-block-index))
                                            %)
                                         catalog)
                        html-unit (some #(when (= :html
                                                   (get (::datastar/coordinate %)
                                                        :seon.web.debug/debug-format))
                                           %)
                                        catalog)]
                    (@#'debug/debug-app-view
                      agent-a "debug-test-view"
                      (:seon.web.debug/snapshot projection)
                      catalog #{})
                    (is (= 1 @ai-renders)
                        "AI blocks are projected once for prompt and diagnostics")
                    (is (zero? @html-renders)
                        "all closed HTML twins remain producer-free stubs")
                    (is (str/includes?
                          (html/->string (datastar/unit-element exact-unit true))
                          "ai")
                        "the exact assembled prompt is available behind one lazy unit")
                    (let [bar (get-in projection
                                      [:seon.web.debug/snapshot :context-bar])]
                      (is (= (::debug/total-tokens bar)
                             (reduce + 0 (map ::debug/tokens
                                              (::debug/segments bar))))
                          "the visible breakdown sums to the exact prompt total"))
                    (@#'debug/debug-app-view
                      agent-a "debug-test-view"
                      (:seon.web.debug/snapshot projection)
                      catalog #{(::datastar/token html-unit)})
                    (is (= 1 @html-renders)
                        "one active HTML twin invokes exactly one selected producer")))
                (finally
                  (set! db/*conn* original))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest exclusive-activation-deactivates-the-prior-unit
  (let [renders (atom 0)
        definition (fn [agent-id block-name]
                     {::datastar/coordinate
                      {:seon.agent/id agent-id
                       :seon.agent.ctx/name block-name}
                      ::datastar/exclusive-group :seon.web.unit.group/mainstage
                      ::datastar/producer #(swap! renders inc)})
        catalog (datastar/unit-catalog
                  [(definition agent-a :seon.agent.ctx/transcript)
                   (definition agent-a :seon.agent.ctx/plan)
                   (assoc (definition agent-b :seon.agent.ctx/namespaces)
                          ::datastar/exclusive-group
                          :seon.web.unit.group/other)])
        by-block (fn [block-name]
                   (some #(when (= block-name
                                   (get-in % [::datastar/coordinate
                                              :seon.agent.ctx/name]))
                            %)
                         catalog))
        prior (by-block :seon.agent.ctx/transcript)
        requested (by-block :seon.agent.ctx/plan)
        independent (by-block :seon.agent.ctx/namespaces)
        prior-token (::datastar/token prior)
        requested-token (::datastar/token requested)
        independent-token (::datastar/token independent)
        result (datastar/transition-active-set
                 {::datastar/catalog catalog
                  ::datastar/active-tokens #{prior-token independent-token}
                  ::datastar/token requested-token
                  ::datastar/active? true})]
    (is (= #{requested-token independent-token}
           (::datastar/active-tokens result))
        "activation swaps only the requested exclusive group")
    (is (= #{requested-token} (::datastar/activated-tokens result)))
    (is (= #{prior-token} (::datastar/deactivated-tokens result)))
    (is (zero? @renders) "an active-set transition invokes no producer")))

(deftest active-fingerprint-is-order-independent
  (let [coordinates [{:seon.agent/id agent-a
                      :seon.web.unit/face :seon.web.unit.face/expanded}
                     {:seon.agent/id agent-b
                      :seon.web.unit/face :seon.web.unit.face/compact}
                     {:seon.eval/id "eval-aaaa00001"
                      :seon.web.unit/face :seon.web.unit.face/detail}]
        tokens (mapv datastar/unit-token coordinates)]
    (is (= (datastar/active-fingerprint (into #{} tokens))
           (datastar/active-fingerprint (into #{} (reverse tokens))))
        "active membership, not discovery order, keys shared subscriptions")))

(deftest unknown-view-and-unit-never-invoke-producers
  (let [renders (atom 0)
        catalog (datastar/unit-catalog
                  [{::datastar/coordinate
                    {:seon.agent/id agent-a
                     :seon.agent.ctx/name :seon.agent.ctx/transcript}
                    ::datastar/producer #(do (swap! renders inc)
                                             [:div {:id "unexpected"}])}])
        known-token (::datastar/token (first catalog))
        [closed-res closed] (response-probe)
        [missing-res missing] (response-probe)]
    (with-redefs [datastar/!feeds
                  (atom
                    (feed-registry
                      [(test-feed
                         "known-view"
                         {::datastar/catalog catalog
                          :seon.web.feed/id
                          #uuid "00000000-0000-0000-0000-000000000001"})]))]
      (datastar/handle-view-unit!
        (unit-ring-request closed-res "closed-view" known-token "1"))
      (datastar/handle-view-unit!
        (unit-ring-request missing-res "known-view" "not-in-catalog" "1"))
      (is (= 410 (::response-status @closed))
          "a released view is gone rather than reconstructed from client input")
      (is (= 404 (::response-status @missing))
          "an opaque token must resolve through the trusted catalog")
      (is (zero? @renders) "neither miss crosses a producer boundary"))))

(deftest unit-activation-materializes-once-and-deactivation-restores-a-stub
  (let [renders (atom 0)
        definition (fn [block-name]
                     {::datastar/coordinate
                      {:seon.agent/id agent-a
                       :seon.agent.ctx/name block-name}
                      ::datastar/exclusive-group :seon.web.unit.group/mainstage
                      ::datastar/producer
                      #(do (swap! renders inc)
                           [:section {:data-produced true}])})
        catalog (datastar/unit-catalog
                  [(definition :seon.agent.ctx/transcript)
                   (definition :seon.agent.ctx/plan)])
        by-block (fn [block-name]
                   (some #(when (= block-name
                                   (get-in % [::datastar/coordinate
                                              :seon.agent.ctx/name]))
                            %)
                         catalog))
        prior (by-block :seon.agent.ctx/transcript)
        requested (by-block :seon.agent.ctx/plan)
        prior-token (::datastar/token prior)
        requested-token (::datastar/token requested)
        view-id "activation-view"
        feed-id #uuid "00000000-0000-0000-0000-000000000002"
        [active-res active-observed] (response-probe)
        [inactive-res inactive-observed] (response-probe)]
    (with-redefs [datastar/!feeds
                  (atom
                    (feed-registry
                      [(test-feed
                         view-id
                         {::datastar/catalog catalog
                          ::datastar/active-tokens #{prior-token}
                          :seon.web.feed/id feed-id})]))]
      (datastar/handle-view-unit!
        (unit-ring-request active-res view-id requested-token "1"))
      (let [body (::response-body @active-observed)]
        (is (= 200 (::response-status @active-observed)))
        (is (str/includes? (get (::response-headers @active-observed)
                                "Content-Type")
                           "text/html"))
        (is (= 1 @renders) "one activation invokes exactly one producer")
        (is (str/includes? body (::datastar/dom-id requested))
            "the active content is wrapped by its stable target")
        (is (str/includes? body (::datastar/dom-id prior))
            "the exclusive prior target returns as a sibling stub")
        (is (str/includes? body "data-seon-unit-active=\"false\"")
            "exclusive deactivation returns an inactive stable root")
        (is (= #{requested-token}
               (::datastar/active-tokens
                 (registry-view @datastar/!feeds view-id)))
            "the view owns the transitioned active set"))
      (datastar/handle-view-unit!
        (unit-ring-request inactive-res view-id requested-token "0"))
      (is (= 1 @renders) "deactivation never invokes a content producer")
      (is (str/includes? (::response-body @inactive-observed)
                         (::datastar/dom-id requested))
          "deactivation returns the same stable target")
      (is (str/includes? (::response-body @inactive-observed)
                         "data-seon-unit-active=\"false\"")
          "the returned target is an inactive stub, not stale content")
      (is (empty? (::datastar/active-tokens
                    (registry-view @datastar/!feeds view-id)))
          "deactivation removes future subscription work"))))

;; ============================================================
;; 2. patch-elements — the datastar-patch-elements wire framing (pure).
;; Assert the CONTRACT structurally (event line, per-HTML-line datalines,
;; blank-line terminator), never the exact event bytes.
;; ============================================================

(deftest patch-elements-frames-a-datastar-event
  (testing "single-line HTML → one data: elements dataline, event-framed"
    (let [ev        (datastar/patch-elements "<main id=\"app-view\">solo</main>")
          datalines (->> (str/split-lines ev)
                         (filter #(str/starts-with? % "data: elements ")))]
      (is (str/starts-with? ev "event: datastar-patch-elements\n")
          "begins with the datastar-patch-elements event line")
      (is (str/ends-with? ev "\n\n")
          "a blank line terminates the event")
      (is (= 1 (count datalines))
          "a one-line view yields exactly one data: elements dataline")))
  (testing "multi-line HTML → one data: elements dataline PER HTML line, verbatim"
    (let [src       "<ul>\n<li>a</li>\n<li>b</li>\n</ul>"
          lines     (str/split-lines src)
          ev        (datastar/patch-elements src)
          datalines (->> (str/split-lines ev)
                         (filter #(str/starts-with? % "data: elements ")))]
      (is (< 1 (count datalines))
          "multi-line HTML produces multiple data: elements lines")
      (is (= (count lines) (count datalines))
          "exactly one data: elements dataline per HTML line — the framing contract")
      (is (= (mapv #(str "data: elements " %) lines) datalines)
          "each HTML line is carried verbatim behind the data: elements prefix"))))

;; ============================================================
;; 2. agents-view = f(db) — APPEAR / VANISH, empty list, determinism.
;; Each test reads ONLY the db value it is handed; assert an agent's card by
;; its derived id marker (`app-agent-<id>`), never the rendered copy.
;; ============================================================

(deftest agents-view-has-a-row-for-every-agent
  (async done
    (-> (with-conn [agent-a agent-b]
          (fn [conn]
            (let [view (datastar/agents-view @conn)
                  s    (html/->string view)]
              (testing "the root is the #app-view morph target (datastar morphs by id)"
                (is (vector? view) "agents-view returns hiccup, not a thrown error")
                (is (= "app-view" (:id (second view)))
                    "root element carries id=view — the morph target the shim page declares"))
              (testing "every agent in the db gets a row keyed by its id"
                (is (str/includes? s "id=\"app-agents\"")
                    "the agents list has one stable morph anchor")
                (is (str/includes? s (str "app-agent-" agent-a)))
                (is (str/includes? s (str "app-agent-" agent-b)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest agents-view-appears-and-vanishes-with-the-db
  (async done
    (-> (with-conn [agent-a]
          (fn [conn]
            (let [s (html/->string (datastar/agents-view @conn))]
              (testing "A present, the absent B never appears"
                (is (str/includes? s (str "app-agent-" agent-a))
                    "the agent in the db is in the agents view")
                (is (not (str/includes? s agent-b))
                    "an agent NOT in the db is absent from the agents view")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest agents-view-empty-db-is-a-valid-view
  (async done
    (-> (with-conn []
          (fn [conn]
            (let [view (datastar/agents-view @conn)
                  s    (html/->string view)]
              (testing "an empty db still renders a valid, non-crashing view"
                (is (vector? view) "no agents → still a hiccup view, never a throw")
                (is (seq s) "the empty agents view renders a non-empty HTML string")
                (is (some? (re-find #"\d+\s+agent" s))
                    "the view surfaces an agent count even at zero")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest agents-view-is-deterministic-over-a-db-value
  (async done
    (-> (with-conn [agent-a agent-b]
          (fn [conn]
            (let [dbv @conn]
              (testing "same db value twice → identical output (pure fn of db)"
                (is (= (html/->string (datastar/agents-view dbv))
                       (html/->string (datastar/agents-view dbv))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; 3. NEVER-CRASH regression — the whole-view morph engine must survive an
;; under-populated or failing agent. (A bare agent does NOT currently make
;; `derive-state` throw — it derives :idle — but the streamer's per-card
;; guard is the load-bearing floor; the second test forces the throw so the
;; regression holds regardless of which future shape breaks derive-state.)
;; ============================================================

(deftest regression-bare-agent-never-crashes-agents-view
  ;; A bare agent (only :seon.agent/id — no run, no turn) must render in the
  ;; agents view. agents-view derives each card's FSM state; this is the never-crash
  ;; floor for the whole-view morph.
  (async done
    (-> (with-conn [agent-a]
          (fn [conn]
            (let [view (try (datastar/agents-view @conn)
                            (catch :default e {:threw (str e)}))
                  s    (html/->string view)]
              (testing "a bare agent renders WITHOUT throwing"
                (is (vector? view) "agents-view returned a view, not a thrown error")
                (is (str/includes? s (str "app-agent-" agent-a))
                    "the bare agent still gets its row")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest regression-agents-view-survives-a-throwing-derive-state
  ;; The load-bearing guard: a single agent whose derived-state read throws must
  ;; NOT abort the whole-view render. `agent-card` catches per-card (→ degraded
  ;; state), so the agents view — and every OTHER agent — still renders, and the
  ;; whole-view error fallback (`#app-error`) is NOT triggered. Force the
  ;; throw via with-redefs so the regression holds no matter what future shape
  ;; makes derive-state throw.
  (async done
    (-> (with-conn [agent-a]
          (fn [conn]
            (let [dbv @conn]
              (with-redefs [derive/derive-state
                            (fn [_ _] (throw (js/Error. "derive boom")))]
                (let [view (try (datastar/agents-view dbv)
                                (catch :default e {:propagated (str e)}))
                      s    (html/->string view)]
                  (testing "the per-card throw is CONTAINED — agents-view never propagates it"
                    (is (vector? view) "render did not propagate the per-card throw"))
                  (testing "the per-card guard (not the whole-view catch) handled it"
                    (is (str/includes? s (str "app-agent-" agent-a))
                        "the agent's card still renders despite its failed derive")
                    (is (not (str/includes? s "app-error"))
                        "whole-view error fallback NOT triggered — the per-card guard caught it")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; 4. broadcast! — zero open connections is a silent no-op.
;; The feed registry is isolated to an empty atom (with-redefs), so the live
;; pod's open feeds are untouched and the no-op never renders / reads *conn*.
;; ============================================================

(deftest broadcast-with-zero-connections-is-a-noop
  (with-redefs [datastar/!feeds (atom @#'datastar/empty-feed-registry)]
    (is (nil? (@#'datastar/broadcast!
                {:seon.db/db nil :seon.db/changed-attrs #{}}))
        "broadcast! over an empty feed registry is a silent no-op (no throw)")
    (is (empty? (::datastar/views @datastar/!feeds))
        "no connection was added or mutated")))

(deftest broadcast-renders-once-per-live-view
  (let [renders (atom 0)
        pushes (atom 0)
        render-change (fn [_subscription _change]
                        (swap! renders inc)
                        {::datastar/elements
                         [[:div {:id "first-target"} "one"]
                          [:div {:id "second-target"} "two"]]})]
    (with-redefs [datastar/!feeds
                  (atom
                    (feed-registry
                      [(test-feed
                         "view-a"
                         {:seon.web.feed/render-change render-change
                          :seon.web.feed/id
                          #uuid "00000000-0000-0000-0000-000000000001"})
                       (test-feed
                         "view-b"
                         {:seon.web.feed/render-change render-change
                          :seon.web.feed/id
                          #uuid "00000000-0000-0000-0000-000000000002"})]))
                  datastar/push-event! (fn [_ event]
                                         (is (str/includes? event "first-target"))
                                         (is (str/includes? event "second-target"))
                                         (swap! pushes inc))]
      (@#'datastar/broadcast!
        {:seon.db/db nil :seon.db/changed-attrs #{:example/value}})
      (is (= 1 @renders) "equivalent feeds share one render")
      (is (= 2 @pushes) "the shared event reaches every equivalent feed"))))

(deftest broadcast-separates-different-active-fingerprints
  (let [renders (atom 0)
        pushes (atom 0)
        render-change (fn [_subscription _change]
                        (swap! renders inc)
                        {::datastar/elements [[:div {:id "target"}]]})]
    (with-redefs [datastar/!feeds
                  (atom
                    (feed-registry
                      [(test-feed
                         "view-a"
                         {:seon.web.feed/render-change render-change
                          ::datastar/active-tokens #{"unit-a"}})
                       (test-feed
                         "view-b"
                         {:seon.web.feed/render-change render-change
                          ::datastar/active-tokens #{"unit-b"}})]))
                  datastar/push-event! (fn [_ _] (swap! pushes inc))]
      (@#'datastar/broadcast!
        {:seon.db/db nil :seon.db/changed-attrs #{:example/value}})
      (is (= 2 @renders)
          "views with different demanded units do not share a render")
      (is (= 2 @pushes) "each active fingerprint receives its own event"))))

(deftest broadcast-ignores-frozen-feeds
  (let [renders (atom 0)]
    (with-redefs [datastar/!feeds
                  (atom
                    (feed-registry
                      [(test-feed
                         "frozen-view"
                         {:seon.web.feed/key [:seon.web.feed/agent agent-a
                                              :seon.web.feed/as-of 1]
                          :seon.web.feed/live? false
                          :seon.web.feed/render-change
                          (fn [_subscription _change]
                            (swap! renders inc)
                            {::datastar/elements []})})]))]
      (@#'datastar/broadcast!
        {:seon.db/db nil :seon.db/changed-attrs #{:example/value}})
      (is (zero? @renders) "as-of feeds never rerender on current commits"))))

(deftest shared-subscription-keeps-new-dependencies-after-first-socket-closes
  (let [EventEmitter (.-EventEmitter (js/require "node:events"))
        PassThrough (.-PassThrough (js/require "node:stream"))
        req-a (new EventEmitter)
        req-b (new EventEmitter)
        res-a (new PassThrough)
        res-b (new PassThrough)
        _write-a (aset res-a "writeHead" (fn [_ _] nil))
        _write-b (aset res-b "writeHead" (fn [_ _] nil))
        renders (atom 0)
        pushes (atom [])
        initial-dependencies
        {:seon.web.datastar-test/structural-attrs #{:example/structure}
         :seon.web.datastar-test/surface-attrs #{:example/original}}
        render-change
        (fn [{dependencies ::datastar/dependencies}
             {attrs :seon.db/changed-attrs}]
          (swap! renders inc)
          (cond
            (some attrs
                  (:seon.web.datastar-test/structural-attrs dependencies))
            {::datastar/elements [[:div {:id "structure-target"}]]
             ::datastar/dependencies
             (assoc dependencies :seon.web.datastar-test/surface-attrs
                    #{:example/new-dependency})}

            (some attrs (:seon.web.datastar-test/surface-attrs dependencies))
            {::datastar/elements
             [[:div {:id "new-dependency-target"} "updated"]]}

            :else {::datastar/elements []}))
        definition
        (fn [view-id]
          {:seon.web.feed/key [:seon.web.feed/agent agent-a]
           :seon.web.feed/live? true
           :seon.web.feed/render-full (fn [] [:main {:id "app-view"}])
           :seon.web.feed/render-change render-change
           ::datastar/dependencies initial-dependencies
           ::datastar/view-id view-id})]
    (with-redefs [datastar/!feeds (atom @#'datastar/empty-feed-registry)
                  datastar/install! (fn [] nil)
                  datastar/uninstall! (fn [] nil)
                  datastar/push-full! (fn [_] nil)
                  datastar/push-event!
                  (fn [conn event]
                    (swap! pushes conj [(::datastar/view-id conn) event]))]
      (@#'datastar/open-feed! req-a res-a (definition "shared-a"))
      (@#'datastar/open-feed! req-b res-b (definition "shared-b"))
      (let [subscriptions (::datastar/subscriptions @datastar/!feeds)
            subscription (first (vals subscriptions))]
        (is (= 1 (count subscriptions))
            "equivalent views have one normalized render authority")
        (is (= #{"shared-a" "shared-b"}
               (::datastar/consumer-view-ids subscription))
            "both sockets consume that one authority"))

      (@#'datastar/broadcast!
        {:seon.db/db nil :seon.db/changed-attrs #{:example/structure}})
      (is (= 1 @renders) "the structural change renders once for both sockets")
      (is (= 2 (count @pushes)) "both equivalent sockets receive the shared patch")
      (let [subscription (first (vals (::datastar/subscriptions @datastar/!feeds)))]
        (is (= #{:example/new-dependency}
               (get (::datastar/dependencies subscription)
                    :seon.web.datastar-test/surface-attrs))
            "the structural transition replaces shared dependency authority"))

      (.emit req-a "close")
      (is (= 1 (count (::datastar/views @datastar/!feeds)))
          "closing the first socket leaves its peer open")
      (is (= 1 (count (::datastar/subscriptions @datastar/!feeds)))
          "the learned authority remains while one consumer exists")
      (reset! pushes [])

      (@#'datastar/broadcast!
        {:seon.db/db nil
         :seon.db/changed-attrs #{:example/new-dependency}})
      (is (= 2 @renders)
          "the later dependency change is routed through the retained authority")
      (is (= 1 (count @pushes)) "only the remaining socket receives the patch")
      (is (= "shared-b" (ffirst @pushes)))
      (is (str/includes? (second (first @pushes)) "new-dependency-target")
          "the new dependency produces the expected targeted morph")
      (.emit req-b "close"))))

(deftest equivalent-open-views-share-first-paint-until-a-transaction
  (let [renders (atom 0)
        pushes (atom [])
        feed-a (test-feed
                 "first-paint-a"
                 {:seon.web.feed/render-full
                  (fn []
                    (swap! renders inc)
                    [:main {:id "app-view"} "current"])})
        feed-b (assoc feed-a ::datastar/view-id "first-paint-b")
        registry (atom (feed-registry [feed-a feed-b]))
        conn-a (registry-view @registry "first-paint-a")
        conn-b (registry-view @registry "first-paint-b")]
    (with-redefs [datastar/!feeds registry
                  datastar/push-event!
                  (fn [conn event]
                    (swap! pushes conj [(::datastar/view-id conn) event]))
                  datastar/schedule-broadcast! (fn [_] nil)]
      (@#'datastar/push-full! conn-a)
      (@#'datastar/push-full! conn-b)
      (is (= 1 @renders)
          "equivalent sockets render one shared initial database view")
      (is (= 2 (count @pushes))
          "each socket receives the shared first paint")
      (@#'datastar/on-tx {:seon.db/changed-attrs #{:example/value}})
      (@#'datastar/push-full! conn-b)
      (is (= 2 @renders)
          "a transaction invalidates the shared first paint before reconnect"))))

(deftest obsolete-subscription-does-not-push-into-a-rebound-view
  (let [pushes (atom 0)
        initial-feed
        (test-feed
          "rebound-view"
          {:seon.web.feed/render-change
           (fn [_subscription _change]
             ;; Simulate a unit activation completing while this old render is
             ;; in flight. The view now consumes a different normalized
             ;; subscription before the old transition returns its patch.
             (swap! datastar/!feeds
                    @#'datastar/attach-feed
                    (test-feed
                      "rebound-view"
                      {::datastar/active-tokens #{"new-active-unit"}}))
             {::datastar/elements [[:div {:id "obsolete-target"}]]})})]
    (with-redefs [datastar/!feeds
                  (atom (feed-registry [initial-feed]))
                  datastar/push-event! (fn [_ _] (swap! pushes inc))]
      (@#'datastar/broadcast!
        {:seon.db/db nil :seon.db/changed-attrs #{:example/value}})
      (is (zero? @pushes)
          "an obsolete authority cannot push into its replacement socket")
      (is (= #{"new-active-unit"}
             (::datastar/active-tokens
               (registry-view @datastar/!feeds "rebound-view")))
          "the replacement view remains bound to its new subscription"))))

(deftest coalesced-change-preserves-the-complete-effective-window
  (let [structural-datom {:seon.db/e 1
                          :seon.db/a :example/structure
                          :seon.db/v :first
                          :seon.db/tx 10
                          :seon.db/added? true}
        first-value-datom {:seon.db/e 1
                           :seon.db/a :example/value
                           :seon.db/v 1
                           :seon.db/tx 10
                           :seon.db/added? true}
        latest-value-datom {:seon.db/e 1
                            :seon.db/a :example/value
                            :seon.db/v 2
                            :seon.db/tx 11
                            :seon.db/added? true}
        first-change {:seon.db/db-before :example.db/zero
                      :seon.db/db :example.db/one
                      :seon.db/datoms [structural-datom first-value-datom]
                      :seon.db/attr-index
                      {:example/structure [structural-datom]
                       :example/value [first-value-datom]}
                      :seon.db/changed-attrs #{:example/explicit-first}}
        latest-change {:seon.db/db-before :example.db/one
                       :seon.db/db :example.db/two
                       :seon.db/datoms [latest-value-datom]
                       :seon.db/attr-index
                       {:example/value [latest-value-datom]}
                       :seon.db/changed-attrs #{:example/explicit-latest}}]
    (with-redefs [agent-view/structural-change?
                  #(contains? % :example/structure)]
      (let [merged (@#'datastar/merge-change
                     (@#'datastar/merge-change nil first-change)
                     latest-change)]
        (is (= :example.db/zero (:seon.db/db-before merged))
            "the first available pre-commit value bounds the change window")
        (is (= :example.db/two (:seon.db/db merged))
            "the newest post-commit value is the render input")
        (is (= [structural-datom first-value-datom latest-value-datom]
               (:seon.db/datoms merged))
            "effective datoms remain ordered across the complete window")
        (is (= [first-value-datom latest-value-datom]
               (get-in merged [:seon.db/attr-index :example/value]))
            "per-attribute evidence composes rather than overwrites")
        (is (= #{:example/structure :example/value
                 :example/explicit-first :example/explicit-latest}
               (:seon.db/changed-attrs merged))
            "explicit and datom-derived changed attributes are unioned")
        (is (true? (:seon.web.broadcast/structural? merged))
            "a later ordinary commit cannot downgrade structural work")))))

(defn- test-datom
  "One effective datom-shaped value for coalescer tests."
  [entity attr value tx added?]
  {:seon.db/e entity
   :seon.db/a attr
   :seon.db/v value
   :seon.db/tx tx
   :seon.db/added? added?})

(defn- running-eval-change
  "One eval-record commit with its turn component link and ancillary tee."
  [n db-before db-after]
  (let [turn-eid 700
        eval-eid (+ 800 n)
        tx (+ 900 n)
        eval-id-datom (test-datom eval-eid :seon.eval/id
                                  (str "eval-" n) tx true)
        turn-link-datom (test-datom turn-eid :seon.agent.turn/evals
                                   eval-eid tx true)
        tee-datom (test-datom (+ 1000 n) :seon.fn/source
                              (str "(defn f" n " [])") tx true)]
    {:seon.db/db-before db-before
     :seon.db/db db-after
     :seon.db/datoms [eval-id-datom turn-link-datom tee-datom]
     :seon.db/attr-index
     {:seon.eval/id [eval-id-datom]
      :seon.agent.turn/evals [turn-link-datom]
      :seon.fn/source [tee-datom]}}))

(defn- running-turn-db
  "Fresh immutable database value with only a running turn status installed."
  []
  (-> (datahike/empty-db
        {:seon.agent.turn/status {}})
      (d/db-with [[:db/add 700 :seon.agent.turn/status :running]])))

(deftest running-eval-commits-wait-for-the-terminal-turn-frame
  (let [clock (atom 0)
        next-handle (atom 0)
        scheduled (atom [])
        cleared (atom [])
        broadcasts (atom [])
        running-db (running-turn-db)
        terminal-datom (test-datom 700 :seon.agent.turn/status
                                   :done 2000 true)
        terminal-change
        {:seon.db/db-before :example.db/eval-5
         :seon.db/db :example.db/terminal
         :seon.db/datoms [terminal-datom]
         :seon.db/attr-index {:seon.agent.turn/status [terminal-datom]}}]
    (with-redefs [datastar/!coalescer
                  (atom @#'datastar/empty-coalescer)
                  datastar/monotonic-ms (fn [] @clock)
                  datastar/set-broadcast-timeout!
                  (fn [callback delay-ms]
                    (let [handle (swap! next-handle inc)]
                      (swap! scheduled conj [handle delay-ms callback])
                      handle))
                  datastar/clear-broadcast-timeout!
                  #(swap! cleared conj %)
                  datastar/broadcast! #(swap! broadcasts conj %)]
      (doseq [n (range 1 6)]
        (reset! clock (* n 500))
        (@#'datastar/schedule-broadcast!
          (running-eval-change
            n
            (if (= n 1) :example.db/zero
                running-db)
            running-db)))
      (is (empty? @scheduled)
          "running eval records retain evidence without creating timers")
      (is (empty? @broadcasts)
          "many durable eval records do not invoke presentation work")
      (is (identical? running-db
                      (get-in @datastar/!coalescer
                              [::datastar/pending-change :seon.db/db]))
          "the held window tracks the latest committed database value")

      (reset! clock 3000)
      (@#'datastar/schedule-broadcast! terminal-change)
      (is (= 1 (count @scheduled))
          "the terminal turn fact schedules exactly one final frame")
      (is (empty? @cleared)
          "no nonexistent eval timer was manufactured or cancelled")
      ((nth (first @scheduled) 2))
      (is (= 1 (count @broadcasts))
          "the terminal frame drains all held eval evidence once")
      (let [broadcast (first @broadcasts)]
        (is (= :example.db/zero (:seon.db/db-before broadcast))
            "the final frame retains the earliest pre-eval database value")
        (is (= :example.db/terminal (:seon.db/db broadcast))
            "the final frame renders the terminal database value")
        (is (= #{:seon.eval/id :seon.agent.turn/evals
                 :seon.fn/source :seon.agent.turn/status}
               (:seon.db/changed-attrs broadcast))
            "the final frame contains every held dependency signal")))))

(deftest running-eval-never-postpones-an-existing-frame
  (let [clock (atom 0)
        next-handle (atom 0)
        scheduled (atom [])
        cleared (atom [])
        broadcasts (atom [])
        running-db (running-turn-db)
        domain-datom (test-datom 600 :my.note/text "before" 10 true)
        domain-change {:seon.db/db-before :example.db/zero
                       :seon.db/db :example.db/domain
                       :seon.db/datoms [domain-datom]
                       :seon.db/attr-index {:my.note/text [domain-datom]}}]
    (with-redefs [datastar/!coalescer
                  (atom @#'datastar/empty-coalescer)
                  datastar/monotonic-ms (fn [] @clock)
                  datastar/set-broadcast-timeout!
                  (fn [callback delay-ms]
                    (let [handle (swap! next-handle inc)]
                      (swap! scheduled conj [handle delay-ms callback])
                      handle))
                  datastar/clear-broadcast-timeout!
                  #(swap! cleared conj %)
                  datastar/broadcast! #(swap! broadcasts conj %)]
      (@#'datastar/schedule-broadcast! domain-change)
      (let [owned-timer (::datastar/timer @datastar/!coalescer)
            owned-due (::datastar/due-at @datastar/!coalescer)]
        (reset! clock 5)
        (@#'datastar/schedule-broadcast!
          (running-eval-change 1 :example.db/domain running-db))
        (is (= 1 (count @scheduled))
            "an eval commit does not replace the already-earned timer")
        (is (empty? @cleared)
            "the existing timer is neither cancelled nor postponed")
        (is (= owned-timer (::datastar/timer @datastar/!coalescer)))
        (is (= owned-due (::datastar/due-at @datastar/!coalescer)))
        ((nth (first @scheduled) 2)))
      (is (= 1 (count @broadcasts)))
      (is (identical? running-db
                      (:seon.db/db (first @broadcasts)))
          "the unchanged timer still renders the latest committed state")
      (is (= #{:my.note/text :seon.eval/id
               :seon.agent.turn/evals :seon.fn/source}
             (:seon.db/changed-attrs (first @broadcasts)))
          "the unchanged timer owns both deliberate and eval evidence"))))

(deftest intervening-non-eval-change-schedules-and-carries-held-evidence
  (let [clock (atom 0)
        next-handle (atom 0)
        scheduled (atom [])
        broadcasts (atom [])
        running-db (running-turn-db)
        canvas-datom (test-datom 600 :seon.render.canvas/content
                                 "my.canvas/render" 30 true)
        canvas-change {:seon.db/db-before :example.db/eval-1
                       :seon.db/db :example.db/canvas
                       :seon.db/datoms [canvas-datom]
                       :seon.db/attr-index
                       {:seon.render.canvas/content [canvas-datom]}}]
    (with-redefs [datastar/!coalescer
                  (atom @#'datastar/empty-coalescer)
                  datastar/monotonic-ms (fn [] @clock)
                  datastar/set-broadcast-timeout!
                  (fn [callback delay-ms]
                    (let [handle (swap! next-handle inc)]
                      (swap! scheduled conj [handle delay-ms callback])
                      handle))
                  datastar/clear-broadcast-timeout! (fn [_] nil)
                  datastar/broadcast! #(swap! broadcasts conj %)]
      (@#'datastar/schedule-broadcast!
        (running-eval-change 1 :example.db/zero running-db))
      (is (empty? @scheduled) "the eval record starts as held evidence")

      (reset! clock 100)
      (@#'datastar/schedule-broadcast! canvas-change)
      (is (= 1 (count @scheduled))
          "a deliberate canvas transaction promptly earns a frame")
      (is (= 16 (second (first @scheduled)))
          "the existing ordinary settle policy remains the scheduling path")

      (reset! clock 105)
      (@#'datastar/schedule-broadcast!
        (running-eval-change 2 :example.db/canvas running-db))
      (is (= 1 (count @scheduled))
          "later eval bookkeeping cannot postpone the deliberate frame")
      ((nth (first @scheduled) 2))
      (is (= 1 (count @broadcasts)))
      (let [broadcast (first @broadcasts)]
        (is (= :example.db/zero (:seon.db/db-before broadcast)))
        (is (identical? running-db (:seon.db/db broadcast)))
        (is (= #{:seon.eval/id :seon.agent.turn/evals
                 :seon.fn/source :seon.render.canvas/content}
               (:seon.db/changed-attrs broadcast))
            "the deliberate frame preserves evidence from both eval sides")))))

(deftest continuous-structural-commits-cannot-move-the-maximum-deadline
  (let [clock (atom 0)
        next-handle (atom 0)
        scheduled (atom [])
        cleared (atom [])
        broadcasts (atom [])
        change (fn [n attr]
                 {:seon.db/db-before (keyword "example.db" (str "before-" n))
                  :seon.db/db (keyword "example.db" (str "after-" n))
                  :seon.db/attr-index {attr []}})]
    (with-redefs [datastar/!coalescer
                  (atom @#'datastar/empty-coalescer)
                  datastar/monotonic-ms (fn [] @clock)
                  datastar/set-broadcast-timeout!
                  (fn [callback delay-ms]
                    (let [handle (swap! next-handle inc)]
                      (swap! scheduled conj [handle delay-ms callback])
                      handle))
                  datastar/clear-broadcast-timeout!
                  #(swap! cleared conj %)
                  datastar/broadcast! #(swap! broadcasts conj %)
                  agent-view/structural-change? (constantly true)]
      (@#'datastar/schedule-broadcast! (change 1 :example/first))
      (is (= 300 (second (first @scheduled)))
          "the first structural commit gets the normal trailing delay")
      (is (= 0 (::datastar/enqueued-at @datastar/!coalescer))
          "the first enqueue fixes the origin of one maximum deadline")
      (is (= 300 (::datastar/due-at @datastar/!coalescer))
          "the initial timer remains a trailing debounce inside that bound")

      (reset! clock 250)
      (@#'datastar/schedule-broadcast! (change 2 :example/second))
      (is (= [300 250] (mapv second @scheduled))
          "the trailing timer may move later, but only to the fixed deadline")
      (is (= 500 (::datastar/due-at @datastar/!coalescer))
          "the rescheduled timer reaches, but never exceeds, the maximum")
      (is (= [1] @cleared) "the replaced timer is cancelled exactly once")

      (reset! clock 490)
      (@#'datastar/schedule-broadcast! (change 3 :example/third))
      (is (= 2 (count @scheduled))
          "continuous work at the cap keeps, rather than recreates, its timer")
      (is (= [1] @cleared)
          "the maximum-deadline timer cannot be cleared into starvation")

      ((nth (first @scheduled) 2))
      (is (empty? @broadcasts) "a queued stale timer cannot drain new work")
      (is (= 2 (::datastar/timer @datastar/!coalescer))
          "stale callbacks leave current timer ownership intact")

      ((nth (second @scheduled) 2))
      (is (= 1 (count @broadcasts))
          "the current deadline drains the whole burst exactly once")
      (is (= #{:example/first :example/second :example/third}
             (:seon.db/changed-attrs (first @broadcasts)))
          "the drained render sees evidence from every coalesced commit")
      (is (= :example.db/before-1
             (:seon.db/db-before (first @broadcasts)))
          "the drained window keeps the earliest pre-commit value")
      (is (= :example.db/after-3 (:seon.db/db (first @broadcasts)))
          "the drained window renders the latest post-commit value")
      (is (= @#'datastar/empty-coalescer @datastar/!coalescer)
          "draining atomically returns the lifecycle state to idle"))))

(deftest listener-lifecycle-uses-the-datahike-key-and-cleans-owned-time
  (let [listen-requests (atom [])
        unlisten-requests (atom [])
        cleared (atom [])
        pending-state {::datastar/pending-change
                       {:seon.db/db :example.db/pending}
                       ::datastar/timer :example.timer/pending
                       ::datastar/timer-token #uuid "00000000-0000-0000-0000-000000000099"
                       ::datastar/enqueued-at 0
                       ::datastar/due-at 500}]
    (with-redefs [datastar/!feeds
                  (atom @#'datastar/empty-feed-registry)
                  datastar/!coalescer (atom pending-state)
                  db/listen! #(do (swap! listen-requests conj %) {:seon.db/key ::datastar/views})
                  db/unlisten! #(do (swap! unlisten-requests conj %) {:seon.db/ok? true})
                  datastar/clear-broadcast-timeout! #(swap! cleared conj %)]
      (datastar/install!)
      (datastar/install!)
      (is (= [::datastar/views ::datastar/views]
             (mapv :seon.db/key @listen-requests))
          "reinstallation delegates idempotence to one stable Datahike key")

      (datastar/before-reload)
      (is (= [{:seon.db/key ::datastar/views}] @unlisten-requests)
          "reload removes that same connection-owned listener")
      (is (= [:example.timer/pending] @cleared)
          "uninstall cancels the timer owned by the pending state")
      (is (= @#'datastar/empty-coalescer @datastar/!coalescer)
          "uninstall cannot leave pending evidence without a timer")

      (datastar/after-reload)
      (is (= 2 (count @listen-requests))
          "an empty feed registry does no work after reload")

      (reset! datastar/!feeds
              (feed-registry [(test-feed "surviving-view" {})]))
      (datastar/after-reload)
      (is (= 3 (count @listen-requests))
          "a surviving feed restores the stable listener after reload")
      (is (= ::datastar/views
             (:seon.db/key (last @listen-requests)))
          "hot reload does not create a second listener identity"))))

(deftest backpressured-feed-retains-only-latest-event
  (let [writes (atom [])
        on-drain (atom nil)
        gz #js {:writableEnded false
                :write (fn [event] (swap! writes conj event) false)
                :flush (fn [_] nil)
                :once (fn [event-name handler]
                        (when (= event-name "drain")
                          (reset! on-drain handler)))}
        res #js {:writableEnded false}
        pending (atom nil)
        draining? (atom false)
        conn {:seon.web.feed/gzip gz
              :seon.web.feed/response res
              :seon.web.feed/pending-event pending
              :seon.web.feed/draining? draining?}]
    (@#'datastar/push-event! conn "first")
    (@#'datastar/push-event! conn "obsolete")
    (@#'datastar/push-event! conn "latest")
    (is (= ["first"] @writes) "pressure prevents an unbounded write queue")
    (is (= "latest" @pending) "new activity replaces stale pending activity")
    (@on-drain)
    (is (= ["first" "latest"] @writes)
        "drain sends only the newest derived state")))

(deftest reconnect-replaces-stale-ownership-and-final-close-releases-view
  (let [EventEmitter (.-EventEmitter (js/require "node:events"))
        PassThrough (.-PassThrough (js/require "node:stream"))
        req-a (new EventEmitter)
        req-b (new EventEmitter)
        res-a (new PassThrough)
        res-b (new PassThrough)
        _write-a (aset res-a "writeHead" (fn [_ _] nil))
        _write-b (aset res-b "writeHead" (fn [_ _] nil))
        view-id "reconnect-view"
        catalog (datastar/unit-catalog
                  [{::datastar/coordinate
                    {:seon.agent/id agent-a
                     :seon.web.unit/face :seon.web.unit.face/expanded}
                    ::datastar/producer (fn [] [:div])}])
        token (::datastar/token (first catalog))
        uninstalls (atom 0)
        base {:seon.web.feed/key [:seon.web.feed/agent agent-a]
              :seon.web.feed/live? true
              :seon.web.feed/render-full (fn [] [:main {:id "app-view"}])
              :seon.web.feed/render-change
              (fn [_subscription _change] {::datastar/elements []})
              ::datastar/view-id view-id}]
    (with-redefs [datastar/!feeds (atom @#'datastar/empty-feed-registry)
                  datastar/install! (fn [] nil)
                  datastar/uninstall! (fn [] (swap! uninstalls inc))
                  datastar/push-full! (fn [_] nil)]
      (@#'datastar/open-feed!
        req-a res-a (assoc base
                           ::datastar/catalog catalog
                           ::datastar/active-tokens #{token}))
      (let [first-owner (:seon.web.feed/id
                          (registry-view @datastar/!feeds view-id))]
        (@#'datastar/open-feed! req-b res-b base)
        (let [second-owner (:seon.web.feed/id
                             (registry-view @datastar/!feeds view-id))]
          (is (= 1 (count (::datastar/views @datastar/!feeds)))
              "one view id has exactly one current socket owner")
          (is (not= first-owner second-owner)
              "reconnect replaces rather than appends socket ownership")
          (is (= #{token}
                 (::datastar/active-tokens
                   (registry-view @datastar/!feeds view-id)))
              "reconnect inherits the view's active state")
          (.emit req-a "close")
          (is (= second-owner
                 (:seon.web.feed/id
                   (registry-view @datastar/!feeds view-id)))
              "a stale close cannot release the replacement")
          (.emit req-b "close")
          (is (empty? (::datastar/views @datastar/!feeds))
              "closing the final owner releases catalog and active state")
          (is (empty? (::datastar/subscriptions @datastar/!feeds))
              "closing the final consumer releases normalized authority")
          (is (= 1 @uninstalls)
              "the final close releases the otherwise-idle tx listener"))))))

;; ============================================================
;; 5. PER-CONNECTION views — the streamer renders EACH connection's OWN
;; bound view-fn (the agents view and an /agent/{id} view both ride the
;; same broadcast). `view-fn-patch` is the per-conn render core: a bound
;; thunk → its own morph patch, GUARDED so one bad view can't abort the
;; broadcast. (The full gzip-stream path needs a node socket, so the
;; mechanism is proven here at the thunk level.)
;; ============================================================

;; ============================================================
;; 4b. Agent rows carry the agent's canvas COMPACT FACE (2026-07-11):
;; each non-root card embeds `render/render-agent-canvas`'s hiccup (the
;; agent's canvas — pinned content, else derived, else the welcome
;; card) clipped + stretch-linked to `/agent/{id}`. Root is skipped by
;; design (root's canvas is the `/` dashboard, which itself renders this
;; agents view — embedding would recurse). Assert the MECHANISM (the preview
;; wrapper's stable id, presence for a bare agent via the welcome
;; fallback, absence for root) — not the rendered face.
;; ============================================================

(deftest agents-view-row-carries-the-agent-canvas-compact-face
  (async done
    (-> (with-conn [agent-a "root"]
          (fn [conn]
            (let [s (html/->string (datastar/agents-view @conn))]
              (testing "both agents render a row"
                (is (str/includes? s (str "app-agent-" agent-a)))
                (is (str/includes? s "app-agent-root")))
              (testing "a bare agent still gets a compact face (welcome fallback)"
                (is (str/includes? s (str "app-agent-" agent-a "-canvas"))
                    "the preview wrapper renders with its stable DOM id"))
              (testing "root gets NO embedded face (its canvas is / itself)"
                (is (not (str/includes? s "app-agent-root-canvas"))
                    "no preview wrapper for root")))))
        (.then done))))

;; ============================================================
;; 5b. The shim's feed OPENER lives OUTSIDE the morph target (2026-07-11
;; regression): a `data-init` ON `#app-view` is stripped by the feed's own
;; first whole-element morph (the pushed `[:main#app-view …]` carries no
;; data-init), so datastar cancels the stream ~100ms after open and the
;; agents page goes permanently dead. The opener must be a SIBLING of
;; `<main id="app-view">`.
;; ============================================================

(deftest regression-shim-feed-opener-is-outside-the-morph-target
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (let [orig db/*conn*]
              (set! db/*conn* conn)
              (let [view (@#'datastar/agents-page-html)]
                (testing "#app-view itself carries NO data-init (the morph would strip it)"
                  (is (str/includes? view "<main id=\"app-view\">")
                      "the morph target is a bare <main id=app-view>"))
                (testing "the feed opener is a sibling element carrying the data-init"
                  (is (str/includes? view "app-feed-opener")
                      "the opener div is present")
                  (is (str/includes? view "@get('/agents/feed'")
                      "the opener opens /agents/feed")))
              (set! db/*conn* orig)
              (done)))))))

;; ============================================================
;; 6. The view SHIM heads route through the seon.web.brand seams (#13) —
;; the page users actually navigate to (/agents and /agent/{id}) must carry
;; the downstream brand the same way the debug view does: SEON_BRAND_CSS
;; inlined in the <head>, the brand NAME in the <title>, and `data-theme`
;; from the brand row. Absent brand row + env → the shipped seon defaults.
;; Assert the brand MECHANISM (css present, name in title, theme attr) — not
;; the surrounding shim markup, which is a refactoring surface.
;; ============================================================

(deftest view-shim-heads-route-through-the-brand-seams
  (async done
    (let [env      (.. js/process -env)
          fs       (js/require "fs")
          css-path "tmp/datastar-brand-shim-test.css"]
      (-> (client/open-agent-conn!)
          (.then
            (fn [conn]
              (let [orig db/*conn*]
                (set! db/*conn* conn)
                ;; --- DEFAULT (unbranded): no brand row, no SEON_BRAND_CSS.
                (js-delete env "SEON_BRAND_CSS")
                (let [view (@#'datastar/agents-page-html)
                      agent (@#'datastar/agent-page-html agent-a)]
                  (testing "unbranded → seon defaults, NO brand <style> inlined"
                    (is (str/includes? view "data-theme=\"phosphor\"")
                        "the default phosphor theme rides the <html> tag")
                    (is (str/includes? view "<title>seon · agents</title>")
                        "the agents title falls back to the seon brand name")
                    (is (str/includes? agent
                                       (str "<title>seon · agent " agent-a "</title>"))
                        "the agent title falls back to the seon brand name")
                    (is (str/includes? view "/css/output.css")
                        "output.css is still linked on the default path")))
                ;; --- BRANDED: a brand row + a SEON_BRAND_CSS file (cyan token).
                (.writeFileSync fs css-path ":root{--color-amber-400:#38bdf8;}")
                (aset env "SEON_BRAND_CSS" css-path)
                (-> (db/transact!
                      {:seon.db/conn    conn
                       :seon.db/tx-data [{::brand/id    "brand"
                                          ::brand/name  "Acme"
                                          ::brand/theme "midnight"}]})
                    (.then
                      (fn [_]
                        (let [view (@#'datastar/agents-page-html)
                              agent (@#'datastar/agent-page-html agent-a)]
                          (testing "branded → SEON_BRAND_CSS inlined, brand name + theme in head"
                            (is (str/includes? view "#38bdf8")
                                "the SEON_BRAND_CSS content is inlined in the agents <head>")
                            (is (str/includes? view "Acme · agents")
                                "the brand name flows into the agents <title>")
                            (is (str/includes? view "data-theme=\"midnight\"")
                                "the brand theme rides the <html> tag")
                            (is (str/includes? agent "#38bdf8")
                                "the SEON_BRAND_CSS content is inlined in the agent <head>")
                            (is (str/includes? agent (str "Acme · agent " agent-a))
                                "the brand name flows into the agent <title>")))))
                    (.finally
                      (fn []
                        (set! db/*conn* orig)
                        (js-delete env "SEON_BRAND_CSS")
                        (try (.unlinkSync fs css-path) (catch :default _ nil))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest view-fn-patch-renders-the-bound-view-and-is-guarded
  (testing "a connection's bound view-fn is rendered into its OWN morph patch"
    (let [patch (@#'datastar/view-fn-patch
                 (fn [] [:main {:id "app-view"} [:div {:id "x"} "BOUND-VIEW"]]))]
      (is (str/starts-with? patch "event: datastar-patch-elements\n")
          "the bound view is framed as a datastar-patch-elements morph")
      (is (str/includes? patch "BOUND-VIEW")
          "the connection's OWN view content rides in its patch")))
  (testing "two connections' views differ — each renders its own bound thunk"
    (let [pa (@#'datastar/view-fn-patch (fn [] [:main {:id "app-view"} "VIEW-A"]))
          pb (@#'datastar/view-fn-patch (fn [] [:main {:id "app-view"} "VIEW-B"]))]
      (is (and (str/includes? pa "VIEW-A") (not (str/includes? pa "VIEW-B")))
          "connection A's patch carries only A's view")
      (is (and (str/includes? pb "VIEW-B") (not (str/includes? pb "VIEW-A")))
          "connection B's patch carries only B's view")))
  (testing "a throwing view-fn degrades to a #app-error morph — never throws"
    (let [patch (@#'datastar/view-fn-patch (fn [] (throw (js/Error. "view boom"))))]
      (is (str/includes? patch "app-error")
          "a per-connection render failure degrades to a visible error, not a crash"))))
