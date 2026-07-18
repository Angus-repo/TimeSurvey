package com.angus.timesurvey.bdd;

import com.angus.timesurvey.model.Survey;
import com.angus.timesurvey.model.SurveyResponse;
import com.angus.timesurvey.model.SurveyVisit;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * 前端（HTML/JS）BDD 步驟：以 Playwright 無頭瀏覽器操作真實頁面。
 * 只有標記 @ui 的場景會啟動瀏覽器，API 場景不受影響。
 */
public class UiSteps {

    @Autowired private SurveyRepository surveyRepo;
    @Autowired private SurveyResponseRepository responseRepo;
    @Autowired private SurveyVisitRepository visitRepo;
    @Value("${local.server.port}") private int port;

    /** 整個測試 JVM 共用一個瀏覽器（啟動成本高），每個場景開獨立的無痕分頁環境 */
    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext ctx;
    private Page page;

    private final Map<String, String> surveyIds = new HashMap<>();
    /** UI 場景固定的發起者識別碼：寫入瀏覽器 localStorage，後台頁就會列出此 token 建立的調查 */
    private static final String OWNER = "tk-ui-owner";

    private static synchronized Browser sharedBrowser() {
        if (browser == null) {
            playwright = Playwright.create();
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(Arrays.asList("--no-sandbox"));
            // 無法自動下載瀏覽器的環境（如受限網路的 CI），可用環境變數指定既有的 Chromium/Chrome 執行檔
            String exe = System.getenv("PLAYWRIGHT_CHROMIUM_PATH");
            if (exe != null && !exe.isBlank()) {
                options.setExecutablePath(java.nio.file.Paths.get(exe));
            }
            browser = playwright.chromium().launch(options);
        }
        return browser;
    }

    private static BrowserContext newUiContext() {
        return sharedBrowser().newContext(new Browser.NewContextOptions().setLocale("zh-TW"));
    }

    /** 新手引導的「已看過」旗標清單；新增引導時只需在此補上一筆 */
    private static final String[] ONBOARDING_FLAGS = {
            "surveyOnboarded", "surveyGridOnboarded", "surveyCalOnboarded",
            "meetHoursOnboarded", "dragSlotOnboarded", "dateRangeOnboarded", "copyLinkOnboarded"};

    /** 組出設定 ownerToken 與指定「已看過引導」旗標的 init script */
    private static String initScript(String... onboardedFlags) {
        StringBuilder sb = new StringBuilder("localStorage.setItem('ownerToken', '" + OWNER + "');");
        for (String f : onboardedFlags) {
            sb.append("localStorage.setItem('").append(f).append("', '1');");
        }
        return sb.toString();
    }

    /** 除了指定旗標之外的所有引導旗標（模擬「只剩某個引導沒看過」） */
    private static String[] onboardedExcept(String skip) {
        return Arrays.stream(ONBOARDING_FLAGS).filter(f -> !f.equals(skip)).toArray(String[]::new);
    }

    /** 預設視為已看過新手引導，避免遮罩擋住一般場景；引導本身由專屬場景測試 */
    private static final String INIT_SCRIPT = initScript(ONBOARDING_FLAGS);

    /** 換一個全新瀏覽器環境；onboardedFlags 列出「已看過」的引導旗標，未列出的引導會再次出現。
     *  init script 每次載頁都會執行、無法事後移除，所以只能整個環境重建。 */
    private void freshContext(String... onboardedFlags) {
        ctx.close();
        ctx = newUiContext();
        ctx.addInitScript(initScript(onboardedFlags));
    }

    @Before("@ui")
    public void openBrowserContext() {
        ctx = newUiContext();
        ctx.addInitScript(INIT_SCRIPT);
        page = ctx.newPage();
    }

    /** 重建瀏覽器環境並固定時區，讓時區相關場景不受測試機器所在時區影響（需在開啟頁面前使用） */
    @Given("瀏覽器時區為 {string}")
    public void browserTimeZone(String tz) {
        ctx.close();
        ctx = sharedBrowser().newContext(new Browser.NewContextOptions()
                .setLocale("zh-TW").setTimezoneId(tz));
        ctx.addInitScript(INIT_SCRIPT);
        page = ctx.newPage();
    }

    /* ---------- UI 測試截圖與 PDF 報告 ---------- */

    private record Shot(String title, boolean failed, byte[] png) {}
    private static final List<Shot> shots = java.util.Collections.synchronizedList(new ArrayList<>());
    /** 總覽頁截圖（在瀏覽器關閉前產生，內容含中文，故以瀏覽器渲染後轉圖片） */
    private static byte[] summaryPng;

    /** 頁面標題橫幅與 PDF 頂端色條同色：通過為綠底，失敗為紅底 */
    private static final java.awt.Color PASS_COLOR = new java.awt.Color(0x1d7a35);
    private static final java.awt.Color FAIL_COLOR = new java.awt.Color(0xc0392b);

    private static java.awt.Color caseColor(int caseNo, boolean failed) {
        return failed ? FAIL_COLOR : PASS_COLOR;
    }

    private static String hex(java.awt.Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** 每個 @ui 場景結束：在頁面頂端壓上場景標題橫幅後截圖（瀏覽器渲染中文，PDF 端不需字型） */
    @After("@ui")
    public void captureAndClose(io.cucumber.java.Scenario scenario) {
        try {
            if (page != null) {
                int caseNo = shots.size() + 1;
                String time = "截圖時間:" + LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                page.evaluate("([no, title, status, time, color]) => {" +
                        // 先凍結 hover 狀態：插入橫幅會使版面下移，Chromium 會對滑鼠所在位置
                        // 重新派發 mouseover，導致 hover 顯示的 UI（如行事曆會議提示框）在截圖前被關掉
                        "document.addEventListener('mouseover', e => e.stopImmediatePropagation(), true);" +
                        "const b = document.createElement('div');" +
                        "b.style.cssText = 'display:flex;align-items:center;gap:14px;background:' + color + ';" +
                        "color:#fff;padding:14px 20px;font-family:sans-serif;';" +
                        "const n = document.createElement('span'); n.textContent = '場景 ' + no;" +
                        "n.style.cssText = 'background:rgba(255,255,255,.22);border:1.5px solid rgba(255,255,255,.6);" +
                        "border-radius:16px;padding:4px 16px;font-size:16px;font-weight:700;white-space:nowrap;';" +
                        "const t = document.createElement('span'); t.textContent = title;" +
                        "t.style.cssText = 'font-size:22px;font-weight:700;flex:1;';" +
                        "const s = document.createElement('span'); s.textContent = status;" +
                        "s.style.cssText = 'font-size:16px;font-weight:700;white-space:nowrap;';" +
                        "const c = document.createElement('span'); c.textContent = time;" +
                        "c.style.cssText = 'font-size:13px;opacity:.9;white-space:nowrap;';" +
                        "b.append(n, t, s, c); document.body.prepend(b);" +
                        // 行事曆提示框以 fixed 座標貼齊所在時段：版面被橫幅推下後把它一起下移，截圖才對得上
                        "const tip = document.getElementById('calTip');" +
                        "if (tip && tip.style.display === 'block') {" +
                        "  tip.style.top = (parseFloat(tip.style.top) + b.offsetHeight) + 'px';" +
                        "} }",
                        Arrays.asList(String.valueOf(caseNo), scenario.getName(),
                                scenario.isFailed() ? "✘ 失敗" : "✔ 通過", time,
                                hex(caseColor(caseNo, scenario.isFailed()))));
                byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                shots.add(new Shot(scenario.getName(), scenario.isFailed(), png));
                scenario.attach(png, "image/png", scenario.getName());   // 同步嵌入 Cucumber HTML 報告
            }
        } catch (Exception ignored) {
        } finally {
            if (ctx != null) ctx.close();
        }
    }

    @AfterAll
    public static void closeBrowser() {
        if (browser != null) {
            captureSummaryShot();
            browser.close();
        }
        if (playwright != null) playwright.close();
        writePdfReport();
    }

    /** 在瀏覽器關閉前，用一個獨立頁面渲染「總覽」統計（總數／通過／失敗）並截圖，供 PDF 第一頁使用 */
    private static void captureSummaryShot() {
        if (shots.isEmpty()) return;
        try {
            int total = shots.size();
            long failed = shots.stream().filter(Shot::failed).count();
            long passed = total - failed;
            StringBuilder rows = new StringBuilder();
            for (int i = 0; i < shots.size(); i++) {
                Shot s = shots.get(i);
                rows.append("<div style='display:flex;align-items:center;gap:12px;padding:9px 16px;")
                        .append(i % 2 == 0 ? "background:#f7f7f7;" : "")
                        .append("'>")
                        .append("<span style='width:32px;color:#888;font-weight:700;'>").append(i + 1).append("</span>")
                        .append("<span style='flex:1;font-size:15px;'>").append(escapeHtml(s.title())).append("</span>")
                        .append("<span style='font-weight:700;color:").append(hex(caseColor(i + 1, s.failed()))).append(";'>")
                        .append(s.failed() ? "✘ 失敗" : "✔ 通過").append("</span>")
                        .append("</div>");
            }
            String time = LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String html = "<html><body style='margin:0;font-family:sans-serif;width:1240px;'>" +
                    "<div style='padding:36px 44px;'>" +
                    "<h1 style='margin:0 0 6px;font-size:30px;'>UI 測試報告總覽</h1>" +
                    "<div style='color:#666;font-size:14px;margin-bottom:26px;'>產出時間：" + time + "</div>" +
                    "<div style='display:flex;gap:20px;margin-bottom:30px;'>" +
                    statCard("測試案例總數", String.valueOf(total), "#333") +
                    statCard("通過", String.valueOf(passed), hex(PASS_COLOR)) +
                    statCard("失敗", String.valueOf(failed), hex(FAIL_COLOR)) +
                    "</div>" +
                    "<div style='border:1px solid #e0e0e0;border-radius:8px;overflow:hidden;'>" + rows + "</div>" +
                    "</div></body></html>";
            try (BrowserContext c = browser.newContext(new Browser.NewContextOptions().setLocale("zh-TW"))) {
                Page p = c.newPage();
                p.setContent(html);
                summaryPng = p.screenshot(new Page.ScreenshotOptions().setFullPage(true));
            }
        } catch (Exception ignored) {
        }
    }

    private static String statCard(String label, String value, String color) {
        return "<div style='flex:1;border:1px solid #e0e0e0;border-radius:8px;padding:18px 20px;text-align:center;'>" +
                "<div style='font-size:13px;color:#888;margin-bottom:6px;'>" + label + "</div>" +
                "<div style='font-size:34px;font-weight:800;color:" + color + ";'>" + value + "</div>" +
                "</div>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 將本次所有 UI 截圖彙整成 target/ui-test-report.pdf（A4）。
     * 截圖過長時切成多頁；每頁頂端畫該 test case 專屬顏色的色條與「Case N (頁次/總頁數)」，
     * 與頁面內的標題橫幅同色，方便快速辨識同一案例的連續頁。
     */
    private static void writePdfReport() {
        if (shots.isEmpty()) return;
        final float pageW = org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth();
        final float pageH = org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getHeight();
        final float barH = 26f;
        final float imgAreaH = pageH - barH;
        var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD);
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            int pdfPages = 0;
            // 第一頁：總覽（測試案例總數／通過／失敗），以瀏覽器渲染的圖片呈現（PDFBox 內建字型不支援中文）
            if (summaryPng != null) {
                pdfPages += addImagePages(doc, summaryPng, "Summary", new java.awt.Color(0x333333),
                        font, pageW, pageH, barH, imgAreaH);
            }
            for (int i = 0; i < shots.size(); i++) {
                Shot s = shots.get(i);
                int caseNo = i + 1;
                String label = "Case " + caseNo + (s.failed() ? "  [FAILED]" : "");
                pdfPages += addImagePages(doc, s.png(), label, caseColor(caseNo, s.failed()),
                        font, pageW, pageH, barH, imgAreaH);
            }
            doc.save("target/ui-test-report.pdf");
            System.out.println("UI 測試截圖報告：target/ui-test-report.pdf（" +
                    shots.size() + " 個場景，共 " + pdfPages + " 頁）");
        } catch (Exception e) {
            System.err.println("UI 測試 PDF 報告產生失敗：" + e);
        }
    }

