package com.matias.editorpdf

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        // Cada vez que arranca el proceso (también tras reiniciar la tablet) se reprograma la búsqueda.
        AutoUpdates.schedule(this)
    }
}
