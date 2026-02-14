import Foundation
import CoreNFC

protocol NFCPassportReaderDelegate: AnyObject {
    func nfcReaderDidConnect()
    func nfcReaderDidDisconnect(error: Error?)
    func nfcReader(didReceiveError error: String)
}

class NFCPassportReader: NSObject {
    
    weak var delegate: NFCPassportReaderDelegate?
    
    private var session: NFCTagReaderSession?
    private var passport: NFCISO7816Tag?
    private var mrzData: MRZData
    private var pendingAPDU: ((String?) -> Void)?
    
    // eMRTD AID
    private let eMRTD_AID: [UInt8] = [0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01]
    
    init(mrzData: MRZData) {
        self.mrzData = mrzData
        super.init()
    }
    
    func startReading() {
        guard NFCTagReaderSession.readingAvailable else {
            delegate?.nfcReader(didReceiveError: "NFC är inte tillgängligt")
            return
        }
        
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self)
        session?.alertMessage = "Håll passet mot telefonens ovansida"
        session?.begin()
    }
    
    func stopReading() {
        session?.invalidate()
        session = nil
        passport = nil
    }
    
    func sendAPDU(_ apduHex: String, completion: @escaping (String?) -> Void) {
        guard let passport = passport else {
            completion(nil)
            return
        }
        
        let apduData = hexStringToData(apduHex)
        guard apduData.count >= 4 else {
            completion(nil)
            return
        }
        
        // Parse APDU
        let cla = apduData[0]
        let ins = apduData[1]
        let p1 = apduData[2]
        let p2 = apduData[3]
        
        var data = Data()
        var le: Int = -1
        
        if apduData.count > 5 {
            let lc = Int(apduData[4])
            if apduData.count >= 5 + lc {
                data = apduData.subdata(in: 5..<(5 + lc))
                if apduData.count > 5 + lc {
                    le = Int(apduData[5 + lc])
                    if le == 0 { le = 256 }
                }
            }
        } else if apduData.count == 5 {
            le = Int(apduData[4])
            if le == 0 { le = 256 }
        }
        
        let apdu = NFCISO7816APDU(
            instructionClass: cla,
            instructionCode: ins,
            p1Parameter: p1,
            p2Parameter: p2,
            data: data,
            expectedResponseLength: le
        )
        
        passport.sendCommand(apdu: apdu) { responseData, sw1, sw2, error in
            if let error = error {
                print("APDU Error: \(error.localizedDescription)")
                completion(nil)
                return
            }
            
            var response = Data(responseData)
            response.append(sw1)
            response.append(sw2)
            
            let responseHex = self.dataToHexString(response)
            completion(responseHex)
        }
    }
    
    // MARK: - Helpers
    private func hexStringToData(_ hex: String) -> Data {
        var data = Data()
        var temp = ""
        
        for char in hex {
            temp += String(char)
            if temp.count == 2 {
                if let byte = UInt8(temp, radix: 16) {
                    data.append(byte)
                }
                temp = ""
            }
        }
        return data
    }
    
    private func dataToHexString(_ data: Data) -> String {
        return data.map { String(format: "%02X", $0) }.joined()
    }
    
    private func selectEMRTDApplet() {
        guard let passport = passport else { return }
        
        let selectAPDU = NFCISO7816APDU(
            instructionClass: 0x00,
            instructionCode: 0xA4,
            p1Parameter: 0x04,
            p2Parameter: 0x0C,
            data: Data(eMRTD_AID),
            expectedResponseLength: -1
        )
        
        passport.sendCommand(apdu: selectAPDU) { [weak self] _, sw1, sw2, error in
            if let error = error {
                self?.delegate?.nfcReader(didReceiveError: "SELECT fel: \(error.localizedDescription)")
                return
            }
            
            if sw1 == 0x90 && sw2 == 0x00 {
                print("✅ eMRTD applet selected successfully")
                self?.delegate?.nfcReaderDidConnect()
            } else {
                let swHex = String(format: "%02X%02X", sw1, sw2)
                self?.delegate?.nfcReader(didReceiveError: "SELECT returnerade: \(swHex)")
            }
        }
    }
}

// MARK: - NFCTagReaderSessionDelegate
extension NFCPassportReader: NFCTagReaderSessionDelegate {
    
    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        print("NFC session active")
    }
    
    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        let readerError = error as? NFCReaderError
        if readerError?.code != .readerSessionInvalidationErrorUserCanceled {
            delegate?.nfcReaderDidDisconnect(error: error)
        } else {
            delegate?.nfcReaderDidDisconnect(error: nil)
        }
        
        self.session = nil
        self.passport = nil
    }
    
    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first else {
            session.invalidate(errorMessage: "Ingen tagg hittad")
            return
        }
        
        guard case let .iso7816(passport) = tag else {
            session.invalidate(errorMessage: "Detta är inte ett pass/eMRTD")
            return
        }
        
        session.connect(to: tag) { [weak self] error in
            if let error = error {
                session.invalidate(errorMessage: "Kunde inte ansluta: \(error.localizedDescription)")
                return
            }
            
            self?.passport = passport
            session.alertMessage = "Pass anslutet! Håll kvar..."
            
            // Select eMRTD applet
            self?.selectEMRTDApplet()
        }
    }
}
