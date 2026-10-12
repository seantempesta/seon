(ns seon.render.var
  "The Var render: what a definition's reply says about it, and nothing when
  nothing changed.

  A `defn` returns its Var. The Var's declared render pair (`:seon.render.var/var`)
  shows its contract when it is missing, and its reaching tests at the
  function's current definition: a result recorded against a program holding
  it is read; none is pending; a test whose result differs from its result
  before the definition (newly red, newly green) is named.
  Both faces read one value, `status`: the AI face returns it as data for the
  value printer, the HTML face as hiccup. Neither runs anything; the
  definition's batch schedules its reaching tests, the render only reads
  their results. A read of the results is ordinary read evidence, so a later
  recording refreshes the next context."
  (:require [clojure.string]
            [clojure.test.check.generators :as gen]
            [seon.db :as db]
            [seon.fn :as sfn]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.test.runner :as runner]))

(defn var-value?
  "True for a host Var or an SCI Var."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason "A predicate answers for any value." :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [x]
  (or (instance? clojure.lang.Var x) (instance? sci.lang.Var x)))

(schema/register-core-predicate! 'seon.render.var/var-value? var-value?)

(def var-generator
  "Host Vars, for the Var schema's honest generator."
  (gen/return #'clojure.core/identity))

(defn var-symbol
  "The symbol a Var names, or nil when it names none."
  {:malli/schema [:=> [:cat :seon.render.var/var] [:maybe :qualified-symbol]]}
  [v]
  (let [{n :ns s :name} (meta v)]
    (when (and n s) (symbol (str n) (str s)))))

(defn- outcome
  "A recorded result's outcome: :fail when it failed or erred, else :pass."
  {:malli/schema [:=> [:cat :map] [:enum :pass :fail]]}
  [r]
  (if (pos? (+ (:seon.test.member/fail-count r (:seon.test/fail-count r 0))
               (:seon.test.member/error-count r (:seon.test/error-count r 0))))
    :fail :pass))

(def ^:private shown-tests
  "How many test names each status list shows; the counts say the rest, and
  the check form reads them."
  5)

(defn- definition-tx
  "The transaction that wrote `sym`'s current definition digest, or nil."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol] [:maybe :int]]}
  [database sym]
  (let [tx (db/q '[:find (max ?tx) . :in $ ?s
                   :where [?f :seon.fn/sym ?s] [?f :seon.program/definition-digest _ ?tx]]
                 database sym)]
    (when (int? tx) tx)))

