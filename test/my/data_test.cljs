(ns my.data-test
  "my.data is the aggregation surface — turn stored rows into the answer
   without a hand-rolled datalog aggregate. Two contracts:

     1. THE REDUCERS are pure over MAPS — sum-by / max-by / group-sum need
        no db. The footgun tests live here: a dataset that a naive
        `(sum ?x)` (no `:with`) would DEDUP-collapse must sum correctly, and
        an argmax must return the winning ROW (not the bare value).

     2. THE PRODUCER + COMPOSITION use ordinary results from the `seon.db`
        query seam — `rows` finds entities by attribute presence,
        and `rows → group-sum → max-by` answers 'biggest category'. The
        my.kb/source-stats refactor is exercised end-to-end so it can't
        bit-rot.

   No test embeds Datahike: the JVM authority owns database execution, while
   these toolkit tests own the query shape and ordinary-data composition."
  (:require
    [cljs.test :refer [deftest is async testing]]
    [my.data :as data]
    [my.kb :as kb]
    [seon.db :as db]))

(defn- finish
  [promise done]
  (-> promise
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "threw — " e)) (done)))))

(defn- with-query-result
  [result body]
  (let [saved db/query]
    (set! db/query (fn [& _] (js/Promise.resolve result)))
    (-> (js/Promise.resolve)
        (.then (fn [] (body)))
        (.finally (fn [] (set! db/query saved))))))

