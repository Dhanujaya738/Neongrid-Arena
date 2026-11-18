# Making changes

A short guide for team members.

## 1. Get the code (once)

```powershell
git clone <repository-url> D:\NeonGrid
cd D:\NeonGrid
```

## 2. Before you start working, get the latest version

```powershell
git checkout main
git pull
```

## 3. Make a branch for your change

Use your member letter and a short topic:

```powershell
git checkout -b memberC-grandmaster-bot
```

## 4. Edit, then build and test

| You changed | Do this |
|---|---|
| Java files (`src/`) | Stop the server (Ctrl+C), run `./scripts/build.ps1`, then `./scripts/run-server.ps1` |
| Web files (`web/`) | Just refresh the browser (Ctrl+F5) |
| Report / guide (`docs/report/*.html`) | Run `./docs/report/make-pdf.ps1` to regenerate the PDFs |

Check the game at `http://localhost:8080/` and, if you changed networking, the Control Room at `/control`.

## 5. Commit and push

```powershell
git add .
git commit -m "Add Grandmaster bot level"
git push -u origin memberC-grandmaster-bot
```

## 6. Open a pull request on GitHub

Open the repository page, click **Compare & pull request**, describe what you changed, and ask a teammate to look at it before merging into `main`.

## Who owns what

| Member | Files |
|---|---|
| A · NIO | `src/arena/server/Reactor.java`, `Connection.java`, `src/arena/client/BotSwarm.java` |
| B · TCP / URL / DNS | `src/arena/server/Http.java`, `WebSocket.java`, `NetUtil.java`, `src/arena/client/ConsoleClient.java` |
| C · Threads / AI | `src/arena/game/AiPlayer.java`, `src/arena/server/Leaderboard.java`, `Metrics.java` |
| D · UDP | `src/arena/server/Discovery.java`, `src/arena/client/LanScanner.java` |
| E · Security | `src/arena/common/Crypto.java`, `SecureLink.java`, `src/arena/server/Lobby.java`, `web/js/net.js` |
| Everyone | `web/` (game and Control Room UI), `docs/` |

If two people need to change the same file, tell each other first to avoid merge conflicts.

## Rules

- Never commit `build/`, `data/` or `*.jar`. The `.gitignore` already excludes them.
- Keep the project dependency-free: JDK only.
- Don't put real passwords in the code. The admin password is set with `--admin-pass` when starting the server.
