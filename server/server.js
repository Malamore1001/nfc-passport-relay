const WebSocket = require('ws');
const express = require('express');
const { v4: uuidv4 } = require('uuid');
const http = require('http');
const path = require('path');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

// Serve static files for web interface
app.use(express.static(path.join(__dirname, 'public')));

// Store active sessions
// Session = { reader: WebSocket, emulator: WebSocket, sessionId: string }
const sessions = new Map();

// Store pending connections waiting to join a session
const pendingConnections = new Map();

console.log(`
╔═══════════════════════════════════════════════════════════════╗
║           NFC PASSPORT RELAY SERVER                           ║
║                                                               ║
║   🛂 Real-time APDU relay between devices                     ║
╚═══════════════════════════════════════════════════════════════╝
`);

wss.on('connection', (ws, req) => {
    const clientId = uuidv4().substring(0, 8);
    ws.clientId = clientId;
    ws.isAlive = true;
    
    console.log(`\n📱 New connection: ${clientId}`);
    
    ws.on('pong', () => {
        ws.isAlive = true;
    });
    
    ws.on('message', (data) => {
        try {
            const message = JSON.parse(data.toString());
            handleMessage(ws, message);
        } catch (e) {
            console.error(`❌ Invalid message from ${ws.clientId}:`, e.message);
            ws.send(JSON.stringify({ type: 'error', message: 'Invalid JSON' }));
        }
    });
    
    ws.on('close', () => {
        console.log(`\n👋 Disconnected: ${ws.clientId}`);
        handleDisconnect(ws);
    });
    
    ws.on('error', (err) => {
        console.error(`❌ Error from ${ws.clientId}:`, err.message);
    });
    
    // Send welcome message
    ws.send(JSON.stringify({
        type: 'welcome',
        clientId: clientId,
        message: 'Connected to NFC Relay Server'
    }));
});

function handleMessage(ws, message) {
    const { type } = message;
    
    switch (type) {
        case 'create_session':
            createSession(ws, message);
            break;
            
        case 'join_session':
            joinSession(ws, message);
            break;
            
        case 'apdu_command':
            relayApduCommand(ws, message);
            break;
            
        case 'apdu_response':
            relayApduResponse(ws, message);
            break;
            
        case 'mrz_data':
            relayMrzData(ws, message);
            break;
            
        case 'passport_ready':
            handlePassportReady(ws, message);
            break;
            
        case 'status':
            sendStatus(ws, message);
            break;
            
        case 'ping':
            ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
            break;
            
        default:
            console.log(`❓ Unknown message type: ${type}`);
    }
}

function createSession(ws, message) {
    const { role } = message; // 'reader' or 'emulator'
    const sessionId = generateSessionCode();
    
    const session = {
        sessionId,
        reader: null,
        emulator: null,
        createdAt: Date.now(),
        mrzData: null
    };
    
    if (role === 'reader') {
        session.reader = ws;
    } else {
        session.emulator = ws;
    }
    
    ws.sessionId = sessionId;
    ws.role = role;
    
    sessions.set(sessionId, session);
    
    console.log(`\n🆕 Session created: ${sessionId} by ${role} (${ws.clientId})`);
    
    ws.send(JSON.stringify({
        type: 'session_created',
        sessionId: sessionId,
        role: role,
        message: `Session ${sessionId} created. Share this code with the other device.`
    }));
}

function joinSession(ws, message) {
    const { sessionId, role } = message;
    
    const session = sessions.get(sessionId.toUpperCase());
    
    if (!session) {
        ws.send(JSON.stringify({
            type: 'error',
            message: `Session ${sessionId} not found`
        }));
        return;
    }
    
    if (session[role]) {
        ws.send(JSON.stringify({
            type: 'error',
            message: `Role ${role} is already taken in this session`
        }));
        return;
    }
    
    session[role] = ws;
    ws.sessionId = sessionId.toUpperCase();
    ws.role = role;
    
    console.log(`\n🔗 ${role} (${ws.clientId}) joined session: ${sessionId}`);
    
    ws.send(JSON.stringify({
        type: 'session_joined',
        sessionId: sessionId,
        role: role
    }));
    
    // Notify both parties that session is ready
    if (session.reader && session.emulator) {
        const readyMessage = JSON.stringify({
            type: 'session_ready',
            sessionId: sessionId,
            message: 'Both devices connected! Ready for NFC relay.'
        });
        
        session.reader.send(readyMessage);
        session.emulator.send(readyMessage);
        
        console.log(`\n✅ Session ${sessionId} is READY - both devices connected!`);
        
        // If MRZ data was already shared, send it to the reader
        if (session.mrzData) {
            session.reader.send(JSON.stringify({
                type: 'mrz_data',
                data: session.mrzData
            }));
        }
    }
}

function relayApduCommand(ws, message) {
    // APDU command comes from EMULATOR (the one being scanned)
    // and needs to go to READER (the one with the real passport)
    const session = sessions.get(ws.sessionId);
    
    if (!session) {
        ws.send(JSON.stringify({ type: 'error', message: 'Not in a session' }));
        return;
    }
    
    if (!session.reader) {
        ws.send(JSON.stringify({ type: 'error', message: 'Reader not connected' }));
        return;
    }
    
    const startTime = Date.now();
    message.relayTimestamp = startTime;
    
    console.log(`\n📤 APDU Command: ${message.apdu?.substring(0, 20)}... → Reader`);
    
    session.reader.send(JSON.stringify({
        type: 'apdu_command',
        apdu: message.apdu,
        commandId: message.commandId,
        relayTimestamp: startTime
    }));
}

