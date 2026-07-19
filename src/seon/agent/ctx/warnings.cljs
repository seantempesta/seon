(ns seon.agent.ctx.warnings
  "Render current agent warnings into context.

   The block presents active checks from `seon.warn` as a compact comment
   section and disappears when none apply. Warning discovery and persistence
   remain with their owning mechanisms."
  (:require
    [clojure.string :as str]
    [seon.agent.home :as home]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.instrument :as instrument]
    [seon.warn :as warn]))

(def ^:private function-rows-query
  '[:find ?sym ?nm ?spec ?fnvar ?priv ?err
    :where
    [?f :seon.fn/sym ?sym]
    [?f :seon.fn/ns ?ns]
    [?ns :seon.ns/name ?nm]
    [(get-else $ ?f :seon.fn/spec "") ?spec]
    [(get-else $ ?f :seon.fn/fn-var? true) ?fnvar]
    [(get-else $ ?f :seon.fn/private? false) ?priv]
    [(get-else $ ?f :seon.fn/schema-error "") ?err]])

(def ^:private schema-provenance-query
  '[:find ?key ?process-id
    :where
    [?schema :seon.schema/key ?key ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id ?process-id]])

(def ^:private schema-forms-query
  '[:find ?key ?form
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/form ?form]])

(def ^:private attribute-counts-query
  '[:find ?attr (count-distinct ?entity)
    :where
    [?schema :seon.schema/key ?attr]
    [?entity ?attr _]])

(defn- since-query
  [find where cutoff]
  (if cutoff
    {:find find
     :in '[$ ?cutoff]
     :where (conj where '[(> ?at ?cutoff)])}
    {:find find :where where}))

(declare query-member member-result latest-user-query)

(defn- runtime-members
  [cutoff now]
  [(query-member
     (since-query '[?eid ?err]
                  '[[?e :seon.eval/ok? false]
                    [?e :seon.eval/at ?at]
                    [?e :seon.eval/id ?eid]
                    [(get-else $ ?e :seon.eval/error "") ?err]]
                  cutoff)
     (cond-> [] cutoff (conj cutoff)) 3000000 65536 2097152)
   (query-member
     (since-query '[?eid ?edn ?at]
                  '[[?e :seon.eval/result-edn ?edn]
                    [?e :seon.eval/at ?at]
                    [?e :seon.eval/id ?eid]]
                  cutoff)
     (cond-> [] cutoff (conj cutoff)) 3000000 65536 2097152)
   (query-member
     (since-query '[?mid ?hops ?at ?fid ?tid]
                  '[[?m :seon.agent.message/hops ?hops]
                    [(>= ?hops 4)]
                    [?m :seon.agent.message/id ?mid]
                    [?m :seon.agent.message/at ?at]
                    [?m :seon.agent.message/from ?f]
                    [?m :seon.agent.message/to ?t]
                    [(get-else $ ?f :seon.agent/id "user") ?fid]
                    [(get-else $ ?t :seon.agent/id "user") ?tid]]
                  cutoff)
     (cond-> [] cutoff (conj cutoff)) 3000000 65536 2097152)
   (query-member
     (since-query '[?eid ?err ?at]
                  '[[?e :seon.eval/record-error ?err]
                    [?e :seon.eval/id ?eid]
                    [?e :seon.eval/at ?at]]
                  cutoff)
     (cond-> [] cutoff (conj cutoff)) 3000000 65536 2097152)
   (query-member
     '[:find ?eid ?dur
       :in $ ?threshold ?cutoff
       :where
       [?e :seon.eval/duration-ms ?dur]
       [(>= ?dur ?threshold)]
       [?e :seon.eval/at ?at]
       [(> ?at ?cutoff)]
       [?e :seon.eval/id ?eid]]
     [warn/slow-eval-threshold-ms
      (js/Date. (- (.getTime ^js now) (* 60 60 1000)))]
     3000000 65536 1048576)
   (query-member
     '[:find ?sym
       :where
       [?test :seon.test/sym ?sym]
       [?test :seon.test/last-failed-at ?failed-at]
       (or-join [?test ?failed-at]
                (and (not [?test :seon.test/last-passed-at _])
                     [(identity ?failed-at) _])
                (and [?test :seon.test/last-passed-at ?passed-at]
                     [(> ?failed-at ?passed-at)]))]
     [] 2000000 65536 1048576)
   (query-member
     '[:find ?agent-id ?content
       :where
       [?agent :seon.agent/id ?agent-id]
       [?agent :seon.render.canvas/content ?content]]
     [] 2000000 65536 1048576)])

