// SingFlow 本地联调用的最小 VLESS 服务端（TCP + UDP/XUDP）
//
// 用途：在没有公网节点的情况下，配合 `adb reverse` 在模拟器/真机上验证
//       「TUN 抓包 -> sing-box 内核 -> VLESS 出站 -> 真实转发」这条完整链路。
//
// 用法：
//   node scripts/dev/vless-loopback-server.js [--port 8443] [--host 127.0.0.1] [--uuid <hex32>]
//   环境变量 VLESS_DEBUG=1 可打印每条连接的首包 hex（排查用）
//
// 安全说明：仅供本机开发/验收使用，默认只监听 127.0.0.1。请勿暴露到公网。
//
// 支持的请求形态（与 sing-box / Xray 服务端行为一致）：
//   command=1 (TCP)  : [ver][uuid][addonLen][addons][cmd][port][atyp][addr][data...]
//   command=2 (UDP)  : 同上，其后为 [2B 长度][UDP 载荷] 循环
//   command=3 (XUDP) : 请求头不含目标地址，目标由每个 XUDP 帧携带
//                      （sing-box 未显式配置 packet_encoding 时的默认形态）
const net = require('node:net');
const dgram = require('node:dgram');

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const PORT = Number(arg('port', 8443));
const HOST = arg('host', '127.0.0.1');
const UUID_HEX = (arg('uuid', '11111111111111111111111111111111') || '').toLowerCase();
const DEBUG = process.env.VLESS_DEBUG === '1';

const CommandTCP = 1;
const CommandUDP = 2;
const CommandMux = 3; // XUDP 使用 Mux 命令承载

const StatusNew = 1;
const StatusKeep = 2;
const StatusEnd = 3;
const OptionData = 1;
const NetworkTCP = 1;
const NetworkUDP = 2;

/** 解析 VLESS 请求头，数据不足时返回 null。 */
function parseHeader(buf) {
  if (buf.length < 1 + 16 + 1) return null;
  let offset = 0;
  const version = buf[offset]; offset += 1;
  const uuid = buf.subarray(offset, offset + 16).toString('hex'); offset += 16;
  const addonLen = buf[offset]; offset += 1;
  if (buf.length < offset + addonLen + 1) return null;
  offset += addonLen;
  const command = buf[offset]; offset += 1;

  // XUDP：请求头不带目标地址，目标在每个 XUDP 帧里。
  if (command === CommandMux) {
    return { version, uuid, command, host: null, port: 0, offset };
  }

  if (buf.length < offset + 2 + 1) return null;
  const port = buf.readUInt16BE(offset); offset += 2;
  const atyp = buf[offset]; offset += 1;

  let host;
  if (atyp === 1) {
    if (buf.length < offset + 4) return null;
    host = Array.from(buf.subarray(offset, offset + 4)).join('.');
    offset += 4;
  } else if (atyp === 2) {
    if (buf.length < offset + 1) return null;
    const len = buf[offset]; offset += 1;
    if (buf.length < offset + len) return null;
    host = buf.subarray(offset, offset + len).toString();
    offset += len;
  } else if (atyp === 3) {
    if (buf.length < offset + 16) return null;
    const parts = [];
    for (let i = 0; i < 16; i += 2) {
      parts.push(buf.readUInt16BE(offset + i).toString(16));
    }
    host = parts.join(':');
    offset += 16;
  } else {
    throw new Error('unsupported atyp ' + atyp);
  }

  return { version, uuid, command, host, port, offset };
}

/** command=1：HTTP/HTTPS 等 TCP 流量，直接透传。 */
function serveTcp(client, header, buf) {
  const upstream = net.connect(header.port, header.host, () => {
    // VLESS 响应头：version + addonLen
    client.write(Buffer.from([0, 0]));
    const payload = buf.subarray(header.offset);
    if (payload.length > 0) upstream.write(payload);
    client.pipe(upstream);
    upstream.pipe(client);
  });
  upstream.on('error', (err) => {
    console.log('[vless] upstream error', err.message);
    client.destroy();
  });
  upstream.on('close', () => client.destroy());
  client.on('error', () => upstream.destroy());
}

