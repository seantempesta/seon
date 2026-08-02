(ns seon.sci.reader-test
  (:refer-clojure :exclude [read])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sci.core :as sci]
            [seon.sci.reader :as reader]
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
            :seon.sci.reader/max-chars 1048576}
           context))))

(defn- error?
  [value]
  (contains? value :seon.error/kind))

(defn- event-slices
  [text read-events]
  (mapv #(subs text
               (:seon.sci.reader/start %)
               (:seon.sci.reader/end %))
        read-events))

(defn- source-round-trips?
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
         (vector? source-read)
         (= (mapv (comp pr-str :seon.sci.reader/form) first-read)
            (mapv (comp pr-str :seon.sci.reader/form) second-read)
            (mapv (comp pr-str :seon.sci.reader/form) source-read))
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
        property
        (prop/for-all [rotation (gen/choose 0 (dec (count files)))]
          ;; Rotation makes the seed visible while every trial still covers
          ;; every source file. JVM regex values compare through pr-str
          ;; because java.util.regex.Pattern is identity-equal only.
          (every?
           source-round-trips?
           (concat (drop rotation files) (take rotation files))))]
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
  (testing "offsets and max-chars use JVM UTF-16 character units"
    (let [text "😀 42"
          read-events (events text)]
      (is (= 5 (count text)))
      (is (= [0 2 5]
             (into [(:seon.sci.reader/start (first read-events))]
                   (map :seon.sci.reader/end)
                   read-events)))
      (is (= 2
             (get-in
              (events "😀" {:seon.sci.reader/max-chars 1})
              [:seon.error/data :seon.sci.reader/length]))))))

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
        (is (= :seon.sci.reader/refused-tag
               (:seon.error/kind refused)))
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
      (is (= :seon.sci.reader/refused-tag
             (:seon.error/kind (events "#foo/bar 1")))))
    (testing "#= is not a tag-map escape hatch"
      (let [result
            (events "#=(+ 20 22)"
                    {:seon.sci.reader/tags
                     {(symbol "#=") identity}})]
        (is (= :seon.sci.reader/refused-tag
               (:seon.error/kind result)))
        (is (= "#="
               (get-in result
                       [:seon.error/data :seon.sci.reader/tag])))))))

(deftest explicit-reader-policy-overrides-a-hostile-sci-context
  (let [original-init sci/init
        hostile-init
        (fn [_]
          (original-init
           {:readers (fn [_] (fn [_] :hostile-tag-value))
            :read-eval true}))]
    (with-redefs [sci/init hostile-init]
      ;; a hostile ctx cannot make a BUILT-IN tag mean something else:
      ;; #inst still reads as the default data reader's value, never
      ;; the hostile :hostile-tag-value
      (is (inst? (first (mapv :seon.sci.reader/form
                              (events "#inst \"2020-01-01\"")))))
      (is (= :seon.sci.reader/refused-tag
             (:seon.error/kind (events "#=(+ 20 22)"))))
      (is (= :seon.sci.reader/refused-tag
             (:seon.error/kind (events "#foo/bar {:x 1}"))))
      (is (= [['inst "2020-01-01"]]
             (mapv :seon.sci.reader/form
                   (events
                    "#inst \"2020-01-01\""
                    {:seon.sci.reader/tags
                     {'inst (fn [value] ['inst value])}})))))))

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
    (is (= :seon.sci.reader/unreadable
           (:seon.error/kind (events "::str/word")))))
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

