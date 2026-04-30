# Mystrix Emulator - Android MIDI Controller

An Android application that emulates a **Mystrix** hardware MIDI controller (by 203 Systems) using Android's USB MIDI peripheral mode. When connected to a PC via USB, the phone appears as a Mystrix MIDI device that can send and receive MIDI signals.

## App Screenshots

<table>
  <tr>
     <td align="center">
      <img src="https://i.ibb.co/Zz6WFngR/launchpad-pro-layout.jpg" width="350"><br>
      <sub><b>Launchpad Pro</b></sub>
    </td> 
    <td align="center">
      <img src="https://i.ibb.co/BV2xqMYb/mystrix-layout.jpg" width="350"><br>
      <sub><b>Mystrix Layout</b></sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="https://i.ibb.co/8nt0L1NX/launchpad-x-layout.jpg" width="350"><br>
      <sub><b>Launchpad X</b></sub>
    </td>
   <td align="center">
      <img src="https://i.ibb.co/33zN5Mf/settings.png" width="350"><br>
      <sub><b>Settings</b></sub>
    </td> 
  </tr>
</table>

## Features

- **8x8 pad grid** matching the Mystrix hardware layout
- **Touchbar emulation** (8 segments on each side of the grid)
- **USB MIDI peripheral mode** - appears as "Mystrix" to connected PCs
- **Drum Rack note mapping** (Performance mode) - compatible with Ableton Live and other DAWs
- **Polyphonic aftertouch** support via touch pressure
- **LED feedback rendering** from DAW:
  - Note-based palette lookup (channels 0-5)
  - Apollo Regular Fill SysEx (0x5E)
  - Apollo Batch Fill SysEx (0x5F)
  - Retina Custom Palette upload (0x41)
- **SysEx Identity Response** for automatic DAW device detection
- **Function button** sends CC121 (Reset All Controllers) + CC123 (All Notes Off)

## Requirements

- Android 6.0 (API 23) or higher
- USB OTG support (for connecting to PC as MIDI peripheral)
- USB cable that supports data transfer

## How It Works

### Sending MIDI (Phone → PC)
When you press a pad on the phone screen:

| Action | MIDI Message |
|--------|-------------|
| Pad Press | Note On (0x90, note, velocity) |
| Pad Hold (pressure change) | Poly Aftertouch (0xA0, note, pressure) |
| Pad Release | Note On vel=0 (0x90, note, 0x00) |
| FN Button | CC 121 val=127 + CC 123 val=0 |

### Receiving MIDI (PC → Phone)
When the DAW sends LED feedback:

| Incoming Message | Effect |
|-----------------|--------|
| Note On Ch0 vel>0 | Look up velocity in Matrix palette → light pad |
| Note On Ch0 vel=0 | Turn pad off |
| Note On Ch1 vel>0 | Look up in LaunchpadX palette |
| CC 123 | Clear all pads |
| SysEx F0 5E... | Apollo Regular Fill |
| SysEx F0 5F... | Apollo Batch Fill |

### Note Mapping (Drum Rack Layout)

```
         x=0  x=1  x=2  x=3  x=4  x=5  x=6  x=7
y=7(top)  64   65   66   67   96   97   98   99
y=6       60   61   62   63   92   93   94   95
y=5       56   57   58   59   88   89   90   91
y=4       52   53   54   55   84   85   86   87
y=3       48   49   50   51   80   81   82   83
y=2       44   45   46   47   76   77   78   79
y=1       40   41   42   43   72   73   74   75
y=0(bot)  36   37   38   39   68   69   70   71
```

## Building

1. Open the project in Android Studio
2. Connect your Android device
3. Build and run

## How to Connect

1. **Open the app** on your phone.
2. **Connect the USB cable** to your phone and your computer.
3. **Set the MIDI connection:** Swipe down from the top of your screen to open the notification panel and tap to select **MIDI**.
4. **Connect to software:** Open your DAW (e.g., Ableton Live), go to your MIDI settings, and select "Midi Function" as the control surface / MIDI input.
5. **Start playing!**

### Troubleshooting: MIDI option is not visible (Xiaomi Devices)

Xiaomi doesn’t show this option by default. Don’t worry though, the functionality still exists in your phone. All you need to do to switch to USB MIDI mode is to go to the Developer Settings menu on your phone and find USB Mode.

The option is called **"Select USB configuration"**. It is under the *Networking* section of the developer options.


## Project Structure

```
MystrixEmulator/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/dev/mystrix/emulator/
│   │   ├── midi/
│   │   │   ├── MidiConstants.kt
│   │   │   ├── MidiLedParser.kt
│   │   │   └── MystrixMidiService.kt
│   │   └── ui/
│   │       ├── MainActivity.kt
│   │       └── PadGridView.kt
│   └── res/
│       ├── layout/activity_main.xml
│       ├── xml/midi_device_info.xml
│       ├── values/ (colors, strings, themes)
│       └── drawable/ (app icon foreground)
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/gradle-wrapper.properties
```

## Based On

- [MatrixOS Firmware](https://github.com/203-Systems/MatrixOS) by 203 Systems
- MIDI protocol reference: see `MatrixOS_MIDI_Protocol_Reference.pdf`

## License

This is an unofficial emulator. Mystrix is a product of 203 Systems.
