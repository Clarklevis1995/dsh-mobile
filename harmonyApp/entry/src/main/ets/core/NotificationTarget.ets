import { NotificationTarget } from './NotificationTypes';

/** 外部 Want 只允许携带有界本地导航标识；身份与会话存在性由根页再次验证。 */
export function parseNotificationTarget(value: string): NotificationTarget | undefined {
  if (!value || value.length > 5000) { return undefined; }
  try {
    const target = JSON.parse(value) as NotificationTarget;
    if (typeof target.host !== 'string' || !/^[a-zA-Z0-9_-]{1,128}$/.test(target.host) ||
      typeof target.session !== 'string' || !target.session || target.session.length > 1024) { return undefined; }
    return { host: target.host, session: target.session };
  } catch (_) { return undefined; }
}
