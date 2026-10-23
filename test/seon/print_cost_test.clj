(ns seon.print-cost-test
  "The one value printer's cost proofs: printing follows the value's shown size, one pass.

  Two proofs over one input. The first states the printer's own work, unarmed (its namespaces
  as defined): what a print must allocate and how long it takes. The second is the same print
  armed, as root runs it (seon.print is armed at boot): it adds exactly the contracted calls
  the printer makes per entry, which is what arming multiplies."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is]] [seon.contract :as contract :refer [defn defn-]]
            [seon.cost :as cost] [seon.print] [seon.print.select :as select] [clojure.string :as str]))

(defn entries
  "The input of size n: a map of n keyword keys to small integers, printed with a handle under a
  budget that shows 10,000 entries whole (60k tokens, ~192k characters; the reply budget shows
  ~1,600)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :map]]}
  [n] [(zipmap (map #(keyword (str "k" %)) (range n)) (range n)) {:handle 'result/h1 :budget 60000}])

(defn- priced
  "What unit operations `ops` cost by the unit table: {:ticks :ns :bytes}."
  {:malli/schema [:=> [:cat [:map-of :keyword number?]] [:map [:ticks number?] [:ns number?] [:bytes number?]]]}
  [ops]
  (reduce-kv (fn [acc op k] (-> acc (update :ticks + (* k (:ticks (cost/units op)))) (update :ns + (* k (:ns (cost/units op))))
                                (update :bytes + (* k (:bytes (cost/units op))))))
             {:ticks 0.0 :ns 0.0 :bytes 0.0} ops))

(defn- round
  "Costs as whole numbers."
  {:malli/schema [:=> [:cat [:map-of :keyword number?]] [:map-of :keyword int?]]}
  [costs]
  (into {} (map (fn [[k v]] [k (Math/round (double v))])) costs))

(def ^:private work
  (str "one pass: per entry, step the map once (a map entry), classify and spell its key and value "
       "(each a leaf: one small map that is its own print node), make the entry (one small map and its "
       "[key value] vector) and size it once, conj it into the map's members; the print tree takes the entry "
       "as it is (one conj into the map's children); emit two text ops (two small maps) and nine ops in all "
       "(nine conj!, the other seven ops shared constants); lay out one group (its end recorded: two conj!, "
       "three list cells for the group and nest stacks) and write ~12 characters (`:k123 123,` and a line "
       "break with its indentation). Nothing per entry beyond that: no second walk, no copy per assoc, "
       "no buffer per group"))

(def ^:private per-entry
  "The unit operations of one entry (`work`), by the nearest rows of seon.cost/units: a small map
  built as :map/assoc! (the table has no small-map literal row; Jolt's 3-9 key literal measures
  110-210 B, so bytes are priced a little low), a small vector as :vec/conj, a conj!, assoc! or
  list cell as :vec/conj!, a character printed as :pr-str/char."
  {:map/assoc! 5 :vec/conj 2 :vec/conj! 16 :pr-str/char 12})

(def ^:private fixed
  "What does not grow with the entries: about sixty small maps per print (the context and its
  config keys, the options, the root's row and node, its sizings, the tree's and the emitter's maps
  and the answer). A map of at most :sort-bound (64) keys is shown sorted, which adds per-entry
  work below 65 entries: the sizes start above it."
  (round (priced {:map/assoc! 60})))

(deftest printing-a-map-is-one-pass-over-its-entries
  ;; the printer's own work: its namespaces as defined, whatever an earlier test armed (and
  ;; armed again after, as that test left them)
  (let [armed? (contract/armed? #'seon.print/print-value)]
    (require 'seon.print.emit 'seon.print.select 'seon.print :reload)
    (try
      (cost/proof
        {:of 'seon.print/print-value :input 'seon.print-cost-test/entries
         :n "entries of a flat map, all shown (~13 characters each), unarmed"
         :work work
         :growth :linear
         :ops per-entry
         :fixed fixed
         :sizes [100 1000 10000]})
      (finally (when armed? (contract/instrument! '[seon.print seon.print.select seon.print.emit]))))))

(deftest armed-printing-adds-only-its-contracted-calls
  ;; as root runs it: every contracted printer function armed. Calls are exact; bytes are the
  ;; own work's plus one :fn/armed-call per call. Wall time is judged unarmed above: armed, it
  ;; is the armed call's (session J3), and the printer's contracts, which check maps, cost ~5x the
  ;; table's one-int contract per call (~340 ns against 60).
  (contract/instrument! '[seon.print seon.print.select seon.print.emit])
  (let [armed (fn [own calls] (round (assoc own :bytes (+ (:bytes own) (:bytes (priced {:fn/armed-call calls})))
                                        :calls calls)))]
    (cost/proof
      {:of 'seon.print/print-value :input 'seon.print-cost-test/entries
       :n "entries of a flat map, all shown, armed as at boot"
       :work (str work ". Armed, per entry exactly nine contracted calls: classify and scalar for the key "
                  "and the value (4), child, entry and member-indent (3), and leaf-op for the key and the "
                  "value (2)")
       :growth :linear
       :per-n (dissoc (armed (priced per-entry) 9) :ns)
       ;; the functions a print calls once or twice whatever its size (show, select, allot, the
       ;; root's node and sizing, tree, emit-both, node-ops, layout and their helpers): 71 calls
       :fixed (dissoc (armed fixed 71) :ns)
       :sizes [100 1000 10000]})))

(defn text
  "The input of size n: a text of n lines (~50 characters each, a log an evaluation printed),
  printed with a handle under the reply budget, which shows its first ~19.6k characters and
  cuts the rest."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :string :map]]}
  [n] [(str/join "\n" (for [i (range n)] (str "line " i " of a long log, as an evaluation prints it"))) {:handle 'result/h1}])

(deftest a-cut-text-reads-its-lines-once
  ;; the one string cutter counts the lines it left from the string's line breaks, found once
  ;; (seon.print.select/line-breaks): what grows with the text is that one pass, never a scan of
  ;; the rest at every sizing. What the budget shows is fixed: its characters escaped once into
  ;; the literal, and read by the sizing passes (fill-end and escaped-width, about three).
  (let [armed? (contract/armed? #'seon.print/print-value)
        shown (select/estimate-chars (:reply seon.print/budgets))]
    (require 'seon.print.emit 'seon.print.select 'seon.print :reload)
    (try
      (cost/proof
        {:of 'seon.print/print-value :input 'seon.print-cost-test/text
         :n "lines of a text cut under the reply budget, unarmed"
         :work (str "per line, one indexOf to its line break (~50 characters read) and one conj! of the break's offset; "
                    "fixed, the ~" shown " characters shown: each escaped once into the string literal and looked up by "
                    "about three sizing passes (char-escape-string)")
         :growth :linear
         :ops {:vec/conj! 1 :seq/reduce 50}
         :fixed (round (priced {:str/escape shown :map/get (* 3 shown)}))
         :sizes [1000 10000 100000]})
      (finally (when armed? (contract/instrument! '[seon.print seon.print.select seon.print.emit]))))))
