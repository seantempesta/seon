(ns seon.print-cost-test
  "The one value printer's cost proofs: printing follows the value's shown size, one pass, at sizes
  of at most 30 (the growth is read off three small sizes, never a huge value). Every proof is the
  print armed, as root runs it (seon.print is armed at boot, and each test shard arms it before its
  tests): arming adds the contracted calls the printer makes per entry to its own work, priced in bytes.
  No proof reloads the printer: a reload to measure it unarmed cost 600 ms a proof and made each a
  process test, for a state nothing runs in."
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

(defn- armed
  "Costs `own` plus the `calls` contracted calls a print makes, each at `per-call` bytes and the armed-call row's ticks."
  {:malli/schema [:=> [:cat [:map-of :keyword number?] pos-int? number?] [:map-of :keyword int?]]}
  [own calls per-call]
  (round (assoc own :bytes (+ (:bytes own) (* calls per-call))
                :ticks (+ (:ticks own) (* calls (:ticks (cost/units :fn/armed-call)))))))

(defn- armed!
  "The printer's contracted vars armed, as at boot (arming an armed var changes nothing)."
  []
  (contract/instrument! '[seon.print seon.print.select seon.print.emit]))

(deftest printing-a-map-is-one-pass-over-its-entries
  ;; as root runs it: every contracted printer function armed. Bytes are the own work's plus what each
  ;; contracted call's checks allocate (the calls price the bytes; they are no bound of their own: the owner, 2026-11-15). An armed call takes no try (task
  ;; seon.contract/an-armed-call-costs-its-checks: :fn/armed-call's one-int row is ~0 B), but the
  ;; printer's contracts check maps, and a map's validator allocates: measured 2026-10-23, armed
  ;; minus unarmed, 62 B a call per entry (3,020 against 2,460 B an entry, nine calls) and ~170 B
  ;; a call of the print's fixed part (19.8 against 7.8 KB at 100, 71 calls). Wall time is judged
  ;; unarmed above. The printer is left as this test found it: an armed seon.print stayed armed for
  ;; every test after it in the process, and a debug page's pass printed through it with 189 more
  ;; contracted calls (the debug page's pass of its rows, measured 2026-10-23: 420 alone, 609 after this test)
  (do
    (armed!)
    (cost/proof
        {:of 'seon.print/print-value :input 'seon.print-cost-test/entries
         :n "entries of a flat map, all shown, armed as at boot"
         :work (str work ". Armed, per entry exactly nine contracted calls: classify and scalar for the key "
                    "and the value (4), child, entry and member-indent (3), and leaf-op for the key and the "
                    "value (2)")
         :growth :linear
         ;; a map of at most :sort-bound (64) keys is shown sorted, which every size here is under: the sort adds per entry
         ;; (measured at 10, 20 and 30 entries: the work above falls short by 2.0k ticks and 1.25k B an entry)
         :per-n (merge-with + (dissoc (armed (priced per-entry) 9 62) :ns) {:ticks 2000 :bytes 1250})
         ;; the functions a print calls once or twice whatever its size (show, select, allot, the
         ;; root's node and sizing, tree, emit-both, node-ops, layout and their helpers): 79 calls
         ;; (71 until an entry of two leaves was charged its flat width, 2026-10-25: the least form,
         ;; seon.print.select/minimal, then checks one more member whole, seven calls; and estimate
         ;; became chars-tokens over a text's length, its rule shared with seon.ctx.wire/messages-tokens:
         ;; one call more; 80 with seon.print.select/lifted-namespace, the one decision of a map's
         ;; #:ns{ lift, made once per map over its keys with no call per key)
         :fixed (dissoc (armed fixed 86 170) :ns)
         :sizes [10 20 30]})))

(def ^:private shown-budget
  "The budget the cut inputs print under, stated here so a proof's input does not move with the
  reply's config key: 64 tokens, which shows the first three lines of a text and cuts the rest, so
  thirty lines are enough to see what grows with the text."
  64)

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
  (let [shown (select/estimate-chars shown-budget)]
    (armed!)
    (cost/proof
        {:of 'seon.print/print-value :input 'seon.print-cost-test/text
         :n "lines of a text cut under 64 tokens, armed as at boot"
         :work (str "per line, one indexOf to its line break (~50 characters read) and one conj! of the break's offset; "
                    "fixed, the ~" shown " characters shown: each escaped once into the string literal and looked up by "
                    "about three sizing passes (char-escape-string)")
         :growth :linear
         :ops {:vec/conj! 1 :seq/reduce 50}
         :fixed (dissoc (armed (priced {:str/escape shown :map/get (* 3 shown)}) 86 170) :ns)
         :sizes [10 20 30]})))

(def ^:private rows-budget
  "The budget the rows print under: 256 tokens, which shows the first few rows whole and cuts the rest."
  256)

(defn rows
  "The input of size n: n problem rows (7 keys each, as my.root/problems' :stale-section rows),
  printed with a handle under rows-budget, which shows the first dozen or so whole and cuts the rest."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :map]]}
  [n] [{:rows (vec (for [i (range n)] (array-map :section (keyword "render" (str "s" i)) :page "docs/render.md"
                                                  :class :stale-section :since 1761360000000 :cited 'seon.print/print-value
                                                  :lead "A section whose lead cites a var that moved." :n i)))}
       {:handle 'result/h1 :budget rows-budget}])

(deftest printing-rows-costs-what-it-shows
  ;; a printed value costs what it shows, not what it hides: the budget fixes the rows shown whole
  ;; (a few of 7 keys under rows-budget, then one cut), and no row past the cut is read (seon.print.select/fill-rows).
  ;; Measured 2026-10-25, unarmed: 13.2 MB and 3.39M ticks at 100, 1,000 and 10,000 rows; main's
  ;; breadth path on the same input cost 16.0 MB and 4.28M ticks at 100 rows, growing with them,
  ;; for 493 cuts and no row shown. The fixed part is the selection trying each level whole
  ;; (the root map, its :rows entry, the vector, each row: about four walks of what shows).
  (do
    (armed!)
    (cost/proof
        {:of 'seon.print/print-value :input 'seon.print-cost-test/rows
         :n "problem rows under 256 tokens, armed as at boot"
         :work (str "nothing per row past what the budget could show (alike? reads member kinds only when no row fits whole): the rows the budget shows whole are fixed by it, about four whole walks of "
                    "them (one a level: the root, its :rows entry, the vector, each row), and the first row "
                    "that does not fit is sized until its charge passes the room; no row past the cut is read")
         :growth :constant
         ;; the walks cost what they show, so a quarter of the 1,024 budget's 0.71M ticks and 2.5 MB (2026-10-31, flat from
         ;; 100 to 1,000 rows): 1.8e5 ticks and 6.3e5 bytes, flat from 10 to 30 rows
         :fixed {:ticks 1.8e5 :ns 5.0e5 :bytes 6.3e5}
         :sizes [10 20 30]})))

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
  ;; code-width) and copied once as written (emit/code-text)
  (let [shown (select/estimate-chars shown-budget)]
    (armed!)
    (cost/proof
        {:of 'seon.print/print-value :input 'seon.print-cost-test/code
         :n "lines of code cut under 64 tokens, armed as at boot"
         :work (str "per line, three times (the map's child is made three times, whole, at its least and filled, "
                    "and each finds its line breaks: seon.print.select/node-of), one indexOf to its line break (~50 "
                    "characters read) and one conj! of the break's offset; "
                    "fixed, the ~" shown " characters shown, read by the sizing passes with no escaping and copied once "
                    "into the text: measured 2026-10-25 at 1,000 lines, 576 KB and 350k ticks in all (a string's escaping, "
                    "a-cut-text-reads-its-lines-once, prices the same characters at 13.5 MB)")
         :growth :linear
         :ops {:vec/conj! 3 :seq/reduce 150}
         ;; the shown characters are ~1% of the budget the 2026-10-25 figure was measured under, so ~5 KB of it; the rest is the
         ;; print's fixed part, armed (about 86 contracted calls at 170 B and 14 ticks each, 15 KB, and the root map, its :source
         ;; child made three times and their sizings), which measured 90 KB and 30k ticks at 10 lines
         :fixed {:ticks 3.0e4 :bytes 9.0e4}
         :sizes [10 20 30]})))

;; See docs/render.md#a-render-is-a-value-printed-once
(deftest a-nested-maps-key-namespace-costs-nothing-more
  ;; task seon.print/a-one-key-map-costs-what-its-key-prints: a message whose sender was the one-key map {:seon.agent/key ..} measured
  ;; ~200 calls a message more than one whose sender was {:db/id 5}, through seon.render/hiccup before a printed reference became its
  ;; identity value; the printer alone shows no such cost: a nested map's key costs the same whatever its namespace (an agent's, another,
  ;; the outer map's own), and an attribute key against a bare :db/id the 13 calls a message of one more qualified key printed
  (let [mk (fn [inner] (vec (for [i (range 20)] {:seon.message/id (str "m" i) :seon.message/from inner :seon.message/body "hello"})))
        calls (fn [v outs] (seon.profile/begin!) (seon.print/print-value v {:budget 200000 :outputs outs}) (seon.profile/calls))]
    (doseq [outs [#{:text} #{:hiccup}]]
      (let [agent (calls (mk {:seon.agent/key "u"}) outs)]
        (is (= agent (calls (mk {:x.y/key "u"}) outs) (calls (mk {:seon.message/key "u"}) outs)) (str "one cost whatever the key's namespace, " outs))
        (is (<= (- agent (calls (mk {:db/id 5}) outs)) (* 20 13)) (str "and at most 13 calls a message over a bare :db/id, " outs))))))
