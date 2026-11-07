'use strict';

const translations = {
    'zh-CN': {
        skip: '跳到主要内容', brand: '影视', companion: '大屏伴侣', navigation: '功能导航', search: '搜索', push: '推送', danmaku: '弹幕', settings: '设置', files: '文件', networkNote: '和大屏，在同一个网络', copyAddress: '复制设备地址', connecting: '正在连接', connected: '已连接', disconnected: '连接中断', myScreen: '我的大屏', appearance: '外观', themeAuto: '跟随系统', themeLight: '浅色', themeDark: '深色', language: '语言', workspace: '你的大屏，近在手边', searchBadge: '小屏输入，大屏精彩', searchHero: '好内容。\n马上上屏。', searchDescription: '输入片名、演员或关键词，把搜索交给大屏。', searchInput: '搜索关键词', searchPlaceholder: '想看什么？', clearSearch: '清空关键词', searchButton: '搜索', recentSearch: '最近搜索', clearHistory: '清空记录', recentSearchEmpty: '想看的片名，随时回来接着找。', pushShortcut: '手里有链接？', pushShortcutDescription: '直接推送到电视', filesShortcut: '文件，也能轻松管理', filesShortcutDescription: '上传字幕、配置和文件', pushBadge: '从手机，送到大屏', pushHero: '喜欢的链接，\n一键上屏。', pushDescription: '粘贴视频地址或分享链接，继续在电视上观看。', playbackLink: '播放链接', pushPlaceholder: '在这里粘贴链接…', pushHelp: '电视会沿用当前来源处理这条链接。', pushButton: '推送到电视', recentPush: '最近推送', recentPushEmpty: '成功发送的链接会保留在这里。', danmakuBadge: '把此刻的感受，留在画面上', danmakuHero: '一起看。\n一起聊。', danmakuDescription: '向当前播放的视频发送一条弹幕。', danmakuContent: '弹幕内容', danmakuPlaceholder: '此刻，想说些什么？', danmakuMode: '显示位置', danmakuSize: '文字大小', danmakuColor: '文字颜色', scrolling: '滚动', top: '顶部', bottom: '底部', small: '小', standard: '标准', large: '大', danmakuHelp: '先在电视上播放视频，再发送弹幕。', sendDanmaku: '发送弹幕', settingsBadge: '少一点遥控输入', settingsHero: '长链接，\n留给手机输入。', settingsDescription: '向电视发送配置地址或设置内容。', settingName: '名称（可选）', settingNamePlaceholder: '例如：点播配置', settingContent: '配置或设置内容', settingContentPlaceholder: '粘贴配置地址或设置内容…', settingsHelp: '内容只发送到当前设备，不保存在浏览器记录中。', settingsButton: '发送到电视', filesBadge: '你的文件，随手管理', deviceFiles: '设备文件', upload: '上传文件', newFolder: '新建文件夹', refreshFiles: '刷新文件', uploadProgress: '上传进度', filePath: '文件路径', filterFiles: '筛选文件', emptyFolder: '这里，暂时留白', emptyFolderDescription: '上传一份文件，让它在大屏上派上用场。', retry: '重试', footer: '手机上的轻松，大屏上的精彩。', nowPlaying: '正在播放', playerIdle: '等待播放', playerPaused: '已暂停', playerPlaying: '播放中', playerBuffering: '缓冲中', playerOffline: '暂时离线', playerIdleTitle: '大屏，等你下一部。', playerIdleDescription: '开始播放后，就能在这里轻松控制。', playbackProgress: '播放进度', previous: '上一集', next: '下一集', play: '播放', pause: '暂停', replay: '重播', repeat: '切换循环', stop: '停止', playerNote: '播放状态来自你的设备', playerOfflineNote: '暂时无法读取播放状态', close: '关闭', cancel: '取消', confirm: '确定', loadingFiles: '正在整理你的文件…', filesCount: '{count} 项', rootFolder: '全部文件', noMatches: '没有找到匹配的文件', noMatchesDescription: '试试更短的关键词，或清空筛选。', filesError: '暂时无法读取文件', filesErrorDescription: '确认设备仍在线，然后再试一次。', requestFailed: '操作未完成，请确认设备在线后重试。', searchSent: '搜索已发送到设备', pushSent: '链接已发送到设备', settingsSent: '设置内容已发送到设备', danmakuSent: '弹幕已发送到设备', controlSent: '控制请求已发送', copied: '已复制', copyFailed: '无法直接复制，请长按选择地址。', historyCleared: '记录已清空', fileActions: '文件操作', folderActions: '文件夹操作', openOnDevice: '在设备上打开', loadSubtitle: '加载字幕', copyFileAddress: '复制文件地址', download: '下载', delete: '删除', deleteTitle: '删除这份文件？', deleteFolderTitle: '删除这个文件夹？', deleteDescription: '删除后无法在这里恢复，请确认选中了正确的文件。', deleteFolderDescription: '文件夹及其中的内容都会删除，请仔细确认。', deleted: '已删除', folderName: '文件夹名称', folderNamePlaceholder: '为文件夹起个名字', newFolderDescription: '在当前目录中新建一个文件夹。', folderCreated: '文件夹已创建', invalidName: '请输入有效名称，不要包含 / 或 \\。', uploadTitle: '上传到当前文件夹', uploadDescription: '以下文件将发送到你的设备。ZIP 文件会沿用设备的解包处理。', uploadConfirm: '开始上传', uploading: '正在上传 {count} 份文件', uploadComplete: '上传完成', uploadingBusy: '已有文件正在上传，请稍候。', fileTime: '修改于 {time}', rename: '重命名', renameDescription: '修改名称，文件内容保持不变。', newName: '新的名称', renamed: '名称已更新', move: '移动', moveDescription: '输入设备上已存在的目标文件夹路径。', moveTarget: '目标文件夹', movePlaceholder: '例如：/字幕；留空为全部文件', moved: '文件已移动', remote: '遥控器', remoteDescription: '方向键、返回和菜单，轻松操作当前设备。', remoteHint: '也可以使用键盘方向键操作', remoteSent: '已发送', remoteUp: '上', remoteDown: '下', remoteLeft: '左', remoteRight: '右', remoteOk: '确定', remoteBack: '返回', remoteMenu: '菜单', remoteNumbers: '数字键', remoteUnavailable: '设备暂时不能接收遥控操作', fieldRequired: '先输入内容，再发送。', fileConflict: '同名文件已存在，请换个名称或目标文件夹。', fileMissing: '文件或目标文件夹已不存在，请刷新后重试。', invalidFolder: '请使用设备内有效的文件夹路径。'
    },
    'zh-TW': {
        skip: '跳到主要內容', brand: '影視', companion: '大螢幕夥伴', navigation: '功能導覽', search: '搜尋', push: '推送', danmaku: '彈幕', settings: '設定', files: '檔案', networkNote: '和大螢幕，在同一個網路', copyAddress: '複製裝置位址', connecting: '正在連線', connected: '已連線', disconnected: '連線中斷', myScreen: '我的大螢幕', appearance: '外觀', themeAuto: '跟隨系統', themeLight: '淺色', themeDark: '深色', language: '語言', workspace: '你的大螢幕，近在手邊', searchBadge: '小螢幕輸入，大螢幕精彩', searchHero: '好內容。\n馬上上螢幕。', searchDescription: '輸入片名、演員或關鍵字，把搜尋交給大螢幕。', searchInput: '搜尋關鍵字', searchPlaceholder: '想看什麼？', clearSearch: '清空關鍵字', searchButton: '搜尋', recentSearch: '最近搜尋', clearHistory: '清空紀錄', recentSearchEmpty: '想看的片名，隨時回來接著找。', pushShortcut: '手裡有連結？', pushShortcutDescription: '直接推送到電視', filesShortcut: '檔案，也能輕鬆管理', filesShortcutDescription: '上傳字幕、設定和檔案', pushBadge: '從手機，送到大螢幕', pushHero: '喜歡的連結，\n一鍵上螢幕。', pushDescription: '貼上影片位址或分享連結，繼續在電視上觀看。', playbackLink: '播放連結', pushPlaceholder: '在這裡貼上連結…', pushHelp: '電視會沿用目前來源處理這條連結。', pushButton: '推送到電視', recentPush: '最近推送', recentPushEmpty: '成功傳送的連結會保留在這裡。', danmakuBadge: '把此刻的感受，留在畫面上', danmakuHero: '一起看。\n一起聊。', danmakuDescription: '向目前播放的影片傳送一條彈幕。', danmakuContent: '彈幕內容', danmakuPlaceholder: '此刻，想說些什麼？', danmakuMode: '顯示位置', danmakuSize: '文字大小', danmakuColor: '文字顏色', scrolling: '捲動', top: '頂部', bottom: '底部', small: '小', standard: '標準', large: '大', danmakuHelp: '先在電視上播放影片，再傳送彈幕。', sendDanmaku: '傳送彈幕', settingsBadge: '少一點遙控輸入', settingsHero: '長連結，\n留給手機輸入。', settingsDescription: '向電視傳送設定網址或設定內容。', settingName: '名稱（選填）', settingNamePlaceholder: '例如：點播設定', settingContent: '設定網址或內容', settingContentPlaceholder: '貼上設定網址或內容…', settingsHelp: '內容只傳送到目前裝置，不儲存在瀏覽器紀錄中。', settingsButton: '傳送到電視', filesBadge: '你的檔案，隨手管理', deviceFiles: '裝置檔案', upload: '上傳檔案', newFolder: '新增資料夾', refreshFiles: '重新整理檔案', uploadProgress: '上傳進度', filePath: '檔案路徑', filterFiles: '篩選檔案', emptyFolder: '這裡，暫時留白', emptyFolderDescription: '上傳一份檔案，讓它在大螢幕上派上用場。', retry: '重試', footer: '手機上的輕鬆，大螢幕上的精彩。', nowPlaying: '正在播放', playerIdle: '等待播放', playerPaused: '已暫停', playerPlaying: '播放中', playerBuffering: '緩衝中', playerOffline: '暫時離線', playerIdleTitle: '大螢幕，等你下一部。', playerIdleDescription: '開始播放後，就能在這裡輕鬆控制。', playbackProgress: '播放進度', previous: '上一集', next: '下一集', play: '播放', pause: '暫停', replay: '重播', repeat: '切換循環', stop: '停止', playerNote: '播放狀態來自你的裝置', playerOfflineNote: '暫時無法讀取播放狀態', close: '關閉', cancel: '取消', confirm: '確定', loadingFiles: '正在整理你的檔案…', filesCount: '{count} 項', rootFolder: '全部檔案', noMatches: '沒有找到相符的檔案', noMatchesDescription: '試試更短的關鍵字，或清空篩選。', filesError: '暫時無法讀取檔案', filesErrorDescription: '確認裝置仍在線，然後再試一次。', requestFailed: '操作未完成，請確認裝置在線後重試。', searchSent: '搜尋已傳送到裝置', pushSent: '連結已傳送到裝置', settingsSent: '設定內容已傳送到裝置', danmakuSent: '彈幕已傳送到裝置', controlSent: '控制請求已傳送', copied: '已複製', copyFailed: '無法直接複製，請長按選擇位址。', historyCleared: '紀錄已清空', fileActions: '檔案操作', folderActions: '資料夾操作', openOnDevice: '在裝置上開啟', loadSubtitle: '載入字幕', copyFileAddress: '複製檔案位址', download: '下載', delete: '刪除', deleteTitle: '刪除這份檔案？', deleteFolderTitle: '刪除這個資料夾？', deleteDescription: '刪除後無法在這裡復原，請確認選中了正確的檔案。', deleteFolderDescription: '資料夾及其中的內容都會刪除，請仔細確認。', deleted: '已刪除', folderName: '資料夾名稱', folderNamePlaceholder: '為資料夾取個名字', newFolderDescription: '在目前目錄中新增一個資料夾。', folderCreated: '資料夾已建立', invalidName: '請輸入有效名稱，不要包含 / 或 \\。', uploadTitle: '上傳到目前資料夾', uploadDescription: '以下檔案將傳送到你的裝置。ZIP 檔案會沿用裝置的解壓處理。', uploadConfirm: '開始上傳', uploading: '正在上傳 {count} 份檔案', uploadComplete: '上傳完成', uploadingBusy: '已有檔案正在上傳，請稍候。', fileTime: '修改於 {time}', rename: '重新命名', renameDescription: '修改名稱，檔案內容保持不變。', newName: '新的名稱', renamed: '名稱已更新', move: '移動', moveDescription: '輸入裝置上已存在的目標資料夾路徑。', moveTarget: '目標資料夾', movePlaceholder: '例如：/字幕；留空為全部檔案', moved: '檔案已移動', remote: '遙控器', remoteDescription: '方向鍵、返回和選單，輕鬆操作目前裝置。', remoteHint: '也可以使用鍵盤方向鍵操作', remoteSent: '已傳送', remoteUp: '上', remoteDown: '下', remoteLeft: '左', remoteRight: '右', remoteOk: '確定', remoteBack: '返回', remoteMenu: '選單', remoteNumbers: '數字鍵', remoteUnavailable: '裝置暫時不能接收遙控操作', fieldRequired: '先輸入內容，再傳送。', fileConflict: '同名檔案已存在，請換個名稱或目標資料夾。', fileMissing: '檔案或目標資料夾已不存在，請重新整理後重試。', invalidFolder: '請使用裝置內有效的資料夾路徑。'
    },
    en: {
        skip: 'Skip to main content', brand: 'TV', companion: 'Big-screen companion', navigation: 'Features', search: 'Search', push: 'Send', danmaku: 'Comments', settings: 'Settings', files: 'Files', networkNote: 'Together on the same network', copyAddress: 'Copy device address', connecting: 'Connecting', connected: 'Connected', disconnected: 'Disconnected', myScreen: 'My screen', appearance: 'Appearance', themeAuto: 'System', themeLight: 'Light', themeDark: 'Dark', language: 'Language', workspace: 'Your big screen, close at hand', searchBadge: 'Small screen. Big possibilities.', searchHero: 'Good stories.\nOn the big screen.', searchDescription: 'Enter a title, actor or keyword. Your screen takes it from here.', searchInput: 'Search keywords', searchPlaceholder: 'What would you like to watch?', clearSearch: 'Clear keywords', searchButton: 'Search', recentSearch: 'Recent searches', clearHistory: 'Clear history', recentSearchEmpty: 'Your next favorite is only a search away.', pushShortcut: 'Have a link ready?', pushShortcutDescription: 'Send it straight to your TV', filesShortcut: 'Your files, made simple', filesShortcutDescription: 'Upload subtitles and more', pushBadge: 'From your phone to your screen', pushHero: 'A link you love.\nA screen to match.', pushDescription: 'Paste a video or sharing link and continue watching on your TV.', playbackLink: 'Playback link', pushPlaceholder: 'Paste your link here…', pushHelp: 'Your TV handles the link using the current source.', pushButton: 'Send to TV', recentPush: 'Recently sent', recentPushEmpty: 'Links you send successfully appear here.', danmakuBadge: 'Share the moment on screen', danmakuHero: 'Watch together.\nTalk together.', danmakuDescription: 'Send a comment to the video currently playing.', danmakuContent: 'Comment', danmakuPlaceholder: 'What is on your mind?', danmakuMode: 'Position', danmakuSize: 'Text size', danmakuColor: 'Text color', scrolling: 'Scrolling', top: 'Top', bottom: 'Bottom', small: 'Small', standard: 'Standard', large: 'Large', danmakuHelp: 'Start a video on your TV before sending a comment.', sendDanmaku: 'Send comment', settingsBadge: 'Less typing with a remote', settingsHero: 'Long links.\nEasy on your phone.', settingsDescription: 'Send a configuration address or setting to your TV.', settingName: 'Name (optional)', settingNamePlaceholder: 'For example: video source', settingContent: 'Configuration or setting', settingContentPlaceholder: 'Paste a configuration address or setting…', settingsHelp: 'Sent only to this device, without saving it in browser history.', settingsButton: 'Send to TV', filesBadge: 'Your files, within reach', deviceFiles: 'Device files', upload: 'Upload files', newFolder: 'New folder', refreshFiles: 'Refresh files', uploadProgress: 'Upload progress', filePath: 'File path', filterFiles: 'Filter files', emptyFolder: 'A little room for something new', emptyFolderDescription: 'Upload a file and put it to work on your big screen.', retry: 'Retry', footer: 'Easy on your phone. Brilliant on your screen.', nowPlaying: 'Now playing', playerIdle: 'Ready when you are', playerPaused: 'Paused', playerPlaying: 'Playing', playerBuffering: 'Buffering', playerOffline: 'Offline', playerIdleTitle: 'Your next story starts here.', playerIdleDescription: 'Start watching to control playback from your phone.', playbackProgress: 'Playback progress', previous: 'Previous episode', next: 'Next episode', play: 'Play', pause: 'Pause', replay: 'Replay', repeat: 'Toggle repeat', stop: 'Stop', playerNote: 'Live status from your device', playerOfflineNote: 'Playback status is temporarily unavailable', close: 'Close', cancel: 'Cancel', confirm: 'Confirm', loadingFiles: 'Getting your files ready…', filesCount: '{count} items', rootFolder: 'All files', noMatches: 'No matching files', noMatchesDescription: 'Try a shorter keyword or clear the filter.', filesError: 'Files are temporarily unavailable', filesErrorDescription: 'Make sure your device is online and try again.', requestFailed: 'Could not complete this action. Check your connection and try again.', searchSent: 'Search sent to your device', pushSent: 'Link sent to your device', settingsSent: 'Setting sent to your device', danmakuSent: 'Comment sent to your device', controlSent: 'Playback request sent', copied: 'Copied', copyFailed: 'Copy is unavailable. Press and hold to select the address.', historyCleared: 'History cleared', fileActions: 'File actions', folderActions: 'Folder actions', openOnDevice: 'Open on device', loadSubtitle: 'Load subtitle', copyFileAddress: 'Copy file address', download: 'Download', delete: 'Delete', deleteTitle: 'Delete this file?', deleteFolderTitle: 'Delete this folder?', deleteDescription: 'This cannot be undone here. Make sure you selected the right file.', deleteFolderDescription: 'This deletes the folder and everything inside it. Please check carefully.', deleted: 'Deleted', folderName: 'Folder name', folderNamePlaceholder: 'Give your folder a name', newFolderDescription: 'Create a new folder in the current location.', folderCreated: 'Folder created', invalidName: 'Enter a valid name without / or \\.', uploadTitle: 'Upload to this folder', uploadDescription: 'These files will be sent to your device. ZIP files use the device’s extraction behavior.', uploadConfirm: 'Start upload', uploading: 'Uploading {count} files', uploadComplete: 'Upload complete', uploadingBusy: 'An upload is already in progress. Please wait.', fileTime: 'Modified {time}', rename: 'Rename', renameDescription: 'Change the name while keeping the contents.', newName: 'New name', renamed: 'Name updated', move: 'Move', moveDescription: 'Enter the path to an existing folder on your device.', moveTarget: 'Destination folder', movePlaceholder: 'For example: /Subtitles; leave empty for All files', moved: 'File moved', remote: 'Remote control', remoteDescription: 'Use direction, back and menu keys to control your device.', remoteHint: 'You can also use your keyboard’s arrow keys', remoteSent: 'Sent', remoteUp: 'Up', remoteDown: 'Down', remoteLeft: 'Left', remoteRight: 'Right', remoteOk: 'OK', remoteBack: 'Back', remoteMenu: 'Menu', remoteNumbers: 'Number keys', remoteUnavailable: 'Your device cannot receive remote keys right now', fieldRequired: 'Enter something before sending.', fileConflict: 'A file with this name already exists. Choose a different name or folder.', fileMissing: 'The file or destination folder no longer exists. Refresh and try again.', invalidFolder: 'Use a valid folder path within this device.'
    }
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
const STORAGE = { language: 'tv-web-language', theme: 'tv-web-theme', search: 'tv-web-searches', push: 'tv-web-pushes' };
let language = stored(STORAGE.language, 'zh-CN');
if (!translations[language]) language = 'zh-CN';
let currentPanel = 1;
let currentRoot = '';
let currentFiles = [];
let fileRequest = null;
let filesLoaded = false;
let fileFailure = false;
let playback = {};
let connectionReady = false;
let deviceName = '';
let artworkUrl = '';
let snackbarTimer = 0;
let dialogHistoryActive = false;
let dialogRestoreFocus = null;
let remoteOpen = false;
let remoteQueue = Promise.resolve();
let remotePending = 0;
let uploadBusy = false;
let pollTimer = 0;
let polling = false;
let lastDeviceCheck = 0;

function stored(key, fallback) {
    try { return localStorage.getItem(key) ?? fallback; } catch (_) { return fallback; }
}
function save(key, value) {
    try { localStorage.setItem(key, value); } catch (_) { /* The controls also work without local storage. */ }
}
function t(key, values = {}) {
    let result = translations[language][key] ?? translations['zh-CN'][key] ?? key;
    Object.entries(values).forEach(([name, value]) => { result = result.replace('{' + name + '}', String(value)); });
    return result;
}
function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.classList.add('icon');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#i-' + name);
    svg.append(use);
    return svg;
}
function textElement(tag, text, className) {
    const element = document.createElement(tag);
    element.textContent = text;
    if (className) element.className = className;
    return element;
}
function button(text, className, callback, iconName) {
    const element = document.createElement('button');
    element.type = 'button';
    element.className = className;
    if (iconName) element.append(icon(iconName));
    element.append(textElement('span', text));
    element.addEventListener('click', callback);
    return element;
}
function setBusy(element, busy) {
    if (!element) return;
    element.disabled = busy;
    element.classList.toggle('is-loading', busy);
    element.setAttribute('aria-busy', String(busy));
}
function announce(message) {
    clearTimeout(snackbarTimer);
    $('#snackbar-message').textContent = message;
    $('#snackbar').hidden = false;
    snackbarTimer = window.setTimeout(() => { $('#snackbar').hidden = true; }, 4500);
}

