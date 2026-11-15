package com.hometv.tv.ui.text

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.hometv.tv.core.util.Format

/** Catalog lookup used by [UiFormat]; arguments are already strings. */
interface StringResolver {
    fun string(@StringRes id: Int, vararg args: String): String

    fun plural(@PluralsRes id: Int, count: Int, vararg args: String): String
}

/**
 * Resources forced to es-ES: the catalog lives in the default `values/` folder anyway, and the
 * es-ES configuration also makes `<plurals>` pick its form with Spanish rules on an English TV.
 */
class ResourcesStringResolver(private val resources: Resources) : StringResolver {
    override fun string(id: Int, vararg args: String): String =
        if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: String): String = resources.getQuantityString(id, count, *args)

    companion object {
        fun spanish(context: Context): ResourcesStringResolver {
            val config = Configuration(context.resources.configuration).apply { setLocale(Format.LOCALE) }
            return ResourcesStringResolver(context.createConfigurationContext(config).resources)
        }
    }
}