(defn ^:async ^:private acquire-warnings
  [agent-id agent database]
  (let [first-result
        (await
          (db/execute-many
            {::db/db database
             ::db/members
             [(query-member latest-user-query)
              (query-member home/latest-successful-ns-query
                            [agent-id] 1000000 32768 262144)
              (query-member home/namespace-assignment-query
                            [agent-id] 100000 64 4096)
              (query-member function-rows-query [] 5000000 65536 2097152)
              {::protocol/operation protocol/schema-operation}
              (query-member schema-provenance-query [] 3000000 65536 2097152)
              (query-member schema-forms-query [] 3000000 65536 2097152)
              (query-member attribute-counts-query [] 5000000 65536 2097152)]
             ::db/max-result-weight 3670016}))
        first-members (::db/results first-result)]
    (if-not (and (not (:seon.error/message first-result))
                 (every? #(true? (::protocol/success? %)) first-members))
      {:seon.error/message "Warning acquisition failed."
       :seon.error/data first-members}
      (let [[cutoff-member eval-ns-member assignment-member fn-member
             schema-member provenance-member
             forms-member counts-member] first-members
            cutoff (ffirst (member-result cutoff-member))
            now (js/Date.)
            runtime-result
            (await (db/execute-many
                     {::db/db database
                      ::db/members (runtime-members cutoff now)
                      ::db/max-result-weight 3145728}))
            runtime (::db/results runtime-result)]
        (if-not (and (not (:seon.error/message runtime-result))
                     (every? #(true? (::protocol/success? %)) runtime))
          {:seon.error/message "Warning runtime acquisition failed."
           :seon.error/data runtime}
          (let [[failed fs-results hops record-errors slow failing canvases]
                (map member-result runtime)]
            {::warn/current-ns
             (home/current-ns
              agent-id agent
              (some-> (member-result eval-ns-member) first)
              (some-> (member-result assignment-member) first))
             ::warn/data
             {::warn/function-rows (member-result fn-member)
              ::warn/installed-schema (::protocol/schema schema-member)
              ::warn/schema-provenance (member-result provenance-member)
              ::warn/schema-forms (member-result forms-member)
              ::warn/attribute-counts (into {} (member-result counts-member))
              ::warn/failed-evals failed
              ::warn/fs-results fs-results
              ::warn/hop-messages hops
              ::warn/record-errors record-errors
              ::warn/slow-evals slow
              ::warn/failing-tests failing
              ::warn/canvases canvases}}))))))

(defn ^:async warnings-block
  "Current problems as a `WARNINGS` comment-block, or empty when clean.

   A single-`;` block: one explanation + fix example per kind, then
   affected locations — derived from live state, never stored, so a warning
   vanishes the moment its cause does. Corpus (spec-hygiene) checks scope
   to the agent's current ns by default; `:seon.warn/ns <ns-kw>` (or
   `:seon.warn/all`) on the `:seon.agent.ctx` entity overrides. Runtime checks
   (failed-evals, bad-ref, slow-evals, failing-tests) are always global.
   Dev-only checks are not surfaced here. Add a kind via `seon.warn/checks`."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [{:seon.agent/keys [id entity] :as input} _invoke-selected!]
  ;; The render engine injects this block's own map as :seon.render/node
  ;; (seon.render/render) — that is where a per-block :seon.warn/ns override
  ;; lives. (Reading :seon.agent.ctx/block here was a dead key: the input
  ;; never carries it, so the override was silently ignored.)
  (let [database (or (::db/db input)
                     (::db/db (db/current-tx-context))
                     (await (db/db)))
        acquired (if (:seon.error/message database)
                   database
                   (await (acquire-warnings id entity database)))
        override (:seon.warn/ns (:seon.render/node input))
        scope    (cond
                   (= override :seon.warn/all) nil
                   (some? override)            override
                   :else (or (::warn/current-ns acquired)
                             (home/home-ns id)))]
    (if-let [message (:seon.error/message acquired)]
      (str "[warnings] render failed: " message " "
           (pr-str (:seon.error/data acquired)))
      (warn/render-warnings
        (cond-> {::warn/data (::warn/data acquired)}
          (some? scope) (assoc :seon.warn/ns
                               (if (keyword? scope)
                                 scope
                                 (keyword (str scope)))))))))

(def ^:private latest-user-query
  '[:find (max ?at)
    :where
    [?m :seon.agent.message/from ?u]
    [?u :seon.user/id _]
    [?m :seon.agent.message/at ?at]])

(def ^:private core-frame-query
  '[:find ?e ?fn
    :where
    [?e :seon.error/frames ?f]
    [?f :seon.error.frame/index 0]
    [?f :seon.error.frame/fn ?fn]])

(def ^:private core-fault-query
  '[:find ?e ?msg ?t ?inst
    :where
    [?e :seon.error/fault :core ?tx]
    [?e :seon.error/message ?msg]
    [(get-else $ ?e :seon.error/basis-t 0) ?t]
    [?tx :db/txInstant ?inst]])

(def ^:private program-schema-query
  '[:find ?sym ?spec
    :where
    [?e :seon.fn/sym ?sym]
    [?e :seon.fn/spec ?spec]])

