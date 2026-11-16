(ns my.branch
  "The merge agent's functions (git's words): merge main into this branch, see what is still
  unmerged, resolve an entry. The writer does the merge (seon.merge/three-way, the one
  derivation); this branch sees main's changes only through these functions."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.fx :as fx] [seon.source :as source] [seon.doc :as doc] [clojure.string :as str] [seon.config :as cfg] [seon.agent :as agent] [seon.repl :as repl] [seon.merge :as pm] [seon.program :as program] [seon.db :as pdb] [datahike.api :as d]))

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
  main never moves; then this process loads what the write changed (seon.program/load-diff!), so it runs its branch's program.
  Answers {:imported [path] :unchanged [path] :skipped [{:path :why}]}, with :load-errors when a form did not load: a file that does not read is skipped
  with why, never half-written, and one whose text the branch holds already is unchanged. Cost: one read of the files, the branch's
  text of each compared (seon.source/files, a page's seon.doc/page-text), and one catch-up of those that differ, together, so a
  file's dependents across them are read once: ~11M ticks warm for a 43 KB namespace [M result/a3b7ff3a]. A checkout's whole diff
  (seon_done's, 53 files) read each alone and then together was 54 catch-ups, 1.2B ticks and 4.4 GB [M result/abc9b79a]. See
  docs/source.md#a-boot-catches-up-with-its-checkout."
  {:malli/schema [:function [:=> [:cat [:sequential :string]] :map] [:=> [:cat :string [:sequential :string]] :map]]}
  ([paths] (import! (cfg/checkout) paths))
  ([root paths]
   ;; catch-up is root's namespace's, which requires this one: resolved at the call (docs/resolution.md, owed)
   (let [catch-up (requiring-resolve 'seon.system/catch-up)
         db (agent/db)
         read (fx/files-at root (vec paths))]
     (if (contract/error? read)
       read
       (let [texts (:value read)
             ;; what the branch holds of each, as its readers write it back: a namespace's file or a kind the allow-list names, a page
             held (merge (source/files db (vec paths))
                         (into {} (for [p paths :when (str/ends-with? p ".md") :let [t (doc/page-text db p)] :when t] [p t])))
             unchanged (vec (filter #(and (contains? held %) (= (get held %) (get texts %))) paths))
             todo (vec (remove (set unchanged) paths))
             r (when (seq todo) (catch-up db root {:fact? false :paths todo}))]
         (if (contract/error? r)
           r
           (let [skipped (vec (for [s (:skipped r)] {:path (:path s) :why (:reason s)}))
                 out (set (map :path skipped))
                 ;; the process runs what its branch now holds, as a spawn's catch-up makes it (program/load-diff!, the one
                 ;; mechanism): written alone, the imported forms were the branch's but not the process's, and its next prove
                 ;; refused as :seon.agent/program-differs (a session's edit, then seon_prove, 2026-11-16)
                 errors (when (seq (:tx r)) (agent/transact! (:tx r)) (agent/commit!) (program/load-diff! db (agent/db)))]
             (cond-> {:imported (vec (remove out todo)) :unchanged unchanged :skipped skipped}
               (seq errors) (assoc :load-errors errors)))))))))

(defn merge-main!
  "git merge main: the branch becomes main plus its own change since the merge base (seon.agent/merge-main!); a key both
  changed stops it, and each conflict stays on the branch as an unmerged entry (base, ours, theirs). Main's forms load at this
  process's next start, never here. Answers {:ok :applied
  :conflicts :unmerged} (unmerged: every entry still open, earlier ones included), printed by `merged-summary`: the whole
  value stays under the eval's handle. The writer's typed error, when it merged nothing (a swept merge base), is answered as it is."
  {:malli/schema [:=> [:cat] [:or [:map [:ok :boolean]] :seon.error/base]]}
  []
  (let [r (agent/merge-main!)]
    (if (contract/error? r)
      r
      (with-meta (assoc r :unmerged (pm/unmerged (agent/db))) {:seon.render/schema :seon.branch/merged}))))

(defn unmerged
  "The entries of this branch still unmerged: the conflicts no re-defn or resolve! has
  settled yet, each with its base, ours and theirs."
  {:malli/schema [:=> [:cat] [:vector :seon.merge/entry-value]]}
  []
  (pm/unmerged (agent/db)))

(defn- settle-fact!
  "One datum entry en of this branch taken to side (its :base or :theirs, as git checkout --theirs): its value written (seon.merge/side-tx);
  a ref to an entity this branch lacks (main made it after the base) brings that entity's own facts from main first, so the branch never
  holds a ref to nothing; a var's form taken is loaded here too, as an eval of it would be. See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :seon.merge/entry-value [:enum :theirs :base]] :any]}
  [{k :key :as en} side]
  (let [[_ e a] k v (get en side) db (agent/db)]
    (when (and (int? v) (= :db.type/ref (get-in (:schema db) [a :db/valueType])) (empty? (d/datoms db :eavt v)))
      (agent/transact! [(assoc (dissoc (d/pull (agent/heard-main) '[*] v) :db/id) :db/id v)]))
    (agent/transact! (pm/side-tx (agent/db) k v))
    (when (and (= :var/form a) (int? v))
      (let [db (agent/db) sym (:var/name (d/entity db e)) src (:form/src (d/entity db v))]
        (when (and sym src)
          (let [cur (ns-name *ns*)] (repl/eval-string (str "(in-ns '" (symbol (namespace sym)) ")\n" src "\n(in-ns '" cur ")"))))))))

(defn resolve!
  "Settle the unmerged entries of an entity (git add): a var by its symbol, any entity by its name ([:seon.task/id \"ns/name\"],
  [:var/name sym]: seon.merge/named, as the merge shows a conflict), or one entry by its key ([:datum e a]). With no side the branch
  keeps what it holds now; :theirs or :base takes that side
  (`settle-fact!`: main's value, a missing ref target brought from main, a var's form loaded). Answers the entries still unmerged."
  {:malli/schema [:function [:=> [:cat [:or :qualified-symbol [:tuple :keyword :any] [:tuple [:= :datum] :int :keyword]]] [:vector :seon.merge/entry-value]]
                  [:=> [:cat [:or :qualified-symbol [:tuple :keyword :any] [:tuple [:= :datum] :int :keyword]] [:enum :ours :theirs :base]] [:vector :seon.merge/entry-value]]]}
  ([x] (resolve! x :ours))
  ([x side]
   (let [db (agent/db) es (pm/entries db)
         ;; the entity x names: a var's symbol, an identity pair, or the entity of a datum key
         e (cond (symbol? x) (pdb/eid db :var/name x)
                 (and (vector? x) (= :datum (first x))) (second x)
                 (and (vector? x) (= 2 (count x)) (qualified-keyword? (first x))) (pdb/eid db (first x) (second x)))
         datum (vec (filter #(and (= :datum (first (:key %))) (not (:resolved? %)) (if (= :datum (first x)) (= x (:key %)) (= e (second (:key %))))) es))]
     (doseq [en datum]
       (when (not= :ours side) (settle-fact! en side))
       (agent/transact! (pm/resolve-tx (agent/db) (:key en))))
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
