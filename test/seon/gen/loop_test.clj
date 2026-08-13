(ns seon.gen.loop-test
  "generate-code v0: the whole loop over facts, on one in-process cluster.

  The plan
  (docs/prds/sci-execution-runtime/plan/generate-code-v0-plan-2026-07-29.md)
  is implemented by mechanisms that already have their own unit
  coverage — the splitter's attribution (`seon.cluster.reply-test`), the
  routing derivation (`seon.cluster.problem-routing-test`), the
  assignment identity and the declination shape
  (`seon.cluster.message-assignment-test`). What NONE of them reach is
  the composition, and the composition is the thing this plan claims:

    a goal arrives as an ORDINARY MESSAGE → the planner's one turn is
    the whole-program attempt → its forms freeze carrying the namespace
    each was WRITTEN under → every red form becomes a problem addressed
    to that namespace's OWNER → the owner answers → settlement is a
    derivation anybody can run, and it does not care what any agent
    said about being finished.

  Nothing here is stubbed except the provider's text, which is the same
  seam `seon.cluster.turn-test` stubs and for the same reason: a suite
  that needs a paid call is a suite nobody runs. The evaluator, the
  splitter, the freeze, the fold, admission, delivery and the
  derivations are all the production ones."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow.core]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.message :as message]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.render.web :as web]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.gen.loop-test")))


(def ^:private process
  (cluster/process-identity {:seon.boot/pid 4242
                             :seon.boot/start-instant (Date. 1700000000000)}))

(def ^:private now (Date. 1700000000000))

;;; ---------------------------------------------------------------------------
;;; The cast, created through the ONE formal path
;;; ---------------------------------------------------------------------------

