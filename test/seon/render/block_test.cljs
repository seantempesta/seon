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
    [seon.agent.ctx.render-fns]
    [seon.agent.run]
    [seon.config :as config]
    [seon.error :as error]
    [seon.render :as render]
    [seon.render.value :as rv]
    [seon.schema :as schema]
    [seon.ui.clojure :as cljhl]
    [seon.ui.html :as html]
    [seon.ui.markdown :as md]))

(defn- s [hiccup] (html/->string hiccup))
(def configuration (config/resolve-config-singleton {}))
(def render-request {})

(defn- flat-text [hiccup]
  (->> (flatten hiccup)
       (filter string?)
       (apply str)))

(defn custom-html
  [{:seon.render/keys [node schema-key]
    :seon.config/keys [configuration]
    :as request}]
  [:div {:data-schema (str schema-key)
         :data-agent (:seon.agent/id request)
         :data-config (boolean configuration)}
   (:demo/name node)])

(defn custom-ai [{:seon.render/keys [node]}]
  (str "custom " (:demo/name node)))

(defn throwing-custom [_]
  (throw (js/Error. "custom exploded")))

(defn- drilled-projection
  ([tree] (drilled-projection [] 0 false tree))
  ([path offset more? tree]
   {:seon.render.value/path path
    :seon.render.value/offset offset
    :seon.render.value/page-size 8
    :seon.render.value/summary "map"
    :seon.render.value/truncated? more?
    :seon.render.value/more? more?
    :seon.render.value/tree tree
    :seon.render.value/schemas []}))

(defn- value-request [route-base selector projection]
  {:seon.render/value-route-base route-base
   :seon.render/value-selector selector
   :seon.render/value-projection projection})

(defn- attrs-with [hiccup attr]
  (->> (tree-seq coll? seq hiccup)
       (filter vector?)
       (keep #(when (map? (second %)) (get (second %) attr)))))

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
    (let [out (s (render/block :html configuration render-request
                               {:seon.render/markdown "## Hi\n\n**bold**"}))]
      (is (str/includes? out "<h2"))
      (is (str/includes? out "<strong")))))

(deftest block-source-kind
  (testing "a :seon.render/source tag → clj->hiccup (highlighted)"
    (let [out (s (render/block :html configuration render-request
                               {:seon.render/source "(defn f [] :ok)"}))]
      (is (str/includes? out "language-clojure hljs"))
      (is (str/includes? out "hljs-keyword")))))

(deftest block-data-kind
  (testing "a render-html-data projection → the collapsible value panel"
    (let [proj (rv/render-html-data configuration "e1"
                                    {:a 1 :nested {:b [1 2 3]}})
          out  (s (render/block :html configuration render-request proj))]
      (is (str/includes? out "<details"))
      (is (str/includes? out "value-node"))
      (is (str/includes? out ":nested")))))

(deftest block-error-kind
  (testing "a :seon/error value → the error-card seam"
    (let [out (s (render/block :html configuration render-request
                               {:seon.error/message "kaboom"
                                :seon.error/where :probe}))]
      (is (str/includes? out "render error"))
      (is (str/includes? out "kaboom")))))

(deftest block-hiccup-passthrough
  (testing "a literal hiccup vector passes through unchanged"
    (let [h [:div {:class "x"} "literal"]]
      (is (= h (render/block :html configuration render-request h))))))

(deftest block-fallback-projects-anything
  (testing "an unknown raw value falls through to the data panel — never throws"
    (let [out (s (render/block :html configuration render-request
                               {:raw "value" :n 42 :list [9 8 7]}))]
      (is (str/includes? out "<details"))
      (is (str/includes? out ":raw")))
    (is (string? (s (render/block :html configuration render-request 42))))
    (is (string? (s (render/block :html configuration render-request "plain string"))))))

;; ============================================================
;; block — ai view returns prompt Strings.
;; ============================================================

(deftest block-ai-view-returns-strings
  (testing "every kind renders to a String for the agent prompt"
    (is (= "## hi"
           (render/block :ai configuration render-request {:seon.render/markdown "## hi"})))
    (is (= "(+ 1 2)"
           (render/block :ai configuration render-request {:seon.render/source "(+ 1 2)"})))
    (is (string?
          (render/block :ai configuration render-request
                        (rv/render-html-data configuration "e1"
                                             {:a (range 100)}))))
    (is (= "broke"
           (render/block :ai configuration render-request {:seon.error/message "broke"})))
    (is (string? (render/block :ai configuration render-request
                               [:div "literal " [:b "text"]])))
    (is (string? (render/block :ai configuration render-request {:k 1})))))

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
                  (fn [] (s (render/block :html configuration render-request
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
      (is (string? (s (render/block :html configuration render-request x)))
          (str "serialized: " (pr-str x))))))

(deftest value-request-is-closed-and-wins-marker-collisions
  (let [projection (drilled-projection {:demo/name "Ada"})
        valid (value-request "/agent/root/value"
                             {:seon.render/entity-id 42}
                             projection)
        malformed (assoc valid :seon.render.value/tree {})
        extra (assoc valid :demo/extra true)
        smuggled (assoc valid :seon.schema/projection {})]
    (is (schema/valid-candidate-value? :seon.render/value-request valid))
    (is (not (schema/valid-candidate-value? :seon.render/value-request extra)))
    (is (not (schema/valid-candidate-value? :seon.render/value-request smuggled)))
    (doseq [request [malformed extra smuggled]]
      (let [out (error/expecting-core-fault!
                  #(render/block :html configuration
                                 {:seon.agent/id "root"} request))]
        (is (str/includes? (s out) "Malformed value render request"))))))

