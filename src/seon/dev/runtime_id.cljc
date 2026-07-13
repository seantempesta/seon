(ns seon.dev.runtime-id
  "The MCP runtime-addressing probe surface (mcp-agent-id-unification
   PRD, 2026-06-10; cluster-qualified per registry C27). ONE id grammar:
   a runtime answers the probe with its `advertisement` — the CLUSTER it
   belongs to plus the VECTOR of ids it hosts — core `:seon.agent/id`
   strings for agent-hosting processes (the pod hosts every agent it
   resumed or minted), or `proc:<name>` for an explicitly advertised
   non-agent runtime. `bin/mcp-server-cljs` resolves an
   `agent_id` eval by MEMBERSHIP: it enumerates shadow runtimes, evals
   `(seon.dev.runtime-id/advertisement)` in each, and applies
   [[select-runtime]] — the ONE decision rule. Every cluster hosts a
   \"root\", so a bare id matching MORE THAN ONE runtime is AMBIGUOUS
   and resolution FAILS LOUD with the candidate list (never an arbitrary
   pick); `\"<cluster>/<id>\"` (e.g. `\"default/root\"`) pins exactly one.

   The `proc:` prefix can never collide with a real agent id — the registered
   `:seon.agent/id` grammar excludes `:` — and cluster names can never
   contain `/` (`bin/seon valid_cluster_name`), so the qualified grammar
   splits unambiguously on the FIRST `/`.

   CLJC + zero platform-specific requires by design: this ns is compiled into
   every build that wants to be MCP-addressable (`:client`, `:node-agent`)
   and must cost nothing beyond two atoms. The CLJC half exists so
   `bin/mcp-server-cljs` (babashka) loads
   the SAME [[parse-id]]/[[select-runtime]] grammar the CLJS suite
   tests — one resolution rule, zero mirrored logic."
  (:require [clojure.string :as str]))

