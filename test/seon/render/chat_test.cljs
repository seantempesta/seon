(ns seon.render.chat-test
  "Tests for the pure chat projection: message classification, markdown
   bubbles, ordered streams, and the system-message presentation."
  (:require
    [cljs.test :refer [deftest is]]
    [clojure.string :as str]
    [seon.render.chat :as chat]
    [seon.render.canvas :as canvas]
    [seon.ui.html :as html]))

;; ============================================================
;; message-kind — label classification.
;; ============================================================

(deftest message-kind-classifies-from-kind-labels
  (is (= ::chat/human (chat/message-kind "user")))
  (is (= ::chat/agent (chat/message-kind "assistant")))
  (is (= ::chat/peer  (chat/message-kind "agent-b2-000001"))
      "another agent's label is a peer")
  (is (= ::chat/peer  (chat/message-kind "→ agent-b2-000001"))
      "an OUTGOING peer send is also peer traffic — direction lives in the label")
  (is (= ::chat/peer  (chat/message-kind "unknown"))
      "unknown senders render subordinate, never as the human"))

;; ============================================================
;; bubble — one hiccup card per kind.
;; ============================================================

(defn- hiccup-strings [h] (filter string? (flatten h)))

(defn- hiccup-classes [h] (->> (flatten h) (filter map?) (keep :class)))

(def ^:private at (js/Date. 2026 5 11 14 30 0))

(deftest bubble-human-is-right-aligned-amber
  (let [h (chat/bubble {::chat/at at ::chat/kind ::chat/human
                        ::chat/label "user" ::chat/content "hello there"})]
    (is (canvas/valid-hiccup? h))
    (is (some #(re-find #"justify-end" %) (hiccup-classes h))
        "the human's words sit on the right")
    (is (some #(re-find #"amber" %) (hiccup-classes h)))
    (is (some #(= "hello there" %) (hiccup-strings h)))
    (is (some #(re-find #"14:30" %) (hiccup-strings h)) "timestamp renders")))

(deftest bubble-agent-renders-markdown-server-side
  (let [content (str "## status\n\n**bold** reply with `inline`\n\n"
                     "- item one\n- item two\n\n"
                     "1. first\n2. second\n\n"
                     "```clojure\n(+ 1 2)\n```\n\n"
                     "<script>alert(1)</script>")
        h (chat/bubble {::chat/at at ::chat/kind ::chat/agent
                        ::chat/label "assistant" ::chat/content content})
        s (html/->string h)]
    (is (canvas/valid-hiccup? h)
        "md->hiccup output satisfies the strict authoring shape")
    (is (some #(re-find #"justify-start" %) (hiccup-classes h)))
    (is (re-find #"<strong[^>]*>bold</strong>" s)
        "**bold** converts to <strong> SERVER-SIDE — curl sees it")
    (is (re-find #"<h2" s) "headings render")
    (is (re-find #"<ul" s) "bullet lists render")
    (is (re-find #"<ol" s) "numbered lists render")
    (is (re-find #"<code" s) "inline code renders")
    (is (re-find #"<pre" s) "code fences render")
    (is (not (re-find #"<script" s))
        "raw HTML NEVER passes through — agent content is untrusted")
    (is (re-find #"&lt;script&gt;alert\(1\)&lt;/script&gt;" s)
        "the script tag degrades to escaped visible text")))

(deftest bubble-human-renders-markdown-too
  ;; Symmetry (a21): the human's words get the same structure.
  (let [h (chat/bubble {::chat/at at ::chat/kind ::chat/human
                        ::chat/label "user"
                        ::chat/content "please **check** the list"})
        s (html/->string h)]
    (is (canvas/valid-hiccup? h))
    (is (re-find #"<strong[^>]*>check</strong>" s))
    (is (some #(re-find #"justify-end" %) (hiccup-classes h))
        "still the human's side")))

(deftest bubble-links-are-nofollow-and-scheme-guarded
  (let [safe   (html/->string
                 (chat/bubble {::chat/at at ::chat/kind ::chat/agent
                               ::chat/label "assistant"
                               ::chat/content "[docs](https://example.com/d)"}))
        unsafe (html/->string
                 (chat/bubble {::chat/at at ::chat/kind ::chat/agent
                               ::chat/label "assistant"
                               ::chat/content "[x](javascript:alert(1))"}))]
    (is (re-find #"<a [^>]*href=\"https://example.com/d\"" safe))
    (is (re-find #"rel=\"nofollow noopener\"" safe))
    (is (re-find #"target=\"_blank\"" safe))
    (is (not (re-find #"javascript:" unsafe))
        "javascript: hrefs never render — text-only degradation")))

(deftest bubble-peer-is-inline-dimmer-smaller-labeled
  (let [h (chat/bubble {::chat/at at ::chat/kind ::chat/peer
                        ::chat/label "agent-b2-000001"
                        ::chat/content "peer ping"})]
    (is (canvas/valid-hiccup? h))
    (is (some #(= "agent-b2-000001" %) (hiccup-strings h))
        "labeled with the peer's id")
    (is (some #(re-find #"text-xs" %) (hiccup-classes h)) "smaller")
    (is (some #(re-find #"text-text-400" %) (hiccup-classes h)) "dimmer")
    (is (not-any? #(re-find #"justify-end" %) (hiccup-classes h))
        "never on the human's side")))

;; ============================================================
;; bubble-stream — the column.
;; ============================================================

(deftest bubble-stream-orders-and-validates
  (let [{:seon.render/keys [hiccup]}
        (chat/bubble-stream
          {::chat/messages
           [{::chat/at at ::chat/kind ::chat/human
             ::chat/label "user" ::chat/content "first"}
            {::chat/at at ::chat/kind ::chat/agent
             ::chat/label "assistant" ::chat/content "second"}]})
        s (str/join " " (hiccup-strings hiccup))]
    (is (canvas/valid-hiccup? hiccup))
    (is (< (str/index-of s "first") (str/index-of s "second"))
        "stream preserves conversation order")))

(deftest bubble-stream-empty-renders-invitation
  (let [{:seon.render/keys [hiccup]}
        (chat/bubble-stream {::chat/messages []})]
    (is (some #(re-find #"no messages yet" %) (hiccup-strings hiccup))
        "an invitation, never a blank pane")))

;; System messages use the same pure bubble renderer.

(deftest bubble-system-is-centered-and-amber-edged
  (let [h (chat/bubble {::chat/at at ::chat/kind ::chat/system
                        ::chat/label "system"
                        ::chat/content "agent's turn failed: provider unreachable — it will resume on your next message"})]
    (is (canvas/valid-hiccup? h))
    (is (some #(re-find #"justify-center" %) (hiccup-classes h))
        "system lines sit centered — neither voice in the conversation")
    (is (some #(re-find #"amber" %) (hiccup-classes h)))
    (is (some #(re-find #"resume on your next message" %)
              (hiccup-strings h)))
    (is (some #(= "system" %) (hiccup-strings h)) "labeled")))