(defn- query-member
  ([query-form]
   (query-member query-form [] 2000000 65536 2097152))
  ([query-form arguments max-work max-results max-result-weight]
   {::protocol/operation protocol/query-operation
    ::protocol/query-form query-form
    ::protocol/arguments arguments
    :datahike.resource/max-work max-work
    :datahike.resource/max-results max-results
    :datahike.resource/max-result-weight max-result-weight}))

(defn- member-result [member]
  (when (true? (::protocol/success? member))
    (or (::protocol/result member)
        (:datahike.query/result member))))

(defn- core-fault-rows
  "Format ordinary core-fault query results at one database value."
  [cutoff-rows frame-rows rows]
  (let [cutoff (ffirst cutoff-rows)
        frame0 (into {} frame-rows)]
    (->> rows
         (filter (fn [[_ _ _ inst]]
                   (or (nil? cutoff)
                       (> (.getTime ^js inst) (.getTime ^js cutoff)))))
         (sort-by (fn [[_ _ t]] t))
         (mapv (fn [[e msg t inst]]
                 [e msg t inst (get frame0 e "")])))))

(defn ^:async core-faults-block
  "`:core`-fault errors since the last user message — root cluster only.

   The derived strict-gate surface of the error-blame design (RULED
   2026-07-04): a `seon.error/record!` with fault `:core` is OUR bug
   (never an agent's), and this section makes it loud on the root
   agent's context until the fix lands — then the query returns empty
   and the section VANISHES (no acknowledgement state, nothing to
   clear). Root-only by config wiring: the block rides ONLY in
   `:seon.config/root-context` (other agents see nothing — core bugs
   are not theirs to fix; the affected agent already saw its in-place
   `:seon/error` envelope)."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [input _invoke-selected!]
  (let [database (or (::db/db input)
                     (::db/db (db/current-tx-context))
                     (await (db/db)))
        acquired (if (:seon.error/message database)
                   database
                   (await (db/execute-many
                           {::db/db database
                            ::db/members
                            (mapv query-member
                                  [latest-user-query core-frame-query
                                   core-fault-query])
                            ::db/max-result-weight 4194304})))
        members (::db/results acquired)
        rows (when (and (not (:seon.error/message acquired))
                        (every? #(true? (::protocol/success? %)) members))
               (apply core-fault-rows (map member-result members)))]
    (cond
      (nil? rows)
      (str "[core-faults] render failed: " (pr-str members))

      (empty? rows)
      ""

      :else
      (str ";;; CORE FAULTS — " (count rows)
           " since the last user message (root-only)\n"
           "; These are SEON CORE bugs (fault :core — our machinery), not agent errors.\n"
           "; Under :seon.config/on-core-error :gate they FAIL dev runs until fixed.\n"
           (str/join
             "\n"
             (map (fn [[e msg t _inst frame]]
                    (str "; t=" t "  " msg
                         (when (seq frame) (str "  @ " frame))
                         "  [eid " e "]"))
                  rows))
           "\n; Freeze the db a fault saw: (seon.db/as-of <t>) ; "
           "full row: (seon.db/pull '[*] <eid>)"))))

(defn ^:async instrumentation-gaps-block
  "Specced fns whose live var lost its malli wrapper — root cluster only.

   The derived coverage invariant (C46) as a reactive section: the census
   (`seon.instrument/coverage-gaps`) is recomputed from the db + the live
   JS vars at every render — nothing stored, so the section VANISHES the
   moment the normal boot/reload/eval publication restores it. Non-empty means specced
   fns are running WITHOUT contract validation — a hole in the `:crash`
   dial's safety story. Root-only by config wiring (the block rides in
   `:seon.config/root-context`, like `:core-faults` — a coverage gap is
   system-wide, not any one agent's). Structural async opt-outs
   (`async-unwrappable?`) are excluded; the kill-switch (`SEON_INSTRUMENT`
   off) renders empty — no invariant to hold."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [input _invoke-selected!]
  (let [database (or (::db/db input)
                     (::db/db (db/current-tx-context))
                     (await (db/db)))
        result (if (:seon.error/message database)
                 database
                 (await (db/query
                         {::db/db database
                          :seon.db/query program-schema-query
                          ::db/max-work 4000000
                          ::db/max-results 65536
                          ::db/max-result-weight 4194304})))
        gaps (when-not (:seon.error/message result)
               (instrument/coverage-gaps result))]
    (cond
      (nil? gaps)
      (str "[instrumentation-gaps] render failed: " (pr-str result))

      (empty? gaps)
      ""

      :else
      (str ";;; INSTRUMENTATION GAPS — " (count gaps)
           " specced fns without a live wrapper (root-only)\n"
           "; These fns carry a :malli/schema but their LIVE var is not malli-wrapped\n"
           "; (structural async opt-outs excluded) — they run with NO contract\n"
           "; validation. This is a core publication fault; restart the pod and\n"
           "; inspect the recorded error if the gap returns.\n"
           (str/join
             "\n"
             (map (fn [{:seon.instrument/keys [sym reason]}]
                    (str "; " sym "  [" (name reason) "]"))
                  gaps))))))
