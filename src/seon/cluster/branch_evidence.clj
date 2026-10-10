(ns seon.cluster.branch-evidence
  "Whether a lane's branch proves the source files it commits: each file
  declares exactly the branch's definitions, and every test reaching what the
  branch changed from its cluster head is green on the branch head. The commit
  gate `bin/lane commit --branch-evidence` asks this instead of booting a JVM."
  (:require [clojure.java.io :as io]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.program :as program]
            [seon.test :as test]
            [seon.test.runner :as runner]))

(defn- file-identities
  "The declaration identities `database` records in the file at `path`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.source/path] :seon.program/identity-set]}
  [database path]
  (into #{} (map program/row-identity)
        (db/q '[:find [(pull ?e [:seon.fn/sym :seon.test/sym :seon.ns/name]) ...]
                :in $ ?path :where [?file :seon.fn.file/relative-path ?path] [?e :seon.fn/file ?file]]
              database path)))

(defn- held
  "The definition digest `database` holds for each of `identities`, `:seon.program/absent` where none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set] :seon.source/expected-digests]}
  [database identities]
  (let [found (db/q '[:find ?a ?v ?d :in $ [[?a ?v]]
                      :where [?e ?a ?v] [?e :seon.program/definition-digest ?d]]
                    database (vec identities))]
    (merge (zipmap identities (repeat :seon.program/absent))
           (into {} (map (fn [[a v d]] [[a v] d])) found))))

(defn- behavior-changed?
  "Whether `declaration`, held on `branch` and `head` at different definition
  digests, differs there in behavior (`seon.db/behavior-digest`): a
  docstring-only change demands no test. A namespace or a declaration absent
  on either side is a change."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/declaration-identity] :boolean]}
  [branch head [attribute value]]
  (let [behavior (fn [database]
                   (let [entity (db/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] database attribute value)]
                     (when (int? entity) (db/behavior-digest database entity))))]
    (or (not (#{:seon.fn/sym :seon.test/sym} attribute))
        (let [on-branch (behavior branch)]
          (or (nil? on-branch) (not= on-branch (behavior head)))))))

(defn- analyzed
  "The rows the ordinary analyzer derives from the working-tree bytes of `path`
  under `root`, read against `branch`; the analyzer's refusal when it refuses."
  {:malli/schema [:=> [:cat :seon.fn/root :seon.source/path :seon.db/database-value]
                  [:or :seon.program/rows :map]]}
  [root path branch]
  (let [file (io/file root path)]
    (try (fn/analyze-entities {:seon.fn/root root :seon.fn/roots [(first (.split ^String path "/"))]
                               :seon.fn/changed-paths #{path}
                               :seon.source/previous-database branch
                               :seon.fn.analyzer/sources {(.getCanonicalPath file) (String. (java.nio.file.Files/readAllBytes (.toPath file)) "UTF-8")}})
         (catch clojure.lang.ExceptionInfo failure
           (if (:seon.fn/index-refused (ex-data failure)) (ex-data failure) (throw failure))))))

(defn- file-evidence
  "One committed file against the branch head and the cluster head: its
  declarations differing from the branch (by definition digest), the changed
  ones (branch against head, by behavior: they demand reaching tests), the
  removed ones and the changed host-bound functions."
  {:malli/schema [:=> [:cat :seon.fn/root :seon.db/database-value :seon.db/database-value :seon.source/path] :map]}
  [root branch head path]
  (let [rows (analyzed root path branch)]
    (if-not (sequential? rows)
      {::unanalyzable [path]}
      (let [file (into {} (keep #(when-let [d (:seon.program/definition-digest %)] [(program/row-identity %) d])) rows)
            identities (into (set (keys file)) cat [(file-identities branch path) (file-identities head path)])
            on-branch (held branch identities)
            on-head (held head identities)
            changed (filterv #(and (not= (on-branch %) (on-head %)) (behavior-changed? branch head %))
                             (sort-by str identities))
            host-bound (into #{} (keep #(when (true? (:seon.fn/host-bound? %)) (:seon.fn/sym %))) rows)]
        {::differing (vec (for [declaration (sort-by str identities)
                                :let [in-file (get file declaration :seon.program/absent)]
                                :when (not= in-file (on-branch declaration))]
                            {:seon.source/path path :seon.source/declaration declaration
                             :seon.source/file-digest in-file :seon.source/branch-digest (on-branch declaration)}))
         ::changed changed
         ::removed (filterv #(= :seon.program/absent (on-branch %)) changed)
         ::host-bound (filterv host-bound (map second changed))}))))

(defn- run-id
  "The id of the run that recorded `result`, a lookup ref or a pulled run."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.test.run/id]]}
  [{run :seon.test/run}]
  (if (vector? run) (second run) (:seon.test.run/id run)))

(defn- platform-run?
  "Whether the run `id` on `branch` ran in a JVM loaded from a candidate
  commit (`seon.test.platform`), so it exercised the branch's host-bound code."
  {:malli/schema [:=> [:cat :seon.db/database-value [:maybe :seon.test.run/id]] :boolean]}
  [branch id]
  (boolean (and id (db/q '[:find ?sha . :in $ ?id :where [?run :seon.test.run/id ?id] [?run :seon.test.run/git-sha ?sha]]
                         branch id))))

(defn- platform-evidence?
  "Whether `test-symbol`'s latest result on `branch` ran in a platform run
  for its current reach there, green or red: the verdict buckets judge it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test/sym [:set :seon.test.run/evaluator]] :boolean]}
  [branch test-symbol evaluators]
  (let [result (test/recorded-result branch test-symbol evaluators)
        current (runner/reach-digests branch [test-symbol])]
    (boolean (and (:seon.test/pass-count result) (map? current)
                  (= (get current test-symbol) (:seon.test.member/reach-digest result))
                  (platform-run? branch (run-id result))))))

