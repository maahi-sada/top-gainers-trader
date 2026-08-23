/* Paisa — reads a credit card statement or bill reminder for the card's own
 * facts: its limit, the day the bill closes and the day it falls due.
 *
 * A statement is not a transaction, so it gets its own reader. The purchases
 * listed on it were each alerted at the time; logging them again from the
 * statement would count every rupee twice.
 *
 * Pure functions, no DOM and no storage, so this file is unit tested on its
 * own. It is the JavaScript twin of core/CardStatementParser.kt in the Android
 * app — the same messages must produce the same answers on both sides. */
(function (global) {
  'use strict';

  /* ---------- labels ----------
   * Indian issuers all write the same handful of labels, so the reader looks
   * for a label and takes the value that follows rather than trying to
   * understand sentences. */
  var LABELS = [
    ['availableLimit', '\\b(?:available|avl\\.?|unutilised|unutilized|remaining|open to buy)\\s*(?:credit\\s*)?limit\\b'],
    ['cashLimit', '\\b(?:available\\s+)?cash\\s*(?:withdrawal\\s*)?limit\\b'],
    ['creditLimit', '\\b(?:total\\s+|sanctioned\\s+|permanent\\s+|your\\s+|revised\\s+|new\\s+)?credit\\s*limit\\b'],
    ['minimumDue', '\\bmin(?:imum)?\\.?\\s*(?:amt\\.?|amount)?\\s*(?:due|payable)\\b'],
    ['totalDue', '\\b(?:total\\s*(?:amt\\.?|amount)?\\s*(?:dues?|payable|outstanding)|net\\s*(?:amt\\.?|amount)\\s*due|(?:amt\\.?|amount)\\s*due|new\\s*balance|statement\\s*balance|closing\\s*balance|bill\\s*amount)\\b'],
    ['dueDate', '\\b(?:payment\\s*)?due\\s*date\\b|\\bdue\\s+(?:on|by)\\b|\\bpay(?:able)?\\s+(?:by|before|on or before)\\b|\\blast\\s+date\\s+(?:of|for)\\s+payment\\b'],
    ['statementDate', '\\b(?:statement|bill(?:ing)?)\\s*(?:date|generation\\s*date|generated\\s*on|dated)\\b|\\bdate\\s+of\\s+statement\\b'],
    ['statementPeriod', '\\b(?:statement|bill(?:ing)?)\\s*period\\b|\\bfor\\s+the\\s+period(?:\\s+ending)?\\b|\\bperiod\\s+ending\\b|\\bstatement\\s+for\\b']
  ];

  /* Every label in the text, with anything nested inside a longer label
   * dropped: "Available Credit Limit" must not also read as "Credit Limit",
   * and "Minimum Amount Due" must not also read as "Amount Due". */
  function hits(text) {
    var all = [];
    LABELS.forEach(function (pair) {
      var re = new RegExp(pair[1], 'gi'), m;
      while ((m = re.exec(text)) !== null) {
        all.push({ label: pair[0], start: m.index, end: m.index + m[0].length });
        if (m.index === re.lastIndex) re.lastIndex++;
      }
    });
    all.sort(function (a, b) { return a.start - b.start || (b.end - b.start) - (a.end - a.start); });

    var kept = [];
    all.forEach(function (hit) {
      var overlaps = kept.some(function (k) { return hit.start < k.end && hit.end > k.start; });
      if (!overlaps) kept.push(hit);
    });
    return kept;
  }

  /* The text belonging to a label: what follows it, stopping at the next
   * label, at the second line break, or after 90 characters. Statement tables
   * put the value on the same line or the one below, never further away. */
  function windowFor(text, hit, all) {
    var nextLabel = text.length;
    all.forEach(function (other) {
      if (other.start >= hit.end && other.start < nextLabel) nextLabel = other.start;
    });
    var end = Math.min(text.length, hit.end + 90, nextLabel);
    var breaks = 0;
    for (var i = hit.end; i < end; i++) {
      if (text.charAt(i) === '\n' && ++breaks === 2) { end = i; break; }
    }
    return text.slice(hit.end, end);
  }

  /* ---------- values ---------- */

  function toPaise(str) {
    var n = parseFloat(String(str).replace(/,/g, ''));
    if (!isFinite(n)) return null;
    return Math.round(n * 100);
  }

  var MONEY = '(?:rs|inr|₹)\\.?\\s*([\\d,]+(?:\\.\\d{1,2})?)|([\\d,]+(?:\\.\\d{1,2})?)';
  var LOOKS_LIKE_DATE = /^\s*[\d,.]*\s*[-/]/;

  /* The first amount in a label's window, skipping anything that is a date or
   * the tail of a masked card number. */
  function moneyIn(chunk) {
    var re = new RegExp(MONEY, 'gi'), m;
    while ((m = re.exec(chunk)) !== null) {
      var raw = m[1] || m[2];
      if (!raw) { if (m.index === re.lastIndex) re.lastIndex++; continue; }
      var after = chunk.slice(m.index + m[0].length);
      var before = chunk.slice(0, m.index);
      if (!m[1] && LOOKS_LIKE_DATE.test(after)) continue;
      if (/[x*]$/i.test(before)) continue;
      return toPaise(raw);
    }
    return null;
  }

  /* ---------- dates ---------- */

  var MONTHS = {
    jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6,
    jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12
  };

  var ISO = '\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b';
  var DAY_MONTH_NAME = '\\b(\\d{1,2})(?:st|nd|rd|th)?[-/.\\s]*([A-Za-z]{3,9})\\.?,?[-/.\\s]*(\\d{2,4})\\b';
  var MONTH_NAME_DAY = '\\b([A-Za-z]{3,9})\\.?[-/.\\s]+(\\d{1,2})(?:st|nd|rd|th)?,?[-/.\\s]+(\\d{2,4})\\b';
  var NUMERIC = '\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b';
  var DAY_MONTH_ONLY = '\\b(\\d{1,2})(?:st|nd|rd|th)?[-/.\\s]*([A-Za-z]{3,9})\\b';
  var MONTH_DAY_ONLY = '\\b([A-Za-z]{3,9})\\.?[-/.\\s]+(\\d{1,2})(?:st|nd|rd|th)?\\b';

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function fullYear(raw) { var n = parseInt(raw, 10); return n < 100 ? 2000 + n : n; }

  /* An ISO date string, or null when those numbers are not a real day. */
  function build(year, month, day) {
    if (!(year >= 2000 && year <= 2099)) return null;
    if (!(month >= 1 && month <= 12) || !(day >= 1 && day <= 31)) return null;
    var d = new Date(Date.UTC(year, month - 1, day));
    if (d.getUTCFullYear() !== year || d.getUTCMonth() !== month - 1 || d.getUTCDate() !== day) return null;
    return year + '-' + pad(month) + '-' + pad(day);
  }

  function monthOf(word) {
    var key = String(word).slice(0, 3).toLowerCase();
    return Object.prototype.hasOwnProperty.call(MONTHS, key) ? MONTHS[key] : null;
  }

  function daysBetween(a, b) {
    return Math.abs((Date.parse(a + 'T00:00:00Z') - Date.parse(b + 'T00:00:00Z')) / 86400000);
  }

  /* "Due Date 05 Sep" — the year is left out often enough to be worth
   * guessing, so the nearest one to today wins. */
  function nearestYear(month, day, today) {
    var year = parseInt(today.slice(0, 4), 10);
    var best = null;
    [year - 1, year, year + 1].forEach(function (y) {
      var candidate = build(y, month, day);
      if (!candidate) return;
      if (best === null || daysBetween(candidate, today) < daysBetween(best, today)) best = candidate;
    });
    return best;
  }

  /* Indian statements are day-first; the international issuers write
   * "September 07, 2026". Both are read, full dates before year-less ones. */
  function dateIn(chunk, today) {
    var m = new RegExp(ISO, 'i').exec(chunk);
    if (m) { var iso = build(parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10)); if (iso) return iso; }

    m = new RegExp(DAY_MONTH_NAME, 'i').exec(chunk);
    if (m) {
      var mo = monthOf(m[2]);
      if (mo) { var d1 = build(fullYear(m[3]), mo, parseInt(m[1], 10)); if (d1) return d1; }
    }
    m = new RegExp(MONTH_NAME_DAY, 'i').exec(chunk);
    if (m) {
      var mo2 = monthOf(m[1]);
      if (mo2) { var d2 = build(fullYear(m[3]), mo2, parseInt(m[2], 10)); if (d2) return d2; }
    }
    m = new RegExp(NUMERIC, 'i').exec(chunk);
    if (m) { var d3 = build(fullYear(m[3]), parseInt(m[2], 10), parseInt(m[1], 10)); if (d3) return d3; }

    m = new RegExp(DAY_MONTH_ONLY, 'i').exec(chunk);
    if (m) { var mo3 = monthOf(m[2]); if (mo3) { var d4 = nearestYear(mo3, parseInt(m[1], 10), today); if (d4) return d4; } }

    m = new RegExp(MONTH_DAY_ONLY, 'i').exec(chunk);
    if (m) { var mo4 = monthOf(m[1]); if (mo4) { var d5 = nearestYear(mo4, parseInt(m[2], 10), today); if (d5) return d5; } }

    return null;
  }

  /* The end of a billing period: "19 Jul 2026 to 18 Aug 2026" closes on the
   * later of the two, whichever order the issuer wrote them in. */
  function lastDateIn(chunk, today) {
    var found = [];
    function sweep(source, read) {
      var re = new RegExp(source, 'gi'), m;
      while ((m = re.exec(chunk)) !== null) {
        var got = read(m);
        if (got) found.push(got);
        if (m.index === re.lastIndex) re.lastIndex++;
      }
    }
    sweep(ISO, function (m) { return build(parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10)); });
    sweep(DAY_MONTH_NAME, function (m) { var mo = monthOf(m[2]); return mo && build(fullYear(m[3]), mo, parseInt(m[1], 10)); });
    sweep(MONTH_NAME_DAY, function (m) { var mo = monthOf(m[1]); return mo && build(fullYear(m[3]), mo, parseInt(m[2], 10)); });
    sweep(NUMERIC, function (m) { return build(fullYear(m[3]), parseInt(m[2], 10), parseInt(m[1], 10)); });

    if (!found.length) return dateIn(chunk, today);
    return found.sort()[found.length - 1];
  }

  /* ---------- the card ---------- */

  var ENDING_TAIL = /\bending(?:\s+(?:in|with))?\s*[:#]?\s*(?:[xX*•]{2,}\s*)?(\d{4})\b/i;
  var MASKED_TAIL = /[xX*•]{2,}\s*(\d{4})\b/;
  var NUMBERED_TAIL = /\bcard\s*(?:no\.?|number)?\s*[:#]?\s*(?:[xX*•\s]{2,})?(\d{4})\b(?!\d)/i;

  function findLast4(text) {
    var m = ENDING_TAIL.exec(text) || MASKED_TAIL.exec(text) || NUMBERED_TAIL.exec(text);
    return m ? m[1] : null;
  }

  var BANKS = [
    ['HDFC', /hdfc/i], ['ICICI', /icici/i], ['Axis', /axis/i], ['Kotak', /kotak/i],
    ['IDFC', /idfc/i], ['IndusInd', /indusind/i], ['Canara', /canara/i],
    ['SBI', /\bsbi\b|state bank/i], ['PNB', /\bpnb\b|punjab national/i],
    ['BoB', /\bbob\b|bank of baroda/i], ['Union', /union bank/i],
    ['Yes Bank', /yes bank/i], ['Federal', /federal bank/i], ['RBL', /\brbl\b/i],
    ['AU', /\bau small\b/i],
    ['Amex', /american express|\bamex\b/i], ['OneCard', /\bonecard\b|one ?card/i],
    ['Slice', /\bslice\b/i], ['Citi', /\bciti(?:bank)?\b/i], ['HSBC', /\bhsbc\b/i],
    ['StanChart', /standard chartered/i], ['BOBCARD', /\bbobcard\b/i],
    ['IDBI', /\bidbi\b/i], ['Bajaj', /bajaj (?:finserv|finance)/i]
  ];

  function bankNamed(text) {
    for (var i = 0; i < BANKS.length; i++) if (BANKS[i][1].test(text)) return BANKS[i][0];
    return null;
  }

  var NAMES_A_CARD = /\b(credit card|card statement|statement of your card|cardmember|card account|card no|card ending)\b/i;

  /* Adverts sell a limit; statements report one. A real statement always
   * carries a date, so marketing language is only fatal when no date is
   * anywhere in sight — which keeps a genuine "your limit has been increased"
   * message from being thrown away with the junk. */
  var ADVERT = [
    { re: /\b(eligible|pre-?qualified|pre-?approved)\b/i, why: 'Advert, not a statement' },
    { re: /\b(up ?to|upto)\s*(rs\.?|inr|₹)/i, why: 'Advert, not a statement' },
    { re: /\b(apply now|click here|loan offer|personal loan|know more)\b/i, why: 'Advert, not a statement' },
    { re: /\b(you can (get|avail)|avail (a|an|your))\b/i, why: 'Advert, not a statement' }
  ];

  /* A limit quoted outside a statement is only believable if the bank says it
   * changed. */
  var LIMIT_CHANGED = /\blimit\b[^.\n]{0,40}\b(?:is|has been|was|now|revised|increased|enhanced|reduced|updated|set)\b/i;

  /* ---------- html ---------- */

  var ENTITIES = {
    amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ',
    rupee: '₹', hellip: '…', ndash: '–', mdash: '—'
  };

  /* Flattens a mail body to text, keeping the row-and-cell shape that carries
   * the meaning: each table row becomes a line, each cell boundary a colon. */
  function htmlToText(body) {
    var text = String(body == null ? '' : body);
    if (!/<[a-z!/]/i.test(text)) return text.replace(/\r/g, '');

    text = text
      .replace(/<(script|style)[\s\S]*?<\/\1>/gi, ' ')
      .replace(/<!--[\s\S]*?-->/g, ' ')
      .replace(/<\/t[dh]>/gi, ' : ')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/(tr|p|div|li|h[1-6]|table|section)>/gi, '\n')
      .replace(/<[^>]+>/g, ' ')
      .replace(/&#(\d+);/g, function (_, n) { return String.fromCharCode(parseInt(n, 10)); })
      .replace(/&#x([0-9a-f]+);/gi, function (_, n) { return String.fromCharCode(parseInt(n, 16)); })
      .replace(/&([a-z]+);/gi, function (whole, name) {
        var key = name.toLowerCase();
        return Object.prototype.hasOwnProperty.call(ENTITIES, key) ? ENTITIES[key] : whole;
      });

    return text.split('\n')
      .map(function (line) { return line.replace(/[ \t ]+/g, ' ').replace(/\s*:\s*$/, '').trim(); })
      .filter(function (line) { return line.length > 0; })
      .join('\n');
  }

  /* ---------- reading ---------- */

  function todayISO() {
    var d = new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }

  function no(why, raw) {
    return { ok: false, why: why, confidence: 0, raw: raw || '' };
  }

  /* An email: subject and body together, the body flattened out of HTML. */
  function read(subject, body, today) {
    var parts = [];
    if (subject && String(subject).trim()) parts.push(String(subject).trim());
    var flat = htmlToText(body);
    if (flat) parts.push(flat);
    return readMessage(parts.join('\n'), today);
  }

  /* An SMS, or any already-flattened text. */
  function readMessage(text, today) {
    var ref = today || todayISO();
    var raw = String(text == null ? '' : text).replace(/\r/g, '').trim();
    if (!raw) return no('Empty message');

    var found = hits(raw);
    if (!found.length) return no('No card details in this message', raw);

    var out = {
      creditLimit: null, availableLimit: null, cashLimit: null,
      totalDue: null, minimumDue: null, statementDate: null, dueDate: null
    };

    found.forEach(function (hit) {
      var chunk = windowFor(raw, hit, found);
      if (hit.label === 'statementPeriod') {
        /* A period reads "19 Jul to 18 Aug": the statement closes at the end. */
        if (out.statementDate === null) out.statementDate = lastDateIn(chunk, ref);
      } else if (hit.label === 'dueDate' || hit.label === 'statementDate') {
        if (out[hit.label] === null) out[hit.label] = dateIn(chunk, ref);
      } else if (out[hit.label] === null) {
        out[hit.label] = moneyIn(chunk);
      }
    });

    var last4 = findLast4(raw);
    var bank = bankNamed(raw);
    var hasDate = out.statementDate !== null || out.dueDate !== null;

    if (!hasDate) {
      for (var i = 0; i < ADVERT.length; i++) {
        if (ADVERT[i].re.test(raw)) return no(ADVERT[i].why, raw);
      }
    }
    if (last4 === null && !NAMES_A_CARD.test(raw)) return no('Nothing here names a card', raw);

    var limitStated = out.creditLimit !== null && (hasDate || LIMIT_CHANGED.test(raw));
    if (!hasDate && !limitStated) return no('No statement date, due date or limit', raw);
    if (bank === null && last4 === null) return no('Could not tell which card this is', raw);

    var confidence = 0.4;
    if (last4 !== null) confidence += 0.20;
    if (out.dueDate !== null) confidence += 0.15;
    if (out.statementDate !== null) confidence += 0.15;
    if (out.creditLimit !== null) confidence += 0.10;

    return {
      ok: true,
      why: null,
      confidence: Math.min(1, confidence),
      bank: bank,
      last4: last4,
      creditLimit: limitStated ? out.creditLimit : null,
      availableLimit: out.availableLimit,
      cashLimit: out.cashLimit,
      statementDate: out.statementDate,
      dueDate: out.dueDate,
      statementDay: out.statementDate ? parseInt(out.statementDate.slice(8), 10) : null,
      dueDay: out.dueDate ? parseInt(out.dueDate.slice(8), 10) : null,
      totalDue: out.totalDue,
      minimumDue: out.minimumDue,
      raw: raw
    };
  }

  /* "HDFC statement of 18 Aug 2026" — where a card's details came from. */
  var SHORT_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  function describe(st) {
    var who = (st && st.bank) || 'Card';
    var on = (st && (st.statementDate || st.dueDate)) || null;
    if (!on) return who + ' statement';
    var month = SHORT_MONTHS[parseInt(on.slice(5, 7), 10) - 1];
    return who + ' statement of ' + parseInt(on.slice(8), 10) + ' ' + month + ' ' + on.slice(0, 4);
  }

  /* ---------- filing it against a card ---------- */

  function money(paise, withPaise) {
    var value = (paise || 0) / 100;
    try {
      return new Intl.NumberFormat('en-IN', {
        style: 'currency', currency: 'INR',
        minimumFractionDigits: withPaise ? 2 : 0,
        maximumFractionDigits: withPaise ? 2 : 0
      }).format(value);
    } catch (e) {
      return '₹' + value.toFixed(withPaise ? 2 : 0);
    }
  }

  function ordinal(day) {
    var suffix = (day % 100 >= 11 && day % 100 <= 13) ? 'th'
      : day % 10 === 1 ? 'st' : day % 10 === 2 ? 'nd' : day % 10 === 3 ? 'rd' : 'th';
    return day + suffix;
  }

  /* The card a statement belongs to: by its digits first, then by issuer when
   * only one card could be meant. */
  function matchCard(st, cards, tails) {
    var i;
    if (st.last4) {
      for (i = 0; i < cards.length; i++) if (cards[i].last4 === st.last4) return cards[i];
      var learned = tails && tails[st.last4];
      if (learned) {
        for (i = 0; i < cards.length; i++) if (cards[i].id === learned) return cards[i];
      }
    }
    if (!st.bank) return null;
    var sameBank = cards.filter(function (c) {
      return String(c.name || '').toLowerCase().indexOf(st.bank.toLowerCase()) >= 0;
    });
    var free = sameBank.filter(function (c) { return !c.last4 || c.last4 === st.last4; });
    return free.length === 1 ? free[0] : null;
  }

  function newCardName(st) {
    return [st.bank || 'Credit', 'Card'].concat(st.last4 ? ['••' + st.last4] : []).join(' ');
  }

  /**
   * Works out what a statement changes about a card, without touching storage.
   *
   * The bank is the authority on these numbers, so a statement overrides what
   * was typed in by hand — but only for the fields it actually stated. A card
   * that has never been seen is proposed as a new one, because reading the
   * details out of your mail is the whole point of doing this automatically.
   *
   * Returns { applied, created, accountId, patch, account, changes, reason }.
   */
  function apply(st, accounts, tails, today) {
    if (!st || !st.ok) return { applied: false, created: false, changes: [], reason: (st && st.why) || 'Not a statement' };

    var cards = (accounts || []).filter(function (a) { return a.type === 'card' && !a.archived; });
    var matched = matchCard(st, cards, tails || {});
    var created = !matched;
    var target = matched || { name: newCardName(st), type: 'card', last4: st.last4 || null, statementDay: 1, dueDay: 1 };

    if (created && !st.bank && !st.last4) {
      return { applied: false, created: false, changes: [], reason: 'Could not tell which card this is' };
    }

    var patch = {};
    var changes = [];

    if (st.last4 && target.last4 !== st.last4) {
      patch.last4 = st.last4;
      if (!created) changes.push('card ••' + st.last4);
    }
    if (st.creditLimit !== null && st.creditLimit > 0 && (target.creditLimit || 0) !== st.creditLimit) {
      patch.creditLimit = st.creditLimit;
      changes.push('limit ' + money(st.creditLimit, false));
    }
    if (st.statementDay !== null && (target.statementDay || 1) !== st.statementDay) {
      patch.statementDay = st.statementDay;
      changes.push('statement on the ' + ordinal(st.statementDay));
    }
    if (st.dueDay !== null && (target.dueDay || 1) !== st.dueDay) {
      patch.dueDay = st.dueDay;
      changes.push('due on the ' + ordinal(st.dueDay));
    }
    if (st.totalDue !== null && (target.lastStatementDue || 0) !== st.totalDue) {
      patch.lastStatementDue = st.totalDue;
      changes.push('bill ' + money(st.totalDue, true));
    }
    if (st.minimumDue !== null && (target.lastMinimumDue || 0) !== st.minimumDue) {
      patch.lastMinimumDue = st.minimumDue;
      changes.push('minimum ' + money(st.minimumDue, true));
    }

    if (!created && !changes.length) {
      return { applied: false, created: false, accountId: target.id, changes: [], reason: 'Already knew all of that' };
    }

    patch.lastStatementDate = st.statementDate || st.dueDate || target.lastStatementDate || null;
    patch.detailsFrom = describe(st);

    if (created) {
      var account = {};
      Object.keys(target).forEach(function (k) { account[k] = target[k]; });
      Object.keys(patch).forEach(function (k) { account[k] = patch[k]; });
      return {
        applied: true, created: true, account: account, patch: patch,
        changes: ['added from your ' + describe(st)].concat(changes)
      };
    }
    return { applied: true, created: false, accountId: target.id, patch: patch, changes: changes };
  }

  /* "HDFC Card ••4321: limit ₹3,00,000, due on the 7th" */
  function summarise(result, name) {
    if (!result || !result.applied) return (result && result.reason) || 'Nothing to update';
    return (name || 'Card') + ': ' + result.changes.join(', ');
  }

  var api = {
    read: read, readMessage: readMessage, htmlToText: htmlToText,
    describe: describe, apply: apply, summarise: summarise, ordinal: ordinal
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.CardStatement = api;
})(typeof window !== 'undefined' ? window : globalThis);
