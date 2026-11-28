'use strict';

(() => {
    const translations = {
        'zh-CN': {
            back: '返回设置', title: '连接电视账号', loading: '正在连接电视…',
            create: '去 Bangumi 生成令牌 ↗', token: '个人访问令牌', placeholder: '回到这里粘贴令牌',
            connect: '连接电视', help: '请保持电视上的连接窗口打开。令牌只发送到这台电视。', retry: '重试连接',
            ready: '在官网生成个人访问令牌，再回到此页粘贴。', pending: '正在校验账号并同步追剧…',
            connected: '已连接，电视正在刷新追更日历。可以关闭此页。',
            invalid_token: '令牌无效或已过期，请重新生成后粘贴。', failed: '暂时无法连接 Bangumi，请重新粘贴令牌后重试。',
            offline: '无法连接电视。请确认手机和电视在同一网络，再重试。',
            ended: '连接窗口已关闭或二维码已过期，请在电视上重新连接并扫码。',
            missing: '请先在电视打开「日历 → Bangumi → 账号 → 连接账号」，再扫描二维码。'
        },
        'zh-TW': {
            back: '返回設定', title: '連接電視帳號', loading: '正在連接電視…',
            create: '去 Bangumi 產生權杖 ↗', token: '個人存取權杖', placeholder: '回到這裡貼上權杖',
            connect: '連接電視', help: '請保持電視上的連接視窗開啟。權杖只傳送到這台電視。', retry: '重試連接',
            ready: '在官網產生個人存取權杖，再回到此頁貼上。', pending: '正在驗證帳號並同步追劇…',
            connected: '已連接，電視正在重新整理追更日曆。可以關閉此頁。',
            invalid_token: '權杖無效或已過期，請重新產生後貼上。', failed: '暫時無法連接 Bangumi，請重新貼上權杖後重試。',
            offline: '無法連接電視。請確認手機和電視在同一網路，再重試。',
            ended: '連接視窗已關閉或 QR Code 已過期，請在電視上重新連接並掃碼。',
            missing: '請先在電視開啟「日曆 → Bangumi → 帳號 → 連接帳號」，再掃描 QR Code。'
        },
        en: {
            back: 'Back to settings', title: 'Connect your TV account', loading: 'Connecting to your TV…',
            create: 'Create a Bangumi token ↗', token: 'Personal access token', placeholder: 'Return here and paste your token',
            connect: 'Connect TV', help: 'Keep the connection dialog open on your TV. The token is sent only to this TV.', retry: 'Retry connection',
            ready: 'Create a personal access token on Bangumi, then return here to paste it.', pending: 'Checking your account and syncing followed titles…',
            connected: 'Connected. Your TV is refreshing the calendar. You can close this page.',
            invalid_token: 'This token is invalid or expired. Please create and paste a new one.', failed: 'Unable to reach Bangumi. Paste your token again to retry.',
            offline: 'Unable to reach the TV. Check that both devices are on the same network and retry.',
            ended: 'The connection dialog was closed or the QR code expired. Reconnect on your TV and scan again.',
            missing: 'On your TV, open Calendar → Bangumi → Account → Connect account, then scan the QR code.'
        }
    };
    const preference = (key, fallback) => {
        try { return localStorage.getItem(key) || fallback; } catch (_) { return fallback; }
    };
    const language = preference('tv-web-language', 'zh-CN');
    const strings = translations[language] || translations['zh-CN'];
    document.documentElement.lang = translations[language] ? language : 'zh-CN';
    const theme = preference('tv-web-theme', 'auto');
    if (['light', 'dark'].includes(theme)) document.documentElement.dataset.theme = theme;
    for (const element of document.querySelectorAll('[data-i18n]')) element.textContent = strings[element.dataset.i18n];
    document.querySelector('#account-token').placeholder = strings.placeholder;
    document.title = 'Bangumi · ' + strings.title;

    const status = document.querySelector('#account-status');
    const form = document.querySelector('#account-form');
    const input = document.querySelector('#account-token');
    const submit = document.querySelector('#account-submit');
    const retry = document.querySelector('#account-retry');
    const storageKey = 'tv-bangumi-pairing';
    const fragment = location.hash.slice(1);
    let code = /^[a-f0-9]{48}$/.test(fragment) ? fragment : '';
    try {
        if (fragment) sessionStorage.removeItem(storageKey);
        if (code) sessionStorage.setItem(storageKey, code);
        else if (!fragment) code = sessionStorage.getItem(storageKey) || '';
    } catch (_) { /* Pairing still works without browser storage. */ }
    history.replaceState(null, '', location.pathname);
    window.addEventListener('hashchange', () => {
        if (/^[a-f0-9]{48}$/.test(location.hash.slice(1))) location.reload();
    });
    let state = 'loading';
    let timer;
    let inFlight = false;
    let stopped = false;

    function render(next) {
        state = next;
        status.dataset.state = next;
        status.textContent = strings[next];
        const editable = ['ready', 'invalid_token', 'failed'].includes(next);
        form.hidden = !editable && next !== 'pending';
        input.disabled = !editable;
        submit.disabled = !editable;
        retry.hidden = next !== 'offline';
        if (['connected', 'ended', 'missing'].includes(next)) {
            stopped = true;
            input.value = '';
            code = '';
            clearTimeout(timer);
            try { sessionStorage.removeItem(storageKey); } catch (_) { /* Nothing persisted. */ }
        }
    }

    function schedule() {
        clearTimeout(timer);
        if (!stopped && !document.hidden) timer = setTimeout(() => request(), state === 'pending' ? 1500 : 5000);
    }

    async function request(token) {
        if (inFlight || stopped) return;
        inFlight = true;
        clearTimeout(timer);
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 10000);
        try {
            const options = { headers: { 'X-Calendar-Pairing': code }, cache: 'no-store', signal: controller.signal, credentials: 'omit' };
            if (token !== undefined) {
                options.method = 'POST';
                options.headers['Content-Type'] = 'application/json';
                options.body = JSON.stringify({ token });
            }
            const response = await fetch('/account/session', options);
            if (response.status === 403 || response.status === 410) { render('ended'); return; }
            if (response.status === 400) { render('invalid_token'); return; }
            if (!response.ok) throw new Error('Connection failed');
            const body = await response.json();
            if (!['ready', 'pending', 'connected', 'invalid_token', 'failed'].includes(body.state) ||
                !Number.isFinite(body.remainingMillis) || body.remainingMillis <= 0) throw new Error('Invalid response');
            render(body.state);
        } catch (_) { render('offline'); }
        finally {
            clearTimeout(timeout);
            inFlight = false;
            schedule();
        }
    }

    form.addEventListener('submit', event => {
        event.preventDefault();
        const token = input.value.trim();
        if (!token || inFlight || stopped) return;
        input.value = '';
        render('pending');
        request(token);
    });
    retry.addEventListener('click', () => request());
    document.addEventListener('visibilitychange', () => {
        clearTimeout(timer);
        if (!document.hidden) request();
    });
    if (/^[a-f0-9]{48}$/.test(code)) request();
    else render('missing');
})();
