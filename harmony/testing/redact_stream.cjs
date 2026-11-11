// CLI 自带启动 URL 的临时访问令牌也不能进入测试日志。
const readline = require('node:readline');
readline.createInterface({ input: process.stdin }).on('line', line => {
  const redacted = line.replace(/([?&](?:token|pairingCode)=)[^\s&#]+/gi, '$1<redacted>')
    .replace(/("(?:token|pairingCode|authorization|deviceId)"\s*:\s*")[^"]*"/gi, '$1<redacted>"');
  process.stdout.write(redacted + '\n');
});
