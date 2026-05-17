(ns seon.agents.alice
  "Alice — the V0 MVP agent. This is *agent-ns* for session \"seon\".

   Empty for now. The agent's defs land here when V0-B-8 lights up
   bootstrap-CLJS eval; until then their playground is reflected
   through the :seon.eval log rendered into ctx, not through forms
   actually interned in this namespace.

   The file exists so:
     (a) (binding [seon.agent/*agent-ns* 'seon.agents.alice] ...)
         resolves to a real namespace shadow-cljs has compiled,
     (b) when V0-B-8 lands, the bootstrap eval has a target ns to
         intern into without needing on-the-fly ns creation."
  (:require [seon.agent]))
