(function () {
  function defaultEscapeHtml(value) {
    return String(value ?? '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function excludedBadgeHtml(excludedDates, options = {}) {
    const dates = Array.from(excludedDates || []).filter(Boolean).sort();
    if (dates.length === 0) return '';

    const formatDate = typeof options.formatDate === 'function' ? options.formatDate : d => d;
    const escapeHtml = typeof options.escapeHtml === 'function' ? options.escapeHtml : defaultEscapeHtml;
    const suffix = options.suffix || '不調查';
    const leadingSpace = options.leadingSpace === false ? '' : ' ';
    const tip = '已跳過 ' + dates.length + ' 天' + suffix + '：' + dates.map(formatDate).join('、');
    const safeTip = escapeHtml(tip);

    return leadingSpace +
      '<span class="skipbadge" data-tip="' + safeTip + '" aria-label="' + safeTip + '">' +
      '🚫' + dates.length + ' 天</span>';
  }

  window.TimeSurveyDateRange = Object.assign({}, window.TimeSurveyDateRange, {
    excludedBadgeHtml
  });
})();
