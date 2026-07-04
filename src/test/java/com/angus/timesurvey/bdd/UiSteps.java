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

    @Before("@ui")
    public void openBrowserContext() {
        ctx = sharedBrowser().newContext();
        // 預設視為已看過新手引導，避免遮罩擋住一般場景；引導本身由專屬場景測試
        ctx.addInitScript("localStorage.setItem('ownerToken', '" + OWNER + "');" +
                "localStorage.setItem('surveyOnboarded', '1');" +
                "localStorage.setItem('surveyGridOnboarded', '1');" +
                "localStorage.setItem('meetHoursOnboarded', '1');" +
                "localStorage.setItem('dragSlotOnboarded', '1');" +
                "localStorage.setItem('dateRangeOnboarded', '1');" +
                "localStorage.setItem('copyLinkOnboarded', '1');");
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
                        "b.append(n, t, s, c); document.body.prepend(b); }",
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
            try (BrowserContext c = browser.newContext()) {
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
        Survey s = new Survey();
        s.setId(UUID.randomUUID().toString());
        s.setName(name);
        s.setStartDate(LocalDate.parse(startDate));
        s.setEndDate(LocalDate.parse(endDate));
        s.setStartTime(LocalTime.parse(startTime));
        s.setEndTime(LocalTime.parse(endTime));
        s.setParticipants(new ArrayList<>(Arrays.asList(people.split(","))));
        List<LocalDate> ex = new ArrayList<>();
        if (!excluded.isBlank()) {
            for (String d : excluded.split(",")) ex.add(LocalDate.parse(d.trim()));
        }
        s.setExcludedDates(ex);
        s.setOwnerToken(OWNER);
        s.setCreatedAt(LocalDateTime.now());
        surveyRepo.save(s);
        surveyIds.put(name, s.getId());
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
        // 換一個沒有「已看過引導」紀錄的全新瀏覽器環境（init script 每次載頁都會執行，無法事後移除）
        ctx.close();
        ctx = sharedBrowser().newContext();
        ctx.addInitScript("localStorage.setItem('ownerToken', '" + OWNER + "')");
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
        page.click("#bar button");
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
        page.click("#btnInvite");
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

    /* ---------- 後台維護頁 ---------- */

    @When("開啟後台維護頁")
    public void openAdminPage() {
        page.navigate(base() + "/");
        page.locator("#surveyList").waitFor();
    }

    @When("以首次使用者身分開啟後台維護頁")
    public void openAdminPageFirstTime() {
        // 換一個沒有「已看過引導」紀錄的全新瀏覽器環境
        ctx.close();
        ctx = sharedBrowser().newContext();
        ctx.addInitScript("localStorage.setItem('ownerToken', '" + OWNER + "')");
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
        assertThat(page.locator("#calPop.onb-cal-spot")).isVisible();
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

    @Then("調查清單中 {string} 應顯示共同時段徽章 {string}")
    public void listShowsSlotBadge(String surveyName, String badgeText) {
        var row = page.locator("#surveyList tr")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(surveyName));
        // 共同時段徽章位於「填寫狀況」欄位（.prog）內，說明文字放在 data-tip（滑鼠移上才顯示），故檢查該屬性內容
        assertDataTipContains(row.locator(".prog .slotbadge"), badgeText);
    }

    @Then("調查清單中 {string} 不應顯示共同時段徽章")
    public void listShowsNoSlotBadge(String surveyName) {
        var row = page.locator("#surveyList tr")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(surveyName));
        // 只有「無共同時段」（⚠️）才顯示徽章，其餘狀況（有共同時段／尚未確定）不顯示任何符號
        assertThat(row.locator(".prog .slotbadge")).hasCount(0);
    }

    @When("點擊調查 {string} 的編輯")
    public void clickEditForSurvey(String surveyName) {
        var row = page.locator("#surveyList tr")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(surveyName));
        row.locator(".btn-ic.edit").click();
    }

    @Then("日期範圍欄位應顯示挖空天數徽章 {string}")
    public void dateRangeShowsExcludedBadge(String tipContains) {
        // 挖空天數只顯示符號，說明文字放在 data-tip（滑鼠移上才顯示），故檢查該屬性內容
        assertDataTipContains(page.locator("#calTriggerText .slotbadge"), tipContains);
    }

    @Then("調查清單中 {string} 的日期範圍應顯示挖空天數徽章 {string}")
    public void listDateRangeShowsExcludedBadge(String surveyName, String tipContains) {
        var row = page.locator("#surveyList tr")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(surveyName));
        // 挖空天數只顯示符號，說明文字放在 data-tip（滑鼠移上才顯示），故檢查該屬性內容
        assertDataTipContains(row.locator(".slotbadge.skipbadge"), tipContains);
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
