(ns seon.warn
  "Compositional, clustered warning checks over the program-graph corpus
   (`:seon.fn` / `:seon.test` / `:seon.eval`).

   Each `check-<kind>` is a separate, independently-testable fn:

     (check-return-is-any {:seon.db/db db :seon.warn/ns :my.domain})
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
   no-return-spec / no-input-spec / missing-test) to one namespace —
   the caller (seon.agent/warnings-section) defaults it to the agent's
   CURRENT ns so an agent isn't confused by other namespaces' defects.
   Omit it for the whole-substrate overview. The RUNTIME checks
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

(schema/register! :seon.warn/affected-entry
  [:map
   [:seon.warn/sym :seon.warn/sym]
   [:seon.warn/where {:optional true} :seon.warn/where]])

(schema/register! :seon.warn/affected
  [:vector :seon.warn/affected-entry])

(schema/register! ::check-request
  [:map
   [:seon.db/db   :seon.db/db]
   [:seon.warn/ns {:optional true} :seon.warn/ns]])

(schema/register! ::check-response
  [:map
   [:seon.warn/kind     :seon.warn/kind]
   [:seon.warn/affected :seon.warn/affected]
   [:seon.warn/explain  :seon.warn/explain]
   [:seon.warn/example  :seon.warn/example]])

;; ============================================================
;; Corpus access + Malli-form walking helpers
;; ============================================================

