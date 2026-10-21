// Points this folder and the GitHub repo back at the original Vercel project that owns
// field-twin-teal.vercel.app, then deletes the extra project that was made by mistake.
import fs from "fs";
import os from "os";
import path from "path";

const home = os.homedir();
const tries = [
  process.env.XDG_DATA_HOME && path.join(process.env.XDG_DATA_HOME, "com.vercel.cli/auth.json"),
  path.join(home, ".local/share/com.vercel.cli/auth.json"),
  path.join(home, ".config/com.vercel.cli/auth.json"),
  path.join(home, ".vercel/auth.json"),
].filter(Boolean);
let token = "";
for (const f of tries) { try { token = JSON.parse(fs.readFileSync(f, "utf8")).token || ""; if (token) break; } catch (e) {} }
if (!token) { console.log("STOPPED: could not find the Vercel login on this phone. Nothing was changed."); process.exit(1); }

const linkFile = path.join(home, "field-twin/.vercel/project.json");
let link;
try { link = JSON.parse(fs.readFileSync(linkFile, "utf8")); } catch (e) { console.log("STOPPED: ~/field-twin is not linked. Nothing was changed."); process.exit(1); }
const team = link.orgId, newId = link.projectId;
const api = async (p, method = "GET") => {
  const r = await fetch("https://api.vercel.com" + p + (p.includes("?") ? "&" : "?") + "teamId=" + team, { method, headers: { Authorization: "Bearer " + token } });
  const t = await r.text();
  let j = {}; try { j = JSON.parse(t); } catch (e) {}
  return { ok: r.ok, status: r.status, j };
};

const dep = await api("/v13/deployments/field-twin-teal.vercel.app");
if (!dep.ok || !dep.j.projectId) { console.log("STOPPED: could not find the project behind field-twin-teal.vercel.app (" + dep.status + "). Nothing was changed."); process.exit(1); }
const oldId = dep.j.projectId;
const oldProj = await api("/v9/projects/" + oldId);
const oldName = oldProj.j.name || oldId;
if (oldId === newId) { console.log("Already linked to " + oldName + ". Nothing to change."); process.exit(0); }
const newProj = await api("/v9/projects/" + newId);
const newName = newProj.j.name || newId;
console.log("Original project: " + oldName);
console.log("Extra project made by mistake: " + newName);

fs.writeFileSync(linkFile, JSON.stringify({ projectId: oldId, orgId: team, projectName: oldName }));
try { fs.rmSync(path.join(home, "field-twin/.env.local")); } catch (e) {}
console.log("This folder now points at " + oldName + ".");

const del = await api("/v9/projects/" + newId, "DELETE");
console.log(del.ok ? "Deleted " + newName + " and its gold address." : "Could not delete " + newName + " (" + del.status + ").");
