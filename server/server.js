import http from "node:http";
import fs from "fs";
import * as HelperMethods from "./HelperMethods.js";


const data = () => JSON.parse(fs.readFileSync("./data.json", "utf8"));
const save = (obj) => fs.writeFileSync("./data.json", JSON.stringify(obj, null, 2));

function readBody(req) {
    return new Promise((resolve, reject) => {
        let raw = "";
        req.on("data", (chunk) => (raw += chunk));
        req.on("end", () => resolve(raw));
        req.on("error", reject);
    });
}

http.createServer(async (req, res) => {
    res.setHeader("Content-Type", "application/json");

    if (req.method === "GET" && req.url === "/tasks") {
        const raw = await readBody(req);
        const body = JSON.parse(raw || "{}");
        console.log(HelperMethods.validateTask(body));
        res.end(JSON.stringify(data()));
        return;
    }

    if (req.method === "POST" && req.url === "/tasks") {
        try {
            const raw = await readBody(req);
            const body = JSON.parse(raw || "{}");
            const id = (body.id ||"").trim();
            const title = (body.title || "").trim();
            const duration = (body.duration ||"").trim();
            const state = (body.state || "").trim();
            const placedDate = (body.placed_date || "").trim();
            const placedTime = (body.placed_time || "").trim();
            const deadlineDate = (body.deadline_date || "").trim();
            const deadlineTime = (body.deadline_time || "").trim();
            const lifecycle = (body.lifecycle || "").trim();
            const workflow = (body.workflow || "").trim();
            const placementUpdatedAt = (body.placementUpdatedAt || "").trim();
            const lastUpdatedAt = (body.lastUpdatedAt || "").trim();
            const userId = (body.userId || "").trim();

            const bodyValidateMsg = HelperMethods.validateTask(body)
            if(bodyValidateMsg !== "OK!"){
                res.statusCode = 400;
                res.end(JSON.stringify(({bodyValidateMsg})));
            };

            const db = data();
            const task = {
                id: Date.now().toString(),
                title,
                state: body.state || "Flexible",
            };
            db.tasks.push(task);

            save(db);
            res.statusCode = 201;
            res.end(JSON.stringify(task));
        } catch (e) {
            res.statusCode = 400;
            res.end(JSON.stringify({ error: "invalid json" }));
        }
        return;
    }

    res.statusCode = 404;
    res.end(JSON.stringify({ error: "not found" }));
}).listen(3456, "127.0.0.1", () => {
    console.log("listening on http://127.0.0.1:3456");
});