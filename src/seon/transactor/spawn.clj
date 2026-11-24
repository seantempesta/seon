(ns seon.transactor.spawn
  "Extracted child namespace of `seon.transactor`."
  (:require [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.store :as store]
            [seon.transactor.calls :as tcalls]
            [seon.transactor.merge :as tmerge]
            [seon.transactor.user :as user :refer [norm-ref show-user-tx]]
            [seon.fork.worker-support :as wsup]))

(defn focus-tx
  {:doc "Returns the focus-namespace parent-chain tx data for `scope`, or nil.", :malli/schema [:=> [:cat [:maybe :symbol]] [:maybe [:vector :any]]], :cost/ticks 5000}
  [scope] (when scope (code/ns-parent-chain-tx scope)))

(defn- branch-for [aid rb]
  (if (and rb (not= :main rb))
    rb
    (keyword "branch" (str (or (namespace aid) "agent") "." (name aid)))))

(defn msg-ent
  {:doc "Builds a `:seon.agent.message/*` entity map for a message id/seq/from/to/type/body.", :malli/schema [:=> [:cat :any :any :any :any :any :any] :map], :cost/ticks 5000, :cost/bytes 16384, :examples [[[:m 1 :user :agent/root :chat "hi"] {:seon.agent.message/id :m, :seon.agent.message/seq 1, :seon.agent.message/from :user, :seon.agent.message/to :agent/root, :seon.agent.message/type :chat, :seon.agent.message/body "hi"}]]}
  [mid mseq from to mtype body]
  {:seon.agent.message/id mid :seon.agent.message/seq mseq
   :seon.agent.message/from (or from :user) :seon.agent.message/to to
   :seon.agent.message/type (or mtype :chat) :seon.agent.message/body (str (or body ""))})

