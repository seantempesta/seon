(ns seon.embed-test
  "Behavior tests for the pod-side `seon.embed` wire-failure envelope.

   Class rule (docs/conventions.md \"Errors Are Values\" consequence 3): a
   specced `^:async` fn must NEVER reject with an expected error — a wire
   knn-search failure RESOLVES to `{::embed/hits [] :seon/error {…}}`.

   The wire is stubbed by `set!` on `wire-node/knn-search` (restored in a
   `.finally` link), NOT `with-redefs`: this fork's `with-redefs` AWAITS an
   async body (so the form yields the resolved value and asyncifies the
   enclosing fn, breaking the `(async done …)` contract). The stub matches
   the original's arities ([query k] / [sock query k eids opts]) so the
   arity-dispatched compiled call site resolves (same gotcha as
   `seon.client.provider-routing-test/tagging-adapter`)."
  (:require
    [cljs.test :refer-macros [deftest is async]]
    [seon.embed :as embed]
    [seon.store.internal.wire-node :as wire-node]
    [seon.test.async :refer [settle!]]))

(defn- stub-wire
  "An arity-matched knn-search stub resolving to `reply`."
  [reply]
  (fn
    ([_query _k] (js/Promise.resolve reply))
    ([_sock _query _k _eids _opts] (js/Promise.resolve reply))))

(defn- with-wire-stub
  "Run `f` (→ Promise) with knn-search stubbed to resolve `reply`; the
   original is restored when the returned Promise settles."
  [reply f]
  (let [orig wire-node/knn-search]
    (set! wire-node/knn-search (stub-wire reply))
    (.finally (f) (fn [] (set! wire-node/knn-search orig)))))

(def ^:private not-ok-reply
  "The raw not-ok wire envelope `knn-search` resolves to on error."
  {"ok" false "error" "wire down (stub)"})

(deftest search-resolves-error-envelope-on-wire-failure
  ;; The old behavior THREW (→ rejected Promise → instrument wrapper records
  ;; a :core fault → :crash exits the dev pod). The fix: resolve an envelope.
  (async done
    (-> (with-wire-stub not-ok-reply
          #(embed/search {:seon.embed/query "anything" :seon.embed/db {}}))
        (.then (fn [{:seon.embed/keys [hits] :as res}]
                 (is (= [] hits) "hits key present and empty on failure")
                 (let [err (:seon/error res)]
                   (is (map? err) "carries a :seon/error map")
                   (is (= :core-bug (:seon.error/kind err)))
                   (is (re-find #"wire down"
                                (str (:seon.error/message err)))
                       "message surfaces the wire error"))))
        (settle! done))))

(deftest search-pull-passes-error-envelope-through-unchanged
  (async done
    (-> (with-wire-stub not-ok-reply
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
      (-> (with-wire-stub hits
            #(embed/search {:seon.embed/query "anything"
                            :seon.embed/db {}}))
          (.then (fn [res]
                   (is (= hits (:seon.embed/hits res)))
                   (is (nil? (:seon/error res)))))
          (settle! done)))))
