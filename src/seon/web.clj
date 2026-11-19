(ns seon.web
  "The `:web` Flow proc (`:io` workload) — 100% Inside-Out, Non-Blocking HTTP/SSE Edge +
   120fps Incremental `datahike.reads` Walk + Live MCP:
   - ZERO ephemeral UI navigation state in `web-proc`: where the user is (`:seon.user/viewing`,
     `:seon.user/view-mode`, `:seon.user/sidebar-open?`, `:seon.user/expanded-agents`,
     `:seon.user/selected-branch`) lives exclusively in Datahike (`:user/default`).
   - Restoring LMDB restores the user's exact view; any agent can move the user's screen via
     `(user/show-user! ...)` — `:web` reacts via `datahike.reads` and streams SSE patches at 120 Hz.
   - ZERO `<!!`, `>!!`, `alts!!`, `Thread/sleep`, or `flow/ping` inside `web-proc` (`seon.health` verified).
   - Serves live MCP JSON-RPC 2.0 at `POST /mcp` (`seon_repl`, `seon_health`, `seon_test`, `seon_merge`, `seon_status`)."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [ring-chez.adapter :as adapter]
            [seon.render :as render]
            [seon.render.ui :as ui]
            [seon.store :as store]))

;; --- Seon Phosphor Theme (OS Light/Dark Native, Warm Obsidian / Warm Parchment, Amber Signal) ---

