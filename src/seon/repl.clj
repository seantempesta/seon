(ns seon.repl
  "An agent's REPL speaking nREPL's message model on its one thread (session F2, after session
  R2's r2.repl; the owner chose nREPL's model for agents).

  The agent's process is a Flow graph of three :io procs on its one thread (`serve!`):
  `transport-step` reads root's frames, `agent-step` runs its REPL, `tests-step` its test runs.
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
  (:require [seon.contract :refer [defn defn- defchecked]] [seon.error :as err] [jolt.nrepl :as nrepl] [seon.intr :as intr] [seon.reader :as reader] [seon.doc :as doc] [seon.agent :as agent] [seon.contract :as contract] [my.db] [my.agent] [my.schema] [malli.registry :as mr]
            [seon.program :as program] [jolt.analyzer] [seon.lib :as lib] [seon.print :as print] [seon.print.select :as select] [seon.render :as render] [seon.context :as context] [seon.ctx :as ctx] [seon.web.html :as h] [seon.profile :as profile] [seon.sock :as sock] [seon.step :as step] [clojure.core.async :as a] [clojure.core.async.flow :as flow]
            [jolt.ffi :as ffi] [seon.config :as cfg] [clojure.test] [datahike.api :as d] [seon.db :as pdb] [seon.edn :as sedn]))

