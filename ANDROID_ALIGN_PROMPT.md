# Paste this into the Android Cursor / Android Studio agent

Work only in:

`C:\Users\vikte\AndroidStudioProjects\AshoreProspect`

Do **not** edit the JavaFX host (`C:\Users\vikte\IdeaProjects\Ashore_Prospect`) or Node (`server/`). Android is **always a client** of the same Node (`http://10.0.2.2:3456` on emulator, LAN/Tailscale on a phone). Do not add SMTP settings, `SMTP_PASS`, or a host mail page.

Node already changed. Register without email + security question now **400**s. There is no `/forgot` or `/reset` on Android yet. Match the host product below.

Do not invent Node fields. Do not add CRDTs or rev columns. Do not take mailbox passwords in chat.

---

## 1. Register must collect recovery fields

Today `AuthRequest` is only `username` + `password`. `LoginScreen` Register is a second tap on the same two fields. That is broken.

`POST /register` body must be:

```json
{
  "username": "...",
  "password": "...",
  "email": "...",
  "question": "...",
  "answer": "...",
  "device": "optional short label"
}
```

Rules (same as Node):

- password min **8**
- email must look real (`@` and `.`)
- security question min **8** characters
- security answer min **4** after trim
- all five fields required or the account cannot be recovered

UI: a **Register** screen (not the same card as login) with username, password, email, question, answer, Remember login, Create account, Back. Show a working/loading state on the button. Dark chrome + cream text already used on login must stay readable.

`POST /login` stays username + password only.

---

## 2. Forgot password (new)

Add a Forgot-password screen from login. Never auto-fill the reset token (the host used to do that only on localhost; it is removed).

Flow:

1. Username + email on the account → `POST /forgot` `{ "username", "email" }`
2. Response is **always** `{ "ok": true }` even if the account does not exist. Do not treat that as “mail definitely sent.”
3. User types: reset token (from **email** or the **host Node terminal**), security answer, new password (min 8)
4. `POST /reset` `{ "token", "answer", "newPassword" }`

Copy to show:

- Token lasts **8 minutes**, one use
- Email alone is not enough; the security answer is required
- Same Gmail can be sender and receiver; check **Spam**
- If mail is late, the host PC Node terminal always printed the token and the question
- Do not click send again for 60 seconds (server rate-limit; second click does nothing)

HTTP: `/forgot` and `/reset` need a **longer read timeout** than the current 8 s OkHttp default (use ≥ 15 s for those two). Show Working… / spinner; do not look dead.

Do **not** expect `resetToken` or `securityQuestion` in the JSON. Clients do not get those fields.

---

## 3. Account (phone) so existing users can set recovery

If there is no Account page yet, add a small one (or settings) for a logged-in user:

- `GET /me` — show username, email, `hasSecurity`, `securityQuestion`
- `POST /me/email` `{ "email" }` — required, cannot clear
- `POST /me/security` `{ "question", "answer" }` — required for recovery
- `POST /change-password` `{ "oldPassword", "newPassword" }` (also `/me/password`) — new password min 8; other sessions die; this device stays signed in

Do not store the security answer in Room / DataStore. Node bcrypts it.

Optional, match JavaFX if you already have remember-me:

- `GET /challenge-question` / `POST /challenge-answer`
- Opt-in random check (~18%) when remember-me + online after auto-login
- Wrong answer: wipe **local** cache only, log out, do not save Typewriter

Do **not** add host SMTP UI.

---

## 4. Remember me (already partly there)

`SessionStore` remember-login is fine. Keep: checked → next launch skips login and opens the local cache offline. Unchecked → do not persist the Bearer token.

---

## 5. Files to touch (expected)

- `app/src/main/java/com/opwait/ashoreprospect/data/remote/NodeDtos.kt` — extend `AuthRequest`; add forgot/reset/me DTOs
- `NodeApiService.kt` — `forgot`, `reset`, `me`, `saveEmail`, `saveSecurity`, `changePassword`
- `TaskRepository.kt` — register with extra fields; forgot/reset helpers; do not log tokens
- `SchedulerScreens.kt` `LoginScreen` — Register screen + Forgot screen; loading; contrast
- `SchedulerViewModel.kt` / `MainActivity.kt` — wire the new screens

Do not change LWW / `preferRemote` / tombstones unless they already diverge from the desktop `TaskRepository`.

---

## 6. Verify

- New register without email/question/answer is rejected
- Register with all fields can log in
- Forgot never paints a token into the field
- Forgot shows Working… then the generic “if that account exists…” line
- Reset with token + answer + new password works
- Emulator still uses `http://10.0.2.2:3456`
- No SMTP / App-password fields anywhere on Android
