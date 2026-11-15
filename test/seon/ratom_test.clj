(ns seon.ratom-test
  "Reagent's ratom on the server (seon.ratom): reagent's own tests (test/reagenttest/testratom.cljs
  at fa4dc05), case by case, and the inferred cursor's. rv/atom is seon.ratom/atom, r/flush is
  flush!, js/Error is ex-info and catch :default is catch Throwable; an on-dispose fn takes its
  argument (JavaScript drops extra arguments, Clojure does not). Left out: test-rswap (reagent.core,
  not the ratom), the print tests, Track, Wrapper and the cursor (inferred here: inferred-paths).
  Run: bin/test seon.ratom-test"
  (:refer-clojure :exclude [run!])
  (:require [clojure.test :refer [deftest is testing]] [seon.ratom :as rv :refer [reaction run! dispose! flush! make-reaction running]]
            [seon.ctx :as ctx] [seon.db :as pdb] [seon.test-store :as ts] [datahike.api :as d]
            [seon.profile :as profile] [datahike.reads :as reads] [clojure.core.async :as a]))

(def testite 10)

;; each test is one owner of the reactions it makes (seon.ratom/*owner*), as a proc's step is: Reagent's process-wide queue and
;; running count are an owner's here (docs/flow.md#the-five-rules)
(clojure.test/use-fixtures :each (fn [t] (binding [rv/*owner* (rv/owner)] (t))))

;; Estimate: two owners, one atom, two reactions, three steps: ~100k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} a-reaction-runs-only-in-its-owners-step
  ;; docs/reactive.md#how-a-commit-reaches-its-readers-today: owner A's reaction dirtied in owner B's step is never run by B's flush!; it reaches A
  ;; as a message on A's port ({:seon.ratom/dirty r}) and runs in A's step. Red before: one process-wide queue, so B's flush! ran A's
  ;; reaction (and preempted fibers lost 267,087 of 400,000 enqueues to it: flow-lab e18). Deterministic, no load
  (let [port (clojure.core.async/chan 4)
        a (rv/owner port) b (rv/owner)
        x (rv/atom 0) runs (rv/atom 0)
        [r w] (binding [rv/*owner* a]
                (let [r (reaction (swap! runs inc) @x)]
                  [r (run! @r)]))]
    (is (= 1 @runs) "A's reaction ran once, made")
    (binding [rv/*owner* b] (reset! x 1) (flush!))
    (is (= 1 @runs) "B's step changed what A's reaction reads, and B's flush! ran none of A's")
    (let [m (clojure.core.async/poll! port)]
      (is (= r (:seon.ratom/dirty m)) "the dirty reached A as a message on its port")
      (binding [rv/*owner* a] (rv/take-dirty! m) (flush!)))
    (is (= 2 @runs) "and ran in A's own step")
    (binding [rv/*owner* a] (dispose! w))))

;; Estimate: two owners, one atom, one auto-run reaction, three steps: ~100k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} an-auto-run-reaction-runs-only-in-its-owners-step
  ;; docs/reactive.md#how-a-commit-reaches-its-readers-today (runtime-2's review of the owner queue): an auto-run reaction (run!) of owner A whose source changes in owner
  ;; B's step does not fire in B's step, under B's bindings; it reaches A as a message on A's port and runs in A's step. Red before:
  ;; it ran at once inside B's reset!
  (let [port (clojure.core.async/chan 4)
        a (rv/owner port) b (rv/owner)
        x (rv/atom 0) seen (rv/atom [])
        w (binding [rv/*owner* a] (run! (swap! seen conj [@x (some? rv/*owner*) (identical? a rv/*owner*)])))]
    (binding [rv/*owner* b] (reset! x 1) (flush!))
    (is (= [[0 true true]] @seen) "B's step changed what A's run! reads, and it did not fire in B's step")
    (let [m (clojure.core.async/poll! port)]
      (is (some? m) "it reached A as a message on its port")
      (binding [rv/*owner* a] (rv/take-dirty! m) (flush!)))
    (is (= [[0 true true] [1 true true]] @seen) "and fired in A's own step, under A")
    (binding [rv/*owner* a] (dispose! w))))

(deftest basic-ratom
  (let [runs (running) start (rv/atom 0) sv (reaction @start) comp (reaction @sv (+ 2 @sv)) c2 (reaction (inc @comp))
        count (rv/atom 0) out (rv/atom 0)
        res (reaction (swap! count inc) @sv @c2 @comp)
        const (run! (reset! out @res))]
    (is (= @count 1) "constrain ran")
    (is (= @out 2))
    (reset! start 1)
    (flush!)
    (is (= @out 3))
    (is (<= 2 @count 3))
    (dispose! const)
    (is (= (running) runs))))

(deftest double-dependency
  (let [runs (running) start (rv/atom 0) c3-count (rv/atom 0) c1 (reaction @start 1) c2 (reaction @start)
        c3 (make-reaction (fn [] (swap! c3-count inc) (+ @c1 @c2)) :auto-run true)]
    (flush!)
    (is (= @c3-count 0))
    (is (= @c3 1))
    (is (= @c3-count 1) "t1")
    (swap! start inc)
    (flush!)
    (is (= @c3-count 2) "t2")
    (is (= @c3 2))
    (is (= @c3-count 2) "t3")
    (dispose! c3)
    (is (= (running) runs))))

(deftest test-from-reflex
  (let [runs (running)]
    (let [!counter (rv/atom 0) !signal (rv/atom "All I do is change")
          co (run! @!signal (swap! !counter inc))]
      (is (= 1 @!counter) "Constraint run on init")
      (reset! !signal "foo")
      (flush!)
      (is (= 2 @!counter) "Counter auto updated")
      (dispose! co))
    (let [!x (rv/atom 0) !co (make-reaction #(inc @!x) :auto-run true)]
      (is (= 1 @!co) "CO has correct value on first deref")
      (swap! !x inc)
      (is (= 2 @!co) "CO auto-updates")
      (dispose! !co))
    (is (= runs (running)))))

(deftest test-unsubscribe
  (dotimes [_ testite]
    (let [runs (running) a (rv/atom 0) a1 (reaction (inc @a)) a2 (reaction @a) b-changed (rv/atom 0) c-changed (rv/atom 0)
          b (reaction (swap! b-changed inc) (inc @a1))
          c (reaction (swap! c-changed inc) (+ 10 @a2))
          res (run! (if (< @a2 1) @b @c))]
      (is (= @res (+ 2 @a)))
      (is (= @b-changed 1))
      (is (= @c-changed 0))
      (reset! a -1)
      (is (= @res (+ 2 @a)))
      (is (= @b-changed 2))
      (is (= @c-changed 0))
      (reset! a 2)
      (is (= @res (+ 10 @a)))
      (is (<= 2 @b-changed 3))
      (is (= @c-changed 1))
      (reset! a 3)
      (is (= @res (+ 10 @a)))
      (is (<= 2 @b-changed 3))
      (is (= @c-changed 2))
      (reset! a 3)
      (is (= @res (+ 10 @a)))
      (is (<= 2 @b-changed 3))
      (is (= @c-changed 2))
      (reset! a -1)
      (is (= @res (+ 2 @a)))
      (dispose! res)
      (is (= runs (running))))))

(deftest maybe-broken
  (let [runs (running)]
    (let [a (rv/atom 0) b (reaction (inc @a)) _c (reaction (dec @a)) d (reaction (str @b)) res (rv/atom 0)
          cs (run! (reset! res @d))]
      (is (= @res "1"))
      (dispose! cs))
    (let [a (rv/atom 0) b (reaction (inc @a)) c (reaction (dec @a)) d (run! [@b @c])]
      (is (= @d [1 -1]))
      (dispose! d))
    (let [a (rv/atom 0) b (reaction (inc @a)) c (reaction (dec @a)) d (run! [@b @c]) res (rv/atom 0)]
      (is (= @d [1 -1]))
      (let [e (run! (reset! res @d))]
        (is (= @res [1 -1]))
        (dispose! e))
      (dispose! d))
    (is (= runs (running)))))

(defn- disposing [label add?]
  (dotimes [_ testite]
    (let [runs (running) a (rv/atom 0) disposed (rv/atom nil) disposed-c (rv/atom nil) disposed-cns (rv/atom nil) count-b (rv/atom 0)
          b (if add? (make-reaction (fn [] (swap! count-b inc) (inc @a)))
                (make-reaction (fn [] (swap! count-b inc) (inc @a)) :on-dispose (fn [_] (reset! disposed true))))
          c (if add? (make-reaction #(if (< @a 1) (inc @b) (dec @a)))
                (make-reaction #(if (< @a 1) (inc @b) (dec @a)) :on-dispose (fn [_] (reset! disposed-c true))))
          res (rv/atom nil)
          cns (if add? (make-reaction #(reset! res @c) :auto-run true)
                  (make-reaction #(reset! res @c) :auto-run true :on-dispose (fn [_] (reset! disposed-cns true))))]
      (when add?
        (rv/add-on-dispose! b (fn [r] (is (identical? r b)) (reset! disposed true)))
        (rv/add-on-dispose! c (fn [_] (reset! disposed-c true)))
        (rv/add-on-dispose! cns (fn [_] (reset! disposed-cns true))))
      @cns
      (is (= @res 2) label)
      (is (= (+ 4 runs) (running)))
      (is (= @count-b 1))
      (reset! a -1)
      (flush!)
      (is (= @res 1))
      (is (= @disposed nil))
      (is (= @count-b 2))
      (is (= (+ 4 runs) (running)) "still running")
      (reset! a 2)
      (flush!)
      (is (= @res 1))
      (is (= @disposed true))
      (is (= (+ 2 runs) (running)) "less running count")
      (reset! disposed nil)
      (reset! a -1)
      (flush!)
      (is (= 1 @res) "should be one again")
      (is (= @disposed nil))
      (reset! a 2)
      (flush!)
      (is (= @res 1))
      (is (= @disposed true))
      (dispose! cns)
      (is (= @disposed-c true))
      (is (= @disposed-cns true))
      (is (= runs (running))))))

(deftest test-dispose (disposing "on-dispose" false))
(deftest test-add-dispose (disposing "add-on-dispose!" true))

(deftest test-on-set
  (let [runs (running) a (rv/atom 0)
        b (make-reaction #(+ 5 @a) :auto-run true :on-set (fn [_ newv] (reset! a (+ 10 newv))))]
    @b
    (is (= 5 @b))
    (reset! a 1)
    (is (= 6 @b))
    (reset! b 1)
    (is (= 11 @a))
    (is (= 16 @b))
    (dispose! b)
    (is (= runs (running)))))

(deftest non-reactive-deref
  (let [runs (running) a (rv/atom 0) b (make-reaction #(+ 5 @a))]
    (is (= @b 5))
    (is (= runs (running)))
    (reset! a 1)
    (is (= @b 6))
    (is (= runs (running)))))

(deftest catching
  (let [runs (running) a (rv/atom false) catch-count (rv/atom 0)
        b (reaction (when @a (throw (ex-info "fail" {}))))
        c (run! (try @b (catch Throwable _ (swap! catch-count inc))))]
    (is (= @catch-count 0))
    (reset! a false)
    (flush!)
    (is (= @catch-count 0))
    (reset! a true)
    (flush!)
    (is (= @catch-count 1))
    (reset! a false)
    (flush!)
    (is (= @catch-count 1))
    (dispose! c)
    (is (= runs (running)))))

(deftest reset-in-reaction
  (let [runs (running) state (rv/atom {})
        c1 (reaction (get-in @state [:data :a])) c2 (reaction (get-in @state [:data :b]))
        ;; JavaScript adds nil as 0 ((+ nil nil) is 0 there); Clojure throws, so nil is 0 here
        rxn (make-reaction #(let [cc1 @c1 cc2 @c2] (swap! state assoc :derived (+ (or cc1 0) (or cc2 0))) nil) :auto-run true)]
    @rxn
    (is (= (:derived @state) 0))
    (swap! state assoc :data {:a 1, :b 2})
    (flush!)
    (is (= (:derived @state) 3))
    (swap! state assoc :data {:a 11, :b 22})
    (flush!)
    (is (= (:derived @state) 33))
    (dispose! rxn)
    (is (= runs (running)))))

(deftest exception-recover
  ;; Seon's rule over Reagent's (web's review of Land A, 2026-11-15): a reader's throw is its own error, raised when it is read; the flush,
  ;; an owner's step, goes on to every other reader and never throws (task seon.ratom/a-commit-reaches-only-the-readers-of-what-it-moved)
  (let [runs (running) state (rv/atom 1) count (rv/atom 0)
        r (run! (swap! count inc) (when (> @state 1) (throw (ex-info "oops" {}))))]
    (is (= @count 1))
    (swap! state inc) (flush!)
    (is (thrown? Throwable @r) "the run's throw is its error, raised when it is read")
    (is (= @count 2))
    (swap! state dec)
    (flush!)
    (is (= @count 3))
    (dispose! r)
    (is (= runs (running)))))

(deftest exception-recover-indirect
  ;; Seon's rule over Reagent's (web's review of Land A, 2026-11-15): a reader's throw is its own error, raised when it is read; the flush,
  ;; an owner's step, goes on to every other reader and never throws (task seon.ratom/a-commit-reaches-only-the-readers-of-what-it-moved)
  (let [runs (running) state (rv/atom 1) count (rv/atom 0)
        ref (reaction (when (= @state 2) (throw (ex-info "err" {}))))
        r (run! (swap! count inc) @ref)]
    (is (= @count 1))
    (swap! state inc) (flush!)
    (is (thrown? Throwable @r) "the run's throw is its error, raised when it is read")
    (is (= @count 2))
    (is (thrown? Throwable @ref))
    (swap! state inc)
    (flush!)
    (is (= @count 3))
    (dispose! r)
    (is (= runs (running)))))

(deftest exception-side-effect
  (let [runs (running) state (rv/atom {:val 1}) rstate (reaction @state) spy (rv/atom nil)
        r1 (run! @rstate)
        r2 (let [val (reaction (:val @rstate))] (run! (reset! spy @val) (is (some? @val))))
        r3 (run! (when (:error? @rstate) (throw (ex-info "Error detected!" {}))))]
    (swap! state assoc :val 2)
    (flush!)
    (swap! state assoc :error? true)
    (flush!)
    (is (thrown? Throwable @r3) "the throwing reader's error, raised when it is read; the flush went on")
    (flush!)
    (flush!)
    (dispose! r1)
    (dispose! r2)
    (dispose! r3)
    (is (= runs (running)))))

(deftest exception-reporting
  (let [runs (running) state (rv/atom {:val 1}) rstate (reaction (:val @state))
        r1 (run! (when (= @rstate 13) (throw (ex-info "fail" {}))))]
    (swap! state assoc :val 13)
    (flush!)
    (is (thrown? Throwable @r1) "the throwing reader's error, raised when it is read; the flush went on")
    (swap! state assoc :val 2)
    (flush!)
    (dispose! r1)
    (is (= runs (running)))))

(deftest ratom-with-meta
  (let [value {:val 1} meta-value {:meta-val 1} state (with-meta (rv/atom value) meta-value)]
    (is (= (meta state) meta-value))
    (is (= @state value))))

(deftest running-disposed-reactions
  (let [ra (rv/atom 0) run-number (rv/atom 0) disposed? (rv/atom false)
        r1 (make-reaction (fn [] (swap! run-number inc) @ra) :on-dispose (fn [_] (reset! disposed? true)))]
    (is (= @run-number 0))
    @r1
    (is (= @run-number 1))
    (swap! ra inc)
    (is (= @run-number 1))
    @r1
    (is (= @run-number 2))
    (dispose! r1)
    (is (true? @disposed?))
    (is (= @run-number 2))))

(deftest inferred-paths
  ;; the inferred cursor over ctx's shape (an entity map, a task under it, a collection of evals by id, newest first) and ctx's one
  ;; diff (seon.ctx/changes): what a run read through a view (seon.ratom/reads-of), in the one path index (read-index), is reached
  ;; (reached) by a change to a path it read, and by no other: what a walk kept as a value runs again (docs/render.md#reactive-renders)
  (let [a (rv/atom {:db/id 1 :x 0 :task {:db/id 2 :plan "p" :n 1}
                    :evals (#'seon.ctx/members-map [[10 {:db/id 10 :v 1}] [11 {:db/id 11 :v 2}]])})
        v #(rv/ref-view a ctx/changes)
        runs {:plan #(:plan (:task (v))) :count #(count (:evals (v))) :e10 #(:v (get (:evals (v)) 10))
              ;; the entity's own key set alone (seon.render.ui/added-keys): which keys it holds, never their values
              :key-set #(rv/key-set (v))
              ;; a nested node's key set (runtime-2's review): reached by a key added under it, and by the node itself replaced
              :task-keys #(rv/key-set (:task (v)))
              ;; a count reads the key set too (web-2's review): a value replaced in the counted node reaches no count
              :task-count #(count (:task (v)))}
        ix (reduce-kv (fn [ix k f] (rv/read-index ix k (second (rv/reads-of a f)) conj)) nil runs)
        ;; the tree moved by f: the runs its diff reaches
        moved (fn [f] (let [t0 @a] (swap! a f) (rv/reached ix (ctx/changes t0 @a))))]
    (is (= #{} (moved #(assoc-in % [:task :n] 2))) "a sibling attribute's change reaches no read")
    (is (= #{} (moved #(assoc % :x 1))) "nor an unread key's, nor a key set's: a value replaced adds or removes no key")
    (is (= #{:key-set} (moved #(assoc % :y 1))) "a key added reaches the key set")
    (is (= #{:key-set} (moved #(dissoc % :y))) "and a key gone")
    (is (= #{:task-keys :task-count} (moved #(assoc-in % [:task :z] 1))) "a key added under a nested node reaches that node's key set and count alone")
    (is (= #{:plan :task-keys :task-count} (moved #(assoc % :task {:db/id 3 :plan "r" :n 1 :z 1})))
        "another entity in the node's place reaches its key set too (the whole node replaced, through :pre), never under-invalidated")
    (is (= #{:plan} (moved #(assoc-in % [:task :plan] "q"))) "a read path's change reaches its reader")
    (is (= #{} (moved #(assoc-in % [:evals 11 :v] 7))) "iterating reads the key set: a member's change reaches no count, nor a read of another member")
    (is (= #{:count} (moved #(assoc-in % [:evals 12] {:db/id 12 :v 3}))) "a member added reaches the iteration, not a read of another member")
    (is (= #{:e10} (moved #(assoc-in % [:evals 10 :v] 8))) "its own member's change reaches it")
    (is (= #{:e10 :count} (moved #(update % :evals dissoc 10))) "its member gone reaches it, and the iteration")))

(deftest a-rerun-indexes-only-the-reads-it-gained-or-lost
  ;; docs/render.md#reactive-renders: a walk moves a component's reads in the path index by what its rerun gained or lost (seon.render's
  ;; render!: a layout over every row, rerun by one change, re-indexed each path with each prefix: one eval added at 100 evals was
  ;; 37.7 MB, 13.0 MB after); the index so moved is the one built from the rerun's reads whole, and a read gained or lost decides what
  ;; reaches it
  (let [a (rv/atom {:db/id 1 :flag 0 :evals (#'seon.ctx/members-map (for [i (range 20)] [i {:db/id i :v i}]))})
        v #(rv/ref-view a ctx/changes)
        reads #(second (rv/reads-of a (fn [] [(:flag (v)) (reduce + (map (fn [[_ e]] (:v e)) (:evals (v))))])))
        ;; as render! moves them: what it no longer reads out, what it newly reads in
        move (fn [ix was now] (rv/move-reads ix :r was now))
        whole #(rv/read-index nil :r % conj)
        reached (fn [ix f] (let [t0 @a] (swap! a f) (rv/reached ix (ctx/changes t0 @a))))
        r0 (reads)
        ix0 (whole r0)]
    (is (= #{:r} (reached ix0 #(assoc % :flag 1))))
    (is (= r0 (reads)) "the same reads, rerun: nothing to move")
    (is (= #{:r} (reached ix0 #(assoc-in % [:evals 20] {:db/id 20 :v 20}))) "a member added reaches the iteration")
    (let [r1 (reads) ix1 (move ix0 r0 r1)]
      (is (= #{[[:evals 20 :v] :deep]} (set (remove r0 r1))) "its rerun gained the new member's read alone")
      (is (= (whole r1) ix1) "moved by it: the index built whole")
      (is (= #{:r} (reached ix1 #(assoc-in % [:evals 20 :v] 5))) "the read it gained reaches it")
      (is (= #{:r} (reached ix1 #(update % :evals dissoc 3))))
      (let [r2 (reads) ix2 (move ix1 r1 r2)]
        (is (= #{[[:evals 3 :v] :deep]} (set (remove r2 r1))) "a member gone: its read lost")
        (is (= (whole r2) ix2) "moved by it: the index built whole")
        (is (= #{:r} (reached ix2 #(assoc-in % [:evals 3] {:db/id 3 :v 1}))) "and back: the iteration")
        (is (= {:pre {} :deep {} :keys {} :key-set {}} (move ix2 r2 #{})) "a reader that reads nothing is gone from it")))))

(deftest a-view-is-a-map
  ;; the view passes as the value it views wherever a map is read, and the printer takes the real one
  (let [a (rv/atom {:db/id 1 :task {:db/id 2 :plan "p"} :evals (#'seon.ctx/members-map [[10 {:db/id 10 :v 1}]])})
        v (rv/ref-view a ctx/changes)]
    (is (map? v))
    (is (= @a v) "= from the map's side")
    (is (= v @a) "and from the view's")
    (is (= "p" (get-in v [:task :plan])))
    (is (= "p" (let [{{:keys [plan]} :task} v] plan)) "destructured")
    (is (= (:task @a) (into {} (:task v))))
    (is (sorted? (:evals v)) "a sorted collection stays sorted")
    (is (= 10 (key (first (rseq (:evals v))))))
    (is (= [1] (map (comp :v val) (subseq (:evals v) <= 10))))
    (is (nil? (get v :seon.render/hiccup)) "an absent key answers nil")
    (is (identical? (:task @a) (rv/unwrap (:task v))) "unwrap: the real value")
    (is (= {:t (:task @a)} (rv/realize {:t (:task v)})) "realize: every view in it")))

;; docs/plan.md#the-root-concept-one-reaction-over-the-store: the store is a source a reaction derefs; what the run read of it is
;; recorded by Datahike's recorder as datom keys, and a commit re-runs the reaction only when its datoms touch one of them
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-reaction-reruns-on-a-commit-touching-a-key-it-read
  (let [db0 (pdb/db-with (ts/test-db) [{:note/text "a" :note/agent :x} {:note/text "b" :note/agent :y}])
        [ea eb] (sort (map :e (d/datoms db0 :aevt :note/text)))
        s (rv/store db0)
        n (volatile! 0)
        ;; the slice read as a seek of [e a]: a pull records [:e e] today (datahike.pull-api/resolve-pull-eid's existence read,
        ;; filed: datahike.pull-api/a-pull-of-an-attribute-records-its-slice)
        r (run! (vswap! n inc) (:v (first (d/datoms @s :eavt ea :note/text))))
        commit! (fn [tx] (let [rep (pdb/with @s tx)] (rv/commit! s (:db-after rep) (:tx-data rep))))]
    (is (= 1 @n))
    (is (= "a" @r))
    (commit! [[:db/add eb :note/text "b2"]]) (flush!)
    (is (= 1 @n) "a sibling slice of the same attribute moved: not rerun")
    (commit! [[:db/add ea :note/agent :z]]) (flush!)
    (is (= 1 @n) "another attribute of the entity it read: not rerun")
    (commit! [[:db/add ea :note/text "a2"]]) (flush!)
    (is (= 2 @n) "the slice it read moved: rerun once")
    (is (= "a2" @r))
    (dispose! r)))

;; reagent.ratom/track (fa4dc05 ratom.cljs 182-240): (track f & args) is = by f and args, and inside a reaction its value is one
;; cached reaction shared by every deref of an equal track, run again only when what it read moved
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-track-answers-its-cached-value-while-nothing-it-read-moved
  (let [db0 (pdb/db-with (ts/test-db) [{:note/text "a" :note/agent :x} {:note/text "b" :note/agent :y}])
        [ea eb] (sort (map :e (d/datoms db0 :aevt :note/text)))
        s (rv/store db0)
        calls (volatile! 0)
        text (fn [e] (vswap! calls inc) (:v (first (d/datoms @s :eavt e :note/text))))
        tracks (volatile! {})
        r (binding [rv/*tracks* tracks] (run! [@(rv/track text ea) @(rv/track text ea)]))]
    (is (= (rv/track text ea) (rv/track text ea)) "= by f and args, as Reagent's")
    (is (not= (rv/track text ea) (rv/track text eb)))
    (is (= ["a" "a"] @r))
    (is (= 1 @calls) "two derefs of equal tracks in one reaction: one run")
    (binding [rv/*tracks* tracks]
      (let [rep (pdb/with @s [[:db/add eb :note/text "b2"]])] (rv/commit! s (:db-after rep) (:tx-data rep))) (flush!))
    (is (= 1 @calls) "nothing it read moved: its cached value")
    (binding [rv/*tracks* tracks]
      (let [rep (pdb/with @s [[:db/add ea :note/text "a2"]])] (rv/commit! s (:db-after rep) (:tx-data rep))) (flush!))
    (is (= 2 @calls) "what it read moved: run once again")
    (is (= ["a2" "a2"] @r))
    (is (= "a2" (binding [rv/*tracks* tracks] @(rv/track text ea))) "outside a reaction while its reaction lives: the cached value")
    (is (= 2 @calls))
    (dispose! r)
    (is (empty? @tracks) "disposed: its owner's cache lets it go")
    (is (= "a2" @(rv/track text ea)) "and once nothing watches it, Reagent's: f called")
    (is (= 3 @calls))))


;; ---- the read index: a commit reaches only the readers of what it moved (task seon.ratom/a-commit-reaches-only-the-readers-of-what-it-moved) ----

(defn- notes
  "A test db with n notes, and their entities in order."
  [n]
  (let [db (pdb/db-with (ts/test-db) (vec (for [i (range n)] {:note/text (str "n" i) :note/agent :x})))]
    [db (vec (sort (map :e (d/datoms db :aevt :note/text))))]))

(defn- commit-ticks
  "The least ticks of three commits to s of tx (each from s's db at the time), with readers made by mk over es: what a commit
  costs them, the reruns of any reader it touched included."
  [db es mk tx]
  (let [s (rv/store db) rs (mapv #(mk s %) es)
        t (reduce min (for [_ (range 3)]
                        (let [rep (pdb/with @s (tx @s))] (:ticks (profile/measure! #(rv/commit! s (:db-after rep) (:tx-data rep)))))))]
    (doseq [r rs] (dispose! r))
    t))

(defn- text-reader
  "A reader of entity e's text on store s, run now and on each commit that touches it."
  [s e] (run! (:v (first (d/datoms @s :eavt e :note/text)))))

;; Estimate: three stores of 1,010 notes (~3M each) and 1,110 readers' first runs (~20k each, ~22M) and 9 commits; ~40M ticks
(deftest ^{:seon.test/estimate {:ticks 40000000}} a-commit-costs-its-datoms-not-the-readers
  ;; docs/reactive.md#what-it-does-not-do-yet: a commit of d datoms costs d x 5 index lookups, whatever the readers; today each reader
  ;; checks every commit itself ([M root result/bcecbc16]: 53.6k, 538k, 4.41M ticks at 10, 100, 1,000 readers)
  (let [[db es] (notes 1010) readers (subvec es 0 1000) moved (subvec es 1000)
        tx (fn [_] (vec (for [e moved] [:db/add e :note/text (str "moved" (rand-int 1000000))])))
        [t10 t100 t1000] (for [n [10 100 1000]] (commit-ticks db (subvec readers 0 n) text-reader tx))]
    (is (<= t1000 (* 2 t10)) (str "a commit no reader read costs the same at 10 and 1,000 readers: " [t10 t100 t1000]))))

;; Estimate: two stores (110 and 1,100 notes, ~4M), 200 readers' first runs (~4M), 6 commits; ~10M ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-commit-costs-its-datoms-not-the-store
  ;; the same commit on a store 10x larger costs the same: nothing a commit does reads the store whole
  (let [cost (fn [n] (let [[db es] (notes n)]
                       (commit-ticks db (subvec es 0 100) text-reader
                                     (fn [_] (vec (for [e (subvec es 100 110)] [:db/add e :note/text (str "moved" (rand-int 1000000))]))))))
        small (cost 110) large (cost 1100)]
    (is (<= large (* 2 small)) (str "10x the store, the same commit: " [small large]))))

;; Estimate: one store of 110 notes, 100 readers of an attribute read whole (each a count over 110 datoms, ~50k a run), two commits
;; and 100 reruns (~5M); ~10M ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-hot-attribute-reruns-each-of-its-readers-once
  ;; a reader of an attribute read whole ([:a a]) reruns on any write of it: the fan-out is its readers, once each, and a write of
  ;; another attribute reruns none of them
  (let [[db es] (notes 110) s (rv/store db) runs (volatile! 0)
        rs (vec (for [_ (range 100)] (run! (vswap! runs inc) (count (d/datoms @s :aevt :note/agent)))))
        commit (fn [tx] (let [rep (pdb/with @s tx)] (rv/commit! s (:db-after rep) (:tx-data rep)) (flush!)))]
    (is (= 100 @runs))
    (commit [[:db/add (first es) :note/text "other attribute"]])
    (is (= 100 @runs) "a write of another attribute reruns none")
    (commit [[:db/add (first es) :note/agent :y]])
    (is (= 200 @runs) "a write of the attribute reruns each of its 100 readers once")
    (doseq [r rs] (dispose! r))))

;; Estimate: 50 rounds over a 40-note store: 40 readers' runs (~20k each) and one commit a round; ~50M ticks
(deftest ^{:seon.test/estimate {:ticks 50000000}} a-commit-reruns-exactly-the-readers-its-datoms-touch
  ;; the index answers what each reader's own check over its key set answered (reads/reads-touched?, the oracle), on random readers and
  ;; commits seeded with what the keys compare by value: card-one upserts, re-assertions, a value read as [:av], a schema datom
  (let [[db es] (notes 40)
        rng (java.util.Random. 42)
        pick #(nth es (.nextInt rng (count es)))
        read-kinds [(fn [s e] (:v (first (d/datoms @s :eavt e :note/text))))      ; [:ea e a]
                    (fn [s e] (count (d/datoms @s :eavt e)))                         ; [:e e]
                    (fn [s _] (count (d/datoms @s :avet :note/agent :y)))            ; [:av a v] through the value index
                    (fn [s _] (count (d/datoms @s :aevt :note/agent)))]              ; [:a a]
        s (rv/store db)
        runs (atom {})
        rs (vec (for [i (range 40) :let [e (pick) f (nth read-kinds (.nextInt rng (count read-kinds)))]]
                  (make-reaction (fn [] (swap! runs update i (fnil inc 0)) (f s e)) :auto-run true)))
        _ (doseq [r rs] @r)]
    (dotimes [_ 50]
      (let [e (pick)
            tx (case (.nextInt rng 4)
                 0 [[:db/add e :note/text (str "t" (.nextInt rng 5))]]          ; a card-one upsert (or a re-assertion of the same text)
                 1 [[:db/add e :note/agent (if (.nextBoolean rng) :x :y)]]      ; a value the [:av] readers read, or not
                 2 [[:db/add e :note/text (:v (first (d/datoms @s :eavt e :note/text)))]] ; a re-assertion: no datom moves
                 3 [{:note/text (str "new" (.nextInt rng 1000)) :note/agent :y}]) ; a new entity
            rep (pdb/with @s tx)
            oracle (into #{} (keep-indexed (fn [i r] (when (reads/reads-touched? (rv/-get r :key-set) (:tx-data rep)) i))) rs)
            before @runs]
        (rv/commit! s (:db-after rep) (:tx-data rep))
        (flush!)
        (is (= oracle (into #{} (keep (fn [[i n]] (when (not= n (before i)) i))) @runs))
            (str "the readers rerun are the ones whose keys the commit touched: " (pr-str tx)))))
    (doseq [r rs] (dispose! r))))

;; Estimate: one store, two owners, one reaction, one message; ~1M ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-reader-of-another-owners-store-registers-by-message
  ;; docs/flow.md#the-five-rules: a store is its owner's state; a reaction owned by another proc that reads it never writes the
  ;; store's index or watches from its own thread: its keys reach the store's owner as a message on its port, as a dirtied reaction does
  (let [[db es] (notes 2)
        port-a (a/chan 4)
        owner-a (rv/owner port-a)
        s (binding [rv/*owner* owner-a] (rv/store db))
        r (binding [rv/*owner* (rv/owner (a/chan 4))] (run! (:v (first (d/datoms @s :eavt (first es) :note/text)))))
        m (a/poll! port-a)]
    (is (= :seon.ratom/keys (some #{:seon.ratom/keys} (keys m))) (str "the keys reach the store's owner as a message: " (pr-str m)))
    (is (empty? (.getWatches s)) "and nothing was written to the store from the other owner's run")
    (dispose! r)))

;; Estimate: a 3-note store, two readers, one commit; ~1M ticks
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-reader-that-throws-is-its-own-error-and-the-commit-reaches-the-rest
  ;; task seon.ratom/a-commit-reaches-only-the-readers-of-what-it-moved (web's BUG 2, result/dc412954): a reader whose run throws (a
  ;; malformed stored error, read by health's row in the render proc's step) is that reader's error, never a throw out of commit!:
  ;; every other reader of what the commit moved is told, and the thrower answers its error, never its old value
  (let [[db es] (notes 3)
        e (first es)
        s (rv/store db)
        told (atom #{})
        text #(:v (first (d/datoms @s :eavt e :note/text)))
        bad (make-reaction (fn [] (swap! told conj :bad) (let [t (text)] (when (= t "boom") (throw (ex-info "boom" {}))) t)) :auto-run true)
        good (make-reaction (fn [] (swap! told conj :good) (text)) :auto-run true)
        _ (do @bad @good (reset! told #{}))
        rep (pdb/with @s [[:db/add e :note/text "boom"]])
        thrown (try (rv/commit! s (:db-after rep) (:tx-data rep)) (flush!) nil (catch Throwable t (ex-message t)))]
    (is (nil? thrown) (str "the commit did not throw: " thrown))
    (is (= #{:bad :good} @told) "both readers of the moved datom were told")
    (is (= "boom" @good) "the other reader has the new value")
    (is (= "boom" (try @bad :no-throw (catch Throwable t (ex-message t)))) "the thrower answers its error, never its old value")
    (dispose! bad) (dispose! good)))

;;; --- task seon.ratom/the-core-is-correct-before-a-reader-moves: root's review (notes reactive-core-review-2026-11-15-1725.md),
;;; flow's shapes and review; each red on the core as Land A left it

;; Estimate: one atom, one reaction, two bounded flushes: ~50k ticks
(deftest ^{:seon.test/estimate {:ticks 50000}} a-queue-reaction-tracks-from-its-first-deref
  ;; red [M result/bd1b9b1d]: a :queue reaction's first deref outside a run tracked nothing (0 watches), so no swap! ever dirtied
  ;; it; Land A ran each one once by hand. Now its first capture is queued, and the owner's first bounded flush tracks it
  (let [a (rv/atom 0)
        r (make-reaction #(+ 10 @a) :auto-run :queue)]
    (flush! 10)
    (swap! a inc)
    (flush! 10)
    (is (= 11 (rv/-get r :state)) "a swap! of what it read reruns it at the owner's next flush")
    (dispose! r)))

;; Estimate: three reactions, one watch that throws, one flush: ~100k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} a-throw-mid-flush-leaves-the-rest-queued
  ;; flow's ruling (1): flush! took its whole queue before running it, so any exit from one run lost the rest. A watch callback that
  ;; throws is that reaction's own error; the reactions queued after it still run in the same flush
  (let [a (rv/atom 0)
        rs (vec (for [i (range 3)] (make-reaction #(+ i @a) :auto-run :queue)))
        _ (flush! 10)
        _ (add-watch (rs 0) :boom (fn [_ _ _ _] (throw (ex-info "a watch that throws" {}))))
        _ (swap! a inc)
        thrown (try (flush! 10) nil (catch Throwable t (ex-message t)))]
    (is (nil? thrown) (str "the flush did not throw: " thrown))
    (is (= [2 3] (map #(rv/-get % :state) (subvec rs 1))) "the reactions queued after the thrower ran")
    (is (some? (rv/-get (rs 0) :caught)) "the throw is the thrower's own error")
    (doseq [r rs] (dispose! r))))

;; Estimate: two owners, one atom, two reactions, one cross-owner swap!: ~100k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} a-cross-owner-dirty-reaches-a-portless-owner-or-is-refused
  ;; flow's ruling (1): a reaction dirtied in another owner's step reached its owner only through the owner's port, and an owner
  ;; with no port dropped it silently, stale until someone happened to deref it. With a port it is a message; with none, the
  ;; reaction's own typed error :seon.ratom/no-owner {:why :no-port}, never a silent drop
  (let [a (rv/atom 0)
        port (a/chan 10)
        with-port (rv/owner port)
        portless (rv/owner)
        mk (fn [o] (binding [rv/*owner* o] (let [r (make-reaction #(deref a) :auto-run :queue)] (flush! 10) r)))
        r1 (mk with-port)
        r2 (mk portless)]
    (swap! a inc)
    (is (= r1 (:seon.ratom/dirty (a/poll! port))) "the owner with a port is sent its dirtied reaction")
    (is (= :seon.ratom/no-owner (:seon.error/type (ex-data (rv/-get r2 :caught)))) "the portless owner's reaction is its typed error")
    (is (= :no-port (:why (ex-data (rv/-get r2 :caught)))) "naming why")
    (dispose! r1) (dispose! r2)))

;; Estimate: eleven reactions, one swap!, one deref: ~100k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} a-deref-runs-only-its-own-chain
  ;; flow's ruling (3): a non-reactive deref called (flush!), so every plain read ran the owner's whole queue, unbounded. It reruns
  ;; only its own dirty chain
  (let [a (rv/atom 0)
        runs (atom {})
        mk (fn [k] (make-reaction #(do (swap! runs update k (fnil inc 0)) @a) :auto-run :queue))
        others (mapv mk (range 10))
        r (mk :mine)]
    (flush! 20)
    (reset! runs {})
    (swap! a inc)
    (is (= 1 @r) "the deref answers the new value")
    (is (= {:mine 1} @runs) (str "and ran only itself: " @runs))
    (doseq [x (conj others r)] (dispose! x))))

;; Estimate: one reaction that dirties itself, one bounded flush: ~100k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} a-cycle-is-a-typed-error-not-a-livelock
  ;; flow's ruling (3): a reaction whose run dirties itself reran until the flush's bound, every step (a livelock under health-more).
  ;; A second run in one generation is its typed error :seon.ratom/cycle, and it is queued no more
  (let [a (rv/atom 0)
        runs (atom 0)
        ;; its first capture tracks a, so the self-dirtying starts with the next change from outside
        r (make-reaction #(do (swap! runs inc) (let [v @a] (when (< 0 v 1000) (swap! a inc)) v)) :auto-run :queue)]
    (flush! 100)
    (reset! runs 0)
    (swap! a inc)
    (flush! 100)
    (is (<= @runs 1) (str "it ran once for the change, its own re-dirtying stopped: " @runs))
    (is (= :seon.ratom/cycle (:seon.error/type (ex-data (rv/-get r :caught))))
        (str "its state is the typed cycle: " (pr-str {:caught (rv/-get r :caught) :ran-gen (rv/-get r :ran-gen) :dirty (rv/-get r :dirty?) :a @a :runs @runs})))
    (is (zero? (flush! 100)) "and nothing is left queued")
    (dispose! r)))

;; Estimate: four reactions in a diamond, one swap!, one flush: ~100k ticks
(deftest ^{:seon.test/estimate {:ticks 100000}} a-diamonds-top-runs-once-a-commit
  ;; the review's (2): reactions ran in queue order, so the top of a diamond could run between its two sides and see half an update
  ;; (a glitch), then run again. Run by height, the top runs once, on both sides' new values
  (let [a (rv/atom 1)
        seen (atom [])
        b (make-reaction #(+ @a 1) :auto-run :queue)
        c (make-reaction #(* @a 2) :auto-run :queue)
        d (make-reaction #(let [v (+ @b @c)] (swap! seen conj v) v) :auto-run :queue)]
    (flush! 10)
    (reset! seen [])
    (swap! a inc)
    (flush! 10)
    (is (= [7] @seen) (str "the top ran once, on both sides' new values (3 + 4): " @seen))
    (doseq [x [d b c]] (dispose! x))))

;; Estimate: three reactions, one that spins past a 2M-tick budget: ~2M ticks
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-flush-ended-by-its-budget-leaves-the-reaction-dirty-and-the-rest-queued
  ;; flow's review of e23cb9abc4: a step's tick budget (or an eval's interrupt) ends a flush mid-reaction; it is no error of that
  ;; reaction, so it reaches the step, and the reaction it cut stays dirty and queued with the rest, for the owner's next step
  (let [a (rv/atom 0)
        spin (make-reaction #(when (pos? @a) (loop [i 0] (recur (inc i)))) :auto-run :queue)
        rest (vec (for [i (range 2)] (make-reaction #(+ i @a) :auto-run :queue)))
        _ (flush! 10)
        _ (swap! a inc)
        ended (try (jolt.host/run-interruptible (jolt.host/make-interrupt) #(flush! 10) 2000000 nil) nil
                   (catch Throwable t (ex-data t)))]
    (is (:jolt/interrupted ended) (str "the budget's end reached the step: " (pr-str ended)))
    (is (nil? (rv/-get spin :caught)) "it is no error of the reaction it cut")
    (is (rv/-get spin :dirty?) "which stays dirty")
    (is (= 3 (rv/queued)) "and queued, with the rest")
    (dispose! spin) (doseq [r rest] (dispose! r))))
