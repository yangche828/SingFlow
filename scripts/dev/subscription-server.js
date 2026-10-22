#!/usr/bin/env node
/**
 * 本地订阅测试服务器。
 *
 * 返回两条 VLESS 回环节点（8445/8446）的 Base64 订阅，
 * 并附带 Subscription-Userinfo 头，用于验证 SingFlow 订阅导入与流量展示。
 */
const http = require('http');

const port = Number(process.argv[2] || 8088);
const links = [
  'vless://11111111-1111-1111-1111-111111111111@127.0.0.1:8445?encryption=none&security=none&type=tcp#%F0%9F%87%AF%F0%9F%87%B5%20JP-SUB-A',
  'vless://11111111-1111-1111-1111-111111111111@127.0.0.1:8446?encryption=none&security=none&type=tcp#%F0%9F%87%AF%F0%9F%87%B5%20JP-SUB-B',
].join('\n');

const body = Buffer.from(links, 'utf8').toString('base64');
const userInfo = 'upload=104857600; download=536870912; total=107374182400; expire=1767225600';

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
    console.log(`[subscription] listening on 127.0.0.1:${port}/sub (${links.length} nodes)`);
  });
