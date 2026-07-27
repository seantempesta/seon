(ns seon.agent.ctx.warnings-test
  "Behavior test for the `:warnings` context block wiring
   (`seon.agent.ctx.warnings/warnings-block`).

   The render engine (`seon.render/render`) injects each block's OWN map
   as `:seon.render/node` — that is where a per-block `:seon.warn/ns`
   scope override lives. This test pins the MECHANISM: an override on the
   node CHANGES the scope of the corpus checks (warning renders when the
   defect is in scope, empty when it isn't). It falsifies the dead read
   that read the override from `:seon.agent.ctx/block` — a key the input
   never carries — which silently ignored every override.

   Run via bin/test-cljs, or interactively via MCP eval:
     (require 'seon.agent.ctx.warnings-test :reload)
     (cljs.test/run-tests 'seon.agent.ctx.warnings-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async]]
    [seon.agent.ctx.warnings :as warnings]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.warn :as warn]))

(def ^:private database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "00000000-0000-0000-0000-000000000042"})

(defn- member [result]
  {::protocol/success? true ::protocol/result result})

(defn- scalar-response []
  {::db/results
   [(member [])
    (member [[:wtest.warns (js/Date. 1) 1]])
    (member [])
    (member (js/Date. 7200000))]})

(defn- index-response
  [identity-attr]
  {:datahike.index-page/datoms
   (case identity-attr
     :seon.fn/sym
     [[1 :seon.fn/sym "wtest.warns/no-spec" 1 true]
      [2 :seon.fn/sym "wtest.clean/ok" 1 true]]
     :seon.schema/key
     [[3 :seon.schema/key :seon.agent/id 1 true]
      [4 :seon.schema/key :datahike.index-page/complete? 1 true]]
     [])
   :datahike.index-page/complete? true})

(defn- pull-response
  [request]
  (let [refs (::db/refs request)]
    (cond
      (= [1 2] refs)
      [{:seon.fn/sym "wtest.warns/no-spec"
        :seon.fn/ns {:seon.ns/name 'wtest.warns}}
       {:seon.fn/sym "wtest.clean/ok"
        :seon.fn/ns {:seon.ns/name 'wtest.clean}
        :seon.fn/spec "[:=> [:cat :string] :string]"}]

      (= [3 4] refs)
      [{:seon.schema/key :seon.agent/id :seon.schema/form ":string"}
       {:seon.schema/key :datahike.index-page/complete?
        :seon.schema/form ":boolean"}]

      (= [[:db/ident :seon.agent/id]
          [:db/ident :datahike.index-page/complete?]] refs)
      [{:db/id 30 :db/ident :seon.agent/id
        :db/valueType :db.type/string
        :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity}
       nil]

      :else [])))

(defn- block-for
  [scope-kw]
  (warnings/warnings-block
    {:seon.agent/id "wtest-agent"
     :seon.agent/entity {:seon.agent/id "wtest-agent"}
     :seon.render/node {:seon.warn/ns scope-kw}
     ::db/db database}
    nil))

(deftest warnings-block-honors-scope-override-on-the-block-node
  (async done
    (let [original db/execute-many
          original-index-page db/index-page
          original-pull-many db/pull-many
          original-query db/query
          original-render warn/render-warnings
          requests (atom [])
          render-requests (atom [])]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve (scalar-response))))
      (set! db/index-page
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                (index-response (first (::db/components request)))))
              ([_database _options]
               (js/Promise.reject (js/Error. "unexpected index-page arity")))))
      (set! db/pull-many
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve (pull-response request)))
              ([_selector _refs]
               (js/Promise.reject (js/Error. "unexpected pull-many arity")))
              ([_database _selector _refs]
               (js/Promise.reject (js/Error. "unexpected pull-many arity")))))
      (set! db/query
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                (if (= :seon.db/process
                       (some #{:seon.db/process}
                             (tree-seq coll? seq (::db/query request))))
                  [[:seon.agent/id :seon.db.process/boot]]
                  [])))
              ([_query & _inputs]
               (js/Promise.reject (js/Error. "unexpected query arity")))))
      (set! warn/render-warnings
            (fn [request]
              (swap! render-requests conj request)
              (original-render request)))
      (-> (block-for :wtest.warns)
          (.then
            (fn [out]
              (testing "the remote ordinary-data owner preserves corpus scope"
                (is (str/includes? out "[no-malli-schema]"))
                (is (str/includes? out "wtest.warns/no-spec")))
              (block-for :seon.warn/all)))
          (.then
            (fn [out]
              (is (str/includes? out "wtest.warns/no-spec"))
              (block-for :wtest.clean)))
          (.then
            (fn [out]
              (is (= "" out))
              (is (every? #(identical? database (::db/db %)) @requests)
                  "every page, pull, query, and scalar uses the frozen value")
              (is (every?
                   #(or (nil? (::db/max-result-weight %))
                        (<= (::db/max-result-weight %) 60000))
                   @requests)
                  "every direct page and pull stays under the 64 KiB floor")
              (is (every? #(= 16 (::db/limit %))
                          (filter ::db/index @requests)))
              (is (not-any?
                   #(= [:datahike.index-page/complete?] (::db/components %))
                   @requests)
                  "registered response keys never become AEVT attributes")
              (is (= 3 (count @render-requests)))
              (is (every? #(map? (::warn/data %)) @render-requests)
                  "the database owner passes ordinary data to the pure renderer")
              (is (every?
                   #(= [[:seon.agent/id :seon.db.process/boot]]
                       (get-in % [::warn/data ::warn/schema-provenance]))
                   @render-requests)
                  "exact schema provenance survives acquisition")))
          (.catch (fn [e] (is false (str "threw — " e))))
          (.finally
           (fn []
             (set! db/execute-many original)
             (set! db/index-page original-index-page)
             (set! db/pull-many original-pull-many)
             (set! db/query original-query)
             (set! warn/render-warnings original-render)
             (done)))))))