(defn- test-evidence
  "The reaching tests of the changed declarations, sorted into verified,
  red-before and unproven, the changed functions no test reaches, and the
  changed host-bound functions every reaching test of which has its current
  result from a platform run."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value [:vector :seon.program/declaration-identity]
                       [:vector :seon.program/declaration-identity] [:vector :seon.fn/sym] [:set :seon.test.run/evaluator]]
                  [:or :map :seon.error/value]]}
  [branch head changed removed host-bound evaluators]
  (let [present (remove (set removed) changed)
        reach (fn [database syms] (if (seq syms) (test/reaching {:seon.db/db database :seon.test/changed (vec syms)}) []))
        per-fn (into {} (for [[a s] present :when (= a :seon.fn/sym)] [s (reach branch [s])]))
        gone (reach head (map second removed))
        failure (some #(when (map? %) %) (cons gone (vals per-fn)))]
    (or failure
        (let [branch-tests (set (db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] branch))
              tests (into (sorted-set) cat [(apply concat (vals per-fn))
                                            (filter branch-tests gone)
                                            (for [[a s] present :when (= a :seon.test/sym)] s)])
              verdicts (into {} (map (fn [t] [t (test/verified-by? branch t evaluators)])) tests)
              refusal (some #(when (map? %) %) (vals verdicts))]
          (or refusal
              (let [{verified true unverified false} (group-by #(true? (verdicts %)) tests)
                    {before true unproven false}
                    (group-by #(and (runner/red-result? (test/recorded-result branch % evaluators))
                                   (runner/red-at-current-reach? head % evaluators))
                              unverified)]
                {::verified (vec verified)
                 ::red-before (vec before)
                 ::unproven (vec unproven)
                 ::unreached (vec (sort (keep (fn [[s r]] (when (empty? r) s)) per-fn)))
                 ::platform-proven (filterv (fn [s] (and (seq (per-fn s)) (every? #(platform-evidence? branch % evaluators) (per-fn s))))
                                            host-bound)
                 ::run-ids (vec (sort (distinct (keep #(run-id (test/recorded-result branch % evaluators)) verified))))}))))))

(defn- branch-agent
  "The id of the agent homed on `branch` in `database`, or nil (root's) when none is."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch] [:maybe :seon.agent/id]]}
  [database branch]
  (db/q '[:find ?id . :in $ ?branch :where [?agent :seon.agent/branch ?branch] [?agent :seon.agent/id ?id]]
        database branch))

(defn- commit-value
  "The commit id `branch` names in `store` and its database value, or the registry's error."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch] [:or [:tuple :uuid :seon.db/database-value] :seon.error/value]]}
  [store branch]
  (let [commit (registry/branch-commit-id {:seon.store/store store :seon.store/branch branch})]
    (if (:seon.error/at commit) commit [commit (source/commit-database store commit)])))

(defn- verdict
  "The gate's answer from the merged file and test evidence."
  {:malli/schema [:=> [:cat :map :seon.source/accepted-red-before] :seon.source/branch-evidence]}
  [{::keys [differing unanalyzable host-bound platform-proven changed red-before unproven unreached verified run-ids
            branch-id head-id]}
   accepted]
  (let [host-bound (vec (remove (set platform-proven) host-bound))]
  {:seon.source/proven? (and (every? empty? [differing unanalyzable host-bound unproven unreached])
                             (every? accepted red-before))
   :seon.source/branch-commit-id branch-id
   :seon.source/head-commit-id head-id
   :seon.source/changed (vec changed)
   :seon.source/differing (vec differing)
   :seon.source/unanalyzable (vec unanalyzable)
   :seon.source/host-bound host-bound
   :seon.test.platform/proven (vec platform-proven)
   :seon.source/verified (vec verified)
   :seon.source/unproven (vec unproven)
   :seon.source/unreached (vec unreached)
   :seon.source/red-before (vec red-before)
   :seon.source/run-ids (vec run-ids)}))

(defn branch-evidence
  "Whether `branch` of `store` proves the source files at `paths` under `root`.
  Each file's working-tree bytes must declare exactly the branch head's definitions
  (`:seon.source/differing` names each that does not), every changed
  host-bound declaration is `:seon.test.platform/proven` (each test reaching
  it has its current result from a run in a JVM loaded from a candidate
  commit, `seon.test.platform`; in-process evidence exercised the old compiled
  code; green or red, the usual buckets then judge it),
  else it is `:seon.source/host-bound`, and every test
  reaching a declaration changed from `head` (its cluster's branch) is `seon.test/verified-by?`
  on the branch head, by the evaluators proving the work of the agent homed there
  (`seon.test.runner/accepted-evaluators`: its own interpreted runs and compiled ones), which
  also read a red on the head. A changed function no test reaches is
  `:seon.source/unreached`; a test red on the branch and recorded red on the
  head for its current reach there is `:seon.source/red-before`, accepted only
  when named in `:seon.source/accepted-red-before`. Answers data; an
  unavailable branch or head answers its error."
  {:malli/schema [:=> [:cat :seon.store/store :seon.source/branch-evidence-request]
                  [:or :seon.source/branch-evidence :seon.error/value]]}
  [store {branch :seon.store/branch head :seon.source/head root :seon.fn/root
          paths :seon.source/paths accepted :seon.source/accepted-red-before}]
  (let [at-branch (commit-value store branch)
        at-head (when (vector? at-branch) (commit-value store head))
        [branch-id branch-db] (when (vector? at-branch) at-branch)
        [head-id head-db] (when (vector? at-head) at-head)]
    (try
      (cond
        (map? at-branch) at-branch
        (map? at-head) at-head
        :else
        (let [files (mapv #(file-evidence root branch-db head-db %) paths)
              merged (apply merge-with into {::differing [] ::unanalyzable [] ::changed [] ::removed [] ::host-bound []} files)
              tests (test-evidence branch-db head-db (::changed merged) (::removed merged) (::host-bound merged)
                                   (runner/accepted-evaluators (branch-agent branch-db branch)))]
          (if (:seon.error/at tests)
            tests
            (verdict (merge merged tests {::branch-id branch-id ::head-id head-id}) (or accepted #{})))))
      (finally (source/release! [branch-db head-db])))))
