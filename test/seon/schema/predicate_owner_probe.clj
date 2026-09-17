(ns seon.schema.predicate-owner-probe
  "A loaded namespace whose predicate a regression can remove and restore.

   The publication wedge this probe reproduces needs a namespace that is
   ALREADY LOADED and whose loaded copy lacks a predicate its source
   declares — the state a long-lived JVM is in after a source edit adds one.
   `ns-unmap` produces exactly that state, and `require :reload` is the only
   thing that leaves it: a plain `require` on a loaded namespace is a no-op.

   It owns nothing global beyond its own private predicate, and a reload
   restores that predicate to the definition below, so the regression leaves
   the worker in the state it entered."
  (:require [seon.schema :as schema]))

(defn- probe-handle?
  [value]
  (= ::handle value))

(schema/register-core-predicate! 'seon.schema.predicate-owner-probe/probe-handle?
                                 probe-handle?)
