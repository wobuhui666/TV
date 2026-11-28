// Buffer and polling adapted from FongMi/TV e296b5905 (GPL-3.0).
const LOG_MAX_LINES = 5000;
const LOG_MAX_BYTES = 512 * 1024;
const logEncoder = new TextEncoder();

function readLogPage(page, after) {
    if (!page || !Array.isArray(page.lines) || page.lines.length > 200 || !Number.isSafeInteger(page.next) || page.next < 0 || typeof page.backlog !== 'boolean' || typeof page.gap !== 'boolean' || !Number.isSafeInteger(page.remainingMillis) || page.remainingMillis < 0) throw new Error('Invalid log response');
    let previous = after === undefined ? 0 : after;
    for (const line of page.lines) {
        if (!line || !Number.isSafeInteger(line.seq) || line.seq <= previous || typeof line.text !== 'string' || logEncoder.encode(line.text).length > 16 * 1024) throw new Error('Invalid log line');
        previous = line.seq;
    }
    if (page.next !== previous || (page.backlog && !page.lines.length)) throw new Error('Invalid log cursor');
    return page;
}

class LogBuffer {
    constructor() {
        this.revision = 0;
        this.clear();
    }

    clear(markMissing = false) {
        const trimmed = markMissing && (this.trimmed || this.lines.length > 0);
        this.lines = [];
        this.bytes = 0;
        this.trimmed = trimmed;
        this.revision++;
    }

    append(lines) {
        if (!lines.length) return;
        for (const line of lines) {
            const bytes = logEncoder.encode(line.text + '\n').length;
            this.lines.push({ seq: line.seq, text: line.text, bytes });
            this.bytes += bytes;
        }
        let removed = 0;
        while (this.lines.length - removed > LOG_MAX_LINES || this.bytes > LOG_MAX_BYTES) {
            this.bytes -= this.lines[removed++].bytes;
            this.trimmed = true;
        }
        if (removed) this.lines.splice(0, removed);
        this.revision++;
    }

    text(search = '', level = '') {
        search = search.toLowerCase();
        return this.lines.filter(line => (!search || line.text.toLowerCase().includes(search)) && (!level || /^\S+\s+\S+\s+\d+\s+\d+\s+([VDIWEFS])\s/.exec(line.text)?.[1] === level)).map(line => line.text).join('\n');
    }
}


function downloadLog(blob, filename) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
}

async function shareLogReport(blob, filename) {
    if (typeof File !== 'undefined' && typeof navigator.share === 'function' && typeof navigator.canShare === 'function') {
        const file = new File([blob], filename, { type: blob.type });
        if (navigator.canShare({ files: [file] })) {
            await navigator.share({ title: t('logTitle'), files: [file] });
            return 'logShared';
        }
    }
    downloadLog(blob, filename);
    return 'logReportDownloaded';
}

class LogReader {
    constructor(update) {
        this.update = update;
        this.buffer = new LogBuffer();
        this.generation = 0;
        this.visible = true;
        this.active = false;
        this.state = 'logWaiting';
        this.cursor = undefined;
        this.remainingMillis = null;
        this.receivedAt = 0;
        this.gap = false;
        this.pending = null;
        this.timer = null;
    }

    cancel() {
        this.generation++;
        if (this.state === 'logStopping') this.state = 'logStopFailed';
        clearTimeout(this.timer);
        this.timer = null;
        if (this.pending) this.pending.abort();
        this.pending = null;
    }

    connect(code = '') {
        this.cancel();
        this.code = code;
        this.cursor = undefined;
        this.remainingMillis = null;
        this.gap = false;
        this.buffer.clear();
        this.active = true;
        this.state = 'logConnecting';
        this.update();
        this.schedule(0);
    }

