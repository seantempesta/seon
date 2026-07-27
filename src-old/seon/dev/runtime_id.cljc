(ns seon.dev.runtime-id
  "Pure runtime-addressing data for the CLJS MCP bridge.

   A pod advertisement is a projection of its database: the cluster name plus
   the nonterminated, born `:seon.agent/id` values in that database. This
   namespace normalizes that projection and owns the one resolution rule. It
   deliberately owns no process-global membership or cluster state.

   `bin/mcp-server-cljs` probes each Shadow runtime, then applies
   [[select-runtime]]. Every cluster can contain a `root`, so a bare id with
   several matches is ambiguous and never selected arbitrarily.
   `\"<cluster>/<id>\"` pins one cluster explicitly.

   The namespace is CLJC so Babashka and ClojureScript execute the same pure
   constructor and selection functions."
  (:require [clojure.string :as str]))

(defn advertisement
  "Normalize one database-derived runtime advertisement.

   `::cluster` is the database/cluster name. `::ids` are queried by the caller
   from that database. Duplicate and input ordering are presentation details,
   so the canonical value is a sorted distinct vector."
  {:malli/schema
   [:=>
    [:catn
     [:seon.dev.runtime-id/request
      [:map
       [:seon.dev.runtime-id/cluster [:string {:min 1}]]
       [:seon.dev.runtime-id/ids [:sequential [:string {:min 1}]]]]]]
    [:map
     [:seon.dev.runtime-id/cluster [:string {:min 1}]]
     [:seon.dev.runtime-id/ids [:vector [:string {:min 1}]]]]]}
  [{:seon.dev.runtime-id/keys [cluster ids]}]
  #:seon.dev.runtime-id{:cluster cluster
                        :ids (->> ids distinct sort vec)})

(defn dir->cluster-name
  "Cluster name from a cluster dir — its basename; blank dir → `default`.

   This shared derivation keeps the pod and Babashka resolver aligned without
   either process loading a database implementation."
  {:malli/schema [:=> [:catn [:seon.dev.runtime-id/dir :string]] :string]}
  [dir]
  (or (last (remove str/blank? (str/split dir #"/"))) "default"))

;; ---------------------------------------------------------------------------
;; Resolution grammar — pure; loaded by BOTH the CLJS builds and the
;; babashka MCP server (the whole reason this file is .cljc).
;; ---------------------------------------------------------------------------

(defn parse-id
  "Split an MCP `agent_id` into its optional cluster qualifier + bare id.

   `\"default/root\"` → `{::cluster \"default\" ::id \"root\"}`;
   `\"root\"` → `{::id \"root\"}` (no qualifier). Splits on the
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
   `::cluster` qualifier was given, its advertised cluster equals it.

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
                                          [:seon.dev.runtime-id/cluster :string]]]]]]]
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
