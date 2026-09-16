(ns seon.edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [my.edit]
            [rewrite-clj.zip :as z]
            [seon.config :as config]
            [seon.db :as db]
            [seon.edit :as edit]
            [seon.effect :as effect]
            [seon.fn :as fn]
            [seon.fs :as fs]
            [seon.id :as id]
            [seon.program :as program]
            [seon.test-support :as test-support])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util Arrays]))

(def ^:private zero-digest
  "A digest-shaped value: these pure transformations never read a file, but
  `:my.edit/*-request` declares the fence, and the armed contract holds
  callers to what is declared."
  (apply str (repeat 64 "0")))

(defn- form-request
  [selector operation source]
  (cond-> {:my.edit/path "sample.clj"
           :my.edit/expected-digest zero-digest
           :my.edit/form selector
           :my.edit/operation operation}
    source (assoc :my.edit/source source)))

(defn- read-all
  [source]
  (binding [*read-eval* false]
    (with-open [reader (java.io.PushbackReader.
                        (java.io.StringReader. source))]
      (loop [forms []]
        (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}}
                         reader)]
          (if (= ::eof form)
            forms
            (recur (conj forms form))))))))

(deftest form-edit-preserves-every-unselected-byte
  (let [before
        (str ";; before\r\n"
             "#_(defn foo [] :discarded)\r\n"
             "^:private (defn foo  [x]\r\n"
             "  (+ x 1))\r\n"
             ",,;; after\r\n"
             "(def bar 1)\r\n")
        replacement "(defn foo [x] (* x 2))"
        result
        (edit/form
         before
         (form-request {:my.edit.form/head 'defn
                        :my.edit.form/name 'foo}
                       :replace replacement)
         8192)
        after (:seon.edit/source result)
        selected "^:private (defn foo  [x]\r\n  (+ x 1))"]
    (is (= (str (subs before 0 (.indexOf before selected))
                replacement
                (subs before (+ (.indexOf before selected)
                                (count selected))))
           after))
    (is (.contains after "#_(defn foo [] :discarded)\r\n"))
    (is (.contains after ",,;; after\r\n"))
    (is (string? (z/root-string (z/of-string* after))))
    (is (= ['(defn foo [x] (* x 2)) '(def bar 1)]
           (read-all after)))))

(deftest selectors-refuse-ambiguity-and-use-parsed-dispatch
  (let [source (str "(defmethod render :alpha [x] x)\n"
                    "(defmethod render :beta [x] x)\n")
        selector {:my.edit.form/head 'defmethod
                  :my.edit.form/name 'render}
        ambiguous
        (edit/form source (form-request selector :delete nil) 8192)
        selected
        (edit/form source
                   (form-request
                    (assoc selector :my.edit.form/dispatch-source ":beta")
                    :replace "(defmethod render :beta [x] :changed)")
                   8192)]
    (is (= :my.edit/ambiguous-match (:seon.error/kind ambiguous)))
    (is (= [1 2]
           (mapv :my.edit/from-line
                 (get-in ambiguous
                         [:seon.error/data :seon.edit/candidates]))))
    (is (= (str "(defmethod render :alpha [x] x)\n"
                "(defmethod render :beta [x] :changed)\n")
           (:seon.edit/source selected)))))

(deftest form-failures-never-fall-back-to-text
  (testing "malformed source refuses before exact-looking text can be changed"
    (let [source "(defn broken [] OLD"
          result
          (edit/form source
                     (form-request {:my.edit.form/head 'defn
                                    :my.edit.form/name 'broken}
                                   :replace "(defn broken [] :new)")
                     8192)]
      (is (= :my.edit/parse-refused (:seon.error/kind result)))
      (is (nil? (:seon.edit/source result)))))
  (testing "malformed replacement is refused by the declared contract"
    ;; `:my.edit/form-request` has always required one readable form in
    ;; `:my.edit/source` (`seon.edit/valid-form-operation?`), so an armed
    ;; caller never reaches the body. `matched-form-result` keeps its own
    ;; `:my.edit/invalid-replacement` branch because the edit hook loads this
    ;; namespace uninstrumented while Seon is down; what is asserted here is
    ;; the ruled armed behaviour, not the lenient shape.
    (is (thrown? clojure.lang.ExceptionInfo
                 (edit/form "(defn stable [] :old)\n"
                            (form-request {:my.edit.form/head 'defn
                                           :my.edit.form/name 'stable}
                                          :replace "(defn stable []")
                            8192))))
  (testing "not-found returns bounded nearby structural evidence"
    (let [result
          (edit/form "(def present 1)\n"
                     (form-request {:my.edit.form/head 'def
                                    :my.edit.form/name 'absent}
                                   :delete nil)
                     8192)]
      (is (= :my.edit/no-match (:seon.error/kind result)))
      (is (= [{:my.edit.form/head 'def
               :my.edit.form/name 'present
               :my.edit/from-line 1
               :my.edit/to-line 1}]
             (get-in result
                     [:seon.error/data :seon.edit/candidates]))))))

(deftest exact-edit-counts-before-changing
  (let [source "OLD\nkeep\nOLD\n"
        base {:my.edit/path "sample.txt"
              :my.edit/expected-digest (apply str (repeat 64 "0"))
              :my.edit/old-string "OLD"
              :my.edit/new-string "NEW"}
        ambiguous (edit/exact source base 8192)
        all (edit/exact source (assoc base :my.edit/replace-all? true) 8192)
        absent (edit/exact source (assoc base :my.edit/old-string "missing")
                           8192)]
    (is (= :my.edit/ambiguous-match (:seon.error/kind ambiguous)))
    (is (= 2 (get-in ambiguous
                     [:seon.error/data :my.edit/replacements])))
    (is (= "NEW\nkeep\nNEW\n" (:seon.edit/source all)))
    (is (= 2 (:my.edit/replacements all)))
    (is (= :my.edit/no-match (:seon.error/kind absent)))))

(deftest line-window-is-an-exact-second-fence
  (let [source "one\r\ntwo\r\nlast"
        base {:my.edit/path "sample.txt"
              :my.edit/expected-digest (apply str (repeat 64 "0"))
              :my.edit/from-line 2
              :my.edit/to-line 2
              :my.edit/old-window "two\r\n"
              :my.edit/new-window "changed\r\n"}
        changed (edit/lines source base 8192)
        refused (edit/lines source (assoc base :my.edit/old-window "two\n")
                            8192)
        final-line
        (edit/lines source
                    (assoc base
                           :my.edit/from-line 3
                           :my.edit/to-line 3
                           :my.edit/old-window "last"
                           :my.edit/new-window "done")
                    8192)]
    (is (= "one\r\nchanged\r\nlast" (:seon.edit/source changed)))
    (is (= :my.edit/no-match (:seon.error/kind refused)))
    (is (= "two\r\n"
           (get-in refused [:seon.error/data :my.edit/actual-window])))
    (is (= "one\r\ntwo\r\ndone" (:seon.edit/source final-line)))))

;;; ---------------------------------------------------------------------------
;;; Write-back provenance — what an edit wrote, in the unit the graph uses
;;; ---------------------------------------------------------------------------

(deftest form-edit-records-its-span-in-utf8-bytes
  (testing "the recorded span selects exactly the submitted bytes"
    (let [before (str "(def caff\u00e8 \"caff\u00e8\")\n"
                      "(defn foo [] 1)\n")
          replacement "(defn foo [] 2)"
          result (edit/form before
                            (form-request {:my.edit.form/head 'defn
                                           :my.edit.form/name 'foo}
                                          :replace replacement)
                            4096)
          [start end] (:seon.edit/form-span result)
          octets (.getBytes ^String (:seon.edit/source result)
                            StandardCharsets/UTF_8)]
      (is (= replacement
             (String. (Arrays/copyOfRange octets (int start) (int end))
                      StandardCharsets/UTF_8))
          "the span addresses disk bytes of the source after the write")
      (is (not= [(:seon.edit/start result) (:seon.edit/end result)]
                [start end])
          (str "char indices and byte offsets must differ below a non-ASCII "
               "character, or the conversion is not happening"))))

  (testing "every operation shape records one"
    (let [source "(def caff\u00e8 1)\n(def bar 2)\n"]
      (doseq [[label candidate]
              [[:exact (edit/exact source
                                   {:my.edit/path "sample.clj"
                                    :my.edit/expected-digest zero-digest
                                    :my.edit/old-string "(def bar 2)"
                                    :my.edit/new-string "(def bar 3)"}
                                   4096)]
               [:lines (edit/lines source
                                   {:my.edit/path "sample.clj"
                                    :my.edit/expected-digest zero-digest
                                    :my.edit/from-line 2 :my.edit/to-line 2
                                    :my.edit/old-window "(def bar 2)\n"
                                    :my.edit/new-window "(def bar 3)\n"}
                                   4096)]]]
        (let [[start end] (:seon.edit/form-span candidate)
              octets (.getBytes ^String (:seon.edit/source candidate)
                                StandardCharsets/UTF_8)]
          (is (some? start) (str label " records a span"))
          (is (= "(def bar 3)"
                 (.trim (String. (Arrays/copyOfRange octets (int start) (int end))
                                 StandardCharsets/UTF_8)))
              (str label " span selects the written bytes")))))))

(defn- declared-root-directory!
  "A scratch directory INSIDE a declared filesystem root.

  `:seon.config.fs/roots` is what `my.fs` admits, so a fixture writing to the
  system temp directory is refused before any capability runs — the root is
  derived here from the same config the handler reads, never assumed. An
  ABSENT root is a refusal naming the fixture's own omission: handing
  `(java.io.File. nil)` a missing dial is the fixture reading absence as a
  value, and its NullPointerException says nothing about the cluster the
  fixture forgot to seed."
  [connection prefix]
  (let [effective (config/effective (db/db connection) "default")
        working (:seon.config.fs/working-root effective)]
    (when-not (string? working)
      (throw (ex-info
              (str "This fixture's cluster declares no "
                   ":seon.config.fs/working-root, so no path it writes can be "
                   "inside a declared filesystem root. Seed the compiled "
                   "config row with `seed-write-back-cluster!`.")
              {:seon.config.fs/working-root working
               :seon.config.fs/roots (:seon.config.fs/roots effective)})))
    (let [scratch (java.io.File. ^String working "tmp")]
      (.mkdirs scratch)
      (.toFile (Files/createTempDirectory
                (.toPath scratch) prefix
                (into-array java.nio.file.attribute.FileAttribute []))))))

(defn- indexed-fixture-file!
  "Write one real Clojure file and index it with the production indexer."
  [connection source]
  (let [directory (declared-root-directory! connection "seon-edit-write-back")
        file (java.io.File. directory "fixture_subject.clj")
        _ (spit file source)
        artifact (fn/build-artifact
                  {:seon.fn/source-path (.getCanonicalPath file)
                   :seon.fn.file/first-party-functions []})
        _ (test-support/transacted! connection (:seon.fn.file/rows artifact))]
    {:file file :path (.getCanonicalPath file) :artifact artifact}))

(defn- write-back-request-context
  [connection ordinal]
  {:seon.env/environment (test-support/environment "seon.edit-test")
   :seon.db/connection connection
   :seon.agent/id "edit-agent"
   :seon.turn/id "edit-run"
   :seon.cluster.eval/ordinal ordinal
   :seon.boot/cluster-name "default"
   :seon.sci.admit/caps (config/result-caps (config/defaults))
   :seon.config/on-core-error :record
   ;; Handed explicitly, exactly as `seon.sci.eval` hands it.
   :seon.sci.eval/projection-state
   (atom {:seon.schema/projection (db/carried-projection (db/db connection))})
   :seon.effect/counter (atom -1)})

(defn- seed-write-back-cluster!
  "Seed the whole world an edit needs: the compiled config row, the agent,
  and the open turn its requests belong to.

  THE CONFIG ROW IS NOT OPTIONAL. `seon.fs.jvm` reads
  `:seon.config.fs/roots` and `:seon.config.fs/working-root` from the
  cluster's own facts; a branch with no config row declares neither, so in a
  cold worker every path is outside every root and the refusal that reaches
  the agent is not the one under test. `compile-manifest` fills the declared
  defaults, exactly as `bin/seon config apply` does in production."
  [connection]
  (test-support/transacted!
   connection
   [(:seon.config/desired-row
     (config/compile-manifest
      {:seon.boot/cluster-name "default"
       :seon.config/manifest
       {:seon.config.effect.background/time-limit-ms 60000}}))
    {:seon.agent/id "edit-agent"}
    {:seon.turn/id "edit-run"
     :seon.turn/agent [:seon.agent/id "edit-agent"]
     :seon.turn/opened-tx "datomic.tx"}]))

(defn- effect-of
  [connection ordinal]
  (db/pull (db/db connection)
           '[* {:seon.effect/file [:seon.fn.file/relative-path]}
             {:seon.effect/program [:seon.fn/sym :seon.fn/form-span]}]
           [:seon.effect/id
            (id/digest 12 [:seon.effect/id "edit-run" ordinal 0])]))

(deftest form-edit-refs-the-program-entity-it-changed
  (test-support/with-database
    (fn [connection]
      (seed-write-back-cluster! connection)
      (let [{:keys [file path]}
            (indexed-fixture-file!
             connection
             (str ";; caff\u00e8 heading comment\n"
                  "(ns fixture-subject)\n"
                  "(defn subject [] 1)\n"))
            edited
            (binding [effect/*request-context*
                      (write-back-request-context connection 1)]
              (my.edit/form! {:my.edit/path path
                              :my.edit/expected-digest
                              (:seon.fn.file/digest
                               (db/pull (db/db connection)
                                        [:seon.fn.file/digest]
                                        [:seon.fn.file/relative-path (fs/relative-path (fs/source-directory) path)]))
                              :my.edit/form {:my.edit.form/head 'defn
                                             :my.edit.form/name 'subject}
                              :my.edit/operation :replace
                              :my.edit/source "(defn subject [] 2)"}))
            receipt (effect-of connection 1)]
        (testing "the edit succeeded and the agent never sees writer keys"
          (is (nil? (:seon.error/kind edited)) (pr-str edited))
          (is (nil? (:seon.effect/provenance edited))
              "write-back provenance is the writer's, not the agent's"))
        (testing "the effect refs the indexed file and the declaration it wrote"
          (is (= (fs/relative-path (fs/source-directory) path) (get-in receipt [:seon.effect/file :seon.fn.file/relative-path])))
          (is (= "fixture-subject/subject"
                 (get-in receipt [:seon.effect/program :seon.fn/sym])))
          (is (= (:seon.effect/form-span receipt)
                 (get-in receipt [:seon.effect/program :seon.fn/form-span]))
              (str "a whole-declaration replacement writes exactly the new "
                   "declaration's span, in the same unit"))
          (is (= (:my.edit/before-digest edited)
                 (:my.edit/before-digest receipt))
              "the result's digests are datoms, not only EDN"))
        (testing "an edit inside no declaration records file and span, no program"
          (let [after-digest (:my.edit/after-digest edited)
                comment-edit
                (binding [effect/*request-context*
                          (write-back-request-context connection 2)]
                  (my.edit/exact! {:my.edit/path path
                                   :my.edit/expected-digest after-digest
                                   :my.edit/old-string "heading comment"
                                   :my.edit/new-string "heading note"}))
                comment-receipt (effect-of connection 2)]
            (is (nil? (:seon.error/kind comment-edit)) (pr-str comment-edit))
            (is (= (fs/relative-path (fs/source-directory) path) (get-in comment-receipt
                                [:seon.effect/file :seon.fn.file/relative-path])))
            (is (some? (:seon.effect/form-span comment-receipt)))
            (is (nil? (:seon.effect/program comment-receipt))
                "no declaration contains a comment, and none is fabricated")))
        (testing "the containment owner names the position instead of answering nil"
          (let [declarations
                (db/q '[:find [(pull ?declaration [:seon.fn/sym :seon.fn/form-span]) ...]
                        :in $ ?path
                        :where
                        [?file :seon.fn.file/relative-path ?path]
                        [?declaration :seon.fn/file ?file]]
                      (db/db connection) (fs/relative-path (fs/source-directory) path))
                refusal (program/declaration-at declarations 0)]
            (is (= :seon.program/no-declaration-at (:seon.error/kind refusal)))
            (is (= 0 (get-in refusal
                             [:seon.error/data :seon.program/position])))))
        (.delete file)
        (.delete (.getParentFile file))))))

(deftest changed-programs-since-basis-is-a-query
  (test-support/with-database
    (fn [connection]
      (seed-write-back-cluster! connection)
      (let [{:keys [file path]}
            (indexed-fixture-file!
             connection
             (str "(ns fixture-changed)\n"
                  "(defn alpha [] 1)\n"
                  "(defn beta [] 1)\n"
                  "(defn gamma [] 1)\n"))
            basis (db/basis-t (db/db connection))
            digest-of (fn []
                        (:seon.fn.file/digest
                         (db/pull (db/db connection) [:seon.fn.file/digest]
                                  [:seon.fn.file/relative-path (fs/relative-path (fs/source-directory) path)])))
            first-digest (digest-of)]
        (loop [ordinal 1
               expected-digest first-digest
               names ['alpha 'beta]]
          (when-let [declaration-name (first names)]
            (let [result
                  (binding [effect/*request-context*
                            (write-back-request-context connection ordinal)]
                    (my.edit/form! {:my.edit/path path
                                    :my.edit/expected-digest expected-digest
                                    :my.edit/form
                                    {:my.edit.form/head 'defn
                                     :my.edit.form/name declaration-name}
                                    :my.edit/operation :replace
                                    :my.edit/source
                                    (str "(defn " declaration-name " [] 2)")}))]
              (is (nil? (:seon.error/kind result)) (pr-str result))
              (recur (inc ordinal) (:my.edit/after-digest result) (next names)))))
        (is (= #{"fixture-changed/alpha" "fixture-changed/beta"}
               (set (db/q '[:find [?symbol ...]
                            :in $ ?basis
                            :where
                            [?effect :seon.effect/program ?declaration ?t]
                            [(> ?t ?basis)]
                            [?declaration :seon.fn/sym ?symbol]]
                          (db/db connection) basis)))
            (str "which declarations a worker changed is a query over effect "
                 "facts, with no file read and no source comparison"))
        (.delete file)
        (.delete (.getParentFile file))))))

(deftest edit-refusals-keep-their-filesystem-evidence
  ;; THE CLASS: a typed refusal degraded into an opaque one. `seon.fs.jvm`'s
  ;; internals refuse by throwing, and this handler calls them directly, so
  ;; every filesystem refusal an edit met — a path outside the declared
  ;; roots, a file past the read ceiling — reached the agent as
  ;; `:seon.effect/handler-failed`, whose entire evidence is the owner
  ;; symbol. The diagnosing agent then has nothing: that is the absence-as-
  ;; health failure at an error boundary.
  (test-support/with-database
    (fn [connection]
      (seed-write-back-cluster! connection)
      (let [outside (.getCanonicalPath
                     (java.io.File.
                      (.toFile (Files/createTempDirectory
                                "seon-edit-outside-roots"
                                (into-array java.nio.file.attribute.FileAttribute [])))
                      "subject.clj"))
            _ (spit outside "(defn subject [] 1)\n")
            refused
            (binding [effect/*request-context*
                      (write-back-request-context connection 7)]
              (my.edit/form! {:my.edit/path outside
                              :my.edit/expected-digest zero-digest
                              :my.edit/form {:my.edit.form/head 'defn
                                             :my.edit.form/name 'subject}
                              :my.edit/operation :replace
                              :my.edit/source "(defn subject [] 2)"}))]
        (is (= :my.fs/path-refused (:seon.error/kind refused))
            (str "the refusal must name what was missing, not collapse into "
                 "a handler failure; got " (pr-str refused)))
        (is (= outside (get-in refused [:seon.error/data :my.fs/path]))
            "and it must carry the exact path it refused")
        (.delete (java.io.File. outside))
        (.delete (.getParentFile (java.io.File. outside)))))))
