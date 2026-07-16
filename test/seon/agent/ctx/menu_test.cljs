(ns seon.agent.ctx.menu-test
  "Focused proof for the coordinate-pinned function-menu acquisition."
  (:require
    [cljs.test :refer [async deftest is]]
    [clojure.string :as str]
    [seon.agent.ctx.menu :as menu]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

(def ^:private agent-id "menutestagentA")
(def ^:private coordinate
  {:seon.db.coordinate/database-id
   #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   :seon.db.coordinate/branch :main
   :seon.db.coordinate/commit-id
   #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   :seon.db.coordinate/t 42})

(defn- member [value]
  {::protocol/success? true ::protocol/result value})

(defn- query-member [value]
  {::protocol/success? true :datahike.query/result value})

(def ^:private done-row
  {:seon.fn/sym "my.plan/done!"
   :seon.fn/fn-var? true
   :seon.fn/agent-facing? true
   :seon.fn/spec "[:=> [:cat :map] :map]"
   :seon.fn/arglists "([request])"
   :seon.fn/doc "Mark a step done."})

(def ^:private plan-row
  {:seon.fn/sym "my.plan/plan!"
   :seon.fn/fn-var? true
   :seon.fn/agent-facing? true
   :seon.fn/spec "[:=> [:cat :map] :map]"
   :seon.fn/arglists "([request])"
   :seon.fn/doc "Author a whole plan."})

(defn- responses [policy]
  [{::db/coordinate coordinate
    ::db/results
    [(member policy)
     (query-member [[(js/Date. 3000) 13 "(plan/done! {})" :my.agent.menu]
                    [(js/Date. 2000) 12 "(plan/done! {})" :my.agent.menu]])
     (query-member [[(js/Date. 3000) 13 "(plan/done! {})" :my.agent.menu]])]}
   {::db/coordinate coordinate
    ::db/results
    [(member [{:seon.ns/name :my.agent.menu
               :seon.ns/require-edges
               [{:seon.ns.require/target :my.plan
                 :seon.ns.require/alias 'plan}]}
              {:seon.ns/name :my.plan :seon.ns/require-edges []}])
     (query-member [[done-row] [plan-row]])
     (member [done-row])]}])

(defn- render-with [policy]
  (let [calls (atom (responses policy))]
    (with-redefs [db/current-tx-context (fn [] {::db/coordinate coordinate})
                  db/execute-many
                  (fn [request]
                    (is (= coordinate (::db/coordinate request))
                        "every acquisition stays pinned")
                    (let [response (first @calls)]
                      (swap! calls subvec 1)
                      (js/Promise.resolve response)))]
      (menu/function-menu-block {:seon.agent/id agent-id} nil))))

(deftest prompt-menu-acquires-remotely-and-preserves-one-numbering
  (async done
    (-> (render-with nil)
        (.then
          (fn [out]
            (is (str/includes? out "① fn my.plan/done! — positional")
                "aliased recent call ranks first")
            (is (str/includes? out "② fn my.plan/plan! — positional")
                "uncalled toolkit function follows in the same numbering")
            (is (= 1 (count (re-seq #"my\.plan/done!" out)))
                "recent and toolkit rows are deduplicated")
            (done)))
        (.catch (fn [error] (is false (str error)) (done))))))

(deftest acquired-policy-bounds-the-prompt-menu
  (async done
    (-> (render-with {:seon.typeahead/menu-cap 0
                      :seon.typeahead/toolkit-cap 1})
        (.then
          (fn [out]
            (is (not (str/includes? out "done!")) "recent cap is applied")
            (is (str/includes? out "① fn my.plan/plan! — positional")
                "toolkit retains the first glyph when recent is empty")
            (done)))
        (.catch (fn [error] (is false (str error)) (done))))))

(deftest absent-coordinate-fails-closed
  (async done
    (with-redefs [db/current-tx-context (constantly nil)]
      (-> (menu/function-menu-block {:seon.agent/id agent-id} nil)
          (.then (fn [out]
                   (is (str/includes? out "render failed")
                       "core acquisition failures remain visible")
                   (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))
