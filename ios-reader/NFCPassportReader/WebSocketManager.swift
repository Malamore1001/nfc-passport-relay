import Foundation

protocol WebSocketManagerDelegate: AnyObject {
    func didConnect()
    func didDisconnect(error: Error?)
    func didReceiveSessionId(_ sessionId: String)
    func didReceiveSessionReady()
    func didReceiveAPDUCommand(_ apdu: String, commandId: String, timestamp: Int64)
    func didReceiveError(_ message: String)
}

class WebSocketManager: NSObject {
    
    weak var delegate: WebSocketManagerDelegate?
    
    private var webSocket: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private let urlString: String
    
    init(urlString: String) {
        self.urlString = urlString
        super.init()
    }
    
    func connect() {
        // Konvertera HTTP URL till WebSocket URL om nödvändigt
        var wsUrl = urlString
        if wsUrl.hasPrefix("http://") {
            wsUrl = wsUrl.replacingOccurrences(of: "http://", with: "ws://")
        } else if wsUrl.hasPrefix("https://") {
            wsUrl = wsUrl.replacingOccurrences(of: "https://", with: "wss://")
        }
        
        guard let url = URL(string: wsUrl) else {
            delegate?.didReceiveError("Ogiltig URL")
            return
        }
        
        urlSession = URLSession(configuration: .default, delegate: self, delegateQueue: OperationQueue())
        webSocket = urlSession?.webSocketTask(with: url)
        webSocket?.resume()
        
        receiveMessage()
    }
    
    func disconnect() {
        webSocket?.cancel(with: .goingAway, reason: nil)
    }
    
    func createSession() {
        let message: [String: Any] = [
            "type": "create_session",
            "role": "reader"
        ]
        send(message)
    }
    
    func sendMRZData(line1: String, line2: String, data: MRZData) {
        let message: [String: Any] = [
            "type": "mrz_data",
            "data": [
                "line1": line1,
                "line2": line2,
                "documentNumber": data.documentNumber,
                "dateOfBirth": data.dateOfBirth,
                "dateOfExpiry": data.dateOfExpiry
            ]
        ]
        send(message)
    }
    
    func sendPassportReady() {
        let message: [String: Any] = [
            "type": "passport_ready",
            "relayMode": true
        ]
        send(message)
    }
    
    func sendAPDUResponse(apdu: String, commandId: String, timestamp: Int64, error: String? = nil) {
        var message: [String: Any] = [
            "type": "apdu_response",
            "apdu": apdu,
            "commandId": commandId,
            "relayTimestamp": timestamp
        ]
        
        if let error = error {
            message["error"] = error
        }
        
        send(message)
    }
    
    private func send(_ message: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let jsonString = String(data: data, encoding: .utf8) else {
            return
        }
        
        webSocket?.send(.string(jsonString)) { error in
            if let error = error {
                print("WebSocket send error: \(error)")
            }
        }
    }
    
    private func receiveMessage() {
        webSocket?.receive { [weak self] result in
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self?.handleMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self?.handleMessage(text)
                    }
                @unknown default:
                    break
                }
                
                // Continue receiving messages
                self?.receiveMessage()
                
            case .failure(let error):
                self?.delegate?.didDisconnect(error: error)
            }
        }
    }
    
    private func handleMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }
        
        switch type {
        case "welcome":
            delegate?.didConnect()
            
        case "session_created":
            if let sessionId = json["sessionId"] as? String {
                delegate?.didReceiveSessionId(sessionId)
            }
            
        case "session_ready":
            delegate?.didReceiveSessionReady()
            
        case "apdu_command":
            if let apdu = json["apdu"] as? String,
               let commandId = json["commandId"] as? String {
                let timestamp = json["relayTimestamp"] as? Int64 ?? 0
                delegate?.didReceiveAPDUCommand(apdu, commandId: commandId, timestamp: timestamp)
            }
            
        case "peer_disconnected":
            delegate?.didReceiveError("Emulator frånkopplad")
            
        case "error":
            if let message = json["message"] as? String {
                delegate?.didReceiveError(message)
            }
            
        default:
            print("Unknown message type: \(type)")
        }
    }
}

// MARK: - URLSessionWebSocketDelegate
extension WebSocketManager: URLSessionWebSocketDelegate {
    
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("WebSocket connected")
    }
    
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        delegate?.didDisconnect(error: nil)
    }
}
