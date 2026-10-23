(ns my.agent
  "An agent's own functions (from proto/flow f2-final proto.agent's agent API). Referred into
  every agent namespace.
    ($ handle)                the live value of an earlier result (also result/h3)
    (whoami)                  {:id :branch :pid}
    (messages)                the messages delivered and not yet taken, taken
    (send-message tos body)   body to the agents tos (ids), delivered by root
    (callers 'ns/f)           the program vars whose recorded definition names ns/f
    (callees 'ns/f)           the vars ns/f's recorded definition names
    (grep "text")             the program vars whose recorded definition's text holds it
    (similar 'ns/f)           what is nearest ns/f's definition by meaning: vars, wiki sections, tasks
    (similar "text")          what is nearest the text by meaning (seon.vector/near)
    (prove)                   run what judges this branch: the task's proofs and the tests reaching
                              what it changed since main, each green one at its digest reused
    (help)                    the help page's sections (docs/agent.md); (help "done") one whole
    clojure.repl as at any Clojure REPL (source, doc, dir, apropos, find-doc, pst), with *1 *2 *3 *e:
    source reads this branch's own definitions first, then a library's file"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.lib :as lib] [seon.context :as context] [seon.ref :as ref] [seon.docs :as docs]
            [seon.config :as cfg] [seon.proofs :as proofs] [seon.task :as task] [seon.vector :as vector] [seon.contract :as contract]
            [clojure.java.io :as io] [clojure.string :as str] [clojure.main] [datahike.api :as d]))

(defn $ "The live value of an earlier result, by handle (:h3)." {:malli/schema [:=> [:cat :keyword] :any]}
  [h] (get-in @agent/state [:results h]))
(defn whoami "This agent: {:id :branch :pid}." {:malli/schema [:=> [:cat] [:map [:id :seon.agent/id] [:branch :seon.db/branch] [:pid :seon.lib/pid]]]}
  [] {:id (:id @agent/state) :branch (:branch @agent/state) :pid (lib/c-getpid)})
(defn messages "The messages delivered to this agent and not yet taken ({:from :body}), taken." {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (agent/messages))
(defn send-message "Send body to the agents tos (ids); root delivers it by id. nil." {:malli/schema [:=> [:cat [:sequential :seon.agent/id] :any] :nil]}
  [tos body] (agent/send-message tos body))

(defn callers
  "The program vars whose recorded definition names var sym (its :form/refs on this branch),
  sorted; sym itself left out."
  {:malli/schema [:=> [:cat :qualified-symbol] [:vector :qualified-symbol]]}
  [sym]
  (vec (sort (disj (set (d/q '[:find [?v ...] :in $ ?s :where [?f :form/refs ?s] [?f :form/defines ?e] [?e :var/name ?v]]
                             (agent/db) sym))
                   sym))))

(defn callees
  "The vars var sym's recorded definition names on this branch (its :form/refs: program,
  library and clojure.core alike), sorted; sym itself left out."
  {:malli/schema [:=> [:cat :qualified-symbol] [:vector :qualified-symbol]]}
  [sym]
  (vec (sort (disj (set (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]]
                             (agent/db) sym))
                   sym))))

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
         q (if (symbol? x)
             (some-> (d/entity db [:var/name x]) :var/form :db/id)
             (let [r (agent/fx! :embed {:texts [(str vector/query-prompt x)]} {})]
               (if (contract/error? r) r (first (:vectors (:value r))))))
         name-of (fn [e] (let [x (d/entity db e)]
                           (or (some-> (:var/_form x) first :var/name) (:seon.section/address x) (:seon.task/id x) e)))]
     (cond (contract/error? q) q
           (nil? q) []
           :else (mapv (fn [[e s]] [(name-of e) s]) (vector/near db q k))))))

(defn prove
  "Run what root's judge will run on this branch (seon.agent/run-tests-forked :judge, in a fork
  of this agent): the task's proofs (or the given ones) and every in-process test reaching what
  the branch changed since the main it forked from, each test green at its reach digest reused,
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
  it (its requires and aliases), which clojure.repl's does not."
  {:malli/schema [:=> [:cat :symbol] [:maybe :string]]}
  [sym]
  (if-let [v (resolve sym)]
    (or (:source (meta v))
        (docs/source-of (agent/db) (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v)))))
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
  "Print the recorded source of the definition named by sym (as clojure.repl/source does)."
  [sym] `(println (or (source-fn '~sym) (not-found '~sym))))

(defn help
  "The help page every agent reads first (seon.context/help-page), as this branch holds it
  (seon.docs/page): its sections, each its address, heading and lead's first sentence; with
  an anchor, that section's own text."
  {:malli/schema [:function [:=> [:cat] [:vector :map]] [:=> [:cat :string] [:maybe :string]]]}
  ([] (mapv (fn [{:keys [address heading lead]}] (cond-> {:address address :heading heading} lead (assoc :lead (ref/lead-sentence lead))))
            (docs/page (agent/db) context/help-page)))
  ([anchor] (:seon.ref/text (docs/view (agent/db) (str context/help-page "#" anchor) :code))))
