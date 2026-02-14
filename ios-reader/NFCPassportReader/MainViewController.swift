import UIKit
import CoreNFC

class MainViewController: UIViewController {
    
    // MARK: - UI Elements
    private let scrollView = UIScrollView()
    private let contentView = UIView()
    
    private let titleLabel: UILabel = {
        let label = UILabel()
        label.text = "🛂 NFC Passport Reader"
        label.font = .systemFont(ofSize: 28, weight: .bold)
        label.textAlignment = .center
        return label
    }()
    
    private let statusLabel: UILabel = {
        let label = UILabel()
        label.text = "⏳ Ansluter..."
        label.font = .systemFont(ofSize: 16)
        label.textAlignment = .center
        label.numberOfLines = 0
        return label
    }()
    
    private let serverTextField: UITextField = {
        let field = UITextField()
        field.placeholder = "Server URL"
        field.text = "ws://51.21.134.222:3000"
        field.borderStyle = .roundedRect
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        return field
    }()
    
    private let connectButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("🔗 Anslut till server", for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 18, weight: .semibold)
        button.backgroundColor = .systemBlue
        button.setTitleColor(.white, for: .normal)
        button.layer.cornerRadius = 12
        return button
    }()
    
    private let sessionLabel: UILabel = {
        let label = UILabel()
        label.text = "Session: -"
        label.font = .systemFont(ofSize: 20, weight: .medium)
        label.textAlignment = .center
        return label
    }()
    
    private let createSessionButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("➕ Skapa session", for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 18, weight: .semibold)
        button.backgroundColor = .systemGreen
        button.setTitleColor(.white, for: .normal)
        button.layer.cornerRadius = 12
        button.isEnabled = false
        button.alpha = 0.5
        return button
    }()
    
    private let mrzTitleLabel: UILabel = {
        let label = UILabel()
        label.text = "📄 MRZ Data"
        label.font = .systemFont(ofSize: 18, weight: .semibold)
        return label
    }()
    
    private let mrzLine1Field: UITextField = {
        let field = UITextField()
        field.placeholder = "MRZ Rad 1 (44 tecken)"
        field.borderStyle = .roundedRect
        field.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        field.autocapitalizationType = .allCharacters
        field.autocorrectionType = .no
        return field
    }()
    
    private let mrzLine2Field: UITextField = {
        let field = UITextField()
        field.placeholder = "MRZ Rad 2 (44 tecken)"
        field.borderStyle = .roundedRect
        field.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        field.autocapitalizationType = .allCharacters
        field.autocorrectionType = .no
        return field
    }()
    
    private let saveMrzButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("💾 Spara MRZ", for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        button.backgroundColor = .systemOrange
        button.setTitleColor(.white, for: .normal)
        button.layer.cornerRadius = 10
        return button
    }()
    
    private let startNfcButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle("📡 Starta NFC-läsning", for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 20, weight: .bold)
        button.backgroundColor = .systemPurple
        button.setTitleColor(.white, for: .normal)
        button.layer.cornerRadius = 16
        button.isEnabled = false
        button.alpha = 0.5
        return button
    }()
    
    private let passportStatusLabel: UILabel = {
        let label = UILabel()
        label.text = "📱 Väntar på NFC..."
        label.font = .systemFont(ofSize: 16)
        label.textAlignment = .center
        label.numberOfLines = 0
        return label
    }()
    
    private let logTextView: UITextView = {
        let textView = UITextView()
        textView.isEditable = false
        textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        textView.backgroundColor = UIColor.systemGray6
        textView.layer.cornerRadius = 8
        textView.text = "Logg:\n"
        return textView
    }()
    
    // MARK: - Properties
    private var webSocketManager: WebSocketManager?
    private var nfcReader: NFCPassportReader?
    private var sessionId: String?
    private var mrzData: MRZData?
    
    // MARK: - Lifecycle
    override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
        setupActions()
        checkNFCAvailability()
    }
    
    // MARK: - Setup
    private func setupUI() {
        view.backgroundColor = .systemBackground
        
        view.addSubview(scrollView)
        scrollView.addSubview(contentView)
        
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        contentView.translatesAutoresizingMaskIntoConstraints = false
        
        let views = [titleLabel, statusLabel, serverTextField, connectButton, 
                     sessionLabel, createSessionButton, mrzTitleLabel,
                     mrzLine1Field, mrzLine2Field, saveMrzButton,
                     startNfcButton, passportStatusLabel, logTextView]
        
        views.forEach { 
            $0.translatesAutoresizingMaskIntoConstraints = false
            contentView.addSubview($0)
        }
        
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            
            contentView.topAnchor.constraint(equalTo: scrollView.topAnchor),
            contentView.leadingAnchor.constraint(equalTo: scrollView.leadingAnchor),
            contentView.trailingAnchor.constraint(equalTo: scrollView.trailingAnchor),
            contentView.bottomAnchor.constraint(equalTo: scrollView.bottomAnchor),
            contentView.widthAnchor.constraint(equalTo: scrollView.widthAnchor),
            
            titleLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 20),
            titleLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            titleLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            
            statusLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 10),
            statusLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            statusLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            
            serverTextField.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 20),
            serverTextField.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            serverTextField.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            serverTextField.heightAnchor.constraint(equalToConstant: 44),
            
            connectButton.topAnchor.constraint(equalTo: serverTextField.bottomAnchor, constant: 12),
            connectButton.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            connectButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            connectButton.heightAnchor.constraint(equalToConstant: 50),
            
            sessionLabel.topAnchor.constraint(equalTo: connectButton.bottomAnchor, constant: 20),
            sessionLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            sessionLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            
            createSessionButton.topAnchor.constraint(equalTo: sessionLabel.bottomAnchor, constant: 12),
            createSessionButton.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            createSessionButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            createSessionButton.heightAnchor.constraint(equalToConstant: 50),
            
            mrzTitleLabel.topAnchor.constraint(equalTo: createSessionButton.bottomAnchor, constant: 30),
            mrzTitleLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            
            mrzLine1Field.topAnchor.constraint(equalTo: mrzTitleLabel.bottomAnchor, constant: 10),
            mrzLine1Field.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            mrzLine1Field.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            mrzLine1Field.heightAnchor.constraint(equalToConstant: 40),
            
            mrzLine2Field.topAnchor.constraint(equalTo: mrzLine1Field.bottomAnchor, constant: 8),
            mrzLine2Field.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            mrzLine2Field.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            mrzLine2Field.heightAnchor.constraint(equalToConstant: 40),
            
            saveMrzButton.topAnchor.constraint(equalTo: mrzLine2Field.bottomAnchor, constant: 12),
            saveMrzButton.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            saveMrzButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            saveMrzButton.heightAnchor.constraint(equalToConstant: 44),
            
            startNfcButton.topAnchor.constraint(equalTo: saveMrzButton.bottomAnchor, constant: 30),
            startNfcButton.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            startNfcButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            startNfcButton.heightAnchor.constraint(equalToConstant: 60),
            
            passportStatusLabel.topAnchor.constraint(equalTo: startNfcButton.bottomAnchor, constant: 15),
            passportStatusLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            passportStatusLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            
            logTextView.topAnchor.constraint(equalTo: passportStatusLabel.bottomAnchor, constant: 20),
            logTextView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            logTextView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            logTextView.heightAnchor.constraint(equalToConstant: 200),
            logTextView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -30),
        ])
    }
    
    private func setupActions() {
        connectButton.addTarget(self, action: #selector(connectTapped), for: .touchUpInside)
        createSessionButton.addTarget(self, action: #selector(createSessionTapped), for: .touchUpInside)
        saveMrzButton.addTarget(self, action: #selector(saveMrzTapped), for: .touchUpInside)
        startNfcButton.addTarget(self, action: #selector(startNfcTapped), for: .touchUpInside)
        
        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboard))
        view.addGestureRecognizer(tapGesture)
    }
    
    private func checkNFCAvailability() {
        if NFCTagReaderSession.readingAvailable {
            log("✅ NFC är tillgängligt")
            statusLabel.text = "✅ NFC redo"
        } else {
            log("❌ NFC är inte tillgängligt på denna enhet")
            statusLabel.text = "❌ NFC stöds inte"
            startNfcButton.isEnabled = false
        }
    }
    
    // MARK: - Actions
    @objc private func dismissKeyboard() {
        view.endEditing(true)
    }
    
    @objc private func connectTapped() {
        guard let urlString = serverTextField.text, !urlString.isEmpty else {
            log("❌ Ange server-URL")
            return
        }
        
        log("🔗 Ansluter till \(urlString)...")
        connectButton.isEnabled = false
        
        webSocketManager = WebSocketManager(urlString: urlString)
        webSocketManager?.delegate = self
        webSocketManager?.connect()
    }
    
    @objc private func createSessionTapped() {
        webSocketManager?.createSession()
    }
    
    @objc private func saveMrzTapped() {
        guard let line1 = mrzLine1Field.text?.uppercased(), line1.count >= 44,
              let line2 = mrzLine2Field.text?.uppercased(), line2.count >= 44 else {
            log("❌ MRZ måste vara minst 44 tecken per rad")
            return
        }
        
        mrzData = MRZParser.parseTD3(line1: String(line1.prefix(44)), line2: String(line2.prefix(44)))
        
        if let data = mrzData {
            log("✅ MRZ sparad: \(data.givenNames) \(data.surname)")
            startNfcButton.isEnabled = true
            startNfcButton.alpha = 1.0
            
            // Skicka MRZ till server
            webSocketManager?.sendMRZData(line1: line1, line2: line2, data: data)
        } else {
            log("❌ Kunde inte tolka MRZ")
        }
    }
    
    @objc private func startNfcTapped() {
        guard let mrzData = mrzData else {
            log("❌ Ange MRZ först")
            return
        }
        
        log("📡 Startar NFC-läsning...")
        passportStatusLabel.text = "📱 Håll passet mot telefonens ovansida..."
        
        nfcReader = NFCPassportReader(mrzData: mrzData)
        nfcReader?.delegate = self
        nfcReader?.startReading()
    }
    
    // MARK: - Helpers
    private func log(_ message: String) {
        let timestamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        let logMessage = "[\(timestamp)] \(message)\n"
        
        DispatchQueue.main.async {
            self.logTextView.text += logMessage
            let bottom = NSRange(location: self.logTextView.text.count - 1, length: 1)
            self.logTextView.scrollRangeToVisible(bottom)
        }
        
        print(logMessage)
    }
}

// MARK: - WebSocketManagerDelegate
extension MainViewController: WebSocketManagerDelegate {
    func didConnect() {
        DispatchQueue.main.async {
            self.log("✅ Ansluten till server")
            self.statusLabel.text = "✅ Ansluten till server"
            self.createSessionButton.isEnabled = true
            self.createSessionButton.alpha = 1.0
        }
    }
    
    func didDisconnect(error: Error?) {
        DispatchQueue.main.async {
            self.log("❌ Frånkopplad: \(error?.localizedDescription ?? "okänt fel")")
            self.statusLabel.text = "❌ Frånkopplad"
            self.connectButton.isEnabled = true
            self.createSessionButton.isEnabled = false
            self.createSessionButton.alpha = 0.5
        }
    }
    
    func didReceiveSessionId(_ sessionId: String) {
        self.sessionId = sessionId
        DispatchQueue.main.async {
            self.log("📍 Session skapad: \(sessionId)")
            self.sessionLabel.text = "Session: \(sessionId)"
        }
    }
    
    func didReceiveSessionReady() {
        DispatchQueue.main.async {
            self.log("✅ Session redo - båda enheter anslutna!")
            self.statusLabel.text = "✅ Session redo!"
        }
    }
    
    func didReceiveAPDUCommand(_ apdu: String, commandId: String, timestamp: Int64) {
        log("← APDU: \(apdu.prefix(40))...")
        
        // Skicka APDU till passet via NFC
        nfcReader?.sendAPDU(apdu) { [weak self] response in
            if let response = response {
                self?.log("→ Svar: \(response.prefix(40))...")
                self?.webSocketManager?.sendAPDUResponse(apdu: response, commandId: commandId, timestamp: timestamp)
            } else {
                self?.log("❌ Inget svar från pass")
                self?.webSocketManager?.sendAPDUResponse(apdu: "", commandId: commandId, timestamp: timestamp, error: "No response")
            }
        }
    }
    
    func didReceiveError(_ message: String) {
        DispatchQueue.main.async {
            self.log("❌ Fel: \(message)")
        }
    }
}

// MARK: - NFCPassportReaderDelegate
extension MainViewController: NFCPassportReaderDelegate {
    func nfcReaderDidConnect() {
        DispatchQueue.main.async {
            self.log("✅ Pass anslutet via NFC!")
            self.passportStatusLabel.text = "✅ Pass anslutet - redo för relay!"
        }
        
        webSocketManager?.sendPassportReady()
    }
    
    func nfcReaderDidDisconnect(error: Error?) {
        DispatchQueue.main.async {
            self.log("⚠️ NFC-session avslutad: \(error?.localizedDescription ?? "manuellt")")
            self.passportStatusLabel.text = "📱 NFC-session avslutad"
        }
    }
    
    func nfcReader(didReceiveError error: String) {
        DispatchQueue.main.async {
            self.log("❌ NFC-fel: \(error)")
            self.passportStatusLabel.text = "❌ \(error)"
        }
    }
}
