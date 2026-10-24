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
     :sizes [100 1000 10000]}))

(defn runs
  "The input of size n: a vector of n runs (each a value whose schema declares a render),
  printed by the default render under a budget that shows them all."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any :map]]}
  [n] [(vec (for [i (range n)] {:seon.run/id (str "t/x#" i) :seon.run/status :done :seon.run/outcome :green}))
       {:budget 200000}])

(deftest the-printer-renders-nested-values-in-one-pass
  ;; the printer's auto-render: each nested value with a render is named in one lookup per key
  ;; (seon.render/schema-of, no validation) and rendered once, in the printer's one pass
  (cost/proof
    {:of 'seon.render/ai :input 'seon.render-cost-test/runs
     :n "runs in a vector, each shown by its own :ai render in the printer's text"
     :work (str "one pass: per run, the printer classifies it once and asks its render once: one "
                "identity lookup over its three keys, its :ai render (seon.render.ai/run's line), cut "
                "to one line by seon.print/line, then the printer lays the line out as a leaf. Nothing per "
                "run is done twice and no run is printed as data")
     :growth :linear
     ;; nineteen contracted calls a run, along the path above: seon.render's nested, render-of
     ;; for each face with own-key, schema-of and declared (its parents) each, render and print-ai's
     ;; own call for the :ai face, the run's render and its parts, print/line and its prose;
     ;; measured 1,947 / 19,047 / 76,047 calls at 100 / 1k / 4k (8.3-8.6 us, 18.4-18.9 KB an
     ;; run), the 47 the print's own whatever its size
     :per-n {:calls 19}
     :fixed {:calls 47}
     :sizes [100 1000 4000]}))


(defn main-of
  "The input of size n: an empty render state and the test store's seed with n units of 20 more
  evaluation records on main (three datoms each: data, not program; 60 datoms a unit, so at
  n = 1000 the records' 60k datoms dwarf the seed's ~5k), as the render proc reads main when
  its t moves."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n]
  [{} (d/db-with (ts/test-db) (into [{:db/id "a" :seon.agent/id :a1}]
                                    (for [i (range (* 20 n))] {:seon.eval/id (str "a1-1-1/" i) :seon.eval/agent "a"
                                                        :seon.eval/record {:code "(+ 1 2)" :text "3"}})))])

(deftest main-info-reads-what-the-page-shows
  ;; global-ids' profile (2026-10-23): main-info took 174 ms a call on the owner's main, 695 ms
  ;; of one loop turn; its count of every datom grew with main (15 ms at 47k datoms). What the
  ;; page shows of main is its t, its definitions (an index count over :var/name), its tasks,
  ;; runs, config and problems: none grows with main's other data
  (cost/proof
    {:of 'seon.render.proc/main-info :input 'seon.render-cost-test/main-of
     :n "units of 20 evaluation records on main (data that is neither program, task nor problem)"
     :work (str "none per record: the vars are an index count over :var/name, the tasks and runs the "
                "reconciler's index over their own attributes, the config one pull of root's entity, the "
                "problems their own indexes; a record is never walked")
     :growth :constant
     ;; the contracted calls a read of main makes, whatever main holds: 32 (the reconciler's
     ;; index, the task rows, the config, the problems by area and their parts, and
     ;; seon.problems/uncited-tasks, one call however many tasks), never one per
     ;; record; and its bytes, which a walk over every datom would grow with the records (measured
     ;; 2026-10-23 at 100 units of 20 records: 3.6 MB, 0.79M ticks).
     ;; Timed 2026-10-23 at 1k / 10k / 50k records (8k / 35k / 155k datoms): 1.8 / 1.4 / 1.6 ms,
     ;; where the count of every datom it no longer does took 1.7 / 8.4 / 33.4 ms
     :per-n {:calls 32 :bytes 4000000}
     :sizes [100 400 1000]}))

