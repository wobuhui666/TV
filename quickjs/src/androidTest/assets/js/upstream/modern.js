import {Spider, net, local, js, md5X, sha256X, htmlQuery} from 'catvod';
export default class Modern extends Spider {
    #value = 7;
    init(extend) {if(typeof extend !== 'string')throw new Error('Modern init requires original string');this.extend = JSON.parse(extend);}
    async homeContent() {return {class: [], list: [{vod_id: this.siteKey, vod_name: String(this.#value)}]};}
    detailContent(ids) {return {list: ids.map(id => ({vod_id: id}))};}
    searchContent(key, quick, pg = '1') {return {list: [], key, quick, page: pg};}
    playerContent(flag, id, flags) {return {parse:0, url:id, flag, flags};}
    liveContent(mode) {return mode === 'text' ? '#EXTM3U\nfixture' : [{name:'live',channel:[]}];}
    manualVideoCheck() {return true;}
    isVideoFormat(url) {return url.endsWith('.mp4');}
    async proxy(params) {
        globalThis.proxyStopped = 0;
        const reader = await net.stream((async function*(){try {yield new Uint8Array([0,127,128,255]);}finally{globalThis.proxyStopped++;}})());
        if (params.mode === 'bad-header') return [200,reader.contentType,reader.stream,'invalid JSON'];
        if (params.mode === 'bad-serialize') {const cyclic={};cyclic.value=cyclic;return [200,reader.contentType,reader.stream,cyclic];}
        return [reader.code,reader.contentType,reader.stream,{'X-Probe':'stream'}];
    }
    async action(mode) {
        if (mode === 'dialog') return this.dialog({title:'modern'}, async event => ({received:event.action}));
        if (mode === 'helpers') {
            const mapped = await net.map([3,1,2], async (item,index) => ({item,index}),2);
            const evaluated = await js.eval('Promise.resolve(x + 1)', {globals:{x:8}});
            local.set('saved', {value:false});
            const plan = {queries:{rows:{selector:'li',fields:{text:{}}}}};
            return {mapped, evaluated, stored:local.get('saved'), fallback:local.get('missing','fallback'),
                md5:md5X('abcdef'),sha:sha256X('abcdef'),html:htmlQuery('<li>fixture</li>',plan),proxy:this.proxyUrl({q:'中'}),dm:this.danmakuUrl('a/b')};
        }
        if (mode === 'proxy-state') return {stops:globalThis.proxyStopped||0};
        if (mode === 'bad') return {bad:Promise.resolve(1)};
        if (mode === 'late') {if (this.emit) this.emit({text:'late'});return {};}
        return {stops:this.stops||0};
    }
    danmaku(id, emit) {
        this.emit=emit;
        emit({text:id,color:16711680});
        const timer=setTimeout(()=>emit({text:'timer'}),500);
        return () => {clearTimeout(timer);this.stops=(this.stops||0)+1;emit({text:'closing'});};
    }
}
