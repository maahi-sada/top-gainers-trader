/* Paisa — reads Indian bank / UPI / wallet messages and turns them into a
 * draft entry. Pure functions, no DOM, no storage: parse(text) in, plain
 * object out, so it can be unit tested on its own.
 *
 * Amounts come back as integer paise, dates as YYYY-MM-DD. */
(function (global) {
  'use strict';

  /* Messages that mention money but are not a completed transaction. */
  var JUNK = [
    { re: /\b(otp|one[- ]time password|verification code)\b/i, why: 'OTP message' },
    { re: /\bdo not share\b.*\b(otp|pin|cvv)\b/i, why: 'OTP message' },
    { re: /\b(will be|shall be) (debited|charged|deducted)\b/i, why: 'Upcoming charge, not done yet' },
    { re: /\b(due|payable|outstanding) (on|by|amount)\b/i, why: 'Bill reminder' },
    { re: /\b(failed|declined|reversed|unsuccessful|could not be processed)\b/i, why: 'Transaction did not go through' },
    { re: /\b(request(ed)? (money|payment)|collect request|has requested)\b/i, why: 'Payment request, not a payment' },
    { re: /\b(offer|cashback up to|apply now|pre-approved|loan offer|click here|t&c apply)\b/i, why: 'Promotional message' },
    { re: /\b(mini statement|statement is ready|e-statement)\b/i, why: 'Statement notice' }
  ];

  /* Direction keywords. Longest/most specific first — 'debited' must win over
   * a stray 'credit card' mention. */
  var OUT_WORDS = /\b(debited|debit(?:ed)? from|spent|paid to|paid at|paid|sent|withdrawn|withdrawal|purchase(?:d)?|deducted|transferred to|utilised|charged)\b/i;
  var IN_WORDS = /\b(credited|credit(?:ed)? to|received|deposited|refund(?:ed)?|added to|has been credited|money received)\b/i;

  var BANKS = [
    ['HDFC', /hdfc/i], ['ICICI', /icici/i], ['Axis', /axis/i], ['Kotak', /kotak/i],
    ['IDFC', /idfc/i], ['IndusInd', /indusind/i], ['Canara', /canara/i],
    ['SBI', /\bsbi\b|state bank/i], ['PNB', /\bpnb\b|punjab national/i],
    ['BoB', /\bbob\b|bank of baroda/i], ['Union', /union bank/i],
    ['Yes Bank', /yes bank/i], ['Federal', /federal bank/i], ['RBL', /\brbl\b/i],
    ['AU', /\bau small\b/i], ['Paytm', /paytm/i], ['PhonePe', /phonepe/i],
    ['Google Pay', /google pay|\bgpay\b|g-pay/i], ['Amazon Pay', /amazon pay/i],
    ['Airtel', /airtel payments/i]
  ];

  var METHODS = [
    ['upi', /\b(upi|vpa|@[a-z]{2,})\b/i],
    ['card', /\b(card|credit card|debit card|pos)\b/i],
    ['atm', /\b(atm|cash withdrawal)\b/i],
    ['netbanking', /\b(net ?banking|imps|neft|rtgs)\b/i]
  ];

  var MONTHS = {
    jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6,
    jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12
  };

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function toPaise(str) {
    var n = parseFloat(String(str).replace(/,/g, ''));
    if (!isFinite(n)) return null;
    return Math.round(n * 100);
  }

  /* ---------- amount ----------
   * A message often carries two or three figures: the transaction, the
   * available balance, and sometimes a limit. Anything introduced by a
   * balance-ish word is discarded, and the first survivor wins. */
  function findAmounts(text) {
    var re = /(?:(?:rs|inr)\.?\s*|₹\s*)([\d,]+(?:\.\d{1,2})?)|([\d,]+(?:\.\d{1,2})?)\s*(?:rs\b|inr\b|rupees)/gi;
    var out = [], m;
    while ((m = re.exec(text)) !== null) {
      var raw = m[1] || m[2];
      var before = text.slice(Math.max(0, m.index - 28), m.index);
      var isBalance = /\b(bal|balance|avl|available|limit|due|emi|remaining|left)\b[^.]{0,12}$/i.test(before);
      out.push({ paise: toPaise(raw), index: m.index, end: re.lastIndex, isBalance: isBalance });
    }
    if (!out.length) {
      var bare = /\b(?:debited|credited|paid|sent|received|withdrawn|spent|transferred)\s+(?:by|for|of|with|amount)?\s*([\d,]+(?:\.\d{1,2})?)\b/i.exec(text);
      if (bare) out.push({ paise: toPaise(bare[1]), index: bare.index, end: bare.index + bare[0].length, isBalance: false });
    }
    return out;
  }

  /* ---------- date ---------- */
  function findDate(text) {
    var m = /\b(\d{4})-(\d{2})-(\d{2})\b/.exec(text);
    if (m) return valid(+m[1], +m[2], +m[3]);

    m = /\b(\d{1,2})[-\/. ]?([A-Za-z]{3,9})[-\/. ]?(\d{2,4})\b/.exec(text);
    if (m) {
      var mon = MONTHS[m[2].slice(0, 3).toLowerCase()];
      if (mon) return valid(year(m[3]), mon, +m[1]);
    }

    /* Indian messages are day-first. */
    m = /\b(\d{1,2})[-\/](\d{1,2})[-\/](\d{2,4})\b/.exec(text);
    if (m) return valid(year(m[3]), +m[2], +m[1]);

    m = /\b(\d{1,2})\s+([A-Za-z]{3,9})\s+(\d{4})\b/.exec(text);
    if (m) {
      var mo = MONTHS[m[2].slice(0, 3).toLowerCase()];
      if (mo) return valid(+m[3], mo, +m[1]);
    }
    return null;
  }

  function year(y) {
    var n = parseInt(y, 10);
    return n < 100 ? 2000 + n : n;
  }

  function valid(y, m, d) {
    if (!(y >= 2000 && y <= 2099 && m >= 1 && m <= 12 && d >= 1 && d <= 31)) return null;
    return y + '-' + pad(m) + '-' + pad(d);
  }

  /* ---------- counterparty ---------- */
  var NOISE = /^(your|the|a|an|account|a\/c|ac|bank|upi|vpa|ref|txn|info|card|no|dear|customer|avl|bal|my|me|atm|pos|upi ref)$/i;
  var LEADING_FILLER = /^(your|my|the|a|an)\s+/i;
  /* A capture holding these is a description of an account, not a person or shop. */
  var NOT_A_NAME = /\b(bank|card|a\/c|acct?|account|wallet|balance|limit)\b|[xX*•]{2,}\d/;

  function cleanName(raw) {
    if (!raw) return null;
    var s = String(raw)
      .replace(/[*_]+/g, ' ')
      .replace(/\s+/g, ' ')
      .replace(/^[\s.,;:-]+|[\s.,;:-]+$/g, '');

    while (LEADING_FILLER.test(s)) s = s.replace(LEADING_FILLER, '');

    if (NOT_A_NAME.test(s)) return null;
    if (/^(rs|inr|₹)\b/i.test(s) || /^[\d.,]/.test(s)) return null;

    s = s.replace(/\b(pvt|private|ltd|limited|india|technologies|services)\b\.?/gi, '')
      .replace(/\s+/g, ' ')
      .trim();

    if (!s || s.length < 2 || NOISE.test(s)) return null;
    if (/^\d+$/.test(s)) return null;

    /* SHOUTING MERCHANT and lowercase vpa handles both become Title Case. */
    if (s === s.toUpperCase() || s === s.toLowerCase()) {
      s = s.toLowerCase().replace(/\b[a-z]/g, function (c) { return c.toUpperCase(); });
    }
    return s;
  }

  function findCounterparty(text) {
    var m;

    /* A UPI handle is the most reliable signal there is. */
    m = /\b([a-z0-9][a-z0-9._-]{1,})@([a-z]{2,})\b/i.exec(text);
    if (m && !/\.(com|in|org|net)$/i.test(m[0])) {
      var handle = m[1];
      if (!/^\d{6,}$/.test(handle)) {
        var pretty = cleanName(handle.replace(/[._-]+/g, ' ').replace(/\d{4,}/g, '').trim());
        if (pretty) return { name: pretty, vpa: m[0].toLowerCase() };
      }
      return { name: cleanName(handle) || handle, vpa: m[0].toLowerCase() };
    }

    /* Where a name ends: the next clause of the sentence. */
    var STOP = '(?:on|for|via|using|from|by|with|towards|ref|refno|upi|dt|date|to|in|at|of|and|your|a\\/c|acct?|account|card|bank|wallet|avl|bal|info|txn|trf)';
    var NAME = "([A-Za-z0-9&'.\\- ]{2,40}?)";
    function pat(prefix, flags) {
      return new RegExp(prefix + '\\s+(?:vpa\\s+)?' + NAME + '(?=\\s+' + STOP + '\\b|\\s*[.,;:()\\[\\]\\/]|$)', flags || 'i');
    }

    var patterns = [
      pat('\\b(?:spent|paid|purchase(?:d)?)\\s+(?:at|to)'),
      pat('\\b(?:trf|transferred)\\s+to'),
      pat('\\b(?:to|towards)'),
      pat('\\bby'),
      pat('\\bfrom'),
      pat('\\bat'),
      /\bUPI\s*[\/:-]\s*(?:[A-Z]{2,3}\s*[\/:-]\s*)?(?:\d+\s*[\/:-]\s*)?([A-Za-z0-9&'.\- ]{2,40}?)(?=[\/:;,.]|$)/i,
      /\b(?:info|desc|narration)\s*[:=-]\s*([A-Za-z0-9&'.\- ]{2,40}?)(?=[;,.]|$)/i,
      /[-–]\s*([A-Z][A-Z ]{2,30})(?=\s*[.,;]|\s*$)/
    ];

    for (var i = 0; i < patterns.length; i++) {
      m = patterns[i].exec(text);
      if (!m) continue;
      /* "refunded to your Kotak Bank Card XX5678" — the words after the capture
       * show it was describing an account, not naming a shop. */
      var tail = text.slice(m.index + m[0].length, m.index + m[0].length + 14);
      if (/^\s*(bank|card|a\/c|acct?|account|wallet)\b/i.test(tail)) continue;
      var name = cleanName(m[1]);
      if (name) return { name: name, vpa: null };
    }
    return { name: null, vpa: null };
  }

  /* ---------- other fields ---------- */
  function findAccountTail(text) {
    var m = /\b(?:a\/c|acc?t?|account|card|ac)\.?\s*(?:no\.?|number)?\s*[:#]?\s*[xX*•]+\s*(\d{3,6})\b/i.exec(text);
    if (m) return m[1];
    m = /\b(?:a\/c|acc?t?|account|card|ac)\.?\s*(?:no\.?|number)?\s*[:#]?\s*(\d{3,6})\b(?!\d)/i.exec(text);
    if (m) return m[1];
    m = /\bX+(\d{3,6})\b/i.exec(text);
    return m ? m[1] : null;
  }

  function findRef(text) {
    var m = /\b(?:ref(?:erence)?|txn|transaction|utr|rrn|upi ref(?: no)?)\.?\s*(?:no\.?|id|#)?\s*[:.\-]?\s*([A-Za-z0-9]{6,25})\b/i.exec(text);
    return m ? m[1] : null;
  }

  function findBalance(text) {
    var m = /\b(?:avl|available|a\/c|closing)?\s*(?:bal|balance)\b[^\d₹]{0,14}(?:rs|inr|₹)?\.?\s*([\d,]+(?:\.\d{1,2})?)/i.exec(text);
    return m ? toPaise(m[1]) : null;
  }

  function findFirst(list, text) {
    for (var i = 0; i < list.length; i++) {
      if (list[i][1].test(text)) return list[i][0];
    }
    return null;
  }

  /* ---------- main ---------- */
  function parse(text) {
    var raw = String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
    var result = {
      ok: false, why: null, confidence: 0, raw: raw,
      type: null, amount: null, date: null,
      counterparty: null, vpa: null, accountTail: null,
      bank: null, method: null, ref: null, balance: null
    };
    if (!raw) { result.why = 'Empty message'; return result; }

    for (var i = 0; i < JUNK.length; i++) {
      if (JUNK[i].re.test(raw)) { result.why = JUNK[i].why; return result; }
    }

    var amounts = findAmounts(raw).filter(function (a) { return a.paise && !a.isBalance; });
    if (!amounts.length) { result.why = 'No amount found'; return result; }
    result.amount = amounts[0].paise;

    var outM = OUT_WORDS.exec(raw);
    var inM = IN_WORDS.exec(raw);
    if (outM && inM) result.type = outM.index < inM.index ? 'expense' : 'income';
    else if (outM) result.type = 'expense';
    else if (inM) result.type = 'income';
    else { result.why = 'Could not tell if money went in or out'; return result; }

    result.date = findDate(raw);
    var who = findCounterparty(raw);
    result.counterparty = who.name;
    result.vpa = who.vpa;
    result.accountTail = findAccountTail(raw);
    result.bank = findFirst(BANKS, raw.replace(/[a-z0-9._-]+@[a-z]+/gi, ' '));
    result.method = findFirst(METHODS, raw);
    result.ref = findRef(raw);
    result.balance = findBalance(raw);

    /* Confidence: amount and direction are table stakes; the rest is how much
     * the user will have to fill in by hand. */
    var score = 0.5;
    if (result.date) score += 0.2;
    if (result.counterparty) score += 0.15;
    if (result.accountTail) score += 0.1;
    if (result.ref) score += 0.05;
    result.confidence = Math.min(1, score);
    result.ok = true;
    return result;
  }

  /* Splits a pasted blob into individual messages. Blank lines first; failing
   * that, a line that starts a fresh amount-bearing sentence. */
  function split(blob) {
    var text = String(blob == null ? '' : blob).replace(/\r/g, '');
    var chunks = text.split(/\n\s*\n+/).map(trim).filter(Boolean);
    if (chunks.length > 1) return chunks;

    var lines = text.split('\n').map(trim).filter(Boolean);
    var moneyLines = lines.filter(function (l) { return /(?:rs|inr|₹)\.?\s*[\d,]/i.test(l); });
    if (lines.length > 1 && moneyLines.length > 1) return lines;

    return chunks.length ? chunks : (text.trim() ? [text.trim()] : []);
  }

  function trim(s) { return s.trim(); }

  /* A stable identity for a parsed message, used to skip re-imports. */
  function fingerprint(p) {
    if (p.ref) return 'ref:' + String(p.ref).toLowerCase();
    return ['fp', p.type, p.amount, p.date || '', (p.counterparty || '').toLowerCase()].join(':');
  }

  var api = { parse: parse, split: split, fingerprint: fingerprint };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.Parse = api;
})(typeof window !== 'undefined' ? window : globalThis);
