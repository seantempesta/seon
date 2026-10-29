(ns seon.program-cost-test
  "Recording a form costs the form, never the program (next-review #6; owner 2026-10-21:
  per form, through the run-ahead writer, no waiting): the facts of a redefinition on a
  branch that already holds n forms are a few index seeks, whatever n. Loading orders the
  forms in one pass over them and their references."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.cost :as cost] [seon.program :as program] [seon.merge] [seon.agent] [test-runner :as runner] [clojure.java.shell :as sh]))

(defn- acct
  {:malli/schema [:=> [:cat :int :string] :map]}
  [i body]
  {:id (str "app.pc/f" i "-" body) :src (str "(defn f" i " [x] " body ")") :ns 'app.pc :ns-form? false
   :defs [{:var (symbol "app.pc" (str "f" i)) :kind :fn}] :declares #{} :methods [] :impls []
   :refs (if (pos? i) #{(symbol "app.pc" (str "f" (dec i)))} #{}) :requires #{} :banned #{} :inline-defs #{}})

(defn branch-of
  "record-tx's arguments: a branch db holding n forms (app.pc/f0..fn-1, each calling the one
  before), and the account of redefining the middle one."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map]]}
  [n]
  (let [db (ts/test-db)
        db (d/db-with db (vec (mapcat #(program/account-tx db (acct % "x") %) (range n))))]
    [db (acct (quot n 2) "(inc x)")]))

(defn probed-of
  "d/q's arguments: a query reaching one form through its var's name, then two of that form's
  attributes (a group on ?f probed by the one ?f the first group binds), on a branch db holding n
  forms (branch-of's)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any :seon.host/db :symbol]]}
  [n]
  ['[:find ?src ?o :in $ ?n :where [?v :var/name ?n] [?v :var/form ?f] [?f :form/src ?src] [?f :form/order ?o]]
   (first (branch-of n)) (symbol "app.pc" (str "f" (quot n 2)))])

(deftest a-probed-entity-is-sought-not-scanned
  ;; task datahike.query/a-probed-entity-is-sought-not-scanned
  (cost/proof
    {:of 'datahike.api/q :input 'seon.program-cost-test/probed-of
     :n "forms the db holds"
     :work (str "one AVET seek for the var, its :var/form, then one EAVT seek per attribute of the one form "
                "it names: log n each; nothing scans an attribute's datoms (the group on ?f scanned all of "
                ":form/src before)")
     :growth :log
     ;; measured 2026-10-23: 47.4k ticks / 208 KB at 100 forms, 49.2k / 211 KB at 1,000, 54.7k /
     ;; 218 KB at 10,000; on Datahike before jolt/in-scalar 220k / 793 KB, 507k / 1.7 MB, 3.24M /
     ;; 10.2 MB (the :form/src scan)
     :per-n {:ticks 1150.0 :bytes 1400.0}
     :fixed {:ticks 41000.0 :bytes 200000.0}
     :sizes [100 300 1000 10000]}))

(deftest recording-a-form-is-flat-in-the-program
  (cost/proof
    {:of 'seon.program/record-tx :input 'seon.program-cost-test/branch-of
     :n "forms the branch's db already holds"
     :work (str "the form only: one AVET seek for the form's id, one for each definition it makes, the "
                "entity read of each one's current form and of that form's :form/defines (to see it is "
                "superseded), and one map per fact; nothing scans the program. Six seeks or entity reads, "
                "each one log n index seek (seon.task-cost-test: ~3 us and 1.3 KB)")
     :growth :log
     :per-n {:ns 900 :bytes 300}
     ;; contracted calls: record-tx, account-tx, eid x2, form-of, the tempid makers x6, method/impl
     ;; keys none here: 12, whatever n
     :fixed {:ns 18000 :bytes 8000 :calls 12}
     :sizes [100 300 1000 10000]
     ;; measured 2026-10-21 (source mode, load 5): 105 -> 114 us and 121 -> 138 KB from 100 to
     ;; 10k forms, on the whole process's count. Re-measured 2026-10-23 on the proof's own thread
     ;; (18b0774: per-thread bytes, thread CPU time; release runtime, Chez 80e4b9f, load 17, as
     ;; bin/prove runs): 161 -> 183 KB, the same in every run (six AVET seeks, ~21 KB each as
     ;; seon.task-cost-test's one seek, and the entity reads), and 192 -> 213 us, which machine
     ;; load moves ~1.3x (158 us at load 10). The record a ratchet compares with is this measure's
     :overrun {:task "seon.db/avet-seek-allocates" :measured {:ns 213400.0 :bytes 183100.0}}}))

(defn program-of
  "load-order's argument: the n live forms of app.pc (the test store's example program is
  beside them)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :map]]]}
  [n]
  (let [[db] (branch-of n)] [(filterv #(= 'app.pc (:ns %)) (program/live-forms db))]))

(deftest load-order-is-one-pass-over-the-forms
  (cost/proof
    {:of 'seon.program/load-order :input 'seon.program-cost-test/program-of
     :n "live forms"
     :work (str "one pass to index each form by id and each var by its earliest interning form, one per "
                "namespace for its dependencies, and a depth-first visit of each form and reference edge, "
                "the forms sorted by order once (n log n compares)")
     :growth :n-log-n
     :ops {:map/assoc 3 :map/get 4 :sort/compare 1 :vec/conj 2 :set/conj! 2}
     ;; itself, and the depth-first sort once for the namespaces and once per namespace (one here)
     :fixed {:calls 3}
     :sizes [100 300 1000 10000]}))

(def ^:private divergence-sizes "The sizes divergence's proof measures." [100 300 1000 10000])

(def ^:private process-token
  "This test process's own: two shards' proofs (divergence's here, diff-plan's in
  seon.program-diff-test) built and removed one store at one path while both ran, and one read
  the other's branch (\"Branch already exists\")."
  (str (random-uuid)))

(defn- on-disk-path
  "Where divergence's proof keeps its store of size n, in this process's own directory."
  {:malli/schema [:=> [:cat pos-int?] :string]}
  [n] (str (System/getProperty "user.dir") "/target/tmp/divergence-" process-token "-" n))

(defn on-disk-diverged-of
  "A branch diff's arguments (base, head) on a store on disk of its own (the last resort: Datahike's
  datoms-diff skips the index subtrees two branches share once they are stored, and the test
  store's in-memory branches hold none, so there both answers grow with the program): main
  holding n forms (branch-of's), and a branch of it that redefines one. The store is made
  afresh at on-disk-path; the proof's end releases and removes it."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db]]}
  [n]
  (let [path (on-disk-path n)
        _ (sh/sh "rm" "-rf" path)
        {:keys [cfg conn]} (pdb/open! :lmdb path 0 :db)
        _ (d/transact conn {:tx-data (vec (mapcat #(program/account-tx @conn (acct % "x") %) (range n)))})
        base @conn
        _ (d/branch! conn :db :branch)
        bc (d/connect (assoc cfg :branch :branch))]
    (d/transact bc {:tx-data (program/record-tx @bc (acct (quot n 2) "(inc x)"))})
    [base @bc]))

(defn- release-stores!
  "Each store the divergence proofs made, released whatever holds it (release-all) and removed,
  and the pair branch-changes' proof left held."
  []
  (swap! seon.agent/state dissoc :branch-changes)
  (doseq [n divergence-sizes :let [path (on-disk-path n)] :when (pdb/store? path)]
    (doseq [b [:db :branch]] (d/release (d/connect (assoc (pdb/store-cfg :lmdb path) :branch b)) true))
    (sh/sh "rm" "-rf" path)))

(defn on-disk-changes-of
  "seon.merge/delta-changes' arguments on on-disk-diverged-of's stores: main holding n forms, its
  branch redefining one, no declared data."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db [:set :keyword]]]}
  [n] (conj (on-disk-diverged-of n) #{}))

(deftest a-turns-diff-reads-what-the-branch-changed
  ;; one diff a turn (root's audit, 2026-10-27: a turn read its base-to-head diff 4-6 times,
  ;; divergence three or four times beside delta-changes, ~2 MB of repeats): the turn reads
  ;; delta-changes once (seon.agent/branch-changes) and every reader projects it
  (try
    (cost/proof
      {:of 'seon.merge/delta-changes :input 'seon.program-cost-test/on-disk-changes-of
       :n "forms main and the branch hold (the branch redefines one)"
       :work (str "the datoms that differ (d/datoms-diff over :eavt, skipping the stored subtrees both share: "
                  "one index path per changed entity), the keys they bear and those keys' content on both sides; "
                  "nothing reads the forms they share")
       :growth :constant
       ;; measured 2026-10-27 on LMDB with the one :eavt diff: 437k ticks / 1.26 MB at 100 forms,
       ;; 462k / 1.37 MB at 300, 547k / 1.61 MB at 1,000 (629k / 1.83 MB at 10,000 in an earlier layout);
       ;; 56 contracted calls each. With one :aevt range per program attribute instead (task
       ;; seon.merge/touched-keys-skips-platform-records): 1.06M ticks / 3.97 MB / 59 calls at 100, the ~44
       ;; descents (60-90 KB each) being the fixed part; what that buys is below. The rise from 100 to 1,000 is the stored index gaining a level (64
       ;; datoms a node), which three sizes cannot fit as a log: it is bounded, a program scan (1 MB
       ;; per 1,000 forms) would not be. A branch that wrote besides the program (evals, turns,
       ;; messages) and main's embeddings since the fork are never read: the ranges skip them. What it
       ;; replaced per turn: this, plus divergence three or four times (453-678 KB each), so a turn's
       ;; diff reads went from ~2.5-3.3 MB to one of these
       :per-n {}
       :fixed {:ticks 1400000.0 :bytes 5000000.0 :calls 59}
       :sizes divergence-sizes})
    (finally (release-stores!))))

(deftest a-turns-held-diff-is-a-lookup
  ;; the proof above builds its stores at on-disk-path n and releases them; this one builds its own,
  ;; one set of stores at a time (a second build at a path a proof still holds is "Branch already exists")
  (try
    (cost/proof
      {:of 'seon.agent/branch-changes :input 'seon.program-cost-test/on-disk-diverged-of
       :n "forms main and the branch hold"
       :work "after a turn's first read, a lookup of the pair it holds: one call, nothing read again"
       :growth :constant
       ;; measured 2026-10-27: 401 ticks, 1,968 bytes, 1 call at every size
       :per-n {}
       :fixed {:ticks 1000.0 :bytes 4000.0 :calls 1}
       :sizes divergence-sizes})
    (finally (release-stores!))))

(defn fan
  "closure's arguments of size n: one root whose neighbors are n leaves (the frontier n wide)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :int] :seon.host/fn]]}
  [n]
  (let [leaves (vec (range 1 (inc n)))]
    [[0] (fn [x] (if (zero? x) leaves []))]))

(deftest a-closure-is-one-visit-per-node-and-edge
  (cost/proof
    {:of 'seon.program/closure :input 'seon.program-cost-test/fan
     :n "nodes reached (a root and its n leaves: the widest frontier)"
     :work (str "each node taken from the queue once and each edge followed once: per node reached, its "
                "conj onto the queue vector and into the seen set and one distinct's set conj (820 B at "
                "100 nodes, 1,010 at 1,000, measured 2026-10-23, the hash set a level deeper: the "
                "persistent set conj has no row in seon.cost/units), and a node "
                "with no neighbors costs its visit alone; nothing per node already queued (the frontier "
                "was copied on every visit before: 446 KB at 100 leaves, quadratic)")
     :growth :linear
     ;; ticks measured 2026-10-23 (seon.cost's once): 12.9k at 100, 153k at 1,000
     :per-n {:ticks 160 :bytes 1100 :calls 0}
     :fixed {:ticks 2000 :bytes 2000 :calls 1}
     :sizes [100 300 1000 10000]}))

(defn knotted
  "components' arguments of size n: n nodes, each pointing to the next and to the one five back
  when it is a multiple of seven (cycles of a few nodes along a chain)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :int] :map]]}
  [n]
  [(vec (range n)) (into {} (for [i (range n)] [i (cond-> (if (< (inc i) n) [(inc i)] []) (and (pos? i) (zero? (mod i 7))) (conj (max 0 (- i 5))))]))])

(deftest the-strongly-connected-components-are-one-visit-per-node-and-edge
  (cost/proof
    {:of 'seon.program/components :input 'seon.program-cost-test/knotted
     :n "nodes (each with one or two edges)"
     :work (str "Tarjan's: each node entered once (an index, a low link, a push), each edge followed once, "
                "each node popped into its component once. Measured 2026-10-23: 145 us / 214 KB at 100 "
                "nodes, 1.6 ms / 2.2 MB at 1,000, 21 ms / 21.7 MB at 10,000 (~2 us, ~2.2 KB a node)")
     :growth :linear
     ;; ticks measured 2026-10-23 (seon.cost's once): 54k at 100, 559k at 1,000
     :per-n {:ticks 560 :bytes 2000 :calls 0}
     :fixed {:ticks 2000 :bytes 2000 :calls 1}
     :sizes [100 300 1000 10000]}))

(defn keyed-chain
  "reach-index's arguments: a branch holding n forms (app.pc/f0..fn-1, each calling the one
  before) and a key for every tenth, each reaching every form below it: n/10 keys whose
  walks would share most of the program."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map :map]]}
  [n]
  (let [[db] (branch-of n)]
    [db {:env "e" :tree "t" :config {}} (into {} (for [i (range 0 n 10)] [(str "k" i) [(symbol "app.pc" (str "f" i))]]))]))

(deftest the-reach-walk-is-one-pass-whatever-the-keys
  (cost/proof
    {:of 'seon.test-store/reach-index :input 'seon.program-cost-test/keyed-chain
     :n "forms (and n/10 keys, each reaching the forms below it)"
     :work (str "the live forms read and digested once, each form's neighbours once, the components once "
                "(Tarjan's) and each one's Merkle digest once, sinks first; then per key its starting "
                "components' digests: linear in the forms, never forms x keys (a walk per key was "
                "quadratic here); a form is read as reach-rows' four facts, none of the rest. Measured: "
                "each attribute read once over its index (by-entity), no entity lookup per fact and no hash of the "
                "text (a form's own digest is its :form/id): 41 MB at 100, 128 MB at 1,000 (was 67 and 205 MB); "
                "the fixed part is the test store's example program")
     :growth :linear
     ;; ticks measured (seon.cost's once): 7.1M at 100, 21.3M at 1,000 (was 14.7M and 44.4M, 33k a form)
     ;; 2026-10-27, reach-step builds from nothing in bulk (transient indexes) and reads a var's contract and
     ;; kind in one pass (var-facts, not an entity per var): 4.60M ticks / 36.2 MB at 100, 6.79M / 54.3 MB at 300,
     ;; 14.57M / 118.4 MB at 1,000 (was 7.43M / 42.5 MB, 10.77M / 63.1 MB, 22.63M / 135.9 MB); on Seon's program
     ;; (3,540 forms) 160M ticks / 969 MB -> 136M / 837 MB
     :per-n {:ticks 11500 :bytes 93000}
     :fixed {:ticks 3600000 :bytes 28000000}
     :sizes [100 300 1000 10000]}))

(defn changed-top
  "reach-update's arguments: the reach graph of a branch holding n forms (app.pc/f0..fn-1,
  each calling the one before) and that branch with the last redefined, which nothing reaches."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db]]}
  [n]
  (let [[db] (branch-of n)
        g (program/reach-graph db nil)]
    [g (d/db-with db (program/record-tx db (acct (dec n) "(dec x)")))]))

(deftest a-change-re-folds-only-what-reaches-it
  ;; task seon.program/reach-index-reads-each-attribute-once: the proof's digest does not reach reach-update (named by a quoted symbol)
  (cost/proof
    {:of 'seon.program/reach-update :input 'seon.program-cost-test/changed-top
     :n "forms the graph already holds"
     :work (str "four index scans of the forms and their definitions' pointers (live-ids, no entity read), "
                "then the changed form only: its row (reach-rows, a seek per attribute), its var's facts, its neighbours, and the components "
                "of it and of what reaches it (nothing, here) re-folded; every other form's neighbours and "
                "component digest kept. Measured 2026-10-23: 2.1 ms / 2.7 MB at 100, 3.9 ms / 6.5 MB at 1,000, "
                "11.5 ms / 16 MB at 3,000, one form re-folded each time; the per-form part is the scan "
                "(~3 us, ~5 KB a form), the re-fold is the change's")
     :growth :linear
     ;; ticks measured (seon.cost's once): 0.50M at 100 and 1.24M at 1,000 on Jolt e0351af8;
     ;; 427k and 1.07M on 0d39edc3, whose protocol calls on a record stopped reading every field.
     ;; The fixed part fell with it (~714 ticks a form, so ~356k fixed): the old 420k left 7k
     ;; above it at 100 and the growth fit read n^1.96. calls 16
     :per-n {:ticks 850 :bytes 5000 :calls 0}
     ;; 18: reach-rows' two contracted calls the config and pin rules added (pin-rules once, config-reads
     ;; per added form), 2026-10-27; 19: computed-config-reader? once per added form (audit cuts 6 and 11)
     :fixed {:ticks 350000 :bytes 4000000 :calls 19}
     :sizes [100 300 1000 10000]}))

(defn- calls-form
  "A defn of m whose body is n steps, each (assoc m :k<i> (str \"s<i>\" (:name m))): a
  program's usual mix of locals, var calls, keywords and strings; every tenth step also names
  a var as quoted data ('[app.acct/g :k<i>], as a proc or a render is named), which is walked."
  {:malli/schema [:=> [:cat pos-int?] :any]}
  [n]
  (apply list 'defn 'g '[m]
         (for [i (range n) :let [k (keyword (str "k" i))]]
           (if (zero? (mod i 10))
             (list 'assoc 'm k (list 'quote ['app.acct/g k]))
             (list 'assoc 'm k (list 'str (str "s" i) (list :name 'm)))))))

(defn- app-acct
  "Namespace app.acct, referring clojure.core, as a file's ns form would set it up."
  {:malli/schema [:=> [:cat] :seon.host/namespace]}
  [] (let [nsx (program/ensure-ns! 'app.acct)] (binding [*ns* nsx] (refer-clojure)) nsx))

(defn calls-node-of
  "unit-account's argument: the analyzed node of calls-form n in app.acct."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [nsx (app-acct)]
    [(binding [*ns* nsx] (jolt.analyzer/analyze (jolt.host/ctx-for-ns "app.acct") (calls-form n)))]))

(deftest a-scalar-constant-costs-no-walk
  ;; task seon.program/an-import-reads-each-form-once
  (cost/proof
    {:of 'seon.program/unit-account :input 'seon.program-cost-test/calls-node-of
     :n "steps in the body (calls-form)"
     :work (str "one visit per IR node (two invokes, their vars, a local, a keyword invoke, constants): each "
                "var's ref conj'd; a scalar constant names no var, so it is not walked, and a quoted "
                "collection is (quoted-names walks every constant: tree-seq, a set, its contract, 3.5 KB each when measured)")
     :growth :linear
     ;; measured 2026-10-23 (n 100 -> 10,000): 5.92 KB, 745 ticks and 9.6 contracted calls a step (vsym,
     ;; invoked, datum: 354 B each armed, seon.cost/units :fn/armed-call); 10.9 KB, 1,157 ticks and 12.4
     ;; calls before
     :per-n {:ticks 750.0 :bytes 5950.0 :calls 9.6}
     :fixed {:ticks 2000.0 :bytes 12000.0 :calls 7.0}
     :sizes [100 300 1000 10000]}))

(defn- keyword-form
  "calls-form with each step's key qualified (assoc m :app.acct/k<i> ...), as a program's facts
  are written: every step names one attribute; every tenth also names a key and a var as quoted
  data ('[:app.acct/k<i> app.acct/g], a render or a proc declared by name), which is walked once."
  {:malli/schema [:=> [:cat pos-int?] :any]}
  [n]
  (apply list 'defn 'g '[m]
         (for [i (range n) :let [k (keyword "app.acct" (str "k" i))]]
           (if (zero? (mod i 10))
             (list 'assoc 'm k (list 'quote [k 'app.acct/g]))
             (list 'assoc 'm k (list 'str (str "s" i) (list :name 'm)))))))

(defn keyword-node-of
  "unit-account's argument: the analyzed node of keyword-form n in app.acct."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [nsx (app-acct)]
    [(binding [*ns* nsx] (jolt.analyzer/analyze (jolt.host/ctx-for-ns "app.acct") (keyword-form n)))]))

(deftest a-keyword-a-form-names-costs-one-conj
  ;; root's gate (approved), :form/keywords: the analyzer records each qualified keyword its
  ;; constants name with one set conj, no walk; the rest of the step as a-scalar-constant-costs-no-walk
  (cost/proof
    {:of 'seon.program/unit-account :input 'seon.program-cost-test/keyword-node-of
     :n "steps in the body, each naming one qualified keyword (keyword-form)"
     :work (str "one visit per IR node, as a-scalar-constant-costs-no-walk; each qualified keyword "
                "constant one conj into the form's :keywords, not walked; a quoted collection walked "
                "for its names (quoted-names) and once more for its keywords")
     :growth :linear
     ;; measured 2026-10-25 (n 100 -> 10,000): 755 ticks, 3.13 KB and 9.6 contracted calls a step;
     ;; 74.9k ticks, 278 KB and 967 calls at 100 (the quoted-collection filter as a contracted
     ;; predicate was 989: one call an element)
     :per-n {:ticks 760.0 :bytes 3150.0 :calls 9.6}
     :fixed {:ticks 2000.0 :bytes 12000.0 :calls 7.0}
     :sizes [100 300 1000 10000]}))

(defn account-of
  "account's argument: calls-form n in app.acct as read, its text and its unit's account."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [[node] (calls-node-of n) form (calls-form n)]
    [{:units [(program/unit-account node)] :src (pr-str form) :form form :ns 'app.acct :ns-after 'app.acct :order 0 :k 0}]))

(deftest an-account-reads-its-form-once
  ;; task seon.program/an-import-reads-each-form-once
  (cost/proof
    {:of 'seon.program/account :input 'seon.program-cost-test/account-of
     :n "steps in the body (calls-form)"
     :work (str "the form as read walked once for the macros it calls (macro-uses: tree-seq, 1.1 KB a "
                "node on Jolt), and its text hashed once (form-id); the text is never read again (it was "
                "read a second time, 4.6 KB a step)")
     :growth :linear
     ;; measured 2026-10-23 (n 1,000 -> 10,000): 13.3 KB and 1.4k ticks a step, 7 contracted calls; 17.9 KB
     ;; and 2.4k ticks before
     :per-n {:ticks 1400.0 :bytes 13300.0}
     :fixed {:ticks 20000.0 :bytes 60000.0 :calls 7.0}
     :sizes [100 300 1000 10000]}))

(defn literals-of
  "eval's argument: a defn whose body is a vector of n fn literals, each closing over the
  defn's argument (an agent's evaluation holds a few; the size shows the cost per literal)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any]]}
  [n] [(list 'defn 'literals-probe '[x] (vec (for [i (range n)] (list 'fn '[y] (list '+ 'x 'y i)))))])

(deftest a-defn-compiles-within-its-estimate
  ;; task jolt.backend/image-registration-is-opt-in: Seon writes no state image, so its compiles
  ;; register no fn literal's source for one (bin/env's JOLT_COMPILER_OPTIONS {:image-fn-forms
  ;; false}); registering rendered each literal's source, read it back, and wrapped it in a maker
  (is (false? (:image-fn-forms *compiler-options*))
      "this process compiles without image registration (bin/env's JOLT_COMPILER_OPTIONS): else the bytes below are registering's")
  (cost/proof
    {:of 'clojure.core/eval :input 'seon.program-cost-test/literals-of
     :n "fn literals in the defn"
     :work (str "per literal: its analysis, its emitted lambda and Chez's compile of it; no source "
                "rendered, read back or registered, no maker")
     :growth :linear
     ;; measured 2026-10-23 (Jolt jolt/image-fn-forms-opt-in): 4.59 MB, 43.3 MB, 474 MB at 10, 100,
     ;; 1,000 literals, ~478 KB and ~52k ticks a literal; registering 11.1 MB, 124 MB, 1,239 MB
     ;; (~1.24 MB and ~172k ticks a literal)
     :per-n {:ticks 55000.0 :bytes 480000.0}
     :fixed {:ticks 100000.0 :bytes 100000.0}
     :sizes [10 100 1000]}))

