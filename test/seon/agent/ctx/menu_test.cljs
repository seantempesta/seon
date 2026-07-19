(ns seon.agent.ctx.menu-test
  "Focused proof for database-value-pinned function-menu acquisition."
  (:require
    [cljs.test :refer [async deftest is]]
    [clojure.string :as str]
    [seon.agent.ctx.menu :as menu]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

(def ^:private agent-id "menutestagentA")
(def ^:private database
  {:datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   :max-tx 42})

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
  [{::db/results
    [(member policy)
     (query-member [[(js/Date. 3000) 13 "(plan/done! {})" 'my.agent.menu]
                    [(js/Date. 2000) 12 "(plan/done! {})" 'my.agent.menu]])
     (query-member [[(js/Date. 3000) 13 "(plan/done! {})" 'my.agent.menu]])]}
   {::db/results
    [(member [{:seon.ns/name 'my.agent.menu
               :seon.ns/require-edges
               [{:seon.ns.require/target 'my.plan
                 :seon.ns.require/alias 'plan}]}
              {:seon.ns/name 'my.plan :seon.ns/require-edges []}])
     (query-member [[done-row] [plan-row]])
     (member [done-row])]}])

(defn- render-with
  ([policy] (render-with policy {::db/db database}))
  ([policy tx-context]
   (let [calls (atom (responses policy))
         original-context db/current-tx-context
         original-execute db/execute-many]
     (set! db/current-tx-context (constantly tx-context))
     (set! db/execute-many
           (fn [request]
             (is (identical? database (::db/db request))
                 "every acquisition uses the same database value")
             (let [response (first @calls)]
               (swap! calls subvec 1)
               (js/Promise.resolve response))))
     (-> (menu/function-menu-block {:seon.agent/id agent-id} nil)
         (.finally (fn []
                     (set! db/current-tx-context original-context)
                     (set! db/execute-many original-execute)))))))

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
            (is (= 1 (count (re-seq #"; ① fn" out)))
                "the acquired caps leave one toolkit entry")
            (is (str/includes? out "① fn my.plan/done! — positional")
                "toolkit starts at the first glyph when recent is empty")
            (done)))
        (.catch (fn [error] (is false (str error)) (done))))))

(deftest structured-acquisition-is-the-display-and-provider-value
  (async done
    (let [calls (atom (responses {:seon.typeahead/max-rounds 3}))
          original-execute db/execute-many]
      (set! db/execute-many
            (fn [request]
              (is (identical? database (::db/db request)))
              (let [response (first @calls)]
                (swap! calls subvec 1)
                (js/Promise.resolve response))))
      (-> (menu/acquire-function-menu
           {:seon.agent/id agent-id ::db/db database})
          (.then
           (fn [value]
             (is (= 3 (get-in value [::menu/policy
                                     :seon.typeahead/max-rounds])))
             (is (= ["①" "②"]
                    (mapv :seon.typeahead/glyph (::menu/offers value))))
             (is (str/includes? (:seon.typeahead/label
                                 (first (::menu/offers value)))
                                "my.plan/done!"))
             (is (str/includes? (:seon.typeahead/label
                                 (second (::menu/offers value)))
                                "my.plan/plan!"))
             (is (str/includes? (::menu/text value)
                                "① fn my.plan/done! — positional"))))
          (.finally (fn [] (set! db/execute-many original-execute)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))
