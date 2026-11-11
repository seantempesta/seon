(ns my.agent
  "An agent's own functions (from proto/flow f2-final proto.agent's agent API). Referred into
  every agent namespace.
    ($ handle)                an earlier eval's response, its live value under :value (also result/a3f9c1d2)
    (whoami)                  {:id :branch :pid}
    (messages)                the messages sent to you and not yet delivered, delivered; they stay in @ctx until you remove one
    (problem \"signature\")     one open problem of this agent's run whole, as its problems part names it
    (send-message tos body)   body to the agents tos (ids), one message to all of them, which wakes each that waits
    (callers 'ns/f)           the program vars whose recorded definition names ns/f
    (callers :ns/key)         the functions whose code or contract names the key (an attribute, config, schema)
    (callers 'a.ns)           the namespaces that require a.ns
    (callees 'ns/f)           the vars ns/f's recorded definition names
    (callees 'a.ns)           the namespaces a.ns requires
    (history x)               what x's attributes have been, oldest first: x a var (qualified symbol), an entity id or a lookup ref
    (search \"text\")           the best matches for text over code, docs, tasks and schemas, fusing its exact
                              text, its meaning and its references: [{:kind :name :score :by :line} ...]
    (search \"text\" {:kinds #{:section}}) only those kinds; :by #{:text :meaning :refs}, :ns \"seon.merge\", :k 20
    (grep \"text\")             like grep: every line holding it, over functions, tests, docs, tasks and schemas,
                              as rows [{:at \"ns/f:3\" :kind :text}]; {:in #{:var :test} :ns \"seon.ref\"} narrows it
    (ls 'a.ns) (ls \"docs/x.md\") like ls: a namespace's definitions or a page's sections, a row each
    (lines 'ns/f 10 40)        like sed -n: x's lines (a var, a namespace, a section \"docs/x.md#a\", a page, a task), numbered
    (wc x)                    like wc, in tokens: what x costs printed; rows are data: take, filter, count them
    (similar 'ns/f)           what is nearest ns/f's definition by meaning: vars, wiki sections, tasks
    (similar \"text\")          what is nearest the text by meaning (seon.vector/near)
    (prove)                   run what judges this branch: the task's proofs and the tests reaching
                              what it changed since main, each one with a result at its digest reused
    (process-test? 'ns/t-test) why test t reaches a process (nil: it does not): it runs at the judge
    @ctx                      this agent's record as a map: :seon.agent/task, :seon.model-call/_agent, :seon.message/_to and :seon.eval/_agent,
                              kept current as it works; the page shows it as a block (@seon.ctx/ctx
                              from any namespace)
    (swap! ctx assoc-in p v)  a change to it, as to any atom (assoc-in, update-in, dissoc): one checked
                              transaction on the branch, or refused with why; the next turn tells it
    (cite! \"docs/x.md?full#y\") read a section (a page whole with no anchor, or a name) beside the task,
                              at its ?depth (summary, lead, full); (uncite! \"docs/x.md#y\") stops reading it
    (doc 'ns.name)            a namespace: its doc, then every definition, privates included, a line each in file order
    (doc \"done\")              the help page's section (docs/agent.md) whole; (doc \"docs/vector.md#the-model\") any
                              section, (doc \"docs/vector.md\") a page's sections; (doc :var/form) a schema key
    (help)                    the system prompt as sent, then this map
    clojure.repl as at any Clojure REPL (source, doc, dir, apropos, find-doc, pst), with *1 *2 *3 *e:
    source reads this branch's own definitions first, then a library's file"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.walk] [datahike.core] [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.lib :as lib] [seon.ctx.search :as search] [seon.ctx.wire :as wire] [seon.ref :as ref] [seon.doc :as doc] [seon.source :as source]
            [seon.config :as cfg] [seon.proofs :as proofs] [seon.fx :as fx] [seon.program :as program] [seon.task :as task] [seon.vector :as vector] [seon.contract :as contract] [seon.ctx]
            [seon.db :as pdb] [seon.print :as print] [seon.print.select :as select] [seon.render :as render] [seon.problems :as problems] [seon.render.ui :as ui] [my.schema :as schema] [jolt.analyzer] [clojure.java.io :as io] [clojure.string :as str] [clojure.main] [datahike.api :as d]))

(defn $ "An earlier eval's response (its live value under :value), by handle (:a3f9c1d2)." {:malli/schema [:=> [:cat :keyword] :any]}
  [h] (get-in @agent/state [:results h]))
(defn whoami "This agent: {:id :branch :pid}." {:malli/schema [:=> [:cat] [:map [:id :seon.agent/id] [:branch :seon.db/branch] [:pid :seon.lib/pid]]]}
  [] {:id (:id @agent/state) :branch (:branch @agent/state) :pid (lib/c-getpid)})
(defn messages "The messages to this agent not yet delivered, delivered ({:id :from :body :sent-ms}: the entities on main that name this agent, another agent's send among them, however it was sent); they stay in @ctx, and its model reads them, until you remove one with (swap! seon.ctx/ctx update :messages dissoc e). Then effect outcomes ({:from :fx :k :kind :body}). See docs/users.md#messages." {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (agent/messages))
(defn- own-cites
  "What this agent cites now, its cite entities on its branch (seon.task/cites-of), each as {:cites target
  :depth d}."
  {:malli/schema [:=> [:cat] [:vector [:map [:cites :any] [:depth :seon.ref/depth]]]]}
  []
  (let [db (agent/db)]
    (mapv (fn [c] {:cites (task/cited-name c) :depth (:seon.cite/depth c)})
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

(defn send-message "Send body to the agents tos (ids): root writes it as a message fact on main to each one's branch, whether or not its process lives; text, or a value printed whole. nil. See docs/users.md#messages." {:malli/schema [:=> [:cat [:sequential :seon.agent/id] :any] :nil]}
  [tos body] (agent/send-message tos body))

(defn named
  "The symbol x names a definition by: x as it is, or a var's ns-qualified name (what a REPL hands
  a function of a name when its caller passes #'f or a value of (resolve 'f))."
  {:malli/schema [:=> [:cat [:or :symbol :seon.host/var]] :symbol]}
  [x] (if (var? x) (symbol (str (ns-name (:ns (meta x)))) (str (:name (meta x)))) x))

(defn- asked
  "The name sym is asked by: the var it resolves to in the agent's namespace (an alias, 'str/join,
  read as clojure.string/join, the name its callers' facts record), else sym as written."
  {:malli/schema [:=> [:cat :symbol] :symbol]}
  [sym] (let [v (ns-resolve *ns* sym)] (if (var? v) (named v) sym)))

(defn- ns-named?
  "Does sym name a namespace on db or in this process, and no var here ('map is clojure.core/map, so
  callers and callees read it as the var; 'seon.task, a namespace, answers namespaces)?"
  {:malli/schema [:=> [:cat :seon.host/db :any] :boolean]}
  [db sym]
  (boolean (and (simple-symbol? sym) (not (var? (ns-resolve *ns* sym))) (or (find-ns sym) (pdb/eid db :ns/name sym)))))

(defn- plan-cites
  "Does task id's plan name sym (a cite, or a two-backtick literal: a name it will create), as it stands (a design rewrites it with update!), read on db by the one
  parser (its stored cites leave out a new namespace's function: seon.ref/kept reads it as an alias form)?
  A parser the agent broke while it inspects its change answers from the stored cites (seon.task/task-cites)
  and :threw its message, so the answer names it, never a quietly weaker one. No task cites nothing."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :string] :symbol] [:map [:cited? :boolean] [:threw [:maybe :string]]]]}
  [db id sym]
  (if-not id
    {:cited? false :threw nil}
    (try {:cited? (contains? (set (some->> (task/task db id) :seon.task/plan ref/names)) sym) :threw nil}
         (catch Throwable e
           {:cited? (boolean (some #(= sym (get-in % [:seon.cite/target :var/name])) (task/task-cites db id))) :threw (or (ex-message e) (str (class e)))}))))

(defn no-var
  "The one answer for a name sym that is no var on db nor in this process (a library's var is
  one), else nil: what callers, callees and source say of it, never [] or silence. It names the
  nearest existing names by Jolt's own did-you-mean ranking (jolt.analyzer/suggestions-for): the
  definitions of sym's namespace, or, when that namespace is none, the nearest namespaces; the form
  for the registry key of that name when db has one; and \"write it\" only when this agent's task
  cites sym in its plan (a plan's new function, not yet written; in-ns makes a new namespace).
  A namespace's name that names one is not missing (source answers what it records)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :symbol] [:maybe :my.agent/no-var]]}
  [db0 sym]
  ;; root holds no branch: its names are the process's alone (an empty db has no vars)
  (let [db (or db0 (datahike.core/empty-db))]
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
          {:keys [cited? threw]} (plan-cites db (:task @agent/state) sym)]
      (contract/error :my.agent/no-var (symbol #'no-var)
                      (str sym (cond view " names no var on this branch"
                                         nsym (str " names no namespace on this branch (" nsym ")")
                                         :else " names no var or namespace here")
                           (when (seq nearest) (str "; nearest: " (str/join ", " nearest)))
                           (when view (str "; every definition of " nsym ", a line each: (source " nsym ")"))
                           (when key? (str "; " k " is a registry key: (my.agent/callers " k ")"))
                           (when cited? (str "; your task cites it, so write it: (in-ns '" nsym ") (defn " (name sym) " ...)"))
                           (when threw (str "; your plan's parse threw (" threw "): its cites read from the stored ones (seon.task/task-cites)")))
                      (cond-> {:name sym :nearest nearest} key? (assoc :ask (str "(my.agent/callers " k ")"))))))))

(defn callers
  "The program vars whose recorded definition names x on this branch, sorted, x itself left out
  (seon.doc/callers): a var's callers (x a qualified symbol, or the var: named), or the functions
  operating on a qualified keyword, an attribute, config key or schema entry (its :form/keywords).
  A symbol through an alias reads as the var it resolves to; one that names no var answers so
  (:my.agent/no-var), never []. A keyword that is a schema, an attribute or a key the program
  names answers its callers ([] when none uses it: the true answer); any other answers
  :seon.schema/unknown-key with the nearest registered keys (my.schema/unknown-key: a typo is no
  \"nothing calls it\"). A namespace's name answers the namespaces that require
  it (seon.source/required-by; an alias resolves as the var it names, so a question of whether a new require would close a cycle gets the namespace's answer)."
  {:malli/schema [:=> [:cat [:or :symbol :seon.host/var :qualified-keyword]] [:or [:vector :symbol] :my.agent/no-var :seon.schema/unknown-key]]}
  [x]
  (let [db (agent/db) x (cond (keyword? x) x (var? x) (named x) (ns-named? db x) x :else (asked x))
        fs (when (keyword? x) (schema/forms))]
    (or (when (symbol? x) (no-var db x))
        (when (ns-named? db x) (vec (sort (source/required-by db x))))
        (when (and (keyword? x) (not (or (contains? fs x) (contains? (d/schema db) x) (seq (d/datoms db :avet :form/keywords x)))))
          (schema/unknown-key fs x))
        (doc/callers db x))))

(defn tests-reaching
  "The tests whose reach includes var x on this branch, nearest first: the tests a change to
  x selects, read from the reach graph the test plan reads (seon.agent/branch-graph) by the same
  reverse walk (seon.program/tests-reaching). A cited var's page lists only its direct tests; run
  this on the function you will change, before writing its proof, to find the test that goes red
  first. A test of a scratch namespace is left out, as the plan leaves it (seon.agent/judges?); a
  library's var, which the reach graph holds no form of, answers []. A symbol that names no var
  answers so (:my.agent/no-var)."
  {:malli/schema [:=> [:cat [:or :qualified-symbol :seon.host/var]] [:or [:vector :qualified-symbol] :my.agent/no-var]]}
  [x]
  (let [db (agent/db) x (if (var? x) (named x) (asked x))]
    (or (no-var db x)
        (let [[_ in] (agent/test-inputs db)
              g (agent/branch-graph db (:base @agent/state) in)]
          (filterv agent/judges? (program/tests-reaching g x))))))

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
  the var (named). A symbol that names no var answers so (:my.agent/no-var), never []. A
  namespace's name answers the namespaces it requires (seon.source/requires)."
  {:malli/schema [:=> [:cat [:or :symbol :seon.host/var]] [:or [:vector :symbol] :my.agent/no-var]]}
  [sym]
  (let [db (agent/db) sym (cond (var? sym) (named sym) (ns-named? db sym) sym :else (asked sym))]
    (or (no-var db sym)
        (when (ns-named? db sym) (vec (sort (source/requires db sym))))
        (vec (sort (disj (set (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]] db sym))
                         sym))))))

(defn history
  "What an entity's attributes have been on this branch, oldest first, a row {:a :v :added? :t :at} for each
  datom the store's history keeps (seon.db/history): x is what it takes (an entity id or a lookup ref), or a
  qualified symbol or the var, which means that var's entity. opts: :attrs, :pull (seon.db/history), and
  :db, the db to read (default this branch's; (my.db/at {:t t}) names another). A symbol that names no var
  answers so (:my.agent/no-var), as callers does.
    (history 'seon.x/f {:attrs #{:var/form} :pull [:form/src]})                  a var's definitions
    (history [:seon.task/id \"x\"] {:attrs #{:seon.task/outcome}})                 a task's outcomes
  See docs/storage.md#history."
  {:malli/schema [:function [:=> [:cat [:or :seon.db/entity :qualified-symbol :seon.host/var]] [:or [:vector :seon.db/history-row] :my.agent/no-var :seon.db/unresolved :seon.db/no-history]]
                  [:=> [:cat [:or :seon.db/entity :qualified-symbol :seon.host/var]
                        [:map {:closed true} [:attrs {:optional true} [:set :keyword]] [:pull {:optional true} [:vector :any]] [:db {:optional true} :seon.host/db]]]
                   [:or [:vector :seon.db/history-row] :my.agent/no-var :seon.db/unresolved :seon.db/no-history]]]}
  ([x] (history x {}))
  ([x {:keys [db] :as opts}]
   (let [db (or db (agent/db)) sym (cond (var? x) (named x) (symbol? x) (asked x))]
     (or (when sym (no-var db sym))
         (pdb/history db (if sym [:var/name sym] x) (dissoc opts :db))))))

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

(defn- hits-of
  "seon.ctx.search/search's hits for q on this branch, its query's vector asked when meaning ranks (`query-of`), or that
  embedding's error: what `search` shows as grep's rows and `similar` reads the scores of. See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:or :qualified-symbol :qualified-keyword :string] :map] [:or [:vector :map] :map]]}
  [q opts]
  (let [db (agent/db)
        qv (when (contains? (or (:by opts) #{:meaning}) :meaning) (query-of db q))]
    (if (contract/error? qv) qv (search/search db q opts qv))))

(defn hit-rows
  "Search hits as grep's rows (`grep`), best first: each {:at \"name:n\" :kind :text :name :relevance}, :at the hit's name and the
  number of the line :text is (the line of its text holding a word of q, else its first), so a hit reads as a grep line does and
  (lines name n 20) reads on from it; :relevance where the hit has one; the last {:kind :more} entry as it is. An agent trusts
  grep because it shows the line. See docs/vector.md#search."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [hits]
  (mapv (fn [{:keys [kind name line at relevance] :as h}]
          (if (= :more kind)
            h
            (cond-> {:at (str name (when at (str ":" at))) :kind kind :text (str (or line "")) :name name}
              relevance (assoc :relevance relevance))))
        hits))

(defn search
  "The best matches on this branch for q, a text, a var's name or a schema key, over code and wiki
  sections, and tests, tasks and schema keys when :kinds asks for them, as grep's rows: [{:at \"name:n\" :kind :text :name :relevance} ...] best first (seon.ctx.search/search, then `hit-rows`), so a
  caller filters it with plain Clojure, e.g. (filter #(= :section (:kind %)) (search \"rrf\")). :kind is
  one of :var :test :section :task :schema, :name its var, section address, task id or schema key, :text the line of
  its text that holds a word of q (else its first) and :at its name and that line's number. opts, all optional: :kinds (a set of
  those kinds), :by (a set of :text :meaning :refs), :ns (a prefix: only vars and tests whose namespace
  starts with it), :k (:seon.config.render/search), :min (a chance: 0.4, the page's cut, unless given). A
  function, section or schema key hit holds :relevance, the chance it is worth reading for q (calibrated
  on a judge's labels: docs/vector.md#relevance), and hits are best first by it; those below :min are
  counted in the last entry, not shown. (doc hit) shows a hit whole, and (lines name n 20) reads on from its line. Hits left out by :k are counted per kind in a
  last {:kind :more} entry with the call that returns them. A failed embedding answers its error; a var
  or key with no vector yet is searched without meaning."
  {:malli/schema [:function [:=> [:cat [:or :qualified-symbol :qualified-keyword :string]] [:or [:vector :map] :map]]
                  [:=> [:cat [:or :qualified-symbol :qualified-keyword :string] :map] [:or [:vector :map] :map]]]}
  ([q] (search q {}))
  ([q opts] (let [r (hits-of q opts)] (if (contract/error? r) r (hit-rows r)))))

(defn similar
  "What on this branch is nearest x by meaning, [[name score] ...] best first, k of them
  (:seon.config.render/search unless given): (search x {:by #{:meaning} :k k}) without its kinds' extras. x a
  var, a schema key or text; a failed embedding answers its error."
  {:malli/schema [:function [:=> [:cat [:or :qualified-symbol :qualified-keyword :string]] [:or [:vector [:tuple :any number?]] :map]]
                            [:=> [:cat [:or :qualified-symbol :qualified-keyword :string] pos-int?] [:or [:vector [:tuple :any number?]] :map]]]}
  ([x] (similar x (cfg/value :seon.config.render/search)))
  ([x k] (let [r (hits-of x {:by #{:meaning} :k k})]
           (if (contract/error? r) r (into [] (comp (remove #(= :more (:kind %))) (map (juxt :name :score))) r)))))

(defn prove
  "Run what root's judge will run on this branch, through the test service (seon.agent/judge!, so the
  run is recorded on main and is one run with any other asker's of this head):
  the task's proofs and every in-process test reaching what the branch changed since the main it
  forked from, each test with a result at its reach digest reused (a red as recorded), not run;
  given proofs (one name, or a set or vector of them), those tests (a namespace's name, its tests), each asked now: a red held at its digest runs again. Once those are green, the process tests among its proofs and
  the tests given run on the merge gate's checkout, slower, and the verdict counts them; the process tests it only reaches run when
  your status is clear, and stay :deferred here (with :why each: the process it reaches through which calls, and :unjudged, why they stay), so the verdict says not green. Answers that test run ({:pass :fail :error :reports :ran :reused :ms ...}) with
  :results {test :pass|:fail|:error} and :proofs (each proof's status: the task's and the given ones,
  :deferred or :none when it has no result), and :unmatched, the given names that name no test (a var that
  is none, a namespace with none: a name that matches nothing must not run silently as 0), printed as its verdict (prove-summary: the schema
  :seon.test/proved): one line; the whole run is the value under the eval's handle, and what it found
  is the run's part of the page. Its results are kept at each test's reach digest (the test service
  records them), so (my.task/status) and the judge read a proof's status at its digest whichever call
  measured it."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:or :symbol [:set :symbol] [:sequential :symbol]]] :map]]}
  ([] (prove #{}))
  ([given]
   (let [proofs (if (symbol? given) #{given} (set given))
         test-run (agent/judge! proofs false)]
     (if (contract/error? test-run)
       test-run
       (let [results (proofs/results test-run)
             task-proofs (some->> (:task @agent/state) (task/task (agent/db)) :seon.task/accumulated :proofs)
             deferred (set (:deferred test-run))
             ;; a name that matches nothing, the task's own proofs too
             unmatched (vec (sort-by str (filter #(empty? (agent/named-tests (agent/db) #{%})) (into (set proofs) task-proofs))))]
         (with-meta (cond-> (assoc test-run :results results
                           :proofs (into {}
                                         (for [p (into (set proofs) task-proofs)]
                                           [p (or (results p) (when (deferred p) :deferred) :none)])))
                      ;; a name that is no test and no namespace of tests runs nothing: said, never a silent ran 0
                      (seq unmatched) (assoc :unmatched unmatched))
           {:seon.render/schema :seon.test/proved}))))))

(defn prove-summary
  "A prove's run r as the eval answers it (seon.render/model, the schema :seon.test/proved): its verdict as counts of the
  proofs it judged (`red: 1 of 3 failed`), each failing test with its first failure in a line, and only what is true of this
  run: the tests it reused (their last result still counts, because nothing they reach changed), the process tests with no
  verdict (then it is not green), the ones that died (no verdict) and a name that matched no test. The run whole is the value under the eval's
  handle (the proofs' statuses, every failure, the deaths). A part of the run (a select-keys of it keeps its metadata) is that
  part, as data. See docs/agent.md#done."
  {:malli/schema [:=> [:cat :seon.render/opts :map] :any]}
  [_ r]
  (if-not (:reports r)
    (with-meta r nil)
    (let [died (count (proofs/died r))
          fails (apply dissoc (proofs/failures r) (proofs/died r))
          ;; a death is no verdict: it counts among the errors, and is said as what it is
          red (or (seq fails) (and (zero? died) (pos? (+ (:fail r 0) (:error r 0)))))
          n (+ (:ran r 0) (:reused r 0))
          ;; a proof with no verdict: green can't be said of what nobody judged
          none (sort-by str (keep (fn [[p st]] (when (= :none st) p)) (:proofs r)))
          clean (fn [x] (str/replace (str x) #"#object\[java\.util\.regex\.Pattern 0x[0-9a-f]+\]" "a regex"))
          failure (fn [t f] (let [got (str/trim (str (or (:actual f) (:got f))))
                                  m (when (str/starts-with? got "#error") (second (re-find #":message \"([^\"]*)\"" got)))]
                              (str "✗ " t (when (:message f) (str ": " (:message f))) "\n    asserted " (clean (:expected f))
                                   "\n    and got  " (if m (str "an error: " m) (clean got)))))]
      (print/code
       (str/join "\n" (remove nil?
         (concat
           [(cond red (str "red: " (count fails) " of " n " failed")
                  (pos? died) (str "died: " died " of " n " had no verdict")
                  (seq none) (str "not green: " (count none) " of " (count (:proofs r)) " proofs have no verdict: " (str/join ", " none))
                  ;; a process test nobody ran has no verdict, a proof or not: green can't be said of it
                  (seq (:deferred r)) (let [ps (vals (:proofs r))
                                            waiting (sort-by str (keep (fn [[p st]] (when (= :deferred st) p)) (:proofs r)))]
                                        (str n " of " n " in-process tests passed" (when (seq ps) (str ", " (count (filter #{:pass} ps)) " of your " (count ps) " proofs among what passed"))
                                             "; outstanding: " (count (:deferred r)) " process tests with no verdict"
                                             (when (seq waiting) (str ", your proofs " (str/join ", " waiting) " among them"))
                                             (some->> (:unjudged r) (str ": ")) ". (my.agent/deferred) lists them."))
                  (zero? n) "nothing judged: no proof and no test this change reaches"
                  :else (str "green: " n " of " n " passed"))]
           (for [[t fs] (sort-by (comp str key) fails)] (failure t (first fs)))
           [(when (pos? (:reused r 0)) (str "reused " (:reused r) ": their last result still counts, because nothing they reach has changed"
                                            (let [held (filter :reused (:reports r)) reds (sort-by str (distinct (keep #(when (#{:fail :error} (:type %)) (proofs/test-symbol (:var %))) held)))]
                                              (when (seq reds) (str "; of them red: " (str/join ", " reds) ". (my.agent/prove '#{a-name}) runs one again")))))
            (when (seq (:unmatched r)) (str "no test is named " (str/join ", " (:unmatched r))))])))))))

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

(def ^:private every-kind
  "What grep reads when :in names nothing: the kinds of text the program records (seon.doc/text-attrs)."
  #{:var :test :section :task :schema})

(defn grep
  "Like grep, over this branch's program: every line matching pattern in the functions, tests, wiki sections,
  task plans and schema entries, one row each, [{:at \"seon.task/accumulate:3\" :kind :var :text \"...\"}] (seon.doc/grep).
  pattern is a string, matched as written, or a regex (#\"plan|proof\"); a string with a regex's operators that matches
  nothing answers [], shown with its regex form (`grep-none-text`). opts, and no others: :in, a set of kinds (:var :test
  :section :task :schema), :ns, a name and what is under it (\"seon.ref\": seon.ref/x and seon.ref.y/z, not seon.ref-test). The answer is data, and whole: narrow it as you
  would a list, (take 10 (grep \"x\")), (filter #(= :test (:kind %)) ...), (count ...), and read a hit's lines
  with (lines \"seon.task/accumulate\" 1 20). (search s) ranks by meaning, words and references; grep finds text. See docs/vector.md#search."
  {:malli/schema [:function [:=> [:cat [:or :string :seon.host/regex]] [:vector :map]]
                  ;; closed: an option grep does not take is refused, never ignored (search's :kinds and :k, ignored,
                  ;; read as every kind's hits)
                  [:=> [:cat [:or :string :seon.host/regex] [:map {:closed true} [:in {:optional true} [:set :keyword]] [:ns {:optional true} :string]]]
                   [:vector :map]]]}
  ([pattern] (grep pattern {}))
  ([pattern {:keys [in ns]}]
   (let [db (agent/db) kinds (or in every-kind) rows (doc/grep db pattern kinds ns)]
     ;; a string is its text as written: one holding a regex's operators that matches nothing is still [] (a
     ;; value, so a reply's later calls run), shown with its regex form: the string's characters between #" and ",
     ;; a quote escaped
     (if (and (empty? rows) (string? pattern) (re-find #"[|^$()\[\]*+?\\]" pattern))
       (with-meta rows {:seon.render/schema :my.agent/grep-none
                        :ask (str "(my.agent/grep #\"" (str/replace pattern "\"" "\\\"") "\""
                                  (when (or in ns) (str " " (print/text (cond-> {} in (assoc :in in) ns (assoc :ns ns)) (:part print/budgets)))) ")")})
       rows))))

(defn grep-none-text
  "grep's empty answer to a string holding a regex's operators as the eval shows it (seon.render/model, the
  schema :my.agent/grep-none): [] and the regex form that asks for what it likely meant. See docs/agent.md#small-answers."
  {:malli/schema [:=> [:cat :seon.render/opts [:vector :map]] :any]}
  [_ rs]
  (print/code (str "[] ; no line holds the string as written: a string is matched as its text, and a regex is written #\"...\", as "
                   (:ask (meta rs)))))

(defn ls
  "Like ls: what x holds, one row each. A namespace ('seon.task): each definition, privates included, in file
  order, {:at \"seon.task/accumulate\" :text \"its doc's first sentence\"} (seon.ref/namespace-view). A wiki page
  (\"docs/merging.md\"): each section in order, {:at address :level :text heading} (seon.doc/outline). A name that
  is neither answers what it found nearest (my.agent/no-var)."
  {:malli/schema [:=> [:cat [:or :symbol :string]] [:or [:vector :map] :my.agent/no-var]]}
  [x]
  (let [db (agent/db)]
    (if (string? x)
      (or (doc/outline db x) (no-var db (symbol x)))
      (if-let [view (ref/namespace-view db x :doc)]
        (mapv (fn [b] {:at (:seon.ref/address b) :text (second (str/split (:seon.ref/heading b) #": " 2))}) (:seon.ref/below view))
        (no-var db x)))))

(declare source-fn)

(defn- text-of
  "The text x names on this branch, as lines reads it: a var's or a namespace's source (source-fn), a section
  (\"docs/x.md#a\": its text, numbered as grep numbers it) or a page whole (seon.doc/markdown), a task's plan
  (its id). nil when it names none of them."
  {:malli/schema [:=> [:cat [:or :symbol :string]] [:maybe :string]]}
  [x]
  (let [db (agent/db)]
    (cond (symbol? x) (source-fn x)
          ;; a section's own text, as grep reads and numbers it (its heading is the address's)
          (str/includes? x "#") (some->> (pdb/eid db :seon.doc/address x) (d/entity db) :seon.doc/text)
          (str/ends-with? x ".md") (doc/markdown db x)
          :else (:seon.task/plan (task/task db x)))))

(defn lines
  "Like sed -n: the lines of x, whole or from line a to line b (1-based, inclusive), one row each
  {:at \"seon.task/accumulate:3\" :text \"...\"}, numbered as grep numbers them, and shown as one block of
  numbered lines (`lines-text`, the model render of :my.agent/lines). x is a var or a namespace
  ('seon.task/accumulate), a section (\"docs/x.md#anchor\"), a page (\"docs/x.md\") or a task's id. Each line
  as it is written, untrimmed (grep's rows are trimmed), so an edit's old text is copied from lines, not grep. A name that holds no text answers my.agent/no-var.
  See docs/agent.md#small-answers."
  {:malli/schema [:function [:=> [:cat [:or :symbol :string]] [:or [:vector :map] :my.agent/no-var]]
                  [:=> [:cat [:or :symbol :string] pos-int? pos-int?] [:or [:vector :map] :my.agent/no-var]]]}
  ([x] (lines x 1 Integer/MAX_VALUE))
  ([x a b]
   (if-let [t (text-of x)]
     (with-meta (into [] (comp (map-indexed (fn [i l] {:at (str x ":" (inc i)) :text l})) (drop (dec a)) (take (inc (- b a))))
                      (str/split-lines t))
       {:seon.render/schema :my.agent/lines})
     (no-var (agent/db) (symbol (str x))))))

(defn lines-text
  "Rows rs of `lines` as the eval shows them (seon.render/model, the schema :my.agent/lines): their
  texts as code, each after its line number, so fifty lines cost their text and not fifty maps naming the
  same thing; the rows stay the value (take, filter and count them). See docs/agent.md#small-answers."
  {:malli/schema [:=> [:cat :seon.render/opts [:vector :map]] :any]}
  [_ rs]
  ;; a row a reader conj'd on without :at keeps the schema: it shows with no number
  (let [n #(or (re-find #"\d+$" (str %)) "")
        w (reduce max 0 (map (comp count n :at) rs))]
    (print/code (str/join "\n" (for [{:keys [at text]} rs :let [k (n at)]] (if (seq k) (str (apply str (repeat (- w (count k)) \space)) k "  " text) text))))))

(defn wc
  "Like wc, in tokens: what x costs printed, as the model is sent it (the one printer's text, whole, by its sizer):
  (wc (grep \"plan\")) before reading it whole."
  {:malli/schema [:=> [:cat :any] nat-int?]}
  [x] (let [ix (agent/branch-index) v (if-let [r (render/model ix x)] (:value r) x)]
        (select/chars-tokens (count (if (string? v) v (:text (print/print-value v {:budget Integer/MAX_VALUE :render #(render/model ix %)})))))))

(defn describe
  "What value v holds, without showing it: a vector of entries, one a path, {:path [...] :holds x}, where :holds is the value when it
  is a short scalar, else what it is as data ({:chars n}, {:map n :keys [...]}, {:vector n}, {:set n}, {:members n :ids [...]} for
  a map keyed by entity ids, :entity for a pulled entity), and, where the key or the entity has a schema, :schema, :doc (the first
  sentence of its :description) and :render (the function its schema declares draws it). Paths are keys, never positions: (get-in v
  path) reads what an entry describes, (describe v path) goes one level in, and for your record (swap! ctx assoc-in path x) changes
  it. depth: the levels described (default :seon.config.agent/describe-depth). Works on any value: @ctx, a result's :value, a
  query's answer. See docs/ctx.md#the-pattern."
  {:seon.model-call/reply "My record can be large, so I look at what it holds first: each path, what is there, and what it is."
   :seon.eval/intent "Describe @ctx: each path, what it holds, its schema and doc in a line. (get-in @ctx path) reads one; (swap! ctx assoc-in path v) changes one."
   :malli/schema [:function [:=> [:cat :any] [:vector :map]] [:=> [:cat :any [:sequential :any]] [:vector :map]]
                  [:=> [:cat :any [:sequential :any] pos-int?] [:vector :map]]]}
  ([v] (describe v [] (cfg/value :seon.config.agent/describe-depth)))
  ([v path] (describe v path (cfg/value :seon.config.agent/describe-depth)))
  ([v path depth] (with-meta (render/describe (agent/branch-index) v path depth) {:seon.render/schema :my.agent/described})))

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
  order (seon.doc/namespace-source), which clojure.repl's does not (pipeline 1's F4: a design
  that asked (source ns) saw the ns form alone, took it for all that was recorded, and spent five
  turns finding the namespace's private functions). sym is a symbol or the var (named)."
  {:malli/schema [:=> [:cat [:or :symbol :seon.host/var]] [:maybe :string]]}
  [sym]
  ;; a name that resolves to a class (java.lang.String) or to anything but a var has no recorded source
  (if-let [v (let [r (if (var? sym) sym (resolve sym))] (when (var? r) r))]
    (or (:source (meta v))
        ;; the branch's record, where there is a branch (a process with no agent's db, a test run's, reads the file)
        (some-> (:db @agent/state) (doc/source-of (named v)))
        (file-source v))
    (when-not (namespace sym)
      ;; the branch's record is the answer: a namespace it does not record has no ns form or privates to list
      (some-> (:db @agent/state) (doc/namespace-source sym)))))

(defn deferred
  "The process tests this branch's change reaches, a proof among them: never run in a fork of an agent; (prove) and a clear
  status run them on the merge gate's checkout (seon.agent/judge!), and the judge's gate before the merge, nobody asking. seon.agent/test-plan's :process among what it selects, each
  with why: the process var it uses and the calls from the test to it (seon.program/process-path)."
  {:malli/schema [:=> [:cat] [:vector [:map [:test :qualified-symbol] [:uses [:vector :qualified-symbol]] [:through [:vector :qualified-symbol]]]]]}
  [] (let [proofs (or (some->> (:task @agent/state) (task/task (agent/db)) :seon.task/accumulated :proofs) #{})
           plan (agent/test-plan (agent/db) (:base @agent/state) proofs (agent/heard-main))]
       (vec (for [t (filter (:process plan) (:selected plan))] (assoc (program/process-path (:graph plan) t) :test t)))))

(defn pst
  "Print the exception e (*e by default) as clojure.repl/pst does: its message, then each cause's
  class and message."
  {:malli/schema [:function [:=> [:cat] :nil] [:=> [:cat :any] :nil]]}
  ([] (if *e (pst *e) (println "no error to print: *e is nil")))
  ([e] (if (instance? Throwable e)
         (do (print (clojure.main/err->msg e))
             (doseq [{:keys [type message]} (rest (:via (Throwable->map e)))]
               (println "Caused by:" type message)))
         (println "pst prints an exception; this is" (class e)))))

(defn- none
  "Why sym is none, in words: no-var's answer with the nearest names (on root, which holds no branch, the process's alone);
  for a var no recorded source holds, that. Never nil, never blank."
  {:malli/schema [:=> [:cat :symbol] :string]}
  [sym]
  (or (:seon.error/message (no-var (:db @agent/state) sym)) (str sym " has no recorded source in this process")))

(defmacro source
  "Print the recorded source of the definition named by sym (as clojure.repl/source does). A
  name is taken as written; any other form (a quoted symbol, #'f, a local of a doseq) is
  evaluated to the symbol or var it holds, as the call reads naturally. A name that is none says so, with the
  nearest names."
  [sym]
  (let [x (if (and (symbol? sym) (not (contains? &env sym))) (list 'quote sym) sym)]
    `(let [x# ~x]
       (println (if (or (symbol? x#) (var? x#))
                  (let [s# (named x#)]
                    (or (source-fn s#) (#'my.agent/none s#)))
                  (str "Source not found for " x# ": source reads a name or a var"))))))

(defn- help-nearest
  "What a help address that is no section may have meant: the help page's sections and the pages
  whose address holds a word of it (four letters or more), the sections whose text the search finds
  for it (`search`: \"schema\" named no address, though the help page says how a schema changes), then the nearest by Jolt's did-you-mean ranking (as no-var): a few
  addresses, never the page ((doc \"docs/agent.md\") lists its sections). See docs/references.md#a-section-read-at-a-depth-with-its-edges."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:vector :string]]}
  [db address]
  (let [sections (doc/page db wire/help-page :summary)
        addresses (mapv :seon.ref/address sections)
        pages (doc/page-paths db)
        words (re-seq #"[a-z0-9]{4,}" (str/lower-case address))
        holds? (fn [s] (some #(str/includes? (str/lower-case s) %) words))
        ranked (jolt.analyzer/suggestions-for address (concat addresses pages))]
    (vec (distinct (concat (filter holds? pages) (filter holds? addresses)
                           (keep #(when (= :section (:kind %)) (:name %)) (search address {:kinds #{:section} :k 3}))
                           ranked)))))

(defn- cited
  "Name n as a cite of it on the agent's page shows it, on demand: a cite of n's target (seon.ref/target) at doc depth made on
  db (one d/with), pulled by the cite's own pattern (seon.ctx/build) and drawn by its render seon.render.ui/cite, so on demand and on
  the page are one render. A name db holds nothing by is `no-var`'s answer, else said to resolve to nothing. See
  docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :seon.host/db [:or :symbol :qualified-keyword]] :any]}
  [db n]
  (let [[a v :as t] (ref/target db {:seon.ref/name n :seon.ref/depth :doc :seon.ref/line 1})]
    (if (pdb/eid db a v)
      (let [r (pdb/speculate db [{:db/id "of" :seon.agent/key (str "help " n)} {:seon.cite/id (str "help " n) :seon.cite/of "of" :seon.cite/target t :seon.cite/depth :doc :seon.cite/order 0}])
            db' (:db-after r)]
        (ui/cite {:seon.db/db db' :seon.render/index (agent/branch-index)} (:tree (seon.ctx/build (agent/branch-index) db' (pdb/eid db' :seon.cite/id (str "help " n)) nil))))
      (or (when (symbol? n) (no-var db n)) [:section [:h3 (str n)] [:p (:unresolved ref/problem-messages)]]))))

(defn- doc-view
  "A section or page view v as `doc` prints it: each edge its address, since (doc address) reads what it says, and no lead when the text below says the same; the
  sections' own text is left whole. See docs/references.md#a-section-read-at-a-depth-with-its-edges."
  {:malli/schema [:=> [:cat :any] :any]}
  [v]
  (clojure.walk/postwalk
   (fn [x]
     (if (and (map? x) (:seon.ref/address x))
       (let [addresses (fn [es] (vec (distinct (map :seon.ref/address es))))]
         (cond-> x
           (:seon.ref/references x) (update :seon.ref/references addresses)
           (:seon.ref/cited-by x) (update :seon.ref/cited-by addresses)
           (= (:seon.ref/lead x) (:seon.ref/text x)) (dissoc :seon.ref/lead)))
       x))
   v))

(defn doc-of
  "The thing x names as data, for `doc`: a schema key (:var/form, its form and what cites it), a wiki address
  (\"docs/vector.md#the-model\" a section whole, \"docs/vector.md\" a page's sections, each by its address, heading and summary without its edges, an anchor of the help page
  \"done\"), a hit of (search ...) or its :name. A miss answers what it did not find, the nearest addresses
  (help-nearest) and the forms that read one, never nil. See docs/references.md#a-section-read-at-a-depth-with-its-edges."
  {:malli/schema [:=> [:cat [:or :string :symbol :qualified-keyword [:map [:kind :keyword]]]] :any]}
  [x]
  (let [db (agent/db)]
    (cond
      (map? x) (let [{:keys [kind name]} x]
                 (case kind
                   :task (select-keys (task/task db name) [:seon.task/id :seon.task/plan :seon.task/proofs :seon.task/needs])
                   :section (doc-view (doc/view db name :code))
                   :more x
                   (cited db name)))
      (symbol? x) (cited db x)
      (keyword? x) (let [f (schema/forms x)] (if (contract/error? f) f (cited db x)))
      :else (let [a (if (str/includes? x "#") x (str wire/help-page "#" x))]
              (or (some-> (doc/view db a :code) doc-view)
                  (some-> (doc/page db x :summary false) doc-view)
                  {:no-section x
                   :nearest (help-nearest db x)
                   :read-one "(doc \"docs/<page>.md\") lists a page's sections; (doc \"docs/<page>.md#<section>\") reads one; (doc 'ns.name) a namespace, every definition a line; (doc :ns/key) a schema key"})))))

(defmacro doc
  "Print the documentation of x and answer nil, as clojure.repl/doc does: a var, a namespace or a special form, named as written
  or quoted (a namespace also lists its definitions, a line each). And what is Seon's: a schema key `(doc :var/form)` or a
  wiki address `(doc \"docs/storage.md#history\")` prints what `doc-of` answers, and so does a local that holds one
  (`(let [a \"docs/storage.md\"] (doc a))`: its value is read). A name that is none says so in words, with the nearest names.
  See docs/references.md#a-section-read-at-a-depth-with-its-edges."
  [x]
  (let [x (if (and (seq? x) (= 'quote (first x))) (second x) x)]
    ;; a symbol the macro's environment binds is a local: its value is read, as a literal's is
    (if (or (not (symbol? x)) (contains? &env x))
      `(do (println (:text (seon.print/print-value (my.agent/doc-of ~x) {:budget Integer/MAX_VALUE}))) nil)
      (let [special ('{& fn catch try finally try} x)]
        (cond (or special (@#'clojure.repl/special-doc-map x)) `(#'clojure.repl/print-doc (#'clojure.repl/special-doc '~(or special x)))
              (find-ns x) `(do (#'clojure.repl/print-doc (#'clojure.repl/namespace-doc (find-ns '~x)))
                               (when (:db @seon.agent/state) (doseq [r# (my.agent/ls '~x)] (println (str "  " (:at r#) (when (:text r#) (str "  " (:text r#))))))))
              (resolve x) `(#'clojure.repl/print-doc (meta (var ~x)))
              :else `(println (#'my.agent/none '~x)))))))

(defmacro dir
  "Print a sorted directory of the public vars of the namespace nsname (an alias too), as clojure.repl/dir does. A name
  that is a var, or none, says so in words, with the nearest names. See docs/agent.md#the-repl."
  [nsname]
  `(let [n# '~nsname]
     (cond (or (find-ns n#) (get (ns-aliases *ns*) n#)) (doseq [v# (clojure.repl/dir-fn n#)] (println v#))
           (resolve n#) (println (str n# " is a var, not a namespace: (doc " n# ") or (source " n# ")"))
           :else (println (#'my.agent/none n#)))))

(defn help
  "The system prompt as an agent is sent it (seon.ctx.wire/system-text), then this namespace's forms: the map of what
  is here. A wiki section, a page, a schema key or a name is `doc`'s (docs/agent.md#finding-your-way)."
  {:malli/schema [:=> [:cat] :string]}
  [] (str (wire/system-text (agent/db)) "\n\n" (:doc (meta (the-ns 'my.agent)))))

;; The REPL's tools in Jolt's clojure.repl: the one place a process gets them, so `clojure.repl/doc` and the bare `doc`
;; are Seon's in every namespace of root, a test child and an agent alike (`seon.agent/repl-name` resolves the bare
;; names there). `intern` is a code smell the rule allows where no definition form exists: these are another
;; namespace's vars (Jolt's clojure.repl lacks source, source-fn and pst, and answers nothing for a missing name);
;; Jolt's own clojure.repl should hold them (docs/resolution.md).
(doseq [s '[source-fn source pst doc dir] :let [v (ns-resolve 'my.agent s)]]
  (intern (find-ns 'clojure.repl) (with-meta s (select-keys (meta v) [:macro :arglists :doc])) @v))
