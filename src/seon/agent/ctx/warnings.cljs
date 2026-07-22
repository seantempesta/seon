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

(def ^:private schema-provenance-query
  '[:find ?requested ?process-id
    :in $ [?requested ...]
    :where
    [?schema :seon.schema/key ?requested ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id ?process-id]])

(def ^:private database-instant-query
  '[:find (max ?instant) .
    :where
    [?transaction :db/txInstant ?instant]])

(def ^:private attribute-counts-query
  '[:find ?requested (count-distinct ?entity)
    :in $ [?requested ...]
    :where
    [?entity ?requested _]])

(declare query-member member-result latest-user-query)

(def ^:private acquisition-page-size 16)
(def ^:private acquisition-page-max-result-weight 60000)

(defn- database-error
  [value]
  (when (and (map? value) (string? (:seon.error/message value))) value))

(defn- ^:async pull-page!
  [database pull-pattern refs]
  (if (seq refs)
    (await
     (db/pull-many
      {::db/db database
       ::db/pull-pattern pull-pattern
       ::db/refs (vec refs)
       ::db/max-result-weight acquisition-page-max-result-weight}))
    []))

(defn- ^:async query-page!
  [database query arguments]
  (await
   (db/query
    {::db/db database
     ::db/query query
     ::db/args arguments
     ::db/max-results acquisition-page-size
     ::db/max-result-weight acquisition-page-max-result-weight})))

(defn- ^:async acquire-identity-pages!
  [database identity-attr initial combine-page acquire-page!]
  (loop [cursor nil
         acquired initial]
    (let [page
          (await
           (db/index-page
            (cond-> {::db/db database
                     ::db/index :aevt
                     ::db/components [identity-attr]
                     ::db/direction :forward
                     ::db/limit acquisition-page-size
                     ::db/max-result-weight
                     acquisition-page-max-result-weight}
              cursor (assoc ::db/cursor cursor))))]
      (if-let [error (database-error page)]
        error
        (let [page-value (await (acquire-page! page))]
          (if-let [error (database-error page-value)]
            error
            (let [next-acquired (combine-page acquired page-value)]
              (if (:datahike.index-page/complete? page)
                next-acquired
                (recur (:datahike.index-page/cursor page)
                       next-acquired)))))))))

(defn- ^:async attribute-counts!
  [database attributes]
  (loop [remaining (vec attributes)
         counts {}]
    (if (empty? remaining)
      counts
      (let [page-attributes (subvec remaining 0
                                    (min acquisition-page-size
                                         (count remaining)))
            rows
            (await
             (db/query
              {::db/db database
               ::db/query attribute-counts-query
               ::db/args [page-attributes]
               ::db/max-work 5000000
               ::db/max-results 65536
               ::db/max-result-weight acquisition-page-max-result-weight}))]
        (if-let [error (database-error rows)]
          error
          (recur (subvec remaining (count page-attributes))
                 (into counts rows)))))))

(defn- function-row
  [entity]
  [(get entity :seon.fn/sym)
   (get-in entity [:seon.fn/ns :seon.ns/name])
   (or (:seon.fn/spec entity) "")
   (if (contains? entity :seon.fn/fn-var?)
     (:seon.fn/fn-var? entity) true)
   (if (contains? entity :seon.fn/private?)
     (:seon.fn/private? entity) false)
   (or (:seon.fn/schema-error entity) "")])

(defn- after-cutoff?
  [at cutoff]
  (and at (or (nil? cutoff) (> (.getTime ^js at) (.getTime ^js cutoff)))))

