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
         :sizes [100 300 1000 10000]})
      (finally (when armed? (contract/instrument! '[seon.print seon.print.select seon.print.emit]))))))

(deftest armed-printing-adds-only-its-contracted-calls
  ;; as root runs it: every contracted printer function armed. Calls are exact; bytes are the
  ;; own work's plus what each call's checks allocate. An armed call takes no try (task
  ;; seon.contract/an-armed-call-costs-its-checks: :fn/armed-call's one-int row is ~0 B), but the
  ;; printer's contracts check maps, and a map's validator allocates: measured 2026-10-23, armed
  ;; minus unarmed, 62 B a call per entry (3,020 against 2,460 B an entry, nine calls) and ~170 B
  ;; a call of the print's fixed part (19.8 against 7.8 KB at 100, 71 calls). Wall time is judged
  ;; unarmed above.
  (contract/instrument! '[seon.print seon.print.select seon.print.emit])
  (let [armed (fn [own calls per-call] (round (assoc own :bytes (+ (:bytes own) (* calls per-call)) :calls calls)))]
    (cost/proof
      {:of 'seon.print/print-value :input 'seon.print-cost-test/entries
       :n "entries of a flat map, all shown, armed as at boot"
       :work (str work ". Armed, per entry exactly nine contracted calls: classify and scalar for the key "
                  "and the value (4), child, entry and member-indent (3), and leaf-op for the key and the "
                  "value (2)")
       :growth :linear
       :per-n (dissoc (armed (priced per-entry) 9 62) :ns)
       ;; the functions a print calls once or twice whatever its size (show, select, allot, the
       ;; root's node and sizing, tree, emit-both, node-ops, layout and their helpers): 79 calls
       ;; (71 until an entry of two leaves was charged its flat width, 2026-10-25: the least form,
       ;; seon.print.select/minimal, then checks one more member whole, seven calls; and estimate
       ;; became chars-tokens over a text's length, its rule shared with seon.context/messages-tokens:
       ;; one call more; 80 with seon.print.select/lifted-namespace, the one decision of a map's
       ;; #:ns{ lift, made once per map over its keys with no call per key)
       :fixed (dissoc (armed fixed 80 170) :ns)
       :sizes [100 300 1000 10000]})))

(def ^:private shown-budget
  "The budget the cut inputs print under, stated here so a proof's input does not move with the
  reply's config key (6,144 tokens, the reply's until 2026-10-25)."
  6144)

(defn text
  "The input of size n: a text of n lines (~50 characters each, a log an evaluation printed),
  printed with a handle under shown-budget, which shows its first ~19.6k characters and cuts
  the rest."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :string :map]]}
  [n] [(str/join "\n" (for [i (range n)] (str "line " i " of a long log, as an evaluation prints it"))) {:handle 'result/h1 :budget shown-budget}])

(deftest a-cut-text-reads-its-lines-once
  ;; the one string cutter counts the lines it left from the string's line breaks, found once
  ;; (seon.print.select/line-breaks): what grows with the text is that one pass, never a scan of
  ;; the rest at every sizing. What the budget shows is fixed: its characters escaped once into
  ;; the literal, and read by the sizing passes (fill-end and escaped-width, about three).
  (let [armed? (contract/armed? #'seon.print/print-value)
        shown (select/estimate-chars shown-budget)]
    (require 'seon.print.emit 'seon.print.select 'seon.print :reload)
    (try
      (cost/proof
        {:of 'seon.print/print-value :input 'seon.print-cost-test/text
         :n "lines of a text cut under 6,144 tokens, unarmed"
         :work (str "per line, one indexOf to its line break (~50 characters read) and one conj! of the break's offset; "
                    "fixed, the ~" shown " characters shown: each escaped once into the string literal and looked up by "
                    "about three sizing passes (char-escape-string)")
         :growth :linear
         :ops {:vec/conj! 1 :seq/reduce 50}
         :fixed (round (priced {:str/escape shown :map/get (* 3 shown)}))
         :sizes [250 500 1000 10000 100000]})
      (finally (when armed? (contract/instrument! '[seon.print seon.print.select seon.print.emit]))))))

(defn rows
  "The input of size n: n problem rows (7 keys each, as my.root/problems' :stale-section rows),
  printed with a handle under shown-budget, which shows the first 73 whole and cuts the rest."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :map]]}
  [n] [{:rows (vec (for [i (range n)] (array-map :section (keyword "render" (str "s" i)) :page "docs/render.md"
                                                  :class :stale-section :since 1761360000000 :cited 'seon.print/print-value
                                                  :lead "A section whose lead cites a var that moved." :n i)))}
       {:handle 'result/h1 :budget shown-budget}])

(deftest printing-rows-costs-what-it-shows
  ;; a printed value costs what it shows, not what it hides: the budget fixes the rows shown whole
  ;; (73 of 7 keys, then one cut), and no row past the cut is read (seon.print.select/fill-rows).
  ;; Measured 2026-10-25, unarmed: 13.2 MB and 3.39M ticks at 100, 1,000 and 10,000 rows; main's
  ;; breadth path on the same input cost 16.0 MB and 4.28M ticks at 100 rows, growing with them,
  ;; for 493 cuts and no row shown. The fixed part is the selection trying each level whole
  ;; (the root map, its :rows entry, the vector, each row: about four walks of what shows).
  (let [armed? (contract/armed? #'seon.print/print-value)]
    (require 'seon.print.emit 'seon.print.select 'seon.print :reload)
    (try
      (cost/proof
        {:of 'seon.print/print-value :input 'seon.print-cost-test/rows
         :n "problem rows under 6,144 tokens, unarmed"
         :work (str "nothing per row past what the budget could show (alike? reads member kinds only when no row fits whole): the rows the budget shows whole are fixed by it, about four whole walks of "
                    "them (one a level: the root, its :rows entry, the vector, each row), and the first row "
                    "that does not fit is sized until its charge passes the room; no row past the cut is read")
         :growth :constant
         :fixed {:ticks 3.4e6 :ns 7.8e6 :bytes 13.2e6}
         :sizes [100 300 1000 10000]})
      (finally (when armed? (contract/instrument! '[seon.print seon.print.select seon.print.emit]))))))

(defn code
  "The input of size n: n lines of a test's source (~50 characters each) as code
  (seon.print/code), printed with a handle under shown-budget, which shows its first ~19.6k
  characters verbatim and cuts the rest."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any :map]]}
  [n] [{:source (seon.print/code (str/join "\n" (for [i (range n)] (str "    (is (= " i " (count (rows db))) \"row " i " is kept\")"))))}
       {:handle 'result/h1 :budget shown-budget}])

(deftest printing-code-costs-what-it-shows
  ;; code (seon.print/code) prints verbatim, cut by the one string cutter: what grows with it
  ;; is the pass that finds its line breaks, as a text's (a-cut-text-reads-its-lines-once), once
  ;; for each time the selection makes the map's child (three);
  ;; what shows is fixed by the budget, each shown character sized about three times (fill-end,
  ;; code-width's line count) and re-indented once (emit/code-text)
  (let [armed? (contract/armed? #'seon.print/print-value)
        shown (select/estimate-chars shown-budget)]
    (require 'seon.print.emit 'seon.print.select 'seon.print :reload)
    (try
      (cost/proof
        {:of 'seon.print/print-value :input 'seon.print-cost-test/code
         :n "lines of code cut under 6,144 tokens, unarmed"
         :work (str "per line, three times (the map's child is made three times, whole, at its least and filled, "
                    "and each finds its line breaks: seon.print.select/node-of), one indexOf to its line break (~50 "
                    "characters read) and one conj! of the break's offset; "
                    "fixed, the ~" shown " characters shown, read by the sizing passes with no escaping and copied once "
                    "into the text: measured 2026-10-25 at 1,000 lines, 576 KB and 350k ticks in all (a string's escaping, "
                    "a-cut-text-reads-its-lines-once, prices the same characters at 13.5 MB)")
         :growth :linear
         :ops {:vec/conj! 3 :seq/reduce 150}
         :fixed {:ticks 2.5e5 :ns 5.0e5 :bytes 4.5e5}
         :sizes [250 500 1000 10000 100000]})
      (finally (when armed? (contract/instrument! '[seon.print seon.print.select seon.print.emit]))))))
