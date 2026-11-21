(ns seon.repl
  "Interactive REPL session commands (`run-tests!`, `grep-src`, `help`, `autofix-health!`,
   `inspect-branch`, `save-example!`, `create-task!`) and unified `user/*` helper installer."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [seon.code :as code]
            [seon.code.cst :as cst]
            [seon.code.edit :as edit]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.fork.worker-support :as wsup]
            [seon.health :as health]
            [seon.store :as store]
            [seon.health.metrics :as hmetrics]))

(def ^:private grep-src-cache (store/lru-cache 8))

(defn- cached-form-index [bdb]
  (let [ck [(get-in bdb [:config :store :id])
            (get-in bdb [:config :branch] :main)
            (:max-tx bdb)]]
    (store/lru-get!
     grep-src-cache 8 ck
     (fn []
       (let [by-eid (volatile! {})]
         (doseq [dt (d/datoms bdb :aevt :seon.code.form/key)]
           (vswap! by-eid assoc (:e dt) {:key (str (:v dt))}))
         (doseq [dt (d/datoms bdb :aevt :seon.code.form/ns)]
           (when (contains? @by-eid (:e dt))
             (vswap! by-eid assoc-in [(:e dt) :ns] (name (:v dt)))))
         (let [out (volatile! [])]
           (doseq [dt (d/datoms bdb :aevt :seon.code.form/src)
                   :let [info (get @by-eid (:e dt))]
                   :when info
                   :let [src (str (:v dt))
                         fk (:key info)]]
             (vswap! out conj {:key fk
                               :sym (try (symbol fk) (catch Throwable _ nil))
                               :ns (or (:ns info) (first (str/split fk #"/|#")))
                               :src src
                               :low (str/lower-case src)}))
           @out))))))

(defn grep-src
  "Fast cached single- or multi-pattern substring/regex grep across all form sources in Datahike.
   Pass a string, regex, or collection of patterns `[\"p1\" \"p2\"]` to scan in a single pass.
   Each hit includes `:sym` (symbol), `:key` (string), `:ns`, `:line`, and `:text`."
  {:malli/schema [:function
                  [:=> [:cat :any] [:maybe [:vector :map]]]
                  [:=> [:cat :any :map] [:maybe [:vector :map]]]]}
  ([pattern] (grep-src pattern {}))
  ([pattern {:keys [ns limit] :or {limit 30}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [bdb @conn
           ns-pref (when ns (if (string? ns) ns (name ns)))
           multi? (and (coll? pattern) (not (map? pattern)))
           pats (if multi? (vec pattern) [pattern])
           compiled (mapv (fn [p]
                            (if (instance? java.util.regex.Pattern p)
                              {:raw (str p) :re? true :re p}
                              {:raw (str p) :re? false :low (str/lower-case (str p))}))
                          pats)
           match-low? (fn [{:keys [re? re low]} ^String src ^String low-src]
                        (if re? (boolean (re-find re src)) (str/includes? low-src low)))
           match-line? (fn [{:keys [re? re low]} ^String line]
                         (if re? (boolean (re-find re line)) (str/includes? (str/lower-case line) low)))
           entries (cached-form-index bdb)
           hits (volatile! [])]
       (doseq [{:keys [key sym src low] :as entry} entries
               :while (< (count @hits) limit)
               :let [ens (:ns entry)]
               :when (or (nil? ns-pref)
                         (= ens ns-pref)
                         (str/starts-with? (str ens) (str ns-pref ".")))
               :let [matched-pats (filterv #(match-low? % src low) compiled)]
               :when (seq matched-pats)]
         (doseq [[i line] (map-indexed vector (str/split-lines src))
                 :while (< (count @hits) limit)
                 :let [hit-p (some #(when (match-line? % line) (:raw %)) matched-pats)]
                 :when hit-p]
           (let [t (str/trim line)]
             (vswap! hits conj
                     (cond-> {:key key
                              :sym sym
                              :ns ens
                              :line (inc i)
                              :text (if (> (count t) 120) (str (subs t 0 120) "…") t)}
                       multi? (assoc :pattern hit-p))))))
       @hits))))

(defn run-tests!
  "Runs the exact reach-selected `deftest`s for your branch (or one/more explicit target test vars/namespaces)\n   live in the REPL. Variadic: `(user/run-tests!)`, `(user/run-tests! 'ns-test)`, or `(user/run-tests! 't1 't2)`.\n   Carries `:seon.eval/budget-override` so variable-size test suites run without tripping the 268 MB REPL cap."
  {:malli/schema [:=> [:cat [:* :any]] [:maybe :map]],
   :seon.eval/budget-override {:ticks 2000000000, :bytes 4294967296}}
  ([] (run-tests! nil))
  ([target & more]
    (when-let [{:keys [conn branch]} *agent-ctx*]
      (let [t0 (System/nanoTime)
            bdb @conn
            base-cid (when (not= branch :main)
                       (try
                         (d/merge-base conn :main branch)
                         (catch Throwable _ nil)))
            ops (when base-cid
                  (code/changed-form-ops
                    conn
                    base-cid
                    (d/commit-id bdb)))
            _ (when (seq (:upserts ops))
                (wsup/apply-form-overlay!
                  bdb
                  {:upserts (:upserts ops),
                   :retracted-keys (:retracted-keys ops)}))
            raw-targets (if (seq more) (cons target more) [target])
            target-list (->>
                          raw-targets
                          (mapcat
                            (fn [x]
                              (cond
                                (nil? x) nil
                                (or (vector? x) (set? x) (seq? x)) x
                                :else [x])))
                          vec)
            test-syms (if (seq target-list)
                        (->>
                          target-list
                          (mapcat
                            (fn [x]
                              (let [s (if (string? x) (symbol x) x)]
                                (cond
                                  (namespace s) [s]
                                  (str/ends-with? (name s) "-test")
                                  (->>
                                    (d/datoms
                                      bdb
                                      :avet
                                      :seon.code.var/ns
                                      s)
                                    (keep
                                      #(let
                                        [v
                                         (d/pull
                                           bdb
                                           '[:seon.code.var/name
                                             :seon.code.var/test?]
                                           (:e %))]
                                        (when
                                          (:seon.code.var/test? v)
                                          (:seon.code.var/name v)))))
                                  :else (code/reaching-tests bdb s)))))
                          distinct
                          sort
                          vec)
                        (let [changed-nss (distinct
                                            (keep
                                              :seon.code.form/ns
                                              (:upserts ops)))]
                          (->>
                            changed-nss
                            (mapcat #(code/reaching-tests bdb %))
                            distinct
                            sort
                            vec)))
            results (mapv
                      #(wsup/run-single-test-var % (str "local:" %))
                      test-syms)
            failures (filterv #(not (:seon.test/pass? %)) results)
            ms (/
                 (Math/round
                   (/ (double (- (System/nanoTime) t0)) 10000.0))
                 100.0)]
        {:ok? (empty? failures),
         :ran (count results),
         :passed (- (count results) (count failures)),
         :failed (count failures),
         :ms ms,
         :failures
         (mapv
           #(select-keys
             %
             [:seon.test/var :seon.test/report :seon.test/ms])
           failures),
         :results
         (mapv
           #(select-keys
             %
             [:seon.test/var :seon.test/pass? :seon.test/ms])
           results)}))))

(def ^:private user-helper-docs
  '{edit-fn!        {:arglists ([target old-subform new-subform] [target old-subform new-subform opts])
                     :doc "Structurally replace old-subform with new-subform in 'ns/fn or 'ns (ignores whitespace/tabs/comments, preserves formatting, persists & live-evals). Opts: {:occurrence N :dry-run? true :eval? true}."}
    add-require!    {:arglists ([nsym spec-or-specs] [nsym spec-or-specs opts])
                     :doc "Idempotently add one or more :require specs (e.g. '[seon.store :as store] or '[[a :as a] [b :as b]]) to nsym, persist #ns, and live-require immediately."}
    set-meta!       {:arglists ([batch-map] [target meta-map] [target meta-map opts])
                     :doc "Insert/merge attr-map (e.g. {:malli/schema [...] :cost/ticks 500000}) onto a defn/defn- right after name + docstring, preserving formatting. Accepts a single 'ns/fn or a batch map {'ns/f1 {...} 'ns/f2 {...}}."}
    ns-requires     {:arglists ([nsym])
                     :doc "Return parsed :require specs of namespace nsym cleanly in ~1 ms without dumping the whole file."}
    grep-src        {:arglists ([pattern] [pattern {:keys [ns limit]}])
                     :doc "Fast linear/scoped grep across all form sources in Datahike (string or regex)."}
    unused-requires {:arglists ([nsym])
                     :doc "AST-accurate analysis of used vs unused :require specs in nsym (skips strings/docstrings/comments)."}
    prune-requires! {:arglists ([nsym] [nsym {:keys [dry-run?]}])
                     :doc "AST-accurately remove unused :require specs from nsym and persist cleanly."}
    run-tests!      {:arglists ([] [target-or-targets])
                     :doc "Run branch-local clojure.test deftest(s) live in-turn. No args = all tests reaching your branch changes; or pass 'ns-test/var, 'ns-test, or 'ns."}
    diff            {:arglists ([] [target])
                     :doc "Return branch upserts, retractions, schemas, health errors, and unified line diffs vs merge-base."}
    context         {:arglists ([] [scope-or-task-or-opts] [target opts])
                     :doc "Render the exact same <walk-context> tiles (ctx-tile, task-tile, ns-tile) + ephemeral tail that internal Seon agents receive. Pass 'ns, :seon.ns/task-id, or {:task :... :scope '... :extra-namespaces [...]}."}
    help            {:arglists ([] [sym])
                     :doc "List all user/* REPL helpers with signatures and docs, or inspect one helper (e.g. (user/help 'edit-fn!))."}
    source          {:arglists ([target-or-vec] [target {:keys [lines]}])
                     :doc "Read source of 'ns/fn, 'ns, 'ns#ns, :seon.schema/key, \"docs/f.md#sec\", 'user/<helper>, or a vector of targets ['a/b 'c/d]. Optional {:lines [start end]} slices a String result."}
    ns-outline      {:arglists ([nsym])
                     :doc "List all forms in nsym with token counts, local deps, and external callers."}
    move-vars!      {:arglists ([{:keys [from to alias doc vars include-deps?]}])
                     :doc "Losslessly split vars from :from into child ns :to, adding :as/:refer and hot-reloading both."}
    register-schema! {:arglists ([schema-key form] [schema-key form opts])
                      :doc "Register/update a Malli schema -> derived Datahike attribute, persisted to resources/seon/schemas.edn on merge."}
    remove-var!     {:arglists ([sym] [sym {:keys [force?]}])
                     :doc "Safely retract a var & form (refuses if callers exist unless {:force? true})."}
    add-fn!         {:arglists ([target-ns form-src] [target-ns form-src opts])
                     :doc "Add or replace a top-level (defn ...)/(def ...) form in target-ns, persist to Datahike, and live-eval."}
    callers         {:arglists ([sym]) :doc "List ALL references to sym (code callers, :seon.schema/render, :seon.agent/render-fn, :seon.agent/tail-renders)."}
    callees         {:arglists ([sym]) :doc "List :refs, :schema-refs, and :keywords of sym."}
    tests-for       {:arglists ([sym-or-ns]) :doc "List all tests reaching sym-or-ns."}
    search          {:arglists ([query]) :doc "Search vars, schemas, and docs by substring."}
    health          {:arglists ([]) :doc "Compute live :seon.health/report on your branch."}
    past-evals      {:arglists ([] [limit-or-opts]) :doc "Query historical evals via Datahike d/history (pass int limit, grep string, or map)."}
    autofix-health! {:arglists ([] [{:keys [ns class dry-run?]}]) :doc "Execute programmatic :fix actions from (user/health) (e.g. prune unused requires) across your branch."}
    inspect-branch  {:arglists ([branch-or-agent-id]) :doc "Inspect a worker's branch diff, health errors, latest auto-proofs, and recent evals in 1 call."}
    save-example!   {:arglists ([agent-id example-id] [agent-id example-id opts]) :doc "Snapshot a real completed agent session into :example/<name> for resources/seon/agents/examples.edn."}
    create-task!    {:arglists ([{:keys [id from-template scope title plan needs tests parent assign? spawn-agent?]}]) :doc "Create a linked task on :main (inheriting :from-template & parent chain) and persist to resources/seon/tasks.edn."}
    update-task!    {:arglists ([{:keys [id title plan scope needs tests summary done?]}]) :doc "Refine your active task's plan, tests, or summary on :main (persisted to resources/seon/tasks.edn)."}
    assign-task!    {:arglists ([task-id]) :doc "Attach a :seon.task/id to your agent entity so @seon.ctx/ctx and <walk-context> pull its full task chain and history."}
    metrics         {:arglists ([] [window]) :doc "Sliding-window task completion, effective turns/merged, and top frustrating tools scorecard."}
    chat!           {:arglists ([msg] [agent-id msg]) :doc "Start or continue a live chat with the entry agent (or any agent-id), resetting Canvas to :live identically to browser chat."}
    monitor         {:arglists ([] [agent-id]) :doc "Inspect any agent's live perspective: parent request, own merge-gate progress, recursive children work tree, recent eval intents, and latest reply."}
    ready!          {:arglists ([]) :doc "Mark branch ready for automated test + 3-way merge + Git write-back (returns map)."}})

(defn- intern-doc! [u sym f]
  (let [target (if (var? f) (fn [& args] (apply @f args)) f)
        v (intern u sym target)
        base-m (get user-helper-docs sym)
        ov (when (var? f) (:seon.eval/budget-override (meta f)))]
    (when (or base-m ov)
      (alter-meta! v merge base-m (when ov {:seon.eval/budget-override ov})))
    v))

(defn help
  "Lists available `user/*` REPL helpers with signatures & one-line docs, or shows one helper in full."
  {:malli/schema [:function
                  [:=> [:cat] :map]
                  [:=> [:cat :any] :map]]}
  ([]
   (into (sorted-map)
         (map (fn [[k {:keys [arglists doc]}]]
                [k (str (pr-str arglists) " — " doc)]))
         user-helper-docs))
  ([sym]
   (let [nm (symbol (name sym))]
     (if-let [info (get user-helper-docs nm)]
       (assoc info :var (symbol "user" (name nm)))
       (if-let [v (ns-resolve 'user nm)]
         (merge {:var (symbol "user" (name nm))} (select-keys (meta v) [:arglists :doc]))
         {:error :unknown-helper :sym sym :available (vec (sort (keys user-helper-docs)))})))))

(defn autofix-health!
  "General programmatic autofix runner over `seon.health/report`: executes every problem carrying a
   `:fix {:fn sym :args [...]}` descriptor (optionally filtered by `:ns`, `:class`, or `{:dry-run? true}`)."
  {:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :map] [:maybe :map]]]}
  ([] (autofix-health! {}))
  ([{:keys [ns class dry-run?]}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [rep (health/report @conn)
           targets (->> (:problems rep)
                        (filter :fix)
                        (filter #(or (nil? ns) (= ns (:ns %)) (health/ns-in-scope? ns (:ns %))))
                        (filter #(or (nil? class) (= class (:class %))))
                        vec)
           results (mapv (fn [p]
                           (let [{:keys [fn args]} (:fix p)
                                 f (or (try (resolve fn) (catch Throwable _ nil))
                                       (try (requiring-resolve fn) (catch Throwable _ nil)))]
                             (if-not f
                               {:ok? false :class (:class p) :subject (:subject p) :error :fix-fn-not-found :fn fn}
                               (try
                                 (let [r (apply f (if dry-run? (concat args [{:dry-run? true}]) args))]
                                   {:ok? (boolean (:ok? r true))
                                    :class (:class p)
                                    :subject (:subject p)
                                    :changed? (boolean (:changed? r true))
                                    :result (dissoc r :diff)})
                                 (catch Throwable ex
                                   {:ok? false :class (:class p) :subject (:subject p)
                                    :error (or (.getMessage ex) (str ex))})))))
                         targets)]
       {:ok? (every? :ok? results)
        :dry-run? (boolean dry-run?)
        :count (count results)
        :results results}))))

(defn inspect-branch
  "Coordinator inspection helper: given a branch keyword (e.g. `:branch/x`) OR an agent keyword
   (e.g. `:ns/seon.render`), returns changed forms, health errors, latest fast proofs, recent evals,
   and compact unified diffs in one O(|Delta|) call."
  {:malli/schema [:function [:=> [:cat] [:maybe :map]] [:=> [:cat :keyword] [:maybe :map]]]}
  ([] (inspect-branch (:branch *agent-ctx*)))
  ([branch-or-agent]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [mdb (store/head conn :main)
           ag-ent (when (first (d/datoms mdb :avet :seon.agent/id branch-or-agent))
                    (d/pull mdb '[:seon.agent/id :seon.agent/branch :seon.agent/scope :seon.agent/goal :seon.agent/task]
                            [:seon.agent/id branch-or-agent]))
           branch (or (:seon.agent/branch ag-ent) branch-or-agent)
           bdb (try (store/head conn branch) (catch Throwable _ nil))]
       (if-not bdb
         {:ok? false :error :branch-not-found :target branch-or-agent :branch branch}
         (let [base-cid (when (not= branch :main)
                          (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
               base-db (when base-cid (d/commit-as-db conn base-cid))
               ops (if base-cid
                     (code/changed-form-ops conn base-cid (d/commit-id bdb))
                     {:upserts [] :retracted-keys #{} :schema-keys #{}})
               touched-nss (vec (distinct (keep :seon.code.form/ns (:upserts ops))))
               errs (if (= branch :main) [] (health/caused-by-branch mdb bdb {} touched-nss))
               aid (or (:seon.agent/id ag-ent)
                       (some (fn [dt]
                               (let [a (d/pull bdb '[:seon.agent/id :seon.agent/branch] (:e dt))]
                                 (when (= branch (:seon.agent/branch a)) (:seon.agent/id a))))
                             (d/datoms bdb :aevt :seon.agent/id)))
               b-ag (when aid (d/pull bdb '[:seon.agent/last-proofs :seon.agent/ready? :seon.agent/waiting?] [:seon.agent/id aid]))
               recent-evals (when aid
                              (->> (d/datoms bdb :avet :seon.agent.eval/agent aid)
                                   (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/handle
                                                       :seon.agent.eval/intent :seon.agent.eval/error
                                                       :seon.agent.eval/ms] (:e %)))
                                   (sort-by #(or (:seon.agent.eval/turn %) 0))
                                   (take-last 6)
                                   vec))
               diffs (into {}
                           (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                                   (let [old-s (when base-db
                                                 (:seon.code.form/src (d/pull base-db '[:seon.code.form/src] [:seon.code.form/key key])))
                                         ud (cst/unified-diff (or old-s "") (or src "") key)]
                                     [key (if (> (count ud) 1200) (str (subs ud 0 1200) "\n…") ud)])))
                           (take 6 (:upserts ops)))]
           {:ok? true
            :agent aid
            :branch branch
            :branch-tx (:max-tx bdb)
            :ready? (boolean (:seon.agent/ready? b-ag))
            :last-proofs (:seon.agent/last-proofs b-ag)
            :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
            :retracted (vec (sort (:retracted-keys ops)))
            :schemas (vec (sort (:schema-keys ops)))
            :health-errors errs
            :recent-evals recent-evals
            :diffs diffs}))))))

(defn save-example!
  "Snapshots a real completed agent session (`source-agent-id`) into a declarative `:example/<name>`
   record tagged `:seon.agent/seed-path \"resources/seon/agents/examples.edn\"` on your current branch.
   Merging writes the curated real-world session cleanly into `resources/seon/agents/examples.edn`."
  {:malli/schema [:function [:=> [:cat :keyword :keyword] [:maybe :map]] [:=> [:cat :keyword :keyword :map] [:maybe :map]]]}
  ([source-agent-id example-id] (save-example! source-agent-id example-id {}))
  ([source-agent-id example-id {:keys [goal max-evals] :or {max-evals 8}}]
   (when-let [{:keys [conn]} *agent-ctx*]
     (if-not (= "example" (namespace example-id))
       {:ok? false :error :invalid-example-id :hint "Example ID must be in the :example/* namespace (e.g. :example/my-workflow)."}
       (let [mdb (store/head conn :main)
             ag (or (when (first (d/datoms mdb :avet :seon.agent/id source-agent-id))
                      (d/pull mdb '[*] [:seon.agent/id source-agent-id]))
                    (d/pull @conn '[*] [:seon.agent/id source-agent-id]))]
         (if-not (:seon.agent/id ag)
           {:ok? false :error :agent-not-found :agent source-agent-id}
           (let [b (:seon.agent/branch ag :main)
                 bdb (or (try (store/head conn b) (catch Throwable _ nil)) @conn)
                 raw-evals (->> (d/datoms bdb :avet :seon.agent.eval/agent source-agent-id)
                                (map #(d/pull bdb '[*] (:e %)))
                                (remove :seon.agent.eval/error)
                                (filter #(not (str/blank? (str (:seon.agent.eval/code %)))))
                                (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0)))
                                (take max-evals)
                                vec)
                 trunc (fn [s n] (let [st (str s)] (if (> (count st) n) (str (subs st 0 n) "…") st)))
                 ex-ent (cond-> {:seon.agent/id example-id
                                 :seon.agent/branch :main
                                 :seon.agent/role (or (:seon.agent/role ag) :worker)
                                 :seon.agent/goal (or goal (:seon.agent/goal ag) "Curated real session example.")
                                 :seon.agent/paused? true
                                 :seon.agent/waiting? true
                                 :seon.agent/seed-path "resources/seon/agents/examples.edn"}
                          (:seon.agent/scope ag) (assoc :seon.agent/scope (:seon.agent/scope ag)))
                 eval-ents (vec
                            (map-indexed
                             (fn [idx ev]
                               (let [turn (inc idx)
                                     eid (hasch/uuid [:seed-eval example-id turn 0])]
                                 (cond-> {:seon.agent.eval/id eid
                                          :seon.agent.eval/agent example-id
                                          :seon.agent.eval/turn turn
                                          :seon.agent.eval/call-index 0
                                          :seon.agent.eval/call-id (str "ex-" (name example-id) "-" turn)
                                          :seon.agent.eval/code (trunc (:seon.agent.eval/code ev) 1200)
                                          :seon.agent.eval/value (trunc (:seon.agent.eval/value ev) 600)
                                          :seon.agent.eval/out (trunc (:seon.agent.eval/out ev "") 200)
                                          :seon.agent.eval/handle (symbol "result" (str "e" turn))
                                          :seon.agent.eval/ms (double (or (:seon.agent.eval/ms ev) 1.0))
                                          :seon.agent.eval/ticks (long (or (:seon.agent.eval/ticks ev) 50000))}
                                   (not (str/blank? (str (:seon.agent.eval/intent ev))))
                                   (assoc :seon.agent.eval/intent (str (:seon.agent.eval/intent ev)))
                                   (not (str/blank? (str (:seon.agent.eval/thought ev))))
                                   (assoc :seon.agent.eval/thought (trunc (:seon.agent.eval/thought ev) 300))
                                   (:seon.agent.eval/result-schema ev)
                                   (assoc :seon.agent.eval/result-schema (:seon.agent.eval/result-schema ev)))))
                             raw-evals))
                 r (d/transact conn {:tx-data (into [ex-ent] eval-ents)})]
             (ctx/refresh-active! (:db-after r) (:tx-data r))
             {:ok? true :example-id example-id :source-agent source-agent-id
              :evals-saved (count eval-ents) :seed-path "resources/seon/agents/examples.edn"
              :tx (:max-tx (:db-after r))})))))))

(defn create-task!
  "Creates a task on `:main` via `:transactor` (automatically written back to `resources/seon/tasks.edn`).
   Supports `:from-template :template/...` to inherit `:scope`, `:plan`, `:tests`, and `:needs`,
   automatically links `:parent` to the calling agent's task when omitted, and optionally assigns
   the new task to the calling agent when `:assign? true`."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [id from-template scope title plan needs tests spawn-agent? agent-id role auto-merge?
           parent template? owner created-by summary assign?]}]
  (when-let [{:keys [conn] cur-aid :agent-id} *agent-ctx*]
    (let [mdb (store/head conn :main)
          cur-task (when cur-aid
                     (or (:seon.agent/task (d/pull @conn '[:seon.agent/task] [:seon.agent/id cur-aid]))
                         (:seon.agent/task (d/pull mdb '[:seon.agent/task] [:seon.agent/id cur-aid]))))
          tpl (when (and from-template (first (d/datoms mdb :avet :seon.task/id from-template)))
                (d/pull mdb '[:seon.task/scope :seon.task/plan :seon.task/tests :seon.task/needs]
                        [:seon.task/id from-template]))
          eff-scope (or scope (:seon.task/scope tpl))
          eff-plan (if (and (:seon.task/plan tpl) (not (str/blank? (str plan))))
                     (str plan "\n\n---\n" (:seon.task/plan tpl))
                     (or plan (:seon.task/plan tpl) ""))
          eff-tests (into (set (:seon.task/tests tpl)) tests)
          eff-needs (into (set (:seon.task/needs tpl)) needs)
          eff-parent (or parent (when (and (not template?) (qualified-keyword? cur-task) (not= cur-task id)) cur-task))
          req (cond-> {:op :create-task
                       :id id
                       :title (or title (name id))
                       :plan eff-plan}
                eff-scope (assoc :scope eff-scope)
                eff-parent (assoc :parent eff-parent)
                (some? template?) (assoc :template? (boolean template?))
                from-template (assoc :template from-template)
                owner (assoc :owner owner)
                (or created-by cur-aid) (assoc :created-by (or created-by cur-aid))
                (string? summary) (assoc :summary summary)
                (seq eff-tests) (assoc :tests (vec eff-tests))
                (seq eff-needs) (assoc :needs (vec eff-needs))
                (some? spawn-agent?) (assoc :spawn-agent? (boolean spawn-agent?))
                (and spawn-agent? cur-aid) (assoc :parent-agent cur-aid)
                agent-id (assoc :agent-id agent-id)
                role (assoc :role role)
                (some? auto-merge?) (assoc :auto-merge? (boolean auto-merge?)))
          ans (fcalls/tx-main-sync! req)]
      (when (and (:ok? ans) assign? cur-aid)
      ((resolve 'user/assign-task!) id))
      ans)))

(defn metrics
  "Returns the live anti-gaming sliding-window task completion & tool-frustration scorecard
   over the latest `window` (default 10) autonomous Seon agents."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe :map]]
                  [:=> [:cat :int] [:maybe :map]]]
   :seon.eval/budget-override {:ticks 300000000 :bytes 1073741824}}
  ([] (metrics 10))
  ([window]
   (when-let [{:keys [conn]} *agent-ctx*]
     (let [mdb (or (try (store/head conn :main) (catch Throwable _ nil)) @conn)]
       (hmetrics/agent-learning-metrics conn mdb {:window window})))))

(defn install-repl-helpers!
  "Interns all `user/*` code-editing, inspection, test, and task helpers into `u` via live var
   delegation (preserving `:doc`, `:arglists`, and `:seon.eval/budget-override` across hot-reloads)."
  {:malli/schema [:=> [:cat :seon.host/ns] :any]}
  [u]
  (intern-doc! u 'add-fn! #'edit/add-fn!)
  (intern-doc! u 'edit-fn! #'edit/edit-fn!)
  (intern-doc! u 'add-require! #'edit/add-require!)
  (intern-doc! u 'set-meta! #'edit/set-meta!)
  (intern-doc! u 'ns-requires #'edit/ns-requires)
  (intern-doc! u 'unused-requires #'edit/unused-requires)
  (intern-doc! u 'prune-requires! #'edit/prune-requires!)
  (intern-doc! u 'diff #'edit/diff)
  (intern-doc! u 'grep-src #'grep-src)
  (intern-doc! u 'run-tests! #'run-tests!)
  (intern-doc! u 'help #'help)
  (intern-doc! u 'autofix-health! #'autofix-health!)
  (intern-doc! u 'inspect-branch #'inspect-branch)
  (intern-doc! u 'save-example! #'save-example!)
  (intern-doc! u 'create-task! #'create-task!)
  (intern-doc! u 'metrics #'metrics))