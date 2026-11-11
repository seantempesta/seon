(ns my.branch
  "The merge agent's functions (git's words): merge main into this branch, see its
  conflicts with main, settle them by merging again with resolutions. The merge is the one merge
  (seon.agent/merge-main!, docs/agent.md#merging); this branch sees main's changes only through these functions."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.fx :as fx] [seon.source :as source] [seon.config :as cfg] [seon.agent :as agent] [seon.merge :as pm] [seon.stage :as stage] [seon.program :as program]))

(defn merged-summary
  "A merge of main as its eval answers it (seon.render/model, the schema :seon.branch/merged): how many keys it applied, and all the rest as it is, since a
  main far ahead applies hundreds and the agent asks for them when it wants them: `(:applied result/...)` reads them under the eval's handle. See docs/merging.md#main-moved-is-said-at-every-eval."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :map]}
  [_ r]
  (-> (with-meta r nil) (assoc :applied (count (:applied r)))))

(defn merge-main!
  "git merge main: stage this runtime onto the branch, then merge main's changes since the merge base, as the one merge
  does (seon.agent/merge-main!): main's transactions replayed onto the branch and loaded here. A conflict (an entity's
  attribute this branch and main each changed to their own value since the base, :seon.merge/data-conflict) writes nothing:
  the answer is the refusal naming them (:seon.merge/conflicts). Merge again with resolutions, {key value}, key [entity
  attribute] (its :seon.merge/resolve shows it), value the one the branch keeps (:seon.merge/gone retracts it). Answers
  {:ok :applied}, printed by
  `merged-summary`: the whole value stays under the eval's handle. See docs/decisions.md, 'A merge replays the source's
  transactions on the branch that receives it'."
  {:malli/schema [:function [:=> [:cat] [:map [:ok :boolean]]] [:=> [:cat :map] [:map [:ok :boolean]]]]}
  ([] (merge-main! {}))
  ([resolutions]
   (stage/stage!)
   (with-meta (agent/merge-main! resolutions) {:seon.render/schema :seon.branch/merged})))

(defn unmerged
  "This branch's conflicts with main now, as a merge of main would answer them (seon.agent/main-conflicts), each a
  :seon.merge/data-conflict whose :seon.merge/resolve is the merge-main! form that settles it. See docs/agent.md#merging."
  {:malli/schema [:=> [:cat] [:vector :seon.merge/data-conflict]]}
  []
  (agent/main-conflicts (:base @agent/state) (agent/db) (agent/heard-main)))

(defn changes
  "git diff main...: what this branch's merge will carry to main, the vars it added, changed
  and removed and the schemas it registered or changed, each sorted (seon.merge/divergence of
  the turn's one diff since its merge base, seon.agent/branch-changes); {} when nothing, and under :checkout the files git sees
  changed in your checkout (seon.fx/uncommitted), which no merge carries.
  Anything listed that the task does not need, put back."
  {:malli/schema [:=> [:cat] :map]}
  [] (let [files (fx/uncommitted (str (cfg/checkout)))]
       (cond-> (pm/divergence (agent/branch-changes (:base @agent/state) (agent/db)))
         (seq files) (assoc :checkout (if (contract/error? files) (:seon.error/message files) files)))))

(defn file
  "The text of the file at path (relative to the checkout) as this agent's branch holds it, or nil when the branch holds no
  such file: a program file fact (:seon.config.source/things: the schema resources, the shipped config, bin/, the test
  fixtures), read from the branch's db (seon.source/held-text), never from a checkout, so a test reading a fixture reads the
  program its digest covers. In a process with no branch (bin/test, which runs its checkout's own tree) the checkout's file.
  See docs/source.md#files-and-the-db-two-directions."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [path]
  (if (:db @agent/state)
    (source/held-text (agent/db) path)
    (let [f (java.io.File. (str (cfg/checkout) "/" path))] (when (.isFile f) (slurp f)))))
