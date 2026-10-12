---
title: "Run 15b Implement step: first-call messages"
type: research
status: complete (lane run15b, 2026-10-13)
created: 2026-10-13
---

# Run 15b Implement step: first-call messages

Rebuilt with `seon.context/model-context` at commit 6acd46b7-48bb-5f8d-a9cf-d45e5b79753b of `:attempt-95984a3964c2` holding the first call's `:seon.ai.attempt/basis-t` 536871284, with a temporarily opened (then released) connection to that branch so `seon.context/merged` could read Prove's merge. Provider-reported prompt tokens: 4749 (1792 cached). Rebuild: 1,242 ms.

## Message 1: role system

````text
[#:seon.help{:title "Who you are",
  :lines
    ["You are a Seon agent at a Clojure REPL, on your own branch of the cluster's database. When your step is done, everything on your branch merges onto main by itself: functions, tests, schemas and your task's facts. Then the step after you starts from main. There is no done call and no merge call."
     "The program is data: functions, tests, schemas and your task are entities you read and define at this REPL."]}
 #:seon.help{:title "How to speak",
  :lines
    ["You have one tool, eval. Its code is Clojure forms, each starting at column zero; prose goes in ;; comments. Text you write outside the tool is read the same way. Nothing else exists: no other tool, no XML or JSON, no file or shell command."]}
 #:seon.help{:title "Your task",
  :lines
    ["Your current-state block is the last part of every request; (seon.context/current-state) reads it again. :seon.context/task holds :seon.task/intent, the goal every step of your chain shares, and :seon.task/instructions, your step's own work: do your instructions, keep the goal true. :seon.context/step is :implement, make the proofs green, or :prove, write red tests for the step after you; :seon.context/start holds the in-ns form for the owner's namespace and, for :prove, for the test namespace your instructions name."
     ":seon.context/chain names the subject, the goal's owner; the steps :seon.context/before you, each with what its merge moved under :seon.context/merged; and the steps :seon.context/after you, each with its instructions. What a step before you merged is already on your branch: read it with (my.program/source 'ns/name) and rely on it. A step after you starts from main once you merge."
     ":seon.context/proofs are the deftests that show your step done, each with its :seon.test/source and :seon.context/proof-status: :red fails, with :seon.context/proof-failures; :pending has no result for your current code yet; :green passes. They run by themselves after each eval request and the next block shows them."
     ":seon.context/must-reads are what the task cites in backticks, each resolved once: a function's :seon.fn/source and :malli/schema, a schema's form, a test's source, a document's heading. :seon.render/operations are forms to evaluate, not data: send one to run it. A namespaced map prints as #:seon.task{:intent ...}."]}
 #:seon.help{:title "Done",
  :lines
    ["Every deftest in :seon.context/proofs is green on your branch and no test you reach is broken. They start red: read :seon.context/proof-failures, change the function that produces the wrong value, and read the next block. A test already green proves nothing."]}
 #:seon.help{:title "How work proceeds",
  :lines
    ["1. Read the owner: (my.program/source 'seon.db/q), (doc seon.db/q), (my.program/search :seon.task/intent)."
     "2. Enter the namespace that owns it with the :seon.context/start form, (in-ns 'the.owner.ns). A definition changes the namespace it is evaluated in; :seon.context/namespace names the one you are in."
     "3. Define the function in the namespace that owns it, its contract on the defn: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)). It replaces the whole definition on your branch, comments included: keep the ;; comments you were shown unless you mean to delete them. A defn without a contract is admitted with a warning in :findings, and merge refuses it."
     "4. Call it on real inputs and read the value. A body's locals exist only inside their binding: call the function, or redefine it with the probe inside. A scratch definition you do not mean to merge goes away with (my.program/ns-unmap! 'the.owner.ns/scratch)."
     "Change code at the REPL, never in files: no slurp, spit, clojure.java.io or shell. Root writes your definitions to files when your step merges."]}
 #:seon.help{:title "Write what you learn",
  :lines
    ["(my.task/refine! {:seon.task/intent \"Make `seon.db/q` answer its refusal; see docs/seon/specs/merge.md#1-the-merge-request.\"}) rewrites the goal for every step of your chain; (my.task/refine! {:seon.task/instructions \"The rows are in `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart`.\"}) rewrites your step's own work. Both write your branch. Refine as soon as you decide something the next step would otherwise rediscover. A reference is a backticked fully qualified symbol or keyword, a task `ns/task-name`, or a path with a #heading anchor; your next block resolves each one."]}
 #:seon.help{:title "When you cannot",
  :lines
    ["Nobody answers a question while the chain runs. Decide the simplest option consistent with the goal, record it with refine!, and continue."
     "A task that is wrong or impossible goes back with its reason, as your last form: (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"}). Never weaken a proof or a contract to go green."]}
 #:seon.help{:title "Each reply is data",
  :lines
    [":value, :error, :findings and :result, a symbol naming the live value later forms may use. :ms and :alloc-mb are the form's cost. A cut value shows the form that reads the rest."
     "An error is a map: read :seon.error/type, :seon.error/message and :seon.error/problems, change the form, and never resend it unchanged. Return values; never println."]}
 #:seon.help{:title "Data",
  :lines
    ["Every value has a named Malli schema: reuse the names on the data flow; (my.program/source :seon.task/intent) reads one; (seon.schema/register! :comp.order/qty [:int {:min 1}]) names a new concept. Keys are fully namespaced keywords; symbols stay symbols."
     "Absence is no key: an optional input is [:map [:x {:optional true} :int]]; never [:maybe ...], :any or :some. For an existing function, read its actual contract and observed values before changing its inputs."
     "Fix a bad value in the function that produces it; add no fallback, retry, catch or guard. Never with-redefs, alter-var-root, intern or load-file. Code you write holds no atom."
     "In Malli a literal is [:= {} nil]; a contract is [:=> [:cat in1 in2] out], every input inside :cat. A keyword lookup is falsy for absent, nil and false alike: test presence with (contains? {:k false} :k)."
     "map and filter are lazy: realize them with mapv or into inside binding and try. Write fully qualified symbols; an alias works only if (ns-aliases *ns*) shows it."]}]
