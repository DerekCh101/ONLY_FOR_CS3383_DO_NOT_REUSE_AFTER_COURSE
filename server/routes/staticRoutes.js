import fs from "node:fs";
import { pathname } from "../lib/httpUtil.js";

export function handleStatic(req, res) {
    const path = pathname(req);
    if (req.method !== "GET") return false;

    if (path === "/" || path === "/index.html") {
        res.setHeader("Content-Type", "text/html; charset=utf-8");
        res.end(fs.readFileSync("./index.html", "utf8"));
        return true;
    }

    if (path === "/app.js") {
        res.setHeader("Content-Type", "text/javascript; charset=utf-8");
        res.end(fs.readFileSync("./app.js", "utf8"));
        return true;
    }

    return false;
}