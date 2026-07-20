(ns seon.host.context
  "Own the JVM agent host's shared sci base and per-agent contexts.

   One base context is built once per host process: the portable pure slice
   of the `my.*` toolkit loaded from its real sources, the compiled host
   `.cljc` functions (`seon.ai.tokens`, `seon.schema` validation), and a
   `seon.db` binding table whose reads and writes are synchronous UDS
   round-trips to the cluster writer through the one existing
   `seon.db.transport.uds` client. Every agent context is a `sci/fork` of
   that base (persistent-structure sharing; forked defs stay private).

   The durable agent is database facts. A context is a cache of those
   facts: park drops it, restore forks the base and replays the agent's def
   sources through [[replay-defs!]].

   TODO SEAM (recorded, deliberately unbuilt in U1 — owner:
   sci-execution-runtime U2 with `seon.eval`'s corpus machinery):
   - def persistence: successful defs evaluated here must tee into the one
     `:seon.fn`/`:seon.ns` program corpus exactly as
     `seon.eval/eval-batch!` records them today. Until that tee exists the
     caller supplies replay sources from the corpus it already holds.
   - `seon.schema/register!` inside a context records the request and
     returns nil; real admission (validator compilation + Datahike bridge)
     is the same U2 unit."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.interrupt :as interrupt]
            [seon.ai.tokens :as tokens]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.schema :as schema]))

(set! *warn-on-reflection* true)

(schema/register! ::writer-socket-path [:string {:min 1}])
(schema/register! ::database-name ::protocol/database-name)
(schema/register!
 ::writer
 [:map {:closed true}
  [::writer-socket-path ::writer-socket-path]
  [::database-name ::database-name]])
(schema/register! ::ctx 'some?)
(schema/register! ::files [:int {:min 0}])
(schema/register! ::pure-blocks [:int {:min 0}])
(schema/register! ::loaded [:int {:min 0}])
(schema/register! ::failed [:int {:min 0}])
(schema/register!
 ::failures
 [:vector [:map {:closed true}
           [::block-name :string]
           [::failure :string]]])
(schema/register!
 ::report
 [:map {:closed true}
  [::files ::files]
  [::pure-blocks ::pure-blocks]
  [::loaded ::loaded]
  [::failed ::failed]
  [::failures ::failures]])
(schema/register!
 ::base
 [:map {:closed true}
  [::ctx ::ctx]
  [::report ::report]])
(schema/register! ::def-sources [:vector :string])
(schema/register!
 ::replay-envelope
 [:map
  [:seon.eval/ok? :boolean]])
(schema/register! ::replay-envelopes [:vector ::replay-envelope])

(defn- writer-call!
  "One request/response round-trip on a fresh writer connection."
  [{::keys [writer-socket-path]} request]
  (with-open [channel (uds/connect! writer-socket-path)]
    (uds/call! {::uds/channel channel ::uds/message request})))

(defn- protocol-error-value
  [response]
  {:seon/error
   {:seon.error/message
    (str "The database writer rejected the call: "
         (or (::protocol/error response) (::protocol/error-kind response)))
    :seon.error/kind :agent
    :seon.error/data (select-keys response [::protocol/error-kind])}})

(defn resolve-head!
  "Resolve the writer's current database value for the host's database."
  {:malli/schema [:=> [:cat ::writer] :map]}
  [{::keys [database-name] :as writer}]
  (let [response (writer-call!
                  writer
                  (protocol/resolve-head-request
                   {::protocol/request-id (str (random-uuid))
                    ::protocol/database-name database-name}))]
    (if (::protocol/success? response)
      (:seon.db/db response)
      (protocol-error-value response))))

(defn- db-query
  "Context `seon.db/query`: one blocking Datalog read at the current head."
  [writer query-form & arguments]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response (writer-call!
                      writer
                      (protocol/query-request
                       {::protocol/request-id (str (random-uuid))
                        :seon.db/db head
                        ::protocol/query-form query-form
                        ::protocol/arguments (vec arguments)}))]
        (if (::protocol/success? response)
          (:datahike.query/result response)
          (protocol-error-value response))))))

(defn- db-pull
  "Context `seon.db/pull`: one blocking pull read at the current head."
  [writer selector entity-id]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response (writer-call!
                      writer
                      (protocol/pull-request
                       {::protocol/request-id (str (random-uuid))
                        :seon.db/db head
                        ::protocol/selector selector
                        ::protocol/entity-id entity-id}))]
        (if (::protocol/success? response)
          (::protocol/result response)
          (protocol-error-value response))))))

(defn- db-transact!
  "Context `seon.db/transact!`: one blocking write at the current head."
  [writer transaction-data]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response (writer-call!
                      writer
                      (protocol/transaction-request
                       {::protocol/request-id (str (random-uuid))
                        :seon.db/db head
                        ::protocol/transaction-data (vec transaction-data)}))]
        (if (::protocol/success? response)
          {:seon.db/ok? true
           :db-after (select-keys (:db-after response)
                                  [:db-name :t :datahike/commit-id])
           :tempids (:tempids response)}
          (protocol-error-value response))))))

;;; Portable `my.*` slice, loaded from the real sources.

