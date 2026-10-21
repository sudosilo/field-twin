// Finds which Vercel account on this phone owns field-twin-teal.vercel.app. If this phone has it,
// ~/field-twin gets pointed at that original project. If this phone owns the extra gold project
// made by mistake, that project is deleted. Exit code 0 means this folder is linked to the original.
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
if (!token) { console.log("This phone has no Vercel login. Nothing was changed."); process.exit(3); }

const api = async (p, scope, method = "GET") => {
  const q = scope ? (p.includes("?") ? "&" : "?") + "teamId=" + scope : "";
  const r = await fetch("https://api.vercel.com" + p + q, { method, headers: { Authorization: "Bearer " + token } });
  let j = {}; try { j = JSON.parse(await r.text()); } catch (e) {}
  return { ok: r.ok, status: r.status, j };
};
const teams = await api("/v2/teams");
const scopes = [null, ...((teams.j.teams || []).map(t => t.id))];
async function find(host) {
  for (const s of scopes) {
    const d = await api("/v13/deployments/" + host, s);
    if (d.ok && d.j.projectId) return { scope: s, projectId: d.j.projectId };
  }
  return null;
}

const orig = await find("field-twin-teal.vercel.app");
const extra = await find("field-twin-teal-gold.vercel.app");

if (extra && (!orig || extra.projectId !== orig.projectId)) {
  const p = await api("/v9/projects/" + extra.projectId, extra.scope);
  const del = await api("/v9/projects/" + extra.projectId, extra.scope, "DELETE");
  console.log(del.ok ? "Deleted the extra project " + (p.j.name || "") + " and its gold address." : "Could not delete the extra project (" + del.status + ").");
}

if (!orig) { console.log("The original field-twin-teal.vercel.app project is not on this phone's Vercel account."); process.exit(3); }
const p = await api("/v9/projects/" + orig.projectId, orig.scope);
const user = await api("/v2/user");
const org = orig.scope || (user.j.user && (user.j.user.defaultTeamId || user.j.user.id));
fs.mkdirSync(path.join(home, "field-twin/.vercel"), { recursive: true });
fs.writeFileSync(path.join(home, "field-twin/.vercel/project.json"), JSON.stringify({ projectId: orig.projectId, orgId: org, projectName: p.j.name }));
try { fs.rmSync(path.join(home, "field-twin/.env.local")); } catch (e) {}
console.log("Found the original project " + p.j.name + ". ~/field-twin now points at it.");
process.exit(0);
