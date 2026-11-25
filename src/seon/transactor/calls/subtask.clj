(ns seon.transactor.calls.subtask
  "Extracted child namespace of `seon.transactor.calls`."
  (:require [datahike.api :as d]
            [seon.store :as store]
            [clojure.string :as str]
            [seon.task.gate :as gate]))

(defn infer-ns-scope
  {:malli/schema [:=> [:cat :seon.host/db :any :any] [:maybe :symbol]], :cost/ticks 500000}
  [db callee explicit-scope]
  (or explicit-scope
      (when (and (keyword? callee) (= "ns" (namespace callee)))
        (let [cand (symbol (name callee))]
          (when (first (d/datoms db :avet :seon.code.ns/name cand))
            cand)))))

(defn format-call-request-body
  {:malli/schema [:=> [:cat :any :any :any :any] :string], :cost/ticks 200000, :cost/bytes 65536}
  [call-id caller prompt result-schema]
  (str "Structured call from " caller " (call-id: " (pr-str call-id) ")\n"
       (when result-schema
         (str "Required result schema: " (pr-str result-schema) "\n"))
       "\n" prompt "\n\n"
       "When finished, settle this call via:\n"
       "  (user/answer! " (pr-str call-id) " <value>)\n"
       "or if out of scope / blocked:\n"
       "  (user/fail! " (pr-str call-id) " {:class :out-of-scope :message \"...\"})"))

(defn call-summary-map
  {:malli/schema [:=> [:cat [:maybe :map]] :map], :cost/ticks 50000, :cost/bytes 16384}
  [c]
  (cond-> {:call-id (:seon.agent.call/id c)
           :callee (:seon.agent.call/callee c)
           :status (:seon.agent.call/status c)
           :ok? (= :resolved (:seon.agent.call/status c))}
    (contains? c :seon.agent.call/result) (assoc :result (:seon.agent.call/result c))
    (contains? c :seon.agent.call/error)  (assoc :error (:seon.agent.call/error c))))

(defn format-call-results-body
  {:malli/schema [:=> [:cat [:maybe [:sequential [:maybe :map]]]] :string], :cost/ticks 500000, :cost/bytes 131072}
  [calls]
  (let [by-id (into {} (map (fn [c] [(:seon.agent.call/id c) (call-summary-map c)])) calls)]
    (str "Resolved call results (" (count calls) "):\n"
         (pr-str by-id))))

(defn best-task-for-scope
  "Returns the most relevant open non-template `:seon.task/id` for `scope`, if one exists."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :symbol]] [:maybe :qualified-keyword]]}
  [db scope]
  (when (and db scope (contains? (:schema db) :seon.task/scope))
    (let [has-tpl? (contains? (:schema db) :seon.task/template?)]
      (->> (d/datoms db :avet :seon.task/scope scope)
           (map #(d/pull db (cond-> [:seon.task/id :seon.task/done?]
                              has-tpl? (conj :seon.task/template?)) (:e %)))
           (filter (fn [t]
                     (and (:seon.task/id t)
                          (not (:seon.task/done? t))
                          (not (:seon.task/template? t)))))
           (sort-by #(str (:seon.task/id %)))
           first
           :seon.task/id))))

