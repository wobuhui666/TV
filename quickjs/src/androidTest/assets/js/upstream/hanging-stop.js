import {Spider} from 'catvod';
export default class HangingStop extends Spider {
    danmaku(id, emit) {emit({text:'started'});return () => new Promise(() => {});}
    destroy() {return new Promise(() => {});}
}
