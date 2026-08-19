/* Paisa — screen rendering. Every function returns an HTML string; all
 * interaction is wired up by app.js through delegated events. */
(function (global) {
  'use strict';

  var U = global.U, S = global.Store, C = global.Charts;
  var esc = function (s) { return U.esc(s); };

  var TYPE_META = {
    expense: { label: 'Spent', icon: '↓', cls: 'out', sign: -1 },
    income: { label: 'Earned', icon: '↑', cls: 'in', sign: 1 },
    transfer: { label: 'Transfer', icon: '⇄', cls: 'neutral', sign: 0 },
    lend: { label: 'Lent out', icon: '→', cls: 'lend', sign: -1 },
    collect: { label: 'Got back', icon: '←', cls: 'collect', sign: 1 },
    borrow: { label: 'Borrowed', icon: '←', cls: 'borrow', sign: 1 },
    settle: { label: 'Repaid', icon: '→', cls: 'settle', sign: -1 }
  };

  function typeMeta(t) { return TYPE_META[t] || TYPE_META.expense; }

  /* ---------- shared fragments ---------- */

  function emptyState(icon, title, body, actionLabel, actionAttr) {
    return '<div class="empty">' +
      '<div class="empty-icon">' + icon + '</div>' +
      '<h3>' + esc(title) + '</h3>' +
      '<p>' + esc(body) + '</p>' +
      (actionLabel ? '<button class="btn primary" ' + actionAttr + '>' + esc(actionLabel) + '</button>' : '') +
      '</div>';
  }

  /* One row in any transaction list. */
  function txnRow(t) {
    var meta = typeMeta(t.type);
    var cat = t.categoryId ? S.category(t.categoryId) : null;
    var acc = t.accountId ? S.account(t.accountId) : null;
    var acc2 = t.toAccountId ? S.account(t.toAccountId) : null;
    var d = t.debtId ? S.debt(t.debtId) : null;

    var title, sub;
    if (t.type === 'transfer') {
      title = (acc ? acc.name : '?') + ' → ' + (acc2 ? acc2.name : '?');
      sub = t.note || 'Moved between accounts';
    } else if (d) {
      title = d.person || 'Someone';
      sub = meta.label + (t.note ? ' · ' + t.note : '');
    } else {
      title = cat ? cat.name : 'Uncategorised';
      sub = (acc ? acc.name : '') + (t.note ? ' · ' + t.note : '');
    }

    var dot = cat ? cat.color : (d ? U.colorFor(d.person) : 'var(--muted)');
    var amountCls = meta.sign > 0 ? 'amt in' : (meta.sign < 0 ? 'amt out' : 'amt neutral');
    var interestNote = t.interest ? '<span class="pill tiny">incl. ' + esc(U.money(t.interest)) + ' interest</span>' : '';

    return '<li class="row txn" data-txn="' + esc(t.id) + '" tabindex="0" role="button">' +
      '<span class="row-dot" style="background:' + esc(dot) + '"><span class="row-glyph">' + meta.icon + '</span></span>' +
      '<span class="row-main">' +
        '<span class="row-title">' + esc(title) + '</span>' +
        '<span class="row-sub">' + esc(sub) + ' ' + interestNote + '</span>' +
      '</span>' +
      '<span class="' + amountCls + '">' + esc(U.signedMoney(t.amount, meta.sign)) + '</span>' +
      '</li>';
  }

  /* Transactions grouped under date headings, with a per-day net. */
  function txnGroups(list) {
    if (!list.length) return '';
    var groups = [];
    var index = {};
    list.forEach(function (t) {
      if (!index[t.date]) { index[t.date] = { date: t.date, items: [], net: 0 }; groups.push(index[t.date]); }
      index[t.date].items.push(t);
      index[t.date].net += typeMeta(t.type).sign * t.amount;
    });
    return groups.map(function (g) {
      return '<div class="group">' +
        '<div class="group-head"><span>' + esc(U.relativeDay(g.date)) + '</span>' +
        '<span class="' + (g.net >= 0 ? 'in' : 'out') + '">' + esc(U.signedMoney(g.net, g.net >= 0 ? 1 : -1)) + '</span></div>' +
        '<ul class="rows">' + g.items.map(txnRow).join('') + '</ul>' +
        '</div>';
    }).join('');
  }

  /* ---------- dashboard ---------- */

  function dashboard() {
    var range = S.monthRange(null, global.App.state.monthOffset);
    var sum = S.summary(range.from, range.to);
    var balance = S.totalBalance();
    var recv = S.receivables(), pay = S.payables();
    var budget = S.settings().monthlyBudget || 0;

    var html = '';

    html += '<section class="hero card">' +
      '<div class="hero-label">Money in hand</div>' +
      '<div class="hero-value ' + (balance < 0 ? 'out' : '') + '">' + esc(U.money(balance)) + '</div>' +
      '<div class="hero-foot">' +
        '<span>Net worth <strong>' + esc(U.money(S.netWorth())) + '</strong></span>' +
        '<span class="hero-sep"></span>' +
        '<span>' + S.accounts().length + ' accounts</span>' +
      '</div>' +
      '</section>';

    html += '<div class="month-switch">' +
      '<button class="icon-btn" data-month="-1" aria-label="Previous month">‹</button>' +
      '<span>' + esc(range.label) + '</span>' +
      '<button class="icon-btn" data-month="1" aria-label="Next month"' +
        (global.App.state.monthOffset >= 0 ? ' disabled' : '') + '>›</button>' +
      '</div>';

    html += '<div class="tiles">' +
      tile('Earned', U.money(sum.income), 'in') +
      tile('Spent', U.money(sum.expense), 'out') +
      tile('Saved', U.signedMoney(sum.net, sum.net >= 0 ? 1 : -1), sum.net >= 0 ? 'in' : 'out') +
      '</div>';

    if (budget > 0) {
      var used = Math.min(1, sum.expense / budget);
      var over = sum.expense > budget;
      html += '<section class="card budget">' +
        '<div class="card-head"><h3>Monthly budget</h3>' +
        '<span class="' + (over ? 'out' : 'muted') + '">' + esc(U.money(sum.expense)) + ' of ' + esc(U.money(budget)) + '</span></div>' +
        '<div class="progress"><div class="progress-fill' + (over ? ' danger' : '') + '" style="width:' + (used * 100).toFixed(1) + '%"></div></div>' +
        '<p class="muted small">' + (over
          ? esc(U.money(sum.expense - budget)) + ' over budget'
          : esc(U.money(budget - sum.expense)) + ' left for this month') + '</p>' +
        '</section>';
    }

    html += '<div class="duo">' +
      debtTile('To receive', recv, 'owed') +
      debtTile('To pay', pay, 'owe') +
      '</div>';

    var cats = S.byCategory('expense', range.from, range.to).slice(0, 5);
    if (cats.length) {
      var totalSpend = S.byCategory('expense', range.from, range.to)
        .reduce(function (s, c) { return s + c.value; }, 0);
      html += '<section class="card">' +
        '<div class="card-head"><h3>Where it went</h3><a href="#/reports" class="link">Reports</a></div>' +
        '<ul class="cat-list">' + cats.map(function (c) {
          return '<li>' +
            '<span class="cat-name"><i class="swatch" style="background:' + esc(c.color) + '"></i>' + esc(c.label) + '</span>' +
            '<span class="cat-val">' + esc(U.money(c.value)) + '</span>' +
            C.meter(c.value / totalSpend, c.color) +
            '</li>';
        }).join('') + '</ul>' +
        '</section>';
    }

    var recent = S.sortedTransactions().slice(0, 8);
    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Recent</h3><a href="#/ledger" class="link">See all</a></div>' +
      (recent.length ? '<ul class="rows">' + recent.map(txnRow).join('') + '</ul>'
        : emptyState('₹', 'Nothing recorded yet',
            'Tap the + button to log your first rupee — a chai, a salary, a loan to a friend.',
            'Add the first entry', 'data-open-add="1"')) +
      '</section>';

    return html;
  }

  function tile(label, value, cls) {
    return '<div class="tile"><span class="tile-label">' + esc(label) + '</span>' +
      '<span class="tile-value ' + cls + '">' + esc(value) + '</span></div>';
  }

  function debtTile(label, amount, direction) {
    var people = S.debts().filter(function (d) {
      return d.direction === direction && S.outstanding(d.id) > 0;
    }).length;
    return '<a class="card mini" href="#/debts">' +
      '<span class="tile-label">' + esc(label) + '</span>' +
      '<span class="mini-value ' + (direction === 'owed' ? 'in' : 'out') + '">' + esc(U.money(amount)) + '</span>' +
      '<span class="muted small">' + people + (people === 1 ? ' person' : ' people') + '</span>' +
      '</a>';
  }

  /* ---------- ledger ---------- */

  function ledger() {
    var st = global.App.state;
    var q = (st.search || '').toLowerCase().trim();
    var list = S.sortedTransactions().filter(function (t) {
      if (st.filterType !== 'all') {
        if (st.filterType === 'debt') {
          if (!t.debtId) return false;
        } else if (t.type !== st.filterType) return false;
      }
      if (st.filterAccount !== 'all' && t.accountId !== st.filterAccount && t.toAccountId !== st.filterAccount) return false;
      if (st.filterMonth !== 'all') {
        var r = S.monthRange(st.filterMonth + '-15', 0);
        if (!S.inRange(t, r.from, r.to)) return false;
      }
      if (!q) return true;
      var cat = t.categoryId ? S.category(t.categoryId) : null;
      var d = t.debtId ? S.debt(t.debtId) : null;
      var acc = t.accountId ? S.account(t.accountId) : null;
      var hay = [t.note, cat && cat.name, d && d.person, acc && acc.name, U.num(t.amount)]
        .filter(Boolean).join(' ').toLowerCase();
      return hay.indexOf(q) !== -1;
    });

    var totals = list.reduce(function (a, t) {
      var s = typeMeta(t.type).sign;
      if (s > 0) a.in += t.amount; else if (s < 0) a.out += t.amount;
      return a;
    }, { in: 0, out: 0 });

    var months = monthOptions();

    var html = '<div class="toolbar">' +
      '<input id="search" class="input search" type="search" placeholder="Search notes, people, amounts…" value="' + esc(st.search || '') + '" autocomplete="off">' +
      '</div>';

    html += '<div class="chips scroll-x">' +
      ['all', 'expense', 'income', 'transfer', 'debt'].map(function (k) {
        var label = k === 'all' ? 'Everything' : (k === 'debt' ? 'Debt moves' : typeMeta(k).label);
        return '<button class="chip' + (st.filterType === k ? ' on' : '') + '" data-filter-type="' + k + '">' + esc(label) + '</button>';
      }).join('') +
      '</div>';

    html += '<div class="filters">' +
      '<select class="input" data-filter-month>' +
        '<option value="all"' + (st.filterMonth === 'all' ? ' selected' : '') + '>All months</option>' +
        months.map(function (m) {
          return '<option value="' + esc(m.value) + '"' + (st.filterMonth === m.value ? ' selected' : '') + '>' + esc(m.label) + '</option>';
        }).join('') +
      '</select>' +
      '<select class="input" data-filter-account>' +
        '<option value="all"' + (st.filterAccount === 'all' ? ' selected' : '') + '>All accounts</option>' +
        S.accounts(true).map(function (a) {
          return '<option value="' + esc(a.id) + '"' + (st.filterAccount === a.id ? ' selected' : '') + '>' + esc(a.name) + '</option>';
        }).join('') +
      '</select>' +
      '</div>';

    html += '<div class="ledger-summary">' +
      '<span>' + list.length + (list.length === 1 ? ' entry' : ' entries') + '</span>' +
      '<span class="in">+' + esc(U.money(totals.in)) + '</span>' +
      '<span class="out">−' + esc(U.money(totals.out)) + '</span>' +
      '</div>';

    html += list.length ? txnGroups(list)
      : emptyState('🔍', 'No matching entries', 'Try a different month, account or search term.', '', '');

    return html;
  }

  function monthOptions() {
    var seen = {}, out = [];
    S.transactions().forEach(function (t) {
      var key = t.date.slice(0, 7);
      if (seen[key]) return;
      seen[key] = true;
      out.push({ value: key, label: U.date(key + '-01').replace(/^\d+ /, '') });
    });
    return out.sort(function (a, b) { return b.value.localeCompare(a.value); });
  }

  /* ---------- debts ---------- */

  function debts() {
    var st = global.App.state;
    var all = S.debts().map(function (d) {
      return { debt: d, out: S.outstanding(d.id) };
    });
    var showSettled = !!st.showSettled;
    var live = all.filter(function (x) { return showSettled || x.out > 0.5; });

    var owed = live.filter(function (x) { return x.debt.direction === 'owed'; });
    var owe = live.filter(function (x) { return x.debt.direction === 'owe'; });

    var html = '<div class="duo tight">' +
      '<div class="card mini"><span class="tile-label">They owe me</span>' +
      '<span class="mini-value in">' + esc(U.money(S.receivables())) + '</span></div>' +
      '<div class="card mini"><span class="tile-label">I owe them</span>' +
      '<span class="mini-value out">' + esc(U.money(S.payables())) + '</span></div>' +
      '</div>';

    var net = S.receivables() - S.payables();
    html += '<p class="net-note ' + (net >= 0 ? 'in' : 'out') + '">' +
      (net >= 0 ? 'Net position: ' + esc(U.money(net)) + ' in your favour'
                : 'Net position: ' + esc(U.money(-net)) + ' against you') + '</p>';

    html += '<label class="switch-row"><input type="checkbox" data-show-settled' + (showSettled ? ' checked' : '') + '> <span>Show settled</span></label>';

    if (!all.length) {
      html += emptyState('🤝', 'No debts tracked',
        'Record money you lent to someone, or money you borrowed. Every repayment gets tracked against it.',
        'Add a debt', 'data-open-debt="new"');
      return html;
    }

    html += debtSection('They owe me', owed, 'owed');
    html += debtSection('I owe them', owe, 'owe');
    return html;
  }

  function debtSection(title, items, direction) {
    if (!items.length) {
      return '<section class="card"><div class="card-head"><h3>' + esc(title) + '</h3></div>' +
        '<p class="muted small pad">Nothing here.</p></section>';
    }
    items.sort(function (a, b) { return b.out - a.out; });
    return '<section class="card flush">' +
      '<div class="card-head"><h3>' + esc(title) + '</h3>' +
      '<button class="link" data-open-debt="new" data-direction="' + direction + '">+ Add</button></div>' +
      '<ul class="rows">' + items.map(function (x) { return debtRow(x.debt, x.out); }).join('') + '</ul>' +
      '</section>';
  }

  function debtRow(d, out) {
    var due = d.dueDate ? U.daysUntil(d.dueDate) : null;
    var dueLabel = '';
    if (out > 0 && due !== null) {
      if (due < 0) dueLabel = '<span class="pill danger">' + Math.abs(due) + 'd overdue</span>';
      else if (due <= 7) dueLabel = '<span class="pill warn">due in ' + due + 'd</span>';
      else dueLabel = '<span class="pill">due ' + esc(U.date(d.dueDate, 'short')) + '</span>';
    }
    var settled = out <= 0.5;
    return '<li class="row debt' + (settled ? ' settled' : '') + '" data-debt="' + esc(d.id) + '" tabindex="0" role="button">' +
      '<span class="avatar" style="background:' + esc(U.colorFor(d.person)) + '">' + esc(U.initials(d.person)) + '</span>' +
      '<span class="row-main">' +
        '<span class="row-title">' + esc(d.person || 'Someone') + ' ' + dueLabel + '</span>' +
        '<span class="row-sub">' + esc(d.note || (d.direction === 'owed' ? 'Lent out' : 'Borrowed')) + '</span>' +
      '</span>' +
      '<span class="amt ' + (settled ? 'neutral' : (d.direction === 'owed' ? 'in' : 'out')) + '">' +
        (settled ? 'Settled' : esc(U.money(out))) + '</span>' +
      '</li>';
  }

  /* ---------- reports ---------- */

  function reports() {
    var st = global.App.state;
    var range = S.monthRange(null, st.monthOffset);
    var sum = S.summary(range.from, range.to);

    var html = '<div class="month-switch">' +
      '<button class="icon-btn" data-month="-1" aria-label="Previous month">‹</button>' +
      '<span>' + esc(range.label) + '</span>' +
      '<button class="icon-btn" data-month="1" aria-label="Next month"' + (st.monthOffset >= 0 ? ' disabled' : '') + '>›</button>' +
      '</div>';

    html += '<div class="tiles">' +
      tile('Earned', U.money(sum.income), 'in') +
      tile('Spent', U.money(sum.expense), 'out') +
      tile('Saved', U.signedMoney(sum.net, sum.net >= 0 ? 1 : -1), sum.net >= 0 ? 'in' : 'out') +
      '</div>';

    /* 12-month trend */
    var points = [];
    for (var i = 11; i >= 0; i--) {
      var r = S.monthRange(null, st.monthOffset - i);
      var s = S.summary(r.from, r.to);
      points.push({ label: r.label.slice(0, 3), income: s.income, expense: s.expense });
    }
    var peak = points.reduce(function (m, p) { return Math.max(m, p.income, p.expense); }, 0);
    html += '<section class="card">' +
      '<div class="card-head"><h3>Last 12 months</h3><span class="muted small">peak ' + esc(U.moneyShort(peak)) + '</span></div>' +
      C.bars(points) +
      '<div class="legend"><span><i class="swatch in-bg"></i>Earned</span><span><i class="swatch out-bg"></i>Spent</span></div>' +
      '</section>';

    /* spending donut */
    var cats = S.byCategory('expense', range.from, range.to);
    var totalSpend = cats.reduce(function (s, c) { return s + c.value; }, 0);
    html += '<section class="card">' +
      '<div class="card-head"><h3>Spending by category</h3></div>' +
      (totalSpend ? '<div class="donut-wrap">' +
          C.donut(cats.slice(0, 8), { centerTop: U.moneyShort(totalSpend), centerBottom: 'spent' }) +
          '<ul class="cat-list grow">' + cats.map(function (c) {
            return '<li>' +
              '<span class="cat-name"><i class="swatch" style="background:' + esc(c.color) + '"></i>' + esc(c.label) +
              '<span class="muted small"> · ' + c.count + '</span></span>' +
              '<span class="cat-val">' + esc(U.money(c.value)) +
              '<span class="muted small"> ' + ((c.value / totalSpend) * 100).toFixed(0) + '%</span></span>' +
              C.meter(c.value / totalSpend, c.color) +
              '</li>';
          }).join('') + '</ul>' +
        '</div>'
        : '<p class="muted small pad">No spending recorded this month.</p>') +
      '</section>';

    /* income sources */
    var inc = S.byCategory('income', range.from, range.to);
    if (inc.length) {
      var totalInc = inc.reduce(function (s, c) { return s + c.value; }, 0);
      html += '<section class="card">' +
        '<div class="card-head"><h3>Where it came from</h3></div>' +
        '<ul class="cat-list">' + inc.map(function (c) {
          return '<li><span class="cat-name"><i class="swatch" style="background:' + esc(c.color) + '"></i>' + esc(c.label) + '</span>' +
            '<span class="cat-val">' + esc(U.money(c.value)) + '</span>' +
            C.meter(c.value / totalInc, c.color) + '</li>';
        }).join('') + '</ul>' +
        '</section>';
    }

    /* account balances */
    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Accounts</h3><a href="#/settings" class="link">Manage</a></div>' +
      '<ul class="rows">' + S.accounts(true).map(function (a) {
        var bal = S.accountBalance(a.id);
        return '<li class="row">' +
          '<span class="row-dot" style="background:' + esc(U.colorFor(a.name)) + '"><span class="row-glyph">' + accountGlyph(a.type) + '</span></span>' +
          '<span class="row-main"><span class="row-title">' + esc(a.name) + (a.archived ? ' <span class="pill">archived</span>' : '') + '</span>' +
          '<span class="row-sub">' + esc(a.type) + '</span></span>' +
          '<span class="amt ' + (bal < 0 ? 'out' : '') + '">' + esc(U.money(bal)) + '</span>' +
          '</li>';
      }).join('') + '</ul>' +
      '</section>';

    return html;
  }

  function accountGlyph(type) {
    return type === 'cash' ? '₹' : type === 'wallet' ? '◈' : type === 'card' ? '▤' : '▦';
  }

  /* ---------- settings ---------- */

  function settings() {
    var s = S.settings();
    var html = '';

    html += '<section class="card">' +
      '<div class="card-head"><h3>Preferences</h3></div>' +
      '<div class="field"><label for="set-budget">Monthly spending budget (₹)</label>' +
        '<input id="set-budget" class="input" type="number" inputmode="decimal" min="0" step="1" value="' + (s.monthlyBudget ? U.num(s.monthlyBudget) : '') + '" placeholder="0 = no budget"></div>' +
      '<div class="field"><label for="set-start">Month starts on day</label>' +
        '<input id="set-start" class="input" type="number" min="1" max="28" value="' + esc(s.monthStartDay) + '">' +
        '<p class="hint">Set this to your salary date to align months with your pay cycle.</p></div>' +
      '<div class="field"><label for="set-theme">Appearance</label>' +
        '<select id="set-theme" class="input">' +
          ['auto', 'light', 'dark'].map(function (t) {
            return '<option value="' + t + '"' + (s.theme === t ? ' selected' : '') + '>' + t.charAt(0).toUpperCase() + t.slice(1) + '</option>';
          }).join('') +
        '</select></div>' +
      '<button class="btn primary" data-save-settings>Save preferences</button>' +
      '</section>';

    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Accounts</h3><button class="link" data-add-account>+ Add</button></div>' +
      '<ul class="rows">' + S.accounts(true).map(function (a) {
        return '<li class="row" data-account="' + esc(a.id) + '" tabindex="0" role="button">' +
          '<span class="row-dot" style="background:' + esc(U.colorFor(a.name)) + '"><span class="row-glyph">' + accountGlyph(a.type) + '</span></span>' +
          '<span class="row-main"><span class="row-title">' + esc(a.name) + (a.archived ? ' <span class="pill">archived</span>' : '') + '</span>' +
          '<span class="row-sub">Opening ' + esc(U.money(a.openingBalance)) + '</span></span>' +
          '<span class="amt">' + esc(U.money(S.accountBalance(a.id))) + '</span></li>';
      }).join('') + '</ul>' +
      '</section>';

    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Categories</h3><button class="link" data-add-category>+ Add</button></div>' +
      '<div class="chips pad">' + S.categories(null, true).map(function (c) {
        return '<button class="chip cat' + (c.archived ? ' muted' : '') + '" data-category="' + esc(c.id) + '">' +
          '<i class="swatch" style="background:' + esc(c.color) + '"></i>' + esc(c.name) + '</button>';
      }).join('') + '</div>' +
      '</section>';

    var d = S.all();
    html += '<section class="card">' +
      '<div class="card-head"><h3>Backup &amp; sync</h3></div>' +
      '<p class="muted small">Your data lives only on this device. Export a file and import it on your other device to keep both in step.</p>' +
      '<div class="btn-row">' +
        '<button class="btn" data-export-json>Export backup (.json)</button>' +
        '<button class="btn" data-export-csv>Export ledger (.csv)</button>' +
      '</div>' +
      '<div class="field"><label for="import-file">Import a backup</label>' +
        '<input id="import-file" class="input" type="file" accept="application/json,.json">' +
        '<p class="hint">Merge keeps what is already here and adds anything new. Replace wipes this device first.</p>' +
        '<div class="btn-row">' +
          '<button class="btn" data-import="merge">Merge in</button>' +
          '<button class="btn danger" data-import="replace">Replace everything</button>' +
        '</div>' +
      '</div>' +
      '<p class="muted small">' + d.transactions.length + ' entries · ' + d.debts.length + ' debts · ' + d.accounts.length + ' accounts</p>' +
      '</section>';

    html += '<section class="card">' +
      '<div class="card-head"><h3>Danger zone</h3></div>' +
      '<button class="btn danger" data-reset>Delete all data</button>' +
      '</section>';

    html += '<p class="footnote">Paisa · every rupee accounted for</p>';
    return html;
  }

  global.Views = {
    dashboard: dashboard, ledger: ledger, debts: debts, reports: reports, settings: settings,
    txnRow: txnRow, txnGroups: txnGroups, typeMeta: typeMeta, emptyState: emptyState,
    accountGlyph: accountGlyph, monthOptions: monthOptions
  };
})(window);
