(ns seon.docs-cost-test
  "Cost proof of loading the wiki: an unchanged reload costs one parse and a few seeks per
  section, and writes nothing."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.docs :as docs] [seon.cost :as cost] [seon.docs-test]))

(defn wiki
  "The input of size n: a main db already holding a wiki of n sections (pages of ten, each
  section one sentence citing a var and linking its page's first section), and those pages."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string [:map-of :string :string]]]}
  [n]
  (let [db (ts/test-db)
        pages (into {} (for [p (range (max 1 (quot n 10)))]
                         [(str "demo/p" p ".md")
                          (apply str (str "# Page " p "\n\nPage " p " holds ten sections.\n")
                                 (for [s (range 1 (min 10 n))]
                                   (str "\n## Section " s "\n\nSection " s " parks as `app.m0/f" s "` does, see [the page](#page-" p ").\n")))]))
        tx (docs/load-tx db "demo" pages)]
    [(d/db-with db tx) "demo" pages]))

(deftest an-unchanged-reload-costs-a-parse-and-a-pull-per-section
  (cost/proof
    {:of 'seon.docs/load-tx :input 'seon.docs-cost-test/wiki
     :n "sections in the wiki"
     :work (str "per section of ~90 bytes: its parse (seon.ref's estimate, ~270 ns and ~360 B per byte, the read of "
                "its one name included: ~25 us, ~32 KB); on db, one AVET seek for its var and one EAVT seek per "
                "stated attribute, five (seon.task/entity-tx) (~5 seeks at ~17 us and ~33 KB each, seon.db/avet-seek-allocates: ~85 us, "
                "~165 KB); ~19 contracted calls (own-text, cited, two citation-targets, kept and its alias check of the one name (seon.ref/kept: the names it keeps), wanted, entity-tx and one eid; the parse's ~3 per inline for its two inlines and the read's 3; the stored-section scan and page split amortized). Since the wiki records what each section was written against, a stored section is also pulled once (its text, :seon.section/read, :seon.section/baseline-unread: one more seek each, ~+40 KB, ~+100 us) to keep that record while its text is unchanged. The stored sections are read once, one datom each, so a section's "
                "parent and link targets are map lookups. Nothing is written: the answer is []")
     :growth :linear
     :per-n {:ns 225000 :bytes 245000 :calls 19.3}
     :fixed {:ns 50000 :bytes 50000 :calls 10}
     :sizes [10 100 1000]}))

(defn view-of
  "The input of size n: the wiki of n sections (wiki) and the first page's own section, which its nine
  sections cite and which has the nine below it: the view with the most edges any size holds."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string :keyword]]}
  [n]
  (let [[db] (wiki n)] [db "demo/p0.md#page-0" :summary]))

(deftest a-view-costs-its-section-and-its-edges
  (let [[db address depth] (view-of 10)
        v (docs/view db address depth)]
    (is (= 9 (count (:seon.ref/cited-by v))) "the page's nine sections cite it: the edges the proof prices are there"))
  (cost/proof
    {:of 'seon.docs/view :input 'seon.docs-cost-test/view-of
     :n "sections in the wiki"
     :work (str "one pull of the section, one of each section below it, and for each edge one seek (the VAET datoms "
                "of the reverse edge, the references of the pull) and one pull of the edge's entity; none reads "
                "another section, so the work is that of the section's own fan, flat in the wiki's size")
     :growth :constant
     ;; measured 2026-10-25 at 10 sections: 33 contracted calls, 1.05 MB (the section's, its nine children's and its nine edges' pulls)
     :fixed {:calls 33 :bytes 1050000}
     :sizes [10 100 1000]}))

(defn search-input
  "The cost proof's input at n texts: n forms, n/2 wiki sections, n/4 task plans and n/8 schema entries
  (the mix of the store's own), a tenth of each holding the word; the search asks for exact text and
  references only (meaning is seon.vector/near's, proved on its own)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string :map :any]]}
  [n]
  (let [hit? (fn [i] (zero? (mod i 10)))
        db (d/db-with (seon.docs-test/grep-db (for [i (range n)] [(str "f" i) nil (str "(defn f" i " []\n  " (if (hit? i) "(fused 1)" "(inc 1)") ")")]))
                      (vec (concat (for [i (range (quot n 2))] {:seon.section/address (str "p.md#s" i) :seon.section/heading (str "S" i)
                                                                :seon.section/text (str "Prose of section " i (when (hit? i) ", fused.") "\nMore prose.")})
                                   (for [i (range (quot n 4))] {:seon.task/id (str "app/t" i) :seon.task/plan (str "Plan " i (when (hit? i) " fused") ".")})
                                   (for [i (range (quot n 8))] {:schema/key (keyword "app" (str "k" i)) :schema/form (str "[:map " (when (hit? i) "fused ") i "]")}))))]
    [db "fused" {:by #{:text :refs} :k 10} nil]))

(deftest search-costs-its-texts
  (cost/proof {:of 'seon.context/search :input 'seon.docs-cost-test/search-input
               :n "texts the branch holds (forms, sections, task plans and schema entries), a tenth of each holding the word"
               :work "one scan of each kind's text attribute, lower-cased once, a count of the query's words in each (BM25 and its z-score), then per hit its names and its line; the references of the names in the query"
               :growth :linear
               :per-n {:bytes 24000 :calls 6.5}
               :fixed {:bytes 100000 :calls 20}
               :sizes [100 300 1000 4000]}))
