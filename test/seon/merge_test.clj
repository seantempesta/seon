(ns seon.merge-test
  "The program merge (seon.merge, in core) over db values: the three-way case table,
  identity by content (identical definitions on two branches merge clean whatever their
  entity ids), an unmap merging as a delete, the latest redefinition winning, flagged defs
  staying local, scratch data and observations never merging, unmerged entries derived
  from the branch, and behind meaning main's program moved."
  (:require [clojure.test :refer [deftest is are testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.merge :as pm]))

(defn- empty-db [] @(second (pdb/test-branch!)))

(defn- defn-src [nm body] (str "(defn " nm " {:malli/schema [:=> [:cat :int] :int]} [x] " body ")"))

(defn- var-tx
  "A recorded definition of app/nm (as seon.program/record-tx writes it)."
  [nm body]
  [{:db/id "app" :ns/name 'app}
   {:var/name (symbol "app" nm) :var/ns "app" :var/src (defn-src nm body) :var/kind :fn
    :var/contract "[:=> [:cat :int] :int]"}])

(defn- with [db & txs] (reduce d/db-with db txs))

(def base (with (empty-db) (var-tx "f" "x") (var-tx "g" "x") (var-tx "h" "x")))

(defn- unmap [nm] [[:db/retractEntity [:var/name (symbol "app" nm)]]])

(defn- tw [o t] (pm/three-way (pm/program base #{}) (pm/program o #{}) (pm/program t #{})))
(defn- vk [nm] [:var (symbol "app" nm)])

(deftest three-way-cases
  (testing "o = t: nothing to do (identical change, both deleted)"
    (are [o t] (let [r (tw o t)] (and (empty? (:apply r)) (empty? (:conflicts r))))
      (with base (var-tx "f" "(inc x)")) (with base (var-tx "f" "(inc x)"))
      (with base (unmap "f")) (with base (unmap "f"))
      (with base (var-tx "n" "1")) (with base (var-tx "n" "1"))))
  (testing "o = b: theirs applies (add, modify, delete)"
    (are [t k] (= [k] (:apply (tw base t)))
      (with base (var-tx "n" "1")) (vk "n")
      (with base (var-tx "f" "(dec x)")) (vk "f")
      (with base (unmap "g")) (vk "g")))
  (testing "t = b: ours kept, nothing applied, and it is an ours-change"
    (let [r (tw (with base (var-tx "f" "(dec x)")) base)]
      (is (empty? (:apply r))) (is (empty? (:conflicts r))) (is (= [(vk "f")] (keys (:ours r))))))
  (testing "conflicts carry their kind, base, ours and theirs"
    (are [o t kind] (= [kind] (map :kind (:conflicts (tw o t))))
      (with base (var-tx "f" "(inc x)")) (with base (var-tx "f" "(dec x)")) :both-modified
      (with base (var-tx "n" "1")) (with base (var-tx "n" "2")) :both-added
      (with base (unmap "f")) (with base (var-tx "f" "(dec x)")) :deleted-by-us
      (with base (var-tx "f" "(inc x)")) (with base (unmap "f")) :deleted-by-them)
    (let [c (first (:conflicts (tw (with base (var-tx "f" "(inc x)")) (with base (var-tx "f" "(dec x)")))))]
      (is (= (vk "f") (:key c)))
      (is (= (defn-src "f" "x") (:src (:base c))))
      (is (= (defn-src "f" "(inc x)") (:src (:ours c))))
      (is (= (defn-src "f" "(dec x)") (:src (:theirs c)))))))

(deftest identical-defs-on-two-branches-merge-clean
  ;; one side makes an unrelated entity first, so app/n gets a different entity id on each
  (let [o (with base [{:note/text "scratch"}] (var-tx "n" "(* 2 x)"))
        t (with base (var-tx "n" "(* 2 x)"))]
    (is (not= (d/q '[:find ?e . :where [?e :var/name app/n]] o) (d/q '[:find ?e . :where [?e :var/name app/n]] t)))
    (is (= {:apply [] :conflicts []} (select-keys (tw o t) [:apply :conflicts])))))

(deftest unmap-merges-as-delete
  (let [ours (with base (unmap "g"))
        r (tw ours base)
        main' (d/db-with base (pm/apply-tx base (into {} (for [[k [_ o]] (:ours r)] [k o]))))]
    (is (= {(vk "g") [(get (pm/program base #{}) (vk "g")) nil]} (:ours r)))
    (is (nil? (d/q '[:find ?e . :where [?e :var/name app/g]] main')))
    (is (= [:deleted-by-us] (map :kind (:conflicts (tw ours (with base (var-tx "g" "(dec x)")))))))))

(deftest latest-redefinition-wins
  (let [ours (with base (var-tx "f" "1") (var-tx "f" "2") (var-tx "f" "3"))]
    (is (= (defn-src "f" "3") (:src (second (get (:ours (tw ours base)) (vk "f"))))))))

(deftest flagged-defs-stay-local
  (testing "a flagged def maps to its base entry: never an ours-change, never a conflict"
    (let [ours (with base [{:db/id "app" :ns/name 'app} {:var/name 'app/counter :var/ns "app" :var/src "(def counter (atom 0))" :var/kind :value :var/flagged true}])
          theirs (with base [{:db/id "app" :ns/name 'app} {:var/name 'app/counter :var/ns "app" :var/src "(def counter 7)" :var/kind :value}])]
      (is (empty? (:ours (tw ours base))))
      (is (= [(vk "counter")] (:apply (tw ours theirs))))
      (is (empty? (:conflicts (tw ours theirs)))))))

(deftest scratch-and-observations-never-merge
  (let [ours (with base [{:note/text "scratch" :note/agent :a1}
                         {:seon.cluster.eval/id "r/1" :seon.cluster.eval/record "{:tag :ret :form \"(+ 1 2)\"}"}
                         {:seon.agent/id :a1 :seon.agent/answered-t 7}
                         {:task/name :x/y :task/status :done}])]
    (is (= (pm/program base #{}) (pm/program ours #{})))
    (is (empty? (:ours (tw ours base))))))

(deftest aliases-are-program-facts
  (let [ours (with base [[:db/add [:ns/name 'app] :ns/alias "[str clojure.string]"]])
        r (tw ours base)]
    (is (= {[:alias 'app 'str] [nil 'clojure.string]} (:ours r)))
    (is (= [:both-added] (map :kind (:conflicts (tw ours (with base [[:db/add [:ns/name 'app] :ns/alias "[str clojure.set]"]]))))))))

(deftest apply-tx-sets-each-key
  (let [p (pm/program (with base (var-tx "f" "(dec x)") (var-tx "n" "1") (unmap "h")) #{})
        db' (d/db-with base (pm/apply-tx base (select-keys p [(vk "f") (vk "n")])))
        db'' (d/db-with db' (pm/apply-tx db' {(vk "h") nil}))]
    (is (= (select-keys p [(vk "f") (vk "n")]) (select-keys (pm/program db' #{}) [(vk "f") (vk "n")])))
    (is (not (contains? (pm/program db'' #{}) (vk "h"))))))

(deftest unmerged-entry-derivation
  (let [o (with base (var-tx "f" "(inc x)"))
        t (with base (var-tx "f" "(dec x)"))
        cs (:conflicts (tw o t))
        b1 (d/db-with o (pm/unmerged-tx o cs 42))
        unmerged (fn [db] (mapv :key (pm/unmerged db (pm/program db #{}))))]
    (are [db expected] (= expected (unmerged db))
      ;; written, ours unchanged: unmerged
      b1 [(vk "f")]
      ;; re-defn of the var: resolved by derivation
      (d/db-with b1 (var-tx "f" "(+ x 0)")) []
      ;; resolve! keeping ours: marked
      (d/db-with b1 (pm/resolve-tx b1 (vk "f"))) [])
    (is (= 42 (:main-t (first (pm/entries b1)))))
    (testing "an attempt's conflict evidence (the same members, no :main-t) is not an entry"
      (is (= [(vk "f")] (mapv :key (pm/entries (d/db-with b1 [(pm/conflict-tx (first cs))]))))))
    (testing "a second conflict on the same key replaces the entry"
      (let [b2 (d/db-with b1 (pm/unmerged-tx b1 cs 43))]
        (is (= [43] (map :main-t (pm/entries b2))))))))

(deftest behind-means-main-program-moved
  (let [observed (with base [{:seon.agent/id :root :seon.agent/answered-t 9} {:note/text "attempt facts"}])]
    (is (empty? (:theirs (tw base observed))))
    (is (seq (:theirs (tw base (with base (var-tx "f" "(dec x)"))))))))

(deftest platform-records-never-put-a-branch-behind
  ;; root's records on main (boot, quiescence, recorded errors) are platform records: with
  ;; all data merged (the writer's :all) they change no key a branch carries; the task
  ;; content an agent may improve (my.task/update!) is carried
  (let [rooted (with base [{:seon.root/id :root :seon.root/quiescent "{:t 9 :stuck []}"}])
        tasked (with base [{:seon.task/id "t/x" :seon.task/plan "better"}])
        p #(pm/program % :all)]
    (is (empty? (:theirs (pm/three-way (p base) (p base) (p rooted)))))
    (is (some (fn [[k]] (= [:datum :seon.task/plan] [(first k) (nth k 2)])) (:ours (pm/three-way (p base) (p tasked) (p base)))))))

(deftest declared-data-merges-by-key
  ;; the data part, used by core's generic merge (every non-program, non-observation attribute)
  (let [o (with base [{:db/id 900 :note/text "n" :note/agent :a1}])
        r (pm/three-way (pm/program base :all) (pm/program o :all) (pm/program base :all))]
    (is (= #{[:datum 900 :note/text] [:datum 900 :note/agent]} (set (keys (:ours r)))))
    (is (empty? (:ours (pm/three-way (pm/program base #{}) (pm/program o #{}) (pm/program base #{})))))))
