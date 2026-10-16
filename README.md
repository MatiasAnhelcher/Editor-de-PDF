# Editor de PDF

App para Android (pensada para la Samsung Galaxy Tab S9+ con S Pen) para estudiar con PDFs:
abrir, leer, resaltar, subrayar, escribir a mano y agregar notas de texto, guardando todo dentro del mismo PDF.

## Instalar en la tablet

1. En la tablet, abre la página **Releases** de este repositorio y descarga `EditorDePDF.apk` de la última versión.
2. Ábrelo. Si Android lo pide, permite **Instalar apps desconocidas** para el navegador (o para «Mis archivos»).
3. Las versiones nuevas se instalan encima de la anterior sin perder nada.
4. Desde ahí, la app avisa sola cuando hay una versión nueva (o usa ⋮ → **Buscar actualizaciones** en la pantalla de inicio) y la descarga e instala con un toque. La primera vez Android pide permiso para que la app instale sus propias actualizaciones.

Cada cambio en `main` publica una versión nueva. Los APK de otras ramas quedan como artefacto en la pestaña **Actions**.

## Cómo se usa

La pantalla de inicio es tu **biblioteca**: muestra la portada de cada PDF, cuánto llevas leído y un acceso para seguir donde quedaste. En el documento, las herramientas están en una barra flotante abajo; el ícono de lápiz, resaltador y subrayado toma el color elegido, y tocar otra vez la herramienta elegida oculta o muestra sus opciones.


| Herramienta | Qué hace |
|---|---|
| Mover | Desplazarse y hacer zoom (también con el S Pen) |
| Lápiz | Escritura a mano, sensible a la presión del S Pen |
| Resaltador | Resaltador transparente. Con «Trazo recto» y «Ajustar al renglón» se acomoda solo a la altura del renglón y a las palabras que tocaste, sin tapar el texto (también en PDFs escaneados) |
| Subrayado | Línea recta que se ubica justo debajo del renglón |
| Texto | Toca la página para escribir una nota. Toca una nota para editarla o borrarla; arrástrala para moverla |
| Borrador | Borra trazos y notas completas al pasar por encima |
| Lazo | Rodea trazos y notas para seleccionarlos: arrástralos para moverlos, toca un color para cambiarlo o bórralos |

### Para estudiar

- **Buscar** (lupa): encuentra una palabra o frase en todo el PDF, sin importar mayúsculas ni tildes, y la marca en la página. No funciona en PDFs escaneados (páginas que son fotos).
- **Panel de estudio** (ícono de lista): índice del libro (si el PDF lo trae), páginas marcadas y la lista de todos tus resaltados, subrayados y notas con el texto que resaltaste. Toca cualquiera para ir a esa página.
- **Marcar página** (ícono de marcador): guarda la página actual para volver rápido.
- **Modo repaso** (ícono de ojo): tapa lo que resaltaste, subrayaste y anotaste. Intenta recordarlo y toca para destapar.
- **Colores con significado**: cada color del resaltador tiene un sentido (amarillo Importante, verde Definición, celeste Ejemplo, rosa Duda, naranja Dato o fecha, violeta Idea propia). Mantén apretado un color para renombrarlo. En la lista de resaltados puedes filtrar por color.
- **Miniaturas**: la pestaña «Páginas» del panel muestra todas las páginas para saltar rápido.
- **Exportar resumen** (menú ⋮ o panel): arma un PDF aparte con todo lo resaltado y anotado, ordenado por página y con su color.
- **Tarjetas de repaso** (menú ⋮ o modo repaso): cada resaltado se vuelve una tarjeta con el texto tapado. Calificas qué tan bien lo recordabas y la app te la vuelve a mostrar en el momento justo (repetición espaciada).

### Constancia

- **Tu estudio** (arriba en la biblioteca): racha de días seguidos estudiando (desde 5 minutos por día), anillo con la meta del día (tócalo para cambiarla) y los minutos de los últimos 7 días. El tiempo cuenta solo mientras usas la app: si pasan 2 minutos sin tocarla, se pausa.
- Al cumplir la meta, la app te avisa con una vibración suave.
- **Recordatorio diario** (botón de la campana en «Tu estudio» o menú ⋮): elige una hora y, si ese día todavía no cumpliste la meta, te llega una notificación para no cortar la racha.

### Uso general

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
