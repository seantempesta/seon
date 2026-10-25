(ns my.agent
  "An agent's own functions (from proto/flow f2-final proto.agent's agent API). Referred into
  every agent namespace.
    ($ handle)                an earlier eval's response, its live value under :value (also result/e3)
    (whoami)                  {:id :branch :pid}
    (messages)                the messages delivered and not yet taken, taken
    (send-message tos body)   body to the agents tos (ids), delivered by root
    (callers 'ns/f)           the program vars whose recorded definition names ns/f
    (callees 'ns/f)           the vars ns/f's recorded definition names
    (grep "text")             the program vars whose recorded definition's text holds it
    (similar 'ns/f)           what is nearest ns/f's definition by meaning: vars, wiki sections, tasks
    (similar "text")          what is nearest the text by meaning (seon.vector/near)
    (related "text" k)        the k wiki sections nearest the text, a line each, and how to read one:
                              refine what the screen's (my.task/related) showed
    (prove)                   run what judges this branch: the task's proofs and the tests reaching
                              what it changed since main, each one with a result at its digest reused
    (help)                    the help page's sections (docs/agent.md); (help "done") one whole;
                              (help "docs/vector.md#the-model") any section, (help "docs/vector.md") a page
    clojure.repl as at any Clojure REPL (source, doc, dir, apropos, find-doc, pst), with *1 *2 *3 *e:
    source reads this branch's own definitions first, then a library's file"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.lib :as lib] [seon.context :as context] [seon.ref :as ref] [seon.docs :as docs]
            [seon.config :as cfg] [seon.proofs :as proofs] [seon.task :as task] [seon.vector :as vector] [seon.contract :as contract]
            [clojure.java.io :as io] [clojure.string :as str] [clojure.main] [datahike.api :as d]))

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

(defn callers
  "The program vars whose recorded definition names var sym (its :form/refs on this branch),
  sorted; sym itself left out. sym is a qualified symbol or the var (named)."
  {:malli/schema [:=> [:cat [:or :qualified-symbol :seon.host/var]] [:vector :qualified-symbol]]}
  [sym] (docs/callers (agent/db) (named sym)))

(defn callees
  "The vars var sym's recorded definition names on this branch (its :form/refs: program,
  library and clojure.core alike), sorted; sym itself left out. sym is a qualified symbol or
  the var (named)."
  {:malli/schema [:=> [:cat [:or :qualified-symbol :seon.host/var]] [:vector :qualified-symbol]]}
  [sym]
  (let [sym (named sym)]
    (vec (sort (disj (set (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]]
                               (agent/db) sym))
                     sym)))))

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
           :else (context/related-view db (mapv #(:seon.section/address (d/entity db (first %)))
                                                (vector/near db q k {:of :seon.section/address})))))))

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
  "The program vars whose recorded definition's text (:form/src on this branch) holds the
  string s, sorted: a text search over the program as the branch holds it."
  {:malli/schema [:=> [:cat :string] [:vector :qualified-symbol]]}
  [s]
  (vec (sort (d/q '[:find [?n ...] :in $ ?s :where [?f :form/src ?src] [(clojure.string/includes? ?src ?s)]
                    [?f :form/defines ?v] [?v :var/name ?n]]
                  (agent/db) s))))

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
  it (its requires and aliases), which clojure.repl's does not. sym is a symbol or the var
  (named)."
  {:malli/schema [:=> [:cat [:or :symbol :seon.host/var]] [:maybe :string]]}
  [sym]
  (if-let [v (if (var? sym) sym (resolve sym))]
    (or (:source (meta v))
        (docs/source-of (agent/db) (named v))
        (file-source v))
    (when-not (namespace sym)
      (d/q '[:find ?src . :in $ ?s :where [?n :ns/name ?s] [?f :form/defines ?n] [?f :form/src ?src]] (agent/db) sym))))

(defn not-found
  "What source prints for sym when it names nothing: Clojure's words; when its namespace part
  names a namespace, that no such var is defined here or recorded on this branch (a name a
  plan gives a function to write is not one yet: step E's paid run 4, whose design and
  implement asked the source of a planned function until they ended :no-progress), how to
  write it there, and the form that shows that namespace's ns form."
  {:malli/schema [:=> [:cat :symbol] :string]}
  [sym]
  (if-let [n (some-> (namespace sym) symbol find-ns)]
    (str "Source not found for " sym ": no such var is defined or recorded on this branch. If your plan names it as new, "
         "write it: (in-ns '" (ns-name n) ") (defn " (name sym) " ...). Its namespace's ns form: (source " (ns-name n) ")")
    (str "Source not found for " sym)))

(defn deferred
  "The process tests this branch's change reaches, a proof among them: an agent's turn and
  root's judge never run them, root's gate runs them when root asks (my.root/gate!; DECISIONS,
  Process tests are not automatic). seon.agent/test-plan's :process among what it selects: names."
  {:malli/schema [:=> [:cat] [:vector :qualified-symbol]]}
  [] (let [proofs (or (some->> (:task @agent/state) (task/task (agent/db)) :seon.task/accumulated :proofs) #{})
           plan (agent/test-plan (agent/db) (:base @agent/state) proofs)]
       (vec (filter (:process plan) (:selected plan)))))

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
    `(let [s# (named ~x)] (println (or (source-fn s#) (not-found s#))))))

(defn help
  "The wiki through the one view (seon.docs/view), as this branch holds it. No argument: the help
  page (seon.context/help-page) at summary, each section its address, heading and lead's first
  sentence, with the sections it cites and those citing it. With an address (``docs/vector.md#the-model``) or
  an anchor of the help page (\"done\"), that section whole; with a page's path (\"docs/vector.md\"), that page
  at summary. A miss answers the help page's sections, every page's path and the form that reads
  one, never nil. (Before this an anchor answered its text alone; it answers the view, :seon.ref/text among its keys.)"
  {:malli/schema [:function [:=> [:cat] [:vector :seon.ref/view]] [:=> [:cat :string] [:or :seon.ref/view [:vector :seon.ref/view] [:map [:no-section :string] [:help-page [:vector :seon.ref/view]] [:pages [:vector :string]] [:read-one :string]]]]]}
  ([] (vec (docs/page (agent/db) context/help-page :summary)))
  ([address]
   (let [db (agent/db)
         a (if (str/includes? address "#") address (str context/help-page "#" address))]
     (or (docs/view db a :code)
         (docs/page db address :summary)
         {:no-section address
          :help-page (vec (docs/page db context/help-page :summary))
          :pages (docs/page-paths db)
          :read-one "(my.agent/help \"docs/<page>.md\") lists a page's sections; (my.agent/help \"docs/<page>.md#<section>\") reads one"}))))

