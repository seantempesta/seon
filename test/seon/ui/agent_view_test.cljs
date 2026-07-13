(ns seon.ui.agent-view-test
  "Structural tests for the DB-derived per-agent view."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent.ctx :as agent-ctx]
    [seon.agent.ctx.render-fns :as render-fns]
    [seon.client :as client]
    [seon.db :as db]
    [seon.error :as err]
    [seon.render :as render]
    [seon.ui.agent-view :as agent-view]
    [seon.ui.html :as html]
    [seon.web.view-unit :as view-unit]))

(def ^:private agent-a "view-aaaa00001")
(def ^:private agent-b "view-bbbb00002")

(defn- selection-for [block-name]
  (str "context-"
       (view-unit/coordinate-token {:seon.agent.ctx/name block-name})))

(defn- catalog-selection [dbv agent-id block-name]
  (::agent-view/selection
    (some #(when (= block-name (:seon.agent.ctx/name %)) %)
          (agent-view/surface-catalog dbv agent-id))))

(defn- with-agents [agents body]
  (-> (client/open-agent-conn!)
      (.then
        (fn [conn]
          (-> (db/transact!
                {:seon.db/conn conn
                 :seon.db/tx-data
                 (mapv (fn [[id blocks]]
                         {:seon.agent/id id :seon.agent/ctx blocks})
                       agents)})
              (.then (fn [_] (body conn))))))))

