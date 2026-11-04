// 仅供 App 原生互通测试：使用网关仓库的隔离 Host 替身和一次性测试设备，不连接用户会话。
import http from 'node:http'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const root = process.argv[2]
if (!root) throw new Error('usage: node scripts/webrtc-test-gateway.mjs /path/to/dsh-plugin-mobile-gateway')
const { startGateway } = await import(pathToFileURL(path.join(root, 'test/webrtc/helpers.mjs')).href)
const fixtures = {
  rtc: await startGateway(),
  legacy: await startGateway({ enabled: false }),
  fallback: await startGateway({ enabled: true, rtcConnectTimeoutMs: 100 }),
}
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  const fixture = fixtures[url.searchParams.get('mode') || 'rtc']
  if (!fixture) { res.writeHead(404).end(); return }
  res.setHeader('Content-Type', 'application/json')
  if (url.pathname === '/status') {
    res.end(JSON.stringify(await fixture.request('status')))
  } else {
    res.end(JSON.stringify({ url: fixture.url, gatewayId: fixture.gatewayId, deviceId: fixture.deviceId, token: fixture.token }))
  }
})
await new Promise(resolve => server.listen(18765, '127.0.0.1', resolve))
console.log('App RTC isolated fixtures listening on loopback port 18765')
async function close() {
  server.closeAllConnections(); server.close()
  await Promise.all(Object.values(fixtures).map(fixture => fixture.close()))
}
process.once('SIGTERM', () => void close())
process.once('SIGINT', () => void close())
