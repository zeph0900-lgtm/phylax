package com.asksakis.freegate.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import android.webkit.WebView

/**
 * Lightweight D-pad bridge for Android TV.
 *
 * Frigate is rendered inside a WebView and its React UI does not expose reliable
 * Android TV focus navigation. This bridge activates only on TV devices, discovers
 * both native focusables and pointer-clickable React containers, and performs simple
 * spatial navigation without changing phone behavior.
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

          var cached = [];
          var refreshTimer = null;

          function visible(el) {
            if (!el || el.disabled) return false;
            if (el.getAttribute('aria-disabled') === 'true') return false;
            var s = window.getComputedStyle(el);
            if (s.display === 'none' || s.visibility === 'hidden') return false;
            if (s.pointerEvents === 'none') return false;
            var r = el.getBoundingClientRect();
            return r.width > 6 && r.height > 6;
          }

          function nativeFocusable(el) {
            try {
              return el.matches(nativeSelector);
            } catch (e) {
              return false;
            }
          }

          function looksClickable(el) {
            if (!visible(el)) return false;
            if (nativeFocusable(el)) return true;
            if (el.hasAttribute('onclick')) return true;

            var cls = String(el.className || '');
            if (cls.indexOf('cursor-pointer') >= 0) return true;

            var tag = (el.tagName || '').toLowerCase();
            if (tag !== 'div' && tag !== 'section' && tag !== 'article' && tag !== 'li') {
              return false;
            }

            try {
              return window.getComputedStyle(el).cursor === 'pointer';
            } catch (e) {
              return false;
            }
          }

          function nearlySameRect(a, b) {
            return Math.abs(a.left - b.left) < 3 &&
              Math.abs(a.top - b.top) < 3 &&
              Math.abs(a.width - b.width) < 3 &&
              Math.abs(a.height - b.height) < 3;
          }

          function rebuild() {
            var pool = Array.prototype.slice.call(
              document.querySelectorAll(nativeSelector + ',[onclick],div,section,article,li')
            );

            var list = [];
            pool.forEach(function(el) {
              if (!looksClickable(el)) return;

              var r = el.getBoundingClientRect();
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

            cached = list;
            return cached;
          }

          function candidates() {
            var list = cached.filter(function(el) {
              return document.contains(el) && visible(el);
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
            var list = ordered(candidates());
            if (!list.length) return false;
            return focusElement(list[0]);
          }

          function directionalCandidate(list, current, dir) {
            var r = current.getBoundingClientRect();
            var cx = r.left + r.width / 2;
            var cy = r.top + r.height / 2;
            var horizontal = dir === 'left' || dir === 'right';
            var best = null;
            var bestScore = Number.POSITIVE_INFINITY;

            list.forEach(function(el) {
              if (el === current) return;
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

          function edgeCandidate(list, dir) {
            var sorted = list.slice().sort(function(a, b) {
              var ar = a.getBoundingClientRect();
              var br = b.getBoundingClientRect();
              if (dir === 'up') return br.bottom - ar.bottom;
              if (dir === 'down') return ar.top - br.top;
              if (dir === 'left') return br.right - ar.right;
              return ar.left - br.left;
            });
            return sorted[0] || null;
          }

          function move(dir) {
            var list = candidates();
            if (!list.length) return false;

            var current = document.activeElement;
            if (list.indexOf(current) < 0) return focusFirst();

            var best = directionalCandidate(list, current, dir);
            if (best) return focusElement(best);

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
              var next = directionalCandidate(refreshed, current, dir) ||
                edgeCandidate(refreshed, dir);
              if (next) focusElement(next);
            }, 90);
            return true;
          }

          function activate() {
            var el = document.activeElement;
            if (!el || el === document.body || el === document.documentElement) {
              return focusFirst();
            }

            if (typeof el.click === 'function') {
              el.click();
              return true;
            }

            var parent = el.closest(nativeSelector + ',[onclick],.cursor-pointer');
            if (parent && typeof parent.click === 'function') {
              parent.click();
              return true;
            }
            return false;
          }

          function scheduleRefresh() {
            if (refreshTimer) window.clearTimeout(refreshTimer);
            refreshTimer = window.setTimeout(rebuild, 120);
          }

          var observer = new MutationObserver(scheduleRefresh);
          observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['class', 'role', 'tabindex', 'aria-disabled']
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
