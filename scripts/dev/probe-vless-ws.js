#!/usr/bin/env node
/*
 * VLESS over WebSocket over TLS 连通性探针（PC 侧，仅用于验收前的独立核对）。
 *
 * 作用：不依赖 SingFlow，直接在 PC 上验证「目标 VLESS-WS 节点」是否接受连接，
 *       从而在跑模拟器 E2E 之前把「节点本身不通」和「App 有问题」区分开。
 *
 * 链路：本机 HTTP 代理(CONNECT) -> 目标 TLS(SNI) -> WebSocket 升级 -> 读 101。
 *
 * 用法：
 *   node scripts/dev/probe-vless-ws.js --host 203.0.113.10 --port 8443 \
 *        --sni node.example.com --path / --ws-host node.example.com [--proxy http://127.0.0.1:10808]
 */
const http = require('node:http')
const tls = require('node:tls')
const crypto = require('node:crypto')

function arg(name, def = '') {
  const i = process.argv.indexOf('--' + name)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : def
}

const host = arg('host')
const port = Number(arg('port', '443'))
const sni = arg('sni', host)
const wsPath = arg('path', '/')
const wsHost = arg('ws-host', sni)
const proxy = arg('proxy', '')
const timeoutMs = Number(arg('timeout', '20000'))

if (!host) {
  console.error('缺少 --host')
  process.exit(2)
}

function log(msg) {
  console.log(msg)
}

function connectTcp() {
  return new Promise((resolve, reject) => {
    if (!proxy) {
      const s = require('node:net').connect({ host, port })
      s.once('connect', () => resolve(s))
      s.once('error', reject)
      return
    }
    const u = new URL(proxy)
    const req = http.request({
      host: u.hostname,
      port: Number(u.port || 80),
      method: 'CONNECT',
      path: `${host}:${port}`,
      headers: { Host: `${host}:${port}` }
    })
    req.on('connect', (res, socket) => {
      if (res.statusCode !== 200) {
        reject(new Error(`代理 CONNECT 失败: ${res.statusCode}`))
        return
      }
      log(`[OK] 通过代理 ${proxy} 建立到 ${host}:${port} 的 TCP 隧道`)
      resolve(socket)
    })
    req.on('error', reject)
    req.end()
  })
}

async function main() {
  const tcp = await connectTcp()
  if (!proxy) log(`[OK] 直连 ${host}:${port} 成功`)

  const secure = await new Promise((resolve, reject) => {
    const s = tls.connect({ socket: tcp, servername: sni, ALPNProtocols: ['http/1.1'], rejectUnauthorized: true })
    s.once('secureConnect', () => resolve(s))
    s.once('error', reject)
  })
  const cert = secure.getPeerCertificate()
  log(`[OK] TLS 握手成功 SNI=${sni} ALPN=${secure.alpnProtocol || '(none)'}`)
  log(`[OK] 证书 CN/SAN: ${cert.subject && cert.subject.CN} / ${(cert.subjectaltname || '').split(',').slice(0, 4).join(',')}`)

  const key = crypto.randomBytes(16).toString('base64')
  const req =
    `GET ${wsPath} HTTP/1.1\r\n` +
    `Host: ${wsHost}\r\n` +
    `Upgrade: websocket\r\n` +
    `Connection: Upgrade\r\n` +
    `Sec-WebSocket-Key: ${key}\r\n` +
    `Sec-WebSocket-Version: 13\r\n\r\n`
  secure.write(req)

  const status = await new Promise((resolve, reject) => {
    let buf = ''
    const timer = setTimeout(() => reject(new Error('等待 WS 升级响应超时')), timeoutMs)
    secure.on('data', (chunk) => {
      buf += chunk.toString('latin1')
      if (buf.includes('\r\n\r\n')) {
        clearTimeout(timer)
        resolve(buf.split('\r\n')[0])
      }
    })
    secure.once('error', (e) => {
      clearTimeout(timer)
      reject(e)
    })
  })

  if (/^HTTP\/1\.1 101/.test(status)) {
    log(`[OK] WebSocket 升级成功: ${status}`)
    log('[PASS] 该 VLESS-WS 节点在 TLS + WS 层可用')
    secure.destroy()
    process.exit(0)
  }
  log(`[FAIL] WS 升级未成功: ${status}`)
  secure.destroy()
  process.exit(1)
}

main().catch((e) => {
  console.error(`[FAIL] ${e.message}`)
  process.exit(1)
})