(defn digested-of
  "program-digest's argument: a branch db holding n forms (branch-of's)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n] [(first (branch-of n))])

(deftest the-program-digest-is-one-read-of-the-program
  (cost/proof
    {:of 'seon.program/program-digest :input 'seon.program-cost-test/digested-of
     :n "forms the db holds"
     :work (str "one read of the :form/id, :schema/form and :ns/tail datoms in index order (no query, no sort), "
                "each entity id (and value) appended once, then one digest of the text")
     :growth :linear
     ;; measured 2026-10-24: 116k ticks / 580 KB at 100 forms, 325k / 1.62 MB at 1,000 (the fixed part is
     ;; the test db's schema entries); 0.79M / 5.0 MB on the standing root's main (2699 forms, 660 entries)
     :per-n {:ticks 235.0 :bytes 1160.0}
     :fixed {:ticks 93000.0 :bytes 465000.0}
     :sizes [100 300 1000 10000]}))

(defn meaning-input
  "The cost proof's input at n: a db of n vars each with a docstring, and tx-data redefining each with another."
  [n] (let [db0 (ts/test-db)
            db (d/db-with db0 (vec (for [i (range n)] {:var/name (symbol "app" (str "f" i)) :var/doc (str "Doc " i ".")})))]
        [db (vec (for [i (range n)] {:var/name (symbol "app" (str "f" i)) :var/doc (str "Doc " i ", changed.")}))]))

(deftest meaning-changes-cost-what-the-eval-wrote
  (cost/proof {:of 'seon.program/meaning-changes :input 'seon.program-cost-test/meaning-input
               :n "vars the eval redefined, each with a changed docstring"
               :work "the tx-data walked once, then per var one entity read and one index seek of its citations"
               :growth :linear
               :per-n {:bytes 60000 :calls 1}
               :fixed {:bytes 200000 :calls 1}
               :sizes [10 100 300]}))


(defn stored-of
  "reach-graph-of's arguments: a branch db of n forms (app.pc/f0..fn-1, each calling the one before)
  with its last form redefined, no data-refs, and a store directory already holding the graph of the
  branch before the redefinition."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :nil :string]]}
  [n]
  (let [[db] (branch-of n)
        dir (str (System/getProperty "user.dir") "/target/tmp/stored-reach-" (random-uuid))]
    (.mkdirs (java.io.File. dir))
    (runner/reach-graph-of db nil dir)
    [(d/db-with db (program/record-tx db (acct (dec n) "(dec x)"))) nil dir]))

(deftest a-plan-reads-the-stored-reach-and-pays-for-what-moved
  (cost/proof
    {:of 'test-runner/reach-graph-of :input 'seon.program-cost-test/stored-of
     :n "forms the stored graph holds (one redefined since)"
     :work (str "the stored graph decoded (jfasl: one pass over its bytes), then reach-update: the live forms "
                "scanned (live-ids) and only the redefined form's components re-folded; never reach-graph's "
                "walk of every form (41k ticks and 260 KB a form against this). Linear in the graph's size "
                "(decode and scan), not flat: the diff of the two dbs by datoms would make the scan flat")
     :growth :linear
     ;; measured 2026-10-27: 752k ticks / 4.29 MB at 100, 1.12M / 6.55 MB at 300, 2.36M / 14.4 MB at 1,000, 10 calls;
     ;; reach-graph on the same dbs: 7.4M / 42 MB, 10.7M / 62 MB, 22.2M / 133 MB (9x more at 1,000). On Seon's
     ;; program (3,540 forms) a stored graph decodes in 23M ticks against 146M to build it.
     :per-n {:ticks 1900 :bytes 11500 :calls 0}
     :fixed {:ticks 580000 :bytes 3200000 :calls 10}
     :sizes [100 300 1000]}))
