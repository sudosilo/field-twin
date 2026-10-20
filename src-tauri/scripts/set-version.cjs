// Stamps the build number into the app version so every release installs as an update.
const fs = require("fs");
const f = "src-tauri/tauri.conf.json";
const c = JSON.parse(fs.readFileSync(f, "utf8"));
c.version = process.argv[2];
fs.writeFileSync(f, JSON.stringify(c, null, 2) + "\n");
console.log("Version set to " + c.version);
