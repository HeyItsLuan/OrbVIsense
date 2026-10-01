# OrbVisense

Aplicación Android para la **transmisión de datos de cámara e IMU mediante WebSocket**, organizada bajo una arquitectura cliente-servidor.

La aplicación está orientada principalmente a la transmisión de datos necesarios para iniciar sistemas de **visión monocular** y **visión monocular-inercial**, mediante los topics `/cam0` y `/imu0`.

---

## Requisitos e instalación

Para acceder y trabajar con la aplicación es necesario contar con **Android Studio**.

En caso de trabajar con **Linux**, se recomienda seguir los siguientes pasos para realizar la instalación.

### 1. Descargar Android Studio

Descarga Android Studio desde el sitio oficial:

[Descargar Android Studio — sitio oficial](https://developer.android.com/studio?hl=es-419&utm_source=chatgpt.com)

Al realizar la descarga, se debería obtener un archivo similar a:

```text
android-studio-quail4-patch1-linux.tar.gz
```

### 2. Instalar las bibliotecas necesarias

En una terminal, ejecuta:

```bash
sudo apt update
sudo apt install libc6:i386 libncurses5:i386 libstdc++6:i386 lib32z1 libbz2-1.0:i386
```

### 3. Descomprimir Android Studio

Si el archivo se encuentra dentro de `~/Descargas`, ejecuta:

```bash
tar -xzf "$HOME/Descargas/android-studio-quail4-patch1-linux.tar.gz" -C "$HOME/Descargas"
```

### 4. Ejecutar Android Studio

Para iniciar Android Studio:

```bash
"$HOME/Descargas/android-studio/bin/studio"
```

### 5. Abrir el proyecto OrbVisense

Una vez iniciado Android Studio:

1. Seleccionar **Open** / **Abrir** desde la interfaz.
2. Seleccionar la carpeta del proyecto **`OrbVIsense`**.
3. La carpeta debe estar previamente **descomprimida**.
4. Esperar a que Android Studio cargue y sincronice el proyecto.

---

# Funcionalidades de la aplicación

La aplicación permite manejar el **envío de datos mediante una arquitectura cliente-servidor**, utilizando WebSocket como medio de transmisión.

Los datos se organizan principalmente en los siguientes topics:

```text
/cam0
/imu0
```

---

## Cámara — `/cam0`

El topic `/cam0` permite enviar datos correspondientes a imágenes comprimidas en formato **JPEG**.

Características:

| Parámetro  | Característica                                    |
| ---------- | ------------------------------------------------- |
| Formato    | JPEG                                              |
| Frecuencia | 0–30 FPS                                          |
| Timestamp  | Cada imagen contiene su timestamp correspondiente |

La frecuencia de transmisión puede verse afectada por factores como:

* Capacidad del servidor receptor.
* Características y rendimiento del dispositivo.
* Calidad de la conexión Wi-Fi.
* Condiciones de red y transmisión.

Por este motivo, las tasas configuradas en la aplicación pueden no mantenerse siempre de forma exacta durante la transmisión.

---

## IMU — `/imu0`

El topic `/imu0` permite enviar los datos provenientes de la IMU:

```text
wx  wy  wz
ax  ay  az
```

Cada conjunto de datos contiene su **timestamp correspondiente**.

| Parámetro  | Característica                     |
| ---------- | ---------------------------------- |
| Datos      | wx, wy, wz, ax, ay, az             |
| Frecuencia | 50–400 Hz                          |
| Timestamp  | Cada muestra contiene su timestamp |

Al igual que con la cámara, la frecuencia efectiva de transmisión puede verse afectada por el dispositivo, el servidor receptor y la calidad de la conexión Wi-Fi.

---

## Objetivo de la transmisión

La herramienta permite obtener una recepción de datos adecuada y ajustable a los requerimientos necesarios para iniciar sistemas de:

* **Visión monocular**
* **Visión monocular-inercial**

La comunicación se realiza mediante **WebSocket**, permitiendo transmitir simultáneamente información de cámara e IMU.

---

# Interfaz de la aplicación

La interfaz está dividida en diferentes paneles y controles destinados a supervisar y configurar la transmisión.

## Panel de información

El panel de información se encuentra ubicado en la **esquina superior izquierda**.

Este panel muestra:

* IP a la que se desea conectar.
* Número de cuadros enviados.
* Número de datos IMU enviados.
* Estado actual de la transmisión.
* Información relacionada con posibles errores de conexión con la IO.

### Estados de conexión

| Español           | Inglés           | Descripción                                                                                         |
| ----------------- | ---------------- | --------------------------------------------------------------------------------------------------- |
| **Desconectado**  | **Disconnected** | Indica que la transmisión no se encuentra conectada.                                                |
| **Conectado**     | **Connecting**   | Indica que la aplicación se encuentra en proceso de conexión para iniciar el envío de datos.        |
| **Transmitiendo** | **Transmitting** | Indica que la conexión WebSocket se estableció correctamente y que los datos están siendo enviados. |

> **Nota:** La interfaz de la aplicación contiene elementos tanto en español como en inglés.

---

## Activador de flash

El activador de flash se encuentra ubicado en la **esquina superior derecha**.

Permite proporcionar iluminación constante mediante el flash de la cámara para:

* Realizar pruebas.
* Trabajar en escenarios con poca iluminación.

El flash puede activarse o desactivarse **en cualquier momento**, independientemente del estado de conexión.

---

## Panel de configuración

El panel de configuración se encuentra ubicado en la **parte central de la pantalla**.

Cuenta con dos sliders que permiten variar la frecuencia de envío de datos:

### Cámara `/cam0`

Permite modificar la frecuencia de transmisión de imágenes entre:

```text
0–30 FPS
```

### IMU `/imu0`

Permite modificar la frecuencia de transmisión de datos entre:

```text
50–400 Hz
```

Ambos sliders pueden modificarse **en cualquier momento**, tanto durante la conexión como cuando el dispositivo se encuentra desconectado.

### Configuración de conexión

El panel también permite establecer:

* **IP** del dispositivo o servidor al que se desea conectar mediante WebSocket.
* **Puerto** correspondiente a la conexión.

### Resolución de imagen

La aplicación ofrece dos opciones de resolución:

```text
640 × 480
1280 × 720
```

---

# Panel de acciones

Actualmente, el panel de acciones cuenta con dos funciones principales:

## 1. Grabar datos

El modo **"Grabar datos"** permite realizar una grabación de datos como si la transmisión estuviera ocurriendo, pero almacenando localmente la información dentro de los archivos de la aplicación.

Cada sesión se almacena en una carpeta con un nombre similar a:

```text
dataset_#####
```

Dentro de cada sesión se crean dos carpetas correspondientes a los topics:

```text
dataset_#####
├── cam0/
└── imu0/
```

### `/cam0`

La carpeta de cámara contiene:

```text
cam0/
├── data/
│   ├── <timestamp_1>.jpg
│   ├── <timestamp_2>.jpg
│   ├── <timestamp_3>.jpg
│   └── ...
└── <archivo>.csv
```

La carpeta `data` contiene las imágenes registradas.

Cada imagen utiliza como nombre el **timestamp correspondiente al momento en que fue tomada**.

El archivo `.csv` contiene la información asociada a cada imagen, incluyendo:

* Timestamp.
* Nombre de la imagen.

### `/imu0`

La carpeta de IMU contiene un archivo `.csv` con las mediciones registradas:

```text
imu0/
└── <archivo>.csv
```

El archivo contiene:

```text
timestamp, wx, wy, wz, ax, ay, az
```

De esta manera, cada muestra de IMU queda asociada con su timestamp correspondiente.

---

## 2. Conectar / Transmitir

El botón **"Conectar/Transmitir"** permite iniciar la conexión mediante WebSocket.

Al activarlo, la aplicación comienza el proceso de conexión con la IP y el puerto configurados y, una vez establecida la conexión, inicia la transmisión de los datos configurados.

---

# Estructura general de funcionamiento

De forma general, el flujo de la aplicación puede representarse como:

```text
┌─────────────────────┐
│      OrbVisense     │
│    Android App      │
└──────────┬──────────┘
           │
           │ WebSocket
           │
     ┌─────┴─────┐
     │           │
     ▼           ▼
  /cam0        /imu0
     │           │
     ▼           ▼
 JPEG +       wx wy wz
timestamp     ax ay az
              + timestamp
```

La aplicación permite configurar las tasas de transmisión y las características de la cámara antes o durante la conexión, de acuerdo con los requerimientos del sistema receptor.
