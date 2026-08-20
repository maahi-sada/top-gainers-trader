/* Paisa — dependency-free SVG charts, so the app stays fully offline. */
(function (global) {
  'use strict';

  var esc = function (s) { return global.U.esc(s); };

  /* Donut of category shares. slices: [{label, value, color}] */
  function donut(slices, opts) {
    opts = opts || {};
    var size = opts.size || 200;
    var stroke = opts.stroke || 26;
    var r = (size - stroke) / 2;
    var cx = size / 2, cy = size / 2;
    var circumference = 2 * Math.PI * r;
    var total = slices.reduce(function (s, x) { return s + x.value; }, 0);

    if (!total) {
      return '<svg class="donut" viewBox="0 0 ' + size + ' ' + size + '" role="img" aria-label="No data">' +
        '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="none" stroke="var(--line)" stroke-width="' + stroke + '"/>' +
        '</svg>';
    }

    var offset = 0;
    var arcs = slices.map(function (s) {
      var frac = s.value / total;
      var len = frac * circumference;
      var seg = '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="none"' +
        ' stroke="' + esc(s.color) + '" stroke-width="' + stroke + '"' +
        ' stroke-dasharray="' + len.toFixed(2) + ' ' + (circumference - len).toFixed(2) + '"' +
        ' stroke-dashoffset="' + (-offset).toFixed(2) + '"' +
        ' transform="rotate(-90 ' + cx + ' ' + cy + ')">' +
        '<title>' + esc(s.label) + ' — ' + (frac * 100).toFixed(1) + '%</title></circle>';
      offset += len;
      return seg;
    }).join('');

    var center = opts.centerTop || opts.centerBottom
      ? '<text x="' + cx + '" y="' + (cy - 2) + '" text-anchor="middle" class="donut-top">' + esc(opts.centerTop || '') + '</text>' +
        '<text x="' + cx + '" y="' + (cy + 16) + '" text-anchor="middle" class="donut-sub">' + esc(opts.centerBottom || '') + '</text>'
      : '';

    return '<svg class="donut" viewBox="0 0 ' + size + ' ' + size + '" role="img" aria-label="Category breakdown">' +
      arcs + center + '</svg>';
  }

  /* Grouped bars for month-over-month income vs expense.
   * points: [{label, income, expense}] in chronological order. */
  function bars(points, opts) {
    opts = opts || {};
    var w = 640, h = 220;
    var padL = 8, padR = 8, padB = 26, padT = 10;
    var innerW = w - padL - padR, innerH = h - padT - padB;
    var max = points.reduce(function (m, p) { return Math.max(m, p.income, p.expense); }, 0) || 1;
    var slot = innerW / Math.max(1, points.length);
    var barW = Math.min(18, slot / 3.2);
    var gap = 3;

    var gridlines = [0.25, 0.5, 0.75, 1].map(function (f) {
      var y = padT + innerH - f * innerH;
      return '<line x1="' + padL + '" y1="' + y.toFixed(1) + '" x2="' + (w - padR) + '" y2="' + y.toFixed(1) + '" class="grid"/>';
    }).join('');

    var body = points.map(function (p, i) {
      var xc = padL + slot * i + slot / 2;
      var hi = (p.income / max) * innerH;
      var he = (p.expense / max) * innerH;
      var xi = xc - barW - gap / 2;
      var xe = xc + gap / 2;
      return '' +
        '<rect x="' + xi.toFixed(1) + '" y="' + (padT + innerH - hi).toFixed(1) + '" width="' + barW.toFixed(1) + '" height="' + Math.max(1, hi).toFixed(1) + '" rx="3" class="bar-in">' +
        '<title>' + esc(p.label) + ' in: ' + global.U.money(p.income) + '</title></rect>' +
        '<rect x="' + xe.toFixed(1) + '" y="' + (padT + innerH - he).toFixed(1) + '" width="' + barW.toFixed(1) + '" height="' + Math.max(1, he).toFixed(1) + '" rx="3" class="bar-out">' +
        '<title>' + esc(p.label) + ' out: ' + global.U.money(p.expense) + '</title></rect>' +
        '<text x="' + xc.toFixed(1) + '" y="' + (h - 8) + '" text-anchor="middle" class="axis">' + esc(p.label) + '</text>';
    }).join('');

    return '<svg class="bars" viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none" role="img" aria-label="Income versus spending by month">' +
      gridlines + body + '</svg>';
  }

  /* Horizontal proportion bar used in category lists. */
  function meter(fraction, color) {
    var pct = Math.max(0, Math.min(1, fraction || 0)) * 100;
    return '<span class="meter"><span class="meter-fill" style="width:' + pct.toFixed(1) + '%;background:' + esc(color) + '"></span></span>';
  }

  global.Charts = { donut: donut, bars: bars, meter: meter };
})(window);