function applyTheme() {
    const preference = stored(STORAGE.theme, 'auto');
    const selected = ['auto', 'light', 'dark'].includes(preference) ? preference : 'auto';
    const theme = selected === 'auto' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : selected;
    document.documentElement.dataset.theme = theme;
    $('#theme-select').value = selected;
    $('meta[name="theme-color"]').content = theme === 'dark' ? '#18151d' : '#f8f6f2';
}
function applyLanguage() {
    document.documentElement.lang = language;
    $('#language-select').value = language;
    $$('[data-i18n]').forEach(element => { element.textContent = t(element.dataset.i18n); });
    $$('[data-i18n-placeholder]').forEach(element => { element.placeholder = t(element.dataset.i18nPlaceholder); });
    $$('[data-i18n-aria]').forEach(element => { element.setAttribute('aria-label', t(element.dataset.i18nAria)); });
    $$('[data-i18n-title]').forEach(element => { element.title = t(element.dataset.i18nTitle); });
    updatePageTitle();
    updateConnection();
    $('#device-name').textContent = deviceName || t('myScreen');
    renderHistories();
    renderPlayback(playback, lastDeviceCheck > 0 && !connectionReady);
    if (filesLoaded || fileFailure) renderFiles();
}
function updatePageTitle() {
    const key = ['search', 'push', 'danmaku', 'settings', 'files'][currentPanel - 1];
    $('#page-title').textContent = t(key);
    document.title = t('brand') + ' · ' + t(key);
}
function showPanel(value, addHistory = true) {
    const panel = Number(value);
    currentPanel = Number.isInteger(panel) && panel >= 1 && panel <= 5 ? panel : 1;
    for (let id = 1; id <= 5; id++) {
        const active = id === currentPanel;
        $('#panel' + id).hidden = !active;
        $('#panel' + id).classList.toggle('active', active);
        $('#tab' + id).classList.toggle('active', active);
        $('#tab' + id).setAttribute('aria-selected', String(active));
        $('#tab' + id).tabIndex = active ? 0 : -1;
    }
    updatePageTitle();
    if (addHistory) {
        const address = new URL(location.href);
        address.searchParams.set('tab', String(currentPanel));
        history.pushState({ tab: currentPanel, path: currentRoot }, '', address);
    }
    if (currentPanel === 5 && !filesLoaded && !fileRequest) listFile(currentRoot);
}