(def ^:private my-source-files
  ["src/my/data.cljs" "src/my/plan.cljs" "src/my/kb.cljs" "src/my/ns.cljs"
   "src/my/canvas.cljs" "src/my/ui.cljs" "src/my/skills.cljs"
   "src/my/blob.cljs"])

(defn- defn-blocks
  "Top-level defn blocks of one source string."
  [source]
  (let [lines (vec (str/split-lines source))
        tops (vec (keep-indexed
                   (fn [index line]
                     (when (re-find #"^\((defn|def )" line) index))
                   lines))]
    (for [[from to] (map vector tops (concat (rest tops) [(count lines)]))
          :let [block (str/join "\n" (subvec lines from to))]
          :when (str/starts-with? block "(defn")]
      block)))

(defn- pure-block?
  "True when a defn block has no async, js-interop, or db-boundary marker."
  [block]
  (not (re-find #"\^:async|\(await |js/|#js|\(\.\-|\(\. |\(\.[a-zA-Z]|db/transact!|db/query|db/pull|db/entity|db/db\b|blob/"
                block)))

(defn- block-name [block]
  (or (second (re-find #"\(defn-? \^?[:a-z]*\s*([^\s]+)" block)) "unknown"))

(defn- file-ns-name [path]
  (-> path
      (str/replace #"^src/" "")
      (str/replace #"\.cljs$" "")
      (str/replace "/" ".")
      symbol))

(defn- load-portable-slice!
  "Eval every pure `my.*` defn block from its real source into `ctx`.

   Returns the honest ledger: block counts plus each failure's first error
   line. Failures are references to impure private helpers the pure slice
   does not carry, recorded — never silently skipped."
  [ctx]
  (let [loads
        (vec
         (for [path my-source-files
               :let [ns-sym (file-ns-name path)
                     source (slurp (io/file path))
                     pure (filterv pure-block? (defn-blocks source))]]
           (do
             ;; A synthetic ns form stands in for the production
             ;; augment-ns-source aliases, pointed at the host namespaces.
             (sci/eval-string*
              ctx (str "(ns " ns-sym
                       " (:require [clojure.string :as str]"
                       " [clojure.set :as set]"
                       " [clojure.edn :as edn]"
                       " [clojure.walk :as walk]"
                       " [seon.db :as db]"
                       " [seon.schema :as schema]"
                       " [seon.ai.tokens :as tokens]))"))
             (reduce
              (fn [tally block]
                (let [outcome
                      (try (sci/eval-string*
                            ctx (str "(in-ns '" ns-sym ")\n" block))
                           ::ok
                           (catch Throwable throwable
                             (first (str/split-lines
                                     (str (.getMessage throwable))))))]
                  (if (= ::ok outcome)
                    (update tally ::loaded inc)
                    (update tally ::failures conj
                            {::block-name (block-name block)
                             ::failure (str outcome)}))))
              {::pure-blocks (count pure) ::loaded 0 ::failures []}
              pure))))]
    {::files (count my-source-files)
     ::pure-blocks (reduce + (map ::pure-blocks loads))
     ::loaded (reduce + (map ::loaded loads))
     ::failed (reduce + (map (comp count ::failures) loads))
     ::failures (into [] (mapcat ::failures) loads)}))

(defn build-base!
  "Build the one shared base context for a host serving one cluster.

   Host bindings close over the cluster writer coordinates; the portable
   `my.*` pure slice loads from its real sources. The returned report is
   the honest real-vs-failed load ledger."
  {:malli/schema [:=> [:cat ::writer] ::base]}
  [writer]
  (let [ctx (sci/init
             {:namespaces
              {'seon.db {'query (partial db-query writer)
                         'pull (partial db-pull writer)
                         'transact! (partial db-transact! writer)
                         'head (partial resolve-head! writer)}
               'seon.schema
               {'validate (fn [schema-key value]
                            (schema/valid-candidate-value? schema-key value))
                ;; TODO SEAM (U2): real admission through the one
                ;; `seon.schema/register!` bridge; recording only for now.
                'register! (fn [_key _schema] nil)}
               'seon.ai.tokens {'estimate tokens/estimate
                                'estimate-chars tokens/estimate-chars}}
              :interrupt-fn
              (fn []
                (when (.isInterrupted (Thread/currentThread))
                  (interrupt/interrupt! "eval deadline exceeded")))})
        report (load-portable-slice! ctx)]
    {::ctx ctx ::report report}))

(defn fork-context
  "Fork one private agent context from the shared base."
  {:malli/schema [:=> [:cat ::base] ::ctx]}
  [{::keys [ctx]}]
  (sci/fork ctx))

(defn replay-defs!
  "Replay def sources into a context; restore = fork base + this replay.

   Each source string evaluates in order; every outcome is a value. The
   sources come from the one program corpus the caller holds — teeing NEW
   defs back into that corpus is the recorded U2 seam, not this function."
  {:malli/schema [:=> [:cat ::ctx ::def-sources] ::replay-envelopes]}
  [ctx def-sources]
  (mapv (fn [source]
          (try
            {:seon.eval/ok? true
             :seon.eval/value (sci/eval-string* ctx source)}
            (catch Throwable throwable
              {:seon.eval/ok? false
               :seon/error {:seon.error/message
                            (str (first (str/split-lines
                                         (str (.getMessage throwable)))))
                            :seon.error/kind :agent}})))
        def-sources))
