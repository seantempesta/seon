(ns my.ns-test
  "The my.ns/functions fn-listing contract over the program graph:
   indexed ns → the compact one-line cards (private fns excluded, sorted,
   rendered through the ONE card mechanism
   `seon.agent.ctx.namespaces/compact-fn-head`); unknown ns → a legible
   ok?-false envelope with the discovery hint; indexed-but-empty ns →
   empty success with a drill hint.

   Fresh :memory conn seeded like the pod boots (the my.data-test
   pattern): the conn installs on the ROOT db/*conn* (a `binding` pops at
   the first async hop), re-pinned before each ambient read."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [my.ns :as my-ns]
    [seon.client :as client]
    [seon.db :as db]))

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema — includes
   the `:seon.ns`/`:seon.fn` program-graph attrs the listing reads."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data (into (db/malli->datahike-schema
                                                  client/agent-bootstrap-attrs)
                                                (db/tx-meta-datahike-schema))})))
                     (.then (fn [_] conn))))))))

(defn- run-test
  "Seed conn as the ROOT db/*conn*, run (chain conn) → Promise, restore.

   `chain` runs inside a `.then` so even a SYNCHRONOUS throw becomes a
   rejection and `.finally` still restores the root conn — a leaked test
   conn wedges later async test namespaces."
  [chain done]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve)
                     (.then (fn [] (chain conn)))
                     (.finally (fn [] (set! db/*conn* orig)))))))
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "threw — " e)) (done)))))

(defn- pinned
  "Re-pin `conn` as the root db/*conn* before running `f` (the my.kb-test
   pattern — closes the concurrent-fiber contamination window)."
  [conn f]
  (fn [x] (set! db/*conn* conn) (f x)))

(defn- seed-demo-ns!
  "One indexed ns (:my.demo) with two public fns + one private."
  []
  (db/transact!
    {:seon.db/tx-data
     [{:db/id "ns" :seon.ns/name :my.demo}
      {:seon.fn/sym "my.demo/twice" :seon.fn/ns "ns"
       :seon.fn/doc "Double a number.\n\nThe mechanism story lives below the fold."
       :seon.fn/arglists "([n])"
       :seon.fn/spec "[:=> [:cat :int] :int]"}
      {:seon.fn/sym "my.demo/add" :seon.fn/ns "ns"
       :seon.fn/doc "Add two numbers."
       :seon.fn/arglists "([a b])"}
      {:seon.fn/sym "my.demo/secret-helper" :seon.fn/ns "ns"
       :seon.fn/private? true
       :seon.fn/arglists "([x])"}]}))

(deftest functions-lists-public-fns-as-compact-cards
  (async done
    (run-test
      (fn [conn]
        (-> (seed-demo-ns!)
            (.then (pinned conn
                     (fn [{ok? :seon.db/ok?}]
                       (is (true? ok?) "the seed tx landed")
                       (let [{ok? :seon.result/ok? :as res}
                             (my-ns/functions {:my.ns/ns 'my.demo})
                             cards (:my.ns/cards res)
                             twice (second cards)]
                         (is (true? ok?))
                         (is (= 2 (:my.ns/count res))
                             "two public fns; the private one is excluded")
                         (is (= 2 (count cards)))
                         (is (str/starts-with? (first cards) "(defn add")
                             "cards sort by name")
                         (is (str/starts-with? twice "(defn twice"))
                         (is (str/includes? twice "Double a number.")
                             "docstring LINE 1 rides the card")
                         (is (not (str/includes? twice "mechanism story"))
                             "the body of the doc is elided")
                         (is (str/includes? twice ":malli/schema")
                             "the spec renders in the head")
                         (is (str/includes? twice "[n]")
                             "the arglist renders")
                         (is (not-any? #(str/includes? % "secret-helper") cards)
                             "private fns never card")))))))
      done)))

(deftest functions-accepts-symbol-keyword-and-string
  (async done
    (run-test
      (fn [conn]
        (-> (seed-demo-ns!)
            (.then (pinned conn
                     (fn [_]
                       (let [by-sym (my-ns/functions {:my.ns/ns 'my.demo})
                             by-kw  (my-ns/functions {:my.ns/ns :my.demo})
                             by-str (my-ns/functions {:my.ns/ns "my.demo"})]
                         (is (= by-sym by-kw by-str)
                             "the three spellings are one question")))))))
      done)))

(deftest functions-unknown-ns-is-a-legible-error
  (async done
    (run-test
      (fn [conn]
        ((pinned conn
           (fn [_]
             (let [{ok? :seon.result/ok? :as res}
                   (my-ns/functions {:my.ns/ns 'no.such.place})]
               (is (false? ok?))
               (is (str/includes? (:my.ns/error res) "not indexed"))
               (is (str/includes? (:my.ns/hint res) ":seon.ns/name")
                   "the hint carries the discovery query"))))
         nil))
      done)))

(deftest functions-indexed-but-empty-ns-is-empty-success
  (async done
    (run-test
      (fn [conn]
        (-> (db/transact! {:seon.db/tx-data [{:seon.ns/name :my.hollow}]})
            (.then (pinned conn
                     (fn [_]
                       (let [{ok? :seon.result/ok? :as res}
                             (my-ns/functions {:my.ns/ns :my.hollow})]
                         (is (true? ok?) "no fns is SUCCESS, not an error")
                         (is (= [] (:my.ns/cards res)))
                         (is (= 0 (:my.ns/count res)))
                         (is (str/includes? (:my.ns/hint res) "render-namespace")
                             "the hint points at the full-ns drill")))))))
      done)))
