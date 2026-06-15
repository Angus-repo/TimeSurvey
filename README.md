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

啟動前需先設定 Jasypt 主金鑰環境變數（見下方「資料庫密碼」一節）：

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

## 資料庫密碼：動態產生、加密儲存

資料庫密碼**不寫在 git 版控的 `application.properties`**，而是由
`DbPasswordEnvironmentPostProcessor` 在啟動時管理：

- **首次啟動**（`./data/timesurvey.mv.db` 與密碼檔都不存在）：產生一組 32 字元的強隨機密碼，
  用 Jasypt 加密成 `ENC(密文)` 後寫入 `./data/db-secret.properties`。
- **之後每次啟動**：讀取該密碼檔，其中的 `ENC(...)` 由 jasypt-spring-boot 在讀取
  `spring.datasource.password` 時，用主金鑰在記憶體中自動解密。
- 因此只要不刪 `./data`，每次都用同一個密碼開同一顆資料庫；密碼檔位於已被
  `.gitignore` 的 `data/` 內，與資料庫檔同生命週期，不會進版控。
- 若資料庫檔已存在但密碼檔遺失，啟動會直接報錯（原密碼已無法復原）——
  此時請刪除整個 `data/` 目錄重新開始。

主金鑰用途：**首次產生密碼時用它加密、之後每次啟動用它解密**。來源優先序：

1. 環境變數 `JASYPT_ENCRYPTOR_PASSWORD`（或屬性 `jasypt.encryptor.password`、JVM 參數 `-Djasypt.encryptor.password=`）。
2. 都沒提供時，**改用主機名稱（hostname）作為預設主金鑰**。

> ⚠️ 用 hostname 當預設要注意兩點：①**主機名稱一旦改變**（換機器、改 hostname、容器重建成不同名稱），
> 舊密碼檔就會解不開、連不進既有資料庫——需沿用相同 hostname，或改用固定的環境變數金鑰。
> ②hostname 通常是可猜測的低強度字串，保護力遠不如自訂金鑰；**正式環境仍建議明確設定
> `JASYPT_ENCRYPTOR_PASSWORD`**，hostname 預設主要是方便本機 / 開發啟動。

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

> 提醒：密碼檔躺在 `data/` 裡、和資料庫檔並排，加密要真的有意義，主金鑰就不能和它放在
> 同一顆磁碟。請務必把主金鑰設在執行環境的環境變數，別寫進專案檔。

### 重置資料庫密碼

刪除整個 `./data` 目錄再啟動即可——資料庫與密碼檔一起清掉，下次啟動會用目前的主金鑰
重新產生一組新密碼。（這也會清空所有調查資料。）

### 換主金鑰

主金鑰換了之後，舊密碼檔裡的 `ENC(...)` 會解不開。最簡單的做法是用**新主金鑰**重新加密
目前的隨機密碼：

1. 取得目前密碼明文：用**舊主金鑰**解開 `./data/db-secret.properties` 的 `ENC(...)`
   （`mvn jasypt:decrypt-value -Djasypt.encryptor.password=舊主金鑰 -Djasypt.plugin.value='貼上ENC括號內的密文'`）。
2. 用**新主金鑰**重新加密該明文：
   `mvn jasypt:encrypt-value -Djasypt.encryptor.password=新主金鑰 -Djasypt.plugin.value='上一步的明文'`。
3. 把新的 `ENC(...)` 寫回 `./data/db-secret.properties`，並改用新主金鑰設定
   `JASYPT_ENCRYPTOR_PASSWORD`。

（若不在意保留現有資料，直接刪除 `./data` 用新主金鑰重新產生最省事。）

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
偵錯用 H2 console：<http://localhost:8080/h2-console>（JDBC URL：`jdbc:h2:file:./data/timesurvey`，帳號 `sa`，
密碼為系統自動產生的隨機密碼，明文不會落地；如需登入 console，可用主金鑰解開 `./data/db-secret.properties` 取得，
見「資料庫密碼」一節）。
