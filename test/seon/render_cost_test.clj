(ns seon.render-cost-test
  "The render calls' cost proof: a page whose parts nest other values' renders by reference (a
  hiccup vector headed by a function, Reagent's call), deeply, with the budget split down, is
  serialized in one pass, linear in its nodes (owner, 2026-10-23: renders as functions and results;
  root: a cost proof for the recursion). Run: bin/test seon.render-cost-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost] [seon.web.html]
            [seon.test-store :as ts] [datahike.api :as d] [seon.render.proc] [seon.render] [seon.render.ui] [seon.config] [seon.task] [seon.repl]))

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

(defn runs
  "The input of size n: a vector of n runs (each a value whose schema declares a render),
  printed by the default render under a budget that shows them all."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n] [{:budget 200000}
       (vec (for [i (range n)] {:seon.run/id (str "t/x#" i) :seon.run/status :done :seon.run/outcome :green}))])

(deftest the-printer-renders-nested-values-in-one-pass
  ;; the printer's auto-render: each nested value with a render is named in one lookup per key
  ;; (seon.render/schema-of, no validation) and rendered once, in the printer's one pass
  (cost/proof
    {:of 'seon.render/html :input 'seon.render-cost-test/runs
     :n "runs in a vector, each shown by its own render in the printer's hiccup"
     :work (str "one pass: per run, the printer classifies it once and asks its render once: one "
                "identity lookup over its three keys, a call of its render (seon.render.ui/run-row) inline, "
                "and its one line by seon.print/line, then the printer lays it out as a leaf. Nothing per "
                "run is done twice and no run is printed as data")
     :growth :linear
     ;; contracted calls a run, along the path above: seon.render's nested, render-of with
     ;; own-key, schema-of and declared (its parents) each, print/line and its prose
     :per-n {:calls 19}
     :fixed {:calls 47}
     :sizes [100 300 1000 4000]}))

(defn main-of
  "The input of size n: an empty render state and the test store's seed with n units of 20 more
  evaluation records on main (three datoms each: data, not program; 60 datoms a unit, so at
  n = 1000 the records' 60k datoms dwarf the seed's ~5k), as the render proc reads main when
  its t moves."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n]
  [{} (d/db-with (ts/test-db) (into [{:db/id "a" :seon.agent/id :a1}]
                                    (for [i (range (* 20 n))] {:seon.eval/id (str "a1-1-1/" i) :seon.eval/agent "a"
                                                        :seon.eval/record {:code "(+ 1 2)" :response {:value 3}}})))])

(deftest main-info-reads-what-the-page-shows
  ;; global-ids' profile (2026-10-23): main-info took 174 ms a call on the owner's main, 695 ms
  ;; of one loop turn; its count of every datom grew with main (15 ms at 47k datoms). What the
  ;; render proc reads of main is its t and the reconciler's index (its runs, for :agent-run):
  ;; none grows with main's other data
  (cost/proof
    {:of 'seon.render.proc/main-info :input 'seon.render-cost-test/main-of
     :n "units of 20 evaluation records on main (data that is neither program, task nor problem)"
     :work (str "none per record: the runs are the reconciler's index over their own attributes; a record "
                "is never walked")
     :growth :constant
     ;; the contracted calls a first read of main makes, whatever main holds, never one per record;
     ;; and its bytes, which a walk over every datom would grow with the records
     :per-n {:calls 33 :bytes 4000000}
     :sizes [100 400 1000]}))

