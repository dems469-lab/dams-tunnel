package com.v2ray.ang.premium

import android.content.Context
import androidx.compose.runtime.Composable

/** Point d'extension facultatif fourni uniquement par certaines variantes de compilation. */
interface PremiumBuildVariantExtension {
    val dataSource: PremiumSubscriptionDataSource
    val hasOptions: Boolean

    @Composable
    fun Options(onOptionSelected: (String) -> Unit)

    fun selectOption(option: String)
}

/** La variante standard n'embarque pas l'implémentation de cette extension. */
object PremiumBuildVariantExtensionLoader {
    private const val IMPLEMENTATION = "com.v2ray.ang.premiumtest.PremiumTestVariantExtension"

    fun load(context: Context): PremiumBuildVariantExtension? = try {
        Class.forName(IMPLEMENTATION)
            .getConstructor(Context::class.java)
            .newInstance(context.applicationContext) as PremiumBuildVariantExtension
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: ClassCastException) {
        null
    }
}
