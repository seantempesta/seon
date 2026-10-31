(ns seon.stage-test
  "Staging the live runtime (seon.stage): a var unmapped from a program namespace and a
  method removed from its multimethod stop pointing to their forms; every other definition
  stays."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.test-store :as ts] [my.test :as mt] [seon.program :as program] [seon.program-test :as pt] [seon.stage :as stage] [seon.db :as pdb]
            [seon.cost]))

(deftest staging-retracts-exactly-what-the-runtime-dropped
  (let [db (pt/record (ts/test-db)
                      'user "(ns app.st-stage)"
                      'app.st-stage "(defn kept [x] x)"
                      'app.st-stage "(defn dropped [x] x)"
                      'app.st-stage "(deftype Pt [x y])"
                      'app.st-stage "(defmulti area :shape)"
                      'app.st-stage "(defmethod area :square [{:keys [s]}] (* s s))"
                      'app.st-stage "(defmethod area :circle [{:keys [r]}] (* 3 r r))")
        form-of (fn [sym] (:db/id (:var/form (d/entity db (pdb/eid db :var/name sym)))))
        ;; this namespace's definitions only: another test of this process may load the test
        ;; store's example namespaces without their vars, which stage-tx rightly retracts
        mine (fn [tx] (filterv #(let [x (d/entity db (second %))]
                                  (= "app.st-stage" (or (some-> (:var/name x) namespace) (some-> (:method/multi x) namespace))))
                               tx))]
    (testing "a runtime that matches the branch stages nothing, a deftype's type included (Jolt interns no var for it)"
      (is (= :type (:var/kind (d/entity db (pdb/eid db :var/name 'app.st-stage/Pt)))))
      (is (= [] (mine (stage/stage-tx db)))))
    (ns-unmap 'app.st-stage 'dropped)
    (remove-method @(find-var 'app.st-stage/area) :circle)
    (testing "the unmapped var and the removed method stop pointing to their forms, nothing else"
      (let [tx (mine (stage/stage-tx db))]
        (is (= 2 (count tx)) (pr-str tx))
        (is (some #{[:db/retract (pdb/eid db :var/name 'app.st-stage/dropped) :var/form (form-of 'app.st-stage/dropped)]} tx))
        (is (some #(= :method/form (nth % 2)) tx))
        (is (not-any? #(= (pdb/eid db :var/name 'app.st-stage/kept) (second %)) tx))))))

(defn- account-of
  "The account of form i, `(defn fi [x] x)` in app.stage-cost, as the compiler gives it."
  {:malli/schema [:=> [:cat :int] :map]}
  [i]
  {:id (str "app.stage-cost/f" i) :src (str "(defn f" i " [x] x)") :ns 'app.stage-cost :ns-form? false
   :defs [{:var (symbol "app.stage-cost" (str "f" i)) :kind :fn}] :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}})

(defn vars-of
  "stage-tx's argument: the world holding n more recorded forms (app.stage-cost/f0..fn-1; a db in memory:
  stage-tx reads only the recorded vars)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n] [(let [db (mt/world)] (d/db-with db (vec (mapcat #(program/account-tx db (account-of %) %) (range n)))))])

(deftest staging-is-one-pass-over-the-recorded-vars
  (seon.cost/proof
    {:of 'seon.stage/stage-tx :input 'seon.stage-test/vars-of
     :n "forms the branch records"
     :work (str "one scan of :var/name and of :var/form (each var's name looked up in a map), one "
                "ns-interns per namespace (it builds the namespace's map), and one map lookup per var; "
                "measured 2026-10-23 on Seon's program (2,128 forms): 5.2 ms, where one ns-interns per "
                "var took 40.8 ms")
     :growth :linear
     ;; measured 2026-10-31 on the world: 129.8 KB and 70.5k ns at 30 forms, 336.3 KB and 142.5k ns at 100
     ;; (2.95 KB and 1.0k ns a form, 41 KB and 40k ns fixed), the 2026-10-23 per-form figures held; the fixed was 2 MB and 1 ms on the test db
     :per-n {:ns 1100 :bytes 3000}
     :fixed {:ns 45000 :bytes 45000}
     :sizes [30 100 300]}))
