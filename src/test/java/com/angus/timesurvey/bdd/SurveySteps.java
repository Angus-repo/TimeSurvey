package com.angus.timesurvey.bdd;

import com.angus.timesurvey.job.HousekeepingJob;
import com.angus.timesurvey.model.Survey;
import com.angus.timesurvey.model.SurveyResponse;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

public class SurveySteps {

    @Autowired private TestRestTemplate rest;
    @Autowired private SurveyRepository surveyRepo;
    @Autowired private SurveyResponseRepository responseRepo;
    @Autowired private HousekeepingJob housekeepingJob;
    @Autowired private SurveyVisitRepository visitRepo;
    @Autowired private ObjectMapper om;
    @Value("${local.server.port}") private int port;

    /** 每個場景獨立的狀態 */
    private final Map<String, String> ownerTokens = new HashMap<>();
    private final Map<String, String> surveyIds = new HashMap<>();
    private ResponseEntity<String> last;
    private final Map<String, List<String>> wsMessages = new ConcurrentHashMap<>();
    private final List<WebSocketSession> wsSessions = new ArrayList<>();

    @Before
    public void cleanDb() {
        responseRepo.deleteAll();
        visitRepo.deleteAll();
        surveyRepo.deleteAll();
    }

    @After
    public void closeWs() {
        for (WebSocketSession s : wsSessions) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /* ---------- 共用工具 ---------- */

    private String tokenOf(String ownerName) {
        return ownerTokens.computeIfAbsent(ownerName, k -> "tk-" + UUID.randomUUID());
    }

    private HttpHeaders headers(String ownerName) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (ownerName != null) h.set("X-Owner-Token", tokenOf(ownerName));
        return h;
    }

    private String surveyId(String surveyName) {
        String id = surveyIds.get(surveyName);
        assertNotNull(id, "找不到調查「" + surveyName + "」的 id，前置步驟可能失敗");
        return id;
    }

    private JsonNode json(String body) {
        try {
            return om.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("回應不是合法 JSON：" + body, e);
        }
    }

    private boolean waitFor(BooleanSupplier cond, long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        return cond.getAsBoolean();
    }

    /* ---------- 建立 / 編輯 / 清單 ---------- */

