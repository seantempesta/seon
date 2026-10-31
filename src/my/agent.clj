(ns my.agent
  "An agent's own functions (from proto/flow f2-final proto.agent's agent API). Referred into
  every agent namespace.
    ($ handle)                an earlier eval's response, its live value under :value (also result/e3)
    (whoami)                  {:id :branch :pid}
    (messages)                the messages delivered and not yet taken, taken
    (problem "signature")     one open problem of this agent's run whole, as its problems part names it
    (send-message tos body)   body to the agents tos (ids), delivered by root
    (callers 'ns/f)           the program vars whose recorded definition names ns/f
    (callers :ns/key)         the functions whose code or contract names the key (an attribute, config, schema)
    (callees 'ns/f)           the vars ns/f's recorded definition names
    (search "text")           the best matches for text over code, docs, tasks and schemas, fusing its exact
                              text, its meaning and its references: [{:kind :name :score :by :line} ...]
    (search "text" {:kinds #{:section}}) only those kinds; :by #{:text :meaning :refs}, :ns "seon.merge", :k 20
    (grep "text")             the program vars whose recorded definition's text holds it (the search's text half)
    (similar 'ns/f)           what is nearest ns/f's definition by meaning: vars, wiki sections, tasks
    (similar "text")          what is nearest the text by meaning (seon.vector/near)
    (prove)                   run what judges this branch: the task's proofs and the tests reaching
                              what it changed since main, each one with a result at its digest reused
    (process-test? 'ns/t-test) why test t reaches a process (nil: it does not): it runs at the judge
    @ctx                      this agent's record as a map: its task, runs, turns, messages and evals,
                              kept current as it works; the page shows it as a block (@seon.ctx/ctx
                              from any namespace)
    (swap! ctx assoc-in p v)  a change to it, as to any atom (assoc-in, update-in, dissoc): one checked
                              transaction on the branch, or refused with why; the next turn tells it
    (cite! "docs/x.md?full#y") read a section (a page whole with no anchor, or a name) beside the task,
                              at its ?depth (summary, lead, full); (uncite! "docs/x.md#y") stops reading it
    (help 'ns.name)           a namespace: every definition, privates included, a line each in file order
    (help)                    the help page's sections (docs/agent.md); (help "done") one whole;
                              (help "docs/vector.md#the-model") any section, (help "docs/vector.md") a page
    clojure.repl as at any Clojure REPL (source, doc, dir, apropos, find-doc, pst), with *1 *2 *3 *e:
    source reads this branch's own definitions first, then a library's file"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.lib :as lib] [seon.context :as context] [seon.ref :as ref] [seon.docs :as docs]
            [seon.config :as cfg] [seon.proofs :as proofs] [seon.fx :as fx] [seon.program :as program] [seon.task :as task] [seon.vector :as vector] [seon.contract :as contract] [seon.ctx]
            [seon.db :as pdb] [seon.problems :as problems] [seon.render.ui :as ui] [my.schema :as schema] [jolt.analyzer] [clojure.java.io :as io] [clojure.string :as str] [clojure.main] [datahike.api :as d]))

(defn $ "An earlier eval's response (its live value under :value), by handle (:e3)." {:malli/schema [:=> [:cat :keyword] :any]}
  [h] (get-in @agent/state [:results h]))
(defn whoami "This agent: {:id :branch :pid}." {:malli/schema [:=> [:cat] [:map [:id :seon.agent/id] [:branch :seon.db/branch] [:pid :seon.lib/pid]]]}
  [] {:id (:id @agent/state) :branch (:branch @agent/state) :pid (lib/c-getpid)})
(defn messages "The words to this agent not yet taken, taken: entries to its session's runs ({:id :kind :from :to :body}), then other agents' sends ({:from :body})." {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (agent/messages))
(defn- own-cites
  "What this agent cites now, its cite entities on its branch (seon.task/cites-of), each as {:cites target
  :depth d}."
  {:malli/schema [:=> [:cat] [:vector [:map [:cites :any] [:depth :seon.ref/depth]]]]}
  []
  (let [db (agent/db)]
    (mapv (fn [c] {:cites (or (:seon.cite/address c) (:seon.cite/name c)) :depth (:seon.cite/depth c)})
          (task/cites-of db (pdb/eid db :seon.agent/key (:key @agent/state))))))

(defn cite!
  "Add address to what this agent's context reads beside its task, in place of its cite of the same section,
  page or name: a path with an optional ?depth (summary, lead or full) and #anchor (\"docs/flow.md?full#idioms\";
  a page whole with no anchor), or a name. Written as a cite entity of this agent on its branch
  (seon.task/cite-tx), committed: its page draws it at its next draw. Answers what it cites now. See
  docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :string] [:vector [:map [:cites :any] [:depth :seon.ref/depth]]]]}
  [address]
  (let [db (agent/db) k (:key @agent/state)
        order (inc (reduce max -1 (map :seon.cite/order (task/cites-of db (pdb/eid db :seon.agent/key k)))))]
    (when-let [c (ref/address-citation address)]
      (agent/transact! [(task/cite-tx db [:seon.agent/key k] k c order)])
      (agent/commit!))
    (own-cites)))

(defn uncite!
  "Stop reading address beside the task (cite!'s other half): this agent's cite of the same section, page or
  name retracted whole, whatever depth it asked. Answers what it cites now. See
  docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :string] [:vector [:map [:cites :any] [:depth :seon.ref/depth]]]]}
  [address]
  (let [db (agent/db) k (:key @agent/state)
        id (some->> (ref/address-citation address) (#(task/cite-tx db [:seon.agent/key k] k % 0)) :seon.cite/id)]
    (when-let [e (and id (pdb/eid db :seon.cite/id id))]
      (agent/transact! [[:db/retractEntity e]])
      (agent/commit!))
    (own-cites)))

(defn problem
  "One open problem of this agent, whole: its row of seon.problems/own's report (a recorded
  error's example and evidence, its count and when it was first and last raised), by the signature its
  context's problems part names; nil when no open problem has it. See docs/health.md#two-kinds-of-problem."
  {:malli/schema [:=> [:cat :string] [:maybe :map]]}
  [signature]
  (let [db (agent/db)
        aid (pdb/eid db :seon.agent/key (:key @agent/state))]
    (when aid
      (some #(when (= signature (:signature %)) %) (mapcat :rows (vals (:classes (problems/own db aid))))))))

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
          ;; the plan as it stands (a design rewrites it with update!), read by the one parser: its stored cites
          ;; leave out a new namespace's function (seon.ref/kept reads it as an alias form). A parser the agent
          ;; broke while it inspects its change answers those stored cites (seon.task/task-cites), never its error
          id (:task @agent/state)
          cited? (boolean (when id
                            (try (contains? (set (some->> (task/task db id) :seon.task/plan ref/names)) sym)
                                 (catch Throwable _ (some #(= sym (:seon.cite/name %)) (task/task-cites db id))))))]
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
  (:my.agent/no-var), never []. A keyword that is a schema, an attribute or a key the program
  names answers its callers ([] when none uses it: the true answer); any other answers
  :seon.schema/unknown-key with the nearest registered keys (my.schema/unknown-key: a typo is no
  \"nothing calls it\", the md-scan bench)."
  {:malli/schema [:=> [:cat [:or :qualified-symbol :seon.host/var :qualified-keyword]] [:or [:vector :qualified-symbol] :my.agent/no-var :seon.schema/unknown-key]]}
  [x]
  (let [db (agent/db) x (cond (keyword? x) x (var? x) (named x) :else (asked x))
        fs (when (keyword? x) (schema/forms))]
    (or (when (symbol? x) (no-var db x))
        (when (and (keyword? x) (not (or (contains? fs x) (contains? (d/schema db) x) (seq (d/datoms db :avet :form/keywords x)))))
          (schema/unknown-key fs x))
        (docs/callers db x))))

(defn tests-reaching
  "The tests whose reach includes var x on this branch, nearest first, each {:test}: the tests a change to
  x selects, read from the reach graph the test plan reads (seon.agent/branch-graph) by the same
  reverse walk (seon.program/tests-reaching). A cited var's page lists only its direct tests; run
  this on the function you will change, before writing its proof, to find the test that goes red
  first. A test of a scratch namespace is left out, as the plan leaves it (seon.agent/judges?); a
  library's var, which the reach graph holds no form of, answers []. A symbol that names no var
  answers so (:my.agent/no-var)."
  {:malli/schema [:=> [:cat [:or :qualified-symbol :seon.host/var]] [:or [:vector [:map [:test :qualified-symbol]]] :my.agent/no-var]]}
  [x]
  (let [db (agent/db) x (if (var? x) (named x) (asked x))]
    (or (no-var db x)
        (let [[_ in] (agent/test-inputs db)
              g (agent/branch-graph db (:base @agent/state) in)]
          (into [] (comp (filter agent/judges?) (map (fn [t] {:test t})))
                (program/tests-reaching g x))))))

(defn cost
  "What contracted function x costs, measured by the test runs, never estimated: :per-call, its
  ticks, bytes and calls per call over the tests at this branch's digests that called it (in all and
  its own: self-ticks, self-bytes; seon.proofs/per-call), and :tests, those tests, each with its
  calls, ticks and bytes, most ticks first, read from their cached verdicts (seon.fx/details,
  seon.proofs/exercising). :unmeasured counts the tests reaching x with no measure yet (their
  digest moved since they last ran, or none ran). A symbol that names no var answers so
  (:my.agent/no-var)."
  {:malli/schema [:=> [:cat [:or :qualified-symbol :seon.host/var]] [:or :map :my.agent/no-var]]}
  [x]
  (let [db (agent/db) x (if (var? x) (named x) (asked x))]
    (or (no-var db x)
        (let [plan (agent/test-plan db (:base @agent/state) #{} (or (agent/heard-main) db))
              details (fx/details (into {} (filter (comp symbol? key)) (:digests plan)))
              rows (proofs/exercising details x)
              reaching (set (filter (:digests plan) (program/tests-reaching (:graph plan) x)))
              measured (get (proofs/per-call details) x)]
          (cond-> {:var x :tests rows :unmeasured (count (remove (set (map :test rows)) reaching))}
            measured (assoc :per-call measured))))))

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
  {:malli/schema [:=> [:cat :seon.host/db [:or :qualified-symbol :qualified-keyword :string]] :any]}
  [db x]
  (if (qualified-keyword? x)
    (some-> (d/entity db [:schema/key x]) :db/id)
  (if (symbol? x)
    (some-> (d/entity db [:var/name x]) :var/form :db/id)
    (let [r (agent/fx! :embed {:texts [(str vector/query-prompt x)]} {})]
      (if (contract/error? r) r (first (:vectors (:value r))))))))

(defn search
  "The best matches on this branch for q, a text, a var's name or a schema key, over code and wiki
  sections, and tests, tasks and schema keys when :kinds asks for them: [{:kind :name :score :by :line} ...] best first (seon.context/search), so a
  caller filters it with plain Clojure, e.g. (filter #(= :section (:kind %)) (search \"rrf\")). :kind is
  one of :var :test :section :task :schema, :name its var, section address, task id or schema key, :by
  the rankings it came from (:text its exact words, :meaning its embedding, :refs the call graph and the
  sections naming it), :line the line of its text that holds q. opts, all optional: :kinds (a set of
  those kinds), :by (a set of :text :meaning :refs), :ns (a prefix: only vars and tests whose namespace
  starts with it), :k (:seon.config.render/search), :min (a chance: 0.4, the page's cut, unless given). A
  function, section or schema key hit holds :relevance, the chance it is worth reading for q (calibrated
  on a judge's labels: docs/vector.md#relevance), and hits are best first by it; those below :min are
  counted in the last entry, not shown. Each hit also holds :e, its entity, and :at, the line of its text
  (my.agent/help takes a hit, or its :name, and shows it). Hits left out by :k are counted per kind in a
  last {:kind :more} entry with the call that returns them. A failed embedding answers its error; a var
  or key with no vector yet is searched without meaning."
  {:malli/schema [:function [:=> [:cat [:or :qualified-symbol :qualified-keyword :string]] [:or [:vector :map] :map]]
                  [:=> [:cat [:or :qualified-symbol :qualified-keyword :string] :map] [:or [:vector :map] :map]]]}
  ([q] (search q {}))
  ([q opts]
   (let [db (agent/db)
         qv (when (contains? (or (:by opts) #{:meaning}) :meaning) (query-of db q))]
     (if (contract/error? qv) qv (context/search db q opts qv)))))

(defn similar
  "What on this branch is nearest x by meaning, [[name score] ...] best first, k of them
  (:seon.config.render/search unless given): (search x {:by #{:meaning} :k k}) without its kinds' extras. x a
  var, a schema key or text; a failed embedding answers its error."
  {:malli/schema [:function [:=> [:cat [:or :qualified-symbol :qualified-keyword :string]] [:or [:vector [:tuple :any number?]] :map]]
                            [:=> [:cat [:or :qualified-symbol :qualified-keyword :string] pos-int?] [:or [:vector [:tuple :any number?]] :map]]]}
  ([x] (similar x (cfg/value :seon.config.render/search)))
  ([x k] (let [r (search x {:by #{:meaning} :k k})]
           (if (contract/error? r) r (into [] (comp (remove #(= :more (:kind %))) (map (juxt :name :score))) r)))))

(defn prove
  "Run what root's judge will run on this branch, through the test service (seon.agent/prove!
  :judge, so the run is recorded on main and is one run with any other asker's of this head):
  the task's proofs and every in-process test reaching what the branch changed since the main it
  forked from, each test with a result at its reach digest reused (a red as recorded), not run;
  given proofs, the tests of their namespaces. The process tests it reaches, a proof among them, run
  at the judge, before a merge (:deferred, and :why each: the process it reaches through which
  calls). Answers that test run ({:pass :fail :error :reports :ran :reused :ms ...}) with
  :results {test :pass|:fail|:error} and :proofs (each proof's status: the task's and the given ones,
  :deferred or :none when it has no result), printed as its verdict (prove-summary: the schema
  :seon.test/proved): one line; the whole run is the value under the eval's handle, and what it found
  is the run's part of the page. Its results are kept at each test's reach digest (the test service
  records them), so (my.task/status) and the judge read a proof's status at its digest whichever call
  measured it."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:set :qualified-symbol]] :map]]}
  ([] (prove #{}))
  ([proofs]
   (let [test-run (agent/prove! :judge (into #{} (map (comp symbol namespace)) proofs))]
     (if (contract/error? test-run)
       test-run
       (let [results (proofs/results test-run)
             task-proofs (some->> (:task @agent/state) (task/task (agent/db)) :seon.task/accumulated :proofs)
             deferred (set (:deferred test-run))]
         (with-meta (assoc test-run :results results
                           :proofs (into {}
                                         (for [p (into (set proofs) task-proofs)]
                                           [p (or (results p) (when (deferred p) :deferred) :none)])))
           {:seon.render/schema :seon.test/proved}))))))

(defn prove-summary
  "A prove's run r as the eval answers it (seon.render/model-printed, the schema :seon.test/proved): one
  line, its verdict and what ran, was left to the judge (process tests), was reused and died (a death
  is no verdict: not counted). What it found (each proof's status, the reds with their first failing
  assertion, the deaths) is drawn on the eval, from its value (seon.render.ui/proved, the schema's
  html render); the run whole is the value under the eval's handle. A part of the run (a select-keys
  of it keeps its metadata) is that part, as data. See docs/agent.md#done."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ r]
  (if-not (:reports r)
    (with-meta r nil)
    (let [died (count (proofs/died r))
          red (pos? (+ (:fail r 0) (:error r 0)))]
      (str (cond red "red" (pos? died) "died" :else "green")
           ": ran " (:ran r 0) ", deferred " (count (:deferred r)) " (to the judge), reused " (:reused r 0) ", died " died))))

(defn process-test?
  "Whether test t reaches a process of its own on this branch (an agent, a root, a fork, the binary:
  seon.program/graph-index's :process?), so it runs at the judge's merge gate and never in this
  agent's fork; with why ({:uses :through}: the process var it reaches and the calls to it,
  seon.program/process-path), or nil when it does not."
  {:malli/schema [:=> [:cat :qualified-symbol] [:maybe :map]]}
  [t]
  (let [plan (agent/test-plan (agent/db) (:base @agent/state) #{t})]
    (when (contains? (:process plan) t)
      (program/process-path (:graph plan) t))))

(defn grep
  "The program vars and tests whose recorded definition holds the words of s, best first: the text half of
  (search s {:by #{:text} :kinds #{:var :test} :min 0.0}), one hit each ({:kind :name :e :score :by :line :at}).
  Every text hit is shown up to :k, whatever its relevance (a grep finds the words; the page's cut is the
  page's, and a text-only query has no meaning for relevance to weigh)."
  {:malli/schema [:=> [:cat :string] [:or [:vector :map] :map]]}
  [s] (search s {:by #{:text} :kinds #{:var :test} :min 0.0}))

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
  :file and :line (a library's, or any in a process with no branch). A namespace's name answers its ns form as this branch records
  it (its requires and aliases), then each definition, privates included, a line each in file
  order (seon.docs/namespace-source), which clojure.repl's does not (pipeline 1's F4: a design
  that asked (source ns) saw the ns form alone, took it for all that was recorded, and spent five
  turns finding the namespace's private functions). sym is a symbol or the var (named)."
  {:malli/schema [:=> [:cat [:or :symbol :seon.host/var]] [:maybe :string]]}
  [sym]
  ;; a name that resolves to a class (java.lang.String) or to anything but a var has no recorded source
  (if-let [v (let [r (if (var? sym) sym (resolve sym))] (when (var? r) r))]
    (or (:source (meta v))
        ;; the branch's record, where there is a branch (a process with no agent's db, a test run's, reads the file)
        (some-> (:db @agent/state) (docs/source-of (named v)))
        (file-source v))
    (when-not (namespace sym)
      ;; the branch's record is the answer: a namespace it does not record has no ns form or privates to list
      (some-> (:db @agent/state) (docs/namespace-source sym)))))

(defn deferred
  "The process tests this branch's change reaches, a proof among them: an agent's turn and
  root's judge never run them in a fork; the judge's merge gate runs them on a checkout before
  the merge, nobody asking (seon.reconcile's :gate). seon.agent/test-plan's :process among what it selects, each
  with why: the process var it uses and the calls from the test to it (seon.program/process-path)."
  {:malli/schema [:=> [:cat] [:vector [:map [:test :qualified-symbol] [:uses [:vector :qualified-symbol]] [:through [:vector :qualified-symbol]]]]]}
  [] (let [proofs (or (some->> (:task @agent/state) (task/task (agent/db)) :seon.task/accumulated :proofs) #{})
           plan (agent/test-plan (agent/db) (:base @agent/state) proofs (agent/heard-main))]
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
    `(let [x# ~x]
       (println (if (or (symbol? x#) (var? x#))
                  (let [s# (named x#)]
                    (or (source-fn s#) (:seon.error/message (no-var (agent/db) s#)) (str "Source not found for " s#)))
                  (str "Source not found for " x# ": source reads a name or a var"))))))

(defn- help-nearest
  "What a help address that is no section may have meant: the help page's sections and the pages
  whose address holds a word of it (four letters or more), the sections whose text the search finds
  for it (`search`: \"schema\" named no address, though the help page says how a schema changes;
  md-scan #6), then the nearest by Jolt's did-you-mean ranking (as no-var): a few
  addresses, never the page ((my.agent/help) lists it). See docs/references.md#a-section-read-at-a-depth-with-its-edges."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:vector :string]]}
  [db address]
  (let [sections (docs/page db context/help-page :summary)
        addresses (mapv :seon.ref/address sections)
        pages (docs/page-paths db)
        words (re-seq #"[a-z0-9]{4,}" (str/lower-case address))
        holds? (fn [s] (some #(str/includes? (str/lower-case s) %) words))
        ranked (jolt.analyzer/suggestions-for address (concat addresses pages))]
    (vec (distinct (concat (filter holds? pages) (filter holds? addresses)
                           (keep #(when (= :section (:kind %)) (:name %)) (search address {:kinds #{:section} :k 3}))
                           ranked)))))

(defn- cited
  "Name n as a cite of it on the agent's page shows it, on demand: a cite of n at doc depth made on db (one
  d/with), pulled by the cite's own pattern (seon.ctx/build) and drawn by the cite's model render
  (seon.render.ui/cite-model), so on demand and on the page are one render. See
  docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :seon.host/db [:or :symbol :qualified-keyword]] :any]}
  [db n]
  (let [r (d/with db [{:db/id "of" :seon.agent/key (str "help " n)} {:seon.cite/id (str "help " n) :seon.cite/of "of" :seon.cite/name n :seon.cite/depth :doc :seon.cite/order 0}])
        db' (:db-after r)]
    (ui/cite-model {} (:tree (seon.ctx/build db' (pdb/eid db' :seon.cite/id (str "help " n)) nil)))))

(defn help
  "The wiki through the one view (seon.docs/view), as this branch holds it. No argument: the help
  page (seon.context/help-page) at summary, each section its address, heading and lead's first
  sentence, with the sections it cites and those citing it. With an address (``docs/vector.md#the-model``) or
  an anchor of the help page (\"done\"), that section whole; with a page's path (\"docs/vector.md\"), that page
  at summary. A miss answers what it did not find, the nearest addresses (help-nearest) and the
  forms that read one, never nil. (Before this an anchor answered its text alone; it answers the view, :seon.ref/text among its keys.)
  With a namespace's name ('seon.print.select), the namespace as a plan's citation of it shows it:
  its ns head and docstring, then each definition, privates included, in file order, a line each
  (its name and its doc's first sentence; seon.ref/namespace-view). With a hit of (my.agent/search ...)
  (a map with its :kind and :name), or its :name (a var's symbol, a schema key, a section's address),
  the thing itself at doc depth: a var its docstring, contract and source, a section whole, a task its
  plan, proofs and needs."
  {:malli/schema [:function [:=> [:cat] [:vector :seon.ref/view]]
                  [:=> [:cat [:or :string :symbol :qualified-keyword [:map [:kind :keyword]]]] :any]]}
  ([] (vec (docs/page (agent/db) context/help-page :summary)))
  ([address]
   (cond
     (map? address) (let [db (agent/db) {:keys [kind name]} address]
                      (case kind
                        :task (select-keys (task/task db name) [:seon.task/id :seon.task/plan :seon.task/proofs :seon.task/needs])
                        :section (docs/view db name :code)
                        :more address
                        (cited db name)))
     (or (symbol? address) (keyword? address))
     (cited (agent/db) address)
     :else
   (let [db (agent/db)
         a (if (str/includes? address "#") address (str context/help-page "#" address))]
     (or (docs/view db a :code)
         (docs/page db address :summary)
         {:no-section address
          :nearest (help-nearest db address)
          :read-one "(my.agent/help) lists the help page's sections; (my.agent/help \"docs/<page>.md\") a page's sections; (my.agent/help \"docs/<page>.md#<section>\") reads one; (my.agent/help 'ns.name) a namespace, every definition a line"})))))

