(ns seon.repl
  "An agent's REPL speaking nREPL's message model on its one thread (session F2, after session
  R2's r2.repl; the owner chose nREPL's model for agents).

  The agent's process runs `agent-step` (the step-fn contract) under seon.step/run-loop.
  A REPL request (nREPL's `eval`, the one op any client sends) is handled on this thread by
  `handler`: eval-code with middleware composed over it, each `(fn [handler] (fn [request]
  ...))`, as in nREPL. A request carries :reply, a fn of one response map. Eval reads the
  code once, form by form, and answers each form (eval-error or interrupted in its status),
  with what it printed; then one `done`.

  Seon's additions are middleware over nREPL's own seams (a plain stack: G1's contract
  admission and arming plug in as one more `wrap-…`):
    wrap-print    the value through the one value printer (seon.print)
    wrap-caught   the error also as the one error map
    wrap-cost     per-form µs, allocated bytes and costliest functions, around the per-form eval fn
    wrap-record   each form's compiler account (its definitions, references) becomes facts on
                  this branch as it runs (seon.program, transact!)
  nREPL's own \"status\" stays a string key; a form's answer is values under its reply item's
  keys (:id :tag :printed :out :err :fns ...), which the fold selects: nothing is printed and
  read back, or streamed and reassembled, in the process. The live value and error ride under :repl/value
  and :repl/ex. The requests and answers are in-process maps:
  root's request arrives as a value ({:op :eval :id :forms ...}, a frame on the agent's
  socket), and the request's answers go back folded, as one {:op :reply :items ...}.

  Interrupt is out of band: root sends SIGUSR1 (seon.intr), which stops the request it
  arrives in and nothing after it. Seon's platform ops (tests, stats, clone,
  messages, writes) are {:op k} values, handled by `agent-step`."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn- defchecked]] [seon.error :as err] [jolt.nrepl :as nrepl] [seon.intr :as intr] [seon.reader :as reader] [seon.agent :as agent] [seon.contract :as contract] [my.db] [my.agent] [my.schema] [malli.registry :as mr]
            [seon.program :as program] [jolt.analyzer] [seon.lib :as lib] [seon.print :as print] [seon.render :as render] [seon.profile :as profile] [seon.sock :as sock] [seon.step :as step]
            [jolt.ffi :as ffi] [seon.config :as cfg] [clojure.test]))

