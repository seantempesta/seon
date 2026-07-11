(ns seon.ui.agent-view-test
  "Structural tests for the DB-derived per-agent view."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [seon.agent.ctx :as agent-ctx]
    [seon.client :as client]
    [seon.db :as db]
    [seon.ui.agent-view :as agent-view]
    [seon.ui.html :as html]))

(def ^:private agent-a "view-aaaa00001")
(def ^:private agent-b "view-bbbb00002")

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
            (let [s (html/->string (agent-view/agent-view @conn agent-a))]
              (is (str/includes? s "id=\"app-view\""))
              (is (str/includes? s "DUAL-HTML"))
              (is (not (str/includes? s "AI-ONLY-TEXT")))
              (is (str/includes? s "data-agent-primary=\"context-dual\""))
              (testing "the focused surface is reactively omitted from the rail"
                (is (str/includes? s
                      "data-show=\"$selected !== &#39;context-dual&#39;\""))
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
            (with-redefs [agent-ctx/rendered-context-blocks
                          (fn [_ _]
                            [{:seon.agent.ctx/name :render-fn/live
                              :seon.agent.ctx/priority 50
                              :seon.render/hiccup [:div "DERIVED-HTML"]}])]
              (let [s (html/->string (agent-view/agent-view @conn agent-a))]
                (is (str/includes? s "DERIVED-HTML"))
                (is (str/includes? s "context-render-fn-live"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))

(deftest missing-agent-degrades-without-throwing
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (let [view (agent-view/agent-view @conn "ghost-agent-xx")]
              (is (vector? view))
              (is (= "app-view" (:id (second view)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str e)) (done))))))