(deftest value-panel-identity-is-logical-not-page-or-projection-identity
  (let [selector {:seon.render/eval-id "e-1"}
        request-a (value-request "/agent/a/value" selector
                                 (drilled-projection [:a] 0 true {:a 1}))
        request-b (value-request "/different/base" selector
                                 (drilled-projection [:a] 8 false {:a 999}))
        id-of (fn [agent request]
                (get-in (render/block :html configuration
                                      {:seon.agent/id agent} request)
                        [1 :id]))]
    (is (= (id-of "a" request-a) (id-of "a" request-b)))
    (is (= (id-of "a" request-a)
           (id-of "a" (into (array-map) (reverse (seq request-a))))))
    (is (not= (id-of "a" request-a) (id-of "b" request-a)))
    (is (not= (id-of "a" request-a)
              (id-of "a" (assoc request-a :seon.render/value-selector
                                 {:seon.render/eval-id "e-2"}))))
    (is (not= (id-of "a" request-a)
              (id-of "a" (assoc-in request-a
                                    [:seon.render/value-projection
                                     :seon.render.value/path]
                                    [:b]))))))

(deftest value-controls-serialize-one-inert-encoded-url
  (let [hostile-base "/agent/');globalThis.pwned=true;//value"
        hostile-key "quote' slash\\ unicode-λ & ="
        projection (drilled-projection [hostile-key] 0 true
                                       {:seon.render.value/pruned :map})
        request (value-request hostile-base
                               {:seon.render/eval-id "eval'&=λ"}
                               projection)
        hiccup (render/block :html configuration
                             {:seon.agent/id "agent"} request)
        expressions (vec (attrs-with hiccup (keyword "data-on:click")))]
    (is (= 2 (count expressions)) "inspect and next-page controls are bounded")
    (doseq [expression expressions]
      (is (str/starts-with? expression "@get(\""))
      (is (str/ends-with? expression "\")"))
      (let [url (js/JSON.parse (subs expression 5 (dec (count expression))))
            parsed (js/URL. url "http://seon.local")]
        (is (= hostile-base (.-pathname parsed)))
        (is (= "eval'&=λ" (.get (.-searchParams parsed) "eval")))
        (is (= (pr-str [hostile-key]) (.get (.-searchParams parsed) "path")))))))

(deftest projected-map-keys-and-their-descendants-have-no-controls
  (let [tree {:seon.render.value/map-entries
              [["safe" {:seon.render.value/pruned :map}]
               ["display-only" {:seon.render.value/pruned :map}]]
              :seon.render.value/non-drillable-key-indexes [1]}
        request (value-request "/agent/root/value"
                               {:seon.render/entity-id 7}
                               (drilled-projection tree))
        hiccup (render/block :html configuration
                             {:seon.agent/id "root"} request)]
    (is (= 1 (count (attrs-with hiccup (keyword "data-on:click"))))
        "only the retained ordinary key exposes a drill control")))

