#!/usr/bin/env python3
"""Staging mail sink: a minimal SMTP server that accepts every message and sends nothing anywhere.

Every message is decoded and printed to stdout (docker logs), so the owner can read what the shop
would have sent (order alerts, password-reset links, customer confirmations). Credentials offered by
the client are accepted and never logged. No TLS is offered, so the backend's optional STARTTLS is skipped.
Standard library only; runs in python:3-alpine. Listens on SINK_PORT (default 2525) inside the private
Docker network; the port is never published.
"""
import asyncio, base64, email, os, sys, time
from email import policy

PORT = int(os.environ.get("SINK_PORT", "2525"))
MAX = 10 * 1024 * 1024


def show(rcpts, sender, raw):
    msg = email.message_from_bytes(raw, policy=policy.default)
    body = msg.get_body(preferencelist=("plain", "html"))
    text = body.get_content() if body is not None else raw.decode("utf-8", "replace")
    out = [
        "=== MAIL CAPTURED %s ===" % time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "Envelope-To: %s" % ", ".join(rcpts),
        "From: %s" % msg.get("From", sender),
        "To: %s" % msg.get("To", ""),
        "Subject: %s" % msg.get("Subject", ""),
        "Message-ID: %s" % msg.get("Message-ID", ""),
        "",
        text.rstrip(),
        "=== END MAIL ===",
    ]
    print("\n".join(out), flush=True)


async def handle(reader, writer):
    async def say(line):
        writer.write((line + "\r\n").encode())
        await writer.drain()

    sender, rcpts = "", []
    try:
        await say("220 sink ESMTP")
        while True:
            raw = await reader.readline()
            if not raw:
                break
            line = raw.decode("utf-8", "replace").strip()
            u = line.upper()
            if u.startswith("EHLO"):
                await say("250-sink")
                await say("250-AUTH PLAIN LOGIN")
                await say("250-SIZE %d" % MAX)
                await say("250 8BITMIME")
            elif u.startswith("HELO"):
                await say("250 sink")
            elif u.startswith("AUTH PLAIN"):
                if u.strip() == "AUTH PLAIN":
                    await say("334 ")
                    await reader.readline()
                await say("235 ok")
            elif u.startswith("AUTH LOGIN"):
                await say("334 VXNlcm5hbWU6")
                await reader.readline()
                await say("334 UGFzc3dvcmQ6")
                await reader.readline()
                await say("235 ok")
            elif u.startswith("MAIL FROM"):
                sender, rcpts = line[10:].strip(), []
                await say("250 ok")
            elif u.startswith("RCPT TO"):
                s, e = line.find("<"), line.find(">")
                rcpts.append(line[s + 1:e] if s >= 0 and e > s else line[8:].strip())
                await say("250 ok")
            elif u == "DATA":
                await say("354 go")
                chunks, size = [], 0
                while True:
                    l = await reader.readline()
                    if not l or l.rstrip(b"\r\n") == b".":
                        break
                    if l.startswith(b".."):
                        l = l[1:]
                    size += len(l)
                    if size <= MAX:
                        chunks.append(l)
                show(rcpts, sender, b"".join(chunks))
                sender, rcpts = "", []
                await say("250 queued")
            elif u == "RSET":
                sender, rcpts = "", []
                await say("250 ok")
            elif u == "QUIT":
                await say("221 bye")
                break
            else:
                await say("250 ok")
    except Exception as e:  # never let one bad client stop the sink
        print("sink error: %r" % (e,), file=sys.stderr, flush=True)
    finally:
        writer.close()


async def main():
    server = await asyncio.start_server(handle, "0.0.0.0", PORT)
    print("mail sink listening on %d; nothing is sent to the outside" % PORT, flush=True)
    async with server:
        await server.serve_forever()


asyncio.run(main())
