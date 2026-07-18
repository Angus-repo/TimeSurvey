/* 多國語系核心（index / survey / stats 三頁共用）。
 *
 * 各語系字典由 /i18n/<lang>.js 提供，且需在本檔之前載入：
 *   window.TimeSurveyI18nCatalogs['zh-TW'] = { name, match, messages, ...formatters }
 *
 * 語系決定順序：
 *   1. localStorage('lang')：使用者曾手動切換過的語系
 *   2. navigator.languages：瀏覽器語系
 *   3. 皆無對應 → 英文，若英文未載入則使用第一個已載入語系
 *
 * 對外介面（window.I18N）：
 *   I18N.lang                目前語系代碼
 *   I18N.t(key, ...args)     取得翻譯字串；{0}、{1}… 依序以 args 取代
 *   I18N.join(arr)           人名等清單的連接
 *   I18N.partSep()           合併多段訊息時使用的分隔符
 *   I18N.weekWrap(dayIdx)    日期後綴的星期標示
 *   I18N.weekName(dayIdx)    星期短名
 *   I18N.ymRange(y1,m1,y2,m2) 日曆標題
 *   I18N.monthLabel(m)       日曆月份分隔標籤
 *   I18N.hours(n)            時數
 *   I18N.apply(root)         套用 data-i18n / data-i18n-title / data-i18n-placeholder /
 *                            data-i18n-tip / data-i18n-aria 屬性到靜態 HTML
 */
