package neth.iecal.curbox.debug

import android.app.Service
import android.content.Intent
import androidx.core.app.CoreComponentFactory
import neth.iecal.curbox.services.AppBlockerService

/** Installs the ticket19 tracer only in debug APKs without changing the production service. */
class Ticket19DebugAppComponentFactory : CoreComponentFactory() {
    override fun instantiateService(
        classLoader: ClassLoader,
        className: String,
        intent: Intent?
    ): Service {
        val service = super.instantiateService(classLoader, className, intent)
        if (service is AppBlockerService) {
            Ticket19ObserverRegistry.attach(service)
        }
        return service
    }
}
