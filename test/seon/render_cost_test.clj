(ns seon.render-cost-test
  "The render calls' cost proof: a page whose parts nest other values' renders by reference (a
  hiccup vector headed by a function, Reagent's call), deeply, with the budget split down, is
  serialized in one pass, linear in its nodes (owner, 2026-10-23: renders as functions and results;
  root: a cost proof for the recursion). Run: bin/test seon.render-cost-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost] [seon.web.html]
            [seon.test-store :as ts] [datahike.api :as d] [seon.render.proc]))

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
                                    (for [i (range (* 20 n))] {:seon.cluster.eval/id (str "a1-1-1/" i) :seon.cluster.eval/agent "a"
                                                        :seon.cluster.eval/record {:tag :ret :form "(+ 1 2)" :shown "3"}})))])

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
