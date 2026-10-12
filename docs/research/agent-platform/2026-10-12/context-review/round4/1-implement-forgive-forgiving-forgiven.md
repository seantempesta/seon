---
title: "Context review round 4: the exact first-call model context for seon.sci.forgive/forgiving-forgiven-text-changes-nothing"
type: research
status: complete
created: 2026-10-12
---

# Context review round 4: seon.sci.forgive/forgiving-forgiven-text-changes-nothing

- Task: `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` (handle `be71b5c7e9ea`)
- Agent: `ctx-dump` (a fixture agent and attempt written on a `d/with` of default's main, never committed; no provider call)
- Default: git HEAD 8d14faeee, cluster `default`; method as round 3 (a8d4a784e), see README.md
- Assembled by `seon.context/model-context`, laid out by `seon.ai/messages` with wrapping `:chat`, the request carrying default's cluster caps, SCI context and time limit.
- First call: no exchanges yet, so the messages are the system message and one user message. Message content is verbatim.
- Total estimated tokens (`seon.ai.tokens/estimate`): **8254**

## Message 1: role system (6920 characters, 2162 estimated tokens)

```system
[#:seon.help{:title "Who you are",
  :lines
    ["You are a Seon agent at a Clojure REPL, on your own branch of the cluster's database. Your definitions live on that branch; they reach main only when root reviews and merges your work."
     "The program is data: functions, tests, schemas and your task are entities you read and define at this REPL."]}
 #:seon.help{:title "Reading your context",
  :lines
    ["Your current-state block is the last part of every request; it is already there, and (seon.context/current-state) reads it again."
     ":seon.context/task is what must become true; its :seon.task/intent states the conditions. :seon.context/step is :implement, make the proofs green, or :prove, write red tests for the intent; its :seon.context/start holds the form that enters the owner's namespace."
     ":seon.context/proofs are the deftests that show the task done, each with its :seon.test/source and a :seon.context/proof-status: :red fails, with its :seon.context/proof-failures, :pending has no result for your current code yet, :green passes. They run after each eval request and the next block shows their status."
     ":seon.context/must-reads are what your task's intent cites in backticks, each resolved once under :seon.ref/name or :seon.ref/path: a function's :seon.fn/source and :malli/schema, a schema's :seon.ref/schema-form, a test's :seon.test/source, a task's :seon.task/intent, a document's heading. Their :seon.ref/references are the names their own content cites, each a summary and its read; :seon.render/operations read the rest. One with a :seon.ref/problem-kind did not resolve; its :seon.ref/message says what to do."
     ":seon.context/turns holds :my.agent/turns-left, the model calls you have left, and :seon.context/namespace, the namespace you are in now."
     ":seon.render/operations are forms to evaluate, not data: send one to run it. A namespaced map prints as #:seon.task{:intent ...}, its keys :seon.task/intent and so on."]}
 #:seon.help{:title "Done",
  :lines
    ["Done is every proof green on your branch and every condition of the intent holding. The proofs, then the tests reaching your change, run by themselves after each eval request; all green completes your task and tells root. There is no done call."
     "A test already green proves nothing: the evidence is a red proof turning green."
     "A condition you cannot check at this REPL goes to root. You never run bin/seon, a shell or a file command."]}
 #:seon.help{:title "How work proceeds",
  :lines
    ["1. Read the owner of what the task names: (doc seon.db/q), (my.program/source 'seon.db/q), (my.program/callers 'seon.db/q), (my.program/search :seon.task/intent)."
     "2. Enter the namespace that owns it: (in-ns 'the.owner.ns). A definition changes the namespace it is evaluated in."
     "3. Define a function in the namespace that owns it, its contract written on the defn: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)). It replaces the definition on your branch."
     "4. Call it on real inputs and read the value. A body's locals exist only inside their binding: call the function, or redefine it with the probe inside."
     "5. A :prove step writes a deftest naming its task in :seon/tasks: (deftest ^{:seon/tasks #{'the.task.ns/name}} increments (is (= 3 (inc 2))))."
     "A resolved function reference shows a function's contract as the :malli/schema you write. A defn without one is admitted with a warning in :findings, and merge refuses it."
     "A contract names registered schemas: (my.program/source :seon.task/intent) reads one; (seon.schema/register! :comp.order/qty [:int {:min 1}]) names a new concept."
     "Change code at the REPL, never in files: no slurp, spit, clojure.java.io or shell. Root writes your definitions back to files when it merges. Delete one with (my.program/ns-unmap! 'the.owner.ns/increment)."]}
 #:seon.help{:title "The REPL",
  :lines
    ["Send only Clojure forms, each starting at column zero; prose goes in ;; comments. Code quoted in prose at column zero is evaluated."
     "Never send tool-call markup: no XML, DSML or JSON tool calls. The REPL reads only Clojure."
     "Each reply is data: :value, :error, :findings, and :result, a symbol naming the live value that later forms use in its place. A cut value shows the form that reads the rest."
     ":ms and :alloc-mb are the form's cost; ordinary forms take milliseconds. Each :findings entry names what went wrong and its :seon.repl/fix."
     "Return values to inspect them; never println: printed output is kept and re-read in every later context."
     "Find more: (doc my.message/send), (my.program/source 'my.message/send), (dir my.program)."]}
 #:seon.help{:title "Data",
  :lines
    ["Every value has a named Malli schema: reuse the names on the data flow. Keys are fully namespaced keywords; symbols stay symbols, never strings."
     "Absence is no key: never pass, store or return nil. An optional input is an optional key, [:map [:x {:optional true} :int]]; never [:maybe ...], :any or :some."
     "Fix a bad value in the function that produces it: never weaken a contract or a proof to pass, and add no fallback, retry, catch or guard around it."
     "Never redefine Vars around a call: no with-redefs, alter-var-root, intern or load-file. Code you write holds no atom: state lives in database facts."]}
 #:seon.help{:title "Ask root or give the task back",
  :lines
    ["Ask: my.message/send with :my.message/to \"root\" and :my.message/content, then (my.turn/wait {:my.turn/note \"Waiting for root's answer.\"}) as the reply's last form pauses until the answer arrives."
     "Write what you learn into your task: (my.task/refine! {:seon.task/intent \"Make `seon.db/q` answer its refusal; see docs/seon/specs/merge.md#1-the-merge-request.\"}) rewrites its intent on your branch. A reference is a backticked fully qualified symbol or keyword, a task `ns/task-name`, or a path with a #heading anchor; your next block resolves each one."
     "A wrong or impossible task goes back with its reason: (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"})."]}
 #:seon.help{:title "Clojure and Malli gotchas",
  :lines
    ["In Malli [:= nil] and [:enum nil] are errors: nil lands in the properties slot; a literal is [:= {} nil]. A contract is [:=> [:cat in1 in2] out], every input inside :cat."
     "A keyword lookup is falsy for absent, nil and false alike; test presence with (contains? {:k false} :k) or (find {:k false} :k)."
     "map and filter are lazy and escape (binding ...) and try; realize them with mapv, into or run! there."
     "Write fully qualified symbols, (seon.db/q ...); an alias works only if (ns-aliases *ns*) shows it."
     "An error is a map: read :seon.error/type, :seon.error/message and :seon.error/problems, change the form, and never resend it unchanged."]}]
```

## Message 2: role user (19496 characters, 6092 estimated tokens)

```user
#:seon.context{:task
   #:seon.task{:sym
      seon.sci.forgive/forgiving-forgiven-text-changes-nothing,
    :intent
      "`seon.sci.forgive/forgive` is idempotent: forgiving its `:seon.batch/text` again yields no `:seon.batch/repairs` and the same `:seon.batch/spans`. Today a dropped stray closer joins tokens (\"a]a\" -> \"aa\"), a quote before an inserted closer falls outside every span (\"('\"), and a closer inserted after a lone backslash becomes a character literal (\"(\\\\\"). Every change stays recorded as a `:seon.batch/repair` (a replaced closer carries both `:seon.batch/dropped` and `:seon.batch/inserted`). The fix lands in `seon.sci.forgive/scan`, which `seon.sci.forgive/forgive` calls; the consumers `seon.sci.forgive/step` and `seon.sci.forgive/segments` read the `:seon.batch/forgiven` it answers. Read `docs/research/agent-platform/2026-10-10/designed-task-examples.md`."},
 :proofs
   #:seon.context{:counts {:pending 3},
    :proof-list
      [{:seon.test/sym
          seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-text-changes-nothing}}
  a-stray-closer-keeps-tokens-apart
  ;; A dropped closer is replaced by a space; a dangling quote or backslash stays outside the inserted closer.
  (are [text forgiven-text spans] (= [forgiven-text spans]
                                     ((juxt :seon.batch/text :seon.batch/spans) (forgive/forgive text :code)))
    \"a]a\"        \"a a\"         [[0 1 1 1] [2 3 1 3]]
    \"1]2\"        \"1 2\"         [[0 1 1 1] [2 3 1 3]]
    \"(f a]b)\"    \"(f a b)\"     [[0 7 1 1]]
    \"(+ 1 2) [3\" \"(+ 1 2) [3]\" [[0 7 1 1] [8 11 1 9]]
    \"\"           \"\"            []
    \"('\"         \"()'\"         [[0 2 1 1] [2 3 1 3]]
    \"(\\\\\"        \"()\\\\\"        [[0 2 1 1] [2 3 1 3]]))",
        :seon.ref/references
          [{:seon.ref/name seon.sci.forgive/forgive,
            :doc
              "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.",
            :seon.render/operations
              [(my.program/source 'seon.sci.forgive/forgive)]}]}
       {:seon.test/sym
          seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-text-changes-nothing}}
  forgiving-forgiven-text-changes-nothing
  (test-support/assert-check!
   (tc/quick-check 300 (prop/for-all [text text-gen] (forgiving-again-changes-nothing? text))
                   :seed 20261010 :max-size 40)))",
        :seon.ref/references
          [{:seon.ref/name
              seon.sci.forgive-test/forgiving-again-changes-nothing?,
            :doc
              "Forgiving the forgiven text repairs nothing and keeps its text and spans.",
            :seon.render/operations
              [(my.program/source
              'seon.sci.forgive-test/forgiving-again-changes-nothing?)]}
           {:seon.ref/name seon.sci.forgive-test/text-gen,
            :doc
              "Trial i runs at size i, so the pinned texts run first, in order; then texts of delimiter-heavy pieces.",
            :seon.render/operations
              [(my.program/source 'seon.sci.forgive-test/text-gen)]}
           {:seon.ref/name seon.test-support/assert-check!,
            :doc
              "Assert a nonempty test.check result while retaining its complete shrink data.",
            :seon.render/operations
              [(my.program/source 'seon.test-support/assert-check!)]}]}
       {:seon.test/sym
          seon.sci.forgive-test/the-repairs-account-for-every-change,
        :seon.context/proof-status :pending,
        :seon.test/source
          "(deftest ^{:seon/tasks #{'seon.sci.forgive/forgiving-forgiven-text-changes-nothing}}
  the-repairs-account-for-every-change
  ;; Model: undoing the recorded repairs gives back the agent's text, so no fix goes unrecorded.
  (test-support/assert-check!
   (tc/quick-check 300 (prop/for-all [text text-gen] (= text (unrepair (forgive/forgive text :code))))
                   :seed 20261010 :max-size 40)))",
        :seon.ref/references
          [{:seon.ref/name seon.sci.forgive-test/text-gen,
            :doc
              "Trial i runs at size i, so the pinned texts run first, in order; then texts of delimiter-heavy pieces.",
            :seon.render/operations
              [(my.program/source 'seon.sci.forgive-test/text-gen)]}
           {:seon.ref/name seon.sci.forgive-test/unrepair,
            :doc
              "The text before forgiving: each repair undone, last position first.",
            :seon.render/operations
              [(my.program/source 'seon.sci.forgive-test/unrepair)]}
           {:seon.ref/name seon.sci.forgive/forgive,
            :doc
              "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.",
            :seon.render/operations
              [(my.program/source 'seon.sci.forgive/forgive)]}
           {:seon.ref/name seon.test-support/assert-check!,
            :doc
              "Assert a nonempty test.check result while retaining its complete shrink data.",
            :seon.render/operations
              [(my.program/source 'seon.test-support/assert-check!)]}]}]},
 :step
   {:seon.task/step :implement,
    :seon.task/status :working,
    :seon.context/start
      {:seon.render/operations [(in-ns 'seon.sci.forgive)]}},
 :must-reads
   [{:seon.ref/name seon.sci.forgive/forgive,
     :seon.fn/source
       "(defn forgive
  \"`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.

  Repairs are present only when its delimiters did not balance; a balanced
  text keeps every delimiter as written.\"
  {:malli/schema [:=> [:cat :string :seon.batch/mode] :seon.batch/forgiven]}
  [text mode]
  (let [reply? (= :reply mode)
        strict (scan text reply? false)
        {r :seon.batch/repairs :as result} (if (::balanced? strict) strict (scan text reply? true))]
    (cond-> (dissoc result ::balanced? :seon.batch/repairs)
      (seq r) (assoc :seon.batch/repairs r))))",
     :malli/schema
       [:=> [:cat :string :seon.batch/mode] :seon.batch/forgiven],
     :seon.render/operations
       [(my.program/callers 'seon.sci.forgive/forgive)
        (my.program/tests-reaching 'seon.sci.forgive/forgive)],
     :seon.ref/references
       [{:seon.ref/name seon.sci.forgive/scan,
         :doc
           "One pass over `text`: the forgiven text, each top-level form's span `[start end line column]` in it, the repairs, and whether `text`'s own delimiters balance.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/scan)]}]}
    #:seon.ref{:name :seon.batch/text,
     :schema-form
       [:string
        {:description
           "Exact text of a batch: what is read, evaluated, stored and rendered."}]}
    #:seon.ref{:name :seon.batch/repairs,
     :schema-form [:vector :seon.batch/repair]}
    #:seon.ref{:name :seon.batch/spans,
     :schema-form [:vector :seon.batch/span]}
    #:seon.ref{:name :seon.batch/repair,
     :schema-form
       [:map
        {:description
           "One change the forgiving scan made to an unbalanced text: closers (or a string's quote) inserted on a line, or a stray closer dropped from it."}
        [:seon.batch/line pos-int?]
        [:seon.batch/at
         {:description
            "Where in the forgiven text the change was made; the segment whos"…+46}
         nat-int?]
        [:seon.batch/inserted {:optional true} :seon.batch/inserted]
        [:seon.batch/dropped {:optional true} :seon.batch/dropped]]}
    #:seon.ref{:name :seon.batch/dropped,
     :schema-form
       [:string
        {:description
           "A stray closer, or the prose, the forgiving scan dropped from a line. Producer: `seon.sci.forgive/forgive`."}],
     :references
       [{:seon.ref/name seon.sci.forgive/forgive,
         :doc
           "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/forgive)]}]}
    #:seon.ref{:name :seon.batch/inserted,
     :schema-form
       [:string
        {:description
           "The closers, or a string's closing quote, the forgiving scan inserted on a line. Producer: `seon.sci.forgive/forgive`."}],
     :references
       [{:seon.ref/name seon.sci.forgive/forgive,
         :doc
           "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/forgive)]}]}
    {:seon.ref/name seon.sci.forgive/scan,
     :seon.fn/source
       "(defn- scan
  \"One pass over `text`: the forgiven text, each top-level form's span
  `[start end line column]` in it, the repairs, and whether `text`'s own
  delimiters balance. Prose rules apply when `reply?`; delimiters are
  repaired when `repair?`, else kept as written.\"
  {:malli/schema [:=> [:cat :string :boolean :boolean] [:map [:seon.batch/text :string] [:seon.batch/spans :seon.batch/spans]
                                                        [:seon.batch/repairs :seon.batch/repairs] [::balanced? :boolean]]]}
  [^String text reply? repair?]
  (let [n (.length text)
        out (StringBuilder. (+ n 16))
        ;; the openers of the open collections, outermost first
        open (StringBuilder.)
        spans (volatile! (transient []))
        repairs (volatile! (transient []))
        balanced (volatile! true)
        in-string (volatile! false)
        ;; the open top-level form: [out-start line column]
        form (volatile! nil)
        ;; the items the open top-level form still needs; just past the last
        ;; code item in `out`, and its line (kept only when repairing)
        marks (long-array [0 0 1])
        fence (volatile! nil)
        closers (fn [from] (let [sb (StringBuilder.)]
                             (loop [k (dec (.length open))]
                               (when (>= k from) (.append sb (char (closer-of (.charAt open k)))) (recur (dec k))))
                             (str sb)))
        matching (fn [c] (loop [k (dec (.length open))]
                           (cond (neg? k) -1 (= c (closer-of (.charAt open k))) k :else (recur (dec k)))))
        at-code! (fn [o line] (when repair? (aset marks 1 (long o)) (aset marks 2 (long line))))
        repair! (fn [line at k v] (vswap! repairs conj! {:seon.batch/line line :seon.batch/at at k v}))
        ;; closers inserted at `o` move every repair made after `o` by `d`
        shift! (fn [o d]
                 (loop [k (dec (count @repairs))]
                   (let [r (when (>= k 0) (nth @repairs k))]
                     (when (and r"…+9971,
     :malli/schema
       [:=>
        [:cat :string :boolean :boolean]
        [:map
         [:seon.batch/text :string]
         [:seon.batch/spans :seon.batch/spans]
         [:seon.batch/repairs :seon.batch/repairs]
         [:seon.sci.forgive/balanced? :boolean]]],
     :seon.render/operations
       [(my.program/callers 'seon.sci.forgive/scan)
        (my.program/tests-reaching 'seon.sci.forgive/scan)],
     :seon.ref/references
       [{:seon.ref/name seon.sci.forgive/closer-of,
         :doc "The closer of each opener.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/closer-of)]}
        {:seon.ref/name seon.sci.forgive/code-fence-line?,
         :doc
           "Whether the line `[ls le)` of code is a fence line: three backticks or tildes first.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/code-fence-line?)]}
        {:seon.ref/name seon.sci.forgive/code-tags,
         :doc "The fence tags whose body is code.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/code-tags)]}
        {:seon.ref/name seon.sci.forgive/fence-line,
         :doc
           "The backtick count and lowercased first word of the line `[ls le)` when it is a fence line (three or more backticks first), else nil.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.sci.forgive/form-start?,
         :doc
           "Whether the character at `s` starts a form by its first character: a collection, quote, deref or meta character, `#` before a no"…+68,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.sci.forgive/line-end,
         :doc
           "The offset of the newline ending the line at `pos`, else the text's end.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.sci.forgive/literal-line?,
         :doc
           "Whether the trimmed line `[s le)` is exactly one literal (number, string, keyword, character, `##` value, `true`, `false`, `nil`"…+78,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.sci.forgive/markup-end,
         :doc
           "The end of the tool markup starting at `i` in the line `[i le)`,"…+180,
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.sci.forgive/non-blank,
         :doc
           "The first offset from `pos` below `limit` that is not whitespace, else `limit`.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.sci.forgive/token-end,
         :doc
           "The end, at most `limit`, of the bare token continuing at `pos`:"…+74,
         :seon.render/operations [(my.program/source …1)]}]}
    {:seon.ref/name seon.sci.forgive/step,
     :seon.fn/source
       "(defn step
  \"The next read step at the cursor in `mode`.

  The first step forgives the text; each step then reads the next form's span
  once. A failed read's step carries the reader's own failure.\"
  {:malli/schema [:=> [:cat :seon.batch/read-stage :seon.batch/mode :seon.batch/read-state] :seon.batch/read-step]}
  [stage mode {v :seon.batch/version}]
  (let [eof (Object.)]
    (loop [v (if (find v :seon.batch/spans) v (merge v (forgive (:seon.batch/text v) mode)))]
      (if-let [[s e :as span] (first (:seon.batch/spans v))]
        (let [v' (-> v (assoc :seon.batch/pos e) (update :seon.batch/spans subvec 1))
              [form failure] (try [((:seon.batch/read stage) (span-reader (:seon.batch/text v) span) eof)]
                                  (catch Throwable t [nil t]))]
          (cond failure {:seon.batch/read-error failure :seon.batch/start s :seon.batch/end e :seon.batch/version v'}
                (identical? eof form) (recur v')
                :else {:seon.batch/form form :seon.batch/start s :seon.batch/end e :seon.batch/version v'}))
        {:seon.batch/eof true :seon.batch/version v}))))",
     :malli/schema
       [:=>
        [:cat :seon.batch/read-stage :seon.batch/mode
         :seon.batch/read-state]
        :seon.batch/read-step],
     :seon.render/operations
       [(my.program/callers 'seon.sci.forgive/step)
        (my.program/tests-reaching 'seon.sci.forgive/step)],
     :seon.ref/references
       [{:seon.ref/name seon.sci.forgive/forgive,
         :doc
           "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/forgive)]}
        {:seon.ref/name seon.sci.forgive/span-reader,
         :doc
           "A reader over the span `[s e line column]` of `text` that numbers lines and columns as `text` does.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/span-reader)]}]}
    {:seon.ref/name seon.sci.forgive/segments,
     :seon.fn/source
       "(defn segments
  \"Each form's source in the forgiven text, the text above it as its comment.

  The text's repairs ride beside the segments in text order, and each segment
  carries those made from its start to the next segment's. `mode` is `:reply`
  by default; a failed read's span is one more source, whose live read answers
  its error.\"
  {:malli/schema [:function
                  [:=> [:cat :string] :seon.batch/segmented]
                  [:=> [:cat :string :seon.batch/mode] :seon.batch/segmented]]}
  ([text] (segments text :reply))
  ([text mode]
   (let [{t :seon.batch/text spans :seon.batch/spans repairs :seon.batch/repairs} (forgive text mode)
         repairs (some->> repairs (sort-by :seon.batch/at) vec)]
     (loop [[[s e] & more] spans prev 0 out [] starts []]
       (if-not s
         (cond-> {:seon.batch/segments (owned out starts repairs)} repairs (assoc :seon.batch/repairs repairs))
         (let [source (str/trimr (subs t s e))
               c (when (< (non-blank t prev s) (long s)) (comment-of (subs t prev s)))
               skip? (no-value? source)]
           (recur more e
                  (if skip? out (conj out (cond-> {:seon.batch/source source} (seq c) (assoc :seon.batch/comment c))))
                  (if skip? starts (conj starts s)))))))))",
     :malli/schema
       [:function
        [:=> [:cat :string] :seon.batch/segmented]
        [:=> [:cat :string :seon.batch/mode] :seon.batch/segmented]],
     :seon.render/operations
       [(my.program/callers 'seon.sci.forgive/segments)
        (my.program/tests-reaching 'seon.sci.forgive/segments)],
     :seon.ref/references
       [{:seon.ref/name seon.sci.forgive/comment-of,
         :doc
           "The text between two forms as comment lines: the `;` lines kept, blank, `;`-only and comma-only lines dropped.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/comment-of)]}
        {:seon.ref/name seon.sci.forgive/forgive,
         :doc
           "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/forgive)]}
        {:seon.ref/name seon.sci.forgive/no-value?,
         :doc
           "Whether the source reads no value: a discard, or a reader conditional selecting no `:clj` branch.",
         :seon.render/operations
           [(my.program/source 'seon.sci.forgive/no-value?)]}
        {:seon.ref/name seon.sci.forgive/non-blank,
         :doc
           "The first offset from `pos` below `limit` that is not whitespace, else `limit`.",
         :seon.render/operations [(my.program/source …1)]}
        {:seon.ref/name seon.sci.forgive/owned,
         :doc
           "`segments` with each of `repairs`, in offset order, on the last segment starting at or before its offset (`starts` are the segme"…+54,
         :seon.render/operations [(my.program/source …1)]}]}
    #:seon.ref{:name :seon.batch/forgiven,
     :schema-form
       [:map
        {:description
           "A text forgiven by seon.sci.forgive/forgive: the text, each top-level form's span in it, and the repairs when its delimiters did not balance."}
        [:seon.batch/text :seon.batch/text]
        [:seon.batch/spans :seon.batch/spans]
        [:seon.batch/repairs {:optional true} :seon.batch/repairs]]}
    {:seon.ref/path
       "docs/research/agent-platform/2026-10-10/designed-task-examples.md",
     :seon.ref/heading "Designed tasks, worked end to end",
     :seon.render/operations
       [(my.fs/section
         "docs/research/agent-platform/2026-10-10/designed-task-examples.md")]}],
 :turns
   {:my.agent/turns-left 100,
    :seon.task/status :working,
    :seon.context/namespace seon.sci.forgive}}
```
