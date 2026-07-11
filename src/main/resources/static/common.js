/* TimeSurvey 各頁面（index.html / survey.html / stats.html）共用的小工具。
   頁面的 inline script 直接以全域函式呼叫，須在各頁其它 script 之前載入。 */

/* 簡易 HTML 逸出：自由輸入的文字要放進 innerHTML 前先處理 */
function esc(s) {
  return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
const escHtml = esc;

/* "2026-06-10" -> "2026/6/10" */
function fmtYmd(d) {
  const [y, m, dd] = String(d).split('-');
  return y + '/' + (+m) + '/' + (+dd);
}

/* "2026-06-10" -> "6/10" */
function fmtMd(d) {
  const [, m, dd] = d.split('-');
  return (+m) + '/' + (+dd);
}

/* Date -> "yyyy-MM-dd" */
function ymd(d) {
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

function todayYmd() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return ymd(d);
}

function isWeekend(s) {
  const g = new Date(s + 'T00:00:00').getDay();
  return g === 0 || g === 6;
}

/* 起日 a 到迄日 b 的跨度是否在一個月內 */
function within1Month(a, b) {
  const sd = new Date(a + 'T00:00:00');
  const max = new Date(sd);
  max.setMonth(max.getMonth() + 1);
  return new Date(b + 'T00:00:00') <= max;
}

/* 起迄日期展開為調查日清單（跳過週六、週日與被挖空的日期） */
function dateList(start, end, excluded) {
  const ex = new Set(excluded || []);
  const out = [];
  let d = new Date(start + 'T00:00:00');
  const e = new Date(end + 'T00:00:00');
  while (d <= e) {
    const ds = ymd(d);
    if (d.getDay() !== 0 && d.getDay() !== 6 && !ex.has(ds)) {
      out.push(ds);
    }
    d.setDate(d.getDate() + 1);
  }
  return out;
}

/* 起迄時間展開為 30 分鐘時段清單（12:00~13:00 為午休，不列入調查時段） */
function timeSlots(startTime, endTime) {
  const out = [];
  let [h, m] = startTime.slice(0, 5).split(':').map(Number);
  const [eh, em] = endTime.slice(0, 5).split(':').map(Number);
  while (h * 60 + m < eh * 60 + em) {
    const t = String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
    if (t < '12:00' || t >= '13:00') out.push(t);
    m += 30;
    if (m >= 60) { m -= 60; h++; }
  }
  return out;
}

/* "10:30" + 30分 -> "11:00" */
function plus30(t) {
  let h = +t.slice(0, 2), m = +t.slice(3, 5) + 30;
  if (m >= 60) { m -= 60; h++; }
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
}

/* "09:00" + n 個 30 分鐘格 -> 之後的時刻 */
function addSlots(t, n) {
  let r = t;
  for (let i = 0; i < n; i++) r = plus30(r);
  return r;
}

/* ---------- 時區（調查時段以發起者的時區為準） ---------- */

/* 目前瀏覽器的 IANA 時區 */
function myTimeZone() {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) { return ''; }
}

/* IANA 時區於指定時刻的 UTC 偏移（分鐘）；瀏覽器不認得該時區時回傳 null */
function zoneOffsetMinutes(tz, at) {
  if (!tz) return null;
  try {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: 'longOffset' })
        .formatToParts(at || new Date());
    const name = (parts.find(p => p.type === 'timeZoneName') || {}).value || '';
    if (name === 'GMT' || name === 'UTC') return 0;
    const m = name.match(/GMT([+-])(\d{1,2})(?::(\d{2}))?/);
    if (!m) return null;
    return (m[1] === '-' ? -1 : 1) * ((+m[2]) * 60 + (m[3] ? +m[3] : 0));
  } catch (e) {
    return null;
  }
}

/* 480 -> "UTC+8"、330 -> "UTC+5:30"、-240 -> "UTC-4" */
function fmtUtcOffset(min) {
  const sign = min < 0 ? '-' : '+';
  const abs = Math.abs(min);
  const h = Math.floor(abs / 60), m = abs % 60;
  return 'UTC' + sign + h + (m ? ':' + String(m).padStart(2, '0') : '');
}

/* 複製文字到剪貼簿（不支援 clipboard API 的環境退回 textarea + execCommand），完成後呼叫 done */
function copyText(text, done) {
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(done);
  } else {
    const ta = document.createElement('textarea');
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    done();
  }
}
