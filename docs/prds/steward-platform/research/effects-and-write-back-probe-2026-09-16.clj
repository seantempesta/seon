;;; R3 — effects and write-back provenance, read-only probes on `default`.
;;; Run through mcp__seon__eval_clj, mode jvm, namespace user, one form at a
;;; time. Explicit custody; nothing started, stopped or reforked.
;;; Measured 2026-09-16: probe 1 = 80 ms, probe 2 = 23 ms.

;; Probe 1 — hypothesis: the effect entity records its request only as EDN
;; text, its capability only as a symbol, and every capability owner already
;; has a declared request schema.
;; Verdict: CONFIRMED. 0 effect holders (branch reforked), 10 capability
;; owners, 10/10 declared request schemas, 7/7 handler symbols already have
;; program entities.
(let [conn (seon.operator/connection "default")
      db (seon.db/db conn)
      attrs (into (sorted-map)
                  (for [a [:seon.effect/id :seon.effect/run :seon.effect/owner
                           :seon.effect/request-edn :seon.effect/result-edn
                           :seon.effect/result-blob :seon.effect/content-blobs
                           :seon.effect/notify :seon.effect/to
                           :seon.effect/settled-at :seon.effect/duration-ms
                           :seon.effect/interrupted-at :seon.effect/ordinal
                           :seon.effect/form-ordinal :seon.effect/result-size
                           :seon.effect/opened-at]]
                    [a (or (ffirst (seon.db/q '[:find (count ?e) :in $ ?a :where [?e ?a]] db a)) 0)]))
      caps (seon.db/q '[:find ?sym ?cap
                        :where [?e :seon.effect/capability ?cap] [?e :seon.fn/sym ?sym]] db)]
  {:effect-attr-holders attrs
   :capability-count (count caps)
   :capability-owners
   (into (sorted-map)
         (for [[sym cap] caps]
           [sym {:cap cap
                 :spec (some-> (seon.db/pull db [:seon.fn/spec] [:seon.fn/sym sym]) :seon.fn/spec)
                 :handler-entity? (boolean (seon.db/pull db [:db/id] [:seon.fn/sym (str cap)]))}]))
   :fn-file-holders
   {:seon.fn/file (or (ffirst (seon.db/q '[:find (count ?e) :where [?e :seon.fn/file]] db)) 0)
    :seon.fn/form-span (or (ffirst (seon.db/q '[:find (count ?e) :where [?e :seon.fn/form-span]] db)) 0)
    :seon.fn.file/path (or (ffirst (seon.db/q '[:find (count ?e) :where [?e :seon.fn.file/path]] db)) 0)}})

;; Probe 2 — hypothesis: the write-back TARGET side is already complete
;; (file entity with digest + byte span per declaration), so only the
;; effect side is missing.
;; Verdict: CONFIRMED. my.edit/form! -> /Users/sean/src/seon/src/my/edit.clj,
;; digest 2552861d…, span [1265 2317]. :seon.publication/id not installed.
(let [conn (seon.operator/connection "default")
      db (seon.db/db conn)
      installed (fn [a] (boolean (seon.db/pull db [:db/ident] a)))
      cnt (fn [a] (or (ffirst (seon.db/q '[:find (count ?e) :in $ ?a :where [?e ?a]] db a)) 0))]
  {:installed {:seon.publication/id (installed :seon.publication/id)
               :seon.source/commit-id (installed :seon.source/commit-id)
               :seon.fn.file/digest (installed :seon.fn.file/digest)
               :seon.effect/capability (installed :seon.effect/capability)}
   :holders {:seon.source/commit-id (cnt :seon.source/commit-id)
             :seon.fn.file/digest (cnt :seon.fn.file/digest)
             :seon.fn/source (cnt :seon.fn/source)
             :seon.eval/renderer (cnt :seon.eval/renderer)}
   :sample (first (seon.db/q '[:find (pull ?e [:seon.fn/sym :seon.fn/form-span
                                               {:seon.fn/file [:seon.fn.file/path
                                                               :seon.fn.file/digest]}])
                               :where [?e :seon.fn/sym "my.edit/form!"]] db))})