(defn- eval-page-data
  [entities cutoff now]
  (reduce
   (fn [data entity]
     (let [{:seon.eval/keys [id ok? at error result-edn duration-ms]} entity]
       (cond-> data
         (and (false? ok?) (after-cutoff? at cutoff))
         (update ::warn/failed-evals conj [id (or error "")])

         (and (contains? entity :seon.eval/result-edn)
              (after-cutoff? at cutoff))
         (update ::warn/fs-results conj [id result-edn at])

         (and duration-ms at
              (>= duration-ms warn/slow-eval-threshold-ms)
              (> (.getTime ^js at)
                 (- (.getTime ^js now) (* 60 60 1000))))
         (update ::warn/slow-evals conj [id duration-ms]))))
   {::warn/failed-evals [] ::warn/fs-results [] ::warn/slow-evals []}
   entities))

(defn- message-page-data
  [entities]
  (into []
        (keep
         (fn [{:seon.agent.message/keys [id hops at from to]}]
           (when (and hops (>= hops 4))
              [id hops at
              (or (:seon.agent/id from) (:seon.user/id from) "user")
              (or (:seon.agent/id to) (:seon.user/id to) "user")])))
        entities))

(defn- test-page-data
  [entities]
  (into []
        (keep
         (fn [{:seon.test/keys [sym last-failed-at last-passed-at]}]
           (when (and last-failed-at
                      (or (nil? last-passed-at)
                          (> (.getTime ^js last-failed-at)
                             (.getTime ^js last-passed-at))))
             [sym])))
        entities))

(defn- canvas-page-data
  [entities]
  (into []
        (keep
         (fn [{:seon.agent/keys [id] :as entity}]
           (when (contains? entity :seon.render.canvas/content)
             [id (:seon.render.canvas/content entity)])))
        entities))

(def ^:private function-pull-pattern
  '[:seon.fn/sym :seon.fn/spec :seon.fn/fn-var? :seon.fn/private?
    :seon.fn/schema-error {:seon.fn/ns [:seon.ns/name]}])

(def ^:private schema-pull-pattern
  '[:seon.schema/key :seon.schema/form])

(def ^:private eval-pull-pattern
  '[:seon.eval/id :seon.eval/ok? :seon.eval/at :seon.eval/error
    :seon.eval/result-edn :seon.eval/duration-ms])

(def ^:private message-pull-pattern
  '[:seon.agent.message/id :seon.agent.message/hops :seon.agent.message/at
    {:seon.agent.message/from [:seon.agent/id :seon.user/id]}
    {:seon.agent.message/to [:seon.agent/id :seon.user/id]}])

