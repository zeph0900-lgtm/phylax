package com.asksakis.freegate.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import android.webkit.WebView

/**
 * Lightweight D-pad bridge for Android TV.
 *
 * Frigate is rendered inside a WebView and its React/Radix UI does not expose reliable
 * Android TV focus navigation. This bridge activates only on TV devices, discovers
 * native and semantic controls, keeps focus inside the top-most dialog/popover, and
 * performs spatial navigation without changing phone behavior.
 */
object TvRemoteNavigator {

    fun isTelevision(context: Context): Boolean {
        val type = context.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        return type == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun install(webView: WebView) {
        if (!isTelevision(webView.context)) return

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    move(webView, "left")
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    move(webView, "right")
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    move(webView, "up")
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    move(webView, "down")
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.repeatCount == 0) activate(webView)
                    true
                }
                else -> false
            }
        }
    }

    fun onPageReady(webView: WebView) {
        if (!isTelevision(webView.context)) return

        webView.requestFocus()
        webView.evaluateJavascript(INSTALL_JS) {
            webView.evaluateJavascript(
                "window.__phylaxTvNav && window.__phylaxTvNav.focusFirst();",
                null,
            )
        }
    }

    private fun move(webView: WebView, direction: String) {
        webView.evaluateJavascript(
            "window.__phylaxTvNav && window.__phylaxTvNav.move('$direction');",
            null,
        )
    }

    private fun activate(webView: WebView) {
        webView.evaluateJavascript(
            "window.__phylaxTvNav && window.__phylaxTvNav.activate();",
            null,
        )
    }

    private val INSTALL_JS = """
        (function() {
          if (window.__phylaxTvNavInstalled) {
            if (window.__phylaxTvNav) window.__phylaxTvNav.refresh();
            return;
          }
          window.__phylaxTvNavInstalled = true;

          var style = document.createElement('style');
          style.textContent = [
            '.phylax-tv-focusable:focus {',
            'outline: 4px solid #ffb300 !important;',
            'outline-offset: -4px !important;',
            'box-shadow: inset 0 0 0 2px rgba(0,0,0,.7) !important;',
            '}'
          ].join('');
          document.head.appendChild(style);

          var nativeSelector = [
            'a[href]',
            'button',
            'input',
            'select',
            'textarea',
            '[role="button"]',
            '[role="link"]',
            '[role="tab"]',
            '[role="menuitem"]',
            '[role="option"]',
            '[role="switch"]',
            '[role="checkbox"]',
            '[role="radio"]',
            '[tabindex]:not([tabindex="-1"])'
          ].join(',');

          var semanticSelector = [
            'label[for]',
            '[aria-label]',
            '[aria-labelledby]',
            '[aria-haspopup]',
            '[aria-controls]',
            '[aria-expanded]',
            '[aria-checked]',
            '[aria-selected]',
            '[data-state]',
            '[data-slot]',
            '[data-radix-collection-item]',
            '[title]',
            '[onclick]',
            '.cursor-pointer'
          ].join(',');

          var overlaySelector = [
            '[role="dialog"]',
            '[role="menu"]',
            '[role="listbox"]',
            '[data-radix-popper-content-wrapper]',
            '[data-radix-menu-content]',
            '[data-radix-dialog-content]'
          ].join(',');

          var cached = [];
          var cachedScope = null;
          var refreshTimer = null;

          function visible(el) {
            if (!el || el.disabled) return false;
            if (el.getAttribute('aria-disabled') === 'true') return false;
            var s = window.getComputedStyle(el);
            if (s.display === 'none' || s.visibility === 'hidden') return false;
            if (parseFloat(s.opacity || '1') < 0.05) return false;
            var r = el.getBoundingClientRect();
            return r.width > 6 && r.height > 6 &&
              r.bottom > 0 && r.right > 0 &&
              r.top < window.innerHeight && r.left < window.innerWidth;
          }

          function depth(el) {
            var d = 0;
            var p = el;
            while (p && p !== document.body) {
              d += 1;
              p = p.parentElement;
            }
            return d;
          }

          function nativeFocusable(el) {
            try {
              return el.matches(nativeSelector);
            } catch (e) {
              return false;
            }
          }

          function semanticInteractive(el) {
            try {
              return el.matches(semanticSelector);
            } catch (e) {
              return false;
            }
          }

          function activeScope() {
            var overlays = Array.prototype.slice.call(
              document.querySelectorAll(overlaySelector)
            ).filter(visible);

            if (!overlays.length) return document;

            overlays.sort(function(a, b) {
              var za = parseInt(window.getComputedStyle(a).zIndex || '0', 10);
              var zb = parseInt(window.getComputedStyle(b).zIndex || '0', 10);
              if (za !== zb) return za - zb;
              return depth(a) - depth(b);
            });
            return overlays[overlays.length - 1];
          }

          function looksClickable(el) {
            if (!visible(el)) return false;
            if (nativeFocusable(el) || semanticInteractive(el)) return true;

            var cls = String(el.className || '');
            if (cls.indexOf('cursor-pointer') >= 0) return true;

            var tag = (el.tagName || '').toLowerCase();
            if (tag !== 'div' && tag !== 'section' &&
                tag !== 'article' && tag !== 'li') {
              return false;
            }

            try {
              return window.getComputedStyle(el).cursor === 'pointer';
            } catch (e) {
              return false;
            }
          }

          function priority(el) {
            if (nativeFocusable(el)) return 4;
            if (semanticInteractive(el)) return 3;
            if (String(el.className || '').indexOf('cursor-pointer') >= 0) return 2;
            return 1;
          }

          function nearlySameRect(a, b) {
            return Math.abs(a.left - b.left) < 3 &&
              Math.abs(a.top - b.top) < 3 &&
              Math.abs(a.width - b.width) < 3 &&
              Math.abs(a.height - b.height) < 3;
          }

          function poolFor(scope) {
            var query = nativeSelector + ',' + semanticSelector +
              ',div,section,article,li';
            return Array.prototype.slice.call(scope.querySelectorAll(query));
          }

          function rebuild() {
            var scope = activeScope();
            var pool = poolFor(scope).filter(looksClickable);

            pool.sort(function(a, b) {
              var pa = priority(a);
              var pb = priority(b);
              if (pa !== pb) return pb - pa;

              var ar = a.getBoundingClientRect();
              var br = b.getBoundingClientRect();
              var aa = ar.width * ar.height;
              var ba = br.width * br.height;
              if (Math.abs(aa - ba) > 20) return aa - ba;
              return depth(b) - depth(a);
            });

            var list = [];
            pool.forEach(function(el) {
              var r = el.getBoundingClientRect();
              var huge = r.width * r.height >
                window.innerWidth * window.innerHeight * 0.55;
              if (huge && !nativeFocusable(el) && !semanticInteractive(el)) {
                return;
              }

              var duplicate = list.some(function(existing) {
                return nearlySameRect(existing.getBoundingClientRect(), r);
              });
              if (duplicate) return;

              if (!nativeFocusable(el)) {
                el.setAttribute('tabindex', '0');
              }
              el.classList.add('phylax-tv-focusable');
              list.push(el);
            });

            cachedScope = scope;
            cached = list;
            return cached;
          }

          function candidates() {
            var scope = activeScope();
            if (scope !== cachedScope) return rebuild();

            var list = cached.filter(function(el) {
              return document.contains(el) && visible(el) &&
                (scope === document || scope.contains(el));
            });
            if (!list.length) list = rebuild();
            return list;
          }

          function focusElement(el) {
            if (!el) return false;
            try {
              el.focus({ preventScroll: true });
            } catch (e) {
              el.focus();
            }
            try {
              el.scrollIntoView({
                block: 'nearest',
                inline: 'nearest',
                behavior: 'auto'
              });
            } catch (e) {}
            return true;
          }

          function ordered(list) {
            return list.slice().sort(function(a, b) {
              var ar = a.getBoundingClientRect();
              var br = b.getBoundingClientRect();
              if (Math.abs(ar.top - br.top) > 12) return ar.top - br.top;
              return ar.left - br.left;
            });
          }

          function focusFirst() {
            var list = ordered(rebuild());
            if (!list.length) return false;
            return focusElement(list[0]);
          }

          function zone(el) {
            var r = el.getBoundingClientRect();
            var cy = r.top + r.height / 2;
            var h = window.innerHeight;
            if (cy < h * 0.23) return 'top';
            if (cy > h * 0.77) return 'bottom';
            return 'content';
          }

          function directionalCandidate(list, current, dir, restrictZone) {
            var r = current.getBoundingClientRect();
            var cx = r.left + r.width / 2;
            var cy = r.top + r.height / 2;
            var horizontal = dir === 'left' || dir === 'right';
            var best = null;
            var bestScore = Number.POSITIVE_INFINITY;

            list.forEach(function(el) {
              if (el === current) return;
              if (restrictZone && zone(el) !== restrictZone) return;

              var t = el.getBoundingClientRect();
              var tx = t.left + t.width / 2;
              var ty = t.top + t.height / 2;
              var dx = tx - cx;
              var dy = ty - cy;

              if (dir === 'left' && dx >= -4) return;
              if (dir === 'right' && dx <= 4) return;
              if (dir === 'up' && dy >= -4) return;
              if (dir === 'down' && dy <= 4) return;

              var primary = horizontal ? Math.abs(dx) : Math.abs(dy);
              var secondary = horizontal ? Math.abs(dy) : Math.abs(dx);
              var lanePenalty = secondary > primary * 1.8 ? secondary * 0.55 : 0;
              var score = primary + secondary * 0.32 + lanePenalty;

              if (score < bestScore) {
                bestScore = score;
                best = el;
              }
            });
            return best;
          }

          function nearestInZone(list, current, targetZone) {
            var r = current.getBoundingClientRect();
            var cx = r.left + r.width / 2;
            var cy = r.top + r.height / 2;
            var best = null;
            var bestScore = Number.POSITIVE_INFINITY;

            list.forEach(function(el) {
              if (el === current || zone(el) !== targetZone) return;
              var t = el.getBoundingClientRect();
              var tx = t.left + t.width / 2;
              var ty = t.top + t.height / 2;
              var score = Math.abs(tx - cx) * 0.35 + Math.abs(ty - cy);
              if (score < bestScore) {
                bestScore = score;
                best = el;
              }
            });
            return best;
          }

          function move(dir) {
            var list = candidates();
            if (!list.length) return false;

            var current = document.activeElement;
            if (list.indexOf(current) < 0) return focusFirst();

            var currentZone = zone(current);
            var best = directionalCandidate(list, current, dir, null);

            if (best) {
              if (dir === 'up' && currentZone === 'bottom' &&
                  zone(best) === 'bottom') {
                best = nearestInZone(list, current, 'content') ||
                  nearestInZone(list, current, 'top') || best;
              } else if (dir === 'down' && currentZone === 'top' &&
                  zone(best) === 'top') {
                best = nearestInZone(list, current, 'content') ||
                  nearestInZone(list, current, 'bottom') || best;
              }
              return focusElement(best);
            }

            if (dir === 'up' && currentZone === 'bottom') {
              best = nearestInZone(list, current, 'content') ||
                nearestInZone(list, current, 'top');
              if (best) return focusElement(best);
            }

            if (dir === 'down' && currentZone === 'top') {
              best = nearestInZone(list, current, 'content') ||
                nearestInZone(list, current, 'bottom');
              if (best) return focusElement(best);
            }

            var horizontal = dir === 'left' || dir === 'right';
            var amount = Math.round(
              (horizontal ? window.innerWidth : window.innerHeight) * 0.68
            );

            if (dir === 'left') window.scrollBy(-amount, 0);
            if (dir === 'right') window.scrollBy(amount, 0);
            if (dir === 'up') window.scrollBy(0, -amount);
            if (dir === 'down') window.scrollBy(0, amount);

            window.setTimeout(function() {
              var refreshed = rebuild();
              var next = directionalCandidate(refreshed, current, dir, null);
              if (next) focusElement(next);
            }, 90);
            return true;
          }

          function activate() {
            var el = document.activeElement;
            if (!el || el === document.body || el === document.documentElement) {
              return focusFirst();
            }

            if ((el.tagName || '').toLowerCase() === 'label') {
              var targetId = el.getAttribute('for');
              var target = targetId ? document.getElementById(targetId) : null;
              if (target && typeof target.click === 'function') {
                target.click();
                return true;
              }
            }

            if (typeof el.click === 'function') {
              el.click();
              window.setTimeout(function() {
                rebuild();
                var scope = activeScope();
                if (scope !== document && !scope.contains(document.activeElement)) {
                  var scoped = ordered(candidates());
                  if (scoped.length) focusElement(scoped[0]);
                }
              }, 100);
              return true;
            }
            return false;
          }

          function scheduleRefresh() {
            if (refreshTimer) window.clearTimeout(refreshTimer);
            refreshTimer = window.setTimeout(rebuild, 100);
          }

          var observer = new MutationObserver(scheduleRefresh);
          observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: [
              'class',
              'role',
              'tabindex',
              'aria-disabled',
              'aria-expanded',
              'aria-selected',
              'aria-checked',
              'data-state'
            ]
          });

          rebuild();

          window.__phylaxTvNav = {
            focusFirst: focusFirst,
            move: move,
            activate: activate,
            refresh: rebuild
          };
        })();
    """.trimIndent()
}
