(ns seon.sci.reader-test
  (:refer-clojure :exclude [read])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sci.core :as sci]
            [seon.sci.eval :as eval]
            [seon.sci.reader :as reader]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(defn- source-files
  []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile %))
       (filter #(or (str/ends-with? (.getName %) ".clj")
                    (str/ends-with? (.getName %) ".cljc")))
       (sort-by str)
       vec))

(defn- events
  ([text]
   (events text {}))
  ([text context]
   (reader/read
    (merge {:seon.sci.reader/text text
            :seon.sci.reader/ns 'user
            :seon.sci.reader/aliases {}
            :seon.sci.reader/refers {}
            :seon.sci.reader/features #{:clj}
            :seon.sci.reader/tags {}
            :seon.config.eval.result/max-source 1048576}
           context))))

(defn- reader-failure?
  [value]
  ((schema/projection-validator (schema/handed-projection) :seon.sci.reader/failure) value))

(deftest fences-are-whitespace-between-reader-forms
  (doseq [delimiter ["```" "```clojure" "```edn" "  ```clj" "~~~clojure"]
          newline ["\n" "\r\n"]]
    (let [source (str "(inc 1)" newline ";; before fence" newline
                      delimiter newline ";; inside" newline
                      "(dec 3)" newline "```" newline "(+ 2 3)")
          parsed (events source)]
      (is (= ['(inc 1) '(dec 3) '(+ 2 3)]
             (mapv :seon.sci.reader/form parsed)))
      (is (not-any? :seon.sci.reader/error parsed))
      (doseq [{:seon.sci.reader/keys [source-start source-end] :as event} parsed]
        (is (= (:seon.sci.reader/source event)
               (subs source source-start source-end))))))
  (let [source "(identity \"before\n```clojure\n(+ 1 2)\n```\nafter\")"]
    (is (= source (:seon.sci.reader/source (first (events source)))))
    (is (= '(identity "before\n```clojure\n(+ 1 2)\n```\nafter")
           (:seon.sci.reader/form (first (events source)))))))

(deftest a-response-shaped-map-reads-as-data
  (let [source "#:seon.repl{:value 3}"
        parsed (events (str "(inc 1)\n" source))]
    (is (= 2 (count parsed)))
    (is (not-any? :seon.sci.reader/error parsed))
    (is (= {:seon.repl/value 3} (:seon.sci.reader/form (second parsed))))
    (is (= source (:seon.sci.reader/source (second parsed))))))

(defn- read-kinds
  [read-events]
  (mapv #(if (:seon.sci.reader/error %) :error :form) read-events))

(defn- first-read-error
  [read-events]
  (some :seon.sci.reader/error read-events))

(defn- event-slices
  [text read-events]
  (mapv #(subs text
               (:seon.sci.reader/start %)
               (:seon.sci.reader/end %))
        read-events))

(defn- source-round-trips?
  "Sources and cursor spans are deterministic even when read-time gensyms are not.

  Clojure expands syntax-quote auto-gensyms on each read, so equality of the
  resulting forms is not a reader promise. The durable promise is that exact
  source strings and their partitioning cursors round-trip unchanged."
  [file]
  (let [text (slurp file)
        first-read (events text)
        second-read (events text)
        source-read
        (when (vector? first-read)
          (events
           (str/join "\n"
                     (map :seon.sci.reader/source first-read))))]
    (and (vector? first-read)
         (vector? second-read)
         (vector? source-read)
         (= (mapv :seon.sci.reader/source first-read)
            (mapv :seon.sci.reader/source second-read)
            (mapv :seon.sci.reader/source source-read))
         (= text (apply str (event-slices text first-read)))
         (every?
          (fn [{:seon.sci.reader/keys
                [source start end source-start]}]
            (and (<= start source-start end)
                 (= source
                    (str/trim (subs text start end)))))
          first-read))))

(deftest source-round-trips-and-spans-partition-the-tree
  (let [files (source-files)
        results (mapv source-round-trips? files)
        property
        (prop/for-all [rotation (gen/choose 0 (dec (count files)))]
          ;; Rotation makes the seed visible while every trial still covers
          ;; every source file. Reading is a pure function of this test JVM's
          ;; frozen source bytes, so compute that expensive relation once and
          ;; retain the ten seeded order-independence trials over its results.
          (every?
           true?
           (concat (drop rotation results) (take rotation results))))]
    (support/assert-check!
     (tc/quick-check 10 property :seed 1785291017)
     "Every fresh-tree source must read deterministically and partition.")))

(deftest every-form-shape-has-a-cursor-derived-span
  (let [text
        (str "  ; leading\n"
             "1 \"two\" :three true false nil symbol "
             "[] () {} #{} '(quoted)\n"
             "  ; trailing\n")
        read-events (events text)]
    (is (vector? read-events))
    (is (= [1 "two" :three true false nil 'symbol
            [] '() {} #{} '(quote (quoted))]
           (mapv :seon.sci.reader/form read-events)))
    (is (= text (apply str (event-slices text read-events))))
    (doseq [event read-events]
      (is (integer? (:seon.sci.reader/start event)))
      (is (integer? (:seon.sci.reader/end event)))
      (is (integer? (:seon.sci.reader/source-start event)))
      (is (pos-int? (:seon.sci.reader/line event)))
      (is (pos-int? (:seon.sci.reader/column event))))
    (is (= "; leading\n1"
           (:seon.sci.reader/source (first read-events))))
    (is (= (count text)
           (:seon.sci.reader/end (peek read-events)))))
  (testing "offsets and max-source use JVM UTF-16 character units"
    (let [text "😀 42"
          read-events (events text)]
      (is (= 5 (count text)))
      (is (= [0 2 5]
             (into [(:seon.sci.reader/start (first read-events))]
                   (map :seon.sci.reader/end)
                   read-events)))
      (is (= 2
             (get-in
              (events "😀" {:seon.config.eval.result/max-source 1})
              [:seon.error/data :seon.sci.reader/length]))))))

(deftest a-number-ending-the-text-keeps-its-source-and-span
  ;; tools.reader steps the column back on unreading EOF's nil after a number.
  (doseq [[text sources] {"1" ["1"] "1 2" ["1" "2"] "1\n2" ["1" "2"]
                          ":k 1" [":k" "1"] "\"s\" -1.5" ["\"s\"" "-1.5"]}]
    (let [read-events (events text)]
      (is (= sources (mapv :seon.sci.reader/source read-events)))
      (is (not-any? :seon.sci.reader/error read-events))
      (is (= text (apply str (event-slices text read-events)))))))

(deftest a-declaration-source-of-other-than-one-form-is-refused-by-its-position
  ;; The one-event rule reads a stored declaration's source (program
  ;; acquisition); an agent's reply is read by the fold, form by form.
  (let [ctx (eval/build-base-ctx (schema/handed-projection))]
    (doseq [[text line column offending] [["(+ 1 2) (+ 3 4)" 1 9 "(+ 3 4)"] ["1 2" 1 3 "2"]
                                          ["1\n2" 2 1 "2"] [" ;; prose only" nil nil " ;; prose only"]]]
      (let [refusal (try (#'eval/one-event text 'user ctx (count text))
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (schema/valid-candidate-value? (schema/handed-projection)
                                           :seon.sci.eval/reader-event-count-error refusal)
            "the refusal is the declared reader-event-count error")
        (is (= [:seon.cluster.eval/source offending]
               [(:seon.error/member refusal) (:seon.error/offending refusal)])
            "the refusal names the source and the form past the first")
        (is (= [line column] (mapv (:seon.error/data refusal {}) [:seon.sci.reader/line :seon.sci.reader/column]))
            "the refusal names the second form's position")
        (when line (is (str/includes? (:seon.error/message refusal) "(do ...)")
                       "the refusal says how to wrap a sequence"))))))

(deftest original-source-spans-preserve-crlf-and-utf16
  (let [text "; 😀 note\r\n(+ 1 2)\r\n\"😀\"\r\n"
        read-events (events text)]
    (is (= ["; 😀 note\r\n(+ 1 2)" "\"😀\""]
           (mapv :seon.sci.reader/source read-events)))
    (is (= text (apply str (event-slices text read-events))))
    (doseq [event read-events]
      (let [{:seon.sci.reader/keys [source source-start source-end]} event]
        (is (= source (subs text source-start source-end)))
        (is (<= source-start source-end (count text)))))))

(deftest the-accepted-tag-set-is-total-and-read-eval-is-always-refused
  ;; BUILT-IN tags (#inst, #uuid) are no longer refused: the reader
  ;; defers to clojure.core/default-data-readers for tags it does not
  ;; own, so a real REPL's literals read (2026-08-01 parity gate). Only
  ;; genuinely unknown tags refuse.
  (let [tag-cases
        [['foo/bar "#foo/bar {:x 1}"]]]
    (doseq [[tag text] tag-cases]
      (let [refused (events text)]
        (is (= tag (:seon.sci.reader/refused-token refused)))
        (is (= 'seon.sci.reader/error-value (:seon.error/operation refused)))
        (is (= tag
               (get-in refused
                       [:seon.error/data :seon.sci.reader/tag]))))
      (let [accepted
            (events text
                    {:seon.sci.reader/tags
                     {tag (fn [value] [tag value])}})]
        (is (= tag (-> accepted first :seon.sci.reader/form first)))))
    (testing "declaring a tag in one read never leaks into the next"
      (is (vector?
           (events "#foo/bar 1"
                   {:seon.sci.reader/tags
                    {'foo/bar (fn [value] value)}})))
      (is (= 'foo/bar (:seon.sci.reader/refused-token (events "#foo/bar 1")))))
    (testing "#= is not a tag-map escape hatch"
      (let [result
            (events "#=(+ 20 22)"
                    {:seon.sci.reader/tags
                     {(symbol "#=") identity}})]
        (is (= "#=" (:seon.sci.reader/refused-token result)))
        (is (= "#="
               (get-in result
                       [:seon.error/data :seon.sci.reader/tag])))))))

(deftest explicit-reader-policy-overrides-a-hostile-sci-context
  ;; The evaluating ctx is an argument, so a hostile one is supplied rather
  ;; than redefined: SCI merges the reader's explicit policy last
  ;; (sci/impl/parser.cljc:150-167).
  (let [hostile (assoc (sci/init {})
                       :readers (fn [_] (fn [_] :hostile-tag-value))
                       :read-eval true)
        in-ctx (fn [text & [context]]
                 (sci/binding [sci/ns (sci/create-ns 'user)]
                   (events text (merge {:seon.sci.reader/ctx hostile} context))))]
    ;; a hostile ctx cannot make a BUILT-IN tag mean something else:
    ;; #inst still reads as the default data reader's value
    (is (inst? (first (mapv :seon.sci.reader/form
                            (in-ctx "#inst \"2020-01-01\"")))))
    (is (= "#=" (:seon.sci.reader/refused-token (in-ctx "#=(+ 20 22)"))))
    (is (= 'foo/bar (:seon.sci.reader/refused-token (in-ctx "#foo/bar {:x 1}"))))
    (is (= [['inst "2020-01-01"]]
           (mapv :seon.sci.reader/form
                 (in-ctx "#inst \"2020-01-01\""
                         {:seon.sci.reader/tags
                          {'inst (fn [value] ['inst value])}}))))))

(deftest syntax-quote-and-auto-resolution-follow-the-evaluating-context
  ;; With the evaluating ctx, SCI resolves syntax-quote and `::alias/k` from
  ;; the namespace table the form will run in: core Vars, classes, imports,
  ;; excludes, refers, aliases and interns, per namespace.
  (let [ctx (sci/init {:namespaces {'foo.bar {'x 1 'y 2}}
                       :classes {'java.util.UUID java.util.UUID}})
        _ (sci/eval-string*
           ctx
           (str "(ns probe.a (:refer-clojure :exclude [inc])"
                " (:require [foo.bar :as fb :refer [x] :rename {x renamed-x}]"
                " [clojure.string :refer [join]]) (:import [java.util UUID]))"
                " (def local 1) (defn inc [v] v)"
                " (ns probe.b (:require [probe.a :as pa]))"))
        form-in (fn [namespace-name text]
                  (let [read-events
                        (sci/binding [sci/ns (sci/create-ns namespace-name)]
                          (events text {:seon.sci.reader/ns namespace-name
                                        :seon.sci.reader/ctx ctx}))]
                    (is (vector? read-events))
                    (if-let [failure (some :seon.sci.reader/error read-events)]
                      failure
                      (:seon.sci.reader/form (first read-events)))))
        ;; The read form is construction code; its quoted symbols, in order,
        ;; are the resolution.
        quoted (fn [form]
                 (keep #(when (and (seq? %) (= 'quote (first %))) (second %))
                       (tree-seq seq? seq form)))
        ;; A renamed refer is left out: SCI's resolver qualifies it into the
        ;; current namespace, as the replaced resolver also did.
        syntax-quoted "`(inc dec String UUID join fb/y local if pa/local)"]
    (is (= '(probe.a/inc clojure.core/dec java.lang.String java.util.UUID
             clojure.string/join foo.bar/y probe.a/local if pa/local)
           (quoted (form-in 'probe.a syntax-quoted)))
        "probe.a: its exclude, refer, import, alias and intern")
    (is (= '(clojure.core/inc clojure.core/dec java.lang.String probe.b/UUID
             probe.b/join fb/y probe.b/local if probe.a/local)
           (quoted (form-in 'probe.b syntax-quoted)))
        "probe.b: none of probe.a's bindings apply; its own alias does")
    (is (= :foo.bar/k (form-in 'probe.a "::fb/k")))
    (is (= :probe.b/k (form-in 'probe.b "::k")))
    (is (= :probe.a/k (form-in 'probe.b "::pa/k")))
    (is (= :seon.sci.reader/form
           (:seon.error/member (form-in 'probe.b "::fb/k")))
        "an alias the namespace lacks is a reader error, not a guess")
    (is (= :jvm (form-in 'probe.a "#?(:clj :jvm :cljs :js)")))
    (testing "the ctx survives recovery after an unreadable span"
      (let [read-events (sci/binding [sci/ns (sci/create-ns 'probe.b)]
                          (events "(+ 1\n(list `inc `String)"
                                  {:seon.sci.reader/ns 'probe.b
                                   :seon.sci.reader/ctx ctx}))]
        (is (= [true false] (mapv (comp boolean :seon.sci.reader/error) read-events)))
        (is (= '(list (quote clojure.core/inc) (quote java.lang.String))
               (:seon.sci.reader/form (second read-events))))))))

(deftest recovery-keeps-the-callers-reading-policy
  ;; A resumed read keeps deferral, features and tags, not only ns state.
  (let [read-events (events "(+ 1\n(f ::zz/k #?(:clj :jvm) #x/t 1)"
                            {:seon.sci.reader/defer-auto-resolve? true
                             :seon.sci.reader/tags {'x/t (fn [v] [:t v])}})]
    (is (= [true false] (mapv (comp boolean :seon.sci.reader/error) read-events)))
    (is (= '(f :zz/k :jvm [:t 1]) (:seon.sci.reader/form (second read-events))))))

(deftest every-reading-context-member-changes-reading-explicitly
  (testing "reader features"
    (is (= [:jvm]
           (mapv :seon.sci.reader/form
                 (events "#?(:clj :jvm :cljs :javascript)"))))
    (is (= [:javascript]
           (mapv :seon.sci.reader/form
                 (events
                  "#?(:clj :jvm :cljs :javascript)"
                  {:seon.sci.reader/features #{:cljs}})))))
  (testing "aliases"
    (is (= [:clojure.string/word]
           (mapv :seon.sci.reader/form
                 (events
                  "::str/word"
                  {:seon.sci.reader/aliases
                   {'str 'clojure.string}}))))
    (is (= :seon.sci.reader/form
           (:seon.sci.reader/unreadable-member (first-read-error (events "::str/word"))))))
  (testing "namespace and refers drive syntax quote without ambient state"
    (is (= 'clojure.core/inc
           (-> (events
                "`inc"
                {:seon.sci.reader/ns 'alpha
                 :seon.sci.reader/refers
                 {'inc 'clojure.core/inc}})
               first :seon.sci.reader/form second)))
    (is (= 'beta/inc
           (-> (events "`inc" {:seon.sci.reader/ns 'beta})
               first :seon.sci.reader/form second)))))

(def ^:private malformed-sources
  ["(defn f [x] (+ x 1)"
   "#=(+ 1 2)"
   "#unaccepted/tag 1"])

(deftest the-reader-never-invents-an-absent-source-bound
  (let [result (reader/read {:seon.sci.reader/text "(+ 1 2)"})]
    (is (= :seon.config.eval.result/max-source (:seon.sci.reader/unreadable-member result)))
    (is (contains? (:seon.error/data result)
                   :seon.config.eval.result/max-source))
    (is (nil? (get-in result
                      [:seon.error/data
                       :seon.config.eval.result/max-source])))))

(deftest every-reader-failure-is-data-and-never-a-throw
  (let [mutation
        (gen/one-of
         [(gen/elements malformed-sources)
          (gen/fmap #(apply str (repeat % "(")) (gen/choose 1 40))])
        property
        (prop/for-all [text mutation]
          (try
            (let [result (events text)
                  errors (if (vector? result)
                           (keep :seon.sci.reader/error result)
                           [result])]
              (and (seq errors)
                   (every?
                    (fn [failure]
                      (and (reader-failure? failure)
                           (or (:seon.sci.reader/unreadable-member failure)
                               (:seon.sci.reader/refused-token failure))
                           (= 'seon.sci.reader/error-value (:seon.error/operation failure))
                           (string? (:seon.error/message failure))
                           (map? (:seon.error/data failure))))
                    errors)))
            (catch Throwable _
              false)))]
    (support/assert-check!
     (tc/quick-check 100 property :seed 1785291018)
     "Malformed and refused source must always become flat data."))
  (testing "oversize reports the source length and bound"
    (let [result
          (events "(+ 1 2)"
                  {:seon.config.eval.result/max-source 3})]
      (is (= 3 (:seon.sci.reader/source-bound result)))
      (is (= 7 (:seon.sci.reader/length result)))
      (is (= 'seon.sci.reader/error-value (:seon.error/operation result)))
      (is (= 7
             (get-in result
                     [:seon.error/data :seon.sci.reader/length])))
      (is (= 3
             (get-in result
                     [:seon.error/data
                      :seon.config.eval.result/max-source])))))
  (testing "unreadable source carries the parser position"
    (let [result (first-read-error (events "(defn f\n  [x]"))]
      (is (= :seon.sci.reader/form
             (:seon.sci.reader/unreadable-member result)))
      (is (pos-int?
           (get-in result
                   [:seon.error/data :seon.sci.reader/line])))
      (is (pos-int?
           (get-in result
                   [:seon.error/data :seon.sci.reader/column])))
      (is (= "parse"
             (get-in result
                     [:seon.error/data :seon.sci.reader/phase])))))
  (testing "one malformed line is an error event and later forms survive"
    (let [result (events "(+ 1 ]\n(+ 2 3)")]
      (is (vector? result))
      (is (= 2 (count result)))
      (is (= :seon.sci.reader/form
             (get-in result [0 :seon.sci.reader/error :seon.sci.reader/unreadable-member])))
      (is (= '(+ 2 3) (:seon.sci.reader/form (second result)))))))

(deftest read-failures-are-isolated
  (doseq [[source expected]
          [["(unbalanced\n(good)" [:error :form]]
           ["(good)\n(unbalanced" [:form :error]]
           ["(a)\n(broken\n(b)" [:form :error :form]]
           ["\"unterminated" [:error]]
           ["(a)\n#unknown-tag value\n(b)" :refused]
           ["(a)\nshe said \"felt good\n(b)"
            [:form :form :form :error :form]]]]
    (testing (pr-str source)
      (let [result (events source)]
        (if (= :refused expected)
          (is (= 'unknown-tag (:seon.sci.reader/refused-token result)))
          (do
            (is (= expected (read-kinds result)))
            (doseq [event result :when (:seon.sci.reader/error event)]
              (is (string? (:seon.sci.reader/source event)))
              (is (vector? [(:seon.sci.reader/start event)
                            (:seon.sci.reader/end event)]))
              (is (= :seon.sci.reader/form
                     (get-in event [:seon.sci.reader/error
                                    :seon.sci.reader/unreadable-member]))))))))))

(deftest structured-error-kind-classification
  (doseq [[source expected]
          [["(a b c" :unclosed]
           ["[1 2 3" :unclosed]
           ["{:a 1" :unclosed]
           ["#{1 2" :unclosed]
           ["(str \"oops" :unclosed]
           ["(map #(+ % 1" :unclosed]
           ["(a)) oops" :stray-closer]
           ["(+ 1 3x)" :invalid-token]
           ["(get m :)" :invalid-token]
           ["{:a 1 :b}" :odd-map]
           ["^123 (foo)" :bad-metadata]]]
    (testing (pr-str source)
      (is (= expected
             (get-in (first-read-error (events source))
                     [:seon.error/data :seon.sci.reader/error-kind]))))))

(deftest an-unmatched-closer-is-a-reader-error-after-the-forms-before-it
  ;; Clojure refuses an unmatched delimiter (LispReader.java:1372-1377); the
  ;; reader keeps the completed form and then the error, in order.
  (doseq [[source expected]
          [["(message/user \"hi\")\n}" [:form :error]]
           ["(message/user \"hi\")\n]" [:form :error]]
           ["(+ 1 2))" [:form :error]]
           ["(let [x 1]\n(f x)\n}" [:error :form :error]]]]
    (is (= expected (read-kinds (events source))))))

(deftest recovered-forms-carry-the-coordinates-of-the-whole-source
  ;; Recovery resumes the same text, so a nested declaration after an error
  ;; carries its own line and column there, as SCI then defines it; error
  ;; facts name the whole source's line too.
  (doseq [[source nested error-line]
          [[")\n(do\n  (defn recovered \"D.\" {:malli/schema [:=> [:cat :int] :int]} [x] x))"
            [[2 1] [3 3]] 1]
           ["(defn f [)\n(defn g [] (h 1))\n)" [[2 1] [2 12]] 3]
           ["(1 2\n;; c\n(3 (4))" [[3 1] [3 4]] 3]]]
    (let [result (events source)
          form (some #(when-not (:seon.sci.reader/error %) %) result)]
      (is (= nested (mapv (comp (juxt :line :column) meta)
                          (filter seq? (tree-seq seq? seq (:seon.sci.reader/form form))))))
      (is (= (:seon.sci.reader/source form)
             (subs source (:seon.sci.reader/source-start form) (:seon.sci.reader/source-end form))))
      (is (= error-line (get-in (last (filter :seon.sci.reader/error result))
                                [:seon.sci.reader/error :seon.error/data :seon.sci.reader/line]))))))

(deftest recovery-never-anchors-on-inner-maps-or-vectors
  (doseq [[source expected]
          [["(db/transact! :seon [\n{:a 1}\n{:b 2}\n}]" [:error]]
           ["{:a 1}" [:form]]
           ["(good)\n{:a 1}" [:form :form]]
           ["(broken [\n{:a 1}" [:error]]]]
    (is (= expected (read-kinds (events source))))))

(deftest a-failure-inside-a-collection-skips-the-whole-form
  ;; THE CLASS: recovery from a failure nested in a collection resumes at the
  ;; next top-level anchor, so the collection's elements never become forms
  ;; and a deep malformed value is read once, not once per level (200 nested
  ;; maps missing one value made 199 events in 6.7 s).
  (is (= [:error :form] (read-kinds (events "[1 2 3x 4]\n(+ 1 2)"))))
  (is (= [:error :form] (read-kinds (events "{:a {:a 1 :b}}\n(+ 1 2)"))))
  (is (= :odd-map (get-in (first-read-error (events "{:a {:a 1 :b}}"))
                          [:seon.error/data :seon.sci.reader/error-kind])))
  (is (= [:error] (read-kinds (events (str (apply str (repeat 200 "{:a "))
                                           (apply str (repeat 199 "}"))))))))

(deftest a-failed-collection-ends-where-its-delimiters-balance
  ;; THE CLASS: a valid form after a malformed closed collection is read,
  ;; whether it opens with `(` at column zero or not, on the same line or the
  ;; next; the error event's source is the malformed form alone.
  (doseq [[source expected-source]
          [["[1 2 3x 4]\nmy-var" "[1 2 3x 4]"]
           ["[1 2 3x 4] (+ 1 2)" "[1 2 3x 4]"]
           ["{:a {:a 1 :b}}\n[5 6]" "{:a {:a 1 :b}}"]
           ["(f \"]\" \\) ; ]\n 3x)\n:k" "(f \"]\" \\) ; ]\n 3x)"]
           ["[1 3x] 42" "[1 3x]"]
           ["[1 3x\n(+ 40 2)]\n(+ 1 2)" "[1 3x\n(+ 40 2)]"]]]
    (let [result (events source)]
      (is (= [:error :form] (read-kinds result)))
      (is (= expected-source (:seon.sci.reader/source (first result))))))
  (is (= [:error :error] (read-kinds (events "[1 3x]\n[2 4x]")))))

(deftest recovery-never-hides-a-real-failure
  (let [broken (events "(+ 1 3x)")
        eof (events "(db/transact! :seon [{:a 1}")]
    (is (= [:error] (read-kinds broken)))
    (is (not (str/blank? (:seon.sci.reader/source (first broken)))))
    (is (= [:error] (read-kinds eof)))
    (is (= [:form] (read-kinds (events "(str \"}\")"))))))

(deftest eof-recovery-never-leaks-an-indented-inner-form
  (let [source "(defn foo []\n;; do the thing\n  (bar)"
        result (events source)]
    (is (= [:error] (read-kinds result)))
    (is (= source (:seon.sci.reader/source (first result))))
    (is (= :unclosed
           (get-in (first-read-error result)
                   [:seon.error/data :seon.sci.reader/error-kind])))))

(deftest recovery-strictly-advances
  (let [point (deref (ns-resolve 'seon.sci.reader 'recovery-point))
        unclosed {:seon.sci.reader/recovery-kind :unclosed
                  :seon.sci.reader/error-kind :unclosed}]
    (is (= (count ";; a\n;; b\n(c)")
           (point ";; a\n;; b\n(c)" 0 unclosed)))
    (is (> (point "(foo \"x\n;;c\n(g)" 0 unclosed) 0))
    (let [result (deref (future (events ";; lead\n(open \"unclosed\n;; mid\n(inner)"))
                        2000 ::timeout)]
      (is (not= ::timeout result))
      (is (vector? result)))))

(deftest invalid-prose-spans-recover-at-the-next-top-level-anchor
  (doseq [source ["denied /etc/hosts\n(+ 1 2)"
                  "80s(+ 2 3)"]]
    (let [result (events source)]
      (is (some #(= :invalid-token
                    (get-in % [:seon.sci.reader/error :seon.error/data
                               :seon.sci.reader/error-kind]))
                result))
      (is (some #(and (seq? (:seon.sci.reader/form %))
                      (= '+ (first (:seon.sci.reader/form %))))
                result)))))

(deftest reader-errors-retain-token-and-enclosing-parser-evidence
  (let [parsed (events "(my.task/done! {:my.task/item/id \"x\"})")
        data (:seon.error/data (first-read-error parsed))]
    (is (= ":my.task/item/id" (:seon.sci.reader/token data)))
    (is (= 'my.task/done! (:seon.sci.reader/call data)))
    (is (= 0 (:seon.sci.reader/argument-index data)))
    (is (= ["{" "("] (mapv :edamame/opened-delimiter (:seon.sci.reader/containers data)))))
  (let [source "Gate: failed\narguments: []\nexpected: number\nactual: wrong )\n(+ 1 2)"
        parsed (events source)]
    (is (= [:error :form] (read-kinds parsed)))
    (is (= "Gate:" (get-in (first-read-error parsed) [:seon.error/data :seon.sci.reader/token])))
    (is (= '(+ 1 2) (:seon.sci.reader/form (last parsed))))))

(deftest mined-digit-leading-result-symbols-never-leak-a-form
  (doseq [source
          ["(get-in result/0xO-2606281659 [:seon.render/text])"
           "(str (get-in result/4IU-2606281655 [:seon.render/text]))"]]
    (let [result (events source)]
      (is (= [:error] (read-kinds result)))
      (is (= :invalid-token
             (get-in (first-read-error result)
                     [:seon.error/data :seon.sci.reader/error-kind]))))))

(defn- event-namespaces
  [text]
  (mapv :seon.sci.reader/ns (events text)))

(deftest namespace-tracking-has-repl-semantics-and-fails-closed
  (testing "ns and in-ns affect the following top-level forms"
    (is (= ['user 'a 'a 'b]
           (event-namespaces
            "(ns a) (defn x []) (ns b) (defn y [])")))
    (is (= ['start 'a]
           (mapv :seon.sci.reader/ns
                 (events
                  "(in-ns 'a) (defn x [])"
                  {:seon.sci.reader/ns 'start})))))
  (testing "a declaration after prose and two forms on one line are ordinary"
    (is (= ['start 'a]
           (mapv :seon.sci.reader/ns
                 (events
                  "; prose from a model\n(ns a) (defn x [])"
                  {:seon.sci.reader/ns 'start})))))
  (testing "aliases and refers change with an ns declaration"
    (let [read-events
          (events
           (str "(ns a (:require "
                "[clojure.string :as str :refer [join]])) "
                "::str/word `join"))]
      (is (= :clojure.string/word
             (:seon.sci.reader/form (nth read-events 1))))
      (is (= 'clojure.string/join
             (-> read-events (nth 2) :seon.sci.reader/form second)))))
  (testing "an ordinary top-level call retains the last explicit namespace"
    ;; A hand list of operations believed namespace-stable erased every
    ;; declaration below the first `set!` or predicate registration in a
    ;; file, leaving 121 of 382 contracted functions in the program graph.
    (doseq [call ["(set! *warn-on-reflection* true)"
                  "(schema.edn/load! {})"
                  "(schema/register-core-predicate! 'a/p? p?)"
                  "(switch-namespace)"]]
      (let [read-events
            (events
             (str "(ns a) " call
                  " (defn ^{:malli/schema [:=> [:cat :int] :int]} x [n] n)"))
            declaration (peek read-events)]
        (is (= 'a (:seon.sci.reader/ns declaration)))
        (is (= 'a/x (:seon.fn/sym declaration))))))
  (testing "a switch visible only to evaluation removes attribution"
    (doseq [text
            ["(do (in-ns 'a)) (defn x [])"
             "(in-ns (symbol s)) (defn x [])"
             "(ns) (defn x [])"]]
      (let [read-events
            (events text {:seon.sci.reader/ns 'start})]
        (is (= 'start
               (:seon.sci.reader/ns (first read-events))))
        (is (not (contains? (second read-events)
                            :seon.sci.reader/ns)))
        (is (not (contains? (second read-events)
                            :seon.fn/sym))))))
  (testing "comments and strings containing ns syntax never switch"
    (is (= ['start 'start]
           (mapv :seon.sci.reader/ns
                 (events
                  "; (ns hidden)\n\"(ns hidden)\" (defn x [])"
                  {:seon.sci.reader/ns 'start}))))))

(deftest empty-input-is-an-empty-success-and-cardinality-is-caller-policy
  (is (= [] (events "")))
  (is (= [] (events "  ; comment only\n")))
  (is (= 2 (count (events "(+ 1 2) (* 3 4)")))))

(deftest declaration-facts-equal-the-current-var-lift
  (let [read-events
        (events (slurp (io/file "src/seon/sci/reader.cljc")))
        read-event
        (first (filter #(= 'seon.sci.reader/read (:seon.fn/sym %))
                       read-events))
        read-metadata (meta #'reader/read)]
    (is (= 'seon.sci.reader/read (:seon.fn/sym read-event)))
    (is (= [:seon.ns/name 'seon.sci.reader]
           (:seon.fn/ns read-event)))
    (is (= (:seon.sci.reader/source read-event)
           (:seon.fn/source read-event)))
    (is (= (:doc read-metadata) (:seon.fn/doc read-event)))
    (is (= (pr-str (:arglists read-metadata))
           (:seon.fn/arglists read-event)))
    (is (= (boolean (:private read-metadata))
           (:seon.fn/private? read-event)))
    (is (= (pr-str (:malli/schema read-metadata))
           (:seon.fn/spec read-event))))
  (let [read-events
        (events
         (str "(ns sample \"Sample namespace.\" "
              "(:require [clojure.string :as str] "
              "[clojure.test :refer [deftest]] "
              "[seon.schema :as schema])) "
              "(defn- ^{:seon.workload :io} hidden [x] x) "
              "(schema/register! ::amount [:int {:min 0}]) "
              "(deftest hidden-test (is true))"))]
    (is (= {:seon.ns/name 'sample
            :seon.ns/source
            (str "(ns sample \"Sample namespace.\" "
                 "(:require [clojure.string :as str] "
                 "[clojure.test :refer [deftest]] "
                 "[seon.schema :as schema]))")
            :seon.ns/doc "Sample namespace."
            :seon.ns/requires #{'clojure.string 'clojure.test 'seon.schema}
            :seon.ns/aliases
            #{{:seon.ns.alias/local 'str
               :seon.ns.alias/target-ns 'clojure.string}
              {:seon.ns.alias/local 'schema
               :seon.ns.alias/target-ns 'seon.schema}}
            :seon.ns/refers
            #{{:seon.ns.refer/local 'deftest
               :seon.ns.refer/target-ns 'clojure.test
               :seon.ns.refer/target-name 'deftest}}}
           (select-keys
            (first read-events)
            [:seon.ns/name :seon.ns/source :seon.ns/doc
             :seon.ns/requires :seon.ns/aliases :seon.ns/refers])))
    (is (= {:seon.fn/sym 'sample/hidden
            :seon.fn/ns [:seon.ns/name 'sample]
            :seon.fn/source
            "(defn- ^{:seon.workload :io} hidden [x] x)"
            :seon.fn/arglists "([x])"
            :seon.fn/private? true
            :seon.fn/workload :io}
           (select-keys
            (second read-events)
            [:seon.fn/sym :seon.fn/ns :seon.fn/source :seon.fn/arglists
             :seon.fn/private? :seon.fn/workload])))
    (is (= {:seon.schema/key :sample/amount
            :seon.schema/form "[:int {:min 0}]"
            :seon.schema/ns [:seon.ns/name 'sample]}
           (select-keys
            (nth read-events 2)
            [:seon.schema/key :seon.schema/form :seon.schema/ns])))
    (is (= {:seon.test/sym 'sample/hidden-test
            :seon.test/ns [:seon.ns/name 'sample]
            :seon.test/source "(deftest hidden-test (is true))"}
           (select-keys
            (nth read-events 3)
            [:seon.test/sym :seon.test/ns :seon.test/source])))))

(deftest renamed-and-refer-all-bindings-resolve-to-exact-targets
  (let [read-events
        (reader/read
         (let [source
               (str "(ns exact.bindings "
                    "(:require [clojure.test :refer [deftest] "
                    ":rename {deftest dt}] "
                    "[clojure.set :refer :all]))\n"
                    "(dt renamed-test :ok)\n"
                    "(defn united [a b] (union a b))")]
           {:seon.sci.reader/text source
            :seon.config.eval.result/max-source (count source)
            :seon.sci.reader/publics
            {'clojure.set #{'union 'intersection 'difference}}}))]
    (is (= 'exact.bindings/renamed-test
           (:seon.test/sym (second read-events))))
    (is (= #{{:seon.ns.refer/local 'dt
              :seon.ns.refer/target-ns 'clojure.test
              :seon.ns.refer/target-name 'deftest}
             {:seon.ns.refer/local 'union
              :seon.ns.refer/target-ns 'clojure.set
              :seon.ns.refer/target-name 'union}
             {:seon.ns.refer/local 'intersection
              :seon.ns.refer/target-ns 'clojure.set
              :seon.ns.refer/target-name 'intersection}
             {:seon.ns.refer/local 'difference
              :seon.ns.refer/target-ns 'clojure.set
              :seon.ns.refer/target-name 'difference}}
           (:seon.ns/refers (first read-events))))))

(deftest namespace-clauses-follow-clojures-own-ns-grammar
  ;; `clojure.core.specs.alpha/ns-form` owns the clause grammar; the hand
  ;; parser read `[clojure [edn :as e] set]` as requires #{clojure}.
  (let [[ns-event keyword-event syntax-event as-alias-event]
        (events
         (str "(ns prefixed (:require [clojure [edn :as e] "
              "[set :refer [union] :rename {union u}]] "
              "[a.b :as-alias ab] clojure.walk))\n"
              "::e/k `u ::ab/k"))]
    (is (= #{'clojure.edn 'clojure.set 'clojure.walk}
           (:seon.ns/requires ns-event))
        "prefix lists expand and :as-alias requires nothing")
    (is (= #{{:seon.ns.alias/local 'e :seon.ns.alias/target-ns 'clojure.edn}
             {:seon.ns.alias/local 'ab :seon.ns.alias/target-ns 'a.b}}
           (:seon.ns/aliases ns-event)))
    (is (= #{{:seon.ns.refer/local 'u
              :seon.ns.refer/target-ns 'clojure.set
              :seon.ns.refer/target-name 'union}}
           (:seon.ns/refers ns-event)))
    (is (= :clojure.edn/k (:seon.sci.reader/form keyword-event)))
    (is (= 'clojure.set/union
           (second (:seon.sci.reader/form syntax-event))))
    (is (= :a.b/k (:seon.sci.reader/form as-alias-event))))
  (testing "a standalone require reads prefix lists through the same grammar"
    (is (= :clojure.edn/k
           (:seon.sci.reader/form
            (second (events "(require '[clojure [edn :as e]]) ::e/k"))))))
  (testing "a nested prefix list is refused by Clojure, so no namespace is claimed"
    (let [[ns-event declaration]
          (events (str "(ns nested (:require [clojure [tools [reader :as r]]]))"
                       " (defn y [])")
                  {:seon.sci.reader/ns 'start})]
      (is (not (contains? ns-event :seon.ns/name)))
      (is (not (contains? ns-event :seon.ns/requires)))
      (is (not (contains? declaration :seon.sci.reader/ns))))))

(deftest standalone-require-advances-the-next-forms-reading-context
  (let [read-events
        (events
         (str "(require '[clojure.test :refer [deftest] "
              ":rename {deftest dt}])\n"
              "(require '[missing.reader.namespace :as-alias ghost])\n"
              "(dt renamed-after-require ::ghost/value)"))]
    (is (= 'user/renamed-after-require
           (:seon.test/sym (nth read-events 2))))
    (is (= :missing.reader.namespace/value
           (nth (:seon.sci.reader/form (nth read-events 2)) 2))
        "a prior literal :as-alias governs later keyword auto-resolution")))

(deftest schema-unregister-resolves-through-the-effective-alias
  (let [read-events
        (events
         (str "(require '[seon.schema :as schema])\n"
              "(schema/unregister! :shared.schema/value)"))]
    (is (= :shared.schema/value
           (:seon.sci.reader/schema-unregister-key
            (second read-events))))))

(deftest declaration-and-namespace-semantics-use-resolved-operator-identity
  (let [read-events
        (events
         (str "(ns audit.a (:require [clojure.test :refer [deftest]]))\n"
              "(foo/ns audit.phantom)\n"
              "(foo/in-ns 'audit.wrong)\n"
              "(foo/defn ghost [] 1)\n"
              "(foo/deftest ghost-test)\n"
              "'(in-ns 'audit.quoted)\n"
              "(defn real [] :ok)\n"
              "(deftest real-test)"))]
    (is (= #{'audit.a}
           (into #{} (keep :seon.ns/name) read-events)))
    (is (= #{'audit.a/real}
           (into #{} (keep :seon.fn/sym) read-events)))
    (is (= #{'audit.a/real-test}
           (into #{} (keep :seon.test/sym) read-events)))))

