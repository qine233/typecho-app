package noline.zhouzhou.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Android 8 之后装自己下载的包，要用户先在系统设置里放行「安装未知应用」。 */
fun Context.canInstallFromUnknownSources(): Boolean = packageManager.canRequestPackageInstalls()

fun Context.openInstallPermissionSettings() {
    startActivity(
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** 把缓存里的 apk 交给系统安装器；URI 必须现授读权限，安装器是别的进程。 */
fun Context.installApk(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
    )
}
