package com.asksakis.freegate.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import android.webkit.WebView

/**
 * Lightweight D-pad bridge for Android TV.
 *
 * Frigate is rendered inside a WebView and its desktop/mobile UI does not expose
 * reliable Android TV focus navigation. This bridge keeps phone behavior untouched:
 * it activates only on TV devices, maps D-pad events to a small JS spatial navigator,
 * and lets Back and other system keys continue through Android normally.
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
          if (window.__phylaxTvNavInstalled) return;
          window.__phylaxTvNavInstalled = true;

          var style = document.createElement('style');
          style.textContent = [
            '*:focus {',
            'outline: 4px solid #ffb300 !important;',
            'outline-offset: -4px !important;',
            '}'
          ].join('');
          document.head.appendChild(style);

          var selector = [
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

          function visible(el) {
            if (!el || el.disabled) return false;
            if (el.getAttribute('aria-disabled') === 'true') return false;
            var s = window.getComputedStyle(el);
            if (s.display === 'none' || s.visibility === 'hidden') return false;
            var r = el.getBoundingClientRect();
            return r.width > 2 && r.height > 2;
          }

          function candidates() {
            return Array.prototype.slice.call(
              document.querySelectorAll(selector)
            ).filter(visible);
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

          function focusFirst() {
            var list = candidates();
            if (!list.length) return false;
            return focusElement(list[0]);
          }

          function move(dir) {
            var list = candidates();
            if (!list.length) return false;

            var current = document.activeElement;
            if (list.indexOf(current) < 0) return focusFirst();

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
              var score = primary + secondary * 0.35;

              if (score < bestScore) {
                bestScore = score;
                best = el;
              }
            });

            if (best) return focusElement(best);

            var amount = Math.round(
              (horizontal ? window.innerWidth : window.innerHeight) * 0.65
            );
            if (dir === 'left') window.scrollBy(-amount, 0);
            if (dir === 'right') window.scrollBy(amount, 0);
            if (dir === 'up') window.scrollBy(0, -amount);
            if (dir === 'down') window.scrollBy(0, amount);
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
            return false;
          }

          window.__phylaxTvNav = {
            focusFirst: focusFirst,
            move: move,
            activate: activate
          };
        })();
    """.trimIndent()
}