````

## Exchanges before the first call

0

## Last message: the current-state block

````text
#:seon.context{:task
   #:seon.task{:sym seon.fs/implement-a-path-under-a-root-answers-true,
    :intent
      "`seon.fs/under-path?` answers false where a path under the root should answer true: `(#'seon.fs/under-path? \"/\" \"/x\")` and `(#'seon.fs/under-path? \"/a/b/\" \"/a/b/c\")` both answer false, because it appends the separator to an ancestor that already ends in one. A descendant equal to the ancestor or lying under it answers true; a sibling sharing a prefix (`\"/a/bc\"` under `\"/a/b\"`) stays false. Read `seon.fs/under-path?` and `seon.fs/admit-destructive-path!`.",
    :instructions
      "Make the table green by changing `seon.fs/under-path?` only: an ancestor that already ends in the separator is not given a second one, so \"/\" and a trailing-slash root contain what lies under them. Keep it safe: a sibling that shares a prefix (\"/a/bc\" under \"/a/b\") is never under."},
 :chain
   #:seon.context{:subject seon.fs/a-path-under-a-root-answers-true,
    :before
      [{:seon.task/sym seon.fs/prove-a-path-under-a-root-answers-true,
        :seon.task/step :prove,
        :seon.task/outcome :merged,
        :seon.context/merged
          {:seon.context/tests
             [{:seon.ref/name
              seon.fs-test/under-path-tells-what-lies-under-a-root,
              :seon.render/operations
              [(my.program/source
              'seon.fs-test/under-path-tells-what-lies-under-a-root)]}]}}]},
 :proofs
   #:seon.context{:counts {:red 1},
    :proof-list
      [{:seon.test/sym
          seon.fs-test/under-path-tells-what-lies-under-a-root,
        :seon.context/proof-status :red,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.fs/implement-a-path-under-a-root-answers-true}}
    under-path-tells-what-lies-under-a-root
  (doseq [[ancestor descendant expected] [[\"/\" \"/x\" true]
                                          [\"/a/b/\" \"/a/b/c\" true]
                                          [\"/a/b\" \"/a/b/c\" true]
                                          [\"/a/b\" \"/a/b\" true]
                                          [\"/a/b\" \"/a/bc\" false]
                                          [\"/a\" \"/a/b\" false]]]
    (testing (str (pr-str descendant) \" under \" (pr-str ancestor))
      (is (= expected (#'seon.fs/under-path? ancestor descendant))))))",
        :seon.ref/references [{:seon.ref/name seon.fs/under-path?}],
        :seon.context/proof-failures
          [{:type :fail,
            :testing ["\"/a/b\" under \"/a\""],
            :expected
              (= expected ((var seon.fs/under-path?) ancestor descendant)),
            :actual (not (= false true))}
           {:type :fail,
            :testing ["\"/a/b/c\" under \"/a/b/\""],
            :expected
              (= expected ((var seon.fs/under-path?) ancestor descendant)),
            :actual (not (= true false))}
           {:type :fail,
            :testing ["\"/x\" under \"/\""],
            :expected
              (= expected ((var seon.fs/under-path?) ancestor descendant)),
            :actual (not (= true false))}]}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/broken #{},
    :seon.context/new-red #{},
    :seon.context/start {:seon.render/operations [(in-ns 'seon.fs)]}},
 :must-reads
   [{:seon.ref/name seon.fs/under-path?,
     :seon.fn/source
       "(defn- under-path?
  [ancestor descendant]
  (or (= ancestor descendant)
      (str/starts-with? descendant (str ancestor java.io.File/separator))))",
     :seon.render/operations
       [(my.program/callers 'seon.fs/under-path?)
        (my.program/tests-reaching 'seon.fs/under-path?)]}
    {:seon.ref/name seon.fs/admit-destructive-path!,
     :seon.fn/source
       "(defn admit-destructive-path!
  \"Admit one recursive deletion, or refuse BEFORE anything is deleted.

  Takes `{:seon.cluster.store/root, :seon.cluster.store/target, :seon.cluster.store/declared-root}` and returns the canonical
  target path. `:seon.cluster.store/root` is the caller's deletion authority, `:seon.cluster.store/target` the
  path it wants removed, and `:seon.cluster.store/declared-root` the operator root this JVM was
  launched to operate (`declared-operator-root`), absent when none was
  declared.

  THE RULE, refused as a typed store refusal naming the offending value:
  the root and the target must be absolute (nil, \\\"\\\", \\\".\\\" and any relative
  spelling resolve against the process working directory and are refused);
  the canonical target must lie under the canonical root; and a target
  inside the working directory's own `data/` is refused unless
  `:seon.cluster.store/declared-root` is that working directory. So `bin/seon reset --force`
  destroys the checkout's data because its JVM declared that root, while a
  test worker, a fixture, or a lane JVM — which declare an isolated root or
  none — cannot construct the deletion.\"
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason \"The admission judges caller-supplied path values of any shape, including nil and relative strings, and refuses them by typed value.\"}]]
    :string]}
  [request]
  (let [root (:seon.cluster.store/root request)
        target (:seon.cluster.store/target request)
        declared (:seon.cluster.store/declared-root request)
        root-string (path-string root)
        target-string (path-string target)]
    (when (str/blank? root-string)
      (refuse-deletion! :seon.cluster.store/undeclared-destructive-root
               (str \"a recursive deletion was requested with no deletion \"
                    \"authority (\" (pr-str root) \"); the root is the disposable \"
                    \"root the caller holds, never the working directory\")
               {:seon.cluster.store/root root :seon.cluster.store/target target}))
    (when (str/blank? target-string)
      (refuse-deletion! :seon.cluster.store/undeclared-destructive-target
               (str \"a recursive deletion was requested with no target (\"
                    (pr-str target) \")\")
               {:seon.cluster.store/root root :seon.cluster.store/target target}))
    (when-not (.isAbsolute (io/file root-string))
      (refuse-deletion! :seon.cluster.store/relative-destructive-root
               (str \"the deletion authority \" (pr-str root-string)
                    \" is relative, so it names the process working directory \"
                    (pr-str (System/getProperty \"user.dir\"))
                    \"; hand the absolute disposable root instead\")
               {:seon.cluster.store/root root-string :seon.cluster.store/target target-string}))
    (when-not (.isAbsolute (io/file target-string))
      (refuse-deletion! :seon.cluster.store/relative-destructive-target
               (str \"the deletion target \" (pr-str target-string)
                    \" is relative, so it names the process working directory \"
                    (pr-str (System/getProperty \"user.dir\")))
               {:seon.cluster.store/root root-string :seon.cluster.store/target target-string}))
    (let [authority (destructive-canonical-path root-string)
          resolved (destructive-canonical-path target-string)
          working (destructive-canonical-path (System/getProperty \"user.dir\"))
          working-data (destructive-canonical-path (io/file working \"data\"))]
      (when-not (under-path? authority resolved)
        (refuse-deletion! :seon.cluster.store/destructive-path-outside-root
                 (str \"refusing to delete \" resolved
                      \" because it lies outside the deletion authority \"
                      authority)
                 {:seon.cluster.store/root authority :seon.cluster.store/target resolved}))
      (when (and (under-path? working-data resolved)
                 (not= declared working))
        (refuse-deletion! :seon.cluster.store/undeclared-checkout-deletion
                 (str \"refusing to delete \" resolved
                      \" inside the working directory's own data directory: \"
                      \"this JVM declared operator root \" (pr-str declared)
                      \", not \" (pr-str working)
                      \"; only a JVM launched to operate that root (bin/seon \"
                      \"[--root PATH]) may destroy it\")
                 {:seon.cluster.store/root authority
                  :seon.cluster.store/target resolved
                  :seon.cluster.store/declared-root declared
                  :seon.cluster.store/working-directory working}))
      resolved)))",
     :malli/schema
       [:=>
        [:cat
         [:any
          #:seon.schema.admission{:exemption
          :seon.schema.admission/polymorphic-boundary,
          :reason
          "The admission judges caller-supplied path values of any shape, including nil and relative strings, and refuses them by typed value."}]]
        :string],
     :seon.render/operations
       [(my.program/callers 'seon.fs/admit-destructive-path!)
        (my.program/tests-reaching 'seon.fs/admit-destructive-path!)],
     :seon.ref/references
       [#:seon.ref{:name :seon.cluster.store/root,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/send that the task names it."}
        #:seon.ref{:name :seon.cluster.store/target,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/send that the task names it."}
        #:seon.ref{:name :seon.cluster.store/declared-root,
         :problem-kind :unresolved,
         :message
           "Nothing of this name or path exists on your branch. If the task means one to create, create it; else tell root with my.message/send that the task names it."}
        {:seon.ref/name seon.fs/destructive-canonical-path,
         :seon.render/operations
           [(my.program/source 'seon.fs/destructive-canonical-path)]}
        {:seon.ref/name seon.fs/path-string,
         :seon.render/operations
           [(my.program/source 'seon.fs/path-string)]}
        {:seon.ref/name seon.fs/refuse-deletion!,
         :seon.render/operations
           [(my.program/source 'seon.fs/refuse-deletion!)]}
        {:seon.ref/name seon.fs/under-path?}]}],
 :turns
   {:my.agent/turns-left 60,
    :seon.task/status :working,
    :seon.context/namespace seon.fs}}
````