/** command=2：请求头已含目标地址，其后为 [2B 长度][载荷] 循环。 */
function serveUdp(client, header, buf) {
  const udp = dgram.createSocket('udp4');
  const target = header.host;
  const destPort = header.port;
  client.write(Buffer.from([0, 0]));

  let pending = buf.subarray(header.offset);
  const flush = () => {
    while (pending.length >= 2) {
      const len = pending.readUInt16BE(0);
      if (pending.length < 2 + len) return;
      const payload = pending.subarray(2, 2 + len);
      pending = pending.subarray(2 + len);
      udp.send(payload, destPort, target, (err) => {
        if (err) console.log('[vless] udp send error', err.message);
      });
    }
  };
  flush();

  client.on('data', (chunk) => {
    pending = Buffer.concat([pending, chunk]);
    flush();
  });

  udp.on('message', (msg) => {
    const frame = Buffer.alloc(2 + msg.length);
    frame.writeUInt16BE(msg.length, 0);
    msg.copy(frame, 2);
    client.write(frame);
  });
  udp.on('error', (err) => {
    console.log('[vless] udp error', err.message);
    client.destroy();
  });
  client.on('error', () => udp.close());
  client.on('close', () => udp.close());
}

// XUDP 帧：
//   [2B frameLen=5+addrLen][2B sessionID][1B status][1B option][1B network]
//   [2B port][1B atyp][addr][2B dataLen][data]
/** 解析一个 XUDP 帧，数据不足时返回 null。 */
function parseXudpFrame(buf) {
  if (buf.length < 6) return null;
  const frameLen = buf.readUInt16BE(0);
  if (frameLen < 4 || frameLen > 600) throw new Error('bad xudp frame length ' + frameLen);
  const sessionID = buf.readUInt16BE(2);
  const status = buf[4];
  const option = buf[5];

  let network = null;
  let host = null;
  let port = 0;
  if (frameLen > 4) {
    let off = 6;
    network = buf[off]; off += 1;
    port = buf.readUInt16BE(off); off += 2;
    const atyp = buf[off]; off += 1;
    if (atyp === 1) {
      host = Array.from(buf.subarray(off, off + 4)).join('.');
    } else if (atyp === 2) {
      const len = buf[off];
      host = buf.subarray(off + 1, off + 1 + len).toString();
    } else if (atyp === 3) {
      const parts = [];
      for (let i = 0; i < 16; i += 2) {
        parts.push(buf.readUInt16BE(off + i).toString(16));
      }
      host = parts.join(':');
    } else {
      throw new Error('xudp unsupported atyp ' + atyp);
    }
  }

  let need = 2 + frameLen;
  let payload = Buffer.alloc(0);
  if (option & OptionData) {
    if (buf.length < need + 2) return null;
    const dataLen = buf.readUInt16BE(need);
    need += 2 + dataLen;
    if (buf.length < need) return null;
    payload = buf.subarray(need - dataLen, need);
  }

  return { frameLen, sessionID, status, option, network, host, port, payload, consumed: need };
}

/** 构造回程 XUDP 帧。 */
function encodeXudpFrame(host, port, sessionID, status, payload) {
  let atyp;
  let addr;
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
    atyp = 1;
    addr = Buffer.from(host.split('.').map(Number));
  } else {
    atyp = 2;
    const raw = Buffer.from(host, 'latin1');
    addr = Buffer.concat([Buffer.from([raw.length]), raw]);
  }
  const addrLen = 2 + 1 + addr.length;
  const frameLen = 5 + addrLen;
  const head = Buffer.alloc(2 + frameLen + 2);
  head.writeUInt16BE(frameLen, 0);
  head.writeUInt16BE(sessionID, 2);
  head[4] = status;
  head[5] = OptionData;
  head[6] = NetworkUDP;
  head.writeUInt16BE(port, 7);
  head[8] = atyp;
  addr.copy(head, 9);
  head.writeUInt16BE(payload.length, 2 + frameLen);
  return Buffer.concat([head, payload]);
}

