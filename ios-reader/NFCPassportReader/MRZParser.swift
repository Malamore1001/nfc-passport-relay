import Foundation

struct MRZData {
    let documentType: String
    let issuingCountry: String
    let surname: String
    let givenNames: String
    let documentNumber: String
    let documentNumberCheckDigit: String
    let nationality: String
    let dateOfBirth: String
    let dateOfBirthCheckDigit: String
    let sex: String
    let dateOfExpiry: String
    let dateOfExpiryCheckDigit: String
    let optionalData: String
    let compositeCheckDigit: String
    
    /// Returns the MRZ information string used for BAC key derivation
    func getMrzInformation() -> String {
        return "\(documentNumber)\(documentNumberCheckDigit)\(dateOfBirth)\(dateOfBirthCheckDigit)\(dateOfExpiry)\(dateOfExpiryCheckDigit)"
    }
}

class MRZParser {
    
    /// Parse TD3 format MRZ (passport, 2 lines of 44 characters)
    static func parseTD3(line1: String, line2: String) -> MRZData? {
        guard line1.count >= 44 && line2.count >= 44 else {
            return nil
        }
        
        let l1 = Array(line1)
        let l2 = Array(line2)
        
        // Line 1: Document type (2) + Country (3) + Name (39)
        let documentType = String(l1[0..<2]).replacingOccurrences(of: "<", with: "").trimmingCharacters(in: .whitespaces)
        let issuingCountry = String(l1[2..<5])
        
        // Parse name - split by << 
        let namePart = String(l1[5..<44])
        let nameParts = namePart.split(separator: "<", omittingEmptySubsequences: false)
        
        var surname = ""
        var givenNames = ""
        var foundDoubleSeparator = false
        
        for (index, part) in nameParts.enumerated() {
            if part.isEmpty && index + 1 < nameParts.count && nameParts[index + 1].isEmpty {
                foundDoubleSeparator = true
                continue
            }
            
            if !foundDoubleSeparator {
                if !surname.isEmpty && !part.isEmpty {
                    surname += " "
                }
                surname += String(part)
            } else {
                if !givenNames.isEmpty && !part.isEmpty {
                    givenNames += " "
                }
                givenNames += String(part)
            }
        }
        
        surname = surname.trimmingCharacters(in: .whitespaces)
        givenNames = givenNames.trimmingCharacters(in: .whitespaces)
        
        // Line 2: DocNo (9) + Check (1) + Nationality (3) + DOB (6) + Check (1) + Sex (1) + DOE (6) + Check (1) + Optional (14) + Check (1)
        let documentNumber = String(l2[0..<9]).replacingOccurrences(of: "<", with: "")
        let documentNumberCheckDigit = String(l2[9])
        let nationality = String(l2[10..<13])
        let dateOfBirth = String(l2[13..<19])
        let dateOfBirthCheckDigit = String(l2[19])
        let sex = String(l2[20])
        let dateOfExpiry = String(l2[21..<27])
        let dateOfExpiryCheckDigit = String(l2[27])
        let optionalData = String(l2[28..<42]).replacingOccurrences(of: "<", with: "")
        let compositeCheckDigit = String(l2[43])
        
        return MRZData(
            documentType: documentType,
            issuingCountry: issuingCountry,
            surname: surname,
            givenNames: givenNames,
            documentNumber: documentNumber,
            documentNumberCheckDigit: documentNumberCheckDigit,
            nationality: nationality,
            dateOfBirth: dateOfBirth,
            dateOfBirthCheckDigit: dateOfBirthCheckDigit,
            sex: sex,
            dateOfExpiry: dateOfExpiry,
            dateOfExpiryCheckDigit: dateOfExpiryCheckDigit,
            optionalData: optionalData,
            compositeCheckDigit: compositeCheckDigit
        )
    }
    
    /// Calculate MRZ check digit
    static func calculateCheckDigit(_ input: String) -> Int {
        let weights = [7, 3, 1]
        var sum = 0
        
        for (index, char) in input.enumerated() {
            let value: Int
            if char.isNumber {
                value = Int(String(char)) ?? 0
            } else if char == "<" {
                value = 0
            } else if char.isLetter {
                value = Int(char.asciiValue ?? 0) - 55 // A=10, B=11, etc.
            } else {
                value = 0
            }
            
            sum += value * weights[index % 3]
        }
        
        return sum % 10
    }
}
