(ns seon.ctx.doc
  "Generic markdown-FILE context section — the one mechanism for turning
   an on-disk `.md` file into a renderable context section.

   `doc-section` takes a file PATH, a section NAME, and a PRIORITY and
   returns a renderable section map when the file currently exists, else
   `nil` (REACTIVE: an absent file is simply no section — there is NO
   fallback). It is GENERIC: it is not named soul, not tied to the LLM
   system message, and works for ANY markdown file. SOUL.md and AGENTS.md
   are wired into the context as two ordinary `doc-section`s
   (`seon.ctx/core-default-ctx`); a third party adds another file the
   same way.

   The file is read FRESH on every render (the path lives in the node,
   the fns re-read it) — a user's edit to the file lands on the next
   render with NO seed, NO restart, NO cache. The content is byte-stable
   BETWEEN renders (the file does not change mid-render), so the section
   keeps its place in the cacheable prefix; a save busts only this
   section's block (and below).

   Two views, the renderable contract:
     - `:seon.render/ai`   → the file as reader-valid `;;`-commented
       markdown (so the whole prompt stays valid Clojure source);
     - `:seon.render/html` → the markdown rendered (`seon.ui.markdown`)."
  (:require
    [clojure.string :as str]
    [seon.schema :as schema]
    [seon.ui.markdown :as md]))

;; The on-disk path a doc-section reads (relative to the pod's cwd =
;; repo root). Carried on the section node so the symbol-slot render fns
;; re-read it fresh each render.
(schema/register! :seon.ctx.doc/path [:string {:min 1}])

(defn- file-exists?
  "True when `path` (resolved against cwd) is a readable file. Never
   throws — a missing fs/file just answers false."
  [path]
  (try
    (let [fs (js/require "fs")]
      (.existsSync fs (str (.cwd js/process) "/" path)))
    (catch :default _ false)))

(defn- read-file-text
  "Live text of file `path` (resolved against cwd), or nil when
   unreadable (missing file). Never throws."
  [path]
  (try
    (let [fs (js/require "fs")]
      (.readFileSync fs (str (.cwd js/process) "/" path) "utf8"))
    (catch :default _ nil)))

(defn- comment-markdown
  "Markdown `text` as reader-valid Clojure: every line prefixed with
   `;; ` so the whole prompt remains valid source. Trailing whitespace
   trimmed; blank lines stay bare `;;` (no trailing space → byte-stable)."
  [text]
  (->> (str/split-lines (or text ""))
       (map (fn [line]
              (if (str/blank? line) ";;" (str ";; " line))))
       (str/join "\n")))

(defn doc-ai
  "The `:seon.render/ai` slot for a doc-section — the node's file read
   FRESH and `;;`-commented. Blank when the file vanished between wiring
   and render (the section then renders empty and is dropped upstream)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{{:seon.ctx.doc/keys [path]} :seon.render/node}]
  (let [text (read-file-text path)]
    (if (str/blank? text) "" (comment-markdown text))))

(defn doc-html
  "The `:seon.render/html` slot for a doc-section — the node's file read
   FRESH and rendered as markdown hiccup. Empty `[:div]` when the file
   vanished."
  {:malli/schema [:=> [:cat :map] :seon.render.live-tile/content]}
  [{{:seon.ctx.doc/keys [path]} :seon.render/node}]
  (md/md->hiccup (or (read-file-text path) "")))

;; The section-request / section shapes are fn-arg schemas inlined in the
;; :malli/schema below (NOT register!'d): `:seon.ctx/name` /
;; `:seon.ctx/priority` are registered in `seon.ctx`, which loads AFTER
;; this lower ns — so a register! here referencing them would trip the
;; load-order guard. Inline literal types keep doc self-contained.

(defn doc-section
  "A renderable context SECTION backed by the markdown file `path`, named
   `name`, ordered at `priority` — when the file currently exists; else
   `nil` (REACTIVE, NO fallback: absent file → no section).

   The returned section carries the path + a SYMBOL slot per view; the
   slot fns ([[doc-ai]] / [[doc-html]]) re-read the file fresh on every
   render so a user's edit lands next turn with no seed/restart. GENERIC:
   any markdown file is a section — SOUL.md and AGENTS.md are two
   `doc-section`s wired in `seon.ctx/core-default-ctx`, nothing
   soul-specific lives here."
  {:malli/schema [:=> [:cat [:map
                             [:seon.ctx.doc/path :seon.ctx.doc/path]
                             [:seon.ctx/name :keyword]
                             [:seon.ctx/priority :int]]]
                  [:maybe [:map
                           [:seon.ctx/name :keyword]
                           [:seon.ctx/priority :int]
                           [:seon.ctx.doc/path :seon.ctx.doc/path]
                           [:seon.render/ai :symbol]
                           [:seon.render/html :symbol]]]]}
  [{:seon.ctx.doc/keys [path] :seon.ctx/keys [name priority]}]
  (when (file-exists? path)
    {:seon.ctx/name      name
     :seon.ctx/priority  priority
     :seon.ctx.doc/path  path
     :seon.render/ai     'seon.ctx.doc/doc-ai
     :seon.render/html   'seon.ctx.doc/doc-html}))
