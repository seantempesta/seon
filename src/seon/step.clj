(ns seon.step
  "The checks the stage templates' handoffs share (`docs/seon/schedule/cards/TASK-CHAIN.md#4-templates-and-their-proofs`):
  pure functions of a plan's text and a database's entities and recorded results."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.cluster.source :as source]
            [seon.ref :as ref]
            [seon.test :as test]
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
  "Each of `tests` with its result at its current identity on `database` (`seon.test.runner/current-results`), a test
  reading its task keyed by `task`'s content as the runner records it, so another task's run of a shared test cannot
  hide it; a test none ran at that identity is absent."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value [:vector :seon.test/sym]]
                   [:or [:map-of :seon.test/sym :map] :seon.test/unknown-error :seon.test/execution-error :seon.error/value]]
                  [:=> [:cat :seon.db/database-value [:vector :seon.test/sym] :seon.test/task]
                   [:or [:map-of :seon.test/sym :map] :seon.test/unknown-error :seon.test/execution-error :seon.error/value]]]}
  ([database tests] (runner/current-results database tests nil))
  ([database tests task] (runner/current-results database tests task)))

(defn changed
  "The values of identity `attribute` (`:seon.fn/sym`, `:seon.test/sym`, `:seon.ns/name`, `:seon.schema/key`) whose
  declarations `database`, a branch, holds at another digest than its merge base with its target
  (`seon.cluster.source/branch-merge-base`, the base `seon.context` judges the done rule over), among those its own
  lineage changed: `seon.test/branch-delta` over `seon.program/own-changed-identities`; empty on a value with no fork."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword] [:set :seon.schema/value]]}
  [database attribute]
  (let [commit (source/branch-merge-base database)
        _ (when (:seon.error/at commit) (throw (ex-info (:seon.error/message commit) commit)))
        base (when commit (db/commit-database database commit))]
    (if (nil? base)
      #{}
      (let [delta (try (test/branch-delta base database) (finally (source/release! [base])))]
        (when (:seon.error/at delta) (throw (ex-info (:seon.error/message delta) delta)))
        (into #{} (keep (fn [[a v]] (when (= attribute a) v))) delta)))))
