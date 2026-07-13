(ns seon.dev.markdown-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.dev.markdown :as md]))

;;; ---------------------------------------------------------------------------
;;; Parse Tests
;;; ---------------------------------------------------------------------------

(deftest parse-frontmatter-test
  (testing "parses YAML frontmatter"
    (let [result (md/parse {::md/content "---\ntype: component\nstatus: active\ntags: [database, schema]\n---\n# Title\n\nBody"})]
      (is (= {:type "component" :status "active" :tags "[database, schema]"}
             (::md/frontmatter result)))))

  (testing "no frontmatter returns nil"
    (let [result (md/parse {::md/content "# Title\n\nBody"})]
      (is (nil? (::md/frontmatter result))))))

(deftest parse-headings-test
  (testing "extracts ATX headings"
    (let [result (md/parse {::md/content "# H1\n\n## H2\n\n### H3"})]
      (is (= 3 (count (::md/headings result))))
      (is (= [1 2 3] (mapv ::md/level (::md/headings result))))
      (is (= ["H1" "H2" "H3"] (mapv ::md/text (::md/headings result))))))

  (testing "skips headings inside code blocks"
    (let [result (md/parse {::md/content "# Real\n\n```\n# Fake\n```\n\n## Also Real"})]
      (is (= 2 (count (::md/headings result))))
      (is (= ["Real" "Also Real"] (mapv ::md/text (::md/headings result)))))))

(deftest parse-links-test
  (testing "extracts wikilinks"
    (let [result (md/parse {::md/content "See [[target]] and [[other|display text]]"})]
      (is (= 2 (count (::md/links result))))
      (is (= :wikilink (::md/type (first (::md/links result)))))
      (is (= "target" (::md/target (first (::md/links result)))))
      (is (= "display text" (::md/text (second (::md/links result)))))))

  (testing "extracts markdown links"
    (let [result (md/parse {::md/content "[text](https://example.com)"})]
      (is (= 1 (count (::md/links result))))
      (is (= :markdown (::md/type (first (::md/links result)))))
      (is (= "https://example.com" (::md/target (first (::md/links result)))))))

  (testing "skips links inside code blocks"
    (let [result (md/parse {::md/content "```\n[[not-a-link]]\n```"})]
      (is (empty? (::md/links result))))))

(deftest parse-sections-test
  (testing "extracts sections between headings"
    (let [result (md/parse {::md/content "# First\n\nContent 1\n\n## Second\n\nContent 2"})]
      (is (= 2 (count (::md/sections result))))
      (is (= "First" (::md/text (::md/heading (first (::md/sections result)))))))))

;;; ---------------------------------------------------------------------------
;;; Validation Tests
;;; ---------------------------------------------------------------------------

