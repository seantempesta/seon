(ns seon.server.broadcast-routing-test
  "P1 per-DB broadcast routing tests for `seon.server.broadcast`.

   The in-process per-DB subscriber API (`subscribe!`/`unsubscribe!`) routes by
   the event's `:seon.store.wire/db-name`: a subscriber to cluster A NEVER
   receives cluster B's events. These tests drive `broadcast!` directly with
   db-name-tagged events (the same namespaced-keyword-keyed events the wire
   `::raw-broadcast` listener emits via `ok-event-from-report`) and assert the
   isolation invariant under load + interleaving, including a generative
   K-clusters x random-tx check.

   The socket-subscriber path (`start-pub-server!`) is exercised by
   `protocol_integration_test`; here we test the routed in-process path.

   Property tests drive `clojure.test.check/quick-check` from inside a plain
   `deftest` (not `defspec`) so clj-kondo resolves every symbol — same
   convention as `wire_props_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.server.broadcast :as bcast]))

(defn ^:private clean-subs [t]
  (bcast/reset-subscribers!)
  (try (t) (finally (bcast/reset-subscribers!))))

(use-fixtures :each clean-subs)

(defn- ev
  "A db-name-tagged tx event, shaped like the wire ::raw-broadcast emits —
   namespaced-keyword keys, exactly what `ok-event-from-report` produces and
   `broadcast!` routes on."
  [db-name basis-t]
  {:seon.store.wire/event "tx"
   :seon.store.wire/db-name db-name
   :seon.store.wire/basis-t basis-t})

;;; --- Basic routing ---------------------------------------------------------

(deftest subscriber-only-gets-its-own-db
  (testing "a subscriber to A receives A's events and none of B's"
    (let [a (atom []) b (atom [])]
      (bcast/subscribe! "cluster-a" #(swap! a conj %))
      (bcast/subscribe! "cluster-b" #(swap! b conj %))
      (bcast/broadcast! (ev "cluster-a" 1))
      (bcast/broadcast! (ev "cluster-b" 2))
      (bcast/broadcast! (ev "cluster-a" 3))
      (is (= [1 3] (mapv :seon.store.wire/basis-t @a)) "A saw only A's events, in order")
      (is (= [2]   (mapv :seon.store.wire/basis-t @b)) "B saw only B's events"))))

(deftest unsubscribe-stops-delivery
  (testing "after unsubscribe!, no further events arrive"
    (let [a (atom [])
          id (bcast/subscribe! "c" #(swap! a conj %))]
      (bcast/broadcast! (ev "c" 1))
      (bcast/unsubscribe! "c" id)
      (bcast/broadcast! (ev "c" 2))
      (is (= [1] (mapv :seon.store.wire/basis-t @a))))))

(deftest event-without-db-name-routes-nowhere
  (testing "an event missing :seon.store.wire/db-name reaches no per-DB subscriber"
    (let [a (atom [])]
      (bcast/subscribe! "c" #(swap! a conj %))
      (bcast/broadcast! {:seon.store.wire/event "tx" :seon.store.wire/basis-t 1})  ; no db-name
      (is (empty? @a)))))

(deftest many-subscribers-one-db
  (testing "N subscribers on the same db all receive that db's event"
    (let [hits (atom 0)]
      (dotimes [_ 25] (bcast/subscribe! "shared" (fn [_] (swap! hits inc))))
      (is (= 25 (bcast/db-subscriber-count "shared")))
      (bcast/broadcast! (ev "shared" 1))
      (is (= 25 @hits)))))

;;; --- Isolation under interleaved load --------------------------------------

(deftest isolation-under-interleaved-load
  (testing "20 clusters, 1 subscriber each; 400 interleaved events; each
            subscriber receives ONLY its cluster's events, in basis-t order,
            zero cross-bleed"
    (let [k        20
          per      20
          names    (mapv #(str "cluster-" %) (range k))
          captured (into {} (map (fn [n] [n (atom [])])) names)]
      (doseq [n names]
        (bcast/subscribe! n (fn [e] (swap! (captured n) conj (:seon.store.wire/basis-t e)))))
      ;; interleave: shuffle a monotonic basis-t per cluster
      (let [events (shuffle (for [n names t (range 1 (inc per))] [n t]))]
        (doseq [[n t] events]
          (bcast/broadcast! (ev n t))))
      (doseq [n names]
        (let [got @(captured n)]
          (is (= per (count got)) (str n " got exactly its " per " events"))
          (is (= (set (range 1 (inc per))) (set got))
              (str n " got precisely basis-t 1.." per ", nothing from siblings")))))))

;;; --- Generative isolation invariant ----------------------------------------

(defn- isolation-property
  "Generate up to 8 clusters and a random interleaving of db-name-tagged events;
   assert each cluster's subscriber saw EXACTLY the basis-ts broadcast for that
   cluster, in emission order, and nothing from any sibling."
  []
  (prop/for-all
   [plan (gen/let [k        (gen/choose 2 8)
                   n-events (gen/choose 1 120)]
           (gen/vector
            (gen/tuple (gen/choose 0 (dec k)) gen/nat)
            n-events))]
   (bcast/reset-subscribers!)
   (let [clusters (into (sorted-set) (map first) plan)
         names    (into {} (map (fn [i] [i (str "g-" i)])) clusters)
         captured (into {} (map (fn [i] [i (atom [])])) clusters)]
     (doseq [i clusters]
       (bcast/subscribe! (names i)
                         (fn [e] (swap! (captured i) conj (:seon.store.wire/basis-t e)))))
     (doseq [[i t] plan]
       (bcast/broadcast! (ev (names i) t)))
     (let [expected (reduce (fn [m [i t]] (update m i (fnil conj []) t)) {} plan)]
       (every? (fn [i] (= (get expected i []) @(captured i))) clusters)))))

(deftest generative-per-db-isolation
  (testing "K clusters x random interleaved tx → strict per-DB isolation"
    (let [result (tc/quick-check 80 (isolation-property))]
      (is (true? (:pass? result))
          (str "shrunk counterexample: "
               (pr-str (get-in result [:shrunk :smallest]))
               " (failed after " (:num-tests result) " tests)")))))
