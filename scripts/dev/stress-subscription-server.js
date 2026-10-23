#!/usr/bin/env node
/**
 * 本地节点数量压测订阅服务器。
 *
 * 返回 N 个（默认 60，由第 3 个参数指定）VLESS 假节点组成的
 * Base64 订阅体（每个节点独立 IP，协议与回环节点同构，
 * 可被 SingFlow 的 VlessParser 解析但不可连通，
 * 用于验证批量导入、列表渲染与一键探活并发调度）。
 *
 * 用法：node scripts/dev/stress-subscription-server.js [port] [nodeCount]
 */
const http = require('http');

const port = Number(process.argv[2] || 8088);
const nodeCount = Math.max(1, Number(process.argv[3] || 60));

const links = Array.from({ length: nodeCount }, (_, i) => {
  const octet3 = Math.floor(i / 250);
  const octet4 = (i % 250) + 2;
  const name = `Stress-${String(i + 1).padStart(3, '0')}`;
  return `vless://11111111-1111-1111-1111-111111111111@10.99.${octet3}.${octet4}:8443?encryption=none&security=none&type=tcp#${name}`;
});
const body = Buffer.from(links.join('\n'), 'utf8').toString('base64');
const userInfo = 'upload=1048576; download=2097152; total=107374182400; expire=1767225600';

http
  .createServer((req, res) => {
    if (req.url && req.url.startsWith('/sub')) {
      res.writeHead(200, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Subscription-Userinfo': userInfo,
        'Content-Length': Buffer.byteLength(body),
      });
      res.end(body);
      return;
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('not found');
  })
  .listen(port, '127.0.0.1', () => {
    console.log(`[stress-subscription] listening on 127.0.0.1:${port}/sub (${nodeCount} nodes)`);
  });