(deftest warnings-block-preserves-top-level-database-error
  (async done
    (let [original db/execute-many
          frame-error {:seon.error/message "Database response exceeds frame."
                       :seon.error/kind :core-bug
                       :seon.error/data
                       {:seon.db.protocol/error-kind
                        :seon.db.protocol.error/frame-too-large
                        :seon.db.protocol/request-id "warnings-frame"}}]
      (set! db/execute-many (fn [_] (js/Promise.resolve frame-error)))
      (-> (block-for :seon.warn/all)
          (.then (fn [out]
                   (is (str/includes? out "[warnings] render failed:"))
                   (is (str/includes? out "warnings-frame"))
                   (is (str/includes? out "frame-too-large"))
                   (is (not (str/includes? out "nil")))))
          (.catch (fn [e] (is false (str "threw — " e))))
          (.finally (fn [] (set! db/execute-many original) (done)))))))

(deftest attribute-count-queries-preserve-count-distinct-semantics
  (async done
    (let [original db/query
          requests (atom [])]
      (set! db/query
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                (mapv (fn [attribute] [attribute 2])
                      (first (::db/args request)))))
              ([_query & _inputs]
               (js/Promise.reject (js/Error. "unexpected query arity")))))
      (-> (js/Promise.resolve
           ((deref #'warnings/attribute-counts!)
            database
            (mapv #(keyword "example" (str "attribute-" %)) (range 33))))
          (.then
           (fn [counts]
             (is (= 33 (count counts)))
             (is (= 3 (count @requests)))
             (is (= [16 16 1] (mapv #(count (first (::db/args %))) @requests)))
             (is (every? #(identical? database (::db/db %)) @requests))
             (is (every? #(= 60000 (::db/max-result-weight %)) @requests))
             (is (every? #(= 65536 (::db/max-results %)) @requests))
             (is (every?
                  #(some #{'count-distinct}
                         (tree-seq coll? seq (::db/query %)))
                  @requests))))
          (.catch (fn [e] (is false (str "threw — " e))))
          (.finally (fn [] (set! db/query original) (done)))))))
