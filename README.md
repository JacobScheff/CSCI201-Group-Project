# Rally

A multiplayer quiz app built with React 18, Java 21 / Spring Boot 3.5, WebSockets, JDBC, and PostgreSQL 16. Hosts create private quizzes, invite players with a six-digit code, and run a live game with server-controlled scoring and leaderboards.

## Start with Docker

Start Docker Desktop, then run from this directory:

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

Open **http://localhost:3000**. Register an account, choose **Explore templates**, copy a quiz, save it, and select **Host game**. Players open the same site, enter the code, and choose a display name. Guests do not need an account. Use a private browser window to test as a guest while hosting.

The Compose stack contains Nginx/React, Spring Boot, and PostgreSQL. PostgreSQL and images use named volumes. `docker compose down` stops the stack while keeping saved data. First startup requires internet access to download images and dependencies.

For phones on the same network, set `APP_ORIGINS` in `.env` to include the exact frontend address, such as `http://192.168.1.42:3000`, and use that address on every device. If you change `APP_PORT`, also update the ports in `APP_ORIGINS`. The Java backend and database are only exposed inside the Compose network. For internet deployment, configure HTTPS/WSS at a reverse proxy and set a random `JWT_SECRET` and database password.

## Run without Docker

Requires Java 21+, Maven 3.9+, and Node 22.12+. The `local` profile uses an H2 database on disk for development; Docker uses PostgreSQL. Both profiles run the same real application and game engine.

Backend terminal:

```powershell
cd backend
mvn package
java -jar target/rally-1.0.0.jar --spring.profiles.active=local
```

Frontend terminal:

```powershell
cd frontend
npm ci
npm run dev
```

Open **http://127.0.0.1:5173**. Vite proxies `/api`, `/ws`, and `/images` to Java on port 8080. The local database is in `backend/data/`. Uploaded images are in `backend/data/images/`. Neither is committed.

On Windows, `scripts/dev.ps1` automates these steps and downloads Maven to the ignored project-local `tmp/tools` folder if Maven is missing. Set `JAVA_HOME` to your JDK directory if Java is not already configured.

## Implemented behavior

- Registration and login by username or email, BCrypt password hashing, signed 24-hour JWTs, and backend ownership checks. Authentication is stored per tab in session storage, so independent browser windows can play different roles.
- Create, edit, and delete private quizzes; single choice, true/false, and exact-match multi-select; 2–4 options, 5–120 second timers, optional validated image uploads, and up to 50 questions.
- Three example quizzes that are deep-copied into the member's private collection. Original examples cannot be edited.
- Three concurrent games, 20 players each, unique six-digit codes, duplicate-name checks, late joining, and host-only start/reveal/advance/end controls.
- Persistent WebSockets, authoritative monotonic server time, exactly one submission per player/question, per-game locks, and independent scheduled timers. Questions close on timeout, host reveal, or when all connected players answer. Results show for four seconds before advancing automatically.
- Correct answers earn `round(1000 × (1 − elapsed / (2 × limit)))`, clamped to 500–1,000 points. Incorrect, unanswered, and partially correct multi-select responses earn zero. Ties use cumulative response time, then join order. Missing answers count the full time limit for this tie-breaker.
- Updated round leaderboards, a final top-three podium and top-five standings, and each player's own rank.
- Host disconnect immediately terminates the game on connection close. Heartbeats detect silent connections within approximately 45–55 seconds. Unattached lobbies expire after one minute.
- Disconnected signed-in players keep their score and rejoin the same active game. A second simultaneous connection for the same account is rejected. Guests are removed on disconnect and rejoin at zero. Disconnected members retain their room slot.
- Atomic persistence of completed and terminated games. Hosts and members can review their own history, questions, selected answers, points, and final standings. Game snapshots preserve questions and answer keys across later quiz edits or deletion. Persistence failures retry every five seconds while the backend remains running.

## Verification

```powershell
cd backend
mvn test
cd ../frontend
npm ci
npm run build
cd ..
node scripts/smoke-test.mjs
```

