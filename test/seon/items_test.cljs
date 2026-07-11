(ns seon.items-test
  "The shared collection-envelope shapes (`seon.items` + `seon.result`)
   are registered at load, and a producer envelope conforms. These shapes
   are load-bearing — `my.data` and the upcoming `my.recall`/`my.schedule`/
   `my.canvas` reference them, so their registration must survive a refactor.

   Run via bin/test-cljs, or interactively:
     (require 'seon.items-test :reload)
     (cljs.test/run-tests 'seon.items-test)"
  (:require
    [cljs.test :refer [deftest is testing]]
    [malli.core :as m]
    [seon.items]
    [seon.result]
    [seon.schema :as schema]))

(deftest envelope-shapes-registered
  (testing "the shared scalar + envelope shapes are registered under their own ns"
    (is (schema/registered? :seon.result/ok?))
    (is (schema/registered? :seon.items/items))
    (is (schema/registered? :seon.items/count))
    (is (schema/registered? :seon.items/envelope))))

(deftest producer-envelope-conforms
  (testing "a producer envelope validates against :seon.items/envelope"
    (is (m/validate :seon.items/envelope
                    {:seon.result/ok?  true
                     :seon.items/items [{:my.expense/category :dining :my.expense/amount-usd 12}]
                     :seon.items/count 1})))
  (testing "an empty collection is a valid envelope"
    (is (m/validate :seon.items/envelope
                    {:seon.result/ok? true :seon.items/items [] :seon.items/count 0})))
  (testing "a non-boolean ok? is rejected (the discriminator is :boolean)"
    (is (not (m/validate :seon.items/envelope
                         {:seon.result/ok? :yes :seon.items/items [] :seon.items/count 0})))))
