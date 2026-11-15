# 源公共弹窗

本功能移植自 FongMi/TV `c956dbc980c65cba6135c13a521aa123d5c2db28`，适配本项目内置 Java、Python、QuickJS 运行时及 JetStream 界面。供源进行登录、验证码、二维码确认、图片展示和选项输入；不替源自动登录。

## 调用方式

Java：在源的工作线程中调用继承的 `dialog.show(JSONObject options)`；需要按钮回调时使用 `dialog.show(options, event -> patch)`。返回 JSONObject 或用户取消时的 null。

Python：`result = self.dialog(options, onAction=None)`。参数、结果和回调事件为 dict，用户取消返回 None。回调返回更新 dict 或 None。

QuickJS：`const result = await dialog({...options, onAction: async event => patch})`。返回对象，用户取消返回 null。可从 `init/home/detail/play/action` 等源调用中使用，连续 `await dialog(...)` 会按顺序显示。脚本顶层与已结束调用的遗留回调不能打开新弹窗。

三种语言的字段协议相同，回调函数通过各自语言入口传递，不放进 JSON 字符串。请勿在源中记录密码、验证码或完整回调事件。

## 简单登录表单

```javascript
const result = await dialog({
    title: '登录此视频源',
    message: '输入验证码后继续',
    inputs: [
        {name: 'phone', label: '手机号', type: 'phone', required: true},
        {name: 'code', label: '验证码', minLength: 4, maxLength: 8, required: true}
    ],
    buttons: [{id: 'login', label: '登录'}]
});
if (result !== null) {
    const phone = result.values.phone;
    const code = result.values.code;
    // 使用此源自己的登录接口。验证成功后再存储源自己的会话。
}
```

未传 buttons 时提供确认/取消；结果为 `{"button":"ok","values":{...}}`。自定义 buttons 有 1 到 3 个，依次对应主操作、次操作和第三操作，id 必须唯一。默认按钮会验证所有启用字段；`validate: []` 可跳过字段验证，或传字段名数组只验证其中一部分。

`cancelable` 默认为 true。返回/取消与离开调用页面返回 null；调用任务被取消或源释放时会取消未完成请求，源应按自身异常处理流程退出。

弹窗在发起调用时绑定页面，跨线程和延迟显示保持该归属；页面暂停、销毁或来源切换后，旧请求不能在首页重新显示。同一页面同时只显示一个公共弹窗，重复请求返回 null；先关闭前一步，再继续调用仍可显示后续表单。片库中的配置操作在进行中忽略重复点击。

## 二维码、图片和选项

- `qrcode`：非空文本，例如当前源返回的扫码地址，最多 4096 字符。
- `image`：`data:image/png;base64,...` 等 base64 图片 data URI，最大 1 MiB 字符。网络图片需先通过源自己的网络客户端下载并转为 data URI，从而保留该源的鉴权和代理策略。空字符串可清除图片。
- `message`：说明文字，最多 65536 字符；`copyable: true` 提供复制按钮。
- `inputs`：最多 32 项；支持 `text`、`password`、`phone`、`number`、`textarea`、`select`、`multiselect`。
- 文本输入/单选结果为字符串，多选为字符串数组。字段支持 `label`、`helperText`、`value`、`required`、`enabled`、`minLength/maxLength`；多选用 `minItems/maxItems`。
- 选项格式为 `options: [{value:'id',label:'显示名'}]`，值为唯一非空字符串，每个字段最多 256 个选项。

## 在同一弹窗内验证或刷新二维码

```javascript
const result = await dialog({
    title: '验证码登录',
    inputs: [{name:'code',label:'验证码',required:true}],
    buttons: [
        {id:'refresh',label:'刷新',close:false,validate:[]},
        {id:'login',label:'登录'}
    ],
    async onAction(event) {
        if (event.button === 'refresh') {
            const value = await net.json('https://source.example/captcha');
            return {image:value.dataUri, message:'请输入新验证码', close:false};
        }
        const reply = await net.json('https://source.example/login', {
            method:'POST', data:event.values
        });
        if (!reply.ok) return {errors:{code:'验证码错误'}, close:false};
        return {close:true};
    }
});
```

回调可返回 `message`、`qrcode`、`image`、`values`（按字段名更新）、`errors`（按字段名显示错误），以及 `inputs:[{name,enabled}]`、`buttons:[{id,enabled}]`。`close` 默认采用被点按钮的配置；存在非空字段错误时继续显示。回调运行时防止重复点击，失败会结束当前请求，不会记录表单内容。

Java/Python 回调在发起调用的工作线程执行；QuickJS 回调回到本源的 JS 线程，并保留其原始调用作用域。等待用户输入时暂停 ViewModel、聚合搜索（含整轮搜索预算）和手动播放解析的计算超时；回调执行和回调网络操作仍计入剩余预算。调用被替换、取消或源释放时关闭该调用的弹窗，不影响其他源。聚合搜索继续共享六个实际执行槽位；不响应取消的源仍占槽，不能通过反复登录／取消增加线程。

下一集预加载、媒体浏览器的列表枚举／搜索和本地媒体代理请求禁止交互。该策略随 Java 初始化线程、QuickJS 异步调用和内部浏览搜索任务传递，遇到 `dialog` 会返回受控失败；因后台策略拒绝交互、没有可用页面或弹窗交互失败而初始化失败的源不会缓存为不可用实例，用户随后主动播放时仍可重试并正常显示登录表单。旧 JAR 已定义全局 `dialog` 时保留其实现。

使用公共弹窗的调用不在宿主日志中预览返回对象，自动回包和异常也会隐藏动态内容。表单结果原样交给源，日志省略不改变源获取的密码／验证码值。

## 验证

- `:catvod:testDebugUnitTest`：Dialog owner 隔离、取消、CallTimeout 输入等待/嵌套/恢复。
- `:quickjs:testDebugUnitTest`：Invocation 作用域、取消及按钮回调计时。
- `node quickjs/src/test/js/dialog.test.cjs`：JS facade 协议、连续请求、回调及取消。
- `python -m unittest discover -s chaquo/src/test/python -p 'test_dialog.py' -v`：Python dict/JSON 桥接、回调、关闭与非法参数。
- preview 设备测试类 `com.fongmi.android.tv.test.SpiderDialogTest`：真实 Java 与 QuickJS 表单、输入验证、二维码、选择器、遥控焦点、异步连续弹窗、任务取消与超时暂停；只使用 loopback HTTP 和虚构登录数据。截图目录为应用 externalFiles 下的 `spider-dialog-verification/`。

真实 Python 虚拟机调用还需要包含 Python 原生库的 ARM 测试设备；Python facade 测试不能替代该验证。需要源主动接入上述 API，现有源不会自动出现登录界面。

## 旧 Java 源的自建窗口

片库配置操作兼容 `com.github.catvod.spider.*` 中使用 Activity 构造的旧 Android Dialog：窗口绑定到发起操作的页面。Android 10／API 29 及以上还检查新增窗口，清理重复窗口与离开页面后的延迟窗口；较低 API 不包含这项旧窗口清理。自建窗口未经过公共弹窗协议，独立异步回调只能按来源包识别归属，无法精确区分所有未知 JAR 的调用。新源应使用公共接口传递页面、取消和连续交互的生命周期。
