import {Spider} from 'catvod';
export default class Pending extends Spider {
    danmaku(id, emit) {
        emit({text:'started'});
        this.emit = emit;
        return new Promise(() => {});
    }
    destroy() {this.emit?.({text:'late'});}
}