(deftest surface-catalog-is-cheap-and-omits-ai-only-context
  (async done
    (-> (with-agents
          [[agent-a
            [{:seon.agent.ctx/name :plan
              :seon.agent.ctx/priority 10
              :seon.render/html [:div "plan"]}
             {:seon.agent.ctx/name :notes
              :seon.agent.ctx/priority 20
              :seon.render/ai "agent-only"}]]]
          (fn [conn]
            (let [html-renders (atom 0)
                  canvas-renders (atom 0)
                  context-roots (atom 0)]
              (with-redefs
                [agent-ctx/context-root
                 (fn [_]
                   (swap! context-roots inc)
                   (throw (js/Error. "catalog loaded the context root")))
                 render/render
                 (fn [& _] (swap! html-renders inc) [:div])
                 render/render-agent-canvas
                 (fn [_]
                   (swap! canvas-renders inc)
                   {:seon.render/hiccup [:div]})]
                (let [catalog (agent-view/surface-catalog @conn agent-a)
                      selections (into #{} (map ::agent-view/selection) catalog)
                      plan (some #(when (= :plan
                                           (:seon.agent.ctx/name %))
                                    %)
                                 catalog)
                      canvas (some #(when (= "canvas"
                                             (::agent-view/selection %))
                                      %)
                                   catalog)
                      common-keys #{::agent-view/selection
                                    ::agent-view/label
                                    ::agent-view/read-attrs
                                    ::agent-view/touch
                                    ::agent-view/focus-touch}]
                  (is (= #{"canvas" (selection-for :plan)} selections))
                  (is (= (conj common-keys :seon.agent.ctx/name)
                         (set (keys plan))))
                  (is (= common-keys (set (keys canvas))))
                  (is (= :plan (:seon.agent.ctx/name plan)))
                  (is (= #{} (::agent-view/read-attrs plan)))
                  (is (zero? @context-roots))
                  (is (zero? @html-renders))
                  (is (zero? @canvas-renders)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest surface-catalog-preserves-symbolic-renderer-dependencies
  (async done
    (-> (with-agents
          [[agent-a
            [{:seon.agent.ctx/name :plan
              :seon.agent.ctx/priority 10
              :seon.render/html 'my.agent.view/plan}]]]
          (fn [conn]
            (-> (db/with-agent
                  agent-a
                  (fn []
                    (db/transact!
                      {:seon.db/conn conn
                       :seon.db/tx-data
                       [{:seon.fn/sym "my.agent.view/plan"
                         :seon.fn/source
                         "(defn plan [_] {:seon.render/hiccup [:div]})"
                         :seon.fn/read-attrs [:seon.agent/purpose]}
                        {:seon.agent/id agent-a
                         :seon.agent/purpose "dependency touch"}]})))
                (.then
                  (fn [result]
                    (is (true? (:seon.db/ok? result)))
                    (let [surface
                          (some #(when (= :plan (:seon.agent.ctx/name %)) %)
                                (agent-view/surface-catalog @conn agent-a))]
                      (is (contains? (::agent-view/read-attrs surface)
                                     :seon.agent/purpose))
                      (is (pos? (::agent-view/touch surface)))
                      (is (= (::agent-view/touch surface)
                             (::agent-view/focus-touch surface)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest surface-selection-preserves-every-legal-keyword-identity
  (async done
    (-> (with-agents
          [[agent-a
            [{:seon.agent.ctx/name :foo/bar
              :seon.agent.ctx/priority 10
              :seon.render/html [:div "NAMESPACED"]}
             {:seon.agent.ctx/name :foo-bar
              :seon.agent.ctx/priority 20
              :seon.render/html [:div "UNQUALIFIED"]}]]]
          (fn [conn]
            (let [dbv @conn
                  catalog (agent-view/surface-catalog dbv agent-a)
                  by-name (into {}
                                (keep (fn [surface]
                                        (when-let [block-name
                                                   (:seon.agent.ctx/name surface)]
                                          [block-name surface])))
                                catalog)
                  namespaced (get by-name :foo/bar)
                  unqualified (get by-name :foo-bar)
                  namespaced-selection (::agent-view/selection namespaced)
                  unqualified-selection (::agent-view/selection unqualified)]
              (is (= #{:foo/bar :foo-bar} (set (keys by-name))))
              (is (not= namespaced-selection unqualified-selection)
                  "distinct legal keywords never share browser identity")
              (is (= [:div "NAMESPACED"]
                     (agent-view/materialize-surface
                       {:seon.db/db dbv
                        :seon.agent/id agent-a
                        ::agent-view/selection namespaced-selection
                        ::agent-view/face :expanded})))
              (is (= [:div "UNQUALIFIED"]
                     (agent-view/materialize-surface
                       {:seon.db/db dbv
                        :seon.agent/id agent-a
                        ::agent-view/selection unqualified-selection
                        ::agent-view/face :expanded}))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest surface-selection-distinguishes-ambiguous-keyword-printing
  (let [left (keyword "a/b" "c")
        right (keyword "a" "b/c")]
    (is (not= left right))
    (is (= (pr-str left) (pr-str right))
        "the historical printed-form coordinate cannot distinguish this pair")
    (is (not= (selection-for left) (selection-for right))
        "the canonical coordinate preserves keyword namespace and name")))

(deftest materialize-surface-invokes-only-the-selected-renderer-once
  (async done
    (-> (with-agents
          [[agent-a []]]
          (fn [conn]
            (let [html-renders (atom [])
                  canvas-renders (atom 0)
                  dual-face
                  [:article {:class "seon-tile"}
                   [:section {:class "seon-tile-compact"} [:p "compact"]]
                   [:section {:class "seon-tile-expanded"} [:p "expanded"]]]]
              (with-redefs
                [agent-ctx/context-root
                 (fn [_]
                   {:seon.agent/entity {:seon.agent/id agent-a}
                    :seon.agent.ctx/children
                    [{:seon.agent.ctx/name :plan
                      :seon.render/html 'my.agent.view/plan}
                     {:seon.agent.ctx/name :transcript
                      :seon.render/html 'my.agent.view/transcript}]})
                 render/render
                 (fn [_ _ node]
                   (swap! html-renders conj (:seon.agent.ctx/name node))
                   dual-face)
                 render/render-agent-canvas
                 (fn [_]
                   (swap! canvas-renders inc)
                   {:seon.render/hiccup dual-face})]
                (let [expanded
                      (agent-view/materialize-surface
                        {:seon.db/db @conn
                         :seon.agent/id agent-a
                         ::agent-view/selection (selection-for :plan)
                         ::agent-view/face :expanded})]
                  (is (= [:section {:class "seon-tile-expanded"}
                          [:p "expanded"]]
                         expanded))
                  (is (= [:plan] @html-renders))
                  (is (zero? @canvas-renders)))
                (reset! html-renders [])
                (let [compact
                      (agent-view/materialize-surface
                        {:seon.db/db @conn
                         :seon.agent/id agent-a
                         ::agent-view/selection (selection-for :transcript)
                         ::agent-view/face :compact})]
                  (is (= [:section {:class "seon-tile-compact"}
                          [:p "compact"]]
                         compact))
                  (is (= [:transcript] @html-renders))
                  (is (zero? @canvas-renders)))
                (reset! html-renders [])
                (let [canvas
                      (agent-view/materialize-surface
                        {:seon.db/db @conn
                         :seon.agent/id agent-a
                         ::agent-view/selection "canvas"
                         ::agent-view/face :compact})]
                  (is (= [:section {:class "seon-tile-compact"}
                          [:p "compact"]]
                         canvas))
                  (is (empty? @html-renders))
                  (is (= 1 @canvas-renders)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest materialize-surface-returns-nil-for-missing-or-empty-content
  (async done
    (-> (with-agents
          [[agent-a []]]
          (fn [conn]
            (let [renders (atom 0)]
              (with-redefs
                [agent-ctx/context-root
                 (fn [_]
                   {:seon.agent/entity {:seon.agent/id agent-a}
                    :seon.agent.ctx/children
                    [{:seon.agent.ctx/name :conditional
                      :seon.render/html 'my.agent.view/conditional}
                     {:seon.agent.ctx/name :agent-only
                      :seon.render/ai "agent-only"}]})
                 render/render
                 (fn [& _] (swap! renders inc) nil)]
                (is (nil?
                      (agent-view/materialize-surface
                        {:seon.db/db @conn
                         :seon.agent/id agent-a
                         ::agent-view/selection (selection-for :missing)
                         ::agent-view/face :expanded})))
                (is (zero? @renders)
                    "a vanished selection does not invoke another surface")
                (is (nil?
                      (agent-view/materialize-surface
                        {:seon.db/db @conn
                         :seon.agent/id agent-a
                         ::agent-view/selection (selection-for :agent-only)
                         ::agent-view/face :compact})))
                (is (zero? @renders)
                    "an AI-only block is not an HTML surface")
                (is (nil?
                      (agent-view/materialize-surface
                        {:seon.db/db @conn
                         :seon.agent/id agent-a
                         ::agent-view/selection (selection-for :conditional)
                         ::agent-view/face :expanded})))
                (is (= 1 @renders)
                    "an empty selected renderer is still invoked exactly once")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest latest-focus-selection-uses-only-catalog-metadata
  (let [surface (fn [selection label touch focus-touch]
                  {::agent-view/selection selection
                   ::agent-view/label label
                   ::agent-view/read-attrs #{}
                   ::agent-view/touch touch
                   ::agent-view/focus-touch focus-touch})]
    (testing "deliberate focus is distinct from general content recency"
      (is (= "context-transcript"
             (agent-view/latest-focus-selection
               [(surface "canvas" "canvas" 80 4)
                (surface "context-transcript" "transcript" 5 6)]))))
    (testing "canvas wins an untouched focus tie"
      (is (= "canvas"
             (agent-view/latest-focus-selection
               [(surface "context-transcript" "transcript" 0 0)
                (surface "canvas" "canvas" 0 0)]))))))

(deftest view-renders-html-blocks-and-omits-ai-only-blocks
  (async done
    (-> (with-agents
          [[agent-a
            [{:seon.agent.ctx/name :dual :seon.agent.ctx/priority 10
              :seon.render/ai "agent text"
              :seon.render/html [:div "DUAL-HTML"]}
             {:seon.agent.ctx/name :ai-only :seon.agent.ctx/priority 20
              :seon.render/ai "AI-ONLY-TEXT"}]]]
          (fn [conn]
            (let [dbv @conn
                  dual-selection (catalog-selection dbv agent-a :dual)
                  s (html/->string (agent-view/agent-view dbv agent-a))]
              (is (str/includes? s "id=\"app-view\""))
              (is (str/includes? s "DUAL-HTML"))
              (is (not (str/includes? s "AI-ONLY-TEXT")))
              (is (str/includes?
                    s (str "data-agent-primary=\"" dual-selection "\"")))
              (testing "the global new-agent action avoids unsupported modal APIs"
                (is (str/includes? s "fetch(&#39;/agents/new&#39;"))
                (is (not (str/includes? s "prompt("))))
              (testing "the focused surface is reactively omitted from the rail"
                (is (str/includes? s
                      (str "data-show=\"$selected !== &#39;"
                           dual-selection "&#39;\"")))
                (is (str/includes? s
                      "data-show=\"$selected !== &#39;canvas&#39;\""))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest view-is-pure-and-agent-scoped
  (async done
    (-> (with-agents
          [[agent-a [{:seon.agent.ctx/name :shared :seon.agent.ctx/priority 10
                      :seon.render/html [:div "A-ONLY"]}]]
           [agent-b [{:seon.agent.ctx/name :shared :seon.agent.ctx/priority 10
                      :seon.render/html [:div "B-ONLY"]}]]]
          (fn [conn]
            (let [dbv @conn
                  a1  (html/->string (agent-view/agent-view dbv agent-a))
                  a2  (html/->string (agent-view/agent-view dbv agent-a))]
              (testing "same frozen db and id produce the same view"
                (is (= a1 a2)))
              (testing "same-named blocks remain agent-scoped"
                (is (str/includes? a1 "A-ONLY"))
                (is (not (str/includes? a1 "B-ONLY")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest view-surfaces-derived-render-fn-html
  (async done
    (-> (with-agents
          [[agent-a []]]
          (fn [conn]
            (with-redefs [agent-ctx/context-root
                          (fn [_]
                            {:seon.agent/entity {}
                             :seon.agent.ctx/children
                             [{:seon.agent.ctx/name :render-fn/live
                               :seon.agent.ctx/priority 50
                               :seon.render/html [:div "DERIVED-HTML"]}]})]
              (let [s (html/->string (agent-view/agent-view @conn agent-a))]
                (is (str/includes? s "DERIVED-HTML"))
                (is (str/includes? s (selection-for :render-fn/live)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest pinned-canvas-renderer-contributes-its-domain-dependencies
  (async done
    (-> (with-agents
          [[agent-a []]]
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.agent/id agent-a
                     :seon.render.canvas/content 'my.agent.view/canvas}]})
                (.then
                  (fn [_]
                    (with-redefs
                      [render-fns/renderer-read-attrs
                       (fn [{renderer :seon.render/html}]
                         (is (= 'my.agent.view/canvas renderer))
                         {:seon.agent.ctx.render-fns/attrs
                          [:my.agent.view/state]})]
                      (let [deps
                            (:seon.ui.agent-view/surface-attrs
                              (agent-view/agent-view-dependencies
                                @conn agent-a))]
                        (is (contains? deps :my.agent.view/state)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest absent-canvas-pin-is-a-normal-missing-value
  (async done
    (-> (with-agents
          [[agent-a []]]
          (fn [conn]
            (let [deps
                  (:seon.ui.agent-view/surface-attrs
                    (agent-view/agent-view-dependencies @conn agent-a))]
              (is (contains? deps :seon.render.canvas/content)))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest malformed-canvas-pin-is-recorded-and-fails-loudly
  (async done
    (-> (with-agents
          [[agent-a []]]
          (fn [conn]
            ;; Simulate a corrupted/pre-validation store by bypassing seon.db's
            ;; registered Malli value gate. First install the attribute through
            ;; the real API, then replace its encoded physical string directly.
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [{:seon.agent/id agent-a
                     :seon.render.canvas/content 'my.agent.view/canvas}]})
                (.then
                  (fn [_]
                    (d/transact! conn
                      {:tx-data
                       [{:db/id [:seon.agent/id agent-a]
                         :seon.render.canvas/content "["}]})))
                (.then
                  (fn [_]
                    (let [recorded (atom nil)]
                      (with-redefs [err/record! #(reset! recorded %)]
                        (is (thrown? js/Error
                              (agent-view/agent-view-dependencies
                                @conn agent-a)))
                        (is (= :core
                              (:seon.error/fault @recorded))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest missing-agent-degrades-without-throwing
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (let [dbv @conn
                  agent-id "ghost-agent-xx"
                  catalog (agent-view/surface-catalog dbv agent-id)
                  view (agent-view/agent-view dbv agent-id)]
              (is (= ["canvas"]
                     (mapv ::agent-view/selection catalog)))
              (is (nil? (:seon.agent.ctx/name (first catalog))))
              (is (nil?
                    (agent-view/materialize-surface
                      {:seon.db/db dbv
                       :seon.agent/id agent-id
                       ::agent-view/selection "canvas"
                       ::agent-view/face :expanded})))
              (is (vector? view))
              (is (= "app-view" (:id (second view)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))
