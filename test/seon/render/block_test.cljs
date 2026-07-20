(ns seon.render.block-test
  "Behavioral tests for the typed-block keystone — `seon.render/block`
   (dispatch on value-kind) + `seon.ui.clojure/clj->hiccup` (the pure
   server-side highlighter).

   We pin MECHANISM, not exact strings (classes/markup will iterate): the
   right delegate fires per kind, malformed source degrades, and a throwing
   input yields an error card — never an exception."
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.config :as config]
    [seon.error :as error]
    [seon.render :as render]
    [seon.render.value :as rv]
    [seon.ui.clojure :as cljhl]
    [seon.ui.html :as html]
    [seon.ui.markdown :as md]))

(defn- s [hiccup] (html/->string hiccup))
(def configuration (config/resolve-config-singleton {}))

(defn- flat-text [hiccup]
  (->> (flatten hiccup)
       (filter string?)
       (apply str)))

(deftest html-marker-leaves-use-the-canonical-ai-token-bytes
  (let [datom {:seon.eval/datom [42 :demo/name "Jane"]}
        opaque {:seon.eval/opaque "datahike/DB"
                :seon.eval/summary "max-tx=9"}
        clipped {:seon.render.value/head "payload"
                 :seon.render.value/string-len 400}
        pruned {:seon.render.value/pruned :map
                :seon.render.value/count 7}]
    (doseq [[marker formatter]
            [[datom rv/datom-token]
             [opaque rv/opaque-token]
             [clipped rv/clipped-string-token]]]
      (is (= (formatter marker)
             (flat-text (@#'render/value-leaf marker)))))
    (is (= (rv/pruned-token pruned)
           (flat-text (get (@#'render/pruned-marker pruned) 2))))
    (is (not (str/includes? (rv/clipped-string-token clipped) " ⟨"))
        "the shared token has no HTML-only leading space")))

;; `block-throwing-delegate-yields-error-card` asserts the graceful PROD
;; fallback (throw → error card, never an exception). Under the harness
;; strict default (SEON_RENDER_STRICT=1) that render THROWS by design, so
;; force the fail-loud dial OFF for this ns (process-global env, async-safe).
;; Restore the CALLER's value, not a hardcoded "1" — an isolated bare-node run
;; (env unset) must not leave the dial flipped ON for whatever runs next.
(defonce ^:private prior-strict-env
  (atom nil))

(t/use-fixtures :once
  {:before (fn []
             (reset! prior-strict-env
                     (.. js/globalThis -process -env -SEON_RENDER_STRICT))
             (set! (.. js/globalThis -process -env -SEON_RENDER_STRICT) "0"))
   :after  (fn []
             (set! (.. js/globalThis -process -env -SEON_RENDER_STRICT)
                   (or @prior-strict-env "")))})

;; ============================================================
;; clj->hiccup — the server-side Clojure highlighter.
;; ============================================================

(deftest highlighter-emits-hljs-spans
  (testing "well-formed source tokenizes to the shared .hljs-* palette"
    (let [out (s (cljhl/clj->hiccup "(defn f [x]\n  ;; note\n  (* x 2 :kw nil \"str\"))"))]
      (is (str/includes? out "language-clojure hljs") "the code class is the eval-card shape")
      (is (str/includes? out "hljs-keyword")  "def-form → keyword")
      (is (str/includes? out "hljs-comment")  "; comment")
      (is (str/includes? out "hljs-number")   "number")
      (is (str/includes? out "hljs-symbol")   ":keyword → symbol")
      (is (str/includes? out "hljs-literal")  "nil → literal")
      (is (str/includes? out "hljs-string")   "string"))))

(deftest highlighter-degrades-on-malformed-source
  (testing "partial / malformed source never throws and keeps the text"
    (doseq [src ["(foo } ]] :bad \"unterminated"
                 "}"
                 "(defn"
                 "\\( \\newline"
                 ""]]
      (let [h   (cljhl/clj->hiccup src)
            out (s h)]                       ; serializes without throwing
        (is (vector? h) (str "still hiccup for: " (pr-str src)))
        (is (str/includes? out "language-clojure hljs"))))))

(deftest highlighter-preserves-text
  (testing "every source char survives into the rendered text (escaped)"
    (let [out (s (cljhl/clj->hiccup "(map inc coll)"))]
      (is (str/includes? out "map"))
      (is (str/includes? out "inc"))
      (is (str/includes? out "coll")))))

;; ============================================================
;; block — dispatch on value-kind (html view).
;; ============================================================

(deftest block-message-kind
  (testing "a :seon.render/markdown tag → md->hiccup"
    (let [out (s (render/block :html configuration
                               {:seon.render/markdown "## Hi\n\n**bold**"}))]
      (is (str/includes? out "<h2"))
      (is (str/includes? out "<strong")))))

(deftest block-source-kind
  (testing "a :seon.render/source tag → clj->hiccup (highlighted)"
    (let [out (s (render/block :html configuration
                               {:seon.render/source "(defn f [] :ok)"}))]
      (is (str/includes? out "language-clojure hljs"))
      (is (str/includes? out "hljs-keyword")))))

(deftest block-data-kind
  (testing "a render-html-data projection → the collapsible value panel"
    (let [proj (rv/render-html-data configuration "e1"
                                    {:a 1 :nested {:b [1 2 3]}})
          out  (s (render/block :html configuration proj))]
      (is (str/includes? out "<details"))
      (is (str/includes? out "value-node"))
      (is (str/includes? out ":nested")))))

(deftest block-error-kind
  (testing "a :seon/error value → the error-card seam"
    (let [out (s (render/block :html configuration
                               {:seon.error/message "kaboom"
                                :seon.error/where :probe}))]
      (is (str/includes? out "render error"))
      (is (str/includes? out "kaboom")))))

(deftest block-hiccup-passthrough
  (testing "a literal hiccup vector passes through unchanged"
    (let [h [:div {:class "x"} "literal"]]
      (is (= h (render/block :html configuration h))))))

(deftest block-fallback-projects-anything
  (testing "an unknown raw value falls through to the data panel — never throws"
    (let [out (s (render/block :html configuration
                               {:raw "value" :n 42 :list [9 8 7]}))]
      (is (str/includes? out "<details"))
      (is (str/includes? out ":raw")))
    (is (string? (s (render/block :html configuration 42))))
    (is (string? (s (render/block :html configuration "plain string"))))))

;; ============================================================
;; block — ai view returns prompt Strings.
;; ============================================================

(deftest block-ai-view-returns-strings
  (testing "every kind renders to a String for the agent prompt"
    (is (= "## hi"
           (render/block :ai configuration {:seon.render/markdown "## hi"})))
    (is (= "(+ 1 2)"
           (render/block :ai configuration {:seon.render/source "(+ 1 2)"})))
    (is (string?
          (render/block :ai configuration
                        (rv/render-html-data configuration "e1"
                                             {:a (range 100)}))))
    (is (= "broke"
           (render/block :ai configuration {:seon.error/message "broke"})))
    (is (string? (render/block :ai configuration
                               [:div "literal " [:b "text"]])))
    (is (string? (render/block :ai configuration {:k 1})))))

;; ============================================================
;; block — the never-throw guard (generalizes render-entity-html).
;; ============================================================

(deftest block-throwing-delegate-yields-error-card
  (testing "a throwing delegate becomes an error card (html) / error text (ai), not an exception"
    ;; A proper MULTI-ARITY throwing fn: the render path calls md->hiccup via
    ;; its compiled arity-1 method, so a variadic-only (fn [& _]) fails with
    ;; "arity$1 is not a function" (a DIFFERENT error than the intended "boom").
    ;; Declaring both arities makes the fixture provoke the real throw.
    (with-redefs [md/md->hiccup (fn ([_]   (throw (js/Error. "boom")))
                                  ([_ _] (throw (js/Error. "boom"))))]
      ;; The throwing delegate is a :core fault (md is seon.*); deliberate here,
      ;; so bracket it EXPECTED (render/block guards+records synchronously).
      (let [out (error/expecting-core-fault!
                  (fn [] (s (render/block :html configuration
                                          {:seon.render/markdown "hi"}))))]
        (is (str/includes? out "render error"))
        (is (str/includes? out "block render failed"))))))

(deftest block-serializes-without-throwing
  (testing "every kind's html output serializes cleanly"
    (doseq [x [{:seon.render/markdown "# h"}
               {:seon.render/source "(inc 1)"}
               (rv/render-html-data configuration "e" {:a 1})
               {:seon.error/message "e"}
               [:div "h"]
               {:unknown true}]]
      (is (string? (s (render/block :html configuration x)))
          (str "serialized: " (pr-str x))))))
