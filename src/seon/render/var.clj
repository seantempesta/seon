(ns seon.render.var
  "The Var render: what a definition's reply says about it, and nothing when
  nothing changed.

  A `defn` returns its Var. The Var's declared render pair (`:seon.render.var/var`)
  shows its contract when it is missing, and its reaching tests
  at the function's current definition: a result recorded at the test's
  current reach digest is read; none is pending; a test whose result at this
  digest differs from its result before (newly red, newly green) is named.
  Both faces read one value, `status`; the AI face is REPL entries that
  evaluate it, the HTML face renders it. Test results are a derived value
  keyed by the exercised digests, so a read of them is ordinary read evidence
  and a later recording refreshes the next context."
  (:require [clojure.string]
            [clojure.test.check.generators :as gen]
            [seon.db :as db]
            [seon.fn :as sfn]
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

(defn- before
  "Each test's latest recorded outcome at a reach digest other than `now`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:map-of :qualified-symbol :string]]
                  [:map-of :qualified-symbol [:enum :pass :fail]]]}
  [database now]
  (into {}
        (for [[t digest] now
              :let [earlier (->> (db/q '[:find ?tx ?d ?f ?e :in $ ?t
                                         :where [?m :seon.test.member/symbol ?t]
                                                [?m :seon.test.member/completed-tx ?tx]
                                                [?m :seon.test.member/reach-digest ?d]
                                                [?m :seon.test.member/fail-count ?f]
                                                [?m :seon.test.member/error-count ?e]]
                                       database t)
                                 (filter (fn [[_ d]] (not= d digest)))
                                 (sort-by first) last)]
              :when earlier]
          [t (outcome {:seon.test.member/fail-count (nth earlier 2) :seon.test.member/error-count (nth earlier 3)})])))

(defn status
  "What the definition `sym` has to say, empty when nothing is worth saying.

  Its contract when missing, and its reaching tests at their
  current reach digests: pending when no result is recorded there, newly red
  or green against the result before."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol] :seon.render.var/status]}
  [database sym]
  (let [entity (db/pull database [:db/id :seon.fn/spec :seon.fn/macro? :seon.fn/constant?] [:seon.fn/sym sym])
        ;; a refused read or no entity says nothing about the contract
        contract (when (and (map? entity) (not (db/read-refusal? entity)) (:db/id entity)
                            (not (or (:seon.fn/spec entity) (:seon.fn/macro? entity) (:seon.fn/constant? entity))))
                   :missing)
        tests (let [t (sfn/gate-sets {:seon.db/db database :seon.fn/seeds #{sym}})] (if (vector? t) t []))
        digests (if (seq tests) (runner/reach-digests database tests) {})
        digests (if (map? digests) (select-keys digests tests) {})
        latest (if (seq tests)
                 (let [r (runner/latest-results database tests)]
                   ;; a refused read is every test's pending, never a result
                   (if (map? r) {} (into {} (map (juxt :seon.test/sym identity)) r)))
                 {})
        now (into {} (for [t tests
                           :let [r (get latest t)]]
                       [t (if (and r (some? (get digests t))
                                   (= (get digests t) (:seon.test.member/reach-digest r)))
                            (outcome r) :pending)]))
        was (before database (select-keys digests (keys (filter (comp #{:pass :fail} val) now))))
        newly (fn [from to] (vec (sort (for [[t o] now :when (and (= to o) (= from (get was t)))] t))))
        pending (vec (sort (for [[t o] now :when (= :pending o)] t)))]
    (cond-> {}
      contract (assoc :seon.render.var/contract contract
                      :seon.render.var/example (str "(defn " (name sym) " {:malli/schema [:=> [:cat :int] :int]} [x] x)"))
      (seq pending) (assoc :seon.render.var/pending pending)
      (seq (newly :pass :fail)) (assoc :seon.render.var/newly-red (newly :pass :fail))
      (seq (newly :fail :pass)) (assoc :seon.render.var/newly-green (newly :fail :pass)))))

(defn render-ai
  "The Var as REPL entries, data forms: the Var itself, then its status when it says something."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/entries]}
  [unit]
  (let [sym (var-symbol (:seon.render/value unit))
        said (when (and sym (:seon.db/db unit)) (status (:seon.db/db unit) sym))]
    (cond-> [(list 'var sym)]
      (seq said) (conj (list 'seon.render.var/status (list 'quote sym))))))

(defn render-html
  "The Var and, when it says something, its status: the AI face's value as hiccup."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [sym (var-symbol (:seon.render/value unit))
        {:seon.render.var/keys [contract example pending newly-red newly-green]}
        (when (and sym (:seon.db/db unit)) (status (:seon.db/db unit) sym))
        tests (fn [label ts] (when (seq ts) [:li label ": " [:code (clojure.string/join " " ts)]]))]
    [:div {:class "seon-var"}
     [:code (str "#'" sym)]
     (when (or contract (seq pending) (seq newly-red) (seq newly-green))
       [:ul
        (when contract [:li (str "contract " (name contract) ": ") [:code example]])
        (tests "newly red" newly-red)
        (tests "newly green" newly-green)
        (tests "pending" pending)])]))
