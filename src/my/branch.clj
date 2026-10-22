(ns my.branch
  "The merge agent's functions (git's words): merge main into this branch, see what is still
  unmerged, resolve an entry. The writer does the merge (seon.merge/three-way, the one
  derivation); this branch sees main's changes only through these functions."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.repl :as repl] [seon.merge :as pm] [seon.harvest :as harvest]))

(defn merge-main!
  "git merge main: stage this runtime onto the branch, then merge main's changes since
  the merge base. What does not conflict is applied and loaded here; each conflict stays
  on the branch as an unmerged entry (base, ours, theirs). Answers {:ok :applied
  :conflicts :unmerged} (unmerged: every entry still open, earlier ones included)."
  {:malli/schema [:=> [:cat] [:map [:ok :boolean]]]}
  []
  (harvest/harvest!)
  (let [r (agent/merge-main! #{})
        db (agent/db)]
    (assoc r :unmerged (pm/unmerged db (pm/program db #{})))))

(defn unmerged
  "The entries of this branch still unmerged: the conflicts no re-defn or resolve! has
  settled yet, each with its base, ours and theirs."
  {:malli/schema [:=> [:cat] [:vector :seon.merge/entry-value]]}
  []
  (let [db (agent/db)] (pm/unmerged db (pm/program db #{}))))

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
             (harvest/harvest!))))
     (agent/transact! (pm/resolve-tx (agent/db) k))
     (unmerged))))
