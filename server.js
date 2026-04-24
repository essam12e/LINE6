const http = require("http");
const fs = require("fs");
const path = require("path");

const root = __dirname;
const port = process.env.PORT || 4173;

const types = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
};

http
  .createServer((req, res) => {
    const urlPath = req.url === "/" ? "/index.html" : req.url.split("?")[0];
    const safePath = path.normalize(path.join(root, urlPath));

    if (!safePath.startsWith(root)) {
      res.writeHead(403);
      return res.end("Forbidden");
    }

    fs.readFile(safePath, (err, data) => {
      if (err) {
        res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
        return res.end("Not found");
      }

      const ext = path.extname(safePath).toLowerCase();
      res.writeHead(200, {
        "Content-Type": types[ext] || "application/octet-stream",
      });
      res.end(data);
    });
  })
  .listen(port, () => {
    console.log(`Line6 site running on http://localhost:${port}`);
  });