(defn agent-of
  "The input of size n: the test store's seed with root, its agent a1 and n evaluations of a1 (one
  neighbour each, by the reverse ref :seon.eval/_agent), each evaluation's record as an
  agent's REPL writes it (seon.repl/record-tx); and a1's eid."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any pos-int?]]}
  [n]
  (let [db (reduce d/db-with (ts/test-db)
                   [[{:seon.root/id :root :seon.root/boot "b1"}]
                    (seon.task/agent-tx "b1" :a1 {:status :idle})
                    (into [] (mapcat #(seon.repl/record-tx {:seon.agent/key "b1/a1" :seon.agent/id :a1} (str "e" %) "a1"
                                                           {:code "(+ 1 2)" :text "{:handle result/e1, :value 3}"} nil))
                          (range n))])]
    [db (:db/id (d/entity db [:seon.agent/key "b1/a1"]))]))

(deftest a-walk-reads-its-entity-and-one-hop
  (cost/proof
    {:of 'seon.render/walk :input 'seon.render-cost-test/agent-of
     :n "evaluations of the walked agent (its neighbours by :seon.eval/_agent)"
     :work (str "two pulls: the agent's own attributes, then its ref units (its runs, its evaluations, its root), "
                "each neighbour's own attributes and no further (its refs stay {:db/id}); per evaluation, one node: "
                "its schema by one map lookup per key (seon.render/entity-schema), its prose (texts: a set lookup of each "
                "of its two strings, its id and :seon.eval/by, its author, neither prose, so nothing is parsed) and the "
                "citation pass over none (cite). "
                "Not sized here: card-many forward refs and components (pulled whole, as pull gives them); "
                "citations: a-walk-pulls-each-citation-once")
     :growth :linear
     ;; entity-schema, relevant (its declared keys), texts, cite: measured 512 calls at 100 with prose? a
     ;; contracted call per string; texts reads seon.render/prose-attrs, a set derived once (2026-10-24)
     :per-n {:calls 4}
     ;; walk, the root's schema, its prose and citations, each unit attribute read forward and its ref test
     :fixed {:calls 19}
     :sizes [100 1000 4000]}))

(def ^:private cited-vars
  "Vars to cite, in a fixed order: the publics of namespaces this test loads (a citation that
  names nothing loaded is no reference, seon.ref/kept), a plain name each."
  (vec (sort-by str (for [nsym '[clojure.core clojure.string seon.render seon.print seon.ref seon.docs seon.task seon.contract seon.program seon.repl]
                          [k _] (ns-publics nsym) :when (re-matches #"[a-z][a-z0-9-]*[?!]?" (name k))]
                      (symbol (str nsym) (name k))))))

(defn cited-of
  "The input of size n: the test store's seed with a task whose plan cites n vars (one backtick
  each, cited-vars), and a run of it whose own plan is the same text; and the run's eid. Walking
  the run, its own plan pulls each var, and its task's plan cites the same n again."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any pos-int?]]}
  [n]
  (let [plan (apply str (for [v (take n cited-vars)] (str "Use `" v "`. ")))
        db (reduce d/db-with (ts/test-db)
                   [[{:seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan plan :seon.task/execution :running
                      :seon.task/branch :task-demo-f :seon.task/kind :work}]
                    (seon.task/run-start-tx "demo/f#1" "demo/f" "b1" nil {:branch :task-demo-f :model "m" :max-turns 8} 0 plan)])]
    [db (:db/id (d/entity db [:seon.run/id "demo/f#1"]))]))

