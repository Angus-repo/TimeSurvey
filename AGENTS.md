# AGENTS.md

本檔案提供給 AI 代理（Claude Code / GitHub Copilot / Cursor / Codex 等）在此專案中工作時的指引，為唯一事實來源（single source of truth）。

## 專案概觀

時間調查網站（TimeSurvey）：讓參與者自行填寫可出席時間的調查網站。
技術棧為 **Spring Boot 3.4.5 + Java 21 + H2（檔案模式）**，行為以 **Cucumber BDD（中文 Gherkin）** 驗證。

詳細功能、執行方式與資料庫密碼機制請見 [README.md](README.md)。

## 專案結構

```
src/
  main/java/com/angus/timesurvey/
    config/      設定（含 DbPasswordEnvironmentPostProcessor）
    controller/  REST / 頁面控制器
    job/         Housekeeping 背景批次
    model/       JPA 實體
    repo/        Spring Data 儲存庫
    ws/          WebSocket 完成通知
  main/resources/static/   前端頁面（index.html / survey.html / stats.html）
  test/java/com/angus/timesurvey/bdd/   Cucumber 膠水碼（SurveySteps / UiSteps 等）
  test/resources/features/  *.feature 行為情境（中文 Gherkin）
```

## 最重要的規則：功能變更必須同步維護 BDD

**任何時候新增、修改或刪除功能，都必須一併更新對應的 BDD 測試。** 這不是可選項。

- **新增功能** → 在 `src/test/resources/features/` 新增或擴充對應的 `.feature` 情境，
  並於 `bdd/` 下補上所需的 Step Definition（膠水碼）。
- **修改功能**（含行為、驗證規則、UI 流程的變動）→ 同步更新相關 `.feature` 的
  情境與步驟，使其反映新的預期行為。
- **刪除功能** → 移除或調整不再適用的 `.feature` 情境與對應步驟，避免遺留失效測試。

### 現有 feature 與功能對應

| Feature 檔 | 對應功能 |
| --- | --- |
| `survey_management.feature` | 調查建立、編輯、刪除與驗證 |
| `owner_isolation.feature` | 發起者隔離（每人只看得到自己的調查） |
| `response_submission.feature` | 參與者填寫與覆寫 |
| `participant_self_service.feature` | 允許成員加寄（邀請他人加入）／允許換員 |
| `survey_stats.feature` | 調查結果統計與會議時段計算 |
| `close_survey.feature` | 結束調查 |
| `completion_notification.feature` | WebSocket 完成通知 |
| `housekeeping.feature` | 過期調查清理批次 |
| `ui_admin_page.feature` | 後台維護頁 UI |
| `ui_survey_page.feature` | 調查填寫頁 UI |
| `ui_stats_page.feature` | 統計頁 UI |

修改功能時，先在上表找到對應 feature；若新功能不屬於任何現有 feature，請新增一個。

## 變更流程（每次功能變更都遵循）

1. 先閱讀相關的 `model/`、`controller/`、`repo/` 以及對應的 `.feature` 與 Step Definition。
2. 實作 / 修改 / 刪除功能程式碼。
3. **同步**更新對應的 `.feature` 情境與 `bdd/` 膠水碼。
4. 執行測試確認全綠：

   ```bash
   mvn test
   ```

5. 完成後在回覆中說明：動了哪些功能、對應更新了哪些 feature／step。

## 慣例

- Gherkin 情境一律使用**繁體中文**撰寫，與既有 `.feature` 風格一致。
- 測試使用 in-memory H2，不會動到 `./data/` 的正式資料。
- 不要把資料庫密碼或主金鑰寫進版控檔；密碼機制由 `DbPasswordEnvironmentPostProcessor`
  與 Jasypt 管理（見 README）。
- UI 行為測試使用 Playwright（見 `UiSteps`）。

## 常用指令

```bash
mvn spring-boot:run   # 啟動（需主金鑰，見 README）
mvn test              # 執行所有 BDD 測試
mvn package           # 打包為可執行 jar
```
