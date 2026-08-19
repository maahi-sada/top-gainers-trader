/* Paisa — app shell: routing, forms, sheets and every event handler. */
(function (global) {
  'use strict';

  var U = global.U, S = global.Store, V = global.Views;
  var esc = function (s) { return U.esc(s); };

  var ROUTES = {
    dashboard: { title: 'Paisa', render: function () { return V.dashboard(); } },
    ledger: { title: 'Ledger', render: function () { return V.ledger(); } },
    inbox: { title: 'Inbox', render: function () { return V.inbox(); } },
    debts: { title: 'Debts', render: function () { return V.debts(); } },
    reports: { title: 'Reports', render: function () { return V.reports(); } },
    settings: { title: 'Settings', render: function () { return V.settings(); } }
  };

  var App = {
    state: {
      route: 'dashboard',
      monthOffset: 0,
      search: '',
      filterType: 'all',
      filterMonth: 'all',
      filterAccount: 'all',
      showSettled: false
    }
  };
  global.App = App;

  /* ---------- routing ---------- */

  function currentRoute() {
    var hash = (location.hash || '#/dashboard').replace(/^#\/?/, '');
    var name = hash.split('/')[0] || 'dashboard';
    return ROUTES[name] ? name : 'dashboard';
  }

  function render() {
    var name = currentRoute();
    App.state.route = name;
    var route = ROUTES[name];

    U.el('#view').innerHTML = route.render();
    U.el('#title').textContent = route.title;
    document.title = (name === 'dashboard' ? 'Paisa' : route.title + ' · Paisa');

    U.els('[data-nav]').forEach(function (b) {
      b.classList.toggle('on', b.getAttribute('data-nav') === name);
      if (b.getAttribute('data-nav') === name) b.setAttribute('aria-current', 'page');
      else b.removeAttribute('aria-current');
    });

    U.el('#app').setAttribute('data-route', name);
    updateBadges();
    window.scrollTo(0, 0);
  }

  /* ---------- sheet (modal) ---------- */

  var sheetOnClose = null;

  /* Each open builds a fresh inner element, so handlers bound by one sheet can
   * never leak onto the next one. */
  function openSheet(title, body, onMount) {
    var sheet = U.el('#sheet');
    var host = U.el('#sheet-body');
    host.innerHTML = '';
    var inner = document.createElement('div');
    inner.className = 'sheet-inner';
    inner.innerHTML = body;
    host.appendChild(inner);

    U.el('#sheet-title').textContent = title;
    sheet.classList.add('open');
    sheet.setAttribute('aria-hidden', 'false');
    document.body.classList.add('locked');
    sheet.scrollTop = 0;
    host.scrollTop = 0;

    if (onMount) onMount(inner);
    var focusMe = U.el('[data-autofocus]', inner);
    if (focusMe && window.matchMedia('(min-width: 720px)').matches) {
      setTimeout(function () { focusMe.focus(); }, 60);
    }
    return inner;
  }

  function closeSheet() {
    var sheet = U.el('#sheet');
    if (!sheet.classList.contains('open')) return;
    sheet.classList.remove('open');
    sheet.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('locked');
    U.el('#sheet-body').innerHTML = '';
    if (sheetOnClose) { var fn = sheetOnClose; sheetOnClose = null; fn(); }
    render();
  }

  /* ---------- transaction form ---------- */

  var TYPES = [
    { key: 'expense', label: 'Spent' },
    { key: 'income', label: 'Earned' },
    { key: 'transfer', label: 'Transfer' },
    { key: 'lend', label: 'Lent out' },
    { key: 'borrow', label: 'Borrowed' },
    { key: 'collect', label: 'Got back' },
    { key: 'settle', label: 'Repaid' }
  ];

  function draftFromTxn(t) {
    return {
      id: t.id,
      type: t.type,
      amount: U.num(t.amount),
      interest: t.interest ? U.num(t.interest) : '',
      date: t.date,
      accountId: t.accountId,
      toAccountId: t.toAccountId,
      categoryId: t.categoryId,
      debtId: t.debtId,
      note: t.note || '',
      personMode: 'existing',
      person: '',
      dueDate: ''
    };
  }

  /* Fields the form does not own but must not lose when it re-renders. */
  function blankDraft(type) {
    var accs = S.accounts();
    return {
      id: null,
      type: type || 'expense',
      amount: '',
      interest: '',
      date: S.today(),
      accountId: accs.length ? accs[0].id : null,
      toAccountId: accs.length > 1 ? accs[1].id : null,
      categoryId: null,
      debtId: null,
      note: '',
      personMode: 'new',
      person: '',
      dueDate: ''
    };
  }

  /* Reads whatever the user has typed so a type switch never loses input. */
  function readForm(root, draft) {
    function val(sel) { var e = U.el(sel, root); return e ? e.value : undefined; }
    var v;
    if ((v = val('[name=amount]')) !== undefined) draft.amount = v;
    if ((v = val('[name=interest]')) !== undefined) draft.interest = v;
    if ((v = val('[name=date]')) !== undefined) draft.date = v;
    if ((v = val('[name=accountId]')) !== undefined) draft.accountId = v;
    if ((v = val('[name=toAccountId]')) !== undefined) draft.toAccountId = v;
    if ((v = val('[name=categoryId]')) !== undefined) draft.categoryId = v;
    if ((v = val('[name=debtId]')) !== undefined) draft.debtId = v;
    if ((v = val('[name=person]')) !== undefined) draft.person = v;
    if ((v = val('[name=dueDate]')) !== undefined) draft.dueDate = v;
    if ((v = val('[name=note]')) !== undefined) draft.note = v;
    return draft;
  }

  function accountSelect(name, selected, label) {
    var accs = S.accounts();
    return '<div class="field"><label>' + esc(label) + '</label>' +
      '<select class="input" name="' + name + '">' +
      accs.map(function (a) {
        return '<option value="' + esc(a.id) + '"' + (a.id === selected ? ' selected' : '') + '>' +
          esc(a.name) + ' · ' + esc(U.money(S.accountBalance(a.id))) + '</option>';
      }).join('') +
      '</select></div>';
  }

  function categorySelect(kind, selected) {
    var cats = S.categories(kind);
    return '<div class="field"><label>Category</label>' +
      '<select class="input" name="categoryId">' +
      cats.map(function (c) {
        return '<option value="' + esc(c.id) + '"' + (c.id === selected ? ' selected' : '') + '>' + esc(c.name) + '</option>';
      }).join('') +
      '</select></div>';
  }

  function debtSelect(direction, selected) {
    var open = S.debts().filter(function (d) {
      return d.direction === direction && (S.outstanding(d.id) > 0.5 || d.id === selected);
    });
    if (!open.length) {
      return '<p class="hint warn-box">No open ' + (direction === 'owed' ? 'lending' : 'borrowing') +
        ' recorded yet. Add a “' + (direction === 'owed' ? 'Lent out' : 'Borrowed') + '” entry first.</p>';
    }
    return '<div class="field"><label>Who</label>' +
      '<select class="input" name="debtId">' +
      open.map(function (d) {
        return '<option value="' + esc(d.id) + '"' + (d.id === selected ? ' selected' : '') + '>' +
          esc(d.person) + ' · ' + esc(U.money(S.outstanding(d.id))) + ' outstanding</option>';
      }).join('') +
      '</select></div>';
  }

  function personField(draft, direction) {
    var existing = S.debts().filter(function (d) { return d.direction === direction; });
    var mode = draft.personMode || (existing.length ? 'existing' : 'new');
    var html = '<div class="field"><label>Person</label>';
    if (existing.length) {
      html += '<select class="input" name="debtId" data-person-mode>' +
        existing.map(function (d) {
          return '<option value="' + esc(d.id) + '"' + (mode === 'existing' && draft.debtId === d.id ? ' selected' : '') + '>' +
            esc(d.person) + (S.outstanding(d.id) > 0.5 ? ' · ' + esc(U.money(S.outstanding(d.id))) + ' open' : ' · settled') + '</option>';
        }).join('') +
        '<option value="__new"' + (mode === 'new' ? ' selected' : '') + '>+ Someone new…</option>' +
        '</select>';
    }
    html += '</div>';
    if (!existing.length || mode === 'new') {
      html += '<div class="field"><label>Name</label>' +
        '<input class="input" name="person" type="text" placeholder="e.g. Ramesh" value="' + esc(draft.person) + '"' +
        (existing.length ? '' : ' data-autofocus-secondary') + '></div>' +
        '<div class="field"><label>Due date <span class="muted">(optional)</span></label>' +
        '<input class="input" name="dueDate" type="date" value="' + esc(draft.dueDate || '') + '"></div>';
    }
    return html;
  }

  function txnForm(draft) {
    var t = draft.type;
    var html = '';

    html += '<div class="chips scroll-x type-chips">' + TYPES.map(function (x) {
      return '<button type="button" class="chip' + (t === x.key ? ' on' : '') + '" data-type="' + x.key + '">' + esc(x.label) + '</button>';
    }).join('') + '</div>';

    html += '<div class="amount-field">' +
      '<span class="rupee">₹</span>' +
      '<input class="amount-input" name="amount" type="number" inputmode="decimal" step="0.01" min="0" ' +
      'placeholder="0.00" value="' + esc(draft.amount) + '" data-autofocus>' +
      '</div>';

    html += '<div class="chips quick">' + [50, 100, 500, 1000, 5000].map(function (n) {
      return '<button type="button" class="chip" data-quick="' + n + '">+' + n + '</button>';
    }).join('') + '<button type="button" class="chip" data-quick="clear">C</button></div>';

    html += '<div class="field"><label>Date</label>' +
      '<input class="input" name="date" type="date" value="' + esc(draft.date) + '" max="2100-12-31"></div>';

    if (t === 'expense' || t === 'income') {
      html += categorySelect(t, draft.categoryId);
      html += accountSelect('accountId', draft.accountId, t === 'expense' ? 'Paid from' : 'Received in');
    } else if (t === 'transfer') {
      html += accountSelect('accountId', draft.accountId, 'From');
      html += accountSelect('toAccountId', draft.toAccountId, 'To');
    } else if (t === 'lend' || t === 'borrow') {
      html += personField(draft, t === 'lend' ? 'owed' : 'owe');
      html += accountSelect('accountId', draft.accountId, t === 'lend' ? 'Paid from' : 'Received in');
      html += '<p class="hint">' + (t === 'lend'
        ? 'Money leaves your account but is still yours — it is tracked as a receivable, not as spending.'
        : 'Money arrives but it is not income — it is tracked as something you owe.') + '</p>';
    } else if (t === 'collect' || t === 'settle') {
      html += debtSelect(t === 'collect' ? 'owed' : 'owe', draft.debtId);
      html += accountSelect('accountId', draft.accountId, t === 'collect' ? 'Received in' : 'Paid from');
      html += '<div class="field"><label>Of which interest <span class="muted">(optional)</span></label>' +
        '<input class="input" name="interest" type="number" inputmode="decimal" step="0.01" min="0" placeholder="0.00" value="' + esc(draft.interest) + '">' +
        '<p class="hint">Interest counts as ' + (t === 'collect' ? 'income' : 'an expense') + '. The rest reduces the outstanding amount.</p></div>';
    }

    html += '<div class="field"><label>Note <span class="muted">(optional)</span></label>' +
      '<input class="input" name="note" type="text" placeholder="What was this for?" value="' + esc(draft.note) + '"></div>';

    html += '<div class="sheet-actions">' +
      (draft.id ? '<button type="button" class="btn danger" data-delete-txn="' + esc(draft.id) + '">Delete</button>' : '') +
      '<button type="button" class="btn primary grow" data-save-txn>' + (draft.id ? 'Save changes' : 'Add entry') + '</button>' +
      '</div>';

    return html;
  }

  function openTxnSheet(draft) {
    var current = draft;

    function mount(root) {
      root.addEventListener('click', function (e) {
        var typeBtn = e.target.closest('[data-type]');
        if (typeBtn) {
          readForm(root, current);
          current.type = typeBtn.getAttribute('data-type');
          /* Sensible default category when switching between spend and earn. */
          var kind = current.type === 'income' ? 'income' : 'expense';
          var cat = current.categoryId ? S.category(current.categoryId) : null;
          if (!cat || cat.kind !== kind) {
            var first = S.categories(kind)[0];
            current.categoryId = first ? first.id : null;
          }
          rerender(root);
          return;
        }
        var quick = e.target.closest('[data-quick]');
        if (quick) {
          var amtEl = U.el('[name=amount]', root);
          var q = quick.getAttribute('data-quick');
          if (q === 'clear') amtEl.value = '';
          else amtEl.value = ((parseFloat(amtEl.value) || 0) + parseInt(q, 10)).toString();
          amtEl.focus();
          return;
        }
        if (e.target.closest('[data-save-txn]')) { submit(root); return; }
        var del = e.target.closest('[data-delete-txn]');
        if (del) {
          if (confirm('Delete this entry? This cannot be undone.')) {
            S.deleteTransaction(del.getAttribute('data-delete-txn'));
            closeSheet();
            U.toast('Entry deleted');
          }
        }
      });

      root.addEventListener('change', function (e) {
        if (e.target.matches('[data-person-mode]')) {
          readForm(root, current);
          current.personMode = e.target.value === '__new' ? 'new' : 'existing';
          if (current.personMode === 'existing') current.debtId = e.target.value;
          rerender(root);
        }
      });

      root.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && e.target.matches('input:not([type=date])')) {
          e.preventDefault();
          submit(root);
        }
      });
    }

    function rerender(root) {
      root.innerHTML = txnForm(current);
      var focusMe = U.el('[data-autofocus-secondary]', root);
      if (focusMe) focusMe.focus();
    }

    function submit(root) {
      readForm(root, current);
      var amount = S.toPaise(current.amount);
      if (amount <= 0) { U.toast('Enter an amount first', 'error'); return; }
      if (!current.date) { U.toast('Pick a date', 'error'); return; }

      var t = current.type;
      var payload = {
        type: t, amount: amount, date: current.date,
        note: current.note, accountId: current.accountId,
        toAccountId: null, categoryId: null, debtId: null, interest: 0
      };

      if (t === 'transfer') {
        if (current.accountId === current.toAccountId) { U.toast('Pick two different accounts', 'error'); return; }
        payload.toAccountId = current.toAccountId;
      } else if (t === 'expense' || t === 'income') {
        payload.categoryId = current.categoryId;
      } else if (t === 'lend' || t === 'borrow') {
        var direction = t === 'lend' ? 'owed' : 'owe';
        var debtId = current.debtId;
        if (current.personMode === 'new' || !debtId || debtId === '__new') {
          var name = (current.person || '').trim();
          if (!name) { U.toast('Whose debt is this?', 'error'); return; }
          debtId = S.addDebt({ direction: direction, person: name, dueDate: current.dueDate || null }).id;
        }
        payload.debtId = debtId;
      } else if (t === 'collect' || t === 'settle') {
        if (!current.debtId) { U.toast('Pick who this is for', 'error'); return; }
        payload.debtId = current.debtId;
        var interest = S.toPaise(current.interest);
        if (interest > amount) { U.toast('Interest cannot exceed the amount', 'error'); return; }
        payload.interest = interest;
      }

      if (current.inboxId) {
        var item = S.inboxItem(current.inboxId);
        if (item) { payload.fp = item.fp; payload.source = item.source; }
        S.addTransaction(payload);
        S.learnFromInbox(current.inboxId, payload);
      } else if (current.id) {
        S.updateTransaction(current.id, payload);
      } else {
        S.addTransaction(payload);
      }

      closeSheet();
      U.toast(current.id ? 'Entry updated' : U.money(amount) + ' recorded');
    }

    openSheet(draft.id ? 'Edit entry' : 'New entry', txnForm(current), mount);
  }

  /* ---------- debt sheet ---------- */

  function openDebtSheet(debtId) {
    var d = S.debt(debtId);
    if (!d) return;
    var out = S.outstanding(d.id);
    var moves = S.sortedTransactions().filter(function (t) { return t.debtId === d.id; });
    var lent = moves.filter(function (t) { return t.type === 'lend' || t.type === 'borrow'; })
      .reduce(function (s, t) { return s + t.amount; }, 0);
    var paid = moves.filter(function (t) { return t.type === 'collect' || t.type === 'settle'; })
      .reduce(function (s, t) { return s + S.principalOf(t); }, 0);
    var interest = moves.reduce(function (s, t) { return s + (t.interest || 0); }, 0);
    var progress = lent ? Math.min(1, paid / lent) : 0;

    var body = '';
    body += '<div class="debt-head">' +
      '<span class="avatar big" style="background:' + esc(U.colorFor(d.person)) + '">' + esc(U.initials(d.person)) + '</span>' +
      '<div><div class="debt-name">' + esc(d.person) + '</div>' +
      '<div class="muted small">' + (d.direction === 'owed' ? 'Owes you' : 'You owe them') +
      (d.dueDate ? ' · due ' + esc(U.date(d.dueDate)) : '') + '</div></div>' +
      '</div>';

    body += '<div class="hero card inner">' +
      '<div class="hero-label">Outstanding</div>' +
      '<div class="hero-value ' + (out > 0.5 ? (d.direction === 'owed' ? 'in' : 'out') : '') + '">' +
      esc(out > 0.5 ? U.money(out) : 'Settled') + '</div>' +
      '<div class="progress"><div class="progress-fill" style="width:' + (progress * 100).toFixed(1) + '%"></div></div>' +
      '<div class="hero-foot"><span>' + esc(U.money(paid)) + ' of ' + esc(U.money(lent)) + ' cleared</span>' +
      (interest ? '<span class="hero-sep"></span><span>' + esc(U.money(interest)) + ' interest</span>' : '') +
      '</div></div>';

    body += '<div class="btn-row">' +
      '<button class="btn primary grow" data-debt-pay="' + esc(d.id) + '">' +
        (d.direction === 'owed' ? 'Record money received' : 'Record repayment') + '</button>' +
      '<button class="btn" data-debt-more="' + esc(d.id) + '">' +
        (d.direction === 'owed' ? 'Lend more' : 'Borrow more') + '</button>' +
      '</div>';

    body += '<div class="field"><label>Due date</label>' +
      '<input class="input" name="dueDate" type="date" value="' + esc(d.dueDate || '') + '" data-debt-due="' + esc(d.id) + '"></div>';
    body += '<div class="field"><label>Note</label>' +
      '<input class="input" type="text" value="' + esc(d.note || '') + '" placeholder="What was it for?" data-debt-note="' + esc(d.id) + '"></div>';

    body += '<h4 class="sub-head">History</h4>';
    body += moves.length ? '<ul class="rows">' + moves.map(V.txnRow).join('') + '</ul>'
      : '<p class="muted small">No movements recorded.</p>';

    body += '<div class="sheet-actions"><button class="btn danger grow" data-debt-delete="' + esc(d.id) + '">Delete debt and its history</button></div>';

    openSheet(d.direction === 'owed' ? 'Money lent out' : 'Money borrowed', body, function (root) {
      root.addEventListener('click', function (e) {
        var pay = e.target.closest('[data-debt-pay]');
        if (pay) {
          var dd = S.debt(pay.getAttribute('data-debt-pay'));
          var draft = blankDraft(dd.direction === 'owed' ? 'collect' : 'settle');
          draft.debtId = dd.id;
          draft.amount = U.num(S.outstanding(dd.id));
          openTxnSheet(draft);
          return;
        }
        var more = e.target.closest('[data-debt-more]');
        if (more) {
          var dm = S.debt(more.getAttribute('data-debt-more'));
          var d2 = blankDraft(dm.direction === 'owed' ? 'lend' : 'borrow');
          d2.personMode = 'existing';
          d2.debtId = dm.id;
          openTxnSheet(d2);
          return;
        }
        var del = e.target.closest('[data-debt-delete]');
        if (del) {
          if (confirm('Delete this debt and every payment recorded against it?')) {
            S.deleteDebt(del.getAttribute('data-debt-delete'));
            closeSheet();
            U.toast('Debt deleted');
          }
          return;
        }
        var row = e.target.closest('[data-txn]');
        if (row) openTxnSheet(draftFromTxn(S.transaction(row.getAttribute('data-txn'))));
      });

      root.addEventListener('change', function (e) {
        if (e.target.matches('[data-debt-due]')) {
          S.updateDebt(e.target.getAttribute('data-debt-due'), { dueDate: e.target.value || null });
          U.toast('Due date saved');
        }
        if (e.target.matches('[data-debt-note]')) {
          S.updateDebt(e.target.getAttribute('data-debt-note'), { note: e.target.value });
          U.toast('Note saved');
        }
      });
    });
  }

  /* ---------- account & category sheets ---------- */

  function openAccountSheet(id) {
    var a = id ? S.account(id) : null;
    var types = ['cash', 'bank', 'wallet', 'card'];
    var body = '<div class="field"><label>Name</label>' +
      '<input class="input" name="name" type="text" value="' + esc(a ? a.name : '') + '" placeholder="e.g. HDFC Savings" data-autofocus></div>' +
      '<div class="field"><label>Type</label><select class="input" name="type">' +
      types.map(function (t) {
        return '<option value="' + t + '"' + (a && a.type === t ? ' selected' : '') + '>' + t.charAt(0).toUpperCase() + t.slice(1) + '</option>';
      }).join('') + '</select></div>' +
      '<div class="field"><label>Opening balance (₹)</label>' +
      '<input class="input" name="opening" type="number" inputmode="decimal" step="0.01" value="' + (a ? U.num(a.openingBalance) : '') + '" placeholder="0.00">' +
      '<p class="hint">What was in this account when you started tracking.</p></div>' +
      (a ? '<label class="switch-row"><input type="checkbox" name="archived"' + (a.archived ? ' checked' : '') + '> <span>Archived (hide from new entries)</span></label>' : '') +
      '<div class="sheet-actions">' +
      (a ? '<button class="btn danger" data-account-delete="' + esc(a.id) + '">Delete</button>' : '') +
      '<button class="btn primary grow" data-account-save>' + (a ? 'Save' : 'Add account') + '</button></div>';

    openSheet(a ? 'Edit account' : 'New account', body, function (root) {
      root.addEventListener('click', function (e) {
        if (e.target.closest('[data-account-save]')) {
          var name = U.el('[name=name]', root).value.trim();
          if (!name) { U.toast('Give the account a name', 'error'); return; }
          var patch = {
            name: name,
            type: U.el('[name=type]', root).value,
            openingBalance: S.toPaise(U.el('[name=opening]', root).value)
          };
          var arch = U.el('[name=archived]', root);
          if (arch) patch.archived = arch.checked;
          if (a) S.updateAccount(a.id, patch); else S.addAccount(patch);
          closeSheet();
          U.toast('Account saved');
          return;
        }
        var del = e.target.closest('[data-account-delete]');
        if (del) {
          var removed = S.deleteAccount(del.getAttribute('data-account-delete'));
          closeSheet();
          U.toast(removed ? 'Account deleted' : 'Account has entries — archived instead');
        }
      });
    });
  }

  function openCategorySheet(id) {
    var c = id ? S.category(id) : null;
    var palette = ['#f97316', '#84cc16', '#0ea5e9', '#8b5cf6', '#06b6d4', '#ec4899', '#ef4444',
      '#6366f1', '#f43f5e', '#14b8a6', '#a855f7', '#22c55e', '#eab308', '#94a3b8'];
    var body = '<div class="field"><label>Name</label>' +
      '<input class="input" name="name" type="text" value="' + esc(c ? c.name : '') + '" placeholder="e.g. Chai &amp; snacks" data-autofocus></div>' +
      '<div class="field"><label>Kind</label><select class="input" name="kind">' +
      '<option value="expense"' + (c && c.kind === 'expense' ? ' selected' : '') + '>Spending</option>' +
      '<option value="income"' + (c && c.kind === 'income' ? ' selected' : '') + '>Earning</option>' +
      '</select></div>' +
      '<div class="field"><label>Colour</label><div class="palette">' +
      palette.map(function (p) {
        return '<button type="button" class="swatch-btn' + (c && c.color === p ? ' on' : '') + '" data-color="' + p + '" style="background:' + p + '" aria-label="' + p + '"></button>';
      }).join('') + '</div><input type="hidden" name="color" value="' + esc(c ? c.color : palette[0]) + '"></div>' +
      (c ? '<label class="switch-row"><input type="checkbox" name="archived"' + (c.archived ? ' checked' : '') + '> <span>Archived</span></label>' : '') +
      '<div class="sheet-actions">' +
      (c ? '<button class="btn danger" data-category-delete="' + esc(c.id) + '">Delete</button>' : '') +
      '<button class="btn primary grow" data-category-save>' + (c ? 'Save' : 'Add category') + '</button></div>';

    openSheet(c ? 'Edit category' : 'New category', body, function (root) {
      root.addEventListener('click', function (e) {
        var sw = e.target.closest('[data-color]');
        if (sw) {
          U.els('.swatch-btn', root).forEach(function (b) { b.classList.remove('on'); });
          sw.classList.add('on');
          U.el('[name=color]', root).value = sw.getAttribute('data-color');
          return;
        }
        if (e.target.closest('[data-category-save]')) {
          var name = U.el('[name=name]', root).value.trim();
          if (!name) { U.toast('Give the category a name', 'error'); return; }
          var patch = {
            name: name,
            kind: U.el('[name=kind]', root).value,
            color: U.el('[name=color]', root).value
          };
          var arch = U.el('[name=archived]', root);
          if (arch) patch.archived = arch.checked;
          if (c) S.updateCategory(c.id, patch); else S.addCategory(patch);
          closeSheet();
          U.toast('Category saved');
          return;
        }
        var del = e.target.closest('[data-category-delete]');
        if (del) {
          var removed = S.deleteCategory(del.getAttribute('data-category-delete'));
          closeSheet();
          U.toast(removed ? 'Category deleted' : 'Category is in use — archived instead');
        }
      });
    });
  }

  function updateBadges() {
    var n = S.inbox().length;
    ['#top-badge', '#rail-badge'].forEach(function (sel) {
      var el = U.el(sel);
      if (!el) return;
      el.textContent = n > 9 ? '9+' : String(n);
      el.hidden = n === 0;
    });
  }

  /* ---------- capture ----------
   * One funnel for everything read automatically: shared messages, pasted
   * blobs, statement rows. Nothing is logged without a rule saying it is safe.
   */

  function ingest(text, source) {
    var messages = global.Parse.split(text);
    var out = { total: messages.length, added: 0, logged: 0, duplicate: 0, rejected: 0, reasons: [] };

    messages.forEach(function (msg) {
      var parsed = global.Parse.parse(msg);
      var res = S.addToInbox(parsed, source);

      if (res.status === 'added') {
        /* A shop we have filed before can skip the queue, if asked to. */
        if (S.settings().autoConfirm && S.matchRule(parsed)) {
          S.confirmInboxItem(res.item.id);
          out.logged++;
        } else {
          out.added++;
        }
      } else if (res.status === 'duplicate') {
        out.duplicate++;
      } else {
        out.rejected++;
        if (res.why && out.reasons.indexOf(res.why) === -1) out.reasons.push(res.why);
      }
    });
    return out;
  }

  function ingestEntries(entries, source) {
    var out = { total: entries.length, added: 0, logged: 0, duplicate: 0, rejected: 0, reasons: [] };
    entries.forEach(function (parsed) {
      var res = S.addToInbox(parsed, source);
      if (res.status === 'added') {
        if (S.settings().autoConfirm && S.matchRule(parsed)) { S.confirmInboxItem(res.item.id); out.logged++; }
        else out.added++;
      } else if (res.status === 'duplicate') out.duplicate++;
      else out.rejected++;
    });
    return out;
  }

  function describeIngest(r) {
    var bits = [];
    if (r.added) bits.push(r.added + ' to review');
    if (r.logged) bits.push(r.logged + ' logged');
    if (r.duplicate) bits.push(r.duplicate + ' already had');
    if (r.rejected) bits.push(r.rejected + ' skipped');
    return bits.length ? bits.join(' · ') : 'Nothing to add';
  }

  function openCaptureSheet(prefill) {
    var body = '<p class="hint">Paste one bank or UPI message, or a whole batch — one per line, or separated by blank lines.</p>' +
      '<textarea class="input tall" name="blob" placeholder="Rs.640.50 debited from A/c XX1234 on 19-08-26 to VPA swiggy@icici…" data-autofocus>' +
      esc(prefill || '') + '</textarea>' +
      '<div id="capture-preview" class="preview"></div>' +
      '<div class="sheet-actions"><button class="btn primary grow" data-capture-save>Read messages</button></div>';

    openSheet('Paste messages', body, function (root) {
      var box = U.el('[name=blob]', root);
      var preview = U.el('#capture-preview', root);
      var timer = null;

      function refresh() {
        var text = box.value.trim();
        if (!text) { preview.innerHTML = ''; return; }
        var messages = global.Parse.split(text);
        preview.innerHTML = '<div class="preview-head">' + messages.length +
          (messages.length === 1 ? ' message read' : ' messages read') + '</div>' +
          messages.slice(0, 12).map(function (m) {
            var p = global.Parse.parse(m);
            if (!p.ok) {
              return '<div class="preview-row bad"><span>✕</span><span>' + esc(p.why) + '</span></div>';
            }
            var bits = [p.counterparty || 'Unnamed', U.date(p.date || S.today(), 'short')];
            return '<div class="preview-row"><span class="' + (p.type === 'income' ? 'in' : 'out') + '">' +
              esc(U.signedMoney(p.amount, p.type === 'income' ? 1 : -1)) + '</span>' +
              '<span>' + esc(bits.join(' · ')) + '</span></div>';
          }).join('') +
          (messages.length > 12 ? '<div class="preview-row muted">…and ' + (messages.length - 12) + ' more</div>' : '');
      }

      box.addEventListener('input', function () {
        clearTimeout(timer);
        timer = setTimeout(refresh, 200);
      });
      refresh();

      root.addEventListener('click', function (e) {
        if (!e.target.closest('[data-capture-save]')) return;
        var text = box.value.trim();
        if (!text) { U.toast('Paste a message first', 'error'); return; }
        var r = ingest(text, 'paste');
        closeSheet();
        U.toast(describeIngest(r));
        if (r.added) location.hash = '#/inbox';
      });
    });
  }

  function openStatementSheet() {
    var body = '<p class="hint">Download the CSV statement from your bank or UPI app and choose it here. Paisa works out the columns itself, and skips anything already logged.</p>' +
      '<div class="field"><label>Statement file</label>' +
      '<input class="input" type="file" name="csv" accept=".csv,.txt,text/csv,text/plain"></div>' +
      '<div id="statement-preview" class="preview"></div>' +
      '<div class="sheet-actions"><button class="btn primary grow" data-statement-import disabled>Choose a file first</button></div>';

    openSheet('Import statement', body, function (root) {
      var input = U.el('[name=csv]', root);
      var preview = U.el('#statement-preview', root);
      var button = U.el('[data-statement-import]', root);
      var ready = null;

      input.addEventListener('change', function () {
        var file = input.files && input.files[0];
        if (!file) return;
        var reader = new FileReader();
        reader.onload = function () {
          var res = global.Statement.read(String(reader.result));
          if (res.error) {
            ready = null;
            button.disabled = true;
            button.textContent = 'Choose a file first';
            preview.innerHTML = '<div class="preview-row bad"><span>✕</span><span>' + esc(res.error) + '</span></div>';
            return;
          }
          ready = res.entries;
          button.disabled = res.entries.length === 0;
          button.textContent = res.entries.length ? 'Import ' + res.entries.length + ' entries' : 'Nothing to import';
          preview.innerHTML = '<div class="preview-head">' + res.entries.length + ' rows read' +
            (res.skipped ? ' · ' + res.skipped + ' skipped' : '') + '</div>' +
            res.entries.slice(0, 8).map(function (p) {
              return '<div class="preview-row"><span class="' + (p.type === 'income' ? 'in' : 'out') + '">' +
                esc(U.signedMoney(p.amount, p.type === 'income' ? 1 : -1)) + '</span>' +
                '<span>' + esc([p.counterparty || 'Unnamed', U.date(p.date, 'short')].join(' · ')) + '</span></div>';
            }).join('') +
            (res.entries.length > 8 ? '<div class="preview-row muted">…and ' + (res.entries.length - 8) + ' more</div>' : '');
        };
        reader.readAsText(file);
      });

      root.addEventListener('click', function (e) {
        if (!e.target.closest('[data-statement-import]')) return;
        if (!ready || !ready.length) return;
        var r = ingestEntries(ready, 'csv');
        closeSheet();
        U.toast(describeIngest(r));
        if (r.added) location.hash = '#/inbox';
      });
    });
  }

  /* Opens a captured item in the normal entry form, so anything about it can be
   * corrected before it is logged. */
  function openInboxEdit(id) {
    var item = S.inboxItem(id);
    if (!item) return;
    var draft = S.suggest(item.parsed);
    openTxnSheet({
      id: null,
      inboxId: id,
      type: draft.type,
      amount: U.num(draft.amount),
      interest: '',
      date: draft.date,
      accountId: draft.accountId,
      toAccountId: null,
      categoryId: draft.categoryId,
      debtId: null,
      note: draft.note,
      personMode: 'new',
      person: item.parsed.counterparty || '',
      dueDate: ''
    });
  }

  /* ---------- repeating entries ---------- */

  function openRecurringSheet(id) {
    var r = id === 'new' ? null : S.recurring().filter(function (x) { return x.id === id; })[0];
    var cats = S.categories(r ? (r.type === 'income' ? 'income' : 'expense') : 'expense');
    var days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

    var body = '<div class="field"><label>What is it</label>' +
      '<input class="input" name="label" type="text" placeholder="e.g. House rent" value="' + esc(r ? r.label : '') + '" data-autofocus></div>' +

      '<div class="field"><label>Kind</label><select class="input" name="type">' +
        '<option value="expense"' + (r && r.type === 'expense' ? ' selected' : '') + '>Money out</option>' +
        '<option value="income"' + (r && r.type === 'income' ? ' selected' : '') + '>Money in</option>' +
      '</select></div>' +

      '<div class="field"><label>Amount (₹)</label>' +
      '<input class="input" name="amount" type="number" inputmode="decimal" step="0.01" min="0" value="' + (r ? U.num(r.amount) : '') + '"></div>' +

      '<div class="field"><label>Category</label><select class="input" name="categoryId">' +
        cats.map(function (c) {
          return '<option value="' + esc(c.id) + '"' + (r && r.categoryId === c.id ? ' selected' : '') + '>' + esc(c.name) + '</option>';
        }).join('') + '</select></div>' +

      accountSelect('accountId', r ? r.accountId : null, 'Account') +

      '<div class="field"><label>How often</label><select class="input" name="freq">' +
        '<option value="monthly"' + (r && r.freq === 'monthly' ? ' selected' : '') + '>Every month</option>' +
        '<option value="weekly"' + (r && r.freq === 'weekly' ? ' selected' : '') + '>Every week</option>' +
      '</select></div>' +

      '<div class="field" data-day-monthly><label>Day of the month</label>' +
      '<input class="input" name="dayMonth" type="number" min="1" max="28" value="' + esc(r && r.freq !== 'weekly' ? r.day : 1) + '">' +
      '<p class="hint">1 to 28, so it lands every month.</p></div>' +

      '<div class="field" data-day-weekly hidden><label>Day of the week</label><select class="input" name="dayWeek">' +
        days.map(function (d, i) {
          return '<option value="' + i + '"' + (r && r.freq === 'weekly' && r.day === i ? ' selected' : '') + '>' + d + '</option>';
        }).join('') + '</select></div>' +

      '<div class="field"><label>Note <span class="muted">(optional)</span></label>' +
      '<input class="input" name="note" type="text" value="' + esc(r ? r.note : '') + '"></div>' +

      '<label class="switch-row"><input type="checkbox" name="autoPost"' + (r && r.autoPost ? ' checked' : '') + '>' +
        ' <span>Log it automatically, no review</span></label>' +
      (r ? '<label class="switch-row"><input type="checkbox" name="paused"' + (r.paused ? ' checked' : '') + '> <span>Paused</span></label>' : '') +
      (r ? '<p class="hint">Next one: ' + esc(U.date(r.nextDate)) + '</p>' : '') +

      '<div class="sheet-actions">' +
        (r ? '<button class="btn danger" data-recurring-delete="' + esc(r.id) + '">Delete</button>' : '') +
        '<button class="btn primary grow" data-recurring-save>' + (r ? 'Save' : 'Add repeating entry') + '</button>' +
      '</div>';

    openSheet(r ? 'Repeating entry' : 'New repeating entry', body, function (root) {
      var freq = U.el('[name=freq]', root);
      function syncDayField() {
        var weekly = freq.value === 'weekly';
        U.el('[data-day-monthly]', root).hidden = weekly;
        U.el('[data-day-weekly]', root).hidden = !weekly;
      }
      freq.addEventListener('change', syncDayField);
      syncDayField();

      root.addEventListener('click', function (e) {
        var del = e.target.closest('[data-recurring-delete]');
        if (del) {
          if (confirm('Delete this repeating entry? Anything it already logged stays.')) {
            S.deleteRecurring(del.getAttribute('data-recurring-delete'));
            closeSheet();
            U.toast('Repeating entry deleted');
          }
          return;
        }
        if (!e.target.closest('[data-recurring-save]')) return;

        var label = U.el('[name=label]', root).value.trim();
        var amount = S.toPaise(U.el('[name=amount]', root).value);
        if (!label) { U.toast('Give it a name', 'error'); return; }
        if (amount <= 0) { U.toast('Enter an amount', 'error'); return; }

        var isWeekly = freq.value === 'weekly';
        var patch = {
          label: label,
          type: U.el('[name=type]', root).value,
          amount: amount,
          categoryId: U.el('[name=categoryId]', root).value,
          accountId: U.el('[name=accountId]', root).value,
          note: U.el('[name=note]', root).value,
          freq: freq.value,
          day: isWeekly ? parseInt(U.el('[name=dayWeek]', root).value, 10) : parseInt(U.el('[name=dayMonth]', root).value, 10),
          autoPost: U.el('[name=autoPost]', root).checked
        };
        var pausedEl = U.el('[name=paused]', root);
        if (pausedEl) patch.paused = pausedEl.checked;

        if (r) {
          /* Reschedule from today when the timing changed. */
          if (r.freq !== patch.freq || r.day !== patch.day) {
            patch.nextDate = S.nextOccurrence(patch.freq, patch.day, S.today());
          }
          S.updateRecurring(r.id, patch);
        } else {
          S.addRecurring(patch);
        }
        closeSheet();
        U.toast('Repeating entry saved');
      });
    });
  }

  /* ---------- settings actions ---------- */

  function handleSettingsClick(e) {
    if (e.target.closest('[data-save-settings]')) {
      S.updateSettings({
        monthlyBudget: S.toPaise(U.el('#set-budget').value),
        monthStartDay: Math.min(28, Math.max(1, parseInt(U.el('#set-start').value, 10) || 1)),
        theme: U.el('#set-theme').value
      });
      applyTheme();
      U.toast('Preferences saved');
      return true;
    }
    var ruleDel = e.target.closest('[data-rule-delete]');
    if (ruleDel) {
      S.deleteRule(ruleDel.getAttribute('data-rule-delete'));
      U.toast('Rule forgotten');
      return true;
    }
    if (e.target.closest('[data-add-account]')) { openAccountSheet(null); return true; }
    if (e.target.closest('[data-add-category]')) { openCategorySheet(null); return true; }

    var accRow = e.target.closest('[data-account]');
    if (accRow) { openAccountSheet(accRow.getAttribute('data-account')); return true; }
    var catChip = e.target.closest('[data-category]');
    if (catChip) { openCategorySheet(catChip.getAttribute('data-category')); return true; }

    if (e.target.closest('[data-export-json]')) {
      U.download('paisa-backup-' + S.today() + '.json', S.exportJSON(), 'application/json');
      U.toast('Backup downloaded');
      return true;
    }
    if (e.target.closest('[data-export-csv]')) {
      U.download('paisa-ledger-' + S.today() + '.csv', S.exportCSV(), 'text/csv');
      U.toast('Ledger downloaded');
      return true;
    }

    var imp = e.target.closest('[data-import]');
    if (imp) {
      var mode = imp.getAttribute('data-import');
      var input = U.el('#import-file');
      var file = input && input.files && input.files[0];
      if (!file) { U.toast('Choose a backup file first', 'error'); return true; }
      if (mode === 'replace' && !confirm('Replace everything on this device with the file? Current data will be lost.')) return true;
      var reader = new FileReader();
      reader.onload = function () {
        try {
          var res = S.importJSON(String(reader.result), mode);
          U.toast(mode === 'replace' ? 'Data replaced' : res.added + ' added, ' + res.skipped + ' already here');
          applyTheme();
          render();
        } catch (err) {
          U.toast(err.message || 'Could not read that file', 'error');
        }
      };
      reader.readAsText(file);
      return true;
    }

    if (e.target.closest('[data-reset]')) {
      if (confirm('Delete every transaction, debt and account on this device? Export a backup first if you might want it back.')) {
        S.reset();
        applyTheme();
        U.toast('All data deleted');
        render();
      }
      return true;
    }
    return false;
  }

  /* ---------- global events ---------- */

  function onViewClick(e) {
    if (App.state.route === 'settings' && handleSettingsClick(e)) return;

    if (e.target.closest('[data-open-capture]')) { openCaptureSheet(''); return; }
    if (e.target.closest('[data-open-statement]')) { openStatementSheet(); return; }

    var rec = e.target.closest('[data-recurring]');
    if (rec) { openRecurringSheet(rec.getAttribute('data-recurring')); return; }

    var confirmOne = e.target.closest('[data-inbox-confirm]');
    if (confirmOne) {
      var txn = S.confirmInboxItem(confirmOne.getAttribute('data-inbox-confirm'));
      U.toast(txn ? U.money(txn.amount) + ' logged' : 'Could not log that');
      render();
      return;
    }
    var dropOne = e.target.closest('[data-inbox-drop]');
    if (dropOne) {
      S.removeFromInbox(dropOne.getAttribute('data-inbox-drop'));
      U.toast('Discarded');
      render();
      return;
    }
    if (e.target.closest('[data-inbox-confirm-all]')) {
      var items = S.inbox();
      if (!items.length) return;
      if (!confirm('Log all ' + items.length + ' entries with the categories shown?')) return;
      items.forEach(function (i) { S.confirmInboxItem(i.id); });
      U.toast(items.length + ' entries logged');
      render();
      return;
    }
    var inboxRow = e.target.closest('[data-inbox]');
    if (inboxRow) { openInboxEdit(inboxRow.getAttribute('data-inbox')); return; }

    var month = e.target.closest('[data-month]');
    if (month) {
      var delta = parseInt(month.getAttribute('data-month'), 10);
      App.state.monthOffset = Math.min(0, App.state.monthOffset + delta);
      render();
      return;
    }
    var chip = e.target.closest('[data-filter-type]');
    if (chip) {
      App.state.filterType = chip.getAttribute('data-filter-type');
      render();
      return;
    }
    if (e.target.closest('[data-open-add]')) { openTxnSheet(blankDraft('expense')); return; }
    var newDebt = e.target.closest('[data-open-debt]');
    if (newDebt) {
      var dir = newDebt.getAttribute('data-direction') || 'owed';
      openTxnSheet(blankDraft(dir === 'owed' ? 'lend' : 'borrow'));
      return;
    }
    var txnRow = e.target.closest('[data-txn]');
    if (txnRow) {
      var t = S.transaction(txnRow.getAttribute('data-txn'));
      if (t) openTxnSheet(draftFromTxn(t));
      return;
    }
    var debtRow = e.target.closest('[data-debt]');
    if (debtRow) { openDebtSheet(debtRow.getAttribute('data-debt')); return; }
  }

  function onViewChange(e) {
    if (e.target.matches('[data-filter-month]')) { App.state.filterMonth = e.target.value; render(); }
    else if (e.target.matches('[data-filter-account]')) { App.state.filterAccount = e.target.value; render(); }
    else if (e.target.matches('[data-show-settled]')) { App.state.showSettled = e.target.checked; render(); }
    else if (e.target.matches('[data-auto-confirm]')) {
      S.updateSettings({ autoConfirm: e.target.checked });
      U.toast(e.target.checked ? 'Known shops will log themselves' : 'Everything will wait for review');
    }
  }

  var searchTimer = null;
  function onViewInput(e) {
    if (!e.target.matches('#search')) return;
    clearTimeout(searchTimer);
    var value = e.target.value;
    searchTimer = setTimeout(function () {
      App.state.search = value;
      render();
      var s = U.el('#search');
      if (s) { s.focus(); s.setSelectionRange(value.length, value.length); }
    }, 220);
  }

  /* Enter / Space on a focused row behaves like a click. */
  function onViewKeydown(e) {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    var row = e.target.closest('[data-txn],[data-debt],[data-account]');
    if (!row) return;
    e.preventDefault();
    row.click();
  }

  /* Android share sheet -> index.html?text=…  (declared in the manifest).
   * The text is consumed once and scrubbed from the URL so a refresh cannot
   * import it twice. */
  function takeSharedText() {
    if (!location.search) return null;
    var params = new URLSearchParams(location.search);
    var text = [params.get('text'), params.get('title'), params.get('url')]
      .filter(Boolean).join('\n').trim();
    if (!text) return null;
    history.replaceState(null, '', location.pathname);
    return text;
  }

  function applyTheme() {
    var theme = S.settings().theme || 'auto';
    document.documentElement.setAttribute('data-theme', theme);
  }

  /* ---------- boot ---------- */

  function init() {
    S.load();
    applyTheme();

    window.addEventListener('hashchange', function () {
      App.state.monthOffset = 0;
      render();
    });

    U.el('#view').addEventListener('click', onViewClick);
    U.el('#view').addEventListener('change', onViewChange);
    U.el('#view').addEventListener('input', onViewInput);
    U.el('#view').addEventListener('keydown', onViewKeydown);

    U.el('#fab').addEventListener('click', function () { openTxnSheet(blankDraft('expense')); });
    U.el('#sheet-close').addEventListener('click', closeSheet);
    U.el('#sheet-backdrop').addEventListener('click', closeSheet);

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && U.el('#sheet').classList.contains('open')) closeSheet();
      if (e.key === 'n' && !e.metaKey && !e.ctrlKey && !/input|select|textarea/i.test(e.target.tagName)) {
        e.preventDefault();
        openTxnSheet(blankDraft('expense'));
      }
    });

    S.onChange(function () { if (!U.el('#sheet').classList.contains('open')) render(); });

    var due = S.runRecurring();
    var shared = takeSharedText();

    render();

    if (shared) {
      var result = ingest(shared, 'share');
      location.hash = '#/inbox';
      render();
      U.toast(describeIngest(result));
    } else if (due.posted || due.queued) {
      var parts = [];
      if (due.posted) parts.push(due.posted + ' logged');
      if (due.queued) parts.push(due.queued + ' to review');
      U.toast('Repeating entries: ' + parts.join(' · '));
    }

    if ('serviceWorker' in navigator && location.protocol !== 'file:') {
      navigator.serviceWorker.register('sw.js').catch(function () { /* offline install is a bonus, not a requirement */ });
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})(window);