async function request(path, params, signal) {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal) {
        if (signal.aborted) controller.abort();
        else signal.addEventListener('abort', abort, { once: true });
    }
    const timer = setTimeout(abort, 15_000);
    try {
        const options = { credentials: 'same-origin', signal: controller.signal, cache: 'no-store' };
        if (params !== undefined) { options.method = 'POST'; options.body = new URLSearchParams(params); }
        const response = await fetch(path, options);
        const body = await response.text();
        if (!response.ok) {
            const mutation = path === '/rename' || path === '/move';
            const key = mutation && response.status === 409 ? 'fileConflict'
                : mutation && response.status === 404 ? 'fileMissing'
                : mutation && response.status === 400 ? (path === '/rename' ? 'invalidName' : 'invalidFolder')
                : 'requestFailed';
            throw new Error(t(key));
        }
        return body;
    } finally {
        clearTimeout(timer);
        if (signal) signal.removeEventListener('abort', abort);
    }
}
async function sendAction(action, params, element, successKey) {
    setBusy(element, true);
    try {
        await request('/action', { ...params, do: action });
        if (successKey) announce(t(successKey));
        return true;
    } catch (_) {
        announce(t('requestFailed'));
        return false;
    } finally { setBusy(element, false); }
}
function historyItems(kind) {
    try {
        const values = JSON.parse(stored(STORAGE[kind], '[]'));
        return Array.isArray(values) ? values.filter(value => typeof value === 'string').slice(0, 8) : [];
    } catch (_) { return []; }
}
function remember(kind, text) {
    const values = [text, ...historyItems(kind).filter(value => value !== text)].slice(0, 8);
    save(STORAGE[kind], JSON.stringify(values));
    renderHistories();
}
function renderHistories() {
    for (const kind of ['search', 'push']) {
        const values = historyItems(kind);
        const list = $('#' + (kind === 'search' ? 'recent-searches' : 'recent-pushes'));
        list.replaceChildren();
        values.forEach(value => {
            const item = button(value, 'recent-chip', () => {
                const input = $('#' + (kind === 'search' ? 'keyword' : 'push_url'));
                input.value = value;
                input.focus();
                if (kind === 'search') $('#clear-keyword').hidden = false;
            }, kind === 'search' ? 'search' : 'push');
            item.title = value;
            list.append(item);
        });
        $('#' + kind + '-history-empty').hidden = values.length > 0;
        $('#clear-' + kind + '-history').hidden = values.length === 0;
    }
}
function fileUri(path) { return 'file:/' + String(path).replace(/^\/+/, ''); }
function fileUrl(path) {
    const value = String(path);
    return '/file' + (value && !value.startsWith('/') ? '/' : '') + value.split('/').map(encodeURIComponent).join('/');
}
async function copy(text) {
    try {
        if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(text);
        else {
            const field = document.createElement('textarea');
            field.value = text;
            field.setAttribute('aria-label', t('copyAddress'));
            field.style.cssText = 'position:fixed;left:-9999px;top:0';
            const owner = $('#action-dialog').open ? $('#dialog-content') : document.body;
            owner.append(field);
            field.select();
            const copied = document.execCommand('copy');
            field.remove();
            if (!copied) throw new Error('Copy unavailable');
        }
        announce(t('copied'));
    } catch (_) { announce(t('copyFailed')); }
}

