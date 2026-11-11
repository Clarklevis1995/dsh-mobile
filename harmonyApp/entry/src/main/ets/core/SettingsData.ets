import { ControlData, HostSnapshotData, emptyControls } from './Models';

/** 设置目的页通过可观察对象接收在途默认配置与连接变化。 */
@Observed
export class SettingsData {
  connected: boolean = false;
  hostSnapshot?: HostSnapshotData;
  controls: ControlData = emptyControls();
  endpoint: string = '';
  hostName: string = '';
  status: string = '尚未连接';
}
