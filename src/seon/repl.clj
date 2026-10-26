(ns seon.repl
  "An agent's REPL speaking nREPL's message model on its one thread (session F2, after session
  R2's r2.repl; the owner chose nREPL's model for agents).

  The agent's process runs `agent-step` (the step-fn contract) under seon.step/run-loop.
  A REPL request (nREPL's `eval`, the one op any client sends) is handled on this thread by
  `handler`: eval-code with middleware composed over it, each `(fn [handler] (fn [request]
  ...))`, as in nREPL. A request carries :reply, a fn of one response map. Eval reads the
  code once, form by form, and answers each form (eval-error or interrupted in its status),
  with what it printed, stopping at the first error; then one `done`. `eval-string` folds a
  request's answers into its one response (`:seon.eval/response`, `response`), printed by the
  one printer under its handle, result/e<n>.

  Seon's additions are middleware over nREPL's own seams (a plain stack: G1's contract
  admission and arming plug in as one more `wrap-…`):
    wrap-caught   the error also as the one error map
    wrap-cost     per-form µs, allocated bytes and costliest functions, around the per-form eval fn
    wrap-record   each form's compiler account (its definitions, references) becomes facts on
                  this branch as it runs (seon.program, transact!)
  nREPL's own \"status\" stays a string key; a form's answer is values under its keys
  (:tag :out :err-out :err :ms :ticks ...), which the fold reads: nothing is printed and read
  back, or streamed and reassembled, in the process. The live value and error ride under
  :repl/value and :repl/ex. The requests and answers are in-process maps: root's request
  arrives as a value ({:op :eval :id :forms ...}, a frame on the agent's socket), and its
  response goes back as one {:op :reply :response ...}, its live value left in the agent.

  Interrupt is out of band: root sends SIGUSR1 (seon.intr), which stops the request it
  arrives in and nothing after it. Seon's platform ops (tests, stats, clone,
  messages, writes) are {:op k} values, handled by `agent-step`."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn- defchecked]] [seon.error :as err] [jolt.nrepl :as nrepl] [seon.intr :as intr] [seon.reader :as reader] [seon.ref :as ref] [seon.agent :as agent] [seon.contract :as contract] [my.db] [my.agent] [my.schema] [malli.registry :as mr]
            [seon.program :as program] [jolt.analyzer] [seon.lib :as lib] [seon.print :as print] [seon.print.select :as select] [seon.render :as render] [seon.ctx :as ctx] [seon.web.html :as h] [seon.profile :as profile] [seon.sock :as sock] [seon.step :as step]
            [jolt.ffi :as ffi] [seon.config :as cfg] [clojure.test] [datahike.api :as d] [seon.db :as pdb] [seon.edn :as sedn]))

