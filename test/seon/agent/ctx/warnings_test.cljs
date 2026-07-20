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

(defn- acquisition-responses []
  [{::db/results
    [(member [])
     (member [[:wtest.warns (js/Date. 1) 1]])
     (member [])
     (member
       [["wtest.warns/no-spec" :wtest.warns "" true false ""]
        ["wtest.clean/ok" :wtest.clean
         "[:=> [:cat :string] :string]" true false ""]])
     {::protocol/success? true ::protocol/schema {}}
     (member [[:seon.agent/id :seon.db.process/boot]])
     (member [])
     (member [])
     (member (js/Date. 7200000))]}
   {::db/results (vec (repeat 6 (member [])))}])

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
          original-render warn/render-warnings
          responses (atom (vec (mapcat identity
                                       (repeat 3 (acquisition-responses)))))
          requests (atom [])
          render-requests (atom [])]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
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
              (is (= 6 (count @requests)) "each render uses two owner-local batches")
              (is (every? #(identical? database (::db/db %)) @requests)
                  "every batch uses the invocation database value")
              (is (every?
                   (fn [request]
                     (every?
                      #(identical? database (or (::db/db %) (::db/db request)))
                      (::db/members request)))
                   @requests)
                  "every member resolves to the exact invocation database")
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
             (set! warn/render-warnings original-render)
             (done)))))))
