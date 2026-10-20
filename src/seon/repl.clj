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
    wrap-print    the value through the agent's value printer (seon.agent/print-value)
    wrap-caught   the error also as the one error map
    wrap-cost     per-form µs and allocated bytes, around the per-form eval fn
    wrap-record   a def in a program namespace becomes a fact on this branch (transact!)
  In-process values ride a response under keyword keys (:repl/value, :repl/ex) until a
  middleware turns them into wire keys; seon.wire never encodes keyword keys.

  Interrupt is out of band: root sends SIGUSR1 (seon.intr), and an `interrupt` message
  is answered once the eval has stopped. Seon's platform ops (tests, stats, sync, clone,
  messages, writes) are seon/<op> messages, handled by `agent-step` as before."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn- defchecked]] [jolt.nrepl :as nrepl] [seon.wire :as wire] [seon.intr :as intr] [seon.agent :as agent] [seon.contract :as contract] [clojure.edn] [my.db] [my.agent]
            [seon.program :as program] [seon.lib :as lib] [seon.sock :as sock] [seon.step :as step]
            [jolt.ffi :as ffi]))

(defchecked ^:private chunk-writer [:=> [:cat :seon.host/fn] :seon.host/writer] @#'jolt.nrepl/chunk-writer)
(defchecked ^:private ex-class-strings [:=> [:cat :seon.host/throwable [:maybe :keyword]] [:tuple :string [:maybe :string]]] @#'jolt.nrepl/ex-class-strings)
(defchecked ^:private outer-frames [:=> [:cat] :any] @#'jolt.nrepl/outer-frames)

(defn- form-text {:malli/schema [:=> [:cat :any] :string]}
  [form] (binding [agent/*print-limits* {:length 12 :level 4 :chars 2000}] (agent/builtin-print form)))

(defn- session-ns
  "The request's namespace: its \"ns\", else its session's, else the agent's own."
  {:malli/schema [:=> [:cat :seon.wire/msg] :seon.host/namespace]}
  [req]
  (let [s @agent/state]
    (or (some-> (get req "ns") wire/text-> symbol find-ns)
        (some-> (get-in s [:sessions (get req "session") :ns]) find-ns)
        (:ns s))))

(defn- eval-code
  "Read code once, form by form, and evaluate each with (:repl/eval-fn req) under
  seon.intr/run. Answers each form; an interrupt stops the request; then done."
  {:malli/schema [:=> [:cat :seon.wire/msg :string] :any]}
  [{:keys [reply] :as req} code]
  (let [r (java.io.PushbackReader. (java.io.StringReader. code))
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
                common {"seon/ordinal" n "seon/form" (form-text form) :repl/form form}]
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
    "eval" (eval-code req (wire/text-> (get req "code")))
    "load-file" (eval-code req (wire/text-> (get req "file")))
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
  "The value through the agent's value printer: `value`, with the printer's tokens,
  truncation and elision count."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m]
           (if (contains? m :repl/value)
             (let [pv (agent/print-value (:repl/value m) (some->> (get m "seon/handle") (symbol "result")))]
               (cond-> (assoc m "value" (:printed pv))
                 (:tokens pv) (assoc "seon/tokens" (:tokens pv))
                 (:truncated? pv) (assoc "seon/truncated" 1)
                 (some-> (:elisions pv) pos?) (assoc "seon/elisions" (:elisions pv))
                 (:printer-error pv) (assoc "seon/printer-error" (:printer-error pv))))
             m))))))

(defn wrap-caught
  "An eval error also as the one error map (printed EDN)."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m] (if-let [e (:repl/ex m)]
                   (assoc m "seon/error" (pr-str (cond-> (agent/error-map e) (get m "seon/phase") (assoc :phase :read))))
                   m))))))

(defn wrap-cost
  "Per-form wall µs and allocated bytes, measured around the per-form eval fn."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (let [last (volatile! nil)
          ef (:repl/eval-fn req eval)]
      (h (-> req
             (assoc :repl/eval-fn (fn [form]
                                    (let [a0 (lib/bytes-allocated) t0 (lib/now-ns)]
                                      (try (ef form)
                                           (finally (vreset! last [(quot (- (lib/now-ns) t0) 1000) (- (lib/bytes-allocated) a0)]))))))
             (around-reply (fn [m]
                             (if-let [[us a] (when (or (contains? m :repl/value) (contains? m :repl/ex)) @last)]
                               (do (vreset! last nil) (assoc m "seon/us" us "seon/alloc-bytes" a))
                               m))))))))

(defn wrap-record
  "A def in a program namespace becomes a fact on this branch (agent mode): the record
  goes to the writer as this agent's own transaction."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m]
           (let [v (:repl/value m)]
             (if (and (= :agent (:mode @agent/state)) (var? v) (program/def-form? (:repl/form m))
                      (program/program-ns? (agent/db) (ns-name (:ns (meta v)))))
               (if contract/record?
                 ;; joins the request's recording transaction (the last def of a var wins)
                 (do (swap! agent/state assoc-in [:defs (symbol v)] (program/record-tx (agent/db) v (:repl/form m)))
                     (assoc m "seon/recorded" (str (symbol v))))
                 (do (agent/transact! (program/record-tx (agent/db) v (:repl/form m)))
                     (assoc m "seon/recorded" (str (symbol v)))))
               m)))))))

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
  "A def's var is admitted by its contract (admit): armed, or a growing warning."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m]
           (let [v (:repl/value m)]
             (if (and (var? v) (program/def-form? (:repl/form m)))
               (merge m (admit v))
               m)))))))

(def middleware
  "Outermost first. Answers pass innermost first: cost, record, admit, caught, print."
  [wrap-print wrap-caught wrap-admit wrap-record wrap-cost])

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
      err? (assoc :printed (:message em) :err em :err-text (:err out))
      (get m "seon/tokens") (assoc :tokens (get m "seon/tokens"))
      (get m "seon/truncated") (assoc :truncated? true)
      (get m "seon/elisions") (assoc :elisions (get m "seon/elisions"))
      (get m "seon/recorded") (assoc :recorded (symbol (t "seon/recorded")))
      (get m "seon/record-error") (assoc :record-error (t "seon/record-error"))
      (get m "seon/contract") (assoc :contract (keyword (t "seon/contract")))
      (get m "seon/contract-error") (assoc :contract-error (clojure.edn/read-string (t "seon/contract-error")))
      (get m "seon/warnings") (assoc :warnings (clojure.edn/read-string (t "seon/warnings")))
      (seq (:out out)) (assoc :out (:out out)))))

(defn fold
  "The answers of one eval request (in order) -> its reply items. `out`/`err` text goes
  onto the next form's item (the form that printed it). text decodes a text field
  (seon.wire/text-> for answers that crossed the wire, identity in-process)."
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
  "The recording transaction of one eval request (G1's proto.agent/record-tx, Seon's names):
  one evaluation entity per item (its id, run, ordinal, source, tag, shown value or error,
  time, allocation), each on the agent's entity, and the agent's answered t. Ids are new
  each time; the agent entity is the one upsert."
  {:malli/schema [:=> [:cat :seon.agent/id :string :seon.agent/items [:maybe :int]] :seon.db/tx-data]}
  [id run items t-basis]
  (conj (vec (for [it items :when (:id it)]
               (cond-> {:seon.cluster.eval/id (str run "/" (:id it)) :seon.cluster.eval/run run
                        :seon.cluster.eval/ordinal (:id it) :seon.cluster.eval/agent "agent"
                        :seon.cluster.eval/tag (:tag it) :seon.cluster.eval/source (or (:form it) "")
                        :seon.eval/duration-us (long (* 1000 (or (:ms it) 0))) :seon.eval/alloc-bytes (long (or (:alloc-bytes it) 0))}
                 (= :ret (:tag it)) (assoc :seon.eval/shown (or (:printed it) ""))
                 (= :err (:tag it)) (assoc :seon.cluster.eval/error (or (:printed it) ""))
                 (:out it) (assoc :seon.cluster.eval/output (:out it)))))
        (cond-> {:db/id "agent" :seon.agent/id id} t-basis (assoc :seon.agent/answered-t t-basis))))

(defn eval-string
  "Evaluate code from inside this agent (the turn loop's forms) through the one REPL path:
  the handler and its middleware, its answers folded into reply items, recorded on the
  branch in one transaction (the evaluations and the defs) through the agent's write path."
  {:malli/schema [:=> [:cat :string] :seon.agent/items]}
  [code]
  (let [answers (volatile! [])
        _ (@the-handler {"op" "eval" "code" code :reply #(vswap! answers conj %)})
        items (fold @answers identity)
        defs (into [] (mapcat val) (:defs @agent/state))]
    (swap! agent/state dissoc :defs)
    (when (and contract/record? (= :agent (:mode @agent/state)) (seq items))
      (agent/transact! (into defs (record-tx (:id @agent/state) (str (name (:id @agent/state)) "-" (lib/c-getpid) "-" (lib/now-ns))
                                             items (:max-tx (agent/db))))))
    items))

;; ---- the process: agent-step under run-loop ----------------------------------------

(defn- reply-fn
  "Answers go straight to root's socket; the request's `done` also carries the agent's
  t and memory (root's view of it)."
  {:malli/schema [:=> [:cat :seon.wire/msg] :seon.host/fn]}
  [req]
  (let [id (get req "id") sid (get req "session")]
    (fn [m] (let [done? (some #{"done"} (get m "status"))
                  rec (when (and done? contract/record? (= :agent (:mode @agent/state)) (= "eval" (get req "op")))
                        (let [defs (into [] (mapcat val) (:defs @agent/state))]
                          (swap! agent/state dissoc :defs)
                          {"seon/run" (str (name (:id @agent/state)) "-" (lib/c-getpid) "-" (lib/now-ns))
                           "seon/defs" (binding [*print-length* nil *print-level* nil] (pr-str defs))}))]
              (agent/send-wire! (cond-> m
                                  id (assoc "id" id) sid (assoc "session" sid)
                                  done? (assoc "seon/t" (:max-tx (agent/db)) "seon/mem-mb" (str (agent/mem-mb)))
                                  rec (merge rec)))))))

(defn agent-step
  "The agent as a step-fn, run on its one thread by seon.step/run-loop: requests in on
  :sock (nREPL requests as {:op :nrepl :msg m}, Seon's as {:op k ...}), replies out on
  :sock; :pause, :resume, :stop and :ping are taken by the loop. Its REPL state is the
  process's one state map (seon.agent/state), which the code it evaluates reads, so the
  step's own state is empty."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:sock "root's requests"} :outs {:sock "replies"} :workload :io
       :ping-map-fn (fn [_] {:evals (:n @agent/state) :pending (count (:pending @agent/state)) :mem-mb (:mem @agent/state)})})
  ([_] {})
  ([st _] st)
  ([st _ m]
   (if (= :nrepl (:op m))
     (let [req (assoc (:msg m) :reply (reply-fn (:msg m)))]
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
  (agent/hello! t-req-ns {:interrupt-ok (:interrupt-ok @agent/state)})
  (serve!))
