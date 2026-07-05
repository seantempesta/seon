(ns seon.agent.ctx.warnings
  "The `:warnings` context section — current problems rendered as a
   single-`;` `WARNINGS` comment-block via the `seon.warn` check registry.
   Symbol-wired into the composer (`seon.config/default-ctx-blocks`) as
   `'seon.agent.ctx.warnings/warnings-block`."
  (:require
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.db :as db]
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

(defn- core-fault-rows
  "[eid msg at tx-inst frame0] rows for `:core`-fault errors since the
   latest user message (every one when no user message exists yet).
   `frame0` is the top parsed stack frame's fn name, \"\" when none."
  [db]
  (let [cutoff (warn/latest-user-at db)
        frame0 (into {} (db/query
                          {:seon.db/db db
                           :seon.db/query
                           '[:find ?e ?fn
                             :where
                             [?e :seon.error/frames ?f]
                             [?f :seon.error.frame/index 0]
                             [?f :seon.error.frame/fn ?fn]]}))
        rows   (db/query
                 {:seon.db/db db
                  :seon.db/query
                  '[:find ?e ?msg ?at ?inst
                    :where
                    [?e :seon.error/fault :core ?tx]
                    [?e :seon.error/message ?msg]
                    [(get-else $ ?e :seon.error/at 0) ?at]
                    [?tx :db/txInstant ?inst]]})]
    (->> rows
         (filter (fn [[_ _ _ inst]]
                   (or (nil? cutoff)
                       (> (.getTime ^js inst) (.getTime ^js cutoff)))))
         (sort-by (fn [[_ _ at]] at))
         (mapv (fn [[e msg at inst]]
                 [e msg at inst (get frame0 e "")])))))

(defn core-faults-block
  "`:core`-fault errors since the last user message — ROOT world only.

   The derived strict-gate surface of the error-blame design (RULED
   2026-07-04): a `seon.error/record!` with fault `:core` is OUR bug
   (never an agent's), and this section makes it loud on the root
   world's context until the fix lands — then the query returns empty
   and the section VANISHES (no acknowledgement state, nothing to
   clear). Root-only by config wiring: the block rides ONLY in
   `:seon.config/root-context` (other agents see nothing — core bugs
   are not theirs to fix; the affected agent already saw its in-place
   `:seon/error` envelope)."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db]}]
  (let [rows (core-fault-rows db)]
    (if (empty? rows)
      ""
      (str ";;; CORE FAULTS — " (count rows)
           " since the last user message (root-only)\n"
           "; These are SEON CORE bugs (fault :core — our machinery), not agent errors.\n"
           "; Under :seon.config/on-core-error :gate they FAIL dev runs until fixed.\n"
           (str/join
             "\n"
             (map (fn [[e msg at _inst frame]]
                    (str "; t=" at "  " msg
                         (when (seq frame) (str "  @ " frame))
                         "  [eid " e "]"))
                  rows))
           "\n; Freeze the db a fault saw: (seon.db/as-of <t>) ; "
           "full row: (seon.db/pull '[*] <eid>)"))))

(defn instrumentation-gaps-block
  "Specced fns whose live var lost its malli wrapper — ROOT world only.

   The derived coverage invariant (C46) as a reactive section: the census
   (`seon.instrument/coverage-gaps`) is recomputed from the db + the live
   JS vars at every render — nothing stored, so the section VANISHES the
   moment coverage is re-asserted (`instrument-from-db!` runs at boot, on
   `start-agent!`, and after every hot reload). Non-empty means specced
   fns are running WITHOUT contract validation — a hole in the `:crash`
   dial's safety story. Root-only by config wiring (the block rides in
   `:seon.config/root-context`, like `:core-faults` — a coverage gap is
   system-wide, not any one agent's). Structural async opt-outs
   (`async-unwrappable?`) are excluded; the kill-switch (`SEON_INSTRUMENT`
   off) renders empty — no invariant to hold."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db]}]
  (let [gaps (instrument/coverage-gaps db)]
    (if (empty? gaps)
      ""
      (str ";;; INSTRUMENTATION GAPS — " (count gaps)
           " specced fns without a live wrapper (root-only)\n"
           "; These fns carry a :malli/schema but their LIVE var is not malli-wrapped\n"
           "; (structural async opt-outs excluded) — they run with NO contract\n"
           "; validation. Usual cause: a ns re-eval replaced wrapped vars without a\n"
           "; re-instrument pass. Re-assert coverage:\n"
           ";   (seon.instrument/instrument-from-db! @seon.db/*conn*)\n"
           (str/join
             "\n"
             (map (fn [{:seon.instrument/keys [sym reason]}]
                    (str "; " sym "  [" (name reason) "]"))
                  gaps))))))
