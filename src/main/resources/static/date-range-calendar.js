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
    const i18n = window.I18N;
    const suffix = options.suffix || (i18n ? i18n.t('cal.suffix.noSurvey') : '不調查');
    const leadingSpace = options.leadingSpace === false ? '' : ' ';
    const list = i18n ? i18n.join(dates.map(formatDate)) : dates.map(formatDate).join('、');
    const tip = i18n
        ? i18n.t('cal.skippedTip', dates.length, suffix, list)
        : '已跳過 ' + dates.length + ' 天' + suffix + '：' + list;
    const safeTip = escapeHtml(tip);
    const label = i18n ? i18n.t('cal.skipBadge', dates.length) : '🚫' + dates.length + ' 天';

    return leadingSpace +
      '<span class="skipbadge" data-tip="' + safeTip + '" aria-label="' + safeTip + '">' +
      escapeHtml(label) + '</span>';
  }

  window.TimeSurveyDateRange = Object.assign({}, window.TimeSurveyDateRange, {
    excludedBadgeHtml
  });
})();