(defchecked ^:private outer-frames [:=> [:cat] :any] @#'jolt.nrepl/outer-frames)

(def ^:private budgeted
  "The origins whose forms run under the config's budgets of ticks and bytes (an agent's model, an
  outside agent, the page, an inserted eval): root's own platform requests and the agent's loop run unbounded. See
  docs/processes.md#interrupts."
  #{:model :outside :ui :insert})

(defn- form-src
  "The exact text of form in code, from its reader position (:line, :column; 1-based) to end,
  the offset where the reader stopped after it, or nil for a form that carries no position (a
  symbol, a number)."
  {:malli/schema [:=> [:cat :string [:vector :int] :any :int] [:maybe :string]]}
  [code starts form end]
  (let [{:keys [line column]} (meta form)]
    (when (and line column)
      (subs code (+ (starts (dec line)) (dec column)) end))))

(defn- error-kept
  "The exception *e keeps after an eval threw e: a new one holding the error as data (agent/error-map),
  with the original's message and no cause or frames, so *e never pins what the throw captured
  (AGENTS 4: an error keeps data; docs/machine.md#roots-heap). ex-message,
  ex-data and my.agent/pst read it as they read any exception."
  {:malli/schema [:=> [:cat :seon.host/throwable] :seon.host/throwable]}
  [e]
  (ex-info (step/error-msg e) (agent/error-map e)))

(declare error-data)

(defn- keep-source!
  "A definition keeps its exact text on its var (owner: source reads the live var), so
  clojure.repl/source shows what this process runs, a REPL-typed defn included."
  {:malli/schema [:=> [:cat :any [:maybe :string]] :any]}
  [v src]
  (when (and (var? v) src) (alter-meta! v assoc :source src)))

(defn- defines-program?
  "Does form, read in namespace ns, define a var of the program: ns is no scratch (seon.program/scratch-ns?) and the
  form's head is def or a macro named def... (defn, deftest; deferred, a function, is none), in ns or among the REPL's names (seon.agent/repl-name). Such
  a form resolves as written, never through the REPL's names; one that only moves or requires does. Only the head is
  read: a definition inside another form, (do (defn f ...)), evaluates with the REPL's names, as it ran at the REPL. See
  docs/agent.md#the-repl."
  {:malli/schema [:=> [:cat :any :seon.host/namespace] :boolean]}
  [form ns]
  (boolean
    (when (and (seq? form) (symbol? (first form)) (not (program/scratch-ns? ns)))
      (let [h (first form)
            v (or (ns-resolve ns h) (some-> (agent/repl-name h) find-var))]
        (or (= 'def h) (and (var? v) (:macro (meta v)) (clojure.string/starts-with? (str (:name (meta v))) "def")))))))

(defn- shadowed
  "The REPL helpers (clojure.repl's and pprint's of seon.agent/repl-names) namespace ns holds a var of its own for, {name its-var}:
  there the name is that var and not the REPL's, as Clojure warns when a definition replaces a
  referred var (an agent in seon.render.ui called its source with a function and read an nth
  error four times). nil when none. It answers a move's :helper-shadowed (docs/render.md#a-part-answers-data)."
  {:malli/schema [:=> [:cat :seon.host/namespace] [:maybe [:map-of :symbol :symbol]]]}
  [ns]
  (not-empty (into {} (for [[from names] agent/repl-names :when ('#{clojure.repl clojure.pprint} from)
                            s names
                            :let [v (ns-resolve ns s)]
                            :when (and (var? v) (not= from (ns-name (:ns (meta v)))))]
                        [s (symbol (str (ns-name (:ns (meta v)))) (str s))]))))

(defn- do-parts
  "The inner forms of a top-level form written as (do ...), each as [text lead]: its exact text and the
  ; comments its writer put before it, taken from the reader's positions in src (the form's own
  text, which the reader gave it; an inner form read again in ns, as the form was, stops where
  the reader stops). What Clojure's compiler evaluates as top-level forms when the form it evaluates
  is (do ...) (Compiler.eval unrolls it), each with its own budget of ticks, as each top-level form
  has. A form that only expands to do (a reader macro) is no written do: its text does not open
  with ( and the token do. Nil for any form but a written do with a body. See docs/prompt.md."
  {:malli/schema [:=> [:cat :any [:maybe :string] :seon.host/namespace] [:maybe [:vector [:tuple :string [:maybe :string]]]]]}
  [form src ns]
  (when (and (seq? form) (= 'do (first form)) (next form) src (= \( (.charAt ^String src 0)))
    (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. src))
          starts (reader/line-starts src)
          pos #(+ (starts (dec (.getLineNumber r))) (dec (.getColumnNumber r)))]
      (.read r)
      (when (= 'do (binding [*ns* ns] (read {:eof ::eof} r)))
        (loop [prev (pos) k (count (next form)) acc []]
          (if (zero? k)
            acc
            (let [_ (binding [*ns* ns] (read {:eof ::eof} r))
                  end (pos)]
              (recur end (dec k) (conj acc [(subs src (reader/lead-end src prev end) end) (reader/lead src prev end)])))))))))

(defn- eval-code
  "Read the request's code once, form by form, and evaluate each with (:repl/eval-fn req)
  under seon.intr/run, in the request's \"ns\", else the agent's own. Answers each form,
  with its exact text (:repl/src), the ; comments its writer put before it (:lead, which a
  definition keeps as its leading comment) and what it wrote to *out* and *err*; the first
  error or an interrupt stops the request (one pending since its asker cleared it included:
  agent-step, the turn loop), as load-string stops; then done. A top-level (do ...) is its
  forms, each read and evaluated as a top-level form, as Clojure's compiler unrolls it, so a
  definition inside it is program (docs/prompt.md); so is a top-level (load-file \"path\") of an
  existing file, as its file's forms (docs/agent.md#the-repl)."
  {:malli/schema [:=> [:cat :seon.wire/msg] :any]}
  [{:keys [reply] :as req}]
  (let [eval-fn (:repl/eval-fn req eval)
        ;; the form's budget of work (engine ticks): the request's, else the config key for an
        ;; agent's model and an outside agent; root's own platform requests run unbounded
        ticks (let [t (or (:ticks req)
                          (when (budgeted (:origin req)) (cfg/value :seon.config.agent/form-ticks)))]
                (when (and t (pos? t)) t))
        ;; and of allocation (bytes), for the same requests (seon.intr/run): root's and work!'s run unbounded
        alloc (let [b (or (:bytes req) (when (budgeted (:origin req)) (cfg/value :seon.config.agent/form-bytes)))]
                (when (and b (pos? b)) b))
        ;; one top-level form from ns: [ns' :ok], or [ns' how] when it ended the request (:err); a form
        ;; written as (do ...) is its inner forms, each one as a top-level form (do-parts), the comments
        ;; before the do leading the first of them
        forms-ref (volatile! nil)
        exec! (fn exec! [form src lead ns top]
                (cond
                  ;; a top-level (load-file "path") of a file that exists is the file's forms, each a top-level
                  ;; form of this request (recorded, its text its var's :source); else clojure.core's, which
                  ;; answers the missing file as its own error
                  (and (seq? form) (= 'load-file (first form)) (= 2 (count form)) (string? (second form))
                       (.isFile (java.io.File. ^String (second form))))
                  ;; the file's end is this form's: the request's next form runs after it
                  (let [[ns' how] (@forms-ref (slurp (second form)) ns top)] [ns' (if (= :eof how) :ok how)])

                  (do-parts form src ns)
                  (let [parts (do-parts form src ns)]
                   (loop [ns ns forms (next form) [[t l] & more] parts lead lead]
                    (let [[ns' how] (exec! (first forms) t (not-empty (apply str (interpose "\n" (keep identity [lead l])))) ns top)]
                      (if (and (= :ok how) more) (recur ns' (next forms) more nil) [ns' how]))))

                  :else
                 (let [n (dec (:n (swap! agent/state update :n (fnil inc 0))))
                       out (java.io.StringWriter.)
                       err (java.io.StringWriter.)
                       ;; *1 *2 *3 *e as clojure.main/repl keeps them; clojure.test reports to
                       ;; *test-out*, bound to the form's output as nREPL does
                       {v1 :v1 v2 :v2 v3 :v3 e1 :e} (:repl-vars @agent/state)
                       [tag v ns' outer] (try (binding [*ns* ns *out* out *err* err clojure.test/*test-out* out *1 v1 *2 v2 *3 v3 *e e1
                                                         ;; the REPL's names in every namespace (seon.agent/repl-name), but for a
                                                         ;; definition of the program, which resolves as written
                                                         jolt.host/*resolve-unresolved* (when-not (defines-program? form ns) agent/repl-name)]
                                                (let [v (intr/run ticks alloc #(eval-fn form))] [:ret v *ns*]))
                                              (catch Throwable e [:err e ns (outer-frames)]))
                       ;; the form as the wire carries it, whole: its own text, else its EDN (a symbol, a number)
                       common (cond-> {:id n :out (not-empty (str out)) :err-out (not-empty (str err)) :form (or src (sedn/encode form)) :repl/form form
                                       :repl/src src :top top}
                                lead (assoc :lead lead))]
                   (swap! agent/state assoc :repl-vars (if (= tag :ret) {:v1 v :v2 v1 :v3 v2 :e e1} {:v1 v1 :v2 v2 :v3 v3 :e (error-kept v)}))
                   (when (= tag :ret) (keep-source! v (:repl/src common)))
                   (if (= tag :ret)
                     (do (reply (cond-> (merge common {:repl/value v :tag :ret})
                                  ;; a move into a namespace whose own vars hide a helper says so, once, as it moves
                                  (not= ns ns') (merge (some->> (shadowed ns') (hash-map :shadowed)))))
                         [ns' :ok])
                     (do (reply (let [m (merge common {:tag :err :err-text (str err (nrepl/err->msg v {:outer outer}))
                                                        "status" (if (:interrupted (ex-data v)) ["interrupted"] ["eval-error"])})]
                                 (assoc m :repl/ex (error-data v m))))
                         [ns :err])))))
        ;; code's forms in order, from ns: [ns' how], how :eof, :read (a read error) or :err; each
        ;; answer names its top-level form (:top, its place in the code; a top-level do's forms its own)
        forms! (fn forms! [code ns top]
                 (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. code))
                       starts (delay (reader/line-starts code))]
                   (loop [ns ns prev 0 k 0]
                     (let [form (try (binding [*ns* ns] (read {:eof ::eof} r)) (catch Throwable e e))
                           ;; where the reader stopped after the form (an eval never moves it), its text and the comments before it
                           end (when-not (or (= ::eof form) (instance? Throwable form))
                                 (+ (@starts (dec (.getLineNumber r))) (dec (.getColumnNumber r))))
                           src (when end (form-src code @starts form end))
                           lead (when end (reader/lead code prev end))]
                       (cond
                         (= ::eof form) [ns :eof]

                         (instance? Throwable form)
                         (let [n (:n (swap! agent/state update :n (fnil inc 0)))]
                           (reply (let [m {:tag :err "status" ["eval-error"] :repl/phase :read :id (dec n)
                                           :err-text (nrepl/err->msg form {:phase :read-source})}]
                                   (assoc m :repl/ex (error-data form m))))
                           [ns :read])

                         :else
                         (let [[ns' how] (exec! form src lead ns (or top k))]
                           (if (= :ok how) (recur ns' end (inc k)) [ns' how])))))))
        _ (vreset! forms-ref forms!)
        [ns how] (forms! (get req "code") (or (some-> (get req "ns") symbol find-ns) (:ns @agent/state)) nil)]
    ;; a read error leaves the agent's namespace as it was
    (when-not (= :read how) (swap! agent/state assoc :ns ns))
    (reply {"status" ["done"]})))

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
  (let [tm (Throwable->map e)
        d (merge (clojure.main/ex-triage (assoc tm :clojure.error/phase :execution)) (ex-data e))
        compile? (:jolt.error/line d)
        ;; a frame inside an anonymous fn is named ns/var$fn: the error is in that var (a cast
        ;; in a map-indexed fn would otherwise name no fn: its frame ns/var$fn, its line -1)
        ;; a contract's wrapper (malli's instrumented fn, seon.contract's) is never where an error is: whether its
        ;; frame tops the trace depends on what is armed, so an error at a request's top was located in malli.core
        ;; after some tests and in nothing after others
        sym (when-not compile? (some-> (:clojure.error/symbol d) (#(symbol (namespace %) (first (clojure.string/split (name %) #"\$"))))
                                       (#(when-not (#{"malli.core" "seon.contract"} (namespace %)) %))))
        ;; its line: the error's own, else the var's own frame's in the trace (the anonymous fn's frame has none)
        frame-line (fn [] (let [prefix (str (munge (namespace sym)) "$" (munge (name sym)) "$")]
                            (some (fn [[cls _ _ l]] (when (and (clojure.string/starts-with? (str cls) prefix) (pos-int? l)) l)) (:trace tm))))
        line (let [l (or (:jolt.error/line d) (:clojure.error/line d))] (if (and l (pos? l)) l (when sym (frame-line))))
        {start :line col0 :column} (meta (if compile? (:repl/form m) (some-> sym var-of)))]
    (if (and line start (<= start line))
      (let [n (inc (- line start))
            col (some-> (:jolt.error/column d) (- (if (and col0 (= 1 n)) (dec col0) 0)))]
        (cond-> {:seon.error/at (cond-> {:line n} col (assoc :column col))}
          sym (assoc :seon.error/in sym)))
      {})))

(defn- clojure-message
  "Error map em's message as Clojure's REPL says it (clojure.main/ex-str): for an error in reading,
  macroexpanding or compiling, the phase, the symbol and the position before the cause, \"Syntax error
  macroexpanding clojure.core/cond at (6:3). index out of bounds\"; else its message as it is: Jolt's cond failed on an odd
  number of forms with only \"index out of bounds\", which names no form. See docs/errors.md#an-eval-errors-diagnostics."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{:clojure.error/keys [phase line column] sym :clojure.error/symbol msg :seon.error/message}]
  (let [what (case phase
               (:macroexpansion :macro-syntax-check) "macroexpanding"
               :compile-syntax-check "compiling"
               :read-source "reading source"
               nil)]
    (if (and what msg (not (clojure.string/starts-with? msg "Syntax error")))
      (str "Syntax error " what (when sym (str " " sym)) (when line (str " at (" line (when column (str ":" column)) ")")) ". " msg)
      msg)))

(defn- given
  "For a macro's syntax error in the form an eval read (phase macroexpansion or its syntax check, at
  Jolt's position): the forms the macro call was given, each printed whole by the one printer (marked
  :seon.print/full: a cut would drop the tail a swallowed form sits in, and an agent's own error is never
  cut), as the reader read them, {:seon.error/given [..]}; else nil. A count or a grouping is what such an error is
  about, and the reader's grouping is what the writer cannot see: a clause short of a paren swallows
  the next form into it, and \"cond requires an even number of forms\" does not say where. See
  docs/errors.md#an-eval-errors-diagnostics."
  {:malli/schema [:=> [:cat :map :map] [:maybe [:map [:seon.error/given [:vector :string]]]]]}
  [em m]
  (let [{line :jolt.error/line col :jolt.error/column phase :clojure.error/phase} em]
    (when (and line col (#{:macro-syntax-check :macroexpansion} phase))
      (when-let [f (some #(when (and (seq? %) (= line (:line (meta %))) (= col (:column (meta %)))) %)
                         (tree-seq coll? seq (:repl/form m)))]
        {:seon.error/given (mapv #(print/text (cond-> % (instance? clojure.lang.IObj %) (vary-meta assoc :seon.print/full true))) (rest f))}))))

(defn- error-data
  "The error map of an eval's exception e (seon.agent/error-map, with its position in the form's
  terms: `located`; :phase :read for a read error), made where the reply is made, so the reply
  carries error data and never the Throwable, whose frames and ex-data would pin the system for as
  long as the reply is held (AGENTS 4, a kept value is a reference; docs/errors.md#an-error-keeps-data)."
  {:malli/schema [:=> [:cat :seon.host/throwable :map] :map]}
  [e m]
  (let [em (cond-> (agent/error-map e) (:repl/phase m) (assoc :phase :read))
        em (if (= :seon.agent/eval-error (:seon.error/type em)) (merge em (located e m) (given em m)) em)]
    ;; its message as Clojure's REPL says it: where it failed (reading, macroexpanding, compiling) first
    (cond-> em (:seon.error/message em) (assoc :seon.error/message (clojure-message em)))))

(defn wrap-caught
  "An eval error also as the one error map (made with the reply: `error-data`, the answer's :err: its
  message and fix as data, which the response carries as :ex), and
  noted in this agent's error fold (seon.error/note): its next commit carries the fold to
  root, which counts each signature across agents."
  {:malli/schema [:=> [:cat :seon.host/fn] :seon.host/fn]}
  [h]
  (fn [req]
    (h (around-reply req
         (fn [m] (if-let [em (:repl/ex m)]
                   (do (swap! agent/state update :errors err/note em :eval (:form m) (System/currentTimeMillis))
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
                                     (seq fns) (assoc :fns fns)))
                               m))))))))

(defn- ns-form?
  "Does form's head resolve, in nsx, to clojure.core/ns (resolution, as the compiler does)?"
  {:malli/schema [:=> [:cat :any :seon.host/namespace] :boolean]}
  [form nsx]
  (boolean (and (seq? form) (symbol? (first form)) (= #'clojure.core/ns (ns-resolve nsx (first form))))))

(defn- unmapped
  "The vars form removed with clojure.core/ns-unmap, as this branch records them: each
  (ns-unmap 'n 's) it calls (its namespace quoted, or *ns*, read in ns, where the form ran), whose var
  db records with a definition and n no longer interns, {var its-entity form-entity}. What the eval
  records as removed: Clojure's ns-unmap, observed, as v1's REPL recorded it (my.program/unmap-subject!;
  docs/source.md#an-agent-changes-code-at-its-repl). An unmapped scratch helper is no longer program, so
  the done rule does not see it as an uncontracted one."
  {:malli/schema [:=> [:cat :seon.host/db :any :seon.host/namespace] [:vector [:tuple :qualified-symbol :int :int]]]}
  [db form ns]
  (let [quoted (fn [x] (when (and (seq? x) (= 'quote (first x)) (symbol? (second x))) (second x)))]
    (vec (for [x (tree-seq coll? seq form)
               :when (and (seq? x) (symbol? (first x)) (= #'clojure.core/ns-unmap (ns-resolve ns (first x))))
               :let [[_ a b] x
                     n (if (= '*ns* a) (ns-name ns) (quoted a))
                     s (quoted b)]
               :when (and n s (find-ns n) (not (contains? (ns-interns (find-ns n)) s)))
               :let [v (symbol (str n) (str s)) e (pdb/eid db :var/name v) f (some-> e (#(d/entity db %)) :var/form :db/id)]
               :when f]
           [v e f]))))

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
                              (program/account (assoc l :src (:repl/src m) :lead (:lead m) :form (:repl/form m) :ns-meta md :ran? (contains? m :repl/value))))
                       ;; a def beside calls stays this eval (seon.program/definition-form?), and says so
                       nested? (and acct (not (program/definition-form? (:repl/form m) (the-ns (:ns l)))))
                       acct (when-not nested? acct)]
                   (when l (vreset! last nil))
                   ;; a var the form unmapped is no longer program on this branch: its definition retracted
                   (let [gone (when (and l (= :agent (:mode @agent/state)) contract/record? (contains? m :repl/value))
                                (not-empty (unmapped (agent/db) (:repl/form m) (the-ns (:ns l)))))
                         _ (when gone (agent/transact! (mapv (fn [[_ e f]] [:db/retract e :var/form f]) gone)))
                         m (cond-> m gone (assoc :unmapped (mapv first gone)))]
                   (if acct
                     (let [tx (when contract/record? (program/record-tx (agent/db) acct))
                           ;; what this form's own transaction changes of an existing var's contract or
                           ;; docstring, read before it is applied (a form that defines nothing reads nothing)
                           redefined (when tx (program/meaning-changes (agent/db) tx))]
                       (when tx (agent/transact! tx))
                       (cond-> (assoc m :recorded (vec (concat (map :var (:defs acct)) (when (:ns-form? acct) [(:ns acct)])))
                                      :repl/defined (mapv :var (:defs acct)))
                         redefined (assoc :redefined redefined)))
                     (cond-> m
                       l (assoc :repl/defined (program/set-vars l))
                       (or nested? (and l (= :agent (:mode @agent/state)) (program/calls-eval? l)))
                       (assoc :not-program "A definition inside a let or through eval runs in this process and is not program, so it will not merge: define it at a form's top level (seon.program/definition-form?)."))))))))))))

(defn- branch-registry
  "The registry this agent's evaluation binds (seon.program/schema-registry of its branch's
  schema changes), so what it defines next compiles against them; empty when no branch is
  held (root, a scratch session). The one held (:schema-registry) answers until a write moves a
  schema entry (seon.agent/transact!, the merge of main: seon.agent/schema-moved), which drops it;
  a new agent holds main's from its fork (seon.fork-server/warm-start). Reading the changes is two
  passes over every schema entry (1.08 MB a call: the commit that made it says), so no eval reads them while
  none moved. See docs/machine.md#an-agents-first-write."
  {:malli/schema [:=> [:cat] :any]}
  []
  (if-let [db (:db @agent/state)]
    (or (:registry (:schema-registry @agent/state))
        (let [k (program/schema-changes db) r (program/schema-registry k)]
          (swap! agent/state assoc :schema-registry {:key k :registry r})
          r))
    {}))

(defn with-branch-schemas
  "f called with malli's dynamic registry bound to this branch's population (branch-registry),
  read now: what a form compiles against, and the renders its values' schemas name
  (seon.contract/properties reads it). Each eval binds it, and so does each turn of the turn loop
  (seon.turn/work!), so Seon's own code checks the agent's values against the schemas the agent
  registered since the loop began (docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map)."
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
  with :malli/schema is armed and leaves the agent's uncontracted set; one without joins it.
  Answers what happened to it, {:var :armed true} (with :error when its contract could not be
  armed) or {:var :uncontracted set}, the agent's uncontracted definitions after it; nil for a
  var that is no function. response turns them into the eval's warnings."
  {:malli/schema [:=> [:cat :seon.host/var] [:maybe :map]]}
  [v]
  (when (contract/function-var? v)
    (let [sym (symbol v)]
      (if (contract/contract v)
        (let [r (contract/arm! v)]
          (swap! agent/state update :uncontracted (fnil disj #{}) sym)
          (cond-> {:var sym :armed true} (contract/error? r) (assoc :error r)))
        {:var sym :uncontracted (:uncontracted (swap! agent/state update :uncontracted (fnil conj #{}) sym))}))))

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
             (reduce (fn [m s] (if-let [i (some-> (find-var s) instrument)] (update m :instrumented (fnil conj []) i) m)) m (:repl/defined m))))))))

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

(defn eval-number
  "The n of an eval's id \"e<n>\" (:seon.eval/id), or nil for any other value."
  {:malli/schema [:=> [:cat :any] [:maybe nat-int?]]}
  [id] (when (string? id) (some->> id (re-matches #"e(\d+)") second parse-long)))

(defn evals-max
  "The highest eval number db records, 0 for none: what an agent on db counts on from (eval-n!).
  One pass over every eval's id; the fork server keeps main's (seon.fork-server/evals-max-after),
  so a new agent never reads it. See docs/agent.md#your-turn."
  {:malli/schema [:=> [:cat :seon.host/db] nat-int?]}
  [db] (transduce (keep #(eval-number (:v %))) max 0 (d/datoms db :aevt :seon.eval/id)))

(defn- eval-n!
  "The next eval's number: one more than the highest e<n> this agent's branch records (a new
  agent is handed main's, as its branch is main at its fork: seon.agent/init!; a resumed one reads
  its branch's once, evals-max), so a handle is unique across restarts of the branch's agents, an
  eval cut off before its record included; then this process's count. A process holding no branch
  (root) counts from 0. See docs/agent.md#your-turn."
  {:malli/schema [:=> [:cat] pos-int?]}
  []
  (let [from (when-not (:eval-n @agent/state)
               (if-let [db (:db @agent/state)] (evals-max db) 0))]
    (:eval-n (swap! agent/state update :eval-n #(inc (or % from))))))

(defn response
  "A request's answers (in order) as its one response (:seon.eval/response): its handle, its
  value (:value: one top-level form's value as Clojure answers it, so (do (+ 1 1) (+ 2 2)) answers
  4; code of several top-level forms answers each one's value in order, as a REPL prints them, a
  vector beside :forms, how many ran, so (+ 1 1) (+ 2 2) answers :forms 2 :value [2 4]; docs/prompt.md),
  what its forms wrote to *out* and *err*, the error that stopped it (:ex, the one error map),
  what its forms changed of an existing var's contract or docstring (:redefined, wrap-record's,
  seon.program/meaning-changes), what its definitions warn (:warnings, from instrument's account
  of each var it defined, in order: the agent's uncontracted definitions as they stand at the
  eval's end when it defined one, with the fix; each contract that could not be armed, by its var;
  a def beside calls that stays the eval, not program),
  and its cost summed over its forms (ms, ticks, bytes allocated);
  each key only when present (DECISIONS Dogfood rulings; a change to a schema
  is an explicit fact the eval shows)."
  {:malli/schema [:=> [:cat [:sequential :seon.wire/msg] :qualified-symbol] :seon.repl/answer]}
  [answers handle]
  (let [fs (filterv :tag answers)
        lst (peek fs)
        ;; the top-level forms that ran, each its answers (a do's forms are one form's)
        ;; a read error is no form that ran: it answers :ex, never a member of :forms
        tops (partition-by :top (filter :top fs))
        joined (fn [k] (not-empty (apply str (keep k fs))))
        sum (fn [k] (when (some k fs) (reduce + (keep k fs))))
        is (into [] (mapcat :instrumented) fs)
        ;; the uncontracted set as the eval left it: each uncontracted var's account holds the set
        ;; after it, and a var armed later in the eval leaves it
        left (reduce (fn [u i] (if (:uncontracted i) (:uncontracted i) (disj (or u #{}) (:var i)))) nil is)
        warnings (cond-> (vec (for [i is :when (:error i)] {:seon.repl/finding :invalid-contract :var (:var i) :error (:error i)}))
                   (and (some :uncontracted is) (seq left))
                   (conj {:seon.repl/finding :uncontracted-definition
                          :seon.repl/fix (str "Declare a contract on each: (defn f {:malli/schema [:=> [:cat ...] ...]} [...] ...). "
                                              "A branch carrying an uncontracted function is refused at merge.")
                          :uncontracted (vec (sort-by str left)) :count (count left)})
                   ;; a def beside calls (wrap-record): it stays this eval, not program
                   (some :not-program fs)
                   (conj {:seon.repl/finding :not-program :seon.repl/fix (some :not-program fs)})
                   (some :shadowed fs)
                   (conj (let [sh (some :shadowed fs)]
                           {:seon.repl/finding :helper-shadowed :shadowed sh
                            :seon.repl/fix (str "In this namespace " (clojure.string/join ", " (map (fn [[k v]] (str k " is " v)) sh))
                                                ", not the REPL's: call " (clojure.string/join " or " (map #(str "clojure.repl/" (key %)) sh)) " qualified")})))]
    (cond-> {:handle handle}
      (next tops) (assoc :forms (count tops)
                         :value (into [] (comp (map last) (filter #(contains? % :repl/value)) (map :repl/value)) tops))
      (and (not (next tops)) (contains? lst :repl/value)) (assoc :value (:repl/value lst))
      ;; what it made program on the branch: each var it defined, an ns form's namespace
      (some :recorded fs) (assoc :recorded (into [] (mapcat :recorded) fs))
      (some :redefined fs) (assoc :redefined (into [] (mapcat :redefined) fs))
      ;; and what it removed from the branch's program (seon.repl/unmapped)
      (some :unmapped fs) (assoc :unmapped (into [] (mapcat :unmapped) fs))
      (seq warnings) (assoc :warnings warnings)
      ;; what it printed, verbatim, as a REPL shows *out* (seon.print/code: never a string literal, whose escapes
      ;; copied back as code double every \\( and break case constants)
      (joined :out) (assoc :out (print/code (joined :out)))
      (joined :err-out) (assoc :err (print/code (joined :err-out)))
      (= :err (:tag lst)) (assoc :ex (or (:err lst) (contract/error :seon.agent/eval-error #'response (str (:err-text lst)) {:class "unknown: the eval answered text, no error"})))
      (sum :ms) (assoc :ms (/ (Math/round (* 1000.0 (sum :ms))) 1000.0))
      (sum :ticks) (assoc :ticks (sum :ticks))
      (sum :alloc-bytes) (assoc :bytes (sum :alloc-bytes)))))

(defn- repaired
  "Response r with what the forgiving reader changed of its code before it ran (req's :repaired,
  seon.context/repaired), so every call's own result tells it, a call not run included."
  {:malli/schema [:=> [:cat :seon.repl/answer :map] :seon.repl/answer]}
  [r req] (cond-> r (seq (:repaired req)) (assoc :repaired (:repaired req))))

(defn- by
  "Who evaluated, as the record names it (:seon.eval/by): whoever asked an inserted eval (the request's :by), the user (the page), an outside
  session (root's server), a context read (seon.turn/context-block), else the agent itself (its model's turn)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [origin] asked :by}]
  (case origin :insert asked :ui "user" :outside "outside" :context "context" (name (or (:id @agent/state) :root))))

(defn record-tx
  "The recording transaction of one eval: its entity, on the agent's, who evaluated it (:seon.eval/by), and its
  details, each its own attribute (`record-details`). Who is the one upsert, by its identity: an
  agent's key ({:seon.agent/key k}, seon.task/agent-key) or root's ({:seon.root/id :root}); an agent's holds the
  namespace the eval left its REPL in (:seon.agent/ns), a datom only when it moved (an unchanged value adds none),
  which seon.context/current-ns reads. See docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat :map :string :string :map] :seon.db/tx-data]}
  [who id by details]
  [(into {:seon.eval/id id :seon.eval/agent "agent" :seon.eval/by by} details)
   (cond-> (assoc who :db/id "agent")
     (and (:seon.agent/key who) (symbol? (get-in details [:seon.eval/response :ns]))) (assoc :seon.agent/ns (get-in details [:seon.eval/response :ns])))])

(defn- kept
  "Value v as an eval's record keeps it, [value drawn?]: plain data (seon.contract/plainly); past the largest value kept
  (:seon.config.store/max-value-chars, counted by seon.contract/within?), what its schema's model render draws of it,
  when it declares one and that fits (drawn? true: it is that render's value, to print as it is); else the marker of what
  it was and the bound it passed, {:seon.value/gone {:type :over}}. See docs/storage.md#plain-data."
  {:malli/schema [:=> [:cat :any] [:tuple :any :boolean]]}
  [v]
  (let [bound (cfg/value :seon.config.store/max-value-chars)]
    ;; counted before anything is copied: a value past the bound costs the bound, whatever its size
    (if (contract/within? v bound)
      [(contract/plainly v bound) false]
      (let [m (some-> (render/model v) :value (contract/plainly bound))]
        (if (and (some? m) (contract/within? m bound))
          [m true]
          [{:seon.value/gone {:type (.getName (class v)) :over bound}} false])))))

(defn record-details
  "What an eval records, each its own attribute of its entity (:seon.eval/code, :response, :schema, :basis, :intent,
:call-id, :fns; each only when present): the code; the response whole with its
  value as plain data (`kept`), never a text made for a reader (each prints it as it shows it: `text-of`),
  so a record is the same whatever came before it; the intent beside it, as written (never
  parsed here: the parser is program the agent may change, and an eval's record runs none of it; a reader
  that wants what the intent cites parses the kept intent, docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map);
  a value that names its render schema keeps it (the page draws the value by it); the value that IS the agent's ctx (@ctx, identical to the
  object seon.ctx/ctx holds: the branch's record at t, seon.ctx/build) keeps the basis in place of the value, so a copy of the session is never
  stored at each @ctx; the provider's call id, its run, its costliest functions (the reply's text and reasoning are its model call's)."
  {:malli/schema [:=> [:cat :string :map :map] [:map [:seon.eval/code :string] [:seon.eval/response :seon.eval/response]]]}
  [code r req]
  (let [resp (dissoc r :text :fns :record)
        v (:value resp)
        ;; the record at a t is the value of the agent's ctx itself (the one object ctx installs, seon.ctx/ctx): metadata
        ;; travels through select-keys, assoc and dissoc, so a map made of it is the data it holds, never a basis
        ctx? (and (contains? resp :value) (some? v) (identical? v @ctx/ctx))
        basis (when ctx? (select-keys v [:t :branch]))
        [kv drawn?] (when (and (contains? resp :value) (not basis)) (kept v))
        ;; a value its model render has already drawn prints as it is
        schema (when (and (contains? resp :value) (not drawn?)) (when-let [k (:seon.render/schema (meta v))] (when (or ctx? (not= :seon.ctx/ctx k)) k)))]
    (cond-> {:seon.eval/code code
             :seon.eval/response (cond basis (dissoc resp :value) (contains? resp :value) (assoc resp :value kv) :else resp)}
      schema (assoc :seon.eval/schema schema)
      basis (assoc :seon.eval/basis basis)
      (:intent req) (assoc :seon.eval/intent (:intent req))
      (:call-id req) (assoc :seon.eval/call-id (:call-id req))
      (seq (:fns r)) (assoc :seon.eval/fns (:fns r)))))

(defn- record!
  "Record response r of code, eval fact rec (`record-details`), on this branch through the agent's write path (an agent's
  only), under the turn in force (:seon.eval/turn, seon.agent/turn-now), with the defs its forms recorded.
  Answers r, with the id of its record (:record) when recorded."
  {:malli/schema [:=> [:cat :map :map :string :map] :map]}
  [r rec code req]
  (let [defs (into [] (mapcat val) (:defs @agent/state))
        id (name (:handle r))]
    (swap! agent/state dissoc :defs)
    ;; an eval within another (seon.repl/edit!'s) is that eval's: its definitions are program, its record the outer one
    (if (and contract/record? (= :agent (:mode @agent/state)) (not (:within req)))
      ;; the write is the edge: a branch that refuses it answers the eval :unrecorded with the writer's message, so the
      ;; turn that asked goes on (a refused write thrown from here ended the whole turn: its reply and evals lost, the turn run again)
      (try (agent/transact! (into defs (record-tx {:seon.agent/key (:key @agent/state) :seon.agent/id (:id @agent/state)} id (by req)
                                                  (let [working? (some? (:turn @agent/state))]
                                                    (cond-> rec
                                                      ;; the turn loop's own eval (the agent working a task, no model call, made by the agent) is no conversation record: it takes no turn
                                                      (not (and working? (not (:call-id req)) (not (#{:ui :outside :context :insert} (:origin req))))) (assoc :seon.eval/turn (agent/turn-now)))))))
           (assoc r :record id)
           (catch Throwable e
             (assoc r :unrecorded (contract/error :seon.agent/unrecorded #'record! (str "the branch refused this eval's record: " (ex-message e)) {}))))
      r)))

(defn- again
  "Response r of code, with :again, a string naming the earlier eval, when an earlier eval of this agent ran the same
  code (compared trimmed) and answered the same (its value, error, output and forms). :again says the answer repeats
  and nothing more: equal answers do not show what the code reads is unchanged. It is kept in the agent's state as a hash
  of the code to the eval and a hash of its answer, one lookup an eval, and only for an agent's own evals (root's,
  an inner eval's and one Seon ran for it, origin :insert, which it did not ask for, are not counted). See docs/agent.md#small-answers."
  {:malli/schema [:=> [:cat :string :map :map] :map]}
  [code r req]
  (if (and (= :agent (:mode @agent/state)) (:handle r) (not= :insert (:origin req)))
    (let [k (hash (clojure.string/trim code))
          ;; its answer without what differs every eval, at the one place it nests: an edit!'s value is its inner eval's
          ;; response, whose handle and cost differ each time (an agent swapping one line back and forth
          ;; repeats it). Nothing else is walked: the agent's own data keeps its keys, and a value holding a db or a host
          ;; object is hashed as it is, never rebuilt
          v (:value r)
          v (if (and (map? v) (not (record? v)) (contains? v :handle)) (dissoc v :handle :ms :ticks :bytes :fns :record) v)
          answer (hash [v (:ex r) (:out r) (:err r) (:forms r)])
          seen (get-in @agent/state [:seen k])]
      (swap! agent/state assoc-in [:seen k] {:eval (name (:handle r)) :answer answer})
      (cond-> r
        (= answer (:answer seen))
        (assoc :again (str (:eval seen) " ran this code and answered the same: "
                           "run something else, or change what it reads first."))))
    r))

(defn- text-of
  "The text of eval fact rec as every reader reads it: the one function the model's message prints with
  (seon.print/record-text, through render/model, an @ctx eval's value rebuilt from its basis by seon.context/basis-value, as
  seon.context/eval-messages does), so the outside agent's text and the model's cannot differ; the live value stays at
  the handle. Made only for a reader: an eval makes and stores data, and a model's is printed where its request is
  assembled. See docs/render.md#a-render-is-a-value-printed-once."
  {:malli/schema [:=> [:cat :map] :string]}
  [rec]
  (print/record-text (cond-> rec (:seon.eval/basis rec) (assoc-in [:seon.eval/response :value] (context/basis-value (:db @agent/state) rec))) render/model))

(defn eval-string
  "Evaluate code through the one REPL path (the handler and its middleware) and answer its one
  response (`response`) with the text its caller reads (:text, `text-of`) and what its forms
  recorded, its handle result/e<n> bound to it (the live value inside), and recorded (an
  agent's, on its branch). From inside an agent, the turn loop's calls; req adds to the request
  (root's: seon.server's namespace, origin, budget and eval fn; root's requests to an agent:
  agent-step's; a model's call: its :intent and :call-id). The request's entrant boundary: a
  defect past a form's own (a middleware's, the printer's) ends the request with its error as
  the response's :ex. Its cost (:ms :ticks :bytes, seon.repl/wrap-cost) is taken once when the
  forms ran, so what the model reads of it never changes in a later turn. The response's :ns is the
  namespace the REPL is in after it (docs/render.md#a-part-answers-data)."
  {:malli/schema [:function [:=> [:cat :string] :seon.repl/answer] [:=> [:cat :string :map] :seon.repl/answer]]}
  ([code] (eval-string code {:origin :model}))
  ([code req]
   (let [answers (volatile! [])
         handle (symbol "result" (str "e" (eval-n!)))
         ;; the writes its forms make and its record name it (seon.agent/transact!'s tx-meta); an
         ;; eval inside it (a context read's) names its own, then this one's again
         outer (:eval @agent/state)
         before @agent/state]
     (swap! agent/state assoc :eval (name handle))
     (try (let [;; with the namespace the REPL is in after it (:ns, as every nREPL response carries): the prompt
                ;; an eval makes data: its value made plain here, inside the boundary, so a value that throws as it is
                ;; realized is the error; text only for a reader who is not a model (whose message is printed at its request)
                ;; its forms left the agent no db (a test's cleanup, run at this REPL): the agent goes on as it was, its walk made
                ;; again, and ctx rebuilt by the state's watch (docs/merging.md#a-test-child-is-a-fork-of-a-live-agent)
                answered (fn [] (when (and (:db before) (nil? (:db @agent/state))) (reset! agent/state (dissoc before :walk)))
                               (let [;; how far through its turns the agent is, on the answer it reads, once (an eval within another is that eval's): a record's
                                     ;; bytes never move (docs/render.md#the-agents-turn)
                                     turns (when (and (= :agent (:mode @agent/state)) (not (:within req))) (agent/turns))
                                     r (again code (cond-> (repaired (response @answers handle) req)
                                                      (:ns @agent/state) (assoc :ns (ns-name (:ns @agent/state)))
                                                      turns (assoc :turn turns))
                                           req)
                                      rec (record-details code r req)]
                                  [r rec (when-not (#{:model :insert} (:origin req)) (text-of rec))]))
                [r rec text] (try (@the-handler (assoc req "code" code :reply #(vswap! answers conj %)))
                              (answered)
                              (catch Throwable e
                                (let [em (agent/error-map e)]
                                  (vswap! answers conj {:tag :err :err em :err-text (nrepl/err->msg e)})
                                  (answered))))]
            (swap! agent/state assoc-in [:results (keyword (name handle))] r)
            ;; the handle a printed cut reads through, as the REPL's *1 is read
            (intern (create-ns 'result) (symbol (name handle)) r)
            ;; how main differs from the branch, said with the answer an outside agent's seon_eval gets, and never recorded
            ;; (seon.agent/main-difference): computed at t, so the agent is never surprised at done
            (let [fns (when (some :fns @answers) (into [] (mapcat :fns) @answers))
                  res (record! (cond-> r text (assoc :text text) fns (assoc :fns fns))
                               (cond-> rec fns (assoc :seon.eval/fns fns))
                               code req)
                  m (when (= :agent (:mode @agent/state)) (agent/main-difference))]
              (cond-> res m (assoc :main m))))
          (finally (swap! agent/state assoc :eval outer))))))

(defn- numbered
  "Lines from to (1-based, inclusive, clamped to text) of text, as [line text] each: a refused edit's error names its candidates by them (docs/agent.md#changing-a-definition)."
  {:malli/schema [:=> [:cat :string :int :int] [:vector [:tuple pos-int? :string]]]}
  [text from to]
  (let [ls (clojure.string/split-lines text)]
    (vec (for [i (range (max 1 from) (inc (min (count ls) to)))] [i (nth ls (dec i))]))))

(defn- offsets
  "Every offset at which s occurs in text, in order (an occurrence may not overlap the one before): where `edit!` finds its old text (docs/agent.md#changing-a-definition)."
  {:malli/schema [:=> [:cat :string :string] [:vector nat-int?]]}
  [text s]
  (loop [from 0 acc []]
    (if-let [i (clojure.string/index-of text s from)] (recur (+ i (max 1 (count s))) (conj acc i)) acc)))

(defn- differs
  "Where old text stops matching src from 1-based line c, as an edit reads it: old's first line ends that
  line (it may start mid-line), its middle lines are whole lines, its last begins its line.
  {:line :old :source}, the first that does not, with :indent [old's source's] leading spaces when they
  differ only by those; nil when every line holds. What an edit's :not-found names, so the writer
  sees the one line and how it differs, never the whole source again. See docs/agent.md#changing-a-definition."
  {:malli/schema [:=> [:cat [:vector :string] pos-int? [:vector :string]] [:maybe :map]]}
  [src-lines c old-lines]
  (let [n (count old-lines)
        lead (fn [s] (count (take-while #{\space} s)))]
    (some (fn [i]
            (let [o (nth old-lines i) s (get src-lines (+ c -1 i))
                  ok? (and s (cond (= 1 n) (clojure.string/includes? s o)
                                   (zero? i) (clojure.string/ends-with? s o)
                                   (= i (dec n)) (clojure.string/starts-with? s o)
                                   :else (= s o)))]
              (when-not ok?
                (cond-> {:line (+ c i) :old o :source s}
                  (and s (= (clojure.string/trim o) (clojure.string/trim s)) (not= (lead o) (lead s)))
                  (assoc :indent [(lead o) (lead s)])))))
          (range n))))

(defn edit!
  "Change the definition var sym names by replacing old, exact text that occurs once in its
  recorded source (`(source sym)`), with new, then evaluating the whole new definition in its
  namespace through the one REPL path (`eval-string`): it is recorded as program, its contract
  armed, as a typed defn would be. Given changes, a vector of [old new] pairs, every pair is checked
  so and all are applied together, then evaluated once: a change that must reach several places at
  once (a loop's bindings and each of its recurs) is one edit, since any of them alone does not
  compile. The new definition is read by the forgiving reader (seon.reader/forgive) as a call's code is:
  a delimiter it leaves open is closed at the definition's end, a stray one made a comma, and each repair is the
  answer's :repaired. Answers that eval's response with :edited, the var and the lines
  around each change; throws the evaluation's error when the new definition does not compile or load,
  its message saying nothing changed, so the eval it is called in answers it as its own :ex (an error nested in an edit's value
  could be cut from sight and read as a change that held; a caller
  outside an eval catches it, its ex-data the error map); or
  :seon.repl/edit-refused, nothing changed: no recorded source, an old not in it (with the lines that
  hold its first line, ignoring indentation), an old there more than once (each candidate's line), two olds that overlap (:overlap), or an empty old (:empty). Exact text and one occurrence, as file editors converged
  on (docs/agent.md#changing-a-definition). Given a doc's address (docs/x.md#heading) for sym, the same
  edit of the doc's own block (seon.doc/doc-source: its heading line and its text), its page loaded again
  on this branch with the new text in its place (seon.doc/edit-tx), so a doc changes as a definition does;
  no delimiter is counted in prose. Answers {:edited {:doc address :lines ...}} (docs/agent.md#changing-a-doc)."
  {:malli/schema [:function
                  [:=> [:cat [:or :qualified-symbol :string] :string :string] [:or :seon.repl/edit-refused :my.agent/no-var :seon.repl/answer :seon.repl/doc-edited]]
                  [:=> [:cat [:or :qualified-symbol :string] [:vector {:min 1} [:tuple :string :string]]] [:or :seon.repl/edit-refused :my.agent/no-var :seon.repl/answer :seon.repl/doc-edited]]]}
  ([sym old new] (edit! sym [[old new]]))
  ([sym changes]
   (let [doc? (string? sym)
         src (if doc? (doc/doc-source (agent/db) sym) (my.agent/source-fn sym))
         n (count changes)
         ;; which change a refused edit names, when there are several
         which (fn [k] (if (< 1 n) (str "change " (inc k) " of " n ": ") ""))
         refuse (fn [why msg lines] (contract/error :seon.repl/edit-refused #'edit! (str sym ": " msg)
                                                    (cond-> {:seon.repl/var sym :seon.repl/refused-because why} (seq lines) (assoc :seon.repl/lines lines))))
         line-of (fn [i] (:at (doc/line-at src i)))
         lines-at (fn [at] (mapv (fn [i] (let [l (line-of i)] [l (nth (clojure.string/split-lines src) (dec l))])) at))
         ;; each change checked against the recorded source: its one offset, or why it is refused
         check (fn [k [old new]]
                 (let [at (when (seq old) (offsets src old))]
                   (cond
                     (empty? old) {:refused (refuse :empty (str (which k) "the old text is empty: give the exact text to replace") nil)}
                     (empty? at) (let [first-line (clojure.string/trim (first (clojure.string/split-lines (str old " "))))
                                       cands (vec (for [[i l] (numbered src 1 Long/MAX_VALUE) :when (and (seq first-line) (clojure.string/includes? (clojure.string/trim l) first-line))] [i l]))
                                       ;; at each line that holds its first line, the one line where old stops matching, and how
                                       diffs (vec (keep (fn [[i _]] (differs (clojure.string/split-lines src) i (clojure.string/split-lines old))) cands))
                                       d (first diffs)]
                                   {:refused (cond-> (refuse :not-found (str (which k) "the old text is not in its source exactly (whitespace counts)"
                                                                             (if d (str ": at line " (:line d) " old has " (pr-str (:old d)) " and the source " (pr-str (:source d))
                                                                                        (when-let [[a b] (:indent d)] (str " (" a " leading spaces, the source " b ")")))
                                                                                 "; (source f) shows it as recorded"))
                                                             cands)
                                               (seq diffs) (assoc :seon.repl/differs diffs))})
                     (next at) {:refused (refuse :ambiguous (str (which k) "the old text occurs " (count at) " times: give more of it, so it is one") (lines-at at))}
                     :else {:at (first at) :old old :new new})))]
     (if (nil? src)
       ;; a name that is no var: the nearest names, as source and callers answer (my.agent/no-var)
       (if doc?
         (refuse :no-source "no doc at this address: (doc address) names the nearest" nil)
         (or (some-> (:db @agent/state) (my.agent/no-var sym)) (refuse :no-source "no recorded source: define it whole first" nil)))
       (let [cs (map-indexed check changes)
             ;; the changes in source order, and two whose old texts overlap
             ordered (sort-by :at (remove :refused cs))
             overlap (first (filter (fn [[x y]] (< (:at y) (+ (:at x) (count (:old x))))) (partition 2 1 ordered)))]
         (cond
           (some :refused cs) (:refused (first (filter :refused cs)))
           overlap (refuse :overlap "two changes' old texts overlap: make them one change, or give each its own text" (lines-at (map :at overlap)))
           :else
           (let [;; applied from the last, so each earlier offset still holds
                 text (reduce (fn [t {:keys [at old new]}] (str (subs t 0 at) new (subs t (+ at (count old))))) src (reverse ordered))
                 ;; the forgiving reader's repairs (a delimiter closed at its end, a stray one made a comma), said as a call's are
                 {text :text repairs :repairs} (if doc? {:text text} (reader/forgive text))]
           (let [;; a doc's page is loaded again with the new text in its place; a definition is evaluated
                 r (if doc?
                     (do (agent/transact! (doc/edit-tx (agent/db) sym text)) {})
                     (eval-string text {"ns" (namespace sym) :origin :model :within (:eval @agent/state)}))
                 k (cfg/value :seon.config.agent/edit-context-lines)
                 ;; each change's first line in the new text: the lines before it in the source, moved by the earlier changes
                 starts (second (reduce (fn [[shift acc] {:keys [at old new]}]
                                          [(+ shift (- (count (filter #{\newline} new)) (count (filter #{\newline} old))))
                                           (conj acc [(+ (line-of at) shift) new])])
                                        [0 []] ordered))]
             (if-let [ex (:ex r)]
               (throw (ex-info (str (:seon.error/message ex))
                               (update ex :seon.error/message
                                       #(str sym ": the edited definition does not compile or load, so nothing changed: " %
                                             ;; the line it names, in the definition as the edit made it
                                             (when-let [l (:clojure.error/line ex)]
                                               (str "; at line " l (some->> (:clojure.error/column ex) (str ", column ")) " of the new definition: "
                                                    (pr-str (clojure.string/trim (nth (clojure.string/split-lines text) (dec l) "")))))))))
               ;; the response as data: its printed text and costliest functions are the outer eval's to print
               (cond-> (assoc (dissoc r :text :fns :record)
                              :edited {(if doc? :doc :var) sym :lines (into [] (comp (mapcat (fn [[from new]] (numbered text (- from k) (+ from (count (filter #{\newline} new)) k)))) (distinct)) starts)})
                 (seq repairs) (assoc :repaired (context/repaired text repairs))))))))))))

(defn unrun
  "A model's eval call that never ran, recorded as an eval whose response is error e, why
  (seon.turn's: a call before it in its reply threw, or the call has no intent or no form), so
  every call of a reply has its one result. See docs/prompt.md."
  {:malli/schema [:=> [:cat :string :map :seon.error/base] :seon.repl/answer]}
  [code req e]
  (let [handle (symbol "result" (str "e" (eval-n!)))
        r (repaired (response [{:tag :err :err e}] handle) req)
        rec (record-details code r req)]
    (swap! agent/state assoc-in [:results (keyword (name handle))] r)
    (record! (cond-> r (not (#{:model :insert} (:origin req))) (assoc :text (text-of rec))) rec code req)))

(defn- call-problem
  "Why an eval call, its intent and its code, is not the eval tool's shape, as the error its one
  result is, or nil: a call is intent and one or more forms (docs/prompt.md).
  Code that does not read is left to the REPL, whose read error says where."
  {:malli/schema [:=> [:cat [:maybe :string] :string] [:maybe :seon.turn/invalid-call]]}
  [intent code]
  (let [forms (reader/read-text code 'user)]
    (cond
      (clojure.string/blank? intent)
      (contract/error :seon.turn/invalid-call #'call-problem
                      "this call has no intent: send intent, a little thinking (a sentence is fine: what these forms are about to do and what you expect), beside code; the form was not run" {:problem :no-intent})
      (and forms (empty? forms))
      (contract/error :seon.turn/invalid-call #'call-problem
                      "this call has no form: its code is empty; nothing was run" {:problem :forms :forms 0}))))

(defn run-calls
  "Eval calls ({:id :intent :code}, a reply's or an insert's: docs/turn.md#an-eval-can-be-inserted) through the one REPL
  path in order, each answered as its own result: {:id :code :intent :not-run :response}. req-of is a call's id to its
  request (its origin, and its call id when a model made it); eval! runs code under a request and answers its response. A call with no
  intent or no form, or after one that threw or answered an error (a refused edit), is not run: `unrun` records
  its one result, which says why, so none builds on a change that never happened and every call has its result."
  {:malli/schema [:=> [:cat [:sequential :map] ifn? ifn?] [:vector :map]]}
  [calls req-of eval!]
  (reduce (fn [acc {:keys [id code intent]}]
            (let [;; the forgiving reader's repairs, told on this call's own result (:repaired)
                  {src :text repairs :repairs} (reader/forgive code)
                  ;; each call keeps its own intent
                  req (cond-> (req-of id)
                        (not (clojure.string/blank? intent)) (assoc :intent intent)
                        (seq repairs) (assoc :repaired (context/repaired src repairs)))
                  failed (some #(when (or (:ex (:response %)) (contract/error? (:value (:response %)))) (:id %)) acc)
                  why (if failed
                        (contract/error :seon.turn/not-run #'run-calls (str "not run: call " failed " of this reply failed before it (its result says why)") {:after failed})
                        (call-problem intent src))]
              (conj acc (if why
                          {:id id :code code :intent intent :not-run (some? failed)
                           :response (let [r (unrun src req why)] (agent/commit!) r)}
                          {:id id :code code :intent intent :response (eval! src req)}))))
          [] calls))

(defn insert!
  "Evals, pairs of intent and code, run now on this agent's REPL queue as calls of nobody's (`run-calls`), each recorded
  as an eval like any other (origin :insert, :seon.eval/by who asked), under the turn in force; one that fails stops the rest.
  Answers the responses. See docs/turn.md#an-eval-can-be-inserted."
  {:malli/schema [:=> [:cat :string [:sequential [:tuple :string :string]]] [:vector :map]]}
  [who evals]
  (mapv :response
        (run-calls (vec (map-indexed (fn [i [intent code]] {:id (str "insert-" i) :intent intent :code code}) evals))
                   (fn [_] {:origin :insert :by who})
                   (fn [code req] (let [r (eval-string code req)] (agent/commit!) r)))))

(defn- layout
  "The layout this branch renders its ctx with: the var :seon.render/render-ctx declares, an agent's own
  when its branch defines one."
  {:malli/schema [:=> [:cat] :any]}
  [] (render/declared-var :seon.render/render-ctx))

(defn- the-walk
  "This agent's walk of its render of ctx (seon.render/walker over @ctx, its diff seon.ctx/changes, and the
  branch's db), held in its state: the one cache of its components, made again when the branch declares another
  layout (docs/ctx.md#the-record-is-the-agents-work). It draws what its model reads (seon.context/page-lists) until a
  person's page asks for the rest (`whole-walk`): the lists nobody reads cost nothing."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [held (:walk @agent/state) l (layout)]
    (if (and held (= l (first (:root held))))
      held
      (let [w (cond-> (render/walker {:ref ctx/ctx :changes ctx/changes} agent/db [l {}])
                (or (nil? held) (:only held)) (assoc :only (set context/page-lists)))]
        (some-> held render/dispose-walk!)
        (swap! agent/state assoc :walk w)
        w))))

(defn page-walk
  "This agent's page walk drawn up to now (seon.render/flush!), under its branch's schemas: the
  components ctx's patches reached, and the db renders whose reads the datoms its db moved by since
  the last draw touched (seon.ctx/take-moved!)."
  {:malli/schema [:=> [:cat] :map]}
  []
  (with-branch-schemas #(let [w (the-walk)] (render/flush! w (ctx/take-moved!)) w)))

(defn- whole-walk
  "`page-walk` with every list drawn, for a reader of the whole page: a person's, who stays its reader from then on (a
  walk holds no count of who is watching). See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat] :map]}
  []
  (with-branch-schemas #(let [w (the-walk)] (when (:only w) (swap! agent/state assoc :walk (render/draw-all w)))))
  (page-walk))

(defn render-ctx
  "This agent's render of ctx as its components' html (root's render proc asks, its {:op :render}:
  seon.render.proc/ask-parts): {:t its ctx's t :units [{:id :container :rev :html}]}, each
  component in the list it is placed in, its revision moved only when its output changed. Records
  nothing (the :render op takes the facts main holds of its own entities first)."
  {:malli/schema [:=> [:cat] [:map [:t :int] [:units [:vector :map]]]]}
  []
  {:t (or (:t @ctx/ctx) 0) :units (render/placed-units (whole-walk))})

(defn render-ctx-hiccup
  "This agent's render of ctx as the user sees it: every component of its three lists (its task's,
  its conversation, each eval its one line until opened, and its tiles), each one's hiccup exactly
  as its render returned it, by its element id, in order: the hiccup render-ctx's html is written
  from, nothing left out."
  {:malli/schema [:=> [:cat] :map]}
  [] (render/placed-hiccup (whole-walk) ["#p-task" "#c-rows" "#p-tiles"]))

(defn page-hiccup
  "This agent's parts as its model reads them now, from its live walk (seon.context/page-lists): each
  component run again only where what it read changed, so a turn's parts cost what changed (a cold
  draw, seon.context/drawn-parts, costs every component). [db parts]: the db it is drawn to, which the request is
  made on, and {element-id hiccup}, in order (seon.context/request sends what changed of them)."
  {:malli/schema [:=> [:cat] [:tuple :seon.host/db :map]]}
  [] (let [w (page-walk)] [(agent/db) (render/placed-hiccup w context/page-lists)]))

(defn page-data
  "The data of this agent's parts now, from its live walk, as `page-hiccup` draws it: {schema-key data}
  (seon.render/placed-data), what the turn decides from, with no model render made for it. See
  docs/render.md#a-part-answers-data."
  {:malli/schema [:=> [:cat] :map]}
  [] (render/placed-data (page-walk) context/page-lists))

;; ---- the process: a Flow graph of three procs on the agent's one thread ----------------

(defn- told-refused
  "Replies rs, the first one that answers an eval (a map :response) carrying what the branch refused since the last answer
  under :unrecorded (seon.agent/take-refused!); rs as it was when none refused or none answers an eval (it waits
  for the next); nil when the request has no reply (a clone). The commit that follows a request is where its writes are
  refused, after its answer was made.
  See docs/failure-points.md."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]]] [:maybe [:sequential :map]]]}
  [rs]
  (let [i (first (keep-indexed #(when (map? (:response %2)) %1) rs))]
    (if-let [e (and i (agent/take-refused!))]
      (assoc-in (vec rs) [i :response :unrecorded] e)
      rs)))

(defn agent-step
  "The agent's :repl proc: the requests the :transport proc hands it, one at a time in arrival
  order: an eval ({:op :eval :id :forms}), its page's parts to draw ({:op :render :id}), Seon's
  ops that write its branch ({:op k ...}: :judge, :merge-main, :clone) and root's :stop, after
  which the process ends. It sends its replies to root itself (seon.agent/send!, one whole frame)
  and commits what a request applied. Its REPL state is the process's one state map
  (seon.agent/state), which the code it evaluates reads, so the step's own state is empty. See
  docs/processes.md#the-agent-process."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:in "the requests that run at the REPL or write the branch"} :outs {} :workload :io
       :ping-map-fn (fn [_] {:evals (:n @agent/state)})})
  ([_] {})
  ([st _] st)
  ([st _ m]
   (swap! agent/state assoc :busy true)
   (let [rs (cond
              ;; root's stop: the process ends after the requests before it, what they applied committed
              (= :stop (:op m)) (do (when (:dirty? @agent/state) (agent/commit!)) (lib/c-exit 0))
              ;; root's render proc asks for this agent's page: its parts, drawn here under its branch's
              ;; schemas by the renders it defines, answered as HTML, and nothing recorded
              ;; the facts main holds of its own entities taken first (an eval a reader opened): what it draws is them
              (= :render (:op m))
              [{:op :reply :id (:id m) :response (do (when-let [tx (seq (agent/untaken-facts))] (agent/transact! (vec tx)) (agent/commit!))
                                                     (render-ctx))}]
              (= :eval (:op m))
              (do
                ;; an interrupt left from before this request is not its; then root hears that it began
                ;; (its proc's :started event), what an interrupt or a kill meant for a running eval
                ;; waits on, never a sleep: one arriving from here on stops it
                (intr/clear-pending!)
                (agent/send! {:op :started :id (:id m)})
                ;; its value goes with the reply only when it is plain data (seon.contract/plain?: what the socket and the wire carry);
                ;; root keeps none of it (its view keeps the head: seon.root/eval-head), and the asker reads the value as data,
                ;; never the printed text back, which is no EDN once the eval printed (its :out verbatim)
                [{:op :reply :id (:id m) :response (let [r (eval-string (:forms m) (select-keys m [:ticks :origin]))] (agent/on-the-wire r))
                  :t (:max-tx (agent/db))}])
              ;; evals given at its spawn (seon.root/spawn!'s :evals) or inserted later: run on this queue, in order, answering no one
              (= :evals (:op m)) (do (intr/clear-pending!) (insert! (:by m) (:evals m)) [])
              :else (agent/handle! m))]
     ;; a branch is committed whenever a request is done (its writes ran ahead in memory), before its answer: a
     ;; reply says what it did is on the branch, so one who reads the branch on hearing it never races the commit
     (when (:dirty? @agent/state) (agent/commit!))
     (doseq [r (told-refused rs)] (agent/send! r))
     (swap! agent/state assoc :busy false)
     [st nil])))

(defn tests-step
  "The agent's :tests proc: root's :tests requests, one at a time, each run on this proc's fiber
  (seon.agent/handle!'s :tests: the plan, the forked children read to their end, the results
  recorded on the branch) and answered, whatever the :repl proc is running: a prove! waits for
  root's answer while this proc serves root's request for the run, and the run's
  work is this fiber's, never charged to the eval that waits. See
  docs/processes.md#the-agent-process."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {:in "root's :tests requests"} :outs {} :workload :io :ping-map-fn (fn [_] {})})
  ([_] {})
  ([st _] st)
  ([st _ m]
   ;; its results committed when the REPL is idle, before their answers; a request it runs commits them with its
   ;; own at its end, never half of it from here
   (let [rs (agent/handle! m)]
     (when (and (:dirty? @agent/state) (not (:busy @agent/state))) (agent/commit!))
     (doseq [r rs] (agent/send! r)))
   [st nil]))

(def ^:private answers
  "The ops of root's answers to the agent's own requests, each carrying the request's :k
  (seon.agent/await-reply). See docs/processes.md#the-agent-process."
  #{:reply :fx-reply :fd :branched})

(def ^:private at-once
  "The ops the :transport proc handles itself, whatever the :repl proc runs: they write nothing
  of the branch but the inbox (seon.agent/handle!): a delivery, a stats read, an effect's outcome
  nobody awaits (fx-send!'s, or one whose wait an interrupt stopped), an answer nobody awaits.
  See docs/processes.md#the-agent-process."
  #{:message :stats :fx-reply :reply :fd :branched})

(defn transport-step
  "The agent's :transport proc, the one reader of root's socket: root's frames arrive on its
  in-port (seon.agent/intake), and each goes where it is owed. An answer to the agent's own
  request goes to the promise its wait holds under its :k (seon.agent/await-reply); root's :tests
  to the :tests proc; a delivery, a stats read and an outcome nobody awaits are handled here at
  once; root's :pause and :resume pause and resume the :repl proc (Flow's own); every other
  request, :stop among them, goes to the :repl proc, in arrival order. The end of root's frames
  ends the process. See docs/processes.md#the-socket-and-the-agent-proc."
  {:malli/schema :seon.step/step-fn}
  ([] {:ins {} :outs {:repl "requests that run at the REPL or write the branch" :tests "root's test runs"} :workload :io
       :ping-map-fn (fn [_] {:waits (count (:waits @agent/state))})})
  ([_] {::flow/in-ports {:from (agent/intake)}})
  ([st _] st)
  ([st _ m]
   (let [op (:op m)
         p (when (answers op) (get-in @agent/state [:waits (:k m)]))]
     (cond
       ;; root's end: the process ends with it (EOF, or root's end frame)
       (nil? m) (lib/c-exit 0)
       p (do (deliver p m) [st nil])
       (= :tests op) [st {:tests [m]}]
       (at-once op) (do (doseq [r (agent/handle! m)] (agent/send! r)) [st nil])
       (= :pause op) (do (flow/pause-proc (:flow @agent/state) :repl) [st nil])
       ;; a paused proc reads only its control channel: a stop resumes the REPL first, so it ends the
       ;; process after the requests before it, paused or not
       (= :stop op) (do (flow/resume-proc (:flow @agent/state) :repl) [st {:repl [m]}])
       (= :resume op) (do (flow/resume-proc (:flow @agent/state) :repl) [st nil])
       :else [st {:repl [m]}]))))

(defn defect-answer
  "The answer root is owed for request m, whose step threw e (a defect no request's own boundary
  caught: the agent may redefine a function its procs call): the reply of m's op carrying the one
  error map (seon.agent/error-map), so its asker gets the error as a value and the agent goes on;
  nil for a message nobody waits on. See docs/processes.md#the-agent-process."
  {:malli/schema [:=> [:cat :map :seon.host/throwable] [:maybe :seon.wire/seon-msg]]}
  [m e]
  (let [em (agent/error-map e 'seon.repl/serve!)]
    (case (:op m)
      (:eval :render) {:op :reply :id (:id m) :response {:ex em}}
      (:tests :judge) {:op :tests :id (:id m) :result em}
      :stats {:op :stats :id (:id m) :result em}
      :merge-main {:op :merged-main :id (:id m) :result em}
      :clone {:op :clone-failed :k (:k m) :error em}
      nil)))

(defn serve!
  "The agent's loop: a Flow graph of three :io procs on its one thread (docs/flow.md): the
  :transport proc, the one reader of root's socket, hands the :repl proc root's requests in
  arrival order and the :tests proc root's test runs. A step that throws (Flow's error channel)
  has its request answered with the error (defect-answer), what the request applied committed,
  and the agent goes on. Never returns: the process ends at root's end (the transport's) or at
  root's :stop."
  {:malli/schema [:=> [:cat] :any]}
  []
  ;; the :repl and :tests in-ports each hold what root may have queued for the agent, as root's end of the
  ;; socket does: at the bound the transport waits to hand on the next (docs/flow.md#flow-inside-an-agent)
  (let [in {:in {:buf-or-n (cfg/value :seon.config.agent/inbox-size)}}
        g (flow/create-flow {:procs {:transport {:proc (flow/process #'transport-step)}
                                     :repl {:proc (flow/process #'agent-step) :chan-opts in}
                                     :tests {:proc (flow/process #'tests-step) :chan-opts in}}
                             :conns [[[:transport :repl] [:repl :in]] [[:transport :tests] [:tests :in]]]})
        {:keys [error-chan]} (flow/start g)]
    (swap! agent/state assoc :flow g)
    (flow/resume g)
    (loop []
      (let [e (a/<!! error-chan) m (::flow/msg e)]
        (sock/write-line! 2 (print/text (agent/error-map (::flow/ex e) 'seon.repl/serve!) (:part print/budgets)))
        ;; the answer first here: it reports a failure, not a write, and the failure may be the commit's
        (when-let [r (when (map? m) (defect-answer m (::flow/ex e)))] (agent/send! r))
        (when (= :repl (::flow/pid e)) (swap! agent/state assoc :busy false))
        (when (:dirty? @agent/state) (agent/commit!))
        (recur)))))

(defn run!
  "The agent's body, in the child of the fork server; fd is its socket to root. Never
  returns."
  {:malli/schema [:=> [:cat :seon.lib/fd :map :seon.host/db :int] :any]}
  [fd m db0 t-req-ns]
  ;; the agent's whole body is one fiber on its one thread (jolt.host/main-carrier-run!): its
  ;; waits park, its go blocks, flows and remote channels run beneath it, and it starts no
  ;; thread, so it still forks its test children safely (design agent-loop section 8a)
  (jolt.host/main-carrier-run!
    (fn []
      ;; code the agent evals is compiled with Jolt's tail-frame history, as `jolt` from source does
      ;; (a built binary's own code has it baked in): a fn called in tail position leaves no frame,
      ;; and the history is what names it in an error's position (`located`, docs/errors.md#an-eval-errors-diagnostics)
      (jolt.host/enable-trace!)
      (agent/init! fd m db0 serve!)
      (agent/hello! t-req-ns (cond-> {:interrupt-ok (:interrupt-ok @agent/state)}
                                (seq (:load-errors @agent/state)) (assoc :load-errors (:load-errors @agent/state))
                                (:misaligned @agent/state) (assoc :misaligned (:misaligned @agent/state))
                                (:unwatched @agent/state) (assoc :unwatched (:unwatched @agent/state))))
      (serve!))))
