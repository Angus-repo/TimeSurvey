/* Microsoft Entra ID（Azure AD）登入與 Microsoft Graph 整合（index / survey / stats 三頁共用）。
 *
 * 登入與 Graph 呼叫都在後端進行（授權碼流程）：
 *   - GET /api/entra-config 回 204 → 未設定，本檔完全不動作，各頁維持原本免登入行為。
 *   - 已設定 → 檢查 /api/entra/me 是否已登入；未登入以遮罩擋住頁面，
 *     按下登入鈕導向 /api/entra/login → 微軟登入頁 → /api/entra/callback
 *     由後端換取 token 並把 refresh token 存進資料庫，之後每次呼叫 Graph
 *     由後端用 refresh token 換 access token（前端不再接觸任何 token）。
 *   - 任一 API 回 401（session 或 refresh token 失效）→ 自動導回登入頁重新登入。
 *
 * 對外介面（window.Entra）：
 *   Entra.ready            Promise<boolean>：初始化完成後 resolve；true=已啟用且已登入
 *   Entra.enabled()        是否啟用 Entra ID 登入
 *   Entra.account()        登入者（{ displayName, username }，未登入為 null）
 *   Entra.searchUsers(name)         以顯示名稱精確查詢組織中的使用者，回傳陣列
 *   Entra.suggestUsers(prefix)      輸入時的人名建議（斷詞字首比對），回傳陣列
 *   Entra.attachSuggest(input, onPick)  把人名建議下拉掛到輸入框（輸入 2 個字以上顯示）
 *   Entra.verifyName(name)          查組織 + 同名多筆時彈出選擇視窗，回傳
 *                                   { status: 'disabled'|'ok'|'notfound'|'cancelled', user }
 *   Entra.userTimeZone(userId)      查某使用者信箱設定的時區，回傳 { timeZone, iana } 或 null
 *   Entra.myCalendar(startIso, endIso)  讀取登入者行事曆，回傳事件陣列
 */
