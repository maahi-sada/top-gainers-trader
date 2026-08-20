/* Paisa — reads a bank statement CSV and turns each row into the same shape
 * the message parser produces, so both funnel into one review inbox.
 *
 * Indian bank exports vary wildly: separate Debit/Credit columns, a single
 * signed Amount, a Dr/Cr marker, junk preamble rows above the real header.
 * All of that is detected here rather than asked of the user. */
(function (global) {
  'use strict';

  /* ---------- CSV ---------- */

  /* A real parser, not a split(',') — narrations contain commas and quotes. */
  function parseCSV(text) {
    var rows = [], row = [], field = '', quoted = false;
    var src = String(text == null ? '' : text).replace(/\r\n?/g, '\n');

    for (var i = 0; i < src.length; i++) {
      var c = src[i];
      if (quoted) {
        if (c === '"') {
          if (src[i + 1] === '"') { field += '"'; i++; }
          else quoted = false;
        } else field += c;
        continue;
      }
      if (c === '"') { quoted = true; continue; }
      if (c === ',' || c === '\t') { row.push(field); field = ''; continue; }
      if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; continue; }
      field += c;
    }
    if (field !== '' || row.length) { row.push(field); rows.push(row); }

    return rows
      .map(function (r) { return r.map(function (c) { return c.trim(); }); })
      .filter(function (r) { return r.some(function (c) { return c !== ''; }); });
  }

  /* ---------- header detection ---------- */

  var HEAD = {
    date: /^(txn|transaction|value|posting|tran)?\s*date|^date$|^dt$/i,
    description: /narration|description|particulars|remarks|details|transaction remarks|payee|merchant/i,
    debit: /^(debit|withdrawal|withdrawal amt|dr|paid out|money out|debit amount)/i,
    credit: /^(credit|deposit|deposit amt|cr|paid in|money in|credit amount)/i,
    amount: /^(amount|amt|transaction amount|value)/i,
    drcr: /^(dr\s*\/?\s*cr|cr\s*\/?\s*dr|debit\s*\/\s*credit|credit\s*\/\s*debit|type|txn type|transaction type|indicator)/i,
    ref: /(ref|cheque|chq|utr|transaction id|txn id)/i,
    balance: /balance|bal/i
  };

  /* The header is the first row where at least two known column names appear. */
  function findHeader(rows) {
    for (var i = 0; i < Math.min(rows.length, 25); i++) {
      var hits = 0;
      rows[i].forEach(function (cell) {
        if (!cell) return;
        for (var key in HEAD) {
          if (HEAD[key].test(cell)) { hits++; return; }
        }
      });
      if (hits >= 2) return i;
    }
    return -1;
  }

  function guessMapping(headers) {
    var map = { date: -1, description: -1, debit: -1, credit: -1, amount: -1, drcr: -1, ref: -1, balance: -1 };
    headers.forEach(function (h, idx) {
      if (!h) return;
      /* Balance columns also match /bal/, so claim them first and skip later. */
      if (map.balance === -1 && HEAD.balance.test(h) && !HEAD.debit.test(h) && !HEAD.credit.test(h)) { map.balance = idx; return; }
      if (map.date === -1 && HEAD.date.test(h)) { map.date = idx; return; }
      /* Before Debit/Credit, so a "Debit/Credit" marker column is not mistaken
       * for a column of debit amounts. */
      if (map.drcr === -1 && HEAD.drcr.test(h)) { map.drcr = idx; return; }
      if (map.debit === -1 && HEAD.debit.test(h)) { map.debit = idx; return; }
      if (map.credit === -1 && HEAD.credit.test(h)) { map.credit = idx; return; }
      if (map.amount === -1 && HEAD.amount.test(h)) { map.amount = idx; return; }
      if (map.description === -1 && HEAD.description.test(h)) { map.description = idx; return; }
      if (map.ref === -1 && HEAD.ref.test(h)) { map.ref = idx; return; }
    });
    return map;
  }

  /* ---------- values ---------- */

  function toPaise(cell) {
    if (cell == null) return null;
    var s = String(cell).replace(/[₹\s]/g, '').replace(/,/g, '');
    if (!s || s === '-') return null;
    var neg = /^\(.*\)$/.test(s) || /^-/.test(s);
    s = s.replace(/^[-(]|\)$/g, '');
    var n = parseFloat(s);
    if (!isFinite(n)) return null;
    return Math.round(n * 100) * (neg ? -1 : 1);
  }

  var MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12 };

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function toISO(cell) {
    var s = String(cell || '').trim();
    var m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(s);
    if (m) return build(+m[1], +m[2], +m[3]);

    m = /^(\d{1,2})[-\/. ]([A-Za-z]{3,9})[-\/. ](\d{2,4})/.exec(s);
    if (m) {
      var mon = MONTHS[m[2].slice(0, 3).toLowerCase()];
      if (mon) return build(yr(m[3]), mon, +m[1]);
    }
    /* Bank exports are day-first. */
    m = /^(\d{1,2})[-\/.](\d{1,2})[-\/.](\d{2,4})/.exec(s);
    if (m) return build(yr(m[3]), +m[2], +m[1]);
    return null;
  }

  function yr(y) { var n = parseInt(y, 10); return n < 100 ? 2000 + n : n; }

  function build(y, m, d) {
    if (!(y >= 1990 && y <= 2099 && m >= 1 && m <= 12 && d >= 1 && d <= 31)) return null;
    return y + '-' + pad(m) + '-' + pad(d);
  }

  /* ---------- rows -> entries ---------- */

  /* Returns { headers, mapping, entries, skipped } where each entry looks like
   * a Parse.parse() result, ready for the inbox. */
  function read(text, overrides) {
    var rows = parseCSV(text);
    if (!rows.length) return { headers: [], mapping: null, entries: [], skipped: 0, error: 'The file is empty.' };

    var headerAt = findHeader(rows);
    if (headerAt === -1) {
      return { headers: rows[0] || [], mapping: null, entries: [], skipped: 0,
        error: 'Could not find a header row with a date and an amount column.' };
    }

    var headers = rows[headerAt];
    var mapping = Object.assign(guessMapping(headers), overrides || {});
    if (mapping.date === -1) {
      return { headers: headers, mapping: mapping, entries: [], skipped: 0, error: 'No date column found.' };
    }
    if (mapping.debit === -1 && mapping.credit === -1 && mapping.amount === -1) {
      return { headers: headers, mapping: mapping, entries: [], skipped: 0, error: 'No amount column found.' };
    }

    var entries = [], skipped = 0;

    rows.slice(headerAt + 1).forEach(function (r) {
      var date = toISO(r[mapping.date]);
      if (!date) { skipped++; return; }

      var debit = mapping.debit >= 0 ? toPaise(r[mapping.debit]) : null;
      var credit = mapping.credit >= 0 ? toPaise(r[mapping.credit]) : null;
      var amount = null, type = null;

      if (debit) { amount = Math.abs(debit); type = 'expense'; }
      else if (credit) { amount = Math.abs(credit); type = 'income'; }
      else if (mapping.amount >= 0) {
        var raw = toPaise(r[mapping.amount]);
        if (raw === null || raw === 0) { skipped++; return; }
        var marker = mapping.drcr >= 0 ? String(r[mapping.drcr] || '') : '';
        if (/^\s*(dr|debit|withdrawal)/i.test(marker)) type = 'expense';
        else if (/^\s*(cr|credit|deposit)/i.test(marker)) type = 'income';
        else type = raw < 0 ? 'expense' : 'income';
        amount = Math.abs(raw);
      }

      if (!amount) { skipped++; return; }

      var desc = mapping.description >= 0 ? (r[mapping.description] || '') : '';
      var ref = mapping.ref >= 0 ? (r[mapping.ref] || '') : '';

      entries.push({
        ok: true, why: null, confidence: 0.95,
        raw: [date, desc, (amount / 100).toFixed(2)].filter(Boolean).join(' · '),
        type: type,
        amount: amount,
        date: date,
        counterparty: tidy(desc),
        vpa: vpaIn(desc),
        accountTail: null,
        bank: null,
        method: null,
        ref: ref || null,
        balance: mapping.balance >= 0 ? toPaise(r[mapping.balance]) : null
      });
    });

    return { headers: headers, mapping: mapping, entries: entries, skipped: skipped, error: null };
  }

  function vpaIn(desc) {
    var m = /\b([a-z0-9][a-z0-9._-]+@[a-z]{2,})\b/i.exec(String(desc || ''));
    return m ? m[1].toLowerCase() : null;
  }

  /* Statement narrations are machine noise around one useful name:
   * "UPI/DR/412345678901/SWIGGY/HDFC/swiggy@icici/Payment" -> "Swiggy" */
  function tidy(desc) {
    var s = String(desc || '').trim();
    if (!s) return null;

    var parts = s.split(/[\/|]/).map(function (p) { return p.trim(); }).filter(Boolean);
    if (parts.length > 1) {
      var best = parts.filter(function (p) {
        if (/^\d+$/.test(p)) return false;
        if (/@/.test(p)) return false;
        if (/^(upi|dr|cr|neft|imps|rtgs|ach|nach|pos|atm|ecs|inb|mmt|tpt|chq|payment|txn|ref|na)$/i.test(p)) return false;
        return p.length >= 3;
      })[0];
      if (best) s = best;
      else {
        var handle = parts.filter(function (p) { return /@/.test(p); })[0];
        if (handle) s = handle.split('@')[0].replace(/[._-]+/g, ' ');
      }
    }

    s = s.replace(/\b(upi|neft|imps|rtgs|pos|atm|ecs|nach|ach)\b/gi, '')
      .replace(/\b\d{6,}\b/g, '')
      .replace(/[*_]+/g, ' ')
      .replace(/\s+/g, ' ')
      .replace(/^[\s.,;:\-]+|[\s.,;:\-]+$/g, '')
      .trim();

    if (!s || s.length < 2) return null;
    if (s === s.toUpperCase() || s === s.toLowerCase()) {
      s = s.toLowerCase().replace(/\b[a-z]/g, function (c) { return c.toUpperCase(); });
    }
    return s;
  }

  var api = { parseCSV: parseCSV, findHeader: findHeader, guessMapping: guessMapping, read: read, tidy: tidy };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.Statement = api;
})(typeof window !== 'undefined' ? window : globalThis);