/** command=3：XUDP（sing-box 默认 packet_encoding），每个帧自带目标地址。 */
function serveXudp(client, header, buf) {
  // VLESS 响应头：version + addonLen
  client.write(Buffer.from([0, 0]));
  const sessions = new Map(); // "host:port" -> { udp, sessionID }
  let pending = buf.subarray(header.offset);

  const flush = () => {
    for (;;) {
      let frame;
      try {
        frame = parseXudpFrame(pending);
      } catch (err) {
        console.log('[vless] bad xudp frame', err.message);
        client.destroy();
        return;
      }
      if (!frame) return;
      pending = pending.subarray(frame.consumed);

      if (frame.status === StatusEnd) {
        sessions.delete(frame.host + ':' + frame.port);
        continue;
      }
      if (!frame.host || frame.network !== NetworkUDP) {
        continue;
      }

      const destination = frame.host;
      const destinationPort = frame.port;
      const key = destination + ':' + destinationPort;
      let entry = sessions.get(key);
      if (!entry) {
        const udp = dgram.createSocket('udp4');
        const sessionID = frame.sessionID;
        udp.on('message', (msg) => {
          client.write(encodeXudpFrame(destination, destinationPort, sessionID, StatusKeep, msg));
        });
        udp.on('error', (err) => console.log('[vless] xudp udp error', err.message));
        entry = { udp, sessionID };
        sessions.set(key, entry);
      }
      if (frame.payload.length > 0) {
        console.log(`[vless] UDP -> ${key} payload=${frame.payload.length}B xudp`);
        entry.udp.send(frame.payload, destinationPort, destination, (err) => {
          if (err) console.log('[vless] xudp send error', err.message);
        });
      }
    }
  };
  flush();

  client.on('data', (chunk) => {
    pending = Buffer.concat([pending, chunk]);
    flush();
  });
  client.on('error', () => sessions.forEach((s) => s.udp.close()));
  client.on('close', () => sessions.forEach((s) => s.udp.close()));
}

const server = net.createServer((socket) => {
  let buf = Buffer.alloc(0);
  let handled = false;

  const onData = (chunk) => {
    if (handled) return;
    buf = Buffer.concat([buf, chunk]);
    if (DEBUG) {
      console.log(new Date().toISOString(), '[vless] conn data len=' + chunk.length +
        ' peer=' + socket.remoteAddress + ':' + socket.remotePort +
        ' head=' + buf.subarray(0, 24).toString('hex'));
    }
    if (buf.length > 262144) {
      socket.destroy();
      return;
    }
    let header;
    try {
      header = parseHeader(buf);
    } catch (err) {
      console.log('[vless] bad header', err.message, 'len=' + buf.length,
        'head=' + buf.subarray(0, 64).toString('hex'));
      socket.destroy();
      return;
    }
    if (!header) return;

    handled = true;
    socket.removeListener('data', onData);
    if (header.version !== 0 || header.uuid !== UUID_HEX) {
      console.log('[vless] bad handshake uuid=' + header.uuid);
      socket.destroy();
      return;
    }
    if (header.command === CommandTCP) {
      console.log(`[vless] TCP -> ${header.host}:${header.port} payload=${buf.length - header.offset}B`);
      serveTcp(socket, header, buf);
    } else if (header.command === CommandUDP) {
      console.log(`[vless] UDP -> ${header.host}:${header.port} payload=${buf.length - header.offset}B`);
      serveUdp(socket, header, buf);
    } else if (header.command === CommandMux) {
      serveXudp(socket, header, buf);
    } else {
      console.log('[vless] unsupported command ' + header.command);
      socket.destroy();
    }
  };

  socket.on('data', onData);
  socket.on('error', () => {});
});

server.listen(PORT, HOST, () => {
  console.log(`[vless] listening on ${HOST}:${PORT} (uuid=${UUID_HEX})`);
});
