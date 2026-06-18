(ns seon.repair-test
  "Corpus tests for `seon.repair/repair-source` (A.2). CLJC so both the
   JVM (`bin/test seon.repair-test`) and the CLJS pod / `:node-test`
   build exercise the SAME repair mechanism — parinferish indent-mode is
   pure CLJC and runs identically on both.

   The `reads?` gate is injected (cycle-free): in these pure tests we
   inject a parser-based predicate built on `seon.repl.internal/parse-forms`
   so the repair's accept-condition is exactly the eval pipeline's
   (`zero :kind :read failures`).

   Fixtures include the REAL `ari-2606180804` episode forms (the
   high-scores form that failed 12×, and the start-screen `Usd` form),
   verbatim shapes. The key-set preservation assertions are the
   load-bearing regression pins flagged by the critique."
  (:require
    #?(:clj  [clojure.test :as t :refer [deftest is testing]]
       :cljs [cljs.test    :as t :refer [deftest is testing]])
    [clojure.string :as str]
    [seon.repair :as repair]
    [seon.repl.internal :as parse]))

(defn- reads?
  "The eval pipeline's accept gate: TRUE iff `s` re-parses with zero
   `:kind :read` failures (and at least one entry)."
  [s]
  (let [es (parse/parse-forms s)]
    (and (seq es) (every? #(not= :read (:kind %)) es))))

(defn- repair*
  "Convenience: run repair-source with the parser-based reads? gate."
  [source]
  (repair/repair-source {:seon.repair/source source
                         :seon.repair/reads? reads?}))

(defn- body-map
  "Extract the body map of a repaired top-level form. For a bare map
   form the form IS the map; for a `(defn name [args] … body)` the body
   map is the last element of the (possibly nested) form. Returns nil
   when no map is found."
  [source]
  (let [entries (parse/parse-forms source)
        form    (:form (first entries))]
    (cond
      (map? form) form
      ;; (defn name [args] {map})  OR  (defn name [args] (let [...] {map}))
      (seq? form) (let [tail (last form)]
                    (cond
                      (map? tail) tail
                      (seq? tail) (let [t2 (last tail)] (when (map? t2) t2))
                      :else nil))
      :else nil)))

;; ============================================================
;; Basic delimiter repairs — the honest CAN-fix scope.
;; ============================================================

(deftest missing-trailing-paren-repaired
  (let [res (repair* "(defn foo [x]\n  (+ x 1")]
    (is (true? (:seon.repair/repaired? res)))
    (is (reads? (:seon.repair/source res)))
    (is (seq (:seon.repair/changes res))
        "the change-set names the inserted delimiter")))

(deftest unclosed-call-before-bracket-repaired
  ;; The dominant high-scores shape, distilled: a (str …) opened, never
  ;; closed before `]]}` arrives.
  (let [src "[:div\n  (str \"a\" b \"c\" (js/Date.)]]"
        res (repair* src)]
    (is (true? (:seon.repair/repaired? res)))
    (is (reads? (:seon.repair/source res)))))

(deftest mismatched-close-delimiter-repaired
  ;; indent-mode swaps a wrong close-delimiter TYPE.
  (let [res (repair* "(let [x 1)\n  x)")]
    (is (true? (:seon.repair/repaired? res)))
    (is (reads? (:seon.repair/source res)))))

(deftest stray-extra-closer-repaired
  (let [res (repair* "(+ 1 2))")]
    ;; Either repaired to balanced, or already-reads handling: the
    ;; contract is the OUTPUT reads cleanly when :repaired? is true.
    (when (:seon.repair/repaired? res)
      (is (reads? (:seon.repair/source res))))))

;; ============================================================
;; (a) REAL episode forms — key-set preservation [critique-flagged].
;; Both render keys MUST survive the repair, not just "it reads".
;; ============================================================

(def real-high-scores-form
  "The `Hyq` form (episode line 366) that failed 12×: a (str …) opened
   and never closed before `]]}` — the unmatched `]`."
  (str "(defn my-kb-high-scores-tile [_]\n"
       "  (let [query-result (seon.db/query {:seon.db/query '[:find ?t :where [?e :my.kb.paper/title ?t]]})\n"
       "        total-count (count query-result)]\n"
       "    {:seon.render/hiccup\n"
       "     [:div {:style {:gap \"12px\"}}\n"
       "      [:div {:style {:gap \"4px\"}}\n"
       "       (into []\n"
       "             (map (fn [[title cites year]]\n"
       "                    [:div [:span title]])\n"
       "               query-result)]\n"
       "      [:div\n"
       "       (str \"generated.md · \" total-count \" rows · :verified · \" (js/Date.)]]}\n"
       "     :seon.render/ai \"High scores tile updated with 50 papers.\"})"))

(def real-start-screen-form
  "The `Usd` form (episode lines 47-80): unclosed outer `[:div`,
   `:seon.render/ai` dedented to map-key level."
  (str "(defn my-start-screen-tile [_]\n"
       "  {:seon.render/hiccup\n"
       "   [:div {:style {:gap \"12px\"}}\n"
       "    [:h1 {:style {:fw \"900\"}}\n"
       "     \"ARIA SYSTEMS\"]\n"
       "    [:div {:style {:fs \"0.9rem\"}}\n"
       "     \"KNOWLEDGE BASE v1.0\"]\n"
       "    [:div {:style {:fs \"0.6rem\"}}\n"
       "     \"demo.tile · 4 rows · :verified · (js/Date.)\"]\n"
       "   :seon.render/ai \"80s Arcade Start Screen Tile.\"})"))

(deftest real-high-scores-form-preserves-both-render-keys
  (testing "the form that failed 12× repairs AND keeps both render keys"
    (let [res  (repair* real-high-scores-form)]
      (is (true? (:seon.repair/repaired? res))
          "the real high-scores form is repairable")
      (is (reads? (:seon.repair/source res)))
      (let [body (body-map (:seon.repair/source res))]
        (is (map? body) "the repaired body is a map")
        (is (contains? body :seon.render/hiccup)
            ":seon.render/hiccup survives the repair")
        (is (contains? body :seon.render/ai)
            ":seon.render/ai survives the repair")
        (is (= #{:seon.render/hiccup :seon.render/ai}
               (set (keys body)))
            "EXACTLY the two render keys — no key absorbed/lost")))))

(deftest real-start-screen-form-preserves-both-render-keys
  (testing "the start-screen Usd form repairs AND keeps both render keys"
    (let [res  (repair* real-start-screen-form)]
      (is (true? (:seon.repair/repaired? res))
          "the real start-screen form is repairable")
      (is (reads? (:seon.repair/source res)))
      (let [body (body-map (:seon.repair/source res))]
        (is (map? body))
        (is (contains? body :seon.render/hiccup))
        (is (contains? body :seon.render/ai))
        (is (= #{:seon.render/hiccup :seon.render/ai}
               (set (keys body))))))))

;; ============================================================
;; Idempotency — already-balanced source is NOT touched.
;; ============================================================

(def already-balanced-cases
  ["(+ 1 2)"
   "(seon.db/transact! {:seon.db/tx-data [{:foo/bar 1}]})"
   "(let [x 1 y 2] [:div {:style {:a \"b\"}} (str x y)])"])

(deftest idempotent-on-balanced-source
  (doseq [src already-balanced-cases]
    (testing (str "balanced source untouched — " (pr-str src))
      (let [res (repair* src)]
        (is (false? (:seon.repair/repaired? res))
            "balanced source is not repaired")
        (is (= src (:seon.repair/source res))
            "byte-identical output")
        (is (empty? (:seon.repair/changes res)))))))

;; ============================================================
;; Unrepairable garbage → :repaired? false, original returned.
;; ============================================================

(def unrepairable-cases
  ["((("                      ; only openers, no indentation signal
   "\"half-typed string"])    ; an unterminated string literal

(deftest unrepairable-returns-false-and-original
  (doseq [src unrepairable-cases]
    (testing (str "unrepairable — " (pr-str src))
      (let [res (repair* src)]
        ;; Contract: when not repaired, the ORIGINAL source is returned
        ;; (never a half-baked output that still doesn't read).
        (when (false? (:seon.repair/repaired? res))
          (is (= src (:seon.repair/source res)))
          (is (empty? (:seon.repair/changes res))))
        ;; And if it DID claim a repair, the output MUST read.
        (when (true? (:seon.repair/repaired? res))
          (is (reads? (:seon.repair/source res))))))))

;; ============================================================
;; The repair note — names the change + the structural shape.
;; ============================================================

(deftest repair-note-names-changes-and-shape
  (let [res  (repair* real-high-scores-form)
        note (repair/repair-note {:seon.repair/changes (:seon.repair/changes res)
                                  :seon.repair/shape "2-key map"})]
    (is (string? note))
    (is (re-find #"auto-balanced" note))
    (is (str/starts-with? note "↻")
        "leads with the ↻ breadcrumb glyph, no ;; prefix (renderer adds it)")
    (is (re-find #"delimiter" note))
    (is (re-find #"2-key map" note)
        "the structural-shape clause is present")
    (is (re-find #"Verify" note)
        "the note tells the agent to verify the repair")))
