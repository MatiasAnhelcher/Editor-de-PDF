package com.matias.editorpdf

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
