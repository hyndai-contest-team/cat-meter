package kr.catmeter.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 고양 영수증 이미지를 저장·공유한다. 기기 안에서만 처리하고 서버로 보내지 않는다. */
object ReceiptExporter {

    /** 갤러리(Pictures/CatMeter)에 저장. 성공하면 사용자에게 보여 줄 위치를 돌려준다. */
    suspend fun save(context: Context, bitmap: Bitmap, name: String): String = withContext(Dispatchers.IO) {
        val fileName = "$name.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CatMeter")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("저장 위치를 만들 수 없어요")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: error("파일을 열 수 없어요")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "사진 > Pictures/CatMeter"
        } else {
            // API 28: 권한 없이 쓸 수 있는 앱 전용 사진 폴더
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CatMeter").apply { mkdirs() }
            File(dir, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dir.absolutePath
        }
    }

    /** 공유 시트를 띄운다. 받을 앱이 없으면 false. */
    suspend fun share(context: Context, bitmap: Bitmap, name: String): Boolean {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
            File(dir, "$name.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (send.resolveActivity(context.packageManager) == null) return false
        context.startActivity(Intent.createChooser(send, "고양 영수증 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
