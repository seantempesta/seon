#!/usr/bin/env python3
"""Regenerate tmp/mcp-entrance/cluster-mcp-region.patch from the current HEAD.

Stage B of the MCP entrance (lane mcp-entrance-opus): delete cluster.clj's MCP
projection region and every caller, once the bridge speaks only seon.mcp/tools
and seon.mcp/call. Cuts are by top-level form name, so the patch follows HEAD.
Run from the repository root: python3 tmp/mcp-entrance/build_patch_b.py
"""
import subprocess, sys

def head(path):
    return subprocess.run(['git', 'show', 'HEAD:' + path], capture_output=True, text=True, check=True).stdout

def cut(text, starts):
    """Remove each top-level form whose first line starts with one of `starts`
    (through the line before the next top-level form or ;;; comment)."""
    lines = text.split('\n')
    for start in starts:
        hits = [i for i, l in enumerate(lines) if l.startswith(start)]
        if len(hits) != 1:
            sys.exit(f'{start!r}: {len(hits)} matches')
        i = hits[0]
        j = i + 1
        while j < len(lines) and not (lines[j].startswith('(') or lines[j].startswith(';;')):
            j += 1
        lines = lines[:i] + lines[j:]
    return '\n'.join(lines)

def replace(text, old, new, path):
    if text.count(old) != 1:
        sys.exit(f'{path}: {old[:60]!r} matched {text.count(old)} times')
    return text.replace(old, new)

files = {}

# src/seon/cluster.clj: the projection ThreadLocal, the prepl mark, the MCP
# projection and blob paging; loaded-code identity and mcp-instance stay.
p = 'src/seon/cluster.clj'
t = head(p)
t = replace(t, """;;; ---------------------------------------------------------------------------
;;; MCP result projection — installed at the cluster io-prepl boundary.
;;; ---------------------------------------------------------------------------
""", """;;; ---------------------------------------------------------------------------
;;; Loaded-code identity: a host evaluation that changed it re-renders pages
;;; (`seon.mcp`); the cluster instance a runtime observation reads.
;;; ---------------------------------------------------------------------------
""", p)
t = cut(t, ['(defonce ^:private mcp-projection', '(defn project-next-prepl-value!', '(defn- consume-mcp-projection!',
            '(defn- mcp-effective', '(defn- mcp-plain', '(defn- mcp-project', '(defn mcp-valf', '(defn mcp-value',
            '(defn mcp-get-value'])
for r in ['            [seon.blob :as blob]\n', '            [seon.render :as render]\n',
          '            [seon.sci.admit :as admit]\n', '            [seon.render.walk :as render.walk]\n',
          '            [seon.print :as print]\n', '            [clojure.walk :as walk]\n']:
    t = replace(t, r, '', p)
files[p] = t

# resources/seon/operator/prepl.clj: canonical EDN by default; `evaluate` goes.
p = 'resources/seon/operator/prepl.clj'
t = head(p)
t = cut(t, ['(defn evaluate'])
t = replace(t, """      (let [value (:val event)
            project (or valf (when-let [project (some-> (find-ns 'seon.cluster) (ns-resolve 'mcp-valf))]
                               #(project cluster-name (deref (ns-resolve 'seon.config 'defaults)) %1 %2)))
            exit?""", """      (let [value (:val event)
            ;; canonical EDN (`seon.id/printed`) once Seon is loaded, so a
            ;; returned string reads back exactly; before that, Clojure's own
            printed (or (some-> (find-ns 'seon.id) (ns-resolve 'printed)) pr-str)
            exit?""", p)
t = replace(t, """                   (assoc event :val (cond project (project (or failure value) (true? (:exception event)))
                                           failure (pr-str (failure-text failure))
                                           :else (pr-str value)))""", """                   (assoc event :val (cond valf (valf (or failure value) (true? (:exception event)))
                                           failure (pr-str (failure-text failure))
                                           :else (printed value)))""", p)
