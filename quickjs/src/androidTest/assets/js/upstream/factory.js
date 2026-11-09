export default function(site) {return {
    init(ext){this.ext=ext;},home(){return JSON.stringify({class:[],site:site.key});},
    detail(id){return {list:[{vod_id:id}]};},search(...args){return {args};},
    live(){return '#EXTM3U\nlegacy';},sniffer(){return true;},isVideo(){return false;},
    action(){return Promise.resolve(JSON.stringify({legacy:true}));},
    proxy(params){return [200,'application/octet-stream',new Uint8Array([0,127,128,255]),params.mode==='malformed'?'not JSON':''];},destroy(){}
};}
