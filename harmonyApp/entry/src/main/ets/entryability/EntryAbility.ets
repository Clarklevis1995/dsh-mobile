import { parseNotificationTarget } from '../core/NotificationTarget';
import { UIAbility, Want, AbilityConstant } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';

export default class EntryAbility extends UIAbility {
  private notification(want: Want): void {
    const value = want.parameters?.whalenessNotificationTarget;
    if (typeof value !== 'string' || !parseNotificationTarget(value)) { return; }
    AppStorage.setOrCreate<string>('whaleness.notification-target', value); this.context.eventHub.emit('notificationTarget');
  }
  onCreate(want: Want, _launchParam: AbilityConstant.LaunchParam): void { this.notification(want); }
  onNewWant(want: Want, _launchParam: AbilityConstant.LaunchParam): void { this.notification(want); }
  onForeground(): void { this.context.eventHub.emit('gatewayForeground'); }
  onBackground(): void { this.context.eventHub.emit('gatewayBackground'); }
  onWindowStageCreate(stage: window.WindowStage): void {
    stage.loadContent('pages/Index');
  }
}