(defn- fn-rows
  "Every `:seon.fn` row joined to its owning ns name, optionally
   filtered to `ns-kw` (compared by `name` so :my.ns ≡ 'my.ns).
   Each row: {:sym :ns :spec :fn-var? :private? :schema-error}.
   Absent attrs come back as \"\" / sentinel defaults via get-else."
  [db ns-kw]
  (let [rows (db/query
               {:seon.db/db db
                :seon.db/query
                '[:find ?sym ?nm ?spec ?fnvar ?priv ?err
                  :where
                  [?f :seon.fn/sym ?sym]
                  [?f :seon.fn/ns ?ns]
                  [?ns :seon.ns/name ?nm]
                  [(get-else $ ?f :seon.fn/spec "") ?spec]
                  [(get-else $ ?f :seon.fn/fn-var? true) ?fnvar]
                  [(get-else $ ?f :seon.fn/private? false) ?priv]
                  [(get-else $ ?f :seon.fn/schema-error "") ?err]]})
        all  (map (fn [[sym nm spec fnvar priv err]]
                    {:sym sym :ns (name nm) :spec spec
                     :fn-var? fnvar :private? priv :schema-error err})
                  rows)]
    (if ns-kw
      (filter #(= (name ns-kw) (:ns %)) all)
      all)))

(defn- public-fn-rows
  "fn-rows narrowed to PUBLIC fn vars — the rows the contract checks
   apply to. Private helpers and non-fn defs are exempt."
  [db ns-kw]
  (->> (fn-rows db ns-kw)
       (filter :fn-var?)
       (remove :private?)))

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
   `{:label <string> :schema <form>}`. `[:catn [name spec]…]` slots are
   labelled by name; `[:cat …]` slots by 1-based position."
  [input-form]
  (let [input (strip-props input-form)]
    (when (and (vector? input) (#{:cat :catn} (first input)))
      (if (= :catn (first input))
        (for [entry (rest input)
              :when (vector? entry)]
          {:label  (str "arg " (first entry))
           :schema (last entry)})
        (map-indexed (fn [i s] {:label (str "arg " (inc i)) :schema s})
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
  [{:seon.db/keys [db] ns-kw :seon.warn/ns} kind explain example row-fn]
  {:seon.warn/kind     kind
   :seon.warn/affected (->> (public-fn-rows db ns-kw)
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
    (fn [{:keys [sym spec schema-error]}]
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
    (fn [{:keys [sym spec]}]
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
    (fn [{:keys [sym spec]}]
      (for [arity (arity-forms (parse-spec spec))
            {:keys [label schema]} (arg-entries (second arity))
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
    (fn [{:keys [sym spec]}]
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
    (fn [{:keys [sym spec]}]
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
    (fn [{:keys [sym spec]}]
      (for [arity (arity-forms (parse-spec spec))
            :let [input (strip-props (second arity))]
            :when (and (some? input)
                       (not (and (vector? input)
                                 (#{:cat :catn} (first input)))))]
        {:seon.warn/sym sym :seon.warn/where "input"}))))

;; ============================================================
;; Domain attrs — the agent-created reuse surface.
;; ============================================================

(defn agent-registered-attrs
  "PUBLIC: the `seon.ctx/context-model` classifier consumes this as its
   `:seon.ctx/agent-attrs` leg — ONE provenance query for the attr
   surface, shared by domain-attrs and the classifier (V3-C).

   The set of attr keywords whose `:seon.schema/key` row landed in a tx
   that does NOT carry `:seon.db/origin :substrate-seed` — i.e. attrs
   registered by an AGENT (every agent `register!` eval is teed into a
   `:seon.schema` row by `seon.eval/build-tee-entities`, in an
   agent-origin tx), as opposed to the substrate's own registrations
   (boot-seeded by `seon.client/index-schemas` /
   `all-entity-schemas-tx-data`, always inside the
   `{:seon.db/origin :substrate-seed}` tx-context, forge-guarded in
   `seon.db`).

   This PROVENANCE rule replaces the old keyword-namespace blanket
   `(db|seon)(\\..*)?` — which wrongly hid agent-authored `seon.*` data
   domains (the live store's `:my.workout/*`) from the whole reuse
   surface (gym S-21 root cause, 2026-06-10). Provenance stays correct
   as the substrate grows with NO list to maintain: new substrate
   registrations arrive via the boot seed (seed origin → hidden), and
   anything an agent registers is teed in its own tx (→ visible),
   whatever keyword namespace it picks."
  [db]
  (let [seed-txs (into #{}
                       (map first)
                       (db/query {:seon.db/db db
                                  :seon.db/query
                                  '[:find ?tx
                                    :where
                                    [?tx :seon.db/origin :substrate-seed]]}))]
    (into #{}
          (keep (fn [[k tx]] (when-not (contains? seed-txs tx) k)))
          (db/query {:seon.db/db db
                     :seon.db/query
                     '[:find ?k ?tx
                       :where [?s :seon.schema/key ?k ?tx]]}))))

(defn domain-attrs
  "Every DOMAIN attr installed on `db` — the db's datahike schema attrs
   intersected with [[agent-registered-attrs]] (provenance: the attr's
   `:seon.schema/key` row was asserted OUTSIDE the boot seed). These
   are the attrs agents registered for the human's data — INCLUDING
   `seon.*` data domains like `:my.workout/*` — the reuse surface
   the schema-catalog renders and [[check-parallel-attr]] guards.
   Substrate attrs (`:seon.db/*`, `:seon.agent/*`, …) stay hidden
   because their rows land under `:seon.db/origin :substrate-seed`.
   Derived from the db value itself (NOT the live registry), so it
   survives pod restarts and stays per-conn. An attr appears once data
   (or schema installation via the first transact!) has landed."
  {:malli/schema [:=> [:cat ::check-request] [:vector :keyword]]}
  [{:seon.db/keys [db]}]
  (let [schema      (db/installed-schema db)
        agent-attrs (agent-registered-attrs db)]
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
  "Count of entities carrying `attr` — one AEVT count."
  [db attr]
  (count (db/query {:seon.db/db db
                    :seon.db/query [:find '?e :where ['?e attr '_]]})))

(defn check-parallel-attr
  "DOMAIN attrs in the SAME keyword namespace naming the SAME quantity
   in DIFFERENT units — e.g. a registered :workout/duration-minutes
   beside the existing :workout/duration-seconds (same ns, shared stem
   'duration', both unit-ish suffixes). GLOBAL — keyword namespaces are
   data domains, not code nses, so :seon.warn/ns is ignored. Within a
   collision group, the attr with the MOST stored instances is the
   established one; every other member is flagged against it."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db] :as req}]
  (let [groups (->> (domain-attrs req)
                    (keep (fn [attr]
                            (when-let [[stem _] (unit-split attr)]
                              {:attr attr
                               :group [(namespace attr) stem]})))
                    (group-by :group)
                    vals
                    (filter #(> (count %) 1)))]
    {:seon.warn/kind :parallel-attr
     :seon.warn/affected
     (->> groups
          (mapcat
            (fn [members]
              (let [ranked (->> members
                                (map (fn [{:keys [attr]}]
                                       {:attr attr
                                        :n    (attr-instance-count db attr)}))
                                (sort-by (fn [{:keys [attr n]}]
                                           [(- n) (str attr)])))
                    {established :attr est-n :n} (first ranked)]
                (for [{:keys [attr]} (rest ranked)]
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
  "Every identity attr installed on `db`'s datahike schema
   (`:db/unique :db.unique/identity`), excluding datahike's own `:db/*`
   attrs (`:db/ident` is unique-identity by construction and carries a
   datom per installed attr — it is schema plumbing, not a kind)."
  [db]
  (->> (db/installed-schema db)
       (keep (fn [[k v]]
               (when (and (keyword? k)
                          (not= "db" (namespace k))
                          (= :db.unique/identity (:db/unique v)))
                 k)))))

(defn- marked-entity-id-attrs
  "The set of id-attrs DECLARED by registered `:map` schemas carrying
   `{:seon.db/entity true}` — `register!` derives `:seon.entity/id-attr`
   into the stored props, so its presence ≡ the marker + an id entry."
  []
  (into #{}
        (keep (fn [[_ v]]
                (when (and (vector? v) (= :map (first v)) (map? (second v)))
                  (:seon.entity/id-attr (second v)))))
        (schema/registered-schemas)))

(defn- unmarked-map-schemas-carrying
  "Registered `:map` schemas that have an entry for `attr` but NO
   `{:seon.db/entity true}` marker — the schema(s) an author most
   likely MEANT to mark. Sorted for stable rendering."
  [attr]
  (->> (schema/registered-schemas)
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
  "BEHAVIORAL entity-marker check: identity attrs that HAVE stored
   datoms but NO registered `:map` schema marked `{:seon.db/entity
   true}` declaring them as a kind. Replaces the register!-time warn,
   which was a false-positive generator by construction — at
   registration an id-carrying map is indistinguishable between
   unmarked-entity and legitimate envelope; once rows EXIST under the
   id-attr, an undeclared kind is a real defect. Derived at render,
   self-heals the moment the kind is marked. GLOBAL — fires on
   substrate and agents alike; :seon.warn/ns is ignored."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db]}]
  (let [marked (marked-entity-id-attrs)]
    {:seon.warn/kind :unmarked-entity-kinds
     :seon.warn/affected
     (->> (identity-attrs db)
          (remove marked)
          (filter #(pos? (attr-instance-count db %)))
          (sort-by str)
          (mapv (fn [attr]
                  (let [carriers (unmarked-map-schemas-carrying attr)]
                    (cond-> {:seon.warn/sym (str attr)}
                      (seq carriers)
                      (assoc :seon.warn/where
                             (str "carried unmarked by "
                                  (str/join ", " (map str carriers)))))))))
     :seon.warn/explain
     (str "Rows are STORED under an identity attr but no registered :map "
          "schema marked {:seon.db/entity true} declares that kind — its "
          "entities are invisible to the catalog and the renderer. "
          "Register (or re-register) the kind's :map schema WITH the "
          "marker. Request/response envelopes stay unmarked; this fires "
          "only where rows actually exist.")
     :seon.warn/example
     (str "(seon.schema/register! :kb.doc\n"
          "  [:map {:seon.db/entity true}\n"
          "   [:kb.doc/path :kb.doc/path]  ; the identity attr\n"
          "   …])")}))

(defn- test-index
  "[set-of-test-syms, concatenated-test-source] for the ns scope —
   the two ways a fn counts as tested (a `<sym>-test` deftest, or any
   in-scope test whose source calls the fn)."
  [db ns-kw]
  (let [rows (db/query
               {:seon.db/db db
                :seon.db/query
                '[:find ?sym ?src
                  :where
                  [?t :seon.test/sym ?sym]
                  [(get-else $ ?t :seon.test/source "") ?src]]})
        in-scope (if ns-kw
                   (filter (fn [[sym _]]
                             (= (name ns-kw) (namespace (symbol sym))))
                           rows)
                   rows)]
    [(set (map first in-scope))
     (str/join "\n" (map second in-scope))]))

(defn check-missing-test
  "Public fns with no associated `:seon.test` — neither a `<sym>-test`
   deftest nor any in-scope test whose source mentions the fn."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db] ns-kw :seon.warn/ns :as req}]
  (let [[test-syms test-src] (test-index db ns-kw)]
    (corpus-check
      req :missing-test
      (str "Fn has no test. Write a deftest named <fn>-test in the same "
           "ns exercising at least the happy path — tested fns are the "
           "ones other agents can safely reuse.")
      (str "(deftest greet-test\n"
           "  (is (= \"hi x\" (greet \"x\"))))")
      (fn [{:keys [sym]}]
        (let [simple (name (symbol sym))]
          (when-not (or (contains? test-syms (str sym "-test"))
                        (str/includes? test-src simple))
            [{:seon.warn/sym sym}]))))))

;; ============================================================
;; Runtime checks — current problems in the eval/test log. GLOBAL
;; (cross-agent visibility is the point); :seon.warn/ns is ignored.
;; ============================================================

(def slow-eval-threshold-ms 500)

(def hop-cap
  "Max `:seon.agent.message/hops` before the wake trigger refuses a message
   (agent↔agent ping-pong guard). Lives here (not seon.agent) so both
   the trigger (seon.agent) and `check-hop-exhausted` read ONE value
   without a require cycle. hops = 0 when from = the user; each
   agent-originated send carries waking-message-hops + 1."
  4)

(defn- latest-user-at
  "Wall-clock of the latest message FROM the user anywhere, or nil.
   Identity is the ref: a user message is one whose
   `:seon.agent.message/from` resolves to a `:seon.user/id` entity."
  [db]
  (ffirst (db/query
            {:seon.db/db db
             :seon.db/query
             '[:find (max ?at)
               :where
               [?m :seon.agent.message/from ?u]
               [?u :seon.user/id _]
               [?m :seon.agent.message/at ?at]]})))

(defn- failed-eval-rows
  "[eval-id error-string] rows for failed evals since the latest user
   message (every failed eval when no user message exists yet)."
  [db]
  (if-let [cutoff (latest-user-at db)]
    (db/query
      {:seon.db/db db
       :seon.db/query
       '[:find ?eid ?err
         :in $ ?cutoff
         :where
         [?e :seon.eval/ok? false]
         [?e :seon.eval/at ?e-at]
         [(> ?e-at ?cutoff)]
         [?e :seon.eval/id ?eid]
         [(get-else $ ?e :seon.eval/error "") ?err]]
       :seon.db/args [cutoff]})
    (db/query
      {:seon.db/db db
       :seon.db/query
       '[:find ?eid ?err
         :where
         [?e :seon.eval/ok? false]
         [?e :seon.eval/id ?eid]
         [(get-else $ ?e :seon.eval/error "") ?err]]})))

(defn- clip [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

(def ^:private bad-ref-marker
  "The cryptic datahike message a lookup-ref against a non-identity
   attr produces — translated by check-bad-ref."
  "Lookup ref attribute should be marked as :db/unique")

(defn check-failed-evals
  "Failed evals since the latest user message — anywhere in the system
   (cross-agent). Excludes bad-ref failures (check-bad-ref owns those).
   Vanishes when the next user msg lands and subsequent evals succeed."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db]}]
  {:seon.warn/kind :failed-evals
   :seon.warn/affected
   (->> (failed-eval-rows db)
        (remove (fn [[_ err]] (str/includes? err bad-ref-marker)))
        (sort-by first)
        (mapv (fn [[eid err]]
                (cond-> {:seon.warn/sym (str eid)}
                  (not (str/blank? err))
                  (assoc :seon.warn/where (clip err 120))))))
   :seon.warn/explain
   (str "Evals FAILED since the latest user message (cross-agent). "
        "Errors are values — (result <eval-id>) holds the full error "
        "data; inspect it and adapt instead of retrying blind.")
   :seon.warn/example "(result :<eval-id>)"})

(defn check-bad-ref
  "Failed evals whose error is datahike's cryptic lookup-ref message —
   translated into the real fix: the target attr needs
   {:seon.db/identity true}, or the referenced entity doesn't exist."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db]}]
  {:seon.warn/kind :bad-ref
   :seon.warn/affected
   (->> (failed-eval-rows db)
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
   (str ";; reference by eid instead of a lookup-ref on a non-identity attr:\n"
        "(def eid (ffirst (seon.db/query {:seon.db/query\n"
        "                                 '[:find ?e :where [?e :kb.doc/path \"a.md\"]]})))\n"
        "{:kb.note/doc eid}  ; NOT {:kb.note/doc [:kb.doc/path \"a.md\"]}")})

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
   SUCCEEDS), so this scans `:seon.eval/result-edn`, not
   `:seon.eval/error`. Marker filtering happens in Clojure, not in a
   :where predicate (datahike-cljs string predicates in :where are a
   known trap — see the gym S-12 note in seon.agent)."
  [db]
  (let [cutoff (latest-user-at db)
        rows   (db/query
                 {:seon.db/db db
                  :seon.db/query
                  '[:find ?eid ?edn ?at
                    :where
                    [?e :seon.eval/result-edn ?edn]
                    [?e :seon.eval/at ?at]
                    [?e :seon.eval/id ?eid]]})]
    (->> rows
         (filter (fn [[_ edn at]]
                   (and (or (nil? cutoff)
                            (> (.getTime ^js at) (.getTime ^js cutoff)))
                        (str/includes? edn fs-error-key-marker)
                        (str/includes? edn fs-denial-marker))))
         (map (fn [[eid edn _]] [eid (fs-denial-text edn)])))))

(defn check-fs-denied
  "fs calls DENIED by the capability allowlist since the latest user
   message — the grant-mismatch shape observed live 2026-06-11: an
   agent INFERRED its grant from a CWD listing (wrongly — the granted
   root was an ancestor) instead of reading the configured truth via
   `(seon.agent.fs/grants)`. DERIVED from the eval log at render time;
   self-heals when a new user message lands and subsequent fs calls
   stay in scope. GLOBAL — :seon.warn/ns is ignored."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db]}]
  {:seon.warn/kind :fs-denied
   :seon.warn/affected
   (->> (fs-denied-eval-rows db)
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
        ";; => {:seon.agent.fs/allowed-roots [\"/Users/me/work\"]\n"
        ";;     :seon.agent.fs/read-only?    false}")})

(defn check-hop-exhausted
  "Messages whose `:seon.agent.message/hops` reached [[hop-cap]] SINCE the
   latest user message — each one is a wake the trigger REFUSED (an
   agent↔agent reply chain hit the ping-pong guard and was dropped on
   the floor). A fresh human message resets the chain (hops 0) and
   scopes these out of the surface."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db]}]
  (let [cutoff (latest-user-at db)
        rows   (db/query
                 {:seon.db/db db
                  :seon.db/query
                  '[:find ?mid ?hops ?at
                    :in $ ?cap
                    :where
                    [?m :seon.agent.message/hops ?hops]
                    [(>= ?hops ?cap)]
                    [?m :seon.agent.message/id ?mid]
                    [?m :seon.agent.message/at ?at]]
                  :seon.db/args [hop-cap]})]
    {:seon.warn/kind :hop-exhausted
     :seon.warn/affected
     (->> rows
          (filter (fn [[_ _ at]]
                    (or (nil? cutoff) (> (.getTime ^js at)
                                         (.getTime ^js cutoff)))))
          (sort-by first)
          (mapv (fn [[mid hops _]]
                  {:seon.warn/sym   (str mid)
                   :seon.warn/where (str "hops " hops "/" hop-cap
                                         " — wake refused")})))
     :seon.warn/explain
     (str "An agent↔agent reply chain hit the hop cap (" hop-cap "): the "
          "wake trigger REFUSED these messages, so their recipients never "
          "ran. Two agents must not auto-bill an infinite conversation — "
          "stop replying to replies; involve the human (message the user) "
          "to continue the thread, which resets hops to 0.")
     :seon.warn/example
     "(seon.agent/message! {:seon.agent.message/content \"summary for you — …\"})  ; to defaults to the user"}))

(defn check-slow-evals
  "Evals over the slow threshold in the last hour, anywhere. Stops
   surfacing when new evals are fast and the offenders age out."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db]}]
  (let [cutoff (js/Date. (- (js/Date.now) (* 60 60 1000)))]
    {:seon.warn/kind :slow-evals
     :seon.warn/affected
     (->> (db/query
            {:seon.db/db db
             :seon.db/query
             '[:find ?eid ?dur
               :in $ ?threshold ?cutoff
               :where
               [?e :seon.eval/duration-ms ?dur]
               [(>= ?dur ?threshold)]
               [?e :seon.eval/at ?at]
               [(> ?at ?cutoff)]
               [?e :seon.eval/id ?eid]]
             :seon.db/args [slow-eval-threshold-ms cutoff]})
          (sort-by first)
          (mapv (fn [[eid dur]]
                  {:seon.warn/sym   (str eid)
                   :seon.warn/where (str dur "ms")})))
     :seon.warn/explain
     (str "Evals took ≥" slow-eval-threshold-ms "ms in the last hour. "
          "Narrow the query (specific attrs, not [*]) or compute less "
          "per form.")
     :seon.warn/example
     ";; pull named attrs, not '[*]; add :where clauses to narrow"}))

(defn check-failing-tests
  "Tests whose last run failed (last-failed-at > last-passed-at)."
  {:malli/schema [:=> [:cat ::check-request] ::check-response]}
  [{:seon.db/keys [db]}]
  {:seon.warn/kind :failing-tests
   :seon.warn/affected
   (->> (db/query
          {:seon.db/db db
           :seon.db/query
           '[:find ?sym
             :where
             [?t :seon.test/sym ?sym]
             [?t :seon.test/last-failed-at ?f-at]
             (or-join [?t ?f-at]
                      (and (not [?t :seon.test/last-passed-at _])
                           [(identity ?f-at) _])
                      (and [?t :seon.test/last-passed-at ?p-at]
                           [(> ?f-at ?p-at)]))]})
        (map first)
        sort
        (mapv (fn [sym] {:seon.warn/sym (str sym)})))
   :seon.warn/explain
   (str "Tests are FAILING (their last run failed). Re-run after fixing: "
        "the warning clears itself when the test passes.")
   :seon.warn/example
   "(seon.test.runner/run-vars {:seon.test.runner/vars ['my.ns/my-test]})"})

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
   check-missing-test
   check-parallel-attr
   check-unmarked-entity-kinds
   check-bad-ref
   check-failed-evals
   check-fs-denied
   check-hop-exhausted
   check-slow-evals
   check-failing-tests])

(defn run-checks
  "Run every registered check against the request; return only the
   non-clean responses (those with at least one affected entry)."
  {:malli/schema [:=> [:cat ::check-request] [:vector ::check-response]]}
  [req]
  (->> checks
       (map (fn [check] (check req)))
       (filterv (comp seq :seon.warn/affected))))

(defn- render-affected-entry
  [{:seon.warn/keys [sym where]}]
  (if where (str sym " (" where ")") sym))

(defn- render-cluster
  "ONE cluster: [kind] explanation, ONE fix example, then the affected
   list with specific locations. The explanation appears once per kind,
   never once per fn."
  [{:seon.warn/keys [kind affected explain example]}]
  (str "[" (name kind) "] " explain "\n"
       "  Fix example:\n"
       (str/join "\n" (map #(str "    " %) (str/split-lines example)))
       "\n"
       "  Affecting: "
       (str/join ", " (map render-affected-entry affected))
       " (" (count affected) "). Please correct before moving on."))

(defn render-warnings
  "Run the registry and render the non-clean checks as a single
   `<warnings>` block, one cluster per kind. Empty string when clean.
   Scope the corpus checks with `:seon.warn/ns`; omit it for the
   whole-substrate overview."
  {:malli/schema [:=> [:cat ::check-request] :string]}
  [req]
  (let [clusters (run-checks req)]
    (if (seq clusters)
      (str "<warnings>\n"
           (str/join "\n\n" (map render-cluster clusters))
           "\n</warnings>")
      "")))
