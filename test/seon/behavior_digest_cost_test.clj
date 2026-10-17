(ns seon.behavior-digest-cost-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cache :as cache]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(deftest the-functions-of-a-namespace-share-one-resolver-context-derivation
  (support/with-running-agent
   (fn [_ _ connection]
     (support/declare-source!
      connection 'tq.bdc
      (str "(ns tq.bdc (:require [clojure.string :as str]))\n"
           (apply str (for [n (range 6)]
                        (str "(defn f" n " \"Doc " n ".\" [x] (str/trim x))\n")))))
     (let [database (db/db connection)
           entities (mapv :e (filter #(re-find #"^tq\.bdc/f" (str (:v %)))
                                     (db/datoms database :aevt :seon.fn/sym)))
           measured (profile/measure #(mapv (partial db/behavior-digest database) entities))]
       (is (= 6 (count entities)))
       (is (= 6 (count (distinct (:seon.profile/value measured)))))
       (is (= 7 (profile/calls-of measured 'seon.db/declaration-value))
           "six functions and their one namespace")))))

(def ^:private hub-sized-source
  "A documented function the size of `seon.db/as-of-key` (about 700 characters) that spells an alias and a refer."
  (str "(defn hub\n"
       "  \"An as-of view of `database` at `t`, keyed by the basis it reads: the same\n"
       "  basis answers the same key, so every reader of one commit shares one view.\"\n"
       "  {:malli/schema [:=> [:cat :map :int] [:maybe :string]]}\n"
       "  [database t]\n"
       "  (when-let [identity (get database :identity)]\n"
       "    (let [basis (str/join \"-\" [identity t])\n"
       "          parts (str/split basis #\"-\")\n"
       "          keyed (get database ::str/keyed)]\n"
       "      (cond (or keyed (blank? (first parts))) nil\n"
       "            (= 1 (count parts)) (str/trim basis)\n"
       "            :else (str/join \":\" (map str/upper-case parts))))))\n"))

(deftest a-cold-behavior-digest-reads-its-source-once
  ;; Estimate: the ~700-character source read once by edamame with positions (~100 KB), the row's ~25 datoms and their
  ;; reference identities (~130 KB), its canonical row and one digest of it (~30 KB) and ~10 armed calls (~60 KB):
  ;; about 320 KB, bounded at 400 KB. Two rewrite-clj parses of the source cost about 860 KB.
  (support/with-running-agent
   (fn [_ _ connection]
     (support/declare-source!
      connection 'tq.bdc-cold
      (str "(ns tq.bdc-cold (:require [clojure.string :as str :refer [blank?]]))\n"
           "(defn sibling \"Warms the namespace's resolver context.\" [x] (str/trim x))\n"
           hub-sized-source))
     (let [database (db/db connection)
           entity-of (fn [sym] (:e (first (db/datoms database :avet :seon.fn/sym sym))))
           hub (entity-of 'tq.bdc-cold/hub)
           _ (db/behavior-digest database (entity-of 'tq.bdc-cold/sibling))
           _ (cache/evict! :seon.program/source-reading (:v (first (db/datoms database :eavt hub :seon.fn/source))))
           measured (profile/measure #(db/behavior-digest database hub))]
       (is (string? (:seon.profile/value measured)))
       (is (= 1 (profile/calls-of measured 'seon.program/read-source)) "the source is read once")
       (is (< (:seon.profile/alloc-bytes measured) 400000) "the cold digest allocates what one read of its source needs")))))