function updateConnection(ready = connectionReady) {
    connectionReady = ready;
    const indicator = $('#connection-indicator');
    indicator.classList.toggle('connected', ready);
    indicator.classList.toggle('disconnected', !ready && lastDeviceCheck > 0);
    $('#connection-label').textContent = t(ready ? 'connected' : lastDeviceCheck ? 'disconnected' : 'connecting');
}
function formatTime(value) {
    const seconds = Math.floor(Math.max(0, Number(value) || 0) / 1000);
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor(seconds / 60) % 60;
    const rest = String(seconds % 60).padStart(2, '0');
    return hours ? hours + ':' + String(minutes).padStart(2, '0') + ':' + rest : minutes + ':' + rest;
}
function renderPlayback(data, offline = false) {
    playback = data && typeof data === 'object' && !Array.isArray(data) ? data : {};
    const active = Object.hasOwn(playback, 'state') && Boolean(playback.url || playback.title || Number(playback.state) > 1);
    const playing = active && Number(playback.state) === 3;
    const status = offline ? 'playerOffline' : !active ? 'playerIdle' : ({ 2: 'playerPaused', 3: 'playerPlaying', 6: 'playerBuffering' }[Number(playback.state)] || 'playerIdle');
    $('#playback-status').textContent = t(status);
    $('#player-title').textContent = active ? String(playback.title || t('myScreen')) : t('playerIdleTitle');
    $('#player-artist').textContent = active ? String(playback.artist || '') : t('playerIdleDescription');
    $('#player-note').textContent = t(offline ? 'playerOfflineNote' : 'playerNote');
    const duration = Math.max(0, Number(playback.duration) || 0);
    const position = Math.max(0, Number(playback.position) || 0);
    $('#player-progress').max = duration || 1;
    $('#player-progress').value = Math.min(position, duration);
    $('#player-position').textContent = formatTime(position);
    $('#player-duration').textContent = formatTime(duration);
    const toggle = $('#playback-toggle');
    toggle.classList.toggle('is-playing', playing);
    $('use', toggle).setAttribute('href', playing ? '#i-pause' : '#i-play');
    toggle.setAttribute('aria-label', t(playing ? 'pause' : 'play'));
    $$('.player-control').forEach(element => { if (element.getAttribute('aria-busy') !== 'true') element.disabled = !active || offline; });
    const speed = Number(playback.speed);
    $('#player-speed').hidden = !active || !Number.isFinite(speed) || speed <= 0;
    $('#player-speed').textContent = Number.isFinite(speed) ? String(Number(speed.toFixed(2))) + '×' : '';
    let nextArtwork = '';
    try {
        const address = new URL(String(playback.artwork || ''), location.origin);
        if (active && playback.artwork && ['http:', 'https:'].includes(address.protocol)) nextArtwork = address.href;
    } catch (_) { /* Keep the local illustration for unsupported artwork. */ }
    if (nextArtwork !== artworkUrl) {
        artworkUrl = nextArtwork;
        const image = $('#player-artwork');
        image.hidden = true;
        $('#player-placeholder').hidden = false;
        if (nextArtwork) {
            image.onload = () => { if (image.src === artworkUrl) { image.hidden = false; $('#player-placeholder').hidden = true; } };
            image.onerror = () => { image.hidden = true; $('#player-placeholder').hidden = false; };
            image.src = nextArtwork;
        } else image.removeAttribute('src');
    }
}
async function pollDevice(force = false) {
    if (polling || document.hidden) return;
    polling = true;
    try {
        if (force || !connectionReady || Date.now() - lastDeviceCheck > 25_000) {
            try {
                const data = JSON.parse(await request('/device'));
                if (!data || typeof data !== 'object' || !data.name) throw new Error('Unavailable device');
                lastDeviceCheck = Date.now();
                deviceName = String(data.name);
                $('#device-name').textContent = deviceName;
                updateConnection(true);
            } catch (_) {
                lastDeviceCheck = Date.now();
                updateConnection(false);
                renderPlayback(playback, true);
                return;
            }
        }
        try { renderPlayback(JSON.parse(await request('/media'))); }
        catch (_) { renderPlayback(playback, true); }
    } finally { polling = false; }
}
function schedulePoll() {
    clearInterval(pollTimer);
    if (!document.hidden) { pollDevice(); pollTimer = setInterval(() => pollDevice(), 3500); }
}