t = replace(t, '  [event failure valf cluster-name]', '  [event failure valf]', p)
t = replace(t, '(reply event (when (and ret? (:exception event)) *e) valf cluster-name)',
            '(reply event (when (and ret? (:exception event)) *e) valf)', p)
t = replace(t, """  perform a requested process exit after the flush. The out-fn never throws:
  `valf` (value, exception?) -> string defaults to the cluster's projection."
  [& {:keys [cluster-name valf]}]""", """  perform a requested process exit after the flush. The out-fn never throws:
  `valf` (value, exception?) -> string, when given, prints each value; else a
  value prints as canonical EDN, so a string an entrance returns (`seon.mcp`)
  reads back exactly."
  [& {:keys [valf]}]""", p)
files[p] = t

# The prepl mark in the operator's request form and two measurement scripts.
p = 'script/seon/operator.clj'
files[p] = replace(head(p), """             (when-let [mark# (ns-resolve '~'seon.cluster '~'project-next-prepl-value!)]
               (mark# {:seon.dev.mcp/read-only? true :seon.dev.mcp/project? false}))
""", '', p)
for p in ['test/seon/cluster/reload_measure.clj', 'test/seon/dev/hook_measure.clj']:
    files[p] = replace(head(p), """                            (seon.cluster/project-next-prepl-value!
                             {:seon.dev.mcp/read-only? true :seon.dev.mcp/project? false})
""", '', p)

# Tests of deleted machinery; the page signal is seon.mcp-entrance-test's.
p = 'test/seon/render/web_context_test.clj'
files[p] = cut(head(p), ['(clojure.test/deftest read-only-mcp-return-preserves-root-page'])
p = 'test/seon/cluster/mcp_test.clj'
t = cut(head(p), ['(defn- projected', '(defn- utf8-size', '(defn- sci-evaluation', '(defn- observed-query-shape',
                  '(deftest mcp-config-reads-receive', '(deftest nested-bulk-is-bounded',
                  '(deftest sci-evaluations-project-the-repl', '(deftest sci-top-level-strings',
                  '(deftest ordinary-mcp-results-always', '(deftest sci-artifact-size-ignores',
                  '(defn- jvm-exceptions-keep', '(defn- a-cut-value-is-stored', '(defn- stored-strings-page',
                  '(defn- retrievable-artifacts-commit', '(defn- ordinary-value-artifacts-drill',
                  '(defn- a-value-shown-whole', '(deftest artifact-lifecycle-preserves',
                  '(deftest only-an-evaluation-that-changed'])
t = replace(t, '  "The MCP surface shares Seon\'s admitted print-node value chain."',
            '  "The runtime observation (`runtime_status`, `bin/seon status`) and the value\n  artifact marker."', p)
t = replace(t, '  (:require [clojure.core.async :as async]\n            [seon.ai.tokens :as tokens]\n'
                 '            [clojure.edn :as edn]\n            [clojure.string :as str]\n            [clojure.test',
            '  (:require [clojure.test', p)
for r in ['            [seon.print :as print]\n', '            [seon.db :as db]\n',
          '            [seon.render :as render]\n', '            [seon.sci.eval :as sci.eval]\n']:
    t = replace(t, r, '', p)
files[p] = t

import difflib, os
out = []
for p, new in files.items():
    old = head(p)
    out += difflib.unified_diff(old.splitlines(True), new.splitlines(True), 'a/' + p, 'b/' + p)
deleted = 'test/seon/mcp_test.clj'
old = head(deleted).splitlines(True)
out += [f'diff --git a/{deleted} b/{deleted}\n', 'deleted file mode 100644\n']
out += difflib.unified_diff(old, [], 'a/' + deleted, '/dev/null')
open('tmp/mcp-entrance/cluster-mcp-region.patch', 'w').write(''.join(out))
print('wrote tmp/mcp-entrance/cluster-mcp-region.patch')