(deftest a-walk-pulls-each-citation-once
  (cost/proof
    {:of 'seon.render/walk :input 'seon.render-cost-test/cited-of
     :n "distinct vars a run's plan cites, each cited again by its task's plan"
     :work (str "the run's plan parsed once (seon.ref/references), each name pulled once at its depth "
                "(seon.docs/cited: its kind, then resolve-ref, at doc depth its arglists, doc and contract); its task's plan "
                "parsed once, each name found by its address in the walk's index (one map lookup) and only its "
                "place added. No ref is pulled twice and no list of refs is scanned")
     :growth :linear
     ;; per var: seon.ref's parse and keep of its two mentions, its kind and its one pull
     ;; (citation-kind, resolve-ref, source-of), its second place an index lookup: 22 contracted
     ;; calls, measured 2,236 / 6,636 / 17,636 at 100 / 300 / 800 vars (not itemized)
     :per-n {:calls 22}
     ;; walk, its pulls' setup, each node's prose and citation pass, the run's two plans parsed
     :fixed {:calls 36}
     ;; the fixed list holds 837 vars (cited-vars)
     :sizes [100 300 800]}))

(defn paged-of
  "The input of size n: agent a1's page walk (seon.render/page) with n evaluations of a1, none in a
  turn (each its own row), and the layout's opts: the first page of rows."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any :map]]}
  [n]
  (let [[db eid] (agent-of n)]
    [(seon.render/page [db] eid) {:shown (seon.config/value :seon.config.render.list/history) :view "a1"}]))

(deftest a-page-renders-only-its-first-rows
  (cost/proof
    {:of 'seon.render.ui/page :input 'seon.render-cost-test/paged-of
     :n "evaluations on the page's list"
     :work (str "per node, how its schema shows it (shown-as: one properties lookup), its row (grouped by the "
                "turns that name it: none here) and the sort of the rows by the transaction that made them; then "
                "units for the first :seon.config.render.list/history rows only: what is rendered does not grow with "
                "the list, only its count does (seon.render.ui/more)")
     ;; the sort is n log n comparisons of one number, uncounted beside a node's two armed calls:
     ;; measured 206 / 2,006 calls and 510 KB / 5.2 MB at 100 / 1,000 (2026-10-23)
     :growth :linear
     ;; shown-as twice a node (the list, then the tiles), each armed (~353 B), and the node's row:
     ;; a map, its parts' vector, its sort key (newest, then id) and its place in the sort
     :per-n {:calls 2 :bytes 5300}
     ;; page, rows, and a slot per shown row and the list's end; what the first page's units hold
     :fixed {:calls 6 :bytes 30000}
     :sizes [100 1000 4000]}))

(defn walked-of
  "The input of size n: main and agent a1's branch with n evaluations of a1 on the branch (main
  holds none), and a1's eid: what an agent's page reads."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :any] pos-int?]]}
  [n]
  (let [[branch eid] (agent-of n)
        main (reduce d/db-with (ts/test-db) [[{:seon.root/id :root :seon.root/boot "b1"}] (seon.task/agent-tx "b1" :a1 {:status :busy})])]
    [[main branch] eid]))

(deftest a-page-walks-each-db-once
  (cost/proof
    {:of 'seon.render/page :input 'seon.render-cost-test/walked-of
     :n "evaluations of the agent, on its branch"
     :work (str "the walk on each db (main, then the branch: seon.render/walk, measured alone at 5 contracted calls "
                "and 27 KB an evaluation, its pull of the evaluation and its record), per node one index read of "
                "its own datoms for the transaction that made it (:seon.render/t, uncounted), one map entry to keep "
                "it once, and the sort of the nodes by unit and transaction: about 14 KB a node beside the walk. "
                "Every pass walks every row, though the page renders only the first: the walk pulling only the rows "
                "shown is owed (task seon.render/page-walks-only-the-rows-shown)")
     :growth :linear
     ;; measured 2026-10-23: 526 / 5,026 calls and 4.19 / 41.1 MB at 100 / 1,000 evaluations; 2026-10-24:
     ;; one call fewer an evaluation, texts asks a set of prose attributes (a-walk-reads-its-entity-and-one-hop)
     :per-n {:calls 4 :bytes 42000}
     :fixed {:calls 30 :bytes 100000}
     :sizes [100 1000 4000]}))

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
     :sizes [100 1000 10000]}))

