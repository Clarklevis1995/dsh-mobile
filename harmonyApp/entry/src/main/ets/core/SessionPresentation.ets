/** 冻结移动端的相对日期展示；只使用共享目录时间，不在UI中判断业务未读。 */
export function sessionRelativeTime(epochSeconds: number, now: number, english: boolean): string {
  if (!Number.isFinite(epochSeconds) || epochSeconds <= 0) { return '—'; }
  const event = Math.min(now, epochSeconds * 1000); const currentDate = new Date(now); const eventDate = new Date(event);
  // 比较本地日历日期，避免夏令时的23/25小时被误判为同一天。
  const day = (date: Date): number => Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
  const days = Math.floor((day(currentDate) - day(eventDate)) / 86400000);
  if (days === 1) { return english ? 'Yesterday' : '昨天'; }
  if (days === 2) { return english ? 'The day before yesterday' : '前天'; }
  if (days > 2) { return english ? days + ' days ago' : days + ' 天前'; }
  const seconds = Math.max(0, Math.floor((now - event) / 1000));
  if (seconds < 60) { return english ? 'Just now' : '刚刚'; }
  const count = Math.floor(seconds / (seconds < 3600 ? 60 : 3600));
  return english ? count + (seconds < 3600 ? count === 1 ? ' minute ago' : ' minutes ago' : count === 1 ? ' hour ago' : ' hours ago') : count + (seconds < 3600 ? ' 分钟前' : ' 小时前');
}
