(ns my.branch
  "The merge agent's functions (git's words): merge main into this branch, see what is still
  unmerged, resolve an entry. The writer does the merge (seon.merge/three-way, the one
  derivation); this branch sees main's changes only through these functions."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.fx :as fx] [seon.source :as source] [seon.config :as cfg] [seon.agent :as agent] [seon.repl :as repl] [seon.merge :as pm] [seon.program :as program] [seon.db :as pdb] [datahike.api :as d]))

(defn merged-summary
  "A merge of main as its eval answers it (seon.render/model, the schema :seon.branch/merged): how many keys it applied, and all the rest as it is, since a
  main far ahead applies hundreds and the agent asks for them when it wants them: `(:applied result/...)` reads them under the eval's handle. See docs/merging.md#main-moved-is-said-at-every-eval."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :map]}
  [_ r]
  (-> (with-meta r nil) (assoc :applied (count (:applied r)))))

(defn import!
  "Bring files a session edited onto this agent's branch, as one write through the agent's writer, the path an eval takes: the
  files paths name (relative to root, default this agent's checkout), each read as a boot's catch-up reads its checkout's changed
  files (seon.system/catch-up :fact? false :paths: code, pages and task files as program keys, in the shape an eval writes);
  main never moves. Answers {:imported [path] :unchanged [path] :skipped [{:path :why}]}: a file that does not read is skipped
  with why, never half-written, and one whose facts the branch holds already is unchanged. Cost: n+1 catch-ups for n paths
  (each alone, then the changed ones together), each refresh-tx's fixed cost (~0.3M ticks) plus its files' forms: fine at a
  session's few files, ~30M ticks for a hundred. See docs/source.md#a-boot-catches-up-with-its-checkout."
  {:malli/schema [:function [:=> [:cat [:sequential :string]] :map] [:=> [:cat :string [:sequential :string]] :map]]}
  ([paths] (import! (cfg/checkout) paths))
  ([root paths]
   ;; catch-up is root's namespace's, which requires this one: resolved at the call (docs/resolution.md, owed)
   (let [catch-up (requiring-resolve 'seon.system/catch-up)
         db (agent/db)
         read (fn [ps] (catch-up db root {:fact? false :paths (vec ps)}))
         ;; each file alone says whether it is skipped or already held; the ones that change are read together, so a file's
         ;; dependents across them are read once
         per (into {} (for [p paths] [p (read [p])]))]
     (if-let [e (some #(when (contract/error? %) %) (vals per))]
       e
       (let [skipped (vec (for [[_ r] per s (:skipped r)] {:path (:path s) :why (:reason s)}))
             out (set (map :path skipped))
             unchanged (vec (for [[p r] per :when (and (not (out p)) (empty? (:tx r)))] p))
             todo (vec (for [[p r] per :when (and (not (out p)) (seq (:tx r)))] p))
             r (when (seq todo) (read todo))]
         (when (seq (:tx r)) (agent/transact! (:tx r)) (agent/commit!))
         {:imported todo :unchanged unchanged :skipped skipped})))))

(defn merge-main!
  "git merge main: the branch becomes main plus its own change since the merge base (seon.agent/merge-main!); a key both
  changed stops it, and each conflict stays on the branch as an unmerged entry (base, ours, theirs). Main's forms load at this
  process's next start, never here. Answers {:ok :applied
  :conflicts :unmerged} (unmerged: every entry still open, earlier ones included), printed by `merged-summary`: the whole
  value stays under the eval's handle."
  {:malli/schema [:=> [:cat] [:map [:ok :boolean]]]}
  []
  (let [r (agent/merge-main!)
        db (agent/db)]
    (with-meta (assoc r :unmerged (pm/unmerged db)) {:seon.render/schema :seon.branch/merged})))

(defn unmerged
  "The entries of this branch still unmerged: the conflicts no re-defn or resolve! has
  settled yet, each with its base, ours and theirs."
  {:malli/schema [:=> [:cat] [:vector :seon.merge/entry-value]]}
  []
  (pm/unmerged (agent/db)))

(defn resolve!
  "Settle the unmerged entry of var sym, or of program key k (a task's: [:task \"ns/name\"]) (git add). With no side the branch
  keeps what it holds now; :theirs or :base takes that side: a var's definition loaded (or the var unmapped when that side
  deleted it) and recorded, a task's content written. Answers the entries still unmerged."
  {:malli/schema [:function [:=> [:cat [:or :qualified-symbol :seon.merge/program-key]] [:vector :seon.merge/entry-value]]
                  [:=> [:cat [:or :qualified-symbol :seon.merge/program-key] [:enum :ours :theirs :base]] [:vector :seon.merge/entry-value]]]}
  ([sym] (resolve! sym :ours))
  ([sym side]
   (if (vector? sym)
     (let [k sym entry (first (filter #(= k (:key %)) (pm/entries (agent/db))))]
       (when (and entry (not= :ours side))
         (agent/transact! (pm/content-tx (agent/db) k (get entry side))))
       (agent/transact! (pm/resolve-tx (agent/db) k))
       (unmerged))
   (let [k [:var sym]
         entry (first (filter #(= k (:key %)) (pm/entries (agent/db))))
         content (get entry side)
         nsym (symbol (namespace sym))]
     (when (and entry (not= :ours side))
       (if-let [src (:src content)]
         (let [cur (ns-name *ns*)]
           (repl/eval-string (str "(in-ns '" nsym ")\n" src "\n(in-ns '" cur ")")))
         ;; main's side removed it: unmapped here, and its definition retracted as an eval's ns-unmap retracts it (seon.repl/removal-tx)
         (let [db (agent/db) e (pdb/eid db :var/name sym) f (some-> e (#(d/entity db %)) :var/form :db/id)]
           (ns-unmap (find-ns nsym) (symbol (name sym)))
           (when f (agent/transact! (repl/removal-tx db [[sym e f]]))))))
     (agent/transact! (pm/resolve-tx (agent/db) k))
     (unmerged)))))

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
