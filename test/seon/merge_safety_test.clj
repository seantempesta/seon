(ns seon.merge-safety-test
  "What merging a branch into main assumes about the data, each assumption a proof on the world: a branch and main
  each move by a generated sequence of real writes (tasks, notes, refs to vars, nested maps naming a var by its content
  id, a definition), main is merged into the branch, then the branch into main, as the writer and an agent's
  merge-main! do (seon.merge/delta-three-way, merge-tx, unmerged-tx over datahike.api/merge-db), and the result is held
  to: ids issued once, no silent overwrite (a true conflict is an unmerged entry and the merge up is refused), main
  equal to the branch after, nothing else on main moved, a second merge changes nothing, every carried datom keeps its
  ids, and every entity the merge touched passes the write check. The same claims on the one merge (task
  seon.merge/a-merge-replays-the-sources-net-change: seon.merge/plan, then the source's transactions replayed, replay-up!)
  are the tests after it, each the smallest reproduction of one assumption, the broken writes' property among them. See
  docs/storage.md#merges-read-what-changed and docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program."
  (:require [clojure.test :refer [deftest is testing]] [clojure.set :as set]
            [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb] [seon.merge :as pm] [seon.program :as program]
            [seon.contract :as contract] [seon.test-store :as ts] [seon.proofs :as proofs] [seon.config :as config] [my.schema :as schema]))

;; ---- the world's writes --------------------------------------------------------------

(def ^:private tx0 "The first transaction id (datahike.constants/tx0): counter entity ids are below it." 536870912)
(def ^:private ce0 "The first content id (datahike.constants/ce0, 2^40)." 1099511627776)

(def ^:private one-attrs "The card-one data attributes the writes use." #{:task/name :task/plan :task/status :note/text :note/agent})
(def ^:private many-attrs "The card-many data attributes the writes use." #{:task/refs})

(defn- pick [^java.util.Random rng xs] (when (seq xs) (nth (vec xs) (.nextInt rng (count xs)))))
(defn- es [db a] (mapv :e (d/datoms db :aevt a)))
(defn- v-of [db e a] (:v (first (d/datoms db :eavt e a))))
(defn- has? [db e a v] (boolean (seq (d/datoms db :eavt e a v))))
(defn- notes-of [db who] (mapv :e (d/datoms db :avet :note/agent who)))

(defn- defn-tx
  "A recorded definition of nsym/nm with body (as seon.program/record-tx writes it): a fn of the db."
  [nsym nm body]
  (fn [db]
    (let [src (str "(defn " nm " {:malli/schema [:=> [:cat :int] :int]} [x] " body ")")]
      (program/record-tx db {:id (str nsym "/" src) :src src :ns nsym :ns-form? false
                             :defs [{:var (symbol (str nsym) nm) :meta #{:arglists} :contract "[:=> [:cat :int] :int]"}]
                             :declares #{} :methods [] :impls [] :refs #{} :requires #{} :banned #{} :inline-defs #{}}))))

(def ^:private safe-ops
  "The writes the property draws, on either side: each holds today."
  [:new-task :plan :status :ref+ :ref- :note+ :note-text :retract-own :nested-var :defn :both-nested :both-defn :both-refs :both-same
   :redefine-shared])

(def ^:private broken-ops
  "The writes whose merge broke an assumption of the old merge (each now a test on the one merge below): a base entity retracted whole, a ref to a
  var made only on the branch, two sides making one task name, a task retracted whole on one side and extended on the other,
  one task's plan changed on both sides (:both-plan, and :plan-any when the sides meet)."
  [:retract-base :retract-task :nested-new :both-task :both-plan :plan-any])

(defn- pool
  "The base entities of xs side changes a card-one value of: the branch the first three, main the next three, so the two
  never meet on one value (a meeting is :plan-any's, a finding)."
  [xs side] (if (= side :branch) (take 3 xs) (take 3 (drop 3 xs))))

(defn- write
  "One generated write of side (:branch or :main) on db, as [op tx-data] (tx-data a fn of the db for a definition), or
  nil when db holds nothing it applies to. k numbers the write, so a value it writes is its own."
  [db rng side k op]
  (let [tasks (es db :task/name)
        vars (vec (take 20 (es db :var/form)))
        tx (case op
             :new-task [{:task/name (keyword (str "gen." (name side)) (str "t" k)) :task/plan (str (name side) " plan " k) :task/status :held}]
             ;; a card-one value of a task of this side's own pool: the branch's first three, main's next three (so main moves
             ;; values the branch never touches), and both sides' first three under :plan-any, where the two meet
             :plan (when-let [t (pick rng (pool tasks side))] [[:db/add t :task/plan (str (name side) " plan " k)]])
             :status (when-let [t (pick rng (pool tasks side))] [[:db/add t :task/status (pick rng [:held :ready :done])]])
             :ref+ (when-let [t (pick rng tasks)] [[:db/add t :task/refs (pick rng vars)]])
             :ref- (when-let [dt (pick rng (d/datoms db :aevt :task/refs))] [[:db/retract (:e dt) :task/refs (:v dt)]])
             :note+ [{:note/text (str (name side) " note " k) :note/agent side}]
             :plan-any (when-let [t (pick rng (take 3 tasks))] [[:db/add t :task/plan (str (name side) " plan " k)]])
             :note-text (when-let [n (pick rng (concat (notes-of db side) (pool (notes-of db :base) side)))] [[:db/add n :note/text (str (name side) " text " k)]])
             :retract-own (when-let [n (pick rng (notes-of db side))] [[:db/retractEntity n]])
             :retract-base (when-let [n (pick rng (notes-of db :base))] [[:db/retractEntity n]])
             :retract-task (when-let [t (pick rng tasks)] [[:db/retractEntity t]])
             ;; a nested map naming an existing var by its content id: the same entity on every branch
             :nested-var (when-let [t (pick rng tasks)] [{:db/id t :task/refs [{:var/name (:v (first (d/datoms db :eavt (pick rng vars) :var/name)))}]}])
             ;; a var only this side names, nested under a ref: no form, so it is no program key and never carried
             :nested-new (when-let [t (pick rng tasks)] [{:db/id t :task/refs [{:var/name (symbol (str "gen." (name side)) (str "v" k))}]}])
             :defn (defn-tx 'gen.code (str (name side) "-f" k) "x")
             :redefine-shared (defn-tx 'gen.code "shared" (str "(+ x " (.nextInt rng 3) ")"))
             nil)]
    (when tx [op tx])))

(defn- apply! [conn side tx]
  (let [tx (if (fn? tx) (tx @conn) tx)]
    (d/transact conn {:tx-data tx :tx-meta (pdb/tx-meta (:branch (:config @conn)))})))

(defn- write!
  "Write [op tx] on conn, or nil when the write check refuses it (a write no agent could make is no branch)."
  [conn side [op tx]]
  (try (apply! conn side tx) op
       (catch clojure.lang.ExceptionInfo _ nil)))

(defn- both!
  "A write both sides make: :both-nested, one new var named by content id under a ref on each side; :both-defn, one
  definition, each side's body its own; :both-refs, one task's refs, each side adding its own; :both-same, one task's plan,
  one value on both sides; :both-plan, one task's plan, each side's value its own; :both-task, one task name
  made on each side (a counter id each)."
  [mc bc k op]
  (let [nm (symbol "gen.both" (str "v" k))]
    (case op
      :both-nested (doseq [c [mc bc]]
                     (when-let [t (first (es @c :task/name))] (apply! c :both [{:db/id t :task/refs [{:var/name nm}]}])))
      ;; one task's plan changed on each side to a value of its own: a true conflict
      :both-plan (doseq [[c side] [[mc :main] [bc :branch]]]
                   (apply! c side [[:db/add (first (es @c :task/name)) :task/plan (str (name side) " plan " k)]]))
      ;; one definition redefined on each side, each its own body: a true conflict of the program
      :both-defn (doseq [[c side] [[mc :main] [bc :branch]]] (apply! c side (defn-tx 'gen.code "contested" (str "(+ x " (name side) ")"))))
      ;; no conflicts: a many-valued attribute each side adds its own value to (the union), and one value both sides write
      :both-refs (doseq [[c i] [[mc 0] [bc 1]]]
                   (let [t (first (es @c :task/name))] (apply! c :both [[:db/add t :task/refs (nth (es @c :var/form) i)]])))
      ;; on the seventh task, which neither side's own pool changes (pool), so the one value stays both sides' one value
      :both-same (doseq [c [mc bc]] (apply! c :both [[:db/add (nth (es @c :task/name) 6) :task/plan (str "agreed " k)]]))
      :both-task (doseq [c [mc bc]] (apply! c :both [{:task/name (keyword "gen.both" (str "t" k)) :task/plan "p" :task/status :held}])))
    op))

;; ---- the merges, as Seon makes them ---------------------------------------------------

(defn- merge-main-into!
  "Main merged into the branch on bc (seon.agent/merge-main!'s three-way and tx, data :all): what main changed since
  base that does not conflict, each conflict an unmerged entry. Answers the three-way."
  [bc base main]
  (let [head @bc r (pm/delta-three-way base head main :all)]
    (when (seq (:theirs r))
      (d/merge-db bc #{(d/commit-id main)} (-> (pm/merge-tx head base main (:apply r) :all)
                                              (into (pm/unmerged-tx head (:conflicts r) (:max-tx main))))
                  (pdb/tx-meta (:branch (:config @bc)) {:seon.tx/merged (:branch (:config main))})))
    r))

(defn- merge-up!
  "The branch br merged into main on conn as the writer's octopus merge makes it (seon.writer/octopus-merge, data
  :all): refused when main moved since the merge base or the branch holds unmerged entries. Answers {:r :base :head
  :rep} or {:refused why}."
  [conn br]
  (let [main @conn head (pdb/head conn br) base (pdb/branch-base conn (:branch (:config main)) br)
        r (pm/delta-three-way base head main :all)]
    (cond (seq (:theirs r)) {:refused :behind :r r}
          (seq (pm/unmerged head)) {:refused :unmerged :r r}
          :else (let [tx (pm/merge-tx main base head (keys (:ours r)) :all)]
                  {:r r :base base :head head :tx tx
                   :rep (d/merge-db conn #{(d/commit-id head)} tx (pdb/tx-meta (:branch (:config main))))}))))

(defn- replay-up!
  "The branch br merged into main on conn as the writer's merge op now makes it (seon.writer/octopus-merge, task
  seon.merge/a-merge-replays-the-sources-net-change): planned from the three values (seon.merge/plan), then each group
  as its own transaction through the connection's checked transact (datahike.writer/apply!), the last with the branch's
  head as a merge parent, committed once; on a conflict or an entity with no identity nothing is written, and a group
  the write check refuses abandons them all. Answers the plan, with :refused {:tx :error} when the write was refused."
  [conn br]
  (let [main @conn head (pdb/head conn br) base (pdb/branch-base conn (:branch (:config main)) br)
        p (pm/plan base main head)
        meta0 (pdb/tx-meta (:branch (:config main)))]
    (if-not (seq (:groups p))
      p
      (let [refused (some (fn [g] (let [r (dw/apply! conn {:tx-data (:ops g) :tx-meta (merge meta0 (select-keys (:meta g) [:seon.tx/branch :seon.tx/eval]))})]
                                    (when-not (map? r) {:tx (:tx g) :error (or (ex-data r) (ex-message r))})))
                          (:groups p))
            refused (or refused (let [r (dw/apply! conn {:parents #{(d/commit-id head)} :tx-data [] :tx-meta meta0} {:op 'merge!})]
                                  (when-not (map? r) {:tx -1 :error (ex-message r)})))]
        (if refused
          (do (dw/abandon-pending! conn) (assoc p :refused refused))
          (do (dw/commit-pending! conn) p))))))

;; ---- what a merge must hold ------------------------------------------------------------

(defn- added-by [base db] (filterv :added (second (d/datoms-diff base db :eavt []))))

(defn- touched-keys
  "Every data datum [e a] (card one) and [e a v] (card many) that differs between base and o or t."
  [base o t]
  (into #{} (for [db [o t] :let [[gone added] (d/datoms-diff base db :eavt [])] dt (concat gone added)
                  :when (or (one-attrs (:a dt)) (many-attrs (:a dt)))]
              (if (one-attrs (:a dt)) [(:e dt) (:a dt)] [(:e dt) (:a dt) (:v dt)]))))

(defn- value [db [e a v]] (if v (has? db e a v) (v-of db e a)))

(defn- check-round
  "The assertions of one round, msg naming the seed and round."
  [conn bc br base main0 head0 msg seen]
  (let [own-b (added-by base head0) own-m (added-by base main0)
        ;; the counter ids each side minted: entities below tx0 that base does not hold
        counter (fn [ds] (into #{} (comp (map :e) (filter #(and (< % tx0) (empty? (d/datoms base :eavt %))))) ds))]
    (testing "ids are issued once across branches: no entity or transaction the two sides made shares an id"
      (is (empty? (set/intersection (counter own-b) (counter own-m))) msg)
      (is (empty? (set/intersection (set (map :tx own-b)) (set (map :tx own-m)))) msg))
    (let [ks (touched-keys base head0 main0)
          conflict? (fn [k] (let [b (value base k) o (value head0 k) t (value main0 k)] (and (not= o b) (not= t b) (not= o t))))
          expected (into {} (for [k ks] [k (let [b (value base k) o (value head0 k) t (value main0 k)] (if (= o b) t o))]))
          r1 (merge-main-into! bc base main0)
          up (merge-up! conn br)
          after @conn]
      (testing "a true conflict (both sides changed one card-one value differently) is an unmerged entry, and the merge up is refused"
        (doseq [k ks :when (conflict? k)]
          (is (some #(= [:datum (first k) (second k)] (:key %)) (:conflicts r1)) (str msg " conflict " k))
          (is (= :unmerged (:refused up)) (str msg " refused " k))))
      (swap! seen conj (if (:refused up) :refused :merged))
      (if (:refused up)
        (testing "a refused merge leaves main as it was"
          (is (not= :behind (:refused up)) msg)
          (is (= (d/commit-id main0) (d/commit-id after)) msg))
        (let [{:keys [r head rep tx]} up merge-t (:max-tx after) carried (set (map (juxt :e :a :v :tx :added) tx))]
          (testing "no silent overwrite: each datum is the side that changed it, or both sides' one value"
            (doseq [k ks] (is (= (expected k) (value after k)) (str msg " datum " k))))
          (testing "the branch's program, data included, is main's after the merge, and main moved nowhere else"
            (is (= {} (pm/delta-changes head after :all)) msg)
            (is (empty? (remove (set (keys (:ours r))) (keys (pm/delta-changes main0 after :all)))) msg))
          (testing "every datom main gained besides the merge's own is one the branch wrote, its e and tx kept"
            (is (every? #(carried ((juxt :e :a :v :tx :added) %)) (remove #(= merge-t (:tx %)) (:tx-data rep))) msg))
          (testing "the merge's own transaction adds no datom but its own facts: what it carries keeps the t it was written at"
            (is (empty? (filter #(and (:added %) (= merge-t (:tx %)) (not= merge-t (:e %))) (:tx-data rep))) msg))
          (testing "every ref the merge wrote names an entity main holds"
            (is (every? #(seq (d/datoms after :eavt (:v %))) (filter #(and (:added %) (many-attrs (:a %))) (:tx-data rep))) msg))
          (testing "every entity the merge touched passes the write check (seon.db/invalid-entity)"
            (is (nil? (pdb/invalid-entity rep)) msg))
          (testing "merging the same branch again changes nothing"
            (let [again (pdb/speculate after (pm/merge-tx after (:base up) head (keys (:ours r)) :all))]
              (is (every? #(= (:max-tx (:db-after again)) (:tx %)) (:tx-data again)) msg)
              (is (= {} (:ours (pm/delta-three-way (pdb/branch-base conn (:branch (:config after)) br) (pdb/head conn br) after :all))) msg))))))))

(defn- error-of [f] (try (f) nil (catch Exception e (or (some (comp :error ex-data) (take-while some? (iterate ex-cause e))) (ex-message e)))))

(defn- property
  "Rounds of the merge property from seed, ops the writes drawn: each round a branch of main, then 2 to 10 writes
  interleaved between the branch and main, then the two merges, checked (check-round). About 50 writes a seed, and the
  seed must reach both a refused (conflicting) merge and a clean one."
  [seed ops]
  (ts/test-branch!
   (fn [cfg conn]
     (let [rng (java.util.Random. seed) seen (atom [])]
       (d/transact conn {:tx-data (vec (for [i (range 4)] {:note/text (str "base note " i) :note/agent :base}))})
       (dotimes [round 8]
         (let [br (keyword (str (name (:branch cfg)) "-r" round))
               _ (d/branch! conn (:branch cfg) br)
               bc (pdb/connect (assoc cfg :branch br))]
           (try
             (let [base @conn]
               (doseq [k (range (+ 2 (.nextInt rng 9)))
                       :let [k (+ (* 100 round) k) op (pick rng ops)]]
                 (if (#{:both-nested :both-defn :both-refs :both-same :both-plan :both-task} op)
                   (both! conn bc k op)
                   (let [[c side] (pick rng [[conn :main] [bc :branch]])]
                     (some->> (write @c rng side k op) (write! c side)))))
               ;; a merge that throws is a finding of its own, named, and the next round still runs
               (let [msg (str "seed " seed " round " round)
                     thrown (error-of #(check-round conn bc br base @conn @bc msg seen))]
                 (is (nil? thrown) (str msg ": the merge threw"))))
             (finally (d/release bc) (d/delete-branch! conn br)))))
       ;; the seed reached both outcomes, so neither half of the property held vacuously
       (is (= #{:refused :merged} (set @seen)) (str "seed " seed " reached " (frequencies @seen)))))))

(defn- seeds [] (let [s (proofs/test-seed)] (map #(+ s %) (range (config/value :seon.config.proofs/seeds)))))

;; docs/storage.md#merges-read-what-changed: any branch built by the writes that hold merges to main with every assumption kept
(deftest any-branch-merges-to-main-safely
  (doseq [seed (seeds)] (property seed safe-ops)))

;; ---- the findings, each its smallest reproduction ---------------------------------------

(defn- branched
  "(f conn bc br) on a branch br of a fresh main holding a task and a note, both released after."
  [f]
  (ts/test-branch!
   (fn [cfg conn]
     (d/transact conn {:tx-data [{:task/name :gen/t :task/plan "p" :task/status :held} {:note/text "n" :note/agent :base}]})
     (let [br (keyword (str (name (:branch cfg)) "-b")) _ (d/branch! conn (:branch cfg) br) bc (pdb/connect (assoc cfg :branch br))]
       (try (f conn bc br) (finally (d/release bc) (d/delete-branch! conn br)))))))

;; ---- the one merge's conflicts, computed from the two sides' net change (task seon.merge/a-merge-replays-the-sources-net-change) ----

(defn- sides
  "(f ours theirs) with each side's net change since the base of a branch: ours is main's after on-main ran on
  it, theirs the branch's after on-branch."
  [on-main on-branch f]
  (branched
   (fn [conn bc br]
     (let [base @conn t (pdb/eid base :task/name :gen/t) n (first (notes-of base :base))]
       (on-main conn t n) (on-branch bc t n)
       (f (pm/net-change base @conn) (pm/net-change base @bc))))))

(defn- sides-db
  "(f base main branch) with the three values of a branch after on-main ran on main and on-branch on it."
  [on-main on-branch f]
  (branched
   (fn [conn bc br]
     (let [base @conn t (pdb/eid base :task/name :gen/t) n (first (notes-of base :base))]
       (on-main conn t n) (on-branch bc t n)
       (f base @conn @bc)))))

(deftest two-ends-of-one-key-are-a-conflict-by-identity
  (sides (fn [c t _] (d/transact c [[:db/add t :task/plan "main's plan"]]))
         (fn [c t _] (d/transact c [[:db/add t :task/plan "the branch's plan"]]))
         (fn [ours theirs]
           (is (= [{:key [:task/name :gen/t :task/plan] :base "p" :ours "main's plan" :theirs "the branch's plan"}] (pm/conflicts ours theirs))
               "the key names the task by its identity, never an entity id, and both ends are its values"))))

(deftest one-end-on-both-sides-and-two-keys-of-one-entity-merge
  (sides (fn [c t _] (d/transact c [[:db/add t :task/plan "one plan"]]))
         (fn [c t _] (d/transact c [[:db/add t :task/plan "one plan"] [:db/add t :task/status :ready]]))
         (fn [ours theirs]
           (is (= "one plan" (get (:keys ours) [:task/name :gen/t :task/plan])) "main's change is its key's new value")
           (is (contains? (:keys theirs) [:task/name :gen/t :task/status]) "the branch changed a second key")
           (is (= [] (pm/conflicts ours theirs)) "the same end on both sides, and a key only one side changed, merge"))))

(deftest a-change-to-an-entity-the-other-side-retracted-is-a-conflict
  (sides (fn [c t _] (d/transact c [[:db/retractEntity t]]))
         (fn [c t _] (d/transact c [[:db/add t :task/status :ready]]))
         (fn [ours theirs]
           (is (= [{:key [:task/name :gen/t :task/status] :base :held :theirs :ready :ours :seon.merge/gone}] (pm/conflicts ours theirs))
               "the branch wrote to a task main retracted: its write is the conflict, though main never changed that key"))))

(deftest an-entity-with-no-identity-is-named-not-carried
  (sides (fn [_ _ _] nil)
         (fn [c _ n] (d/transact c [[:db/add n :note/text "changed on the branch"]]))
         (fn [_ theirs]
           (is (= #{#{:note/text :note/agent}} (:unidentified theirs)) "a note bears no identity: the change names its attributes")
           (is (empty? (:keys theirs)) "and no key falls back to its entity id"))))

(deftest a-replay-is-the-sources-transactions-in-order-and-brings-its-net-change
  (branched
   (fn [conn bc br]
     (let [base @conn t (pdb/eid base :task/name :gen/t)]
       (apply! bc :branch [[:db/add t :task/plan "first"]])
       (apply! bc :branch [{:task/name :gen/new :task/plan "made on the branch" :task/status :held}])
       (apply! bc :branch [[:db/add t :task/plan "second"] [:db/add t :task/status :ready]])
       (apply! bc :branch [{:task/name :gen/gone :task/plan "made and retracted" :task/status :held}])
       (apply! bc :branch [[:db/retractEntity (pdb/eid @bc :task/name :gen/gone)]])
       (let [groups (pm/replay base @bc)
             ;; through the write check, one transaction a group, as the writer's op will
             refused (vec (keep (fn [g] (error-of #(apply! conn :main (:ops g)))) groups))
             landed @conn]
         (is (= 5 (count groups)) "one group for each of the source's transactions since the base, the entity made and retracted included")
         (is (apply < (map :tx groups)) "in the source's order")
         (is (every? #(= br (:seon.tx/branch (:meta %))) groups) "each carries its source transaction's own facts: the branch that wrote it")
         (is (not-any? #(contains? (:meta %) :db/txInstant) groups) "and never its time: the receiving branch gives each a new one")
         (is (= [] refused) "every group passes the write check on main: each state it makes is one the branch held")
         (is (= (:keys (pm/net-change base @bc)) (:keys (pm/net-change base landed)))
             "main after the replay holds the branch's net change, key for key, by identity"))))))

(deftest a-merge-is-planned-from-three-values-before-anything-is-written
  ;; both sides wrote the same key to two ends: the plan is its conflict, and no group
  (sides-db (fn [c t _] (d/transact c [[:db/add t :task/plan "main's plan"]]))
            (fn [c t _] (d/transact c [[:db/add t :task/plan "the branch's plan"]]))
            (fn [base main branch]
              (is (= {:conflicts [{:key [:task/name :gen/t :task/plan] :base "p" :ours "main's plan" :theirs "the branch's plan"}]}
                     (pm/plan base main branch)))))
  ;; the branch changed a note, which bears no identity: named, never carried, and no group
  (sides-db (fn [_ _ _] nil)
            (fn [c _ n] (d/transact c [[:db/add n :note/text "changed on the branch"]]))
            (fn [base main branch]
              (is (= {:unidentified #{#{:note/text :note/agent}}} (pm/plan base main branch)))))
  ;; main and the branch changed two keys of one task: no conflict, and the plan is the branch's transactions
  (sides-db (fn [c t _] (d/transact c [[:db/add t :task/plan "main's plan"]]))
            (fn [c t _] (d/transact c [[:db/add t :task/status :ready]]))
            (fn [base main branch]
              (let [p (pm/plan base main branch)]
                (is (= [:groups] (keys p)))
                (is (= [[[:db/retract :task/status :held] [:db/add :task/status :ready]]]
                       (mapv (fn [g] (mapv (fn [[op _ a v]] [op a v]) (:ops g))) (:groups p)))
                    "one group, the branch's one transaction, whole"))))
  ;; nothing on the branch that main lacks: no group
  (sides-db (fn [c t _] (d/transact c [[:db/add t :task/plan "main's plan"]]))
            (fn [_ _ _] nil)
            (fn [base main branch] (is (= {:groups []} (pm/plan base main branch))))))

;; ---- the merge up as the writer's op makes it: the plan, then the source's transactions (task seon.merge/a-merge-replays-the-sources-net-change) ----

;; by the plan: both sides changed one task's plan to two ends, so nothing is written
(deftest a-data-conflict-holds-the-merge-up
  (branched
   (fn [conn bc br]
     (let [t (pdb/eid @conn :task/name :gen/t)]
       (d/transact conn [[:db/add t :task/plan "main's plan"]])
       (d/transact bc [[:db/add t :task/plan "the branch's plan"]])
       (let [t0 (:max-tx @conn) r (replay-up! conn br)]
         (is (= [[:task/name :gen/t :task/plan]] (mapv :key (:conflicts r))) "the plan names the conflict by the task's identity")
         (is (= "main's plan" (v-of @conn t :task/plan)) "main keeps its value until the conflict is resolved")
         (is (= t0 (:max-tx @conn)) "and nothing is written"))))))

;; by the plan, on an entity with an identity (a note has none: its change is
;; named as :unidentified and never carried, an-entity-with-no-identity-is-named-not-carried): main retracted a task whole, the
;; branch changed its plan
(deftest a-retraction-against-a-change-is-a-conflict
  (branched
   (fn [conn bc br]
     (let [t (pdb/eid @conn :task/name :gen/t)]
       (d/transact conn [[:db/retractEntity t]])
       (d/transact bc [[:db/add t :task/plan "changed on the branch"]])
       (let [r (replay-up! conn br)]
         (is (= [{:key [:task/name :gen/t :task/plan] :base "p" :theirs "changed on the branch" :ours :seon.merge/gone}] (:conflicts r))
             "the plan names the retraction against the change")
         (is (empty? (d/datoms @conn :eavt t)) "main keeps the task retracted until the conflict is resolved")))))
  ;; the same on a note: named, never written
  (branched
   (fn [conn bc br]
     (let [n (first (notes-of @conn :base))]
       (d/transact conn [[:db/retractEntity n]])
       (d/transact bc [[:db/add n :note/text "changed on the branch"]])
       (let [t1 (:max-tx @conn) r (replay-up! conn br)]
         (is (seq (:unidentified r)) "a note bears no identity: its change is named")
         (is (and (= t1 (:max-tx @conn)) (empty? (d/datoms @conn :eavt n))) "and main keeps the note retracted, nothing written"))))))

;; by the plan: each side made one task name with one value, each a
;; counter id of its own. The net changes meet with one end (no conflict by key), and the branch's transaction, replayed on
;; main, claims the identity main's entity holds: the write check refuses it, the merge answers that as data, and nothing is written
(deftest two-entities-claiming-one-identity-are-a-conflict
  (branched
   (fn [conn bc br]
     (doseq [c [conn bc]] (d/transact c [{:task/name :gen/same :task/plan "p" :task/status :held}]))
     (let [e-main (pdb/eid @conn :task/name :gen/same) t0 (:max-tx @conn)
           r (error-of #(replay-up! conn br))]
       (is (nil? r) "the merge answers, never throws")
       (let [r (replay-up! conn br)]
         (is (or (seq (:conflicts r)) (:refused r)) "a conflict, or the branch's transaction refused by main's write check")
         (is (= t0 (:max-tx @conn)) "and nothing is written")
         (is (= [e-main] (mapv :e (d/datoms @conn :avet :task/name :gen/same))) "main's one entity holds the name"))))))

;; merged up by the plan: main retracted a task whole,
;; the branch gave it a ref
(deftest a-branchs-write-to-an-entity-main-retracted-is-kept-or-a-conflict
  (branched
   (fn [conn bc br]
     (let [t (pdb/eid @conn :task/name :gen/t) v (first (es @conn :var/form)) t0 (do (d/transact conn [[:db/retractEntity t]]) (:max-tx @conn))]
       (d/transact bc [[:db/add t :task/refs v]])
       (let [r (replay-up! conn br)]
         (is (= [[[:task/name :gen/t :task/refs] true :seon.merge/gone]] (mapv (juxt #(subvec (:key %) 0 3) :theirs :ours) (:conflicts r)))
             "the branch's ref to a task main retracted is the conflict")
         (is (and (= t0 (:max-tx @conn)) (empty? (d/datoms @conn :eavt t))) "and nothing is written: main keeps the task retracted"))))))

;; docs/storage.md#the-write-checks-every-entity-it-touches: a merge is a transaction on a connection, so the write check holds it.
;; Main gave a task a ref, the branch retracted the task whole: the one merge names that a conflict (a write to an entity the other
;; side retracted) and writes nothing; and a merge transaction ({:op 'merge!}, datahike.writer/apply!) that would leave a task
;; holding only a ref is refused by the check, as any transaction is
(deftest a-merge-passes-the-write-check
  (branched
   (fn [conn bc br]
     (let [t (pdb/eid @conn :task/name :gen/t) v (first (es @conn :var/form))]
       (d/transact conn [[:db/add t :task/refs v]])
       (d/transact bc [[:db/retractEntity t]])
       (let [t0 (:max-tx @conn) r (replay-up! conn br)]
         (is (seq (:conflicts r)) "the ref main wrote to the task the branch retracted is a conflict")
         (is (= t0 (:max-tx @conn)) "and nothing is written: main holds no task broken by a merge"))
       (let [rep (dw/apply! conn {:parents #{(d/commit-id @bc)} :tx-data [[:db/retract t :task/name :gen/t] [:db/retract t :task/plan "p"]]
                                  :tx-meta (pdb/tx-meta (:branch (:config @conn)))}
                            {:op 'merge!})]
         (dw/abandon-pending! conn)
         (is (not (map? rep)) "a merge transaction that leaves a task holding only a ref is refused by the write check")
         (is (= :seon.db/invalid-entity (:seon.error/type (ex-data rep))) (pr-str (ex-data rep))))))))

;; docs/merging.md#seons-merge-is-a-three-way-merge-of-the-program: a ref the branch writes to an entity only it holds (a var
;; named by its content id, with no form, so no program key) is carried, and so is the entity: main holds what the ref names
(deftest a-carried-ref-names-an-entity-main-holds
  (branched
   (fn [conn bc br]
     (let [t (pdb/eid @conn :task/name :gen/t)]
       (d/transact bc [{:db/id t :task/refs [{:var/name 'gen.only/v}]}])
       (let [r (replay-up! conn br) v (pdb/eid @bc :var/name 'gen.only/v)]
         (is (seq (:groups r)) (pr-str r))
         (is (has? @conn t :task/refs v) "the ref is carried")
         (is (seq (d/datoms @conn :eavt v)) "and names an entity main holds"))))))

;; docs/storage.md#history: main as of a t it held before the merge reads only what main held then: a merge writes the
;; branch's transactions as new transactions of main, so a t main held before it never reads the branch's facts
(deftest main-as-of-a-t-reads-what-main-held-then
  (branched
   (fn [conn bc br]
     (d/transact bc [{:task/name :gen/branch :task/plan "made on the branch" :task/status :held}])
     (d/transact conn [{:task/name :gen/main :task/plan "made on main" :task/status :held}])
     (let [t-main (:max-tx @conn) held (pdb/head conn (:branch (:config @conn)))
           r (replay-up! conn br)
           then (pdb/at conn @conn {:t t-main})]
       (is (seq (:groups r)) (pr-str r))
       (is (some? (pdb/eid @conn :task/name :gen/branch)) "main holds the branch's task after the merge")
       (is (= (some? (pdb/eid held :task/name :gen/branch)) (some? (pdb/eid then :task/name :gen/branch)))
           "main as of t holds the branch's task only if main held it at t")))))

;; docs/storage.md#history, datahike.db.transaction/next-tx-instant: main's instants rise with its t after a merge, and the last
;; transaction is the latest: a merge writes the branch's transactions as new ones of main, each stamped after main's last
(deftest mains-instants-rise-with-its-t-after-a-merge
  (branched
   (fn [conn bc br]
     (let [c (java.util.Date. (+ (System/currentTimeMillis) (* 1000 60 60 24 365))) t0 (:max-tx @conn)]
       (with-redefs [datahike.tools/get-date (constantly c)]
         (dotimes [i 3] (d/transact bc [{:task/name (keyword "gen" (str "b" i)) :task/plan "p" :task/status :held}])))
       (d/transact conn [{:task/name :gen/m :task/plan "p" :task/status :held}])
       (is (seq (:groups (replay-up! conn br))))
       (let [db @conn
             txs (sort (map :e (d/datoms db :aevt :db/txInstant)))
             at (fn [t] (.getTime ^java.util.Date (v-of db t :db/txInstant)))]
         (is (apply < (map at (filter #(> % t0) txs))) "main's transactions' instants rise with their t")
         (is (every? #(< (at %) (at (:max-tx db))) (butlast txs)) "the last transaction is the latest"))))))

;; docs/storage.md#ids, case 2 with a ref identity (:seon.vector/of, a vector's identity is the entity it embeds): each side made
;; its own vector of one form. The one merge names the identity both sides claim (its key by :seon.vector/of), answers, never
;; throws, and writes nothing
(deftest one-ref-identity-on-two-entities-is-a-conflict
  (branched
   (fn [conn bc br]
     (let [f (first (es @conn :form/id))
           vec-of (fn [x] {:seon.vector/of f :seon.vector/model "m" :seon.vector/text (str "text " x)
                           :seon.vector/f16 (byte-array [1 2]) :seon.vector/coarse (byte-array [3])})]
       (d/transact conn [(vec-of "main")])
       (d/transact bc [(vec-of "branch")])
       (is (not= (pdb/eid @conn :seon.vector/of f) (pdb/eid @bc :seon.vector/of f)) "two entities, one identity")
       (let [t0 (:max-tx @conn) r (error-of #(replay-up! conn br))]
         (is (nil? r) "the merge answers, never throws")
         (let [r (replay-up! conn br)]
           (is (some #(= :seon.vector/of (first (:key %))) (:conflicts r)) "the one merge names the identity both sides claim")
           (is (= t0 (:max-tx @conn)) "and writes nothing")))))))

;; docs/storage.md#merges-read-what-changed, the data model session's ask: a value compares by its content. A vector's embedding
;; is a byte array: the same bytes written on both sides are no conflict (seon.merge/conflicts, datahike.datom/value=), and
;; writing the bytes an entity holds is a no-op
(deftest byte-values-compare-by-content
  (let [k [:seon.vector/of 1 :seon.vector/f16]]
    (is (= [] (pm/conflicts {:keys {k (byte-array [1 2])}} {:keys {k (byte-array [1 2])}})) "the same bytes written on both sides are no conflict")
    (is (seq (pm/conflicts {:keys {k (byte-array [1 2])}} {:keys {k (byte-array [1 3])}})) "and two contents are"))
  (branched
   (fn [conn bc br]
     (let [f (first (es @conn :form/id))]
       (d/transact conn [{:seon.vector/of f :seon.vector/model "m" :seon.vector/text "t" :seon.vector/f16 (byte-array [0]) :seon.vector/coarse (byte-array [3])}])
       (d/branch! conn (:branch (:config @conn)) (keyword (str (name br) "-v")))
       (let [vb (keyword (str (name br) "-v")) vc (pdb/connect (assoc (:config @conn) :branch vb)) e (pdb/eid @conn :seon.vector/of f)]
         (try
           (doseq [c [conn vc]] (d/transact c [[:db/add e :seon.vector/f16 (byte-array [1 2])]]))
           (let [rep (pdb/speculate @conn [[:db/add e :seon.vector/f16 (byte-array [1 2])]])]
             (is (empty? (remove #(= (:e %) (:max-tx (:db-after rep))) (:tx-data rep))) "writing the bytes an entity holds is a no-op"))
           (let [r (replay-up! conn vb)]
             (is (empty? (:conflicts r)) (str "the same bytes on both sides merge: " (pr-str (:conflicts r)))))
           (finally (d/release vc) (d/delete-branch! conn vb))))))))

;; ---- the property on the one merge: a branch merged up as the writer's op makes it (replay-up!) ----

(defn- check-round-up
  "The assertions of one round on the one merge (replay-up!), msg naming the seed and round: the claims of check-round,
  each as the one merge states it (it merges up only; main into the branch is a later piece)."
  [conn br base main0 head0 msg seen]
  (let [own-b (added-by base head0) own-m (added-by base main0)
        counter (fn [ds] (into #{} (comp (map :e) (filter #(and (< % tx0) (empty? (d/datoms base :eavt %))))) ds))]
    (testing "ids are issued once across branches: no entity or transaction the two sides made shares an id"
      (is (empty? (set/intersection (counter own-b) (counter own-m))) msg)
      (is (empty? (set/intersection (set (map :tx own-b)) (set (map :tx own-m)))) msg))
    (let [ks (touched-keys base head0 main0)
          conflict? (fn [k] (let [b (value base k) o (value head0 k) t (value main0 k)] (and (not= o b) (not= t b) (not= o t))))
          expected (into {} (for [k ks] [k (let [b (value base k) o (value head0 k) t (value main0 k)] (if (= o b) t o))]))
          up (replay-up! conn br)
          refused? (not (and (:groups up) (nil? (:refused up))))
          after @conn]
      (testing "a true conflict (both sides changed one card-one value differently) refuses the merge up"
        (doseq [k ks :when (conflict? k)] (is refused? (str msg " refused " k))))
      (swap! seen conj (if refused? :refused :merged))
      (if refused?
        (testing "a refused merge leaves main as it was"
          (is (= (d/commit-id main0) (d/commit-id after)) msg))
        (let [nb (:keys (pm/net-change base head0)) na (:keys (pm/net-change base after))]
          (testing "no silent overwrite: each datum is the side that changed it, or both sides' one value"
            (doseq [k ks] (is (= (expected k) (value after k)) (str msg " datum " k))))
          (testing "the branch's net change, data included, is main's after the merge, and main moved nowhere else"
            (is (every? (fn [[k v]] (= v (get na k ::none))) nb) msg)
            (is (every? #(contains? nb %) (keys (:keys (pm/net-change main0 after)))) msg))
          (testing "every datom main gained is one the branch wrote, its e kept (its transaction is main's own, new)"
            (let [wrote (into #{} (map (juxt :e :a :v :added)) (second (d/datoms-diff (d/history base) (d/history head0) :eavt [])))
                  tx? (fn [e] (seq (d/datoms after :eavt e :db/txInstant)))]
              (is (every? #(wrote ((juxt :e :a :v :added) %)) (remove #(tx? (:e %)) (second (d/datoms-diff (d/history main0) (d/history after) :eavt [])))) msg)))
          (testing "every ref the merge wrote names an entity main holds"
            (is (every? #(seq (d/datoms after :eavt (:v %))) (filter #(many-attrs (:a %)) (added-by main0 after))) msg))
          (testing "merging the same branch again changes nothing"
            (let [t1 (:max-tx @conn) again (replay-up! conn br)]
              (is (empty? (:groups again)) msg)
              (is (= t1 (:max-tx @conn)) msg))))))))

(defn- property-up
  "Rounds of the merge property on the one merge from seed, ops the writes drawn: as property, each round's branch merged
  up by replay-up! and checked (check-round-up)."
  [seed ops]
  (ts/test-branch!
   (fn [cfg conn]
     (let [rng (java.util.Random. seed) seen (atom [])]
       (d/transact conn {:tx-data (vec (for [i (range 4)] {:note/text (str "base note " i) :note/agent :base}))})
       (dotimes [round 8]
         (let [br (keyword (str (name (:branch cfg)) "-r" round))
               _ (d/branch! conn (:branch cfg) br)
               bc (pdb/connect (assoc cfg :branch br))]
           (try
             (let [base @conn]
               (doseq [k (range (+ 2 (.nextInt rng 9)))
                       :let [k (+ (* 100 round) k) op (pick rng ops)]]
                 (if (#{:both-nested :both-defn :both-refs :both-same :both-plan :both-task} op)
                   (both! conn bc k op)
                   (let [[c side] (pick rng [[conn :main] [bc :branch]])]
                     (some->> (write @c rng side k op) (write! c side)))))
               (let [msg (str "seed " seed " round " round)
                     thrown (error-of #(check-round-up conn br base @conn @bc msg seen))]
                 (is (nil? thrown) (str msg ": the merge threw"))))
             (finally (d/release bc) (d/delete-branch! conn br)))))
       (is (= #{:refused :merged} (set @seen)) (str "seed " seed " reached " (frequencies @seen)))))))

;; docs/storage.md#merges-read-what-changed: the same property with every write, the broken ones included, on the one merge
(deftest any-branch-with-any-write-merges-to-main-safely
  (doseq [seed (seeds)] (property-up seed (into safe-ops broken-ops))))

;; docs/source.md#schemas-at-the-repl: a branch registers an entity of its own (my.schema/registration, as my.schema/register! writes
;; it: the schema entries and the Datahike declarations of their attributes) and writes one; merged up by the one merge, main holds
;; the attribute and the value
(deftest a-branchs-new-attribute-and-its-value-merge-up
  (branched
   (fn [conn bc br]
     (doseq [[k v] [[:app.mg/id [:and {:seon.db/identity true} :string]] [:app.mg/size :int]
                    [:app.mg/thing [:map {:seon.db/attributes true} [:app.mg/id :app.mg/id] [:app.mg/size :app.mg/size]]]]]
       (let [r (schema/registration @bc k v false)]
         (is (not (contract/error? r)) (pr-str r))
         (d/transact bc {:tx-data (:tx r) :tx-meta (pdb/tx-meta br)})))
     (let [k (program/schema-changes @bc)]
       (d/transact bc {:tx-data [{:app.mg/id "one" :app.mg/size 3}] :tx-meta (pdb/tx-meta br) :seon.db/schemas {:forms k :registry (program/schema-registry k)}}))
     (let [r (replay-up! conn br)]
       (spit "/tmp/mergeop/new-attr.edn" (pr-str (-> r (update :groups #(mapv (fn [g] (update g :ops (fn [ops] (mapv (fn [o] (vec (take 3 o))) ops)))) %)))))
       (is (seq (:groups r)) (pr-str (dissoc r :groups)))
       (is (nil? (:refused r)) (pr-str (:refused r)))
       (is (contains? (d/schema @conn) :app.mg/size) "main holds the attribute")
       (is (= 3 (:app.mg/size (d/entity @conn [:app.mg/id "one"]))) "and the value")))))

;; docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together: an entity holding two identities is named, in a net change, by
;; the identity whose attribute's ident sorts first, whichever order a branch installed the attributes in
(deftest an-entity-with-two-identities-is-named-by-the-first-ident
  (branched
   (fn [conn bc br]
     (let [main-b (:branch (:config @conn))
           keys-of (fn [order]
                     (let [b (keyword (str (name br) "-" (name (first order)))) _ (d/branch! conn main-b b) c (pdb/connect (assoc (:config @conn) :branch b))]
                       (try
                         (doseq [k order]
                           (let [r (schema/registration @c k [:and {:seon.db/identity true} :string] false)] (d/transact c {:tx-data (:tx r)})))
                         (let [r (schema/registration @c :app.two/thing [:map {:seon.db/attributes true} [:app.two/a-id :app.two/a-id] [:app.two/b-id :app.two/b-id]] false)]
                           (d/transact c {:tx-data (:tx r)}))
                         (let [base (pdb/branch-base conn main-b b) k (program/schema-changes @c)]
                           (d/transact c {:tx-data [{:app.two/b-id "b" :app.two/a-id "a"}] :seon.db/schemas {:forms k :registry (program/schema-registry k)}})
                           (into #{} (comp (map first) (filter #{:app.two/a-id :app.two/b-id})) (keys (:keys (pm/net-change base @c)))))
                         (finally (d/release c) (d/delete-branch! conn b)))))]
       (let [ab (keys-of [:app.two/a-id :app.two/b-id]) ba (keys-of [:app.two/b-id :app.two/a-id])]
         (is (= #{:app.two/a-id} ab) (str "installed a then b: " (pr-str ab)))
         (is (= ab ba) (str "installed b then a: " (pr-str ba))))))))

;; docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together: every entity schema names an identity attribute, its own or
;; a schema's it extends, so what crosses a merge is named by its identity (seon.merge/net-change's :unidentified is the
;; exception's refusal). The exceptions: a transaction's own facts (:seon.tx/meta, never merged), a merge's stored conflict entry
;; (:seon.merge/entry-fact, deleted with the one merge's schema commit) and a note (:seon.db/note-fact); and three attribute
;; groups that ride an entity identified otherwise: :seon.config/config (the keys of :seon.config/config-fact, named by
;; :seon.config/id), :seon.config/sources-fact (on root's entity, :seon.root/id) and :seon.script/fact (on a file, :file/path)
(deftest every-entity-schema-names-an-identity
  (let [ix (pdb/index) forms (:forms ix)
        entity? (fn [k] (let [f (get forms k)] (and (vector? f) (map? (second f)) (:seon.db/attributes (second f)))))
        ids (set (keys (:identity-schemas ix)))
        own (fn [k] (set (for [[a s] (:entity-schemas ix) :when (= s k)] a)))
        reach (fn [k] (tree-seq (constantly true) #(#'pdb/parents-in forms %) k))]
    (is (= #{:seon.tx/meta :seon.merge/entry-fact :seon.db/note-fact :seon.config/config :seon.config/sources-fact :seon.script/fact}
           (into #{} (for [k (keys forms) :when (entity? k) :when (not (some #(seq (filter ids (own %))) (reach k)))] k))))))

;; docs/decisions.md, 'A merge replays the source's transactions on the branch that receives it': a branch merged up, then main
;; merged down: main's history since the base holds the branch's own transactions replayed; each comes back writing nothing, and
;; leaves no transaction on the branch (seon.merge/apply-groups!); main's own transaction is written
(deftest a-branchs-own-transactions-come-back-writing-nothing
  (branched
   (fn [conn bc br]
     (dotimes [i 5] (apply! bc :branch [{:task/name (keyword "gen" (str "own" i)) :task/plan "p" :task/status :held}]))
     (is (seq (:groups (replay-up! conn br))))
     (apply! conn :main [{:task/name :gen/mains :task/plan "main's" :task/status :held}])
     (let [main-b (:branch (:config @conn)) base (pdb/branch-base conn main-b br) head @bc
           p (pm/plan base head @conn)
           cost (volatile! nil)
           a (seon.profile/measure! #(pm/apply-groups! bc head (pdb/tx-meta br {:seon.tx/merged main-b}) (:groups p)) #(vreset! cost %))
           _ (dw/commit-pending! bc)
           txs (fn [db] (count (d/datoms db :aevt :db/txInstant)))]
       (spit "/tmp/mergeop/replay-back.edn" (pr-str {:groups (count (:groups p)) :ops (reduce + (map (comp count :ops) (:groups p)))
                                                      :applied (count (:applied a)) :ops-written (reduce + (map (comp count pdb/report->tx) (:reps a)))
                                                      :ticks (:ticks @cost) :bytes (:bytes @cost)}))
       (is (= 1 (count (:applied a))) "only main's own transaction is written")
       (is (= (inc (txs head)) (txs @bc)) "the branch's own, come back, leave no transaction")
       (is (some? (pdb/eid @bc :task/name :gen/mains)) "and main's change is on the branch")
       (is (every? #(pdb/eid @bc :task/name (keyword "gen" (str "own" %))) (range 5)) "and the branch keeps what it wrote")))))

;; docs/decisions.md, 'A merge replays the source's transactions on the branch that receives it': a fact two dbs hold under two
;; transactions (a merge's copy, written again under a new one) is no change of either: after a merge up, the branch changes one
;; of its own facts and main changes nothing of its own; merged either way, no conflict
(deftest a-fact-held-under-another-transaction-is-no-change
  (branched
   (fn [conn bc br]
     (apply! bc :branch [{:task/name :gen/own :task/plan "p" :task/status :held}])
     (is (seq (:groups (replay-up! conn br))))
     (apply! bc :branch [[:db/add (pdb/eid @bc :task/name :gen/own) :task/plan "changed after the merge"]])
     (let [base (pdb/branch-base conn (:branch (:config @conn)) br)]
       (is (= {} (:keys (pm/net-change base @conn))) "main changed nothing since the base: its copy of the branch's facts is none")
       (is (= {[:task/name :gen/own :task/plan] "changed after the merge"} (:keys (pm/net-change base @bc))) "the branch changed one key")
       (is (empty? (:conflicts (pm/plan base @bc @conn))) "merged down, no conflict")
       (is (empty? (:conflicts (pm/plan base @conn @bc))) "merged up, no conflict")))))

;; docs/decisions.md, 'A merge replays the source's transactions on the branch that receives it': a retraction the source's history
;; does not hold (an attribute that keeps no history) rides the last group that is replayed, never one left out: here main's last
;; transactions are the branch's own, come back through the merge up, and are no group of the merge down
(deftest a-retraction-with-no-history-rides-a-group-that-stays
  (branched
   (fn [conn bc br]
     (let [v (first (es @conn :var/form))]
       (d/transact conn [[:db/add v :seon.test/status :green]])
       (d/release bc) (d/delete-branch! conn br) (d/branch! conn (:branch (:config @conn)) br)
       (let [bc (pdb/connect (assoc (:config @conn) :branch br))]
         (try
           (apply! bc :branch [{:task/name :gen/own :task/plan "p" :task/status :held}])
           ;; main drops the status (no history keeps the retraction), then takes the branch's work
           (d/transact conn [[:db/retract v :seon.test/status :green]])
           (is (seq (:groups (replay-up! conn br))))
           (let [base (pdb/branch-base conn (:branch (:config @conn)) br)
                 p (pm/plan base @bc @conn)]
             (is (= [[:db/retract v :seon.test/status :green]] (vec (mapcat :ops (:groups p)))) (pr-str p)))
           (finally (d/release bc))))))))