    /**
     * 將一張截圖（過長時切成多頁）畫入 PDF，每頁頂端加上同色色條與標籤文字。
     * 回傳實際新增的頁數。
     */
    private static int addImagePages(org.apache.pdfbox.pdmodel.PDDocument doc, byte[] png, String baseLabel,
            java.awt.Color color, org.apache.pdfbox.pdmodel.font.PDType1Font font,
            float pageW, float pageH, float barH, float imgAreaH) throws Exception {
        var full = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        // 每頁可容納的截圖高度（像素），以 A4 寬度等比換算
        int chunkPx = Math.max(1, (int) Math.floor(full.getWidth() * (imgAreaH / pageW)));
        int total = Math.max(1, (int) Math.ceil(full.getHeight() / (double) chunkPx));
        for (int p = 0; p < total; p++) {
            int y = p * chunkPx;
            int h = Math.min(chunkPx, full.getHeight() - y);
            var part = full.getSubimage(0, y, full.getWidth(), h);
            var img = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, part);
            var pg = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(pg);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, pg)) {
                // 頂端色條：同一張截圖的每一頁同色
                cs.setNonStrokingColor(color);
                cs.addRect(0, pageH - barH, pageW, barH);
                cs.fill();
                cs.setNonStrokingColor(java.awt.Color.WHITE);
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(16, pageH - barH + 8);
                // 單頁不顯示頁次；切成多頁時才標示 (頁次/總頁數)
                cs.showText(baseLabel + (total > 1 ? "   (" + (p + 1) + "/" + total + ")" : ""));
                cs.endText();
                float drawH = h * (pageW / full.getWidth());
                cs.drawImage(img, 0, pageH - barH - drawH, pageW, drawH);
            }
        }
        return total;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    /* ---------- 測試資料（直接寫入資料庫） ---------- */

    @Given("存在調查 {string}，日期 {string} 到 {string}，時間 {string} 到 {string}，人員 {string}")
    public void surveyExists(String name, String startDate, String endDate,
                             String startTime, String endTime, String people) {
        Survey s = new Survey();
        s.setId(UUID.randomUUID().toString());
        s.setName(name);
        s.setStartDate(LocalDate.parse(startDate));
        s.setEndDate(LocalDate.parse(endDate));
        s.setStartTime(LocalTime.parse(startTime));
        s.setEndTime(LocalTime.parse(endTime));
        s.setParticipants(new ArrayList<>(Arrays.asList(people.split(","))));
        s.setOwnerToken(OWNER);
        s.setCreatedAt(LocalDateTime.now());
        surveyRepo.save(s);
        surveyIds.put(name, s.getId());
    }

    @Given("存在調查 {string}，日期 {string} 到 {string}，時間 {string} 到 {string}，人員 {string}，挖空日期 {string}")
    public void surveyExistsWithExcluded(String name, String startDate, String endDate,
                                         String startTime, String endTime, String people, String excluded) {
        surveyExists(name, startDate, endDate, startTime, endTime, people);
        Survey s = surveyRepo.findById(surveyIds.get(name)).orElseThrow();
        List<LocalDate> ex = new ArrayList<>();
        if (!excluded.isBlank()) {
            for (String d : excluded.split(",")) ex.add(LocalDate.parse(d.trim()));
        }
        s.setExcludedDates(ex);
        surveyRepo.save(s);
    }

    @Given("存在調查 {string}，日期 {string} 到 {string}，時間 {string} 到 {string}，人員 {string}，發起者時區 {string}")
    public void surveyExistsWithTimeZone(String name, String startDate, String endDate,
                                         String startTime, String endTime, String people, String timeZone) {
        surveyExists(name, startDate, endDate, startTime, endTime, people);
        Survey s = surveyRepo.findById(surveyIds.get(name)).orElseThrow();
        s.setTimeZone(timeZone);
        surveyRepo.save(s);
    }

    @Given("存在調查 {string}，日期 {string} 到 {string}，時間 {string} 到 {string}，人員 {string}，允許成員加寄")
    public void surveyExistsWithInvite(String name, String startDate, String endDate,
                                       String startTime, String endTime, String people) {
        surveyExists(name, startDate, endDate, startTime, endTime, people);
        Survey s = surveyRepo.findById(surveyIds.get(name)).orElseThrow();
        s.setAllowAddParticipant(true);
        surveyRepo.save(s);
    }

    @Given("調查 {string} 已有 {string} 的填寫紀錄 {string}")
    public void responseExists(String surveyName, String person, String slots) {
        SurveyResponse r = new SurveyResponse();
        r.setSurveyId(surveyIds.get(surveyName));
        r.setParticipantName(person);
        r.setSlots(slots);
        r.setUpdatedAt(LocalDateTime.now());
        responseRepo.save(r);
    }

    @Given("調查 {string} 已結束")
    public void surveyClosed(String surveyName) {
        Survey s = surveyRepo.findById(surveyIds.get(surveyName)).orElseThrow();
        s.setClosedAt(LocalDateTime.now());
        surveyRepo.save(s);
    }

    @Given("調查 {string} 已被開啟 {int} 次，來自 {int} 個不同 IP")
    public void visitsExist(String surveyName, int visits, int ips) {
        for (int i = 0; i < visits; i++) {
            SurveyVisit v = new SurveyVisit();
            v.setSurveyId(surveyIds.get(surveyName));
            v.setIp("10.0.0." + (i % ips + 1));
            v.setVisitedAt(LocalDateTime.now());
            visitRepo.save(v);
        }
    }

    /* ---------- 調查填寫頁 ---------- */

    @When("開啟調查 {string} 的填寫頁")
    public void openSurveyPage(String surveyName) {
        page.navigate(base() + "/s/" + surveyIds.get(surveyName));
        page.locator(".slot").first().waitFor();   // 等格線渲染完成
    }

    @When("以首次使用者身分開啟調查 {string} 的填寫頁")
    public void openSurveyPageFirstTime(String surveyName) {
        freshContext();   // 沒有任何「已看過引導」紀錄
        page = ctx.newPage();
        page.navigate(base() + "/s/" + surveyIds.get(surveyName));
        page.locator(".slot").first().waitFor();
    }

    @When("以未使用過大日曆的身分開啟調查 {string} 的填寫頁")
    public void openSurveyPageWithoutDateRangeOnboarding(String surveyName) {
        // 只清掉 dateRangeOnboarded，模擬「index / survey 都沒用過大日曆」；
        // 其他填寫頁教學維持已看過，避免遮罩干擾本場景。
        freshContext(onboardedExcept("dateRangeOnboarded"));
        page = ctx.newPage();
        page.navigate(base() + "/s/" + surveyIds.get(surveyName));
        page.locator(".slot").first().waitFor();
    }

    @Then("應顯示選擇姓名的新手引導")
    public void onboardingShown() {
        assertThat(page.locator("#onbPop")).isVisible();
        assertThat(page.locator("#onbMask")).isVisible();
    }

    @Then("新手引導應消失")
    public void onboardingGone() {
        // 只驗證姓名引導泡泡消失；遮罩可能因緊接著的表格操作引導而繼續顯示
        assertThat(page.locator("#onbPop")).isHidden();
    }

    @Then("應顯示表格操作的新手引導")
    public void gridOnboardingShown() {
        assertThat(page.locator("#onbPopGrid")).isVisible();
        assertThat(page.locator("#onbMask")).isVisible();
    }

    @Then("表格操作引導的左半段應完整顯示在畫面上")
    public void gridOnboardingLeftFullyVisible() {
        assertThat(page.locator("#onbPopGrid")).isVisible();
        assertThat(page.locator("#onbColLeft")).isVisible();
        waitUntilInsideViewport("#gridCard th.datecell");
        waitUntilInsideViewport("#onbPopGrid");
        assertInsideViewport(page.locator("#onbPopGrid"), "左半段引導泡泡");
        assertInsideViewport(page.locator("#gridCard th.datecell").first(), "左半段快速操作區");
        Boolean arrowPointsLeft = (Boolean) page.locator("#onbPopGrid").evaluate(
                "el => el.classList.contains('at-right') && " +
                "getComputedStyle(el, '::before').borderRightColor !== 'rgba(0, 0, 0, 0)'");
        org.junit.jupiter.api.Assertions.assertTrue(arrowPointsLeft,
                "左半段說明應顯示指向左半段的小箭頭");
    }

    @Then("表格操作引導的右半段應完整顯示在畫面上")
    public void gridOnboardingRightFullyVisible() {
        assertThat(page.locator("#onbPopGrid")).isVisible();
        assertThat(page.locator("#onbColRight")).isVisible();
        waitUntilInsideViewport("#gridCard td.hourcell .hbox");
        waitUntilInsideViewport("#onbPopGrid");
        assertInsideViewport(page.locator("#onbPopGrid"), "右半段引導泡泡");
        assertInsideViewport(page.locator("#gridCard td.hourcell .hbox").first(), "右半段時段操作區");
        Boolean arrowPointsRight = (Boolean) page.locator("#onbPopGrid").evaluate(
                "el => el.classList.contains('at-left') && " +
                "getComputedStyle(el, '::before').borderLeftColor !== 'rgba(0, 0, 0, 0)'");
        org.junit.jupiter.api.Assertions.assertTrue(arrowPointsRight,
                "右半段說明應顯示指向右半段的小箭頭");
    }

    @Then("表格操作引導的表格區應停在操作列下方的理想高度")
    public void gridOnboardingCardAtIdealHeight() {
        waitForPageScrollToSettle();
        page.waitForFunction("""
                () => {
                  const card = document.getElementById('gridCard');
                  if (!card) return false;
                  const cardRect = card.getBoundingClientRect();
                  const actionBar = document.getElementById('actionBar');
                  if (actionBar && actionBar.getClientRects().length > 0) {
                    const actionRect = actionBar.getBoundingClientRect();
                    const gap = cardRect.top - actionRect.bottom;
                    return actionRect.top >= -4 && gap >= 8 && gap <= 36;
                  }
                  return cardRect.top >= 56 && cardRect.top <= 150;
                }
                """);
        var box = page.locator("#gridCard").boundingBox();
        org.junit.jupiter.api.Assertions.assertNotNull(box, "表格區應可取得位置");
        var actionBar = page.locator("#actionBar").boundingBox();
        if (actionBar != null) {
            double gap = box.y - (actionBar.y + actionBar.height);
            org.junit.jupiter.api.Assertions.assertTrue(actionBar.y >= -4,
                    "操作列應保留在畫面上方，實際 y=" + actionBar.y);
            org.junit.jupiter.api.Assertions.assertTrue(gap >= 8 && gap <= 36,
                    "表格區應停在操作列下方約一個卡片間隔，實際間距=" + gap);
        } else {
            org.junit.jupiter.api.Assertions.assertTrue(box.y >= 56 && box.y <= 150,
                    "沒有操作列時表格區應停在畫面上方但不貼頂，實際 y=" + box.y);
        }
    }

    @When("切換為手機版面")
    public void switchToMobileLayout() {
        page.setViewportSize(390, 844);
        page.waitForFunction("window.matchMedia('(max-width: 720px)').matches");
    }

    @Then("手機版表格操作引導應移動到上方快速操作並完整顯示")
    public void mobileGridOnboardingMovesToQuickActions() {
        assertThat(page.locator("#onbLeftTitleText")).containsText("上方");
        waitForMobileGridOnboardingTarget("#gridCard th.datecell");
        waitUntilInsideViewport("#onbPopGrid");
        assertInsideViewport(page.locator("#gridCard th.datecell").first(), "手機版上方快速操作區");
        assertInsideViewport(page.locator("#onbPopGrid"), "手機版上方操作引導泡泡");
        assertThat(page.locator("#onbPopGrid")).hasClass(java.util.regex.Pattern.compile(".*\\bat-below\\b.*"));
        page.evaluate("window.__mobileGridOnbFirstScrollY = window.scrollY");
    }

    @Then("手機版表格操作引導應移動到下方細部時段並完整顯示")
    public void mobileGridOnboardingMovesToDetailedSlots() {
        assertThat(page.locator("#onbRightTitleText")).containsText("下方");
        waitForMobileGridOnboardingTarget("#gridCard td.hourcell .hbox");
        waitUntilInsideViewport("#onbPopGrid");
        assertInsideViewport(page.locator("#gridCard td.hourcell .hbox").first(), "手機版下方細部時段區");
        assertInsideViewport(page.locator("#onbPopGrid"), "手機版下方時段引導泡泡");
        Number firstScrollY = (Number) page.evaluate("window.__mobileGridOnbFirstScrollY || 0");
        Number currentScrollY = (Number) page.evaluate("window.scrollY");
        org.junit.jupiter.api.Assertions.assertTrue(currentScrollY.doubleValue() > firstScrollY.doubleValue() + 10,
                "手機版第 2 步應從上方快速操作平滑移動到下方細部時段，第一次 scrollY="
                        + firstScrollY + "，第二次 scrollY=" + currentScrollY);
    }

    private void waitForMobileGridOnboardingTarget(String selector) {
        page.waitForFunction("""
                selector => {
                  const el = document.querySelector(selector);
                  if (!el || !window.matchMedia('(max-width: 720px)').matches) return false;
                  return Math.abs(el.getBoundingClientRect().top - gridOnbTargetTop()) <= 2;
                }
                """, selector);
    }

    private void assertInsideViewport(Locator locator, String label) {
        var box = locator.boundingBox();
        org.junit.jupiter.api.Assertions.assertNotNull(box, label + "應可取得位置");
        var vp = page.viewportSize();
        double tolerance = 1.0;
        org.junit.jupiter.api.Assertions.assertTrue(box.x >= -tolerance,
                label + "左側不應超出畫面，實際 x=" + box.x);
        org.junit.jupiter.api.Assertions.assertTrue(box.y >= -tolerance,
                label + "上方不應超出畫面，實際 y=" + box.y);
        org.junit.jupiter.api.Assertions.assertTrue(box.x + box.width <= vp.width + tolerance,
                label + "右側不應超出畫面，實際 right=" + (box.x + box.width));
        org.junit.jupiter.api.Assertions.assertTrue(box.y + box.height <= vp.height + tolerance,
                label + "下方不應超出畫面，實際 bottom=" + (box.y + box.height));
    }

    /** 平滑捲動是非同步動畫；等目標實際進入畫面後再檢查最終座標，避免讀到移動途中的位置。 */
    private void waitUntilInsideViewport(String selector) {
        page.waitForFunction("""
                selector => {
                  const el = document.querySelector(selector);
                  if (!el) return false;
                  const r = el.getBoundingClientRect();
                  return r.left >= -1 && r.top >= -1
                      && r.right <= window.innerWidth + 1
                      && r.bottom <= window.innerHeight + 1;
                }
                """, selector);
    }

    /** 等頁面連續數幀維持同一捲動位置，再分別讀取元素座標，避免兩次量測落在動畫的不同影格。 */
    private void waitForPageScrollToSettle() {
        page.waitForFunction("""
                () => new Promise(resolve => {
                  let lastX = window.scrollX;
                  let lastY = window.scrollY;
                  let stableFrames = 0;
                  const check = () => {
                    const stable = Math.abs(window.scrollX - lastX) < 0.25
                        && Math.abs(window.scrollY - lastY) < 0.25;
                    stableFrames = stable ? stableFrames + 1 : 0;
                    lastX = window.scrollX;
                    lastY = window.scrollY;
                    if (stableFrames >= 4) resolve(true);
                    else requestAnimationFrame(check);
                  };
                  requestAnimationFrame(check);
                })
                """);
    }

    @When("開始記錄頁面定位捲動方式")
    public void startRecordingPositioningScrollBehavior() {
        page.evaluate("""
                () => {
                  window.__positioningScrollCalls = [];
                  if (window.__positioningScrollRecorderInstalled) return;
                  window.__positioningScrollRecorderInstalled = true;

                  const behaviorOf = args => {
                    const options = args.length === 1 && args[0] && typeof args[0] === 'object'
                        ? args[0] : null;
                    return options && options.behavior ? options.behavior : null;
                  };
                  const record = (method, args) => window.__positioningScrollCalls.push({
                    method,
                    behavior: behaviorOf(args)
                  });

                  const windowScrollTo = window.scrollTo;
                  window.scrollTo = function(...args) {
                    record('window.scrollTo', args);
                    return windowScrollTo.apply(window, args);
                  };

                  const elementScrollTo = Element.prototype.scrollTo;
                  if (elementScrollTo) {
                    Element.prototype.scrollTo = function(...args) {
                      record('element.scrollTo', args);
                      return elementScrollTo.apply(this, args);
                    };
                  }

                  const scrollIntoView = Element.prototype.scrollIntoView;
                  Element.prototype.scrollIntoView = function(...args) {
                    record('element.scrollIntoView', args);
                    return scrollIntoView.apply(this, args);
                  };
                }
                """);
    }

    @Then("頁面定位捲動應使用平滑效果")
    public void positioningScrollUsesSmoothBehavior() {
        Number count = (Number) page.evaluate("window.__positioningScrollCalls?.length || 0");
        String calls = (String) page.evaluate("JSON.stringify(window.__positioningScrollCalls || [])");
        org.junit.jupiter.api.Assertions.assertTrue(count.intValue() > 0,
                "應至少發生一次頁面定位捲動，實際紀錄=" + calls);
        Boolean allSmooth = (Boolean) page.evaluate(
                "(window.__positioningScrollCalls || []).every(call => call.behavior === 'smooth')");
        org.junit.jupiter.api.Assertions.assertTrue(allSmooth,
                "所有頁面定位捲動都應使用 smooth，實際紀錄=" + calls);
    }

    @When("按下表格操作引導的按鈕")
    public void clickGridOnboardingButton() {
        page.click("#onbGridBtn");
    }

    /** Entra ID 登入模擬需在重建瀏覽器環境「之後」掛 route，因此與首次使用者開頁合為一步 */
    @When("模擬已啟用 Entra ID 登入且登入者為 {string} 並以首次使用者身分開啟調查 {string} 的填寫頁")
    public void openSurveyPageFirstTimeWithEntra(String name, String surveyName) {
        freshContext();
        mockEntraSignedIn(name);
        page = ctx.newPage();
        page.navigate(base() + "/s/" + surveyIds.get(surveyName));
        page.locator(".slot").first().waitFor();
    }

    @Then("應顯示同意讀取行事曆的新手引導")
    public void calOnboardingShown() {
        assertThat(page.locator("#onbPopCal")).isVisible();
        assertThat(page.locator("#onbMask")).isVisible();
    }

    @When("按下行事曆引導的知道了")
    public void clickCalOnboardingGotIt() {
        page.click("#onbPopCal button");
    }

    @Then("行事曆引導應消失")
    public void calOnboardingGone() {
        // 只驗證行事曆引導泡泡消失；遮罩可能因緊接著的表格操作引導而繼續顯示
        assertThat(page.locator("#onbPopCal")).isHidden();
    }

    @Then("表格操作引導應消失")
    public void gridOnboardingGone() {
        assertThat(page.locator("#onbPopGrid")).isHidden();
        assertThat(page.locator("#onbMask")).isHidden();
    }

    @When("選擇姓名 {string}")
    public void chooseName(String name) {
        // 「您的姓名」已改為自訂下拉（按鈕＋浮動清單，而非原生 <select>），
        // 好讓來源說明（例如「（由 X 邀請加入）」）能以灰色文字呈現
        page.click("#whoTrigger");
        page.click(".who-item[data-name='" + name + "']");
    }

    @When("點擊時段 {string}")
    public void clickSlot(String slot) {
        // force：唯讀模式下時段有 pointer-events:none，一般點擊會因無法命中而逾時等待，
        // 用 force 直接觸發點擊座標即可（若被唯讀樣式擋下，點擊將落在外層 hbox 而不會選取該時段）
        page.click(".slot[data-slot='" + slot + "']", new Page.ClickOptions().setForce(true));
    }

    @When("點擊 {string} 的 {string} 時勾勾")
    public void clickHourCheck(String date, String hour) {
        page.click(".hbox[data-date='" + date + "'][data-hour='" + hour + "'] .chk");
    }

    @When("點擊 {string} 的複製鈕")
    public void clickCopyBtn(String date) {
        page.click(".copybtn[data-date='" + date + "']");
    }

    @When("按住修飾鍵點擊 {string} 的複製鈕")
    public void modifierClickCopyBtn(String date) {
        // macOS 上 Ctrl+click 會被視為右鍵，改用 Meta(⌘) 觸發「按住修飾鍵連續貼上」
        page.click(".copybtn[data-date='" + date + "']", new Page.ClickOptions()
                .setModifiers(Arrays.asList(com.microsoft.playwright.options.KeyboardModifier.META)));
    }

    @Then("{string} 的複製鈕狀態應為 {string}")
    public void copyBtnState(String date, String state) {
        assertThat(page.locator(".copybtn[data-date='" + date + "']")).hasAttribute("data-state", state);
    }

    @When("按下送出")
    public void clickSubmit() {
        page.click("#submitBtn");
    }

    @When("選擇沒有我可以出席的時間")
    public void chooseNoAvailableTimeSubmitOption() {
        page.click("#submitMenuBtn");
        assertThat(page.locator("#submitMenu")).isVisible();
        page.click("#miNoTime");
    }

    @Then("應顯示完全沒有可出席時段視窗")
    public void noTimeModalShown() {
        assertThat(page.locator("#noTimeModal")).isVisible();
        assertThat(page.locator("#noTimeModal")).containsText("完全沒有可出席的時段");
    }

    @Then("完全沒有可出席時段視窗不應顯示")
    public void noTimeModalHidden() {
        assertThat(page.locator("#noTimeModal")).isHidden();
    }

    @Then("建議日期區間應使用大日曆選擇器")
    public void suggestedDatesUseCalendarPicker() {
        assertThat(page.locator("#suggestCalTrigger")).isVisible();
        assertThat(page.locator("#suggestCalPop")).isHidden();
        assertThat(page.locator("#suggestStart")).hasAttribute("type", "hidden");
        assertThat(page.locator("#suggestEnd")).hasAttribute("type", "hidden");
        assertThat(page.locator("#suggestExcludedDates")).hasAttribute("type", "hidden");
    }

    @Then("建議日期區間應預設為 {string} 到 {string}")
    public void suggestedDateRangeDefaultsTo(String from, String to) {
        assertThat(page.locator("#suggestStart")).hasValue(from);
        assertThat(page.locator("#suggestEnd")).hasValue(to);
    }

    @When("開啟建議日期區間日曆")
    public void openSuggestedDateCalendar() {
        page.click("#suggestCalTrigger");
        assertThat(page.locator("#suggestCalPop")).isVisible();
    }

    @When("在建議日期大日曆選擇前兩個可選工作日")
    public void chooseFirstTwoSuggestedCalendarDays() {
        openSuggestedDateCalendar();
        var days = page.locator("#suggestCalDays .cal-day:not(.cal-disabled):not(.cal-blank)");
        org.junit.jupiter.api.Assertions.assertTrue(days.count() >= 2, "大日曆至少應有兩個可選工作日");
        days.nth(0).click();
        days = page.locator("#suggestCalDays .cal-day:not(.cal-disabled):not(.cal-blank)");
        days.nth(1).click();
    }

    @When("在建議日期大日曆選擇前三個可選工作日並挖空中間日期")
    public void chooseFirstThreeSuggestedCalendarDaysAndExcludeMiddle() {
        openSuggestedDateCalendar();
        var days = page.locator("#suggestCalDays .cal-day:not(.cal-disabled):not(.cal-blank):not(.cal-start):not(.cal-end):not(.cal-single)");
        org.junit.jupiter.api.Assertions.assertTrue(days.count() >= 3, "大日曆至少應有三個可選工作日");
        String start = days.nth(0).getAttribute("data-d");
        String middle = days.nth(1).getAttribute("data-d");
        String end = days.nth(2).getAttribute("data-d");
        page.click("#suggestCalDays .cal-day[data-d='" + start + "']");
        page.click("#suggestCalDays .cal-day[data-d='" + end + "']");
        page.click("#suggestCalDays .cal-day[data-d='" + middle + "']");
        assertThat(page.locator("#suggestCalDays .cal-day.cal-excluded[data-d='" + middle + "']")).isVisible();
    }

    @Then("建議日期區間應已填入起迄日")
    public void suggestedDateRangeFilled() {
        String from = (String) page.locator("#suggestStart").inputValue();
        String to = (String) page.locator("#suggestEnd").inputValue();
        org.junit.jupiter.api.Assertions.assertFalse(from.isBlank(), "建議起日應已填入");
        org.junit.jupiter.api.Assertions.assertFalse(to.isBlank(), "建議迄日應已填入");
        org.junit.jupiter.api.Assertions.assertTrue(to.compareTo(from) >= 0, "建議迄日不可早於起日");
        assertThat(page.locator("#suggestCalTriggerText")).containsText("~");
    }

    @Then("建議日期區間應有 {int} 個挖空日期")
    public void suggestedDateRangeHasExcludedDates(int count) {
        String excluded = page.locator("#suggestExcludedDates").inputValue();
        int actual = excluded.isBlank() ? 0 : excluded.split(",").length;
        org.junit.jupiter.api.Assertions.assertEquals(count, actual, "建議日期區間的挖空日期數量不符：" + excluded);
        assertThat(page.locator("#suggestCalTriggerText .skipbadge")).containsText(count + " 天");
    }

    @Then("建議日期區間挖空徽章應顯示即時提示 {string}")
    public void suggestedDateRangeSkipBadgeShowsInstantTip(String tipContains) {
        var badge = page.locator("#suggestCalTriggerText .skipbadge");
        assertDataTipContains(badge, tipContains);
        assertSharedSkipBadgeStyle(badge);
    }

    @Then("時段 {string} 應為選取狀態")
    public void slotSelected(String slot) {
        assertThat(page.locator(".slot[data-slot='" + slot + "']")).hasClass("slot on");
    }

    @Then("時段 {string} 不應為選取狀態")
    public void slotNotSelected(String slot) {
        assertThat(page.locator(".slot[data-slot='" + slot + "']")).hasClass("slot");
    }

    @Then("填寫頁應出現日期 {string}")
    public void fillPageHasDate(String date) {
        org.junit.jupiter.api.Assertions.assertTrue(
                page.locator(".slot[data-slot^='" + date + "T']").count() > 0,
                "填寫頁應出現日期 " + date);
    }

    @Then("填寫頁不應出現日期 {string}")
    public void fillPageHasNoDate(String date) {
        org.junit.jupiter.api.Assertions.assertEquals(0,
                page.locator(".slot[data-slot^='" + date + "T']").count(),
                "填寫頁不應出現被挖空的日期 " + date);
    }

    @Then("填寫頁應出現上午與下午的分隔")
    public void fillPageHasAmPmSeparator() {
        org.junit.jupiter.api.Assertions.assertTrue(
                page.locator("td.col-sep").count() > 0,
                "填寫頁應出現上午與下午之間的分隔欄");
    }

    @Then("填寫頁應出現週次分隔")
    public void fillPageHasWeekSeparator() {
        org.junit.jupiter.api.Assertions.assertTrue(
                page.locator("tr.week-sep").count() > 0,
                "填寫頁應出現不同週之間的分隔列");
    }

    @Then("填寫頁不應出現週次分隔")
    public void fillPageHasNoWeekSeparator() {
        org.junit.jupiter.api.Assertions.assertEquals(0,
                page.locator("tr.week-sep").count(),
                "單一週的調查不應出現週次分隔列");
    }

    @Then("應出現提示 {string}")
    public void toastShows(String message) {
        assertThat(page.locator("#toast")).containsText(message);
    }

    /* ---------- 邀請他人加入視窗 ---------- */

    @When("開啟邀請他人加入視窗")
    public void openInviteModal() {
        // 邀請鈕已改為成員操作複合按鈕：只開放邀請時主按鈕即為「邀請其他人加入」
        page.click("#btnMemberMain");
        assertThat(page.locator("#inviteModal")).isVisible();
    }

    @When("在邀請視窗輸入姓名 {string} 並按下 Enter")
    public void typeInviteNameAndEnter(String name) {
        page.fill("#inviteInput", name);
        page.press("#inviteInput", "Enter");
    }

    @When("在邀請視窗輸入姓名 {string}")
    public void typeInviteName(String name) {
        page.fill("#inviteInput", name);
    }

    @When("按下確認邀請")
    public void clickConfirmInvite() {
        page.click("#inviteModal .topbtn");
    }

    @Then("提示訊息應顯示在邀請視窗之上")
    public void toastAboveInviteModal() {
        // toast 的 z-index 必須高於彈窗與其遮罩，否則提示會被遮罩壓住看不到
        int toastZ = Integer.parseInt((String) page.evaluate(
                "getComputedStyle(document.getElementById('toast')).zIndex"));
        int modalZ = Integer.parseInt((String) page.evaluate(
                "getComputedStyle(document.getElementById('inviteModal')).zIndex"));
        org.junit.jupiter.api.Assertions.assertTrue(toastZ > modalZ,
                "提示訊息的 z-index (" + toastZ + ") 應高於邀請視窗 (" + modalZ + ")，否則會被遮罩壓住看不到");
    }

    @Then("應顯示完成畫面，且姓名為 {string}")
    public void doneViewShown(String name) {
        assertThat(page.locator("#done")).isVisible();
        assertThat(page.locator("#doneName")).hasText(name);
        assertThat(page.locator("#main")).isHidden();
    }

    @Then("完成畫面應包含 {string}")
    public void doneViewContains(String text) {
        assertThat(page.locator("#doneList")).containsText(text);
    }

    @Then("送出按鈕不應顯示")
    public void submitButtonHidden() {
        assertThat(page.locator("#submitBtn")).isHidden();
    }

    @Then("應顯示調查已結束的唯讀提示橫幅")
    public void closedBannerShown() {
        assertThat(page.locator("#closedBanner")).isVisible();
        assertThat(page.locator("#closedBanner")).containsText("已結束");
    }

    @Then("應顯示發起者時區的提示橫幅，內容包含 {string}")
    public void tzBannerShown(String text) {
        assertThat(page.locator("#tzBanner")).isVisible();
        assertThat(page.locator("#tzBanner")).containsText(text);
    }

    @Then("不應顯示發起者時區的提示橫幅")
    public void tzBannerHidden() {
        assertThat(page.locator("#tzBanner")).isHidden();
    }

    @Then("填寫頁不應顯示帶入行事曆按鈕")
    public void calendarButtonHidden() {
        assertThat(page.locator("#btnCal")).isHidden();
    }

    @Then("送出與成員操作複合按鈕文字前方應顯示圖示")
    public void splitButtonsShowLeadingIcons() {
        assertLeadingIconBeforeText("#btnMemberMain", "🚫", "不參加此會議");
        assertLeadingIconBeforeText("#submitBtn", "🕒", "送出我的可出席時間");
    }

    private void assertLeadingIconBeforeText(String buttonSelector, String icon, String text) {
        assertThat(page.locator(buttonSelector + " .btn-icon")).isVisible();
        assertThat(page.locator(buttonSelector + " .btn-icon")).hasText(icon);
        assertThat(page.locator(buttonSelector + " .btn-text")).hasText(text);
        var iconBox = page.locator(buttonSelector + " .btn-icon").boundingBox();
        var textBox = page.locator(buttonSelector + " .btn-text").boundingBox();
        org.junit.jupiter.api.Assertions.assertTrue(iconBox.x < textBox.x,
                "圖示應位於文字前方：" + buttonSelector);
    }

    /* ---------- Entra ID 登入模擬 ---------- */

    /** 以 route 攔截模擬「已啟用 Entra ID 且已登入」：不需真的 Azure 設定即可驗證前端行為 */
    @Given("模擬已啟用 Entra ID 登入且登入者為 {string}")
    public void mockEntraSignedIn(String name) {
        ctx.route("**/api/entra-config", r -> r.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(200).setContentType("application/json")
                .setBody("{\"loginPath\":\"/api/entra/login\"}")));
        // me 端點會帶 ?page=… 記錄使用頁面，glob 需以 * 收尾才攔得到
        ctx.route("**/api/entra/me*", r -> r.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(200).setContentType("application/json")
                .setBody("{\"displayName\":\"" + name + "\",\"username\":\"" + name + "@test.local\"}")));
        ctx.route("**/api/entra/photo", r -> r.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(404)));
    }

    /* ---------- Entra ID 時區模擬（後台維護頁名牌的時差徽章） ---------- */

    /** 模擬的組織目錄使用者：姓名 → Graph 使用者 id；id → [Windows 時區名稱, IANA 識別碼] */
    private final Map<String, String> entraTzUserIds = new HashMap<>();
    private final Map<String, String[]> entraTzByUserId = new HashMap<>();

    /** 以 route 攔截模擬「組織目錄查得到此人（含 Graph 使用者 id）且其信箱時區為指定值」：
     *  名牌的時差徽章需先通過組織檢查（/users 回傳 id）再查時區（/timezone） */
    @Given("模擬組織目錄中 {string} 的信箱時區為 {string}，IANA 為 {string}")
    public void mockEntraUserTimeZone(String name, String windowsTz, String iana) {
        if (entraTzUserIds.isEmpty()) {
            // 首次呼叫才掛 route。人名建議回空陣列，避免建議下拉干擾名牌輸入流程
            ctx.route("**/api/entra/suggest*", r -> r.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                    .setStatus(200).setContentType("application/json").setBody("[]")));
            // 組織目錄精確查詢：回傳含 Graph 使用者 id 的單筆結果（前端需以 id 查時區）
            ctx.route("**/api/entra/users*", r -> {
                String q = java.net.URLDecoder.decode(
                        r.request().url().replaceAll(".*[?&]name=([^&]*).*", "$1"),
                        java.nio.charset.StandardCharsets.UTF_8);
                String id = entraTzUserIds.get(q);
                r.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                        .setStatus(200).setContentType("application/json")
                        .setBody(id == null ? "[]"
                                : "[{\"id\":\"" + id + "\",\"displayName\":\"" + q + "\",\"mail\":\"" + id + "@test.local\"}]"));
            });
            // 信箱時區查詢：依 userId 回傳 Windows 時區名稱與對應的 IANA 識別碼
            ctx.route("**/api/entra/timezone*", r -> {
                String uid = r.request().url().replaceAll(".*[?&]userId=([^&]*).*", "$1");
                String[] tz = entraTzByUserId.get(uid);
                if (tz == null) {
                    r.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(204));
                } else {
                    r.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                            .setStatus(200).setContentType("application/json")
                            .setBody("{\"timeZone\":\"" + tz[0] + "\",\"iana\":\"" + tz[1] + "\"}"));
                }
            });
        }
        String id = "tz-user-" + (entraTzUserIds.size() + 1);
        entraTzUserIds.put(name, id);
        entraTzByUserId.put(id, new String[]{windowsTz, iana});
    }

    /** 依姓名取得受調查人員名牌 */
    private com.microsoft.playwright.Locator participantTag(String name) {
        return page.locator("#pTags .ptag")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(name));
    }

    @Then("受調查人員名牌 {string} 應顯示時差徽章 {string}")
    public void tagShowsTzBadge(String name, String badgeText) {
        var badge = participantTag(name).locator(".tz");
        assertThat(badge).isVisible();
        assertThat(badge).containsText(badgeText);
    }

    @Then("受調查人員名牌 {string} 的時差徽章滑上說明應包含 {string}")
    public void tzBadgeTitleContains(String name, String expected) {
        var badge = participantTag(name).locator(".tz");
        badge.hover();   // 完整時區資訊放在 title（原生 tooltip），滑上後驗證其內容
        String title = badge.getAttribute("title");
        org.junit.jupiter.api.Assertions.assertTrue(title != null && title.contains(expected),
                "時差徽章滑上說明應包含「" + expected + "」，實際為：" + title);
    }

    @Then("受調查人員名牌 {string} 不應顯示時差徽章")
    public void tagNoTzBadge(String name) {
        var tag = participantTag(name);
        // 先等組織檢查完成（檢查中名牌會顯示「查詢組織中」），再確認沒有時差徽章
        assertThat(tag).not().containsText("查詢組織中");
        assertThat(tag.locator(".tz")).hasCount(0);
    }

    @Then("姓名應自動帶入 {string} 且不開放自行選擇")
    public void whoAutoFilledAndLocked(String name) {
        assertThat(page.locator("#whoTriggerText")).hasText(name);
        assertThat(page.locator("#whoTrigger")).isDisabled();
        assertThat(page.locator("#whoMenu")).isHidden();
    }

    @Then("姓名提示應顯示登入帶入後的填寫說明")
    public void whoHintShowsEntraAutofillGuidance() {
        assertThat(page.locator("#whoHint")).isVisible();
        assertThat(page.locator("#whoHint")).containsText("已用登入身分帶入姓名");
        assertThat(page.locator("#whoHint")).containsText("標記可出席的時間");
        assertThat(page.locator("#whoHint .who-help-chip")).hasCount(4);
        assertThat(page.locator("#whoHint")).containsText("快速選整天");
        assertThat(page.locator("#whoHint")).containsText("上午 / 下午");
        assertThat(page.locator("#whoHint")).containsText("小時勾勾");
        assertThat(page.locator("#whoHint")).containsText("30 分鐘格");
    }

    @Then("應顯示非邀請對象的提示")
    public void notInviteeAlertShown() {
        assertThat(page.locator("#whoAlert")).isVisible();
        assertThat(page.locator("#whoAlert")).containsText("不是本次調查的邀請對象");
        assertThat(page.locator("#whoTrigger")).isDisabled();
    }

    /* ---------- Entra ID 行事曆模擬 ---------- */

    /** 以 route 攔截模擬 /api/entra/calendar 回傳指定會議：欄位格式比照 Microsoft Graph
     *  calendarView 的真實回應（台北時區、7 位小數秒的 dateTime、巢狀的 organizer），
     *  才能驗證前端解析與時段覆蓋的完整流程。表格時間形如 "2026-06-15T09:00" */
    @Given("模擬行事曆包含以下會議：")
    public void mockCalendarEvents(io.cucumber.datatable.DataTable table) {
        StringBuilder sb = new StringBuilder("[");
        for (Map<String, String> row : table.asMaps()) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"subject\":\"").append(row.get("會議名稱")).append("\",")
              .append("\"organizer\":{\"emailAddress\":{\"name\":\"").append(row.get("邀請人"))
              .append("\",\"address\":\"meet@test.local\"}},")
              .append("\"start\":{\"dateTime\":\"").append(row.get("開始"))
              .append(":00.0000000\",\"timeZone\":\"Taipei Standard Time\"},")
              .append("\"end\":{\"dateTime\":\"").append(row.get("結束"))
              .append(":00.0000000\",\"timeZone\":\"Taipei Standard Time\"},")
              .append("\"isAllDay\":false}");
        }
        mockCalendarBody(sb.append(']').toString());
    }

    @Given("模擬行事曆沒有任何會議")
    public void mockCalendarEmpty() {
        mockCalendarBody("[]");
    }

    private void mockCalendarBody(String json) {
        ctx.route("**/api/entra/calendar**", r -> r.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(200).setContentType("application/json").setBody(json)));
    }

    @When("按下同意讀取我的行事曆")
    public void clickCalendarButton() {
        page.click("#btnCal");
    }

    @Then("帶入行事曆按鈕應顯示 {string}")
    public void calendarButtonShows(String text) {
        assertThat(page.locator("#btnCal")).containsText(text);
    }

    @Then("時段 {string} 應顯示為忙碌")
    public void slotBusy(String slot) {
        assertThat(page.locator(".slot.busy[data-slot='" + slot + "']")).isVisible();
    }

    @Then("時段 {string} 不應顯示為忙碌")
    public void slotNotBusy(String slot) {
        assertThat(page.locator(".slot[data-slot='" + slot + "']")).isVisible();
        assertThat(page.locator(".slot.busy[data-slot='" + slot + "']")).hasCount(0);
    }

    @Then("時段 {string} 應顯示會議數量角標 {string}")
    public void slotBusyBadge(String slot, String count) {
        assertThat(page.locator(".slot[data-slot='" + slot + "'] .bzn")).hasText(count);
    }

    /** 2 個以上會議的時段以再深一階的灰色呈現（multi 樣式） */
    @Then("時段 {string} 應以多會議的較深底色呈現")
    public void slotMultiBusy(String slot) {
        assertThat(page.locator(".slot.busy.multi[data-slot='" + slot + "']")).isVisible();
    }

    @Then("時段 {string} 不應以多會議的較深底色呈現")
    public void slotNotMultiBusy(String slot) {
        assertThat(page.locator(".slot.busy[data-slot='" + slot + "']")).isVisible();
        assertThat(page.locator(".slot.busy.multi[data-slot='" + slot + "']")).hasCount(0);
    }

    /** 數量角標改置於時段右緣：驗證整個角標都落在時段框內，不再與小時外框的勾勾重疊 */
    @Then("時段 {string} 的會議數量角標應完整位於時段框內")
    public void badgeInsideSlot(String slot) {
        var s = page.locator(".slot[data-slot='" + slot + "']").boundingBox();
        var b = page.locator(".slot[data-slot='" + slot + "'] .bzn").boundingBox();
        org.junit.jupiter.api.Assertions.assertTrue(
                b.x >= s.x && b.x + b.width <= s.x + s.width + 0.5 &&
                b.y >= s.y && b.y + b.height <= s.y + s.height + 0.5,
                "數量角標應完整位於時段框內：slot=" + s.x + "," + s.y + "," + s.width + "," + s.height +
                        " badge=" + b.x + "," + b.y + "," + b.width + "," + b.height);
    }

    /** 忙碌時段（格內兩行會議名稱）不應撐大格子：與一般時段等高 */
    @Then("時段 {string} 的框高應與時段 {string} 相同")
    public void slotHeightsEqual(String slotA, String slotB) {
        double a = page.locator(".slot[data-slot='" + slotA + "']").boundingBox().height;
        double b = page.locator(".slot[data-slot='" + slotB + "']").boundingBox().height;
        org.junit.jupiter.api.Assertions.assertEquals(b, a, 1.0,
                "忙碌時段不應撐大格子：" + slotA + "=" + a + "px、" + slotB + "=" + b + "px");
    }

    /** 忙碌時段（格內會議名稱）不應撐寬欄位：與其它小時欄的時段等寬（需跨小時欄比較，
     *  同一小時框內的上下兩格必然等寬，比較不出撐寬問題） */
    @Then("時段 {string} 的框寬應與時段 {string} 相同")
    public void slotWidthsEqual(String slotA, String slotB) {
        double a = page.locator(".slot[data-slot='" + slotA + "']").boundingBox().width;
        double b = page.locator(".slot[data-slot='" + slotB + "']").boundingBox().width;
        org.junit.jupiter.api.Assertions.assertEquals(b, a, 1.0,
                "忙碌時段不應撐寬欄位：" + slotA + "=" + a + "px、" + slotB + "=" + b + "px");
    }

    /** 忙碌時段格內改列會議名稱（前 8 字、超過加 …，最多 2 行），不再顯示時段時間。
     *  expected 為逗號分隔的各行文字，依序比對 */
    @Then("時段 {string} 格內應依序顯示會議名稱 {string} 而非時段時間")
    public void slotShowsMeetingNames(String slot, String expected) {
        assertThat(page.locator(".slot[data-slot='" + slot + "'] .bsub")).hasText(expected.split(","));
        String time = slot.substring(slot.indexOf('T') + 1);   // 如 "09:00"
        org.junit.jupiter.api.Assertions.assertFalse(
                page.locator(".slot[data-slot='" + slot + "']").innerText().contains(time),
                "已有會議的時段不應再顯示時段時間 " + time);
    }

    @Then("時段 {string} 不應顯示會議數量角標")
    public void slotNoBusyBadge(String slot) {
        assertThat(page.locator(".slot[data-slot='" + slot + "'] .bzn")).hasCount(0);
    }

    @When("滑鼠移到時段 {string}")
    public void hoverSlot(String slot) {
        page.hover(".slot[data-slot='" + slot + "']");
    }

    @Then("行事曆提示應顯示 {string}")
    public void calendarTipShows(String text) {
        assertThat(page.locator("#calTip")).isVisible();
        assertThat(page.locator("#calTip")).containsText(text);
    }

    /** 忙碌樣式僅供對照，不影響勾選：時段可同時帶有 busy 與 on 兩種狀態 */
    @Then("時段 {string} 應同時為忙碌且選取狀態")
    public void slotBusyAndSelected(String slot) {
        assertThat(page.locator(".slot.busy.on[data-slot='" + slot + "']")).isVisible();
    }

    /* ---------- 後台維護頁 ---------- */

    @When("開啟後台維護頁")
    public void openAdminPage() {
        page.navigate(base() + "/");
        page.locator("#surveyList").waitFor();
    }

    @When("切換語言為英文")
    public void switchLanguageToEnglish() {
        page.locator(".i18n-switch").waitFor();
        page.locator(".i18n-switch").click();
        page.locator(".i18n-option[data-lang='en']").click();
        page.waitForFunction("() => document.documentElement.lang === 'en'");
        page.locator("#surveyList").waitFor();
    }

    @Then("後台維護頁應顯示英文介面")
    public void adminPageShowsEnglishUi() {
        assertThat(page.locator("header h1")).hasText("⏰ Time Survey - Admin");
        assertThat(page.locator("#saveBtn")).hasText("Create survey");
        assertThat(page.locator("#surveyList")).containsText("No surveys yet. Create one above.");
    }

    @When("以首次使用者身分開啟後台維護頁")
    public void openAdminPageFirstTime() {
        freshContext();   // 沒有任何「已看過引導」紀錄
        page = ctx.newPage();
        page.navigate(base() + "/");
        page.locator("#surveyList").waitFor();
    }

    @Then("應顯示會議時間的新手引導")
    public void meetOnboardingShown() {
        assertThat(page.locator("#meetOnbPop")).isVisible();
        assertThat(page.locator("#meetOnbMask")).isVisible();
    }

    @Then("會議時間的新手引導應消失")
    public void meetOnboardingGone() {
        assertThat(page.locator("#meetOnbPop")).isHidden();
        assertThat(page.locator("#meetOnbMask")).isHidden();
    }

    @Then("應顯示複製連結的新手引導")
    public void linkOnboardingShown() {
        assertThat(page.locator("#linkOnbPop")).isVisible();
        assertThat(page.locator("#linkOnbMask")).isVisible();
        assertThat(page.locator(".btn-ic.copy.onb-spot")).isVisible();
    }

    @Then("複製連結引導泡泡應對準被打亮的按鈕")
    public void linkOnboardingPopAligned() {
        var btnBox = page.locator(".btn-ic.copy.onb-spot").boundingBox();
        var popBox = page.locator("#linkOnbPop").boundingBox();
        double btnCx = btnBox.x + btnBox.width / 2;
        // 泡泡的水平範圍必須涵蓋按鈕中心（箭頭才指得到按鈕），否則就是定位歪掉
        org.junit.jupiter.api.Assertions.assertTrue(
                btnCx >= popBox.x && btnCx <= popBox.x + popBox.width,
                "泡泡應對準按鈕：按鈕中心 x=" + btnCx + "，泡泡範圍 " + popBox.x + " ~ " + (popBox.x + popBox.width));
    }

    @Then("複製連結的新手引導應消失")
    public void linkOnboardingGone() {
        assertThat(page.locator("#linkOnbPop")).isHidden();
        assertThat(page.locator("#linkOnbMask")).isHidden();
    }

    @When("點擊被打亮的複製連結按鈕")
    public void clickSpotCopyButton() {
        page.click(".btn-ic.copy.onb-spot");
    }

    @When("點擊重新顯示新手引導按鈕")
    public void clickResetOnboarding() {
        page.click("#resetOnbBtn");
    }

    @When("開啟調查日期範圍日曆")
    public void openDateRangeCalendar() {
        page.click("#calTrigger");
        page.locator("#calPop").waitFor();
    }

    @Then("應顯示調查日期範圍的新手引導")
    public void dateRangeOnboardingShown() {
        assertThat(page.locator("#calOnbPop")).isVisible();
        assertThat(page.locator("#calOnbMask")).isVisible();
        // 引導進行時大日曆需抬到遮罩之上，使用者才能一邊看說明一邊操作
        assertThat(page.locator("#calPop.onb-cal-spot, #suggestCalPop.onb-cal-spot")).isVisible();
    }

    @When("點擊日期範圍引導的知道了")
    public void dismissDateRangeOnboarding() {
        page.click("#calOnbPop button");
    }

    @Then("調查日期範圍的新手引導應消失")
    public void dateRangeOnboardingGone() {
        assertThat(page.locator("#calOnbPop")).isHidden();
        assertThat(page.locator("#calOnbMask")).isHidden();
    }

    /* ---------- 大日曆：連續顯示本月＋下月、捲軸捲動 ---------- */

    @Then("大日曆應連續顯示本月與下月的日期")
    public void calendarShowsThisAndNextMonth() {
        LocalDate first = LocalDate.now().withDayOfMonth(1);                 // 本月一號
        LocalDate nextEnd = first.plusMonths(2).minusDays(1);               // 下個月月底
        // 本月一號到下個月月底都在同一份週曆中，跨月日期得以連續呈現
        assertThat(page.locator("#calDays [data-d='" + first + "']")).hasCount(1);
        assertThat(page.locator("#calDays [data-d='" + nextEnd + "']")).hasCount(1);
        // 最多兩個月：不顯示下下個月的日期
        assertThat(page.locator("#calDays [data-d='" + first.plusMonths(2) + "']")).hasCount(0);
    }

    @Then("大日曆不應提供上下月切換按鈕")
    public void calendarHasNoMonthNav() {
        assertThat(page.locator("#calPrev")).hasCount(0);
        assertThat(page.locator("#calNext")).hasCount(0);
    }

    @Then("大日曆應顯示下個月份的交界標籤")
    public void calendarShowsMonthDivider() {
        LocalDate nextMonth1st = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        // 月份交界的分隔列需標示下一個月的月份數字，讓跨月一眼可辨識
        assertThat(page.locator(".cal-month-divider")).hasText(nextMonth1st.getMonthValue() + "月");
    }

    @When("在大日曆中滾動滑鼠捲軸")
    public void wheelScrollCalendar() {
        // 開啟日曆時會自動捲到起日所在列；先捲回頂端，確保測的是「捲軸能往下捲動」本身
        page.evaluate("document.getElementById('calScroll').scrollTop = 0");
        page.hover("#calScroll");
        page.mouse().wheel(0, 200);
    }

    @Then("大日曆應向下捲動")
    public void calendarScrolledDown() {
        // mouse.wheel 不會等捲動完成，需等待 scrollTop 實際變化
        page.waitForFunction("document.getElementById('calScroll').scrollTop > 0");
    }

    @When("在後台輸入調查名稱 {string} 與人員 {string}")
    public void fillAdminForm(String name, String people) {
        page.fill("#fName", name);
        for (String p : people.split(",")) {
            page.fill("#pInput", p);
            page.keyboard().press("Enter");   // 名牌式輸入：每位按 Enter 成為一顆名牌
        }
    }

    @When("在受調查人員欄位貼上 {string}")
    public void pasteParticipants(String text) {
        // 觸發輸入框的 paste 事件（頁面攔截剪貼簿內容自行拆名，不能用 fill 模擬）
        page.evaluate("t => { const dt = new DataTransfer(); dt.setData('text', t);"
                + " document.getElementById('pInput').dispatchEvent("
                + "new ClipboardEvent('paste', { clipboardData: dt })); }", text);
    }

    @Then("受調查人員名牌應依序為 {string}")
    public void participantTagsAre(String expected) {
        String[] names = expected.split(",");
        var tags = page.locator("#pTags .ptag");
        assertThat(tags).hasCount(names.length);
        for (int i = 0; i < names.length; i++) {
            assertThat(tags.nth(i)).containsText(names[i]);
        }
    }

    @When("按下建立調查")
    public void clickCreate() {
        page.click("#saveBtn");
    }

    @When("點擊第一筆調查的查看結果")
    public void clickFirstResult() {
        page.click("#surveyList .btn-ic.result");
    }

    /* ---------- 全站統計頁 ---------- */

    @When("開啟全站統計頁")
    public void openStatsPage() {
        page.navigate(base() + "/stats");
        page.locator("#statTable tbody tr").first().waitFor();
    }

    @Then("統計頁應列出調查 {string}：開啟 {int} 次、使用者 {int} 人、回覆 {string}")
    public void statsPageRow(String name, int visits, int users, String responded) {
        var row = page.locator("#statTable tbody tr")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(name));
        assertThat(row).isVisible();
        var cells = row.locator("td");
        assertThat(cells.nth(3)).hasText(String.valueOf(visits));
        assertThat(cells.nth(4)).hasText(String.valueOf(users));
        assertThat(cells.nth(5)).hasText(responded);
    }

    @Then("調查清單應包含 {string}")
    public void listContains(String name) {
        assertThat(page.locator("#surveyList")).containsText(name);
    }

    @Then("調查清單第一筆應捲動至可視範圍內")
    public void firstRowScrolledIntoView() {
        assertThat(page.locator("#surveyList tr").first()).isInViewport();
    }

    @Then("調查清單第一筆的複製連結按鈕應高亮閃爍")
    public void firstRowCopyBtnFlashes() {
        // 複合按鈕（split button）有主按鈕與小箭頭兩個 .btn-ic.copy，高亮閃爍只套在主按鈕上
        assertThat(page.locator("#surveyList tr").first().locator(".btn-ic.copy.main"))
                .hasClass(java.util.regex.Pattern.compile(".*\\bflash-hl\\b.*"));
    }

    /* ---------- 複製連結／Teams 分享的複合按鈕（split button） ---------- */

    @Then("第一筆調查應只有複製連結按鈕且無分享方式小箭頭")
    public void firstRowCopyOnlyNoCaret() {
        var row = page.locator("#surveyList tr").first();
        assertThat(row.locator(".btn-ic.copy.main")).isVisible();
        assertThat(row.locator(".btn-ic.copy.caret")).hasCount(0);
        assertThat(row.locator(".sharemenu")).hasCount(0);
    }

    @When("點擊第一筆調查的分享方式小箭頭")
    public void clickFirstRowShareCaret() {
        page.locator("#surveyList tr").first().locator(".btn-ic.copy.caret").click();
    }

    @Then("應顯示分享方式選單")
    public void shareMenuShown() {
        assertThat(page.locator(".sharemenu.open")).isVisible();
    }

    @Then("分享方式選單應包含 {string} 與 {string} 選項")
    public void shareMenuHasOptions(String a, String b) {
        var menu = page.locator(".sharemenu.open");
        assertThat(menu).containsText(a);
        assertThat(menu).containsText(b);
    }

    @When("點擊分享方式選單的 {string}")
    public void clickShareMenuOption(String label) {
        // 攔截 window.open：測試環境不真的開新分頁連外，只記下要開啟的網址供後續驗證
        page.evaluate("window.__openedUrl = null; window.open = u => { window.__openedUrl = String(u); return null; };");
        page.locator(".sharemenu.open button")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(label)).click();
    }

    @Then("應開啟 Teams 分享連結，內容包含調查名稱 {string}、人員 {string} 與調查連結")
    public void teamsShareLinkOpened(String surveyName, String people) {
        String url = (String) page.evaluate("window.__openedUrl");
        org.junit.jupiter.api.Assertions.assertNotNull(url, "應以 window.open 開啟 Teams 分享連結");
        org.junit.jupiter.api.Assertions.assertTrue(
                url.startsWith("https://teams.microsoft.com/l/chat/0/0?"),
                "應為 Teams 聊天深層連結：" + url);
        String decoded = java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(decoded.contains(surveyName),
                "訊息應包含調查名稱 " + surveyName + "：" + decoded);
        for (String p : people.split(",")) {
            org.junit.jupiter.api.Assertions.assertTrue(decoded.contains(p),
                    "訊息應包含受調查人員 " + p + "：" + decoded);
        }
        org.junit.jupiter.api.Assertions.assertTrue(decoded.contains("/s/"),
                "訊息應包含調查連結（/s/調查ID）：" + decoded);
    }

    /** 模擬組織目錄（/api/entra/users 精確查詢）用的「姓名 → email」對照表 */
    private final Map<String, String> entraDirectory = new HashMap<>();

    @Given("模擬組織目錄中 {string} 的 email 為 {string}")
    public void mockEntraDirectory(String name, String mail) {
        if (entraDirectory.isEmpty()) {
            // 首次呼叫才掛 route：依查詢參數 name 回傳對照表中的使用者，查無此人回空陣列
            ctx.route("**/api/entra/users*", r -> {
                String q = java.net.URLDecoder.decode(
                        r.request().url().replaceAll(".*[?&]name=([^&]*).*", "$1"),
                        java.nio.charset.StandardCharsets.UTF_8);
                String m = entraDirectory.get(q);
                r.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                        .setStatus(200).setContentType("application/json")
                        .setBody(m == null ? "[]"
                                : "[{\"displayName\":\"" + q + "\",\"mail\":\"" + m + "\"}]"));
            });
        }
        entraDirectory.put(name, mail);
    }

    @Then("應開啟 Teams 分享連結，收件人應為 {string}")
    public void teamsShareRecipients(String expected) {
        // 收件人要先向組織目錄查 email（非同步），等 window.open 真的被呼叫再驗證
        page.waitForFunction("() => window.__openedUrl");
        String url = (String) page.evaluate("window.__openedUrl");
        String users = java.net.URLDecoder.decode(
                url.replaceAll(".*[?&]users=([^&]*).*", "$1"),
                java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertEquals(expected, users,
                "Teams 收件人應為受調查人員的 email：" + url);
    }

    @When("重新整理後台維護頁")
    public void reloadAdminPage() {
        page.reload();
        page.locator("#surveyList tr").first().waitFor();
    }

    @Then("第一筆調查的分享主按鈕應為 Teams 分享模式")
    public void firstShareBtnInTeamsMode() {
        assertThat(page.locator("#surveyList tr").first().locator(".btn-ic.copy.main"))
                .hasAttribute("aria-label", "在 Teams 分享調查連結");
    }

    /** 調查清單中名稱含指定文字的那一列 */
    private com.microsoft.playwright.Locator surveyRow(String surveyName) {
        return page.locator("#surveyList tr")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(surveyName));
    }

    @Then("調查清單中 {string} 應顯示共同時段徽章 {string}")
    public void listShowsSlotBadge(String surveyName, String badgeText) {
        var row = surveyRow(surveyName);
        // 共同時段徽章位於「填寫狀況」欄位（.prog）內，說明文字放在 data-tip（滑鼠移上才顯示），故檢查該屬性內容
        assertDataTipContains(row.locator(".prog .slotbadge"), badgeText);
    }

    @Then("調查清單中 {string} 不應顯示共同時段徽章")
    public void listShowsNoSlotBadge(String surveyName) {
        var row = surveyRow(surveyName);
        // 只有「無共同時段」（⚠️）才顯示徽章，其餘狀況（有共同時段／尚未確定）不顯示任何符號
        assertThat(row.locator(".prog .slotbadge")).hasCount(0);
    }

    @When("點擊調查 {string} 的編輯")
    public void clickEditForSurvey(String surveyName) {
        var row = surveyRow(surveyName);
        row.locator(".btn-ic.edit").click();
    }

    @Then("日期範圍欄位應顯示挖空天數徽章 {string}")
    public void dateRangeShowsExcludedBadge(String tipContains) {
        // 挖空天數只顯示符號，說明文字放在 data-tip（滑鼠移上才顯示），故檢查該屬性內容
        assertDataTipContains(page.locator("#calTriggerText .skipbadge"), tipContains);
    }

    @Then("日期範圍挖空徽章應使用粉紅底即時提示")
    public void dateRangeSkipBadgeUsesSharedStyle() {
        assertSharedSkipBadgeStyle(page.locator("#calTriggerText .skipbadge"));
    }

    @Then("調查清單中 {string} 的日期範圍應顯示挖空天數徽章 {string}")
    public void listDateRangeShowsExcludedBadge(String surveyName, String tipContains) {
        var row = surveyRow(surveyName);
        // 挖空天數只顯示符號，說明文字放在 data-tip（滑鼠移上才顯示），故檢查該屬性內容
        assertDataTipContains(row.locator(".skipbadge"), tipContains);
    }

    @Then("調查清單中 {string} 的日期範圍挖空徽章應使用粉紅底即時提示")
    public void listDateRangeSkipBadgeUsesSharedStyle(String surveyName) {
        var row = surveyRow(surveyName);
        assertSharedSkipBadgeStyle(row.locator(".skipbadge"));
    }

    /**
     * 檢查元素的 data-tip 屬性是否包含指定文字。
     * 不用 Playwright 的 Pattern 版 hasAttribute：Java 的 Pattern.quote 會產生 \Q..\E，
     * 但 Playwright 是把 regex 轉譯到瀏覽器端以 JS RegExp 執行，JS 不支援 \Q..\E，
     * 導致條件恆為不符。改為等待元素出現後直接讀取屬性做子字串比對。
     */
    private void assertDataTipContains(com.microsoft.playwright.Locator locator, String expectedSubstring) {
        assertThat(locator).isVisible();
        String tip = locator.getAttribute("data-tip");
        org.junit.jupiter.api.Assertions.assertTrue(tip != null && tip.contains(expectedSubstring),
                "data-tip 應包含「" + expectedSubstring + "」，實際為：" + tip);
    }

    private void assertSharedSkipBadgeStyle(com.microsoft.playwright.Locator locator) {
        locator = locator.first();
        assertThat(locator).isVisible();

        String tip = locator.getAttribute("data-tip");
        org.junit.jupiter.api.Assertions.assertTrue(tip != null && !tip.isBlank(), "挖空徽章應使用 data-tip 自製提示");
        String title = locator.getAttribute("title");
        org.junit.jupiter.api.Assertions.assertTrue(title == null || title.isBlank(),
                "挖空徽章不應使用原生 title，避免提示延遲，實際為：" + title);

        String bg = (String) locator.evaluate("el => getComputedStyle(el).backgroundColor");
        org.junit.jupiter.api.Assertions.assertEquals("rgb(251, 233, 231)", bg, "挖空徽章應使用共用粉紅底色");

        locator.hover();
        String visibility = (String) locator.evaluate("el => getComputedStyle(el, '::after').visibility");
        org.junit.jupiter.api.Assertions.assertEquals("visible", visibility, "hover 後自製 tooltip 應立即顯示");
    }

    @Then("意見回饋按鈕不應顯示")
    public void feedbackButtonHidden() {
        assertThat(page.locator("#feedbackBtn")).isHidden();
    }

    @Then("結論區應顯示會通按鈕")
    public void conclusionHasMeetingButtons() {
        assertThat(page.locator("#resultArea .cbtn").first()).isVisible();
        assertThat(page.locator("#resultArea")).containsText("📅");
    }

    @When("設定會議時間為 {string} 小時")
    public void setMeetingHours(String hours) {
        page.fill("#meetHours", hours);   // oninput 會即時重算結論區
    }

    @Then("結論區應顯示可開會時段 {string}")
    public void fitChipShown(String range) {
        // 新版以可拖曳時間軸呈現，.tb-lab 顯示目前選定的時段（預設為最早可行時段）
        assertThat(page.locator("#resultArea .tb-lab")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(range))
                .first()).isVisible();
    }

    @Then("結論區應顯示長度不足時段 {string}")
    public void unfitChipShown(String range) {
        assertThat(page.locator("#resultArea .chip:not(.fit)")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(range))
                .first()).isVisible();
    }

    @Then("結論區應顯示 {int} 個可開會時段")
    public void fitChipCount(int n) {
        // 每個可容納會議的連續區塊呈現為一條可拖曳時間軸
        assertThat(page.locator("#resultArea .tb-track")).hasCount(n);
    }

    @Then("結論區不應顯示可開會時段")
    public void noFitChip() {
        assertThat(page.locator("#resultArea .tb-track")).hasCount(0);
    }

    @Then("結論區應顯示部分可參與時段 {string}")
    public void partialChipShown(String range) {
        assertThat(page.locator("#resultArea .chip.partial")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(range))
                .first()).isVisible();
    }

    @Then("部分可參與時段 {string} 應顯示可參與 {string} 與無法參與 {string}")
    public void partialChipShowsNames(String range, String canJoin, String cannotJoin) {
        var chip = page.locator("#resultArea .chip.partial")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(range))
                .first();
        assertThat(chip.locator(".ptip .ok")).containsText(canJoin);
        assertThat(chip.locator(".ptip .miss")).containsText(cannotJoin);
    }

    @When("展開調查明細")
    public void expandDetail() {
        page.click("#detailToggle");
        assertThat(page.locator("#detailWrap")).isVisible();
    }

    @Then("調查明細應顯示人員 {string}")
    public void detailShowsParticipants(String people) {
        for (String p : people.split(",")) {
            assertThat(page.locator("#detailWrap table thead")).containsText(p);
        }
    }
}
