package com.matias.editorpdf

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Los íconos que se pueden elegir. Cada uno es un activity-alias del manifiesto que abre MainActivity. */
enum class AppIcon(val alias: String, val label: Int, val preview: Int) {
    FOCO("IconFoco", R.string.icon_foco, R.mipmap.ic_launcher),
    INDIGO("IconIndigo", R.string.icon_indigo, R.mipmap.ic_launcher_1),
    AZUL("IconAzul", R.string.icon_azul, R.mipmap.ic_launcher_2),
    VERDE("IconVerde", R.string.icon_verde, R.mipmap.ic_launcher_3),
    NOCHE("IconNoche", R.string.icon_noche, R.mipmap.ic_launcher_4);

    private fun component(context: Context) = ComponentName(context.packageName, "${context.packageName}.$alias")

    companion object {
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull { icon ->
                when (pm.getComponentEnabledSetting(icon.component(context))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    // Sin cambios guardados vale lo del manifiesto: solo el primero viene activo.
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == FOCO
                    else -> false
                }
            } ?: FOCO
        }

        /** Activa el ícono elegido y apaga los demás; el launcher lo cambia a los pocos segundos. */
        fun set(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            for (other in entries) if (other != icon) {
                pm.setComponentEnabledSetting(
                    other.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
