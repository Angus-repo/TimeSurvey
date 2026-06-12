# 時間調查網站 (TimeSurvey)

讓參與者自行填寫可出席時間的調查網站。使用 Spring Boot + H2 資料庫（檔案模式，資料存在 `./data/`）。

## 功能

### 1. 後台維護頁（`/`）
- 設定調查名稱、可調查的日期範圍、每日時間範圍、受調查人員名單
- 「複製調查連結」按鈕，可複製實際的調查網址（`/s/{調查ID}`）
- 查看調查結果：欄＝參與者、列＝日期＋30 分鐘時段；可設定會議時間（小時），
  「每個人都有空且長度足夠」的連續時段會以綠色大框與結論膠囊標示
- 調查清單顯示填寫狀況（x/y），全部填完以綠色標示
- **每個人只看得到自己發起的調查**：以瀏覽器 localStorage 的 owner token 識別
  （無需登入；換瀏覽器或清除資料會看不到舊調查）
- **完成通知**：全部人填寫完成時，後端透過 WebSocket（`/ws/notify`）即時推播，
  後台頁面跳出瀏覽器通知（需點「開啟完成通知」授權）
- 編輯、刪除調查（僅發起者本人可操作）

### 2. 時間調查網頁（`/s/{調查ID}`）
- 參與者選擇自己的姓名後，勾選可出席的時間
- 可一鍵勾選某日的「全天」「上午」「下午」，或勾選個別時段
- 時間顆粒度為 30 分鐘
- 重複開啟會載入先前填寫的內容，可修改後重新送出

## 執行方式

啟動前需先設定 Jasypt 主金鑰環境變數（見下方「設定檔加密」一節）：

```bash
export JASYPT_ENCRYPTOR_PASSWORD=你的主金鑰
mvn spring-boot:run
```

啟動後開啟 <http://localhost:8080> 進入後台維護頁。
（VS Code 使用者按 F5 即可，`.vscode/launch.json` 已帶入開發用主金鑰。）

打包執行：

```bash
mvn package
JASYPT_ENCRYPTOR_PASSWORD=你的主金鑰 java -jar target/timesurvey-1.0.0.jar
```

## 設定檔加密（Jasypt）

`application.properties` 中的資料庫密碼以 Jasypt 加密成 `ENC(密文)` 存放，
啟動時才用主金鑰在記憶體中解密，設定檔裡不會出現明文密碼。
主金鑰由環境變數 `JASYPT_ENCRYPTOR_PASSWORD` 提供；未設定時應用程式會拒絕啟動。

### 設定主金鑰環境變數

| 環境 | 指令 |
| --- | --- |
| Linux / macOS（當前終端機） | `export JASYPT_ENCRYPTOR_PASSWORD=你的主金鑰` |
| Windows cmd（當前視窗） | `set JASYPT_ENCRYPTOR_PASSWORD=你的主金鑰` |
| Windows PowerShell（當前視窗） | `$env:JASYPT_ENCRYPTOR_PASSWORD="你的主金鑰"` |
| Windows（永久，使用者層級） | `setx JASYPT_ENCRYPTOR_PASSWORD 你的主金鑰`（開新視窗才生效） |

也可改用 JVM 參數：`java -Djasypt.encryptor.password=你的主金鑰 -jar target/timesurvey-1.0.0.jar`。

開發機上 VS Code 的 `.vscode/launch.json` 已內建一組開發用主金鑰，按 F5 即可啟動；
**正式環境請換成自己的主金鑰**，只設在伺服器的環境變數，不要提交進 git。

### 換主金鑰（或換資料庫密碼）

1. 用新的主金鑰重新產生密文：

   ```bash
   mvn jasypt:encrypt-value -Djasypt.encryptor.password=新主金鑰 -Djasypt.plugin.value='資料庫密碼'
   ```

2. 將輸出的 `ENC(...)` 整段貼回 `application.properties` 的 `spring.datasource.password=`。
3. 之後啟動時改用新主金鑰設定 `JASYPT_ENCRYPTOR_PASSWORD`。

注意：H2 的 `sa` 密碼是在「第一次建立資料庫檔案」時定下來的。
若要更換的是**資料庫密碼本身**（而非只換主金鑰），需先刪除舊的
`./data/timesurvey.mv.db` 再啟動，否則會連不上。

## 測試（Cucumber BDD）

行為測試位於 `src/test/resources/features`（中文 Gherkin），涵蓋：調查建立與驗證、
發起者隔離、參與者填寫與覆寫、結束調查、WebSocket 完成通知、housekeeping 清理。

```bash
mvn test
```

測試使用 in-memory H2，不會動到 `./data` 的正式資料。
VS Code 使用者可直接執行內建 task「BDD 測試 (Cucumber)」（終端機 → 執行工作，
或 Ctrl/Cmd+Shift+P → Tasks: Run Test Task）。

## Housekeeping 背景批次

- 由 `application.properties` 的 `housekeeping.cron` 以 crontab 格式設定啟動時間（秒 分 時 日 月 週），預設每晚 20:00：

  ```properties
  housekeeping.cron=0 0 20 * * *
  ```

- 批次啟動時，以批次啟動日計算，**迄日已經過 7 日**的調查會連同填寫資料一併清除。

## 資料儲存

所有設定與調查資料皆存於 H2 資料庫（`./data/timesurvey.mv.db`），重啟不會遺失。
偵錯用 H2 console：<http://localhost:8080/h2-console>（JDBC URL：`jdbc:h2:file:./data/timesurvey`，帳號 `sa`，密碼為建立資料庫時設定的密碼）。
