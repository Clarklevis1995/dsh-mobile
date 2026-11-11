/** 原生日期选择器使用设备本地时区；拒绝被 Date 自动归一化的非法日期和 DST 缺失时间。 */
export function localScheduleInstant(year: number, month: number, day: number, hour: number, minute: number): string | undefined {
  const value = new Date(year, month, day, hour, minute, 0, 0);
  if (!Number.isFinite(value.getTime()) || value.getFullYear() !== year || value.getMonth() !== month || value.getDate() !== day || value.getHours() !== hour || value.getMinutes() !== minute) { return undefined; }
  return value.toISOString();
}
export function scheduleDate(value: string): string { const date = new Date(value); return Number.isFinite(date.getTime()) ? date.toLocaleString() : value; }
