/* Phylax TV 0.2: Frigate 0.18 desktop adapter. No React internals or server config writes.
 * Android consumes remote events; exactly one scope below handles each command.
 */
(() => {
  'use strict';
  if (window.PhylaxTV) { window.PhylaxTV.refresh(); return; }
  const SELECTORS = 'button,a[href],input:not([type=hidden]),select,textarea,[role=button],[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=switch],[role=radio],[role=combobox],[role=slider],[data-camera],.cursor-pointer';
  const MODALS = '[role=dialog],[role=alertdialog],[role=menu],[role=listbox]';
  const state = { stack: [], active: null, opener: null, suspended: false,
    restore: null, lastCard: null, lastContent: null, editing: null, custom: null,
    config: null, base: null, group: null, rootBack: false, pending: '', timer: 0,
    nav: null, ready: false, bootstrap: false, route: location.pathname + location.hash };
  const style = document.createElement('style');
  style.textContent = `
    [data-tv-focus]:focus { outline:4px solid #ffb300!important; outline-offset:-3px!important; box-shadow:0 0 0 2px #101820!important; }
    [data-tv-hidden] { display:none!important; }
    #tv-nav { position:fixed;top:0;left:0;right:0;height:58px;z-index:35;display:flex;align-items:center;gap:12px;padding:6px 20px;background:#122338;color:#fff;font:18px sans-serif; }
    #tv-nav button,#tv-dialog button { border:1px solid #53677d;background:#223b53;color:#fff;border-radius:8px;padding:8px 18px;min-height:42px; }
    #tv-title { margin-left:auto;max-width:38%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:15px; }
    #tv-dialog-shade { position:fixed;inset:0;background:#000b;z-index:10000;display:grid;place-items:center; }
    #tv-dialog { background:#14283e;color:#fff;border:1px solid #58708b;border-radius:14px;padding:24px;width:min(650px,85vw);max-height:82vh;overflow:auto;font:18px sans-serif; }
    #tv-dialog h2 { font-size:24px;margin:0 0 16px; } #tv-dialog button { display:block;width:100%;margin:8px 0;text-align:left; }
    #tv-hint { position:fixed;bottom:10px;left:50%;transform:translateX(-50%);background:#000d;color:#fff;padding:9px 16px;border-radius:8px;z-index:11000;pointer-events:none;font:16px sans-serif; }
    body[data-tv-viewer] aside { display:none!important; }
    body[data-tv-viewer] #root { padding-top:58px;box-sizing:border-box; }
    [role=dialog] { max-width:85vw!important; } [role=dialog] button { min-height:36px; }
    [data-tv-icon] { min-width:36px;min-height:36px;display:flex;align-items:center;justify-content:center; }
  `;
  document.head.append(style);

  const arr = (root, selector) => Array.from(root.querySelectorAll(selector));
  function shown(el, allowOffscreen = false) {
    if (!(el instanceof Element) || !el.isConnected || el.closest('[hidden],[inert],[data-tv-hidden],[aria-hidden=true]')) return false;
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
    const s = getComputedStyle(el), r = el.getBoundingClientRect();
    if (s.display === 'none' || s.visibility === 'hidden' || Number(s.opacity) < .05 || r.width < 3 || r.height < 3) return false;
    if (allowOffscreen) return true;
    return r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  }
  function hint(text) {
    let h = document.getElementById('tv-hint');
    if (!h) { h = document.createElement('div'); h.id = 'tv-hint'; document.body.append(h); }
    h.textContent = text; clearTimeout(hint.timer); hint.timer = setTimeout(() => h.remove(), 2500);
  }
  function label(el) { return (el.getAttribute('aria-label') || el.textContent || el.getAttribute('title') || '').trim(); }
  function focus(el) {
    if (!el || !shown(el, true)) return false;
    el.setAttribute('data-tv-focus', '');
    if (!el.matches('button,input,select,textarea,a[href]')) el.tabIndex = 0;
    el.focus({preventScroll:true});
    el.scrollIntoView({block:'nearest',inline:'nearest'});
    state.active = el;
    if (!el.closest('#tv-nav') && scope() === document) state.lastContent = el;
    return document.activeElement === el;
  }
  function z(el) {
    let value = 0;
    for (let p=el;p && p!==document.body;p=p.parentElement) value = Math.max(value,parseInt(getComputedStyle(p).zIndex)||0);
    return value;
  }
  function scope() {
    if (state.custom) return state.custom;
    const list = arr(document, MODALS).filter(el => shown(el) && el.getAttribute('data-state') !== 'closed');
    // Portal popups can be siblings of their dialogs. Z order first, DOM order breaks ties.
    list.sort((a,b) => z(a)-z(b));
    return list.at(-1) || document;
  }
  function collect(root = scope()) {
    let list = arr(root, SELECTORS).filter(el => {
      if (!shown(el,true) || el.closest('aside')) return false;
      if (el.matches('input[type=checkbox]') && el.getAttribute('aria-hidden') === 'true') return false;
      // A camera is a single selectable card. Its decorative nested divs aren't controls.
      if (el.closest('[data-camera]') !== el && el.closest('[data-camera]') && !el.matches('button,a,input')) return false;
      if (el.matches('.cursor-pointer') && el.querySelector('button,input,select,[role=switch],[role=checkbox]')) return false;
      return true;
    });
    // Frigate IconWrapper puts the pointer class on its SVG child, onClick on the parent div.
    arr(root,'svg.cursor-pointer').forEach(svg => {
      const parent = svg.parentElement;
      if (parent && !parent.closest('button,a,[data-camera]') && shown(parent,true) && !parent.closest('aside')) {
        parent.dataset.tvIcon = ''; list.push(parent);
      }
    });
    list = [...new Set(list.map(el => el instanceof SVGElement ? el.parentElement : el))];
    return list.filter(el => {
      if (root !== document && !root.contains(el)) return false;
      if (root === document && el.closest(MODALS)) return false;
      return !el.closest('[role=tooltip]');
    });
  }
  function first(root) {
    const list=collect(root);
    return list.find(el=>el.getAttribute('aria-selected')==='true') || list.find(el=>shown(el)) || list[0];
  }
  function syncScope() {
    const top=scope(), old=state.stack.at(-1);
    if (!old || old.root!==top) {
      const existing=state.stack.findIndex(x=>x.root===top);
      let target=null;
      if (existing>=0) {
        const removed=state.stack.splice(existing+1);
        target=removed[0]?.returnTo;
      } else {
        state.stack.push({root:top,returnTo:state.opener || state.active,dirty:false});
      }
      state.opener=null;
      state.editing=null;
      if (top!==document || old) {
        requestAnimationFrame(()=> {
          if (scope()!==top || state.suspended) return;
          focus(target && shown(target,true) && (top===document || top.contains(target)) ? target : first(top));
        });
      }
    }
    return top;
  }
  function dispatchKey(el,key) {
    const code={Escape:27,Enter:13,' ':32,ArrowUp:38,ArrowDown:40,ArrowLeft:37,ArrowRight:39}[key]||0;
    for (const type of ['keydown','keyup']) el.dispatchEvent(new KeyboardEvent(type,{key,code:key===' '?'Space':key,keyCode:code,which:code,bubbles:true,cancelable:true}));
  }
  function activate(el) {
    if (!el || !shown(el,true)) return;
    state.opener=el;
    if (el.matches('[role=combobox],[aria-haspopup=menu]')) {
      // Radix Select/Menu opens via keyboard; .click() is not its opening contract.
      dispatchKey(el,'Enter');
    } else if (el.matches('[role=option],[role=menuitem]')) {
      dispatchKey(el,'Enter');
    } else {
      el.click();
    }
    setTimeout(refresh,40);
  }
  function topFrame() { return state.stack.at(-1); }
  function markDirty(e) {
    if (!e.isTrusted && e.target.closest('#tv-dialog')) return;
    const frame=topFrame();
    if (frame && frame.root!==document && frame.root.matches('[role=dialog]')) frame.dirty=true;
  }
  document.addEventListener('input',markDirty,true);
  document.addEventListener('change',markDirty,true);

  function closeLayer(force=false) {
    const root=scope();
    if (state.custom) { closeDialog();return true; }
    if (root===document) return false;
    if (!force && topFrame()?.dirty) {
      showDialog('尚未儲存的修改',[
        ['繼續編輯',()=>closeDialog()],
        ['放棄修改',()=>{closeDialog();closeLayer(true);}]
      ]);return true;
    }
    const cancel=arr(root,'button').find(el=>/^(取消|Cancel|關閉|Close|返回|Back)$/i.test(label(el)) && shown(el,true));
    if (root.matches('[role=dialog],[role=alertdialog]') && cancel) activate(cancel);
    else {
      const target=document.activeElement && root.contains(document.activeElement) ? document.activeElement : root;
      const stop=e=>e.stopPropagation();
      // Radix handles Escape in document capture. Stop bubble before Frigate page shortcuts.
      root.addEventListener('keydown',stop,{once:true});root.addEventListener('keyup',stop,{once:true});
      dispatchKey(target,'Escape');
      root.removeEventListener('keydown',stop);root.removeEventListener('keyup',stop);
    }
    setTimeout(()=> {
      refresh();
      if (scope()===root) hint('請選擇此視窗的取消或關閉按鈕');
    },180);
    return true; // Never fall through to browser Back when a modal is still present.
  }
  function closeDialog() {
    const target=state.custom?._returnTo;
    document.getElementById('tv-dialog-shade')?.remove();state.custom=null;
    syncScope(); if (target) focus(target);
  }
  function showDialog(title,items) {
    if (state.custom) closeDialog();
    const shade=document.createElement('div');shade.id='tv-dialog-shade';
    const box=document.createElement('div');box.id='tv-dialog';box.setAttribute('role','dialog');box.setAttribute('aria-modal','true');
    box._returnTo=document.activeElement;const heading=document.createElement('h2');heading.textContent=title;box.append(heading);
    items.forEach(([text,action])=>{const b=document.createElement('button');b.textContent=text;b.onclick=action;box.append(b);});
    shade.append(box);const host=scope();(host===document?document.body:host).append(shade);state.custom=box;syncScope();focus(box.querySelector('button'));
  }
  function move(dir) {
    const root=syncScope();
    const list=collect(root), current=document.activeElement;
    if (!list.includes(current)) { focus(first(root));return; }
    if (state.editing===current) {
      const stop=e=>e.stopPropagation();
      current.addEventListener('keydown',stop);current.addEventListener('keyup',stop);
      dispatchKey(current,{left:'ArrowLeft',right:'ArrowRight',up:'ArrowUp',down:'ArrowDown'}[dir]);
      current.removeEventListener('keydown',stop);current.removeEventListener('keyup',stop);return;
    }
    const r=current.getBoundingClientRect(), cx=r.x+r.width/2,cy=r.y+r.height/2;
    const horizontal=dir==='left'||dir==='right';
    let best=null,score=Infinity;
    for (const el of list) {
      if (el===current) continue;
      const t=el.getBoundingClientRect(),dx=t.x+t.width/2-cx,dy=t.y+t.height/2-cy;
      if ((dir==='left'&&dx>=-3)||(dir==='right'&&dx<=3)||(dir==='up'&&dy>=-3)||(dir==='down'&&dy<=3)) continue;
      const overlap=horizontal ? Math.min(r.bottom,t.bottom)-Math.max(r.top,t.top) : Math.min(r.right,t.right)-Math.max(r.left,t.left);
      const primary=horizontal?Math.abs(dx):Math.abs(dy), secondary=horizontal?Math.abs(dy):Math.abs(dx);
      const candidate=primary+secondary*2+(overlap>0?0:2000);
      if (candidate<score) {score=candidate;best=el;}
    }
    if (root===document && current.closest('#tv-nav') && dir==='down' && state.lastContent && shown(state.lastContent,true)) best=state.lastContent;
    if (best) focus(best);
  }
  function basePath() {
    if (state.base) return state.base;
    const a=document.querySelector('aside a[href]');
    if (a) state.base=new URL(a.href).pathname;
    return state.base;
  }
  function route(path) {
    const base=basePath();if (!base) {hint('Frigate 尚未載入，請稍候');return;}
    const target=base.replace(/\/?$/,'/')+path;
    const a=arr(document,'aside a[href]').find(el=>new URL(el.href).pathname===target);
    if (a) { a.click();setTimeout(refresh,100); }
    else location.assign(target);
  }
  function groupButtons() {
    const sidebar=document.querySelector('aside');if (!sidebar) return [];
    // The group strip is the flex-column container holding an all-cameras button,
    // zero or more group buttons, then the edit-groups pencil button (0.18 desktop).
    return arr(sidebar,'div.flex-col').map(el=>Array.from(el.children).filter(x=>x.tagName==='BUTTON'))
      .sort((a,b)=>b.length-a.length)[0]||[];
  }
  async function loadConfig() {
    const base=basePath();if (!base) return;
    try {
      const res=await fetch(base.replace(/\/?$/,'/')+'api/config',{credentials:'same-origin'});
      if (!res.ok) return;
      state.config=await res.json();
    } catch (_) { /* keep existing UI usable if the config request fails */ }
  }
  function groups() {
    return Object.entries(state.config?.camera_groups||{}).sort((a,b)=>a[1].order-b[1].order);
  }
  function chooseGroup(name) {
    if (state.custom) closeDialog();
    state.group=name;localStorage.setItem('phylax.tv.group',name);
    if (location.pathname===basePath() && !location.hash) {
      const all=groups(),buttons=groupButtons(),idx=all.findIndex(x=>x[0]===name);
      if (idx>=0 && buttons.length===all.length+2) {
        buttons[idx+1].click();state.lastCard=null;setTimeout(()=>{refresh();focus(document.querySelector('[data-camera]'));},120);return;
      }
    }
    location.assign(basePath()+'?group='+encodeURIComponent(name));
  }
  function editGroups() {
    closeDialog();
    if (location.pathname!==basePath() || location.hash) {
      sessionStorage.setItem('phylax.tv.edit','1');route('');return;
    }
    const buttons=groupButtons();
    const edit=buttons.at(-1);
    if (edit && edit.querySelector('svg') && buttons.length>=2) activateHidden(edit);
    else hint('此帳號沒有群組編輯入口，或頁面尚未載入');
  }
  function activateHidden(el) {state.opener=state.nav?.querySelector('button');el.click();setTimeout(refresh,80);}
  async function openGroups() {
    await loadConfig();
    const items=groups().map(([name])=>[name,()=>chooseGroup(name)]);
    items.push(['新增／編輯群組',editGroups],['取消',closeDialog]);
    showDialog('群組',items);
  }
  function addNav() {
    if (state.nav || !document.querySelector('aside')) return;
    basePath();document.body.dataset.tvViewer='';
    const nav=document.createElement('div');nav.id='tv-nav';
    const actions=[['群組',openGroups],['回放',()=>route('review')],['App 設定',()=>{state.pending='settings';}]];
    for (const [text,fn] of actions) {const b=document.createElement('button');b.textContent=text;b.onclick=fn;nav.append(b);}
    const title=document.createElement('span');title.id='tv-title';nav.append(title);document.body.append(nav);state.nav=nav;
    // Hide the sidebar's reserved 52px gutter without changing the Frigate component tree.
    const sidebar=document.querySelector('aside');
    const content=sidebar?.parentElement?.querySelector(':scope > div.absolute');
    if(content) {content.style.top='58px';content.style.left='0';}
    const main=document.querySelector('main');if (main) {main.style.marginLeft='0';main.style.paddingLeft='8px';}
    loadConfig().then(()=> {
      const list=groups();
      const saved=localStorage.getItem('phylax.tv.group');
      const live=history.state?.usr?.cameraGroup;
      state.group=list.some(x=>x[0]===live)?live:list.some(x=>x[0]===saved)?saved:list[0]?.[0];
      if (!state.bootstrap && location.pathname===basePath() && !location.hash) {
        state.bootstrap=true;
        if (sessionStorage.getItem('phylax.tv.edit')) {sessionStorage.removeItem('phylax.tv.edit');editGroups();}
        else if (live && live!=='default') localStorage.setItem('phylax.tv.group',live);
        else if (state.group && !new URLSearchParams(location.search).has('group')) chooseGroup(state.group);
        else if (!state.group) showDialog('尚未建立群組',[['新增群組',editGroups],['App 設定',()=>{state.pending='settings';}],['取消',closeDialog]]);
      }
    });
  }
  function applyAdapter() {
    addNav();
    // Only navigation links to management pages are hidden. Streaming gear buttons stay.
    arr(document,'a[href]').forEach(a=> {
      const u=new URL(a.href,location.href),base=basePath();
      if (base && u.origin===location.origin && /^(settings|config|system|logs)(\/|$)/.test(u.pathname.slice(base.length))) a.dataset.tvHidden='';
    });
    arr(document,'button[aria-label="Enter layout editing mode"]').forEach(el=>el.dataset.tvHidden='');
    arr(document,'[data-camera]').forEach(el=> {el.tabIndex=0;el.setAttribute('role','button');el.dataset.tvFocus='';});
    if (state.nav) document.getElementById('tv-title').textContent=document.title;
    const camera=location.hash.slice(1);
    if (state.lastCard && !camera && location.pathname===basePath() && state.route!==location.pathname+location.hash) {
      const el=arr(document,'[data-camera]').find(x=>x.dataset.camera===state.lastCard);if(el) focus(el);
    }
    state.route=location.pathname+location.hash;
  }
  function refresh() {
    if (state.suspended) return;
    applyAdapter();syncScope();
    if (!document.activeElement || document.activeElement===document.body) {
      const restore=state.restore&&arr(document,'[data-camera]').find(x=>x.dataset.camera===state.restore);
      focus(restore || first(scope()));state.restore=null;
    }
  }
  function back() {
    if (state.editing) {state.editing=null;hint('已結束調整');return 'handled';}
    if (closeLayer()) return 'handled';
    if (document.fullscreenElement) {document.exitFullscreen();return 'handled';}
    if (location.hash || location.pathname!==basePath()) {
      state.restore=state.lastCard;
      if (history.state?.idx>0) history.back();else route('');
      setTimeout(refresh,120);return 'handled';
    }
    if (!document.activeElement?.closest('#tv-nav')) {focus(state.nav?.querySelector('button'));state.rootBack=true;return 'handled';}
    return 'exit';
  }
  function playback(cmd) {
    const video=arr(document,'video').find(el=>shown(el));
    if (!video) {hint('請先開啟一段錄影');return;}
    if(cmd==='play') {if(video.paused) video.play().catch(()=>{});else video.pause();}
    else if (location.pathname!==basePath() && Number.isFinite(video.duration)) video.currentTime=Math.max(0,Math.min(video.duration,video.currentTime+(cmd==='forward'?10:-10)));
  }
  function key(command) {
    if (state.suspended) return 'handled';
    refresh();
    if (command==='back') return back();
    if (command==='menu') {if(scope()===document) focus(state.nav?.querySelector('button'));return 'handled';}
    if (['up','down','left','right'].includes(command)) move(command);
    if (['play','forward','rewind'].includes(command)) playback(command);
    if(command==='ok') {
      const el=document.activeElement;
      if(!el || !collect().includes(el)) {focus(first(scope()));return 'handled';}
      if (el.matches('input:not([type=checkbox]):not([type=radio]):not([type=range]),textarea')) {el.focus();el.click();return 'ime';}
      if(el.matches('[role=slider],input[type=range]')) {state.editing=state.editing===el?null:el;hint(state.editing?'左右調整，OK 或返回結束':'已結束調整');return 'handled';}
      if(el.dataset.camera) {state.lastCard=el.dataset.camera;state.restore=el.dataset.camera;}
      if(el.matches('[role=switch],[role=checkbox]') && topFrame()?.root!==document) topFrame().dirty=true;
      activate(el);
    }
    const result=state.pending||'handled';state.pending='';
    if(result==='settings') state.suspended=true;
    return result;
  }
  const observer=new MutationObserver(records=> {
    if(records.every(r=>r.target.closest?.('#tv-nav,#tv-hint'))) return;
    if(state.timer) return;
    state.timer=setTimeout(()=>{state.timer=0;refresh();},100);
  });
  observer.observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['data-state','aria-expanded','aria-hidden','disabled']});
  window.addEventListener('popstate',()=>setTimeout(refresh,50));
  window.addEventListener('resize',refresh);
  window.PhylaxTV={key,refresh:()=>{state.suspended=false;refresh();},debug:()=>({scope:scope()===document?'page':scope().getAttribute('role'),layers:state.stack.length,focus:label(document.activeElement),group:state.group})};
  refresh();
})();