;;; S3's lesson: there is no namespace-less agent. The planner owns
;;; `my.gen.planner` exactly as alpha and beta own theirs, and it is
;;; that ownership — not a table in this file — that decides where a
;;; red form goes.
(def ^:private cast-spec
  [["root" 'my.agents.root]
   ["planner" 'my.gen.planner]
   ["alpha" 'my.gen.alpha]
   ["beta" 'my.gen.beta]])

(def ^:private cast-rows
  (into []
        (mapcat (fn [[agent-id namespace-name]]
                  (agent/creation-tx {:seon.cluster.agent/id agent-id
                                      :seon.cluster/name "generate-code-v0"
                                      :seon.ns/name namespace-name})))
        cast-spec))

(defn- with-render-context-proc
  "Run the production render proc that answers prompt context requests."
  [cluster body]
  (let [context-channel (async/chan)
        render-channel (async/chan (async/sliding-buffer 1))
        pages-channel (async/chan (async/sliding-buffer 1))
        stream-channel (async/chan (async/sliding-buffer 1))
        completion (async/promise-chan)
        cluster (assoc cluster
                       :seon.env/environment @test-environment
                       :seon.render/context-channel context-channel
                       :seon.cluster.loop/stream-channel stream-channel)
        graph
        (flow.core/create-flow
         {:procs
          {:seon.render.web/render
           {:proc
            (seon.flow/var-process
             #'web/render-step :io
             {:seon.env/environment @test-environment
              :seon.render.web/render-channel render-channel
              :seon.render/context-channel context-channel
              :seon.render.web/pages-channel pages-channel
              :seon.render.web/registration (atom {})
              :seon.render.web/latest-packages (atom {})
              :seon.render.web/interest (atom :all)
              :seon.render.web/completion completion
              :seon.render.web/root-agent-id "planner"
              :seon.cluster.loop/cluster cluster})}}
          :conns []})
        {:keys [report-chan error-chan]} (flow.core/start graph)]
    (async/go-loop [] (when (async/<! report-chan) (recur)))
    (async/go-loop [] (when (async/<! error-chan) (recur)))
    (try
      (flow.core/resume graph)
      (body cluster)
      (finally
        (flow.core/stop graph)
        (async/<!! completion)))))

(defn- with-gen-cluster
  "One in-process cluster with the v0 cast and the production dials."
  [body]
  (test-support/with-database
   (fn [connection]
     (let [launcher
           (seon.flow/start-work-launcher!
            {:seon.env/environment @test-environment
             ::seon.flow/configuration
             {:seon.config.flow.compute/queue-depth 10
              :seon.config.flow.compute/concurrency 2
              :seon.config.flow.io/queue-depth 2
              :seon.config.flow.io/concurrency 2}})]
      (try
       (test-support/seed-cluster! connection "generate-code-v0")
       (db/transact! connection
                   (conj cast-rows
                         (:seon.config/desired-row
                          (config/compile-manifest
                           {:seon.boot/cluster-name "generate-code-v0"
                            :seon.config/manifest
                            {:seon.config.run/max-episode-runs 100}}))))
       ;; Unresolved calls cross the ordinary evaluator boundary; the
       ;; run loop's second static-admission pass was deleted with the
       ;; minimal turn, so no lint bypass is needed.
       (with-render-context-proc
         {:seon.db/connection connection
          :seon.cluster/name "generate-code-v0"
          :seon.flow/work-launcher launcher
          :seon.cluster.run/process process
          :seon.sci.eval/ctx (test-support/fork-cluster-ctx connection)
          :seon.cluster.wake/channel
          (async/chan (async/sliding-buffer 1))
          :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
          :seon.config.eval/time-limit-ms 2000
          :seon.config/on-core-error :panic
          :seon.config.error/recurrence-limit 3
          :seon.config.message/max-chain 4
          :seon.sci.admit/caps
          {:seon.config.eval.result/max-depth 6
           :seon.config.eval.result/max-collection 8
           :seon.config.eval.result/max-string 4096
           :seon.config.eval.result/max-nodes 256}}
         body)
       (finally
         (seon.flow/stop-work-launcher! launcher)))))))

(defn- agent-ids
  [db]
  (sort (db/q '[:find [?id ...] :where [?e :seon.cluster.agent/id ?id]] db)))

(defn- drive!
  "Run each agent's own pass until nobody has work, or `limit` passes.
  Production gives every agent its own turn proc; one thread asking each
  agent's OWN derivation in turn drives the same code."
  [cluster limit]
  (let [connection (:seon.db/connection cluster)]
    (loop [passes 0]
      (when (< passes limit)
        ;; a deterministic clock that still advances: run order is a
        ;; fact this suite reads, and a wall clock makes two runs in the
        ;; same millisecond order themselves at random
        (let [at (Date. (+ (inst-ms now) (* 1000 (long passes))))
              work (some (fn [agent-id]
                           (when-let [orphan (work/interruption @connection
                                                                agent-id)]
                             (cluster.loop/settle-interruption!
                              cluster (:seon.cluster.run/id orphan) at))
                           (work/next-agent-work
                            @connection
                            {:seon.cluster.agent/id agent-id
                             :seon.cluster.run/process process
                             :seon.cluster.work/now at}))
                         (agent-ids @connection))]
          (when work
            (cluster.loop/turn {:seon.cluster.loop/cluster cluster
                                :seon.cluster.work/next work}
                               at)
            (recur (inc passes))))))))

;;; ---------------------------------------------------------------------------
;;; The staged attempt — failure is INJECTED, so the design is what is
;;; being measured rather than the model's mood
;;; ---------------------------------------------------------------------------

(def ^:private program
  "Two namespaces in one reply, each with one form that fails.
  The failures are INJECTED so the observable is the design and not the
  model's mood: an unresolved symbol is red through the ordinary
  evaluator, receipt and admission path, with nothing stubbed."
  (str "I will set up both namespaces.\n"
       "(in-ns 'my.gen.alpha)\n"
       "(defn widget-total [n] (* n 3))\n"
       "(alpha-helper-missing)\n"
       "(in-ns 'my.gen.beta)\n"
       "(defn beta-label [] \"beta\")\n"
       "(beta-helper-missing)\n"))

(def ^:private planner-attempt
  (str program "(my.run/wait \"asked the namespace owners\")"))

(defn- assigned-receipt-id
  "The receipt identity named by an assignment in a rendered prompt."
  [prompt recipient]
  (let [prefix (str "said to " recipient ": Repair problem ")
        suffix " from run "]
    (loop [offset 0]
      (when-let [start (str/index-of prompt prefix offset)]
        (let [identity-start (+ start (count prefix))
              end (str/index-of prompt suffix identity-start)
              receipt-id (when end (subs prompt identity-start end))
              receipt
              (when receipt-id
                (try
                  (edn/read-string receipt-id)
                  (catch Throwable _ nil)))]
          (if (and (vector? receipt)
                   (= 2 (count receipt))
                   (string? (first receipt))
                   (int? (second receipt)))
            receipt-id
            (recur (inc start))))))))

(defn- prompt-agent-id
  "The focal agent owns the namespace in the first REPL prompt."
  [prompt]
  (when-let [prompt-end (str/index-of prompt "=>")]
    (let [namespace-name (subs prompt 0 prompt-end)]
      (some (fn [[agent-id owned-namespace]]
              (when (= namespace-name (str owned-namespace))
                agent-id))
            cast-spec))))

(defn- staged-reply
  "One provider stub for the whole cast, keyed on WHOSE prompt it is."
  [{prompt :seon.ai/prompt}]
  {:seon.ai/text
   (case (prompt-agent-id prompt)
     "planner" planner-attempt

     ;; alpha REPAIRS: it defines the missing helper in its own
     ;; namespace and says so. Nothing about this is a claim the
     ;; settlement derivation trusts.
     "alpha"
     (str "(defn alpha-helper-missing [] 3)\n"
          "(my.run/complete \"defined the missing helper\")")

     ;; beta DECLINES, naming the problem it was assigned — the D10
     ;; shape, read out of its own context the way an agent would.
     "beta"
     (if-let [receipt-id (assigned-receipt-id prompt "beta")]
       (str "(my.message/decline \"planner\" " (pr-str receipt-id)
            " \"That namespace has no contract to satisfy.\")\n"
            "(my.run/complete \"declined\")")
       "(my.run/complete \"I was told nothing I can act on\")")

     "(my.run/complete \"nothing to do\")")})

;;; ---------------------------------------------------------------------------
;;; Queries — every milestone is a fact
;;; ---------------------------------------------------------------------------

(defn- planner-run
  "The planner's first run after its complete form census commits.

  A re-triggered planner attempts again, and asserting over \"the\"
  planner run would quietly change subject when it does. Refuse the
  fixture at this boundary when the frozen forms and their receipts do
  not exist, before routing assertions can settle over absence."
  [db expected-form-count]
  (let [run-id
        (->> (db/q '[:find ?id ?opened
                    :where
                    [?run :seon.cluster.run/id ?id]
                    [?run :seon.cluster.run/opened-at ?opened]
                    [?run :seon.cluster.run/agent ?agent]
                    [?agent :seon.cluster.agent/id "planner"]]
                  db)
             (sort-by (comp inst-ms second))
             ffirst)
        form-count
        (count (db/q '[:find ?form
                       :in $ ?run-id
                       :where
                       [?run :seon.cluster.run/id ?run-id]
                       [?form :seon.cluster.run.form/run ?run]]
                     db run-id))
        receipt-count
        (count (db/q '[:find ?receipt
                       :in $ ?run-id
                       :where
                       [?run :seon.cluster.run/id ?run-id]
                       [?receipt :seon.cluster.eval/run ?run]]
                     db run-id))]
    (if (and (some? run-id)
             (= expected-form-count form-count receipt-count))
      run-id
      (throw
       (ex-info
        "Generative loop fixture refused routing assertions: the planner attempt census did not commit."
        (cond->
         {:seon.gen.loop/expected-form-count expected-form-count
          :seon.gen.loop/run-id run-id
          :seon.gen.loop/form-count form-count
          :seon.gen.loop/receipt-count receipt-count}
          run-id
          (assoc :seon.cluster.run/error
                 (db/q '[:find ?error .
                         :in $ ?run-id
                         :where
                         [?run :seon.cluster.run/id ?run-id]
                         [?run :seon.cluster.run/error ?error]]
                       db run-id))))))))

(defn- form-namespaces
  "Ordinal → parse-time namespace, for the forms that carry one."
  [db run-id]
  (into {}
        (db/q '[:find ?ordinal ?namespace-name
               :in $ ?run-id
               :where
               [?run :seon.cluster.run/id ?run-id]
               [?form :seon.cluster.run.form/run ?run]
               [?form :seon.cluster.run.form/ordinal ?ordinal]
               [?form :seon.cluster.run.form/ns ?namespace]
               [?namespace :seon.ns/name ?namespace-name]]
             db run-id)))

(defn- assignments
  "Messages about one run's red receipts: #{[recipient receipt-id]}."
  [db run-id]
  (set (db/q '[:find ?to-id ?receipt-id
              :in $ ?run-id
              :where
              [?run :seon.cluster.run/id ?run-id]
              [?receipt :seon.cluster.eval/run ?run]
              [?receipt :seon.cluster.eval/id ?receipt-id]
              [?m :seon.cluster.message/about ?receipt]
              [?m :seon.cluster.message/to ?to]
              [?to :seon.cluster.agent/id ?to-id]]
            db run-id)))

(defn- states
  "The derived state of every form of the planner's plan, by ordinal."
  [db run-id]
  (into {}
        (map (juxt :seon.cluster.run.form/ordinal
                   :seon.cluster.work/form-state))
        (:seon.cluster.work/forms (work/plan-settlement db run-id))))

(defn- ambiguous-identities
  "Identity strings this database gives to more than one entity.

  Exactly the derivation `seon.cluster.message/resolve-about` performs on
  an agent's `about`: an agent holds an ordinary string, so it resolves
  against EVERY installed `:db.unique/identity` attribute and refuses a
  tie rather than guessing. Two families minting one string therefore
  does not merely look untidy — it makes every message naming that
  string undeliverable, and the refusal lands as an error fact nobody's
  assertion was reading.

  Returns `{identity #{attribute …}}` for every colliding string, so a
  failure names the two families instead of only a count."
  [db]
  (->> (db/q '[:find ?value ?entity ?attribute
              :where
              [?entity ?attribute ?value]
              [(string? ?value)]]
            db)
       (filter (fn [[_ _ attribute]]
                 (= :db.unique/identity
                    (get-in db [:schema attribute :db/unique]))))
       (group-by first)
       (into {}
             (keep (fn [[value rows]]
                     (when (< 1 (count (into #{} (map second) rows)))
                       [value (into #{} (map #(nth % 2)) rows)]))))))

(defn- run-identity-collisions
  "Colliding identities among the strings ONE run's own entities mint.

  Scoped to this run's forms and receipts by QUERY, not by an exception
  list: any family — declared today or added tomorrow — that mints a
  string a run already minted appears here, while a collision in an
  unrelated family stays that family's own defect.

  `seon.gen.loop-test` currently observes one such foreign collision, a
  cluster's name held by both `:seon.cluster/name` and
  `:seon.config/cluster`; it is filed as
  `docs/seon/issues/one-identity-string-names-two-entities.md` and is not
  fixable inside this namespace's owner."
  [db run-id]
  (let [minted
        (db/q '[:find [?value ...]
               :in $ ?run-id
               :where
               [?run :seon.cluster.run/id ?run-id]
               (or-join [?run ?value]
                        (and [?form :seon.cluster.run.form/run ?run]
                             [?form :seon.cluster.run.form/id ?value])
                        (and [?receipt :seon.cluster.eval/run ?run]
                             [?receipt :seon.cluster.eval/id ?value]))]
             db run-id)]
    (select-keys (ambiguous-identities db) (vec minted))))

;;; ---------------------------------------------------------------------------
;;; The loop
;;; ---------------------------------------------------------------------------

(deftest a-goal-is-a-message-and-the-attempt-routes-its-own-failures
  (with-gen-cluster
   (fn [cluster]
     (let [connection (:seon.db/connection cluster)]
       ;; THE SURFACE: no new agent-facing construct — root's goal is an
       ;; ordinary message, and everything after it is the system's own
       ;; doing.
       (db/transact! connection
                   [{:seon.cluster.message/id "goal-1"
                     :seon.cluster.message/to
                     [:seon.cluster.agent/id "planner"]
                     :seon.cluster.message/from
                     [:seon.cluster.agent/id "root"]
                     :seon.cluster.message/content
                     (str "Build the widget helpers: my.gen.alpha owns "
                          "the arithmetic and my.gen.beta owns the label.")
                     :seon.cluster.message/at now}])
       (with-redefs [ai/complete staged-reply]
         (drive! cluster 12))
       (let [db @connection
             run-id (planner-run db 7)]

         (testing "the attempt froze one plan whose forms carry the
                   namespace each was WRITTEN under"
           (is (= {0 'my.gen.planner
                   1 'my.gen.alpha
                   2 'my.gen.alpha
                   3 'my.gen.alpha
                   4 'my.gen.beta
                   5 'my.gen.beta
                   6 'my.gen.beta}
                  (form-namespaces db run-id))
               "REPL semantics across two declarations in ONE reply.
                The agent namespace is the default until a namespace
                declaration governs; from there the last EXPLICIT
                declaration holds, so `(alpha-helper-missing)` runs in
                my.gen.alpha where it was written. Presuming an ordinary
                call moved the namespace is what erased most of the
                program graph (2026-07-29); only a form that mentions
                `ns`/`in-ns` below its head clears attribution.")
           (is (= 7 (count (db/q '[:find ?f
                                  :in $ ?run-id
                                  :where
                                  [?run :seon.cluster.run/id ?run-id]
                                  [?f :seon.cluster.run.form/run ?run]]
                                db run-id)))
               "seven forms froze and every form has evaluation truth"))

         (testing "execution honours parse-time attribution"
           (is (str/includes?
                (db/q '[:find ?edn .
                       :in $ ?run-id
                       :where
                       [?run :seon.cluster.run/id ?run-id]
                       [?r :seon.cluster.eval/run ?run]
                       [?r :seon.cluster.eval/ordinal 1]
                       [?r :seon.cluster.eval/result-edn ?edn]]
                     db run-id)
                "my.gen.alpha/widget-total")
               "the definition is installed in the namespace the reader
                attributed to its form"))

         (testing "the fold CONTINUES past a red form — nothing halts"
           (is (= 7 (count (db/q '[:find ?r
                                  :in $ ?run-id
                                  :where
                                  [?run :seon.cluster.run/id ?run-id]
                                  [?r :seon.cluster.eval/run ?run]]
                                db run-id)))
               "one receipt per ordinal, including the ones after the
                first failure"))

         (testing "each red form is addressed to the agent that owns the
                   namespace it was written in"
           (is (= #{["alpha" (work/problem-id run-id 2)]
                    ["beta" (work/problem-id run-id 5)]
                    ["planner" (work/problem-id run-id 5)]}
                  (assignments db run-id))
               "the two repairs follow parse-time ownership; beta's
                ordinary transcript includes the problem identity, so its
                explicit decline also reaches the planner about that fact"))

         (testing "an assignment rides the terminal transaction of the
                   very form that produced it"
           (let [assignment-tx (db/q '[:find ?tx .
                                      :in $ ?receipt-id
                                      :where
                                      [?about :seon.cluster.eval/id ?receipt-id]
                                      [?m :seon.cluster.message/about ?about
                                       ?tx]]
                                    db (work/problem-id run-id 2))]
             (is (= [2]
                    (db/q '[:find [?ordinal ...]
                           :in $ ?tx
                           :where
                           [?r :seon.cluster.eval/error _ ?tx]
                           [?r :seon.cluster.eval/ordinal ?ordinal]]
                         db assignment-tx))
                 "no window in which an assignment exists and the red
                  receipt explaining it does not")))

         ;; THE CLASS, not the instance. Before 2026-08-07 the frozen
         ;; form and its receipt both minted `(pr-str [run-id ordinal])`
         ;; into two `:db.unique/identity` attributes, so EVERY problem
         ;; identity an agent could name resolved to two entities and
         ;; every `my.message/decline` about one was refused
         ;; `:seon.cluster.message/ambiguous-about` — loudly, into an
         ;; error fact, which no assertion here was reading. The form
         ;; identity is now qualified by its own attribute
         ;; (`seon.cluster.run/form-identity`), and this is the standing
         ;; proof that no OTHER family reintroduces the class: it runs
         ;; the resolver's own derivation over a real production drive
         ;; and scopes it BY QUERY to the strings this run minted, so a
         ;; new colliding family fails automatically — it is not a check
         ;; for the one pair that broke.
         (testing "no identity this run minted names two entities"
           (is (= {} (run-identity-collisions db run-id))
               "an agent holds an ordinary string, so a string held by
                two identity attributes makes every message naming it
                undeliverable"))

         (testing "an owner sees the problem through the ordinary transcript
                   renderer and may make a real declination join"
           (is (= :owner-declared-cant (get (states db run-id) 5)))
           (is (= :routed (get (states db run-id) 2))
               "alpha's repair prose does not mutate the red receipt"))

         (testing "the red evidence survives the settlement"
           (is (some? (db/q '[:find ?error .
                             :in $ ?run-id
                             :where
                             [?run :seon.cluster.run/id ?run-id]
                             [?r :seon.cluster.eval/run ?run]
                             [?r :seon.cluster.eval/ordinal 5]
                             [?r :seon.cluster.eval/error ?error]]
                           db run-id))))

         (testing "the plan is NOT settled, and no agent's completion
                   can make it so"
           (is (false? (:seon.cluster.work/settled?
                        (work/plan-settlement db run-id))))
           (is (some? (db/q '[:find ?closed .
                             :in $ ?run-id
                             :where
                             [?run :seon.cluster.run/id ?run-id]
                             [?run :seon.cluster.run/closed-at ?closed]]
                           db run-id))
               "the planner's run closed normally — settlement is a
                derivation over forms, never a run state"))

         (testing "the derivation transacts nothing"
           ;; The live drive cannot settle this — its cluster is still
           ;; taking turns while the derivation runs, so `max-tx` moves
           ;; for reasons that have nothing to do with it. Here the
           ;; drive has stopped, so the basis is decisive.
           (let [before (:max-tx @connection)]
             (work/plan-settlement @connection run-id)
             (is (= before (:max-tx @connection))
                 "plan settlement is a pure function of a database
                  value; deriving it can never commit")))

         (testing "the goal scopes the whole conversation by cause alone"
           (is (= 1 (message/chain-depth
                     db
                     (db/q '[:find ?id .
                            :in $ ?receipt-id
                            :where
                            [?about :seon.cluster.eval/id ?receipt-id]
                            [?m :seon.cluster.message/about ?about]
                            [?m :seon.cluster.message/to ?to]
                            [?to :seon.cluster.agent/id "alpha"]
                            [?m :seon.cluster.message/id ?id]]
                          db (work/problem-id run-id 2))))
               "the assignment is one hop from the human-shaped goal")))))))

(deftest a-result-built-on-a-failed-form-is-red-and-routes
  ;; The open issue's exact evidence case
  ;; (`a-failed-form-does-not-stop-the-fold`), replayed as the plan's
  ;; obligation 7: form 0 fails, form 1 computes on the definition that
  ;; never happened, and the fold continues. The question is whether the
  ;; value form 1 produces is RED — because if it is not, a run can
  ;; still complete with a confident lie.
  (with-gen-cluster
   (fn [cluster]
     (let [connection (:seon.db/connection cluster)]
       (db/transact! connection
                   [{:seon.cluster.message/id "goal-1"
                     :seon.cluster.message/to
                     [:seon.cluster.agent/id "planner"]
                     :seon.cluster.message/from
                     [:seon.cluster.agent/id "root"]
                     :seon.cluster.message/content "Count the primes."
                     :seon.cluster.message/at now}])
       (with-redefs [ai/complete
                     (fn [{prompt :seon.ai/prompt}]
                       {:seon.ai/text
                        (if (= "planner" (prompt-agent-id prompt))
                          (str "(in-ns 'my.gen.alpha)\n"
                               ;; fails: Math/sqrt is not in the base ctx
                               "(def primes (Math/sqrt 4))\n"
                               ;; evaluates fine, and its VALUE references
                               ;; the var form 0 never bound
                               "primes\n")
                          "(my.run/wait \"nothing\")")})]
         (drive! cluster 6))
       (let [db @connection
             run-id (planner-run db 3)
             state (states db run-id)]
         (is (= :routed (get state 1))
             "the failed def itself is red and routed")
         (is (= :routed (get state 2))
             "the unbound-var result is red at the one admission gate and
              routes like any other red form — no string matching, and no
              run completing on a value that references nothing")
         (is (false? (:seon.cluster.work/settled?
                      (work/plan-settlement db run-id)))))))))

(deftest a-silent-owner-leaves-the-plan-unsettled-forever
  ;; S6's adversarial history, as a required proof: the owner that never
  ;; answers cannot produce a completed goal, and the facts — not a
  ;; convention — are what say so.
  (with-gen-cluster
   (fn [cluster]
     (let [connection (:seon.db/connection cluster)]
       (db/transact! connection
                   [{:seon.cluster.message/id "goal-1"
                     :seon.cluster.message/to
                     [:seon.cluster.agent/id "planner"]
                     :seon.cluster.message/from
                     [:seon.cluster.agent/id "root"]
                     :seon.cluster.message/content "Build the helpers."
                     :seon.cluster.message/at now}])
       ;; every owner is mute; only the planner ever answers, and it
       ;; answers by claiming it is done
       (with-redefs [ai/complete
                     (fn [{prompt :seon.ai/prompt}]
                       {:seon.ai/text
                        (if (= "planner" (prompt-agent-id prompt))
                          (str program
                               "(my.run/complete \"the program is built\")")
                          "(my.run/wait \"saying nothing\")")})]
         (drive! cluster 12))
       (let [db @connection
             run-id (planner-run db 7)]
         (is (= "the program is built"
                (db/q '[:find ?content .
                       :where
                       [?m :seon.cluster.message/to ?to]
                       [?to :seon.cluster.agent/id "root"]
                       [?m :seon.cluster.message/content ?content]]
                     db))
             "the planner said it was finished")
         (is (false? (:seon.cluster.work/settled?
                      (work/plan-settlement db run-id)))
             "and the facts contradict it — an unsettled routed problem
              keeps the plan open no matter what the reply says")
         (is (= #{:routed}
                (set (vals (select-keys (states db run-id) [2 5]))))
             "both red forms are routed and neither owner answered"))))))
