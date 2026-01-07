import com.faselhd.utils.PluginContext

@CloudstreamPlugin
class FaselHDPlugin: Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        registerMainAPI(FaselHD())
    }
}
