(ns seon.stage-test
  "Staging the live runtime (seon.stage): a var unmapped from a program namespace and a
  method removed from its multimethod stop pointing to their forms; every other definition
  stays."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d]
            [seon.test-store :as ts] [seon.program-test :as pt] [seon.stage :as stage] [seon.db :as pdb]
            [seon.cost] [seon.program-cost-test]))

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

(defn vars-of
  "stage-tx's argument: a branch db holding n recorded forms (seon.program-cost-test/branch-of)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n] [(first (seon.program-cost-test/branch-of n))])

(deftest staging-is-one-pass-over-the-recorded-vars
  (seon.cost/proof
    {:of 'seon.stage/stage-tx :input 'seon.stage-test/vars-of
     :n "forms the branch records"
     :work (str "one scan of :var/name and of :var/form (each var's name looked up in a map), one "
                "ns-interns per namespace (it builds the namespace's map), and one map lookup per var; "
                "measured 2026-10-23 on Seon's program (2,128 forms): 5.2 ms, where one ns-interns per "
                "var took 40.8 ms")
     :growth :linear
     :per-n {:ns 3000 :bytes 3000}
     :fixed {:ns 1000000 :bytes 2000000}
     :sizes [100 300 1000 10000]}))