    async stop() {
        if (!this.code || this.state === 'logStopping') return;
        this.cancel();
        this.active = false;
        this.state = 'logStopping';
        this.remainingMillis = null;
        const generation = this.generation;
        const controller = new AbortController();
        this.pending = controller;
        const timeout = setTimeout(() => controller.abort(), 10000);
        this.update();
        try {
            const response = await fetch('/log', { method: 'DELETE', headers: { 'X-Log-Code': this.code }, cache: 'no-store', signal: controller.signal });
            if (generation !== this.generation) return;
            if (response.ok || response.status === 410) {
                this.code = '';
                this.state = 'logStopped';
            } else {
                this.state = response.status === 403 ? 'logDenied' : 'logStopFailed';
            }
        } catch (error) {
            if (generation === this.generation) this.state = 'logStopFailed';
        } finally {
            clearTimeout(timeout);
            if (generation === this.generation) {
                this.pending = null;
                this.update();
            }
        }
    }

    setVisible(visible) {
        this.visible = visible;
        if (!visible) this.cancel();
        else this.schedule(0);
        this.update();
    }

    schedule(delay) {
        clearTimeout(this.timer);
        this.timer = null;
        if (this.active && this.visible && !this.pending) this.timer = setTimeout(() => this.poll(), delay);
    }

    async poll() {
        clearTimeout(this.timer);
        this.timer = null;
        if (!this.active || !this.visible || this.pending) return;
        const generation = this.generation;
        const controller = new AbortController();
        this.pending = controller;
        const timeout = setTimeout(() => controller.abort(), 10000);
        const opening = !this.code;
        let delay = 1000;
        try {
            const url = this.cursor === undefined ? '/log' : '/log?after=' + this.cursor;
            const options = opening ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' } : { method: 'GET', headers: { 'X-Log-Code': this.code } };
            const response = await fetch(url, { ...options, cache: 'no-store', signal: controller.signal });
            if (generation !== this.generation) return;
            if (opening) delay = await this.openSession(response, generation);
            else delay = await this.readPage(response, generation);
        } catch (error) {
            if (generation === this.generation) {
                this.state = opening ? 'logConnectFailed' : 'logRetrying';
                if (opening) this.active = false;
            }
        } finally {
            clearTimeout(timeout);
            if (generation === this.generation) {
                this.pending = null;
                this.update();
                this.schedule(delay);
            }
        }
    }

    async openSession(response, generation) {
        if (!response.ok) {
            this.active = false;
            this.state = response.status === 403 ? 'logDenied' : 'logConnectFailed';
            return 1000;
        }
        const session = await response.json();
        if (generation !== this.generation) return 1000;
        if (!session || typeof session.code !== 'string' || !/^[A-HJ-NP-Z2-9]{8}$/.test(session.code)) {
            this.active = false;
            this.state = 'invalidResponse';
            return 1000;
        }
        this.code = session.code;
        return 0;
    }

    async readPage(response, generation) {
        if ([400, 403, 410].includes(response.status)) {
            this.active = false;
            this.state = response.status === 410 ? 'logEnded' : response.status === 403 ? 'logDenied' : 'invalidResponse';
            this.remainingMillis = null;
            return 1000;
        }
        if (!response.ok) throw new Error('Log request failed');
        const body = await response.json();
        if (generation !== this.generation) return 1000;
        let page;
        try { page = readLogPage(body, this.cursor); }
        catch (_) {
            this.active = false;
            this.state = 'invalidResponse';
            this.remainingMillis = null;
            return 1000;
        }
        this.buffer.append(page.lines);
        this.cursor = page.next;
        this.remainingMillis = page.remainingMillis;
        this.receivedAt = performance.now();
        this.gap ||= page.gap;
        this.state = 'logReceiving';
        return page.backlog ? 0 : 1000;
    }
}