(deftest matching-a-commit-is-flat-in-the-pages-watched
  (cost/proof
    {:of 'seon.render.proc/touched :input 'seon.render-cost-test/by-pages
     :n "ten times the pages watched (each having read 100 entities)"
     :work "the 100 datoms' keys, each one map lookup, whatever the index holds"
     :growth :constant
     ;; measured 2026-10-24: 209 / 207 / 207 KB at 10 / 100 / 1,000 pages
     :fixed {:calls 1 :bytes 212000}
     :sizes [100 1000 10000]}))

(defn debug-of
  "The input of size n: the render proc's state holding agent a1's debug page with n model turns
  held, and one turn more on the branch: each turn sent the same two evals (:seon.turn/sent), so
  a held turn's input is what the page answered for it; and the view."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :any]]}
  [n]
  (let [k (seon.task/agent-key "b1" :a1)
        sent [[:eval "e1"] [:eval "e2"]]
        db (reduce d/db-with (ts/test-db)
                   [[{:seon.root/id :root :seon.root/boot "b1"}]
                    (seon.task/agent-tx "b1" :a1 {:status :busy})
                    (into [] (mapcat #(seon.repl/record-tx {:seon.agent/key k :seon.agent/id :a1} % "a1" {:code "(+ 1 2)" :text "{:handle result/e1, :value 3}" :run "t/x#1" :call-id (str "c-" %) :call 0} nil)) ["e1" "e2"])
                    [{:db/id "run" :seon.run/id "t/x#1"}]
                    (vec (for [i (range (inc n))]
                           {:seon.turn/id (str "t/x#1/" i) :seon.turn/run "run" :seon.turn/n i :seon.turn/origin :model :seon.turn/sent sent
                            :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 1}))])
        view [:debug :a1]
        st {:rid 1 :pending {} :watch #{view} :waiting {} :kf-wanted #{} :views {} :main-db db :branches {view {:db db}} :view {:agents {}}}
        ;; the first n turns held: their units as a pass left them
        held (into {} (for [[e i] (d/q '[:find ?e ?i :where [?e :seon.turn/n ?i]] db) :when (< i n)]
                        [(str "d-" e) {:input {:turn i :run "t/x#1" :parts (seon.context/sent-parts db e)} :html "<li></li>" :container "#d-turns"}]))]
    [(assoc-in st [:views view] {:units held :skeleton "" :reads #{}}) view]))

(deftest a-new-turn-costs-one-turns-messages
  ;; task seon.context/a-turn-records-what-it-sent (C): a pass of the debug page after one new turn
  ;; reads back that turn's records alone (seon.context/sent-parts); every held turn keeps its input
  (cost/proof
    {:of 'seon.render.proc/pass-view :input 'seon.render-cost-test/debug-of
     :n "model turns the debug page already holds"
     :work (str "the new turn read back from its records (its two evals, each by its :seon.render/ai render) and "
                "rendered once: the contracted calls, flat in the turns held. A real turn also reads back its help page "
                "and its task story as of their t (seon.context/sent-parts: 2.24M ticks, 9.3 MB on context-test's "
                "fixture, mostly the story's run sections), once: a finished turn's row is held and never read again. Per held turn, no read back and no "
                "render: the page's walk lists every turn again (its query, recorded reads, the held input compared), "
                "about 34 KB a turn, until step 4 makes each turn its own component (task seon.render/a-page-is-its-entitys-view)")
     :growth :linear
     ;; measured 2026-10-24: 88 / 88 calls and 3.90 / 34.3 MB at 100 / 1,000 held turns
     :per-n {:bytes 34500}
     :fixed {:calls 88 :bytes 500000}
     ;; the full run measures 4,000 too (:seon.config.proofs/cost-max-n)
     :sizes [100 1000 4000]}))
