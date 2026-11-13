import SwiftUI
import UIKit
import QuietmapUI

/// Hosts the shared Compose screens. Everything else lives in Kotlin (`QuietmapIos`).
@main
struct QuietmapApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .onOpenURL { url in _ = QuietmapIos.shared.handleUrl(url: url.absoluteString) }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Also runs when iOS relaunches the app in the background for a significant location change.
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        QuietmapIos.shared.start()
        return true
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { QuietmapIosKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
