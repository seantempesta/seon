(ns my.branch
  "The merge agent's functions (git's words): merge main into this branch, see what is still
  unmerged, resolve an entry. The writer does the merge (seon.merge/three-way, the one
  derivation); this branch sees main's changes only through these functions."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.fx :as fx] [seon.source :as source] [seon.config :as cfg] [seon.agent :as agent] [seon.repl :as repl] [seon.merge :as pm] [seon.stage :as stage] [seon.program :as program]))

(defn merged-summary
  "A merge of main as its eval answers it (seon.render/model, the schema :seon.branch/merged): how many keys it applied, and all the rest as it is, since a
  main far ahead applies hundreds and the agent asks for them when it wants them: `(:applied result/...)` reads them under the eval's handle. See docs/merging.md#main-moved-is-said-at-every-eval."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :map]}
  [_ r]
  (-> (with-meta r nil) (assoc :applied (count (:applied r)))))

(defn merge-main!
  "git merge main: stage this runtime onto the branch, then merge main's changes since
  the merge base. What does not conflict is applied and loaded here; each conflict stays
  on the branch as an unmerged entry (base, ours, theirs). Answers {:ok :applied
  :conflicts :unmerged} (unmerged: every entry still open, earlier ones included), printed by `merged-summary`: the whole
  value stays under the eval's handle."
  {:malli/schema [:=> [:cat] [:map [:ok :boolean]]]}
  []
  (stage/stage!)
  (let [r (agent/merge-main! #{})
        db (agent/db)]
    (with-meta (assoc r :unmerged (pm/unmerged db)) {:seon.render/schema :seon.branch/merged})))

(defn unmerged
  "The entries of this branch still unmerged: the conflicts no re-defn or resolve! has
  settled yet, each with its base, ours and theirs."
  {:malli/schema [:=> [:cat] [:vector :seon.merge/entry-value]]}
  []
  (pm/unmerged (agent/db)))

(defn resolve!
  "Settle the unmerged entry of var sym (git add). With no side the branch keeps what it
  holds now; :theirs or :base loads that side's definition (or unmaps the var when that
  side deleted it) and records it. Answers the entries still unmerged."
  {:malli/schema [:function [:=> [:cat :qualified-symbol] [:vector :seon.merge/entry-value]]
                  [:=> [:cat :qualified-symbol [:enum :ours :theirs :base]] [:vector :seon.merge/entry-value]]]}
  ([sym] (resolve! sym :ours))
  ([sym side]
   (let [k [:var sym]
         entry (first (filter #(= k (:key %)) (pm/entries (agent/db))))
         content (get entry side)
         nsym (symbol (namespace sym))]
     (when (and entry (not= :ours side))
       (if-let [src (:src content)]
         (let [cur (ns-name *ns*)]
           (repl/eval-string (str "(in-ns '" nsym ")\n" src "\n(in-ns '" cur ")")))
         (do (ns-unmap (find-ns nsym) (symbol (name sym)))
             (stage/stage!))))
     (agent/transact! (pm/resolve-tx (agent/db) k))
     (unmerged))))

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
