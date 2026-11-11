const http = require('http');
const path = require('path');
const fs = require('fs');
const { 
    default: makeWASocket, 
    useMultiFileAuthState, 
    DisconnectReason, 
    fetchLatestBaileysVersion 
} = require('@whiskeysockets/baileys');
const pino = require('pino');

const filesDir = process.env.NODE_FILES_DIR || __dirname;
const AUTH_DIR = path.join(filesDir, 'wa_session');

let sock = null;
let isConnected = false;
let currentPairingCode = null;

async function startWhatsApp(targetPhone = null) {
    const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
    const { version } = await fetchLatestBaileysVersion();

    sock = makeWASocket({
        version,
        auth: state,
        printQRInTerminal: false,
        logger: pino({ level: 'silent' }),
        browser: ['QuickSender Pro', 'Chrome', '3.0.0']
    });

    sock.ev.on('creds.update', saveCreds);

    if (!sock.authState.creds.registered && targetPhone) {
        setTimeout(async () => {
            try {
                const clean = targetPhone.replace(/[^0-9]/g, '');
                currentPairingCode = await sock.requestPairingCode(clean);
            } catch (err) {
                currentPairingCode = "ERR: " + err.message;
            }
        }, 2500);
    }

    sock.ev.on('connection.update', (update) => {
        const { connection, lastDisconnect } = update;
        if (connection === 'open') {
            isConnected = true;
            currentPairingCode = null;
        }
        if (connection === 'close') {
            isConnected = false;
            const code = lastDisconnect?.error?.output?.statusCode;
            if (code !== DisconnectReason.loggedOut) {
                startWhatsApp();
            } else {
                fs.rmSync(AUTH_DIR, { recursive: true, force: true });
            }
        }
    });
}

const server = http.createServer((req, res) => {
    res.setHeader('Content-Type', 'application/json');

    if (req.method === 'POST' && req.url === '/pair') {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', async () => {
            try {
                const data = JSON.parse(body);
                currentPairingCode = "WAITING";
                await startWhatsApp(data.phone);
                
                let count = 0;
                const timer = setInterval(() => {
                    count++;
                    if (currentPairingCode && currentPairingCode !== "WAITING") {
                        clearInterval(timer);
                        res.writeHead(200);
                        res.end(JSON.stringify({ success: true, code: currentPairingCode }));
                    } else if (count > 20) {
                        clearInterval(timer);
                        res.writeHead(500);
                        res.end(JSON.stringify({ success: false, error: "Pairing timed out" }));
                    }
                }, 500);
            } catch (e) {
                res.writeHead(400);
                res.end(JSON.stringify({ success: false, error: e.message }));
            }
        });
    } else if (req.method === 'GET' && req.url === '/status') {
        res.writeHead(200);
        res.end(JSON.stringify({ connected: isConnected }));
    } else if (req.method === 'POST' && req.url === '/send') {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', async () => {
            if (!isConnected || !sock) {
                res.writeHead(503);
                return res.end(JSON.stringify({ success: false, error: "WhatsApp not linked" }));
            }
            try {
                const data = JSON.parse(body);
                const clean = data.phone.replace(/[^0-9]/g, '');
                const jid = `${clean}@s.whatsapp.net`;
                await sock.sendMessage(jid, { text: data.message });
                res.writeHead(200);
                res.end(JSON.stringify({ success: true }));
            } catch (e) {
                res.writeHead(500);
                res.end(JSON.stringify({ success: false, error: e.message }));
            }
        });
    } else {
        res.writeHead(404);
        res.end(JSON.stringify({ error: "Endpoint not found" }));
    }
});

server.listen(3000, '127.0.0.1', () => {
    if (fs.existsSync(AUTH_DIR)) {
        startWhatsApp();
    }
});
