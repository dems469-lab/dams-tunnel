package com.v2ray.ang.ui.backup

import android.app.Application
import android.net.Uri
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.ui.base.ViewModelEvent
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ZipUtil
import com.v2ray.ang.util.DamsFileCodec
import com.v2ray.ang.util.BackupFileSize
import com.v2ray.ang.util.BackupFileTooLargeException
import com.v2ray.ang.util.readBytesLimited
import com.v2ray.ang.util.DamsRestoreAttemptPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class BackupViewModel(application: Application) : BaseViewModel(application) {

    private val _webDavConfig = MutableStateFlow(MmkvManager.decodeWebDavConfig())
    val webDavConfig: StateFlow<WebDavConfig?> = _webDavConfig.asStateFlow()

    fun cleanupOldBackupTempFiles(cacheDir: File) {
        launchLoading {
            withContext(Dispatchers.IO) {
                deleteExpiredBackupTempFiles(
                    cacheDir,
                    System.currentTimeMillis(),
                    BACKUP_TEMP_MAX_AGE_MILLIS,
                )
            }
        }
    }

    sealed interface BackupViewModelEvent : ViewModelEvent {
        data class ShareFile(val filePath: String) : BackupViewModelEvent
        data class ExportLocal(val cachePath: String, val targetUri: Uri) : BackupViewModelEvent
        data class DamsRestoreFailed(val remainingAttempts: Int, val incorrectPassword: Boolean) : BackupViewModelEvent
        object RestoreSuccess : BackupViewModelEvent
    }

    fun saveWebDavConfig(config: WebDavConfig) {
        MmkvManager.encodeWebDavConfig(config)
        _webDavConfig.value = config
        toastSuccess(R.string.toast_success)
    }

    fun cleanupProfileStorage() {
        launchLoading {
            try {
                val removed = withContext(Dispatchers.IO) {
                    MmkvManager.removeOrphanedServerProfiles()
                }
                if (removed == null) {
                    toastError(R.string.toast_profile_storage_cleanup_skipped)
                } else {
                    toastSuccess(getString(R.string.toast_profile_storage_cleanup, removed))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to clean up profile storage", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun shareBackup(cacheDir: File, appName: String) {
        launchLoading {
            try {
                val ret = backupConfigurationToCache(cacheDir, appName)
                if (ret.first) {
                    _viewModelEvent.send(BackupViewModelEvent.ShareFile(ret.second))
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: BackupFileTooLargeException) {
                toastError(R.string.backup_file_too_large)
            }
        }
    }

    fun prepareBackupForUri(cacheDir: File, appName: String, targetUri: Uri) {
        launchLoading {
            try {
                val ret = backupConfigurationToCache(cacheDir, appName)
                if (ret.first) {
                    _viewModelEvent.send(BackupViewModelEvent.ExportLocal(ret.second, targetUri))
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: BackupFileTooLargeException) {
                toastError(R.string.backup_file_too_large)
            }
        }
    }

    fun prepareDamsBackupForUri(
        cacheDir: File,
        appName: String,
        targetUri: Uri,
        password: String,
        expiresAtEpochMillis: Long?,
    ) {
        launchLoading {
            try {
                val ret = backupConfigurationToCache(cacheDir, appName)
                if (!ret.first) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }
                val zipFile = File(ret.second)
                withTemporaryFileCleanup(zipFile) {
                    val zipBytes = zipFile.inputStream().use { it.readBytesLimited() }
                    val damsBytes = DamsFileCodec.encrypt(
                        zipBytes,
                        DamsFileCodec.ExportOptions(password, expiresAtEpochMillis),
                    )
                    val damsPath = File(cacheDir, "${appName}_${System.currentTimeMillis()}.dams")
                    damsPath.writeBytes(damsBytes)
                    _viewModelEvent.send(BackupViewModelEvent.ExportLocal(damsPath.absolutePath, targetUri))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to create Dams export", e)
                toastError(if (e is BackupFileTooLargeException) R.string.backup_file_too_large else R.string.toast_failure)
            }
        }
    }

    fun restoreConfiguration(cacheDir: File, zipFile: File) {
        launchLoading {
            try {
                val success = withTemporaryFileCleanup(zipFile) {
                    performRestore(cacheDir, zipFile)
                }
                if (success) {
                    toastSuccess(R.string.toast_success)
                    _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: BackupFileTooLargeException) {
                toastError(R.string.backup_file_too_large)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to restore ZIP backup", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun restoreDamsConfiguration(
        cacheDir: File,
        damsFile: File,
        password: String,
        attemptsRemaining: Int,
    ) {
        launchLoading {
            try {
                val zipFile = File(cacheDir, "restore_${System.currentTimeMillis()}.zip")
                withTemporaryFileCleanup(zipFile) {
                    val damsBytes = damsFile.inputStream().use { it.readBytesLimited() }
                    zipFile.writeBytes(DamsFileCodec.decrypt(damsBytes, password))
                    val success = performRestore(cacheDir, zipFile)
                    if (!success) {
                        throw IllegalStateException("The .dams archive could not be restored")
                    }
                }
                damsFile.delete()
                toastSuccess(R.string.toast_success)
                _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to restore Dams export", e)
                val incorrectPassword = e is DamsFileCodec.IncorrectPasswordException
                val remaining = DamsRestoreAttemptPolicy.remainingAfterFailure(attemptsRemaining, e)
                _viewModelEvent.send(BackupViewModelEvent.DamsRestoreFailed(remaining, incorrectPassword))
            } finally {
                damsFile.delete()
            }
        }
    }

    fun backupViaWebDav(cacheDir: File, appName: String) {
        val config = _webDavConfig.value
        if (config == null || (config.baseUrl.isEmpty())) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }

        launchLoading {
            var tempFile: File? = null
            try {
                val ret = backupConfigurationToCache(cacheDir, appName)
                if (!ret.first) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }

                tempFile = File(ret.second)
                WebDavManager.init(config)

                val ok = try {
                    WebDavManager.uploadFile(tempFile, AppConfig.WEBDAV_BACKUP_FILE_NAME)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "WebDAV upload error", e)
                    false
                }

                if (ok) {
                    toastSuccess(R.string.toast_success)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV backup error", e)
                toastError(if (e is BackupFileTooLargeException) R.string.backup_file_too_large else R.string.toast_failure)
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun restoreViaWebDav(cacheDir: File) {
        val config = _webDavConfig.value
        if (config == null || (config.baseUrl.isEmpty())) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }

        launchLoading {
            var target: File? = null
            try {
                target = File(cacheDir, "download_${System.currentTimeMillis()}.zip")
                WebDavManager.init(config)
                val ok = WebDavManager.downloadFile(AppConfig.WEBDAV_BACKUP_FILE_NAME, target)
                if (!ok) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }

                if (performRestore(cacheDir, target)) {
                    toastSuccess(R.string.toast_success)
                    _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV download error", e)
                toastError(if (e is BackupFileTooLargeException) R.string.backup_file_too_large else R.string.toast_failure)
            } finally {
                target?.delete()
            }
        }
    }

    private fun backupConfigurationToCache(cacheDir: File, appName: String): Pair<Boolean, String> {
        val dateFormatted = SimpleDateFormat(
            "yyyy-MM-dd-HH-mm-ss",
            Locale.getDefault()
        ).format(System.currentTimeMillis())
        val folderName = "${appName}_$dateFormatted"
        val backupDir = cacheDir.absolutePath + "/$folderName"
        val outputZipFilePath = "${cacheDir.absolutePath}/$folderName.zip"

        val count = MMKV.backupAllToDirectory(backupDir)
        if (count <= 0) {
            return Pair(false, "")
        }

        return if (ZipUtil.zipFromFolder(backupDir, outputZipFilePath)) {
            val zipFile = File(outputZipFilePath)
            if (zipFile.length() > BackupFileSize.MAX_BYTES) {
                zipFile.delete()
                throw BackupFileTooLargeException()
            }
            Pair(true, outputZipFilePath)
        } else {
            Pair(false, "")
        }
    }

    private suspend fun performRestore(cacheDir: File, zipFile: File): Boolean =
        withContext(Dispatchers.IO) {
            if (zipFile.length() > BackupFileSize.MAX_BYTES) throw BackupFileTooLargeException()
            val backupDir = File(cacheDir, "restore_${System.nanoTime()}")
            try {
                if (!ZipUtil.unzipToFolder(zipFile, backupDir.absolutePath)) {
                    return@withContext false
                }

                val count = MMKV.restoreAllFromDirectory(backupDir.absolutePath)
                SettingsChangeManager.makeSetupGroupTab()
                SettingsChangeManager.makeRestartService()
                count > 0
            } finally {
                backupDir.deleteRecursively()
            }
        }

    private companion object {
        const val BACKUP_TEMP_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}
