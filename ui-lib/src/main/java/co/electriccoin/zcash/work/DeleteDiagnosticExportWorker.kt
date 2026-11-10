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
        val name = inputData.getString(FILE_NAME)?.takeIf {
            it != "." && it != ".." && File(it).name == it
        } ?: return Result.failure()
        val file = File(File(applicationContext.cacheDir, DIRECTORY), name)
        return if (!file.exists() || file.delete()) Result.success() else Result.retry()
    }

    companion object {
        const val DIRECTORY = "diagnostic_exports"
        private const val FILE_NAME = "file-name"
        private const val RETENTION_HOURS = 1L

        /** Android may defer this cleanup while the app is force-stopped or background work is restricted. */
        fun schedule(context: Context, file: File) = WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<DeleteDiagnosticExportWorker>()
                .setInputData(workDataOf(FILE_NAME to file.name))
                .setInitialDelay(RETENTION_HOURS, TimeUnit.HOURS)
                .build()
        )
    }
}
