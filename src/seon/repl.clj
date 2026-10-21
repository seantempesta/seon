(ns seon.repl
  "An agent's REPL speaking nREPL's message model on its one thread (lane F2, after lane
  R2's r2.repl; the owner chose nREPL's model for agents).

  The agent's process runs `agent-step` (the step-fn contract) under seon.step/run-loop.
  A REPL request (`eval`, `load-file`, `describe`, `clone`, `close`, `ls-sessions`,
  `interrupt`) is handled on this thread by `handler`: a base handler with middleware
  composed over it, each `(fn [handler] (fn [request] ...))`, as in nREPL. A request
  carries :reply, a fn of one response map (id and session added). Eval reads the code
  once, form by form, and answers each form as JVM nREPL does: `out`/`err` as printed,
  then `value` + `ns`, or `err` + `ex` + eval-error; then one `done`.

  Seon's additions are middleware over nREPL's own seams (a plain stack: G1's contract
  admission and arming plug in as one more `wrap-…`):
    wrap-print    the value through the one value printer (seon.print)
    wrap-caught   the error also as the one error map
    wrap-cost     per-form µs, allocated bytes and costliest functions, around the per-form eval fn
    wrap-record   each form's compiler account (its definitions, references) becomes facts on
                  this branch as it runs (seon.program, transact!)
  In-process values ride a response under keyword keys (:repl/value, :repl/ex) until a
  middleware turns them into answer keys. The requests and answers are in-process maps:
  root's request arrives as a value ({:op :eval :id :forms ...}, a frame on the agent's
  socket), and the request's answers go back folded, as one {:op :reply :items ...}.

  Interrupt is out of band: root sends SIGUSR1 (seon.intr), and an `interrupt` message
  is answered once the eval has stopped. Seon's platform ops (tests, stats, clone,
  messages, writes) are {:op k} values, handled by `agent-step`."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn- defchecked]] [seon.error :as err] [jolt.nrepl :as nrepl] [seon.intr :as intr] [seon.agent :as agent] [seon.contract :as contract] [clojure.edn] [my.db] [my.agent]
            [seon.program :as program] [jolt.analyzer] [seon.lib :as lib] [seon.print :as print] [seon.profile :as profile] [seon.sock :as sock] [seon.step :as step]
            [jolt.ffi :as ffi]))

