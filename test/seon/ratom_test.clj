(ns seon.ratom-test
  "Reagent's ratom on the server (seon.ratom): reagent's own tests (test/reagenttest/testratom.cljs
  at fa4dc05), case by case, and the inferred cursor's. rv/atom is seon.ratom/atom, r/flush is
  flush!, js/Error is ex-info and catch :default is catch Throwable; an on-dispose fn takes its
  argument (JavaScript drops extra arguments, Clojure does not). Left out: test-rswap (reagent.core,
  not the ratom), the print tests, Track, Wrapper and the cursor (inferred here: inferred-paths).
  Run: bin/test seon.ratom-test"
  (:refer-clojure :exclude [run!])
  (:require [clojure.test :refer [deftest is testing]] [seon.ratom :as rv :refer [reaction run! dispose! flush! make-reaction running]]
            [seon.ctx :as ctx] [seon.db :as pdb] [seon.test-store :as ts] [datahike.api :as d]))

(def testite 10)

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
  (let [runs (running) state (rv/atom 1) count (rv/atom 0)
        r (run! (swap! count inc) (when (> @state 1) (throw (ex-info "oops" {}))))]
    (is (= @count 1))
    (is (thrown? Throwable (do (swap! state inc) (flush!))))
    (is (= @count 2))
    (swap! state dec)
    (flush!)
    (is (= @count 3))
    (dispose! r)
    (is (= runs (running)))))

(deftest exception-recover-indirect
  (let [runs (running) state (rv/atom 1) count (rv/atom 0)
        ref (reaction (when (= @state 2) (throw (ex-info "err" {}))))
        r (run! (swap! count inc) @ref)]
    (is (= @count 1))
    (is (thrown? Throwable (do (swap! state inc) (flush!))))
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
    (is (thrown? Throwable (flush!)))
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
    (is (thrown? Throwable (flush!)))
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
