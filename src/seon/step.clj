(ns seon.step
  "The checks the stage templates' handoffs share (`docs/seon/schedule/cards/TASK-CHAIN.md#4-templates-and-their-proofs`):
  pure functions of a plan's text and a database's entities and recorded results."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.ref :as ref]
            [seon.test.runner :as runner]))

(defn sections
  "Each heading anchor of Markdown `plan` (`seon.ref/document`), at any level, with its section's text up to the next heading."
  {:malli/schema [:=> [:cat :string] [:map-of :seon.ref/anchor :string]]}
  [plan]
  (let [lines (vec (str/split-lines plan))
        headings (:seon.ref/headings (ref/document plan))
        ends (concat (map :seon.ref/line (rest headings)) [(inc (count lines))])]
    (into {} (map (fn [{:seon.ref/keys [anchor line]} end] [anchor (str/join "\n" (subvec lines line (dec end)))]) headings ends))))

(defn problems
  "The references `text` makes that do not resolve on `database`, each with its `:seon.ref/problem-kind`."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] [:vector :seon.ref/problem]]}
  [database text]
  (into [] (filter :seon.ref/problem-kind) (ref/references database {:seon.ref/text text})))

(defn deftest?
  "Whether `sym` names a deftest on `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol] :boolean]}
  [database sym]
  (boolean (db/q '[:find ?e . :in $ ?s :where [?e :seon.test/sym ?s]] database sym)))

(defn cited-fns
  "The first-party functions (`:seon.fn/sym` on `database`) `text` cites."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] [:set :qualified-symbol]]}
  [database text]
  (set (db/q '[:find [?s ...] :in $ [?s ...] :where [_ :seon.fn/sym ?s]] database (vec (ref/names text)))))

(defn results
  "Each of `tests` with its result at its current reach on `database` (`seon.test.runner/result-at-reach`), so another
  task's newer run of a shared test cannot hide it; a test none ran at that reach is absent."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                  [:or [:map-of :seon.test/sym :map] :seon.test/unknown-error :seon.test/execution-error]]}
  [database tests]
  (let [reaches (runner/reach-digests database tests)]
    (if (:seon.error/at reaches) reaches (runner/result-at-reach database reaches))))
