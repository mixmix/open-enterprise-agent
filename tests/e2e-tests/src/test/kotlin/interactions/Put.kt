package interactions

import common.Environments
import net.serenitybdd.screenplay.Actor
import net.serenitybdd.screenplay.Tasks
import net.serenitybdd.screenplay.rest.abilities.CallAnApi
import net.serenitybdd.screenplay.rest.interactions.RestInteraction
import net.thucydides.core.annotations.Step


/**
 * This class is a copy of the class Put from serenity rest interactions
 * to add a custom authentication header to the request on-the-fly.
 */
open class Put(private val resource: String) : RestInteraction() {
    @Step("{0} executes a PUT on the resource #resource")
    override fun <T : Actor?> performAs(actor: T) {
        val spec = rest()
        if (Environments.AGENT_AUTH_REQUIRED) {
            spec.header(Environments.AGENT_AUTH_HEADER, actor!!.recall("AUTH_KEY"))
        }
        spec.put(CallAnApi.`as`(actor).resolve(resource))
    }

    companion object {
        fun to(resource: String?): Put {
            return Tasks.instrumented(Put::class.java, resource)
        }
    }
}