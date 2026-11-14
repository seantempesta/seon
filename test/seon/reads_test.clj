(ns seon.reads-test
  "One index from a read key to its readers, matched against moved datoms. See docs/storage.md#what-a-read-scanned."
  (:require [clojure.test :refer [deftest is]] [seon.db :as pdb] [seon.contract :as contract] [datahike.datom :as dd] [datahike.api :as d] [seon.test-store :as ts] [seon.lib] [seon.task] [datahike.reads]))

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
                                                 :seon.proof/reads rs :seon.reads/key-set (set (map pr-str (:seon.reads/keys rs))) :seon.proof/status :pass :seon.proof/route :live
                                                 :seon.proof/basis {:seon.db/branch :main :seon.db/commit #uuid "00000000-0000-0000-0000-000000000001" :seon.db/t 7}
                                                 :seon.bound/spent {:seon.bound/ticks 5}}))))

(defn- proof
  "A result of test t that read keys ks, at db's basis, as tests' T-1 writes one."
  [db t ks status]
  {:seon.proof/id (seon.lib/digest (str t ks)) :seon.proof/test t :seon.proof/reads-digest (pdb/reads-digest db (set (map pr-str ks)) nil)
   :seon.proof/reads {:seon.reads/branch :db :seon.reads/commit #uuid "00000000-0000-0000-0000-000000000001" :seon.reads/keys (set ks)}
   :seon.reads/key-set (set (map pr-str ks)) :seon.proof/status status :seon.proof/route :live
   :seon.proof/basis {:seon.db/branch :db :seon.db/commit #uuid "00000000-0000-0000-0000-000000000001" :seon.db/t (:max-tx db)}})

;; See docs/plan.md#the-root-concept-one-reaction-over-the-store: the one index is stored, :seon.reads/key-set on every derived
;; entity, and a commit's datoms reach exactly the readers of the keys they touch, by seek on the db alone (no process state)
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-commits-datoms-rerun-the-readers-of-what-they-moved
  (let [db0 (pdb/db-with (ts/test-db) [{:note/text "read" :note/agent :t}])
        e (:e (first (d/datoms db0 :aevt :note/text)))
        k-ea [:ea e :note/text] k-a [:a :seon.task/plan] k-av [:av :seon.task/outcome :running]
        ;; two kinds of derived entity hold the one attribute: a test's result and any other (here a note standing for a page part)
        db (pdb/db-with db0 [(proof db0 'app-test/one #{k-ea k-av} :pass)
                             {:note/text "a page part" :note/agent :p :seon.reads/key-set #{(pr-str k-a) (pr-str k-ea)}}])
        name-of #(let [en (d/entity db %)] (or (:seon.proof/test en) (:note/agent en)))
        moved (fn [& ds] (set (map name-of (pdb/readers db (map #(apply dd/datom %) ds)))))]
    (is (= #{'app-test/one :p} (moved [e :note/text "x" 9])) "an entity's attribute: every reader of [:ea e a], of either kind")
    (is (= #{:p} (moved [77 :seon.task/plan "p" 9])) "an attribute read whole: any datom of it")
    (is (= #{'app-test/one} (moved [12 :seon.task/outcome :running 9])) "a new datom with a read value: the reader of [:av a v], an absence read")
    (is (= #{} (moved [77 :seon.task/kind :work 9])) "an attribute nobody read reaches nobody")
    (is (= #{'app-test/one :p} (moved [5 :db/cardinality :db.cardinality/one 9])) "a schema datom reaches every reader")
    (is (datahike.reads/reads-touched? #{k-ea} [(dd/datom e :note/text "x" 9)]) "a key set held in a value (a page component's walk) matches by Datahike's one function")
    (is (not (datahike.reads/reads-touched? #{k-ea} [(dd/datom e :note/agent :x 9)])))
    (is (and (contains? (:schema db) :seon.reads/key-set) (not (contains? (:schema db) :seon.proof/read-keys))) "one stored index: the proof's own key went with the rename")))

;; See docs/storage.md#what-a-read-scanned: a result is read back by seek from what moved, and its status is written once
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-result-is-found-by-what-moved-and-its-status-is-written-once
  (let [db0 (pdb/db-with (ts/test-db) [{:note/text "read" :note/agent :t}])
        e (:e (first (d/datoms db0 :aevt :note/text)))
        k-ea [:ea e :note/text] k-a [:a :seon.task/plan]
        db (pdb/db-with db0 [(proof db0 'app-test/one #{k-ea} :pass) (proof db0 'app-test/two #{k-a} :pass)])
        id-of #(:seon.proof/test (d/entity db %))
        moved (fn [& ds] (set (map id-of (pdb/readers db (map #(apply dd/datom %) ds)))))]
    (is (= #{'app-test/one} (moved [e :note/text "x" 9])) "an entity's attribute it read: its result, by one seek")
    (is (= #{'app-test/two} (moved [77 :seon.task/plan "p" 9])) "an attribute it read whole")
    (is (= #{} (moved [77 :seon.task/kind :work 9])) "an attribute no result read")
    (is (= '#{app-test/one} (set (map :seon.proof/test (d/q '[:find [(pull ?r [:seon.proof/test]) ...] :in $ ?s :where [?r :seon.reads/key-set ?s]] db (pr-str k-ea)))))
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
