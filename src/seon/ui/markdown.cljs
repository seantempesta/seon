(ns seon.ui.markdown
  "Minimal markdown → hiccup renderer for chat messages.

   Tonight's demo needs LLM responses (which come back in markdown)
   to render as styled hiccup rather than raw text. The full
   markdown-clj library is JVM-only; rather than pull a CLJS-native
   markdown parser we hand-roll the small subset the agent actually
   emits in its replies:

     ## H2 headings
     ### H3 headings
     **bold**
     *italic* (single-asterisk; underscores not supported on purpose
              — they appear in file paths and keyword namespaces too
              often to safely treat as emphasis)
     `inline code`
     ```fenced code blocks```
     - bullet lists (single-level)
     1. ordered lists (single-level)
     paragraph breaks on blank lines
     [link text](url)

   Anything else falls through as plain text. Good enough for the
   chat-with-the-wiki demo; replace with a proper parser when this
   stops being good enough."
  (:require
    [clojure.string :as str]))

;; ============================================================
;; Inline span parsing — handles **bold**, *italic*, `code`, [link](url).
;; ============================================================

(def ^:private inline-pattern
  ;; Order matters — code first (its contents don't get parsed for
  ;; other inline markers), then links, then bold, then italic.
  #"(`([^`]+)`)|(\[([^\]]+)\]\(([^)]+)\))|(\*\*([^*]+)\*\*)|(\*([^*]+)\*)")

(defn- safe-link-url
  "`url` when its scheme is http(s)/mailto (or it is scheme-relative /
   path-relative), else nil — `javascript:`/`data:` and friends never
   become a clickable href. Agent-authored content is untrusted on the
   chat surface (chat-surface task #29)."
  [url]
  (when (or (re-matches #"(?i)^(https?:|mailto:)\S*" url)
            (re-matches #"^[/#.][^:\s]*" url))
    url))

(defn- inline->hiccup
  "Split a single line of text into hiccup spans for inline markdown.
   Returns a vector of strings / hiccup forms."
  [text]
  (let [acc      (atom [])
        last-end (atom 0)]
    (doseq [m (re-seq inline-pattern text)
            :let [[full
                   _code   code-body
                   _link   link-text link-url
                   _bold   bold-body
                   _ital   ital-body] m
                  start    (str/index-of text full @last-end)
                  end      (+ start (count full))]]
      (when (> start @last-end)
        (swap! acc conj (subs text @last-end start)))
      (cond
        code-body  (swap! acc conj
                          [:code {:class "px-1 py-0.5 rounded bg-base-800 text-warning text-xs font-mono"}
                           code-body])
        link-text  (swap! acc conj
                          (if-let [url (safe-link-url link-url)]
                            [:a {:href url
                                 :class "text-info underline hover:text-warning"
                                 :target "_blank"
                                 :rel "nofollow noopener"}
                             link-text]
                            ;; Unsafe scheme — degrade to the visible
                            ;; text, never a clickable href.
                            link-text))
        bold-body  (swap! acc conj [:strong {:class "text-text-100 font-bold"} bold-body])
        ital-body  (swap! acc conj [:em {:class "italic"} ital-body]))
      (reset! last-end end))
    (when (< @last-end (count text))
      (swap! acc conj (subs text @last-end)))
    (if (empty? @acc) [text] @acc)))

;; ============================================================
;; Block parsing — group lines into blocks (headings, paragraphs,
;; lists, code fences) then render each.
;; ============================================================

(defn- heading-level
  "Return [level rest-of-line] if line is an H1–H4 ATX heading."
  [line]
  (when-let [[_ hashes content] (re-matches #"^(#{1,4})\s+(.+)$" line)]
    [(count hashes) content]))

(defn- bullet-item [line]
  (second (re-matches #"^\s*[-*]\s+(.+)$" line)))

(defn- numbered-item [line]
  (second (re-matches #"^\s*\d+\.\s+(.+)$" line)))

(defn- render-block [block]
  (let [{:keys [kind lines]} block]
    (case kind
      :code-fence
      [:pre {:class (str "p-2 my-2 rounded bg-base-800 text-text-200 "
                         "text-xs font-mono overflow-x-auto whitespace-pre")}
       [:code (str/join "\n" lines)]]

      :heading
      (let [[level content] (heading-level (first lines))
            tag (case level 1 :h2 2 :h2 3 :h3 4 :h4)
            cls (case level
                  1 "text-base font-bold text-signal mt-2 mb-1"
                  2 "text-sm font-bold text-signal mt-2 mb-1"
                  3 "text-xs font-bold text-text-100 mt-1 mb-1 uppercase tracking-wide"
                  4 "text-xs font-semibold text-text-200 mt-1")]
        (into [tag {:class cls}] (inline->hiccup content)))

      ;; Lists splice their <li> children as VECTOR children (not one
      ;; lazy-seq child) so the whole tree satisfies the strict
      ;; authoring shape `seon.render.live-tile/valid-hiccup?`.
      :bullets
      (into [:ul {:class "list-disc pl-5 my-1 space-y-0.5"}]
            (for [ln lines]
              (into [:li {:class "text-text-100"}]
                    (inline->hiccup (bullet-item ln)))))

      :numbered
      (into [:ol {:class "list-decimal pl-5 my-1 space-y-0.5"}]
            (for [ln lines]
              (into [:li {:class "text-text-100"}]
                    (inline->hiccup (numbered-item ln)))))

      :paragraph
      (into [:p {:class "my-1 text-text-100 whitespace-pre-wrap"}]
            (inline->hiccup (str/join " " lines))))))

(defn- group-blocks
  "Walk `lines`, group into a sequence of `{:kind :lines}` blocks."
  [lines]
  (loop [lines  lines
         blocks []
         current nil]
    (if (empty? lines)
      (cond-> blocks current (conj current))
      (let [ln (first lines)
            rest-lines (rest lines)]
        (cond
          ;; Code fence — consume until closing ```
          (re-matches #"^```.*$" ln)
          (let [[body remaining] (split-with #(not (re-matches #"^```.*$" %)) rest-lines)]
            (recur (rest remaining)
                   (-> blocks
                       (cond-> current (conj current))
                       (conj {:kind :code-fence :lines (vec body)}))
                   nil))

          ;; Blank line — flush current
          (str/blank? ln)
          (recur rest-lines
                 (cond-> blocks current (conj current))
                 nil)

          ;; Heading — always its own block
          (heading-level ln)
          (recur rest-lines
                 (-> blocks (cond-> current (conj current))
                            (conj {:kind :heading :lines [ln]}))
                 nil)

          ;; Bullet
          (bullet-item ln)
          (if (= :bullets (:kind current))
            (recur rest-lines blocks (update current :lines conj ln))
            (recur rest-lines
                   (cond-> blocks current (conj current))
                   {:kind :bullets :lines [ln]}))

          ;; Numbered
          (numbered-item ln)
          (if (= :numbered (:kind current))
            (recur rest-lines blocks (update current :lines conj ln))
            (recur rest-lines
                   (cond-> blocks current (conj current))
                   {:kind :numbered :lines [ln]}))

          ;; Paragraph continuation
          :else
          (if (= :paragraph (:kind current))
            (recur rest-lines blocks (update current :lines conj ln))
            (recur rest-lines
                   (cond-> blocks current (conj current))
                   {:kind :paragraph :lines [ln]})))))))

;; ============================================================
;; Public — md->hiccup
;; ============================================================

(defn md->hiccup
  "Render `text` as a vector of hiccup blocks. Returns a `[:div ...]`
   wrapper so it drops into any parent layout. Pass `:wrap-class` to
   override the outer div's classes."
  {:malli/schema [:function
                  [:=> [:cat :any] :any]
                  [:=> [:cat :any :any] :any]]}
  ([text] (md->hiccup text nil))
  ([text {:keys [wrap-class] :or {wrap-class "text-xs"}}]
   (let [text   (or text "")
         lines  (str/split-lines text)
         blocks (group-blocks lines)]
     (into [:div {:class wrap-class}]
           (map render-block blocks)))))