(defn- with-rows-result
  [result body]
  (let [saved data/rows]
    (set! data/rows (fn [_] (js/Promise.resolve result)))
    (-> (js/Promise.resolve)
        (.then (fn [] (body)))
        (.finally (fn [] (set! data/rows saved))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; 1. THE REDUCERS — pure over maps. The two footguns are GONE.
;;; ───────────────────────────────────────────────────────────────────────

(deftest sum-by-totals-and-the-dedup-footgun-is-gone
  ;; A naive datalog `(sum ?c)` (no `:with ?e`) collapses the two rows of 5
  ;; into one 5 → 12. Reducing over MAPS can't dedup: 5+5+7 = 17.
  (let [items [{:my.subscription/name "A" :my.subscription/monthly-usd 5}
               {:my.subscription/name "B" :my.subscription/monthly-usd 5}
               {:my.subscription/name "C" :my.subscription/monthly-usd 7}]]
    (is (= 17 (data/sum-by {:seon.items/items items
                            :my.data/key :my.subscription/monthly-usd}))
        "repeated values must each count — the :with dedup collapse is gone"))
  (is (= 0 (data/sum-by {:seon.items/items [] :my.data/key :my.subscription/monthly-usd}))
      "empty → 0")
  (is (= 7 (data/sum-by {:seon.items/items [{:my.subscription/monthly-usd 7}
                                            {:my.subscription/name "no-amount"}]
                         :my.data/key :my.subscription/monthly-usd}))
      "rows missing the key are skipped, like (sum ?x) over asserters only"))

(deftest max-by-returns-the-row-not-the-value
  (let [items [{:my.subscription/name "Netflix" :my.subscription/monthly-usd 18}
               {:my.subscription/name "Adobe CC" :my.subscription/monthly-usd 45}
               {:my.subscription/name "iCloud" :my.subscription/monthly-usd 11}]
        winner (data/max-by {:seon.items/items items
                             :my.data/key :my.subscription/monthly-usd})]
    (is (map? winner) "argmax returns the ENTITY, not the bare 45")
    (is (= "Adobe CC" (:my.subscription/name winner))
        "read the name straight off the winning row — no (max ?x)+rejoin"))
  (testing "ties: the FIRST row at the max wins (strict >)"
    (is (= "first" (:n (data/max-by {:seon.items/items [{:n "first" :v 9}
                                                        {:n "second" :v 9}]
                                     :my.data/key :v})))))
  (is (nil? (data/max-by {:seon.items/items [] :my.data/key :v}))
      "no items → nil"))

(deftest group-sum-tallies-per-group-as-an-envelope
  (let [items [{:my.expense/amount-usd 28 :my.expense/category :dining}
               {:my.expense/amount-usd 52 :my.expense/category :dining}
               {:my.expense/amount-usd 26 :my.expense/category :dining}
               {:my.expense/amount-usd 73 :my.expense/category :groceries}
               {:my.expense/amount-usd 40 :my.expense/category :transport}]
        env   (data/group-sum {:seon.items/items items
                               :my.data/group-key :my.expense/category
                               :my.data/key :my.expense/amount-usd})
        by-g  (into {} (map (juxt :my.data/group :my.data/total)
                            (:seon.items/items env)))]
    (is (true? (:seon.result/ok? env)) "a :seon.items/* envelope")
    (is (= 3 (:seon.items/count env)) "three distinct categories")
    (is (= {:dining 106 :groceries 73 :transport 40} by-g)
        "each category's rows sum independently — dining = 28+52+26 = 106")))

;;; ───────────────────────────────────────────────────────────────────────
;;; 2. THE PRODUCER + COMPOSITION — against a seeded db.
;;; ───────────────────────────────────────────────────────────────────────

(deftest rows-finds-by-attribute-presence
  (async done
    (finish
      (with-query-result
        [{:my.subscription/name "Netflix" :my.subscription/monthly-usd 18}
         {:my.subscription/name "Spotify" :my.subscription/monthly-usd 12}
         {:my.subscription/name "Adobe CC" :my.subscription/monthly-usd 45}
         {:my.subscription/name "iCloud" :my.subscription/monthly-usd 11}
         {:my.subscription/name "NYT" :my.subscription/monthly-usd 15}]
        (fn []
          (-> (data/rows {:my.data/attr :my.subscription/name})
              (.then (fn [env]
                       (is (true? (:seon.result/ok? env)))
                       (is (= 5 (:seon.items/count env)) "every subscription, by presence")
                       (is (every? map? (:seon.items/items env)) "self-describing maps")
                       (is (= 101 (data/sum-by (merge env {:my.data/key :my.subscription/monthly-usd})))
                           "rows → sum-by via the merge arrow = 101")
                       (is (= "Adobe CC"
                              (:my.subscription/name
                                (data/max-by (merge env {:my.data/key :my.subscription/monthly-usd}))))
                           "rows → max-by = the Adobe row"))))))
      done)))

(deftest rows-returns-the-error-value-when-the-query-fails
  ;; the swallow regression: a failed query yields the `:seon.error/*` map,
  ;; and (vec <map>) would turn it into MapEntry "rows" reported ok? true.
  (async done
    (finish
      (with-query-result
        {:seon.error/message "writer unavailable"
         :seon.error/kind :core-bug}
        (fn []
          (-> (data/rows {:my.data/attr :my.subscription/name})
              (.then (fn [env]
                       (is (false? (:seon.result/ok? env))
                           "a failed query is ok? false, never fake rows")
                       (is (re-find #"writer unavailable" (:my.data/error env))
                           "the query failure message is carried through")
                       (is (not (contains? env :seon.items/items))
                           "no items key — absent, not MapEntry garbage"))))))
      done)))

(deftest composition-rows-group-sum-max-by-biggest-category
  (async done
    (finish
      (with-query-result
        [{:my.expense/merchant "Thai Place" :my.expense/amount-usd 28 :my.expense/category :dining}
         {:my.expense/merchant "Sushi Bar" :my.expense/amount-usd 52 :my.expense/category :dining}
         {:my.expense/merchant "Cafe Luna" :my.expense/amount-usd 26 :my.expense/category :dining}
         {:my.expense/merchant "Trader Joe's" :my.expense/amount-usd 73 :my.expense/category :groceries}
         {:my.expense/merchant "Shell" :my.expense/amount-usd 40 :my.expense/category :transport}]
        (fn []
          (-> (data/rows {:my.data/attr :my.expense/amount-usd})
              (.then (fn [exp]
                     (let [
                           totals  (data/group-sum (merge exp {:my.data/group-key :my.expense/category
                                                               :my.data/key :my.expense/amount-usd}))
                           biggest (data/max-by (merge totals {:my.data/key :my.data/total}))]
                       (is (= :dining (:my.data/group biggest))
                           "argmax over the GROUPS, not the rows — dining, not the $73 grocery row")
                       (is (= 106 (:my.data/total biggest))
                           "and its exact total")
                       ;; falsify: a flat sum-by over rows would give 219 (wrong),
                       ;; a flat max-by over rows would give the $73 row (wrong category).
                       (is (not= 219 (:my.data/total biggest))
                           "not the flat all-rows sum")))))))
      done)))

;;; ───────────────────────────────────────────────────────────────────────
;;; 3. THE REFACTOR — my.kb/source-stats now delegates to my.data.
;;; ───────────────────────────────────────────────────────────────────────

(deftest source-stats-delegates-to-my-data
  (async done
    (finish
      (with-rows-result
        {:seon.result/ok? true
         :seon.items/count 3
         :seon.items/items
         [{:my.kb.source/rating 5 :my.kb.source/topics [:lisp :databases]}
          {:my.kb.source/rating 4 :my.kb.source/topics [:agents]}
          {:my.kb.source/rating 5 :my.kb.source/topics [:lisp]}]}
        (fn []
          (-> (kb/source-stats)
              (.then (fn [stats]
                     ;; 3 sources, ratings 5 + 4 + 5 = 14 (two 5s do NOT collapse),
                     ;; topics tallied across the cardinality-many vectors.
                     (is (= 3 (:my.kb/count stats)))
                     (is (= 14 (:my.kb/rating-total stats))
                         "two sources rated 5 sum to 10, not collapse to 5")
                     (is (= 2 (:lisp (:my.kb/topic-counts stats)))
                         "topic frequencies over the many-valued attr"))))))
      done)))