const logTranslations = {
    'zh-CN': {
        logTitle: '应用日志', logBack: '返回设置', logStatus: '采集状态', logContent: '日志内容', logFollow: '跟随最新', logSearch: '搜索文字', logLevel: '级别', logAll: '全部', logPause: '暂停显示', logResume: '恢复显示', logClear: '清空本页', logDownload: '下载日志', logShare: '分享日志', logStop: '停止采集', logRestart: '重新采集',
        logWaiting: '等待连接', logConnecting: '正在连接…', logReceiving: '正在接收', logHidden: '已暂停接收', logRetrying: '连接中断，正在重试…', logConnectFailed: '连接失败，请重试', logStopping: '正在停止…', logStopped: '已停止采集', logStopFailed: '停止失败，请重试', logEnded: '采集已结束', logDenied: '无法访问此日志会话', invalidResponse: '日志响应无效，请重新采集',
        logGap: '部分较早的日志已移除。', logEmpty: '暂无日志', logNoMatches: '没有匹配的日志', logCount: '保留 {count} 条', logRemaining: '剩余 {time}', logShared: '已分享', logReportDownloaded: '已下载', logExportFailed: '导出失败，请重试',
        logHint: '仅采集当前应用进程的日志，最长一小时。暂停显示或离开网页不会停止采集，请使用“停止采集”。下载和分享包含本页保留的全部日志。'
    },
    'zh-TW': {
        logTitle: '應用日誌', logBack: '返回設定', logStatus: '擷取狀態', logContent: '日誌內容', logFollow: '跟隨最新', logSearch: '搜尋文字', logLevel: '等級', logAll: '全部', logPause: '暫停顯示', logResume: '恢復顯示', logClear: '清空本頁', logDownload: '下載日誌', logShare: '分享日誌', logStop: '停止擷取', logRestart: '重新擷取',
        logWaiting: '等待連線', logConnecting: '連線中…', logReceiving: '接收中', logHidden: '已暫停接收', logRetrying: '連線中斷，重試中…', logConnectFailed: '連線失敗，請重試', logStopping: '停止中…', logStopped: '已停止擷取', logStopFailed: '停止失敗，請重試', logEnded: '擷取已結束', logDenied: '無法存取此日誌工作階段', invalidResponse: '日誌回應無效，請重新擷取',
        logGap: '部分較早的日誌已移除。', logEmpty: '尚無日誌', logNoMatches: '沒有符合的日誌', logCount: '保留 {count} 筆', logRemaining: '剩餘 {time}', logShared: '已分享', logReportDownloaded: '已下載', logExportFailed: '匯出失敗，請重試',
        logHint: '僅擷取目前應用程式的日誌，最長一小時。暫停顯示或離開網頁不會停止擷取，請使用「停止擷取」。下載與分享包含本頁保留的全部日誌。'
    },
    en: {
        logTitle: 'App logs', logBack: 'Back to settings', logStatus: 'Capture status', logContent: 'Log output', logFollow: 'Follow latest', logSearch: 'Search text', logLevel: 'Level', logAll: 'All', logPause: 'Pause display', logResume: 'Resume display', logClear: 'Clear page', logDownload: 'Download logs', logShare: 'Share logs', logStop: 'Stop capture', logRestart: 'Restart capture',
        logWaiting: 'Waiting to connect', logConnecting: 'Connecting…', logReceiving: 'Receiving', logHidden: 'Receiving paused', logRetrying: 'Disconnected. Retrying…', logConnectFailed: 'Connection failed. Try again.', logStopping: 'Stopping…', logStopped: 'Capture stopped', logStopFailed: 'Could not stop. Try again.', logEnded: 'Capture ended', logDenied: 'Cannot access this log session', invalidResponse: 'Invalid log response. Restart capture.',
        logGap: 'Some earlier logs have been removed.', logEmpty: 'No logs yet', logNoMatches: 'No matching logs', logCount: '{count} entries retained', logRemaining: '{time} remaining', logShared: 'Shared', logReportDownloaded: 'Downloaded', logExportFailed: 'Export failed. Try again.',
        logHint: 'Captures only this app process for up to one hour. Pausing the display or leaving this page does not stop capture; use Stop capture. Downloads and shares include all logs retained on this page.'
    }
};
function logPreference(key, fallback) {
    try { return localStorage.getItem(key) || fallback; } catch (_) { return fallback; }
}
const logLanguage = logPreference('tv-web-language', 'zh-CN');
function t(key, values = {}) {
    return Object.entries(values).reduce((text, [name, value]) => text.replace('{' + name + '}', String(value)), (logTranslations[logLanguage] || logTranslations['zh-CN'])[key] || key);
}
function initLogs() {
    const byId = id => document.getElementById('log-' + id);
    const output = byId('text'), search = byId('search'), level = byId('level'), follow = byId('follow');
    const stop = byId('stop'), retry = byId('retry'), pause = byId('pause');
    let paused = false, revision = -1, exporting = false;
    const reader = new LogReader(update);

    function render() {
        if (paused || revision === reader.buffer.revision) return;
        revision = reader.buffer.revision;
        output.textContent = reader.buffer.text(search.value, level.value) || t(reader.buffer.lines.length ? 'logNoMatches' : 'logEmpty');
        if (follow.checked) output.scrollTop = output.scrollHeight;
    }
    function update() {
        if (reader.state === 'logConnecting') {
            output.textContent = t('logEmpty');
            revision = -1;
            byId('export-status').hidden = true;
        }
        const state = reader.active && !reader.visible ? 'logHidden' : reader.state;
        byId('status').dataset.state = state;
        byId('status').textContent = t(state);
        const seconds = Math.ceil(Math.max(0, (reader.remainingMillis || 0) - (performance.now() - reader.receivedAt)) / 1000);
        byId('remaining').textContent = reader.remainingMillis === null ? '' : t('logRemaining', { time: Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0') });
        byId('gap').hidden = !(reader.gap || reader.buffer.trimmed);
        byId('count').textContent = t('logCount', { count: reader.buffer.lines.length });
        retry.hidden = reader.active || state === 'logStopping' || state === 'logStopFailed';
        stop.hidden = !reader.code || ['logEnded', 'logDenied', 'invalidResponse', 'logStopped'].includes(state);
        stop.disabled = state === 'logStopping';
        if (stop.hidden && document.activeElement === stop && !retry.hidden) retry.focus();
        byId('download').disabled = byId('share').disabled = exporting || !reader.buffer.lines.length;
        if (reader.visible) render();
    }
    async function exportLog(share) {
        if (exporting) return;
        exporting = true;
        update();
        try {
            const header = 'TV app log\nExported: ' + new Date().toISOString() + '\nEarlier entries missing: ' + (reader.gap || reader.buffer.trimmed) + '\n\n';
            const blob = new Blob([header, reader.buffer.text(), '\n'], { type: 'text/plain;charset=utf-8' });
            const filename = 'tv-log-' + new Date().toISOString().replace(/[:.]/g, '-') + '.txt';
            const result = share ? await shareLogReport(blob, filename) : (downloadLog(blob, filename), 'logReportDownloaded');
            byId('export-status').textContent = t(result);
            byId('export-status').hidden = false;
        } catch (error) {
            if (error.name !== 'AbortError') {
                byId('export-status').textContent = t('logExportFailed');
                byId('export-status').hidden = false;
            }
        } finally { exporting = false; update(); }
    }
    search.addEventListener('input', () => { revision = -1; render(); });
    level.addEventListener('change', () => { revision = -1; render(); });
    follow.addEventListener('change', () => { if (follow.checked) output.scrollTop = output.scrollHeight; });
    pause.addEventListener('click', () => {
        paused = !paused;
        pause.setAttribute('aria-pressed', String(paused));
        pause.textContent = t(paused ? 'logResume' : 'logPause');
        render();
    });
    byId('clear').addEventListener('click', () => {
        reader.buffer.clear(true);
        output.textContent = t('logEmpty');
        revision = -1;
        update();
    });
    byId('download').addEventListener('click', () => exportLog(false));
    byId('share').addEventListener('click', () => exportLog(true));
    stop.addEventListener('click', () => reader.stop());
    retry.addEventListener('click', () => reader.connect());
    document.addEventListener('visibilitychange', () => reader.setVisible(!document.hidden));
    window.addEventListener('pagehide', () => reader.setVisible(false));
    window.addEventListener('pageshow', () => reader.setVisible(!document.hidden));
    reader.visible = !document.hidden;
    reader.connect();
}
document.documentElement.lang = logTranslations[logLanguage] ? logLanguage : 'zh-CN';
const logTheme = logPreference('tv-web-theme', 'auto');
if (['light', 'dark'].includes(logTheme)) document.documentElement.dataset.theme = logTheme;
document.title = t('logTitle');
document.querySelectorAll('[data-i18n]').forEach(element => { element.textContent = t(element.dataset.i18n); });
document.querySelectorAll('[data-i18n-aria]').forEach(element => { element.setAttribute('aria-label', t(element.dataset.i18nAria)); });
initLogs();
