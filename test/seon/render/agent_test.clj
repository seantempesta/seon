(ns seon.render.agent-test
  "Slice 2: the three-block agent page and its family-owned transcript."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.message :as message]
            [seon.cluster.run :as run]
            [seon.error :as error]
            [seon.render.agent :as agent]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.walk :as walk]
            [seon.render.web :as web]
            [seon.test-support :as support]))

(def ^:private agent-id "agent-transcript")
(def ^:private peer-id "agent-peer")

(def ^:private caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-nodes 500
   :seon.config.eval.result/max-collection 100
   :seon.config.eval.result/max-string 10000})

(defn- at
  [offset]
  (java.util.Date. (long (+ 1785300000000 offset))))

(defn- entity-id
  [db attribute value]
  (d/q '[:find ?entity .
         :in $ ?attribute ?value
         :where [?entity ?attribute ?value]]
       db attribute value))

(defn- transact-one!
  [connection row]
  (d/transact connection {:tx-data [row]}))

(defn- seed-history!
  [connection]
  (transact-one!
   connection
   {:seon.cluster.agent/id agent-id
    :seon.cluster.agent/blocks agent/blocks})
  (transact-one! connection {:seon.cluster.agent/id peer-id})
  (transact-one!
   connection
   {:seon.cluster.message/id "inbound-message"
    :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
    :seon.cluster.message/content "Please inspect the build."
    :seon.cluster.message/at (at 1)})
  (transact-one!
   connection
   {:seon.cluster.run/id "run-transcript"
    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
    :seon.cluster.run/opened-at (at 2)
    :seon.cluster.run/closed-at (at 7)})
  (transact-one!
   connection
   {:seon.cluster.eval/id "receipt-transcript"
    :seon.cluster.eval/run [:seon.cluster.run/id "run-transcript"]
    :seon.cluster.eval/ordinal 0
    :seon.cluster.eval/at (at 3)
    :seon.cluster.eval/result-edn "{:inspection :complete}"})
  (transact-one!
   connection
   {:seon.error/id "error-transcript"
    :seon.error/at (at 4)
    :seon.error/process "scratch-process"
    :seon.error/kind :agent.test/ordinary-failure
    :seon.error/message "One ordinary form failed."
    :seon.error/signature (apply str (repeat 64 "a"))
    :seon.error/data-edn "{}"
    :seon.error/capped? false
    :seon.error/agent [:seon.cluster.agent/id agent-id]
    :seon.error/run [:seon.cluster.run/id "run-transcript"]})
  (transact-one!
   connection
   {:seon.cluster.message/id "outbound-message"
    :seon.cluster.message/to [:seon.cluster.agent/id peer-id]
    :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
    :seon.cluster.message/content "The build is ready."
    :seon.cluster.message/at (at 5)})
  (let [db @connection]
    {:agent (entity-id db :seon.cluster.agent/id agent-id)
     :message-in (entity-id db :seon.cluster.message/id "inbound-message")
     :run (entity-id db :seon.cluster.run/id "run-transcript")
     :receipt (entity-id db :seon.cluster.eval/id "receipt-transcript")
     :error (entity-id db :seon.error/id "error-transcript")
     :message-out (entity-id db :seon.cluster.message/id "outbound-message")}))

(defn- render-unit
  [db]
  {:seon.db/db db
   :seon.cluster.agent/id agent-id
   :seon.render/distance 2
   :seon.sci.admit/caps caps})

(defn- nodes
  [hiccup]
  (filter vector? (tree-seq sequential? seq hiccup)))