function relayApduResponse(ws, message) {
    // APDU response comes from READER (real passport)
    // and needs to go to EMULATOR (being scanned)
    const session = sessions.get(ws.sessionId);
    
    if (!session) {
        ws.send(JSON.stringify({ type: 'error', message: 'Not in a session' }));
        return;
    }
    
    if (!session.emulator) {
        ws.send(JSON.stringify({ type: 'error', message: 'Emulator not connected' }));
        return;
    }
    
    const latency = message.relayTimestamp ? Date.now() - message.relayTimestamp : 'N/A';
    console.log(`\n📥 APDU Response: ${message.apdu?.substring(0, 20)}... → Emulator (${latency}ms)`);
    
    session.emulator.send(JSON.stringify({
        type: 'apdu_response',
        apdu: message.apdu,
        commandId: message.commandId,
        latency: latency
    }));
}

function relayMrzData(ws, message) {
    const session = sessions.get(ws.sessionId);
    
    if (!session) {
        ws.send(JSON.stringify({ type: 'error', message: 'Not in a session' }));
        return;
    }
    
    // Store MRZ data in session
    session.mrzData = message.data;
    
    console.log(`\n🔐 MRZ Data received and stored for session ${ws.sessionId}`);
    
    // If reader is connected, forward MRZ data
    if (session.reader) {
        session.reader.send(JSON.stringify({
            type: 'mrz_data',
            data: message.data
        }));
    }
    
    ws.send(JSON.stringify({
        type: 'mrz_received',
        message: 'MRZ data stored successfully'
    }));
}

function handlePassportReady(ws, message) {
    const session = sessions.get(ws.sessionId);
    
    if (!session) {
        ws.send(JSON.stringify({ type: 'error', message: 'Not in a session' }));
        return;
    }
    
    console.log(`\n🛂 Passport ready for relay in session ${ws.sessionId}!`);
    console.log(`   Max transceive length: ${message.maxTransceiveLength}`);
    
    // Store passport info in session
    session.passportReady = true;
    session.maxTransceiveLength = message.maxTransceiveLength;
    
    // Notify emulator that passport is ready
    if (session.emulator && session.emulator.readyState === WebSocket.OPEN) {
        session.emulator.send(JSON.stringify({
            type: 'passport_ready',
            relayMode: true,
            maxTransceiveLength: message.maxTransceiveLength
        }));
        console.log(`   Notified emulator!`);
    }
    
    ws.send(JSON.stringify({
        type: 'passport_ready_confirmed',
        message: 'Passport connected and ready for relay'
    }));
}

function sendStatus(ws, message) {
    const session = sessions.get(ws.sessionId);
    
    ws.send(JSON.stringify({
        type: 'status_response',
        inSession: !!session,
        sessionId: ws.sessionId || null,
        role: ws.role || null,
        readerConnected: session?.reader?.readyState === WebSocket.OPEN,
        emulatorConnected: session?.emulator?.readyState === WebSocket.OPEN
    }));
}

function handleDisconnect(ws) {
    if (!ws.sessionId) return;
    
    const session = sessions.get(ws.sessionId);
    if (!session) return;
    
    // Notify the other party
    const otherRole = ws.role === 'reader' ? 'emulator' : 'reader';
    const otherWs = session[otherRole];
    
    if (otherWs && otherWs.readyState === WebSocket.OPEN) {
        otherWs.send(JSON.stringify({
            type: 'peer_disconnected',
            role: ws.role,
            message: `${ws.role} has disconnected`
        }));
    }
    
    // Remove from session
    session[ws.role] = null;
    
    // If both disconnected, remove session
    if (!session.reader && !session.emulator) {
        sessions.delete(ws.sessionId);
        console.log(`\n🗑️ Session ${ws.sessionId} removed (all disconnected)`);
    }
}

function generateSessionCode() {
    // Generate a 6-character alphanumeric code
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // Removed confusing chars
    let code = '';
    for (let i = 0; i < 6; i++) {
        code += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return code;
}

// Heartbeat to detect broken connections
const heartbeatInterval = setInterval(() => {
    wss.clients.forEach((ws) => {
        if (ws.isAlive === false) {
            console.log(`💔 Terminating inactive connection: ${ws.clientId}`);
            return ws.terminate();
        }
        ws.isAlive = false;
        ws.ping();
    });
}, 30000);

wss.on('close', () => {
    clearInterval(heartbeatInterval);
});

// Clean up old sessions periodically
setInterval(() => {
    const now = Date.now();
    const maxAge = 30 * 60 * 1000; // 30 minutes
    
    for (const [sessionId, session] of sessions) {
        if (now - session.createdAt > maxAge) {
            if (!session.reader && !session.emulator) {
                sessions.delete(sessionId);
                console.log(`🧹 Cleaned up stale session: ${sessionId}`);
            }
        }
    }
}, 60000);

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`🚀 Server running on port ${PORT}`);
    console.log(`📡 WebSocket: ws://localhost:${PORT}`);
    console.log(`🌐 Web UI: http://localhost:${PORT}`);
});
