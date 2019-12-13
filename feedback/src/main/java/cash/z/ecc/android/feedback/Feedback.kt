package cash.z.ecc.android.feedback

import cash.z.ecc.android.feedback.util.CompositeJob
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlin.coroutines.coroutineContext

class Feedback(capacity: Int = 256) {
    lateinit var scope: CoroutineScope
        private set

    private val _metrics = BroadcastChannel<Metric>(capacity)
    private val _actions = BroadcastChannel<Action>(capacity)
    private var onStartListener: () -> Unit = {}

    private val jobs = CompositeJob()

    val metrics: Flow<Metric> = _metrics.asFlow()
    val actions: Flow<Action> = _actions.asFlow()

    /**
     * Verifies that this class is not leaking anything. Checks that all underlying channels are
     * closed and all launched reporting jobs are inactive.
     */
    val isStopped get() = ensureScope() && _metrics.isClosedForSend && _actions.isClosedForSend && !scope.isActive && !jobs.isActive()

    /**
     * Starts this feedback as a child of the calling coroutineContext, meaning when that context
     * ends, this feedback's scope and anything it launced will cancel. Note that the [metrics] and
     * [actions] channels will remain open unless [stop] is also called on this instance.
     */
    suspend fun start(): Feedback {
        check(!::scope.isInitialized) {
            "Error: cannot initialize feedback because it has already been initialized."
        }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob(coroutineContext[Job]))
        scope.coroutineContext[Job]!!.invokeOnCompletion {
            _metrics.close()
            _actions.close()
        }
        onStartListener()
        return this
    }

    /**
     * Invokes the given callback after the scope has been initialized or immediately, if the scope
     * has already been initialized. This is used by [FeedbackProcessor] and things like it that
     * want to immediately begin collecting the metrics/actions flows because any emissions that
     * occur before subscription are dropped.
     */
    fun onStart(onStartListener: () -> Unit) {
        if (::scope.isInitialized) {
            onStartListener()
        } else {
            this.onStartListener = onStartListener
        }
    }

    /**
     * Stop this instance and close all reporting channels. This function will first wait for all
     * in-flight reports to complete.
     */
    suspend fun stop() {
        // expose instances where stop is being called before start occurred.
        ensureScope()
        await()
        scope.cancel()
    }

    /**
     * Suspends until all in-flight reports have completed. This is automatically called before
     * [stop].
     */
    suspend fun await() {
        jobs.await()
    }

    /**
     * Measures the given block of code by surrounding it with time metrics and the reporting the
     * result.
     *
     * Don't measure code that launches coroutines, instead measure the code within the coroutine
     * that gets launched. Otherwise, the timing will be incorrect because the launched coroutine
     * will run concurrently--meaning a "happens before" relationship between the measurer and the
     * measured cannot be established and thereby the concurrent action cannot be timed.
     */
    inline fun <T> measure(description: Any = "measurement", block: () -> T) {
        ensureScope()
        val metric = TimeMetric(description.toString()).markTime()
        block()
        metric.markTime()
        report(metric)
    }

    /**
     * Add the given metric to the stream of metric events.
     *
     * @param metric the metric to add.
     */
    fun report(metric: Metric) {
        jobs += scope.launch {
            _metrics.send(metric)
        }
    }

    /**
     * Add the given action to the  stream of action events.
     *
     * @param action the action to add.
     */
    fun report(action: Action) {
        jobs += scope.launch {
            _actions.send(action)
        }
    }

    /**
     * Ensures that the scope for this instance has been initialized.
     */
    fun ensureScope(): Boolean {
        check(::scope.isInitialized) {
            "Error: feedback has not been initialized. Before attempting to use this feedback" +
                    " object, ensure feedback.start() has been called."
        }
        return true
    }

    fun ensureStopped(): Boolean {
        val errors = mutableListOf<String>()

        if (!_metrics.isClosedForSend && !_actions.isClosedForSend) errors += "both channels are still open"
        else if (!_actions.isClosedForSend) errors += "the actions channel is still open"
        else if (!_metrics.isClosedForSend) errors += "the metrics channel is still open"

        if (scope.isActive) errors += "the scope is still active"
        if (jobs.isActive()) errors += "reporting jobs are still active"
        if (errors.isEmpty()) return true
        throw IllegalStateException("Feedback is still active because ${errors.joinToString(", ")}.")
    }

}