package de.christinecoenen.code.zapp.app.personal.series

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the periodic check for new shows in collections.
 */
object CollectionUpdateScheduler {

	private const val WORK_NAME = "collection-update-check"

	/**
	 * @param intervalMinutes Interval in minutes. 0 (or less) disables the check.
	 */
	fun schedule(context: Context, intervalMinutes: Int) {
		val workManager = WorkManager.getInstance(context)

		if (intervalMinutes <= 0) {
			workManager.cancelUniqueWork(WORK_NAME)
			return
		}

		val request = PeriodicWorkRequestBuilder<CollectionUpdateWorker>(
			intervalMinutes.toLong(),
			TimeUnit.MINUTES
		)
			.setConstraints(
				Constraints.Builder()
					.setRequiredNetworkType(NetworkType.CONNECTED)
					.build()
			)
			.build()

		workManager.enqueueUniquePeriodicWork(
			WORK_NAME,
			ExistingPeriodicWorkPolicy.UPDATE,
			request
		)
	}
}
