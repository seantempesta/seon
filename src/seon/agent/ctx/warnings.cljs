(ns seon.agent.ctx.warnings
  "The `:warnings` context section — current problems rendered as a
   single-`;` `WARNINGS` comment-block via the `seon.warn` check registry.
   Symbol-wired into the composer (`config manifest`) as
   `'seon.agent.ctx.warnings/warnings-block`."
  (:require
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.instrument :as instrument]
    [seon.warn :as warn]))

(defn warnings-block
  "Current problems as a `WARNINGS` comment-block, or empty when clean.

   A single-`;` block: one explanation + fix example per kind, then
   affected locations — derived from live state, never stored, so a warning
   vanishes the moment its cause does. Corpus (spec-hygiene) checks scope
   to the agent's current ns by default; `:seon.warn/ns <ns-kw>` (or
   `:seon.warn/all`) on the `:seon.agent.ctx` entity overrides. Runtime checks
   (failed-evals, bad-ref, slow-evals, failing-tests) are always global.
   Dev-only checks are not surfaced here. Add a kind via `seon.warn/checks`."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  ;; The render engine injects this block's own map as :seon.render/node
  ;; (seon.render/render) — that is where a per-block :seon.warn/ns override
  ;; lives. (Reading :seon.agent.ctx/block here was a dead key: the input
  ;; never carries it, so the override was silently ignored.)
  (let [override (:seon.warn/ns (:seon.render/node input))
        scope    (cond
                   (= override :seon.warn/all) nil
                   (some? override)            override
                   :else
                   (let [ns (ctx/current-ns {:seon.agent/id id :seon.db/db db})]
                     (if (keyword? ns) ns (keyword (str ns)))))]
    (warn/render-warnings
      (cond-> {:seon.db/db db}
        (some? scope) (assoc :seon.warn/ns scope)))))

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
    [(get-else $ ?e :seon.error/t 0) ?t]
    [?tx :db/txInstant ?inst]])

(def ^:private program-schema-query
  '[:find ?sym ?spec
    :where
    [?e :seon.fn/sym ?sym]
    [?e :seon.fn/spec ?spec]])

(defn- query-member [query-form]
  {::protocol/operation protocol/query-operation
   ::protocol/query-form query-form
   :datahike.resource/max-work 2000000
   :datahike.resource/max-results 65536
   :datahike.resource/max-result-weight 2097152})

(defn- member-result [member]
  (when (true? (::protocol/success? member))
    (or (::protocol/result member)
        (:datahike.query/result member))))

(defn- core-fault-rows
  "Format ordinary core-fault query results at one database coordinate."
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
  [_input _invoke-selected!]
  (let [acquired (await (db/execute-many
                          {::db/members
                           (mapv query-member
                                 [latest-user-query core-frame-query
                                  core-fault-query])
                           ::db/max-result-weight 4194304}))
        members (::db/results acquired)
        rows (when (every? #(true? (::protocol/success? %)) members)
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
  [_input _invoke-selected!]
  (let [result (await (db/query
                        {:seon.db/query program-schema-query
                         :datahike.resource/max-work 4000000
                         :datahike.resource/max-results 65536
                         :datahike.resource/max-result-weight 4194304}))
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
