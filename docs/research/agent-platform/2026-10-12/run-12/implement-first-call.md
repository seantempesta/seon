---
title: "Run 12 Implement step: first-call messages"
type: research
status: complete (lane run12, 2026-10-13)
created: 2026-10-13
---

# Run 12 Implement step: first-call messages

Rebuilt with `seon.context/model-context` at the branch commit of the first call's `:seon.ai.attempt/basis-t`, laid out by `seon.ai/messages` `:chat`. Provider-reported prompt tokens: 3956. Estimated (`seon.ai.tokens/estimate`): system 2011, user 2332.

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

## Message 2: role user

````text
#:seon.context{:task
   #:seon.task{:sym
      seon.error/implement-a-blank-error-message-is-replaced,
    :intent
      "`seon.error/message` answers a message that is not blank for every source: a map whose `:seon.error/message` is whitespace-only is read as having no message (it returns \"   \" unchanged today, against its docstring \"Never absent, never blank\"), so the transition sentence, the root cause's message or the unclassified sentence answers instead. Read `seon.error/message` and `:seon.error/message`.",
    :instructions
      "Make the table green by changing `seon.error/message`, keeping its docstring promise (never absent, never blank): a message map member counts only when it has a non-whitespace character; the other branches are unchanged."},
 :chain
   #:seon.context{:subject
      seon.error/a-blank-error-message-is-replaced-by-the-sentence-that-describes-the-source,
    :before
      [{:seon.task/sym
          seon.error/prove-a-blank-error-message-is-replaced,
        :seon.task/step :prove,
        :seon.task/outcome :merged,
        :seon.context/merged
          {:seon.context/tests
             [{:seon.ref/name
              seon.error-test/a-blank-error-message-is-replaced-by-the-sentence-that-describes-the-source,
              :seon.render/operations
              [(my.program/source
              'seon.error-test/a-blank-error-message-is-replaced-by-the-sentence-that-describes-the-source)]}]}}]},
 :proofs
   #:seon.context{:counts {:red 1},
    :proof-list
      [{:seon.test/sym
          seon.error-test/a-blank-error-message-is-replaced-by-the-sentence-that-describes-the-source,
        :seon.context/proof-status :red,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.error/implement-a-blank-error-message-is-replaced}}
  a-blank-error-message-is-replaced-by-the-sentence-that-describes-the-source
  (let [cases
        [[\"a whitespace-only member is no message at all\"
          {:seon.error/message \"   \"} nil
          \"An unclassified clojure.lang.PersistentArrayMap arrived where an error was expected.\"]
         [\"an empty member is no message at all\"
          {:seon.error/message \"\"} nil
          \"An unclassified clojure.lang.PersistentArrayMap arrived where an error was expected.\"]
         [\"a blank member lets the transition sentence answer\"
          {:seon.error/message \"   \"
           :seon.turn/transition \"the transition\"
           :seon.turn/rule :seon.turn/not-the-holder}
          nil
          \"the transition was refused by :seon.turn/not-the-holder.\"]
         [\"a blank member lets the root cause answer\"
          {:seon.error/message \"  \"} (ex-info \"boom\" {})
          \"boom\"]
         [\"a real message is returned unchanged\"
          {:seon.error/message \"  the transition refused  \"} nil
          \"  the transition refused  \"]
         [\"a real message wins over the turn branches\"
          {:seon.error/message \"real\"
           :seon.turn/transition \"the transition\"
           :seon.turn/rule :seon.turn/not-the-holder}
          nil
          \"real\"]]]
    (doseq [[what source failure expected] cases]
      (let [answer (#'seon.error/message source failure)]
        (is (string? answer) what)
        (is (not (re-matches #\"\\s*\" answer))
            (str what \" -- the answer is never blank, got \" (pr-str answer)))
        (is (= expected answer) what)))))",
        :seon.ref/references [{:seon.ref/name seon.error/message}],
        :seon.context/proof-failures
          [{:type :fail,
            :expected (= expected answer),
            :actual
              (not
 (=
  "An unclassified clojure.lang.PersistentArrayMap arrived where an error was expected."
  "   ")),
            :message "a whitespace-only member is no message at all"}
           {:type :fail,
            :expected (= expected answer),
            :actual (not (= "boom" "  ")),
            :message "a blank member lets the root cause answer"}
           {:type :fail,
            :expected (= expected answer),
            :actual
              (not
 (= "the transition was refused by :seon.turn/not-the-holder." "   ")),
            :message
              "a blank member lets the transition sentence answer"}
           {:type :fail,
            :expected
              (not (re-matches #object[java.util.regex.Pattern 0x7ed734a8] answer)),
            :actual (not (not "  ")),
            :message
              "a blank member lets the root cause answer -- the answer is never blank, got \"  \""}
           {:type :fail,
            :expected
              (not (re-matches #object[java.util.regex.Pattern 0x7ed734a8] answer)),
            :actual (not (not "   ")),
            :message
              "a blank member lets the transition sentence answer -- the answer is never blank, got \"   \""}
           {:type :fail,
            :expected
              (not (re-matches #object[java.util.regex.Pattern 0x7ed734a8] answer)),
            :actual (not (not "   ")),
            :message
              "a whitespace-only member is no message at all -- the answer is never blank, got \"   \""}]}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start {:seon.render/operations [(in-ns 'seon.error)]}},
 :must-reads
   [{:seon.ref/name seon.error/message,
     :seon.fn/source
       "(defn- message
  \"What a reader is told. Never absent, never blank.
  Taken from the ROOT CAUSE, not the outermost wrapper: measured on the
  first real projection, a Datahike-wrapped transition refusal produced
  the message \\\"wrapper\\\" while the observation came from the bottom of the
  chain, and an agent reading \\\"An error stopped work: wrapper\\\" has been
  told nothing. The chain is not recoverable from `data-edn` either —
  admission projects a Throwable to an opaque marker by design — so this
  string is the only place the real sentence can appear.
  A source nothing recognizes still says what arrived: `nil` is a
  perfectly possible thing to be handed, and \\\"an error we cannot
  describe\\\" has to describe that much.\"
  {:malli/schema [:=> [:cat :seon.error/source [:or :nil :seon.error/throwable]]
                  :seon.error/message]}
  [source failure]
  (or (when (map? source) (not-empty (:seon.error/message source)))
      (when (and (map? source)
                 (:seon.turn/rule source)
                 (:seon.turn/transition source))
        (str (:seon.turn/transition source) \" was refused by \"
             (:seon.turn/rule source) \".\"))
      (when failure
        (let [deepest (stacktrace/root-cause failure)]
          (or (not-empty (ex-message deepest))
              (not-empty (ex-message failure))
              (.getName (class deepest)))))
      (if (nil? source)
        \"An unclassified nil arrived where an error was expected.\"
        (str \"An unclassified \" (.getName (class source)) \" arrived where an \"
             \"error was expected.\"))))",
     :malli/schema
       [:=>
        [:cat :seon.error/source [:or :nil :seon.error/throwable]]
        :seon.error/message],
     :seon.render/operations
       [(my.program/callers 'seon.error/message)
        (my.program/tests-reaching 'seon.error/message)]}
    #:seon.ref{:name :seon.error/message,
     :schema-form [:string {:min 1}]}],
 :turns
   {:my.agent/turns-left 60,
    :seon.task/status :working,
    :seon.context/namespace seon.error}}
````

