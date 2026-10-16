# Editor de PDF

App para Android (pensada para la Samsung Galaxy Tab S9+ con S Pen) para estudiar con PDFs:
abrir, leer, resaltar, subrayar, escribir a mano y agregar notas de texto, guardando todo dentro del mismo PDF.

## Instalar en la tablet

1. En la tablet, abre la página **Releases** de este repositorio y descarga `EditorDePDF.apk` de la última versión.
2. Ábrelo. Si Android lo pide, permite **Instalar apps desconocidas** para el navegador (o para «Mis archivos»).
3. Las versiones nuevas se instalan encima de la anterior sin perder nada.

Cada cambio en `main` publica una versión nueva. Los APK de otras ramas quedan como artefacto en la pestaña **Actions**.

## Cómo se usa

| Herramienta | Qué hace |
|---|---|
| ✋ Mover | Desplazarse y hacer zoom (también con el S Pen) |
| ✏️ Lápiz | Escritura a mano, sensible a la presión del S Pen |
| 🖍️ Resaltar | Resaltador transparente; con «Trazo recto» queda una línea prolija (se endereza sola si está casi horizontal) |
| 〰️ Subrayar | Línea recta fina para subrayar |
| 🔤 Texto | Toca la página para escribir una nota. Toca una nota para editarla o borrarla; arrástrala para moverla |
| 🧽 Borrar | Borra trazos y notas completas al pasar por encima |

- **S Pen escribe, el dedo navega**: con el dedo te mueves y haces zoom con dos dedos aunque tengas el lápiz seleccionado. La palma apoyada se ignora mientras escribes. Si quieres escribir con el dedo, actívalo en el menú ⋮.
- **Botón del S Pen** apretado = borrador temporal.
- **Doble toque** con el dedo: zoom rápido.
- Toca el número de página (abajo a la derecha) para saltar a una página. La app recuerda en qué página quedaste.
- **Guardar** escribe las anotaciones dentro del PDF como anotaciones estándar: se ven en Adobe, Xodo, Drive o la PC, y al volver a abrir el PDF en esta app siguen siendo editables.
- Si cierras sin guardar, la app conserva un borrador y lo recupera la próxima vez.
- Si abriste el PDF desde otra app que solo da permiso de lectura, usa **Guardar una copia**. Para guardar sobre el original, ábrelo desde el botón «Abrir PDF».

## Detalles técnicos

- Kotlin nativo, sin servicios externos. Mínimo Android 10.
- Las páginas se dibujan con el motor de PDF de Android (`PdfRenderer`); las anotaciones se escriben con
  [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0) como anotaciones `Ink` y `Stamp` con su apariencia,
  más una copia de los datos en la clave `/EdPdfData` para poder seguir editándolas.
- `keystore/editorpdf.jks` es la firma fija de la app (contraseña `editorpdf`), para que las actualizaciones se instalen encima.
  No es un secreto: solo sirve para esta app personal.
- Compilar localmente: `./gradlew assembleRelease` (requiere Android SDK).
