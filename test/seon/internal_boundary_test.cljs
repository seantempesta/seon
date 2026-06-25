(ns seon.internal-boundary-test
  "The `.internal` boundary — the framework's `*.internal` namespaces hold
   private machinery agents must never see or reach. The whole `.internal`
   refine-wave premise is that this boundary is STRUCTURAL (the `.internal`
   suffix IS the filter), not nominal — so these assertions pin the
   MECHANISM that enforces it, not any particular namespace's contents:

     1. `.internal` nses are NOT in the agent-facing
        `seon.ctx.namespaces/full-source-whitelist` (the curated set rendered
        to agents in full) — and the predicates over that whitelist agree.
     2. The structural selection rule `included-ns?` EXCLUDES every
        `.internal` ns from the agent prompt while INCLUDING its public
        parent — one suffix rule, no per-namespace special-casing.
     3. A public ns genuinely DELEGATES to its `.internal` sibling: the
        public entry point and the private worker are distinct vars in
        distinct namespaces (the boundary is real code, not a comment)."
  (:require
    [cljs.test :refer [deftest is]]
    [seon.ctx.namespaces :as ns]
    [seon.db :as db]
    [seon.db.internal :as db.internal]))

;; The real framework `.internal` namespaces (each a sibling of a public ns
;; whose body it backs). NONE may leak to an agent.
(def ^:private internal-nses
  [:seon.db.internal
   :seon.agent.internal
   :seon.agent.search.internal
   :seon.agent.fs.internal])

(deftest internal-nses-absent-from-the-full-source-whitelist
  (doseq [n internal-nses]
    (is (not (contains? ns/full-source-whitelist n))
        (str n " must NOT be in the agent-facing full-source whitelist"))
    (is (false? (ns/in-full-source-whitelist? n))
        (str "in-full-source-whitelist? rejects " n))
    (is (false? (ns/full-source-ns? n))
        (str "full-source-ns? never inlines " n " — .internal is hidden"))))

(deftest included-ns-excludes-internal-keeps-the-public-parent
  ;; The structural agent-prompt selection rule: .internal is filtered out by
  ;; the suffix alone, while the public parent renders. Falsifies a hollow
  ;; "always false" check by asserting the parent IS included.
  (doseq [[internal parent] [[:seon.db.internal           :seon.db]
                             [:seon.agent.search.internal :seon.agent.search]
                             [:seon.agent.fs.internal     :seon.agent.fs]]]
    (is (false? (ns/included-ns? internal))
        (str internal " is excluded from the agent prompt (.internal suffix)"))
    (is (true? (ns/included-ns? parent))
        (str "the PUBLIC parent " parent " IS included — the boundary is the "
             "suffix, not a blanket exclusion")))
  ;; String/keyword/symbol tolerance — same answer whatever the caller hands.
  (is (false? (ns/included-ns? "seon.db.internal")))
  (is (false? (ns/included-ns? 'seon.db.internal))))

(deftest public-db-delegates-to-its-internal-sibling
  ;; The boundary is real code: the public entry points an agent calls live
  ;; in seon.db; the workers they delegate to live in seon.db.internal. They
  ;; are DISTINCT vars in DISTINCT namespaces — not re-exports.
  (is (fn? db/transact!)            "public entry point exists")
  (is (fn? db.internal/transact!*)  "private worker exists in the .internal ns")
  (is (fn? db/current-agent-id)     "public reader exists")
  (is (fn? db.internal/current-agent-id) "private reader exists in .internal")
  (is (not (identical? db/transact! db.internal/transact!*))
      "public transact! is NOT the same var as the internal worker — it wraps it"))
