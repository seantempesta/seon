(ns seon.task-test
  "The task writer derives what a task cites (seon.doc.edn's one-reference-reader, task
  seon.task/a-task-cites-what-it-needs): references from the plan, never authored, each a ref to what it names, and a
  citation that names nothing refused by the write (docs/references.md#an-entity-cites)."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [datahike.api :as d] [seon.repl :as repl]
            [seon.db :as pdb] [seon.task :as task] [seon.ref :as ref] [seon.template :as template] [clojure.string :as str]))

(defn- db-with-program []
  ;; one recorded definition, its form (seon.program's facts), written as data; and a page of the wiki, a schema entry and a
  ;; file, each a thing a cite may name (docs/references.md#an-entity-cites)
  (d/db-with (ts/test-db)
             [{:db/id "ns" :ns/name 'demo :ns/file "src/demo.clj"}
              {:db/id "v" :var/name 'demo/f :var/ns "ns" :var/form "form"}
              {:db/id "form" :form/id "demo/f" :form/src "(defn f [x] x)" :form/ns "ns" :form/order 0 :form/defines ["v"]}
              {:ns/name 'demo.calc :ns/file "src/demo/calc.clj"}
              {:ns/name 'demo.ops :ns/file "src/demo/ops.clj"}
              {:db/id "top" :seon.doc/address "docs/vector.md#vector" :seon.doc/heading "Vector"}
              {:seon.doc/address "docs/vector.md#the-pyramid" :seon.doc/heading "The pyramid" :seon.doc/parent "top"}
              {:seon.doc/address "docs/agent.md#working-a-task" :seon.doc/heading "Working a task"}
              {:schema/key :demo/k :schema/form ":int"}
              {:file/path "resources/demo.edn" :file/text "{}"}]))

(defn- decl [plan] {:seon.task/ns 'demo :seon.task/name "t" :seon.task/plan plan})

(defn- target-of
  "The identity value a cite of a transaction names: its lookup's value, or the tempid of the entity the write makes."
  [m] (let [t (:seon.cite/target m)] (if (vector? t) (second t) t)))

(defn- cited-in
  "What transaction tx makes its entities cite: each cite entity it writes, {target depth}."
  [tx] (into {} (for [m tx :when (and (map? m) (:seon.cite/id m))] [(target-of m) (:seon.cite/depth m)])))

(defn- released
  "db with task id released by the person, as my.root/run! writes it: its :seon.task/started-by, which its agent's start names
  its parent (seon.task/start-tx)."
  [db id] (d/db-with db [{:seon.task/id id :seon.task/started-by {:seon.agent/key "sean"}}]))

(defn- refusal
  "The typed error the checked write of tx on db answers (seon.db/with), or nil when it is written."
  [db tx] (try (pdb/with db tx) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; docs/references.md#task-references: a plan naming its own task's id, the function it will make, cites what the db holds after
;; the write: the task the same write makes until the function is written, never nothing (a new task app.greet/greet whose plan names
;; app.greet/greet was refused, so no task could be filed for a function not yet written)
(deftest a-task-naming-its-own-function-cites-what-the-write-holds
  (let [db (db-with-program)
        tx (task/create-tx db [(decl "Make `demo/t` work, after `demo/f`.")])
        db' (pdb/db-with db tx)
        targets (into {} (for [m tx :when (and (map? m) (:seon.cite/id m))] [(target-of m) (:seon.cite/target m)]))]
    (is (= [:seon.task/id "demo/t"] (targets "demo/t")) "its own name: the task the write makes")
    (is (= ["demo/t" 'demo/f] (mapv task/cited-name (task/cites-of db' (pdb/eid db' :seon.task/id "demo/t")))) "and the write is not refused")
    (let [db2 (pdb/db-with db' [{:var/name 'demo/t}])]
      (is (= [:var/name 'demo/t] (:seon.cite/target (first (filter :seon.cite/id (task/rederive-tx db2 (task/task db2 "demo/t"))))))
          "once the function is written, a rewrite of the text cites it"))))

;; docs/references.md#task-references: a task's text is parsed once at its write, and each target it cites is a cite entity
(deftest the-writer-writes-a-tasks-cites
  (let [plan "Make `demo/f` pass; ``demo/later`` is only mentioned; `(demo/f 1)` is code. Read [vectors](docs/vector.md#the-pyramid), then ```demo/f``` whole."
        tx (task/create-tx (db-with-program) [(decl plan)])
        cites (filterv :seon.cite/id tx)]
    (is (= {'demo/f :code "docs/vector.md#the-pyramid" :doc} (cited-in tx)) "one per target, at the strongest depth its text asks: no literal, no form")
    (is (= [0 1] (mapv :seon.cite/order cites)) "in the order of first mention")
    (is (= #{"demo/t demo/f" "demo/t docs/vector.md#the-pyramid"} (set (map :seon.cite/id cites))) "each named by its task and its target")
    (is (every? #(= "demo/t" (:seon.cite/of %)) cites) "each of the task the same write makes (its tempid)")
    (is (= {} (cited-in (task/create-tx (db-with-program) [(decl "No names here.")]))) "no names, no cites")))

;; docs/references.md#an-entity-cites: a cite's target is a ref to the thing it names, of six kinds
(deftest a-cite-is-written-with-its-target-ref
  (let [db0 (db-with-program)
        db0 (d/db-with db0 (task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "other" :seon.task/plan "p"}]))
        plan "Read [the pyramid](docs/vector.md#the-pyramid), change `demo/f`, after `demo/other`, set `:demo/k`, in `demo.calc`, see `resources/demo.edn` and `src/demo/ops.clj`."
        db (pdb/db-with db0 (task/create-tx db0 [(decl plan)]))
        kinds (mapv (fn [c] (some #(when (get-in c [:seon.cite/target %]) %) ref/target-attrs)) (task/cites-of db (pdb/eid db :seon.task/id "demo/t")))]
    (is (= [:seon.doc/address :var/name :seon.task/id :schema/key :ns/name :file/path :ns/name] kinds)
        "a section, a var, a task, a schema entry, a namespace, a file, and a namespace's file: each its entity")
    (is (= ["docs/vector.md#the-pyramid" 'demo/f "demo/other" :demo/k 'demo.calc "resources/demo.edn" 'demo.ops]
           (mapv task/cited-name (task/cites-of db (pdb/eid db :seon.task/id "demo/t")))))))

;; docs/references.md#an-entity-cites: a cite names what exists; a name that does not is the write's refusal, naming it
(deftest a-cite-of-nothing-is-refused-naming-its-target
  (let [db (db-with-program)]
    (is (= {:seon.error/type :seon.db/no-target :target [:var/name 'demo/nope] :by "demo/t"}
           (select-keys (refusal db (task/create-tx db [(decl "Make `demo/f` and `demo/nope` pass.")])) [:seon.error/type :target :by]))
        "naming the task that cites it, so whoever meets the refusal can say which text to fix")
    (is (= [:schema/key :no.such/key] (:target (refusal db (task/create-tx db [(decl "Set `:no.such/key`.")])))) "a schema key no entry holds: the same refusal")
    (is (= [:seon.doc/address "docs/vector.md#nowhere"] (:target (refusal db (task/create-tx db [(decl "Read [n](docs/vector.md#nowhere).")])))))
    (is (= [:file/path "docs/nowhere.md"] (:target (refusal db (task/create-tx db [(decl "Read [n](docs/nowhere.md).")]))))
        "a page the db holds no section of, and no file: nothing")))

;; docs/references.md#an-entity-cites: the exception: a target the same write makes whole, named by its tempid
(deftest a-cite-of-what-the-same-write-makes-is-written
  (let [db (db-with-program)
        tx (task/create-tx db [(decl "Run after `demo/u`.") {:seon.task/ns 'demo :seon.task/name "u" :seon.task/plan "First."}])
        db' (pdb/db-with db tx)]
    (is (= {"demo/u" :doc} (cited-in tx)) "its target is the tempid of the task the write makes")
    (is (= ["demo/u"] (mapv task/cited-name (task/cites-of db' (pdb/eid db' :seon.task/id "demo/t")))) "written, a ref to that task")))

;; docs/references.md#the-delimiter-picks-the-depth: two backticks are a literal, which cites nothing (a plan names what it will
;; create so)
(deftest a-two-backtick-literal-cites-nothing
  (let [db (db-with-program)
        tx (task/create-tx db [(decl "Add ``demo/later`` and ``:demo/later-key`` beside `demo/f`.")])]
    (is (= {'demo/f :doc} (cited-in tx)))
    (is (nil? (refusal db tx)) "and the write is not refused")))

(deftest deleting-a-cited-var-ends-its-cites-and-the-task-holds-what-is-gone
  ;; docs/references.md#task-references: a cite is a ref to what exists, and it ends with its target
  ;; (docs/storage.md#the-write-checks-every-entity-it-touches): the write that deletes what a task's text cites is accepted
  ;; (it was refused, so an import retracting a var a generated task cited refused whole, 2026-11-10), and leaves the task
  ;; :seon.task/gone naming it, which the text written again clears
  (let [db0 (db-with-program)
        db (pdb/db-with db0 (task/create-tx db0 [(decl "Make `demo/f` exact.")]))
        gone [[:db/retractEntity (pdb/eid db :var/name 'demo/f)]]
        after (pdb/db-with db gone)
        held #(:seon.task/gone (d/pull % [:seon.task/gone] [:seon.task/id "demo/t"]))]
    (is (nil? (refusal db gone)) "the delete is not refused")
    (is (nil? (pdb/eid after :var/name 'demo/f)) "the var is deleted")
    (is (= [] (task/cites-of after (pdb/eid after :seon.task/id "demo/t"))) "and its cite with it")
    (is (= ['demo/f] (held after)) "the task holds what is gone")
    (is (nil? (held db)) "and held nothing before")
    (let [plan "Make ``demo/f`` exact again, once it is written."]
      (is (nil? (held (pdb/db-with after (into [[:db/add [:seon.task/id "demo/t"] :seon.task/plan plan]]
                                               (task/rederive-tx after {:seon.task/id "demo/t" :seon.task/plan plan})))))
          "the text written again clears it"))))

(deftest a-page-cited-whole-is-its-top-section
  ;; docs/references.md#an-entity-cites: a path with no anchor cites the page's top section, whose sections join below it
  (is (= {"docs/vector.md#vector" :doc} (cited-in (task/create-tx (db-with-program) [(decl "Read [x](docs/vector.md).")]))) "the page the db holds: its top section"))

(deftest a-rewritten-text-leaves-exactly-its-cites
  ;; the write of a task's text (seon.agent/transact!'s with-citations calls rederive-tx): its cites become the new
  ;; text's, one per target at its strongest depth, upserted by their content id, and the ones it no longer cites retracted
  (let [db0 (d/db-with (db-with-program) [{:var/name 'demo/g}])
        db (d/db-with db0 (task/create-tx db0 [(decl "Make `demo/f` pass; see [v](docs/vector.md#the-pyramid).")]))
        db (d/db-with db [[:db/add [:seon.task/id "demo/t"] :seon.task/plan "Make ```demo/f``` pass, and `demo/g`."]])
        db (d/db-with db (task/rederive-tx db (task/task db "demo/t")))
        cites (task/cites-of db (pdb/eid db :seon.task/id "demo/t"))]
    (is (= [['demo/f :code 0] ['demo/g :doc 1]] (mapv (juxt task/cited-name :seon.cite/depth :seon.cite/order) cites)) "the new text's cites, demo/f deepened in place")
    (is (empty? (d/datoms db :avet :seon.cite/id "demo/t docs/vector.md#the-pyramid")) "the section it no longer cites, retracted whole")
    (is (not-any? #(some-> (:a %) keyword? (and (= "seon.cite" (namespace (:a %))))) (:tx-data (d/with db (task/rederive-tx db (task/task db "demo/t")))))
        "written again: no cite datom changes")))

(deftest an-agent-is-made-citing-what-every-agent-cites
  ;; :seon.config.agent/cites written on a new agent's record (seon.task/agent-cites-tx, at its first start: seon.agent/begin-run!), never
  ;; read as a default at draw time: each address a section of the wiki, at its ?depth
  (let [db (d/db-with (db-with-program) (for [a (seon.config/value :seon.config.agent/cites)] {:seon.doc/address a :seon.doc/heading a}))
        db (released (d/db-with db (task/create-tx db [(decl "Do.")])) "demo/t")
        tx (into (task/start-tx db "demo/t" "b1" :a1 {:branch :b :model nil :max-turns 4} 0) (task/agent-cites-tx db "a1" "a1"))
        db (pdb/db-with db tx)]
    (is (= (count (seon.config/value :seon.config.agent/cites)) (count (filter :seon.cite/id tx))) "one per address the config names")
    (is (= (mapv (fn [a] [a :doc]) (seon.config/value :seon.config.agent/cites))
           (mapv (juxt task/cited-name :seon.cite/depth) (task/cites-of db (pdb/eid db :seon.agent/key "a1"))))
        "on the agent's record, in the config's order (the references it is told it can read, not pulled into its context whole)")))

;; docs/references.md#an-entity-cites: a store may lack a section the config cites (a store older than its wiki). An agent is
;; then made citing the sections the store holds, the write is one a db takes (a cite of a missing target threw "Nothing found
;; for entity id" in the fork server's step, and every spawn failed), and what was left out is data: the addresses, by name
(deftest an-agent-is-made-though-the-store-lacks-a-section-the-config-cites
  (let [held (vec (seon.config/value :seon.config.agent/cites)) gone "docs/nowhere.md#never-written"
        base (d/db-with (db-with-program) (for [a held] {:seon.doc/address a :seon.doc/heading a}))
        ;; the task's own set names one address the store holds no section for, between two it holds
        db (released (d/db-with base (task/create-tx base [(assoc (decl "Do.") :seon.task/config {:seon.config.agent/cites [(first held) gone (second held)]})])) "demo/t")
        db (pdb/db-with db (task/start-tx db "demo/t" "b1" :a1 {:branch :b :model nil :max-turns 4} 0))
        tx (task/agent-cites-tx db [:seon.agent/key "a1"] "a1")
        db' (pdb/db-with db tx)]
    (is (nil? (pdb/eid db :seon.doc/address gone)) "the store holds no such section")
    (is (= [(first held) (second held)] (mapv task/cited-name (task/cites-of db' (pdb/eid db' :seon.agent/key "a1")))) "a cite for each section the store holds, in the config's order")
    (is (= [gone] (task/agent-cites-missing db "a1")) "the address the store lacks, named")))

(deftest a-task-may-name-what-its-agent-cites
  ;; docs/config.md: a task holds a config key as its own setting, read before the shipped value; a role's standing cites are
  ;; :seon.config.agent/cites on its task (its template sets it), so a worker and a preparer can read different sections
  (let [own ["docs/testing.md#testing-in-the-world"]
        base (d/db-with (db-with-program) (for [a (seon.config/value :seon.config.agent/cites)] {:seon.doc/address a :seon.doc/heading a}))
        cited (fn [config]
                (let [db (released (d/db-with base (task/create-tx base [(cond-> (decl "Do.") config (assoc :seon.task/config config))])) "demo/t")
                      db (pdb/db-with db (task/start-tx db "demo/t" "b1" :a1 {:branch :b :model nil :max-turns 4} 0))
                      db (pdb/db-with db (task/agent-cites-tx db [:seon.agent/key "a1"] "a1"))]
                  (mapv task/cited-name (task/cites-of db (pdb/eid db :seon.agent/key "a1")))))]
    (is (= own (cited {:seon.config.agent/cites own})) "the task's own set, not the shipped four")
    (is (= (seon.config/value :seon.config.agent/cites) (cited nil)) "a task holding none: the shipped set, in its order")))

(deftest the-writer-cites-what-names-something
  ;; audit item 2a: a reproduction's text names a test helper (demo-test/fixture) and a handle (result/e-none) that name
  ;; nothing: a text word is cited only when it names something (seon.ref/kept); the program's names among the cites
  ;; are what the judge reads (seon.judge/subject). A handle no eval makes: seon.ref also asks this process, and a shard's
  ;; other tests define result/e1
  (let [db (d/db-with (db-with-program) [{:ns/name 'demo-test :ns/file "test/demo_test.clj"} {:var/name 'demo/g} {:var/name 'demo-test/f-test}])
        plan (str "Make demo/f exact and add `demo/g`. Reproduce: (let [db (demo-test/fixture)] (demo/f db)) answers result/e-none.")
        names (set (keep target-of (task/create-tx db [(decl plan)])))]
    (is (every? names '[demo/f demo/g]) "the defined name and the backticked one")
    (is (not-any? names '[demo-test/fixture result/e-none]) "and no text word that names nothing")
    (testing "a test the plan names is cited as any name; the page shows it as the task's proof"
      (let [tx (task/create-tx db [(assoc (decl "Make `demo-test/f-test` pass.") :seon.task/proofs #{'demo-test/f-test})])]
        (is (= #{'demo-test/f-test} (:seon.task/proofs (first tx))))))))

(deftest a-templates-instructions-are-cited-as-a-plan-is
  ;; owner, 2026-10-22: instructions are cited text; a var a kind's instructions cite is one of the task's cites,
  ;; and so its context, exactly as a plan's is
  (doseq [kind [:design :implement :test :merge]
          :let [db (db-with-program)
                ins (template/instructions kind)
                kept (keep #(second (ref/target db %)) (ref/kept db (ref/cited (:seon.ref/citations (ref/parse nil ins)))))
                tx (task/create-tx db [(assoc (decl "") :seon.task/instructions ins)])]]
    (is (= (set kept) (set (keep target-of tx))) (str kind ": every name it cites"))))

(deftest a-plan-and-its-instructions-are-cited-apart
  ;; md-scan bench-b17: the plan quotes a fenced block's ``` marks, and parsed as one text with the instructions that
  ;; run opened a code span, so the instructions' ```docs/agent.md#working-a-task``` was cited at summary (its heading
  ;; alone) and the agent was never sent how a task is worked
  (let [db (db-with-program)
        tx (task/create-tx db [(assoc (decl "Drop a fenced block's ``` marks from `demo/f`.")
                                      :seon.task/instructions "How: ```docs/agent.md#working-a-task```.")])
        cite (first (filter #(= "docs/agent.md#working-a-task" (target-of %)) tx))]
    (is (= :code (:seon.cite/depth cite)) (str "the instructions' citation at the depth its own delimiter asks: " (pr-str cite)))
    (is (some #(= 'demo/f (target-of %)) tx) "and the plan's own")))

(deftest the-reader-answers-every-stored-member
  ;; research row 20: the reader pulls what main stores (Datahike's wildcard), so a task
  ;; attribute is written in one place; references were stored and never read back
  (let [db (d/db-with (db-with-program) (task/create-tx (db-with-program) [(decl "Make `demo/f` pass.")]))
        t (task/task db "demo/t")]
    (is (= ['demo/f] (mapv task/cited-name (task/cites-of db (pdb/eid db :seon.task/id "demo/t")))) "its cites, entities of their own")
    (is (not-any? #{:db/id :seon.task/declared} (keys t)) "the stored declaration is load-tx's, not the reader's")
    (is (= t (get (task/tasks db) "demo/t")))))

(deftest a-resumed-branch-takes-its-needs-from-main
  ;; a kept branch resumed for a run may have been forked before main held the tasks its
  ;; task needs (the step E dry run read its task on its branch as nil and threw).
  ;; onto-tx puts the task and what it needs there, as main holds them, and leaves another
  ;; task the branch already holds as the branch has it
  (let [db0 (ts/test-db)
        branch (d/db-with db0 (task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "Branch's own a."}]))
        main (d/db-with branch (task/create-tx branch [{:seon.task/ns 'demo :seon.task/name "schema" :seon.task/plan "Register the schema." :seon.task/needs #{"demo/a"}}
                                                       {:seon.task/ns 'demo :seon.task/name "store" :seon.task/plan "" :seon.task/needs #{"demo/schema"}}]))
        main (d/db-with main [{:seon.task/id "demo/a" :seon.task/plan "Main's a."}])
        tx (task/onto-tx main branch "demo/store")
        after (d/db-with branch tx)]
    (is (= #{"demo/schema" "demo/store"} (set (map :seon.task/id tx))) "the task and what it needs that the branch lacks")
    (is (= "Register the schema." (:seon.task/plan (task/task after "demo/schema"))))
    (is (= #{"demo/schema"} (:seon.task/needs (task/task after "demo/store"))) "its needs resolve on the branch")
    (is (= "Branch's own a." (:seon.task/plan (task/task after "demo/a"))) "a task the branch holds is left as the branch has it")
    (is (= [] (task/onto-tx main after "demo/store")) "nothing when the branch holds them")))

(deftest a-released-branch-takes-its-task-from-main
  ;; owner, 2026-10-23: a refused end holds its task for root, and root's release starts it
  ;; again on the same, kept branch; root's judge wrote the failure and the evidence on main
  ;; after the branch last moved, so the resumed agent takes its task as main holds it
  (let [db0 (ts/test-db)
        branch (d/db-with db0 (task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "Make a true."}]))
        ev {:from "demo/a" :conflicts [] :unmerged [] :red ['demo-test/a-test] :problem "proof-red demo-test/a-test"}
        main (d/db-with branch [{:seon.task/id "demo/a" :seon.task/evidence ev :seon.task/failure {:reason "proof-red demo-test/a-test" :agent "boot-1/a1"}}])
        after (d/db-with branch (task/onto-tx main branch "demo/a"))]
    (is (= ev (:seon.task/evidence (task/task after "demo/a"))) "the evidence")
    (is (= "boot-1/a1" (:agent (:seon.task/failure (task/task after "demo/a")))) "and the failure it answers")
    (is (= [] (task/onto-tx main after "demo/a")) "nothing when the branch holds it as main does")))

(deftest a-tasks-lifecycle-is-read-from-its-schema
  ;; task seon.task/a-runs-lifecycle-is-read-once: the sets are the schema's, and the two reads
  ;; every view and judge shares answer from the tasks' own outcomes
  (let [db0 (d/db-with (ts/test-db) [{:seon.task/id "demo/a" :seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :b :seon.task/kind :work}
                                     {:seon.task/id "demo/b" :seon.task/ns 'demo :seon.task/name "b" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :b2 :seon.task/kind :work}])
        start (fn [db tid ag] (d/db-with db (task/start-tx db tid "boot-1" ag {:branch :b :model nil :max-turns 4} 1000)))
        db (-> db0 (start "demo/a" :a1) (start "demo/b" :a2)
               (d/db-with [{:seon.task/id "demo/b" :seon.task/outcome :merged}]))
        te #(:db/id (d/entity db [:seon.task/id %]))]
    (is (= #{:done :merged} task/finished) "the schema's finished set")
    (is (= ["demo/a"] (mapv :task (task/running db))) "only the running task, one seek of the outcome")
    (is (= [:a1 "a1"] ((juxt :agent :key) (first (task/running db)))) "with its agent's id and key")
    (is (false? (task/done? db (te "demo/a"))) "a running task is not done")
    (is (true? (task/done? db (te "demo/b"))) "a merged one is")))

;; ---- the links between root, an agent, its task, turns and messages are refs: a pull follows them ----------------------------

(deftest an-agents-links-pull-from-its-root
  ;; root records its agent on main; the reconciler records the task's start, naming the agent by its key;
  ;; the agent's branch records a turn of the agent, its evaluations and the message it took: each
  ;; written by its producer's own function, each link a ref a pull follows from root down
  (ts/test-branch!
   (fn [cfg conn]
     (let [main (:branch cfg) b (keyword (str (name main) "-a3"))
           k "a3"]
       (d/transact conn [{:seon.system/id :root :seon.system/boot "b1"}])
       (d/transact conn [{:seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :task-demo-f :seon.task/kind :work
                          :seon.task/started-by {:seon.agent/key "owner"}}])
       (d/transact conn (task/agent-tx :a3 {:status :idle :pid 77 :running {:form "(f 2)"}}))
       (d/branch! conn main b)
       (let [bc (pdb/connect (assoc cfg :branch b))]
         (try
           (d/transact conn (task/start-tx @conn "demo/f" "b1" :a3 {:branch b :model "m" :max-turns 8} 0))
           (d/transact conn (task/message-tx "m-1" (seon.task/sender "owner") [k] "check g" 1000))
           ;; on the branch, which forked before main held the task's start or the message
           (d/transact bc (ts/turn-tx k 1 {}))
           (d/transact bc (task/message-tx "m-1" (seon.task/sender "owner") [k] "check g" 1000))
           (d/transact bc (repl/record-tx {:seon.agent/key k} "e1" (seon.task/sender "a3") {:seon.eval/code "(f 2)" :seon.eval/response {:handle 'result/e1 :value 4}}))
           (testing "from root on main: its agents, each agent's task"
             (is (= [{:seon.agent/key "a3" :seon.agent/status :idle :seon.process/_agent [{:seon.process/pid 77 :seon.process/running "(f 2)"}]
                      :seon.task/_agent [{:seon.task/id "demo/f"}]}]
                    (:seon.agent/_root (d/pull @conn '[{:seon.agent/_root [:seon.agent/key :seon.agent/status
                                                                           {:seon.process/_agent [:seon.process/pid :seon.process/running]}
                                                                           {:seon.task/_agent [:seon.task/id]}]}]
                                               [:seon.system/id :root])))))
           (testing "the message is to the agent by its key, on main"
             (is (= ["check g"] (d/q (quote [:find [?b ...] :in $ ?k :where [?a :seon.agent/key ?k] [?m :seon.message/to ?a] [?m :seon.message/body ?b]]) @conn k))))
           (testing "on the branch: the agent's model calls and the message it took, the agent's evaluations"
             (let [agent (d/pull @bc '[{:seon.model-call/_agent [:seon.model-call/id]} ] [:seon.agent/key k])]
               (is (= [{:seon.model-call/id (seon.lib/call-id k 1)}] (:seon.model-call/_agent agent)))
               (is (= ["m-1"] (d/q (quote [:find [?id ...] :in $ ?k :where [?a :seon.agent/key ?k] [?m :seon.message/to ?a] [?m :seon.message/id ?id]]) @bc k))))
             (is (= ["e1"] (mapv :seon.eval/id (:seon.eval/_agent (d/pull @bc '[{:seon.eval/_agent [:seon.eval/id]}] [:seon.agent/key k]))))))
           (testing "the message and the agent are one entity on the branch and on main (content ids)"
             (doseq [ref [[:seon.message/id "m-1"] [:seon.agent/key k]]]
               (is (= (:db/id (d/entity @conn ref)) (:db/id (d/entity @bc ref))) (pr-str ref))))
           (testing "the task's reader names its agent by key"
             (is (= k (:seon.task/agent (task/task @conn "demo/f")))))
           (finally (d/release bc) (d/delete-branch! conn b))))))))

(deftest an-agents-live-state-is-facts-on-its-entity
  ;; what root observes of an agent is recorded on the agent's entity: a member no longer true
  ;; is retracted, and an agent is named by its key, whatever boot
  (let [db0 (d/db-with (ts/test-db) [{:seon.system/id :root :seon.system/boot "b1"}])
        db1 (d/db-with db0 (task/agent-tx :a3 {:status :running :pid 77 :running {:form "(f 2)" :since-ms 5} :mem-mb 120.5}))
        db2 (d/db-with db1 (task/agent-tx :a3 {:status :idle :pid 77 :running nil}))
        db3 (d/db-with db2 (task/agent-tx :a3 {:status :spawning}))
        pull (fn [db] (d/pull db [:seon.agent/status {:seon.agent/root [:seon.system/id]}
                                   {:seon.process/_agent [:seon.process/pid :seon.process/running]}]
                                   [:seon.agent/key "a3"]))
        proc (fn [m] {:seon.agent/status (:seon.agent/status m) :seon.agent/root (:seon.agent/root m) :p (first (:seon.process/_agent m))})]
    (is (= {:seon.agent/status :running :seon.agent/root {:seon.system/id :root} :p {:seon.process/pid 77 :seon.process/running "(f 2)"}} (proc (pull db1))))
    (is (= {:seon.agent/status :idle :seon.agent/root {:seon.system/id :root} :p {:seon.process/pid 77}} (proc (pull db2)))
        "the form returned: no longer running")
    (is (= :spawning (:seon.agent/status (pull db3))) "a later fact of the same agent")))

(deftest a-new-boot-keeps-the-earlier-boots-agents
  ;; docs/users.md#roots-agent: a boot keeps each earlier boot's agent linked to root (root's page holds every boot's
  ;; sessions), marking one still shown live :dead; red before: a boot retracted every link but the user's and root's session's
  (let [db (d/db-with (ts/test-db) (into [{:seon.system/id :root :seon.system/boot "b1"}] (task/agent-tx :a1 {:status :idle})))
        db2 (d/db-with db (into (task/boot-tx "b2" "sean") (task/earlier-agents-tx db)))
        db3 (d/db-with db2 (task/agent-tx :a2 {:status :spawning}))
        agents (fn [db] (set (map :seon.agent/key (:seon.agent/_root (d/pull db '[{:seon.agent/_root [:seon.agent/key]}] [:seon.system/id :root]))))) ]
    (is (= #{"a1"} (agents db)))
    (is (= #{"sean" "a1"} (agents db2)) "the new boot keeps the earlier boot's agent, beside its user")
    (is (= :dead (:seon.agent/status (d/entity db2 [:seon.agent/key "a1"]))) "though its process ended with that boot")
    (is (= #{"sean" "a1" "a2"} (agents db3)) "and holds its own as they start")
    (is (= #{"sean" "a1" "a2"} (agents (d/db-with db3 (into (task/boot-tx "b3" "sean") (task/earlier-agents-tx db3))))) "a later boot keeps them all")))

(deftest a-design-adds-a-proof-with-update
  ;; owner, 2026-10-25 (Dogfood rulings): my.task/update! takes :seon.task/proofs, so a design
  ;; registers the red proofs it writes; its write is seon.task/member-tx, a set member's adds and
  ;; retracts, a single member's new value
  (let [t {:seon.task/id "demo/t" :seon.task/plan "p" :seon.task/proofs #{'demo-test/a-test}}]
    (is (= [[:db/add [:seon.task/id "demo/t"] :seon.task/proofs 'demo-test/b-test]]
           (task/member-tx t :seon.task/proofs conj ['demo-test/b-test])))
    (is (= [[:db/retract [:seon.task/id "demo/t"] :seon.task/proofs 'demo-test/a-test]]
           (task/member-tx t :seon.task/proofs disj ['demo-test/a-test])))
    (is (= [[:db/add [:seon.task/id "demo/t"] :seon.task/proofs 'demo-test/b-test]]
           (task/member-tx (dissoc t :seon.task/proofs) :seon.task/proofs conj ['demo-test/b-test])) "a task with no proofs yet")
    (is (= [[:db/add [:seon.task/id "demo/t"] :seon.task/plan "p, better"]]
           (task/member-tx t :seon.task/plan str [", better"])))
    (is (= :seon.task/invalid-member (:seon.error/type (task/member-tx t :seon.task/proofs (constantly nil) []))) "proofs that are no set of names: the error, never #{}")
    (is (= :seon.task/invalid-member (:seon.error/type (task/member-tx t :seon.task/proofs conj ["not-a-symbol"]))))
    (is (= :seon.task/invalid-member (:seon.error/type (task/member-tx t :seon.task/plan (constantly 3) []))) "a plan that is no string")
    (is (= 3 (:value (task/member-tx t :seon.task/plan (constantly 3) []))) "what it would be is the error's data, printed by its render, not text in its message")
    (let [db (d/db-with (ts/test-db) (task/create-tx (ts/test-db) [(decl "Make t true.")]))
          after (d/db-with db (task/member-tx (task/task db "demo/t") :seon.task/proofs conj ['demo-test/t-test]))]
      (is (= #{'demo-test/t-test} (set (:seon.task/proofs (task/task after "demo/t")))) "written, the task holds it"))))

(deftest a-root-before-its-first-boot-is-no-entity-value
  ;; a store an earlier build left (seon.system-test/a-root-resumes-a-store-an-earlier-build-wrote):
  ;; root's entity holds a quiescent fact and no boot, so (task/root db) is nil, and
  ;; entity-value answered [:root nil] against its own contract: the reconciler's step read it as
  ;; a :platform defect on every resume. Before its boot root is no value the index holds.
  (let [db (d/db-with (ts/test-db) [{:seon.system/id :root :seon.system/quiescent {:t 536870914 :root-causes []}}])
        e (:db/id (d/entity db [:seon.system/id :root]))]
    (is (nil? (task/root db)))
    (is (nil? (task/entity-value db e)))
    (let [booted (d/db-with db [{:seon.system/id :root :seon.system/boot "b1"}])]
      (is (= :root (first (task/entity-value booted e))))
      (is (= "b1" (:boot (second (task/entity-value booted e))))))))

(deftest a-message-on-the-branch-is-delivered
  ;; docs/users.md#messages (owner, 2026-11-09): the entity on the agent's branch is the proof of delivery: one a fork inherited is delivered at the fork, one removed (its ref retracted, the entity kept) stays so
  (let [main (d/db-with (ts/test-db) (into [{:seon.agent/key "a"}] (task/message-tx "m1" (seon.task/sender "sean") ["a"] "first" 1)))
        away (d/db-with (ts/test-db) [{:seon.agent/key "a"}])
        removed (d/db-with main [[:db/retract (:db/id (d/entity main [:seon.message/id "m1"])) :seon.message/to (:db/id (d/entity main [:seon.agent/key "a"]))]])]
    (is (= ["m1"] (map :id (task/undelivered main away "a"))) "a branch that does not hold it is owed it")
    (is (= [] (task/undelivered main main "a")) "inherited at the fork: delivered")
    (is (= [] (task/undelivered main removed "a")) "removed after, it stays delivered")))

(deftest a-fork-writes-its-child-as-one-checked-entity-and-its-messages-have-their-entries
  ;; the one-unit spec (section 1 and its ports): the fork writes the child with its parent, env (branch, commit, t) and options in one
  ;; checked write; a process's end, the link's two unions and a request's address each have their entry
  (let [db (d/db-with (ts/test-db) [{:seon.agent/key "root"}])
        child {:seon.agent/key "c1" :seon.agent/parent [:seon.agent/key "root"] :seon.agent/branch :task-c1 :seon.agent/commit (random-uuid)
               :seon.agent/t 12 :seon.agent/model-turns false :seon.agent/render-walk false :seon.agent/interrupts false :seon.agent/keeps-branch false}
        ok? (fn [k v] (malli.core/validate (seon.contract/schema k) v))]
    (is (nil? (refusal db [child])) "a test child's fork: parent, env and the four options, one checked write")
    (is (some? (refusal db [(assoc child :seon.agent/t "12")])) "and a t that is no int is refused")
    (is (nil? (refusal db [{:seon.process/id "c1/1" :seon.process/pid 4242 :seon.process/exit 0}])) "a process records its exit status")
    (is (ok? :seon.process/end {:seon.process/pid 4242 :seon.process/exit 1}) "a child's end: its pid and exit status")
    (is (not (ok? :seon.process/end {:seon.process/pid 4242})) "an end names its status")
    (is (ok? :seon.link/down {:seon.step/request-id 3 :seon.step/to :api :op :run}) "down a link: a request")
    (is (ok? :seon.link/down :interrupt) "or a control, interrupt among them")
    (is (ok? :seon.link/up {:seon.step/request-id 3 :seon.step/value {:pass 1}}) "up a link: an answer")
    (is (ok? :seon.link/up {:seon.step/at-ms 1 :type :spawned}) "an event")
    (is (ok? :seon.link/up {:seon.step/request-id 4 :seon.step/to :api}) "or a request routed up by its address")
    (is (not (ok? :seon.link/up {:op :run})) "and nothing else")))

(deftest a-started-agent-names-the-agent-it-is-forked-from-its-parent
  ;; docs/users.md#who-started-whom: an agent names the agent it is forked from, :seon.agent/parent, in the write that makes it: the agent
  ;; that started its task (a launch, a refine), or root's agent when a person did (a person forks nothing); who asked stays the task's
  (let [db (d/db-with (ts/test-db) (into [{:seon.system/id :root :seon.system/boot "b1"} {:seon.agent/key "sean" :seon.user/name "sean"}
                                          {:seon.agent/key "root"} {:seon.agent/key "lead"}]
                                         (task/create-tx (ts/test-db) [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "A." :seon.task/kind :work}])))
        db (d/db-with db [{:seon.task/id "demo/a" :seon.task/started-by [:seon.agent/key "sean"]}])
        env {:branch :task-demo-a :model nil :max-turns 40}
        started (d/db-with db (task/start-tx db "demo/a" "b1" :a1 env 1000))
        parent (fn [db k] (:seon.agent/key (:seon.agent/parent (d/entity db [:seon.agent/key k]))))]
    (is (= "root" (parent started "a1")) "a person's request: its agent is root's child")
    (is (= #{"a1"} (set (map :seon.agent/key (:seon.agent/_parent (d/entity started [:seon.agent/key "root"]))))) "root's children hold it")
    (is (nil? (refusal db (task/start-tx db "demo/a" "b1" :a1 env 1000))) "and the write that makes it passes the one path")
    (let [launched (d/db-with db [{:seon.task/id "demo/a" :seon.task/started-by [:seon.agent/key "lead"]}])]
      (is (= "lead" (parent (d/db-with launched (task/start-tx launched "demo/a" "b1" :a1 env 1000)) "a1")) "an agent's launch: its launcher's child"))
    (let [again (d/db-with started [{:seon.task/id "demo/a" :seon.task/started-by [:seon.agent/key "lead"]}])]
      (is (= "root" (parent (d/db-with again (task/start-tx again "demo/a" "b1" :a1 env 2000)) "a1")) "an agent that continues keeps the parent it was made with"))
    (let [none (d/db-with db [[:db/retract [:seon.task/id "demo/a"] :seon.task/started-by [:seon.agent/key "sean"]]])]
      (is (nil? (parent (d/db-with none (task/start-tx none "demo/a" "b1" :a1 env 1000)) "a1")) "a task nobody started gives its agent no parent, never an invented one"))
    (is (nil? (task/forked-from db "demo/a" "root")) "and an agent is never its own parent: root's own task names none")
    (testing "a refine is started by the agent whose run it refines: its agent is that one's child"
      (let [ended (d/db-with started [{:seon.task/id "demo/a" :seon.task/outcome :merged}])
            r (d/db-with ended (task/refine-tx ended "demo/a"))]
        (is (= "a1" (:seon.agent/key (:seon.task/started-by (d/entity r [:seon.task/id "demo/a-refine"])))))
        (is (= "a1" (parent (d/db-with r (task/start-tx r "demo/a-refine" "b1" :a2 {:branch :task-demo-a-refine :model nil :max-turns 40} 3000)) "a2")))))))


;; docs/decisions.md#an-id-is-the-content-of-the-facts-that-make-it-owner-2026-11-08 (docs/decisions.md#an-id-is-the-content-of-the-facts-that-make-it-owner-2026-11-08)
(deftest an-agent-is-named-by-a-unique-id-and-a-call-by-its-content
  (let [ids (repeatedly 100 seon.lib/unique-id)
        db (d/db-with (db-with-program) (task/create-tx (db-with-program) [(decl "Do.")]))
        a (keyword (first ids))
        tx (task/start-tx db "demo/t" "b1" a {:branch :b :model nil :max-turns 4} 0)]
    (is (= 100 (count (set ids))) "every spawn mints its own: two spawns of one task are two agents")
    (is (every? #(re-matches #"[a-p][0-9a-f]{15}" %) ids) "in the one id form (seon.lib/id of a digest): a name every Clojure reader reads")
    (is (some #(= (name a) (:seon.agent/key %)) tx) "the agent's key is its id's name")
    (is (not-any? :seon.agent/id (tree-seq coll? seq tx)) "and :seon.agent/id is not written")
    (is (= (seon.lib/call-id "k" 3) (seon.lib/call-id "k" 3) (:seon.model-call/id (task/call-ref "k" 3))) "the same agent and turn, the same call id")
    (is (= (seon.lib/id (seon.lib/digest "k/3")) (seon.lib/call-id "k" 3)) "a model call's id is its agent and turn digested, in seon.lib/id's form")
    (is (= 3 (count (distinct [(seon.lib/call-id "k" 3) (seon.lib/call-id "k" 4) (seon.lib/call-id "j" 3)]))) "another turn or agent, another id")))

(deftest an-agents-id-is-no-stored-attribute
  ;; docs/decisions.md#an-id-is-the-content-of-the-facts-that-make-it-owner-2026-11-08: an agent is named by its key alone; its id in
  ;; root's process is the keyword of that key and is never stored, and nothing indexes the boot (the list is by who started an agent)
  (let [schema (d/schema (ts/test-db))]
    (is (contains? schema :seon.agent/key) "the key is the stored name")
    (is (not (contains? schema :seon.agent/id)) "no attribute for the id")
    (is (:db/index (get schema :seon.agent/boot)) "the boot is indexed: a boot's sessions are read by it")
    (is (= "b9" (:seon.agent/boot (first (task/agent-tx :c0ffee00c0ffee00 {:boot "b9" :status :spawning}))))
        "every agent root starts records its boot (its view's :boot), a bare spawn! too, so the agents list holds it")))

;; docs/reconcile.md#the-reconcilers-index: a task's reader takes what the task holds, its config keys among them: a raised budget
;; (seon.task/env-tx's :seon.config.agent/max-turns, an attribute of the config's schema a task holds) is in the task the
;; reconciler reads, so a release continues with it (bin/check's e2e-held caught a named pull that left it out)
(deftest a-tasks-raised-budget-is-read-with-the-task
  (let [db0 (ts/test-db)
        db1 (:db-after (d/with db0 (task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "t" :seon.task/plan "Make it."}])))
        db2 (:db-after (d/with db1 (task/env-tx "demo/t" {:max-turns 7})))
        e (pdb/eid db2 :seon.task/id "demo/t")]
    (is (= 7 (:seon.config.agent/max-turns (task/task db2 "demo/t"))) "task reads it")
    (is (= 7 (:seon.config.agent/max-turns (second (task/entity-value db2 e)))) "and so does the reconciler's index")))

(deftest ^{:seon.test/estimate {:ticks 20000000}} an-unchanged-tasks-cites-follow-the-code-that-reads-them
  ;; docs/data-modeling.md#derived-data-is-a-cache-never-relied-on (context, 2026-11-12: a task kept the cites of the reader that
  ;; wrote them): a task's cites are derived from its text by the citation reader, so they are kept by the digest of both, and an
  ;; entry whose text is unchanged is read again when the reader's code moved, and only then
  (let [db0 (db-with-program)
        files {'demo [(decl "It uses `demo/f`.")]}
        db1 (d/db-with db0 (task/load-tx db0 files))
        e (fn [db] (pdb/eid db :seon.task/id "demo/t"))
        cites (fn [db] (set (map task/cited-name (task/cites-of db (e db)))))
        ;; the cites an older reader left: none
        stale (d/db-with db1 (for [{c :e} (d/datoms db1 :avet :seon.cite/of (e db1))] [:db/retractEntity c]))
        ;; the reader's code moves (its form's code, as an import records it)
        moved (d/db-with stale [{:db/id "tns" :ns/name 'seon.task :ns/file "src/seon/task.clj"}
                                {:db/id "tf" :form/id "seon.task/new-reader" :form/src "(defn- text-citations [db decl] [])" :form/code "seon.task/new-reader"
                                 :form/ns "tns" :form/order 0 :form/defines ["tv"]}
                                {:db/id "tv" :var/name 'seon.task/text-citations :var/ns "tns" :var/form "tf"}])]
    (is (= #{(quote demo/f)} (cites db1)) "a new entry's cites")
    (is (= [] (task/load-tx db1 files)) "an unchanged entry under the same reader writes nothing")
    (is (= [] (task/load-tx stale files)) "its cites are kept by their digest: the same text and reader read nothing again")
    (is (= #{(quote demo/f)} (cites (d/db-with moved (task/load-tx moved files)))) "the reader's code moved: an unchanged entry's cites follow")))