(def ^:private app-css
  ":root{
     color-scheme:dark light;
     --font-mono:'JetBrains Mono','SF Mono',ui-monospace,Menlo,Monaco,monospace;
     --font-text:ui-sans-serif,system-ui,-apple-system,'Segoe UI',sans-serif;
     --base-950:#0d0d0c;--base-900:#1a1918;--base-850:#252422;--base-800:#302e2b;--base-700:#3d3a36;
     --text-50:#faf9f7;--text-100:#ece9e3;--text-200:#d4d0c8;--text-300:#b0aa9e;--text-400:#8a8478;--text-500:#6b665d;
     --signal:#f0b429;--signal-fg:#0d0d0c;--success:#34d399;--error:#f87171;--warning:#fbbf24;--info:#60a5fa;--eval:#c084fc;
   }
   @media(prefers-color-scheme:light){
     :root:not(:has([data-theme='dark'])){
       --base-950:#f7f4ee;--base-900:#fffdf9;--base-850:#f1ece3;--base-800:#e4ddd1;--base-700:#d3cabb;
       --text-50:#17140f;--text-100:#221e18;--text-200:#3a352d;--text-300:#5c564b;--text-400:#7d766a;--text-500:#a29a8c;
       --signal:#b7791f;--signal-fg:#fffdf9;--success:#0f8a5f;--error:#c53030;--warning:#b7791f;--info:#2563eb;--eval:#7c3aed;
     }
   }
   :root:has([data-theme='light']){
     color-scheme:light;
     --base-950:#f7f4ee;--base-900:#fffdf9;--base-850:#f1ece3;--base-800:#e4ddd1;--base-700:#d3cabb;
     --text-50:#17140f;--text-100:#221e18;--text-200:#3a352d;--text-300:#5c564b;--text-400:#7d766a;--text-500:#a29a8c;
     --signal:#b7791f;--signal-fg:#fffdf9;--success:#0f8a5f;--error:#c53030;--warning:#b7791f;--info:#2563eb;--eval:#7c3aed;
   }
   :root:has([data-theme='dark']){color-scheme:dark}
   @view-transition{navigation:auto}
   @media(prefers-reduced-motion:reduce){@view-transition{navigation:none}}
   *{box-sizing:border-box}
   html,body{margin:0;background:var(--base-950);color:var(--text-200);font-family:var(--font-text);font-size:14.5px;line-height:1.6;-webkit-font-smoothing:antialiased;min-height:100vh}
   a{color:inherit;text-decoration:none}
   .mono,pre,code{font-family:var(--font-mono)}
   .muted{color:var(--text-400)} .tiny{font-size:11.5px} .strong{font-weight:600} .sm-copy{font-size:13.5px;line-height:1.58;margin:4px 0;color:var(--text-200)}
   .kicker{font-family:var(--font-mono);text-transform:uppercase;letter-spacing:.14em;font-size:10.5px;color:var(--text-400);font-weight:500}
   .truncate{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:480px}
   .flex-1{flex:1;min-width:140px}

   /* Quiet Phosphor Top Bar (Always 1 Clean Row, Even at Half-Screen!) */
   .topbar{view-transition-name:seon-bar;background:var(--base-950);border-bottom:1px solid var(--base-800);padding:0 16px;height:48px;display:flex;align-items:center;position:sticky;top:0;z-index:30;flex-shrink:0}
   .brand-row{display:flex;justify-content:space-between;align-items:center;gap:10px;width:100%;min-width:0;flex-wrap:nowrap}
   .brand{display:flex;align-items:center;gap:8px;min-width:0;flex:1;overflow:hidden}
   .icon-btn{background:transparent;border:1px solid var(--base-800);color:var(--text-300);width:28px;height:28px;border-radius:6px;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;flex-shrink:0;padding:0;transition:border-color .15s,color .15s,background .15s}
   .icon-btn:hover,.icon-btn.active{border-color:var(--signal);color:var(--signal);background:rgba(240,180,41,.07)}
   .brand-link{background:none;border:none;padding:0;cursor:pointer;display:inline-flex;align-items:baseline;gap:5px;color:var(--text-50);flex-shrink:0}
   .brand-link:hover{color:var(--signal)}
   .diamond{color:var(--signal);font-size:12px}
   .wordmark{font-family:var(--font-mono);font-size:13.5px;font-weight:700;letter-spacing:.04em}
   .crumb-sep{color:var(--base-700);font-family:var(--font-mono);font-size:12px;flex-shrink:0}
   .crumb{font-family:var(--font-mono);color:var(--text-300);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0}
   .dot{width:7px;height:7px;border-radius:50%;display:inline-block;flex-shrink:0}
   .dot.live{background:var(--success)}
   .dot.warn{background:var(--signal)}
   .dot.bad{background:var(--error)}
   .dot.pulsing{background:var(--signal);box-shadow:0 0 0 0 rgba(240,180,41,.55);animation:seon-pulse 1.6s ease-out infinite}
   @keyframes seon-pulse{0%{box-shadow:0 0 0 0 rgba(240,180,41,.55)}70%{box-shadow:0 0 0 7px rgba(240,180,41,0)}100%{box-shadow:0 0 0 0 rgba(240,180,41,0)}}
   .live-pill{display:inline-flex;align-items:center;gap:5px;padding:2px 8px;border-radius:999px;border:1px solid rgba(240,180,41,.38);background:rgba(240,180,41,.08);color:var(--signal);font-family:var(--font-mono);font-size:10.5px;flex-shrink:0}
   .stats-strip{display:flex;align-items:center;gap:8px;flex-shrink:0}
   .status-pill{display:inline-flex;align-items:center;gap:6px;background:transparent;border:1px solid var(--base-800);color:var(--text-300);height:28px;padding:0 10px;border-radius:999px;font-family:var(--font-mono);font-size:11px;cursor:pointer;flex-shrink:0}
   .status-pill.ok:hover{border-color:var(--signal);color:var(--text-50)}
   .status-pill.bad{border-color:var(--error);color:var(--error)}
   .mode-group{display:inline-flex;align-items:center;gap:2px;background:var(--base-900);border:1px solid var(--base-800);border-radius:7px;padding:2px;flex-shrink:0}
   .nav-pill{background:transparent;border:none;color:var(--text-400);padding:4px 10px;border-radius:5px;font-family:var(--font-mono);font-size:11.5px;cursor:pointer;white-space:nowrap}
   .nav-pill:hover{color:var(--text-100)}
   .nav-pill.active{background:var(--base-800);color:var(--text-50);font-weight:600}

   /* 100vh Flex App Shell & Collapsible Left Sidebar (Floating Drawer at Half-Screen <= 960px!) */
   html,body{height:100%;overflow:hidden}
   .app-shell{height:100vh;display:flex;flex-direction:column;overflow:hidden}
   .app-body{flex:1;min-height:0;display:flex;overflow:hidden;position:relative}
   .sidebar.closed{display:none}
   .sidebar.open{width:248px;flex-shrink:0;background:var(--base-900);border-right:1px solid var(--base-800);display:flex;flex-direction:column;overflow:hidden}
   .sidebar-inner{display:flex;flex-direction:column;height:100%;padding:12px 10px;gap:10px;overflow:hidden}
   .sidebar-head{display:flex;justify-content:space-between;align-items:center;padding:2px 6px 8px;border-bottom:1px solid var(--base-800)}
   .tree-scroll{flex:1;min-height:0;overflow-y:auto;display:flex;flex-direction:column;gap:2px}
   .tree-row{display:flex;align-items:center;gap:4px;border-radius:6px;padding:5px 6px}
   .tree-row:hover{background:var(--base-850)}
   .tree-row.active{background:var(--base-800);color:var(--text-50)}
   .tree-exp{background:none;border:none;color:var(--text-400);cursor:pointer;width:16px;padding:0;font-family:var(--font-mono);font-size:11px;flex-shrink:0}
   .tree-exp:hover{color:var(--signal)}
   .tree-exp-spacer{width:16px;text-align:center;color:var(--text-500);font-size:10px;flex-shrink:0}
   .tree-select{flex:1;min-width:0;background:none;border:none;color:inherit;cursor:pointer;display:flex;align-items:center;gap:8px;text-align:left;padding:2px 0}
   .tree-name{font-size:12.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
   .tree-role{font-family:var(--font-mono);font-size:10px;margin-left:auto}
   .sidebar-spawn{border-top:1px solid var(--base-800);padding-top:10px}
   .sidebar-spawn summary{cursor:pointer;list-style:none;padding:4px 6px;border-radius:5px;color:var(--text-300)}
   .sidebar-spawn summary:hover{background:var(--base-850);color:var(--text-50)}
   .sidebar-spawn summary::-webkit-details-marker{display:none}

   /* Canvas Interactive Datahike History Scrubber Bar (Real SVG Left/Right + Clickable Timeline Pips) */
   .canvas-timeline-bar{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:6px 10px;border-radius:9px;background:var(--base-900);border:1px solid var(--base-800)}
   .canvas-timeline-bar.historical{border-color:rgba(240,180,41,.45);background:rgba(240,180,41,.06)}
   .tl-nav{display:inline-flex;align-items:center;gap:4px;flex-shrink:0}
   .tl-btn{width:28px;height:26px;border-radius:6px;border:1px solid var(--base-800);background:var(--base-950);color:var(--text-200);cursor:pointer;display:inline-flex;align-items:center;justify-content:center;padding:0;transition:border-color .15s,color .15s,background .15s}
   .tl-btn:hover:not(:disabled){border-color:var(--signal);color:var(--signal);background:rgba(240,180,41,.08)}
   .tl-btn:disabled{opacity:.3;cursor:default}
   .tl-track{display:flex;align-items:center;justify-content:center;gap:6px;flex:1;min-width:0;overflow:hidden;padding:0 6px}
   .tl-pip{width:7px;height:7px;border-radius:999px;border:none;padding:0;background:var(--base-700);cursor:pointer;transition:width .18s ease,background .15s ease,transform .15s ease;flex-shrink:0}
   .tl-pip:hover{background:var(--text-300);transform:scale(1.25)}
   .tl-pip.past{background:var(--text-500)}
   .tl-pip.active{width:20px;background:var(--signal);transform:none}
   .tl-meta{display:inline-flex;align-items:center;gap:8px;flex-shrink:0;font-family:var(--font-mono);font-size:11px;color:var(--text-400)}
   .step-live{background:var(--signal);color:var(--signal-fg);border:none;border-radius:5px;padding:3px 9px;font-family:var(--font-mono);font-size:10.5px;font-weight:600;cursor:pointer}
   .step-live:hover{opacity:.9}

   /* Main Column: Scrollable Stage + Non-Overlapping Fixed Bottom Dock */
   .main-col{flex:1;min-width:0;display:flex;flex-direction:column;overflow:hidden}
   .stage-scroll{flex:1;min-height:0;overflow-y:auto;overflow-x:hidden;padding:22px 28px 28px}
   .bottom-dock{flex-shrink:0;background:var(--base-950);padding:0}
   .bottom-dock:has(.active){border-top:1px solid var(--base-800)}
   .bottom-dock:empty{display:none;border:none}

   /* In-Place Stream Dock (Zero-Flicker CSS Toggle via #seon-app[data-thinking], Zero Front-Truncation!) */
   .stream-dock.idle{display:none}
   .stream-dock.active{max-width:860px;width:100%;margin:0 auto;padding:10px 24px 0}
   .live-stream-card{background:var(--base-900);border:1px solid rgba(240,180,41,.32);border-left:2px solid var(--signal);border-radius:8px;padding:10px 14px;display:flex;flex-direction:column;gap:8px}
   .live-stream-head{display:flex;justify-content:space-between;align-items:center;gap:10px}
   .thinking-toggle{background:transparent;border:1px solid var(--base-700);color:var(--text-300);padding:2px 8px;border-radius:4px;font-family:var(--font-mono);font-size:10.5px;cursor:pointer}
   .thinking-toggle:hover{border-color:var(--signal);color:var(--signal)}
   #seon-app[data-thinking='closed'] .live-reasoning{display:none}
   #seon-app[data-thinking='open'] .live-reasoning{display:block}
   #seon-app[data-thinking='closed'] .thinking-toggle .when-open{display:none}
   #seon-app[data-thinking='open'] .thinking-toggle .when-closed{display:none}
   .live-reasoning{font-family:var(--font-mono);font-size:11.5px;color:var(--text-400);white-space:pre-wrap;max-height:220px;overflow-y:auto;line-height:1.55;padding:9px 11px;background:var(--base-950);border-radius:6px;border:1px solid var(--base-800)}
   .live-prose{font-family:var(--font-text);font-size:15.5px;line-height:1.68;color:var(--text-50);white-space:pre-wrap;max-height:220px;overflow-y:auto}
   .cursor{color:var(--signal);animation:seon-blink 1s steps(2,end) infinite}
   @keyframes seon-blink{0%,100%{opacity:1}50%{opacity:0}}

   /* Docked Canvas Composer (Never Overlaps Stage, Never Interrupted by Streaming) */
   .composer-dock.idle{display:none}
   .composer-dock.active{max-width:860px;width:100%;margin:0 auto;padding:10px 24px 14px}

   /* Shared Cards & Typography */
   .card{background:var(--base-900);border:1px solid var(--base-800);border-radius:8px;padding:15px 17px;min-width:0;max-width:100%}
   .tile-wrap{display:flex;flex-direction:column;gap:4px}
   .tile-meta{display:flex;justify-content:space-between;align-items:center;padding:0 2px}
   .tile-card{padding:13px 15px}
   .card-head{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:10px;flex-wrap:wrap}
   h2{margin:0;font-family:var(--font-mono);font-size:12.5px;font-weight:600;letter-spacing:.03em;color:var(--text-100)}
   h3{margin:0;font-family:var(--font-mono);font-size:10.5px;text-transform:uppercase;letter-spacing:.14em;color:var(--text-400)}
   .stack{display:flex;flex-direction:column;gap:14px}
   .stack-sm{display:flex;flex-direction:column;gap:8px}
   .grid-2{display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start}
   .grid-2>*{min-width:0;max-width:100%}
   .row-between{display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap}
   .pill-row{display:flex;flex-wrap:wrap;gap:6px;align-items:center}

   /* View 1: Pure Self-Replacing Live Canvas (For ALL Agents — Zero Chat Log!) */
   .root-canvas{max-width:860px;margin:0 auto;display:flex;flex-direction:column;gap:18px}
   .canvas-header{display:flex;flex-direction:column;gap:8px}
   .canvas-title{margin:0;font-family:var(--font-text);font-size:clamp(22px,3vw,30px);line-height:1.18;font-weight:650;letter-spacing:-.026em;color:var(--text-50)}
   .canvas-summary{margin:0;font-family:var(--font-text);font-size:clamp(14.5px,1.8vw,16px);line-height:1.68;color:var(--text-200)}
   .canvas-reply{font-family:var(--font-text);font-size:clamp(15.5px,2vw,18px);line-height:1.72;color:var(--text-50);padding:4px 0 6px}
   .canvas-re-prompt{font-family:var(--font-mono);font-size:11.5px;color:var(--text-400);margin-bottom:8px}
   .canvas-pending{display:flex;flex-direction:column;gap:14px;padding:20px 0 12px}
   .canvas-prompt-headline{margin:0;font-family:var(--font-text);font-size:clamp(20px,2.8vw,27px);line-height:1.28;font-weight:600;letter-spacing:-.022em;color:var(--text-50);white-space:pre-wrap}
   .canvas-pending-status{display:inline-flex;align-items:center;gap:9px;font-family:var(--font-mono);font-size:12px;color:var(--signal)}
   .prev-peek{margin-top:8px}
   .prev-peek summary{cursor:pointer;font-family:var(--font-mono);font-size:11px;color:var(--text-500)}
   .prev-peek summary:hover{color:var(--text-300)}
   .wire-img{max-width:100%;height:auto;border-radius:6px;border:1px solid var(--base-800);margin-top:8px;display:block}
   .canvas-prev-visual{display:flex;flex-direction:column;gap:10px;padding-top:16px;border-top:1px solid var(--base-800)}
   .prev-visual-head{display:flex;align-items:center;justify-content:space-between}
   .canvas-surface{background:var(--base-900);border:1px solid var(--base-800);border-radius:10px;padding:20px 22px}
   .canvas-surface svg{max-width:100%;height:auto;display:block}
   .prose-blocks{display:flex;flex-direction:column;gap:10px}
   .prose-p{margin:0}
   .prose-h3{margin:8px 0 2px;font-family:var(--font-text);font-size:17px;font-weight:650;text-transform:none;letter-spacing:-.01em;color:var(--text-50)}
   .prose-list{margin:2px 0 4px;padding-left:20px;display:flex;flex-direction:column;gap:5px}
   .prose-list li{line-height:1.65}
   .canvas-reply strong,.canvas-summary strong,.chat-body strong,.prose-p strong,.prose-list strong{color:var(--text-50);font-weight:650}
   .canvas-reply em,.canvas-summary em,.chat-body em,.prose-p em,.prose-list em{font-style:italic;color:var(--text-100)}
   .root-hero{padding:28px 0 18px}
   .root-kicker{font-family:var(--font-mono);text-transform:uppercase;letter-spacing:.14em;font-size:10.5px;color:var(--signal);margin-bottom:10px}
   .root-title{margin:0 0 10px;font-family:var(--font-text);font-size:clamp(24px,3.2vw,32px);line-height:1.16;font-weight:650;letter-spacing:-.028em;color:var(--text-50)}
   .root-sub{margin:0;font-family:var(--font-text);font-size:15px;line-height:1.65;color:var(--text-300);max-width:650px}
   .root-visuals{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:14px}
   .stage-remove{opacity:.35;transition:opacity .15s}
   .tile-wrap:hover .stage-remove{opacity:1}
   .activity-fold{background:var(--base-900);border:1px solid var(--base-800);border-radius:7px;padding:6px 11px}
   .activity-fold>summary{display:flex;align-items:center;gap:8px;cursor:pointer;list-style:none;font-family:var(--font-mono);font-size:11.5px;color:var(--text-400)}
   .activity-fold>summary::-webkit-details-marker{display:none}
   .activity-fold>summary::before{content:'▸';color:var(--text-500)}
   .activity-fold[open]>summary::before{content:'▾';color:var(--signal)}
   .activity-fold[open]>summary{color:var(--text-200)}
   .activity-body{margin-top:9px;display:flex;flex-direction:column;gap:8px}
   .inline-code{background:var(--base-850);color:var(--signal);padding:1px 6px;border-radius:4px;font-family:var(--font-mono);font-size:.88em}
   .branch-preview-bar{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:8px 13px;border-radius:8px;border:1px solid rgba(240,180,41,.38);background:rgba(240,180,41,.07);font-family:var(--font-mono);font-size:11.5px;flex-wrap:wrap}
   .chat-body{font-family:var(--font-text);font-size:15px;line-height:1.68;color:var(--text-100)}
   /* Sleek Minimal Message Bar (Canvas & Workspace — Zero Clutter!) */
   .composer-shell{background:var(--base-900);border:1px solid var(--base-700);border-radius:12px;padding:8px 8px 8px 14px;display:flex;align-items:flex-end;gap:8px;transition:border-color .15s,box-shadow .15s}
   .composer-shell:focus-within{border-color:var(--signal);box-shadow:0 0 0 2px rgba(240,180,41,.12)}
   .composer-inp{background:transparent;border:none;color:var(--text-50);font-size:14.5px;line-height:1.5;resize:none;outline:none;font-family:var(--font-text);flex:1;min-width:0;padding:4px 0;min-height:30px;max-height:140px}
   .composer-inp::placeholder{color:var(--text-400)}
   .send-icon-btn{width:34px;height:34px;border-radius:9px;border:none;background:var(--signal);color:var(--signal-fg);cursor:pointer;display:inline-flex;align-items:center;justify-content:center;flex-shrink:0;padding:0;transition:opacity .15s,transform .1s}
   .send-icon-btn:hover{opacity:.92}
   .send-icon-btn:active{transform:scale(.95)}
   .table-scroll{width:100%;overflow-x:auto}

   /* View 2: Clean Traditional Chronological Chat + Live Context Tiles (`:workspace`) */
   .workspace-grid{display:grid;grid-template-columns:minmax(400px,1.25fr) minmax(340px,1fr);gap:18px;max-width:1540px;margin:0 auto;height:calc(100vh - 100px);align-items:stretch}
   .ws-left,.ws-right{background:var(--base-900);border:1px solid var(--base-800);border-radius:9px;padding:14px 16px;display:flex;flex-direction:column;gap:12px;min-height:0;overflow:hidden}
   .ws-head{display:flex;justify-content:space-between;align-items:flex-start;gap:10px;padding-bottom:10px;border-bottom:1px solid var(--base-800);flex-shrink:0}
   .ws-timeline{display:flex;flex-direction:column;gap:16px;flex:1;min-height:0;overflow-y:auto;padding:10px 8px 16px 2px}
   .ws-exchange{display:flex;flex-direction:column;gap:10px}
   .ws-user-bubble{align-self:flex-end;background:var(--base-850);border:1px solid var(--base-700);border-radius:12px 12px 3px 12px;padding:9px 14px;font-size:14.5px;line-height:1.55;color:var(--text-50);max-width:82%;white-space:pre-wrap}
   .ws-agent-turn{display:flex;flex-direction:column;gap:8px;padding:2px 0 4px 14px;border-left:2px solid var(--base-700)}
   .ws-agent-turn.has-prose{border-left-color:var(--signal)}
   .ws-composer{border-top:1px solid var(--base-800);padding-top:10px;flex-shrink:0}
   .goal-banner{background:var(--base-850);border-left:2px solid var(--signal);padding:8px 12px;border-radius:4px}
   .tiles-scroll{display:flex;flex-direction:column;gap:14px;flex:1;min-height:0;overflow-y:auto;padding-right:4px}

   /* View 3: Zero-Cost Debug View */
   .debug-wrap{display:flex;flex-direction:column;gap:16px;max-width:100%;overflow-x:hidden}
   .wire-list{display:flex;flex-direction:column;gap:6px}
   .wire-row{background:var(--base-950);border:1px solid var(--base-800);border-radius:6px;padding:7px 10px;min-width:0;max-width:100%}
   .wire-summary{display:flex;align-items:center;gap:8px;cursor:pointer;list-style:none;font-family:var(--font-mono);font-size:12px}
   .wire-summary::-webkit-details-marker{display:none}
   .wire-summary::before{content:'▸';color:var(--text-500)}
   .wire-row[open]>.wire-summary::before{content:'▾';color:var(--signal)}
   .wire-body{margin-top:8px;padding-top:8px;border-top:1px solid var(--base-800)}
   .xml-tag{background:transparent;border:1px solid var(--base-700);color:var(--eval)}
   .debug-tile-pair{display:grid;grid-template-columns:1fr 1fr;gap:14px;align-items:start;padding:12px;background:var(--base-950);border:1px solid var(--base-800);border-radius:8px}
   .debug-tile-col{min-width:0;display:flex;flex-direction:column;gap:6px}
   .ctx-pre{max-height:360px;overflow:auto;white-space:pre-wrap}

   /* Shared Phosphor Controls */
   .eval-fold{background:var(--base-950);border:1px solid var(--base-800);border-radius:6px;padding:6px 10px}
   .eval-summary{display:flex;align-items:center;gap:8px;cursor:pointer;list-style:none;flex-wrap:wrap;font-family:var(--font-mono);font-size:12px}
   .eval-summary::-webkit-details-marker{display:none}
   .eval-summary::before{content:'▸';color:var(--text-500)}
   .eval-fold[open]>.eval-summary::before{content:'▾';color:var(--signal)}
   .eval-detail{margin-top:8px;display:flex;flex-direction:column;gap:6px}
   .fold summary{cursor:pointer;font-family:var(--font-mono)}
   .handle-pill{background:transparent;border:1px solid var(--base-700);color:var(--eval)}
   .btn{border:1px solid var(--base-700);background:var(--base-850);color:var(--text-100);padding:6px 13px;border-radius:6px;cursor:pointer;font-family:var(--font-mono);font-size:12px;font-weight:500}
   .btn:hover{border-color:var(--signal);color:var(--text-50)}
   .btn.sm{padding:4px 9px;font-size:11px;border-radius:5px}
   .btn.primary{background:var(--signal);border-color:var(--signal);color:var(--signal-fg);font-weight:600}
   .btn.primary:hover{opacity:.92;color:var(--signal-fg)}
   .btn.success{border-color:var(--success);color:var(--success);background:transparent}
   .btn.danger{border-color:var(--error);color:var(--error);background:transparent}
   .link-muted{background:none;border:none;color:var(--text-500);cursor:pointer;padding:0;font-family:var(--font-mono)}
   .link-muted:hover{color:var(--error)}
   .badge{padding:1px 7px;border-radius:999px;border:1px solid var(--base-700);font-family:var(--font-mono);font-size:10.5px;font-weight:500;display:inline-flex;align-items:center;color:var(--text-300)}
   .badge.blue{border-color:rgba(96,165,250,.4);color:var(--info)}
   .badge.green{border-color:rgba(52,211,153,.4);color:var(--success)}
   .badge.amber{border-color:rgba(240,180,41,.45);color:var(--signal)}
   .badge.red{border-color:rgba(248,113,113,.45);color:var(--error)}
   .badge.purple{border-color:rgba(192,132,252,.45);color:var(--eval)}
   .badge.muted{border-color:var(--base-800);color:var(--text-400)}
   .pill{background:var(--base-850);border:1px solid var(--base-800);color:var(--text-200);padding:1px 7px;border-radius:4px;font-family:var(--font-mono);font-size:11px}
   .kv-grid{display:grid;grid-template-columns:180px 1fr;gap:4px 10px;margin:0}
   .kv-grid dt{color:var(--text-400);font-family:var(--font-mono);font-size:11.5px}
   .kv-grid dd{margin:0;word-break:break-word;font-family:var(--font-mono);font-size:11.5px;color:var(--text-100)}
   .data-table{width:100%;border-collapse:collapse;font-family:var(--font-mono);font-size:11.5px}
   .data-table th,.data-table td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--base-800);vertical-align:top;word-break:break-word}
   .data-table th{color:var(--text-400);font-weight:500}
   .code-box{background:var(--base-950);border:1px solid var(--base-800);border-radius:6px;padding:9px 11px;margin:0;max-width:100%;overflow-x:auto;font-family:var(--font-mono);font-size:11.5px;color:var(--text-100)}
   .doc-box{background:var(--base-850);border-left:2px solid var(--signal);padding:8px 12px;border-radius:4px;color:var(--text-200);font-family:var(--font-text)}
   .form-stack{display:flex;flex-direction:column;gap:8px}
   .row-form{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
   .inp{background:var(--base-950);border:1px solid var(--base-700);color:var(--text-100);padding:7px 10px;border-radius:5px;font-family:var(--font-mono);font-size:12px;width:100%;outline:none}
   .inp:focus{border-color:var(--signal)}
   .inp.sm{padding:5px 8px;font-size:11.5px;width:auto}
   .notice{padding:8px 12px;border-radius:6px;font-family:var(--font-mono);font-size:12px;border:1px solid var(--base-700);background:var(--base-900)}
   .notice.green{border-color:rgba(52,211,153,.45);color:var(--success)}
   .notice.red{border-color:rgba(248,113,113,.5);color:var(--error)}
   .green-text{color:var(--success)} .red-text{color:var(--error)}
   /* Responsive Breakpoints — Half-Screen (600-960px) & Compact (<640px) First-Class! */
   @media(max-width:1080px){
     .workspace-grid{grid-template-columns:1fr;height:auto}
     .ws-left{min-height:460px;max-height:72vh}
     .ws-right{min-height:360px}
     .grid-2,.debug-tile-pair{grid-template-columns:1fr}
   }
   @media(max-width:960px){
     .sidebar.open{position:fixed;top:48px;bottom:0;left:0;z-index:40;width:252px;box-shadow:10px 0 36px rgba(0,0,0,.48)}
     .stage-scroll{padding:16px 18px 22px}
     .composer-dock.active,.stream-dock.active{padding-left:16px;padding-right:16px}
     .kv-grid{grid-template-columns:136px 1fr}
     .ws-head{flex-wrap:wrap}
   }
   @media(max-width:740px){
     .topbar{padding:0 10px}
     .status-pill{padding:0 8px}
     .status-pill .status-label{display:none}
     .nav-pill{padding:4px 8px;font-size:11px}
     .stage-scroll{padding:14px 12px 18px}
     .composer-dock.active,.stream-dock.active{padding-left:12px;padding-right:12px}
     .canvas-surface{padding:14px}
     .ws-left,.ws-right{padding:12px}
     .ws-user-bubble{max-width:92%}
     .wire-summary{flex-wrap:wrap}
     .truncate{max-width:220px}
   }
   @media(max-width:560px){
     .kv-grid{grid-template-columns:1fr;gap:2px}
     .kv-grid dt{margin-top:6px}
     .wordmark{display:none}
     .crumb-sep{display:none}
   }")

(def ^:private datastar-js-text
  (delay
    (let [f (io/file store/checkout-root "resources/public/datastar.js")]
      (if (.isFile f) (slurp f) "// datastar.js missing"))))

(defn url-enc [^String s] (render/url-enc s))

(defn parse-deep-link
  "Translates optional browser URL deep-links into `:show-user` ops so Datahike stays the sole truth."
  ([^String uri] (parse-deep-link nil uri))
  ([db ^String uri]
   (let [u (or uri "/")]
     (cond
       (or (= u "/") (= u "")) :current
       (= u "/root/debug") {:op :show-user :viewing (or (when db (ui/entry-agent-id db)) :agent/root) :mode :debug}
       (str/starts-with? u "/agent/")
       (let [parts (str/split (subs u 7) #"/" -1)
             slug (java.net.URLDecoder/decode (first parts) "UTF-8")
             aid (ui/slug->agent-id slug)
             debug? (= "debug" (second parts))]
         (when aid
           {:op :show-user :viewing aid :mode (if debug? :debug :workspace)}))
       :else nil))))

(defn- parse-kv-pairs [s]
  (if (str/blank? s)
    {}
    (into {}
          (keep (fn [part]
                  (when (str/includes? part "=")
                    (let [[k v] (str/split part #"=" 2)]
                      (when (seq k)
                        [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                         (java.net.URLDecoder/decode (or v "") "UTF-8")])))))
          (str/split s #"&"))))

(defn- parse-multipart-fields [^String ct ^String body]
  (if-let [[_ boundary] (re-find #"boundary=([^\s;]+)" (str ct))]
    (let [parts (str/split body (re-pattern (str "--" (java.util.regex.Pattern/quote boundary))))]
      (into {}
            (keep (fn [part]
                    (when-let [[_ fname] (re-find #"name=\"([^\"]+)\"" part)]
                      (let [sep (or (str/index-of part "\r\n\r\n") (str/index-of part "\n\n"))
                            offset (if (str/index-of part "\r\n\r\n") 4 2)]
                        (when sep
                          (let [raw-val (subs part (+ sep offset))
                                val (str/replace raw-val #"(?:\r?\n)$" "")]
                            [(keyword fname) val]))))))
            parts))
    {}))

(defn- parse-form-body [req]
  (let [ct (str/lower-case (or (get-in req [:headers "content-type"]) ""))
        body-str (cond
                   (nil? (:body req)) ""
                   (string? (:body req)) (:body req)
                   :else (slurp (:body req)))]
    (cond
      (str/blank? body-str) {}
      (str/includes? ct "multipart/form-data") (parse-multipart-fields ct body-str)
      (str/includes? ct "application/x-www-form-urlencoded") (parse-kv-pairs body-str)
      ;; Datastar sends client UI signals (e.g. `{"showThinking":false}`) as `application/json`
      ;; on non-form `@post(...)` clicks — never treat client signals as HTML form fields!
      (str/includes? ct "application/json") {}
      :else (parse-kv-pairs body-str))))

(defn- parse-kw-input [^String s default-kw]
  (let [t (str/trim (str s))]
    (cond
      (str/blank? t) default-kw
      (str/starts-with? t ":") (edn/read-string t)
      :else (keyword t))))

;; --- Non-Blocking Inside-Out `^:seon/callable` UI Actions (All Persist in Datahike!) ---

(defn- async-action [st req slot format-fn]
  {:st st :async {:req req :slot slot :format-fn format-fn}})

(defn ^:seon/callable toggle-sidebar! [st]
  (async-action st {:op :show-user :toggle-sidebar? true} nil nil))

(defn ^:seon/callable toggle-theme! [st]
  (async-action st {:op :show-user :toggle-theme? true} nil nil))

(defn ^:seon/callable toggle-expand! [st agent-id]
  (async-action st {:op :show-user :toggle-expand agent-id} nil nil))

(defn ^:seon/callable view-agent! [st agent-id mode]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :viewing agent-id :mode (or mode :workspace)}
                nil nil))

(defn ^:seon/callable set-view-mode! [st mode]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :mode mode}
                nil nil))

(defn ^:seon/callable select-branch! [st branch]
  (async-action (assoc st :branch-feedback nil)
                {:op :show-user :selected-branch branch}
                nil nil))

;; Back-compat alias kept for existing tests
(defn ^:seon/callable switch-tab! [st tab]
  (set-view-mode! st (case tab (:schemas :health :branches) :debug :agents :workspace :canvas)))

(defn ^:seon/callable power-loss-reboot! [st]
  (when-let [pl (:on-power-loss st)]
    (a/thread (pl)))
  {:st st})

(defn ^:seon/callable run-tests! [st branch]
  (async-action
   (assoc st :branch-feedback {:ok? true :text (str "Running reach tests on " branch "...")})
   {:op :test :branch branch}
   :branch-feedback
   (fn [ans]
     {:ok? (:ok? ans)
      :text (str "Tests on " branch ": " (if (:ok? ans) "PASS" "FAIL")
                 " (" (:ran ans 0) " ran, " (:cached ans 0) " cached"
                 (when (:short-circuited? ans) (str ", fail-fast skipped " (:skipped-slow ans) " slow")) ")")})))

(defn ^:seon/callable merge-branch! [st branch]
  (let [srv (:server-in-ch st)
        ev-ch (:event-ch st)]
    (when (and srv ev-ch)
      (a/go
        (let [t-ans (let [rc (a/chan 1)]
                      (a/>! srv {:req {:op :test :branch branch} :reply-ch rc})
                      (let [[v _] (a/alts! [rc (a/timeout 30000)])]
                        (or v {:ok? false :error :timeout})))]
          (if-not (:ok? t-ans)
            (a/>! ev-ch {:ev :action-result
                         :field :branch-feedback
                         :val {:ok? false
                               :text (str "Merge blocked on " branch ": tests failed ("
                                          (or (:error t-ans)
                                              (str (:ran t-ans 0) " ran, "
                                                   (count (remove :seon.test/pass? (:results t-ans))) " failed")) ")")}})
            (let [m-ans (let [rc (a/chan 1)]
                          (a/>! srv {:req {:op :merge :branch branch} :reply-ch rc})
                          (let [[v _] (a/alts! [rc (a/timeout 30000)])]
                            (or v {:ok? false :error :timeout})))]
              (a/>! ev-ch {:ev :action-result
                           :field :branch-feedback
                           :val {:ok? (:ok? m-ans)
                                 :text (if (:ok? m-ans)
                                         (str "Tested & merged " branch " -> :main @ tx " (:tx m-ans)
                                              " (" (count (:touched-paths m-ans)) " files)")
                                         (str "Blocked / Conflict: " (pr-str (or (:conflicts m-ans) (:error m-ans)))))}}))))))
    (assoc st :branch-feedback {:ok? true :text (str "Running reach tests before merging " branch " -> :main...")})))

(defn ^:seon/callable assign-health-fix! [st cls subject message]
  (let [tid (keyword "seon.health" (str "fix-" (mod (System/currentTimeMillis) 100000)))
        scope-ns (try
                   (let [s (str subject)]
                     (if (str/includes? s "/") (symbol (first (str/split s #"/" 2))) 'seon))
                   (catch Throwable _ 'seon))]
    (async-action
     (assoc st :branch-feedback {:ok? true :text (str "Spawned worker for health issue " cls " on " subject)})
     {:op :create-task :id tid :scope scope-ns :title (str "Fix " (name cls) " on " subject)
      :plan (str message) :spawn-agent? true :role :worker}
     nil nil)))

(defn ^:seon/callable spawn-agent-form!
  ([st form] (spawn-agent-form! st (ui/entry-agent-id @(:conn st)) form))
  ([st parent-id form]
   (let [aid (parse-kw-input (:agent_id form) (keyword "agent" (str "w" (mod (System/currentTimeMillis) 10000))))
         branch (keyword "branch" (str (or (namespace aid) "agent") "." (name aid)))
         scope (when-not (str/blank? (:scope form)) (symbol (str/trim (:scope form))))
         goal (str/trim (str (:goal form "")))]
     (async-action
      (assoc st :branch-feedback {:ok? true :text (str "Spawned " aid " under " parent-id " on " branch)})
      (cond-> {:op :spawn-agent :id aid :parent parent-id :branch branch :role :worker :goal goal}
        scope (assoc :scope scope))
      nil nil))))

(defn ^:seon/callable register-schema-form! [st form]
  (let [k (parse-kw-input (:key form) :seon.custom/item)]
    (async-action
     (assoc st :branch-feedback {:ok? true :text (str "Validating & deriving " k "...")})
     (cond-> {:op :register-schema :key k :form (:form form "")}
       (not (str/blank? (:doc form))) (assoc :doc (:doc form)))
     :branch-feedback
     (fn [ans]
       {:ok? (:ok? ans)
        :text (if (:ok? ans)
                (str "Registered " (:key ans)
                     (when (seq (:derived-attributes ans))
                       (str " + derived Datahike attr " (:derived-attributes ans)))
                     " — seed=42 sample: " (:sample ans))
                (str "Rejected: " (:error ans)))}))))

(defn ^:seon/callable step-canvas-history! [st delta]
  (let [db @(:conn st)
        {:keys [viewing-id as-of-tx]} (ui/user-view-state db)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        bdb (or (try (store/head (:conn st) branch) (catch Throwable _ nil)) db)
        txs (ui/canvas-history-txs bdb viewing-id)
        n (count txs)]
    (if (< n 2)
      {:st st}
      (let [cur-idx (if as-of-tx
                      (or (first (keep-indexed (fn [i tx] (when (= tx as-of-tx) i)) txs)) (dec n))
                      (dec n))
            nxt-idx (max 0 (min (dec n) (+ cur-idx (long delta))))
            target (if (= nxt-idx (dec n)) :live (nth txs nxt-idx))]
        (async-action st {:op :show-user :as-of-tx target} nil nil)))))

(defn ^:seon/callable jump-canvas-tx! [st tx]
  (let [db @(:conn st)
        {:keys [viewing-id]} (ui/user-view-state db)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        bdb (or (try (store/head (:conn st) branch) (catch Throwable _ nil)) db)
        txs (ui/canvas-history-txs bdb viewing-id)
        target (if (or (nil? tx) (= tx (last txs))) :live (long tx))]
    (async-action st {:op :show-user :as-of-tx target} nil nil)))

(defn ^:seon/callable jump-canvas-live! [st]
  (async-action st {:op :show-user :as-of-tx :live} nil nil))

(defn ^:seon/callable dismiss-feedback! [st]
  {:st (assoc st :branch-feedback nil)})

(defn ^:seon/callable chat-root-form!
  "Back-compat alias to `chat-agent-form!`."
  ([st form] (chat-agent-form! st form))
  ([st agent-id form] (chat-agent-form! st agent-id form)))

(defn ^:seon/callable eval-agent-form! [st agent-id branch form]
  (let [code-str (str/trim (str (:code form "")))]
    (cond
      (str/blank? code-str)
      {:st st}

      (or (boolean (re-find #"^[\(\[\{'#@]" code-str))
          (and (str/starts-with? code-str ":") (not (str/includes? code-str " "))))
      (async-action
       (assoc st :branch-feedback nil)
       {:op :eval :branch branch :agent-id agent-id :code code-str :keep-warm? true}
       :branch-feedback
       (fn [ans]
         (when-not (:ok? ans)
           {:ok? false :text (str "Error: " (:error ans))})))

      :else
      (async-action
       (assoc st :branch-feedback nil)
       {:op :send-message :from :user :to agent-id :type :chat :body code-str}
       nil nil))))

(defn ^:seon/callable unpresent-stage! [st agent-id stage-key]
  (let [branch (or (:seon.agent/branch (d/pull @(:conn st) '[:seon.agent/branch] [:seon.agent/id agent-id])) :main)]
    (async-action
     st
     {:op :eval :branch branch :agent-id agent-id :code (str "(user/unpresent! " (pr-str stage-key) ")") :keep-warm? true}
     nil nil)))

(defn ^:seon/callable pin-ref-form! [st agent-id form]
  (let [raw (str/trim (str (:ref form "")))
        parsed (try (edn/read-string raw) (catch Throwable _ nil))]
    (if-not (and (vector? parsed) (= 2 (count parsed)) (qualified-keyword? (first parsed)))
      {:st (assoc st :branch-feedback {:ok? false :text "Pin expects a lookup ref vector like [:seon.code.ns/name 'seon.web]"})}
      (let [cur (vec (or (:seon.agent/pinned (d/pull @(:conn st) '[:seon.agent/pinned] [:seon.agent/id agent-id])) []))
            nxt (if (some #(= % parsed) cur) cur (conj cur parsed))]
        (async-action
         (assoc st :branch-feedback {:ok? true :text (str "Pinned " (pr-str parsed) " to " agent-id "'s walk.")})
         {:op :transact :tx-data [{:seon.agent/id agent-id :seon.agent/pinned nxt}]}
         nil nil)))))

(defn ^:seon/callable unpin-tile! [st agent-id ref-val]
  (let [cur (vec (or (:seon.agent/pinned (d/pull @(:conn st) '[:seon.agent/pinned] [:seon.agent/id agent-id])) []))
        nxt (vec (remove #(= % ref-val) cur))]
    (async-action
     st
     {:op :transact :tx-data [{:seon.agent/id agent-id :seon.agent/pinned nxt}]}
     nil nil)))

(defn invoke-call!
  "Dispatches either a registered Reagent closure (`?h=...`) OR a stateless `^:seon/callable`
   Clojure call form (`?form=(ns/fn ...)`). Fault-isolated: never throws out of `web-proc`!"
  [st {:keys [hkey call-form form-fields]}]
  (try
    (cond
      hkey
      (if-let [hfn (get-in st [:walk :handlers hkey])]
        (let [res (if (seq form-fields) (hfn st form-fields) (hfn st))]
          (if (and (map? res) (contains? res :st))
            (assoc res :ok? true)
            {:ok? true :st (if (and (map? res) (contains? res :conn)) res st)}))
        {:ok? false :error :seon.web/stale-handler :handler hkey :st st})

      (and (seq? call-form) (qualified-symbol? (first call-form)))
      (let [sym (first call-form)
            v (resolve sym)]
        (if-not (and v (:seon/callable (meta v)))
          {:ok? false :error :seon.web/not-callable :message (str sym " is not marked ^:seon/callable") :fn sym :st st}
          (let [args (cond-> (vec (rest call-form)) (seq form-fields) (conj form-fields))
                res (apply @v st args)]
            (if (and (map? res) (contains? res :st))
              (assoc res :ok? true)
              {:ok? true :st (if (and (map? res) (contains? res :conn)) res st)}))))

      :else
      {:ok? false :error :seon.web/malformed-call :form call-form :st st})
    (catch Throwable ex
      {:ok? false :error :seon.web/handler-error :message (or (.getMessage ex) (str ex)) :st st})))

;; --- Live MCP JSON-RPC 2.0 Edge (`POST /mcp`) ---

(def ^:private mcp-tools
  [{:name "seon_repl"
    :description "Evaluate Clojure forms inside an isolated COW forked Seon agent REPL on a Datahike branch. Returns live runtime handle `result/e<n>`, `@seon.ctx/ctx`, `(user/pin! ...)`, `(user/show-user! ...)`, `(user/health)`, and `(user/register-schema! ...)`."
    :inputSchema {:type "object"
                  :properties {:code   {:type "string" :description "Clojure form(s) to evaluate."}
                               :branch {:type "string" :description "Datahike branch keyword, e.g. \":branch/work\" (default \":branch/mcp\")."}
                               :agent  {:type "string" :description "Agent keyword id, e.g. \":agent/mcp\"."}}
                  :required ["code"]}}
   {:name "seon_health"
    :description "Read system health (derived structural violations, blocking Flow procs, unschemed attributes, stored-data schema checks, global failing tests across branches, open proc faults)."
    :inputSchema {:type "object" :properties {}}}
   {:name "seon_test"
    :description "Run minimal reach-selected, fail-fast clojure.test + seed=42 Malli generative checks on a branch (default :main) inside Seon."
    :inputSchema {:type "object"
                  :properties {:branch {:type "string" :description "Branch keyword (default \":main\")."}
                               :force  {:type "boolean" :description "Bypass green reach cache."}}}}
   {:name "seon_merge"
    :description "Perform Malli-driven 3-way merge of a branch into Datahike :main (enforcing stored-data schema & health gates) and sync local Git."
    :inputSchema {:type "object"
                  :properties {:branch  {:type "string" :description "Branch keyword to merge into :main."}
                               :message {:type "string" :description "Optional commit message."}}
                  :required ["branch"]}}
   {:name "seon_status"
    :description "Read system status snapshot (main tx, git commit, branches, tasks, agents, config, derived Datahike attributes)."
    :inputSchema {:type "object" :properties {}}}])

(defn ^:seon.flow/edge call-server-edge!
  [server-in-ch req timeout-ms]
  (let [rch (a/chan 1)]
    (a/put! server-in-ch {:req req :reply-ch rch})
    (let [[ans _] (a/alts!! [rch (a/timeout timeout-ms)])]
      (or ans {:ok? false :error :timeout}))))

(defn ^:seon.flow/edge handle-mcp-jsonrpc!
  [server-in-ch ^String body-str]
  (let [msg (try (json/read-str body-str :key-fn keyword) (catch Throwable _ nil))
        id (:id msg)
        method (:method msg)]
    (cond
      (nil? msg)
      {:status 400 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Invalid JSON"}})}

      (= method "initialize")
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/write-str
              {:jsonrpc "2.0" :id id
               :result {:protocolVersion "2024-11-05"
                        :capabilities {:tools {}}
                        :serverInfo {:name "seon-next" :version "2.0.0"}}})}

      (= method "notifications/initialized")
      {:status 200 :headers {"Content-Type" "application/json"} :body ""}

      (= method "tools/list")
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:jsonrpc "2.0" :id id :result {:tools mcp-tools}})}

      (= method "tools/call")
      (let [tname (get-in msg [:params :name])
            args  (get-in msg [:params :arguments] {})
            req   (case tname
                    "seon_repl"   {:op :eval
                                   :branch (parse-kw-input (or (:branch args) ":branch/mcp") :branch/mcp)
                                   :agent-id (when (:agent args) (parse-kw-input (:agent args) :agent/mcp))
                                   :code (str (:code args ""))
                                   :keep-warm? true}
                    "seon_health" {:op :health}
                    "seon_test"   {:op :test
                                   :branch (parse-kw-input (or (:branch args) ":main") :main)
                                   :force? (boolean (:force args))}
                    "seon_merge"  {:op :merge
                                   :branch (parse-kw-input (:branch args) :branch/mcp)
                                   :message (:message args)}
                    "seon_status" {:op :status}
                    nil)]
        (if-not req
          {:status 200 :headers {"Content-Type" "application/json"}
           :body (json/write-str {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Unknown tool: " tname)}})}
          (let [ans (if (= tname "seon_merge")
                      (let [br (:branch req)
                            t-ans (call-server-edge! server-in-ch {:op :test :branch br} 30000)]
                        (if-not (:ok? t-ans)
                          {:ok? false :branch br :error "Reach tests failed; merge blocked" :test-result t-ans}
                          (call-server-edge! server-in-ch req 30000)))
                      (call-server-edge! server-in-ch req 30000))]
            {:status 200 :headers {"Content-Type" "application/json"}
             :body (json/write-str
                    {:jsonrpc "2.0" :id id
                     :result {:content [{:type "text" :text (pr-str ans)}]
                              :isError (not (:ok? ans true))}})})))

      :else
      {:status 200 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}})})))

;; --- Unified Page Shell & Persistent HTTP/SSE Listener (`^:seon.flow/edge`) ---

(def ^:private sticky-scroll-js
  "(()=>{let t=null;const near=(el)=>(el.scrollHeight-el.scrollTop-el.clientHeight)<56;const snapAll=()=>{document.querySelectorAll('.ws-timeline,.live-reasoning,.live-prose').forEach((el)=>{const ag=el.dataset.agent||'';if(el._lastAgent!==ag){el._lastAgent=ag;el.dataset.userScrolled='0';}if(el.dataset.userScrolled!=='1'){el.scrollTop=el.scrollHeight;}});};document.addEventListener('scroll',(e)=>{const el=e.target;if(el&&el.classList&&(el.classList.contains('ws-timeline')||el.classList.contains('live-reasoning')||el.classList.contains('live-prose'))){el.dataset.userScrolled=near(el)?'0':'1';}},true);document.addEventListener('submit',(e)=>{const f=e.target;document.querySelectorAll('.ws-timeline,.live-reasoning,.live-prose').forEach((el)=>{el.dataset.userScrolled='0';});if(f&&f.querySelectorAll){setTimeout(()=>{f.querySelectorAll('textarea[name=text],textarea[name=code],input[name=ref]').forEach((i)=>{i.value='';});},0);}},true);window.addEventListener('resize',()=>{if(t)cancelAnimationFrame(t);t=requestAnimationFrame(snapAll);});const mo=new MutationObserver(()=>{if(t)cancelAnimationFrame(t);t=requestAnimationFrame(snapAll);});window.addEventListener('DOMContentLoaded',()=>{const app=document.getElementById('seon-app');if(app)mo.observe(app,{childList:true,subtree:true,characterData:true});snapAll();});})();")

(defn- page-shell [walk]
  (let [header   (get-in walk [:units "seon-header" :html] "")
        sidebar  (get-in walk [:units "seon-sidebar" :html] "")
        view     (get-in walk [:units "seon-view-unit" :html] "")
        stream   (get-in walk [:units "seon-stream-unit" :html] "")
        composer (get-in walk [:units "seon-composer-unit" :html] "")]
    (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>seon</title>"
         "<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Ccircle cx='8' cy='8' r='6' fill='%23f0b429'/%3E%3C/svg%3E\">"
         "<style>" app-css "</style>"
         "<script type=\"module\" src=\"/datastar.js\"></script>"
         "<script>" sticky-scroll-js "</script>"
         "</head><body>"
         "<div id=\"seon-app\" class=\"app-shell\" data-signals=\"{showThinking: false}\" data-thinking=\"closed\" data-attr:data-thinking=\"$showThinking ? 'open' : 'closed'\">"
         header
         "<div class=\"app-body\">"
         sidebar
         "<div class=\"main-col\">"
         "<main id=\"seon-main\" class=\"stage-scroll\">"
         view
         "</main>"
         "<div id=\"seon-bottom-dock\" class=\"bottom-dock\">"
         stream
         composer
         "</div>"
         "</div></div></div>"
         "<div style=\"display:none\" data-init=\"@get('/events', {retry: 'always', retryMaxCount: Infinity, openWhenHidden: true})\"></div>"
         "</body></html>")))

(defn ^:seon.flow/edge start-http-listener! [port]
  (let [delegate (volatile! nil)
        latest-page (volatile! nil)
        sse-tabs (volatile! {})
        tab-seq (volatile! 0)
        pending (volatile! [])
        flushing? (volatile! false)
        flush-now! (fn []
                     (let [chunks @pending]
                       (vreset! pending [])
                       (when (seq chunks)
                         (let [merged (apply str chunks)]
                           (vswap! sse-tabs
                                   (fn [tabs]
                                     (reduce-kv
                                      (fn [acc tid ch]
                                        (if (a/offer! ch merged)
                                          (assoc acc tid ch)
                                          (do (a/close! ch) acc)))
                                      {}
                                      tabs)))))))
        schedule-flush! (fn []
                          (when (not @flushing?)
                            (vreset! flushing? true)
                            (a/go
                              (a/<! (a/timeout 8))
                              (vreset! flushing? false)
                              (flush-now!))))
        handler (fn [req]
                  (let [m (:request-method req)
                        uri (:uri req)]
                    (cond
                      (= [:get "/events"] [m uri])
                      (let [ch (a/chan 64)
                            tid (vswap! tab-seq inc)]
                        (vswap! sse-tabs assoc tid ch)
                        (when-let [d @delegate]
                          ((:on-new-tab! d) ch))
                        {:status 200
                         :headers {"Content-Type" "text/event-stream"
                                   "Cache-Control" "no-cache"
                                   "Connection" "keep-alive"}
                         :body ch})

                      (and (= m :get) (parse-deep-link uri))
                      (let [dl (parse-deep-link uri)]
                        (if (= :current dl)
                          (if-let [html @latest-page]
                            {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html}
                            {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon booting..."})
                          (if-let [d @delegate]
                            (let [html ((:sync-deep-link! d) dl)]
                              {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html})
                            {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon rebooting..."})))

                      :else
                      (if-let [d @delegate]
                        ((:handle d) req)
                        {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon rebooting..."}))))
        srv (when (not= 0 port)
              (adapter/run-server handler {:port (if (neg? port) 0 port) :host "127.0.0.1"}))
        bound-port (if srv (:port srv) 0)]
    {:server srv
     :port bound-port
     :handler handler
     :set-page! (fn [^String html] (vreset! latest-page html))
     :broadcast! (fn [^String chunk]
                   (when (and (seq @sse-tabs) (not (str/blank? chunk)))
                     (vswap! pending conj chunk)
                     (schedule-flush!)))
     :tab-count (fn [] (count @sse-tabs))
     :set-delegate! (fn [d]
                      (vreset! delegate d)
                      (when d ((:on-delegate! d))))
     :stop! (fn []
              (doseq [[_ ch] @sse-tabs] (a/close! ch))
              (vreset! sse-tabs {})
              (when srv
                (try (adapter/stop-server srv {:drain-timeout-ms 1500}) (catch Throwable _))))}))

(defn- walk->summary [walk]
  (mapv (fn [uid]
          (let [u (get-in walk [:units uid])]
            {:id uid :reads-count (:reads-count u 0) :reads-idx (:reads-idx u)}))
        (remove #(= "seon-stream-unit" %) (:order walk))))

(defn- advance-walk-and-broadcast!
  ([st tx-datoms] (advance-walk-and-broadcast! st tx-datoms nil))
  ([st tx-datoms reason]
   (let [t0 (System/nanoTime)
         db @(:conn st)
         specs (ui/page-unit-specs st)
         {:keys [walk patches render-errors]} (render/step-walk (:walk st) db tx-datoms specs)
         ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
         fault-reason? (and (vector? reason) (#{:fault :retract-fault :resolve-fault} (first reason)))
         st1 (cond-> (-> st (assoc :walk walk) (update :frames inc))
               (not= tx-datoms []) (assoc :walk-summary (walk->summary walk))
               (seq render-errors) (update :errors #(vec (take 20 (concat render-errors %)))))
         st2 (if (and (seq patches) (not fault-reason?) (:server-in-ch st1))
               (let [{:keys [view-mode]} (ui/user-view-state db)
                     fid (hasch/uuid [:perf :slow-render (or view-mode :canvas)])
                     prev (get-in st1 [:perf-alerts fid])]
                 (cond
                   (and (> ms 40.0) (or (nil? prev) (> ms (* 1.25 (:ms prev 0.0)))))
                   (do (a/put! (:server-in-ch st1)
                               {:req {:op :record-fault
                                      :id fid
                                      :class :slow-render
                                      :severity :warning
                                      :proc :web
                                      :ns 'seon.render.ui
                                      :subject (str "seon.render.ui/render:" (name (or view-mode :canvas)))
                                      :message (str "Slow UI render on :" (name (or view-mode :canvas))
                                                    " view: " ms " ms (> 40 ms frame budget).")}})
                       (assoc-in st1 [:perf-alerts fid] {:ms ms}))

                   (and (<= ms 25.0) prev)
                   (do (a/put! (:server-in-ch st1)
                               {:req {:op :retract-fault :id fid}})
                       (update st1 :perf-alerts dissoc fid))

                   :else st1))
               st1)]
     (when-let [sp (:set-page! st2)]
       (sp (page-shell walk)))
     (when (and (seq patches) (:broadcast! st2))
       ((:broadcast! st2) (apply str patches)))
     st2)))

(defn- launch-async-server-op! [server-in-ch ui-ch {:keys [req slot format-fn]}]
  (let [rch (a/chan 1)]
    (a/put! server-in-ch {:req req :reply-ch rch})
    (a/go
      (let [[ans _] (a/alts! [rch (a/timeout 30000)])]
        (when slot
          (let [val (if format-fn (format-fn (or ans {:ok? false :error :timeout})) (or ans {:ok? false :error :timeout}))]
            (a/put! ui-ch {:ev :action-done :slot slot :val val})))))))

(defn web-proc
  ([] {:params {:port "HTTP port"
                :listener "Persistent HTTP listener map"
                :conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository dir"
                :store-path "LMDB store path"
                :fork-parent-pid "PID of 1-thread Fork Parent"
                :fork-parent-ceiling "Chez GC ceiling of Fork Parent"
                :server-in-ch "Channel to :server"
                :report-chan "Flow report channel"
                :error-chan "Flow error channel"
                :on-power-loss "0-arg callback for Power-Loss reboot"
                :boot-seq "Boot sequence integer"}
       :ins {:db "Commit notices {:commit :tx :tx-data :reason} from :transactor"
             :stream "120fps live token & agent stream deltas from :model"
             :exits "Agent turn/branch completion notices from :agents"
             :snap "Latest live WKWebView viewport screenshot `{:snapshot}` from :browser"}
       :outs {}
       :workload :io
       :doc "100% DB-driven inside-out 120fps Datastar render proc, Reagent /call dispatcher & /mcp endpoint."
       :ping-map-fn (fn [st]
                      {:port (:bound-port st)
                       :tabs (if-let [tc (:tab-count st)] (tc) 0)
                       :frames (:frames st)})})
  ([{:keys [port listener conn cfg repo-dir store-path fork-parent-pid fork-parent-ceiling
            server-in-ch report-chan error-chan on-power-loss boot-seq]}]
   (let [ui-ch (a/chan 128)
         own-listener (when (and (nil? listener) (pos? (or port 0)))
                        (start-http-listener! port))
         eff-listener (or listener own-listener)
         st-init {:conn conn
                  :cfg cfg
                  :repo-dir repo-dir
                  :store-path store-path
                  :fork-parent-pid fork-parent-pid
                  :fork-parent-ceiling fork-parent-ceiling
                  :server-in-ch server-in-ch
                  :ui-ch ui-ch
                  :on-power-loss on-power-loss
                  :boot-seq (or boot-seq 1)
                  :bound-port (:port eff-listener 0)
                  :own-listener own-listener
                  :set-page! (:set-page! eff-listener)
                  :broadcast! (:broadcast! eff-listener)
                  :tab-count (:tab-count eff-listener)
                  :walk (render/empty-walk)
                  :live-streams {}
                  :agent-states {}
                  :branch-renders {}
                  :viewport-screenshot nil
                  :reports []
                  :errors []
                  :frames 0}
         st0 (advance-walk-and-broadcast! st-init nil)]
     (when eff-listener
       ((:set-delegate! eff-listener)
        {:on-delegate! (fn [] (a/put! ui-ch {:ev :broadcast-keyframe}))
         :on-new-tab!  (fn [ch] (a/put! ui-ch {:ev :new-tab :ch ch}))
         :sync-deep-link! (fn [show-op]
                            (call-server-edge! server-in-ch show-op 5000)
                            (let [res (render/step-walk (render/empty-walk) @conn nil
                                                        (ui/page-unit-specs st0))]
                              (page-shell (:walk res))))
         :handle
         (fn [req]
           (let [m (:request-method req)
                 uri (:uri req)]
             (cond
               (and (= m :get) (= uri "/datastar.js"))
               {:status 200
                :headers {"Content-Type" "text/javascript; charset=utf-8" "Cache-Control" "public, max-age=3600"}
                :body @datastar-js-text}

               (and (= m :post) (= uri "/op"))
               (let [body-str (if (string? (:body req)) (:body req) (slurp (:body req)))
                     edn-req (edn/read-string body-str)
                     ans (call-server-edge! server-in-ch edn-req 30000)]
                 {:status 200
                  :headers {"Content-Type" "application/edn; charset=utf-8"}
                  :body (pr-str ans)})

               (and (= m :post) (= uri "/mcp"))
               (let [body-str (if (string? (:body req)) (:body req) (slurp (:body req)))]
                 (handle-mcp-jsonrpc! server-in-ch body-str))

               (and (= m :post) (= uri "/call"))
               (let [qmap (parse-kv-pairs (:query-string req))
                     form-fields (parse-form-body req)
                     call-form (when-let [fs (:form qmap)]
                                 (try (edn/read-string fs) (catch Throwable _ nil)))
                     hkey (:h qmap)]
                 (if (and (nil? hkey)
                          (or (not (and (seq? call-form) (qualified-symbol? (first call-form))))
                              (let [v (resolve (first call-form))]
                                (not (and v (:seon/callable (meta v)))))))
                   {:status 403
                    :headers {"Content-Type" "application/edn; charset=utf-8"}
                    :body (pr-str {:ok? false :error :seon.web/not-callable :form call-form})}
                   (do (a/put! ui-ch {:ev :call :hkey hkey :call-form call-form :form-fields form-fields})
                       {:status 200
                        :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
                        :body ""})))

               :else
               {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"})))}))
     (assoc st0
            ::flow/in-ports (cond-> {:ui ui-ch}
                              report-chan (assoc :report report-chan)
                              error-chan  (assoc :error error-chan)))))
  ([st transition]
   (when (and (= transition ::flow/stop) (:own-listener st))
     ((:stop! (:own-listener st))))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :report
       (let [st1 (update st :reports #(vec (take 30 (cons msg %))))]
         [(advance-walk-and-broadcast! st1 []) nil])

       :error
       (let [st1 (update st :errors #(vec (take 20 (cons msg %))))]
         (binding [*out* *err*]
           (println "[FLOW ERROR]" (pr-str msg))
           (flush))
         [(advance-walk-and-broadcast! st1 []) nil])

       :snap
       (let [st1 (assoc st :viewport-screenshot (:snapshot msg))
             view-mode (:view-mode (ui/user-view-state @(:conn st1)))]
         (if (= view-mode :debug)
           [(advance-walk-and-broadcast! st1 []) nil]
           [st1 nil]))

       :stream
       (let [{:keys [agent-id status text]} msg
             st1 (case status
                   ;; Keep card calmly mounted while tool calls execute — `:turn-idle` tears it down cleanly!
                   :done
                   (if (contains? (:live-streams st) agent-id)
                     (update-in st [:live-streams agent-id]
                                (fn [prev]
                                  (assoc prev :status :running-tools)))
                     st)

                   :thinking
                   (-> st
                       (update-in [:live-streams agent-id]
                                  (fn [prev]
                                    (let [prev-r (str (:reasoning prev ""))]
                                      {:status :thinking
                                       :text ""
                                       :reasoning (if (str/blank? prev-r)
                                                    ""
                                                    (str prev-r "\n\n── next step ──\n"))})))
                       (assoc-in [:agent-states agent-id] :active))

                   :reasoning-token
                   (-> st
                       (update-in [:live-streams agent-id]
                                  (fn [prev]
                                    {:status :reasoning
                                     :text (or (:text prev) "")
                                     :reasoning (str (or (:reasoning prev) "") (or text ""))}))
                       (cond-> (not= :active (get-in st [:agent-states agent-id]))
                         (assoc-in [:agent-states agent-id] :active)))

                   ;; `:token` or other live status delta — keeps `:agent-states` unchanged (`=`) so 0 DB seeks!
                   (-> st
                       (update-in [:live-streams agent-id]
                                  (fn [prev]
                                    {:status status
                                     :reasoning (or (:reasoning prev) "")
                                     :text (str (or (:text prev) "") (or text ""))}))
                       (cond-> (not= :active (get-in st [:agent-states agent-id]))
                         (assoc-in [:agent-states agent-id] :active))))]
         [(advance-walk-and-broadcast! st1 []) nil])

       :exits
       (let [aid (:agent-id msg)]
         (case (:ev msg)
           (:forked :turn-busy)
           (let [st1 (assoc-in st [:agent-states aid] :active)]
             [(advance-walk-and-broadcast! st1 []) nil])

           :turn-idle
           (let [st1 (-> st
                         (update :agent-states dissoc aid)
                         (update :live-streams dissoc aid))]
             [(advance-walk-and-broadcast! st1 nil) nil])

           :exited
           (let [st1 (-> st
                         (update :agent-states dissoc aid)
                         (update :live-streams dissoc aid))]
             [(advance-walk-and-broadcast! st1 []) nil])

           :branch-render
           (let [st1 (assoc-in st [:branch-renders aid]
                               (select-keys msg [:branch :branch-tx :render-fn :hiccup :error]))]
             [(advance-walk-and-broadcast! st1 nil) nil])

           :branch-moved
           [(advance-walk-and-broadcast! st nil) nil]

           [st nil]))

       :db
       (let [reason (:reason msg)
             tx-ds (:tx-data msg)
             code-or-merge? (or (= :merge reason)
                                (and (vector? reason) (= :merge (first reason)))
                                (some #(#{:seon.code.form/src :seon.agent/render-fn} (:a %)) tx-ds))]
         [(advance-walk-and-broadcast! st (if code-or-merge? nil tx-ds) reason) nil])

       :ui
       (case (:ev msg)
         :new-tab
         (let [ch (:ch msg)
               kf (render/datastar-patch-event (render/compose-keyframe (:walk st)))]
           (a/offer! ch kf)
           [st nil])

         :broadcast-keyframe
         (let [st1 (advance-walk-and-broadcast! st nil)
               kf (render/datastar-patch-event (render/compose-keyframe (:walk st1)))]
           (when (:broadcast! st1)
             ((:broadcast! st1) kf))
           [st1 nil])

         :action-done
         (let [{:keys [slot val]} msg
               st1 (assoc st slot val)]
           [(advance-walk-and-broadcast! st1 nil) nil])

         :call
         (let [res (invoke-call! st msg)]
           (if-not (:ok? res)
             (let [st1 (update st :errors #(vec (take 20 (cons (dissoc res :st) %))))]
               [(advance-walk-and-broadcast! st1 []) nil])
             (let [st1 (:st res)
                   _ (when-let [async-spec (:async res)]
                       (launch-async-server-op! (:server-in-ch st1) (:ui-ch st1) async-spec))]
               [(advance-walk-and-broadcast! st1 []) nil])))

         [st nil])))))

(defn ^:seon/callable chat-agent-form!
  ([st form] (chat-agent-form! st (ui/entry-agent-id @(:conn st)) form))
  ([st agent-id form]
   (let [db @(:conn st)
         txt (str/trim (str (:text form "")))
         aid (or agent-id (ui/entry-agent-id db))
         br (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id aid]))
                :branch/repl)]
     (cond
       (str/blank? txt) {:st st}

       (or (str/starts-with? txt "(") (str/starts-with? txt "["))
       (async-action
        (assoc st :branch-feedback nil)
        {:op :eval :branch br :agent-id aid :code txt :keep-warm? true}
        :branch-feedback
        (fn [ans]
          (when-not (:ok? ans)
            {:ok? false :text (str "Eval error: " (:error ans))})))

       :else
       (do
         (when (:as-of-tx (ui/user-view-state db))
           (a/put! (:server-in-ch st) {:req {:op :show-user :as-of-tx :live}}))
         (async-action
          (assoc st :branch-feedback nil)
          {:op :send-message :from :user :to aid :type :chat :body txt}
          nil nil))))))
