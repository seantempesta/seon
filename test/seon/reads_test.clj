(ns seon.reads-test
  "One index from a read key to its readers, matched against moved datoms. See docs/storage.md#what-a-read-scanned."
  (:require [clojure.test :refer [deftest is]] [seon.reads :as reads] [seon.contract :as contract] [datahike.datom :as dd]))

;; See docs/storage.md#what-a-read-scanned
(deftest ^{:seon.test/estimate {:ticks 300000}} a-moved-datom-reaches-exactly-the-readers-of-its-keys
  (let [idx (-> {}
                (reads/add :page #{[:ea 10 :seon.task/plan] [:av :seon.task/outcome :running]})
                (reads/add :result #{[:a :seon.task/proofs] [:ea 10 :seon.task/plan]})
                (reads/add :other #{[:ea 11 :seon.task/plan]}))
        moved (fn [& ds] (reads/readers idx (map #(apply dd/datom %) ds)))]
    (is (= #{:page :result} (moved [10 :seon.task/plan "new" 2]))
        "an entity's attribute: the readers of [:ea e a], never another entity's")
    (is (= #{:page} (moved [12 :seon.task/outcome :running 2])) "a new datom with a read value: the reader of [:av a v], an absence read")
    (is (= #{:result} (moved [99 :seon.task/proofs 'a/b 2])) "an attribute scan: any datom of it")
    (is (= #{} (moved [10 :seon.task/kind :work 2])) "an attribute nobody read reaches nobody")
    (is (= #{:page :result :other} (moved [5 :db/cardinality :db.cardinality/one 2])) "a schema datom reaches every reader")
    (is (= #{:result} (reads/readers (reads/drop-reader idx :page #{[:ea 10 :seon.task/plan] [:av :seon.task/outcome :running]})
                                     [(dd/datom 10 :seon.task/plan "x" 2)]))
        "a dropped reader is no longer reached; its keys with no reader left go")
    (is (= {} (reads/drop-reader (reads/add {} :page #{[:a :x/y]}) :page #{[:a :x/y]})))))

;; See docs/storage.md#what-a-read-scanned: the shared interfaces other areas build on validate the shapes they will hold
(deftest ^{:seon.test/estimate {:ticks 400000}} the-shared-read-bound-effect-and-proof-shapes-validate-what-they-name
  (let [rs {:seon.reads/branch :main :seon.reads/commit #uuid "00000000-0000-0000-0000-000000000001"
            :seon.reads/keys #{[:ea 10 :seon.task/plan] [:a :seon.task/proofs] [:av :seon.task/outcome :running] :any}
            :seon.reads/outside #{{:seon.fx.read/path "deps.edn" :seon.fx.read/digest "abc"} {:seon.fx.read/env "HOME" :seon.fx.read/value "/x"}}}]
    (is (contract/valid? :seon.reads/set rs))
    (is (not (contract/valid? :seon.reads/set (assoc rs :seon.reads/keys #{[:ea "ten" :seon.task/plan]}))) "an entity key names an entity id")
    (is (contract/valid? :seon.bound/bound {:seon.bound/ticks 1000000 :seon.bound/ms 50}))
    (is (not (contract/valid? :seon.bound/bound {:ticks 10})) "one set of names, all namespaced")
    (is (contract/valid? :seon.bound/reached {:seon.error/type :seon.bound/reached :seon.error/operation 'seon.x/f :seon.error/message "past its ticks"
                                              :seon.bound/bound {:seon.bound/ticks 10} :seon.bound/spent {:seon.bound/ticks 11} :seon.bound/at 'seon.x/f}))
    (is (contract/valid? :seon.fx/answer {:seon.fx/value "out" :seon.bound/spent {:seon.bound/ms 4} :seon.fx/reads [{:seon.fx.read/env "HOME" :seon.fx.read/value "/x"}]}))
    (is (contract/valid? :seon.fx/answer {:seon.error/type :seon.fx/io-error :seon.error/operation 'seon.fx/shell! :seon.error/message "killed"
                                          :seon.fx/indeterminate true :seon.bound/spent {:seon.bound/ms 50}}))
    (is (contract/valid? :seon.proof/proof-fact {:seon.proof/id "0123456789abcdef" :seon.proof/test 'seon.x-test/t :seon.proof/reads-digest "0123456789abcdef"
                                                 :seon.proof/reads rs :seon.proof/status :pass :seon.proof/route :live :seon.bound/spent {:seon.bound/ticks 5}}))))