(defn- class-token?
  [node token]
  (when-let [attributes (when (map? (nth node 1 nil)) (nth node 1))]
    (contains? (set (str/split (get attributes :class "") #"\s+")) token)))

(defn- transcript-entry-ids
  [rendered]
  (into []
        (keep (fn [node]
                (when (class-token? node "seon-transcript-entry")
                  (some-> node second :data-transcript-entity Long/parseLong))))
        (nodes rendered)))

(defn- transcript-entries
  [rendered]
  (into {}
        (keep (fn [node]
                (when (class-token? node "seon-transcript-entry")
                  [(get (second node) :data-transcript-entity)
                   (hiccup/->string node)])))
        (nodes rendered)))

(defn- text-nodes
  [rendered]
  (into #{} (filter string?) (tree-seq sequential? seq rendered)))

(deftest transcript-orders-by-commit-test
  (support/with-database
    (fn [connection]
      (let [ids (seed-history! connection)
            db @connection
            first-render (agent/transcript-html (render-unit db))
            second-render (agent/transcript-html (render-unit db))
            expected (sort (map ids
                                [:message-in :run :receipt :error :message-out]))]
        (is (= expected (transcript-entry-ids first-render))
            "entity id ascending is the committed-history order")
        (is (= first-render second-render)
            "two derivations of one database value have one order")))))

(deftest transcript-renders-through-family-lenses-test
  (support/with-database
    (fn [connection]
      (let [ids (seed-history! connection)
            db @connection
            resolution {:seon.render/kind :seon.render/html
                        :seon.render/overrides {}
                        :seon.render/floor `block/data-panel}
            projection (fn [entity-id]
                         (walk/projection
                          (assoc (d/pull db '[*] entity-id)
                                 :seon.db/db db
                                 :seon.sci.admit/caps caps)
                          resolution))]
        (is (= `message/render-html (projection (:message-in ids))))
        (is (= `run/render-html (projection (:run ids))))
        (is (= `run/render-receipt-html (projection (:receipt ids))))
        (is (= `error/render-html (projection (:error ids))))
        (let [before (transcript-entries
                      (agent/transcript-html (render-unit db)))
              message-ids #{(str (:message-in ids)) (str (:message-out ids))}
              after
              (with-redefs
               [message/render-html
                (fn [_unit]
                  [:article {:class "seon-family-entry seon-message-entry"}
                   [:p "hot-reloaded-message-lens"]])]
                (transcript-entries
                 (agent/transcript-html (render-unit db))))
              changed (into #{}
                            (keep (fn [[id html]]
                                    (when (not= html (get before id)) id)))
                            after)]
          (is (= message-ids changed)
              "only the redefined family's entries change")
          (is (every? #(str/includes? (get after %) "hot-reloaded-message-lens")
                      message-ids)))))))

(deftest rail-and-focus-are-one-renderer-at-two-distances-test
  ;; seed 2026072907
  (support/with-database
    (fn [connection]
      (let [{:keys [run] :as ids} (seed-history! connection)
            db @connection
            pulled (assoc (d/pull db '[*] run)
                          :seon.db/db db
                          :seon.sci.admit/caps caps)
            compact (agent/unit-html
                     (assoc pulled :seon.render/distance 0))
            expanded (agent/unit-html
                      (assoc pulled :seon.render/distance 2))
            compact-facts (text-nodes compact)
            expanded-facts (text-nodes expanded)
            calls (atom [])]
        (is (every? expanded-facts compact-facts)
            "the compact facts are a subset of the expanded facts")
        (with-redefs [agent/unit-html
                      (fn [unit]
                        (swap! calls conj
                               [(:db/id unit) (:seon.render/distance unit)])
                        [:div "projection"])]
          (agent/focus-html (render-unit db)))
        (doseq [entity-id (map ids
                               [:agent :message-in :run :receipt
                                :error :message-out])]
          (is (= #{0 2}
                 (into #{}
                       (keep (fn [[called distance]]
                               (when (= entity-id called) distance)))
                       @calls))
              "the same unit renderer supplies rail and focal distance"))))))

(deftest selection-survives-a-morph-test
  ;; seed 2026072908 — wire bytes use Datastar's if-missing signal modifier.
  (support/with-database
    (fn [connection]
      (seed-history! connection)
      (let [request {:seon.db/db @connection
                     :seon.cluster.agent/id agent-id
                     :seon.sci.admit/caps caps
                     :seon.cluster.run/live-processes #{"scratch-process"}}
            initial (web/page-of request)]
        (transact-one!
         connection
         {:seon.cluster.message/id "later-message"
          :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
          :seon.cluster.message/content "A later transcript repaint."
          :seon.cluster.message/at (at 9)})
        (let [repaint
              (web/changed initial
                           (web/page-of (assoc request :seon.db/db @connection)))
              patches (into {} (:seon.render.web/patches repaint))
              transcript (get patches (block/surface-id :transcript))
              focus (get patches (block/surface-id :focus))]
          (is (str/includes? transcript "A later transcript repaint."))
          (is (str/includes? focus "data-signals__ifmissing")
              "a focus repaint may initialize, but never overwrite selection")
          (is (not (re-find #"<section[^>]* data-signals="
                            focus))
              "the wire has no unconditional signal assignment")
          (is (str/includes? focus "$selected ===")
              "the focal choice remains a tab-local signal"))))))

(deftest an-agent-with-nothing-to-say-renders-an-empty-transcript-test
  ;; seed 2026072909
  (support/with-database
    (fn [connection]
      (transact-one! connection {:seon.cluster.agent/id agent-id})
      (let [rendered (agent/transcript-html (render-unit @connection))
            html (hiccup/->string rendered)]
        (is (= (block/surface-id :transcript)
               (get-in rendered [1 :id]))
            "the identified morph wrapper exists before its first entry")
        (is (str/includes? html "Send a message above to begin.")
            "the empty state teaches what fills it")))))
