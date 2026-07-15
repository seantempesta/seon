(ns seon.test-seed
  "Hermetic-store seed for tests that RENDER agent context.

   SCI bounding is FAIL-LOUD: a `my.*` render fn with no stored
   `:seon.fn/source` renders a `:seon/error` block in place instead of
   falling back to the unbounded compiled path (a hang there would wedge
   the single-threaded pod). The default ctx blocks reference `my.*` fns
   (`my.kb.shared/instructions-block`, `my.plan.internal/plan-block`,
   `my.skills/catalog-block` / `skill-block`), so a hermetic test store
   that renders context must carry their source rows — exactly what the
   pod's boot indexer stores. [[my-core-rows]] is the `my.*` slice of
   `seon.client/index-core!` (the ONE indexer — no hand-written rows to
   drift), memoized once per test process (index-core! reads source
   files; per-store recomputation would slow the suite). The slice retains the
   complete canonical schema declaration population because persisted
   function contracts are invalid without the schemas they reference."
  (:require
    [clojure.string :as str]
    [seon.client :as client]))

(def ^:private !my-core-rows
  (delay
    (into
      (filterv (fn [row]
                 (let [s (or (:seon.fn/sym row)
                             (some-> (:seon.ns/name row) name))]
                   (and s (str/starts-with? (str s) "my."))))
               (client/index-core!))
      (client/index-schemas))))

(defn my-core-rows
  "The boot indexer's `my.*` rows and canonical schema facts."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  @!my-core-rows)