(defn commit-res
  {:doc "Commits a Datahike transact result into the transactor state, bumping :writes and emitting a :db commit notice.", :malli/schema [:=> [:cat :map :any :map :any [:? [:maybe :map]] [:? [:maybe :map]]] [:tuple :map :map]], :seon.flow/edge true}
  [st prev-cid r reason & [extra-out extra-st]]
  (let [db (:db-after r) cid (d/commit-id db)]
    [(-> (merge st extra-st) (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
     (merge {:db [(tmerge/db-notice prev-cid db (:tx-data r) reason)]} extra-out)]))

(defn git-wb
  {:doc "Builds the optional `:git` write-back out for a committed file path (nil when git write-back is off).", :malli/schema [:=> [:cat :map :any :string :string] [:maybe :map]], :cost/ticks 5000}
  [st cid path message]
  (when (and (:commit-git? st) (:repo-dir st))
    {:git [{:op :write-back :branch :main :commit cid :touched-paths #{path} :message message}]}))

(defn- derive-focus-scope
  {:doc "Derives the focus namespace symbol for a spawned agent from a `[attr value]` focus ref and its entity.", :malli/schema [:=> [:cat [:tuple :keyword :any] [:maybe :map]] [:maybe :symbol]], :cost/ticks 20000}
  [[fa fv] ent]
  (let [ns-of #(when (and (symbol? %) (namespace %)) (symbol (namespace %)))]
    (or (some #(when (symbol? %) %)
              [(:seon.code.var/ns ent) (:seon.code.ns/name ent) (:seon.code.form/ns ent)
               (:seon.task/scope ent) (get-in ent [:seon.error/data :ns]) (:seon.agent/scope ent)])
        (some ns-of [(:seon.error/var ent) (:seon.test/var ent) (:seon.fix/regression-test ent)])
        (when (= fa :seon.code.var/name) (ns-of fv))
        (when (and (= fa :seon.code.ns/name) (symbol? fv)) fv))))

(defn- focus-agent-id
  {:doc "Derives a unique `:agent/*` id keyword for a focus ref/entity, suffixing `-n` on collisions.", :malli/schema [:=> [:cat :seon.host/db [:tuple :any :any] [:maybe :map]] :keyword], :cost/ticks 50000}
  [db [fa fv] ent]
  (let [raw (or (some #(when-let [v (get ent %)] (if (ident? v) (name v) (str v)))
                      [:seon.code.var/name :seon.code.ns/name :seon.task/id
                       :seon.rule/id :seon.schema/key :seon.error/kind])
                (when (= :seon.system/id fa) (str "system-" (if (ident? fv) (name fv) fv)))
                (if (ident? fv) (name fv) (str fv)))
        s1 (-> (str/lower-case (str raw)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))
        base (if (str/blank? s1) "focus" (subs s1 0 (min 20 (count s1))))]
    (loop [n 1]
      (let [cand (keyword "agent" (if (= n 1) base (str base "-" n)))]
        (if (first (d/datoms db :avet :seon.agent/id cand)) (recur (inc n)) cand)))))

(defn tx!
  {:doc "Transacts `tx-data` on `conn` after expanding `:seon.agent/*` ref mirrors, returning the transact result.", :malli/schema [:=> [:cat :seon.host/conn [:sequential :any]] :map], :seon.flow/edge true}
  [conn tx-data] (d/transact conn {:tx-data (tcalls/with-ref-mirrors @conn tx-data)}))

(defn handle-spawn-on-entity!
  {:doc "Spawns an agent focused on an existing Datahike entity, creating a focus task and switching the user view.", :malli/schema [:=> [:cat :map :map :any] [:tuple :map [:maybe :map]]], :seon.flow/edge true}
  [st msg prev-cid]
  (let [conn (:conn st) db0 @conn dh (:schema db0) fref (norm-ref (:focus-ref msg))]
    (if-not fref
      (do (tmerge/reply! msg {:ok? false :error "Invalid :focus-ref"}) [st nil])
      (let [[fa fv] fref
            feid (cond (= fa :db/id) (when (integer? fv) fv)
                       (contains? dh fa) (:e (first (d/datoms db0 :avet fa fv))) :else nil)
            sys-up (when (and (nil? feid) (= fa :seon.system/id) (contains? dh :seon.system/id))
                     {:db/id "focus/system" :seon.system/id fv
                      :seon.system/title (if (= fv :root) "Seon Runtime" (str "System " (name fv)))})]
        (if (and (nil? feid) (nil? sys-up) (not (#{:seon.signal/kind :seon.branch/name} fa)))
          (do (tmerge/reply! msg {:ok? false :error (str "Entity not found: " (pr-str fref))}) [st nil])
          (let [ent (or (when feid (d/pull db0 '[*] feid)) sys-up)
                tref (or feid (when sys-up "focus/system"))
                aid (or (:id msg) (focus-agent-id db0 fref ent))
                b (branch-for aid (:branch msg))
                scope (or (:scope msg) (derive-focus-scope fref ent))
                lbl (or (some #(when-let [v (get ent %)] (not-empty (str v)))
                              [:seon.code.var/name :seon.code.ns/name :seon.task/title
                               :seon.task/id :seon.error/kind :seon.system/title]) (str fv))
                prompt (str/trim (str (or (:prompt msg) "")))
                body (if (str/blank? prompt) (str "Focus on " (pr-str fref) " (" lbl ").") prompt)
                is-task? (or (= fa :seon.task/id) (some? (:seon.task/id ent)))
                eff-task (if is-task? (or (:seon.task/id ent) fv) (keyword "seon.focus" (name aid)))
                task-tid (str "task/" (namespace eff-task) "/" (name eff-task))
                task-op (when-not is-task?
                          (cond-> {:db/id task-tid :seon.task/id eff-task :seon.task/title (str "Focus: " lbl)
                                   :seon.task/plan body :seon.task/done? false}
                            scope (assoc :seon.task/scope scope)
                            (contains? dh :seon.task/owner) (assoc :seon.task/owner aid :seon.task/created-by (or (:parent msg) :user))
                            (contains? dh :seon.task/curated) (assoc :seon.task/curated [fref])))
                mseq (inc (:msg-seq st)) mid (random-uuid)
                ag-ent (cond-> {:db/id "agent/new" :seon.agent/id aid :seon.agent/branch b
                                
                                :seon.agent/goal (if (str/blank? prompt) (str "Focus on " lbl) prompt)
                                :seon.agent/parent (or (:parent msg) :user)
                                :seon.agent/pinned [fref]}
                         scope (assoc :seon.agent/scope scope :seon.agent/focus (str "ns/" scope))
                         (and tref (contains? dh :seon.agent/target)) (assoc :seon.agent/target tref)
                         (contains? dh :seon.agent/tasks) (assoc :seon.agent/tasks #{(or (:e (first (d/datoms db0 :avet :seon.task/id eff-task))) task-tid)})
                         (some? (:auto-merge? msg)) (assoc :seon.agent/auto-merge? (boolean (:auto-merge? msg))))
                u-ops (when (contains? dh :seon.user/id)
                        (:tx-data (show-user-tx db0 {:user-id (or (:user-id msg) :user/default)
                                                     :viewing-ref "agent/new" :mode :session
                                                     :clear-inspect true :as-of-tx :live})))
                r (tx! conn (vec (concat (when scope (focus-tx scope))
                  (remove nil? [sys-up task-op ag-ent (msg-ent mid mseq (:from msg) aid :chat body)])
                  u-ops)))
                _ (store/branch! conn :main b)
                db (:db-after r)
                res-t (or feid (when (contains? dh fa) (:e (first (d/datoms db :avet fa fv)))))]
            (tmerge/reply! msg (cond-> {:ok? true :agent aid :branch b :task eff-task :focus-ref fref :message-id mid :tx (:max-tx db)}
                                 scope (assoc :scope scope) res-t (assoc :target res-t)))
            (commit-res st prev-cid r [:spawn-on-entity aid fref]
                        {::flow/report [(str "[:transactor] spawned " aid " on " b " focused on " (pr-str fref))]}
                        {:msg-seq mseq})))))))

(defn handle-spawn-agent!
  "Spawns a normal or COW-forked agent entity (branch, matched/template tasks, parent, target, fork-from, prefix-mode, merge-target, examples) and commits it, replying to the caller."
  {:malli/schema [:=> [:cat :map :map :any] [:tuple :map [:maybe :map]]] :seon.flow/edge true}
  [st msg prev-cid]
  (let [conn (:conn st) db0 @conn dh (:schema db0)
        {:keys [id parent scope branch router? interactive? goal task tasks target examples config pause-parent?]} msg
        b (branch-for id branch) mseq (inc (:msg-seq st))
        fspec (tcalls/resolve-fork-spec conn db0 msg)
        resolve-agent-eid (:resolve-eid fspec)
        fork-eid (:fork-eid fspec) fork-parent (:fork-parent fspec)
        fork-src-branch (:src-branch fspec) fork-commit (:fork-commit fspec)
        auto-merge? (:auto-merge? fspec)
        given-tasks (vec (distinct (concat (when task [task]) (when (seq tasks) (vec tasks)))))
        blank? (and (str/blank? (str goal)) (empty? given-tasks) (nil? scope) (nil? fork-eid))
        matched (when (and (not blank?) (empty? given-tasks) (nil? fork-eid))
                  (tcalls/best-task-for-scope db0 scope))
        sub (when (and (not blank?) (empty? given-tasks) (nil? matched) (nil? fork-eid)
                       (first (d/datoms db0 :avet :seon.task/id :template/discover-and-plan)))
              (tcalls/build-subtask-tx db0 (or parent :user) id (random-uuid) scope
                {:title (or (not-empty (str goal)) (str "Discover & plan " (or scope id)))
                 :plan (str (or goal "")) :from-template :template/discover-and-plan}))
        eff-tasks (cond (seq given-tasks) given-tasks matched [matched] (:task-id sub) [(:task-id sub)] :else [])
        eff-task (first eff-tasks)
        eff-scope (or scope
                      (when eff-task (:seon.task/scope (d/pull db0 '[:seon.task/scope] [:seon.task/id eff-task])))
                      (:seon.agent/scope fork-parent))
        ag0 (merge (cond-> {:seon.agent/id id :seon.agent/branch b :seon.agent/goal (or goal "")} router? (assoc :seon.agent/router? true) interactive? (assoc :seon.agent/interactive? true)) config)
        ag (let [task-refs (when (and (seq eff-tasks) (contains? dh :seon.agent/tasks))
                             (into [] (keep (fn [tid]
                                              (or (:e (first (d/datoms db0 :avet :seon.task/id tid)))
                                                  (some #(when (= tid (:seon.task/id %)) (:db/id %)) (:tx sub)))))
                                   eff-tasks))
                 target-ref (when (and (or target fork-eid) (contains? dh :seon.agent/target))
                              (or (when target (resolve-agent-eid target)) fork-eid))
                 ex-kws (when (seq examples) (into #{} (filter qualified-keyword?) examples))
                 ex-refs (when (and (seq examples) (contains? dh :seon.agent/example-refs))
                           (into #{} (keep resolve-agent-eid) examples))]
             (cond-> ag0
               (some? auto-merge?) (assoc :seon.agent/auto-merge? (boolean auto-merge?))
               blank? (assoc :seon.agent/pinned (vec (distinct (conj (vec (:seon.agent/pinned ag0)) [:seon.system/id :root]))))
               parent (assoc :seon.agent/parent parent)
               eff-scope (assoc :seon.agent/scope eff-scope :seon.agent/focus (str "ns/" eff-scope))
               
               (seq task-refs) (assoc :seon.agent/tasks (set task-refs))
               target-ref (assoc :seon.agent/target target-ref)
               fork-eid (assoc :seon.agent/forked-from fork-eid)
               fork-commit (assoc :seon.agent/fork-commit fork-commit)
               
               (some? (:merge-target fspec)) (assoc :seon.agent/merge-target (:merge-target fspec))
               (seq ex-kws) (assoc :seon.agent/examples ex-kws)
               (seq ex-refs) (assoc :seon.agent/example-refs ex-refs)))
        tx-data (vec (concat (when eff-scope (focus-tx eff-scope)) (:tx sub)
                             (remove nil? [(when (and pause-parent? (:seon.agent/id fork-parent))
                                             {:seon.agent/id (:seon.agent/id fork-parent) :seon.agent/paused? true})
                                           ag (when-not (str/blank? (str goal))
                                                (msg-ent (random-uuid) mseq parent id :delegate goal))])))
        r (tx! conn tx-data)
        _ (store/branch! conn fork-src-branch b)
        _ (when (not= fork-src-branch :main)
            (let [bconn (store/connect (:cfg st) b)]
              (try
                (wsup/sync-missing-main-schema! bconn b)
                (let [bdb @bconn mdb (:db-after r)
                      seed-tasks (vec (keep (fn [tid]
                                              (when-not (first (d/datoms bdb :avet :seon.task/id tid))
                                                (let [t (dissoc (d/pull mdb '[*] [:seon.task/id tid]) :db/id :seon.task/test-vars :seon.task/proofs)]
                                                  (when (:seon.task/id t) t))))
                                            eff-tasks))
                      _ (when (seq seed-tasks) (tx! bconn seed-tasks))
                      bdb2 @bconn
                      explicit-target-id (when target
                                           (or (when (qualified-keyword? target) target)
                                               (when-let [te (resolve-agent-eid target)]
                                                 (:seon.agent/id (d/pull mdb '[:seon.agent/id] te)))))
                      eff-target-id (or explicit-target-id (:seon.agent/id fork-parent))
                      target-on-b? (and eff-target-id (boolean (first (d/datoms bdb2 :avet :seon.agent/id eff-target-id))))
                      ex-refs-on-b (when (seq examples)
                                     (into #{} (keep (fn [ex]
                                                       (let [aid (cond (qualified-keyword? ex) ex
                                                                       (integer? ex) (:seon.agent/id (d/pull mdb '[:seon.agent/id] ex))
                                                                       (and (vector? ex) (= :seon.agent/id (first ex))) (second ex))]
                                                         (when (and aid (first (d/datoms bdb2 :avet :seon.agent/id aid)))
                                                           [:seon.agent/id aid]))))
                                           examples))
                      b-ag (-> ag
                               (dissoc :seon.agent/tasks :seon.agent/target :seon.agent/forked-from :seon.agent/example-refs :seon.agent/merge-target)
                               (cond-> (seq eff-tasks) (assoc :seon.agent/tasks (mapv #(vector :seon.task/id %) eff-tasks))
                                       (:seon.agent/id fork-parent) (assoc :seon.agent/forked-from [:seon.agent/id (:seon.agent/id fork-parent)])
                                       target-on-b? (assoc :seon.agent/target [:seon.agent/id eff-target-id])
                                       (seq ex-refs-on-b) (assoc :seon.agent/example-refs ex-refs-on-b)))
                      b-tx (vec (concat (when eff-scope (focus-tx eff-scope)) [b-ag]))]
                  (tx! bconn b-tx))
                (finally (d/release bconn)))))]
    (tmerge/reply! msg (cond-> {:ok? true :agent id :branch b :scope eff-scope :tx (:max-tx (:db-after r))}
                         eff-task (assoc :task eff-task)
                         (seq eff-tasks) (assoc :tasks eff-tasks)
                         (:seon.agent/id fork-parent) (assoc :forked-from (:seon.agent/id fork-parent) :fork-commit fork-commit)))
    (commit-res st prev-cid r [:spawn-agent id]
                {::flow/report [(str "[:transactor] branched " b " from " fork-src-branch " for agent " id
                                     (when eff-task (str " (task " eff-task ")")))]}
                {:msg-seq mseq})))

(defn handle-update-task!
  {:doc "Updates an existing `:seon.task/*` entity's fields/needs/tests and commits the change.", :malli/schema [:=> [:cat :map :map :any] [:tuple :map [:maybe :map]]], :seon.flow/edge true}
  [st msg prev-cid]
  (let [conn (:conn st) db0 @conn
        {:keys [id title plan scope needs tests summary done? owner]} msg]
    (if-not (and id (first (d/datoms db0 :avet :seon.task/id id)))
      (do (tmerge/reply! msg {:ok? false :error (str "Task not found: " id)}) [st nil])
      (let [ext? (contains? (:schema db0) :seon.task/owner)
            ent (cond-> {:seon.task/id id}
                  (string? title) (assoc :seon.task/title title) (string? plan) (assoc :seon.task/plan plan)
                  scope (assoc :seon.task/scope scope) (some? done?) (assoc :seon.task/done? (boolean done?))
                  (and ext? (string? summary)) (assoc :seon.task/summary summary)
                  (and ext? owner (qualified-keyword? owner)) (assoc :seon.task/owner owner))
            r (tx! conn (remove nil? [ent
              (when (seq needs) {:seon.task/id id :seon.task/needs (set needs)})
              (when (seq tests) {:seon.task/id id :seon.task/tests (set tests)})]))
            db (:db-after r)]
        (tmerge/reply! msg {:ok? true :task id :tx (:max-tx db)})
        (commit-res st prev-cid r [:update-task id]
                    (merge {::flow/report [(str "[:transactor] updated task " id)]}
                           (git-wb st (d/commit-id db) "resources/seon/tasks.edn" (str "Update task " id))))))))

(defn handle-create-task!
  {:doc "Creates a `:seon.task/*` entity (expanding optional `:from-template` and forwarding normalized `:curated` lookup refs, optionally spawning its owning agent on a new branch) and commits it.",
   :malli/schema [:=> [:cat :map :map :any] [:tuple :map [:maybe :map]]], :seon.flow/edge true}
  [st msg prev-cid]
  (let [conn (:conn st) db0 @conn dh (:schema db0)
        {:keys [id scope title plan needs tests curated from-template spawn-agent? agent-id router? auto-merge? parent template? template owner created-by summary parent-agent]} msg
        tpl-id (or from-template template)
        tpl (when (and from-template (qualified-keyword? from-template) (contains? dh :seon.task/id)
                       (first (d/datoms db0 :avet :seon.task/id from-template)))
              (d/pull db0 '[:seon.task/scope :seon.task/plan :seon.task/tests :seon.task/needs :seon.task/curated]
                      [:seon.task/id from-template]))
        eff-scope (or scope (:seon.task/scope tpl))
        raw-plan (str (or plan ""))
        tpl-plan (:seon.task/plan tpl)
        eff-plan (cond
                   (and tpl-plan (not (str/blank? raw-plan)) (not (str/includes? raw-plan "\n---\n")))
                   (str (str/trim raw-plan) "\n\n---\n" tpl-plan)
                   (not (str/blank? raw-plan)) raw-plan
                   :else (or tpl-plan ""))
        eff-needs (into (set (:seon.task/needs tpl)) needs)
        eff-tests (into (set (:seon.task/tests tpl)) tests)
        eff-curated (vec (distinct (concat (keep norm-ref (:seon.task/curated tpl)) (keep norm-ref curated))))
        aid (or agent-id (keyword (namespace id) (name id)))
        b (branch-for aid nil) mseq (inc (:msg-seq st))
        ext? (contains? dh :seon.task/owner)
        tx-data (cond-> (vec (concat (when (and spawn-agent? eff-scope) (focus-tx eff-scope))
                                     [(cond-> {:db/id (str "task/" (namespace id) "/" (name id))
                                               :seon.task/id id :seon.task/title (or title (name id))
                                               :seon.task/plan eff-plan :seon.task/done? false}
                                        eff-scope (assoc :seon.task/scope eff-scope)
                                        (and ext? (some? template?)) (assoc :seon.task/template? (boolean template?))
                                        (and ext? tpl-id (qualified-keyword? tpl-id)) (assoc :seon.task/template tpl-id)
                                        (and ext? (or owner (when spawn-agent? aid))) (assoc :seon.task/owner (or owner aid))
                                        (and ext? created-by) (assoc :seon.task/created-by created-by)
                                        (and ext? (string? summary)) (assoc :seon.task/summary summary)
                                        (and (contains? dh :seon.task/curated) (seq eff-curated)) (assoc :seon.task/curated eff-curated))
                                      (when (seq eff-needs) {:seon.task/id id :seon.task/needs eff-needs})
                                      (when (seq eff-tests) {:seon.task/id id :seon.task/tests eff-tests})
                                      (when (and parent (qualified-keyword? parent) (not= parent id)
                                                 (first (d/datoms db0 :avet :seon.task/id parent))
                                                 (not (:seon.task/template? (d/pull db0 '[:seon.task/template?] [:seon.task/id parent]))))
                                        {:seon.task/id parent :seon.task/needs #{id}})]))
                  spawn-agent?
                  (conj (cond-> {:seon.agent/id aid :seon.agent/branch b :seon.agent/goal (or title (name id))}
                          (contains? dh :seon.agent/tasks) (assoc :seon.agent/tasks #{(or (:e (first (d/datoms db0 :avet :seon.task/id id))) (str "task/" (namespace id) "/" (name id)))})
                          router? (assoc :seon.agent/router? true)
                          parent-agent (assoc :seon.agent/parent parent-agent)
                          (some? auto-merge?) (assoc :seon.agent/auto-merge? (boolean auto-merge?))
                          eff-scope (assoc :seon.agent/scope eff-scope :seon.agent/focus (str "ns/" eff-scope)))
                        (msg-ent (random-uuid) mseq parent-agent aid :task (str (or title (name id)) "\n" eff-plan))))
        r (tx! conn (remove nil? tx-data))
        _ (when spawn-agent? (store/branch! conn :main b))
        db (:db-after r) cid (d/commit-id db)]
    (tmerge/reply! msg {:ok? true :task id :agent (when spawn-agent? aid) :branch (when spawn-agent? b) :tx (:max-tx db)})
    (commit-res st prev-cid r [:create-task id]
                (merge {::flow/report [(str "[:transactor] created task " id (when spawn-agent? (str " + agent " aid " on " b)))]}
                       (git-wb st cid "resources/seon/tasks.edn" (str "Create task " id)))
                (when spawn-agent? {:msg-seq mseq}))))