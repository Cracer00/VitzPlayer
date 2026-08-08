package com.vitz.music.admin

/**
 * Стили и скрипт админки лежат строками и отдаются своими маршрутами: сторонних CDN нет,
 * фронтового тулчейна — тоже. Всё интерактивное здесь — загрузка файлов и опрос очереди.
 */
val ADMIN_CSS = """
:root {
  color-scheme: dark;
  --bg: #14161a;
  --panel: #1c1f26;
  --line: #2a2f3a;
  --text: #e6e8ec;
  --muted: #9aa3b2;
  --accent: #5aa2ff;
  --danger: #ff6b6b;
  --ok: #4cc38a;
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--bg); color: var(--text);
  font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
}
a { color: var(--accent); text-decoration: none; }
a:hover { text-decoration: underline; }
header {
  display: flex; gap: 20px; align-items: center; flex-wrap: wrap;
  padding: 14px 24px; border-bottom: 1px solid var(--line); background: var(--panel);
}
header .brand { font-weight: 600; letter-spacing: .02em; }
header nav { display: flex; gap: 16px; flex-wrap: wrap; }
header .spacer { flex: 1; }
main { padding: 24px; max-width: 1100px; margin: 0 auto; }
h1 { font-size: 22px; margin: 0 0 18px; }
h2 { font-size: 17px; margin: 28px 0 12px; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; }
.card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 14px; }
.card .value { font-size: 26px; font-weight: 600; }
.card .label { color: var(--muted); font-size: 13px; }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--line); vertical-align: middle; }
th { color: var(--muted); font-weight: 500; font-size: 13px; }
tr:hover td { background: rgba(255,255,255,.02); }
input, select, textarea, button {
  font: inherit; color: var(--text); background: #12141a;
  border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px;
}
input:focus, select:focus, textarea:focus { outline: 2px solid var(--accent); outline-offset: -1px; }
button { cursor: pointer; background: #232833; }
button:hover { background: #2b313d; }
button.primary { background: var(--accent); color: #08101f; border-color: transparent; font-weight: 600; }
button.danger { color: var(--danger); }
form.inline { display: inline; }
.row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.muted { color: var(--muted); }
.pill { font-size: 12px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--line); }
.pill.done { color: var(--ok); border-color: rgba(76,195,138,.4); }
.pill.failed, .pill.needs_attention { color: var(--danger); border-color: rgba(255,107,107,.4); }
.pill.running { color: var(--accent); border-color: rgba(90,162,255,.4); }
#dropzone {
  border: 2px dashed var(--line); border-radius: 12px; padding: 42px; text-align: center;
  color: var(--muted); background: var(--panel);
}
#dropzone.hot { border-color: var(--accent); color: var(--text); }
.progress { height: 6px; background: #0e1015; border-radius: 999px; overflow: hidden; width: 180px; }
.progress > div { height: 100%; background: var(--accent); width: 0; transition: width .15s linear; }
.log { white-space: pre-wrap; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; color: var(--muted); }
.cover { width: 40px; height: 40px; border-radius: 6px; object-fit: cover; background: #0e1015; }
""".trimIndent()

val ADMIN_JS = """
(function () {
  const zone = document.getElementById('dropzone');
  const picker = document.getElementById('picker');
  const list = document.getElementById('queue');
  if (!zone) return;

  const csrf = document.body.dataset.csrf;
  let pending = [];
  let active = 0;
  const MAX_PARALLEL = 2;

  function row(file) {
    const li = document.createElement('li');
    li.className = 'row';
    li.style.padding = '6px 0';
    li.innerHTML = '<span style="flex:1">' + file.name.replace(/</g, '&lt;') + '</span>' +
      '<span class="progress"><div></div></span><span class="muted" style="width:120px;text-align:right"></span>';
    list.appendChild(li);
    return { bar: li.querySelector('.progress > div'), status: li.querySelector('span:last-child') };
  }

  function upload(file) {
    const ui = row(file);
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/admin/upload?name=' + encodeURIComponent(file.name));
    xhr.setRequestHeader('X-CSRF', csrf);
    xhr.upload.onprogress = function (e) {
      if (e.lengthComputable) ui.bar.style.width = (e.loaded / e.total * 100) + '%';
    };
    xhr.onload = function () {
      ui.bar.style.width = '100%';
      ui.status.textContent = xhr.status === 202 ? 'в очереди' : ('ошибка ' + xhr.status);
      next();
      refreshJobs();
    };
    xhr.onerror = function () { ui.status.textContent = 'сеть недоступна'; next(); };
    xhr.send(file);
  }

  function next() {
    active--;
    while (active < MAX_PARALLEL && pending.length) { active++; upload(pending.shift()); }
  }

  function add(files) {
    for (const file of files) pending.push(file);
    while (active < MAX_PARALLEL && pending.length) { active++; upload(pending.shift()); }
  }

  zone.addEventListener('click', () => picker.click());
  picker.addEventListener('change', () => add(picker.files));
  ['dragenter', 'dragover'].forEach(name => zone.addEventListener(name, e => {
    e.preventDefault(); zone.classList.add('hot');
  }));
  ['dragleave', 'drop'].forEach(name => zone.addEventListener(name, e => {
    e.preventDefault(); zone.classList.remove('hot');
  }));
  zone.addEventListener('drop', e => add(e.dataTransfer.files));

  function refreshJobs() {
    fetch('/admin/jobs/fragment', { headers: { 'Accept': 'text/html' } })
      .then(r => r.text())
      .then(html => { document.getElementById('jobs').innerHTML = html; });
  }

  setInterval(refreshJobs, 2500);
  refreshJobs();
})();
""".trimIndent()
