const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../../main/assets/js/lib');
const facade = fs.readFileSync(path.join(assets, 'catvod.js'), 'utf8').replace(/export /g, '') + '\nglobalThis.Spider = Spider;';
const loader = fs.readFileSync(path.join(assets, 'spider.js'), 'utf8').replace(/^import .*;$/gm, '');
function context(entry) {
    const values = new Map();
    const emitted = [];
    const ctx = vm.createContext({console, entry, emitted, __SPIDER_SITE__: {key:'source-A'},
        net:{}, http:()=>{}, wasm:{}, dialog:()=>{}, proxyUrl:()=>'/proxy', danmakuUrl:id=>'spider://source-A/'+id,
        local:{get:key=>values.get(key)??null,set:(key,value)=>values.set(key,value),delete:key=>values.delete(key),clear:()=>values.clear()},
        __SPIDER_JS_EVAL__:async()=>9, __SPIDER_DANMAKU_EMIT__:(token,json)=>emitted.push([token,JSON.parse(json)])});
    vm.runInContext(facade,ctx);
    return ctx;
}
(async()=>{
    const ctx=context({});
    vm.runInContext("globalThis.moduleDialog=dialog;",ctx);
    ctx.dialog = options => {ctx.receivedDialog=options;return Promise.resolve({button:'ok'});};
    const action=()=>({clicked:true});
    await ctx.moduleDialog({title:'test'},action);
    assert.equal(ctx.receivedDialog.onAction,action);
    assert.equal(ctx.receivedDialog.title,'test');
    await assert.rejects(ctx.moduleDialog({},3),/callback/);
    vm.runInContext(`entry.default=class Modern extends Spider {
        #value=7;
        homeContent(){return Promise.resolve({class:[],list:[this.#value,this.siteKey]});}
        detailContent(ids){return {list:ids};}
        danmaku(id,emit){this.emit=emit;emit({text:id});return ()=>{this.stops=(this.stops||0)+1;emit({text:'late'});};}
        action(name){if(name==='nested')return {bad:Promise.resolve(1)};return {stops:this.stops||0};}
    };`,ctx);
    vm.runInContext(loader,ctx);
    assert.equal(ctx.__SPIDER_STANDARD__,true);
    assert.deepEqual(JSON.parse(await ctx.__JS_SPIDER__.home()),{class:[],list:[7,'source-A']});
    assert.deepEqual(JSON.parse(await ctx.__JS_SPIDER__.detail(['one','two'])),{list:['one','two']});
    await assert.rejects(ctx.__JS_SPIDER__.action('nested'),/Await asynchronous/);
    await ctx.__JS_SPIDER__.startDanmaku('live',1);
    await ctx.__JS_SPIDER__.stopDanmaku(1);
    await ctx.__JS_SPIDER__.stopDanmaku(1);
    assert.deepEqual(ctx.emitted,[[1,{text:'live'}]]);
    assert.equal(JSON.parse(await ctx.__JS_SPIDER__.action('state')).stops,1);
    await ctx.__JS_SPIDER__.destroy();
    await assert.rejects(ctx.__JS_SPIDER__.startDanmaku('late',2),/closed/);
    for(const kind of ['factory','object','frozen','cat']) {
        const old=context({});
        vm.runInContext(`const object={init(ext){this.ext=ext;},home(){return '{"class":[]}';},detail(id){return {list:[id]};},search(...args){return args;},destroy(){}};
            entry.${kind==='cat'?'__jsEvalReturn':'default'}=${kind==='frozen'?'Object.freeze(object)':kind==='object'?'object':kind==='factory'?'site=>{object.site=site;return object;}':'()=>object'};`,old);
        vm.runInContext(loader,old);
        assert.equal(old.__JS_SPIDER__.home(),'{"class":[]}');
        assert.deepEqual(JSON.parse(JSON.stringify(old.__JS_SPIDER__.detail('one'))),{list:['one']});
        assert.deepEqual(JSON.parse(JSON.stringify(old.__JS_SPIDER__.search('k',true,'3'))),['k',true,'3']);
        if(kind==='factory')assert.equal(old.__JS_SPIDER__.site.key,'source-A');
    }
    const race=context({});
    vm.runInContext(`let resolveStart; entry.default=class Pending extends Spider {danmaku(id,emit){this.emit=emit; return new Promise(resolve=>{resolveStart=resolve;});}};
        globalThis.finishStart=()=>resolveStart(()=>{globalThis.stopCount=(globalThis.stopCount||0)+1;});`,race);
    vm.runInContext(loader,race);
    const starting=race.__JS_SPIDER__.startDanmaku('pending',1);
    await race.__JS_SPIDER__.stopDanmaku(1);
    race.finishStart();
    await starting;
    assert.equal(race.stopCount,1);
    await race.__JS_SPIDER__.destroy();
    console.log('Spider class/factory/object/legacy JSON and danmaku lifecycle checks passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