window.I18N = (function () {
  const CATALOGS = window.TimeSurveyI18nCatalogs || {};
  const FALLBACK = CATALOGS.en ? 'en' : Object.keys(CATALOGS)[0];
  const SUPPORTED = Object.keys(CATALOGS).map(code => ({
    code,
    name: CATALOGS[code].name || code,
    shortName: CATALOGS[code].shortName || code.toUpperCase()
  }));

  function catalog(code) {
    return CATALOGS[code] || CATALOGS[FALLBACK] || { messages: {} };
  }

  function matchLang(tag) {
    const t = String(tag || '').toLowerCase();
    for (const code of Object.keys(CATALOGS)) {
      const codeLc = code.toLowerCase();
      if (t === codeLc || t.startsWith(codeLc + '-')) return code;
    }
    for (const code of Object.keys(CATALOGS)) {
      const matches = CATALOGS[code].match || [];
      for (const m of matches) {
        const ml = String(m).toLowerCase();
        if (t === ml || t.startsWith(ml + '-')) return code;
      }
    }
    return null;
  }

  function detect() {
    const saved = localStorage.getItem('lang');
    if (CATALOGS[saved]) return saved;
    for (const tag of (navigator.languages || [navigator.language])) {
      const m = matchLang(tag);
      if (m) return m;
    }
    return FALLBACK;
  }

  const lang = detect();
  document.documentElement.lang = lang || '';
  const active = catalog(lang);
  const fallback = catalog(FALLBACK);

  function pick(prop, def) {
    if (active[prop] !== undefined) return active[prop];
    if (fallback[prop] !== undefined) return fallback[prop];
    return def;
  }

  function fmt(str, args) {
    return String(str).replace(/\{(\d+)\}/g, (m, i) => (args[i] !== undefined ? args[i] : m));
  }

  function t(key) {
    const args = Array.prototype.slice.call(arguments, 1);
    const messages = active.messages || {};
    const fallbackMessages = fallback.messages || {};
    const v = messages[key] !== undefined ? messages[key]
            : (fallbackMessages[key] !== undefined ? fallbackMessages[key] : key);
    return fmt(v, args);
  }

  function join(arr) {
    return (arr || []).join(pick('joinSep', ', '));
  }

  function partSep() {
    return pick('partSep', '; ');
  }

  function weekName(i) {
    const week = pick('week', []);
    return week[i] || '';
  }

  function withFormatter(name, args, fallbackFn) {
    const fn = active[name] || fallback[name];
    return typeof fn === 'function' ? fn.apply(null, args) : fallbackFn();
  }

  function weekWrap(i) {
    return withFormatter('weekWrap', [i], () => ' (' + weekName(i) + ')');
  }

  function ym(y, m) {
    return withFormatter('ym', [y, m], () => y + '-' + String(m).padStart(2, '0'));
  }

  function ymRange(y1, m1, y2, m2) {
    return withFormatter('ymRange', [y1, m1, y2, m2], () => ym(y1, m1) + ' – ' + ym(y2, m2));
  }

  function monthLabel(m) {
    return withFormatter('monthLabel', [m], () => String(m));
  }

  function hours(n) {
    return withFormatter('hours', [n], () => n + ' hr');
  }

  function apply(root) {
    root = root || document;
    root.querySelectorAll('[data-i18n]').forEach(el => {
      const key = el.getAttribute('data-i18n');
      if (el.tagName === 'TITLE') { document.title = t(key); return; }
      if (/Html$/.test(key)) el.innerHTML = t(key);
      else el.textContent = t(key);
    });
    root.querySelectorAll('[data-i18n-title]').forEach(el => el.title = t(el.getAttribute('data-i18n-title')));
    root.querySelectorAll('[data-i18n-placeholder]').forEach(el => el.placeholder = t(el.getAttribute('data-i18n-placeholder')));
    root.querySelectorAll('[data-i18n-aria]').forEach(el => el.setAttribute('aria-label', t(el.getAttribute('data-i18n-aria'))));
    root.querySelectorAll('[data-i18n-tip]').forEach(el => el.setAttribute('data-tip', t(el.getAttribute('data-i18n-tip'))));
  }

  function mountSwitcher() {
    if (!SUPPORTED.length || document.querySelector('.i18n-switch')) return;
    const current = SUPPORTED.find(l => l.code === lang) || SUPPORTED[0];
    const wrap = document.createElement('span');
    wrap.className = 'i18n-control';
    wrap.title = t('lang.label');
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'i18n-switch';
    btn.setAttribute('aria-label', t('lang.label'));
    btn.setAttribute('aria-haspopup', 'listbox');
    btn.setAttribute('aria-expanded', 'false');
    const icon = document.createElement('span');
    icon.className = 'i18n-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = '🌐';
    const badge = document.createElement('span');
    badge.className = 'i18n-badge';
    badge.textContent = current.shortName;
    btn.appendChild(icon);
    btn.appendChild(badge);
    const menu = document.createElement('div');
    menu.className = 'i18n-menu';
    menu.setAttribute('role', 'listbox');
    menu.setAttribute('aria-label', t('lang.label'));
    menu.hidden = true;
    const optionButtons = [];

    function closeMenu() {
      wrap.classList.remove('open');
      btn.setAttribute('aria-expanded', 'false');
      menu.hidden = true;
    }

    function openMenu() {
      wrap.classList.add('open');
      btn.setAttribute('aria-expanded', 'true');
      menu.hidden = false;
      const activeOption = optionButtons.find(opt => opt.getAttribute('aria-selected') === 'true') || optionButtons[0];
      if (activeOption) activeOption.focus();
    }

    function toggleMenu() {
      if (menu.hidden) openMenu();
      else closeMenu();
    }

    function chooseLanguage(code) {
      if (code === lang) {
        closeMenu();
        btn.focus();
        return;
      }
      localStorage.setItem('lang', code);
      location.reload();
    }

    for (const l of SUPPORTED) {
      const opt = document.createElement('button');
      opt.type = 'button';
      opt.className = 'i18n-option';
      opt.dataset.lang = l.code;
      opt.setAttribute('role', 'option');
      opt.setAttribute('aria-selected', String(l.code === lang));
      const code = document.createElement('span');
      code.className = 'i18n-option-code';
      code.textContent = l.shortName;
      const name = document.createElement('span');
      name.className = 'i18n-option-name';
      name.textContent = l.name;
      opt.appendChild(code);
      opt.appendChild(name);
      opt.addEventListener('click', () => chooseLanguage(l.code));
      opt.addEventListener('keydown', ev => {
        const idx = optionButtons.indexOf(opt);
        if (ev.key === 'ArrowDown') {
          ev.preventDefault();
          optionButtons[(idx + 1) % optionButtons.length].focus();
        } else if (ev.key === 'ArrowUp') {
          ev.preventDefault();
          optionButtons[(idx - 1 + optionButtons.length) % optionButtons.length].focus();
        } else if (ev.key === 'Home') {
          ev.preventDefault();
          optionButtons[0].focus();
        } else if (ev.key === 'End') {
          ev.preventDefault();
          optionButtons[optionButtons.length - 1].focus();
        } else if (ev.key === 'Enter' || ev.key === ' ') {
          ev.preventDefault();
          chooseLanguage(l.code);
        } else if (ev.key === 'Escape') {
          ev.preventDefault();
          closeMenu();
          btn.focus();
        } else if (ev.key === 'Tab') {
          closeMenu();
        }
      });
      optionButtons.push(opt);
      menu.appendChild(opt);
    }

    btn.addEventListener('click', ev => {
      ev.stopPropagation();
      toggleMenu();
    });
    btn.addEventListener('keydown', ev => {
      if (ev.key === 'ArrowDown' || ev.key === 'Enter' || ev.key === ' ') {
        ev.preventDefault();
        openMenu();
      } else if (ev.key === 'Escape') {
        closeMenu();
      }
    });
    menu.addEventListener('click', ev => ev.stopPropagation());
    document.addEventListener('click', ev => {
      if (!wrap.contains(ev.target)) closeMenu();
    });
    wrap.appendChild(btn);
    wrap.appendChild(menu);

    const st = document.createElement('style');
    st.textContent =
      '.i18n-control { display: inline-flex; align-items: center; width: 78px; min-width: 78px; max-width: 78px;' +
      ' height: 38px; padding: 0; border-radius: 999px; border: 1px solid rgba(255,255,255,.35);' +
      ' background: rgba(255,255,255,.12); color: #fff; flex: 0 0 auto; position: relative;' +
      ' transition: background .15s, border-color .15s, box-shadow .15s; }' +
      '.i18n-control:hover, .i18n-control.open { background: rgba(255,255,255,.24); border-color: rgba(255,255,255,.6); }' +
      '.i18n-control:focus-within { box-shadow: 0 0 0 3px rgba(255,255,255,.28); border-color: rgba(255,255,255,.75); }' +
      '.i18n-switch { width: 100%; height: 100%; padding: 0 7px 0 8px; border: 0; border-radius: inherit;' +
      ' display: inline-flex; align-items: center; justify-content: space-between; gap: 3px;' +
      ' background: transparent; color: inherit; font-size: 13px; font-family: inherit; font-weight: 700; cursor: pointer; line-height: 1;' +
      ' appearance: none; -webkit-appearance: none; outline: none; }' +
      '.i18n-switch::after { content: "⌄"; font-size: 14px; font-weight: 700; line-height: 1; opacity: .9; }' +
      '.i18n-icon { display: inline-flex; align-items: center; justify-content: center; width: 14px; height: 14px;' +
      ' font-size: 14px; line-height: 1; pointer-events: none; }' +
      '.i18n-control.open .i18n-icon { display: none; }' +
      '.i18n-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 26px;' +
      ' color: inherit; white-space: nowrap; line-height: 1; pointer-events: none; }' +
      '.i18n-menu { position: absolute; top: calc(100% + 8px); right: 0; z-index: 1000; min-width: 162px;' +
      ' padding: 6px; border: 1px solid #dde3ea; border-radius: 10px; background: #fff;' +
      ' box-shadow: 0 10px 26px rgba(16,24,40,.2); }' +
      '.i18n-menu[hidden] { display: none; }' +
      '.i18n-option { width: 100%; display: flex; align-items: center; gap: 8px; padding: 7px 9px;' +
      ' border: 0; border-radius: 8px; background: transparent; color: #2f3b48; font-size: 13px;' +
      ' font-family: inherit; line-height: 1.35; text-align: left; white-space: nowrap; cursor: pointer;' +
      ' transition: background .15s, color .15s, box-shadow .15s; }' +
      '.i18n-option:focus { outline: none; }' +
      '.i18n-option:hover, .i18n-option:focus-visible { background: #eef7f4; color: #245f51;' +
      ' box-shadow: inset 0 0 0 1px #d3e9e1; }' +
      '.i18n-option-code { min-width: 38px; height: 22px; padding: 0 7px; border-radius: 999px;' +
      ' display: inline-flex; align-items: center; justify-content: center; background: #e7ecf1;' +
      ' border: 1px solid #d8dfe7; color: #324253; box-shadow: inset 0 1px 0 rgba(255,255,255,.8);' +
      ' font-size: 12px; font-weight: 800; letter-spacing: .04em; line-height: 1;' +
      ' transition: background .15s, border-color .15s, color .15s; }' +
      '.i18n-option[aria-selected="true"] { background: #e7f0ff; color: #1f4f8c;' +
      ' box-shadow: inset 0 0 0 1px #c6daf5; }' +
      '.i18n-option[aria-selected="true"] .i18n-option-name { font-weight: 700; }' +
      '.i18n-option[aria-selected="true"] .i18n-option-code { background: #3d78bf; border-color: #3d78bf;' +
      ' color: #fff; box-shadow: none; }' +
      '.i18n-option[aria-selected="true"]:hover, .i18n-option[aria-selected="true"]:focus-visible {' +
      ' background: #d6e6fb; box-shadow: inset 0 0 0 1px #b8d0ef; }' +
      '.i18n-control.fixed { position: fixed; top: 14px; right: 14px; z-index: 900;' +
      ' background: #fff; color: #333; border-color: #ccc; }';
    document.head.appendChild(st);

    const actions = document.querySelector('header .hdr-actions');
    const header = document.querySelector('header');
    if (actions) {
      actions.appendChild(wrap);
    } else if (header) {
      header.appendChild(wrap);
    } else {
      wrap.classList.add('fixed');
      document.body.appendChild(wrap);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mountSwitcher);
  } else {
    mountSwitcher();
  }

  return { lang, t, join, partSep, weekName, weekWrap, ym, ymRange, monthLabel, hours, apply, supported: SUPPORTED };
})();
