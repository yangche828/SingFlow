#!/usr/bin/env node
/**
 * 本地 WebDAV 测试服务器（内存存储，Basic Auth）。
 *
 * PUT /backup.json 上传；GET 下载；用于验证 SingFlow WebDAV 备份。
 */
const http = require('http');

const port = Number(process.argv[2] || 8089);
const user = process.argv[3] || 'test';
const pass = process.argv[4] || 'secret';
const expected = 'Basic ' + Buffer.from(`${user}:${pass}`).toString('base64');
let stored = null;

http
  .createServer((req, res) => {
    if (req.headers.authorization !== expected) {
      res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="singflow"' });
      res.end('unauthorized');
      return;
    }
    if (req.method === 'PUT') {
      const chunks = [];
      req.on('data', (chunk) => chunks.push(chunk));
      req.on('end', () => {
        stored = Buffer.concat(chunks);
        console.log(`[webdav] PUT ${req.url} ${stored.length} bytes`);
        res.writeHead(201);
        res.end('created');
      });
      return;
    }
    if (req.method === 'GET') {
      if (!stored) {
        res.writeHead(404);
        res.end('not found');
        return;
      }
      console.log(`[webdav] GET ${req.url} ${stored.length} bytes`);
      res.writeHead(200, {
        'Content-Type': 'application/json',
        'Content-Length': stored.length,
      });
      res.end(stored);
      return;
    }
    res.writeHead(405);
    res.end('method not allowed');
  })
  .listen(port, '127.0.0.1', () => {
    console.log(`[webdav] listening on 127.0.0.1:${port} user=${user}`);
  });
