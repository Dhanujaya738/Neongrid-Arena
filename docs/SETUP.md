# NeonGrid — Setup Guide (5 minutes)

For any team member who wants to run the project on their own laptop.

---

## 1. Install Java (only once)

You need **JDK 17 or newer**. A JRE alone is not enough, because the build needs `javac`.

1. Open PowerShell and check:
   ```powershell
   java -version
   ```
   ```powershell
   javac -version
   ```
2. If both print a version of 17 or higher, skip to step 2.
3. Otherwise, download **Eclipse Temurin JDK 21** (Windows x64 `.msi`) from **adoptium.net**. During installation, tick **"Set JAVA_HOME"** and **"Add to PATH"**.
4. **Close and reopen PowerShell**, then run the two commands again.

---

## 2. Get the project

**Option A — from GitHub (recommended, so you can push changes):**

```powershell
git clone <repository-url> D:\NeonGrid
```

**Option B — from the ZIP:** copy `NeonGridArena-Submission.zip` from the group, right-click it → **Extract All…** → e.g. `D:\NeonGrid`.

Either way, the project folder contains `src`, `web`, `scripts`, `docs` and `README.md`.

---

## 3. Open PowerShell in that folder

In File Explorer, inside the project folder, click the **address bar**, type `powershell` and press **Enter**.

Then allow the project scripts to run (needed once per PowerShell window):

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

---

## 4. Build

```powershell
./scripts/build.ps1
```

Wait for **`Built build\neongrid-arena.jar`**. Only repeat this after changing a `.java` file.

---

## 5. Start the server

```powershell
./scripts/run-server.ps1
```

- The NEONGRID banner appears with all addresses. **Keep this window open**, because closing it stops the game.
- If Windows Firewall asks, tick **Private networks** and click **Allow access**.

---

## 6. Play

| What | Open in the browser |
|---|---|
| The game | `http://localhost:8080/` |
| Second player on the same laptop | a **second browser tab** with the same address |
| Phone / another laptop (same Wi-Fi) | the **LAN** address shown in the banner, e.g. `http://192.168.8.163:8080/` |
| Network Control Room | the purple **antenna icon** (top right of the game), or `http://localhost:8080/control` |

**Control Room password:** `arena-admin`

To use a different password, start the server like this instead:
```powershell
./scripts/run-server.ps1 --admin-pass MySecret
```

---

## 7. Optional extras

Open a **second** PowerShell window in the same folder and run `Set-ExecutionPolicy -Scope Process Bypass` first.

Terminal player (finds the server automatically over UDP):
```powershell
./scripts/run-client.ps1
```
Commands inside it: `ai ultimate hard`, `quick classic`, `join ABCD`, `5 3` (board 5, cell 3), `chat hi`, `quit`.

Load test with 300 bots (watch the Control Room):
```powershell
./scripts/run-bots.ps1 --bots 300
```

UDP discovery scan only:
```powershell
java -cp build/classes arena.client.LanScanner
```

**To stop anything:** press **Ctrl + C** in its window, or close the window.

---

## 8. Every time after the first

1. Open PowerShell in the project folder.
2. Run `Set-ExecutionPolicy -Scope Process Bypass`.
3. Run `./scripts/run-server.ps1`.
4. Open `http://localhost:8080/`.

---

## 9. Troubleshooting

| Problem | Fix |
|---|---|
| `java` / `javac` is not recognized | The JDK isn't installed or isn't on PATH. Reinstall Temurin with "Add to PATH" ticked, then **reopen** PowerShell |
| `./scripts/build.ps1` cannot be loaded … scripts disabled | Run `Set-ExecutionPolicy -Scope Process Bypass` in that same window |
| `./scripts/run-server.ps1` is not recognized | You're in the wrong folder. `cd` into the project folder (the one containing `README.md` and `scripts`) first |
| "Port 8080 … already in use" | A server is already running (check other PowerShell windows), or another app uses the port. Run `./scripts/run-server.ps1 --port 9090 --udp 9999` and open `http://localhost:9090/` |
| Phone can't open the LAN address | Both devices must be on the **same Wi-Fi**. Allow Java in Windows Firewall (Private). Campus Wi-Fi often blocks this, so use a **phone hotspot** |
| Page shows "Can't reach the game server" | The server window was closed or crashed. Start `./scripts/run-server.ps1` again |
| Control Room says "Wrong password" | Use `arena-admin`, or the password you passed with `--admin-pass`. After 5 wrong tries, refresh the page |
| Changed a `.java` file but nothing changed | Stop the server (Ctrl+C), run `./scripts/build.ps1`, then `./scripts/run-server.ps1` again. HTML/CSS/JS changes only need a browser refresh (Ctrl+F5) |
| Fonts look plain | The laptop is offline (fonts load from Google Fonts). The game still works fine |

---

## macOS / Linux

```bash
./scripts/build.sh
java -jar build/neongrid-arena.jar
```

Optional clients: `java -cp build/classes arena.client.ConsoleClient` and `java -cp build/classes arena.client.BotSwarm --bots 100`.

---

More detail: `README.md` (rules and demo plan) and `docs/report/NeonGrid-Team-Guide.pdf` (how every part works, per member).