(deftest every-refusal-is-a-flat-value-and-never-a-throw
  (let [mutation
        (gen/one-of
         [(gen/elements malformed-sources)
          (gen/fmap #(apply str (repeat % "(")) (gen/choose 1 40))])
        property
        (prop/for-all [text mutation]
          (try
            (let [result (events text)]
              (and (error? result)
                   (contains?
                    #{:seon.sci.reader/unreadable
                      :seon.sci.reader/refused-tag}
                    (:seon.error/kind result))
                   (string? (:seon.error/message result))
                   (map? (:seon.error/data result))))
            (catch Throwable _
              false)))]
    (support/assert-check!
     (tc/quick-check 100 property :seed 1785291018)
     "Malformed and refused source must always become flat data."))
  (testing "oversize is the third and only other kind"
    (let [result
          (events "(+ 1 2)"
                  {:seon.sci.reader/max-chars 3})]
      (is (= :seon.sci.reader/oversize
             (:seon.error/kind result)))
      (is (= 7
             (get-in result
                     [:seon.error/data :seon.sci.reader/length])))
      (is (= 3
             (get-in result
                     [:seon.error/data :seon.sci.reader/max-chars])))))
  (testing "unreadable source carries the parser position"
    (let [result (events "(defn f\n  [x]")]
      (is (= :seon.sci.reader/unreadable
             (:seon.error/kind result)))
      (is (pos-int?
           (get-in result
                   [:seon.error/data :seon.sci.reader/line])))
      (is (pos-int?
           (get-in result
                   [:seon.error/data :seon.sci.reader/column])))
      (is (= "parse"
             (get-in result
                     [:seon.error/data :seon.sci.reader/phase]))))))

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
        (is (= 'a (:seon.sci.reader/ns declaration)) call)
        (is (= "a/x" (:seon.fn/sym declaration)) call))))
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
        (first (filter #(= "seon.sci.reader/read" (:seon.fn/sym %))
                       read-events))
        read-metadata (meta #'reader/read)]
    (is (= "seon.sci.reader/read" (:seon.fn/sym read-event)))
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
    (is (= {:seon.fn/sym "sample/hidden"
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
            :seon.schema/form "[:int {:min 0}]"}
           (select-keys
            (nth read-events 2)
            [:seon.schema/key :seon.schema/form])))
    (is (= {:seon.test/sym "sample/hidden-test"
            :seon.test/ns [:seon.ns/name 'sample]
            :seon.test/source "(deftest hidden-test (is true))"}
           (select-keys
            (nth read-events 3)
            [:seon.test/sym :seon.test/ns :seon.test/source])))))

(deftest renamed-and-refer-all-bindings-resolve-to-exact-targets
  (let [read-events
        (reader/read
         {:seon.sci.reader/text
          (str "(ns exact.bindings "
               "(:require [clojure.test :refer [deftest] "
               ":rename {deftest dt}] "
               "[clojure.set :refer :all]))\n"
               "(dt renamed-test :ok)\n"
               "(defn united [a b] (union a b))")
          :seon.sci.reader/publics
          {'clojure.set #{'union 'intersection 'difference}}})]
    (is (= "exact.bindings/renamed-test"
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

(deftest standalone-require-advances-the-next-forms-reading-context
  (let [read-events
        (events
         (str "(require '[clojure.test :refer [deftest] "
              ":rename {deftest dt}])\n"
              "(require '[missing.reader.namespace :as-alias ghost])\n"
              "(dt renamed-after-require ::ghost/value)"))]
    (is (= "user/renamed-after-require"
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
    (is (= #{"audit.a/real"}
           (into #{} (keep :seon.fn/sym) read-events)))
    (is (= #{"audit.a/real-test"}
           (into #{} (keep :seon.test/sym) read-events))))
  (let [nested (events "(ns audit.a)\n(do (defn hidden [x] x))")]
    (is (= [{:seon.fn/sym "audit.a/hidden"
             :seon.fn/ns [:seon.ns/name 'audit.a]
             :seon.fn/source "(defn hidden [x] x)"
             :seon.fn/arglists "([x])"
             :seon.fn/private? false}]
           (:seon.sci.reader/nested-declarations (second nested))))))

(def ^:private surface-exemptions
  {"src/seon/schema/edn.clj"
   "EDN schema data has a different grammar and proves EOF separately."
   "src/seon/schema.clj"
   "Schema candidate encoding is an ordinary EDN codec."
   "src/seon/cluster/run.cljc"
   "Run values use an ordinary EDN result codec."})

(def ^:private accepted-source-reader-pattern
  #"\((?:sci/(?:parse-string|parse-next\+string)|edamame/parse-string-all|read(?:\+string)?\s+\{|clojure\.core/read-string)")

(defn- second-reader-sites
  []
  (->> ["src" "script" "bin"]
       (mapcat #(file-seq (io/file %)))
       (filter #(.isFile %))
       (remove #(= "src/seon/sci/reader.cljc" (str %)))
       (remove #(contains? surface-exemptions (str %)))
       (keep
        (fn [file]
          (try
            (let [matches
                  (keep-indexed
                   (fn [index line]
                     (when (re-find accepted-source-reader-pattern line)
                       (inc index)))
                   (str/split-lines (slurp file)))]
              (when (seq matches)
                [(str file) (vec matches)]))
            (catch Throwable _
              nil))))
       (into (sorted-map))))

(deftest evaluator-no-longer-owns-a-second-reader-surface
  (let [sites (second-reader-sites)]
    (is (seq sites)
        "non-evaluator tooling still owns its specialized readers")
    (is (not (contains? sites "src/seon/sci/eval.clj")))
    (is (contains? sites "src/seon/fn.clj")
        "the static indexer reads only the namespace form after clj-kondo analysis")
    ;; MIGRATED 2026-07-29 (generate-code v0): the reply splitter reads
    ;; through this reader now, which is where its forms get the
    ;; parse-time namespace-in-effect they freeze with.
    (is (not (contains? sites "src/seon/cluster/reply.cljc")))))
