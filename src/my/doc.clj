(ns my.doc
  "The wiki's functions for an agent (a section it has read again): record that you read a section
  as it is, though the code it describes changed, so it is no longer stale."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.doc :as doc] [seon.config :as cfg]))

(defn reviewed!
  "Record that who read section address (docs/x.md#heading) as it is, with the code it cites as it
  stands now: its recorded form ids become the current ones, its text is not touched, and the review
  (who, and the form ids it read at) is kept as a fact on this branch. A review is also an empty git
  commit with the trailer `Reviewed: <address>` (the :git effect, on the merges branch), which the wiki's
  baseline reads from git, so the review survives an erased store or a fresh clone; off a checkout, or
  when git refuses, the commit is owed (:commit-owed). A section that cites code the branch changed is
  stale (seon.problems, :stale-section) until it is edited or reviewed. Answers the write with :commit
  (the sha) or :commit-owed, or nil when there is no such section."
  {:malli/schema [:=> [:cat :string :string] [:maybe :map]]}
  [address who]
  (when-let [tx (doc/reviewed-tx (agent/db) address who)]
    (let [w (agent/transact! tx)
          c (agent/fx! :git {:dir (cfg/checkout) :branch (cfg/value :seon.config.source/branch) :files {} :allow-empty? true
                             :message (doc/review-message address who)} {})]
      (if-let [sha (get-in c [:value :sha])]
        (assoc w :commit sha)
        (assoc w :commit-owed address)))))
