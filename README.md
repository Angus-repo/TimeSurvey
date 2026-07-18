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
- **意見回饋**（可選）：後台頁右上角可顯示「意見回饋」按鈕，點擊後以 `mailto:` 開啟
  預設郵件軟體寄信給指定收件者（見下方「意見回饋設定」一節）。未設定時此按鈕不會顯示。

### 2. 時間調查網頁（`/s/{調查ID}`）
- 參與者選擇自己的姓名後，勾選可出席的時間
- 可一鍵勾選某日的「全天」「上午」「下午」，或勾選個別時段
- 時間顆粒度為 30 分鐘
- 重複開啟會載入先前填寫的內容，可修改後重新送出

## 意見回饋設定

後台頁右上角的「意見回饋」按鈕預設**不顯示**，需自行建立 `./data/feedback.properties`
（與資料庫檔同目錄，已 `.gitignore`，不進版控）才會出現：

```properties
# 必填：收件者信箱，未填此項則整個意見回饋功能不顯示
feedback.recipient=someone@example.com
# 選填：郵件主旨，未填則採用預設值 "[TimeSurvey] 意見回饋"
feedback.subject=[TimeSurvey] 意見回饋
```

修改此檔後，重新啟動應用程式即會生效（設定值於啟動時載入，執行中修改不會即時反映）。

## Entra ID 登入設定（可選）

各網頁（後台維護頁 `/`、調查填寫頁 `/s/{id}`、統計頁 `/stats`）預設**免登入**。
若要啟用 Microsoft Entra ID（Azure AD）登入，請建立 `./data/entra.properties`
（範例見同目錄的 `entra.properties.example`，已 `.gitignore`，不進版控）：

```properties
# 必填：Azure 應用程式註冊的 Application (client) ID，未填此項則不啟用登入
entra.client-id=11111111-2222-3333-4444-555555555555
# 應用程式身分驗證，密碼與憑證二擇一（皆填時以憑證優先）——
# 方式 a：用戶端密碼（憑證及祕密 > 用戶端密碼的「值」），後端以此向微軟換取 token
entra.client-secret=你的用戶端密碼
# 方式 b：憑證（PEM 憑證 + PKCS#8 私鑰；私鑰與憑證同檔時 certificate-key 可省略），
# 後端每次呼叫 token 端點時用私鑰現簽短命 JWT，私鑰不出主機，較密碼安全
entra.certificate=./data/entra-cert.pem
entra.certificate-key=./data/entra-key.pem
# 選填：Directory (tenant) ID，未填則採用預設值 common（公司帳號與個人 Microsoft 帳戶皆可）；
# 公司正式使用建議填自家租戶 ID。注意 organizations 會擋掉個人帳戶（選了帳號會被退回
# 帳戶選擇頁、形成無限循環），個人帳戶測試時請留空（common）
entra.tenant-id=
```

登入採**後端授權碼流程**：首次登入後 refresh token 由後端保存在資料庫
（`entra_token` 資料表），之後每次都由後端以 refresh token 換取 access token
呼叫 Microsoft Graph，前端不接觸任何 token。啟用後除了強制以公司帳號登入，
還會開啟兩項整合功能：

- **受調查人員的組織目錄檢查**：在後台輸入受調查人員、或於填寫頁邀請／換員時，
  以 Microsoft Graph 檢查該姓名是否存在公司組織；查無此人會提示使用者，
  同名同姓多筆時彈出選擇視窗（列出部門、email）讓使用者指定是哪一位。
- **填寫頁帶入我的行事曆**：填寫頁新增「同意讀取我的行事曆」按鈕，同意後把
  調查期間已有的會議帶入時段表，忙碌時段以**灰色**顯示（與白色可選、綠色已選區別）；
  同一時段有多個會議會在右上角註明數量，滑鼠移過可看每個會議的完整名稱與邀請人。

