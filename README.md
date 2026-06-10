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

```bash
mvn spring-boot:run
```

啟動後開啟 <http://localhost:8080> 進入後台維護頁。

打包執行：

```bash
mvn package
java -jar target/timesurvey-1.0.0.jar
```

## Housekeeping 背景批次

- 由 `application.properties` 的 `housekeeping.cron` 以 crontab 格式設定啟動時間（秒 分 時 日 月 週），預設每晚 20:00：

  ```properties
  housekeeping.cron=0 0 20 * * *
  ```

- 批次啟動時，以批次啟動日計算，**迄日已經過 7 日**的調查會連同填寫資料一併清除。

## 資料儲存

所有設定與調查資料皆存於 H2 資料庫（`./data/timesurvey.mv.db`），重啟不會遺失。
偵錯用 H2 console：<http://localhost:8080/h2-console>（JDBC URL：`jdbc:h2:file:./data/timesurvey`，帳號 `sa`，密碼空白）。