(defchecked ^:private outer-frames [:=> [:cat] :any] @#'jolt.nrepl/outer-frames)

(defn- form-text {:malli/schema [:=> [:cat :any] :string]}
  [form] (print/text form (:form print/budgets)))

(defn- form-src
  "The exact text of form in code, from its reader position (:line, :column) to where the
  reader stopped after it (end-line, end-col; all 1-based), or nil for a form that carries
  no position (a symbol, a number)."
  {:malli/schema [:=> [:cat :string [:vector :int] :any :int :int] [:maybe :string]]}
  [code starts form end-line end-col]
  (let [{:keys [line column]} (meta form)]
    (when (and line column)
      (subs code (+ (starts (dec line)) (dec column)) (+ (starts (dec end-line)) (dec end-col))))))

(defn- keep-source!
  "A definition keeps its exact text on its var (owner: source reads the live var), so
  clojure.repl/source shows what this process runs, a REPL-typed defn included."
  {:malli/schema [:=> [:cat :any [:maybe :string]] :any]}
  [v src]
  (when (and (var? v) src) (alter-meta! v assoc :source src)))

(defn- eval-code
  "Read the request's code once, form by form, and evaluate each with (:repl/eval-fn req)
  under seon.intr/run, in the request's \"ns\", else the agent's own. Answers each form,
  with its exact text (:repl/src); an interrupt stops the request (one pending since its
  asker cleared it included: agent-step, the turn loop); then done."
  {:malli/schema [:=> [:cat :seon.wire/msg] :any]}
  [{:keys [reply] :as req}]
  (let [code (get req "code")
        r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. code))
        starts (delay (reader/line-starts code))
        eval-fn (:repl/eval-fn req eval)
        ;; the form's budget of work (engine ticks): the request's, else the config key for an
        ;; agent's model and an outside agent; root's own platform requests run unbounded
        ticks (let [t (or (:ticks req)
                          (when (#{:model :outside :ui} (:origin req)) (cfg/value :seon.config.agent/form-ticks)))]
                (when (and t (pos? t)) t))]
    (loop [ns (or (some-> (get req "ns") symbol find-ns) (:ns @agent/state))]
      (let [form (try (binding [*ns* ns] (read {:eof ::eof} r)) (catch Throwable e e))]
        (cond
          (= ::eof form)
          (do (swap! agent/state assoc :ns ns)
              (reply {"status" ["done"]}))

          (instance? Throwable form)
          (let [n (:n (swap! agent/state update :n (fnil inc 0)))]
            (reply {:repl/ex form :tag :err "status" ["eval-error"] :repl/phase :read :id (dec n)
                    :err-text (nrepl/err->msg form {:phase :read-source})})
            (reply {"status" ["done"]}))

          :else
          (let [n (dec (:n (swap! agent/state update :n (fnil inc 0))))
                out (java.io.StringWriter.)
                err (java.io.StringWriter.)
                ;; *1 *2 *3 *e as clojure.main/repl keeps them; clojure.test reports to
                ;; *test-out*, bound to the form's output as nREPL does
                {v1 :v1 v2 :v2 v3 :v3 e1 :e} (:repl-vars @agent/state)
                [tag v ns' outer] (try (binding [*ns* ns *out* out *err* err clojure.test/*test-out* out *1 v1 *2 v2 *3 v3 *e e1]
                                         (let [v (intr/run ticks #(eval-fn form))] [:ret v *ns*]))
                                       (catch Throwable e [:err e ns (outer-frames)]))
                common {:id n :out (not-empty (str out)) :form (form-text form) :repl/form form
                        :repl/src (form-src code @starts form (.getLineNumber r) (.getColumnNumber r))}]
            (swap! agent/state assoc :repl-vars (if (= tag :ret) {:v1 v :v2 v1 :v3 v2 :e e1} {:v1 v1 :v2 v2 :v3 v3 :e v}))
            (when (= tag :ret) (keep-source! v (:repl/src common)))
            (if (= tag :ret)
              (let [h (symbol "result" (str "h" n))]
                (swap! agent/state assoc-in [:results (keyword (str "h" n))] v)
                (intern (create-ns 'result) (symbol (name h)) v)
                (reply (merge common {:repl/value v :tag :ret :handle (keyword (str "h" n))}))
                (recur ns'))
              (let [stopped? (:interrupted (ex-data v))]
                (reply (merge common {:repl/ex v :tag :err :err-text (str err (nrepl/err->msg v {:outer outer}))
                                      "status" (if stopped? ["interrupted"] ["eval-error"])}))
                (if stopped? (reply {"status" ["done"]}) (recur ns))))))))))

;; ---- middleware -------------------------------------------------------------------

(defn- around-reply {:malli/schema [:=> [:cat :seon.wire/msg :seon.host/fn] :seon.wire/msg]}
  [req f] (assoc req :reply (let [r (:reply req)] (fn [m] (r (f m))))))

(defn wrap-print
  "The value as its render shows it (seon.render/printed: its schema's declared AI render,
  else the one value printer), its cuts reading the rest through the value's result
  handle: `value`, with the printer's tokens, truncation and elision count. A value whose
  first part does not fit the budget answers the printer's error (insufficient budget) by
  its render, the error beside it."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m]
           (if (contains? m :repl/value)
             (let [pv (render/printed (:repl/value m) (some->> (:handle m) name (symbol "result")))]
               (if-let [text (:text pv)]
                 (cond-> (assoc m :printed text :tokens (:tokens pv))
                   (:truncated? pv) (assoc :truncated? true)
                   (some-> (:elisions pv) pos?) (assoc :elisions (:elisions pv)))
                 (assoc m :printed (render/ai pv {}) :printer-error pv)))
             m))))))

(defn- var-of
  "The var a qualified symbol names, nil when its namespace is not loaded or it has no such var."
  {:malli/schema [:=> [:cat :qualified-symbol] [:maybe :seon.host/var]]}
  [sym]
  (when (some-> (namespace sym) symbol find-ns) (find-var sym)))

(defn- located
  "Where an eval error is, as data in the form's own terms (what `(source v)` prints, 1-based):
  :seon.error/at {:line :column} and, for a runtime error, the fn it was in as :seon.error/in.
  Jolt's positions are the request's text's; a compile error is in the form being read (its
  start is m's), a runtime error in the form that defined the fn (its var's :line). Nothing
  (an empty map) when the error names no position, that form has none, or it lies before the form's start.
  Only an eval item gets it: the form's start is the item's, so the agent's other error boundaries
  (`seon.agent/error-map` alone) carry Jolt's keys without it."
  {:malli/schema [:=> [:cat :seon.host/throwable :map]
                  [:map [:seon.error/at {:optional true} :seon.error/at] [:seon.error/in {:optional true} :qualified-symbol]]]}
  [e m]
  (let [d (merge (clojure.main/ex-triage (assoc (Throwable->map e) :clojure.error/phase :execution)) (ex-data e))
        compile? (:jolt.error/line d)
        line (or (:jolt.error/line d) (:clojure.error/line d))
        sym (when-not compile? (:clojure.error/symbol d))
        {start :line col0 :column} (meta (if compile? (:repl/form m) (some-> sym var-of)))]
    (if (and line start (<= start line))
      (let [n (inc (- line start))
            col (some-> (:jolt.error/column d) (- (if (and col0 (= 1 n)) (dec col0) 0)))]
        (cond-> {:seon.error/at (cond-> {:line n} col (assoc :column col))}
          sym (assoc :seon.error/in sym)))
      {})))

(defn wrap-caught
  "An eval error also as the one error map (seon.agent/error-map, the item's :err, its
  text the error's render: message and fix), and
  noted in this agent's error fold (seon.error/note): its next commit carries the fold to
  root, which counts each signature across agents."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m] (if-let [e (:repl/ex m)]
                   (let [em (cond-> (agent/error-map e) (:repl/phase m) (assoc :phase :read))
                         em (if (= :seon.agent/eval-error (:seon.error/type em)) (merge em (located e m)) em)]
                     (swap! agent/state update :errors err/note em (:form m) (System/currentTimeMillis))
                     (assoc m :err em :printed (render/ai em {})))
                   m))))))

