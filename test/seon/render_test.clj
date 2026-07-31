(ns seon.render-test
  "Sealed acceptance draft for the ONE projection router.

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27, step 1 of the
  error-wiring order). The implementation lane makes these green by
  implementing `seon.render` ONLY — schemas and tests are byte-sealed;
  friction is reported, never resolved by weakening.

  The suite is deliberately ignorant of the error family: it routes
  units it invents, through projections it defines, to prove the router
  is generic. `seon.error-test` proves the error family's units route
  through this same router, which is the seam that matters — if the
  router ever needed to know about errors, the mechanism would be a
  dispatch table wearing a router's name.

  Isolation is per test by construction: the router is pure resolution
  plus one call, so a test's only state is the unit it builds. The one
  test that mutates a var (the hot-reload proof) restores it in a
  `finally`, and the one namespace load it causes
  (`seon.render-fixture`) is monotonic — the first run proves the load,
  a rerun in the same JVM proves only the call, and that is honest
  because nothing else in the tree requires that namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.render :as render]
            [seon.schema]))

;;; ---------------------------------------------------------------------------
;;; Projections this suite owns
;;; ---------------------------------------------------------------------------

(defn echo-kind
  "A projection: the unit's own `::marker`, so a test can tell WHICH
  unit reached WHICH projection."
  [unit]
  (str "echo:" (::marker unit)))

(defn other-projection
  [unit]
  (str "other:" (::marker unit)))

(defn reloaded-projection
  "The var the hot-reload proof re-roots. Its initial answer is
  `:first`; the test re-roots it to answer `:second` and asserts the
  NEXT render says so — a router that cached the resolved fn would
  still say `:first`, and the bug would look like a stale surface."
  [_unit]
  :first)

(defn throwing-projection
  [_unit]
  (throw (ex-info "the projection itself is broken" {::deliberate true})))

(defn render-html
  "The requesting namespace's HTML override for provenance tests."
  [_unit]
  [:p "namespace floor override"])

(defn- unit
  "A unit declaring `ai` and `log`, plus keys that are NOT declarations."
  []
  {::marker "u1"
   :seon.render/ai `echo-kind
   :seon.render/log `other-projection
   ;; a key in the render namespace whose value is not a qualified
   ;; symbol: presentation data, not a projection
   :seon.render/priority 3
   ;; ordinary payload the projections read
   :seon.error/message "not a projection key"})

(defn- request
  [unit kind]
  {:seon.render/unit unit :seon.render/kind kind})

;;; ---------------------------------------------------------------------------
;;; The kind set is computed
;;; ---------------------------------------------------------------------------

