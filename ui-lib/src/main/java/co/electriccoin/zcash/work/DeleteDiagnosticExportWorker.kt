package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

/** Removes user-requested diagnostic copies after the receiving app has had time to read them. */
class DeleteDiagnosticExportWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val tax = inputData.getString(TAX_STAGING_NAME)
        val pczt = inputData.getString(PCZT_STAGING_NAME)
        return when {
            tax != null -> removeStaging(TAX_DIRECTORY, tax, TAX_STAGING_PATTERN)
            pczt != null -> removeStaging(PCZT_DIRECTORY, pczt, PCZT_STAGING_PATTERN)
            else -> removeDiagnostic()
        }
    }

    private fun removeStaging(directory: String, name: String, pattern: Regex): Result {
        if (!pattern.matches(name)) return Result.failure()
        val staging = File(File(applicationContext.cacheDir, directory), name)
        return if (staging.deleteRecursively()) Result.success() else Result.retry()
    }

    private fun removeDiagnostic(): Result {
        val name = inputData.getString(FILE_NAME)?.takeIf {
            it != "." && it != ".." && File(it).name == it
        } ?: return Result.failure()
        val file = File(File(applicationContext.cacheDir, DIRECTORY), name)
        return if (!file.exists() || file.delete()) Result.success() else Result.retry()
    }

    companion object {
        const val DIRECTORY = "diagnostic_exports"
        const val TAX_DIRECTORY = "tax_exports"
        const val PCZT_DIRECTORY = "pczt_exports"
        private const val FILE_NAME = "file-name"
        private const val TAX_STAGING_NAME = "tax-staging-name"
        private const val PCZT_STAGING_NAME = "pczt-staging-name"
        private val TAX_STAGING_PATTERN = Regex("tax-[0-9]+")
        private val PCZT_STAGING_PATTERN = Regex("pczt-[0-9]+")
        private const val RETENTION_HOURS = 1L

        /** Android may defer this cleanup while the app is force-stopped or background work is restricted. */
        fun schedule(context: Context, file: File) = enqueue(context, FILE_NAME, file.name)

        fun scheduleTax(context: Context, staging: File) = enqueue(context, TAX_STAGING_NAME, staging.name)

        fun schedulePczt(context: Context, staging: File) = enqueue(context, PCZT_STAGING_NAME, staging.name)

        private fun enqueue(context: Context, key: String, name: String) = WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<DeleteDiagnosticExportWorker>()
                .setInputData(workDataOf(key to name))
                .setInitialDelay(RETENTION_HOURS, TimeUnit.HOURS)
                .build()
        )
    }
}
