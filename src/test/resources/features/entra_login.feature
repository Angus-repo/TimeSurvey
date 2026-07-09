# language: zh-TW
功能: Entra ID 登入設定
  由 data/entra.properties 決定是否啟用 Microsoft Entra ID 登入：
  未設定 CLIENT_ID／CLIENT_SECRET 時，設定 API 回應 204，各網頁（後台維護頁、
  調查填寫頁、統計頁）不啟用 Entra ID 登入，維持原本的免登入行為。
  已啟用時登入採後端授權碼流程：refresh token 保存在資料庫，
  由後端換取 access token 代呼叫 Microsoft Graph；未登入呼叫代理 API 一律回 401

  場景: 未設定 Entra ID 時設定 API 回應 204
    當 查詢 Entra ID 登入設定
    那麼 回應狀態碼應為 204

  場景: 未登入時查詢組織使用者回應 401
    當 未登入時查詢組織使用者
    那麼 回應狀態碼應為 401

  場景: 未登入時讀取行事曆回應 401
    當 未登入時讀取我的行事曆
    那麼 回應狀態碼應為 401

  場景: 未登入時查詢姓名建議回應 401
    當 未登入時查詢姓名建議
    那麼 回應狀態碼應為 401

  場景: 未登入時查詢使用者時區回應 401
    當 未登入時查詢使用者時區
    那麼 回應狀態碼應為 401
