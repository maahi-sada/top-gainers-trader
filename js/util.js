/* Paisa — formatting + small DOM helpers. */
(function (global) {
  'use strict';

  function fmtOpts() {
    var s = global.Store ? global.Store.settings() : { locale: 'en-IN', currency: 'INR' };
    return { locale: s.locale || 'en-IN', currency: s.currency || 'INR' };
  }

  /* Rupees with Indian digit grouping: ₹1,23,456.78 */
  function money(paise, opts) {
    opts = opts || {};
    var o = fmtOpts();
    var v = (paise || 0) / 100;
    var showPaise = opts.paise !== false;
    try {
      return new Intl.NumberFormat(o.locale, {
        style: 'currency',
        currency: o.currency,
        minimumFractionDigits: showPaise ? 2 : 0,
        maximumFractionDigits: showPaise ? 2 : 0
      }).format(v);
    } catch (e) {
      return '₹' + v.toFixed(showPaise ? 2 : 0);
    }
  }

  /* Signed, for ledger rows: +₹500.00 / −₹500.00 */
  function signedMoney(paise, sign) {
    var s = money(Math.abs(paise));
    if (sign > 0) return '+' + s;
    if (sign < 0) return '−' + s;
    return s;
  }

  /* Short form for tight tiles: ₹1.2L, ₹45.3K, ₹2.4Cr */
  function moneyShort(paise) {
    var v = Math.abs((paise || 0) / 100);
    var sign = paise < 0 ? '−' : '';
    var out;
    if (v >= 1e7) out = (v / 1e7).toFixed(2).replace(/\.00$/, '') + 'Cr';
    else if (v >= 1e5) out = (v / 1e5).toFixed(2).replace(/\.00$/, '') + 'L';
    else if (v >= 1000) out = (v / 1000).toFixed(1).replace(/\.0$/, '') + 'K';
    else out = v.toFixed(v % 1 ? 2 : 0);
    return sign + '₹' + out;
  }

  function num(paise) { return ((paise || 0) / 100).toFixed(2); }

  /* '2026-08-19' -> '19 Aug 2026' */
  function date(iso, style) {
    if (!iso) return '';
    var d = new Date(iso + 'T00:00:00');
    if (isNaN(d.getTime())) return iso;
    if (style === 'short') return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
    if (style === 'day') return d.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
    return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  function p2(n) { return (n < 10 ? '0' : '') + n; }

  function relativeDay(iso) {
    var t = global.Store.today();
    if (iso === t) return 'Today';
    var y = new Date(new Date(t + 'T00:00:00').getTime() - 86400000);
    var yIso = y.getFullYear() + '-' + p2(y.getMonth() + 1) + '-' + p2(y.getDate());
    if (iso === yIso) return 'Yesterday';
    return date(iso, 'day');
  }

  function daysUntil(iso) {
    if (!iso) return null;
    var a = new Date(global.Store.today() + 'T00:00:00');
    var b = new Date(iso + 'T00:00:00');
    return Math.round((b - a) / 86400000);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function el(sel, root) { return (root || document).querySelector(sel); }
  function els(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  function initials(name) {
    var parts = String(name || '?').trim().split(/\s+/);
    var a = (parts[0] || '?').charAt(0);
    var b = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
    return (a + b).toUpperCase();
  }

  /* Deterministic colour per person, so the same name always looks the same. */
  function colorFor(str) {
    var h = 0, s = String(str || '');
    for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
    return 'hsl(' + h + ' 62% 48%)';
  }

  function download(filename, text, mime) {
    var blob = new Blob([text], { type: mime || 'application/json' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url; a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
  }

  var toastTimer = null;
  function toast(msg, kind) {
    var t = el('#toast');
    if (!t) return;
    t.textContent = msg;
    t.className = 'toast show' + (kind ? ' ' + kind : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = 'toast'; }, 2600);
  }

  global.U = {
    money: money, signedMoney: signedMoney, moneyShort: moneyShort, num: num,
    date: date, relativeDay: relativeDay, daysUntil: daysUntil,
    esc: esc, el: el, els: els, initials: initials, colorFor: colorFor,
    download: download, toast: toast
  };
})(window);