function openDialog(options) {
    const dialog = $('#action-dialog');
    const wasOpen = dialog.open;
    if (!wasOpen) dialogRestoreFocus = document.activeElement;
    remoteOpen = options.remote === true;
    $('#dialog-title').textContent = options.title;
    $('#dialog-description').textContent = options.description || '';
    $('#dialog-icon').replaceChildren(icon(options.icon || 'file'));
    $('#dialog-content').replaceChildren(...(options.content || []));
    const actions = $('#dialog-actions');
    actions.classList.toggle('file-dialog-actions', options.grid === true);
    actions.replaceChildren(...(options.actions || []));
    if (!wasOpen) {
        if (typeof dialog.showModal === 'function') dialog.showModal();
        else dialog.setAttribute('open', '');
        history.pushState({ tab: currentPanel, path: currentRoot, dialog: true }, '');
        dialogHistoryActive = true;
    }
    requestAnimationFrame(() => {
        const input = $('input, textarea', dialog);
        if (input) { input.focus(); if (options.selectInput) input.select(); }
        else ($('.remote-ok', dialog) || $('#dialog-actions button', dialog) || $('#dialog-close', dialog))?.focus();
    });
}
function closeDialog(fromHistory = false) {
    const dialog = $('#action-dialog');
    if (typeof dialog.close === 'function') dialog.close();
    else dialog.removeAttribute('open');
    remoteOpen = false;
    if (dialogRestoreFocus?.isConnected) dialogRestoreFocus.focus();
    if (dialogHistoryActive && !fromHistory) { dialogHistoryActive = false; history.back(); }
    else dialogHistoryActive = false;
}
function cancelButton() { return button(t('cancel'), 'button button-tonal', () => closeDialog()); }
function validName(value) { return value.length > 0 && value !== '.' && value !== '..' && !/[\/\\\u0000]/.test(value); }
function dialogInput(id, label, value, placeholder, required = true) {
    const wrapper = document.createElement('div');
    const fieldLabel = textElement('label', label, 'field-label');
    fieldLabel.htmlFor = id;
    const input = document.createElement('input');
    input.id = id;
    input.className = 'outlined-input';
    input.value = value;
    input.placeholder = placeholder;
    input.required = required;
    input.maxLength = 500;
    wrapper.append(fieldLabel, input);
    return wrapper;
}
function showFileActions(file) {
    const directory = Number(file.dir) === 1;
    const address = textElement('div', fileUri(file.path), 'dialog-file-path');
    const actions = [];
    if (!directory) {
        const video = /\.(?:mkv|mp4|avi|mov|m4v|ts|m2ts|webm|strm)$/i.test(file.name);
        const subtitle = /\.(?:srt|ass|ssa|vtt|ttml|dfxp|sub)$/i.test(file.name);
        actions.push(button(t(video ? 'play' : subtitle ? 'loadSubtitle' : 'openOnDevice'), 'button button-filled', async event => {
            const accepted = await sendAction(video ? 'push' : 'file', { [video ? 'url' : 'path']: fileUri(file.path) }, event.currentTarget, 'pushSent');
            if (accepted) closeDialog();
        }, video ? 'play' : 'tv'));
        actions.push(button(t('download'), 'button button-tonal', () => {
            const link = document.createElement('a');
            link.href = fileUrl(file.path); link.download = String(file.name); link.rel = 'noopener';
            $('#dialog-content').append(link); link.click(); link.remove();
        }, 'download'));
    }
    actions.push(button(t('copyFileAddress'), 'button button-tonal', () => copy(fileUri(file.path)), 'copy'));
    actions.push(button(t('rename'), 'button button-tonal', () => showRename(file), 'file'));
    actions.push(button(t('move'), 'button button-tonal', () => showMove(file), 'folder'));
    actions.push(button(t('delete'), 'button button-error', () => showDelete(file), 'trash'));
    openDialog({ title: String(file.name), description: t(directory ? 'folderActions' : 'fileActions'), icon: directory ? 'folder' : 'file', content: [address], actions, grid: true });
}
function showDelete(file) {
    const directory = Number(file.dir) === 1;
    const confirm = button(t('delete'), 'button button-error', async event => {
        const element = event.currentTarget;
        setBusy(element, true);
        try {
            await request(directory ? '/delFolder' : '/delFile', { path: file.path });
            closeDialog(); announce(t('deleted')); listFile(currentRoot);
        } catch (error) { announce(error.message || t('requestFailed')); }
        finally { setBusy(element, false); }
    }, 'trash');
    openDialog({ title: t(directory ? 'deleteFolderTitle' : 'deleteTitle'), description: t(directory ? 'deleteFolderDescription' : 'deleteDescription'), icon: 'trash', content: [textElement('div', String(file.name), 'dialog-file-path')], actions: [cancelButton(), confirm] });
}
function showRename(file) {
    const field = dialogInput('rename-name', t('newName'), String(file.name), '', true);
    const submit = button(t('rename'), 'button button-filled', async event => {
        const input = $('#rename-name'); const name = input.value.trim();
        if (!input.reportValidity() || !validName(name)) { announce(t('invalidName')); return; }
        const element = event.currentTarget;
        setBusy(element, true);
        try { await request('/rename', { path: file.path, name }); closeDialog(); announce(t('renamed')); listFile(currentRoot); }
        catch (error) { announce(error.message || t('requestFailed')); }
        finally { setBusy(element, false); }
    });
    field.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); submit.click(); } });
    openDialog({ title: t('rename'), description: t('renameDescription'), icon: 'file', content: [field], actions: [cancelButton(), submit], selectInput: true });
}
function showMove(file) {
    const field = dialogInput('move-target', t('moveTarget'), currentRoot, t('movePlaceholder'), false);
    const submit = button(t('move'), 'button button-filled', async event => {
        const element = event.currentTarget;
        setBusy(element, true);
        try { await request('/move', { path: file.path, target: $('#move-target').value.trim() }); closeDialog(); announce(t('moved')); listFile(currentRoot); }
        catch (error) { announce(error.message || t('requestFailed')); }
        finally { setBusy(element, false); }
    }, 'folder');
    field.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); submit.click(); } });
    openDialog({ title: t('move'), description: t('moveDescription'), icon: 'folder', content: [field], actions: [cancelButton(), submit] });
}
function showNewFolder() {
    const field = dialogInput('new-folder-name', t('folderName'), '', t('folderNamePlaceholder'));
    const submit = button(t('newFolder'), 'button button-filled', async event => {
        const input = $('#new-folder-name'); const name = input.value.trim();
        if (!input.reportValidity() || !validName(name)) { announce(t('invalidName')); return; }
        const element = event.currentTarget;
        setBusy(element, true);
        try { await request('/newFolder', { path: currentRoot, name }); closeDialog(); announce(t('folderCreated')); listFile(currentRoot); }
        catch (error) { announce(error.message || t('requestFailed')); }
        finally { setBusy(element, false); }
    }, 'plus');
    field.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); submit.click(); } });
    openDialog({ title: t('newFolder'), description: t('newFolderDescription'), icon: 'folder', content: [field], actions: [cancelButton(), submit] });
}

