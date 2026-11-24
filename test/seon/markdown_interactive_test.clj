(ns seon.markdown-interactive-test
  "Comprehensive proofs for CommonMark/GFM parsing, backtick deep-linking, interactive links,
   diff/clojure/hiccup/seon-view live blocks, step-meta-row links, eval-snippet!, CSS, prompts,
   primers, and seed=42 generative checks."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [malli.generator :as mg]
            [seon.fork.calls :as fcalls]
            [seon.health.contracts :as hcontracts]
            [seon.model.messages :as messages]
            [seon.render :as render]
            [seon.render.markdown :as rmd]
            [seon.render.markdown.parse :as mdp]
            [seon.schema :as schema]
            [seon.web :as web]
            [seon.web.router :as router]
            [seon.web.styles :as styles]))

(deftest ^{:cost/ticks 60000000} gfm-blocks-tables-alerts-tasks-and-inline-test
  (testing "Headings h1..h6, hr, pipe tables with alignment, blockquotes, GitHub alerts, and task lists"
    (let [md (str "# Heading One\n\n"
                  "#### Heading Four\n\n"
                  "| Name | Count | Align |\n"
                  "|:---|---:|:---:|\n"
                  "| `a|b` | 42 | **ok** |\n\n"
                  "> Plain quote\n\n"
                  "> [!WARNING]\n"
                  "> Watch out for edge cases.\n\n"
                  "- [x] Done item\n"
                  "- [ ] Open item\n\n"
                  "3. Third\n"
                  "4. Fourth\n\n"
                  "---\n\n"
                  "Inline ~8000 tok, foo_bar_baz, ~~gone~~, ![ok](https://example.com/a.png), ![bad](javascript:alert(1)), <script>alert(1)</script>")
          html (render/hiccup->html (rmd/render-markdown-blocks md))]
      (is (str/includes? html "<h1 class=\"prose-h1\">Heading One</h1>"))
      (is (str/includes? html "<h4 class=\"prose-h4\">Heading Four</h4>"))
      (is (str/includes? html "<table class=\"md-table\">"))
      (is (str/includes? html "text-align:left"))
      (is (str/includes? html "text-align:right"))
      (is (str/includes? html "text-align:center"))
      (is (str/includes? html "a|b"))
      (is (str/includes? html "<blockquote class=\"md-quote\">"))
      (is (str/includes? html "class=\"md-alert md-alert-warning\""))
      (is (str/includes? html ">WARNING</div>"))
      (is (str/includes? html "prose-list has-tasks"))
      (is (str/includes? html "md-task-item is-done"))
      (is (str/includes? html "type=\"checkbox\""))
      (is (str/includes? html "start=\"3\""))
      (is (str/includes? html "<hr class=\"prose-hr\">"))
      (is (str/includes? html "~8000 tok"))
      (is (str/includes? html "foo_bar_baz"))
      (is (str/includes? html "<del>gone</del>"))
      (is (str/includes? html "src=\"https://example.com/a.png\""))
      (is (not (str/includes? html "javascript:alert")))
      (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
      (is (not (str/includes? html "<script>"))))))

(deftest ^{:cost/ticks 60000000} backtick-deep-linking-and-wildcard-safety-test
  (testing "Zero-syntax backtick deep-linking resolves vars, namespaces, tasks, rules, agents, schemas, docs, while preserving wildcards"
    (is (= [:seon.code.var/name (quote seon.web/navigate-url!)] (rmd/resolve-backtick-ref "seon.web/navigate-url!")))
    (is (= [:seon.code.ns/name (quote seon.web.router)] (rmd/resolve-backtick-ref "seon.web.router")))
    (is (= [:seon.task/id :seon.task/my-task] (rmd/resolve-backtick-ref ":seon.task/my-task")))
    (is (= [:seon.rule/id :rule.ctx/scope-and-extra-namespaces] (rmd/resolve-backtick-ref ":rule.ctx/scope-and-extra-namespaces")))
    (is (= [:seon.agent/id :agent/root] (rmd/resolve-backtick-ref ":agent/root")))
    (is (= [:seon.agent/id :ns/seon.web] (rmd/resolve-backtick-ref ":ns/seon.web")))
    (is (= [:seon.schema/key :seon.agent/id] (rmd/resolve-backtick-ref ":seon.agent/id")))
    (is (= [:seon.code.doc/id "docs/render.md#walk"] (rmd/resolve-backtick-ref "docs/render.md#walk")))
    (is (nil? (rmd/resolve-backtick-ref ":seon.summary/*")))
    (is (nil? (rmd/resolve-backtick-ref "c")))
    (let [doc-href (router/entity-ref->path nil [:seon.code.doc/id "docs/render.md#walk"])
          html (render/hiccup->html (rmd/render-inline-prose "See `seon.web/navigate-url!`, `seon.web.router`, `:seon.task/demo`, `docs/render.md#walk`, and `:seon.summary/*`."))]
      (is (str/includes? html "href=\"/var/seon.web/navigate-url!\""))
      (is (str/includes? html "href=\"/ns/seon.web.router\""))
      (is (str/includes? html "href=\"/task/seon.task/demo\""))
      (is (str/includes? html (str "href=\"" doc-href "\"")))
      (is (str/includes? html "interestfor=\"seon-hovercard\""))
      (is (str/includes? html "data-preview-ref="))
      (is (str/includes? html "inspect-entity%21"))
      (is (str/includes? html "<code class=\"inline-code\">:seon.summary/*</code>"))
      (is (not (str/includes? html "seon.summary/*\""))))
    (testing "Backtick code inside explicit markdown link never produces nested <a> tags"
      (let [h (render/hiccup->html (rmd/render-inline-prose "[`seon.web/web-proc`](/var/seon.web/web-proc)"))]
        (is (= 1 (count (re-seq #"<a " h))))))))

(deftest ^{:cost/ticks 60000000} interactive-markdown-links-and-xss-safety-test
  (testing "Internal Reitit links, external links, and dangerous scheme neutralization"
    (let [md (str "[web-proc](/var/seon.web/web-proc) "
                  "[docs](/doc/docs/render.md#walk) "
                  "[ext](https://example.org/docs) "
                  "[bad1](javascript:alert(1)) "
                  "[bad2](data:text/html,<script>alert(1)</script>) "
                  "[bad3](vbscript:msgbox(1))")
          html (render/hiccup->html (rmd/render-markdown-blocks md))]
      (is (str/includes? html "href=\"/var/seon.web/web-proc\""))
      (is (str/includes? html "class=\"ent-link md-link\""))
      (is (str/includes? html "navigate-url%21"))
      (is (str/includes? html "data-preview-ref=\"[:seon.code.var/name seon.web/web-proc]\""))
      (is (str/includes? html "interestfor=\"seon-hovercard\""))
      (is (str/includes? html "href=\"https://example.org/docs\""))
      (is (str/includes? html "target=\"_blank\""))
      (is (str/includes? html "rel=\"noopener noreferrer\""))
      (is (str/includes? html "class=\"md-ext-link\""))
      (is (str/includes? html "<span class=\"md-unsafe-link\">bad1</span>"))
      (is (str/includes? html "<span class=\"md-unsafe-link\">bad2</span>"))
      (is (str/includes? html "<span class=\"md-unsafe-link\">bad3</span>"))
      (is (not (str/includes? html "javascript:")))
      (is (not (str/includes? html "vbscript:")))
      (is (not (str/includes? html "data:text/html"))))))

(deftest ^{:cost/ticks 60000000} interactive-fenced-blocks-and-step-meta-links-test
  (testing "diff, clojure/repl, live sanitized hiccup, and seon-view fenced code blocks"
    (let [md (str "```diff\n@@ -1,2 +1,2 @@\n-old line\n+new line\n ctx line\n```\n\n"
                  "```clojure\n(+ 1 2)\n```\n\n"
                  "```repl\n(inc 41)\n```\n\n"
                  "```hiccup\n[:div {:class \"demo-card\" :onclick \"evil()\" :data-on:click \"evil()\"}\n"
                  " [:script \"alert(1)\"]\n"
                  " [:iframe {:src \"https://evil.test\"}]\n"
                  " [:a {:href \"javascript:alert(1)\"} \"bad\"]\n"
                  " [:a {:href \"https://example.com\"} \"ok\"]\n"
                  " [:svg {:viewBox \"0 0 20 20\" :width 20 :height 20} [:circle {:cx 10 :cy 10 :r 6 :fill \"#f0b429\"}]]\n"
                  " {:seon.render/raw \"<script>raw</script>\"}\n"
                  " [:p \"Live inline\"]]\n```\n\n"
                  "```seon-view\n{:var seon.render.status/render-status-card :data {:seon.ui.status/id :status/demo :seon.ui.status/agent :agent/root :seon.ui.status/label \"Building\" :seon.ui.status/pct 75 :seon.ui.status/stage :testing}}\n```\n\n"
                  "```seon-view\n{:ref [:seon.task/id :task/demo]}\n```\n\n"
                  "```seon-view\n{:var clojure.core/println :data {}}\n```")
          html (render/hiccup->html (rmd/render-markdown-blocks md))
          live-body (second (re-find #"<div class=\"md-live-body\">(.*?)</div><details" html))]
      (is (str/includes? html "diff-hunk"))
      (is (str/includes? html "diff-add"))
      (is (str/includes? html "diff-del"))
      (is (str/includes? html "diff-ctx"))
      (is (str/includes? html "Run in REPL"))
      (is (str/includes? html "eval-snippet%21"))
      (is (str/includes? html "md-live-card md-hiccup-card"))
      (is (str/includes? html "View Hiccup Source"))
      (is (some? live-body))
      (is (str/includes? live-body "Live inline"))
      (is (str/includes? live-body "<circle "))
      (is (not (str/includes? live-body "<script")))
      (is (not (str/includes? live-body "<iframe")))
      (is (not (str/includes? live-body "onclick=")))
      (is (not (str/includes? live-body "evil()")))
      (is (not (str/includes? live-body "javascript:")))
      (is (str/includes? html "md-live-card md-seon-view-card"))
      (is (str/includes? html "seon.render.status/render-status-card"))
      (is (str/includes? html "Building"))
      (is (str/includes? html "75%"))
      (is (str/includes? html "href=\"/task/task/demo\""))
      (is (str/includes? html "Renderer var must be in seon.render.*"))
      (is (empty? (mdp/parse-document "")))
      (is (= :unsafe (:kind (mdp/classify-url "java\tscript:alert(1)"))))
      (is (= [:div "safe"] (mdp/sanitize-hiccup [:div {:data-init "evil()"} [:script "x"] "safe"])))))
  (testing "step-meta-row renders real <a href> hovercard links instead of <button class=ent-link>"
    (let [eid (random-uuid)
          evs [{:seon.agent.eval/id eid :seon.agent.eval/handle (quote result/e7)
                :seon.agent.eval/result-schema :seon.health/report :seon.agent.eval/intent "Run check"
                :seon.agent.eval/code "(+ 1 2)" :seon.agent.eval/value "3" :seon.agent.eval/ms 1.0}]
          html (render/hiccup->html (rmd/render-activity-fold evs "sm"))]
      (is (not (str/includes? html "<button type=\"button\" class=\"ent-link")))
      (is (str/includes? html "href=\"/schema/seon.health/report\""))
      (is (str/includes? html "interestfor=\"seon-hovercard\""))
      (is (str/includes? html "data-preview-ref="))))
  (testing "styles/full-css includes rich markdown CSS rules"
    (let [css (styles/full-css)]
      (is (str/includes? css ".md-table{"))
      (is (str/includes? css ".md-alert{"))
      (is (str/includes? css ".diff-add"))
      (is (str/includes? css ".md-live-card"))
      (is (str/includes? css ".md-run-btn{"))
      (is (str/includes? css ".md-link{")))))

(deftest ^{:cost/ticks 60000000} eval-snippet-callable-prompt-and-primers-test
  (testing "seon.web/eval-snippet! is ^:seon/callable and dispatches :op :eval into :branch-feedback"
    (is (:seon/callable (meta (var web/eval-snippet!))))
    (let [res (web/invoke-call! {} {:call-form (list (quote seon.web/eval-snippet!) "(+ 1 2)")})
          req (get-in res [:async :req])
          fmt (get-in res [:async :format-fn])]
      (is (:ok? res))
      (is (= :eval (:op req)))
      (is (= "(+ 1 2)" (:code req)))
      (is (= :branch-feedback (get-in res [:async :slot])))
      (is (= {:ok? true :text "result/e1 => 3 | out: hi"}
             (fmt {:ok? true :handle (quote result/e1) :value "3" :out "hi\n"})))
      (is (false? (:ok? (fmt {:ok? false :error "boom"})))))
    (let [blank-res (web/invoke-call! {} {:call-form (list (quote seon.web/eval-snippet!) "   ")})]
      (is (:ok? blank-res))
      (is (nil? (:async blank-res)))
      (is (false? (get-in blank-res [:st :branch-feedback :ok?])))))
  (testing "default-system-prompt encourages expressive Markdown while staying slim"
    (let [p messages/default-system-prompt]
      (is (< (count p) 450))
      (is (< (count (str/split p #"\s+")) 75))
      (is (str/includes? p "Markdown"))
      (is (str/includes? p "hiccup"))
      (is (str/includes? p "NO tool calls"))
      (is (str/includes? p "<agent ...>...</agent>"))))
  (testing "docs/primers.md interactive markdown section has populated doc refs and zero broken refs"
    (when-let [db (when fcalls/*agent-ctx* @(:conn fcalls/*agent-ctx*))]
      (let [sec (d/pull db (quote [:seon.code.doc/id :seon.code.doc/body :seon.code.doc/references])
                        [:seon.code.doc/id "docs/primers.md#interactive-markdown-canvas"])]
        (is (= "docs/primers.md#interactive-markdown-canvas" (:seon.code.doc/id sec)))
        (is (str/includes? (str (:seon.code.doc/body sec)) "seon.render.markdown/render-markdown-blocks"))
        (is (str/includes? (str (:seon.code.doc/body sec)) "seon.web/eval-snippet!"))
        (is (>= (count (:seon.code.doc/references sec)) 3))
        (is (empty? (hcontracts/broken-doc-refs db)))))))

(deftest ^{:cost/ticks 120000000} seed42-generative-checks-test
  (testing "All single-arity pure parser/sanitizer functions pass 100-sample seed=42 schema/stress-var"
    (doseq [sym (quote [seon.render.markdown.parse/parse-inlines
                        seon.render.markdown.parse/parse-blocks
                        seon.render.markdown.parse/parse-document
                        seon.render.markdown.parse/classify-url
                        seon.render.markdown.parse/safe-read-edn
                        seon.render.markdown.parse/sanitize-hiccup])]
      (let [res (schema/stress-var nil sym {:samples 100 :seed 42})]
        (is (:ok? res) (str "Generative check failed for " sym ": " (pr-str res))))))
  (testing "All multi-arity interactive markdown renderers pass 100-sample seed=42 generative checks"
    (let [samples (mg/generate [:vector {:min 100 :max 100} [:maybe :string]] {:seed 42 :size 8})]
      (doseq [s samples]
        (let [r (rmd/resolve-backtick-ref s)
              inl (rmd/render-inline-prose s)
              blk (rmd/render-markdown-blocks s {})
              html (render/hiccup->html blk)]
          (is (or (nil? r) (and (vector? r) (= 2 (count r)))))
          (is (or (string? inl) (vector? inl)))
          (is (vector? blk))
          (is (string? html)))))))