;; defonce — a hot reload of any ns that calls host!/cluster! must not
;; wipe the hosted set; re-arm paths re-host idempotently anyway.
(defonce ^:private !hosted (atom #{}))
(defonce ^:private !cluster (atom nil))

(defn host!
  "Register `id` as hosted by THIS process; idempotent.

   `id` is a core `:seon.agent/id` string, or `proc:<name>` for infra runtimes."
  {:malli/schema [:=> [:catn [:seon.dev.runtime-id/id :string]] :string]}
  [id]
  (when-not (str/blank? id)
    (swap! !hosted conj id))
  id)

(defn unhost!
  "Remove `id` from this process's hosted set. Idempotent."
  {:malli/schema [:=> [:catn [:seon.dev.runtime-id/id :string]] :string]}
  [id]
  (swap! !hosted disj id)
  id)

(defn hosted
  "The ids this runtime answers to — sorted vector.

   The id half of [[advertisement]] (the full probe envelope, which adds
   the cluster). Kept as its own read for callers that only need ids."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (vec (sort @!hosted)))

(defn cluster!
  "Declare the cluster THIS process belongs to; idempotent.

   The pod calls it at boot with `seon.db.replica/database-name`, the one
   database-name derivation. Blank names are refused — a runtime
   that never declares advertises WITHOUT a cluster (legacy shape) and
   can only be pinned by an unambiguous bare id."
  {:malli/schema [:=> [:catn [:seon.dev.runtime-id/cluster :string]] :string]}
  [cluster-name]
  (when-not (str/blank? cluster-name)
    (reset! !cluster cluster-name))
  cluster-name)

(defn dir->cluster-name
  "Cluster name from a cluster dir — its basename; blank dir → `default`.

   The same rule as `seon.db.replica/database-name` (basename of
   `SEON_CLUSTER_DIR`), exposed here so the Babashka resolver can derive
   its own cluster without loading the replica."
  {:malli/schema [:=> [:catn [:seon.dev.runtime-id/dir :string]] :string]}
  [dir]
  (or (last (remove str/blank? (str/split dir #"/"))) "default"))

(defn advertisement
  "The probe envelope: this runtime's cluster (when declared) + hosted ids.

   THE probe form `bin/mcp-server-cljs` evals into each shadow runtime;
   [[select-runtime]] resolves an `agent_id` against a set of these."
  {:malli/schema [:=> [:cat]
                  [:map
                   [:seon.dev.runtime-id/ids [:vector :string]]
                   [:seon.dev.runtime-id/cluster {:optional true} :string]]]}
  []
  (let [c @!cluster]
    (cond-> #:seon.dev.runtime-id{:ids (hosted)}
      (some? c) (assoc :seon.dev.runtime-id/cluster c))))

;; ---------------------------------------------------------------------------
;; Resolution grammar — pure; loaded by BOTH the CLJS builds and the
;; babashka MCP server (the whole reason this file is .cljc).
;; ---------------------------------------------------------------------------

(defn parse-id
  "Split an MCP `agent_id` into its optional cluster qualifier + bare id.

   `\"default/root\"` → `{::cluster \"default\" ::id \"root\"}`;
   `\"root\"` / `\"proc:worker\"` → `{::id …}` (no qualifier). Splits on the
   FIRST `/` — safe because cluster names can't contain `/` and the canonical
   agent-id grammar excludes it (human-facing agents use joined words)."
  {:malli/schema [:=> [:catn [:seon.dev.runtime-id/agent-id :string]]
                  [:map
                   [:seon.dev.runtime-id/id :string]
                   [:seon.dev.runtime-id/cluster {:optional true} :string]]]}
  [agent-id]
  (if-let [i (str/index-of agent-id "/")]
    #:seon.dev.runtime-id{:cluster (subs agent-id 0 i)
                          :id      (subs agent-id (inc i))}
    #:seon.dev.runtime-id{:id agent-id}))

(defn select-runtime
  "Resolve a parsed id against runtime advertisements — THE decision rule.

   Candidates are [[advertisement]] maps (callers may merge extra keys,
   e.g. the resolver's build/client-id — passed through untouched). A
   candidate matches when its `::ids` contains `::id` AND, when a
   `::cluster` qualifier was given, its advertised cluster equals it (a
   candidate with NO advertised cluster never matches a qualified id).

     0 matches → `{::resolution :none}`
     1 match   → `{::resolution :match ::runtime <candidate>}`
     2+        → `{::resolution :ambiguous ::runtimes <candidates>}`

   Ambiguity is NEVER broken by picking arbitrarily (registry C27: every
   cluster hosts a \"root\"; a plausible mis-pin means cross-cluster
   writes) — the caller fails loud with the candidate list."
  {:malli/schema [:=> [:catn [:seon.dev.runtime-id/request
                              [:map
                               [:seon.dev.runtime-id/id :string]
                               [:seon.dev.runtime-id/cluster {:optional true} :string]
                               [:seon.dev.runtime-id/candidates
                                [:vector [:map
                                          [:seon.dev.runtime-id/ids [:vector :string]]
                                          [:seon.dev.runtime-id/cluster {:optional true} :string]]]]]]]
                  [:map
                   [:seon.dev.runtime-id/resolution [:enum :match :ambiguous :none]]
                   [:seon.dev.runtime-id/runtime {:optional true} :map]
                   [:seon.dev.runtime-id/runtimes {:optional true} [:vector :map]]]]}
  [{:seon.dev.runtime-id/keys [id cluster candidates]}]
  (let [matches (filterv (fn [cand]
                           (and (boolean (some #(= id %) (:seon.dev.runtime-id/ids cand)))
                                (or (nil? cluster)
                                    (= cluster (:seon.dev.runtime-id/cluster cand)))))
                         candidates)]
    (case (count matches)
      0 #:seon.dev.runtime-id{:resolution :none}
      1 #:seon.dev.runtime-id{:resolution :match :runtime (first matches)}
      #:seon.dev.runtime-id{:resolution :ambiguous :runtimes matches})))
