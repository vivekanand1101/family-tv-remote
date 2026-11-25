import Foundation
import Speech

func fail(_ message: String, code: Int32 = 1) -> Never {
    FileHandle.standardError.write(Data("\(message)\n".utf8))
    exit(code)
}

guard CommandLine.arguments.count >= 2 else {
    fail("Usage: macos-transcribe <audio-file> [locale]")
}

let audioURL = URL(fileURLWithPath: CommandLine.arguments[1])
let localeID = CommandLine.arguments.count >= 3 ? CommandLine.arguments[2] : "en-IN"

func recognize() {
    guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: localeID)) else {
        fail("Speech recognition is unavailable for locale \(localeID)")
    }
    guard recognizer.isAvailable else {
        fail("Apple Speech recognition is currently unavailable")
    }

    let request = SFSpeechURLRecognitionRequest(url: audioURL)
    request.shouldReportPartialResults = false
    request.addsPunctuation = true

    var task: SFSpeechRecognitionTask?
    task = recognizer.recognitionTask(with: request) { result, error in
        if let result, result.isFinal {
            print(result.bestTranscription.formattedString)
            task?.cancel()
            exit(0)
        }
        if let error {
            fail("Speech recognition failed: \(error.localizedDescription)")
        }
    }

    DispatchQueue.main.asyncAfter(deadline: .now() + 45) {
        task?.cancel()
        fail("Speech recognition timed out")
    }
}

switch SFSpeechRecognizer.authorizationStatus() {
case .authorized:
    recognize()
case .notDetermined:
    SFSpeechRecognizer.requestAuthorization { status in
        guard status == .authorized else {
            fail("Speech recognition permission was not granted")
        }
        DispatchQueue.main.async { recognize() }
    }
case .denied:
    fail("Speech recognition permission is denied. Enable it in System Settings > Privacy & Security > Speech Recognition.")
case .restricted:
    fail("Speech recognition is restricted on this Mac")
@unknown default:
    fail("Unknown speech recognition authorization state")
}

RunLoop.main.run()
