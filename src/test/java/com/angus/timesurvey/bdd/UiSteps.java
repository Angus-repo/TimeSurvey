package com.angus.timesurvey.bdd;

import com.angus.timesurvey.model.Survey;
import com.angus.timesurvey.model.SurveyResponse;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
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
        ctx.addInitScript("localStorage.setItem('ownerToken', '" + OWNER + "')");
        page = ctx.newPage();
    }

    /* ---------- UI 測試截圖與 PDF 報告 ---------- */

    private record Shot(String title, boolean failed, byte[] png) {}
    private static final List<Shot> shots = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 每個 @ui 場景結束：在頁面頂端壓上場景名稱與結果的橫幅後截圖（瀏覽器渲染中文，PDF 端不需字型） */
    @After("@ui")
    public void captureAndClose(io.cucumber.java.Scenario scenario) {
        try {
            if (page != null) {
                String title = "UI 測試場景：" + scenario.getName() +
                        "　［" + (scenario.isFailed() ? "✘ 失敗" : "✔ 通過") + "］";
                String time = "截圖時間:" + LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String color = scenario.isFailed() ? "#c0392b" : "#1d7a35";
                page.evaluate("([title, time]) => { const b = document.createElement('div');" +
                        "b.style.cssText = 'display:flex;justify-content:space-between;align-items:center;" +
                        "background:" + color + ";color:#fff;font:bold 15px sans-serif;padding:10px 16px;';" +
                        "const l = document.createElement('span'); l.textContent = title;" +
                        "const r = document.createElement('span'); r.textContent = time; r.style.fontWeight = '400';" +
                        "b.append(l, r); document.body.prepend(b); }",
                        Arrays.asList(title, time));
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

    /** 將本次所有 UI 截圖彙整成 target/ui-test-report.pdf，一張截圖一頁 */
    private static void writePdfReport() {
        if (shots.isEmpty()) return;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (Shot s : shots) {
                var img = org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
                        .createFromByteArray(doc, s.png(), s.title());
                float pageW = 595f;   // A4 寬，高度依截圖比例
                float pageH = pageW * img.getHeight() / img.getWidth();
                var pg = new org.apache.pdfbox.pdmodel.PDPage(
                        new org.apache.pdfbox.pdmodel.common.PDRectangle(pageW, pageH));
                doc.addPage(pg);
                try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, pg)) {
                    cs.drawImage(img, 0, 0, pageW, pageH);
                }
            }
            doc.save("target/ui-test-report.pdf");
            System.out.println("UI 測試截圖報告：target/ui-test-report.pdf（共 " + shots.size() + " 頁）");
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

    /* ---------- 調查填寫頁 ---------- */

    @When("開啟調查 {string} 的填寫頁")
    public void openSurveyPage(String surveyName) {
        page.navigate(base() + "/s/" + surveyIds.get(surveyName));
        page.locator(".slot").first().waitFor();   // 等格線渲染完成
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

    @When("在後台輸入調查名稱 {string} 與人員 {string}")
    public void fillAdminForm(String name, String people) {
        page.fill("#fName", name);
        page.fill("#fParticipants", String.join("\n", people.split(",")));
    }

    @When("按下建立調查")
    public void clickCreate() {
        page.click("#saveBtn");
    }

    @When("點擊第一筆調查的查看結果")
    public void clickFirstResult() {
        page.click("#surveyList .btn-ic.result");
    }

    @Then("調查清單應包含 {string}")
    public void listContains(String name) {
        assertThat(page.locator("#surveyList")).containsText(name);
    }

    @Then("結論區應顯示會通按鈕")
    public void conclusionHasMeetingButtons() {
        assertThat(page.locator("#resultArea .cbtn").first()).isVisible();
        assertThat(page.locator("#resultArea")).containsText("📅");
        assertThat(page.locator("#resultArea")).containsText("🌐");
    }
}
