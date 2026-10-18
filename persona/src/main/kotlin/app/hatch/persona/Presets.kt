package app.hatch.persona

import kotlinx.serialization.json.Json

/**
 * Built-in identities and regions, loaded from `personas/identities.json` and `personas/regions.json`
 * (repo root; exposed on the classpath by the `:persona` Gradle module).
 */
object Presets {
    const val REAL_DEVICE_ID = "real-device"
    const val DEVICE_REGION_ID = "device"
    const val DEFAULT_IDENTITY_ID = "win-firefox"

    private val json = Json { ignoreUnknownKeys = false }

    private val identityList: List<Identity> by lazy { load("/identities.json") }
    private val regionList: List<Region> by lazy { load("/regions.json") }

    fun identities(): List<Identity> = identityList
    fun regions(): List<Region> = regionList

    fun identity(id: String): Identity? = identityList.firstOrNull { it.id == id }
    fun region(id: String): Region? = regionList.firstOrNull { it.id == id }

    /** `win-firefox` × `device`: the most coherent desktop identity on Gecko, with the device's own region. */
    fun default(): Persona = Persona(
        identity = identity(DEFAULT_IDENTITY_ID) ?: error("missing preset identity $DEFAULT_IDENTITY_ID"),
        region = region(DEVICE_REGION_ID) ?: error("missing preset region $DEVICE_REGION_ID"),
    )

    /** The identity that leaves every engine default alone (no shim registered). */
    fun realDevice(): Identity = identity(REAL_DEVICE_ID) ?: error("missing preset identity $REAL_DEVICE_ID")

    private inline fun <reified T> load(resource: String): List<T> {
        val stream = Presets::class.java.getResourceAsStream(resource)
            ?: error("preset resource $resource not on classpath")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return json.decodeFromString(text)
    }
}
