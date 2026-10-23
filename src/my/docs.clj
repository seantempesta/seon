(ns my.docs
  "The wiki's functions for an agent (a section it has read again): record that you read a section
  as it is, though the code it describes changed, so it is no longer stale."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.docs :as docs]))

(defn reviewed!
  "Record that who read section address (docs/x.md#heading) as it is, with the code it cites as it
  stands now: its recorded form ids become the current ones, its text is not touched, and the review
  (who, and the form ids it read at) is kept as a fact on this branch. A section that cites code the
  branch changed is stale (seon.problems, :stale-section) until it is edited or reviewed. Answers the
  write, or nil when there is no such section."
  {:malli/schema [:=> [:cat :string :string] [:maybe :map]]}
  [address who]
  (when-let [tx (docs/reviewed-tx (agent/db) address who)] (agent/transact! tx)))
