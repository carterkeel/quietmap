package app.quietmap.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UILabel
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/**
 * In-app QR scanner, the counterpart of Android's zxing scanner: a full-screen camera view that
 * returns the first QR code it sees. Uses AVFoundation's built-in QR detection, so no extra
 * library. Frames stay on the phone; nothing is recorded.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosQrScanner(private val showMessage: (String) -> Unit) {

    fun scan(presenter: UIViewController, onResult: (String?) -> Unit) {
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> present(presenter, onResult)
            AVAuthorizationStatusNotDetermined -> AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                dispatch_async(dispatch_get_main_queue()) {
                    if (granted) present(presenter, onResult) else denied(onResult)
                }
            }
            else -> denied(onResult)
        }
    }

    private fun denied(onResult: (String?) -> Unit) {
        showMessage("Allow camera access in Settings to scan invite codes.")
        onResult(null)
    }

    private fun present(presenter: UIViewController, onResult: (String?) -> Unit) {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, error = null) }
        if (input == null) { // e.g. the simulator, which has no camera
            showMessage("No camera available. Paste the invite link instead.")
            return onResult(null)
        }
        val scanner = ScannerController(input, onResult)
        scanner.modalPresentationStyle = UIModalPresentationFullScreen
        presenter.presentViewController(scanner, animated = true, completion = null)
    }

    private class ScannerController(
        private val input: AVCaptureDeviceInput,
        private val onResult: (String?) -> Unit,
    ) : UIViewController(nibName = null, bundle = null) {
        private val session = AVCaptureSession()
        private val preview = AVCaptureVideoPreviewLayer(session = session)
        private val delegate = Delegate { finish(it) }
        private var done = false

        override fun viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = UIColor.blackColor
            preview.videoGravity = AVLayerVideoGravityResizeAspectFill
            view.layer.addSublayer(preview)

            val output = AVCaptureMetadataOutput()
            if (session.canAddInput(input)) session.addInput(input)
            if (session.canAddOutput(output)) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(delegate, queue = dispatch_get_main_queue())
                output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
            }

            val hint = UILabel().apply {
                text = "Point the camera at a Quietmap invite code"
                textColor = UIColor.whiteColor
                font = UIFont.systemFontOfSize(17.0)
                textAlignment = NSTextAlignmentCenter
                numberOfLines = 0
                tag = HINT_TAG
            }
            view.addSubview(hint)

            val cancel = UIButton.buttonWithConfiguration(
                UIButtonConfiguration.filledButtonConfiguration().apply {
                    title = "Cancel"
                    baseBackgroundColor = UIColor.colorWithWhite(1.0, alpha = 0.2)
                    baseForegroundColor = UIColor.whiteColor
                    cornerStyle = 4 // UIButtonConfigurationCornerStyleCapsule: pill, like the shared buttons
                },
                primaryAction = UIAction.actionWithHandler { _ -> finish(null) },
            ).apply { tag = CANCEL_TAG }
            view.addSubview(cancel)
        }

        override fun viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            val (w, h) = view.bounds.useContents { size.width to size.height }
            val (top, bottom) = view.safeAreaInsets.useContents { this.top to this.bottom }
            preview.frame = view.bounds
            view.viewWithTag(HINT_TAG)?.setFrame(CGRectMake(24.0, top + 24.0, w - 48.0, 60.0))
            view.viewWithTag(CANCEL_TAG)?.setFrame(CGRectMake(24.0, h - bottom - 24.0 - 52.0, w - 48.0, 52.0))
        }

        override fun viewDidAppear(animated: Boolean) {
            super.viewDidAppear(animated)
            // startRunning blocks while the camera starts; keep it off the main thread.
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.startRunning() }
        }

        override fun viewWillDisappear(animated: Boolean) {
            super.viewWillDisappear(animated)
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.stopRunning() }
        }

        private fun finish(text: String?) {
            if (done) return
            done = true
            dismissViewControllerAnimated(true) { onResult(text) }
        }
    }

    private class Delegate(private val found: (String) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
        override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
            didOutputMetadataObjects.firstNotNullOfOrNull { (it as? AVMetadataMachineReadableCodeObject)?.stringValue }?.let(found)
        }
    }

    private companion object {
        const val HINT_TAG = 1L
        const val CANCEL_TAG = 2L
    }
}