(defn- before
  "Each of `tests`' latest recorded outcome completed before transaction `since`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol] :int]
                  [:map-of :qualified-symbol [:enum :pass :fail]]]}
  [database tests since]
  (let [rows (if (seq tests)
               (db/q '[:find ?t ?tx ?f ?e :in $ [?t ...]
                       :where [?m :seon.test.member/symbol ?t]
                              [?m :seon.test.member/completed-tx ?tx]
                              [?m :seon.test.member/fail-count ?f]
                              [?m :seon.test.member/error-count ?e]]
                     database tests)
               [])]
    (if (db/read-refusal? rows)
      {}
      (into {}
            (for [[t group] (group-by first rows)
                  :let [[_ _ f e] (->> group (filter #(< (second %) since)) (sort-by second) last)]
                  :when f]
              [t (outcome {:seon.test.member/fail-count f :seon.test.member/error-count e})])))))

(defn- armed?
  "Whether the Var `v` carries a contract: the definition armed it, whether or not the
  database this reply reads has recorded it yet."
  {:malli/schema [:=> [:cat :seon.render.var/var] :boolean]}
  [v]
  (some? (:malli/schema (meta v))))

(defn status
  "What the definition `sym` has to say, empty when nothing is worth saying.

  Its contract when missing (neither recorded nor carried by its Var, `armed`), and its reaching tests: pending when no result
  was recorded against a program holding its current definition (the run's
  basis is before the transaction that wrote it), newly red or green against
  the latest result before that definition. Each list shows its first five
  names; `:seon.render.var/counts` says how many there are when more.

  ## Costs

  Proportional to the reaching tests: one gate-set read, the indexed latest
  results and one query over the tests with a current result; no reach
  digests are derived."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol :boolean] :seon.render.var/status]}
  [database sym armed]
  (let [entity (db/pull database [:db/id :seon.fn/spec :seon.fn/macro? :seon.fn/constant?] [:seon.fn/sym sym])
        ;; a refused read or no entity says nothing about the contract
        contract (when (and (map? entity) (not (db/read-refusal? entity)) (:db/id entity)
                            (not (or armed (:seon.fn/spec entity) (:seon.fn/macro? entity) (:seon.fn/constant? entity))))
                   :missing)
        tests (let [t (sfn/gate-sets {:seon.db/db database :seon.fn/seeds #{sym}})] (if (vector? t) t []))
        since (when (seq tests) (definition-tx database sym))
        latest (if (and (seq tests) since)
                 (let [r (runner/latest-results database tests)]
                   ;; a refused read is every test's pending, never a result
                   (if (map? r) {} (into {} (map (juxt :seon.test/sym identity)) r)))
                 {})
        now (into {} (for [t tests
                           :let [r (get latest t)
                                 basis (:seon.test.run/basis-t r (:seon.test/run-basis-t r))]]
                       [t (if (and (int? basis) (>= basis since)) (outcome r) :pending)]))
        was (if since (before database (vec (keys (filter (comp #{:pass :fail} val) now))) since) {})
        newly (fn [from to] (vec (sort (for [[t o] now :when (and (= to o) (= from (get was t)))] t))))
        lists {:seon.render.var/pending (vec (sort (for [[t o] now :when (= :pending o)] t)))
               :seon.render.var/newly-red (newly :pass :fail)
               :seon.render.var/newly-green (newly :fail :pass)}
        counts (into {} (for [[k v] lists :when (< shown-tests (count v))] [k (count v)]))]
    (cond-> (into {} (for [[k v] lists :when (seq v)] [k (vec (take shown-tests v))]))
      contract (assoc :seon.render.var/contract contract
                      :seon.render.var/example (list 'defn (symbol (name sym)) {:malli/schema [:=> [:cat :int] :int]} '[x] 'x))
      (seq counts) (assoc :seon.render.var/counts counts))))

(defn render-ai
  "The Var as data for the value printer: its symbol and, when it says
  something, its status, with the forms that act on it as data (a contract
  to start from, the check that shows a red or pending test). Nothing here is
  evaluated by the render step."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render.var/view]}
  [unit]
  (let [sym (var-symbol (:seon.render/value unit))
        said (when (and sym (:seon.db/db unit))
               (status (:seon.db/db unit) sym (armed? (:seon.render/value unit))))]
    (cond-> (assoc said :seon.render.var/symbol sym)
      (or (seq (:seon.render.var/pending said)) (seq (:seon.render.var/newly-red said)))
      (assoc :seon.render/operations [(list 'my.test/check {:seon.test/changed [sym]})]))))

(defn render-html
  "The Var and, when it says something, its status: the AI face's value as hiccup."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [sym (var-symbol (:seon.render/value unit))
        {:seon.render.var/keys [contract example pending newly-red newly-green counts]}
        (when (and sym (:seon.db/db unit))
          (status (:seon.db/db unit) sym (armed? (:seon.render/value unit))))
        tests (fn [label ts k]
                (when (seq ts)
                  [:li label (when-let [n (get counts k)] (str " (" n ")")) ": "
                   [:code (clojure.string/join " " ts)]]))]
    [:div {:class "seon-var"}
     [:code (str "#'" sym)]
     (when (or contract (seq pending) (seq newly-red) (seq newly-green))
       [:ul
        (when contract [:li (str "contract " (name contract) ": ")
                        (render.value/html {:seon.render/value example
                                            :seon.db/db (:seon.db/db unit)
                                            :seon.render.value/root [::example sym]})])
        (tests "newly red" newly-red :seon.render.var/newly-red)
        (tests "newly green" newly-green :seon.render.var/newly-green)
        (tests "pending" pending :seon.render.var/pending)])]))
