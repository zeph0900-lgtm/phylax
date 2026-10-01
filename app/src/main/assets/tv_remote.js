(function () {
  if (window.__phylaxTvRemote && window.__phylaxTvRemote.version === "0.1") {
    window.__phylaxTvRemote.refresh();
    return;
  }

  const NATIVE = [
    "a[href]",
    "button",
    "input",
    "select",
    "textarea",
    "[role='button']",
    "[role='link']",
    "[role='tab']",
    "[role='menuitem']",
    "[role='option']",
    "[role='switch']",
    "[role='checkbox']",
    "[role='radio']",
    "[role='combobox']",
    "[role='slider']",
    "[tabindex]:not([tabindex='-1'])"
  ].join(",");

  const SEMANTIC = [
    "label[for]",
    "[aria-label]",
    "[aria-labelledby]",
    "[aria-haspopup]",
    "[aria-controls]",
    "[aria-expanded]",
    "[aria-checked]",
    "[aria-selected]",
    "[data-state]",
    "[data-radix-collection-item]",
    "[title]",
    "[onclick]",
    ".cursor-pointer"
  ].join(",");

  const POPUP = [
    "[role='dialog']",
    "[role='menu']",
    "[role='listbox']",
    "[role='alertdialog']",
    "[data-radix-popper-content-wrapper]",
    "[data-radix-menu-content]",
    "[data-radix-dialog-content]",
    "[data-radix-select-viewport]"
  ].join(",");

  let mode = "focus";
  let cached = [];
  let cachedScope = null;
  let refreshTimer = null;
  let cursorX = Math.round(window.innerWidth / 2);
  let cursorY = Math.round(window.innerHeight / 2);

  const style = document.createElement("style");
  style.id = "phylax-tv-style";
  style.textContent = [
    ".phylax-tv-focusable:focus {",
    "outline: 4px solid #ffb300 !important;",
    "outline-offset: -4px !important;",
    "box-shadow: inset 0 0 0 2px rgba(0,0,0,.75) !important;",
    "}",
    "#phylax-tv-cursor {",
    "position: fixed;",
    "width: 26px;",
    "height: 26px;",
    "margin-left: -13px;",
    "margin-top: -13px;",
    "border: 3px solid #ffb300;",
    "border-radius: 50%;",
    "box-shadow: 0 0 0 2px rgba(0,0,0,.8), 0 0 10px rgba(255,179,0,.8);",
    "pointer-events: none;",
    "z-index: 2147483647;",
    "display: none;",
    "}",
    "#phylax-tv-cursor::before, #phylax-tv-cursor::after {",
    "content: '';",
    "position: absolute;",
    "background: #ffb300;",
    "}",
    "#phylax-tv-cursor::before {",
    "left: 10px;",
    "top: -7px;",
    "width: 2px;",
    "height: 34px;",
    "}",
    "#phylax-tv-cursor::after {",
    "left: -7px;",
    "top: 10px;",
    "width: 34px;",
    "height: 2px;",
    "}",
    "#phylax-tv-badge {",
    "position: fixed;",
    "right: 18px;",
    "bottom: 18px;",
    "padding: 8px 12px;",
    "border-radius: 8px;",
    "background: rgba(0,0,0,.82);",
    "color: white;",
    "font: 14px sans-serif;",
    "z-index: 2147483646;",
    "pointer-events: none;",
    "opacity: 0;",
    "transition: opacity .15s;",
    "}"
  ].join("");
  document.head.appendChild(style);

  const cursor = document.createElement("div");
  cursor.id = "phylax-tv-cursor";
  document.body.appendChild(cursor);

  const badge = document.createElement("div");
  badge.id = "phylax-tv-badge";
  document.body.appendChild(badge);

  function showBadge(text, timeout) {
    badge.textContent = text;
    badge.style.opacity = "1";
    window.clearTimeout(showBadge.timer);
    showBadge.timer = window.setTimeout(function () {
      badge.style.opacity = "0";
    }, timeout || 1800);
  }

  function visible(el) {
    if (!el || !document.contains(el)) return false;
    if (el.disabled) return false;
    if (el.getAttribute("aria-disabled") === "true") return false;

    const s = window.getComputedStyle(el);
    if (s.display === "none" || s.visibility === "hidden") return false;
    if (parseFloat(s.opacity || "1") < 0.05) return false;
    if (s.pointerEvents === "none" && !el.matches("input,textarea")) return false;

    const r = el.getBoundingClientRect();
    return r.width > 5 &&
      r.height > 5 &&
      r.bottom > 0 &&
      r.right > 0 &&
      r.top < window.innerHeight &&
      r.left < window.innerWidth;
  }

  function depth(el) {
    let d = 0;
    let p = el;
    while (p && p !== document.body) {
      d += 1;
      p = p.parentElement;
    }
    return d;
  }

  function zIndex(el) {
    const raw = window.getComputedStyle(el).zIndex;
    const value = parseInt(raw || "0", 10);
    return Number.isFinite(value) ? value : 0;
  }

  function fixedFullscreenLayers() {
    const all = Array.prototype.slice.call(
      document.querySelectorAll("[class*='fixed'][class*='inset-0']")
    );
    return all.filter(function (el) {
      if (!visible(el)) return false;
      const s = window.getComputedStyle(el);
      if (s.position !== "fixed") return false;
      if (zIndex(el) < 40) return false;

      const r = el.getBoundingClientRect();
      const area = r.width * r.height;
      const viewportArea = window.innerWidth * window.innerHeight;
      return area > viewportArea * 0.45;
    });
  }

  function activeScope() {
    const popups = Array.prototype.slice.call(
      document.querySelectorAll(POPUP)
    ).filter(visible);

    if (popups.length) {
      popups.sort(function (a, b) {
        const za = zIndex(a);
        const zb = zIndex(b);
        if (za !== zb) return za - zb;
        return depth(a) - depth(b);
      });
      return popups[popups.length - 1];
    }

    const layers = fixedFullscreenLayers();
    if (!layers.length) return document;

    layers.sort(function (a, b) {
      const za = zIndex(a);
      const zb = zIndex(b);
      if (za !== zb) return za - zb;
      return depth(a) - depth(b);
    });
    return layers[layers.length - 1];
  }

  function nativeFocusable(el) {
    try {
      return el.matches(NATIVE);
    } catch (_) {
      return false;
    }
  }

  function semanticInteractive(el) {
    try {
      return el.matches(SEMANTIC);
    } catch (_) {
      return false;
    }
  }

  function looksInteractive(el) {
    if (!visible(el)) return false;
    if (nativeFocusable(el) || semanticInteractive(el)) return true;

    const tag = String(el.tagName || "").toLowerCase();
    if (!["div", "section", "article", "li", "span"].includes(tag)) {
      return false;
    }

    const cls = String(el.className || "");
    if (cls.indexOf("cursor-pointer") >= 0) return true;

    try {
      return window.getComputedStyle(el).cursor === "pointer";
    } catch (_) {
      return false;
    }
  }

  function priority(el) {
    if (nativeFocusable(el)) return 5;
    if (el.hasAttribute("aria-label")) return 4;
    if (semanticInteractive(el)) return 3;
    if (String(el.className || "").indexOf("cursor-pointer") >= 0) return 2;
    return 1;
  }

  function sameRect(a, b) {
    return Math.abs(a.left - b.left) < 3 &&
      Math.abs(a.top - b.top) < 3 &&
      Math.abs(a.width - b.width) < 3 &&
      Math.abs(a.height - b.height) < 3;
  }

  function poolFor(scope) {
    const query = NATIVE + "," + SEMANTIC +
      ",div,section,article,li";
    return Array.prototype.slice.call(scope.querySelectorAll(query));
  }

  function rebuild() {
    const scope = activeScope();
    const pool = poolFor(scope).filter(looksInteractive);

    pool.sort(function (a, b) {
      const pa = priority(a);
      const pb = priority(b);
      if (pa !== pb) return pb - pa;

      const ar = a.getBoundingClientRect();
      const br = b.getBoundingClientRect();
      const aa = ar.width * ar.height;
      const ba = br.width * br.height;
      if (Math.abs(aa - ba) > 20) return aa - ba;
      return depth(b) - depth(a);
    });

    const list = [];
    pool.forEach(function (el) {
      const r = el.getBoundingClientRect();
      const viewportArea = window.innerWidth * window.innerHeight;
      const huge = r.width * r.height > viewportArea * 0.58;

      if (huge && !nativeFocusable(el) && !el.hasAttribute("aria-label")) {
        return;
      }

      const duplicate = list.some(function (existing) {
        return sameRect(existing.getBoundingClientRect(), r);
      });
      if (duplicate) return;

      if (!nativeFocusable(el)) {
        el.setAttribute("tabindex", "0");
      }
      el.classList.add("phylax-tv-focusable");
      list.push(el);
    });

    cachedScope = scope;
    cached = list;
    return cached;
  }

  function candidates() {
    const scope = activeScope();
    if (scope !== cachedScope) return rebuild();

    const list = cached.filter(function (el) {
      return visible(el) &&
        (scope === document || scope.contains(el));
    });
    return list.length ? list : rebuild();
  }

  function focusElement(el) {
    if (!el) return false;

    try {
      el.focus({ preventScroll: true });
    } catch (_) {
      try {
        el.focus();
      } catch (_) {
        return false;
      }
    }

    try {
      el.scrollIntoView({
        block: "nearest",
        inline: "nearest",
        behavior: "auto"
      });
    } catch (_) {}

    return true;
  }

  function ordered(list) {
    return list.slice().sort(function (a, b) {
      const ar = a.getBoundingClientRect();
      const br = b.getBoundingClientRect();
      if (Math.abs(ar.top - br.top) > 12) return ar.top - br.top;
      return ar.left - br.left;
    });
  }

  function focusFirst() {
    const list = ordered(rebuild());
    if (!list.length) {
      setMode("cursor");
      return false;
    }
    return focusElement(list[0]);
  }

  function center(el) {
    const r = el.getBoundingClientRect();
    return {
      x: r.left + r.width / 2,
      y: r.top + r.height / 2
    };
  }

  function zone(el) {
    const c = center(el);
    if (c.y < window.innerHeight * 0.22) return "top";
    if (c.y > window.innerHeight * 0.78) return "bottom";
    return "content";
  }

  function directionalCandidate(list, current, dir) {
    const c = center(current);
    const horizontal = dir === "left" || dir === "right";
    let best = null;
    let bestScore = Number.POSITIVE_INFINITY;

    list.forEach(function (el) {
      if (el === current) return;

      const t = center(el);
      const dx = t.x - c.x;
      const dy = t.y - c.y;

      if (dir === "left" && dx >= -4) return;
      if (dir === "right" && dx <= 4) return;
      if (dir === "up" && dy >= -4) return;
      if (dir === "down" && dy <= 4) return;

      const primary = horizontal ? Math.abs(dx) : Math.abs(dy);
      const secondary = horizontal ? Math.abs(dy) : Math.abs(dx);
      const laneRatio = secondary / Math.max(primary, 1);
      const lanePenalty = laneRatio > 1.6 ? secondary * 1.2 : 0;
      const score = primary + secondary * 0.28 + lanePenalty;

      if (score < bestScore) {
        bestScore = score;
        best = el;
      }
    });

    return best;
  }

  function nearestZone(list, current, targetZone) {
    const c = center(current);
    let best = null;
    let bestScore = Number.POSITIVE_INFINITY;

    list.forEach(function (el) {
      if (el === current || zone(el) !== targetZone) return;
      const t = center(el);
      const score = Math.abs(t.x - c.x) * 0.25 +
        Math.abs(t.y - c.y);

      if (score < bestScore) {
        bestScore = score;
        best = el;
      }
    });

    return best;
  }

  function scrollableAncestor(el) {
    let p = el;
    while (p && p !== document.body) {
      const s = window.getComputedStyle(p);
      const y = s.overflowY;
      if ((y === "auto" || y === "scroll") &&
          p.scrollHeight > p.clientHeight + 4) {
        return p;
      }
      p = p.parentElement;
    }
    return document.scrollingElement || document.documentElement;
  }

  function scrollForDirection(current, dir) {
    const scroller = scrollableAncestor(current);
    const horizontal = dir === "left" || dir === "right";
    const amount = Math.round(
      (horizontal ? window.innerWidth : window.innerHeight) * 0.58
    );

    if (dir === "left") scroller.scrollBy({ left: -amount, behavior: "auto" });
    if (dir === "right") scroller.scrollBy({ left: amount, behavior: "auto" });
    if (dir === "up") scroller.scrollBy({ top: -amount, behavior: "auto" });
    if (dir === "down") scroller.scrollBy({ top: amount, behavior: "auto" });
  }

  function moveFocus(dir) {
    const list = candidates();
    if (!list.length) {
      setMode("cursor");
      return false;
    }

    const current = document.activeElement;
    if (list.indexOf(current) < 0) return focusFirst();

    const currentZone = zone(current);
    let best = directionalCandidate(list, current);

    if (best) {
      if (dir === "up" &&
          currentZone === "bottom" &&
          zone(best) === "bottom") {
        best = nearestZone(list, current, "content") ||
          nearestZone(list, current, "top") ||
          best;
      }

      if (dir === "down" &&
          currentZone === "top" &&
          zone(best) === "top") {
        best = nearestZone(list, current, "content") ||
          nearestZone(list, current, "bottom") ||
          best;
      }

      return focusElement(best);
    }

    if (dir === "up" && currentZone === "bottom") {
      best = nearestZone(list, current, "content") ||
        nearestZone(list, current, "top");
      if (best) return focusElement(best);
    }

    if (dir === "down" && currentZone === "top") {
      best = nearestZone(list, current, "content") ||
        nearestZone(list, current, "bottom");
      if (best) return focusElement(best);
    }

    scrollForDirection(current, dir);

    window.setTimeout(function () {
      const refreshed = rebuild();
      const next = directionalCandidate(refreshed, current, dir);
      if (next) {
        focusElement(next);
      } else {
        showBadge("長按 OK 可切換游標模式", 1600);
      }
    }, 80);

    return true;
  }

  function setCursorPosition(x, y) {
    cursorX = Math.max(10, Math.min(window.innerWidth - 10, x));
    cursorY = Math.max(10, Math.min(window.innerHeight - 10, y));
    cursor.style.left = cursorX + "px";
    cursor.style.top = cursorY + "px";
  }

  function setMode(next) {
    mode = next === "cursor" ? "cursor" : "focus";

    if (mode === "cursor") {
      const active = document.activeElement;
      if (active && active !== document.body &&
          active !== document.documentElement) {
        const c = center(active);
        setCursorPosition(c.x, c.y);
      } else {
        setCursorPosition(cursorX, cursorY);
      }

      cursor.style.display = "block";
      showBadge("游標模式｜長按 OK 回焦點模式", 2200);
    } else {
      cursor.style.display = "none";
      showBadge("焦點模式｜長按 OK 切換游標", 2200);

      const hit = document.elementFromPoint(cursorX, cursorY);
      const target = closestInteractive(hit);
      if (target) {
        focusElement(target);
      } else if (!document.activeElement ||
          document.activeElement === document.body) {
        focusFirst();
      }
    }
  }

  function toggleCursor() {
    setMode(mode === "cursor" ? "focus" : "cursor");
    return mode;
  }

  function moveCursor(dir) {
    const step = 44;

    if (dir === "left") setCursorPosition(cursorX - step, cursorY);
    if (dir === "right") setCursorPosition(cursorX + step, cursorY);
    if (dir === "up") setCursorPosition(cursorX, cursorY - step);
    if (dir === "down") setCursorPosition(cursorX, cursorY + step);

    const edge = 20;
    const hit = document.elementFromPoint(cursorX, cursorY);
    const scroller = scrollableAncestor(hit || document.body);

    if (dir === "up" && cursorY <= edge + 10) {
      scroller.scrollBy({ top: -160, behavior: "auto" });
    }
    if (dir === "down" &&
        cursorY >= window.innerHeight - edge - 10) {
      scroller.scrollBy({ top: 160, behavior: "auto" });
    }

    return true;
  }

  function closestInteractive(start) {
    let el = start;
    while (el && el !== document.body) {
      if (looksInteractive(el)) return el;
      el = el.parentElement;
    }
    return start;
  }

  function clickAtCursor() {
    const raw = document.elementFromPoint(cursorX, cursorY);
    if (!raw) return false;

    const el = closestInteractive(raw) || raw;

    try {
      el.focus({ preventScroll: true });
    } catch (_) {}

    const eventInit = {
      bubbles: true,
      cancelable: true,
      clientX: cursorX,
      clientY: cursorY,
      button: 0,
      buttons: 1,
      pointerId: 1,
      pointerType: "mouse",
      isPrimary: true
    };

    try {
      el.dispatchEvent(new PointerEvent("pointerdown", eventInit));
      el.dispatchEvent(new MouseEvent("mousedown", eventInit));
      el.dispatchEvent(new PointerEvent("pointerup", eventInit));
      el.dispatchEvent(new MouseEvent("mouseup", eventInit));
    } catch (_) {}

    if (typeof el.click === "function") {
      el.click();
    } else {
      try {
        el.dispatchEvent(new MouseEvent("click", eventInit));
      } catch (_) {}
    }

    window.setTimeout(rebuild, 80);
    return true;
  }

  function activateFocus() {
    let el = document.activeElement;
    if (!el || el === document.body || el === document.documentElement) {
      return focusFirst();
    }

    if (String(el.tagName || "").toLowerCase() === "label") {
      const targetId = el.getAttribute("for");
      const target = targetId ? document.getElementById(targetId) : null;
      if (target && typeof target.click === "function") {
        target.click();
        window.setTimeout(rebuild, 80);
        return true;
      }
    }

    if (typeof el.click === "function") {
      el.click();
      window.setTimeout(function () {
        rebuild();
        const scope = activeScope();
        if (scope !== document &&
            !scope.contains(document.activeElement)) {
          const list = ordered(candidates());
          if (list.length) focusElement(list[0]);
        }
      }, 100);
      return true;
    }

    setMode("cursor");
    return false;
  }

  function move(dir) {
    if (mode === "cursor") return moveCursor(dir);
    return moveFocus(dir);
  }

  function activate() {
    if (mode === "cursor") return clickAtCursor();
    return activateFocus();
  }

  function refresh() {
    rebuild();
    if (mode === "focus") {
      const scope = activeScope();
      if (scope !== document &&
          !scope.contains(document.activeElement)) {
        const list = ordered(candidates());
        if (list.length) focusElement(list[0]);
      }
    }
  }

  function scheduleRefresh() {
    if (refreshTimer) window.clearTimeout(refreshTimer);
    refreshTimer = window.setTimeout(refresh, 90);
  }

  const observer = new MutationObserver(scheduleRefresh);
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: [
      "role",
      "disabled",
      "aria-disabled",
      "aria-expanded",
      "aria-selected",
      "aria-checked",
      "data-state"
    ]
  });

  window.addEventListener("resize", scheduleRefresh);

  window.__phylaxTvRemote = {
    version: "0.1",
    move: move,
    activate: activate,
    toggleCursor: toggleCursor,
    setMode: setMode,
    refresh: refresh,
    focusFirst: focusFirst,
    getMode: function () {
      return mode;
    }
  };

  rebuild();
  window.setTimeout(function () {
    focusFirst();
    showBadge("TV 模式｜長按 OK 可切換游標", 3200);
  }, 120);
})();