Azure 應用程式註冊需求：**Web** 平台重新導向 URI 填本站的回呼端點
（如 `http://localhost:8080/api/entra/callback`），並於「憑證及祕密」建立
用戶端密碼**或**上傳憑證公鑰（產生步驟見下）；
Microsoft Graph 委派權限：`User.Read`（登入）、`User.ReadBasic.All`（組織目錄檢查）、
`Calendars.Read`（帶入行事曆）、`offline_access`（取得 refresh token）。
未建立 `entra.properties`（或 CLIENT_ID 留空、密碼與憑證皆未設）時，
以上功能全部不啟用，網站維持原本的免登入行為。

### 以憑證取代用戶端密碼

後端每次呼叫微軟 token 端點時，改用憑證私鑰現簽短命 JWT（client assertion）
證明應用程式身分：私鑰不出主機、線上只傳簽章，較 client secret 安全，
效期也可自訂（密碼在 Azure 上限約 2 年）。設定步驟：

1. 產生自簽憑證（私鑰為 PKCS#8、效期 2 年，檔案落在已 `.gitignore` 的 `data/`）：

   ```bash
   openssl req -x509 -newkey rsa:2048 -keyout ./data/entra-key.pem \
       -out ./data/entra-cert.pem -days 730 -nodes -subj "/CN=timesurvey"
   ```

2. 到 Azure Portal > 應用程式註冊 > 憑證及祕密 > **憑證** > 上傳憑證，
   上傳 `entra-cert.pem`（公鑰）；私鑰 `entra-key.pem` 留在主機上，切勿外流。

3. 在 `entra.properties` 加入憑證路徑（有設憑證就優先於 client-secret）：

   ```properties
   entra.certificate=./data/entra-cert.pem
   entra.certificate-key=./data/entra-key.pem
   ```

4. 重新啟動應用程式即生效。確認登入正常後，可刪除 `entra.client-secret`
   設定並到 Azure Portal 撤銷舊的用戶端密碼。

注意：私鑰需為 PKCS#8 格式（檔頭 `-----BEGIN PRIVATE KEY-----`）。
若手上的私鑰是 openssl 舊格式（`BEGIN RSA PRIVATE KEY`，PKCS#1），請先轉換：

```bash
openssl pkcs8 -topk8 -nocrypt -in 舊私鑰.pem -out 新私鑰.pem
```

若憑證與私鑰放在同一個 PEM 檔，`entra.certificate-key` 可省略。
憑證設定有誤（找不到檔案、格式不對）時應用程式會**啟動失敗**並顯示中文錯誤訊息，
避免上線後才發現登入不了。憑證到期前記得重新產生並上傳新公鑰。

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
2. 都沒提供時，**自動改用主機名稱（hostname）作為預設主金鑰**。

### 預設主金鑰：主機名稱（hostname）

如果**完全不設定** `JASYPT_ENCRYPTOR_PASSWORD`，系統會自動以執行主機的名稱當作主金鑰，
因此「什麼都不設定」也能直接啟動：

```bash
# 不帶任何環境變數，直接啟動 → 以 hostname 作為主金鑰
java -jar target/timesurvey-1.0.0.jar
```

實際採用的 hostname 依下列順序取得（取到第一個非空值為止）：

1. `InetAddress.getLocalHost().getHostName()`（Java 取得的主機名稱）
2. 環境變數 `HOSTNAME`（常見於 Linux）
3. 環境變數 `COMPUTERNAME`（Windows）
4. 都取不到時，退回固定字串 `timesurvey-default-key`

> ⚠️ 用 hostname 當預設要注意兩點：
> 1. **主機名稱一旦改變**（換機器、改 hostname、容器重建成不同名稱），舊密碼檔就會解不開、
>    連不進既有資料庫——需沿用相同 hostname，或改用固定的環境變數金鑰。由於資料庫密碼是在
>    「第一次建立 `data/` 時」用當時的主金鑰定下來的，**之後就不能再切換主金鑰（含 hostname ↔ 明確金鑰）**，
>    否則會出現 H2 `Wrong user name or password`；要換金鑰請刪除整個 `data/` 重置。
> 2. hostname 通常是可猜測的低強度字串，保護力遠不如自訂金鑰；**正式環境仍建議明確設定
>    `JASYPT_ENCRYPTOR_PASSWORD`**，hostname 預設主要是方便本機 / 開發 / 免設定啟動。

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