    @When("{string} 建立調查 {string}，日期 {string} 到 {string}，時間 {string} 到 {string}，人員 {string}")
    public void createSurvey(String owner, String name, String sd, String ed, String st, String et, String participants) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("startDate", sd);
        body.put("endDate", ed);
        body.put("startTime", st);
        body.put("endTime", et);
        body.put("participants", participants.isBlank() ? List.of() : Arrays.asList(participants.split(",")));
        last = rest.exchange("/api/surveys", HttpMethod.POST, new HttpEntity<>(body, headers(owner)), String.class);
        if (last.getStatusCode().is2xxSuccessful()) {
            surveyIds.put(name, json(last.getBody()).get("id").asText());
        }
    }

    @When("以無識別碼建立調查 {string}")
    public void createWithoutOwner(String name) {
        Map<String, Object> body = Map.of(
                "name", name, "startDate", "2026-06-15", "endDate", "2026-06-15",
                "startTime", "09:00", "endTime", "12:00", "participants", List.of("甲"));
        last = rest.exchange("/api/surveys", HttpMethod.POST, new HttpEntity<>(body, headers(null)), String.class);
    }

    @When("{string} 將調查 {string} 改名為 {string}")
    public void renameSurvey(String owner, String surveyName, String newName) {
        JsonNode cur = json(rest.getForEntity("/api/surveys/" + surveyId(surveyName), String.class).getBody());
        Map<String, Object> body = new HashMap<>();
        body.put("name", newName);
        body.put("startDate", cur.get("startDate").asText());
        body.put("endDate", cur.get("endDate").asText());
        body.put("startTime", cur.get("startTime").asText());
        body.put("endTime", cur.get("endTime").asText());
        List<String> ps = new ArrayList<>();
        cur.get("participants").forEach(p -> ps.add(p.asText()));
        body.put("participants", ps);
        last = rest.exchange("/api/surveys/" + surveyId(surveyName), HttpMethod.PUT,
                new HttpEntity<>(body, headers(owner)), String.class);
        if (last.getStatusCode().is2xxSuccessful()) {
            surveyIds.put(newName, surveyId(surveyName));
        }
    }

    @Then("{string} 的調查清單應包含 {string}")
    public void listContains(String owner, String surveyName) {
        JsonNode list = json(rest.exchange("/api/surveys", HttpMethod.GET,
                new HttpEntity<>(headers(owner)), String.class).getBody());
        boolean found = false;
        for (JsonNode s : list) if (s.get("name").asText().equals(surveyName)) found = true;
        assertTrue(found, "清單中找不到調查「" + surveyName + "」");
    }

    @Then("{string} 的調查清單應不包含 {string}")
    public void listNotContains(String owner, String surveyName) {
        JsonNode list = json(rest.exchange("/api/surveys", HttpMethod.GET,
                new HttpEntity<>(headers(owner)), String.class).getBody());
        for (JsonNode s : list) {
            assertNotEquals(surveyName, s.get("name").asText(), "清單不應包含調查「" + surveyName + "」");
        }
    }

    @Then("調查 {string} 的 API 回應不應洩漏發起者識別碼")
    public void noOwnerTokenLeak(String surveyName) {
        String body = rest.getForEntity("/api/surveys/" + surveyId(surveyName), String.class).getBody();
        assertFalse(body.contains("ownerToken"), "API 回應不應包含 ownerToken 欄位：" + body);
    }

    /* ---------- 填寫回覆 ---------- */

    @When("參與者 {string} 在調查 {string} 填寫時段 {string}")
    public void submitResponse(String participant, String surveyName, String slots) {
        Map<String, String> body = Map.of("participantName", participant, "slots", slots);
        last = rest.exchange("/api/surveys/" + surveyId(surveyName) + "/responses",
                HttpMethod.POST, new HttpEntity<>(body, headers(null)), String.class);
    }

    @Then("調查 {string} 的回覆數應為 {int}")
    public void responseCount(String surveyName, int expected) {
        JsonNode list = json(rest.getForEntity("/api/surveys/" + surveyId(surveyName) + "/responses", String.class).getBody());
        assertEquals(expected, list.size());
    }

    @Then("{string} 在調查 {string} 已填的時段應為 {string}")
    public void participantSlots(String participant, String surveyName, String expectedSlots) {
        JsonNode list = json(rest.getForEntity("/api/surveys/" + surveyId(surveyName) + "/responses", String.class).getBody());
        for (JsonNode r : list) {
            if (r.get("participantName").asText().equals(participant)) {
                assertEquals(expectedSlots, r.get("slots").asText());
                return;
            }
        }
        fail("找不到參與者「" + participant + "」的回覆");
    }

    @Then("查詢調查 {string} 的結果應成功")
    public void resultsOk(String surveyName) {
        ResponseEntity<String> res = rest.getForEntity("/api/surveys/" + surveyId(surveyName) + "/responses", String.class);
        assertEquals(200, res.getStatusCode().value());
    }

    /* ---------- 結束調查 ---------- */

    @When("{string} 結束調查 {string}")
    public void closeSurvey(String owner, String surveyName) {
        last = rest.exchange("/api/surveys/" + surveyId(surveyName) + "/close",
                HttpMethod.POST, new HttpEntity<>(headers(owner)), String.class);
    }

    /* ---------- 共用斷言 ---------- */

    @Then("回應狀態碼應為 {int}")
    public void statusIs(int expected) {
        assertEquals(expected, last.getStatusCode().value(), "回應內容：" + last.getBody());
    }

    @Then("回應訊息應包含 {string}")
    public void messageContains(String expected) {
        assertNotNull(last.getBody());
        assertTrue(last.getBody().contains(expected),
                "回應應包含「" + expected + "」，實際為：" + last.getBody());
    }

    /* ---------- WebSocket 完成通知 ---------- */

    @Given("{string} 已開啟後台通知連線")
    public void openWs(String owner) throws Exception {
        List<String> inbox = wsMessages.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>());
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                inbox.add(message.getPayload());
            }
        }, "ws://localhost:" + port + "/ws/notify?owner=" + tokenOf(owner)).get(5, TimeUnit.SECONDS);
        wsSessions.add(session);
    }

    @Then("{string} 不應收到完成通知")
    public void noNotification(String owner) {
        waitFor(() -> false, 600);   // 留時間讓誤發的訊息有機會到達
        List<String> completes = wsMessages.getOrDefault(owner, List.<String>of()).stream()
                .filter(m -> m.contains("surveyComplete")).toList();
        assertEquals(0, completes.size(), "不應收到完成通知，實際收到：" + completes);
    }

    @Then("{string} 不應收到任何通知")
    public void noMessagesAtAll(String owner) {
        waitFor(() -> false, 600);   // 留時間讓誤發的訊息有機會到達
        assertEquals(0, wsMessages.getOrDefault(owner, List.of()).size(),
                "不應收到任何通知，實際收到：" + wsMessages.get(owner));
    }

    @Then("{string} 應收到調查 {string} 的填寫進度更新，進度 {int} \\/ {int}")
    public void gotProgress(String owner, String surveyName, int done, int total) {
        boolean ok = waitFor(() -> wsMessages.getOrDefault(owner, List.<String>of()).stream()
                .anyMatch(m -> m.contains("responseUpdated") && m.contains(surveyName)
                        && m.contains("\"done\":" + done) && m.contains("\"total\":" + total)), 3000);
        assertTrue(ok, "未收到進度 " + done + "/" + total + " 的更新，收到的訊息：" + wsMessages.get(owner));
    }

    @Then("{string} 應收到調查 {string} 的完成通知")
    public void gotNotification(String owner, String surveyName) {
        boolean ok = waitFor(() -> wsMessages.getOrDefault(owner, List.of()).stream()
                .anyMatch(m -> m.contains("surveyComplete") && m.contains(surveyName)), 3000);
        assertTrue(ok, "未收到調查「" + surveyName + "」的完成通知，收到的訊息：" + wsMessages.get(owner));
    }

    @Then("{string} 收到的完成通知總數應為 {int}")
    public void notificationCount(String owner, int expected) {
        waitFor(() -> false, 600);   // 留時間讓重複通知有機會到達
        List<String> completes = wsMessages.getOrDefault(owner, List.<String>of()).stream()
                .filter(m -> m.contains("surveyComplete")).toList();
        assertEquals(expected, completes.size(), "完成通知內容：" + completes);
    }

    /* ---------- 使用統計 ---------- */

    @When("有人從 IP {string} 開啟調查 {string} 的填寫頁")
    public void visitSurveyPage(String ip, String surveyName) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Forwarded-For", ip);
        rest.exchange("/s/" + surveyId(surveyName), HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    @When("{string} 查詢調查 {string} 的統計")
    public void queryStats(String owner, String surveyName) {
        last = rest.exchange("/api/surveys/" + surveyId(surveyName) + "/stats",
                HttpMethod.GET, new HttpEntity<>(headers(owner)), String.class);
    }

    @Then("{string} 查詢調查 {string} 的統計應為 開啟 {int} 次、使用者 {int} 人、回覆 {int} \\/ {int}")
    public void statsShouldBe(String owner, String surveyName, int visits, int users, int responded, int total) {
        queryStats(owner, surveyName);
        assertEquals(200, last.getStatusCode().value(), "回應：" + last.getBody());
        JsonNode n = json(last.getBody());
        assertEquals(visits, n.get("visits").asInt(), "開啟次數不符：" + last.getBody());
        assertEquals(users, n.get("uniqueIps").asInt(), "不重複 IP 數不符：" + last.getBody());
        assertEquals(responded, n.get("responded").asInt(), "回覆人數不符：" + last.getBody());
        assertEquals(total, n.get("total").asInt(), "應回覆人數不符：" + last.getBody());
    }

    /* ---------- Housekeeping 批次 ---------- */

    @Given("存在一筆迄日為 {int} 天前的調查 {string}")
    public void surveyEndedDaysAgo(int daysAgo, String name) {
        Survey s = new Survey();
        s.setId(UUID.randomUUID().toString());
        s.setName(name);
        s.setEndDate(LocalDate.now().minusDays(daysAgo));
        s.setStartDate(s.getEndDate().minusDays(1));
        s.setStartTime(LocalTime.of(9, 0));
        s.setEndTime(LocalTime.of(17, 0));
        s.setParticipants(new ArrayList<>(List.of("甲")));
        s.setOwnerToken("tk-housekeeping");
        s.setCreatedAt(LocalDateTime.now());
        surveyRepo.save(s);
        surveyIds.put(name, s.getId());
    }

    @Given("存在一筆迄日為 {int} 天後的調查 {string}")
    public void surveyEndsInDays(int daysAhead, String name) {
        surveyEndedDaysAgo(-daysAhead, name);
    }

    @Given("調查 {string} 已有一筆填寫資料")
    public void surveyHasResponse(String surveyName) {
        SurveyResponse r = new SurveyResponse();
        r.setSurveyId(surveyId(surveyName));
        r.setParticipantName("甲");
        r.setSlots("");
        r.setUpdatedAt(LocalDateTime.now());
        responseRepo.save(r);
    }

    @When("執行 housekeeping 批次")
    public void runHousekeeping() {
        housekeepingJob.cleanupExpiredSurveys();
    }

    @Then("調查 {string} 應已被清除")
    public void surveyDeleted(String surveyName) {
        assertTrue(surveyRepo.findById(surveyId(surveyName)).isEmpty(),
                "調查「" + surveyName + "」應已被 housekeeping 清除");
    }

    @Then("調查 {string} 應仍存在")
    public void surveyExists(String surveyName) {
        assertTrue(surveyRepo.findById(surveyId(surveyName)).isPresent(),
                "調查「" + surveyName + "」不應被清除");
    }

    @Then("調查 {string} 的填寫資料應已被清除")
    public void responsesDeleted(String surveyName) {
        assertTrue(responseRepo.findBySurveyId(surveyId(surveyName)).isEmpty());
    }
}