(deftest kinds-are-computed-from-the-unit
  (testing "every seon.render key holding a qualified symbol, and nothing else"
    (is (= #{:seon.render/ai :seon.render/log} (render/kinds (unit)))))
  (testing "a unit that declares nothing is an ordinary value, not an error"
    (is (= #{} (render/kinds {::marker "plain"}))))
  (testing "a new kind is accretion: add a key, and it is discoverable"
    (is (= #{:seon.render/ai :seon.render/log :seon.render/sms}
           (render/kinds (assoc (unit) :seon.render/sms `echo-kind)))))
  (testing "the router's own request keys can never be mistaken for kinds"
    (is (= #{} (render/kinds (request {::marker "u"} :seon.render/ai)))))
  (testing "a wrapped raw value and its unit declarations are both visible"
    (is (= #{:seon.render/ai :seon.render/log}
           (render/kinds
            {:seon.render/value {:seon.render/ai `echo-kind}
             :seon.render/log `other-projection})))))

;;; ---------------------------------------------------------------------------
;;; Resolution is late, var-backed, and loads the owner
;;; ---------------------------------------------------------------------------

(deftest render-resolves-the-symbol-and-applies-it-to-the-unit
  (let [rendered (render/render (request (unit) :seon.render/ai))]
    (is (= {:seon.render/kind :seon.render/ai
            :seon.render/output "echo:u1"}
           rendered))
    (is (seon.schema/valid-candidate-value? :seon.render/rendered rendered)))
  (testing "each kind reaches its own projection"
    (is (= "other:u1"
           (:seon.render/output
            (render/render (request (unit) :seon.render/log)))))))

(deftest render-loads-the-projections-owning-namespace
  ;; the fixture namespace is required by NOTHING (see its docstring),
  ;; so on a cold JVM this is a real proof that requiring-resolve loads
  ;; the owner rather than finding one somebody else had loaded
  (let [rendered (render/render
                  (request (assoc (unit) :seon.render/ai
                                  'seon.render-fixture/kinds-count)
                           :seon.render/ai))]
    (is (= "2" (:seon.render/output rendered)))
    (is (some? (find-ns 'seon.render-fixture)))))

(deftest render-is-var-backed-so-a-reload-takes-effect
  (let [original reloaded-projection]
    (try
      (is (= :first (:seon.render/output
                     (render/render (request (assoc (unit) :seon.render/ai
                                                    `reloaded-projection)
                                             :seon.render/ai)))))
      (alter-var-root #'reloaded-projection (constantly (fn [_] :second)))
      (is (= :second (:seon.render/output
                      (render/render (request (assoc (unit) :seon.render/ai
                                                     `reloaded-projection)
                                              :seon.render/ai))))
          "a cached resolution would still answer :first")
      (finally
        (alter-var-root #'reloaded-projection (constantly original))))))

;;; ---------------------------------------------------------------------------
;;; Totality: this router runs on the error path and may not fault into it
;;; ---------------------------------------------------------------------------

(deftest an-undeclared-kind-reaches-the-universal-structural-floor
  (let [rendered (render/render (request (unit) :seon.render/html))]
    (is (= :seon.render/html (:seon.render/kind rendered)))
    (is (vector? (:seon.render/output rendered)))
    (is (= :div (first (:seon.render/output rendered))))
    (is (= #{:seon.render/ai :seon.render/log}
           (render/kinds (unit)))
        "the universal floor is capability, not a repeated declaration")))

(deftest resolution-derives-whether-the-floor-branch-won
  (let [fallback (render/resolve-unit (request (unit) :seon.render/html))
        explicit (render/resolve-unit
                  (request (assoc (unit) :seon.render/html
                                  'seon.render.block/data-panel)
                           :seon.render/html))
        namespace-owned
        (render/resolve-unit
         (request (assoc (unit) :seon.render/namespace
                         'seon.render-test)
                  :seon.render/html))]
    (is (true? (:seon.render/would-fall-to-floor? fallback)))
    (is (= 'seon.render.block/data-panel (:seon.render/html fallback)))
    (is (false? (:seon.render/would-fall-to-floor? explicit))
        "provenance is the winning branch, not equality with its symbol")
    (is (false? (:seon.render/would-fall-to-floor? namespace-owned)))
    (is (= 'seon.render-test/render-html
           (:seon.render/html namespace-owned)))))

(deftest a-literal-declaration-is-its-own-output
  ;; The accretion this test's predecessor pinned the refusal for, in
  ;; the file its comment said it would change in first (N4 package 2).
  ;; A block that just says a fixed thing should not have to define a
  ;; function to say it.
  (testing "a verbatim string, for a prose kind"
    (is (= {:seon.render/kind :seon.render/ai
            :seon.render/output "a verbatim string"}
           (render/render (request (assoc (unit) :seon.render/ai
                                          "a verbatim string")
                                   :seon.render/ai)))))
  (testing "a literal vector, for a hiccup kind"
    (is (= {:seon.render/kind :seon.render/html
            :seon.render/output [:p "fixed"]}
           (render/render (request (assoc (unit) :seon.render/html
                                          [:p "fixed"])
                                   :seon.render/html)))))
  (testing "and a literal is discoverable as a kind like any declaration"
    (is (contains? (render/kinds (assoc (unit) :seon.render/html [:p "fixed"]))
                   :seon.render/html)))
  (testing "nothing to resolve means nothing that can throw"
    ;; the whole failure surface of a literal is empty, which is why it
    ;; needs no error branch of its own
    (is (nil? (:seon.error/kind
               (render/render (request (assoc (unit) :seon.render/ai "x")
                                       :seon.render/ai)))))))

(deftest data-on-a-render-key-stays-data
  ;; THE REASON THE ADMISSIBLE SHAPES ARE NARROW. `kinds` derives what a
  ;; unit can become from the unit itself, so every shape admitted as a
  ;; declaration turns some ordinary seon.render-namespaced value into a
  ;; kind. A number, keyword, map or set declares nothing.
  (doseq [value [3 :a-keyword {:a 1} #{:a} nil]]
    (is (not (render/declaration? value))
        (str "must stay data: " (pr-str value))))
  (is (= #{:seon.render/ai :seon.render/log} (render/kinds (unit)))
      ":seon.render/priority 3 is presentation data, not a projection"))

(deftest an-unresolvable-symbol-is-a-value-naming-the-symbol
  (let [refused (render/render (request (assoc (unit) :seon.render/ai
                                               'no.such.namespace/nope)
                                        :seon.render/ai))]
    (is (seon.schema/valid-candidate-value? :seon.error/value refused))
    (is (= :seon.render/unresolvable (:seon.error/kind refused)))
    (is (= 'no.such.namespace/nope
           (:seon.render/projection (:seon.error/data refused))))))

(deftest a-throwing-projection-is-a-value-naming-the-projection
  (let [refused (render/render (request (assoc (unit) :seon.render/ai
                                               `throwing-projection)
                                        :seon.render/ai))]
    (is (seon.schema/valid-candidate-value? :seon.error/value refused))
    (is (= :seon.render/projection-failed (:seon.error/kind refused)))
    (is (= `throwing-projection
           (:seon.render/projection (:seon.error/data refused)))
        "the projection is named, because the projection is what is broken")
    (is (= "clojure.lang.ExceptionInfo"
           (:seon.error/class (:seon.error/data refused))))))

(deftest the-router-never-throws-and-the-caller-lives
  ;; one property stated as one test, because "never throws" is the
  ;; reason this namespace exists rather than a nicety: seon.error's
  ;; notices route through here, so a throw would turn recording an
  ;; error into a second error. And per the owner's 2026-07-27 ruling —
  ;; fail loud, do NOT fall down — the caller must still be working
  ;; afterwards, which is the second half asserted below.
  (doseq [broken [(assoc (unit) :seon.render/ai `throwing-projection)
                  (assoc (unit) :seon.render/ai 'no.such.namespace/nope)
                  (dissoc (unit) :seon.render/ai)
                  {}]]
    (is (map? (render/render (request broken :seon.render/ai)))))
  (testing "a broken projection does not poison the next render"
    (render/render (request (assoc (unit) :seon.render/ai `throwing-projection)
                            :seon.render/ai))
    (is (= "echo:u1"
           (:seon.render/output (render/render (request (unit)
                                                        :seon.render/ai)))))))
