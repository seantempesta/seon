(ns seon.warn
  "Derive clustered warnings from the program graph.

   Each `check-<kind>` is a separate, independently-testable PURE fn
   over pre-acquired ordinary data (`:seon.warn/data`, supplied by
   `seon.agent.ctx.warnings/warnings-block` — the one acquisition owner):

     (check-return-is-any {:seon.warn/data acquired :seon.warn/ns :my.domain})
     ;; → {:seon.warn/kind     :return-is-any
     ;;    :seon.warn/affected [{:seon.warn/sym \"my.domain/f\"
     ;;                          :seon.warn/where \"return\"} …]
     ;;    :seon.warn/explain  \"…one complete explanation…\"
     ;;    :seon.warn/example  \"…one targeted fix…\"}

   `:seon.warn/affected` is EMPTY when the check is clean. The `checks`
   registry composes them; [[render-warnings]] renders each non-clean
   check as ONE cluster: the explanation + ONE fix example + the
   affected list with specific locations — never the same explanation
   repeated per-fn.

   ns-scope (`:seon.warn/ns`, optional) limits the CORPUS checks
   (no-malli-schema / return-is-any / arg-is-any / uses-maybe /
   no-return-spec / no-input-spec) to one namespace —
   the caller (seon.agent/warnings-block) defaults it to the agent's
   CURRENT ns so an agent isn't confused by other namespaces' defects.
   Omit it for the whole-core overview. The RUNTIME checks
   (failed-evals / slow-evals / failing-tests / bad-ref) and the
   SCHEMA/DOMAIN checks (parallel-attr, unmarked-entity-kinds —
   keyword namespaces are data domains, not code nses) stay global —
   cross-agent visibility is their point.

   Everything here is derived from the DB at render time — no warning
   datoms are stored, so warnings self-heal the moment the underlying
   defect is fixed. See docs/seon/concepts/reactive-context.

   There is deliberately NO missing-identity check: identity is
   OPTIONAL on entities (bulk data, value-ish rows, and component
   children legitimately have no natural key)."
  (:require
    [cljs.reader :as edn]
    [clojure.string :as str]
    [seon.db :as db]
    [seon.eval :as eval]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas
;; ============================================================

(schema/register! :seon.warn/ns      :keyword)
(schema/register! :seon.warn/kind    :keyword)
(schema/register! :seon.warn/sym     :string)
(schema/register! :seon.warn/where   :string)
(schema/register! :seon.warn/explain :string)
(schema/register! :seon.warn/example :string)
(schema/register! :seon.warn/urgent? :boolean)
;; DEV-ONLY tier: a check sets this true when its defect is a
;; schema-hygiene / display concern for the dev/web-UI surface, NOT an
;; agent task. render-warnings drops dev-only clusters from the agent
;; render unless :seon.warn/include-dev? is passed (the dev surface opts
;; in). Absent ≡ false (agent-actionable). Derived classification, nothing
;; stored — consistent with the reactive-context model.
(schema/register! :seon.warn/dev-only?    :boolean)
(schema/register! :seon.warn/include-dev? :boolean)
(schema/register! ::data :map)

(schema/register! :seon.warn/affected-entry
  [:map
   [:seon.warn/sym :seon.warn/sym]
   [:seon.warn/where {:optional true} :seon.warn/where]])

(schema/register! :seon.warn/affected
  [:vector :seon.warn/affected-entry])

;; Checks are PURE over ::data — the invoking operation (the warnings
;; context block) pre-acquires every relation at ONE database value and
;; passes ordinary data. No check reads the database itself, so no
;; `^:async` can ever reach [[run-checks]] (a Promise leaking into
;; `(seq :seon.warn/affected)` would throw OUTSIDE the per-check
;; try/catch and kill the whole WARNINGS block).
(schema/register! ::check-request
  [:map
   [::data ::data]
   [:seon.warn/ns {:optional true} :seon.warn/ns]
   ;; When truthy, dev-only clusters are KEPT (the dev/web-UI surface
   ;; opts in). The agent render path passes nothing → dev-only suppressed.
   [:seon.warn/include-dev? {:optional true} :seon.warn/include-dev?]])

(schema/register! ::check-response
  [:map
   [:seon.warn/kind     :seon.warn/kind]
   [:seon.warn/affected :seon.warn/affected]
   [:seon.warn/explain  :seon.warn/explain]
   [:seon.warn/example  :seon.warn/example]
   ;; URGENCY tier: a check sets this true when its defect is one the
   ;; human is hitting RIGHT NOW (e.g. a broken canvas). render-warnings
   ;; renders urgent clusters FIRST with a louder template. Absent ≡ false.
   [:seon.warn/urgent?  {:optional true} :seon.warn/urgent?]
   ;; DEV-ONLY tier: a schema-hygiene/display concern for the dev surface,
   ;; not an agent task — dropped from the agent render. Absent ≡ false.
   [:seon.warn/dev-only? {:optional true} :seon.warn/dev-only?]])

;; ============================================================
;; Corpus access + Malli-form walking helpers
;; ============================================================

(defn- fn-rows
  "Every `:seon.fn` row joined to its owning ns name, optionally
   filtered to `ns-kw`. This request scope remains a keyword UI option, so the
   comparison translates by name at this boundary only.
   Each row is a projection speaking the PERSISTED attr keys
   (`:seon.fn/sym`/`:seon.ns/name`/`:seon.fn/spec`/`:seon.fn/fn-var?`/
   `:seon.fn/private?`/`:seon.fn/schema-error` — C39, no bare twins).
   Absent attrs come back as \"\" / sentinel defaults via get-else in the
   acquisition query (seon.agent.ctx.warnings)."
  [{data ::data} ns-kw]
  (let [rows (::function-rows data)
        all  (map (fn [[sym nm spec fnvar priv err]]
                    {:seon.fn/sym sym :seon.ns/name nm :seon.fn/spec spec
                     :seon.fn/fn-var? fnvar :seon.fn/private? priv
                     :seon.fn/schema-error err})
                  rows)]
    (if ns-kw
      (filter #(= (name ns-kw) (name (:seon.ns/name %))) all)
      all)))

(defn- public-fn-rows
  "fn-rows narrowed to PUBLIC fn vars — the rows the contract checks
   apply to. Private helpers and non-fn defs are exempt."
  [req ns-kw]
  (->> (fn-rows req ns-kw)
       (filter :seon.fn/fn-var?)
       (remove :seon.fn/private?)))

(defn- parse-spec
  "Read a `:seon.fn/spec` string (pr-str'd Malli form) back to data.
   nil when blank or unreadable (e.g. a form containing #object refs)."
  [spec-str]
  (when-not (str/blank? spec-str)
    (try (edn/read-string spec-str)
         (catch :default _ nil))))

(defn- strip-props
  "Drop an optional properties map at position 1 of a Malli vector
   form: [:=> {p} in out] → [:=> in out]."
  [form]
  (if (and (vector? form) (map? (second form)))
    (into [(first form)] (drop 2 form))
    form))

(defn- arity-forms
  "All `[:=> in out]` arity forms in a parsed spec — one for a plain
   `:=>`, each child for `:function`. [] for anything else."
  [form]
  (let [form (strip-props form)]
    (cond
      (and (vector? form) (= :=> (first form)))
      [form]
      (and (vector? form) (= :function (first form)))
      (->> (rest form)
           (keep #(let [f (strip-props %)]
                    (when (and (vector? f) (= :=> (first f))) f)))
           vec)
      :else [])))

(defn- arg-entries
  "The argument slots of an arity's input form, each
   `{::label <string> ::schema <form>}`. `[:catn [name spec]…]` slots
   are labelled by name; `[:cat …]` slots by 1-based position."
  [input-form]
  (let [input (strip-props input-form)]
    (when (and (vector? input) (#{:cat :catn} (first input)))
      (if (= :catn (first input))
        (for [entry (rest input)
              :when (vector? entry)]
          {::label  (str "arg " (first entry))
           ::schema (last entry)})
        (map-indexed (fn [i s] {::label (str "arg " (inc i)) ::schema s})
                     (rest input))))))

(defn- form-contains-maybe?
  "True when the parsed Malli form contains a `[:maybe X]` anywhere."
  [form]
  (->> (tree-seq coll? seq form)
       (some #(and (vector? %) (= :maybe (first %))))
       boolean))

(defn- corpus-check
  "Build a corpus check response: run `row-fn` over the public specced
   fn rows in scope; `row-fn` returns a seq of affected entries (or
   nil). Sorted by sym for stable rendering."
  [{ns-kw :seon.warn/ns :as req} kind explain example row-fn]
  {:seon.warn/kind     kind
   :seon.warn/affected (->> (public-fn-rows req ns-kw)
                            (mapcat #(or (row-fn %) []))
                            distinct                ; multi-arity :function
                            (sort-by :seon.warn/sym) ; specs repeat a defect
                            vec)
   :seon.warn/explain  explain
   :seon.warn/example  example})

;; ============================================================
;; Corpus checks — contract defects on the :seon.fn corpus.
;; Each names the EXACT defect + location, never "one of these things".
;; ============================================================

(defn check-no-malli-schema
  "Public fn vars with NO `:malli/schema` metadata at all."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [req]
  (corpus-check
    req :no-malli-schema
    (str "Public fn has NO :malli/schema — every public fn declares its "
         "contract (all inputs + the return) so it is discoverable, "
         "validated, and reusable by other agents.")
    (str "(defn greet\n"
         "  {:malli/schema [:=> [:cat :string] :string]}\n"
         "  [who]\n"
         "  (str \"hi \" who))")
    (fn [{:seon.fn/keys [sym spec schema-error]}]
      (when (and (str/blank? spec) (str/blank? schema-error))
        [{:seon.warn/sym sym}]))))

(defn check-return-is-any
  "Fns whose RETURN spec is `:any`."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [req]
  (corpus-check
    req :return-is-any
    (str "The fn's RETURN spec is :any. Type the return — a registered "
         "response schema (e.g. ::greet-response) or a concrete type "
         "(:string, :int, [:map …]). :any is allowed only for values a "
         "third-party library returns that you don't control.")
    (str ";; before: [:=> [:cat :string] :any]\n"
         ";; after:  [:=> [:cat :string] :string]")
    (fn [{:seon.fn/keys [sym spec]}]
      (for [arity (arity-forms (parse-spec spec))
            :when (= :any (last arity))]
        {:seon.warn/sym sym :seon.warn/where "return"}))))

(defn check-arg-is-any
  "Fns where a SPECIFIC argument is `:any` — names which arg."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [req]
  (corpus-check
    req :arg-is-any
    (str "An argument's spec is :any. Type each argument — name it in a "
         "[:catn …] slot or a map entry with a concrete schema. :any is "
         "allowed only for third-party boundary values.")
    (str ";; before: [:=> [:catn [:kb.doc/title :any]] :string]\n"
         ";; after:  [:=> [:catn [:kb.doc/title :string]] :string]")
    (fn [{:seon.fn/keys [sym spec]}]
      (for [arity (arity-forms (parse-spec spec))
            {::keys [label schema]} (arg-entries (second arity))
            :when (= :any schema)]
        {:seon.warn/sym sym :seon.warn/where label}))))

(defn check-uses-maybe
  "Fns whose spec contains `[:maybe X]` anywhere."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [req]
  (corpus-check
    req :uses-maybe
    (str "The spec uses [:maybe X]. Seon's rule is absence-only: a value "
         "is either present and valid or the KEY is absent — never nil. "
         "Use {:optional true} on the map entry, or a concrete type.")
    (str ";; before: [:map [:kb.doc/title [:maybe :string]]]\n"
         ";; after:  [:map [:kb.doc/title {:optional true} :string]]")
    (fn [{:seon.fn/keys [sym spec]}]
      (when-let [form (parse-spec spec)]
        (when (form-contains-maybe? form)
          [{:seon.warn/sym sym :seon.warn/where "schema"}])))))

(defn check-no-return-spec
  "Fns whose `:=>` arity is missing its OUTPUT schema."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [req]
  (corpus-check
    req :no-return-spec
    (str "A :=> arity has an input but NO output schema. Every arity is "
         "[:=> [:cat …inputs…] <return>] — add the return.")
    (str ";; before: [:=> [:cat :string]]\n"
         ";; after:  [:=> [:cat :string] :string]")
    (fn [{:seon.fn/keys [sym spec]}]
      (for [arity (arity-forms (parse-spec spec))
            :let [input (strip-props (second arity))]
            ;; only when the input IS present (a [:cat …] form) — a
            ;; missing input is check-no-input-spec's defect, not ours
            :when (and (< (count arity) 3)
                       (vector? input)
                       (#{:cat :catn} (first input)))]
        {:seon.warn/sym sym :seon.warn/where "return"}))))

(defn check-no-input-spec
  "Fns whose `:=>` arity is missing its INPUT `[:cat …]` form."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [req]
  (corpus-check
    req :no-input-spec
    (str "A :=> arity is missing its input [:cat …]/[:catn …] form. "
         "Every arity specs ALL its arguments — a 0-arg fn uses [:cat].")
    (str ";; before: [:=> :string]\n"
         ";; after:  [:=> [:cat] :string]")
    (fn [{:seon.fn/keys [sym spec]}]
      (for [arity (arity-forms (parse-spec spec))
            :let [input (strip-props (second arity))]
            :when (and (some? input)
                       (not (and (vector? input)
                                 (#{:cat :catn} (first input)))))]
        {:seon.warn/sym sym :seon.warn/where "input"}))))

;; ============================================================
;; Domain attrs — the agent-created reuse surface.
;; ============================================================

(defn- acquired-agent-registered-attrs
  "Attr keywords an AGENT registered — provenance outside the boot seed.

   Reads the acquired `::schema-provenance` rows ([attr process-id]):
   every agent `register!` eval is teed into a `:seon.schema` row in an
   agent-origin tx, while the core's own registrations land through the
   boot process (`seon.client/index-schemas`). Provenance — not a
   keyword-namespace pattern — is the rule, so agent-authored `seon.*`
   data domains (e.g. `:my.workout/*`) stay visible on the reuse surface
   with NO list to maintain."
  [data]
  (into #{}
        (keep (fn [[attr process-id]]
                (when (not= process-id :seon.db.process/boot) attr)))
        (::schema-provenance data)))

(defn domain-attrs
  "Every DOMAIN attr installed on `db` — agent-registered, not core.

   The acquired datahike schema attrs
   intersected with [[acquired-agent-registered-attrs]] (provenance: the
   attr's
   `:seon.schema/key` row was asserted OUTSIDE the boot seed). These
   are the attrs agents registered for the human's data — INCLUDING
   `seon.*` data domains like `:my.workout/*` — the reuse surface
   [[check-parallel-attr]] guards.
   Core attrs (`:seon.db/*`, `:seon.agent/*`, …) stay hidden
   because their rows land through the boot process.
   Derived from the acquired database value's schema (NOT the live
   registry), so it
   survives pod restarts and stays per-conn. An attr appears once data
   (or schema installation via the first transact!) has landed."
  {:malli/schema [:=> [:cat ::check-request] [:vector :keyword]]}
  [{data ::data}]
  (let [schema      (::installed-schema data)
        agent-attrs (acquired-agent-registered-attrs data)]
    (->> (keys schema)
         (filter keyword?)
         (filter namespace)
         (filter agent-attrs)
         distinct
         (sort-by str)
         vec)))

(def ^:private unit-suffixes
  "Unit-ish final name-tokens. Two attrs in one namespace sharing a
   stem but differing in a suffix from this set are the same quantity
   forked into different units — the parallel-attr defect."
  #{"ms" "millis" "milliseconds" "seconds" "secs" "minutes" "mins"
    "hours" "days" "meters" "km" "miles" "kg" "lbs" "grams"})

(defn- unit-split
  "Split an attr's NAME into [stem unit-suffix] when its final
   dash-token is unit-ish; nil otherwise (:workout/date → nil, so
   date/type-style attrs can never collide)."
  [attr]
  (let [tokens (str/split (name attr) #"-")]
    (when (and (> (count tokens) 1)
               (contains? unit-suffixes (last tokens)))
      [(str/join "-" (butlast tokens)) (last tokens)])))

(defn- attr-instance-count
  "Count of entities carrying `attr` — the acquired AEVT count."
  [{data ::data} attr]
  (get (::attribute-counts data) attr 0))

(defn check-parallel-attr
  "DOMAIN attrs naming the SAME quantity in DIFFERENT units.

   Detected within the SAME keyword namespace — e.g. a registered
   :workout/duration-minutes
   beside the existing :workout/duration-seconds (same ns, shared stem
   'duration', both unit-ish suffixes). GLOBAL — keyword namespaces are
   data domains, not code nses, so :seon.warn/ns is ignored. Within a
   collision group, the attr with the MOST stored instances is the
   established one; every other member is flagged against it."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:as req}]
  (let [groups (->> (domain-attrs req)
                    (keep (fn [attr]
                            (when-let [[stem _] (unit-split attr)]
                              {::attr  attr
                               ::group [(namespace attr) stem]})))
                    (group-by ::group)
                    vals
                    (filter #(> (count %) 1)))]
    {:seon.warn/kind :parallel-attr
     :seon.warn/affected
     (->> groups
          (mapcat
            (fn [members]
              (let [ranked (->> members
                                (map (fn [{::keys [attr]}]
                                       {::attr attr
                                        ::n    (attr-instance-count req attr)}))
                                (sort-by (fn [{::keys [attr n]}]
                                           [(- n) (str attr)])))
                    {established ::attr est-n ::n} (first ranked)]
                (for [{::keys [attr]} (rest ranked)]
                  {:seon.warn/sym   (str attr)
                   :seon.warn/where (str "vs established " established
                                         " (" est-n " entit"
                                         (if (= 1 est-n) "y" "ies") ")")}))))
          (sort-by :seon.warn/sym)
          vec)
     :seon.warn/explain
     (str "Two attrs in the same namespace store the SAME quantity in "
          "DIFFERENT units (shared name-stem, unit suffixes). This forks "
          "the data model — one aggregate query can no longer see all the "
          "data. Use the EXISTING attr everywhere, converting units at "
          "write time, and rewrite any rows already stored under the "
          "forked attr.")
     :seon.warn/example
     (str ";; use the existing :workout/duration-seconds; convert at write time:\n"
          "{:workout/duration-seconds (* 35 60)}  ; NOT :workout/duration-minutes 35")}))

(defn- identity-attrs
  "Every identity attr on the acquired datahike schema
   (`:db/unique :db.unique/identity`), excluding datahike's own `:db/*`
   attrs (`:db/ident` is unique-identity by construction and carries a
   datom per installed attr — it is schema plumbing, not a kind)."
  [{data ::data}]
  (->> (::installed-schema data)
       (keep (fn [[k v]]
               (when (and (keyword? k)
                          (not= "db" (namespace k))
                          (= :db.unique/identity (:db/unique v)))
                 k)))))

(defn- acquired-schema-forms
  [data]
  (into {}
        (keep (fn [[k form]]
                (when (and k (string? form))
                  (try [k (edn/read-string form)]
                       (catch :default _ nil)))))
        (::schema-forms data)))

(defn- marked-entity-id-attrs
  "The id attrs derived for committed entity-map declarations.

   Canonical schema forms retain only authored properties. The active
   projection's entity catalog owns the derived identity association; never
   search canonical forms for the retired `:seon.entity/id-attr` copy."
  [data]
  (into #{}
        (map :seon.schema.catalog/id-attr)
        (:seon.schema.projection/catalog
         (schema/build-projection (acquired-schema-forms data)))))

(defn- unmarked-map-schemas-carrying
  "Registered `:map` schemas that have an entry for `attr` but NO
   `{:seon.db/entity true}` marker — the schema(s) an author most
   likely MEANT to mark. Sorted for stable rendering."
  [data attr]
  (->> (acquired-schema-forms data)
       (keep (fn [[k v]]
               (when (and (vector? v) (= :map (first v)))
                 (let [props   (when (map? (second v)) (second v))
                       entries (if props (drop 2 v) (rest v))]
                   (when (and (not (:seon.db/entity props))
                              (some #(and (vector? %) (= attr (first %)))
                                    entries))
                     k)))))
       (sort-by str)))

(defn check-unmarked-entity-kinds
  "Identity attrs with stored datoms but no `{:seon.db/entity true}` map.

   BEHAVIORAL entity-marker check: identity attrs that HAVE stored
   datoms but NO registered `:map` schema marked `{:seon.db/entity
   true}` declaring them as a kind. Replaces the register!-time warn,
   which was a false-positive generator by construction — at
   registration an id-carrying map is indistinguishable between
   unmarked-entity and legitimate envelope; once rows EXIST under the
   id-attr, an undeclared kind is a real defect. Derived at render,
   self-heals the moment the entity map is marked. Fires GLOBALLY across
   agent-authored identity attrs (:seon.warn/ns is ignored), but excludes
   the exact attrs whose schema facts were authored by the boot process.
   Agents cannot and should not re-register compiled core schemas, so nagging
   them about an unmarked boot attr is a no-op task. The fix for a genuine
   core entity is to mark its owning :map schema {:seon.db/entity true} at
   source; ordinary request/config rows correctly remain unmarked.

   DEV-ONLY: this is the one check that is purely about the
   entity-renderer marker — a dev/web-UI display concern, not an agent
   task (the rows remain directly queryable). It is tagged
   `:seon.warn/dev-only? true` so
   [[render-warnings]] drops it from the AGENT prompt (the agent is told to
   store my.kb.* facts under identity attrs — nagging that a correct write
   is \"invisible\" frames the right action as broken). The check still
   runs globally and stays visible in the dev/web-UI surface via
   `:seon.warn/include-dev? true`."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{data ::data :as req}]
  (let [marked (marked-entity-id-attrs data)
        boot-attrs
        (into #{}
              (keep (fn [[attr process-id]]
                      (when (= process-id :seon.db.process/boot)
                        attr)))
              (::schema-provenance data))]
    {:seon.warn/kind :unmarked-entity-kinds
     :seon.warn/dev-only? true
     :seon.warn/affected
     (->> (identity-attrs req)
          (remove marked)
          (remove boot-attrs)
          (filter #(pos? (attr-instance-count req %)))
          (sort-by str)
          (mapv (fn [attr]
                  (let [carriers (unmarked-map-schemas-carrying data attr)]
                    (cond-> {:seon.warn/sym (str attr)}
                      (seq carriers)
                      (assoc :seon.warn/where
                             (str "carried unmarked by "
                                  (str/join ", " (map str carriers)))))))))
     :seon.warn/explain
     (str "Rows are STORED under an identity attr but no registered :map "
          "schema marked {:seon.db/entity true} declares that kind — its "
          "entities are invisible to the entity renderer (the rows remain "
          "directly queryable by attribute presence). "
          "Register (or re-register) the kind's :map schema WITH the "
          "marker. Request/response envelopes stay unmarked; this fires "
          "only where rows actually exist.")
     :seon.warn/example
     (str "(seon.schema/register! :kb.doc\n"
          "  [:map {:seon.db/entity true}\n"
          "   [:kb.doc/path :kb.doc/path]  ; the identity attr\n"
          "   …])")}))

;; NOTE: there is deliberately NO "missing example/test" corpus check.
;; A usage example (a `defn` with `:test` var-meta) is OPT-IN — it
;; is authored ONLY when the fn's `:malli/schema` + the ns's rendered
;; schemas don't already make the call obvious. Most well-specced fns
;; need NO example, so a blanket "this fn has no test" warning would nag
;; every trivial fn and contradict the opt-in model (same reasoning as
;; "identity is OPTIONAL — don't force/warn it"). A test that is
;; currently FAILING is a real defect and DOES surface — see
;; [[check-failing-tests]] (a runtime check on the eval/test log).

;; ============================================================
;; Runtime checks — current problems in the eval/test log. GLOBAL
;; (cross-agent visibility is the point); :seon.warn/ns is ignored.
;; ============================================================

(def slow-eval-threshold-ms 500)

(def hop-cap
  "Max `:seon.agent.message/hops` before the wake trigger refuses a message
   (agent↔agent ping-pong guard). Lives here (not seon.agent) so both
   the trigger (seon.agent) and `check-hop-exhausted` read ONE value
   without a require cycle. hops = 0 when from = the user; an
   agent-originated send carries the SAME {me,peer}-pair's prior depth
   + 1 (per-peer, reset at each human message — `outbound-hops`), so a
   genuine A↔B↔A↔B runaway trips at the cap while distinct delegation
   rounds (parent→A then parent→B) never accumulate."
  4)

(defn- failed-eval-rows
  "[eval-id error-string] rows for failed evals since the latest user
   message (every failed eval when no user message exists yet). The
   \"since the latest user message\" cutoff is applied by the acquisition
   queries in seon.agent.ctx.warnings; every row here is already in scope."
  [{data ::data}]
  (::failed-evals data))

(defn- clip [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

(def ^:private bad-ref-marker
  "The cryptic datahike message a lookup-ref against a non-identity
   attr produces — translated by check-bad-ref."
  "Lookup ref attribute should be marked as :db/unique")

(defn check-failed-evals
  "Failed evals since the latest user message, anywhere (cross-agent).

   Excludes bad-ref failures (check-bad-ref owns those).
   Vanishes when the next user msg lands and subsequent evals succeed."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:as req}]
  {:seon.warn/kind :failed-evals
   :seon.warn/affected
   (->> (failed-eval-rows req)
        (remove (fn [[_ err]] (str/includes? err bad-ref-marker)))
        (sort-by first)
        (mapv (fn [[eid err]]
                (cond-> {:seon.warn/sym (str eid)}
                  (not (str/blank? err))
                  (assoc :seon.warn/where (clip err 120))))))
   :seon.warn/explain
   (str "Evals FAILED since the latest user message (cross-agent). "
        "Errors are values — the eval row holds the full error data; "
        "inspect it and adapt instead of retrying blind.")
   :seon.warn/example
   (str "(seon.db/pull {:seon.db/pull-pattern [:seon.eval/source :seon.eval/error\n"
        "                                      :seon.eval/error-data]\n"
        "               :seon.db/ref [:seon.eval/id \"<eval-id>\"]})")})

(defn check-bad-ref
  "Failed evals whose error is datahike's cryptic lookup-ref message.

   Translated into the real fix: the target attr needs
   {:seon.db/identity true}, or the referenced entity doesn't exist."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:as req}]
  {:seon.warn/kind :bad-ref
   :seon.warn/affected
   (->> (failed-eval-rows req)
        (filter (fn [[_ err]] (str/includes? err bad-ref-marker)))
        (sort-by first)
        (mapv (fn [[eid err]]
                (let [attr (second (re-find #":db/unique:?\s*\[\s*(:[^\s\]]+)"
                                            err))]
                  (cond-> {:seon.warn/sym (str eid)}
                    attr (assoc :seon.warn/where (str "lookup-ref on " attr)))))))
   :seon.warn/explain
   (str "A transact used a lookup ref [:attr v] whose target attr is "
        "not registered with {:seon.db/identity true} (datahike says "
        "\"Lookup ref attribute should be marked as :db/unique\"). "
        "Usually the fix is NOT identity: query for the entity's eid "
        "and reference that, or transact the entity first (or via a "
        "tempid in the same tx). Do NOT re-register an EXISTING attr "
        "to add identity — that mutates a shared data model. Identity "
        "is only for a NEW attr that is the kind's natural key.")
   :seon.warn/example
   (str ";; reference by eid instead of a lookup-ref on a non-identity attr.\n"
        ";; First, query for the eid as its OWN top-level form (the runtime\n"
        ";; awaits it and returns the rows):\n"
        "(seon.db/query {:seon.db/query '[:find ?e :where [?e :kb.doc/path \"a.md\"]]})\n"
        ";; ⟹ #{[42]} — then use the returned eid directly:\n"
        "{:kb.note/doc 42}  ; NOT {:kb.note/doc [:kb.doc/path \"a.md\"]}")})

(def ^:private fs-error-key-marker
  "The pr-str'd `:seon.agent.fs/error` key — its presence in a result
   projection marks an fs op that returned a failure envelope."
  ":seon.agent.fs/error")

(def ^:private fs-denial-marker
  "Substring present in BOTH allowlist-denial messages seon.agent.fs
   produces (`scope-denied`): \"path outside allowed-roots …\" and
   \"…no allowed-roots configured…\". A grants-response also mentions
   allowed-roots but never carries `:seon.agent.fs/error`, so the two
   markers TOGETHER identify a denial."
  "allowed-roots")

(defn- fs-denial-text
  "Extract the `:seon.agent.fs/error` denial string from a result-edn
   projection (the result may nest the fs response inside a larger
   value). Falls back to a clip of the raw edn when unparseable."
  [edn-str]
  (let [v (try (edn/read-string edn-str) (catch :default _ nil))]
    (or (->> (tree-seq coll? seq v)
             (keep #(when (map? %) (:seon.agent.fs/error %)))
             (filter #(and (string? %) (str/includes? % fs-denial-marker)))
             first)
        (clip edn-str 120))))

(defn- fs-denied-eval-rows
  "[eval-id denial-text] rows for evals since the latest user message
   whose RESULT carries an fs allowlist denial. seon.agent.fs ops never
   throw — a denial is an ok? false RESULT map (the eval itself
   SUCCEEDS), so this scans the acquired `:seon.eval/result-edn` rows,
   not `:seon.eval/error`. The user-message cutoff is applied by the
   acquisition query; marker filtering happens here in Clojure, not in a
   :where predicate (datahike-cljs string predicates in :where are a
   known trap)."
  [{data ::data}]
  (->> (::fs-results data)
       (filter (fn [[_ edn _]]
                 (and (str/includes? edn fs-error-key-marker)
                      (str/includes? edn fs-denial-marker))))
       (map (fn [[eid edn _]] [eid (fs-denial-text edn)]))))

(defn check-fs-denied
  "fs calls DENIED by the capability allowlist since the last user msg.

   The grant-mismatch shape where an agent INFERRED its grant
   from a CWD listing (wrongly — the granted root was an ancestor)
   instead of reading the configured truth via
   `(seon.agent.fs/grants)`. DERIVED from the eval log at render time;
   self-heals when a new user message lands and subsequent fs calls
   stay in scope. GLOBAL — :seon.warn/ns is ignored."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:as req}]
  {:seon.warn/kind :fs-denied
   :seon.warn/affected
   (->> (fs-denied-eval-rows req)
        (sort-by first)
        (mapv (fn [[eid text]]
                {:seon.warn/sym   (str eid)
                 :seon.warn/where (clip text 120)})))
   :seon.warn/explain
   (str "A seon.agent.fs call was DENIED by the allowlist — the path is "
        "outside the configured grant (or no grant is configured at all). "
        "Do NOT infer your grant from a directory listing or your current "
        "directory: the granted root is often an ANCESTOR of where you "
        "happen to be looking. Read the CONFIGURED truth and stay under "
        "those roots.")
   :seon.warn/example
   (str "(seon.agent.fs/grants)\n"
        "; ⟹ «map: :seon.agent.fs/allowed-roots [\"/Users/me/work\"], :seon.agent.fs/read-only? false»")})

(defn check-hop-exhausted
  "Messages dropped at the hop cap since the last user message.

   DEAD-LETTER surface — messages whose `:seon.agent.message/hops` reached
   [[hop-cap]] SINCE the latest user message. Each is a wake the trigger
   REFUSED (a same-pair agent↔agent reply chain hit the ping-pong guard
   and was dropped on the floor — the recipient NEVER ran, and the sender
   often went `:idle` thinking it succeeded). Rendering it here, named
   `from X → Y`, is the dead-letter: the sender, the recipient (when it
   next renders), and the human all SEE the bounce instead of a silent
   deadlock. GLOBAL (cross-agent) on purpose. A fresh human message resets
   the chain and scopes these out — self-healing, nothing to clear."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{data ::data}]
  (let [rows (::hop-messages data)]
    {:seon.warn/kind :hop-exhausted
     :seon.warn/affected
     (->> rows
          (sort-by first)
          (mapv (fn [[mid hops _ fid tid]]
                  {:seon.warn/sym   (str mid)
                   :seon.warn/where (str "from " fid " → " tid
                                         " — REFUSED at hops " hops "/" hop-cap
                                         " (recipient never ran)")})))
     :seon.warn/explain
     (str "DEAD-LETTER: a same-pair agent↔agent reply chain hit the hop cap ("
          hop-cap "). The wake trigger REFUSED these messages, so their "
          "recipients never woke and the senders may have gone idle believing "
          "they delivered. The cap is a PING-PONG guard (one pair bouncing) — "
          "distinct delegation rounds do not accumulate. If you are the sender, "
          "your message did NOT land: stop replying to replies; message the "
          "HUMAN (resets the chain to hops 0) to continue the thread.")
     :seon.warn/example
     "(seon.agent/message! {:seon.agent.message/content \"summary for you — …\"})  ; to defaults to the user"}))

;; NOTE: there is deliberately NO record-errors check. The partial-record
;; class it guarded (a stamped `:seon.eval/record-error` beside a bare eval
;; row) was removed when eval publication became atomic (346e70fa): a
;; dropped eval record now persists NOTHING transcript-shaped, and
;; record-eval!'s tx-failure branch records the drop through the ONE fault
;; path (`seon.error/record!`, fault `:core` — b109266e). Those fault
;; datoms already have their one derived surface:
;; `seon.agent.ctx.warnings/core-faults-block` (root-only by the 2026-07-04
;; error-blame ruling — core faults are ours to fix, not any agent's task).

(defn check-slow-evals
  "Evals over the slow threshold in the last hour, anywhere.

   Stops surfacing when new evals are fast and the offenders age out.
   The threshold + last-hour window are applied by the acquisition query."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{data ::data}]
  {:seon.warn/kind :slow-evals
   :seon.warn/affected
   (->> (::slow-evals data)
        (sort-by first)
        (mapv (fn [[eid dur]]
                {:seon.warn/sym   (str eid)
                 :seon.warn/where (str dur "ms")})))
   :seon.warn/explain
   (str "Evals took ≥" slow-eval-threshold-ms "ms in the last hour. "
        "Narrow the query (specific attrs, not [*]) or compute less "
        "per form.")
   :seon.warn/example
   ";; pull named attrs, not '[*]; add :where clauses to narrow"})

(defn check-failing-tests
  "Tests whose last run failed (last-failed-at > last-passed-at)."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{data ::data}]
  {:seon.warn/kind :failing-tests
   :seon.warn/affected
   (->> (::failing-tests data)
        (map first)
        sort
        (mapv (fn [sym] {:seon.warn/sym (str sym)})))
   :seon.warn/explain
   (str "Tests are FAILING (their last run failed). Re-run after fixing: "
        "the warning clears itself when the test passes.")
   :seon.warn/example
   "(seon.test.runner/run-vars {:seon.test.runner/vars ['my.ns/my-test]})"})

(defn check-canvas-unresolved
  "Canvases pointing at a fn symbol absent from the current program.

   `:seon.render.canvas/content` names a qualified fn symbol that
   has no current `:seon.fn/sym`, so the human sees the guarded render-error
   card instead of the intended view. Core renderers resolve from the compiled
   package. Literal hiccup canvases (vectors), current authored functions, and
   compiled core functions produce nothing. DERIVED at render; self-heals the
   moment the fn is defined. GLOBAL — :seon.warn/ns is ignored."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{data ::data :as request}]
  (let [rows (::canvases data)
        current-authored-symbols
        (into #{} (map (comp symbol :seon.fn/sym)) (fn-rows request nil))]
    {:seon.warn/kind :canvas-unresolved
     :seon.warn/urgent? true
     :seon.warn/affected
     (->> rows
          (keep (fn [[aid content]]
                  (let [decoded (db/decode-edn-value
                                  :seon.render.canvas/content content)]
                    (when (and (qualified-symbol? decoded)
                               (not (contains? current-authored-symbols decoded))
                               (nil? (eval/lookup-value decoded)))
                      {:seon.warn/sym   (str decoded)
                       :seon.warn/where (str "canvas of " aid)}))))
          (sort-by :seon.warn/sym)
          vec)
     :seon.warn/explain
     (str "Your canvas selects a function that is absent from the current "
          "database program, so the human sees the guarded render-error card "
          "instead of the intended view. Define that exact qualified function "
          "with a :malli/schema and return Hiccup through my.canvas/view; the "
          "canvas updates after the successful eval. Alternatively call "
          "my.canvas/show! with an existing qualified function or literal "
          "Hiccup. Define a replacement before removing or renaming the "
          "selected function.")
     :seon.warn/example
     (str "(defn my-canvas\n"
          "  {:malli/schema [:=> [:cat :seon.render/system-input]\n"
          "                  :seon.render/html-response]}\n"
          "  [{:seon.db/keys [db] :seon.agent/keys [id]}]\n"
          "  (my.canvas/view\n"
          "    {:my.canvas/content\n"
          "     [:div {:class \"seon-card\"} \"hi\"]}))\n"
          "(my.canvas/show! {:my.canvas/content `my-canvas})")}))

;; ============================================================
;; Registry + clustered renderer
;; ============================================================

(def checks
  "The registry [[render-warnings]] composes. Order = render order:
   contract defects first (teaching), runtime problems last (urgent,
   closest to the prompt). Add a check by conj'ing a fn with the
   ::check-request → ::check-response contract."
  [check-no-malli-schema
   check-return-is-any
   check-arg-is-any
   check-uses-maybe
   check-no-return-spec
   check-no-input-spec
   check-parallel-attr
   check-unmarked-entity-kinds
   check-bad-ref
   check-failed-evals
   check-fs-denied
   check-hop-exhausted
   check-slow-evals
   check-failing-tests
   check-canvas-unresolved])

(defn- check-name
  "Best-effort display name for a registry fn — the demunged compiled
   fn name (e.g. `seon.warn/check-bad-ref`)."
  [check]
  (let [n (.-name check)]
    (if (seq n) (demunge n) "anonymous-check")))

(defn- check-error-cluster
  "Synthetic ::check-response for a registry check that THREW instead
   of returning a cluster — the section degrades PER CHECK, loudly,
   instead of one broken check killing the whole WARNINGS block.
   Self-heals: renders only while the check keeps throwing."
  [check e]
  (let [nm (check-name check)]
    {:seon.warn/kind     :warn-check-error
     :seon.warn/affected [{:seon.warn/sym nm}]
     :seon.warn/explain  (str "warn check " nm " failed: "
                              (or (ex-message e) (str e))
                              " — its warnings are MISSING this render; "
                              "every other check rendered normally.")
     :seon.warn/example  (str ";; checks are PURE over pre-acquired data — reproduce the throw\n"
                              ";; with plain data (no database read), then fix the check:\n"
                              "(" nm " {:seon.warn/data {}})")}))

(defn run-checks
  "Run every registered check; return the non-clean responses.

   Only responses with at least one affected entry are returned. A
   check that THROWS becomes its own `:warn-check-error` cluster — the
   remaining checks still run and render (degrade per-check, loudly)."
  {:malli/schema [:=> [:cat ::check-request] [:vector ::check-response]]}
  [req]
  (->> checks
       (map (fn [check]
              (try (check req)
                   (catch :default e
                     (check-error-cluster check e)))))
       (filterv (comp seq :seon.warn/affected))))

(defn- render-affected-entry
  [{:seon.warn/keys [sym where]}]
  (if where (str sym " (" where ")") sym))

(defn- render-cluster
  "ONE cluster as a single-`;` comment-block:
   `; [kind] explanation`, ONE fix example as `;` lines, then the
   affected list with specific locations. Positive-framing: it names what
   TO do (the fix), and these warnings DERIVE from current state — each
   vanishes the moment you correct it. The explanation appears once per
   kind, never once per fn."
  [{:seon.warn/keys [kind affected explain example]}]
  (str "; [" (name kind) "] " explain "\n"
       "; Fix it like this:\n"
       (str/join "\n" (map #(str ";   " %) (str/split-lines example)))
       "\n"
       "; Affecting: "
       (str/join ", " (map render-affected-entry affected))
       " (" (count affected) "). Correct these and this note clears itself."))

(defn- render-urgent-cluster
  "A LOUD cluster for a `:seon.warn/urgent? true` check — something the
   human is hitting THIS render (e.g. a broken canvas). Unmistakable
   `‼ URGENT` banner as a single-`;` line, then the same explanation + fix
   example + affected list. Rendered at the TOP of the WARNINGS block,
   ahead of the ordinary contract/runtime clusters."
  [{:seon.warn/keys [kind affected explain example]}]
  (str "; ‼ URGENT [" (name kind) "] " explain "\n"
       "; Fix it like this:\n"
       (str/join "\n" (map #(str ";   " %) (str/split-lines example)))
       "\n"
       "; Affecting: "
       (str/join ", " (map render-affected-entry affected))
       " (" (count affected) "). Fix this now — it auto-resolves the "
       "moment you do."))

(defn render-warnings
  "Render the non-clean checks as a single WARNINGS comment-block.

   Run the registry and render the non-clean checks: a single-`;` `WARNINGS`
   heading, then one `;` cluster per kind. URGENT clusters
   (`:seon.warn/urgent? true`) render FIRST with a louder template; the
   remaining clusters follow in registry order. Empty string when clean.
   Scope the corpus checks with `:seon.warn/ns`; omit it for the
   whole-core overview.

   DEV-ONLY clusters (`:seon.warn/dev-only? true`, e.g.
   [[check-unmarked-entity-kinds]]) are DROPPED unless the request carries
   `:seon.warn/include-dev? true`. The AGENT render path (ctx/warnings.cljs)
   passes nothing → dev-hygiene is hidden from agents; the dev/web-UI
   surface opts in to still see it. Derived classification + render-time
   filter — nothing stored (reactive-context model)."
  {:malli/schema [:=> [:cat ::check-request] :string]}
  [req]
  (let [clusters         (cond->> (run-checks req)
                           (not (:seon.warn/include-dev? req))
                           (remove (comp boolean :seon.warn/dev-only?)))
        {urgent  true
         ordinary false} (group-by (comp boolean :seon.warn/urgent?) clusters)]
    (if (seq clusters)
      (str "; WARNINGS\n"
           (str/join "\n\n"
                     (concat (map render-urgent-cluster urgent)
                             (map render-cluster ordinary))))
      "")))
