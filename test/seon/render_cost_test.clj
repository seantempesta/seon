(ns seon.render-cost-test
  "The render calls' cost proof: a page whose parts nest other values' renders by reference (a
  hiccup vector headed by a function, Reagent's call), deeply, with the budget split down, is
  serialized in one pass, linear in its nodes (owner, 2026-10-23: renders as functions and results;
  root: a cost proof for the recursion). Run: bin/test seon.render-cost-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost] [seon.web.html] [seon.db :as pdb]
            [seon.test-store :as ts] [datahike.api :as d] [seon.render.proc] [seon.render] [seon.render.ui] [seon.config] [seon.task] [seon.repl] [seon.doc] [seon.ctx] [seon.ctx] [seon.ratom] [seon.program] [seon.ctx.wire] [seon.fixtures]))

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
  [n] [{:budget 200000 :seon.render/index (seon.db/index)}
       (vec (for [i (range n)] {:seon.message/id (str "m" i) :seon.message/from {:seon.agent/key "user"} :seon.message/body "hello"}))])

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
     ;; 30,495 at 180 (168 a message and 255 once).
     ;; 2026-11-10, step 2 (senders as refs): bound 170 -> 171 calls a message, the +1 the one call of seon.render/reference-value per
     ;; sender (a printed reference is its identity value); measured 3,657 at 20 and 10,497 at 60 (170.6 a message, 168 before). Ruled
     ;; by Point for the owner on 2026-11-10, awaiting his word. The +1 is the reference rule's own call, which no other fix removes: the ~200 calls a message of a one-key sender map were seon.render's hook path before that rule, and the printer alone shows no namespace cost (seon.print-cost-test/a-nested-maps-key-namespace-costs-nothing-more)
     :per-n {:calls 171}
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