window.Entra = (function () {
  let enabled = false;   // 後端是否已設定 Entra ID 登入
  let account = null;    // 登入者 { displayName, username }

  /* ---------- 工具 ---------- */
  function esc(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function t(key) {
    const args = Array.prototype.slice.call(arguments, 1);
    return window.I18N ? I18N.t.apply(I18N, [key].concat(args)) : key;
  }

  /* 導向後端登入端點（登入完成後回到目前頁面） */
  function gotoLogin() {
    location.href = '/api/entra/login?return=' +
        encodeURIComponent(location.pathname + location.search);
  }

  /* 呼叫後端 API；401（session 或 refresh token 失效）時自動重新登入，
     回傳的 Promise 不再 resolve（頁面即將離開），其餘錯誤拋出訊息 */
  async function api(path) {
    const res = await fetch(path);
    if (res.status === 401) {
      gotoLogin();
      return new Promise(() => {});
    }
    if (!res.ok) {
      let msg = t('auth.apiFail', res.status);
      try { msg = (await res.json()).message || msg; } catch (e) {}
      throw new Error(msg);
    }
    return res.json();
  }

  /* ---------- 登入遮罩：啟用 Entra ID 且尚未登入時，以全頁遮罩擋住內容 ---------- */
  function injectStyle() {
    const css = `
      .entra-mask { position: fixed; inset: 0; background: rgba(31,39,51,.72); z-index: 5000;
                    display: flex; align-items: center; justify-content: center; }
      .entra-card { background: #fff; border-radius: 12px; padding: 32px 40px; max-width: 420px;
                    text-align: center; box-shadow: 0 12px 40px rgba(0,0,0,.35);
                    font-family: "Microsoft JhengHei", "PingFang TC", sans-serif; }
      .entra-card h2 { margin: 0 0 10px; font-size: 19px; color: #1f2733; }
      .entra-card p { margin: 0 0 18px; font-size: 14px; color: #6b7682; line-height: 1.7; }
      .entra-card .entra-err { color: #c0392b; font-size: 13px; margin-top: 12px; display: none; }
      .entra-login-btn { display: inline-flex; align-items: center; gap: 10px; background: #2f2f2f; color: #fff;
                         border: none; border-radius: 6px; padding: 11px 26px; font-size: 15px; cursor: pointer;
                         font-family: inherit; transition: background .15s; }
      .entra-login-btn:hover { background: #1a1a1a; }
      .entra-chip { width: 36px; height: 36px; border-radius: 50%; border: 2px solid rgba(255,255,255,.75);
                    padding: 0; margin: 0; float: none; display: inline-flex; align-items: center; justify-content: center;
                    background-color: #6c5ce7; background-size: cover; background-position: center;
                    color: #fff; font-size: 15px; font-weight: 700; line-height: 1; cursor: pointer;
                    box-shadow: 0 1px 4px rgba(0,0,0,.25); flex: none;
                    font-family: "Microsoft JhengHei", "PingFang TC", sans-serif;
                    transition: box-shadow .15s, transform .15s; }
      .entra-chip:hover { box-shadow: 0 0 0 3px rgba(255,255,255,.35); transform: scale(1.06); }
      .entra-chip-abs { position: absolute; right: 20px; top: 50%; transform: translateY(-50%); }
      .entra-chip-abs:hover { transform: translateY(-50%) scale(1.06); }
      .entra-chip-fixed { position: fixed; right: 14px; top: 14px; z-index: 900; }
      /* 已登入時把頁首的常用工具收進姓名資訊卡；未啟用 Entra 時仍維持原本頁首操作。 */
      .entra-signed-in header #resetOnbBtn,
      .entra-signed-in header #feedbackBtn { display: none !important; }
      .entra-panel { position: fixed; z-index: 5300; width: 300px; background: #fff; border-radius: 12px;
                     box-shadow: 0 10px 34px rgba(0,0,0,.3); padding: 18px 20px; color: #1f2733;
                     max-width: calc(100vw - 16px); max-height: calc(100vh - 16px); overflow-y: auto;
                     font-family: "Microsoft JhengHei", "PingFang TC", sans-serif; font-size: 14px; }
      .entra-panel-head { display: flex; align-items: center; gap: 12px; padding-bottom: 12px;
                          border-bottom: 1px solid #eef1f5; margin-bottom: 10px; }
      .entra-panel-ava { width: 52px; height: 52px; border-radius: 50%; flex: none;
                         background-color: #6c5ce7; background-size: cover; background-position: center;
                         color: #fff; font-size: 21px; font-weight: 700; display: flex;
                         align-items: center; justify-content: center; }
      .entra-panel-name { font-size: 15.5px; font-weight: 700; }
      .entra-panel-mail { font-size: 12.5px; color: #6b7682; word-break: break-all; margin-top: 2px; }
      .entra-panel-row { display: flex; gap: 8px; margin: 6px 0; font-size: 13px; line-height: 1.6; }
      .entra-panel-row .k { flex: none; width: 62px; color: #8a94a0; }
      .entra-panel-row .v { color: #1f2733; word-break: break-all; }
      .entra-panel-actions { margin-top: 12px; padding-top: 10px; border-top: 1px solid #eef1f5; }
      .entra-panel-actions-title { color: #8a94a0; font-size: 12px; margin: 0 0 6px; }
      .entra-account-action { width: 100%; display: flex; align-items: center; gap: 9px; padding: 8px 10px;
                              border: none; border-radius: 7px; background: transparent; color: #334155;
                              font-size: 13.5px; font-family: inherit; text-align: left; cursor: pointer; }
      .entra-account-action:hover, .entra-account-action:focus { background: #f0f5fa; outline: none; }
      .entra-account-action .label { flex: 1; }
      .entra-panel-foot { margin-top: 12px; padding-top: 12px; border-top: 1px solid #eef1f5; text-align: right; }
      .entra-logout { background: #eceff1; color: #3b4654; border: none; border-radius: 6px; float: none;
                      padding: 7px 18px; font-size: 13px; cursor: pointer; font-family: inherit;
                      transition: background .15s; }
      .entra-logout:hover { background: #dfe4e8; }
      .entra-panel-loading { color: #8a94a0; font-size: 13px; margin: 4px 0; }
      .entra-pick-mask { position: fixed; inset: 0; background: rgba(0,0,0,.55); z-index: 5100; }
      .entra-pick { position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); z-index: 5200;
                    width: 460px; max-width: 92vw; background: #fff; border-radius: 12px; padding: 22px 26px;
                    box-shadow: 0 12px 40px rgba(0,0,0,.35);
                    font-family: "Microsoft JhengHei", "PingFang TC", sans-serif; }
      .entra-pick h3 { margin: 0 0 6px; font-size: 17px; color: #1f2733; }
      .entra-pick .entra-pick-hint { font-size: 13px; color: #8a94a0; margin: 0 0 12px; }
      .entra-pick-list { max-height: 300px; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; }
      .entra-pick-item { display: block; width: 100%; text-align: left; background: #f7f9fb;
                         border: 1px solid #e4e8ec; border-radius: 8px; padding: 10px 14px; cursor: pointer;
                         font-family: inherit; font-size: 14px; color: #1f2733;
                         transition: border-color .15s, background .15s; }
      .entra-pick-item:hover { border-color: #2c7be5; background: #eaf2fd; }
      .entra-pick-item .entra-pick-name { font-weight: 700; }
      .entra-pick-item .entra-pick-meta { font-size: 12.5px; color: #6b7682; margin-top: 3px; }
      .entra-pick-cancel { margin-top: 14px; background: #95a5a6; color: #fff; border: none;
                           border-radius: 6px; padding: 8px 20px; font-size: 14px; cursor: pointer;
                           font-family: inherit; float: right; }
      .entra-pick-cancel:hover { background: #7f8c8d; }
      .entra-suggest { position: fixed; z-index: 5400; background: #fff; border-radius: 10px;
                       box-shadow: 0 10px 30px rgba(0,0,0,.25); padding: 6px; max-height: 280px;
                       overflow-y: auto; display: flex; flex-direction: column; gap: 2px;
                       font-family: "Microsoft JhengHei", "PingFang TC", sans-serif; }
      .entra-suggest-item { display: flex; flex-direction: column; gap: 1px; width: 100%; text-align: left;
                            background: transparent; border: none; border-radius: 7px; padding: 7px 10px;
                            cursor: pointer; font-family: inherit; }
      .entra-suggest-item:hover, .entra-suggest-item.active { background: #eaf2fd; }
      .entra-suggest-name { font-size: 14px; color: #1f2733; font-weight: 700; }
      .entra-suggest-meta { font-size: 12px; color: #6b7682; }
      .entra-suggest-empty { font-size: 13px; color: #8a94a0; padding: 8px 10px; }`;
    const st = document.createElement('style');
    st.textContent = css;
    document.head.appendChild(st);
  }

  /* Microsoft 標誌（四色方塊）SVG */
  const MS_LOGO = '<svg width="18" height="18" viewBox="0 0 21 21">' +
      '<rect x="1" y="1" width="9" height="9" fill="#f25022"/><rect x="11" y="1" width="9" height="9" fill="#7fba00"/>' +
      '<rect x="1" y="11" width="9" height="9" fill="#00a4ef"/><rect x="11" y="11" width="9" height="9" fill="#ffb900"/></svg>';

  /* 顯示登入遮罩：按下登入鈕即整頁導向後端登入端點（登入後導回本頁）。
     initialErr：上一次登入回呼失敗的錯誤訊息，直接顯示在卡片上供診斷 */
  function requireLogin(initialErr) {
    const mask = document.createElement('div');
    mask.className = 'entra-mask';
    mask.innerHTML =
        '<div class="entra-card">' +
        '<h2>' + t('auth.loginTitle') + '</h2>' +
        '<p>' + t('auth.loginDesc') + '</p>' +
        '<button class="entra-login-btn">' + MS_LOGO + t('auth.loginBtn') + '</button>' +
        '<div class="entra-err"></div>' +
        '</div>';
    document.body.appendChild(mask);
    if (initialErr) {
      const err = mask.querySelector('.entra-err');
      err.textContent = t('auth.loginFail', initialErr);
      err.style.display = 'block';
    }
    mask.querySelector('.entra-login-btn').addEventListener('click', gotoLogin);
    // 整頁導向後不會回到這裡；ready 維持 pending，各頁不會繼續動作
    return new Promise(() => {});
  }

  /* ---------- 右上角的登入者頭像：點選展開帳號資訊卡 ---------- */

  /* 頭像縮寫：中文名取第一個字（通常是姓氏），英文名取前兩個單字的首字母 */
  function initials(name) {
    const s = String(name || '?').trim();
    if (/^[A-Za-z]/.test(s)) {
      return s.split(/\s+/).slice(0, 2).map(w => w[0].toUpperCase()).join('');
    }
    return s.slice(0, 1);
  }

  /* 依名稱產生固定的頭像底色（同一人每次登入顏色一致） */
  function avatarColor(name) {
    const palette = ['#6c5ce7', '#0984e3', '#00a884', '#e17055', '#d63031', '#8e44ad', '#2c7be5', '#c0392b'];
    let h = 0;
    for (const c of String(name || '')) h = (h * 31 + c.charCodeAt(0)) >>> 0;
    return palette[h % palette.length];
  }

  let chipEl = null;      // 右上角頭像
  let panelEl = null;     // 帳號資訊卡（展開中才存在）
  let photoUrl = null;    // Graph 取得的大頭照（blob URL；無照片維持 null）
  let profile = null;     // Graph /me 的個人資料（首次展開時載入）
  let feedbackConfigPromise = null; // 意見回饋設定（未設定時 resolve null）

  function applyAvatar(el, name) {
    if (photoUrl) {
      el.textContent = '';
      el.style.backgroundImage = 'url(' + photoUrl + ')';
    } else {
      el.textContent = initials(name);
      el.style.backgroundColor = avatarColor(name);
    }
  }

  function accountName() {
    return (account && (account.displayName || account.username)) || '?';
  }

  /* 在頁面右上角放上登入者頭像：優先放進頁首的動作區（後台維護頁），
     否則靠頁首右緣絕對定位（填寫頁、統計頁），沒有頁首時固定在視窗右上角 */
  async function showUserChip() {
    if (!account) return;
    document.documentElement.classList.add('entra-signed-in');
    const name = accountName();
    chipEl = document.createElement('button');
    chipEl.type = 'button';
    chipEl.className = 'entra-chip';
    chipEl.title = t('auth.chipTitle', name);
    chipEl.setAttribute('aria-label', t('auth.chipAria', name));
    applyAvatar(chipEl, name);
    chipEl.addEventListener('click', e => {
      e.stopPropagation();
      panelEl ? closePanel() : openPanel();
    });
    document.addEventListener('click', e => {
      if (panelEl && !panelEl.contains(e.target)) closePanel();
    });

    const actions = document.querySelector('header .hdr-actions');
    const header = document.querySelector('header');
    if (actions) {
      actions.appendChild(chipEl);
    } else if (header) {
      header.style.position = 'relative';
      chipEl.classList.add('entra-chip-abs');
      header.appendChild(chipEl);
    } else {
      chipEl.classList.add('entra-chip-fixed');
      document.body.appendChild(chipEl);
    }

    // 背景抓大頭照（沒有照片時後端回 404，維持文字縮寫頭像）
    try {
      const res = await fetch('/api/entra/photo');
      if (res.ok) {
        photoUrl = URL.createObjectURL(await res.blob());
        applyAvatar(chipEl, name);
        chipEl.style.backgroundColor = 'transparent';
      }
    } catch (e) { /* 取不到照片就維持縮寫 */ }
  }

  function positionPanel() {
    if (!chipEl || !panelEl) return;
    const r = chipEl.getBoundingClientRect();
    const top = Math.max(8, Math.min(r.bottom + 8, window.innerHeight - panelEl.offsetHeight - 8));
    panelEl.style.top = top + 'px';
    panelEl.style.left = Math.max(8,
        Math.min(r.right - panelEl.offsetWidth, window.innerWidth - panelEl.offsetWidth - 8)) + 'px';
  }

  function accountAction(action, icon, label, onClick) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'entra-account-action';
    btn.dataset.action = action;
    btn.innerHTML = '<span aria-hidden="true">' + icon + '</span><span class="label"></span>';
    btn.querySelector('.label').textContent = label;
    btn.addEventListener('click', onClick);
    return btn;
  }

  function accountActionLabel(key) {
    return t(key).replace(/^[^\w\u3400-\u9fff]+/, '');
  }

  function loadFeedbackConfig() {
    if (!feedbackConfigPromise) {
      feedbackConfigPromise = fetch('/api/feedback-config')
          .then(res => res.status === 200 ? res.json() : null)
          .catch(() => null);
    }
    return feedbackConfigPromise;
  }

  /* 姓名資訊卡內只收合重新顯示引導與意見回饋；多國語系維持在頁首。 */
  async function mountAccountActions(actions) {
    const resetSource = document.getElementById('resetOnbBtn');
    if (resetSource) {
      actions.appendChild(accountAction('onboarding', '🧭', accountActionLabel('idx.resetOnb'), () => {
        closePanel();
        resetSource.click();
      }));
    }

    const feedback = await loadFeedbackConfig();
    if (!feedback || !panelEl || !actions.isConnected) return;
    actions.appendChild(accountAction('feedback', '💬', accountActionLabel('idx.feedback'), () => {
      location.href = 'mailto:' + encodeURIComponent(feedback.recipient) +
          '?subject=' + encodeURIComponent(feedback.subject);
    }));
    positionPanel();
  }

  /* 展開帳號資訊卡：顯示姓名、email，並從 Graph 載入職稱、部門等詳細資料 */
  async function openPanel() {
    if (!chipEl || panelEl) return;
    const name = accountName();
    panelEl = document.createElement('div');
    panelEl.className = 'entra-panel';
    panelEl.addEventListener('click', e => e.stopPropagation());

    const head = document.createElement('div');
    head.className = 'entra-panel-head';
    const ava = document.createElement('div');
    ava.className = 'entra-panel-ava';
    applyAvatar(ava, name);
    const idBox = document.createElement('div');
    const nm = document.createElement('div');
    nm.className = 'entra-panel-name';
    nm.textContent = name;
    const ml = document.createElement('div');
    ml.className = 'entra-panel-mail';
    ml.textContent = account.username || '';
    idBox.append(nm, ml);
    head.append(ava, idBox);

    const body = document.createElement('div');
    body.innerHTML = '<div class="entra-panel-loading">' + t('auth.loading') + '</div>';

    const actions = document.createElement('div');
    actions.className = 'entra-panel-actions';
    const actionsTitle = document.createElement('div');
    actionsTitle.className = 'entra-panel-actions-title';
    actionsTitle.textContent = t('auth.actions');
    actions.appendChild(actionsTitle);

    const foot = document.createElement('div');
    foot.className = 'entra-panel-foot';
    const out = document.createElement('button');
    out.type = 'button';
    out.className = 'entra-logout';
    out.textContent = t('auth.logout');
    out.addEventListener('click', () => {
      location.href = '/api/entra/logout';
    });
    foot.appendChild(out);

    panelEl.append(head, body, actions, foot);
    document.body.appendChild(panelEl);
    mountAccountActions(actions);

    // 資訊卡對齊頭像下方、靠右緣，並夾在視窗內
    positionPanel();

    // 首次展開時向後端取個人資料，之後直接沿用
    try {
      if (!profile) {
        profile = await api('/api/entra/profile');
      }
      if (!panelEl) return;   // 載入期間卡片已被關閉
      ml.textContent = profile.mail || profile.userPrincipalName || ml.textContent;
      body.innerHTML = '';
      const rows = [
        [t('auth.jobTitle'), profile.jobTitle],
        [t('auth.department'), profile.department],
        [t('auth.office'), profile.officeLocation],
        [t('auth.mobile'), profile.mobilePhone],
        [t('auth.phone'), profile.businessPhones && profile.businessPhones[0]]
      ];
      let shown = 0;
      for (const [k, v] of rows) {
        if (!v) continue;
        shown++;
        const row = document.createElement('div');
        row.className = 'entra-panel-row';
        const kEl = document.createElement('span');
        kEl.className = 'k';
        kEl.textContent = k;
        const vEl = document.createElement('span');
        vEl.className = 'v';
        vEl.textContent = v;
        row.append(kEl, vEl);
        body.appendChild(row);
      }
      if (shown === 0) {
        body.innerHTML = '<div class="entra-panel-loading">' + t('auth.noInfo') + '</div>';
      }
      positionPanel();
    } catch (e) {
      if (panelEl) {
        body.innerHTML = '<div class="entra-panel-loading">' + t('auth.loadFail') + '</div>';
        positionPanel();
      }
    }
  }

  function closePanel() {
    if (panelEl) { panelEl.remove(); panelEl = null; }
  }

  /* ---------- 初始化 ---------- */

  /* 登入回呼失敗時後端會把錯誤放在網址 fragment（#entra_error=...）帶回來 */
  function popLoginError() {
    const m = (location.hash || '').match(/[#&]entra_error=([^&]*)/);
    if (!m) return null;
    history.replaceState(null, '', location.pathname + location.search);
    try { return decodeURIComponent(m[1].replace(/\+/g, ' ')); } catch (e) { return m[1]; }
  }

  async function init() {
    let res;
    try {
      res = await fetch('/api/entra-config');
    } catch (e) {
      return false;   // 設定 API 取不到時視為未啟用，不影響原功能
    }
    if (res.status !== 200) {
      console.info('[Entra] not configured; Microsoft Entra ID sign-in is disabled');
      return false;   // 204 = 未設定
    }
    enabled = true;
    console.info('[Entra] Microsoft Entra ID sign-in is enabled');
    injectStyle();

    const loginErr = popLoginError();
    // 帶上目前頁面路徑：後端順帶記錄一筆使用紀錄（誰、何時、開了哪一頁）供統計
    const me = await fetch('/api/entra/me?page=' + encodeURIComponent(location.pathname));
    if (me.status !== 200) {
      await requireLogin(loginErr);   // 導向登入頁後不再返回
      return false;
    }
    account = await me.json();
    showUserChip();
    return true;
  }

  /* ---------- Microsoft Graph（經後端代理） ---------- */

  /* 以顯示名稱「精確比對」查詢組織中的使用者（含部門、email） */
  async function searchUsers(name) {
    return api('/api/entra/users?name=' + encodeURIComponent(name));
  }

  /* 輸入時的人名建議（比對顯示名稱各斷詞的字首，最多 8 筆） */
  async function suggestUsers(prefix) {
    return api('/api/entra/suggest?name=' + encodeURIComponent(prefix));
  }

  /* 把「輸入 2 個字以上就顯示組織目錄的人名建議」掛到指定輸入框。
     以滑鼠點選（或方向鍵反白後按 Enter）選取建議時呼叫 onPick(user)；
     未反白時 Enter 仍交給頁面原本的提交行為。未啟用 Entra ID 時不動作 */
  function attachSuggest(input, onPick) {
    if (!enabled || !input) return;
    let box = null;        // 建議清單容器（顯示中才存在）
    let items = [];        // 目前顯示的建議（Graph 使用者物件）
    let hi = -1;           // 反白中的建議索引；-1 = 未反白
    let timer = null;      // 輸入停頓的防抖計時器
    let seq = 0;           // 查詢序號：僅顯示最後一次查詢的結果
    let composing = false; // 中文輸入法組字中不查詢

    function close() {
      if (box) { box.remove(); box = null; }
      items = [];
      hi = -1;
    }

    function highlight(delta) {
      if (items.length === 0) return;   // 「無符合的姓名」提示列不可反白選取
      const els = box.querySelectorAll('.entra-suggest-item');
      hi = (hi + delta + els.length) % els.length;
      els.forEach((el, i) => el.classList.toggle('active', i === hi));
      els[hi].scrollIntoView({ block: 'nearest' });
    }

    function pick(i) {
      const u = items[i];
      close();
      if (u) onPick(u);
    }

    function render(list) {
      close();
      items = list;
      box = document.createElement('div');
      box.className = 'entra-suggest';
      if (list.length === 0) {
        // 查無符合的姓名也要明確提示，避免使用者以為建議功能沒有作用
        const empty = document.createElement('div');
        empty.className = 'entra-suggest-empty';
        empty.textContent = t('auth.noMatch');
        box.appendChild(empty);
      }
      list.forEach((u, i) => {
        const it = document.createElement('button');
        it.type = 'button';
        it.className = 'entra-suggest-item';
        const meta = [u.department, u.jobTitle].filter(Boolean).join('　') ||
            u.mail || u.userPrincipalName || '';
        it.innerHTML = '<span class="entra-suggest-name">' + esc(u.displayName) + '</span>' +
            (meta ? '<span class="entra-suggest-meta">' + esc(meta) + '</span>' : '');
        // 用 mousedown（而非 click）並 preventDefault：避免輸入框先失焦、
        // 觸發頁面的 blur 提交把還沒選完的文字變成名牌
        it.addEventListener('mousedown', e => { e.preventDefault(); pick(i); });
        box.appendChild(it);
      });
      document.body.appendChild(box);
      const r = input.getBoundingClientRect();
      box.style.left = r.left + 'px';
      box.style.top = (r.bottom + 4) + 'px';
      box.style.minWidth = Math.max(r.width, 240) + 'px';
    }

    function query() {
      clearTimeout(timer);
      const v = input.value.trim();
      if (v.length < 2) return close();
      timer = setTimeout(async () => {
        const my = ++seq;
        try {
          const list = await suggestUsers(v);
          // 回應到達時輸入已改變或焦點已離開 → 不顯示過時的建議
          if (my === seq && document.activeElement === input && input.value.trim() === v) {
            render(list);
          }
        } catch (e) { close(); }   // 建議查詢失敗不打擾使用者，照常手動輸入
      }, 250);
    }

    input.addEventListener('compositionstart', () => { composing = true; });
    input.addEventListener('compositionend', () => { composing = false; query(); });
    input.addEventListener('input', () => { if (!composing) query(); });
    input.addEventListener('blur', () => setTimeout(close, 120));
    window.addEventListener('scroll', close, true);
    // 捕獲階段搶在頁面自身的 keydown（Enter 提交）之前：有反白的建議時 Enter 選取該建議
    document.addEventListener('keydown', e => {
      if (!box || e.target !== input || e.isComposing) return;
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        highlight(1);
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        highlight(-1);
      } else if (e.key === 'Enter') {
        if (hi >= 0) {
          e.preventDefault();
          e.stopPropagation();
          pick(hi);
        } else {
          close();   // 未反白：關閉建議，交給頁面原本的 Enter 提交
        }
      } else if (e.key === 'Escape') {
        close();
      }
    }, true);
  }

  /* 同名同姓多筆時的選擇視窗：列出部門與 email 讓使用者選，回傳選中的使用者或 null（取消） */
  function pickUser(name, candidates) {
    return new Promise(resolve => {
      const mask = document.createElement('div');
      mask.className = 'entra-pick-mask';
      const box = document.createElement('div');
      box.className = 'entra-pick';
      let html = '<h3>' + t('auth.pickTitle', esc(name)) + '</h3>' +
          '<p class="entra-pick-hint">' + t('auth.pickHint') + '</p><div class="entra-pick-list">';
      candidates.forEach((u, i) => {
        html += '<button type="button" class="entra-pick-item" data-i="' + i + '">' +
            '<div class="entra-pick-name">' + esc(u.displayName) +
            (u.jobTitle ? '<span style="font-weight:400;color:#8a94a0">　' + esc(u.jobTitle) + '</span>' : '') + '</div>' +
            '<div class="entra-pick-meta">🏢 ' + esc(u.department || t('auth.noDept')) +
            '　✉️ ' + esc(u.mail || u.userPrincipalName || t('auth.noMail')) + '</div></button>';
      });
      html += '</div><button type="button" class="entra-pick-cancel">' + t('common.cancel') + '</button><div style="clear:both"></div>';
      box.innerHTML = html;
      const done = u => { mask.remove(); box.remove(); resolve(u); };
      box.querySelectorAll('.entra-pick-item').forEach(btn =>
          btn.addEventListener('click', () => done(candidates[+btn.dataset.i])));
      box.querySelector('.entra-pick-cancel').addEventListener('click', () => done(null));
      mask.addEventListener('click', () => done(null));
      document.body.append(mask, box);
    });
  }

  /* 檢查姓名是否存在於公司組織：
     - 未啟用 Entra ID → { status: 'disabled' }
     - 查無此人       → { status: 'notfound' }
     - 恰好一筆       → { status: 'ok', user }
     - 同名多筆       → 彈出選擇視窗；選了 → { status: 'ok', user }，取消 → { status: 'cancelled' } */
  async function verifyName(name) {
    if (!enabled) return { status: 'disabled' };
    const list = await searchUsers(name);
    if (list.length === 0) return { status: 'notfound' };
    if (list.length === 1) return { status: 'ok', user: list[0] };
    const picked = await pickUser(name, list);
    return picked ? { status: 'ok', user: picked } : { status: 'cancelled' };
  }

  /* 各使用者信箱時區的快取（null 也快取：查過但取不到就不再重查） */
  const tzCache = {};

  /* 查某使用者（Graph 使用者 id）信箱設定的時區，回傳 { timeZone, iana } 或 null。
     查不到（後端回 204：權限不足、對方無信箱等）與查詢失敗都回 null，不打擾使用者 */
  async function userTimeZone(userId) {
    if (!userId) return null;
    if (userId in tzCache) return tzCache[userId];
    let tz = null;
    try {
      const res = await fetch('/api/entra/timezone?userId=' + encodeURIComponent(userId));
      if (res.status === 200) tz = await res.json();
    } catch (e) { /* 時區僅是輔助資訊，查不到就略過 */ }
    tzCache[userId] = tz;
    return tz;
  }

  /* 讀取登入者行事曆。startIso / endIso 形如 "2026-07-06T00:00:00"；
     回傳事件陣列（時間為台北時區），每筆含 subject、organizer、start、end */
  async function myCalendar(startIso, endIso) {
    return api('/api/entra/calendar?start=' + encodeURIComponent(startIso) +
        '&end=' + encodeURIComponent(endIso));
  }

  // 初始化失敗時不讓 ready 變成 rejected promise，
  // 各頁 await Entra.ready 一律拿到 true / false，不需另行處理例外
  const ready = init().catch(e => {
    console.warn('Entra ID initialization failed; continuing without sign-in:', e);
    return false;
  });
  return {
    ready,
    enabled: () => enabled,
    account: () => account,
    searchUsers,
    suggestUsers,
    attachSuggest,
    verifyName,
    userTimeZone,
    myCalendar
  };
})();