(defchecked ^:private outer-frames [:=> [:cat] :any] @#'jolt.nrepl/outer-frames)

(defn- form-src
  "The exact text of form in code, from its reader position (:line, :column; 1-based) to end,
  the offset where the reader stopped after it, or nil for a form that carries no position (a
  symbol, a number)."
  {:malli/schema [:=> [:cat :string [:vector :int] :any :int] [:maybe :string]]}
  [code starts form end]
  (let [{:keys [line column]} (meta form)]
    (when (and line column)
      (subs code (+ (starts (dec line)) (dec column)) end))))

(defn- keep-source!
  "A definition keeps its exact text on its var (owner: source reads the live var), so
  clojure.repl/source shows what this process runs, a REPL-typed defn included."
  {:malli/schema [:=> [:cat :any [:maybe :string]] :any]}
  [v src]
  (when (and (var? v) src) (alter-meta! v assoc :source src)))

(defn- eval-code
  "Read the request's code once, form by form, and evaluate each with (:repl/eval-fn req)
  under seon.intr/run, in the request's \"ns\", else the agent's own. Answers each form,
  with its exact text (:repl/src), the ; comments its writer put before it (:lead, which a
  definition keeps as its leading comment) and what it wrote to *out* and *err*; the first
  error or an interrupt stops the request (one pending since its asker cleared it included:
  agent-step, the turn loop), as load-string stops; then done."
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
    (loop [ns (or (some-> (get req "ns") symbol find-ns) (:ns @agent/state)) prev 0]
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
                end (+ (@starts (dec (.getLineNumber r))) (dec (.getColumnNumber r)))
                lead (reader/lead code prev end)
                src (form-src code @starts form end)
                ;; the form as the wire carries it, whole: its own text, else its EDN (a symbol, a number)
                common (cond-> {:id n :out (not-empty (str out)) :err-out (not-empty (str err)) :form (or src (sedn/encode form)) :repl/form form
                                :repl/src src}
                         lead (assoc :lead lead))]
            (swap! agent/state assoc :repl-vars (if (= tag :ret) {:v1 v :v2 v1 :v3 v2 :e e1} {:v1 v1 :v2 v2 :v3 v3 :e v}))
            (when (= tag :ret) (keep-source! v (:repl/src common)))
            (if (= tag :ret)
              (do (reply (merge common {:repl/value v :tag :ret}))
                  (recur ns' end))
              (do (reply (merge common {:repl/ex v :tag :err :err-text (str err (nrepl/err->msg v {:outer outer}))
                                        "status" (if (:interrupted (ex-data v)) ["interrupted"] ["eval-error"])}))
                  (swap! agent/state assoc :ns ns)
                  (reply {"status" ["done"]})))))))))

;; ---- middleware -------------------------------------------------------------------

(defn- around-reply {:malli/schema [:=> [:cat :seon.wire/msg :seon.host/fn] :seon.wire/msg]}
  [req f] (assoc req :reply (let [r (:reply req)] (fn [m] (r (f m))))))

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
  "An eval error also as the one error map (seon.agent/error-map, the answer's :err: its
  message and fix as data, which the response carries as :ex), and
  noted in this agent's error fold (seon.error/note): its next commit carries the fold to
  root, which counts each signature across agents."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m] (if-let [e (:repl/ex m)]
                   (let [em (cond-> (agent/error-map e) (:repl/phase m) (assoc :phase :read))
                         em (if (= :seon.agent/eval-error (:seon.error/type em)) (merge em (located e m)) em)]
                     (swap! agent/state update :errors err/note em :eval (:form m) (System/currentTimeMillis))
                     (assoc m :err em))
                   m))))))