(defn moved-of
  "The input of size n: the render state holding main with n tasks (main-info's first read), and
  main one agent status later (one of the four status and running commits an eval makes), and the
  datoms that differ between them, which advance-main reads once and main-info takes."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any :any]]}
  [n]
  (let [db0 (d/db-with (ts/test-db) (seon.task/create-tx (ts/test-db) (vec (for [i (range n)] {:seon.task/ns 't :seon.task/name (str "k" i) :seon.task/plan (str "Make " i ".") :seon.task/proofs #{}}))))
        db0 (-> db0 (d/db-with (seon.test-store/root-tx {:seon.system/boot "b1" :seon.config.agent/max-running 4})) (d/db-with (seon.task/agent-tx :a1 {:status :idle})))
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
                   [[{:seon.system/id :root :seon.system/boot "b1"}]
                    (seon.task/agent-tx :a1 {:status :idle})
                    (into [] (mapcat #(seon.repl/record-tx {:seon.agent/key "b1/a1"} (str "e" %) (seon.task/sender "a1")
                                                           {:seon.eval/code "(+ 1 2)" :seon.eval/response {:handle 'result/e1 :value 3}}))
                          (range n))])]
    [[db] (:db/id (d/entity db [:seon.agent/key "b1/a1"]))]))

(defn walked-of
  "The input of size n: main and agent a1's branch with n evaluations of a1 on the branch (main
  holds none), and a1's eid: what an agent's page reads."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :any] pos-int?]]}
  [n]
  (let [[[branch] eid] (agent-of n)
        main (reduce d/db-with (ts/test-db) [[{:seon.system/id :root :seon.system/boot "b1"}] (seon.task/agent-tx :a1 {:status :busy :branch :b})])]
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
        ev (fn [id turn] (seon.repl/record-tx {:seon.agent/key k} id (seon.task/sender "a1") {:seon.eval/code "(+ 1 2)" :seon.eval/response {:handle (symbol "result" id) :value 3} :seon.eval/call (seon.task/call-ref k turn) :seon.eval/call-id (str "c-" id)}))
        db0 (reduce d/db-with (ts/test-db)
                    [[{:seon.system/id :root :seon.system/boot "b1"}]
                     (seon.task/agent-tx :a1 {:status :busy :branch :task-x})])
        db0 (reduce d/db-with db0
                    [(seon.doc/load-tx db0 "docs" {seon.doc/system-page "# You are a Seon agent\n\nThe help page."})
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
            :branches {view {:db db :read [:task-x] :branch :task-x}} :branch-reads {:task-x {:db db}} :view {:agents {}}}
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
                "printed by its own render, the turn's page message made at the head) "
                "under its provider, put in the wire shape "
                "(seon.model/request-body), its head and the rows the move added drawn (seon.render.ui/debug-units: a unit a message), and the timeline's "
                "strip (a pull of each model call's basis). Linear in the evals: the page's rows, and the request read from the agent's kept walk "
                "(seon.render.proc/branch-walk, advanced by the eval more), never a cold draw")
     :growth :linear
     ;; 2026-11-14 (web): restated in ticks and bytes, the owner's measure (ticks, bytes, seconds; calls retired: they count private
     ;; helpers production does not check, W-5's marker tests among them). Ticks = the work's calls above x ~1.4k ticks a contracted call
     ;; on this render path (seon.render-cost-test/a-request-made-again-costs-what-moved: 3.65M ticks over 2,598 calls, 2026-11-14).
     ;; bytes from the bound, never a run (root, 2026-11-14): an eval is its message text (~1 KB), its html entry (~3 KB), its wire JSON
     ;; (~2 KB) and its pulled entity (~2 KB): ~8 KB, 20 KB with slack; fixed, the system prompt (~20 KB), the agent's ctx (~200 KB),
     ;; the page's skeleton (~50 KB) and the request body (~50 KB): ~0.35 MB, 1 MB with slack
     :per-n {:ticks 444000 :bytes 20000}
     :fixed {:ticks 2660000 :bytes 1000000}
     ;; measured 8.2 / 11.0 / 19.2 MB at 3 / 10 / 30: ~8x the fixed bound and ~20x the per-eval one, waste, its task
     ;; read from the kept walk (web, 2026-11-14): 14.28 MB and 4.40M ticks at 30, against 19.23 MB and 6.21M drawn cold
     ;; a row a unit (web, 2026-11-15): 10.33 MB and 2.99M ticks at 30; what still grows an eval is the request made whole each move
     ;; (seon.ctx/node, own, join, linked ~50 KB an eval; the wire shape ~20 KB): context's P7
     ;; seon.render/a-debug-entry-allocates-what-it-shows
     :overrun {:task "seon.render/a-debug-entry-allocates-what-it-shows" :n 30 :measured {:ticks 2990000.0 :bytes 10330000.0}}
     :sizes [3 10 30]}))

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

(defn step-of
  "The input of size n: a kept walk of an agent's page (seon.render.proc/kept-walk, a value) drawn over n-1 of its turns, each
  as production writes it (docs/turn.md#the-turn-is-a-number: its model call and reply, an eval the call ran, and every third turn a message
  it took), and the db with the n-th turn written: [kept-walk db-before db-after]."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db :seon.host/db]]}
  [n]
  (let [k "b1/a1"
        turn (fn [i] (let [id (str "x" i)]
                       (concat
                        [{:seon.agent/key k :seon.agent/turn (inc i)}
                         {:seon.model-call/id (seon.lib/call-id k (inc i)) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n (inc i) :seon.model-call/origin :model
                          :seon.model-call/reply (str "Checking step " i ".") :seon.model-call/ms 1}]
                        (seon.repl/record-tx {:seon.agent/key k} id (seon.task/sender "a1")
                                             {:seon.eval/call-id (str "c-" id) :seon.eval/call (seon.task/call-ref k (inc i)) :seon.eval/code (str "(inc " i ")")
                                              :seon.eval/response {:handle (symbol "result" id) :value {:step i}}})
                        (when (zero? (mod i 3))
                          (seon.task/message-tx (str "mm-" i) (seon.task/sender "owner") [k] (str "How is step " i "?") 1000)))))
        base (reduce d/db-with (ts/test-db) [[{:seon.system/id :root :seon.system/boot "b1"}] (seon.task/agent-tx :a1 {:status :busy :branch :b})])
        ;; a turn, then its basis at the branch's t as the loop writes it (seon.turn/basis!), which places a message delivered before it
        with-turn (fn [db i] (let [db (d/db-with db (vec (turn i)))]
                               (d/db-with db [{:seon.model-call/id (seon.lib/call-id k (inc i))
                                               :seon.model-call/basis {:commit (random-uuid) :t (:max-tx db) :task "demo/b1" :turn (inc i) :max-turns 9 :origin :model}}])))
        db (reduce with-turn base (range (dec n)))
        kw (#'seon.render.proc/kept-walk db (seon.db/eid db :seon.agent/key k) :b {:seon.agent/key k} identity)]
    (seon.fixtures/no-error-render (:defects (:p kw)))
    [kw db (with-turn db (dec n))]))

(defn step!
  "The kept walk kw (held at db, a branch) moved to later, the branch with one more turn, and back (seon.render.proc/advance-walk!
  twice, so that a call repeats): ctx patched by what moved and the components it reached drawn, each way."
  {:malli/schema [:=> [:cat :map :seon.host/db :seon.host/db] :map]}
  [kw db later]
  (-> kw (#'seon.render.proc/advance-walk! later :b identity) (#'seon.render.proc/advance-walk! db :b identity)))

(defn change-of
  "The input of size n: an agent's page kept over n turns as production writes them (`step-of`): its walk's value and the db it is at,
  which `redraw!` changes one eval of."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db]]}
  [n]
  (let [[kw] (step-of n)] [(:p kw) (:db kw)]))

(defn redraw!
  "One change drawn: walk value p moved to its tree with an eval opened or closed (each call a change from p, as a patch moves ctx: the
  tree's diff, seon.ctx/changes, looks up the components whose read paths it reaches), nothing moved in db: what it redrew."
  {:malli/schema [:=> [:cat :map :seon.host/db] [:map-of [:vector :any] [:enum :miss :same]]]}
  [p db]
  (:ran (seon.render/walk p {:db db :tree (update-in (:tree p) [:seon.eval/_agent (first (keys (:seon.eval/_agent (:tree p)))) :seon.eval/opened] not)
                             :root (:root p) :index (:index p) :outputs #{:seon.render/hiccup} :changes seon.ctx/changes :moved []})))

(deftest a-change-costs-its-components
  ;; a change draws the components whose read paths it
  ;; reaches, and nothing else of the page
  (cost/proof
    {:of 'seon.render-cost-test/redraw! :input 'seon.render-cost-test/change-of
     :n "evals in the agent's ctx"
     :work (str "the one eval's component and the turn holding it, drawn again, on real records, and nothing else: no other turn's "
                "reads are reached by an eval opened. The reset! runs ctx's diff once (seon.ctx/changes) and looks up the components its "
                "changed paths reach by index; the diff walks every key of the changed collection, which is linear. 379 contracted calls "
                "at 100 turns, the owner's 2026-11-05: cull the calls later if the perf is bad; 394 and a bound of 400 by the owner's yes, 2026-11-07, reading an eval's result by its ref (seon.db/response-of)")
     :growth :linear
     :per-n {:calls 0 :bytes 3400}
     :fixed {:calls 400 :bytes 150000}
     :sizes [30 100 300]}))

;; See docs/render.md#a-change-is-edits-by-name
(defn keyed-part
  "The input of size n: a component as status draws it, n named children, then the same with one of them changed: page-edits' arguments, the entity index and [[id was now]]."
  {:malli/schema [:=> [:cat pos-int?] [:vector :any]]}
  [n]
  (let [was (into ['seon.render.ui/status {:ctx [:seon.agent/task :proved]} [:h2 "Your proofs"]]
                  [(into [:ul {:key :proofs}] (for [i (range n)] (let [t (symbol "demo-test" (str "t-" i))] [:li {:key t} [:code (str t)] ": passes"])))])
        now (assoc-in was [3 3] '[:li {:key demo-test/t-1} [:code "demo-test/t-1"] ": fails"])]
    [(seon.db/index) [["status" was now]]]))

(deftest edits-cost-the-component
  ;; a diff reads the one component that moved: its children, once, never the page's
  (cost/proof
    {:of 'seon.render/page-edits :input 'seon.render-cost-test/keyed-part
     :n "named children of the component that moved"
     :work (str "the component read as a map by name, on each side (seon.render/keyed); editscript's walk of those maps; each edit named. "
                "Linear in the children in bytes and flat in contracted calls, whatever the rest of the page holds; ticks 27k at 10 children, 326k at 100, "
                "17.6M at 1,000, the map reads being an array map's")
     :growth :linear
     :per-n {:calls 12 :bytes 12500}
     ;; 2026-11-14 (web, W-5): every reader of an output asks one marker test a node and one element split an element (seon.render/marker-key,
     ;; element-parts, private: checked in tests, not on a production call), contracted calls the inline tests were not: +12 a member (the edited component's
     ;; output holds a row a member, each row's markers tested and elements split; the inline tests counted none) and +22 fixed
     :fixed {:calls 31 :bytes 60000}
     :sizes [10 30 100 300]}))

;; See docs/render.md#components
(defn gone-member
  "A member of a list, its text from ctx."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} id] [:li (str id (:n c))])
(defn gone-list
  "A list of ctx's :ids."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :any] :any]}
  [{c :seon.ctx/ctx} _view] (into [:ol] (for [id (seon.ratom/realize (:ids c))] [#'gone-member {} id])))

(defn- moved-to
  "Walk value p on db moved to tree t, nothing moved in db (seon.render/walk, :moved []): a change of its ctx alone."
  {:malli/schema [:=> [:cat :map :seon.host/db :any] :map]}
  [p db t] (seon.render/walk p {:db db :tree t :root (:root p) :index (:index p) :outputs #{:seon.render/hiccup} :changes seon.ctx/changes :moved []}))

(defn list-of
  "The input of size n: a list of n members drawn as a walk value, and the db it is on."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db]]}
  [n]
  (let [db (ts/test-db)]
    [(seon.fixtures/walk nil {:db db :tree {:db/id 1 :ids (vec (range n))} :root #'gone-list :index (seon.db/index)}) db]))

(defn member-gone-and-back!
  "One member let go and drawn again: the list's runs and what went."
  {:malli/schema [:=> [:cat :map :seon.host/db] :any]}
  [p db]
  (let [t (:tree p)]
    (moved-to (moved-to p db (update t :ids pop)) db t)))

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
  (cost/proof
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
     :sizes [10 40 160]}))

;; On disk, the one-store rule's last resort (docs/cost.md#how-we-measure): a page advanced from one turn's basis to the next
;; takes the datoms between the two commits (seon.ctx/moved, d/datoms-diff), which skips the subtrees they share by address; the
;; in-memory test store gives its nodes no address, so there the diff walks the whole index each advance (4 MB a turn measured)
(def ^:private disk
  (delay (let [{:keys [conn]} (pdb/open! :lmdb (str (seon.config/checkout) "/target/render-cost-" (random-uuid)) 2000 :db)]
           ;; seeded as the test store is (seon.test-store/store): what agents and templates cite, and a stub of the system prompt
           (d/transact conn {:tx-data (ts/standing-targets-tx)})
           (d/transact conn {:tx-data (seon.doc/load-tx @conn "docs" {seon.doc/system-page "# A stub of the system prompt\n\nBe brief."})})
           (pdb/store-cfg :lmdb (get-in @conn [:config :store :path])))))

(defn- disk-branch!
  "(f conn) on a fresh branch of the on-disk store (`disk`), deleted when f returns or throws, as seon.test-store/test-branch! does."
  {:malli/schema [:=> [:cat :seon.host/fn] :any]}
  [f]
  (let [b (keyword (str "test-" (random-uuid)))
        [conn _] (pdb/open-main! @disk b)
        seed (pdb/connect @disk)]
    (try (f conn)
         (finally (d/release conn) (pdb/delete-main! seed b) (d/release seed)))))

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
  "The cold walk proof's opts (bound by its deftest)."
  {:model-only true :once true :outputs #{:seon.render/hiccup :seon.render/openai-compat}})

(deftest printing-a-result-is-the-models-reading-of-it
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: an eval's printed result is computed the first time its model render runs and is a lookup after: a person's walk (no model output asked) prints no model text at all
  (let [k "b/a1" n 5
        db (d/db-with (ts/test-db) (seon.doc/load-tx (ts/test-db) "docs" {seon.doc/system-page "# You are\n\nRules."}))
        db (reduce (fn [db i] (d/db-with db [{:seon.agent/key k :seon.agent/turn (inc i)}
                                             {:seon.eval/id (str "e" i) :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key k} :seon.eval/call (seon.task/call-ref k (inc i))
                                              :seon.eval/code (str "(f " i ")") :seon.eval/call-id (str "c" i) :seon.eval/response {:handle (symbol "result" (str "e" i)) :value i}}]))
                   db (range n))
        a (:db/id (d/entity db [:seon.agent/key k]))
        t (:tree (seon.ctx/build (seon.db/index) db a :b))
        ix (seon.program/index-of-db db)
        opts (fn [outs] {:db db :tree t :root #'seon.render.ui/agent :index ix :outputs outs})
        prints (fn [f] (seon.profile/measure! f) (or (some (fn [x] (when (= 'seon.print/record-text (:var x)) (:calls x))) (seon.profile/touched)) 0))
        both #{:seon.render/hiccup :seon.render/openai-compat}]
    (clojure.test/is (zero? (prints #(seon.fixtures/walk nil (assoc (opts #{:seon.render/hiccup}) :units true)))) "a page drawn and written prints none")
    ;; the walk asked for the model's output prints each result in the eval's model render, and keeps its messages
    (let [p (volatile! nil)]
      (clojure.test/is (<= n (prints #(vreset! p (seon.fixtures/walk nil (opts both))))) "the first reading prints each")
      (clojure.test/is (zero? (prints #(seon.fixtures/walk @p (assoc (opts both) :moved [])))) "the next finds each where the walk keeps it"))))

(defn page-of
  "The input at n: the arguments of seon.render/walk drawing agent b/p<n>'s page cold on a branch where it
  holds a task and n turns, each production-shaped: its eval (a record as the REPL writes it), the reply that holds it and, every other turn, a message taken at it:
  no prev, and its ctx tree, the db, the layout, the db's entity index, the diff and *sels*."
  {:malli/schema [:=> [:cat pos-int?] [:vector :any]]}
  [n]
  (let [conn *conn* k (str "b/p" n) tid (str "demo/p" n)]
    (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name (str "p" n) :seon.task/plan "Make p." :seon.task/proofs #{'demo-test/p-test}}]))
    (d/transact conn [{:seon.agent/key k :seon.agent/turn n :seon.agent/task {:seon.task/id tid}}])
    (doseq [i (range n)]
      ;; a message is delivered before the turn that reads it, so it comes before that turn's call is made
      (when (even? i)
        (d/transact conn (seon.task/message-tx (str "p" n "m" i) (seon.task/sender "user") [k] (str "note " i) 1000)))
      (d/transact conn [{:seon.eval/id (str "p" n "e" i) :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key k}
                         :seon.eval/call (assoc (seon.task/call-ref k (inc i)) :seon.model-call/origin :model :seon.model-call/reply (str "Trying " i "."))
                         :seon.eval/code (str "(f " i ")") :seon.eval/response {:handle (symbol "result" (str "p" n "e" i)) :value (vec (range 5)) :ns 'user} :seon.eval/call-id (str "c" i) :seon.eval/intent (str "Step " i ".")}])
      ;; the turn's basis, the branch's commit and t as the loop writes it (seon.turn/basis!: a commit the store holds): what a message delivered before it is placed by
      (d/transact conn [{:seon.model-call/id (seon.lib/call-id k (inc i))
                         :seon.model-call/basis {:commit (d/commit-id @conn) :t (:max-tx @conn) :task tid :turn (inc i) :max-turns 9 :origin :model}}]))
    (let [db @conn a (:db/id (d/entity db [:seon.agent/key k]))]
      [nil (merge {:db db :tree (:tree (seon.ctx/build (seon.db/index) db a (:seon.agent/branch (d/entity db a)))) :root #'seon.render.ui/agent
                   :index (seon.program/index-of-db db) :changes seon.ctx/changes}
                  *sels*)])))

;; See docs/render.md#the-models-context-a-cached-prefix-then-the-turn
(deftest a-cold-request-draws-what-the-model-reads
  ;; docs/render.md#a-layout-places-its-lists: a request with no live walk (a replay at a turn's commit, a judge's first move) draws the tree the model reads,
  ;; its conversation among it, and nothing for the page alone: no tile, no eval's cost or structured value
  (binding [*conn* nil]
    (disk-branch!
      (fn [conn]
        (binding [*conn* conn]
          (cost/proof {:of 'seon.render/walk :input 'seon.render-cost-test/page-of
                       :n "evals of the agent (and a message for every other)"
                       :work "the task's list drawn once, a component for each turn, message and eval, each eval's response printed once"
                       :growth :linear
                       ;; derived from the work, item by item (each item's calls read off its own code path, web 2026-11-14):
                       ;; - a turn: its eval component (~60 calls of the walk's own and the tree tracking's views of its members:
                       ;;   wrap, view?, notify-deref-watcher!, ~25 each) and its response printed once by the armed printer
                       ;;   (seon.print.select/emit, ~600 calls: classify, more?, member-indent, child, sized, node-of), its reply, and
                       ;;   a message every other turn (~80): ~840 calls and ~1.17 MB a turn (the printer's text and the views, the
                       ;;   print cache filling: a cold request's first draw, which is what one is)
                       ;; - fixed: the task's list, the system message (the doc's markdown) and the page's head: ~6.6k calls, ~12.3 MB
                       ;; Measured on jolt/web 2026-11-14, a first draw at 10 / 20 / 40: 15,057 / 23,343 / 40,207 calls, 24.1 / 33.8 /
                       ;; 59.2 MB (a second draw allocates 15-25% less, its prints cached). The
                       ;; old estimate (200 a turn, 700 fixed) left every size over, so each was measured again 18-43 times: the
                       ;; proof cost ~1.0B ticks for ~63M of draws and ~155M of building its input. The tree tracking of a draw no
                       ;; one advances (~30% of the calls) is the cost to cut next: task seon.render/a-model-render-draws-each-turns-diff
                       :per-n {:calls 850 :bytes 1200000}
                       :fixed {:calls 6700 :bytes 12500000}
                       :sizes [10 20 40]}))))))

(defn- session-eval
  "Eval id of agent a1's turn n, as its REPL records it (seon.repl/record-tx), its call that turn's."
  {:malli/schema [:=> [:cat :string pos-int?] [:vector :any]]}
  [id n]
  (seon.repl/record-tx {:seon.agent/key "a1"} id (seon.task/sender "a1")
                       {:seon.eval/code "(+ 1 2)" :seon.eval/response {:handle (symbol "result" id) :value 3}
                        :seon.eval/call (seon.task/call-ref "a1" n) :seon.eval/call-id (str "c-" id)}))

(defn- session-of
  "Agent a1 on db0 (the system prompt, a task) with n turns, each one eval of the model's: the db, its entity index, a1's eid and a
  function from db to seon.render/walk's opts for a1's request there (its tree pulled as a request pulls it, seon.ctx/agent-ctx).
  Written as production writes it: each transaction a commit on a test branch, so every turn's basis names a commit holding its t
  (a db-with chain named a commit that never held it, and every point read back was a view: seon.ctx/moved then merged the whole db)."
  {:malli/schema [:=> [:cat nat-int?] [:tuple :seon.host/db :map pos-int? :seon.host/fn]]}
  [n]
  (let [k "a1"
        db (ts/test-branch!
             (fn [_ conn]
               (doseq [tx [[{:seon.system/id :root :seon.system/boot "b1"}] (seon.task/agent-tx :a1 {:status :busy :branch :task-x})]]
                 (d/transact conn tx))
               (d/transact conn (seon.doc/load-tx @conn "docs" {seon.doc/system-page "# You are a Seon agent\n\nThe help page."}))
               (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 't :seon.task/name "x" :seon.task/plan "Make x." :seon.task/proofs #{'t-test/x-test}}]))
               ;; each turn its own commit, its call holding its basis as seon.turn writes it: the point its request was made at, before its eval
               (doseq [i (range n) :let [db @conn]]
                 (d/transact conn (conj (session-eval (str "e" i) (inc i))
                                        {:seon.model-call/id (:seon.model-call/id (seon.task/call-ref k (inc i)))
                                         :seon.model-call/basis {:commit (d/commit-id db) :t (:max-tx db) :task "t/x" :turn (inc i)
                                                                 :max-turns 40 :origin :model}})))
               (d/transact conn [{:seon.agent/key k :seon.agent/turn n}])
               @conn))
        ix (seon.program/index-of-db db)
        a (:db/id (d/entity db [:seon.agent/key k]))]
    [db ix a (fn [db] (let [t (seon.ctx/agent-ctx ix db a)]
                        {:db db :tree t :root (seon.render/render-var-of ix (merge {:seon.agent/key k} t)) :index ix :changes seon.ctx/changes
                         :outputs #{:seon.render/hiccup :seon.render/openai-compat} :model-only true}))]))

(defn moved-between-turns-of
  "The input at n: seon.ctx/moved's arguments between the points of turns n-1 and n of agent a1's session (session-of), each the db
  its basis names (seon.ctx.wire/db-at), as seon.ctx.wire/page-parts-through advances its kept walk from one turn to the next."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :nil]]}
  [n]
  (let [[db] (session-of n)
        b (fn [i] (:seon.model-call/basis (d/entity db [:seon.model-call/id (:seon.model-call/id (seon.task/call-ref "a1" i))])))]
    [(#'seon.ctx.wire/db-at db (b (dec n))) (#'seon.ctx.wire/db-at db (b n)) nil]))

;; See docs/render.md#a-page-is-a-kept-walk
(deftest ^{:seon.test/estimate {:ticks 30000000}} what-moved-between-turns-costs-one-turn
  ;; web's probe, 2026-11-13: a 20-turn page cost 190M ticks, 96% seon.ctx/moved, 21 calls at ~8.7M and 26 MB each on a 6k-datom db,
  ;; growing with turns x db: the turns' points were views (a db-with chain whose bases named a commit that never held their t), and a
  ;; view's datoms-diff merges the whole range. Production's points are commits holding their t (40 of 40 on the loop task's run): the
  ;; diff skips what both share. Estimate: the session at three sizes, a diff each
  (cost/proof {:of 'seon.ctx/moved :input 'seon.render-cost-test/moved-between-turns-of
               :n "turns of the agent, an eval each"
               :work (str "the datoms of one turn (12: its eval and its call), found by walking the paths the two commits do not share and "
                          "decoding each unshared leaf whole: a turn's datoms sit in the leaf the session's evals fill, so up to a leaf's "
                          "size (about 40 turns here) that leaf grows with the turns, then splits and stays flat; never the db's size "
                          "(the view path this replaced was 25.9 MB at 10 turns)")
               ;; corrected 11-13 (data's probe, both trees): the first estimate (:log, 20k + 25k a level) left out the leaf decoded whole, so
               ;; 30 schema datoms elsewhere (data's branch) moved the split and the bytes at 40 turns went 140k -> 278k. Linear to a leaf:
               :growth :linear
               :fixed {:calls 1 :bytes 20000}
               :per-n {:bytes 7000}
               :sizes [10 20 40]}))

(defn walk-plus-one-of
  "The input at n: seon.render/walk's arguments for agent a1's request after one eval more (turn n+1), its walk at n turns kept from
  the request before, and the datoms between (seon.ctx/moved)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :map]]}
  [n]
  (let [[db _ _ at] (session-of n)
        db1 (d/db-with db (session-eval (str "e" n) (inc n)))]
    [(seon.render/walk nil (at db)) (assoc (at db1) :moved (seon.ctx/moved db db1 nil))]))

(defn walk-same-of
  "The input at n: seon.render/walk's arguments for agent a1's request at n turns made again on the same db and tree, the walk the
  first made kept."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :map]]}
  [n]
  (let [[db _ _ at] (session-of n) o (at db) p (seon.render/walk nil o)]
    [p (assoc o :moved [])]))

(defn child-page-of
  "The input at n: seon.render/walk's arguments for the first page of c1, an agent a1 started (its :seon.agent/parent), a1 having
  worked n turns: c1 has none, drawn cold, as a forked agent's first page is (its walk is its own, never its parent's)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any :map]]}
  [n]
  (let [[db ix _ _] (session-of n)
        db (d/db-with db [{:seon.agent/key "c1" :seon.agent/root [:seon.system/id :root] :seon.agent/parent [:seon.agent/key "a1"]}])
        c (:db/id (d/entity db [:seon.agent/key "c1"]))
        t (seon.ctx/agent-ctx ix db c)]
    [nil {:db db :tree t :root (seon.render/render-var-of ix (merge {:seon.agent/key "c1"} t)) :index ix :changes seon.ctx/changes
          :outputs #{:seon.render/hiccup :seon.render/openai-compat}}]))

;; See docs/render.md#style
(deftest ^{:seon.test/estimate {:ticks 60000000}} a-forks-first-page-costs-the-same-at-any-parent-length
  ;; a forked agent's first page is drawn cold in its own process (its walk is its own, never its parent's), each of its components
  ;; once: its cost is its own page's, never its parent's history
  (cost/proof {:of 'seon.render/walk :input 'seon.render-cost-test/child-page-of
               :n "turns its parent worked"
               :work (str "the child's 15 components (agent, its state, parts, rules list and rules, system message, examples, proof story, "
                          "conversation list and 2 turns, chat end, eval tiles, page message), each drawn once; the rules read twice")
               :growth :constant
               ;; derived from the work, item by item (each item's per-unit cost read off its own code path's contracted calls):
               ;; - 15 components x 46 calls (the walk's 18: render!, emit, inputs, opts-form, with-id, key-id, comp-key, led!, reads-of, props,
               ;;   id-safe and inputs-in twice; the tree tracking's ~28: read-index, prefixes, view, wrap, fresh per path read), ~15k ticks, ~80 KB = 690 calls
               ;; - rules, twice (drawn, then again when the renders it drew are first drawn): seon.render.ui's 162 and seon.ctx.wire's 43 vars,
               ;;   one css? each, ~43k ticks and ~235 KB a read = 412 calls
               ;; - the printer over the 36 nodes the components print (system message, examples, state), ~32 calls and ~7k ticks a node = 1,150 calls
               ;; - the system page as markdown (seon.doc, 4 sections): ~125k ticks, ~660 KB
               ;; - the db, ctx and config reads (members, parents, watch, config values): ~190 calls, ~90k ticks, ~300 KB
               ;; - 2026-11-14 (web): one seon.render/entity-read-keys per component run, its tree reads as datom keys: 15 calls
               ;; sum: ~2,455 calls, ~0.78M ticks, ~3.3 MB. Measured 2026-11-13 at 2 / 4 / 8: 2,429 calls, 0.82M ticks, 3.54 MB at every size
               ;; (1.01M and 4.17 MB when rules sorted all 162 vars). None of it reads the parent's history
               :fixed {:calls 2455 :bytes 3500000 :ticks 800000}
               :sizes [2 4 8]}))

(defn rules-of
  "The input at k: seon.render/rules' argument, k of seon.render.ui's renders drawn (all in one namespace)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:map-of :any :any]]]}
  [k]
  [(into {} (map (fn [v] [v @v])) (take k (sort-by symbol (filter (comp :malli/schema meta) (vals (ns-interns 'seon.render.ui))))))])

;; See docs/render.md#style
(deftest ^{:seon.test/estimate {:ticks 2000000}} the-rules-cost-their-namespaces-vars-read-once
  (cost/proof {:of 'seon.render/rules :input 'seon.render-cost-test/rules-of
               :n "renders drawn, of one namespace"
               :work (str "the namespace's vars (" (count (ns-interns 'seon.render.ui)) " now) read once (ns-interns ~7k ticks, 93 KB; a meta read and css? each, ~200 ticks and ~700 B), "
                          "the 24 that state rules sorted (~3k): constant in the renders drawn. It sorted every var by symbol: 124k ticks, 492 KB")
               :growth :constant
               ;; calls: rules, and css? once for each var of seon.render.ui, counted from the namespace itself: a restated count went red
               ;; at each new render (163, then 164 with turns-tile)
               :fixed {:calls (inc (count (ns-interns 'seon.render.ui))) :bytes 240000 :ticks 45000}
               :sizes [1 4 16]}))

;; See docs/render.md#a-page-is-a-kept-walk
(deftest a-request-made-again-costs-what-moved
  ;; seon.render/walk, the request's walk kept as a value by its owner: one eval more draws that eval's component, its call's and the
  ;; components that read what moved, every other component reused; the same request again draws nothing
  (cost/proof {:of 'seon.render/walk :input 'seon.render-cost-test/walk-plus-one-of
               :n "turns of the agent, an eval each"
               :work (str "the new eval's and its call's components, the root's page and model render again (the tree's key set it reads moved), "
                          "and the messages put together. Linear: the agent's model render (seon.ctx.wire/agent-msgs) lists every turn again, "
                          "each a reused component; the turn's own component would make it constant (task seon.ctx.wire/a-turn-is-a-component-of-the-request)")
               :growth :linear
               ;; measured 2026-11-12 on jolt/web by the test runner at 10 / 20 / 40: 2,768 / 3,828 / 6,068 calls, 3.10 / 3.92 / 5.63 MB,
               ;; 783k / 976k / 1.38M ticks (the request drawn cold, seon.ctx.wire/render, 2.4M at 40). The first estimate came from root's
               ;; REPL, whose binary counts no private helper's contracted calls; the test runner counts each
               ;; restated 2026-11-12 (context, with web) once the input's calls held their basis as seon.turn writes it: each turn now
               ;; lists its page message component too (+~55 calls, +25 KB a turn), and the first page is drawn once, at turn 1's basis,
               ;; not at the db's own point at every request (fixed -800 calls, -450 KB). Measured on jolt/context: 2,539 / 4,179 / 7,552 calls,
               ;; 2.93 / 4.21 / 6.47 MB at 10 / 20 / 40. The old figures measured an input whose calls had no basis, read at the store's parent commit,
               ;; where the agent was not yet (seon.ctx.wire/db-at): its pages were empty
               ;; restated 2026-11-14 (deps; the owner: "anything close is fine"): 156 KB a turn, measured on jolt/deps' pin build at
               ;; 3.38 / 4.96 / 8.07 MB at 10 / 20 / 40, 2,533 / 4,173 / 7,545 calls. Of it ~161 KB a turn is waste (web's probe: re-listing
               ;; every turn, seon.db/owners via schema-of 42 KB, ratom reads-of 24 KB, wire/turn-entities 18 KB, the ratom's wrap, notify and
               ;; view ~28 KB), which task seon.ctx.wire/a-turn-is-a-component-of-the-request removes: back to ~115 KB a turn when it lands.
               ;; A waiver (:overrun) was refused by health's :no-waived-proof.
               ;; 2026-11-14 (web, W-5): every reader of an output asks one marker test a node and one element split an element (seon.render/marker-key,
               ;; element-parts, private: checked in tests, not on a production call), contracted calls the inline tests were not: +6 a turn (its output's nodes)
               ;; 2026-11-14 (web): +300 fixed calls, the html of the move's new eval and its call, drawn once: the model output places them
               ;; first (::for, no html), and the page's placement used to hit that entry and draw no html (the bug, seon.render/render!'s
               ;; :for-only); measured 2,909 / 4,629 / 8,189 at 10 / 20 / 40, ~300 over the old bound at each size, so fixed, not per turn
               :per-n {:calls 176 :bytes 156000}
               :fixed {:calls 1150 :bytes 1850000}
               :sizes [10 20 40]})
  (cost/proof {:of 'seon.render/walk :input 'seon.render-cost-test/walk-same-of
               :n "turns of the agent, an eval each"
               :work "nothing moved: the root reused, the last answers answered"
               :growth :constant
               ;; measured 2026-11-12 on jolt/web by the test runner: 17 calls, 19.0 KB, 3.5k ticks at every size; 18 and 20.2 KB once the
               ;; queueing of what moved became its own function (walk's form was over the token bound)
               ;; 19 and 23.3 KB once walk's answer is its own function and the page's drawn renders are two kept cells (the render proc's page walks, step 1)
               :fixed {:calls 19 :bytes 24000}
               :sizes [10 20 40]}))

(defn newest-pair-of
  "The input at n: the arguments of seon.ctx.wire/page-edits-between for agent b/t<n>'s newest turn, on a branch where it holds a
  task and has worked n turns, each a commit with an eval and the turn number moved: the db, its index, the agent, and the bases of
  turns n-1 and n."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map :int :map :map]]}
  [n]
  (let [conn *conn* k (str "b/t" n) tid (str "demo/t" n)
        point (fn [] (let [db @conn] {:branch :b :commit (d/commit-id db) :t (:max-tx db)}))]
    (d/transact conn (seon.task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name (str "t" n) :seon.task/plan "Make t." :seon.task/proofs #{'demo-test/t-test}}]))
    (d/transact conn [{:seon.agent/key k :seon.agent/turn 0 :seon.agent/task {:seon.task/id tid}}])
    (let [ps (vec (for [i (range n)]
                    (do (d/transact conn [{:seon.agent/key k :seon.agent/turn (inc i)}
                                          {:seon.eval/id (str "t" n "e" i) :seon.eval/by {:seon.agent/key "a1"} :seon.eval/agent {:seon.agent/key k}
                                           :seon.eval/call (assoc (seon.task/call-ref k (inc i)) :seon.model-call/origin :model :seon.model-call/reply (str "Trying " i "."))
                                           :seon.eval/code (str "(f " i ")") :seon.eval/response {:handle (symbol "result" (str "t" n "e" i)) :value (vec (range 5)) :ns 'user} :seon.eval/call-id (str "c" i) :seon.eval/intent (str "Step " i ".")}])
                        (point))))
          db @conn]
      [db (seon.program/index-of-db db) (:db/id (d/entity db [:seon.agent/key k])) (peek (pop ps)) (peek ps)])))

(deftest a-turns-page-edits-cost-the-same-at-any-length
  ;; task seon.render/a-model-render-draws-each-turns-diff: a turn's diff renders the page parts at its two bases (each pulled by
  ;; what the parts render reads, never the agent's conversation), so the newest pair costs the same at 4 turns as at 64
  (disk-branch!
    (fn [conn]
      (binding [*conn* conn]
        (cost/proof
          {:of 'seon.ctx.wire/page-edits-between :input 'seon.render-cost-test/newest-pair-of
           :n "turns the agent has worked, each a commit holding an eval"
           :work "the page parts pulled and rendered at two bases, and the edits between them"
           :growth :constant
           :fixed {:calls 2000 :bytes 4000000}
           :sizes [4 16 64]})))))

(defn val-member
  "A member of a list, its text its own value in ctx's :vals."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :int] :any]}
  [{c :seon.ctx/ctx} id] [:li (str id ":" (get-in c [:vals id :v]))])
(defn val-list
  "A list of ctx's :ids, each member reading its own value."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :any] :any]}
  [{c :seon.ctx/ctx} _view] (into [:ol] (for [id (seon.ratom/realize (:ids c))] [#'val-member {} id])))

(defn vals-of
  "The input of size n: a list of n members drawn as a walk value, each reading its own value, and the db it is on."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db]]}
  [n]
  ;; held as ctx holds a join: a map sorted by its members' ids, each member an entity
  (let [db (ts/test-db)
        t {:db/id 1 :ids (vec (range 10 (+ 10 n))) :vals (into (sorted-map-by (fn [a b] (cond (and (int? a) (int? b)) (compare b a) (int? a) -1 (int? b) 1 :else (compare (str a) (str b))))) (map (fn [i] [i {:db/id i :v i}])) (range 10 (+ 10 n)))}]
    [(seon.fixtures/walk nil {:db db :tree t :root #'val-list :index (seon.db/index)}) db]))

(defn one-member-changed!
  "One member's value changed and drawn, then changed back and drawn."
  {:malli/schema [:=> [:cat :map :seon.host/db] :any]}
  [p db]
  (let [t (:tree p)]
    (moved-to (moved-to p db (update-in t [:vals 10 :v] inc)) db t)))

(deftest a-flush-costs-the-children-that-changed
  ;; seon.render/a-flush-costs-the-children-that-changed: one member changed reruns that member alone, whatever the list holds
  (cost/proof
    {:of 'seon.render-cost-test/one-member-changed! :input 'seon.render-cost-test/vals-of
     :n "members of the list, one of them changed"
     :work (str "the changed member run twice and nothing else run: calls flat in the members; the swap's diff (seon.ctx/changes) steps past "
                "each other member of the collection it enters, about 0.8 KB a member")
     :growth :linear
     ;; measured 2026-11-10: 81 calls at 10, 40 and 160; 99 KB, 124 KB, 217 KB. 2026-11-12: 87 at 10, 88 KB: the reactions' read index
     ;; is the one value index a walk kept as a value reads too (seon.ratom/read-index, reached), a call each where it was inline
     :per-n {:calls 0 :bytes 900}
     ;; 2026-11-14 (web): +3, the changed member's two runs each derive their tree reads' datom keys (seon.render/entity-read-keys), and
     ;; its re-run takes the entity keys it held out of the walk's index (forget-readers!), which a tree-only reader never held before
     :fixed {:calls 91 :bytes 100000}
     :sizes [10 40 160]}))

(defn big-member
  "A member of a list whose hiccup is ctx's :size elements: the page's bulk, which no change moves."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]]] :any]}
  [{c :seon.ctx/ctx}] (into [:li [:h3 "bulk"]] (for [i (range (:size c))] [:p [:span (str "x" i)]])))
(defn bulk-list
  "A list of one big member (`big-member`) and ctx's :ids, each member reading its own value."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map]]] :any] :any]}
  [{c :seon.ctx/ctx} _view] (into [:ol [#'big-member {}]] (for [id (seon.ratom/realize (:ids c))] [#'val-member {} id])))

(defn bulk-of
  "The input of size n: a page of ten small members and one whose hiccup is n elements, drawn and its units written once, then one
  small member changed and walked: placed-units' argument, that walk value."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [db (ts/test-db)
        t {:db/id 1 :size n :ids (vec (range 10 20)) :vals (into {} (map (fn [i] [i {:v 0}])) (range 10 20))}
        ;; drawn and its units written once (each component's placements kept on its entry)
        p (seon.fixtures/walk nil {:db db :tree t :root #'bulk-list :index (seon.db/index) :units true})]
    ;; one small member changed and drawn, its units not yet written: what placed-units, on the walk value, writes
    [(moved-to p db (update-in t [:vals 10 :v] inc))]))

;; docs/render.md#a-layout-places-its-lists
(deftest a-flush-places-what-moved
  ;; seon.render/a-flush-places-what-moved: where each unit sits is read from what each component places, kept by its revision, never from the page's
  ;; hiccup: one small member changed, the units placed costs the same whatever an unchanged member's hiccup holds (red before: a prewalk
  ;; of every placed component's output on each call, O(page hiccup), 289,823 ticks and 2.19 MB for 22 units at 10 turns, 2026-11-10)
  (cost/proof
    {:of 'seon.render/placed-units :input 'seon.render-cost-test/bulk-of
     :n "elements in an unchanged member's hiccup"
     :work "each component's kept placements read once, the changed member's html written again; nothing of the unchanged member's hiccup"
     :growth :constant
     ;; measured 2026-11-11: 2 calls and 18.8 KB at 10, within 20 KB at 100 and 1000; before (the prewalk) 31.2 KB at 10, 83.7 KB at 100 (n^0.43).
     ;; Those 2 calls were wrong: the input was a walker's cells, which the measure's first call wrote, so every later call it kept
     ;; measured a page already laid out. Over a walk value each call does the work: placed-units, placed, places of the changed member,
     ;; styled and thrown-in (5), and its one <li> written by seon.web.html (html, write!, write-attrs!, callback, an attr-name per
     ;; attribute's part and an escape per text: ~23), ~28 calls; its html, its placements and the value's new cache entries, ~35 KB
     ;; 2026-11-14 (web, W-5): every reader of an output asks one marker test a node and one element split an element (seon.render/marker-key,
     ;; element-parts, private: checked in tests, not on a production call), contracted calls the inline tests were not: +8 (the placed list's nodes)
     :fixed {:calls 38 :bytes 40000}
     :sizes [10 100 1000]}))


(defn styled-output
  "The input of size n: one component's output of n elements, each with a class (a misfit) and a child marker every tenth (never walked)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any]]}
  [n] [(into [:section {:id "c"}] (for [i (range n)] (if (zero? (mod i 10)) [:seon.render/child ['x i]] [:p {:class "text-sm" :key i} (str "p" i)])))])

;; docs/render.md#the-hiccup-is-checked
(deftest the-hiccup-check-costs-the-components-own-output
  ;; seon.render/misfits reads one component's own output, its elements' attributes once each, never a child's output
  (cost/proof
    {:of 'seon.render/misfits :input 'seon.render-cost-test/styled-output
     :n "elements of one component's output"
     :work "each element's attributes checked once against the schema, a misfit counted by kind; a child's marker skipped"
     :growth :linear
     ;; measured 2026-11-11: 41 calls and 26.4 KB at 10, within 4 calls and 3 KB an element at 100 and 1000 (seon.contract/fits? an element)
     ;; 2026-11-14 (web, W-5): every reader of an output asks one marker test a node and one element split an element (seon.render/marker-key,
     ;; element-parts, private: checked in tests, not on a production call), contracted calls the inline tests were not: +3 a member (its row's nodes)
     :per-n {:calls 7 :bytes 3000}
     :fixed {:calls 5 :bytes 5000}
     :sizes [10 100 1000]}))