async function listFile(path, addHistory = false) {
    if (fileRequest) fileRequest.abort();
    const controller = new AbortController(); fileRequest = controller;
    const target = String(path || '');
    fileFailure = false; $('#file-empty').hidden = true;
    const loading = textElement('div', t('loadingFiles'), 'file-loading');
    const ring = document.createElement('span'); ring.className = 'loading-ring'; ring.setAttribute('aria-hidden', 'true'); loading.prepend(ring);
    $('#file_list').replaceChildren(loading); $('#file_list').setAttribute('aria-busy', 'true');
    try {
        const info = JSON.parse(await request(fileUrl(target), undefined, controller.signal));
        if (fileRequest !== controller) return;
        if (!info || !Array.isArray(info.files)) throw new Error('Invalid file response');
        currentRoot = target;
        currentFiles = info.files.filter(file => file && typeof file.name === 'string' && typeof file.path === 'string');
        filesLoaded = true;
        $('#file-filter').value = '';
        if (addHistory) history.pushState({ tab: 5, path: currentRoot }, '');
        renderFiles();
    } catch (_) {
        if (fileRequest !== controller || controller.signal.aborted) return;
        currentRoot = target; currentFiles = []; fileFailure = true; renderFiles();
    } finally {
        if (fileRequest === controller) { fileRequest = null; $('#file_list').setAttribute('aria-busy', 'false'); }
    }
}
function renderBreadcrumbs() {
    const breadcrumb = $('#file-breadcrumbs'); breadcrumb.replaceChildren();
    const root = button(t('rootFolder'), '', () => listFile('', true)); breadcrumb.append(root);
    const segments = currentRoot.split('/').filter(Boolean);
    let path = '';
    segments.forEach(segment => {
        path += '/' + segment;
        const target = path;
        breadcrumb.append(icon('chevron'), button(segment, '', () => listFile(target, true)));
    });
}
function renderFiles() {
    renderBreadcrumbs();
    const list = $('#file_list'); list.replaceChildren();
    const query = $('#file-filter').value.trim().toLocaleLowerCase();
    const files = currentFiles.filter(file => file.name.toLocaleLowerCase().includes(query));
    files.sort((a, b) => Number(b.dir) - Number(a.dir) || a.name.localeCompare(b.name, language, { numeric: true }));
    $('#file-count').textContent = filesLoaded && !fileFailure ? t('filesCount', { count: currentFiles.length }) : '';
    $('#file-empty').hidden = files.length > 0;
    $('#retry-files').hidden = !fileFailure;
    $('#file-empty-title').textContent = t(fileFailure ? 'filesError' : query ? 'noMatches' : 'emptyFolder');
    $('#file-empty-description').textContent = t(fileFailure ? 'filesErrorDescription' : query ? 'noMatchesDescription' : 'emptyFolderDescription');
    files.forEach(file => {
        const directory = Number(file.dir) === 1;
        const item = document.createElement('div'); item.className = 'file-item'; item.dataset.path = file.path;
        const main = document.createElement('button'); main.type = 'button'; main.className = 'file-main';
        const symbol = document.createElement('span'); symbol.className = 'file-symbol' + (directory ? ' directory' : ''); symbol.append(icon(directory ? 'folder' : 'file'));
        const info = document.createElement('span'); info.className = 'file-info';
        info.append(textElement('span', file.name, 'file-name'));
        if (file.time) info.append(textElement('span', String(file.time), 'file-time'));
        main.append(symbol, info); main.addEventListener('click', () => directory ? listFile(file.path, true) : showFileActions(file));
        item.append(main);
        const menu = document.createElement('button'); menu.type = 'button'; menu.className = 'icon-button'; menu.setAttribute('aria-label', t(directory ? 'folderActions' : 'fileActions') + ': ' + file.name); menu.append(icon('more'));
        menu.addEventListener('click', () => showFileActions(file)); item.append(menu);
        list.append(item);
    });
}
function showUpload(files) {
    if (uploadBusy) { announce(t('uploadingBusy')); $('#file_uploader').value = ''; return; }
    const target = currentRoot;
    const names = document.createElement('div'); names.className = 'dialog-file-list';
    files.forEach(file => names.append(textElement('div', file.name)));
    const start = button(t('uploadConfirm'), 'button button-filled', () => { closeDialog(); upload(files, target); }, 'upload');
    openDialog({ title: t('uploadTitle'), description: t('uploadDescription'), icon: 'upload', content: [names], actions: [button(t('cancel'), 'button button-tonal', () => { $('#file_uploader').value = ''; closeDialog(); }), start] });
}
function upload(files, target) {
    if (uploadBusy || files.length === 0) return;
    uploadBusy = true; $('#upload-button').disabled = true;
    $('#upload-state').hidden = false; $('#upload-progress').value = 0; $('#upload-percent').textContent = '0%';
    $('#upload-label').textContent = t('uploading', { count: files.length });
    const data = new FormData(); data.append('path', target); files.forEach((file, index) => data.append('files-' + index, file));
    const xhr = new XMLHttpRequest(); xhr.open('POST', '/upload'); xhr.timeout = 120_000;
    xhr.upload.addEventListener('progress', event => {
        if (!event.lengthComputable) { $('#upload-progress').removeAttribute('value'); $('#upload-percent').textContent = ''; return; }
        const progress = Math.round(event.loaded / event.total * 100); $('#upload-progress').value = progress; $('#upload-percent').textContent = progress + '%';
    });
    const finish = success => {
        uploadBusy = false; $('#upload-button').disabled = false; $('#file_uploader').value = ''; $('#upload-state').hidden = true;
        announce(t(success ? 'uploadComplete' : 'requestFailed'));
        if (success && currentRoot === target) listFile(currentRoot);
    };
    xhr.addEventListener('load', () => finish(xhr.status >= 200 && xhr.status < 300));
    xhr.addEventListener('error', () => finish(false)); xhr.addEventListener('timeout', () => finish(false)); xhr.addEventListener('abort', () => finish(false));
    xhr.send(data);
}
function remoteKey(key) {
    if (!remoteOpen || remotePending >= 8) return;
    remotePending++;
    remoteQueue = remoteQueue.then(async () => {
        if (!remoteOpen) return;
        try {
            await request('/remote/key', { key, action: 'press' });
            const status = $('#remote-status'); if (status) status.textContent = t('remoteSent');
        } catch (_) { announce(t('remoteUnavailable')); }
    }).finally(() => { remotePending--; });
}
function showRemote() {
    const content = document.createElement('div'); content.className = 'remote-content';
    const pad = document.createElement('div'); pad.className = 'remote-pad';
    for (const key of ['up', 'left', 'ok', 'right', 'down']) {
        const item = document.createElement('button'); item.type = 'button'; item.className = 'remote-key remote-' + key; item.dataset.remoteKey = key;
        item.setAttribute('aria-label', t('remote' + key[0].toUpperCase() + key.slice(1))); item.append(icon(key === 'ok' ? 'check' : 'chevron')); item.addEventListener('click', () => remoteKey(key)); pad.append(item);
    }
    const bottom = document.createElement('div'); bottom.className = 'remote-bottom';
    bottom.append(button(t('remoteBack'), 'button button-tonal', () => remoteKey('back'), 'replay'), button(t('remoteMenu'), 'button button-tonal', () => remoteKey('menu'), 'more'));
    const details = document.createElement('details'); details.className = 'remote-numbers'; details.append(textElement('summary', t('remoteNumbers')));
    const numbers = document.createElement('div'); numbers.className = 'number-pad';
    for (const key of ['1','2','3','4','5','6','7','8','9','0']) {
        const item = button(key, 'remote-number', () => remoteKey(key)); item.dataset.remoteKey = key; numbers.append(item);
    }
    details.append(numbers);
    const status = textElement('p', t('remoteHint'), 'remote-status'); status.id = 'remote-status'; status.setAttribute('role', 'status');
    content.append(pad, bottom, details, status);
    openDialog({ title: t('remote'), description: t('remoteDescription'), icon: 'remote', content: [content], remote: true });
}

