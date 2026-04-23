package net.iovxw.pwap.scanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.king.camera.scan.CameraScan
import com.king.camera.scan.analyze.Analyzer
import com.king.zxing.DecodeConfig
import com.king.zxing.BarcodeCameraScanActivity
import com.king.zxing.analyze.QRCodeAnalyzer
import net.iovxw.pwap.R

class ScannerActivity : BarcodeCameraScanActivity() {

    override fun initCameraScan(cameraScan: CameraScan<com.google.zxing.Result>) {
        super.initCameraScan(cameraScan)
        cameraScan.setAnalyzer(createAnalyzeQRCode())
    }

    override fun onScanResultCallback(result: com.king.camera.scan.AnalyzeResult<com.google.zxing.Result>) {
        cameraScan.setAnalyzeImage(false)
        val text = result.result?.text
        if (text.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.scanner_no_content), Toast.LENGTH_SHORT).show()
            cameraScan.setAnalyzeImage(true)
            return
        }
        val intent = Intent().apply {
            putExtra(EXTRA_SCAN_RESULT, text)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private fun createAnalyzeQRCode(): Analyzer<com.google.zxing.Result> {
        val decodeConfig = DecodeConfig().apply {
            setHints(com.king.zxing.DecodeFormatManager.QR_CODE_HINTS)
        }
        return QRCodeAnalyzer(decodeConfig)
    }

    companion object {
        const val EXTRA_SCAN_RESULT = "scan_result"
    }
}