(defchecked ^:private chunk-writer [:=> [:cat :seon.host/fn] :seon.host/writer] @#'jolt.nrepl/chunk-writer)
(defchecked ^:private ex-class-strings [:=> [:cat :seon.host/throwable [:maybe :keyword]] [:tuple :string [:maybe :string]]] @#'jolt.nrepl/ex-class-strings)
(defchecked ^:private outer-frames [:=> [:cat] :any] @#'jolt.nrepl/outer-frames)

(defn- form-text {:malli/schema [:=> [:cat :any] :string]}
  [form] (print/text form (:form print/budgets)))

(defn- session-ns
  "The request's namespace: its \"ns\", else its session's, else the agent's own."
  {:malli/schema [:=> [:cat :seon.wire/msg] :seon.host/namespace]}
  [req]
  (let [s @agent/state]
    (or (some-> (get req "ns") symbol find-ns)
        (some-> (get-in s [:sessions (get req "session") :ns]) find-ns)
        (:ns s))))

(defn- line-starts
  "The offset in code where each line starts."
  {:malli/schema [:=> [:cat :string] [:vector :int]]}
  [^String code]
  (loop [acc [0] i 0]
    (let [j (.indexOf code "\n" (int i))]
      (if (neg? j) acc (recur (conj acc (inc j)) (inc j))))))

(defn- form-src
  "The exact text of form in code, from its reader position (:line, :column) to where the
  reader stopped after it (end-line, end-col; all 1-based), or nil for a form that carries
  no position (a symbol, a number)."
  {:malli/schema [:=> [:cat :string [:vector :int] :any :int :int] [:maybe :string]]}
  [code starts form end-line end-col]
  (let [{:keys [line column]} (meta form)]
    (when (and line column)
      (subs code (+ (starts (dec line)) (dec column)) (+ (starts (dec end-line)) (dec end-col))))))

(defn- eval-code
  "Read code once, form by form, and evaluate each with (:repl/eval-fn req) under
  seon.intr/run. Answers each form, with its exact text (:repl/src); an interrupt stops the
  request; then done."
  {:malli/schema [:=> [:cat :seon.wire/msg :string] :any]}
  [{:keys [reply] :as req} code]
  (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. code))
        starts (delay (line-starts code))
        eval-fn (:repl/eval-fn req eval)
        timeout (get req "seon/timeout-ms")
        sid (get req "session")]
    (loop [ns (session-ns req)]
      (let [form (try (binding [*ns* ns] (read {:eof ::eof} r)) (catch Throwable e e))]
        (cond
          (= ::eof form)
          (do (if sid
                (swap! agent/state assoc-in [:sessions sid :ns] (ns-name ns))
                (swap! agent/state assoc :ns ns))
              (reply {"status" ["done"]}))

          (instance? Throwable form)
          (let [n (:n (swap! agent/state update :n inc))
                [ex root] (ex-class-strings form :read-source)]
            (reply {"err" (nrepl/err->msg form {:phase :read-source})})
            (reply {:repl/ex form "ex" ex "root-ex" root "status" ["eval-error"] "seon/phase" "read"
                    "seon/ordinal" (dec n)})
            (reply {"status" ["done"]}))

          :else
          (let [n (dec (:n (swap! agent/state update :n inc)))
                out (chunk-writer #(reply {"out" %}))
                err (chunk-writer #(reply {"err" %}))
                [tag v ns' outer] (try (binding [*ns* ns *out* out *err* err]
                                         (let [v (intr/run timeout #(eval-fn form))] [:ret v *ns*]))
                                       (catch Throwable e [:err e ns (outer-frames)])
                                       (finally (.flush out) (.flush err)))
                common {"seon/ordinal" n "seon/form" (form-text form) :repl/form form
                        :repl/src (form-src code @starts form (.getLineNumber r) (.getColumnNumber r))}]
            (swap! agent/state update :nss conj (ns-name ns'))
            (if (= tag :ret)
              (let [h (symbol "result" (str "h" n))]
                (swap! agent/state assoc-in [:results (keyword (str "h" n))] v)
                (intern (create-ns 'result) (symbol (name h)) v)
                (reply (merge common {:repl/value v "ns" (str (ns-name ns')) "seon/handle" (str "h" n)}))
                (recur ns'))
              (let [[ex root] (ex-class-strings v nil)
                    stopped? (:interrupted (ex-data v))]
                (reply {"err" (nrepl/err->msg v {:outer outer})})
                (reply (merge common {:repl/ex v "ex" ex "root-ex" root
                                      "status" (if stopped? ["interrupted"] ["eval-error"])}))
                (if stopped?
                  (do (swap! agent/state assoc :interrupted (get req "id"))
                      (reply {"status" ["done"]}))
                  (recur ns))))))))))

(defn- describe {:malli/schema [:=> [:cat :any] :seon.wire/msg]}
  [_]
  {"status" ["done"]
   "versions" {"jolt" {"version-string" (jolt.host/jolt-version)} "seon-agent" {"version-string" "f2"}}
   "ops" (zipmap ["describe" "clone" "close" "ls-sessions" "eval" "load-file" "interrupt"] (repeat {}))})

(defn base-handler
  "nREPL's ops, answered on this thread."
  {:malli/schema [:=> [:cat :seon.wire/msg] :any]}
  [{:keys [reply] :as req}]
  (case (get req "op")
    "describe" (reply (describe req))
    "clone" (let [id (nrepl/new-session)
                  from (get-in @agent/state [:sessions (get req "session") :ns] (ns-name (:ns @agent/state)))]
              (swap! agent/state assoc-in [:sessions id] {:ns from})
              (reply {"new-session" id "status" ["done"]}))
    "close" (do (swap! agent/state update :sessions dissoc (get req "session"))
                (reply {"status" ["session-closed" "done"]}))
    "ls-sessions" (reply {"sessions" (vec (keys (:sessions @agent/state))) "status" ["done"]})
    "eval" (eval-code req (get req "code"))
    "load-file" (eval-code req (get req "file"))
    ;; root signalled this process before sending this: the eval it meant has stopped
    "interrupt" (let [hit (:interrupted @agent/state)]
                  (swap! agent/state dissoc :interrupted)
                  (intr/clear-pending!)
                  (reply {"status" (if hit ["done"] ["session-idle" "done"])}))
    (reply {"status" ["done" "unknown-op"]})))

;; ---- middleware -------------------------------------------------------------------

(defn- around-reply {:malli/schema [:=> [:cat :seon.wire/msg :seon.host/fn] :seon.wire/msg]}
  [req f] (assoc req :reply (let [r (:reply req)] (fn [m] (r (f m))))))

(defn wrap-print
  "The value through the one value printer (seon.print), its cuts reading the rest through
  the value's result handle: `value`, with the printer's tokens, truncation and elision
  count. A value whose first part does not fit the budget answers the printer's refusal
  message, with the refusal beside it."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m]
           (if (contains? m :repl/value)
             (let [pv (print/print-value (:repl/value m) {:handle (some->> (get m "seon/handle") (symbol "result"))})]
               (if-let [text (:text pv)]
                 (cond-> (assoc m "value" text "seon/tokens" (:tokens pv))
                   (:truncated? pv) (assoc "seon/truncated" 1)
                   (pos? (:elisions pv)) (assoc "seon/elisions" (:elisions pv)))
                 (assoc m "value" (:seon.error/message pv) "seon/printer-error" (pr-str pv))))
             m))))))

(defn wrap-caught
  "An eval error also as the one error map (seon.agent/error-map, EDN on the wire), and
  noted in this agent's error fold (seon.error/note): its next commit carries the fold to
  root, which counts each signature across agents."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m] (if-let [e (:repl/ex m)]
                   (let [em (cond-> (agent/error-map e) (get m "seon/phase") (assoc :phase :read))]
                     (swap! agent/state update :errors err/note em (get m "seon/form") (System/currentTimeMillis))
                     (assoc m "seon/error" (pr-str em)))
                   m))))))

(def top-fns
  "How many of a form's costliest contracted functions its record keeps (Seon's
  :seon.config.test.cost/top)."
  10)

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
                                                      (vreset! last [(quot (- t1 t0) 1000) (- a1 a0) (profile/top (profile/touched) top-fns)])))))))
             (around-reply (fn [m]
                             (if-let [[us a fns] (when (or (contains? m :repl/value) (contains? m :repl/ex)) @last)]
                               (do (vreset! last nil)
                                   (cond-> (assoc m "seon/us" us "seon/alloc-bytes" a)
                                     (seq fns) (assoc "seon/fns" (pr-str (mapv (fn [c] (update c :ms #(/ (Math/round (* % 1000.0)) 1000.0))) fns)))))
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
  (:repl/defined, for admission), and what it recorded (\"seon/recorded\")."
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
                              (program/account (assoc l :src (:repl/src m) :ran? (contains? m :repl/value))))]
                   (when l (vreset! last nil))
                   (if acct
                     (do (when contract/record? (agent/transact! (program/record-tx (agent/db) acct)))
                         (assoc m "seon/recorded" (pr-str (vec (concat (map :var (:defs acct))
                                                                       (when (:ns-form? acct) [(:ns acct)]))))
                                :repl/defined (mapv :var (:defs acct))))
                     (cond-> m l (assoc :repl/defined (program/set-vars l))))))))))))

(defn- admit
  "Admit a definition by its contract (G1's proto.agent/admit!, Seon's rule): a function var
  with :malli/schema is armed and leaves the agent's uncontracted set; one without joins
  it, and the response carries the warning naming every uncontracted definition so far.
  Answers the response's additions (wire keys)."
  {:malli/schema [:=> [:cat :seon.host/var] [:maybe :seon.wire/msg]]}
  [v]
  (when (contract/function-var? v)
    (let [sym (symbol v)]
      (if (contract/contract v)
        (let [r (contract/arm! v)]
          (swap! agent/state update :uncontracted (fnil disj #{}) sym)
          (if (contract/error? r)
            {"seon/contract" "invalid" "seon/contract-error" (pr-str r)}
            {"seon/contract" (name r)}))
        (let [u (:uncontracted (swap! agent/state update :uncontracted (fnil conj #{}) sym))]
          {"seon/warnings" (pr-str [{:seon.repl/finding :uncontracted-definition
                                     :seon.repl/fix (str "Declare a contract: (defn " (name sym) " {:malli/schema [:=> [:cat ...] ...]} [...] ...). "
                                                         "A branch carrying an uncontracted function is refused at merge.")
                                     :uncontracted (vec (sort-by str u)) :count (count u)}])})))))

(defn wrap-admit
  "Each var a form defined (wrap-record's :repl/defined) is admitted by its contract
  (admit): armed, or a growing warning."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m]
           (reduce (fn [m s] (if-let [v (find-var s)] (merge m (admit v)) m)) m (:repl/defined m)))))))

(def middleware
  "Outermost first. Answers pass innermost first: record, cost, admit, caught, print. The
  eval fn is record's around cost's around eval, so an eval's cost is the eval's (with the
  compiler's tap in it, as part of compiling)."
  [wrap-print wrap-caught wrap-admit wrap-cost wrap-record])

(defn handler
  {:malli/schema [:function [:=> [:cat] :seon.host/fn] [:=> [:cat [:sequential :seon.host/fn]] :seon.host/fn]]}
  ([] (handler middleware))
  ([mw] (reduce (fn [h m] (m h)) base-handler (reverse mw))))

(def ^:private the-handler (delay (handler)))

;; ---- reply items and their recording (root folds the wire; the agent folds in-process) ----

(defn- fold-item
  "One form's nREPL answer (with the out/err text that came before it) as a reply item."
  {:malli/schema [:=> [:cat :seon.wire/msg :map :seon.host/fn] :seon.agent/item]}
  [m out text]
  (let [st (set (get m "status"))
        err? (or (st "eval-error") (st "interrupted"))
        em (some-> (get m "seon/error") text clojure.edn/read-string)
        t #(some-> (get m %) text)]
    (cond-> {:id (get m "seon/ordinal") :tag (if err? :err :ret) :form (t "seon/form")
             :ms (some-> (get m "seon/us") (/ 1000.0)) :alloc-bytes (get m "seon/alloc-bytes")}
      (not err?) (assoc :printed (t "value") :ret (t "value") :handle (some-> (get m "seon/handle") keyword))
      err? (assoc :printed (:seon.error/message em) :err em :err-text (:err out))
      (get m "seon/tokens") (assoc :tokens (get m "seon/tokens"))
      (get m "seon/truncated") (assoc :truncated? true)
      (get m "seon/elisions") (assoc :elisions (get m "seon/elisions"))
      (get m "seon/recorded") (assoc :recorded (symbol (t "seon/recorded")))
      (get m "seon/record-error") (assoc :record-error (t "seon/record-error"))
      (get m "seon/contract") (assoc :contract (keyword (t "seon/contract")))
      (get m "seon/contract-error") (assoc :contract-error (clojure.edn/read-string (t "seon/contract-error")))
      (get m "seon/warnings") (assoc :warnings (clojure.edn/read-string (t "seon/warnings")))
      (get m "seon/fns") (assoc :fns (clojure.edn/read-string (t "seon/fns")))
      (seq (:out out)) (assoc :out (:out out)))))

(defn fold
  "The answers of one eval request (in order) -> its reply items. `out`/`err` text goes
  onto the next form's item (the form that printed it). text decodes a text field
  (identity: the answers are in-process)."
  {:malli/schema [:=> [:cat [:sequential :seon.wire/msg] :seon.host/fn] :seon.agent/items]}
  [answers text]
  (loop [[m & more :as ms] answers acc {:items [] :out {}}]
    (if-not (seq ms)
      (:items acc)
      (let [st (set (get m "status"))]
        (cond
          (or (contains? m "value") (st "eval-error") (st "interrupted"))
          (recur more (-> acc (update :items conj (fold-item m (:out acc) text)) (assoc :out {})))
          (contains? m "out") (recur more (update-in acc [:out :out] str (text (get m "out"))))
          (contains? m "err") (recur more (update-in acc [:out :err] str (text (get m "err"))))
          :else (recur more acc))))))

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
                :seon.cluster.eval/record (binding [*print-length* nil *print-level* nil *print-meta* false]
                                            (pr-str (cond-> (select-keys it [:tag :form :out :ms :alloc-bytes :fns :origin])
                                                      (= :ret (:tag it)) (assoc :shown (:printed it))
                                                      (= :err (:tag it)) (assoc :error (or (:err it) (:printed it))))))}))
        (cond-> {:db/id "agent" :seon.agent/id id} t-basis (assoc :seon.agent/answered-t t-basis))))

