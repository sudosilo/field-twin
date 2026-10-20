// Copies the Field Twin tracking code into the generated Android project and adds its permissions,
// the tracking service, and the fieldtwin:// link that odometer prompts use.
const fs = require("fs");
const path = require("path");
const root = "src-tauri/gen/android/app/src/main";
const pkgDir = path.join(root, "java", "app", "fieldtwin", "teal");
if (!fs.existsSync(pkgDir)) throw new Error("generated package folder not found at " + pkgDir);
for (const f of fs.readdirSync("src-tauri/android")) {
  if (f.endsWith(".kt")) fs.copyFileSync(path.join("src-tauri/android", f), path.join(pkgDir, f));
}
const mf = path.join(root, "AndroidManifest.xml");
let s = fs.readFileSync(mf, "utf8");
const perms = [
  "android.permission.ACCESS_FINE_LOCATION",
  "android.permission.ACCESS_COARSE_LOCATION",
  "android.permission.FOREGROUND_SERVICE",
  "android.permission.FOREGROUND_SERVICE_LOCATION",
  "android.permission.POST_NOTIFICATIONS",
  "android.permission.CAMERA"
];
let add = "";
for (const p of perms) if (!s.includes('"' + p + '"')) add += '    <uses-permission android:name="' + p + '" />\n';
s = s.replace("<application", add + "    <application");
if (!s.includes("FieldTwinTrackService")) {
  s = s.replace("</application>",
    '    <service android:name=".FieldTwinTrackService" android:exported="false" android:foregroundServiceType="location" />\n    </application>');
}
if (!s.includes('android:scheme="fieldtwin"')) {
  const filter = '<intent-filter>\n                <action android:name="android.intent.action.VIEW" />\n                <category android:name="android.intent.category.DEFAULT" />\n                <category android:name="android.intent.category.BROWSABLE" />\n                <data android:scheme="fieldtwin" />\n            </intent-filter>\n            ';
  const i = s.indexOf("</activity>");
  if (i < 0) throw new Error("no activity in manifest");
  s = s.slice(0, i) + filter + s.slice(i);
}
if (!s.includes("android:launchMode")) s = s.replace('android:name=".MainActivity"', 'android:name=".MainActivity"\n            android:launchMode="singleTask"');
fs.writeFileSync(mf, s);
console.log("Field Twin Android project patched");