(defn wrap-cost
  "Per-form wall µs, allocated bytes and the form's costliest contracted functions (each
  with its exact calls and estimated ms: seon.profile), measured around the per-form eval
  fn. Reading them costs the functions the form called, not the program's size."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (let [last (volatile! nil)
          ef (:repl/eval-fn req eval)]
      (h (-> req
             (assoc :repl/eval-fn (fn [form]
                                    (let [a0 (profile/alloc-bytes) _ (profile/begin!) t0 (profile/now-ns)]
                                      (try (ef form)
                                           (finally (let [t1 (profile/now-ns) a1 (profile/alloc-bytes)]
                                                      (vreset! last [(/ (quot (- t1 t0) 1000) 1000.0) (- a1 a0) (profile/top (profile/touched) (cfg/value :seon.config.agent/form-top-fns))])))))))
             (around-reply (fn [m]
                             (if-let [[ms a fns] (when (or (contains? m :repl/value) (contains? m :repl/ex)) @last)]
                               (do (vreset! last nil)
                                   (cond-> (assoc m :ms ms :alloc-bytes a)
                                     (seq fns) (assoc :fns (mapv (fn [c] (update c :ms #(/ (Math/round (* % 1000.0)) 1000.0))) fns))))
                               m))))))))

(defn- ns-form?
  "Does form's head resolve, in nsx, to clojure.core/ns (resolution, as the compiler does)?"
  {:malli/schema [:=> [:cat :any :seon.host/namespace] :boolean]}
  [form nsx]
  (boolean (and (seq? form) (symbol? (first form)) (= #'clojure.core/ns (ns-resolve nsx (first form))))))

(defn wrap-record
  "What a form leaves that is program becomes facts on this branch (agent mode) as it runs
  (seon.program): the compiler's account of the form (jolt.analyzer/*unit-tap* around its
  eval, and a readback of the vars it set), applied at once through the run-ahead writer
  (seon.agent/transact!), so the next form of the request already reads it. An `ns` form, a
  definition, a declaration or a change to a namespace's environment, in any namespace but
  a scratch one. The answer carries the vars it set in any namespace, scratch ones included
  (:repl/defined, for admission), and what it recorded (:recorded)."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (let [last (volatile! nil)
          ef (:repl/eval-fn req eval)]
      (h (-> req
             (assoc :repl/eval-fn
                    (fn [form]
                      (let [acc (volatile! {:nodes [] :before {}})
                            ns0 *ns*
                            nsf? (ns-form? form ns0)]
                        (vreset! last nil)
                        (try (binding [jolt.analyzer/*unit-tap* (program/tap acc)] (ef form))
                             (finally (vreset! last (assoc @acc :ns (ns-name ns0) :ns-form? nsf? :ns-after (ns-name *ns*))))))))
             (around-reply
               (fn [m]
                 (let [l (when (or (contains? m :repl/value) (contains? m :repl/ex)) @last)
                       ;; Clojure's ns puts the name's metadata on the namespace; Jolt's drops it
                       ;; (jolt/ns-meta is the fix at its cause)
                       md (when (:ns-form? l) (not-empty (dissoc (meta (second (:repl/form m))) :line :column)))
                       _ (when md (alter-meta! (the-ns (:ns-after l)) merge md))
                       acct (when (and l (= :agent (:mode @agent/state)) (:repl/src m))
                              (program/account (assoc l :src (:repl/src m) :form (:repl/form m) :ran? (contains? m :repl/value))))]
                   (when l (vreset! last nil))
                   (if acct
                     (do (when contract/record? (agent/transact! (program/record-tx (agent/db) acct)))
                         (assoc m :recorded (vec (concat (map :var (:defs acct)) (when (:ns-form? acct) [(:ns acct)])))
                                :repl/defined (mapv :var (:defs acct))))
                     (cond-> m l (assoc :repl/defined (program/set-vars l))))))))))))

(defn- branch-registry
  "The registry this agent's evaluation binds (seon.program/schema-registry of its branch's
  schema changes), so what it defines next compiles against them; empty when no branch is
  held (root, a scratch session). Reused while the changes are equal: keying is 0.7 ms,
  compiling the population 5 ms."
  {:malli/schema [:=> [:cat] :any]}
  []
  (if-let [db (:db @agent/state)]
    (let [k (program/schema-changes db)
          held (:schema-registry @agent/state)]
      (if (= k (:key held))
        (:registry held)
        (let [r (program/schema-registry k)]
          (swap! agent/state assoc :schema-registry {:key k :registry r})
          r)))
    {}))

(defn wrap-registry
  "Each form evaluates with malli's dynamic registry bound to its branch's population
  (branch-registry), read before the form: a schema the previous form registered is in
  effect for this one."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (let [ef (:repl/eval-fn req eval)]
      (h (assoc req :repl/eval-fn (fn [form] (binding [mr/*registry* (branch-registry)] (ef form))))))))

(defn- instrument
  "Instrument a definition by its contract (G1's proto.agent/admit!, Seon's rule): a function var
  with :malli/schema is armed and leaves the agent's uncontracted set; one without joins
  it, and the response carries the warning naming every uncontracted definition so far.
  Answers the response's additions (its reply item's keys)."
  {:malli/schema [:=> [:cat :seon.host/var] [:maybe :seon.wire/msg]]}
  [v]
  (when (contract/function-var? v)
    (let [sym (symbol v)]
      (if (contract/contract v)
        (let [r (contract/arm! v)]
          (swap! agent/state update :uncontracted (fnil disj #{}) sym)
          (if (contract/error? r)
            {:contract :invalid :contract-error r}
            {:contract r}))
        (let [u (:uncontracted (swap! agent/state update :uncontracted (fnil conj #{}) sym))]
          {:warnings [{:seon.repl/finding :uncontracted-definition
                                     :seon.repl/fix (str "Declare a contract: (defn " (name sym) " {:malli/schema [:=> [:cat ...] ...]} [...] ...). "
                                                         "A branch carrying an uncontracted function is refused at merge.")
                                     :uncontracted (vec (sort-by str u)) :count (count u)}]})))))

(defn wrap-instrument
  "Each var a form defined (wrap-record's :repl/defined) is instrumented by its contract
  (instrument): armed, or a growing warning."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m]
           ;; arming compiles the contract: against the branch's population, as its eval did
           (binding [mr/*registry* (if (seq (:repl/defined m)) (branch-registry) {})]
             (reduce (fn [m s] (if-let [v (find-var s)] (merge m (instrument v)) m)) m (:repl/defined m))))))))

(def middleware
  "Outermost first. Answers pass innermost first: record, cost, instrument, caught, print. The
  eval fn is the registry's binding around record's around cost's around eval, so an eval's
  cost is the eval's (with the compiler's tap in it, as part of compiling); keying the
  branch's registry, read before each form, is not the form's (0.7 ms measured)."
  [wrap-print wrap-caught wrap-instrument wrap-cost wrap-record wrap-registry])

(defn handler
  "eval-code with the middleware composed over it, outermost first."
  {:malli/schema [:=> [:cat] :seon.host/fn]}
  [] (reduce (fn [h m] (m h)) eval-code (reverse middleware)))

(def ^:private the-handler (delay (handler)))

;; ---- reply items and their recording (root folds the wire; the agent folds in-process) ----

(def ^:private item-keys
  "The keys of a form's answer that are its reply item's."
  [:id :tag :form :printed :out :err-text :ms :alloc-bytes :handle :err :tokens :truncated? :elisions :recorded :contract :contract-error :warnings :fns])

(defn fold
  "The answers of one eval request (in order) -> its reply items: each form's answer (:tag),
  its item's keys selected."
  {:malli/schema [:=> [:cat [:sequential :seon.wire/msg]] :seon.agent/items]}
  [answers]
  (into [] (comp (filter :tag) (map #(into {} (filter val) (select-keys % item-keys)))) answers))

(defn record-tx
  "The recording transaction of one eval request: one evaluation entity per item, on the
  agent's entity, its details in one EDN attribute (the lean record, L1's measurement:
  three attributes, not nine, nothing queries the details apart), and the agent's answered
  t. A form's error is stored as its error map (data: grouped by :seon.error/type and
  operation without parsing text). Ids are new each time; the agent entity is the one
  upsert."
  {:malli/schema [:=> [:cat :seon.agent/id :string :seon.agent/items [:maybe :int]] :seon.db/tx-data]}
  [id run items t-basis]
  (conj (vec (for [it items :when (:id it)]
               {:seon.cluster.eval/id (str run "/" (:id it)) :seon.cluster.eval/agent "agent"
                :seon.cluster.eval/record (cond-> (select-keys it [:tag :form :out :ms :alloc-bytes :fns :origin])
                                            (:printed it) (assoc :shown (:printed it))
                                            (= :err (:tag it)) (assoc :error (or (:err it) (:printed it))))}))
        (cond-> {:db/id "agent" :seon.agent/id id} t-basis (assoc :seon.agent/answered-t t-basis))))

(defn- record!
  "Record items (with the defs their forms recorded) on this branch through the agent's
  write path (each item's :origin says who asked, when not the agent itself). Answers items."
  {:malli/schema [:=> [:cat :seon.agent/items] :seon.agent/items]}
  [items]
  ;; the effects asked during the request (seon.agent/fx-record!) join its recording
  (let [defs (into [] (mapcat val) (:defs @agent/state))]
    (swap! agent/state dissoc :defs)
    (if (and contract/record? (= :agent (:mode @agent/state)) (seq items))
      (do (agent/transact! (into defs (record-tx (:id @agent/state) (str (name (:id @agent/state)) "-" (lib/c-getpid) "-" (profile/now-ns))
                                                 items (:max-tx (agent/db)))))
          items)
      items)))

(defn eval-string
  "Evaluate code through the one REPL path: the handler and its middleware, its answers
  folded into reply items, each with who asked (:origin, when not the agent's own :model),
  and recorded (an agent's, on its branch). From inside an agent, the turn loop's forms; req
  adds to the request (root's: seon.server's namespace, origin, budget and eval fn; root's
  requests to an agent: agent-step's). The request's entrant boundary: a defect past a
  form's own (a middleware's, the printer's) ends the request with the answers so far and
  one :err item, its error map, recorded with them."
  {:malli/schema [:function [:=> [:cat :string] :seon.agent/items] [:=> [:cat :string :map] :seon.agent/items]]}
  ([code] (eval-string code {:origin :model}))
  ([code req]
   (let [answers (volatile! [])
         o (:origin req)]
     (try (@the-handler (assoc req "code" code :reply #(vswap! answers conj %)))
          (catch Throwable e
            (let [em (agent/error-map e)]
              (vswap! answers conj {:tag :err :id (dec (:n (swap! agent/state update :n (fnil inc 0))))
                                    :err em :printed (:seon.error/message em) :err-text (nrepl/err->msg e)}))))
     (record! (cond->> (fold @answers) (and o (not= :model o)) (mapv #(assoc % :origin o)))))))

;; ---- the process: agent-step under run-loop ----------------------------------------

(defn agent-step
  "The agent as a step-fn, run on its one thread by seon.step/run-loop: requests in on
  :sock (an eval as {:op :eval :id :forms}, Seon's ops as {:op k ...}), replies out on
  :sock; :pause, :resume, :stop and :ping are taken by the loop. Its REPL state is the
  process's one state map (seon.agent/state), which the code it evaluates reads, so the
  step's own state is empty."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:sock "root's requests"} :outs {:sock "replies"} :workload :io
       :ping-map-fn (fn [_] {:evals (:n @agent/state) :pending (count (:pending @agent/state))})})
  ([_] {})
  ([st _] st)
  ([st _ m]
   (if (= :eval (:op m))
     (do
       ;; an interrupt left from before this request is not its; then root hears that it began
       ;; (its proc's :started event), what an interrupt or a kill meant for a running eval
       ;; waits on, never a sleep: one arriving from here on stops it
       (intr/clear-pending!)
       (agent/send! {:op :started :id (:id m)})
       [st {:sock [{:op :reply :id (:id m) :items (eval-string (:forms m) (select-keys m [:ticks :origin]))
                    :t (:max-tx (agent/db))}]}])
     [st (when-let [ms (agent/handle! m)] {:sock ms})])))

(defn serve!
  "The agent's loop: one request at a time, in arrival order. Never returns. The agent
  process's entrant boundary: a defect no request's own boundary caught (the agent may
  redefine a function its loop calls) ends the process; its cause is the attempt's end on
  the branch (seon.agent/died!), and the exit status 70 (EX_SOFTWARE) names the death."
  {:malli/schema [:=> [:cat] :any]}
  []
  (try (step/run-loop #'agent-step {}
                      (fn [] (let [m (agent/next-msg!)] (when-not (= m :dead) [:sock m])))
                      (fn [_ m] (agent/send! m)))
       (lib/c-exit 0)
       (catch Throwable e
         (try (agent/died! e) (finally (lib/c-exit 70))))))

(defn run!
  "The agent's body, in the child of the fork server; fd is its socket to root. Never
  returns."
  {:malli/schema [:=> [:cat :seon.lib/fd :map :seon.host/db :int] :any]}
  [fd m db0 t-req-ns]
  (agent/init! fd m db0 serve!)
  (agent/hello! t-req-ns (cond-> {:interrupt-ok (:interrupt-ok @agent/state)}
                            (seq (:load-errors @agent/state)) (assoc :load-errors (:load-errors @agent/state))
                            (:misaligned @agent/state) (assoc :misaligned (:misaligned @agent/state))
                            (:unwatched @agent/state) (assoc :unwatched (:unwatched @agent/state))))
  (serve!))