(deftest validate-has-frontmatter-test
  (testing "flags missing frontmatter"
    (let [result (md/validate {::md/content "# Title\n\nBody\n"
                               ::md/rules #{:has-frontmatter}})]
      (is (not (::md/valid? result)))
      (is (= :has-frontmatter (::md/rule (first (::md/violations result)))))))

  (testing "passes with frontmatter"
    (let [result (md/validate {::md/content "---\ntype: component\nstatus: active\n---\n# Title\n\nBody\n"
                               ::md/rules #{:has-frontmatter}})]
      (is (::md/valid? result)))))

(deftest validate-required-fields-test
  (testing "flags missing type and status"
    (let [result (md/validate {::md/content "---\nfoo: bar\n---\n# Title\n"
                               ::md/rules #{:required-fields}})]
      (is (= 2 (count (::md/violations result))))))

  (testing "passes with both fields"
    (let [result (md/validate {::md/content "---\ntype: component\nstatus: active\n---\n# Title\n"
                               ::md/rules #{:required-fields}})]
      (is (::md/valid? result)))))

(deftest validate-heading-increment-test
  (testing "flags h1 to h3 jump"
    (let [result (md/validate {::md/content "# Title\n\n### Skipped\n"
                               ::md/rules #{:heading-increment}})]
      (is (not (::md/valid? result)))
      (is (= :heading-increment (::md/rule (first (::md/violations result)))))))

  (testing "passes with proper increment"
    (let [result (md/validate {::md/content "# Title\n\n## Section\n\n### Subsection\n"
                               ::md/rules #{:heading-increment}})]
      (is (::md/valid? result)))))

(deftest validate-single-h1-test
  (testing "flags multiple h1"
    (let [result (md/validate {::md/content "# First\n\n# Second\n"
                               ::md/rules #{:single-h1}})]
      (is (not (::md/valid? result)))))

  (testing "passes with single h1"
    (let [result (md/validate {::md/content "# Only One\n\n## Section\n"
                               ::md/rules #{:single-h1}})]
      (is (::md/valid? result)))))

(deftest validate-trailing-whitespace-test
  (testing "flags trailing whitespace"
    (let [result (md/validate {::md/content "hello   \nworld\n"
                               ::md/rules #{:trailing-whitespace}})]
      (is (not (::md/valid? result)))))

  (testing "allows 2-space line break"
    (let [result (md/validate {::md/content "hello  \nworld\n"
                               ::md/rules #{:trailing-whitespace}})]
      (is (::md/valid? result)))))

(deftest validate-no-multiple-blanks-test
  (testing "flags multiple blank lines"
    (let [result (md/validate {::md/content "hello\n\n\nworld\n"
                               ::md/rules #{:no-multiple-blanks}})]
      (is (not (::md/valid? result)))))

  (testing "allows single blank line"
    (let [result (md/validate {::md/content "hello\n\nworld\n"
                               ::md/rules #{:no-multiple-blanks}})]
      (is (::md/valid? result)))))

(deftest validate-valid-tags-test
  (testing "flags invalid tags"
    (let [result (md/validate {::md/content "---\ntype: component\nstatus: active\ntags: [database, invalid-tag]\n---\n# Title\n"
                               ::md/rules #{:valid-tags}})]
      (is (not (::md/valid? result)))
      (is (= :valid-tags (::md/rule (first (::md/violations result)))))))

  (testing "passes with valid tags"
    (let [result (md/validate {::md/content "---\ntype: component\nstatus: active\ntags: [database, schema]\n---\n# Title\n"
                               ::md/rules #{:valid-tags}})]
      (is (::md/valid? result)))))

(deftest validate-list-style-test
  (testing "flags asterisk lists"
    (let [result (md/validate {::md/content "* item one\n* item two\n"
                               ::md/rules #{:list-style}})]
      (is (not (::md/valid? result)))))

  (testing "passes with dash lists"
    (let [result (md/validate {::md/content "- item one\n- item two\n"
                               ::md/rules #{:list-style}})]
      (is (::md/valid? result)))))

(deftest validate-fenced-code-style-test
  (testing "flags tilde fences"
    (let [result (md/validate {::md/content "~~~\ncode\n~~~\n"
                               ::md/rules #{:fenced-code-style}})]
      (is (not (::md/valid? result)))))

  (testing "passes with backtick fences"
    (let [result (md/validate {::md/content "```\ncode\n```\n"
                               ::md/rules #{:fenced-code-style}})]
      (is (::md/valid? result)))))

(deftest validate-no-bare-urls-test
  (testing "flags bare URLs"
    (let [result (md/validate {::md/content "Visit https://example.com for more\n"
                               ::md/rules #{:no-bare-urls}})]
      (is (not (::md/valid? result)))))

  (testing "passes with markdown links"
    (let [result (md/validate {::md/content "Visit [example](https://example.com) for more\n"
                               ::md/rules #{:no-bare-urls}})]
      (is (::md/valid? result)))))

;;; ---------------------------------------------------------------------------
;;; Format Tests
;;; ---------------------------------------------------------------------------

(deftest format-violations-test
  (testing "formats violations by severity"
    (let [result (md/format-violations
                  {::md/violations [{::md/rule :has-frontmatter
                                     ::md/severity :error
                                     ::md/line 1
                                     ::md/message "Missing frontmatter"}
                                    {::md/rule :trailing-whitespace
                                     ::md/severity :warning
                                     ::md/line 5
                                     ::md/message "Trailing whitespace"}]})]
      (is (string? (::md/formatted result)))
      (is (re-find #"ERRORS" (::md/formatted result)))
      (is (re-find #"WARNINGS" (::md/formatted result)))))

  (testing "truncates long output"
    (let [many-violations (mapv (fn [i]
                                  {::md/rule :trailing-whitespace
                                   ::md/severity :warning
                                   ::md/line i
                                   ::md/message (str "Violation on line " i)})
                                (range 1 100))
          result (md/format-violations
                  {::md/violations many-violations
                   ::md/max-length 200})]
      (is (<= (count (::md/formatted result)) 200)))) ; must never exceed max-length

  (testing "truncation never exceeds max-length at the boundary (off-by-one regression)"
    ; The elision suffix "\n... (truncated)" is 16 chars. The old code
    ; subtracted a hardcoded 15, so truncated output overshot max-length by 1.
    ; Sweep a range of max-lengths around the boundary; every one must hold.
    (let [many-violations (mapv (fn [i]
                                  {::md/rule :trailing-whitespace
                                   ::md/severity :warning
                                   ::md/line i
                                   ::md/message (str "Violation on line " i)})
                                (range 1 200))]
      (doseq [max-len (range 20 300)]
        (let [out (::md/formatted (md/format-violations
                                   {::md/violations many-violations
                                    ::md/max-length max-len}))]
          (is (<= (count out) max-len)
              (str "output length " (count out) " exceeded max-length " max-len)))))))

;;; ---------------------------------------------------------------------------
;;; Fix Tests
;;; ---------------------------------------------------------------------------

(deftest fix-trailing-whitespace-test
  (testing "strips trailing whitespace"
    (let [result (md/fix {::md/content "hello   \nworld  \n"})]
      (is (not (re-find #"   \n" (::md/content result))))
      (is (pos? (::md/fixed-count result))))))

(deftest fix-multiple-blanks-test
  (testing "collapses multiple blank lines"
    (let [result (md/fix {::md/content "hello\n\n\n\nworld\n"})]
      (is (not (re-find #"\n{3,}" (::md/content result))))
      (is (pos? (::md/fixed-count result))))))

(deftest fix-trailing-newline-test
  (testing "adds trailing newline"
    (let [result (md/fix {::md/content "hello"})]
      (is (= "hello\n" (::md/content result)))
      (is (pos? (::md/fixed-count result)))))

  (testing "removes extra trailing newlines"
    (let [result (md/fix {::md/content "hello\n\n\n"})]
      (is (= "hello\n" (::md/content result))))))

(deftest fix-idempotent-test
  (testing "applying fix twice produces same result"
    (let [content "# Title\n\nGood content.\n"
          first-fix (md/fix {::md/content content})
          second-fix (md/fix {::md/content (::md/content first-fix)})]
      (is (= (::md/content first-fix) (::md/content second-fix)))
      (is (zero? (::md/fixed-count second-fix))))))

;;; ---------------------------------------------------------------------------
;;; Validate-File Tests
;;; ---------------------------------------------------------------------------

(deftest validate-file-nonexistent-test
  (testing "returns error for missing file"
    (let [result (md/validate-file {::md/file-path "/nonexistent/file.md"})]
      (is (not (::md/valid? result)))
      (is (= :file-not-found (::md/rule (first (::md/violations result))))))))

;;; ---------------------------------------------------------------------------
;;; Integration Tests
;;; ---------------------------------------------------------------------------

(deftest frontmatter-not-flagged-as-setext-test
  (testing "closing --- of frontmatter is not flagged as setext heading"
    (let [content "---\ntype: component\nstatus: active\ntags: [database]\n---\n\n# Title\n\nBody\n"
          result (md/validate {::md/content content
                               ::md/rules #{:heading-style}})]
      (is (::md/valid? result)
          (str "False positive: " (pr-str (::md/violations result))))))

  (testing "real setext heading IS flagged"
    (let [content "---\ntype: component\nstatus: active\n---\n\nTitle\n===\n\nBody\n"
          result (md/validate {::md/content content
                               ::md/rules #{:heading-style}})]
      (is (not (::md/valid? result)))
      (is (= :heading-style (::md/rule (first (::md/violations result)))))))

  (testing "blanks-around-headings not flagged for heading right after frontmatter"
    (let [content "---\ntype: component\nstatus: active\n---\n\n# Title\n\nBody\n"
          result (md/validate {::md/content content
                               ::md/rules #{:blanks-around-headings}})]
      (is (::md/valid? result)
          (str "False positive: " (pr-str (::md/violations result)))))))

(deftest full-document-test
  (testing "good document validates cleanly"
    (let [content (str "---\n"
                       "type: component\n"
                       "status: active\n"
                       "tags: [database, schema]\n"
                       "---\n"
                       "\n"
                       "# Title\n"
                       "\n"
                       "Some content with a [[wikilink]] and [markdown link](url).\n"
                       "\n"
                       "## Section\n"
                       "\n"
                       "- item one\n"
                       "- item two\n")
          result (md/validate {::md/content content
                               ::md/rules #{:has-frontmatter :required-fields
                                            :heading-style :heading-increment
                                            :single-h1 :blanks-around-headings
                                            :blanks-around-fences
                                            :trailing-whitespace :no-multiple-blanks
                                            :list-style :fenced-code-style}})]
      (is (::md/valid? result)
          (str "Violations: " (pr-str (::md/violations result)))))))
