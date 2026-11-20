(ns seon.web.css
  "Presentation layer: the giant `app-css` stylesheet, sticky-scroll JS and the Datastar runtime script text. Pure data, zero IO."
  (:require [clojure.java.io :as io]
            [seon.store :as store]))

(def app-css
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
 .stack-xs{display:flex;flex-direction:column;gap:4px}
 .bounded-box{max-height:300px;overflow:auto}
 .preview-box{max-height:180px;overflow:hidden}
 .nested-fold{border:1px dashed var(--base-700);border-radius:5px;padding:4px 8px;background:rgba(255,255,255,.012)}
 .nested-summary{display:flex;align-items:center;gap:6px;cursor:pointer;list-style:none;font-family:var(--font-mono);font-size:11px;color:var(--text-400)}
 .nested-summary::-webkit-details-marker{display:none}
 .nested-summary::before{content:'▸';color:var(--text-500)}
 .nested-fold[open]>.nested-summary::before{content:'▾';color:var(--signal)}
 .nested-fold[open]>.nested-summary{color:var(--text-200);margin-bottom:6px}
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
 .data-table th,.data-table td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--base-800);vertical-align:top;overflow-wrap:break-word}
 .data-table th,.data-table .btn{white-space:nowrap}
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

(def datastar-js-text
  (delay
    (let [f (io/file store/checkout-root "resources/public/datastar.js")]
      (if (.isFile f) (slurp f) "// datastar.js missing"))))

(def sticky-scroll-js
  "(()=>{let t=null;const near=(el)=>(el.scrollHeight-el.scrollTop-el.clientHeight)<56;const snapAll=()=>{document.querySelectorAll('.ws-timeline,.live-reasoning,.live-prose').forEach((el)=>{const ag=el.dataset.agent||'';if(el._lastAgent!==ag){el._lastAgent=ag;el.dataset.userScrolled='0';}if(el.dataset.userScrolled!=='1'){el.scrollTop=el.scrollHeight;}});};document.addEventListener('scroll',(e)=>{const el=e.target;if(el&&el.classList&&(el.classList.contains('ws-timeline')||el.classList.contains('live-reasoning')||el.classList.contains('live-prose'))){el.dataset.userScrolled=near(el)?'0':'1';}},true);document.addEventListener('submit',(e)=>{const f=e.target;document.querySelectorAll('.ws-timeline,.live-reasoning,.live-prose').forEach((el)=>{el.dataset.userScrolled='0';});if(f&&f.querySelectorAll){setTimeout(()=>{f.querySelectorAll('textarea[name=text],textarea[name=code],input[name=ref]').forEach((i)=>{i.value='';});},0);}},true);window.addEventListener('resize',()=>{if(t)cancelAnimationFrame(t);t=requestAnimationFrame(snapAll);});const mo=new MutationObserver(()=>{if(t)cancelAnimationFrame(t);t=requestAnimationFrame(snapAll);});window.addEventListener('DOMContentLoaded',()=>{const app=document.getElementById('seon-app');if(app)mo.observe(app,{childList:true,subtree:true,characterData:true});snapAll();});})();")