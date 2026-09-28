;; Evaluate this one form only in an MCP JVM scratch namespace.
;; Candidate for program.cljc; no production Var is replaced.
(do
  (require '[seon.program :as program] '[seon.schema :as schema]
           '[seon.cache :as cache] '[seon.id :as id])
  (defn definition-content-key
    "Preserve the canonical encoder's sequence and numeric distinctions."
    {:malli/schema [:=> [:cat :map] :map]}
    [parts]
    (letfn [(key-of [v]
              (cond
                (map? v) (into {} (map (fn [[k value]] [(key-of k) (key-of value)])) v)
                (set? v) (into #{} (map key-of) v)
                (sequential? v) [(if (vector? v) :vector :seq) (mapv key-of v)]
                (number? v) [:number (str v)]
                :else v))]
      (key-of parts)))
  (defn digest-candidate
    "Cache the digest after excluding provenance and selecting resolver inputs."
    {:malli/schema [:=> [:cat :map [:maybe :map]] :seon.program/definition-digest]}
    [entity resolver-context]
    (let [parts (cond-> {:seon.program/declaration
                        (apply dissoc entity @#'program/definition-digest-excluded-attributes)}
                  resolver-context
                  (assoc :seon.program/resolver-context
                         (@#'program/used-resolver-context entity resolver-context)))]
      (first
       (cache/lookup
        ::digest-candidate (definition-content-key parts) second
        #(let [text (schema/canonical-data-string parts)]
           [(id/digest 64 [text]) (* 6 (count text))]))))))
