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
                                   (str "\n## Section " s "\n\nSection " s " parks as `app.m0/f" s "` does, see [the page](#page-" p ") and the cost in docs/references.md#cost.\n")))]))
        tx (docs/load-tx db "demo" pages)]
    [(d/db-with db tx) "demo" pages]))

(deftest an-unchanged-reload-costs-a-parse-and-a-pull-per-section
  (cost/proof
    {:of 'seon.docs/load-tx :input 'seon.docs-cost-test/wiki
     :n "sections in the wiki"
     :work (str "per section of ~90 bytes: its parse (seon.ref's estimate, ~270 ns and ~360 B per byte, the read of "
                "its one name included: ~25 us, ~32 KB); on db, one AVET seek for its var and one EAVT seek per "
                "stated attribute, five (seon.task/entity-tx) (~5 seeks at ~17 us and ~33 KB each, seon.db/avet-seek-allocates: ~85 us, "
                "~165 KB); ~19 contracted calls (own-text, cited, two citation-targets, kept and its alias check of the one name (seon.ref/kept: the names it keeps), wanted, entity-tx and one eid; the parse's ~3 per inline for its two inlines and the read's 3; the stored-section scan and page split amortized). Its second inline, a section of a file named by its address, costs a candidate and its target and reads no file (seon.docs/section-names keeps the names only: it used to parse the file for each, 1.2 MB a section). Since the wiki records what each section was written against, a stored section is also pulled once (its text, :seon.section/read, :seon.section/baseline-unread: one more seek each, ~+40 KB, ~+100 us) to keep that record while its text is unchanged. The stored sections are read once, one datom each, so a section's "
                "parent and link targets are map lookups. Nothing is written: the answer is []")
     :growth :linear
     :per-n {:ns 170000 :bytes 190000 :calls 23}
     :fixed {:ns 50000 :bytes 50000 :calls 10}
     :sizes [10 60 250]}))

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
  (the mix of the store's own), ten of each holding the word, whatever n; the search asks for exact text and
  references only (meaning is seon.vector/near's, proved on its own)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string :map :any]]}
  [n]
  (let [hit? (fn [i] (< i 10))
        db (d/db-with (seon.docs-test/grep-db (for [i (range n)] [(str "f" i) nil (str "(defn f" i " []\n  " (if (hit? i) "(fused 1)" "(inc 1)") ")")]))
                      (vec (concat (for [i (range (quot n 2))] {:seon.section/address (str "p.md#s" i) :seon.section/heading (str "S" i)
                                                                :seon.section/text (str "Prose of section " i (when (hit? i) ", fused.") "\nMore prose.")})
                                   (for [i (range (quot n 4))] {:seon.task/id (str "app/t" i) :seon.task/plan (str "Plan " i (when (hit? i) " fused") ".")})
                                   (for [i (range (quot n 8))] {:schema/key (keyword "app" (str "k" i)) :schema/form (str "[:map " (when (hit? i) "fused ") i "]")}))))]
    ;; the postings are derived data, built once for a value of the store (docs/vector.md#search): the query is what is measured
    (run! #(docs/text-index db %) (vals docs/text-attrs))
    [db "fused" {:by #{:text :refs} :k 10} nil]))

(defn tokens-input
  "tokens' arguments at size n: search-input's db (its index built), the word, every kind, z 2."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string [:set :keyword] :double]]}
  [n]
  (let [[db] (search-input n)] [db "fused" #{:var :section :task :schema} 2.0]))

;; See docs/vector.md#search
(deftest tokens-cost-the-texts-holding-the-word
  (cost/proof {:of 'seon.docs/tokens :input 'seon.docs-cost-test/tokens-input
               :n "texts the branch holds (search-input's mix), ten of each kind holding the word"
               :work "a scan of the index's vocabulary (the db unchanged since the index was checked: its :hash and :max-tx say so, no text is read), then the postings of the word: each text holding it scored, each hit read and named"
               :growth :linear
               ;; measured 2026-10-28: 1.66 MB / 132 calls at 100 and 300, 1.83 MB / 132 at 1,000 (the vocabulary's scan, about 190 bytes a
               ;; text); before the postings: 866 KB at 100 with a tenth of the texts holding the word, 16 KB a text of the corpus
               :per-n {:bytes 300 :calls 0}
               :fixed {:bytes 1570000 :calls 100}
               :sizes [300 600 1000]}))

(defn index-input
  "text-index's arguments at size n: search-input's db and the attribute of a form's source."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :qualified-keyword]]}
  [n]
  (let [[db] (search-input n)]
    ;; a db with the same texts but a newer index: the build is what is measured, not a hit
    [(d/db-with db [{:schema/key :app/extra :schema/form "[:map]"}]) :form/src]))

;; See docs/vector.md#search
(deftest a-text-index-is-built-once-for-the-value-it-reads
  (let [[db] (search-input 10)
        a :form/src]
    (is (identical? (docs/text-index db a) (docs/text-index db a)) "a second read of the same value is the first's index")
    (let [db2 (d/db-with db [{:db/id "v" :var/name 'app/new} {:form/id "app/new (defn new [] 1)" :form/src "(defn new [] 1)" :form/defines ["v"]}])
          ix (docs/text-index db2 a)]
      (is (= (inc (:n (docs/text-index db a))) (:n ix)) "a changed attribute is read again: the new text is in it")
      (is (some? (get-in ix [:post "new"])) "its terms are postings"))
    ;; the path search reads: a connection's value, read again with nothing written between
    (is (identical? (docs/text-index db a) (docs/text-index (d/db-with db [{:schema/key :app/other :schema/form "[:map]"}]) a))
        "a write to another attribute moves the db, and the texts' fingerprint keeps the index")
    (ts/test-branch! (fn [_ conn]
                       (is (identical? (docs/text-index @conn a) (docs/text-index @conn a)) "two reads of a branch's value share one index")))))

;; See docs/vector.md#search
(deftest a-text-index-costs-its-texts
  (cost/proof {:of 'seon.docs/build-index :input 'seon.docs-cost-test/index-input
               :n "forms' sources the branch holds (search-input's mix, ten holding the word)"
               :work "one pass over the attribute's texts, each split into terms once and its terms added to the postings"
               :growth :linear
               ;; measured 2026-10-28: 3.30 MB / 461 calls at 100, 4.20 MB / 661 at 300
               :per-n {:bytes 5000 :calls 1.05}
               :fixed {:bytes 2850000 :calls 360}
               :sizes [100 300 1000 4000]}))

(deftest search-costs-its-texts
  (cost/proof {:of 'seon.context/search :input 'seon.docs-cost-test/search-input
               :n "texts the branch holds (forms, sections, task plans and schema entries), ten of each kind holding the word"
               :work "one scan of each kind's text attribute, lower-cased once, a count of the query's words in each (BM25 and its z-score), then per hit its names and its line; the references of the names in the query; relevance over the first k x related-near hits (a bounded pool)"
               :growth :linear
               ;; measured 2026-10-28 with search and relevance on the one seon.docs/bm25 (each text's counts a
               ;; sparse map, bm25's contract checking them): 3.78 MB / 623 calls at 100, 8.70 MB / 1,088 at 300,
               ;; 19.17 MB / 2,314 at 1,000, 67.68 MB / 7,714 at 4,000 (before the one bm25: 62.71 MB at 4,000)
               ;; measured 2026-10-28 with the postings (ten texts of each kind holding the word): 2.70 MB / 204 calls at 100, 2.75 MB / 204 at 300,
               ;; 2.88 MB / 204 at 1,000; before them, a tenth holding it: 3.78 MB at 100, 19.17 MB at 1,000, 67.68 MB / 7,714 calls at 4,000
               :per-n {:bytes 800 :calls 0}
               :fixed {:bytes 2700000 :calls 204}
               :sizes [100 300 1000 4000]}))

(defn citing-input
  "citing-sections' arguments at size n: a wiki of n sections all citing app.m0/f1 (pages of ten), and that var."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :symbol]]}
  [n]
  (let [db (ts/test-db)
        pages (into {} (for [p (range (max 1 (quot n 10)))]
                         [(str "demo/p" p ".md")
                          (apply str (str "# Page " p "\n\nPage " p " holds ten sections.\n")
                                 (for [s (range 1 11)] (str "\n## Section " s "\n\nSection " s " parks as `app.m0/f1` does.\n")))]))]
    [(d/db-with db (docs/load-tx db "demo" pages)) 'app.m0/f1]))

;; See docs/references.md#the-wiki-in-the-store
(deftest a-cited-var-finds-its-sections-by-the-names-sections-hold
  (cost/proof
    {:of 'seon.docs/citing-sections :input 'seon.docs-cost-test/citing-input
     :n "wiki sections citing the var (all of them)"
     :work "one pass over the sections' cited names (:seon.section/names has no index), each match's address; the cut is the first :seon.config.ctx/cited-by of the sorted addresses"
     :growth :linear
     ;; measured 2026-10-27: 332k ticks / 1.42 MB at 100, 1.04M / 4.65 MB at 300, 3.86M / 18.1 MB at 1,000
     :per-n {:ticks 4000 :bytes 19000 :calls 0}
     :fixed {:ticks 20000 :bytes 300000 :calls 4}
     :sizes [100 300 1000]}))

