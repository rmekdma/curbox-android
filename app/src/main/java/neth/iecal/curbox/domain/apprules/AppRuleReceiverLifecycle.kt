package neth.iecal.curbox.domain.apprules

/** Transactional registration seam for the service's feature receivers. */
class AppRuleReceiverLifecycle(
    private val registrations: List<Registration>,
    private val isReady: () -> Boolean = { true }
) {
    class Registration(
        val register: () -> Unit,
        val unregister: () -> Unit
    )

    private val registered = mutableListOf<Registration>()

    fun register() {
        if (!isReady()) return
        try {
            registrations.forEach { registration ->
                registration.register()
                registered += registration
            }
        } catch (error: Exception) {
            unregister()
            throw error
        }
    }

    fun unregister(): List<Exception> {
        val errors = mutableListOf<Exception>()
        registered.asReversed().forEach { registration ->
            try {
                registration.unregister()
            } catch (error: Exception) {
                errors += error
            }
        }
        registered.clear()
        return errors
    }
}
