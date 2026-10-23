(ns seon.docs-cost-test
  "Cost proof of loading the wiki: an unchanged reload costs one parse and a few seeks per
  section, and writes nothing."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.docs :as docs] [seon.cost :as cost]))

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
