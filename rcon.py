#!/usr/bin/env python3
"""Minimal RCON client for the NestWorld dev server: ./rcon.py "<command>" """
import socket, struct, sys

def rcon(cmd, host='127.0.0.1', port=25575, password='nestworld-dev'):
    def pack(req_id, ptype, payload):
        body = struct.pack('<ii', req_id, ptype) + payload.encode() + b'\x00\x00'
        return struct.pack('<i', len(body)) + body
    def recv(s):
        ln = struct.unpack('<i', s.recv(4))[0]
        data = b''
        while len(data) < ln: data += s.recv(ln - len(data))
        rid, rtype = struct.unpack('<ii', data[:8])
        return rid, rtype, data[8:-2].decode(errors='replace')
    s = socket.create_connection((host, port), timeout=10)
    s.sendall(pack(1, 3, password))
    rid, _, _ = recv(s)
    if rid == -1: raise SystemExit("RCON auth failed")
    s.sendall(pack(2, 2, cmd))
    _, _, resp = recv(s)
    s.close()
    return resp

if __name__ == '__main__':
    print(rcon(' '.join(sys.argv[1:])))