$$('[data-panel]').forEach(element => element.addEventListener('click', () => showPanel(element.dataset.panel)));
$('.main-nav').addEventListener('keydown', event => {
    const keys = { ArrowLeft: -1, ArrowUp: -1, ArrowRight: 1, ArrowDown: 1 };
    if (!(event.key in keys) && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    const target = event.key === 'Home' ? 1 : event.key === 'End' ? 5 : ((currentPanel - 1 + keys[event.key] + 5) % 5) + 1;
    showPanel(target); $('#tab' + target).focus();
});
$('#theme-select').addEventListener('change', event => { save(STORAGE.theme, event.target.value); applyTheme(); });
$('#language-select').addEventListener('change', event => { language = event.target.value; save(STORAGE.language, language); applyLanguage(); });
const mediaQuery = matchMedia('(prefers-color-scheme: dark)');
if (mediaQuery.addEventListener) mediaQuery.addEventListener('change', applyTheme); else mediaQuery.addListener(applyTheme);
$('#keyword').addEventListener('input', event => { $('#clear-keyword').hidden = !event.target.value; });
$('#clear-keyword').addEventListener('click', () => { $('#keyword').value = ''; $('#clear-keyword').hidden = true; $('#keyword').focus(); });
$('#search-form').addEventListener('submit', async event => {
    event.preventDefault(); const word = $('#keyword').value.trim(); if (!word) { announce(t('fieldRequired')); return; }
    if (await sendAction('search', { word }, $('button[type="submit"]', event.currentTarget), 'searchSent')) remember('search', word);
});
$('#push-form').addEventListener('submit', async event => {
    event.preventDefault(); const url = $('#push_url').value.trim(); if (!url) { announce(t('fieldRequired')); return; }
    if (await sendAction('push', { url }, $('button[type="submit"]', event.currentTarget), 'pushSent')) remember('push', url);
});
$('#settings-form').addEventListener('submit', event => {
    event.preventDefault(); const text = $('#setting_text').value.trim(); if (!text) { announce(t('fieldRequired')); return; }
    sendAction('setting', { text, name: $('#setting_name').value.trim() }, $('button[type="submit"]', event.currentTarget), 'settingsSent');
});
$('#danmaku-form').addEventListener('submit', async event => {
    event.preventDefault(); const text = $('#danmaku_text').value.trim(); if (!text) return;
    const color = parseInt($('#danmaku-color').value.slice(1), 16);
    const value = `[0.0,${$('#danmaku-mode').value},${$('#danmaku-size').value},${color}]${text}`;
    if (await sendAction('danmaku', { text: value }, $('button[type="submit"]', event.currentTarget), 'danmakuSent')) $('#danmaku_text').value = '';
});
$('#danmaku-color').addEventListener('input', event => { $('#danmaku-color-value').textContent = event.target.value.toUpperCase(); });
for (const id of ['push_url', 'setting_text']) {
    $('#' + id).addEventListener('keydown', event => {
        if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey)) return;
        event.preventDefault();
        const form = event.target.form;
        if (form.requestSubmit) form.requestSubmit();
        else $('button[type="submit"]', form).click();
    });
}

