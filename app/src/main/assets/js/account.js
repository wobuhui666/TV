'use strict';

(() => {
    const translations = {
        'zh-CN': {
            accounts: '日历账号', verificationCode: '验证码', authorize: '前往 Trakt 授权 ↗',
            back: '返回设置', title: '连接电视账号', loading: '正在连接电视…',
            create: '去 Bangumi 生成令牌 ↗', token: '个人访问令牌', placeholder: '回到这里粘贴令牌',
            connect: '连接电视', help: '请保持电视上的连接窗口打开。令牌只发送到这台电视。', retry: '重试连接',
            ready: '在官网生成个人访问令牌，再回到此页粘贴。', pending: '正在校验账号并同步追剧…',
            connected: '已连接，电视正在刷新追更日历。可以关闭此页。',
            invalid_token: '令牌无效或已过期，请重新生成后粘贴。', failed: '暂时无法连接 Bangumi，请重新粘贴令牌后重试。',
            offline: '无法连接电视。请确认手机和电视在同一网络，再重试。',
            ended: '连接窗口已关闭或二维码已过期，请在电视上重新连接并扫码。',
            missing: '请先在电视打开「日历 → Trakt 或 Bangumi → 账号 → 连接账号」，再扫描二维码。'
        },
        'zh-TW': {
            accounts: '日曆帳號', verificationCode: '驗證碼', authorize: '前往 Trakt 授權 ↗',
            back: '返回設定', title: '連接電視帳號', loading: '正在連接電視…',
            create: '去 Bangumi 產生權杖 ↗', token: '個人存取權杖', placeholder: '回到這裡貼上權杖',
            connect: '連接電視', help: '請保持電視上的連接視窗開啟。權杖只傳送到這台電視。', retry: '重試連接',
            ready: '在官網產生個人存取權杖，再回到此頁貼上。', pending: '正在驗證帳號並同步追劇…',
            connected: '已連接，電視正在重新整理追更日曆。可以關閉此頁。',
            invalid_token: '權杖無效或已過期，請重新產生後貼上。', failed: '暫時無法連接 Bangumi，請重新貼上權杖後重試。',
            offline: '無法連接電視。請確認手機和電視在同一網路，再重試。',
            ended: '連接視窗已關閉或 QR Code 已過期，請在電視上重新連接並掃碼。',
            missing: '請先在電視開啟「日曆 → Trakt 或 Bangumi → 帳號 → 連接帳號」，再掃描 QR Code。'
        },
        en: {
            accounts: 'Calendar accounts', verificationCode: 'Verification code', authorize: 'Authorize on Trakt ↗',
            back: 'Back to settings', title: 'Connect your TV account', loading: 'Connecting to your TV…',
            create: 'Create a Bangumi token ↗', token: 'Personal access token', placeholder: 'Return here and paste your token',
            connect: 'Connect TV', help: 'Keep the connection dialog open on your TV. The token is sent only to this TV.', retry: 'Retry connection',
            ready: 'Create a personal access token on Bangumi, then return here to paste it.', pending: 'Checking your account and syncing followed titles…',
            connected: 'Connected. Your TV is refreshing the calendar. You can close this page.',
            invalid_token: 'This token is invalid or expired. Please create and paste a new one.', failed: 'Unable to reach Bangumi. Paste your token again to retry.',
            offline: 'Unable to reach the TV. Check that both devices are on the same network and retry.',
            ended: 'The connection dialog was closed or the QR code expired. Reconnect on your TV and scan again.',
            missing: 'On your TV, open Calendar → Trakt or Bangumi → Account → Connect account, then scan the QR code.'
        }
    };
    const traktTranslations = {
        'zh-CN': { create: 'Trakt 应用管理 ↗', token: 'Trakt Client ID', placeholder: '粘贴应用的 Client ID',
            ready: '填写 Trakt 应用的 Client ID，接着前往官网授权。', pending: '正在申请 Trakt 授权码…',
            authorizing: '复制下方验证码，在 Trakt 官网输入并同意授权。电视会自动连接。',
            invalid_token: 'Client ID 格式不正确，请检查后重试。', failed: '未能申请 Trakt 授权码，请检查 Client ID 和网络后重试。',
            help: '请保持电视上的连接窗口打开，无需 Client Secret。', connect: '继续授权' },
        'zh-TW': { create: 'Trakt 應用管理 ↗', token: 'Trakt Client ID', placeholder: '貼上應用的 Client ID',
            ready: '填寫 Trakt 應用的 Client ID，接著前往官網授權。', pending: '正在申請 Trakt 授權碼…',
            authorizing: '複製下方驗證碼，在 Trakt 官網輸入並同意授權。電視會自動連接。',
            invalid_token: 'Client ID 格式不正確，請檢查後重試。', failed: '未能申請 Trakt 授權碼，請檢查 Client ID 和網路後重試。',
            help: '請保持電視上的連接視窗開啟，無需 Client Secret。', connect: '繼續授權' },
        en: { create: 'Manage Trakt apps ↗', token: 'Trakt Client ID', placeholder: 'Paste your application Client ID',
            ready: 'Enter your Trakt application Client ID, then authorize on the official website.', pending: 'Requesting a Trakt authorization code…',
            authorizing: 'Copy this code, enter it on Trakt and approve access. Your TV will connect automatically.',
            invalid_token: 'Invalid Client ID format. Check it and try again.', failed: 'Unable to request a Trakt code. Check your Client ID and network, then retry.',
            help: 'Keep the connection dialog open on your TV. No Client Secret is needed.', connect: 'Continue to authorization' }
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
    document.title = strings.accounts;

    const status = document.querySelector('#account-status');
    const form = document.querySelector('#account-form');
    const input = document.querySelector('#account-token');
    const submit = document.querySelector('#account-submit');
    const retry = document.querySelector('#account-retry');
    const authorization = document.querySelector('#account-authorization');
    const authorize = document.querySelector('#account-authorize');
    const userCode = document.querySelector('#account-user-code');
    const storageKey = 'tv-calendar-pairing';
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
    let provider = 'bangumi';
    const t = key => (provider === 'trakt' ? (traktTranslations[language] || traktTranslations['zh-CN'])[key] : undefined) || strings[key];

    function updateProvider(value) {
        if (provider !== value) input.value = '';
        provider = value;
        const name = provider === 'trakt' ? 'Trakt' : 'Bangumi';
        document.querySelector('#account-provider').textContent = name;
        document.title = name + ' · ' + strings.title;
        document.querySelector('#account-input-label').textContent = t('token');
        document.querySelector('#account-help').textContent = t('help');
        const create = document.querySelector('.account-token-link');
        create.textContent = t('create');
        create.href = provider === 'trakt' ? 'https://developer.trakt.tv/apps' : 'https://next.bgm.tv/demo/access-token';
        input.type = provider === 'trakt' ? 'text' : 'password';
        input.maxLength = provider === 'trakt' ? 256 : 2048;
        input.placeholder = t('placeholder');
        submit.textContent = t('connect');
    }

    function render(next) {
        state = next;
        status.dataset.state = next;
        status.textContent = t(next);
        authorization.hidden = next !== 'authorizing';
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
        if (!stopped && !document.hidden) timer = setTimeout(() => request(), ['pending', 'authorizing'].includes(state) ? 1500 : 5000);
    }

    async function request(token) {
        if (inFlight || stopped) return;
        inFlight = true;
        submit.disabled = true;
        clearTimeout(timer);
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 10000);
        try {
            const options = { headers: { 'X-Calendar-Pairing': code }, cache: 'no-store', signal: controller.signal, credentials: 'omit' };
            if (token !== undefined) {
                options.method = 'POST';
                options.headers['Content-Type'] = 'application/json';
                options.body = JSON.stringify(provider === 'trakt' ? { clientId: token } : { token });
            }
            const response = await fetch('/account/session', options);
            if (response.status === 403 || response.status === 410) { render('ended'); return; }
            if (response.status === 400) { render('invalid_token'); return; }
            if (!response.ok) throw new Error('Connection failed');
            const body = await response.json();
            if (!['bangumi', 'trakt'].includes(body.provider) ||
                !['ready', 'pending', 'authorizing', 'connected', 'invalid_token', 'failed'].includes(body.state) ||
                !Number.isFinite(body.remainingMillis) || body.remainingMillis <= 0) throw new Error('Invalid response');
            if (body.state === 'authorizing') {
                const url = new URL(body.verificationUrl);
                if (body.provider !== 'trakt' || url.protocol !== 'https:' || url.username || url.password ||
                    !['auth.trakt.tv', 'trakt.tv', 'app.trakt.tv'].includes(url.hostname) ||
                    typeof body.userCode !== 'string' || !body.userCode.trim()) throw new Error('Invalid Trakt authorization');
                authorize.href = url.href;
                userCode.value = body.userCode;
            }
            updateProvider(body.provider);
            render(body.state);
        } catch (_) { render('offline'); }
        finally {
            clearTimeout(timeout);
            inFlight = false;
            submit.disabled = !['ready', 'invalid_token', 'failed'].includes(state);
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
    userCode.addEventListener('click', () => userCode.select());
    document.addEventListener('visibilitychange', () => {
        clearTimeout(timer);
        if (!document.hidden) request();
    });
    if (/^[a-f0-9]{48}$/.test(code)) request();
    else render('missing');
})();
