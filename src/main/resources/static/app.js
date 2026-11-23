const form = document.querySelector('#send-form');
const result = document.querySelector('#result');
const rows = document.querySelector('#rows');
const buttons = [...form.querySelectorAll('button')];

async function request(path, options) {
  const response = await fetch(path, options);
  const body = await response.json();
  if (!response.ok) throw new Error(body.detail || `요청 실패 (${response.status})`);
  return { status: response.status, body };
}

async function send(sync) {
  if (!form.reportValidity()) return;
  const data = Object.fromEntries(new FormData(form));
  data.demoFailures = Number(data.demoFailures);
  data.demoDelayMs = Number(data.demoDelayMs);
  buttons.forEach(button => button.disabled = true);
  result.classList.remove('error');
  result.textContent = '요청 중...';
  const start = performance.now();
  try {
    const response = await request(sync ? '/api/demo/send-sync' : '/api/notifications', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data)
    });
    const elapsed = Math.round(performance.now() - start);
    result.textContent = `${sync ? '동기 발송 완료' : '비동기 접수 완료'} · HTTP ${response.status} · 응답 ${elapsed}ms\n` +
      (sync ? '테스트 발송기가 끝날 때까지 기다린 응답입니다.' : `알림 ID: ${response.body.id}\n접수 시점 응답입니다. 최종 발송 상태는 목록에서 확인합니다.`);
  } catch (error) {
    result.textContent = error.message;
    result.classList.add('error');
  } finally {
    buttons.forEach(button => button.disabled = false);
    await refresh();
  }
}

// 입력한 메시지는 HTML로 해석하지 않는다.
function cell(text, className) {
  const td = document.createElement('td');
  td.textContent = text;
  if (className) td.className = className;
  return td;
}

let refreshing = false;
async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    const [notifications, queue] = await Promise.all([request('/api/notifications'), request('/api/queue')]);
    rows.replaceChildren();
    for (const item of notifications.body) {
      const tr = document.createElement('tr');
      const time = new Date(item.createdAt).toLocaleTimeString('ko-KR', { hour12: false });
      tr.append(cell(time), cell(item.recipient), cell(item.message, 'message'),
        cell(item.status, `status ${item.status}`), cell(item.attempts));
      const detailCell = document.createElement('td');
      const details = document.createElement('details');
      const summary = document.createElement('summary');
      summary.textContent = item.id.slice(0, 8);
      details.append(summary);
      for (const text of [item.id, item.lastError || '오류 없음', `지연 ${item.demoDelayMs}ms / 실패 설정 ${item.demoFailures}회`]) {
        const p = document.createElement('p');
        p.textContent = text;
        details.append(p);
      }
      detailCell.append(details);
      tr.append(detailCell);
      rows.append(tr);
    }
    if (!notifications.body.length) {
      const tr = document.createElement('tr');
      const td = cell('접수된 알림이 없습니다.');
      td.colSpan = 6;
      tr.append(td);
      rows.append(tr);
    }
    document.querySelector('#queue').textContent = `미확인 작업 ${queue.body.pending}개 · 최종 실패 기록 ${queue.body.deadLetters}개`;
    document.querySelector('#connection').textContent = '연결됨';
  } catch (error) {
    document.querySelector('#connection').textContent = `갱신 실패: ${error.message}`;
  } finally {
    refreshing = false;
  }
}

form.addEventListener('submit', event => { event.preventDefault(); send(false); });
document.querySelector('#sync').addEventListener('click', () => send(true));
document.querySelector('#refresh').addEventListener('click', refresh);
refresh();
setInterval(refresh, 1000);