(deftest explicit-projection-dispatch-is-late-and-preserves-tagged-precedence
  (let [projection (schema/build-projection
                     {:demo/person
                      [:map {:seon.render/html 'seon.render.block-test/custom-html
                             :seon.render/ai 'seon.render.block-test/custom-ai}
                       [:demo/name :string]]
                      :demo/message
                      [:map {:seon.render/html 'seon.render.block-test/throwing-custom}
                       [:seon.render/markdown :string]]})
        request {:seon.agent/id "agent-a"
                 :seon.schema/projection projection}
        value {:demo/name "Ada"}]
    (is (str/includes?
          (s (render/block :html configuration request value)) "Ada"))
    (let [seen (atom nil)
          rendered (with-redefs [custom-html
                                 (fn [input]
                                   (reset! seen input)
                                   [:div "captured"])]
                     (render/block :html configuration request value))]
      (is (= (assoc request
                    :seon.config/configuration configuration
                    :seon.render/node value
                    :seon.render/schema-key :demo/person)
             @seen))
      (is (= "captured" (get rendered 1))))
    (let [rendered (render/block :html configuration request value)]
      (is (= ":demo/person" (get-in rendered [1 :data-schema])))
      (is (= "agent-a" (get-in rendered [1 :data-agent])))
      (is (true? (get-in rendered [1 :data-config]))))
    (is (= "custom Ada" (render/block :ai configuration request value)))
    (with-redefs [custom-html (fn [_] [:div "redefined"])]
      (is (str/includes?
            (s (render/block :html configuration request value)) "redefined")))
    (is (str/includes?
          (s (render/block :html configuration request
                           {:seon.render/markdown "**tagged**"}))
          "<strong"))))

(deftest explicit-override-short-circuits-schema-matching
  (let [value {:demo/name "Ada"
               :seon.render/html 'seon.render.block-test/custom-html}]
    (with-redefs [schema/matching-shapes-in
                  (fn [& _] (throw (js/Error. "matcher must not run")))]
      (is (str/includes?
            (s (render/block :html configuration
                             {:seon.schema/projection {}} value))
            "Ada")))))

(deftest invalid-candidate-and-no-match-use-generic-data-without-custom-dispatch
  (let [calls (atom 0)
        projection (schema/build-projection
                     {:demo/person
                      [:map {:seon.render/html 'seon.render.block-test/custom-html}
                       [:demo/name :string]]})
        generic (drilled-projection {:generic true})]
    (with-redefs [custom-html (fn [_] (swap! calls inc) [:div "wrong"])
                  rv/render-html-data (fn [& _] generic)]
      (doseq [value [{:demo/name 42} {:other/value true}]]
        (is (str/includes?
              (s (render/block :html configuration
                               {:seon.schema/projection projection} value))
              ":generic")))
      (is (zero? @calls)))))

(deftest existing-value-projection-never-reenters-custom-dispatch
  (let [projection (schema/build-projection
                     {:demo/projection
                      [:map {:seon.render/html 'seon.render.block-test/throwing-custom}
                       [:seon.render.value/tree :map]]})
        projected (drilled-projection {:already "data"})]
    (is (str/includes?
          (s (render/block :html configuration
                           {:seon.schema/projection projection} projected))
          "already"))))

(deftest schema-status-badges-and-invalid-explanation-are-deterministic
  (let [projection (assoc (drilled-projection {:demo/name 1})
                          :seon.render.value/schemas
                          [{:seon.schema/key :demo/valid
                            :seon.schema/entity? false
                            :seon.render.value/status :valid}
                           {:seon.schema/key :demo/invalid
                            :seon.schema/entity? true
                            :seon.render.value/status :invalid}
                           {:seon.schema/key :demo/shape
                            :seon.schema/entity? false
                            :seon.render.value/status :shape-only}]
                          :seon.render.value/explanation
                          {:seon.render.value/humanized {:demo/name ["must be string"]}
                           :seon.render.value/error-value {:demo/name 1}})
        request (value-request "/agent/root/value"
                               {:seon.render/entity-id 1} projection)
        rendered (render/block :html configuration
                               {:seon.agent/id "root"} request)
        out (s rendered)]
    (is (< (str/index-of out ":demo/valid")
           (str/index-of out ":demo/invalid")
           (str/index-of out ":demo/shape")))
    (is (str/includes? out "border-success"))
    (is (str/includes? out "border-error"))
    (is (str/includes? out "must be string"))))

(deftest next-page-control-stops-at-the-admitted-work-boundary
  (let [bounded-config (assoc configuration
                              :seon.config.render/value-max-realized-items 16)
        selector {:seon.render/entity-id 9}
        request-at-0 (value-request "/agent/root/value" selector
                                    (drilled-projection [] 0 true {}))
        request-at-8 (value-request "/agent/root/value" selector
                                    (drilled-projection [] 8 true {}))]
    (is (= 1 (count (attrs-with
                      (render/block :html bounded-config
                                    {:seon.agent/id "root"} request-at-0)
                      (keyword "data-on:click")))))
    (is (empty? (attrs-with
                  (render/block :html bounded-config
                                {:seon.agent/id "root"} request-at-8)
                  (keyword "data-on:click"))))))

(deftest missing-and-throwing-custom-renderers-stay-visible
  (doseq [[sym expected]
          [['seon.render.block-test/missing-custom "Missing custom renderer"]
           ['seon.render.block-test/throwing-custom "custom exploded"]]]
    (let [projection (schema/build-projection
                       {:demo/custom
                        [:map {:seon.render/html sym}
                         [:demo/name :string]]})
          out (error/expecting-core-fault!
                #(render/block :html configuration
                               {:seon.schema/projection projection}
                               {:demo/name "Ada"}))]
      (is (str/includes? (s out) expected)))))
