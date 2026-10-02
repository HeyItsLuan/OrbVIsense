# OrbVisense

Android application for **camera and IMU data transmission over WebSocket**, organized under a client-server architecture.

The application is mainly intended for transmitting the data required to initialize **monocular vision** and **monocular-inertial vision** systems through the `/cam0` and `/imu0` topics.

---

# Requirements and Installation

To access and work with the application, **Android Studio** is required.

If working on **Linux**, the following installation procedure is recommended.

## 1. Download Android Studio

Download Android Studio from the official website:

[Download Android Studio — Official Website](https://developer.android.com/studio?hl=en&utm_source=chatgpt.com)

The downloaded file should be similar to:

```text
android-studio-quail4-patch1-linux.tar.gz
```

## 2. Install the required libraries

Open a terminal and run:

```bash
sudo apt update
sudo apt install libc6:i386 libncurses5:i386 libstdc++6:i386 lib32z1 libbz2-1.0:i386
```

## 3. Extract Android Studio

If the downloaded file is located inside `~/Downloads`, run:

```bash
tar -xzf "$HOME/Downloads/android-studio-quail4-patch1-linux.tar.gz" -C "$HOME/Downloads"
```

## 4. Launch Android Studio

To start Android Studio:

```bash
"$HOME/Downloads/android-studio/bin/studio"
```
### Download the Project

To download the OrbVisense project directly from GitHub, clone the repository using:

```bash
git clone https://github.com/HeyItsLuan/OrbVIsense.git
```

Then enter the project directory:

```bash
cd OrbVIsense
```

## 5. Open the OrbVisense project

Once Android Studio has started:

1. Select **Open** from the interface.
2. Select the **`OrbVIsense`** project folder.
3. The project folder must be **previously extracted**.
4. Wait for Android Studio to load and synchronize the project.

---

# Application Features

The application handles **data transmission through a client-server architecture**, using WebSocket as the communication method.

The transmitted data is organized mainly into the following topics:

```text
/cam0
/imu0
```

---

## Camera — `/cam0`

The `/cam0` topic is used to transmit camera images compressed in **JPEG** format.

| Parameter | Characteristic                                  |
| --------- | ----------------------------------------------- |
| Format    | JPEG                                            |
| Frequency | 0–30 FPS                                        |
| Timestamp | Each image contains its corresponding timestamp |

The transmission frequency may be affected by factors such as:

* Receiver server performance.
* Device characteristics and processing capabilities.
* Wi-Fi connection quality.
* Network and transmission conditions.

Therefore, the configured transmission rates may not always be maintained exactly during operation.

---

## IMU — `/imu0`

The `/imu0` topic is used to transmit IMU measurements:

```text
wx  wy  wz
ax  ay  az
```

Each measurement contains its **corresponding timestamp**.

| Parameter | Characteristic                          |
| --------- | --------------------------------------- |
| Data      | wx, wy, wz, ax, ay, az                  |
| Frequency | 50–400 Hz                               |
| Timestamp | Each measurement contains its timestamp |

As with the camera, the effective transmission frequency may be affected by the device, receiver server, and Wi-Fi connection quality.

---

# Transmission Purpose

The main purpose of this tool is to provide an adequate and configurable data stream according to the requirements needed to initialize:

* **Monocular vision systems**
* **Monocular-inertial vision systems**

Communication is performed through **WebSocket**, allowing camera and IMU information to be transmitted simultaneously.

---

# Application Interface

The interface is divided into different panels and controls for monitoring and configuring the transmission.

## Information Panel

The information panel is located in the **upper-left corner** of the screen.

It displays:

* IP address to which the application is configured to connect.
* Number of frames transmitted.
* Number of IMU measurements transmitted.
* Current transmission status.
* Information about possible connection errors with the IO.

### Connection Status

| Spanish           | English          | Description                                                                                              |
| ----------------- | ---------------- | -------------------------------------------------------------------------------------------------------- |
| **Desconectado**  | **Disconnected** | Indicates that the transmission is not connected.                                                        |
| **Conectado**     | **Connecting**   | Indicates that the application is currently establishing the connection to begin transmitting data.      |
| **Transmitiendo** | **Transmitting** | Indicates that the WebSocket connection has been successfully established and data is being transmitted. |

> **Note:** Some elements of the application interface are displayed in Spanish while others are displayed in English.

---

## Flash Activation

The flash activation control is located in the **upper-right corner**.

It provides constant illumination using the camera flash, which can be useful for:

* Testing.
* Low-light environments.

The flash can be activated or deactivated **at any time**, regardless of the current connection state.

---

## Configuration Panel

The configuration panel is located in the **center of the screen**.

It contains two sliders that allow the transmission frequency of the data to be adjusted.

### Camera `/cam0`

The camera transmission frequency can be adjusted between:

```text
0–30 FPS
```

### IMU `/imu0`

The IMU transmission frequency can be adjusted between:

```text
50–400 Hz
```

Both sliders can be modified **at any time**, whether the device is connected or disconnected.

### Connection Configuration

The panel also allows the user to configure:

* **IP address** of the device or server to connect to through WebSocket.
* **Port** used for the connection.

### Image Resolution

The application provides two image resolution options:

```text
640 × 480
1280 × 720
```

---

# Action Panel

Currently, the action panel contains two main functions.

## 1. Record Data

The **"Record Data"** mode allows data to be recorded as if a transmission were taking place, while storing the information locally within the application's files.

Each recording session is stored in a folder with a name similar to:

```text
dataset_#####
```

Each session contains two folders corresponding to the topics:

```text
dataset_#####
├── cam0/
└── imu0/
```

### `/cam0`

The camera folder contains:

```text
cam0/
├── data/
│   ├── <timestamp_1>.png
│   ├── <timestamp_2>.png
│   └── ...
└── <file>.csv
```

The `data` folder contains the recorded images.

Each image uses the **timestamp corresponding to the moment it was captured** as its filename.

The `.csv` file contains the information associated with each image, including:

* Timestamp.
* Image filename.

### `/imu0`

The IMU folder contains a `.csv` file with the recorded measurements:

```text
imu0/
└── <file>.csv
```

The file contains:

```text
timestamp, wx, wy, wz, ax, ay, az
```

This allows each IMU measurement to be associated with its corresponding timestamp.

---

## 2. Connect / Transmit

The **"Connect/Transmit"** button is used to initiate the WebSocket connection.

When activated, the application starts the connection process using the configured IP address and port. Once the connection is successfully established, the application begins transmitting the configured data.

---

# General Operation

The general operation of the application can be represented as follows:

```text
┌─────────────────────┐
│      OrbVisense     │
│     Android App     │
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

The application allows the transmission rates and camera parameters to be configured before or during the connection according to the requirements of the receiving system.

## Camera-IMU Calibration

For the complete camera-IMU calibration procedure, including Allan Variance, Kalibr, dataset preparation, and conversion of the calibration results to ORB-SLAM3 parameters, see the dedicated calibration repository:

**https://github.com/HeyItsLuan/OrbVIsense-calibration**

