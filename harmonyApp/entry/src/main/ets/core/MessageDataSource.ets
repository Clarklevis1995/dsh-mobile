import { MessageView } from '../components/ViewModels';

/** 只通知结构变化；同 ID 的正文更新由 @Observed MessageView 驱动。 */
export class MessageDataSource implements IDataSource {
  private rows: MessageView[] = [];
  private scope: string = '';
  private listeners: DataChangeListener[] = [];
  totalCount(): number { return this.rows.length; }
  getData(index: number): MessageView { return this.rows[index]; }
  registerDataChangeListener(listener: DataChangeListener): void { if (!this.listeners.includes(listener)) { this.listeners.push(listener); } }
  unregisterDataChangeListener(listener: DataChangeListener): void { const index = this.listeners.indexOf(listener); if (index >= 0) { this.listeners.splice(index, 1); } }
  update(rows: MessageView[], scope: string): boolean {
    // @Link 数组可能被原地更新，数据源必须保存独立容器才能比较结构。
    const old = this.rows; this.rows = rows.slice();
    if (scope === this.scope && !old.length && !rows.length) { return false; }
    if (scope !== this.scope || !old.length) { this.scope = scope; this.listeners.forEach(listener => listener.onDatasetChange([{ type: DataOperationType.RELOAD }])); return true; }
    let start = 0; while (start < old.length && start < rows.length && old[start].id === rows[start].id) { start++; }
    let suffix = 0; while (suffix < old.length - start && suffix < rows.length - start && old[old.length - 1 - suffix].id === rows[rows.length - 1 - suffix].id) { suffix++; }
    const remove = old.length - start - suffix; const add = rows.length - start - suffix;
    const operations: DataOperation[] = [];
    if (remove) { operations.push({ type: DataOperationType.DELETE, index: start, count: remove }); }
    if (add) { operations.push({ type: DataOperationType.ADD, index: start, count: add }); }
    // 相同 ID 但对象已换代时，必须重新绑定，不能展示旧主机或旧连接的对象。
    let rebound = false;
    rows.forEach((row: MessageView, index: number) => {
      const oldIndex = index < start ? index : index >= rows.length - suffix ? old.length - rows.length + index : -1;
      if (oldIndex >= 0 && old[oldIndex] !== row) { rebound = true; operations.push({ type: DataOperationType.CHANGE, index }); }
    });
    if (operations.length) { this.listeners.forEach(listener => listener.onDatasetChange(operations)); }
    return operations.length > 0 || rebound;
  }
}
