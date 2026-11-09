(ns seon.render-cost-test
  "The render calls' cost proof: a page whose parts nest other values' renders by reference (a
  hiccup vector headed by a function, Reagent's call), deeply, with the budget split down, is
  serialized in one pass, linear in its nodes (owner, 2026-10-23: renders as functions and results;
  root: a cost proof for the recursion). Run: bin/test seon.render-cost-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost] [seon.web.html] [seon.db]
            [seon.test-store :as ts] [datahike.api :as d] [seon.render.proc] [seon.render] [seon.render.ui] [seon.config] [seon.task] [seon.repl] [seon.doc] [seon.ctx] [seon.ctx] [seon.ratom]))

(def ^:private fan "Children per node: a tree as deep as log3 n (10,000 nodes: 9 levels)." 3)

(defn node
  "Render node k of a tree of n: its element, holding its budget's share, and a call of each
  child with that share split between them (the budget passed down, as a parent's render does)."
  {:malli/schema [:=> [:cat nat-int? [:map [:n pos-int?] [:budget pos-int?]]] :any]}
  [k {:keys [n budget]}]
  (let [share (max 1 (quot budget fan))]
    [:div {:class "n"} (str budget)
     (for [c (range (inc (* fan k)) (min n (+ (* fan k) fan 1)))] [node c {:n n :budget share}])]))

(defn tree
  "The input of size n: the root call of an n-node tree of render calls, under a 60,000-token budget."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any]]}
  [n] [[node 0 {:n n :budget 60000}]])

(def ^:private per-node
  "A node's unit operations (the proof's :work), by the nearest rows of seon.cost/units."
  {:fn/armed-call 11 :vec/conj 4 :map/literal 2 :map/assoc 2 :str/append 30 :seq/reduce 4})

(defn- bytes-of
  "What unit operations ops allocate, by the unit table."
  {:malli/schema [:=> [:cat [:map-of :keyword number?]] number?]}
  [ops] (reduce-kv (fn [b op k] (+ b (* k (:bytes (cost/units op))))) 0.0 ops))

(deftest render-calls-are-one-pass-over-their-nodes
  ;; armed, as root runs it: calls exact, bytes priced by the unit table; wall time is the armed
  ;; call's, whose contracts check maps (~5x the table's one-int contract, as the printer's
  ;; armed proof found), so it is not judged here
  (cost/proof
    {:of 'seon.web.html/html :input 'seon.render-cost-test/tree
     :n "render calls in a tree three wide, each node's budget a third of its parent's"
     :work (str "one pass: per node, one call of its render (node), which builds its element (a vector, a "
                "one-key attribute map, its budget as text) and a lazy seq of its children's calls with the "
                "budget split; the serializer writes it once: the call applied, the element's tag, its one "
                "attribute (sorted, escaped) and its text, about 30 characters (<div class=\"n\">20</div>). "
                "No node is visited twice and no subtree is copied. Armed, as at boot: eleven contracted calls "
                "a node (its render; the serializer's write! of the call, the element, its text and its children's "
                "seq; callback, write-attrs!, attr-name twice, escape for the attribute and the text)")
     :growth :linear
     :per-n {:calls 11 :bytes (Math/round (bytes-of per-node))}
     ;; html itself, the root's write! and its StringBuilder
     :fixed {:calls 3}
     :sizes [100 300 1000 10000]}))

(defn messages
  "The input of size n: a vector of n messages (each a value whose schema declares a render),
  printed by the default render under a budget that shows them all."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n] [{:budget 200000}
       (vec (for [i (range n)] {:seon.message/id (str "m" i) :seon.message/from "user" :seon.message/body "hello"}))])

(deftest the-printer-renders-nested-values-in-one-pass
  ;; the printer's auto-render: each nested value with a render is named in one lookup per key
  ;; (seon.render/schema-of, no validation) and rendered once, in the printer's one pass
  (cost/proof
    {:of 'seon.render/hiccup :input 'seon.render-cost-test/messages
     :n "messages in a vector, each shown by its own render in the printer's hiccup"
     :work (str "one pass: per message, the printer classifies it once and asks its render once: one "
                "identity lookup over its keys, a call of its render (seon.render.ui/message) inline, "
                "and its one line by seon.print/line, then the printer lays it out as a leaf. Nothing per "
                "message is done twice and no message is printed as data")
     :growth :linear
     ;; contracted calls a message, along the path above: seon.render's nested, render-of with
     ;; own-key, schema-of and declared (its parents) each, print/line and its message. The input was runs (a table row with no
     ;; text: 143 a run and 209 once, 2026-11-03) until they went; messages are the entity whose render is as light that is left,
     ;; and its inline line holds its body as text. Measured 2026-11-04 on sizes 20 to 180: 3,615 calls at 20, 10,375 at 60,
     ;; 30,495 at 180 (168 a message and 255 once)
     :per-n {:calls 170}
     :fixed {:calls 260}
     :sizes [20 60 180]}))

(defn main-of
  "The input of size n: an empty render state and the test store's seed with n units of 20 more
  evaluation records on main (three datoms each: data, not program; 60 datoms a unit, so at
  n = 1000 the records' 60k datoms dwarf the seed's ~5k), as the render proc reads main when
  its t moves."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n]
  [{} (d/db-with (ts/test-db) (into [{:db/id "a"}]
                                    (for [i (range (* 20 n))] {:seon.eval/id (str "a1-1-1/" i) :seon.eval/agent "a"
                                                        :seon.eval/code "(+ 1 2)" :seon.eval/response {:value 3}})))])