(defn moved-of
  "The input of size n: the render state holding main with n tasks (main-info's first read), and
  main one agent status later (one of the four status and running commits an eval makes)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n]
  (let [db0 (d/db-with (ts/test-db) (seon.task/create-tx (ts/test-db) (vec (for [i (range n)] {:seon.task/ns 't :seon.task/name (str "k" i) :seon.task/plan (str "Make " i ".") :seon.task/proofs #{}}))))
        db0 (-> db0 (d/db-with [{:seon.root/id :root :seon.root/boot "b1" :seon.config.agent/max-running 4}]) (d/db-with (seon.task/agent-tx "b1" :a1 {:status :idle})))
        st (#'seon.render.proc/main-info {} db0)
        db1 (d/db-with db0 (seon.task/agent-tx "b1" :a1 {:status :busy}))]
    [st db1]))

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
     :per-n {:calls 4 :bytes 16000000}
     :sizes [25 50 100]}))

(defn agent-of
  "The input of size n: the test store's seed with root, its agent a1 and n evaluations of a1 (one
  neighbour each, by the reverse ref :seon.eval/_agent), each evaluation's record as an
  agent's REPL writes it (seon.repl/record-tx): [[db] a1's eid]."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :any] pos-int?]]}
  [n]
  (let [db (reduce d/db-with (ts/test-db)
                   [[{:seon.root/id :root :seon.root/boot "b1"}]
                    (seon.task/agent-tx "b1" :a1 {:status :idle})
                    (into [] (mapcat #(seon.repl/record-tx {:seon.agent/key "b1/a1" :seon.agent/id :a1} (str "e" %) "a1"
                                                           {:code "(+ 1 2)" :response {:handle 'result/e1 :value 3}}))
                          (range n))])]
    [[db] (:db/id (d/entity db [:seon.agent/key "b1/a1"]))]))

(defn walked-of
  "The input of size n: main and agent a1's branch with n evaluations of a1 on the branch (main
  holds none), and a1's eid: what an agent's page reads."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :any] pos-int?]]}
  [n]
  (let [[[branch] eid] (agent-of n)
        main (reduce d/db-with (ts/test-db) [[{:seon.root/id :root :seon.root/boot "b1"}] (seon.task/agent-tx "b1" :a1 {:status :busy :branch :b})])]
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
  ;; task seon.render/a-view-renders-once-per-change-of-its-reads (C): the pages a commit touched
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

(defn debug-of
  "The input of size n: the render proc's state holding agent a1's debug page after a pass over n
  model turns (its shown rows rendered as units), with one turn more on the
  branch: each turn sent the same two evals (:seon.turn/sent); and the view."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n]
  (let [k (seon.task/agent-key "b1" :a1)
        sent [[:eval "e1"] [:eval "e2"]]
        turn (fn [i] {:seon.turn/id (str "t/x#1/" i) :seon.turn/run [:seon.run/id "t/x#1"] :seon.turn/n i :seon.turn/origin :model :seon.turn/sent sent
                      :seon.turn/tokens 100 :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 1})
        db (reduce d/db-with (ts/test-db)
                   [[{:seon.root/id :root :seon.root/boot "b1"}]
                    (seon.task/agent-tx "b1" :a1 {:status :busy})
                    (into [] (mapcat #(seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a1} % "a1" {:code "(+ 1 2)" :response {:handle 'result/e1 :value 3} :run "t/x#1" :call-id (str "c-" %) :call 0})) ["e1" "e2"])
                    [{:db/id "run" :seon.run/id "t/x#1"}]
                    (mapv turn (range n))])
        view [:debug :a1]
        st {:rid 1 :pending {} :watch #{view} :waiting {} :kf-wanted #{} :views {} :main-db db
            :branches {view {:db db :read [:task-x]}} :view {:agents {}}}
        [st _] (#'seon.render.proc/pass-view st view)]
    [(assoc-in st [:branches view :db] (d/db-with db [(turn n)])) view]))

(deftest a-new-turn-costs-one-turns-messages
  ;; task seon.context/a-turn-records-what-it-sent (C): a pass of the debug page after one new turn
  ;; reads back that turn's records alone (seon.context/parts-of) and sizes them (where its tokens
  ;; went, drawn once, flat in the turns held: root, 2026-10-25); every held turn keeps its input
  (cost/proof
    {:of 'seon.render.proc/pass-view :input 'seon.render-cost-test/debug-of
     :n "model turns the debug page already holds"
     :work (str "the new turn's row, a unit (drawn by seon.render.ui/sent-request when its input changes), read back from its "
                "records (its two evals, each by seon.context/eval-messages), its request sized from those parts "
                "(seon.context/sent-split: system, conversation, screen), and rendered once: the contracted calls, "
                "flat in the turns held. A real turn also reads back its help page and its task data as of their t "
                "(seon.context/parts-of: 2.24M ticks, 9.3 MB on context-test's fixture, mostly the task data's run "
                "sections), once: a finished turn's row is cached and never rendered again. Per held turn: the list's "
                "query of every turn's id, its place in the sort and its slot, no render")
     :growth :linear
     ;; measured 2026-10-24: 357 / 357 calls and 1.20 / 4.37 MB at 100 / 1,000 held turns, one component
     ;; rendered (before rows were components: 130 calls and 3.90 / 34.3 MB, ~34 KB a held turn)
     :per-n {:bytes 3600}
     ;; 403 once each row's id is seon.render.ui/comp-id;
     ;; 404 since a row's records are printed as sent, in order (seon.context/record-parts, 2026-10-25);
     ;; 429 and 1.51 MB: the whole request read once, and sized (where its tokens went), in place of
     ;; the read of the added parts alone (2026-10-25); 458 with the page's shared bar and header
     ;; (seon.render.ui/top-bar: their elements and attributes, serialized once a pass, 2026-10-25);
     ;; 459 since sent-split sizes the parts together (seon.context/messages-tokens, one call a pass:
     ;; the sizer is not additive across pieces; measured per function, 2026-10-25); 500 with the bar's
     ;; toggle between a page and what its model reads (its tabs' elements and attributes serialized,
     ;; seon.web.html: 41 calls, measured per function against main, 2026-10-26)
     :fixed {:calls 500 :bytes 1210000}
     ;; the full run measures 4,000 too (:seon.config.proofs/cost-max-n)
     :sizes [100 300 1000 4000]}))

(defn growing-debug
  "The render proc's state holding agent a1's debug page after n model turns as a real run
  sends them: turn i sent every eval of the run so far (e0 .. e(i-1), its conversation) and its
  screen eval (s<i>), so each request is one record longer than the one before; and the view."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n]
  (let [k (seon.task/agent-key "b1" :a1)
        ev (fn [id by more] (seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a1} id by (merge {:code "(+ 1 2)" :response {:handle 'result/e1 :value 3} :run "t/x#1"} more)))
        db (reduce d/db-with (ts/test-db)
                   [[{:seon.root/id :root :seon.root/boot "b1"}]
                    (seon.task/agent-tx "b1" :a1 {:status :busy})
                    [{:db/id "run" :seon.run/id "t/x#1"}]
                    (into [] (mapcat (fn [i] (concat (ev (str "e" i) "a1" {:call-id (str "c" i) :call 0}) (ev (str "s" i) "screen" {})))) (range n))
                    (vec (for [i (range n)]
                           {:seon.turn/id (str "t/x#1/" i) :seon.turn/run [:seon.run/id "t/x#1"] :seon.turn/n i :seon.turn/origin :model
                            :seon.turn/sent (conj (mapv #(vector :eval (str "e" %)) (range i)) [:eval (str "s" i)])
                            :seon.turn/tokens 100 :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 1}))])
        view [:debug :a1]]
    [{:rid 1 :pending {} :watch #{view} :waiting {} :kf-wanted #{} :views {} :main-db db :branches {view {:db db}} :view {:agents {}}} view]))

(defn page-size
  "Agent a1's debug page after n turns (growing-debug), its first pass: {:bytes :nodes}, the
  composed HTML's characters and its elements."
  {:malli/schema [:=> [:cat pos-int?] :map]}
  [n]
  (let [[st v] (growing-debug n)
        [st _] (#'seon.render.proc/pass-view st v)
        html (seon.render.proc/view-html st v)]
    {:bytes (count html) :nodes (count (re-seq #"<[a-z]" html))}))

(deftest a-transaction-renders-what-it-touched
  ;; task seon.render/a-page-is-its-entitys-view (4a), now every-page-is-its-parts: a commit on main touching
  ;; the agent renders the one unit whose input it moved (its head), whatever the page's rows
  (doseq [n [20 400]]
    (let [[[main branch] _] (walked-of n)
          st {:rid 1 :pending {} :watch #{:a1} :waiting {} :kf-wanted #{} :views {} :main-db main
              :branches {:a1 {:db branch :read [:b]}} :view {:agents {}}}
          [st1 _] (#'seon.render.proc/pass-view st :a1)
          r (d/with main (seon.task/agent-tx "b1" :a1 {:status :idle}))
          [st2 _] (#'seon.render.proc/pass-view (assoc st1 :main-db (:db-after r)) :a1)]
      (clojure.test/is (= 1 (- (get-in st2 [:counts :rendered]) (get-in st1 [:counts :rendered]))) (str n " rows: the agent's head rendered, nothing else")))))

(defn change-of
  "The input of size n: an agent's page walked live (seon.render/walker over a reactive atom holding
  its ctx: n evals, each a record of a short form and its value, by eid newest first, as
  seon.ctx/build holds them) and drawn once, then one eval's value changed (the atom reset! as the
  patch does, the eval's component queued by its reaction): what frame! draws."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/deref]]}
  [n]
  (let [evals (#'seon.ctx/members-map
                    (for [i (range n) :let [id (str "e" i)]]
                      [(+ 10 i) {:db/id (+ 10 i) :seon.eval/id id :seon.eval/by "a1"
                                 :seon.eval/record {:code (str "(inc " i ")") :response {:handle (symbol "result" id) :value (inc i)}}}]))
        a (seon.ratom/atom {:db/id 1 :seon.agent/key "b/a1" :seon.agent/id :a1 :evals evals})
        w (seon.render/walker {:ref a :changes seon.ctx/changes} (constantly (ts/test-db)) [(requiring-resolve (seon.render/declared :seon.render/render-ctx)) {}])]
    (seon.render/frame! w nil)
    [w a]))

(defn redraw!
  "One change drawn: eval 10 opened or closed in the atom (each call a change, as a patch reset!s ctx:
  each component watching it asked whether one of its read paths moved), then the walk's frame."
  {:malli/schema [:=> [:cat :map :seon.host/deref] [:vector :any]]}
  [w a]
  (swap! a update-in [:evals 10 :seon.eval/opened] not)
  (seon.render/frame! w nil))

(deftest a-change-costs-its-components
  ;; task seon.render/a-page-is-its-components: a change draws the components whose read paths it
  ;; reaches, and nothing else of the page
  (cost/proof
    {:of 'seon.render-cost-test/redraw! :input 'seon.render-cost-test/change-of
     :n "evals in the agent's ctx"
     :work (str "the one eval's component, drawn again (its record printed in one line), and nothing else: the "
                "layout's reads (the evals' ids and their values) are not reached by an eval opened. The reset! runs "
                "ctx's diff once (seon.ctx/changes) and looks up the components its changed paths reach by index: the "
                "walk's part is flat (137 contracted calls, about 100 KB, measured 100 to 4,000); the diff walks every "
                "key of the changed collection, about 3.2 KB an eval (seon.ctx/changes alone: 268 KB at 100, 12.8 MB "
                "at 4,000), which is linear")
     :growth :linear
     :per-n {:calls 0 :bytes 3400}
     :fixed {:calls 140 :bytes 150000}
     :sizes [100 300 1000 4000]}))
