(ns seon.contracts
  "Contract back-fill: an uncontracted function becomes an assignable task whose
  proof asks whether the function now carries a complete contract."
  (:require [seon.db :as db]
            [seon.fn :as fn]))

(def guide
  "The guide every contract task references: what a good contract is in Seon."
  "docs/seon/architecture/contracts-guide.md")

(defn gaps
  "What keeps `function-symbol` from a complete contract in `database`.

  Its `seon.fn/contract-findings` rows, or one absent row when nothing defines
  it; empty when the contract is complete; a refused read as it came."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:or [:vector [:or :seon.fn.contract/finding-row
                                 [:map [:seon.fn/sym :seon.fn/sym] [:seon.contracts/absent [:= true]]]]]
                   :seon.error/value :seon.db/invalid-read-error :seon.schema/validation-refusal]]}
  [database function-symbol]
  (let [defined (db/q '[:find ?e . :in $ ?s :where [?e :seon.fn/sym ?s]] database function-symbol)]
    (cond
      (db/read-refusal? defined) defined
      (nil? defined) [{:seon.fn/sym function-symbol :seon.contracts/absent true}]
      :else (fn/contract-findings database {:seon.fn.contract/selected #{function-symbol}}))))

(defn- intent
  "The task's intent: what must become true, the guide by path and the function by
  its backticked symbol, which the resolver pulls into context; nothing copied."
  {:malli/schema [:=> [:cat :seon.fn/sym :boolean] :seon.task/intent]}
  [function-symbol host-bound?]
  (str "Give `" function-symbol "` a complete Malli contract that checks what its boundary is for, "
       "with the cheapest schema that checks it (`" guide "`): registered names, every input and output, "
       "the error variants it returns. "
       (when host-bound?
         "It is host-bound (SCI cannot interpret it): a Claude lane changes it and commits through the check. ")
       "A contract that refuses a real value (a reaching test, a live call, your probe) is a finding, not friction: "
       "if the contract misread the value's shape, fix the contract and cite the observed shape; if the producer "
       "emits a bad value, keep the contract, file a task on the producer naming `" function-symbol
       "` in its intent with the exact form, the refused value's essence and the Malli explain, and say so "
       "in your reply. Never widen to :any, [:maybe ...] or :map to go green. "
       "A caller that passes nil to an input is the producer of the bad value: change the caller so it does not pass nil (absence is no key), or take a map with the key optional; never contract nil with [:maybe ...] or :nil. "
       "Done when `seon.contracts/gaps` "
       "answers [] for it and its reaching tests stay green and no slower."))

(defn task
  "The task declaration asking an agent to contract `function-symbol`.

  Named `contract-<name>` in the function's namespace, or the refused read. Create it
  with `seon.task/create!` or add it to that namespace's task file; its proof is
  the `seon.contract-coverage-test` row tagged with it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:or :seon.task/declaration :seon.db/invalid-read-error]]}
  [database function-symbol]
  (let [host-bound? (db/q '[:find ?h . :in $ ?s
                            :where [?e :seon.fn/sym ?s] [(get-else $ ?e :seon.fn/host-bound? false) ?h]]
                          database function-symbol)]
    (if (db/read-refusal? host-bound?)
      host-bound?
      {:seon.task/ns (symbol (namespace function-symbol))
       :seon.task/name (str "contract-" (name function-symbol))
       :seon.task/intent (intent function-symbol (true? host-bound?))})))

(def ^:private candidate-query
  ;; Source functions with no contract: test roots, macros and value defs
  ;; (`seon.fn/value-definition?`, constants included) are not functions to contract.
  '[:find ?s ?host
    :where [?e :seon.fn/sym ?s]
    [(missing? $ ?e :seon.fn/spec)]
    [?e :seon.fn/file ?f] [?f :seon.fn.file/relative-root ?root] [(not= ?root "test")]
    [(get-else $ ?e :seon.fn/macro? false) ?macro] [(false? ?macro)]
    [?e :seon.fn/arglists ?arglists] [(seon.fn/value-definition? ?arglists) ?value] [(false? ?value)]
    [(get-else $ ?e :seon.fn/host-bound? false) ?host]])

(defn- partitioned
  "The candidate `rows` partitioned by their `uses` counts and `named` set."
  {:malli/schema [:=> [:cat [:set [:tuple :seon.fn/sym :boolean]] [:map-of :seon.fn/sym :int]
                       [:set :seon.fn/sym]]
                  [:map [:seon.contracts/eligible [:vector :seon.fn/sym]]
                   [:seon.contracts/host-bound [:vector :seon.fn/sym]]
                   [:seon.contracts/unused [:vector :seon.fn/sym]]
                   [:seon.contracts/named-by-task [:vector :seon.fn/sym]]]]}
  [rows uses named]
  (let [host (into #{} (keep (fn [[s h]] (when h s))) rows)
        {unused :unused named-by-task :named host-bound :host eligible :eligible}
        (group-by #(cond (named %) :named (not (uses %)) :unused (host %) :host :else :eligible)
                  (map first rows))
        ranked (fn [xs] (vec (sort-by (juxt #(- (uses % 0)) str) xs)))]
    {:seon.contracts/eligible (ranked eligible)
     :seon.contracts/host-bound (ranked host-bound)
     :seon.contracts/unused (vec (sort unused))
     :seon.contracts/named-by-task (ranked named-by-task)}))

(defn report
  "The uncontracted source functions of `database`, partitioned.

  `eligible`
  (used, ranked by calling functions and tests plus referencing declarations),
  `host-bound` (for Claude lanes), `unused` (no call or reference edge: deletion
  candidates) and `named-by-task` (an open task already names them; skipped).
  A refused read is returned as it came."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:map [:seon.contracts/eligible [:vector :seon.fn/sym]]
                        [:seon.contracts/host-bound [:vector :seon.fn/sym]]
                        [:seon.contracts/unused [:vector :seon.fn/sym]]
                        [:seon.contracts/named-by-task [:vector :seon.fn/sym]]]
                   :seon.db/invalid-read-error]]}
  [database]
  (let [rows (db/q candidate-query database)
        symbols (when-not (db/read-refusal? rows) (mapv first rows))
        uses (when symbols
               (db/q '[:find ?s (count ?c) :in $ [?s ...]
                       :where (or [?c :seon.fn/calls ?s] [?c :seon.fn/references ?s])]
                     database symbols))
        named (when symbols
                (db/q '[:find [?s ...] :in $ [?s ...] :where [_ :seon.task/references ?s]] database symbols))]
    (or (some #(when (db/read-refusal? %) %) [rows uses named])
        (partitioned rows (into {} uses) (set named)))))

(defn findings
  "The bug yield of contract tasks, by function.

  For each of `function-symbols`, the other
  tasks whose intent cites it (the producer tasks its contract work
  filed), or the refused read."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :seon.fn/sym]]
                  [:or [:map-of :seon.fn/sym [:set :qualified-symbol]] :seon.db/invalid-read-error]]}
  [database function-symbols]
  (let [named (db/q '[:find ?s ?t :in $ [?s ...] :where [?task :seon.task/references ?s] [?task :seon.task/sym ?t]]
                    database function-symbols)]
    (if (db/read-refusal? named)
      named
      (reduce (fn [found [function-symbol task-symbol]]
                (if (= (name task-symbol) (str "contract-" (name function-symbol)))
                  found
                  (update found function-symbol conj task-symbol)))
              (zipmap function-symbols (repeat #{}))
              named))))
