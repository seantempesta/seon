(ns seon.reads-test
  "One index from a read key to its readers, matched against moved datoms. See docs/storage.md#what-a-read-scanned."
  (:require [clojure.test :refer [deftest is]] [seon.db :as pdb] [seon.contract :as contract] [datahike.datom :as dd] [datahike.api :as d] [seon.test-store :as ts] [seon.lib] [seon.task]))

;; See docs/storage.md#what-a-read-scanned
(deftest ^{:seon.test/estimate {:ticks 300000}} a-moved-datom-reaches-exactly-the-readers-of-its-keys
  (let [idx (-> {}
                (pdb/reads-add :page #{[:ea 10 :seon.task/plan] [:av :seon.task/outcome :running]})
                (pdb/reads-add :result #{[:a :seon.task/proofs] [:ea 10 :seon.task/plan]})
                (pdb/reads-add :other #{[:ea 11 :seon.task/plan]}))
        moved (fn [& ds] (pdb/readers idx (map #(apply dd/datom %) ds)))]
    (is (= #{:page :result} (moved [10 :seon.task/plan "new" 2]))
        "an entity's attribute: the readers of [:ea e a], never another entity's")
    (is (= #{:page} (moved [12 :seon.task/outcome :running 2])) "a new datom with a read value: the reader of [:av a v], an absence read")
    (is (= #{:result} (moved [99 :seon.task/proofs 'a/b 2])) "an attribute scan: any datom of it")
    (is (= #{} (moved [10 :seon.task/kind :work 2])) "an attribute nobody read reaches nobody")
    (is (= #{:page :result :other} (moved [5 :db/cardinality :db.cardinality/one 2])) "a schema datom reaches every reader")
    (is (= #{:result} (pdb/readers (pdb/reads-drop idx :page #{[:ea 10 :seon.task/plan] [:av :seon.task/outcome :running]})
                                     [(dd/datom 10 :seon.task/plan "x" 2)]))
        "a dropped reader is no longer reached; its keys with no reader left go")
    (is (= {} (pdb/reads-drop (pdb/reads-add {} :page #{[:a :x/y]}) :page #{[:a :x/y]})))))

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
                                                 :seon.proof/reads rs :seon.proof/read-keys (set (map pr-str (:seon.reads/keys rs))) :seon.proof/status :pass :seon.proof/route :live
                                                 :seon.proof/basis {:seon.db/branch :main :seon.db/commit #uuid "00000000-0000-0000-0000-000000000001" :seon.db/t 7}
                                                 :seon.proof/outside (:seon.reads/outside rs) :seon.bound/spent {:seon.bound/ticks 5}}))))

(defn- proof
  "A result of test t that read keys ks, at db's basis, as tests' T-1 writes one."
  [db t ks status]
  {:seon.proof/id (seon.lib/digest (str t ks)) :seon.proof/test t :seon.proof/reads-digest (pdb/reads-digest db (set (map pr-str ks)) nil)
   :seon.proof/reads {:seon.reads/branch :db :seon.reads/commit #uuid "00000000-0000-0000-0000-000000000001" :seon.reads/keys (set ks)}
   :seon.proof/read-keys (set (map pr-str ks)) :seon.proof/status status :seon.proof/route :live
   :seon.proof/basis {:seon.db/branch :db :seon.db/commit #uuid "00000000-0000-0000-0000-000000000001" :seon.db/t (:max-tx db)}})

;; See docs/storage.md#what-a-read-scanned: a result is read back by seek from what moved, and its status is written once
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-result-is-found-by-what-moved-and-its-status-is-written-once
  (let [db0 (pdb/db-with (ts/test-db) [{:note/text "read" :note/agent :t}])
        e (:e (first (d/datoms db0 :aevt :note/text)))
        k-ea [:ea e :note/text] k-a [:a :seon.task/plan]
        db (pdb/db-with db0 [(proof db0 'app-test/one #{k-ea} :pass) (proof db0 'app-test/two #{k-a} :pass)])
        id-of #(:seon.proof/test (d/entity db %))
        moved (fn [& ds] (set (map id-of (pdb/results-moved db (map #(apply dd/datom %) ds)))))]
    (is (= #{'app-test/one} (moved [e :note/text "x" 9])) "an entity's attribute it read: its result, by one seek")
    (is (= #{'app-test/two} (moved [77 :seon.task/plan "p" 9])) "an attribute it read whole")
    (is (= #{} (moved [77 :seon.task/kind :work 9])) "an attribute no result read")
    (is (= '#{app-test/one} (set (map :seon.proof/test (d/q '[:find [(pull ?r [:seon.proof/test]) ...] :in $ ?s :where [?r :seon.proof/read-keys ?s]] db (pr-str k-ea)))))
        "pulled back by what it read")
    (let [r (try (pdb/with db [{:seon.proof/id (seon.lib/digest (str 'app-test/one #{k-ea})) :seon.proof/status :fail}]) (catch clojure.lang.ExceptionInfo x (ex-data x)))]
      (is (= :seon.db/write-once (:seon.error/type r)) "a second, different status is refused, never written")
      (is (= [:pass :fail] [(:held r) (:new r)])))))

;; See docs/storage.md#what-a-read-scanned: equal content, one digest, whatever the basis
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-reads-digest-is-the-content-read-not-the-basis
  (let [db0 (pdb/db-with (ts/test-db) [{:note/text "read" :note/agent :t}])
        e (:e (first (d/datoms db0 :aevt :note/text)))
        ks #{(pr-str [:ea e :note/text])}
        later (pdb/db-with db0 [{:note/text "unrelated" :note/agent :t}])]
    (is (= (pdb/reads-digest db0 ks nil) (pdb/reads-digest later ks nil)) "a commit that moved nothing it read: one digest")
    (is (not= (pdb/reads-digest db0 ks nil) (pdb/reads-digest db0 #{(pr-str [:ea e :note/agent])} nil)) "another read, another digest")))

;; docs/plan.md stage 1.8: a task's causes are tags, several true at once, each held to its tag's schema
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-tasks-causes-are-tags-each-held-to-its-schema
  (let [db0 (ts/test-db)
        db (pdb/db-with db0 (seon.task/create-tx db0 [{:seon.task/ns 'zz :seon.task/name "blocked" :seon.task/plan "Make it." :seon.task/proofs #{'zz-test/blocked}}]))
        cause! (fn [cs] (try (pdb/db-with db [{:seon.task/id "zz/blocked" :seon.task/causes cs}]) (catch clojure.lang.ExceptionInfo x (ex-data x))))
        stale {:seon.db/tag :seon.cause/stale-program :seon.cause/var 'seon.x/f :seon.cause/held "f-1" :seon.cause/expected "f-2"}
        pin {:seon.db/tag :seon.cause/pin-off :seon.fx.read/pin "reference-code/jolt" :seon.cause/held "a" :seon.cause/expected "b"}]
    (is (= [stale pin] (:seon.task/causes (d/entity (cause! [stale pin]) [:seon.task/id "zz/blocked"]))) "two causes true at once")
    (is (= :seon.db/invalid-datom (:seon.error/type (cause! [(dissoc stale :seon.cause/var)]))) "a cause short of its tag's evidence is refused")
    (is (= :seon.db/invalid-datom (:seon.error/type (cause! [{:seon.db/tag :seon.cause/no-such :x 1}]))) "a tag no schema registers is refused")))
