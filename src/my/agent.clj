(ns my.agent
  "An agent's own functions (from proto/flow f2-final proto.agent's agent API). Referred into
  every agent namespace.
    ($ handle)                an earlier eval's response, its live value under :value (also result/e3)
    (whoami)                  {:id :branch :pid}
    (messages)                the messages delivered and not yet taken, taken
    (send-message tos body)   body to the agents tos (ids), delivered by root
    (callers 'ns/f)           the program vars whose recorded definition names ns/f
    (callers :ns/key)         the functions whose code or contract names the key (an attribute, config, schema)
    (callees 'ns/f)           the vars ns/f's recorded definition names
    (grep "text")             the program vars whose recorded definition's text holds it
    (similar 'ns/f)           what is nearest ns/f's definition by meaning: vars, wiki sections, tasks
    (similar "text")          what is nearest the text by meaning (seon.vector/near)
    (related "text" k)        the k wiki sections nearest the text, a line each, and how to read one:
                              refine what the screen's (my.task/related) showed
    (prove)                   run what judges this branch: the task's proofs and the tests reaching
                              what it changed since main, each one with a result at its digest reused
    @ctx                      this agent's record as a map: its task, runs, turns, messages and evals,
                              kept current as it works; the page shows it as a block
    (help 'ns.name)           a namespace: every definition, privates included, a line each in file order
    (help)                    the help page's sections (docs/agent.md); (help "done") one whole;
                              (help "docs/vector.md#the-model") any section, (help "docs/vector.md") a page
    clojure.repl as at any Clojure REPL (source, doc, dir, apropos, find-doc, pst), with *1 *2 *3 *e:
    source reads this branch's own definitions first, then a library's file"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.lib :as lib] [seon.context :as context] [seon.ref :as ref] [seon.docs :as docs]
            [seon.config :as cfg] [seon.proofs :as proofs] [seon.program :as program] [seon.task :as task] [seon.vector :as vector] [seon.contract :as contract] [seon.ctx]
            [seon.db :as pdb] [jolt.analyzer] [clojure.java.io :as io] [clojure.string :as str] [clojure.main] [datahike.api :as d]))

(defn $ "An earlier eval's response (its live value under :value), by handle (:e3)." {:malli/schema [:=> [:cat :keyword] :any]}
  [h] (get-in @agent/state [:results h]))
(defn whoami "This agent: {:id :branch :pid}." {:malli/schema [:=> [:cat] [:map [:id :seon.agent/id] [:branch :seon.db/branch] [:pid :seon.lib/pid]]]}
  [] {:id (:id @agent/state) :branch (:branch @agent/state) :pid (lib/c-getpid)})
(defn messages "The messages delivered to this agent and not yet taken ({:from :body}), taken." {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (agent/messages))
(defn send-message "Send body to the agents tos (ids); root delivers it by id. nil." {:malli/schema [:=> [:cat [:sequential :seon.agent/id] :any] :nil]}
  [tos body] (agent/send-message tos body))

(defn named
  "The symbol x names a definition by: x as it is, or a var's ns-qualified name (what a REPL hands
  a function of a name when its caller passes #'f or a value of (resolve 'f))."
  {:malli/schema [:=> [:cat [:or :symbol :seon.host/var]] :symbol]}
  [x] (if (var? x) (symbol (str (ns-name (:ns (meta x)))) (str (:name (meta x)))) x))

(defn- asked
  "The name sym is asked by: the var it resolves to in the agent's namespace (an alias, 'str/join,
  read as clojure.string/join, the name its callers' facts record), else sym as written."
  {:malli/schema [:=> [:cat :qualified-symbol] :qualified-symbol]}
  [sym] (let [v (ns-resolve *ns* sym)] (if (var? v) (named v) sym)))

(defn no-var
  "The one answer for a name sym that is no var on db nor in this process (a library's var is
  one), else nil: what callers, callees and source say of it, never [] or silence. It names the
  nearest existing names by Jolt's own did-you-mean ranking (jolt.analyzer/suggestions-for): the
  definitions of sym's namespace, or, when that namespace is none, the nearest namespaces; the form
  for the registry key of that name when db has one; and \"write it\" only when this agent's task
  cites sym in its plan (a plan's new function, not yet written; in-ns makes a new namespace).
  A namespace's name that names one is not missing (source answers what it records)."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:maybe :my.agent/no-var]]}
  [db sym]
  (when-not (or (pdb/eid db :var/name sym) (var? (ns-resolve *ns* sym))
                (and (not (namespace sym)) (or (find-ns sym) (pdb/eid db :ns/name sym))))
    (let [nsym (some-> (namespace sym) symbol)
          view (some->> nsym (#(ref/namespace-view db % :doc)))
          near (fn [s xs] (mapv symbol (jolt.analyzer/suggestions-for s (map str xs))))
          nss (fn [] (concat (map ns-name (all-ns)) (d/q '[:find [?n ...] :where [_ :ns/name ?n]] db)))
          nearest (cond view (near (str sym) (map :seon.ref/address (:seon.ref/below view)))
                        nsym (near (str nsym) (nss))
                        :else (near (str sym) (concat (nss) (keys (ns-map *ns*)))))
          k (when nsym (keyword (namespace sym) (name sym)))
          key? (and k (or (contains? (d/schema db) k) (pdb/eid db :schema/key k)))
          ;; the plan as it stands (a design rewrites it with update!), read by the one parser
          cited? (contains? (set (some->> (:task @agent/state) (task/task db) :seon.task/plan ref/names)) sym)]
      (contract/error :my.agent/no-var (symbol #'no-var)
                      (str sym (cond view " names no var on this branch"
                                         nsym (str " names no namespace on this branch (" nsym ")")
                                         :else " names no var or namespace here")
                           (when (seq nearest) (str "; nearest: " (str/join ", " nearest)))
                           (when view (str "; every definition of " nsym ", a line each: (source " nsym ")"))
                           (when key? (str "; " k " is a registry key: (my.agent/callers " k ")"))
                           (when cited? (str "; your task cites it, so write it: (in-ns '" nsym ") (defn " (name sym) " ...)")))
                      (cond-> {:name sym :nearest nearest} key? (assoc :ask (str "(my.agent/callers " k ")")))))))

(defn callers
  "The program vars whose recorded definition names x on this branch, sorted, x itself left out
  (seon.docs/callers): a var's callers (x a qualified symbol, or the var: named), or the functions
  operating on a qualified keyword, an attribute, config key or schema entry (its :form/keywords).
  A symbol through an alias reads as the var it resolves to; one that names no var answers so
  (:my.agent/no-var), never []. A keyword nothing names answers [] (no use is the true answer)."
  {:malli/schema [:=> [:cat [:or :qualified-symbol :seon.host/var :qualified-keyword]] [:or [:vector :qualified-symbol] :my.agent/no-var]]}
  [x]
  (let [db (agent/db) x (cond (keyword? x) x (var? x) (named x) :else (asked x))]
    (or (when (symbol? x) (no-var db x)) (docs/callers db x))))

(defn callees
  "The vars var sym's recorded definition names on this branch (its :form/refs: program,
  library and clojure.core alike), sorted; sym itself left out. sym is a qualified symbol or
  the var (named). A symbol that names no var answers so (:my.agent/no-var), never []."
  {:malli/schema [:=> [:cat [:or :qualified-symbol :seon.host/var]] [:or [:vector :qualified-symbol] :my.agent/no-var]]}
  [sym]
  (let [db (agent/db) sym (if (var? sym) (named sym) (asked sym))]
    (or (no-var db sym)
        (vec (sort (disj (set (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]] db sym))
                         sym))))))

(defn- query-of
  "x's vector on db: a var's definition's (its entity, whose vector near reads), or text embedded
  as a search's query by root's :embed effect (a failed embedding its error); nil for a var with
  no vector yet."
  {:malli/schema [:=> [:cat :seon.host/db [:or :qualified-symbol :string]] :any]}
  [db x]
  (if (symbol? x)
    (some-> (d/entity db [:var/name x]) :var/form :db/id)
    (let [r (agent/fx! :embed {:texts [(str vector/query-prompt x)]} {})]
      (if (contract/error? r) r (first (:vectors (:value r)))))))

(defn similar
  "What on this branch is nearest x by meaning (seon.vector/near): x a var, by its definition's
  vector, or text, embedded as a search's query by root's :embed effect. [[name score] ...] best
  first, k of them (10 unless given), each name a var, a wiki section's address or a task's id;
  a var with no vector yet answers [], and a failed embedding its error."
  {:malli/schema [:function [:=> [:cat [:or :qualified-symbol :string]] [:or [:vector [:tuple :any number?]] :map]]
                            [:=> [:cat [:or :qualified-symbol :string] nat-int?] [:or [:vector [:tuple :any number?]] :map]]]}
  ([x] (similar x 10))
  ([x k]
   (let [db (agent/db)
         q (query-of db x)
         name-of (fn [e] (let [x (d/entity db e)]
                           (or (some-> (:var/_form x) first :var/name) (:seon.section/address x) (:seon.task/id x) e)))]
     (cond (contract/error? q) q
           (nil? q) []
           :else (mapv (fn [[e s]] [(name-of e) s]) (vector/near db q k))))))

(defn related
  "The wiki sections nearest x by meaning, a text (embedded as a search's query by root's :embed
  effect) or a var's definition, k of them (:seon.config.render/related unless given), each a
  line at summary depth with the forms that read one whole and search further
  (seon.context/related-view): how an agent refines, in its own words, what its screen's
  (my.task/related) showed. A var with no vector yet says so; a failed embedding answers its
  error."
  {:malli/schema [:function [:=> [:cat [:or :qualified-symbol :string]] :map]
                  [:=> [:cat [:or :qualified-symbol :string] nat-int?] :map]]}
  ([x] (related x (cfg/value :seon.config.render/related)))
  ([x k]
   (let [db (agent/db) q (query-of db x)]
     (cond (contract/error? q) q
           (nil? q) {:not-embedded (str x " has no vector yet")}
           :else (context/related-view db {:sections (mapv #(:seon.section/address (d/entity db (first %)))
                                                           (vector/near db q k {:of :seon.section/address}))})))))

(defn prove
  "Run what root's judge will run on this branch (seon.agent/run-tests-forked :judge, in a fork
  of this agent): the task's proofs (or the given ones) and every in-process test reaching what
  the branch changed since the main it forked from, each test with a result at its reach digest
  reused (a red as recorded),
  not run; the process tests it reaches run only when root asks (:deferred). Answers that test run ({:pass :fail :error :reports :ran :reused :ms ...}) with :results
  {test :pass|:fail|:error}."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:set :qualified-symbol]] :map]]}
  ([] (prove (or (some->> (:task @agent/state) (task/task (agent/db)) :seon.task/accumulated :proofs) #{})))
  ([proofs]
   (let [test-run (agent/run-tests-forked (cfg/value :seon.config.agent/test-ticks) proofs :judge)]
     (assoc test-run :results (proofs/results test-run)))))

(defn grep
  "The program vars whose recorded definition on this branch holds the string s, best first, each
  {:var :line} (seon.docs/grep): a var whose name holds it, then one whose docstring does, then the rest."
  {:malli/schema [:=> [:cat :string] [:vector [:map [:var :qualified-symbol] [:line :string]]]]}
  [s] (docs/grep (agent/db) s))

(defn- file-source
  "The text of the form at var v's :file and :line on the classpath (Clojure's clojure.repl/source-fn
  reads it the same way: a library's vars carry both), or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [v]
  (let [{:keys [file line]} (meta v)]
    (when-let [r (and file line (io/resource file))]
      (let [text (slurp r)
            off (reduce (fn [i _] (inc (str/index-of text "\n" i))) 0 (range (dec line)))]
        (second (read+string {:read-cond :allow} (java.io.PushbackReader. (java.io.StringReader. (subs text off)))))))))

(defn source-fn
  "The source of the var sym names, or nil: clojure.repl/source-fn. The text its last definition
  in this process kept (seon.repl), else what this branch records for it, else the form at its
  :file and :line (a library's). A namespace's name answers its ns form as this branch records
  it (its requires and aliases), then each definition, privates included, a line each in file
  order (seon.docs/definition-lines), which clojure.repl's does not (pipeline 1's F4: a design
  that asked (source ns) saw the ns form alone, took it for all that was recorded, and spent five
  turns finding the namespace's private functions). sym is a symbol or the var (named)."
  {:malli/schema [:=> [:cat [:or :symbol :seon.host/var]] [:maybe :string]]}
  [sym]
  (if-let [v (if (var? sym) sym (resolve sym))]
    (or (:source (meta v))
        (docs/source-of (agent/db) (named v))
        (file-source v))
    (when-not (namespace sym)
      ;; the branch's record is the answer: a namespace it does not record has no ns form or privates to list
      (let [db (agent/db)]
        (when-let [head (d/q '[:find ?src . :in $ ?s :where [?n :ns/name ?s] [?f :form/defines ?n] [?f :form/src ?src]] db sym)]
          (str head (some->> (docs/definition-lines (ref/namespace-view db sym :doc)) (str "\n"))))))))

(defn deferred
  "The process tests this branch's change reaches, a proof among them: an agent's turn and
  root's judge never run them, root's gate runs them when root asks (my.root/gate!; DECISIONS,
  Process tests are not automatic). seon.agent/test-plan's :process among what it selects, each
  with why: the process var it uses and the calls from the test to it (seon.program/process-path)."
  {:malli/schema [:=> [:cat] [:vector [:map [:test :qualified-symbol] [:uses [:vector :qualified-symbol]] [:through [:vector :qualified-symbol]]]]]}
  [] (let [proofs (or (some->> (:task @agent/state) (task/task (agent/db)) :seon.task/accumulated :proofs) #{})
           plan (agent/test-plan (agent/db) (:base @agent/state) proofs)]
       (vec (for [t (filter (:process plan) (:selected plan))] (assoc (program/process-path (:graph plan) t) :test t)))))

(defn pst
  "Print the exception e (*e by default) as clojure.repl/pst does: its message, then each cause's
  class and message."
  {:malli/schema [:function [:=> [:cat] :nil] [:=> [:cat :any] :nil]]}
  ([] (pst *e))
  ([e] (when (instance? Throwable e)
         (print (clojure.main/err->msg e))
         (doseq [{:keys [type message]} (rest (:via (Throwable->map e)))]
           (println "Caused by:" type message)))))

(defmacro source
  "Print the recorded source of the definition named by sym (as clojure.repl/source does). A
  name is taken as written; any other form (a quoted symbol, #'f, a local of a doseq) is
  evaluated to the symbol or var it holds, as the call reads naturally."
  [sym]
  (let [x (if (and (symbol? sym) (not (contains? &env sym))) (list 'quote sym) sym)]
    `(let [s# (named ~x)]
       (println (or (source-fn s#) (:seon.error/message (no-var (agent/db) s#)) (str "Source not found for " s#))))))

(defn help
  "The wiki through the one view (seon.docs/view), as this branch holds it. No argument: the help
  page (seon.context/help-page) at summary, each section its address, heading and lead's first
  sentence, with the sections it cites and those citing it. With an address (``docs/vector.md#the-model``) or
  an anchor of the help page (\"done\"), that section whole; with a page's path (\"docs/vector.md\"), that page
  at summary. A miss answers the help page's sections, every page's path and the form that reads
  one, never nil. (Before this an anchor answered its text alone; it answers the view, :seon.ref/text among its keys.)
  With a namespace's name ('seon.print.select), the namespace as a plan's citation of it shows it:
  its ns head and docstring, then each definition, privates included, in file order, a line each
  (its name and its doc's first sentence; seon.ref/namespace-view)."
  {:malli/schema [:function [:=> [:cat] [:vector :seon.ref/view]]
                  [:=> [:cat [:or :string :symbol]] [:or :seon.ref/view [:vector :seon.ref/view] [:maybe [:map [:seon.print/code :string]]] [:map [:no-section :string] [:help-page [:vector :seon.ref/view]] [:pages [:vector :string]] [:read-one :string]]]]]}
  ([] (vec (docs/page (agent/db) context/help-page :summary)))
  ([address]
   (if (symbol? address)
     (docs/cited-code (agent/db) {:seon.ref/name address :seon.ref/depth :doc :seon.ref/line 1})
   (let [db (agent/db)
         a (if (str/includes? address "#") address (str context/help-page "#" address))]
     (or (docs/view db a :code)
         (docs/page db address :summary)
         {:no-section address
          :help-page (vec (docs/page db context/help-page :summary))
          :pages (docs/page-paths db)
          :read-one "(my.agent/help \"docs/<page>.md\") lists a page's sections; (my.agent/help \"docs/<page>.md#<section>\") reads one; (my.agent/help 'ns.name) a namespace, every definition a line"})))))

