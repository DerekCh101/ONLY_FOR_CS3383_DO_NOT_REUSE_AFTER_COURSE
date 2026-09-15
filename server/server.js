import http from "node:http";
import "./db.js";
import { send } from "./lib/httpUtil.js";
import { handleStatic } from "./routes/staticRoutes.js";
import { handleAuth } from "./routes/authRoutes.js";
import { handleTasks } from "./routes/taskRoutes.js";
import { handleBlocked } from "./routes/blockedRoutes.js";
import { handleTypewriter } from "./routes/typewriterRoutes.js";

const HostNodeServerListenToWhereIP = process.env.HOST_LISTEN_IP || "0.0.0.0";
const HostNodeServerListenToWherePort = Number(process.env.HostNodeServerListenToWherePort || 3456);

http.createServer(async (req, res) => {
    if (handleStatic(req, res)) return;
    if (await handleAuth(req, res)) return;
    if (await handleTasks(req, res)) return;
    if (await handleBlocked(req, res)) return;
    if (await handleTypewriter(req, res)) return;
    send(res, 404, { error: "not found" });
}).listen(HostNodeServerListenToWherePort, HostNodeServerListenToWhereIP, () => {
    console.log("listening on http://" + HostNodeServerListenToWhereIP + ":" + HostNodeServerListenToWherePort);
});