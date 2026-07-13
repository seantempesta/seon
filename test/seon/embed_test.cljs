(ns seon.embed-test
  "Behavior tests for the pod-side `seon.embed` writer-failure envelope.

   Class rule (docs/conventions.md \"Errors Are Values\" consequence 3): a
   specced `^:async` fn must NEVER reject with an expected error — a writer
   knn-search failure RESOLVES to `{::embed/hits [] :seon/error {…}}`.

   The database operation is stubbed by `set!` on `replica/knn-search!`
   (restored in a
   `.finally` link), NOT `with-redefs`: this fork's `with-redefs` AWAITS an
   async body (so the form yields the resolved value and asyncifies the
   enclosing fn, breaking the `(async done …)` contract)."
  (:require
    [cljs.test :refer-macros [deftest is async]]
    [seon.db.protocol :as protocol]
    [seon.db.replica :as replica]
    [seon.embed :as embed]
    [seon.test.async :refer [settle!]]))

(defn- stub-search
  "A map-in KNN stub resolving to `reply`."
  [reply]
  (fn [_request] (js/Promise.resolve reply)))

(defn- with-search-stub
  "Run `f` (→ Promise) with knn-search stubbed to resolve `reply`; the
   original is restored when the returned Promise settles."
  [reply f]
  (let [orig replica/knn-search!]
    (set! replica/knn-search! (stub-search reply))
    (.finally (f) (fn [] (set! replica/knn-search! orig)))))

(def ^:private failed-reply
  {::protocol/success? false
   ::protocol/error-kind protocol/database-error
   ::protocol/error "writer down (stub)"})

(deftest search-resolves-error-envelope-on-wire-failure
  ;; The old behavior THREW (→ rejected Promise → instrument wrapper records
  ;; a :core fault → :crash exits the dev pod). The fix: resolve an envelope.
  (async done
    (-> (with-search-stub failed-reply
          #(embed/search {:seon.embed/query "anything" :seon.embed/db {}}))
        (.then (fn [{:seon.embed/keys [hits] :as res}]
                 (is (= [] hits) "hits key present and empty on failure")
                 (let [err (:seon/error res)]
                   (is (map? err) "carries a :seon/error map")
                   (is (= :core-bug (:seon.error/kind err)))
                   (is (re-find #"writer down"
                                (str (:seon.error/message err)))
                       "message surfaces the wire error"))))
        (settle! done))))

(deftest search-pull-passes-error-envelope-through-unchanged
  (async done
    (-> (with-search-stub failed-reply
          #(embed/search-pull {:seon.embed/query "anything"
                               :seon.embed/db {}}))
        (.then (fn [res]
                 (is (= [] (:seon.embed/hits res)))
                 (is (some? (:seon/error res))
                     "search's :seon/error passes through unchanged")))
        (settle! done))))

(deftest search-resolves-hits-on-ok-wire-reply
  (async done
    (let [hits [{:seon.embed/eid 7 :seon.embed/distance 0.25}]]
      (-> (with-search-stub hits
            #(embed/search {:seon.embed/query "anything"
                            :seon.embed/db {}}))
          (.then (fn [res]
                   (is (= hits (:seon.embed/hits res)))
                   (is (nil? (:seon/error res)))))
          (settle! done)))))