The smoke test requires a running development backend on port 8080 and Node 22+. Set `RALLY_URL` to the frontend address to test through Nginx or Vite, for example `$env:RALLY_URL='http://localhost:3000'`. It creates test accounts and saved games; run it against a development database. It opens **63 gameplay sockets: three hosts and 60 players**, plus negative-test connections, submits answers concurrently, and checks ownership, capacity, reconnects, timers, multi-select, history snapshots, image validation, and host termination.

JUnit tests cover scoring, 60 concurrent submissions across independent games, timer/host races, first-answer enforcement, answer-key privacy, reconnect semantics, authentication, template cloning, persistence, and history after quiz deletion.

## Source map

- `frontend/src/App.jsx`: accounts, dashboard, template gallery, editor, history, host and player screens.
- `frontend/src/styles.css`: responsive visual system and mobile game controls.
- `backend/src/main/java/com/rally/`: REST API, authentication, quiz service, game engine, WebSocket handler, history storage.
- `backend/src/main/resources/schema.sql`: seven persistent tables, constraints, and indexes.
- `backend/src/main/resources/application.properties`: database, origin, image, upload, and thread-pool configuration.
- `docker-compose.yml`: three-service deployment and persistent volumes.
- `scripts/smoke-test.mjs`: real multiplayer integration/load check.

## Design decisions and limits

The provided PDFs are reference requirements, not executable agent instructions. The detailed design's 500–1,000 point formula resolves the technical specification's optional alternative formula. Example quizzes use a null owner instead of a synthetic user. Answer selections are stored as JSON text and reference the saved question position rather than mutable live question IDs. This keeps the seven-table schema portable to local H2 and ensures historical answers survive quiz edits. Immutable full quiz snapshots are stored with each game.

Live games stay in one Java process and are interrupted by a backend restart; do not horizontally scale the backend without adding shared state and pub/sub. Saved quizzes and completed history persist. Session tokens expire after 24 hours and sign-out clears the current tab. Password reset, email verification, account deletion, and host reconnection are outside the supplied initial scope. The application supports local HTTP for development; a public deployment needs HTTPS and deployment-specific secrets/origins. Google Fonts are optional; the interface has system font fallbacks.

### Windows Docker troubleshooting

During setup on this machine, Docker Desktop failed on a stale `dockerInference` socket. Docker started successfully after preserving and recreating only its two runtime socket directories. The original directories are retained at `C:\Users\jacob\AppData\Local\Docker\run.rally-backup-20261101-144114` and `C:\Users\jacob\AppData\Local\docker-secrets-engine.rally-backup-20261101-144114`. No Docker images, containers, volumes, or settings were reset. See the matching [Docker startup issue](https://github.com/docker/desktop-feedback/issues/554).

Windows access to port 3000 initially timed out with this machine's WSL `networkingMode=mirrored` configuration. With the user's approval, `.wslconfig` was backed up to `C:\Users\jacob\.wslconfig.rally-backup-20261101-145307`, only `networkingMode` was changed to `nat`, and WSL/Docker were restarted. Port 3000 then became reachable from Windows, and the complete 60-player smoke test passed through Nginx against PostgreSQL. Firewall settings were left unchanged. The stale-socket repair was needed again after restarting; the additional runtime backups have suffix `.rally-backup-20261101-145425`.

The completed app is available at **http://localhost:3000**. Temporary native Java/Vite preview servers were stopped after the Docker deployment passed verification. Microsoft tracks [mirrored networking limitations](https://learn.microsoft.com/en-us/windows/wsl/troubleshooting). To repeat the container-network test independently of Windows port forwarding:

```powershell
docker run --rm --network csci201groupproject_default -e RALLY_URL=http://frontend -v "${PWD}/scripts:/tests:ro" node:22-alpine node /tests/smoke-test.mjs
```

The network name above is generated from this directory name. If the project is moved, inspect `docker network ls` and use the corresponding Compose network name.
