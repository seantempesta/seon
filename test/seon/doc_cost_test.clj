(ns seon.doc-cost-test
  "Cost proof of loading the wiki: an unchanged reload costs one parse and a few seeks per
  section, and writes nothing."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [my.test :as mt] [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.doc :as doc] [seon.sqlite :as sqlite] [seon.cost :as cost] [seon.doc-test]))

(defn- base
  "The world, and the namespace app.m0 the pages cite (f0 to f10)."
  []
  (mt/with (mt/world) (mt/code 'app.m0 (str "(ns app.m0)\n" (apply str (for [i (range 11)] (str "(defn f" i " [] " i ")\n")))))))

(defn wiki
  "The input of size n: a main db already holding a wiki of n sections (pages of ten, each
  section one sentence citing a var and linking its page's first section), and those pages."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string [:map-of :string :string]]]}
  [n]
  (let [db (base)
        pages (into {} (for [p (range (max 1 (quot n 10)))]
                         [(str "demo/p" p ".md")
                          (apply str (str "# Page " p "\n\nPage " p " holds ten sections.\n")
                                 (for [s (range 1 (min 10 n))]
                                   (str "\n## Section " s "\n\nSection " s " parks as `app.m0/f" s "` does, see [the page](#page-" p ") and the cost in docs/references.md#cost.\n")))]))
        tx (doc/load-tx db "demo" pages)]
    [(d/db-with db tx) "demo" pages]))

(def ^:private reload-estimate
  "The estimate of an unchanged reload: see the test."
  {:of 'seon.doc/load-tx :input 'seon.doc-cost-test/wiki
   :n "sections in the wiki"
   :work (str "per section of ~90 bytes: its parse (seon.ref's estimate, ~270 ns and ~360 B per byte, the read of "
              "its one name included: ~25 us, ~32 KB); on db, one AVET seek for its var and one EAVT seek per "
              "stated attribute, five (seon.task/entity-tx) (~5 seeks at ~17 us and ~33 KB each, seon.db/avet-seek-allocates: ~85 us, "
              "~165 KB); ~19 contracted calls (own-text, cited, two citation-targets, kept and its alias check of the one name (seon.ref/kept: the names it keeps), wanted, entity-tx and one eid; the parse's ~3 per inline for its two inlines and the read's 3; the stored-section scan and page split amortized). Its second inline, a section of a file named by its address, costs a candidate and its target and reads no file (seon.doc/section-names keeps the names only: it used to parse the file for each, 1.2 MB a section). Since the wiki records what each section was written against, a stored section is also pulled once (its text, :seon.doc/read, :seon.doc/baseline-unread: one more seek each, ~+40 KB, ~+100 us) to keep that record while its text is unchanged. The stored sections are read once, one datom each, so a section's "
              "parent and link targets are map lookups. Nothing is written: the answer is []")
   :growth :linear
   ;; 2026-10-31 (shrink-tests-4): sizes [10 20 30] on the world (a size is a multiple of ten: pages of ten sections):
   ;; 1.69 / 3.56 MB, 231 / 459 / 687 calls: exactly 3 + 22.8 a section (228 a page), the old sizes up to 250 read the same
   ;; ~177 KB a section. Calls are counted exactly, so the test asserts them (below) and the growth needs no wide span
   :per-n {:ns 170000 :bytes 190000 :calls 22.8}
   :fixed {:ns 50000 :bytes 50000 :calls 3}
   :sizes [10 20 30]})

(deftest an-unchanged-reload-costs-a-parse-and-a-pull-per-section
  ;; the claim: per section, a parse and a pull and a fixed number of contracted calls, nothing written. Calls are exact, so
  ;; the growth is asserted by count: at evenly spaced sizes the calls are 3 + 228 for each page of ten sections (equal
  ;; increments are linear), and the proof judges the bytes and ns against the estimate. Red if a section costs another call
  ;; (the count moves), a pass over the whole wiki appears (the increments stop being equal), or bytes pass the estimate.
  (let [{:keys [rows verdict]} (cost/run reload-estimate)]
    (is (= (map #(+ 3 (* 228 (quot % 10))) [10 20 30]) (map #(long (get-in % [:measured :calls])) rows)) "exactly 228 calls a page of ten sections")
    (is (:ok? verdict) (cost/explain reload-estimate rows verdict))))

(defn view-of
  "The input of size n: the wiki of n sections (wiki) and the first page's own section, which its nine
  sections cite and which has the nine below it: the view with the most edges any size holds."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string :keyword]]}
  [n]
  (let [[db] (wiki n)] [db "demo/p0.md#page-0" :summary]))

(deftest a-view-costs-its-section-and-its-edges
  (let [[db address depth] (view-of 10)
        v (doc/view db address depth)]
    (is (= 9 (count (:seon.ref/cited-by v))) "the page's nine sections cite it: the edges the proof prices are there"))
  (cost/proof
    {:of 'seon.doc/view :input 'seon.doc-cost-test/view-of
     :n "sections in the wiki"
     :work (str "one pull of the section, one of each section below it, and for each edge one seek (the VAET datoms "
                "of the reverse edge, the references of the pull) and one pull of the edge's entity; none reads "
                "another section, so the work is that of the section's own fan, flat in the wiki's size")
     :growth :constant
     ;; measured 2026-10-25 at 10 sections: 33 contracted calls, 1.05 MB (the section's, its nine children's and its nine edges' pulls)
     :fixed {:calls 33 :bytes 1050000}
     :sizes [10 30 100]}))

(defn search-input
  "The cost proof's input at n texts: n forms, n/2 wiki sections, n/4 task plans and n/8 schema entries
  (the mix of the store's own), ten of each holding the word, whatever n; the search asks for exact text and
  references only (meaning is seon.vector/near's, proved on its own)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string :map :any]]}
  [n]
  (let [hit? (fn [i] (< i 10))
        db (d/db-with (seon.doc-test/grep-db (for [i (range n)] [(str "f" i) nil (str "(defn f" i " []\n  " (if (hit? i) "(fused 1)" "(inc 1)") ")")]))
                      (vec (concat (for [i (range (quot n 2))] {:seon.doc/address (str "p.md#s" i) :seon.doc/heading (str "S" i)
                                                                :seon.doc/text (str "Prose of section " i (when (hit? i) ", fused.") "\nMore prose.")})
                                   (for [i (range (quot n 4))] {:seon.task/id (str "app/t" i) :seon.task/plan (str "Plan " i (when (hit? i) " fused") ".")})
                                   (for [i (range (quot n 8))] {:schema/key (keyword "app" (str "k" i)) :schema/form (str "[:map " (when (hit? i) "fused ") i "]")}))))]
    ;; the postings are derived data, built once for a value of the store (docs/vector.md#search): the query is what is measured
    (doc/text-index db)
    [db "fused" {:by #{:text :refs} :k 10} nil]))

(defn tokens-input
  "tokens' arguments at size n: the world with n/2 wiki sections and n/2 task plans (the texts of the kinds asked),
  ten of them (five of each) holding the word `fused` and the rest one text between them, so the vocabulary of the
  texts holding no word is the same few terms at any n and the word is rare by construction; the index built."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :string [:set :keyword] :double]]}
  [n]
  (let [h (quot n 2)
        db (mt/with (mt/world)
                    (vec (concat (for [i (range h)] {:seon.doc/address (str "p.md#s" i) :seon.doc/heading "S"
                                                     :seon.doc/text (if (< i 5) "Prose of a section, fused.\nMore prose." "Prose of a section.\nMore prose.")})
                                 (for [i (range h)] {:seon.task/id (str "app/t" i)
                                                     :seon.task/plan (if (< i 5) "Plan of a task fused." "Plan of a task.")}))))]
    ;; the postings are derived data, built once for a value of the store (docs/vector.md#search): the query is what is measured
    (doc/text-index db)
    [db "fused" #{:section :task} 0.5]))

;; See docs/vector.md#search
(deftest tokens-cost-the-texts-holding-the-word
  ;; the claim: a text that holds no word of the query costs nothing; the work is the FTS5 query, which reads the rows holding it.
  ;; The input (tokens-input) has ten texts holding the word, so the cost is flat. Red if tokens reads each text (a scan of the
  ;; texts: bytes and calls grow with n) or its hits are not the ten (the first assertion).
  (let [[db w kinds z] (tokens-input 40)]
    (is (= 10 (count (doc/tokens db w kinds z))) "the ten texts holding the word are the hits"))
  (cost/proof {:of 'seon.doc/tokens :input 'seon.doc-cost-test/tokens-input
               :n "texts of the kinds asked (sections and task plans), ten of them holding the word, the rest one vocabulary without it"
               :work "one FTS5 query of the index (the db unchanged since the index was built: its :hash and :max-tx say so, no text is read): each text holding the word scored, each hit read and named"
               :growth :constant
               ;; 2026-10-31 (shrink-tests-4): measured on this input; before it (search-input's mix, a vocabulary that grew
               ;; with the texts' names): 1.66 MB / 132 calls at 100 and 300, 1.83 MB / 132 at 1,000. Here 323 KB / 32 calls at 20 and 60,
               ;; 340 KB at 200 (z-min 0.5, so the ten holding the word are the hits at every size; at 2 a small store's z leaves fewer)
               ;; with the FTS5 index (task seon.doc/the-text-index-is-fts5; no vocabulary to scan): 282 KB / 30 calls at 20 and 60, 300 KB at 200
               :fixed {:bytes 305000 :calls 30}
               :sizes [20 60 200]}))

(defn index-input
  "build-index's argument at size n: search-input's db, with a text added so it is a newer value."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db]]}
  [n]
  (let [[db] (search-input n)]
    ;; a db with the same texts but a newer index: the build is what is measured, not a hit
    [(d/db-with db [{:schema/key :app/extra :schema/form "[:map]"}])]))

;; See docs/vector.md#search
(deftest a-text-index-is-built-once-for-the-value-it-reads
  (let [[db] (search-input 10)]
    (is (identical? (doc/text-index db) (doc/text-index db)) "a second read of the same value is the first's index")
    (let [db2 (d/db-with db [{:db/id "v" :var/name 'app/new} {:form/id "app/new (defn new [] 1)" :form/src "(defn new [] 1)" :form/defines ["v"]}])
          ix (doc/text-index db2)]
      (is (= (inc (:n (doc/text-index db))) (:n ix)) "a changed text is read again: the new text is in it")
      (is (= 1 (count (sqlite/fts-match ix "\"new\"" #{}))) "and found by its word"))
    ;; the path search reads: a connection's value, read again with nothing written between
    (ts/test-branch! (fn [_ conn]
                       (is (identical? (doc/text-index @conn) (doc/text-index @conn)) "two reads of a branch's value share one index")))))

;; See docs/vector.md#search
(deftest a-text-index-costs-its-texts
  (cost/proof {:of 'seon.doc/build-index :input 'seon.doc-cost-test/index-input
               :n "forms' sources the branch holds (search-input's mix, ten holding the word); the index holds every kind's texts"
               :work "one pass over every text attribute's texts, each bound once into the FTS5 table (sqlite tokenizes and stems it)"
               :growth :linear
               ;; measured 2026-10-28: 3.30 MB / 461 calls at 100, 4.20 MB / 661 at 300
               ;; 2026-10-31 (shrink-tests-4): on the world (no seed texts), sizes [60 180 600]: 316 KB / 71 calls at 60, 828 KB /
               ;; 191 at 180, 2.72 MB / 611 at 600: ~4.5 KB and 1 call a text, the fixed part ~20 KB / 10 calls (the 2.85 MB / 360
               ;; calls were the seed's texts)
               ;; the FTS5 index of every text attribute (task seon.doc/the-text-index-is-fts5): 238 KB at 60, 599 KB at 180,
               ;; 1.87 MB at 600, 10 calls at every size (the trigram table's create and fill are two more): ~3.0 KB a text (the text, its C string and its row), the fixed part ~56 KB
               :per-n {:bytes 3100 :calls 0}
               :fixed {:bytes 62000 :calls 10}
               :sizes [60 180 600]}))

(deftest search-costs-its-texts
  (cost/proof {:of 'seon.context/search :input 'seon.doc-cost-test/search-input
               :n "texts the branch holds (forms, sections, task plans and schema entries), ten of each kind holding the word"
               :work "one FTS5 query of the index (built once for the value), BM25 and a z-score of its rows, then per hit its names and its line; the references of the names in the query; relevance over the first k x related-near hits (a bounded pool)"
               :growth :linear
               ;; measured 2026-10-28 with search and relevance on the one seon.doc/bm25 (each text's counts a
               ;; sparse map, bm25's contract checking them): 3.78 MB / 623 calls at 100, 8.70 MB / 1,088 at 300,
               ;; 19.17 MB / 2,314 at 1,000, 67.68 MB / 7,714 at 4,000 (before the one bm25: 62.71 MB at 4,000)
               ;; measured 2026-10-28 with the postings (ten texts of each kind holding the word): 2.70 MB / 204 calls at 100, 2.75 MB / 204 at 300,
               ;; 2.88 MB / 204 at 1,000; before them, a tenth holding it: 3.78 MB at 100, 19.17 MB at 1,000, 67.68 MB / 7,714 calls at 4,000
               ;; 2026-10-31 (shrink-tests-4): on the world, sizes [100 300 1000]: 2.64 / 2.69 / 2.83 MB, 204 calls: ~210 B a text,
               ;; the fixed part 2.62 MB (the 800 B a text was the seed's)
               ;; with the FTS5 index (task seon.doc/the-text-index-is-fts5): 2.52 / 2.54 / 2.57 MB, 202 calls (select and in-attrs are two contracted calls): ~50 B a text, the
               ;; fixed part 2.52 MB
               :per-n {:bytes 55 :calls 0}
               :fixed {:bytes 2530000 :calls 202}
               :sizes [100 300 1000]}))

(defn citing-input
  "citing-sections' arguments at size n: a wiki of n sections all citing app.m0/f1 (pages of ten), and that var."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :symbol]]}
  [n]
  (let [db (base)
        pages (into {} (for [p (range (max 1 (quot n 10)))]
                         [(str "demo/p" p ".md")
                          (apply str (str "# Page " p "\n\nPage " p " holds ten sections.\n")
                                 (for [s (range 1 11)] (str "\n## Section " s "\n\nSection " s " parks as `app.m0/f1` does.\n")))]))]
    [(d/db-with db (doc/load-tx db "demo" pages)) 'app.m0/f1]))

;; See docs/references.md#the-wiki-in-the-store
(deftest a-cited-var-finds-its-sections-by-the-names-sections-hold
  (cost/proof
    {:of 'seon.doc/citing-sections :input 'seon.doc-cost-test/citing-input
     :n "wiki sections citing the var (all of them)"
     :work "one pass over the sections' cited names (:seon.doc/names has no index), each match's address; the cut is the first :seon.config.ctx/cited-by of the sorted addresses"
     :growth :linear
     ;; 2026-10-31 (shrink-tests-4): sizes [30 100 300], on the world
     ;; measured 2026-10-27: 332k ticks / 1.42 MB at 100, 1.04M / 4.65 MB at 300, 3.86M / 18.1 MB at 1,000
     :per-n {:ticks 4000 :bytes 19000 :calls 0}
     :fixed {:ticks 20000 :bytes 300000 :calls 4}
     :sizes [30 100 300]}))