(def ^:private test-pull-pattern
  '[:seon.test/sym :seon.test/last-failed-at :seon.test/last-passed-at])

(def ^:private canvas-pull-pattern
  '[:seon.agent/id :seon.render.canvas/content])

(defn- ^:async acquire-entities!
  [database identity-attr pull-pattern page-fn initial combine-page]
  (await
   (acquire-identity-pages!
    database identity-attr initial combine-page
    (fn ^:async acquire-page [page]
      (let [entities
            (await
             (pull-page! database pull-pattern
                         (mapv first (:datahike.index-page/datoms page))))]
        (if-let [error (database-error entities)]
          error
          (page-fn entities)))))))

(defn- merge-schema-data
  [left right]
  (-> left
      (update ::warn/installed-schema merge (::warn/installed-schema right))
      (update ::warn/schema-provenance into (::warn/schema-provenance right))
      (update ::warn/schema-forms into (::warn/schema-forms right))
      (update ::warn/attribute-counts merge (::warn/attribute-counts right))))

(defn- ^:async acquire-schema-data!
  [database]
  (await
   (acquire-identity-pages!
    database :seon.schema/key
    {::warn/installed-schema {}
     ::warn/schema-provenance []
     ::warn/schema-forms []
     ::warn/attribute-counts {}}
    merge-schema-data
    (fn ^:async acquire-page [page]
      (let [datoms (:datahike.index-page/datoms page)
            entity-ids (mapv first datoms)
            keys (mapv #(nth % 2) datoms)
            schemas (await (pull-page! database schema-pull-pattern entity-ids))]
        (if-let [error (database-error schemas)]
          error
          (let [installed
                (await (pull-page! database '[*]
                                   (mapv #(vector :db/ident %) keys)))]
            (if-let [error (database-error installed)]
              error
              (let [provenance
                    (await (query-page! database schema-provenance-query [keys]))]
                (if-let [error (database-error provenance)]
                  error
                  (let [installed-attributes (into [] (keep :db/ident) installed)
                        counts
                        (await (attribute-counts! database installed-attributes))]
                    (if-let [error (database-error counts)]
                      error
                      {::warn/installed-schema
                       (into {}
                             (keep (fn [entity]
                                     (when entity [(:db/ident entity) entity])))
                             installed)
                       ::warn/schema-provenance provenance
                       ::warn/schema-forms
                       (into []
                             (keep (fn [entity]
                                     (when (contains? entity :seon.schema/form)
                                       [(:seon.schema/key entity)
                                        (:seon.schema/form entity)])))
                             schemas)
                       ::warn/attribute-counts counts}))))))))))))

(defn ^:async ^:private acquire-warnings
  [agent-id agent database]
  (let [first-result
        (await
          (db/execute-many
            {::db/db database
             ::db/members
             [(query-member latest-user-query)
              (query-member home/latest-successful-ns-query
                            [agent-id] 1000000 32768
                            acquisition-page-max-result-weight)
              (query-member home/namespace-assignment-query
                            [agent-id] 100000 64
                            acquisition-page-max-result-weight)
              (query-member database-instant-query [] 2000000 65536
                            acquisition-page-max-result-weight)]
             ::db/max-result-weight acquisition-page-max-result-weight}))
        first-members (::db/results first-result)]
    (cond
      (database-error first-result) first-result
      (not-every? #(true? (::protocol/success? %)) first-members)
      (let [failed (first (remove #(true? (::protocol/success? %))
                                  first-members))]
        {:seon.error/message "Warning acquisition member failed."
         :seon.error/data failed
         :seon.error/kind :core-bug})
      :else
      (let [[cutoff-member eval-ns-member assignment-member instant-member]
            first-members
            cutoff (ffirst (member-result cutoff-member))
            now (member-result instant-member)
            functions
            (await
             (acquire-entities! database :seon.fn/sym function-pull-pattern
                                #(mapv function-row %) [] into))]
        (if-let [error (database-error functions)]
          error
          (let [schema-data (await (acquire-schema-data! database))]
            (if-let [error (database-error schema-data)]
              error
              (let [eval-data
                    (await
                     (acquire-entities!
                      database :seon.eval/id eval-pull-pattern
                      #(eval-page-data % cutoff now)
                      {::warn/failed-evals [] ::warn/fs-results []
                       ::warn/slow-evals []}
                      (partial merge-with into)))]
                (if-let [error (database-error eval-data)]
                  error
                  (let [hops
                        (await
                         (acquire-entities!
                          database :seon.agent.message/id message-pull-pattern
                          message-page-data [] into))]
                    (if-let [error (database-error hops)]
                      error
                      (let [failing
                            (await
                             (acquire-entities!
                              database :seon.test/sym test-pull-pattern
                              test-page-data [] into))]
                        (if-let [error (database-error failing)]
                          error
                          (let [canvases
                                (await
                                 (acquire-entities!
                                  database :seon.agent/id canvas-pull-pattern
                                  canvas-page-data [] into))]
                            (if-let [error (database-error canvases)]
                              error
                              {::warn/current-ns
                               (home/current-ns
                                agent-id agent
                                (some-> (member-result eval-ns-member) first)
                                (some-> (member-result assignment-member) first))
                               ::warn/data
                               (merge schema-data eval-data
                                      {::warn/function-rows functions
                                       ::warn/hop-messages hops
                                       ::warn/failing-tests failing
                                       ::warn/canvases canvases})})))))))))))))))

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
    (if (:seon.error/message acquired)
      (str "[warnings] render failed: " (pr-str acquired))
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
