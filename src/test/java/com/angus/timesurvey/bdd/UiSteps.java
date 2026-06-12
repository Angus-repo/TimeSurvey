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
                "localStorage.setItem('meetHoursOnboarded', '1');" +
                "localStorage.setItem('copyLinkOnboarded', '1');");
        page = ctx.newPage();
    }

    /* ---------- UI 測試截圖與 PDF 報告 ---------- */

    private record Shot(String title, boolean failed, byte[] png) {}
    private static final List<Shot> shots = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 每個 test case 一個專屬顏色：頁面標題橫幅與 PDF 頂端色條同色，跨頁時靠顏色快速辨識同一案例 */
    private static final java.awt.Color[] CASE_COLORS = {
            new java.awt.Color(0x1d7a35), new java.awt.Color(0x2c7be5), new java.awt.Color(0x6c5ce7),
            new java.awt.Color(0xe67e22), new java.awt.Color(0x16a085), new java.awt.Color(0xc2185b),
            new java.awt.Color(0x34495e), new java.awt.Color(0x795548)
    };
    private static final java.awt.Color FAIL_COLOR = new java.awt.Color(0xc0392b);

    private static java.awt.Color caseColor(int caseNo, boolean failed) {
        return failed ? FAIL_COLOR : CASE_COLORS[(caseNo - 1) % CASE_COLORS.length];
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
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        writePdfReport();
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
            for (int i = 0; i < shots.size(); i++) {
                Shot s = shots.get(i);
                int caseNo = i + 1;
                java.awt.Color color = caseColor(caseNo, s.failed());
                var full = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(s.png()));
                // 每頁可容納的截圖高度（像素），以 A4 寬度等比換算
                int chunkPx = Math.max(1, (int) Math.floor(full.getWidth() * (imgAreaH / pageW)));
                int total = Math.max(1, (int) Math.ceil(full.getHeight() / (double) chunkPx));
                for (int p = 0; p < total; p++) {
                    int y = p * chunkPx;
                    int h = Math.min(chunkPx, full.getHeight() - y);
                    var part = full.getSubimage(0, y, full.getWidth(), h);
                    var img = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
                            .createFromImage(doc, part);
                    var pg = new org.apache.pdfbox.pdmodel.PDPage(
                            org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
                    doc.addPage(pg);
                    pdfPages++;
                    try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, pg)) {
                        // 頂端色條：同一 case 的每一頁同色
                        cs.setNonStrokingColor(color);
                        cs.addRect(0, pageH - barH, pageW, barH);
                        cs.fill();
                        cs.setNonStrokingColor(java.awt.Color.WHITE);
                        cs.beginText();
                        cs.setFont(font, 12);
                        cs.newLineAtOffset(16, pageH - barH + 8);
                        // 單頁不顯示頁次；切成多頁時才標示 (頁次/總頁數)
                        cs.showText("Case " + caseNo + (s.failed() ? "  [FAILED]" : "") +
                                (total > 1 ? "   (" + (p + 1) + "/" + total + ")" : ""));
                        cs.endText();
                        float drawH = h * (pageW / full.getWidth());
                        cs.drawImage(img, 0, pageH - barH - drawH, pageW, drawH);
                    }
                }
            }
            doc.save("target/ui-test-report.pdf");
            System.out.println("UI 測試截圖報告：target/ui-test-report.pdf（" +
                    shots.size() + " 個場景，共 " + pdfPages + " 頁）");
        } catch (Exception e) {
            System.err.println("UI 測試 PDF 報告產生失敗：" + e);
        }
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

    @Given("調查 {string} 已有 {string} 的填寫紀錄 {string}")
    public void responseExists(String surveyName, String person, String slots) {
        SurveyResponse r = new SurveyResponse();
        r.setSurveyId(surveyIds.get(surveyName));
        r.setParticipantName(person);
        r.setSlots(slots);
        r.setUpdatedAt(LocalDateTime.now());
        responseRepo.save(r);
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
        assertThat(page.locator("#onbPop")).isHidden();
        assertThat(page.locator("#onbMask")).isHidden();
    }

    @When("選擇姓名 {string}")
    public void chooseName(String name) {
        page.selectOption("#who", name);
    }

    @When("點擊時段 {string}")
    public void clickSlot(String slot) {
        page.click(".slot[data-slot='" + slot + "']");
    }

    @When("點擊 {string} 的 {string} 時勾勾")
    public void clickHourCheck(String date, String hour) {
        page.click(".hbox[data-date='" + date + "'][data-hour='" + hour + "'] .chk");
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

    @Then("應出現提示 {string}")
    public void toastShows(String message) {
        assertThat(page.locator("#toast")).containsText(message);
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
        assertThat(page.locator("#resultArea .chip.fit")
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
        assertThat(page.locator("#resultArea .chip.fit")).hasCount(n);
    }

    @Then("結論區不應顯示可開會時段")
    public void noFitChip() {
        assertThat(page.locator("#resultArea .chip.fit")).hasCount(0);
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