for (const kind of ['search', 'push']) $('#clear-' + kind + '-history').addEventListener('click', () => { save(STORAGE[kind], '[]'); renderHistories(); announce(t('historyCleared')); });
$('#copy-address').addEventListener('click', () => copy(location.origin));
$$('.player-control').forEach(element => element.addEventListener('click', async () => {
    const type = element.dataset.control === 'toggle' ? (Number(playback.state) === 3 ? 'pause' : 'play') : element.dataset.control;
    if (await sendAction('control', { type }, element, 'controlSent')) pollDevice(true);
}));
$('#upload-button').addEventListener('click', () => $('#file_uploader').click());
$('#file_uploader').addEventListener('change', event => { const files = Array.from(event.target.files); event.target.value = ''; if (files.length) showUpload(files); });
$('#new-folder-button').addEventListener('click', showNewFolder);
$('#refresh-files').addEventListener('click', () => listFile(currentRoot));
$('#retry-files').addEventListener('click', () => listFile(currentRoot));
$('#file-filter').addEventListener('input', renderFiles);
$('#remote-button').addEventListener('click', showRemote);
$('#dialog-close').addEventListener('click', () => closeDialog());
$('#action-dialog').addEventListener('cancel', event => { event.preventDefault(); closeDialog(); });
$('#action-dialog').addEventListener('click', event => {
    if (event.target !== $('#action-dialog')) return;
    const bounds = event.target.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) closeDialog();
});
$('#action-dialog').addEventListener('keydown', event => {
    if (remoteOpen && !event.target.matches('input, textarea, select')) {
        const key = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Backspace: 'back' }[event.key];
        if (key) { event.preventDefault(); remoteKey(key); return; }
    }
    if (event.key !== 'Tab') return;
    const targets = $$('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), summary, a[href]', event.currentTarget).filter(element => element.getClientRects().length > 0);
    if (!targets.length) { event.preventDefault(); return; }
    const first = targets[0], last = targets[targets.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
});
$('#snackbar-close').addEventListener('click', () => { clearTimeout(snackbarTimer); $('#snackbar').hidden = true; });
window.addEventListener('popstate', event => {
    if ($('#action-dialog').open) closeDialog(true);
    const state = event.state;
    if (state?.tab) showPanel(state.tab, false);
    if (state && typeof state.path === 'string' && currentPanel === 5 && state.path !== currentRoot) listFile(state.path);
});
document.addEventListener('visibilitychange', schedulePoll);
window.addEventListener('online', () => pollDevice(true));
$('#host-address').textContent = location.host;
const initialPanel = Number(new URLSearchParams(location.search).get('tab')) || 1;
showPanel(initialPanel, false);
history.replaceState({ tab: currentPanel, path: currentRoot }, '');
applyTheme(); applyLanguage(); schedulePoll();
