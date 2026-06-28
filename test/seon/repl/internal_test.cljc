(ns seon.repl.internal-test
  "Corpus tests for `seon.repl.internal/parse-forms`. CLJC so both JVM
   (`bin/test seon.repl.internal-test`) and the CLJS pod can exercise it
   from the same file.

   Test design: each `def` below holds a vector of `{:in :expected
   :note}` maps. The `deftest`s `doseq` over their corpus + `is`-
   compare extraction. New shapes the agent's LLM produces in the
   wild should add entries here as bugs surface — the corpus is
   the contract."
  (:require
    #?(:clj  [clojure.test :as t :refer [deftest is testing]]
       :cljs [cljs.test    :as t :refer [deftest is testing]])
    [clojure.string :as str]
    [seon.repl.internal :as parse]))

;; ============================================================
;; Basic shapes — happy path
;; ============================================================

(def basic-cases
  [{:in "(+ 1 2)"
    :expected [{:kind :form
                :narration ""
                :source "(+ 1 2)"
                :form '(+ 1 2)}]
    :note "single bare form"}

   {:in ";; narration\n(+ 1 2)"
    :expected [{:kind :form
                :narration "narration"
                :source "(+ 1 2)"
                :form '(+ 1 2)}]
    :note "comment attaches to following form"}

   {:in ";; line 1\n;; line 2\n(foo)"
    :expected [{:kind :form
                :narration "line 1\nline 2"
                :source "(foo)"
                :form '(foo)}]
    :note "consecutive comments accumulate"}

   {:in "(+ 1 2)\n(+ 3 4)"
    :expected [{:kind :form :narration "" :source "(+ 1 2)" :form '(+ 1 2)}
               {:kind :form :narration "" :source "(+ 3 4)" :form '(+ 3 4)}]
    :note "multiple forms, no narration"}

   {:in ";; first\n(a)\n;; second\n(b)"
    :expected [{:kind :form :narration "first"  :source "(a)" :form '(a)}
               {:kind :form :narration "second" :source "(b)" :form '(b)}]
    :note "per-form narration"}

   {:in ""
    :expected []
    :note "empty text"}

   {:in ";; trailing comment with no form"
    :expected [{:kind :comment :narration "trailing comment with no form"}]
    :note "trailing comment with no form → a comment-only entry (NOT dropped)"}

   {:in "(+ 1 2)\n;; afterthought"
    :expected [{:kind :form :narration "" :source "(+ 1 2)" :form '(+ 1 2)}
               {:kind :comment :narration "afterthought"}]
    :note "trailing comment after a form → its own comment entry, form kept"}])

(deftest basic-shapes
  (doseq [{:keys [in expected note]} basic-cases]
    (testing (str note " — " (pr-str in))
      (is (= expected (parse/parse-forms in))))))

;; ============================================================
;; Byte-faithful :source — load-bearing for resume re-eval
;; ============================================================

(def byte-faithful-cases
  [{:in "(defn foo [x] x)"
    :expected-source "(defn foo [x] x)"
    :note "canonical defn"}

   {:in "(defn  foo  [x]\n  x)"
    :expected-source "(defn  foo  [x]\n  x)"
    :note "preserves multi-line + extra whitespace"}

   {:in "(seon.db/transact!\n  {:seon.db/tx-data\n   [{:foo/bar 1}]})"
    :expected-source "(seon.db/transact!\n  {:seon.db/tx-data\n   [{:foo/bar 1}]})"
    :note "preserves indentation across multi-line maps"}

   {:in "@!atom-ref"
    :expected-source "@!atom-ref"
    :note "reader macro (deref) — reads as a seq, stays a form"}

   {:in "(let [x #(+ % 1)] (x 41))"
    :expected-source "(let [x #(+ % 1)] (x 41))"
    :note "fn literal nested in a list form"}])

(deftest source-is-byte-faithful
  (doseq [{:keys [in expected-source note]} byte-faithful-cases]
    (testing (str note " — " (pr-str in))
      (let [entries (parse/parse-forms in)]
        (is (= 1 (count entries)))
        (is (= expected-source (:source (first entries))))))))

;; ============================================================
;; Forms-and-prose-only (#50/#52) — a top-level read form is EVALUATED
;; (`:kind :form`) iff it is a LIST/SEQ. Everything else is PROSE and is
;; DROPPED (no entry) — NOT echoed back as a `;;` line (that echo was the
;; `;;`-imitation trap). The ONE exception is a demoted top-level DATA
;; LITERAL (`{…}`/`[…]`/`#{…}`), which emits a single `:kind :comment`
;; warning entry. Real `;;` comments still attach as narration to the
;; following form. Shapes below include the live mangles
;; (context-blind-spots-2026-06-11): `24`, `88.`, the `", felt good…"`
;; quote-fragment, prose sentences, echoed-prompt symbol lines, and the
;; fabricated `=> {…}` echo (#52).
;; ============================================================

(def prose-cases
  ;; Each case: :in, :form-count (evaluated :kind :form entries),
  ;; optional :entry-count (total entries), :first-form, :first-narration,
  ;; :warned? (a demoted-literal `⚠` warning is present).
  [{:in "Let me think (+ 1 2)"
    :note "prose before form — bare symbols DROPPED, form kept, NO narration"
    :form-count 1
    :entry-count 1
    :first-form '(+ 1 2)
    :first-narration ""}

   {:in "thinking thinking (+ 1 2)"
    :note "multiple bare symbols dropped, form kept"
    :form-count 1
    :entry-count 1
    :first-form '(+ 1 2)}

   {:in "(+ 1 2)\nokay\n(+ 3 4)"
    :note "bare symbol between forms — both real forms kept; prose dropped"
    :form-count 2
    :entry-count 2}

   {:in ";; thinking\nokay (+ 1 2)"
    :note "real `;;` comment attaches; bare-prose `okay` dropped"
    :form-count 1
    :entry-count 1
    :first-narration "thinking"}

   {:in "24"
    :note "bare top-level number — DROPPED, no entry (observed: s21 sweep-3)"
    :form-count 0
    :entry-count 0}

   {:in "88."
    :note "number with trailing dot — DROPPED (observed: s32 sweep-1)"
    :form-count 0
    :entry-count 0}

   {:in "\", felt good. Before I design a schema, I need to check whether a workout schema already exists\""
    :note "quote-fragment swallowed into a string literal — DROPPED"
    :form-count 0
    :entry-count 0}

   {:in "I ran this morning - 24 minutes, felt good."
    :note "whole prose sentence — DROPPED, no entry, no warning"
    :form-count 0
    :entry-count 0}

   {:in ":ok"
    :note "bare top-level keyword — DROPPED"
    :form-count 0
    :entry-count 0}

   {:in "do it now"
    :note "special symbols (`do`) are atoms — bare `do` is the English word, DROPPED"
    :form-count 0
    :entry-count 0}

   {:in "my.agent.RnA-2606111546=>"
    :note "echoed prompt line tokenizes to a symbol — DROPPED"
    :form-count 0
    :entry-count 0}

   {:in "The plan:\n(+ 1 2)\nThat should work"
    :note "legitimate form sandwiched between prose — exactly one eval, prose dropped"
    :form-count 1
    :entry-count 1
    :first-form '(+ 1 2)
    :first-narration ""}

   {:in "{:seon.eval/ok? true, :seon.eval/result 3}"
    :note "echoed result map is a DATA LITERAL — DROPPED + warning, NOT evaluated (#52)"
    :form-count 0
    :entry-count 1
    :warned? true}

   {:in "[1 2 3]"
    :note "bare vector literal — DROPPED + warning, NOT evaluated"
    :form-count 0
    :entry-count 1
    :warned? true}

   {:in "#{:a :b}"
    :note "bare set literal — DROPPED + warning, NOT evaluated"
    :form-count 0
    :entry-count 1
    :warned? true}

   {:in "(grants) => {:role :admin :ok true}"
    :note "#52: list runs (harmless), `=>` is prose, `{…}` demoted + warned, NO eval/result"
    :form-count 1
    :first-form '(grants)
    :warned? true}

   {:in "#inst \"2020-01-01\""
    :note "tagged literal (#inst) sexprs to a seq but is a DATUM — DROPPED, no warning"
    :form-count 0
    :entry-count 0}

   {:in "#uuid \"00000000-0000-0000-0000-000000000000\""
    :note "tagged literal (#uuid) — DROPPED, no warning"
    :form-count 0
    :entry-count 0}])

(deftest forms-and-prose-only
  (doseq [{:keys [in note form-count entry-count
                  first-form first-narration warned?]} prose-cases]
    (testing (str note " — " (pr-str in))
      (let [entries (parse/parse-forms in)
            forms   (filter #(= :form (:kind %)) entries)
            warning? (some #(and (= :comment (:kind %))
                                 (str/includes? (str (:narration %)) "⚠"))
                           entries)]
        (is (= form-count (count forms))
            (str "form-count mismatch for " (pr-str in)
                 " — got " (pr-str entries)))
        (when entry-count
          (is (= entry-count (count entries))
              (str "entry-count mismatch for " (pr-str in)
                   " — got " (pr-str entries))))
        (when first-form
          (is (= first-form (:form (first forms)))))
        (when (some? first-narration)
          (is (= first-narration (:narration (first forms)))))
        (when (some? warned?)
          (is (= warned? (boolean warning?))
              (str "demoted-literal warning expected=" warned?
                   " for " (pr-str in) " — got " (pr-str entries))))))))

;; ============================================================
;; Reader-macro forms — `@x`/`'x`/`#(…)`/`#'x` all read as SEQS, so they
;; EVALUATE (`:kind :form`); they are NOT prose. This is the seq?-not-coll?
;; cut: a list and these seq-shaped reader macros stay forms, while
;; maps/vectors/sets are prose.
;;
;; The INLINE-BACKTICK reader macros — `` `(…) `` (syntax-quote), `~x`
;; (unquote), `~@x` (unquote-splicing) — ALSO read as seqs but are now
;; PROSE (dropped): at the agent REPL a leading backtick is always inline
;; narration (`I'll use \`(subs s 0 5)\``), never intentional
;; macro-quoting — the "backtick cascade" bug. See `inline-backtick-prose`
;; below.
;; ============================================================

(def reader-macro-cases
  [{:in "@!atom-ref"       :form '(clojure.core/deref !atom-ref)}
   {:in "'x"               :form '(quote x)}
   {:in "#(+ % 1)"         :form '(fn* [%1] (+ %1 1))}
   {:in "#'some-var"       :form '(var some-var)}])

(deftest reader-macros-evaluate
  (doseq [{:keys [in form]} reader-macro-cases]
    (testing (str "reader macro evaluates — " (pr-str in))
      (let [entries (parse/parse-forms in)]
        (is (= 1 (count entries)))
        (is (= :form (:kind (first entries))))
        ;; fn-literal gensyms differ; compare structurally where exact,
        ;; else just assert it's a seq form.
        (when (not (str/starts-with? in "#("))
          (is (= form (:form (first entries)))))
        (is (seq? (:form (first entries))))))))

;; ============================================================
;; Inline-backtick prose — `` `(…) ``/`~x`/`~@x` are DROPPED, never
;; evaluated. The live "backtick cascade": one inline `` `(form) `` in
;; LLM narration ("I'll use `(subs s 0 5)` to format") used to shred into
;; multiple junk `:syntax-quote` evals plus bare-atom prose, all recorded
;; as real `result/<id>` history. Classifying the backtick reader-macros
;; as prose stops the cascade at its root.
;; ============================================================

(deftest inline-backtick-prose
  (testing "a top-level syntax-quote is prose (dropped), not a form"
    (is (= [] (parse/parse-forms "`(a b)"))))
  (testing "unquote / unquote-splicing are prose (dropped)"
    (is (= [] (parse/parse-forms "~x")))
    (is (= [] (parse/parse-forms "~@xs"))))
  (testing "the cascade shape: inline-backtick narration extracts NO forms"
    (is (= [] (parse/parse-forms "I'll use `(subs s 0 5)` to format."))))
  (testing "a real bare form after dropped backtick prose still evaluates"
    ;; `;;` comment carries through the dropped syntax-quote to the form.
    (let [entries (parse/parse-forms ";; using a quote\n`(noise)\n(+ 1 2)")
          forms   (filter #(= :form (:kind %)) entries)]
      (is (= 1 (count forms)))
      (is (= '(+ 1 2) (:form (first forms)))))))

;; ============================================================
;; Multiline / indented forms are indent-safe — the reader groups a
;; whole `(…)` as one top-level form regardless of indentation, while a
;; bare multiline data literal stays ONE demoted datum.
;; ============================================================

(deftest multiline-form-is-one-eval
  (let [entries (parse/parse-forms
                  "(seon.db/transact!\n  {:seon.db/tx-data\n   [{:foo/bar 1}]})")]
    (is (= 1 (count entries)))
    (is (= :form (:kind (first entries))))
    (is (= '(seon.db/transact! {:seon.db/tx-data [{:foo/bar 1}]})
           (:form (first entries)))))
  ;; A bare multiline map is ONE demoted datum (NOT a form), with a warning.
  (let [entries (parse/parse-forms "{:a 1\n :b 2\n :c 3}")]
    (is (= 1 (count entries)))
    (is (= :comment (:kind (first entries))))
    (is (str/includes? (str (:narration (first entries))) "⚠"))
    (is (empty? (filter #(= :form (:kind %)) entries)))))

;; ============================================================
;; Read-error recovery — bad form becomes a :read entry; subsequent
;; forms still parse.
;; ============================================================

(def recovery-cases
  [{:in "(unbalanced\n(good)"
    :note "unbalanced paren — bad span recorded, recovery to next form"
    :expected-kinds [:read :form]}

   {:in "(good)\n(unbalanced"
    :note "good form first, bad form last — both recorded"
    :expected-kinds [:form :read]}

   {:in "(a)\n(broken\n(b)"
    :note "bad form in the middle — forms before AND after kept"
    :expected-kinds [:form :read :form]}

   {:in "\"unterminated"
    :note "unterminated string — single :read entry, no forms"
    :expected-kinds [:read]}

   {:in "(a)\n#unknown-tag value\n(b)"
    :note "unknown reader tag — recovers to next column-0 form"
    :expected-kinds-contain [:form :form]}

   {:in "(a)\nshe said \"felt good\n(b)"
    :note "odd quote in prose opens an unterminated string — reader error mid-text must not poison adjacent forms"
    :expected-kinds-contain [:form :form]}])

(deftest read-failures-isolated
  (doseq [{:keys [in note expected-kinds expected-kinds-contain]} recovery-cases]
    (testing (str note " — " (pr-str in))
      (let [entries (parse/parse-forms in)
            kinds   (mapv :kind entries)]
        (when expected-kinds
          (is (= expected-kinds kinds)
              (str "kinds mismatch: got " (pr-str kinds))))
        (when expected-kinds-contain
          (is (every? (set kinds) expected-kinds-contain)
              (str "expected kinds " (pr-str expected-kinds-contain)
                   " all present, got " (pr-str kinds))))
        ;; Every :read entry must have :ok? false + non-blank :source + :error
        ;; + the re-noise/repair fields (:span absolute offsets, :error-kind).
        (doseq [e entries :when (= :read (:kind e))]
          (is (false? (:ok? e)))
          (is (string? (:source e)))
          (is (string? (:error e)))
          (is (vector? (:span e)))
          (is (= 2 (count (:span e))))
          (is (keyword? (:error-kind e))))))))

;; ============================================================
;; :error-kind classification — every rewrite-clj read-throw the
;; re-noise / repair layer dispatches on. Cores grounded in
;; tools.reader's impl/errors.clj families (cited in classify-read-error).
;; rewrite-clj wraps some messages with a `[line L, col C]` PREFIX and
;; others with an `[at line …]` SUFFIX — these cases pin BOTH shapes so a
;; prefix-only matcher (the original bug) can't regress.
;; ============================================================

(def error-kind-cases
  [{:in "(a b c"          :kind :eof                 :note "unclosed list"}
   {:in "[1 2 3"          :kind :eof                 :note "unclosed vector"}
   {:in "{:a 1"           :kind :eof                 :note "unclosed map"}
   {:in "#{1 2"           :kind :eof                 :note "unclosed set"}
   {:in "(str \"oops"     :kind :eof                 :note "unterminated string (suffix-form msg)"}
   {:in "(map #(+ % 1"    :kind :eof                 :note "unclosed anon-fn"}
   ;; trailing token keeps the span non-closer-only so it survives PRONG 1
   ;; as a real :read (a pure `(a))` orphan is dropped — see prong1 tests).
   {:in "(a)) oops"       :kind :unmatched-delimiter :note "surplus closer + trailing token"}
   {:in "(+ 1 3x)"        :kind :invalid-token       :note "invalid number"}
   {:in "(get m :)"       :kind :invalid-token       :note "lone colon (prefix-form msg)"}
   {:in "{:a 1 :b}"       :kind :odd-map             :note "odd map — value MISSING (unsafe to fix)"}
   {:in "^123 (foo)"      :kind :bad-metadata        :note "metadata not a map/kw/sym/string"}])

(deftest error-kind-classification
  (doseq [{:keys [in kind note]} error-kind-cases]
    (testing (str note " — " (pr-str in))
      (let [reads (filter #(= :read (:kind %)) (parse/parse-forms in))
            ek    (:error-kind (first reads))]
        (is (= kind ek)
            (str "expected :error-kind " kind " got " (pr-str ek)
                 " (msg: " (:error (first reads)) ")"))))))

;; ============================================================
;; Borrowed false-positive guard — inputs the real ClojureScript reader
;; ACCEPTS (corpus lifted from reference-code/.../cljs/reader_test.cljs)
;; must NEVER produce a :read failure in our parser. We don't start from
;; zero: the reader's own accepted corpus is our regression net against
;; mis-flagging valid Clojure as broken.
;; ============================================================

(def reader-accepted-corpus
  ;; valid forms the cljs reader round-trips (reader_test.cljs)
  ["1" "-1" "-1.5" "[3 4]" "\"foo\"" ":hello" "goodbye" "%" "#{1 2 3}"
   "(7 8 9)" "foo/bar" "\\a" "^String {:a 1}" "[:a b #{c {:d [:e :f :g]}}]"
   ":foo/bar" "nil" "true" "false" "#_nope 2" "{:a 1 :b 2 :c 3}"
   "#js [1 2 3]" "#js {:foo \"bar\"}" "#inst \"2010-11-12T13:14:15.666-05:00\""
   "#uuid \"550e8400-e29b-41d4-a716-446655440000\""
   "(map #(+ % 1) [1 2 3])" "#?(:clj 1 :cljs 2)" "#'foo" "`(a ~b ~@c)"])

(deftest reader-accepted-never-misflagged
  (doseq [in reader-accepted-corpus]
    (testing (str "valid reader input must not :read-fail — " (pr-str in))
      (is (not-any? #(= :read (:kind %)) (parse/parse-forms in))
          (str "mis-flagged valid input as broken: " (pr-str in))))))

;; ============================================================
;; PRONG 1 (eval-segmenter research) — a pure-closer recovery span is an
;; orphan-delimiter artifact (the unbalanced form upstream already shed
;; it + is itself recorded). Drop it at emit; never shred one broken
;; block into a wall of `}`/`]` rows. Assert BEHAVIOR (kinds), not strings.
;; ============================================================

(def closer-only-cases
  [{:in "(message/user \"hi\")\n}"   :kinds [:form] :note "trailing orphan } dropped, good form kept"}
   {:in "(message/user \"hi\")\n]"   :kinds [:form] :note "orphan ] dropped too"}
   {:in "(a)\n}\n}\n}"               :kinds [:form] :note "stacked orphans all dropped"}
   {:in "(let [x 1]\n(f x)\n}"       :kinds [:read :form] :note "broken-head :read kept, trailing orphan dropped"}])

(deftest prong1-closer-only-spans-dropped
  (doseq [{:keys [in kinds note]} closer-only-cases]
    (testing (str note " — " (pr-str in))
      (is (= kinds (mapv :kind (parse/parse-forms in)))
          (str "kinds: " (pr-str (mapv :kind (parse/parse-forms in))))))))

(deftest prong1-never-hides-a-real-failure
  ;; risk #1: a genuinely broken FORM (leading `(` + bad token) must STILL
  ;; surface as a :read with non-blank source + :error.
  (testing "real broken form still recorded"
    (let [r (first (filter #(= :read (:kind %)) (parse/parse-forms "(+ 1 3x)")))]
      (is (some? r))
      (is (not (str/blank? (:source r))))
      (is (string? (:error r)))))
  ;; risk #2: incomplete final form (EOF mid-form) is ONE honest :read,
  ;; NOT a dropped orphan (its span is the whole form, not pure closers).
  (testing "EOF mid-form stays one honest :read"
    (let [es (parse/parse-forms "(db/transact! :seon [{:a 1}")]
      (is (= [:read] (mapv :kind es)))
      (is (not (str/blank? (:source (first es)))))))
  ;; risk #3: a closer INSIDE a string of a GOOD form is never stripped
  ;; (closer-only? runs on failed spans only; this form reads clean).
  (testing "closer inside a string literal is not stripped"
    (is (= [:form] (mapv :kind (parse/parse-forms "(str \"}\")"))))))

;; ============================================================
;; Narration semantics on recovery — narration accumulated before a
;; bad form attaches to the :read entry, not to the next good form.
;; ============================================================

(deftest narration-attaches-to-failure-not-next-good
  (let [entries (parse/parse-forms ";; about-to-fail\n(unbalanced\n;; about-next-good\n(good)")
        read-entry (first (filter #(= :read (:kind %)) entries))
        form-entry (first (filter #(= :form (:kind %)) entries))]
    (is (some? read-entry))
    (is (= "about-to-fail" (:narration read-entry)))
    (is (some? form-entry))
    (is (= "about-next-good" (:narration form-entry)))))

;; ============================================================
;; A.1 — prose-vs-code classification. A reader THROW on a prose token
;; (`80s`, `to:`, `detail:`, `v1.0`) must be DROPPED, NOT recorded as a
;; `:read` failure — UNLESS the failing span has a LIST opener `(` at its
;; START (a genuinely broken FORM like `(+ 1 3x)`). The opener-at-START
;; rule (now `(`-only) keeps inline-code prose ("I'll use (subs …) to
;; format") classified as prose while keeping a real broken list form as
;; broken code. The KEY invariant here is `:no-read?` — a prose token
;; never becomes a `:read` failure; the prose is DROPPED (not echoed).
;; ============================================================

(def prose-token-cases
  [{:in "80s arcade/start screen."
    :note "Invalid number `80s` in prose — DROPPED, NO :read failure (FHb)"
    :entry-count 0
    :no-read? true}

   {:in "to:\n1.  Register the schema."
    :note "Invalid symbol `to:` in prose — captured, NO :read failure (SpO)"
    :form-count 0
    :no-read? true}

   {:in "detail: The user said \"have the interface update\"."
    :note "Invalid symbol `detail:` in prose — captured, NO :read failure (ZyJ)"
    :no-read? true}

   {:in "Version v1.0 shipped."
    :note "Invalid token `v1.0` mid-prose — captured, NO :read failure"
    :no-read? true}

   {:in "I'll use (subs (str (js/Date.)) 11 19) to format the time."
    :note "(b) parenthetical-prose: opener MID-sentence → prose, NOT a :read failure [critique-flagged]"
    :no-read? true}

   {:in "(+ 1 3x)"
    :note "opener AT START + Invalid number `3x` → genuinely broken CODE, recorded as :read"
    :expected-kinds [:read]}

   {:in "80s arcade/start screen.\nThis should include:\n- Neon colors.\n;; Define the tile\n(defn my-tile [_] {:seon.render/hiccup [:div]})"
    :note "bare-prose preamble DROPPED; real `;;` comment kept; defn parses (episode turn-2)"
    :form-count 1
    :no-read? true
    :narration-includes ["Define the tile"]}])

(deftest prose-tokens-dropped-not-read-failures
  (doseq [{:keys [in note entry-count form-count no-read?
                  expected-kinds narration-includes]} prose-token-cases]
    (testing (str note " — " (pr-str in))
      (let [entries (parse/parse-forms in)
            kinds   (mapv :kind entries)
            forms   (filter #(= :form (:kind %)) entries)]
        (when expected-kinds
          (is (= expected-kinds kinds)
              (str "kinds mismatch: got " (pr-str kinds))))
        (when no-read?
          (is (not-any? #(= :read %) kinds)
              (str "a :read failure leaked for prose: " (pr-str kinds))))
        (when narration-includes
          ;; The real `;;` comment attaches to the form it precedes.
          (let [narr (str/join "\n" (keep :narration entries))]
            (doseq [frag narration-includes]
              (is (clojure.string/includes? narr frag)
                  (str "narration must include " (pr-str frag)
                       " — got " (pr-str narr))))))
        (when entry-count
          (is (= entry-count (count entries))
              (str "entry-count mismatch: " (pr-str entries))))
        (when form-count
          (is (= form-count (count forms))
              (str "form-count mismatch: " (pr-str kinds))))))))

;; ============================================================
;; Round-trip — under forms-and-prose-only, re-rendering an entry's `;;`
;; comment-preamble above its source and RE-PARSING it must recover the
;; SAME forms and the SAME real-comment text. Bare prose does NOT
;; round-trip — it was DROPPED on the first parse, so the rendered stream
;; never contains it. This is the parse-level half (the full
;; format-eval-row render round-trip lives in the ctx test).
;; ============================================================

(defn- render-entry
  "Re-render one parse entry as the unified stream fragment: its
   `;;` comment-preamble as `;;` lines, then (for a form/read) the source.
   A demoted-literal warning (`⚠`) entry is a comment, so it round-trips
   as a `;;` line — but it is NOT a form, so it never re-evaluates."
  [{:keys [kind narration source]}]
  (let [pre (when (and narration (not (str/blank? narration)))
              (->> (str/split-lines narration)
                   (map #(str ";; " (str/replace % #"^[\s;]+" "")))
                   (str/join "\n")))]
    (str pre
         (when (and pre (#{:form :read} kind)) "\n")
         (when (#{:form :read} kind) source))))

(def round-trip-cases
  ;; forms + real `;;` comments survive; bare prose is dropped on parse 1
  ["raw text thinking\n;; writing a function to add 1 + 1\n(+ 1 1)\nforgot to put comments here\n(correct-working-fn correct-args)"
   ";; first\n(a)\n;; second\n(b)"
   "The plan:\n(+ 1 2)\nThat should work"
   ";; just a trailing thought"])

(deftest narration-round-trips
  (doseq [in round-trip-cases]
    (testing (str "round-trip — " (pr-str in))
      (let [entries  (parse/parse-forms in)
            rendered (str/join "\n" (map render-entry entries))
            reparsed (parse/parse-forms rendered)]
        ;; forms survive byte-faithfully + in order
        (is (= (mapv :form (filter #(= :form (:kind %)) entries))
               (mapv :form (filter #(= :form (:kind %)) reparsed)))
            (str "forms changed across round-trip — " (pr-str rendered)))
        ;; every real `;;` comment line re-appears as a `;;` line
        (doseq [e entries
                ln (when (:narration e) (str/split-lines (:narration e)))
                :when (not (str/blank? ln))]
          (is (str/includes? rendered (str ";; " (str/replace ln #"^[\s;]+" "")))
              (str "comment line " (pr-str ln) " missing from "
                   (pr-str rendered))))
        ;; re-parsed narration equals the original (idempotent)
        (is (= (mapv :narration entries) (mapv :narration reparsed))
            (str "narration drifted across round-trip — got "
                 (pr-str (mapv :narration reparsed))))))))