(deftest main-info-reads-what-the-page-shows
  ;; global-ids' profile (2026-10-23): main-info took 174 ms a call on the owner's main, 695 ms
  ;; of one loop turn; its count of every datom grew with main (15 ms at 47k datoms). What the
  ;; render proc reads of main is its t and the reconciler's index (its tasks and agents):
  ;; none grows with main's other data
  (cost/proof
    {:of 'seon.render.proc/main-info :input 'seon.render-cost-test/main-of
     :n "units of 20 evaluation records on main (data that is neither program, task nor problem)"
     :work (str "none per record: the tasks and agents are the reconciler's index over their own attributes; a record "
                "is never walked")
     :growth :constant
     ;; the contracted calls a first read of main makes, whatever main holds, never one per record;
     ;; and its bytes, which a walk over every datom would grow with the records
     :per-n {:calls 33 :bytes 4000000}
     :sizes [10 40 100]}))

(defn boots-of
  "The input of size n: the agents list's opts over a db holding n sessions of another boot and one of boot \"b\", and that boot."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :string]]}
  [n]
  [{:seon.db/db (d/db-with (ts/test-db) (into [{:seon.agent/key "b/a1"}]
                                              (for [i (range n)] {:seon.agent/key (str "other/a" i)})))}
   "b"])

(deftest a-boots-sessions-cost-no-other-boots
  ;; docs/render.md#a-sessions-address-and-the-agents-list: every session on main is listed, and a store's sessions only grow
  (cost/proof
    {:of 'seon.render.ui/boot-side :input 'seon.render-cost-test/boots-of
     :n "sessions of another boot on main"
     :work "one index range over this boot's keys, never a step per session of another"
     :growth :constant
     :per-n {:calls 3 :bytes 16000}
     :sizes [10 40 160]}))

(defn moved-of
  "The input of size n: the render state holding main with n tasks (main-info's first read), and
  main one agent status later (one of the four status and running commits an eval makes), and the
  datoms that differ between them, which advance-main reads once and main-info takes."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any :any]]}
  [n]
  (let [db0 (d/db-with (ts/test-db) (seon.task/create-tx (ts/test-db) (vec (for [i (range n)] {:seon.task/ns 't :seon.task/name (str "k" i) :seon.task/plan (str "Make " i ".") :seon.task/proofs #{}}))))
        db0 (-> db0 (d/db-with [{:seon.root/id :root :seon.root/boot "b1" :seon.config.agent/max-running 4}]) (d/db-with (seon.task/agent-tx :a1 {:status :idle})))
        st (#'seon.render.proc/main-info {} db0)
        db1 (d/db-with db0 (seon.task/agent-tx :a1 {:status :busy}))]
    [st db1 (vec (apply concat (d/datoms-diff db0 db1 :eavt [])))]))

(deftest a-move-of-main-costs-what-moved
  ;; the 1.2 s an agent's page took after an eval (root's baseline, 2026-10-27): each of an eval's
  ;; four commits on main rebuilt the reconciler's index of every task (506 ms on 902 tasks), the
  ;; task rows (203), main's problems (181) and its tasks (79), about 970 ms a commit, before the
  ;; pass. Now the held index moves by the watched datoms that differ
  (cost/proof
    {:of 'seon.render.proc/main-info :input 'seon.render-cost-test/moved-of
     :n "tasks on main"
     :work "the index moved by the one status datom (seon.reconcile/index over the watched datoms that differ), never one step per task"
     :growth :constant
     ;; measured 2026-10-29: 9.1 KB and 3 calls at the sizes; before, one datoms-diff for each of the 90 watched
     ;; attributes: 14.6 MB at 25 tasks, 20.1 MB at 50
     :per-n {:calls 4 :bytes 12000}
     :sizes [25 50 100]}))

(defn agent-of
  "The input of size n: the test store's seed with root, its agent a1 and n evaluations of a1 (one
  neighbour each, by the reverse ref :seon.eval/_agent), each evaluation's record as an
  agent's REPL writes it (seon.repl/record-tx): [[db] a1's eid]."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :any] pos-int?]]}
  [n]
  (let [db (reduce d/db-with (ts/test-db)
                   [[{:seon.root/id :root :seon.root/boot "b1"}]
                    (seon.task/agent-tx :a1 {:status :idle})
                    (into [] (mapcat #(seon.repl/record-tx {:seon.agent/key "b1/a1"} (str "e" %) "a1"
                                                           {:seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/e1 :value 3}}))
                          (range n))])]
    [[db] (:db/id (d/entity db [:seon.agent/key "b1/a1"]))]))

(defn walked-of
  "The input of size n: main and agent a1's branch with n evaluations of a1 on the branch (main
  holds none), and a1's eid: what an agent's page reads."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :any] pos-int?]]}
  [n]
  (let [[[branch] eid] (agent-of n)
        main (reduce d/db-with (ts/test-db) [[{:seon.root/id :root :seon.root/boot "b1"}] (seon.task/agent-tx :a1 {:status :busy :branch :b})])]
    [[main branch] eid]))