(defn build-subtask-tx
  "Builds a linked child `:seon.task/*` entity for a delegated call and adds it to the caller's
   active task's `:seon.task/needs` set (one DAG attribute for parent <-> child chains)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword :keyword :uuid [:maybe :symbol] :map] [:maybe :map]]}
  [db caller callee call-id scope {:keys [task title plan from-template tests needs prompt role]}]
  (let [has-ext? (contains? (:schema db) :seon.task/owner)
        has-tpl-seed? (and (contains? (:schema db) :seon.task/id)
                           (or (first (d/datoms db :avet :seon.task/id :template/subtask-code))
                           (first (d/datoms db :avet :seon.task/id :template/subtask-research))
                           (first (d/datoms db :avet :seon.task/id :template/discover-and-plan))))
        caller-task (when (and caller (not= caller :user) (first (d/datoms db :avet :seon.agent/id caller)))
                      (some (fn [dt] (:v (first (d/datoms db :eavt (:v dt) :seon.task/id)))) (when (contains? (:schema db) :seon.agent/tasks) (d/datoms db :eavt (:e (first (d/datoms db :avet :seon.agent/id caller))) :seon.agent/tasks))))
        caller-is-real? (and caller-task (qualified-keyword? caller-task)
                             (first (d/datoms db :avet :seon.task/id caller-task))
                             (not (:seon.task/template? (d/pull db '[:seon.task/template?] [:seon.task/id caller-task]))))]
    (cond
      (and task (first (d/datoms db :avet :seon.task/id task)))
      {:task-id task
       :tx (if (and caller-is-real? (not= caller-task task))
             [{:seon.task/id caller-task :seon.task/needs #{task}}]
             [])}

      (and has-ext? (or task title plan from-template has-tpl-seed?))
      (let [default-tpl (if (= role :research) :template/subtask-research :template/subtask-code)
            tpl-id (or from-template
                       (when (first (d/datoms db :avet :seon.task/id default-tpl)) default-tpl)
                       (when (first (d/datoms db :avet :seon.task/id :template/discover-and-plan)) :template/discover-and-plan))
            tpl (when (and tpl-id (first (d/datoms db :avet :seon.task/id tpl-id)))
                  (d/pull db '[:seon.task/plan :seon.task/tests :seon.task/needs :seon.task/scope]
                          [:seon.task/id tpl-id]))
            tid (or task (keyword (str "subtask." (name callee)) (str "c-" (subs (str call-id) 0 8))))
            raw-prompt (str/trim (str (or prompt "")))
            eff-title (or (not-empty (str/trim (str (or title ""))))
                          (when-not (str/blank? raw-prompt)
                            (let [line (first (str/split-lines raw-prompt))]
                              (if (> (count line) 84) (str (subs line 0 81) "…") line)))
                          (str "Subtask for " callee))
            body-plan (or (not-empty (str/trim (str (or plan "")))) raw-prompt)
            eff-plan (cond
                       (and (:seon.task/plan tpl) (not (str/blank? body-plan)))
                       (str body-plan "\n\n---\n" (:seon.task/plan tpl))
                       (not (str/blank? body-plan)) body-plan
                       :else (or (:seon.task/plan tpl) ""))
            eff-scope (or scope (:seon.task/scope tpl))
            eff-tests (into (set (:seon.task/tests tpl)) tests)
            eff-needs (into (set (:seon.task/needs tpl)) needs)
            ent (cond-> {:db/id (str "task/" (namespace tid) "/" (name tid)) :seon.task/id tid :seon.task/title eff-title :seon.task/plan eff-plan :seon.task/done? false :seon.task/owner callee :seon.task/created-by (or caller :user)}
                  eff-scope (assoc :seon.task/scope eff-scope)
                  (and tpl-id (qualified-keyword? tpl-id)) (assoc :seon.task/template tpl-id)
                  (seq eff-tests) (assoc :seon.task/tests eff-tests)
                  (seq eff-needs) (assoc :seon.task/needs eff-needs))
            link-parent (when (and caller-is-real? (not= caller-task tid))
                          {:seon.task/id caller-task :seon.task/needs #{tid}})]
        {:task-id tid :tx (vec (remove nil? [ent link-parent]))})

      :else nil)))

(defn- call-subtask-id [db call]
  (let [cid (:seon.agent.call/id call)
        callee (:seon.agent.call/callee call)]
    (or (get-in call [:seon.agent.call/task-ref :seon.task/id])
        (when (and cid callee (contains? (:schema db) :seon.task/id))
          (let [cand (keyword (str "subtask." (name callee)) (str "c-" (subs (str cid) 0 8)))]
            (when (first (d/datoms db :avet :seon.task/id cand))
              cand))))))

(defn- summarize-call-result [result]
  (let [raw (cond
              (string? result) result
              (and (map? result) (string? (:summary result))) (:summary result)
              (and (map? result) (string? (:answer result))) (:answer result)
              :else (pr-str result))
        s (-> (str (or raw "")) (str/replace #"\s+" " ") str/trim)]
    (if (> (count s) 240) (str (subs s 0 237) "…") s)))

(defn auto-close-subtask-op
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db :map :any] :any], :seon.flow/edge true}
  [conn db0 call result]
  (when-let [tid (call-subtask-id db0 call)]
    (let [t (d/pull db0 '[:seon.task/id :seon.task/done? :seon.task/tests :seon.task/test-vars]
                    [:seon.task/id tid])
          callee (:seon.agent.call/callee call)
          callee-branch (when callee (:seon.agent/branch (d/pull db0 '[:seon.agent/branch] [:seon.agent/id callee])))]
      (when (and (:seon.task/id t)
                 (not (:seon.task/done? t))
                 (empty? (:seon.task/tests t))
                 (empty? (:seon.task/test-vars t))
                 (not (and callee-branch (not= callee-branch :main) (gate/branch-has-code-edits? (store/head conn :main) (try (store/head conn callee-branch) (catch Throwable _ nil))))))
        (let [sum (summarize-call-result result)]
          (cond-> {:seon.task/id tid :seon.task/done? true}
            (and (contains? (:schema db0) :seon.task/summary) (not (str/blank? sum)))
            (assoc :seon.task/summary sum)))))))

(defn ^:seon.flow/edge mirror-callee-task-to-branch!
  {:malli/schema [:=> [:cat :seon.host/conn :any :any :any :any :any] :any]}
  [conn cfg callee-branch subtask-tx callee subtask-id]
  (when (and cfg callee-branch (not= callee-branch :main) callee subtask-id)
    (try
      (when (store/head conn callee-branch)
        (let [task-ents (filterv #(and (map? %) (= subtask-id (:seon.task/id %))) subtask-tx)
              bconn (store/connect cfg callee-branch)]
          (try
            (let [bdb @bconn
                  tx (cond-> (vec task-ents)
                       (first (d/datoms bdb :avet :seon.agent/id callee))
                       (conj {:seon.agent/id callee :seon.agent/tasks #{(or (:e (first (d/datoms bdb :avet :seon.task/id subtask-id))) (:db/id (first task-ents)) [:seon.task/id subtask-id])}}))]
              (when (seq tx)
                (d/transact bconn {:tx-data tx}))
              true)
            (finally (d/release bconn)))))
      (catch Throwable _ false))))
