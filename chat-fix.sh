set -e
cd ~/field-twin
cat > $HOME/.ft-chat.js <<'CHATJS'
// Field Chat: the chat shared by Field Twin and Delivery Twin (Driver Chat there), on the twin_chat tables in
// this app's own database, signed in with the same account as the rest of Field Twin. Nothing reaches Cat Command Chat.
// With alerts on, a message from someone else flashes the screen in rainbow colors for a moment.
let fcMember = false, fcChan = null, fcMsgs = [], fcNames = {}, fcAlerts = false, fcUnread = 0;
try { fcAlerts = localStorage.getItem('fcAlerts') === '1'; } catch (e) {}
async function fcStart() {
  if (!(sb && cloudUser)) return false;
  try {
    const r = await sb.rpc('twin_chat_member');
    fcMember = r.data === true;
    if (fcMember) { await fcLoadNames(); fcListen(); }
    return true;
  } catch (e) { return false; }
}
async function fcLoadNames() {
  try { const n = await sb.from('twin_chat_names').select('id,name,color'); (n.data || []).forEach(x => { fcNames[x.id] = x; }); } catch (e) {}
}
function fcName(id) { return id === (cloudUser && cloudUser.id) ? 'You' : ((fcNames[id] && fcNames[id].name) || 'Someone'); }
function fcListen() {
  if (fcChan || !sb) return;
  fcChan = sb.channel('twin-chat')
    .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'twin_chat_messages' }, p => {
      const m = p.new;
      if (!m || fcMsgs.some(x => x.id === m.id)) return;
      fcMsgs.push(m);
      if (!fcNames[m.sender]) fcLoadNames();
      const mine = cloudUser && m.sender === cloudUser.id;
      const open = document.getElementById('fcList');
      if (open) fcDraw(); else if (!mine) { fcUnread++; fcBadge(); }
      if (!mine && fcAlerts) fcFlash();
    })
    .subscribe();
}
function fcFlash() {
  const old = document.getElementById('fcStar');
  if (old) old.remove();
  const d = document.createElement('div');
  d.id = 'fcStar';
  document.body.appendChild(d);
  setTimeout(() => d.remove(), 1300);
  if (typeof ftcSend === 'function') ftcSend('F');
}
function fcBadge() {
  const b = document.getElementById('fieldChatOpen');
  if (b) b.textContent = fcUnread ? 'Open Field Chat (' + fcUnread + ' new)' : 'Open Field Chat';
}
async function fcSetAlerts(on) {
  fcAlerts = on;
  try { localStorage.setItem('fcAlerts', on ? '1' : '0'); } catch (e) {}
  setToggle('fieldChatBtn', on);
  if (on) { await fcStart(); if (!fcMember) setStatus('open Field Chat once to join with an invite code'); }
}
function fcDraw() {
  const list = document.getElementById('fcList');
  if (!list) return;
  list.innerHTML = fcMsgs.filter(m => !m.deleted_at).slice(-80).map(m => '<div class="fc-msg' + (cloudUser && m.sender === cloudUser.id ? ' mine' : '') + '"><b>' + escapeHtml(fcName(m.sender)) + '</b> <span class="meta-line">' + new Date(m.created_at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) + '</span><div>' + escapeHtml(m.body) + '</div></div>').join('') || '<div class="meta-line">No messages yet.</div>';
  list.scrollTop = list.scrollHeight;
}
async function fcOpen() {
  const panel = document.getElementById('panel');
  panel.innerHTML = '<h2>Field Chat</h2><div class="meta-line" id="fcMsg">Connecting.</div>';
  panel.classList.add('open');
  if (!(sb && cloudUser)) { document.getElementById('fcMsg').textContent = 'Sign in to Field Twin first, under Account and profile.'; return; }
  if (!(await fcStart())) { document.getElementById('fcMsg').textContent = 'Could not reach the chat. Check the signal and try again.'; return; }
  fcUnread = 0; fcBadge();
  if (!fcMember) {
    panel.innerHTML = '<h2>Field Chat</h2><div class="meta-line">Field Chat is shared by Field Twin and Delivery Twin and nothing else. Ask a member for an invite code.</div>' +
      '<input type="text" id="fcCode" autocomplete="off" placeholder="Invite code"><input type="text" id="fcNameIn" autocomplete="off" placeholder="Your name in the chat">' +
      '<div class="meta-line" id="fcErr"></div><div class="panel-actions"><button id="fcJoin">Join</button><button class="secondary" id="fcClose">Close</button></div>';
    document.getElementById('fcClose').addEventListener('click', closePanel);
    document.getElementById('fcJoin').addEventListener('click', async () => {
      const code = document.getElementById('fcCode').value.trim();
      const name = document.getElementById('fcNameIn').value.trim();
      const err = document.getElementById('fcErr');
      if (!code || !name) { err.textContent = 'Type the invite code and your name.'; return; }
      err.textContent = 'Joining.';
      const r = await sb.rpc('twin_chat_join', { code: code });
      if (r.error || r.data !== true) { err.textContent = 'That code did not work. Check it with the person who invited you.'; return; }
      await sb.from('twin_chat_names').upsert({ id: cloudUser.id, name: name, color: '#0b4ea2' });
      fcMember = true;
      await fcLoadNames();
      fcListen();
      fcOpen();
    });
    return;
  }
  panel.innerHTML = '<h2>Field Chat</h2><div id="fcList" style="max-height:46vh;overflow-y:auto;margin:6px 0;"></div>' +
    '<textarea id="fcText" rows="2" placeholder="Message the field crew"></textarea>' +
    '<div class="panel-actions"><button id="fcSend">Send</button><button class="secondary" id="fcInvite">Invite code</button><button class="secondary" id="fcClose">Close</button></div>' +
    '<div class="meta-line" id="fcInviteOut"></div>';
  document.getElementById('fcClose').addEventListener('click', closePanel);
  document.getElementById('fcInvite').addEventListener('click', async () => {
    const out = document.getElementById('fcInviteOut');
    const r = await sb.rpc('twin_chat_invite', { p_max_uses: 1, p_days: 7 });
    out.textContent = r.error ? 'Could not make a code, ' + r.error.message : 'Invite code, good for one person for seven days: ' + r.data;
  });
  const send = async () => {
    const t = document.getElementById('fcText');
    const body = t.value.trim();
    if (!body) return;
    t.value = '';
    const r = await sb.from('twin_chat_messages').insert({ body: body }).select().single();
    if (r.error) { t.value = body; setStatus('message did not send, ' + r.error.message); return; }
    if (r.data && !fcMsgs.some(x => x.id === r.data.id)) { fcMsgs.push(r.data); fcDraw(); }
  };
  document.getElementById('fcSend').addEventListener('click', send);
  try {
    const r = await sb.from('twin_chat_messages').select('*').is('deleted_at', null).order('id', { ascending: false }).limit(80);
    if (!r.error) fcMsgs = (r.data || []).reverse();
  } catch (e) {}
  fcDraw();
}
setTimeout(() => { setToggle('fieldChatBtn', fcAlerts); if (fcAlerts) fcStart(); }, 4000);
CHATJS
awk 'FNR==NR{code=code $0 "\n"; next} /^\/\/ Field Chat: a room inside the Cat Command Chat backend/{skip=1; printf "%s", code} skip && /^\/\/ GO picks and arrivals raise a place/{skip=0} !skip{print}' $HOME/.ft-chat.js index.html > $HOME/.ft-index.html && mv $HOME/.ft-index.html index.html
rm -f $HOME/.ft-chat.js
sed -i "s|^  html += '<div>Field Chat is a chat room for your team, run on the same backend as Cat Command Chat.*$|  html += '<div>Field Chat is the chat shared by Field Twin and Delivery Twin, where it is called Driver Chat. It runs on this app\\\\'s own database and never reaches Cat Command Chat. Join with an invite code from a member, and members make codes with the Invite code button. With Field Chat alerts on, a new message from someone else flashes the screen in rainbow colors for a moment, on the full map, the floating window and the companion screen alike.</div>';|" index.html