(defn- watching
  "The render proc's state as it holds pages watched pages, each having read 100 entities."
  {:malli/schema [:=> [:cat pos-int?] :map]}
  [pages] {:views (into {} (for [v (range pages)] [v {}]))
           :read-index (into {} (for [v (range pages) e (range 100)] [[:e (+ (* v 1000) e)] #{v}]))})

(defn- commit-of
  "n datoms of a commit, none on an entity a page read."
  {:malli/schema [:=> [:cat pos-int?] [:vector :map]]}
  [n] (vec (for [i (range n)] {:e (+ 9000000 i) :a :seon.task/plan :v (str i)})))

(defn by-datoms "The input of size n: 100 watched pages, a commit of n datoms." {:malli/schema [:=> [:cat pos-int?] [:tuple :map [:vector :map]]]} [n] [(watching 100) (commit-of n)])
(defn by-pages "The input of size n: n/10 watched pages, a commit of 100 datoms." {:malli/schema [:=> [:cat pos-int?] [:tuple :map [:vector :map]]]} [n] [(watching (quot n 10)) (commit-of 100)])

(deftest a-transaction-costs-its-datoms-to-match
  ;; the pages a commit touched
  ;; are found by its datoms' keys in the proc's one index (seon.render.proc/touched)
  (cost/proof
    {:of 'seon.render.proc/touched :input 'seon.render-cost-test/by-datoms
     :n "datoms in the commit"
     :work "per datom, its five keys (d/datom-keys) and one map lookup a key; nothing per page watched"
     :growth :linear
     ;; measured 2026-10-24: 207 KB / 2.06 MB / 21.2 MB at 100 / 1,000 / 10,000 datoms; one contracted call
     :per-n {:bytes 2120}
     :fixed {:calls 1 :bytes 2000}
     :sizes [100 300 1000 10000]}))

(deftest matching-a-commit-is-flat-in-the-pages-watched
  (cost/proof
    {:of 'seon.render.proc/touched :input 'seon.render-cost-test/by-pages
     :n "ten times the pages watched (each having read 100 entities)"
     :work "the 100 datoms' keys, each one map lookup, whatever the index holds"
     :growth :constant
     ;; measured 2026-10-24: 209 / 207 / 207 KB at 10 / 100 / 1,000 pages
     :fixed {:calls 1 :bytes 212000}
     :sizes [100 300 1000 10000]}))

(defn latest-of
  "The input of size n: the render proc's state holding agent a1's debug page on latest after a pass, over a
  session of n earlier evals and one model turn (its basis recorded as seon.turn/act! writes it), with one eval
  more on the branch since: what the next pass draws (the turn to come's request, made at the head); and the view."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n]
  (let [k "a1"
        ev (fn [id turn] (seon.repl/record-tx {:seon.agent/key k} id "a1" {:seon.eval/code "(+ 1 2)" :seon.eval/response {:handle (symbol "result" id) :value 3} :seon.eval/call (seon.task/call-ref k turn) :seon.eval/call-id (str "c-" id)}))
        db0 (reduce d/db-with (ts/test-db)
                    [[{:seon.root/id :root :seon.root/boot "b1"}]
                     (seon.task/agent-tx :a1 {:status :busy :branch :task-x})])
        db0 (reduce d/db-with db0
                    [(seon.doc/load-tx db0 "docs" {seon.ctx.wire/system-page "# You are a Seon agent\n\nThe help page."})
                     (seon.task/create-tx db0 [{:seon.task/ns 't :seon.task/name "x" :seon.task/plan "Make x." :seon.task/proofs #{'t-test/x-test}}])
                     ;; the agent has no :seon.agent/task: its page draws no task part, as this input has always been (the run it
                     ;; stood for held the task); linked, the draw costs 4,135 calls at 10 against these 3,920
                     (into [] (mapcat #(ev (str "e" %) 0)) (range n))
                     [{:seon.agent/key k :seon.agent/turn 1}
                      {:seon.model-call/id (str k "/1") :seon.model-call/agent [:seon.agent/key k] :seon.model-call/n 1 :seon.model-call/origin :model}]])
        b {:commit (d/commit-id db0) :task "t/x" :turn 1 :max-turns 9 :origin :model :agent k}
        db (d/db-with db0 [{:seon.model-call/id (str k "/1") :seon.model-call/basis b}])
        view [:debug :a1]
        st {:rid 1 :pending {} :watch #{view} :waiting {} :kf-wanted #{} :views {} :main-db db
            :branches {view {:db db :read [:task-x] :branch :task-x}} :branch-reads {:task-x {:db db :walk (volatile! nil)}} :view {:agents {}}}
        [st _] (#'seon.render.proc/pass-view st view)]
    [(assoc-in st [:branches view :db] (d/db-with db (ev "next" 1))) view]))

(deftest drawing-latest-costs-one-request
  ;; task seon.render/the-debug-page-is-the-wire-request: a pass of the debug page on latest after the branch
  ;; moved draws the one request the next turn would send (seon.ctx.wire/wire-turn at the head), nothing per
  ;; earlier turn; the request holds the session, so it grows with the session's evals, as the turn's own does
  (cost/proof
    {:of 'seon.render.proc/pass-view :input 'seon.render-cost-test/latest-of
     :n "earlier evals in the agent's session"
     :work (str "one request made at the branch's head (seon.ctx.wire/render: the system prompt, the session's records each "
                "printed by its own render, the page drawn cold at the head as the call Seon made when the records hold none) "
                "under its provider, put in the wire shape "
                "(seon.model/request-body) and drawn once (seon.render.ui/debug-turn: an entry a message), and the timeline's "
                "strip (a pull of each model call's basis). Linear in the evals: the page's rows, and the cold draw's ctx")
     :growth :linear
     ;; measured 2026-11-03: 3,888 / 8,488 / 24.6k calls and 10.4 / 16.8 / 39.5 MB at 10 / 30 / 100 evals (before the
     ;; print cache and the task's list drawn alone: 8,708 / 21,668 / 67,028 and 16.6 / 33.2 / 92.7 MB); what grows is
     ;; the page's html, an entry a message, and the cold draw's ctx (seon.ctx/build), each about a third
     ;; measured 2026-11-06, a debug read's cold request, no print handed to it: 5,040 / 11.4k / 33.6k calls and 8.77 / 14.4 / 34.3 MB at
     ;; 10 / 30 / 100 evals.
     :per-n {:calls 317 :bytes 280000}
     :fixed {:calls 1900 :bytes 6600000}
     :sizes [10 30 100]}))

(deftest a-transaction-renders-what-it-touched
  ;; task seon.render/a-page-is-its-entitys-view (4a), now every-page-is-its-parts: a commit on main touching
  ;; the agent renders the one unit whose input it moved (its head), whatever the page's rows
  (doseq [n [20 400]]
    (let [[[main branch] _] (walked-of n)
          st {:rid 1 :pending {} :watch #{:a1} :waiting {} :kf-wanted #{} :views {} :main-db main
              :branches {:a1 {:db branch :read [:b]}} :view {:agents {}}}
          [st1 _] (#'seon.render.proc/pass-view st :a1)
          r (d/with main (seon.task/agent-tx :a1 {:status :idle}))
          [st2 _] (#'seon.render.proc/pass-view (assoc st1 :main-db (:db-after r)) :a1)]
      (clojure.test/is (= 1 (- (get-in st2 [:counts :rendered]) (get-in st1 [:counts :rendered]))) (str n " rows: the agent's head rendered, nothing else")))))

(def ^:private kept-walks
  "The walks `step-of` made for the proof in hand, which cost/proof holds no end for: let go by `with-kept-walks`."
  (atom []))

(defn- with-kept-walks
  "f, then every walk `step-of` made for it disposed: one left behind runs at every later redefinition of a render in this process."
  {:malli/schema [:=> [:cat :seon.host/fn] :any]}
  [f] (try (f) (finally (run! seon.render/dispose-walk! @kept-walks) (reset! kept-walks []))))

(defn step-of
  "The input of size n: a kept walk of an agent's page (as seon.render.proc/kept-walk holds one) drawn over n-1 of its turns, each
  as production writes it (docs/turn.md#the-turn-is-a-number: its model call and reply, an eval the call ran, and every third turn a message
  it took), and the db with the n-th turn written: [kept-walk db-before db-after]."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db :seon.host/db]]}
  [n]
  (let [k "b1/a1"
        turn (fn [i] (let [id (str "x" i)]
                       (concat
                        [{:seon.agent/key k :seon.agent/turn (inc i)}
                         {:seon.model-call/id (str k "/" (inc i)) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n (inc i) :seon.model-call/origin :model
                          :seon.model-call/reply (str "Checking step " i ".") :seon.model-call/ms 1}]
                        (seon.repl/record-tx {:seon.agent/key k} id "a1"
                                             {:seon.eval/call-id (str "c-" id) :seon.eval/call (seon.task/call-ref k (inc i)) :seon.eval/code (str "(inc " i ")")
                                              :seon.eval/response {:handle (symbol "result" id) :value {:step i}}})
                        (when (zero? (mod i 3))
                          (update (seon.task/message-tx (str "mm-" i) "owner" [k] (str "How is step " i "?") 1000) 0 assoc
                                  :seon.message/call (seon.task/call-ref k (inc i)))))))
        base (reduce d/db-with (ts/test-db) [[{:seon.root/id :root :seon.root/boot "b1"}] (seon.task/agent-tx :a1 {:status :busy :branch :b})])
        db (reduce (fn [db i] (d/db-with db (vec (turn i)))) base (range (dec n)))
        c (seon.ctx/build (seon.db/index) db (seon.db/eid db :seon.agent/key k) :b)
        at (volatile! db)
        a (seon.ratom/atom (:tree c))
        w (seon.render/walker {:ref a :changes seon.ctx/changes} (fn [] @at) [(requiring-resolve (seon.render/declared (seon.db/index) :seon.render/render-ctx)) {}])]
    (seon.render/flush! w nil)
    (swap! kept-walks conj w)
    [{:w w :a a :c c :at at} db (d/db-with db (vec (turn (dec n))))]))

(defn step!
  "The kept walk kw (held at db, a branch) moved to later, the branch with one more turn, and back (seon.render.proc/advance-walk!
  twice, so that a call repeats): ctx patched by what moved and the components it reached drawn, each way."
  {:malli/schema [:=> [:cat :map :seon.host/db :seon.host/db] :map]}
  [kw db later]
  (-> kw (#'seon.render.proc/advance-walk! later :b identity) (#'seon.render.proc/advance-walk! db :b identity)))

(defn change-of
  "The input of size n: an agent's page kept over n turns as production writes them (`step-of`): its walk and the atom holding its
  ctx, then one eval's value changed (the atom reset! as the patch does): what flush! draws."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/deref]]}
  [n]
  (let [[kw] (step-of n)] [(:w kw) (:a kw)]))

(defn redraw!
  "One change drawn: an eval opened or closed in the atom (each call a change, as a patch reset!s ctx: each component watching it
  asked whether one of its read paths moved), then the walk's frame: what it redrew."
  {:malli/schema [:=> [:cat :map :seon.host/deref] [:map-of [:vector :any] [:enum :miss :same]]]}
  [w a]
  (swap! a (fn [c] (update-in c [:seon.eval/_agent (first (keys (:seon.eval/_agent c))) :seon.eval/opened] not)))
  (seon.render/flush! w nil))

(deftest a-change-costs-its-components
  ;; a change draws the components whose read paths it
  ;; reaches, and nothing else of the page
  (with-kept-walks #(cost/proof
    {:of 'seon.render-cost-test/redraw! :input 'seon.render-cost-test/change-of
     :n "evals in the agent's ctx"
     :work (str "the one eval's component and the turn holding it, drawn again, on real records, and nothing else: no other turn's "
                "reads are reached by an eval opened. The reset! runs ctx's diff once (seon.ctx/changes) and looks up the components its "
                "changed paths reach by index; the diff walks every key of the changed collection, which is linear. 379 contracted calls "
                "at 100 turns, the owner's 2026-11-05: cull the calls later if the perf is bad; 394 and a bound of 400 by the owner's yes, 2026-11-07, reading an eval's result by its ref (seon.db/response-of)")
     :growth :linear
     :per-n {:calls 0 :bytes 3400}
     :fixed {:calls 400 :bytes 150000}
     :sizes [100 300 1000 4000]})))

;; See docs/render.md#a-change-is-edits-by-name
(defn keyed-part
  "The input of size n: a component as status draws it, n named children, then the same with one of them changed: page-edits' one argument, [[id was now]]."
  {:malli/schema [:=> [:cat pos-int?] [:vector :any]]}
  [n]
  (let [was (into ['seon.render.ui/status {:ctx [:seon.agent/task :proved]} [:h2 "Your proofs"]]
                  [(into [:ul {:key :proofs}] (for [i (range n)] (let [t (symbol "demo-test" (str "t-" i))] [:li {:key t} [:code (str t)] ": passes"])))])
        now (assoc-in was [3 3] '[:li {:key demo-test/t-1} [:code "demo-test/t-1"] ": fails"])]
    [[["status" was now]]]))

(deftest edits-cost-the-component
  ;; a diff reads the one component that moved: its children, once, never the page's
  (cost/proof
    {:of 'seon.render/page-edits :input 'seon.render-cost-test/keyed-part
     :n "named children of the component that moved"
     :work (str "the component read as a map by name, on each side (seon.render/keyed); editscript's walk of those maps; each edit named. "
                "Linear in the children in bytes and flat in contracted calls, whatever the rest of the page holds; ticks 27k at 10 children, 326k at 100, "
                "17.6M at 1,000, the map reads being an array map's")
     :growth :linear
     :per-n {:calls 0 :bytes 12500}
     :fixed {:calls 9 :bytes 60000}
     :sizes [10 30 100 300]}))

;; See docs/render.md#components
(defn gone-member
  "A member of a list, its text from ctx."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} id] [:li (str id (:n c))])
(defn gone-list
  "A list of ctx's :ids."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}] (into [:ol] (for [id (seon.ratom/realize (:ids c))] [#'gone-member {} id])))

(defn list-of
  "The input of size n: a list of n members drawn, and the atom holding its ids."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/deref]]}
  [n]
  (let [a (seon.ratom/atom {:db/id 1 :ids (vec (range n))})
        w (seon.render/walker {:ref a :changes seon.ctx/changes} (constantly nil) [#'gone-list {}] (seon.db/index))]
    (seon.render/flush! w nil)
    [w a]))

(defn member-gone-and-back!
  "One member let go and drawn again: the list's runs and what went."
  {:malli/schema [:=> [:cat :map :seon.host/deref] :any]}
  [w a]
  (let [ids (:ids @a)]
    (swap! a assoc :ids (pop ids))
    (seon.render/flush! w nil)
    (swap! a assoc :ids ids)
    (seon.render/flush! w nil)))

(deftest a-member-gone-costs-the-lists-lookups
  ;; a member the list no longer draws goes at the flush's end, by who holds it: no walk of the page and no scan of the list per member
  (cost/proof
    {:of 'seon.render-cost-test/member-gone-and-back! :input 'seon.render-cost-test/list-of
     :n "members of the list"
     :work (str "the list run twice, a lookup of each member's marker each time and one set of its children; the one member disposed and drawn "
                "again; nothing per member of who holds it but the one that moved")
     :growth :linear
     ;; measured 2026-11-06: 702 calls and 431 KB at 100
     :per-n {:calls 7 :bytes 4200}
     :fixed {:calls 30 :bytes 40000}
     :sizes [100 300 1000]}))

(deftest a-step-costs-what-changed
  ;; task seon.render/a-step-costs-what-changed (docs/render.md#a-page-is-a-kept-walk): one more turn draws its own component of the
  ;; conversation, found by its number; the earlier turns are a lookup each (a form drawn keeps its marker) and are not drawn again
  (with-kept-walks #(cost/proof
    {:of 'seon.render-cost-test/step! :input 'seon.render-cost-test/step-of
     :n "turns the agent took before the one stepped to and back from"
     :work (str "the turn's records read through the index by its number and drawn (its message, its reply and its eval), ctx patched "
                "by the datoms that moved with one assoc a member, and the list's one lookup of each earlier turn's marker; the datoms "
                "matched once against the walk's index of what each component read. Linear in that lookup and in seon.ctx/moved, the diff "
                "of two db values, which a step through history pays and a live turn, holding its transaction's datoms, does not")
     :growth :linear
     ;; measured 2026-11-05, a step and back on this input: 1,231 / 1,471 / 2,431 / 6,271 contracted calls and 10.86 / 11.95 / 16.68 /
     ;; 33.70 MB at 10 / 40 / 160 / 640 turns; 8 calls a turn, two components each (its turn, its tiles). Before the read index and the turn's component, one step on the same
     ;; input: 11,073 calls and 80.9 MB at 640 turns
     ;; render-core's agent-facing renders (the status written to the agent, a part for each cite, the turn line): 1,355 calls and
     ;; 11.31 MB at 10 turns under the test binary's contracts, 8 calls and 36 KB a turn, as before them; the fixed part is the
     ;; renders the model now reads drawn on a step. 1,365 at 10 since the turn line holds no number and the cost line is sized once
     ;; (seon.print/response-text); the owner approved 250 for a change's 379, and said cull later
     :per-n {:calls 8.2 :bytes 37000}
     :fixed {:calls 1285 :bytes 11250000}
     :sizes [10 40 160 640]})))

(def ^:dynamic *conn*
  "The branch's connection these proofs read and write: the status's its base, main and branch, the draw's its parts (bound by each deftest)."
  nil)

(defn- defn-facts
  "Program facts for var v defined by source src, as an import records them."
  {:malli/schema [:=> [:cat :qualified-symbol :string] [:vector :map]]}
  [v src]
  (let [n (namespace v)]
    [{:db/id (str "ns " n) :ns/name (symbol n) :ns/file (str "src/" (clojure.string/replace n "-" "_") ".clj")}
     {:db/id (str "form " v) :form/id (str v " " src) :form/src src :form/ns (str "ns " n) :form/order 0 :form/defines [(str v)]}
     {:db/id (str v) :var/name v :var/ns (str "ns " n) :var/form (str "form " v) :var/meta #{:arglists}}]))

(defn branch-of
  "The input at n: the status part's arguments, [opts aid]: a base holding one function, main one
  function later, and the branch (the base's db, with the agent whose base commit it is) with n functions
  of its own, each without a contract (so gaps and divergence have n rows each); the task merges itself,
  so main's commit is read too."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [conn *conn* ns (str "bn" n)
        _ (d/transact conn (defn-facts (symbol ns "f") "(defn f [x] x)"))
        base (d/commit-id @conn)
        _ (d/transact conn (defn-facts (symbol ns "k") "(defn k [] 2)"))
        main (d/commit-id @conn)
        ours (reduce (fn [db i] (d/db-with db (defn-facts (symbol ns (str "h" i)) (str "(defn h" i " [] " i ")"))))
                     (d/db-with (d/commit-as-db conn base) [{:seon.agent/key "b/a1" :seon.agent/base-commit base}])
                     (range n))]
    [{:seon.db/db ours :seon.ctx/ctx {:task {:seon.task/kind :whole} :seon.agent/main-program main}}]))

;; See docs/render.md#a-component-answers-hiccup
(deftest the-status-reads-the-branchs-diff-once
  (ts/test-branch!
    (fn [_ conn]
      (binding [*conn* conn]
        (cost/proof {:of 'seon.render.ui/status :input 'seon.render-cost-test/branch-of
                     :n "functions the branch defined without a contract (a base and main one function apart)"
                     :work (str "the datoms that differ between the base and the branch read once (seon.merge/content-datoms), "
                                "then the changes, the problems it caused, the gaps, what it carries and the merge section's "
                                "three-way from them; main's own diff once; the verdicts one scan of the status datoms")
                     :growth :linear
                     ;; measured 2026-10-28: 28.1 MB / 175 calls at 1, 37.4 MB / 742 at 10, 57.9 MB / 2,002 at 30
                     ;; (9.7M, 12.9M, 19.9M ticks); before, the four views each reading the diff: 61.5 MB / 220 calls
                     ;; at 1, 80.9 MB / 1,309 at 10, 124.7 MB / 3,729 at 30 (21.8M, 28.7M, 44.3M ticks)
;; +2 calls a function: caused asks each changed var of the :long-form class (its seek and its form's text)
                     :per-n {:bytes 1100000 :calls 72}
                     ;; +3 calls 2026-10-30: seon.merge/content-attr, one contracted call a merge-diff read, was uncontracted
;; +10 calls: caused asks the :long-form class (seon.problems/long-forms) of the changed vars, its bound read once
                     :fixed {:bytes 27000000 :calls 136}
                     :sizes [1 10 30]})))))

(def ^:dynamic *sels*
  "The draw proof's opts (bound by its deftest)."
  {:model-only true :once true})

(deftest printing-a-result-is-the-models-reading-of-it
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: an eval's printed result is computed the first time the model reads that eval and is a lookup after: a person's walk prints no model text at all
  (let [k "b/a1" n 5
        db (d/db-with (ts/test-db) (seon.doc/load-tx (ts/test-db) "docs" {seon.ctx.wire/system-page "# You are\n\nRules."}))
        db (reduce (fn [db i] (d/db-with db [{:seon.agent/key k :seon.agent/turn (inc i)}
                                             {:seon.eval/id (str "e" i) :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key k} :seon.eval/call (seon.task/call-ref k (inc i))
                                              :seon.eval/code (str "(f " i ")") :seon.eval/call-id (str "c" i) :seon.eval/response {:handle (symbol "result" (str "e" i)) :value i}}]))
                   db (range n))
        a (:db/id (d/entity db [:seon.agent/key k]))
        src (seon.ratom/atom (:tree (seon.ctx/build (seon.db/index) db a :b)))
        w (seon.render/walker {:ref src :changes seon.ctx/changes} (constantly db) [(seon.render/declared-var (seon.db/index) :seon.render/render-ctx) {}])
        prints (fn [f] (seon.profile/measure! f (fn [_])) (or (some (fn [x] (when (= 'seon.print/record-text (:var x)) (:calls x))) (seon.profile/touched)) 0))]
    (try
      (clojure.test/is (zero? (prints #(do (seon.render/flush! w nil) (seon.render/placed-units w)))) "a page drawn and written prints none")
      (clojure.test/is (<= n (prints #(seon.render/wire w))) "the first reading prints each")
      (clojure.test/is (zero? (prints #(seon.render/wire w))) "the next finds each where the walk keeps it")
      (finally (seon.render/dispose-walk! w)))))

(defn page-of
  "The input at n: the arguments of seon.render/draw for agent b/p<n>'s page on a branch where it
  holds a task and n turns, each production-shaped: its eval (a record as the REPL writes it), the reply that holds it and, every other turn, a message taken at it:
  its ctx tree, the diff, the db, the layout and its opts (*sels*)."
  {:malli/schema [:=> [:cat pos-int?] [:vector :any]]}
  [n]
  (let [conn *conn* k (str "b/p" n) tid (str "demo/p" n)]
    (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name (str "p" n) :seon.task/plan "Make p." :seon.task/proofs #{'demo-test/p-test}}]))
    (d/transact conn [{:seon.agent/key k :seon.agent/turn n :seon.agent/task {:seon.task/id tid}}])
    (doseq [i (range n)]
      (d/transact conn [{:seon.eval/id (str "p" n "e" i) :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key k}
                         :seon.eval/call (assoc (seon.task/call-ref k (inc i)) :seon.model-call/origin :model :seon.model-call/reply (str "Trying " i "."))
                         :seon.eval/code (str "(f " i ")") :seon.eval/response {:handle (symbol "result" (str "p" n "e" i)) :value (vec (range 5)) :ns 'user} :seon.eval/call-id (str "c" i) :seon.eval/intent (str "Step " i ".")}])
      (when (even? i)
        (d/transact conn (update (seon.task/message-tx (str "p" n "m" i) "user" [k] (str "note " i) 1000) 0 assoc :seon.message/call (seon.task/call-ref k (inc i))))))
    (let [db @conn a (:db/id (d/entity db [:seon.agent/key k]))]
      (into [(:tree (seon.ctx/build (seon.db/index) db a (:seon.agent/branch (d/entity db a)))) seon.ctx/changes db (seon.render/declared-var (seon.db/index) :seon.render/render-ctx)] (some-> *sels* vector)))))

;; See docs/render.md#the-render-is-an-eval
(deftest a-cold-request-draws-what-the-model-reads
  ;; docs/render.md#a-layout-places-its-lists: a request with no live walk (a replay at a turn's commit, a judge's first move) draws the tree the model reads,
  ;; its conversation among it, and nothing for the page alone: no tile, no eval's cost or structured value
  (binding [*conn* nil]
    (ts/test-branch!
      (fn [_ conn]
        (binding [*conn* conn]
          (cost/proof {:of 'seon.render/draw :input 'seon.render-cost-test/page-of
                       :n "evals of the agent (and a message for every other)"
                       :work "the task's list drawn once, a component for each turn, message and eval, each eval's response printed once"
                       :growth :linear
                       ;; set by this proof's first measure, for the owner's word: the old proof's flat 1,050 calls drew one list, and this draws the conversation. An eval's response is printed
                       ;; here once, under the armed printer, 197 calls: seon.ctx.wire/request's old cold cost was 546 a record
                       :per-n {:calls 200 :bytes 120000}
                       :fixed {:calls 700 :bytes 1300000}
                       :sizes [10 40 80]}))))))

(defn turns-of
  "The input at n: the arguments of seon.ctx.wire/diffs-through for agent b/t<n> on a branch where it holds a task and works n turns, each a commit with an eval (a record as the REPL writes it) and the agent's turn number moved: the db, the agent, and the chain [nil p1 ... pn] of the turns' points."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int [:vector [:maybe :map]]]]}
  [n]
  (let [conn *conn* k (str "b/t" n) tid (str "demo/t" n)
        point (fn [] (let [db @conn] {:branch :b :commit (d/commit-id db) :t (:max-tx db)}))]
    (d/transact conn (seon.doc/load-tx @conn "docs" {seon.ctx.wire/system-page (str "# You are a Seon agent " n "\n\nRules.")}))
    (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name (str "t" n) :seon.task/plan "Make t." :seon.task/proofs #{'demo-test/t-test}}]))
    (d/transact conn [{:seon.agent/key k :seon.agent/turn 0 :seon.agent/task {:seon.task/id tid}}])
    [@conn (:db/id (d/entity @conn [:seon.agent/key k]))
     (into [nil] (for [i (range n)]
                   (do (d/transact conn [{:seon.agent/key k :seon.agent/turn (inc i)}
                                         {:seon.eval/id (str "t" n "e" i) :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key k}
                                          :seon.eval/call (assoc (seon.task/call-ref k (inc i)) :seon.model-call/origin :model :seon.model-call/reply (str "Trying " i "."))
                                          :seon.eval/code (str "(f " i ")") :seon.eval/response {:handle (symbol "result" (str "t" n "e" i)) :value (vec (range 5)) :ns 'user} :seon.eval/call-id (str "c" i) :seon.eval/intent (str "Step " i ".")}])
                       (point))))]))

(deftest a-restart-draws-its-turns-in-one-walk
  ;; rule 6 of seon.render/one-walk-per-view: every pair of an agent's turns filled in one pass (seon.ctx.wire/diffs-through), a draw at the
  ;; first point then what moved at each later one, so a restart of T turns costs the first draw and a part a turn, not a draw a pair.
  ;; Measured on the test world, whose store is :memory, where a commit's diff (seon.ctx/moved) walks the whole index; LMDB, where it costs
  ;; what changed, is what counts and is measured on a lab root.
  (ts/test-branch!
    (fn [_ conn]
      (binding [*conn* conn]
        (cost/proof
          {:of 'seon.ctx.wire/diffs-through :input 'seon.render-cost-test/turns-of
           :n "turns of the agent, each a commit holding an eval and the turn number moved"
           :work "the page drawn once at the first point, then at each later point the datoms between it and the one before read, the ctx patched by them and the components they reached drawn again, their edits made"
           :growth :linear
           ;; measured 2026-11-08 on the :memory test store (a commit's diff walks the whole index there, so the bytes creep up; LMDB's costs what changed):
           ;; 20.0 MB / 3,362 calls / 7.1M ticks at 4 turns, 41.7 MB / 5,714 / 15.8M at 8, 84.6 MB / 10,418 / 33.0M at 16
           :per-n {:calls 600 :bytes 5400000}
           :fixed {:calls 1000 :bytes 1000000}
           :sizes [4 8 16]})))))

;; See docs/render.md#a-change-is-edits-by-name
(defn asks-changes
  "A component that asks the walk for what changed between the points in its opts (the :seon.render/changes input), and draws the edits."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:from :map] [:to :map] [:seon.render/changes :seon.host/fn]]]] :any]}
  [{:keys [from to] changes :seon.render/changes}]
  [:p (pr-str (changes from to))])

(defn two-askers
  "A page whose two components, each its own key, ask for the same pair (two views of one point)."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:from :map] [:to :map]]]] :any]}
  [{:keys [from to]}]
  [:div [#'asks-changes {:key :a :from from :to to}] [#'asks-changes {:key :b :from from :to to}]])

(deftest a-pair-of-points-is-computed-once
  ;; rule 6: the input is one function of two points, memoized on the walk: two components and a second ask cost one pass; a pair
  ;; asked cold fills every pair before it from the agent's points in the same pass, and a pair that is not between two turns is drawn alone
  (let [passes (atom []) p0 {:branch :b :commit #uuid "00000000-0000-0000-0000-000000000000" :t 1}
        p1 (assoc p0 :commit #uuid "00000000-0000-0000-0000-000000000001" :t 2) p2 (assoc p0 :commit #uuid "00000000-0000-0000-0000-000000000002" :t 3)
        pages {nil {} p0 {"status" '[f {} "was"]} p1 {"status" '[f {} "now"]} p2 {"status" '[f {} "later"]}}
        through (fn [chain] (swap! passes conj chain)
                  (mapv (fn [[a b]] (seon.render/page-edits (seon.db/index) (seon.render/forms-moved (pages a) (pages b)))) (partition 2 1 chain)))
        w (seon.render/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes :points (fn [] [p0 p1 p2]) :diffs-through through} (constantly nil)
                              [#'two-askers {:from p1 :to p2}])]
    (seon.render/flush! w nil)
    (clojure.test/is (= [[nil p0 p1 p2]] @passes) "two components, one pair, cold: one pass from nothing through p2, filling the pairs before it")
    (let [again ((seon.render/changes-of w) p0 p1)]
      (clojure.test/is (= [[nil p0 p1 p2]] @passes) "an earlier pair of the pass: held")
      (clojure.test/is (= (seon.render/page-edits (seon.db/index) (seon.render/forms-moved (pages p0) (pages p1))) again) "the edits of the two pages"))
    ((seon.render/changes-of w) p0 p2)
    (clojure.test/is (= [[nil p0 p1 p2] [p0 p2]] @passes) "a pair that is not between neighbours is drawn alone")
    (seon.render/dispose-walk! w)
    (let [no-source (seon.render/walker {:ref (seon.ratom/atom {}) :changes seon.ctx/changes} (constantly nil) [#'identity {}] (seon.db/index))]
      (clojure.test/is (= :seon.render/no-diffs-through (:seon.error/type ((seon.render/changes-of no-source) p0 p1))) "no way to draw a point: a typed error, not nothing"))))