(defn- record!
  "Record items (with the defs their forms recorded) on this branch through the agent's
  write path; origin (:outside, :ui) says who asked, when not the agent itself. Answers items.
  When the agent's entity ids are spent (:seon.db/lease-spent) the record cannot be written
  on its branch: root is told ({:op :unrecorded}) and records it as a problem on main."
  {:malli/schema [:=> [:cat :seon.agent/items [:maybe :keyword]] :seon.agent/items]}
  [items origin]
  ;; the effects asked during the request (seon.agent/fx-record!) join its recording
  (let [defs (into [] (mapcat val) (:defs @agent/state))]
    (swap! agent/state dissoc :defs)
    (when (and contract/record? (= :agent (:mode @agent/state)) (seq items))
      (try
        (agent/transact! (into defs (record-tx (:id @agent/state) (str (name (:id @agent/state)) "-" (lib/c-getpid) "-" (lib/now-ns))
                                               (cond->> items origin (mapv #(assoc % :origin origin))) (:max-tx (agent/db)))))
        (catch clojure.lang.ExceptionInfo e
          (if (= :seon.db/lease-spent (:seon.error/type (ex-data e)))
            (agent/send! {:op :unrecorded :error (ex-data e) :forms (count items)})
            (throw e)))))
    items))

(defn eval-string
  "Evaluate code from inside this agent (the turn loop's forms) through the one REPL path:
  the handler and its middleware, its answers folded into reply items and recorded."
  {:malli/schema [:=> [:cat :string] :seon.agent/items]}
  [code]
  (let [answers (volatile! [])]
    (@the-handler {"op" "eval" "code" code :reply #(vswap! answers conj %)})
    (record! (fold @answers identity) nil)))

;; ---- the process: agent-step under run-loop ----------------------------------------

(defn- reply-fn
  "The request's answers are kept and folded at its done into one reply value for root
  ({:op :reply :id :items :t :mem-mb}); an eval's items are recorded first (the agent owns
  its branch's writes)."
  {:malli/schema [:=> [:cat :seon.wire/msg] :seon.host/fn]}
  [req]
  (let [answers (volatile! [])]
    (fn [m]
      (vswap! answers conj m)
      (when (some #{"done"} (get m "status"))
        ;; who asked travels with the items, to root's view as to the record
        (let [origin (some-> (get req "seon/origin") keyword)
              items (cond->> (fold @answers identity) origin (mapv #(assoc % :origin origin)))]
          (when (= "eval" (get req "op")) (record! items nil))
          (agent/send! {:op :reply :id (:id req) :items items :t (:max-tx (agent/db)) :mem-mb (agent/mem-mb)}))))))

(defn- eval-request
  "Root's eval request as the nREPL request the handler takes."
  {:malli/schema [:=> [:cat :seon.wire/seon-msg] :seon.wire/msg]}
  [m]
  (let [req (cond-> {"op" "eval" "id" (str (:id m)) "code" (:forms m) :id (:id m)}
              (:timeout-ms m) (assoc "seon/timeout-ms" (:timeout-ms m))
              (:origin m) (assoc "seon/origin" (name (:origin m))))]
    (assoc req :reply (reply-fn req))))

(defn agent-step
  "The agent as a step-fn, run on its one thread by seon.step/run-loop: requests in on
  :sock (an eval as {:op :eval :id :forms}, Seon's ops as {:op k ...}), replies out on
  :sock; :pause, :resume, :stop and :ping are taken by the loop. Its REPL state is the
  process's one state map (seon.agent/state), which the code it evaluates reads, so the
  step's own state is empty."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:sock "root's requests"} :outs {:sock "replies"} :workload :io
       :ping-map-fn (fn [_] {:evals (:n @agent/state) :pending (count (:pending @agent/state)) :mem-mb (:mem @agent/state)})})
  ([_] {})
  ([st _] st)
  ([st _ m]
   (if (= :eval (:op m))
     (let [req (eval-request m)]
       ;; the agent's entrant boundary: a defect answers err + done
       (try (@the-handler req)
            (catch Throwable e
              ((:reply req) {"err" (nrepl/err->msg e)})
              ((:reply req) {"status" ["eval-error" "done"]})))
       [st nil])
     [st (when-let [ms (agent/handle! m)] {:sock ms})])))

(defn serve!
  "The agent's loop: one request at a time, in arrival order. Never returns."
  {:malli/schema [:=> [:cat] :any]}
  []
  (step/run-loop #'agent-step {}
                 (fn [] (let [m (agent/next-msg!)] (when-not (= m :dead) [:sock m])))
                 (fn [_ m] (agent/send! m)))
  (lib/c-exit 0))

(defn run!
  "The agent's body, in the child of the fork server; fd is its socket to root. Never
  returns."
  {:malli/schema [:=> [:cat :seon.lib/fd :map :seon.host/db :int] :any]}
  [fd m db0 t-req-ns]
  (agent/init! fd m db0 serve!)
  (agent/hello! t-req-ns (cond-> {:interrupt-ok (:interrupt-ok @agent/state)}
                            (seq (:load-errors @agent/state)) (assoc :load-errors (:load-errors @agent/state))))
  (serve!))
