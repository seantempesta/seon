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
   DOMAIN-attr check (parallel-attr — keyword namespaces are data
   domains, not code nses) stay global — cross-agent visibility is
   their point.

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

(defn- internal-attr-ns?
  "True for substrate/datahike-internal keyword namespaces (`seon`,
   `seon.*`, `db`, `db.*`) — never agent-forkable data domains."
  [ns-str]
  (boolean (re-matches #"(db|seon)(\..*)?" ns-str)))

(defn domain-attrs
  "Every DOMAIN attr installed on `db` — the db's datahike schema attrs
   minus substrate/datahike internals. These are the attrs agents
   registered for the human's data: the reuse surface the
   schema-catalog renders and [[check-parallel-attr]] guards. Derived
   from the db value itself (NOT the live registry), so it survives pod
   restarts and stays per-conn. An attr appears once data (or schema
   installation via the first transact!) has landed."
  {:malli/schema [:=> [:cat ::check-request] [:vector :keyword]]}
  [{:seon.db/keys [db]}]
  (->> (keys (:schema db))
       (filter keyword?)
       (filter namespace)
       (remove #(internal-attr-ns? (namespace %)))
       distinct
       (sort-by str)
       vec))

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

(defn- latest-user-at
  "Wall-clock of the latest :user message anywhere, or nil."
  [db]
  (ffirst (db/query
            {:seon.db/db db
             :seon.db/query
             '[:find (max ?at)
               :where
               [?u :seon.message/role :user]
               [?u :seon.message/at ?at]]})))

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
        "Either register the attr as an identity, or — if the entity "
        "doesn't exist yet — transact it first (or in the same tx).")
   :seon.warn/example
   "(seon.schema/register! :kb.doc/path [:string {:seon.db/identity true}])"})

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
   check-bad-ref
   check-failed-evals
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
