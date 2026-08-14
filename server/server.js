const http = require("http");
const fs = require("fs");

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
        res.end(JSON.stringify(data().tasks));
        return;
    }

    if (req.method === "POST" && req.url === "/tasks") {
        try {
            const raw = await readBody(req);
            const body = JSON.parse(raw || "{}");
            const title = (body.title || "").trim();
            if (!title) {
                res.statusCode = 400;
                res.end(JSON.stringify({ error: "title required" }));
                return;
            }
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