(defn wrap-cost
  "Per-form wall ms, allocated bytes, engine ticks (the work it did, the exact measure:
  seon.profile/measure!) and the
  form's costliest contracted functions (each with its exact calls and estimated ms:
  seon.profile), measured around the per-form eval fn. They are recorded once, when the
  form ran, beside the answer's value: the one value the record (record-tx) stores and the
  reply shows, so it is shown from that fact and never measured again (the provider's prompt
  cache holds while the context repeats it byte for byte). The one measurement of a call,
  seon.profile/measure!, takes them, so a form that threw is counted. Reading them costs the
  functions the form called, not the program's size."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (let [last (volatile! nil)
          ef (:repl/eval-fn req eval)]
      (h (-> req
             (assoc :repl/eval-fn (fn [form]
                                    (profile/measure! #(ef form)
                                                      (fn [{:keys [ticks bytes wall-ns]}]
                                                        (vreset! last {:ms (/ (quot wall-ns 1000) 1000.0) :alloc-bytes bytes :ticks ticks
                                                                       :fns (profile/top (profile/touched) (cfg/value :seon.config.agent/form-top-fns))})))))
             (around-reply (fn [m]
                             (if-let [{:keys [fns] :as c} (when (or (contains? m :repl/value) (contains? m :repl/ex)) @last)]
                               (do (vreset! last nil)
                                   (cond-> (merge m (dissoc c :fns))
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
                              (program/account (assoc l :src (:repl/src m) :lead (:lead m) :form (:repl/form m) :ran? (contains? m :repl/value))))]
                   (when l (vreset! last nil))
                   (if acct
                     (let [tx (when contract/record? (program/record-tx (agent/db) acct))
                           ;; what this form's own transaction changes of an existing var's contract or
                           ;; docstring, read before it is applied (a form that defines nothing reads nothing)
                           redefined (when tx (program/meaning-changes (agent/db) tx))]
                       (when tx (agent/transact! tx))
                       (cond-> (assoc m :recorded (vec (concat (map :var (:defs acct)) (when (:ns-form? acct) [(:ns acct)])))
                                      :repl/defined (mapv :var (:defs acct)))
                         redefined (assoc :redefined redefined)))
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

(defn- with-branch-schemas
  "f called with malli's dynamic registry bound to this branch's population (branch-registry),
  read now: what a form compiles against, and the renders its values' schemas name
  (seon.contract/properties reads it)."
  {:malli/schema [:=> [:cat :seon.host/fn] :any]}
  [f] (binding [mr/*registry* (branch-registry)] (f)))

(defn wrap-registry
  "Each form evaluates with its branch's schemas bound (with-branch-schemas), read before the
  form: a schema the previous form registered is in effect for this one."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (let [ef (:repl/eval-fn req eval)]
      (h (assoc req :repl/eval-fn (fn [form] (with-branch-schemas #(ef form))))))))

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
  "Outermost first. Answers pass innermost first: record, cost, instrument, caught. The
  eval fn is the registry's binding around record's around cost's around eval, so an eval's
  cost is the eval's (with the compiler's tap in it, as part of compiling); keying the
  branch's registry, read before each form, is not the form's (0.7 ms measured)."
  [wrap-caught wrap-instrument wrap-cost wrap-record wrap-registry])

(defn handler
  "eval-code with the middleware composed over it, outermost first."
  {:malli/schema [:=> [:cat] :seon.host/fn]}
  [] (reduce (fn [h m] (m h)) eval-code (reverse middleware)))

(def ^:private the-handler (delay (handler)))

;; ---- a request's response and its record: one per eval request (a tool call, a typed form) ----

(defn- eval-n!
  "The next eval's number: one more than the evals this agent's branch records, counted once a
  process (so a handle is unique across restarts of the branch's agents), then this process's
  count; a process holding no branch (root) counts from 0."
  {:malli/schema [:=> [:cat] pos-int?]}
  []
  (let [from (when-not (:eval-n @agent/state)
               (if-let [db (:db @agent/state)] (count (d/datoms db :aevt :seon.eval/id)) 0))]
    (:eval-n (swap! agent/state update :eval-n #(inc (or % from))))))

(defn response
  "A request's answers (in order) as its one response (:seon.eval/response): its handle, each
  form's value in order (:values, as nREPL answers a value per form, when more than one form
  answered: the values before an error stay), the last form's value (:value, as *1 holds it),
  what its forms wrote to *out* and *err*, the error that stopped it (:ex, the one error map),
  what its forms changed of an existing var's contract or docstring (:redefined, wrap-record's,
  seon.program/meaning-changes), and its cost summed over its forms (ms, ticks, bytes allocated);
  each key only when present (DECISIONS Dogfood rulings; a change to a schema
  is an explicit fact the eval shows)."
  {:malli/schema [:=> [:cat [:sequential :seon.wire/msg] :qualified-symbol] :seon.eval/response]}
  [answers handle]
  (let [fs (filterv :tag answers)
        lst (peek fs)
        vs (into [] (comp (filter #(contains? % :repl/value)) (map :repl/value)) fs)
        joined (fn [k] (not-empty (apply str (keep k fs))))
        sum (fn [k] (when (some k fs) (reduce + (keep k fs))))]
    (cond-> {:handle handle}
      (contains? lst :repl/value) (assoc :value (:repl/value lst))
      (and (> (count fs) 1) (seq vs)) (assoc :values vs)
      (some :redefined fs) (assoc :redefined (into [] (mapcat :redefined) fs))
      (joined :out) (assoc :out (joined :out))
      (joined :err-out) (assoc :err (joined :err-out))
      (= :err (:tag lst)) (assoc :ex (or (:err lst) {:seon.error/message (str (:err-text lst))}))
      (sum :ms) (assoc :ms (/ (Math/round (* 1000.0 (sum :ms))) 1000.0))
      (sum :ticks) (assoc :ticks (sum :ticks))
      (sum :alloc-bytes) (assoc :bytes (sum :alloc-bytes)))))

(defn- by
  "Who evaluated, as the record names it (:seon.eval/by): the user (the page), an outside
  session (root's server), a context read, the screen (the forms run before each model
  request, seon.turn), else the agent itself (its model's turn)."
  {:malli/schema [:=> [:cat [:maybe :keyword]] :string]}
  [origin]
  (case origin :ui "user" :outside "outside" :context "context" :screen "screen" (name (or (:id @agent/state) :root))))

(defn record-tx
  "The recording transaction of one eval: its entity, on the agent's, who evaluated it
  (:seon.eval/by), its details in one EDN attribute (the lean record: nothing queries them
  apart): `record-details`; and the agent's answered t. Who is the one upsert, by its identity: an
  agent's key ({:seon.agent/key k}, seon.task/agent-key) or root's ({:seon.root/id :root})."
  {:malli/schema [:=> [:cat :map :string :string :map [:maybe :int]] :seon.db/tx-data]}
  [who id by details t-basis]
  [{:seon.eval/id id :seon.eval/agent "agent" :seon.eval/by by :seon.eval/record details}
   (cond-> (assoc who :db/id "agent") t-basis (assoc :seon.agent/answered-t t-basis))])

(defn record-details
  "What an eval's record holds (:seon.eval/record): its code, its response whole, its value
  included when it is plain data (seon.db/plain?: stored as data, once under its content key when
  large), else the response as it printed then (:printed) and the value's type (:type), since only
  data is stored; never a text made for a reader (each prints it as it shows it: seon.print/
  record-text), so a record is the same whatever came before it. Then the prose beside it and what that prose cites (its
  references on db, parsed here once by the one reference parser, seon.ref/references, so no
  render parses it again; read on db, the branch as the eval began, so a name the eval itself
  defines is not yet one; none without a db: root's page before main is read), its value in one line, or its error's message (:line, the one printer
  under the line budget: what a collapsed eval shows), the provider's call id, its reasoning, its run, its costliest
  functions and what it recorded."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :string :map :map [:maybe :string]] :seon.eval/record]}
  [db code r req run]
  (let [cites (when db (some->> (:prose req) (ref/references db)))
        resp (dissoc r :text :recorded :fns :record)
        ;; a value that names its basis (@ctx: the branch's record at t, seon.ctx/build) is
        ;; recorded as that basis and its schema: the page renders it from what exists, and a
        ;; copy of the session's record is never stored at each @ctx
        basis (when (and (contains? resp :value) (not (contains? resp :values))) (:seon.render/basis (meta (:value resp))))
        data? (and (nil? basis) (or (not (contains? resp :value)) (pdb/plain? (:value resp))) (every? pdb/plain? (:values resp)))]
    (cond-> (if data?
              {:code code :response resp}
              (cond-> {:code code :response (dissoc resp :value :values) :printed (print/response-text resp) :type (str (type (:value resp)))}
                basis (assoc :schema (:seon.render/schema (meta (:value resp))) :basis basis)))
      (or (contains? r :value) (:ex r))
      (assoc :line (print/line (cond (:ex r) (or (get-in r [:ex :seon.error/message]) (:ex r)) (contains? r :values) (:values r) (nil? (:value r)) "nil" :else (:value r)) (cfg/value :seon.config.render/line-budget) nil))
      (:prose req) (assoc :prose (:prose req))
      (seq cites) (assoc :cites cites)
      (:call-id req) (assoc :call-id (:call-id req) :call (:call req 0))
      (:reasoning req) (assoc :reasoning (:reasoning req))
      (:content req) (assoc :content (:content req))
      (:call-prose req) (assoc :call-prose (:call-prose req))
      run (assoc :run run)
      (seq (:fns r)) (assoc :fns (:fns r))
      (seq (:recorded r)) (assoc :recorded (:recorded r)))))

(defn- record!
  "Record response r of code on this branch through the agent's write path (an agent's
  only), with the defs its forms recorded and the effects asked during it (seon.agent/fx-record!).
  Answers r, with the id of its record (:record) when recorded."
  {:malli/schema [:=> [:cat :map :string :map] :map]}
  [r code req]
  (let [defs (into [] (mapcat val) (:defs @agent/state))
        id (name (:handle r))]
    (swap! agent/state dissoc :defs)
    (if (and contract/record? (= :agent (:mode @agent/state)))
      (do (agent/transact! (into defs (record-tx {:seon.agent/key (:key @agent/state) :seon.agent/id (:id @agent/state)} id (by (:origin req))
                                                 (record-details (agent/db) code r req (get-in @agent/state [:turn :run]))
                                                 (:max-tx (agent/db)))))
          (assoc r :record id))
      r)))

(defn eval-string
  "Evaluate code through the one REPL path (the handler and its middleware) and answer its one
  response (`response`) with the text its caller reads (:text, seon.print/response-text) and what its forms
  recorded, its handle result/e<n> bound to it (the live value inside), and recorded (an
  agent's, on its branch). From inside an agent, the turn loop's calls; req adds to the request
  (root's: seon.server's namespace, origin, budget and eval fn; root's requests to an agent:
  agent-step's; a model's call: its :prose and :call-id). The request's entrant boundary: a
  defect past a form's own (a middleware's, the printer's) ends the request with its error as
  the response's :ex. Its cost (:ms :ticks :bytes, seon.repl/wrap-cost) is taken once when the
  forms ran, so what the model reads of it never changes in a later turn."
  {:malli/schema [:function [:=> [:cat :string] :seon.eval/response] [:=> [:cat :string :map] :seon.eval/response]]}
  ([code] (eval-string code {:origin :model}))
  ([code req]
   (let [answers (volatile! [])
         handle (symbol "result" (str "e" (eval-n!)))
         ;; the writes its forms make and its record name it (seon.agent/transact!'s tx-meta); an
         ;; eval inside it (the screen's, a context read's) names its own, then this one's again
         outer (:eval @agent/state)]
     (swap! agent/state assoc :eval (name handle))
     (try (let [answered (fn [] (let [r (response @answers handle)] [r (print/response-text r (:seen @agent/state))]))
                ;; printing is inside the boundary: a value that throws as it is realized is the error
                [r text] (try (@the-handler (assoc req "code" code :reply #(vswap! answers conj %)))
                              (answered)
                              (catch Throwable e
                                (let [em (agent/error-map e)]
                                  (vswap! answers conj {:tag :err :err em :err-text (nrepl/err->msg e)})
                                  (answered))))
                recorded (into [] (mapcat :recorded) @answers)]
            (swap! agent/state assoc-in [:results (keyword (name handle))] r)
            ;; the screen's responses are replaced each request: no later response reads them
            (when-not (= :screen (:origin req)) (swap! agent/state update :seen print/seen r))
            ;; the handle a printed cut reads through, as the REPL's *1 is read
            (intern (create-ns 'result) (symbol (name handle)) r)
            (record! (cond-> (assoc r :text text) (seq recorded) (assoc :recorded recorded) (some :fns @answers) (assoc :fns (into [] (mapcat :fns) @answers)))
                     code req))
          (finally (swap! agent/state assoc :eval outer))))))

(defn- draw-part
  "Part p's html through memo m (seon.render/drawn), or its error's render when its render throws
  (an agent's own render, unmerged: the boundary that answers it, so the page always has the part
  and the agent goes on). Answers [m' {:key :html :inline}]."
  {:malli/schema [:=> [:cat :map :seon.render/part] [:tuple :map :seon.render/part-html]]}
  [m p]
  (try (let [[m f] (render/drawn m p)] [m (render/part-html p f)])
       (catch Throwable e (let [err (agent/error-map e)]
                            [m {:key (:key p) :html (h/html (render/html err {})) :inline (h/html (render/html err {:as :inline}))}]))))

(defn parts
  "This agent's page as its parts' html, drawn here under its branch's schemas (root's render proc
  asks, its {:op :render}: seon.render.proc/ask-parts), each through the part memo this process holds (:drawn):
  {:t its ctx's t :parts [{:key :html :inline}]}, the parts in their order (seon.render/view).
  Records nothing."
  {:malli/schema [:=> [:cat] [:map [:t :int] [:parts [:vector :seon.render/part-html]]]]}
  []
  (with-branch-schemas
    ;; the held record: @ctx with each member's first transaction, what the conversation is ordered by
    #(let [c (ctx/record)
           [memo out] (reduce (fn [[m acc] p] (let [[m d] (draw-part m p)] [m (conj acc d)]))
                              [(:drawn @agent/state {}) []] (render/view c))]
       ;; the memo holds the parts this page has now, none it dropped
       (swap! agent/state assoc :drawn (select-keys memo (map :key out)))
       {:t (get-in c [:tree :t] 0) :parts out})))

;; ---- the process: agent-step under run-loop ----------------------------------------

(defn agent-step
  "The agent as a step-fn, run on its one thread by seon.step/run-loop: requests in on
  :sock (an eval as {:op :eval :id :forms}, its page's parts to draw as {:op :render :id},
  Seon's ops as {:op k ...}), replies out on
  :sock; :pause, :resume, :stop and :ping are taken by the loop. Its REPL state is the
  process's one state map (seon.agent/state), which the code it evaluates reads, so the
  step's own state is empty."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:sock "root's requests"} :outs {:sock "replies"} :workload :io
       :ping-map-fn (fn [_] {:evals (:n @agent/state) :pending (count (:pending @agent/state))})})
  ([_] {})
  ([st _] st)
  ([st _ m]
   (cond
     ;; root's render proc asks for this agent's page: its parts, drawn here under its branch's
     ;; schemas by the renders it defines, answered as HTML, and nothing recorded
     (= :render (:op m))
     [st {:sock [{:op :reply :id (:id m) :response (parts)}]}]
     (= :eval (:op m))
     (do
       ;; an interrupt left from before this request is not its; then root hears that it began
       ;; (its proc's :started event), what an interrupt or a kill meant for a running eval
       ;; waits on, never a sleep: one arriving from here on stops it
       (intr/clear-pending!)
       (agent/send! {:op :started :id (:id m)})
       [st {:sock [{:op :reply :id (:id m) :response (dissoc (eval-string (:forms m) (select-keys m [:ticks :origin])) :value :values)
                    :t (:max-tx (agent/db))}]}])
     :else [st (when-let [ms (agent/handle! m)] {:sock ms})])))

(defn serve!
  "The agent's loop: one request at a time, in arrival order. Never returns. The agent
  process's entrant boundary: a defect no request's own boundary caught (the agent may
  redefine a function its loop calls) ends the process; its cause is the run's end on
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
  ;; the agent's whole body is one fiber on its one thread (jolt.host/main-carrier-run!): its
  ;; waits park, its go blocks, flows and remote channels run beneath it, and it starts no
  ;; thread, so it still forks its test children safely (design agent-loop section 8a). macOS
  ;; only: the loop wakes on root's signals through kqueue (jolt.io-poller/wake-on-signals!),
  ;; which Linux lacks, so there the agent runs on its thread as before
  ((if (lib/macos?) jolt.host/main-carrier-run! #(%))
    (fn []
      (agent/init! fd m db0 serve!)
      (agent/hello! t-req-ns (cond-> {:interrupt-ok (:interrupt-ok @agent/state)}
                                (seq (:load-errors @agent/state)) (assoc :load-errors (:load-errors @agent/state))
                                (:misaligned @agent/state) (assoc :misaligned (:misaligned @agent/state))
                                (:unwatched @agent/state) (assoc :unwatched (:unwatched @agent/state))))
      (serve!))))
